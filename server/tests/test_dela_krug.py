"""Круг жизни (dela_0009, дерево целей — dela_0013): направления, «Зачем», цели-узлы, наследование, приватность,
«Моё», движение, засев дерева."""

from __future__ import annotations

import pytest

from pravka_dela import krug, store
from pravka_dela import db as dela_db
from test_dela_schema import db_today, dela, project  # noqa: F401  (фикстура и помощники)

# Выдуманный справочник: настоящий живёт только в Своде владельца.
DIRS = [
    {"key": "health", "pos": 0, "zone": "Я", "name": "Здоровье", "short": "Здоровье", "formula": "Тело в форме."},
    {"key": "work", "pos": 3, "zone": "Мир", "name": "Дело", "short": "Дело", "formula": "Дело растёт."},
    {"key": "parenting", "pos": 6, "zone": "Семья", "name": "Родительство", "short": "Дети", "formula": "Дети растут."},
    {"key": "home", "pos": 8, "zone": "Семья", "name": "Комфортный быт", "short": "Быт", "formula": "Дома хорошо.",
     "aliases": ["Дом"]},
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


def find(url, q):
    return store.view(url, "sasha", "search", q=q)["items"][0]


def test_without_svod_directions_are_refused_in_words(dela):
    r = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Купить магний", "directions": ["health"]}})[0]
    assert not r["ok"] and "krug.directions" in r["error"]


def test_names_become_keys_and_unknown_is_refused(kr):
    t = mk(kr, title="Купить магний", directions=["здоровье", "Дом", "health"])
    assert t["directions"] == ["health", "home"] and t["dirs"] == ["health", "home"]
    r = ops(kr, "sasha", {"op": "task.set", "id": t["id"], "set": {"directions": ["Астрология"]}})[0]
    assert not r["ok"] and "Астрология" in r["error"] and "Здоровье" in r["error"]
    r = ops(kr, "sasha", {"op": "task.set", "id": t["id"], "set": {"directions": ["Здоровье", "Дело", "Дети", "Быт"]}})[0]
    assert not r["ok"] and "не больше 3" in r["error"]


def test_why_chain_main_direction_from_root_own_become_tags(kr):
    top = mk(kr, title="Учится в сильной школе", directions=["Дети"], goal=True)
    step = mk(kr, title="Распечатать столбики примеров", why_title="Уверенно считает до 20")
    goal = find(kr, "считает до 20")
    # «Новая цель словами» — узел дерева, а не дело.
    assert goal["goal"] and step["why_id"] == goal["id"] and step["why_title"] == "Уверенно считает до 20"
    assert step["dirs"] == [] and goal["steps_open"] == 1
    ok(kr, "sasha", {"op": "task.set", "id": goal["id"], "set": {"why_id": str(top["num"])}})
    step = find(kr, "столбики")
    assert step["dirs"] == ["parenting"] and step["directions"] == []
    # Своё направление шага под целью — метка: главное — у корня цепочки (бриф 7.2, §3).
    ok(kr, "sasha", {"op": "task.set", "id": step["id"], "set": {"directions": ["Быт"]}})
    assert find(kr, "столбики")["dirs"] == ["parenting", "home"]
    # По кругу нельзя: цель не может быть шагом своего шага.
    r = ops(kr, "sasha", {"op": "task.set", "id": top["id"], "set": {"why_id": step["id"]}})[0]
    assert not r["ok"] and "по кругу" in r["error"]


def test_goal_nodes_are_private_and_not_in_lists(kr):
    family = project(kr, "sasha", "Семья", kind="personal", sphere="home")
    ok(kr, "sasha", {"op": "access.set", "project_id": str(family), "user_id": "marianna", "role": "edit"})
    goal = mk(kr, title="Личная цель", project_id=str(family), goal=True, directions=["Быт"],
              due_date=db_today(kr).isoformat())
    step = mk(kr, title="Купить продукты", project_id=str(family), why_id=goal["id"], due_date=db_today(kr).isoformat())
    # Узел — без проекта: его текст видит только хозяин; общее дело видно без своего «зачем».
    assert goal["goal"] and goal["project_id"] is None
    seen = {t["id"]: t for t in store.sync(kr, "marianna", 0)["tasks"]}
    assert goal["id"] not in seen and seen[step["id"]]["why_id"] is None and seen[step["id"]]["why_title"] is None
    # В списках дел целей нет — только в дереве и в цепочке «Зачем».
    today = store.view(kr, "sasha", "today")
    listed = [t["id"] for g in today["groups"] for t in g["items"]] + [t["id"] for t in today["mine"]]
    assert step["id"] in listed and goal["id"] not in listed
    assert goal["id"] not in [t["id"] for g in store.view(kr, "sasha", "later")["groups"] for t in g["items"]]
    # Ритм делает практику: цель, которая повторяется.
    ok(kr, "sasha", {"op": "task.set", "id": step["id"], "set": {"rhythm": "еженедельно"}})
    assert find(kr, "продукты")["goal"] and find(kr, "продукты")["rhythm"] == "еженедельно"


def test_no_steps_goal_takes_nothing_under_it(kr):
    g = mk(kr, title="Ориентир", goal=True, directions=["Здоровье"], no_steps=True)
    r = ops(kr, "sasha", {"op": "task.create", "task": {"title": "Шаг", "why_id": g["id"]}})[0]
    assert not r["ok"] and "ориентир" in r["error"]


def test_project_hangs_on_goal_and_tasks_inherit(kr):
    root = mk(kr, title="Своя фирма", goal=True, directions=["Дело"])
    mandates = mk(kr, title="Мандаты закрываются с гонораром", goal=True, why_id=root["id"])
    p = project(kr, "sasha", "Альфа")
    ok(kr, "sasha", {"op": "project.set", "id": str(p), "set": {"why_id": str(mandates["num"])}})
    t = mk(kr, title="Подписать договор", project_id=str(p))
    assert t["dirs"] == ["work"]
    assert {k["id"]: k["why_id"] for k in store.sync(kr, "sasha", 0)["krug"]} == {str(p): mandates["id"]}
    tr = store.view(kr, "sasha", "tree", direction="work")["directions"][0]
    node = tr["children"][0]["children"][0]
    assert node["title"] == "Мандаты закрываются с гонораром" and node["children"][0] == {
        "kind": "project", "name": "Альфа", "client": True, "open": 1, "moved": 0,
        "children": [{"kind": "task", "num": t["num"], "title": "Подписать договор", "project_name": "Альфа", "ball": "mine",
                      "open": 0, "moved": 0}]}
    assert tr["open"] == 1
    r = ops(kr, "sasha", {"op": "project.set", "id": str(p), "set": {"goal": True}})[0]
    assert not r["ok"] and "проект" in r["error"]


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
    assert "krug" in got and {"krug", "tree"} <= set(store.FEATURES)


def test_today_has_mine_and_principle(kr):
    goal = mk(kr, title="Уверенно считает до 20", directions=["Дети"], goal=True)
    step = mk(kr, title="Распечатать столбики", why_id=goal["id"], due_date=db_today(kr).isoformat())
    mk(kr, title="Ответить Гуркину", ball="owed", why_id=goal["id"])  # ждут от меня — не «Моё»
    pinned = mk(kr, title="Смонтировать ролик", want=True)
    mk(kr, title="Пустая цель", goal=True, why_id=goal["id"])  # цель без шагов — не дело, в «Моё» не идёт
    mk(kr, title="Подобрать задачник", why_id=goal["id"])
    v = store.view(kr, "sasha", "today")
    # С 13.10.2026 «Сегодня» — ровно срок сегодня: шаг со сроком — в группах, а «Моё» — подсказка из остального,
    # без того, что и так на виду.
    in_groups = [t["id"] for g in v["groups"] for t in g["items"]]
    assert step["id"] in in_groups and pinned["id"] not in in_groups
    assert [t["title"] for t in v["mine"]] == ["Смонтировать ролик", "Подобрать задачник"]
    assert v["principle"]["text"] in ("Страшно — значит, делаю.", "Решил — делаю сразу.")
    # Взял в «Сейчас» — из «Моё» уходит: отобрано до трёх из оставшегося, а не обрезано после.
    ok(kr, "sasha", {"op": "task.set", "id": pinned["id"], "set": {"focus_on": db_today(kr).isoformat()}})
    assert [t["title"] for t in store.view(kr, "sasha", "today")["mine"]] == ["Подобрать задачник"]


def test_movement_counts_goals_triple_and_only_main(kr):
    goal = mk(kr, title="БЖЖ два раза в неделю", directions=["Здоровье"], goal=True)
    step = mk(kr, title="Тренировка БЖЖ", why_id=goal["id"], directions=["Дети"])  # метка движения не даёт
    ok(kr, "sasha", {"op": "task.done", "id": step["id"]}, {"op": "task.done", "id": goal["id"]})
    with dela_db.session(kr, "sasha", via="app") as c:
        m = krug.movement(c, "sasha", c.execute("SELECT crm.today() AS d").fetchone()["d"])
    assert m == {"health": {"done": 2, "goals": 1, "events": 4, "radius": 6.0}}


def test_krug_question_accepted_with_fix(kr):
    t = mk(kr, title="Театр вдвоём")
    s = ok(kr, "sasha", {"op": "suggestion.create", "suggestion": {
        "kind": "update", "task_id": t["id"], "source": "krug", "batch_ref": "krug:test", "batch_title": "Место в дереве",
        "payload": {"directions": ["home"], "ask": "Театр вдвоём — Быт?"}}})[0]["suggestion"]
    r = ok(kr, "sasha", {"op": "suggestion.decide", "id": s["id"], "decision": "accept", "set": {"directions": ["Здоровье"]}})[0]
    assert r["task"]["directions"] == ["health"] and r["suggestion"]["status"] == "edited"


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
    assert krug.seed(dela, "sasha", data) == {"svod": [], "merged": 0, "projects": [], "tree": None}
    assert store.view(dela, "sasha", "today")["principle"]["text"] == "Своё"


def test_seed_merges_directions_and_imports_tree_once(kr):
    old = mk(kr, title="Купить кроссовки", directions=["Дом"])
    school = mk(kr, title="Поступить в сильную школу", directions=["Дети"], project_id=str(project(kr, "sasha", "Семья", kind="personal")))
    sums = mk(kr, title="Решать примеры")
    alpha = project(kr, "sasha", "Альфа")
    beta = project(kr, "sasha", "Бета")
    # Двенадцать → девять: «household» сливается в «home» (выдуманная карта).
    with dela_db.session(kr, "system", "test") as c:
        c.execute("UPDATE tasks.krug SET directions = '{household}' WHERE id = %s", (old["id"],))
    data = {
        "merge": {"household": "home"},
        "tree": {
            "parenting": [{"title": "Старший сын", "children": [
                {"num": school["num"], "title": "Учится в сильной школе", "tasks": [sums["num"]], "children": [
                    {"title": "Читает каждый день", "rhythm": "ежедневно"},
                    {"title": "Подобрать книги", "task": True}]}]}],
            "work": [{"title": "Своя фирма", "projects": ["Бета"], "children": [
                {"title": "Мандаты закрываются с гонораром", "projects": ["kind:client"]}]}],
            "home": [{"title": "Покупки вовремя", "tasks": [old["num"]]}],
            "health": [{"title": "Ориентир", "no_steps": True}],
        },
    }
    out = krug.seed(kr, "sasha", data)
    t = out["tree"]
    assert out["merged"] == 1 and t["missing"] == []
    assert t["created"] == 7 and t["goals"] == 6 and t["linked"] == 2
    assert t["renamed"] == [f"#{school['num']}: Поступить в сильную школу → Учится в сильной школе"]
    assert sorted(t["projects"]) == ["Альфа", "Бета"]  # «Семья» — не клиент: остаётся без цели
    school = find(kr, "сильной школе")
    assert school["goal"] and school["project_id"] is None and school["dirs"] == ["parenting"] and school["directions"] == []
    assert find(kr, "примеры")["why_id"] == school["id"] and find(kr, "примеры")["dirs"] == ["parenting"]
    assert find(kr, "кроссовки")["dirs"] == ["home"] and find(kr, "кроссовки")["directions"] == []
    assert find(kr, "Читает каждый день")["rhythm"] == "ежедневно"
    books = find(kr, "Подобрать книги")
    assert not books["goal"] and books["why_id"] == school["id"]
    krugs = {k["id"]: k["why_id"] for k in store.sync(kr, "sasha", 0)["krug"]}
    assert krugs[str(alpha)] == find(kr, "Мандаты")["id"] and krugs[str(beta)] == find(kr, "Своя фирма")["id"]
    assert find(kr, "Ориентир")["no_steps"]
    # Повтор (установщик зовёт засев каждый раз): дерево уже посажено — не трогает, перенесённое владельцем на месте.
    ok(kr, "sasha", {"op": "task.set", "id": sums["id"], "set": {"why_id": find(kr, "Читает каждый день")["id"]}})
    assert krug.seed(kr, "sasha", data)["tree"] is None
    assert find(kr, "примеры")["why_id"] == find(kr, "Читает каждый день")["id"]
    # --force — пройти ещё раз: новых узлов нет, ничего не переименовано.
    again = krug.seed(kr, "sasha", data, force=True)["tree"]
    assert again["created"] == 0 and again["renamed"] == []
    # Новая версия файла — Свод переписан один раз.
    v2 = {**data, "version": "7.2", "principles": ["Начинаю с тела."], "tree": {}}
    assert krug.PRINCIPLES_KEY in krug.seed(kr, "sasha", v2)["svod"]
    assert krug.seed(kr, "sasha", v2)["svod"] == []


def test_mcp_tools_speak_tree(kr):
    from pravka_dela import mcp_tools

    top = mcp_tools.add(kr, title="Учится в сильной школе", directions=["Дети"], goal=True)
    assert "цель" in top and "направление Дети" in top and "Входящие" not in top, top
    num = top.split("#", 1)[1].split(" ", 1)[0]
    step = mcp_tools.add(kr, title="Распечатать столбики", why="Уверенно считает до 20")
    assert "зачем #" in step and "Уверенно считает до 20" in step, step
    goal = mcp_tools.change(kr, step.split("зачем #", 1)[1].split(" ", 1)[0], why=num)
    assert "цель" in goal and "направление Дети" in goal and "шагов открыто 1" in goal, goal
    # «Зачем» по названию открытой цели — та же цель, а не новая.
    again = mcp_tools.add(kr, title="Привезти учебник", why="уверенно считает до 20")
    assert again.split("зачем #", 1)[1].split(" ", 1)[0] == step.split("зачем #", 1)[1].split(" ", 1)[0]
    k = mcp_tools.view(kr, "krug")
    assert "Родительство (зона «Семья») — Дети растут." in k and "открыто 2" in k, k
    tree = mcp_tools.view(kr, "tree", direction="Дети")
    assert f"◆ #{num} Учится в сильной школе" in tree and "      #" in tree, tree
    only = mcp_tools.view(kr, "later", direction="Дети")
    assert "Распечатать столбики" in only and "направление Дети" in only
    assert "нет такого направления" in mcp_tools.view(kr, "later", direction="Астрология")
    today = mcp_tools.view(kr, "today")
    assert "Принцип дня:" in today and "Моё" in today, today


def test_view_krug_overview_tree_and_life_view(kr, conn):
    top = mk(kr, title="Поступить в сильную школу", directions=["Дети"], goal=True)
    goal = mk(kr, title="Хорошо знает математику", why_id=top["id"], goal=True)
    mk(kr, title="Распечатать столбики", why_id=goal["id"])
    mk(kr, title="Без направления пока")
    v = store.view(kr, "sasha", "krug")
    kids = next(d for d in v["directions"] if d["key"] == "parenting")
    assert kids["open"] == 1 and [g["title"] for g in kids["goals"]] == ["Поступить в сильную школу"]
    assert [x["title"] for x in v["without"]] == ["Без направления пока"]
    assert [d["key"] for d in v["directions"]][0] == "health"  # по кругу: сверху Здоровье
    t = store.view(kr, "sasha", "tree", node=str(goal["num"]))["node"]
    assert t["kind"] == "goal" and t["open"] == 1 and t["children"][0]["title"] == "Распечатать столбики"
    row = conn.execute("SELECT directions, why, goal FROM life.tasks WHERE title = 'Распечатать столбики'").fetchone()
    assert row == (["parenting"], "Хорошо знает математику", False)


def test_reminder_under_goal_not_in_tree(kr):
    """Поезд 16.10.2026 (проверка вагона 4): напоминание (захват, kind remind) — не дело, и в «Дереве жизни» его нет,
    даже с «Зачем»-целью (её ставит модель захвата): ни узлом, ни в «открытых» цели и направления — как у телефона
    (DelaKrug.treeModel: !it.remind) и веба (treeModel: !isRemind). Дело под той же целью — на месте."""
    goal = mk(kr, title="Сделки закрываются сами", goal=True, directions=["Дело"])
    rem = mk(kr, title="Напомнить про сверку", why_id=goal["id"], kind="remind",
             remind_at=f"{db_today(kr).isoformat()}T23:30:00+03:00")
    step = mk(kr, title="Отправить термшит", why_id=goal["id"])
    assert rem["kind"] == "remind" and rem["why_id"] == goal["id"]
    node = store.view(kr, "sasha", "tree", node=str(goal["num"]))["node"]
    assert node["open"] == 1 and [c["title"] for c in node.get("children", [])] == ["Отправить термшит"]
    work = store.view(kr, "sasha", "tree", direction="work")["directions"][0]
    assert work["open"] == 1
    kids = next(d for d in store.view(kr, "sasha", "krug")["directions"] if d["key"] == "work")
    assert kids["open"] == 1 and [g["open"] for g in kids["goals"]] == [1]
    assert step["id"] != rem["id"]


def test_direction_follows_why(kr):
    """Владелец 13.10.2026: выбрал «Зачем» — направление ставится само по ветке цели; руками направления правятся
    по-прежнему. Прежнее своё главное (дело висело прямо на направлении) не остаётся меткой из чужой ветки."""
    money = mk(kr, title="Бюджет сам собирается", goal=True, directions=["Дело"])
    kids = mk(kr, title="Сын считает до 20", goal=True, directions=["Дети"])
    t = mk(kr, title="Выгрузить пуши банка", directions=["Здоровье", "Быт"])
    assert t["dirs"] == ["health", "home"]
    ok(kr, "sasha", {"op": "task.set", "id": t["id"], "set": {"why_id": money["id"]}})
    t = find(kr, "пуши банка")
    assert t["dirs"] == ["work", "home"] and t["directions"] == ["home"]  # «Быт» поставлен руками — остался меткой
    # Другая ветка — главное за ней; своё прежнее главное было не своим (от цели) — метки не трогаем.
    ok(kr, "sasha", {"op": "task.set", "id": t["id"], "set": {"why_id": kids["id"]}})
    assert find(kr, "пуши банка")["dirs"] == ["parenting", "home"]
    # Направления в той же операции — решение человека.
    ok(kr, "sasha", {"op": "task.set", "id": t["id"], "set": {"why_id": money["id"], "directions": ["Здоровье"]}})
    t = find(kr, "пуши банка")
    assert t["directions"] == ["health"] and t["dirs"] == ["work", "health"]
    # «Зачем» убрали — дело не выпадает из дерева: прежнее главное остаётся своим.
    ok(kr, "sasha", {"op": "task.set", "id": t["id"], "set": {"directions": []}})
    assert find(kr, "пуши банка")["dirs"] == ["work"]
    ok(kr, "sasha", {"op": "task.set", "id": t["id"], "set": {"why_id": None}})
    t = find(kr, "пуши банка")
    assert t["dirs"] == ["work"] and t["directions"] == ["work"]
    # Цель без направления не отнимает своё: главному больше неоткуда взяться.
    bare = mk(kr, title="Цель без ветки", goal=True)
    ok(kr, "sasha", {"op": "task.set", "id": t["id"], "set": {"why_id": bare["id"]}})
    assert find(kr, "пуши банка")["dirs"] == ["work"]
