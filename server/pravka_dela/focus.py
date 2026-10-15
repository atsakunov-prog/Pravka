"""Контекст по делу (аудит 15.10.2026, №13): кто связан с делами и как узнать названного — вид context_index.

Вход сверки встреч, окна дайджеста и правки словами (talk) нёс весь справочник: всех живых людей с компанией и
должностью, все открытые дела с заметками, хронологию всех клиентов — 57–65 тыс. токенов на каждую сверку и окно, и
вход рос с каждой карточкой (+100 немых контактов ≈ +2,5 тыс. токенов). Модели нужны не все, а те, о ком речь.

Сервер отдаёт службам и телефону две вещи:
- related — люди, связанные с делами (одна правда о «связан» — здесь): команда; касание за days дней; дела (кто и кто
  просил) открытые и закрытые или изменённые за days дней; сделки — ведущий и люди клиента живых и закрытых за days
  дней; участники встреч за days дней; теплота (cadence, hub); новые карточки за NEW_DAYS дней. Слитый дубль — его
  живая карточка. Видимость — RLS вызывающего;
- ключи имён людей и проектов — чтобы клиент сам нашёл названных в тексте (встреча, окно, наговор) тем же правилом,
  что сервер: матчер (forms) одинаковый у сервера, встреч и телефона (правило RULE). Таблицы окончаний, букв окончаний и
  транслитерации — тоже отсюда (people.py), у клиентов своих копий нет.

Ключи не строже people.who (тест): уменьшительные в обе стороны («Женя» ↔ «Евгений»), падежи («с Соколовым»),
латиница через транслит; падеж — только буквами окончаний («Марина» ≠ «Мария», «Новик» ≠ «Новиков»). Известная дыра:
короткое имя с «ь» в косвенном падеже («у Ильи») — без «ь» оно совпадает с «или»; такие приходят через related или
правило ответа «нет в списке — имя, как названо» (Дела узнают сами).

focus.mentioned — тот же матчер для /api/parse (№67): каталог людей разбора — связанные и названные, а не все.
"""

from __future__ import annotations

import re
from functools import lru_cache

from . import people

RULE = 1
DAYS = 90
NEW_DAYS = 30
PEOPLE_MIN = 3     # ключ человека — от 3 букв (имена из двух — только через related)
PROJECT_MIN = 4    # ключ проекта — от 4 букв, без people.GENERIC («банк», «групп», «ооо»…)
LETTERS = people.ENDING_LETTERS
WORD = re.compile(r"[^0-9a-zа-я]+")
LATIN = re.compile(r"[a-z]")


def clean(s: str) -> str:
    return s.replace("ь", "").replace("ъ", "")


@lru_cache(maxsize=1)
def _diminutives() -> dict[str, frozenset[str]]:
    """Полное имя → его уменьшительные (обратный people.DIMINUTIVE): у «Евгений» — «женя», «жека»."""
    out: dict[str, set[str]] = {}
    for d, fulls in people.DIMINUTIVE.items():
        for f in fulls:
            out.setdefault(f, set()).add(d)
    return {k: frozenset(v) for k, v in out.items()}


def _cut3(w: str) -> str:
    """Окончание падежа срезается, если остаётся ≥ 3 букв («миша» → «миш»; people.stem требует ≥ 4)."""
    for e in people.ENDINGS:
        if len(w) - len(e) >= 3 and w.endswith(e):
            return w[: -len(e)]
    return w


@lru_cache(maxsize=65536)
def _variants(w: str) -> frozenset[str]:
    """Слово карточки и, если оно — имя, его полные формы и уменьшительные в обе стороны; латиница — и транслитом."""
    vs = {w}
    if LATIN.search(w):
        vs.add(people.translit(w))
    for v in list(vs):
        if people.is_first(v):
            vs |= set(people.first_forms(v))
            for f, ds in _diminutives().items():
                if people.same(v, f):
                    vs |= ds
    return frozenset(vs)


@lru_cache(maxsize=65536)
def word_keys(w: str, generic: bool = False, min_len: int = PEOPLE_MIN) -> frozenset[str]:
    """Ключи одного слова карточки (правило RULE): k1 — people.stem, k2 — окончание срезано, если остаётся ≥ 3 букв, и
    k1 без 1–2 последних букв окончаний, если остаётся ≥ 4. Без «ь» и «ъ»; короче min_len — не отдаются."""
    out: set[str] = set()
    for v in _variants(w):
        if generic and v in people.GENERIC:
            continue
        k1, k2 = clean(people.stem(v)), clean(_cut3(v))
        for k in (k1, k2):
            if len(k) >= min_len:
                out.add(k)
        for n in (1, 2):
            if len(k1) - n >= 4 and set(k1[-n:]) <= LETTERS:
                out.add(k1[:-n])
    return frozenset(out)


def keys_of(names, generic: bool = False, min_len: int = PEOPLE_MIN) -> list[str]:
    out: set[str] = set()
    for n in names:
        for w in people.words(n):
            if generic and w in people.GENERIC:
                continue
            out |= word_keys(w, generic, min_len)
    return sorted(out)


@lru_cache(maxsize=65536)
def forms(word: str) -> frozenset[str]:
    """Формы слова текста (матчер, правило RULE): само слово (нижний регистр, ё → е, без «ь» и «ъ», латиница —
    транслитом); оно без каждого подходящего окончания, если остаётся ≥ 3 буквы; каждая из них без 1–2 последних букв
    окончаний, если остаётся ≥ 4. Человек назван, если хоть одна форма хоть одного слова — его ключ."""
    w = people.norm(word)
    if LATIN.search(w):
        w = people.translit(w)
    w = clean(w)
    out = {w}
    for e in people.ENDINGS:
        if w.endswith(e) and len(w) - len(e) >= 3:
            out.add(w[: -len(e)])
    for f in list(out):
        for n in (1, 2):
            if len(f) - n >= 4 and set(f[-n:]) <= LETTERS:
                out.add(f[:-n])
    return frozenset(out)


def found(text: str, index: dict[str, list[str]]) -> set[str]:
    """id из index (ключ → id), названные в тексте."""
    rev: dict[str, set[str]] = {}
    for pid, keys in index.items():
        for k in keys:
            rev.setdefault(k, set()).add(pid)
    got: set[str] = set()
    for w in WORD.split(people.norm(text)):
        if w:
            for f in forms(w):
                got |= rev.get(f, set())
    return got


# ── Справочник ключей ──────────────────────────────────────────────────

_cache: dict[tuple, tuple[dict, dict]] = {}
CACHE_MAX = 16


def keys(conn, user: str) -> tuple[dict, dict]:
    """(ключи людей, ключи проектов) — всех живых видимых карточек. Кэш в процессе по (кто, max seq людей, max seq
    проектов): справочник меняется реже, чем его спрашивают."""
    stamp = conn.execute("SELECT (SELECT max(seq) FROM crm.people) AS p, (SELECT max(seq) FROM crm.projects) AS r").fetchone()
    k = (user, stamp["p"], stamp["r"])
    if k in _cache:
        return _cache[k]
    ppl = conn.execute("SELECT id, name, short, aliases FROM crm.v_people "
                       "WHERE archived_at IS NULL AND merged_into IS NULL").fetchall()
    prj = conn.execute("SELECT id, name, aliases FROM crm.projects WHERE archived_at IS NULL").fetchall()
    pk = {str(r["id"]): ks for r in ppl if (ks := keys_of([r["name"], r["short"] or "", *(r["aliases"] or [])]))}
    rk = {str(r["id"]): ks for r in prj
          if (ks := keys_of([r["name"], *(r["aliases"] or [])], generic=True, min_len=PROJECT_MIN))}
    if len(_cache) >= CACHE_MAX:
        _cache.clear()
    _cache[k] = (pk, rk)
    return pk, rk


RELATED_SQL = """
WITH src AS (
    SELECT person_id AS pid FROM crm.users WHERE person_id IS NOT NULL
    UNION SELECT unnest(person_ids) FROM crm.interactions
     WHERE deleted_at IS NULL AND at >= now() - make_interval(days => %(d)s)
    UNION SELECT person_id FROM tasks.tasks WHERE person_id IS NOT NULL
       AND (status = 'open' OR updated_at >= now() - make_interval(days => %(d)s))
    UNION SELECT requested_by FROM tasks.tasks WHERE requested_by IS NOT NULL
       AND (status = 'open' OR updated_at >= now() - make_interval(days => %(d)s))
    UNION SELECT lead_person_id FROM crm.deals WHERE lead_person_id IS NOT NULL
       AND (stage <> 'archive' OR closed_on >= crm.today() - %(d)s OR updated_at >= now() - make_interval(days => %(d)s))
    UNION SELECT unnest(person_ids) FROM crm.deals
     WHERE stage <> 'archive' OR closed_on >= crm.today() - %(d)s OR updated_at >= now() - make_interval(days => %(d)s)
    UNION SELECT unnest(person_ids) FROM crm.meetings WHERE started_at >= now() - make_interval(days => %(d)s)
    UNION SELECT id FROM crm.v_people WHERE cadence IN ('month', 'quarter', 'year') OR hub
    UNION SELECT id FROM crm.people WHERE created_at >= now() - make_interval(days => %(new)s)
)
SELECT DISTINCT live.id FROM src
JOIN crm.people pe ON pe.id = src.pid
JOIN crm.people live ON live.id = coalesce(pe.merged_into, pe.id)
WHERE live.archived_at IS NULL AND live.merged_into IS NULL
ORDER BY live.id
"""


def related(conn, days: int = DAYS) -> list[str]:
    """Связанные с делами живые карточки, видимые вызывающему (RLS): запрос на каждый вызов — он дешёвый."""
    return [str(r["id"]) for r in conn.execute(RELATED_SQL, {"d": int(days), "new": NEW_DAYS}).fetchall()]


def mentioned(conn, user: str, text: str) -> set[str]:
    """Люди, названные в тексте (тот же матчер, что у встреч и телефона): /api/parse берёт их в каталог разбора."""
    pk, _ = keys(conn, user)
    return found(text or "", pk)


def view_context_index(conn, user: str, days=None, **_) -> dict:
    """Вид context_index (флаг context_index): related, ключи людей и проектов, таблицы матчера. Без заметок, телефонов
    и почты: только id и ключи имён."""
    try:
        d = max(1, min(int(days or DAYS), 365))
    except ValueError:
        d = DAYS
    pk, rk = keys(conn, user)
    return {"rule": RULE, "days": d, "related": related(conn, d), "people": pk, "projects": rk,
            "endings": list(people.ENDINGS), "letters": "".join(sorted(LETTERS)),
            "translit": [list(x) for x in people.TRANSLIT], "translit_y_end": True}


def register(views: dict) -> None:
    views["context_index"] = view_context_index
