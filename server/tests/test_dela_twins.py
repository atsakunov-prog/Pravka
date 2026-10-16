"""Страницы-двойники экранов телефона в вебе Дел (dela_0016, twins.py, zasechka.py, life.py, sky.py).

Архив читается узко — функцией crm.life_rows, не схемой life; страницы и команды Засечки — только владельцу; команда
телефону — take одному устройству, done с ответом. Все данные выдуманные: репозиторий публичный.
"""

from __future__ import annotations

import datetime as dt
import json
from pathlib import Path

import psycopg
import pytest
from psycopg import sql
from starlette.testclient import TestClient

from pravka_archive.db import mark_source
from pravka_archive.ingest import store_events
from pravka_dela import api, kovcheg_ui, life, sky, store, tokens, twins, zasechka
from pravka_dela import db as dela_db
from pravka_dela.config import Config
from test_dela_schema import db_today, dela  # noqa: F401  (фикстура)

CONTRACT = json.loads((Path(__file__).resolve().parents[1] / "contract" / "dela-zasechka.json").read_text(encoding="utf-8"))
SEQ = [1000]


def ev(kind: str, key: str, data: dict, device: str = "phone:test") -> dict:
    SEQ[0] += 1
    return {"eid": f"{device}:{SEQ[0]}", "seq": SEQ[0], "kind": kind, "key": key, "op": "put",
            "at": "2026-10-14T12:00:00+03:00", "data": data}


def iso(d: dt.date, hm: str) -> str:
    return f"{d.isoformat()}T{hm}:00.000+03:00"


@pytest.fixture()
def day(dela, clean):
    """Выдуманный день владельца в архиве: лента, еда, тренировка, сон, траты семьи, телефон, диктовки."""
    d = db_today(dela)
    y = d - dt.timedelta(days=1)
    cats = [{"name": "Работа: текущая", "hint": "", "base_min": 360, "value": 9, "ord": 1},
            {"name": "Сон", "hint": "", "base_min": 450, "value": 0, "ord": 2},
            {"name": "Еда", "hint": "", "base_min": 60, "value": 1, "ord": 3},
            {"name": "Передвижение: транспорт", "hint": "", "base_min": 30, "value": -1, "ord": 4},
            {"name": "Не размечено", "hint": "", "base_min": 0, "value": -2, "ord": 5}]
    entries = [
        {"id": 101, "start": iso(d, "00:00"), "end": iso(d, "07:00"), "minutes": 420, "title": "Сон", "category": "Сон",
         "client": "", "source": "auto", "raw": "garmin", "comment": ""},
        {"id": 102, "start": iso(d, "07:00"), "end": iso(d, "07:30"), "minutes": 30, "title": "Завтрак", "category": "Еда",
         "client": "", "source": "voice", "raw": "завтракаю", "comment": ""},
        {"id": 103, "start": iso(d, "07:30"), "end": iso(d, "08:00"), "minutes": 30, "title": "не размечено",
         "category": "Не размечено", "client": "", "source": "gap", "raw": "", "comment": ""},
        {"id": 104, "start": iso(d, "08:00"), "title": "Тизер для Альфы", "category": "Работа: текущая",
         "client": "Альфа Групп", "source": "task", "raw": "Тизер для Альфы", "comment": "", "task": "0b9a8c7d-6e5f-4a3b-8c2d-1e0f9a8b7c6d"},
    ]
    yest = [{"id": 99, "start": iso(y, "23:30"), "end": f"{d.isoformat()}T00:00:00.000+03:00", "minutes": 30, "title": "Сон",
             "category": "Сон", "client": "", "source": "auto", "raw": "garmin", "comment": ""}]
    store_events(clean, "phone:test", [
        ev("zasechka.reference", "all", {"categories": cats, "clients": ["Альфа Групп"]}),
        ev("zasechka.day", d.isoformat(), {"day": d.isoformat(), "entries": entries}),
        ev("zasechka.day", y.isoformat(), {"day": y.isoformat(), "entries": yest}),
        ev("food.meal", "f1", {"day": d.isoformat(), "at": iso(d, "07:10"), "kind": "завтрак", "source": "voice",
                               "confirmed": True, "items": [{"name": "Каша", "grams": 250, "kcal": 400, "protein_g": 20}],
                               "totals": {"kcal": 400, "protein_g": 20, "fat_g": 8, "carbs_g": 60, "fiber_g": 5, "grams": 250}}),
        ev("food.meal", "f2", {"day": d.isoformat(), "at": iso(d, "07:20"), "kind": "перекус", "source": "voice",
                               "confirmed": False, "items": [{"name": "Яблоко", "grams": 150, "kcal": 80, "protein_g": 0}],
                               "totals": {"kcal": 80, "protein_g": 0, "fat_g": 0, "carbs_g": 20, "fiber_g": 3, "grams": 150}}),
        ev("money.reference", "all", {"categories": [{"key": "groceries", "title": "Продукты", "group": "Дом", "shelf": "family", "income": False},
                                                     {"key": "own", "title": "Между своими", "group": "", "shelf": "service", "income": False}]}),
        ev("money.entry", "m1", {"owner": "sasha", "day": d.isoformat(), "at": iso(d, "07:40"), "amount_kop": -150000,
                                 "what": "Магазин у дома", "category": "groceries", "source": "push", "live": True}),
        ev("money.entry", "m2", {"owner": "sasha", "day": d.isoformat(), "at": iso(d, "07:45"), "amount_kop": -500000,
                                 "what": "Себе на карту", "category": "own", "source": "push", "live": True}),
        ev("phone.day", d.isoformat(), {"day": d.isoformat(), "screen_min": 120, "pickups": 40}),
        ev("pravka.take", "1000", {"day": d.isoformat(), "at": iso(d, "07:05"), "engine": "google-net", "audio_ms": 9000,
                                   "text": "купить хлеба и молока по дороге", "ok": True, "mic": "телефон → гарнитура «OpenComm2 by Shokz_II»"}),
        ev("pravka.clean", "1001", {"day": d.isoformat(), "at": f"{d.isoformat()}T07:05:02.000+03:00", "mode": "clean",
                                    "model": "claude-sonnet-5-5", "input": "купить хлеба и молока по дороге",
                                    "output": "Купить хлеба и молока по дороге.", "latency_ms": 1400, "cost_usd": 0.011, "error": ""}),
        ev("pravka.take", "pc-1760418000000", {"day": d.isoformat(), "at": iso(d, "07:06"), "engine": "gigaam cpu по ходу",
                                               "audio_ms": 4000, "text": "привет как дела", "ok": True, "origin": "pc",
                                               "status": "pasted", "final": "Привет, как дела?"}),
        ev("pravka.clean", "pc-1760418000000", {"day": d.isoformat(), "at": iso(d, "07:06"), "mode": "clean", "style": "chat",
                                                "model": "claude-sonnet-5-5", "input": "привет как дела", "output": "Привет, как дела?",
                                                "latency_ms": 900, "cost_usd": 0.0004, "origin": "pc", "take": "pc-1760418000000"}),
    ])
    store_events(clean, "intervals", [
        ev("icu.activity", "i1", {"id": "i1", "start_date_local": f"{d.isoformat()}T06:00:00", "start_date": f"{d.isoformat()}T03:00:00Z",
                                  "type": "Run", "name": "Утренний бег", "elapsed_time": 1800, "moving_time": 1750,
                                  "distance": 5000, "average_heartrate": 140, "icu_training_load": 40}, "intervals"),
        ev("icu.wellness", d.isoformat(), {"id": d.isoformat(), "sleepSecs": 25200, "sleepScore": 80, "hrv": 55, "ctl": 40, "atl": 42}, "intervals"),
    ])
    mark_source(clean, "phone:test", True)
    return d


def view(url, user, name, **p):
    return store.view(url, user, name, **p)


def test_role_reads_archive_only_through_function(day, dela):
    """Роль службы схему life не видит; функция отдаёт владельцу и отказывает остальным — прямым запросом тоже."""
    with dela_db.session(dela, "sasha") as c:
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            c.execute("SELECT * FROM life.entries LIMIT 1")
    with dela_db.session(dela, "sasha") as c:
        got = [r["r"] for r in c.execute("SELECT crm.life_rows('entries', %s, %s) AS r", (day, day)).fetchall()]
    assert [e["id"] for e in got] == [101, 102, 103, 104]
    assert got[-1]["open"] is True and got[-1]["task_id"] == "0b9a8c7d-6e5f-4a3b-8c2d-1e0f9a8b7c6d"
    for who in ("natasha", "marianna", "system"):
        with dela_db.session(dela, who) as c:
            with pytest.raises(psycopg.errors.InsufficientPrivilege):
                c.execute("SELECT crm.life_rows('entries', %s, %s)", (day, day)).fetchall()
    with dela_db.session(dela, "sasha") as c:
        with pytest.raises(psycopg.errors.RaiseException, match="такого чтения архива нет"):
            c.execute("SELECT crm.life_rows('raw', %s, %s)", (day, day)).fetchall()
    with dela_db.session(dela, "sasha") as c:
        with pytest.raises(psycopg.errors.RaiseException, match="62 суток"):
            c.execute("SELECT crm.life_rows('entries', %s, %s)", (day - dt.timedelta(days=100), day)).fetchall()


def test_pages_only_for_owner(day, dela):
    for name in twins.PAGES + ("pogoda",):
        for who in ("natasha", "marianna"):
            with pytest.raises(store.OpError, match="только владельцу"):
                view(dela, who, name)


def test_day_view_reads_archive_and_counts_tiles(day, dela):
    with dela_db.session(dela, "system", "test") as c:
        c.execute("INSERT INTO crm.svod (owner_id, key, value, author) VALUES ('sasha', 'food.goals', %s, 'test') "
                  "ON CONFLICT (owner_id, key) DO UPDATE SET value = EXCLUDED.value",
                  (json.dumps({"kcal": 2500, "protein_g": 140}),))
        c.execute("INSERT INTO crm.meetings (ref, started_at, title, owner_id) VALUES ('t:1', now(), 'Альфа: тизер', 'sasha')")
    out = view(dela, "sasha", "day")
    assert out["archive"] == {"ok": True, "error": ""}
    assert out["day"] == day.isoformat()
    # Вчерашний хвост сна — тоже здесь: голова ночи в «от–до» ленты.
    assert {e["id"] for e in out["entries"]} == {99, 101, 102, 103, 104}
    assert [c["name"] for c in out["categories"]][:2] == ["Работа: текущая", "Сон"]
    t = out["tiles"]
    assert (t["kcal"], t["kcal_goal"], t["protein"]) == (400, 2500, 20)   # в счёт — только подтверждённое
    assert (t["sport_min"], t["sport_name"]) == (30, "Утренний бег")
    assert t["money_day_kop"] == 150000 and t["money_month_kop"] >= 150000   # «между своими» — не трата
    assert t["sleep_h"] == 7 and t["shabbat"]["candles"]
    assert len(out["meals"]) == 2 and {m["confirmed"] for m in out["meals"]} == {True, False}
    assert [m["what"] for m in out["money"]] == ["Магазин у дома", "Себе на карту"]
    assert out["meetings"][0]["title"] == "Альфа: тизер"
    assert out["fresh"]["phone"] and out["commands"] == []


def test_zasechka_view_counts_time_by_task(day, dela):
    out = view(dela, "sasha", "zasechka")
    tid = "0b9a8c7d-6e5f-4a3b-8c2d-1e0f9a8b7c6d"
    assert out["tasks"][tid]["category"] == "Работа: текущая"
    assert out["archive"]["ok"] and len(out["entries"]) == 5


def test_no_archive_access_is_words_not_a_crash(day, dela, cfg):
    """Роли не выдано право (старый установщик) — страница говорит «нет доступа к архиву», а дела и команды живут."""
    role = sql.Identifier(Config(db_url=dela).app_role)
    with psycopg.connect(cfg.db_url, autocommit=True) as c:
        for fn in dela_db.APP_FUNCTIONS:
            c.execute(sql.SQL("REVOKE EXECUTE ON FUNCTION " + fn + " FROM {}").format(role))
    try:
        out = view(dela, "sasha", "day")
        assert not out["archive"]["ok"] and "нет доступа к архиву" in out["archive"]["error"]
        assert out["entries"] == [] and out["commands"] == []
        p = view(dela, "sasha", "pravka")
        assert not p["archive"]["ok"] and p["items"] == []
    finally:
        with psycopg.connect(cfg.db_url, autocommit=True) as c:
            dela_db.grant_app(c, Config(db_url=dela).app_role)


def test_pravka_pairs_phone_take_with_its_clean_and_pc_by_key(day, dela):
    out = view(dela, "sasha", "pravka")
    assert out["archive"]["ok"] and out["days"] == twins.PRAVKA_DAYS
    items = {i["key"]: i for i in out["items"]}
    ph = items["1000"]
    assert (ph["origin"], ph["device"]) == ("headset", "Shokz")
    assert ph["said"] == "купить хлеба и молока по дороге" and ph["wrote"] == "Купить хлеба и молока по дороге."
    assert (ph["engine_label"], ph["clean_model_label"], ph["clean_usd_label"], ph["length"]) == \
        ("Google, по сети", "Сонет 5.5", "$0,011", "9 с")
    pc = items["pc-1760418000000"]
    assert (pc["origin"], pc["device"], pc["status_label"]) == ("pc", "компьютер", "вставлено в окно")
    assert pc["wrote"] == "Привет, как дела?" and pc["engine_label"] == "GigaAM-v3, по ходу речи"
    assert pc["clean_mode"] == "chat" and pc["clean_usd_label"] == "<$0,001"
    assert [i["key"] for i in out["items"]] == ["pc-1760418000000", "1000"]   # свежие сверху


def test_pair_rules_like_the_phone():
    """Склейка — как Dictations.phone: окно −2…+180 с, половина слов, одна чистка — одному тейку; чужие режимы мимо."""
    t0 = "2026-10-14T10:00:00+03:00"
    takes = [{"id": "1", "at": t0, "text": "раз два три четыре", "ok": True},
             {"id": "2", "at": "2026-10-14T10:00:30+03:00", "text": "раз два три четыре", "ok": True},
             {"id": "3", "at": "2026-10-14T10:10:00+03:00", "text": "совсем другое", "ok": True}]
    cleans = [{"id": "a", "at": "2026-10-14T10:00:05+03:00", "mode": "CLEAN", "input": "раз два три четыре", "output": "Раз."},
              {"id": "b", "at": "2026-10-14T10:00:40+03:00", "mode": "assistant", "input": "раз два три четыре", "output": "Нет."},
              {"id": "c", "at": "2026-10-14T10:20:00+03:00", "mode": "clean", "input": "совсем другое", "output": "Поздно."}]
    got = {i["key"]: i for i in twins.pair(takes, cleans)}
    assert got["1"]["wrote"] == "Раз." and got["2"]["wrote"] is None and got["3"]["wrote"] is None
    assert twins.duration(235_000) == "3 мин 55 с" and twins.usd(0.02) == "$0,02" and twins.usd(0) == ""
    assert twins.engine_label("small cuda") == "Whisper small, видеокарта" and twins.model_label("claude-opus-5-5") == "Опус 5.5"
    assert twins.device_of(None) == ("phone", "телефон")


def ops(url, user, *items, via="web"):
    return store.apply_ops(url, user, list(items), via)["results"]


def test_contract_flag_and_example(dela):
    assert CONTRACT["features"]["flag"] in store.FEATURES
    r = ops(dela, "sasha", CONTRACT["op_cmd"]["example"])[0]
    assert r["ok"] and r["cmd"]["status"] == "wait" and r["cmd"]["kind"] == "start"
    assert set(CONTRACT["sync"]["item"]) == set(r["cmd"])


def test_command_goes_to_phone_once_and_comes_back_done(dela):
    r = ops(dela, "sasha", {"op": "zasechka.cmd", "cmd": {"kind": "start", "args": {"title": "  Тизер  для Альфы ", "category": "Работа: текущая"}}})[0]
    assert r["ok"] and r["cmd"]["args"]["title"] == "Тизер для Альфы"
    cid = r["cmd"]["id"]
    s = store.sync(dela, "sasha", 0)
    assert [c["id"] for c in s["zasechka"]] == [cid]
    assert "zasechka" not in store.sync(dela, "natasha", 0) and "zasechka" not in store.sync(dela, "marianna", 0)
    a = ops(dela, "sasha", {"op": "zasechka.take", "id": cid, "device": "fold"}, via="app")[0]
    assert a["ok"] and a["cmd"]["status"] == "taken" and a["cmd"]["taken_by"] == "fold"
    assert ops(dela, "sasha", {"op": "zasechka.take", "id": cid, "device": "fold"}, via="app")[0]["ok"]   # повтор — своё
    b = ops(dela, "sasha", {"op": "zasechka.take", "id": cid, "device": "old-phone"}, via="app")[0]
    assert not b["ok"] and "другой телефон" in b["error"]
    no = ops(dela, "sasha", {"op": "zasechka.cancel", "id": cid})[0]
    assert not no["ok"] and "снять нельзя" in no["error"]
    d = ops(dela, "sasha", {"op": "zasechka.done", "id": cid, "device": "fold", "ok": True, "note": "начато с 10:00",
                            "entry_id": 1760425200000}, via="app")[0]
    assert d["ok"] and d["cmd"]["status"] == "done" and d["cmd"]["entry_id"] == 1760425200000
    assert ops(dela, "sasha", {"op": "zasechka.done", "id": cid, "device": "fold", "ok": True}, via="app")[0]["ok"]
    assert store.sync(dela, "sasha", 0)["zasechka"] == []
    with dela_db.session(dela, "sasha") as c:
        assert zasechka.recent(c, "sasha")[0]["note"] == "начато с 10:00"
    # Журнала у команд нет сознательно: названия дел ленты — личные.
    with dela_db.session(dela, "system", "test") as c:
        assert c.execute("SELECT count(*) AS n FROM crm.history WHERE entity = 'crm.zasechka_cmds'").fetchone()["n"] == 0


def test_refused_cancelled_expired(dela):
    cid = ops(dela, "sasha", {"op": "zasechka.cmd", "cmd": {"kind": "stop", "args": {"entry_id": 104}}})[0]["cmd"]["id"]
    r = ops(dela, "sasha", {"op": "zasechka.done", "id": cid, "device": "fold", "ok": False, "note": "уже идёт другое дело"}, via="app")[0]
    assert r["ok"] and r["cmd"]["status"] == "refused" and r["cmd"]["taken_by"] == "fold"
    cid = ops(dela, "sasha", {"op": "zasechka.cmd", "cmd": {"kind": "stop", "args": {"entry_id": 104}}})[0]["cmd"]["id"]
    assert ops(dela, "sasha", {"op": "zasechka.cancel", "id": cid})[0]["cmd"]["status"] == "cancelled"
    t = ops(dela, "sasha", {"op": "zasechka.take", "id": cid, "device": "fold"}, via="app")[0]
    assert not t["ok"] and "сняли в вебе" in t["error"]
    old = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=13)).isoformat()
    with dela_db.session(dela, "system", "test") as c:
        cid = c.execute("INSERT INTO crm.zasechka_cmds (owner_id, kind, args, said_at) VALUES ('sasha', 'stop', %s, %s) "
                        "RETURNING id", (json.dumps({"entry_id": 1}), old)).fetchone()["id"]
    assert str(cid) not in {c["id"] for c in store.sync(dela, "sasha", 0)["zasechka"]}
    t = ops(dela, "sasha", {"op": "zasechka.take", "id": str(cid), "device": "fold"}, via="app")[0]
    assert not t["ok"] and "устарела" in t["error"]


def test_commands_check_their_words(dela):
    bad = [
        ({"kind": "start", "args": {"title": " "}}, "пусто"),
        ({"kind": "fly", "args": {}}, "одна из"),
        ({"kind": "stop", "args": {}}, "entry_id"),
        ({"kind": "start", "args": {"title": "x", "task_id": "не-uuid"}}, "не uuid"),
        ({"kind": "edit", "args": {"entry_id": 5, "day": "2026-10-14", "was": {"title": "a", "start": "10:00", "end": "11:00"},
                                   "set": {"title": "a"}}}, "нечего"),
        ({"kind": "edit", "args": {"entry_id": 5, "day": "2026-10-14", "was": {"title": "a", "start": "10:00", "end": ""},
                                   "set": {"end": "11:00"}}}, "Стоп"),
        ({"kind": "edit", "args": {"entry_id": 5, "day": "2026-10-14", "was": {"title": "a", "start": "10:00", "end": "11:00"},
                                   "set": {"start": "25:00"}}}, "ЧЧ:ММ"),
        ({"kind": "start", "args": {"title": "x"}, "at": "2020-01-01T00:00:00+03:00"}, "12 часов"),
    ]
    for cmd, why in bad:
        r = ops(dela, "sasha", {"op": "zasechka.cmd", "cmd": cmd})[0]
        assert not r["ok"] and why in r["error"], (cmd, r)
    ok = ops(dela, "sasha", {"op": "zasechka.cmd", "cmd": {"kind": "edit", "args": {
        "entry_id": 5, "day": "2026-10-14", "was": {"title": "a", "category": "Еда", "start": "10:00", "end": "11:00"},
        "set": {"title": "Обед с Иваном", "end": "11:30"}}}})[0]
    assert ok["ok"] and ok["cmd"]["args"]["set"] == {"title": "Обед с Иваном", "end": "11:30"}
    r = ops(dela, "natasha", {"op": "zasechka.cmd", "cmd": {"kind": "start", "args": {"title": "x"}}})[0]
    assert not r["ok"] and "только владельцу" in r["error"]


def test_web_sees_pages_flag_and_tails_through_api(dela):
    """Владелец видит флаг страниц в /api/me, команда — нет; хвосты отдаются в app.js и style.css, своего адреса нет."""
    app = api.build(Config(db_url=dela, public_url="https://dela.example"))
    cl = TestClient(app)
    owner = tokens.issue(dela, "sasha", "device", "fold")
    member = tokens.issue(dela, "natasha", "device", "n-phone")
    me = cl.get("/api/me", headers={"Authorization": "Bearer " + owner}).json()
    assert me["look"]["pages"] == list(twins.PAGES) == list(kovcheg_ui.PAGES) and "zasechka" in me["features"]
    assert "pages" not in cl.get("/api/me", headers={"Authorization": "Bearer " + member}).json()["look"]
    js = cl.get("/static/app.js").text
    assert "twinsRoute" in js and cl.get("/static/twins.js").status_code == 404
    assert ".tw-page" in cl.get("/static/style.css").text
    r = cl.get("/api/view/day", headers={"Authorization": "Bearer " + member})
    assert r.status_code == 400 and "только владельцу" in r.json()["error"]
    assert cl.get("/api/view/day", headers={"Authorization": "Bearer " + owner}).status_code == 200
    # Службы с токеном владельца (встречи, бот) — ни страниц, ни команд Засечки.
    svc = {"Authorization": "Bearer " + tokens.issue(dela, "sasha", "service", "meetings")}
    assert cl.get("/api/view/zasechka", headers=svc).status_code == 403
    r = cl.post("/api/ops", headers=svc, json={"ops": [{"op": "zasechka.cmd", "cmd": {"kind": "start", "args": {"title": "x"}}}]})
    assert r.status_code == 403


def test_shabbat_like_the_phone_tile():
    """Те же числа, что ShabbatTimes телефона (TodayV2Test): Москва, вторник 13.10.2026 — свечи 17:09, исход 18:20."""
    tue = dt.datetime(2026, 10, 13, 18, 50, tzinfo=sky.MSK)
    t = sky.shabbat(tue, 55.7558, 37.6173)
    assert t["friday"] == "2026-10-16" and not t["now"]

    def sec(hm):
        h, m = map(int, hm.split(":"))
        return h * 3600 + m * 60
    assert abs(sec(t["candles"]) - sec("17:09")) <= 5 * 60 and abs(sec(t["exit"]) - sec("18:20")) <= 8 * 60
    assert sky.shabbat(dt.datetime(2026, 10, 17, 12, 0, tzinfo=sky.MSK), 55.7558, 37.6173)["now"]
    assert sky.shabbat(dt.datetime(2026, 10, 17, 22, 0, tzinfo=sky.MSK), 55.7558, 37.6173)["friday"] == "2026-10-23"


def fake_forecast(start: dt.datetime) -> dict:
    """Open-Meteo в форме ответа: десять суток по часам с 00:00, Москва (+3)."""
    base = start.replace(hour=0, minute=0, second=0, microsecond=0)
    times = [(base + dt.timedelta(hours=i)).strftime("%Y-%m-%dT%H:%M") for i in range(240)]
    temps = [5 + (i % 24) / 4 for i in range(240)]
    return {"utc_offset_seconds": 10800,
            "hourly": {"time": times, "temperature_2m": temps, "apparent_temperature": [t - 3 for t in temps],
                       "precipitation": [0.5 if i % 24 == 15 else 0 for i in range(240)],
                       "precipitation_probability": [60 if i % 24 == 15 else 5 for i in range(240)],
                       "weather_code": [61 if i % 24 == 15 else 3 for i in range(240)]},
            "daily": {"time": [(base + dt.timedelta(days=i)).date().isoformat() for i in range(10)],
                      "weather_code": [3] * 10, "temperature_2m_max": [11] * 10, "temperature_2m_min": [4] * 10,
                      "precipitation_sum": [0.5, 0, 6.2] + [0] * 7, "precipitation_probability_max": [60, 10, 75] + [0] * 7}}


def test_weather_rules_and_cache(monkeypatch):
    now = dt.datetime(2026, 10, 14, 21, 15, tzinfo=sky.MSK)
    out = sky.build(fake_forecast(now), now.timestamp())
    assert [p["label"] for p in out["parts"]] == ["вечер", "утро", "день"]   # в 21:15 — вечер, утро, день
    assert out["hours"][0]["t"] == "21" and len(out["hours"]) == 24
    assert out["now"]["temp"] == "+10°" and out["now"]["feels"] == "+7°"
    assert [w["rain"] for w in out["week"][:3]] == ["0,5 мм · 60%", "сухо", "6,2 мм · 75%"]
    assert out["week"][0]["d"] == "Ср 14"
    calls = []

    def fetch(lat, lon):
        calls.append((lat, lon))
        if len(calls) > 1:
            raise OSError("нет сети")
        return fake_forecast(now)
    monkeypatch.setattr(sky, "fetch", fetch)
    monkeypatch.setattr(sky, "_cache", {})
    where = {"name": "Тестоград", "lat": 10.0, "lon": 20.0}
    a = sky.forecast(where, now.timestamp())
    b = sky.forecast(where, now.timestamp() + 60)            # в пределах часа — из кэша
    c = sky.forecast(where, now.timestamp() + 2 * 3600)      # час прошёл, сети нет — прежний прогноз, «старый»
    assert a["ok"] and b["ok"] and len(calls) == 2 and c["ok"] and c["stale"] and "не обновился" in c["error"]
    monkeypatch.setattr(sky, "_cache", {})
    assert not sky.forecast(where, now.timestamp())["ok"]       # ни одного прогноза — погоды нет, без выдумок


def test_life_rows_split_long_spans(day, dela):
    """Длиннее 62 суток — частями: функция базы больше за раз не отдаёт."""
    with dela_db.session(dela, "sasha") as c:
        got = life.rows(c, "entries", day - dt.timedelta(days=150), day)
    assert {e["id"] for e in got} == {99, 101, 102, 103, 104}


def test_calls_view_reads_archive_for_digest(day, dela, clean):
    """Звонки для разноски дайджеста (dela_0035): из архива по суткам и собеседникам, через crm.life_rows, только
    владельцу; архив не читается — отказ словами, а не пустой список; флаг calls."""
    store_events(clean, "phone:test", [ev("phone.day", day.isoformat(), {
        "day": day.isoformat(), "screen_min": 120, "pickups": 40,
        "callers": [{"who": "Ольга  Пробная", "min": 3}, {"who": "Иван Тестов", "min": 12.5}]})])
    out = view(dela, "sasha", "calls", since=(day - dt.timedelta(days=7)).isoformat(), until=day.isoformat())
    assert out["calls"] == [{"day": day.isoformat(), "who": "Иван Тестов", "minutes": 12.5},
                            {"day": day.isoformat(), "who": "Ольга Пробная", "minutes": 3.0}]
    assert [c["who"] for c in view(dela, "sasha", "calls")["calls"]] == ["Иван Тестов", "Ольга Пробная"]  # неделя до сегодня
    with dela_db.session(dela, "sasha") as c:
        got = [r["r"] for r in c.execute("SELECT crm.life_rows('calls', %s, %s) AS r", (day, day)).fetchall()]
    assert {g["who"] for g in got} == {"Иван Тестов", "Ольга  Пробная"}
    with pytest.raises(store.OpError, match="только владельцу"):
        view(dela, "natasha", "calls")
    with pytest.raises(store.OpError, match="не больше 31"):
        view(dela, "sasha", "calls", since=(day - dt.timedelta(days=40)).isoformat(), until=day.isoformat())
    with pytest.raises(store.OpError, match="дата"):
        view(dela, "sasha", "calls", since="вчера")
    assert "calls" in store.FEATURES
