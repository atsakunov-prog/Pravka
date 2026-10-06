"""Логика Дел поверх базы: синк, операции, виды.

Один код на всех клиентов: API службы (телефон, веб, бот), инструменты Claude
в архиве и импорт. Все ходят ролью службы, поэтому права решает база (RLS),
а журнал пишет триггер — здесь только разбор, проверки и форма ответа.

Операция — словарь {"op": "task.set", "op_id": "<uuid>", ...}. op_id делает
повтор безвредным: телефон без сети копит очередь и шлёт её, пока не получит
ответ, а сервер на повтор отдаёт прежний результат (crm.ops_seen).
"""

from __future__ import annotations

import datetime as dt
import decimal
import uuid
from collections.abc import Callable
from typing import Any

import psycopg
from psycopg import sql
from psycopg.types.json import Jsonb

from . import db

# Сколько номеров изменений синк захватывает назад. Номер берётся в начале
# транзакции, а видна она после фиксации: изменение с меньшим номером может
# стать видно позже большего. Окно с запасом перекрывает такие опоздания;
# повтор строки телефону безвреден (он сверяет rev).
OVERLAP = 200

TASK_FIELDS = {
    "title", "notes", "project_id", "deal_id", "owner_id", "ball", "person_id", "nudge_on", "requested_by",
    "due_date", "due_time", "estimate_min", "money", "want", "focus_on", "labels", "status",
    "source", "source_ref", "remind_at", "remind_place",
}
# reminded_at в TASK_FIELDS нет: его ставит только сервер (task.reminded от бота).

# Что этот сервер умеет сверх части 1 контракта — в каждом ответе синка и /api/me. Без «remind»
# телефон полей remind_* не шлёт: _pick отверг бы операцию целиком, а с ней и дело.
FEATURES = ["remind"]
# Токен службы бота Ковчега (python -m pravka_dela token --name kovcheg): только он забирает
# «что пора» и отмечает отправку (remind.py, task.reminded).
REMIND_BOT = "kovcheg"
PROJECT_FIELDS = {"name", "aliases", "sphere", "kind", "org_id", "money_default", "note", "archived_at"}
DEAL_FIELDS = {
    "project_id", "name", "stage", "deal_type", "lead_person_id", "person_ids", "fee_kop", "deadline",
    "wheel", "ball", "next_step", "my_view", "ideas", "log",
    "outcome", "lost_reason", "closed_on", "fee_kind", "retainer_kop", "success_pct", "probability", "expected_on",
    "source_person_id", "team_ids",
}
PERSON_FIELDS = {
    "name", "short", "aliases", "org_id", "role", "phones", "emails", "telegram_id", "telegram_username",
    "birth_day", "birth_month", "birth_year", "source", "seeks", "offers", "traits", "cadence", "hub",
    "note", "archived_at",
}
ORG_FIELDS = {"name", "aliases", "kind", "note", "archived_at"}
INTERACTION_FIELDS = {"at", "kind", "summary", "next_step", "project_id", "deal_id", "person_ids", "source", "source_ref", "duration_min"}
PAYMENT_FIELDS = {"deal_id", "kind", "title", "amount_kop", "due_on", "invoiced_on", "paid_on", "cancelled_at", "note"}
SUGGESTION_FIELDS = {"kind", "task_id", "payload", "source", "source_ref", "quote", "batch_ref", "batch_title", "dup_of", "for_user", "expires_at"}

SUGGESTION_TTL = dt.timedelta(days=7)


class OpError(Exception):
    """Ошибка операции, которую повтор не исправит: её запоминаем вместе с op_id."""


# ── Форма данных ────────────────────────────────────────────────────────


def jsonable(v: Any) -> Any:
    if isinstance(v, dict):
        return {k: jsonable(x) for k, x in v.items() if k != "search"}
    if isinstance(v, (list, tuple)):
        return [jsonable(x) for x in v]
    if isinstance(v, uuid.UUID):
        return str(v)
    if isinstance(v, dt.datetime):
        return v.isoformat(timespec="seconds")
    if isinstance(v, dt.date):
        return v.isoformat()
    if isinstance(v, dt.time):
        return v.strftime("%H:%M")
    if isinstance(v, decimal.Decimal):
        return int(v) if v == v.to_integral_value() else float(v)
    return v


def _pick(data: dict, allowed: set[str], what: str) -> dict:
    extra = set(data) - allowed
    if extra:
        raise OpError(f"{what}: нет таких полей — {', '.join(sorted(extra))}")
    out = {}
    for k, v in data.items():
        if isinstance(v, str) and k not in {"notes", "note", "summary", "title"}:
            v = v.strip() or None
        out[k] = Jsonb(v) if k == "payload" else v
    return out


def _insert(conn: psycopg.Connection, table: str, data: dict) -> dict:
    cols = list(data)
    q = sql.SQL("INSERT INTO {} ({}) VALUES ({}) RETURNING *").format(
        sql.SQL(table), sql.SQL(", ").join(map(sql.Identifier, cols)), sql.SQL(", ").join(sql.Placeholder() * len(cols))
    )
    return conn.execute(q, [data[c] for c in cols]).fetchone()


def _update(conn: psycopg.Connection, table: str, where: dict, data: dict) -> dict | None:
    if not data:
        return conn.execute(
            sql.SQL("SELECT * FROM {} WHERE {}").format(
                sql.SQL(table), sql.SQL(" AND ").join(sql.SQL("{} = %s").format(sql.Identifier(k)) for k in where)
            ),
            list(where.values()),
        ).fetchone()
    q = sql.SQL("UPDATE {} SET {} WHERE {} RETURNING *").format(
        sql.SQL(table),
        sql.SQL(", ").join(sql.SQL("{} = %s").format(sql.Identifier(k)) for k in data),
        sql.SQL(" AND ").join(sql.SQL("{} = %s").format(sql.Identifier(k)) for k in where),
    )
    return conn.execute(q, [*data.values(), *where.values()]).fetchone()


def _same(a: Any, b: Any) -> bool:
    return jsonable(a) == jsonable(b) or (a in (None, "", []) and b in (None, "", []))


def _ensure_labels(conn: psycopg.Connection, labels: list[str] | None) -> None:
    for name in labels or []:
        conn.execute("INSERT INTO tasks.labels (name) VALUES (%s) ON CONFLICT DO NOTHING", (name,))


def task_by(conn: psycopg.Connection, ref: Any) -> dict | None:
    """Дело по id или по короткому номеру («57», «#57»)."""
    s = str(ref).strip().lstrip("#")
    if s.isdigit():
        return conn.execute("SELECT * FROM tasks.v_tasks WHERE num = %s", (int(s),)).fetchone()
    try:
        uuid.UUID(s)
    except ValueError:
        return None
    return conn.execute("SELECT * FROM tasks.v_tasks WHERE id = %s", (s,)).fetchone()


def _full(conn: psycopg.Connection, tid: Any) -> dict:
    return conn.execute("SELECT * FROM tasks.v_tasks WHERE id = %s", (tid,)).fetchone()


# ── Операции ────────────────────────────────────────────────────────────


def op_task_create(conn, user, op):
    data = _pick(op.get("task") or {}, TASK_FIELDS | {"id", "import_ref"}, "дело")
    if not data.get("title"):
        raise OpError("у дела нет названия")
    if data.get("id"):
        have = conn.execute("SELECT id FROM tasks.tasks WHERE id = %s", (data["id"],)).fetchone()
        if have:  # тот же id уже создан: телефон повторил без op_id
            return {"task": _full(conn, have["id"])}
    data.setdefault("owner_id", user)
    data["created_by"] = user
    _ensure_labels(conn, data.get("labels"))
    row = _insert(conn, "tasks.tasks", data)
    return {"task": _full(conn, row["id"])}


def op_task_set(conn, user, op):
    cur = task_by(conn, op.get("id"))
    if not cur:
        raise OpError("нет такого дела или оно не видно")
    changes = _pick(op.get("set") or {}, TASK_FIELDS, "дело")
    was = op.get("was") or {}
    # Поле поменяли с двух сторон: побеждает пришедшее последним, прежнее
    # значение остаётся в журнале, а клиент узнаёт, что спорил не один.
    conflicts = sorted(k for k in changes if k in was and not _same(cur.get(k), was[k]) and not _same(cur.get(k), changes[k]))
    _ensure_labels(conn, changes.get("labels"))
    row = _update(conn, "tasks.tasks", {"id": cur["id"]}, changes)
    if row is None:
        raise OpError("это дело можно только смотреть")
    return {"task": _full(conn, row["id"]), "conflicts": conflicts}


def _status(status):
    def handler(conn, user, op):
        return op_task_set(conn, user, {"id": op.get("id"), "set": {"status": status}})

    return handler


def _instant(v: Any) -> dt.datetime | None:
    if isinstance(v, dt.datetime):
        return v
    try:
        got = dt.datetime.fromisoformat(str(v or ""))
    except ValueError:
        return None
    return got if got.tzinfo else None


def op_task_reminded(conn, user, op):
    """Бот Ковчега отправил напоминание в Telegram: reminded_at — больше не слать.

    Только токен бота: телефон и веб reminded_at не ставят (его нет в TASK_FIELDS). Бот
    напоминает всем, у кого есть Telegram, а токен у него на одного человека — поэтому отметка
    идёт от имени system (как и «что пора»), а в журнал — бот. remind_at — то время, о котором
    бот напомнил: если его успели переставить («через час» с телефона, пока бот слал), отметка
    не ставится — новое напоминание уйдёт в своё время. message_id — в ответе (crm.ops_seen).
    """
    actor = conn.execute("SELECT crm.actor() AS a").fetchone()["a"]
    if actor != f"svc:{REMIND_BOT}":
        raise OpError("task.reminded шлёт только бот напоминаний")
    at = _instant(op.get("at")) or dt.datetime.now(dt.timezone.utc)
    conn.execute("SELECT set_config('dela.user', 'system', true)")
    try:
        cur = task_by(conn, op.get("id"))
        if not cur:
            raise OpError("нет такого дела")
        sent_for = _instant(op.get("remind_at"))
        if op.get("remind_at") and sent_for != cur["remind_at"]:
            return {"task": cur, "stale": True, "message_id": op.get("message_id")}
        conn.execute("UPDATE tasks.tasks SET reminded_at = %s WHERE id = %s AND reminded_at IS NULL", (at, cur["id"]))
        return {"task": _full(conn, cur["id"]), "message_id": op.get("message_id")}
    finally:
        conn.execute("SELECT set_config('dela.user', %s, true)", (user,))


def op_comment_add(conn, user, op):
    data = _pick(op.get("comment") or {}, {"id", "task_id", "text"}, "комментарий")
    task = task_by(conn, data.get("task_id"))
    if not task:
        raise OpError("нет такого дела или оно не видно")
    data["task_id"] = task["id"]
    data["author_id"] = user
    if data.get("id") and conn.execute("SELECT 1 FROM tasks.comments WHERE id = %s", (data["id"],)).fetchone():
        return {"comment": conn.execute("SELECT * FROM tasks.comments WHERE id = %s", (data["id"],)).fetchone()}
    return {"comment": _insert(conn, "tasks.comments", data)}


def op_comment_delete(conn, user, op):
    row = conn.execute(
        "UPDATE tasks.comments SET deleted_at = now() WHERE id = %s AND deleted_at IS NULL RETURNING *", (op.get("id"),)
    ).fetchone()
    if not row:
        raise OpError("нет такого комментария или он не твой")
    return {"comment": row}


def _entity(table: str, fields: set[str], what: str, owner_col: str | None = "owner_id", extra: tuple = ("id", "notion_id")):
    def create(conn, user, op):
        data = _pick(op.get("data") or {}, fields | set(extra), what)
        if owner_col:
            data.setdefault(owner_col, user)
        return {"row": _insert(conn, table, data)}

    def update(conn, user, op):
        data = _pick(op.get("set") or {}, fields, what)
        row = _update(conn, table, {"id": op.get("id")}, data)
        if row is None:
            raise OpError(f"{what}: нет такого или нет прав")
        return {"row": row}

    return create, update


def op_user_settings(conn, user, op):
    """Свои настройки (вид, скрытые клиенты Засечки) — слиянием. Роль и доступ так не поменять."""
    data = op.get("settings")
    if not isinstance(data, dict) or not data:
        raise OpError("настройки — словарь")
    row = conn.execute(
        "UPDATE crm.users SET settings = settings || %s WHERE id = %s RETURNING id, settings", (Jsonb(data), user)
    ).fetchone()
    return {"row": row}


def op_access_set(conn, user, op):
    pid, uid, role = op.get("project_id"), op.get("user_id"), op.get("role")
    if role is None:
        n = conn.execute("DELETE FROM crm.project_access WHERE project_id = %s AND user_id = %s", (pid, uid)).rowcount
        return {"removed": n}
    if role not in ("view", "edit"):
        raise OpError("доступ: view или edit")
    row = conn.execute(
        "INSERT INTO crm.project_access (project_id, user_id, role, granted_by) VALUES (%s, %s, %s, %s) "
        "ON CONFLICT (project_id, user_id) DO UPDATE SET role = EXCLUDED.role RETURNING *",
        (pid, uid, role, user),
    ).fetchone()
    return {"row": row}


def op_suggestion_create(conn, user, op):
    data = _pick(op.get("suggestion") or {}, SUGGESTION_FIELDS, "предложение")
    data.setdefault("for_user", db_owner(conn))
    data.setdefault("expires_at", dt.datetime.now(dt.timezone.utc) + SUGGESTION_TTL)
    data["created_by"] = user
    if data.get("source_ref") and data.get("kind", "create") == "create":
        # Повтор той же находки (встреча разобрана заново) не плодит дубль.
        same = conn.execute(
            "SELECT * FROM tasks.suggestions WHERE source_ref = %s AND kind = 'create' AND payload ->> 'title' = %s",
            (data["source_ref"], (op.get("suggestion") or {}).get("payload", {}).get("title")),
        ).fetchone()
        if same:
            return {"suggestion": same}
    row = _insert(conn, "tasks.suggestions", data)
    if _auto_close(conn, user, row):
        return op_suggestion_decide(conn, user, {"id": row["id"], "decision": "accept", "reason": AUTO_CLOSE_REASON})
    return {"suggestion": row}


AUTO_CLOSE_DAYS = 3
AUTO_CLOSE_REASON = "закрыто само"


def _auto_close(conn, user, s: dict) -> bool:
    """Очевидное «закрыть» проходит само (решение владельца 05.10.2026), остальное — в «Новое».

    Само — только когда автоматика уверена (payload.auto), основание названо (quote), дело —
    открытое и самого владельца токена, а встреча или переписка свежая (AUTO_CLOSE_DAYS) и не
    старше самого дела: разбор архива и чужие дела по-прежнему ждут решения человека.
    Основание ложится комментарием к делу (_trace), «Вернуть» — в «Новом», «Закрыто само».
    """
    p = s.get("payload") or {}
    if s.get("kind") != "close" or p.get("auto") is not True or user != s.get("for_user") or not s.get("quote"):
        return False
    try:
        at = dt.date.fromisoformat(str(p.get("meeting_at") or "")[:10])
    except ValueError:
        return False
    row = conn.execute(
        "SELECT %s >= crm.today() - %s AND %s >= (created_at AT TIME ZONE 'Europe/Moscow')::date AS ok "
        "FROM tasks.tasks WHERE id = %s AND status = 'open' AND owner_id = %s",
        (at, AUTO_CLOSE_DAYS, at, s["task_id"], user),
    ).fetchone()
    return bool(row and row["ok"])


def op_suggestion_decide(conn, user, op):
    s = conn.execute(
        "SELECT * FROM tasks.suggestions WHERE id = %s AND status = 'pending' FOR UPDATE", (op.get("id"),)
    ).fetchone()
    if not s:
        raise OpError("предложение уже разобрано или не твоё")
    decision = op.get("decision")
    if decision == "reject":
        row = _update(conn, "tasks.suggestions", {"id": s["id"]},
                      {"status": "rejected", "reason": op.get("reason"), "decided_by": user, "decided_at": dt.datetime.now(dt.timezone.utc)})
        return {"suggestion": row}
    if decision != "accept":
        raise OpError("решение: accept или reject")
    fix = _pick(op.get("set") or {}, TASK_FIELDS, "дело")
    payload = dict(s["payload"] or {})
    task = None
    if s["kind"] == "create":
        fields = {k: v for k, v in payload.items() if k in TASK_FIELDS}
        fields.update(_names(conn, payload))
        fields.update(fix)
        fields.setdefault("source", _task_source(s["source"]))
        fields.setdefault("source_ref", s["source_ref"])
        task = op_task_create(conn, user, {"task": fields})["task"]
    elif s["kind"] in ("close", "update"):
        fields = {k: v for k, v in payload.items() if k in TASK_FIELDS}
        fields.update(_names(conn, payload))  # «уточнить»: мяч перешёл к другому человеку — по имени
        if s["kind"] == "close":
            fields.setdefault("status", "done")
        fields.update(fix)
        task = op_task_set(conn, user, {"id": s["task_id"], "set": fields})["task"]
        _trace(conn, user, s, task)
    elif s["kind"] == "assign":
        fields = {"owner_id": user, **fix}
        task = op_task_set(conn, user, {"id": s["task_id"], "set": fields})["task"]
    edited = bool(fix) and any(not _same(payload.get(k), v) for k, v in fix.items())
    row = _update(conn, "tasks.suggestions", {"id": s["id"]}, {
        "status": "edited" if edited else "accepted",
        "reason": op.get("reason"),
        "decided_by": user,
        "decided_at": dt.datetime.now(dt.timezone.utc),
        "result_task_id": task["id"] if task else None,
        "result": Jsonb(jsonable({k: task[k] for k in TASK_FIELDS if task and k in task})),
    })
    return {"suggestion": row, "task": task}


def _trace(conn, user, s: dict, task: dict | None) -> None:
    """Принятое «закрыть» или «поправить» оставляет при деле комментарий: откуда и почему.

    Уточнение из встречи или переписки (payload.note — новые подробности) иначе осталось бы
    только в «Новом», а через неделю там его уже не найти; у закрытого — основание (quote).
    """
    text = ((s.get("payload") or {}).get("note") or s.get("quote") or "").strip()
    if not text or not task:
        return
    head = s.get("batch_title") or {"meeting": "Встреча", "telegram": "Telegram"}.get(s.get("source"), s.get("source") or "")
    op_comment_add(conn, user, {"comment": {"task_id": task["id"], "text": f"{head}: {text}" if head else text}})


def _names(conn, payload: dict) -> dict:
    """Имена из автоматики («Алфавит», «Дмитрий Шерстобитов») — в id по справочнику.

    Встречи и юзербот не знают id: шлют project_name и person_name. Не нашлось
    или проект не виден принимающему — поле остаётся пустым, человек поправит.
    """
    out: dict = {}
    for key, col in (("project_name", "project_id"), ("person_name", "person_id")):
        name = payload.get(key)
        if not name or payload.get(col):
            continue
        r = conn.execute(f"SELECT {col} FROM crm.match_name(%s)", (name,)).fetchone()
        if r and r[col]:
            table = "crm.projects" if col == "project_id" else "crm.people"
            if conn.execute(f"SELECT 1 FROM {table} WHERE id = %s", (r[col],)).fetchone():
                out[col] = r[col]
    return out


def _task_source(s: str) -> str:
    return {"meeting": "meeting", "telegram": "telegram", "userbot": "telegram", "bot": "bot", "mcp": "mcp", "import": "import"}.get(s, "manual")


def db_owner(conn) -> str:
    return conn.execute("SELECT crm.owner_id() AS id").fetchone()["id"]


org_create, org_set = _entity("crm.orgs", ORG_FIELDS, "организация")
person_create, person_set = _entity("crm.people", PERSON_FIELDS, "человек")
project_create, project_set = _entity("crm.projects", PROJECT_FIELDS, "проект", extra=("id", "import_ref"))
_deal_create, _deal_set = _entity("crm.deals", DEAL_FIELDS, "сделка", owner_col=None)
interaction_create, interaction_set = _entity("crm.interactions", INTERACTION_FIELDS, "взаимодействие")
payment_create, payment_set = _entity("crm.payments", PAYMENT_FIELDS, "оплата", owner_col=None, extra=("id",))


def _shown_deal(conn, did) -> dict:
    """Сделка в ответе — из вида, а не из RETURNING: деньги и личная оценка
    владельца не должны уйти тому, кому они закрыты."""
    return conn.execute("SELECT * FROM crm.v_deals WHERE id = %s", (did,)).fetchone()


def deal_create(conn, user, op):
    return {"row": _shown_deal(conn, _deal_create(conn, user, op)["row"]["id"])}


def deal_set(conn, user, op):
    return {"row": _shown_deal(conn, _deal_set(conn, user, op)["row"]["id"])}


def op_interaction_delete(conn, user, op):
    row = conn.execute(
        "UPDATE crm.interactions SET deleted_at = now() WHERE id = %s AND deleted_at IS NULL RETURNING *", (op.get("id"),)
    ).fetchone()
    if not row:
        raise OpError("нет такой записи хронологии или её нельзя убрать")
    return {"row": row}

HANDLERS: dict[str, Callable] = {
    "task.create": op_task_create,
    "task.set": op_task_set,
    "task.done": _status("done"),
    "task.reopen": _status("open"),
    "task.cancel": _status("cancelled"),
    "task.reminded": op_task_reminded,
    "comment.add": op_comment_add,
    "comment.delete": op_comment_delete,
    "org.create": org_create,
    "org.set": org_set,
    "person.create": person_create,
    "person.set": person_set,
    "project.create": project_create,
    "project.set": project_set,
    "deal.create": deal_create,
    "deal.set": deal_set,
    "access.set": op_access_set,
    "user.settings": op_user_settings,
    "interaction.add": interaction_create,
    "interaction.set": interaction_set,
    "interaction.delete": op_interaction_delete,
    "payment.create": payment_create,
    "payment.set": payment_set,
    "suggestion.create": op_suggestion_create,
    "suggestion.decide": op_suggestion_decide,
}

# Ошибки базы, которые повтор не исправит: формат, права, правила.
PERMANENT = (
    psycopg.errors.IntegrityError,
    psycopg.errors.RaiseException,
    psycopg.errors.InsufficientPrivilege,
    psycopg.errors.DataError,
    psycopg.errors.InvalidTextRepresentation,
)


def _db_error(e: psycopg.Error) -> str:
    d = e.diag
    return (d.message_primary or str(e)).strip() + (f" ({d.message_detail})" if d.message_detail else "")


def apply_ops(url: str, user: str, ops: list[dict], via: str, actor: str | None = None) -> dict:
    """Пачка операций одной транзакцией, каждая — в своей точке сохранения.

    Упавшая операция не роняет остальные. Ответ на каждую ложится в
    crm.ops_seen под её op_id: повтор пачки отдаёт те же ответы.
    """
    results = []
    with db.session(url, user, actor, via) as conn:
        for op in ops:
            op_id = op.get("op_id")
            if op_id:
                seen = conn.execute("SELECT result FROM crm.ops_seen WHERE op_id = %s", (op_id,)).fetchone()
                if seen:
                    results.append(seen["result"])
                    continue
            handler = HANDLERS.get(op.get("op", ""))
            try:
                if handler is None:
                    raise OpError(f"нет такой операции: {op.get('op')!r}")
                with conn.transaction():
                    res = {"op_id": op_id, "ok": True, **jsonable(handler(conn, user, op))}
            except OpError as e:
                res = {"op_id": op_id, "ok": False, "error": str(e)}
            except PERMANENT as e:
                res = {"op_id": op_id, "ok": False, "error": _db_error(e)}
            if op_id:
                conn.execute("INSERT INTO crm.ops_seen (op_id, user_id, result) VALUES (%s, %s, %s)", (op_id, user, Jsonb(res)))
            results.append(res)
        seq = conn.execute("SELECT max(seq) AS s FROM tasks.tasks").fetchone()["s"] or 0
    return {"results": results, "seq": seq}


# ── Синк ────────────────────────────────────────────────────────────────

SYNC_TABLES = {
    "tasks": "SELECT * FROM tasks.v_tasks WHERE seq > %s",
    "projects": "SELECT * FROM crm.projects WHERE seq > %s",
    "deals": "SELECT * FROM crm.v_deals WHERE seq > %s",
    "payments": "SELECT * FROM crm.payments WHERE seq > %s",
    "people": "SELECT * FROM crm.people WHERE seq > %s",
    "orgs": "SELECT * FROM crm.orgs WHERE seq > %s",
    "comments": "SELECT * FROM tasks.comments WHERE seq > %s",
    "suggestions": "SELECT * FROM tasks.suggestions WHERE seq > %s",
    "access": "SELECT * FROM crm.project_access WHERE seq > %s",
    "users": "SELECT id, name, person_id, role, clients, sees_money, seq FROM crm.users WHERE seq > %s",
}


def sync(url: str, user: str, since: int) -> dict:
    """Всё изменившееся после since, что видит user.

    full — клиент выбрасывает кэш и берёт ответ целиком: первый синк, снятый
    доступ (что-то перестало быть видно) или новый доступ (старые дела
    открытого проекта имеют номера меньше since).
    """
    with db.session(url, user, via="sync") as conn:
        full = since <= 0 or bool(conn.execute(
            "SELECT 1 FROM crm.access_revoked WHERE user_id = %s AND seq > %s "
            "UNION ALL SELECT 1 FROM crm.project_access WHERE user_id = %s AND seq > %s LIMIT 1",
            (user, since, user, since),
        ).fetchone())
        lo = 0 if full else max(0, since - OVERLAP)
        out: dict[str, Any] = {"full": full}
        top = since
        for name, q in SYNC_TABLES.items():
            rows = conn.execute(q, (lo,)).fetchall()
            out[name] = jsonable(rows)
            top = max([top, *(r["seq"] for r in rows)])
        out["labels"] = jsonable(conn.execute("SELECT name, color FROM tasks.labels ORDER BY name").fetchall())
        out["seq"] = top
        out["today"] = conn.execute("SELECT crm.today() AS d").fetchone()["d"].isoformat()
    out["features"] = FEATURES
    return out


# ── Виды ────────────────────────────────────────────────────────────────

def _sphere(sphere: str | None) -> tuple[str, list]:
    if sphere in ("work", "home"):
        return " AND sphere IN (%s, 'inbox')", [sphere]
    return "", []


ORDER = " ORDER BY due_date NULLS LAST, due_time NULLS LAST, (money_eff = 'paid') DESC, created_at"


def _list(conn, where: str, params: list, sphere: str | None = None, order: str = ORDER) -> list[dict]:
    s, sp = _sphere(sphere)
    return conn.execute(f"SELECT * FROM tasks.v_tasks WHERE {where}{s}{order}", [*params, *sp]).fetchall()


def view_morning(conn, user, sphere=None, **_):
    open_mine = "status = 'open' AND owner_id = %s"
    return {
        "now": _list(conn, f"{open_mine} AND focus_on = crm.today()", [user], sphere),
        "today": _list(conn, f"{open_mine} AND ball = 'mine' AND due_date <= crm.today()", [user], sphere),
        "nudge": _list(conn, f"{open_mine} AND ball = 'waiting' AND (nudge_on <= crm.today() OR due_date <= crm.today())", [user], sphere),
        "paid_undated": _list(conn, f"{open_mine} AND ball = 'mine' AND money_eff = 'paid' AND due_date IS NULL", [user], sphere),
        "from_others": _list(conn, f"{open_mine} AND created_by <> owner_id AND created_at > now() - interval '3 days'", [user], sphere),
        "new_count": conn.execute(
            "SELECT count(*) AS n FROM tasks.suggestions WHERE for_user = %s AND status = 'pending'", (user,)
        ).fetchone()["n"],
    }


def view_new(conn, user, **_):
    # Пачки — по дате встречи, свежие сверху; у «закрыть» и «поправить» — номер и название дела.
    rows = conn.execute(
        "SELECT s.*, t.num AS task_num, t.title AS task_title FROM tasks.suggestions s "
        "LEFT JOIN tasks.tasks t ON t.id = s.task_id "
        "WHERE s.for_user = %s AND s.status = 'pending' "
        "ORDER BY max(coalesce(s.payload ->> 'meeting_at', s.created_at::date::text)) "
        "         OVER (PARTITION BY coalesce(s.batch_ref, s.id::text)) DESC, s.batch_ref, s.created_at",
        (user,),
    ).fetchall()
    batches: dict[str, dict] = {}
    for r in rows:
        key = r["batch_ref"] or f"one:{r['id']}"
        b = batches.setdefault(key, {"batch_ref": r["batch_ref"], "title": r["batch_title"], "items": []})
        b["items"].append(r)
    return {"batches": list(batches.values()), "count": len(rows)}


def view_waiting(conn, user, sphere=None, **_):
    rows = _list(conn, "status = 'open' AND owner_id = %s AND ball = 'waiting'", [user], sphere,
                 " ORDER BY person_short NULLS LAST, waiting_since NULLS LAST")
    people: dict[str, dict] = {}
    for r in rows:
        key = str(r["person_id"]) if r["person_id"] else "-"
        p = people.setdefault(key, {"person_id": r["person_id"], "person": r["person_short"] or "без человека", "items": []})
        p["items"].append(r)
    return {"people": list(people.values())}


def view_person(conn, user, person_id=None, **_):
    if not person_id:
        raise OpError("нужен person_id")
    open_ = "status = 'open' AND "
    return {
        "person": conn.execute("SELECT * FROM crm.people WHERE id = %s", (person_id,)).fetchone(),
        "agenda": _list(conn, open_ + "ball = 'agenda' AND person_id = %s", [person_id]),
        "waiting": _list(conn, open_ + "ball = 'waiting' AND person_id = %s", [person_id]),
        "asked": _list(conn, open_ + "requested_by = %s", [person_id]),
        "mine_about": _list(conn, open_ + "ball = 'mine' AND person_id = %s", [person_id]),
    }


def view_quick(conn, user, sphere=None, **_):
    return {"items": _list(conn, "status = 'open' AND owner_id = %s AND ball = 'mine' AND estimate_min <= 10", [user], sphere)}


def view_now(conn, user, sphere=None, **_):
    return {"items": _list(conn, "status = 'open' AND owner_id = %s AND focus_on = crm.today()", [user], sphere)}


def view_project(conn, user, project_id=None, **_):
    if not project_id:
        raise OpError("нужен project_id")
    project = conn.execute("SELECT * FROM crm.projects WHERE id = %s", (project_id,)).fetchone()
    if not project:
        raise OpError("нет такого проекта или он не виден")
    hours = None
    if project["owner_id"] == user:
        hours = conn.execute("SELECT * FROM crm.project_minutes(%s)", (project_id,)).fetchall()
    return {
        "project": project,
        "deals": conn.execute("SELECT * FROM crm.v_deals WHERE project_id = %s ORDER BY stage = 'archive', name", (project_id,)).fetchall(),
        "open": _list(conn, "status = 'open' AND project_id = %s", [project_id]),
        "done": _list(conn, "status <> 'open' AND project_id = %s", [project_id], order=" ORDER BY completed_at DESC LIMIT 30"),
        "minutes": hours,
    }


def view_week(conn, user, sphere=None, **_):
    open_mine = "status = 'open' AND owner_id = %s"
    s, sp = _sphere(sphere)
    return {
        "stale": _list(conn, f"{open_mine} AND (due_date < crm.today() - 14 OR updated_at < now() - interval '21 days')", [user], sphere),
        "waiting_stale": _list(
            conn,
            f"{open_mine} AND ball = 'waiting' AND waiting_since < crm.today() - 7 AND (nudge_on IS NULL OR nudge_on < crm.today())",
            [user], sphere),
        "no_next_step": conn.execute(
            "SELECT p.* FROM crm.projects p WHERE p.archived_at IS NULL AND p.owner_id = %s "
            "AND p.money_default IN ('paid', 'potential') AND NOT EXISTS (SELECT 1 FROM tasks.tasks t "
            "WHERE t.project_id = p.id AND t.status = 'open' AND t.ball = 'mine') ORDER BY p.money_default, p.name",
            (user,),
        ).fetchall(),
        "expired": conn.execute(
            "SELECT * FROM tasks.suggestions WHERE for_user = %s AND status = 'expired' AND decided_at > now() - interval '7 days'",
            (user,),
        ).fetchall(),
        "sphere": s and sp[0],
    }


def view_search(conn, user, q=None, status="open", **_):
    if not q:
        raise OpError("нужен текст поиска")
    st = "" if status == "all" else " AND status = 'open'"
    rows = conn.execute(
        "SELECT *, ts_rank(search, websearch_to_tsquery('russian', %s)) AS rank FROM tasks.v_tasks "
        f"WHERE (search @@ websearch_to_tsquery('russian', %s) OR lower(title) LIKE '%%' || lower(%s) || '%%'){st} "
        "ORDER BY rank DESC, updated_at DESC LIMIT 50",
        (q, q, q),
    ).fetchall()
    return {"items": rows}


VIEWS: dict[str, Callable] = {
    "morning": view_morning,
    "new": view_new,
    "waiting": view_waiting,
    "person": view_person,
    "quick": view_quick,
    "now": view_now,
    "project": view_project,
    "week": view_week,
    "search": view_search,
}


def view(url: str, user: str, name: str, **params) -> dict:
    from . import crm, stats  # noqa: F401  CRM-виды и статистика (свои модули) встают в VIEWS при импорте
    fn = VIEWS.get(name)
    if fn is None:
        raise OpError(f"нет такого вида: {name}")
    with db.session(url, user, via="view") as conn:
        return jsonable(fn(conn, user, **params))


def expire_suggestions(url: str) -> int:
    """Неразобранное за неделю гаснет: решение «не важно» тоже решение, и оно в журнале."""
    with db.session(url, "system", "svc:expire") as conn:
        return conn.execute(
            "UPDATE tasks.suggestions SET status = 'expired', decided_at = now() "
            "WHERE status = 'pending' AND expires_at < now()"
        ).rowcount
