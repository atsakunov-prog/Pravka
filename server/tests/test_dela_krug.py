"""Круг жизни (dela_0009): направления, «Зачем», наследование, приватность, «Моё», движение."""

from __future__ import annotations

import datetime as dt

import pytest

from pravka_dela import krug, store
from pravka_dela import db as dela_db
from test_dela_schema import dela, project  # noqa: F401  (фикстура и помощник)

# Выдуманный справочник: настоящий живёт только в Своде владельца.
DIRS = [
    {"key": "health", "pos": 12, "name": "Здоровье", "short": "Здоровье", "formula": "Тело в форме."},
    {"key": "work", "pos": 3, "name": "Дело", "short": "Дело", "formula": "Дело растёт."},
    {"key": "education", "pos": 7, "name": "Образование", "short": "Образование", "formula": "Дети учатся."},
    {"key": "home", "pos": 10, "name": "Комфорт в доме", "short": "Дом", "formula": "Дома хорошо."},
    {"key": "household", "pos": 11, "name": "Быт", "short": "Быт", "formula": "Быт настроен."},
]


def ops(url, user, *items, via="app"):
    return store.apply_ops(url, user, list(items), via)["results"]


def ok(url, user, *items):
    res = ops(url, user, *items)
    assert all(r["ok"] for r in res), res
    return res


@pytest.fixture()
def kr(dela):
    ok(dela, "sasha", {"op": "svod.set", "key": krug.DIRECTIONS_KEY, "value": DIRS, "author": "test"},
       {"op": "svod.set", "key": krug.PRINCIPLES_KEY, "value": ["Страшно — значит, делаю.", "Решил — делаю сразу."], "author": "test"})
    return dela


def mk(url, user="sasha", **task):
    return ok(url, user, {"op": "task.create", "task": task})[0]["task"]


def test_without_svod_directions_are_refused_in_words(dela):
    r = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Купить магний", "directions": ["health"]}})[0]
    assert not r["ok"] and "krug.directions" in r["error"]


def test_names_become_keys_and_unknown_is_refused(kr):
    t = mk(kr, title="Купить магний", directions=["здоровье", "Дом", "health"])
    assert t["directions"] == ["health", "home"] and t["dirs"] == ["health", "home"]
    r = ops(kr, "sasha", {"op": "task.set", "id": t["id"], "set": {"directions": ["Астрология"]}})[0]
    assert not r["ok"] and "Астрология" in r["error"] and "Здоровье" in r["error"]


def test_why_chain_inherits_and_counts_steps(kr):
    top = mk(kr, title="Попробовать поступить в сильную школу", directions=["Образование"])
    step = mk(kr, title="Распечатать столбики примеров", why_title="Уверенно считает до 20")
    goal = store.view(kr, "sasha", "search", q="считает до 20")["items"][0]
    assert step["why_id"] == goal["id"] and step["why_num"] == goal["num"] and step["why_title"] == "Уверенно считает до 20"
    assert step["dirs"] == [] and goal["steps_open"] == 1
    ok(kr, "sasha", {"op": "task.set", "id": goal["id"], "set": {"why_id": str(top["num"])}})
    step = store.view(kr, "sasha", "search", q="столбики")["items"][0]
    assert step["dirs"] == ["education"] and step["directions"] == []
    # Своё направление сильнее наследованного.
    ok(kr, "sasha", {"op": "task.set", "id": step["id"], "set": {"directions": ["Быт"]}})
    assert store.view(kr, "sasha", "search", q="столбики")["items"][0]["dirs"] == ["household"]
    # По кругу нельзя: цель не может быть шагом своего шага.
    r = ops(kr, "sasha", {"op": "task.set", "id": top["id"], "set": {"why_id": step["id"]}})[0]
    assert not r["ok"] and "по кругу" in r["error"]


def test_project_directions_are_inherited_only_by_owner(kr, conn):
    p = project(kr, "sasha", "Альфа")
    ok(kr, "sasha", {"op": "project.set", "id": str(p), "set": {"directions": ["Дело"]}},
       {"op": "access.set", "project_id": str(p), "user_id": "natasha", "role": "edit"})
    mine = mk(kr, title="Подписать счёт", project_id=str(p))
    hers = mk(kr, "natasha", title="Выставить счёт", project_id=str(p))
    assert mine["dirs"] == ["work"] and hers["dirs"] == []
    # Наташа видит дело Саши, но не его Круг; поставить его направления не может.
    seen = {t["id"]: t for t in store.sync(kr, "natasha", 0)["tasks"]}
    assert seen[mine["id"]]["dirs"] == [] and seen[mine["id"]]["directions"] == []
    assert store.sync(kr, "natasha", 0)["krug"] == []
    r = ops(kr, "natasha", {"op": "task.set", "id": mine["id"], "set": {"directions": ["Дело"]}})[0]
    assert not r["ok"] and "хозяин" in r["error"]
    # Журнал карточки (api.task_card читает crm.history по tasks.tasks) про Круг не знает: у Круга свой журнал.
    ok(kr, "sasha", {"op": "task.set", "id": mine["id"], "set": {"directions": ["Здоровье"]}})
    rows = conn.execute("SELECT entity, after FROM crm.history WHERE entity_id = %s", (mine["id"],)).fetchall()
    assert not any("directions" in (after or {}) for entity, after in rows if entity == "tasks.tasks")
    assert any(entity == "tasks.krug" for entity, _ in rows)


def test_krug_only_change_reaches_incremental_sync(kr):
    t = mk(kr, title="Выкинуть велосипед")
    seq = store.sync(kr, "sasha", 0)["seq"]
    ok(kr, "sasha", {"op": "task.set", "id": t["id"], "set": {"directions": ["Дом"]}})
    got = store.sync(kr, "sasha", seq)
    assert [x["directions"] for x in got["tasks"] if x["id"] == t["id"]] == [["home"]] and got["seq"] > seq
    assert "krug" in got and "krug" in store.FEATURES


def test_today_has_mine_and_principle(kr):
    goal = mk(kr, title="Уверенно считает до 20", directions=["Образование"])
    step = mk(kr, title="Распечатать столбики", why_id=goal["id"], due_date=dt.date.today().isoformat())
    mk(kr, title="Ответить Гуркину", ball="owed", why_id=goal["id"])  # ждут от меня — не «Моё»
    pinned = mk(kr, title="Смонтировать ролик", want=True)
    v = store.view(kr, "sasha", "today")
    assert [t["title"] for t in v["mine"]] == ["Смонтировать ролик", "Распечатать столбики"]
    assert v["principle"]["text"] in ("Страшно — значит, делаю.", "Решил — делаю сразу.")
    # Дело из «Моё» в трёх группах не повторяется.
    in_groups = [t["id"] for g in v["groups"] for t in g["items"]]
    assert step["id"] not in in_groups and pinned["id"] not in in_groups


def test_movement_counts_goals_triple(kr):
    goal = mk(kr, title="БЖЖ: 12 недель по две тренировки", directions=["Здоровье"])
    step = mk(kr, title="Тренировка БЖЖ", why_id=goal["id"])
    ok(kr, "sasha", {"op": "task.done", "id": step["id"]}, {"op": "task.done", "id": goal["id"]})
    with dela_db.session(kr, "sasha", via="app") as c:
        m = krug.movement(c, "sasha", c.execute("SELECT crm.today() AS d").fetchone()["d"])
    assert m["health"]["events"] == 4 and m["health"]["goals"] == 1 and m["health"]["radius"] == 6.0


def test_krug_question_accepted_with_fix(kr):
    t = mk(kr, title="Театр вдвоём")
    s = ok(kr, "sasha", {"op": "suggestion.create", "suggestion": {
        "kind": "update", "task_id": t["id"], "source": "krug", "batch_ref": "krug:test", "batch_title": "Направления",
        "payload": {"directions": ["home"], "ask": "Театр вдвоём — Дом?"}}})[0]["suggestion"]
    r = ok(kr, "sasha", {"op": "suggestion.decide", "id": s["id"], "decision": "accept", "set": {"directions": ["Быт"]}})[0]
    assert r["task"]["directions"] == ["household"] and r["suggestion"]["status"] == "edited"


def test_seed_writes_svod_and_projects_once(dela):
    client = project(dela, "sasha", "Альфа")
    zf = project(dela, "sasha", "ЗФ", kind="internal")
    family = project(dela, "sasha", "Семья", kind="personal", sphere="home")
    data = {"directions": DIRS, "principles": ["Начинаю с тела."], "projects": {"_": "правила", "kind:client": ["work"], "ЗФ": ["work"]}}
    out = krug.seed(dela, "sasha", data)
    assert out["svod"] == [krug.DIRECTIONS_KEY, krug.PRINCIPLES_KEY] and len(out["projects"]) == 2
    got = {k["id"]: k["directions"] for k in store.sync(dela, "sasha", 0)["krug"]}
    assert got == {str(client): ["work"], str(zf): ["work"]} and str(family) not in got
    # Повтор ничего не переписывает: правки владельца в Своде целы.
    ok(dela, "sasha", {"op": "svod.set", "key": krug.PRINCIPLES_KEY, "value": ["Своё"], "author": "web"})
    assert krug.seed(dela, "sasha", data) == {"svod": [], "projects": []}
    assert store.view(dela, "sasha", "today")["principle"]["text"] == "Своё"


def test_mcp_tools_speak_krug(kr):
    from pravka_dela import mcp_tools

    top = mcp_tools.add(kr, title="Поступить в сильную школу", directions=["Образование"])
    assert "направление Образование" in top and "(насл.)" not in top, top
    num = top.split("#", 1)[1].split(" ", 1)[0]
    step = mcp_tools.add(kr, title="Распечатать столбики", why="Уверенно считает до 20")
    assert "зачем #" in step and "Уверенно считает до 20" in step, step
    goal = mcp_tools.change(kr, step.split("зачем #", 1)[1].split(" ", 1)[0], why=num)
    assert "направление Образование (насл.)" in goal and "цель, шагов открыто 1" in goal, goal
    # «Зачем» по названию открытой цели — та же цель, а не новая.
    again = mcp_tools.add(kr, title="Привезти учебник", why="уверенно считает до 20")
    assert again.split("зачем #", 1)[1].split(" ", 1)[0] == step.split("зачем #", 1)[1].split(" ", 1)[0]
    k = mcp_tools.view(kr, "krug")
    assert "7 · Образование — открыто 4" in k and "    #" in k, k
    only = mcp_tools.view(kr, "later", direction="Образование")
    assert "Распечатать столбики" in only and "направление Образование" in only
    assert "нет такого направления" in mcp_tools.view(kr, "later", direction="Астрология")
    today = mcp_tools.view(kr, "today")
    assert "Принцип дня:" in today and "Моё" in today, today


def test_view_krug_tree_and_life_view(kr, conn):
    top = mk(kr, title="Поступить в сильную школу", directions=["Образование"])
    goal = mk(kr, title="Хорошо знает математику", why_id=top["id"])
    mk(kr, title="Распечатать столбики", why_id=goal["id"])
    mk(kr, title="Без направления пока")
    v = store.view(kr, "sasha", "krug")
    edu = next(d for d in v["directions"] if d["key"] == "education")
    assert edu["open"] == 3 and [n["title"] for n in edu["tree"]] == ["Поступить в сильную школу"]
    assert edu["tree"][0]["steps"][0]["steps"][0]["title"] == "Распечатать столбики"
    assert [x["title"] for x in v["without"]] == ["Без направления пока"]
    assert [d["key"] for d in v["directions"]][0] == "health"  # по кругу: сверху 12
    row = conn.execute("SELECT directions, why FROM life.tasks WHERE title = 'Распечатать столбики'").fetchone()
    assert row == (["education"], "Хорошо знает математику")
