"""Инструменты CRM для Claude (коннектор «Правка»): воронка, клиенты, сделка, связи,
деньги — смотреть; сделка и оплаты — менять. Хронологию пишет dela_note.

Тот же код, что у веба (crm, store), от имени владельца: права решает база,
журнал пишет триггер с via = mcp. Ответы — компактным текстом.
"""

from __future__ import annotations

import datetime as dt

from . import crm, db, store
from .mcp_tools import USER, _block, _d, find_deal, find_person, find_project

STAGE_RU = {"lead": "лид", "proposal": "КП", "mandate": "мандат", "active": "в работе", "closing": "закрытие", "archive": "архив"}
OUTCOME_RU = {"won": "выиграли", "lost": "проиграли", "paused": "заморожено"}
FEE_RU = {"fixed": "фикс", "retainer": "ретейнер", "success": "success fee", "hourly": "почасово", "mixed": "смешанная"}
PAY_RU = {"advance": "аванс", "stage": "этап", "final": "финал", "success": "success fee", "retainer": "ретейнер", "extra": "допработы"}
CADENCE_RU = {"month": "раз в месяц", "quarter": "раз в квартал", "year": "раз в год"}


def rub(kop) -> str:
    if not kop:
        return ""
    r = round(kop / 100)
    if abs(r) >= 1_000_000:
        return f"{r / 1_000_000:.1f}".replace(".", ",").replace(",0", "") + " млн ₽"
    return f"{r:,}".replace(",", " ") + " ₽"


def hours(minutes) -> str:
    if not minutes:
        return ""
    return f"{minutes // 60} ч {minutes % 60:02d} мин" if minutes >= 60 else f"{minutes} мин"


def _names(conn) -> dict:
    return {r["id"]: r["short"] or r["name"] for r in conn.execute("SELECT id, name, short FROM crm.people")}


def deal_line(d: dict, names: dict) -> str:
    stage = STAGE_RU.get(d["stage"], d["stage"])
    if d.get("outcome"):
        stage += " — " + OUTCOME_RU[d["outcome"]] + (f": {d['lost_reason']}" if d.get("lost_reason") else "")
    bits = [f"{d['name']} ({d['project_name']})", stage]
    if d.get("deal_type"):
        bits.append(d["deal_type"])
    if names.get(d.get("lead_person_id")):
        bits.append("ведёт " + names[d["lead_person_id"]])
    if d.get("fee_kop"):
        bits.append("гонорар " + rub(d["fee_kop"]) + (f", взвешенно {rub(d['weighted_kop'])}" if d["stage"] in ("lead", "proposal") else ""))
    if d.get("paid_kop"):
        bits.append("получено " + rub(d["paid_kop"]))
    if d.get("to_get_kop"):
        bits.append("осталось получить " + rub(d["to_get_kop"]))
    if d.get("next_task"):
        nt = d["next_task"]
        bits.append(f"следующее: #{nt['num']} {nt['title']}" + (f" до {_d(nt['due_date'])}" if nt.get("due_date") else ""))
    elif d["stage"] != "archive":
        bits.append("НЕТ следующего дела")
    if d.get("last_touch"):
        bits.append("контакт " + _d(d["last_touch"]))
    if (d.get("quiet_days") or 0) > crm.STALE_DAYS and d["stage"] != "archive":
        bits.append(f"тишина {d['quiet_days']} дн.")
    if d.get("minutes_all"):
        bits.append("время " + hours(d["minutes_all"]))
    return " · ".join(bits)


def _pay_line(p: dict) -> str:
    st = "получено " + _d(p["paid_on"]) if p["paid_on"] else ("счёт " + _d(p["invoiced_on"]) if p["invoiced_on"] else "план")
    return (f"[{str(p['id'])[:8]}] {PAY_RU.get(p['kind'], p['kind'])}" + (f" «{p['title']}»" if p.get("title") else "")
            + f" {rub(p['amount_kop'])} · {st}"
            + (f" · ждём {_d(p['due_on'])}" if p["due_on"] and not p["paid_on"] else "")
            + (" · отменено" if p["cancelled_at"] else ""))


def view(url: str, name: str = "pipeline", project: str | None = None, deal: str | None = None,
         person: str | None = None) -> str:
    try:
        with db.session(url, USER, via="mcp") as conn:
            names = _names(conn)
            today = conn.execute("SELECT crm.today() AS d").fetchone()["d"]
            params: dict = {}
            if name in ("client", "deal"):
                params["project_id"] = find_project(conn, project or "")["id"]
            if name == "deal":
                params = {"deal_id": find_deal(conn, params["project_id"], deal or "")["id"]}
            if name == "person":
                params, name = {"person_id": find_person(conn, person or "")["id"]}, "dossier"
            if name not in crm.VIEWS:
                return "Виды CRM: pipeline, clients, client, deal, person, ties, money."
            v = crm.VIEWS[name](conn, USER, **params)
    except store.OpError as e:
        return f"Не вышло: {e}"
    out = [f"CRM — {name}, сегодня {today.strftime('%d.%m.%Y')}", ""]
    if name == "pipeline":
        t = v["totals"]
        out.append(f"Живых сделок {t['live']}, без движения месяц или без следующего дела: {t['stale']}. "
                   f"Воронка (лид и КП, взвешенно): {rub(t['pipeline_kop']) or '0'}; подписано, осталось получить: {rub(t['to_get_kop']) or '0'}.")
        for s in crm.STAGES:
            items = [d for d in v["deals"] if d["stage"] == s]
            if items:
                out += ["", f"{STAGE_RU[s]} ({len(items)}):", *(f"  {deal_line(d, names)}" for d in items)]
    elif name == "clients":
        for c in v["clients"]:
            if c["archived_at"]:
                continue
            bits = [c["name"], f"сделок в работе {c['live_deals']}"]
            if c.get("last_touch"):
                bits.append("контакт " + _d(c["last_touch"]))
            if c.get("next_task"):
                bits.append(f"следующее #{c['next_task']['num']} {c['next_task']['title']}")
            if c.get("paid_year_kop"):
                bits.append("получено за год " + rub(c["paid_year_kop"]))
            if c.get("minutes_90"):
                bits.append("время за 90 дн. " + hours(c["minutes_90"]))
            out.append("  " + " · ".join(bits))
        if v.get("unmatched"):
            out += ["", "Засечка знает, справочник нет (завести клиентом или алиасом к проекту):"]
            out += [f"  {u['client']} — {hours(u['minutes'])}, записей {u['entries']}, последняя {_d(u['last_day'])}" for u in v["unmatched"]]
    elif name in ("client", "deal", "dossier"):
        if name == "deal":
            d = v["deal"]
            out.append(deal_line(d, names))
            model = [FEE_RU.get(d.get("fee_kind") or ""), d.get("retainer_kop") and f"ретейнер {rub(d['retainer_kop'])}/мес",
                     d.get("success_pct") and f"успех {d['success_pct']}%", d.get("p_eff") is not None and f"вероятность {d['p_eff']}%",
                     d.get("expected_on") and f"решение ждём {_d(d['expected_on'])}"]
            if any(model):
                out.append("Модель: " + ", ".join(x for x in model if x))
            team = [names.get(x) for x in d.get("team_ids") or []]
            if any(team):
                out.append("Команда: " + ", ".join(x for x in team if x))
            if d.get("person_ids"):
                out.append("Со стороны клиента: " + ", ".join(names.get(x, "?") for x in d["person_ids"]))
            if d.get("source_person_id"):
                out.append("Привёл: " + names.get(d["source_person_id"], "?"))
            if d.get("my_view"):
                out.append("Как я вижу: " + d["my_view"])
            out += ["", *_block("Открытые дела", v["open"], today)]
            if v["payments"]:
                out += ["Оплаты:", *(f"  {_pay_line(p)}" for p in v["payments"])]
            if d.get("next_step") or d.get("log"):
                out.append("Из Notion (до мая 2026): " + (d.get("next_step") or "") + ((" | " + d["log"][:500]) if d.get("log") else ""))
        else:
            for d in v["deals"]:
                out.append("  сделка: " + deal_line(d, names))
        if v.get("months"):
            out.append("Время по месяцам: " + ", ".join(f"{m['month']} {hours(m['minutes'])}" for m in v["months"][:6]))
        tl = v["timeline"][:40]
        if tl:
            out += ["", "Хронология (свежие сверху):"]
            for i in tl:
                who = ", ".join(names[x] for x in i["person_ids"] if names.get(x))
                out.append(f"  {_d(i['at'])} {i['kind']}: {i['summary'][:220]}" + (f" ({who})" if who else "")
                           + (f" → {i['next_step'][:120]}" if i.get("next_step") else ""))
    elif name == "ties":
        out.append(f"Пора напомнить о себе: {sum(1 for p in v['people'] if p['due'])}")
        for p in v["people"]:
            last = f"контакт {p['since_days']} дн. назад" if p["since_days"] is not None else "контактов не записано"
            bits = [p["name"] + (f" ({p['org']})" if p.get("org") else ""), CADENCE_RU.get(p["cadence"]) or ("хаб" if p["hub"] else ""), last]
            if p["brought"]:
                bits.append(f"привёл сделок: {p['brought']}")
            if p.get("birthday_in") is not None and p["birthday_in"] <= 14:
                bits.append(f"день рождения через {p['birthday_in']} дн.")
            out.append(("  ! " if p["due"] else "    ") + " · ".join(b for b in bits if b))
    elif name == "money":
        t = v["totals"]
        out.append(f"Получено: месяц {rub(t['paid_month']) or '0'}, квартал {rub(t['paid_quarter']) or '0'}, год {rub(t['paid_year']) or '0'}. "
                   f"Дебиторка {rub(t['receivable']) or '0'}; ждём за 30 дней {rub(t['expected_30']) or '0'}; "
                   f"по подписанным осталось {rub(t['to_get']) or '0'}; воронка взвешенно {rub(t['pipeline']) or '0'}.")
        for key, title in (("overdue", "Просрочено (счёт выставлен)"), ("invoiced", "Счёт выставлен"),
                           ("expected", "Ждём в ближайший месяц"), ("later", "Позже или без даты")):
            if v[key]:
                out += ["", f"{title}:", *(f"  {p['project_name']} / {p['deal_name']}: {_pay_line(p)}" for p in v[key])]
        if v["unplanned"]:
            out += ["", "Подписано, а оплаты расписаны не на всю сумму: " + ", ".join(d["name"] for d in v["unplanned"])]
    return "\n".join(out).rstrip() + "\n"


DEAL_ARGS = {"stage", "outcome", "lost_reason", "deal_type", "probability", "expected_on", "deadline", "my_view", "ideas", "fee_kind"}


def deal(url: str, project: str, deal: str | None = None, name: str | None = None, lead: str | None = None,
         team: list[str] | None = None, people: list[str] | None = None, source: str | None = None,
         fee_rub: float | None = None, retainer_rub: float | None = None, success_pct: float | None = None,
         **fields) -> str:
    """Завести сделку (нет deal — нужен name) или поправить её. Пустая строка — очистить поле."""
    try:
        with db.session(url, USER, via="mcp") as conn:
            pid = find_project(conn, project)["id"]
            did = find_deal(conn, pid, deal)["id"] if deal else None
            data: dict = {k: (v if v != "" else None) for k, v in fields.items() if k in DEAL_ARGS and v is not None}
            if name:
                data["name"] = name
            if lead is not None:
                data["lead_person_id"] = str(find_person(conn, lead)["id"]) if lead else None
            if team is not None:
                data["team_ids"] = [str(find_person(conn, x)["id"]) for x in team]
            if people is not None:
                data["person_ids"] = [str(find_person(conn, x)["id"]) for x in people]
            if source is not None:
                data["source_person_id"] = str(find_person(conn, source)["id"]) if source else None
            for k, v in (("fee_kop", fee_rub), ("retainer_kop", retainer_rub)):
                if v is not None:
                    data[k] = round(v * 100) if v else None
            if success_pct is not None:
                data["success_pct"] = success_pct or None
    except store.OpError as e:
        return f"Не вышло: {e}"
    if did:
        if not data:
            return "Нечего менять."
        op = {"op": "deal.set", "id": str(did), "set": data}
    elif not name:
        return "Новой сделке нужно имя (name), или укажи deal — какую поправить."
    else:
        op = {"op": "deal.create", "data": {"project_id": str(pid), **data}}
    r = store.apply_ops(url, USER, [op], via="mcp")["results"][0]
    if not r["ok"]:
        return f"Не вышло: {r['error']}"
    with db.session(url, USER, via="mcp") as conn:
        shown = crm.deals(conn, "d.id = %s", [r["row"]["id"]])[0]
        return ("Поправил: " if did else "Завёл: ") + deal_line(shown, _names(conn))


def payment(url: str, project: str, deal: str, amount_rub: float | None = None, kind: str | None = None,
            title: str | None = None, due_on: str | None = None, invoiced_on: str | None = None,
            paid_on: str | None = None, payment: str | None = None, cancel: bool = False, note: str | None = None) -> str:
    """Новая оплата по сделке или правка старой (payment — id из crm_view view=deal)."""
    try:
        with db.session(url, USER, via="mcp") as conn:
            did = find_deal(conn, find_project(conn, project)["id"], deal)["id"]
            pay_id = None
            if payment:
                hit = conn.execute("SELECT id FROM crm.payments WHERE deal_id = %s AND id::text LIKE %s",
                                   (did, payment + "%")).fetchall()
                if len(hit) != 1:
                    return "Оплата не определилась: возьми id из crm_view view=deal."
                pay_id = hit[0]["id"]
    except store.OpError as e:
        return f"Не вышло: {e}"
    data = {k: v for k, v in {"kind": kind, "title": title, "due_on": due_on, "invoiced_on": invoiced_on,
                              "paid_on": paid_on, "note": note}.items() if v is not None}
    for k in ("due_on", "invoiced_on", "paid_on", "title", "note"):
        if data.get(k) == "":
            data[k] = None
    if amount_rub is not None:
        data["amount_kop"] = round(amount_rub * 100)
    if cancel:
        data["cancelled_at"] = dt.datetime.now(dt.timezone.utc).isoformat()
    if pay_id:
        op = {"op": "payment.set", "id": str(pay_id), "set": data}
    elif not data.get("amount_kop"):
        return "Новой оплате нужна сумма (amount_rub)."
    else:
        op = {"op": "payment.create", "data": {"deal_id": str(did), "kind": "stage", **data}}
    r = store.apply_ops(url, USER, [op], via="mcp")["results"][0]
    return ("Оплата " + _pay_line(r["row"])) if r["ok"] else f"Не вышло: {r['error']}"
