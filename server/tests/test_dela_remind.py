"""Напоминания в Telegram: «что пора» для бота, его кнопки, разбор с напоминанием, перенос из заметок.

Контракт — contract/dela-remind.json; бот (служба встреч) здесь не участвует — только Дела.
"""

from __future__ import annotations

import datetime as dt
import json
import uuid
from pathlib import Path

from starlette.testclient import TestClient

from pravka_dela import api, ask, mcp_tools, parse, remind, store, tokens
from pravka_dela import db as dela_db
from pravka_dela.config import Config
from test_dela_schema import dela, project  # noqa: F401

CONTRACT = json.loads((Path(__file__).resolve().parents[1] / "contract" / "dela-remind.json").read_text(encoding="utf-8"))
BOT = f"svc:{store.REMIND_BOT}"


def ops(url, user, *items, via="app"):
    return store.apply_ops(url, user, list(items), via)["results"]


def telegram(url, user, tid):
    with dela_db.session(url, "system", "test") as c:
        c.execute("UPDATE crm.users SET telegram_id = %s WHERE id = %s", (tid, user))


def ago(minutes: int) -> str:
    return remind.iso(remind.now() - dt.timedelta(minutes=minutes))


# ── Слова и время ───────────────────────────────────────────────────────


def test_words_like_phone():
    today = dt.date(2026, 10, 6)  # вторник
    at = lambda d, h: dt.datetime(2026, 10, d, h, 0, tzinfo=remind.MSK)  # noqa: E731
    assert remind.when_words(at(6, 11), today) == "11:00"
    assert remind.when_words(at(7, 9), today) == "завтра 09:00"
    assert remind.when_words(at(9, 15), today) == "пт 15:00"
    assert remind.when_words(at(20, 9), today) == "20.10 09:00"
    t = {"status": "open", "remind_at": "2026-10-06T11:00:00+03:00"}
    assert remind.chip(t, today) == "⏰ 11:00"
    assert remind.chip({"status": "open", "remind_place": "дом"}, today) == "⏰ дом"
    assert remind.chip({**t, "reminded_at": "2026-10-06T11:00:04+03:00"}, today) == ""
    assert remind.chip({**t, "status": "done"}, today) == ""
    assert remind.local_iso("2026-10-07 11:00") == "2026-10-07T11:00:00+03:00"
    assert remind.local_iso("2026-10-07T08:00:00+00:00") == "2026-10-07T11:00:00+03:00"
    assert remind.local_iso("завтра") is None and remind.local_iso("2026-10-07 25:00") is None
    assert remind.match_place("домой", ["дом", "Летово"]) == "дом"
    assert remind.match_place("на даче", ["дача"]) == ""  # предлог не угадываем — как телефон
    assert remind.match_place("летово", ["дом", "Летово"]) == "Летово"
    assert remind.match_place("офис", ["дом", "Летово"]) == ""
    assert remind.places_block([]).startswith("МЕСТА: телефон пока не знает")
    assert remind.places_block(["дом", "Летово"]).endswith(":\nдом, Летово")


def test_notes_words_back_to_time():
    created = dt.datetime(2026, 10, 6, 9, 40, tzinfo=remind.MSK)  # вторник
    assert remind.said_at("11:00", created) == "2026-10-06T11:00:00+03:00"
    assert remind.said_at("завтра 09:00", created) == "2026-10-07T09:00:00+03:00"
    assert remind.said_at("пт 15:00", created) == "2026-10-09T15:00:00+03:00"
    assert remind.said_at("20.10 09:00", created) == "2026-10-20T09:00:00+03:00"
    assert remind.said_at("когда-нибудь", created) is None


# ── Что пора и кнопки ───────────────────────────────────────────────────


def test_due_only_open_unsent_past_with_telegram(dela):
    telegram(dela, "sasha", 111)
    p = project(dela, "sasha", "Бета Групп")
    with dela_db.session(dela, "system", "test") as c:
        pe = c.execute("INSERT INTO crm.people (name, short, owner_id) VALUES ('Иван Петров', 'Иван', 'sasha') RETURNING id").fetchone()["id"]
    mk = lambda title, **kw: ops(dela, "sasha", {"op": "task.create", "task": {"title": title, **kw}})[0]["task"]  # noqa: E731
    due = mk("Позвонить Ивану", project_id=str(p), person_id=str(pe), remind_at=ago(3))
    mk("Потом", remind_at=remind.iso(remind.now() + dt.timedelta(hours=1)))
    mk("По месту, ещё не приехал", remind_place="дом")
    done = mk("Уже сделано", remind_at=ago(5))
    ops(dela, "sasha", {"op": "task.done", "id": done["id"]})
    late = mk("Служба лежала", remind_at=ago(90))
    sent = mk("Уже отправлено", remind_at=ago(10))
    store.apply_ops(dela, "sasha", [{"op": "task.reminded", "id": sent["id"]}], store.REMIND_BOT, BOT)
    # У Наташи нет Telegram — её напоминания не уходят и не помечаются.
    mk_n = ops(dela, "natasha", {"op": "task.create", "task": {"title": "Наташино", "remind_at": ago(1)}})[0]
    assert mk_n["ok"]
    got = remind.due(dela, "https://dela.example")
    nums = [r["num"] for r in got["reminders"]]
    assert nums == [late["num"], due["num"]]  # по времени напоминания, опоздавшее первым
    r = got["reminders"][1]
    assert r["user"] == "sasha" and r["telegram_id"] == 111 and r["project"] == "Бета Групп" and r["person"] == "Иван"
    assert r["url"] == f"https://dela.example/#task/{due['num']}" and 2 <= r["late_min"] <= 4
    assert got["reminders"][0]["late_min"] >= 89
    # Поля — как в контракте (бот строит сообщение по ним).
    assert set(CONTRACT["reminders_due"]["response"]["reminders"][0]) <= set(r)


def test_marianna_gets_only_reminders_she_set_herself(dela):
    """Правило 6: Марианне (reminders: false) — только её собственные напоминания."""
    telegram(dela, "marianna", 222)
    own = ops(dela, "marianna", {"op": "task.create", "task": {"title": "Купить подарок", "remind_at": ago(1)}})[0]["task"]
    # Дело, принятое ею, но напоминание поставил Саша — не её просьба.
    s = ops(dela, "sasha", {"op": "suggestion.create", "suggestion": {
        "for_user": "marianna", "kind": "create", "source": "bot", "payload": {"title": "Забрать Рому"}}})[0]["suggestion"]
    t = ops(dela, "marianna", {"op": "suggestion.decide", "id": s["id"], "decision": "accept"})[0]["task"]
    with dela_db.session(dela, "system", "sasha", "web") as c:  # «Саша поставил»: журнал запишет его
        c.execute("UPDATE tasks.tasks SET remind_at = now() - interval '1 minute' WHERE id = %s", (t["id"],))
    nums = [r["num"] for r in remind.due(dela)["reminders"]]
    assert own["num"] in nums and t["num"] not in nums


def test_buttons_done_snooze_tomorrow(dela):
    telegram(dela, "sasha", 111)
    telegram(dela, "natasha", 333)
    t = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Повесить полку", "remind_place": "дом"}})[0]["task"]
    ops(dela, "sasha", {"op": "task.set", "id": t["id"], "set": {"remind_at": ago(0)}})  # телефон увидел приезд
    store.apply_ops(dela, "sasha", [{"op": "task.reminded", "id": t["id"]}], store.REMIND_BOT, BOT)
    out = remind.act(dela, 111, t["num"], "snooze", 60)
    at = remind.at_of(out["task"]["remind_at"])
    assert 58 <= (at - remind.now()).total_seconds() / 60 <= 61
    assert out["task"]["reminded_at"] is None and out["task"]["remind_place"] is None  # время вместо места
    out = remind.act(dela, 111, str(t["num"]), "tomorrow")
    at = remind.at_of(out["task"]["remind_at"])
    assert at.date() == remind.now().date() + dt.timedelta(days=1) and at.strftime("%H:%M") == "09:00"
    # Чужое дело и незнакомый Telegram — отказ.
    for tg in (333, 999):
        try:
            remind.act(dela, tg, t["num"], "done")
            raise AssertionError("должен быть отказ")
        except store.OpError:
            pass
    out = remind.act(dela, 111, t["num"], "done")
    assert out["task"]["status"] == "done"
    with dela_db.session(dela, "system", "test") as c:
        h = c.execute("SELECT actor, via FROM crm.history WHERE entity = 'tasks.tasks' AND entity_id = %s ORDER BY id DESC LIMIT 1",
                      (str(t["id"]),)).fetchone()
    assert h["actor"] == BOT and h["via"] == store.REMIND_BOT


def test_reminder_api_only_for_bot(dela):
    telegram(dela, "sasha", 111)
    t = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Проверить напоминания", "remind_at": ago(1)}})[0]["task"]
    bot = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'service', store.REMIND_BOT)}"}
    phone = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'device', 'телефон')}"}
    other = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'service', 'meetings')}"}
    client = TestClient(api.build(Config(db_url=dela, public_url="https://dela.example")))
    assert "remind" in client.get("/api/me", headers=phone).json()["features"]
    assert "remind" in client.get("/api/sync", headers=phone).json()["features"]
    assert client.get("/api/reminders/due").status_code == 401
    assert client.get("/api/reminders/due", headers=phone).status_code == 403
    assert client.get("/api/reminders/due", headers=other).status_code == 403
    d = client.get("/api/reminders/due", headers=bot).json()
    assert d["ok"] and [r["num"] for r in d["reminders"]] == [t["num"]]
    r = d["reminders"][0]
    res = client.post("/api/ops", headers=bot, json={"ops": [{"op": "task.reminded", "op_id": str(uuid.uuid4()), "id": r["task_id"],
                                                             "at": remind.iso(remind.now()), "message_id": 7,
                                                             "remind_at": r["remind_at"]}]}).json()
    assert res["results"][0]["ok"] and res["results"][0]["task"]["reminded_at"]
    assert client.get("/api/reminders/due", headers=bot).json()["reminders"] == []
    assert client.post("/api/reminders/act", headers=phone, json={"telegram_id": 111, "num": t["num"], "action": "done"}).status_code == 403
    assert client.post("/api/reminders/act", headers=bot, json={"num": t["num"]}).status_code == 400
    assert client.post("/api/reminders/act", headers=bot, json={"telegram_id": 5, "num": t["num"], "action": "done"}).status_code == 422
    a = client.post("/api/reminders/act", headers=bot, json={"telegram_id": 111, "num": t["num"], "action": "snooze", "minutes": 30}).json()
    assert a["ok"] and a["task"]["reminded_at"] is None
    with dela_db.session(dela, "system", "test") as c:  # пути бота журнал пишет от его имени
        actors = {x["actor"] for x in c.execute("SELECT actor FROM crm.history WHERE entity_id = %s", (t["id"],))}
    assert BOT in actors


# ── Разбор и правка словами ─────────────────────────────────────────────


def test_parse_contract_example_with_reminders(dela):
    """Ответ-пример контракта (сейчас 14:00, вторник, места: дом, Летово) → поля как answer_expected."""
    ops(dela, "sasha", {"op": "user.settings", "settings": {"places": ["дом", "Летово"]}})
    seen = {}

    def fake(system, user_text):
        seen["system"] = system
        return CONTRACT["prompt"]["answer_example"]

    out = parse.run(dela, "sasha", "напомни завтра в 11 позвонить Ивану…", {}, "", None, ask_fn=fake)
    assert "МЕСТА (remind_place — ровно одно имя из списка" in seen["system"] and "\nдом, Летово" in seen["system"]
    assert not [ph for ph in ("{CATALOG}", "{PLACES}", "{TODAY}", "{NOW}", "{SPEAKER}") if ph in seen["system"]]
    assert ", сейчас " in seen["system"] and "НАПОМИНАНИЕ (remind_at, remind_place)" in seen["system"]
    assert out["errors"] == []
    got = {t["title"]: t for t in out["tasks"]}
    for want in CONTRACT["prompt"]["answer_expected"]:
        t = got[want["title"]]
        assert (t["due_date"] or "") == want["due_date"] and (t["due_time"] or "") == want["due_time"], t
        assert (t["remind_place"] or "") == want["remind_place"], t
        if want["remind_at"]:
            assert dt.datetime.fromisoformat(t["remind_at"]) == dt.datetime.fromisoformat(want["remind_at"])
        else:
            assert t["remind_at"] is None
        if want.get("notes_has"):
            assert want["notes_has"] in (t["notes"] or "")


def test_ask_sets_and_clears_reminders(dela):
    ops(dela, "sasha", {"op": "user.settings", "settings": {"places": ["дом"]}})
    t = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Повесить полку"}})[0]["task"]
    seen = {}
    blank = {k: "" for k in ask._TEXT_FIELDS} | {"ball": "", "now": "", "estimate_min": 0, "labels_add": [], "labels_remove": [], "status": ""}

    def reply(**x):
        def fn(system, user_text):
            seen["system"], seen["user"] = system, user_text
            return {"route": "edit", "reply": "ок", "changes": [{**blank, "num": t["num"], **x}], "create": [], "suggestions": []}
        return fn

    out = ask.run(dela, "sasha", "напомни, когда приеду домой", {"title": "Утро", "task_ids": [t["id"]]}, "", None,
                  ask_fn=reply(remind_place="домой"))
    assert "НАПОМИНАНИЕ (remind_at, remind_place)" in seen["system"] and "\nдом" in seen["system"]
    assert ", сейчас " in seen["user"]
    assert out["changed"][0]["after"] == {"remind_place": "дом"}
    out = ask.run(dela, "sasha", "лучше завтра в 10", {"title": "Утро", "task_ids": [t["id"]]}, "", None,
                  ask_fn=reply(remind_at="2026-10-07 10:00"))
    assert out["changed"][0]["after"] == {"remind_at": "2026-10-07T10:00:00+03:00", "remind_place": None}
    assert "напомню в Telegram, когда приедет: дом" in seen["user"]  # строка дела показывает Claude напоминание
    out = ask.run(dela, "sasha", "не напоминай", {"title": "Утро", "task_ids": [t["id"]]}, "", None, ask_fn=reply(remind_at="-"))
    assert out["changed"][0]["after"] == {"remind_at": None}
    out = ask.run(dela, "sasha", "напомни в офисе", {"title": "Утро", "task_ids": [t["id"]]}, "", None, ask_fn=reply(remind_place="офис"))
    assert out["changed"] == [] and any("офис" in e for e in out["errors"])


def test_remind_rules_cut():
    """Правка словами берёт раздел «НАПОМИНАНИЕ» из общего промпта: переименовали заголовки — тест упадёт."""
    rules = ask.remind_rules()
    assert rules.startswith(ask.REMIND_HEAD) and "remind_place" in rules and ask.REMIND_END not in rules


def test_mcp_add_and_change_with_reminders(dela):
    ops(dela, "sasha", {"op": "user.settings", "settings": {"places": ["дом"]}})
    said = mcp_tools.add(dela, title="Позвонить в банк", remind_at="2026-10-07 10:00", due_date="2026-10-07")
    assert said.startswith("Записал:") and "напомню в Telegram" in said
    num = said.split("#")[1].split()[0]
    said = mcp_tools.change(dela, num, remind_place="домой")
    assert "когда приедет: дом" in said
    assert mcp_tools.change(dela, num, remind_at="когда-нибудь").startswith("Не поменял")
    said = mcp_tools.change(dela, num, remind_place="")
    assert "напомню" not in said


# ── Перенос строк из заметок ────────────────────────────────────────────


def test_move_notes_once(dela):
    tail = remind.NOTES_TAIL
    place = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Взять 50 000",
                                                            "notes": f"⏰ Напомнить, когда приеду: Дом {tail}"}})[0]["task"]
    time_ = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Позвонить",
                                                            "notes": f"про модель\n⏰ Напомнить: завтра 09:00 {tail}"}})[0]["task"]
    closed = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Закрыто",
                                                             "notes": f"⏰ Напомнить: 11:00 {tail}"}})[0]["task"]
    ops(dela, "sasha", {"op": "task.done", "id": closed["id"]})
    assert len(remind.move_notes(dela, apply=False)) == 2
    done = remind.move_notes(dela)
    assert len(done) == 2 and remind.move_notes(dela) == []
    with dela_db.session(dela, "system", "test") as c:
        rows = {r["num"]: r for r in c.execute("SELECT num, notes, remind_at, remind_place FROM tasks.tasks")}
    assert rows[place["num"]]["remind_place"] == "Дом" and rows[place["num"]]["notes"] is None
    created = dt.datetime.fromisoformat(time_["created_at"]).astimezone(remind.MSK).date()
    want = dt.datetime.combine(created + dt.timedelta(days=1), dt.time(9, 0), remind.MSK)
    assert rows[time_["num"]]["remind_at"] == want and rows[time_["num"]]["notes"] == "про модель"
    assert rows[closed["num"]]["remind_at"] is None  # закрытое не трогаем
