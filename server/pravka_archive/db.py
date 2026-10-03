"""База: подключение и схема.

Схема core — таблицы, меняются только новыми файлами `sql/core_NNNN.sql`
(применённые помнит core.migrations). Схема life — виды, строится заново на
каждом `migrate` из `sql/life.sql`: виды можно менять как угодно, данные
от этого не меняются. После видов пересобирается указатель сказанного.
"""

from __future__ import annotations

from importlib import resources

import psycopg
from psycopg import sql

from .config import Config


def connect(url: str, autocommit: bool = False) -> psycopg.Connection:
    return psycopg.connect(url, autocommit=autocommit)


def _sql_files() -> list[tuple[str, str]]:
    root = resources.files(__package__) / "sql"
    out = []
    for item in root.iterdir():
        if item.name.endswith(".sql"):
            out.append((item.name, item.read_text(encoding="utf-8")))
    return sorted(out)


def migrate(cfg: Config) -> list[str]:
    """Применяет недостающие файлы core и пересобирает life. Возвращает, что сделано."""
    done: list[str] = []
    files = dict(_sql_files())
    with connect(cfg.db_url, autocommit=True) as conn:
        conn.execute("CREATE SCHEMA IF NOT EXISTS core")
        conn.execute(
            "CREATE TABLE IF NOT EXISTS core.migrations ("
            " name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())"
        )
        applied = {row[0] for row in conn.execute("SELECT name FROM core.migrations")}
        for name in sorted(n for n in files if n.startswith("core_")):
            if name in applied:
                continue
            with conn.transaction():
                conn.execute(files[name])
                conn.execute("INSERT INTO core.migrations (name) VALUES (%s)", (name,))
            done.append(name)

        reader = cfg.reader_role
        exists = conn.execute("SELECT 1 FROM pg_roles WHERE rolname = %s", (reader,)).fetchone()
        if not exists:
            raise RuntimeError(
                f"Роли {reader} нет: её заводит часть А server/SETUP-PC.md (шаг 4). Без неё инструменту sql нечем читать."
            )
        with conn.transaction():
            conn.execute(files["life.sql"])
            role = sql.Identifier(reader)
            conn.execute(sql.SQL("GRANT USAGE ON SCHEMA life TO {}").format(role))
            conn.execute(sql.SQL("GRANT SELECT ON ALL TABLES IN SCHEMA life TO {}").format(role))
            conn.execute(sql.SQL("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA life TO {}").format(role))
            conn.execute("TRUNCATE core.said_index")
            conn.execute(
                "INSERT INTO core.said_index (kind, key, part, domain, day, at, time_local, ref, text) "
                "SELECT kind, key, part, domain, day, at, time_local, ref, text FROM core.said_source"
            )
        done.append("life.sql")
        # life только что снесена целиком — вместе с видами Дел (life.tasks и
        # др.). Возвращаем их, если Дела уже стоят в этой базе.
        from pravka_dela import db as dela_db

        if dela_db.life_views(conn, reader):
            done.append("life_dela.sql")
    return done


def refresh_said(conn: psycopg.Connection, kind: str, key: str) -> None:
    """Указатель сказанного по одной записи: снять старые куски, положить новые."""
    conn.execute("DELETE FROM core.said_index WHERE kind = %s AND key = %s", (kind, key))
    conn.execute(
        "INSERT INTO core.said_index (kind, key, part, domain, day, at, time_local, ref, text) "
        "SELECT kind, key, part, domain, day, at, time_local, ref, text FROM core.said_source "
        "WHERE kind = %s AND key = %s",
        (kind, key),
    )


def mark_source(conn: psycopg.Connection, source: str, ok: bool, error: str | None = None, note: dict | None = None) -> None:
    from psycopg.types.json import Jsonb

    if ok:
        conn.execute(
            "INSERT INTO core.sources (source, last_ok, note) VALUES (%s, now(), %s) "
            "ON CONFLICT (source) DO UPDATE SET last_ok = now(), note = COALESCE(EXCLUDED.note, core.sources.note)",
            (source, Jsonb(note) if note is not None else None),
        )
    else:
        conn.execute(
            "INSERT INTO core.sources (source, last_error, last_error_at) VALUES (%s, %s, now()) "
            "ON CONFLICT (source) DO UPDATE SET last_error = EXCLUDED.last_error, last_error_at = now()",
            (source, (error or "")[:2000]),
        )
