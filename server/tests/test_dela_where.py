"""«Где мы» (10.10.2026): точки семьи — видит и пишет только семья, одна точка на телефон,
перестал делиться — точки нет, сам себя в семью не добавишь."""

from __future__ import annotations

import base64
import json
from pathlib import Path

import psycopg
import pytest

from pravka_dela import db as dela_db
from pravka_dela import store
from test_dela_schema import dela  # noqa: F401  (фикстура)

CONTRACT = json.loads((Path(__file__).resolve().parents[1] / "contract" / "dela-where.json").read_text(encoding="utf-8"))


@pytest.fixture()
def fam(dela, conn):
    """Саша и Марианна в семье, Наташа — нет (как на сервере после миграции 8)."""
    conn.execute("TRUNCATE crm.where_points, crm.where_asks")
    with dela_db.session(dela, "system", "test") as c:
        c.execute("UPDATE crm.users SET family = (id IN ('sasha', 'marianna'))")
    return dela


def run(url, user, *ops):
    return store.apply_ops(url, user, list(ops), "app")["results"]


def point(lat=55.7447, lon=37.5372, **kw):
    return {"lat": lat, "lon": lon, "acc": 20.0, "at": 1791633600000, "src": "fused", **kw}


def test_family_sees_each_other(fam):
    r = run(fam, "sasha", {"op": "where.set", "device": "sasha-3f9a2c", "point": point(place="Дом")})[0]
    assert r["ok"], r
    run(fam, "marianna", {"op": "where.set", "device": "marianna-01ab9e", "point": point(55.75, 37.6)})
    seen = store.view(fam, "marianna", "where")
    assert seen["family"] is True
    assert {p["device"] for p in seen["points"]} == {"sasha-3f9a2c", "marianna-01ab9e"}
    sasha = next(p for p in seen["points"] if p["user_id"] == "sasha")
    assert sasha["name"] == "Саша" and sasha["point"]["place"] == "Дом" and sasha["has_avatar"] is False


def test_not_family_sees_nothing_and_cannot_write(fam):
    run(fam, "sasha", {"op": "where.set", "device": "sasha-3f9a2c", "point": point()})
    out = store.view(fam, "natasha", "where")
    assert out == {"family": False, "points": [], "asks": [], "now": out["now"]}
    r = run(fam, "natasha", {"op": "where.set", "device": "natasha-1", "point": point()},
            {"op": "where.ask", "device": "natasha-1"})
    assert not r[0]["ok"] and "не в семье" in r[0]["error"]
    assert not r[1]["ok"]
    # И аватар чужой семьи ей не отдаётся.
    assert store.view(fam, "natasha", "where_avatar", device="sasha-3f9a2c")["avatar"] is None


def test_one_point_per_phone_and_no_history(fam, conn):
    for lat in (55.70, 55.71, 55.72):
        run(fam, "sasha", {"op": "where.set", "device": "sasha-3f9a2c", "point": point(lat)})
    rows = conn.execute("SELECT point FROM crm.where_points").fetchall()
    assert len(rows) == 1 and rows[0][0]["lat"] == 55.72
    # Журнал не копит перемещений — истории точек нет нигде.
    assert conn.execute("SELECT count(*) FROM crm.history WHERE entity LIKE 'crm.where%'").fetchone()[0] == 0


def test_cannot_overwrite_someone_elses_phone(fam):
    run(fam, "sasha", {"op": "where.set", "device": "sasha-3f9a2c", "point": point()})
    r = run(fam, "marianna", {"op": "where.set", "device": "sasha-3f9a2c", "point": point(1.0, 1.0)})[0]
    assert not r["ok"]
    p = store.view(fam, "sasha", "where")["points"][0]
    assert p["point"]["lat"] == 55.7447
    # И убрать чужую точку нельзя: off трогает только свои строки.
    assert run(fam, "marianna", {"op": "where.off", "device": "sasha-3f9a2c"})[0]["removed"] is False
    assert len(store.view(fam, "sasha", "where")["points"]) == 1


def test_off_removes_point_and_ask(fam):
    run(fam, "sasha", {"op": "where.set", "device": "sasha-3f9a2c", "point": point()},
        {"op": "where.ask", "device": "sasha-3f9a2c"})
    assert store.view(fam, "marianna", "where")["asks"][0]["device"] == "sasha-3f9a2c"
    r = run(fam, "sasha", {"op": "where.off", "device": "sasha-3f9a2c"})[0]
    assert r["ok"] and r["removed"] is True
    seen = store.view(fam, "marianna", "where")
    assert seen["points"] == [] and seen["asks"] == []


def test_avatar_kept_until_changed(fam):
    jpeg = b"\xff\xd8\xff" + b"x" * 1000
    run(fam, "sasha", {"op": "where.set", "device": "sasha-3f9a2c", "point": point(),
                       "avatar": base64.b64encode(jpeg).decode(), "avatar_at": 77})
    run(fam, "sasha", {"op": "where.set", "device": "sasha-3f9a2c", "point": point(55.8)})
    p = store.view(fam, "marianna", "where")["points"][0]
    assert p["avatar_at"] == 77 and p["has_avatar"] is True
    got = store.view(fam, "marianna", "where_avatar", device="sasha-3f9a2c")
    assert base64.b64decode(got["avatar"]) == jpeg and got["avatar_at"] == 77
    run(fam, "sasha", {"op": "where.set", "device": "sasha-3f9a2c", "point": point(), "avatar": None, "avatar_at": 0})
    assert store.view(fam, "marianna", "where")["points"][0]["has_avatar"] is False


def test_bad_points_are_refused(fam):
    r = run(fam, "sasha",
            {"op": "where.set", "device": "sasha-3f9a2c", "point": point(91.0)},
            {"op": "where.set", "device": "Саша", "point": point()},
            {"op": "where.set", "device": "sasha-3f9a2c", "point": {"lat": 55.7}},
            {"op": "where.set", "device": "sasha-3f9a2c", "point": point(note="x" * 5000)},
            {"op": "where.set", "device": "sasha-3f9a2c", "point": point(), "avatar": "не base64!"})
    assert [x["ok"] for x in r] == [False] * 5


def test_nobody_joins_family_by_themselves(fam):
    with pytest.raises(psycopg.errors.RaiseException):
        with dela_db.session(fam, "natasha", via="app") as c:
            c.execute("UPDATE crm.users SET family = true WHERE id = 'natasha'")


def test_contract_matches_server(fam):
    assert "where" in store.FEATURES
    for o in CONTRACT["ops_examples"]:
        assert o["op"] in store.HANDLERS
    run(fam, "sasha", *[{k: v for k, v in o.items() if k != "_"} for o in CONTRACT["ops_examples"] if o["op"] != "where.off"])
    run(fam, "marianna", {"op": "where.set", "device": "marianna-01ab9e", "point": point(),
                          "avatar": base64.b64encode(b"\xff\xd8\xff").decode(), "avatar_at": 5})
    out = store.view(fam, "sasha", "where")
    want = CONTRACT["views"]["where"]["response"]
    assert set(want) <= set(out)
    assert set(want["points"][0]) <= set(out["points"][0])
    assert set(want["asks"][0]) <= set(out["asks"][0])
    av = store.view(fam, "sasha", "where_avatar", device="marianna-01ab9e")
    assert set(CONTRACT["views"]["where_avatar"]["response"]) <= set(av)


def test_migration_with_users_in_place(admin_dsn):
    """Миграция 8 — поверх живых Дел, где пользователи уже есть (10.10.2026 у владельца упала:
    правка crm.users без подписи для журнала). В общей тестовой базе миграции идут по пустой."""
    import secrets as _secrets
    from urllib.parse import urlparse, urlunparse

    from psycopg import sql

    name = f"pa_mig8_{_secrets.token_hex(4)}"
    with psycopg.connect(admin_dsn, autocommit=True) as a:
        a.execute(sql.SQL("CREATE DATABASE {} ENCODING 'UTF8' TEMPLATE template0").format(sql.Identifier(name)))
    u = urlparse(admin_dsn)
    url = urlunparse((u.scheme, u.netloc, "/" + name, "", "", ""))
    try:
        files = dict(dela_db._sql_files())
        before = {k: v for k, v in files.items() if k.startswith("dela_") and k < "dela_0008.sql"}
        with psycopg.connect(url, autocommit=True) as c:
            c.execute("CREATE SCHEMA core")
            c.execute("CREATE TABLE core.migrations (name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())")
            dela_db.apply_pending(c, before)
        with dela_db.session(url, "system", "test") as c:
            c.execute("INSERT INTO crm.users (id, name, role) VALUES ('sasha', 'Саша', 'owner'), "
                      "('natasha', 'Наташа', 'member'), ('marianna', 'Марианна', 'member')")
        with psycopg.connect(url, autocommit=True) as c:
            assert dela_db.apply_pending(c, files) == ["dela_0008.sql"]
            fam = {r[0] for r in c.execute("SELECT id FROM crm.users WHERE family")}
        assert fam == {"sasha", "marianna"}
    finally:
        with psycopg.connect(admin_dsn, autocommit=True) as a:
            a.execute(sql.SQL("DROP DATABASE IF EXISTS {} WITH (FORCE)").format(sql.Identifier(name)))
