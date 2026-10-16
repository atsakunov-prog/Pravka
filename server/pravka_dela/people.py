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

import psycopg
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


# Буквы окончаний падежей: только такой «хвост» основы — остаток окончания того же слова (сквозной тест №25/№60,
# 15.10.2026). Согласной вне окончаний («н», «в») там не бывает: «мари» + «н» — это другое имя, а не падеж.
ENDING_LETTERS = frozenset("".join(ENDINGS)) | {"ь", "ъ"}


@lru_cache(maxsize=65536)
def same(a: str, b: str) -> bool:
    """Одно слово в разных падежах: основы равны или одна продолжает другую на 1–2 буквы окончания
    («евген» и «евгени» из «Евгений» и «Евгением»). Только морфология: род — отдельно (_gender_clash).

    Продолжение — только буквами окончаний (ENDING_LETTERS): до 15.10.2026 «Марина» была «Марией» (основы «марин» и
    «мари»), и сквозной тест №25/№60 показал, чем это кончается: спикер «Марина (Бета)» уверенно встал к карточке
    «Мария», а «Марина» записалась ей другим именем. Так же «Новик» был «Новиковым», «Антонина» — «Антоном»."""
    na, nb = norm(a), norm(b)
    sa, sb = (stem(x).replace("ь", "").replace("ъ", "") for x in (na, nb))
    if sa == sb:
        return len(sa) >= 3
    lo, hi = sorted((sa, sb), key=len)
    return (len(lo) >= 4 and hi.startswith(lo) and len(hi) - len(lo) <= 2
            and set(hi[len(lo):]) <= ENDING_LETTERS)


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

# Подсказка в скобках называет не компанию карточки — кандидат, но не «уверенно» (этап 6 аудита, п. 6, 15.10.2026). До
# того спор скобок снимал 0,15, и от 1,0 оставалось 0,85 — ровно SURE: спикер «Ольга (Бета)» уверенно вставал к карточке
# «Ольги» из другой компании, хотя голос с этой карточкой служба встреч отвергла именно за компанию.
HINT_CAP = 0.8
# Подсказка — родство или роль у близких, а не компания: «Сергей (отец)», «Анна (терапевт)». С компанией карточки она не
# спорит: иначе «папа» из ЗФ стал бы вопросом при каждой встрече.
KIN = frozenset("""
отец папа мама мать жена муж сын дочь брат сестра бабушка дедушка теща тесть свекровь свекор тетя дядя племянник
племянница крестный крестная терапевт психолог психотерапевт врач доктор няня репетитор тренер учитель
""".split())
KIN_STEMS = frozenset(stem(w) for w in KIN)  # «(папы Бори)», «(мамой)» — падежи тоже родство


def _kin(hint: str) -> bool:
    return any(w in KIN or stem(w) in KIN_STEMS for w in words(hint))


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
    alias = _names_score(q, person.get("aliases") or [])
    qw = words(q)
    if alias > HINT_CAP and len(qw) == 1 and not is_first(qw[0]):
        # Одна фамилия совпала только с другим именем — так в карточке лежат ослышки расшифровки («Образцовская» другим
        # именем у «Образцовой», этап 6 аудита, п. 6): по ней одной не «уверенно», иначе к этой карточке уйдёт и
        # будущий человек с настоящей такой фамилией.
        alias = HINT_CAP
    return _names_score(q, own), alias


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


MERGED_HOW = "номер слитой карточки"  # ключ (номер, Telegram, почта) дубля, слитого в эту карточку (аудит №23)
KEY_HOWS = ("номер", "telegram", "почта", MERGED_HOW)  # совпало железом, а не именем


def who_rows(conn, with_merged: bool = False) -> list[dict]:
    """Люди, среди которых ищет who: видимые человеку (RLS), живые — архив и слитые нет.

    with_merged — и слитые дубли (merged_into) — только ради их ключей (аудит №23): слияние не переносит telegram_id, ник
    и номер дубля, если у живой свои, а встреча, звонок или Telegram помнят ключ дубля. По имени слитые не участвуют."""
    cols = ("pe.id, pe.name, pe.short, pe.aliases, pe.phones, pe.emails, pe.telegram_id, pe.telegram_username, "
            "pe.org_id, pe.role, pe.user_id, pe.merged_into, o.name AS org_name, o.aliases AS org_aliases "
            "FROM crm.v_people pe LEFT JOIN crm.orgs o ON o.id = pe.org_id ")  # поля по ярусу: коллегу по номеру не узнать
    rows = conn.execute(f"SELECT {cols} WHERE pe.archived_at IS NULL AND pe.merged_into IS NULL").fetchall()
    if with_merged:
        rows += conn.execute(f"SELECT {cols} WHERE pe.merged_into IS NOT NULL").fetchall()
    return rows


def _key_how(p: dict, pk: str | None, telegram_id: Any, tg_user: str, mail: str) -> str:
    """Чем карточка совпала железом: номер, Telegram, почта — или пусто."""
    if pk and any(phone_key(x) == pk for x in p["phones"] or []):
        return "номер"
    if telegram_id and p["telegram_id"] and str(p["telegram_id"]) == str(telegram_id):
        return "telegram"
    if tg_user and norm(p["telegram_username"]).lstrip("@") == tg_user:
        return "telegram"
    if mail and mail in [norm(x) for x in p["emails"] or []]:
        return "почта"
    return ""


def who(conn, q: str | None = None, phone: str | None = None, telegram_id: Any = None,
        telegram: str | None = None, email: str | None = None, limit: int = 5, rows: list[dict] | None = None) -> dict:
    """Кто это. Видимые человеку люди (RLS), живые: архив и слитые — нет.

    Номер, Telegram и почта слитого дубля ведут в живую карточку (how «номер слитой карточки», аудит №23); по имени
    слитые не участвуют — их имена слияние уже дописало живой.

    rows — уже прочитанные who_rows: дайджест узнаёт за раз десятки имён (digest_crm.py), и каждый раз читать всех
    людей заново незачем; правило узнавания то же."""
    pk = phone_key(phone)
    tg_user = norm(telegram).lstrip("@") if telegram else ""
    mail = norm(email) if email else ""
    keyed = bool(pk or telegram_id or tg_user or mail)
    rows = who_rows(conn, with_merged=keyed) if rows is None else rows
    live = [p for p in rows if not p.get("merged_into")]
    q_name, hint = split_hint(q or "")
    scored = []
    for p in live:
        s, how, alias_only = 0.0, _key_how(p, pk, telegram_id, tg_user, mail), False
        if how:
            s = 1.0
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
                elif orgs and not _kin(hint):
                    s = min(s - 0.15, HINT_CAP)  # чужая компания — кандидат, не «уверенно» (п. 6 этапа 6, HINT_CAP)
        if s > 0.3:
            scored.append((round(s, 2), how, alias_only, p))
    if keyed:
        # Ключ слитого дубля — его живая карточка (№23), если она видна и своим ключом уже не совпала.
        by_id = {str(p["id"]): p for p in live}
        hit = {str(t[3]["id"]) for t in scored if t[1] in KEY_HOWS}
        for p in rows:
            target = by_id.get(str(p.get("merged_into") or ""))
            if target is None or str(target["id"]) in hit or not _key_how(p, pk, telegram_id, tg_user, mail):
                continue
            scored = [t for t in scored if str(t[3]["id"]) != str(target["id"])]
            scored.append((1.0, MERGED_HOW, False, target))
            hit.add(str(target["id"]))
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


# ── Новый человек со встречи (аудит 15.10.2026, №25/№60) ──────────────────

# Владелец: «Назвал человека в расшифровке… — он должен оказаться в CRM… Нет — карточка заводится (неясно — один вопрос в
# „Подскажи“)». Решает сервер, а не служба встреч: узнавание людей одно (закон 3), и справочника имён у службы нет.
ASK_MIN = 0.6  # похожий не слабее — вопрос «Это X?»; однофамилец с другим именем (0,5) — новый человек, не вопрос
# Подписи, которые не имя человека: метки разметки, устройства, «участник 3» — с начала слова (тот же список, что у
# службы встреч, worker/people.py GENERIC: она шум и не шлёт, здесь — страховка).
NOISE = ("speaker", "спикер", "участник", "гость", "guest", "unknown", "неизвестн", "user", "zoom", "iphone", "ipad",
         "android")
NEW_ASK_MAX = 2  # «Это X» кнопками: два кандидата, «Новый человек» и «Не для CRM» — четыре кнопки (OPTIONS_MAX)
NEW_SAY_MAX = 5  # похожих больше NEW_ASK_MAX — вопрос словами с этими именами, ответ — Claude (set.person_id)
# «Хоть одно слово — имя» для нового человека: FIRST_NAMES собран из уменьшительных (около ста имён) — «Кирилл Иванов»,
# «Марина Орлова» и «Глеб Соколов» по нему не люди, и каждое такое имя со встречи стало бы вопросом. Здесь — частые
# полные имена сверх него. Только для этой проверки: в who FIRST_NAMES отделяет имя от фамилии, и расширять его — менять
# баллы узнавания (проверка 15.10: «Кирилл Иванов» против «Кирилл Петров» — 0,5, а с «кирилл» в FIRST_NAMES стало бы 0).
MORE_FIRST = frozenset("""
кирилл никита егор денис глеб марк матвей руслан артур ярослав филипп леонид аркадий всеволод даниил данила богдан
тихон савва захар мирон лука елисей платон степан макар ростислав родион демид давид эмиль ренат рустам марат виталий
герман игнат клим кузьма назар оскар прохор роберт тарас трофим феликс эрик ян альберт ефим иннокентий остап арсен
алан армен тигран натан моисей
марина оксана вера зоя алла инна лариса людмила нина регина диана алина кристина карина элина яна жанна маргарита
ульяна василиса милана арина варвара злата эвелина ангелина алиса вероника елизавета ева майя нелли римма раиса
таисия эльвира эмилия антонина зинаида клавдия лидия олеся снежана тамара фаина лилия белла дина лиана милена анжелика
виолетта камила сара кира марьяна катерина дарина
""".split())


def looks_first(w: str) -> bool:
    """Слово — имя человека (для нового человека со встречи): справочник who или частые полные имена (MORE_FIRST)."""
    w = norm(w)
    return is_first(w) or w in MORE_FIRST or stem(w) in MORE_FIRST


def bare_name(name: Any) -> tuple[str, str]:
    """«Иван Тестов (экспо)» → («Иван Тестов», «экспо»): голое имя и подсказка в скобках."""
    s = " ".join(str(name or "").split())
    m = re.search(r"\s*\(([^)]*)\)\s*$", s)
    return (s[: m.start()].strip(), m.group(1).strip()) if m else (s, "")


def noise(name: Any) -> bool:
    """Не имя человека: нет букв, есть цифры, подпись устройства или разметки, одна буква. Почта — не шум."""
    s = norm(name)
    if re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", s):
        return False
    if not re.search(r"[a-zа-я]", s) or re.search(r"\d", s):
        return True
    if any(re.search(r"(?<![a-zа-я])" + g, s) for g in NOISE):
        return True
    ws = words(s)
    return len(ws) == 1 and len(ws[0]) < 2


def org_key(name: Any) -> str:
    """«Альфа-Банк» = «Альфа Банк», «Ромашка (группа)» = «Ромашка»: без знаков, пробелов и пояснения в скобках."""
    return "".join(words(re.sub(r"\([^)]*\)", " ", str(name or ""))))


def org_named(conn, name: Any) -> dict | None:
    """Живая компания с таким именем или другим именем (по org_key) — или None. Одно правило для «Людей сами»
    (store._org_for) и нового человека со встречи (подпись в скобках)."""
    key = org_key(name)
    if not key:
        return None
    for o in conn.execute("SELECT id, name, aliases FROM crm.orgs WHERE archived_at IS NULL ORDER BY created_at").fetchall():
        if key in {org_key(x) for x in [o["name"], *(o["aliases"] or [])]}:
            return o
    return None


def _named_like(n: str, p: dict) -> bool:
    return any(norm(bare_name(x)[0]) == n for x in [p.get("name"), p.get("short"), *(p.get("aliases") or [])] if x)


def new_person(conn, name: str) -> dict:
    """Имя спикера встречи, которого служба не узнала: завести самому, спросить или «уже убран» (контракт §3.3).

    verdict:
    - «known» — «кто это» уверенно знает его (служба не успела узнать или карточку только что завели): это он, новой
      карточки нет;
    - «removed» — похожих живых нет, а в архиве (не слитая) есть карточка ровно с этим полным именем (от двух слов):
      владелец её убрал — больше не заводим. Совпало одно слово, короткое или другое имя архивной — вопрос с подсказкой
      «в архиве есть «…»» (требование №25/№60: названный попадает в CRM, неясно — один вопрос);
    - «ask» — вопрос: одно слово, больше четырёх слов, шум (цифры, «iPhone», «SPEAKER_03»), ни одного имени, латиница,
      которую translit не делает русским именем, или похожие карточки от ASK_MIN (до NEW_ASK_MAX — кнопками «Это X»,
      больше — словами);
    - «auto» — завести самому: 2–4 слова, хоть одно — имя (looks_first), похожих нет (однофамилец с другим именем — 0,5).
    Подпись в скобках — компания, только если такая уже есть (org_named): org_id, org_name; иначе — заметкой note
    («на встрече подписан «…»»), новую компанию из подписи спикера не заводим. «(2)» и «(iPhone)» от Zoom — не подпись.
    Ответ — {verdict, name (голое), hint, org_id, org_name, note, why [строки held], candidates, known, removed}."""
    bare, hint = bare_name(name)
    if hint and noise(hint):
        hint = ""  # «Иван Петров (2)» — второй вход Zoom того же человека, а не его компания
    n = norm(bare)
    ws = words(bare)
    out: dict[str, Any] = {"verdict": "ask", "name": bare, "hint": hint, "org_id": None, "org_name": None, "note": None,
                           "why": [], "candidates": [], "known": None, "removed": None}
    w = who(conn, q=f"{bare} ({hint})" if hint else bare, limit=50)
    if w["sure"] and w["best"]:
        out.update(verdict="known", known=w["best"])
        return out
    near = [c for c in w["candidates"] if c["score"] >= ASK_MIN]
    why = out["why"]
    if n:
        gone = conn.execute(
            "SELECT id, name, short, aliases FROM crm.people WHERE archived_at IS NOT NULL AND merged_into IS NULL "
            "ORDER BY archived_at DESC, id").fetchall()
        # «Уже убран» — только полное собственное имя архивной карточки (от двух слов) и только когда живых похожих нет:
        # так владелец сказал «нет» именно этому человеку («Вернуть» у заведённого само). Одно слово, короткое или другое
        # имя архивной — не он: «Мария» — не «Мария Старая», а ответ «нет» служба встреч запомнила бы навсегда, и
        # названный человек в CRM не попал бы никогда (проверка 15.10.2026; требование владельца №25/№60 — «нет карточки —
        # заводится, неясно — один вопрос»). Такое совпадение — подсказкой в вопросе.
        hit = next((p for p in gone if len(ws) >= 2 and norm(bare_name(p["name"])[0]) == n), None)
        if hit and not near:
            out.update(verdict="removed", removed={"id": hit["id"], "name": hit["name"]})
            return out
        like = [p for p in gone if _named_like(n, p)]
    else:
        like = []
    if noise(bare):
        why.append("не похоже на имя человека")
    elif len(ws) == 1:
        why.append("имя из одного слова")
    elif len(ws) > 4:
        why.append("больше четырёх слов")
    else:
        latin = bool(re.search(r"[a-z]", n))
        if not any(looks_first(x) or (latin and looks_first(translit(x))) for x in ws):
            why.append("латиница — как назвать, реши сам" if latin else "не похоже на имя человека")
    if near:
        # Кандидаты — все похожие (до NEW_SAY_MAX): кнопками «Это X» сервер спросит, только если их не больше
        # NEW_ASK_MAX, иначе — словами (нужный третий иначе не выбирался бы вовсе).
        out["candidates"] = near[:NEW_SAY_MAX]
        why.append("похожие уже есть: " + ", ".join(c["name"] for c in near[:NEW_ASK_MAX + 1])
                   + (f" и ещё {len(near) - NEW_ASK_MAX - 1}" if len(near) > NEW_ASK_MAX + 1 else ""))
    if like:
        # Сам по себе — тоже вопрос, а не «завести»: владелец уже убирал человека с таким именем.
        why.append("в архиве есть " + ", ".join(f"«{p['name']}»" for p in like[:2]))
    if hint:
        org = org_named(conn, hint)
        if org:
            out.update(org_id=org["id"], org_name=org["name"])
        else:
            out["note"] = f"на встрече подписан «{bare} ({hint})»"
    if not why:
        out["verdict"] = "auto"
    return out


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


def foreign_emails(conn, pid, keys: list[str], ignore: tuple = ()) -> dict[str, dict]:
    """Какие из почт keys (нормой) уже лежат в ДРУГОЙ живой карточке (не архив, не слитая): {почта: та карточка}.
    Почта — железо «кто это» (1,0): у двух карточек она сделала бы обе неуверенными (этап 6 аудита, 15.10.2026).
    Почты — из crm.v_people, по ярусу смотрящего (поезд, вагон 5, поверх аудита №10): отказ «почта карточки «X»» по сырой
    строке выдал бы сотруднику почту коллеги, которую закон приватности ему не показывает. Владельцу и system вид — всё."""
    keys = sorted({k for k in keys if k})
    if not keys:
        return {}
    rows = conn.execute(
        "SELECT id, name, emails FROM crm.v_people WHERE archived_at IS NULL AND merged_into IS NULL "
        "AND id IS DISTINCT FROM %s::uuid AND NOT (id = ANY(%s::uuid[])) "
        "AND EXISTS (SELECT 1 FROM unnest(emails) e WHERE lower(btrim(e)) = ANY(%s)) ORDER BY created_at, id",
        (pid, [str(x) for x in ignore], keys),
    ).fetchall()
    out: dict[str, dict] = {}
    for r in rows:
        for e in r["emails"] or []:
            k = norm(e)
            if k in keys:
                out.setdefault(k, {"id": r["id"], "name": r["name"]})
    return out


def op_person_add(conn, user, op, error, ignore: tuple = ()):
    """Дописать человеку другие имена, номера, почту и Telegram — без затирания чужого.

    Встречи, звонки и дайджест знают только «ещё одно имя этого человека»: перезапись массива
    целиком (person.set) съела бы то, что дописал другой. Повтор — безвреден: что уже есть
    (с точностью до регистра и формы номера), не дублируется. Telegram ставится, только если пуст.

    Другое имя, которое уже есть имя или короткое имя другой живой карточки, не дописывается (аудит 15.10.2026, №1:
    сверка контактов дописала второй карточке короткое имя сотрудника — и «Ваня» перестал узнаваться у обоих). Ответ
    говорит об этом в skipped: [{alias, person_id, name}] — кто так зовётся; старые клиенты поле не читают.
    Почта другой живой карточки — тоже не дописывается (этап 6 аудита, «не чужое»): refused [{field: "emails", value,
    why, person_id, name}]. До того сверка встреч могла положить почту учётки Zoom, с которой говорил другой человек.
    ignore — чьи имена не мешают: слияние дописывает живой карточке имя дубля, пока дубль ещё не убран.
    """
    pid = op.get("id")
    # Что уже есть — из вида по ярусу (проверка пакета «Приватность», аудит №10): по сырой строке ответ «ok, ничего не
    # поменялось» против «нет прав менять» выдавал сотруднику, есть ли у коллеги такой номер, почта или другое имя. Кто
    # вправе править (владелец, автор, сам человек, system), видит в виде те же контакты, что в таблице.
    row = conn.execute(
        "SELECT id, name, short, aliases, phones, emails, telegram_id, telegram_username FROM crm.v_people WHERE id = %s",
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
    new_mails = data.get("emails") or []
    mails = foreign_emails(conn, pid, [norm(x) for x in ([new_mails] if isinstance(new_mails, str) else new_mails)], ignore)
    skipped: list[dict] = []
    refused: list[dict] = []
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
            if col == "emails" and k in mails and k not in seen:
                o = mails[k]
                if all(r["value"] != k for r in refused):
                    refused.append({"field": "emails", "value": k, "why": f"почта карточки «{o['name']}»",
                                    "person_id": o["id"], "name": o["name"]})
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
        return {"row": conn.execute("SELECT * FROM crm.v_people WHERE id = %s", (pid,)).fetchone() or row,
                "changed": False, "skipped": skipped, "refused": refused}
    cols = ", ".join(f"{k} = %s" for k in sets)
    out = conn.execute(f"UPDATE crm.people SET {cols} WHERE id = %s RETURNING id", [*sets.values(), pid]).fetchone()
    if out is None:
        raise error("человек: нет прав менять")
    # Ответ — из вида по ярусу (аудит №10): RETURNING * отдал бы и пометки владельца.
    return {"row": conn.execute("SELECT * FROM crm.v_people WHERE id = %s", (pid,)).fetchone(), "changed": True,
            "skipped": skipped, "refused": refused}


# Где живут ссылки на человека: (таблица, колонка или ключ, массив ли). Переносит их функция базы crm.person_merge
# (dela_0023) — мимо RLS, все строки; список здесь — карта для читателя и тестов: новая ссылка на человека — строка тут
# и в теле функции новой миграцией (аудит №23: до 15.10 слияние не знало встреч, команды сделки, вопросов и семьи).
REFS = (
    ("tasks.tasks", "person_id", False),
    ("tasks.tasks", "requested_by", False),
    ("crm.deals", "lead_person_id", False),
    ("crm.deals", "source_person_id", False),
    ("crm.deals", "person_ids", True),
    ("crm.deals", "team_ids", True),
    ("crm.interactions", "person_ids", True),
    ("crm.meetings", "person_ids", True),
    ("crm.insights", "person_ids", True),  # «Только тебе» (dela_0027)
    ("crm.ideas", "person_id", False),
    ("tasks.suggestions", "payload.person_id, payload.options[].person_id (только ждущие)", False),
    ("crm.svod", "people.family", True),
    ("crm.people", "merged_into", False),
    ("crm.users", "person_id", False),
)


def op_person_merge(conn, user, op, error):
    """Слить дубль в живую карточку: ссылки переезжают, имена и номера дописываются, дубль — в архив.

    Строки не удаляются (правило 8), поэтому дубль остаётся с merged_into: встреча или голос,
    помнящие старый id, найдут живого через него (и who — по его номеру, Telegram и почте).

    С 15.10.2026 (аудит №23) склеивает людей только владелец Дел (закон 3), а ссылки переносит функция базы
    crm.person_merge мимо RLS: до того UPDATE шли под правами сливающего, и невидимое ему (дело сотрудника, хронология
    чужого проекта, сам дубль, заведённый другим) молча оставалось на дубле. Уже слитый дубль — отказ словами. Здесь —
    имена и номера дубля живой (op_person_add: чужое имя другим именем не пишется — skipped, аудит №1).
    """
    dup, into = op.get("id"), op.get("into")
    if not dup or not into or str(dup) == str(into):
        raise error("слияние: нужны id дубля и into — живая карточка, и это разные люди")
    owner = conn.execute("SELECT crm.owner_id() AS o").fetchone()["o"]
    if user not in (owner, "system"):
        raise error("слияние: склеивает людей только владелец Дел — скажи ему, кто это")
    try:
        with conn.transaction():
            r = conn.execute("SELECT crm.person_merge(%s::uuid, %s::uuid) AS r", (str(dup), str(into))).fetchone()["r"]
    except psycopg.errors.RaiseException as e:
        raise error((e.diag.message_primary or str(e)).removeprefix("Дела: ")) from None
    except psycopg.errors.InvalidTextRepresentation:
        raise error("слияние: id — не номер карточки (uuid)") from None
    a = r["dup"]
    add = {
        "aliases": [x for x in [a["name"], a["short"], *(a["aliases"] or [])] if x],
        "phones": a["phones"] or [], "emails": a["emails"] or [],
        "telegram_id": a["telegram_id"], "telegram_username": a["telegram_username"],
    }
    # Живая — тоже мимо RLS: владелец сливает и в карточку, заведённую другим (проверено базой выше). Сбой — точка
    # сохранения операции откатит и прежнего пользователя (set_config откатывается с ней).
    me = conn.execute("SELECT current_setting('dela.user', true) AS u").fetchone()["u"]
    conn.execute("SELECT set_config('dela.user', 'system', true)")
    skipped = op_person_add(conn, user, {"id": into, "add": add}, error)["skipped"]
    if a.get("address"):
        # Как к нему обращаются (dela_0033) — пустое у живой берёт из дубля, как компанию и должность crm.person_merge.
        conn.execute("UPDATE crm.people SET address = %s WHERE id = %s AND nullif(btrim(address), '') IS NULL",
                     (a["address"], into))
    row = conn.execute("SELECT * FROM crm.people WHERE id = %s", (into,)).fetchone()
    conn.execute("SELECT set_config('dela.user', %s, true)", (me or "",))
    # Имена дубля, которые так зовут кого-то ещё, живой не дописаны (№1) — сказать, а не потерять молча.
    return {"row": row, "moved": r["moved"], "skipped": skipped}


# ── Обучение после подтверждения спикера встречи (этап 6 аудита, 15.10.2026) ─────────────
# Саша: «Обучение после подтверждения: email — в карточку, голос — в voices.db, подпись — в aliases, addressed_as — в
# карточку». Голос и своя память подписей — у службы встреч; сюда — то, что живёт в карточке: почта учётки Zoom (или
# гостя календаря), обращение («вы, Иван Петрович») и подпись Zoom другим именем. Только после «да» владельца:
# «Сделано само» не учит ничему, кроме самой привязки (store.op_person_learn проверяет ответ, если он назван). Имена,
# услышанные распознаванием, другими именами не пишем никогда — подпись Zoom человек набрал сам, это не ослышка.

EMAIL = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
# Общие ящики: адрес отдела, робота или переговорной — не человек; в карточку его не пишем.
MAILBOX = frozenset("""
info office hello hi contact contacts admin administrator support help sales team mail noreply no-reply donotreply
do-not-reply secretary reception manager hr pr press marketing billing accounting buh zakaz order orders service events
zoom meeting meetings invite invites calendar notifications notify bot robot postmaster webmaster
""".split())
# Почтовые службы для всех: домен такой почты у сотрудника не делает её «почтой команды».
PUBLIC_MAIL = frozenset("""
gmail.com googlemail.com mail.ru inbox.ru list.ru bk.ru internet.ru yandex.ru ya.ru yandex.com icloud.com me.com mac.com
outlook.com hotmail.com live.com msn.com yahoo.com rambler.ru proton.me protonmail.com gmx.de gmx.net
""".split())
ADDRESS_MAX = 120
LEARN_KEYS = ("emails", "address", "zoom_name")


def learn_data(raw: Any) -> dict:
    """Что выучить карточке — только знакомые поля и в своих пределах: emails (до 5 строк), address, zoom_name.
    Кривое — молча мимо: это проверка формы, отказы по смыслу — в learn."""
    if not isinstance(raw, dict):
        return {}
    out: dict[str, Any] = {}
    mails = raw.get("emails")
    mails = [mails] if isinstance(mails, str) else mails if isinstance(mails, list) else []
    mails = [" ".join(str(x).split())[:120] for x in mails if isinstance(x, str) and x.strip()][:5]
    if mails:
        out["emails"] = mails
    for k, cap in (("address", 300), ("zoom_name", 120)):
        v = " ".join(str(raw.get(k) or "").split()) if isinstance(raw.get(k), str) else ""
        if v:
            out[k] = v[:cap]
    return out


def _team_and_family(conn, card: dict) -> str:
    """Свой ли человек: «команда» (учётка Дел или компания владельца), «семья» (флаг family или Свод people.family),
    «владелец» — или пусто. Обращение таким не пишем: это не клиент, к которому надо помнить «вы»."""
    owner = conn.execute(
        "SELECT u.id, u.person_id, pe.org_id FROM crm.users u LEFT JOIN crm.people pe ON pe.id = u.person_id "
        "WHERE u.id = crm.owner_id()").fetchone()
    if owner and owner["person_id"] and str(owner["person_id"]) == str(card["id"]):
        return "владелец"
    if card.get("user_id"):
        return "команда"
    if owner and owner["org_id"] and card.get("org_id") and str(owner["org_id"]) == str(card["org_id"]):
        return "команда"
    fam = {str(r["person_id"]) for r in conn.execute(
        "SELECT person_id FROM crm.users WHERE family AND person_id IS NOT NULL").fetchall()}
    sv = conn.execute("SELECT value FROM crm.svod WHERE owner_id = crm.owner_id() AND key = 'people.family'").fetchone()
    if sv and isinstance(sv["value"], list):
        fam |= {str(x) for x in sv["value"]}
    return "семья" if str(card["id"]) in fam else ""


def _team_domains(conn) -> set[str]:
    """Домены почт команды (люди с учёткой Дел), кроме общих почтовых служб: такой адрес у клиента — чужой."""
    out: set[str] = set()
    for r in conn.execute("SELECT emails FROM crm.people WHERE user_id IS NOT NULL AND archived_at IS NULL").fetchall():
        for e in r["emails"] or []:
            d = norm(e).rpartition("@")[2]
            if d and d not in PUBLIC_MAIL:
                out.add(d)
    return out


def _signature(conn, card: dict, sig: str, refused: list[dict]) -> str | None:
    """Подпись Zoom — другим именем карточки или None (с причиной в refused). Подпись набрал человек сам, но учётка
    бывает общей и подпись — инициалом: такая ведёт ко многим, и одним другим именем стала бы «уверенно» (who даёт
    другому имени 1,0). Поэтому — только полное имя: от двух слов, без инициалов, хоть одно слово — имя; не шум
    устройства; не чужое имя (это решит op_person_add — skipped) и не подпись другой живой карточки (общая учётка)."""
    ws = words(sig)
    if noise(sig) or EMAIL.fullmatch(norm(sig)):
        refused.append({"field": "zoom_name", "value": sig, "why": "не имя человека — подпись устройства или почта"})
        return None
    if len(ws) < 2 or any(len(w) == 1 for w in ws):
        refused.append({"field": "zoom_name", "value": sig,
                        "why": "подпись из одного слова или с инициалом — по ней одной человека не узнать"})
        return None
    if not any(looks_first(w) or looks_first(translit(w)) for w in ws):
        refused.append({"field": "zoom_name", "value": sig, "why": "не похоже на имя человека"})
        return None
    k = norm(sig)
    if k in {norm(x) for x in [card["name"], card["short"], *(card["aliases"] or [])] if x}:
        return None  # уже его имя
    other = conn.execute(
        "SELECT id, name FROM crm.people WHERE archived_at IS NULL AND merged_into IS NULL AND id <> %s "
        "AND EXISTS (SELECT 1 FROM unnest(aliases) a WHERE replace(crm.norm(a), 'ё', 'е') = %s) ORDER BY created_at LIMIT 1",
        (card["id"], k)).fetchone()
    if other:
        refused.append({"field": "zoom_name", "value": sig, "why": f"общая подпись: так подписана и карточка «{other['name']}»",
                        "person_id": other["id"], "name": other["name"]})
        return None
    if not _signature_names(conn, card, sig):
        # Подпись чужой учётки (этап 6 аудита, проверка 15.10): с учётки «Pavel Orlov» говорил Тимур — подтвердили
        # Тимура, но подпись не его, и другим именем его карточки она вела бы к нему каждого с этой учётки.
        refused.append({"field": "zoom_name", "value": sig, "why": "подпись чужой учётки: имя в ней — не его"})
        return None
    return sig


def _signature_names(conn, card: dict, sig: str) -> bool:
    """Подпись называет этого человека: имя то же (уменьшительные, латиница) и фамилия не спорит — или «кто это» по подписи
    ≥ 0,95 ведёт к нему. Одно правило со службой встреч (meetings/ident.py, card_fits)."""
    sw = [translit(w) if re.search(r"[a-z]", w) else w for w in words(sig)]
    full = [w for w in words(card["name"]) if len(w) > 1]
    first = [w for n in [card["name"], card["short"], *(card["aliases"] or [])] if n for w in words(n) if looks_first(w)]
    sf = [w for w in sw if looks_first(w)]
    ss = [w for w in sw if len(w) > 1 and not looks_first(w)]
    cs = [w for w in full if not looks_first(w)]
    if sf and any(same_first(a, b) and not _gender_clash([a], [b]) for a in sf for b in first) \
            and (not ss or not cs or any(same(a, b) for a in ss for b in cs)):
        return True
    w = who(conn, q=sig)
    return bool(w["sure"] and w["best"] and str(w["best"]["id"]) == str(card["id"]) and w["best"]["score"] >= 0.95)


def learn(conn, user, pid, raw: Any, error) -> dict:
    """Выучить карточке pid то, что служба встреч узнала о подтверждённом спикере (поля — learn_data).

    - Почта: адрес; не общий ящик (MAILBOX); не почта команды у чужого человека (_team_domains); не почта другой
      живой карточки — «не чужое» (одна почта — один человек: иначе «кто это» по почте перестал бы быть уверенным).
    - Обращение: только в пустое поле (поменять — владелец), не команде, семье и самому владельцу; длиннее
      ADDRESS_MAX — мимо.
    - Подпись Zoom: другим именем — по правилам _signature.
    Ответ — {row, changed, wrote: {emails, address, aliases}, refused: [{field, value, why, person_id?, name?}],
    skipped} (skipped — как у person.add: чужое имя). Слитая карточка — учится живая, в архиве — отказ словами.
    Только владелец Дел или system (служба встреч — его токеном); другим — отказ словами."""
    owner = conn.execute("SELECT crm.owner_id() AS o").fetchone()["o"]
    if user not in (owner, "system"):
        # Учит карточку только «да» владельца (служба встреч ходит его токеном). И закон приватности (правило 26, поезд,
        # вагон 5): отказы обучения называют чужие подписи и почты команды, которые сотруднику не видны.
        raise error("обучение карточки — только владельцу Дел: учит его «да» спикеру встречи")
    data = learn_data(raw)
    card = conn.execute("SELECT * FROM crm.people WHERE id = %s", (pid,)).fetchone()
    if card is not None and card["merged_into"]:
        card = conn.execute("SELECT * FROM crm.people WHERE id = %s", (card["merged_into"],)).fetchone()
    if card is None:
        raise error("человек: нет такого или нет прав")
    if card["archived_at"]:
        raise error(f"человек: «{card['name']}» в архиве — учить нечему")
    wrote: dict[str, Any] = {"emails": [], "address": None, "aliases": []}
    refused: list[dict] = []
    add: dict[str, list] = {}

    have = {norm(x) for x in card["emails"] or []}
    team = None
    for x in data.get("emails") or []:
        k = norm(x)
        if not EMAIL.fullmatch(k):
            refused.append({"field": "emails", "value": x, "why": "не адрес почты"})
            continue
        if k in have or k in add.get("emails", []):
            continue
        local, _, dom = k.partition("@")
        if local.split("+")[0] in MAILBOX:
            refused.append({"field": "emails", "value": k, "why": "общий ящик, не человек"})
            continue
        if team is None:
            team = _team_domains(conn) if not card["user_id"] else set()
        if dom in team:
            refused.append({"field": "emails", "value": k, "why": "почта команды"})
            continue
        add.setdefault("emails", []).append(k)

    sig = data.get("zoom_name")
    if sig:
        alias = _signature(conn, card, sig, refused)
        if alias:
            add["aliases"] = [alias]

    row, changed, skipped = card, False, []
    if add:
        res = op_person_add(conn, user, {"id": card["id"], "add": add}, error)
        row, changed, skipped = res["row"], res["changed"], res["skipped"]
        refused += res.get("refused") or []
        now = {norm(x) for x in row["emails"] or []}
        wrote["emails"] = [x for x in add.get("emails", []) if x in now and x not in have]
        names = {norm(x) for x in row["aliases"] or []}
        wrote["aliases"] = [x for x in add.get("aliases", []) if norm(x) in names
                            and norm(x) not in {norm(a) for a in card["aliases"] or []}]
        for s in skipped:
            refused.append({"field": "zoom_name", "value": s["alias"], "why": f"так зовут карточку «{s['name']}»",
                            "person_id": s["person_id"], "name": s["name"]})

    addr = data.get("address")
    if addr:
        addr = addr.strip(" .;,«»\"'")
        mine = _team_and_family(conn, card)
        if len(addr) > ADDRESS_MAX:
            refused.append({"field": "address", "value": addr[:ADDRESS_MAX], "why": "слишком длинно для обращения"})
        elif mine:
            refused.append({"field": "address", "value": addr, "why": f"{mine} — обращение не записываем"})
        elif (row.get("address") or "").strip():
            if norm(row["address"]) != norm(addr):
                refused.append({"field": "address", "value": addr,
                                "why": f"уже записано «{row['address']}» — меняет владелец"})
        else:
            out = conn.execute("UPDATE crm.people SET address = %s WHERE id = %s AND nullif(btrim(address), '') IS NULL "
                               "RETURNING *", (addr, card["id"])).fetchone()
            if out is not None:
                row, changed, wrote["address"] = out, True, addr
    return {"row": row, "changed": changed, "wrote": wrote, "refused": refused, "skipped": skipped}


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
    if key == PRIVACY_KEY:
        value = _privacy_value(conn, owner, body, value, error)
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
    if key == PRIVACY_KEY:
        # Переключатель поменялся — записи встреч владельца переписываются тут же, в той же транзакции: команда видит
        # ровно то, что выбрано, без «догонит на следующей правке карточки».
        return {"row": row, "touched": touch_owner_meetings(conn, owner)}
    return {"row": row}


# ── Что видит команда (аудит №9, правило 26) ─────────────────────────────────

PRIVACY_KEY = "privacy.team"
PRIVACY_MEETINGS = ("fact", "summary")


def _privacy_value(conn, owner: str, body, value, error) -> dict:
    """Свод privacy.team — {"meetings": "fact" | "summary"}: пишет только сам владелец Дел (роль owner, не служба с его
    токеном — открыть команде итоги встреч решает человек). Другие ключи и слова — отказ: кривое значение выглядело бы
    выбором, а действовало бы как «только факт»."""
    role = conn.execute("SELECT role FROM crm.users WHERE id = %s", (owner,)).fetchone()
    actor = str(conn.execute("SELECT crm.actor() AS a").fetchone()["a"] or "")
    if not role or role["role"] != "owner" or actor.startswith("svc:") or crm_me(conn) != owner:
        raise error("свод: что видит команда (privacy.team) решает сам владелец Дел — не служба и не команда")
    if body is not None or not isinstance(value, dict) or set(value) != {"meetings"} \
            or value["meetings"] not in PRIVACY_MEETINGS:
        raise error('свод: privacy.team — {"meetings": "fact"} (команда видит только факт встречи) '
                    'или {"meetings": "summary"} (и итог с договорённостями)')
    return value


def crm_me(conn) -> str | None:
    return conn.execute("SELECT crm.me() AS u").fetchone()["u"]


def touch_owner_meetings(conn, owner: str) -> dict[str, int]:
    """Записи всех встреч владельца с 01.09 — заново по их карточкам и переключателю (crm.meeting_touch). Ответ — счёт по
    словам: {"обновлена": 12, "нечего": 30, …}."""
    out: dict[str, int] = {}
    for r in conn.execute("SELECT crm.meeting_touch(id) AS a FROM crm.meetings WHERE owner_id = %s "
                          "AND started_at >= crm.meeting_touch_from() ORDER BY started_at, id", (owner,)).fetchall():
        out[r["a"]] = out.get(r["a"], 0) + 1
    return out


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
