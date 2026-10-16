"""Логика Дел: операции, повторы, споры, предложения, синк и виды."""

from __future__ import annotations

import datetime as dt
import uuid

import pytest

from pravka_dela import store
from test_dela_schema import db_today, dela, project  # noqa: F401  (фикстура и помощники)


def ops(url, user, *items, via="app"):
    return store.apply_ops(url, user, list(items), via)["results"]


def test_create_set_done_with_short_number(dela):
    r = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Яблоков: ответить на КП", "ball": "waiting"}})[0]
    assert r["ok"], r
    t = r["task"]
    assert t["waiting_since"] == db_today(dela).isoformat()
    r = ops(dela, "sasha", {"op": "task.done", "id": f"#{t['num']}"})[0]
    assert r["ok"] and r["task"]["status"] == "done" and r["task"]["completed_at"]


def test_repeat_op_returns_same_answer(dela):
    op = {"op": "task.create", "op_id": str(uuid.uuid4()), "task": {"title": "Продлить Контур"}}
    first = ops(dela, "sasha", op)[0]
    again = ops(dela, "sasha", op)[0]
    assert first == again
    found = store.view(dela, "sasha", "search", q="Контур")["items"]
    assert len(found) == 1


def test_crooked_op_id_is_one_refusal_not_a_failed_batch(dela):
    """op_id не в формате uuid ронял транзакцию всей пачки (InvalidTextRepresentation до try — HTTP 500). Теперь — отказ
    этой операции словами, соседние проходят."""
    res = ops(dela, "sasha", {"op": "task.create", "op_id": "meeting:215:1", "task": {"title": "Кривой ключ"}},
              {"op": "task.create", "op_id": "urn:uuid:" + str(uuid.uuid4()), "task": {"title": "И этот кривой"}},
              {"op": "task.create", "op_id": str(uuid.uuid4()).upper(), "task": {"title": "Ровный ключ"}})
    assert [(r["ok"], r.get("error")) for r in res[:2]] == [(False, "op_id — не uuid")] * 2, res
    assert res[0]["op_id"] == "meeting:215:1"
    assert res[2]["ok"] and res[2]["task"]["title"] == "Ровный ключ", res
    assert [x["title"] for x in store.view(dela, "sasha", "search", q="ключ")["items"]] == ["Ровный ключ"]


def test_offline_create_with_own_id_is_idempotent(dela):
    tid = str(uuid.uuid4())
    a = ops(dela, "sasha", {"op": "task.create", "task": {"id": tid, "title": "Выкинуть велосипед"}})[0]
    b = ops(dela, "sasha", {"op": "task.create", "task": {"id": tid, "title": "Выкинуть велосипед"}})[0]
    assert a["task"]["id"] == b["task"]["id"] == tid


def test_bad_op_does_not_break_the_batch(dela):
    res = ops(
        dela, "sasha",
        {"op": "task.create", "task": {"title": "Хорошее"}},
        {"op": "task.create", "task": {"title": "Плохое", "ball": "куда-то"}},
        {"op": "нет-такой"},
        {"op": "task.create", "task": {"title": "Тоже хорошее"}},
    )
    assert [r["ok"] for r in res] == [True, False, False, True]
    assert "ball" in res[1]["error"] or "check" in res[1]["error"].lower()


def test_conflict_is_reported_last_write_wins(dela):
    t = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Тизер Додо", "due_date": "2026-10-05"}})[0]["task"]
    ops(dela, "sasha", {"op": "task.set", "id": t["id"], "set": {"due_date": "2026-10-07"}}, via="web")
    # Телефон без сети думал, что срок 05.10, и ставит 06.10.
    r = ops(dela, "sasha", {"op": "task.set", "id": t["id"], "set": {"due_date": "2026-10-06"}, "was": {"due_date": "2026-10-05"}})[0]
    assert r["conflicts"] == ["due_date"] and r["task"]["due_date"] == "2026-10-06"


def test_view_only_user_cannot_change(dela):
    p = project(dela, "sasha", "Чемодан.Про")
    ops(dela, "sasha", {"op": "access.set", "project_id": str(p), "user_id": "natasha", "role": "view"})
    t = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Статус по проекту", "project_id": str(p)}})[0]["task"]
    r = ops(dela, "natasha", {"op": "task.set", "id": t["id"], "set": {"title": "x"}})[0]
    assert not r["ok"] and "смотреть" in r["error"]


def test_suggestion_accept_edit_reject(dela, conn):
    mk = lambda title: ops(dela, "system", {"op": "suggestion.create", "suggestion": {  # noqa: E731
        "for_user": "sasha", "kind": "create", "source": "meeting", "source_ref": "meeting:42",
        "batch_ref": "meeting:42", "batch_title": "Додо, 03.10", "payload": {"title": title, "ball": "mine"}}})[0]["suggestion"]
    a, b, c = mk("Горецкий: прислать тизер"), mk("Наташа: КП"), mk("Идея: фонд")
    # Повтор той же находки дубля не плодит.
    assert mk("Горецкий: прислать тизер")["id"] == a["id"]
    new = store.view(dela, "sasha", "new")
    assert new["count"] == 3 and new["batches"][0]["title"] == "Додо, 03.10"

    r1 = ops(dela, "sasha", {"op": "suggestion.decide", "id": a["id"], "decision": "accept"})[0]
    r2 = ops(dela, "sasha", {"op": "suggestion.decide", "id": b["id"], "decision": "accept", "set": {"ball": "waiting"}})[0]
    r3 = ops(dela, "sasha", {"op": "suggestion.decide", "id": c["id"], "decision": "reject", "reason": "это идея, не дело"})[0]
    assert r1["suggestion"]["status"] == "accepted" and r1["task"]["source"] == "meeting"
    assert r1["task"]["source_ref"] == "meeting:42"
    assert r2["suggestion"]["status"] == "edited" and r2["task"]["ball"] == "waiting"
    assert r3["suggestion"]["status"] == "rejected" and r3["suggestion"]["reason"] == "это идея, не дело"
    # Повтор того же решения — не ошибка (отладка 16.10.2026): ok с already, второго дела нет; другое решение — отказ.
    again = ops(dela, "sasha", {"op": "suggestion.decide", "id": a["id"], "decision": "accept"})[0]
    assert again["ok"] and again["already"] and "task" not in again, again
    other = ops(dela, "sasha", {"op": "suggestion.decide", "id": a["id"], "decision": "reject"})[0]
    assert not other["ok"] and other["error"].startswith("Уже решено раньше: принято "), other
    # Чужое «Новое» не видно.
    assert store.view(dela, "natasha", "new")["count"] == 0


def test_assign_to_marianna_only_through_her_consent(dela):
    p = project(dela, "sasha", "Семья", sphere="home", kind="personal")
    ops(dela, "sasha", {"op": "access.set", "project_id": str(p), "user_id": "marianna", "role": "edit"})
    t = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Записать Рому к врачу", "project_id": str(p)}})[0]["task"]
    r = ops(dela, "sasha", {"op": "task.set", "id": t["id"], "set": {"owner_id": "marianna"}})[0]
    assert not r["ok"] and "согласия" in r["error"]
    s = ops(dela, "sasha", {"op": "suggestion.create", "suggestion": {
        "for_user": "marianna", "kind": "assign", "task_id": t["id"], "source": "user"}})[0]["suggestion"]
    took = ops(dela, "marianna", {"op": "suggestion.decide", "id": s["id"], "decision": "accept"})[0]
    assert took["ok"], took
    assert took["task"]["owner_id"] == "marianna"


def test_sync_incremental_and_full_on_access_change(dela):
    first = store.sync(dela, "natasha", 0)
    assert first["full"] and first["tasks"] == []
    p = project(dela, "sasha", "ПТИЦ")
    ops(dela, "sasha", {"op": "task.create", "task": {"title": "Наташа: КП ПТИЦ", "project_id": str(p)}})
    # Пока доступа нет — Наташе пусто.
    assert store.sync(dela, "natasha", first["seq"])["tasks"] == []
    ops(dela, "sasha", {"op": "access.set", "project_id": str(p), "user_id": "natasha", "role": "edit"})
    after = store.sync(dela, "natasha", first["seq"])
    assert after["full"] and [t["title"] for t in after["tasks"]] == ["Наташа: КП ПТИЦ"]
    quiet = store.sync(dela, "natasha", after["seq"])
    assert not quiet["full"]
    ops(dela, "sasha", {"op": "access.set", "project_id": str(p), "user_id": "natasha", "role": None})
    gone = store.sync(dela, "natasha", after["seq"])
    assert gone["full"] and gone["tasks"] == []


def test_morning_and_week_views(dela):
    today = db_today(dela)
    p = project(dela, "sasha", "Ростислав", money_default="paid")
    ops(
        dela, "sasha",
        {"op": "task.create", "task": {"title": "Сегодня", "due_date": today.isoformat()}},
        {"op": "task.create", "task": {"title": "Давно", "due_date": (today - dt.timedelta(days=30)).isoformat()}},
        {"op": "task.create", "task": {"title": "Платное без даты", "project_id": str(p)}},
        {"op": "task.create", "task": {"title": "Ждём", "ball": "waiting", "nudge_on": today.isoformat()}},
        {"op": "task.create", "task": {"title": "Сейчас", "focus_on": today.isoformat()}},
    )
    m = store.view(dela, "sasha", "morning")
    assert {t["title"] for t in m["today"]} == {"Сегодня", "Давно"}
    # Раздела «оплачено, без даты» нет с 05.10.2026: платное без срока — нигде в «Утре».
    assert "paid_undated" not in m
    assert all(t["title"] != "Платное без даты" for k in ("now", "today", "nudge", "from_others") for t in m[k])
    assert [t["title"] for t in m["nudge"]] == ["Ждём"]
    assert [t["title"] for t in m["now"]] == ["Сейчас"]
    w = store.view(dela, "sasha", "week")
    assert [t["title"] for t in w["stale"]] == ["Давно"]
    # Платный проект с делом не попадает в «без следующего шага»; пустой — попадает.
    project(dela, "sasha", "Пустой", money_default="potential")
    w = store.view(dela, "sasha", "week")
    assert [p["name"] for p in w["no_next_step"]] == ["Пустой"]


def test_expire_suggestions(dela, conn):
    s = ops(dela, "system", {"op": "suggestion.create", "suggestion": {
        "for_user": "sasha", "kind": "create", "source": "userbot", "payload": {"title": "Старое"},
        "expires_at": "2020-01-01T00:00:00+03:00"}})[0]["suggestion"]
    assert store.expire_suggestions(dela) == 1
    assert conn.execute("SELECT status FROM tasks.suggestions WHERE id = %s", (s["id"],)).fetchone()[0] == "expired"


def test_suggestion_names_resolve_on_accept(dela):
    p = project(dela, "sasha", "Альфа Групп", aliases=["Альфа"])
    with store.db.session(dela, "system", "t") as c:
        c.execute("INSERT INTO crm.people (name, short, owner_id) VALUES ('Дмитрий Орлов', 'Орлов', 'sasha')")
    s = ops(dela, "system", {"op": "suggestion.create", "suggestion": {
        "for_user": "sasha", "kind": "create", "source": "meeting", "batch_ref": "meeting:9",
        "payload": {"title": "Дмитрий Орлов: посмотреть презентацию", "ball": "waiting",
                    "person_name": "Дмитрий Орлов", "project_name": "Альфа"}}})[0]["suggestion"]
    t = ops(dela, "sasha", {"op": "suggestion.decide", "id": s["id"], "decision": "accept"})[0]["task"]
    assert (t["project_id"], t["person_short"], t["ball"]) == (str(p), "Орлов", "waiting")
    # Чужой проект по имени не подставится: Наташе он не виден.
    s2 = ops(dela, "system", {"op": "suggestion.create", "suggestion": {
        "for_user": "natasha", "kind": "create", "source": "bot", "payload": {"title": "x", "project_name": "Альфа"}}})[0]["suggestion"]
    t2 = ops(dela, "natasha", {"op": "suggestion.decide", "id": s2["id"], "decision": "accept"})[0]["task"]
    assert t2["project_id"] is None


def test_close_and_refine_from_meeting_leave_a_trace(dela):
    """Встреча не только заводит дела: закрывает и уточняет открытые — мяч к другому
    человеку по имени, новая формулировка, подробности. Основание остаётся комментарием."""
    with store.db.session(dela, "system", "t") as c:
        c.execute("INSERT INTO crm.people (name, short, owner_id) VALUES ('Ольга Смирнова', 'Ольга', 'sasha')")
    a = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Список фондов"}})[0]["task"]
    b = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Тизер"}})[0]["task"]
    mk = lambda kind, t, payload, quote: ops(dela, "system", {"op": "suggestion.create", "suggestion": {  # noqa: E731
        "for_user": "sasha", "kind": kind, "task_id": t["id"], "source": "meeting", "batch_ref": "meeting:7",
        "batch_title": "Бета: статус · 05.10", "quote": quote, "payload": payload}})[0]["suggestion"]
    up = mk("update", a, {"title": "Ольга: список фондов с обоснованием", "ball": "waiting", "person_name": "Ольга",
                          "note": "нужно 40 фондов, не 20; Катя добавит два своих"}, "Ольга пришлёт список к пятнице")
    cl = mk("close", b, {}, "тизер ушёл инвесторам")
    t = ops(dela, "sasha", {"op": "suggestion.decide", "id": up["id"], "decision": "accept"})[0]["task"]
    assert (t["title"], t["ball"], t["person_short"]) == ("Ольга: список фондов с обоснованием", "waiting", "Ольга")
    t2 = ops(dela, "sasha", {"op": "suggestion.decide", "id": cl["id"], "decision": "accept"})[0]["task"]
    assert t2["status"] == "done"
    def texts(t):
        with store.db.session(dela, "sasha", "t") as c:
            return [r["text"] for r in c.execute("SELECT text FROM tasks.comments WHERE task_id = %s ORDER BY created_at", (t["id"],))]
    assert texts(a) == ["Бета: статус · 05.10: нужно 40 фондов, не 20; Катя добавит два своих"]
    assert texts(b) == ["Бета: статус · 05.10: тизер ушёл инвесторам"]


def test_obvious_close_from_fresh_meeting_closes_itself(dela):
    """Очевидное закрытие по свежей встрече проходит само, с основанием в комментарии.
    Старая встреча, сомнение и дело не самого владельца — по-прежнему в «Новое»."""
    today = db_today(dela)
    mk_task = lambda title, **kw: ops(dela, "sasha", {"op": "task.create", "task": {"title": title, **kw}})[0]["task"]  # noqa: E731
    fresh, old, unsure = mk_task("Договор с Бетой"), mk_task("Тизер для фондов"), mk_task("Модель Альфы")

    def close(t, at, auto=True):
        return ops(dela, "sasha", {"op": "suggestion.create", "suggestion": {
            "for_user": "sasha", "kind": "close", "task_id": t["id"], "source": "meeting", "batch_ref": "meeting:3",
            "batch_title": "Бета: статус", "quote": "договор подписан", "payload": {"meeting_at": at, **({"auto": True} if auto else {})}}})[0]

    r = close(fresh, today.isoformat())
    assert r["ok"] and r["suggestion"]["status"] == "accepted" and r["suggestion"]["reason"] == store.AUTO_CLOSE_REASON
    assert r["task"]["status"] == "done"
    with store.db.session(dela, "sasha", "t") as c:
        assert [x["text"] for x in c.execute("SELECT text FROM tasks.comments WHERE task_id = %s", (fresh["id"],))] == ["Бета: статус: договор подписан"]
    assert close(old, (today - dt.timedelta(days=10)).isoformat())["suggestion"]["status"] == "pending"
    assert close(unsure, today.isoformat(), auto=False)["suggestion"]["status"] == "pending"
    with store.db.session(dela, "sasha", "t") as c:
        st = {r["title"]: r["status"] for r in c.execute("SELECT title, status FROM tasks.tasks")}
    assert (st["Тизер для фондов"], st["Модель Альфы"]) == ("open", "open")


def test_refine_from_fresh_meeting_applies_itself_and_goes_back(dela):
    """Уточнение по свежей встрече проходит само (владелец 08.10.2026: «спокойно уточняй»): срок,
    мяч к человеку по имени, подробности — комментарием. В ответе — как было и id комментария,
    ими «Вернуть» возвращает дело. Неузнанный человек, старая встреча, без auto — ждут в «Новом»."""
    today = db_today(dela)
    with store.db.session(dela, "system", "t") as c:
        c.execute("INSERT INTO crm.people (name, short, owner_id) VALUES ('Ольга Смирнова', 'Ольга', 'sasha')")
    mk_task = lambda title, **kw: ops(dela, "sasha", {"op": "task.create", "task": {"title": title, **kw}})[0]["task"]  # noqa: E731
    soon = (today + dt.timedelta(days=2)).isoformat()
    a, b, c_, d = mk_task("Список фондов", due_date=soon), mk_task("Модель Беты"), mk_task("Тизер"), mk_task("Отчёт")

    def refine(t, payload, at=today, auto=True):
        return ops(dela, "sasha", {"op": "suggestion.create", "suggestion": {
            "for_user": "sasha", "kind": "update", "task_id": t["id"], "source": "meeting", "batch_ref": "meeting:4",
            "batch_title": "Бета: статус", "quote": "Ольга пришлёт к пятнице",
            "payload": {"meeting_at": at.isoformat(), **payload, **({"auto": True} if auto else {})}}})[0]

    later = (today + dt.timedelta(days=5)).isoformat()
    r = refine(a, {"due_date": later, "ball": "waiting", "person_name": "Ольга", "note": "нужно 40 фондов, не 20"})
    assert r["ok"] and r["suggestion"]["status"] == "accepted" and r["suggestion"]["reason"] == store.AUTO_UPDATE_REASON
    t = r["task"]
    assert (t["due_date"], t["ball"], t["person_short"]) == (later, "waiting", "Ольга")
    back = r["suggestion"]["result"]
    assert back["was"] == {"due_date": soon, "ball": "mine", "person_id": None}
    with store.db.session(dela, "sasha", "t") as c:
        assert [x["text"] for x in c.execute("SELECT text FROM tasks.comments WHERE id = %s", (back["comment_id"],))] == [
            "Бета: статус: нужно 40 фондов, не 20"]

    # «Вернуть» веба: как было и без комментария
    undo = ops(dela, "sasha", {"op": "task.set", "id": t["id"], "set": back["was"]}, {"op": "comment.delete", "id": back["comment_id"]})
    assert all(x["ok"] for x in undo), undo
    assert (undo[0]["task"]["due_date"], undo[0]["task"]["ball"], undo[0]["task"]["person_id"]) == (soon, "mine", None)

    assert refine(b, {"ball": "waiting", "person_name": "Захар Неизвестный"})["suggestion"]["status"] == "pending"
    assert refine(c_, {"title": "Тизер на английском"}, at=today - dt.timedelta(days=10))["suggestion"]["status"] == "pending"
    assert refine(d, {"title": "Отчёт за сентябрь"}, auto=False)["suggestion"]["status"] == "pending"


def test_sure_new_task_from_fresh_meeting_is_created_itself(dela):
    """Новое дело по свежей встрече автоматика заводит сама, когда уверена (владелец 08.10.2026:
    «уверен — поставил, не уверен — подскажи»): проект и человек по имени, основание — в предложении.
    Вопрос вместо auto, неузнанный проект или человек, старая встреча — ждут в «Новом»; очков за
    заведённое само нет — разбирал не человек."""
    from pravka_dela import stats

    today = db_today(dela)
    project(dela, "sasha", "Бета Групп")
    with store.db.session(dela, "system", "t") as c:
        c.execute("INSERT INTO crm.people (name, short, owner_id) VALUES ('Ольга Смирнова', 'Ольга', 'sasha')")

    def create(title, payload, at=today, auto=True):
        return ops(dela, "sasha", {"op": "suggestion.create", "suggestion": {
            "for_user": "sasha", "kind": "create", "source": "meeting", "batch_ref": "meeting:5",
            "batch_title": "Бета: статус", "quote": "Ольга пришлёт список фондов",
            "payload": {"title": title, "meeting_at": at.isoformat(), **payload, **({"auto": True} if auto else {})}}})[0]

    r = create("Ольга: список фондов", {"project_name": "Бета Групп", "person_name": "Ольга", "ball": "waiting"})
    assert r["ok"] and r["suggestion"]["status"] == "accepted" and r["suggestion"]["reason"] == store.AUTO_CREATE_REASON
    t = r["task"]
    assert (t["title"], t["project_name"], t["person_short"], t["ball"], t["source"]) == (
        "Ольга: список фондов", "Бета Групп", "Ольга", "waiting", "meeting")
    assert str(r["suggestion"]["result_task_id"]) == str(t["id"])
    assert create("Купить билеты", {})["suggestion"]["status"] == "accepted"  # без проекта — можно: дело ясное
    assert create("Модель для Гаммы", {"ask": "Это Гамма или Гамма-2?"}, auto=False)["suggestion"]["status"] == "pending"
    assert create("Тизер", {"project_name": "Неведомый проект"})["suggestion"]["status"] == "pending"
    assert create("Созвон", {"person_name": "Захар Неизвестный", "ball": "agenda"})["suggestion"]["status"] == "pending"
    assert create("Отчёт", {}, at=today - dt.timedelta(days=10))["suggestion"]["status"] == "pending"
    with store.db.session(dela, "sasha", "t") as c:
        assert not [e for e in stats._events(c, "sasha") if e["kind"] == "triage"]


def test_phone_ops_as_pravka_sends_them(dela):
    """Операции ровно той формы, что собирает Правка (`core/Dela.kt`): id дела
    и op_id — телефона, пустое — null, у правки — was, заметка Разноски —
    interaction.add с person_ids строками uuid."""
    from test_dela_schema import as_

    p = project(dela, "sasha", "Бета Групп")
    with as_(dela, "sasha") as c:
        pe = c.execute("INSERT INTO crm.people (name, short, owner_id) VALUES ('Иван Петров', 'Иван', 'sasha') RETURNING id").fetchone()["id"]
    tid, cid, nid = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
    res = ops(
        dela, "sasha",
        {"op": "task.create", "op_id": str(uuid.uuid4()), "task": {
            "id": tid, "title": "Иван: прислать модель", "project_id": str(p), "ball": "waiting", "person_id": str(pe),
            "due_date": "2026-10-10", "estimate_min": 10, "labels": ["звонок"], "source": "voice", "source_ref": "raznoska:1"}},
        {"op": "task.set", "op_id": str(uuid.uuid4()), "id": tid,
         "set": {"due_date": "2026-10-12", "estimate_min": None, "labels": []},
         "was": {"due_date": "2026-10-10", "estimate_min": 10, "labels": ["звонок"]}},
        {"op": "comment.add", "op_id": str(uuid.uuid4()), "comment": {"id": cid, "task_id": tid, "text": "звонил"}},
        {"op": "interaction.add", "op_id": str(uuid.uuid4()), "data": {
            "id": nid, "at": "2026-10-03T21:10:00+03:00", "kind": "note", "summary": "комитет пройден",
            "source": "raznoska", "person_ids": [str(pe)], "project_id": str(p), "source_ref": "raznoska:1:0"}},
        {"op": "interaction.add", "op_id": str(uuid.uuid4()), "data": {
            "id": str(uuid.uuid4()), "at": "2026-10-03T21:10:00+03:00", "kind": "note", "summary": "без людей",
            "source": "raznoska", "person_ids": []}},
        {"op": "task.cancel", "op_id": str(uuid.uuid4()), "id": tid},
    )
    assert all(r["ok"] for r in res), res
    assert res[1]["conflicts"] == []
    out = store.sync(dela, "sasha", 0)
    t = next(x for x in out["tasks"] if x["id"] == tid)
    assert t["due_date"] == "2026-10-12" and t["estimate_min"] is None and t["labels"] == [] and t["status"] == "cancelled"
    assert any(c["id"] == cid for c in out["comments"])


# ── Напоминания в Telegram (контракт dela-remind.json) ──────────────────

def bot_ops(url, *items):
    """Операции от бота Ковчега: токен службы kovcheg, журнал — svc:kovcheg."""
    return store.apply_ops(url, "sasha", list(items), store.REMIND_BOT, f"svc:{store.REMIND_BOT}")["results"]


def test_remind_fields_flag_and_reminded_only_from_bot(dela):
    out = store.sync(dela, "sasha", 0)
    assert "remind" in out["features"] and "remind" in store.sync(dela, "sasha", out["seq"])["features"]
    tid = str(uuid.uuid4())
    t = ops(dela, "sasha", {"op": "task.create", "op_id": str(uuid.uuid4()), "task": {
        "id": tid, "title": "Позвонить Ивану", "due_date": "2026-10-06", "remind_at": "2026-10-06T11:00:00+03:00",
        "source": "voice"}})[0]
    assert t["ok"], t
    assert dt.datetime.fromisoformat(t["task"]["remind_at"]) == dt.datetime.fromisoformat("2026-10-06T11:00:00+03:00")
    assert t["task"]["reminded_at"] is None and t["task"]["remind_place"] is None
    # reminded_at клиентам не поле: телефон и веб его не ставят — ни правкой, ни операцией.
    bad = ops(dela, "sasha", {"op": "task.set", "id": tid, "set": {"reminded_at": "2026-10-06T11:00:00+03:00"}})[0]
    assert not bad["ok"] and "reminded_at" in bad["error"]
    phone = ops(dela, "sasha", {"op": "task.reminded", "id": tid, "at": "2026-10-06T11:00:04+03:00", "message_id": 1})[0]
    assert not phone["ok"] and "бот" in phone["error"]
    # Бот отправил: отметка стоит, message_id — в ответе.
    sent = bot_ops(dela, {"op": "task.reminded", "op_id": str(uuid.uuid4()), "id": tid, "at": "2026-10-06T11:00:04+03:00",
                          "message_id": 81234, "remind_at": "2026-10-06T11:00:00+03:00"})[0]
    assert sent["ok"] and sent["task"]["reminded_at"] and sent["message_id"] == 81234 and "stale" not in sent
    # Новое время (кнопка «Через час», карточка) — напоминание снова в силе: reminded_at сбросил триггер.
    again = ops(dela, "sasha", {"op": "task.set", "id": tid, "set": {"remind_at": "2026-10-06T12:00:00+03:00"}})[0]
    assert again["ok"] and again["task"]["reminded_at"] is None
    # Бот слал старое время, пока его переставили: отметку не ставим — новое уйдёт в свой срок.
    stale = bot_ops(dela, {"op": "task.reminded", "id": tid, "remind_at": "2026-10-06T11:00:00+03:00", "message_id": 2})[0]
    assert stale["ok"] and stale["stale"] and stale["task"]["reminded_at"] is None
    # Правка других полей отметку не трогает; место тоже взводит заново.
    bot_ops(dela, {"op": "task.reminded", "id": tid, "remind_at": "2026-10-06T12:00:00+03:00", "message_id": 3})
    kept = ops(dela, "sasha", {"op": "task.set", "id": tid, "set": {"title": "Позвонить Ивану про модель"}})[0]["task"]
    assert kept["reminded_at"]
    place = ops(dela, "sasha", {"op": "task.set", "id": tid, "set": {"remind_at": None, "remind_place": "дом"}})[0]["task"]
    assert place["reminded_at"] is None and place["remind_place"] == "дом"


def test_reminded_by_bot_for_task_its_user_cannot_see(dela):
    """Токен бота — на Сашу, а напоминает он всем, у кого есть Telegram: отметка — от имени system."""
    p = project(dela, "natasha", "Наташино личное", kind="personal", sphere="home")
    t = ops(dela, "natasha", {"op": "task.create", "task": {"title": "Сверка", "project_id": str(p),
                                                          "remind_at": "2026-10-06T10:00:00+03:00"}})[0]["task"]
    assert not ops(dela, "sasha", {"op": "task.set", "id": t["id"], "set": {"title": "x"}})[0]["ok"]  # Саша его не видит
    r = bot_ops(dela, {"op": "task.reminded", "id": t["id"], "message_id": 5})[0]
    assert r["ok"] and r["task"]["reminded_at"]
    # После операции бот снова Саша: чужое по-прежнему не видно.
    res = store.apply_ops(dela, "sasha", [{"op": "task.reminded", "id": t["id"]}, {"op": "task.set", "id": t["id"], "set": {"title": "x"}}],
                          store.REMIND_BOT, f"svc:{store.REMIND_BOT}")["results"]
    assert res[0]["ok"] and not res[1]["ok"]


def test_now_holds_five_on_the_server(dela, conn):
    """«Сейчас» — не больше пяти держит сервер: шестое отказывает словами из любого клиента
    (коннектор claude.ai, реплай в Telegram, телефон), а не встаёт молча (06.10.2026, LOGIC.md §11)."""
    today = conn.execute("SELECT crm.today()").fetchone()[0].isoformat()
    five = [ops(dela, "sasha", {"op": "task.create", "task": {"title": f"Сейчас {i}", "focus_on": today}}, via="web")[0]
            for i in range(5)]
    assert all(r["ok"] for r in five), five
    sixth = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Шестое", "focus_on": today}}, via="mcp")[0]
    assert not sixth["ok"] and "уже пять" in sixth["error"] and "Сейчас 0" in sixth["error"]
    # Без «Сейчас» дело заводится спокойно, а поставить его туда шестым — нельзя.
    later = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Потом"}}, via="mcp")[0]["task"]
    r = ops(dela, "sasha", {"op": "task.set", "id": later["id"], "set": {"focus_on": today}}, via="mcp")[0]
    assert not r["ok"] and "сначала убери одно" in r["error"]
    # Правка стоящего в «Сейчас» и его закрытие предел не трогают; место освободилось — встаёт.
    first = five[0]["task"]
    assert ops(dela, "sasha", {"op": "task.set", "id": first["id"], "set": {"notes": "уточнение"}})[0]["ok"]
    assert ops(dela, "sasha", {"op": "task.done", "id": first["id"]})[0]["ok"]
    assert ops(dela, "sasha", {"op": "task.set", "id": later["id"], "set": {"focus_on": today}})[0]["ok"]
    # Вернуть закрытое с сегодняшним focus_on — тоже вход в «Сейчас»: шестым не встаёт.
    r = ops(dela, "sasha", {"op": "task.reopen", "id": first["id"]})[0]
    assert not r["ok"] and "уже пять" in r["error"]
    # Вчерашнее «Сейчас» в счёт не идёт, у другого человека — своё «Сейчас».
    yesterday = (dt.date.fromisoformat(today) - dt.timedelta(days=1)).isoformat()
    assert ops(dela, "sasha", {"op": "task.create", "task": {"title": "Вчерашнее", "focus_on": yesterday}})[0]["ok"]
    assert ops(dela, "natasha", {"op": "task.create", "task": {"title": "Наташино", "focus_on": today}})[0]["ok"]


def test_now_mark_leaves_due_alone(dela):
    """14.10.2026, владелец: «Если нажимаю на „сейчас“, то дело пропадает и не ставится в сейчас… Надо оставить только
    сейчас». Солнце у строки (`toggleToday` веба и телефона) принимали за «Сейчас», а оно у дела со сроком сегодня
    ставило срок на завтра — в журнале трёх дел утром только `due_date` с телефона. Теперь отметка одна — молния, и ни
    взятие, ни снятие не трогают срок, время и напоминание: ни сервер (операция с одним `focus_on`, как шлют телефон и
    веб, с `was`), ни сам веб — его правило в `app.js`, поэтому оно проверяется текстом."""
    from pathlib import Path

    today = db_today(dela)
    d, yday, tmrw = today.isoformat(), (today - dt.timedelta(days=1)).isoformat(), (today + dt.timedelta(days=1)).isoformat()
    made = ops(dela, "sasha",
               {"op": "task.create", "task": {"title": "Без срока"}},
               {"op": "task.create", "task": {"title": "Срок сегодня", "due_date": d, "due_time": "15:00"}},
               {"op": "task.create", "task": {"title": "Просрочено", "due_date": yday}},
               {"op": "task.create", "task": {"title": "На завтра", "due_date": tmrw}},
               {"op": "task.create", "task": {"title": "Жду с напоминанием", "ball": "waiting", "due_date": d, "nudge_on": d}})
    assert all(r["ok"] for r in made), made
    keep = ("due_date", "due_time", "nudge_on", "ball", "owed")
    for r in made:
        t = r["task"]
        before = {k: t[k] for k in keep}
        on = ops(dela, "sasha", {"op": "task.set", "id": t["id"], "set": {"focus_on": d}, "was": {"focus_on": None}}, via="app")[0]
        assert on["ok"] and not on["conflicts"], on
        assert str(on["task"]["focus_on"]) == d and {k: on["task"][k] for k in keep} == before, t["title"]
        off = ops(dela, "sasha", {"op": "task.set", "id": t["id"], "set": {"focus_on": None}, "was": {"focus_on": d}}, via="web")[0]
        assert off["ok"] and not off["conflicts"], off
        assert off["task"]["focus_on"] is None and {k: off["task"][k] for k in keep} == before, t["title"]
    # «Сейчас» — сам по себе: дело со сроком сегодня осталось в «Сегодня», просроченное — во «Всех делах».
    assert ops(dela, "sasha", {"op": "task.set", "id": made[2]["task"]["id"], "set": {"focus_on": d}})[0]["ok"]
    assert [t["title"] for t in store.view(dela, "sasha", "now")["items"]] == ["Просрочено"]
    assert [t["title"] for t in store.view(dela, "sasha", "all")["overdue"]] == ["Просрочено"]

    # Веб: молния шлёт только focus_on, солнца и «На сегодня» нет ни у строки, ни в карточке, ни в панели выбранных.
    # read_text читает с переводом строк Python: «\r\n» файла здесь — «\n», конец функции — «}» с начала строки.
    js = (Path(store.__file__).parent / "static" / "app.js").read_text(encoding="utf-8")

    def fn(head: str) -> str:
        body = js[js.index(head):]
        return body[:body.index("\n}\n")]

    now = fn("function toggleNow(ids)")
    assert "focus_on" in now and "due_date" not in now and "nudge_on" not in now
    assert "function toggleToday" not in js and "toggleToday(" not in js
    assert "'На сегодня'" not in js and "'На завтра'" not in js
    row = fn("function taskRow(")
    assert "icon('bolt'" in row and "toggleNow([t.id])" in row and "icon('sun'" not in row


def test_three_groups_and_today(dela):
    """Три группы дел (владелец 10.10.2026): «Отбить» (ждут от меня) — первым делом, «Запустить», «Мониторить».
    ball «owed» — так группу называет автоматика; в базе это mine и флаг owed (телефон до своего задания
    видит такое дело своим «моё»). Мяч ушёл к человеку — флаг гаснет сам; телефонное «моё» его не роняет.
    «Сегодня» с 13.10.2026 — только срок ровно сегодня; «ждут от меня» без срока, «мониторить» с напоминанием
    и просроченное — во «Всех делах» (просроченное — сверху)."""
    today = db_today(dela)
    yday, tmrw = today - dt.timedelta(days=1), today + dt.timedelta(days=1)
    with store.db.session(dela, "system", "t") as c:
        pid = c.execute("INSERT INTO crm.people (name, short, owner_id) VALUES ('Евгений Гуркин', 'Гуркин', 'sasha') "
                        "RETURNING id").fetchone()["id"]
    r = ops(dela, "sasha",
            {"op": "task.create", "task": {"title": "Перезвонить Гуркину по фонду", "ball": "owed", "person_id": str(pid)}},
            {"op": "task.create", "task": {"title": "Попросить Наташу о тизере", "due_date": today.isoformat()}},
            {"op": "task.create", "task": {"title": "Гуркин: прислать структуру", "ball": "waiting", "person_id": str(pid),
                                           "nudge_on": yday.isoformat()}},
            {"op": "task.create", "task": {"title": "Обсудить при встрече", "ball": "agenda", "person_id": str(pid),
                                           "due_date": yday.isoformat()}},
            {"op": "task.create", "task": {"title": "Ответить банку к сроку", "ball": "owed", "due_date": tmrw.isoformat()}},
            {"op": "task.create", "task": {"title": "Купить магний"}})
    assert all(x["ok"] for x in r), r
    owed = r[0]["task"]
    assert (owed["ball"], owed["owed"], owed["grp"]) == ("mine", True, "owed")

    v = store.view(dela, "sasha", "today")
    assert [g["key"] for g in v["groups"]] == ["owed", "self", "check"]
    assert {g["key"]: [t["title"] for t in g["items"]] for g in v["groups"]} == {
        "owed": [], "self": ["Попросить Наташу о тизере"], "check": []}
    assert (v["overdue_count"], v["now_count"], v["now_max"]) == (1, 0, 5)
    every = store.view(dela, "sasha", "all")
    assert [t["title"] for t in every["overdue"]] == ["Обсудить при встрече"]
    assert {g["key"]: [t["title"] for t in g["items"]] for g in every["groups"]} == {
        "owed": ["Ответить банку к сроку", "Перезвонить Гуркину по фонду"],  # по сроку, без срока — ниже
        "self": ["Попросить Наташу о тизере", "Купить магний"],
        "check": ["Гуркин: прислать структуру"]}
    # «Дальше» (10.10–13.10) — старое имя «Всех дел»: коннектор мог его запомнить.
    assert store.view(dela, "sasha", "later") == every

    def set_(**kw):
        return ops(dela, "sasha", {"op": "task.set", "id": owed["id"], "set": kw})[0]["task"]

    assert set_(due_date=today.isoformat())["owed"] is True
    t = set_(ball="waiting")
    assert (t["ball"], t["owed"], t["grp"]) == ("waiting", False, "check")
    t = set_(ball="owed")
    assert (t["ball"], t["owed"], t["grp"]) == ("mine", True, "owed")
    assert set_(ball="mine", title="Перезвонить Гуркину")["owed"] is True
    assert set_(owed=False)["grp"] == "self"

    # Переписка: «Женя ждёт звонка» — дело само встаёт в «Отбить».
    s = ops(dela, "sasha", {"op": "suggestion.create", "suggestion": {
        "for_user": "sasha", "kind": "create", "source": "telegram", "batch_ref": "tg:1", "quote": "Женя: жду звонка",
        "payload": {"title": "Перезвонить Жене", "ball": "owed", "person_name": "Гуркин",
                    "meeting_at": today.isoformat(), "auto": True}}})[0]
    assert s["suggestion"]["status"] == "accepted" and s["task"]["owed"] is True and s["task"]["ball"] == "mine"


def test_now_today_and_overdue_apart(dela):
    """13.10.2026, владелец: «В делах давай разделим: Сейчас (то, что отметил) и Сегодня (с датой сегодня)»;
    просроченное само ни туда, ни туда не попадает — оно во «Всех делах», сверху. Чужое и закрытое — нигде."""
    from pravka_dela import mcp_tools

    today = db_today(dela)
    yday, tmrw = (today - dt.timedelta(days=1)).isoformat(), (today + dt.timedelta(days=1)).isoformat()
    d = today.isoformat()
    r = ops(dela, "sasha",
            {"op": "task.create", "task": {"title": "Взял сам", "focus_on": d}},
            {"op": "task.create", "task": {"title": "Взял и срок сегодня", "focus_on": d, "due_date": d}},
            {"op": "task.create", "task": {"title": "Срок сегодня", "due_date": d, "due_time": "15:00"}},
            {"op": "task.create", "task": {"title": "Сдать отчёт за сентябрь", "due_date": yday}},
            {"op": "task.create", "task": {"title": "Вчера брал в Сейчас", "focus_on": yday}},
            {"op": "task.create", "task": {"title": "Ждут ответа без срока", "ball": "owed"}},
            {"op": "task.create", "task": {"title": "Пора напомнить", "ball": "waiting", "nudge_on": d}},
            {"op": "task.create", "task": {"title": "На завтра", "due_date": tmrw}},
            {"op": "task.create", "task": {"title": "Сделал сегодняшнее", "due_date": d}})
    assert all(x["ok"] for x in r), r
    assert ops(dela, "sasha", {"op": "task.done", "id": r[-1]["task"]["id"]})[0]["ok"]
    assert ops(dela, "natasha", {"op": "task.create", "task": {"title": "Наташино на сегодня", "due_date": d, "focus_on": d}})[0]["ok"]

    now = store.view(dela, "sasha", "now")
    assert {t["title"] for t in now["items"]} == {"Взял сам", "Взял и срок сегодня"} and now["max"] == store.NOW_MAX
    v = store.view(dela, "sasha", "today")
    # Со временем — выше; «Сейчас» со сроком сегодня в «Сегодня» тоже есть (на главной веба — один раз, выше).
    assert [t["title"] for g in v["groups"] for t in g["items"]] == ["Срок сегодня", "Взял и срок сегодня"]
    assert (v["now_count"], v["overdue_count"], v["done_today"]) == (2, 1, 1)
    every = store.view(dela, "sasha", "all")
    assert [t["title"] for t in every["overdue"]] == ["Сдать отчёт за сентябрь"]
    assert {t["title"] for g in every["groups"] for t in g["items"]} == {
        "Взял сам", "Взял и срок сегодня", "Срок сегодня", "Вчера брал в Сейчас", "Ждут ответа без срока", "Пора напомнить",
        "На завтра"}
    # У Наташи — своё «Сейчас» и «Сегодня», Сашиного она там не видит.
    assert [t["title"] for t in store.view(dela, "natasha", "now")["items"]] == ["Наташино на сегодня"]
    assert [t["title"] for g in store.view(dela, "natasha", "today")["groups"] for t in g["items"]] == ["Наташино на сегодня"]

    # Коннектор claude.ai говорит то же словами: «Сейчас» отдельно, просроченное — числом и во «Всех делах».
    txt = mcp_tools.view(dela, "now")
    assert "Взял сам" in txt and "В «Сейчас» 2 из 5" in txt and "Срок сегодня" not in txt, txt
    txt = mcp_tools.view(dela, "today")
    assert "Срок сегодня" in txt and "Взял сам" not in txt and "Сдать отчёт" not in txt, txt
    assert "в «Сейчас»" in [x for x in txt.splitlines() if "Взял и срок сегодня" in x][0], txt
    assert "В «Сейчас» (отметил Саша): 2 из 5" in txt and "Просрочено: 1" in txt and "view=all" in txt, txt
    txt = mcp_tools.view(dela, "all")
    assert txt.index("Просрочено (срок прошёл) (1):") < txt.index("Сдать отчёт") < txt.index("Отбить"), txt


# ── Двусмысленное имя: вопрос кнопками, причина видна, «Да» без выбора не теряет человека (аудит 15.10.2026, №1) ──

def _person(url, name, **kw):
    r = ops(url, "sasha", {"op": "person.create", "data": {"name": name, **kw}})[0]
    assert r["ok"], r
    return str(r["row"]["id"])


def _sug(url, title, payload, at=None, auto=True, kind="create", **kw):
    at = at or db_today(url)
    return ops(url, "sasha", {"op": "suggestion.create", "suggestion": {
        "for_user": "sasha", "kind": kind, "source": "meeting", "batch_ref": "meeting:21", "batch_title": "Ромашка: статус",
        "quote": "Ваня пришлёт расчёт", **kw,
        "payload": {"title": title, "meeting_at": at.isoformat(), **payload, **({"auto": True} if auto else {})}}})[0]


def test_own_short_name_lets_suggestion_go_by_itself(dela):
    """(а) «Ваня» — короткое имя A и другое имя B: предложение «завести» встаёт само и с A."""
    a = _person(dela, "Иван Тестов", short="Ваня")
    _person(dela, "Иван Пробный", aliases=["Ваня"])
    r = _sug(dela, "Ваня: прислать расчёт", {"person_name": "Ваня", "ball": "waiting"})
    assert r["ok"] and r["suggestion"]["status"] == "accepted" and r["suggestion"]["reason"] == store.AUTO_CREATE_REASON, r
    assert str(r["task"]["person_id"]) == a


def test_ambiguous_name_asks_who_with_buttons(dela):
    """(б) «Ваня» — короткое имя у двоих: вопрос «Кто это — X или Y?» кнопками и «Без человека», причина — held;
    «Да» без выбора — отказ словами; ответ кнопкой — дело с тем, кого выбрали."""
    a = _person(dela, "Иван Тестов", short="Ваня", org_id=ops(dela, "sasha", {"op": "org.create", "data": {"name": "ООО Ромашка"}})[0]["row"]["id"])
    b = _person(dela, "Иван Сидоров", short="Ваня")
    r = _sug(dela, "Ваня: прислать расчёт", {"person_name": "Ваня", "ball": "waiting"})
    s = r["suggestion"]
    assert r["ok"] and s["status"] == "pending", r
    p = s["payload"]
    assert p["pick"] is True
    assert p["ask"].startswith("Кто это — «Ваня»: ") and "Иван Тестов" in p["ask"] and "Иван Сидоров" in p["ask"], p["ask"]
    assert p["held"].startswith("двое с именем «Ваня»: ") and "Иван Тестов (ООО Ромашка)" in p["held"], p["held"]
    labels = [o["label"] for o in p["options"]]
    assert sorted(labels[:2]) == ["Иван Сидоров", "Иван Тестов (ООО Ромашка)"] and labels[2] == "Без человека", labels
    assert p["options"][2]["person_id"] is None and p["options"][2]["person_name"] is None
    # «Да» без выбора (старый телефон, «Поставить») — отказ словами с кандидатами, предложение ждёт.
    no = ops(dela, "sasha", {"op": "suggestion.decide", "id": s["id"], "decision": "accept"})[0]
    assert not no["ok"] and no["error"].startswith("Кто это — «Ваня»: ") and "Ответь кнопкой" in no["error"], no
    # Ответ кнопкой — дело с A.
    i = next(k for k, o in enumerate(p["options"]) if o.get("person_id") == a)
    t = ops(dela, "sasha", {"op": "suggestion.decide", "id": s["id"], "option": i})[0]
    assert t["ok"] and str(t["task"]["person_id"]) == a, t
    # «Без человека» — дело без человека, и это ответ, а не потеря.
    s2 = _sug(dela, "Ваня: созвон", {"person_name": "Ваня"})["suggestion"]
    t2 = ops(dela, "sasha", {"op": "suggestion.decide", "id": s2["id"], "option": 2})[0]
    assert t2["ok"] and t2["task"]["person_id"] is None and "held" not in t2["suggestion"]["result"]
    # Человек выбран правкой («Поправить» в вебе, Claude) — тоже можно.
    s3 = _sug(dela, "Ваня: отчёт", {"person_name": "Ваня"})["suggestion"]
    t3 = ops(dela, "sasha", {"op": "suggestion.decide", "id": s3["id"], "decision": "accept", "set": {"person_id": b}})[0]
    assert t3["ok"] and str(t3["task"]["person_id"]) == b


def test_model_question_kept_and_other_answer_leaves_task_without_person(dela):
    """Модель спросила своё (про проект) — её вопрос и кнопки не трогаем, только held. Ответ на её вопрос при
    двусмысленном человеке — дело без человека, почему — result.held и комментарий к делу."""
    _person(dela, "Иван Тестов", short="Ваня")
    _person(dela, "Иван Сидоров", short="Ваня")
    project(dela, "sasha", "Альфа")
    opts = [{"label": "Альфа", "project_name": "Альфа"}, {"label": "Без проекта", "project_name": None}]
    s = _sug(dela, "Ваня: расчёт", {"person_name": "Ваня", "ask": "Это для Альфы?", "options": opts}, auto=False)["suggestion"]
    p = s["payload"]
    assert p["ask"] == "Это для Альфы?" and [o["label"] for o in p["options"]] == ["Альфа", "Без проекта"] and "pick" not in p
    assert p["held"].startswith("двое с именем «Ваня»")
    t = ops(dela, "sasha", {"op": "suggestion.decide", "id": s["id"], "option": 0})[0]
    assert t["ok"] and t["task"]["person_id"] is None and t["task"]["project_name"] == "Альфа", t
    res = t["suggestion"]["result"]
    assert res["held"].startswith("двое с именем «Ваня»") and res["comment_id"]
    with store.db.session(dela, "sasha", "t") as c:
        txt = c.execute("SELECT text FROM tasks.comments WHERE id = %s", (res["comment_id"],)).fetchone()["text"]
    assert txt.startswith("Человек не поставлен: двое с именем «Ваня»"), txt


def test_held_says_why_it_waits(dela, caplog):
    """(г) Не вышло само — причина в payload.held и строкой журнала, а не молча: «Сейчас» полно, старая встреча,
    не знаю проект."""
    today = db_today(dela)
    for k in range(store.NOW_MAX):
        assert ops(dela, "sasha", {"op": "task.create", "task": {"title": f"Срочное {k}", "focus_on": today.isoformat()}})[0]["ok"]
    with caplog.at_level("INFO", logger="dela.store"):
        r = _sug(dela, "Ещё одно срочное", {"focus_on": today.isoformat()})
    s = r["suggestion"]
    assert r["ok"] and s["status"] == "pending" and "task" not in r, r
    assert s["payload"]["held"].startswith("само не встало: в «Сейчас» уже пять дел"), s["payload"]
    assert any("Новое: «Ещё одно срочное» ждёт — само не встало" in m for m in caplog.messages), caplog.messages
    old = _sug(dela, "Старое", {}, at=today - dt.timedelta(days=10))["suggestion"]
    assert old["status"] == "pending" and old["payload"]["held"] == "встреча старше 3 дней"
    unknown = _sug(dela, "Тизер", {"project_name": "Неведомый проект"})["suggestion"]
    assert unknown["status"] == "pending" and unknown["payload"]["held"] == "не знаю проект «Неведомый проект»"
    # Автоматика спросила сама, имена узнались — причины нет: вопрос и есть причина.
    asked = _sug(dela, "Модель", {"ask": "Это Гамма?"}, auto=False)["suggestion"]
    assert asked["payload"]["held"] == ""  # пусто — сервер посмотрел имена, всё узнано (веб берёт его вердикт)


def test_weak_first_name_is_not_a_pick_question(dela):
    """«Маша» при пяти и больше Мариях с фамилией — совпало одно имя с полным (0,35): это «не знаю человека», а не
    вопрос со случайными кнопками; «Да» — дело без человека, как раньше (проверка 15.10.2026)."""
    for k, fam in enumerate(("Примерова", "Пробная", "Учебная", "Тестова", "Образцова", "Опытная")):
        _person(dela, f"Мария {fam}")
    s = _sug(dela, "Маша: прислать договор", {"person_name": "Маша"}, auto=False)["suggestion"]
    p = s["payload"]
    assert "pick" not in p and "options" not in p and "ask" not in p, p
    assert p["held"] == "не знаю человека «Маша»", p
    t = ops(dela, "sasha", {"op": "suggestion.decide", "id": s["id"], "decision": "accept"})[0]
    assert t["ok"] and t["task"]["person_id"] is None, t


def test_more_namesakes_than_buttons_ask_in_words(dela):
    """Тёзок своим именем больше, чем кнопок (WHO_PICK_MAX): вопрос словами со всеми в счёте, без кнопок (нужного среди
    трёх могло не быть, а «Поправить» у кнопок веб прячет); «Да» без человека — отказ, «Поправить» с человеком — да."""
    ids = [_person(dela, f"Иван {fam}", short="Ваня") for fam in ("Тестов", "Пробный", "Учебный", "Опытный", "Образцов")]
    s = _sug(dela, "Ваня: созвон", {"person_name": "Ваня"}, auto=False)["suggestion"]
    p = s["payload"]
    assert "pick" not in p and "options" not in p, p
    assert p["ask"].startswith("Кто это — «Ваня»: ") and p["ask"].endswith(" или ещё 2?"), p["ask"]
    assert p["held"].startswith("пятеро с именем «Ваня»: ") and p["held"].endswith(" и ещё 2"), p["held"]
    no = ops(dela, "sasha", {"op": "suggestion.decide", "id": s["id"], "decision": "accept"})[0]
    assert not no["ok"] and "«Поправить»" in no["error"], no
    yes = ops(dela, "sasha", {"op": "suggestion.decide", "id": s["id"], "decision": "accept", "set": {"person_id": ids[4]}})[0]
    assert yes["ok"] and str(yes["task"]["person_id"]) == ids[4], yes


def test_surname_namesakes_are_a_real_question(dela):
    """Одна фамилия у двоих (0,6 — настоящее совпадение): вопрос кнопками, а не «не знаю»."""
    a = _person(dela, "Иван Петров")
    b = _person(dela, "Анна Петрова")
    p = _sug(dela, "Петров: счёт", {"person_name": "Петров"}, auto=False)["suggestion"]["payload"]
    assert p["pick"] and {o.get("person_id") for o in p["options"]} >= {a, b}, p
    assert p["held"].startswith("на «Петров» похожи: "), p["held"]


# ── Сделка дела из встречи и переписки (аудит 15.10.2026, №2; dela_0022) ─────────────────────────────────────


def _deal(url, project_id, name, stage="active"):
    r = ops(url, "sasha", {"op": "deal.create", "data": {"project_id": str(project_id), "name": name, "stage": stage}})[0]
    assert r["ok"], r
    return r["row"]["id"]


def test_deal_name_finds_the_deal(dela):
    """payload.deal_name (сверка встреч и разноска дайджеста) — сделка по точному имени среди живых сделок клиента;
    чужое имя — без сделки, но дело встаёт само; клиента не назвали — единственная живая с таким именем, и её клиент."""
    p, q = project(dela, "sasha", "Ромашка"), project(dela, "sasha", "Бета Групп")
    model = _deal(dela, p, "Ромашка: модель")
    _deal(dela, p, "Ромашка: аудит")
    funds = _deal(dela, q, "Бета: фонды")
    r = _sug(dela, "Ромашка: прислать модель", {"project_name": "Ромашка", "deal_name": "ромашка:  МОДЕЛЬ"})
    assert r["suggestion"]["status"] == "accepted" and r["task"]["deal_id"] == model, r
    r = _sug(dela, "Ромашка: чужое", {"project_name": "Ромашка", "deal_name": "Бета: фонды"})
    assert r["suggestion"]["status"] == "accepted" and r["task"]["deal_id"] is None, r  # две живые — без сделки
    assert r["suggestion"]["payload"]["held"] == ""  # неузнанная сделка самопринятию не мешает
    r = _sug(dela, "Фонды: список", {"deal_name": "Бета: фонды"})
    assert (r["task"]["deal_id"], r["task"]["project_id"]) == (funds, str(q)), r


def test_automation_takes_single_live_deal_but_own_word_does_not(dela):
    """Дело автоматики (встреча, переписка) у клиента с одной живой сделкой — её шаг; дело, сказанное самим владельцем
    (task.create), и «Поправить» без сделки — без неё; две живые — без сделки."""
    p = project(dela, "sasha", "Ромашка")
    only = _deal(dela, p, "Ромашка: модель")
    _deal(dela, p, "Ромашка: прошлое", stage="lead")
    ops(dela, "sasha", {"op": "deal.set", "id": _deal(dela, p, "Ромашка: закрыто"), "set": {"outcome": "won"}})
    # Пока живых две — сделки нет.
    r = _sug(dela, "Ромашка: счёт", {"project_name": "Ромашка"})
    assert r["task"]["deal_id"] is None
    with store.db.session(dela, "system", "t") as c:
        c.execute("UPDATE crm.deals SET stage = 'archive', outcome = 'paused' WHERE name = 'Ромашка: прошлое'")
    r = _sug(dela, "Ромашка: прислать модель", {"project_name": "Ромашка"})
    assert r["suggestion"]["status"] == "accepted" and r["task"]["deal_id"] == only, r
    tg = ops(dela, "sasha", {"op": "suggestion.create", "suggestion": {
        "for_user": "sasha", "kind": "create", "source": "telegram", "batch_ref": "digest:x", "quote": "…",
        "payload": {"title": "Ромашка: ответить в чате", "project_name": "Ромашка"}}})[0]["suggestion"]
    t = ops(dela, "sasha", {"op": "suggestion.decide", "id": tg["id"], "decision": "accept"})[0]["task"]
    assert t["deal_id"] == only
    # «Поправить» без сделки — слово владельца.
    s = _sug(dela, "Ромашка: созвон", {"project_name": "Ромашка", "ask": "Это к модели?"}, auto=False)["suggestion"]
    t = ops(dela, "sasha", {"op": "suggestion.decide", "id": s["id"], "decision": "accept", "set": {"deal_id": None}})[0]["task"]
    assert t["deal_id"] is None
    # Сказанное самим владельцем — без сделки.
    t = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Ромашка: подумать", "project_id": str(p)}})[0]["task"]
    assert t["deal_id"] is None
    assert "deal_steps" in store.FEATURES


# ── Ждут без срока и жду больше недели — на виду (аудит 15.10.2026, №5) ──────────────────────────────────────

def test_today_counts_owed_without_due_and_stale_waiting(dela):
    """«Отбить» без срока и «Мониторить» без движения больше недели (напоминание не назначено или прошло) — числами в
    виде today (шапка «Сейчас»); правило «жду» то же, что waiting_stale недели; цели, чужое и закрытое не в счёт."""
    from pravka_dela import mcp_tools

    today = db_today(dela)
    d = lambda n: (today + dt.timedelta(days=n)).isoformat()  # noqa: E731
    mk = lambda title, user="sasha", **kw: ops(dela, user, {"op": "task.create", "task": {"title": title, **kw}})[0]["task"]  # noqa: E731
    mk("Ответить Ивану Тестову", ball="owed")
    mk("Ответить к пятнице", ball="owed", due_date=d(2))
    done = mk("Отвеченное", ball="owed")
    assert ops(dela, "sasha", {"op": "task.done", "id": done["id"]})[0]["ok"]
    mk("Цель: научиться отвечать", ball="owed", goal=True)
    mk("Наташино без срока", user="natasha", ball="owed")
    stale = {t: mk(t, ball="waiting", **kw)["id"] for t, kw in (
        ("Жду договор", {}), ("Жду акт", {"nudge_on": d(-1)}), ("Жду счёт", {"nudge_on": d(1)}), ("Жду ответ", {}),
        ("Цель: жду чуда", {"goal": True}))}
    with store.db.session(dela, "system", "t") as c:
        for t, since in (("Жду договор", -10), ("Жду акт", -9), ("Жду счёт", -10), ("Жду ответ", -3), ("Цель: жду чуда", -30)):
            c.execute("UPDATE tasks.tasks SET waiting_since = %s WHERE id = %s", (d(since), stale[t]))
    v = store.view(dela, "sasha", "today")
    assert (v["owed_nodue_count"], v["waiting_stale_count"]) == (1, 2), v
    assert {t["title"] for t in store.view(dela, "sasha", "week")["waiting_stale"]} == {"Жду договор", "Жду акт"}
    txt = mcp_tools.view(dela, "today")
    assert "Ждут от Саши без срока («Отбить»): 1" in txt and "Ждёт от людей больше недели" in txt and ": 2 " in txt, txt
    assert store.view(dela, "natasha", "today")["owed_nodue_count"] == 1


# ── Счётчик откатов автоматики за неделю (аудит 15.10.2026, №29) ─────────────────────────────────────────────

def test_week_counts_what_automation_did_and_what_was_undone(dela):
    """Завели само — отменил; закрыли само — открыл снова; уточнили само — «Вернуть» (основание убрано); завели человека
    сам — в архив; вопросы: да, с правкой, нет (с причиной), погасло. Решённое сервером без человека — не вопрос."""
    from pravka_dela import mcp_tools

    today = db_today(dela).isoformat()

    def sug(kind, source="meeting", auto=True, **kw):
        payload = {"meeting_at": today, **kw.pop("payload", {}), **({"auto": True} if auto else {})}
        r = ops(dela, "sasha", {"op": "suggestion.create", "suggestion": {
            "for_user": "sasha", "kind": kind, "source": source, "quote": "так сказали", **kw, "payload": payload}})[0]
        assert r["ok"], r
        return r

    made = sug("create", payload={"title": "Прислать расчёт"})
    assert made["suggestion"]["reason"] == store.AUTO_CREATE_REASON
    assert ops(dela, "sasha", {"op": "task.cancel", "id": made["task"]["id"]})[0]["ok"]
    sug("create", source="telegram", payload={"title": "Ответить в чате"})
    t1 = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Договор с ООО Ромашка"}})[0]["task"]
    closed = sug("close", task_id=t1["id"])
    assert closed["suggestion"]["reason"] == store.AUTO_CLOSE_REASON
    assert ops(dela, "sasha", {"op": "task.reopen", "id": t1["id"]})[0]["ok"]
    t2 = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Модель"}})[0]["task"]
    upd = sug("update", task_id=t2["id"], payload={"title": "Модель к пятнице"})["suggestion"]
    assert upd["reason"] == store.AUTO_UPDATE_REASON
    back = upd["result"]
    assert all(x["ok"] for x in ops(dela, "sasha", {"op": "task.set", "id": t2["id"], "set": back["was"]},
                                    {"op": "comment.delete", "id": back["comment_id"]}))
    man = sug("person", source="telegram", source_ref="tg:900010", payload={"person": {"name": "Пётр Пробный"}})["suggestion"]
    assert man["reason"] == store.AUTO_PERSON_REASON
    assert ops(dela, "sasha", {"op": "person.set", "id": man["result"]["person_id"], "set": {"archived_at": today + "T12:00:00+03:00"}})[0]["ok"]
    yes = sug("create", auto=False, payload={"title": "Созвон", "ask": "Ставить?"})["suggestion"]
    assert ops(dela, "sasha", {"op": "suggestion.decide", "id": yes["id"], "decision": "accept"})[0]["ok"]
    edit = sug("create", auto=False, payload={"title": "Тизер", "ask": "Ставить?"})["suggestion"]
    assert ops(dela, "sasha", {"op": "suggestion.decide", "id": edit["id"], "decision": "accept", "set": {"title": "Тизер на английском"}})[0]["ok"]
    no = sug("create", source="krug", auto=False, payload={"title": "Купить полку", "ask": "Это быт?"})["suggestion"]
    assert ops(dela, "sasha", {"op": "suggestion.decide", "id": no["id"], "decision": "reject", "reason": "не дело"})[0]["ok"]
    sug("create", auto=False, payload={"title": "Старое", "ask": "Ставить?"}, expires_at="2020-01-01T00:00:00+03:00")
    assert store.expire_suggestions(dela) == 1
    # Решил сервер сам («уже в CRM») — не вопрос и не откат.
    with store.db.session(dela, "system", "t") as c:
        c.execute("INSERT INTO crm.people (name, owner_id) VALUES ('Ольга Смирнова', 'sasha')")
    known = sug("person", auto=False, source_ref="meeting-person:ольга смирнова",
                payload={"person": {"name": "Ольга Смирнова"}, "speaker": {"meeting_ref": "77"}})["suggestion"]
    assert known["status"] == "accepted" and known["decided_by"] is None, known
    # «Человек заведён сам», а на деле дописано в живую карточку (её Telegram уже был): result.created = false — не новый
    # человек, в счёт «людей» не идёт (проверка 15.10.2026; веб и телефон «Вернуть» такому не показывают).
    with store.db.session(dela, "system", "t") as c:
        c.execute("INSERT INTO crm.people (name, telegram_id, owner_id) VALUES ('Вера Ключева', 900011, 'sasha')")
    added = sug("person", source="telegram", source_ref="tg:900011",
                payload={"person": {"name": "Вера К.", "telegram_id": 900011}})["suggestion"]
    assert (added["reason"], added["result"]["created"]) == (store.AUTO_PERSON_REASON, False), added

    a = store.view(dela, "sasha", "week")["automation"]
    b = a["all"]
    assert (b["created"], b["created_undone"], b["closed"], b["closed_undone"], b["updated"], b["updated_undone"],
            b["people"], b["people_undone"]) == (2, 1, 1, 1, 1, 1, 1, 1), b
    assert (b["asked"], b["yes"], b["edited"], b["no"], b["expired"]) == (4, 1, 1, 1, 1), b
    assert b["no_reasons"] == [{"reason": "не дело", "n": 1}]
    assert a["sources"]["telegram"]["created"] == 1 and a["sources"]["telegram"]["people_undone"] == 1
    assert a["sources"]["meeting"]["created_undone"] == 1 and a["sources"]["krug"]["no"] == 1
    txt = mcp_tools.view(dela, "week")
    assert "поставлено само 2 (отменил 1) · закрыто 1 (вернул 1) · уточнено 1 (1) · людей 1 (1)" in txt, txt
    assert "вопросов 4: да 1, с правкой 1, нет 1, погасло 1" in txt and "«не дело» — 1" in txt, txt


# ── Цели дерева автоматика не трогает (аудит 15.10.2026, №19) ────────────────────────────────────────────────

def test_automation_does_not_close_or_change_a_tree_goal(dela):
    """«Закрыть» или «уточнить» с номером цели дерева не встаёт само, даже с auto: ждёт владельца с причиной в held.
    Сам владелец цель закрывает как обычно."""
    today = db_today(dela).isoformat()
    goal = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Сын уверенно считает до 20", "goal": True}})[0]["task"]
    assert goal["goal"]

    def ask(kind, **payload):
        return ops(dela, "sasha", {"op": "suggestion.create", "suggestion": {
            "for_user": "sasha", "kind": kind, "task_id": goal["id"], "source": "telegram", "quote": "сделали",
            "payload": {"meeting_at": today, "auto": True, **payload}}})[0]

    r = ask("close")
    assert r["ok"] and r["suggestion"]["status"] == "pending" and "task" not in r, r
    assert r["suggestion"]["payload"]["held"] == "это цель дерева — закрывает владелец"
    u = ask("update", title="Сын уверенно считает до 100")["suggestion"]
    assert u["status"] == "pending" and u["payload"]["held"] == "это цель дерева — правит владелец", u
    with store.db.session(dela, "sasha", "t") as c:
        assert c.execute("SELECT status, title FROM tasks.tasks WHERE id = %s", (goal["id"],)).fetchone() == {
            "status": "open", "title": "Сын уверенно считает до 20"}
    assert ops(dela, "sasha", {"op": "task.done", "id": goal["id"]})[0]["ok"]  # владелец закрывает сам
