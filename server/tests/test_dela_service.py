"""API службы, инструменты Claude и перенос — на настоящем PostgreSQL.

Данные выдуманные: репозиторий публичный, живые имена — только в файле
решений на компе владельца.
"""

from __future__ import annotations

import json
from types import SimpleNamespace

from starlette.testclient import TestClient

from pravka_dela import api, importer, mcp_tools, tokens
from pravka_dela.config import Config
from test_dela_schema import dela, project  # noqa: F401


def test_api_token_sync_ops_view(dela):
    client = TestClient(api.build(Config(db_url=dela)))
    assert client.get("/health").json()["service"] == "pravka-dela"
    assert client.get("/api/sync").status_code == 401
    tok = tokens.issue(dela, "sasha", "device", "телефон")
    h = {"Authorization": f"Bearer {tok}"}
    assert client.get("/api/me", headers=h).json()["user"] == "sasha"
    r = client.post("/api/ops", headers=h, json={"ops": [
        {"op": "task.create", "op_id": "11111111-1111-4111-8111-111111111111", "task": {"title": "Альфа: позвонить", "due_date": "2026-10-03"}},
    ]}).json()
    assert r["results"][0]["ok"], r
    num = r["results"][0]["task"]["num"]
    s = client.get("/api/sync?since=0", headers=h).json()
    assert s["full"] and [t["title"] for t in s["tasks"]] == ["Альфа: позвонить"]
    card = client.get(f"/api/task/{num}", headers=h).json()
    assert card["task"]["num"] == num and card["history"][0]["op"] == "insert" and card["history"][0]["via"] == "app"
    assert client.get("/api/view/nope", headers=h).status_code == 400
    tokens.revoke(dela, "телефон")
    assert client.get("/api/me", headers=h).status_code == 401


def test_mcp_tools_round_trip(dela):
    p = project(dela, "sasha", "Бета Групп", aliases=["Бета"], money_default="paid")
    with mcp_tools.db.session(dela, "system", "test") as c:
        c.execute("INSERT INTO crm.people (name, short, aliases, owner_id) VALUES ('Иван Петров', 'Иван', '{Ваня}', 'sasha')")
        c.execute("INSERT INTO crm.deals (project_id, name, stage) VALUES (%s, 'Бета: фонды', 'active')", (p,))
    out = mcp_tools.add(dela, title="Иван: прислать модель", project="бета", deal="фонды", person="Ваня", ball="waiting", due_date="2026-10-10")
    assert out.startswith("Записал: #") and "Бета Групп / Бета: фонды" in out and "жду Иван" in out, out
    num = out.split("#", 1)[1].split(" ", 1)[0]
    assert "просрочено" not in out
    ch = mcp_tools.change(dela, num, status="done", comment="Прислал вечером")
    assert "сделано" in ch, ch
    card = mcp_tools.card(dela, num)
    assert "Прислал вечером" in card and "Журнал:" in card
    assert "не определился" in mcp_tools.add(dela, title="x", project="Гамма")
    v = mcp_tools.view(dela, "project", project="Бета")
    assert "сделка: Бета: фонды · active" in v and "Закрытые недавно (1)" in v
    assert mcp_tools.note(dela, "Созвонились, ждём модель", kind="call", project="Бета", people=["Иван"]) == "Записал в хронологию."


def test_mcp_decide_batch(dela):
    from pravka_dela import store

    for title in ("Иван: КП", "Мне: подготовить тизер"):
        store.apply_ops(dela, "system", [{"op": "suggestion.create", "suggestion": {
            "for_user": "sasha", "kind": "create", "source": "meeting", "source_ref": "meeting:7",
            "batch_ref": "meeting:7", "batch_title": "Встреча 7", "payload": {"title": title}}}], via="meetings")
    assert "Встреча 7:" in mcp_tools.view(dela, "new")
    out = mcp_tools.decide(dela, "batch:meeting:7", "accept")
    assert out.startswith("Разобрано: 2 из 2."), out


def _write(tmp, name, data):
    p = tmp / name
    p.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    return p


def test_import_dry_and_apply_idempotent(dela, tmp_path):
    todoist = _write(tmp_path, "t.json", {
        "projects": [{"id": "in", "name": "Inbox", "inbox": True}, {"id": "p1", "name": "Альфа"},
                     {"id": "p2", "name": "Альфа: аудит", "folder": "f"}, {"id": "p3", "name": "Семья"}],
        "labels": ["жду", "коля", "быстр", "личное", "звонок"],
        "tasks": [
            {"id": "t1", "content": "Коля: прислать договор", "description": "", "dueDate": "2026-09-07", "priority": "p1",
             "projectId": "p1", "labels": ["жду", "коля"], "addedAt": "2026-09-05T10:00:00Z"},
            {"id": "t2", "content": "Петров: позвонить", "description": "срочно", "priority": "p2",
             "projectId": "p2", "labels": ["быстр", "звонок"], "addedAt": "2026-09-05T10:00:00Z"},
            {"id": "t3", "content": "Купить кофе", "description": "", "priority": "p4", "projectId": "p3",
             "labels": ["личное"], "addedAt": "2026-09-05T10:00:00Z"},
            {"id": "t4", "content": "Поздравить Колю", "description": "", "dueDate": "2027-01-02", "recurring": "every 2 jan",
             "priority": "p4", "projectId": "in", "labels": [], "addedAt": "2026-01-01T10:00:00Z"},
        ]})
    folder = tmp_path / "notion"
    folder.mkdir()
    _write(folder, "notion-contacts.json", {"results": [
        {"url": "n:c1", "Имя": "Николай Сидоров", "Компания": "Альфа", "Контакты": "+7 900 111-22-33, kolya@alfa.ru, @kolya_s",
         "Теплота": "🔥 Раз в месяц", "Хаб": "__YES__", "date:Посл. контакт:start": "2026-04-01"},
        {"url": "n:c2", "Имя": "Николай Сидоров", "Компания": "Альфа", "Контакты": ""},
    ]})
    _write(folder, "notion-deals.json", {"results": [
        {"url": "n:d1", "Название": "Альфа: аудит", "Статус": "В работе", "Деньги": 500000, "Участники": "[\"n:c1\"]",
         "Ответственный ЗФ": "Саша", "Следующий шаг": "Ждать ТЗ"},
        {"url": "n:d2", "Название": "Омега", "Статус": "Архив"},
    ]})
    clients = _write(tmp_path, "c.json", {"clients": [["альфа", 3, 2.5], ["Неведомо", 1, 1.0]]})
    plan_file = _write(tmp_path, "dec.json", {
        "users": [{"id": "sasha", "name": "Саша", "role": "owner", "person": "Саша Тестов"}],
        "people": [{"name": "Саша Тестов", "short": "Саша", "user": "sasha"},
                   {"name": "Николай Сидоров", "short": "Коля", "aliases": ["Коля"]}],
        "label_people": {"коля": "Коля"}, "personal_project": "Личное",
        "project_kinds": {"Семья": "personal", "Личное": "personal"},
        "merge_projects": {"Альфа: аудит": "Альфа"},
        "birthdays": {"t4": {"person": "Коля", "day": 2, "month": 1}},
    })

    # аргументы команды import
    A = SimpleNamespace(todoist=str(todoist), notion=str(folder), clients=str(clients), plan=str(plan_file),
                        apply=False, report=str(tmp_path / "r.md"))

    assert importer.run(Config(db_url=dela), A) == 0
    rep = (tmp_path / "r.md").read_text(encoding="utf-8")
    assert "Альфа | client | paid | 1 | 2" in rep  # Альфа + слитый «Альфа: аудит», сделка в работе
    assert "Петров — из «Петров: позвонить»" in rep
    assert "Поздравить Колю → Николай Сидоров, 02.01" in rep
    assert "«Неведомо»" in rep and "Омега" in rep
    with mcp_tools.db.session(dela, "system", "t") as c:
        assert c.execute("SELECT count(*) AS n FROM tasks.tasks").fetchone()["n"] == 0  # сухой прогон

    A.apply = True
    importer.run(Config(db_url=dela), A)
    importer.run(Config(db_url=dela), A)  # повтор — те же строки, не дубли
    with mcp_tools.db.session(dela, "system", "t") as c:
        tasks = {r["title"]: r for r in c.execute("SELECT * FROM tasks.v_tasks")}
        assert set(tasks) == {"Коля: прислать договор", "Петров: позвонить", "Купить кофе"}
        t1 = tasks["Коля: прислать договор"]
        assert (t1["ball"], t1["person_short"], t1["money"], t1["money_eff"]) == ("waiting", "Коля", None, "paid")
        t2 = tasks["Петров: позвонить"]
        assert t2["deal_name"] == "Альфа: аудит" and t2["estimate_min"] == 10 and t2["labels"] == ["звонок"]
        assert t2["money"] == "potential"
        assert tasks["Купить кофе"]["project_name"] == "Личное"
        kolya = c.execute("SELECT * FROM crm.people WHERE short = 'Коля'").fetchone()
        assert kolya["phones"] == ["+79001112233"] and kolya["emails"] == ["kolya@alfa.ru"]
        assert kolya["telegram_username"] == "kolya_s" and kolya["cadence"] == "month" and kolya["hub"]
        assert (kolya["birth_day"], kolya["birth_month"]) == (2, 1)
        assert c.execute("SELECT count(*) AS n FROM crm.people WHERE name = 'Николай Сидоров'").fetchone()["n"] == 1
        omega = c.execute("SELECT archived_at FROM crm.projects WHERE name = 'Омега'").fetchone()
        assert omega["archived_at"] is not None
        hist = c.execute("SELECT DISTINCT actor, via FROM crm.history WHERE entity = 'tasks.tasks'").fetchall()
        assert [(h["actor"], h["via"]) for h in hist] == [("svc:import", "import")]


def test_todoist_bridge_takes_only_new(dela):
    import httpx

    from pravka_dela import bridge, store

    p = project(dela, "sasha", "Дельта", aliases=["Дельта ООО"])
    with mcp_tools.db.session(dela, "system", "t") as c:
        c.execute("INSERT INTO crm.people (name, short, aliases, owner_id) VALUES ('Пётр Сомов', 'Пётр', '{}', 'sasha')")
    bridge.save_decisions(dela, {"label_people": {"петя": "Пётр"}, "title_people": {}, "personal_project": None})

    def handler(request):
        path = request.url.path.rsplit("/", 1)[-1]
        data = {
            "projects": [{"id": "pi", "name": "Inbox", "inbox_project": True}, {"id": "pd", "name": "Дельта ООО"}],
            "tasks": [
                {"id": "a1", "content": "Пётр: прислать счёт", "project_id": "pd", "labels": ["жду", "петя"],
                 "priority": 4, "due": {"date": "2026-10-06"}},
                {"id": "a2", "content": "Купить марки", "project_id": "pi", "labels": ["быстр"], "priority": 1},
            ],
        }[path]
        return httpx.Response(200, json={"results": data, "next_cursor": None})

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        data = bridge.fetch("t" * 40, client)
    assert sorted(bridge.pull_new(dela, data)) == ["Купить марки", "Пётр: прислать счёт"]
    # Уже перенесённое закрыли в Делах — мост его не воскрешает и не дублирует.
    tid = importer.sid("task", "todoist", "a1")
    store.apply_ops(dela, "sasha", [{"op": "task.done", "id": tid}], via="app")
    assert bridge.pull_new(dela, data) == []
    with mcp_tools.db.session(dela, "system", "t") as c:
        t = c.execute("SELECT * FROM tasks.v_tasks WHERE id = %s", (tid,)).fetchone()
        assert (t["status"], t["project_id"], t["person_short"], t["ball"], t["money"]) == ("done", p, "Пётр", "waiting", "paid")
        assert c.execute("SELECT estimate_min, project_id FROM tasks.tasks WHERE title = 'Купить марки'").fetchone() == {"estimate_min": 10, "project_id": None}
