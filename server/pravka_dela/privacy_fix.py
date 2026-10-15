"""Разовый шаг с данными после установки пакета «Приватность» (аудит 15.10.2026, №8–11): `privacy-fix`.

Миграции dela_0024–0028 строк не трогают: они меняют правила, а прежние данные чинит эта команда — после своей копии
базы (`pravka-before-privacy.dump`) и шагов части 1 (`meetings touch`, `digest-relink`). `--dry` — всё в точке
сохранения, которая откатывается: числа те же, в базе ничего.

1. Основания — приватными: комментарии-основания автоматики из личных каналов (id в result.comment_id у предложений
   из meeting, telegram, userbot, contacts — «закрыто/уточнено само», «Человек не поставлен») и «Это оно» веба и
   телефона (предложение такого источника отклонено «уже есть: …», комментарий его адресата лёг в ту же минуту и
   начинается с «<пачка или подпись источника>: <название предложения>»; дело могли переименовать). Причина «уже есть»
   без такого комментария — списком, посмотреть глазами.
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


# «Это оно» (веб — mergeInto в static/app.js, телефон — onMerge в DelaTab.kt) одним запросом пишет комментарий
# «<откуда>: <название> — «<цитата>» — <заметка>» и отклоняет предложение причиной «уже есть: <название дела тогда>».
# <откуда> — пачка предложения, а без неё — подпись источника: у веба SUG_FROM (иначе «Автоматика»), у телефона
# DelaViews.mark(source).word. Название дела в причине — на тот день: дело могли переименовать.
MERGE_HEADS = {"meeting": ("встреча", "Встреча"), "telegram": ("Telegram",), "userbot": ("Telegram",),
               "contacts": ("телефонная книжка", "Телефонная книжка")}
MERGE_ANY_HEAD = "Автоматика"


def _merge_text(text: str, heads: list[str], title: str | None) -> bool:
    """Комментарий — слова «Это оно» этого предложения: начинается с «<откуда>: », а дальше — с названия предложения
    (если оно было)."""
    t = _norm(text)
    return any(t.startswith(_norm(f"{h}: {title}") if title else _norm(h) + ":") for h in heads)


def _bases(conn) -> dict:
    """Шаг 1: основания и «Это оно» из личных каналов — приватными."""
    ids = conn.execute(
        "UPDATE tasks.comments c SET private = true WHERE NOT c.private AND c.deleted_at IS NULL AND c.id::text IN ("
        " SELECT s.result ->> 'comment_id' FROM tasks.suggestions s"
        " WHERE s.source = ANY(%s) AND s.result ? 'comment_id' AND s.result ->> 'comment_id' IS NOT NULL) RETURNING c.id",
        (list(SOURCES),)).fetchall()
    # «Это оно» — по автору, времени и началу текста, без названия дела (проверка пакета: дело переименовали после «Это
    # оно» или у предложения не было пачки — прежнее сравнение «уже есть: » || нынешнее название их не находило).
    # Комментарий лёг тем же запросом, что решение: минута — с запасом на часы базы и службы.
    sugs = conn.execute(
        "SELECT s.id, s.source, s.for_user, s.decided_by, s.decided_at, s.reason, "
        "nullif(btrim(s.batch_title), '') AS batch, nullif(btrim(s.payload ->> 'title'), '') AS title "
        "FROM tasks.suggestions s WHERE s.source = ANY(%s) AND s.status = 'rejected' AND s.reason LIKE 'уже есть: %%' "
        "AND s.decided_at IS NOT NULL ORDER BY s.decided_at, s.id", (list(SOURCES),)).fetchall()
    hide: set = set()
    missed: list[dict] = []
    for s in sugs:
        heads = [h for h in (s["batch"], *MERGE_HEADS.get(s["source"], ()), MERGE_ANY_HEAD) if h]
        near = conn.execute(
            "SELECT id, text, private FROM tasks.comments WHERE deleted_at IS NULL AND author_id IN (%s, %s) "
            "AND created_at BETWEEN %s::timestamptz - interval '1 minute' AND %s::timestamptz + interval '1 minute'",
            (s["for_user"], s["decided_by"] or s["for_user"], s["decided_at"], s["decided_at"])).fetchall()
        hit = [c for c in near if _merge_text(c["text"], heads, s["title"])]
        if not hit:
            missed.append({"day": s["decided_at"].astimezone(meetings.MSK).strftime("%d.%m"), "reason": s["reason"]})
        hide |= {c["id"] for c in hit if not c["private"]}
    if hide:
        conn.execute("UPDATE tasks.comments SET private = true WHERE id = ANY(%s) AND NOT private", (list(hide),))
    return {"bases": len(ids), "merged": len(hide), "merged_missed": missed}


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
             f"1. основания — приватными: {out['bases']} (и «Это оно»: {out['merged']})"]
    if out.get("merged_missed"):
        # Причина «уже есть: …» есть, а комментария рядом по времени и с началом «<откуда>: » нет — посмотреть глазами
        # (комментарий убран, написан позже или иначе); приватным он не стал.
        miss = out["merged_missed"]
        lines.append(f"   «Это оно» без комментария рядом — посмотри глазами ({len(miss)}): "
                     + "; ".join(f"{x['day']} «{x['reason']}»" for x in miss[:10]) + ("; …" if len(miss) > 10 else ""))
    lines += [f"2. заметки машины ({', '.join(out['notes_sources'])}) — в приватный комментарий: {out['notes_moved']}; "
              f"правленые руками — не тронуты: {out['notes_kept']}",
              "3. записи встреч: " + (", ".join(f"{k} {v}" for k, v in sorted(out["meetings"].items())) or "нет встреч")]
    if out["locked"]:
        lines.append(("   скрыты от команды" if out["locked_private"] else "   под замком — твой текст, команда видит целиком")
                     + f" ({len(out['locked'])}): " + "; ".join(f"{x['day']} «{x['title']}»" for x in out["locked"]))
    lines.append(f"4. событий доступа (полный синк не-владельцам): {out['events']}")
    return "\n".join(lines)
