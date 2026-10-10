"""Статистика, серия и очки человека в Делах — вид stats (/api/view/stats), «Статистика» в вебе.

Очки — только за доведённое (решение 05.10.2026):
- закрыл дело — 10; обещанное — ещё 5: было в «Сейчас» на день закрытия, срок пришёл
  (срок в день закрытия или раньше) или его ждали от тебя («Ждут от меня», с 10.10.2026);
- отменил ненужное — 2: чистка завала тоже работа;
- разобрал предложение «Нового» сам (принял, поправил, отклонил) — 2.
Заводить дела бесплатно: награда за планирование кормила бы построение систем вместо дела.
Всё считается из самих дел и предложений, отдельно не копится — «Вернуть» снимает очки само.

Серия — дни подряд с хотя бы одним закрытым делом. Суббота серию не рвёт (в Шаббат манна не
падает), а закрытое в субботу засчитывается. Сегодня без дел — серия жива до полуночи.
Сутки — московские, как везде в Делах. Уровни и их имена — в вебе, по сумме очков.
"""

from __future__ import annotations

import datetime as dt
from collections import defaultdict

from . import store

DONE, PROMISED, CANCEL, TRIAGE = 10, 5, 2, 2
DAYS = 30
MSK = "AT TIME ZONE 'Europe/Moscow'"
SOURCES = {"meeting": "встречи", "telegram": "Telegram", "mcp": "Claude", "web": "веб", "voice": "голос",
           "manual": "руками", "bot": "бот", "import": "перенос"}


def _events(conn, user: str) -> list[dict]:
    """Каждое засчитанное действие: день, вид, очки и чем было дело."""
    return conn.execute(
        f"""
        SELECT (t.completed_at {MSK})::date AS day, 'done' AS kind,
               {DONE} + CASE WHEN t.focus_on = (t.completed_at {MSK})::date OR t.owed
                                OR t.due_date <= (t.completed_at {MSK})::date THEN {PROMISED} ELSE 0 END AS pts,
               t.project_id, t.source, t.due_date, (t.completed_at {MSK})::date AS at_day
        FROM tasks.tasks t WHERE t.owner_id = %(u)s AND t.status = 'done' AND t.completed_at IS NOT NULL
        UNION ALL
        SELECT (t.updated_at {MSK})::date, 'cancelled', {CANCEL}, t.project_id, t.source, NULL, NULL
        FROM tasks.tasks t WHERE t.owner_id = %(u)s AND t.status = 'cancelled'
        UNION ALL
        SELECT (s.decided_at {MSK})::date, 'triage', {TRIAGE}, NULL, s.source, NULL, NULL
        FROM tasks.suggestions s
        WHERE s.for_user = %(u)s AND s.decided_by = %(u)s AND s.decided_at IS NOT NULL
          AND s.status IN ('accepted', 'edited', 'rejected') AND coalesce(s.reason, '') NOT IN ('закрыто само', 'уточнено само', 'заведено само')
        """,
        {"u": user},
    ).fetchall()


def streaks(done_days: set[dt.date], today: dt.date) -> tuple[int, int]:
    """(текущая, лучшая): суббота без дел не рвёт, сегодня без дел — ещё не вечер."""
    if not done_days:
        return 0, 0
    run = best = 0
    d = min(done_days)
    while d <= today:
        if d in done_days:
            run += 1
            best = max(best, run)
        elif d.weekday() != 5 and d != today:
            run = 0
        d += dt.timedelta(days=1)
    return run, best


def view_stats(conn, user, **_):
    today = conn.execute("SELECT crm.today() AS d").fetchone()["d"]
    events = _events(conn, user)
    per = defaultdict(lambda: {"done": 0, "cancelled": 0, "triage": 0, "points": 0})
    for e in events:
        if e["day"] is None:
            continue
        p = per[e["day"]]
        p[e["kind"]] += 1
        p["points"] += e["pts"]
    since = today - dt.timedelta(days=DAYS - 1)
    week, prev = today - dt.timedelta(days=6), today - dt.timedelta(days=13)
    total = sum(p["points"] for p in per.values())
    cur, best = streaks({d for d, p in per.items() if p["done"]}, today)
    recent = [e for e in events if e["kind"] == "done" and e["day"] and e["day"] >= since]
    names = {r["id"]: r["name"] for r in conn.execute("SELECT id, name FROM crm.projects")}
    by_project: dict[str, int] = defaultdict(int)
    by_source: dict[str, int] = defaultdict(int)
    for e in recent:
        by_project[names.get(e["project_id"], "Входящие")] += 1
        by_source[SOURCES.get(e["source"], e["source"] or "—")] += 1
    with_due = [e for e in recent if e["due_date"]]
    now = conn.execute(
        "SELECT count(*) FILTER (WHERE status = 'open') AS open, "
        "count(*) FILTER (WHERE status = 'open' AND due_date < crm.today()) AS overdue, "
        "count(*) FILTER (WHERE status = 'open' AND focus_on = crm.today()) AS now_ "
        "FROM tasks.tasks WHERE owner_id = %s", (user,),
    ).fetchone()
    days = []
    for i in range(DAYS):
        d = since + dt.timedelta(days=i)
        days.append({"day": d, **per.get(d, {"done": 0, "cancelled": 0, "triage": 0, "points": 0})})
    return {
        "today": today,
        "points": {"total": total, "today": per.get(today, {}).get("points", 0),
                   "week": sum(p["points"] for d, p in per.items() if d >= week),
                   "prev_week": sum(p["points"] for d, p in per.items() if prev <= d < week)},
        "streak": {"current": cur, "best": best, "today_done": bool(per.get(today, {}).get("done"))},
        "days": days,
        "month": {"done": len(recent), "on_time": sum(1 for e in with_due if e["at_day"] <= e["due_date"]),
                  "with_due": len(with_due)},
        "by_project": sorted(({"name": k, "done": v} for k, v in by_project.items()), key=lambda x: -x["done"])[:6],
        "by_source": sorted(({"name": k, "done": v} for k, v in by_source.items()), key=lambda x: -x["done"]),
        "open": dict(now),
        "since": min(per) if per else None,
        "rules": {"done": DONE, "promised": PROMISED, "cancel": CANCEL, "triage": TRIAGE},
    }


store.VIEWS.update({"stats": view_stats})
