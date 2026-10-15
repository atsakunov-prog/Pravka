"""Время заходов очереди «Подскажи» — одно место правил (шаг 1 петли этапа 4, 15.10.2026).

Саша (ответ 1а, 15.10.2026): «с 19:00 в „Подскажи“ до семи кандидатов „Сейчас на завтра“». Тем же вечером он перенёс
заход Пн–Чт на 18:00: по ленте Засечки в 19:00 он уже с семьёй чаще (11 будних вечеров из 26, в 18:00 — 9), а свободные
10 минут чаще всего выпадают на 17:50–18:20. Пятница — раньше: в пятницу вечером шаббат, и вечерний заход пришёлся бы
на свечи. Правило простое и без свечей — Пт 14:00, а с 15.11 по 15.01 (свечи
в Москве раньше 16:00) — 13:30: его же посчитают веб и телефон шага 2, у которых часов восхода нет. Что заход «не позже
свечей − 2 ч» на каждой пятнице, держит тест на 2026–2027 по `sky.shabbat` (Москва с завода); не пройдёт на краю окна —
окно шире, а не точная формула. Сб–Вс заходов нет.

Время — Свод `asks.rhythm` поверх заводского по полю: кривое поле — заводское и строка журнала (раз на значение, а не
каждую минуту тика). Пишут claude и владелец, читает сервер; записи нет — заводское.

Шаг 1 заводит модуль и четыре поля (`evening`, `fri`, `fri_winter`, `winter`); шаг 2 дописывает сюда 15:00, 05:00,
пределы, фазы, штамп и вид, а в `FACTORY` — свои поля, эти четыре не трогает. План «Сейчас на завтра» (`plan.py`) время
прогона берёт отсюда: одно время на план и заходы шага 2.
"""

from __future__ import annotations

import datetime as dt
import logging
import re
from typing import Any

from .meetings import MSK

log = logging.getLogger("dela.asks")

SVOD_KEY = "asks.rhythm"
# Время по Москве «ЧЧ:ММ»; winter — с какого по какое число (ММ-ДД, через Новый год) пятница идёт по fri_winter.
FACTORY: dict[str, Any] = {"evening": "18:00", "fri": "14:00", "fri_winter": "13:30", "winter": ["11-15", "01-15"]}
FRIDAY = 4
_HM = re.compile(r"^([01]?\d|2[0-3]):([0-5]\d)$")
_MD = re.compile(r"^(\d{2})-(\d{2})$")
_warned: set[str] = set()


def _hm(v: Any) -> tuple[int, int] | None:
    m = _HM.match(str(v or "").strip())
    return (int(m.group(1)), int(m.group(2))) if m else None


def _md(v: Any) -> tuple[int, int] | None:
    m = _MD.match(str(v or "").strip())
    if not m:
        return None
    try:
        dt.date(2028, int(m.group(1)), int(m.group(2)))  # високосный: «02-29» — тоже день
    except ValueError:
        return None
    return int(m.group(1)), int(m.group(2))


def norm(value: Any) -> tuple[dict, list[str]]:
    """Свод поверх заводского по полю. Ответ — (ритм, что было криво словами). Незнакомые поля (шаг 2 допишет свои)
    проходят как есть: читатель знает свои и не трогает чужие."""
    out = {k: (list(v) if isinstance(v, list) else v) for k, v in FACTORY.items()}
    bad: list[str] = []
    if value is None:
        return out, bad
    if not isinstance(value, dict):
        return out, [f"{SVOD_KEY}: не словарь — заводское"]
    for k, v in value.items():
        if k in ("evening", "fri", "fri_winter"):
            hm = _hm(v)
            if hm:
                out[k] = f"{hm[0]:02d}:{hm[1]:02d}"
            else:
                bad.append(f"{k}={v!r} — не «ЧЧ:ММ»")
        elif k == "winter":
            if isinstance(v, list) and len(v) == 2 and all(_md(x) for x in v):
                out[k] = [str(x).strip() for x in v]
            else:
                bad.append(f"winter={v!r} — не [«ММ-ДД», «ММ-ДД»]")
        else:
            out[k] = v
    return out, bad


def rhythm(conn, owner: str) -> dict:
    """Ритм владельца: Свод asks.rhythm поверх заводского. Кривое поле — заводское и строка журнала."""
    row = conn.execute("SELECT value FROM crm.svod WHERE owner_id = %s AND key = %s", (owner, SVOD_KEY)).fetchone()
    out, bad = norm((row or {}).get("value"))
    for b in bad:
        if b not in _warned:
            _warned.add(b)
            log.warning("Свод %s: %s — беру заводское", SVOD_KEY, b)
    return out


def winter(rh: dict, day: dt.date) -> bool:
    """Зимнее окно пятницы (с завода 15.11–15.01): свечи в Москве раньше 16:00, заход — на полчаса раньше."""
    lo, hi = _md(rh["winter"][0]), _md(rh["winter"][1])
    md = (day.month, day.day)
    return lo <= md <= hi if lo <= hi else (md >= lo or md <= hi)


def evening_at(rh: dict, day: dt.date) -> dt.datetime | None:
    """Время вечернего захода дня по Москве: Пн–Чт — evening (18:00), Пт — fri (14:00) или в зимнем окне fri_winter
    (13:30), Сб–Вс — None (заходов нет)."""
    wd = day.weekday()
    if wd > FRIDAY:
        return None
    key = "evening" if wd < FRIDAY else "fri_winter" if winter(rh, day) else "fri"
    h, m = _hm(rh[key]) or _hm(FACTORY[key])
    return dt.datetime(day.year, day.month, day.day, h, m, tzinfo=MSK)
