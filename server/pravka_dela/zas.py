"""Лента Засечки на сервере (dela_0038, 26.10.2026, docs/server-truth.md): хранение, операции, передача, синк, таймер.

Владелец: «основная база всё равно на сервере… переносим всё». До передачи (режим phone) правда ленты — телефон, и
здесь ничего не меняется: веб ставит команды (zasechka.py). Передача (`zas.handover`) — новая сборка телефона
отдаёт ленту целиком один раз; дальше (режим server) правда — `crm.zas_entries`, правила — `ledger.py` (порт
`ZasechkaStore` один к одному), а:
- веб пишет сразу операциями web_start / web_stop / web_edit — без «ждёт телефона»;
- телефон пишет те же мутаторы, что у себя (start, close, insert_closed, interruption, update, …), операцией `zas.op`
  через очередь Дел (повтор по op_id гасит apply_ops); запись по id несёт `was` (начало и название): кусок, которого
  сервер не знает под этим id (телефон без сети нарезал его сам), находится по ним;
- синк Дел отдаёт телефону окно ленты (последние двое суток и идущее), когда версия ленты lrev сменилась;
- раз в минуту `tick` нормализует ленту на «сейчас»: живой заполнитель, полночь, карантин дыр — то, что телефон
  делал тиком службы;
- после каждой правки затронутые сутки ложатся в архив видом zasechka.day от svc-zasechka (`crm.zas_publish`) — всё,
  что читает ленту из архива, не меняется.
Только владельцу: лента — его.
"""

from __future__ import annotations

import datetime as dt
import json
import re
from dataclasses import replace
from typing import Any, Callable

from psycopg.types.json import Jsonb

from . import digest, ledger
from .ledger import Entry, Ledger

DAY_MS = 86_400_000
WINDOW_DAYS = 2                 # окно ленты телефону: двое суток и идущее (вчерашний вечер правят утром)
LOAD_DAYS = 11                  # движок смотрит на десять суток назад (NORMALIZE_WINDOW) — грузим с запасом
HANDOVER_MAX = 60_000           # записей в передаче: у владельца 1,7 тысячи за два месяца
TITLE_MAX = 120
TEXT_MAX = 4000
WEB_AHEAD_MS = 120_000          # часы компа и сервера расходятся — миг нажатия чуть в будущем не ошибка
WEB_MAX_AGE_MS = 12 * 3_600_000
HM = re.compile(r"^(\d{1,2}):(\d{2})$")
COLS = ("id, start_ms, end_ms, raw, title, category, client, useful, source, created_ms, pomodoros, comment, "
        "task, project, who")


# ── хранение ─────────────────────────────────────────────────────────────────

def _entry(r: dict) -> Entry:
    return Entry(id=r["id"], start=r["start_ms"], end=r["end_ms"], raw=r["raw"], title=r["title"],
                 category=r["category"], client=r["client"], useful=r["useful"], source=r["source"],
                 created_at=r["created_ms"], pomodoros=r["pomodoros"], comment=r["comment"], task=r["task"],
                 project=r["project"], person=r["who"])


def out(e: Entry | None) -> dict | None:
    """Запись наружу — те же имена, что у Entry телефона (start/end — мс, end 0 — идёт)."""
    return None if e is None else e.to_dict()


def state(conn, user: str, lock: bool = False) -> dict | None:
    return conn.execute("SELECT * FROM crm.zas_state WHERE owner_id = %s" + (" FOR UPDATE" if lock else ""),
                        (user,)).fetchone()


def mode(conn, user: str) -> str:
    st = state(conn, user)
    return st["mode"] if st else "phone"


def now_ms(conn) -> int:
    return int(conn.execute("SELECT (extract(epoch FROM now()) * 1000)::bigint AS n").fetchone()["n"])


def _load(conn, user: str, lower: int, ids: tuple[int, ...] = ()) -> list[Entry]:
    rows = conn.execute(
        f"SELECT {COLS} FROM crm.zas_entries WHERE owner_id = %s AND (end_ms = 0 OR end_ms > %s OR start_ms > %s "
        "OR id = ANY(%s)) ORDER BY start_ms, id", (user, lower, lower, list(ids))).fetchall()
    return [_entry(r) for r in rows]


def _put(conn, user: str, e: Entry) -> None:
    conn.execute(
        f"INSERT INTO crm.zas_entries (owner_id, {COLS}, day) VALUES (%s, {', '.join(['%s'] * 15)}, %s) "
        "ON CONFLICT (owner_id, id) DO UPDATE SET start_ms = EXCLUDED.start_ms, end_ms = EXCLUDED.end_ms, "
        "raw = EXCLUDED.raw, title = EXCLUDED.title, category = EXCLUDED.category, client = EXCLUDED.client, "
        "useful = EXCLUDED.useful, source = EXCLUDED.source, created_ms = EXCLUDED.created_ms, "
        "pomodoros = EXCLUDED.pomodoros, comment = EXCLUDED.comment, task = EXCLUDED.task, "
        "project = EXCLUDED.project, who = EXCLUDED.who, day = EXCLUDED.day",
        (user, e.id, e.start, e.end, e.raw, e.title, e.category, e.client, e.useful, e.source, e.created_at,
         e.pomodoros, e.comment, e.task, e.project, e.person, ledger.day_of(e.start)))


def _save(conn, user: str, before: dict[int, Entry], after: list[Entry]) -> set[str]:
    """Записать разницу; вернуть сутки, которые она задела (их и кладём в архив)."""
    days: set[str] = set()
    now_ids = {e.id for e in after}
    gone = [i for i in before if i not in now_ids]
    if gone:
        conn.execute("DELETE FROM crm.zas_entries WHERE owner_id = %s AND id = ANY(%s)", (user, gone))
        days |= {ledger.day_of(before[i].start) for i in gone}
    for e in after:
        old = before.get(e.id)
        if old == e:
            continue
        _put(conn, user, e)
        days.add(ledger.day_of(e.start))
        if old is not None:
            days.add(ledger.day_of(old.start))
    return days


def _day_entries(conn, user: str, day: str) -> list[Entry]:
    rows = conn.execute(f"SELECT {COLS} FROM crm.zas_entries WHERE owner_id = %s AND day = %s ORDER BY start_ms, id",
                        (user, day)).fetchall()
    return [_entry(r) for r in rows]


def _publish(conn, user: str, days: set[str], now: int) -> int:
    """Сутки — в архив: тем же видом и в той же форме, что слал телефон (ledger.day_snapshot). Пустые — удалением."""
    n = 0
    for d in sorted(days):
        snap = ledger.day_snapshot(_day_entries(conn, user, d), d, now)
        if snap is None:
            got = conn.execute("SELECT crm.zas_publish('zasechka.day', %s, NULL, true) AS ok", (d,)).fetchone()
        else:
            got = conn.execute("SELECT crm.zas_publish('zasechka.day', %s, %s) AS ok", (d, Jsonb(snap))).fetchone()
        n += bool(got["ok"])
    return n


def categories(conn, user: str) -> list[dict]:
    """Категории ленты — правда в Своде (zasechka.categories, пишет телефон и веб); нет записи — с передачи."""
    row = conn.execute("SELECT body, value FROM crm.svod WHERE owner_id = %s AND key = 'zasechka.categories'",
                       (user,)).fetchone()
    val: Any = None
    if row:
        val = row["value"]
        if val is None and row["body"]:
            try:
                val = json.loads(row["body"])
            except ValueError:
                val = None
    if not isinstance(val, list) or not val:
        st = state(conn, user)
        val = st["categories"] if st else []
    outl = []
    for c in val:
        if isinstance(c, dict) and str(c.get("name") or "").strip():
            outl.append({"name": str(c["name"]).strip(), "hint": str(c.get("hint") or ""),
                         "base_min": int(c.get("base_min") or c.get("baseMin") or 0), "value": int(c.get("value") or 0)})
        elif isinstance(c, str) and c.strip():
            outl.append({"name": c.strip(), "hint": "", "base_min": 0, "value": 0})
    return outl


def publish_reference(conn, user: str) -> bool:
    st = state(conn, user)
    cats = categories(conn, user)
    ref = {"categories": [dict(c, ord=i + 1) for i, c in enumerate(cats)],
           "clients": list(st["clients"]) if st else []}
    got = conn.execute("SELECT crm.zas_publish('zasechka.reference', 'all', %s) AS ok", (Jsonb(ref),)).fetchone()
    return bool(got["ok"])


def window(conn, user: str, now: int) -> list[dict]:
    lo = now - WINDOW_DAYS * DAY_MS
    rows = conn.execute(
        f"SELECT {COLS} FROM crm.zas_entries WHERE owner_id = %s AND (end_ms = 0 OR end_ms >= %s OR start_ms >= %s) "
        "ORDER BY start_ms, id", (user, lo, lo)).fetchall()
    return [_entry(r).to_dict() for r in rows]


# ── операция над лентой ─────────────────────────────────────────────────────

def _run(conn, user: str, err, fn: Callable[[Ledger], Any], *, kind: str, args: dict, lower: int | None = None,
         ids: tuple[int, ...] = (), at: Any = None, device: str = "", log: bool = True) -> dict:
    st = state(conn, user, lock=True)
    if not st or st["mode"] != "server":
        raise err("Засечка: лента ещё на телефоне — веб ставит команду, телефон применит (передачи ещё не было)")
    now = now_ms(conn)
    lo = min(now - LOAD_DAYS * DAY_MS, (lower if lower else now) - DAY_MS)
    entries = _load(conn, user, lo, ids)
    before = {e.id: e for e in entries}
    lg = Ledger(entries, now=now, last_id=st["last_id"])
    res = fn(lg)
    days = _save(conn, user, before, lg.entries)
    lrev = st["lrev"] + (1 if days else 0)
    published = _publish(conn, user, days, now) if days else 0
    conn.execute("UPDATE crm.zas_state SET last_id = %s, lrev = %s, updated_at = now() WHERE owner_id = %s",
                 (max(lg.last_id, st["last_id"]), lrev, user))
    result = out(res) if isinstance(res, Entry) else res
    if log or days:
        conn.execute("INSERT INTO crm.zas_ops (owner_id, kind, args, at, device, via, result) "
                     "VALUES (%s, %s, %s, %s, %s, crm.via(), %s)",
                     (user, kind, Jsonb(args), _ts(at), device or None,
                      Jsonb({"result": _brief(result), "days": sorted(days), "published": published})))
    return {"result": result, "lrev": lrev, "days": sorted(days)}


def _brief(r: Any) -> Any:
    if isinstance(r, dict) and "id" in r:
        return {k: r.get(k) for k in ("id", "start", "end", "title", "category", "source")}
    return r


def _ts(at: Any) -> dt.datetime | None:
    if isinstance(at, (int, float)) and at > 0:
        return dt.datetime.fromtimestamp(at / 1000, dt.timezone.utc)
    if isinstance(at, str) and at:
        try:
            return dt.datetime.fromisoformat(at.replace("Z", "+00:00"))
        except ValueError:
            return None
    return None


def _owner_only(conn, user: str, err) -> None:
    if not digest.is_owner(conn, user):
        raise err("Засечка — только владельцу Дел: лента его")


def _int(v: Any, what: str, err, allow_zero: bool = False) -> int:
    try:
        n = int(v)
    except (TypeError, ValueError):
        raise err(f"Засечка: {what} — число") from None
    if n < 0 or (n == 0 and not allow_zero):
        raise err(f"Засечка: {what} — положительное число")
    return n


def _str(v: Any, n: int = TITLE_MAX) -> str:
    return str(v or "").strip()[:n]


def _text(v: Any) -> str:
    return str(v or "")[:TEXT_MAX]


def _resolve(lg: Ledger, eid: Any, was: Any) -> int | None:
    """Кусок по номеру; нет такого — по тому, как его видел телефон (начало и название): заполнители и куски, которые
    телефон без сети нарезал сам, на сервере живут под своими номерами."""
    try:
        i = int(eid)
    except (TypeError, ValueError):
        i = 0
    if i and lg.by_id(i):
        return i
    if isinstance(was, dict):
        try:
            start = int(was.get("start") or 0)
        except (TypeError, ValueError):
            start = 0
        title = str(was.get("title") or "").strip()
        if start:
            hit = [e for e in lg.entries if e.start == start and e.title.strip() == title]
            if not hit and was.get("source") == ledger.GAP_SOURCE:
                hit = [e for e in lg.entries if e.start == start and e.source == ledger.GAP_SOURCE]
            if len(hit) == 1:
                return hit[0].id
    return None


def _need(lg: Ledger, eid: Any, was: Any, err) -> int:
    got = _resolve(lg, eid, was)
    if got is None:
        raise err("Засечка: этой записи в ленте нет — её уже поправили или удалили")
    return got


def _was_start(args: dict) -> int | None:
    w = args.get("was")
    if isinstance(w, dict):
        try:
            return int(w.get("start") or 0) or None
        except (TypeError, ValueError):
            return None
    return None


# Мутаторы телефона: (что делать с лентой, нижняя граница времени, какие номера подгрузить).
def _phone_op(kind: str, a: dict, err) -> tuple[Callable[[Ledger], Any], int | None, tuple[int, ...]]:
    if kind == "start":
        start = _int(a.get("start"), "start", err)
        return (lambda lg: lg.start_entry(start, raw=_text(a.get("raw")), title=_str(a.get("title")),
                                          category=_str(a.get("category")), client=_str(a.get("client")),
                                          useful=int(a.get("useful") or 0), source=_str(a.get("source"), 20) or "voice",
                                          task=_str(a.get("task")), project=_str(a.get("project")),
                                          id=int(a.get("id") or 0) or None), start, ())
    if kind == "close":
        at = _int(a.get("at"), "at", err)
        return (lambda lg: lg.close_open(at), at, ())
    if kind == "insert_closed":
        s, e = _int(a.get("start"), "start", err), _int(a.get("end"), "end", err)
        return (lambda lg: lg.insert_closed(s, e, raw=_text(a.get("raw")), title=_str(a.get("title")),
                                            category=_str(a.get("category")), client=_str(a.get("client")),
                                            useful=int(a.get("useful") or 0), id=int(a.get("id") or 0) or None), s, ())
    if kind == "interruption":
        s, e = _int(a.get("start"), "start", err), _int(a.get("end"), "end", err)
        return (lambda lg: lg.insert_interruption(
            s, e, _str(a.get("title")), _str(a.get("category")), bool(a.get("resume_previous")),
            client=_str(a.get("client")), project=_str(a.get("project")), person=_str(a.get("person")),
            comment=_text(a.get("comment")), id=int(a.get("id") or 0) or None), s, ())
    ids = tuple(int(x) for x in (a.get("id"), a.get("reopen_id"), a.get("guess_id"), a.get("said_id"))
                if isinstance(x, (int, str)) and str(x).isdigit())
    if kind == "dissolve":
        return (lambda lg: lg.dissolve_interruption(_need(lg, a.get("id"), a.get("was"), err)), _was_start(a), ids)
    if kind == "update":
        src = a.get("entry")
        if not isinstance(src, dict):
            raise err("Засечка: update — нужна запись entry")
        new = Entry.from_dict(src)
        if new.start <= 0 or (new.end and new.end < new.start):
            raise err("Засечка: update — время записи кривое")

        def upd(lg: Ledger):
            eid = _need(lg, new.id, a.get("was"), err)
            fixed = replace(new, id=eid)
            lg.update(fixed)
            return lg.by_id(eid)
        return upd, min(new.start, _was_start(a) or new.start), ids + ((new.id,) if new.id else ())
    if kind == "delete":
        return (lambda lg: lg.delete(_need(lg, a.get("id"), a.get("was"), err)), _was_start(a), ids)
    if kind == "revert":
        started = [int(x) for x in (a.get("started_ids") or []) if str(x).isdigit()]
        if not started:
            raise err("Засечка: revert — нужны started_ids")
        reopen = int(a.get("reopen_id") or 0)
        return (lambda lg: lg.revert_auto_start(started, reopen), _was_start(a), tuple(started) + ids)
    if kind == "reopen":
        return (lambda lg: lg.reopen(_need(lg, a.get("id"), a.get("was"), err)), _was_start(a), ids)
    if kind == "absorb":
        return (lambda lg: lg.absorb(int(a.get("guess_id") or 0), int(a.get("said_id") or 0)), None, ids)
    if kind == "annotate":
        return (lambda lg: lg.annotate(_need(lg, a.get("id"), a.get("was"), err), _text(a.get("raw"))),
                _was_start(a), ids)
    if kind == "comment":
        return (lambda lg: lg.set_comment(_need(lg, a.get("id"), a.get("was"), err), _text(a.get("comment"))),
                _was_start(a), ids)
    if kind == "normalize":
        return (lambda lg: lg.normalize(), None, ())
    raise err(f"Засечка: нет такой правки ленты: {kind!r}")


# ── веб: «Начать», «Стоп», «Поправить» (как ZasechkaWeb телефона, только сразу) ──

def _hm(ms: int) -> str:
    return dt.datetime.fromtimestamp(ms / 1000, ledger.TZ).strftime("%H:%M")


def _at_hm(day: dt.date, hm: str, end_of_day: bool = False) -> int | None:
    m = HM.match(str(hm or "").strip())
    if not m:
        return None
    h, mi = int(m.group(1)), int(m.group(2))
    if mi > 59 or h > 24 or (h == 24 and mi != 0):
        return None
    if h == 24 or (end_of_day and h == 0 and mi == 0):
        return int(dt.datetime.combine(day + dt.timedelta(days=1), dt.time(0), ledger.TZ).timestamp() * 1000)
    return int(dt.datetime.combine(day, dt.time(h, mi), ledger.TZ).timestamp() * 1000)


def _canonical(conn, user: str, named: str) -> str:
    """Категория буква в букву, как в справочнике; нет такой — как прислали (лента пишет и свободную)."""
    for c in categories(conn, user):
        if c["name"].lower() == named.lower():
            return c["name"]
    return named


def _said(conn, at: Any, err) -> int:
    now = now_ms(conn)
    if at in (None, ""):
        return now
    t = _ts(at)
    if t is None or t.tzinfo is None:
        raise err("Засечка: at — миг ISO с поясом")
    ms = int(t.timestamp() * 1000)
    if ms > now + WEB_AHEAD_MS or ms < now - WEB_MAX_AGE_MS:
        raise err("Засечка: миг нажатия — не позже «сейчас» и не раньше 12 часов назад")
    return min(ms, now)


def _web_op(conn, user: str, kind: str, a: dict, at: Any, err) -> tuple[Callable[[Ledger], Any], int | None, tuple]:
    if kind == "web_start":
        title = _str(a.get("title"))
        if not title:
            raise err("Засечка: чем занят — пусто")
        t = _said(conn, at, err)
        cat = _canonical(conn, user, _str(a.get("category")))
        task, project = _str(a.get("task_id"), 40), _str(a.get("project_id"), 40)
        source = "task" if task else "text"

        def start(lg: Ledger):
            same = [e for e in lg.entries if abs(e.start - t) <= 1500 and e.title.strip() == title]
            if same:
                return same[0]  # повтор нажатия после обрыва: дело не заводится второй раз
            return lg.start_entry(t, title=title, category=cat, client=_str(a.get("client")), source=source,
                                  task=task, project=project)
        return start, t, ()
    if kind == "web_stop":
        t = _said(conn, at, err)
        eid = a.get("entry_id")

        def stop(lg: Ledger):
            e = lg.by_id(int(eid)) if str(eid or "").isdigit() else lg.open_entry()
            if e is None:
                raise err("Засечка: этой записи в ленте нет — её уже поправили")
            if not e.open:
                return e   # уже остановлено — ответить тем, что есть
            if t < e.start:
                raise err(f"Засечка: «{e.title}» началось позже нажатия")
            return lg.close_open(t)
        return stop, t, (int(eid),) if str(eid or "").isdigit() else ()
    if kind == "web_edit":
        try:
            day = dt.date.fromisoformat(str(a.get("day") or ""))
        except ValueError:
            raise err("Засечка: day — сутки записи ГГГГ-ММ-ДД") from None
        eid = _int(a.get("entry_id"), "entry_id", err)
        was = a.get("was") if isinstance(a.get("was"), dict) else {}
        st = a.get("set") if isinstance(a.get("set"), dict) else {}
        now = now_ms(conn)

        def edit(lg: Ledger):
            e = lg.by_id(eid)
            if e is None:
                raise err("Засечка: этой записи в ленте нет — её уже поправили")
            # Запись поменялась с тех пор, как веб её показал (правка с телефона или другой вкладки) — не затираем.
            same = (str(was.get("title", e.title)).strip() == e.title.strip()
                    and str(was.get("start", _hm(e.start))) == _hm(e.start)
                    and str(was.get("end", "" if e.open else _hm(e.end))) == ("" if e.open else _hm(e.end)))
            if not same:
                raise err("Засечка: запись уже поправили — обнови страницу")
            title = _str(st["title"]) if "title" in st else e.title
            if not title:
                raise err("Засечка: название — пусто")
            cat = _canonical(conn, user, _str(st["category"])) if "category" in st else e.category
            start = _at_hm(day, st["start"]) if "start" in st else e.start
            if start is None:
                raise err("Засечка: начало — не время")
            if "end" in st:
                if e.open:
                    raise err("Засечка: у идущего дела конца нет — его ставит «Стоп»")
                end = _at_hm(day, st["end"], end_of_day=True)
                if end is None:
                    raise err("Засечка: конец — не время")
            else:
                end = e.end
            if (title, cat, start, end) == (e.title, e.category, e.start, e.end):
                return e
            if start > now:
                raise err("Засечка: начало позже, чем «сейчас»")
            if end and end <= start:
                raise err("Засечка: конец раньше начала")
            lg.update(replace(e, title=title, category=cat, start=start, end=end, source="edit"))
            return lg.by_id(eid)
        lower = int(dt.datetime.combine(day, dt.time(0), ledger.TZ).timestamp() * 1000)
        return edit, lower, (eid,)
    raise err(f"Засечка: нет такой правки из веба: {kind!r}")


# ── операции Дел ─────────────────────────────────────────────────────────────

def op_zas(conn, user: str, op: dict, err) -> dict:
    """Правка ленты: веб (web_*) или телефон (мутаторы ZasechkaStore). Ответ — запись, версия ленты и, телефону, окно."""
    _owner_only(conn, user, err)
    kind = str(op.get("kind") or "")
    a = op.get("args") if isinstance(op.get("args"), dict) else {}
    device = _str(op.get("device"), 80)
    if kind.startswith("web_"):
        fn, lower, ids = _web_op(conn, user, kind, a, op.get("at"), err)
    else:
        fn, lower, ids = _phone_op(kind, a, err)
    got = _run(conn, user, err, fn, kind=kind, args=a, lower=lower, ids=ids, at=op.get("at"), device=device)
    res = {"zas": {"mode": "server", **got}}
    if device:
        now = now_ms(conn)
        res["zas"]["from_ms"] = now - WINDOW_DAYS * DAY_MS
        res["zas"]["window"] = window(conn, user, now)
    return res


def op_handover(conn, user: str, op: dict, err) -> dict:
    """Телефон отдаёт ленту целиком — один раз. Повтор тем же телефоном (ответ потерялся) — безвреден."""
    _owner_only(conn, user, err)
    device = _str(op.get("device"), 80)
    if not device:
        raise err("Засечка: передача — нужен device")
    st = state(conn, user, lock=True)
    if st and st["mode"] == "server":
        if st["handed_by"] == device:
            now = now_ms(conn)
            return {"zas": {"mode": "server", "lrev": st["lrev"], "handed": False, "from_ms": now - WINDOW_DAYS * DAY_MS,
                            "window": window(conn, user, now)}}
        raise err(f"Засечка: лента уже на сервере — её передал {st['handed_by']} {st['handed_at']:%d.%m %H:%M}")
    raw = op.get("entries")
    if not isinstance(raw, list) or not raw:
        raise err("Засечка: передача — пустая лента не передаётся (это поломка, а не правка)")
    if len(raw) > HANDOVER_MAX:
        raise err(f"Засечка: передача — больше {HANDOVER_MAX} записей")
    entries: dict[int, Entry] = {}
    for x in raw:
        if not isinstance(x, dict):
            raise err("Засечка: передача — запись не объект")
        e = Entry.from_dict(x)
        if e.id <= 0 or e.start <= 0 or (e.end and e.end < e.start):
            raise err(f"Засечка: передача — кривая запись {e.id}")
        entries[e.id] = e
    cats = [c for c in (op.get("categories") or []) if isinstance(c, dict) and str(c.get("name") or "").strip()]
    clients = [str(c).strip() for c in (op.get("clients") or []) if str(c or "").strip()]
    # Команды веба телефону, не взятые до передачи, больше не нужны: веб пишет сам.
    conn.execute("UPDATE crm.zasechka_cmds SET status = 'cancelled', note = 'лента перешла на сервер — команда не нужна' "
                 "WHERE owner_id = %s AND status IN ('wait', 'taken')", (user,))
    conn.execute("DELETE FROM crm.zas_entries WHERE owner_id = %s", (user,))
    for e in entries.values():
        _put(conn, user, e)
    last = max(entries)
    conn.execute(
        "INSERT INTO crm.zas_state (owner_id, mode, handed_by, handed_at, handed_entries, clients, categories, "
        "last_id, lrev) VALUES (%s, 'server', %s, now(), %s, %s, %s, %s, 1) "
        "ON CONFLICT (owner_id) DO UPDATE SET mode = 'server', handed_by = EXCLUDED.handed_by, handed_at = now(), "
        "handed_entries = EXCLUDED.handed_entries, clients = EXCLUDED.clients, categories = EXCLUDED.categories, "
        "last_id = EXCLUDED.last_id, lrev = crm.zas_state.lrev + 1, updated_at = now()",
        (user, device, len(entries), Jsonb(clients), Jsonb(cats), last))
    now = now_ms(conn)
    days = {ledger.day_of(e.start) for e in entries.values()}
    published = _publish(conn, user, days, now)
    publish_reference(conn, user)
    st = state(conn, user)
    conn.execute("INSERT INTO crm.zas_ops (owner_id, kind, args, device, via, result) "
                 "VALUES (%s, 'handover', %s, %s, crm.via(), %s)",
                 (user, Jsonb({"entries": len(entries), "categories": len(cats), "clients": len(clients)}), device,
                  Jsonb({"days": len(days), "published": published})))
    return {"zas": {"mode": "server", "lrev": st["lrev"], "handed": True, "entries": len(entries), "days": len(days),
                    "from_ms": now - WINDOW_DAYS * DAY_MS, "window": window(conn, user, now)}}


def for_sync(conn, user: str, known: Any = None) -> dict | None:
    """Поле zas синка: режим ленты и, когда версия сменилась, окно. Не владельцу — None (поля нет)."""
    if not digest.is_owner(conn, user):
        return None
    st = state(conn, user)
    if not st or st["mode"] != "server":
        return {"mode": "phone"}   # сервер умеет ленту: новая сборка телефона передаст её
    res: dict[str, Any] = {"mode": "server", "lrev": st["lrev"]}
    try:
        k = int(known) if known not in (None, "") else -1
    except (TypeError, ValueError):
        k = -1
    if k != st["lrev"]:
        now = now_ms(conn)
        res["from_ms"] = now - WINDOW_DAYS * DAY_MS
        res["entries"] = window(conn, user, now)
    return res


def tick(conn) -> dict | None:
    """Раз в минуту: нормализовать ленту владельца на «сейчас» (живой заполнитель, полночь, карантин дыр)."""
    owner = conn.execute("SELECT crm.owner_id() AS o").fetchone()["o"]
    if not owner:
        return None
    st = state(conn, owner)
    if not st or st["mode"] != "server":
        return None
    return _run(conn, owner, ValueError, lambda lg: lg.normalize(), kind="tick", args={}, log=False)


def register(handlers: dict, err) -> None:
    handlers["zas.op"] = lambda conn, user, op: op_zas(conn, user, op, err)
    handlers["zas.handover"] = lambda conn, user, op: op_handover(conn, user, op, err)
