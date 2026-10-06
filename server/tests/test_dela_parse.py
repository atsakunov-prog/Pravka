"""Разбор текста Claude: справочник в промпте, имена в id, дела и заметки в базе.

Claude подменён: проверяется всё вокруг вызова, а не сама модель.
"""

from __future__ import annotations

import time

from starlette.testclient import TestClient

from pravka_dela import api, parse, tokens
from pravka_dela.config import Config
from test_dela_schema import dela, project  # noqa: F401


def _people(url):
    with parse.db.session(url, "system", "test") as c:
        c.execute("INSERT INTO crm.people (name, short, aliases, owner_id) VALUES ('Иван Петров', 'Иван', '{Ваня}', 'sasha')")
        c.execute("INSERT INTO tasks.labels (name) VALUES ('звонок')")


def test_parse_makes_tasks_and_notes(dela):
    p = project(dela, "sasha", "Бета Групп", aliases=["Бета"])
    _people(dela)
    seen = {}

    def fake(system, user_text):
        seen["system"], seen["user"] = system, user_text
        return {
            "tasks": [
                {"title": "Иван: прислать модель", "notes": "", "project": "Бета", "person": "Ваня", "ball": "waiting",
                 "due": "2026-10-10", "estimate_min": 0, "money": "", "want": False, "labels": ["звонок", "выдумка"]},
                {"title": "Позвонить в банк", "notes": "по ковенантам", "project": "Гамма", "person": "Пётр", "ball": "agenda",
                 "due": "завтра", "estimate_min": 10, "money": "paid", "want": True, "labels": []},
            ],
            "notes": [{"text": "Комитет пройден", "project": "Бета Групп", "person": "Иван"}],
        }

    out = parse.run(dela, "sasha", "жду от Вани модель к десятому, позвонить в банк", {}, "", None, ask_fn=fake)
    assert "— Бета Групп (Бета) · работа · клиент" in seen["system"]
    assert "— Иван (Иван Петров, Ваня)" in seen["system"] and "звонок" in seen["system"]
    assert "Сегодня 20" in seen["system"] and "Сейчас говорит не Саша" not in seen["system"]
    assert seen["user"].endswith("позвонить в банк")
    a, b = out["tasks"]
    assert a["project_id"] == str(p) and a["person_short"] == "Иван" and a["ball"] == "waiting"
    assert a["due_date"] == "2026-10-10" and a["labels"] == ["звонок"] and a["source"] == "web"
    # Неизвестные проект и человек — пусто; без человека мяч остаётся у себя; срок словами не принят.
    assert b["project_id"] is None and b["person_id"] is None and b["ball"] == "mine" and b["due_date"] is None
    assert b["notes"] == "по ковенантам" and b["estimate_min"] == 10 and b["money"] == "paid" and b["want"] is True
    assert out["notes"][0]["kind"] == "note" and out["notes"][0]["source"] == "raznoska"
    assert out["notes"][0]["project_id"] == str(p) and len(out["notes"][0]["person_ids"]) == 1
    assert out["errors"] == []


def test_parse_defaults_and_speaker(dela):
    p = project(dela, "natasha", "Дельта")
    hidden = project(dela, "sasha", "Секретный клиент")

    def fake(system, user_text):
        assert "Сейчас говорит не Саша, а Наташа" in system
        assert "Секретный" not in system  # чужой проект Наташа не видит — и Claude тоже
        return {"tasks": [{"title": "Сверка", "notes": "", "project": "", "person": "", "ball": "mine", "due": "",
                           "estimate_min": 0, "money": "", "want": False, "labels": []}], "notes": []}

    out = parse.run(dela, "natasha", "сверка", {"project_id": str(p)}, "", None, ask_fn=fake)
    assert out["tasks"][0]["project_id"] == str(p) and out["tasks"][0]["owner_id"] == "natasha"
    # Подставить чужой проект через «по умолчанию» нельзя: база не даст.
    out = parse.run(dela, "natasha", "сверка", {"project_id": str(hidden)}, "", None, ask_fn=fake)
    assert out["tasks"] == [] and out["errors"]


def _wait(client, h, jid):
    for _ in range(100):
        r = client.get(f"/api/parse/{jid}", headers=h).json()
        if r.get("status") != "run":
            return r
        time.sleep(0.05)
    raise AssertionError("разбор не закончился")


def test_parse_api(dela, monkeypatch):
    h = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'device', 'телефон')}"}
    nat = {"Authorization": f"Bearer {tokens.issue(dela, 'natasha', 'device', 'телефон Наташи')}"}
    client = TestClient(api.build(Config(db_url=dela)))
    assert client.get("/api/me", headers=h).json()["claude"] is False
    r = client.post("/api/parse", headers=h, json={"text": "позвонить"})
    assert r.status_code == 422 and "нет ключа" in r.json()["error"]

    def fake_ask(cl, system, user_text):
        if "сломай" in user_text:
            raise ConnectionError("xray лежит")
        return {"tasks": [{"title": "Позвонить", "notes": "", "project": "", "person": "", "ball": "mine", "due": "",
                           "estimate_min": 5, "money": "", "want": False, "labels": []}], "notes": []}

    monkeypatch.setattr(parse, "client", lambda key, proxy: object())
    monkeypatch.setattr(parse, "ask", fake_ask)
    with TestClient(api.build(Config(db_url=dela, anthropic_key="sk-test"))) as client:
        assert client.get("/api/me", headers=h).json()["claude"] is True
        assert client.post("/api/parse", headers=h, json={"text": "  "}).status_code == 422
        assert client.post("/api/parse", headers=h, json={"text": "x", "project_id": "nope"}).status_code == 400
        assert client.post("/api/parse", json={"text": "x"}).status_code == 401
        r = client.post("/api/parse", headers=h, json={"text": "позвонить"})
        assert r.status_code == 202
        jid = r.json()["job"]
        assert client.get(f"/api/parse/{jid}", headers=nat).status_code == 404  # чужое задание не видно
        done = _wait(client, h, jid)
        assert done["status"] == "done" and done["tasks"][0]["title"] == "Позвонить" and done["tasks"][0]["estimate_min"] == 5
        bad = _wait(client, h, client.post("/api/parse", headers=h, json={"text": "сломай"}).json()["job"])
        assert bad["status"] == "error" and "ConnectionError" in bad["error"]
        assert client.get("/api/parse/nope", headers=h).status_code == 404


def test_schema_is_strict():
    """Структурный ответ: каждое поле обязательно, лишних нет — иначе API отвергнет схему."""
    def walk(node):
        if node.get("type") == "object":
            assert node["additionalProperties"] is False
            assert set(node["required"]) == set(node["properties"])
            for v in node["properties"].values():
                walk(v)
        if node.get("type") == "array":
            walk(node["items"])

    walk(parse.schema())
