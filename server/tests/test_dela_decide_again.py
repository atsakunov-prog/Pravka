"""Повтор решения в «Новом» — не ошибка (отладка 16.10.2026, записи 1–2).

Второй тап «Да» или «Не надо» (телефон без связи шлёт очередь заново с новым op_id, экран отстал от синка, решили с
другого устройства) получал отказ «предложение уже разобрано или не твоё», и над «Новым» висела красная плашка
«Сервер не принял …», хотя всё сделано. Теперь тот же исход — ok с already, ничего не меняется и не учится; другой
исход — отказ, который называет, как и когда решено; чужое или несуществующее — «нет или не твой». Данные — выдуманные.
"""

from __future__ import annotations

import logging
import uuid

from starlette.testclient import TestClient

from pravka_dela import api, store, tokens
from pravka_dela import db as dela_db
from pravka_dela.config import Config
from test_dela_schema import dela  # noqa: F401  (фикстура)
from test_dela_speakers import VOICE, card, link_sug, sp  # noqa: F401  (фикстура и помощники)


def ops(url, user, *items):
    """Каждой операции — свой op_id, как у телефона: повтор тапа — НОВЫЙ op_id, ops_seen его не узнает."""
    return store.apply_ops(url, user, [{"op_id": str(uuid.uuid4()), **o} for o in items], "app")["results"]


def one(url, item, user="sasha"):
    r = ops(url, user, item)[0]
    assert r["ok"], r
    return r


def sug(url, title, **payload):
    return one(url, {"op": "suggestion.create", "suggestion": {
        "for_user": "sasha", "kind": "create", "source": "meeting", "source_ref": f"meeting:{title}",
        "batch_ref": "meeting:77", "batch_title": "Ромашка: статус · 14.10", "payload": {"title": title, **payload}}},
        user="system")["suggestion"]


def decide(url, s, user="sasha", **kw):
    return ops(url, user, {"op": "suggestion.decide", "id": str(s["id"]), **kw})[0]


def row(url, s):
    with dela_db.session(url, "system", "t") as c:
        return c.execute("SELECT * FROM tasks.suggestions WHERE id = %s", (s["id"],)).fetchone()


def titled(url, title):
    with dela_db.session(url, "system", "t") as c:
        return c.execute("SELECT count(*) AS n FROM tasks.tasks WHERE title = %s", (title,)).fetchone()["n"]


def at(url, s, when="2026-10-16 06:05:00+00"):
    """Решено в известный миг — чтобы проверить слова отказа «дд.мм в чч:мм» по Москве."""
    with dela_db.session(url, "system", "t") as c:
        c.execute("UPDATE tasks.suggestions SET decided_at = %s WHERE id = %s", (when, s["id"]))


def test_yes_twice_is_ok_already_one_task_same_decided_at(dela):
    s = sug(dela, "Иван Тестов: прислать тизер", ball="mine")
    first = decide(dela, s, decision="accept")
    assert first["ok"] and first["task"]["title"] == "Иван Тестов: прислать тизер", first
    # Второй тап «Да» с новым op_id — ok, отметка already, дела второго нет, решение прежнее.
    again = decide(dela, s, decision="accept")
    assert again["ok"] and again["already"] is True and "task" not in again, again
    assert again["suggestion"]["status"] == "accepted"
    assert again["suggestion"]["decided_at"] == first["suggestion"]["decided_at"]
    assert again["suggestion"]["rev"] == first["suggestion"]["rev"]  # строку не трогали вовсе
    assert again["suggestion"]["result_task_id"] == first["task"]["id"]
    assert titled(dela, "Иван Тестов: прислать тизер") == 1
    # «Да» у принятого с правкой — тоже тот же исход.
    e = sug(dela, "Ольга Тестова: КП", ball="mine")
    assert decide(dela, e, decision="accept", set={"ball": "waiting"})["suggestion"]["status"] == "edited"
    r = decide(dela, e, decision="accept")
    assert r["ok"] and r["already"] and titled(dela, "Ольга Тестова: КП") == 1, r


def test_no_twice_and_option_reject_twice_is_ok_already(dela):
    s = sug(dela, "Идея: фонд")
    assert decide(dela, s, decision="reject", reason="это идея, не дело")["suggestion"]["status"] == "rejected"
    r = decide(dela, s, decision="reject", reason="другая причина")
    assert r["ok"] and r["already"], r
    assert row(dela, s)["reason"] == "это идея, не дело"  # первое слово не переписано
    # Вариант «не дело» дважды; «Не надо» после него — тот же исход.
    opts = [{"label": "Мне", "ball": "mine"}, {"label": "Жду", "ball": "waiting"},
            {"label": "Не дело", "reject": True, "reason": "не дело"}]
    q = sug(dela, "Петров: созвон", ask="Кто держит мяч?", options=opts)
    assert decide(dela, q, option=2)["suggestion"]["status"] == "rejected"
    r = decide(dela, q, option=2)
    assert r["ok"] and r["already"], r
    r = decide(dela, q, decision="reject")
    assert r["ok"] and r["already"], r
    assert titled(dela, "Петров: созвон") == 0
    # Вариант «Да» дважды — тот же ответ; другой вариант «Да» — другой ответ (мяч у другого): отказ, дело как было.
    w = sug(dela, "Сидоров: смета", ask="Кто держит мяч?", options=opts)
    t = decide(dela, w, option=1)["task"]
    assert t["ball"] == "waiting"
    r = decide(dela, w, option=1)
    assert r["ok"] and r["already"], r
    r = decide(dela, w, option=0)
    assert not r["ok"] and r["error"].startswith("Уже решено раньше: принято "), r
    r = decide(dela, w, option=9)  # нет такого варианта — тоже не тот же исход
    assert not r["ok"] and r["error"].startswith("Уже решено раньше: принято "), r
    assert titled(dela, "Сидоров: смета") == 1


def test_yes_after_no_is_refused_with_when(dela):
    s = sug(dela, "Вера Тестова: счёт")
    decide(dela, s, decision="reject", reason="не дело")
    at(dela, s)
    r = decide(dela, s, decision="accept")
    assert not r["ok"] and r["error"] == "Уже решено раньше: отклонено 16.10 в 09:05 — повторять не нужно", r
    r = decide(dela, s, option=0)  # вариантов нет — не тот же исход
    assert not r["ok"] and r["error"].startswith("Уже решено раньше: отклонено"), r
    assert titled(dela, "Вера Тестова: счёт") == 0
    # «Не надо» после «Да» — так же.
    y = sug(dela, "Тизер Ромашке")
    decide(dela, y, decision="accept")
    at(dela, y, "2026-10-15 21:30:00+00")
    r = decide(dela, y, decision="reject")
    assert not r["ok"] and r["error"] == "Уже решено раньше: принято 16.10 в 00:30 — повторять не нужно", r
    assert row(dela, y)["status"] == "accepted"
    # Погасшее само: «Да» — отказ «закрыто само» (дела нет, и тап его не заведёт); «Не надо» — тот же исход.
    old = one(dela, {"op": "suggestion.create", "suggestion": {
        "for_user": "sasha", "kind": "create", "source": "userbot", "payload": {"title": "Старое"},
        "expires_at": "2020-01-01T00:00:00+03:00"}}, user="system")["suggestion"]
    assert store.expire_suggestions(dela) == 1
    at(dela, old, "2026-10-16 00:00:00+00")
    r = decide(dela, old, decision="accept")
    assert not r["ok"] and r["error"] == "Уже решено раньше: закрыто само 16.10 в 03:00 — повторять не нужно", r
    r = decide(dela, old, decision="reject")
    assert r["ok"] and r["already"], r
    assert row(dela, old)["status"] == "expired" and titled(dela, "Старое") == 0


def test_yes_with_set_on_decided_is_refused(dela):
    s = sug(dela, "Иван Тестов: договор", ball="mine")
    t = decide(dela, s, decision="accept")["task"]
    r = decide(dela, s, decision="accept", set={"ball": "waiting"})
    assert not r["ok"] and r["error"].startswith("Уже решено раньше: принято "), r
    with dela_db.session(dela, "sasha", "t") as c:
        assert c.execute("SELECT ball FROM tasks.tasks WHERE id = %s", (t["id"],)).fetchone()["ball"] == "mine"
    # Пустая правка (коннектор шлёт set: {}) — не правка.
    r = decide(dela, s, decision="accept", set={})
    assert r["ok"] and r["already"], r


def test_person_answer_again_does_not_teach_twice(sp):
    url = sp["url"]
    learn = {str(sp["denis"]): {"emails": ["Denis.O@beta-test.ru"], "address": "вы, Денис Ильич",
                                "zoom_name": "Denis Obraztsov"}}
    s = one(url, link_sug(sp["denis"], [VOICE, {"group": "speech", "strong": False, "text": "слабое"}],
                          learn=learn))["suggestion"]
    assert s["status"] == "pending", s
    first = decide(url, s, option=0)
    assert first["ok"] and first["suggestion"]["result"]["learned"]["wrote"]["address"] == "вы, Денис Ильич", first
    # Владелец сам стёр выученное (не то обращение) — повтор того же тапа не должен вписать его снова.
    with dela_db.session(url, "sasha", "t") as c:
        c.execute("UPDATE crm.people SET address = NULL, aliases = '{}', emails = '{denis@beta-test.ru}' WHERE id = %s",
                  (sp["denis"],))
        hist = c.execute("SELECT count(*) AS n FROM crm.history").fetchone()["n"]
    again = decide(url, s, option=0)
    assert again["ok"] and again["already"] and "person" not in again, again
    assert again["suggestion"]["result"] == first["suggestion"]["result"]
    p = card(url, sp["denis"])
    assert (p["address"], p["aliases"], p["emails"]) == (None, [], ["denis@beta-test.ru"])
    with dela_db.session(url, "sasha", "t") as c:
        assert c.execute("SELECT count(*) AS n FROM crm.history").fetchone()["n"] == hist  # ничего не записано
    # «Нет, не он» после «Это Денис» — другой исход: отказ словами.
    r = decide(url, s, option=1)
    assert not r["ok"] and r["error"].startswith("Уже решено раньше: принято "), r


def test_foreign_or_missing_suggestion_is_not_yours(dela):
    s = sug(dela, "Иван Тестов: письмо")
    for who, sid in (("natasha", s["id"]), ("sasha", str(uuid.uuid4())), ("sasha", "не id")):
        r = ops(dela, who, {"op": "suggestion.decide", "id": sid, "decision": "accept"})[0]
        assert not r["ok"] and r["error"] == "Такого вопроса нет или он не твой", (who, r)
    assert row(dela, s)["status"] == "pending"  # чужой тап ничего не решил
    # Третий пользователь завёл вопрос Саше — видит его (автор), но решать не ему.
    m = one(dela, {"op": "suggestion.create", "suggestion": {
        "for_user": "sasha", "kind": "create", "source": "user", "payload": {"title": "Записать к врачу"}}},
        user="marianna")["suggestion"]
    r = decide(dela, m, user="marianna", decision="accept")
    assert not r["ok"] and r["error"] == "Такого вопроса нет или он не твой", r


def test_refusal_names_op_and_id_and_replays_by_op_id(dela, caplog):
    s = sug(dela, "Ольга Тестова: ответ")
    decide(dela, s, decision="accept")
    op = {"op_id": str(uuid.uuid4()), "op": "suggestion.decide", "id": s["id"], "decision": "reject"}
    r = store.apply_ops(dela, "sasha", [op], "app")["results"][0]
    assert (r["ok"], r["op"], r["id"]) == (False, "suggestion.decide", str(s["id"])), r
    assert r["error"].startswith("Уже решено раньше: принято ")
    with dela_db.session(dela, "sasha", "t") as c:
        seen = c.execute("SELECT result FROM crm.ops_seen WHERE op_id = %s", (op["op_id"],)).fetchone()["result"]
    assert seen == r
    # Повтор той же операции (тот же op_id) — прежний ответ из ops_seen, слово в слово.
    assert store.apply_ops(dela, "sasha", [op], "app")["results"][0] == r
    # Операция без id — id пустой, отказ всё равно называет операцию.
    bad = ops(dela, "sasha", {"op": "нет.такой"})[0]
    assert (bad["ok"], bad["op"], bad["id"]) == (False, "нет.такой", None), bad
    # Журнал службы называет операцию и начало id.
    client = TestClient(api.build(Config(db_url=dela)))
    h = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'device', 'fold')}"}
    with caplog.at_level(logging.INFO, logger="dela.api"):
        res = client.post("/api/ops", headers=h, json={"ops": [{"op_id": str(uuid.uuid4()), "op": "suggestion.decide",
                                                                 "id": str(s["id"]), "decision": "reject"}]})
    assert res.status_code == 200 and not res.json()["results"][0]["ok"], res.text
    line = f"первая: suggestion.decide {str(s['id'])[:8]}: Уже решено раньше: принято "
    assert any(line in m for m in caplog.messages), caplog.messages
