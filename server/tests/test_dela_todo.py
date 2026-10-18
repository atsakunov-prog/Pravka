"""Дела в виде Todoist (dela_0036, 19.10.2026, todo.py): приоритет p1…p4, дедлайн, повтор, разделы проекта, цвет.

Повтор — правило, которое не зависит от клиента: выполнение повторяющегося дела переносит срок, а дело остаётся
открытым и у старого телефона (он шлёт тот же task.done). Имена — выдуманные.
"""

from __future__ import annotations

import datetime as dt

import pytest

from pravka_dela import store, todo
from test_dela_schema import dela, db_today, project  # noqa: F401

D = dt.date


class E(Exception):
    pass


def nd(rule, due, today):
    return todo.next_due(todo.recur_check(rule, E), due, today)


def ok(res):
    assert all(r["ok"] for r in res["results"]), res
    return res["results"]


def one(url, user, op):
    return ok(store.apply_ops(url, user, [op], via="test"))[0]


def bad(url, user, op) -> str:
    r = store.apply_ops(url, user, [op], via="test")["results"][0]
    assert not r["ok"], r
    return r["error"]


# ── Правило повтора без базы ──────────────────────────────────────────────

def test_next_due_like_todoist():
    assert nd({"every": "day"}, D(2026, 10, 18), D(2026, 10, 19)) == D(2026, 10, 20)  # ежедневное — от выполнения
    assert nd({"every": "week", "days": [1]}, D(2026, 10, 19), D(2026, 10, 21)) == D(2026, 10, 26)
    assert nd({"every": "week", "days": [1, 4]}, D(2026, 10, 19), D(2026, 10, 19)) == D(2026, 10, 22)
    assert nd({"every": "week", "n": 2, "days": [1]}, D(2026, 10, 19), D(2026, 10, 19)) == D(2026, 11, 2)
    assert nd({"every": "day", "n": 3, "from": "done"}, D(2026, 10, 1), D(2026, 10, 19)) == D(2026, 10, 22)
    assert nd({"every": "workday"}, D(2026, 10, 23), D(2026, 10, 23)) == D(2026, 10, 26)  # пт — пн
    assert nd({"every": "year"}, D(2028, 2, 29), D(2028, 2, 29)) == D(2029, 2, 28)
    # Пропущенные разы перескакиваются: следующий — позже сегодня.
    assert nd({"every": "week", "days": [1]}, D(2026, 10, 5), D(2026, 10, 19)) == D(2026, 10, 26)
    assert nd({"every": "week", "until": "2026-10-20"}, D(2026, 10, 19), D(2026, 10, 19)) is None
    # 31-е: в феврале 28-е, в марте снова 31-е.
    rule = todo.recur_check({"every": "month", "monthday": 31}, E)
    feb = todo.next_due(rule, D(2027, 1, 31), D(2027, 1, 31))
    assert (feb, todo.next_due(rule, feb, feb)) == (D(2027, 2, 28), D(2027, 3, 31))
    last = todo.recur_check({"every": "month", "monthday": -1}, E)
    assert todo.next_due(last, D(2027, 1, 31), D(2027, 1, 31)) == D(2027, 2, 28)


@pytest.mark.parametrize("rule", [
    {"every": "fortnight"}, {"every": "day", "n": 0}, {"every": "day", "days": [1]}, {"every": "week", "days": [8]},
    {"every": "month", "monthday": 0}, {"every": "day", "from": "later"}, {"every": "day", "until": "завтра"},
    {"every": "day", "hour": 9}, "каждый день",
])
def test_recur_check_refuses_crooked(rule):
    with pytest.raises(E):
        todo.recur_check(rule, E)


# ── На базе ───────────────────────────────────────────────────────────────

def test_priority_deadline_and_defaults(dela):
    t = one(dela, "sasha", {"op": "task.create", "task": {"title": "Альфа: тизер", "priority": 1, "deadline": "2099-01-31"}})["task"]
    assert (t["priority"], str(t["deadline"]), t["recur"], t["section_id"], t["done_count"]) == (1, "2099-01-31", None, None, 0)
    plain = one(dela, "sasha", {"op": "task.create", "task": {"title": "Разобрать почту"}})["task"]
    assert plain["priority"] == 4  # без приоритета — p4, как у Todoist
    assert "приоритет" in bad(dela, "sasha", {"op": "task.set", "id": str(t["id"]), "set": {"priority": 5}})
    assert "приоритет" in bad(dela, "sasha", {"op": "task.set", "id": str(t["id"]), "set": {"priority": True}})
    got = one(dela, "sasha", {"op": "task.set", "id": str(t["id"]), "set": {"priority": None}})["task"]
    assert got["priority"] == 4


def test_recurring_done_moves_due_and_stays_open(dela):
    today = db_today(dela)
    t = one(dela, "sasha", {"op": "task.create", "task": {
        "title": "Полить цветы", "due_date": today.isoformat(), "due_rule": "каждые 3 дня",
        "recur": {"every": "day", "n": 3}, "focus_on": today.isoformat()}})["task"]
    assert t["recur"] == {"every": "day", "n": 3, "from": "due"} and t["due_rule"] == "каждые 3 дня"
    res = one(dela, "sasha", {"op": "task.done", "id": str(t["id"])})
    got = res["task"]
    nxt = (today + dt.timedelta(days=3)).isoformat()
    assert got["status"] == "open" and got["due_date"] == nxt
    assert got["done_count"] == 1 and got["focus_on"] is None and res["recurred"] == nxt
    # «Выполнить навсегда» — закрывает.
    done = one(dela, "sasha", {"op": "task.done", "id": str(t["id"]), "forever": True})["task"]
    assert done["status"] == "done" and "recurred" not in done


def test_recurring_until_past_closes(dela):
    today = db_today(dela)
    t = one(dela, "sasha", {"op": "task.create", "task": {
        "title": "Курс уколов", "due_date": today.isoformat(), "due_rule": "каждый день до сегодня",
        "recur": {"every": "day", "until": today.isoformat()}}})["task"]
    assert one(dela, "sasha", {"op": "task.done", "id": str(t["id"])})["task"]["status"] == "done"


def test_recur_needs_words_rule_and_date(dela):
    assert "вместе" in bad(dela, "sasha", {"op": "task.create", "task": {"title": "x", "due_date": "2099-01-01",
                                                                        "recur": {"every": "day"}}})
    assert "первый день" in bad(dela, "sasha", {"op": "task.create", "task": {"title": "x", "due_rule": "каждый день",
                                                                             "recur": {"every": "day"}}})
    # Сняли срок у повторяющегося — повтор уходит с ним.
    t = one(dela, "sasha", {"op": "task.create", "task": {"title": "Зарядка", "due_date": "2099-01-01",
                                                          "due_rule": "каждый день", "recur": {"every": "day"}}})["task"]
    got = one(dela, "sasha", {"op": "task.set", "id": str(t["id"]), "set": {"due_date": None}})["task"]
    assert (got["recur"], got["due_rule"]) == (None, None)
    # Ежемесячное помнит число: от 31-го — 31-е в правиле.
    m = one(dela, "sasha", {"op": "task.create", "task": {"title": "Счета", "due_date": "2099-01-31",
                                                          "due_rule": "каждый месяц", "recur": {"every": "month"}}})["task"]
    assert m["recur"]["monthday"] == 31


def test_sections_live_in_project(dela):
    alpha, beta = project(dela, "sasha", "Альфа"), project(dela, "sasha", "Бета")
    s = one(dela, "sasha", {"op": "section.create", "data": {"project_id": str(alpha), "name": "  Фонды ", "pos": 1}})["row"]
    assert s["name"] == "Фонды" and s["seq"] > 0
    # Раздел без проекта — дело переезжает в его проект.
    t = one(dela, "sasha", {"op": "task.create", "task": {"title": "Список фондов", "section_id": str(s["id"])}})["task"]
    assert (t["project_id"], t["section_name"]) == (str(alpha), "Фонды")
    assert "другого проекта" in bad(dela, "sasha", {"op": "task.set", "id": str(t["id"]),
                                                     "set": {"project_id": str(beta), "section_id": str(s["id"])}})
    # Сменили проект — раздел снимается.
    moved = one(dela, "sasha", {"op": "task.set", "id": str(t["id"]), "set": {"project_id": str(beta)}})["task"]
    assert (moved["project_id"], moved["section_id"]) == (str(beta), None)
    # Архивный раздел: открытые дела остаются в проекте без раздела; в архивный не положить.
    t2 = one(dela, "sasha", {"op": "task.create", "task": {"title": "Тизер", "section_id": str(s["id"])}})["task"]
    one(dela, "sasha", {"op": "section.set", "id": str(s["id"]), "set": {"archived": True}})
    assert store.apply_ops(dela, "sasha", [{"op": "task.set", "id": str(t2["id"]), "set": {}}], via="test")["results"][0]["task"]["section_id"] is None
    assert "архиве" in bad(dela, "sasha", {"op": "task.set", "id": str(t2["id"]), "set": {"section_id": str(s["id"])}})
    assert "названия" in bad(dela, "sasha", {"op": "section.create", "data": {"project_id": str(alpha), "name": " "}})
    assert "нет таких полей" in bad(dela, "sasha", {"op": "section.create", "data": {"project_id": str(alpha), "name": "a", "x": 1}})


def test_section_moves_with_open_tasks(dela):
    alpha, beta = project(dela, "sasha", "Альфа"), project(dela, "sasha", "Бета")
    s = one(dela, "sasha", {"op": "section.create", "data": {"project_id": str(alpha), "name": "Договоры"}})["row"]
    t = one(dela, "sasha", {"op": "task.create", "task": {"title": "Акт", "section_id": str(s["id"])}})["task"]
    one(dela, "sasha", {"op": "section.set", "id": str(s["id"]), "set": {"project_id": str(beta)}})
    with store.db.session(dela, "sasha", "t") as c:
        assert c.execute("SELECT project_id FROM tasks.tasks WHERE id = %s", (t["id"],)).fetchone()["project_id"] == beta


def test_sections_follow_project_visibility(dela):
    alpha = project(dela, "sasha", "Альфа")
    one(dela, "sasha", {"op": "section.create", "data": {"project_id": str(alpha), "name": "Фонды"}})
    assert [x["name"] for x in store.sync(dela, "sasha", 0)["sections"]] == ["Фонды"]
    assert store.sync(dela, "natasha", 0)["sections"] == []  # проект ей не открыт
    assert bad(dela, "natasha", {"op": "section.create", "data": {"project_id": str(alpha), "name": "Чужое"}})
    assert "todo" in store.sync(dela, "sasha", 0)["features"]


def test_project_color_from_palette(dela):
    alpha = project(dela, "sasha", "Альфа")
    row = one(dela, "sasha", {"op": "project.set", "id": str(alpha), "set": {"color": "berry_red"}})["row"]
    assert row["color"] == "berry_red"
    assert "палитры" in bad(dela, "sasha", {"op": "project.set", "id": str(alpha), "set": {"color": "#ff0000"}})
    assert one(dela, "sasha", {"op": "project.set", "id": str(alpha), "set": {"color": None}})["row"]["color"] is None
