"""Схема Дел на настоящем PostgreSQL: журнал, версии, права в самой базе, виды для Claude."""

from __future__ import annotations

import psycopg
import pytest

from pravka_dela import db as dela_db


@pytest.fixture()
def dela(dela_url, conn):
    """Чистые Дела: трое пользователей, больше ничего."""
    conn.execute(
        "TRUNCATE crm.history, crm.ops_seen, crm.state, crm.tokens, crm.sessions, crm.login_requests, crm.passkeys, "
        "crm.access_revoked, tasks.suggestions, tasks.comments, tasks.tasks, tasks.labels, crm.interactions, "
        "crm.project_access, crm.projects, crm.users, crm.people, crm.orgs CASCADE"
    )
    with dela_db.session(dela_url, "system", "test") as c:
        c.execute(
            "INSERT INTO crm.users (id, name, role, settings) VALUES "
            "('sasha', 'Саша', 'owner', '{}'), ('natasha', 'Наташа', 'member', '{}'), "
            "('marianna', 'Марианна', 'member', '{\"assign\": \"ask\", \"reminders\": false}')"
        )
    return dela_url


def as_(url, user):
    return dela_db.session(url, user, via="app")


def project(url, user, name, **kw):
    cols = {"name": name, "sphere": "work", "kind": "client", "owner_id": user, **kw}
    with as_(url, user) as c:
        keys = ", ".join(cols)
        marks = ", ".join(["%s"] * len(cols))
        return c.execute(f"INSERT INTO crm.projects ({keys}) VALUES ({marks}) RETURNING id", list(cols.values())).fetchone()["id"]


def task(url, user, title, **kw):
    cols = {"title": title, "owner_id": user, "created_by": user, **kw}
    with as_(url, user) as c:
        keys = ", ".join(cols)
        marks = ", ".join(["%s"] * len(cols))
        return c.execute(f"INSERT INTO tasks.tasks ({keys}) VALUES ({marks}) RETURNING id, num, rev, seq", list(cols.values())).fetchone()


def test_migrate_again_is_harmless(dela, cfg):
    assert dela_db.migrate(cfg.db_url, dela.split("//")[1].split(":")[0], cfg.reader_role) == ["life_dela.sql"]


def test_journal_keeps_who_what_and_only_changed_fields(dela, conn):
    p = project(dela, "sasha", "Додо")
    t = task(dela, "sasha", "Горецкий: подписать договор", project_id=p)
    with as_(dela, "sasha") as c:
        c.execute("UPDATE tasks.tasks SET ball = 'waiting', notes = 'к понедельнику' WHERE id = %s", (t["id"],))
    rows = conn.execute(
        "SELECT actor, via, op, before, after FROM crm.history WHERE entity = 'tasks.tasks' AND entity_id = %s ORDER BY id",
        (str(t["id"]),),
    ).fetchall()
    assert [r[2] for r in rows] == ["insert", "update"]
    actor, via, _, before, after = rows[1]
    assert (actor, via) == ("sasha", "app")
    # Правка пишет только изменившееся, плюс то, что поставили правила (waiting_since).
    assert set(after) == {"ball", "notes", "waiting_since"}
    assert before["ball"] == "mine" and after["ball"] == "waiting"


def test_no_actor_no_write(dela):
    with psycopg.connect(dela) as c:
        c.execute("SELECT set_config('dela.user', 'system', false)")
        with pytest.raises(psycopg.errors.RaiseException, match="dela.actor"):
            c.execute("INSERT INTO crm.orgs (name, owner_id) VALUES ('X', 'sasha')")


def test_rev_and_seq_move_only_on_real_change(dela):
    t = task(dela, "sasha", "Купить кофе")
    with as_(dela, "sasha") as c:
        same = c.execute("UPDATE tasks.tasks SET title = title WHERE id = %s RETURNING rev, seq", (t["id"],)).fetchone()
        assert (same["rev"], same["seq"]) == (t["rev"], t["seq"])
        moved = c.execute("UPDATE tasks.tasks SET title = 'Купить зерно' WHERE id = %s RETURNING rev, seq", (t["id"],)).fetchone()
    assert moved["rev"] == t["rev"] + 1 and moved["seq"] > t["seq"]


def test_done_sets_completed_and_reopen_clears(dela):
    t = task(dela, "sasha", "Продлить Контур")
    with as_(dela, "sasha") as c:
        done = c.execute("UPDATE tasks.tasks SET status = 'done' WHERE id = %s RETURNING completed_at", (t["id"],)).fetchone()
        assert done["completed_at"] is not None
        again = c.execute("UPDATE tasks.tasks SET status = 'open' WHERE id = %s RETURNING completed_at", (t["id"],)).fetchone()
        assert again["completed_at"] is None


def test_project_privacy_and_roles(dela):
    p = project(dela, "sasha", "ПТИЦ")
    t = task(dela, "sasha", "Тимофей: остаток", project_id=p)
    with as_(dela, "natasha") as c:
        assert c.execute("SELECT count(*) AS n FROM tasks.tasks").fetchone()["n"] == 0
        assert c.execute("SELECT count(*) AS n FROM crm.projects").fetchone()["n"] == 0
    with as_(dela, "sasha") as c:
        c.execute("INSERT INTO crm.project_access (project_id, user_id, role, granted_by) VALUES (%s, 'natasha', 'view', 'sasha')", (p,))
    with as_(dela, "natasha") as c:
        assert c.execute("SELECT count(*) AS n FROM tasks.tasks").fetchone()["n"] == 1
        # Только смотреть: правка не проходит молча — строк ноль.
        assert c.execute("UPDATE tasks.tasks SET title = 'x' WHERE id = %s", (t["id"],)).rowcount == 0
    with as_(dela, "sasha") as c:
        c.execute("UPDATE crm.project_access SET role = 'edit' WHERE project_id = %s AND user_id = 'natasha'", (p,))
    with as_(dela, "natasha") as c:
        assert c.execute("UPDATE tasks.tasks SET notes = 'КП в работе' WHERE id = %s", (t["id"],)).rowcount == 1
        # Наташа ставит Саше задачу в открытом ей проекте.
        c.execute(
            "INSERT INTO tasks.tasks (title, owner_id, created_by, project_id) VALUES ('Саша: согласовать КП', 'sasha', 'natasha', %s)",
            (p,),
        )
    # Её приватный проект Саша не видит.
    own = project(dela, "natasha", "Наташино личное", sphere="home", kind="personal")
    task(dela, "natasha", "Своё", project_id=own)
    with as_(dela, "sasha") as c:
        names = {r["name"] for r in c.execute("SELECT name FROM crm.projects")}
        assert names == {"ПТИЦ"}
        assert c.execute("SELECT count(*) AS n FROM tasks.tasks WHERE title = 'Своё'").fetchone()["n"] == 0


def test_inbox_is_owner_only(dela):
    task(dela, "sasha", "Напомнить про онко-тест")
    with as_(dela, "natasha") as c:
        assert c.execute("SELECT count(*) AS n FROM tasks.tasks").fetchone()["n"] == 0


def test_revoked_access_is_recorded(dela, conn):
    p = project(dela, "sasha", "Стеллар")
    with as_(dela, "sasha") as c:
        c.execute("INSERT INTO crm.project_access (project_id, user_id, role, granted_by) VALUES (%s, 'natasha', 'edit', 'sasha')", (p,))
        c.execute("DELETE FROM crm.project_access WHERE project_id = %s", (p,))
    assert conn.execute("SELECT user_id FROM crm.access_revoked").fetchall() == [("natasha",)]


def test_marianna_only_by_consent(dela):
    p = project(dela, "sasha", "Семья", sphere="home", kind="personal")
    with as_(dela, "sasha") as c:
        c.execute("INSERT INTO crm.project_access (project_id, user_id, role, granted_by) VALUES (%s, 'marianna', 'edit', 'sasha')", (p,))
    with pytest.raises(psycopg.errors.RaiseException, match="согласия"):
        task(dela, "sasha", "Марианна: записать Рому", project_id=p, owner_id="marianna")
    # Своё дело в общем проекте Марианна заводит сама.
    task(dela, "marianna", "Записать Рому", project_id=p)


def test_owner_must_see_project(dela):
    p = project(dela, "sasha", "Яблоков")
    with pytest.raises(psycopg.errors.RaiseException, match="не видит проект"):
        with dela_db.session(dela, "system", "test") as c:
            c.execute(
                "INSERT INTO tasks.tasks (title, owner_id, created_by, project_id) VALUES ('x', 'natasha', 'sasha', %s)",
                (p,),
            )


def test_match_name_aliases_people_and_orgs(dela, conn):
    with dela_db.session(dela, "system", "test") as c:
        org = c.execute("INSERT INTO crm.orgs (name, owner_id) VALUES ('ПТИЦ', 'sasha') RETURNING id").fetchone()["id"]
        c.execute(
            "INSERT INTO crm.projects (name, aliases, sphere, kind, owner_id, org_id) VALUES "
            "('ПТИЦ', '{Птиц}', 'work', 'client', 'sasha', %s), ('Додо', '{\"Додо Пицца\"}', 'work', 'client', 'sasha', NULL)",
            (org,),
        )
        c.execute(
            "INSERT INTO crm.people (name, short, aliases, owner_id, org_id) VALUES "
            "('Тимофей', 'Тимофей', '{\"Тимофей Птиц\"}', 'sasha', %s), ('Марианна Цакунова', 'Марианна', '{}', 'sasha', NULL)",
            (org,),
        )

    def m(s):
        r = conn.execute(
            "SELECT p.name, pe.short FROM crm.match_name(%s) x "
            "LEFT JOIN crm.projects p ON p.id = x.project_id LEFT JOIN crm.people pe ON pe.id = x.person_id",
            (s,),
        ).fetchone()
        return tuple(r)

    assert m("додо пицца") == ("Додо", None)
    assert m("Птиц") == ("ПТИЦ", None)
    assert m("Тимофей Птиц") == ("ПТИЦ", "Тимофей")
    assert m("Марианна Цакунова") == (None, "Марианна")
    assert m("Неизвестный") == (None, None)


def test_life_tasks_for_claude_shows_only_owner_view(dela, cfg):
    p = project(dela, "sasha", "Стаффджет", money_default="paid")
    task(dela, "sasha", "Закрыть акт", project_id=p)
    own = project(dela, "natasha", "Наташино", sphere="home", kind="personal")
    task(dela, "natasha", "Секрет", project_id=own)
    with psycopg.connect(cfg.reader_url) as r:
        rows = r.execute("SELECT title, project, money FROM life.tasks").fetchall()
    assert rows == [("Закрыть акт", "Стаффджет", "paid")]


def test_day_tool_shows_dela_done_created_and_timeline(dela, cfg):
    """«Что у меня было» в чате: сутки архива видят и работу — сделанное, заведённое, контакты."""
    import datetime as dt

    from pravka_archive import tools
    from pravka_dela import store

    p = project(dela, "sasha", "Стаффджет")
    run = lambda *o: store.apply_ops(dela, "sasha", list(o), "web")["results"]  # noqa: E731
    a = run({"op": "task.create", "task": {"title": "Закрыть акт", "project_id": str(p)}})[0]["task"]
    run({"op": "task.done", "id": a["id"]},
        {"op": "task.create", "task": {"title": "Иван: прислать модель", "source": "meeting"}},
        {"op": "interaction.add", "data": {"at": dt.datetime.now(dt.timezone.utc).isoformat(), "kind": "call",
                                           "summary": "Обсудили акт и оплату", "project_id": str(p), "source": "web"}})
    today = dt.datetime.now(dt.timezone(dt.timedelta(hours=3))).date().isoformat()
    out = tools.day(cfg, today)
    assert "Дела: сделано 1, заведено 2:" in out
    assert f"сделано #{a['num']} Закрыть акт · Стаффджет" in out
    assert "Иван: прислать модель · из встречи" in out
    assert "Хронология контактов: 1:" in out and "call · Стаффджет — Обсудили акт и оплату" in out


def test_work_time_takes_project_from_entry_then_task_then_alias(dela, clean, batch, cfg):
    """Запись Засечки из дела несёт task и project (телефон, 03.10.2026): связь
    сильнее сопоставления текста клиента; без project — проект её дела."""
    from pravka_archive.ingest import ingest_batch

    beta = project(dela, "sasha", "Бета Групп", aliases=["Тестовый клиент"])
    gamma = project(dela, "sasha", "Гамма")
    t = task(dela, "sasha", "Иван: прислать модель", project_id=gamma)
    day = next(e for e in batch["events"] if e["kind"] == "zasechka.day")
    entries = day["data"]["entries"]
    for e in entries:
        if e.get("task"):
            # Запись контракта с клиентом «Тестовый клиент»: проект записи — Гамма,
            # хотя текст клиента по алиасу — Бета.
            e["task"] = str(t["id"])
            e["project"] = str(gamma)
        elif e["title"] == "завтрак с детьми":
            e["task"] = str(t["id"])  # только дело — проект берётся у него
    ingest_batch(clean, batch, "sasha")
    with psycopg.connect(cfg.reader_url) as r:
        rows = r.execute("SELECT title, project, task_num FROM life.work_time ORDER BY start_local").fetchall()
        ids = r.execute("SELECT task_id, project_id FROM life.entries WHERE title = 'разбор отчётности'").fetchone()
    assert ("разбор отчётности", "Гамма", t["num"]) in rows
    assert ("завтрак с детьми", "Гамма", t["num"]) in rows
    assert ids == (str(t["id"]), str(gamma))
    # Без связи — по-старому, алиасом клиента.
    for e in entries:
        e.pop("task", None)
        e.pop("project", None)
    day["eid"] = day["eid"] + "b"
    day["seq"] = day["seq"] + 1000
    ingest_batch(clean, batch, "sasha")
    with psycopg.connect(cfg.reader_url) as r:
        row = r.execute("SELECT project, task_num FROM life.work_time WHERE title = 'разбор отчётности'").fetchone()
    assert row == ("Бета Групп", None)
    assert beta
