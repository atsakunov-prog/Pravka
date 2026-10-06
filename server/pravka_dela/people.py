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
from typing import Any

from psycopg.types.json import Jsonb

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


def stem(w: str) -> str:
    """Грубая основа для падежей: «соколовым» → «соколов». Короткие слова не режем."""
    for e in ENDINGS:
        if len(w) - len(e) >= 4 and w.endswith(e):
            return w[: -len(e)]
    return w


FIRST_STEMS: set[str] = set()  # заполняется ниже, после stem()


# Латиница из Zoom и почты — в кириллицу, грубо, но так, чтобы сошлась основа: «Vasiliy Smirnov» —
# «василий смирнов». Мягкий знак при сравнении не считается: в латинице его нет («Veldyaksov»).
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


def same(a: str, b: str) -> bool:
    """Одно слово в разных падежах: основы равны или одна продолжает другую на 1–2 буквы
    («евген» и «евгени» из «Евгений» и «Евгением»)."""
    sa, sb = (stem(norm(x)).replace("ь", "").replace("ъ", "") for x in (a, b))
    if sa == sb:
        return len(sa) >= 3
    lo, hi = sorted((sa, sb), key=len)
    return len(lo) >= 4 and hi.startswith(lo) and len(hi) - len(lo) <= 2


FIRST_STEMS.update(stem(n) for n in FIRST_NAMES)


def first_forms(w: str) -> set[str]:
    """Имя и его полные формы: «женя» → {женя, евгений, евгения}; «женей» — тоже."""
    w = norm(w)
    out = {w}
    for d, full in DIMINUTIVE.items():
        if same(w, d):
            out.add(d)
            out.update(full)
    return out


def same_first(a: str, b: str) -> bool:
    return any(same(x, y) for x in first_forms(a) for y in first_forms(b))


def is_first(w: str) -> bool:
    """Слово — имя, а не фамилия. Строго по основе: «Петрова» — не «Пётр», «Иванов» — не «Иван»
    (фамилии от имён часты, нестрогое сравнение принимало их за имена и теряло фамилию)."""
    w = norm(w)
    return w in FIRST_NAMES or stem(w) in FIRST_STEMS


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
    слово — «финдир Додо» тоже Додо), аббревиатура компании («ЗФ») или роль («терапевт»)."""
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
    """Насколько текст q похож на имя человека: 1 — точное имя или другое имя, 0 — нет.

    Имя и фамилия сверяются парой в обоих порядках («Соколов Евгений» тоже он): фамилия — основой
    (падеж не мешает), имя — через уменьшительные. Одно слово — фамилия средне, имя слабо.
    """
    if not q:
        return 0.0
    names = [norm(n) for n in [person.get("name"), person.get("short"), *(person.get("aliases") or [])] if n]
    if q in names:
        return 1.0
    qw = words(q)
    best = 0.0
    for n in names:
        nw = words(n)
        if not nw:
            continue
        if len(qw) == len(nw) and all(same(a, b) for a, b in zip(qw, nw)):
            best = max(best, 0.95)
            continue
        if len(qw) >= 2 and len(nw) >= 2:
            for qf, ql in ((qw[0], qw[-1]), (qw[-1], qw[0])):
                for nf, nl in ((nw[0], nw[-1]), (nw[-1], nw[0])):
                    # Инициал вместо имени или фамилии — «a.filatov», «Dmitry L» из Zoom и почты.
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
                best = max(best, 0.6)
            elif len(nw) == 1 and same_first(w, nw[0]):
                best = max(best, 0.7)  # короткое имя человека («Женя») — само по себе имя
            elif same_first(w, nw[0]):
                best = max(best, 0.35)
    return best


def who(conn, q: str | None = None, phone: str | None = None, telegram_id: Any = None,
        telegram: str | None = None, email: str | None = None, limit: int = 5) -> dict:
    """Кто это. Видимые человеку люди (RLS), живые: архив и слитые — нет."""
    rows = conn.execute(
        "SELECT pe.id, pe.name, pe.short, pe.aliases, pe.phones, pe.emails, pe.telegram_id, pe.telegram_username, "
        "pe.org_id, pe.role, pe.user_id, o.name AS org_name, o.aliases AS org_aliases "
        "FROM crm.people pe LEFT JOIN crm.orgs o ON o.id = pe.org_id "
        "WHERE pe.archived_at IS NULL AND pe.merged_into IS NULL"
    ).fetchall()
    pk = phone_key(phone)
    tg_user = norm(telegram).lstrip("@") if telegram else ""
    mail = norm(email) if email else ""
    q_name, hint = split_hint(q or "")
    scored = []
    for p in rows:
        s, how = 0.0, ""
        if pk and any(phone_key(x) == pk for x in p["phones"] or []):
            s, how = 1.0, "номер"
        elif telegram_id and p["telegram_id"] and str(p["telegram_id"]) == str(telegram_id):
            s, how = 1.0, "telegram"
        elif tg_user and norm(p["telegram_username"]).lstrip("@") == tg_user:
            s, how = 1.0, "telegram"
        elif mail and mail in [norm(x) for x in p["emails"] or []]:
            s, how = 1.0, "почта"
        else:
            s = _name_score(q_name, p)
            if re.search(r"[a-z]", q_name):
                s = max(s, _name_score(translit(q_name), p))  # подпись из Zoom латиницей
            how = "имя" if s >= 0.95 else "похоже"
            orgs = [x for x in [p["org_name"], *(p["org_aliases"] or [])] if x]
            if s and hint:
                # Компания или роль подтверждает — увереннее, а одно имя с ней («Дмитрий (финдир
                # Додо)») — почти наверняка: уникальность всё равно проверит GAP. У человека другая
                # компания — слабее; компании нет — подсказке не с чем спорить («Анна Смирнова
                # (Цветочная лавка)» — это она).
                if _hint_hit(hint, orgs + [p["role"] or ""]) or _hint_hit(translit(hint), orgs + [p["role"] or ""]):
                    s = max(min(1.0, s + 0.2), 0.9 if _first_only(q_name) else 0.0)
                elif orgs:
                    s -= 0.15
        if s > 0.3:
            scored.append((s, how, p))
    scored.sort(key=lambda t: -t[0])
    cands = [
        {"id": p["id"], "name": p["name"], "short": p["short"], "org": p["org_name"], "role": p["role"],
         "user_id": p["user_id"], "score": round(s, 2), "how": how}
        for s, how, p in scored[:limit]
    ]
    sure = bool(cands) and cands[0]["score"] >= SURE and (len(cands) == 1 or cands[0]["score"] - cands[1]["score"] >= GAP)
    return {"best": cands[0] if cands else None, "sure": sure, "candidates": cands, "hint": hint or None}


# ── Операции ────────────────────────────────────────────────────────────

ADD_ARRAYS = ("aliases", "phones", "emails")


def op_person_add(conn, user, op, error):
    """Дописать человеку другие имена, номера, почту и Telegram — без затирания чужого.

    Встречи, звонки и дайджест знают только «ещё одно имя этого человека»: перезапись массива
    целиком (person.set) съела бы то, что дописал другой. Повтор — безвреден: что уже есть
    (с точностью до регистра и формы номера), не дублируется. Telegram ставится, только если пуст.
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
        return {"row": row, "changed": False}
    cols = ", ".join(f"{k} = %s" for k in sets)
    out = conn.execute(f"UPDATE crm.people SET {cols} WHERE id = %s RETURNING *", [*sets.values(), pid]).fetchone()
    if out is None:
        raise error("человек: нет прав менять")
    return {"row": out, "changed": True}


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
    op_person_add(conn, user, {"id": into, "add": add}, error)
    fill = {k: a[k] for k in ("org_id", "role", "birth_day", "birth_month", "birth_year", "note", "user_id")
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
    return {"row": row, "moved": moved}


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
