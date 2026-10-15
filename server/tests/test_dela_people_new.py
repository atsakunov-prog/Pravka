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


# ── Назвал человека во встрече — он в CRM (аудит 15.10.2026, №25/№60) ─────────
# Владелец: «Назвал человека в расшифровке… — он должен оказаться в CRM… Есть карточка — спикер привязан к ней. Нет —
# карточка заводится (неясно — один вопрос в „Подскажи“)». Решает сервер (people.new_person). Люди — выдуманные.

def meet_sug(name, key=None, meeting_ref="101", seconds=300, name_source="manual", auto=True, source=None):
    """Предложение службы встреч по контракту §2.3 (как worker/people.py _person_op)."""
    person = {"name": name, **({"source": source} if source else {})}
    return {"op": "suggestion.create", "suggestion": {
        "kind": "person", "source": "meeting", "source_ref": key or f"meeting-person:{name.lower()}",
        "batch_ref": f"meeting:{meeting_ref}", "batch_title": "Альфа: фонды · 14.10",
        "quote": f"Встреча «Альфа: фонды» 14.10: «{name}» — 5 мин речи (имя дал ты)",
        "payload": {"title": name, "where": "встреча «Альфа: фонды» 14.10 · 5 мин речи · имя дал ты",
                    "person": person, "speaker": {"meeting_ref": meeting_ref, "label": "SPEAKER_01", "seconds": seconds,
                                                  "name_source": name_source,
                                                  "url": f"https://meetings.example.org/m/{meeting_ref}"},
                    **({"auto": True} if auto else {})}}}


def people_named(url, name):
    with store.db.session(url, "sasha", "t") as c:
        return c.execute("SELECT * FROM crm.people WHERE name = %s ORDER BY created_at", (name,)).fetchall()


def test_meeting_person_sure_is_created_itself(dela):
    r = ops(dela, "sasha", meet_sug("Пётр Новиков"))[0]
    assert r["ok"], r
    s = r["suggestion"]
    assert (s["status"], s["reason"]) == ("accepted", store.AUTO_PERSON_REASON) and s["result"]["created"], s
    p = card(dela, s["result"]["person_id"])
    assert (p["name"], p["source"], p["org_id"]) == ("Пётр Новиков", "встреча: Альфа: фонды", None)
    # Служба сама сказала, откуда карточка, — её слово; подпись в скобках — компания, только если такая уже есть.
    with store.db.session(dela, "sasha", "t") as c:
        c.execute("INSERT INTO crm.orgs (name, kind, owner_id) VALUES ('Ромашка Капитал', 'fund', 'sasha')")
    r = ops(dela, "sasha", meet_sug("Павел Орлов (Ромашка-Капитал)", source="встреча 14.10.2026: Альфа: фонды"))[0]
    p = card(dela, r["suggestion"]["result"]["person_id"])
    assert (p["name"], p["source"], p["org_name"], p["note"]) == (
        "Павел Орлов", "встреча 14.10.2026: Альфа: фонды", "Ромашка Капитал", None)
    # Подпись — не компания из справочника: новую компанию не заводим, подпись — заметкой.
    r = ops(dela, "sasha", meet_sug("Кирилл Белов (экспо)"))[0]
    p = card(dela, r["suggestion"]["result"]["person_id"])
    assert (p["name"], p["org_id"], p["note"]) == ("Кирилл Белов", None, "на встрече подписан «Кирилл Белов (экспо)»")
    # «(2)» от Zoom — второй вход того же человека, а не подпись; латиница, которая читается русским именем, — сама.
    r = ops(dela, "sasha", meet_sug("Ivan Orlov (2)", name_source="zoom"))[0]
    p = card(dela, r["suggestion"]["result"]["person_id"])
    assert (r["suggestion"]["status"], p["name"], p["note"]) == ("accepted", "Ivan Orlov", None)
    assert "people_link" in store.FEATURES and "meeting_touch" in store.FEATURES


def test_meeting_person_unclear_is_one_question(dela):
    r = ops(dela, "sasha", meet_sug("Мария"))[0]
    s = r["suggestion"]
    p = s["payload"]
    assert s["status"] == "pending" and "auto" not in p, s
    assert p["ask"] == "Кто это на встрече «Альфа: фонды» 14.10 — «Мария»?"
    assert p["options"] == [{"label": "Завести «Мария»", "new": True},
                            {"label": "Не для CRM", "reject": True, "reason": "не для CRM"}]
    assert p["held"] == "имя из одного слова" and not p.get("pick")
    # Та же «Мария» на другой встрече — тот же ключ (по имени): не второй вопрос.
    again = ops(dela, "sasha", meet_sug("Мария", meeting_ref="103"))[0]["suggestion"]
    assert again["id"] == s["id"]
    # «Завести «Мария»» — карточка, откуда она — встреча.
    r = ops(dela, "sasha", {"op": "suggestion.decide", "id": s["id"], "option": 0})[0]
    assert r["ok"] and r["person"]["created"], r
    assert card(dela, r["person"]["person_id"])["source"] == "встреча: Альфа: фонды"
    # Что ещё неясно — тоже вопрос, со своей причиной; карточек не прибавилось.
    why = {name: ops(dela, "sasha", meet_sug(name))[0]["suggestion"]["payload"]["held"] for name in (
        "John Smith", "Отдел продаж", "Иван Петрович Сидоров Младший Пятый", "SPEAKER 03")}
    assert why == {"John Smith": "латиница — как назвать, реши сам", "Отдел продаж": "не похоже на имя человека",
                   "Иван Петрович Сидоров Младший Пятый": "больше четырёх слов",
                   "SPEAKER 03": "не похоже на имя человека"}, why
    assert not people_named(dela, "John Smith") and not people_named(dela, "Отдел продаж")
    # Служба не просила завести само (нет auto) — тоже вопрос, а не молчаливое ожидание.
    s = ops(dela, "sasha", meet_sug("Глеб Тихонов", auto=False))[0]["suggestion"]
    assert s["status"] == "pending" and s["payload"]["ask"].endswith("— «Глеб Тихонов»?")


def test_meeting_person_ambiguous_asks_with_candidates(dela):
    with store.db.session(dela, "sasha", "t") as c:
        c.execute("INSERT INTO crm.orgs (name, kind, owner_id) VALUES ('Альфа', 'other', 'sasha'), ('Бета', 'other', 'sasha')")
        petrov, sidorov = (c.execute(
            "INSERT INTO crm.people (name, short, org_id, owner_id) VALUES (%s, 'Иван', (SELECT id FROM crm.orgs WHERE name = %s), "
            "'sasha') RETURNING id", (n, o)).fetchone()["id"] for n, o in (("Иван Петров", "Альфа"), ("Иван Сидоров", "Бета")))
    s = ops(dela, "sasha", meet_sug("Иван", key="meeting-speaker:101:SPEAKER_04"))[0]["suggestion"]
    p = s["payload"]
    assert s["status"] == "pending" and p["pick"] is True, s
    assert p["ask"] == "«Иван» на встрече «Альфа: фонды» 14.10 — это Иван Петров (Альфа) или Иван Сидоров (Бета)?"
    assert [o["label"] for o in p["options"]] == ["Это Иван Петров", "Это Иван Сидоров", "Новый человек", "Не для CRM"]
    assert [o.get("person_id") for o in p["options"][:2]] == [str(petrov), str(sidorov)]
    # «Да» без выбора — отказ словами: третьего «Ивана» не заводим и не привязываем наугад.
    r = ops(dela, "sasha", {"op": "suggestion.decide", "id": s["id"], "decision": "accept"})[0]
    assert not r["ok"] and "Иван Петров (Альфа) или Иван Сидоров (Бета)" in r["error"] and "кнопкой" in r["error"], r
    # «Это Иван Сидоров» — он; «Иван» — его своё короткое имя, другим именем не дописывается.
    r = ops(dela, "sasha", {"op": "suggestion.decide", "id": s["id"], "option": 1})[0]
    assert r["ok"] and str(r["person"]["person_id"]) == str(sidorov) and not r["person"]["created"], r
    assert r["suggestion"]["result"]["person_id"] == str(sidorov)
    assert "Иван" not in (card(dela, sidorov)["aliases"] or [])
    assert len(people_named(dela, "Иван")) == 0


def test_meeting_person_removed_known_and_repeat(dela):
    with store.db.session(dela, "sasha", "t") as c:
        c.execute("INSERT INTO crm.people (name, owner_id, archived_at) VALUES ('Глеб Архивов', 'sasha', now())")
        olga = c.execute("INSERT INTO crm.people (name, owner_id) VALUES ('Ольга Смирнова', 'sasha') RETURNING id").fetchone()["id"]
    # Владелец убрал карточку в архив — со встреч она не возвращается: сразу «нет» с причиной.
    s = ops(dela, "sasha", meet_sug("Глеб Архивов"))[0]["suggestion"]
    assert (s["status"], s["reason"]) == ("rejected", "уже убран в архив: Глеб Архивов"), s
    assert s["decided_by"] is None  # решил сервер, не владелец: очков за разбор «Нового» нет
    assert [p["archived_at"] is not None for p in people_named(dela, "Глеб Архивов")] == [True]
    # «Кто это» уверенно знает — карточки не заводим, ответ — она.
    r = ops(dela, "sasha", meet_sug("Ольга Смирнова"))[0]
    assert (r["suggestion"]["status"], str(r["person"]["person_id"])) == ("accepted", str(olga)), r
    assert len(people_named(dela, "Ольга Смирнова")) == 1
    # «Не для CRM» — навсегда: повтор той же находки возвращает тот же отказ.
    q = ops(dela, "sasha", meet_sug("Мария"))[0]["suggestion"]
    ops(dela, "sasha", {"op": "suggestion.decide", "id": q["id"], "option": 1})
    again = ops(dela, "sasha", meet_sug("Мария", meeting_ref="103"))[0]["suggestion"]
    assert (again["id"], again["status"], again["reason"]) == (q["id"], "rejected", "не для CRM")
    # Погасший без ответа — не «нет навсегда» (этап 3, ответ по умолчанию «а»): та же встреча — прежний, новая
    # встреча — новый вопрос.
    q = ops(dela, "sasha", meet_sug("Тамара"))[0]["suggestion"]
    with store.db.session(dela, "system", "t") as c:
        c.execute("UPDATE tasks.suggestions SET status = 'expired', decided_at = now() WHERE id = %s", (q["id"],))
    assert ops(dela, "sasha", meet_sug("Тамара"))[0]["suggestion"]["id"] == q["id"]
    fresh = ops(dela, "sasha", meet_sug("Тамара", meeting_ref="104"))[0]["suggestion"]
    assert fresh["id"] != q["id"] and fresh["status"] == "pending"


def test_meeting_person_removed_only_by_full_own_name(dela):
    """«Уже убран» — только полное собственное имя архивной карточки и только без живых похожих (проверка 15.10.2026).
    Совпало одно слово, короткое или другое имя архивной — это не он: вопрос с подсказкой, а не «нет» навсегда
    (требование владельца №25/№60: названный попадает в CRM, неясно — один вопрос)."""
    with store.db.session(dela, "sasha", "t") as c:
        c.execute("INSERT INTO crm.people (name, short, aliases, owner_id, archived_at) VALUES "
                  "('Мария Старая', 'Мария', '{}', 'sasha', now()), ('Олег Прежний', NULL, '{Олег}', 'sasha', now()), "
                  "('Пётр Старый', 'Петя', '{Петя Старый}', 'sasha', now()), ('Глеб Архивов', NULL, '{}', 'sasha', now())")
        c.execute("INSERT INTO crm.orgs (name, kind, owner_id) VALUES ('Альфа', 'other', 'sasha'), ('Бета', 'other', 'sasha')")
        c.execute("INSERT INTO crm.people (name, org_id, owner_id) VALUES ('Пётр Сомов', NULL, 'sasha'), "
                  "('Глеб Архивов', (SELECT id FROM crm.orgs WHERE name = 'Альфа'), 'sasha'), "
                  "('Глеб Архивов', (SELECT id FROM crm.orgs WHERE name = 'Бета'), 'sasha')")
    got = {}
    for name in ("Мария", "Олег", "Петя", "Петя Старый"):
        s = ops(dela, "sasha", meet_sug(name))[0]["suggestion"]
        got[name] = (s["status"], s["payload"].get("held"))
    assert got["Мария"] == ("pending", "имя из одного слова; в архиве есть «Мария Старая»"), got
    assert got["Олег"] == ("pending", "имя из одного слова; в архиве есть «Олег Прежний»"), got
    # Короткое имя архивной при живом «Пётр Сомов» — вопрос (раньше — «уже убран»).
    assert got["Петя"][0] == "pending" and got["Петя"][1].endswith("в архиве есть «Пётр Старый»"), got
    # Другое имя архивной из двух слов — тоже не «уже убран»: вопрос, а не карточка сама.
    assert got["Петя Старый"] == ("pending", "в архиве есть «Пётр Старый»"), got
    assert not people_named(dela, "Петя Старый")
    # Полное своё имя архивной — «уже убран»; но если есть живые похожие (два «Глеба Архивова»), — вопрос.
    with store.db.session(dela, "sasha", "t") as c:
        c.execute("INSERT INTO crm.people (name, owner_id, archived_at) VALUES ('Анна Убранная', 'sasha', now())")
    s = ops(dela, "sasha", meet_sug("Анна Убранная"))[0]["suggestion"]
    assert (s["status"], s["reason"]) == ("rejected", "уже убран в архив: Анна Убранная"), s
    s = ops(dela, "sasha", meet_sug("Глеб Архивов"))[0]["suggestion"]
    assert s["status"] == "pending" and s["payload"]["pick"] is True, s
    assert s["payload"]["held"].endswith("в архиве есть «Глеб Архивов»"), s


def test_meeting_person_many_alike_asks_in_words_and_takes_person_from_claude(dela):
    """Похожих больше, чем кнопок «Это X» (две): вопрос словами со всеми, кнопки — «Новый человек» и «Не для CRM»;
    нужного третьего выбирает Claude — suggestion.decide с set.person_id (проверка 15.10.2026)."""
    with store.db.session(dela, "sasha", "t") as c:
        ids = [c.execute("INSERT INTO crm.people (name, short, owner_id) VALUES (%s, 'Иван', 'sasha') RETURNING id",
                         (n,)).fetchone()["id"] for n in ("Иван Петров", "Иван Сидоров", "Иван Третьяков")]
    s = ops(dela, "sasha", meet_sug("Иван", key="meeting-speaker:101:SPEAKER_07"))[0]["suggestion"]
    p = s["payload"]
    assert s["status"] == "pending" and not p.get("pick"), s
    assert p["ask"].startswith("«Иван» на встрече «Альфа: фонды» 14.10 — кто это: ") and "Иван Третьяков" in p["ask"], p
    assert [o["label"] for o in p["options"]] == ["Новый человек", "Не для CRM"]
    # Кривой или чужой id — отказ словами, предложение ждёт.
    bad = ops(dela, "sasha", {"op": "suggestion.decide", "id": s["id"], "decision": "accept", "set": {"person_id": "x"}})[0]
    assert not bad["ok"] and "person_id" in bad["error"], bad
    # «Это Иван Третьяков» — Claude: карточка та, нового человека нет, «Иван» — его своё короткое имя (не дописываем).
    r = ops(dela, "sasha", {"op": "suggestion.decide", "id": s["id"], "decision": "accept",
                            "set": {"person_id": str(ids[2])}})[0]
    assert r["ok"] and str(r["person"]["person_id"]) == str(ids[2]) and not r["person"]["created"], r
    assert r["suggestion"]["result"]["person_id"] == str(ids[2])
    assert len(people_named(dela, "Иван")) == 0
    # И у вопроса кнопками (pick) выбор человека словами сильнее pick: «Да» без выбора — по-прежнему отказ.
    with store.db.session(dela, "sasha", "t") as c:
        c.execute("UPDATE crm.people SET archived_at = now() WHERE id = %s", (ids[2],))
    q = ops(dela, "sasha", meet_sug("Иван", key="meeting-speaker:102:SPEAKER_01", meeting_ref="102"))[0]["suggestion"]
    assert q["payload"]["pick"] is True
    r = ops(dela, "sasha", {"op": "suggestion.decide", "id": q["id"], "decision": "accept",
                            "set": {"person_id": str(ids[0])}})[0]
    assert r["ok"] and str(r["person"]["person_id"]) == str(ids[0]), r
    # Архивную карточку так не выбрать.
    z = ops(dela, "sasha", meet_sug("Иван", key="meeting-speaker:103:SPEAKER_01", meeting_ref="103"))[0]["suggestion"]
    r = ops(dela, "sasha", {"op": "suggestion.decide", "id": z["id"], "decision": "accept",
                            "set": {"person_id": str(ids[2])}})[0]
    assert not r["ok"] and "живой карточки нет" in r["error"], r


def test_option_person_dict_binds_telegram(dela):
    """«Это он в Telegram?» («Люди сами», контракт §3.4): вариант ответа несёт person — что дописать в карточку."""
    with store.db.session(dela, "sasha", "t") as c:
        pid = c.execute("INSERT INTO crm.people (name, owner_id) VALUES ('Пётр Новиков', 'sasha') RETURNING id").fetchone()["id"]
        mpid = c.execute("INSERT INTO crm.people (name, owner_id) VALUES ('Мария', 'sasha') RETURNING id").fetchone()["id"]
    one = {"telegram_id": 900101, "telegram_username": "pnovikov", "aliases": ["Pyotr Novikov"]}
    q = ops(dela, "sasha", person_sug(f"tgfind:{pid}", {
        "title": "Пётр Новиков", "where": "Telegram: «Pyotr Novikov» (@pnovikov)", "person_id": str(pid), "person": one,
        "ask": "Пётр Новиков в Telegram — это «Pyotr Novikov» (@pnovikov)?",
        "options": [{"label": "Да", "person_id": str(pid), "person": one},
                    {"label": "Нет", "reject": True, "reason": "не он в Telegram"}]}))[0]["suggestion"]
    assert q["status"] == "pending" and q["payload"]["options"][0]["person"] == one
    r = ops(dela, "sasha", {"op": "suggestion.decide", "id": q["id"], "option": 0})[0]
    assert r["ok"] and not r["person"]["created"], r
    p = card(dela, pid)
    assert (p["telegram_id"], p["telegram_username"], p["aliases"]) == (900101, "pnovikov", ["Pyotr Novikov"])
    # Два контакта — pick: «Да» без выбора не проходит; выбранный вариант заменяет payload.person целиком.
    a, b = {"telegram_id": 900102, "telegram_username": "mkot"}, {"telegram_id": 900103}
    q = ops(dela, "sasha", person_sug(f"tgfind:{mpid}", {
        "title": "Мария", "person_id": str(mpid), "pick": True, "ask": "Мария в Telegram — это «Мария Котова» или «Мария Белова»?",
        "options": [{"label": "Это «Мария Котова» (@mkot)", "person_id": str(mpid), "person": a},
                    {"label": "Это «Мария Белова»", "person_id": str(mpid), "person": b},
                    {"label": "Кривой", "person": "не словарь"},
                    {"label": "Никто", "reject": True, "reason": "не он в Telegram"}]}))[0]["suggestion"]
    assert "person" not in q["payload"]["options"][2]  # не словарь — выброшен
    r = ops(dela, "sasha", {"op": "suggestion.decide", "id": q["id"], "decision": "accept"})[0]
    assert not r["ok"] and "кнопкой" in r["error"]
    assert ops(dela, "sasha", {"op": "suggestion.decide", "id": q["id"], "option": 0})[0]["ok"]
    assert (card(dela, mpid)["telegram_id"], card(dela, mpid)["telegram_username"]) == (900102, "mkot")
    # Повтор той же находки после ответа — прежнее предложение, без второго вопроса.
    again = ops(dela, "sasha", person_sug(f"tgfind:{mpid}", {"title": "Мария", "person_id": str(mpid)}))[0]["suggestion"]
    assert again["id"] == q["id"]


def test_meeting_batch_takes_dela_title(dela):
    """№43: пачка «Нового» встречи — названием Дел, если разборщик уже назвал встречу раньше, чем пришла сверка."""
    with store.db.session(dela, "sasha", "t") as c:
        c.execute("INSERT INTO crm.meetings (ref, started_at, owner_id, title) VALUES "
                  "('101', '2026-10-14T11:00:00+03:00', 'sasha', 'Альфа: фонды и сроки')")
    s = ops(dela, "sasha", meet_sug("Мария"))[0]["suggestion"]
    assert s["batch_title"] == "Альфа: фонды и сроки · 14.10"
    assert s["payload"]["ask"] == "Кто это на встрече «Альфа: фонды и сроки» 14.10 — «Мария»?"
