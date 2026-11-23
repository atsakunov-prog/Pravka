"""Календарь Google в Делах (23.11.2026, dela_0040): зеркало событий владельца рядом с делами.

Владелец 23.11: «мне дела просто некуда записывать… записываю в календарь, а это ужас», утром — «доделаем
календарь». Главный вид Дел (/t) показывает день целиком: встречи и дела рядом, как Todoist с подключённым
календарём, — «Сегодня», «Предстоящее» и сетка календаря, куда задачу можно бросить на час.

Ключ Google есть только у службы встреч (C:\\Bot\\Meetings, gcal.py: она же ставит встречи из дел в календарь).
Поэтому зеркало кладёт она: раз в 5 минут окно «неделя назад — восемь недель вперёд» операцией cal.put. Окно
ЗАМЕНЯЕТ всё, что лежало в нём: событие, пропавшее в Google (удалили, отклонил), уходит и здесь; повтор безвреден.
Читает веб видом cal (since, until — дни по Москве). Правда — Google: правок отсюда в календарь нет, клик по событию
открывает его в Google.

Журнала нет и строки удаляются сознательно: это зеркало чужого источника, а не данные Дел (как книга трат). Видит
каждый только свои события: встреча владельца — не дело проекта, и команде её не показать.
"""

from __future__ import annotations

import datetime as dt
import re
import uuid

from .meetings import MSK

WINDOW_MAX_DAYS = 120     # окно одной присылки — не больше; служба шлёт 63 дня
VIEW_MAX_DAYS = 200       # окно вида — не больше
EVENTS_MAX = 3000         # событий за присылку
STALE_MIN = 30            # присылки нет дольше — веб говорит «календарь не обновлялся»

_LINK = re.compile(r"^https?://\S+$", re.I)


def _day(v, what: str, err) -> dt.date:
    try:
        return dt.date.fromisoformat(str(v)[:10])
    except (TypeError, ValueError):
        raise err(f"календарь: {what} — дата ГГГГ-ММ-ДД") from None


def _moment(v, what: str, err) -> dt.datetime:
    try:
        m = dt.datetime.fromisoformat(str(v).replace("Z", "+00:00"))
    except (TypeError, ValueError):
        raise err(f"календарь: {what} — момент ISO с поясом") from None
    if m.tzinfo is None:
        raise err(f"календарь: {what} — момент без пояса, не понять, когда это")
    return m


def _text(v, n: int) -> str:
    return " ".join(str(v or "").split())[:n]


def _url(v) -> str:
    s = str(v or "").strip()
    return s[:1000] if _LINK.match(s) else ""


def norm_event(e: dict, err) -> dict:
    """Событие присылки — строка таблицы. Весь день: start_day…end_day (последний день включительно); со временем:
    start, end — моменты ISO с поясом; первый и последний день — по Москве."""
    if not isinstance(e, dict):
        raise err("календарь: событие — объект")
    eid = str(e.get("id") or "").strip()
    if not eid or len(eid) > 300:
        raise err("календарь: у события нужен id (до 300 знаков)")
    row = {"id": eid, "title": _text(e.get("title"), 500), "all_day": bool(e.get("all_day")),
           "location": _text(e.get("location"), 500), "link": _url(e.get("link")), "url": _url(e.get("url")),
           "busy": e.get("busy") is not False, "task_id": None, "start_at": None, "end_at": None}
    tid = e.get("task_id")
    if tid:
        try:
            row["task_id"] = str(uuid.UUID(str(tid)))
        except ValueError:
            row["task_id"] = None  # чужая метка — просто событие
    if row["all_day"]:
        row["day"] = _day(e.get("start_day"), f"начало «весь день» события {eid}", err)
        row["last_day"] = _day(e.get("end_day") or row["day"], f"конец «весь день» события {eid}", err)
    else:
        a = _moment(e.get("start"), f"начало события {eid}", err)
        b = _moment(e.get("end") or e.get("start"), f"конец события {eid}", err)
        if b < a:
            raise err(f"календарь: у события {eid} конец раньше начала")
        row["start_at"], row["end_at"] = a, b
        row["day"] = a.astimezone(MSK).date()
        # Конец в полночь — событие того же дня (23:00–00:00 не залезает в завтра).
        row["last_day"] = max(row["day"], (b - dt.timedelta(microseconds=1)).astimezone(MSK).date()) if b > a else row["day"]
    if row["last_day"] < row["day"]:
        raise err(f"календарь: у события {eid} последний день раньше первого")
    return row


COLS = ("title", "all_day", "start_at", "end_at", "day", "last_day", "location", "link", "url", "task_id", "busy")


def op_put(conn, user: str, op: dict, err) -> dict:
    """Окно событий целиком: что в окне было, а в присылке нет, — уходит; остальное — как прислали."""
    a = _day(op.get("from"), "from", err)
    b = _day(op.get("to"), "to", err)
    if b < a:
        raise err("календарь: to раньше from")
    if (b - a).days > WINDOW_MAX_DAYS:
        raise err(f"календарь: окно больше {WINDOW_MAX_DAYS} дней — дели на части")
    events = op.get("events")
    if not isinstance(events, list):
        raise err("календарь: events — список событий")
    if len(events) > EVENTS_MAX:
        raise err(f"календарь: за раз не больше {EVENTS_MAX} событий")
    rows: dict[str, dict] = {}
    outside = 0
    for e in events:
        r = norm_event(e, err)
        if r["day"] > b or r["last_day"] < a:
            outside += 1  # вне окна — не наше: следующее окно его не уберёт, поэтому и не кладём
            continue
        rows[r["id"]] = r  # повтор id в присылке — последний
    removed = conn.execute(
        "DELETE FROM crm.cal_events WHERE owner_id = %s AND day <= %s AND last_day >= %s AND NOT (id = ANY(%s::text[]))",
        (user, b, a, list(rows))).rowcount
    changed = 0
    cols = ", ".join(COLS)
    marks = ", ".join(["%s"] * len(COLS))
    sets = ", ".join(f"{c} = EXCLUDED.{c}" for c in COLS)
    differs = " OR ".join(f"crm.cal_events.{c} IS DISTINCT FROM EXCLUDED.{c}" for c in COLS)
    for r in rows.values():
        changed += conn.execute(
            f"INSERT INTO crm.cal_events (owner_id, id, {cols}) VALUES (%s, %s, {marks}) "
            f"ON CONFLICT (owner_id, id) DO UPDATE SET {sets}, updated_at = now() WHERE {differs}",
            [user, r["id"], *(r[c] for c in COLS)]).rowcount
    conn.execute(
        "INSERT INTO crm.cal_sync (owner_id, at, events, day_from, day_to) VALUES (%s, now(), %s, %s, %s) "
        "ON CONFLICT (owner_id) DO UPDATE SET at = now(), events = EXCLUDED.events, day_from = EXCLUDED.day_from, "
        "day_to = EXCLUDED.day_to", (user, len(rows), a, b))
    return {"put": len(rows), "changed": changed, "removed": removed, "outside": outside}


def view_cal(conn, user: str, err, since=None, until=None, **_) -> dict:
    """События в днях since…until (по Москве; по умолчанию вчера…через шесть недель). Время — по Москве, ЧЧ:ММ."""
    today = conn.execute("SELECT crm.today() AS d").fetchone()["d"]
    a = _day(since, "since", err) if since else today - dt.timedelta(days=1)
    b = _day(until, "until", err) if until else today + dt.timedelta(days=42)
    if b < a:
        raise err("календарь: until раньше since")
    if (b - a).days > VIEW_MAX_DAYS:
        raise err(f"календарь: окно вида больше {VIEW_MAX_DAYS} дней")
    rows = conn.execute(
        "SELECT id, title, all_day, day, last_day, location, link, url, task_id, busy, "
        "       to_char(start_at AT TIME ZONE 'Europe/Moscow', 'HH24:MI') AS start, "
        "       to_char(end_at AT TIME ZONE 'Europe/Moscow', 'HH24:MI') AS \"end\", "
        "       (end_at AT TIME ZONE 'Europe/Moscow')::date AS end_day, "
        "       round(extract(epoch FROM end_at - start_at) / 60)::int AS mins "
        "FROM crm.cal_events WHERE owner_id = %s AND day <= %s AND last_day >= %s "
        "ORDER BY day, all_day DESC, start_at, title, id", (user, b, a)).fetchall()
    s = conn.execute("SELECT at, events, day_from, day_to, now() - at > make_interval(mins => %s) AS stale "
                     "FROM crm.cal_sync WHERE owner_id = %s", (STALE_MIN, user)).fetchone()
    return {"since": a, "until": b, "events": rows,
            "sync": None if not s else {"at": s["at"], "events": s["events"], "from": s["day_from"],
                                        "to": s["day_to"], "stale": s["stale"]}}


def register(handlers: dict, views: dict, err) -> None:
    handlers["cal.put"] = lambda conn, user, op: op_put(conn, user, op, err)
    views["cal"] = lambda conn, user, **p: view_cal(conn, user, err, **p)
