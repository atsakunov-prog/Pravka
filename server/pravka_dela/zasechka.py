"""Команды Засечки из веба телефону (dela_0016, 14.10.2026): «Начать», «Стоп», «Поправить» с компьютера.

Правда ленты — телефон (ZasechkaStore; docs/agreements.md «Лента — незаменимые данные»), архив — её копия. Веб день
в архив не пишет: телефон следующей отправкой перезатёр бы его правку, а она — телефонную. Поэтому кнопка в вебе
ставит команду; телефон забирает её синком Дел (поле zasechka), берёт (zasechka.take — одна команда одному
устройству: два телефона владельца не заведут одно дело дважды), применяет тем же путём, что кнопки и голос, и
отвечает (zasechka.done: сделал или почему нет). День уходит в архив как обычно — и веб видит его оттуда. Пока
команда ждёт, веб пишет «ждёт телефона». Контракт — server/contract/dela-zasechka.json, телефону — docs/zasechka.md
«Команды из веба».

Только владелец: лента — его. Не владельцу операции отказывают словами, поля в синке нет.
"""

from __future__ import annotations

import datetime as dt
import re
import uuid
from typing import Any

from psycopg.types.json import Jsonb

from . import digest

KINDS = ("start", "stop", "edit")
EXPIRE_HOURS = 12        # не взята за полсуток — устарела: «Начать» вчера вечером утром уже неправда
AHEAD_SECONDS = 120      # часы компа и сервера расходятся — миг нажатия чуть в будущем не ошибка
TITLE_MAX = 120
WORD_MAX = 80
NOTE_MAX = 500
DEVICE_MAX = 80
SYNC_MAX = 20
RECENT_MAX = 20
HM = re.compile(r"^(?:[01]\d|2[0-3]):[0-5]\d$|^24:00$")
COLUMNS = "id, kind, args, said_at, via, status, taken_by, taken_at, done_at, note, entry_id, created_at, rev, seq"


def _s(v: Any, n: int) -> str:
    return " ".join(str(v or "").split())[:n]


def _uuid(v: Any, what: str, err) -> str | None:
    if v in (None, ""):
        return None
    try:
        return str(uuid.UUID(str(v)))
    except ValueError:
        raise err(f"Засечка: {what} — не uuid") from None


def _entry(v: Any, err) -> int:
    try:
        n = int(v)
    except (TypeError, ValueError):
        raise err("Засечка: нужен entry_id — номер записи ленты") from None
    if n <= 0:
        raise err("Засечка: entry_id — номер записи ленты")
    return n


def _hm(v: Any, what: str, err) -> str:
    s = str(v or "").strip()
    if not HM.match(s):
        raise err(f"Засечка: {what} — время «ЧЧ:ММ»")
    return s


def clean_args(kind: Any, args: Any, err) -> dict:
    """Что веб просит сделать — в том виде, какой телефон применит без догадок."""
    if kind not in KINDS:
        raise err(f"Засечка: команда — одна из {', '.join(KINDS)}")
    a = args if isinstance(args, dict) else {}
    if kind == "start":
        title = _s(a.get("title"), TITLE_MAX)
        if not title:
            raise err("Засечка: чем занят — пусто")
        out = {"title": title, "category": _s(a.get("category"), WORD_MAX), "client": _s(a.get("client"), WORD_MAX)}
        for k in ("task_id", "project_id"):
            if (v := _uuid(a.get(k), k, err)) is not None:
                out[k] = v
        return out
    if kind == "stop":
        return {"entry_id": _entry(a.get("entry_id"), err)}
    # edit: запись, как её видел веб (was), и что поменять (set). Телефон сверяет was — запись успели поправить на
    # телефоне — и тогда не трогает её: человек против человека, свежее слово — телефона.
    day = str(a.get("day") or "")
    try:
        dt.date.fromisoformat(day)
    except ValueError:
        raise err("Засечка: day — сутки записи ГГГГ-ММ-ДД") from None
    was_in, set_in = a.get("was"), a.get("set")
    if not isinstance(was_in, dict) or not isinstance(set_in, dict):
        raise err("Засечка: поправить — нужны was и set")
    was = {"title": _s(was_in.get("title"), TITLE_MAX), "category": _s(was_in.get("category"), WORD_MAX),
           "start": _hm(was_in.get("start"), "was.start", err),
           "end": _hm(was_in.get("end"), "was.end", err) if was_in.get("end") else ""}
    new: dict[str, str] = {}
    if "title" in set_in:
        new["title"] = _s(set_in["title"], TITLE_MAX)
        if not new["title"]:
            raise err("Засечка: название — пусто")
    if "category" in set_in:
        new["category"] = _s(set_in["category"], WORD_MAX)
    if "start" in set_in:
        new["start"] = _hm(set_in["start"], "set.start", err)
    if "end" in set_in:
        if not was["end"]:
            raise err("Засечка: у идущего дела конца нет — его ставит «Стоп»")
        new["end"] = _hm(set_in["end"], "set.end", err)
    new = {k: v for k, v in new.items() if v != was.get(k)}
    if not new:
        raise err("Засечка: поправить нечего — всё как было")
    start, end = new.get("start", was["start"]), new.get("end", was["end"])
    if end and end != "24:00" and end <= start and not (end == "00:00"):
        raise err("Засечка: конец раньше начала")
    return {"entry_id": _entry(a.get("entry_id"), err), "day": day, "was": was, "set": new}


def _said_at(conn, v: Any, err) -> dt.datetime:
    now = conn.execute("SELECT now() AS n").fetchone()["n"]
    if v in (None, ""):
        return now
    try:
        at = dt.datetime.fromisoformat(str(v).replace("Z", "+00:00"))
    except ValueError:
        raise err("Засечка: at — миг ISO с поясом") from None
    if at.tzinfo is None:
        raise err("Засечка: at — с поясом")
    if at > now + dt.timedelta(seconds=AHEAD_SECONDS) or at < now - dt.timedelta(hours=EXPIRE_HOURS):
        raise err(f"Засечка: миг команды — не позже «сейчас» и не раньше {EXPIRE_HOURS} часов назад")
    return at


def _owner_only(conn, user: str, err) -> None:
    if not digest.is_owner(conn, user):
        raise err("Засечка — только владельцу Дел: лента его")


def expire(conn, user: str) -> None:
    """Не взятое за полсуток — устарело: телефон не начнёт вчерашнее «Начать» утром."""
    conn.execute(
        "UPDATE crm.zasechka_cmds SET status = 'expired', note = coalesce(note, %s) "
        "WHERE owner_id = %s AND status = 'wait' AND said_at < now() - make_interval(hours => %s)",
        (f"телефон не взял за {EXPIRE_HOURS} часов", user, EXPIRE_HOURS))


def _one(conn, user: str, cid: Any, err) -> dict:
    key = _uuid(cid, "id", err)
    if not key:
        raise err("Засечка: нужен id команды")
    row = conn.execute(f"SELECT {COLUMNS} FROM crm.zasechka_cmds WHERE id = %s AND owner_id = %s FOR UPDATE",
                       (key, user)).fetchone()
    if not row:
        raise err("Засечка: нет такой команды")
    return row


def op_cmd(conn, user: str, op: dict, err) -> dict:
    """Веб (или любой клиент владельца) ставит команду. Ответ — строка команды: её номер веб показывает «ждёт»."""
    _owner_only(conn, user, err)
    c = op.get("cmd") if isinstance(op.get("cmd"), dict) else {}
    args = clean_args(c.get("kind"), c.get("args"), err)
    at = _said_at(conn, c.get("at"), err)
    row = conn.execute(
        f"INSERT INTO crm.zasechka_cmds (owner_id, kind, args, said_at, via) VALUES (%s, %s, %s, %s, crm.via()) "
        f"RETURNING {COLUMNS}", (user, c["kind"], Jsonb(args), at)).fetchone()
    return {"cmd": row}


def op_cancel(conn, user: str, op: dict, err) -> dict:
    """Снять ждущую команду (передумал). Взятую телефоном не снять — она уже в ленте или вот-вот будет."""
    _owner_only(conn, user, err)
    row = _one(conn, user, op.get("id"), err)
    if row["status"] == "cancelled":
        return {"cmd": row}
    if row["status"] != "wait":
        raise err("Засечка: команду уже " + {"taken": "взял телефон", "done": "сделал телефон",
                                              "refused": "телефон не стал делать", "expired": "не взяли — устарела"}
                  .get(row["status"], row["status"]) + " — снять нельзя")
    row = conn.execute(f"UPDATE crm.zasechka_cmds SET status = 'cancelled', note = 'снята в вебе' WHERE id = %s "
                       f"RETURNING {COLUMNS}", (row["id"],)).fetchone()
    return {"cmd": row}


def _device(op: dict, err) -> str:
    d = _s(op.get("device"), DEVICE_MAX)
    if not d:
        raise err("Засечка: нужен device — имя телефона, который берёт команду")
    return d


def op_take(conn, user: str, op: dict, err) -> dict:
    """Телефон берёт команду перед тем, как применить: одна команда — одному устройству. Повтор тем же — безвреден."""
    _owner_only(conn, user, err)
    device = _device(op, err)
    expire(conn, user)
    row = _one(conn, user, op.get("id"), err)
    st = row["status"]
    if st == "wait":
        row = conn.execute(f"UPDATE crm.zasechka_cmds SET status = 'taken', taken_by = %s, taken_at = now() "
                           f"WHERE id = %s RETURNING {COLUMNS}", (device, row["id"])).fetchone()
        return {"cmd": row}
    if st in ("taken", "done", "refused") and row["taken_by"] == device:
        return {"cmd": row}  # повтор после обрыва связи: своё — своё
    if st in ("taken", "done", "refused"):
        raise err(f"Засечка: команду уже взял другой телефон ({row['taken_by']})")
    raise err("Засечка: команду " + ("сняли в вебе" if st == "cancelled" else "не взяли вовремя — устарела"))


def op_done(conn, user: str, op: dict, err) -> dict:
    """Телефон применил команду (ok) или не стал (note — почему): веб покажет это словами. Повтор — безвреден."""
    _owner_only(conn, user, err)
    device = _device(op, err)
    row = _one(conn, user, op.get("id"), err)
    ok = op.get("ok")
    if not isinstance(ok, bool):
        raise err("Засечка: ok — да или нет")
    note = _s(op.get("note"), NOTE_MAX) or None
    entry = op.get("entry_id")
    entry = int(entry) if isinstance(entry, int) or (isinstance(entry, str) and entry.isdigit()) else None
    status = "done" if ok else "refused"
    if row["status"] in ("done", "refused") and row["taken_by"] == device:
        return {"cmd": row}
    if row["status"] not in ("wait", "taken") or (row["status"] == "taken" and row["taken_by"] != device):
        raise err("Засечка: команду " + ("взял другой телефон" if row["status"] == "taken" else "уже не ждут: " + row["status"]))
    row = conn.execute(
        f"UPDATE crm.zasechka_cmds SET status = %s, taken_by = %s, taken_at = coalesce(taken_at, now()), done_at = now(), "
        f"note = %s, entry_id = %s WHERE id = %s RETURNING {COLUMNS}", (status, device, note, entry, row["id"])).fetchone()
    return {"cmd": row}


def for_sync(conn, user: str) -> list[dict] | None:
    """Поле zasechka синка: команды, которые ждут телефона или взяты, но ещё не отмечены. Не владельцу — None (поля нет:
    телефон Марианны о Засечке владельца не знает ничего)."""
    if not digest.is_owner(conn, user):
        return None
    expire(conn, user)
    return conn.execute(
        f"SELECT {COLUMNS} FROM crm.zasechka_cmds WHERE owner_id = %s AND status IN ('wait', 'taken') "
        "ORDER BY said_at, created_at LIMIT %s", (user, SYNC_MAX)).fetchall()


def recent(conn, user: str) -> list[dict]:
    """Команды за полсуток, свежие сверху — веб показывает «ждёт телефона», «сделано», «телефон не стал: …»."""
    expire(conn, user)
    return conn.execute(
        f"SELECT {COLUMNS} FROM crm.zasechka_cmds WHERE owner_id = %s AND said_at > now() - make_interval(hours => %s) "
        "ORDER BY said_at DESC, created_at DESC LIMIT %s", (user, EXPIRE_HOURS, RECENT_MAX)).fetchall()


def register(handlers: dict, err) -> None:
    handlers["zasechka.cmd"] = lambda conn, user, op: op_cmd(conn, user, op, err)
    handlers["zasechka.cancel"] = lambda conn, user, op: op_cancel(conn, user, op, err)
    handlers["zasechka.take"] = lambda conn, user, op: op_take(conn, user, op, err)
    handlers["zasechka.done"] = lambda conn, user, op: op_done(conn, user, op, err)
