"""Логика Дел: операции, повторы, споры, предложения, синк и виды."""

from __future__ import annotations

import datetime as dt
import uuid

import pytest

from pravka_dela import store
from test_dela_schema import dela, project  # noqa: F401  (фикстура и помощник)


def ops(url, user, *items, via="app"):
    return store.apply_ops(url, user, list(items), via)["results"]


def test_create_set_done_with_short_number(dela):
    r = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Яблоков: ответить на КП", "ball": "waiting"}})[0]
    assert r["ok"], r
    t = r["task"]
    assert t["waiting_since"] == dt.date.today().isoformat() or t["waiting_since"]
    r = ops(dela, "sasha", {"op": "task.done", "id": f"#{t['num']}"})[0]
    assert r["ok"] and r["task"]["status"] == "done" and r["task"]["completed_at"]


def test_repeat_op_returns_same_answer(dela):
    op = {"op": "task.create", "op_id": str(uuid.uuid4()), "task": {"title": "Продлить Контур"}}
    first = ops(dela, "sasha", op)[0]
    again = ops(dela, "sasha", op)[0]
    assert first == again
    found = store.view(dela, "sasha", "search", q="Контур")["items"]
    assert len(found) == 1


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
    again = ops(dela, "sasha", {"op": "suggestion.decide", "id": a["id"], "decision": "accept"})[0]
    assert not again["ok"]
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
    today = dt.date.today()
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
    assert [t["title"] for t in m["paid_undated"]] == ["Платное без даты"]
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
    today = dt.date.today()
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
    assert out["features"] == ["remind"] and store.sync(dela, "sasha", out["seq"])["features"] == ["remind"]
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
