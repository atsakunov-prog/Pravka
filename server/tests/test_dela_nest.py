"""Дела внутри дела (15.10.2026): «Для этого», «до этого надо…» — новое дело внутри, «положи это в то дело» — вложить
существующее, порядок среди соседей (pos, dela_0024), подъём родителя, когда сделано последнее дело внутри, петли.

Владелец: «Пишу „до этого надо из расшифровки встречи набросать драфт“ в „Что поправить в этом деле“ — он добавляет в
комментарии вместо того, чтобы сделать новое дело»; «это не отдельная сущность, а обычные дела, вложенные в дело».
Claude подменён готовым ответом по схеме: проверяется всё вокруг вызова. Имена — выдуманные.
"""

from __future__ import annotations

import datetime as dt

from starlette.testclient import TestClient

from pravka_dela import api, ask, parse, store, tokens
from pravka_dela.config import Config
from test_dela_ask import _wait, item, mk
from test_dela_cards import _client, cd, empty_card, ops
from test_dela_schema import db_today, dela, project  # noqa: F401


def cr(title, **kw):
    """Новое дело в ответе Claude: всё пусто, кроме сказанного."""
    return {"title": title, "notes_add": "", "project": "", "person": "", "due": "", "ball": "", "now": "", "step": False, **kw}


def row(url, tid, user="sasha"):
    with parse.db.session(url, user, "t") as c:
        return store.jsonable(c.execute("SELECT * FROM tasks.v_tasks WHERE id = %s", (tid,)).fetchone())


def no_parse(*_):
    raise AssertionError("из карточки дела разбор надиктовки не зовём")


def test_step_due_rule(dela):
    """Срок дела внутри, если не назвали: родитель в «Сейчас», просрочен или на сегодня — сегодня; на будущую дату — тот
    же; без срока — без срока. То же правило — веб (stepDue) и телефон (DelaSteps.due)."""
    today = db_today(dela)
    later = today + dt.timedelta(days=5)
    assert store.step_due({"due_date": None, "focus_on": today}, today) == today
    assert store.step_due({"due_date": today - dt.timedelta(days=3)}, today) == today
    assert store.step_due({"due_date": today.isoformat()}, today) == today
    assert store.step_due({"due_date": later}, today) == later
    assert store.step_due({}, today) is None
    assert store.step_due({"focus_on": today - dt.timedelta(days=1)}, today) is None  # вчерашнее «Сейчас» — не сегодня
    near = store.step_of({"id": "x", "owner_id": "sasha", "project_id": "p", "deal_id": "d", "person_id": "h", "due_date": later},
                         "sasha", today)
    assert near == {"project_id": "p", "deal_id": "d", "person_id": "h", "why_id": "x", "due_date": later.isoformat()}
    # У цели нет проекта, сделки и человека; у чужого дела «Зачем» не ставится (правило базы — только своё).
    assert store.step_of({"id": "g", "owner_id": "sasha", "goal": True, "project_id": "p"}, "sasha", today) == {"why_id": "g"}
    assert "why_id" not in store.step_of({"id": "x", "owner_id": "sasha"}, "natasha", today)


def test_before_this_goes_inside_not_to_notes(dela):
    """«Что поправить»: «до этого надо X» — create step:true → дело внутри: «Зачем» — это дело, проект, сделка, человек и
    срок — от него; само дело не меняется; reply о новом деле пишет сервер. Обычное create из карточки — по-прежнему
    рядом (к той же цели)."""
    from pravka_dela import krug

    p, org, bank, olga, ivan, deal = _client(dela)
    today = db_today(dela)
    ops(dela, "sasha", [{"op": "svod.set", "key": krug.DIRECTIONS_KEY, "author": "test",
                         "value": [{"key": "work", "pos": 3, "name": "Дело", "short": "Дело"}]}])
    goal = mk(dela, "Сделки закрываются", goal=True, directions=["work"])
    t = mk(dela, "Подготовить простой термшит и отправить Ивану", project_id=str(p), deal_id=str(deal), person_id=str(ivan),
           due_date=today.isoformat(), why_id=goal["id"])
    seen = {}

    def fake(system, user_text):
        seen["system"], seen["user"] = system, user_text
        return empty_card(create=[cr("Набросать драфт из расшифровки встречи", step=True), cr("Отдельное рядом")],
                          reply="Ещё завёл рядом отдельное.", _usage=None)

    out = ask.run(dela, "sasha", "до этого надо из расшифровки встречи набросать драфт", {"card": "task", "open": t["id"]},
                  "", None, ask_fn=fake, parse_fn=no_parse)
    assert "step — true только в КАРТОЧКЕ ДЕЛА" in seen["system"] and "НЕ notes_add" in seen["system"]
    assert not out["errors"], out["errors"]
    inside, near = out["tasks"]
    assert (inside["why_id"], inside["project_id"], inside["deal_id"], inside["person_id"], inside["due_date"]) == (
        t["id"], str(p), str(deal), str(ivan), today.isoformat())
    assert inside["focus_on"] is None  # «Сейчас» — только если сказал
    assert out["steps"] == [inside["id"]]
    # Рядом — к той же цели, без срока родителя и без человека.
    assert (near["why_id"], near["project_id"], near["due_date"], near["person_id"]) == (goal["id"], str(p), None, None)
    assert out["reply"] == ("Завёл для «Подготовить простой термшит и отправить Ивану»: «Набросать драфт из расшифровки встречи», "
                            "на сегодня. Ещё завёл рядом отдельное.")
    me = row(dela, t["id"])
    assert me["notes"] is None and me["steps_open"] == 1  # само дело не тронуто, внутри — одно открытое


def test_named_fields_win_over_parent(dela):
    """Названное Сашей сильнее родительского: срок, другой проект (без сделки родителя), «-» у человека — без человека."""
    p, org, bank, olga, ivan, deal = _client(dela)
    other = project(dela, "sasha", "Ромашка")
    today = db_today(dela)
    later = (today + dt.timedelta(days=9)).isoformat()
    t = mk(dela, "Термшит", project_id=str(p), deal_id=str(deal), person_id=str(ivan), due_date=later)
    friday = (today + dt.timedelta(days=2)).isoformat()

    def fake(system, user_text):
        return empty_card(create=[cr("Сверить цифры", step=True, due=friday), cr("Спросить в Ромашке", step=True, project="Ромашка"),
                                  cr("Самому прикинуть", step=True, person="-")], _usage=None)

    out = ask.run(dela, "sasha", "x", {"card": "task", "open": t["id"]}, "", None, ask_fn=fake)
    a, b, c = out["tasks"]
    assert (a["due_date"], a["deal_id"]) == (friday, str(deal))
    assert (b["project_id"], b["deal_id"], b["due_date"]) == (str(other), None, later)  # будущий срок родителя — тот же
    assert (c["person_id"], c["why_id"]) == (None, t["id"])
    assert "«Сверить цифры», на " + friday[8:10] + "." + friday[5:7] in out["reply"]


def test_for_this_mode_only_makes_inside(dela):
    """«Для этого» (scope.step): всё названное — дела внутри (даже без step в ответе), само дело не меняется; Claude
    ничего не завёл — дело внутри названием, как сказано. Без ключа Claude — то же без вызова и без трат."""
    t = mk(dela, "Отчёт для банка")  # без срока — и внутри без срока
    seen = {}

    def fake(system, user_text):
        seen["user"] = user_text
        return empty_card(changes=[item(t["num"], notes_add="не должно лечь", due="2099-01-01")],
                          create=[cr("Выгрузить ОСВ"), cr("Наташа: проверить сверку", person="-")],
                          card=[cd("note", text="не должно лечь")], _usage=None)

    out = ask.run(dela, "sasha", "выгрузить ОСВ и Наташе проверить сверку", {"card": "task", "open": t["id"], "step": True},
                  "", None, ask_fn=fake)
    assert seen["user"].rstrip().endswith("ДЛЯ ЭТОГО — Саша называет, что сделать для этого дела: выгрузить ОСВ и Наташе проверить сверку")
    assert [x["title"] for x in out["tasks"]] == ["Выгрузить ОСВ", "Наташа: проверить сверку"]
    assert all(x["why_id"] == t["id"] and x["due_date"] is None for x in out["tasks"])
    assert out["changed"] == [] and out["crm"] == []
    parent = row(dela, t["id"])
    assert parent["notes"] is None and parent["due_date"] is None
    assert out["reply"] == "Завёл для «Отчёт для банка»: «Выгрузить ОСВ», без срока; «Наташа: проверить сверку», без срока."

    # Claude ничего не завёл — всё равно дело внутри, названием, как сказано.
    out = ask.run(dela, "sasha", "  созвониться   с юристом ", {"card": "task", "open": t["id"], "step": True}, "", None,
                  ask_fn=lambda s, u: empty_card(reply="Не понял.", _usage=None))
    assert [x["title"] for x in out["tasks"]] == ["созвониться с юристом"] and out["tasks"][0]["why_id"] == t["id"]

    # Без ключа Claude: без вызова, без трат.
    with parse.db.session(dela, "system", "t") as c:
        before = c.execute("SELECT value FROM crm.state WHERE key = 'llm_cost'").fetchone()
    today = db_today(dela).isoformat()
    store.apply_ops(dela, "sasha", [{"op": "task.set", "id": t["id"], "set": {"focus_on": today}}], "web")
    out = ask.run(dela, "sasha", "позвонить в банк", {"card": "task", "open": t["id"], "step": True}, "", None)
    made = out["tasks"][0]
    assert (made["title"], made["why_id"], made["due_date"]) == ("позвонить в банк", t["id"], today)  # родитель в «Сейчас»
    assert out["reply"] == "Завёл для «Отчёт для банка»: «позвонить в банк», на сегодня." and out["usage"] is None
    with parse.db.session(dela, "system", "t") as c:
        assert c.execute("SELECT value FROM crm.state WHERE key = 'llm_cost'").fetchone() == before
    # Без ключа и не «Для этого» — как раньше, отказ словами.
    try:
        ask.run(dela, "sasha", "перенеси", {"card": "task", "open": t["id"]}, "", None)
        raise AssertionError("без ключа правка словами не работает")
    except ask.AskError as e:
        assert "нет ключа" in str(e)


def test_api_step_scope_and_no_key(dela, monkeypatch):
    """API: step доходит до ask.run; без ключа — только «Для этого» в карточке дела, остальное — 422."""
    t = mk(dela, "Сверка с банком")
    h = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'device', 'телефон-шаги')}"}
    with TestClient(api.build(Config(db_url=dela))) as client:
        r = client.post("/api/ask", headers=h, json={"text": "перенеси", "scope": {"card": "task", "open": t["id"]}})
        assert r.status_code == 422 and "нет ключа" in r.json()["error"]
        jid = client.post("/api/ask", headers=h, json={"text": "выгрузить выписку", "scope": {
            "card": "task", "open": t["id"], "step": True}}).json()["job"]
        done = _wait(client, h, jid)
        assert done["status"] == "done" and [x["title"] for x in done["tasks"]] == ["выгрузить выписку"]
        assert done["tasks"][0]["why_id"] == t["id"] and done["reply"].startswith("Завёл для «Сверка с банком»")
    seen = {}

    def fake_run(url, user, text, scope, key, proxy, via="web", actor=None, **_):
        seen["scope"] = scope
        return {"route": "edit", "reply": "ок", "changed": [], "tasks": [], "decided": [], "crm": [], "notes": [], "errors": []}

    monkeypatch.setattr(ask, "run", fake_run)
    with TestClient(api.build(Config(db_url=dela, anthropic_key="sk-test"))) as client:
        jid = client.post("/api/ask", headers=h, json={"text": "x", "scope": {"card": "task", "open": t["id"], "step": 1}}).json()["job"]
        _wait(client, h, jid)
    assert seen["scope"]["step"] is True and seen["scope"]["card"] == "task"


def test_put_existing_inside_and_take_out(dela):
    """«Положи это в дело про термшит», «это часть того дела» — task.set why_id (into); -1 — вынуть. В карточке дела
    Claude видит остальные свои открытые дела — только чтобы найти, куда вложить; править их нельзя. «Вернуть» —
    как было."""
    term = mk(dela, "Подготовить термшит")
    draft = mk(dela, "Набросать драфт")
    third = mk(dela, "Позвонить юристу")
    seen = {}

    def fake(system, user_text):
        seen["user"] = user_text
        return empty_card(changes=[item(draft["num"], into=term["num"]), item(third["num"], into=term["num"])], _usage=None)

    out = ask.run(dela, "sasha", "положи это в дело про термшит", {"card": "task", "open": draft["id"]}, "", None, ask_fn=fake)
    assert f"#{term['num']} Подготовить термшит" in seen["user"].split("ДРУГИЕ МОИ ОТКРЫТЫЕ ДЕЛА")[1]
    assert row(dela, draft["id"])["why_id"] == term["id"] and row(dela, third["id"])["why_id"] is None  # чужое — не правим
    assert any("одного из названных дел нет" in e for e in out["errors"])
    assert out["changed"][0]["before"] == {"why_id": None} and out["changed"][0]["after"] == {"why_id": term["id"]}

    # Со страницы: «эти два — часть термшита».
    out = ask.run(dela, "sasha", "это часть того дела", {"task_ids": [third["id"], term["id"]]}, "", None,
                  ask_fn=lambda s, u: empty_card(changes=[item(third["num"], into=term["num"])], _usage=None))
    assert row(dela, third["id"])["why_id"] == term["id"] and row(dela, term["id"])["steps_open"] == 2
    # Вынуть.
    ask.run(dela, "sasha", "вынь его оттуда", {"card": "task", "open": third["id"]}, "", None,
            ask_fn=lambda s, u: empty_card(changes=[item(third["num"], into=-1)], _usage=None))
    assert row(dela, third["id"])["why_id"] is None
    # В самого себя — нет.
    out = ask.run(dela, "sasha", "x", {"card": "task", "open": term["id"]}, "", None,
                  ask_fn=lambda s, u: empty_card(changes=[item(term["num"], into=term["num"])], _usage=None))
    assert any("в самого себя" in e for e in out["errors"])


def test_any_depth_and_no_loops(dela):
    """Вложенность любой глубины; петлю (дело внутри своего же вложенного) не пускает база — отказ словами."""
    a, b, c = mk(dela, "Сделка"), mk(dela, "Термшит"), mk(dela, "Драфт")
    assert all(r["ok"] for r in ops(dela, "sasha", [{"op": "task.set", "id": b["id"], "set": {"why_id": a["id"]}},
                                                     {"op": "task.set", "id": c["id"], "set": {"why_id": b["id"]}}]))
    assert row(dela, c["id"])["why_id"] == b["id"] and row(dela, b["id"])["why_id"] == a["id"]
    bad = ops(dela, "sasha", [{"op": "task.set", "id": a["id"], "set": {"why_id": c["id"]}}])[0]
    assert not bad["ok"] and "по кругу" in bad["error"]
    bad = ops(dela, "sasha", [{"op": "task.set", "id": a["id"], "set": {"why_id": a["id"]}}])[0]
    assert not bad["ok"]
    # Claude так же: отказ сервера — в ошибках ответа, без молчания.
    out = ask.run(dela, "sasha", "положи сделку в драфт", {"card": "task", "open": a["id"]}, "", None,
                  ask_fn=lambda s, u: empty_card(changes=[item(a["num"], into=c["num"])], _usage=None))
    assert any("по кругу" in e for e in out["errors"]) and row(dela, a["id"])["why_id"] is None


def test_order_among_siblings(dela):
    """pos — место среди соседей (dela_0024): общее поле дела, в синке и журнале как любое; дерево Круга — по нему."""
    from pravka_dela import krug

    parent = mk(dela, "Термшит")
    x = mk(dela, "Второе", why_id=parent["id"])
    y = mk(dela, "Первое", why_id=parent["id"])
    r = ops(dela, "sasha", [{"op": "task.set", "id": y["id"], "set": {"pos": 1}}, {"op": "task.set", "id": x["id"], "set": {"pos": 2}}])
    assert all(z["ok"] for z in r) and r[0]["task"]["pos"] == 1
    synced = {t["id"]: t for t in store.sync(dela, "sasha", 0)["tasks"]}
    assert (synced[y["id"]]["pos"], synced[x["id"]]["pos"]) == (1, 2)
    with parse.db.session(dela, "sasha", "t") as c:
        tree = krug.tree(c, "sasha", db_today(dela))
    # Без направления — в none: родитель один раз, внутри него — по месту, без повторов на верхнем уровне.
    assert [n["title"] for n in tree["none"]] == ["Термшит"]
    assert [c["title"] for c in tree["none"][0]["children"]] == ["Первое", "Второе"]
    # Дело без «Зачем» у команды — pos видно, «Зачем» — нет (Круг личный, правило 19).
    with parse.db.session(dela, "system", "t") as c:
        assert c.execute("SELECT count(*) AS n FROM crm.history WHERE entity = 'tasks.tasks' AND after ? 'pos'").fetchone()["n"] >= 2


def test_last_inside_done_raises_parent(dela):
    """Сделано последнее открытое дело внутри — родитель НЕ закрывается, а поднимается: без срока или позже — на
    сегодня (raised в ответе операции); просроченный — как был; цель и закрытое не трогаем; отмена — не «сделано»."""
    today = db_today(dela)
    later = (today + dt.timedelta(days=7)).isoformat()
    parent = mk(dela, "Отправить термшит Ивану", due_date=later)
    a = mk(dela, "Драфт", why_id=parent["id"])
    b = mk(dela, "Сверить цифры", why_id=parent["id"])
    first = ops(dela, "sasha", [{"op": "task.done", "id": a["id"]}])[0]
    assert first["ok"] and "raised" not in first and row(dela, parent["id"])["due_date"] == later
    last = ops(dela, "sasha", [{"op": "task.done", "id": b["id"]}])[0]
    assert last["raised"] == {"id": parent["id"], "num": parent["num"], "title": "Отправить термшит Ивану", "moved": True,
                              "due_date": today.isoformat()}
    p = row(dela, parent["id"])
    assert p["status"] == "open" and p["due_date"] == today.isoformat()  # не закрыт — поднялся
    # Просроченный родитель — срок не трогаем, но «всё внутри сделано» всё равно.
    old = mk(dela, "Старое", due_date=(today - dt.timedelta(days=2)).isoformat())
    c = mk(dela, "Внутри старого", why_id=old["id"])
    r = ops(dela, "sasha", [{"op": "task.done", "id": c["id"]}])[0]
    assert r["raised"]["moved"] is False and row(dela, old["id"])["due_date"] == (today - dt.timedelta(days=2)).isoformat()
    # Отмена последнего — не «сделано»: не поднимаем.
    q = mk(dela, "Без срока")
    d = mk(dela, "Отменю", why_id=q["id"])
    assert "raised" not in ops(dela, "sasha", [{"op": "task.cancel", "id": d["id"]}])[0] and row(dela, q["id"])["due_date"] is None
    # Цель — живёт в дереве: не поднимается.
    g = mk(dela, "Цель", goal=True)
    e = mk(dela, "Шаг к цели", why_id=g["id"])
    assert "raised" not in ops(dela, "sasha", [{"op": "task.done", "id": e["id"]}])[0]
    # Claude закрыл последнее — reply говорит, что дальше.
    z = mk(dela, "Подписать договор")
    y = mk(dela, "Согласовать правки", why_id=z["id"])
    out = ask.run(dela, "sasha", "правки согласовал", {"task_ids": [y["id"]]}, "", None,
                  ask_fn=lambda s, u: empty_card(changes=[item(y["num"], status="done")], reply="Закрыл.", _usage=None))
    assert out["reply"] == "Всё внутри сделано — теперь: «Подписать договор», на сегодня. Закрыл."
    assert out["raised"][0]["title"] == "Подписать договор"


def test_team_sees_inside_flat(dela):
    """Видимость не меняется: «Зачем» видит только хозяин дела. Наташа в общем проекте видит дело внутри обычным делом
    (без why_id); её «Для этого» у дела Саши заводит дело в его проекте, но вложить в чужое нельзя — reply так и говорит."""
    p = project(dela, "sasha", "Ромашка")
    with parse.db.session(dela, "system", "t") as c:
        c.execute("INSERT INTO crm.project_access (project_id, user_id, role, granted_by) VALUES (%s, 'natasha', 'edit', 'sasha')", (p,))
    parent = mk(dela, "Термшит Ромашке", project_id=str(p))
    inside = mk(dela, "Драфт термшита", project_id=str(p), why_id=parent["id"])
    nat = row(dela, inside["id"], user="natasha")
    assert nat is not None and nat["why_id"] is None and row(dela, inside["id"])["why_id"] == parent["id"]
    out = ask.run(dela, "natasha", "сверить цифры", {"card": "task", "open": parent["id"], "step": True}, "", None)
    made = out["tasks"][0]
    assert (made["owner_id"], made["project_id"], made["why_id"]) == ("natasha", str(p), None)
    assert out["reply"].endswith("отдельно: вложить в «Термшит Ромашке» может только его хозяин.")


def test_web_uses_same_rules():
    """Веб — тем же правилом, что сервер и телефон: «Для этого» у строки и в карточке шлёт scope.step, срок по умолчанию —
    stepDue (как store.step_due), строки списков идут одной точкой taskRows (дело внутри — перед родителем, «Деревом» —
    под ним), «сначала: …» — первое по месту (pos)."""
    from pathlib import Path

    js = (Path(store.__file__).parent / "static" / "app.js").read_text(encoding="utf-8")

    def fn(head: str) -> str:
        body = js[js.index(head):]
        return body[:body.index("\n}\n")]

    assert "card: 'task', open: t.id, step: true" in fn("function stepPop(")
    assert "...(card && card.step ? { step: true } : {})" in js
    due = js[js.index("const stepDue"):js.index("\n", js.index("const stepDue"))]
    assert "p.focus_on === S.today" in due and "p.due_date <= S.today) ? S.today : p.due_date || null" in due
    assert "taskRows(items, by, opts.chips)" in fn("function groupBox(") and "stepsFirst(items)" in fn("function taskRows(")
    assert "insideOf(t.id)" in fn("function stepChips(") and ".sort(byPos)" in js[js.index("const insideOf"):js.index("const stepDue")]
    # «Шаг» — не имя сущности (владелец 15.10.2026): ни в кнопках, ни в подписях новых мест.
    for name in ("function stepChips(", "function stepPop(", "function insideBox(", "function forLine(", "function nestPop("):
        assert "шаг" not in fn(name).lower().replace("шага, шагов", ""), name
