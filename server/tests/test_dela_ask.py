"""Правка дел словами (ask.py): что видит Claude, как его ответ становится правками, отмена.

Claude подменён: проверяется всё вокруг вызова, а не сама модель.
"""

from __future__ import annotations

import datetime as dt
import time

from starlette.testclient import TestClient

from pravka_dela import api, ask, parse, store, tokens
from pravka_dela.config import Config
from test_dela_parse import _people
from test_dela_schema import dela, project  # noqa: F401


def item(num, **kw):
    """Запись правки, как её вернёт Claude: всё пусто, кроме сказанного."""
    base = {"num": num, "title": "", "notes_add": "", "project": "", "deal": "", "person": "", "due": "", "due_time": "",
            "ball": "", "now": "", "estimate_min": 0, "labels_add": [], "labels_remove": [], "status": ""}
    return {**base, **kw}


def mk(url, title, **kw):
    return store.apply_ops(url, "sasha", [{"op": "task.create", "task": {"title": title, **kw}}], "web")["results"][0]["task"]


def test_ask_edits_tasks_on_screen(dela):
    p = project(dela, "sasha", "ПТИЦ", aliases=["Птиц"])
    _people(dela)
    with parse.db.session(dela, "system", "test") as c:
        c.execute("INSERT INTO crm.people (name, short, owner_id) VALUES ('Наталья Ш', 'Наташа', 'sasha')")
    today = dt.date.today()
    late = (today - dt.timedelta(days=3)).isoformat()
    a = mk(dela, "Бюджет на квартал", due_date=late, project_id=str(p))
    b = mk(dela, "Смета по ремонту", due_date=late)
    c_ = mk(dela, "Закрыть акт")
    off = mk(dela, "Чужое на другой странице")
    seen = {}
    tomorrow = (today + dt.timedelta(days=1)).isoformat()

    def fake(system, user_text):
        seen["system"], seen["user"] = system, user_text
        return {"route": "edit", "reply": "Бюджет — первым делом сегодня, смету Наташе, акт закрыл.", "create": [], "changes": [
            item(a["num"], now="on", due=today.isoformat(), notes_add="сначала маркетинг"),
            item(b["num"], person="Наташа", ball="waiting", title="Наташа: смета по ремонту", due=tomorrow, project="Птиц"),
            item(c_["num"], status="done"),
            item(off["num"], due=tomorrow),
            item(a["num"], due="-"),  # второй раз то же дело — не трогаем
        ], "_usage": {"input": 1000, "output": 200, "cache_write": 0, "cache_read": 3000}}

    scope = {"title": "Проект ПТИЦ", "task_ids": [a["id"], b["id"], c_["id"]], "project_id": str(p)}
    out = ask.run(dela, "sasha", "бюджет первым делом, смету Наташе к завтра, акт закрыл", scope, "", None, ask_fn=fake)
    assert "— ПТИЦ (Птиц)" in seen["system"] and "— Наташа (Наталья Ш)" in seen["system"]
    assert "Страница: Проект ПТИЦ" in seen["user"] and "(просрочено)" in seen["user"] and "Чужое" not in seen["user"]
    assert f"{today.isoformat()} — сегодня" in seen["user"] and seen["user"].endswith("акт закрыл")
    assert out["route"] == "edit" and out["reply"].startswith("Бюджет")
    with parse.db.session(dela, "sasha", "t") as c:
        st = {r["num"]: r for r in c.execute("SELECT * FROM tasks.v_tasks")}
    assert st[a["num"]]["focus_on"] == today and st[a["num"]]["due_date"] == today and st[a["num"]]["notes"] == "сначала маркетинг"
    nb = st[b["num"]]
    assert (nb["person_short"], nb["ball"], nb["title"], nb["project_id"]) == ("Наташа", "waiting", "Наташа: смета по ремонту", p)
    assert st[c_["num"]]["status"] == "done"
    assert st[off["num"]]["due_date"] is None  # не на экране — не трогаем
    assert any("не на экране" in e for e in out["errors"])
    # Как было — для «Вернуть»: только изменённые поля и статус.
    undo = {u["num"]: u for u in out["changed"]}
    assert undo[a["num"]]["before"] == {"due_date": late, "focus_on": None, "notes": None}
    assert undo[c_["num"]]["status"] == ["open", "done"] and undo[c_["num"]]["before"] == {}
    assert ask.cost(fake("", "")["_usage"]) > 0
    with parse.db.session(dela, "system", "t") as c:
        spent = c.execute("SELECT value FROM crm.state WHERE key = 'llm_cost'").fetchone()["value"]
    assert spent["by"]["ask"] > 0 and spent["total"] == spent["by"]["ask"]


def test_ask_focus_create_and_new_route(dela):
    _people(dela)
    t = mk(dela, "Иван: прислать модель")

    def fake(system, user_text):
        assert "ДЕЛО (команда про него):" in user_text and "Иван: прислать модель" in user_text.split("ДЕЛА НА ЭКРАНЕ")[0]
        return {"route": "new", "reply": "", "changes": [item(t["num"], due="2099-01-30", due_time="9:30")],
                "create": [item(0, title="Позвонить Ивану", person="Ваня", ball="agenda")]}

    # У микрофона дела — всегда правка, даже если Claude решил, что это новое.
    out = ask.run(dela, "sasha", "перенеси на 30 января в полдесятого и напомни позвонить", {"focus": t["id"]}, "", None,
                  ask_fn=fake, parse_fn=lambda *a: (_ for _ in ()).throw(AssertionError("разбор не нужен")))
    assert out["route"] == "edit" and out["changed"][0]["after"] == {"due_date": "2099-01-30", "due_time": "09:30"}
    assert out["tasks"][0]["title"] == "Позвонить Ивану" and out["tasks"][0]["ball"] == "agenda"

    # Строка наверху, команда целиком про новые дела — разбор надиктовки, как у звёздочки.
    calls = []

    def fake_parse(system, user_text):
        calls.append(user_text)
        return {"tasks": [{"title": "Наташе сверку", "notes": "", "project": "", "person": "", "ball": "mine", "due": "",
                           "estimate_min": 0, "money": "", "want": False, "labels": []}], "notes": []}

    out = ask.run(dela, "sasha", "Наташе сверку к пятнице", {"task_ids": [t["id"]]}, "", None,
                  ask_fn=lambda s, u: {"route": "new", "reply": "", "changes": [], "create": []}, parse_fn=fake_parse)
    assert out["route"] == "new" and out["tasks"][0]["title"] == "Наташе сверку" and calls


def _wait(client, h, jid):
    for _ in range(100):
        r = client.get(f"/api/ask/{jid}", headers=h).json()
        if r.get("status") != "run":
            return r
        time.sleep(0.05)
    raise AssertionError("правка не закончилась")


def test_ask_api(dela, monkeypatch):
    h = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'device', 'телефон')}"}
    t = mk(dela, "Сверка")
    client = TestClient(api.build(Config(db_url=dela)))
    r = client.post("/api/ask", headers=h, json={"text": "на завтра", "scope": {"task_ids": [t["id"]]}})
    assert r.status_code == 422 and "нет ключа" in r.json()["error"]
    monkeypatch.setattr(ask, "client", lambda key, proxy: object())
    seen = {}

    def fake(cl, s, u, model, effort):
        seen["model"], seen["effort"] = model, effort
        return {"route": "edit", "reply": "Готово", "create": [], "changes": [item(t["num"], estimate_min=15)],
                "_usage": {"model": model, "input": 500, "output": 100, "cache_write": 0, "cache_read": 0}}

    monkeypatch.setattr(ask, "ask", fake)
    nat = {"Authorization": f"Bearer {tokens.issue(dela, 'natasha', 'device', 'телефон Наташи')}"}
    with TestClient(api.build(Config(db_url=dela, anthropic_key="sk-test"))) as client:
        assert client.post("/api/ask", headers=h, json={"text": "x", "scope": {"task_ids": ["nope"]}}).status_code == 400
        assert client.post("/api/ask", json={"text": "x"}).status_code == 401
        done = _wait(client, h, client.post("/api/ask", headers=h, json={"text": "15 минут", "scope": {"focus": t["id"]}}).json()["job"])
        assert done["status"] == "done" and done["reply"] == "Готово" and done["changed"][0]["after"] == {"estimate_min": 15}
        assert (seen["model"], seen["effort"]) == ("claude-sonnet-5-5", "low")  # по умолчанию
        # «Настройки»: Opus и «вдумчиво» — у этого человека; траты видит только владелец.
        store.apply_ops(dela, "sasha", [{"op": "user.settings", "settings": {"claude_model": "opus", "claude_effort": "medium"}}], "web")
        _wait(client, h, client.post("/api/ask", headers=h, json={"text": "15 минут", "scope": {"focus": t["id"]}}).json()["job"])
        assert (seen["model"], seen["effort"]) == ("claude-opus-5-5", "medium")
        mine = client.get("/api/settings", headers=h).json()
        assert mine["settings"]["claude_model"] == "opus" and mine["cost"]["by"]["ask"] > 0
        assert "claude-opus-5-5" in mine["cost"]["models"] and mine["cost"]["today"] == mine["cost"]["total"]
        theirs = client.get("/api/settings", headers=nat).json()
        assert "cost" not in theirs and theirs["default"]["claude_model"] == "sonnet"


def test_schema_is_strict():
    def walk(node):
        if node.get("type") == "object":
            assert node["additionalProperties"] is False
            assert set(node["required"]) == set(node["properties"])
            for v in node["properties"].values():
                walk(v)
        if node.get("type") == "array":
            walk(node["items"])

    walk(ask.SCHEMA)
