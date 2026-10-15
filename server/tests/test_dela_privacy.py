"""Закон приватности (аудит 15.10.2026, №8–11; правило 26 карты Дел): команда видит работу по проекту, а текст личных
каналов владельца (переписка Telegram, встречи), оценки людей и «Только тебе» — только он.

Люди и компании в тестах выдуманные. Команда — фикстура team (test_dela_crm): natasha — все клиенты и деньги, alena —
только клиенты своих сделок."""

from __future__ import annotations

import datetime as dt

import psycopg
import pytest

from pravka_dela import api, ask, store
from pravka_dela import db as dela_db
from test_dela_crm import deal, team  # noqa: F401  (фикстура)
from test_dela_schema import as_, db_today, dela, project  # noqa: F401  (фикстура и помощники)


def ops(url, user, *items, via="web"):
    return store.apply_ops(url, user, list(items), via)["results"]


def one(url, user, item, via="web"):
    r = ops(url, user, item, via=via)[0]
    assert r["ok"], r
    return r


def sug(url, kind, source, payload, task_id=None, **kw):
    s = {"for_user": "sasha", "kind": kind, "source": source, "payload": payload, **kw}
    if task_id:
        s["task_id"] = str(task_id)
    return one(url, "sasha", {"op": "suggestion.create", "suggestion": s})["suggestion"]


def comments_as(url, user, task_id) -> list[dict]:
    with as_(url, user) as c:
        return c.execute("SELECT * FROM tasks.comments WHERE task_id = %s AND deleted_at IS NULL ORDER BY created_at",
                         (task_id,)).fetchall()


def card_text(url, user, task_id) -> str:
    """Что видит человек в карточке дела (/api/task) и Claude карточки дела (ask._task_card) — одной строкой."""
    c = api.task_card(url, user, str(task_id))
    with dela_db.session(url, user, via="ask") as conn:
        t = store.task_by(conn, str(task_id))
        lines, _, _ = ask._task_card(conn, url, user, t, db_today(url))
    return repr(c) + "\n".join(lines)


# ── №8: основания и подробности из личных каналов — приватным комментарием ─────────────────────


def test_telegram_notes_become_private_comment(team):
    url = team["url"]
    p = project(url, "sasha", "ООО Ромашка")
    s = sug(url, "create", "telegram", {"title": "Иван Тестов: прислать модель", "project_name": "ООО Ромашка",
                                         "notes": "МЕТКА-TG пересказ переписки: Иван сомневается в цене"},
            batch_title="Telegram 15.10 11:00", quote="«пришлю модель к пятнице»")
    r = one(url, "sasha", {"op": "suggestion.decide", "id": s["id"], "decision": "accept"})
    t = r["task"]
    assert t["project_id"] == str(p) and not t["notes"]
    cid = r["suggestion"]["result"]["notes_comment_id"]
    mine = comments_as(url, "sasha", t["id"])
    assert [(str(c["id"]), c["private"]) for c in mine] == [(cid, True)]
    assert mine[0]["text"] == "Telegram 15.10 11:00: МЕТКА-TG пересказ переписки: Иван сомневается в цене"
    # Наташа видит клиента и дело — но не заметку машины: ни в базе, ни в синке, ни в карточке, ни у Claude.
    assert not comments_as(url, "natasha", t["id"])
    synced = store.sync(url, "natasha", 0)
    assert any(x["id"] == t["id"] for x in synced["tasks"])
    assert "МЕТКА-TG" not in repr(synced) and "МЕТКА-TG" not in card_text(url, "natasha", t["id"])
    assert "МЕТКА-TG" in card_text(url, "sasha", t["id"])
    assert "(только тебе)" in card_text(url, "sasha", t["id"])
    # У приватного комментария нет строк журнала.
    with dela_db.session(url, "system", "t") as c:
        assert not c.execute("SELECT 1 FROM crm.history WHERE entity = 'tasks.comments' AND entity_id = %s", (cid,)).fetchone()


def test_owners_own_notes_go_into_task(team):
    """«Поправить → Принять» с той же заметкой — машина (приватно); с другой — слово владельца, в заметку дела."""
    url = team["url"]
    project(url, "sasha", "ООО Ромашка")
    a = sug(url, "create", "telegram", {"title": "Дело А", "project_name": "ООО Ромашка", "notes": "заметка  машины"})
    b = sug(url, "create", "telegram", {"title": "Дело Б", "project_name": "ООО Ромашка", "notes": "заметка машины"})
    ta = one(url, "sasha", {"op": "suggestion.decide", "id": a["id"], "decision": "accept",
                            "set": {"notes": "заметка машины"}})["task"]
    tb = one(url, "sasha", {"op": "suggestion.decide", "id": b["id"], "decision": "accept",
                            "set": {"notes": "Моя заметка: позвонить после обеда"}})["task"]
    assert not ta["notes"] and [c["private"] for c in comments_as(url, "sasha", ta["id"])] == [True]
    assert tb["notes"] == "Моя заметка: позвонить после обеда" and not comments_as(url, "sasha", tb["id"])


def test_meeting_close_and_update_trace_private_and_once(team):
    """«Закрыть» и «уточнить» само из встречи: основание — приватным комментарием; «Вернуть» его убирает; то же основание
    второй раз — второго комментария нет. Заметка машины у «уточнить» — в тот же комментарий, не в дело."""
    url = team["url"]
    p = project(url, "sasha", "ООО Ромашка")
    today = db_today(url).isoformat()
    a = one(url, "sasha", {"op": "task.create", "task": {"title": "Тизер", "project_id": str(p)}})["task"]
    b = one(url, "sasha", {"op": "task.create", "task": {"title": "Модель", "project_id": str(p)}})["task"]
    base = {"batch_title": "Ромашка: статус · 15.10", "quote": "МЕТКА-ВСТРЕЧА тизер ушёл инвесторам"}
    cl = sug(url, "close", "meeting", {"meeting_at": today, "auto": True}, a["id"], **base)
    assert cl["status"] == "accepted"
    cid = cl["result"]["comment_id"]
    assert [(str(c["id"]), c["private"]) for c in comments_as(url, "sasha", a["id"])] == [(cid, True)]
    assert not comments_as(url, "natasha", a["id"])
    # «Вернуть»: открыть дело и убрать основание.
    one(url, "sasha", {"op": "task.reopen", "id": a["id"]})
    one(url, "sasha", {"op": "comment.delete", "id": cid})
    assert not comments_as(url, "sasha", a["id"])
    up = sug(url, "update", "meeting", {"meeting_at": today, "auto": True, "note": "МЕТКА-УТОЧН 40 фондов",
                                         "notes": "МЕТКА-ЗАМЕТКА машины"}, b["id"], **base)
    assert up["status"] == "accepted"
    got = comments_as(url, "sasha", b["id"])
    assert len(got) == 1 and got[0]["private"] and "МЕТКА-УТОЧН" in got[0]["text"] and "МЕТКА-ЗАМЕТКА" in got[0]["text"]
    with as_(url, "sasha") as c:
        assert not c.execute("SELECT notes FROM tasks.tasks WHERE id = %s", (b["id"],)).fetchone()["notes"]
    again = sug(url, "update", "meeting", {"meeting_at": today, "auto": True, "note": "МЕТКА-УТОЧН 40 фондов",
                                            "notes": "МЕТКА-ЗАМЕТКА машины"}, b["id"], **{**base, "source_ref": "x"})
    assert again["status"] == "accepted" and again["result"]["comment_id"] is None
    assert len(comments_as(url, "sasha", b["id"])) == 1


def test_private_comment_rules(team):
    url = team["url"]
    p = project(url, "sasha", "ООО Ромашка")
    t = one(url, "sasha", {"op": "task.create", "task": {"title": "Договор", "project_id": str(p)}})["task"]
    mine = one(url, "natasha", {"op": "comment.add", "comment": {"task_id": t["id"], "text": "Наташе: только мне",
                                                                 "private": True}})["comment"]
    shared = one(url, "sasha", {"op": "comment.add", "comment": {"task_id": t["id"], "text": "общий"}})["comment"]
    assert [c["text"] for c in comments_as(url, "natasha", t["id"])] == ["Наташе: только мне", "общий"]
    assert [c["text"] for c in comments_as(url, "sasha", t["id"])] == ["общий"]
    # Снять приватность — отказ словами; скрыть чужой общий — нельзя (политика правки — только автору).
    with pytest.raises(psycopg.errors.RaiseException, match="приватность комментария не снимается"):
        with as_(url, "natasha") as c:
            c.execute("UPDATE tasks.comments SET private = false WHERE id = %s", (mine["id"],))
    with as_(url, "natasha") as c:
        assert c.execute("UPDATE tasks.comments SET private = true WHERE id = %s", (shared["id"],)).rowcount == 0
    # Скрыть свой общий автор может — и дальше команда его не видит.
    with as_(url, "sasha") as c:
        assert c.execute("UPDATE tasks.comments SET private = true WHERE id = %s", (shared["id"],)).rowcount == 1
    assert [c["text"] for c in comments_as(url, "natasha", t["id"])] == ["Наташе: только мне"]
    with dela_db.session(url, "system", "t") as c:
        assert not c.execute("SELECT 1 FROM crm.history WHERE entity = 'tasks.comments' AND entity_id = %s",
                             (mine["id"],)).fetchone()


def test_task_journal_without_machine_notes_for_team(team):
    """Журнал дела из Telegram сотруднику — без значений заметки (её прежний текст живёт в crm.history), владельцу — с ней."""
    url = team["url"]
    p = project(url, "sasha", "ООО Ромашка")
    t = one(url, "sasha", {"op": "task.create", "task": {"title": "Ответить", "project_id": str(p), "source": "telegram",
                                                          "notes": "МЕТКА-ЖУРНАЛ пересказ"}})["task"]
    one(url, "sasha", {"op": "task.set", "id": t["id"], "set": {"notes": "МЕТКА-ЖУРНАЛ-2"}})
    one(url, "sasha", {"op": "task.set", "id": t["id"], "set": {"due_date": db_today(url).isoformat()}})
    team_hist = api.task_card(url, "natasha", t["id"])["history"]
    assert "МЕТКА-ЖУРНАЛ" not in repr(team_hist)
    assert [h["op"] for h in team_hist] == ["insert", "update"]  # правка одной заметкой — без строки
    assert "МЕТКА-ЖУРНАЛ-2" in repr(api.task_card(url, "sasha", t["id"])["history"])
