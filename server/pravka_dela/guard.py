"""Защита от петли Claude на сервере Дел (аудит 15.10.2026, №12): рубильник, сообщения владельцу, суточный предел.

Потолка трат у сервера не было: любой вошедший мог звать /api/ask и /api/parse без счёта (общий предел — два задания
разом), «Когда» — 20 за 5 минут на человека, а разборщики (Круг, встречи, недельные выводы, советник трат) — по своему
расписанию без предела. Ошибка в коде, которая зовёт Claude в цикле, тратила бы деньги владельца до утра. Образец —
телефон: core/FloodGuard.kt (пороги за 10 минут и за час на дорогу и всего) и data/FloodBreaker.kt (сработал — стоит,
вопрос владельцу, «Продолжать» — пороги втрое на час).

Как устроено (контракт пакета «Деньги» §4):
- client(url, key, proxy, task) — клиент Anthropic в обёртке: messages.create / stream, beta.messages.create / stream,
  messages.batches.create — логические вызовы, каждый считается для задачи (её имя — то же, что `what` в llm.account:
  ask, task, parse, when, krug, meetings, krug_tune, spend_advice, zachem); retrieve, results, cancel пачек бесплатны и
  не считаются, повторы SDK внутри вызова — тоже (их держит max_retries). Порог задачи превышен — стоит эта задача;
  порог «всего» — стоят все задачи Дел. Сработал — Stopped (словами: какая задача, сколько за какое окно, порог) и
  одно сообщение владельцу ботом Ковчега (crm.state claude_alerts; забирает служба встреч из /api/reminders/due и
  отмечает claude.alert_sent). Новая дорога к Anthropic мимо guard.client — нельзя (правило 24 карты; тест ловит).
- «Продолжать» (claude.guard resume: веб, телефон, кнопка в Telegram, команда guard on) — снять и час пороги втрое (у
  задачи и у «всего»); «Оставить» и молчание — стоит; стоит без ответа ask_again_hours — ещё одно сообщение, не чаще раза
  в сутки.
- Состояние (попадания за час, что стоит, послабления) — crm.state claude_guard: переживает перезапуск службы (петля,
  которая роняет службу, иначе начинала бы с нуля).
- Пороги — Свод claude.caps (правит владелец словами через Claude), нет записи или кривое поле — заводские FACTORY.
- Сторож суммы дня (watch_day, зовёт spend.watch после каждой строки книги): сумма дня всего, по службе и задаче против
  claude.caps.day — только сообщение, не остановка (сумма дня бывает плановой).
- Суточный предел не-владельцу (quota): 40 правок словами и 20 разборов в сутки по Москве (claude.caps.user_day).
"""

from __future__ import annotations

import copy
import datetime as dt
import logging
import time
import uuid
from typing import Any
from zoneinfo import ZoneInfo

from psycopg.types.json import Jsonb

from . import db

log = logging.getLogger("dela.guard")

SERVICE = "dela"
FLAG = "claude_guard"
CAPS_KEY = "claude.caps"
STATE = "claude_guard"        # crm.state: попадания за час, что стоит, послабления
ALERTS = "claude_alerts"      # crm.state: сообщения владельцу (ждут бота Ковчега)
SEEN = "claude_caps_seen"     # crm.state: какие пороги суммы дня уже сообщены в этот день
QUOTA = "claude_quota"        # crm.state: суточный счёт не-владельцу
BOT = "kovcheg"               # кнопку в Telegram передаёт служба встреч токеном бота Ковчега
ALL = "*"
ALERTS_MAX = 50
CAPS_EVERY = 300              # Свод claude.caps перечитывается раз в 5 минут
MSK = ZoneInfo("Europe/Moscow")
WINDOWS = ((600, "10 минут"), (3600, "час"))

# Пороги с завода (контракт §4.1, §4.6): «за 10 минут / за час», запас ×1,5–3 к жизни. Имена задач у Дел — `what` книги
# трат; «*» — любая другая задача Дел; total — все задачи службы вместе. У встреч и дайджеста — свои (их guard.py, зеркало
# Свода claude-caps.json), здесь — чтобы запись Свода была одна и полная.
FACTORY: dict = {
    "day": {"total": 15, "services": {"*": 6, "books": 20, "phone": 5, "dela": 3, "digest": 3}, "tasks": {}},
    "rate": {
        "dela": {"total": [80, 250], "*": [30, 100], "when": [40, 120], "krug": [6, 20], "meetings": [6, 30],
                 "krug_tune": [4, 10], "spend_advice": [4, 10], "zachem": [4, 10]},
        "meetings": {"total": [150, 400], "clean": [100, 300], "summary": [30, 80], "dela": [40, 80], "talk": [20, 60]},
        "digest": {"total": [30, 60], "window": [6, 20], "razn": [25, 40], "lyudi": [6, 20]},
    },
    "relax": 3, "relax_min": 60, "ask_again_hours": 6,
    "user_day": {"ask": 40, "parse": 20},
}
TASK_WORDS = {"ask": "строка Claude и разговор", "task": "Claude карточки дела", "parse": "разбор надиктовки",
              "when": "«Когда»", "krug": "разборщик Круга", "meetings": "разборщик встреч",
              "krug_tune": "недельные выводы дерева", "spend_advice": "советник трат", "zachem": "«Зачем»",
              ALL: "все задачи Дел"}


class Stopped(Exception):
    """Задача стоит — защита от петли. Не «Claude недоступен»: работа ждёт, попытки не тратятся."""

    def __init__(self, task: str, why: str):
        self.task = task
        self.why = why
        super().__init__(f"Claude остановлен защитой от петли ({why}). Включить — «Продолжать» в сообщении бота "
                         "Ковчега или на странице трат Дел.")


def _calls(n: int) -> str:
    word = "вызов" if n % 10 == 1 and n % 100 != 11 else "вызова" if 2 <= n % 10 <= 4 and not 12 <= n % 100 <= 14 else "вызовов"
    return f"{n} {word}"


def _usd(v: float) -> str:
    return f"${int(v)}" if float(v) == int(v) else f"${v:.2f}".replace(".", ",")


# ── Пороги ─────────────────────────────────────────────────────────────

_caps: dict = {"at": -CAPS_EVERY, "value": None}


def _num(v: Any) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool) and v > 0


def _pair(v: Any) -> list[int] | None:
    if isinstance(v, (list, tuple)) and len(v) == 2 and all(_num(x) for x in v):
        return [int(v[0]), int(v[1])]
    return None


def merge_caps(value: Any) -> dict:
    """Свод claude.caps поверх заводских: каждое годное поле — его, кривое — строка журнала и заводское (не падение)."""
    out = copy.deepcopy(FACTORY)
    if value is None:
        return out
    if not isinstance(value, dict):
        log.warning("claude.caps: не объект — пороги заводские")
        return out
    rate = value.get("rate") if isinstance(value.get("rate"), dict) else {}
    for svc, tasks in rate.items():
        if not isinstance(tasks, dict):
            log.warning("claude.caps.rate.%s: не объект — заводское", svc)
            continue
        for task, pair in tasks.items():
            p = _pair(pair)
            if p:
                out["rate"].setdefault(svc, {})[task] = p
            else:
                log.warning("claude.caps.rate.%s.%s: нужна пара [за 10 минут, за час] — заводское", svc, task)
    day = value.get("day")
    if isinstance(day, dict):
        if _num(day.get("total")):
            out["day"]["total"] = day["total"]
        for k in ("services", "tasks"):
            for name, usd in (day.get(k) or {}).items() if isinstance(day.get(k), dict) else []:
                if _num(usd):
                    out["day"][k][name] = usd
                else:
                    log.warning("claude.caps.day.%s.%s: нужна сумма в $ — заводское", k, name)
    for k in ("relax", "relax_min", "ask_again_hours"):
        v = value.get(k)
        if _num(v):
            out[k] = v
        elif v is not None:
            log.warning("claude.caps.%s: нужно положительное число — заводское", k)
    ud = value.get("user_day")
    if isinstance(ud, dict):
        for k in ("ask", "parse"):
            v = ud.get(k)
            if isinstance(v, int) and not isinstance(v, bool) and v >= 0:
                out["user_day"][k] = v
            elif v is not None:
                log.warning("claude.caps.user_day.%s: нужно целое число — заводское", k)
    return out


def caps_of(conn) -> dict:
    """Пороги этой же сессией (её пользователь — владелец или system: Свод под RLS «только своё»)."""
    row = conn.execute("SELECT value FROM crm.svod WHERE owner_id = crm.owner_id() AND key = %s", (CAPS_KEY,)).fetchone()
    return merge_caps((row or {}).get("value"))


def caps(url: str | None) -> dict:
    """Действующие пороги: Свод владельца claude.caps (раз в CAPS_EVERY) поверх FACTORY."""
    if url and (time.monotonic() - _caps["at"] >= CAPS_EVERY or _caps["value"] is None):
        _caps["at"] = time.monotonic()
        try:
            with db.session(url, "system", via="claude") as conn:
                _caps["value"] = caps_of(conn)
        except Exception:  # noqa: BLE001 — пороги не должны ронять Claude: есть заводские
            log.exception("claude.caps из Свода")
    return _caps["value"] or copy.deepcopy(FACTORY)


def limits(c: dict, task: str) -> tuple[list[int], list[int]]:
    rate = c["rate"].get(SERVICE) or {}
    return rate.get(task) or rate.get(ALL) or [30, 100], rate.get("total") or [80, 250]


# ── Состояние ──────────────────────────────────────────────────────────

def _load(conn, key: str, default: dict) -> dict:
    row = conn.execute("SELECT value FROM crm.state WHERE key = %s FOR UPDATE", (key,)).fetchone()
    v = dict(row["value"]) if row and isinstance(row["value"], dict) else {}
    return {**copy.deepcopy(default), **v}


def _save(conn, key: str, value: dict) -> None:
    conn.execute("INSERT INTO crm.state (key, value) VALUES (%s, %s) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, "
                 "updated_at = now()", (key, Jsonb(value)))


def _blank() -> dict:
    return {"v": 1, "hits": [], "stopped": {}, "relax": {}}


def _hm(ts: float) -> str:
    return dt.datetime.fromtimestamp(ts, MSK).strftime("%H:%M")


def _iso(ts: float) -> str:
    return dt.datetime.fromtimestamp(ts, dt.timezone.utc).isoformat(timespec="seconds")


def _alert(conn, kind: str, task: str, text: str, buttons: list[dict], now: float, link: str | None = None) -> dict:
    """Сообщение владельцу — в очередь (crm.state claude_alerts, до ALERTS_MAX); забирает бот Ковчега из
    /api/reminders/due. link — относительный адрес веба Дел (кнопка-ссылка), полный строит due по public_url."""
    al = _load(conn, ALERTS, {"items": []})
    item = {"id": str(uuid.uuid4()), "kind": kind, "service": SERVICE, "task": task, "text": text, "buttons": buttons,
            "link": link, "at": _iso(now), "sent_at": None, "message_id": None, "answered": None}
    al["items"] = [*al.get("items", []), item][-ALERTS_MAX:]
    _save(conn, ALERTS, al)
    return item


def _guard_buttons(task: str) -> list[dict]:
    return [{"text": "Продолжать", "data": f"guard:dela:{task}:on"},
            {"text": "Оставить остановленным", "data": f"guard:dela:{task}:keep"}]


# ── Рубильник ──────────────────────────────────────────────────────────

def hit(url: str, task: str, now: float | None = None) -> None:
    """Один логический вызов Claude задачи task: счёт и проверка порогов. Стоит или порог превышен — Stopped.

    Счёт и решение — одной транзакцией под замком строки crm.state (два потока не проскочат порог вдвоём); Stopped —
    после фиксации: иначе откат унёс бы и «стоит», и сообщение владельцу. Вызов, который не пустили, не считается."""
    now = time.time() if now is None else now
    c = caps(url)
    stop: tuple[str, str] | None = None
    with db.session(url, "system", "svc:guard", "claude") as conn:
        st = _load(conn, STATE, _blank())
        hits = [h for h in st["hits"] if isinstance(h, list) and len(h) == 2 and now - float(h[0]) < 3600]
        relax = {k: float(v) for k, v in (st.get("relax") or {}).items() if float(v) > now}
        stopped = dict(st.get("stopped") or {})
        if ALL in stopped or task in stopped:
            k = ALL if ALL in stopped else task
            stop = (k, stopped[k].get("why") or "стоит")
        else:
            mine = [float(h[0]) for h in hits if h[1] == task] + [now]
            every = [float(h[0]) for h in hits] + [now]
            t_lim, all_lim = limits(c, task)
            for key, seen, lim in ((task, mine, t_lim), (ALL, every, all_lim)):
                times = c["relax"] if key in relax else 1
                for (sec, word), cap in zip(WINDOWS, lim):
                    n = sum(1 for x in seen if now - x < sec)
                    if n > cap * times:
                        why = f"{TASK_WORDS.get(key, key)}: {_calls(n)} за {word}, порог {int(cap * times)}"
                        stopped[key] = {"why": why, "at": now, "asked_at": now}
                        stop = (key, why)
                        break
                if stop:
                    break
            if stop:
                text = (f"⛔ <b>Дела</b> · защита от петли\n{stop[1]}.\nЗадача стоит, деньги не тратятся.\n"
                        "<i>↩️ продолжать?</i>")
                _alert(conn, "guard", stop[0], text, _guard_buttons(stop[0]), now)
                log.warning("защита от петли: стоит %s — %s", stop[0], stop[1])
            else:
                hits.append([now, task])
        _save(conn, STATE, {"v": 1, "hits": hits, "stopped": stopped, "relax": relax})
    if stop:
        raise Stopped(stop[0], stop[1])


def blocked(url: str, task: str) -> str | None:
    """Дешёвая проверка до взятия работы: стоит ли задача (или всё). Ответ — почему, или None."""
    try:
        with db.session(url, "system", "svc:guard", "claude") as conn:
            row = conn.execute("SELECT value FROM crm.state WHERE key = %s", (STATE,)).fetchone()
    except Exception:  # noqa: BLE001
        return None
    stopped = ((row or {}).get("value") or {}).get("stopped") or {}
    for k in (ALL, task):
        if k in stopped:
            return stopped[k].get("why") or "стоит"
    return None


# ── Обёртка клиента Anthropic ──────────────────────────────────────────

# Логические вызовы, которые стоят денег. Пачка (batches.create) — тоже: она одна, но платная; retrieve, results,
# cancel — бесплатны и не считаются.
COUNTED = frozenset({"messages.create", "messages.stream", "beta.messages.create", "beta.messages.stream",
                     "messages.batches.create", "beta.messages.batches.create"})
PREFIXES = frozenset({"messages", "beta", "beta.messages", "messages.batches", "beta.messages.batches"})


class Guarded:
    """Тонкая обёртка: проверка до вызова, дальше — тот же объект SDK; всё прочее — насквозь."""

    def __init__(self, inner, check, path: str = ""):
        self._inner, self._check, self._path = inner, check, path

    def __getattr__(self, name: str):
        val = getattr(self._inner, name)
        path = f"{self._path}.{name}" if self._path else name
        if path in COUNTED and callable(val):
            check = self._check

            def counted(*a, **k):
                check()
                return val(*a, **k)

            return counted
        if path in PREFIXES:
            return Guarded(val, self._check, path)
        return val


def wrap(inner, url: str, task: str) -> Guarded:
    return Guarded(inner, lambda: hit(url, task))


def client(url: str, key: str, proxy: str | None, task: str) -> Guarded:
    """Клиент Anthropic Дел — только так (правило 24): parse.client в обёртке рубильника."""
    from . import parse

    return wrap(parse.client(key, proxy), url, task)


# ── Ответ владельца ────────────────────────────────────────────────────

def answer(url: str, task: str, action: str, by: str, now: float | None = None) -> dict:
    """«Продолжать» (resume) — снять и час пороги втрое у задачи и «всего»; «Оставить» (keep) — стоит, вопрос отвечен."""
    with db.session(url, "system", "svc:guard", "claude") as conn:
        return _answer(conn, task, action, by, now)


def _answer(conn, task: str, action: str, by: str, now: float | None = None) -> dict:
    """То же в чужой транзакции; пользователь сессии — system (crm.state пишет только он)."""
    now = time.time() if now is None else now
    c = caps_of(conn)
    st = _load(conn, STATE, _blank())
    stopped, relax = dict(st.get("stopped") or {}), dict(st.get("relax") or {})
    if action == "resume":
        keys = list(stopped) if task == ALL else [task]
        for k in keys:
            stopped.pop(k, None)
        until = now + float(c["relax_min"]) * 60
        for k in {*keys, task, ALL}:
            relax[k] = until
        # Попадания до «Продолжать» не в счёт: иначе первый же вызов снова упёрся бы в прежний порог.
        st["hits"] = [] if task == ALL else [h for h in st["hits"] if h[1] != task]
    st.update(stopped=stopped, relax=relax)
    _save(conn, STATE, st)
    al = _load(conn, ALERTS, {"items": []})
    for it in al.get("items", []):
        if it.get("kind") == "guard" and (task == ALL or it.get("task") == task) and not it.get("answered"):
            it["answered"] = {"action": action, "by": by, "at": now}
    _save(conn, ALERTS, al)
    _caps["at"] = -CAPS_EVERY  # следующий вызов перечитает пороги
    log.warning("защита от петли: %s — %s (%s)", task, "продолжаю, час пороги втрое" if action == "resume" else "стоит", by)
    return {"task": task, "action": action, "stopped": sorted(stopped),
            "relax": {k: _iso(v) for k, v in relax.items() if float(v) > now}}


def op_guard(conn, user: str, op: dict, err) -> dict:
    """Операция claude.guard {action: resume|keep, task}: владелец (веб, телефон) или бот Ковчега (кнопку нажал
    владелец — проверяет служба встреч по settings.owner_tg). Прочим — отказ словами."""
    actor = conn.execute("SELECT crm.actor() AS a").fetchone()["a"] or user
    owner = conn.execute("SELECT crm.owner_id() AS o").fetchone()["o"]
    if not (actor == f"svc:{BOT}" or (user == owner and not str(actor).startswith("svc:"))):
        raise err("защита от петли: «Продолжать» — только владелец Дел (веб, телефон или кнопка бота Ковчега)")
    action, task = op.get("action"), str(op.get("task") or ALL).strip()
    if action not in ("resume", "keep"):
        raise err("защита от петли: action — resume или keep")
    if task != ALL and (not task.replace("_", "").isalnum() or len(task) > 32):
        raise err("защита от петли: task — имя задачи или «*»")
    # crm.state пишет только system — на эту операцию, потом прежний пользователь (сбой откатит и set_config).
    conn.execute("SELECT set_config('dela.user', 'system', true)")
    try:
        return _answer(conn, task, action, actor)
    finally:
        conn.execute("SELECT set_config('dela.user', %s, true)", (user,))


def op_alert_sent(conn, user: str, op: dict, err) -> dict:
    """Бот Ковчега отправил сообщение (claude.alert_sent {id, message_id}) — больше не слать. Только svc:kovcheg."""
    actor = conn.execute("SELECT crm.actor() AS a").fetchone()["a"]
    if actor != f"svc:{BOT}":
        raise err("claude.alert_sent шлёт только бот Ковчега")
    aid = str(op.get("id") or "")
    conn.execute("SELECT set_config('dela.user', 'system', true)")
    try:
        al = _load(conn, ALERTS, {"items": []})
        got = next((it for it in al.get("items", []) if it.get("id") == aid), None)
        if not got:
            raise err("нет такого сообщения")
        got["sent_at"] = got.get("sent_at") or _iso(time.time())
        got["message_id"] = op.get("message_id")
        _save(conn, ALERTS, al)
    finally:
        conn.execute("SELECT set_config('dela.user', %s, true)", (user,))
    return {"alert": {"id": aid, "sent_at": got["sent_at"], "message_id": got["message_id"]}}


# ── Сообщения боту ─────────────────────────────────────────────────────

def alerts_due(url: str, public_url: str = "", now: float | None = None) -> list[dict]:
    """Неотправленные сообщения владельцу — полем alerts у /api/reminders/due. Здесь же повтор вопроса: задача стоит без
    ответа ask_again_hours — ещё одно сообщение «всё ещё стоит с чч:мм», не чаще раза в сутки. У сторожа суммы дня
    (kind cap) кнопка — ссылка на страницу трат (public_url + link)."""
    now = time.time() if now is None else now
    c = caps(url)
    base = (public_url or "").rstrip("/")
    with db.session(url, "system", "svc:guard", "claude") as conn:
        st = _load(conn, STATE, _blank())
        changed = False
        for k, s in (st.get("stopped") or {}).items():
            asked = float(s.get("asked_at") or s.get("at") or now)
            if now - asked >= float(c["ask_again_hours"]) * 3600 and now - float(s.get("again_at") or 0) >= 24 * 3600:
                text = (f"⛔ <b>Дела</b> · защита от петли\n{TASK_WORDS.get(k, k)}: всё ещё стоит с "
                        f"{_hm(float(s.get('at') or now))} — {s.get('why') or ''}.\n<i>↩️ продолжать?</i>")
                _alert(conn, "guard", k, text, _guard_buttons(k), now)
                s["asked_at"], s["again_at"] = now, now
                changed = True
        if changed:
            _save(conn, STATE, st)
        al = _load(conn, ALERTS, {"items": []})
    out = []
    for it in al.get("items", []):
        if it.get("sent_at") or it.get("answered"):
            continue
        x = {k: it.get(k) for k in ("id", "kind", "service", "task", "text", "buttons", "at")}
        if it.get("link"):
            href = f"{base}/{it['link'].lstrip('/')}" if base else it["link"]
            x["buttons"] = [{"text": b["text"], "url": href} for b in it.get("buttons") or []]
        out.append(x)
    return out


# ── Сторож суммы дня ───────────────────────────────────────────────────

def watch_day(conn, day: dt.date | None = None, now: float | None = None) -> list[dict]:
    """Суммы дня (Москва) — всего, по службе и по «служба.задача» — против claude.caps.day. Перешли порог — сообщение
    владельцу (kind cap), один раз на день и порог (claude_caps_seen). Пользователь сессии — system (зовёт spend.watch)."""
    from . import spend

    now = time.time() if now is None else now
    c = caps_of(conn)
    d = day or conn.execute("SELECT crm.today() AS d").fetchone()["d"]
    rows = conn.execute("SELECT service, task, sum(usd) AS usd FROM crm.claude_spend WHERE day = %s GROUP BY service, task",
                        (d,)).fetchall()
    total = sum(float(r["usd"] or 0) for r in rows)
    by_svc: dict[str, float] = {}
    for r in rows:
        by_svc[r["service"]] = by_svc.get(r["service"], 0.0) + float(r["usd"] or 0)
    checks = [("total", "всего", total, c["day"]["total"])]
    for s, usd in sorted(by_svc.items()):
        cap = c["day"]["services"].get(s, c["day"]["services"].get(ALL))
        if cap:
            checks.append((f"svc:{s}", f"«{spend.service_title(s)}»", usd, cap))
    for r in rows:
        k = f"{r['service']}.{r['task']}"
        cap = c["day"]["tasks"].get(k)
        if cap:
            checks.append((f"task:{k}", f"«{spend.service_title(r['service'])} · {spend.task_title(r['service'], r['task'])}»",
                           float(r["usd"] or 0), cap))
    seen = _load(conn, SEEN, {"day": str(d), "keys": []})
    if seen.get("day") != str(d):
        seen = {"day": str(d), "keys": []}
    out = []
    for key, what, usd, cap in checks:
        if usd > float(cap) and key not in seen["keys"]:
            seen["keys"].append(key)
            text = f"💸 <b>Claude</b> · за сегодня {what} — {_usd(round(usd, 2))} (порог {_usd(cap)}); всего {_usd(round(total, 2))}."
            out.append(_alert(conn, "cap", key, text, [{"text": "Траты"}], now, link="#/spend"))
            log.warning("сумма дня Claude: %s — $%.2f (порог $%s)", key, usd, cap)
    if out:
        _save(conn, SEEN, seen)
    return out


# ── Суточный предел не-владельцу ───────────────────────────────────────

QUOTA_WORDS = {"ask": "правок словами", "parse": "разборов"}


class OverQuota(Exception):
    pass


def quota(url: str, user: str, what: str) -> None:
    """Не-владелец (роль не owner) за сутки по Москве: user_day.ask правок словами и user_day.parse разборов. Сверх —
    OverQuota со словами (API — 429). Владелец и службы с его токеном — без суточного предела (их держит рубильник).
    Счёт растёт, когда задание принято."""
    c = caps(url)
    with db.session(url, "system", "svc:guard", "claude") as conn:
        u = conn.execute("SELECT role, crm.today()::text AS d FROM crm.users WHERE id = %s", (user,)).fetchone()
        if not u or u["role"] == "owner":
            return
        q = _load(conn, QUOTA, {"day": u["d"], "users": {}})
        if q.get("day") != u["d"]:
            q = {"day": u["d"], "users": {}}
        mine = q["users"].setdefault(user, {})
        cap = int(c["user_day"].get(what, 0))
        n = int(mine.get(what, 0))
        if n >= cap:
            raise OverQuota(f"На сегодня Claude для тебя — всё: {n} из {cap} {QUOTA_WORDS.get(what, what)}. "
                            "Завтра снова; поднять предел может Саша.")
        mine[what] = n + 1
        _save(conn, QUOTA, q)


# ── Что стоит — странице трат и команде guard ──────────────────────────

def status_of(conn, now: float | None = None) -> dict:
    """Что стоит, почему и с какого времени, послабления (поле guard вида spend). Пользователь сессии — system."""
    now = time.time() if now is None else now
    row = conn.execute("SELECT value FROM crm.state WHERE key = %s", (STATE,)).fetchone()
    st = (row or {}).get("value") or {}
    stopped = [{"task": k, "title": TASK_WORDS.get(k, k), "why": v.get("why"), "at": _iso(float(v.get("at") or now))}
               for k, v in (st.get("stopped") or {}).items()]
    relax = [{"task": k, "title": TASK_WORDS.get(k, k), "until": _iso(float(v))}
             for k, v in (st.get("relax") or {}).items() if float(v) > now]
    hour = [h for h in st.get("hits") or [] if now - float(h[0]) < 3600]
    return {"stopped": stopped, "relax": relax, "hits_hour": len(hour)}


def status(url: str, now: float | None = None) -> dict:
    with db.session(url, "system", "svc:guard", "claude") as conn:
        return status_of(conn, now)


def register(handlers: dict, views: dict, err) -> None:
    handlers["claude.guard"] = lambda conn, user, op: op_guard(conn, user, op, err)
    handlers["claude.alert_sent"] = lambda conn, user, op: op_alert_sent(conn, user, op, err)
