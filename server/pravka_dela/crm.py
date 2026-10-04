"""CRM поверх Дел: воронка, клиенты, карточка сделки, связи, деньги, время из Засечки.

Те же правила, что у дел: всё читается ролью службы от имени человека, поэтому
видимость решает база (RLS), а деньги прячет вид crm.v_deals и политика оплат.
Время из ленты Засечки — функции владельца (crm.time_*): другим они отвечают
пусто, и ключа minutes в ответе для них просто нет.

Живой следующий шаг сделки — её открытое дело (tasks.deal_id). Тексты «Следующий
шаг» и «Мяч» из Notion остались справкой: им полгода, и правдой они не станут.
"""

from __future__ import annotations

import datetime as dt
from typing import Any

from . import store

STAGES = ["lead", "proposal", "mandate", "active", "closing", "archive"]
OPEN_STAGES = STAGES[:-1]
SIGNED = ("mandate", "active", "closing")
# Вероятность по стадии, если у сделки своей нет: лид и КП ещё не деньги,
# мандат и дальше — подписано, вопрос только в оплате.
DEFAULT_P = {"lead": 10, "proposal": 30, "mandate": 90, "active": 90, "closing": 90}
CADENCE_DAYS = {"month": 30, "quarter": 90, "year": 365}
STALE_DAYS = 30
EPOCH = dt.date(2000, 1, 1)

# Сделка с тем, что о ней надо знать в списке: клиент, стадия с какого дня,
# следующее дело, последний контакт и движение, деньги по оплатам.
DEALS_SQL = """
SELECT d.*, p.name AS project_name, p.org_id, crm.stage_since(d.id) AS stage_since,
       (SELECT count(*) FROM tasks.tasks t WHERE t.deal_id = d.id AND t.status = 'open') AS open_tasks,
       (SELECT json_build_object('id', t.id, 'num', t.num, 'title', t.title, 'due_date', t.due_date,
                                 'owner_id', t.owner_id, 'ball', t.ball)
          FROM tasks.tasks t WHERE t.deal_id = d.id AND t.status = 'open'
          ORDER BY t.due_date NULLS LAST, t.created_at LIMIT 1) AS next_task,
       (SELECT max(i.at) FROM crm.interactions i
         WHERE i.deleted_at IS NULL AND (i.deal_id = d.id OR (i.deal_id IS NULL AND i.project_id = d.project_id))) AS last_touch,
       (SELECT max(greatest(t.created_at, t.completed_at)) FROM tasks.tasks t WHERE t.deal_id = d.id) AS last_task_move,
       (SELECT sum(pm.amount_kop) FROM crm.payments pm
         WHERE pm.deal_id = d.id AND pm.cancelled_at IS NULL AND pm.paid_on IS NOT NULL) AS paid_kop,
       (SELECT sum(pm.amount_kop) FROM crm.payments pm
         WHERE pm.deal_id = d.id AND pm.cancelled_at IS NULL AND pm.paid_on IS NULL AND pm.invoiced_on IS NOT NULL) AS invoiced_kop,
       (SELECT sum(pm.amount_kop) FROM crm.payments pm
         WHERE pm.deal_id = d.id AND pm.cancelled_at IS NULL AND pm.paid_on IS NULL AND pm.invoiced_on IS NULL) AS planned_kop
FROM crm.v_deals d
JOIN crm.projects p ON p.id = d.project_id
"""


def _today(conn) -> dt.date:
    return conn.execute("SELECT crm.today() AS d").fetchone()["d"]


def _flag(conn, fn: str, user: str) -> bool:
    return bool(conn.execute(f"SELECT {fn}(%s) AS x", (user,)).fetchone()["x"])


def is_owner(conn, user: str) -> bool:
    return conn.execute("SELECT crm.owner_id() = %s AS x", (user,)).fetchone()["x"]


def money_ok(conn, user: str) -> bool:
    return _flag(conn, "crm.money_ok", user)


def _day(v) -> dt.date | None:
    if v is None:
        return None
    return v.date() if isinstance(v, dt.datetime) else v


def _decorate(d: dict, today: dt.date) -> dict:
    """Вероятность по стадии, вес в воронке, сколько осталось получить, застой."""
    p = d.get("probability")
    if p is None and d["stage"] in DEFAULT_P:
        p = DEFAULT_P[d["stage"]]
    d["p_eff"] = p
    fee = d.get("fee_kop")
    d["weighted_kop"] = round(fee * p / 100) if fee and p is not None and d["stage"] != "archive" else None
    if fee and d["stage"] in SIGNED:
        d["to_get_kop"] = max(fee - (d.get("paid_kop") or 0), 0)
    moves = [x for x in (_day(d.get("last_touch")), _day(d.get("last_task_move")), d.get("stage_since")) if x]
    d["last_move"] = max(moves) if moves else None
    d["quiet_days"] = (today - d["last_move"]).days if d["last_move"] else None
    d["stale"] = d["stage"] != "archive" and (not d.get("open_tasks") or (d["quiet_days"] or 0) > STALE_DAYS)
    return d


def deals(conn, where: str = "TRUE", params: list | None = None) -> list[dict]:
    today = _today(conn)
    rows = conn.execute(f"{DEALS_SQL} WHERE {where} ORDER BY p.name, d.name", params or []).fetchall()
    return [_decorate(dict(r), today) for r in rows]


def _minutes(conn, fn: str, since: dt.date, key: str) -> dict:
    return {r[key]: r for r in conn.execute(f"SELECT * FROM {fn}(%s)", (since,)).fetchall()}


# ── Виды ────────────────────────────────────────────────────────────────


def view_pipeline(conn, user, closed_days: str | None = None, **_):
    """Воронка: живые сделки по стадиям с весом, застывшие и недавно закрытые."""
    today = _today(conn)
    days = int(closed_days or 90)
    rows = deals(conn, "d.stage <> 'archive' OR d.closed_on >= %s", [today - dt.timedelta(days=days)])
    owner = is_owner(conn, user)
    if owner:
        t30 = _minutes(conn, "crm.time_by_deal", today - dt.timedelta(days=30), "deal_id")
        tall = _minutes(conn, "crm.time_by_deal", EPOCH, "deal_id")
        for d in rows:
            d["minutes_30"] = (t30.get(d["id"]) or {}).get("minutes")
            d["minutes_all"] = (tall.get(d["id"]) or {}).get("minutes")
    stages = []
    for s in STAGES:
        items = [d for d in rows if d["stage"] == s]
        stages.append({
            "stage": s,
            "count": len(items),
            "fee_kop": sum(d.get("fee_kop") or 0 for d in items),
            "weighted_kop": sum(d.get("weighted_kop") or 0 for d in items),
        })
    live = [d for d in rows if d["stage"] != "archive"]
    return {
        "deals": rows,
        "stages": stages,
        "money": money_ok(conn, user),
        "owner": owner,
        "totals": {
            "live": len(live),
            "pipeline_kop": sum(d.get("weighted_kop") or 0 for d in live if d["stage"] in ("lead", "proposal")),
            "to_get_kop": sum(d.get("to_get_kop") or 0 for d in live),
            "stale": sum(1 for d in live if d["stale"]),
        },
    }


CLIENTS_SQL = """
SELECT p.id, p.name, p.aliases, p.org_id, o.name AS org, p.money_default, p.archived_at, p.owner_id, p.note,
       (SELECT count(*) FROM crm.deals d WHERE d.project_id = p.id AND d.stage <> 'archive') AS live_deals,
       (SELECT count(*) FROM crm.deals d WHERE d.project_id = p.id) AS all_deals,
       (SELECT array_agg(DISTINCT d.stage) FROM crm.deals d WHERE d.project_id = p.id AND d.stage <> 'archive') AS stages,
       (SELECT max(i.at) FROM crm.interactions i WHERE i.project_id = p.id AND i.deleted_at IS NULL) AS last_touch,
       (SELECT count(*) FROM tasks.tasks t WHERE t.project_id = p.id AND t.status = 'open') AS open_tasks,
       (SELECT json_build_object('id', t.id, 'num', t.num, 'title', t.title, 'due_date', t.due_date, 'owner_id', t.owner_id, 'ball', t.ball)
          FROM tasks.tasks t WHERE t.project_id = p.id AND t.status = 'open'
          ORDER BY t.due_date NULLS LAST, t.created_at LIMIT 1) AS next_task,
       (SELECT sum(d.fee_kop) FROM crm.v_deals d WHERE d.project_id = p.id AND d.stage IN ('mandate', 'active', 'closing')) AS signed_fee_kop,
       (SELECT sum(pm.amount_kop) FROM crm.payments pm JOIN crm.deals d ON d.id = pm.deal_id
         WHERE d.project_id = p.id AND pm.paid_on IS NOT NULL AND pm.cancelled_at IS NULL) AS paid_kop,
       (SELECT sum(pm.amount_kop) FROM crm.payments pm JOIN crm.deals d ON d.id = pm.deal_id
         WHERE d.project_id = p.id AND pm.paid_on >= date_trunc('year', crm.today())::date AND pm.cancelled_at IS NULL) AS paid_year_kop,
       (SELECT sum(pm.amount_kop) FROM crm.payments pm JOIN crm.deals d ON d.id = pm.deal_id
         WHERE d.project_id = p.id AND pm.paid_on IS NULL AND pm.invoiced_on IS NOT NULL AND pm.cancelled_at IS NULL) AS invoiced_kop
FROM crm.projects p
LEFT JOIN crm.orgs o ON o.id = p.org_id
WHERE p.kind = 'client'
ORDER BY p.archived_at IS NOT NULL, p.name
"""


def view_clients(conn, user, **_):
    """Клиенты: сделки, последний контакт, следующее дело, деньги, часы из Засечки."""
    today = _today(conn)
    rows = [dict(r) for r in conn.execute(CLIENTS_SQL).fetchall()]
    out: dict[str, Any] = {"clients": rows, "money": money_ok(conn, user), "owner": is_owner(conn, user)}
    if out["owner"]:
        t90 = _minutes(conn, "crm.time_by_project", today - dt.timedelta(days=90), "project_id")
        tall = _minutes(conn, "crm.time_by_project", EPOCH, "project_id")
        for c in rows:
            c["minutes_90"] = (t90.get(c["id"]) or {}).get("minutes")
            c["minutes_all"] = (tall.get(c["id"]) or {}).get("minutes")
            c["last_work"] = (tall.get(c["id"]) or {}).get("last_day")
        ignore = set(_settings(conn, user).get("time_ignore") or [])
        out["unmatched"] = [r for r in conn.execute("SELECT * FROM crm.time_unmatched(%s)", (EPOCH,)).fetchall()
                            if r["client"] not in ignore]
    return out


def _settings(conn, user) -> dict:
    row = conn.execute("SELECT settings FROM crm.users WHERE id = %s", (user,)).fetchone()
    return (row or {}).get("settings") or {}


def _timeline(conn, where: str, params: list, limit: int = 300) -> list[dict]:
    return conn.execute(
        "SELECT i.*, d.name AS deal_name, p.name AS project_name FROM crm.interactions i "
        "LEFT JOIN crm.deals d ON d.id = i.deal_id LEFT JOIN crm.projects p ON p.id = i.project_id "
        f"WHERE i.deleted_at IS NULL AND ({where}) ORDER BY i.at DESC LIMIT {int(limit)}",
        params,
    ).fetchall()


def view_deal(conn, user, deal_id=None, **_):
    if not deal_id:
        raise store.OpError("нужен deal_id")
    found = deals(conn, "d.id = %s", [deal_id])
    if not found:
        raise store.OpError("нет такой сделки или она не видна")
    d = found[0]
    out: dict[str, Any] = {
        "deal": d,
        "open": store._list(conn, "status = 'open' AND deal_id = %s", [deal_id]),
        "done": store._list(conn, "status <> 'open' AND deal_id = %s", [deal_id], order=" ORDER BY completed_at DESC LIMIT 30"),
        "payments": conn.execute(
            "SELECT * FROM crm.payments WHERE deal_id = %s ORDER BY coalesce(paid_on, invoiced_on, due_on, created_at::date)", (deal_id,)
        ).fetchall(),
        "timeline": _timeline(conn, "i.deal_id = %s", [deal_id]),
        "history": conn.execute("SELECT * FROM crm.deal_history(%s)", (deal_id,)).fetchall(),
        "money": money_ok(conn, user),
        "owner": is_owner(conn, user),
    }
    if out["owner"]:
        out["entries"] = conn.execute("SELECT * FROM crm.time_entries(%s, %s, 200)", (d["project_id"], deal_id)).fetchall()
        out["minutes_all"] = (_minutes(conn, "crm.time_by_deal", EPOCH, "deal_id").get(d["id"]) or {}).get("minutes")
    return out


def view_client(conn, user, project_id=None, **_):
    """Хронология клиента, люди, часы по месяцам — для страницы проекта-клиента."""
    if not project_id:
        raise store.OpError("нужен project_id")
    p = conn.execute("SELECT * FROM crm.projects WHERE id = %s", (project_id,)).fetchone()
    if not p:
        raise store.OpError("нет такого проекта или он не виден")
    out: dict[str, Any] = {
        "project": p,
        "timeline": _timeline(conn, "i.project_id = %s", [project_id]),
        "deals": deals(conn, "d.project_id = %s", [project_id]),
        "people": conn.execute(
            "SELECT pe.* FROM crm.people pe WHERE pe.archived_at IS NULL AND ("
            " (pe.org_id IS NOT NULL AND pe.org_id = %s)"
            " OR EXISTS (SELECT 1 FROM crm.deals d WHERE d.project_id = %s AND pe.id = ANY (d.person_ids)))"
            " ORDER BY pe.name",
            (p["org_id"], project_id),
        ).fetchall(),
        "money": money_ok(conn, user),
        "owner": is_owner(conn, user),
    }
    if out["owner"]:
        out["entries"] = conn.execute("SELECT * FROM crm.time_entries(%s, NULL, 300)", (project_id,)).fetchall()
        months: dict[str, int] = {}
        for e in conn.execute("SELECT * FROM crm.time_entries(%s, NULL, 100000)", (project_id,)).fetchall():
            k = e["day"].strftime("%Y-%m")
            months[k] = months.get(k, 0) + (e["minutes"] or 0)
        out["months"] = [{"month": k, "minutes": v} for k, v in sorted(months.items(), reverse=True)]
    return out


def view_dossier(conn, user, person_id=None, **_):
    """Человек в CRM: его сделки (ведёт, в команде, участник, привёл) и хронология."""
    if not person_id:
        raise store.OpError("нужен person_id")
    return {
        "timeline": _timeline(conn, "%s = ANY (i.person_ids)", [person_id], 150),
        "deals": deals(conn, "d.lead_person_id = %s OR %s = ANY (d.team_ids) OR %s = ANY (d.person_ids) OR d.source_person_id = %s",
                       [person_id] * 4),
    }


TIES_SQL = """
SELECT pe.id, pe.name, pe.short, pe.org_id, o.name AS org, pe.role, pe.cadence, pe.hub, pe.telegram_username, pe.phones,
       pe.birth_day, pe.birth_month, pe.seeks, pe.offers,
       (SELECT max(i.at) FROM crm.interactions i WHERE pe.id = ANY (i.person_ids) AND i.deleted_at IS NULL) AS last_touch,
       (SELECT count(*) FROM crm.deals d WHERE d.source_person_id = pe.id) AS brought,
       (SELECT count(*) FROM tasks.tasks t WHERE t.person_id = pe.id AND t.status = 'open' AND t.ball = 'agenda') AS agenda
FROM crm.people pe
LEFT JOIN crm.orgs o ON o.id = pe.org_id
WHERE pe.archived_at IS NULL AND pe.user_id IS NULL
  AND (pe.cadence IN ('month', 'quarter', 'year') OR pe.hub
       OR EXISTS (SELECT 1 FROM crm.deals d WHERE d.source_person_id = pe.id))
"""


def view_ties(conn, user, **_):
    """Связи: кому пора напомнить о себе по теплоте (cadence), хабы, кто приводит сделки."""
    today = _today(conn)
    rows = []
    for r in conn.execute(TIES_SQL).fetchall():
        r = dict(r)
        last = _day(r["last_touch"])
        r["since_days"] = (today - last).days if last else None
        period = CADENCE_DAYS.get(r["cadence"])
        r["due"] = bool(period) and (last is None or (today - last).days >= period)
        r["overdue_days"] = ((today - last).days - period) if period and last else None
        if r["birth_day"]:
            r["birthday_in"] = _days_to(today, r["birth_month"], r["birth_day"])
        rows.append(r)
    # Сначала те, кому пора: ни разу не говорили — первыми, дальше по просрочке.
    rows.sort(key=lambda r: (not r["due"], -(10**6 if r["overdue_days"] is None else r["overdue_days"]) if r["due"] else 0, r["name"]))
    return {"people": rows, "today": today}


def _days_to(today: dt.date, month: int, day: int) -> int:
    """Дней до ближайшего дня рождения; 29 февраля в обычный год — 1 марта."""
    for year in (today.year, today.year + 1):
        try:
            b = dt.date(year, month, day)
        except ValueError:
            b = dt.date(year, 3, 1)
        if b >= today:
            return (b - today).days
    return 0


def view_money(conn, user, **_):
    """Деньги фирмы по сделкам: дебиторка, ждём в ближайший месяц, пришло; портфель и воронка."""
    if not money_ok(conn, user):
        raise store.OpError("деньги этому пользователю не открыты")
    today = _today(conn)
    pays = conn.execute(
        "SELECT pm.*, d.name AS deal_name, d.project_id, p.name AS project_name FROM crm.payments pm "
        "JOIN crm.deals d ON d.id = pm.deal_id JOIN crm.projects p ON p.id = d.project_id "
        "WHERE pm.cancelled_at IS NULL ORDER BY coalesce(pm.paid_on, pm.due_on, pm.invoiced_on) NULLS LAST"
    ).fetchall()
    unpaid = [x for x in pays if not x["paid_on"]]
    paid = [x for x in pays if x["paid_on"]]
    month0 = today.replace(day=1)
    quarter0 = today.replace(month=(today.month - 1) // 3 * 3 + 1, day=1)
    year0 = today.replace(month=1, day=1)
    soon = today + dt.timedelta(days=30)
    live = deals(conn, "d.stage <> 'archive'")

    def total(xs):
        return sum(x["amount_kop"] for x in xs)

    return {
        "overdue": [x for x in unpaid if x["invoiced_on"] and (x["due_on"] or x["invoiced_on"]) < today],
        "invoiced": [x for x in unpaid if x["invoiced_on"] and not ((x["due_on"] or x["invoiced_on"]) < today)],
        "expected": [x for x in unpaid if not x["invoiced_on"] and x["due_on"] and x["due_on"] <= soon],
        "later": [x for x in unpaid if not x["invoiced_on"] and (not x["due_on"] or x["due_on"] > soon)],
        "paid": [x for x in reversed(paid) if x["paid_on"] >= today - dt.timedelta(days=120)],
        "totals": {
            "paid_month": total(x for x in paid if x["paid_on"] >= month0),
            "paid_quarter": total(x for x in paid if x["paid_on"] >= quarter0),
            "paid_year": total(x for x in paid if x["paid_on"] >= year0),
            "receivable": total(x for x in unpaid if x["invoiced_on"]),
            "expected_30": total(x for x in unpaid if x["due_on"] and x["due_on"] <= soon),
            "to_get": sum(d.get("to_get_kop") or 0 for d in live),
            "pipeline": sum(d.get("weighted_kop") or 0 for d in live if d["stage"] in ("lead", "proposal")),
        },
        "unplanned": [d for d in live if d["stage"] in SIGNED and d.get("fee_kop")
                      and (d.get("paid_kop") or 0) + (d.get("invoiced_kop") or 0) + (d.get("planned_kop") or 0) < d["fee_kop"]],
    }


VIEWS = {
    "pipeline": view_pipeline,
    "clients": view_clients,
    "deal": view_deal,
    "client": view_client,
    "dossier": view_dossier,
    "ties": view_ties,
    "money": view_money,
}
store.VIEWS.update(VIEWS)
