"""Инструменты Дел для Claude (коннектор «Правка» в claude.ai).

Тот же код, что у API службы (store), под ролью службы и от имени владельца:
права решает база, журнал пишет триггер с via = mcp. Ответы — компактным
текстом, как у архива: Claude читает их глазами модели, не парсером.

Люди и проекты называются по-человечески («Додо», «Наташа»): имя ищется
среди алиасов. Дело — по короткому номеру «57» или «#57».

Напоминание в Telegram (06.10.2026): remind_at — «ГГГГ-ММ-ДД ЧЧ:ММ» по Москве, remind_place —
место телефона («дом»): «напомни мне завтра в 10» из claude.ai доходит до бота Ковчега.
"""

from __future__ import annotations

import datetime as dt
import re
from typing import Any

from . import db, people, remind, store

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
    if t["ball"] != "mine" or t.get("person_id"):
        who = t.get("person_short") or t.get("person_name") or "кого — не указано"
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
    r = remind.state(t, today)
    if r:
        bits.append(r)
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
    # «Женя Соколов», «Соколов из Ромашки» — то же узнавание, что у встреч и звонков (people.who).
    w = people.who(conn, q=name)
    if w["sure"] and not exact:
        return {"id": w["best"]["id"], "name": w["best"]["name"], "short": w["best"]["short"], "aliases": []}
    cands = ", ".join(r["name"] for r in part[:8]) or ", ".join(c["name"] for c in w["candidates"]) or "нет похожих"
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
    # Напоминание — одно из двух: время снимает место, место — время (как карточка телефона).
    if "remind_at" in out:
        said = out.pop("remind_at")
        if said == "":
            out["remind_at"] = None
        else:
            at = remind.local_iso(said)
            if not at:
                raise NameError_(f"remind_at «{said}» — нужно «ГГГГ-ММ-ДД ЧЧ:ММ» по Москве")
            out["remind_at"] = at
            out.setdefault("remind_place", None)
    if out.get("remind_place"):
        places = remind.places_of(conn, USER)
        out["remind_place"] = remind.match_place(out["remind_place"], places) or out["remind_place"].strip()
        if "remind_at" not in out:
            out["remind_at"] = None
    elif out.get("remind_place") == "":
        out["remind_place"] = None
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
        out += _block("Пора напомнить", v["nudge"], today)
        out += _block("Поставили другие", v["from_others"], today)
        if v["new_count"]:
            out.append(f"В «Новом» ждут решения: {v['new_count']} (dela view=new).")
    elif name == "new":
        for b in v["batches"]:
            out.append(f"{b['title'] or 'Без пачки'}:")
            for s in b["items"]:
                p = s["payload"] or {}
                if s["kind"] == "create":
                    what = p.get("title") or ""
                    hint = " · ".join(x for x in (p.get("project_name"), p.get("person_name"), BALL.get(p.get("ball", "mine")), p.get("due_date")) if x)
                else:
                    ref = f"#{s['task_num']} {s['task_title']}" if s.get("task_num") else "дело не видно"
                    what = {"close": "закрыть", "update": "поправить", "assign": "взять себе"}.get(s["kind"], s["kind"]) + ": " + ref
                    hint = " · ".join(x for x in (p.get("due_date") and "срок " + p["due_date"], p.get("ball") and BALL.get(p["ball"])) if x)
                out.append(f"  [{s['id'][:8]}] {what}" + (f" ({hint})" if hint else "")
                           + (f" — «{s['quote'][:160]}»" if s.get("quote") and s["quote"] != what else ""))
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


def person(url: str, name: str, also: list[str] | None = None, phone: str | None = None,
           telegram: str | None = None, merge_into: str | None = None) -> str:
    """Один человек на всю систему: другое имя, номер, Telegram — к карточке; дубль — в живую карточку.

    Владелец (06.10.2026): «Женя из встречи — это тот же Евгений из карточки клиента». Карточку находит
    то же узнавание, что у встреч и звонков; неуверенное не трогаем — перечисляем кандидатов.
    """
    with db.session(url, USER, via="mcp") as conn:
        try:
            p = find_person(conn, name)
            into = find_person(conn, merge_into) if merge_into else None
        except NameError_ as e:
            return f"Не вышло: {e}"
    if into is not None:
        if str(into["id"]) == str(p["id"]):
            return f"«{name}» и «{merge_into}» — уже одна карточка: {p['name']}."
        r = store.apply_ops(url, USER, [{"op": "person.merge", "id": str(p["id"]), "into": str(into["id"])}], via="mcp")["results"][0]
        if not r["ok"]:
            return f"Не слил: {r['error']}"
        return f"Слил «{p['name']}» в «{into['name']}»: ссылок переехало {r.get('moved', 0)}, другие имена — {', '.join(r['row']['aliases'])}."
    add = {"aliases": [a for a in (also or []) if a], "phones": [phone] if phone else [],
           "telegram_username": telegram.lstrip("@") if telegram else None}
    r = store.apply_ops(url, USER, [{"op": "person.add", "id": str(p["id"]), "add": add}], via="mcp")["results"][0]
    if not r["ok"]:
        return f"Не записал: {r['error']}"
    row = r["row"]
    bits = [f"другие имена: {', '.join(row['aliases']) or '—'}"]
    if row.get("phones"):
        bits.append(f"номера: {', '.join(row['phones'])}")
    return ("Записал. " if r.get("changed") else "Уже было. ") + f"{row['name']} — " + "; ".join(bits) + "."


# ── Инструменты коннектора «Правка» в claude.ai ─────────────────────────


def register(mcp, url: str) -> None:
    """Инструменты Дел и CRM для Claude — архив регистрирует их, если задан DELA_DB_URL.

    Подпись, описание для Claude и код — здесь, у Дел: новое поле дела — одна правка в этом файле.
    До 06.10.2026 подписи жили в pravka_archive/app.py и каждое поле добавляли в оба места (LOGIC.md §11).
    Тот же код, что у службы Дел, ролью её же базы.
    """
    import anyio

    from pravka_dela import mcp_tools as dela

    def run(fn, *a, **kw):
        return anyio.to_thread.run_sync(lambda: fn(url, *a, **kw))

    @mcp.tool()
    async def dela_view(view: str = "morning", sphere: str | None = None, person: str | None = None,
                        project: str | None = None, query: str | None = None) -> str:
        """Дела списком. view: morning (утро: сейчас, на сегодня и просроченное, кому пора напомнить, поставили другие), new («Новое» — что предложила автоматика), waiting (жду, по людям), person (по человеку: повестка, жду, его просьбы — нужен person), project (проект: сделки, задачи, время из ленты — нужен project), week (неделя: протухшее, жду без движения, проекты без следующего шага), quick (до 10 минут), now (сейчас), search (поиск — нужен query). sphere: work, home или пусто (всё). Имена людей и проектов — как говорит Саша («Додо», «Наташа»)."""
        return await run(dela.view, view, sphere, person, project, query)

    @mcp.tool()
    async def dela_person(name: str, also: list[str] | None = None, phone: str | None = None,
                          telegram: str | None = None, merge_into: str | None = None) -> str:
        """Один человек на всю систему. name — как его называют («Женя Соколов», «Соколов из Ромашки»); also — его другие имена, чтобы встречи, звонки и разбор узнавали; phone — номер; telegram — @имя. merge_into — живая карточка, в которую слить эту (дубль уходит в архив, всё его переезжает). Неуверенное не трогает — вернёт кандидатов."""
        return await run(dela.person, name, also, phone, telegram, merge_into)

    @mcp.tool()
    async def dela_task(ref: str) -> str:
        """Дело целиком: поля, заметки, комментарии, журнал правок. ref — короткий номер («57»)."""
        return await run(dela.card, ref)

    @mcp.tool()
    async def dela_add(title: str, project: str | None = None, deal: str | None = None, person: str | None = None,
                       ball: str = "mine", due_date: str | None = None, due_time: str | None = None,
                       nudge_on: str | None = None, requested_by: str | None = None, estimate_min: int | None = None,
                       money: str | None = None, want: bool | None = None, now: bool | None = None,
                       labels: list[str] | None = None, notes: str | None = None, remind_at: str | None = None,
                       remind_place: str | None = None) -> str:
        """Новое дело Саше. title — «Кто: действие». project — проект (пусто — «Входящие»), deal — сделка проекта. ball: mine, waiting (жду от person), agenda (поднять при встрече с person). due_date, nudge_on — YYYY-MM-DD. money: paid, potential, none (пусто — как у проекта). now — в «Сейчас» на сегодня. labels — только контексты вроде «звонок». Напомнить Саше в Telegram (бот Ковчега): remind_at — «YYYY-MM-DD HH:MM» по Москве («напомни завтра в 10»), или remind_place — по приезду в место телефона («дом»); одно из двух. Срок не назван — due_date = день напоминания."""
        return await run(dela.add, title=title, project=project, deal=deal, person=person, ball=ball, due_date=due_date,
                         due_time=due_time, nudge_on=nudge_on, requested_by=requested_by, estimate_min=estimate_min,
                         money=money, want=want, now=now, labels=labels, notes=notes, remind_at=remind_at,
                         remind_place=remind_place)

    @mcp.tool()
    async def dela_change(ref: str, title: str | None = None, notes: str | None = None, project: str | None = None,
                          deal: str | None = None, person: str | None = None, ball: str | None = None,
                          due_date: str | None = None, due_time: str | None = None, nudge_on: str | None = None,
                          requested_by: str | None = None, estimate_min: int | None = None, money: str | None = None,
                          want: bool | None = None, now: bool | None = None, labels: list[str] | None = None,
                          status: str | None = None, comment: str | None = None, remind_at: str | None = None,
                          remind_place: str | None = None) -> str:
        """Поправить дело по номеру: любые поля как у dela_add; status: done (сделано), cancelled (отменено), open (вернуть); comment — дописать комментарий. Пустая строка в поле — очистить его (due_date="" — без срока, remind_at="" — без напоминания). Новое remind_at или remind_place взводит напоминание заново, даже если старое уже приходило. Что не передано — не меняется."""
        return await run(dela.change, ref, comment=comment, title=title, notes=notes, project=project, deal=deal,
                         person=person, ball=ball, due_date=due_date, due_time=due_time, nudge_on=nudge_on,
                         requested_by=requested_by, estimate_min=estimate_min, money=money, want=want, now=now,
                         labels=labels, status=status, remind_at=remind_at, remind_place=remind_place)

    @mcp.tool()
    async def dela_decide(suggestion: str, decision: str, reason: str | None = None, title: str | None = None,
                          project: str | None = None, person: str | None = None, ball: str | None = None,
                          due_date: str | None = None) -> str:
        """Разобрать «Новое». suggestion — id из dela_view view=new (первых 8 знаков хватит) или «batch:<пачка>» — вся пачка встречи. decision: accept или reject. reason — почему отклонил (это учит разбор). Поля title, project, person, ball, due_date — принять с поправкой."""
        return await run(dela.decide, suggestion, decision, reason, title=title, project=project, person=person,
                         ball=ball, due_date=due_date)

    from pravka_dela import crm_tools as crm

    @mcp.tool()
    async def crm_view(view: str = "pipeline", project: str | None = None, deal: str | None = None,
                       person: str | None = None) -> str:
        """CRM ЗФ. view: pipeline (воронка по стадиям: гонорар, взвешенно, следующее дело, тишина, время), clients (клиенты: контакт, следующее дело, деньги за год, время за 90 дней; плюс клиенты из Засечки, которых нет в справочнике), client (клиент целиком: сделки, хронология, время по месяцам — нужен project), deal (сделка: модель гонорара, команда, дела, оплаты с id, хронология — нужны project и deal), person (человек: сделки и хронология — нужен person), ties (связи: кому пора напомнить о себе по теплоте, кто приводит сделки, дни рождения), money (деньги фирмы: дебиторка, ждём, получено, портфель, воронка)."""
        return await run(crm.view, view, project, deal, person)

    @mcp.tool()
    async def crm_deal(project: str, deal: str | None = None, name: str | None = None, stage: str | None = None,
                       outcome: str | None = None, lost_reason: str | None = None, deal_type: str | None = None,
                       lead: str | None = None, team: list[str] | None = None, people: list[str] | None = None,
                       source: str | None = None, fee_kind: str | None = None, fee_rub: float | None = None,
                       retainer_rub: float | None = None, success_pct: float | None = None, probability: int | None = None,
                       expected_on: str | None = None, deadline: str | None = None, my_view: str | None = None,
                       ideas: str | None = None) -> str:
        """Завести сделку клиента (project + name) или поправить (project + deal). stage: lead, proposal (КП), mandate, active, closing, archive; outcome: won (сделали), lost (проиграли), paused (заморожено) — сам уводит в архив, lost_reason — почему. deal_type: M&A, Банковский advisory, Управленка, Косткаттинг, Финансирование, Сопровождение. lead — ответственный в ЗФ, team — команда ЗФ, people — люди клиента, source — кто привёл. fee_kind: fixed, retainer, success, hourly, mixed; fee_rub — гонорар всего, retainer_rub — в месяц, success_pct — процент успеха; probability — %, expected_on — когда решение (YYYY-MM-DD). my_view — «как я вижу» (видит только Саша). Пустая строка — очистить поле. Ответственный и команда видят клиента в Делах; деньги — только кому открыты."""
        return await run(crm.deal, project, deal=deal, name=name, lead=lead, team=team, people=people, source=source,
                         fee_rub=fee_rub, retainer_rub=retainer_rub, success_pct=success_pct, stage=stage, outcome=outcome,
                         lost_reason=lost_reason, deal_type=deal_type, fee_kind=fee_kind, probability=probability,
                         expected_on=expected_on, deadline=deadline, my_view=my_view, ideas=ideas)

    @mcp.tool()
    async def crm_payment(project: str, deal: str, amount_rub: float | None = None, kind: str | None = None,
                          title: str | None = None, due_on: str | None = None, invoiced_on: str | None = None,
                          paid_on: str | None = None, payment: str | None = None, cancel: bool = False,
                          note: str | None = None) -> str:
        """Оплата по сделке ЗФ: новая (amount_rub) или правка старой (payment — id из crm_view view=deal). kind: advance (аванс), stage (этап), final, success (success fee), retainer (за месяц), extra (допработы). due_on — когда ждём, invoiced_on — счёт выставлен, paid_on — деньги пришли (YYYY-MM-DD). cancel — оплаты не будет (строка остаётся)."""
        return await run(crm.payment, project, deal, amount_rub=amount_rub, kind=kind, title=title, due_on=due_on,
                         invoiced_on=invoiced_on, paid_on=paid_on, payment=payment, cancel=cancel, note=note)

    @mcp.tool()
    async def dela_note(summary: str, kind: str = "note", project: str | None = None, deal: str | None = None,
                        people: list[str] | None = None, next_step: str | None = None, at: str | None = None) -> str:
        """Запись в хронологию клиента и людей (бывший «Лог взаимодействий» Notion): звонок, встреча, переписка, заметка — не дело. kind: call, meeting, zoom, telegram, email, whatsapp, note, other. at — когда (ISO, пусто — сейчас)."""
        return await run(dela.note, summary, kind, project, deal, people, next_step, at)
