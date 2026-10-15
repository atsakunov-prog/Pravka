"""Контекст по делу (аудит 15.10.2026, №13): вид context_index — связанные люди и ключи имён, матчер не строже who.

Люди и компании — выдуманные."""

from __future__ import annotations

import json

import pytest
from starlette.testclient import TestClient

from pravka_dela import api, focus, store, tokens
from pravka_dela import db as dela_db
from pravka_dela.config import Config
from test_dela_schema import dela, project  # noqa: F401  (фикстура и помощник)

OLD = "now() - interval '60 days'"


def _person(c, name, **kw):
    cols = {"name": name, "owner_id": "sasha", **kw}
    keys = ", ".join(cols) + ", created_at"
    marks = ", ".join(["%s"] * len(cols)) + f", {OLD}"
    return str(c.execute(f"INSERT INTO crm.people ({keys}) VALUES ({marks}) RETURNING id", list(cols.values())).fetchone()["id"])


def test_related_people_by_the_one_rule(dela):
    p = project(dela, "sasha", "ООО Ромашка")
    with dela_db.session(dela, "system", "test") as c:
        team = _person(c, "Наталья Тестова")
        c.execute("UPDATE crm.users SET person_id = %s WHERE id = 'natasha'", (team,))
        touched, stale = _person(c, "Касание Свежее"), _person(c, "Касание Старое")
        c.execute("INSERT INTO crm.interactions (at, kind, summary, project_id, person_ids, source, owner_id) VALUES "
                  "(now() - interval '89 days', 'call', 'созвон', %s, %s, 'web', 'sasha'), "
                  "(now() - interval '91 days', 'call', 'созвон', %s, %s, 'web', 'sasha')", (p, [touched], p, [stale]))
        by_task = _person(c, "Дело Открытое")
        c.execute("INSERT INTO tasks.tasks (title, owner_id, created_by, person_id, project_id) VALUES "
                  "('Ромашка: договор', 'sasha', 'sasha', %s, %s)", (by_task, p))
        by_deal = _person(c, "Сделка Живая")
        c.execute("INSERT INTO crm.deals (project_id, name, stage, person_ids) VALUES (%s, 'Ромашка: модель', 'active', %s)",
                  (p, [by_deal]))
        by_meeting = _person(c, "Встреча Свежая")
        c.execute("INSERT INTO crm.meetings (ref, started_at, owner_id, person_ids) VALUES ('41', now() - interval '3 days', "
                  "'sasha', %s)", ([by_meeting],))
        warm = _person(c, "Тёплый Тестов", cadence="quarter")
        new = c.execute("INSERT INTO crm.people (name, owner_id) VALUES ('Новый Тестов', 'sasha') RETURNING id").fetchone()["id"]
        mute = _person(c, "Немой Контакт")
        live = _person(c, "Живая Карточка")
        dup = _person(c, "Дубль Карточки", merged_into=live, archived_at=None)
        c.execute("UPDATE crm.people SET archived_at = now() WHERE id = %s", (dup,))
        c.execute("INSERT INTO tasks.tasks (title, owner_id, created_by, person_id) VALUES ('Позвонить', 'sasha', 'sasha', %s)",
                  (dup,))
    with dela_db.session(dela, "sasha", "test") as c:
        rel = set(focus.related(c))
    assert {team, touched, by_task, by_deal, by_meeting, warm, str(new), live} <= rel
    assert not {stale, mute, dup} & rel
    # Не-владельцу — только видимое ему (RLS): дело Саши во «Входящих» Наташа не видит, и того, кто с ним связан, — тоже.
    with dela_db.session(dela, "natasha", "test") as c:
        theirs = set(focus.related(c))
    assert live not in theirs and team in theirs


SOKOLOV = ["Евгений Соколов"]


@pytest.mark.parametrize("text,names,hit", [
    ("встреча с Соколовым", SOKOLOV, True), ("Евгением доволен", SOKOLOV, True), ("передать Жене", SOKOLOV, True),
    ("Женя сказал", SOKOLOV, True), ("позвать Марину", ["Мария Тестова"], False), ("позвать Марину", ["Марина Тестова"], True),
    ("Марии", ["Мария Тестова"], True), ("Новик пришёл", ["Пётр Новиков"], False), ("Мише", ["Миша Иванов"], True),
    ("с Мишей", ["Миша Иванов"], True), ("Vasiliy Belousov", ["Василий Белоусов"], True),
])
def test_name_keys_and_matcher(text, names, hit):
    keys = {"p": focus.keys_of(names)}
    assert (focus.found(text, keys) == {"p"}) is hit, (text, keys)


def test_project_keys_skip_generic_words():
    keys = {"r": focus.keys_of(["ООО Ромашка"], generic=True, min_len=focus.PROJECT_MIN)}
    assert focus.found("у Ромашки", keys) == {"r"} and focus.found("Ромашке", keys) == {"r"}
    assert focus.found("ООО", keys) == set() and focus.found("банк группа", keys) == set()


# Случаи, где people.who уверенно находит карточку по имени (test_dela_people.py): матчер не строже — тот же человек
# назван и для него (иначе с укороченным справочником модель потеряла бы человека, которого Дела узнали бы).
WHO_CASES = [
    (["Евгений Соколов", "Евгений Иванов", "Анна Смирнова"], ["Женя Соколов", "Соколов Евгений", "Евгений Соколов (ромашка)",
                                                                "Женя Соколов из Ромашки", "Евгением Соколовым"]),
    (["Ольга Петрова", "Борис Петров", "Дмитрий Кузнецов", "Дмитрий Орлов", "Анна Иванова"],
     ["Оля Петрова (ВО)", "Анна Иванова (терапевт)"]),
    (["Василий Белоусов", "Елена Смирнова"], ["Vasiliy Belousov (Sever Capital)", "Лена Смирнова (ЗФ)"]),
    (["Александр Комаров", "Дмитрий Лебедев", "Дмитрий Орлов", "Вася Белоусов"],
     ["a.komarov", "Dmitry L", "Vasiliy Belousov", "Василий Белоусов"]),
    (["Евгений Соколов", "Валерий Иванов", "Наталья Пробная"], ["Евгением Соколовым", "Валера Иванов", "Наталия Пробная"]),
]


def test_matcher_is_not_stricter_than_who(dela):
    from pravka_dela import people

    for names, queries in WHO_CASES:
        with dela_db.session(dela, "system", "test") as c:
            c.execute("UPDATE crm.people SET archived_at = now() WHERE archived_at IS NULL")
            for n in names:
                _person(c, n)
        with dela_db.session(dela, "sasha", "test") as c:
            focus._cache.clear()
            for q in queries:
                w = people.who(c, q=q)
                if not w["sure"]:
                    continue
                got = focus.mentioned(c, "sasha", q)
                assert str(w["best"]["id"]) in got, (q, w["best"]["name"], got)


def test_view_flag_scope_and_no_contacts(dela):
    with dela_db.session(dela, "system", "test") as c:
        _person(c, "Иван Тестов", phones=["+7 000 000-00-07"], emails=["ivan@example.org"], note="МЕТКА-ЗАМЕТКА",
                cadence="month")
    assert "context_index" in store.sync(dela, "sasha", 0)["features"]
    v = store.view(dela, "sasha", "context_index")
    text = json.dumps(v, ensure_ascii=False)
    assert v["rule"] == focus.RULE and v["days"] == focus.DAYS and v["letters"] and v["endings"] and v["translit"]
    assert any("тест" in k for ks in v["people"].values() for k in ks) and v["related"]
    assert "000-00-07" not in text and "example.org" not in text and "МЕТКА" not in text
    client = TestClient(api.build(Config(db_url=dela)))
    h = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'service', 'dengi')}"}
    assert client.get("/api/view/context_index", headers=h).status_code == 403
    owner = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'service', 'meetings')}"}
    r = client.get("/api/view/context_index?days=30", headers=owner)
    assert r.status_code == 200 and r.json()["days"] == 30
    assert "context_index" in client.get("/api/me", headers=owner).json()["features"]
