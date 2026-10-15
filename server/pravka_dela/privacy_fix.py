"""Разовый шаг с данными после установки пакета «Приватность» (аудит 15.10.2026, №8–11): `privacy-fix`.

Миграции dela_0024–0028 строк не трогают: они меняют правила, а прежние данные чинит эта команда — после своей копии
базы (`pravka-before-privacy.dump`) и шагов части 1 (`meetings touch`, `digest-relink`). `--dry` — всё в точке
сохранения, которая откатывается: числа те же, в базе ничего.

1. Основания — приватными: комментарии-основания автоматики из личных каналов (id в result.comment_id у предложений
   из meeting, telegram, userbot, contacts — «закрыто/уточнено само», «Человек не поставлен») и «Это оно» веба (текст
   начинается с пачки такого предложения, автор — его адресат, предложение отклонено «уже есть: …»).
2. Заметки машины — дела, заведённые «завести» из Telegram (и из встреч при «только факт»), чья заметка совпадает с
   payload.notes предложения: заметка уходит приватным комментарием, у дела пусто (журнал — svc:privacy-fix). Заметку
   правили руками — не трогаем, только число.
3. Записи встреч — crm.meeting_touch всех встреч с 01.09 по переключателю (с завода «только факт»). Под замком touch со
   своим текстом — список «дд.мм, название»; с --locked-private им ставится private (только по «да» владельца).
4. События доступа всем не-владельцам: их синк пересоберёт кэш.

Откат: шаг 1 — только из копии (приватность не снимается); шаг 2 — заметки обратно по crm.history (before.notes от
svc:privacy-fix); шаг 3 — переключатель «факт и итог» переписывает записи обратно.
"""

from __future__ import annotations

from typing import Any

from . import db, meetings, store

ACTOR = "svc:privacy-fix"
SOURCES = ("meeting", "telegram", "userbot", "contacts")


class _DryRun(Exception):
    pass


def _norm(s: Any) -> str:
    return " ".join(str(s or "").split())


def run(url: str, dry: bool = False, locked_private: bool = False) -> dict:
    out: dict[str, Any] = {"dry": dry}
    with db.session(url, "system", ACTOR, "system") as conn:
        try:
            with conn.transaction():
                out.update(_bases(conn))
                out.update(_notes(conn))
                out.update(_meetings(conn, locked_private))
                out["events"] = conn.execute(
                    "SELECT count(*) AS n FROM (SELECT crm.access_changed(u.id) FROM crm.users u WHERE u.role <> 'owner') x"
                ).fetchone()["n"]
                if dry:
                    raise _DryRun
        except _DryRun:
            pass
    return out


def _bases(conn) -> dict:
    """Шаг 1: основания и «Это оно» из личных каналов — приватными."""
    ids = conn.execute(
        "UPDATE tasks.comments c SET private = true WHERE NOT c.private AND c.deleted_at IS NULL AND c.id::text IN ("
        " SELECT s.result ->> 'comment_id' FROM tasks.suggestions s"
        " WHERE s.source = ANY(%s) AND s.result ? 'comment_id' AND s.result ->> 'comment_id' IS NOT NULL) RETURNING c.id",
        (list(SOURCES),)).fetchall()
    merged = conn.execute(
        "UPDATE tasks.comments c SET private = true WHERE NOT c.private AND c.deleted_at IS NULL AND EXISTS ("
        " SELECT 1 FROM tasks.suggestions s JOIN tasks.tasks t ON t.id = c.task_id"
        " WHERE s.source = ANY(%s) AND s.status = 'rejected' AND s.reason = 'уже есть: ' || t.title"
        "   AND c.author_id = s.for_user AND nullif(btrim(s.batch_title), '') IS NOT NULL"
        "   AND left(c.text, length(s.batch_title) + 2) = s.batch_title || ': ') RETURNING c.id",
        (list(SOURCES),)).fetchall()
    return {"bases": len(ids), "merged": len(merged)}


def _notes(conn) -> dict:
    """Шаг 2: заметки машины из Telegram (и встреч при «только факт») — приватным комментарием."""
    fact = store.team_sees_meetings(conn) == "fact"
    srcs = ["telegram", "userbot"] + (["meeting"] if fact else [])
    rows = conn.execute(
        "SELECT t.id AS task_id, t.notes, s.payload ->> 'notes' AS machine, s.batch_title, s.source, "
        "coalesce(s.decided_by, s.for_user) AS author FROM tasks.suggestions s JOIN tasks.tasks t ON t.id = s.result_task_id "
        "WHERE s.kind = 'create' AND s.status IN ('accepted', 'edited') AND s.source = ANY(%s) "
        "AND nullif(btrim(s.payload ->> 'notes'), '') IS NOT NULL AND nullif(btrim(t.notes), '') IS NOT NULL",
        (srcs,)).fetchall()
    moved = kept = 0
    for r in rows:
        if _norm(r["notes"]) != _norm(r["machine"]):
            kept += 1
            continue
        head = r["batch_title"] or store.SOURCE_HEAD.get(r["source"], r["source"])
        text = f"{head}: {r['machine'].strip()}" if head else r["machine"].strip()
        if not conn.execute("SELECT 1 FROM tasks.comments WHERE task_id = %s AND deleted_at IS NULL AND text = %s",
                            (r["task_id"], text)).fetchone():
            conn.execute("INSERT INTO tasks.comments (task_id, author_id, text, private) VALUES (%s, %s, %s, true)",
                         (r["task_id"], r["author"], text))
        conn.execute("UPDATE tasks.tasks SET notes = NULL WHERE id = %s", (r["task_id"],))
        moved += 1
    return {"notes_moved": moved, "notes_kept": kept, "notes_sources": srcs}


def _meetings(conn, locked_private: bool) -> dict:
    """Шаг 3: записи встреч — по переключателю; под замком touch — списком (и private по --locked-private)."""
    counts: dict[str, int] = {}
    held: list[dict] = []
    for r in conn.execute("SELECT id, ref, started_at, owner_id, coalesce(nullif(btrim(title), ''), source_title) AS title "
                          "FROM crm.meetings WHERE started_at >= crm.meeting_touch_from() ORDER BY started_at").fetchall():
        a = meetings.touch(conn, r["id"])
        counts[a] = counts.get(a, 0) + 1
        if a == "под замком":
            rec = conn.execute(
                "SELECT id, private FROM crm.interactions WHERE source = 'meeting' AND deleted_at IS NULL AND owner_id = %s "
                "AND crm.meeting_ref(source_ref) = crm.meeting_ref(coalesce((SELECT url FROM crm.meetings WHERE id = %s), "
                "'/m/' || %s))", (r["owner_id"], r["id"], r["ref"])).fetchone()
            if rec and not rec["private"]:
                held.append({"ref": r["ref"], "title": r["title"],
                             "day": r["started_at"].astimezone(meetings.MSK).strftime("%d.%m")})
                if locked_private:
                    conn.execute("UPDATE crm.interactions SET private = true WHERE id = %s", (rec["id"],))
    return {"meetings": counts, "locked": held, "locked_private": locked_private}


def words(out: dict) -> str:
    """Итог словами для командной строки."""
    lines = [("вхолостую (ничего не записано): " if out["dry"] else "") + "privacy-fix:",
             f"1. основания — приватными: {out['bases']} (и «Это оно»: {out['merged']})",
             f"2. заметки машины ({', '.join(out['notes_sources'])}) — в приватный комментарий: {out['notes_moved']}; "
             f"правленые руками — не тронуты: {out['notes_kept']}",
             "3. записи встреч: " + (", ".join(f"{k} {v}" for k, v in sorted(out["meetings"].items())) or "нет встреч")]
    if out["locked"]:
        lines.append(("   скрыты от команды" if out["locked_private"] else "   под замком — твой текст, команда видит целиком")
                     + f" ({len(out['locked'])}): " + "; ".join(f"{x['day']} «{x['title']}»" for x in out["locked"]))
    lines.append(f"4. событий доступа (полный синк не-владельцам): {out['events']}")
    return "\n".join(lines)
