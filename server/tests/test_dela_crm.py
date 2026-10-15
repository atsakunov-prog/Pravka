"""CRM поверх Дел: доступ команды по ответственности, деньги только тем, кому открыты,
воронка с итогом, оплаты, хронология без удаления, виды."""

from __future__ import annotations

import datetime as dt

import psycopg
import pytest

from pravka_dela import crm, store
from pravka_dela import db as dela_db
from test_dela_schema import as_, db_today, dela, project, task  # noqa: F401  (фикстура и помощники)


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
    today = db_today(dela)
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


# ── Шаги и контакт сделки — одним правилом (аудит 15.10.2026, №2, №3, №7, №28; dela_0022) ──────────────────────


def _rec(url, user="sasha", **data):
    r = ops(url, user, {"op": "interaction.add", "data": {"kind": "call", "summary": "созвон", **data}})[0]
    assert r["ok"], r
    return r["row"]


def _pipe(url, user="sasha"):
    return {d["name"]: d for d in store.view(url, user, "pipeline")["deals"]}


def _day(v) -> str | None:
    """День по Москве из ответа вида (строка ISO) или базы."""
    if v is None:
        return None
    v = dt.datetime.fromisoformat(v) if isinstance(v, str) else v
    return v.astimezone(dt.timezone(dt.timedelta(hours=3))).date().isoformat()


def _since(url, deal_id, at):
    """Сделку «завели» раньше: тестовые сделки заводятся сегодня, а записи — задним числом."""
    with dela_db.session(url, "system", "test") as c:
        c.execute("UPDATE crm.deals SET created_at = %s WHERE id = %s", (at, deal_id))


def test_client_task_is_the_single_deal_step(team, cfg):
    """№2: у клиента одна живая сделка — его открытое дело без сделки — её шаг: «застыла» не горит, у next_task —
    via_client; своё дело сделки — первым. Вторая живая сделка — правило выключено, новая без дел горит. Коннектор
    (life.deals) считает шаги так же."""
    url = team["url"]
    p = project(url, "sasha", "Ромашка")
    a = deal(url, "sasha", p, "Ромашка: модель", stage="active")
    old = deal(url, "sasha", p, "Ромашка: прошлое")
    ops(url, "sasha", {"op": "deal.set", "id": old["id"], "set": {"outcome": "won"}})  # архив — не в счёт
    task(url, "sasha", "Ромашка: прислать модель", project_id=p)
    task(url, "sasha", "Ромашка: закрытое", project_id=p, status="done")
    m = _pipe(url)["Ромашка: модель"]
    assert m["solo"] and not m["stale"] and m["open_tasks"] == 1, m
    assert m["next_task"]["title"] == "Ромашка: прислать модель" and m["next_task"]["via_client"]
    card = store.view(url, "sasha", "deal", deal_id=a["id"])
    assert [(x["title"], x["via_client"]) for x in card["open"]] == [("Ромашка: прислать модель", True)]
    assert not _pipe(url)["Ромашка: прошлое"]["solo"]  # архивной сделке дела клиента не шаги
    # Строка Claude на Воронке видит тот же шаг.
    from pravka_dela import ask

    with as_(url, "sasha") as c:
        [line] = ask._deals_on_screen(c, [a["id"]], set())
    assert "Ромашка: прислать модель — дело клиента без сделки" in line, line
    # Своё дело сделки — следующим шагом, дело клиента — после.
    task(url, "sasha", "Ромашка: созвон по модели", project_id=p, deal_id=a["id"], due_date=db_today(url) + dt.timedelta(days=9))
    m = _pipe(url)["Ромашка: модель"]
    assert m["open_tasks"] == 2 and m["next_task"]["title"] == "Ромашка: созвон по модели" and not m["next_task"]["via_client"]
    # Наташа (видит всех клиентов) видит то же.
    assert _pipe(url, "natasha")["Ромашка: модель"]["open_tasks"] == 2
    with psycopg.connect(cfg.reader_url) as r:
        life = dict(r.execute("SELECT name, open_tasks FROM life.deals WHERE project = 'Ромашка'").fetchall())
    assert life == {"Ромашка: модель": 2, "Ромашка: прошлое": 0}
    # Вторая живая сделка — дела клиента больше ничьи шаги.
    deal(url, "sasha", p, "Ромашка: допсоглашение")
    v = _pipe(url)
    assert v["Ромашка: допсоглашение"]["stale"] and v["Ромашка: допсоглашение"]["open_tasks"] == 0
    assert v["Ромашка: модель"]["open_tasks"] == 1 and not v["Ромашка: модель"]["solo"]
    assert [x["title"] for x in store.view(url, "sasha", "deal", deal_id=a["id"])["open"]] == ["Ромашка: созвон по модели"]
    with psycopg.connect(cfg.reader_url) as r:
        assert r.execute("SELECT open_tasks FROM life.deals WHERE name = 'Ромашка: модель'").fetchone()[0] == 1


def test_deal_timeline_takes_client_records_only_while_single(team):
    """№3: хронология сделки — её записи и, пока сделка у клиента одна живая, записи клиента без сделки: не из Notion и
    не раньше дня её заведения (у сделки из Notion — без границы). Вторая живая сделка — записи клиента уходят."""
    url = team["url"]
    p = project(url, "sasha", "Сигма")
    d = deal(url, "sasha", p, "Сигма: аудит", stage="active")
    _since(url, d["id"], "2026-10-01T10:00:00+03:00")
    # deal_id: None — запись именно клиента (без ключа сервер сам поставил бы сделку касания).
    _rec(url, summary="после заведения", at="2026-10-05T12:00:00+03:00", project_id=str(p), deal_id=None)
    _rec(url, summary="до заведения", at="2026-09-20T12:00:00+03:00", project_id=str(p), deal_id=None)
    _rec(url, summary="из Notion", at="2026-10-06T12:00:00+03:00", project_id=str(p), deal_id=None, source="notion")
    _rec(url, summary="своя", at="2026-09-10T12:00:00+03:00", project_id=str(p), deal_id=d["id"])

    def tl(user="sasha"):
        return [x["summary"] for x in store.view(url, user, "deal", deal_id=d["id"])["timeline"]]

    assert tl() == ["после заведения", "своя"]
    assert tl("natasha") == ["после заведения", "своя"]
    # Сделка из Notion жила и до переноса — записи клиента без нижней границы (но не сами записи Notion).
    with dela_db.session(url, "system", "test") as c:
        c.execute("UPDATE crm.deals SET notion_id = 'https://notion.example.org/x' WHERE id = %s", (d["id"],))
    assert tl() == ["после заведения", "до заведения", "своя"]
    # Вторая живая сделка — записи клиента без сделки ничьи.
    deal(url, "sasha", p, "Сигма: оценка")
    assert tl() == ["своя"]


def test_interaction_without_deal_takes_touch_deal(team):
    """№3: interaction.add без ключа deal_id — «сделка касания»: одна живая у клиента — она; иначе живая, где есть
    кто-то из людей записи, если такая одна; иначе без сделки. Ключ есть, хоть null, — не трогаем. Названная — по имени."""
    url = team["url"]
    p = project(url, "sasha", "Тета")
    with dela_db.session(url, "system", "test") as c:
        ivan = c.execute("INSERT INTO crm.people (name, owner_id) VALUES ('Иван Тестов', 'sasha') RETURNING id").fetchone()["id"]
    a = deal(url, "sasha", p, "Тета: модель", stage="active")
    _since(url, a["id"], "2026-10-01T10:00:00+03:00")
    now = "2026-10-10T12:00:00+03:00"
    assert str(_rec(url, at=now, project_id=str(p))["deal_id"]) == a["id"]
    assert _rec(url, at=now, project_id=str(p), deal_id=None)["deal_id"] is None
    # Запись раньше заведения сделки (заведена в Делах, не из Notion) — не её.
    assert _rec(url, at="2026-09-15T12:00:00+03:00", project_id=str(p))["deal_id"] is None
    # Две живые: по людям записи — та, где человек; без людей — никакая.
    b = deal(url, "sasha", p, "Тета: оценка", stage="lead", person_ids=[str(ivan)])
    _since(url, b["id"], "2026-10-01T10:00:00+03:00")
    assert str(_rec(url, at=now, project_id=str(p), person_ids=[str(ivan)])["deal_id"]) == b["id"]
    assert _rec(url, at=now, project_id=str(p))["deal_id"] is None
    with as_(url, "sasha") as c:
        named = c.execute("SELECT crm.touch_deal(%s, '{}', now(), 'тета:  МОДЕЛЬ') AS d", (p,)).fetchone()["d"]
        assert str(named) == a["id"]


def test_deal_last_touch_one_rule_for_web_and_connector(team, cfg):
    """№7, №28: последний контакт сделки — одна функция (crm.deal_last_touch) для веба и коннектора: записи сделки и,
    при одной живой, клиента без сделки; упоминание не освежает; приватную запись видит только её хозяин — владелец и
    life.deals видят одну дату, команда — без приватной."""
    url = team["url"]
    p = project(url, "sasha", "Йота")
    a = deal(url, "sasha", p, "Йота: модель", stage="active")
    b = deal(url, "sasha", p, "Йота: оценка", stage="lead")
    for x in (a, b):
        _since(url, x["id"], "2026-10-01T10:00:00+03:00")
    _rec(url, at="2026-10-05T12:00:00+03:00", project_id=str(p), deal_id=None)
    v = _pipe(url)
    assert v["Йота: модель"]["last_touch"] is None and v["Йота: оценка"]["last_touch"] is None  # две живые — ничья
    ops(url, "sasha", {"op": "deal.set", "id": b["id"], "set": {"outcome": "lost"}})
    assert _day(_pipe(url)["Йота: модель"]["last_touch"]) == "2026-10-05"
    # Пункт сводки Telegram: упоминание (клиента назвали в теме) не освежает, контакт — освежает только владельцу.
    with dela_db.session(url, "sasha", "svc:digest") as c:
        for ref, at, mention in (("digest:x#work-1", "2026-10-09T20:00:00+03:00", True),
                                 ("digest:x#wait-2", "2026-10-07T20:00:00+03:00", False)):
            c.execute("INSERT INTO crm.interactions (at, kind, summary, project_id, source, source_ref, owner_id, private, "
                      "mention) VALUES (%s, 'telegram', 'Telegram: пункт', %s, 'digest', %s, 'sasha', true, %s)",
                      (at, p, ref, mention))
    assert _day(_pipe(url)["Йота: модель"]["last_touch"]) == "2026-10-07"
    assert _day(_pipe(url, "natasha")["Йота: модель"]["last_touch"]) == "2026-10-05"
    with as_(url, "sasha") as c:
        own = c.execute("SELECT crm.deal_last_touch(%s) AS t", (a["id"],)).fetchone()["t"]
    with psycopg.connect(cfg.reader_url) as r:
        life = r.execute("SELECT last_touch FROM life.deals WHERE name = 'Йота: модель'").fetchone()[0]
        assert life == own and own.date() == dt.date(2026, 10, 7)
        ints = dict(r.execute("SELECT source_ref, mention FROM life.interactions WHERE source = 'digest'").fetchall())
    assert ints == {"digest:x#work-1": True, "digest:x#wait-2": False}
    # «Клиенты» — та же правда: упоминание не контакт.
    c = next(x for x in store.view(url, "sasha", "clients")["clients"] if x["name"] == "Йота")
    assert _day(c["last_touch"]) == "2026-10-07"


def test_deal_and_client_show_their_meetings_to_owner_only(team):
    """№3: у сделки и клиента — их встречи по карточке встречи (и до 01.09), только владельцу; встречи сделки — и
    отбором deal_id на странице «Встречи»."""
    url = team["url"]
    p = project(url, "sasha", "Каппа")
    a = deal(url, "sasha", p, "Каппа: сделка", stage="active")
    with dela_db.session(url, "sasha", "svc:test") as c:
        for ref, at, did in (("901", "2026-08-10T11:00:00+03:00", a["id"]), ("902", "2026-10-10T11:00:00+03:00", a["id"]),
                             ("903", "2026-10-11T11:00:00+03:00", None)):
            c.execute("INSERT INTO crm.meetings (ref, url, started_at, duration_min, owner_id, sphere, project_id, deal_id, title) "
                      "VALUES (%s, %s, %s, 40, 'sasha', 'work', %s, %s, 'Каппа: встреча')",
                      (ref, f"https://meetings.example.org/m/{ref}", at, p, did))
    card = store.view(url, "sasha", "deal", deal_id=a["id"])
    assert [m["ref"] for m in card["meetings"]] == ["902", "901"] and card["meetings_n"] == 2
    client = store.view(url, "sasha", "client", project_id=str(p))
    assert [m["ref"] for m in client["meetings"]] == ["903", "902", "901"] and client["meetings_n"] == 3
    for view, kw in (("deal", {"deal_id": a["id"]}), ("client", {"project_id": str(p)})):
        got = store.view(url, "natasha", view, **kw)
        assert got["meetings"] == [] and got["meetings_n"] == 0, view
    assert [m["ref"] for m in store.view(url, "sasha", "meetings", deal_id=a["id"])["items"]] == ["902", "901"]
    assert [m["ref"] for m in store.view(url, "sasha", "meetings", deal_id="none")["items"]] == ["903"]


def test_migration_22_over_deals_and_records(admin_dsn):
    """dela_0022 поверх Дел с записями, сделками и делами: прежние записи — не упоминания, функции отвечают, ничего не
    переписано."""
    import secrets as _secrets
    from urllib.parse import urlparse, urlunparse

    from psycopg import sql

    name = f"pa_mig22_{_secrets.token_hex(4)}"
    with psycopg.connect(admin_dsn, autocommit=True) as a:
        a.execute(sql.SQL("CREATE DATABASE {} ENCODING 'UTF8' TEMPLATE template0").format(sql.Identifier(name)))
    u = urlparse(admin_dsn)
    url = urlunparse((u.scheme, u.netloc, "/" + name, "", "", ""))
    try:
        files = dict(dela_db._sql_files())
        before = {k: v for k, v in files.items() if k.startswith("dela_") and k < "dela_0022.sql"}
        with psycopg.connect(url, autocommit=True) as c:
            c.execute("CREATE SCHEMA core")
            c.execute("CREATE TABLE core.migrations (name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())")
            dela_db.apply_pending(c, before)
        with dela_db.session(url, "system", "test") as c:
            c.execute("INSERT INTO crm.users (id, name, role) VALUES ('sasha', 'Саша', 'owner')")
            pid = c.execute("INSERT INTO crm.projects (name, sphere, kind, owner_id) VALUES ('Вектор', 'work', 'client', 'sasha') "
                            "RETURNING id").fetchone()["id"]
            did = c.execute("INSERT INTO crm.deals (project_id, name, stage, created_at) VALUES (%s, 'Вектор: заём', 'active', "
                            "'2026-10-01') RETURNING id", (pid,)).fetchone()["id"]
            c.execute("INSERT INTO crm.interactions (at, kind, summary, project_id, source, owner_id) VALUES "
                      "('2026-10-05T12:00:00+03:00', 'call', 'созвон', %s, 'web', 'sasha')", (pid,))
            c.execute("INSERT INTO tasks.tasks (title, project_id, owner_id, created_by) VALUES ('Вектор: договор', %s, 'sasha', 'sasha')",
                      (pid,))
            rev = c.execute("SELECT rev FROM crm.interactions").fetchone()["rev"]
        with psycopg.connect(url, autocommit=True) as c:
            assert dela_db.apply_pending(c, files)[0] == "dela_0022.sql"
        with dela_db.session(url, "sasha", "test") as c:
            row = c.execute("SELECT mention, rev, deal_id FROM crm.interactions").fetchone()
            assert (row["mention"], row["rev"], row["deal_id"]) == (False, rev, None)
            assert c.execute("SELECT crm.only_deal(%s) AS d", (pid,)).fetchone()["d"] == did
            assert c.execute("SELECT crm.deal_last_touch(%s) AS t", (did,)).fetchone()["t"].date() == dt.date(2026, 10, 5)
    finally:
        with psycopg.connect(admin_dsn, autocommit=True) as a:
            a.execute(sql.SQL("DROP DATABASE IF EXISTS {} WITH (FORCE)").format(sql.Identifier(name)))
