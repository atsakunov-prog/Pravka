"""«Новое» с наговорками, «понятно» у закрытого само, «Сейчас» не больше пяти и Claude в карточке
клиента (06.10.2026). Claude подменён: проверяется всё вокруг вызова. Имена — выдуманные.
"""

from __future__ import annotations

import datetime as dt

from pravka_dela import ask, parse, store
from test_dela_ask import item, mk
from test_dela_schema import dela, project  # noqa: F401


def ops(url, user, items, via="web"):
    return store.apply_ops(url, user, items, via)["results"]


def test_dictations_view_keeps_tasks_and_touches(dela):
    p = project(dela, "sasha", "Альфа")
    said = "Ивану напомнить про фонды до пятницы. Купить билеты. Факт: комитет пройден."
    r = ops(dela, "sasha", [
        {"op": "dictation.add", "dictation": {"id": "raznoska:1", "text": said, "source": "phone"}},
        {"op": "task.create", "task": {"title": "Иван: фонды", "project_id": str(p), "source": "voice", "source_ref": "raznoska:1"}},
        {"op": "task.create", "task": {"title": "Купить билеты", "source": "voice", "source_ref": "raznoska:1"}},
        {"op": "interaction.add", "data": {"at": dt.datetime.now(dt.timezone.utc).isoformat(), "kind": "note",
                                           "summary": "комитет пройден", "project_id": str(p), "source": "raznoska",
                                           "source_ref": "raznoska:1:0"}},
    ])
    assert all(x["ok"] for x in r), r
    fonds = r[1]["task"]
    # Повтор из очереди телефона — та же строка, без ошибки и без перезаписи.
    again = ops(dela, "sasha", [{"op": "dictation.add", "dictation": {"id": "raznoska:1", "text": "другое"}}])[0]
    assert again["ok"] and again["dictation"]["text"] == said
    # Старый телефон: наговорка без текста — видна по меткам её дел.
    ops(dela, "sasha", [{"op": "task.create", "task": {"title": "Позвонить в банк", "source": "voice", "source_ref": "raznoska:2"}}])
    # Потом про дело из наговорки сказал Telegram — дело то же, в наговорке оно остаётся.
    ops(dela, "system", [{"op": "suggestion.create", "suggestion": {
        "for_user": "sasha", "kind": "update", "task_id": fonds["id"], "source": "telegram", "batch_title": "Telegram · сегодня",
        "quote": "фонды в понедельник", "payload": {"due_date": dt.date.today().isoformat()}}}], via="test")
    ops(dela, "sasha", [{"op": "task.done", "id": fonds["id"]}])

    v = store.view(dela, "sasha", "dictations")["items"]
    assert [d["ref"] for d in v] == ["raznoska:2", "raznoska:1"]
    old, d = v
    assert old["text"] is None and old["source"] == "phone" and [t["title"] for t in old["tasks"]] == ["Позвонить в банк"]
    assert d["text"] == said and [t["title"] for t in d["tasks"]] == ["Иван: фонды", "Купить билеты"]
    assert d["tasks"][0]["status"] == "done"  # закрытое не выпадает из своей наговорки
    assert [n["summary"] for n in d["notes"]] == ["комитет пройден"]
    assert [(s["kind"], s["source"], s["status"]) for s in d["touches"]] == [("update", "telegram", "pending")]
    # Наговорки — только свои.
    assert store.view(dela, "natasha", "dictations")["items"] == []
    bad = ops(dela, "natasha", [{"op": "dictation.add", "dictation": {"id": "raznoska:1", "text": "моё"}}])[0]
    assert not bad["ok"]
    assert not ops(dela, "sasha", [{"op": "dictation.add", "dictation": {"id": "x:1", "text": "т", "source": "радио"}}])[0]["ok"]


def test_seen_hides_auto_closed(dela):
    t = mk(dela, "Продлить лицензию")
    today = dt.date.today().isoformat()
    s = ops(dela, "sasha", [{"op": "suggestion.create", "suggestion": {
        "for_user": "sasha", "kind": "close", "task_id": t["id"], "source": "telegram", "quote": "лицензия продлена",
        "payload": {"meeting_at": today, "auto": True}}}])[0]["suggestion"]
    assert s["status"] == "accepted" and s["seen_at"] is None
    assert not ops(dela, "natasha", [{"op": "suggestion.seen", "ids": [s["id"]]}])[0]["seen"]  # чужое — не отметить
    r = ops(dela, "sasha", [{"op": "suggestion.seen", "ids": [s["id"]]}])[0]
    assert r["ok"] and r["seen"] == [s["id"]]
    assert ops(dela, "sasha", [{"op": "suggestion.seen", "ids": [s["id"]]}])[0]["seen"] == []  # второй раз — уже
    with parse.db.session(dela, "sasha", "t") as c:
        row = c.execute("SELECT seen_at, status FROM tasks.suggestions WHERE id = %s", (s["id"],)).fetchone()
    assert row["seen_at"] is not None and row["status"] == "accepted"
    assert "dictations" in store.FEATURES


def test_parse_keeps_dictation(dela):
    def fake(system, user_text):
        return {"tasks": [{"title": "Позвонить Ивану", "notes": "", "project": "", "person": "", "ball": "mine", "due": "",
                           "estimate_min": 0, "money": "", "want": False, "labels": []}],
                "notes": [{"text": "комитет пройден", "project": "", "person": ""}], "_usage": None}

    out = parse.run(dela, "sasha", "позвонить Ивану. Факт: комитет пройден", {}, "", None, ask_fn=fake)
    ref = out["tasks"][0]["source_ref"]
    assert ref.startswith("parse:") and out["notes"][0]["source_ref"] == ref + ":0"
    v = store.view(dela, "sasha", "dictations")["items"]
    assert v[0]["ref"] == ref and v[0]["text"] == "позвонить Ивану. Факт: комитет пройден" and v[0]["source"] == "web"


def empty_card(**kw):
    """Ответ Claude по схеме: всё пусто, кроме сказанного."""
    return {"route": "edit", "reply": "", "changes": [], "create": [], "suggestions": [], "card": [], **kw}


def cd(do, **kw):
    """Правка карточки, как её вернёт Claude: одна запись массива card, лишние поля пусты."""
    return {"do": do, "name": "", "text": "", "kind": "", "date": "", "org": "", "role": "", "stage": "", "probability": 0,
            "remove": False, "new": False, "people": [], **kw}


def test_now_holds_five(dela):
    today = dt.date.today().isoformat()
    for i in range(4):
        mk(dela, f"Сейчас {i}", focus_on=today)
    a, b = mk(dela, "Пятое"), mk(dela, "Шестое")

    def fake(system, user_text):
        assert "В «Сейчас» сегодня: 4 из 5." in user_text
        return empty_card(changes=[item(a["num"], now="on"), item(b["num"], now="on")],
                          create=[item(0, title="Седьмое", now="on")], _usage=None)

    out = ask.run(dela, "sasha", "пятое и шестое — первым делом, и заведи седьмое на сейчас", {"task_ids": [a["id"], b["id"]]},
                  "", None, ask_fn=fake)
    with parse.db.session(dela, "sasha", "t") as c:
        now = {r["title"] for r in c.execute("SELECT title FROM tasks.tasks WHERE focus_on = crm.today()")}
        made = c.execute("SELECT focus_on FROM tasks.tasks WHERE title = 'Седьмое'").fetchone()
    assert len(now) == 5 and "Пятое" in now and "Шестое" not in now
    assert made and made["focus_on"] is None
    assert sum("уже 5" in e for e in out["errors"]) == 2


def _client(url):
    """Клиент «Альфа» с организацией, человек Ольга из неё, Иван — в людях сделки «Альфа: фонды»."""
    with parse.db.session(url, "system", "test") as c:
        org = c.execute("INSERT INTO crm.orgs (name, kind, owner_id) VALUES ('Альфа', 'client', 'sasha') RETURNING id").fetchone()["id"]
        bank = c.execute("INSERT INTO crm.orgs (name, kind, owner_id) VALUES ('Банк Гамма', 'bank', 'sasha') RETURNING id").fetchone()["id"]
        olga = c.execute("INSERT INTO crm.people (name, short, org_id, owner_id) VALUES ('Ольга Смирнова', 'Ольга', %s, 'sasha') "
                         "RETURNING id", (org,)).fetchone()["id"]
        ivan = c.execute("INSERT INTO crm.people (name, short, owner_id) VALUES ('Иван Петров', 'Иван', 'sasha') RETURNING id").fetchone()["id"]
    p = project(url, "sasha", "Альфа", org_id=org)
    with parse.db.session(url, "system", "test") as c:
        deal = c.execute("INSERT INTO crm.deals (project_id, name, stage, person_ids) VALUES (%s, 'Альфа: фонды', 'active', %s) RETURNING id",
                         (p, [ivan])).fetchone()["id"]
    return p, org, bank, olga, ivan, deal


def test_card_client_edits_timeline_people_deals_and_undo(dela):
    p, org, bank, olga, ivan, deal = _client(dela)
    t = mk(dela, "Альфа: КП", project_id=str(p))
    seen = {}

    def fake(system, user_text):
        seen["system"], seen["user"] = system, user_text
        return empty_card(card=[
            cd("note", text="Созвонились: ждут модель к пятнице", kind="call", people=["Ольга"]),
            cd("person", name="Ольга", role="CFO"),
            cd("person", name="Ивана", remove=True),
            cd("person", name="Пётр Сидоров", org="Банк Гамма", role="юрист", new=True),
            cd("deal", name="Альфа: фонды", stage="lost", text="ушли к другим")],
            reply="Записал звонок, Ольга — CFO, Ивана убрал, сделку закрыл.", _usage=None)

    scope = {"title": "Клиенты: Альфа", "task_ids": [t["id"]], "card": "client", "project_id": str(p)}
    out = ask.run(dela, "sasha", "созвонились с Ольгой, она CFO; Иван не отсюда; фонды проиграли", scope, "", None, ask_fn=fake)
    assert "КАРТОЧКА: клиент «Альфа»" in seen["user"] and "Альфа: фонды · в работе" in seen["user"]
    assert "— Ольга (Ольга Смирнова) · откуда: Альфа" in seen["user"]
    assert "КОМПАНИИ" in seen["system"] and "— Банк Гамма" in seen["system"]
    assert out["model"] == ask.CARD_MODEL and not out["errors"], out["errors"]
    assert len(out["crm"]) == 5 and any("в хронологию" in c["what"] for c in out["crm"])
    with parse.db.session(dela, "sasha", "t") as c:
        note = c.execute("SELECT * FROM crm.interactions WHERE deleted_at IS NULL").fetchone()
        o = c.execute("SELECT role, org_id FROM crm.people WHERE id = %s", (olga,)).fetchone()
        d = c.execute("SELECT stage, outcome, lost_reason, person_ids FROM crm.deals WHERE id = %s", (deal,)).fetchone()
        new = c.execute("SELECT org_id, role FROM crm.people WHERE name = 'Пётр Сидоров'").fetchone()
    assert (note["kind"], note["project_id"], note["person_ids"]) == ("call", p, [olga])
    assert (o["role"], o["org_id"]) == ("CFO", org)
    assert (d["stage"], d["outcome"], d["lost_reason"], d["person_ids"]) == ("archive", "lost", "ушли к другим", [])
    assert (new["org_id"], new["role"]) == (bank, "юрист")
    # «Вернуть всё» — ответ несёт отмену каждой правки карточки.
    back = [u for c_ in out["crm"] for u in c_["undo"]]
    assert all(x["ok"] for x in ops(dela, "sasha", back)), back
    with parse.db.session(dela, "sasha", "t") as c:
        assert c.execute("SELECT count(*) AS n FROM crm.interactions WHERE deleted_at IS NULL").fetchone()["n"] == 0
        assert c.execute("SELECT role FROM crm.people WHERE id = %s", (olga,)).fetchone()["role"] is None
        d = c.execute("SELECT stage, outcome, person_ids FROM crm.deals WHERE id = %s", (deal,)).fetchone()
        gone = c.execute("SELECT archived_at FROM crm.people WHERE name = 'Пётр Сидоров'").fetchone()
    assert (d["stage"], d["outcome"], d["person_ids"]) == ("active", None, [ivan])
    assert gone["archived_at"] is not None  # строки не удаляются — заведённый уходит в архив


def test_people_deals_and_notes_from_any_page(dela):
    """Вне карточки — по имени (08.10.2026, владелец на Воронке: «<сделку> надо убрать — мы не работаем больше»,
    а Claude отвечал «откройте карточку сделки»): человек, сделка, запись хронологии к сделке или клиенту."""
    p, org, bank, olga, ivan, deal = _client(dela)
    seen = {}

    def fake(system, user_text):
        seen["user"] = user_text
        return empty_card(card=[cd("person", name="Иван", org="Банк Гамма"),
                                cd("deal", name="Альфа: фонды", stage="lost", text="не работаем больше"),
                                cd("note", text="Ольга звонила: фонды не нужны", kind="call", org="Альфа"),
                                cd("note", text="что-то было", kind="созвон")], _usage=None)

    scope = {"title": "Воронка", "task_ids": [], "deal_ids": [str(deal)], "visible_deal_ids": [str(deal)]}
    out = ask.run(dela, "sasha", "фонды Альфы убери — не работаем; Иван теперь в банке Гамма", scope, "", None, ask_fn=fake)
    assert "КАРТОЧКА" not in seen["user"]
    assert "СДЕЛКИ НА ЭКРАНЕ (1); «в окне» — 1" in seen["user"]
    assert "в окне · Альфа: фонды · в работе" in seen["user"] and "клиент Альфа · нет следующего дела" in seen["user"]
    with parse.db.session(dela, "sasha", "t") as c:
        assert c.execute("SELECT org_id FROM crm.people WHERE id = %s", (ivan,)).fetchone()["org_id"] == bank
        d = c.execute("SELECT stage, outcome, lost_reason FROM crm.deals WHERE id = %s", (deal,)).fetchone()
        notes = c.execute("SELECT project_id, kind, summary FROM crm.interactions WHERE deleted_at IS NULL").fetchall()
    assert (d["stage"], d["outcome"], d["lost_reason"]) == ("archive", "lost", "не работаем больше")
    assert [(n["project_id"], n["kind"], n["summary"]) for n in notes] == [(p, "call", "Ольга звонила: фонды не нужны")]
    assert any("к какому клиенту" in e for e in out["errors"])  # без сделки и клиента запись не пишется
    assert out["model"] != ask.CARD_MODEL or ask.MODEL == ask.CARD_MODEL  # вне карточки — модель из «Настроек»
    # «Вернуть всё» и тут: сделка снова в работе.
    back = [u for c_ in out["crm"] for u in c_["undo"]]
    assert all(x["ok"] for x in ops(dela, "sasha", back)), back
    with parse.db.session(dela, "sasha", "t") as c:
        assert c.execute("SELECT stage, outcome FROM crm.deals WHERE id = %s", (deal,)).fetchone() == {"stage": "active", "outcome": None}


def test_card_deal_new_tasks_land_in_deal(dela):
    p, org, bank, olga, ivan, deal = _client(dela)

    def fake(system, user_text):
        assert "КАРТОЧКА: сделка «Альфа: фонды» клиента «Альфа»" in user_text
        return empty_card(create=[item(0, title="Прислать тизер")], card=[cd("deal", stage="closing", probability=80)], _usage=None)

    out = ask.run(dela, "sasha", "прислать тизер; переходим к закрытию, вероятность 80", {"task_ids": [], "card": "deal", "deal_id": str(deal)},
                  "", None, ask_fn=fake)
    assert not out["errors"], out["errors"]
    with parse.db.session(dela, "sasha", "t") as c:
        t = c.execute("SELECT project_id, deal_id FROM tasks.tasks WHERE title = 'Прислать тизер'").fetchone()
        d = c.execute("SELECT stage, probability FROM crm.deals WHERE id = %s", (deal,)).fetchone()
    assert (t["project_id"], t["deal_id"]) == (p, deal)
    assert (d["stage"], d["probability"]) == ("closing", 80)


def test_card_team_people_about_status(dela):
    """Одна строка на всё (09.10.2026, владелец: «написал: добавь <человека> — написал, что добавил, но ничего
    не добавилось»): в команду и к людям клиента — kind у person, два человека в один массив не затирают друг
    друга; пустая правка не пропадает молча; описание сделки и клиента, статус «поддержание отношений»."""
    p, org, bank, olga, ivan, deal = _client(dela)
    with parse.db.session(dela, "system", "test") as c:
        lena = c.execute("INSERT INTO crm.people (name, short, owner_id) VALUES ('Лена Тестова', 'Лена', 'sasha') RETURNING id").fetchone()["id"]
        oleg = c.execute("INSERT INTO crm.people (name, short, owner_id) VALUES ('Олег Тестов', 'Олег', 'sasha') RETURNING id").fetchone()["id"]
    seen = {}

    def fake(system, user_text):
        seen["user"] = user_text
        return empty_card(card=[
            cd("person", name="Лена", kind="team"),
            cd("person", name="Олег", kind="team"),
            cd("person", name="Олег", kind="lead"),
            cd("person", name="Ольга", kind="client"),
            cd("person", name="Иван", kind="client"),       # уже в людях клиента — ответ «уже там», не ошибка
            cd("person", name="Лена"),                       # пустая правка — в ошибки, а не молча
            cd("about", text="Дашборды для собственника: сравнение с депозитом")],
            reply="Лена и Олег в команде, Олег ведёт.", _usage=None)

    scope = {"title": "Сделка: Альфа: фонды", "task_ids": [], "card": "deal", "deal_id": str(deal)}
    out = ask.run(dela, "sasha", "добавь Лену и Олега, Олег ведёт; Ольга с их стороны; опиши проект", scope, "", None, ask_fn=fake)
    assert "Описание: (пусто)" in seen["user"] and "Команда ЗФ: (никого, кроме ведущего)" in seen["user"]
    with parse.db.session(dela, "sasha", "t") as c:
        d = c.execute("SELECT team_ids, lead_person_id, person_ids, description FROM crm.deals WHERE id = %s", (deal,)).fetchone()
    assert set(d["team_ids"]) == {lena, oleg} and d["lead_person_id"] == oleg  # триггер сделки раскладывает команду сам
    assert d["person_ids"] == [ivan, olga] and d["description"] == "Дашборды для собственника: сравнение с депозитом"
    assert any("Иван уже в людях клиента «Альфа: фонды»" == x["what"] for x in out["crm"])
    assert out["errors"] == ["Лена: не понял, что сделать — в команду, к людям клиента или поправить должность?"]
    back = [u for c_ in out["crm"] for u in c_["undo"]]
    assert all(x["ok"] for x in ops(dela, "sasha", back)), back
    with parse.db.session(dela, "sasha", "t") as c:
        d = c.execute("SELECT team_ids, lead_person_id, person_ids, description FROM crm.deals WHERE id = %s", (deal,)).fetchone()
    assert (d["team_ids"], d["lead_person_id"], d["person_ids"], d["description"]) == ([], None, [ivan], None)

    # Карточка клиента: человек — к самому клиенту (его организация), в команду — единственного живого проекта;
    # описание клиента — его note, статус — поддержание отношений.
    def fake2(system, user_text):
        seen["user"] = user_text
        return empty_card(card=[cd("person", name="Иван", kind="client"), cd("person", name="Лена", kind="team"),
                                cd("about", text="Давний клиент, семейный бизнес"), cd("client", stage="relations")], _usage=None)

    out = ask.run(dela, "sasha", "Иван их; Лена с нами; давний клиент; поддерживаем отношения",
                  {"task_ids": [], "card": "client", "project_id": str(p)}, "", None, ask_fn=fake2)
    assert not out["errors"], out["errors"]
    assert "Статус: по проектам" in seen["user"]
    with parse.db.session(dela, "sasha", "t") as c:
        assert c.execute("SELECT org_id FROM crm.people WHERE id = %s", (ivan,)).fetchone()["org_id"] == org
        assert c.execute("SELECT team_ids FROM crm.deals WHERE id = %s", (deal,)).fetchone()["team_ids"] == [lena]
        pr = c.execute("SELECT note, status FROM crm.projects WHERE id = %s", (p,)).fetchone()
    assert (pr["note"], pr["status"]) == ("Давний клиент, семейный бизнес", "relations")


def test_files_and_new_fields(dela):
    """Папка и документы ссылками у сделки и клиента, кому и как ушёл счёт (dela_0006)."""
    p, org, bank, olga, ivan, deal = _client(dela)
    files = [{"kind": "contract", "title": "Договор", "url": "https://disk.example/d.pdf"}, {"kind": "что-то", "url": "http://x.example/n"}]
    r = ops(dela, "sasha", [
        {"op": "deal.set", "id": str(deal), "set": {"folder_url": "https://disk.example/alfa", "files": files, "description": "Тест"}},
        {"op": "project.set", "id": str(p), "set": {"status": "relations", "files": []}},
        {"op": "payment.create", "data": {"deal_id": str(deal), "kind": "advance", "amount_kop": 100, "invoiced_on": dt.date.today().isoformat(),
                                          "sent_to": "бухгалтерия", "sent_via": "почта"}}])
    assert all(x["ok"] for x in r), r
    assert r[0]["row"]["files"][1]["kind"] == "other" and r[0]["row"]["folder_url"] == "https://disk.example/alfa"
    assert (r[2]["row"]["sent_to"], r[2]["row"]["sent_via"]) == ("бухгалтерия", "почта")
    bad = ops(dela, "sasha", [{"op": "deal.set", "id": str(deal), "set": {"files": [{"title": "без ссылки"}]}}])[0]
    assert not bad["ok"] and "ссылка" in bad["error"]
    v = store.view(dela, "sasha", "clients")["clients"]
    assert v[0]["status"] == "relations"


def test_schema_fits_grammar():
    """У схемы ответа предел: 06.10.2026 с карточкой тремя массивами и полным create API ответил
    «compiled grammar is too large» (62 поля — нет, 52 — да). Новое поле — сначала проба на API."""
    def count(node):
        if node.get("type") == "object":
            return sum(1 + count(v) for v in node["properties"].values())
        if node.get("type") == "array":
            return count(node["items"])
        return 0

    assert count(ask.SCHEMA) <= 52
