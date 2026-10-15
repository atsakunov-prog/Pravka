"""Идеи у клиента, человека, сделки (15.10.2026, dela_0032; ответ владельца 5а — «свой список „Идеи“ у клиента,
человека и сделки; всплывают перед встречей, когда работаешь по клиенту, и раз в неделю»).

Идея — не дело: «идея: предложить Ромашке косткаттинг» не должна ни засорять «Запустить», ни тонуть в хронологии.
Своя маленькая сущность (вариант 12.1 а): текст, к кому (клиент, человек, сделка), живая / использована / убрана. Видит
только хозяин — это его мысли о клиенте (RLS, как сводки дайджеста). Заводит захват (capture.py: «идея …» правилом или
ideas от модели), веб и коннектор — операциями idea.add / idea.set; телефон получает их полем ideas синка.
"""

from __future__ import annotations

IDEA_FIELDS = {"text", "project_id", "person_id", "deal_id", "status", "task_id", "source", "source_ref"}
STATUSES = ("open", "used", "dropped")
SOURCES = ("voice", "web", "meeting", "mcp", "manual")


def _clean(conn, data: dict, error) -> dict:
    from . import store

    out = store._pick({k: v for k, v in data.items() if k in IDEA_FIELDS | {"id"}}, IDEA_FIELDS | {"id"}, "идея")
    if "text" in out:
        out["text"] = " ".join(str(out["text"] or "").split())[:2000]
        if not out["text"]:
            raise error("у идеи нет текста")
    if out.get("status") is not None and out["status"] not in STATUSES:
        raise error("идея: статус — open, used или dropped")
    if out.get("source") is not None and out["source"] not in SOURCES:
        raise error("идея: откуда — voice, web, meeting, mcp или manual")
    if out.get("deal_id") and not out.get("project_id"):
        d = conn.execute("SELECT project_id FROM crm.deals WHERE id = %s", (out["deal_id"],)).fetchone()
        if not d:
            raise error("идея: нет такой сделки или она не видна")
        out["project_id"] = d["project_id"]
    return out


def op_idea_add(conn, user, op, error):
    """Завести идею. Повтор с тем же id — прежняя строка (телефон повторяет очередь без op_id)."""
    from . import store

    data = _clean(conn, dict(op.get("idea") or {}), error)
    if not data.get("text"):
        raise error("у идеи нет текста")
    if data.get("id"):
        have = conn.execute("SELECT * FROM crm.ideas WHERE id = %s", (data["id"],)).fetchone()
        if have:
            return {"idea": have}
    data["owner_id"] = user
    data.setdefault("status", "open")
    return {"idea": store._insert(conn, "crm.ideas", data)}


def op_idea_set(conn, user, op, error):
    """Поправить идею: текст, к кому, «Использована» (used), «Убрать» (dropped), «В дело» (used + task_id)."""
    from . import store

    data = _clean(conn, dict(op.get("set") or {}), error)
    data.pop("id", None)
    row = store._update(conn, "crm.ideas", {"id": op.get("id")}, data)
    if row is None:
        raise error("нет такой идеи или она не твоя")
    return {"idea": row}


IDEA_SELECT = ("SELECT i.*, p.name AS project_name, pe.name AS person_name, pe.short AS person_short, d.name AS deal_name "
               "FROM crm.ideas i LEFT JOIN crm.projects p ON p.id = i.project_id "
               "LEFT JOIN crm.people pe ON pe.id = i.person_id LEFT JOIN crm.deals d ON d.id = i.deal_id ")


def view_ideas(conn, user, project_id=None, person_id=None, deal_id=None, status="open", **_):
    """Идеи хозяина: все живые (список «Идеи») или у клиента, человека, сделки (карточки). status=all — и прошлые."""
    where, params = ["i.owner_id = %s"], [user]
    if status != "all":
        where.append("i.status = 'open'")
    for col, v in (("project_id", project_id), ("person_id", person_id), ("deal_id", deal_id)):
        if v:
            where.append(f"i.{col} = %s")
            params.append(v)
    rows = conn.execute(IDEA_SELECT + "WHERE " + " AND ".join(where) + " ORDER BY i.created_at DESC LIMIT 300",
                        params).fetchall()
    return {"items": rows}


def for_sync(conn, user, lo: int) -> list[dict]:
    """Поле ideas синка: свои идеи с номером изменения после lo (RLS и так отдаёт только свои)."""
    return conn.execute("SELECT * FROM crm.ideas WHERE owner_id = %s AND seq > %s ORDER BY seq", (user, lo)).fetchall()


def register(handlers: dict, views: dict, error) -> None:
    handlers["idea.add"] = lambda conn, user, op: op_idea_add(conn, user, op, error)
    handlers["idea.set"] = lambda conn, user, op: op_idea_set(conn, user, op, error)
    views["ideas"] = view_ideas
