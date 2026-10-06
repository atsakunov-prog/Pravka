"""Напоминания дел в Telegram (06.10.2026): время и места, «что пора» для бота, его кнопки.

Владелец: «хочу, чтобы шли напоминания через мой Ковчег-бот в Телеграме… „напомни мне в 11:00“
или „напомни, когда я приеду домой“». Напоминание — два поля дела: remind_at (момент) и
remind_place (место автопилота Засечки на телефоне). Место в момент превращает телефон: он один
знает, где владелец, и на настоящем приезде ставит remind_at = миг приезда.

Отправляет бот Ковчега — служба встреч (C:\\Bot\\Meetings\\meetings\\worker\\remind.py): Telegram
владельца живёт там (токен, getUpdates, кнопки приходят туда же), а службе Дел C:\\Bot закрыт.
Дела остаются API: бот раз в 30 секунд спрашивает «что пора» (due), шлёт и отмечает отправку
операцией task.reminded; кнопки под сообщением — act. Оба пути — только токену бота (BOT).

Контракт — server/contract/dela-remind.json; слова и подписи — как DelaRemind на телефоне.
"""

from __future__ import annotations

import datetime as dt
import re
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from . import db, store

try:
    MSK = ZoneInfo("Europe/Moscow")
except ZoneInfoNotFoundError:  # без tzdata: в Москве нет перевода часов с 2014 года
    MSK = dt.timezone(dt.timedelta(hours=3), "MSK")

BOT = store.REMIND_BOT
ACTOR = f"svc:{BOT}"
MORNING = (9, 0)              # «утром», «завтра» без часов — как в общем промпте разбора
LIMIT = 200                   # за один опрос: служба лежала сутки — бот сведёт опоздавших в одно сообщение
WD_SHORT = ["пн", "вт", "ср", "чт", "пт", "сб", "вс"]
NOTES_TAIL = "(сервер Дел пока не напоминает)"


class RemindError(store.OpError):
    pass


# ── Время ───────────────────────────────────────────────────────────────


def now() -> dt.datetime:
    return dt.datetime.now(MSK)


def iso(at: dt.datetime) -> str:
    return at.astimezone(MSK).isoformat(timespec="seconds")


def local_iso(said: str | None) -> str | None:
    """«2026-10-07 11:00» по Москве (как отвечает модель) — в ISO со смещением. Готовое ISO со
    смещением — как есть. Не время — None: лучше без напоминания, чем в выдуманный миг."""
    s = (said or "").strip()
    if not s:
        return None
    try:
        got = dt.datetime.fromisoformat(s)
        if got.tzinfo is not None:
            return iso(got)
    except ValueError:
        pass
    m = re.fullmatch(r"(\d{4}-\d{2}-\d{2})[ T](\d{1,2})[:.](\d{2})(?::\d{2})?", s)
    if not m:
        return None
    try:
        day = dt.date.fromisoformat(m.group(1))
        return iso(dt.datetime(day.year, day.month, day.day, int(m.group(2)), int(m.group(3)), tzinfo=MSK))
    except ValueError:
        return None


def at_of(v) -> dt.datetime | None:
    """remind_at из базы (datetime) или из JSON (строка) — в московское время."""
    if isinstance(v, dt.datetime):
        return v.astimezone(MSK)
    try:
        got = dt.datetime.fromisoformat(str(v or ""))
    except ValueError:
        return None
    return got.astimezone(MSK) if got.tzinfo else got.replace(tzinfo=MSK)


def norm_time(s: str | None) -> str | None:
    """«9:00», «09.30» — «09:00», «09:30»; не время — None."""
    m = re.fullmatch(r"(\d{1,2})[:.](\d{2})", (s or "").strip())
    if not m or int(m.group(1)) > 23 or int(m.group(2)) > 59:
        return None
    return f"{int(m.group(1)):02d}:{m.group(2)}"


def when_words(at: dt.datetime, today: dt.date) -> str:
    """«11:00», «завтра 09:00», «чт 09:00» (до недели вперёд), иначе «12.10 09:00» — DelaRemind.whenWords."""
    at = at.astimezone(MSK)
    hm = at.strftime("%H:%M")
    days = (at.date() - today).days
    if days == 0:
        return hm
    if days == 1:
        return f"завтра {hm}"
    if 2 <= days <= 6:
        return f"{WD_SHORT[at.weekday()]} {hm}"
    return at.strftime("%d.%m ") + hm


def chip(t: dict, today: dt.date) -> str:
    """Подпись в строке дела: «⏰ 11:00», «⏰ дом»; отправленное и закрытое — пусто (DelaRemind.chip)."""
    if t.get("reminded_at") or t.get("status", "open") != "open":
        return ""
    at = at_of(t.get("remind_at")) if t.get("remind_at") else None
    if at:
        return "⏰ " + when_words(at, today)
    return f"⏰ {t['remind_place']}" if t.get("remind_place") else ""


def state(t: dict, today: dt.date) -> str:
    """Напоминание словами — для Claude и карточки (DelaRemind.state). Нет напоминания — пусто."""
    sent = at_of(t.get("reminded_at")) if t.get("reminded_at") else None
    if sent:
        return "напоминание отправлено в Telegram " + when_words(sent, today)
    at = at_of(t.get("remind_at")) if t.get("remind_at") else None
    if at:
        return (f"приехал «{t['remind_place']}» — " if t.get("remind_place") else "") + "напомню в Telegram " + when_words(at, today)
    if t.get("remind_place"):
        return f"напомню в Telegram, когда приедет: {t['remind_place']}"
    return ""


# ── Места ───────────────────────────────────────────────────────────────


def _norm(s: str | None) -> str:
    return re.sub(r"\s+", " ", (s or "").replace("ё", "е").replace("Ё", "Е").strip().lower())


def _stem(s: str) -> str:
    for _ in range(2):  # падежный хвост: «домой» → «дом», «даче» → «дач»
        if len(s) > 3 and s[-1] in "аеиоуыэюяйь":
            s = s[:-1]
    return s


def match_place(named: str | None, places: list[str]) -> str:
    """Место, как его назвали («домой», «на даче»), — в имя места телефона; нет такого — ""."""
    n = _norm(named)
    if not n:
        return ""
    for p in places:
        if _norm(p) == n:
            return p
    st = _stem(n)
    if len(st) < 3:
        return ""
    return next((p for p in places if _stem(_norm(p)) == st), "")


def places_of(conn, user: str) -> list[str]:
    """Места автопилота, которые телефон прислал серверу (user.settings {places: [...]})."""
    row = conn.execute("SELECT settings -> 'places' AS p FROM crm.users WHERE id = %s", (user,)).fetchone()
    raw = row["p"] if row and isinstance(row["p"], list) else []
    out: list[str] = []
    for p in raw:
        p = str(p).strip()
        if p and _norm(p) not in {_norm(x) for x in out}:
            out.append(p)
    return out


def places_block(places: list[str]) -> str:
    """Блок МЕСТА для общего промпта разбора ({PLACES}) — слово в слово DelaRemind.placesBlock."""
    if not places:
        return "МЕСТА: телефон пока не знает ни одного места — remind_place оставляй пустым, место словами пиши в notes."
    return "МЕСТА (remind_place — ровно одно имя из списка; телефон узнаёт приезд по Wi-Fi):\n" + ", ".join(places)


def place_note(said: str) -> str:
    """Место названо, а у телефона такого нет — строка в заметки дела, как пишет телефон."""
    return f"⏰ Напомнить, когда приеду: {said} — такого места нет у автопилота Засечки"


def is_home(place: str | None) -> bool:
    return _stem(_norm(place)) == "дом"


# ── Бот: что пора, кнопки ───────────────────────────────────────────────

DUE = """
SELECT t.id AS task_id, t.num, t.owner_id AS "user", u.telegram_id, t.title,
       p.name AS project, coalesce(pe.short, pe.name) AS person,
       t.due_date, t.due_time, t.remind_at, t.remind_place,
       greatest(0, floor(extract(epoch FROM now() - t.remind_at) / 60))::int AS late_min
FROM tasks.tasks t
JOIN crm.users u ON u.id = t.owner_id
LEFT JOIN crm.projects p ON p.id = t.project_id
LEFT JOIN crm.people pe ON pe.id = t.person_id
WHERE t.status = 'open' AND t.reminded_at IS NULL AND t.remind_at <= now() AND u.telegram_id IS NOT NULL
  -- Кому напоминания не нужны (Марианна: settings.reminders = false), тому — только те, что он
  -- поставил себе сам (или кнопкой бота под своим же напоминанием); чужих — нет (правило 6).
  AND (coalesce(u.settings ->> 'reminders', 'true') <> 'false'
       OR (SELECT h.actor FROM crm.history h
           WHERE h.entity = 'tasks.tasks' AND h.entity_id = t.id::text
             AND (h.after ? 'remind_at' OR h.after ? 'remind_place')
           ORDER BY h.id DESC LIMIT 1) IN (t.owner_id, %(bot)s))
ORDER BY t.remind_at, t.num
LIMIT %(limit)s
"""


def due(url: str, public_url: str = "") -> dict:
    """Что пора отправить: открытые дела с remind_at <= now(), не отправленные, у владельцев с
    telegram_id. Читает system (бот напоминает всем, у кого есть Telegram), журнал — от имени бота."""
    base = (public_url or "https://dela.kovcheg.am").rstrip("/")
    with db.session(url, "system", ACTOR, BOT) as conn:
        rows = conn.execute(DUE, {"bot": ACTOR, "limit": LIMIT}).fetchall()
        at = conn.execute("SELECT now() AS n").fetchone()["n"]
    out = []
    for r in rows:
        r = dict(r)
        r["url"] = f"{base}/#task/{r['num']}"
        out.append(r)
    return store.jsonable({"ok": True, "now": iso(at), "reminders": out})


ACTIONS = ("done", "snooze", "tomorrow")


def act(url: str, telegram_id: int, ref, action: str, minutes: int = 60) -> dict:
    """Кнопка под напоминанием: «Сделано», «Через N минут», «Завтра 9:00».

    Бот приходит своим токеном, а действует владелец дела — тот, кто нажал (его telegram_id):
    операция идёт от его имени, через его права (RLS), в журнал — бот. Чужое дело — отказ:
    напоминание приходит только владельцу, и кнопки под ним — его.
    """
    if action not in ACTIONS:
        raise RemindError(f"нет такой кнопки: {action!r}")
    with db.session(url, "system", ACTOR, BOT) as conn:
        u = conn.execute("SELECT id FROM crm.users WHERE telegram_id = %s", (int(telegram_id),)).fetchone()
        t = store.task_by(conn, ref) if u else None
    if not u:
        raise RemindError("этот Telegram Делам не знаком")
    if not t or t["owner_id"] != u["id"]:
        raise RemindError("это не твоё дело")
    if action == "done":
        op = {"op": "task.done", "id": str(t["id"])}
    else:
        if action == "snooze":
            when = now().replace(second=0, microsecond=0) + dt.timedelta(minutes=max(1, min(int(minutes), 7 * 24 * 60)))
        else:
            d = now().date() + dt.timedelta(days=1)
            when = dt.datetime(d.year, d.month, d.day, *MORNING, tzinfo=MSK)
        # Время вместо места: «Ты дома» через час было бы уже неправдой.
        op = {"op": "task.set", "id": str(t["id"]), "set": {"remind_at": iso(when), "remind_place": None}}
    r = store.apply_ops(url, u["id"], [op], BOT, ACTOR)["results"][0]
    if not r.get("ok"):
        raise RemindError(r.get("error") or "не вышло")
    return {"ok": True, "user": u["id"], "task": r["task"]}


# ── Перенос строк «⏰ Напомнить…» из заметок ─────────────────────────────

_PLACE = re.compile(r"^⏰ Напомнить, когда приеду: (.+?) " + re.escape(NOTES_TAIL) + r"\s*$", re.M)
_TIME = re.compile(r"^⏰ Напомнить: (.+?) " + re.escape(NOTES_TAIL) + r"\s*$", re.M)


def said_at(words: str, created: dt.datetime) -> str | None:
    """«11:00», «завтра 09:00», «чт 09:00», «12.10 09:00» (DelaRemind.whenWords) — от дня, когда дело
    заведено: телефон писал их относительно своего «сегодня»."""
    base = created.astimezone(MSK).date()
    m = re.fullmatch(r"(?:(завтра)|(пн|вт|ср|чт|пт|сб|вс)|(\d{2})\.(\d{2}))?\s*(\d{1,2}:\d{2})", words.strip())
    if not m:
        return None
    hm = norm_time(m.group(5))
    if not hm:
        return None
    if m.group(1):
        day = base + dt.timedelta(days=1)
    elif m.group(2):
        ahead = (WD_SHORT.index(m.group(2)) - base.weekday()) % 7
        day = base + dt.timedelta(days=ahead or 7)
    elif m.group(3):
        day = dt.date(base.year, int(m.group(4)), int(m.group(3)))
        if day < base:
            day = day.replace(year=base.year + 1)
    else:
        day = base
    h, mi = map(int, hm.split(":"))
    return iso(dt.datetime(day.year, day.month, day.day, h, mi, tzinfo=MSK))


def move_notes(url: str, apply: bool = True) -> list[str]:
    """Разовый перенос: пока сервер не знал напоминаний, телефон клал их строкой в заметки дела.
    Открытые дела с такой строкой получают remind_place или remind_at, строка из заметок уходит.
    Повтор безвреден: перенесённой строки в заметках больше нет."""
    done: list[str] = []
    with db.session(url, "system", "svc:remind-notes", "system") as conn:
        rows = conn.execute(
            "SELECT id, num, title, notes, created_at, remind_at, remind_place FROM tasks.tasks "
            "WHERE status = 'open' AND notes LIKE '%⏰ Напомнить%'").fetchall()
        for t in rows:
            notes, set_ = t["notes"], {}
            mp, mt = _PLACE.search(notes), _TIME.search(notes)
            if mp and not (t["remind_at"] or t["remind_place"]):
                set_["remind_place"] = mp.group(1).strip()
                notes = _PLACE.sub("", notes)
            elif mt and not (t["remind_at"] or t["remind_place"]):
                at = said_at(mt.group(1), t["created_at"])
                if not at:
                    continue
                set_["remind_at"] = at
                notes = _TIME.sub("", notes)
            else:
                continue
            set_["notes"] = re.sub(r"\n{3,}", "\n\n", notes).strip() or None
            what = f"remind_place «{set_['remind_place']}»" if "remind_place" in set_ else f"remind_at {set_['remind_at']}"
            done.append(f"#{t['num']} {t['title']}: {what}")
            if apply:
                store._update(conn, "tasks.tasks", {"id": t["id"]}, set_)
    return done
