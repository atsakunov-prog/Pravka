"""Приём событий: телефон шлёт пачки, сборщик intervals кладёт свои.

Событие — снимок одной записи целиком (`put`) или её удаление (`del`).
Журнал (core.events) только дописывается. Состояние (core.records) держит
последнюю версию по паре (at, seq). Тот же снимок, что уже лежит, нового
события не плодит: сборщик intervals опрашивает одно и то же каждые десять
минут, а журнал должен хранить изменения, а не опросы.

Ответ на каждое событие — квитанция: телефон убирает из очереди всё, что
получило `acked`, и не повторяет `rejected` (там формат, повтор не поможет),
а показывает причину словами.
"""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

import psycopg
from psycopg.types.json import Jsonb

from .db import mark_source, refresh_said

SCHEMA = 1
KIND_RE = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$")
DEVICE_RE = re.compile(r"^[a-z0-9][a-z0-9-]{2,63}$")
MAX_EVENTS = 5000
MAX_KEY = 300


def canonical(data: Any) -> str:
    return json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def digest(data: Any) -> str:
    return hashlib.sha256(canonical(data).encode("utf-8")).hexdigest()


def parse_at(value: Any) -> datetime | None:
    """Время ISO 8601 обязательно с поясом: без него сутки не восстановить."""
    if not isinstance(value, str):
        return None
    try:
        at = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return at if at.tzinfo is not None else None


@dataclass
class Result:
    acked: list[str] = field(default_factory=list)
    rejected: list[dict[str, str]] = field(default_factory=list)
    stored: int = 0      # новых событий в журнале
    changed: int = 0     # записей, чьё состояние поменялось

    def as_json(self) -> dict[str, Any]:
        return {"acked": self.acked, "rejected": self.rejected, "stored": self.stored, "changed": self.changed}


def check_event(ev: Any, device: str) -> str | None:
    """Что не так с событием — словами; None — всё в порядке."""
    if not isinstance(ev, dict):
        return "событие не объект"
    eid = ev.get("eid")
    if not isinstance(eid, str) or not eid.startswith(device + ":") or len(eid) > 200:
        return f"eid должен начинаться с «{device}:»"
    seq = ev.get("seq")
    if not isinstance(seq, int) or isinstance(seq, bool) or seq < 0:
        return "seq — целое неотрицательное"
    kind = ev.get("kind")
    if not isinstance(kind, str) or not KIND_RE.match(kind):
        return "kind вида «режим.запись» латиницей"
    key = ev.get("key")
    if not isinstance(key, str) or not key or len(key) > MAX_KEY:
        return f"key — непустая строка до {MAX_KEY} знаков"
    if ev.get("op") not in ("put", "del"):
        return "op — put или del"
    if parse_at(ev.get("at")) is None:
        return "at — время ISO 8601 с поясом"
    if ev.get("op") == "put" and not isinstance(ev.get("data"), dict):
        return "у put data — объект"
    return None


def store_events(
    conn: psycopg.Connection,
    device: str,
    events: list[dict[str, Any]],
    app: str | None = None,
    schema: int = SCHEMA,
) -> Result:
    """Кладёт события в журнал и состояние. Каждое — в своей точке сохранения:
    одно кривое не роняет пачку."""
    res = Result()
    for ev in events:
        why = check_event(ev, device)
        eid = ev.get("eid") if isinstance(ev, dict) else None
        if why:
            res.rejected.append({"eid": str(eid), "why": why})
            continue
        try:
            with conn.transaction():
                _store_one(conn, device, ev, app, schema, res)
            res.acked.append(eid)
        except psycopg.Error as e:  # pragma: no cover - видно в журнале службы
            res.rejected.append({"eid": eid, "why": f"база: {e.__class__.__name__}: {e}"[:500]})
    return res


def _store_one(conn: psycopg.Connection, device: str, ev: dict[str, Any], app: str | None, schema: int, res: Result) -> None:
    kind, key, op, seq = ev["kind"], ev["key"], ev["op"], ev["seq"]
    at = parse_at(ev["at"])
    data = ev.get("data") if op == "put" else None
    h = digest(data) if op == "put" else "del"

    cur = conn.execute(
        "SELECT hash, deleted, at, seq FROM core.records WHERE kind = %s AND key = %s FOR UPDATE",
        (kind, key),
    ).fetchone()
    if cur is not None:
        same = (op == "del" and cur[1]) or (op == "put" and not cur[1] and cur[0] == h)
        if same:
            return

    inserted = conn.execute(
        "INSERT INTO core.events (eid, device, seq, kind, key, op, at, schema, app, data) "
        "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s) ON CONFLICT (eid) DO NOTHING",
        (ev["eid"], device, seq, kind, key, op, at, schema, app, Jsonb(data) if data is not None else None),
    ).rowcount
    if not inserted:
        return
    res.stored += 1

    if cur is not None and (at, seq) <= (cur[2], cur[3]):
        return  # старше того, что уже лежит: в журнале есть, состояние не трогаем

    if op == "put":
        conn.execute(
            "INSERT INTO core.records (kind, key, deleted, data, hash, at, seq, eid, device, updated_at) "
            "VALUES (%s, %s, false, %s, %s, %s, %s, %s, %s, now()) "
            "ON CONFLICT (kind, key) DO UPDATE SET deleted = false, data = EXCLUDED.data, hash = EXCLUDED.hash, "
            "at = EXCLUDED.at, seq = EXCLUDED.seq, eid = EXCLUDED.eid, device = EXCLUDED.device, updated_at = now()",
            (kind, key, Jsonb(data), h, at, seq, ev["eid"], device),
        )
    else:
        # Удаление хранит последние известные данные: «что было» не теряется.
        conn.execute(
            "INSERT INTO core.records (kind, key, deleted, data, hash, at, seq, eid, device, updated_at) "
            "VALUES (%s, %s, true, '{}'::jsonb, 'del', %s, %s, %s, %s, now()) "
            "ON CONFLICT (kind, key) DO UPDATE SET deleted = true, hash = 'del', "
            "at = EXCLUDED.at, seq = EXCLUDED.seq, eid = EXCLUDED.eid, device = EXCLUDED.device, updated_at = now()",
            (kind, key, at, seq, ev["eid"], device),
        )
    res.changed += 1
    refresh_said(conn, kind, key)


def ingest_batch(conn: psycopg.Connection, batch: Any, owner_profile: str) -> tuple[int, dict[str, Any]]:
    """Пачка с телефона целиком: (HTTP-код, ответ)."""
    if not isinstance(batch, dict):
        return 400, {"error": "тело — объект JSON"}
    device = batch.get("device")
    if not isinstance(device, str) or not DEVICE_RE.match(device) or device == "intervals":
        return 400, {"error": "device — имя устройства латиницей, например sasha-3f9a2c"}
    profile = batch.get("profile")
    if profile != owner_profile:
        # Архив одного человека: чужой профиль перетёр бы его сутки своими.
        return 403, {"error": f"архив принимает только профиль «{owner_profile}», пришёл «{profile}»"}
    schema = batch.get("schema")
    if schema != SCHEMA:
        return 400, {"error": f"контракт версии {schema} сервер не знает, он знает {SCHEMA}"}
    events = batch.get("events")
    if not isinstance(events, list) or len(events) > MAX_EVENTS:
        return 400, {"error": f"events — список до {MAX_EVENTS} событий"}
    app = batch.get("app") if isinstance(batch.get("app"), str) else None
    with conn.transaction():
        res = store_events(conn, device, events, app=app, schema=SCHEMA)
        mark_source(conn, f"phone:{device}", ok=True, note={"app": app, "profile": profile, "last_batch": len(events)})
    return 200, {"ok": True, **res.as_json()}
