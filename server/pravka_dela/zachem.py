"""«Зачем» по-человечески (13.10.2026): переименовать проекты клиентов и цели дерева коротко, глаголом или результатом.

Владелец: «Называть это одним большим словом „Зачем“» и «переименовать все „Зачем“ по-человечески: с большой буквы,
коротко, глаголом/результатом» — не имя клиента и не тема через двоеточие, а «Помочь с хеджем», «Сделать валютную
модель», «Сделать клиенту дашборд». «Зачем» — то, ради чего дела: живой проект клиента (сделка,
crm.deals) или открытая цель дерева (дело-узел, tasks с пометкой goal). Клиенты и разделы (ЗФ, Семья) — не «Зачем»:
это кто и где, их имена не трогаем.

Порядок (правило 9 C:\\Bot\\CLAUDE.md — «чистки только после своей именной копии»):
1. Своя копия базы `pravka-before-zachem-rename.dump` (D:\\PravkaArchive\\backups) — до правок.
2. `propose` — Claude одним запросом пачкой (Message Batches, −50 %): по названию, клиенту, делам внутри — новое
   название; хорошее оставляет как есть. Сначала на малом (`--limit 10`): качество и цена.
3. `apply` — правки от владельца со службой `svc:zachem` в журнале (crm.history — что было), список «было → стало» —
   в Свод `zachem.renamed`: по нему веб ищет и по старому названию, и по нему же `rollback` возвращает как было.
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import time
import uuid

from . import db, llm, store

log = logging.getLogger("dela.zachem")

MODEL = "claude-opus-5-5"   # называть — тонкая работа: главная модель (правило Саши — Opus по умолчанию)
EFFORT = "medium"
RENAMED_KEY = "zachem.renamed"
ACTOR = "svc:zachem"
FALLBACK_BETA = "server-side-fallback-2026-07-01"
TASKS_SHOWN = 8

SYSTEM = """Ты переименовываешь «Зачем» владельца в его сервисе «Дела». «Зачем» — то, ради чего делаются дела: проект
клиента его консалтинговой компании (сделка) или цель в его дереве жизни. Владелец хочет называть их по-человечески.

КАК НАЗЫВАТЬ
- С большой буквы, коротко (2–6 слов), глаголом или результатом: что сделать или что будет, когда сделано.
  Плохо: «Бета: валютная модель», «Гамма: хедж юаня», «Альфа: дашборды», «Дельта: buy-side M&A».
  Хорошо: «Сделать валютную модель», «Помочь с хеджем юаня», «Сделать Альфе дашборд», «Найти Дельте компанию для
  покупки».
- Клиент и так виден рядом с названием: в названии он нужен, только если без него непонятно или звучит естественно
  («Сделать Альфе дашборд»). Префикс «Клиент: …» убери.
- Смысл — из названия, клиента, описания и дел внутри; не выдумывай того, чего там нет. Сокращения и имена
  собственные (M&A, DD, АО, CFO, названия компаний) оставляй как есть или пиши по-русски понятно.
- Цель дерева — состояние или результат («Сын занимается музыкой», «Бюджет собирается сам»). Цель, которая уже
  названа так, оставь как есть.
- Название уже хорошее (с большой буквы, коротко, понятно ради чего) — верни name пустым: не трогать.

Ответ — по каждому пункту списка, key — ровно как в списке."""

SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": ["items"],
    "properties": {"items": {"type": "array", "items": {
        "type": "object",
        "additionalProperties": False,
        "required": ["key", "name"],
        "properties": {"key": {"type": "string"}, "name": {"type": "string"}},
    }}},
}


def _short(s, n: int) -> str:
    s = " ".join(str(s or "").split())
    return s[:n] + "…" if len(s) > n else s


def collect(url: str, owner: str) -> list[dict]:
    """Что переименовывать: живые проекты клиентов (сделки не в архиве) и открытые цели дерева владельца — с тем,
    что нужно модели (клиент, описание, путь цели, дела внутри)."""
    out: list[dict] = []
    with db.session(url, owner, via="zachem") as conn:
        for d in conn.execute(
                "SELECT d.id, d.name, d.stage, d.description, p.name AS client FROM crm.deals d JOIN crm.projects p "
                "ON p.id = d.project_id WHERE d.stage <> 'archive' AND p.archived_at IS NULL ORDER BY p.name, d.name").fetchall():
            tasks = [r["title"] for r in conn.execute(
                "SELECT title FROM tasks.tasks WHERE deal_id = %s AND status = 'open' ORDER BY due_date NULLS LAST, num LIMIT %s",
                (d["id"], TASKS_SHOWN)).fetchall()]
            out.append({"key": f"d:{d['id']}", "kind": "deal", "id": str(d["id"]), "name": d["name"], "client": d["client"],
                        "about": _short(d["description"], 300), "tasks": tasks})
        goals = conn.execute("SELECT id, num, title, why_id FROM tasks.v_tasks WHERE owner_id = %s AND goal AND status = 'open' "
                             "ORDER BY num", (owner,)).fetchall()
        by = {g["id"]: g for g in goals}
        for g in goals:
            path, x = [], by.get(g["why_id"])
            while x and len(path) < 6:
                path.append(x["title"])
                x = by.get(x["why_id"])
            tasks = [r["title"] for r in conn.execute(
                "SELECT t.title FROM tasks.tasks t JOIN tasks.krug k ON k.id = t.id WHERE k.why_id = %s AND t.status = 'open' "
                "ORDER BY t.num LIMIT %s", (g["id"], TASKS_SHOWN)).fetchall()]
            out.append({"key": f"g:{g['num']}", "kind": "goal", "id": str(g["id"]), "name": g["title"],
                        "path": list(reversed(path)), "tasks": tasks})
    return out


def prompt(items: list[dict]) -> str:
    lines = ["СПИСОК («Зачем», что внутри):"]
    for it in items:
        if it["kind"] == "deal":
            head = f"{it['key']} · проект клиента «{it['client']}»: «{it['name']}»"
            if it.get("about"):
                head += f" — {it['about']}"
        else:
            head = f"{it['key']} · цель дерева: «{it['name']}»" + (f" (внутри цели: {' › '.join(it['path'])})" if it.get("path") else "")
        lines.append(head + (" · дела: " + "; ".join(it["tasks"]) if it.get("tasks") else " · дел нет"))
    return "\n".join(lines)


def params(items: list[dict]) -> dict:
    return {"model": MODEL, "max_tokens": 16000, "system": SYSTEM,
            "output_config": {"effort": EFFORT, "format": {"type": "json_schema", "schema": SCHEMA}},
            "messages": [{"role": "user", "content": prompt(items)}]}


def _parse(msg) -> dict[str, str]:
    if getattr(msg, "stop_reason", None) in ("refusal", "max_tokens"):
        raise RuntimeError(f"«Зачем»: ответ оборван ({msg.stop_reason})")
    text = next((b.text for b in msg.content if getattr(b, "type", "") == "text"), "")
    return {x["key"]: " ".join(str(x.get("name") or "").split()) for x in json.loads(text).get("items") or []}


def propose(url: str, owner: str, key: str, proxy: str | None, limit: int | None = None, batch: bool = True,
            client=None, wait: float = 5.0) -> dict:
    """Предложения новых названий: {items: [{key, kind, id, was, now}], usd}. Ничего не пишет. Пачкой — Message
    Batches (−50 %, обычно минуты); batch=False — сразу (для малой пробы, если ждать некогда)."""
    from . import parse

    items = collect(url, owner)
    if limit:
        items = items[:limit]
    if not items:
        return {"items": [], "usd": 0.0}
    cl = client or parse.client(key, proxy)
    p = params(items)
    if batch:
        b = cl.messages.batches.create(requests=[{"custom_id": "zachem", "params": p}])
        while cl.messages.batches.retrieve(b.id).processing_status != "ended":
            time.sleep(wait)
        res = next(iter(cl.messages.batches.results(b.id)))
        if res.result.type != "succeeded":
            raise RuntimeError(f"«Зачем»: пачка — {res.result.type}")
        msg = res.result.message
    else:
        msg = cl.beta.messages.create(**p, betas=[FALLBACK_BETA], fallbacks="default")
    usage = llm.usage_of(msg, MODEL)
    usd = llm.cost(usage, MODEL, url) * (0.5 if batch else 1.0)
    llm.account(url, "zachem", usd, usage.get("model") or MODEL)
    got = _parse(msg)
    out = []
    for it in items:
        now = got.get(it["key"], "")
        if now and now != it["name"]:
            out.append({"key": it["key"], "kind": it["kind"], "id": it["id"], "was": it["name"], "now": now,
                        **({"client": it["client"]} if it.get("client") else {})})
    return {"items": out, "seen": len(items), "usd": round(usd, 4), "usage": usage}


def _renamed(conn, owner: str) -> list[dict]:
    row = conn.execute("SELECT value FROM crm.svod WHERE owner_id = %s AND key = %s", (owner, RENAMED_KEY)).fetchone()
    v = (row or {}).get("value")
    return list(v) if isinstance(v, list) else []


def apply(url: str, owner: str, items: list[dict]) -> dict:
    """Переименовать: сделки — deal.set name, цели — task.set title, от владельца со службой svc:zachem. Название
    поменяли после предложения (не то, что было) — не трогаем. Список «было → стало» — в Свод zachem.renamed."""
    ops, plan = [], []
    with db.session(url, owner, via="zachem") as conn:
        for it in items:
            if it["kind"] == "deal":
                cur = conn.execute("SELECT name FROM crm.deals WHERE id = %s", (it["id"],)).fetchone()
                if cur and cur["name"] == it["was"]:
                    ops.append({"op": "deal.set", "op_id": str(uuid.uuid4()), "id": it["id"], "set": {"name": it["now"]}})
                    plan.append(it)
            elif it["kind"] == "goal":
                cur = conn.execute("SELECT title FROM tasks.tasks WHERE id = %s", (it["id"],)).fetchone()
                if cur and cur["title"] == it["was"]:
                    ops.append({"op": "task.set", "op_id": str(uuid.uuid4()), "id": it["id"], "set": {"title": it["now"]}})
                    plan.append(it)
        before = _renamed(conn, owner)
    res = store.apply_ops(url, owner, ops, "zachem", ACTOR)["results"] if ops else []
    done = [it for it, r in zip(plan, res) if r.get("ok")]
    errors = [f"{it['was']}: {r.get('error')}" for it, r in zip(plan, res) if not r.get("ok")]
    if done:
        at = dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")
        value = before + [{"kind": x["kind"], "id": x["id"], "was": x["was"], "now": x["now"], "at": at} for x in done]
        r = store.apply_ops(url, owner, [{"op": "svod.set", "key": RENAMED_KEY, "value": value, "author": "claude",
                                          "reason": f"«Зачем» по-человечески: {len(done)}"}], "zachem", ACTOR)["results"][0]
        if not r.get("ok"):
            errors.append(f"Свод {RENAMED_KEY}: {r.get('error')}")
    return {"done": done, "errors": errors}


def rollback(url: str, owner: str, ids: set[str] | None = None) -> dict:
    """Вернуть старые названия (все или ids) — только там, где название всё ещё то, что поставили мы."""
    with db.session(url, owner, via="zachem") as conn:
        renamed = _renamed(conn, owner)
    back = [{"kind": x["kind"], "id": x["id"], "was": x["now"], "now": x["was"]} for x in reversed(renamed)
            if not ids or x["id"] in ids]
    out = apply(url, owner, back)
    return out
