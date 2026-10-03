"""Инструменты Дел для Claude (коннектор «Правка» в claude.ai).

Тот же код, что у API службы (store), под ролью службы и от имени владельца:
права решает база, журнал пишет триггер с via = mcp. Ответы — компактным
текстом, как у архива: Claude читает их глазами модели, не парсером.

Люди и проекты называются по-человечески («Додо», «Наташа»): имя ищется
среди алиасов. Дело — по короткому номеру «57» или «#57».
"""

from __future__ import annotations

import datetime as dt
import re
from typing import Any

from . import db, store

USER = "sasha"  # архив — одного человека, и Claude в нём действует от его имени
BALL = {"mine": "моё", "waiting": "жду", "agenda": "повестка"}
MONEY = {"paid": "оплачено", "potential": "развитие", "none": ""}
STATUS = {"done": "сделано", "cancelled": "отменено", "open": ""}


def _d(v: Any) -> str:
    if not v:
        return ""
    if isinstance(v, str):
        v = dt.date.fromisoformat(v[:10])
    return v.strftime("%d.%m")


def line(t: dict, today: dt.date | None = None) -> str:
    """Дело одной строкой: номер, название, проект, мяч, сроки, деньги."""
    today = today or dt.date.today()
    bits = [f"#{t['num']} {t['title']}"]
    where = t.get("project_name") or "Входящие"
    if t.get("deal_name"):
        where += f" / {t['deal_name']}"
    bits.append(where)
    if t["ball"] != "mine" or t.get("person_short"):
        who = t.get("person_short") or "?"
        b = f"{BALL[t['ball']]} {who}" if t["ball"] != "mine" else f"с {who}"
        if t["ball"] == "waiting" and t.get("waiting_since"):
            b += f" с {_d(t['waiting_since'])}"
        bits.append(b)
    if t.get("due_date"):
        due = dt.date.fromisoformat(str(t["due_date"])[:10])
        s = f"срок {_d(due)}" + (f" {str(t['due_time'])[:5]}" if t.get("due_time") else "")
        if t["status"] == "open" and due < today:
            s += f" (просрочено {(today - due).days} дн.)"
        bits.append(s)
    if t.get("nudge_on") and t["ball"] == "waiting":
        bits.append(f"напомнить {_d(t['nudge_on'])}")
    if t.get("estimate_min"):
        bits.append(f"{t['estimate_min']} мин")
    m = MONEY.get(t.get("money_eff") or "none")
    if m:
        bits.append(m)
    if t.get("want"):
        bits.append("хочу сам")
    if t.get("labels"):
        bits.append(", ".join(t["labels"]))
    if STATUS.get(t["status"]):
        bits.append(STATUS[t["status"]])
    return " · ".join(bits)


def _block(title: str, items: list[dict], today) -> list[str]:
    if not items:
        return []
    return [f"{title} ({len(items)}):", *(f"  {line(t, today)}" for t in items), ""]


def _today(url: str) -> dt.date:
    with db.session(url, USER, via="mcp") as conn:
        return conn.execute("SELECT crm.today() AS d").fetchone()["d"]


# ── Имена ───────────────────────────────────────────────────────────────


class NameError_(store.OpError):
    pass


def find_project(conn, name: str) -> dict:
    n = store_norm(name)
    rows = conn.execute("SELECT id, name, aliases, archived_at FROM crm.projects").fetchall()
    exact = [r for r in rows if n in {store_norm(r["name"]), *map(store_norm, r["aliases"])}]
    live = [r for r in exact if not r["archived_at"]] or exact
    if len(live) == 1:
        return live[0]
    part = [r for r in rows if not r["archived_at"] and n in store_norm(r["name"])]
    if len(part) == 1:
        return part[0]
    cands = ", ".join(r["name"] for r in (live or part)[:8]) or "нет похожих"
    raise NameError_(f"проект «{name}» не определился: {cands}")


def find_person(conn, name: str) -> dict:
    n = store_norm(name)
    rows = conn.execute("SELECT id, name, short, aliases FROM crm.people WHERE archived_at IS NULL").fetchall()
    exact = [r for r in rows if n in {store_norm(r["name"]), store_norm(r["short"]), *map(store_norm, r["aliases"])}]
    if len(exact) == 1:
        return exact[0]
    part = exact or [r for r in rows if n in store_norm(r["name"])]
    if len(part) == 1:
        return part[0]
    cands = ", ".join(r["name"] for r in part[:8]) or "нет похожих"
    raise NameError_(f"человек «{name}» не определился: {cands}")


def find_deal(conn, project_id, name: str) -> dict:
    n = store_norm(name)
    rows = conn.execute("SELECT id, name FROM crm.deals WHERE project_id = %s", (project_id,)).fetchall()
    hit = [r for r in rows if n in store_norm(r["name"])]
    if len(hit) == 1:
        return hit[0]
    raise NameError_(f"сделка «{name}» не определилась: {', '.join(r['name'] for r in rows) or 'у проекта нет сделок'}")


def store_norm(s: str | None) -> str:
    return re.sub(r"\s+", " ", (s or "").replace("ё", "е").replace("Ё", "Е").strip().lower())


def _resolve(conn, fields: dict) -> dict:
    """Имена в id: project, person, requested_by, deal. Пустая строка — очистить поле."""
    out = dict(fields)
    if "project" in out:
        v = out.pop("project")
        out["project_id"] = None if v in ("", "Входящие", "входящие") else find_project(conn, v)["id"]
    if "person" in out:
        v = out.pop("person")
        out["person_id"] = find_person(conn, v)["id"] if v else None
    if "requested_by" in out:
        v = out.pop("requested_by")
        out["requested_by"] = find_person(conn, v)["id"] if v else None
    if "deal" in out:
        v = out.pop("deal")
        pid = out.get("project_id")
        if v and not pid:
            raise NameError_("сделка задаётся вместе с проектом")
        out["deal_id"] = find_deal(conn, pid, v)["id"] if v else None
    if "now" in out:
        out["focus_on"] = conn.execute("SELECT crm.today() AS d").fetchone()["d"] if out.pop("now") else None
    for k in ("due_date", "nudge_on"):
        if k in out and out[k] == "":
            out[k] = None
    return out


# ── Инструменты ─────────────────────────────────────────────────────────

VIEW_TITLES = {
    "morning": "Утро", "new": "Новое", "waiting": "Жду", "person": "По человеку", "quick": "Быстрое",
    "now": "Сейчас", "project": "Проект", "week": "Неделя", "search": "Поиск",
}


def view(url: str, name: str = "morning", sphere: str | None = None, person: str | None = None,
         project: str | None = None, query: str | None = None) -> str:
    today = _today(url)
    params: dict = {"sphere": sphere}
    try:
        with db.session(url, USER, via="mcp") as conn:
            if name == "person":
                params["person_id"] = find_person(conn, person or "")["id"]
            if name == "project":
                params["project_id"] = find_project(conn, project or "")["id"]
        if name == "search":
            params["q"] = query
        v = store.view(url, USER, name, **params)
    except store.OpError as e:
        return f"Не вышло: {e}"
    head = f"Дела — {VIEW_TITLES.get(name, name)}, сегодня {today.strftime('%d.%m.%Y')} ({['пн','вт','ср','чт','пт','сб','вс'][today.weekday()]})"
    out = [head, ""]
    if name == "morning":
        out += _block("Сейчас", v["now"], today) + _block("На сегодня и просроченное", v["today"], today)
        out += _block("Пора напомнить", v["nudge"], today) + _block("Оплачено, без даты", v["paid_undated"], today)
        out += _block("Поставили другие", v["from_others"], today)
        if v["new_count"]:
            out.append(f"В «Новом» ждут решения: {v['new_count']} (dela view=new).")
    elif name == "new":
        for b in v["batches"]:
            out.append(f"{b['title'] or 'Без пачки'}:")
            for s in b["items"]:
                p = s["payload"] or {}
                q = f" — «{s['quote'][:120]}»" if s.get("quote") else ""
                out.append(f"  [{s['id'][:8]}] {s['kind']}: {p.get('title') or ''}{q}")
            out.append("")
        if not v["batches"]:
            out.append("Пусто.")
    elif name == "waiting":
        for p in v["people"]:
            out += _block(p["person"], p["items"], today)
    elif name == "person":
        pe = v["person"] or {}
        out.append(f"{pe.get('name')}" + (f" ({pe['role']})" if pe.get("role") else ""))
        out += _block("Повестка с ним", v["agenda"], today) + _block("Жду от него", v["waiting"], today)
        out += _block("Его просьбы ко мне", v["asked"], today) + _block("Моё о нём", v["mine_about"], today)
    elif name == "project":
        pr = v["project"]
        out.append(f"{pr['name']} · {pr['kind']} · деньги по умолчанию: {pr['money_default']}")
        for dl in v["deals"]:
            fee = f" · fee {dl['fee_kop'] // 100:,} ₽".replace(",", " ") if dl.get("fee_kop") else ""
            out.append(f"  сделка: {dl['name']} · {dl['stage']}{fee}" + (f" · следующий шаг: {dl['next_step']}" if dl.get("next_step") else ""))
        out += ["", *_block("Открытые", v["open"], today), *_block("Закрытые недавно", v["done"], today)]
        if v.get("minutes"):
            total = sum(r["minutes"] or 0 for r in v["minutes"])
            out.append(f"Время из ленты: {total // 60} ч {total % 60} мин за {len(v['minutes'])} дн.")
    elif name == "week":
        out += _block("Протухшее", v["stale"], today) + _block("Жду без движения", v["waiting_stale"], today)
        if v["no_next_step"]:
            out.append("Проекты с деньгами без моего следующего шага: " + ", ".join(p["name"] for p in v["no_next_step"]))
        if v["expired"]:
            out.append(f"Погасло предложений за неделю: {len(v['expired'])}")
    else:
        out += _block(VIEW_TITLES.get(name, name), v.get("items", []), today) or ["Пусто."]
    return "\n".join(out).rstrip() + "\n"


def card(url: str, ref: str) -> str:
    from .api import task_card

    c = task_card(url, USER, ref)
    if not c:
        return f"Дела #{ref} нет или оно не видно."
    t = c["task"]
    out = [line(t), ""]
    if t.get("notes"):
        out += [t["notes"], ""]
    for cm in c["comments"]:
        out.append(f"— {cm['author_id']} {cm['created_at'][:16].replace('T', ' ')}: {cm['text']}")
    if c["history"]:
        out.append("Журнал:")
        for h in c["history"][-12:]:
            what = ", ".join(sorted((h["after"] or {}).keys()))[:120] if h["op"] == "update" else h["op"]
            out.append(f"  {h['at'][:16].replace('T', ' ')} {h['actor']} ({h['via']}): {what}")
    return "\n".join(out) + "\n"


def add(url: str, **fields) -> str:
    try:
        with db.session(url, USER, via="mcp") as conn:
            data = _resolve(conn, {k: v for k, v in fields.items() if v is not None})
    except store.OpError as e:
        return f"Не записал: {e}"
    data["source"] = "mcp"
    r = store.apply_ops(url, USER, [{"op": "task.create", "task": data}], via="mcp")["results"][0]
    if not r["ok"]:
        return f"Не записал: {r['error']}"
    return "Записал: " + line(r["task"])


def change(url: str, ref: str, comment: str | None = None, **fields) -> str:
    try:
        with db.session(url, USER, via="mcp") as conn:
            t = store.task_by(conn, ref)
            if not t:
                return f"Дела {ref} нет или оно не видно."
            if "deal" in fields and "project" not in fields:
                fields["project_id"] = t["project_id"]
            data = _resolve(conn, {k: v for k, v in fields.items() if v is not None})
    except store.OpError as e:
        return f"Не поменял: {e}"
    ops = []
    if data:
        ops.append({"op": "task.set", "id": str(t["id"]), "set": data})
    if comment:
        ops.append({"op": "comment.add", "comment": {"task_id": str(t["id"]), "text": comment}})
    if not ops:
        return "Нечего менять."
    res = store.apply_ops(url, USER, ops, via="mcp")["results"]
    bad = [r["error"] for r in res if not r["ok"]]
    if bad:
        return "Не поменял: " + "; ".join(bad)
    task = next((r["task"] for r in res if "task" in r), None)
    return "Готово: " + (line(task) if task else f"комментарий к #{t['num']}")


def decide(url: str, suggestion: str, decision: str, reason: str | None = None, **fix) -> str:
    """suggestion — id (первые 8 знаков хватит) или «batch:<batch_ref>» для всей пачки."""
    with db.session(url, USER, via="mcp") as conn:
        if suggestion.startswith("batch:"):
            ids = [r["id"] for r in conn.execute(
                "SELECT id FROM tasks.suggestions WHERE batch_ref = %s AND status = 'pending' AND for_user = %s",
                (suggestion[6:], USER))]
        else:
            ids = [r["id"] for r in conn.execute(
                "SELECT id FROM tasks.suggestions WHERE id::text LIKE %s AND status = 'pending' AND for_user = %s",
                (suggestion + "%", USER))]
        try:
            fixed = _resolve(conn, {k: v for k, v in fix.items() if v is not None}) if fix else {}
        except store.OpError as e:
            return f"Не вышло: {e}"
    if not ids:
        return "Таких предложений в «Новом» нет."
    ops = [{"op": "suggestion.decide", "id": str(i), "decision": decision, "reason": reason, "set": fixed} for i in ids]
    res = store.apply_ops(url, USER, ops, via="mcp")["results"]
    ok = [r for r in res if r["ok"]]
    out = [f"Разобрано: {len(ok)} из {len(res)}."]
    out += [f"  {line(r['task'])}" for r in ok if r.get("task")]
    out += [f"  ошибка: {r['error']}" for r in res if not r["ok"]]
    return "\n".join(out)


def note(url: str, summary: str, kind: str = "note", project: str | None = None, deal: str | None = None,
         people: list[str] | None = None, next_step: str | None = None, at: str | None = None) -> str:
    try:
        with db.session(url, USER, via="mcp") as conn:
            pid = find_project(conn, project)["id"] if project else None
            did = find_deal(conn, pid, deal)["id"] if deal and pid else None
            pids = [str(find_person(conn, p)["id"]) for p in (people or [])]
    except store.OpError as e:
        return f"Не записал: {e}"
    data = {"at": at or dt.datetime.now(dt.timezone.utc).isoformat(), "kind": kind, "summary": summary,
            "project_id": pid, "deal_id": did, "person_ids": pids, "next_step": next_step, "source": "manual"}
    r = store.apply_ops(url, USER, [{"op": "interaction.add", "data": {k: v for k, v in data.items() if v is not None}}], via="mcp")["results"][0]
    return "Записал в хронологию." if r["ok"] else f"Не записал: {r['error']}"
