"""Свод и одна карточка человека (06.10.2026): кто это, дописать имена и номера, слить дубль."""

from __future__ import annotations

import datetime as dt

from pravka_dela import store
from test_dela_schema import as_, dela, project  # noqa: F401  (фикстура и помощники)


def ops(url, user, *items, via="app"):
    return store.apply_ops(url, user, list(items), via)["results"]


def org(url, user, name, **kw):
    return ops(url, user, {"op": "org.create", "data": {"name": name, **kw}})[0]["row"]["id"]


def person(url, user, name, **kw):
    r = ops(url, user, {"op": "person.create", "data": {"name": name, **kw}})[0]
    assert r["ok"], r
    return str(r["row"]["id"])


# ── Свод ────────────────────────────────────────────────────────────────

def test_svod_set_sync_and_history(dela):
    r = ops(dela, "sasha", {"op": "svod.set", "key": "prompt.clean", "body": "Чисть текст.", "author": "phone", "reason": "первый"})[0]
    assert r["ok"] and r["row"]["rev"] == 1
    s = store.sync(dela, "sasha", 0)
    assert [(x["key"], x["body"]) for x in s["svod"]] == [("prompt.clean", "Чисть текст.")]
    assert "svod" in s["features"] and "people" in s["features"]
    seq = s["seq"]
    # Тот же текст ещё раз — версия не растёт, синк молчит.
    ops(dela, "sasha", {"op": "svod.set", "key": "prompt.clean", "body": "Чисть текст.", "author": "phone", "reason": "первый"})
    assert store.sync(dela, "sasha", seq)["svod"] == [] or store.sync(dela, "sasha", seq)["svod"][0]["rev"] == 1
    r = ops(dela, "sasha", {"op": "svod.set", "key": "prompt.clean", "body": "Чисть бережно.", "author": "tuner"})[0]
    assert r["row"]["rev"] == 2
    got = store.sync(dela, "sasha", seq)["svod"]
    assert [x["body"] for x in got] == ["Чисть бережно."]
    with as_(dela, "system") as c:
        h = c.execute("SELECT before, after FROM crm.history WHERE entity = 'crm.svod' ORDER BY at").fetchall()
    assert h[-1]["before"]["body"] == "Чисть текст." and h[-1]["after"]["body"] == "Чисть бережно."


def test_svod_json_view_and_validation(dela):
    v = {"pct": 30, "from": "2026-10-05"}
    assert ops(dela, "sasha", {"op": "svod.set", "key": "money.partner", "value": v, "author": "dengi"})[0]["ok"]
    items = store.view(dela, "sasha", "svod", prefix="money.")["items"]
    assert [(i["key"], i["value"]) for i in items] == [("money.partner", v)]
    bad = ops(
        dela, "sasha",
        {"op": "svod.set", "key": "Плохой ключ", "body": "x"},
        {"op": "svod.set", "key": "a.b", "body": "x", "value": {"y": 1}},
        {"op": "svod.set", "key": "a.b"},
    )
    assert [b["ok"] for b in bad] == [False, False, False]


def test_svod_is_private(dela):
    ops(dela, "sasha", {"op": "svod.set", "key": "prompt.patterns", "body": "про семью", "author": "phone"})
    assert store.sync(dela, "natasha", 0)["svod"] == []
    assert store.view(dela, "natasha", "svod")["items"] == []
    # Чужое не перепишешь и не прочтёшь даже ключом.
    r = ops(dela, "natasha", {"op": "svod.set", "key": "prompt.patterns", "body": "моё", "author": "web", "owner": "sasha"})[0]
    assert not r["ok"]
    assert store.view(dela, "sasha", "svod", key="prompt.patterns")["items"][0]["body"] == "про семью"


def test_svod_base_rev_guards_automatons(dela):
    ops(dela, "sasha", {"op": "svod.set", "key": "dict.main", "value": [{"w": "Ромашка"}], "author": "phone", "base_rev": 0})
    ops(dela, "sasha", {"op": "svod.set", "key": "dict.main", "value": [{"w": "Ромашка"}, {"w": "Соколов"}], "author": "meetings"})
    # Телефон доучил словарь поверх первой версии — сервер не даёт затереть «Соколова».
    r = ops(dela, "sasha", {"op": "svod.set", "key": "dict.main", "value": [{"w": "Ромашка"}, {"w": "Лютик"}], "author": "phone", "base_rev": 1})[0]
    assert not r["ok"] and "версии 2" in r["error"]
    # Без base_rev — последнее слово за пишущим (владелец: «после сервера — тоже уходит»).
    r = ops(dela, "sasha", {"op": "svod.set", "key": "dict.main", "value": [{"w": "Лютик"}], "author": "phone"})[0]
    assert r["ok"] and r["row"]["rev"] == 3


# ── Кто это ─────────────────────────────────────────────────────────────

def test_who_finds_zhenya_sokolov_from_romashka(dela):
    romashka = org(dela, "sasha", "Ромашка-банк")
    sokolov = person(dela, "sasha", "Евгений Соколов", org_id=romashka, aliases=["Евгений Соколов VP банка"])
    person(dela, "sasha", "Евгений Иванов")
    person(dela, "sasha", "Анна Смирнова", short="Анна")
    for q in ("Женя Соколов", "Соколов Евгений", "Евгений Соколов (ромашка)", "Женя Соколов из Ромашки", "Евгением Соколовым"):
        w = store.view(dela, "sasha", "who", q=q)
        assert w["sure"] and str(w["best"]["id"]) == str(sokolov), (q, w)
    # Одно «Женя» — двое Евгениев: не уверен, решает человек.
    assert not store.view(dela, "sasha", "who", q="Женя")["sure"]
    # Подсказка другой компании ослабляет, но не ломает знакомое имя без компании.
    w = store.view(dela, "sasha", "who", q="Анна Смирнова (Цветочная лавка)")
    assert w["sure"] and w["best"]["name"] == "Анна Смирнова"
    assert store.view(dela, "sasha", "who", q="Пётр Петров")["best"] is None


def test_who_by_phone_and_telegram(dela):
    p = person(dela, "sasha", "Евгений Соколов", phones=["+7 (916) 123-45-67"], telegram_username="sokolov_e")
    for kw in ({"phone": "89161234567"}, {"phone": "9161234567"}, {"telegram": "@Sokolov_E"}):
        w = store.view(dela, "sasha", "who", **kw)
        assert w["sure"] and str(w["best"]["id"]) == str(p), kw


def test_person_add_appends_without_duplicates(dela):
    p = person(dela, "sasha", "Евгений Соколов", aliases=["Евгений Соколов VP банка"], phones=["+7 916 123-45-67"])
    r = ops(dela, "sasha", {"op": "person.add", "id": str(p), "add": {
        "aliases": ["Женя Соколов", "евгений соколов", "ЕВГЕНИЙ СОКОЛОВ VP банка"],
        "phones": ["8 (916) 123-45-67", "+7 999 000-11-22"],
        "telegram_username": "@sokolov_e",
    }}, via="meetings")[0]
    assert r["ok"] and r["changed"]
    row = r["row"]
    assert row["aliases"] == ["Евгений Соколов VP банка", "Женя Соколов"]
    assert row["phones"] == ["+7 916 123-45-67", "+7 999 000-11-22"]
    assert row["telegram_username"] == "sokolov_e"
    again = ops(dela, "sasha", {"op": "person.add", "id": str(p), "add": {"aliases": ["Женя Соколов"]}})[0]
    assert again["ok"] and not again["changed"]
    # Теперь «Женя Соколов» — точное имя для всех, кто сопоставляет и в базе.
    with as_(dela, "sasha") as c:
        assert str(c.execute("SELECT person_id FROM crm.match_name('Женя Соколов')").fetchone()["person_id"]) == p


def test_person_merge_moves_everything_and_keeps_the_row(dela):
    pr = project(dela, "sasha", "Ромашка")
    live = person(dela, "sasha", "Евгений Соколов")
    dup = person(dela, "sasha", "Женя Соколов (ромашка)", phones=["+7 916 123-45-67"], role="VP")
    t = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Соколов: прислать КП", "person_id": str(dup), "ball": "waiting"}})[0]["task"]
    d = ops(dela, "sasha", {"op": "deal.create", "data": {"project_id": str(pr), "name": "Кредит", "lead_person_id": str(dup), "person_ids": [str(dup)]}})[0]
    assert d["ok"], d
    ops(dela, "sasha", {"op": "interaction.add", "data": {"at": dt.datetime.now(dt.timezone.utc).isoformat(), "kind": "call", "summary": "Созвон", "project_id": str(pr), "person_ids": [str(dup), str(live)]}})
    r = ops(dela, "sasha", {"op": "person.merge", "id": str(dup), "into": str(live)})[0]
    assert r["ok"], r
    assert r["row"]["phones"] == ["+7 916 123-45-67"] and "Женя Соколов (ромашка)" in r["row"]["aliases"] and r["row"]["role"] == "VP"
    with as_(dela, "sasha") as c:
        assert str(c.execute("SELECT person_id FROM tasks.tasks WHERE id = %s", (t["id"],)).fetchone()["person_id"]) == live
        deal = c.execute("SELECT lead_person_id::text AS lead, person_ids::text[] AS ids FROM crm.deals").fetchone()
        assert deal["lead"] == live and deal["ids"] == [live]
        assert c.execute("SELECT person_ids::text[] AS ids FROM crm.interactions").fetchone()["ids"] == [live]
        gone = c.execute("SELECT merged_into::text AS into_, archived_at FROM crm.people WHERE id = %s", (dup,)).fetchone()
    assert gone["into_"] == live and gone["archived_at"]
    # Слитый больше не кандидат: «кто это» видит одного.
    w = store.view(dela, "sasha", "who", q="Женя Соколов")
    assert w["sure"] and str(w["best"]["id"]) == live and len(w["candidates"]) == 1
    assert not ops(dela, "sasha", {"op": "person.merge", "id": str(live), "into": str(dup)})[0]["ok"]


def test_suggestion_person_name_uses_who(dela):
    p = person(dela, "sasha", "Евгений Соколов")
    r = ops(dela, "sasha", {"op": "suggestion.create", "suggestion": {
        "kind": "create", "payload": {"title": "Соколов: позвонить", "person_name": "Женя Соколов"}, "source": "meeting",
    }}, via="meetings")[0]
    assert r["ok"], r
    d = ops(dela, "sasha", {"op": "suggestion.decide", "id": str(r["suggestion"]["id"]), "decision": "accept"})[0]
    assert d["ok"], d
    assert str(d["task"]["person_id"]) == str(p)


# ── Контракт и Claude ──────────────────────────────────────────────────

def test_svod_contract_matches_server(dela):
    import json
    from pathlib import Path

    four = json.loads((Path(__file__).resolve().parents[1] / "contract" / "svod.json").read_text(encoding="utf-8"))
    assert set(four["features"]["example"]) <= set(store.FEATURES)
    assert {o["op"] for o in four["people"]["ops_examples"]} | {four["svod"]["set"]["op"]} <= set(store.HANDLERS)
    assert {"svod", "who"} <= set(store.VIEWS)
    ops(dela, "sasha", {"op": "svod.set", "key": "prompt.food", "body": "Еда.", "author": "phone"})
    got = store.sync(dela, "sasha", 0)["svod"][0]
    assert set(four["svod"]["sync_example"][0]) == set(got), set(four["svod"]["sync_example"][0]) ^ set(got)
    org(dela, "sasha", "Ромашка-банк")
    w = store.view(dela, "sasha", "who", q="Пример")
    assert set(four["people"]["who"]["answer"]) == set(w)


def test_claude_tool_person_alias_and_merge(dela):
    from pravka_dela import mcp_tools

    live = person(dela, "sasha", "Евгений Соколов", org_id=org(dela, "sasha", "Ромашка-банк"))
    out = mcp_tools.person(dela, "Женя Соколов из Ромашки", also=["Женя Соколов"], phone="+7 916 123-45-67")
    assert out.startswith("Записал") and "Женя Соколов" in out, out
    dup = person(dela, "sasha", "Соколов Е. (банк)")
    out = mcp_tools.person(dela, "Соколов Е. (банк)", merge_into="Евгений Соколов")
    assert out.startswith("Слил"), out
    w = store.view(dela, "sasha", "who", phone="89161234567")
    assert str(w["best"]["id"]) == live
    assert "не определился" in mcp_tools.person(dela, "Пётр Петров", also=["Петя"])
    assert dup


def test_api_who_svod_and_scoped_dengi(dela):
    """Через HTTP: параметры Свода и «кто это» доходят до вида; «Деньгам» — только money.* Свода."""
    from starlette.testclient import TestClient

    from pravka_dela import api, tokens
    from pravka_dela.config import Config

    p = person(dela, "sasha", "Евгений Соколов", phones=["+7 916 123-45-67"])
    ops(dela, "sasha", {"op": "svod.set", "key": "prompt.food", "body": "Еда.", "author": "phone"},
        {"op": "svod.set", "key": "money.partner", "value": {"pct": 30}, "author": "dengi"})
    phone = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'device', 'телефон')}"}
    meet = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'service', 'meetings')}"}
    dengi = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'service', 'dengi')}"}
    c = TestClient(api.build(Config(db_url=dela)))
    w = c.get("/api/view/who", params={"phone": "89161234567"}, headers=phone).json()
    assert w["sure"] and w["best"]["id"] == p
    w = c.get("/api/view/who", params={"q": "Женя Соколов"}, headers=meet).json()
    assert w["sure"] and w["best"]["id"] == p
    items = c.get("/api/view/svod", params={"key": "prompt.food"}, headers=meet).json()["items"]
    assert [i["key"] for i in items] == ["prompt.food"]
    # «Деньги»: свои записи — да; чужие ключи, дела, синк, люди — нет.
    items = c.get("/api/view/svod", headers=dengi).json()["items"]
    assert [i["key"] for i in items] == ["money.partner"]
    assert c.get("/api/view/svod", params={"key": "prompt.food"}, headers=dengi).status_code == 403
    assert c.get("/api/view/who", params={"q": "Соколов"}, headers=dengi).status_code == 403
    assert c.get("/api/sync", headers=dengi).status_code == 403
    r = c.post("/api/ops", headers=dengi, json={"ops": [{"op": "svod.set", "key": "money.summary", "value": {"debt": 1}, "author": "dengi"}]})
    assert r.status_code == 200 and r.json()["results"][0]["ok"]
    for bad in ({"op": "svod.set", "key": "prompt.food", "body": "взлом", "author": "dengi"},
                {"op": "task.create", "task": {"title": "Деньги завели дело"}}):
        assert c.post("/api/ops", headers=dengi, json={"ops": [bad]}).status_code == 403
    assert store.view(dela, "sasha", "svod", key="prompt.food")["items"][0]["body"] == "Еда."


def test_who_hints_abbreviation_role_and_first_name_with_company(dela):
    """Как спикеры подписаны во встречах: «(ЗФ)», «(финдир Ромашки)», «(терапевт)», одно имя с компанией."""
    firm = org(dela, "sasha", "Весёлый Огород")
    rom = org(dela, "sasha", "Ромашка")
    olya = person(dela, "sasha", "Ольга Петрова", org_id=firm)
    person(dela, "sasha", "Борис Петров", org_id=firm)
    dima = person(dela, "sasha", "Дмитрий Кузнецов", org_id=rom, role="финансовый директор")
    person(dela, "sasha", "Дмитрий Орлов", org_id=firm)
    anna = person(dela, "sasha", "Анна Иванова", role="терапевт")
    cases = {"Оля Петрова (ВО)": olya, "Дмитрий (финдир Ромашки)": dima, "Дмитрий (Ромашка)": dima,
             "Анна Иванова (терапевт)": anna}
    for q, want in cases.items():
        w = store.view(dela, "sasha", "who", q=q)
        assert w["sure"] and str(w["best"]["id"]) == want, (q, w)
    # Одно имя без компании — не уверен; «банк» не отличает компанию.
    assert not store.view(dela, "sasha", "who", q="Дмитрий")["sure"]
    assert not store.view(dela, "sasha", "who", q="Дмитрий (банк)")["sure"]


def test_who_latin_zoom_names_and_short_company(dela):
    zf = org(dela, "sasha", "ЗФ")
    v = person(dela, "sasha", "Василий Вельдяксов", org_id=org(dela, "sasha", "Восток Инвестиции"))
    lena = person(dela, "sasha", "Елена Смирнова", org_id=zf)
    for q, want in {"Vasiliy Veldyaksov (Vostok Investments)": v, "Лена Смирнова (ЗФ)": lena}.items():
        w = store.view(dela, "sasha", "who", q=q)
        assert w["sure"] and str(w["best"]["id"]) == want, (q, w)


def test_who_initials_and_diminutive_card(dela):
    """Подписи Zoom и почты: «a.filatov», «Dmitry L»; в карточке — уменьшительное («Вася»)."""
    f = person(dela, "sasha", "Александр Филатов")
    d = person(dela, "sasha", "Дмитрий Лебедев")
    person(dela, "sasha", "Дмитрий Орлов")
    v = person(dela, "sasha", "Вася Вельдяксов")
    for q, want in {"a.filatov": f, "Dmitry L": d, "Vasiliy Veldyaksov": v, "Василий Вельдяксов": v}.items():
        w = store.view(dela, "sasha", "who", q=q)
        assert w["sure"] and str(w["best"]["id"]) == want, (q, w)
