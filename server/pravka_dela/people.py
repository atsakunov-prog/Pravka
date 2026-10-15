"""Кто это: одна карточка человека для встреч, звонков, Telegram и разбора (06.10.2026).

Владелец: «Женя из встречи — это тот же Евгений из карточки клиента». До этого каждый узнавал человека
по-своему и только точным совпадением: встречи — строкой спикера, голоса — «Имя Фамилия
(Компания)», телефон — именем контакта, дайджест — именем в Telegram. Здесь одно правило для
всех: номер, Telegram, почта, потом имя — с уменьшительными (Женя = Евгений), падежами
(«с Соколовым») и подсказкой компании в скобках или после «из».

Ответ — кандидаты с баллом и `sure`: уверенно, только когда лучший один и с запасом. Неуверенное
решает владелец — автоматика сама не склеивает людей.
"""

from __future__ import annotations

import re
from functools import lru_cache
from typing import Any

from psycopg.types.json import Jsonb

from . import kovcheg_ui

# Уменьшительное → полные имена. Только то, что реально встречается в речи и контактах;
# неоднозначные (Саша, Женя, Валя) — к обоим полным.
DIMINUTIVE: dict[str, tuple[str, ...]] = {
    "саша": ("александр", "александра"), "шура": ("александр", "александра"), "саня": ("александр", "александра"),
    "женя": ("евгений", "евгения"), "жека": ("евгений",),
    "валя": ("валентин", "валентина"), "слава": ("вячеслав", "святослав", "владислав"),
    "лена": ("елена",), "леночка": ("елена",), "маша": ("мария",), "миша": ("михаил",),
    "дима": ("дмитрий",), "митя": ("дмитрий",), "катя": ("екатерина",), "наташа": ("наталья", "наталия"),
    "сережа": ("сергей",), "серега": ("сергей",), "боря": ("борис",), "рома": ("роман",),
    "леша": ("алексей",), "алеша": ("алексей",), "коля": ("николай",), "вова": ("владимир",), "володя": ("владимир",),
    "таня": ("татьяна",), "оля": ("ольга",), "юля": ("юлия",), "аня": ("анна",), "ира": ("ирина",),
    "света": ("светлана",), "паша": ("павел",), "петя": ("петр",), "костя": ("константин",), "андрюша": ("андрей",),
    "витя": ("виктор",), "гоша": ("георгий", "игорь"), "жора": ("георгий",), "стас": ("станислав",),
    "толя": ("анатолий",), "валера": ("валерий",), "лера": ("валерия",), "галя": ("галина",), "люба": ("любовь",),
    "надя": ("надежда",), "настя": ("анастасия",), "даша": ("дарья",), "ксюша": ("ксения",), "тема": ("артем",),
    "макс": ("максим",), "гена": ("геннадий",), "федя": ("федор",), "илюша": ("илья",), "ваня": ("иван",),
    "гриша": ("григорий",), "вася": ("василий",), "толик": ("анатолий",), "веня": ("вениамин",), "лева": ("лев",), "сеня": ("семен", "арсений"), "вадик": ("вадим",),
    "влад": ("владислав",), "эдик": ("эдуард",), "тоша": ("антон",), "антоша": ("антон",), "тима": ("тимофей", "тимур"),
    "вика": ("виктория",), "соня": ("софья", "софия"), "поля": ("полина",), "алена": ("елена", "алена"),
    "олег": ("олег",), "игорек": ("игорь",), "юра": ("юрий",), "ося": ("иосиф",), "яша": ("яков",),
}

# Окончания падежей, от длинного к короткому: «Соколовым», «Соколову», «Евгению», «Анной».
ENDINGS = ("ыми", "ими", "ому", "ему", "ого", "его", "ой", "ей", "ий", "ым", "им", "ом", "ем", "ою", "ею",
           "ую", "юю", "ая", "яя", "ии", "ия", "ья", "ье", "ью", "у", "ю", "а", "я", "е", "ы", "и", "о", "й")

# Слова-имена: совпадение по ним — не совпадение фамилии («Евгений Иванов» ≠ «Евгений Соколов»).
FIRST_NAMES = set(DIMINUTIVE) | {n for v in DIMINUTIVE.values() for n in v}

SURE = 0.85  # лучший не ниже этого и впереди второго на GAP — уверенно
GAP = 0.15


def norm(s: Any) -> str:
    """Как crm.norm в базе: нижний регистр, ё → е, пробелы схлопнуты."""
    return re.sub(r"\s+", " ", str(s or "").replace("ё", "е").replace("Ё", "Е").lower().strip())


def words(s: Any) -> list[str]:
    return [w for w in re.split(r"[^0-9a-zа-я]+", norm(s)) if w]


@lru_cache(maxsize=65536)
def stem(w: str) -> str:
    """Грубая основа для падежей: «соколовым» → «соколов». Короткие слова не режем."""
    for e in ENDINGS:
        if len(w) - len(e) >= 4 and w.endswith(e):
            return w[: -len(e)]
    return w


FIRST_STEMS: set[str] = set()  # заполняется ниже, после stem()


# Латиница из Zoom и почты — в кириллицу, грубо, но так, чтобы сошлась основа: «Vasiliy Smirnov» —
# «василий смирнов». Мягкий знак при сравнении не считается: в латинице его нет («Belousov»).
TRANSLIT = [("shch", "щ"), ("sch", "щ"), ("yo", "ё"), ("yu", "ю"), ("ya", "я"), ("ye", "е"), ("zh", "ж"),
            ("kh", "х"), ("ts", "ц"), ("ch", "ч"), ("sh", "ш"), ("iy", "ий"), ("yy", "ый"), ("ay", "ай"),
            ("ey", "ей"), ("oy", "ой"), ("a", "а"), ("b", "б"), ("v", "в"), ("w", "в"), ("g", "г"), ("d", "д"),
            ("e", "е"), ("z", "з"), ("i", "и"), ("j", "й"), ("k", "к"), ("l", "л"), ("m", "м"), ("n", "н"),
            ("o", "о"), ("p", "п"), ("r", "р"), ("s", "с"), ("t", "т"), ("u", "у"), ("f", "ф"), ("h", "х"),
            ("c", "к"), ("y", "ы"), ("x", "кс"), ("q", "к")]


def translit(s: str) -> str:
    # «Dmitry», «Vasily», «Anatoly» — на конце после согласной «y» звучит «ий».
    s = re.sub(r"(?<=[bcdfghklmnprstvz])y\b", "iy", norm(s))
    out, i = [], 0
    while i < len(s):
        for lat, cyr in TRANSLIT:
            if s.startswith(lat, i):
                out.append(cyr)
                i += len(lat)
                break
        else:
            out.append(s[i])
            i += 1
    return "".join(out)


@lru_cache(maxsize=65536)
def same(a: str, b: str) -> bool:
    """Одно слово в разных падежах: основы равны или одна продолжает другую на 1–2 буквы
    («евген» и «евгени» из «Евгений» и «Евгением»). Только морфология: род — отдельно (_gender_clash)."""
    na, nb = norm(a), norm(b)
    sa, sb = (stem(x).replace("ь", "").replace("ъ", "") for x in (na, nb))
    if sa == sb:
        return len(sa) >= 3
    lo, hi = sorted((sa, sb), key=len)
    return len(lo) >= 4 and hi.startswith(lo) and len(hi) - len(lo) <= 2


FIRST_STEMS.update(stem(n) for n in FIRST_NAMES)


@lru_cache(maxsize=65536)
def first_forms(w: str) -> frozenset[str]:
    """Имя и его полные формы: «женя» → {женя, евгений, евгения}; «женей» — тоже.

    Кэш (15.10.2026): who для имени одним словом сверяет его с каждой карточкой через все уменьшительные — на 450
    карточках это около секунды на вызов, а форма имени от карточки не зависит."""
    w = norm(w)
    out = {w}
    for d, full in DIMINUTIVE.items():
        if same(w, d):
            out.add(d)
            out.update(full)
    return frozenset(out)


@lru_cache(maxsize=65536)
def same_first(a: str, b: str) -> bool:
    return any(same(x, y) for x in first_forms(a) for y in first_forms(b))


@lru_cache(maxsize=65536)
def is_first(w: str) -> bool:
    """Слово — имя, а не фамилия. Строго по основе: «Петрова» — не «Пётр», «Иванов» — не «Иван»
    (фамилии от имён часты, нестрогое сравнение принимало их за имена и теряло фамилию)."""
    w = norm(w)
    return w in FIRST_NAMES or stem(w) in FIRST_STEMS


# ── Род (аудит 15.10.2026, №22) ─────────────────────────────────────────
# «Евгения Соколова» уверенно находила карточку «Евгений Соколов»: основа у имён общая. Но запрет («разные имена»)
# был слишком строг: «Александра», «Евгения», «Валентина» — это ещё и родительный и винительный от Александр, Евгений,
# Валентин («у Александра Петрова»), и такие запросы перестали находить человека вовсе. Поэтому род — мягко: имя или
# фамилия запроса другого рода, чем у карточки, — балл не выше GENDER_CAP («похоже»): карточка видна кандидатом, но
# сама не ставится. Род имени — по справочнику полных форм (не по окончанию: «Илья» и «Валера» — мужские, «Любовь» —
# женское), уменьшительное — по своим полным формам («Саша», «Женя», «Валя» — оба рода, не судим).
MALE_FULL = frozenset({
    "александр", "евгений", "валентин", "вячеслав", "святослав", "владислав", "михаил", "дмитрий", "сергей", "борис",
    "роман", "алексей", "николай", "владимир", "павел", "петр", "константин", "андрей", "виктор", "георгий", "игорь",
    "станислав", "анатолий", "валерий", "артем", "максим", "геннадий", "федор", "илья", "иван", "григорий", "василий",
    "вениамин", "лев", "семен", "арсений", "вадим", "эдуард", "антон", "тимофей", "тимур", "олег", "юрий", "иосиф",
    "яков"})
FEMALE_FULL = frozenset({
    "александра", "евгения", "валентина", "елена", "мария", "екатерина", "наталья", "наталия", "татьяна", "ольга",
    "юлия", "анна", "ирина", "светлана", "валерия", "галина", "любовь", "надежда", "анастасия", "дарья", "ксения",
    "виктория", "софья", "софия", "полина", "алена"})
GENDER_CAP = 0.8  # ниже SURE: кандидат, но не уверенно

# Фамилия: какого рода форма. У карточки — именительный; у запроса — любой падеж, поэтому только формы одного рода:
# «Соколовой» — только женская, «Соколовым», «Соколове» — только мужская; «Соколова» и «Соколову» — обоих (родительный
# и дательный мужской, именительный и винительный женской), по ним не судим.
SURNAME_NOM = ((re.compile(r"(ов|ев|ин|ын|ский|цкий)$"), "m"), (re.compile(r"(ова|ева|ина|ына|ская|цкая)$"), "f"))
SURNAME_ANY = ((re.compile(r"(ов|ев|ин|ын|ский|цкий)$"), "m"), (re.compile(r"(ов|ев|ин|ын)(ым|е)$"), "m"),
               (re.compile(r"(ск|цк)(ого|ому|им|ом)$"), "m"),
               (re.compile(r"(ов|ев|ин|ын)ой$"), "f"), (re.compile(r"(ск|цк)ую$"), "f"))


@lru_cache(maxsize=4096)
def name_gender(w: str) -> str | None:
    """Род имени в именительном по справочнику: «m», «f» или None (не имя, оба рода, косвенный падеж)."""
    w = norm(w)
    if w in MALE_FULL:
        return "m"
    if w in FEMALE_FULL:
        return "f"
    full = DIMINUTIVE.get(w) or ()
    g = {"m" if x in MALE_FULL else "f" if x in FEMALE_FULL else "?" for x in full}
    return next(iter(g)) if len(g) == 1 and "?" not in g else None


def _surname_gender(w: str, nominative: bool) -> str | None:
    if len(w) < 4:
        return None
    for rx, g in SURNAME_NOM if nominative else SURNAME_ANY:
        if rx.search(w):
            return g
    return None


def _gender_clash(qw: list[str], nw: list[str]) -> bool:
    """Совпавшие слова запроса и карточки — разного рода: имя («Евгения» и «Евгений», «Валерия» и «Валерий» через
    «Валеру») или фамилия («Соколовой» и «Соколов»)."""
    for a in qw:
        for b in nw:
            if is_first(a) or is_first(b):
                ga, gb = name_gender(a), name_gender(b)
                if ga and gb and ga != gb and a != b and same_first(a, b):
                    return True
            else:
                ga, gb = _surname_gender(a, False), _surname_gender(b, True)
                if ga and gb and ga != gb and same(a, b):
                    return True
    return False


def _first_only(q: str) -> bool:
    """Запрос — одно имя («Дмитрий», «Женя»)."""
    ws = words(q)
    return len(ws) == 1 and is_first(ws[0])


def phone_key(s: Any) -> str | None:
    """Последние 10 цифр: +7 916…, 8 916… и 916… — один номер. Короче 7 цифр — не номер."""
    d = re.sub(r"\D", "", str(s or ""))
    return d[-10:] if len(d) >= 7 else None


def split_hint(q: str) -> tuple[str, str]:
    """«Евгений Соколов (ромашка)» и «Женя Соколов из Ромашки» → («евгений соколов», «ромашки»)."""
    q = norm(q)
    hint = ""
    m = re.search(r"\(([^)]*)\)\s*$", q)
    if m:
        hint, q = m.group(1).strip(), q[: m.start()].strip()
    m = re.search(r"\s(?:из|from)\s+(.+)$", q)
    if m and not hint:
        hint, q = m.group(1).strip(), q[: m.start()].strip()
    return q, hint


# Слова, которые компанию не отличают: «Петров (банк)» не должен поднимать всех Петровых из банков.
GENERIC = {"банк", "групп", "группа", "компания", "ооо", "ао", "пао", "зао", "ип", "холдинг", "фонд", "бизнес"}


def _initials(name: str) -> str:
    """«Знакомый Финансист» → «зф»: так компанию зовут в скобках у спикеров."""
    ws = words(name)
    return "".join(w[0] for w in ws) if len(ws) >= 2 else ""


def _hint_hit(hint: str, names: list[str]) -> bool:
    """Подсказка в скобках или после «из» подтверждает человека: компания (любое отличительное
    слово — «финдир Ромашки» тоже Ромашка), аббревиатура компании («ЗФ») или роль («терапевт»)."""
    if not hint:
        return False
    hw = [stem(w) for w in words(hint) if len(w) >= 3 and w not in GENERIC]
    compact = "".join(words(hint))
    for n in names:
        # «ЗФ» — и аббревиатура «Знакомого финансиста», и компания, которая так и записана.
        if compact and len(compact) >= 2 and compact in (_initials(n), "".join(words(n))):
            return True
        ow = [stem(w) for w in words(n) if len(w) >= 3 and w not in GENERIC]
        if any(o.startswith(h) or h.startswith(o) for h in hw for o in ow):
            return True
    return False


def _name_score(q: str, person: dict) -> float:
    """Насколько текст q похож на имя человека: 1 — точное имя или другое имя, 0 — нет."""
    return max(_name_parts(q, person))


def _name_parts(q: str, person: dict) -> tuple[float, float]:
    """Похожесть q отдельно на своё имя карточки (name, short) и на её другие имена (aliases).

    Аудит 15.10.2026, №1: короткое имя сотрудника («Ваня») стало другим именем второй карточки из сверки контактов —
    и узнавание по «Ване» встало для обоих. Своё имя сильнее чужого другого имени: who разводит такую ничью (sure), а
    балл не меняется — одиночное совпадение другим именем (имя из Telegram) остаётся уверенным."""
    own = [person.get("name"), person.get("short")]
    return _names_score(q, own), _names_score(q, person.get("aliases") or [])


def _names_score(q: str, raw: list) -> float:
    """Имя и фамилия сверяются парой в обоих порядках («Соколов Евгений» тоже он): фамилия — основой
    (падеж не мешает), имя — через уменьшительные. Одно слово — фамилия средне, имя слабо.
    """
    if not q:
        return 0.0
    names = [norm(n) for n in raw if n]
    if q in names:
        return 1.0
    qw = words(q)
    best = 0.0
    for n in names:
        nw = words(n)
        if not nw:
            continue
        s = _word_score(qw, nw)
        if s > GENDER_CAP and _gender_clash(qw, nw):
            s = GENDER_CAP  # другой род — «похоже», не уверенно (аудит 15.10.2026, №22)
        best = max(best, s)
    return best


def _word_score(qw: list[str], nw: list[str]) -> float:
    """Похожесть слов запроса на слова одного имени карточки (без рода)."""
    if len(qw) == len(nw) and all(same(a, b) for a, b in zip(qw, nw)):
        return 0.95
    best = 0.0
    if len(qw) >= 2 and len(nw) >= 2:
        for qf, ql in ((qw[0], qw[-1]), (qw[-1], qw[0])):
            for nf, nl in ((nw[0], nw[-1]), (nw[-1], nw[0])):
                # Инициал вместо имени или фамилии — «a.komarov», «Dmitry L» из Zoom и почты.
                if len(qf) == 1 and nf.startswith(qf) and not is_first(ql) and same(ql, nl):
                    best = max(best, 0.85)
                    continue
                if len(ql) == 1 and nl.startswith(ql) and is_first(nf) and same_first(qf, nf):
                    best = max(best, 0.85)
                    continue
                if is_first(ql) or not same(ql, nl):
                    continue
                best = max(best, 0.9 if same_first(qf, nf) else 0.5)
    elif len(qw) == 1:
        w = qw[0]
        if not is_first(w) and any(same(w, b) for b in nw if not is_first(b)):
            best = 0.6
        elif len(nw) == 1 and same_first(w, nw[0]):
            best = 0.7  # короткое имя человека («Женя») — само по себе имя
        elif same_first(w, nw[0]):
            best = 0.35
    return best


def who_rows(conn) -> list[dict]:
    """Люди, среди которых ищет who: видимые человеку (RLS), живые — архив и слитые нет."""
    return conn.execute(
        "SELECT pe.id, pe.name, pe.short, pe.aliases, pe.phones, pe.emails, pe.telegram_id, pe.telegram_username, "
        "pe.org_id, pe.role, pe.user_id, o.name AS org_name, o.aliases AS org_aliases "
        "FROM crm.people pe LEFT JOIN crm.orgs o ON o.id = pe.org_id "
        "WHERE pe.archived_at IS NULL AND pe.merged_into IS NULL"
    ).fetchall()


def who(conn, q: str | None = None, phone: str | None = None, telegram_id: Any = None,
        telegram: str | None = None, email: str | None = None, limit: int = 5, rows: list[dict] | None = None) -> dict:
    """Кто это. Видимые человеку люди (RLS), живые: архив и слитые — нет.

    rows — уже прочитанные who_rows: дайджест узнаёт за раз десятки имён (digest_crm.py), и каждый раз читать всех
    людей заново незачем; правило узнавания то же."""
    rows = who_rows(conn) if rows is None else rows
    pk = phone_key(phone)
    tg_user = norm(telegram).lstrip("@") if telegram else ""
    mail = norm(email) if email else ""
    q_name, hint = split_hint(q or "")
    scored = []
    for p in rows:
        s, how, alias_only = 0.0, "", False
        if pk and any(phone_key(x) == pk for x in p["phones"] or []):
            s, how = 1.0, "номер"
        elif telegram_id and p["telegram_id"] and str(p["telegram_id"]) == str(telegram_id):
            s, how = 1.0, "telegram"
        elif tg_user and norm(p["telegram_username"]).lstrip("@") == tg_user:
            s, how = 1.0, "telegram"
        elif mail and mail in [norm(x) for x in p["emails"] or []]:
            s, how = 1.0, "почта"
        else:
            own, alias = _name_parts(q_name, p)
            if re.search(r"[a-z]", q_name):
                o2, a2 = _name_parts(translit(q_name), p)  # подпись из Zoom латиницей
                own, alias = max(own, o2), max(alias, a2)
            s, alias_only = max(own, alias), alias > own
            # how: «имя» — совпало своё имя или короткое имя карточки, «другое имя» — только её aliases.
            how = ("другое имя" if alias_only else "имя") if s >= 0.95 else "похоже"
            orgs = [x for x in [p["org_name"], *(p["org_aliases"] or [])] if x]
            if s and hint:
                # Компания или роль подтверждает — увереннее, а одно имя с ней («Дмитрий (финдир
                # Ромашки)») — почти наверняка: уникальность всё равно проверит GAP. У человека другая
                # компания — слабее; компании нет — подсказке не с чем спорить («Анна Смирнова
                # (Цветочная лавка)» — это она).
                if _hint_hit(hint, orgs + [p["role"] or ""]) or _hint_hit(translit(hint), orgs + [p["role"] or ""]):
                    s = max(min(1.0, s + 0.2), 0.9 if _first_only(q_name) else 0.0)
                elif orgs:
                    s -= 0.15
        if s > 0.3:
            scored.append((round(s, 2), how, alias_only, p))
    # При равных баллах своё имя — впереди чужого другого имени; дальше по имени и id — порядок не зависит от того, как
    # база отдала строки (кнопки «Кто это — X или Y?» не должны прыгать от запроса к запросу).
    scored.sort(key=lambda t: (-t[0], t[2], norm(t[3]["name"]), str(t[3]["id"])))
    cands = [
        {"id": p["id"], "name": p["name"], "short": p["short"], "org": p["org_name"], "role": p["role"],
         "user_id": p["user_id"], "score": s, "how": how}
        for s, how, _, p in scored[:limit]
    ]
    sure = False
    if scored and scored[0][0] >= SURE:
        rivals = [t for t in scored[1:] if scored[0][0] - t[0] < GAP]
        # Развязка (№1): лучший совпал своим name или short, а все, кто рядом, — только своим другим именем.
        sure = not rivals or (scored[0][1] == "имя" and all(t[2] for t in rivals))
    return {"best": cands[0] if cands else None, "sure": sure, "candidates": cands, "hint": hint or None}


# ── Операции ────────────────────────────────────────────────────────────

ADD_ARRAYS = ("aliases", "phones", "emails")


def own_aliases(conn, pid, aliases: list, ignore: tuple = ()) -> tuple[list[str], list[dict]]:
    """Другие имена, которые можно записать карточке pid (None — новой): без имён и коротких имён других живых
    карточек. Ответ — (что писать, skipped [{alias, person_id, name}]). Одно правило для person.add и новой карточки
    «Людей сами» и встреч (аудит 15.10.2026, №1)."""
    aliases = [str(x).strip() for x in aliases or [] if str(x or "").strip()]
    others = _owned_names(conn, pid, aliases, ignore)
    keep, skipped = [], []
    for x in aliases:
        k = norm(x)
        if k in others:
            if all(norm(s["alias"]) != k for s in skipped):
                skipped.append({"alias": x, "person_id": others[k]["id"], "name": others[k]["name"]})
            continue
        keep.append(x)
    return keep, skipped


def _owned_names(conn, pid, names: list[str], ignore: tuple = ()) -> dict[str, dict]:
    """Какие из names — имя или короткое имя ДРУГОЙ живой карточки (не архив, не слитая): {норма: та карточка}."""
    keys = sorted({norm(x) for x in names if norm(x)})
    if not keys:
        return {}
    rows = conn.execute(
        "SELECT id, name, short FROM crm.people WHERE archived_at IS NULL AND merged_into IS NULL "
        "AND id IS DISTINCT FROM %s::uuid AND NOT (id = ANY(%s::uuid[])) "
        "AND (replace(crm.norm(name), 'ё', 'е') = ANY(%s) OR replace(crm.norm(short), 'ё', 'е') = ANY(%s)) "
        "ORDER BY created_at, id",
        (pid, [str(x) for x in ignore], keys, keys),
    ).fetchall()
    out: dict[str, dict] = {}
    for r in rows:
        for k in (norm(r["name"]), norm(r["short"])):
            if k in keys:
                out.setdefault(k, r)
    return out


def op_person_add(conn, user, op, error, ignore: tuple = ()):
    """Дописать человеку другие имена, номера, почту и Telegram — без затирания чужого.

    Встречи, звонки и дайджест знают только «ещё одно имя этого человека»: перезапись массива
    целиком (person.set) съела бы то, что дописал другой. Повтор — безвреден: что уже есть
    (с точностью до регистра и формы номера), не дублируется. Telegram ставится, только если пуст.

    Другое имя, которое уже есть имя или короткое имя другой живой карточки, не дописывается (аудит 15.10.2026, №1:
    сверка контактов дописала второй карточке короткое имя сотрудника — и «Ваня» перестал узнаваться у обоих). Ответ
    говорит об этом в skipped: [{alias, person_id, name}] — кто так зовётся; старые клиенты поле не читают.
    ignore — чьи имена не мешают: слияние дописывает живой карточке имя дубля, пока дубль ещё не убран.
    """
    pid = op.get("id")
    row = conn.execute(
        "SELECT id, name, short, aliases, phones, emails, telegram_id, telegram_username FROM crm.people WHERE id = %s",
        (pid,),
    ).fetchone()
    if row is None:
        raise error("человек: нет такого или нет прав")
    data = op.get("add") or {}
    if not isinstance(data, dict):
        raise error("человек: add — словарь")
    sets: dict[str, Any] = {}
    taken_names = {norm(row["name"]), norm(row["short"])}
    new_aliases = data.get("aliases") or []
    if isinstance(new_aliases, str):
        new_aliases = [new_aliases]
    others = _owned_names(conn, pid, [str(x) for x in new_aliases], ignore)
    skipped: list[dict] = []
    for col in ADD_ARRAYS:
        new = data.get(col) or []
        if isinstance(new, str):
            new = [new]
        have = list(row[col] or [])
        key = phone_key if col == "phones" else norm
        seen = {key(x) for x in have} | (taken_names if col == "aliases" else set())
        for x in new:
            x = str(x).strip()
            k = key(x)
            if col == "aliases" and k in others and k not in seen:
                o = others[k]
                if all(norm(s["alias"]) != k for s in skipped):
                    skipped.append({"alias": x, "person_id": o["id"], "name": o["name"]})
                continue
            if x and k and k not in seen:
                have.append(x)
                seen.add(k)
        if have != list(row[col] or []):
            sets[col] = have
    if data.get("telegram_id") and not row["telegram_id"]:
        sets["telegram_id"] = int(data["telegram_id"])
    if data.get("telegram_username") and not row["telegram_username"]:
        sets["telegram_username"] = str(data["telegram_username"]).lstrip("@")
    if not sets:
        return {"row": row, "changed": False, "skipped": skipped}
    cols = ", ".join(f"{k} = %s" for k in sets)
    out = conn.execute(f"UPDATE crm.people SET {cols} WHERE id = %s RETURNING *", [*sets.values(), pid]).fetchone()
    if out is None:
        raise error("человек: нет прав менять")
    return {"row": out, "changed": True, "skipped": skipped}


# Где живут ссылки на человека: (таблица, колонка, массив ли).
REFS = (
    ("tasks.tasks", "person_id", False),
    ("tasks.tasks", "requested_by", False),
    ("crm.deals", "lead_person_id", False),
    ("crm.deals", "source_person_id", False),
    ("crm.deals", "person_ids", True),
    ("crm.interactions", "person_ids", True),
)


def op_person_merge(conn, user, op, error):
    """Слить дубль в живую карточку: ссылки переезжают, имена и номера дописываются, дубль — в архив.

    Строки не удаляются (правило 8), поэтому дубль остаётся с merged_into: встреча или голос,
    помнящие старый id, найдут живого через него.
    """
    dup, into = op.get("id"), op.get("into")
    if not dup or not into or str(dup) == str(into):
        raise error("слияние: нужны id дубля и into — живая карточка, и это разные люди")
    a = conn.execute("SELECT * FROM crm.people WHERE id = %s", (dup,)).fetchone()
    b = conn.execute("SELECT * FROM crm.people WHERE id = %s", (into,)).fetchone()
    if a is None or b is None:
        raise error("слияние: нет такого человека или нет прав")
    if b["merged_into"] or b["archived_at"]:
        raise error("слияние: живая карточка сама в архиве — сливать в неё нельзя")
    if a["user_id"] and b["user_id"]:
        raise error("слияние: оба — пользователи Дел, такое решает владелец руками")
    moved = 0
    for table, col, is_array in REFS:
        if is_array:
            n = conn.execute(
                f"UPDATE {table} SET {col} = ARRAY(SELECT DISTINCT unnest(array_replace({col}, %s::uuid, %s::uuid))) "
                f"WHERE %s::uuid = ANY({col})", (dup, into, dup),
            ).rowcount
        else:
            n = conn.execute(f"UPDATE {table} SET {col} = %s WHERE {col} = %s", (into, dup)).rowcount
        moved += n
    add = {
        "aliases": [x for x in [a["name"], a["short"], *(a["aliases"] or [])] if x],
        "phones": a["phones"] or [], "emails": a["emails"] or [],
        "telegram_id": a["telegram_id"], "telegram_username": a["telegram_username"],
    }
    skipped = op_person_add(conn, user, {"id": into, "add": add}, error, ignore=(dup,))["skipped"]
    fill ={k: a[k] for k in ("org_id", "role", "birth_day", "birth_month", "birth_year", "note", "user_id")
            if a[k] is not None and b[k] is None}
    if a["user_id"]:
        # user_id уникален: снять с дубля до того, как поставить живому.
        conn.execute("UPDATE crm.people SET user_id = NULL WHERE id = %s", (dup,))
        conn.execute("UPDATE crm.users SET person_id = %s WHERE id = %s", (into, a["user_id"]))
    if fill:
        cols = ", ".join(f"{k} = %s" for k in fill)
        conn.execute(f"UPDATE crm.people SET {cols} WHERE id = %s", [*fill.values(), into])
    conn.execute(
        "UPDATE crm.people SET merged_into = %s, archived_at = coalesce(archived_at, now()) WHERE id = %s", (into, dup)
    )
    row = conn.execute("SELECT * FROM crm.people WHERE id = %s", (into,)).fetchone()
    # Имена дубля, которые так зовут кого-то ещё, живой не дописаны (№1) — сказать, а не потерять молча.
    return {"row": row, "moved": moved, "skipped": skipped}


# ── Свод ────────────────────────────────────────────────────────────────

SVOD_KEY = re.compile(r"^[a-z][a-z0-9_.-]{0,79}$")


def op_svod_set(conn, user, op, error):
    """Новая версия записи Свода. base_rev — какую версию видел пишущий.

    Владелец: «если я что-то на телефоне поменял, но после сервера, то это тоже должно уходить».
    Поэтому по умолчанию побеждает последний. Автоматы, которые переписывают целое по своей
    копии (тюнер промпта, словарь), шлют base_rev: если сервер ушёл вперёд — отказ с текущей
    версией, автомат сливает и шлёт снова, а не затирает чужое.
    """
    key = str(op.get("key") or "")
    if not SVOD_KEY.match(key):
        raise error("свод: ключ — латиница, цифры, точка, дефис: prompt.clean, dict.main")
    body, value = op.get("body"), op.get("value")
    if (body is None) == (value is None):
        raise error("свод: нужен ровно один из body (текст) и value (JSON)")
    if body is not None and not isinstance(body, str):
        raise error("свод: body — строка")
    author = str(op.get("author") or "").strip() or "app"
    owner = op.get("owner") or user
    if key.startswith("ui."):
        # Вид и меню Ковчега (этап 7, kovcheg_ui.py): одни на всех, правит владелец; проверка — до записи, иначе кривое
        # меню уронило бы шапку на всех страницах и в телефоне разом. Команде база запись не запретила бы (свой Свод),
        # но её никто не читает — лучше сказать прямо, чем молча принять.
        role = conn.execute("SELECT role FROM crm.users WHERE id = %s", (owner,)).fetchone()
        if not role or role["role"] != "owner":
            raise error("свод: вид и меню Ковчега (ui.*) правит владелец")
        try:
            value = kovcheg_ui.check(key, body, value)
        except kovcheg_ui.UiError as e:
            raise error(f"свод: {e}") from None
    cur = conn.execute("SELECT rev FROM crm.svod WHERE owner_id = %s AND key = %s", (owner, key)).fetchone()
    base = op.get("base_rev")
    if base is not None and cur is not None and int(base) != cur["rev"]:
        raise error(f"свод: {key} на сервере уже версии {cur['rev']}, а правка — поверх {base}: возьми свежую и слей")
    if base is not None and cur is None and int(base) != 0:
        raise error(f"свод: {key} на сервере нет, а правка — поверх версии {base}")
    row = conn.execute(
        "INSERT INTO crm.svod (owner_id, key, body, value, author, reason) VALUES (%s, %s, %s, %s, %s, %s) "
        "ON CONFLICT (owner_id, key) DO UPDATE SET body = EXCLUDED.body, value = EXCLUDED.value, "
        "author = EXCLUDED.author, reason = EXCLUDED.reason RETURNING *",
        (owner, key, body, Jsonb(value) if value is not None else None, author, op.get("reason")),
    ).fetchone()
    if row is None:
        raise error("свод: нет прав")
    return {"row": row}


def view_svod(conn, user, key=None, prefix=None, **_):
    """Записи Свода: одна по ключу, все с префиксом или все. Только свои (RLS)."""
    q = "SELECT owner_id, key, body, value, author, reason, updated_at, rev, seq FROM crm.svod WHERE owner_id = %s"
    params: list = [user]
    if key:
        q += " AND key = %s"
        params.append(key)
    elif prefix:
        q += " AND key LIKE %s"
        params.append(prefix.replace("%", "") + "%")
    return {"items": conn.execute(q + " ORDER BY key", params).fetchall()}


def view_who(conn, user, q=None, phone=None, telegram_id=None, telegram=None, email=None, **_):
    return who(conn, q=q, phone=phone, telegram_id=telegram_id, telegram=telegram, email=email)
