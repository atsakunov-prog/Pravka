"""База Дел: миграции и подключение с контекстом пользователя.

Схемы crm и tasks живут в той же базе, что архив, и принадлежат той же роли
(владельцу базы): иначе ночной pg_dump ролью pravka споткнулся бы о чужие
таблицы. Служба ходит своей ролью (с завода dela_app) — у неё есть crm и
tasks и нет ни core, ни life. Миграции — файлы sql/dela_NNNN.sql, применённые
помнит общая core.migrations архива.
"""

from __future__ import annotations

import contextlib
from collections.abc import Iterator
from importlib import resources

import psycopg
from psycopg import sql
from psycopg.rows import dict_row

APP_TABLES = {
    # таблица: права роли службы (RLS решает, какие строки)
    "crm.users": "SELECT, INSERT, UPDATE",
    "crm.orgs": "SELECT, INSERT, UPDATE",
    "crm.people": "SELECT, INSERT, UPDATE",
    "crm.projects": "SELECT, INSERT, UPDATE",
    "crm.deals": "SELECT, INSERT, UPDATE",
    "crm.project_access": "SELECT, INSERT, UPDATE, DELETE",
    "crm.access_revoked": "SELECT",
    "crm.interactions": "SELECT, INSERT, UPDATE",
    "crm.payments": "SELECT, INSERT, UPDATE",
    "tasks.labels": "SELECT, INSERT",
    "tasks.tasks": "SELECT, INSERT, UPDATE",
    "tasks.comments": "SELECT, INSERT, UPDATE",
    "tasks.suggestions": "SELECT, INSERT, UPDATE",
    "crm.tokens": "SELECT, INSERT, UPDATE",
    "crm.sessions": "SELECT, INSERT, UPDATE, DELETE",
    "crm.login_requests": "SELECT, INSERT, UPDATE, DELETE",
    "crm.passkeys": "SELECT, INSERT, UPDATE, DELETE",
    "crm.ops_seen": "SELECT, INSERT, DELETE",
    "crm.state": "SELECT, INSERT, UPDATE",
    "crm.history": "SELECT",
}
APP_VIEWS = ["tasks.v_tasks", "crm.v_deals"]
APP_SEQUENCES = ["tasks.task_num"]


def _sql_files() -> list[tuple[str, str]]:
    root = resources.files(__package__) / "sql"
    return sorted((i.name, i.read_text(encoding="utf-8")) for i in root.iterdir() if i.name.endswith(".sql"))


def migrate(owner_url: str, app_role: str, reader_role: str | None = None) -> list[str]:
    """Применяет недостающие dela_*.sql, раздаёт права роли службы и виды для Claude.

    Идёт ролью владельца базы. Повтор безвреден.
    """
    done: list[str] = []
    files = dict(_sql_files())
    with psycopg.connect(owner_url, autocommit=True) as conn:
        conn.execute("CREATE SCHEMA IF NOT EXISTS core")
        conn.execute(
            "CREATE TABLE IF NOT EXISTS core.migrations ("
            " name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())"
        )
        if not conn.execute("SELECT 1 FROM pg_roles WHERE rolname = %s", (app_role,)).fetchone():
            raise RuntimeError(
                f"Роли {app_role} нет: её заводит установщик Дел (install-dela.ps1) от суперпользователя."
            )
        done += apply_pending(conn, files)
        with conn.transaction():
            grant_app(conn, app_role)
        if reader_role and life_views(conn, reader_role):
            done.append("life_dela.sql")
    return done


def apply_pending(conn: psycopg.Connection, files: dict[str, str] | None = None) -> list[str]:
    """Недостающие dela_NNNN.sql по порядку, каждая своей транзакцией. Роль — владелец базы."""
    files = files if files is not None else dict(_sql_files())
    applied = {r[0] for r in conn.execute("SELECT name FROM core.migrations")}
    done = []
    for name in sorted(n for n in files if n.startswith("dela_")):
        if name in applied:
            continue
        with conn.transaction():
            conn.execute(files[name])
            conn.execute("INSERT INTO core.migrations (name) VALUES (%s)", (name,))
        done.append(name)
    return done


def grant_app(conn: psycopg.Connection, role: str) -> None:
    r = sql.Identifier(role)
    conn.execute(sql.SQL("GRANT USAGE ON SCHEMA crm, tasks TO {}").format(r))
    for table, privs in APP_TABLES.items():
        conn.execute(sql.SQL("GRANT " + privs + " ON {} TO {}").format(sql.SQL(table), r))
    for view in APP_VIEWS:
        conn.execute(sql.SQL("GRANT SELECT ON {} TO {}").format(sql.SQL(view), r))
    for seq in APP_SEQUENCES:
        conn.execute(sql.SQL("GRANT USAGE ON SEQUENCE {} TO {}").format(sql.SQL(seq), r))


def life_views(conn: psycopg.Connection, reader_role: str) -> bool:
    """Виды Дел в схеме life для Claude. Архив пересобирает life целиком на
    своём migrate и зовёт эту же функцию; здесь — на случай, если первым
    прошёл migrate Дел. Нет схемы life или задач — молча ничего."""
    have = conn.execute(
        "SELECT count(*) FROM pg_namespace WHERE nspname IN ('life', 'tasks')"
    ).fetchone()[0]
    if have < 2:
        return False
    files = dict(_sql_files())
    # Виды life читают колонки новых миграций Дел, а migrate архива (он идёт
    # первым в установщике) пересобирает life раньше, чем migrate Дел: поэтому
    # недостающие миграции Дел — здесь же. Права роли службы раздаст migrate Дел.
    apply_pending(conn, files)
    with conn.transaction():
        conn.execute(files["life_dela.sql"])
        r = sql.Identifier(reader_role)
        if conn.execute("SELECT 1 FROM pg_roles WHERE rolname = %s", (reader_role,)).fetchone():
            conn.execute(sql.SQL("GRANT SELECT ON ALL TABLES IN SCHEMA life TO {}").format(r))
            conn.execute(sql.SQL("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA life TO {}").format(r))
    return True


@contextlib.contextmanager
def session(url: str, user: str, actor: str | None = None, via: str = "system") -> Iterator[psycopg.Connection]:
    """Одна транзакция от имени пользователя: RLS видит dela.user, журнал — actor и via.

    user — id пользователя или 'system'. actor — кто записан в журнал
    (с завода тот же пользователь; служба пишет 'svc:<имя>').
    """
    with psycopg.connect(url, row_factory=dict_row) as conn:
        with conn.transaction():
            conn.execute("SELECT set_config('dela.user', %s, true)", (user,))
            conn.execute("SELECT set_config('dela.actor', %s, true)", (actor or user,))
            conn.execute("SELECT set_config('dela.via', %s, true)", (via,))
            yield conn
