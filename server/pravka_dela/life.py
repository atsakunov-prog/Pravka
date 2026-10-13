"""Архив жизни для страниц-двойников веба Дел — узко, через crm.life_rows (dela_0016).

Роль службы Дел не видит схему life (правило 2): служба смотрит в интернет. Страницам «Сегодня», «Засечка» и
«Правка» нужна лента, еда, тренировки, деньги семьи, телефон по дням и диктовки — их отдаёт одна функция базы,
SECURITY DEFINER, только владельцу и только из своего списка видов и колонок (как crm.time_* правила 13). Здесь —
вызов этой функции и слова, когда архива нет: старый сервер, тестовая база без архива, архив старше страниц.
Страница от этого не падает — она говорит «нет доступа к архиву» и показывает то, что знают сами Дела.
"""

from __future__ import annotations

import datetime as dt
from typing import Any

import psycopg

SPAN_MAX = 62  # столько суток функция отдаёт за раз (dela_0016): длиннее — частями


class NoArchive(Exception):
    """Архив не читается: нет функции (сервер Дел старый), нет видов life, архив старше страниц."""


def available(conn) -> tuple[bool, str]:
    """Можно ли читать архив и, если нет, почему — словами для страницы."""
    try:
        with conn.transaction():
            ok = conn.execute("SELECT crm.life_ok() AS ok").fetchone()["ok"]
    except psycopg.errors.UndefinedFunction:
        return False, "нет доступа к архиву: сервер Дел ещё без чтения архива — его поставит install-dela.ps1"
    except psycopg.errors.InsufficientPrivilege:
        return False, "нет доступа к архиву: роли службы Дел не выдано право — его выдаёт install-dela.ps1"
    if not ok:
        return False, "нет доступа к архиву: в базе нет видов архива жизни (life) — архив не установлен или пересобирается"
    return True, ""


def rows(conn, what: str, d1: dt.date, d2: dt.date | None = None) -> list[dict[str, Any]]:
    """Строки вида what за сутки d1…d2 (по полю day архива). Длинный отрезок — частями по SPAN_MAX суток."""
    d2 = d2 or d1
    out: list[dict[str, Any]] = []
    a = d1
    while a <= d2:
        b = min(d2, a + dt.timedelta(days=SPAN_MAX))
        try:
            with conn.transaction():
                got = conn.execute("SELECT crm.life_rows(%s, %s, %s) AS r", (what, a, b)).fetchall()
        except psycopg.errors.UndefinedFunction as e:
            raise NoArchive("нет доступа к архиву: сервер Дел ещё без чтения архива") from e
        except psycopg.errors.InsufficientPrivilege as e:
            raise NoArchive("нет доступа к архиву: " + _primary(e)) from e
        except psycopg.errors.RaiseException as e:
            raise NoArchive(_primary(e)) from e
        out += [r["r"] for r in got]
        if what in ("categories", "freshness"):
            break  # у справочников нет суток: второй кусок дал бы те же строки
        a = b + dt.timedelta(days=1)
    return out


def _primary(e: psycopg.Error) -> str:
    return (e.diag.message_primary or str(e)).strip()
