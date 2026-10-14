"""Люди сами (dela_0019, 14.10.2026): новый человек из Telegram или телефонной книжки — предложением kind = person.

Владелец согласился: «уверен — заводит сам с „Вернуть“, не уверен — вопрос в „Подскажи“, не деловой — больше не
спрашивать». Склеивает людей по-прежнему только он: «это тот же человек?» — всегда вопрос. Данные — выдуманные.
"""

from __future__ import annotations

from pravka_dela import ask, mcp_tools, store
from test_dela_schema import dela  # noqa: F401  (фикстура)


def ops(url, user, *items):
    return store.apply_ops(url, user, list(items), "app")["results"]


def person_sug(source_ref, payload, quote="Пишет про сделку с «Ромашкой»", source="telegram"):
    return {"op": "suggestion.create", "suggestion": {
        "for_user": "sasha", "kind": "person", "source": source, "source_ref": source_ref,
        "batch_ref": "people:2026-10-14", "batch_title": "Новые люди", "quote": quote, "payload": payload}}


def card(url, pid):
    with store.db.session(url, "sasha", "t") as c:
        return c.execute("SELECT p.*, o.name AS org_name, o.kind AS org_kind FROM crm.people p "
                         "LEFT JOIN crm.orgs o ON o.id = p.org_id WHERE p.id = %s", (pid,)).fetchone()


def test_sure_new_person_is_created_itself_with_company(dela):
    new = {"name": "Иван Петров (Ромашка Капитал)", "short": "Иван Петров", "aliases": ["Ivan Petrov"],
           "telegram_id": 5550001, "telegram_username": "ivan_p", "role": "партнёр фонда", "source": "Telegram, 14.10.2026"}
    r = ops(dela, "sasha", person_sug("tg:5550001", {"title": new["name"], "person": new, "org_name": "Ромашка Капитал",
                                                     "org_kind": "fund", "auto": True}))[0]
    assert r["ok"], r
    s = r["suggestion"]
    assert (s["status"], s["reason"]) == ("accepted", store.AUTO_PERSON_REASON)
    res = s["result"]
    assert res["created"] and res["org_created"]
    p = card(dela, res["person_id"])
    assert (p["name"], p["telegram_id"], p["telegram_username"], p["org_name"], p["org_kind"]) == (
        "Иван Петров (Ромашка Капитал)", 5550001, "ivan_p", "Ромашка Капитал", "fund")
    assert p["aliases"] == ["Ivan Petrov"]

    # Повтор той же находки (служба перезапустилась) — то же предложение, без второй карточки.
    again = ops(dela, "sasha", person_sug("tg:5550001", {"title": new["name"], "person": new, "auto": True}))[0]
    assert again["suggestion"]["id"] == s["id"]
    with store.db.session(dela, "sasha", "t") as c:
        assert c.execute("SELECT count(*) AS n FROM crm.people WHERE telegram_id = 5550001").fetchone()["n"] == 1

    # Компания по другому имени и написанию — та же; клиентом её автоматика не делает.
    with store.db.session(dela, "sasha", "t") as c:
        c.execute("INSERT INTO crm.orgs (name, aliases, kind, owner_id) VALUES ('Grow Fast', '{Гроу Фаст}', 'other', 'sasha')")
    r = ops(dela, "sasha", person_sug("contacts:c1", {"person": {"name": "Анна Смирнова (Гроу Фаст)", "phones": ["+7 900 000-00-01"]},
                                                      "org_name": "Гроу-Фаст", "auto": True}, source="contacts"))[0]
    assert r["ok"] and not r["suggestion"]["result"]["org_created"], r
    assert card(dela, r["suggestion"]["result"]["person_id"])["org_name"] == "Grow Fast"
    r = ops(dela, "sasha", person_sug("tg:5550002", {"person": {"name": "Олег Новиков (Новая Лавка)", "telegram_id": 5550002},
                                                     "org_name": "Новая Лавка", "org_kind": "client", "auto": True}))[0]
    assert card(dela, r["suggestion"]["result"]["person_id"])["org_kind"] == "other"


def test_unsure_waits_and_same_person_is_always_a_question(dela):
    with store.db.session(dela, "sasha", "t") as c:
        pid = c.execute("INSERT INTO crm.people (name, short, owner_id) VALUES ('Иван Петров (Ромашка)', 'Иван', 'sasha') "
                        "RETURNING id").fetchone()["id"]
    # Без auto, без основания и «это он?» с person_id — даже с auto — ждут владельца.
    assert ops(dela, "sasha", person_sug("tg:1", {"person": {"name": "Пётр"}, "ask": "Завести?"}))[0]["suggestion"]["status"] == "pending"
    assert ops(dela, "sasha", person_sug("tg:2", {"person": {"name": "Пётр"}, "auto": True}, quote=None))[0]["suggestion"]["status"] == "pending"
    same = ops(dela, "sasha", person_sug("tg:5550003", {
        "person": {"name": "Ivan Petrov", "telegram_id": 5550003, "telegram_username": "ivanpetrov"}, "person_id": str(pid),
        "auto": True, "ask": "Это Иван из «Ромашки»?",
        "options": [{"label": "Это он", "person_id": str(pid)}, {"label": "Новый человек", "new": True},
                    {"label": "Не для CRM", "reject": True, "reason": "не для CRM"}]}))[0]["suggestion"]
    assert same["status"] == "pending" and len(same["payload"]["options"]) == 3

    # «Это он» — Telegram и имя из профиля дописываются в карточку, второй карточки нет.
    r = ops(dela, "sasha", {"op": "suggestion.decide", "id": same["id"], "option": 0})[0]
    assert r["ok"] and r["suggestion"]["status"] == "accepted" and not r["person"]["created"], r
    p = card(dela, pid)
    assert (p["name"], p["telegram_id"], p["telegram_username"]) == ("Иван Петров (Ромашка)", 5550003, "ivanpetrov")
    assert "Ivan Petrov" in p["aliases"]


def test_new_option_reject_and_late_duplicate(dela):
    with store.db.session(dela, "sasha", "t") as c:
        pid = c.execute("INSERT INTO crm.people (name, owner_id) VALUES ('Мария Лебедева', 'sasha') RETURNING id").fetchone()["id"]
    opts = [{"label": "Это Мария", "person_id": str(pid)}, {"label": "Новый человек", "new": True},
            {"label": "Не для CRM", "reject": True, "reason": "не для CRM"}]
    q = ops(dela, "sasha", person_sug("tg:7001", {"person": {"name": "Мария Лебедева (Сигма)", "telegram_id": 7001},
                                                  "person_id": str(pid), "org_name": "Сигма", "ask": "Это Мария?", "options": opts}))[0]
    r = ops(dela, "sasha", {"op": "suggestion.decide", "id": q["suggestion"]["id"], "option": 1})[0]
    assert r["ok"] and r["person"]["created"] and str(r["person"]["person_id"]) != str(pid), r
    assert card(dela, r["person"]["person_id"])["org_name"] == "Сигма"

    q = ops(dela, "sasha", person_sug("tg:7002", {"person": {"name": "Курьер"}, "ask": "Завести?", "options": opts}))[0]
    r = ops(dela, "sasha", {"op": "suggestion.decide", "id": q["suggestion"]["id"], "option": 2})[0]
    assert (r["suggestion"]["status"], r["suggestion"]["reason"]) == ("rejected", "не для CRM")

    # Пока вопрос ждал, Telegram этого человека уже лёг в карточку (дописал владелец) — «да» дописывает туда, а не плодит дубль.
    q = ops(dela, "sasha", person_sug("tg:7003", {"person": {"name": "Maria L", "telegram_id": 7003}, "ask": "Завести?"}))[0]
    assert ops(dela, "sasha", {"op": "person.add", "id": str(pid), "add": {"telegram_id": 7003}})[0]["ok"]
    r = ops(dela, "sasha", {"op": "suggestion.decide", "id": q["suggestion"]["id"], "decision": "accept"})[0]
    assert r["ok"] and not r["person"]["created"] and str(r["person"]["person_id"]) == str(pid), r
    assert "Maria L" in card(dela, pid)["aliases"]


def test_rename_and_words_for_claude(dela):
    with store.db.session(dela, "sasha", "t") as c:
        pid = c.execute("INSERT INTO crm.people (name, owner_id) VALUES ('Олег Новиков', 'sasha') RETURNING id").fetchone()["id"]
    q = ops(dela, "sasha", person_sug("contacts:c9", {
        "title": "Олег Новиков (Бета)", "person": {"name": "Олег Новиков (Бета)"}, "person_id": str(pid), "rename": True,
        "ask": "В телефоне он теперь «Олег Новиков Бета» — так и назвать?"}, source="contacts"))[0]["suggestion"]
    line = ask._sug_line(q, None)
    assert line.startswith("человек: Олег Новиков (Бета)") and "так и назвать" in line, line
    assert "человек: Олег Новиков (Бета)" in mcp_tools.view(dela, "new")
    r = ops(dela, "sasha", {"op": "suggestion.decide", "id": q["id"], "decision": "accept"})[0]
    assert r["person"]["was_name"] == "Олег Новиков"
    p = card(dela, pid)
    assert p["name"] == "Олег Новиков (Бета)" and "Олег Новиков" in p["aliases"]
    # Очков за решённое само нет; за ответ владельца — как за любой разбор «Нового».
    assert "people_new" in store.FEATURES
