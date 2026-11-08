"""«Где мы» снято (08.11.2026, dela_0039): сервер карты семьи не знает — ни операций, ни видов, ни таблиц точек.
Старая сборка Правки на «нет такого вида» перестаёт искать себя по расписанию обмена и ничего не шлёт. Флаг семьи
остался: по нему дайджест и карточки людей узнают своих, и сам себя в семью по-прежнему никто не добавит."""

from __future__ import annotations

import psycopg
import pytest

from pravka_dela import db as dela_db
from pravka_dela import store
from test_dela_schema import dela  # noqa: F401  (фикстура)


def run(url, user, *ops):
    return store.apply_ops(url, user, list(ops), "app")["results"]


def test_server_knows_nothing_of_the_map(dela):
    assert "where" not in store.FEATURES
    assert not [k for k in store.HANDLERS if k.startswith("where.")]
    for name in ("where", "where_avatar"):
        with pytest.raises(store.OpError, match="нет такого вида"):
            store.view(dela, "sasha", name)


def test_old_phone_gets_a_refusal_not_a_point(dela):
    """Так старая сборка и слышит: точка не принята, обмен «не вышел» — и искать себя к нему ей незачем."""
    point = {"lat": 55.7447, "lon": 37.5372, "acc": 20.0, "at": 1791633600000, "src": "fused"}
    r = run(dela, "sasha", {"op": "where.set", "device": "sasha-3f9a2c", "point": point},
            {"op": "where.off", "device": "sasha-3f9a2c"}, {"op": "where.ask", "device": "sasha-3f9a2c"})
    assert [x["ok"] for x in r] == [False] * 3
    assert all("нет такой операции" in x["error"] for x in r)


def test_points_are_gone_family_flag_stays(dela, conn):
    gone = conn.execute("SELECT to_regclass('crm.where_points') IS NULL, to_regclass('crm.where_asks') IS NULL").fetchone()
    assert gone == (True, True)
    assert conn.execute("SELECT count(*) FROM pg_proc WHERE proname = 'in_family'").fetchone()[0] == 0
    assert conn.execute("SELECT count(*) FROM information_schema.columns "
                        "WHERE table_schema = 'crm' AND table_name = 'users' AND column_name = 'family'").fetchone()[0] == 1


def test_nobody_joins_family_by_themselves(dela):
    with pytest.raises(psycopg.errors.RaiseException):
        with dela_db.session(dela, "natasha", via="app") as c:
            c.execute("UPDATE crm.users SET family = true WHERE id = 'natasha'")


def test_migration_with_users_in_place(admin_dsn):
    """Миграция 8 — поверх живых Дел, где пользователи уже есть (10.10.2026 у владельца упала:
    правка crm.users без подписи для журнала), и следующие за ней до 39-й, которая карту сносит. В общей
    тестовой базе миграции идут по пустой."""
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
            # Восьмая — первой; следующие (Круг, dela_0009, …, снос карты, dela_0039) едут за ней по тем же данным.
            done = dela_db.apply_pending(c, files)
            assert done[0] == "dela_0008.sql" and "dela_0039.sql" in done
            fam = {r[0] for r in c.execute("SELECT id FROM crm.users WHERE family")}
            gone = c.execute("SELECT to_regclass('crm.where_points') IS NULL").fetchone()[0]
        assert fam == {"sasha", "marianna"} and gone
    finally:
        with psycopg.connect(admin_dsn, autocommit=True) as a:
            a.execute(sql.SQL("DROP DATABASE IF EXISTS {} WITH (FORCE)").format(sql.Identifier(name)))
