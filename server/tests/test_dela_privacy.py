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


# ── №9: встреча — команде факт, итог и договорённости — владельцу ─────────────────────────────

from test_dela_meetings import _live, _name, mt, push  # noqa: E402, F401  (фикстура и помощники встреч)

FULL = "Вектор: счета: Счёт перевыставить на новое юрлицо; модель — таблицы чувствительности."
AGREED = "Счёт перевыставить до пятницы"


def _client_timeline(url, user, project_id):
    return store.view(url, user, "client", project_id=str(project_id))["timeline"]


def test_meeting_record_fact_for_team_summary_for_owner(mt, conn):
    url = mt["url"]
    with dela_db.session(url, "system", "test") as c:
        c.execute("INSERT INTO crm.project_access (project_id, user_id, role, granted_by) VALUES (%s, 'natasha', 'view', "
                  "'sasha')", (mt["project"],))
    m = push(url, agreements=[AGREED])
    _name(url, m["id"], client="Вектор", topic="счета")
    [r] = _live(url)
    assert (r["summary"], r["next_step"]) == ("Вектор: счета", None)  # с завода — только факт
    mine = _client_timeline(url, "sasha", mt["project"])
    assert [(i["summary"], i["next_step"], i["team_sees"]) for i in mine] == [(FULL, AGREED, "fact")]
    assert mine[0]["source_ref"] == "https://meetings.example.org/m/215"
    hers = _client_timeline(url, "natasha", mt["project"])
    assert [(i["summary"], i["next_step"], i["source_ref"]) for i in hers] == [("Вектор: счета", None, None)]
    # Вход сверки (токен владельца) и коннектор (life) — с итогом, как было.
    assert [x["summary"] for x in store.view(url, "sasha", "timeline")["items"]] == [FULL]
    assert conn.execute("SELECT summary, next_step, team_sees FROM life.interactions WHERE source = 'meeting'").fetchall() == [
        (FULL, AGREED, "fact")]
    # «Факт и итог» — записи переписаны тут же, Наташа видит итог; обратно — снова факт.
    on = one(url, "sasha", {"op": "svod.set", "key": "privacy.team", "value": {"meetings": "summary"}, "author": "web"})
    assert on["touched"].get("обновлена") == 1, on["touched"]
    hers = _client_timeline(url, "natasha", mt["project"])
    assert [(i["summary"], i["next_step"]) for i in hers] == [(FULL, AGREED)]
    assert _client_timeline(url, "sasha", mt["project"])[0]["team_sees"] == "all"
    one(url, "sasha", {"op": "svod.set", "key": "privacy.team", "value": {"meetings": "fact"}, "author": "web"})
    assert [i["summary"] for i in _client_timeline(url, "natasha", mt["project"])] == ["Вектор: счета"]
    # Решает сам владелец: служба с его токеном, команда и кривое значение — отказ словами.
    svc = store.apply_ops(url, "sasha", [{"op": "svod.set", "key": "privacy.team", "value": {"meetings": "summary"}}],
                          "app", "svc:meetings")["results"][0]
    assert not svc["ok"] and "решает сам владелец" in svc["error"]
    team_try = ops(url, "natasha", {"op": "svod.set", "key": "privacy.team", "value": {"meetings": "summary"}})[0]
    assert not team_try["ok"]
    crooked = ops(url, "sasha", {"op": "svod.set", "key": "privacy.team", "value": {"meetings": "all"}})[0]
    assert not crooked["ok"] and "privacy.team" in crooked["error"]
    assert [i["summary"] for i in _client_timeline(url, "natasha", mt["project"])] == ["Вектор: счета"]


def test_owner_hand_on_meeting_record_is_his_text(mt):
    """Запись под замком touch (владелец поправил её сам) — его текст и владельцу: карточкой встречи не подменяется."""
    url = mt["url"]
    m = push(url)
    _name(url, m["id"], client="Вектор", topic="счета")
    [r] = _live(url)
    one(url, "sasha", {"op": "interaction.set", "id": str(r["id"]), "set": {"summary": "Вектор: моими словами"}})
    mine = _client_timeline(url, "sasha", mt["project"])
    assert [(i["summary"], i["team_sees"]) for i in mine] == [("Вектор: моими словами", "all")]


def test_private_record_rules(team):
    url = team["url"]
    p = project(url, "sasha", "ООО Ромашка")
    at = dt.datetime.now(dt.timezone.utc).isoformat()

    def add(user, summary, **kw):
        return one(url, user, {"op": "interaction.add", "data": {"at": at, "kind": "note", "summary": summary,
                                                                  "project_id": str(p), "source": "web", **kw}})["row"]

    a = add("sasha", "МЕТКА-ЗАП только мне", private=True)
    b = add("sasha", "общая запись")
    assert [i["summary"] for i in _client_timeline(url, "natasha", p)] == ["общая запись"]
    one(url, "sasha", {"op": "interaction.set", "id": str(b["id"]), "set": {"private": True}})
    assert _client_timeline(url, "natasha", p) == []
    assert {i["team_sees"] for i in _client_timeline(url, "sasha", p)} == {"none"}
    back = ops(url, "sasha", {"op": "interaction.set", "id": str(a["id"]), "set": {"private": False}})[0]
    assert not back["ok"] and "приватность" in back["error"]
    c = add("sasha", "ещё общая")
    hide = ops(url, "natasha", {"op": "interaction.set", "id": str(c["id"]), "set": {"private": True}})[0]
    assert not hide["ok"] and "только её хозяин" in hide["error"]
    # Сотрудник по-прежнему правит общую запись (не встречу и не дайджест).
    assert ops(url, "natasha", {"op": "interaction.set", "id": str(c["id"]), "set": {"summary": "поправила"}})[0]["ok"]


def test_hiding_meeting_record_keeps_owner_summary(mt, conn):
    """«Скрыть от команды» у записи встречи — не правка текста: замка touch нет, владелец по-прежнему видит итог и
    договорённости с карточки (хронология и коннектор), команда — ничего; правка текста — замок, как раньше."""
    url = mt["url"]
    with dela_db.session(url, "system", "test") as c:
        c.execute("INSERT INTO crm.project_access (project_id, user_id, role, granted_by) VALUES (%s, 'natasha', 'view', "
                  "'sasha')", (mt["project"],))
    m = push(url, agreements=[AGREED])
    _name(url, m["id"], client="Вектор", topic="счета")
    [r] = _live(url)
    one(url, "sasha", {"op": "interaction.set", "id": str(r["id"]), "set": {"private": True}})
    mine = _client_timeline(url, "sasha", mt["project"])
    assert [(i["summary"], i["next_step"], i["team_sees"]) for i in mine] == [(FULL, AGREED, "none")]
    assert _client_timeline(url, "natasha", mt["project"]) == []
    assert conn.execute("SELECT summary, next_step FROM life.interactions WHERE source = 'meeting'").fetchall() == [
        (FULL, AGREED)]
    with as_(url, "sasha") as c:
        assert "touch" not in (c.execute("SELECT locked FROM crm.meetings WHERE id = %s", (m["id"],)).fetchone()["locked"] or [])
    # Поправил текст — его слово: замок.
    one(url, "sasha", {"op": "interaction.set", "id": str(r["id"]), "set": {"summary": "Вектор: моими словами"}})
    with as_(url, "sasha") as c:
        assert "touch" in c.execute("SELECT locked FROM crm.meetings WHERE id = %s", (m["id"],)).fetchone()["locked"]


def test_team_cannot_touch_meeting_record_and_home_takes_none(mt):
    url = mt["url"]
    with dela_db.session(url, "system", "test") as c:
        c.execute("INSERT INTO crm.project_access (project_id, user_id, role, granted_by) VALUES (%s, 'natasha', 'edit', "
                  "'sasha')", (mt["project"],))
    m = push(url)
    _name(url, m["id"], client="Вектор", topic="счета")
    [r] = _live(url)
    gone = ops(url, "natasha", {"op": "interaction.delete", "id": str(r["id"])})[0]
    edit = ops(url, "natasha", {"op": "interaction.set", "id": str(r["id"]), "set": {"summary": "моё"}})[0]
    assert not gone["ok"] and not edit["ok"]
    assert [x["summary"] for x in _live(url)] == ["Вектор: счета"]
    home = project(url, "sasha", "Семья", sphere="home", kind="personal")
    bad = ops(url, "sasha", {"op": "interaction.add", "data": {"at": "2026-10-12T11:00:00+03:00", "kind": "meeting",
                                                              "summary": "Семья: ужин", "project_id": str(home),
                                                              "source": "meeting",
                                                              "source_ref": "https://meetings.example.org/m/990"}})[0]
    assert not bad["ok"] and "личный проект" in bad["error"]


# ── №10: карточка человека и компании — поля по тому, кто смотрит ─────────────────────────────


@pytest.fixture()
def cards(team):
    """Клиент «ООО Ромашка» с компанией и человеком клиента (со всеми пометками владельца), Алёна — пользователь Дел со
    своей карточкой (user_id) и пометкой владельца о ней."""
    url = team["url"]
    p = project(url, "sasha", "ООО Ромашка")
    with dela_db.session(url, "system", "t") as c:
        org = c.execute("INSERT INTO crm.orgs (name, owner_id, note) VALUES ('ООО Ромашка', 'sasha', 'МЕТКА-ОРГ') "
                        "RETURNING id").fetchone()["id"]
        c.execute("UPDATE crm.projects SET org_id = %s WHERE id = %s", (org, p))
        ivan = c.execute(
            "INSERT INTO crm.people (name, short, owner_id, org_id, role, phones, emails, telegram_username, traits, note, "
            "seeks, offers, source, birth_day, birth_month, cadence, hub) VALUES ('Иван Тестов', 'Иван', 'sasha', %s, "
            "'финдиректор', '{+7 000 000-00-02}', '{ivan@example.org}', 'ivan_test', 'МЕТКА-ЧЕРТЫ', 'МЕТКА-ЗАМЕТКА', "
            "'МЕТКА-ИЩЕТ', 'МЕТКА-ПРЕДЛАГАЕТ', 'МЕТКА-ОТКУДА', 5, 6, 'month', true) RETURNING id", (org,)).fetchone()["id"]
        c.execute("UPDATE crm.people SET user_id = 'alena', phones = '{+7 000 000-00-01}', traits = 'МЕТКА-ОБ-АЛЁНЕ', "
                  "birth_day = 1, birth_month = 2 WHERE id = %s", (team["alena"],))
    deal(url, "sasha", p, "Ромашка: модель", person_ids=[str(ivan)])
    return {**team, "project": p, "org": org, "ivan": ivan}


MARKS = ("МЕТКА-ЧЕРТЫ", "МЕТКА-ЗАМЕТКА", "МЕТКА-ИЩЕТ", "МЕТКА-ПРЕДЛАГАЕТ", "МЕТКА-ОТКУДА")


def test_person_card_fields_by_who_looks(cards):
    url, ivan = cards["url"], str(cards["ivan"])
    synced = store.sync(url, "natasha", 0)
    row = next(x for x in synced["people"] if x["id"] == ivan)
    assert (row["name"], row["role"], row["phones"], row["emails"], row["telegram_username"]) == (
        "Иван Тестов", "финдиректор", ["+7 000 000-00-02"], ["ivan@example.org"], "ivan_test")
    assert (row["birth_day"], row["cadence"], row["hub"], row["tier"]) == (None, None, False, "work")
    client_people = store.view(url, "natasha", "client", project_id=str(cards["project"]))["people"]
    seen = repr(synced) + repr(client_people) + repr(store.view(url, "natasha", "person", person_id=ivan))
    seen += repr(store.view(url, "natasha", "ties"))
    assert not [m for m in (*MARKS, "МЕТКА-ОРГ", "МЕТКА-ОБ-АЛЁНЕ") if m in seen]
    assert all(m in repr(store.view(url, "sasha", "person", person_id=ivan)) for m in MARKS)
    assert next(o for o in store.sync(url, "sasha", 0)["orgs"] if o["id"] == str(cards["org"]))["note"] == "МЕТКА-ОРГ"
    # Коллега (пользователь Дел, ничем не связан) — имя и должность, без контактов.
    al = next(x for x in synced["people"] if x["id"] == str(cards["alena"]))
    assert (al["name"], al["phones"], al["birth_day"], al["traits"], al["tier"]) == ("Алёна Юристова", [], None, None,
                                                                                     "colleague")
    # «Кто это» сотрудника коллегу по номеру не узнаёт; владелец — узнаёт.
    assert not store.view(url, "natasha", "who", phone="+7 000 000-00-01")["candidates"]
    assert store.view(url, "sasha", "who", phone="+7 000 000-00-01")["best"]["id"] == str(cards["alena"])


def test_own_card_contacts_yes_marks_no(cards):
    url, me = cards["url"], str(cards["alena"])
    mine = next(x for x in store.sync(url, "alena", 0)["people"] if x["id"] == me)
    assert (mine["phones"], mine["birth_day"], mine["traits"], mine["tier"]) == (["+7 000 000-00-01"], 1, None, "self")
    bad = ops(url, "alena", {"op": "person.set", "id": me, "set": {"traits": "сама о себе"}})[0]
    assert not bad["ok"] and "пометки о человеке" in bad["error"]
    got = one(url, "alena", {"op": "person.set", "id": me, "set": {"phones": ["+7 000 000-00-09"]}})["row"]
    assert got["phones"] == ["+7 000 000-00-09"] and got["traits"] is None and "МЕТКА" not in repr(got)
    added = one(url, "alena", {"op": "person.add", "id": me, "add": {"emails": ["alena@example.org"]}})["row"]
    assert "МЕТКА" not in repr(added)
    with as_(url, "sasha") as c:
        assert c.execute("SELECT traits FROM crm.people WHERE id = %s", (me,)).fetchone()["traits"] == "МЕТКА-ОБ-АЛЁНЕ"


def test_colleague_on_a_deal_is_still_a_colleague(cards):
    """Проверка пакета (dela_0029): пользователь Дел, который ведёт видимую сделку или назван в видимом деле, коллегам —
    только имя и должность: ни контактов, ни других имён в синке и карточке, «кто это» его по номеру и другому имени не
    узнаёт, person.add не выдаёт, есть ли у него такой номер. Владельцу — всё; сам себя видит и по одной учётке."""
    url, al, p = cards["url"], str(cards["alena"]), cards["project"]
    with dela_db.session(url, "system", "t") as c:
        c.execute("UPDATE crm.people SET aliases = '{Ольга Ласточкина}', emails = '{alena@example.org}', "
                  "telegram_username = 'alena_test' WHERE id = %s", (al,))
    deal(url, "sasha", p, "Ромашка: оценка", lead_person_id=al)
    one(url, "sasha", {"op": "task.create", "task": {"title": "Ромашка: проверить", "project_id": str(p), "person_id": al}})
    row = next(x for x in store.sync(url, "natasha", 0)["people"] if x["id"] == al)
    assert (row["tier"], row["phones"], row["aliases"], row["emails"], row["telegram_username"]) == (
        "colleague", [], [], [], None)
    assert "000-00-01" not in repr(store.view(url, "natasha", "person", person_id=al))
    for q in ({"phone": "+7 000 000-00-01"}, {"q": "Ольга Ласточкина"}, {"email": "alena@example.org"}):
        assert al not in [x["id"] for x in store.view(url, "natasha", "who", **q)["candidates"]], q
        assert store.view(url, "sasha", "who", **q)["best"]["id"] == al, q
    # Есть ли у коллеги такой номер, другое имя или Telegram — ответ один и тот же.
    probes = [ops(url, "natasha", {"op": "person.add", "id": al, "add": add})[0] for add in (
        {"phones": ["+7 000 000-00-01"]}, {"phones": ["+7 000 999-99-99"]},
        {"aliases": ["Ольга Ласточкина"]}, {"aliases": ["Пётр Иванович"]}, {"telegram_username": "x_test"})]
    assert {(x["ok"], x.get("error")) for x in probes} == {(False, "человек: нет прав менять")}, probes
    # Владелец видит всё; Алёна себя — сама (self); Наташа свою карточку, связанную лишь учёткой, — тоже как свою.
    assert next(x for x in store.sync(url, "sasha", 0)["people"] if x["id"] == al)["phones"] == ["+7 000 000-00-01"]
    assert next(x for x in store.sync(url, "alena", 0)["people"] if x["id"] == al)["tier"] == "self"
    nat = str(cards["natasha"])
    assert next(x for x in store.sync(url, "natasha", 0)["people"] if x["id"] == nat)["tier"] == "self"
    assert next(x for x in store.sync(url, "alena", 0)["people"] if x["id"] == nat)["tier"] == "colleague"


def test_people_by_themselves_note_is_only_where(cards):
    """«Люди сами» старой службы клали в пометку «<откуда>. <почему>» — в карточку идёт только «откуда»."""
    url = cards["url"]
    s = sug(url, "person", "telegram", {"person": {"name": "Пётр Новый", "note": "Telegram: «Ромашка». МЕТКА-ПОЧЕМУ"},
                                        "where": "Telegram: «Ромашка»"}, quote="МЕТКА-ПОЧЕМУ он писал про модель")
    r = one(url, "sasha", {"op": "suggestion.decide", "id": s["id"], "decision": "accept"})
    with as_(url, "sasha") as c:
        note = c.execute("SELECT note FROM crm.people WHERE id = %s", (r["person"]["person_id"],)).fetchone()["note"]
    assert note == "Telegram: «Ромашка»"


# ── «Только тебе» (crm.insights, dela_0027) — место этапа 6 ─────────────────────────────────


def test_only_you_insights_owner_only(cards, conn):
    url, ivan, p = cards["url"], str(cards["ivan"]), str(cards["project"])
    with as_(url, "sasha") as c:
        d = c.execute("SELECT id FROM crm.deals WHERE project_id = %s", (p,)).fetchone()["id"]
    a = one(url, "sasha", {"op": "insight.add", "insight": {"kind": "hedge", "text": "МЕТКА-ТТ сомневается в сроках",
                                                             "quote": "МЕТКА-ЦИТАТА «ну, посмотрим»", "person_ids": [ivan],
                                                             "deal_id": str(d), "source": "web"}})["insight"]
    assert (a["project_id"], a["author"], a["state"], a["people"]) == (p, "sasha", "new", ["Иван"])  # клиент — от сделки
    # Владелец видит везде: клиент, сделка, человек, вид insights, коннектор.
    assert [x["id"] for x in store.view(url, "sasha", "client", project_id=p)["insights"]] == [a["id"]]
    assert [x["id"] for x in store.view(url, "sasha", "deal", deal_id=str(d))["insights"]] == [a["id"]]
    assert [x["id"] for x in store.view(url, "sasha", "dossier", person_id=ivan)["insights"]] == [a["id"]]
    assert [x["id"] for x in store.view(url, "sasha", "insights", person_id=ivan)["items"]] == [a["id"]]
    assert conn.execute("SELECT text, people, client, deal FROM life.insights").fetchall() == [
        ("МЕТКА-ТТ сомневается в сроках", ["Иван"], "ООО Ромашка", "Ромашка: модель")]
    # Команде — пусто везде, записать — отказ базы, журнала нет, в синке нет.
    assert store.view(url, "natasha", "client", project_id=p)["insights"] == []
    assert store.view(url, "natasha", "deal", deal_id=str(d))["insights"] == []
    assert store.view(url, "natasha", "insights")["items"] == []
    with as_(url, "natasha") as c:
        assert not c.execute("SELECT 1 FROM crm.insights").fetchall()
    bad = ops(url, "natasha", {"op": "insight.add", "insight": {"text": "моё", "project_id": p}})[0]
    assert not bad["ok"] and "только владельцу Дел" in bad["error"]
    assert "МЕТКА-ТТ" not in repr(store.sync(url, "natasha", 0)) and "МЕТКА-ТТ" not in repr(store.sync(url, "sasha", 0))
    with dela_db.session(url, "system", "t") as c:
        assert not c.execute("SELECT 1 FROM crm.history WHERE entity = 'crm.insights'").fetchall()
    # «Видел», «Мимо», «Убрать».
    assert one(url, "sasha", {"op": "insight.set", "id": a["id"], "set": {"state": "seen"}})["insight"]["state"] == "seen"
    one(url, "sasha", {"op": "insight.delete", "id": a["id"]})
    assert store.view(url, "sasha", "client", project_id=p)["insights"] == []


def test_only_you_repeat_finding_and_home_meeting(mt):
    """Служба этапа 6: повтор той же находки (item_key) — та же строка; убранную не возвращает. Встреча стала личной —
    её «Только тебе» убраны, новую на личную — отказ. Слияние людей переносит person_ids."""
    url = mt["url"]
    m = push(url)
    _name(url, m["id"], client="Вектор", topic="счета")
    item = {"kind": "conflict", "text": "в КП 300, на встрече — 250", "meeting_ref": "215", "source": "meeting",
            "source_ref": "https://meetings.example.org/m/215", "item_key": "conflict-1", "person_ids": [str(mt["Олег"])]}
    a = one(url, "sasha", {"op": "insight.add", "insight": item})
    assert not a.get("existing") and a["insight"]["meeting_id"] == m["id"]
    again = one(url, "sasha", {"op": "insight.add", "insight": {**item, "text": "в КП 300, на встрече — 260"}})
    assert again["existing"] and again["insight"]["id"] == a["insight"]["id"] and again["insight"]["text"].endswith("260")
    assert [x["id"] for x in store.view(url, "sasha", "meeting", id=m["id"], lines="none")["insights"]] == [a["insight"]["id"]]
    one(url, "sasha", {"op": "insight.delete", "id": a["insight"]["id"]})
    gone = one(url, "sasha", {"op": "insight.add", "insight": item})
    assert gone["existing"] and gone["insight"]["deleted_at"] is not None
    # Слияние людей: наблюдение о дубле — о живой.
    b = one(url, "sasha", {"op": "insight.add", "insight": {"text": "любит цифры", "person_ids": [str(mt["Олег"])]}})["insight"]
    with as_(url, "sasha") as c:
        dup = c.execute("INSERT INTO crm.people (name, owner_id) VALUES ('Олег В.', 'sasha') RETURNING id").fetchone()["id"]
    one(url, "sasha", {"op": "insight.set", "id": b["id"], "set": {"person_ids": [str(dup)]}})
    one(url, "sasha", {"op": "person.merge", "id": str(dup), "into": str(mt["Олег"])})
    assert store.view(url, "sasha", "insights", person_id=str(mt["Олег"]))["items"][0]["id"] == b["id"]
    # Встреча стала личной — её строки убраны; новую на личную — отказ.
    c_ = one(url, "sasha", {"op": "insight.add", "insight": {**item, "item_key": "conflict-2"}})["insight"]
    one(url, "sasha", {"op": "meeting.set", "id": m["id"], "set": {"sphere": "home"}})
    assert store.view(url, "sasha", "insights", meeting_id=m["id"])["items"] == []
    with as_(url, "sasha") as c:
        assert c.execute("SELECT deleted_at FROM crm.insights WHERE id = %s", (c_["id"],)).fetchone()["deleted_at"]
    bad = ops(url, "sasha", {"op": "insight.add", "insight": {**item, "item_key": "conflict-3"}})[0]
    assert not bad["ok"] and "личная встреча" in bad["error"]


# ── Миграции поверх данных и разовый шаг privacy-fix ─────────────────────────────────────────


def test_migration_privacy_over_data(admin_dsn):
    """dela_0024–0029 поверх Дел, где уже есть люди, сделки, комментарии, записи встреч и команда: данные не меняются
    (rev прежние), кроме событий доступа не-владельцам."""
    import secrets as _secrets
    from urllib.parse import urlparse, urlunparse

    from psycopg import sql

    name = f"pa_migpriv_{_secrets.token_hex(4)}"
    with psycopg.connect(admin_dsn, autocommit=True) as a:
        a.execute(sql.SQL("CREATE DATABASE {} ENCODING 'UTF8' TEMPLATE template0").format(sql.Identifier(name)))
    u = urlparse(admin_dsn)
    url = urlunparse((u.scheme, u.netloc, "/" + name, "", "", ""))
    try:
        files = dict(dela_db._sql_files())
        before = {k: v for k, v in files.items() if k.startswith("dela_") and k < "dela_0024.sql"}
        with psycopg.connect(url, autocommit=True) as c:
            c.execute("CREATE SCHEMA core")
            c.execute("CREATE TABLE core.migrations (name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())")
            dela_db.apply_pending(c, before)
        with dela_db.session(url, "system", "test") as c:
            c.execute("INSERT INTO crm.users (id, name, role) VALUES ('sasha', 'Саша', 'owner')")
            alena = c.execute("INSERT INTO crm.people (name, owner_id, traits) VALUES ('Алёна Тестова', 'sasha', 'черты') "
                              "RETURNING id").fetchone()["id"]
            c.execute("INSERT INTO crm.users (id, name, role, person_id, clients) VALUES ('alena', 'Алёна', 'member', %s, 'team')",
                      (alena,))
            pid = c.execute("INSERT INTO crm.projects (name, sphere, kind, owner_id) VALUES ('ООО Ромашка', 'work', 'client', "
                            "'sasha') RETURNING id").fetchone()["id"]
            c.execute("INSERT INTO crm.deals (project_id, name, stage, lead_person_id) VALUES (%s, 'Ромашка: модель', 'archive', %s)",
                      (pid, alena))
            tid = c.execute("INSERT INTO tasks.tasks (title, project_id, owner_id, created_by, notes, source) VALUES "
                            "('Ромашка: договор', %s, 'sasha', 'sasha', 'заметка машины', 'telegram') RETURNING id",
                            (pid,)).fetchone()["id"]
            c.execute("INSERT INTO tasks.comments (task_id, author_id, text) VALUES (%s, 'sasha', 'Telegram: основание')", (tid,))
            c.execute("INSERT INTO crm.interactions (at, kind, summary, next_step, project_id, source, source_ref, owner_id) "
                      "VALUES (now(), 'meeting', 'Ромашка: модель: итог', 'договорились', %s, 'meeting', "
                      "'https://meetings.example.org/m/5', 'sasha')", (pid,))
            revs = {t: c.execute(f"SELECT array_agg(rev ORDER BY id) AS r FROM {t}").fetchone()["r"]
                    for t in ("crm.people", "crm.deals", "tasks.tasks", "tasks.comments", "crm.interactions", "crm.users")}
        with psycopg.connect(url, autocommit=True) as c:
            got = dela_db.apply_pending(c, files)
        assert got[:6] == ["dela_0024.sql", "dela_0025.sql", "dela_0026.sql", "dela_0027.sql", "dela_0028.sql",
                           "dela_0029.sql"], got
        with dela_db.session(url, "system", "test") as c:
            for t, r in revs.items():
                assert c.execute(f"SELECT array_agg(rev ORDER BY id) AS r FROM {t}").fetchone()["r"] == r, t
            assert c.execute("SELECT private FROM tasks.comments").fetchone()["private"] is False
            assert c.execute("SELECT summary FROM crm.interactions").fetchone()["summary"] == "Ромашка: модель: итог"
            events = c.execute("SELECT user_id FROM crm.access_revoked WHERE project_id IS NULL").fetchall()
            assert {e["user_id"] for e in events} == {"alena"}
        # Сделка в архиве — Алёна клиента больше не видит (on_client), а черты о ней — не её (сессия суперпользователя:
        # RLS не действует, проверяем правила функциями и ярусом вида).
        with dela_db.session(url, "alena", "test") as c:
            assert c.execute("SELECT crm.on_client('alena', %s) AS x", (pid,)).fetchone()["x"] is False
            assert c.execute("SELECT traits FROM crm.v_people WHERE id = %s", (alena,)).fetchone()["traits"] is None
    finally:
        with psycopg.connect(admin_dsn, autocommit=True) as a:
            a.execute(sql.SQL("DROP DATABASE IF EXISTS {} WITH (FORCE)").format(sql.Identifier(name)))


def test_privacy_fix_makes_old_bases_and_notes_private(team, capsys, tmp_path, monkeypatch):
    """Разово после установки: прежние основания, «Это оно» и заметки машины — приватными; правленая руками заметка не
    тронута; записи встреч — по переключателю; --dry ничего не пишет."""
    from pravka_dela import __main__ as cli
    from pravka_dela import privacy_fix

    url = team["url"]
    p = project(url, "sasha", "ООО Ромашка")
    # Как было до пакета: общий комментарий-основание, заметка машины в деле, «Это оно» общим комментарием.
    with dela_db.session(url, "sasha", "test") as c:
        t1 = c.execute("INSERT INTO tasks.tasks (title, project_id, owner_id, created_by, notes, source) VALUES "
                       "('Ответить', %s, 'sasha', 'sasha', 'пересказ переписки', 'telegram') RETURNING id", (p,)).fetchone()["id"]
        t2 = c.execute("INSERT INTO tasks.tasks (title, project_id, owner_id, created_by, notes, source) VALUES "
                       "('Прислать', %s, 'sasha', 'sasha', 'правил руками', 'telegram') RETURNING id", (p,)).fetchone()["id"]
        base = c.execute("INSERT INTO tasks.comments (task_id, author_id, text) VALUES (%s, 'sasha', 'Встреча: основание') "
                         "RETURNING id", (t1,)).fetchone()["id"]
        merged = c.execute("INSERT INTO tasks.comments (task_id, author_id, text) VALUES (%s, 'sasha', "
                           "'Telegram 15.10: слова переписки') RETURNING id", (t2,)).fetchone()["id"]
        for tid, notes, status, extra in ((t1, "пересказ переписки", "accepted", {}), (t2, "иное от машины", "accepted", {})):
            c.execute("INSERT INTO tasks.suggestions (for_user, kind, payload, source, status, decided_at, decided_by, "
                      "result_task_id, created_by) VALUES ('sasha', 'create', %s, 'telegram', %s, now(), 'sasha', %s, 'sasha')",
                      (store.Jsonb({"title": "x", "notes": notes}), status, tid))
        c.execute("INSERT INTO tasks.suggestions (for_user, kind, task_id, payload, source, status, decided_at, decided_by, "
                  "result, created_by, quote) VALUES ('sasha', 'close', %s, '{}', 'meeting', 'accepted', now(), 'sasha', %s, "
                  "'sasha', 'основание')", (t1, store.Jsonb({"comment_id": str(base)})))
        c.execute("INSERT INTO tasks.suggestions (for_user, kind, payload, source, status, decided_at, decided_by, reason, "
                  "batch_title, created_by) VALUES ('sasha', 'create', '{}', 'telegram', 'rejected', now(), 'sasha', "
                  "'уже есть: Прислать', 'Telegram 15.10', 'sasha')")
    seq = store.sync(url, "natasha", 0)["seq"]
    env = tmp_path / "dela.env"
    env.write_text(f"DELA_DB_URL={url}\n", encoding="utf-8")
    monkeypatch.delenv("DELA_DB_URL", raising=False)
    assert cli.main(["--env", str(env), "privacy-fix", "--dry"]) == 0
    said = capsys.readouterr().out
    assert said.startswith("вхолостую") and "основания — приватными: 1 (и «Это оно»: 1)" in said, said
    assert "в приватный комментарий: 1; правленые руками — не тронуты: 1" in said, said
    with dela_db.session(url, "natasha", "test") as c:
        assert len(c.execute("SELECT 1 FROM tasks.comments").fetchall()) == 2  # вхолостую — ничего не поменялось
    out = privacy_fix.run(url)
    assert (out["bases"], out["merged"], out["notes_moved"], out["notes_kept"]) == (1, 1, 1, 1)
    with dela_db.session(url, "natasha", "test") as c:
        assert c.execute("SELECT 1 FROM tasks.comments").fetchall() == []
        notes = {r["title"]: r["notes"] for r in c.execute("SELECT title, notes FROM tasks.tasks").fetchall()}
    assert notes == {"Ответить": None, "Прислать": "правил руками"}
    with dela_db.session(url, "sasha", "test") as c:
        texts = {r["text"] for r in c.execute("SELECT text FROM tasks.comments WHERE private").fetchall()}
    assert texts == {"Встреча: основание", "Telegram 15.10: слова переписки", "Telegram: пересказ переписки"}
    assert store.sync(url, "natasha", seq)["full"]  # событие доступа — полный синк


def test_privacy_fix_finds_this_is_it_by_author_time_and_head(team):
    """«Это оно» находится и когда дело потом переименовали, и без пачки у предложения (веб писал подпись источника,
    телефон — свою); чужой комментарий той же минуты и общий текст владельца не трогаются; причина «уже есть» без
    комментария рядом — списком."""
    from pravka_dela import privacy_fix

    url = team["url"]
    p = project(url, "sasha", "ООО Ромашка")
    with dela_db.session(url, "sasha", "test") as c:
        t = c.execute("INSERT INTO tasks.tasks (title, project_id, owner_id, created_by) VALUES ('Тизер', %s, 'sasha', "
                      "'sasha') RETURNING id", (p,)).fetchone()["id"]

        def merge(source, title, text, batch=None, ago="0"):
            c.execute("INSERT INTO tasks.suggestions (for_user, kind, payload, source, status, decided_at, decided_by, "
                      "reason, batch_title, created_by) VALUES ('sasha', 'create', %s, %s, 'rejected', "
                      "now() - %s::interval, 'sasha', 'уже есть: Тизер', %s, 'sasha')",
                      (store.Jsonb({"title": title}), source, ago, batch))
            if text:
                return c.execute("INSERT INTO tasks.comments (task_id, author_id, text) VALUES (%s, 'sasha', %s) RETURNING id",
                                 (t, text)).fetchone()["id"]

        web = merge("meeting", "Прислать тизер", "встреча: Прислать тизер — «пришлю к пятнице»")  # веб без пачки
        phone = merge("contacts", "Позвонить Ивану", "телефонная книжка: Позвонить Ивану")  # телефон без пачки
        merge("telegram", "Ответить", None, ago="2 hours")  # комментарий убран — посмотреть глазами
        own = c.execute("INSERT INTO tasks.comments (task_id, author_id, text) VALUES (%s, 'sasha', 'встреча: моё слово') "
                        "RETURNING id", (t,)).fetchone()["id"]  # начало то же, названия предложения нет
        c.execute("UPDATE tasks.tasks SET title = 'Тизер на английском' WHERE id = %s", (t,))  # переименовали потом
    out = privacy_fix.run(url)
    assert out["merged"] == 2 and [x["reason"] for x in out["merged_missed"]] == ["уже есть: Тизер"], out
    with dela_db.session(url, "system", "test") as c:
        got = {r["id"]: r["private"] for r in c.execute("SELECT id, private FROM tasks.comments").fetchall()}
    assert (got[web], got[phone], got[own]) == (True, True, False)
    assert "посмотри глазами (1)" in privacy_fix.words(out)


# ── Тест-метла (контракт §5): ни одна метка приватного места не доходит до команды ─────────────────

def _broom_world(team):
    """По уникальной метке в каждом приватном месте (§1 закона). Ответ — (метки, что где лежит)."""
    url = team["url"]
    today = db_today(url).isoformat()
    p = project(url, "sasha", "ООО Ромашка")
    with dela_db.session(url, "system", "t") as c:
        org = c.execute("INSERT INTO crm.orgs (name, owner_id, note) VALUES ('ООО Ромашка', 'sasha', 'МЕТКА-ОРГ-ПОМЕТКА') "
                        "RETURNING id").fetchone()["id"]
        c.execute("UPDATE crm.projects SET org_id = %s WHERE id = %s", (org, p))
        ivan = c.execute(
            "INSERT INTO crm.people (name, short, owner_id, org_id, traits, note, seeks, offers, source, birth_day, birth_month) "
            "VALUES ('Иван Тестов', 'Иван', 'sasha', %s, 'МЕТКА-ЧЕРТЫ', 'МЕТКА-ПОМЕТКА', 'МЕТКА-ИЩЕТ', 'МЕТКА-ПРЕДЛАГАЕТ', "
            "'МЕТКА-ОТКУДА', 29, 2) RETURNING id", (org,)).fetchone()["id"]
        fam = c.execute("INSERT INTO crm.people (name, short, owner_id, phones, user_id) VALUES ('Марианна Тестова', "
                        "'Марианна', 'sasha', '{+7 000 111-22-33}', 'marianna') RETURNING id").fetchone()["id"]
        c.execute("UPDATE crm.users SET person_id = %s WHERE id = 'marianna'", (fam,))
        # Пункт дайджеста Telegram — всегда приватная запись хронологии (dela_0018).
        c.execute("INSERT INTO crm.interactions (at, kind, summary, project_id, person_ids, source, source_ref, owner_id, "
                  "private) VALUES (now(), 'telegram', 'Telegram, окно: МЕТКА-ДАЙДЖЕСТ', %s, %s, 'digest', "
                  "'digest:2026-10-15T1100#work-1', 'sasha', true)", (p, [ivan]))
    d = deal(url, "sasha", p, "Ромашка: модель", lead_person_id=str(team["alena"]), person_ids=[str(ivan)],
             fee_kop=987654321, my_view="МЕТКА-КАКВИЖУ")
    # Заметка машины из Telegram — стала приватным комментарием.
    s = sug(url, "create", "telegram", {"title": "Иван Тестов: прислать модель", "project_name": "ООО Ромашка",
                                         "notes": "МЕТКА-ЗАМЕТКА-МАШИНЫ"}, quote="«пришлю»")
    t1 = one(url, "sasha", {"op": "suggestion.decide", "id": s["id"], "decision": "accept"})["task"]
    # Основание «закрыто само» из встречи.
    t2 = one(url, "sasha", {"op": "task.create", "task": {"title": "Тизер", "project_id": str(p)}})["task"]
    sug(url, "close", "meeting", {"meeting_at": today, "auto": True}, t2["id"], batch_title="Ромашка: статус",
        quote="МЕТКА-ОСНОВАНИЕ тизер ушёл")
    # «Только мне» — комментарий владельца.
    one(url, "sasha", {"op": "comment.add", "comment": {"task_id": t2["id"], "text": "МЕТКА-ТОЛЬКО-МНЕ", "private": True}})
    # Приватная запись хронологии.
    one(url, "sasha", {"op": "interaction.add", "data": {"at": today + "T10:00:00+03:00", "kind": "note",
                                                          "summary": "МЕТКА-ПРИВАТНАЯ-ЗАПИСЬ", "project_id": str(p),
                                                          "source": "web", "private": True}})
    # Встреча (только факт): итог и договорённости — владельцу.
    m = push(url, ref="777", summary="МЕТКА-ИТОГ встречи", agreements=["МЕТКА-ДОГОВОР"])
    _name(url, m["id"], client="ООО Ромашка", topic="модель")
    # «Только тебе».
    one(url, "sasha", {"op": "insight.add", "insight": {"text": "МЕТКА-ТТ-ТЕКСТ", "quote": "МЕТКА-ТТ-ЦИТАТА",
                                                         "person_ids": [str(ivan)], "project_id": str(p)}})
    marks = ["МЕТКА-ОРГ-ПОМЕТКА", "МЕТКА-ЧЕРТЫ", "МЕТКА-ПОМЕТКА", "МЕТКА-ИЩЕТ", "МЕТКА-ПРЕДЛАГАЕТ", "МЕТКА-ОТКУДА",
             "+7 000 111-22-33", "МЕТКА-ДАЙДЖЕСТ", "МЕТКА-КАКВИЖУ", "МЕТКА-ЗАМЕТКА-МАШИНЫ", "МЕТКА-ОСНОВАНИЕ",
             "МЕТКА-ТОЛЬКО-МНЕ", "МЕТКА-ПРИВАТНАЯ-ЗАПИСЬ", "МЕТКА-ИТОГ", "МЕТКА-ДОГОВОР", "МЕТКА-ТТ-ТЕКСТ",
             "МЕТКА-ТТ-ЦИТАТА"]
    return marks, {"project": p, "deal": d["id"], "ivan": ivan, "fam": fam, "tasks": [t1["id"], t2["id"]],
                   "meeting": m["id"]}


def _everything(url, user, marks, w) -> str:
    """Всё, что API отдаёт человеку: синк, виды, карточки дел, Claude карточек (только строки контекста, без вызова),
    ответы операций. Одной строкой — для поиска меток."""
    out = [store.sync(url, user, 0)]
    views = [("client", {"project_id": str(w["project"])}), ("deal", {"deal_id": str(w["deal"])}),
             ("dossier", {"person_id": str(w["ivan"])}), ("pipeline", {}), ("clients", {}), ("ties", {}), ("timeline", {}),
             ("person", {"person_id": str(w["ivan"])}), ("person", {"person_id": str(w["fam"])}), ("today", {}), ("all", {}),
             ("meetings", {}), ("insights", {}), ("new", {}), ("week", {}), ("dictations", {}), ("money", {}),
             ("meeting", {"id": str(w["meeting"])}), ("digest", {}), ("project", {"project_id": str(w["project"])})]
    views += [("search", {"q": m, "status": "all"}) for m in marks] + [("who", {"q": m}) for m in marks]
    views += [("who", {"phone": "+7 000 111-22-33"})]
    for name, params in views:
        try:
            out.append(store.view(url, user, name, **params))
        except store.OpError as e:
            out.append(str(e))
    tasks = [x["id"] for x in out[0]["tasks"]]
    for tid in tasks:
        out.append(api.task_card(url, user, tid))
    with dela_db.session(url, user, via="ask") as conn:
        for scope in ({"card": "client", "project_id": str(w["project"])}, {"card": "deal", "deal_id": str(w["deal"])},
                      {"card": "person", "person_id": str(w["ivan"])}, {"card": "person", "person_id": str(w["fam"])}):
            out.append(ask._card(conn, scope)[0])
        for tid in tasks:
            t = store.task_by(conn, tid)
            out.append(ask._task_card(conn, url, user, t, db_today(url))[0])
    out.append(ops(url, user, {"op": "person.set", "id": str(w["ivan"]), "set": {"role": "финдиректор"}},
                   {"op": "person.add", "id": str(w["fam"]), "add": {"aliases": ["Марианна"]}},
                   *({"op": "comment.add", "comment": {"task_id": tid, "text": "ок"}} for tid in tasks[:1])))
    return repr(out)


def test_broom_nothing_private_reaches_the_team(team):
    marks, w = _broom_world(team)
    url = team["url"]
    for user in ("natasha", "alena"):
        seen = _everything(url, user, marks, w)
        leaked = [m for m in marks if m in seen]
        assert not leaked, (user, leaked)
        assert "987654321" not in seen or user == "natasha"  # деньги сделки — только кому открыты (у Наташи открыты)
    # Владелец видит каждую метку там, где закон говорит «✓».
    mine = _everything(url, "sasha", marks, w)
    assert not [m for m in marks if m not in mine], [m for m in marks if m not in mine]


def test_broom_with_nesting_team_sees_flat(team):
    """Поезд, вагон 2 (дела внутри дела, правило 27) поверх закона приватности (правило 26): Саша вложил дело клиента
    (его видит команда) в своё дело из личного проекта (команде не видно). У команды дело внутри стоит плоско — why_id,
    why_title пусты, why_goal ложь; журнал Круга (вложил, вынул) карточка дела ей не отдаёт; название личного родителя
    не доходит ни одним путём метлы — синк, виды, поиск, карточки, Claude карточек. Хозяин видит и вложение, и журнал."""
    marks, w = _broom_world(team)
    url = team["url"]
    home = project(url, "sasha", "Личное", kind="personal", sphere="home")
    parent = one(url, "sasha", {"op": "task.create", "task": {"title": "МЕТКА-РОДИТЕЛЬ-ЛИЧНОЕ", "project_id": str(home)}})["task"]
    kid = str(w["tasks"][0])  # дело клиента из Telegram: команда его видит
    one(url, "sasha", {"op": "task.set", "id": kid, "set": {"why_id": str(parent["id"])}})
    marks = [*marks, "МЕТКА-РОДИТЕЛЬ-ЛИЧНОЕ"]
    flat = 0
    for user in ("natasha", "alena"):
        seen = _everything(url, user, marks, w)
        leaked = [m for m in marks if m in seen]
        assert not leaked, (user, leaked)
        with as_(url, user) as c:
            r = c.execute("SELECT why_id, why_title, why_goal, steps_open FROM tasks.v_tasks WHERE id = %s", (kid,)).fetchone()
        if r is None:
            continue  # дело ему не видно вовсе — метлы хватает
        flat += 1
        assert (r["why_id"], r["why_title"], r["why_goal"]) == (None, None, False), (user, r)
        hist = api.task_card(url, user, kid)["history"]
        assert hist and all(h["entity"] == "tasks.tasks" for h in hist), user
    assert flat, "дело клиента не видно никому из команды — проверка «плоско» ничего не проверила"
    mine = _everything(url, "sasha", marks, w)
    assert "МЕТКА-РОДИТЕЛЬ-ЛИЧНОЕ" in mine
    with as_(url, "sasha") as c:
        r = c.execute("SELECT why_id, why_title, why_goal FROM tasks.v_tasks WHERE id = %s", (kid,)).fetchone()
    assert (str(r["why_id"]), r["why_title"], r["why_goal"]) == (str(parent["id"]), "МЕТКА-РОДИТЕЛЬ-ЛИЧНОЕ", False)
    hist = api.task_card(url, "sasha", kid)["history"]
    assert any(h["entity"] == "tasks.krug" and (h["after"] or {}).get("why_id") == str(parent["id"]) for h in hist)
