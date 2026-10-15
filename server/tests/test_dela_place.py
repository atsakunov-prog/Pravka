"""Место в дереве при разборе (place.py, 13.10.2026): номер цели от модели (why_auto) — в «Зачем» от службы, отказы
без ошибки, блок «дерево жизни» для промптов, Свод krug.place, учёба на правках владельца, недельные выводы."""

from __future__ import annotations

import datetime as dt
import json
from types import SimpleNamespace as NS
from zoneinfo import ZoneInfo

from pravka_dela import db, krug, parse, place, store
from test_dela_krug import mk, ok, ops  # noqa: F401
from test_dela_krug import kr  # noqa: F401  (фикстура)
from test_dela_schema import db_today, dela, project  # noqa: F401

MSK = ZoneInfo("Europe/Moscow")


def tree(url):
    """Маленькое дерево: Здоровье › Еда › Белок, Дети › Школа › Математика, ориентир без дел."""
    food = mk(url, title="Еда", goal=True, directions=["Здоровье"])
    protein = mk(url, title="Ежедневно набираю белок", goal=True, why_id=food["id"], rhythm="ежедневно")
    school = mk(url, title="Учится в сильной школе", goal=True, directions=["Дети"])
    math = mk(url, title="Хорошо знает математику", goal=True, why_id=school["id"])
    mark = mk(url, title="Ориентир", goal=True, directions=["Здоровье"], no_steps=True)
    return {"food": food, "protein": protein, "school": school, "math": math, "mark": mark}


def journal(url, task_id):
    with db.session(url, "system", via="test") as c:
        return c.execute("SELECT actor, after FROM crm.history WHERE entity = 'tasks.krug' AND entity_id = %s ORDER BY id",
                         (str(task_id),)).fetchall()


def got(url, task_id):
    return next(t for t in store.sync(url, "sasha", 0)["tasks"] if t["id"] == task_id)


def test_why_auto_places_under_goal_as_service(kr):
    g = tree(kr)
    t = mk(kr, title="Купить протеин", why_auto=g["protein"]["num"])
    assert t["why_id"] == g["protein"]["id"] and t["dirs"][0] == "health"
    # Дело завёл владелец, а место выбрала модель: в журнале Круга — служба, учёба её догадку не возьмёт.
    assert [h["actor"] for h in journal(kr, t["id"])] == [place.ACTOR]
    # «#номер» строкой — то же самое; свои направления владельца — его, место — службы.
    t2 = mk(kr, title="Распечатать примеры", directions=["Дети"], why_auto=f"#{g['math']['num']}")
    assert t2["why_id"] == g["math"]["id"] and t2["directions"] == ["parenting"]
    assert [h["actor"] for h in journal(kr, t2["id"])] == ["sasha", place.ACTOR]


def test_why_auto_refused_quietly(kr):
    g = tree(kr)
    client = project(kr, "sasha", "Альфа")
    deal = ok(kr, "sasha", {"op": "deal.create", "data": {"project_id": str(client), "name": "Сделка"}})[0]["row"]
    personal = project(kr, "sasha", "Личное", kind="personal", sphere="home")
    closed = mk(kr, title="Старая цель", goal=True, directions=["Здоровье"])
    ok(kr, "sasha", {"op": "task.set", "id": closed["id"], "set": {"status": "done"}})
    cases = [
        mk(kr, title="Альфа: подписать счёт", project_id=str(client), why_auto=g["food"]["num"]),  # клиент висит на цели
        mk(kr, title="Сделка: созвон", project_id=str(client), deal_id=str(deal["id"]), why_auto=g["food"]["num"]),
        mk(kr, title="Под ориентир", why_auto=g["mark"]["num"]),          # под ориентиром дел не ставят
        mk(kr, title="Под закрытую", why_auto=closed["num"]),             # цель закрыта
        mk(kr, title="Нет такой", why_auto=999999),
        mk(kr, title="Не номер", why_auto="белок"),
        mk(kr, title="Новая цель", goal=True, why_auto=g["food"]["num"]),  # цель — не дело
        mk(kr, title="Сам назвал", why_id=g["math"]["id"], why_auto=g["food"]["num"]),  # слово владельца сильнее
    ]
    assert [t["why_id"] for t in cases[:-1]] == [None] * (len(cases) - 1)
    assert cases[-1]["why_id"] == g["math"]["id"]
    # Обычный проект (не клиент) места не отнимает.
    t = mk(kr, title="Купить протеин", project_id=str(personal), why_auto=g["protein"]["num"])
    assert t["why_id"] == g["protein"]["id"]


def test_why_auto_from_not_owner_is_ignored(kr):
    g = tree(kr)
    shared = project(kr, "sasha", "ЗФ", kind="internal")
    with db.session(kr, "system", via="test") as c:
        c.execute("INSERT INTO crm.project_access (project_id, user_id, role, granted_by) VALUES (%s, 'natasha', 'edit', 'sasha')",
                  (shared,))
    r = ops(kr, "natasha", {"op": "task.create", "task": {"title": "Наташа: сверка", "project_id": str(shared),
                                                           "why_auto": g["food"]["num"]}})[0]
    assert r["ok"], r  # Круг — только хозяину; но дело Наташи заводится, а не падает
    assert not journal(kr, r["task"]["id"])


def test_suggestion_from_service_carries_place(kr):
    g = tree(kr)
    today = db_today(kr).isoformat()
    r = store.apply_ops(kr, "sasha", [{"op": "suggestion.create", "suggestion": {
        "kind": "create", "source": "meeting", "source_ref": "https://m/1", "batch_ref": "meeting:1", "quote": "купить протеин",
        "payload": {"title": "Купить протеин", "auto": True, "meeting_at": today, "why_auto": g["protein"]["num"]}}}],
        "meetings", "svc:meetings")["results"][0]
    assert r["ok"] and r["task"]["why_id"] == g["protein"]["id"], r
    assert [h["actor"] for h in journal(kr, r["task"]["id"])] == ["svc:meetings"]


def test_block_lists_tree_rules_and_directions(kr):
    g = tree(kr)
    mk(kr, title="Ориентир-ветка", goal=True, directions=["Дети"], no_steps=True)
    rules = ("Ты находишь место дел.\n\nКак решать:\n- Покупка по зачем: добавки — Здоровье.\n"
             "- goal_num — номер цели.\n- directions — главное первым.\n- Уверен — sure: true.\n- Отвечай по каждому делу.")
    ok(kr, "sasha", {"op": "svod.set", "key": krug.PROMPT_KEY, "body": rules, "author": "test"})
    b = place.block(kr, "sasha")
    assert b.startswith("<дерево жизни>") and b.endswith("</дерево жизни>")
    assert "- Покупка по зачем: добавки — Здоровье." in b
    assert "goal_num" not in b and "sure" not in b and "Отвечай по" not in b and "Ты находишь" not in b
    assert f"Здоровье\n  #{g['food']['num']} Еда\n    #{g['protein']['num']} Ежедневно набираю белок — практика: ежедневно" in b
    assert f"Родительство\n  #{g['school']['num']} Учится в сильной школе\n    #{g['math']['num']} Хорошо знает математику" in b
    assert "Ориентир" not in b  # ориентир без подцелей дереву разбора не нужен
    assert "Комфортный быт — Дома хорошо." in b
    # Своих правок ещё нет — и разделов о них нет.
    assert "ПОПРАВЛЯЛ" not in b and "ВЫВОДЫ" not in b


def test_block_is_empty_without_krug(dela):
    assert place.block(dela, "sasha") == ""
    assert parse.with_tree("правила\n{TREE}\nСегодня {TODAY}", "") == "правила\n" + parse.NO_TREE + "\nСегодня {TODAY}"


def test_learns_fixes_answers_and_own(kr):
    g = tree(kr)
    moved = mk(kr, title="Купить протеин", why_auto=g["food"]["num"])     # модель: под «Еда»
    ok(kr, "sasha", {"op": "task.set", "id": moved["id"], "set": {"why_id": g["protein"]["id"]}})  # владелец уточнил
    kept = mk(kr, title="Купить витамин D", why_auto=g["food"]["num"])    # модель угадала — примером не становится
    mine = mk(kr, title="Распечатать примеры")
    ok(kr, "sasha", {"op": "task.set", "id": mine["id"], "set": {"why_id": g["math"]["id"]}})
    lamp = mk(kr, title="Поменять лампу")
    s = ok(kr, "sasha", {"op": "suggestion.create", "suggestion": {
        "kind": "update", "task_id": lamp["id"], "source": "krug", "batch_ref": "krug:t", "batch_title": "Место в дереве",
        "payload": {"directions": ["health"], "ask": "Лампа — Здоровье?"}}})[0]["suggestion"]
    ok(kr, "sasha", {"op": "suggestion.decide", "id": s["id"], "decision": "reject", "reason": "это быт, дом"})
    events = place.learned(kr, "sasha")
    kinds = {(e["kind"], e["task"]) for e in events}
    assert ("fix", moved["id"]) in kinds and ("own", mine["id"]) in kinds and ("answer", lamp["id"]) in kinds
    assert not any(e["task"] == kept["id"] for e in events)
    b = place.block(kr, "sasha")
    assert "«Купить протеин»: стояло в Здоровье › Еда — переставил в Здоровье › Еда › Ежедневно набираю белок" in b
    assert "«Поменять лампу»: спросили «Лампа — Здоровье?» — владелец ответил: «это быт, дом»" in b
    assert "КАК ОН СТАВИЛ САМ" in b and "«Распечатать примеры»: Родительство › Учится в сильной школе › Хорошо знает математику" in b
    assert "Купить витамин D" not in b


def test_refresh_writes_svod_once(kr):
    g = tree(kr)
    assert place.refresh(kr, force=True)
    row = store.view(kr, "sasha", "svod", key=place.PLACE_KEY)["items"][0]
    assert f"#{g['protein']['num']}" in row["body"] and row["author"] == "krug"
    assert not place.refresh(kr, force=True)  # ничего не поменялось — Свод не трогаем
    mk(kr, title="Сон", goal=True, directions=["Здоровье"])
    assert place.refresh(kr, force=True)
    assert "Сон" in store.view(kr, "sasha", "svod", key=place.PLACE_KEY)["items"][0]["body"]
    # Телефон получает его синком: Свод — в синке хозяина.
    assert any(x["key"] == place.PLACE_KEY for x in store.sync(kr, "sasha", 0)["svod"])


def test_parse_puts_tree_in_prompt_and_places(kr):
    g = tree(kr)
    seen = {}

    def fake(system, user_text):
        seen["system"] = system
        return {"tasks": [{"title": "Купить протеин", "notes": "", "project": "", "person": "", "ball": "mine", "due": "",
                           "estimate_min": 0, "money": "", "want": False, "labels": [], "goal": g["protein"]["num"]},
                          {"title": "Позвонить в банк", "notes": "", "project": "", "person": "", "ball": "mine", "due": "",
                           "estimate_min": 0, "money": "", "want": False, "labels": [], "goal": 0}], "notes": []}

    out = parse.run(kr, "sasha", "купить протеин и позвонить в банк", {}, "", None, ask_fn=fake)
    assert "<дерево жизни>" in seen["system"] and "{TREE}" not in seen["system"]
    assert seen["system"].index("</дерево жизни>") < seen["system"].index("Сегодня 20")
    a, b = out["tasks"]
    assert a["why_id"] == g["protein"]["id"] and b["why_id"] is None
    # Правила без {TREE} (тюнер телефона переписал их раньше) — блок всё равно встаёт перед датой.
    ok(kr, "sasha", {"op": "svod.set", "key": "prompt.raznoska", "body": "Разбери.\n{CATALOG}\n\nСегодня {TODAY}, сейчас {NOW}.",
                     "author": "tuner"})
    parse.run(kr, "sasha", "купить протеин", {}, "", None, ask_fn=fake)
    assert seen["system"].index("<дерево жизни>") < seen["system"].index("Сегодня 20")


def test_parse_for_not_owner_has_no_tree(kr):
    tree(kr)
    seen = {}

    def fake(system, user_text):
        seen["system"] = system
        return {"tasks": [], "notes": []}

    parse.run(kr, "natasha", "сверка", {}, "", None, ask_fn=fake)
    assert "<дерево жизни>" not in seen["system"] and parse.NO_TREE in seen["system"]


class FakeBatches:
    def __init__(self, answer):
        self.answer, self.sent, self.status = answer, [], "in_progress"

    def create(self, requests):
        self.sent.append(requests)
        return NS(id="msgbatch_t")

    def retrieve(self, bid):
        return NS(processing_status=self.status)

    def results(self, bid):
        msg = NS(stop_reason="end_turn", model="claude-opus-5-5", content=[NS(type="text", text=json.dumps(self.answer))],
                 usage=NS(input_tokens=5000, output_tokens=400, cache_creation_input_tokens=0, cache_read_input_tokens=0))
        return iter([NS(custom_id="krug-tune", result=NS(type="succeeded", message=msg))])


def test_tune_weekly_lessons(kr):
    g = tree(kr)
    b = FakeBatches({"lessons": ["Покупки для спорта — Здоровье › Еда, а не Быт: покупку ставит по зачем."],
                     "note": "первый вывод"})
    cl = NS(messages=NS(batches=b))
    sun = dt.datetime(2026, 10, 18, 21, 5, tzinfo=MSK)
    assert place.tune_slot(sun) == sun.replace(minute=0)
    assert place.tune_slot(sun.replace(hour=20)) == dt.datetime(2026, 10, 11, 21, 0, tzinfo=MSK)
    # Журнал пишет настоящее время базы — неделя теста от него: прошлое воскресенье и следующее.
    sun = place.tune_slot(dt.datetime.now(MSK))
    # Правок нет — ни одного запроса, неделя отмечена.
    assert place.tune(kr, "key", None, sun, cl) is None and b.sent == []
    moved = mk(kr, title="Купить протеин", why_auto=g["food"]["num"])
    ok(kr, "sasha", {"op": "task.set", "id": moved["id"], "set": {"why_id": g["protein"]["id"]}})
    # В ту же неделю второй раз не шлёт; через неделю — правка уходит на выводы.
    assert place.tune(kr, "key", None, sun, cl) is None and b.sent == []
    later = sun + dt.timedelta(days=7)
    assert place.tune(kr, "key", None, later, cl) == {"sent": True}
    req = b.sent[0][0]["params"]
    assert "стояло в Здоровье › Еда — переставил в" in req["messages"][0]["content"]
    assert req["output_config"]["effort"] == place.TUNE_EFFORT
    assert place.tune(kr, "key", None, later, cl) is None  # пачка в работе
    b.status = "ended"
    out = place.tune(kr, "key", None, later, cl)
    assert out["lessons"] == 1
    body = store.view(kr, "sasha", "svod", key=place.LESSONS_KEY)["items"][0]["body"]
    assert body.startswith("- Покупки для спорта")
    # Выводы — в блоке для разборов.
    assert "ВЫВОДЫ ИЗ ЕГО ПРАВОК" in place.block(kr, "sasha")


LONG_DIRS = [{"key": f"d{i}", "pos": i, "zone": "Я", "name": f"Направление {i}", "short": f"Н{i}",
              "formula": f"Формула направления {i}.", "includes": [f"входит пункт {i}.{k} с подробным пояснением" for k in range(6)],
              "excludes": [f"не сюда {i}"], "notes": f"ещё длинная заметка о направлении {i} " * 3,
              "examples": [f"пример дела {i}.{k} с пояснением, почему здесь" for k in range(6)]} for i in range(9)]


def test_block_is_short_but_krug_keeps_forty(kr, monkeypatch):
    """№67: блок «дерево жизни» едет в каждый разбор — в нём до 10 примеров (поправок до 6), до 10 выводов, направление
    одной строкой; блок вдвое короче прежнего; разборщик Круга по-прежнему получает свои 40 примеров."""
    from pravka_dela import krug_auto

    ok(kr, "sasha", {"op": "svod.set", "key": krug.DIRECTIONS_KEY, "value": LONG_DIRS, "author": "test"})
    goals = [mk(kr, title=f"Цель {i}", goal=True, directions=[f"d{i % 9}"]) for i in range(5)]
    for i in range(40):
        t = mk(kr, title=f"Дело номер {i} с длинным названием для примера")
        if i % 3 == 0:
            t = mk(kr, title=f"Поставлено автоматикой {i} с длинным названием", why_auto=goals[0]["num"])
        ok(kr, "sasha", {"op": "task.set", "id": t["id"], "set": {"why_id": goals[i % 5]["id"]}})
    ok(kr, "sasha", {"op": "svod.set", "key": place.LESSONS_KEY, "body": "\n".join(f"- вывод {i}" for i in range(20)),
                     "author": "test"})
    new = place.block(kr, "sasha")
    examples = [x for x in new.splitlines() if x.startswith("— «")]
    assert 0 < len(examples) <= place.BLOCK_EXAMPLES
    assert len([x for x in new.splitlines() if x.startswith("- вывод")]) == place.BLOCK_LESSONS
    assert "входит:" not in new and "примеры:" not in new and "Направление 3 — Формула направления 3. (не сюда: не сюда 3)" in new
    # Прежний блок — те же данные с прежними пределами и направлениями целиком.
    monkeypatch.setattr(place, "BLOCK_EXAMPLES", place.EXAMPLES)
    monkeypatch.setattr(place, "BLOCK_FIXES", place.FIXES)
    monkeypatch.setattr(place, "BLOCK_LESSONS", 100)
    monkeypatch.setattr(place, "dir_line", lambda d: "\n".join(place.dir_lines([d])))
    old = place.block(kr, "sasha")
    assert len(new) * 2 <= len(old), (len(new), len(old))
    fixes, own = krug_auto.examples(kr, "sasha", krug.directions_of(kr, "sasha"))
    assert 10 < len(fixes) + len(own) <= place.EXAMPLES  # у разборщика Круга — прежние до 40
