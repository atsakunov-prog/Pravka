"""Статистика Дел: очки только за доведённое, серия с субботой, «Вернуть» снимает очки."""

from __future__ import annotations

import datetime as dt

from pravka_dela import stats, store
from test_dela_schema import db_today, dela  # noqa: F401


def test_streak_saturday_does_not_break_and_today_is_not_over():
    mon = dt.date(2026, 10, 5)  # понедельник
    d = lambda n: mon + dt.timedelta(days=n)  # noqa: E731
    # чт, пт, (сб пусто), вс, пн-сегодня ещё без дел
    assert stats.streaks({d(-4), d(-3), d(-1)}, mon) == (3, 3)
    # среда пустая — рвёт; сегодня с делом
    assert stats.streaks({d(-6), d(-4), d(-3), d(-1), mon}, mon) == (4, 4)
    assert stats.streaks({d(-10), d(-9), d(-8)}, mon) == (0, 3)
    assert stats.streaks(set(), mon) == (0, 0)


def test_points_promised_cancel_triage_and_undo(dela):
    run = lambda *o: store.apply_ops(dela, "sasha", list(o), "web")["results"]  # noqa: E731
    today = db_today(dela)
    mk = lambda title, **kw: run({"op": "task.create", "task": {"title": title, **kw}})[0]["task"]  # noqa: E731
    a = mk("Обещанное на сегодня", due_date=today.isoformat())
    b = mk("Без срока")
    c = mk("Ненужное")
    e = mk("Вчерашнее")
    run({"op": "task.done", "id": a["id"]}, {"op": "task.done", "id": b["id"]}, {"op": "task.cancel", "id": c["id"]},
        {"op": "task.done", "id": e["id"]})
    with store.db.session(dela, "sasha", "t") as conn:
        conn.execute("UPDATE tasks.tasks SET completed_at = completed_at - interval '1 day' WHERE id = %s", (e["id"],))
    s = store.apply_ops(dela, "system", [{"op": "suggestion.create", "suggestion": {
        "for_user": "sasha", "kind": "create", "source": "meeting", "payload": {"title": "Из встречи"}}}], "t")["results"][0]["suggestion"]
    run({"op": "suggestion.decide", "id": s["id"], "decision": "reject", "reason": "не дело"})
    # Чужое не считается.
    store.apply_ops(dela, "natasha", [{"op": "task.create", "task": {"title": "Наташино"}}], "web")

    v = store.view(dela, "sasha", "stats")
    assert v["points"]["today"] == 15 + 10 + 2 + 2 and v["points"]["total"] == 15 + 10 + 2 + 2 + 10
    assert v["streak"] == {"current": 2, "best": 2, "today_done": True}
    assert v["days"][-1]["done"] == 2 and v["days"][-1]["cancelled"] == 1 and v["days"][-1]["triage"] == 1
    assert v["month"]["with_due"] == 1 and v["month"]["on_time"] == 1
    assert v["by_source"][0]["done"] == 3 and v["open"]["open"] == 0

    run({"op": "task.reopen", "id": a["id"]})  # «Вернуть» снимает очки само
    v = store.view(dela, "sasha", "stats")
    assert v["points"]["today"] == 10 + 2 + 2 and v["open"]["open"] == 1
    assert store.view(dela, "natasha", "stats")["points"]["total"] == 0


def test_productivity_by_day_and_project(dela):
    """«Продуктивность»: выполненное по дням и проектам с первого дня; чужое не считается, «Вернуть» снимает."""
    run = lambda *o: store.apply_ops(dela, "sasha", list(o), "web")["results"]  # noqa: E731
    today = db_today(dela)
    p = run({"op": "project.create", "data": {"name": "Альфа", "kind": "internal", "sphere": "work"}})[0]["row"]
    mk = lambda title, **kw: run({"op": "task.create", "task": {"title": title, **kw}})[0]["task"]  # noqa: E731
    a, b, c = mk("Раз", project_id=str(p["id"])), mk("Два", project_id=str(p["id"])), mk("Без проекта")
    old = mk("Позавчера")
    run(*({"op": "task.done", "id": x["id"]} for x in (a, b, c, old)))
    # закрытое напоминание — движение Круга, но не очки и не «Продуктивность»
    rem = mk("Напомни: позвонить", kind="remind", remind_at=(dt.datetime.now(dt.timezone.utc) + dt.timedelta(hours=1)).isoformat())
    run({"op": "task.done", "id": rem["id"]})
    with store.db.session(dela, "sasha", "t") as conn:
        conn.execute("UPDATE tasks.tasks SET completed_at = completed_at - interval '2 days' WHERE id = %s", (old["id"],))
    store.apply_ops(dela, "natasha", [{"op": "task.create", "task": {"title": "Чужое дело"}}], "web")
    v = store.view(dela, "sasha", "productivity")
    assert v["total"] == 4 and v["since"] == (today - dt.timedelta(days=2)).isoformat()
    last = v["days"][-1]
    assert last["day"] == today.isoformat() and last["by_project"] == {str(p["id"]): 2, "": 1}
    run({"op": "task.reopen", "id": a["id"]})
    assert store.view(dela, "sasha", "productivity")["days"][-1]["by_project"][str(p["id"])] == 1
    assert store.view(dela, "natasha", "productivity")["total"] == 0
