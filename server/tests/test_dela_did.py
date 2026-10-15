"""«Позвонил X» — сказал, значит сделано (did.py, spec-5 §2; ядро взял шаг 0 «Захват», 15.10.2026).

Ответ владельца 3а: «закрыть само и сказать „закрыл: позвонить X“, „Вернуть“ 7 дней; похожих обещаний два и больше —
спросить вечером». Имена выдуманные.
"""

from __future__ import annotations

import datetime as dt
import uuid

from pravka_dela import did, store
from pravka_dela import db as dela_db
from test_dela_schema import dela, project  # noqa: F401


def ops(url, user, *items, via="app"):
    return store.apply_ops(url, user, list(items), via)["results"]


def person(url, name, short=None, **kw):
    return ops(url, "sasha", {"op": "person.create", "data": {"name": name, **({"short": short} if short else {}), **kw}})[0]["row"]["id"]


def mk(url, title, **kw):
    return ops(url, "sasha", {"op": "task.create", "task": {"title": title, **kw}})[0]["task"]


def did_op(url, title, said=None, user="sasha", **task):
    op = {"op": "task.did", "task": {"title": title, **task}, "said": said or title}
    r = ops(url, user, op)[0]
    assert r["ok"], r
    return r


def status(url, tid):
    with dela_db.session(url, "system", "test") as c:
        return c.execute("SELECT status FROM tasks.tasks WHERE id = %s", (tid,)).fetchone()["status"]


def touches(url, pid):
    with dela_db.session(url, "sasha") as c:
        return c.execute("SELECT * FROM crm.interactions WHERE %s::uuid = ANY (person_ids) AND deleted_at IS NULL ORDER BY at",
                         (pid,)).fetchall()


def test_verb_families():
    assert {did.family(did.verb(t)) for t in ("Позвонил Ивану", "позвонить", "Перезвонить Ивану", "созвонились с Олей")} == {"call"}
    assert did.verb("Иван: прислать сверку") == "присл" and did.family("присл") == "send"
    assert did.verb("Ёлку купить") == "елку"
    assert did.verb("Я уже отправил договор") == "отпра"


def test_one_promise_closes_itself(dela):
    ivan = person(dela, "Иван Тестов", "Иван")
    t = mk(dela, "Позвонить Ивану", ball="owed", person_id=ivan)
    r = did_op(dela, "Позвонил Ивану", "позвонил Ивану", ball="mine", person_id=ivan)
    d = r["did"]
    assert d["closed"] == t["id"] and not d["created"] and d["line"].startswith("закрыл «Позвонить Ивану»")
    assert status(dela, t["id"]) == "done"
    with dela_db.session(dela, "sasha") as c:
        s = c.execute("SELECT * FROM tasks.suggestions WHERE task_id = %s", (t["id"],)).fetchone()
        n = c.execute("SELECT count(*) AS n FROM tasks.tasks WHERE title = 'Позвонил Ивану'").fetchone()["n"]
        com = c.execute("SELECT text FROM tasks.comments WHERE task_id = %s", (t["id"],)).fetchone()
    assert s["status"] == "accepted" and s["reason"] == store.AUTO_CLOSE_REASON and s["result"]["was"] == {"status": "open"}
    assert s["batch_ref"].startswith("said:") and n == 0 and com["text"].startswith("Сказал · ")
    [touch] = touches(dela, ivan)
    assert touch["kind"] == "call" and touch["summary"] == "Позвонил Ивану" and touch["source_ref"] == f"did:{t['id']}"
    # «Сделано само» в «Новом» — с «Вернуть»: веб и старый телефон видят закрытое само.
    week = store.view(dela, "sasha", "week")["automation"]
    assert week["all"]["closed"] == 0 and week["sources"]["said"]["closed"] == 1


def test_two_promises_ask_instead(dela):
    ivan = person(dela, "Иван Тестов", "Иван")
    a = mk(dela, "Позвонить Ивану по модели", ball="owed", person_id=ivan)
    b = mk(dela, "Перезвонить Ивану про фонды", ball="agenda", person_id=ivan)
    tid = str(uuid.uuid4())
    r = did_op(dela, "Позвонил Ивану", id=tid, person_id=ivan)
    d = r["did"]
    assert d["created"] == tid and not d["closed"] and len(d["asked"]) == 2 and "похожих у Ивана 2 — спрошу вечером" in d["line"]
    assert status(dela, a["id"]) == "open" and status(dela, b["id"]) == "open" and status(dela, tid) == "done"
    with dela_db.session(dela, "sasha") as c:
        asks = c.execute("SELECT payload FROM tasks.suggestions WHERE status = 'pending' ORDER BY created_at").fetchall()
    assert all(x["payload"]["ask"].startswith("Это закрывает «") for x in asks) and len(touches(dela, ivan)) == 1


def test_none_or_no_person(dela):
    ivan = person(dela, "Иван Тестов", "Иван")
    r = did_op(dela, "Позвонил Ивану", person_id=ivan)
    assert r["did"]["created"] and r["task"]["status"] == "done" and r["task"]["completed_at"]
    assert len(touches(dela, ivan)) == 1
    r = did_op(dela, "Купил подарок")
    assert r["did"]["created"] and not r["did"]["touch"] and r["did"]["line"] == "записал сделанным"


def test_waiting_result_closes_monitoring(dela):
    ivan = person(dela, "Иван Тестов", "Иван")
    w = mk(dela, "Иван: прислать сверку", ball="waiting", person_id=ivan)
    o = mk(dela, "Прислать Ивану акт", ball="owed", person_id=ivan)
    r = did_op(dela, "Иван: прислал сверку", ball="waiting", person_id=ivan)
    assert r["did"]["closed"] == w["id"] and status(dela, o["id"]) == "open"
    assert touches(dela, ivan)[-1]["kind"] == "email"
    olga = person(dela, "Ольга Тестова", "Ольга")
    did_op(dela, "Ольга: прислала договор", "Ольга прислала договор в телеграме", ball="waiting", person_id=olga)
    assert touches(dela, olga)[-1]["kind"] == "telegram"


def test_old_goal_foreign_and_similar_are_not_closed(dela):
    ivan = person(dela, "Иван Тестов", "Иван")
    old = mk(dela, "Позвонить Ивану", ball="owed", person_id=ivan)
    with dela_db.session(dela, "system", "test") as c:
        c.execute("UPDATE tasks.tasks SET created_at = now() - interval '31 days' WHERE id = %s", (old["id"],))
    goal = mk(dela, "Позвонить Ивану — цель", ball="owed", person_id=ivan, goal=True)
    nat = ops(dela, "natasha", {"op": "task.create", "task": {"title": "Позвонить Ивану", "ball": "agenda"}})[0]["task"]
    like = mk(dela, "Позвонить Ивану насчёт обеда", person_id=ivan)  # «Запустить» — только вопрос
    r = did_op(dela, "Позвонил Ивану", person_id=ivan)
    assert r["did"]["created"] and r["did"]["asked"] and not r["did"]["closed"]
    for t in (old, goal, nat, like):
        assert status(dela, t["id"]) == "open"


def test_family_skip_and_call_twin(dela):
    mom = person(dela, "Мама Тестова", "Мама")
    ops(dela, "sasha", {"op": "svod.set", "key": "people.family", "value": [mom], "author": "test"})
    r = did_op(dela, "Позвонил маме", person_id=mom)
    assert r["did"]["skip"] == "семья" and not r["did"]["touch"] and r["did"]["line"].endswith("семья — в CRM не пишу")
    ivan = person(dela, "Иван Тестов", "Иван")
    ops(dela, "sasha", {"op": "interaction.add", "data": {
        "at": (dt.datetime.now(dt.timezone.utc) - dt.timedelta(minutes=20)).isoformat(), "kind": "call",
        "summary": "Звонок", "person_ids": [ivan], "source": "phone"}})
    r = did_op(dela, "Позвонил Ивану", person_id=ivan)
    assert r["did"]["touch_existing"] and not r["did"]["touch"] and len(touches(dela, ivan)) == 1


def test_client_person_gets_client_and_deal(dela):
    org = ops(dela, "sasha", {"op": "org.create", "data": {"name": "ООО Ромашка"}})[0]["row"]["id"]
    p = project(dela, "sasha", "ООО Ромашка", org_id=org)
    deal = ops(dela, "sasha", {"op": "deal.create", "data": {"project_id": str(p), "name": "Ромашка: модель", "stage": "active"}})[0]["row"]
    ivan = person(dela, "Иван Тестов", "Иван", org_id=org)
    did_op(dela, "Встретился с Иваном", person_id=ivan)
    t = touches(dela, ivan)[-1]
    assert t["kind"] == "meeting" and str(t["project_id"]) == str(p) and str(t["deal_id"]) == deal["id"]


def test_repeat_without_second_close(dela):
    ivan = person(dela, "Иван Тестов", "Иван")
    mk(dela, "Позвонить Ивану", ball="owed", person_id=ivan)
    tid, op_id = str(uuid.uuid4()), str(uuid.uuid4())
    op = {"op": "task.did", "op_id": op_id, "task": {"id": tid, "title": "Позвонил Ивану", "person_id": ivan}}
    a = ops(dela, "sasha", op)[0]
    assert ops(dela, "sasha", op)[0] == a
    b = ops(dela, "sasha", {"op": "task.did", "task": {"id": tid, "title": "Позвонил Ивану", "person_id": ivan}})[0]
    assert b["ok"] and b["did"]["repeat"] and len(touches(dela, ivan)) == 1


def test_undo_returns_all_reopen_keeps_touch(dela):
    ivan = person(dela, "Иван Тестов", "Иван")
    t = mk(dela, "Позвонить Ивану", ball="owed", person_id=ivan)
    r = did_op(dela, "Позвонил Ивану", person_id=ivan)
    res = ops(dela, "sasha", *r["did"]["undo"])
    assert all(x["ok"] for x in res), res
    assert status(dela, t["id"]) == "open" and touches(dela, ivan) == []
    r = did_op(dela, "Позвонил Ивану", person_id=ivan)
    ops(dela, "sasha", {"op": "task.reopen", "id": t["id"]})  # «Вернуть» в «Сделано само»
    assert status(dela, t["id"]) == "open" and len(touches(dela, ivan)) == 1


def test_said_mom_closes_reminder_without_person(dela):
    rem = mk(dela, "Позвонить маме", kind="remind", remind_at="2026-10-15T19:00:00+03:00")
    r = did_op(dela, "Позвонил маме")
    assert r["did"]["closed"] == rem["id"] and status(dela, rem["id"]) == "done"


def test_said_object_picks_the_promise(dela):
    """«Иван прислал сверку» при «Иван: прислать сверку» и «Иван: прислать модель» — закрывается сверка, модель не
    трогается и вопросом не становится (предмет сказан)."""
    ivan = person(dela, "Иван Тестов", "Иван")
    sverka = mk(dela, "Иван: прислать сверку", ball="waiting", person_id=ivan)
    model = mk(dela, "Иван: прислать модель", ball="waiting", person_id=ivan)
    r = did_op(dela, "Иван: прислал сверку", ball="waiting", person_id=ivan)
    assert r["did"]["closed"] == sverka["id"] and not r["did"]["asked"]
    assert status(dela, model["id"]) == "open"
