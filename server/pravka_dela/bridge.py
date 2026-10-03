"""Мост из Todoist до переезда телефона: только новые задачи, в одну сторону.

Пока Правка шлёт голос в Todoist, служба раз в 10 минут забирает оттуда
задачи, которых в Делах ещё нет, и ничего в Todoist не пишет. Правка и
закрытие уже переехавших задач — только в Делах: перенесённое не
перезаписывается (ON CONFLICT DO NOTHING), поэтому двух правд не бывает.

Кто есть кто — по справочнику Дел (алиасы) и по решениям владельца, которые
перенос положил в crm.state ('import_decisions'): метки людей, префиксы
названий. Токен — DELA_TODOIST_TOKEN в dela.env.
"""

from __future__ import annotations

import logging

import httpx
from psycopg.types.json import Jsonb

from . import db
from .importer import PRIORITY_MONEY, sid

log = logging.getLogger("dela.bridge")

API = "https://api.todoist.com/api/v1"


def fetch(token: str, client: httpx.Client | None = None) -> dict:
    """Открытые задачи и проекты Todoist (API v1, страницы по курсору)."""
    own = client is None
    client = client or httpx.Client(timeout=30)
    try:
        def pages(path: str) -> list[dict]:
            out, cursor = [], None
            for _ in range(20):
                params = {"limit": 200, **({"cursor": cursor} if cursor else {})}
                r = client.get(f"{API}/{path}", params=params, headers={"Authorization": f"Bearer {token}"})
                r.raise_for_status()
                d = r.json()
                out += d.get("results", [])
                cursor = d.get("next_cursor")
                if not cursor:
                    break
            return out

        return {"projects": pages("projects"), "tasks": pages("tasks")}
    finally:
        if own:
            client.close()


def _person(conn, name: str | None):
    if not name:
        return None
    r = conn.execute("SELECT person_id FROM crm.match_name(%s)", (name,)).fetchone()
    return r["person_id"] if r else None


def pull_new(url: str, data: dict) -> list[str]:
    """Новые задачи Todoist — в Дела. Возвращает названия заведённых."""
    made: list[str] = []
    with db.session(url, "system", "svc:todoist", via="import") as conn:
        owner = conn.execute("SELECT crm.owner_id() AS id").fetchone()["id"]
        st = conn.execute("SELECT value FROM crm.state WHERE key = 'import_decisions'").fetchone()
        dec = st["value"] if st else {}
        label_people = dec.get("label_people", {})
        title_people = dec.get("title_people", {})
        merge = dec.get("merge_projects", {})
        # Что перенос нарочно не сделал делом (дни рождения у людей, явные пропуски), мост не воскрешает.
        skip = set(dec.get("birthdays", {})) | set(dec.get("skip_todoist", []))
        projects = {p["id"]: p for p in data["projects"]}
        for t in data["tasks"]:
            if t.get("checked") or t.get("is_deleted") or t["id"] in skip:
                continue
            tid = sid("task", "todoist", t["id"])
            if conn.execute("SELECT 1 FROM tasks.tasks WHERE id = %s", (tid,)).fetchone():
                continue
            tp = projects.get(t.get("project_id")) or {}
            labels = t.get("labels") or []
            pid = None
            if not (tp.get("inbox_project") or tp.get("is_inbox_project")):
                name = merge.get(tp.get("name", ""), tp.get("name", ""))
                row = conn.execute(
                    "SELECT id FROM crm.projects WHERE import_ref = %s", (f"todoist:{tp.get('id')}",)
                ).fetchone() or conn.execute("SELECT project_id AS id FROM crm.match_name(%s)", (name,)).fetchone()
                pid = row["id"] if row and row["id"] else None
                if pid is None and name:
                    pid = conn.execute(
                        "INSERT INTO crm.projects (id, name, sphere, kind, owner_id, import_ref) "
                        "VALUES (%s, %s, 'work', 'client', %s, %s) ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name RETURNING id",
                        (sid("project", "todoist", tp["id"]), name, owner, f"todoist:{tp['id']}"),
                    ).fetchone()["id"]
            if "личное" in labels and dec.get("personal_project"):
                r = conn.execute("SELECT project_id AS id FROM crm.match_name(%s)", (dec["personal_project"],)).fetchone()
                pid = r["id"] if r and r["id"] else pid
            person = next((_person(conn, label_people[l]) for l in labels if l in label_people), None)
            title = t["content"].strip()
            prefix = title.split(":", 1)[0].strip() if ":" in title[:70] else None
            if person is None and prefix:
                person = _person(conn, title_people.get(prefix, prefix))
            due = ((t.get("due") or {}).get("date") or "")[:10] or None
            ball = "agenda" if "повестка" in labels else "waiting" if "жду" in labels else "mine"
            keep = [l for l in labels if l == "звонок"]
            conn.execute(
                "INSERT INTO tasks.tasks (id, title, notes, project_id, owner_id, ball, person_id, due_date, estimate_min, "
                "money, labels, source, source_ref, import_ref, created_by) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, 'import', 'todoist-bridge', %s, %s) ON CONFLICT (id) DO NOTHING",
                (tid, title, (t.get("description") or "").strip() or None, pid, owner, ball, person, due,
                 10 if "быстр" in labels else None, PRIORITY_MONEY.get(f"p{5 - int(t.get('priority', 1))}") if int(t.get("priority", 1)) > 1 else None,
                 keep, f"todoist:{t['id']}", owner),
            )
            made.append(title)
        conn.execute(
            "INSERT INTO crm.state (key, value) VALUES ('todoist_bridge', %s) "
            "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()",
            (Jsonb({"seen": len(data["tasks"]), "made": len(made)}),),
        )
    if made:
        log.info("мост Todoist: новых задач %d — %s", len(made), "; ".join(made)[:300])
    return made


def save_decisions(url: str, dec: dict) -> None:
    """Решения владельца — в базу, чтобы мост в службе их видел (C:\\Bot ей закрыт)."""
    keys = ("label_people", "title_people", "merge_projects", "personal_project", "birthdays", "skip_todoist")
    slim = {k: dec[k] for k in keys if k in dec}
    with db.session(url, "system", "svc:import", via="import") as conn:
        conn.execute(
            "INSERT INTO crm.state (key, value) VALUES ('import_decisions', %s) "
            "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()",
            (Jsonb(slim),),
        )



def export(token: str, client: httpx.Client | None = None) -> dict:
    """Всё открытое в Todoist для переноса: проекты, задачи, метки и комментарии (API v1)."""
    own = client is None
    client = client or httpx.Client(timeout=30)
    try:
        data = fetch(token, client)
        h = {"Authorization": f"Bearer {token}"}
        r = client.get(f"{API}/labels", params={"limit": 200}, headers=h)
        r.raise_for_status()
        data["labels"] = [x["name"] for x in r.json().get("results", [])]
        comments = []
        for t in data["tasks"]:
            if not t.get("note_count"):
                continue
            r = client.get(f"{API}/comments", params={"task_id": t["id"], "limit": 200}, headers=h)
            r.raise_for_status()
            comments += [{"id": c["id"], "task_id": t["id"], "content": c.get("content", ""), "posted_at": c.get("posted_at")}
                         for c in r.json().get("results", [])]
        data["comments"] = comments
        return data
    finally:
        if own:
            client.close()
