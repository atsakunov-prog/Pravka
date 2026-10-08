"""CRM поверх Дел: доступ команды по ответственности, деньги только тем, кому открыты,
воронка с итогом, оплаты, хронология без удаления, виды."""

from __future__ import annotations

import datetime as dt

import psycopg
import pytest

from pravka_dela import crm, store
from pravka_dela import db as dela_db
from test_dela_schema import as_, dela, project, task  # noqa: F401  (фикстура и помощники)


def ops(url, user, *items, via="web"):
    return store.apply_ops(url, user, list(items), via)["results"]


@pytest.fixture()
def team(dela):
    """Команда: Наташа видит всех клиентов и деньги, Алёна — только свои сделки и без денег."""
    with dela_db.session(dela, "system", "test") as c:
        alena = c.execute("INSERT INTO crm.people (name, short, owner_id) VALUES ('Алёна Юристова', 'Алёна', 'sasha') RETURNING id").fetchone()["id"]
        natasha = c.execute("INSERT INTO crm.people (name, short, owner_id) VALUES ('Наталья Операционная', 'Наташа', 'sasha') RETURNING id").fetchone()["id"]
        c.execute("INSERT INTO crm.users (id, name, role, person_id, clients) VALUES ('alena', 'Алёна', 'member', %s, 'team')", (alena,))
        c.execute("UPDATE crm.users SET person_id = %s, clients = 'all', sees_money = true WHERE id = 'natasha'", (natasha,))
    return {"url": dela, "alena": alena, "natasha": natasha}


def deal(url, user, project_id, name, **kw):
    r = ops(url, user, {"op": "deal.create", "data": {"project_id": str(project_id), "name": name, **kw}})[0]
    assert r["ok"], r
    return r["row"]


def test_owner_sees_all_clients_and_money(dela):
    # Владелец видит всех клиентов и деньги по роли, что бы ни стояло в clients.
    p = project(dela, "natasha", "Клиент Наташи")
    with as_(dela, "sasha") as c:
        assert c.execute("SELECT 1 FROM crm.projects WHERE id = %s", (p,)).fetchone()
        assert c.execute("SELECT crm.money_ok('sasha') AS x").fetchone()["x"]
        assert not c.execute("SELECT crm.money_ok('marianna') AS x").fetchone()["x"]


def test_team_member_sees_client_only_while_on_deal(team):
    url = team["url"]
    p = project(url, "sasha", "Омега")
    t = task(url, "sasha", "Кротов: договор", project_id=p)
    with as_(url, "alena") as c:
        assert not c.execute("SELECT 1 FROM crm.projects WHERE id = %s", (p,)).fetchone()
        assert not c.execute("SELECT 1 FROM tasks.tasks WHERE id = %s", (t["id"],)).fetchone()
    d = deal(url, "sasha", p, "Омега: M&A", lead_person_id=str(team["alena"]), fee_kop=50_000_000)
    with as_(url, "alena") as c:
        assert c.execute("SELECT 1 FROM crm.projects WHERE id = %s", (p,)).fetchone()
        assert c.execute("SELECT 1 FROM tasks.tasks WHERE id = %s", (t["id"],)).fetchone()
        shown = c.execute("SELECT fee_kop, my_view FROM crm.v_deals WHERE id = %s", (d["id"],)).fetchone()
        assert shown["fee_kop"] is None
    # Без денег — ни увидеть, ни поменять гонорар; стадию — можно.
    bad = ops(url, "alena", {"op": "deal.set", "id": d["id"], "set": {"fee_kop": 1}})[0]
    assert not bad["ok"] and "деньги" in bad["error"]
    ok = ops(url, "alena", {"op": "deal.set", "id": d["id"], "set": {"stage": "active"}})[0]
    assert ok["ok"] and ok["row"]["fee_kop"] is None  # ответ тоже без денег
    # Сняли с проекта — клиент пропал.
    ops(url, "sasha", {"op": "deal.set", "id": d["id"], "set": {"lead_person_id": None}})
    with as_(url, "alena") as c:
        assert not c.execute("SELECT 1 FROM crm.projects WHERE id = %s", (p,)).fetchone()


def test_all_clients_but_not_personal_projects(team):
    url = team["url"]
    client = project(url, "sasha", "Сигма")
    family = project(url, "sasha", "Семья", sphere="home", kind="personal")
    with as_(url, "natasha") as c:
        seen = {r["id"] for r in c.execute("SELECT id FROM crm.projects").fetchall()}
    assert client in seen and family not in seen
    with as_(url, "marianna") as c:
        assert not c.execute("SELECT 1 FROM crm.projects WHERE id = %s", (client,)).fetchone()


def test_people_cannot_raise_their_own_access(team):
    with pytest.raises(psycopg.errors.RaiseException, match="меняет только владелец"):
        with as_(team["url"], "alena") as c:
            c.execute("UPDATE crm.users SET clients = 'all' WHERE id = 'alena'")
    r = ops(team["url"], "alena", {"op": "user.settings", "settings": {"time_ignore": ["Каппа"]}})[0]
    assert r["ok"] and r["row"]["settings"]["time_ignore"] == ["Каппа"]


def test_payments_only_for_money_people(team):
    url = team["url"]
    p = project(url, "sasha", "Дельта")
    d = deal(url, "sasha", p, "Дельта: юрконсалтинг", lead_person_id=str(team["alena"]), fee_kop=25_000_000, stage="mandate")
    r = ops(url, "natasha", {"op": "payment.create", "data": {"deal_id": d["id"], "kind": "advance", "amount_kop": 10_000_000,
                                                               "due_on": "2026-10-10"}})[0]
    assert r["ok"], r
    bad = ops(url, "alena", {"op": "payment.create", "data": {"deal_id": d["id"], "amount_kop": 1}})[0]
    assert not bad["ok"]
    with as_(url, "alena") as c:
        assert not c.execute("SELECT 1 FROM crm.payments").fetchone()
    sync = store.sync(url, "alena", 0)
    assert sync["payments"] == [] and all(x["fee_kop"] is None for x in sync["deals"])
    assert store.sync(url, "natasha", 0)["payments"][0]["amount_kop"] == 10_000_000


def test_archive_keeps_outcome_and_date_reopen_clears(dela):
    p = project(dela, "sasha", "Эпсилон")
    d = deal(dela, "sasha", p, "Эпсилон: участки")
    won = ops(dela, "sasha", {"op": "deal.set", "id": d["id"], "set": {"outcome": "won"}})[0]
    assert won["row"]["stage"] == "archive"  # итог сам уводит в архив
    ops(dela, "sasha", {"op": "deal.set", "id": d["id"], "set": {"stage": "proposal"}})
    r = ops(dela, "sasha", {"op": "deal.set", "id": d["id"], "set": {"stage": "archive", "outcome": "lost", "lost_reason": "дорого"}})[0]
    assert r["ok"] and r["row"]["closed_on"] and r["row"]["outcome"] == "lost"
    r = ops(dela, "sasha", {"op": "deal.set", "id": d["id"], "set": {"stage": "lead"}})[0]
    assert r["row"]["outcome"] is None and r["row"]["closed_on"] is None and r["row"]["lost_reason"] is None


def test_pipeline_weights_and_money_totals(dela):
    p = project(dela, "sasha", "Альфа")
    lead = deal(dela, "sasha", p, "Альфа: лид", fee_kop=100_000_00)
    signed = deal(dela, "sasha", p, "Альфа: мандат", fee_kop=500_000_00, stage="mandate")
    task(dela, "sasha", "Альфа: счёт на аванс", project_id=p, deal_id=signed["id"])
    today = dt.date.today()
    ops(dela, "sasha",
        {"op": "payment.create", "data": {"deal_id": signed["id"], "amount_kop": 200_000_00, "invoiced_on": str(today - dt.timedelta(days=20)),
                                           "due_on": str(today - dt.timedelta(days=5)), "kind": "advance"}},
        {"op": "payment.create", "data": {"deal_id": signed["id"], "amount_kop": 100_000_00, "paid_on": str(today), "kind": "stage"}})
    v = store.view(dela, "sasha", "pipeline")
    by = {d["name"]: d for d in v["deals"]}
    assert by["Альфа: лид"]["weighted_kop"] == 10_000_00  # 10 % по стадии
    assert by["Альфа: лид"]["stale"]  # нет ни одного дела
    assert by["Альфа: мандат"]["to_get_kop"] == 400_000_00 and not by["Альфа: мандат"]["stale"]
    assert by["Альфа: мандат"]["next_task"]["title"] == "Альфа: счёт на аванс"
    m = store.view(dela, "sasha", "money")
    assert len(m["overdue"]) == 1 and m["totals"]["receivable"] == 200_000_00
    assert m["totals"]["paid_month"] == 100_000_00
    assert [x["name"] for x in m["unplanned"]] == ["Альфа: мандат"]  # 500 по договору, расписано 300
    with pytest.raises(store.OpError):
        store.view(dela, "marianna", "money")
    assert lead["id"]


def test_timeline_soft_delete_and_card(team):
    url = team["url"]
    p = project(url, "sasha", "Зета")
    d = deal(url, "sasha", p, "Зета: финмодель", lead_person_id=str(team["alena"]), fee_kop=330_000_00)
    r = ops(url, "sasha", {"op": "interaction.add", "data": {"at": "2026-10-01T12:00:00+03:00", "kind": "call", "summary": "Созвон по модели",
                                                             "project_id": str(p), "deal_id": d["id"], "duration_min": 30}})[0]
    assert r["ok"], r
    ops(url, "sasha", {"op": "deal.set", "id": d["id"], "set": {"stage": "active", "fee_kop": 300_000_00}})
    card = store.view(url, "sasha", "deal", deal_id=d["id"])
    assert [i["summary"] for i in card["timeline"]] == ["Созвон по модели"]
    assert any("fee_kop" in (h["after"] or {}) for h in card["history"])
    # Алёна видит хронологию и стадии, но не деньги в журнале.
    card_a = store.view(url, "alena", "deal", deal_id=d["id"])
    assert card_a["timeline"] and not any("fee_kop" in (h["after"] or {}) for h in card_a["history"])
    assert ops(url, "sasha", {"op": "interaction.delete", "id": r["row"]["id"]})[0]["ok"]
    assert store.view(url, "sasha", "deal", deal_id=d["id"])["timeline"] == []
    with pytest.raises(store.OpError):
        store.view(url, "marianna", "deal", deal_id=d["id"])


def test_recent_timeline_for_meetings_input(team):
    """Вид timeline — вход сверки встреч и дайджеста: по три свежих записи на клиента, давнее и
    убранное не видно, остальным — по их доступу к клиентам."""
    url = team["url"]
    now = dt.datetime.now(dt.timezone.utc)
    p, q = project(url, "sasha", "Эта"), project(url, "sasha", "Йота")
    add = lambda pid, days, text: ops(url, "sasha", {"op": "interaction.add", "data": {  # noqa: E731
        "at": (now - dt.timedelta(days=days)).isoformat(), "kind": "meeting", "summary": text, "project_id": str(pid)}})[0]["row"]
    for i in range(5):
        add(p, i + 1, f"Эта {i + 1}")
    ops(url, "sasha", {"op": "interaction.delete", "id": add(p, 0, "Эта убрано")["id"]})
    add(q, 2, "Йота свежая")
    add(q, 90, "Йота давняя")
    got = [(i["project_name"], i["summary"]) for i in store.view(url, "sasha", "timeline")["items"]]
    assert [s for n, s in got if n == "Эта"] == ["Эта 1", "Эта 2", "Эта 3"]
    assert [s for n, s in got if n == "Йота"] == ["Йота свежая"]
    assert store.view(url, "alena", "timeline")["items"] == []


def test_clients_ties_and_time_views_answer(team):
    url = team["url"]
    p = project(url, "sasha", "Йота")
    with dela_db.session(url, "system", "test") as c:
        c.execute("INSERT INTO crm.people (name, short, owner_id, cadence) VALUES ('Илья Друг', 'Илья', 'sasha', 'month')")
    v = store.view(url, "sasha", "clients")
    assert [c["name"] for c in v["clients"]] == ["Йота"] and v["owner"] and "unmatched" in v
    assert "unmatched" not in store.view(url, "natasha", "clients")  # лента — только владельцу
    ties = store.view(url, "sasha", "ties")["people"]
    assert ties[0]["name"] == "Илья Друг" and ties[0]["due"]  # ни разу не говорили — пора
    assert store.view(url, "sasha", "client", project_id=str(p))["project"]["name"] == "Йота"


def test_notion_log_import_links_people_deals_and_prefix(dela, tmp_path):
    from pravka_dela import importer

    p = project(dela, "sasha", "Лямбда")
    with dela_db.session(dela, "system", "test") as c:
        den = c.execute("INSERT INTO crm.people (name, short, owner_id, notion_id) VALUES ('Пётр Лямбдин', 'Пётр', 'sasha', "
                        "'https://app.notion.com/p/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa') RETURNING id").fetchone()["id"]
        d = c.execute("INSERT INTO crm.deals (project_id, name, notion_id) VALUES (%s, 'Лямбда: финансирование', "
                      "'https://app.notion.com/p/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb') RETURNING id", (p,)).fetchone()["id"]
        c.execute("INSERT INTO crm.interactions (at, kind, summary, person_ids, source, owner_id) VALUES "
                  "('2026-04-01 12:00+03', 'other', %s, %s, 'import', 'sasha')", (importer.PLACEHOLDER, [den]))
    rows = [
        {"id": "c" * 32, "date": "2026-04-07", "is_datetime": 0, "src": "Дайджест", "kind": "Telegram",
         "summary": "Фонд Ро — нет", "next": "Ответить по компенсации", "contacts": ["a" * 32], "deals": ["b" * 32]},
        {"id": "d" * 32, "date": "2026-04-08", "is_datetime": 0, "src": "Дайджест", "kind": "Zoom",
         "summary": "Лямбда/Пётр: созвон — 4 направления", "next": None, "contacts": [], "deals": []},
        {"id": "e" * 32, "date": "2026-04-09", "is_datetime": 0, "src": "Дайджест", "kind": "Telegram",
         "summary": "Непонятно о ком", "next": None, "contacts": [], "deals": []},
    ]
    f = tmp_path / "notion-log.json"
    f.write_text(__import__("json").dumps({"results": rows}, ensure_ascii=False), encoding="utf-8")
    dry = importer.notion_log(dela, f, False)
    assert "Непонятно о ком" in dry
    with as_(dela, "sasha") as c:
        assert not c.execute("SELECT 1 FROM crm.interactions WHERE source = 'notion'").fetchone()
    importer.notion_log(dela, f, True)
    importer.notion_log(dela, f, True)  # повтор безвреден
    with as_(dela, "sasha") as c:
        got = c.execute("SELECT summary, kind, deal_id, project_id, person_ids, next_step FROM crm.interactions "
                        "WHERE source = 'notion' ORDER BY at").fetchall()
        assert len(got) == 3
        assert got[0]["deal_id"] == d and got[0]["project_id"] == p and got[0]["person_ids"] == [den]
        assert got[1]["project_id"] == p and got[1]["person_ids"] == [den] and got[1]["kind"] == "zoom"
        assert got[2]["project_id"] is None
        assert c.execute("SELECT deleted_at FROM crm.interactions WHERE source = 'import'").fetchone()["deleted_at"]


def test_claude_tools_deal_payment_and_views(team):
    from pravka_dela import crm_tools

    url = team["url"]
    project(url, "sasha", "Тета.Про")
    out = crm_tools.deal(url, "Тета", name="Тета.Про: оценка", stage="mandate", lead="Алёна", fee_rub=350000,
                         deal_type="M&A", fee_kind="fixed")
    assert out.startswith("Завёл:") and "350 000 ₽" in out and "ведёт Алёна" in out, out
    assert "НЕТ следующего дела" in out
    pay = crm_tools.payment(url, "Тета", "оценка", amount_rub=175000, kind="advance", invoiced_on="2026-10-01")
    assert pay.startswith("Оплата") and "175 000 ₽" in pay, pay
    pid = pay.split("[", 1)[1][:8]
    assert "получено" in crm_tools.payment(url, "Тета", "оценка", payment=pid, paid_on="2026-10-03")
    won = crm_tools.deal(url, "Тета", deal="оценка", outcome="won")
    assert "архив — выиграли" in won, won
    for v in ("pipeline", "clients", "ties", "money"):
        assert crm_tools.view(url, v).startswith("CRM"), v
    card = crm_tools.view(url, "deal", project="Тета", deal="оценка")
    assert "аванс" in card and "Модель: фикс" in card, card
    assert "Не вышло" in crm_tools.view(url, "client", project="Нет такого")


def test_life_views_apply_pending_dela_migrations_first(dela, conn):
    """Установщик зовёт migrate архива раньше migrate Дел: виды life не должны упасть на новых колонках."""
    try:
        assert dela_db.apply_pending(conn, {"dela_9999.sql": "CREATE TABLE crm.zz_probe (x int)"}) == ["dela_9999.sql"]
        assert dela_db.apply_pending(conn, {"dela_9999.sql": "CREATE TABLE crm.zz_probe (x int)"}) == []
    finally:
        conn.execute("DROP TABLE IF EXISTS crm.zz_probe")
        conn.execute("DELETE FROM core.migrations WHERE name = 'dela_9999.sql'")
