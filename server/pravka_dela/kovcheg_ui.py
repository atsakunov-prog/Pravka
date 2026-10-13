"""Вид и меню Ковчега — из одной правды, Свода (Правка 5.0, этап 7, 14.10.2026).

Владелец 13.10.2026: телефон и веб — две копии одного Ковчега, «правка в вебе — и в телефоне». До этапа 7 меню значка
Ковчега (три большие кнопки, восемь направлений Круга жизни, строка «Творчество») жило тремя копиями: `core/Kovcheg.kt`
телефона, `SVC_DIRS` веба Дел и `GROUPS` общего слоя страниц на компе (`kovcheg.js`), — и любая правка подписи
требовала трёх выкладок. Теперь это две записи Свода:

- `ui.menu` — что в меню и в каком порядке: большие кнопки, направления с подписями и сервисами, «Творчество».
  Сервис — КЛЮЧ (`sport`, `dela`, `kino`…), подпись и значок. Адресов в меню нет: репозиторий публичный, а адрес —
  свойство установки (`DELA_NAV` на компе, поддомены в `kovcheg.js`), а не меню. Ключ, которому клиент не знает
  дороги, он просто не показывает; знакомый, но не поставленный в меню — уходит в «Ещё» (ничего не теряется).
- `ui.look` — токены вида обеих тем поверх заводских v2 (главное — акцент): `{"light": {"accent": "#2747c9", …},
  "dark": {…}}`. Имена — переменные общего слоя без `--`.

Пишут: владелец в вебе Дел («Настройки → Вид и меню», author = web) и claude по его слову (`svod set`). Значение
проверяется ДО записи (`check`, зовёт `people.op_svod_set`): кривое меню не ложится — иначе оно уронило бы шапку на
всех страницах разом. Читают: веб Дел — `/api/me` (`effective`: нет записи или она не прошла проверку — заводское
отсюда), телефон — синком Свода (своя проверка и своё заводское — `core/Kovcheg.kt`), остальные страницы — сборщик
«Дома» узким токеном `dom` (только чтение `ui.*`), sync.py вписывает их в kovcheg.js и kovcheg.css страниц.
Заводские значения — те же, что `ui` в `server/contract/svod.json`: их сверяют тесты сервера и телефона.
"""

from __future__ import annotations

import json
import logging
import re

from . import db

log = logging.getLogger("dela.ui")

MENU = "ui.menu"
LOOK = "ui.look"
KEYS = (MENU, LOOK)

# Каталог знакомых сервисов: ключ → (название, значок набора Ковчега). По нему меню без подписи берёт заводскую, а
# сервисы из настроек компа (DELA_NAV — «Название|значок|адрес») узнают свой ключ по значку: так в настройке ключ не
# нужен. Новый сервис — строка здесь, в core/Kovcheg.kt и в SERVICES kovcheg.js (там же его поддомен).
CATALOG: dict[str, tuple[str, str]] = {
    "today": ("Сегодня", "sun"),
    "zasechka": ("Засечка", "clock"),
    "pravka": ("Правка", "edit"),
    "sport": ("Спорт", "activity"),
    "eda": ("Еда", "food"),
    "knigi": ("Книги", "book"),
    "dela": ("Дела", "dela"),
    "meetings": ("Встречи", "mic"),
    "dengi": ("Деньги", "wallet"),
    "crm": ("CRM", "briefcase"),
    "deti": ("Дети", "kids"),
    "shabbat": ("Шаббат", "candles"),
    "kino": ("Кино", "film"),
    "dom": ("Дом", "home"),
    "vpn": ("VPN", "shield"),
    "claude": ("Claude", "claude"),
}
KEY_BY_ICON = {icon: key for key, (_, icon) in CATALOG.items()}

# Страницы-двойники веба Дел для больших кнопок меню (14.10.2026; делает свой этап — двойники «Сегодня», «Засечки»
# и «Правки» в вебе, маршруты #/day, #/zasechka, #/pravka). Здесь — только то, что сервер уже умеет: /api/me отдаёт
# список владельцу полем look.pages, и большая кнопка в вебе (Дела и страницы компа) появляется лишь тогда, когда её
# страница есть; пока список пуст — больших кнопок в вебе нет, как до Свода. Телефону он не нужен: там это экраны.
PAGES: tuple[str, ...] = ()
# Большая кнопка меню → страница веба Дел (её маршрут — «#/<страница>»).
PAGE_OF = {"today": "day", "zasechka": "zasechka", "pravka": "pravka"}


def _it(key: str, sub: str = "") -> dict:
    name, icon = CATALOG[key]
    return {"key": key, "name": name, "icon": icon, **({"sub": sub} if sub else {})}


def _dir(name: str, sub: str, *keys: str) -> dict:
    return {"name": name, "sub": sub, "items": [_it(k) for k in keys]}


# Заводское меню — ровно то, что было в коде до Свода (план §3, решение владельца 13.10.2026).
FACTORY_MENU = {
    "v": 1,
    "big": [_it("today", "день целиком"), _it("zasechka", "что делаю сейчас"), _it("pravka", "все диктовки")],
    "directions": [
        _dir("Здоровье", "сильное тело, ясная голова", "sport", "eda"),
        _dir("Ум", "много знаю и узнаю", "knigi"),
        _dir("Дело", "растёт вглубь и вширь", "dela", "meetings"),
        _dir("Деньги", "деньги — не ограничение", "dengi"),
        _dir("Круг", "знаю многих", "crm"),
        _dir("Родительство", "сыновья растут сильными", "deti", "shabbat"),
        _dir("Пара", "близкая живая пара", "kino"),
        _dir("Быт", "дома хорошо всем пятерым", "dom", "vpn"),
    ],
    "create": _dir("Творчество", "создаю новое", "claude"),
}

# Токены вида, которые можно переопределить: переменные kovcheg.css (и style.css Дел) без «--». Полупрозрачные
# (hover, тень) — нет: их цвет выводится из темы, а не выбирается.
TOKENS = ("bg", "side", "panel", "panel-2", "line", "line-soft", "text", "muted", "faint", "icon", "check", "ok", "warn",
          "bad", "info", "claude", "accent", "accent-ink", "accent-soft", "bar-fill",
          "sp0", "sp1", "sp2", "sp3", "sp4", "sp5", "sp6", "sp7")
# Заводской вид — нынешний v2 (kovcheg.css): акцент обеих тем. Остальные токены не записаны — значит, заводские.
FACTORY_LOOK = {
    "v": 1,
    "light": {"accent": "#2747c9", "accent-ink": "#ffffff", "accent-soft": "#e9edfb", "info": "#2747c9"},
    "dark": {"accent": "#93abf8", "accent-ink": "#141414", "accent-soft": "#252c47", "info": "#93abf8"},
}
# Фон, на который ложится мягкий акцент (выбранное, подложки), если его не задали: светлая — белая панель, тёмная —
# фон темы. Доли подобраны по заводским парам: #2747c9 → #e9edfb, #93abf8 → около #252c47.
_SOFT = {"light": ("#ffffff", 0.10), "dark": ("#161513", 0.22)}

_KEY = re.compile(r"^[a-z][a-z0-9_-]{0,23}$")
_ICON = re.compile(r"^[a-z][a-zA-Z0-9]{0,23}$")
_HEX = re.compile(r"^#(?:[0-9a-f]{3}|[0-9a-f]{6})$")
# Управляющие знаки и угловые скобки — нет: подписи меню вписываются в страницы всех служб; клиенты экранируют и
# сами, но запись, похожая на разметку, — точно ошибка, а не подпись.
_BAD = re.compile(r"[\x00-\x1f\x7f<>]")

NAME_MAX, SUB_MAX, ITEM_SUB_MAX = 24, 60, 40
BIG_MAX, DIRS_MAX, ITEMS_MAX, TOTAL_MAX, SIZE_MAX = 4, 12, 10, 40, 16_000


class UiError(ValueError):
    """Значение ui.* не годится — словами, что именно (уходит человеку в ответ svod.set)."""


def _text(v, what: str, most: int, need: bool = True) -> str:
    if v is None:
        v = ""
    if not isinstance(v, str):
        raise UiError(f"{what}: нужен текст")
    s = " ".join(v.split())
    if need and not s:
        raise UiError(f"{what}: пусто")
    if len(s) > most:
        raise UiError(f"{what}: длиннее {most} знаков")
    if _BAD.search(s):
        raise UiError(f"{what}: угловые скобки и управляющие знаки нельзя")
    return s


def _item(x, where: str, seen: set) -> dict:
    if isinstance(x, str):
        x = {"key": x}
    if not isinstance(x, dict):
        raise UiError(f"{where}: нужен сервис {{key, name, icon}}")
    extra = set(x) - {"key", "name", "icon", "sub"}
    if extra:
        raise UiError(f"{where}: незнакомые поля {', '.join(sorted(extra))}")
    key = x.get("key")
    if not isinstance(key, str) or not _KEY.match(key):
        raise UiError(f"{where}: ключ сервиса — латиницей: sport, dela, kino")
    if key in seen:
        raise UiError(f"«{key}» в меню дважды — сервис живёт в одном месте")
    seen.add(key)
    cat = CATALOG.get(key)
    name = _text(x.get("name") or (cat[0] if cat else ""), f"{where} ({key}): название", NAME_MAX)
    icon = x.get("icon") or (cat[1] if cat else "")
    if not isinstance(icon, str) or not _ICON.match(icon):
        raise UiError(f"{where} ({key}): значок — имя из набора Ковчега латиницей: activity, book, film")
    out = {"key": key, "name": name, "icon": icon}
    sub = _text(x.get("sub"), f"{where} ({key}): подпись", ITEM_SUB_MAX, need=False)
    if sub:
        out["sub"] = sub
    return out


def _group(d, where: str, seen: set) -> dict:
    if not isinstance(d, dict):
        raise UiError(f"{where}: нужен объект {{name, sub, items}}")
    extra = set(d) - {"name", "sub", "items"}
    if extra:
        raise UiError(f"{where}: незнакомые поля {', '.join(sorted(extra))}")
    name = _text(d.get("name"), f"{where}: название", NAME_MAX)
    items = d.get("items", [])
    if not isinstance(items, list) or len(items) > ITEMS_MAX:
        raise UiError(f"{where} «{name}»: сервисы — список до {ITEMS_MAX}")
    return {"name": name, "sub": _text(d.get("sub"), f"{where} «{name}»: подпись", SUB_MAX, need=False),
            "items": [_item(x, f"«{name}», сервис {i + 1}", seen) for i, x in enumerate(items)]}


def check_menu(v) -> dict:
    """Проверить и привести ui.menu. Нет big или create — заводские (запись можно писать частью: только направления);
    create: null — строки «Творчество» нет. Ошибка — UiError со словами, что не так."""
    if not isinstance(v, dict):
        raise UiError("ui.menu: нужен объект {big, directions, create}")
    extra = set(v) - {"v", "big", "directions", "create"}
    if extra:
        raise UiError(f"ui.menu: незнакомые поля {', '.join(sorted(extra))}")
    if v.get("v", 1) != 1:
        raise UiError("ui.menu: версия 1")
    seen: set = set()
    big = v.get("big", FACTORY_MENU["big"])
    if not isinstance(big, list) or len(big) > BIG_MAX:
        raise UiError(f"ui.menu: big — список до {BIG_MAX} больших кнопок")
    big = [_item(x, f"большая кнопка {i + 1}", seen) for i, x in enumerate(big)]
    dirs = v.get("directions")
    if not isinstance(dirs, list) or not 1 <= len(dirs) <= DIRS_MAX:
        raise UiError(f"ui.menu: directions — список от 1 до {DIRS_MAX} направлений")
    dirs = [_group(d, f"направление {i + 1}", seen) for i, d in enumerate(dirs)]
    names = [d["name"].lower() for d in dirs]
    if len(set(names)) != len(names):
        raise UiError("ui.menu: два направления с одним названием")
    create = v.get("create", FACTORY_MENU["create"])
    create = None if create is None else _group(create, "«Творчество»", seen)
    if len(seen) > TOTAL_MAX:
        raise UiError(f"ui.menu: больше {TOTAL_MAX} пунктов")
    out = {"v": 1, "big": big, "directions": dirs, "create": create}
    if len(json.dumps(out, ensure_ascii=False)) > SIZE_MAX:
        raise UiError("ui.menu: слишком длинно")
    return out


def _hex6(s: str) -> str:
    s = s.strip().lower()
    return "#" + "".join(c * 2 for c in s[1:]) if len(s) == 4 else s


def _rgb(h: str) -> tuple[int, int, int]:
    return int(h[1:3], 16), int(h[3:5], 16), int(h[5:7], 16)


def mix(a: str, b: str, wa: float) -> str:
    """Смесь двух цветов: доля wa первого."""
    return "#" + "".join(f"{round(x * wa + y * (1 - wa)):02x}" for x, y in zip(_rgb(a), _rgb(b)))


def _lum(h: str) -> float:
    def lin(c: int) -> float:
        c /= 255
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = _rgb(h)
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)


def ink(accent: str) -> str:
    """Текст на кнопке цвета акцента: белый или почти чёрный — что контрастнее (как у заводских пар)."""
    la = _lum(accent)
    white, dark = 1.05 / (la + 0.05), (la + 0.05) / (_lum("#141414") + 0.05)
    return "#ffffff" if white >= dark else "#141414"


def check_look(v) -> dict:
    """Проверить и привести ui.look: только знакомые токены, цвет — #rgb или #rrggbb (приводится к #rrggbb). Задан
    акцент без мягкого акцента, текста на акценте или «info» — они выводятся из него: иначе выбранное подсвечивалось
    бы старым синим, а текст на новой кнопке мог стать нечитаемым."""
    if not isinstance(v, dict):
        raise UiError("ui.look: нужен объект {light, dark}")
    extra = set(v) - {"v", "light", "dark"}
    if extra:
        raise UiError(f"ui.look: незнакомые поля {', '.join(sorted(extra))}")
    if v.get("v", 1) != 1:
        raise UiError("ui.look: версия 1")
    out: dict = {"v": 1}
    for theme in ("light", "dark"):
        m = v.get(theme)
        if m is None:
            continue
        if not isinstance(m, dict):
            raise UiError(f"ui.look: {theme} — объект {{токен: цвет}}")
        t = {}
        for name, col in m.items():
            if name not in TOKENS:
                raise UiError(f"ui.look: {theme}.{name} — такого цвета в виде нет; можно: {', '.join(TOKENS)}")
            if not isinstance(col, str) or not _HEX.match(col.strip().lower()):
                raise UiError(f"ui.look: {theme}.{name} — цвет вида #2747c9")
            t[name] = _hex6(col)
        if "accent" in t:
            base, share = _SOFT[theme]
            t.setdefault("accent-soft", mix(t["accent"], base, share))
            t.setdefault("accent-ink", ink(t["accent"]))
            t.setdefault("info", t["accent"])
        if t:
            out[theme] = {k: t[k] for k in TOKENS if k in t}
    if len(out) == 1:
        raise UiError("ui.look: нет ни light, ни dark")
    return out


def check(key: str, body, value):
    """Проверка записи ui.* перед svod.set: приведённое значение или UiError."""
    if key not in KEYS:
        raise UiError(f"свод: из ui.* знаю только {', '.join(KEYS)}")
    if body is not None:
        raise UiError(f"свод: {key} — JSON (value), а не текст")
    return check_menu(value) if key == MENU else check_look(value)


def effective(url: str) -> dict:
    """Вид и меню владельца для /api/me: запись Свода, если она проходит проверку, иначе заводское. Читается как
    system: команде Дел акцент владельца тоже нужен (вид у Ковчега один), а свой Свод у неё пуст."""
    got = {}
    try:
        with db.session(url, "system", via="view") as conn:
            for r in conn.execute("SELECT key, value, rev FROM crm.svod WHERE owner_id = crm.owner_id() AND key = ANY(%s)",
                                  (list(KEYS),)).fetchall():
                got[r["key"]] = r
    except Exception:  # noqa: BLE001 — меню не повод не ответить /api/me
        log.exception("вид и меню из Свода")
    out = {"menu": FACTORY_MENU, "menu_from": "factory", "menu_rev": 0,
           "tokens": {k: FACTORY_LOOK[k] for k in ("light", "dark")}, "tokens_from": "factory", "tokens_rev": 0}
    for key, check_fn, field in ((MENU, check_menu, "menu"), (LOOK, check_look, "tokens")):
        r = got.get(key)
        if not r:
            continue
        # Версия — настоящая, даже у кривой записи: веб сохраняет с base_rev, и с нулём он упирался бы в «уже версии N».
        out[field + "_rev"] = r["rev"]
        try:
            v = check_fn(r["value"])
        except UiError as e:
            log.warning("%s версии %s не прошла проверку — заводское: %s", key, r["rev"], e)
            continue
        out[field] = v if field == "menu" else {k: v.get(k, {}) for k in ("light", "dark")}
        out[field + "_from"] = "svod"
    return out
