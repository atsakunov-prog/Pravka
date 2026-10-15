"""Дайджест Telegram по карточкам CRM (dela_0018, digest_crm.py): пункт сводки — в хронологию рабочего человека и его
клиента, только владельцу; семья и личные — мимо; повтор не плодит; команда не видит; коннектор — только владельцу.

Люди, клиенты и переписка выдуманные: репозиторий публичный.
"""

from __future__ import annotations

import copy

import psycopg
import pytest
from starlette.testclient import TestClient

from pravka_dela import api, digest, digest_crm, mcp_tools, store, tokens
from pravka_dela import db as dela_db
from pravka_dela.config import Config
from test_dela_schema import dela  # noqa: F401  (фикстура)

REF = "digest:2026-10-13T2000"
LABEL = "вт 13.10, 20:00"

SOURCES = [
    {"name": "Иван Соколов (Ромашка)", "kind": "личный", "peer_id": 1001, "tg_id": 1001},
    {"name": "Ольга Лебедева", "kind": "личный", "peer_id": 1002},
    {"name": "Игорь Громов (Финбюро)", "kind": "личный", "peer_id": 1005},
    {"name": "(Бета Групп) сделка", "kind": "группа", "tg_id": -100500, "senders": {"Михаил Орлов": 1003}},
    {"name": "Марианна Громова", "kind": "личный", "peer_id": 1006},
    {"name": "Наталья Ветрова (Финбюро)", "kind": "личный", "peer_id": 1004},
]

SUMMARY = {
    "waiting": [
        {"chat": "Иван Соколов (Ромашка)", "who": "Иван", "what": "ждёт модель к пятнице"},
        {"chat": "Ольга Лебедева", "who": "Оля", "what": "зовёт на ужин в субботу"},
        {"chat": "Игорь Громов (Финбюро)", "who": "папа", "what": "заехать за рассадой"},
        {"chat": "(Бета Групп) сделка", "who": "Михаил Орлов", "what": "просит согласовать NDA"},
        {"chat": "Марианна Громова", "who": "Марианна", "what": "купить молоко"},
    ],
    "work": [
        {"topic": "Ромашка", "text": "Подписали допсоглашение, аванс на следующей неделе."},
        {"topic": "Непонятный чат", "text": "Обсуждали погоду."},
        {"topic": "Наталья Ветрова (Финбюро)", "text": "Собрала отчёт по Ромашке, ждёт проверки."},
    ],
    "family": ["Папа: на дачу в субботу."],
    "other": "Группы шумели про отпуск.",
}


def put(url, d, user="sasha"):
    return store.apply_ops(url, user, [{"op": "digest.put", "digest": d}], "digest", "svc:digest")["results"][0]


def window(ref=REF, ended="2026-10-13T20:00:00+03:00", summary=None, sources=SOURCES, **kw) -> dict:
    d = {"ref": ref, "ended_at": ended, "started_at": "2026-10-13T15:00:00+03:00", "label": LABEL,
         "chats": 6, "msgs": 40, "summary": copy.deepcopy(summary or SUMMARY), **kw}
    if sources is not None:
        d["sources"] = copy.deepcopy(sources)
    return d


@pytest.fixture()
def crm(dela):
    """Справочник: владелец Пётр Громов из «Финбюро», Наташа — команда, Марианна — семья (флаг), Игорь Громов — папа
    (однофамилец, флага нет), клиенты Ромашка (сделка с Иваном, в команде Наташа) и Бета Групп, Ольга — подруга."""
    url = dela
    with dela_db.session(url, "system", "test") as c:
        org = {}
        for name, kind in (("Финбюро", "other"), ("Ромашка", "client"), ("Бета Групп", "client"), ("Кафе Ольги", "other")):
            org[name] = c.execute("INSERT INTO crm.orgs (name, kind, owner_id) VALUES (%s, %s, 'sasha') RETURNING id",
                                  (name, kind)).fetchone()["id"]
        ppl = {}
        # Имя карточки — «Имя Фамилия (Компания)», полное без скобок — другим именем: так их назвала сверка людей
        # 08.10.2026, и так же названы контакты Telegram (название личного чата).
        for key, name, o, tg in (
                ("me", "Пётр Громов (Финбюро)", "Финбюро", None),
                ("natasha", "Наталья Ветрова (Финбюро)", "Финбюро", 1004),
                ("marianna", "Марианна Громова", None, 1006),
                ("papa", "Игорь Громов (Финбюро)", "Финбюро", 1005),
                ("ivan", "Иван Соколов (Ромашка)", "Ромашка", 1001),
                ("misha", "Михаил Орлов (Бета Групп)", "Бета Групп", 1003),
                ("olga", "Ольга Лебедева", "Кафе Ольги", 1002)):
            ppl[key] = c.execute(
                "INSERT INTO crm.people (name, aliases, org_id, telegram_id, owner_id) VALUES (%s, %s, %s, %s, 'sasha') "
                "RETURNING id", (name, [name.split(" (")[0]], org.get(o), tg)).fetchone()["id"]
        c.execute("UPDATE crm.users SET person_id = %s, family = true WHERE id = 'sasha'", (ppl["me"],))
        c.execute("UPDATE crm.users SET person_id = %s WHERE id = 'natasha'", (ppl["natasha"],))
        c.execute("UPDATE crm.users SET person_id = %s, family = true WHERE id = 'marianna'", (ppl["marianna"],))
        prj = {}
        for name in ("Ромашка", "Бета Групп"):
            prj[name] = c.execute(
                "INSERT INTO crm.projects (name, sphere, kind, org_id, owner_id) VALUES (%s, 'work', 'client', %s, 'sasha') "
                "RETURNING id", (name, org[name])).fetchone()["id"]
        # Сделка заведена до сводок (с 15.10.2026 сделка, заведённая в Делах позже окна, его записей не берёт — №3).
        deal = c.execute(
            "INSERT INTO crm.deals (project_id, name, stage, person_ids, team_ids, created_at) VALUES "
            "(%s, 'Модель', 'active', %s, %s, '2026-10-01T10:00:00+03:00') "
            "RETURNING id", (prj["Ромашка"], [ppl["ivan"]], [ppl["natasha"]])).fetchone()["id"]
        # Наташе Ромашка открыта: клиента и его хронологию она видит — но не записи из Telegram владельца.
        c.execute("INSERT INTO crm.project_access (project_id, user_id, role, granted_by) VALUES (%s, 'natasha', 'edit', 'sasha')",
                  (prj["Ромашка"],))
    return {"url": url, "people": ppl, "projects": prj, "deal": deal}


def rows(url, user="sasha", **where):
    cond = " AND ".join(f"{k} = %s" for k in where) or "TRUE"
    with dela_db.session(url, user, via="test") as c:
        return c.execute(f"SELECT * FROM crm.interactions WHERE source = 'digest' AND {cond} ORDER BY summary",
                         list(where.values())).fetchall()


def test_items_land_in_person_client_and_deal_cards(crm):
    url, ppl, prj = crm["url"], crm["people"], crm["projects"]
    r = put(url, window())
    assert r["ok"], r
    c = r["crm"]
    # Иван (личный чат, id Telegram) — Ромашка и сделка; Михаил (группа, id отправителя) — Бета Групп;
    # тема «Ромашка» — клиент; Наташа (личный чат) — только её карточка. Папа и Марианна — семья, Ольга — личное,
    # «Непонятный чат» — никто.
    assert (c["linked"], c["new"], c["retired"]) == (4, 4, 0), c
    assert c["skipped"] == {"family": 2, "personal": 1, "unknown": 1}, c
    ivan = store.view(url, "sasha", "dossier", person_id=str(ppl["ivan"]))["timeline"]
    assert len(ivan) == 1
    e = ivan[0]
    assert e["kind"] == "telegram" and e["source"] == "digest" and e["private"] and e["digest_ref"] == REF
    # «Кто (чат)» — как в сводке (digest.where_of): «Иван» и так часть названия чата.
    assert e["summary"] == f"Telegram, {LABEL}: ждёт тебя — Иван: ждёт модель к пятнице"
    assert str(e["project_id"]) == str(prj["Ромашка"]) and str(e["deal_id"]) == str(crm["deal"])
    client = store.view(url, "sasha", "client", project_id=str(prj["Ромашка"]))["timeline"]
    assert len(client) == 2 and {x["digest_ref"] for x in client} == {REF}
    assert any("допсоглашение" in x["summary"] for x in client)
    # Сделка у Ромашки одна живая — обе записи клиента в её хронологии и с ней (сделка касания, аудит №3).
    deal = store.view(url, "sasha", "deal", deal_id=str(crm["deal"]))["timeline"]
    assert {x["id"] for x in deal} == {x["id"] for x in client} and {str(x["deal_id"]) for x in deal} == {str(crm["deal"])}
    beta = store.view(url, "sasha", "client", project_id=str(prj["Бета Групп"]))["timeline"]
    assert len(beta) == 1 and "NDA" in beta[0]["summary"] and [str(x) for x in beta[0]["person_ids"]] == [str(ppl["misha"])]
    nat = store.view(url, "sasha", "dossier", person_id=str(ppl["natasha"]))["timeline"]
    assert len(nat) == 1 and nat[0]["project_id"] is None and "отчёт" in nat[0]["summary"]


def test_family_and_personal_stay_only_in_digest(crm):
    url, ppl = crm["url"], crm["people"]
    assert put(url, window())["ok"]
    for who in ("papa", "marianna", "olga", "me"):
        assert store.view(url, "sasha", "dossier", person_id=str(ppl[who]))["timeline"] == [], who
    # Свод people.family («Семья» звонков) — правда о семье: записан Иван — его пункт больше не разносится (запись
    # убрана); папы в списке нет — он теперь не семья по фамилии, а команда (компания владельца) — пункт у него.
    with dela_db.session(url, "system", "test") as c:
        c.execute("INSERT INTO crm.svod (owner_id, key, value, author) VALUES ('sasha', 'people.family', %s, 'web')",
                  (psycopg.types.json.Jsonb([str(ppl["ivan"])]),))
    r = put(url, window())
    assert r["crm"]["skipped"]["family"] == 2 and (r["crm"]["new"], r["crm"]["retired"]) == (1, 1), r
    assert store.view(url, "sasha", "dossier", person_id=str(ppl["ivan"]))["timeline"] == []
    assert len(store.view(url, "sasha", "dossier", person_id=str(ppl["papa"]))["timeline"]) == 1
    with dela_db.session(url, "system", "test") as c:
        c.execute("UPDATE crm.svod SET value = %s WHERE key = 'people.family'",
                  (psycopg.types.json.Jsonb([str(ppl["ivan"]), str(ppl["papa"])]),))
    r = put(url, window())
    assert r["crm"]["skipped"]["family"] == 3 and r["crm"]["retired"] == 1, r
    assert store.view(url, "sasha", "dossier", person_id=str(ppl["papa"]))["timeline"] == []


def test_repeat_does_not_duplicate_and_dropped_item_is_retired(crm):
    url = crm["url"]
    first = put(url, window())["crm"]
    again = put(url, window())["crm"]
    assert (again["linked"], again["new"], again["retired"]) == (first["linked"], 0, 0)
    assert len(rows(url)) == 4
    # Сводку прислали заново без пункта Михаила — его запись убрана (строка цела, правило 8), остальные — те же.
    less = copy.deepcopy(SUMMARY)
    less["waiting"] = [w for w in less["waiting"] if w["who"] != "Михаил Орлов"]
    r = put(url, window(summary=less))["crm"]
    assert (r["linked"], r["new"], r["retired"]) == (3, 0, 1)
    live = [x for x in rows(url) if x["deleted_at"] is None]
    assert len(live) == 3 and len(rows(url)) == 4
    # Убранное владельцем руками повтор не возвращает.
    ivan = next(x for x in live if "модель" in x["summary"])
    assert store.apply_ops(url, "sasha", [{"op": "interaction.delete", "id": str(ivan["id"])}], "web")["results"][0]["ok"]
    put(url, window(summary=less))
    assert next(x for x in rows(url) if x["id"] == ivan["id"])["deleted_at"] is not None
    # Другая сводка с тем же пунктом — своя запись: у пункта своя ссылка на свою сводку.
    put(url, window(ref="digest:2026-10-14T1100", ended="2026-10-14T11:00:00+03:00"))
    assert len({x["source_ref"].split("#")[0] for x in rows(url)}) == 2


def test_old_digest_without_sources_matches_by_names(crm):
    """Сводки до 14.10.2026 (догрузка из Output) — без чатов службы: люди узнаются по названиям чатов и именам."""
    url, ppl = crm["url"], crm["people"]
    r = put(url, window(sources=None))
    assert r["ok"] and r["crm"]["linked"] == 4, r
    assert len(store.view(url, "sasha", "dossier", person_id=str(ppl["misha"]))["timeline"]) == 1
    # Повтор со списком чатов — те же записи, не новые; без списка — прежний список не затирается.
    assert put(url, window())["crm"]["new"] == 0
    put(url, window(sources=None))
    with dela_db.session(url, "sasha", via="test") as c:
        assert len(c.execute("SELECT sources FROM crm.digests WHERE ref = %s", (REF,)).fetchone()["sources"]) == len(SOURCES)


def test_team_and_family_never_see_digest_entries(crm):
    url, prj = crm["url"], crm["projects"]
    assert put(url, window())["ok"]
    # Обычная запись того же клиента Наташа видит — значит, видимость проекта у неё есть, а Telegram скрыт именно им.
    assert store.apply_ops(url, "sasha", [{"op": "interaction.add", "data": {
        "at": "2026-10-13T12:00:00+03:00", "kind": "call", "summary": "Созвон с Иваном", "project_id": str(prj["Ромашка"])}}],
        "web")["results"][0]["ok"]
    seen = store.view(url, "natasha", "client", project_id=str(prj["Ромашка"]))["timeline"]
    assert [x["summary"] for x in seen] == ["Созвон с Иваном"]
    assert [x["summary"] for x in store.view(url, "natasha", "deal", deal_id=str(crm["deal"]))["timeline"]] == ["Созвон с Иваном"]
    for user in ("natasha", "marianna"):
        assert rows(url, user) == [], user
        with dela_db.session(url, user, via="app") as c:
            # Ни прочесть, ни поправить, ни убрать: строки для них нет вовсе.
            assert c.execute("UPDATE crm.interactions SET summary = 'x' WHERE source = 'digest' RETURNING id").fetchall() == []
        with pytest.raises(store.OpError, match="только владельцу"):
            store.view(url, user, "digest", ref=REF)
    # Журнала у записей дайджеста нет: текст переписки не копится в crm.history.
    with dela_db.session(url, "system", "test") as c:
        n = c.execute("SELECT count(*) AS n FROM crm.history h WHERE h.entity = 'crm.interactions' AND h.entity_id IN "
                      "(SELECT id::text FROM crm.interactions WHERE source = 'digest')").fetchone()["n"]
        assert n == 0
    # Вход сверки встреч и дайджеста (вид timeline) — без записей Telegram: Claude видит прежнее.
    tl = store.view(url, "sasha", "timeline")["items"]
    assert [x["summary"] for x in tl] == ["Созвон с Иваном"]


def test_private_is_a_rule_of_the_base(crm):
    url = crm["url"]
    put(url, window())
    with dela_db.session(url, "sasha", via="test") as c:
        with pytest.raises(psycopg.errors.RaiseException, match="приватность"):
            c.execute("UPDATE crm.interactions SET private = false WHERE source = 'digest'")
    with dela_db.session(url, "sasha", via="test") as c:
        with pytest.raises(psycopg.errors.CheckViolation):
            c.execute("INSERT INTO crm.interactions (at, kind, summary, source, owner_id) "
                      "VALUES (now(), 'telegram', 'x', 'digest', 'sasha')")


def test_link_failure_keeps_the_digest(crm, monkeypatch):
    def boom(*a, **k):
        raise RuntimeError("сломалось")

    monkeypatch.setattr(digest_crm, "plan", boom)
    r = put(crm["url"], window())
    assert r["ok"] and r["crm"] == {"error": "RuntimeError"}, r
    assert store.view(crm["url"], "sasha", "digest", ref=REF)["digest"]["label"] == LABEL
    assert rows(crm["url"]) == []


def test_view_search_dates_and_card_filters(crm):
    url, ppl, prj = crm["url"], crm["people"], crm["projects"]
    put(url, window())
    other = {"waiting": [], "work": [{"topic": "Бета Групп", "text": "Счета за октябрь отправлены."}], "family": [], "other": ""}
    assert put(url, window(ref="digest:2026-10-11T2200", ended="2026-10-11T22:00:00+03:00", summary=other,
                           started_at="2026-10-10T22:00:00+03:00", label="вс 11.10 · за сутки"))["ok"]
    v = store.view(url, "sasha", "digest", q="допсоглашения")  # словоформа
    assert [x["ref"] for x in v["items"]] == [REF] and "допсоглашение" in v["items"][0]["snippet"]
    assert [x["ref"] for x in store.view(url, "sasha", "digest", q="счёт")["items"]] == ["digest:2026-10-11T2200"]
    found = store.view(url, "sasha", "digest", q="Соколов")["items"]  # фамилия — из полного названия чата в пункте
    assert [x["ref"] for x in found] == [REF] and "Иван Соколов (Ромашка)" in found[0]["snippet"]
    assert [x["ref"] for x in store.view(url, "sasha", "digest", since="2026-10-11", until="2026-10-11")["items"]] == [
        "digest:2026-10-11T2200"]
    assert [x["ref"] for x in store.view(url, "sasha", "digest", since="2026-10-12")["items"]] == [REF]
    assert [x["ref"] for x in store.view(url, "sasha", "digest", person_id=str(ppl["ivan"]))["items"]] == [REF]
    got = [x["ref"] for x in store.view(url, "sasha", "digest", project_id=str(prj["Бета Групп"]))["items"]]
    assert got == [REF, "digest:2026-10-11T2200"]
    one = store.view(url, "sasha", "digest", ref=REF + "#waiting-xxx")  # ссылка записи хронологии ведёт на сводку
    assert one["digest"]["ref"] == REF and len(one["crm"]) == 4
    assert any("Иван Соколов (Ромашка)" in x["people"] for x in one["crm"])
    with pytest.raises(store.OpError, match="ГГГГ-ММ-ДД"):
        store.view(url, "sasha", "digest", since="вчера")


def test_connector_tools_are_owner_only(crm, monkeypatch):
    url = crm["url"]
    put(url, window())
    out = mcp_tools.digests_list(url, query="NDA")
    assert out.startswith("Сводки дайджеста Telegram (поиск «NDA»): 1") and f"[{REF}]" in out and "NDA" in out
    assert f"[{REF}]" in mcp_tools.digests_list(url, since="2026-10-13", until="2026-10-13")
    assert f"[{REF}]" in mcp_tools.digests_list(url, person="Иван Соколов")
    assert f"[{REF}]" in mcp_tools.digests_list(url, client="Бета Групп")
    card = mcp_tools.digest_card(url, "2026-10-13T2000")
    assert card.startswith(f"Дайджест Telegram [{REF}] — {LABEL}") and "Ждут тебя" in card
    assert "В карточках CRM" in card and "Михаил Орлов (Бета Групп)" in card
    assert mcp_tools.digest_card(url, "last").startswith(f"Дайджест Telegram [{REF}]")
    assert mcp_tools.digest_card(url, "digest:nope").startswith("Не вышло")
    # Коннектор действует от имени владельца; будь это кто-то другой — отказ словами, ничего наружу.
    monkeypatch.setattr(mcp_tools, "USER", "natasha")
    assert "только владельцу" in mcp_tools.digests_list(url)
    assert "только владельцу" in mcp_tools.digest_card(url, REF)


def test_api_service_sends_sources_and_phone_reads_card(crm):
    """Через HTTP: служба шлёт сводку с чатами, телефон владельца видит запись в карточке человека; Наташа — нет."""
    url, ppl = crm["url"], crm["people"]
    client = TestClient(api.build(Config(db_url=url)))
    svc = {"Authorization": f"Bearer {tokens.issue(url, 'sasha', 'service', 'digest')}"}
    phone = {"Authorization": f"Bearer {tokens.issue(url, 'sasha', 'device', 'телефон')}"}
    team = {"Authorization": f"Bearer {tokens.issue(url, 'natasha', 'device', 'телефон Н')}"}
    r = client.post("/api/ops", headers=svc, json={"ops": [{"op": "digest.put", "digest": window()}]}).json()
    assert r["results"][0]["ok"] and r["results"][0]["crm"]["linked"] == 4, r
    path = f"/api/view/dossier?person_id={ppl['ivan']}"
    tl = client.get(path, headers=phone).json()["timeline"]
    assert len(tl) == 1 and tl[0]["digest_ref"] == REF
    assert client.get(path, headers=team).json().get("timeline", []) == []
    v = client.get(f"/api/view/digest?q=NDA&person_id={ppl['misha']}", headers=phone).json()
    assert v["ok"] and [x["ref"] for x in v["items"]] == [REF] and "NDA" in v["items"][0]["snippet"]


def test_where_of_and_line():
    assert digest_crm._line(LABEL, "work", {"topic": "Ромашка", "text": "Подписали."}) == f"Telegram, {LABEL}: Ромашка: Подписали."
    w = digest_crm._line(LABEL, "waiting", {"chat": "Бета · сделка", "who": "Олег", "what": "Просит NDA."})
    assert w == f"Telegram, {LABEL}: ждёт тебя — Олег (Бета · сделка): Просит NDA."
    assert digest_crm._line(LABEL, "waiting", {"chat": "", "who": "Олег", "what": ""}) == f"Telegram, {LABEL}: ждёт тебя — Олег"
    assert digest.snippet("Ждут тебя\n• Иван: модель\n\nПо работе\n• Ромашка: счета", "счёт") == "Ромашка: счета"


def test_family_by_surname_until_listed_even_for_team_users():
    """Однофамильцы владельца — семья, пока в Своде нет списка people.family: и папа из команды с учёткой Дел (флаг
    family — про карту «Где мы», не про родство). Список в Своде есть — правда он."""
    rows = [{"id": "me", "name": "Пётр Громов (Финбюро)", "org_id": "o"},
            {"id": "papa", "name": "Игорь Громов (Финбюро)", "org_id": "o", "user_id": "igor"},
            {"id": "team", "name": "Анна Ветрова (Финбюро)", "org_id": "o", "user_id": "anna"},
            {"id": "client", "name": "Иван Соколов (Ромашка)", "org_id": "r"}]
    ctx = digest_crm.build(rows, [], [], [], "me", [])
    assert ctx["family"] == {"papa"} and digest_crm.is_work(ctx, "team") and not digest_crm.is_work(ctx, "papa")
    listed = digest_crm.build(rows, [], [], [], "me", [], family_listed=[])
    assert listed["family"] == set() and digest_crm.is_work(listed, "papa")


# ── Упоминание — не контакт; сделка записи — правилом базы (аудит 15.10.2026, №28, №3; dela_0022) ─────────────


def _client(url, name, user="sasha"):
    return next(c for c in store.view(url, user, "clients")["clients"] if c["name"] == name)


def test_client_from_its_group_is_a_contact(crm):
    """Клиент из группы клиента — контакт: и когда тема — сам чат, и когда модель назвала тему именем клиента, а группа
    клиента в окне была. Ждут тебя — контакт всегда."""
    url, prj = crm["url"], crm["projects"]
    work = {"waiting": [], "work": [{"topic": "(Бета Групп) сделка", "text": "Согласовали NDA."},
                                    {"topic": "Бета Групп", "text": "Ждут график платежей."}], "family": [], "other": ""}
    assert put(url, window(summary=work))["crm"]["mentions"] == 0
    beta = rows(url, project_id=prj["Бета Групп"])
    assert len(beta) == 2 and not any(x["mention"] for x in beta)
    assert _client(url, "Бета Групп")["last_touch"] is not None
    full = put(url, window(ref="digest:2026-10-13T2100", ended="2026-10-13T21:00:00+03:00"))["crm"]
    assert full["mentions"] == 0, full  # «Ромашка» темой — у Ивана из Ромашки был личный чат в окне


def test_client_named_only_in_topic_is_a_mention(crm):
    """Клиент, названный только текстом темы, когда его чата (группы клиента, личного с его человеком) в окне нет, —
    упоминание: в хронологии есть, «последний контакт» клиента и сделки не двигает. Повтор сводки с его чатом — контакт:
    флаг обновляется."""
    url, prj = crm["url"], crm["projects"]
    work = {"waiting": [], "work": [{"topic": "Ромашка", "text": "Обсуждали, как подать модель."}], "family": [], "other": ""}
    no_chat = [s for s in SOURCES if s.get("peer_id") != 1001]  # без личного чата Ивана из Ромашки
    r = put(url, window(summary=work, sources=no_chat))["crm"]
    assert (r["linked"], r["mentions"]) == (1, 1), r
    [e] = rows(url, project_id=prj["Ромашка"])
    assert e["mention"] and str(e["deal_id"]) == str(crm["deal"])  # сделка — правилом базы: одна живая у клиента
    assert _client(url, "Ромашка")["last_touch"] is None
    deal = store.view(url, "sasha", "deal", deal_id=str(crm["deal"]))
    assert deal["deal"]["last_touch"] is None and [x["id"] for x in deal["timeline"]] == [str(e["id"])]
    # Та же сводка, но в окне был чат Ивана — контакт; запись та же.
    r = put(url, window(summary=work))["crm"]
    assert (r["new"], r["mentions"]) == (0, 0), r
    [e2] = rows(url, project_id=prj["Ромашка"])
    assert e2["id"] == e["id"] and not e2["mention"]
    assert _client(url, "Ромашка")["last_touch"] is not None
    # Сводка без списка чатов (до 14.10) — как раньше, контакт: отличить не по чему.
    r = put(url, window(ref="digest:2026-10-12T2000", ended="2026-10-12T20:00:00+03:00", started_at="2026-10-12T15:00:00+03:00",
                        summary=work, sources=None))["crm"]
    assert r["mentions"] == 0, r


def test_python_deal_rule_matches_the_base(crm):
    """Сделку записи пишет база (crm.touch_deal); питонья _deal (прогон вхолостую) — то же правило: одна живая у
    клиента; две — по людям пункта; заведённая позже окна — не берёт."""
    url, ppl, prj = crm["url"], crm["people"], crm["projects"]

    def same():
        with dela_db.session(url, "sasha", via="test") as c:
            planned = digest_crm.plan(digest_crm.load(c, "sasha"), {"ref": REF, "label": LABEL,
                                                                     "ended_at": "2026-10-13T20:00:00+03:00"}, SUMMARY, SOURCES)
        stored = {x["source_ref"]: (str(x["deal_id"]) if x["deal_id"] else None) for x in rows(url) if x["deleted_at"] is None}
        want = {r["ref"]: (str(r["deal_id"]) if r["deal_id"] else None) for r in planned["rows"]}
        assert stored == want
        return stored

    put(url, window())
    assert set(same().values()) == {str(crm["deal"]), None}  # Ромашка — её сделка; Бета (сделок нет), Наташа — без
    with dela_db.session(url, "system", "test") as c:
        # Вторая живая сделка Ромашки, без Ивана: запись Ивана — к его сделке, тема «Ромашка» без людей — ни к какой.
        c.execute("INSERT INTO crm.deals (project_id, name, stage, created_at) VALUES (%s, 'Аудит', 'lead', '2026-10-01')",
                  (prj["Ромашка"],))
        # У Беты — единственная сделка, заведённая позже окна: записи окна она не берёт.
        c.execute("INSERT INTO crm.deals (project_id, name, stage, person_ids) VALUES (%s, 'Бета: NDA', 'active', %s)",
                  (prj["Бета Групп"], [ppl["misha"]]))
    put(url, window())
    got = same()
    ivan = next(x for x in rows(url) if "модель к пятнице" in x["summary"])
    topic = next(x for x in rows(url) if "допсоглашение" in x["summary"])
    nda = next(x for x in rows(url) if "NDA" in x["summary"])
    assert got[ivan["source_ref"]] == str(crm["deal"]) and got[topic["source_ref"]] is None and got[nda["source_ref"]] is None


def test_digest_relink_dry_writes_nothing_and_real_sets_flags(crm, tmp_path, capsys, monkeypatch):
    """digest-relink: сохранённые сводки — заново тем же разносом; --dry считает и ничего не пишет; без него — флаг
    упоминания и сделка старым записям (до dela_0022 их не было)."""
    from pravka_dela import __main__ as cli

    url, prj = crm["url"], crm["projects"]
    work = {"waiting": [], "work": [{"topic": "Ромашка", "text": "Обсуждали, как подать модель."}], "family": [], "other": ""}
    put(url, window(summary=work, sources=[s for s in SOURCES if s.get("peer_id") != 1001]))
    put(url, window(ref="digest:2026-10-14T1100", ended="2026-10-14T11:00:00+03:00"))
    # Как было до установки: флага нет, сделки у записи нет.
    with dela_db.session(url, "sasha", "svc:test") as c:
        c.execute("UPDATE crm.interactions SET mention = false, deal_id = NULL WHERE source = 'digest'")
    before = {x["id"]: (x["mention"], x["deal_id"]) for x in rows(url)}
    env = tmp_path / "dela.env"
    env.write_text(f"DELA_DB_URL={url}\n", encoding="utf-8")
    monkeypatch.delenv("DELA_DB_URL", raising=False)
    assert cli.main(["--env", str(env), "digest-relink", "--dry"]) == 0
    said = capsys.readouterr().out
    assert said.startswith("вхолостую (ничего не записано): сводок: 2; ") and "упоминаний было 0, стало 1" in said, said
    assert {x["id"]: (x["mention"], x["deal_id"]) for x in rows(url)} == before
    real = digest_crm.relink(url)
    assert real["digests"] == 2 and real["new"] == 0 and real["after"]["mentions"] == 1, real
    [e] = [x for x in rows(url, project_id=prj["Ромашка"]) if "подать модель" in x["summary"]]
    assert e["mention"] and str(e["deal_id"]) == str(crm["deal"])
    again = digest_crm.relink(url)
    assert again["after"] == real["after"] and again["new"] == 0  # повтор безвреден
