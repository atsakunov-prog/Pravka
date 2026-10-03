"""Логика Дел: операции, повторы, споры, предложения, синк и виды."""

from __future__ import annotations

import datetime as dt
import uuid

import pytest

from pravka_dela import store
from test_dela_schema import dela, project  # noqa: F401  (фикстура и помощник)


def ops(url, user, *items, via="app"):
    return store.apply_ops(url, user, list(items), via)["results"]


def test_create_set_done_with_short_number(dela):
    r = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Яблоков: ответить на КП", "ball": "waiting"}})[0]
    assert r["ok"], r
    t = r["task"]
    assert t["waiting_since"] == dt.date.today().isoformat() or t["waiting_since"]
    r = ops(dela, "sasha", {"op": "task.done", "id": f"#{t['num']}"})[0]
    assert r["ok"] and r["task"]["status"] == "done" and r["task"]["completed_at"]


def test_repeat_op_returns_same_answer(dela):
    op = {"op": "task.create", "op_id": str(uuid.uuid4()), "task": {"title": "Продлить Контур"}}
    first = ops(dela, "sasha", op)[0]
    again = ops(dela, "sasha", op)[0]
    assert first == again
    found = store.view(dela, "sasha", "search", q="Контур")["items"]
    assert len(found) == 1


def test_offline_create_with_own_id_is_idempotent(dela):
    tid = str(uuid.uuid4())
    a = ops(dela, "sasha", {"op": "task.create", "task": {"id": tid, "title": "Выкинуть велосипед"}})[0]
    b = ops(dela, "sasha", {"op": "task.create", "task": {"id": tid, "title": "Выкинуть велосипед"}})[0]
    assert a["task"]["id"] == b["task"]["id"] == tid


def test_bad_op_does_not_break_the_batch(dela):
    res = ops(
        dela, "sasha",
        {"op": "task.create", "task": {"title": "Хорошее"}},
        {"op": "task.create", "task": {"title": "Плохое", "ball": "куда-то"}},
        {"op": "нет-такой"},
        {"op": "task.create", "task": {"title": "Тоже хорошее"}},
    )
    assert [r["ok"] for r in res] == [True, False, False, True]
    assert "ball" in res[1]["error"] or "check" in res[1]["error"].lower()


def test_conflict_is_reported_last_write_wins(dela):
    t = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Тизер Додо", "due_date": "2026-10-05"}})[0]["task"]
    ops(dela, "sasha", {"op": "task.set", "id": t["id"], "set": {"due_date": "2026-10-07"}}, via="web")
    # Телефон без сети думал, что срок 05.10, и ставит 06.10.
    r = ops(dela, "sasha", {"op": "task.set", "id": t["id"], "set": {"due_date": "2026-10-06"}, "was": {"due_date": "2026-10-05"}})[0]
    assert r["conflicts"] == ["due_date"] and r["task"]["due_date"] == "2026-10-06"


def test_view_only_user_cannot_change(dela):
    p = project(dela, "sasha", "Чемодан.Про")
    ops(dela, "sasha", {"op": "access.set", "project_id": str(p), "user_id": "natasha", "role": "view"})
    t = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Статус по проекту", "project_id": str(p)}})[0]["task"]
    r = ops(dela, "natasha", {"op": "task.set", "id": t["id"], "set": {"title": "x"}})[0]
    assert not r["ok"] and "смотреть" in r["error"]


def test_suggestion_accept_edit_reject(dela, conn):
    mk = lambda title: ops(dela, "system", {"op": "suggestion.create", "suggestion": {  # noqa: E731
        "for_user": "sasha", "kind": "create", "source": "meeting", "source_ref": "meeting:42",
        "batch_ref": "meeting:42", "batch_title": "Додо, 03.10", "payload": {"title": title, "ball": "mine"}}})[0]["suggestion"]
    a, b, c = mk("Горецкий: прислать тизер"), mk("Наташа: КП"), mk("Идея: фонд")
    # Повтор той же находки дубля не плодит.
    assert mk("Горецкий: прислать тизер")["id"] == a["id"]
    new = store.view(dela, "sasha", "new")
    assert new["count"] == 3 and new["batches"][0]["title"] == "Додо, 03.10"

    r1 = ops(dela, "sasha", {"op": "suggestion.decide", "id": a["id"], "decision": "accept"})[0]
    r2 = ops(dela, "sasha", {"op": "suggestion.decide", "id": b["id"], "decision": "accept", "set": {"ball": "waiting"}})[0]
    r3 = ops(dela, "sasha", {"op": "suggestion.decide", "id": c["id"], "decision": "reject", "reason": "это идея, не дело"})[0]
    assert r1["suggestion"]["status"] == "accepted" and r1["task"]["source"] == "meeting"
    assert r1["task"]["source_ref"] == "meeting:42"
    assert r2["suggestion"]["status"] == "edited" and r2["task"]["ball"] == "waiting"
    assert r3["suggestion"]["status"] == "rejected" and r3["suggestion"]["reason"] == "это идея, не дело"
    again = ops(dela, "sasha", {"op": "suggestion.decide", "id": a["id"], "decision": "accept"})[0]
    assert not again["ok"]
    # Чужое «Новое» не видно.
    assert store.view(dela, "natasha", "new")["count"] == 0


def test_assign_to_marianna_only_through_her_consent(dela):
    p = project(dela, "sasha", "Семья", sphere="home", kind="personal")
    ops(dela, "sasha", {"op": "access.set", "project_id": str(p), "user_id": "marianna", "role": "edit"})
    t = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Записать Рому к врачу", "project_id": str(p)}})[0]["task"]
    r = ops(dela, "sasha", {"op": "task.set", "id": t["id"], "set": {"owner_id": "marianna"}})[0]
    assert not r["ok"] and "согласия" in r["error"]
    s = ops(dela, "sasha", {"op": "suggestion.create", "suggestion": {
        "for_user": "marianna", "kind": "assign", "task_id": t["id"], "source": "user"}})[0]["suggestion"]
    took = ops(dela, "marianna", {"op": "suggestion.decide", "id": s["id"], "decision": "accept"})[0]
    assert took["ok"], took
    assert took["task"]["owner_id"] == "marianna"


def test_sync_incremental_and_full_on_access_change(dela):
    first = store.sync(dela, "natasha", 0)
    assert first["full"] and first["tasks"] == []
    p = project(dela, "sasha", "ПТИЦ")
    ops(dela, "sasha", {"op": "task.create", "task": {"title": "Наташа: КП ПТИЦ", "project_id": str(p)}})
    # Пока доступа нет — Наташе пусто.
    assert store.sync(dela, "natasha", first["seq"])["tasks"] == []
    ops(dela, "sasha", {"op": "access.set", "project_id": str(p), "user_id": "natasha", "role": "edit"})
    after = store.sync(dela, "natasha", first["seq"])
    assert after["full"] and [t["title"] for t in after["tasks"]] == ["Наташа: КП ПТИЦ"]
    quiet = store.sync(dela, "natasha", after["seq"])
    assert not quiet["full"]
    ops(dela, "sasha", {"op": "access.set", "project_id": str(p), "user_id": "natasha", "role": None})
    gone = store.sync(dela, "natasha", after["seq"])
    assert gone["full"] and gone["tasks"] == []


def test_morning_and_week_views(dela):
    today = dt.date.today()
    p = project(dela, "sasha", "Ростислав", money_default="paid")
    ops(
        dela, "sasha",
        {"op": "task.create", "task": {"title": "Сегодня", "due_date": today.isoformat()}},
        {"op": "task.create", "task": {"title": "Давно", "due_date": (today - dt.timedelta(days=30)).isoformat()}},
        {"op": "task.create", "task": {"title": "Платное без даты", "project_id": str(p)}},
        {"op": "task.create", "task": {"title": "Ждём", "ball": "waiting", "nudge_on": today.isoformat()}},
        {"op": "task.create", "task": {"title": "Сейчас", "focus_on": today.isoformat()}},
    )
    m = store.view(dela, "sasha", "morning")
    assert {t["title"] for t in m["today"]} == {"Сегодня", "Давно"}
    assert [t["title"] for t in m["paid_undated"]] == ["Платное без даты"]
    assert [t["title"] for t in m["nudge"]] == ["Ждём"]
    assert [t["title"] for t in m["now"]] == ["Сейчас"]
    w = store.view(dela, "sasha", "week")
    assert [t["title"] for t in w["stale"]] == ["Давно"]
    # Платный проект с делом не попадает в «без следующего шага»; пустой — попадает.
    project(dela, "sasha", "Пустой", money_default="potential")
    w = store.view(dela, "sasha", "week")
    assert [p["name"] for p in w["no_next_step"]] == ["Пустой"]


def test_expire_suggestions(dela, conn):
    s = ops(dela, "system", {"op": "suggestion.create", "suggestion": {
        "for_user": "sasha", "kind": "create", "source": "userbot", "payload": {"title": "Старое"},
        "expires_at": "2020-01-01T00:00:00+03:00"}})[0]["suggestion"]
    assert store.expire_suggestions(dela) == 1
    assert conn.execute("SELECT status FROM tasks.suggestions WHERE id = %s", (s["id"],)).fetchone()[0] == "expired"


def test_suggestion_names_resolve_on_accept(dela):
    p = project(dela, "sasha", "Альфа Групп", aliases=["Альфа"])
    with store.db.session(dela, "system", "t") as c:
        c.execute("INSERT INTO crm.people (name, short, owner_id) VALUES ('Дмитрий Орлов', 'Орлов', 'sasha')")
    s = ops(dela, "system", {"op": "suggestion.create", "suggestion": {
        "for_user": "sasha", "kind": "create", "source": "meeting", "batch_ref": "meeting:9",
        "payload": {"title": "Дмитрий Орлов: посмотреть презентацию", "ball": "waiting",
                    "person_name": "Дмитрий Орлов", "project_name": "Альфа"}}})[0]["suggestion"]
    t = ops(dela, "sasha", {"op": "suggestion.decide", "id": s["id"], "decision": "accept"})[0]["task"]
    assert (t["project_id"], t["person_short"], t["ball"]) == (str(p), "Орлов", "waiting")
    # Чужой проект по имени не подставится: Наташе он не виден.
    s2 = ops(dela, "system", {"op": "suggestion.create", "suggestion": {
        "for_user": "natasha", "kind": "create", "source": "bot", "payload": {"title": "x", "project_name": "Альфа"}}})[0]["suggestion"]
    t2 = ops(dela, "natasha", {"op": "suggestion.decide", "id": s2["id"], "decision": "accept"})[0]["task"]
    assert t2["project_id"] is None
