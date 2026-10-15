"""Одна книга трат Claude (14.10.2026, dela_0017): день · служба · задача · модель · источник — вызовы, токены, $.

Владелец: «значок денег Claude (все запросы в Claude) на главный экран справа вверху… проаудировать, что мы всё
учитываем, и систематизировать лучше по задачам… раз в неделю сам Claude пускай предлагает варианты экономии». До
этого у каждой службы была своя книга и свой разрез (C:\\Bot\\README.md §5.5), а сложить их можно было только
руками. Здесь они сходятся в одну таблицу crm.claude_spend, а экран трат (телефон, веб Дел) и недельный советник
(spend_advice.py) читают только её.

Кто приносит — и почему нет двойного счёта:
- Дела — каждым своим вызовом (llm.account → add): прибавка к строке дня, src «dela»;
- телефон — свои траты по дорогам (Stats) операцией spend.put, src — имя телефона;
- службы компа — сборщик «Дома» (токен dom, от SYSTEM читает их книги), spend.put, src «pc».
spend.put ЗАМЕНЯЕТ строку дня целиком: источник шлёт итог дня из своей книги, а не прибавку, — повтор и пересчёт
безвредны. У строки ровно один источник (src в ключе), поэтому телефон и сборщик не могут сложиться дважды.

Кто что может:
- телефон и веб человека (не служба) пишут только службу phone — свои траты;
- сборщик «Дома» (svc:dom) — любую службу компа, кроме phone и dela (их приносят сами);
- другая служба со своим токеном (svc:<имя>) — только свою службу <имя>: так позже любая служба сможет приносить
  траты сама, без сборщика;
- читает книгу (вид spend, поле spend синка) и открывает советы (spend.seen) только владелец Дел.

Здесь только числа: ни промптов, ни ответов, ни имён — журнала у книги нет сознательно (как у дайджеста).
"""

from __future__ import annotations

import datetime as dt
import json
import logging
from decimal import Decimal
from functools import lru_cache
from pathlib import Path
from typing import Any

from psycopg.types.json import Jsonb

from . import db

log = logging.getLogger("dela.spend")

FLAG = "spend"
PHONE = "phone"
SELF = "dela"
SELF_KEY = "1"           # Дела платят ключом №1 (копия в dela.env)
COLLECTOR = "dom"        # сборщик «Дома»: python -m pravka_dela token --kind service --name dom
COLLECTOR_SRC = "pc"
ROWS_MAX = 3000          # за раз: 120 дней × ~25 задач компа — с запасом
DAYS_BACK = 120          # глубже книга не принимает: столько же хранит llm_cost Дел и больше не нужно экрану
NUMS = ("calls", "batch_calls", "input", "output", "cache_write", "cache_write_1h", "cache_read")
TOKENS = ("input", "output", "cache_write", "cache_write_1h", "cache_read")
USD_ROW_MAX = 1000.0     # больше тысячи долларов в день на одну задачу — сбой источника, а не трата
TREND_DAYS = 30
CONTRACT = Path(__file__).resolve().parents[1] / "contract" / "dela-spend.json"


class SpendError(Exception):
    pass


@lru_cache(maxsize=1)
def contract() -> dict:
    """Контракт части «Траты Claude»: подписи служб и задач, что учитывается и что нет. Файла нет — пусто (подписи
    будут ключами): экран не должен падать из-за документа."""
    try:
        return json.loads(CONTRACT.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        log.warning("контракт трат Claude не прочитался: %s", CONTRACT)
        return {}


def catalog() -> dict:
    return contract().get("catalog") or {}


def service_title(service: str) -> str:
    return (catalog().get(service) or {}).get("title") or service


def task_info(service: str, task: str) -> dict:
    return ((catalog().get(service) or {}).get("tasks") or {}).get(task) or {}


def task_title(service: str, task: str) -> str:
    return task_info(service, task).get("title") or task


def service_key(service: str) -> str:
    return (catalog().get(service) or {}).get("key") or ""


# ── Запись ──────────────────────────────────────────────────────────────

def _num(v: Any, what: str, err, integer: bool = True) -> int | float:
    if v in (None, ""):
        return 0
    if isinstance(v, bool):
        raise err(f"траты: {what} — число")
    try:
        x = float(v)
    except (TypeError, ValueError):
        raise err(f"траты: {what} — число") from None
    if x != x or x in (float("inf"), float("-inf")):
        raise err(f"траты: {what} — число")
    if x < 0:
        raise err(f"траты: {what} — не меньше нуля")
    if integer:
        if x != int(x):
            raise err(f"траты: {what} — целое число")
        return int(x)
    return x


def _day(v: Any, today: dt.date, err) -> dt.date:
    try:
        d = dt.date.fromisoformat(str(v or "").strip()[:10])
    except ValueError:
        raise err("траты: day — дата «2026-10-14»") from None
    if d > today + dt.timedelta(days=1):
        raise err(f"траты: {d} — день из будущего")
    if d < today - dt.timedelta(days=DAYS_BACK):
        raise err(f"траты: {d} — старше {DAYS_BACK} дней, книга их не берёт")
    return d


def _ident(v: Any, what: str, err, n: int) -> str:
    s = str(v or "").strip().lower()
    ok = s and len(s) <= n and s[0].isalnum() and s.isascii() and all(c.isalnum() or c in "_.-" for c in s)
    if not ok:
        raise err(f"траты: {what} — латиница, цифры, «_.-», до {n} знаков («{str(v)[:40]}»)")
    return s


def norm_row(r: Any, today: dt.date, err) -> dict:
    """Строка от источника: проверка и приведение. Неизвестные поля — отказ: молча потерянное поле выглядело бы учтённым."""
    if not isinstance(r, dict):
        raise err("траты: строка — объект")
    extra = set(r) - {"day", "service", "task", "model", "key", *NUMS, "usd", "usd_list", "exact"}
    if extra:
        raise err(f"траты: нет таких полей — {', '.join(sorted(extra))}")
    service = _ident(r.get("service"), "service", err, 32)
    if not service[0].isalpha() or "." in service or "-" in service:
        raise err("траты: service — латиница, цифры и «_»")
    row = {"day": _day(r.get("day"), today, err), "service": service,
           "task": _ident(r.get("task"), "task", err, 64),
           "model": str(r.get("model") or "").strip()[:64],
           "key": str(r.get("key") or service_key(service)).strip()[:16]}
    for k in NUMS:
        row[k] = int(_num(r.get(k), k, err))
    row["batch_calls"] = min(row["batch_calls"], row["calls"]) if row["calls"] else row["batch_calls"]
    row["usd"] = float(_num(r.get("usd"), "usd", err, integer=False))
    row["usd_list"] = None if r.get("usd_list") in (None, "") else float(_num(r.get("usd_list"), "usd_list", err, integer=False))
    if row["usd"] > USD_ROW_MAX or (row["usd_list"] or 0) > 2 * USD_ROW_MAX:
        raise err(f"траты: {row['usd']} $ за день на одну задачу — похоже на сбой источника")
    if row["usd_list"] is not None and row["usd_list"] + 1e-9 < row["usd"]:
        raise err("траты: usd_list — цена без скидки пачки, она не меньше usd")
    exact = r.get("exact")
    row["exact"] = bool(any(row[k] for k in TOKENS)) if exact is None else bool(exact)
    return row


COLS = ("owner_id", "day", "service", "task", "model", "src", "key", *NUMS, "usd", "usd_list", "exact")
KEY_COLS = ("owner_id", "day", "service", "task", "model", "src")


def _upsert(conn, row: dict, add: bool) -> bool:
    """Строка в книгу: add — прибавить (Дела сами), иначе заменить (итог дня от источника). True — что-то поменялось."""
    vals = [row.get(c) for c in COLS]
    keep = [c for c in COLS if c not in KEY_COLS]
    if add:
        sets = ", ".join(f"{c} = crm.claude_spend.{c} + EXCLUDED.{c}" for c in (*NUMS, "usd"))
        sets += (", usd_list = coalesce(crm.claude_spend.usd_list, crm.claude_spend.usd) + coalesce(EXCLUDED.usd_list, EXCLUDED.usd)"
                 ", exact = crm.claude_spend.exact AND EXCLUDED.exact, key = EXCLUDED.key")
    else:
        sets = ", ".join(f"{c} = EXCLUDED.{c}" for c in keep)
    got = conn.execute(
        f"INSERT INTO crm.claude_spend ({', '.join(COLS)}) VALUES ({', '.join(['%s'] * len(COLS))}) "
        f"ON CONFLICT ({', '.join(KEY_COLS)}) DO UPDATE SET {sets} RETURNING updated_at = now() AS changed", vals).fetchone()
    # crm.stamp двигает updated_at только у поменявшейся строки, а now() — время этой транзакции: так видно,
    # привёз ли повтор что-то новое (новая строка — тоже now()).
    return bool(got["changed"])


def _who_writes(actor: str, err) -> tuple[Any, str | None]:
    """Какие службы может писать этот вход и с каким src по умолчанию."""
    if actor.startswith("svc:"):
        name = actor[4:]
        if name == COLLECTOR:
            return (lambda s: s not in (PHONE, SELF)), COLLECTOR_SRC
        return (lambda s: s == name), name
    return (lambda s: s == PHONE), None


def op_put(conn, user: str, op: dict, err) -> dict:
    """Итоги дней от источника: телефон — свои дороги, сборщик «Дома» — службы компа. Заменяет строки целиком."""
    actor = conn.execute("SELECT crm.actor() AS a").fetchone()["a"] or user
    allowed, src_default = _who_writes(actor, err)
    src = " ".join(str(op.get("src") or "").split())[:64] or src_default
    if not src:
        raise err("траты: нужен src — имя телефона")
    rows = op.get("rows")
    if not isinstance(rows, list):
        raise err("траты: rows — список строк")
    if len(rows) > ROWS_MAX:
        raise err(f"траты: за раз не больше {ROWS_MAX} строк — дели на части")
    today = conn.execute("SELECT crm.today() AS d").fetchone()["d"]
    clean = [norm_row(r, today, err) for r in rows]
    bad = sorted({r["service"] for r in clean if not allowed(r["service"])})
    if bad:
        who = "сборщику «Дома»" if actor == f"svc:{COLLECTOR}" else (f"службе {actor[4:]}" if actor.startswith("svc:") else "телефону")
        raise err(f"траты: {who} нельзя писать службу {', '.join(bad)}")
    seen: set[tuple] = set()
    changed = 0
    for r in clean:
        k = (r["day"], r["service"], r["task"], r["model"])
        if k in seen:
            raise err(f"траты: строка {r['day']} · {r['service']} · {r['task']} · {r['model'] or '—'} дважды в одной присылке")
        seen.add(k)
        changed += _upsert(conn, {**r, "owner_id": user, "src": src}, add=False)
    if changed:
        watch(conn)  # сумма дня могла перейти порог (телефон, службы компа)
    return {"put": len(clean), "changed": changed, "src": src}


def add(url: str, task: str, usd: float, model: str | None, usage: dict | None, batch: bool, calls: int = 1,
        service: str = SELF, key: str = SELF_KEY) -> None:
    """Вызов самих Дел — прибавкой к строке дня (llm.account). Пишет служебный вход: строка — владельцу Дел."""
    u = usage or {}
    row = {"service": service, "task": task, "model": (model or u.get("model") or "")[:64], "src": SELF, "key": key,
           "calls": max(0, int(calls or 0)), "batch_calls": max(0, int(calls or 0)) if batch else 0,
           **{k: int(u.get(k) or 0) for k in TOKENS},
           "usd": round(float(usd), 6), "usd_list": round(float(usd) * (2 if batch else 1), 6),
           "exact": bool(usage)}
    with db.session(url, "system", "svc:spend", "claude") as conn:
        owner = conn.execute("SELECT crm.owner_id() AS id, crm.today() AS d").fetchone()
        if not owner["id"]:
            return
        _upsert(conn, {**row, "owner_id": owner["id"], "day": owner["d"]}, add=True)
        watch(conn)


def watch(conn) -> list[dict]:
    """Сторож суммы дня (аудит №12): после каждой строки книги — суммы сегодняшнего дня всего, по службе и по
    «служба.задача» против Свода claude.caps.day; перешли порог — сообщение владельцу ботом Ковчега (один раз на день и
    порог). Только уведомление: остановку делает рубильник (guard), а сумма дня бывает плановой. crm.state пишет только
    system — на время сторожа пользователь сессии он, потом прежний. Сбой сторожа строку книги не роняет."""
    from . import guard

    me = conn.execute("SELECT current_setting('dela.user', true) AS u").fetchone()["u"]
    try:
        with conn.transaction():
            conn.execute("SELECT set_config('dela.user', 'system', true)")
            return guard.watch_day(conn)
    except Exception:  # noqa: BLE001
        log.exception("сторож суммы дня")
        return []
    finally:
        conn.execute("SELECT set_config('dela.user', %s, true)", (me or "",))


# ── Чтение ──────────────────────────────────────────────────────────────

def is_owner(conn, user: str) -> bool:
    row = conn.execute("SELECT role FROM crm.users WHERE id = %s", (user,)).fetchone()
    return bool(row) and row["role"] == "owner"


def _f(v: Any) -> float:
    return float(v) if isinstance(v, Decimal) else float(v or 0)


def _prices(conn) -> dict:
    """Цены из Свода (claude.prices) прямо этой сессией, поверх запасной таблицы llm.PRICES."""
    from . import llm

    row = conn.execute("SELECT value FROM crm.svod WHERE key = 'claude.prices' AND value IS NOT NULL "
                       "ORDER BY updated_at DESC LIMIT 1").fetchone()
    return {**llm.PRICES, **llm.parse_prices(row["value"] if row else None)}


def savings(row: dict, table: dict) -> tuple[float, float]:
    """(сэкономил кэш, сэкономила пачка) по строке, $. Кэш: те же токены по цене входа без кэша минус как вышло
    (запись дороже входа — бывает в минус: кэш пишется, а не читается). Пачка: цена без скидки минус заплачено."""
    from . import llm

    batch = max(0.0, _f(row.get("usd_list")) - _f(row.get("usd"))) if row.get("usd_list") is not None else 0.0
    if not row.get("exact"):
        return 0.0, batch
    p = llm.price_of(table, row.get("model"))
    cw, cw1, cr = int(row.get("cache_write") or 0), int(row.get("cache_write_1h") or 0), int(row.get("cache_read") or 0)
    if not (cw or cw1 or cr):
        return 0.0, batch
    plain = (cw + cw1 + cr) * p["in"]
    real = cw * p["cache_write"] + cw1 * p.get("cache_write_1h", 2 * p["in"]) + cr * p["cache_read"]
    usd, lst = _f(row.get("usd")), _f(row.get("usd_list"))
    share = usd / lst if row.get("usd_list") is not None and lst > 0 else 1.0  # пачка делит и выгоду кэша пополам
    return (plain - real) / 1e6 * share, batch


def _group(rows: list[dict], key, table: dict) -> list[dict]:
    out: dict[Any, dict] = {}
    for r in rows:
        k = key(r)
        g = out.setdefault(k, {"usd": 0.0, "calls": 0, "batch_calls": 0, "cache_saved": 0.0, "batch_saved": 0.0,
                                "input": 0, "output": 0, "cache_write": 0, "cache_read": 0, "models": {}, "exact": True})
        g["usd"] += _f(r["usd"])
        g["calls"] += int(r["calls"] or 0)
        g["batch_calls"] += int(r["batch_calls"] or 0)
        for t in ("input", "output", "cache_read"):
            g[t] += int(r[t] or 0)
        g["cache_write"] += int(r["cache_write"] or 0) + int(r["cache_write_1h"] or 0)
        cs, bs = savings(r, table)
        g["cache_saved"] += cs
        g["batch_saved"] += bs
        g["exact"] = g["exact"] and bool(r["exact"])
        if r["model"]:
            g["models"][r["model"]] = g["models"].get(r["model"], 0.0) + _f(r["usd"])
    res = []
    for k, g in out.items():
        g["models"] = [m for m, _ in sorted(g["models"].items(), key=lambda x: -x[1])]
        for f in ("usd", "cache_saved", "batch_saved"):
            g[f] = round(g[f], 4)
        res.append({"k": k, **g})
    return sorted(res, key=lambda g: -g["usd"])


# Два явных блока на экране трат (владелец 14.10.2026: «сбор в одном месте, плюс там отдельно должны быть серверные
# траты»): «Телефон» — дороги Правки, «Сервер» — всё, что зовёт Claude на компе (Дела, Встречи, дайджест, Деньги…).
BLOCKS = (("phone", "Телефон"), ("server", "Сервер"))


def block_of(service: str) -> str:
    return "phone" if service == PHONE else "server"


def _period(rows: list[dict], table: dict) -> dict:
    """Срез за период: общий итог и два блока — «Телефон» и «Сервер», у каждого свои службы и задачи."""
    out = _slice(rows, table)
    out["blocks"] = [{"block": b, "title": t, **_slice([r for r in rows if block_of(r["service"]) == b], table)}
                     for b, t in BLOCKS]
    return out


def _slice(rows: list[dict], table: dict) -> dict:
    """Срез строк: всего, по службам, задачам и моделям (самые дорогие сверху), кэш и пачки."""
    services = []
    for g in _group(rows, lambda r: r["service"], table):
        s = g.pop("k")
        services.append({"service": s, "title": service_title(s), "key": service_key(s), **g})
    tasks = []
    for g in _group(rows, lambda r: (r["service"], r["task"]), table):
        s, t = g.pop("k")
        info = task_info(s, t)
        tasks.append({"service": s, "task": t, "title": task_title(s, t), "service_title": service_title(s),
                      "how": info.get("how") or "", **g})
    models = []
    for g in _group(rows, lambda r: r["model"] or "", table):
        m = g.pop("k")
        g.pop("models", None)
        models.append({"model": m, **g})
    total = sum(_f(r["usd"]) for r in rows)
    sav = [savings(r, table) for r in rows]
    return {"usd": round(total, 4), "calls": sum(int(r["calls"] or 0) for r in rows),
            "batch_calls": sum(int(r["batch_calls"] or 0) for r in rows),
            "cache_read": sum(int(r["cache_read"] or 0) for r in rows),
            "cache_write": sum(int(r["cache_write"] or 0) + int(r["cache_write_1h"] or 0) for r in rows),
            "cache_saved": round(sum(c for c, _ in sav), 4), "batch_saved": round(sum(b for _, b in sav), 4),
            "inexact_usd": round(sum(_f(r["usd"]) for r in rows if not r["exact"]), 4),
            "services": services, "tasks": tasks, "models": models}


def _rows(conn, since: dt.date) -> list[dict]:
    return conn.execute("SELECT * FROM crm.claude_spend WHERE day >= %s", (since,)).fetchall()


def last_tips(conn, user: str) -> dict | None:
    return conn.execute("SELECT id, week, tips, note, week_usd, model, usd, seen_at, created_at "
                        "FROM crm.claude_tips WHERE owner_id = %s ORDER BY week DESC LIMIT 1", (user,)).fetchone()


def badge(conn, user: str) -> dict | None:
    """Поле spend синка — для значка на «Сегодня»: $ за 7 дней и сегодня, есть ли непрочитанные советы. Только
    владельцу; не владельцу — None (поля нет). Маленькое и считается на каждом синке: строк за неделю — десятки."""
    if not is_owner(conn, user):
        return None
    today = conn.execute("SELECT crm.today() AS d").fetchone()["d"]
    got = conn.execute(
        "SELECT coalesce(sum(usd), 0) AS week, coalesce(sum(usd) FILTER (WHERE day = %s), 0) AS today, "
        "coalesce(sum(usd) FILTER (WHERE service = %s), 0) AS phone "
        "FROM crm.claude_spend WHERE day > %s", (today, PHONE, today - dt.timedelta(days=7))).fetchone()
    tips = last_tips(conn, user)
    week = round(_f(got["week"]), 4)
    return {"week": week, "today": round(_f(got["today"]), 4),
            "week_phone": round(_f(got["phone"]), 4), "week_server": round(week - _f(got["phone"]), 4),
            "tips": ({"id": tips["id"], "week": tips["week"], "count": len(tips["tips"] or []),
                      "unseen": tips["seen_at"] is None} if tips else None)}


def view_spend(conn, user: str, err=None, **_) -> dict:
    """Вид spend — экран «Claude: траты»: сегодня, 7 и 30 дней (по службам, задачам, моделям, кэш и пачки), тренд по
    дням, источники (когда приносили), что не учитывается, последние советы. Только владельцу."""
    if not is_owner(conn, user):
        raise err("траты Claude — только владельцу Дел")
    today = conn.execute("SELECT crm.today() AS d").fetchone()["d"]
    table = _prices(conn)
    rows = _rows(conn, today - dt.timedelta(days=TREND_DAYS + 7 - 1))
    in_days = lambda n: [r for r in rows if r["day"] > today - dt.timedelta(days=n)]  # noqa: E731
    prev = [r for r in rows if today - dt.timedelta(days=14) < r["day"] <= today - dt.timedelta(days=7)]
    trend: dict[dt.date, float] = {today - dt.timedelta(days=i): 0.0 for i in range(TREND_DAYS)}
    for r in rows:
        if r["day"] in trend:
            trend[r["day"]] += _f(r["usd"])
    src = conn.execute("SELECT src, array_agg(DISTINCT service) AS services, max(updated_at) AS at, max(day) AS day "
                       "FROM crm.claude_spend GROUP BY src ORDER BY max(updated_at) DESC").fetchall()
    tips = last_tips(conn, user)
    c = contract()
    return {
        "today": today,
        "periods": {"today": _period(in_days(1), table), "week": _period(in_days(7), table),
                    "month": _period(in_days(TREND_DAYS), table)},
        "prev_week": round(sum(_f(r["usd"]) for r in prev), 4),
        "trend": [{"day": d, "usd": round(v, 4)} for d, v in sorted(trend.items())],
        "sources": [{"src": s["src"], "services": sorted(s["services"]),
                     "titles": [service_title(x) for x in sorted(s["services"])], "at": s["at"], "day": s["day"]}
                    for s in src],
        "gaps": c.get("gaps") or [],
        "tips": tips,
        # Защита от петли (аудит №12): что стоит, почему, с какого времени, послабления — полоса «остановлено» с
        # «Продолжать» (операция claude.guard).
        "guard": _guard(conn),
    }


def _guard(conn) -> dict:
    from . import guard

    me = conn.execute("SELECT current_setting('dela.user', true) AS u").fetchone()["u"]
    conn.execute("SELECT set_config('dela.user', 'system', true)")
    try:
        return guard.status_of(conn)
    finally:
        conn.execute("SELECT set_config('dela.user', %s, true)", (me or "",))


def op_seen(conn, user: str, op: dict, err) -> dict:
    """Владелец открыл советы — красная точка гаснет. id — какие (с завода последние)."""
    if not is_owner(conn, user):
        raise err("советы по тратам — только владельцу Дел")
    tid = op.get("id")
    if tid:
        row = conn.execute("UPDATE crm.claude_tips SET seen_at = coalesce(seen_at, now()) WHERE owner_id = %s AND id = %s::uuid "
                           "RETURNING id, seen_at", (user, str(tid))).fetchone()
    else:
        row = conn.execute("UPDATE crm.claude_tips SET seen_at = coalesce(seen_at, now()) WHERE id = "
                           "(SELECT id FROM crm.claude_tips WHERE owner_id = %s ORDER BY week DESC LIMIT 1) "
                           "RETURNING id, seen_at", (user,)).fetchone()
    if not row:
        raise err("нет таких советов")
    return {"tips": row}


def register(handlers: dict, views: dict, err) -> None:
    handlers["spend.put"] = lambda conn, user, op: op_put(conn, user, op, err)
    handlers["spend.seen"] = lambda conn, user, op: op_seen(conn, user, op, err)
    views["spend"] = lambda conn, user, **p: view_spend(conn, user, err=err, **p)


BACKFILL_KEY = "spend_backfill"


def backfill(url: str) -> int:
    """Один раз (при первом запуске с книгой): прежний учёт Дел (crm.state llm_cost, суммы по дням без разреза) —
    строками «до книги» (dela · before). Сегодняшняя сумма тоже: книга с этого мига прибавляет новое к своим строкам,
    а то, что было до запуска, лежит в before — двойного счёта нет. Повтор ничего не делает (отметка в crm.state)."""
    with db.session(url, "system", "svc:spend", "claude") as conn:
        if conn.execute("SELECT 1 FROM crm.state WHERE key = %s", (BACKFILL_KEY,)).fetchone():
            return 0
        owner = conn.execute("SELECT crm.owner_id() AS id, crm.today() AS d").fetchone()
        row = conn.execute("SELECT value FROM crm.state WHERE key = 'llm_cost'").fetchone()
        days = ((row or {}).get("value") or {}).get("days") or {}
        n = 0
        if owner["id"]:
            for day, usd in sorted(days.items()):
                try:
                    d = dt.date.fromisoformat(day)
                except ValueError:
                    continue
                if float(usd or 0) <= 0 or d < owner["d"] - dt.timedelta(days=DAYS_BACK):
                    continue
                _upsert(conn, {"owner_id": owner["id"], "day": d, "service": SELF, "task": "before", "model": "",
                               "src": SELF, "key": SELF_KEY, **{k: 0 for k in NUMS}, "usd": round(float(usd), 6),
                               "usd_list": None, "exact": False}, add=False)
                n += 1
        conn.execute("INSERT INTO crm.state (key, value) VALUES (%s, %s)",
                     (BACKFILL_KEY, Jsonb({"at": dt.datetime.now(dt.timezone.utc).isoformat(), "days": n})))
    return n
