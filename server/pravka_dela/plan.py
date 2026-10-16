"""«Сейчас на завтра» — кандидаты правилом, Саша отмечает до пяти (шаг 1 петли этапа 4, 15.10.2026, dela_0034).

Саша (ответ 1а, 15.10.2026): «с 19:00 в „Подскажи“ до семи кандидатов „Сейчас на завтра“, у каждого — почему и
откуда; Саша отмечает до пяти; в сводке 20:03 — строка „Завтра в Сейчас: предложено N [Открыть]“». Тем же вечером
он перенёс заход Пн–Чт на 18:00 (почему — asks.py). «Сейчас» ставит только Саша — система предлагает правилом и ничего
не ставит молча: молчание до 12:00 следующего дня — ничего.

Как устроено (подробно — docs/dela-server.md, решение 22; контракт — server/contract/dela-plan.json):
- **Слот** — время вечернего захода из asks.py (Свод asks.rhythm): Пн–Чт 18:00 на завтра, Пт 14:00 (с 15.11 по 15.01 —
  13:30) на понедельник, Сб–Вс — ничего. Один прогон в день: пачка plan:<день> уже в базе — второй прогон ничего не
  пишет (`run`, под замком; память crm.state plan.run_day — только чтобы не звать его каждую минуту); служба лежала —
  догонка 4 часа (LATE). op_id — uuid5 от source_ref.
- **Кандидаты** (`candidates`) — свои открытые дела владельца, не цели; пять корзин (`BASKETS`), у каждой своя квота;
  места — по весам судьи (`PICK`), показ — `SHOW`. Не берутся: уже стоит на этот день, уже ждёт кандидат, «Не надо»
  за 3 дня, два прогона подряд без ответа — пауза 3 дня, третий день подряд в «Сейчас» — строка stuck, не кандидат.
- **Запись** — предложением от владельца (actor svc:plan, via plan) через store.apply_ops, как krug_auto: у дела — kind
  update, без дела (сделка без шага, «напомнить троим») — create. Варианты: «☆ На завтра» (focus_on), у обещаний и
  несделанного — «✓ Уже сделал» (done), «Не надо» (reject). Гаснут в 12:00 дня, на который предложены (`sweep`).
- **Снимок** (`snap`, tasks.now_days): 11:00, 18:00 перед прогоном, 23:55; пропущенные дни (до 3) — догонкой.

Claude здесь не зовётся ($0): модуль llm не импортирует. Свод plan.tomorrow — сколько и пороги (FACTORY по полю).
"""

from __future__ import annotations

import datetime as dt
import logging
import uuid
from typing import Any

from psycopg.types.json import Jsonb

from . import asks, crm, db, krug, store
from .meetings import MSK

log = logging.getLogger("dela.plan")

SOURCE = "plan"
ACTOR = "svc:plan"
STATE_KEY = "plan"
SVOD_KEY = "plan.tomorrow"
# Комп спал в слот — догоняем до 4 часов после него (Пн–Чт до 22:00), а не утром: утром кандидаты уже гаснут. С 15.10
# заход Пн–Чт в 18:00, а не в 19:00; окно догонки сдвинулось вместе с ним, а не растянуто до прежних 23:00: Саша
# перенёс заход раньше, потому что поздний вечер у него семейный, — пачке, опоздавшей больше чем на 4 часа, там тем
# более не место; и LATE общий с пятницей (14:00 → 18:00, зимой 13:30 → 17:30) — пять часов увели бы её догонку ещё
# на час за свечи.
LATE = dt.timedelta(hours=4)
SNAPS = (("11", 11, 0), ("18", 18, 0), ("2355", 23, 55))  # 18:00 — «Сейчас» дня до прогона (и в дни без прогона)
SNAP_BACK = 3                      # пропущенные дни снимка — до трёх назад
STATS_DAYS = 14
HISTORY_DAYS = 30
NS = uuid.UUID("5e4a2b1c-0d3e-4f5a-8b6c-7d8e9f0a1b2c")  # пространство uuid5 для op_id прогонов плана
PLAN_LOCK = 0x5E4B                 # первый ключ замка прогона (второй — владелец), рядом с store.NOW_LOCK

# Корзины: дело — в ПЕРВОЙ своей по этому порядку; места — по весам судьи (резерв Круга → обещания → сделка →
# несделанное → «напомнить»), квоты не перетекают; на экране — сначала несделанное сегодня, Круг — последним.
BASKETS = ("krug", "carry", "owed", "deal", "nudge")
PICK = ("krug", "owed", "deal", "carry", "nudge")
SHOW = ("carry", "owed", "deal", "nudge", "krug")
DONE_BASKETS = ("owed", "carry")   # у них — «✓ Уже сделал» («сделал?» судьи §3.8)

FACTORY: dict[str, Any] = {
    "expires": "12:00",
    "max": 7, "friday_max": 3,
    "quota": {"krug": 1, "carry": 2, "owed": 3, "deal": 1, "nudge": 1},
    "nudge_people": 3,
    "krug_idle_days": 14, "carry_max": 2, "cooldown_days": 3, "silent_pause": [2, 3],
    "take": {"busy_from": 3, "busy": 3, "free": 5},
    "words": {"yes": "☆ На завтра", "yes_friday": "☆ На понедельник", "done": "✓ Уже сделал", "no": "Не надо"},
}
REASON_NO = "план: не надо"
REASON_SILENT = "план: молчание до 12:00"
REASON_CLOSED = "план: дело закрыто"
REASON_ALREADY = "план: уже в «Сейчас»"
_warned: set[str] = set()


# ── Свод plan.tomorrow поверх заводского ─────────────────────────────────

def _int(v: Any, lo: int = 0, hi: int = 50) -> int | None:
    return v if isinstance(v, int) and not isinstance(v, bool) and lo <= v <= hi else None


def norm_config(value: Any) -> tuple[dict, list[str]]:
    """Свод поверх заводского по ключу; кривое — заводское по этому ключу и слово, что не так."""
    out = {k: (dict(v) if isinstance(v, dict) else list(v) if isinstance(v, list) else v) for k, v in FACTORY.items()}
    bad: list[str] = []
    if value is None:
        return out, bad
    if not isinstance(value, dict):
        return out, [f"{SVOD_KEY}: не словарь"]
    for k, v in value.items():
        if k not in FACTORY:
            continue
        f = FACTORY[k]
        if k == "expires":
            hm = asks._hm(v)
            out[k] = f"{hm[0]:02d}:{hm[1]:02d}" if hm else f
            bad += [] if hm else [f"{k}={v!r}"]
        elif isinstance(f, dict):
            if not isinstance(v, dict):
                bad.append(f"{k}={v!r}")
                continue
            for kk, vv in v.items():
                if kk not in f:
                    continue
                ok = (isinstance(vv, str) and 0 < len(vv.strip()) <= 40) if k == "words" else _int(vv) is not None
                if ok:
                    out[k][kk] = vv.strip() if k == "words" else vv
                else:
                    bad.append(f"{k}.{kk}={vv!r}")
        elif isinstance(f, list):
            if isinstance(v, list) and len(v) == 2 and all(_int(x, 1, 30) is not None for x in v):
                out[k] = list(v)
            else:
                bad.append(f"{k}={v!r}")
        elif _int(v, 0, 60) is not None:
            out[k] = v
        else:
            bad.append(f"{k}={v!r}")
    return out, bad


def config(conn, owner: str) -> dict:
    row = conn.execute("SELECT value FROM crm.svod WHERE owner_id = %s AND key = %s", (owner, SVOD_KEY)).fetchone()
    out, bad = norm_config((row or {}).get("value"))
    for b in bad:
        if b not in _warned:
            _warned.add(b)
            log.warning("Свод %s: %s — беру заводское", SVOD_KEY, b)
    return out


# ── Дни ─────────────────────────────────────────────────────────────────

def next_day(today: dt.date) -> dt.date:
    """На какой день план вечера: Пн–Чт — завтра, Пт–Вс — понедельник."""
    return today + dt.timedelta(days=1 if today.weekday() < 4 else 7 - today.weekday())


def run_day(for_date: dt.date) -> dt.date:
    """В какой день вечером предлагают на for_date: понедельник — пятница, иначе — накануне."""
    return for_date - dt.timedelta(days=3 if for_date.weekday() == 0 else 1)


def _expires(for_date: dt.date, cfg: dict) -> dt.datetime:
    h, m = asks._hm(cfg["expires"]) or (12, 0)
    return dt.datetime(for_date.year, for_date.month, for_date.day, h, m, tzinfo=MSK)


def _ddmm(v: Any) -> str:
    d = v.astimezone(MSK).date() if isinstance(v, dt.datetime) else v if isinstance(v, dt.date) else \
        dt.date.fromisoformat(str(v)[:10])
    return f"{d:%d.%m}"


def days_word(n: int) -> str:
    n = abs(int(n))
    if n % 10 == 1 and n % 100 != 11:
        return f"{n} день"
    if 2 <= n % 10 <= 4 and not 12 <= n % 100 <= 14:
        return f"{n} дня"
    return f"{n} дней"


def label(for_date: dt.date) -> str:
    return store.day_label(for_date)


# ── Кто такие кандидаты ─────────────────────────────────────────────────

# Сколько дней подряд дело в «Сейчас» дня: строка снимка, а нет её (снимок ещё не шёл) — 1 + carried прошлого дня со
# снимком, где оно не сделано. Прошлый день — вчера; у понедельника — пятница и выходные.
CARRIED = ("coalesce(n.carried, 1 + coalesce((SELECT p.carried FROM tasks.now_days p WHERE p.owner_id = %(o)s "
           "AND p.task_id = t.id AND p.day >= %(lo)s::date AND p.day < %(day)s::date AND NOT p.done "
           "ORDER BY p.day DESC LIMIT 1), 0))")


def _prev_lo(day: dt.date) -> dt.date:
    return day - dt.timedelta(days=3 if day.weekday() == 0 else 1)


def in_now(conn, owner: str, day: dt.date) -> list[dict]:
    """Открытые дела в «Сейчас» дня с тем, какой это день подряд (carried)."""
    return conn.execute(
        "SELECT t.id, t.num, t.title, t.owed, t.ball, t.grp, t.created_at, t.source, t.source_ref, t.person_short, "
        f"t.due_date, t.focus_on, t.why_id, t.why_goal, t.why_title, {CARRIED} AS carried "
        "FROM tasks.v_tasks t LEFT JOIN tasks.now_days n ON n.owner_id = %(o)s AND n.day = %(day)s::date AND n.task_id = t.id "
        f"WHERE t.owner_id = %(o)s AND t.status = 'open' AND NOT t.goal AND {store.DEED_KIND_IF} AND t.focus_on = %(day)s::date "
        "ORDER BY t.owed DESC, t.created_at, t.num",
        {"o": owner, "day": day.isoformat(), "lo": _prev_lo(day).isoformat()}).fetchall()


def _since(conn, owner: str, task_id: Any, day: dt.date, carried: int) -> dt.date:
    """С какого дня дело в «Сейчас» подряд: carried − 1 строк снимка назад (их дни — с выходными)."""
    rows = conn.execute("SELECT day FROM tasks.now_days WHERE owner_id = %s AND task_id = %s AND day < %s "
                        "ORDER BY day DESC LIMIT %s", (owner, task_id, day, max(0, carried - 1))).fetchall()
    return rows[-1]["day"] if rows and len(rows) == carried - 1 else day


def _history(conn, owner: str, today: dt.date) -> list[dict]:
    return conn.execute(
        "SELECT id, kind, task_id, status, reason, decided_at, created_at, batch_ref, result, "
        "payload ->> 'basket' AS basket, payload ->> 'for_date' AS for_date, payload ->> 'deal_id' AS deal_id, "
        "payload -> 'nudge_ids' AS nudge_ids FROM tasks.suggestions "
        "WHERE source = 'plan' AND for_user = %s AND created_at > %s::date - %s ORDER BY created_at",
        (owner, today, HISTORY_DAYS)).fetchall()


def _subjects(s: dict) -> list[str]:
    """О чём кандидат: дело (t:id), сделка (d:id) или люди «напомнить» (t:id каждого «жду»)."""
    if s.get("task_id"):
        return [f"t:{s['task_id']}"]
    if s.get("deal_id"):
        return [f"d:{s['deal_id']}"]
    return [f"t:{x}" for x in (s.get("nudge_ids") or []) if x]


def _silent(s: dict) -> bool:
    return s["status"] == "expired" and (s.get("reason") or REASON_SILENT) == REASON_SILENT


def blocked(hist: list[dict], now: dt.datetime, cfg: dict) -> set[str]:
    """Кого сейчас не предлагать: уже ждёт кандидат; «Не надо» за cooldown_days; молчал silent_pause[0] прогонов
    подряд — пауза silent_pause[1] дней (иначе одни и те же три «Отбить» вставали бы каждый вечер — против «не
    подталкивай по нескольку раз»). Молчание один раз — может прийти снова."""
    out: set[str] = set()
    runs = sorted({s["batch_ref"] for s in hist if s.get("batch_ref")}, key=lambda r: r.split(":", 1)[-1])
    by: dict[str, list[dict]] = {}
    for s in hist:
        for k in _subjects(s):
            by.setdefault(k, []).append(s)
    times, pause_days = cfg["silent_pause"]
    for k, items in by.items():
        if any(s["status"] == "pending" for s in items):
            out.add(k)
            continue
        if any(s["status"] == "rejected" and s.get("decided_at")
               and now - s["decided_at"] < dt.timedelta(days=cfg["cooldown_days"]) for s in items):
            out.add(k)
            continue
        last = sorted(items, key=lambda s: s.get("batch_ref") and s["batch_ref"].split(":", 1)[-1] or "")[-times:]
        if len(last) == times and all(_silent(s) for s in last) and last[-1].get("decided_at") \
                and now - last[-1]["decided_at"] < dt.timedelta(days=pause_days):
            idx = [runs.index(s["batch_ref"]) for s in last if s.get("batch_ref") in runs]
            if len(idx) == times and idx == list(range(idx[0], idx[0] + times)):
                out.add(k)
    return out


# Живая сделка у дела: своя (не архив) или единственная живая сделка клиента (crm.only_deal, правило 12). Подзапросом,
# а не JOIN: правила дел (OWED_HOT_IF) пишут колонки без имени таблицы, и created_at сделки их бы перепутал.
LIVE_DEAL = ("(EXISTS (SELECT 1 FROM crm.deals d WHERE d.id = t.deal_id AND d.stage <> 'archive') "
             "OR (t.deal_id IS NULL AND t.project_id IS NOT NULL AND crm.only_deal(t.project_id) IS NOT NULL))")


TASK_COLS = ("t.id, t.num, t.title, t.owed, t.ball, t.grp, t.created_at, t.source, t.source_ref, t.person_id, "
             "t.person_short, t.due_date, t.focus_on, t.project_id, t.deal_id, t.waiting_since, t.dirs, t.why_id, "
             "t.why_goal, t.why_title")

# «Потом» (захват, back_on; поезд 16.10.2026, стык с шагом 0): Саша сам отложил дело — план его не подталкивает. Не
# кандидат: (1) дело ещё отложено на день плана (back_on позже него), если только срок не горит к этому дню — срок сильнее
# «потом», как в «Сегодня»; (2) дело «Вернулось» — своё, не «Отбить», без срока, с днём возврата (capture.BACK_IF): у него
# свой вопрос на экране в день возврата («В «Сейчас» · Сегодня · Ещё потом · Сделано»), нетронутое ночью уходит на
# понедельник (capture.back_shift) — второй вопрос о том же вечером был бы «подталкиванием по нескольку раз».
# Вернувшиеся «Отбить» и «жду» в «Вернулось» не попадают — кандидаты как все. «Несделанное сегодня» (carry) правило
# не трогает: дело в «Сейчас» Саша поставил сам («В «Сейчас»» у «Вернулось» «потом» снимает).
LATER_OK = ("NOT (t.back_on IS NOT NULL AND ((t.back_on > %(for)s::date AND (t.due_date IS NULL OR t.due_date > %(for)s::date)) "
            "OR (t.ball = 'mine' AND NOT t.owed AND t.due_date IS NULL)))")


# Дела внутри дела (правило 27; поезд 16.10.2026, стык с вагоном 2). «Зачем» дела — цель дерева или обычное дело, внутри
# которого оно лежит. Строки Круга хозяина: id → («Зачем», цель ли это).
def _links(conn, owner: str) -> dict[str, tuple[str | None, bool]]:
    return {str(r["id"]): (str(r["why_id"]) if r["why_id"] else None, bool(r["goal"])) for r in conn.execute(
        "SELECT id, why_id, goal FROM tasks.krug WHERE kind = 'task' AND owner_id = %s", (owner,)).fetchall()}


def _ups(links: dict, tid: str) -> set[str]:
    """Дела, внутри которых лежит это дело, — вверх по «Зачем» до цели (цель — не дело: «внутри цели» — шаг к ней)."""
    out: set[str] = set()
    x = links.get(tid, (None, False))[0]
    while x and x not in out and len(out) < 60:
        up = links.get(x)
        if up and up[1]:
            break
        out.add(x)
        x = up[0] if up else None
    return out


def _pools(conn, owner: str, today: dt.date, for_date: dt.date, cfg: dict, now_rows: list[dict],
           links: dict | None = None) -> dict[str, list[dict]]:
    p = {"o": owner, "today": today.isoformat(), "for": for_date.isoformat()}
    base = (f"t.owner_id = %(o)s AND t.status = 'open' AND NOT t.goal AND {store.DEED_KIND_IF} "
            f"AND t.focus_on IS DISTINCT FROM %(for)s::date AND {LATER_OK}")
    pools: dict[str, list[dict]] = {}
    # Резерв Круга: шаг к цели (своих открытых шагов нет), не «Отбить», не «жду», не в «Сейчас» сегодня (то — carry),
    # из направления, где за krug_idle_days не закрыто ни одного дела по главному направлению (dirs[1], как
    # krug.movement). Старше — раньше. Простой — тем же правилом, что движение Круга: закрытое напоминание захвата —
    # движение (krug.movement и дерево его считают; поезд 16.10.2026), иначе план писал бы «направление стоит 16 дней»
    # там, где Круг показывает движение.
    steps = conn.execute(
        f"SELECT {TASK_COLS} FROM tasks.v_tasks t WHERE {base} AND t.why_id IS NOT NULL AND t.steps_open = 0 "
        "AND NOT t.owed AND t.ball <> 'waiting' AND t.focus_on IS DISTINCT FROM %(today)s::date "
        "AND cardinality(t.dirs) > 0 ORDER BY t.created_at, t.num", p).fetchall()
    # Шаг к цели — вверх по «Зачем» есть цель (krug.toward_goal, как «Моё»): дело внутри обычного дела, над которым целей
    # нет (драфт для договора клиенту), — не резерв Круга, хоть направление оно и наследует (поезд 16.10.2026).
    toward = krug.toward_goal(links if links is not None else _links(conn, owner))
    steps = [r for r in steps if str(r["id"]) in toward]
    last = {r["dir"]: r["last"] for r in conn.execute(
        "SELECT t.dirs[1] AS dir, max((t.completed_at AT TIME ZONE 'Europe/Moscow')::date) AS last "
        "FROM tasks.v_tasks t WHERE t.owner_id = %(o)s AND t.status = 'done' AND cardinality(t.dirs) > 0 "
        "AND t.completed_at > %(today)s::date - 400 GROUP BY 1", p).fetchall()}
    names = {d["key"]: d.get("name") or d["key"] for d in krug.directions(conn, owner)}
    pools["krug"] = []
    for r in steps:
        main = r["dirs"][0]
        done = last.get(main)
        idle = (today - done).days if done else None
        if done is None or idle >= cfg["krug_idle_days"]:
            pools["krug"].append({**r, "dir": main, "dir_name": names.get(main, main), "idle": idle})
    # Несделанное сегодня — что стоит в «Сейчас» сегодня и открыто: «Отбить» первым, потом старше. Третий день подряд
    # и дальше (carried > carry_max) — не кандидат, а строка stuck.
    pools["carry"] = [r for r in now_rows if r["carried"] <= cfg["carry_max"]]
    # Обещания, которые горят к дню: живая сделка → дольше ждёт → срок.
    owed = conn.execute(
        f"SELECT {TASK_COLS}, {LIVE_DEAL} AS live_deal FROM tasks.v_tasks t "
        f"WHERE {base} AND {store.OWED_HOT_IF('%(for)s::date')}", p).fetchall()
    pools["owed"] = sorted(owed, key=lambda r: (not r["live_deal"], r["created_at"], r["due_date"] or dt.date.max))
    # Сделка без шага — только с правилом шагов сделки этапа 2 (deal_steps): до него «нет дела» было наполовину ложным.
    pools["deal"] = []
    if "deal_steps" in store.FEATURES:
        live = [d for d in crm.deals(conn, "d.stage <> 'archive'") if not d.get("open_tasks")]
        pools["deal"] = sorted(live, key=lambda d: (-(d["quiet_days"] if d["quiet_days"] is not None else 10 ** 6),
                                                    d["name"]))
    # «Жду» дольше недели (то же правило, что week.waiting_stale) — по людям: до nudge_people разных одной строкой.
    pools["nudge"] = conn.execute(
        f"SELECT {TASK_COLS} FROM tasks.v_tasks t WHERE {base} AND t.person_id IS NOT NULL "
        f"AND {store.WAITING_STALE_IF} ORDER BY t.waiting_since, t.created_at", p).fetchall()
    return pools


def candidates(conn, owner: str, today: dt.date, for_date: dt.date, cfg: dict,
               now: dt.datetime | None = None) -> dict:
    """Кандидаты на for_date: {items: [...] в порядке показа, stuck: [...]}. Только правило, без записи."""
    now = now or dt.datetime.now(dt.timezone.utc)
    now_rows = in_now(conn, owner, today)
    links = _links(conn, owner)
    pools = _pools(conn, owner, today, for_date, cfg, now_rows, links)
    stop = blocked(_history(conn, owner, today), now, cfg)
    stuck = [{"id": str(r["id"]), "num": r["num"], "title": r["title"], "days": r["carried"]}
             for r in now_rows if r["carried"] > cfg["carry_max"]]
    # Третий день подряд в «Сейчас» — не кандидат ни в одной корзине (ни «горящим» обещанием): строка stuck.
    taken: set[str] = {f"t:{x['id']}" for x in stuck}
    baskets: dict[str, list[dict]] = {b: [] for b in BASKETS}
    for b in BASKETS:
        for r in pools[b]:
            if b == "deal":
                key = f"d:{r['id']}"
                if key in stop:
                    continue
                baskets[b].append({"basket": b, "kind": "create", "id": str(r["id"]), "deal": r})
                continue
            key = f"t:{r['id']}"
            if key in taken or key in stop:
                continue
            if b == "nudge":
                baskets[b].append(r)  # людей собираем ниже — одной строкой
                continue
            taken.add(key)
            baskets[b].append({"basket": b, "kind": "update", "id": str(r["id"]), "task": r})
    # «Напомнить троим»: первые по сроку ожидания «жду» до nudge_people разных людей.
    people: dict[Any, dict] = {}
    for r in baskets["nudge"]:
        if f"t:{r['id']}" in taken or r["person_id"] in people:
            continue
        people[r["person_id"]] = r
        if len(people) >= cfg["nudge_people"]:
            break
    baskets["nudge"] = [{"basket": "nudge", "kind": "create", "id": str(next(iter(people.values()))["id"]),
                         "nudge": list(people.values())}] if people else []
    limit = cfg["friday_max"] if (for_date - today).days > 1 else cfg["max"]
    # Из одной семьи дел внутри дела (правило 27) — один кандидат: отметил родителя — его открытые дела внутри встанут в
    # «Сейчас» над ним (в пять не считаются, renderNow / DelaSteps.inner), отметил дело внутри — это и есть первый шаг.
    # Кто из семьи — решает порядок мест судьи (PICK), место в квоте не пропадает: берётся следующий в корзине.
    picked: list[dict] = []
    chosen: set[str] = set()   # выбранные дела
    above: set[str] = set()    # и все, внутри чего они лежат
    for b in PICK:
        n = 0
        for it in baskets[b]:
            if n >= cfg["quota"].get(b, 0) or len(picked) >= limit:
                break
            if it["kind"] == "update":
                ups = _ups(links, it["id"])
                if it["id"] in above or ups & chosen:
                    continue
                chosen.add(it["id"])
                above |= ups
            picked.append(it)
            n += 1
    picked.sort(key=lambda it: SHOW.index(it["basket"]))
    return {"items": picked, "stuck": stuck, "limit": limit}


# ── Почему и откуда — словами ───────────────────────────────────────────

def _due_words(due: dt.date | None, today: dt.date, for_date: dt.date) -> str | None:
    if not due:
        return None
    if due < today:
        return f"просрочено {days_word((today - due).days)}"
    if due == today:
        return "срок сегодня"
    if due == today + dt.timedelta(days=1):
        return "срок завтра"
    return f"срок {store.day_label(due)}"


def _inside(t: dict) -> str | None:
    """«для «Прислать договор»» — дело внутри обычного дела (правило 27): без родителя «Набросать драфт» не понять."""
    return f"для «{t['why_title']}»" if t.get("why_id") and not t.get("why_goal") and t.get("why_title") else None


def why_line(conn, owner: str, it: dict, today: dt.date, for_date: dt.date) -> str:
    b = it["basket"]
    if b in ("krug", "carry", "owed"):
        return " · ".join(x for x in (_why(conn, owner, it, today, for_date), _inside(it["task"])) if x)
    return _why(conn, owner, it, today, for_date)


def _why(conn, owner: str, it: dict, today: dt.date, for_date: dt.date) -> str:
    b = it["basket"]
    if b == "deal":
        d = it["deal"]
        q = d.get("quiet_days")
        return f"сделка без шага {q} дн." if q else "сделка без шага"
    if b == "nudge":
        return "жду дольше недели: " + ", ".join(
            f"{r['person_short'] or 'без имени'} {(today - r['waiting_since']).days} дн." for r in it["nudge"])
    t = it["task"]
    if b == "krug":
        idle = t.get("idle")
        return (f"направление «{t['dir_name']}» стоит {days_word(idle)}" if idle is not None
                else f"направление «{t['dir_name']}» — закрытых дел ещё не было")
    if b == "carry":
        since = _since(conn, owner, t["id"], today, t["carried"])
        n = t["carried"] + 1
        parts = [f"в «Сейчас» с {_ddmm(since)} — переносится {n}-й день"]
        if t.get("owed"):
            parts.append((f"{t['person_short']} ждёт" if t.get("person_short") else "ждут от тебя"))
        return " · ".join(parts)
    # owed
    made = t["created_at"].astimezone(MSK).date()
    waits = (today - made).days
    who = t.get("person_short")
    parts = [(f"{who} ждёт" if who else "ждёт") + (f" {days_word(waits)}" if waits > 0 else " с сегодня")]
    due = _due_words(t.get("due_date"), today, for_date)
    if due:
        parts.append(due)
    if t.get("live_deal"):
        parts.append("сделка живая")
    return " · ".join(parts)


# Откуда — словами Саши, а не кодом источника: дело, заведённое из кандидата плана, на следующий вечер пришло бы с
# «откуда: plan 15.10». Незнакомый код — «руками» (запас), не сам код.
_FROM_SOURCE = {"voice": "сказал сам", "manual": "руками", "web": "руками", "user": "руками", "meeting": "встреча",
                "telegram": "Telegram", "userbot": "Telegram", "bot": "ответ в Telegram", "mcp": "Claude", "import": "перенос",
                "plan": "план на завтра", "phone": "телефон", "krug": "разборщик Круга", "contacts": "телефонная книжка"}
FROM_ELSE = "руками"


def from_line(conn, it: dict) -> str:
    """Откуда — по store.task_origin: «встреча «Клиент: тема» 13.10», «Telegram 12.10», «сказал сам 10.10», «руками
    09.10»; у сделки — «Воронка», у «жду» — «Мониторить», у Круга — «Круг жизни»."""
    b = it["basket"]
    if b in ("deal", "nudge", "krug"):
        return {"deal": "Воронка", "nudge": "Мониторить", "krug": "Круг жизни"}[b]
    t = it["task"]
    o = store.task_origin(conn, t)
    if o and o["kind"] == "dictation":
        return f"сказал сам {_ddmm(o['at'] or t['created_at'])}"
    if o and o["kind"] == "suggestion":
        if o["source"] == "meeting":
            head, _, dm = str(o.get("batch_title") or "").rpartition(" · ")
            name = head or o.get("batch_title")
            return (f"встреча «{name}» " if name else "встреча ") + (dm.strip() or _ddmm(o["at"]))
        if o["source"] in ("telegram", "userbot"):
            return f"Telegram {_ddmm(o['at'])}"
        return f"{_FROM_SOURCE.get(o['source'], FROM_ELSE)} {_ddmm(o['at'])}"
    return f"{_FROM_SOURCE.get(t.get('source'), FROM_ELSE)} {_ddmm(t['created_at'])}"


def capacity(conn, owner: str, for_date: dt.date, cfg: dict) -> dict:
    """«Завтра встреч ~N — возьми K»: N — среднее рабочих встреч этого дня недели за 4 недели (crm.meetings, sphere
    work); K = take.busy при N ≥ take.busy_from, иначе take.free. Календаря на сервере нет — только база встреч Дел."""
    days = [for_date - dt.timedelta(days=7 * k) for k in range(1, 5)]
    n = conn.execute(
        "SELECT count(*) AS n FROM crm.meetings WHERE owner_id = %s AND sphere = 'work' AND archived_at IS NULL "
        "AND (started_at AT TIME ZONE 'Europe/Moscow')::date = ANY(%s::date[])", (owner, days)).fetchone()["n"]
    avg = int(n / len(days) + 0.5)
    t = cfg["take"]
    return {"meetings": avg, "take": t["busy"] if avg >= t["busy_from"] else t["free"]}


# ── Прогон ──────────────────────────────────────────────────────────────

def _owner(url: str) -> str | None:
    with db.session(url, "system", via=SOURCE) as conn:
        return conn.execute("SELECT crm.owner_id() AS id").fetchone()["id"]


def _batch_title(for_date: dt.date, cap: dict) -> str:
    return f"Сейчас на {label(for_date)} · встреч ~{cap['meetings']} — возьми {cap['take']}"


def build(conn, owner: str, today: dt.date, for_date: dt.date, cfg: dict, now: dt.datetime) -> dict:
    """Кандидаты со словами и готовые операции suggestion.create — без записи (dry печатает это же)."""
    got = candidates(conn, owner, today, for_date, cfg, now)
    cap = capacity(conn, owner, for_date, cfg)
    weekend = (for_date - today).days > 1
    w = cfg["words"]
    yes = {"label": w["yes_friday"] if weekend else w["yes"], "focus_on": for_date.isoformat()}
    no = {"label": w["no"], "reject": True, "reason": REASON_NO}
    title = _batch_title(for_date, cap)
    ops, out = [], []
    for n, it in enumerate(got["items"], 1):
        why, frm = why_line(conn, owner, it, today, for_date), from_line(conn, it)
        ref = f"plan:{for_date.isoformat()}:{it['basket']}:{it['id']}"
        opts = [yes, *([{"label": w["done"], "done": True}] if it["basket"] in DONE_BASKETS else []), no]
        payload: dict[str, Any] = {"basket": it["basket"], "for_date": for_date.isoformat(), "focus_on": for_date.isoformat(),
                                   "why": why, "from": frm, "order": n,
                                   "ask": f"В «Сейчас» на {label(for_date)}? {why} ({frm})", "options": opts}
        s: dict[str, Any] = {"kind": it["kind"], "source": SOURCE, "source_ref": ref, "batch_ref": f"plan:{for_date.isoformat()}",
                             "batch_title": title, "expires_at": _expires(for_date, cfg).isoformat()}
        if it["kind"] == "update":
            s["task_id"] = it["id"]
            name = it["task"]["title"]
        elif it["basket"] == "deal":
            d = it["deal"]
            name = f"Решить следующий шаг: {d['name']}"
            payload.update({"title": name, "project_id": str(d["project_id"]), "deal_id": str(d["id"]), "ball": "mine"})
        else:
            who = [r["person_short"] or r["title"] for r in it["nudge"]]
            name = "Напомнить: " + ", ".join(who)
            payload.update({"title": name, "ball": "mine", "nudge_ids": [str(r["id"]) for r in it["nudge"]]})
        s["payload"] = payload
        ops.append({"op": "suggestion.create", "op_id": str(uuid.uuid5(NS, ref)), "suggestion": s})
        out.append({"basket": it["basket"], "title": name, "why": why, "from": frm, "ref": ref})
    return {"for_date": for_date.isoformat(), "label": label(for_date), "title": title, **cap, "limit": got["limit"],
            "items": out, "stuck": got["stuck"], "ops": ops}


def offered(conn, owner: str, for_date: dt.date) -> int:
    """Сколько кандидатов уже предложено на этот день — пачка plan:<день> в любом статусе (ждёт, отмечено, «не надо»,
    погасло)."""
    return conn.execute("SELECT count(*) AS n FROM tasks.suggestions WHERE source = 'plan' AND for_user = %s "
                        "AND batch_ref = %s", (owner, f"plan:{for_date.isoformat()}")).fetchone()["n"]


def run(url: str, now: dt.datetime | None = None, dry: bool = False, for_date: dt.date | None = None) -> dict:
    """Прогон плана: кандидаты на for_date (с завода — следующий будний после сегодня) в «Подскажи». dry — только
    посчитать и вернуть, ничего не писать (команда plan без run).

    Один прогон на день — правдой базы, а не памятью tick: пачка этого дня уже есть (в любом статусе) — ничего не пишет и
    отвечает skipped. Иначе второй прогон (plan run руками, plan --for на спланированный день, служба упала между записью
    кандидатов и памятью run_day) добавлял к семи ещё: blocked() держит только ждущих, отказанных и дважды молчавших, а
    предел семи считался заново. Прогоны одного владельца — по одному (замок PLAN_LOCK до конца записи): служба и команда
    в одну секунду двух пачек не положат. made — сколько строк пачки легло, а не сколько op_id ответили «да»."""
    now = now or dt.datetime.now(dt.timezone.utc)
    today = now.astimezone(MSK).date()
    owner = _owner(url)
    if not owner:
        return {"skipped": "нет владельца Дел"}
    fd = for_date or next_day(today)
    with db.session(url, owner, ACTOR, SOURCE) as conn:
        if not dry:
            conn.execute("SELECT pg_advisory_xact_lock(%s, hashtext(%s::text))", (PLAN_LOCK, owner))
        before = offered(conn, owner, fd)
        if before and not dry:
            return {"skipped": f"на {label(fd)} уже предложено {before} — второй прогон ничего не пишет",
                    "for_date": fd.isoformat(), "label": label(fd), "offered": before}
        out = build(conn, owner, today, fd, config(conn, owner), now)
        if dry:
            return {**out, "dry": True, "offered": before}
        # Пишет apply_ops своей транзакцией; замок этой держится до конца — следующий прогон увидит уже записанное.
        res = store.apply_ops(url, owner, out["ops"], SOURCE, ACTOR)["results"] if out["ops"] else []
        made = offered(conn, owner, fd)
    bad = [r.get("error") for r in res if not r.get("ok")]
    if bad:
        log.warning("план: не записалось %d из %d: %s", len(bad), len(res), bad[0])
    return {**out, "made": made, "errors": bad}


# ── Уборка и снимок ─────────────────────────────────────────────────────

def sweep(url: str, now: dt.datetime | None = None) -> int:
    """Гасит ждущих кандидатов (expired): дело закрыто или отменено до отметки, уже стоит на этот день, или наступил
    срок (12:00 дня — точно, без часовой задержки общей уборки store.expire_suggestions). Принятое не трогается."""
    now = now or dt.datetime.now(dt.timezone.utc)
    with db.session(url, "system", ACTOR, SOURCE) as conn:
        return conn.execute(
            "UPDATE tasks.suggestions s SET status = 'expired', decided_at = now(), reason = x.why "
            "FROM (SELECT s2.id, CASE WHEN t.id IS NOT NULL AND t.status <> 'open' THEN %(closed)s "
            "             WHEN t.id IS NOT NULL AND t.focus_on::text = s2.payload ->> 'for_date' THEN %(already)s "
            "             WHEN s2.expires_at <= %(now)s THEN %(silent)s END AS why "
            "      FROM tasks.suggestions s2 LEFT JOIN tasks.tasks t ON t.id = s2.task_id "
            "      WHERE s2.source = 'plan' AND s2.status = 'pending') x "
            "WHERE s.id = x.id AND x.why IS NOT NULL",
            {"closed": REASON_CLOSED, "already": REASON_ALREADY, "silent": REASON_SILENT, "now": now}).rowcount


def snap(url: str, day: dt.date, owner: str | None = None) -> int:
    """Снимок «Сейчас» дня в tasks.now_days: открытые с focus_on = день и закрытые в этот день; записанные строки
    дообновляются (done), не удаляются — дело, перенесённое кнопкой на завтра, остаётся в своём дне."""
    owner = owner or _owner(url)
    if not owner:
        return 0
    p = {"o": owner, "day": day.isoformat(), "lo": _prev_lo(day).isoformat()}
    with db.session(url, owner, ACTOR, SOURCE) as conn:
        return conn.execute(
            f"WITH ids AS (SELECT t.id FROM tasks.tasks t WHERE t.owner_id = %(o)s AND t.focus_on = %(day)s::date "
            f"               AND {store.DEED_KIND_IF} AND (t.status = 'open' OR (t.status = 'done' "
            "                    AND (t.completed_at AT TIME ZONE 'Europe/Moscow')::date = %(day)s::date)) "
            "             UNION SELECT task_id FROM tasks.now_days WHERE owner_id = %(o)s AND day = %(day)s::date) "
            "INSERT INTO tasks.now_days AS x (owner_id, day, task_id, carried, from_plan, done, done_at) "
            f"SELECT %(o)s, %(day)s::date, t.id, {CARRIED}, "
            "       EXISTS (SELECT 1 FROM tasks.suggestions s WHERE s.source = 'plan' AND s.status IN ('accepted', 'edited') "
            "               AND s.payload ->> 'for_date' = %(day)s::date::text AND coalesce(s.result_task_id, s.task_id) = t.id), "
            "       t.status = 'done' AND (t.completed_at AT TIME ZONE 'Europe/Moscow')::date = %(day)s::date, "
            "       CASE WHEN t.status = 'done' AND (t.completed_at AT TIME ZONE 'Europe/Moscow')::date = %(day)s::date "
            "            THEN t.completed_at END "
            "FROM ids JOIN tasks.tasks t ON t.id = ids.id "
            "LEFT JOIN tasks.now_days n ON n.owner_id = %(o)s AND n.day = %(day)s::date AND n.task_id = t.id "
            "ON CONFLICT (owner_id, day, task_id) DO UPDATE SET done = EXCLUDED.done, done_at = EXCLUDED.done_at, "
            "    from_plan = x.from_plan OR EXCLUDED.from_plan", p).rowcount


def _state(url: str) -> dict:
    with db.session(url, "system", via=SOURCE) as conn:
        row = conn.execute("SELECT value FROM crm.state WHERE key = %s", (STATE_KEY,)).fetchone()
    return dict(row["value"]) if row else {}


def _save(url: str, value: dict) -> None:
    with db.session(url, "system", ACTOR, SOURCE) as conn:
        conn.execute("INSERT INTO crm.state (key, value) VALUES (%s, %s) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value",
                     (STATE_KEY, Jsonb(value)))


def tick(url: str, now: dt.datetime | None = None) -> dict | None:
    """Раз в минуту из serve (rules_loop, без условия на ключ Claude): уборка, снимки дня и, в свой слот, прогон.
    Ответ — что сделано (None — ничего)."""
    now = now or dt.datetime.now(dt.timezone.utc)
    msk = now.astimezone(MSK)
    today = msk.date()
    did: dict[str, Any] = {}
    n = sweep(url, now)
    if n:
        did["swept"] = n
    owner = _owner(url)
    if not owner:
        return did or None
    st = _state(url)
    snaps: dict[str, list] = {k: list(v) for k, v in (st.get("snaps") or {}).items()}
    changed = False
    # Пропущенные дни: служба лежала в 23:55 — снимок дня догоняется (до SNAP_BACK назад), «сделано» — по дню закрытия.
    for k in range(SNAP_BACK, 0, -1):
        d = (today - dt.timedelta(days=k)).isoformat()
        if "2355" not in snaps.get(d, []):
            snap(url, today - dt.timedelta(days=k), owner)
            snaps[d] = sorted({*snaps.get(d, []), "2355"})
            changed = True
    passed = [key for key, h, m in SNAPS if (msk.hour, msk.minute) >= (h, m)]
    if [key for key in passed if key not in snaps.get(today.isoformat(), [])]:
        snap(url, today, owner)
        snaps[today.isoformat()] = sorted({*snaps.get(today.isoformat(), []), *passed})
        changed = True
        did["snap"] = today.isoformat()
    with db.session(url, owner, via=SOURCE) as conn:
        slot = asks.evening_at(asks.rhythm(conn, owner), today)
    if slot and msk >= slot and msk - slot <= LATE and st.get("run_day") != today.isoformat():
        snap(url, today, owner)  # «Сейчас» дня — до того, как кнопки «☆ На завтра» начнут его переносить
        out = run(url, now)
        st["run_day"] = today.isoformat()
        changed = True
        if out.get("skipped"):
            # Пачка дня уже в базе: служба перезапустилась между прогоном и записью памяти, или прогон был руками. Второй
            # раз не предлагаем (run держит это базой), итог того прогона не затираем.
            did["run"] = {"skipped": out["skipped"]}
            log.info("план: %s", out["skipped"])
        else:
            st["last"] = {"at": now.isoformat(), "for_date": out.get("for_date"), "made": out.get("made", 0),
                          "errors": len(out.get("errors") or []), "stuck": len(out.get("stuck") or [])}
            did["run"] = st["last"]
            log.info("план на %s: предложено %d", out.get("for_date"), out.get("made", 0))
    if changed:
        keep = {(today - dt.timedelta(days=k)).isoformat() for k in range(SNAP_BACK + 4)}
        st["snaps"] = {d: v for d, v in snaps.items() if d in keep}
        _save(url, st)
    return did or None


def accepted(conn, user: str, payload: dict) -> dict:
    """После «Да» у кандидата плана (зовёт store.op_suggestion_decide): «напомнить троим» — nudge_on = день плана трём
    своим открытым «жду». Остальным корзинам добавить нечего."""
    if payload.get("basket") != "nudge" or not payload.get("for_date"):
        return {}
    ids = []
    for x in payload.get("nudge_ids") or []:
        try:
            ids.append(str(uuid.UUID(str(x))))
        except ValueError:
            continue
    rows = conn.execute("SELECT id FROM tasks.tasks WHERE id = ANY(%s::uuid[]) AND owner_id = %s AND status = 'open' "
                        "AND ball = 'waiting'", (ids, user)).fetchall() if ids else []
    for r in rows:
        store.op_task_set(conn, user, {"id": str(r["id"]), "set": {"nudge_on": payload["for_date"]}})
    return {"nudged": [str(r["id"]) for r in rows]} if rows else {}


# ── Вид plan ────────────────────────────────────────────────────────────

def stats(conn, owner: str, today: dt.date, days: int = STATS_DAYS) -> dict:
    """За days дней: предложено, отмечено, «✓ Уже сделал», «не надо», молчание, закрыто до отметки, уже стояло — и по
    корзинам. Для правила судьи «отмечают меньше 30 % — пробуем Сонет» и проверки через неделю."""
    rows = conn.execute(
        "SELECT status, reason, result, payload ->> 'basket' AS basket FROM tasks.suggestions "
        "WHERE source = 'plan' AND for_user = %s AND (payload ->> 'for_date')::date > %s::date - %s",
        (owner, today, days)).fetchall()

    def bucket() -> dict:
        return {"offered": 0, "marked": 0, "done": 0, "no": 0, "silent": 0, "closed": 0, "already": 0, "pending": 0}

    out = {"days": days, **bucket(), "baskets": {}}
    for r in rows:
        for b in (out, out["baskets"].setdefault(r["basket"] or "?", bucket())):
            b["offered"] += 1
            if r["status"] in ("accepted", "edited"):
                b["done" if (r["result"] or {}).get("status") == "done" else "marked"] += 1
            elif r["status"] == "rejected":
                b["no"] += 1
            elif r["status"] == "pending":
                b["pending"] += 1
            elif r["reason"] == REASON_CLOSED:
                b["closed"] += 1
            elif r["reason"] == REASON_ALREADY:
                b["already"] += 1
            else:
                b["silent"] += 1
    out["marked_share"] = round(out["marked"] / out["offered"], 2) if out["offered"] else None
    return out


def view_plan(conn, user, **_) -> dict:
    """Шапка блока «Сейчас на завтра» и строка дайджеста 20:03. Только владельцу (не владельцу — {}).

    for_date — ближайший план: есть кандидаты на день позже сегодняшнего — он; до 12:00 и есть план на сегодня —
    сегодня; иначе — следующий будний (заход ещё впереди)."""
    if user != conn.execute("SELECT crm.owner_id() AS id").fetchone()["id"]:
        return {}
    now = dt.datetime.now(dt.timezone.utc)
    today = now.astimezone(MSK).date()
    cfg = config(conn, user)
    days = [dt.date.fromisoformat(r["d"]) for r in conn.execute(
        "SELECT DISTINCT payload ->> 'for_date' AS d FROM tasks.suggestions WHERE source = 'plan' AND for_user = %s "
        "AND (payload ->> 'for_date')::date >= %s::date", (user, today)).fetchall() if r["d"]]
    later = sorted(d for d in days if d > today)
    if later:
        fd = later[0]
    elif today in days and now.astimezone(MSK) < _expires(today, cfg):
        fd = today
    else:
        fd = next_day(today)
    batch = f"plan:{fd.isoformat()}"
    rows = conn.execute("SELECT status FROM tasks.suggestions WHERE source = 'plan' AND for_user = %s AND batch_ref = %s",
                        (user, batch)).fetchall()
    marked = conn.execute(
        f"SELECT t.id, t.num, t.title FROM tasks.v_tasks t WHERE t.owner_id = %s AND t.status = 'open' AND NOT t.goal "
        f"AND {store.DEED_KIND_IF} AND t.focus_on = %s::date ORDER BY t.num", (user, fd)).fetchall()
    at = asks.evening_at(asks.rhythm(conn, user), run_day(fd))
    cap = capacity(conn, user, fd, cfg)
    return {
        "for_date": fd.isoformat(), "label": label(fd), "batch_ref": batch, "at": at.isoformat() if at else None,
        "offered": len(rows), "pending": sum(1 for r in rows if r["status"] == "pending"),
        "marked": marked, "max": store.NOW_MAX, **cap,
        "today": store.now_counts(conn, user, today),
        "stuck": [{"id": str(r["id"]), "num": r["num"], "title": r["title"], "days": r["carried"]}
                  for r in in_now(conn, user, today) if r["carried"] > cfg["carry_max"]],
        "stats": stats(conn, user, today),
    }


def register(handlers: dict, views: dict, err) -> None:  # noqa: ARG001 — операций у плана нет: ответы — suggestion.decide
    views["plan"] = view_plan


register(store.HANDLERS, store.VIEWS, store.OpError)
