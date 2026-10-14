"""Советник по тратам Claude: раз в неделю 3–5 вариантов экономии (14.10.2026, dela_0017).

Владелец: «раз в неделю сам Claude пускай предлагает варианты экономии (красная точка над значком)». Советник —
тот же Claude, только смотрит не на дела, а на книгу трат (spend.py): в воскресенье вечером (21:30 по Москве, после
выводов Круга в 21:00) Дела собирают сводку недели — ТОЛЬКО числа и названия задач: сколько вызовов, токенов, $,
кэш и пачки по каждой службе и задаче, как она устроена сейчас (модель, глубина, кэш, пачка — из контракта
dela-spend.json), прошлые недели и прошлые советы. Ни текстов переписки, ни промптов, ни имён: советнику нужно, где
дорого и почему, а не о чём говорили.

Почему здесь, в Делах: у службы уже есть ключ №1 (копия в dela.env), прокси клиента, тик раз в минуту и книга в
своей базе; и она же отдаёт советы телефону и вебу. Новой работы на видеокарте нет — это один запрос в неделю.

Как платим: Opus 5.5, глубина medium явно (его «по умолчанию» — medium, но правило — писать), Message Batches −50 %:
советы не срочные. Пачка не кончилась за 6 ч — отмена, советы подождут неделю. Трат за неделю нет — ни запроса.
Траты самого советника — в ту же книгу (llm.account, задача spend_advice). Прикидка: вход ~5–9 тыс. токенов,
выход с размышлениями ~3–6 тыс. — около $0,05–0,08 за неделю пачкой, ~$0,3 в месяц.

Ответ — в crm.claude_tips (одна строка на неделю); seen_at пусто — красная точка над значком трат на «Сегодня»,
гасит её владелец, открыв советы (spend.seen).
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import time

from psycopg.types.json import Jsonb

from . import db, llm, spend
from .meetings import MSK

log = logging.getLogger("dela.spend_advice")

STATE_KEY = "spend_advice"   # crm.state: неделя, пачка в работе, итог последнего прогона
TASK = "spend_advice"
MODEL = "claude-opus-5-5"
EFFORT = "medium"
MAX_TOKENS = 12000
SLOT_WEEKDAY, SLOT_HOUR, SLOT_MIN = 6, 21, 30   # воскресенье 21:30 по Москве
LATE = dt.timedelta(hours=6)  # комп спал в слот — догоняем в тот же вечер, а не через неделю
BATCH_MAX_AGE = 6 * 3600
TIPS_MAX = 5
WEEKS_BACK = 3               # для сравнения: три прошлые недели по каждой задаче

RULES = """Ты — советник владельца по тратам на Claude API в его домашней системе: телефонное приложение «Правка» и
службы на домашнем компьютере (встречи, дайджест переписки, дела, деньги, еда, шаббат, книги и озвучка, диктовка).
Раз в неделю ты смотришь на книгу трат и предлагаешь 3–5 КОНКРЕТНЫХ вариантов экономии.

Как владелец решает (его правила, их не оспаривай — опирайся на них):
- Основная модель — Opus 5.5. Где задача попроще и Sonnet 5.5 справится (чистка, сортировка, короткие сводки,
  разметка), можно предложить Sonnet — с прикидкой экономии; решает он.
- Всё, что может подождать минуты, — Message Batches (−50 %). Сразу — только когда человек ждёт ответа.
- Кэш промпта — где префикс реально повторяется; кэш привязан к схеме ответа и модели. Запись кэша дороже входа:
  кэш, который пишется и не читается, — убыток (в данных это «кэш сэкономил» со знаком минус).
- Глубина размышлений (effort) — настройка: размышления оплачиваются как выход; xhigh/max на частой дороге — дорого.
- Новое — сначала на малом. Удобство владельца важнее копеек: не предлагай убрать то, чем он пользуется каждый день,
  ради экономии меньше $1 в месяц.

Как отвечать:
- Опирайся ТОЛЬКО на цифры ниже: службу, задачу, модель, вызовы, токены, $, долю кэша и пачек, прошлые недели. Не
  выдумывай задач и цен. Если данных мало (неделя неполная, источник не присылал) — скажи это в note.
- Траты делятся на «Телефон» (приложение Правка: настройки дорог в его «Моделях») и «Сервер» (службы на компьютере:
  меняются в их коде или Своде). В каждом совете называй, где он — телефон или сервер.
- Каждый совет — одно изменение в одном месте: что поменять (служба · задача: модель, глубина, кэш, пачка,
  частота, объём входа), почему (какие цифры это показывают), сколько сэкономит в МЕСЯЦ в долларах (прикидка из
  цифр недели × ~4,3, с поправкой на долю, которую реально уберёт изменение), чем рискуем (качество, задержка).
- Сначала самые крупные. Совет дешевле $0,5 в месяц — только если больше нечего предложить.
- Если совет уже был в прошлые недели и по цифрам ничего не поменялось — не повторяй его теми же словами; можно
  коротко напомнить, сколько он стоил за неделю.
- Пиши по-русски, коротко и по делу, без вступлений и без «стоит рассмотреть». Суммы — в долларах.
- Ответ: tips — от 3 до 5 советов; note — одна-две фразы: главное о неделе (выросли или упали траты и из-за чего)."""


def schema() -> dict:
    tip = {"type": "object", "additionalProperties": False,
           "required": ["where", "title", "why", "how", "save_usd_month", "risk"],
           "properties": {"where": {"type": "string", "enum": ["phone", "server"]},
                          "title": {"type": "string"}, "why": {"type": "string"}, "how": {"type": "string"},
                          "save_usd_month": {"type": "number"}, "risk": {"type": "string"}}}
    return {"type": "object", "additionalProperties": False, "required": ["tips", "note"],
            "properties": {"tips": {"type": "array", "items": tip}, "note": {"type": "string"}}}


def slot(now: dt.datetime) -> dt.datetime:
    """Последний недельный слот: воскресенье, 21:30 по Москве."""
    t = now.astimezone(MSK).replace(hour=SLOT_HOUR, minute=SLOT_MIN, second=0, microsecond=0)
    t -= dt.timedelta(days=(t.weekday() - SLOT_WEEKDAY) % 7)
    return t if t <= now.astimezone(MSK) else t - dt.timedelta(days=7)


def week_start(day: dt.date) -> dt.date:
    return day - dt.timedelta(days=day.weekday())


def _f(v) -> float:
    return float(v or 0)


def _k(n: int) -> str:
    return f"{n / 1000:.1f}".replace(".", ",")


def _usd(x: float) -> str:
    return f"${x:.2f}" if x >= 0.995 or x == 0 else f"${x:.3f}"


def facts(url: str, owner: str, last_day: dt.date) -> dict | None:
    """Сводка недели, которая кончилась last_day (7 дней), и трёх прошлых — числами. Трат нет — None."""
    first = last_day - dt.timedelta(days=6)
    lo = first - dt.timedelta(days=7 * WEEKS_BACK)
    with db.session(url, owner, via="claude") as conn:
        rows = conn.execute("SELECT * FROM crm.claude_spend WHERE day BETWEEN %s AND %s", (lo, last_day)).fetchall()
        table = spend._prices(conn)
        prev = conn.execute("SELECT week, tips, seen_at FROM crm.claude_tips WHERE owner_id = %s AND week < %s "
                            "ORDER BY week DESC LIMIT 2", (owner, week_start(first) + dt.timedelta(days=7))).fetchall()
        src = conn.execute("SELECT src, max(updated_at) AS at, max(day) AS day FROM crm.claude_spend GROUP BY src").fetchall()
    week = [r for r in rows if r["day"] >= first]
    total = sum(_f(r["usd"]) for r in week)
    if total <= 0:
        return None
    back = []
    for i in range(1, WEEKS_BACK + 1):
        a, b = first - dt.timedelta(days=7 * i), last_day - dt.timedelta(days=7 * i)
        back.append(round(sum(_f(r["usd"]) for r in rows if a <= r["day"] <= b), 4))
    tasks: dict[tuple, dict] = {}
    for r in rows:
        k = (r["service"], r["task"])
        g = tasks.setdefault(k, {"usd": 0.0, "list": 0.0, "calls": 0, "batch": 0, "in": 0, "out": 0, "cw": 0, "cr": 0,
                                 "saved": 0.0, "models": set(), "back": [0.0] * WEEKS_BACK, "exact": True, "src": set()})
        if r["day"] >= first:
            g["usd"] += _f(r["usd"])
            g["list"] += _f(r["usd_list"]) if r["usd_list"] is not None else _f(r["usd"])
            g["calls"] += r["calls"]
            g["batch"] += r["batch_calls"]
            g["in"] += r["input"]
            g["out"] += r["output"]
            g["cw"] += r["cache_write"] + r["cache_write_1h"]
            g["cr"] += r["cache_read"]
            g["saved"] += spend.savings(r, table)[0]
            g["exact"] = g["exact"] and r["exact"]
            g["src"].add(r["src"])
            if r["model"]:
                g["models"].add(r["model"])
        else:
            i = (first - r["day"]).days // 7
            if 0 <= i < WEEKS_BACK:
                g["back"][i] += _f(r["usd"])
    return {"first": first, "last": last_day, "total": round(total, 4), "back": back, "tasks": tasks,
            "prices": table, "prev": prev, "sources": src}


def message(f: dict) -> str:
    """Сводка недели словами и числами — вход советника. Только цифры и названия задач из контракта."""
    lines = [f"НЕДЕЛЯ {f['first']:%d.%m}–{f['last']:%d.%m.%Y}: всего {_usd(f['total'])}; прошлые недели (от ближней): "
             + ", ".join(_usd(x) for x in f["back"]) + "."]
    for b, title in spend.BLOCKS:
        mine = [g for (s, _), g in f["tasks"].items() if spend.block_of(s) == b]
        lines.append(f"{title.upper()} ({'приложение Правка, ключ телефона' if b == 'phone' else 'службы на домашнем компьютере'}): "
                     f"неделя {_usd(sum(g['usd'] for g in mine))}, прошлые: "
                     + ", ".join(_usd(sum(g["back"][i] for g in mine)) for i in range(WEEKS_BACK)) + ".")
    lines.append("\nПО ЗАДАЧАМ — [где] служба · задача [ключ]; как устроено сейчас; за неделю: вызовов (из них пачкой), "
                 "токены тыс. вход / выход / запись кэша / чтение кэша, $ заплачено (без скидки пачки), кэш сэкономил $; "
                 "$ прошлых недель:")
    order = sorted(f["tasks"].items(), key=lambda kv: -(kv[1]["usd"] + sum(kv[1]["back"]) / 10))
    for (s, t), g in order:
        if g["usd"] <= 0 and not any(g["back"]):
            continue
        where = "телефон" if spend.block_of(s) == "phone" else "сервер"
        info = spend.task_info(s, t)
        how = info.get("how") or "устройство не описано"
        key = spend.service_key(s) or "?"
        tok = (f"{_k(g['in'])} / {_k(g['out'])} / {_k(g['cw'])} / {_k(g['cr'])}" if g["exact"]
               else "токенов служба не пишет, только $")
        models = ", ".join(sorted(g["models"])) or "модель не записана"
        lines.append(f"- [{where}] {spend.service_title(s)} · {spend.task_title(s, t)} [{key}]; {how}; модели: {models}; "
                     f"{g['calls']} ({g['batch']}); {tok}; {_usd(g['usd'])} ({_usd(g['list'])}); "
                     f"кэш {('+' if g['saved'] >= 0 else '−')}{_usd(abs(g['saved']))}; "
                     f"прошлые: {', '.join(_usd(x) for x in g['back'])}")
    gaps = spend.contract().get("gaps") or []
    if gaps:
        lines.append("\nНЕ УЧИТЫВАЕТСЯ В КНИГЕ (в цифрах выше этого нет):")
        lines += [f"- {g}" for g in gaps]
    if f["sources"]:
        lines.append("\nИСТОЧНИКИ — когда последний раз привозили траты: "
                     + "; ".join(f"{x['src']} — по {x['day']:%d.%m}" for x in f["sources"]))
    lines.append("\nЦЕНЫ, $ за млн токенов (вход / выход / запись кэша 5 мин / 1 ч / чтение); пачка −50 %:")
    for m in ("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-4-5"):
        p = f["prices"].get(m)
        if p:
            lines.append(f"- {m}: {p['in']:g} / {p['out']:g} / {p['cache_write']:g} / {p.get('cache_write_1h', 2 * p['in']):g} / {p['cache_read']:g}")
    if f["prev"]:
        lines.append("\nПРОШЛЫЕ СОВЕТЫ:")
        for p in f["prev"]:
            for t in (p["tips"] or [])[:TIPS_MAX]:
                lines.append(f"- неделя {p['week']:%d.%m}: {t.get('title', '')} (прикидка {_usd(_f(t.get('save_usd_month')))} в месяц)")
    return "\n".join(lines)


def params(f: dict) -> dict:
    return {"model": MODEL, "max_tokens": MAX_TOKENS, "system": RULES,
            "output_config": {"effort": EFFORT, "format": {"type": "json_schema", "schema": schema()}},
            "messages": [{"role": "user", "content": message(f)}]}


def _state(url: str) -> dict:
    with db.session(url, "system", via="claude") as conn:
        row = conn.execute("SELECT value FROM crm.state WHERE key = %s", (STATE_KEY,)).fetchone()
    return dict(row["value"]) if row else {}


def _save(url: str, value: dict) -> None:
    with db.session(url, "system", "svc:spend", "claude") as conn:
        conn.execute("INSERT INTO crm.state (key, value) VALUES (%s, %s) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value",
                     (STATE_KEY, Jsonb(value)))


def norm_tips(data: dict) -> list[dict]:
    out = []
    for t in (data.get("tips") or [])[:TIPS_MAX]:
        if not isinstance(t, dict) or not str(t.get("title") or "").strip():
            continue
        try:
            save = round(max(0.0, float(t.get("save_usd_month") or 0)), 2)
        except (TypeError, ValueError):
            save = 0.0
        out.append({"where": t.get("where") if t.get("where") in ("phone", "server") else "",
                    "title": " ".join(str(t["title"]).split())[:200],
                    "why": " ".join(str(t.get("why") or "").split())[:600],
                    "how": " ".join(str(t.get("how") or "").split())[:600],
                    "save_usd_month": save,
                    "risk": " ".join(str(t.get("risk") or "").split())[:300]})
    return out


def apply(url: str, owner: str, week: dt.date, msg, week_usd: float, batch: bool = True) -> dict:
    """Ответ советника — в crm.claude_tips (неделя заново — заменяет), траты — в книгу."""
    # Сначала траты, потом проверка: оборванный ответ тоже оплачен.
    usage = llm.usage_of(msg, MODEL)
    usd = llm.cost(usage, MODEL, url) * (0.5 if batch else 1.0)
    llm.account(url, TASK, usd, usage.get("model") or MODEL, usage=usage, batch=batch)
    if getattr(msg, "stop_reason", None) in ("refusal", "max_tokens"):
        raise RuntimeError(f"советы по тратам: ответ оборван ({msg.stop_reason})")
    data = json.loads(next((b.text for b in msg.content if getattr(b, "type", "") == "text"), "") or "{}")
    tips = norm_tips(data)
    if not tips:
        raise RuntimeError("советы по тратам: пустой ответ")
    note = " ".join(str(data.get("note") or "").split())[:600]
    with db.session(url, owner, "svc:spend", "claude") as conn:
        row = conn.execute(
            "INSERT INTO crm.claude_tips (owner_id, week, tips, note, week_usd, model, usd) VALUES (%s, %s, %s, %s, %s, %s, %s) "
            "ON CONFLICT (owner_id, week) DO UPDATE SET tips = EXCLUDED.tips, note = EXCLUDED.note, "
            "week_usd = EXCLUDED.week_usd, model = EXCLUDED.model, usd = EXCLUDED.usd, seen_at = NULL RETURNING id",
            (owner, week, Jsonb(tips), note, round(week_usd, 4), usage.get("model") or MODEL, round(usd, 6))).fetchone()
    return {"id": str(row["id"]), "tips": len(tips), "usd": round(usd, 4)}


def _owner(url: str) -> str | None:
    with db.session(url, "system", via="claude") as conn:
        return conn.execute("SELECT crm.owner_id() AS id").fetchone()["id"]


def tick(url: str, key: str, proxy: str | None, now: dt.datetime | None = None, client=None) -> dict | None:
    """Тик раз в минуту (из serve): досмотреть пачку или, в свой слот, отправить новую. None — делать нечего."""
    now = now or dt.datetime.now(dt.timezone.utc)
    st = _state(url)
    if st.get("batch"):
        b = st["batch"]
        if client is None:
            from . import parse
            client = parse.client(key, proxy)
        info = client.messages.batches.retrieve(b["id"])
        if info.processing_status != "ended":
            if time.time() - b["at"] < BATCH_MAX_AGE:
                return None
            try:
                client.messages.batches.cancel(b["id"])
            except Exception:  # noqa: BLE001
                pass
            _save(url, {**st, "batch": None, "last": {"week": b["week"], "error": "пачка застряла"}})
            log.warning("советы по тратам: пачка %s застряла — до следующей недели", b["id"])
            return {"error": True}
        res = next(iter(client.messages.batches.results(b["id"])), None)
        if res is None or res.result.type != "succeeded":
            _save(url, {**st, "batch": None, "last": {"week": b["week"], "error": res.result.type if res else "пусто"}})
            return {"error": True}
        try:
            out = apply(url, b["owner"], dt.date.fromisoformat(b["week"]), res.result.message, b.get("week_usd", 0.0))
        except (RuntimeError, ValueError) as e:
            _save(url, {**st, "batch": None, "last": {"week": b["week"], "error": str(e)[:200]}})
            log.warning("советы по тратам: %s", e)
            return {"error": True}
        _save(url, {**st, "batch": None, "last": {"week": b["week"], **out}})
        log.info("советы по тратам: %d за неделю %s ($%.3f)", out["tips"], b["week"], out["usd"])
        return out
    s = slot(now)
    week = week_start(s.date())
    if not key or st.get("week") == week.isoformat() or now.astimezone(MSK) - s > LATE:
        return None
    return send(url, key, proxy, s.date(), client=client, state=st)


def send(url: str, key: str, proxy: str | None, last_day: dt.date, client=None, state: dict | None = None,
         batch: bool = True) -> dict | None:
    """Собрать сводку недели, кончившейся last_day, и отправить советнику (пачкой; batch=False — сразу, для пробы)."""
    st = state if state is not None else _state(url)
    week = week_start(last_day)
    owner = _owner(url)
    f = facts(url, owner, last_day) if owner else None
    if not f:
        _save(url, {**st, "week": week.isoformat(), "last": {"week": week.isoformat(), "skipped": "трат нет"}})
        return None
    if client is None:
        from . import parse
        client = parse.client(key, proxy)
    p = params(f)
    if not batch:
        from .parse import FALLBACK_BETA
        msg = client.beta.messages.create(**p, betas=[FALLBACK_BETA], fallbacks="default")
        out = apply(url, owner, week, msg, f["total"], batch=False)
        _save(url, {**st, "week": week.isoformat(), "last": {"week": week.isoformat(), "direct": True, **out}})
        return out
    b = client.messages.batches.create(requests=[{"custom_id": "spend-advice", "params": p}])
    _save(url, {**st, "week": week.isoformat(),
                "batch": {"id": b.id, "at": time.time(), "week": week.isoformat(), "owner": owner, "week_usd": f["total"]}})
    log.info("советы по тратам: неделя %s ($%.2f) — советнику, пачка %s", week, f["total"], b.id)
    return {"sent": True, "week": week.isoformat()}
