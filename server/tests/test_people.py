"""Второй человек в архиве (08.10.2026): Марианна шлёт со своего телефона.

Её записи с теми же ключами (сутки ленты — дата, справочник — all) лежат
рядом с записями хозяина, а не поверх; виды хозяина (life) их не видят, её
виды (marianna) не видят его записей; поиск и сутки — по человеку."""

from __future__ import annotations

import copy
import datetime as dt

import httpx

from pravka_archive import db, icu, tools
from pravka_archive.config import Config, Person
from pravka_archive.ingest import ingest_batch, store_events
from test_views import RUN, WELL
from test_views import icu as icu_event

PEOPLE = ("marianna",)


def her(batch: dict) -> dict:
    """Тот же контракт с её телефона: своё устройство, свои eid, свои слова."""
    b = copy.deepcopy(batch)
    b["device"] = "marianna-a1b2c3"
    b["profile"] = "marianna"
    for i, ev in enumerate(b["events"]):
        ev["eid"] = f"marianna-a1b2c3:{1000 + i}"
        ev["seq"] = 1000 + i
        if ev["kind"] == "zasechka.day":
            for e in ev["data"]["entries"]:
                if e.get("raw"):
                    e["raw"] = "утром йога с Леной"
    return b


def test_unknown_profile_is_refused_with_the_fix(clean, batch):
    status, res = ingest_batch(clean, dict(batch, profile="seryozha"), "sasha", PEOPLE)
    assert status == 403
    assert "PRAVKA_PEOPLE" in res["error"] and "marianna" in res["error"]


def test_same_keys_lie_side_by_side(clean, batch):
    assert ingest_batch(clean, batch, "sasha", PEOPLE)[0] == 200
    status, res = ingest_batch(clean, her(batch), "sasha", PEOPLE)
    assert status == 200, res
    assert res["rejected"] == [] and res["stored"] == len(batch["events"])
    # Сутки 2026-09-07 — у обоих, каждые своей записью.
    rows = clean.execute(
        "SELECT person FROM core.records WHERE kind = 'zasechka.day' AND key = '2026-09-07' ORDER BY person"
    ).fetchall()
    assert [r[0] for r in rows] == ["", "marianna"]
    life = clean.execute("SELECT sum(minutes) FROM life.entries WHERE day = '2026-09-07'").fetchone()[0]
    hers = clean.execute("SELECT sum(minutes) FROM marianna.entries WHERE day = '2026-09-07'").fetchone()[0]
    assert life == 1440 and hers == 1440  # не 2880: сутки не сложились
    assert clean.execute("SELECT count(*) FROM life.entries WHERE raw LIKE '%йога%'").fetchone()[0] == 0
    assert clean.execute("SELECT count(*) FROM marianna.entries WHERE raw LIKE '%йога%'").fetchone()[0] > 0
    assert clean.execute("SELECT count(*) FROM marianna.history").fetchone()[0] == len(batch["events"])
    src = dict(clean.execute("SELECT source, note->>'profile' FROM core.sources").fetchall())
    assert src == {"phone:sasha-3f9a2c": "sasha", "phone:marianna-a1b2c3": "marianna"}


def test_her_delete_does_not_touch_his_record(clean, batch):
    ingest_batch(clean, batch, "sasha", PEOPLE)
    ingest_batch(clean, her(batch), "sasha", PEOPLE)
    kill = {"eid": "marianna-a1b2c3:5000", "seq": 5000, "kind": "food.meal", "key": "f1757218205000", "op": "del",
            "at": "2026-09-07T22:00:00.000+03:00"}
    res = store_events(clean, "marianna-a1b2c3", [kill], person="marianna")
    assert res.changed == 1
    assert clean.execute("SELECT count(*) FROM marianna.meals").fetchone()[0] == 0
    assert clean.execute("SELECT count(*) FROM life.meals").fetchone()[0] == 1


def test_search_and_day_by_person(clean, batch, cfg):
    ingest_batch(clean, batch, "sasha", PEOPLE)
    ingest_batch(clean, her(batch), "sasha", PEOPLE)
    assert "Ничего не нашлось" in tools.search(cfg, "йога")
    found = tools.search(cfg, "йога", who="marianna")
    assert "засечка" in found and "Леной" in found
    # Имя по-русски — тот же человек, что ключ профиля.
    assert "йога" in tools.day(cfg, "2026-09-07", who="Марианна")
    assert "йога" not in tools.day(cfg, "2026-09-07")
    assert "Такого человека в архиве нет" in tools.day(cfg, "2026-09-07", who="Петя")
    # Дела — хозяина: в её сутках их нет, а деньги — её операции из общего журнала.
    hers = tools.day(cfg, "2026-09-07", who="marianna")
    assert "ВкусВилл" not in hers  # операция в контракте — Сашина


def test_schema_names_the_other_person(clean, batch, cfg):
    ingest_batch(clean, batch, "sasha", PEOPLE)
    ingest_batch(clean, her(batch), "sasha", PEOPLE)
    top = tools.schema(cfg)
    assert "marianna — marianna.entries" in top
    assert "телефон marianna" in top and "телефон:" in top
    hers = tools.schema(cfg, who="marianna")
    assert "Виды схемы marianna" in hers and "entries —" in hers
    assert "minutes integer" in tools.schema(cfg, "entries", who="marianna")
    # Читатель Claude видит её схему, а писать не может.
    assert tools.run_sql(cfg, "SELECT count(*) AS n FROM marianna.entries").splitlines()[1] != "0"


def test_her_intervals_go_under_her_and_leave_his_alone(clean, cfg):
    store_events(clean, "intervals", [icu_event("icu.activity", "i9001", RUN, 1), icu_event("icu.wellness", "2026-09-07", WELL, 2)])
    mine = dict(RUN, id="i5001", name="Йога", type="Yoga", start_date_local="2026-09-07T08:00:00")
    seen: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.url.path)
        path = request.url.path
        if path.endswith("/activities"):
            return httpx.Response(200, json=[mine])
        if path.endswith("/wellness"):
            return httpx.Response(200, json=[dict(WELL, weight=61.5)])
        if path.endswith("/events"):
            return httpx.Response(200, json=[])
        if path.endswith("/streams.json") or path.endswith("/file"):
            return httpx.Response(404)
        if "/activity/" in path:
            return httpx.Response(200, json=mine)
        return httpx.Response(404)

    person = next(p for p in cfg.persons() if p.profile == "marianna")
    assert person.icu_source == "intervals-marianna" and person.has_icu
    client = httpx.Client(base_url=icu.BASE, transport=httpx.MockTransport(handler), auth=("API_KEY", "key-m"))
    p = icu.Puller(cfg, client=client, today=lambda: dt.date(2026, 9, 8), person=person)
    p.run("recent")
    assert all("/athlete/i777" in s or "/activity/" in s for s in seen)
    assert clean.execute("SELECT name FROM marianna.workouts").fetchall() == [("Йога",)]
    assert clean.execute("SELECT weight_kg FROM marianna.wellness").fetchone()[0] == 61.5
    # Её «тренировки в окне» — только её: Сашин бег не стал удалением.
    assert clean.execute("SELECT name FROM life.workouts").fetchall() == [("Москва Бег",)]
    assert clean.execute("SELECT count(*) FROM core.events WHERE op = 'del'").fetchone()[0] == 0
    fresh = clean.execute("SELECT note ? 'recent_at' FROM core.sources WHERE source = 'intervals-marianna'").fetchone()
    assert fresh == (True,)


def test_person_sql_renames_schema_and_core_views():
    text = (
        "DROP SCHEMA IF EXISTS life CASCADE;\n"
        "CREATE VIEW core.records_life AS SELECT * FROM core.records WHERE person = '';\n"
        "CREATE VIEW life.x AS SELECT life.hm(d) FROM core.records_life r; -- lifelong\n"
    )
    out = db.person_sql(text, Person("marianna", "marianna", "marianna"))
    assert "DROP SCHEMA IF EXISTS marianna CASCADE" in out
    assert "core.records_marianna AS SELECT * FROM core.records WHERE person = 'marianna'" in out
    assert "marianna.x AS SELECT marianna.hm(d) FROM core.records_marianna r" in out
    assert "lifelong" in out  # слово внутри другого слова не трогается
    owner = Person("sasha", "", "life")
    assert db.person_sql(text, owner) == text


def test_config_people_from_env(monkeypatch, tmp_path):
    from pravka_archive import config

    env = tmp_path / "server.env"
    env.write_text("PRAVKA_PEOPLE=marianna, life, Sasha\nICU_ATHLETE_ID_MARIANNA=i777\nICU_API_KEY_MARIANNA=k\n", encoding="utf-8")
    c = config.load(str(env))
    assert c.people == ("marianna", "life", "sasha")
    assert [p.profile for p in c.persons()] == ["sasha", "marianna"]  # служебное и хозяин отсеяны
    assert c.persons()[1].icu_athlete == "i777"
    assert any("«life» не годится" in x for x in c.problems())
    assert c.person_of("marianna").schema == "marianna" and c.person_of("petya") is None
    assert isinstance(c, Config)
