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


def test_people_by_themselves_note_is_only_where(cards):
    """«Люди сами» старой службы клали в пометку «<откуда>. <почему>» — в карточку идёт только «откуда»."""
    url = cards["url"]
    s = sug(url, "person", "telegram", {"person": {"name": "Пётр Новый", "note": "Telegram: «Ромашка». МЕТКА-ПОЧЕМУ"},
                                        "where": "Telegram: «Ромашка»"}, quote="МЕТКА-ПОЧЕМУ он писал про модель")
    r = one(url, "sasha", {"op": "suggestion.decide", "id": s["id"], "decision": "accept"})
    with as_(url, "sasha") as c:
        note = c.execute("SELECT note FROM crm.people WHERE id = %s", (r["person"]["person_id"],)).fetchone()["note"]
    assert note == "Telegram: «Ромашка»"
