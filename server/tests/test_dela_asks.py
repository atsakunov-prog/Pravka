"""«Подскажи» двумя заходами (шаг 2 петли этапа 4, 15.10.2026, asks.py, контракт dela-asks.json): фазы по часам Москвы,
срочность и предмет вопроса при записи, очередь группами, вид asks (и ?at= через HTTP), флаг, Свод поверх заводского,
веб — та же правда.

Люди и компании — выдуманные («Иван Тестов», «ООО Ромашка»). «Сегодня» — db_today (crm.today(), Москва); фазы — явным
мигом по Москве: тесты не зависят от того, в какой час и день недели их гоняют (и при TZ=UTC0)."""

from __future__ import annotations

import datetime as dt
import json
import re
from pathlib import Path

import pytest
from starlette.testclient import TestClient

from pravka_dela import api, asks, store, tokens
from pravka_dela import db as dela_db
from pravka_dela.config import Config
from test_dela_schema import db_today, dela, project  # noqa: F401  (фикстура и помощники)

MSK = asks.MSK
ROOT = Path(__file__).resolve().parents[1]
CONTRACT = json.loads((ROOT / "contract" / "dela-asks.json").read_text(encoding="utf-8"))
STATIC = Path(store.__file__).parent / "static"


def ops(url, user, *items, via="app"):
    return store.apply_ops(url, user, list(items), via)["results"]


def ok(url, user, *items):
    res = ops(url, user, *items)
    assert all(r["ok"] for r in res), res
    return res


def sug(url, user="sasha", **s):
    """Вопрос в «Новое» — как его шлют службы (их токен — владельца)."""
    s.setdefault("kind", "create")
    s.setdefault("source", "telegram")
    return ok(url, user, {"op": "suggestion.create", "suggestion": s})[0]["suggestion"]


def at(day: dt.date, h: int, m: int = 0) -> dt.datetime:
    return dt.datetime(day.year, day.month, day.day, h, m, tzinfo=MSK)


def person(url, name, short):
    with dela_db.session(url, "sasha", via="test") as c:
        return c.execute("INSERT INTO crm.people (name, short, owner_id) VALUES (%s, %s, 'sasha') RETURNING id",
                         (name, short)).fetchone()["id"]


def meeting(url, ref, day: dt.date, project_id=None, title="Созвон"):
    m = {"ref": ref, "started_at": at(day, 11).isoformat(), "kind": "zoom", "title": title, "summary": "итог",
         **({"project_id": str(project_id)} if project_id else {})}
    row = ok(url, "sasha", {"op": "meeting.upsert", "meeting": m})[0]["meeting"]
    with dela_db.session(url, "sasha", via="test") as c:
        assert c.execute("SELECT project_id FROM crm.meetings WHERE id = %s", (row["id"],)).fetchone()["project_id"] == project_id
    return row


# ── 1. Фазы: таблица контракта, Свод поверх заводского ─────────────────

def test_phase_examples_from_contract():
    """Таблица phases.examples — общая с телефоном (core/AsksTest.kt) и вебом: одно правило в трёх местах. Считается по
    ритму таблицы (phases.rhythm поверх заводского, у примера — свои поля поверх): заводское время меняется словом
    Саши, а правило — нет."""
    table = CONTRACT["phases"]
    assert len(table["examples"]) >= 20
    for e in table["examples"]:
        rh, _ = asks.norm({**table["rhythm"], **e.get("rhythm", {})})
        qt, _ = asks.norm_quiet(e["quiet"]) if "quiet" in e else asks.norm_quiet(table["quiet"])
        t = dt.datetime.fromisoformat(e["at"].replace("Z", "+00:00"))
        st = asks.state(rh, qt, t)
        assert (st["phase"], st["next"], st["next_phase"]) == (e["phase"], e["next"], e["next_phase"]), e


def test_friday_goes_through_evening_at_of_step_1():
    """Пятница — через asks.evening_at шага 1 (тест свечей там): поменял fri — сдвинулся и заход очереди."""
    fri = dt.date(2026, 10, 16)
    rh, _ = asks.norm({"fri": "13:15"})
    qt, _ = asks.norm_quiet(None)
    assert asks.phase(rh, qt, at(fri, 13, 14)) == "before"
    assert asks.phase(rh, qt, at(fri, 13, 15)) == "evening"
    assert asks.state(rh, qt, at(fri, 9))["next"] == "13:15"
    # Тишина — с day.quiet.weekend, а не с часа захода: позже пятничного захода, но раньше 15:00 — ещё вечер.
    assert asks.phase(rh, qt, at(fri, 14, 59)) == "evening" and asks.phase(rh, qt, at(fri, 15)) == "quiet"


def test_norm_over_factory_and_crooked_svod(caplog):
    rh, bad = asks.norm({"today": "16:30", "night_end": "4:00", "today_max": 3, "evening_max": 0, "evening": "кривое",
                         "future": {"x": 1}})
    assert rh["today"] == "16:30" and rh["night_end"] == "04:00" and rh["today_max"] == 3
    assert rh["evening_max"] == asks.FACTORY["evening_max"] and rh["evening"] == asks.FACTORY["evening"]
    assert rh["future"] == {"x": 1}, "чужое поле проходит как есть"
    assert any("evening_max" in b for b in bad) and any("evening=" in b for b in bad)
    assert asks.norm("не словарь")[0] == asks.norm(None)[0] == {**asks.FACTORY}
    qt, bad = asks.norm_quiet({"weekend": {"dow": 9, "from": "16:00"}, "daily": [{"from": "21:00", "to": "09:15"}]})
    assert qt["weekend"] == {"dow": 5, "from": "16:00"} and qt["daily"] == [{"from": "21:00", "to": "09:15"}]
    assert bad == ["weekend.dow=9 — не 1…7"]
    assert asks.norm_quiet({"weekend": None})[0]["weekend"] is None
    assert asks.norm_quiet(["кривое"])[0] == asks.QUIET_FACTORY


def test_rhythm_and_quiet_from_svod(dela):
    ok(dela, "sasha", {"op": "svod.set", "key": asks.SVOD_KEY, "value": {"today": "15:30", "today_max": "много"}, "author": "test"},
       {"op": "svod.set", "key": asks.QUIET_KEY, "value": {"v": 1, "weekend": {"dow": 5, "from": "14:45"}}, "author": "test"})
    with dela_db.session(dela, "sasha", via="test") as c:
        rh, qt = asks.rhythm(c, "sasha"), asks.quiet(c, "sasha")
    assert rh["today"] == "15:30" and rh["today_max"] == 5 and rh["evening"] == asks.FACTORY["evening"]
    assert qt["weekend"] == {"dow": 5, "from": "14:45"}
    v = store.view(dela, "sasha", "asks", at="2026-10-16T14:50:00+03:00")
    assert v["phase"] == "quiet" and v["rhythm"]["today"] == "15:30"
    assert store.view(dela, "sasha", "asks", at="2026-10-15T15:10:00+03:00")["phase"] == "before"


# ── 2. Очередь: группы в порядке экрана ─────────────────────────────────

def test_queue_examples_from_contract():
    for e in CONTRACT["queue"]["examples"]:
        assert asks.queue(e["suggestions"], e["day"]) == e["expect"]


# ── 3. Штамп: срочность и предмет при записи ────────────────────────────

def test_urgency_by_meeting_day_and_service_word(dela):
    today = db_today(dela)
    yday = today - dt.timedelta(days=1)
    s = sug(dela, source="meeting", batch_ref="meeting:301", payload={"title": "Прислать модель", "meeting_at": today.isoformat()})
    assert s["payload"]["urgency"] == "today" and s["payload"]["urgent_on"] == today.isoformat()
    s = sug(dela, source="meeting", batch_ref="meeting:302", payload={"title": "Старое", "meeting_at": yday.isoformat()})
    assert s["payload"]["urgency"] == "evening" and "urgent_on" not in s["payload"]
    # Время встречи с поясом — день по Москве.
    s = sug(dela, source="meeting", payload={"title": "Поздний созвон", "meeting_at": at(today, 0, 30).astimezone(dt.timezone.utc).isoformat()})
    assert s["payload"]["urgency"] == "today"
    # Дня в payload нет — день карточки встречи по batch_ref.
    meeting(dela, "303", today)
    s = sug(dela, source="meeting", batch_ref="meeting:303", kind="close", task_id=str(
        ok(dela, "sasha", {"op": "task.create", "task": {"title": "Дело"}})[0]["task"]["id"]), payload={})
    assert s["payload"]["urgency"] == "today"
    # Telegram, Круг — вечер; служба сказала сама — её слово; мусор — правило.
    assert sug(dela, payload={"title": "Из чата"})["payload"]["urgency"] == "evening"
    s = sug(dela, payload={"title": "Срочно из чата", "urgency": "today"})
    assert s["payload"]["urgency"] == "today" and s["payload"]["urgent_on"] == today.isoformat()
    s = sug(dela, payload={"title": "Завтра сгорит", "urgency": "today", "urgent_on": (today + dt.timedelta(days=1)).isoformat()})
    assert s["payload"]["urgent_on"] == (today + dt.timedelta(days=1)).isoformat()
    assert sug(dela, payload={"title": "Мусор", "urgency": "срочно"})["payload"]["urgency"] == "evening"
    s = sug(dela, source="meeting", payload={"title": "По встрече, но служба сказала вечер", "meeting_at": today.isoformat(),
                                             "urgency": "evening"})
    assert s["payload"]["urgency"] == "evening"
    t = ok(dela, "sasha", {"op": "task.create", "task": {"title": "Без места"}})[0]["task"]
    s = sug(dela, source="krug", kind="update", task_id=str(t["id"]), payload={"urgency": "today", "directions": ["work"]})
    assert s["payload"]["urgency"] == "evening" and s["payload"]["about"] == {"kind": "small", "title": "Место в дереве"}


def test_about_first_that_fits(dela):
    today = db_today(dela)
    romashka = project(dela, "sasha", "ООО Ромашка")
    zf = project(dela, "sasha", "ЗФ", kind="internal")
    ivan = person(dela, "Иван Тестов", "Иван")
    with dela_db.session(dela, "sasha", via="test") as c:
        deal = c.execute("INSERT INTO crm.deals (project_id, name, stage) VALUES (%s, 'Ромашка: модель', 'active') RETURNING id",
                         (romashka,)).fetchone()["id"]
    client = {"kind": "client", "id": str(romashka), "title": "ООО Ромашка"}
    assert sug(dela, payload={"title": "a", "project_id": str(romashka)})["payload"]["about"] == client
    assert sug(dela, payload={"title": "b", "project_name": "ООО Ромашка"})["payload"]["about"] == client
    assert sug(dela, payload={"title": "c", "deal_id": str(deal)})["payload"]["about"] == client
    t = ok(dela, "sasha", {"op": "task.create", "task": {"title": "Дело Ромашки", "project_id": str(romashka)}})[0]["task"]
    assert sug(dela, kind="close", task_id=str(t["id"]), payload={})["payload"]["about"] == client
    meeting(dela, "401", today, romashka, "Ромашка: бюджет")
    assert sug(dela, source="meeting", batch_ref="meeting:401", payload={"title": "d"})["payload"]["about"] == client
    # Проект не клиент — не группа клиента: дальше человек.
    a = sug(dela, payload={"title": "e", "project_id": str(zf), "person_id": str(ivan)})["payload"]["about"]
    assert a == {"kind": "person", "id": str(ivan), "title": "Иван"}
    assert sug(dela, payload={"title": "f", "person_name": "Иван Тестов"})["payload"]["about"]["id"] == str(ivan)
    assert sug(dela, payload={"title": "g", "person_name": "Пётр Незнакомый"})["payload"]["about"] == {"kind": "person", "title": "Пётр Незнакомый"}
    t = ok(dela, "sasha", {"op": "task.create", "task": {"title": "Ивану ответить", "person_id": str(ivan)}})[0]["task"]
    assert sug(dela, kind="update", task_id=str(t["id"]), payload={"notes": "x"})["payload"]["about"]["id"] == str(ivan)
    # Вопрос о человеке («Люди сами»): сама карточка — по имени.
    s = sug(dela, kind="person", source="contacts", source_ref="tg:777", quote="новый собеседник",
            payload={"person": {"name": "Ольга Тестова", "telegram_id": 777}})
    assert s["payload"]["about"] == {"kind": "person", "title": "Ольга Тестова"}
    # Встреча без клиента — встреча; без встречи в базе — по названию пачки.
    m = meeting(dela, "402", today, title="Созвон команды")
    a = sug(dela, source="meeting", batch_ref="meeting:402", batch_title="Созвон команды · 15.10", payload={"title": "h"})["payload"]["about"]
    assert a["kind"] == "meeting" and a["id"] == str(m["id"])
    a = sug(dela, source="meeting", batch_ref="meeting:999", batch_title="Где-то · 15.10", payload={"title": "i"})["payload"]["about"]
    assert a == {"kind": "meeting", "title": "Где-то · 15.10"}
    assert sug(dela, batch_title="Telegram 15.10", payload={"title": "j"})["payload"]["about"] == {"kind": "small", "title": "Telegram 15.10"}
    assert sug(dela, payload={"title": "k"})["payload"]["about"] == {"kind": "small", "title": "Прочее"}
    # Кривой id не роняет запись — просто не подходит.
    assert sug(dela, payload={"title": "l", "project_id": "не-uuid", "person_id": 42})["payload"]["about"]["kind"] == "small"


def test_meeting_got_client_later_question_moves_to_client(dela):
    """Вопрос сверки записан до разбора Дел — у встречи ещё нет клиента, предмет «встреча». Разборщик или владелец дал
    встрече клиента (meetings.after_edit → asks.restamp_meeting) — вопрос в группе клиента, срочность прежняя; клиента
    убрали — снова встреча. Название группы встречи — текущее название пачки (её переименовывает и триггер клиента в
    базе, мимо restamp). Вопрос без штампа (до выката) restamp не трогает — он мелочь (проверка шага 2, 15.10.2026)."""
    today = db_today(dela)
    romashka = project(dela, "sasha", "ООО Ромашка")
    m = meeting(dela, "601", today, title="Созвон")
    s = sug(dela, source="meeting", batch_ref="meeting:601", batch_title="Созвон · 15.10", payload={"title": "Прислать модель"})
    assert s["payload"]["about"] == {"kind": "meeting", "id": str(m["id"]), "title": "Созвон · 15.10"}
    assert s["payload"]["urgency"] == "today"
    with dela_db.session(dela, "system", "test") as conn:
        bare = conn.execute("INSERT INTO tasks.suggestions (for_user, kind, source, batch_ref, batch_title, payload, "
                            "created_by) VALUES ('sasha', 'create', 'meeting', 'meeting:601', 'Созвон · 15.10', "
                            "'{\"title\": \"Старый\"}', 'sasha') RETURNING id").fetchone()["id"]

    def stamped(sid):
        with dela_db.session(dela, "sasha", via="test") as conn:
            return conn.execute("SELECT batch_title, payload FROM tasks.suggestions WHERE id = %s", (sid,)).fetchone()

    ok(dela, "sasha", {"op": "meeting.set", "id": str(m["id"]), "set": {"project_id": str(romashka), "topic": "модель"}})
    row = stamped(s["id"])
    assert row["payload"]["about"] == {"kind": "client", "id": str(romashka), "title": "ООО Ромашка"}
    assert (row["payload"]["urgency"], row["payload"]["urgent_on"]) == ("today", today.isoformat())
    assert "about" not in stamped(bare)["payload"], "без штампа — мелочь, разовой правки нет"
    v = store.view(dela, "sasha", "asks", at=at(today, 20).isoformat())
    assert [(g["kind"], g["id"], g["n"]) for g in v["groups"]] == [("client", str(romashka), 1), ("small", None, 1)]
    # Клиента убрали — снова встреча, названием пачки после правки.
    ok(dela, "sasha", {"op": "meeting.set", "id": str(m["id"]), "set": {"project_id": None}})
    row = stamped(s["id"])
    assert row["payload"]["about"]["kind"] == "meeting"
    v = store.view(dela, "sasha", "asks", at=at(today, 20).isoformat())
    assert (v["groups"][0]["kind"], v["groups"][0]["title"]) == ("meeting", row["batch_title"])
    # Пачку переименовали мимо restamp (триггер клиента в базе) — группа встречи берёт новое название пачки.
    with dela_db.session(dela, "system", "test") as conn:
        conn.execute("UPDATE tasks.suggestions SET batch_title = 'ООО Лютик: модель · 15.10' WHERE batch_ref = 'meeting:601'")
    v = store.view(dela, "sasha", "asks", at=at(today, 20).isoformat())
    assert v["groups"][0]["title"] == "ООО Лютик: модель · 15.10"


def test_plan_candidate_is_plan_and_evening(dela):
    t = ok(dela, "sasha", {"op": "task.create", "task": {"title": "Дело на завтра"}})[0]["task"]
    s = sug(dela, source="plan", kind="update", task_id=str(t["id"]), batch_ref="plan:2026-10-16",
            batch_title="Сейчас на пт 16.10 · встреч ~3 — возьми 3", payload={"basket": "carry", "urgency": "today"})
    assert s["payload"]["about"] == {"kind": "plan", "title": "Сейчас на пт 16.10 · встреч ~3 — возьми 3"}
    assert s["payload"]["urgency"] == "evening"


def test_repeat_is_not_restamped_and_yes_keeps_task_clean(dela):
    today = db_today(dela)
    first = sug(dela, source_ref="tg:1", payload={"title": "Позвонить Ивану"})
    again = sug(dela, source_ref="tg:1", payload={"title": "Позвонить Ивану", "urgency": "today"})
    assert again["id"] == first["id"] and again["payload"]["urgency"] == "evening"
    # «Да» — ключи штампа в дело не идут (фильтр полей дела); вариант кнопкой — тоже.
    s = sug(dela, source="meeting", payload={"title": "Прислать договор", "meeting_at": today.isoformat(),
                                             "options": [{"label": "Да"}, {"label": "Нет", "reject": True}]})
    assert s["payload"]["urgency"] == "today"
    r = ok(dela, "sasha", {"op": "suggestion.decide", "id": str(s["id"]), "option": 0})[0]
    assert not {"urgency", "urgent_on", "about"} & set(r["task"]) and r["task"]["title"] == "Прислать договор"
    s = sug(dela, payload={"title": "Без вариантов"})
    r = ok(dela, "sasha", {"op": "suggestion.decide", "id": str(s["id"]), "decision": "accept"})[0]
    assert not {"urgency", "urgent_on", "about"} & set(r["suggestion"]["result"])


def test_self_accepted_still_works(dela):
    """Самопринятое (_auto) не ломается штампом: заведено само, штамп остаётся в payload."""
    today = db_today(dela)
    r = ok(dela, "sasha", {"op": "suggestion.create", "suggestion": {
        "kind": "create", "source": "meeting", "quote": "договорились прислать", "batch_ref": "meeting:501",
        "payload": {"title": "Прислать расчёт", "auto": True, "meeting_at": today.isoformat()}}})[0]
    assert r["suggestion"]["status"] == "accepted" and r["suggestion"]["reason"] == store.AUTO_CREATE_REASON
    assert r["suggestion"]["payload"]["urgency"] == "today" and r["task"]["title"] == "Прислать расчёт"


# ── 4. Вид asks ─────────────────────────────────────────────────────────

def test_view_groups_counts_and_order(dela):
    today = db_today(dela)
    romashka = project(dela, "sasha", "ООО Ромашка")
    ivan = person(dela, "Иван Тестов", "Иван")
    a = sug(dela, payload={"title": "По Ромашке вечером", "project_id": str(romashka)})
    b = sug(dela, source="meeting", payload={"title": "По Ромашке сегодня", "project_id": str(romashka),
                                             "meeting_at": today.isoformat()})
    c = sug(dela, payload={"title": "Ивану", "person_id": str(ivan)})
    d = sug(dela, payload={"title": "Мелочь"})
    # Вопрос до выката шага 2 — без штампа: мелочь, вечер.
    with dela_db.session(dela, "system", "test") as conn:
        old = conn.execute("INSERT INTO tasks.suggestions (for_user, kind, source, payload, batch_title, created_by) "
                           "VALUES ('sasha', 'create', 'telegram', '{\"title\": \"Старый\"}', 'Telegram 14.10', 'sasha') "
                           "RETURNING id").fetchone()["id"]
    t = ok(dela, "sasha", {"op": "task.create", "task": {"title": "Дело на завтра"}})[0]["task"]
    sug(dela, source="plan", kind="update", task_id=str(t["id"]), batch_ref="plan:2026-10-16", payload={"basket": "carry"})
    # Чужой вопрос не виден.
    sug(dela, user="system", for_user="natasha", payload={"title": "Чужой вопрос"})
    # «Ждут от тебя»: горит (срок сегодня) — в счёт, без срока свежее — нет.
    ok(dela, "sasha", {"op": "task.create", "task": {"title": "Ответить Ивану", "ball": "owed", "due_date": today.isoformat()}},
       {"op": "task.create", "task": {"title": "Ответить Петру", "ball": "owed"}},
       {"op": "task.create", "task": {"title": "В «Сейчас»", "focus_on": today.isoformat()}})
    v = store.view(dela, "sasha", "asks", at=at(today, 15, 30).isoformat())
    assert v["phase"] in ("today", "quiet", "evening")  # день недели тестового прогона любой; фазы — в таблице контракта
    assert (v["total"], v["urgent"], v["rest"], v["plan"]) == (5, 1, 4, 1)
    assert [(g["kind"], g["n"], g["urgent"]) for g in v["groups"]] == [("client", 2, 1), ("person", 1, 0), ("small", 2, 0)]
    assert v["groups"][0]["ids"] == [str(b["id"]), str(a["id"])] and v["urgent_ids"] == [str(b["id"])]
    assert v["groups"][1] == {"kind": "person", "id": str(ivan), "title": "Иван", "n": 1, "urgent": 0, "ids": [str(c["id"])]}
    assert v["groups"][2]["title"] == "Мелочь" and set(v["groups"][2]["ids"]) == {str(d["id"]), str(old)}
    assert v["owed_hot"] == 1 and v["now"] == store.view(dela, "sasha", "plan")["today"] == {"total": 1, "done": 0}
    assert v["day"] == today.isoformat()
    # Завтра тот же вопрос — обычный вечерний.
    v = store.view(dela, "sasha", "asks", at=at(today + dt.timedelta(days=1), 15, 30).isoformat())
    assert v["urgent"] == 0 and v["groups"][0]["urgent"] == 0
    # Клиента переименовали — группа свежим именем.
    with dela_db.session(dela, "sasha", via="test") as conn:
        conn.execute("UPDATE crm.projects SET name = 'ООО Ромашка Плюс' WHERE id = %s", (romashka,))
    v = store.view(dela, "sasha", "asks", at=at(today, 20).isoformat())
    assert v["groups"][0]["title"] == "ООО Ромашка Плюс"
    # Ответил — ушло из очереди; погасшее по сроку — тоже.
    ok(dela, "sasha", {"op": "suggestion.decide", "id": str(d["id"]), "decision": "reject"})
    with dela_db.session(dela, "system", "test") as conn:
        conn.execute("UPDATE tasks.suggestions SET expires_at = now() - interval '1 minute' WHERE id = %s", (old,))
    assert store.view(dela, "sasha", "asks")["total"] == 3


def test_view_matches_contract_example(dela):
    def fields(example, actual, path):
        missing = set(example) - set(actual)
        assert not missing, f"{path}: нет {sorted(missing)}"
    sug(dela, payload={"title": "Что-то"})
    v = store.view(dela, "sasha", "asks")
    fields(CONTRACT["view"]["example"], v, "вид asks")
    fields(CONTRACT["view"]["fields"], v, "поля вида asks")
    assert set(CONTRACT["view"]["example"]["groups"][0]) == set(v["groups"][0])
    with pytest.raises(store.OpError, match="не время ISO"):
        store.view(dela, "sasha", "asks", at="вчера")


@pytest.fixture
def client(dela):
    return TestClient(api.build(Config(db_url=dela)))


def test_at_goes_through_http_and_narrow_tokens_are_refused(dela, client):
    """?at= доходит до вида (он в списке параметров api.view: без строки вид молча считал бы «сейчас»)."""
    own = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'device', 'телефон')}"}
    r = client.get("/api/view/asks", params={"at": "2026-10-16T15:00:00+03:00"}, headers=own)
    assert r.status_code == 200 and r.json()["phase"] == "quiet" and r.json()["at"].startswith("2026-10-16T15:00")
    r = client.get("/api/view/asks?at=2026-10-15T15:30:00+03:00", headers=own)  # «+» без кодирования — пробел
    assert r.status_code == 200 and r.json()["phase"] == "today"
    assert client.get("/api/view/asks?at=кривое", headers=own).status_code == 400
    # Служба с токеном владельца (дайджест, Встречи) — можно; узкие — нет.
    svc = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'service', 'digest')}"}
    assert client.get("/api/view/asks", headers=svc).status_code == 200
    for name in ("dengi", "eda", "sport", "dom"):
        h = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'service', name)}"}
        assert client.get("/api/view/asks", headers=h).status_code == 403, name
    me = client.get("/api/me", headers=own).json()
    assert "asks" in me["features"]
    assert "asks" in store.sync(dela, "sasha", 0)["features"]


# ── 5. Контракт, Свод, веб — одна правда ────────────────────────────────

def test_contract_factory_and_svod_keys():
    assert CONTRACT["features"]["flag"] == "asks" and "asks" in store.FEATURES
    assert CONTRACT["svod"]["asks.rhythm"]["factory"] == asks.FACTORY
    assert CONTRACT["svod"]["day.quiet"]["factory"] == asks.QUIET_FACTORY
    plan_c = json.loads((ROOT / "contract" / "dela-plan.json").read_text(encoding="utf-8"))
    assert plan_c["svod"]["asks.rhythm"]["factory"] == asks.FACTORY, "одно заводское на план и очередь"
    keys = json.loads((ROOT / "contract" / "svod.json").read_text(encoding="utf-8"))["svod"]["keys"]
    assert {"asks.rhythm", "day.quiet"} <= set(keys)
    views = json.loads((ROOT / "contract" / "dela.json").read_text(encoding="utf-8"))["views"]
    assert "asks?at=" in views and "asks" in store.VIEWS
    # Ключи штампа — не поля дела и не поля варианта ответа: в дело они не попадут.
    assert not {"urgency", "urgent_on", "about"} & (store.TASK_FIELDS | store.OPTION_FIELDS)


def _js_const(js: str, mark: str):
    line = next(x for x in js.splitlines() if x.startswith(mark))
    return json.loads(line.split("= ", 1)[1].rstrip(";"))


def test_web_has_the_same_rule():
    """Веб: заводское — точная копия asks.py; блок «Подскажи» раскладывается по фазе (newBox, day.js) из asksQueue."""
    js = (STATIC / "app.js").read_text(encoding="utf-8")
    assert _js_const(js, "/* asks.rhythm_factory */") == asks.FACTORY
    assert _js_const(js, "/* day.quiet_factory */") == asks.QUIET_FACTORY
    for name in ("function asksPhaseAt(", "function asksNext(", "function asksQueue(", "const isUrgent = ", "const aboutOf = "):
        assert name in js, name
    box = js[js.index("function newBox()"):]
    box = box[:box.index("\n}\n")]
    assert "asksOn()" in box and "asksQueue()" in box
    # Свод asks.rhythm и day.quiet — в слиянии синка, рядом с krug.directions.
    sync = js[js.index("async function sync("):]
    sync = sync[:sync.index("\n}\n")]
    assert "'asks.rhythm'" in sync and "'day.quiet'" in sync
    day = (STATIC / "day.js").read_text(encoding="utf-8")
    card = day[day.index("function newCard()"):]
    assert "if (asksOn()) return asksCard();" in card[:card.index("\n  }\n")]
    card = day[day.index("function asksCard()"):]
    card = card[:card.index("\n  }\n")]
    assert "asksQueue()" in card
    # Раскрыто щелчком — до смены фазы: ключ — фаза и миг ближайшего захода (как Queue.openKey телефона), а не одно слово
    # фазы — иначе раскрытое в пн 10:00 стояло раскрытым и во вт 10:00 (проверка шага 2). Ключ берут и главная, и #/day.
    key = re.search(r"const asksKey = \(q\) => (.+);", js)
    assert key and "q.phase" in key.group(1) and "q.next.at" in key.group(1), "ключ раскрытия — с мигом захода"
    assert "S.asksOpen = asksKey(q)" in js and "S.asksOpen === asksKey(q)" in js
    assert "asksExpand(q)" in card and "asksExpand(q.phase)" not in js + card
    # Группа встречи — названием пачки (её переименовывает сервер вслед за встречей), как asks._title.
    assert "s.batch_title" in js[js.index("function asksTitle("):js.index("function asksQueue(")]
    # Подписи групп — те же слова, что у сервера (мелочь — одна группа).
    assert f"'{asks.SMALL}'" in js and re.search(r"KINDS = \['client', 'person', 'meeting', 'small'\]", js)


# ── 6. Поезд 16.10.2026: срочность и предмет у новых видов вопросов поезда ──

def test_train_meeting_people_and_speakers_burn_only_for_todays_meeting(dela):
    """Люди и спикеры встречи (вагон 5: «кто это» по имени и вопрос о спикере с кандидатами, режим тени — до трёх на
    встречу) уходят в Дела сразу после разбора, раньше самой встречи (meeting.upsert — следующим кругом службы), и
    payload.meeting_at у них нет: день встречи — из названия пачки службы «… · дд.мм». Сгорает только вопрос о
    сегодняшней встрече; спикер — в группе встречи (у безымянного «человек» — метка «S1»), не своим «человеком»; встреча
    пришла с клиентом — оба в группе клиента, срочность прежняя."""
    today = db_today(dela)
    old = today - dt.timedelta(days=3)
    romashka = project(dela, "sasha", "ООО Ромашка")
    ivan = person(dela, "Иван Тестов", "Иван")

    def named(ref, day, name):
        return sug(dela, kind="person", source="meeting", source_ref=f"meeting-person:{ref}:{name}",
                   batch_ref=f"meeting:{ref}", batch_title=f"Созвон · {day:%d.%m}", quote=f"Встреча «Созвон»: «{name}»",
                   payload={"title": name, "person": {"name": name}, "speaker": {"meeting_ref": ref, "label": "S2"}})

    a = named("701", today, "Пётр Незнакомый")
    assert a["status"] == "pending" and a["payload"]["ask"], a["payload"]
    assert (a["payload"]["urgency"], a["payload"]["urgent_on"]) == ("today", today.isoformat())
    assert a["payload"]["about"] == {"kind": "person", "title": "Пётр Незнакомый"}
    b = named("703", old, "Глеб Пробнов")
    assert b["payload"]["urgency"] == "evening" and "urgent_on" not in b["payload"], "встреча не сегодня — вечер"
    sp = sug(dela, kind="person", source="meeting", source_ref="speaker:701:S1", batch_ref="meeting:701",
             batch_title=f"Созвон · {today:%d.%m}", quote="Встреча «Созвон»: S1",
             payload={"title": "S1", "speaker": {"meeting_ref": "701", "label": "S1", "seconds": 300},
                      "ident": {"label": "S1", "candidates": [{"person_id": str(ivan), "signs": [
                          {"group": "speech", "strong": True, "text": "«Иван, добрый день»"}]}]}})
    assert sp["status"] == "pending" and sp["payload"]["pick"], sp["payload"]
    assert sp["payload"]["urgency"] == "today"
    assert sp["payload"]["about"] == {"kind": "meeting", "title": f"Созвон · {today:%d.%m}"}, "спикер — не «человек» S1"
    # Название без дня, кривой день, чужая пачка — дня нет, вечер (а не «сегодня» наугад).
    for title, ref in (("Без дня", "meeting:704"), ("Кривое · 31.02", "meeting:705"), (f"Чат · {today:%d.%m}", "tg:1")):
        s = sug(dela, kind="create", source="meeting", batch_ref=ref, batch_title=title, payload={"title": "x" + ref})
        assert s["payload"]["urgency"] == "evening", title
    # Встреча пришла в Дела с клиентом (meeting.upsert → after_edit → restamp_meeting): вопросы встречи — у клиента.
    meeting(dela, "701", today, romashka, "Ромашка: бюджет")
    with dela_db.session(dela, "sasha", via="test") as c:
        rows = {str(r["id"]): r["payload"] for r in c.execute(
            "SELECT id, payload FROM tasks.suggestions WHERE batch_ref = 'meeting:701'").fetchall()}
    for sid in (str(a["id"]), str(sp["id"])):
        assert rows[sid]["about"] == {"kind": "client", "id": str(romashka), "title": "ООО Ромашка"}
        assert (rows[sid]["urgency"], rows[sid]["urgent_on"]) == ("today", today.isoformat())
    v = store.view(dela, "sasha", "asks", at=at(today, 15, 30).isoformat())
    assert v["urgent"] == 2 and v["groups"][0]["kind"] == "client" and v["groups"][0]["urgent"] == 2
    assert {g["title"] for g in v["groups"][1:]} >= {"Глеб Пробнов"}


def test_train_did_question_is_evening_and_about_its_person_or_client(dela):
    """«Это закрывает …?» (вагон 4, did.py): фраза самого Саши — вечер (его ответ 3а: «похожих обещаний два и больше —
    спросить вечером»); предмет — клиент дела, иначе его человек."""
    romashka = project(dela, "sasha", "ООО Ромашка")
    ivan = person(dela, "Иван Тестов", "Иван")
    for title, extra in (("Позвонить Ивану по модели", {"project_id": str(romashka)}), ("Перезвонить Ивану про фонды", {})):
        ok(dela, "sasha", {"op": "task.create", "task": {"title": title, "ball": "owed", "person_id": str(ivan), **extra}})
    r = ok(dela, "sasha", {"op": "task.did", "task": {"title": "Позвонил Ивану", "person_id": str(ivan)},
                           "said": "позвонил Ивану"})[0]
    assert len(r["did"]["asked"]) == 2
    with dela_db.session(dela, "sasha", via="test") as c:
        got = {r_["title"]: r_["payload"] for r_ in c.execute(
            "SELECT t.title, s.payload FROM tasks.suggestions s JOIN tasks.tasks t ON t.id = s.task_id "
            "WHERE s.status = 'pending'").fetchall()}
    assert {p["urgency"] for p in got.values()} == {"evening"}
    assert got["Позвонить Ивану по модели"]["about"] == {"kind": "client", "id": str(romashka), "title": "ООО Ромашка"}
    assert got["Перезвонить Ивану про фонды"]["about"] == {"kind": "person", "id": str(ivan), "title": "Иван"}


def test_train_member_question_and_view_are_only_theirs(dela):
    """Приватность (вагон 1, правило 26): предмет вопроса штампуется глазами того, кто его пишет (RLS сессии записи), —
    клиент, которого сотруднику не открыли, не попадает в его группы и именем; вид asks — только свои вопросы: владелец
    чужих не видит и наоборот."""
    romashka = project(dela, "sasha", "ООО Ромашка")  # клиент владельца, сотруднику не открыт
    s = sug(dela, user="natasha", for_user="natasha", payload={"title": "Про чужого клиента", "project_id": str(romashka)})
    assert s["payload"]["about"]["kind"] != "client" and "Ромашка" not in json.dumps(s["payload"]["about"], ensure_ascii=False)
    sug(dela, payload={"title": "Вопрос владельцу", "project_id": str(romashka)})
    theirs, mine = store.view(dela, "natasha", "asks"), store.view(dela, "sasha", "asks")
    assert (theirs["total"], mine["total"]) == (1, 1)
    assert mine["groups"][0]["title"] == "ООО Ромашка" and "Ромашка" not in json.dumps(theirs["groups"], ensure_ascii=False)
