"""Тесты на настоящем PostgreSQL: виды и поиск проверяются только им.

Нужна база с правом суперпользователя: адрес в PRAVKA_TEST_DSN
(например postgresql://postgres@127.0.0.1:55432/postgres). На компе это
установленный PostgreSQL: PRAVKA_TEST_DSN=postgresql://postgres:<пароль>@127.0.0.1:5432/postgres.
Каждый прогон заводит свою базу и свои роли и сносит их в конце.
"""

from __future__ import annotations

import json
import os
import secrets
import sys
from pathlib import Path
from urllib.parse import urlparse, urlunparse

import psycopg
import pytest
from psycopg import sql

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from pravka_archive.config import Config  # noqa: E402
from pravka_archive import db  # noqa: E402


def _with(dsn: str, user: str, password: str, dbname: str) -> str:
    u = urlparse(dsn)
    netloc = f"{user}:{password}@{u.hostname}:{u.port or 5432}"
    return urlunparse((u.scheme, netloc, "/" + dbname, "", "", ""))


@pytest.fixture(scope="session")
def admin_dsn() -> str:
    dsn = os.environ.get("PRAVKA_TEST_DSN")
    if not dsn:
        pytest.skip("PRAVKA_TEST_DSN не задан: тестам нужен PostgreSQL")
    return dsn


@pytest.fixture(scope="session")
def cfg(admin_dsn: str, tmp_path_factory) -> Config:
    suffix = secrets.token_hex(4)
    owner, reader, dbname = f"pa_owner_{suffix}", f"pa_reader_{suffix}", f"pa_test_{suffix}"
    pw_owner, pw_reader = secrets.token_hex(8), secrets.token_hex(8)
    with psycopg.connect(admin_dsn, autocommit=True) as conn:
        conn.execute(sql.SQL("CREATE ROLE {} LOGIN PASSWORD {}").format(sql.Identifier(owner), sql.Literal(pw_owner)))
        conn.execute(sql.SQL("CREATE DATABASE {} OWNER {} ENCODING 'UTF8' TEMPLATE template0").format(sql.Identifier(dbname), sql.Identifier(owner)))
        conn.execute(sql.SQL("CREATE ROLE {} LOGIN PASSWORD {}").format(sql.Identifier(reader), sql.Literal(pw_reader)))
        conn.execute(sql.SQL("ALTER ROLE {} SET default_transaction_read_only = on").format(sql.Identifier(reader)))
        conn.execute(sql.SQL("ALTER ROLE {} SET statement_timeout = '15s'").format(sql.Identifier(reader)))
        conn.execute(sql.SQL("GRANT CONNECT ON DATABASE {} TO {}").format(sql.Identifier(dbname), sql.Identifier(reader)))
    tmp = tmp_path_factory.mktemp("archive")
    c = Config(
        db_url=_with(admin_dsn, owner, pw_owner, dbname),
        reader_url=_with(admin_dsn, reader, pw_reader, dbname),
        listen_host="127.0.0.1",
        listen_port=0,
        public_url="https://archive.example.keenetic.pro:8443",
        ingest_token="t" * 64,
        owner_password="верный-пароль-архива",
        blobs=tmp / "blobs",
        logs=tmp / "logs",
        icu_athlete="i12345",
        icu_key="key",
    )
    db.migrate(c)
    yield c
    with psycopg.connect(admin_dsn, autocommit=True) as conn:
        conn.execute(sql.SQL("DROP DATABASE IF EXISTS {} WITH (FORCE)").format(sql.Identifier(dbname)))
        conn.execute(sql.SQL("DROP ROLE IF EXISTS {}").format(sql.Identifier(reader)))
        conn.execute(sql.SQL("DROP ROLE IF EXISTS {}").format(sql.Identifier(owner)))
        conn.execute(sql.SQL("DROP ROLE IF EXISTS {}").format(sql.Identifier(f"pa_dela_{suffix}")))


@pytest.fixture(scope="session")
def dela_url(cfg: Config, admin_dsn: str) -> str:
    """Дела в той же тестовой базе: своя роль службы, миграции, виды в life.

    Роль сносит cfg в конце сессии (по тому же суффиксу)."""
    from pravka_dela import db as dela_db

    suffix = urlparse(cfg.db_url).path.rsplit("_", 1)[-1]
    role, pw = f"pa_dela_{suffix}", secrets.token_hex(8)
    dbname = urlparse(cfg.db_url).path.lstrip("/")
    with psycopg.connect(admin_dsn, autocommit=True) as conn:
        conn.execute(sql.SQL("CREATE ROLE {} LOGIN PASSWORD {}").format(sql.Identifier(role), sql.Literal(pw)))
        conn.execute(sql.SQL("GRANT CONNECT ON DATABASE {} TO {}").format(sql.Identifier(dbname), sql.Identifier(role)))
    dela_db.migrate(cfg.db_url, role, cfg.reader_role)
    return _with(admin_dsn, role, pw, dbname)


@pytest.fixture()
def conn(cfg: Config):
    with psycopg.connect(cfg.db_url, autocommit=True) as c:
        yield c


@pytest.fixture()
def clean(conn):
    """Чистый архив перед тестом: журнал, состояние, указатель, свежесть."""
    conn.execute("TRUNCATE core.events, core.records, core.said_index, core.sources, core.icu_seen, core.oauth_tokens, core.oauth_clients")
    return conn


@pytest.fixture()
def batch() -> dict:
    return json.loads((ROOT / "contract" / "batch.json").read_text(encoding="utf-8"))
