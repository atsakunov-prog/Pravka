"""«Сейчас на завтра» (шаг 1 петли этапа 4, 15.10.2026, dela_0034, plan.py, asks.py): предел пяти на каждый день,
корзины кандидатов, пятница, один прогон в день, ответы кнопками, уборка, снимок «Сейчас», вид plan, контракт.

Люди и компании — выдуманные («Иван Тестов», «ООО Ромашка»). «Сегодня» — db_today (crm.today(), Москва); время прогона —
явным now по Москве: так тесты не зависят от того, в какой час и день недели их гоняют (и при TZ=UTC0)."""

from __future__ import annotations

import datetime as dt
import json
from pathlib import Path

import psycopg
import pytest
from psycopg.types.json import Jsonb

from pravka_dela import asks, krug, plan, sky, store
from pravka_dela import db as dela_db
from test_dela_schema import db_today, dela, project  # noqa: F401  (фикстура и помощники)

MSK = plan.MSK
ROOT = Path(__file__).resolve().parents[1]
DIRS = [
    {"key": "health", "pos": 0, "zone": "Я", "name": "Здоровье", "short": "Здоровье", "formula": "Тело в форме."},
    {"key": "work", "pos": 3, "zone": "Мир", "name": "Дело", "short": "Дело", "formula": "Дело растёт."},
]


def ops(url, user, *items, via="app"):
    return store.apply_ops(url, user, list(items), via)["results"]


def ok(url, user, *items):
    res = ops(url, user, *items)
    assert all(r["ok"] for r in res), res
    return res


def mk(url, user="sasha", **task):
    return ok(url, user, {"op": "task.create", "task": task})[0]["task"]


def aged(url, tid, days: int):
    """Дело заведено days дней назад (ждёт столько)."""
    with dela_db.session(url, "system", "test") as c:
        c.execute("UPDATE tasks.tasks SET created_at = now() - make_interval(days => %s) WHERE id = %s", (days, tid))


def at(day: dt.date, h: int, m: int = 0) -> dt.datetime:
    return dt.datetime(day.year, day.month, day.day, h, m, tzinfo=MSK)


def cfg_of(url):
    with dela_db.session(url, "sasha", via="test") as c:
        return plan.config(c, "sasha")


def build(url, today, for_date, now=None):
    with dela_db.session(url, "sasha", "svc:plan", "plan") as c:
        return plan.build(c, "sasha", today, for_date, plan.config(c, "sasha"), now or at(today, 18, 5))


def plan_sugs(url, status=None):
    with dela_db.session(url, "system", "test") as c:
        q = "SELECT * FROM tasks.suggestions WHERE source = 'plan'" + (" AND status = %s" if status else "") + " ORDER BY payload ->> 'order'"
        return c.execute(q, (status,) if status else ()).fetchall()


def sug_of(url, task_id):
    return next(s for s in plan_sugs(url) if str(s["task_id"]) == str(task_id))


def decide(url, s, **kw):
    return ops(url, "sasha", {"op": "suggestion.decide", "id": str(s["id"]), **kw})[0]


# ── 1. Предел пяти — на каждый день ─────────────────────────────────────

def test_now_limit_is_per_day(dela):
    today = db_today(dela)
    tmrw, yday = today + dt.timedelta(days=1), today - dt.timedelta(days=1)
    for i in range(5):
        mk(dela, title=f"Сегодня {i}", focus_on=today.isoformat())
    # Пять на сегодня не мешают завтрашним.
    for i in range(5):
        mk(dela, title=f"Завтра {i}", focus_on=tmrw.isoformat())
    r = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Шестое завтра", "focus_on": tmrw.isoformat()}})[0]
    assert not r["ok"] and f"на {store.day_label(tmrw)} уже пять дел" in r["error"] and "сначала убери одно" in r["error"]
    extra = mk(dela, title="Лишнее")
    r = ops(dela, "sasha", {"op": "task.set", "id": extra["id"], "set": {"focus_on": tmrw.isoformat()}})[0]
    assert not r["ok"] and "уже пять дел" in r["error"]
    # Сегодня — прежние слова, без дня.
    r = ops(dela, "sasha", {"op": "task.set", "id": extra["id"], "set": {"focus_on": today.isoformat()}})[0]
    assert not r["ok"] and r["error"].startswith("в «Сейчас» уже пять дел")
    # Правка уже стоящего на завтра проходит; прошедший день не проверяется.
    t = store.view(dela, "sasha", "search", q="Завтра 3")["items"][0]
    assert ops(dela, "sasha", {"op": "task.set", "id": t["id"], "set": {"title": "Завтра 3 — уточнил"}})[0]["ok"]
    for i in range(6):
        mk(dela, title=f"Вчера {i}", focus_on=yday.isoformat())
    # Снял одно с завтра — место есть.
    assert ops(dela, "sasha", {"op": "task.set", "id": t["id"], "set": {"focus_on": None}})[0]["ok"]
    assert ops(dela, "sasha", {"op": "task.set", "id": extra["id"], "set": {"focus_on": tmrw.isoformat()}})[0]["ok"]


# ── 2. Корзины ───────────────────────────────────────────────────────────

@pytest.fixture()
def world(dela):
    """Набор на каждую корзину плюс то, что кандидатом быть не должно."""
    today = db_today(dela)
    tmrw = today + dt.timedelta(days=1)
    ok(dela, "sasha", {"op": "svod.set", "key": krug.DIRECTIONS_KEY, "value": DIRS, "author": "test"})
    p = project(dela, "sasha", "ООО Ромашка")
    q = project(dela, "sasha", "ООО Лютик")
    with dela_db.session(dela, "sasha", via="test") as c:
        ivan = c.execute("INSERT INTO crm.people (name, short, owner_id) VALUES ('Иван Тестов', 'Иван', 'sasha') "
                         "RETURNING id").fetchone()["id"]
        ppl = [c.execute("INSERT INTO crm.people (name, short, owner_id) VALUES (%s, %s, 'sasha') RETURNING id",
                         (f"{n} Тестов", n)).fetchone()["id"] for n in ("Пётр", "Олег", "Глеб", "Лев")]
    w = {"today": today, "tmrw": tmrw, "p": p, "ivan": ivan}
    deal = ok(dela, "sasha", {"op": "deal.create", "data": {"project_id": str(p), "name": "Ромашка: продажа доли", "stage": "active"}})[0]["row"]
    quiet = ok(dela, "sasha", {"op": "deal.create", "data": {"project_id": str(q), "name": "Лютик: аудит", "stage": "proposal"}})[0]["row"]
    w["deal"], w["quiet"] = deal, quiet
    # carry: три в «Сейчас» сегодня — «Отбить» первым, потом старше.
    w["c_owed"] = mk(dela, title="Иван Тестов: ответить по договору", ball="owed", person_id=str(ivan), focus_on=today.isoformat())
    w["c_old"] = mk(dela, title="Собрать отчёт", focus_on=today.isoformat())
    aged(dela, w["c_old"]["id"], 5)
    w["c_new"] = mk(dela, title="Позвонить в банк", focus_on=today.isoformat())
    # owed: горят к завтра — со сделкой, дольше ждёт, срок завтра; свежее без срока — нет.
    w["o_deal"] = mk(dela, title="Ромашка: прислать модель", ball="owed", project_id=str(p), deal_id=deal["id"])
    aged(dela, w["o_deal"]["id"], 1)
    w["o_old"] = mk(dela, title="Ответить Петру", ball="owed", person_id=str(ppl[0]))
    aged(dela, w["o_old"]["id"], 6)
    w["o_due"] = mk(dela, title="Отправить счёт", ball="owed", due_date=tmrw.isoformat())
    w["o_mid"] = mk(dela, title="Ответить Олегу", ball="owed", person_id=str(ppl[1]))
    aged(dela, w["o_mid"]["id"], 3)
    w["o_fresh"] = mk(dela, title="Свежее отбить", ball="owed")
    # Голое «срок завтра» — не кандидат (оно и так будет в «Сегодня»), как и уже стоящее на завтра, чужое и цель.
    w["due_tmrw"] = mk(dela, title="Срок завтра", due_date=tmrw.isoformat())
    w["on_tmrw"] = mk(dela, title="Уже на завтра", ball="owed", focus_on=tmrw.isoformat())
    aged(dela, w["on_tmrw"]["id"], 9)
    w["foreign"] = mk(dela, user="natasha", title="Чужое отбить", ball="owed")
    aged(dela, w["foreign"]["id"], 9)
    # nudge: четыре «жду» дольше недели у четырёх людей — одной строкой трое.
    w["waits"] = []
    for i, pid in enumerate([ppl[0], ppl[1], ppl[2], ppl[3]]):
        t = mk(dela, title=f"Жду ответа {i}", ball="waiting", person_id=str(pid))
        with dela_db.session(dela, "system", "test") as c:
            c.execute("UPDATE tasks.tasks SET waiting_since = %s WHERE id = %s", (today - dt.timedelta(days=10 + i), t["id"]))
        w["waits"].append(t)
    # Круг: шаг к цели «Здоровье» (направление стоит), шаг к цели «Дело» (сегодня закрыто дело — не стоит).
    g = mk(dela, title="Тело в форме", goal=True, directions=["health"])
    w["step"] = mk(dela, title="Записаться в бассейн", why_id=g["id"])
    gw = mk(dela, title="Дело растёт", goal=True, directions=["work"])
    mk(dela, title="Шаг к делу", why_id=gw["id"])
    done = mk(dela, title="Сделано по делу", directions=["work"])
    ok(dela, "sasha", {"op": "task.done", "id": done["id"]})
    return w


def titles(out):
    return [x["title"] for x in out["items"]]


def test_baskets_quota_and_order(world, dela, monkeypatch):
    w = world
    out = build(dela, w["today"], w["tmrw"])
    baskets = [x["basket"] for x in out["items"]]
    # Места: резерв Круга 1 → owed 3 → deal 1 → carry 2 = семь; «напомнить» не влезло. Показ: carry → owed → deal → krug.
    assert out["limit"] == 7 and len(out["items"]) == 7, out["items"]
    assert baskets == ["carry", "carry", "owed", "owed", "owed", "deal", "krug"], baskets
    t = titles(out)
    assert t[:2] == ["Иван Тестов: ответить по договору", "Собрать отчёт"], "«Отбить» первым, потом старше"
    assert t[2:5] == ["Ромашка: прислать модель", "Ответить Петру", "Ответить Олегу"], "живая сделка, дольше ждёт"
    assert t[5] == "Решить следующий шаг: Лютик: аудит" and t[6] == "Записаться в бассейн"
    for bad in ("Свежее отбить", "Срок завтра", "Уже на завтра", "Чужое отбить", "Тело в форме", "Шаг к делу",
                "Позвонить в банк", "Отправить счёт"):
        assert bad not in t
    why = {x["title"]: x for x in out["items"]}
    assert why["Ответить Петру"]["why"] == "Пётр ждёт 6 дней" and why["Ответить Петру"]["from"].startswith("руками ")
    assert why["Ромашка: прислать модель"]["why"] == "ждёт 1 день · сделка живая"
    assert why["Собрать отчёт"]["why"].endswith("переносится 2-й день")
    assert why["Записаться в бассейн"]["why"] == "направление «Здоровье» — закрытых дел ещё не было"
    assert why["Записаться в бассейн"]["from"] == "Круг жизни" and why["Решить следующий шаг: Лютик: аудит"]["from"] == "Воронка"
    # «Откуда» — словами, а не кодом источника: незнакомый код — «руками», не сам код.
    at_ = dt.datetime(2026, 10, 15, 12, tzinfo=MSK)
    task = {"id": w["c_new"]["id"], "created_at": at_, "source": "manual"}
    for src, word in (("plan", "план на завтра"), ("phone", "телефон"), ("user", "руками"), ("krug", "разборщик Круга"),
                      ("contacts", "телефонная книжка"), ("userbot", "Telegram"), ("нечто", "руками")):
        monkeypatch.setattr(plan.store, "task_origin", lambda c, t, s=src: {"kind": "suggestion", "source": s, "at": at_})
        assert plan.from_line(None, {"basket": "owed", "task": task}) == f"{word} 15.10", src
    monkeypatch.setattr(plan.store, "task_origin", lambda c, t: None)
    assert plan.from_line(None, {"basket": "owed", "task": {**task, "source": "нечто"}}) == "руками 15.10"


def test_nudge_and_no_krug_reserve(world, dela, monkeypatch):
    """Шага к стоящему направлению нет — места Круга нет (квоты не перетекают), и тогда влезает «напомнить троим»; без
    deal_steps корзины сделки нет."""
    w = world
    ok(dela, "sasha", {"op": "task.done", "id": w["step"]["id"]})
    monkeypatch.setattr(store, "FEATURES", [f for f in store.FEATURES if f != "deal_steps"])
    out = build(dela, w["today"], w["tmrw"])
    baskets = [x["basket"] for x in out["items"]]
    assert baskets == ["carry", "carry", "owed", "owed", "owed", "nudge"], baskets
    nudge = out["items"][-1]
    assert nudge["title"] == "Напомнить: Лев, Глеб, Олег" and nudge["why"].startswith("жду дольше недели: Лев 13 дн.")
    op = next(o for o in out["ops"] if o["suggestion"]["payload"]["basket"] == "nudge")
    assert len(op["suggestion"]["payload"]["nudge_ids"]) == 3 and op["suggestion"]["kind"] == "create"


# ── 3. Пятница и время заходов ───────────────────────────────────────────

def test_rhythm_friday_and_candles():
    rh, bad = asks.norm(None)
    assert not bad
    assert asks.evening_at(rh, dt.date(2026, 10, 15)) == at(dt.date(2026, 10, 15), 18)       # чт (Саша, 15.10: не 19:00)
    assert asks.evening_at(rh, dt.date(2026, 10, 12)) == at(dt.date(2026, 10, 12), 18)       # пн
    assert asks.evening_at(rh, dt.date(2026, 10, 15)) + plan.LATE == at(dt.date(2026, 10, 15), 22)  # догонка — до 22:00
    assert asks.evening_at(rh, dt.date(2026, 10, 16)) == at(dt.date(2026, 10, 16), 14)       # пт
    assert asks.evening_at(rh, dt.date(2026, 11, 13)) == at(dt.date(2026, 11, 13), 14)       # до окна
    assert asks.evening_at(rh, dt.date(2026, 11, 20)) == at(dt.date(2026, 11, 20), 13, 30)   # в окне
    assert asks.evening_at(rh, dt.date(2027, 1, 15)) == at(dt.date(2027, 1, 15), 13, 30)     # последний день окна
    assert asks.evening_at(rh, dt.date(2027, 1, 22)) == at(dt.date(2027, 1, 22), 14)
    assert asks.evening_at(rh, dt.date(2026, 10, 17)) is None and asks.evening_at(rh, dt.date(2026, 10, 18)) is None
    # Каждая пятница 2026–2027: заход не позже свечей − 2 ч (Москва с завода).
    lat, lon = sky.DEFAULT_PLACE["lat"], sky.DEFAULT_PLACE["lon"]
    day = dt.date(2026, 1, 2)
    while day.year < 2028:
        sh = sky.shabbat(at(day, 0), lat, lon)
        candles = dt.datetime.fromisoformat(sh["candles_at"])
        assert sh["friday"] == day.isoformat()
        assert asks.evening_at(rh, day) <= candles - dt.timedelta(hours=2), (day, sh["candles"])
        day += dt.timedelta(days=7)
    # Свод поверх заводского по полю; кривое поле — заводское.
    rh, bad = asks.norm({"fri": "13:45", "evening": "25:00", "winter": ["11-31", "01-15"], "later": "15:00"})
    assert rh["fri"] == "13:45" and rh["evening"] == "18:00" and rh["winter"] == ["11-15", "01-15"] and rh["later"] == "15:00"
    assert len(bad) == 2
    assert plan.next_day(dt.date(2026, 10, 16)) == dt.date(2026, 10, 19) and plan.next_day(dt.date(2026, 10, 15)) == dt.date(2026, 10, 16)
    assert plan.next_day(dt.date(2026, 10, 18)) == dt.date(2026, 10, 19) and plan.run_day(dt.date(2026, 10, 19)) == dt.date(2026, 10, 16)


def test_friday_catch_up_before_candles():
    """Пятничная догонка (поезд 16.10.2026, Шаббат): служба лежала в заход — кандидатов на понедельник сервер кладёт не
    дольше часа после захода и не позже свечей − 1 ч, что раньше; будни — 4 часа (Пн–Чт до 22:00). Каждая пятница
    2026–2027 (Москва с завода), как тест «заход не позже свечей − 2 ч»."""
    rh, _ = asks.norm(None)
    thu = asks.evening_at(rh, dt.date(2026, 10, 15))
    assert plan.catch_until(thu) == at(dt.date(2026, 10, 15), 22)
    lat, lon = sky.DEFAULT_PLACE["lat"], sky.DEFAULT_PLACE["lon"]
    day, margin = dt.date(2026, 1, 2), dt.timedelta(days=1)
    while day.year < 2028:
        slot = asks.evening_at(rh, day)
        until = plan.catch_until(slot)
        candles = dt.datetime.fromisoformat(sky.shabbat(at(day, 0), lat, lon)["candles_at"])
        assert slot < until <= slot + dt.timedelta(hours=1), (day, until)
        assert until <= candles - dt.timedelta(hours=1), (day, until, candles)
        margin = min(margin, candles - until)
        day += dt.timedelta(days=7)
    assert margin >= dt.timedelta(hours=1)
    # Время из Свода позже: 14:15 в декабре (свечи 15:38) — догонка до свечей − 1 ч, а не до 15:15; заход после свечей
    # − 1 ч — окна нет вовсе.
    dec = dt.date(2026, 12, 18)
    candles = dt.datetime.fromisoformat(sky.shabbat(at(dec, 0), lat, lon)["candles_at"])
    late, _ = asks.norm({"fri_winter": "14:15"})
    assert plan.catch_until(asks.evening_at(late, dec)) == candles - dt.timedelta(hours=1) < at(dec, 15, 15)
    slot = at(dec, 15)
    assert plan.catch_until(slot) < slot
    # Место — Свод today.weather (как «Сегодня»): в Иерусалиме свечи позже, чем в Москве в декабре.
    jer = {"lat": 31.78, "lon": 35.22}
    assert plan.catch_until(slot, jer) == slot + dt.timedelta(hours=1)


def test_tick_friday_catches_up_before_candles(dela):
    """tick в пятницу (18.12.2026: заход 13:30, свечи в Москве 15:38): догонка — до 14:30, позже прогона нет."""
    fri = dt.date(2026, 12, 18)
    assert (plan.tick(dela, at(fri, 14, 31)) or {}).get("run") is None
    got = plan.tick(dela, at(fri, 14, 29))
    assert got["run"]["for_date"] == "2026-12-21", got
    # Свод: пятница в 14:15 — окно до свечей − 1 ч.
    ok(dela, "sasha", {"op": "svod.set", "key": asks.SVOD_KEY, "value": {"fri_winter": "14:15"}, "author": "test"})
    with dela_db.session(dela, "system", "test") as c:
        c.execute("UPDATE crm.state SET value = value - 'run_day' WHERE key = 'plan'")
    candles = dt.datetime.fromisoformat(sky.shabbat(at(fri, 0), sky.DEFAULT_PLACE["lat"], sky.DEFAULT_PLACE["lon"])["candles_at"])
    edge = candles - dt.timedelta(hours=1)
    assert (plan.tick(dela, edge + dt.timedelta(minutes=1)) or {}).get("run") is None
    assert plan.tick(dela, edge - dt.timedelta(minutes=1))["run"]["for_date"] == "2026-12-21"


def test_rhythm_from_svod(dela):
    ok(dela, "sasha", {"op": "svod.set", "key": asks.SVOD_KEY, "value": {"fri": "13:15", "evening": "кривое"}, "author": "test"})
    with dela_db.session(dela, "sasha", via="test") as c:
        rh = asks.rhythm(c, "sasha")
    assert rh["fri"] == "13:15" and rh["evening"] == "18:00"


def test_friday_three_for_monday(dela):
    today = db_today(dela)
    fri = today + dt.timedelta(days=(4 - today.weekday()) % 7 or 7)
    mon = plan.next_day(fri)
    for i in range(5):
        t = mk(dela, title=f"Ответить {i}", ball="owed")
        aged(dela, t["id"], 3 + i)
    out = build(dela, fri, mon, at(fri, 14, 5))
    assert out["limit"] == 3 and len(out["items"]) == 3
    assert out["label"] == store.day_label(mon) and out["title"].startswith(f"Сейчас на {store.day_label(mon)}")
    opts = out["ops"][0]["suggestion"]["payload"]["options"]
    assert [o["label"] for o in opts] == ["☆ На понедельник", "✓ Уже сделал", "Не надо"]
    assert opts[0]["focus_on"] == mon.isoformat()


# ── 4. Один прогон в день и догонка ──────────────────────────────────────

def test_tick_runs_once_and_catches_up_late(dela, monkeypatch):
    today = db_today(dela)
    monkeypatch.setattr(plan.asks, "evening_at", lambda rh, day: at(day, 18))
    # Догонка будней — 4 часа в любой день прогона тестов (и в пятницу); пятница — test_friday_catch_up_before_candles.
    monkeypatch.setattr(plan, "catch_until", lambda slot, where=None: slot + plan.LATE)
    for i in range(3):
        t = mk(dela, title=f"Ответить {i}", ball="owed")
        aged(dela, t["id"], 4)
    assert (plan.tick(dela, at(today, 17, 59)) or {}).get("run") is None
    first = plan.tick(dela, at(today, 18, 1))
    assert first["run"]["made"] == 3, first
    again = plan.tick(dela, at(today, 18, 2))
    assert again is None or "run" not in again
    assert len(plan_sugs(dela)) == 3
    # Прогон руками тем же днём — пачка дня уже есть: ничего нового.
    r = plan.run(dela, at(today, 18, 3))
    assert r["skipped"].endswith("уже предложено 3 — второй прогон ничего не пишет") and r["offered"] == 3, r
    assert len(plan_sugs(dela)) == 3
    # Служба лежала: догонка — до четырёх часов после слота, позже — нет.
    with dela_db.session(dela, "system", "test") as c:
        c.execute("UPDATE crm.state SET value = value - 'run_day' WHERE key = 'plan'")
    assert "run" not in (plan.tick(dela, at(today, 22, 1)) or {})
    late = plan.tick(dela, at(today, 21, 59))["run"]
    assert "уже предложено 3" in late["skipped"], late  # кандидаты дня уже в базе — новых нет
    assert len(plan_sugs(dela)) == 3
    with dela_db.session(dela, "system", "t") as c:
        st = c.execute("SELECT value FROM crm.state WHERE key = 'plan'").fetchone()["value"]
    assert st["run_day"] == today.isoformat() and st["last"]["made"] == 3, "итог первого прогона не затёрт"


def test_second_run_same_day_writes_nothing(world, dela, monkeypatch):
    """Один прогон на день — правдой базы: второй plan.run на тот же день (руками, plan --for, служба упала между
    записью кандидатов и памятью run_day) ничего не пишет, даже когда Саша часть уже решил, а подходящих дел больше семи
    (blocked держит только ждущих, отказанных и дважды молчавших — без этого в пачке становилось 11)."""
    w = world
    tm = w["tmrw"]
    first = plan.run(dela, at(w["today"], 18, 5), for_date=tm)
    assert first["made"] == 7 and not first["errors"], first
    # Саша ответил на часть: отметил, «не надо», одно дело закрыл — уборка его погасила.
    assert decide(dela, sug_of(dela, w["o_mid"]["id"]), option=0)["ok"]
    s = sug_of(dela, w["c_old"]["id"])
    assert decide(dela, s, option=len(s["payload"]["options"]) - 1)["ok"]
    ok(dela, "sasha", {"op": "task.done", "id": w["o_old"]["id"]})
    assert plan.sweep(dela, at(w["today"], 20)) == 1
    again = plan.run(dela, at(w["today"], 20, 30), for_date=tm)
    assert again == {"skipped": f"на {store.day_label(tm)} уже предложено 7 — второй прогон ничего не пишет",
                     "for_date": tm.isoformat(), "label": store.day_label(tm), "offered": 7}
    assert len(plan_sugs(dela)) == 7 and store.view(dela, "sasha", "plan")["offered"] == 7
    # Вхолостую — показывает, что предложил бы, и что на день уже предложено; ничего не пишет.
    dry = plan.run(dela, at(w["today"], 20, 31), for_date=tm, dry=True)
    assert dry["dry"] and dry["offered"] == 7 and dry["items"] and len(plan_sugs(dela)) == 7
    # Служба перезапустилась между прогоном и памятью run_day: tick в окне догонки — тоже ничего.
    monkeypatch.setattr(plan.asks, "evening_at", lambda rh, day: at(day, 18))
    monkeypatch.setattr(plan, "catch_until", lambda slot, where=None: slot + plan.LATE)  # будни — и в пятницу тестов
    monkeypatch.setattr(plan, "next_day", lambda d: d + dt.timedelta(days=1))
    got = plan.tick(dela, at(w["today"], 21))
    assert "уже предложено 7" in got["run"]["skipped"], got
    assert len(plan_sugs(dela)) == 7
    # На другой день — свой прогон, как обычно.
    nxt = plan.run(dela, at(w["today"], 21, 5), for_date=tm + dt.timedelta(days=1))
    assert nxt.get("made") and not nxt.get("skipped"), nxt


# ── 5. Ответы ────────────────────────────────────────────────────────────

def test_answers(world, dela):
    w = world
    out = plan.run(dela, at(w["today"], 18, 5), for_date=w["tmrw"])
    assert out["made"] == 7 and not out["errors"], out
    tm = w["tmrw"].isoformat()
    # Вариант 0 — «☆ На завтра»: focus_on завтра.
    s = sug_of(dela, w["o_old"]["id"])
    assert s["kind"] == "update" and s["batch_ref"] == f"plan:{tm}" and s["payload"]["for_date"] == tm
    assert s["payload"]["ask"].startswith(f"В «Сейчас» на {store.day_label(w['tmrw'])}?")
    r = decide(dela, s, option=0)
    assert r["ok"] and str(r["task"]["focus_on"]) == tm and r["suggestion"]["reason"] == "ответ: ☆ На завтра"
    # Старое «Да» без варианта — то же.
    r = decide(dela, sug_of(dela, w["o_mid"]["id"]), decision="accept")
    assert r["ok"] and str(r["task"]["focus_on"]) == tm
    # «✓ Уже сделал» у обещания — дело закрыто ответом, «Сейчас» завтра не ставится, в «Сделано само» не попадает.
    s = sug_of(dela, w["o_deal"]["id"])
    i = [o["label"] for o in s["payload"]["options"]].index("✓ Уже сделал")
    r = decide(dela, s, option=i)
    assert r["ok"] and r["task"]["status"] == "done" and r["task"]["focus_on"] is None
    assert r["suggestion"]["reason"] == "ответ: ✓ Уже сделал" and r["suggestion"]["reason"] not in store.AUTO_REASONS
    # «Не надо» — отказ с причиной; дело не вернётся следующим вечером (3 дня).
    s = sug_of(dela, w["c_old"]["id"])
    r = decide(dela, s, option=len(s["payload"]["options"]) - 1)
    assert r["ok"] and r["suggestion"]["status"] == "rejected" and r["suggestion"]["reason"] == "план: не надо"
    # Шестое на завтра — отказ словами с днём (пять уже: стоявшее, два отмеченных и два поставленных руками).
    for k in range(2):
        mk(dela, title=f"Руками на завтра {k}", focus_on=tm)
    r = decide(dela, sug_of(dela, w["c_owed"]["id"]), option=0)
    assert not r["ok"] and f"на {store.day_label(w['tmrw'])} уже пять дел" in r["error"]
    # Сделка: create — дело со сделкой и клиентом, сразу на завтра.
    for k in range(2):
        ok(dela, "sasha", {"op": "task.set", "id": store.view(dela, "sasha", "search", q=f"Руками на завтра {k}")["items"][0]["id"],
                           "set": {"focus_on": None}})
    s = next(x for x in plan_sugs(dela, "pending") if x["payload"]["basket"] == "deal")
    r = decide(dela, s, option=0)
    assert r["ok"] and str(r["task"]["deal_id"]) == str(w["quiet"]["id"]) and str(r["task"]["focus_on"]) == tm
    assert r["task"]["title"] == "Решить следующий шаг: Лютик: аудит"
    # Дело из кандидата плана на следующий вечер придёт с «откуда: план на завтра …», а не «plan …».
    with dela_db.session(dela, "sasha", via="t") as c:
        assert plan.from_line(c, {"basket": "carry", "task": r["task"]}).startswith("план на завтра ")
    # Счёт автоматики и число «Нового» плана не видят.
    with dela_db.session(dela, "sasha", via="t") as c:
        assert store.automation(c, "sasha")["all"]["asked"] == 0
    assert store.view(dela, "sasha", "today")["new_count"] == 0
    # Следующий вечер: отказанное и отмеченное — не кандидаты.
    nxt = plan.run(dela, at(w["tmrw"], 18, 5), for_date=w["tmrw"] + dt.timedelta(days=1), dry=True)
    assert "Собрать отчёт" not in titles(nxt)


def test_nudge_accept_sets_nudge_on(world, dela, monkeypatch):
    w = world
    ok(dela, "sasha", {"op": "task.done", "id": w["step"]["id"]})
    monkeypatch.setattr(store, "FEATURES", [f for f in store.FEATURES if f != "deal_steps"])
    plan.run(dela, at(w["today"], 18, 5), for_date=w["tmrw"])
    s = next(x for x in plan_sugs(dela) if x["payload"]["basket"] == "nudge")
    r = decide(dela, s, option=0)
    assert r["ok"] and str(r["task"]["focus_on"]) == w["tmrw"].isoformat() and r["task"]["title"].startswith("Напомнить:")
    assert len(r["suggestion"]["result"]["nudged"]) == 3
    with dela_db.session(dela, "sasha", via="t") as c:
        on = {str(x["id"]): x["nudge_on"] for x in c.execute("SELECT id, nudge_on FROM tasks.tasks WHERE ball = 'waiting'").fetchall()}
    assert sorted(str(k) for k, v in on.items() if v == w["tmrw"]) == sorted(r["suggestion"]["result"]["nudged"])


def test_done_option_only_for_plan(dela):
    """Флаг done в варианте ответа закрывает дело только у кандидата плана: у вопроса автоматики (здесь — разборщика
    Круга) он ничего не делает — status варианту не дан нарочно."""
    t = mk(dela, title="Купить магний")
    s = ok(dela, "system", {"op": "suggestion.create", "suggestion": {
        "for_user": "sasha", "kind": "update", "task_id": t["id"], "source": "krug", "batch_ref": "krug:x",
        "payload": {"ask": "Куда?", "options": [{"label": "Готово", "done": True}, {"label": "Нет", "reject": True}]}}})[0]["suggestion"]
    assert s["payload"]["options"][0] == {"label": "Готово", "done": True}
    r = decide(dela, s, option=0)
    assert r["ok"] and r["task"]["status"] == "open"


def test_silence_twice_pauses(dela):
    """Два прогона подряд без ответа — пауза 3 дня; один раз — может прийти снова."""
    today = db_today(dela)
    t = mk(dela, title="Ответить Ивану Тестову", ball="owed")
    aged(dela, t["id"], 5)
    u = mk(dela, title="Ответить Петру Тестову", ball="owed")
    aged(dela, u["id"], 5)
    now = dt.datetime.now(dt.timezone.utc)
    with dela_db.session(dela, "system", "test") as c:
        for k, ids in ((2, [t["id"], u["id"]]), (1, [t["id"]])):
            d = (today - dt.timedelta(days=k - 1)).isoformat()
            for tid in ids:
                c.execute("INSERT INTO tasks.suggestions (for_user, kind, task_id, source, source_ref, batch_ref, payload, "
                          "status, reason, decided_at, created_by) VALUES ('sasha', 'update', %s, 'plan', %s, %s, %s, "
                          "'expired', %s, %s, 'sasha')",
                          (tid, f"plan:{d}:owed:{tid}", f"plan:{d}", Jsonb({"basket": "owed", "for_date": d}),
                           plan.REASON_SILENT, now - dt.timedelta(hours=10 * k)))
    out = build(dela, today, today + dt.timedelta(days=1))
    assert titles(out) == ["Ответить Петру Тестову"], "дважды молчал — пауза; Пётр промолчал раз (в первом прогоне)"


# ── 6. Уборка ────────────────────────────────────────────────────────────

def test_sweep(world, dela):
    w = world
    plan.run(dela, at(w["today"], 18, 5), for_date=w["tmrw"])
    tm = w["tmrw"].isoformat()
    keep = sug_of(dela, w["o_mid"]["id"])
    assert decide(dela, keep, option=0)["ok"]
    ok(dela, "sasha", {"op": "task.done", "id": w["o_old"]["id"]})                               # закрыто до отметки
    ok(dela, "sasha", {"op": "task.set", "id": w["c_old"]["id"], "set": {"focus_on": tm}})      # само встало на завтра
    assert plan.sweep(dela, at(w["today"], 20)) == 2
    assert sug_of(dela, w["o_old"]["id"])["reason"] == plan.REASON_CLOSED
    assert sug_of(dela, w["c_old"]["id"])["reason"] == plan.REASON_ALREADY
    # 12:00 дня плана — гаснут все ждущие, принятое не трогается.
    assert plan.sweep(dela, at(w["tmrw"], 11, 59)) == 0
    n = len(plan_sugs(dela, "pending"))
    assert n and plan.sweep(dela, at(w["tmrw"], 12, 0)) == n
    assert {s["reason"] for s in plan_sugs(dela, "expired")} >= {plan.REASON_SILENT}
    assert sug_of(dela, w["o_mid"]["id"])["status"] == "accepted"


# ── 7. Снимок «Сейчас» ───────────────────────────────────────────────────

def rows_of(url, day):
    with dela_db.session(url, "sasha", via="t") as c:
        return {r["title"]: r for r in c.execute(
            "SELECT t.title, n.* FROM tasks.now_days n JOIN tasks.tasks t ON t.id = n.task_id WHERE n.day = %s", (day,)).fetchall()}


def test_snapshot_days_and_carried(dela):
    today = db_today(dela)
    tmrw = today + dt.timedelta(days=1)
    d2, d1 = today - dt.timedelta(days=2), today - dt.timedelta(days=1)
    t = mk(dela, title="Тянется третий день", focus_on=d2.isoformat())
    assert plan.snap(dela, d2) == 1
    ok(dela, "sasha", {"op": "task.set", "id": t["id"], "set": {"focus_on": d1.isoformat()}})
    plan.snap(dela, d1)
    ok(dela, "sasha", {"op": "task.set", "id": t["id"], "set": {"focus_on": today.isoformat()}})
    a = mk(dela, title="Перенесу на завтра", focus_on=today.isoformat())
    b = mk(dela, title="Закрыл вчера, стоит на завтра", focus_on=tmrw.isoformat())
    ok(dela, "sasha", {"op": "task.done", "id": b["id"]})
    plan.snap(dela, today)
    rows = rows_of(dela, today)
    assert rows["Тянется третий день"]["carried"] == 3 and rows["Перенесу на завтра"]["carried"] == 1
    assert [r["carried"] for r in rows_of(dela, d1).values()] == [2]
    # Перенёс кнопкой на завтра — строка в сегодняшнем дне остаётся; сделал после переноса — done.
    ok(dela, "sasha", {"op": "task.set", "id": a["id"], "set": {"focus_on": tmrw.isoformat()}})
    ok(dela, "sasha", {"op": "task.done", "id": a["id"]})
    plan.snap(dela, today)
    rows = rows_of(dela, today)
    assert rows["Перенесу на завтра"]["done"] and rows["Перенесу на завтра"]["done_at"]
    # Закрытое сегодня, но стоящее на завтра, в завтрашний день не попадает (закрыто не в тот день).
    plan.snap(dela, tmrw)
    assert "Закрыл вчера, стоит на завтра" not in rows_of(dela, tmrw)
    # «Сегодня сделано x из y» — с перенесённым: оно было в «Сейчас» сегодня.
    with dela_db.session(dela, "sasha", via="t") as c:
        assert store.now_counts(c, "sasha", today) == {"total": 2, "done": 1}
    # Третий день — не кандидат, а строка stuck.
    out = build(dela, today, tmrw)
    assert "Тянется третий день" not in titles(out)
    assert [(x["title"], x["days"]) for x in out["stuck"]] == [("Тянется третий день", 3)]


def test_tick_snaps_and_catches_up_missed_day(dela, monkeypatch):
    today = db_today(dela)
    monkeypatch.setattr(plan.asks, "evening_at", lambda rh, day: None)  # без прогона — только снимки
    yday = today - dt.timedelta(days=1)
    mk(dela, title="Вчерашнее", focus_on=yday.isoformat())
    mk(dela, title="Сегодняшнее", focus_on=today.isoformat())
    plan.tick(dela, at(today, 10, 0))
    assert "Вчерашнее" in rows_of(dela, yday), "пропущенный день догнан"
    assert rows_of(dela, today) == {}, "до 11:00 снимка дня нет"
    plan.tick(dela, at(today, 11, 1))
    assert "Сегодняшнее" in rows_of(dela, today)
    with dela_db.session(dela, "system", "t") as c:
        st = c.execute("SELECT value FROM crm.state WHERE key = 'plan'").fetchone()["value"]
    assert st["snaps"][today.isoformat()] == ["11"] and "2355" in st["snaps"][yday.isoformat()]
    # Вечерний снимок — в 18:00, вместе с заходом Пн–Чт (Саша, 15.10: не 19:00), и в дни без прогона.
    assert (plan.tick(dela, at(today, 17, 59)) or {}).get("snap") is None
    assert plan.tick(dela, at(today, 18, 0))["snap"] == today.isoformat()
    with dela_db.session(dela, "system", "t") as c:
        st = c.execute("SELECT value FROM crm.state WHERE key = 'plan'").fetchone()["value"]
    assert st["snaps"][today.isoformat()] == ["11", "18"]


# ── 8. Вид plan ──────────────────────────────────────────────────────────

def test_view_plan(world, dela):
    w = world
    plan.run(dela, at(w["today"], 18, 5), for_date=w["tmrw"])
    assert decide(dela, sug_of(dela, w["o_mid"]["id"]), option=0)["ok"]
    v = store.view(dela, "sasha", "plan")
    assert v["for_date"] == w["tmrw"].isoformat() and v["batch_ref"] == f"plan:{v['for_date']}"
    assert v["offered"] == 7 and v["pending"] == 6 and v["max"] == 5
    assert {m["title"] for m in v["marked"]} == {"Ответить Олегу", "Уже на завтра"}
    assert v["today"] == {"total": 3, "done": 0} and v["stuck"] == [] and v["meetings"] == 0 and v["take"] == 5
    assert v["stats"]["offered"] == 7 and v["stats"]["marked"] == 1 and v["stats"]["baskets"]["owed"]["marked"] == 1
    assert store.view(dela, "natasha", "plan") == {}
    # Контракт: пример вида — те же поля.
    from test_dela_contract import fields
    c = json.loads((ROOT / "contract" / "dela-plan.json").read_text(encoding="utf-8"))
    fields(c["view"]["example"], v, "вид plan")
    s = sug_of(dela, w["c_owed"]["id"])
    fields(c["suggestion"]["example_update"], s, "кандидат update")
    assert set(c["features"]["example"]) <= set(store.FEATURES)


def test_claude_sees_plan_candidates(world, dela):
    """Строка Claude в вебе видит кандидатов П-номерами (planItem кладёт их в S.sugOrder): день, почему, откуда и кнопки —
    иначе «первое из плана» или «П12 уже сделал» понять было не из чего («уточнить #180 Иван: » с пустыми подробностями)."""
    from pravka_dela import ask

    w = world
    plan.run(dela, at(w["today"], 18, 5), for_date=w["tmrw"])
    day = store.day_label(w["tmrw"])
    s = sug_of(dela, w["o_old"]["id"])
    with dela_db.session(dela, "sasha", via="t") as c:
        t = c.execute("SELECT * FROM tasks.v_tasks WHERE id = %s", (w["o_old"]["id"],)).fetchone()
    line = ask._sug_line(s, t)
    assert line == (f"в «Сейчас» на {day}: #{t['num']} Ответить Петру — Пётр ждёт 6 дней ({s['payload']['from']}); "
                    "варианты: ☆ На завтра / ✓ Уже сделал / Не надо"), line
    deal = next(x for x in plan_sugs(dela) if x["payload"]["basket"] == "deal")
    line = ask._sug_line(deal, None)
    assert line.startswith(f"в «Сейчас» на {day}: завести «Решить следующий шаг: Лютик: аудит» — сделка без шага"), line
    assert line.endswith("(Воронка); варианты: ☆ На завтра / Не надо"), line


def test_capacity_from_meetings(dela):
    today = db_today(dela)
    tmrw = today + dt.timedelta(days=1)
    with dela_db.session(dela, "sasha", via="t") as c:
        for k in range(1, 5):
            for j in range(3):
                d = tmrw - dt.timedelta(days=7 * k)
                c.execute("INSERT INTO crm.meetings (ref, started_at, owner_id, sphere, title) VALUES (%s, %s, 'sasha', %s, 'Созвон')",
                          (f"m{k}{j}", at(d, 10 + j), "work"))
        c.execute("INSERT INTO crm.meetings (ref, started_at, owner_id, sphere, title) VALUES ('h1', %s, 'sasha', 'home', 'Дом')",
                  (at(tmrw - dt.timedelta(days=7), 18),))
        cap = plan.capacity(c, "sasha", tmrw, plan.config(c, "sasha"))
    assert cap == {"meetings": 3, "take": 3}
    assert build(dela, today, tmrw)["title"] == f"Сейчас на {store.day_label(tmrw)} · встреч ~3 — возьми 3"


# ── 9. Прочее: виды life, миграция поверх данных, контракт, Свод ────────

def test_life_focus_on_and_now_days_for_reader(dela, cfg):
    today = db_today(dela)
    tmrw = today + dt.timedelta(days=1)
    mk(dela, title="На завтра заранее", focus_on=tmrw.isoformat())
    mk(dela, title="Сейчас сегодня", focus_on=today.isoformat())
    mk(dela, user="natasha", title="Дело команды в Сейчас", focus_on=today.isoformat())
    plan.snap(dela, today)
    with dela_db.session(dela, "system", "t") as c:  # строка снимка не владельца — в life её не видно
        nid = c.execute("SELECT id FROM tasks.tasks WHERE owner_id = 'natasha'").fetchone()["id"]
        c.execute("INSERT INTO tasks.now_days (owner_id, day, task_id) VALUES ('natasha', %s, %s)", (today, nid))
    with psycopg.connect(cfg.reader_url) as r:
        got = dict(r.execute("SELECT title, focus_on FROM life.tasks WHERE focus_on IS NOT NULL").fetchall())
        days = r.execute("SELECT title, carried, done FROM life.now_days").fetchall()
    assert got["На завтра заранее"] == tmrw and got["Сейчас сегодня"] == today
    assert days == [("Сейчас сегодня", 1, False)]


def test_migration_over_data(admin_dsn):
    """Миграция плана (dela_0034; в ветке — dela_0024) — поверх живых Дел с предложениями и делами (источник шире,
    таблица новая, UPDATE нет). Файл ищется по тому, что он заводит (tasks.now_days), а не по номеру: при слиянии с
    другими ветками номер сдвигался (поезд 16.10.2026 — 34), и тест не тянет за собой правку литералов. Применённая
    второй раз, она не падает и снимка не теряет (повтор безвреден)."""
    import secrets as _secrets
    from urllib.parse import urlparse, urlunparse

    from psycopg import sql

    files = dict(dela_db._sql_files())
    mine = [k for k, v in files.items() if k.startswith("dela_") and "CREATE TABLE IF NOT EXISTS tasks.now_days" in v]
    assert len(mine) == 1, mine
    mig = mine[0]
    name = f"pa_migplan_{_secrets.token_hex(4)}"
    with psycopg.connect(admin_dsn, autocommit=True) as a:
        a.execute(sql.SQL("CREATE DATABASE {} ENCODING 'UTF8' TEMPLATE template0").format(sql.Identifier(name)))
    u = urlparse(admin_dsn)
    url = urlunparse((u.scheme, u.netloc, "/" + name, "", "", ""))
    try:
        before = {k: v for k, v in files.items() if k.startswith("dela_") and k < mig}
        with psycopg.connect(url, autocommit=True) as c:
            c.execute("CREATE SCHEMA core")
            c.execute("CREATE TABLE core.migrations (name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())")
            dela_db.apply_pending(c, before)
        with dela_db.session(url, "system", "test") as c:
            c.execute("INSERT INTO crm.users (id, name, role) VALUES ('sasha', 'Саша', 'owner')")
            tid = c.execute("INSERT INTO tasks.tasks (title, owner_id, created_by, focus_on) VALUES ('Дело', 'sasha', 'sasha', "
                            "crm.today()) RETURNING id").fetchone()["id"]
            c.execute("INSERT INTO tasks.suggestions (for_user, kind, task_id, source, created_by) "
                      "VALUES ('sasha', 'update', %s, 'krug', 'sasha')", (tid,))
        with psycopg.connect(url, autocommit=True) as c:
            assert dela_db.apply_pending(c, files)[0] == mig
        with dela_db.session(url, "system", "test") as c:
            c.execute("INSERT INTO tasks.suggestions (for_user, kind, task_id, source, created_by) "
                      "VALUES ('sasha', 'update', %s, 'plan', 'sasha')", (tid,))
            c.execute("INSERT INTO tasks.now_days (owner_id, day, task_id) VALUES ('sasha', crm.today(), %s)", (tid,))
            assert c.execute("SELECT count(*) AS n FROM tasks.suggestions").fetchone()["n"] == 2
        # Второй раз (копия, где файл уже стоял) — без ошибки, снимок и источник plan на месте.
        with psycopg.connect(url, autocommit=True) as c:
            with c.transaction():
                c.execute(files[mig])
        with dela_db.session(url, "system", "test") as c:
            assert c.execute("SELECT count(*) AS n FROM tasks.now_days").fetchone()["n"] == 1
            assert c.execute("SELECT count(*) AS n FROM tasks.suggestions WHERE source = 'plan'").fetchone()["n"] == 1
    finally:
        with psycopg.connect(admin_dsn, autocommit=True) as a:
            a.execute(sql.SQL("DROP DATABASE IF EXISTS {} WITH (FORCE)").format(sql.Identifier(name)))


# ── 9. Поезд 16.10.2026: стыки с другими вагонами ────────────────────────

def test_train_capture_reminders_and_later(dela):
    """Стык с захватом (вагон 4, dela_0032, правило 29). Напоминание — не дело: ни в одной корзине (store.DEED_KIND_IF).
    «Потом» план не подталкивает: дело, отложенное дальше дня плана, — не кандидат, если срок не горит (срок сильнее
    «потом»); «Вернулось» (своё, не «Отбить», без срока, с днём возврата) — не кандидат: у него свой вопрос на экране; а
    вернувшееся «Отбить» — кандидат, и «☆ На завтра» снимает с него «потом». Закрытое напоминание — движение направления,
    как у Круга (krug.movement): «направление стоит» план тогда не пишет."""
    today = db_today(dela)
    tm = today + dt.timedelta(days=1)
    ok(dela, "sasha", {"op": "svod.set", "key": krug.DIRECTIONS_KEY, "value": DIRS, "author": "test"})
    rem = mk(dela, title="Напомни: отправить счёт", kind="remind", ball="owed", remind_at=at(tm, 12).isoformat())
    aged(dela, rem["id"], 5)
    later = mk(dela, title="Ответить Олегу потом", ball="owed", back_on=(today + dt.timedelta(days=5)).isoformat())
    aged(dela, later["id"], 5)
    burning = mk(dela, title="Счёт до завтра", ball="owed", due_date=tm.isoformat(),
                 back_on=(today + dt.timedelta(days=5)).isoformat())
    back_owed = mk(dela, title="Ответить Глебу", ball="owed", back_on=today.isoformat())
    aged(dela, back_owed["id"], 5)
    g = mk(dela, title="Тело в форме", goal=True, directions=["health"])
    returned = mk(dela, title="Купить коврик", why_id=g["id"], back_on=today.isoformat())
    aged(dela, returned["id"], 10)
    step = mk(dela, title="Сделать зарядку", why_id=g["id"])
    aged(dela, step["id"], 1)
    with dela_db.session(dela, "sasha", via="t") as c:
        kinds = dict(c.execute("SELECT title, kind FROM tasks.tasks WHERE id = %s", (rem["id"],)).fetchone() or {})
    assert kinds.get("kind") == "remind", "напоминание осталось напоминанием (время есть, срока нет)"
    out = build(dela, today, tm)
    t = titles(out)
    assert "Напомни: отправить счёт" not in t
    assert "Ответить Олегу потом" not in t and "Купить коврик" not in t
    assert "Счёт до завтра" in t and "Ответить Глебу" in t and "Сделать зарядку" in t, t
    # Закрытое напоминание в «Здоровье» — движение: резерва Круга нет.
    doc = mk(dela, title="Напомни: записаться к врачу", kind="remind", remind_at=at(today, 9).isoformat(),
             directions=["health"])
    ok(dela, "sasha", {"op": "task.done", "id": doc["id"]})
    assert not [x for x in build(dela, today, tm)["items"] if x["basket"] == "krug"]
    # «☆ На завтра» у вернувшегося «Отбить» — в «Сейчас» на завтра, «потом» снят (как «В «Сейчас»» у «Вернулось»).
    plan.run(dela, at(today, 18, 5), for_date=tm)
    r = decide(dela, sug_of(dela, back_owed["id"]), option=0)
    assert r["ok"] and str(r["task"]["focus_on"]) == tm.isoformat() and r["task"]["back_on"] is None, r


def test_train_nest_one_per_family(dela):
    """Стык с делами внутри дела (вагон 2, dela_0030, правило 27). Из семьи — один кандидат: родитель «Отбить» и дело
    внутри него, оба горящие, — одна строка (родитель: отметил его — дела внутри встанут в «Сейчас» над ним), место в
    квоте берёт следующий. Семья — по корню: два горящих «Отбить» внутри одного родителя (сам он не кандидат) — тоже
    одна строка, а не два места из семи (проверка вагона 6). Резерв Круга — только шаг к цели: дело внутри дела, над
    которым цели нет, не резерв, хоть направление оно и наследует; дело внутри дела, ведущего к цели, — резерв, и
    «почему» называет родителя («для «…»»)."""
    today = db_today(dela)
    tm = today + dt.timedelta(days=1)
    ok(dela, "sasha", {"op": "svod.set", "key": krug.DIRECTIONS_KEY, "value": DIRS, "author": "test"})
    # Соседи: родитель — своё дело (не «Отбить», без срока и не в «Сейчас» — сам не кандидат), внутри два горящих
    # «Отбить», старше всех остальных (идут в корзине первыми).
    bank = mk(dela, title="Договор с банком")
    aged(dela, bank["id"], 12)
    sib1 = mk(dela, title="Отбить банку по тарифам", ball="owed", why_id=bank["id"])
    aged(dela, sib1["id"], 9)
    sib2 = mk(dela, title="Отбить юристу по договору", ball="owed", why_id=bank["id"])
    aged(dela, sib2["id"], 8)
    parent = mk(dela, title="Прислать договор Ивану", ball="owed")
    aged(dela, parent["id"], 6)
    inner = mk(dela, title="Набросать драфт договора", ball="owed", why_id=parent["id"])
    aged(dela, inner["id"], 5)
    other = mk(dela, title="Ответить Олегу", ball="owed")
    aged(dela, other["id"], 4)
    # Цели над ним нет — не резерв Круга (направление «Здоровье» своё у родителя, у дела внутри — по наследству).
    free = mk(dela, title="Разобрать полку", directions=["health"])
    aged(dela, free["id"], 20)
    lone = mk(dela, title="Купить ящики для полки", why_id=free["id"])
    aged(dela, lone["id"], 20)
    # Цель → дело → дело внутри: внутреннее — шаг к цели (родитель с открытым делом внутри — нет).
    g = mk(dela, title="Тело в форме", goal=True, directions=["health"])
    prep = mk(dela, title="Подготовиться к забегу", why_id=g["id"])
    shoes = mk(dela, title="Купить кроссовки", why_id=prep["id"])
    aged(dela, shoes["id"], 2)
    with dela_db.session(dela, "sasha", via="t") as c:
        dirs = c.execute("SELECT dirs FROM tasks.v_tasks WHERE id = %s", (lone["id"],)).fetchone()["dirs"]
    assert dirs and dirs[0] == "health", "направление дело внутри наследует — отсекает именно «шаг к цели»"
    out = build(dela, today, tm)
    t = titles(out)
    assert "Прислать договор Ивану" in t and "Набросать драфт договора" not in t, t
    assert "Отбить банку по тарифам" in t and "Отбить юристу по договору" not in t, "соседи внутри одного родителя — одна семья"
    assert "Договор с банком" not in t, t
    owed_items = [x for x in out["items"] if x["basket"] == "owed"]
    assert len(owed_items) == 3, owed_items
    assert "Ответить Олегу" in t, "место в квоте «Отбить» не пропало — взят следующий"
    sib = next(x for x in owed_items if x["title"] == "Отбить банку по тарифам")
    assert sib["why"].endswith(" · для «Договор с банком»"), sib["why"]
    assert "Купить ящики для полки" not in t and "Разобрать полку" not in t and "Подготовиться к забегу" not in t
    krug_items = [x for x in out["items"] if x["basket"] == "krug"]
    assert [x["title"] for x in krug_items] == ["Купить кроссовки"], out["items"]
    assert krug_items[0]["why"].endswith(" · для «Подготовиться к забегу»"), krug_items[0]["why"]
    # Отметил родителя — открытое дело внутри в «Сейчас» завтра пойдёт с ним, отдельным кандидатом его нет и после.
    plan.run(dela, at(today, 18, 5), for_date=tm)
    assert decide(dela, sug_of(dela, parent["id"]), option=0)["ok"]
    assert not [s for s in plan_sugs(dela) if str(s["task_id"]) == str(inner["id"])]


def test_contract_and_svod_keys():
    c = json.loads((ROOT / "contract" / "dela-plan.json").read_text(encoding="utf-8"))
    assert c["features"]["flag"] == "plan" and "plan" in store.FEATURES
    assert c["svod"]["plan.tomorrow"]["factory"] == plan.FACTORY
    assert c["svod"]["asks.rhythm"]["factory"] == asks.FACTORY
    keys = json.loads((ROOT / "contract" / "svod.json").read_text(encoding="utf-8"))["svod"]["keys"]
    assert "plan.tomorrow" in keys and "asks.rhythm" in keys
    assert "done" in store.OPTION_FIELDS and "status" not in store.OPTION_FIELDS


def test_web_plan_block_text():
    """Веб: кандидаты плана — своим блоком под «Подскажи», не в числе «Нового» и не в «Поставить все»."""
    js = (Path(store.__file__).parent / "static" / "app.js").read_text(encoding="utf-8")

    def fn(head: str) -> str:
        body = js[js.index(head):]
        return body[:body.index("\n}\n")]

    assert "const isPlan = " in js and "const planSugs = " in js
    pend = js[js.index("const pendingSugs = "):]
    assert "!isPlan(s)" in pend[:pend.index("\n")], "«Подскажи» и число «Нового» — без кандидатов плана"
    box = fn("function planBox(")
    assert "Поставить все" not in box and "crmGet('/api/view/plan'" in box
    now = fn("function renderNow()")
    assert now.index("newBox()") < now.index("planBox()"), "сначала ответить на вопросы, потом план"
    item = fn("function planItem(")
    assert "S.sugOrder.push(s.id)" in item and "answer(s, i, planLabel(opts[i]))" in item
    # После полуночи день плана — уже сегодня: шапка «на сегодня, до 12:00», и кнопка «☆ На сегодня», а не «На завтра».
    assert "const PLAN_TODAY = '☆ На сегодня'" in js and "o.focus_on <= S.today ? PLAN_TODAY : o.label" in js
    assert "planLabel(o)))" in item, "подпись кнопки — planLabel, не подпись сервера"
    # «Не надо» — тостом без повтора («Не надо: Не надо» было).
    ans = fn("async function answer(")
    assert "/^Не надо/.test(said) ? said : 'Не надо: ' + said" in ans


def test_cli_plan_shows_unless_run(dela, tmp_path, capsys, monkeypatch):
    """Команда plan без действия — только показать (выкат велит звать её на живой базе: забытый --dry не должен класть
    Саше кандидатов); писать — явным plan run, и второй run на тот же день ничего не пишет."""
    from pravka_dela import __main__ as cli

    for i in range(3):
        t = mk(dela, title=f"Ответить Тестову {i}", ball="owed")
        aged(dela, t["id"], 4 + i)
    env = tmp_path / "dela.env"
    env.write_text(f"DELA_DB_URL={dela}\n", encoding="utf-8")
    monkeypatch.delenv("DELA_DB_URL", raising=False)
    assert cli.main(["--env", str(env), "plan"]) == 0
    said = capsys.readouterr().out
    assert said.startswith("вхолостую (ничего не записано): на ") and "кандидатов 3 из" in said, said
    assert "; записать — plan run" in said and plan_sugs(dela) == []
    assert cli.main(["--env", str(env), "plan", "run"]) == 0
    said = capsys.readouterr().out
    assert not said.startswith("вхолостую") and "записано 3, ошибок 0" in said, said
    assert len(plan_sugs(dela)) == 3
    assert cli.main(["--env", str(env), "plan", "run"]) == 0
    assert "уже предложено 3 — второй прогон ничего не пишет" in capsys.readouterr().out
    assert cli.main(["--env", str(env), "plan"]) == 0
    assert "на этот день уже предложено 3 — run ничего не запишет" in capsys.readouterr().out
    assert len(plan_sugs(dela)) == 3
