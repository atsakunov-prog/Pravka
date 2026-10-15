"""Режим отладки Дел (15.10.2026, миграция dela_0031, правило 28 карты Дел): записки о багах логики с тем, что было на экране.

Владелец: «Давай сделаем в делах накопление багов. Прямо такой debug mode. Наверху где-то прямо текстбокс в телефоне и в
компе. И туда я просто накидываю баги, причём они идут с тем, что у меня сейчас на экране». Включён режим (настройка
`debug` в `users.settings` владельца — `python -m pravka_dela debug on`, тумблер в «Настройках» веба) — у веба и вкладки
Дел телефона вверху тонкая строка «Что не так? — запишу с экраном». Записка уходит операцией `debug.add` (телефон —
своей очередью, без сети тоже: op_id и id записки защищают от повтора) вместе со снимком экрана ДАННЫМИ — маршрут,
фильтры, открытая карточка, видимые строки — и последними действиями страницы: что ушло операциями, что сказал Claude
и что он поменял, тосты, ошибки сети. Это главное для багов логики: «сказал X — сделал Y».

Разбирает сессия Claude на компе: коннектор `dela_debug` (mcp_tools) или `python -m pravka_dela debug list | show N |
set N статус "ответ"`. Статус и ответ видны в вебе (`#/debug`) и на телефоне («Отладка» в боковине Дел).

Не путать с «🐞 Баг или предложение» телефона (FeedbackStore → архив → ночной разбор, docs/feedback.md): тот — про
приложение целиком и разбирается ночью; этот — про логику Дел, с экраном данными, и разбирается днём по живой базе.

Только владелец: не владельцу операции отказывают словами, вида нет, поля в синке нет; службам (их токены действуют
от имени владельца) — тоже нет: записки пишет человек со своего экрана.
"""

from __future__ import annotations

import datetime as dt
import json
import re
import uuid
from typing import Any

from psycopg.types.json import Jsonb

from . import db, digest

VIEW = "debug"
FEATURE = "debug"
STATUSES = ("new", "taken", "fixed", "not_bug", "later")
# Слова статусов — владельца (16.10.2026, приёмка режима: «новое / разбираю / починено / не баг / потом»); те же в вебе,
# на телефоне и в ответах Claude.
STATUS_WORD = {"new": "новое", "taken": "разбираю", "fixed": "починено", "not_bug": "не баг", "later": "потом"}
# Как Claude и человек называют статус словами — в CLI и коннекторе (и прежние слова ветки: «новая», «взял»).
STATUS_ALIAS = {"новое": "new", "новая": "new", "разбираю": "taken", "разбор": "taken", "взял": "taken", "в работе": "taken",
                "починено": "fixed", "починил": "fixed",
                "готово": "fixed", "не баг": "not_bug", "нет": "not_bug", "потом": "later", "notbug": "not_bug",
                "not-bug": "not_bug"}
SOURCES = ("web", "phone")
TEXT_MAX = 4000
ANSWER_MAX = 8000
SHORT_MAX = 80
SNAPSHOT_MAX = 400_000   # знаков JSON: больше — снимок ужимается (строки списка короче), но записка ложится всегда
ACTIONS_MAX = 100        # событий в записке
ACTION_MAX = 4000        # знаков одного события
SHOT_MAX = 400_000       # знаков строки data:image/… (телефон жмёт до ~150 КБ)
LIST_MAX = 200
AHEAD_SECONDS = 300      # часы устройства впереди — не ошибка, но и не «завтра»
MSK = dt.timezone(dt.timedelta(hours=3))  # в Москве нет перевода часов — как phone.py
COLUMNS = ("id, num, said_at, text, source, version, screen, status, answer, status_by, status_at, via, created_at, "
           "updated_at, rev, seq, shot IS NOT NULL AS has_shot")
FULL = COLUMNS + ", snapshot, actions, shot"


def _s(v: Any, n: int) -> str:
    return " ".join(str(v or "").split())[:n] if not isinstance(v, (dict, list)) else ""


def _text(v: Any, n: int) -> str:
    """Многострочный текст как есть (переносы строк — часть мысли), без пустых краёв."""
    if isinstance(v, (dict, list)) or v is None:
        return ""
    return str(v).replace("\r\n", "\n").strip()[:n]


def _uuid(v: Any) -> str | None:
    try:
        return str(uuid.UUID(str(v))) if v not in (None, "") else None
    except ValueError:
        return None


def status_of(v: Any) -> str | None:
    s = str(v or "").strip().lower()
    s = STATUS_ALIAS.get(s, s)
    return s if s in STATUSES else None


def is_on(conn, user: str) -> bool:
    row = conn.execute("SELECT role, coalesce((settings->>'debug')::boolean, false) AS on_ FROM crm.users WHERE id = %s",
                       (user,)).fetchone()
    return bool(row) and row["role"] == "owner" and bool(row["on_"])


def _owner_only(conn, user: str, err) -> None:
    if not digest.is_owner(conn, user):
        raise err("Отладка — только владельцу Дел: записки — про его экран")


def _not_service(conn, err) -> None:
    """Службы ходят токеном владельца (встречи, дайджест, бот): записку пишет человек со своего экрана, разбирает он или
    Claude (коннектор, командная строка) — не служба."""
    actor = conn.execute("SELECT crm.actor() AS a").fetchone()["a"] or ""
    if actor.startswith("svc:"):
        raise err(f"Отладка: службе {actor[4:]} записки не положены")


def _said_at(conn, v: Any) -> dt.datetime:
    now = conn.execute("SELECT now() AS n").fetchone()["n"]
    try:
        at = dt.datetime.fromisoformat(str(v).replace("Z", "+00:00")) if v not in (None, "") else None
    except ValueError:
        at = None
    # Записка важнее часов: кривое или будущее время — миг приёма (телефон без сети шлёт позже, это нормально).
    if at is None or at.tzinfo is None or at > now + dt.timedelta(seconds=AHEAD_SECONDS):
        return now
    return at


def _size(v: Any) -> int:
    return len(json.dumps(v, ensure_ascii=False, default=str))


def clean_snapshot(v: Any) -> dict:
    """Снимок — объект, не больше SNAPSHOT_MAX: длинный список строк режется с хвоста (сверху — то, что видно первым),
    не влез и так — остаются заголовок и маршрут с пометкой. Записка без снимка лучше, чем без записки."""
    snap = v if isinstance(v, dict) else {}
    if _size(snap) <= SNAPSHOT_MAX:
        return snap
    snap = dict(snap)
    rows = snap.get("rows") if isinstance(snap.get("rows"), list) else []
    while rows and _size(snap) > SNAPSHOT_MAX:
        rows = rows[: max(0, len(rows) * 2 // 3 - 1)]
        snap["rows"] = rows
        snap["rows_cut"] = True
    if _size(snap) <= SNAPSHOT_MAX:
        return snap
    keep = {k: snap[k] for k in ("v", "route", "title", "sub", "mode") if k in snap}
    return {**keep, "cut": f"снимок больше {SNAPSHOT_MAX // 1000} тыс. знаков — остался только заголовок"}


def clean_actions(v: Any) -> list:
    """Последние ACTIONS_MAX событий объектами; длинное событие — коротко (одно событие не съест записку)."""
    items = [x for x in v if isinstance(x, dict)] if isinstance(v, list) else []
    out = []
    for x in items[-ACTIONS_MAX:]:
        if _size(x) > ACTION_MAX:
            x = {k: x[k] for k in ("at", "kind", "text") if k in x}
            x["text"] = _s(x.get("text"), 500)
            x["cut"] = True
        out.append(x)
    return out


def clean_shot(v: Any) -> str | None:
    s = str(v or "")
    return s if s.startswith("data:image/") and len(s) <= SHOT_MAX else None


def _next_num(conn, user: str) -> int:
    # Номер по порядку: две записки разом (веб и телефон) не возьмут один — замок до конца транзакции.
    conn.execute("SELECT pg_advisory_xact_lock(hashtext('crm.debug_notes'), 0)")
    return conn.execute("SELECT coalesce(max(num), 0) + 1 AS n FROM crm.debug_notes WHERE owner_id = %s",
                        (user,)).fetchone()["n"]


def _who(conn) -> str:
    row = conn.execute("SELECT crm.actor() AS a, crm.via() AS v").fetchone()
    return "claude" if row["v"] in ("mcp", "cli") else (row["a"] or row["v"] or "?")


def op_add(conn, user: str, op: dict, err) -> dict:
    """Записка: {note: {id?, text, source, version?, screen?, at?, snapshot?, actions?, shot?}}. Тот же id второй раз —
    прежняя записка (телефон повторил после обрыва связи, а ответ сервера потерялся)."""
    _owner_only(conn, user, err)
    _not_service(conn, err)
    n = op.get("note") if isinstance(op.get("note"), dict) else {}
    text = _text(n.get("text"), TEXT_MAX)
    if not text:
        raise err("Отладка: пустая записка — напиши, что не так")
    source = str(n.get("source") or "")
    if source not in SOURCES:
        raise err("Отладка: source — web или phone")
    nid = _uuid(n.get("id"))
    if n.get("id") not in (None, "") and nid is None:
        raise err("Отладка: id записки — не uuid")
    if nid:
        was = conn.execute(f"SELECT {COLUMNS} FROM crm.debug_notes WHERE id = %s", (nid,)).fetchone()
        if was:
            return {"note": was, "again": True}
    shot_in = n.get("shot")
    shot = clean_shot(shot_in)
    snapshot = clean_snapshot(n.get("snapshot"))
    if shot_in and not shot:
        snapshot = {**snapshot, "shot_dropped": "картинка не легла: не data:image/… или больше 400 тыс. знаков"}
    row = conn.execute(
        f"INSERT INTO crm.debug_notes (id, owner_id, num, said_at, text, source, version, screen, snapshot, actions, shot, via) "
        f"VALUES (coalesce(%s::uuid, gen_random_uuid()), %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, crm.via()) RETURNING {COLUMNS}",
        (nid, user, _next_num(conn, user), _said_at(conn, n.get("at")), text, source, _s(n.get("version"), SHORT_MAX),
         _s(n.get("screen"), SHORT_MAX), Jsonb(snapshot), Jsonb(clean_actions(n.get("actions"))), shot)).fetchone()
    return {"note": row}


def _find(conn, user: str, ref: Any, err, full: bool = False) -> dict:
    """Записка по id или по номеру («12», «№12», «#12»)."""
    cols = FULL if full else COLUMNS
    key = _uuid(ref)
    if key:
        row = conn.execute(f"SELECT {cols} FROM crm.debug_notes WHERE id = %s AND owner_id = %s", (key, user)).fetchone()
    else:
        m = re.fullmatch(r"\s*[№#]?\s*(\d{1,9})\s*", str(ref or ""))
        if not m:
            raise err("Отладка: нужна записка — номер или id")
        row = conn.execute(f"SELECT {cols} FROM crm.debug_notes WHERE num = %s AND owner_id = %s",
                           (int(m.group(1)), user)).fetchone()
    if not row:
        raise err(f"Отладка: нет записки {ref}")
    return row


def op_set(conn, user: str, op: dict, err) -> dict:
    """Разбор: {id | num, status?, answer?} — статус словом из STATUSES, ответ — текстом целиком (заменяет прежний)."""
    _owner_only(conn, user, err)
    _not_service(conn, err)
    row = _find(conn, user, op.get("id") or op.get("num"), err)
    sets, args = [], []
    if op.get("status") not in (None, ""):
        st = status_of(op.get("status"))
        if not st:
            raise err("Отладка: статус — " + ", ".join(STATUSES))
        if st != row["status"]:
            sets.append("status = %s")
            args.append(st)
    if "answer" in op and op.get("answer") is not None:
        answer = _text(op.get("answer"), ANSWER_MAX)
        if answer != row["answer"]:
            sets.append("answer = %s")
            args.append(answer)
    if not sets:
        if op.get("status") in (None, "") and op.get("answer") is None:
            raise err("Отладка: поменять нечего — нужен status или answer")
        return {"note": row}
    sets += ["status_by = %s", "status_at = now()"]
    args.append(_who(conn))
    row = conn.execute(f"UPDATE crm.debug_notes SET {', '.join(sets)} WHERE id = %s RETURNING {COLUMNS}",
                       (*args, row["id"])).fetchone()
    return {"note": row}


def view_debug(conn, user: str, err, id=None, status=None, limit=None, **_) -> dict:
    """Список свежих сверху (status — один или через запятую; open — new и taken) или одна записка целиком (id — номер
    или uuid): снимок, действия, картинка."""
    _owner_only(conn, user, err)
    counts = {s: 0 for s in STATUSES} | {r["status"]: r["n"] for r in conn.execute(
        "SELECT status, count(*) AS n FROM crm.debug_notes WHERE owner_id = %s GROUP BY status", (user,))}
    on = is_on(conn, user)
    if id not in (None, ""):
        return {"note": _find(conn, user, id, err, full=True), "on": on, "counts": counts}
    want = [s.strip() for s in str(status or "").split(",") if s.strip()]
    want = sorted({x for s in want for x in (("new", "taken") if s == "open" else (status_of(s) or s,))})
    bad = [s for s in want if s not in STATUSES]
    if bad:
        raise err("Отладка: статус — " + ", ".join(STATUSES) + " или open")
    try:
        n = max(1, min(LIST_MAX, int(limit or 50)))
    except (TypeError, ValueError):
        raise err("Отладка: limit — число") from None
    # В списке — снимок коротко: маршрут и заголовок, чтобы было видно, с какого экрана записка.
    items = conn.execute(
        f"SELECT {COLUMNS}, snapshot->>'route' AS route, snapshot->>'title' AS title FROM crm.debug_notes "
        "WHERE owner_id = %s AND (%s::text[] IS NULL OR status = ANY(%s::text[])) ORDER BY num DESC LIMIT %s",
        (user, want or None, want or None, n)).fetchall()
    return {"items": items, "on": on, "counts": counts}


def for_sync(conn, user: str) -> dict | None:
    """Поле debug синка: включён ли режим, сколько записок ждут разбора, номер последней и когда разбор что-то поменял
    (телефон по нему перечитывает список). Не владельцу — None: поля нет."""
    if not digest.is_owner(conn, user):
        return None
    row = conn.execute(
        "SELECT count(*) FILTER (WHERE status = 'new') AS new, coalesce(max(num), 0) AS last, max(status_at) AS answered "
        "FROM crm.debug_notes WHERE owner_id = %s", (user,)).fetchone()
    return {"on": is_on(conn, user), "new": row["new"], "last": row["last"], "answered": row["answered"]}


def register(handlers: dict, views: dict, features: list, err) -> None:
    handlers["debug.add"] = lambda conn, user, op: op_add(conn, user, op, err)
    handlers["debug.set"] = lambda conn, user, op: op_set(conn, user, op, err)
    views[VIEW] = lambda conn, user, **p: view_debug(conn, user, err, **p)
    if FEATURE not in features:
        features.append(FEATURE)


# ── Словами: для Claude (коннектор) и командной строки ─────────────────────


def _msk(v: Any) -> str:
    if isinstance(v, str):
        try:
            v = dt.datetime.fromisoformat(v)
        except ValueError:
            return v
    return v.astimezone(MSK).strftime("%d.%m %H:%M:%S") if isinstance(v, dt.datetime) else str(v or "")


def _one_line(v: Any, n: int = 300) -> str:
    s = v if isinstance(v, str) else json.dumps(v, ensure_ascii=False, default=str)
    s = " ".join(s.split())
    return s if len(s) <= n else s[: n - 1] + "…"


def line(x: dict) -> str:
    """Записка строкой списка: «№12 15.10 21:40 · web · новая · Сегодня — текст»."""
    where = " · ".join(p for p in (x.get("title") or "", x.get("route") or "") if p)
    head = f"№{x['num']} {_msk(x['said_at'])} · {x['source']}" + (f" {x['version']}" if x.get("version") else "") \
        + f" · {STATUS_WORD.get(x['status'], x['status'])}" + (f" · {where}" if where else "") \
        + (" · картинка" if x.get("has_shot") else "")
    out = head + "\n    " + _one_line(x["text"], 600)
    if x.get("answer"):
        out += "\n    ответ: " + _one_line(x["answer"], 300)
    return out


ROW_KEYS = ("group", "due", "person", "project", "why", "status", "meta")
# Вложенность строки (правило 27; поезд 16.10.2026): веб и телефон шлют её данными — родитель, место, сколько внутри
# открыто, отступ на экране, свёрнут ли родитель «Деревом».
NEST_KEYS = ("depth", "in", "pos", "inside", "folded")


def _nest(r: dict) -> list[str]:
    """Вложенность словами: «внутри #12 …», «место 2», «внутри открыто 3 (свёрнуто)»."""
    out = []
    if r.get("in"):
        out.append(f"внутри {r['in']}")
    if r.get("pos") not in (None, "", 0):
        out.append(f"место {r['pos']}")
    if r.get("inside"):
        out.append(f"внутри открыто {r['inside']}" + (" (свёрнуто)" if r.get("folded") else ""))
    return out


def _row(r: dict) -> str:
    if not isinstance(r, dict):
        return "  " + _one_line(r, 200)
    num = f"#{r['num']} " if r.get("num") else ""
    try:
        depth = max(0, min(int(r.get("depth") or 0), 4))
    except (TypeError, ValueError):
        depth = 0
    parts = _nest(r) + [str(r.get(k)) for k in ROW_KEYS if r.get(k) not in (None, "", [], {})]
    rest = {k: v for k, v in r.items()
            if k not in (*ROW_KEYS, *NEST_KEYS, "num", "title", "id", "in_view") and v not in (None, "", [], {})}
    return ("  " + ("* " if r.get("in_view") else "  ") + "  " * depth + ("↳ " if depth else "") + num
            + str(r.get("title") or r.get("id") or "?")
            + (" — " + " · ".join(parts) if parts else "") + (" " + _one_line(rest, 200) if rest else ""))


def snapshot_lines(s: dict) -> list[str]:
    """Снимок экрана читаемо: заголовок и маршрут, режим, карточка полями, строки списка (* — были в окне), прочее —
    одной строкой JSON на ключ."""
    if not s:
        return ["  (снимка нет)"]
    out = []
    if s.get("title") or s.get("route"):
        out.append(f"  экран: {s.get('title') or ''}" + (f"  [{s['route']}]" if s.get("route") else "")
                   + (f" — {s['sub']}" if s.get("sub") else ""))
    if s.get("mode"):
        out.append("  режим: " + _one_line(s["mode"], 600))
    card = s.get("card")
    if isinstance(card, dict) and card:
        out.append(f"  открыто: {card.get('kind') or ''} " + (f"#{card['num']} " if card.get("num") else "")
                   + str(card.get("title") or card.get("id") or ""))
        fields = card.get("fields") if isinstance(card.get("fields"), dict) else {}
        for k, v in fields.items():
            if v in (None, "", [], {}):
                continue
            # Список строк («Внутри» по месту, правило 27) — строкой на пункт, а не одной строкой JSON.
            if isinstance(v, list) and all(isinstance(x, str) for x in v):
                out.append(f"    {k}:")
                out += [f"      {_one_line(x, 300)}" for x in v[:60]]
            else:
                out.append(f"    {k}: {_one_line(v, 400)}")
        # Что карточка показывала текстом (веб: панель карточки или страница) — без него Claude видел бы только поля.
        if card.get("text"):
            out.append("    на экране текстом: " + _one_line(card["text"], 1500))
    if s.get("selected"):
        out.append("  выбрано галочками: " + _one_line(s["selected"], 400))
    rows = s.get("rows") if isinstance(s.get("rows"), list) else []
    if rows:
        total = s.get("rows_total") or len(rows)
        out.append(f"  строки списка ({len(rows)}" + (f" из {total}" if total != len(rows) else "") + "; * — в окне):")
        out += [_row(r) for r in rows]
    for k, v in s.items():
        if k in ("v", "title", "route", "sub", "mode", "card", "selected", "rows", "rows_total") or v in (None, "", [], {}):
            continue
        out.append(f"  {k}: {_one_line(v, 1500)}")
    return out


def action_lines(a: list) -> list[str]:
    if not a:
        return ["  (действий нет)"]
    out = []
    for x in a:
        at = str(x.get("at") or "")
        hm = _msk(at)[6:] if "T" in at else at
        data = {k: v for k, v in x.items() if k not in ("at", "kind", "text")}
        out.append(f"  {hm} {x.get('kind') or '?'}: {_one_line(x.get('text') or '', 600)}"
                   + (f"  {_one_line(data, 800)}" if data else ""))
    return out


def card_text(v: dict) -> str:
    x = v["note"]
    head = (f"Записка №{x['num']} — {_msk(x['said_at'])} · {x['source']}" + (f" {x['version']}" if x.get("version") else "")
            + (f" · экран {x['screen']}" if x.get("screen") else "") + f" · {STATUS_WORD.get(x['status'], x['status'])}"
            + (f" ({x['status_by']}, {_msk(x['status_at'])})" if x.get("status_at") else ""))
    out = [head, "", x["text"]]
    if x.get("answer"):
        out += ["", "Ответ разбора:", x["answer"]]
    out += ["", "Что было на экране:"] + snapshot_lines(x.get("snapshot") or {})
    out += ["", "Последние действия (старые сверху):"] + action_lines(x.get("actions") or [])
    if x.get("shot"):
        out += ["", f"Картинка экрана: есть ({len(x['shot']) // 1000} тыс. знаков data:) — в вебе #/debug, в CLI — show N --shot файл"]
    return "\n".join(out)


def list_text(v: dict, what: str) -> str:
    c = v.get("counts") or {}
    total = " · ".join(f"{STATUS_WORD[s]} {c[s]}" for s in STATUSES if c.get(s))
    head = f"Записки отладки ({what}): {len(v['items'])}" + (f"; всего — {total}" if total else "") \
        + ("" if v.get("on") else "; режим сейчас выключен") + ". Целиком — по номеру."
    return "\n".join([head] + [line(x) for x in v["items"]]) if v["items"] else head.replace(": 0", ": нет")


def _owner(url: str) -> str:
    with db.session(url, "system", via="cli") as conn:
        owner = conn.execute("SELECT crm.owner_id() AS id").fetchone()["id"]
    if not owner:
        raise RuntimeError("в Делах нет владельца")
    return owner


def tool(url: str, user: str, action: str = "list", note: str | None = None, status: str | None = None,
         answer: str | None = None, show_all: bool = False, via: str = "mcp") -> str:
    """Один вход для коннектора и командной строки: list — новые и в разборе (show_all — все), show — записка целиком,
    set — статус и/или ответ."""
    from . import store

    status = None if status in (None, "", "-") else status
    try:
        if action == "show":
            return card_text(store.view(url, user, VIEW, id=note))
        if action == "set":
            op = {"op": "debug.set", "id": note, "status": status, "answer": answer}
            r = store.apply_ops(url, user, [op], via=via, actor=user)["results"][0]
            if not r["ok"]:
                return "Не вышло: " + r["error"]
            x = r["note"]
            return f"№{x['num']}: {STATUS_WORD.get(x['status'], x['status'])}" + (" · ответ записан" if answer else "")
        if action == "on" or action == "off":
            # Включает строку владельцу: не владельцу настройка ничего не дала бы (строки у него нет) — отказ словами.
            with db.session(url, user, via=via) as conn:
                _owner_only(conn, user, store.OpError)
            r = store.apply_ops(url, user, [{"op": "user.settings", "settings": {"debug": action == "on"}}], via=via,
                                actor=user)["results"][0]
            return ("Режим отладки включён: строка «Что не так?» — вверху веба и вкладки Дел телефона (после синка)."
                    if action == "on" else "Режим отладки выключен: строки больше нет, записки целы.") \
                if r["ok"] else "Не вышло: " + r["error"]
        v = store.view(url, user, VIEW, status=None if show_all else "open", limit=LIST_MAX if show_all else 50)
        return list_text(v, "все" if show_all else "новое и разбираю")
    except store.OpError as e:
        return f"Не вышло: {e}"


def add_cli(sub) -> None:
    p = sub.add_parser("debug", help="режим отладки: записки о багах с экраном — list, show N, set N статус «ответ», on/off")
    p.add_argument("action", nargs="?", choices=["list", "show", "set", "on", "off"], default="list")
    p.add_argument("note", nargs="?", help="номер записки (show, set)")
    p.add_argument("status", nargs="?", help="set: " + ", ".join(STATUSES) + " (или словом: разбираю, починено, не баг, потом…)")
    p.add_argument("answer", nargs="?", help="set: ответ разбора текстом (заменяет прежний)")
    p.add_argument("--all", action="store_true", help="list: все записки, а не только новое и разбираю")
    p.add_argument("--answer-file", help="set: ответ из файла (длинный, с переносами)")
    p.add_argument("--json", action="store_true", help="show: записка как есть, JSON")
    p.add_argument("--shot", help="show: сохранить картинку экрана в этот файл")
    p.add_argument("--user", help="от чьего имени (с завода — владелец Дел); не владельцу записки закрыты — так это и проверяют")
    p.set_defaults(fn=cmd)


def cmd(args) -> int:
    """`python -m pravka_dela debug …` — сессия Claude на компе читает записки и отвечает на них."""
    import base64
    from pathlib import Path

    import sys

    from . import config as config_mod, store

    # Записка может нести эмодзи и всё что угодно: консоль в cp1251 упала бы на печати — непечатное заменяем «?».
    try:
        sys.stdout.reconfigure(errors="replace")
    except (AttributeError, ValueError):
        pass
    cfg = config_mod.load(args.env)
    user = args.user or _owner(cfg.db_url)
    if args.action in ("show", "set") and not args.note:
        print("нужен номер записки")
        return 2
    if args.action == "show" and (args.json or args.shot):
        try:
            v = store.view(cfg.db_url, user, VIEW, id=args.note)
        except store.OpError as e:
            print(f"Не вышло: {e}")
            return 1
        x = v["note"]
        if args.shot:
            shot = x.get("shot") or ""
            if not shot:
                print(f"у записки №{x['num']} картинки нет")
                return 1
            Path(args.shot).write_bytes(base64.b64decode(shot.split(",", 1)[1]))
            print(f"картинка №{x['num']} → {args.shot}")
        if args.json:
            print(json.dumps(store.jsonable({k: v_ for k, v_ in x.items() if k != "shot"}), ensure_ascii=False, indent=1))
        return 0
    answer = Path(args.answer_file).read_text(encoding="utf-8-sig") if args.answer_file else args.answer
    if args.action == "set" and not args.status and answer is None:
        print("set: нужен статус (" + ", ".join(STATUSES) + ") и/или ответ")
        return 2
    out = tool(cfg.db_url, user, args.action, note=args.note, status=args.status, answer=answer, show_all=args.all, via="cli")
    print(out)
    return 1 if out.startswith("Не вышло") else 0
