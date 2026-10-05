"""Статистика Дел: очки только за доведённое, серия с субботой, «Вернуть» снимает очки."""

from __future__ import annotations

import datetime as dt

from pravka_dela import stats, store
from test_dela_schema import dela  # noqa: F401


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
    today = dt.date.today()
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
