"""Дела внутри дела (15.10.2026): «Для этого», «до этого надо…» — новое дело внутри, «положи это в то дело» — вложить
существующее, порядок среди соседей (pos, dela_0030), подъём родителя, когда сделано последнее дело внутри, петли.

Владелец: «Пишу „до этого надо из расшифровки встречи набросать драфт“ в „Что поправить в этом деле“ — он добавляет в
комментарии вместо того, чтобы сделать новое дело»; «это не отдельная сущность, а обычные дела, вложенные в дело».
Claude подменён готовым ответом по схеме: проверяется всё вокруг вызова. Имена — выдуманные.
"""

from __future__ import annotations

import datetime as dt
import time
from types import SimpleNamespace as NS

from starlette.testclient import TestClient

import psycopg
import pytest

from pravka_dela import api, ask, guard, nest, parse, store, tokens
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
    же; без срока — без срока. То же правило — веб (stepDue) и телефон (DelaSteps.due). С 16.10.2026 одно место —
    nest.py: копий store.step_of в ask.py и mcp_tools больше нет, store.step_of удалён."""
    today = db_today(dela)
    later = today + dt.timedelta(days=5)
    assert nest.step_due({"due_date": None, "focus_on": today}, today) == today
    assert nest.step_due({"due_date": today - dt.timedelta(days=3)}, today) == today
    assert nest.step_due({"due_date": today.isoformat()}, today) == today
    assert nest.step_due({"due_date": later}, today) == later
    assert nest.step_due({}, today) is None
    assert nest.step_due({"focus_on": today - dt.timedelta(days=1)}, today) is None  # вчерашнее «Сейчас» — не сегодня
    assert not hasattr(store, "step_of") and not hasattr(store, "step_due") and not hasattr(store, "_back_inside")
    parent = {"id": "x", "owner_id": "sasha", "project_id": "p", "deal_id": "d", "person_id": "h", "due_date": later}
    assert nest.nest_fill({}, parent, today) == {"project_id": "p", "deal_id": "d", "person_id": "h", "due_date": later.isoformat()}
    # Названное сильнее, явная пустота — тоже слово; другой проект — без сделки родителя.
    assert nest.nest_fill({"person_id": None, "due_date": "2099-01-01"}, parent, today) == {
        "person_id": None, "due_date": "2099-01-01", "project_id": "p", "deal_id": "d"}
    assert nest.nest_fill({"project_id": "q"}, parent, today) == {"project_id": "q", "person_id": "h", "due_date": later.isoformat()}
    assert nest.nest_fill({"project_id": None}, parent, today) == {"project_id": None, "person_id": "h", "due_date": later.isoformat()}
    # Унаследованный срок снимает «потом»; родитель без срока — «потом» остаётся.
    got = nest.nest_fill({"back_on": "2099-01-01", "back_said": "потом"}, parent, today)
    assert (got["back_on"], got["back_said"], got["due_date"]) == (None, None, later.isoformat())
    assert nest.nest_fill({"back_on": "2099-01-01"}, {"id": "y"}, today) == {"back_on": "2099-01-01"}
    # Цель и напоминание ничего не дают: у цели нет проекта, сделки, человека и срока, в напоминание не вкладывают.
    assert nest.nest_fill({}, {**parent, "goal": True}, today) == {}
    assert nest.nest_fill({}, {**parent, "kind": "remind"}, today) == {}
    assert nest.nest_fill({}, None, today) == {}
    # Ключи, которые телефон шлёт явным null (Dela.createOp, NEST_FILLED), — те же, что дополняет сервер.
    assert nest.FILL_KEYS == ("project_id", "deal_id", "person_id", "due_date")


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


def test_step_without_claude_when_stopped_or_over_quota(dela, monkeypatch):
    """Стык вагонов поезда (16.10.2026): «Для этого» работает и без Claude — и когда задача стоит защитой от петли, и
    когда не-владельцу кончился суточный предел: дело названием, как без ключа, без вызова и трат, с пометкой в ответе
    (почему без Claude). Остальные правки словами — как были: Stopped словами и 429."""
    guard._caps.update(at=-guard.CAPS_EVERY, value=None)  # пороги — заводские (кэш Свода в процессе)

    def no_call(**_):
        raise AssertionError("Claude не зовём: защита стоит до вызова")

    monkeypatch.setattr(parse, "client", lambda key, proxy: NS(beta=NS(messages=NS(stream=no_call, create=no_call))))
    t = mk(dela, "Отчёт фонду")
    now = time.time()
    for i in range(31):  # задача «task» (Claude карточки дела) — 31 вызов за 10 минут: стоит
        try:
            guard.hit(dela, "task", now - 60 + i)
        except guard.Stopped:
            pass
    assert guard.blocked(dela, "task")
    with parse.db.session(dela, "system", "t") as c:
        before = c.execute("SELECT value FROM crm.state WHERE key = 'llm_cost'").fetchone()
    out = ask.run(dela, "sasha", "  собрать   выписки ", {"card": "task", "open": t["id"], "step": True}, "sk-test", None)
    assert [x["title"] for x in out["tasks"]] == ["собрать выписки"] and out["tasks"][0]["why_id"] == t["id"]
    assert out["reply"].startswith("Завёл для «Отчёт фонду»: «собрать выписки»") and "защитой от петли" in out["reply"]
    assert out["usage"] is None
    with parse.db.session(dela, "system", "t") as c:
        assert c.execute("SELECT value FROM crm.state WHERE key = 'llm_cost'").fetchone() == before
    # Не «Для этого» — как было: защита словами, ничего не заведено.
    with pytest.raises(guard.Stopped):
        ask.run(dela, "sasha", "перенеси на завтра", {"card": "task", "open": t["id"]}, "sk-test", None)

    # Не-владелец сверх суточного предела (40 правок словами): «Для этого» — 202 и дело названием, остальное — 429.
    mine = store.apply_ops(dela, "natasha", [{"op": "task.create", "task": {"title": "Сверка для фонда"}}],
                           "web")["results"][0]["task"]
    for _ in range(40):
        guard.quota(dela, "natasha", "ask")
    team = {"Authorization": f"Bearer {tokens.issue(dela, 'natasha', 'device', 'телефон-наташи-шаги')}"}
    with TestClient(api.build(Config(db_url=dela, anthropic_key="sk-test"))) as client:
        r = client.post("/api/ask", headers=team, json={"text": "перенеси", "scope": {"card": "task", "open": mine["id"]}})
        assert r.status_code == 429 and "40 из 40" in r.json()["error"]
        # no_claude из запроса не берётся: его ставит только сервер.
        r = client.post("/api/ask", headers=team, json={"text": "позвонить в банк", "scope": {
            "card": "task", "open": mine["id"], "step": True, "no_claude": True}})
        assert r.status_code == 202
        done = _wait(client, team, r.json()["job"])
    assert done["status"] == "done", done
    assert [x["title"] for x in done["tasks"]] == ["позвонить в банк"] and done["tasks"][0]["why_id"] == mine["id"]
    assert done["reply"].startswith("Завёл для «Сверка для фонда»") and "на сегодня для тебя — всё" in done["reply"]


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
    """pos — место среди соседей (dela_0030): общее поле дела, в синке и журнале как любое; дерево Круга — по нему."""
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
    stepDue (как nest.step_due), строки списков идут одной точкой taskRows (дело внутри — перед родителем, «Деревом» —
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


# ── Поезд 16.10.2026: дела внутри дела (вагон 2) и захват (вагон 4) вместе ─────────────────────────────────


def _remind_at(hours: int = 2) -> str:
    return (dt.datetime.now(dt.timezone.utc) + dt.timedelta(hours=hours)).isoformat(timespec="seconds")


def test_v_tasks_one_view_nest_and_capture(dela):
    """dela_0032 пересобирает tasks.v_tasks от ПОСЛЕДНЕЙ сборки (dela_0030), а не от dela_0013, как было в ветке захвата:
    в одном виде и вложенность (pos, why_goal, steps_open), и захват (kind, back_on, back_said, back_n, reminded_via);
    steps_open считает только дела — напоминание внутри (лечь так могло бы только мимо сервера) родителя не держит."""
    parent = mk(dela, "Отправить термшит Ивану")
    a = mk(dela, "Драфт", why_id=parent["id"], pos=1)
    r = mk(dela, "Не забыть цифры", why_id=parent["id"])
    with parse.db.session(dela, "sasha", "t") as c:
        # Напоминание без своего срока (_kind_norm): срок, унаследованный делом от родителя (nest_fill), снимаем.
        c.execute("UPDATE tasks.tasks SET kind = 'remind', remind_at = %s, due_date = NULL WHERE id = %s", (_remind_at(), r["id"]))
        cols = {d.name for d in c.execute("SELECT * FROM tasks.v_tasks LIMIT 0").description}
    assert {"pos", "why_goal", "steps_open", "kind", "back_on", "back_said", "back_n", "reminded_via"} <= cols, cols
    p, x = row(dela, parent["id"]), row(dela, a["id"])
    assert p["steps_open"] == 1 and x["pos"] == 1 and x["why_goal"] is False and x["kind"] == "task"
    # Слияние людей (crm.person_merge в dela_0032) — тело от dela_0027: и «Только тебе», и идеи.
    with parse.db.session(dela, "system", "t") as c:
        body = c.execute("SELECT prosrc FROM pg_proc WHERE oid = 'crm.person_merge(uuid, uuid)'::regprocedure").fetchone()["prosrc"]
    assert "crm.insights" in body and "crm.ideas" in body


def test_reminder_inside_does_not_hold_or_raise_parent(dela):
    """Подъём родителя (правило 27) — только по делам (tasks.nest_rise / nest_fall, dela_0032): открытое напоминание внутри
    не держит родителя «внутри ещё открыто», сделанное напоминание его не поднимает, «Вернуть» напоминания срок ему не
    возвращает. В dela_0030 открытых детей искали по виду строки Круга (krug.kind), а не дела."""
    today = db_today(dela)
    later = (today + dt.timedelta(days=7)).isoformat()
    parent = mk(dela, "Отправить термшит Ивану", due_date=later)
    a = mk(dela, "Драфт", why_id=parent["id"])
    r = mk(dela, "Не забыть цифры", why_id=parent["id"])
    with parse.db.session(dela, "sasha", "t") as c:  # мимо сервера (он так не вложит) — база всё равно права
        # Напоминание без своего срока (_kind_norm): срок, унаследованный делом от родителя (nest_fill), снимаем.
        c.execute("UPDATE tasks.tasks SET kind = 'remind', remind_at = %s, due_date = NULL WHERE id = %s", (_remind_at(), r["id"]))
    got = ops(dela, "sasha", [{"op": "task.done", "id": r["id"]}])[0]
    assert got["ok"] and "raised" not in got and row(dela, parent["id"])["due_date"] == later
    assert ops(dela, "sasha", [{"op": "task.reopen", "id": r["id"]}])[0]["ok"]
    last = ops(dela, "sasha", [{"op": "task.done", "id": a["id"]}])[0]
    assert last["raised"]["moved"] is True and row(dela, parent["id"])["due_date"] == today.isoformat(), last
    ops(dela, "sasha", [{"op": "task.done", "id": r["id"]}])
    back = ops(dela, "sasha", [{"op": "task.reopen", "id": r["id"]}])[0]
    assert back["ok"] and "fell" not in back and row(dela, parent["id"])["due_date"] == today.isoformat(), back


def test_reminder_is_not_nested_and_nothing_goes_into_it(dela):
    """Напоминание — не дело (захват): в дело оно не вкладывается, в него ничего не вкладывают — отказ словами; цель
    дерева «Зачем» у напоминания можно; «Это дело» (kind task) — и вкладывай."""
    parent = mk(dela, "Отправить термшит Ивану")
    goal = mk(dela, "Сделки закрываются", goal=True)
    rem = mk(dela, "Отправить счёт в Контуре", kind="remind", remind_at=_remind_at())
    assert rem["kind"] == "remind"
    r1 = ops(dela, "sasha", [{"op": "task.set", "id": rem["id"], "set": {"why_id": parent["id"]}}])[0]
    assert not r1["ok"] and "напоминание не вкладывается в дело" in r1["error"], r1
    r2 = ops(dela, "sasha", [{"op": "task.create", "task": {"title": "Сверить цифры", "why_id": rem["id"]}}])[0]
    assert not r2["ok"] and "в напоминание дела не вкладываются" in r2["error"], r2
    assert row(dela, rem["id"])["why_id"] is None
    assert ops(dela, "sasha", [{"op": "task.set", "id": rem["id"], "set": {"why_id": goal["id"]}}])[0]["ok"]
    r3 = ops(dela, "sasha", [{"op": "task.set", "id": rem["id"], "set": {"kind": "task", "why_id": parent["id"]}}])[0]
    assert r3["ok"], r3
    x = row(dela, rem["id"])
    assert x["kind"] == "task" and x["why_id"] == parent["id"] and x["why_goal"] is False


def test_later_does_not_beat_parent_due(dela):
    """«Потом» (захват, back_on) не перебивает срок, унаследованный от родителя: новое дело внутри дела без своего срока,
    но с днём возврата, берёт срок по правилу step_due, а «потом» снимается (nest.nest_fill в store.op_task_create; до
    16.10.2026 — store._back_inside). Родитель без срока — «потом» остаётся; под целью — тоже (у цели срока нет)."""
    today = db_today(dela)
    later = (today + dt.timedelta(days=5)).isoformat()
    back = (today + dt.timedelta(days=1)).isoformat()
    dated = mk(dela, "Подписать договор", due_date=later)
    child = mk(dela, "Запросить реквизиты", why_id=dated["id"], back_on=back, back_said="на неделе")
    x = row(dela, child["id"])
    assert (x["due_date"], x["back_on"], x["back_said"]) == (later, None, None), x
    now_ = mk(dela, "Отправить счёт", focus_on=today.isoformat())
    y = row(dela, mk(dela, "Сверить сумму", why_id=now_["id"], back_on=back)["id"])
    assert (y["due_date"], y["back_on"]) == (today.isoformat(), None), y
    loose = mk(dela, "Когда-нибудь")
    z = row(dela, mk(dela, "Подумать", why_id=loose["id"], back_on=back)["id"])
    assert (z["due_date"], z["back_on"]) == (None, back), z
    goal = mk(dela, "Сделки закрываются", goal=True)
    g = row(dela, mk(dela, "Шаг к цели", why_id=goal["id"], back_on=back)["id"])
    assert (g["due_date"], g["back_on"]) == (None, back), g


def test_agenda_reply_says_povestka(dela):
    """Дело в повестку — ответ говорит «в повестку с …», а не только «без срока» (мелочи 16.10.2026, запись 19: дело
    легло в повестку, а ответ сказал «без срока», и повестки Саша не увидел). Дело внутри — в строке «Завёл для …»,
    отдельное — своей строкой сервера; не повестка — как было."""
    with parse.db.session(dela, "system", "t") as c:
        c.execute("INSERT INTO crm.people (name, short, owner_id) VALUES ('Иван Тестов', 'Иван', 'sasha')")
    t = mk(dela, "Грант для фонда")

    def fake(system, user_text):
        return empty_card(create=[cr("Обсудить с Иваном грант", person="Иван", ball="agenda", step=True),
                                  cr("Спросить Ивана про сроки", person="Иван", ball="agenda"),
                                  cr("Собрать выписки", step=True)], _usage=None)

    out = ask.run(dela, "sasha", "обсудить с Иваном грант и спросить про сроки", {"card": "task", "open": t["id"]}, "",
                  None, ask_fn=fake)
    assert [x["ball"] for x in out["tasks"]] == ["agenda", "agenda", "mine"], out["tasks"]
    assert out["reply"].startswith("Завёл для «Грант для фонда»: «Обсудить с Иваном грант», в повестку с Иваном "
                                   "Тестовым; «Собрать выписки», без срока."), out["reply"]
    assert "«Спросить Ивана про сроки» — в повестку с Иваном Тестовым." in out["reply"]
    assert ask.group_said({"ball": "agenda", "person_name": "Ольга Тестова"}) == "в повестку с Ольгой Тестовой"
    assert ask.group_said({"ball": "waiting", "person_name": "Ольга Тестова"}) == ""


# ── Один решатель вложенности (16.10.2026, тяжёлая полоса, правка 01-nest; записи отладки 20–23) ─────────────────────
# Владелец 15.10.2026: «чтобы я мог свободно делать nesting и говорить дела и они нормально подцеплялись». «Куда класть»
# (nest.resolve, nest.rank) и «что берёт от родителя» (nest.nest_fill в store.op_task_create) — одно место для строки
# Claude, захвата, перетаскивания и «Нового».


def _dirs(url):
    from pravka_dela import krug

    ops(url, "sasha", [{"op": "svod.set", "key": krug.DIRECTIONS_KEY, "author": "test", "value": [
        {"key": "health", "pos": 0, "name": "Здоровье", "short": "Здоровье"},
        {"key": "work", "pos": 3, "name": "Дело", "short": "Дело", "aliases": ["Работа"]}]}])


def test_resolve_number_new_direction_out(dela):
    """nest.resolve — куда класть, из строки входа: «#N» (и «N») — своё открытое дело или цель; «^k» — k-е новое из этого
    же ответа; «направление Здоровье» — прямо на направление (why_id null, directions [health]), как бросок на колонку
    дерева; «-» — вынуть; «» — не трогать."""
    _dirs(dela)
    deal = mk(dela, "Подготовить КП Ромашке")
    goal = mk(dela, "Осваиваю гончарное дело", goal=True, directions=["work"])
    with parse.db.session(dela, "sasha", "t") as c:
        got = nest.resolve(c, "sasha", f"#{deal['num']}")
        assert got == {"kind": "task", "why_id": deal["id"], "sure": True, "num": deal["num"], "title": deal["title"]}
        assert nest.resolve(c, "sasha", str(deal["num"]))["why_id"] == deal["id"]
        g = nest.resolve(c, "sasha", f"#{goal['num']}")
        assert (g["kind"], g["why_id"], g["sure"]) == ("goal", goal["id"], True)
        # Цель — родитель и цели (подцель), и дела.
        assert nest.resolve(c, "sasha", f"#{goal['num']}", child={"goal": True})["why_id"] == goal["id"]
        assert nest.resolve(c, "sasha", "^1", made=["u-1", "u-2"]) == {"kind": "new", "why_id": "u-1", "sure": True}
        assert nest.resolve(c, "sasha", " ^2 ", made=["u-1", "u-2"])["why_id"] == "u-2"
        bad = nest.resolve(c, "sasha", "^3", made=["u-1"])
        assert bad["sure"] is False and "нет 3-го нового" in bad["error"] and "why_id" not in bad
        assert nest.resolve(c, "sasha", "направление Здоровье") == {
            "kind": "direction", "why_id": None, "directions": ["health"], "sure": True}
        assert nest.resolve(c, "sasha", "в направление «работа»")["directions"] == ["work"]  # другое имя направления
        nope = nest.resolve(c, "sasha", "направление Космос")
        assert nope["sure"] is False and "нет такого направления" in nope["error"]
        assert nest.resolve(c, "sasha", "-") == {"kind": "out", "why_id": None, "sure": True}
        assert nest.resolve(c, "sasha", "") == {"kind": "keep", "sure": True}
        assert nest.resolve(c, "sasha", None) == {"kind": "keep", "sure": True}
        assert "дела #999999 нет" in nest.resolve(c, "sasha", "#999999")["error"]


def test_resolve_refuses_foreign_remind_closed_and_loop(dela):
    """Отказы словами, а не молча и не ошибкой базы: чужое дело (Круг личный), закрытое, напоминание, ориентир, цель в
    дело, в самого себя и по кругу (дело — внутри того, что вкладываем). Базу и так страхует krug_rules."""
    hers = store.apply_ops(dela, "natasha", [{"op": "task.create", "task": {"title": "Сверка коллеги"}}], "web")["results"][0]["task"]
    rem = mk(dela, "Отправить счёт", kind="remind", remind_at=_remind_at())
    shut = mk(dela, "Старое")
    ops(dela, "sasha", [{"op": "task.done", "id": shut["id"]}])
    mark = mk(dela, "Ориентир", goal=True, no_steps=True)
    top = mk(dela, "Договор с Ромашкой")
    mid = mk(dela, "Запросить реквизиты", why_id=top["id"])
    low = mk(dela, "Найти телефон бухгалтера", why_id=mid["id"])
    loose = mk(dela, "Купить тетради")
    with parse.db.session(dela, "sasha", "t") as c:
        def err(into, **kw):
            got = nest.resolve(c, "sasha", into, **kw)
            assert got["sure"] is False and "why_id" not in got, got
            return got["error"]

        e = err(f"#{hers['num']}")
        assert "не видно" in e or "чужое дело" in e, e  # чужое личное база не показывает, а видное — не своё
        assert "закрыто" in err(f"#{shut['num']}")
        assert "напоминание" in err(f"#{rem['num']}")
        assert "ориентир" in err(f"#{mark['num']}")
        assert "цель кладут в цель или на направление" in err(f"#{top['num']}", child={"goal": True})
        assert "в самого себя" in err(f"#{top['num']}", child={"id": top["id"]})
        assert "по кругу" in err(f"#{low['num']}", child={"id": top["id"]})
        assert "по кругу" in err(f"#{mid['num']}", child={"id": top["id"]})
        assert "в самого себя" in err("^1", made=[top["id"]], child={"id": top["id"]})
        assert "цель кладут в цель" in err("договор с Ромашкой", child={"goal": True})  # словами цель в дело не ищем
        # Соседняя ветка — можно.
        assert nest.resolve(c, "sasha", f"#{low['num']}", child={"id": loose["id"]})["sure"]
    # Дело в общем проекте видно члену команды, но «Зачем» — только своё.
    p = project(dela, "sasha", "Ромашка")
    with parse.db.session(dela, "system", "t") as c:
        c.execute("INSERT INTO crm.project_access (project_id, user_id, role, granted_by) VALUES (%s, 'natasha', 'edit', 'sasha')", (p,))
    shared = mk(dela, "Ромашка: акт сверки", project_id=str(p))
    with parse.db.session(dela, "natasha", "t") as c:
        got = nest.resolve(c, "natasha", f"#{shared['num']}")
    assert not got["sure"] and "чужое дело" in got["error"]


def test_rank_finds_parent_by_words(dela):
    """nest.rank — родитель по словам (голос «к делу про сверку», кнопки «Вложить в…»): свои открытые дела, не цели и не
    напоминания; доля общих основ (падеж не мешает, служебные — мимо), +0,2 тот же проект, +0,1 тот же человек, полное
    вхождение названия — 1,0. Уверен — лучший от 0,6 и второй ниже хотя бы на 0,2; иначе до трёх кандидатов от 0,34."""
    p, org, bank, olga, ivan, deal = _client(dela)
    other = project(dela, "sasha", "Ромашка")
    kp = mk(dela, "Подготовить КП Ромашке", project_id=str(other))
    with_bank = mk(dela, "Сверка с банком")
    sept = mk(dela, "Сверка за сентябрь")
    mk(dela, "Сверка целей", goal=True)  # цель — не кандидат
    mk(dela, "Сверка напомнить", kind="remind", remind_at=_remind_at())  # напоминание — не кандидат
    ops(dela, "sasha", [{"op": "task.done", "id": mk(dela, "Сверка старая")["id"]}])  # закрытое — не кандидат
    alfa = mk(dela, "Отправить договор", project_id=str(p), person_id=str(ivan))
    roma = mk(dela, "Отправить договор", project_id=str(other))
    with parse.db.session(dela, "sasha", "t") as c:
        exact = nest.rank(c, "sasha", "Подготовить КП Ромашке")
        assert exact["sure"] and exact["why_id"] == kp["id"] and exact["score"] == 1.0, exact
        # Основы: «к делу про КП Ромашке» — служебные мимо, «сверку с банком» — падеж.
        assert nest.rank(c, "sasha", "к делу про КП Ромашке")["why_id"] == kp["id"]
        stems = nest.rank(c, "sasha", "сверку с банком")
        assert stems["sure"] and stems["why_id"] == with_bank["id"], stems
        assert [x["id"] for x in stems["cands"]] == [with_bank["id"], sept["id"]]
        # Двусмысленно: «сверка» — два дела поровну → кандидаты, а не уверенно; цель, напоминание и закрытое — мимо.
        two = nest.rank(c, "sasha", "сверка")
        assert not two["sure"] and "why_id" not in two, two
        assert {x["id"] for x in two["cands"]} == {with_bank["id"], sept["id"]}, two
        # Проект решает ничью (+0,2); человек один — нет (+0,1 меньше запаса 0,2), но ставит своё дело первым.
        tie = nest.rank(c, "sasha", "отправить договор")
        assert not tie["sure"] and len(tie["cands"]) == 2
        by_project = nest.rank(c, "sasha", "отправить договор", project_id=str(other))
        assert by_project["sure"] and by_project["why_id"] == roma["id"] and by_project["score"] == 1.2, by_project
        by_person = nest.rank(c, "sasha", "отправить договор", person_id=str(ivan))
        assert not by_person["sure"] and by_person["cands"][0]["id"] == alfa["id"], by_person
        # Ничего общего — пусто.
        assert nest.rank(c, "sasha", "купить подарок для Пети") == {"sure": False, "cands": []}
        assert nest.rank(c, "sasha", "к делу") == {"sure": False, "cands": []}
    # Что кладём — само и всё внутри него — не кандидат (петля).
    inner = mk(dela, "Сверка с банком: выписки", why_id=with_bank["id"])
    with parse.db.session(dela, "sasha", "t") as c:
        alone = nest.rank(c, "sasha", "сверку с банком", child_id=with_bank["id"])
        assert alone["sure"] is False and [x["id"] for x in alone["cands"]] == [sept["id"]], alone
        assert inner["id"] not in {x["id"] for x in alone["cands"]}
        # resolve понимает слова тем же rank: уверенно — дело, иначе отказ с кандидатами.
        hit = nest.resolve(c, "sasha", "к сверке за сентябрь")
        assert (hit["kind"], hit["why_id"], hit["sure"], hit["by"]) == ("task", sept["id"], True, "words"), hit
        many = nest.resolve(c, "sasha", "сверка", child={"project_id": None})
        assert many["kind"] == "words" and not many["sure"] and "похоже на несколько дел" in many["error"], many
        assert len(many["cands"]) >= 2 and "why_id" not in many
        none = nest.resolve(c, "sasha", "купить подарок для Пети")
        assert none["kind"] == "words" and none["cands"] == [] and "не нашёл" in none["error"] and "«#57»" in none["error"]
        # Бонус проекта — от того, что кладём.
        assert nest.resolve(c, "sasha", "отправить договор", child={"project_id": str(p)})["why_id"] == alfa["id"]


def test_create_inside_deed_takes_parent_fields_named_wins(dela):
    """task.create с why_id на ДЕЛО — от родителя проект, сделка, человек и срок (store.op_task_create → nest.nest_fill)
    у любого входа: службы, захват, «Новое», телефон. Названное сильнее; явная пустота (null) — тоже слово: телефон шлёт
    так очищенное поле; другой проект — без сделки родителя. Места не назвали — в конец соседей."""
    p, org, bank, olga, ivan, deal = _client(dela)
    other = project(dela, "sasha", "Ромашка")
    today = db_today(dela)
    later = (today + dt.timedelta(days=6)).isoformat()
    parent = mk(dela, "Подготовить тизер для Альфы", project_id=str(p), deal_id=str(deal), person_id=str(ivan), due_date=later)
    kid = mk(dela, "Собрать отчётность", why_id=parent["id"], source="meeting")
    assert (kid["project_id"], kid["deal_id"], kid["person_id"], kid["due_date"], kid["why_id"]) == (
        str(p), str(deal), str(ivan), later, parent["id"])
    assert kid["focus_on"] is None  # «Сейчас» не наследуется
    friday = (today + dt.timedelta(days=2)).isoformat()
    named = mk(dela, "Сверить цифры с Ольгой", why_id=str(parent["num"]), person_id=str(olga), due_date=friday)
    assert (named["person_id"], named["due_date"], named["deal_id"]) == (str(olga), friday, str(deal))
    blank = mk(dela, "Прикинуть самому", why_id=parent["id"], person_id=None, due_date=None)
    assert (blank["person_id"], blank["due_date"], blank["project_id"]) == (None, None, str(p))
    moved = mk(dela, "Спросить в Ромашке", why_id=parent["id"], project_id=str(other))
    assert (moved["project_id"], moved["deal_id"], moved["person_id"]) == (str(other), None, str(ivan))
    # В конец соседей: сосед, заведённый «позже» (часы клиента впереди), не встаёт после нового — соседи нумеруются.
    with parse.db.session(dela, "system", "t") as c:
        c.execute("UPDATE tasks.tasks SET created_at = now() + interval '1 hour' WHERE id = %s", (kid["id"],))
    last = mk(dela, "Отправить тизер", why_id=parent["id"])
    with parse.db.session(dela, "sasha", "t") as c:
        order = [r["title"] for r in c.execute(
            "SELECT title FROM tasks.v_tasks WHERE why_id = %s AND status = 'open' ORDER BY pos NULLS LAST, created_at, num",
            (parent["id"],)).fetchall()]
    assert order[-1] == "Отправить тизер" and row(dela, last["id"])["pos"] == len(order), order
    # Место названо — его и держим.
    first = mk(dela, "Созвониться с Иваном", why_id=parent["id"], pos=1)
    assert row(dela, first["id"])["pos"] == 1


def test_create_under_goal_takes_nothing(dela):
    """Родитель — цель дерева: ничего не наследуется (у цели нет проекта, сделки и человека; её срок — не срок шагов),
    «Зачем» — цель. Напоминание в дело не вкладывается — отказ словами, как раньше. Новая цель под делом от него тоже
    ничего не берёт (цель живёт без проекта, человека и срока родителя)."""
    today = db_today(dela)
    goal = mk(dela, "Сын хорошо знает русский", goal=True, due_date=today.isoformat())
    step = mk(dela, "Купить прописи", why_id=goal["id"])
    assert (step["why_id"], step["due_date"], step["project_id"], step["person_id"]) == (goal["id"], None, None, None)
    parent = mk(dela, "Отправить термшит", due_date=today.isoformat())
    bad = ops(dela, "sasha", [{"op": "task.create", "task": {"title": "Не забыть", "kind": "remind", "remind_at": _remind_at(),
                                                               "why_id": parent["id"]}}])[0]
    assert not bad["ok"] and "напоминание не вкладывается в дело" in bad["error"]
    sub = ops(dela, "sasha", [{"op": "task.create", "task": {"title": "Подцель", "goal": True, "why_id": parent["id"]}}])[0]
    assert sub["ok"] and sub["task"]["due_date"] is None, sub


def test_new_parent_and_inside_one_batch(dela):
    """«^k» строки Claude — родитель и дела внутри него одной пачкой apply_ops: task.create принимает свой id (uuid
    клиента, to_ops даёт его заранее), родитель ложится первым, дети берут его поля и встают по порядку."""
    import uuid as _uuid

    p = project(dela, "sasha", "Ромашка")
    today = db_today(dela)
    later = (today + dt.timedelta(days=4)).isoformat()
    made = [str(_uuid.uuid4()) for _ in range(3)]
    with parse.db.session(dela, "sasha", "t") as c:
        into = [nest.resolve(c, "sasha", "^1", made=made) for _ in range(2)]
    assert all(x["sure"] and x["why_id"] == made[0] for x in into)
    r = ops(dela, "sasha", [
        {"op": "task.create", "task": {"id": made[0], "title": "Подготовить КП Ромашке", "project_id": str(p), "due_date": later}},
        {"op": "task.create", "task": {"id": made[1], "title": "Собрать цифры", "why_id": into[0]["why_id"]}},
        {"op": "task.create", "task": {"id": made[2], "title": "Согласовать с Ольгой", "why_id": into[1]["why_id"]}},
    ])
    assert all(x["ok"] for x in r), r
    assert [x["task"]["id"] for x in r] == made
    for x in r[1:]:
        t = x["task"]
        assert (t["why_id"], t["project_id"], t["due_date"]) == (made[0], str(p), later), t
    assert row(dela, made[0])["steps_open"] == 2
    with parse.db.session(dela, "sasha", "t") as c:
        order = [str(x["id"]) for x in c.execute(
            "SELECT id FROM tasks.v_tasks WHERE why_id = %s ORDER BY pos NULLS LAST, created_at, num", (made[0],)).fetchall()]
    assert order == made[1:]  # по порядку пачки: первое — первым
    # Повтор той же пачки (телефон без op_id) — те же дела, без дублей.
    again = ops(dela, "sasha", [{"op": "task.create", "task": {"id": made[1], "title": "Собрать цифры", "why_id": made[0]}}])[0]
    assert again["ok"] and again["task"]["id"] == made[1]
    with parse.db.session(dela, "sasha", "t") as c:
        assert c.execute("SELECT count(*) AS n FROM tasks.tasks WHERE title = 'Собрать цифры'").fetchone()["n"] == 1


def test_card_dash_keeps_empty_and_near_inside_follows_parent(dela):
    """Строка Claude в карточке: «-» у проекта, человека и срока дела внутри — явная пустота, сервер её не перебивает.
    «Рядом» из карточки дела, которое само внутри другого дела, — тоже внутри того дела и берёт его поля (одно правило
    для всех входов), а названное Claude сильнее; «-» у «рядом» — тоже пустота."""
    p, org, bank, olga, ivan, deal = _client(dela)
    today = db_today(dela)
    later = (today + dt.timedelta(days=5)).isoformat()
    top = mk(dela, "Закрыть сделку с Альфой", project_id=str(p), deal_id=str(deal), person_id=str(ivan), due_date=later)
    t = mk(dela, "Подготовить тизер", why_id=top["id"])
    out = ask.run(dela, "sasha", "x", {"card": "task", "open": t["id"]}, "", None,
                  ask_fn=lambda s, u: empty_card(create=[cr("Самому подумать", step=True, project="-", person="-", due="-")],
                                                 _usage=None))
    a = out["tasks"][0]
    assert (a["why_id"], a["project_id"], a["deal_id"], a["person_id"], a["due_date"]) == (t["id"], None, None, None, None)
    out = ask.run(dela, "sasha", "x", {"card": "task", "open": t["id"]}, "", None,
                  ask_fn=lambda s, u: empty_card(create=[cr("Позвонить фонду"), cr("Написать Ольге", person="Ольга Смирнова"),
                                                         cr("Прикинуть без сроков", person="-", due="-")], _usage=None))
    near, named, dash = out["tasks"]
    assert (near["why_id"], near["project_id"], near["deal_id"], near["person_id"], near["due_date"]) == (
        top["id"], str(p), str(deal), str(ivan), later)
    assert (named["why_id"], named["person_id"]) == (top["id"], str(olga))
    assert (dash["why_id"], dash["project_id"], dash["person_id"], dash["due_date"]) == (top["id"], str(p), None, None)


def test_service_suggestion_inside_takes_parent_fields(dela):
    """Дело внутри от службы («Новое»): пустое поле автоматики (null в payload) — не слово Саши, поле берётся от
    родителя; пустое из «Поправить» — его слово, остаётся пустым."""
    p, org, bank, olga, ivan, deal = _client(dela)
    later = (db_today(dela) + dt.timedelta(days=3)).isoformat()
    parent = mk(dela, "Подготовить тизер для Альфы", project_id=str(p), person_id=str(ivan), due_date=later)

    def sug(title, **payload):
        r = ops(dela, "system", [{"op": "suggestion.create", "suggestion": {
            "for_user": "sasha", "kind": "create", "source": "meeting", "source_ref": f"meeting:{title}",
            "batch_ref": "meeting:77", "batch_title": "Альфа: статус · 14.10",
            "payload": {"title": title, "why_id": parent["id"], **payload}}}])[0]
        assert r["ok"], r
        return r["suggestion"]

    s1 = sug("Собрать цифры для тизера", person_id=None, due_date=None, project_id=None)
    got = ops(dela, "sasha", [{"op": "suggestion.decide", "id": str(s1["id"]), "decision": "accept"}])[0]
    assert got["ok"], got
    t1 = got["task"]
    assert (t1["why_id"], t1["project_id"], t1["person_id"], t1["due_date"]) == (parent["id"], str(p), str(ivan), later), t1
    s2 = sug("Сверить с юристом")
    got = ops(dela, "sasha", [{"op": "suggestion.decide", "id": str(s2["id"]), "decision": "accept",
                               "set": {"person_id": None}}])[0]
    assert got["ok"], got
    assert (got["task"]["person_id"], got["task"]["due_date"]) == (None, later), got["task"]
