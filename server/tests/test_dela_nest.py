"""Дела внутри дела (15.10.2026): «Для этого», «до этого надо…» — новое дело внутри, «положи это в то дело» — вложить
существующее, порядок среди соседей (pos, dela_0024), подъём родителя, когда сделано последнее дело внутри, петли.

Владелец: «Пишу „до этого надо из расшифровки встречи набросать драфт“ в „Что поправить в этом деле“ — он добавляет в
комментарии вместо того, чтобы сделать новое дело»; «это не отдельная сущность, а обычные дела, вложенные в дело».
Claude подменён готовым ответом по схеме: проверяется всё вокруг вызова. Имена — выдуманные.
"""

from __future__ import annotations

import datetime as dt

from starlette.testclient import TestClient

import psycopg

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
    assert "step — true, если открыто дело" in seen["system"] and "НЕ notes_add" in seen["system"]
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
    assert "taskRows(items, by, opts.chips, opts.rowOpt)" in fn("function groupBox(") and "stepsFirst(items)" in fn("function taskRows(")
    # Хвост 3 (15.10.2026): соседи, чьего родителя в списке нет, — подряд и по месту; уровни группировки (кроме «время»)
    # кладут дело внутри в ветку его верхнего предка из этого же списка — как «Деревом», только плоско.
    sf = fn("function stepsFirst(")
    assert "lone" in sf and "[...sibs].sort(byPos)" in sf
    ns = fn("function nested(")
    assert "const top = lv === 'date' ? null : topIn(items);" in ns and "levelKey(top ? top(t) : t, lv)" in ns
    # Главная «Сейчас»: дела внутри дел из «Сейчас» — над родителем отступом, в «Сегодня» ниже не повторяются.
    rn = fn("function renderNow(")
    assert "innerOf(now)" in rn and "...depth.keys()" in rn and "rowOpt" in rn
    # «Зачем» — только цель дерева (у дела внутри — своя сделка или проект, иначе «Зачем» родителя); выбор целей —
    # только цели; движение Круга — без «родителей»; «Моё» — шаги к целям (krug.toward_goal).
    zo = fn("function zachemOf(")
    assert "if (w && w.goal) return { kind: 'goal'" in zo and "return zachemOf(w, seen)" in zo
    assert "&& x.goal)" in fn("function goalPop(") and "stepsOf(x.id).length" not in fn("function zachemPop(")
    assert "!parentOf(t)" in fn("function zachemPop(")  # выбор сделки не вынимает дело из родителя
    assert "parents" not in fn("function movement(") and "t.goal ? 3 : 1" in fn("function movement(")
    assert "towardGoal(t)" in fn("function mineTasks(")
    # «Вернуть» после «Сделано» — срок родителя возвращает сервер (fell), веб говорит это словами.
    assert "fellWords(res)" in fn("async function toggleDone(")
    assert "insideOf(t.id)" in fn("function stepChips(") and ".sort(byPos)" in js[js.index("const insideOf"):js.index("const stepDue")]
    # «Шаг» — не имя сущности (владелец 15.10.2026): ни в кнопках, ни в подписях новых мест.
    for name in ("function stepChips(", "function stepPop(", "function insideBox(", "function forLine(", "function nestPop("):
        assert "шаг" not in fn(name).lower().replace("шага, шагов", ""), name


# ── Найденное при проверке пакета (15.10.2026, ночь) ─────────────────────


def test_reopen_brings_parent_due_back(dela):
    """«Вернуть» после «Сделано» последнего дела внутри возвращает и срок родителя (tasks.nest_fall): тост веба,
    повторный тап телефона, «Вернуть» у «Закрыто само» — task.reopen, «Вернуть» реплики Claude — status open; одно
    место на сервере. Срок родителя правили между ними — «Вернуть» его не трогает (память подъёма гаснет)."""
    today = db_today(dela)
    later = (today + dt.timedelta(days=5)).isoformat()
    p = mk(dela, "Термшит Альфе", due_date=later)
    a = mk(dela, "Драфт", why_id=p["id"])
    up = ops(dela, "sasha", [{"op": "task.done", "id": a["id"]}])[0]
    assert up["raised"]["moved"] and row(dela, p["id"])["due_date"] == today.isoformat()
    back = ops(dela, "sasha", [{"op": "task.reopen", "id": a["id"]}])[0]
    assert back["fell"] == {"id": p["id"], "num": p["num"], "title": "Термшит Альфе", "due_date": later}
    assert row(dela, p["id"])["due_date"] == later
    # Второй раз — снова поднят (moved, а не «уже сегодня»).
    assert ops(dela, "sasha", [{"op": "task.done", "id": a["id"]}])[0]["raised"]["moved"] is True
    # Срок родителя перенесли руками — «Вернуть» его не трогает.
    fri = (today + dt.timedelta(days=2)).isoformat()
    ops(dela, "sasha", [{"op": "task.set", "id": p["id"], "set": {"due_date": fri}}])
    r = ops(dela, "sasha", [{"op": "task.reopen", "id": a["id"]}])[0]
    assert r["ok"] and "fell" not in r and row(dela, p["id"])["due_date"] == fri
    # Был без срока — «Вернуть» снова без срока; реплика Claude «верни» — тем же правилом и словами в ответе.
    q = mk(dela, "Отчёт банку")
    b = mk(dela, "Выписки", why_id=q["id"])
    ops(dela, "sasha", [{"op": "task.done", "id": b["id"]}])
    assert row(dela, q["id"])["due_date"] == today.isoformat()
    out = ask.run(dela, "sasha", "выписки ещё не готовы", {"task_ids": [b["id"]]}, "", None,
                  ask_fn=lambda s, u: empty_card(changes=[item(b["num"], status="open")], reply="Вернул.", _usage=None))
    assert row(dela, q["id"])["due_date"] is None and out["fell"][0]["title"] == "Отчёт банку"
    assert out["reply"] == "Срок «Отчёт банку» — снова без срока. Вернул."
    # Просроченный родитель не поднимался (moved false) — «Вернуть» ему нечего возвращать.
    old = mk(dela, "Старое", due_date=(today - dt.timedelta(days=2)).isoformat())
    c = mk(dela, "Внутри старого", why_id=old["id"])
    ops(dela, "sasha", [{"op": "task.done", "id": c["id"]}])
    assert "fell" not in ops(dela, "sasha", [{"op": "task.reopen", "id": c["id"]}])[0]
    assert row(dela, old["id"])["due_date"] == (today - dt.timedelta(days=2)).isoformat()


def test_team_member_closing_last_inside_raises_owner_parent(dela):
    """Последнее дело внутри (дело Саши в общем проекте «Кто: действие») закрыла Наташа — родитель Саши поднимается:
    подъём делает база мимо RLS Круга. Ей об этом не говорим (raised и fell — только хозяину родителя), why_id ей
    по-прежнему не виден. Родитель в личном проекте Саши, которого Наташа не видит вовсе, — поднимается тоже."""
    p = project(dela, "sasha", "Ромашка")
    with parse.db.session(dela, "system", "t") as c:
        c.execute("INSERT INTO crm.project_access (project_id, user_id, role, granted_by) VALUES (%s, 'natasha', 'edit', 'sasha')", (p,))
    today = db_today(dela)
    later = (today + dt.timedelta(days=6)).isoformat()
    parent = mk(dela, "Ромашка: акт сверки за сентябрь", project_id=str(p), due_date=later)
    kid = mk(dela, "Наташа: запросить акт у бухгалтерии", project_id=str(p), why_id=parent["id"])
    r = ops(dela, "natasha", [{"op": "task.done", "id": kid["id"]}])[0]
    assert r["ok"] and "raised" not in r
    assert row(dela, parent["id"])["due_date"] == today.isoformat() and row(dela, parent["id"])["status"] == "open"
    assert row(dela, kid["id"], user="natasha")["why_id"] is None
    r = ops(dela, "natasha", [{"op": "task.reopen", "id": kid["id"]}])[0]
    assert r["ok"] and "fell" not in r and row(dela, parent["id"])["due_date"] == later
    # Журнал родителя пишет ту, кто закрыла и открыла (подъём — следствие её «Сделано»).
    with parse.db.session(dela, "system", "t") as c:
        who = c.execute("SELECT actor FROM crm.history WHERE entity = 'tasks.tasks' AND entity_id = %s AND after ? 'due_date' "
                        "ORDER BY id", (parent["id"],)).fetchall()
    assert [h["actor"] for h in who][-2:] == ["natasha", "natasha"]
    # Родитель — в личном проекте Саши (Наташе не виден), дело внутри — в общем.
    mine = project(dela, "sasha", "Личное", kind="personal", sphere="home")
    hidden = mk(dela, "Подготовиться к сверке", project_id=str(mine))
    k2 = mk(dela, "Наташа: выгрузить реестр", project_id=str(p), why_id=hidden["id"])
    assert row(dela, hidden["id"], user="natasha") is None
    assert "raised" not in ops(dela, "natasha", [{"op": "task.done", "id": k2["id"]}])[0]
    assert row(dela, hidden["id"])["due_date"] == today.isoformat()


def test_pos_resets_when_nested_elsewhere_and_is_from_one(dela):
    """Сменил «Зачем» (вложил в другое дело, вынул), места не назвал — место снимается: дело встаёт после
    занумерованных новых соседей, а не первым (и «сначала: …» у нового родителя — не оно). Место — с единицы: 0, минус и
    не число — отказ словами; CHECK в базе — страховка."""
    from pravka_dela import krug

    q1, q2 = mk(dela, "Оценка бизнеса"), mk(dela, "Тизер для покупателей")
    y1 = mk(dela, "Запросить отчётность", why_id=q1["id"], pos=2)
    mk(dela, "Собрать мультипликаторы", why_id=q1["id"], pos=1)
    z1 = mk(dela, "Набросать структуру", why_id=q2["id"], pos=1)
    z2 = mk(dela, "Согласовать с Ольгой", why_id=q2["id"], pos=2)
    r = ops(dela, "sasha", [{"op": "task.set", "id": y1["id"], "set": {"why_id": q2["id"]}}])[0]
    assert r["ok"] and r["task"]["pos"] is None
    with parse.db.session(dela, "sasha", "t") as c:
        tree = krug.tree(c, "sasha", db_today(dela))
    node = next(n for n in tree["none"] if n["title"] == "Тизер для покупателей")
    assert [x["title"] for x in node["children"]] == [z1["title"], z2["title"], y1["title"]]
    # Вынуть — место тоже уходит; названное в той же операции — остаётся.
    assert ops(dela, "sasha", [{"op": "task.set", "id": z1["id"], "set": {"why_id": None}}])[0]["task"]["pos"] is None
    r = ops(dela, "sasha", [{"op": "task.set", "id": z2["id"], "set": {"why_id": q1["id"], "pos": 3}}])[0]
    assert r["task"]["pos"] == 3
    # Тот же «Зачем» (номером) — место не трогаем.
    assert ops(dela, "sasha", [{"op": "task.set", "id": z2["id"], "set": {"why_id": str(q1["num"])}}])[0]["task"]["pos"] == 3
    for bad in (0, -1, "первым", True):
        r = ops(dela, "sasha", [{"op": "task.set", "id": z2["id"], "set": {"pos": bad}}])[0]
        assert not r["ok"] and "целое число от 1" in r["error"], (bad, r)
    assert not ops(dela, "sasha", [{"op": "task.create", "task": {"title": "x", "pos": 0}}])[0]["ok"]
    assert ops(dela, "sasha", [{"op": "task.set", "id": z2["id"], "set": {"pos": "2"}}])[0]["task"]["pos"] == 2
    with parse.db.session(dela, "system", "t") as c:
        try:
            with c.transaction():
                c.execute("UPDATE tasks.tasks SET pos = 0 WHERE id = %s", (z2["id"],))
            raise AssertionError("CHECK pos > 0 не сработал")
        except psycopg.errors.CheckViolation:
            pass


def test_nested_elsewhere_goes_last_among_unnumbered(dela):
    """Сквозной тест, п. 5б: старое дело вложили к более новым соседям без места — пустое место поставило бы его по
    времени заведения первым («сначала: …» у родителя — оно). Сервер нумерует соседей в нынешнем порядке, вложенное —
    последним; когда оно и так последнее (новее соседей или они занумерованы), соседей не трогает."""
    old = mk(dela, "Запросить отчётность")  # заведено раньше всех
    q = mk(dela, "Тизер для покупателей")
    a, b = mk(dela, "Набросать структуру", why_id=q["id"]), mk(dela, "Согласовать с Ольгой", why_id=q["id"])
    r = ops(dela, "sasha", [{"op": "task.set", "id": old["id"], "set": {"why_id": q["id"]}}])[0]
    assert r["ok"] and r["task"]["pos"] == 3
    assert [row(dela, x["id"])["pos"] for x in (a, b)] == [1, 2]
    with parse.db.session(dela, "sasha", "t") as c:
        first = c.execute("SELECT title FROM tasks.v_tasks WHERE why_id = %s AND status = 'open' "
                          "ORDER BY pos NULLS LAST, created_at, num LIMIT 1", (q["id"],)).fetchone()["title"]
    assert first == a["title"]
    # Новое дело, вложенное к старым незанумерованным, и так последнее — соседей не нумеруем.
    p = mk(dela, "Отчёт банку")
    k1, k2 = mk(dela, "Выписки", why_id=p["id"]), mk(dela, "Сверка")
    r = ops(dela, "sasha", [{"op": "task.set", "id": k2["id"], "set": {"why_id": p["id"]}}])[0]
    assert r["task"]["pos"] is None and row(dela, k1["id"])["pos"] is None


def test_tree_orders_by_pos_only_inside_plain_tasks(dela):
    """Дерево Круга учитывает место только у дел внутри обычного дела, как веб и телефон: под целью старое место
    дела не поднимает его выше соседей."""
    from pravka_dela import krug

    g = mk(dela, "Хорошо знает математику", goal=True)
    a = mk(dela, "Распечатать примеры", why_id=g["id"])
    b = mk(dela, "Купить тетрадь", why_id=g["id"], pos=1)
    with parse.db.session(dela, "sasha", "t") as c:
        tree = krug.tree(c, "sasha", db_today(dela))
    node = next(n for n in tree["none"] if n["title"] == "Хорошо знает математику")
    assert [x["title"] for x in node["children"]] == [a["title"], b["title"]]


def test_mine_and_movement_know_inside_from_steps(dela):
    """«Моё» — шаги к целям (вверх по «Зачем» есть цель), а не дела внутри клиентских дел: драфт для термшита Ивану
    — не «то, что хочу я». Движение Круга: сделанный родитель дел внутри — обычное дело (1), цель — 3."""
    from pravka_dela import krug

    ops(dela, "sasha", [{"op": "svod.set", "key": krug.DIRECTIONS_KEY, "author": "test",
                         "value": [{"key": "work", "pos": 3, "name": "Дело", "short": "Дело"}]}])
    goal = mk(dela, "Сделки закрываются", goal=True, directions=["work"])
    deal_task = mk(dela, "Термшит Ивану", directions=["work"])
    draft = mk(dela, "Набросать драфт термшита", why_id=deal_task["id"])
    step = mk(dela, "Позвонить фондам", why_id=goal["id"])
    inner = mk(dela, "Найти телефоны фондов", why_id=step["id"])
    titles = [t["title"] for t in store.view(dela, "sasha", "today")["mine"]]
    assert draft["title"] not in titles and inner["title"] in titles and step["title"] not in titles, titles  # step — не лист
    ops(dela, "sasha", [{"op": "task.done", "id": draft["id"]}, {"op": "task.done", "id": deal_task["id"]}])
    with parse.db.session(dela, "sasha", "t") as c:
        mv = krug.movement(c, "sasha", db_today(dela))
    assert mv["work"]["done"] == 2 and mv["work"]["goals"] == 0 and mv["work"]["events"] == 2


def test_place_and_krug_sorter_offer_only_goals(dela):
    """Блок «дерево жизни» для разборов и список целей разборщика Круга — только цели: дело с делами внутри — не узел
    дерева (до 15.10.2026 «дело с шагами» годилось, и модель могла повесить новое дело под клиентское дело)."""
    from pravka_dela import krug_auto, place

    goal = mk(dela, "Сын считает до 20", goal=True)
    parent = mk(dela, "Термшит Альфе")
    mk(dela, "Драфт", why_id=parent["id"])
    loose = mk(dela, "Купить прописи")
    with parse.db.session(dela, "sasha", "t") as c:
        assert [r["title"] for r in place.targets(c, "sasha")] == [goal["title"]]
        assert [g["path"][-1] for g in krug_auto.goals(c, "sasha")] == [goal["title"]]
        assert place.resolve(c, {"id": loose["id"]}, parent["num"]) is None
        assert str(place.resolve(c, {"id": loose["id"]}, goal["num"])) == goal["id"]


def test_page_line_with_open_task_puts_before_this_inside(dela):
    """Хвост 2: строка Claude над открытым делом (телефон — строка внизу вкладки, веб — строка страницы с карточкой
    справа) шлёт scope.open, а не карточку дела. «До этого надо X» — дело внутри открытого (step у create), строку о
    нём пишет сервер; без открытого дела step ничего не вкладывает."""
    today = db_today(dela)
    parent = mk(dela, "Подготовить термшит", due_date=today.isoformat())
    other = mk(dela, "Позвонить юристу")
    seen = {}

    def fake(system, user_text):
        seen["system"], seen["user"] = system, user_text
        return empty_card(create=[cr("Набросать драфт", step=True)], reply="", _usage=None)

    out = ask.run(dela, "sasha", "до этого надо набросать драфт", {"task_ids": [other["id"], parent["id"]], "open": parent["id"]},
                  "", None, ask_fn=fake)
    made = out["tasks"][0]
    assert (made["why_id"], made["due_date"]) == (parent["id"], today.isoformat())
    assert "ОТКРЫТО В КАРТОЧКЕ СПРАВА" in seen["user"] and "step — true, если открыто дело" in seen["system"]
    assert out["reply"] == "Завёл для «Подготовить термшит»: «Набросать драфт», на сегодня." and out["steps"] == [made["id"]]
    # Ничего не открыто — step не вкладывает: обычное новое дело.
    out = ask.run(dela, "sasha", "x", {"task_ids": [other["id"]]}, "", None,
                  ask_fn=lambda s, u: empty_card(create=[cr("Просто дело", step=True)], _usage=None))
    assert out["tasks"][0]["why_id"] is None and out["steps"] == []


def test_card_sees_others_only_for_nesting_and_takes_put_here(dela):
    """Цена: остальные открытые дела карточка дела показывает Claude, только когда о вложении речь (и не в «Для
    этого»), и хвостом. «Вложи сюда X» из карточки родителя — правка X, где заполнено одно into = это дело; любую
    другую правку X отсюда не принимаем."""
    parent = mk(dela, "Подготовить термшит")
    x = mk(dela, "Набросать драфт")
    seen = {}

    def fake(system, user_text):
        seen["user"] = user_text
        return empty_card(changes=[item(x["num"], into=parent["num"])], _usage=None)

    ctx = ask.context(dela, "sasha", "перенеси на завтра", {"card": "task", "open": parent["id"]})
    assert "ДРУГИЕ МОИ" not in ctx["message"]
    ctx = ask.context(dela, "sasha", "вложи туда выписку", {"card": "task", "open": parent["id"], "step": True})
    assert "ДРУГИЕ МОИ" not in ctx["message"]
    out = ask.run(dela, "sasha", "вложи сюда драфт", {"card": "task", "open": parent["id"]}, "", None, ask_fn=fake)
    assert "ДРУГИЕ МОИ ОТКРЫТЫЕ ДЕЛА" in seen["user"].split("КОМАНДА:")[0] and not out["errors"], out["errors"]
    assert row(dela, x["id"])["why_id"] == parent["id"]
    # Разговор: слова о вложении были в прошлой реплике — список есть и сейчас.
    ctx = ask.context(dela, "sasha", "про термшит", {"card": "task", "open": x["id"],
                                                     "history": [{"said": "положи это в другое дело", "reply": "В какое?", "done": ""}]})
    assert "ДРУГИЕ МОИ ОТКРЫТЫЕ ДЕЛА" in ctx["message"]
    # Не только into — не трогаем.
    y = mk(dela, "Позвонить юристу")
    out = ask.run(dela, "sasha", "вложи сюда звонок и перенеси", {"card": "task", "open": parent["id"]}, "", None,
                  ask_fn=lambda s, u: empty_card(changes=[item(y["num"], into=parent["num"], due="2099-01-01")], _usage=None))
    assert out["errors"] and row(dela, y["id"])["why_id"] is None and row(dela, y["id"])["due_date"] is None


def test_loop_refusal_in_words_of_nesting(dela):
    """Петля «Вложить в…» — отказ словами вложенности, а не прежнего «шаг к самому себе» (текст базы прежний)."""
    a, b = mk(dela, "Сделка"), mk(dela, "Термшит")
    ops(dela, "sasha", [{"op": "task.set", "id": b["id"], "set": {"why_id": a["id"]}}])
    bad = ops(dela, "sasha", [{"op": "task.set", "id": a["id"], "set": {"why_id": b["id"]}}])[0]
    assert bad["error"] == "Нельзя: это дело уже внутри того, куда вкладываешь («зачем» по кругу)"


def test_card_journal_shows_nesting_to_owner_only(dela):
    """«Вложить в…» и «Вынуть» живут в строке Круга (tasks.krug): хозяину карточка дела отдаёт и её журнал, команде —
    нет (Круг личный)."""
    p = project(dela, "sasha", "Ромашка")
    with parse.db.session(dela, "system", "t") as c:
        c.execute("INSERT INTO crm.project_access (project_id, user_id, role, granted_by) VALUES (%s, 'natasha', 'edit', 'sasha')", (p,))
    parent, kid = mk(dela, "Термшит", project_id=str(p)), mk(dela, "Драфт", project_id=str(p))
    ops(dela, "sasha", [{"op": "task.set", "id": kid["id"], "set": {"why_id": parent["id"]}},
                        {"op": "task.set", "id": kid["id"], "set": {"why_id": None}}])
    mine = api.task_card(dela, "sasha", kid["id"])["history"]
    krug_rows = [h for h in mine if h["entity"] == "tasks.krug"]
    assert [(h["after"] or {}).get("why_id") for h in krug_rows][-2:] == [parent["id"], None]
    hers = api.task_card(dela, "natasha", kid["id"])["history"]
    assert hers and all(h["entity"] == "tasks.tasks" for h in hers)


def test_connector_speaks_inside(dela):
    """Хвост 1 — коннектор claude.ai: inside у dela_add (поля от родителя тем же правилом, что «Для этого») и
    dela_change (вложить, вынуть, место); why — только цель: на номер или название обычного дела — подсказка inside, а
    не новая цель с именем дела. Строка: «внутри #N …» у дела внутри, «внутри открыто N, сначала: …» у родителя; done
    последнего — «всё внутри сделано — теперь: …», open — срок родителя «снова …»."""
    from pravka_dela import mcp_tools

    p, org, bank, olga, ivan, deal = _client(dela)
    today = db_today(dela)
    parent = mk(dela, "Подготовить термшит Ивану", project_id=str(p), deal_id=str(deal), person_id=str(ivan),
                focus_on=today.isoformat())
    said = mcp_tools.add(dela, title="Набросать драфт", inside=str(parent["num"]))
    assert said.startswith("Записал: #") and f"внутри #{parent['num']} Подготовить термшит Ивану" in said, said
    num = int(said.split("#", 1)[1].split(" ", 1)[0])
    with parse.db.session(dela, "sasha", "t") as c:
        d = store.task_by(c, num)
    assert (str(d["project_id"]), str(d["deal_id"]), str(d["person_id"]), d["due_date"], d["focus_on"]) == (
        str(p), str(deal), str(ivan), today, None)
    # Названное сильнее родительского; inside — и по точному названию.
    other = mcp_tools.add(dela, title="Сверить цифры", inside="подготовить термшит ивану", due_date="2099-01-02")
    assert "срок 02.01" in other and "внутри #" in other, other
    n2 = int(other.split("#", 1)[1].split(" ", 1)[0])
    # Родитель: сколько внутри и что сначала; место — pos.
    assert mcp_tools.change(dela, str(n2), pos=1).startswith("Готово:")
    view = mcp_tools.view(dela, "now")
    assert f"внутри открыто 2, сначала: #{n2} Сверить цифры" in view, view
    card = mcp_tools.card(dela, str(parent["num"]))
    assert f"1. #{n2} Сверить цифры" in card and f"2. #{num} Набросать драфт" in card, card
    # why — только цель: номер дела — подсказка inside; название дела — подсказка, а не новая цель.
    bad = mcp_tools.add(dela, title="x", why=str(parent["num"]))
    assert bad.startswith("Не записал") and f"inside={parent['num']}" in bad, bad
    bad = mcp_tools.add(dela, title="y", why="термшит ивану")
    assert bad.startswith("Не записал") and "похоже на дело" in bad, bad
    with parse.db.session(dela, "sasha", "t") as c:
        assert not c.execute("SELECT 1 FROM tasks.v_tasks WHERE goal AND title ILIKE '%%термшит%%'").fetchone()
    # Вложить, вынуть; цель в inside — отказ (это why).
    loose = mk(dela, "Позвонить юристу")
    assert "внутри #" in mcp_tools.change(dela, str(loose["num"]), inside=str(parent["num"]))
    assert "внутри #" not in mcp_tools.change(dela, str(loose["num"]), inside="")
    aim = mk(dela, "Цель", goal=True)
    assert "цель дерева" in mcp_tools.change(dela, str(loose["num"]), inside=str(aim["num"]))
    # why и пустой inside в одной правке — «Зачем» решает why: цель ставится, а не снимается «вынуть».
    said = mcp_tools.change(dela, str(loose["num"]), why=str(aim["num"]), inside="")
    assert f"зачем #{aim['num']} Цель" in said and row(dela, loose["id"])["why_id"] == aim["id"], said
    # Под целью «вынуть» (inside="") — подсказка why="", а why="" и inside="" вместе — снять цель.
    assert "убрать цель" in mcp_tools.change(dela, str(loose["num"]), inside="")
    assert mcp_tools.change(dela, str(loose["num"]), why="", inside="").startswith("Готово:")
    assert row(dela, loose["id"])["why_id"] is None
    # Сделано последнее — родитель поднимается (он в «Сейчас» без срока — на сегодня); вернул — срок снова пуст.
    mcp_tools.change(dela, str(num), status="done")
    done = mcp_tools.change(dela, str(n2), status="done")
    assert f"всё внутри сделано — теперь: #{parent['num']} «Подготовить термшит Ивану», на сегодня" in done, done
    back = mcp_tools.change(dela, str(n2), status="open")
    assert f"срок #{parent['num']} «Подготовить термшит Ивану» — снова без срока" in back, back
