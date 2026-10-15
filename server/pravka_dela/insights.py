"""«Только тебе» (dela_0027, аудит 15.10.2026 №8–11; место этапа 6): наблюдения только владельцу Дел.

Разбор встреч и переписки (этап 6) будет замечать несостыковки, неуверенность, «кто за столом» и оценки людей;
владелец и сам пишет такое о людях и клиентах. Это видно только ему — не команде, не в общей хронологии клиента, не в
синке и не в коннекторе для чужих. Здесь — одно хранилище (crm.insights) и его операции и виды:

- insight.add {insight: {id?, kind?, text, quote?, person_ids?, project_id?, deal_id?, org_id?, meeting_id? | meeting_ref?,
  at?, at_sec?, source?, source_ref?, item_key?}} — запись. meeting_ref — номер встречи службы (ref): сервер найдёт её у
  владельца. С item_key — повтор той же находки службы находит прежнюю строку: живую обновляет (текст, цитата, когда,
  о ком; состояние не трогает), убранную не возвращает — ответ {insight, existing: true};
- insight.set {id, set: {state?, text?, kind?, person_ids?, project_id?, deal_id?, org_id?}} — «Видел», «Мимо»,
  «Неверно», правка;
- insight.delete {id} — убрать (deleted_at; строки не удаляются);
- вид insights?person_id|project_id|deal_id|org_id|meeting_id|state|limit, поле insights у видов client, deal, dossier и
  meeting (of). Команде — пусто: RLS «только владельцу» и правило строки (сотруднику insight.add — отказ базы словами).
В синк не входит: телефон берёт «Только тебе» видами. Клиенты шлют операции только при флаге privacy.
"""

from __future__ import annotations

import datetime as dt
import uuid
from typing import Any

FIELDS = {"id", "kind", "text", "quote", "person_ids", "project_id", "deal_id", "org_id", "meeting_id", "meeting_ref",
          "at", "at_sec", "source", "source_ref", "item_key"}
SET_FIELDS = {"state", "text", "kind", "person_ids", "project_id", "deal_id", "org_id", "quote"}
STATES = ("new", "seen", "useful", "dismissed", "wrong", "moved")
SOURCES = ("manual", "web", "phone", "mcp", "meeting", "digest")
CARD_MAX = 50
LIST_MAX = 500

# Строка вида: наблюдение и названия того, о ком оно (для подписи в карточке без лишних запросов).
SELECT = (
    "SELECT i.*, coalesce(nullif(btrim(m.title), ''), m.source_title) AS meeting_title, m.ref AS meeting_ref, "
    "p.name AS project_name, d.name AS deal_name, o.name AS org_name, "
    "ARRAY(SELECT coalesce(pe.short, pe.name) FROM unnest(i.person_ids) WITH ORDINALITY AS u(pid, n) "
    "      JOIN crm.people pe ON pe.id = u.pid ORDER BY u.n) AS people "
    "FROM crm.insights i LEFT JOIN crm.meetings m ON m.id = i.meeting_id LEFT JOIN crm.projects p ON p.id = i.project_id "
    "LEFT JOIN crm.deals d ON d.id = i.deal_id LEFT JOIN crm.orgs o ON o.id = i.org_id ")


def _uuid(v: Any, what: str, err) -> str | None:
    if v in (None, ""):
        return None
    try:
        return str(uuid.UUID(str(v)))
    except ValueError:
        raise err(f"«Только тебе»: {what} — не id") from None


def _clean(conn, user: str, d: dict, err, new: bool) -> dict:
    """Поля наблюдения: проверка и приведение (id — uuid, люди — список id, когда — ISO, встреча по номеру службы)."""
    out: dict = {}
    for k in ("project_id", "deal_id", "org_id", "meeting_id"):
        if k in d:
            out[k] = _uuid(d[k], k, err)
    if "person_ids" in d:
        ppl = d["person_ids"] or []
        if not isinstance(ppl, list):
            raise err("«Только тебе»: person_ids — список id людей")
        out["person_ids"] = [x for x in (_uuid(p, "person_ids", err) for p in ppl) if x]
    if d.get("meeting_ref") and not out.get("meeting_id"):
        m = conn.execute("SELECT id FROM crm.meetings WHERE owner_id = %s AND ref = %s ORDER BY chars_n DESC LIMIT 1",
                         (user, str(d["meeting_ref"]).strip())).fetchone()
        if not m:
            raise err(f"«Только тебе»: встречи №{d['meeting_ref']} нет")
        out["meeting_id"] = str(m["id"])
    for k in ("text", "quote", "kind", "source", "source_ref", "item_key"):
        if k in d:
            v = d[k]
            out[k] = (" ".join(str(v).split()) if k in ("kind", "source", "item_key") else str(v).strip()) if v is not None else None
            if k != "text" and out[k] == "":
                out[k] = None
    if new and not (out.get("text") or "").strip():
        raise err("«Только тебе»: нужен текст")
    if out.get("source") and out["source"] not in SOURCES:
        raise err(f"«Только тебе»: откуда — {', '.join(SOURCES)}")
    if "at" in d and d["at"]:
        try:
            at = dt.datetime.fromisoformat(str(d["at"]))
        except ValueError:
            raise err("«Только тебе»: at — время ISO") from None
        out["at"] = at if at.tzinfo else at.replace(tzinfo=dt.timezone.utc)
    if "at_sec" in d and d["at_sec"] is not None:
        try:
            out["at_sec"] = round(float(d["at_sec"]), 1)
        except (TypeError, ValueError):
            raise err("«Только тебе»: at_sec — секунда от начала встречи") from None
    if "state" in d:
        if d["state"] not in STATES:
            raise err(f"«Только тебе»: состояние — {', '.join(STATES)}")
        out["state"] = d["state"]
    return out


def _one(conn, iid) -> dict | None:
    return conn.execute(SELECT + "WHERE i.id = %s", (iid,)).fetchone()


def op_add(conn, user: str, op: dict, err) -> dict:
    raw = op.get("insight") or {}
    if not isinstance(raw, dict):
        raise err("«Только тебе»: insight — словарь")
    extra = set(raw) - FIELDS
    if extra:
        raise err(f"«Только тебе»: нет таких полей — {', '.join(sorted(extra))}")
    data = _clean(conn, user, raw, err, new=True)
    data.setdefault("source", "manual")
    if raw.get("id"):
        data["id"] = _uuid(raw["id"], "id", err)
        have = conn.execute("SELECT id FROM crm.insights WHERE id = %s", (data["id"],)).fetchone()
        if have:  # тот же id уже записан: телефон повторил без op_id
            return {"insight": _one(conn, have["id"]), "existing": True}
    if data.get("item_key"):
        same = conn.execute(
            "SELECT id, deleted_at FROM crm.insights WHERE owner_id = %s AND source = %s "
            "AND coalesce(source_ref, '') = coalesce(%s, '') AND item_key = %s",
            (user, data["source"], data.get("source_ref"), data["item_key"])).fetchone()
        if same:
            if same["deleted_at"] is None:
                upd = {k: data[k] for k in ("text", "quote", "at", "at_sec", "person_ids") if k in data}
                if upd:
                    cols = ", ".join(f"{k} = %s" for k in upd)
                    conn.execute(f"UPDATE crm.insights SET {cols} WHERE id = %s", [*upd.values(), same["id"]])
            return {"insight": _one(conn, same["id"]), "existing": True}
    data["owner_id"] = user
    data["author"] = user  # правило базы подпишет crm.actor()
    cols = list(data)
    row = conn.execute(f"INSERT INTO crm.insights ({', '.join(cols)}) VALUES ({', '.join(['%s'] * len(cols))}) RETURNING id",
                       [data[c] for c in cols]).fetchone()
    return {"insight": _one(conn, row["id"])}


def op_set(conn, user: str, op: dict, err) -> dict:
    iid = _uuid(op.get("id"), "id", err)
    raw = op.get("set") or {}
    if not isinstance(raw, dict) or not raw:
        raise err("«Только тебе»: set — словарь полей")
    extra = set(raw) - SET_FIELDS
    if extra:
        raise err(f"«Только тебе»: нельзя менять — {', '.join(sorted(extra))}")
    data = _clean(conn, user, raw, err, new=False)
    if "text" in data and not (data["text"] or "").strip():
        raise err("«Только тебе»: текст не может быть пустым — убери наблюдение")
    cols = ", ".join(f"{k} = %s" for k in data)
    row = conn.execute(f"UPDATE crm.insights SET {cols} WHERE id = %s AND deleted_at IS NULL RETURNING id",
                       [*data.values(), iid]).fetchone()
    if not row:
        raise err("«Только тебе»: нет такого наблюдения")
    return {"insight": _one(conn, row["id"])}


def op_delete(conn, user: str, op: dict, err) -> dict:
    iid = _uuid(op.get("id"), "id", err)
    row = conn.execute("UPDATE crm.insights SET deleted_at = now() WHERE id = %s AND deleted_at IS NULL RETURNING id",
                       (iid,)).fetchone()
    if not row:
        raise err("«Только тебе»: нет такого наблюдения или оно уже убрано")
    return {"insight": _one(conn, row["id"])}


def of(conn, where: str, params: list, limit: int = CARD_MAX) -> list[dict]:
    """Живые наблюдения карточки, свежие сверху (поле insights видов client, deal, dossier, meeting). Команде — []: RLS."""
    return conn.execute(SELECT + f"WHERE i.deleted_at IS NULL AND ({where}) ORDER BY i.at DESC, i.created_at DESC "
                        f"LIMIT {int(limit)}", params).fetchall()


def view_insights(conn, user: str, person_id=None, project_id=None, deal_id=None, org_id=None, meeting_id=None,
                  state=None, limit=None, err=Exception, **_) -> dict:
    """Вид insights: живые наблюдения владельца, отбор по человеку, клиенту, сделке, компании, встрече, состоянию."""
    where, params = ["TRUE"], []
    for col, v in (("project_id", project_id), ("deal_id", deal_id), ("org_id", org_id), ("meeting_id", meeting_id)):
        if v:
            where.append(f"i.{col} = %s")
            params.append(_uuid(v, col, err))
    if person_id:
        where.append("%s = ANY (i.person_ids)")
        params.append(_uuid(person_id, "person_id", err))
    if state:
        if state not in STATES:
            raise err(f"«Только тебе»: состояние — {', '.join(STATES)}")
        where.append("i.state = %s")
        params.append(state)
    try:
        n = max(1, min(int(limit or CARD_MAX), LIST_MAX))
    except ValueError:
        raise err("limit — число") from None
    return {"items": of(conn, " AND ".join(where), params, n)}


def register(handlers: dict, views: dict, err) -> None:
    handlers["insight.add"] = lambda conn, user, op: op_add(conn, user, op, err)
    handlers["insight.set"] = lambda conn, user, op: op_set(conn, user, op, err)
    handlers["insight.delete"] = lambda conn, user, op: op_delete(conn, user, op, err)
    views["insights"] = lambda conn, user, **p: view_insights(conn, user, err=err, **p)
