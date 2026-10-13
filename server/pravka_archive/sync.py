"""Обратная дорога: телефон и службы компа забирают то, что поменял кто-то другой.

Владелец, 13.10.2026: «Я хочу, чтобы правда была на сервере. А добавляю я спокойно из
телефона или из компьютера». До этого архив был зеркалом: еда жила в food.json
телефона, сюда шла копия, и правка с компа до Правки не дошла бы никогда. Теперь у
еды два окна — Правка и служба «Еда» на компе. Пишут обе в один журнал (POST /ingest:
телефон своим токеном, служба — узким, core.tokens), правда — core.records, а чужое
каждая забирает здесь (POST /sync).

Курсор — время приёма события сервером (received_at) с запасом OVERLAP: транзакции не
мгновенны, и событие, принятое раньше, может стать видимым позже соседнего. Следующий
курсор поэтому — «сейчас минус запас», а повтор записи безвреден: клиент кладёт тот же
снимок второй раз. Отдаётся не каждое событие, а последнее слово по записи (put с
данными или del). Записи, где последним писало само это устройство, не отдаются — они у
него и так (own=true отдаёт и их: телефону после переустановки).

Здесь же — проверка приёма пищи от службы (check_meal): виды life читают поля приёма с
приведением типов, и одно кривое число от чужого клиента сломало бы life.meals целиком.
Телефону верим как раньше: его формат — контракт batch.json, его держат тесты.

Диктовки компьютера (13.10.2026, Правка 5.0, этап 5: «история всех диктовок — с телефона и
с компьютера, одна на сервере»). Диктовка на ПК пишет каждый тейк теми же видами, что
телефон, — pravka.take (распознанное) и pravka.clean (чистка Сонетом), с "origin": "pc",
ключом «pc-<мс>» у обоих (так чистка находит свой тейк) и от устройства svc-diktovka.
Свой вид не заводили: life.dictations, life.cleanups, поиск по сказанному и разборы видят
диктовки компа сразу, без второй ветки в каждом виде. Чужой ключ служба не перепишет:
её ключи — только «pc-…», у телефона «<мс>-<отпечаток>» (check_dictation). Телефону свои
диктовки назад не нужны, поэтому /sync отдаёт по этим видам только записанное службами
(FROM_SERVICES) — и после переустановки телефона прежнее устройство не приедет к нему
тысячами чужих тейков. Формат — contract/diktovka.json.
"""

from __future__ import annotations

import datetime as dt
import hashlib
import re
import secrets
from typing import Any

import psycopg

OVERLAP = dt.timedelta(seconds=60)
# Какие виды отдаёт /sync телефону. Расширять — когда правдой сервера становится ещё
# что-то, что правят с двух сторон (силовые, зарядка) или что пишет комп (диктовки).
DICTATION_KINDS = frozenset({"pravka.take", "pravka.clean"})
SYNC_KINDS = frozenset({"food.meal"}) | DICTATION_KINDS
# Виды, которые /sync отдаёт только записанными службами компа: свои диктовки телефону
# назад не нужны, а «кроме своего устройства» после переустановки отдало бы ему всю
# прежнюю историю с прошлого устройства.
FROM_SERVICES = DICTATION_KINDS
# Что служба может получить токеном. Выдаёт `python -m pravka_archive token`.
SERVICE_KINDS = frozenset({"food.meal"}) | DICTATION_KINDS
SERVICE_DEVICE = "svc-{name}"
NAME_RE = re.compile(r"^[a-z][a-z0-9-]{1,30}$")

MEAL_KINDS = ("завтрак", "обед", "ужин", "перекус", "добавки")
SURE = ("точно", "примерно", "наугад")
# Ключи веществ — как у телефона (core/Micronutrients.kt) и life.norms.
MICRO = frozenset({"va", "vd", "ve", "vk", "vc", "b1", "b2", "b3", "b6", "b9", "b12",
                   "ca", "fe", "mg", "zn", "k", "na", "se", "i", "o3"})
ITEM_NUMS = ("grams", "kcal", "protein_g", "fat_g", "carbs_g", "fiber_g")
TOTAL_NUMS = ("kcal", "protein_g", "fat_g", "carbs_g", "fiber_g", "grams")
DAY_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


class SyncError(ValueError):
    """Запрос не годится: причина словами, клиент показывает её как есть."""


# ── Токены служб ────────────────────────────────────────────────────────


def _sha(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def issue(conn: psycopg.Connection, name: str, kinds: list[str]) -> str:
    """Новый токен службы (прежний с тем же именем перестаёт работать). Возвращает токен целиком —
    он виден один раз, в базе остаётся только sha256."""
    if not NAME_RE.match(name):
        raise SyncError("имя службы — латиницей, 2–31 знак: eda, sport")
    bad = [k for k in kinds if k not in SERVICE_KINDS]
    if not kinds or bad:
        raise SyncError(f"службе можно только {', '.join(sorted(SERVICE_KINDS))}; не бывает: {', '.join(bad) or 'пусто'}")
    token = secrets.token_urlsafe(32)
    conn.execute(
        "INSERT INTO core.tokens (name, kinds, sha256) VALUES (%s, %s, %s) "
        "ON CONFLICT (name) DO UPDATE SET kinds = EXCLUDED.kinds, sha256 = EXCLUDED.sha256, "
        "created_at = now(), revoked_at = NULL",
        (name, sorted(set(kinds)), _sha(token)),
    )
    return token


def revoke(conn: psycopg.Connection, name: str) -> bool:
    return bool(conn.execute(
        "UPDATE core.tokens SET revoked_at = now() WHERE name = %s AND revoked_at IS NULL", (name,)
    ).rowcount)


def service_of(conn: psycopg.Connection, token: str) -> tuple[str, frozenset[str]] | None:
    """Чей это токен службы: (имя, виды) или None."""
    if not token:
        return None
    row = conn.execute(
        "SELECT name, kinds FROM core.tokens WHERE sha256 = %s AND revoked_at IS NULL", (_sha(token),)
    ).fetchone()
    return (row[0], frozenset(row[1])) if row else None


def service_device(name: str) -> str:
    return SERVICE_DEVICE.format(name=name)


# ── Забрать чужое ───────────────────────────────────────────────────────


def _since(value: Any) -> dt.datetime | None:
    if value in (None, ""):
        return None
    if not isinstance(value, str):
        raise SyncError("since — время ISO 8601 с поясом из прошлого ответа (cursor) или пусто")
    try:
        at = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        raise SyncError("since — время ISO 8601 с поясом из прошлого ответа (cursor)") from None
    if at.tzinfo is None:
        raise SyncError("since — с поясом")
    return at


def pull(
    conn: psycopg.Connection,
    person: str,
    kinds: list[str],
    since: Any,
    device: str,
    own: bool = False,
) -> dict[str, Any]:
    """Записи видов kinds, поменявшиеся после since (или все живые, если since пуст), кроме
    тех, где последним писал device (own=true — и их). Виды FROM_SERVICES — только
    записанные службами компа (svc-*). Ответ — {cursor, records}."""
    after = _since(since)
    now = conn.execute("SELECT now()").fetchone()[0]
    svc_only = sorted(FROM_SERVICES)
    if after is None:
        rows = conn.execute(
            "SELECT kind, key, deleted, data, at, device FROM core.records "
            "WHERE person = %s AND kind = ANY(%s) AND NOT deleted AND (%s OR device <> %s) "
            "AND (NOT kind = ANY(%s) OR device LIKE 'svc-%%') "
            "ORDER BY kind, key",
            (person, kinds, own, device, svc_only),
        ).fetchall()
    else:
        rows = conn.execute(
            "SELECT r.kind, r.key, r.deleted, r.data, r.at, r.device FROM core.records r "
            "WHERE r.person = %s AND r.kind = ANY(%s) AND (%s OR r.device <> %s) "
            "AND (NOT r.kind = ANY(%s) OR r.device LIKE 'svc-%%') AND EXISTS ("
            " SELECT 1 FROM core.events e WHERE e.person = r.person AND e.kind = r.kind AND e.key = r.key"
            " AND e.received_at > %s) "
            "ORDER BY r.kind, r.key",
            (person, kinds, own, device, svc_only, after),
        ).fetchall()
    cursor = now - OVERLAP
    if after is not None and cursor < after:
        cursor = after
    records = []
    for kind, key, deleted, data, at, dev in rows:
        rec: dict[str, Any] = {"kind": kind, "key": key, "op": "del" if deleted else "put",
                               "at": at.isoformat(timespec="milliseconds"), "device": dev}
        if not deleted:
            rec["data"] = data
        records.append(rec)
    return {"ok": True, "cursor": cursor.isoformat(timespec="milliseconds"), "records": records}


# ── Приём пищи от службы ────────────────────────────────────────────────


def _num(v: Any, hi: float = 20000) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool) and 0 <= v <= hi


def _iso(v: Any) -> bool:
    if not isinstance(v, str):
        return False
    try:
        return dt.datetime.fromisoformat(v.replace("Z", "+00:00")).tzinfo is not None
    except ValueError:
        return False


def check_meal(key: str, data: Any) -> str | None:
    """Что не так с приёмом от службы — словами; None — годится. Формат — food.meal в
    contract/batch.json (так пишет телефон, так читают виды life)."""
    if not isinstance(data, dict):
        return "приём — объект"
    mid = data.get("id")
    if not isinstance(mid, int) or isinstance(mid, bool) or key != f"f{mid}":
        return "id — целое, а key — «f<id>»"
    if not isinstance(data.get("day"), str) or not DAY_RE.match(data["day"]):
        return "day — ГГГГ-ММ-ДД"
    if not _iso(data.get("at")):
        return "at — время ISO 8601 с поясом"
    if data.get("created") is not None and not _iso(data["created"]):
        return "created — время ISO 8601 с поясом"
    if data.get("kind") not in MEAL_KINDS:
        return f"kind — {', '.join(MEAL_KINDS)}"
    if not isinstance(data.get("confirmed"), bool):
        return "confirmed — да или нет"
    for f in ("source", "raw", "note", "photo", "model"):
        if data.get(f) is not None and (not isinstance(data[f], str) or len(data[f]) > 4000):
            return f"{f} — строка до 4000 знаков"
    items = data.get("items")
    if not isinstance(items, list) or not items or len(items) > 60:
        return "items — от 1 до 60 позиций"
    sums = dict.fromkeys(TOTAL_NUMS, 0.0)
    for n, it in enumerate(items, 1):
        if not isinstance(it, dict):
            return f"позиция {n} — объект"
        if not isinstance(it.get("name"), str) or not it["name"].strip() or len(it["name"]) > 300:
            return f"позиция {n}: name — непустая строка"
        for f in ITEM_NUMS:
            if not _num(it.get(f)):
                return f"позиция {n}: {f} — число от 0 до 20000"
            if f in sums:
                sums[f] += it[f]
        if it.get("sureness") not in SURE:
            return f"позиция {n}: sureness — {', '.join(SURE)}"
        if not isinstance(it.get("pill", False), bool):
            return f"позиция {n}: pill — да или нет"
        micro = it.get("micro", {})
        if not isinstance(micro, dict) or any(k not in MICRO or not _num(v, 100000) for k, v in micro.items()):
            return f"позиция {n}: micro — вещества из справочника с неотрицательными числами"
    totals = data.get("totals")
    if not isinstance(totals, dict) or not all(_num(totals.get(f), 100000) for f in TOTAL_NUMS):
        return "totals — числа kcal, protein_g, fat_g, carbs_g, fiber_g, grams"
    for f in TOTAL_NUMS:
        if abs(totals[f] - sums[f]) > 1.5:
            return f"totals.{f} не сходится с суммой позиций ({totals[f]} против {round(sums[f])})"
    return None


# ── Диктовка компьютера ─────────────────────────────────────────────────

PC_KEY_RE = re.compile(r"^pc-(\d{10,16})$")
MAX_TEXT = 100_000      # десять минут речи — около 10 тыс. знаков; запас вдесятеро
TAKE_STATUS = ("pasted", "clipboard", "cancelled", "empty", "error")


def _str(v: Any, hi: int) -> bool:
    return isinstance(v, str) and len(v) <= hi


def _int(v: Any, hi: int) -> bool:
    return isinstance(v, int) and not isinstance(v, bool) and 0 <= v <= hi


def check_dictation(kind: str, key: str, data: Any) -> str | None:
    """Что не так с диктовкой компа — словами; None — годится. Формат — contract/diktovka.json:
    поля те же, что у телефона (batch.json), плюс origin, а ключ — «pc-<мс>», чтобы служба
    не могла переписать телефонную диктовку."""
    m = PC_KEY_RE.match(key or "")
    if not m:
        return "key — «pc-<миллисекунды>»: диктовки компа живут отдельно от телефонных"
    if not isinstance(data, dict):
        return "диктовка — объект"
    if data.get("origin") != "pc":
        return "origin — «pc»"
    if not isinstance(data.get("id"), int) or isinstance(data.get("id"), bool) or str(data["id"]) != m.group(1):
        return "id — целое, а key — «pc-<id>»"
    if not isinstance(data.get("day"), str) or not DAY_RE.match(data["day"]):
        return "day — ГГГГ-ММ-ДД"
    if not _iso(data.get("at")):
        return "at — время ISO 8601 с поясом"
    if kind == "pravka.take":
        if not _str(data.get("text"), MAX_TEXT):
            return f"text — строка до {MAX_TEXT} знаков"
        if not _int(data.get("audio_ms"), 36_000_000):
            return "audio_ms — целое от 0 до 10 часов"
        if not isinstance(data.get("ok"), bool):
            return "ok — да или нет"
        if data.get("status") not in TAKE_STATUS:
            return f"status — {', '.join(TAKE_STATUS)}"
        for f in ("engine", "error", "audio", "mic"):
            if data.get(f) is not None and not _str(data[f], 4000):
                return f"{f} — строка до 4000 знаков"
        if data.get("final") is not None and not _str(data["final"], MAX_TEXT):
            return f"final — строка до {MAX_TEXT} знаков"
        return None
    if kind == "pravka.clean":
        if data.get("take") != key:
            return "take — ключ своего тейка (тот же «pc-<id>»)"
        for f in ("input", "output"):
            if not _str(data.get(f), MAX_TEXT):
                return f"{f} — строка до {MAX_TEXT} знаков"
        for f in ("mode", "model", "error", "style"):
            if data.get(f) is not None and not _str(data[f], 4000):
                return f"{f} — строка до 4000 знаков"
        if not _int(data.get("latency_ms"), 3_600_000):
            return "latency_ms — целое от 0 до часа"
        if not _num(data.get("cost_usd"), 100):
            return "cost_usd — число от 0 до 100"
        return None
    return f"вид {kind} — не диктовка"


def check_service(kind: str, key: str, data: Any) -> str | None:
    """Проверка записи от службы компа по её виду: приём пищи — check_meal, диктовка —
    check_dictation. Вид без проверки (удаление, например) — годится."""
    if kind == "food.meal":
        return check_meal(key, data)
    if kind in DICTATION_KINDS:
        return check_dictation(kind, key, data)
    return None
