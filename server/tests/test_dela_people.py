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


def test_scoped_eda_and_sport(dela):
    """«Еда» и «Спорт» на компе (13.10.2026): свои ключи Свода пишут и читают, «Еде» — ещё промпт еды; чужого — нет."""
    from starlette.testclient import TestClient

    from pravka_dela import api, tokens
    from pravka_dela.config import Config

    ops(dela, "sasha", {"op": "svod.set", "key": "prompt.food", "body": "Еда.", "author": "phone"},
        {"op": "svod.set", "key": "money.partner", "value": {"pct": 30}, "author": "dengi"})
    eda = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'service', 'eda')}"}
    sport = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'service', 'sport')}"}
    c = TestClient(api.build(Config(db_url=dela)))
    r = c.post("/api/ops", headers=eda, json={"ops": [
        {"op": "svod.set", "key": "food.goals", "value": {"kcal": 2500}, "author": "eda"}]})
    assert r.status_code == 200 and r.json()["results"][0]["ok"]
    rev = c.get("/api/view/svod", params={"key": "food.goals"}, headers=eda).json()["items"][0]["rev"]
    stale = c.post("/api/ops", headers=eda, json={"ops": [
        {"op": "svod.set", "key": "food.goals", "value": {"kcal": 2400}, "author": "eda", "base_rev": rev - 1}]})
    assert not stale.json()["results"][0]["ok"]
    assert c.get("/api/view/svod", params={"key": "prompt.food"}, headers=eda).json()["items"][0]["body"] == "Еда."
    assert [i["key"] for i in c.get("/api/view/svod", headers=eda).json()["items"]] == ["food.goals"]
    assert c.get("/api/view/svod", params={"key": "money.partner"}, headers=eda).status_code == 403
    assert c.get("/api/view/svod", params={"key": "models.routes"}, headers=eda).status_code == 200
    assert c.post("/api/ops", headers=eda, json={"ops": [
        {"op": "svod.set", "key": "prompt.food", "body": "взлом", "author": "eda"}]}).status_code == 403
    assert c.get("/api/sync", headers=eda).status_code == 403
    r = c.post("/api/ops", headers=sport, json={"ops": [
        {"op": "svod.set", "key": "sport.rules", "value": {"hr_ceiling": 152}, "author": "sport"}]})
    assert r.status_code == 200 and r.json()["results"][0]["ok"]
    assert c.get("/api/view/svod", params={"key": "prompt.food"}, headers=sport).status_code == 403
    assert c.get("/api/view/svod", params={"key": "food.goals"}, headers=sport).json()["items"][0]["value"] == {"kcal": 2500}
    assert c.post("/api/ops", headers=sport, json={"ops": [
        {"op": "svod.set", "key": "food.goals", "value": {}, "author": "sport"}]}).status_code == 403


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
    v = person(dela, "sasha", "Василий Белоусов", org_id=org(dela, "sasha", "Север Капитал"))
    lena = person(dela, "sasha", "Елена Смирнова", org_id=zf)
    for q, want in {"Vasiliy Belousov (Sever Capital)": v, "Лена Смирнова (ЗФ)": lena}.items():
        w = store.view(dela, "sasha", "who", q=q)
        assert w["sure"] and str(w["best"]["id"]) == want, (q, w)


def test_who_initials_and_diminutive_card(dela):
    """Подписи Zoom и почты: «a.komarov», «Dmitry L»; в карточке — уменьшительное («Вася»)."""
    f = person(dela, "sasha", "Александр Комаров")
    d = person(dela, "sasha", "Дмитрий Лебедев")
    person(dela, "sasha", "Дмитрий Орлов")
    v = person(dela, "sasha", "Вася Белоусов")
    for q, want in {"a.komarov": f, "Dmitry L": d, "Vasiliy Belousov": v, "Василий Белоусов": v}.items():
        w = store.view(dela, "sasha", "who", q=q)
        assert w["sure"] and str(w["best"]["id"]) == want, (q, w)


def test_claude_prices_come_from_svod(dela):
    """Цены Claude — из Свода (claude.prices), таблица в llm.py — только запас (06.10.2026, LOGIC.md §11)."""
    from pravka_dela import llm

    usage = {"model": "claude-opus-5-5", "input": 1_000_000, "output": 0}
    llm._svod.update(at=-llm.SVOD_EVERY, prices={})
    assert llm.cost(usage) == 4.0                       # без Свода — запас
    v = {"batch_discount": 0.5, "claude-opus-5-5": {"in": 5, "out": 25, "cache_read": 0.5, "cache_write_5m": 6.25,
                                                     "cache_write_1h": 10}}
    assert ops(dela, "sasha", {"op": "svod.set", "key": "claude.prices", "value": v, "author": "claude"})[0]["ok"]
    assert llm.cost(usage, url=dela) == 5.0             # из Свода
    assert llm.cost({"model": "claude-opus-5-5-20260601", "input": 0, "output": 1_000_000}) == 25.0
    llm._svod.update(at=-llm.SVOD_EVERY, prices={})


# ── Своё имя сильнее чужого другого имени, род, коннектор без подстроки (аудит 15.10.2026, №1, №22) ──────────────

def test_own_short_beats_foreign_alias(dela):
    """«Ваня» — короткое имя одного и другое имя второго (сверка контактов дописала): узнаётся тот, чьё это имя."""
    a = person(dela, "sasha", "Иван Тестов", short="Ваня")
    b = person(dela, "sasha", "Иван Пробный", aliases=["Ваня"])
    w = store.view(dela, "sasha", "who", q="Ваня")
    assert w["sure"] and str(w["best"]["id"]) == a, w
    how = {str(c["id"]): c["how"] for c in w["candidates"]}
    assert how[a] == "имя" and how[b] == "другое имя"
    with as_(dela, "sasha") as c:
        assert str(c.execute("SELECT person_id FROM crm.match_name('Ваня')").fetchone()["person_id"]) == a
    # Одно другое имя без соперника — по-прежнему уверенно (имена из Telegram).
    p = person(dela, "sasha", "Пётр Образцов", aliases=["Петруха Телеграм"])
    w = store.view(dela, "sasha", "who", q="Петруха Телеграм")
    assert w["sure"] and str(w["best"]["id"]) == p and w["best"]["how"] == "другое имя"


def test_two_own_short_names_stay_ambiguous(dela):
    person(dela, "sasha", "Иван Тестов", short="Ваня")
    person(dela, "sasha", "Иван Сидоров", short="Ваня")
    person(dela, "sasha", "Иван Пробный", aliases=["Ваня"])
    assert not store.view(dela, "sasha", "who", q="Ваня")["sure"]
    with as_(dela, "sasha") as c:
        # Двое своим именем — никого, к другим именам не идём: одиночное другое имя не перебивает тёзок.
        assert c.execute("SELECT person_id FROM crm.match_name('Ваня')").fetchone()["person_id"] is None


def test_person_add_does_not_take_someone_elses_name(dela):
    a = person(dela, "sasha", "Иван Тестов", short="Ваня")
    c_ = person(dela, "sasha", "Иван Третий")
    old = person(dela, "sasha", "Пётр Старый")
    ops(dela, "sasha", {"op": "person.set", "id": old, "set": {"archived_at": "2026-10-01T00:00:00+03:00"}})
    r = ops(dela, "sasha", {"op": "person.add", "id": c_, "add": {"aliases": ["ваня", "Ванечка", "Пётр Старый"]}})[0]
    assert r["ok"] and r["changed"], r
    assert r["row"]["aliases"] == ["Ванечка", "Пётр Старый"]  # архивная карточка имени не держит
    assert [(s["alias"], str(s["person_id"]), s["name"]) for s in r["skipped"]] == [("ваня", a, "Иван Тестов")]
    # «Ваня» по-прежнему узнаётся однозначно.
    assert str(store.view(dela, "sasha", "who", q="Ваня")["best"]["id"]) == a
    # Через коннектор — то же словами.
    from pravka_dela import mcp_tools
    out = mcp_tools.person(dela, "Иван Третий", also=["Ваня"])
    assert "«Ваня» не дописал — так зовут Иван Тестов" in out, out


def test_new_card_and_merge_do_not_take_someone_elses_name(dela):
    """№1 для новой карточки «Людей сами» и слияния: чужое имя другим именем не пишется, а ответ говорит — skipped."""
    a = person(dela, "sasha", "Иван Тестов", short="Ваня")
    r = ops(dela, "sasha", {"op": "suggestion.create", "suggestion": {
        "for_user": "sasha", "kind": "person", "source": "telegram", "source_ref": "tg:42", "quote": "пишет про счёт",
        "payload": {"person": {"name": "Иван Новый", "aliases": ["Ваня", "Ivan Novy"], "telegram_id": 42}, "auto": True}}})[0]
    res = r["suggestion"]["result"]
    assert res["created"] and [(x["alias"], str(x["person_id"])) for x in res["skipped"]] == [("Ваня", a)], res
    with as_(dela, "sasha") as c:
        assert c.execute("SELECT aliases FROM crm.people WHERE id = %s", (res["person_id"],)).fetchone()["aliases"] == ["Ivan Novy"]
    dup = person(dela, "sasha", "Ваня")  # дубль, которого зовут так же, как короткое имя живой карточки
    m = ops(dela, "sasha", {"op": "person.merge", "id": dup, "into": str(res["person_id"])})[0]
    assert m["ok"] and [x["alias"] for x in m["skipped"]] == ["Ваня"], m


def test_gender_of_first_names(dela):
    """Род мягко (15.10.2026): имя или фамилия другого рода — кандидат «похоже», но не уверенно. «Евгения Соколова» — и
    женщина, и родительный от «Евгений Соколов» («у Евгения Соколова»): его карточка видна, сама не ставится. Падеж и
    вариант одного рода — по-прежнему уверенно."""
    def score(w, pid):
        return next((c["score"] for c in w["candidates"] if str(c["id"]) == pid), None)

    sokolov = person(dela, "sasha", "Евгений Соколов")
    w = store.view(dela, "sasha", "who", q="Евгения Соколова")
    assert not w["sure"] and str(w["best"]["id"]) == sokolov and w["best"]["how"] == "похоже", w
    assert score(w, sokolov) == 0.8, w
    w = store.view(dela, "sasha", "who", q="Евгении Соколовой")  # только женская форма фамилии
    assert not w["sure"] and score(w, sokolov) == 0.8, w
    w = store.view(dela, "sasha", "who", q="Евгением Соколовым")
    assert w["sure"] and str(w["best"]["id"]) == sokolov and w["best"]["how"] == "имя", w
    # Родительный мужского имени — он, кандидатом («у Александра Петрова»): раньше запрет терял его вовсе.
    petrov = person(dela, "sasha", "Александр Петров", short="Александр")
    w = store.view(dela, "sasha", "who", q="Александра Петрова")
    assert str(w["best"]["id"]) == petrov and not w["sure"] and score(w, petrov) == 0.8, w
    w = store.view(dela, "sasha", "who", q="Александра")
    assert str(w["best"]["id"]) == petrov and not w["sure"], w
    # Род — по справочнику, не по окончанию: «Валера» — мужское, и «Валерия» через него не становится «Валерием».
    val = person(dela, "sasha", "Валерий Иванов")
    w = store.view(dela, "sasha", "who", q="Валерия Иванова")
    assert not w["sure"] and score(w, val) == 0.8, w
    w = store.view(dela, "sasha", "who", q="Валера Иванов")
    assert w["sure"] and str(w["best"]["id"]) == val, w
    nata = person(dela, "sasha", "Наталья Пробная")
    w = store.view(dela, "sasha", "who", q="Наталия Пробная")
    assert w["sure"] and str(w["best"]["id"]) == nata, w
    sasha_f = person(dela, "sasha", "Александра Тестова")
    w = store.view(dela, "sasha", "who", q="Александр Тестов")
    assert not (w["sure"] and str(w["best"]["id"]) == sasha_f), w
    # Своя карточка того же рода, если есть, — точнее: «Александра Петрова» при живой Александре Петровой — она.
    alexandra = person(dela, "sasha", "Александра Петрова")
    w = store.view(dela, "sasha", "who", q="Александра Петрова")
    assert w["sure"] and str(w["best"]["id"]) == alexandra, w


def test_different_names_with_shared_beginning_are_not_cases(dela):
    """Падеж — продолжение основы только буквами окончаний (сквозной тест №25/№60, 15.10.2026): «Марина» — не «Мария»,
    «Новик» — не «Новиков», «Антонина» — не «Антон». Падежи одного имени узнаются по-прежнему."""
    from pravka_dela import people as pp
    for a, b in (("марина", "мария"), ("марину", "мария"), ("новик", "новиков"), ("антонина", "антон"),
                 ("василиса", "василий")):
        assert not pp.same(a, b), (a, b)
    for a, b in (("евгений", "евгением"), ("сергей", "сергеем"), ("дмитрий", "дмитрием"), ("мария", "марии"),
                 ("мария", "марией"), ("марина", "марину"), ("наталья", "натальей"), ("соколов", "соколовым")):
        assert pp.same(a, b), (a, b)
    maria = person(dela, "sasha", "Мария")
    w = store.view(dela, "sasha", "who", q="Марина (Бета)")
    assert not w["sure"] and all(str(c["id"]) != maria for c in w["candidates"]), w
    w = store.view(dela, "sasha", "who", q="Марией")
    assert w["sure"] and str(w["best"]["id"]) == maria, w
    novikov = person(dela, "sasha", "Пётр Новиков")
    w = store.view(dela, "sasha", "who", q="Пётр Новик")
    assert not (w["sure"] and str(w["best"]["id"]) == novikov), w


def test_gender_dictionary_covers_every_full_name():
    from pravka_dela import people as pp
    assert {x for v in pp.DIMINUTIVE.values() for x in v} <= pp.MALE_FULL | pp.FEMALE_FULL
    assert not pp.MALE_FULL & pp.FEMALE_FULL
    assert (pp.name_gender("илья"), pp.name_gender("валера"), pp.name_gender("любовь"), pp.name_gender("саша")) == \
        ("m", "m", "f", None)


def test_connector_find_person_without_substring(dela):
    from pravka_dela import mcp_tools

    anna = person(dela, "sasha", "Анна Петрова")
    with as_(dela, "sasha") as c:
        try:
            got = mcp_tools.find_person(c, "Петров")
        except mcp_tools.NameError_ as e:
            got = str(e)
    # Раньше подстрока без границы слова отдавала её молча.
    assert isinstance(got, str) and "не определился" in got and "Анна Петрова" in got, got
    with as_(dela, "sasha") as c:
        assert str(mcp_tools.find_person(c, "Анна Петрова")["id"]) == anna
        assert str(mcp_tools.find_person(c, anna)["id"]) == anna  # и по id


def test_connector_merge_only_by_exact_name_or_id(dela):
    from pravka_dela import mcp_tools

    live = person(dela, "sasha", "Евгений Соколов", org_id=org(dela, "sasha", "Ромашка-банк"))
    dup = person(dela, "sasha", "Соколов Е. (банк)")
    # Догадка who («Женя Соколов» — он) для слияния не годится: отмены у слияния нет.
    assert mcp_tools.person(dela, "Соколов Е. (банк)", merge_into="Женя Соколов").startswith("Не вышло"), "into"
    assert mcp_tools.person(dela, "Соколов Е.", merge_into="Евгений Соколов").startswith("Не вышло"), "dup"
    with as_(dela, "sasha") as c:
        assert c.execute("SELECT merged_into FROM crm.people WHERE id = %s", (dup,)).fetchone()["merged_into"] is None
    out = mcp_tools.person(dela, dup, merge_into=live)
    assert out.startswith("Слил"), out


def test_migration_20_over_live_people(admin_dsn):
    """dela_0020 поверх Дел, где люди уже есть: «Ваня» — короткое имя одного и другое имя второго. До миграции
    crm.match_name не узнавал никого (два совпадения на равных), после — того, чьё это имя; данные не тронуты."""
    import secrets as _secrets
    from urllib.parse import urlparse, urlunparse

    import psycopg
    from psycopg import sql

    from pravka_dela import db as dela_db

    name = f"pa_mig20_{_secrets.token_hex(4)}"
    with psycopg.connect(admin_dsn, autocommit=True) as a:
        a.execute(sql.SQL("CREATE DATABASE {} ENCODING 'UTF8' TEMPLATE template0").format(sql.Identifier(name)))
    u = urlparse(admin_dsn)
    url = urlunparse((u.scheme, u.netloc, "/" + name, "", "", ""))
    try:
        files = dict(dela_db._sql_files())
        before = {k: v for k, v in files.items() if k.startswith("dela_") and k < "dela_0020.sql"}
        with psycopg.connect(url, autocommit=True) as c:
            c.execute("CREATE SCHEMA core")
            c.execute("CREATE TABLE core.migrations (name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())")
            dela_db.apply_pending(c, before)
        with dela_db.session(url, "system", "test") as c:
            c.execute("INSERT INTO crm.users (id, name, role) VALUES ('sasha', 'Саша', 'owner')")
            a_id = c.execute("INSERT INTO crm.people (name, short, owner_id) VALUES ('Иван Тестов', 'Ваня', 'sasha') "
                             "RETURNING id").fetchone()["id"]
            c.execute("INSERT INTO crm.people (name, aliases, owner_id) VALUES ('Иван Пробный', ARRAY['Ваня'], 'sasha')")
            assert c.execute("SELECT person_id FROM crm.match_name('Ваня')").fetchone()["person_id"] is None
            seq = c.execute("SELECT max(seq) AS s FROM crm.people").fetchone()["s"]
        with psycopg.connect(url, autocommit=True) as c:
            assert dela_db.apply_pending(c, files)[0] == "dela_0020.sql"
        with dela_db.session(url, "system", "test") as c:
            assert c.execute("SELECT person_id FROM crm.match_name('Ваня')").fetchone()["person_id"] == a_id
            assert c.execute("SELECT max(seq) AS s FROM crm.people").fetchone()["s"] == seq  # строки не тронуты
    finally:
        with psycopg.connect(admin_dsn, autocommit=True) as a:
            a.execute(sql.SQL("DROP DATABASE IF EXISTS {} WITH (FORCE)").format(sql.Identifier(name)))


# ── Слияние переносит всё и только у владельца (аудит 15.10.2026, №23; dela_0023) ──────────────────────────────

def test_person_merge_moves_team_meetings_questions_and_family(dela):
    """Слияние переносит и то, чего прежний список не знал: команду сделки (по ней сотрудник видит клиента), участников
    встречи, ждущие вопросы «Нового» (это он? и варианты кнопками) и семью в Своде; Telegram дубля, который живой не
    достался (у неё свой), — по-прежнему ведёт в неё (who: «номер слитой карточки»)."""
    from pravka_dela import people

    pr = project(dela, "sasha", "ООО Ромашка")
    live = person(dela, "sasha", "Иван Тестов", telegram_id=900002, telegram_username="ivan_test")
    dup = person(dela, "sasha", "Ваня Тестов (Ромашка)", telegram_id=900001, telegram_username="vanya_t",
                 phones=["+7 900 000-00-01"])
    other = person(dela, "sasha", "Пётр Пробный")
    d = ops(dela, "sasha", {"op": "deal.create", "data": {"project_id": str(pr), "name": "Кредит", "team_ids": [dup, other]}})[0]
    assert d["ok"], d
    with as_(dela, "sasha") as c:
        m = c.execute("INSERT INTO crm.meetings (ref, started_at, owner_id, person_ids) VALUES ('901', now(), 'sasha', %s::uuid[]) "
                      "RETURNING id", ([dup, other],)).fetchone()["id"]
    q1 = ops(dela, "sasha", {"op": "suggestion.create", "suggestion": {
        "for_user": "sasha", "kind": "person", "source": "telegram", "source_ref": "tgfind:x",
        "payload": {"title": "Ваня Тестов", "person_id": dup, "ask": "Это он в Telegram?"}}})[0]["suggestion"]
    q2 = ops(dela, "sasha", {"op": "suggestion.create", "suggestion": {
        "for_user": "sasha", "kind": "create", "source": "meeting", "quote": "Ваня пришлёт расчёт",
        "payload": {"title": "Ваня: расчёт", "person_name": "Ваня", "ask": "Кто это — Ваня?",
                    "options": [{"label": "Ваня Тестов", "person_id": dup}, {"label": "Пётр Пробный", "person_id": other}]}}})[0]
    assert q2["ok"] and q2["suggestion"]["status"] == "pending", q2
    assert ops(dela, "sasha", {"op": "svod.set", "key": "people.family", "value": [other, dup], "author": "web"})[0]["ok"]

    r = ops(dela, "sasha", {"op": "person.merge", "id": dup, "into": live})[0]
    assert r["ok"], r
    assert r["moved"] >= 5, r  # команда сделки, встреча, два вопроса, семья
    assert "Ваня Тестов (Ромашка)" in r["row"]["aliases"] and r["row"]["phones"] == ["+7 900 000-00-01"]
    assert r["row"]["telegram_id"] == 900002  # свой Telegram у живой не затирается
    with as_(dela, "sasha") as c:
        assert set(c.execute("SELECT team_ids::text[] AS t FROM crm.deals").fetchone()["t"]) == {live, other}
        assert c.execute("SELECT person_ids::text[] AS p FROM crm.meetings WHERE id = %s", (m,)).fetchone()["p"] == [live, other]
        p1 = c.execute("SELECT payload FROM tasks.suggestions WHERE id = %s", (q1["id"],)).fetchone()["payload"]
        p2 = c.execute("SELECT payload FROM tasks.suggestions WHERE id = %s", (q2["suggestion"]["id"],)).fetchone()["payload"]
        fam = c.execute("SELECT value, author FROM crm.svod WHERE key = 'people.family'").fetchone()
    assert p1["person_id"] == live
    assert [o.get("person_id") for o in p2["options"]] == [live, other]
    assert fam["value"] == [other, live] and fam["author"] == "merge"
    # Ключ дубля, которого живая не взяла (у неё свой Telegram), ведёт в неё; по имени дубль не кандидат.
    for kw in ({"telegram_id": "900001"}, {"telegram": "@vanya_t"}):
        w = store.view(dela, "sasha", "who", **kw)
        assert w["sure"] and str(w["best"]["id"]) == live and w["best"]["how"] == people.MERGED_HOW, (kw, w)
    w = store.view(dela, "sasha", "who", telegram_id="900002")
    assert w["sure"] and w["best"]["how"] == "telegram"  # свой ключ живой — свой how
    assert [str(c["id"]) for c in store.view(dela, "sasha", "who", q="Ваня Тестов")["candidates"]] == [live]


def test_person_merge_only_owner_once_and_past_rls(dela):
    """Склеивает людей только владелец; уже слитого второй раз не сливаем; дубль, заведённый другим пользователем, и
    его дело, невидимые владельцу, переезжают мимо RLS; учётка Дел дубля — живой."""
    live = person(dela, "sasha", "Иван Тестов")
    dup = person(dela, "sasha", "Иван Тестов (2)")
    no = ops(dela, "natasha", {"op": "person.merge", "id": dup, "into": live})[0]
    assert not no["ok"] and "только владелец" in no["error"], no
    assert ops(dela, "sasha", {"op": "person.merge", "id": dup, "into": live})[0]["ok"]
    again = ops(dela, "sasha", {"op": "person.merge", "id": dup, "into": person(dela, "sasha", "Иван Новый")})[0]
    assert not again["ok"] and "уже слит" in again["error"], again

    # Дубль Наташи: его карточку и её дело о нём владелец не видит (RLS), а слияние переносит всё.
    with as_(dela, "natasha") as c:
        hers = str(c.execute("INSERT INTO crm.people (name, owner_id) VALUES ('Пётр Пробный', 'natasha') RETURNING id")
                   .fetchone()["id"])
    t = ops(dela, "natasha", {"op": "task.create", "task": {"title": "Позвонить Петру", "person_id": hers}})[0]
    assert t["ok"], t
    with as_(dela, "sasha") as c:
        assert c.execute("SELECT 1 FROM crm.people WHERE id = %s", (hers,)).fetchone() is None  # не видит
    r = ops(dela, "sasha", {"op": "person.merge", "id": hers, "into": live})[0]
    assert r["ok"], r
    with store.db.session(dela, "system", "t") as c:
        assert str(c.execute("SELECT merged_into FROM crm.people WHERE id = %s", (hers,)).fetchone()["merged_into"]) == live
        assert str(c.execute("SELECT person_id FROM tasks.tasks WHERE id = %s", (t["task"]["id"],)).fetchone()["person_id"]) == live

    # Учётка: карточка пользователя Дел слита в другую — учётка переехала (crm.users.person_id меняет только system).
    with store.db.session(dela, "system", "t") as c:
        acc = str(c.execute("INSERT INTO crm.people (name, owner_id, user_id) VALUES ('Марина Учётная', 'sasha', 'marianna') "
                            "RETURNING id").fetchone()["id"])
        c.execute("UPDATE crm.users SET person_id = %s WHERE id = 'marianna'", (acc,))
    card = person(dela, "sasha", "Марина Учётная-Тестова")
    r = ops(dela, "sasha", {"op": "person.merge", "id": acc, "into": card})[0]
    assert r["ok"] and r["row"]["user_id"] == "marianna", r
    with store.db.session(dela, "system", "t") as c:
        assert str(c.execute("SELECT person_id FROM crm.users WHERE id = 'marianna'").fetchone()["person_id"]) == card
        assert c.execute("SELECT user_id FROM crm.people WHERE id = %s", (acc,)).fetchone()["user_id"] is None


def test_merge_refs_cover_every_person_column(dela):
    """Новая ссылка на человека — строка в REFS и в теле crm.person_merge: колонки с людьми в crm и tasks должны быть в
    списке (тест ловит забытую колонку, как test_dela_api_scope — забытый путь)."""
    from pravka_dela import people

    known = {(t, c) for t, c, _ in people.REFS}
    with store.db.session(dela, "system", "t") as c:
        cols = c.execute(
            "SELECT c.table_schema || '.' || c.table_name AS t, c.column_name AS c FROM information_schema.columns c "
            "JOIN information_schema.tables tb ON tb.table_schema = c.table_schema AND tb.table_name = c.table_name "
            "WHERE c.table_schema IN ('crm', 'tasks') AND tb.table_type = 'BASE TABLE' "
            "AND (c.column_name LIKE %s OR c.column_name IN ('requested_by', 'team_ids', 'merged_into'))",
            ("%person%",)).fetchall()
        body = c.execute("SELECT pg_get_functiondef('crm.person_merge(uuid,uuid)'::regprocedure) AS d").fetchone()["d"]
    missing = {(r["t"], r["c"]) for r in cols} - known
    assert not missing, missing
    # REFS — описание для читателя; переносит ссылки тело функции базы. Колонку дописали в REFS, а в функцию забыли —
    # слияние молча её пропускало бы (проверка 15.10.2026): каждая колонка — своим UPDATE в теле.
    flat = " ".join(body.split())
    lost = {(r["t"], r["c"]) for r in cols if f"UPDATE {r['t']} SET {r['c']} " not in flat}
    assert not lost, lost
    assert cols, "запрос колонок пуст — проверять нечего"


def test_migration_23_over_live_people(admin_dsn):
    """dela_0023 поверх Дел, где люди, сделки и встречи уже есть: миграция только заводит функции (данные не трогает),
    после неё слияние владельцем переносит команду сделки и участников встречи."""
    import secrets as _secrets
    from urllib.parse import urlparse, urlunparse

    import psycopg
    from psycopg import sql

    from pravka_dela import db as dela_db

    name = f"pa_mig23_{_secrets.token_hex(4)}"
    with psycopg.connect(admin_dsn, autocommit=True) as a:
        a.execute(sql.SQL("CREATE DATABASE {} ENCODING 'UTF8' TEMPLATE template0").format(sql.Identifier(name)))
    u = urlparse(admin_dsn)
    url = urlunparse((u.scheme, u.netloc, "/" + name, "", "", ""))
    try:
        files = dict(dela_db._sql_files())
        before = {k: v for k, v in files.items() if k.startswith("dela_") and k < "dela_0023.sql"}
        with psycopg.connect(url, autocommit=True) as c:
            c.execute("CREATE SCHEMA core")
            c.execute("CREATE TABLE core.migrations (name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())")
            dela_db.apply_pending(c, before)
        with dela_db.session(url, "system", "test") as c:
            c.execute("INSERT INTO crm.users (id, name, role) VALUES ('sasha', 'Саша', 'owner')")
            live, dup = (c.execute("INSERT INTO crm.people (name, owner_id) VALUES (%s, 'sasha') RETURNING id", (n,))
                         .fetchone()["id"] for n in ("Иван Тестов", "Ваня Тестов"))
            pr = c.execute("INSERT INTO crm.projects (name, kind, sphere, owner_id) VALUES ('ООО Ромашка', 'client', 'work', "
                           "'sasha') RETURNING id").fetchone()["id"]
            c.execute("INSERT INTO crm.deals (project_id, name, team_ids) VALUES (%s, 'Кредит', %s)", (pr, [dup]))
            c.execute("INSERT INTO crm.meetings (ref, started_at, owner_id, person_ids) VALUES ('5', now(), 'sasha', %s)", ([dup],))
            seq = c.execute("SELECT max(seq) AS s FROM crm.people").fetchone()["s"]
        with psycopg.connect(url, autocommit=True) as c:
            assert dela_db.apply_pending(c, files)[0] == "dela_0023.sql"
        with dela_db.session(url, "system", "test") as c:
            assert c.execute("SELECT max(seq) AS s FROM crm.people").fetchone()["s"] == seq  # строки не тронуты
        with dela_db.session(url, "sasha", "test") as c:
            r = c.execute("SELECT crm.person_merge(%s, %s) AS r", (dup, live)).fetchone()["r"]
            assert r["moved"] == 2, r
            assert c.execute("SELECT team_ids FROM crm.deals").fetchone()["team_ids"] == [live]
            assert c.execute("SELECT person_ids FROM crm.meetings").fetchone()["person_ids"] == [live]
    finally:
        with psycopg.connect(admin_dsn, autocommit=True) as a:
            a.execute(sql.SQL("DROP DATABASE IF EXISTS {} WITH (FORCE)").format(sql.Identifier(name)))
