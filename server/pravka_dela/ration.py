"""Рацион Саши в Своде (`food.ration`, 13.10.2026): смотреть и править из коннектора claude.ai.

Рацион — его постоянные продукты (КБЖУ на 100 г с этикеток, обычная порция, как он их называет
голосом) и блюда — наборы этих продуктов с граммами («Моя каша», «Индейка с кускусом»). Формат —
ключ food.ration в server/contract/svod.json; читает телефон (core/Ration.kt): разбор еды голосом
(названо блюдо — все его продукты) и тап по рациону во вкладке «Еда». Владелец: «добавь рацион в
коннектор. И надо иметь возможность этот рацион редактировать».

Правка — целым значением записи Свода с base_rev: рацион правит и телефон, и правка из claude.ai
поверх свежей чужой версии не должна молча её затереть — отказ, перечитать, повторить.

Записи на сервере ещё нет (телефон кладёт её с первым синком новой сборки) — правка начинается с
заводского файла телефона из этого же репозитория (app/src/main/assets/ration.json), а не с пустого
рациона: иначе одна новая строка стала бы всем рационом, и засев телефона сервер бы уже не принял.
"""

from __future__ import annotations

import copy
import datetime as dt
import json
import math
import re
from pathlib import Path
from zoneinfo import ZoneInfo

from . import store

KEY = "food.ration"
VERSION = 2
USER = "sasha"
KINDS = ("завтрак", "обед", "ужин", "перекус", "добавки")
SURE = ("точно", "примерно", "наугад")
ABOUT = ("Рацион владельца: продукты (КБЖУ на 100 г, обычная порция, как он их называет) и блюда — "
         "наборы продуктов с граммами. Правда — запись Свода food.ration на сервере Дел.")
# Ключи веществ — как у телефона (core/Micronutrients.kt) и life.norms архива: чужой ключ телефон
# молча выбросит, поэтому он не принимается здесь же, с понятным отказом.
MICRO = {
    "va": "витамин A, мкг", "vd": "витамин D, мкг", "ve": "витамин E, мг", "vk": "витамин K, мкг",
    "vc": "витамин C, мг", "b1": "B1, мг", "b2": "B2, мг", "b3": "B3, мг", "b6": "B6, мг",
    "b9": "фолат, мкг", "b12": "B12, мкг", "ca": "кальций, мг", "fe": "железо, мг", "mg": "магний, мг",
    "zn": "цинк, мг", "k": "калий, мг", "na": "натрий, мг", "se": "селен, мкг", "i": "йод, мкг",
    "o3": "омега-3, г",
}
FACTORY = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "assets" / "ration.json"


class RationError(ValueError):
    """Правку нельзя сделать: причина словами — её читает Claude и пересказывает Саше."""


# ── Чтение и запись ─────────────────────────────────────────────────────


def factory() -> dict | None:
    try:
        return json.loads(FACTORY.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def load(url: str, user: str = USER) -> tuple[dict | None, int, str]:
    """Рацион, его версия в Своде (0 — записи нет) и откуда он: «свод», «заводской» или «нет»."""
    items = store.view(url, user, "svod", key=KEY)["items"]
    if items and isinstance(items[0].get("value"), dict):
        return items[0]["value"], int(items[0]["rev"]), "свод"
    # Запись есть, но не рацион (текстом, битая) — правка начнётся с заводского поверх её версии,
    # иначе base_rev 0 упирался бы в «уже версии N» без конца.
    rev = int(items[0]["rev"]) if items else 0
    f = factory()
    return (f, rev, "заводской") if f else (None, rev, "нет")


def save(url: str, book: dict, rev: int, reason: str, user: str = USER) -> int:
    """Новая версия рациона поверх версии rev. Ответ — новая версия; чужая свежая — RationError."""
    # День — московский: часы облака и тестов идут по UTC, а «обновлён 13.10» Саша читает по Москве.
    today = dt.datetime.now(ZoneInfo("Europe/Moscow")).date().isoformat()
    book = dict(book, version=VERSION, updated=today)
    book.setdefault("about", ABOUT)
    problems = check(book)
    if problems:
        raise RationError("рацион не сходится: " + "; ".join(problems))
    op = {"op": "svod.set", "key": KEY, "value": book, "author": "claude", "reason": reason[:200], "base_rev": rev}
    r = store.apply_ops(url, user, [op], via="mcp")["results"][0]
    if not r["ok"]:
        if "на сервере уже версии" in (r.get("error") or ""):
            raise RationError("рацион только что поменяли (на телефоне?) — перечитай его (ration) и повтори правку")
        raise RationError(r.get("error") or "Свод не принял")
    return int(r["row"]["rev"])


# ── Поиск ───────────────────────────────────────────────────────────────


def norm(s: str | None) -> str:
    s = (s or "").lower().replace("ё", "е")
    s = re.sub(r"[«»\"'“”()]", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def find(items: list[dict], name: str, what: str) -> dict | None:
    """Продукт или блюдо по id, названию или тому, как его называет Саша. Нет — None; двое — отказ."""
    n = norm(name)
    if not n:
        raise RationError(f"{what}: пустое название")
    for it in items:
        if it.get("id") == name.strip():
            return it

    def names(it):
        return [norm(it.get("name"))] + [norm(a) for a in it.get("aliases") or []]

    exact = [it for it in items if n in names(it)]
    if len(exact) == 1:
        return exact[0]
    part = exact or [it for it in items if any(n in x for x in names(it))]
    if len(part) == 1:
        return part[0]
    if len(part) > 1:
        raise RationError(f"«{name}» — это {', '.join(it['name'] for it in part)}? Назови точнее")
    return None


def _slug(name: str, taken: set[str]) -> str:
    tr = dict(zip("абвгдеёжзийклмнопрстуфхцчшщъыьэюя",
                  ["a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r", "s",
                   "t", "u", "f", "h", "c", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya"]))
    s = "".join(tr.get(c, c) for c in norm(name))
    s = re.sub(r"[^a-z0-9]+", "-", s).strip("-")[:40] or "x"
    out, k = s, 2
    while out in taken:
        out, k = f"{s}-{k}", k + 1
    return out


# ── Числа ───────────────────────────────────────────────────────────────


def _r(v: float) -> int:
    """Округление как у телефона (Math.round): 142,5 → 143, а не банковское 142."""
    return int(math.floor(v + 0.5))


def _fmt(v) -> str:
    v = float(v or 0)
    return str(int(v)) if v == int(v) else f"{v:.1f}".rstrip("0").rstrip(".")


def _num(v, what: str, top: float) -> float:
    try:
        x = float(v)
    except (TypeError, ValueError):
        raise RationError(f"{what}: нужно число, а не «{v}»") from None
    if math.isnan(x) or x < 0 or x > top:
        raise RationError(f"{what}: {_fmt(x)} — вне 0…{_fmt(top)}")
    return x


def portion_kcal(p: dict, grams: int) -> int:
    return 0 if p.get("pill") else _r(float(p.get("kcal100") or 0) * grams / 100)


def dish_kcal(book: dict, d: dict) -> int:
    by = {p["id"]: p for p in book.get("products") or []}
    return sum(portion_kcal(by[i["product"]], int(i.get("grams") or 0)) for i in d.get("items") or [] if i["product"] in by)


def check(book: dict) -> list[str]:
    """То же, что телефон считает поломкой записи: без id и имени, дубль, блюдо со ссылкой в пустоту."""
    out, ids = [], set()
    for p in book.get("products") or []:
        if not p.get("id") or not p.get("name"):
            out.append("продукт без id или названия")
        elif p["id"] in ids:
            out.append(f"продукт «{p['id']}» дважды")
        ids.add(p.get("id"))
    for d in book.get("dishes") or []:
        if not d.get("items"):
            out.append(f"блюдо «{d.get('name')}» без продуктов")
        for i in d.get("items") or []:
            if i.get("product") not in ids:
                out.append(f"в «{d.get('name')}» продукт «{i.get('product')}», которого нет")
    return out


# ── Правка продукта ─────────────────────────────────────────────────────


def _aliases(have: list[str], replace: list[str] | None, add: list[str] | None) -> list[str]:
    out = list(have)
    if replace is not None:
        out = [a.strip() for a in replace if a and a.strip()]
    for a in add or []:
        if a and a.strip() and norm(a) not in {norm(x) for x in out}:
            out.append(a.strip())
    seen, uniq = set(), []
    for a in out:
        if norm(a) not in seen:
            seen.add(norm(a))
            uniq.append(a)
    return uniq


def put_product(book: dict, name: str, *, new_name: str | None = None, aliases: list[str] | None = None,
                add_aliases: list[str] | None = None, grams: int | None = None, kcal100: float | None = None,
                protein100: float | None = None, fat100: float | None = None, carbs100: float | None = None,
                sure: str | None = None, pill: bool | None = None, note: str | None = None,
                micro: dict | None = None, remove: bool = False) -> str:
    """Завести продукт, поправить его или убрать (remove — и из всех блюд). Меняет book на месте."""
    products = book.setdefault("products", [])
    p = find(products, name, "продукт")
    if remove:
        if p is None:
            raise RationError(f"продукта «{name}» в рационе нет")
        products.remove(p)
        hit = []
        for d in list(book.get("dishes") or []):
            before = len(d.get("items") or [])
            d["items"] = [i for i in d.get("items") or [] if i.get("product") != p["id"]]
            if len(d["items"]) != before:
                hit.append(d["name"])
            if not d["items"]:
                book["dishes"].remove(d)
                hit[-1] += " (блюдо опустело — убрано)"
        return f"Убрал продукт «{p['name']}»" + (f" — и из блюд: {', '.join(hit)}" if hit else "") + "."

    created = p is None
    if created:
        missing = [w for w, v in (("kcal100", kcal100), ("protein100", protein100), ("fat100", fat100),
                                  ("carbs100", carbs100), ("grams", grams)) if v is None]
        if pill:
            missing = []
        if missing:
            raise RationError(f"продукта «{name}» в рационе нет; чтобы завести — нужны {', '.join(missing)} "
                              "(КБЖУ на 100 г и обычная порция)")
        p = {"id": _slug(new_name or name, {x["id"] for x in products}), "name": (new_name or name).strip(),
             "aliases": [], "grams": 0, "kcal100": 0, "p100": 0, "f100": 0, "c100": 0}
        products.append(p)
    elif new_name and norm(new_name) != norm(p["name"]):
        if any(norm(x["name"]) == norm(new_name) for x in products if x is not p):
            raise RationError(f"«{new_name}» уже есть в рационе")
        p["name"] = new_name.strip()
    for field, value, top in (("kcal100", kcal100, 900), ("p100", protein100, 100), ("f100", fat100, 100),
                              ("c100", carbs100, 100)):
        if value is not None:
            p[field] = _num(value, field, top)
    if grams is not None:
        p["grams"] = int(_num(grams, "grams", 5000))
    if pill is not None:
        if pill:
            p["pill"] = True
            p["grams"] = 0
            for f in ("kcal100", "p100", "f100", "c100"):
                p[f] = 0
        else:
            p.pop("pill", None)
    if sure is not None:
        if sure not in SURE:
            raise RationError(f"sure — одно из: {', '.join(SURE)}")
        if sure == "точно":
            p.pop("sure", None)
        else:
            p["sure"] = sure
    if aliases is not None or add_aliases:
        p["aliases"] = _aliases(p.get("aliases") or [], aliases, add_aliases)
    if note is not None:
        if note.strip():
            p["note"] = note.strip()
        else:
            p.pop("note", None)
    if micro is not None:
        bad = [k for k in micro if k not in MICRO]
        if bad:
            raise RationError(f"вещества {', '.join(bad)} не знаю; ключи: " +
                              ", ".join(f"{k} ({v})" for k, v in MICRO.items()))
        m = dict(p.get("micro") or {})
        for k, v in micro.items():
            if v in (None, 0, 0.0):
                m.pop(k, None)
            else:
                m[k] = _num(v, k, 100000)
        if m:
            p["micro"] = m
        else:
            p.pop("micro", None)
    if not p.get("pill") and p.get("grams", 0) <= 0:
        raise RationError(f"у «{p['name']}» нет обычной порции (grams) — без неё «съел {p['name'].lower()}» не посчитать")
    warn = ""
    if not p.get("pill"):
        macro = 4 * p["p100"] + 9 * p["f100"] + 4 * p["c100"]
        if p["p100"] + p["f100"] + p["c100"] > 101:
            raise RationError(f"белков, жиров и углеводов вместе больше 100 г на 100 г — проверь цифры")
        if p["kcal100"] > 0 and abs(macro - p["kcal100"]) > max(15, p["kcal100"] * 0.15):
            warn = (f" Внимание: по макросам выходит {_r(macro)} ккал на 100 г, а записано {_fmt(p['kcal100'])} — "
                    "сверь с этикеткой.")
    return ("Завёл" if created else "Поправил") + " продукт: " + product_line(p) + "." + warn


# ── Правка блюда ────────────────────────────────────────────────────────


def _portions(book: dict, rows: list[dict] | None) -> list[dict]:
    out = []
    for row in rows or []:
        pname = str((row or {}).get("product") or "").strip()
        p = find(book.get("products") or [], pname, "продукт блюда")
        if p is None:
            raise RationError(f"продукта «{pname}» в рационе нет — сначала заведи его (ration_product)")
        grams = 0 if p.get("pill") else int(_num(row.get("grams") if row.get("grams") is not None else p.get("grams", 0),
                                                     f"граммы «{p['name']}»", 5000))
        if grams <= 0 and not p.get("pill"):
            raise RationError(f"граммы «{p['name']}» в блюде — ноль")
        out.append({"product": p["id"], "grams": grams})
    return out


def put_dish(book: dict, name: str, *, new_name: str | None = None, kind: str | None = None,
             aliases: list[str] | None = None, add_aliases: list[str] | None = None, items: list[dict] | None = None,
             add: list[dict] | None = None, drop: list[str] | None = None, note: str | None = None,
             remove: bool = False) -> str:
    """Завести блюдо, поправить состав и слова или убрать его. Меняет book на месте."""
    dishes = book.setdefault("dishes", [])
    d = find(dishes, name, "блюдо")
    if remove:
        if d is None:
            raise RationError(f"блюда «{name}» в рационе нет")
        dishes.remove(d)
        return f"Убрал блюдо «{d['name']}»."
    created = d is None
    if created:
        if not items and not add:
            raise RationError(f"блюда «{name}» нет; чтобы завести — нужен состав (items: продукт и граммы)")
        d = {"id": _slug(new_name or name, {x["id"] for x in dishes}), "name": (new_name or name).strip(),
             "kind": "", "aliases": [], "items": []}
        dishes.append(d)
    elif new_name and norm(new_name) != norm(d["name"]):
        if any(norm(x["name"]) == norm(new_name) for x in dishes if x is not d):
            raise RationError(f"блюдо «{new_name}» уже есть")
        d["name"] = new_name.strip()
    if items is not None:
        d["items"] = _portions(book, items)
    for row in _portions(book, add):
        have = next((i for i in d["items"] if i["product"] == row["product"]), None)
        if have:
            have["grams"] = row["grams"]
        else:
            d["items"].append(row)
    for pname in drop or []:
        p = find(book.get("products") or [], pname, "продукт блюда")
        ids = [p["id"]] if p else []
        if not ids or not any(i["product"] in ids for i in d["items"]):
            raise RationError(f"в «{d['name']}» нет «{pname}»")
        d["items"] = [i for i in d["items"] if i["product"] not in ids]
    if not d["items"]:
        if created:
            dishes.remove(d)
        raise RationError(f"в «{d['name']}» не осталось продуктов — убрать блюдо целиком: remove")
    if kind is not None:
        k = norm(kind)
        if k in ("", "по времени", "любой", "любое"):
            d["kind"] = ""
        elif k in KINDS:
            d["kind"] = k
        else:
            raise RationError(f"приём — одно из: {', '.join(KINDS)} или пусто (по времени)")
    if aliases is not None or add_aliases:
        d["aliases"] = _aliases(d.get("aliases") or [], aliases, add_aliases)
    if note is not None:
        if note.strip():
            d["note"] = note.strip()
        else:
            d.pop("note", None)
    return ("Завёл" if created else "Поправил") + " блюдо: " + dish_line(book, d) + "."


# ── Текстом для Claude ──────────────────────────────────────────────────


def product_line(p: dict) -> str:
    s = p["name"]
    if p.get("pill"):
        s += " — добавка, 0 ккал"
    else:
        s += (f" — {_fmt(p.get('kcal100'))} ккал, Б{_fmt(p.get('p100'))} Ж{_fmt(p.get('f100'))} "
              f"У{_fmt(p.get('c100'))} на 100 г; порция {p.get('grams', 0)} г ({portion_kcal(p, p.get('grams', 0))} ккал)")
    if p.get("sure") and p["sure"] != "точно":
        s += f"; цифры {p['sure']}"
    if p.get("aliases"):
        s += "; говорит: " + ", ".join(p["aliases"])
    if p.get("note"):
        s += f"; {p['note']}"
    return s


def dish_line(book: dict, d: dict) -> str:
    by = {p["id"]: p for p in book.get("products") or []}
    parts = []
    for i in d.get("items") or []:
        p = by.get(i["product"])
        if p:
            parts.append(p["name"] if p.get("pill") else f"{p['name']} {i['grams']} г")
    s = d["name"]
    tags = []
    if d.get("kind"):
        tags.append(f"обычно {d['kind']}")
    if d.get("aliases"):
        tags.append("говорит: " + ", ".join(d["aliases"]))
    if tags:
        s += " (" + "; ".join(tags) + ")"
    s += ": " + ", ".join(parts) + f" — {dish_kcal(book, d)} ккал"
    if d.get("note"):
        s += f". {d['note']}"
    return s


def text(book: dict | None, rev: int, source: str, name: str | None = None) -> str:
    if not book:
        return "Рациона нет ни в Своде, ни заводского файла рядом с сервером."
    head = {"свод": f"Рацион Саши — Свод food.ration, версия {rev}" + (f", от {book['updated']}" if book.get("updated") else ""),
            "заводской": "Рацион Саши — в Своде ещё нет (телефон положит его с первым синком новой сборки); "
                         "это заводской файл. Первая правка отсюда сама положит его на сервер"}.get(source, "Рацион")
    products, dishes = book.get("products") or [], book.get("dishes") or []
    if name:
        d = find(dishes, name, "блюдо")
        if d is not None:
            return head + ".\n\n" + dish_line(book, d)
        p = find(products, name, "продукт")
        if p is None:
            return f"«{name}» в рационе нет. Блюда: {', '.join(x['name'] for x in dishes)}."
        line = product_line(p)
        if p.get("micro"):
            line += "\nВещества на 100 г: " + ", ".join(f"{k} {_fmt(v)}" for k, v in p["micro"].items())
        used = [x["name"] for x in dishes if any(i["product"] == p["id"] for i in x.get("items") or [])]
        if used:
            line += "\nВ блюдах: " + ", ".join(used)
        return head + ".\n\n" + line
    out = [head + ".", "", f"Блюда ({len(dishes)}):"]
    out += ["- " + dish_line(book, d) for d in dishes] or ["—"]
    out += ["", f"Продукты ({len(products)}):"]
    out += ["- " + product_line(p) for p in products] or ["—"]
    return "\n".join(out)


# ── Для коннектора: прочитать, поправить, записать ──────────────────────


def change(url: str, fn, reason: str, **kw) -> str:
    """Одна правка рациона целиком: прочитать свежий, поправить копию, записать поверх его версии."""
    book, rev, source = load(url)
    if book is None:
        raise RationError("рациона нет ни в Своде, ни заводского файла рядом с сервером")
    work = copy.deepcopy(book)
    msg = fn(work, **kw)
    new_rev = save(url, work, rev, reason)
    tail = f" Версия рациона {new_rev}" + (" — первая на сервере, начал с заводского файла" if source == "заводской" else "") + \
        "; телефон возьмёт её со следующим синком Дел."
    return msg + tail
