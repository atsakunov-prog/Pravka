"""Модели Claude в Делах, их цены и учёт трат — общее для правки словами (ask.py) и разбора (parse.py).

Траты копятся в crm.state 'llm_cost' (всего, по дням, по видам и моделям); писать туда может только
служебный пользователь, поэтому учёт ходит своей сессией. Цены — C:\\Bot\\README.md §5.1.
"""

from __future__ import annotations

import logging
from datetime import timedelta

from psycopg.types.json import Jsonb

from . import db

log = logging.getLogger("dela.llm")

# $ за 1 млн токенов: вход, выход, запись кэша (5 мин), чтение кэша.
PRICES = {
    "claude-sonnet-5-5": {"in": 2.0, "out": 10.0, "cache_write": 2.5, "cache_read": 0.20},
    "claude-opus-5-5": {"in": 4.0, "out": 20.0, "cache_write": 5.0, "cache_read": 0.20},
}
# Выбор в настройках веба («Настройки» → Claude): ключ настройки → модель.
MODELS = {"sonnet": "claude-sonnet-5-5", "opus": "claude-opus-5-5"}
EFFORTS = ("low", "medium", "high")


def cost(usage: dict | None, model: str | None = None) -> float:
    """Цена ответа по его usage; модель — та, что ответила (после отказа бывает резервная)."""
    if not usage:
        return 0.0
    p = PRICES.get(usage.get("model") or model or "", PRICES["claude-opus-5-5"])
    return (usage.get("input", 0) * p["in"] + usage.get("output", 0) * p["out"]
            + usage.get("cache_write", 0) * p["cache_write"] + usage.get("cache_read", 0) * p["cache_read"]) / 1e6


def usage_of(msg, model: str) -> dict:
    u = msg.usage
    return {"model": getattr(msg, "model", None) or model, "input": u.input_tokens, "output": u.output_tokens,
            "cache_write": getattr(u, "cache_creation_input_tokens", 0) or 0,
            "cache_read": getattr(u, "cache_read_input_tokens", 0) or 0}


def account(url: str, what: str, usd: float, model: str | None = None) -> None:
    """Траты в crm.state 'llm_cost': total, days (последние 120), by (вид), models. Сбой учёта правку не роняет."""
    if usd <= 0:
        return
    try:
        with db.session(url, "system", via="claude") as conn:
            day = conn.execute("SELECT crm.today()::text AS d").fetchone()["d"]
            row = conn.execute("SELECT value FROM crm.state WHERE key = 'llm_cost' FOR UPDATE").fetchone()
            v = dict(row["value"]) if row else {}
            days, by, models = v.setdefault("days", {}), v.setdefault("by", {}), v.setdefault("models", {})
            v["total"] = round(v.get("total", 0) + usd, 4)
            days[day] = round(days.get(day, 0) + usd, 4)
            by[what] = round(by.get(what, 0) + usd, 4)
            if model:
                models[model] = round(models.get(model, 0) + usd, 4)
            v["days"] = dict(sorted(days.items())[-120:])
            conn.execute("INSERT INTO crm.state (key, value) VALUES ('llm_cost', %s) "
                         "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value", (Jsonb(v),))
    except Exception:  # noqa: BLE001
        log.exception("учёт трат Claude")


def spent(url: str) -> dict:
    """Траты для «Настроек»: сегодня, 30 дней, всего, по видам и моделям."""
    with db.session(url, "system", via="claude") as conn:
        today = conn.execute("SELECT crm.today() AS d").fetchone()["d"]
        row = conn.execute("SELECT value FROM crm.state WHERE key = 'llm_cost'").fetchone()
    v = dict(row["value"]) if row else {}
    days = v.get("days", {})
    since = (today - timedelta(days=29)).isoformat()
    return {"today": days.get(today.isoformat(), 0), "month": round(sum(x for d, x in days.items() if d >= since), 4),
            "total": v.get("total", 0), "by": v.get("by", {}), "models": v.get("models", {})}
