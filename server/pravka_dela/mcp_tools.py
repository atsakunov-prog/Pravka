"""Инструменты Дел для Claude (коннектор «Правка» в claude.ai).

Тот же код, что у API службы (store), под ролью службы и от имени владельца:
права решает база, журнал пишет триггер с via = mcp. Ответы — компактным
текстом, как у архива: Claude читает их глазами модели, не парсером.

Люди и проекты называются по-человечески («Додо», «Наташа»): имя ищется
среди алиасов. Дело — по короткому номеру «57» или «#57».

Напоминание в Telegram (06.10.2026): remind_at — «ГГГГ-ММ-ДД ЧЧ:ММ» по Москве, remind_place —
место телефона («дом»): «напомни мне завтра в 10» из claude.ai доходит до бота Ковчега.

Дела внутри дела (15.10.2026, правило 27): inside у dela_add и dela_change — номер дела, внутри которого (поля нового
от родителя — nest.nest_fill в store.op_task_create, как у всех входов), inside="" — вынуть, pos — место среди соседей; why —
только цель дерева (номер или название обычного дела — отказ с подсказкой inside, а не новая цель с именем дела).
Строка дела: «внутри #N …» у дела внутри, «внутри открыто N, сначала: …» у родителя; status=done последнего внутри —
«всё внутри сделано — теперь: …» (raised), status=open — родителю прежний срок (fell).
"""

from __future__ import annotations

import datetime as dt
import re
import uuid
from typing import Any

from . import db, krug, meetings, people, remind, store

USER = "sasha"  # архив — одного человека, и Claude в нём действует от его имени
BALL = {"mine": "запустить", "owed": "отбить", "waiting": "жду", "agenda": "повестка"}
MONEY = {"paid": "оплачено", "potential": "развитие", "none": ""}
STATUS = {"done": "сделано", "cancelled": "отменено", "open": ""}


def _d(v: Any) -> str:
    if not v:
        return ""
    if isinstance(v, str):
        v = dt.date.fromisoformat(v[:10])
    return v.strftime("%d.%m")


def line(t: dict, today: dt.date | None = None, dn: dict | None = None, first: dict | None = None) -> str:
    """Дело одной строкой: номер, название, проект, мяч, сроки, деньги; направления Круга и «Зачем».

    Дела внутри дела (15.10.2026, правило 27): у дела внутри — «внутри #N …» (и «место K» среди соседей), а не «зачем»
    (зачем — только цель дерева); у родителя — «внутри открыто N» и «сначала: …» (first — {id родителя: первое открытое
    внутри по месту}). Слово «шаг» — только у цели: дело с делами внутри целью не стало."""
    today = today or dt.date.today()
    bits = [f"#{t['num']} {t['title']}"]
    where = t.get("project_name") or ("" if t.get("goal") else "Входящие")  # цель живёт без проекта — в дереве
    if t.get("deal_name"):
        where += f" / {t['deal_name']}"
    if where:
        bits.append(where)
    who = t.get("person_short") or t.get("person_name")
    if t.get("owed"):  # «Отбить» (dela_0007): кто ждёт и с какого дня
        bits.append(f"ждёт {who or 'человек'} с {_d(t.get('created_at'))}")
    elif t["ball"] != "mine" or who:
        who = who or "кого — не указано"
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
    # «Сейчас» — отметка владельца на сегодня (13.10.2026): видно и в «Сегодня», и во «Всех делах».
    if t["status"] == "open" and t.get("focus_on") and str(t["focus_on"])[:10] == today.isoformat():
        bits.append("в «Сейчас»")
    m = MONEY.get(t.get("money_eff") or "none")
    if m:
        bits.append(m)
    if t.get("want"):
        bits.append("хочу сам")
    if t.get("labels"):
        bits.append(", ".join(t["labels"]))
    if STATUS.get(t["status"]):
        bits.append(STATUS[t["status"]])
    # Круг жизни (12.10.2026, дерево — 13.10): главное направление и метки, «Зачем» — номер цели; цель и практика.
    if t.get("goal"):
        bits.append(f"цель, практика {t['rhythm']}" if t.get("rhythm") else "цель")
    if t.get("dirs"):
        names = [(dn or {}).get(k, k) for k in t["dirs"]]
        bits.append(f"направление {names[0]}" + (f", метки {', '.join(names[1:])}" if names[1:] else ""))
    if t.get("why_num"):
        if t.get("why_goal", True):  # старый вид без why_goal — как раньше
            bits.append(f"зачем #{t['why_num']} {t.get('why_title') or ''}".rstrip())
        else:
            bits.append(f"внутри #{t['why_num']} {t.get('why_title') or ''}".rstrip() + (f" (место {t['pos']})" if t.get("pos") else ""))
    if t.get("steps_open"):
        if t.get("goal"):
            bits.append(f"шагов открыто {t['steps_open']}")
        else:
            nxt = (first or {}).get(str(t.get("id")))
            bits.append(f"внутри открыто {t['steps_open']}" + (f", сначала: #{nxt['num']} {nxt['title']}" if nxt else ""))
    return " · ".join(bits)


def _firsts(conn, items: list[dict]) -> dict:
    """{id родителя: первое открытое дело внутри по месту} — для «сначала: …» в строках коннектора."""
    ids = [str(t["id"]) for t in items if t.get("steps_open") and not t.get("goal")]
    if not ids:
        return {}
    rows = conn.execute(
        "SELECT DISTINCT ON (why_id) why_id, num, title FROM tasks.v_tasks WHERE why_id = ANY(%s::uuid[]) "
        "AND status = 'open' AND NOT goal ORDER BY why_id, pos NULLS LAST, created_at, num", (ids,)).fetchall()
    return {str(r["why_id"]): r for r in rows}


def _items_of(v) -> list[dict]:
    """Дела вида списком: items, overdue, mine, open…, и в группах (groups[].items, people[].items)."""
    out: list[dict] = []
    for x in v.values() if isinstance(v, dict) else []:
        if not isinstance(x, list):
            continue
        for t in x:
            if isinstance(t, dict) and "steps_open" in t:
                out.append(t)
            elif isinstance(t, dict) and isinstance(t.get("items"), list):
                out += [i for i in t["items"] if isinstance(i, dict) and "steps_open" in i]
    return out


def _block(title: str, items: list[dict], today, dn: dict | None = None, first: dict | None = None) -> list[str]:
    if not items:
        return []
    return [f"{title} ({len(items)}):", *(f"  {line(t, today, dn, first)}" for t in items), ""]


def _dirnames(conn) -> dict:
    """Ключ направления — короткое имя (Свод владельца krug.directions); Круга нет — пусто."""
    return {d["key"]: d.get("short") or d.get("name") or d["key"] for d in krug.directions(conn, USER)}


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


def find_person(conn, name: str, exact_only: bool = False) -> dict:
    """Человек по имени — тем же правилом, что сервер (аудит 15.10.2026, №22): точное имя единственное (сначала своё
    имя или короткое имя карточки, потом другое имя — как crm.match_name), потом уверенное people.who; иначе кандидаты
    словами. Шага «подстрока полного имени» больше нет: «Петров» находил «Анну Петрову» частью чужого слова.
    exact_only — для слияния: только точное единственное совпадение или id карточки, who не участвует (отмены у
    слияния нет — склеить не тех хуже, чем переспросить)."""
    rows = conn.execute(
        "SELECT id, name, short, aliases FROM crm.people WHERE archived_at IS NULL AND merged_into IS NULL").fetchall()
    try:
        pid = str(uuid.UUID(str(name).strip()))
    except ValueError:
        pid = None
    if pid:
        hit = [r for r in rows if str(r["id"]) == pid]
        if hit:
            return hit[0]
    n = store_norm(name)
    own = [r for r in rows if n and n in {store_norm(r["name"]), store_norm(r["short"])}]
    exact = own or [r for r in rows if n and n in set(map(store_norm, r["aliases"] or []))]
    if len(exact) == 1:
        return exact[0]
    if exact:
        raise NameError_(f"человек «{name}» не определился: {', '.join(r['name'] for r in exact[:8])}")
    if exact_only:
        raise NameError_(f"человек «{name}» не определился: для слияния нужно точное имя карточки или её id")
    # «Женя Соколов», «Соколов из Ромашки» — то же узнавание, что у встреч и звонков (people.who).
    w = people.who(conn, q=name)
    if w["sure"]:
        return {"id": w["best"]["id"], "name": w["best"]["name"], "short": w["best"]["short"], "aliases": []}
    cands = ", ".join(c["name"] for c in w["candidates"]) or "нет похожих"
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


def _like(conn, v: str, goal: bool) -> list[dict]:
    """Свои открытые цели (goal) или дела, в названии которых есть все слова [v] (от 4 букв) — кандидаты словами."""
    words = [w for w in re.findall(r"\w+", store_norm(v)) if len(w) >= 4] or [store_norm(v)]
    rows = conn.execute("SELECT t.id, t.num, t.title FROM tasks.tasks t LEFT JOIN tasks.krug k ON k.id = t.id AND k.kind = 'task' "
                        "WHERE t.owner_id = %s AND t.status = 'open' AND coalesce(k.goal, false) = %s ORDER BY t.num",
                        (USER, goal)).fetchall()
    return [r for r in rows if all(w in store_norm(r["title"]) for w in words)]


def _goal_ref(conn, v: str) -> dict | None:
    """«Зачем» — цель дерева: по номеру или точному названию открытой цели. Номер или название обычного дела — отказ
    с подсказкой «inside»; ничего похожего — None (заведётся новая цель словами)."""
    if v.lstrip("#").isdigit():
        g = store.task_by(conn, v)
        if not g:
            raise NameError_(f"«зачем»: дела {v} нет или оно не видно")
        if not g["goal"]:
            raise NameError_(f"#{g['num']} «{g['title']}» — дело, а не цель дерева: положить внутрь него — inside={g['num']}")
        return g
    n = store_norm(v)
    goals = [r for r in _like(conn, v, True) if store_norm(r["title"]) == n]
    if len(goals) == 1:
        return goals[0]
    if len(goals) > 1:
        raise NameError_(f"цель «{v}» не определилась: " + ", ".join(f"#{r['num']} {r['title']}" for r in goals[:8]))
    deeds = _like(conn, v, False)
    if deeds:
        raise NameError_(f"«{v}» — не цель дерева, а похоже на дело: " + "; ".join(f"#{r['num']} {r['title']}" for r in deeds[:8])
                         + " — положить внутрь дела: inside=<номер>; новая цель — why словами, не похожими на дело")
    return None


def _parent_ref(conn, v: str) -> dict:
    """Дело, внутри которого (inside): своё открытое дело по номеру или точному названию; цель — отказ (это why)."""
    if v.lstrip("#").isdigit():
        p = store.task_by(conn, v)
        if not p:
            raise NameError_(f"inside: дела {v} нет или оно не видно")
    else:
        n = store_norm(v)
        hit = [r for r in _like(conn, v, False) if store_norm(r["title"]) == n] or _like(conn, v, False)
        if len(hit) != 1:
            raise NameError_(f"inside: дело «{v}» не определилось: "
                             + ("; ".join(f"#{r['num']} {r['title']}" for r in hit[:8]) or "нет похожих") + " — дай номер")
        p = store.task_by(conn, hit[0]["num"])
    if p["goal"]:
        raise NameError_(f"#{p['num']} «{p['title']}» — цель дерева: к ней — why={p['num']}, inside — только обычное дело")
    if p["owner_id"] != USER or p["status"] != "open":
        raise NameError_(f"inside: #{p['num']} «{p['title']}» — " + ("чужое дело" if p["owner_id"] != USER else "закрыто"))
    return p


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
    # «Зачем» (Круг жизни): номер цели («57», «#57»), название открытой цели или новая цель словами. Только цель
    # дерева (15.10.2026): «положи это в дело „подготовить термшит“» claude.ai раньше слал why=<название> — неточное
    # уходило в why_title, и в дереве заводилась НОВАЯ ЦЕЛЬ с именем дела. Обычное дело — inside, why на него — отказ
    # с подсказкой.
    had_why = "why" in out
    if had_why and "inside" in out and str(out.get("why") or "").strip() and str(out.get("inside") or "").strip():
        raise NameError_("либо why (цель дерева), либо inside (дело, внутри которого) — не оба")
    if "why" in out:
        v = str(out.pop("why") or "").strip()
        if not v:
            out["why_id"] = None
        else:
            g = _goal_ref(conn, v)
            if g:
                out["why_id"] = str(g["id"])
            else:
                out["why_title"] = v
    # Дело внутри дела (15.10.2026, правило 27): inside — номер (или точное название) своего открытого дела, внутри
    # которого это дело; пустая строка — вынуть (если why в той же правке не назван: «Зачем» тогда решает он). Место
    # среди соседей — pos (1 — первым; 0 — снять место).
    if "inside" in out:
        v = str(out.pop("inside") or "").strip()
        if v:
            out["why_id"] = str(_parent_ref(conn, v)["id"])
            out["_inside"] = v
        elif not had_why:
            out["why_id"] = None
            out["_inside"] = ""
    if "pos" in out:
        out["pos"] = None if out["pos"] in (0, "0", "") else out["pos"]
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
    "now": "Сейчас", "today": "Сегодня", "all": "Все дела", "later": "Все дела", "morning": "Утро", "new": "Новое",
    "waiting": "Жду", "person": "По человеку", "quick": "Быстрое", "project": "Проект", "week": "Неделя", "search": "Поиск",
    "krug": "Круг жизни", "tree": "Дерево жизни",
}


def _krug_text(v: dict, out: list[str]) -> None:
    """Круг жизни обзором: направления по кругу с зоной, движение за неделю, цели верхнего уровня."""
    if not v["configured"]:
        out.append("Круг жизни не настроен: в Своде нет krug.directions.")
        return
    if v.get("principle"):
        out += [f"Принцип дня: {v['principle']['text']}", ""]
    for d in v["directions"]:
        m = d["move"]
        out.append(d["name"] + (f" (зона «{d['zone']}»)" if d.get("zone") else "") + (f" — {d['formula']}" if d.get("formula") else ""))
        out.append(f"  открыто {d['open']}, за неделю закрыто {m.get('done', 0)} (событий {m.get('events', 0)}, "
                   f"точка {m.get('radius', 0)} из 10)")
        if d["goals"]:
            out.append("  цели: " + "; ".join(f"#{g['num']} {g['title']}" + (f" ({g['open']})" if g["open"] else "") for g in d["goals"]))
        out.append("")
    if v["without"]:
        out.append(f"Без направления ({len(v['without'])}) — разборщик поставит в ближайший слот:")
        out += [f"  #{t['num']} {t['title']}" for t in v["without"][:20] if t.get("num")]
    out.append("Дерево целиком — dela_view view=tree (direction — одно направление, node — ветка одной цели).")


def _tree_text(v: dict, out: list[str]) -> None:
    """Дерево жизни текстом: направление › цели › подцели › проекты и дела, у узла — открыто и движение."""
    if not v["configured"]:
        out.append("Круг жизни не настроен: в Своде нет krug.directions.")
        return

    def walk(nodes, depth):
        for n in nodes:
            if n["kind"] == "project":
                # Дела проекта — dela_view view=project; пустой проект (старый клиент) дерево не засоряет.
                if n["open"] or n["moved"]:
                    out.append("  " * depth + f"▣ проект {n['name']} — открыто {n['open']}" + (f", за неделю {n['moved']}" if n["moved"] else ""))
                continue
            if n["kind"] == "goal":
                bits = [f"◆ #{n['num']} {n['title']}"]
                if n.get("rhythm"):
                    bits.append("практика: " + n["rhythm"])
                if n.get("no_steps"):
                    bits.append("ориентир, без дел")
                bits.append(f"открыто {n['open']}" + (f", за неделю {n['moved']}" if n["moved"] else ""))
            else:
                bits = [f"#{n['num']} {n['title']}"]
                if n.get("project_name"):
                    bits.append(n["project_name"])
                if n.get("owed"):
                    bits.append("отбить")
                elif n.get("ball") == "waiting":
                    bits.append("мониторить")
                if n.get("due_date"):
                    bits.append("срок " + _d(n["due_date"]))
            out.append("  " * depth + " · ".join(bits))
            walk(n.get("children") or [], depth + 1)

    if "node" in v:
        if not v["node"]:
            out.append("Такой цели в дереве нет (или она закрыта).")
            return
        walk([v["node"]], 0)
        return
    for d in v["directions"]:
        out.append(f"{d['name']} — открыто {d['open']}" + (f", за неделю {d['moved']}" if d["moved"] else ""))
        walk(d["children"], 1)
        out.append("")
    if v.get("none"):
        out.append(f"Без направления ({len(v['none'])}):")
        walk(v["none"], 1)


AUTO_SRC = {"meeting": "встречи", "telegram": "Telegram", "contacts": "телефонная книжка", "krug": "разборщик Круга",
            "other": "другое"}


def automation_line(b: dict) -> str:
    """Счётчик откатов автоматики одной строкой (аудит №29) — та же, что веб пишет под «Сделано само»."""
    parts = []
    if b["created"]:
        parts.append(f"поставлено само {b['created']} (отменил {b['created_undone']})")
    if b["closed"]:
        parts.append(f"закрыто {b['closed']} (вернул {b['closed_undone']})")
    if b["updated"]:
        parts.append(f"уточнено {b['updated']} ({b['updated_undone']})")
    if b["people"]:
        parts.append(f"людей {b['people']} ({b['people_undone']})")
    if b["asked"]:
        ans = [f"{w} {b[k]}" for k, w in (("yes", "да"), ("edited", "с правкой"), ("no", "нет"), ("expired", "погасло")) if b[k]]
        parts.append(f"вопросов {b['asked']}: " + ", ".join(ans))
    return " · ".join(parts)


def automation_lines(a: dict) -> list[str]:
    """«Автоматика за неделю» для dela_view week: всего, по источникам и частые причины «нет»."""
    line = automation_line(a["all"])
    if not line:
        return [f"Автоматика за {a['days']} дней: ничего сама не делала и не спрашивала."]
    out = [f"Автоматика за {a['days']} дней (в скобках — сколько Саша отменил или вернул): {line}"]
    for src, b in a["sources"].items():
        if automation_line(b):
            out.append(f"  {AUTO_SRC.get(src, src)}: {automation_line(b)}")
    if a["all"]["no_reasons"]:
        out.append("  частые «нет»: " + "; ".join(f"«{x['reason']}» — {x['n']}" for x in a["all"]["no_reasons"]))
    return out


def view(url: str, name: str = "today", sphere: str | None = None, person: str | None = None,
         project: str | None = None, query: str | None = None, direction: str | None = None,
         node: str | None = None) -> str:
    today = _today(url)
    params: dict = {"sphere": sphere}
    try:
        with db.session(url, USER, via="mcp") as conn:
            if name == "person":
                params["person_id"] = find_person(conn, person or "")["id"]
            if name == "project":
                params["project_id"] = find_project(conn, project or "")["id"]
            dn = _dirnames(conn)
            want = krug.norm_directions(conn, USER, [direction], NameError_) if direction else []
        if name == "search":
            params["q"] = query
        if name == "tree":
            params = {"direction": want[0] if want else None, "node": node}
        v = store.view(url, USER, name, **params)
        with db.session(url, USER, via="mcp") as conn:
            first = _firsts(conn, _items_of(v))  # «сначала: …» у дел с делами внутри
    except store.OpError as e:
        return f"Не вышло: {e}"
    head = f"Дела — {VIEW_TITLES.get(name, name)}, сегодня {today.strftime('%d.%m.%Y')} ({['пн','вт','ср','чт','пт','сб','вс'][today.weekday()]})"
    if want:
        head += f" — направление {dn.get(want[0], want[0])}"
    out = [head, ""]

    def block(title, items, today_):
        # Фильтр по направлению (главное или метка) — для любого списка дел.
        return _block(title, [t for t in items if not want or want[0] in (t.get("dirs") or [])], today_, dn, first)

    if name == "krug":
        _krug_text(v, out)
    elif name == "tree":
        _tree_text(v, out)
    elif name == "now":
        # «Сейчас» (13.10.2026) — только то, что Саша отметил сам; срок тут ни при чём.
        out += block("Сейчас — отметил Саша, сделать сегодня", v["items"], today) or [
            "Пусто: на сегодня Саша ничего не отметил в «Сейчас».", ""]
        out.append(f"В «Сейчас» {len(v['items'])} из {v['max']}: больше {v['max']} сервер не поставит. "
                   "Со сроком сегодня — dela_view view=today.")
    elif name == "today":
        # «Сегодня» (13.10.2026) — свои открытые со сроком ровно сегодня, три группы по приоритету: отбить, запустить,
        # мониторить (владелец 10.10.2026). Просроченного и «Сейчас» без срока здесь нет — только их числа внизу.
        if v.get("principle"):
            out += [f"Принцип дня: {v['principle']['text']}", ""]
        for g in v["groups"]:
            out += block(g["title"], g["items"], today)
        if not any(g["items"] for g in v["groups"]):
            out += ["Со сроком на сегодня всё закрыто." if v["done_today"] else "Со сроком на сегодня дел нет.", ""]
        out += block("Моё — подсказка Круга, срока на сегодня нет (никто не ждёт, двигает жизнь)", v.get("mine") or [], today)
        out.append(f"В «Сейчас» (отметил Саша): {v['now_count']} из {v['now_max']} — dela_view view=now.")
        if v["overdue_count"]:
            out.append(f"Просрочено: {v['overdue_count']} — в «Сегодня» не входит, оно во «Всех делах» (dela_view view=all).")
        # №5 (15.10.2026): «Отбить» без срока и «Мониторить» без движения больше недели — тоже не в «Сегодня», но на виду.
        if v.get("owed_nodue_count"):
            out.append(f"Ждут от Саши без срока («Отбить»): {v['owed_nodue_count']} — во «Всех делах».")
        if v.get("waiting_stale_count"):
            out.append(f"Ждёт от людей больше недели («Мониторить», напоминание не назначено): {v['waiting_stale_count']} "
                       "— dela_view view=week.")
        out.append(f"Закрыто сегодня: {v['done_today']}.")
        if v["new_count"]:
            out.append(f"В «Новом» ждут решения: {v['new_count']} (dela_view view=new).")
    elif name in ("all", "later"):
        # «Все дела»: сверху «Просрочено» — само оно ни в «Сейчас», ни в «Сегодня» не попадает; дальше три группы.
        out += block("Просрочено (срок прошёл)", v["overdue"], today)
        for g in v["groups"]:
            out += block(g["title"], g["items"], today)
        if len(out) == 2:
            out.append("Открытых дел нет.")
    elif name == "morning":
        out += block("Сейчас", v["now"], today) + block("На сегодня и просроченное", v["today"], today)
        out += block("Пора напомнить", v["nudge"], today)
        out += block("Поставили другие", v["from_others"], today)
        if v["new_count"]:
            out.append(f"В «Новом» ждут решения: {v['new_count']} (dela view=new).")
    elif name == "new":
        for b in v["batches"]:
            out.append(f"{b['title'] or 'Без пачки'}:")
            for s in b["items"]:
                p = s["payload"] or {}
                if s["kind"] == "person":
                    what = "человек: " + (p.get("title") or (p.get("person") or {}).get("name") or "")
                    hint = p.get("ask") or ""
                elif s["kind"] == "create":
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
            out += block(p["person"], p["items"], today)
    elif name == "person":
        pe = v["person"] or {}
        out.append(f"{pe.get('name')}" + (f" ({pe['role']})" if pe.get("role") else ""))
        out += block("Повестка с ним", v["agenda"], today) + block("Жду от него", v["waiting"], today)
        out += block("Его просьбы ко мне", v["asked"], today) + block("Моё о нём", v["mine_about"], today)
    elif name == "project":
        pr = v["project"]
        out.append(f"{pr['name']} · {pr['kind']} · деньги по умолчанию: {pr['money_default']}")
        for dl in v["deals"]:
            fee = f" · fee {dl['fee_kop'] // 100:,} ₽".replace(",", " ") if dl.get("fee_kop") else ""
            out.append(f"  сделка: {dl['name']} · {dl['stage']}{fee}" + (f" · следующий шаг: {dl['next_step']}" if dl.get("next_step") else ""))
        out += ["", *block("Открытые", v["open"], today), *block("Закрытые недавно", v["done"], today)]
        if v.get("minutes"):
            total = sum(r["minutes"] or 0 for r in v["minutes"])
            out.append(f"Время из ленты: {total // 60} ч {total % 60} мин за {len(v['minutes'])} дн.")
    elif name == "week":
        out += block("Протухшее", v["stale"], today) + block("Жду без движения", v["waiting_stale"], today)
        if v["no_next_step"]:
            out.append("Проекты с деньгами без моего следующего шага: " + ", ".join(p["name"] for p in v["no_next_step"]))
        if v["expired"]:
            out.append(f"Погасло предложений за неделю: {len(v['expired'])}")
        if v.get("automation"):
            out += automation_lines(v["automation"])
    else:
        out += block(VIEW_TITLES.get(name, name), v.get("items", []), today) or ["Пусто."]
    return "\n".join(out).rstrip() + "\n"


def card(url: str, ref: str) -> str:
    from .api import task_card

    c = task_card(url, USER, ref)
    if not c:
        return f"Дела #{ref} нет или оно не видно."
    t = c["task"]
    hist = c["history"][-12:]
    with db.session(url, USER, via="mcp") as conn:
        dn = _dirnames(conn)
        # Дела внутри (правило 27): по месту — первое и есть «сначала»; сделанные — числом.
        inside = conn.execute(
            "SELECT num, title, status, due_date, pos FROM tasks.v_tasks WHERE why_id = %s AND NOT goal AND status <> 'cancelled' "
            "ORDER BY status <> 'open', pos NULLS LAST, created_at, num", (t["id"],)).fetchall() if not t.get("goal") else []
        # Журнал строки Круга (только хозяину): куда вкладывали — номером и названием.
        ups = [h["after"]["why_id"] for h in hist if h.get("entity") == "tasks.krug" and (h.get("after") or {}).get("why_id")]
        named = {str(r["id"]): r for r in conn.execute(
            "SELECT id, num, title, goal FROM tasks.v_tasks WHERE id = ANY(%s::uuid[])", (ups,)).fetchall()} if ups else {}
    out = [line(t, None, dn), ""]
    live = [r for r in inside if r["status"] == "open"]
    if live:
        out += ["Внутри (по порядку; место — pos, «Раньше/Позже» — dela_change pos=…):",
                *(f"  {n}. #{r['num']} {r['title']}" + (f" · срок {_d(r['due_date'])}" if r.get("due_date") else "")
                  for n, r in enumerate(live, 1)), ""]
    if len(inside) > len(live):
        out += [f"Сделано внутри: {len(inside) - len(live)}", ""]
    if t.get("notes"):
        out += [t["notes"], ""]
    for cm in c["comments"]:
        # Приватный комментарий (основание из переписки и встреч, «только мне», аудит №8) — пометкой: людям не цитировать.
        mark = " (только тебе)" if cm.get("private") else ""
        out.append(f"— {cm['author_id']} {cm['created_at'][:16].replace('T', ' ')}{mark}: {cm['text']}")
    if hist:
        out.append("Журнал:")
        for h in hist:
            a = h.get("after") or {}
            what = ", ".join(sorted(a.keys()))[:120] if h["op"] == "update" else h["op"]
            if h.get("entity") == "tasks.krug" and "why_id" in a:  # вложить, вынуть, «Зачем» — строка Круга
                w = named.get(str(a["why_id"])) if a["why_id"] else None
                what = ("вынуто, без «Зачем»" if not a["why_id"] else
                        f"{'«Зачем» — цель' if w and w['goal'] else 'вложено в'} #{w['num']} {w['title']}" if w else "«Зачем» сменилось")
            elif h.get("entity") == "tasks.krug":
                what = "Круг: " + what
            out.append(f"  {h['at'][:16].replace('T', ' ')} {h['actor']} ({h['via']}): {what}")
    return "\n".join(out) + "\n"


def add(url: str, **fields) -> str:
    try:
        with db.session(url, USER, via="mcp") as conn:
            data = _resolve(conn, {k: v for k, v in fields.items() if v is not None})
            # Дело внутри дела (inside, 15.10.2026): проект, сделка, человек и срок — от родителя ставит сам сервер
            # (store.op_task_create → nest.nest_fill, одно правило для всех входов с вагона A поезда 16.10.2026: родитель в
            # «Сейчас», просрочен или на сегодня — сегодня, позже — тот же срок, без срока — без); названное сильнее
            # (пустая строка — тоже слово: «без человека», «без срока»), сделка — только вместе с проектом родителя.
            if data.pop("_inside", None) == "":
                data.pop("why_id", None)  # новое дело и так ни в чём не лежит
    except store.OpError as e:
        return f"Не записал: {e}"
    data["source"] = "mcp"
    r = store.apply_ops(url, USER, [{"op": "task.create", "task": data}], via="mcp")["results"][0]
    if not r["ok"]:
        return f"Не записал: {r['error']}"
    with db.session(url, USER, via="mcp") as conn:
        dn = _dirnames(conn)
    return "Записал: " + line(r["task"], None, dn)


def change(url: str, ref: str, comment: str | None = None, comment_private: bool = False, **fields) -> str:
    try:
        with db.session(url, USER, via="mcp") as conn:
            t = store.task_by(conn, ref)
            if not t:
                return f"Дела {ref} нет или оно не видно."
            if "deal" in fields and "project" not in fields:
                fields["project_id"] = t["project_id"]
            data = _resolve(conn, {k: v for k, v in fields.items() if v is not None})
            if "_inside" in data:
                said = data.pop("_inside")
                if not said and t.get("why_id") and t.get("why_goal"):
                    return (f"Не поменял: #{t['num']} не внутри дела — его «Зачем» цель #{t['why_num']} «{t['why_title']}»; "
                            "убрать цель — why=\"\"")
                if not said and not t.get("why_id"):
                    data.pop("why_id", None)  # и так само по себе
                if data.get("why_id") == str(t["id"]):
                    return "Не поменял: в самого себя не вложить."
    except store.OpError as e:
        return f"Не поменял: {e}"
    ops = []
    if data:
        ops.append({"op": "task.set", "id": str(t["id"]), "set": data})
    if comment:
        # comment_private (аудит №8): «только Саше» — команда комментария не увидит (приватный комментарий).
        ops.append({"op": "comment.add", "comment": {"task_id": str(t["id"]), "text": comment,
                                                     **({"private": True} if comment_private else {})}})
    if not ops:
        return "Нечего менять."
    res = store.apply_ops(url, USER, ops, via="mcp")["results"]
    bad = [r["error"] for r in res if not r["ok"]]
    if bad:
        return "Не поменял: " + "; ".join(bad)
    task = next((r["task"] for r in res if "task" in r), None)
    # Сделано последнее дело внутри — родитель поднялся (store._nest_rise); снова открыто — подъём отменён.
    after = []
    for r in res:
        if r.get("raised"):
            up = r["raised"]
            after.append(f"всё внутри сделано — теперь: #{up['num']} «{up['title']}»" + (", на сегодня" if up.get("moved") else ""))
        if r.get("fell"):
            dn_ = r["fell"]
            after.append(f"срок #{dn_['num']} «{dn_['title']}» — снова " + (_d(dn_["due_date"]) if dn_.get("due_date") else "без срока"))
    with db.session(url, USER, via="mcp") as conn:
        dn = _dirnames(conn)
        first = _firsts(conn, [task]) if task else {}
    return "Готово: " + (line(task, None, dn, first) if task else f"комментарий к #{t['num']}") + "".join(f" · {x}" for x in after)


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
         people: list[str] | None = None, next_step: str | None = None, at: str | None = None,
         private: bool = False) -> str:
    try:
        with db.session(url, USER, via="mcp") as conn:
            pid = find_project(conn, project)["id"] if project else None
            did = find_deal(conn, pid, deal)["id"] if deal and pid else None
            pids = [str(find_person(conn, p)["id"]) for p in (people or [])]
    except store.OpError as e:
        return f"Не записал: {e}"
    data = {"at": at or dt.datetime.now(dt.timezone.utc).isoformat(), "kind": kind, "summary": summary,
            "project_id": pid, "deal_id": did, "person_ids": pids, "next_step": next_step, "source": "manual"}
    if private:
        data["private"] = True  # аудит №9: запись «только Саше» — команда клиента её не увидит
    r = store.apply_ops(url, USER, [{"op": "interaction.add", "data": {k: v for k, v in data.items() if v is not None}}], via="mcp")["results"][0]
    if not r["ok"]:
        return f"Не записал: {r['error']}"
    return "Записал в хронологию — только тебе: команда записи не увидит." if private else "Записал в хронологию."


# ── Встречи (dela_0010, 12.10.2026) ────────────────────────────────────

SPHERE_RU = {"work": "рабочая", "home": "личная"}
MEETING_TEXT_MAX = 60000  # знаков расшифровки в ответ: длинную встречу Claude читает окнами (around)


def meetings_list(url: str, query: str | None = None, client: str | None = None, person: str | None = None,
                  sphere: str | None = None, since: str | None = None, until: str | None = None, limit: int = 30) -> str:
    """Встречи строками «Клиент: тема · люди · дата · длительность»; с query — где в разговоре это звучало."""
    params: dict = {"sphere": sphere, "since": since, "until": until, "q": query, "sort": "relevance" if query else "date"}
    try:
        with db.session(url, USER, via="mcp") as conn:
            if client:
                params["project_id"] = find_project(conn, client)["id"]
            if person:
                params["person_id"] = find_person(conn, person)["id"]
        v = store.view(url, USER, "meetings", **{k: x for k, x in params.items() if x})
    except store.OpError as e:
        return f"Не вышло: {e}"
    items = v["items"][: max(1, min(int(limit or 30), 200))]
    c = v["counts"]
    out = [f"Встречи: {len(v['items'])} из {c['all']} (рабочих {c['work']}, личных {c['home']}"
           + (f", ждут разбора {c['unnamed']}" if c["unnamed"] else "") + ")", ""]
    for m in items:
        out.append(f"[{m['ref']}] {m['line']}" + (" · не разобрана" if not m["named"] else "")
                   + (f" · вопрос: {m['question']}" if m.get("question") else ""))
        for h in m.get("hits") or []:
            out.append(f"    {h.get('speaker') or '—'}: {h['text']}  (реплика {h['n']})")
    if not items:
        out.append("Ничего не нашлось." if query else "Встреч нет.")
    return "\n".join(out)


def meeting_card(url: str, ref: str, around: int | None = None) -> str:
    """Встреча целиком: поля, сводка, договорённости, связанное и расшифровка (или окно вокруг реплики around)."""
    try:
        v = store.view(url, USER, "meeting", **({"id": ref} if len(str(ref)) > 20 else {"ref": str(ref).strip()}),
                       **({"around": around} if around is not None else {}))
    except store.OpError as e:
        return f"Не вышло: {e}"
    out = [v["line"], f"номер {v['ref']} · {SPHERE_RU.get(v['sphere'], 'не разобрана')}"
           + (f" · клиент {v['client']}" if v.get("client") else "") + (f" · сделка {v['deal']}" if v.get("deal") else "")
           + (f" · ссылка {v['url']}" if v.get("url") else "")]
    if v.get("question"):
        out.append(f"Разбор спрашивает: {v['question']}")
    if v.get("locked"):
        out.append("Владелец поправил сам: " + ", ".join(v["locked"]))
    if v.get("summary"):
        out += ["", "Сводка службы:", v["summary"]]
    if v.get("agreements"):
        out += ["", "Договорённости:"] + [f"— {a if isinstance(a, str) else a}" for a in v["agreements"]]
    if v.get("tasks"):
        out += ["", "Дела из встречи:"] + [f"#{t['num']} {t['title']} ({STATUS.get(t['status']) or 'открыто'})" for t in v["tasks"]]
    lines = v.get("lines") or []
    if lines:
        out += ["", "Расшифровка" + (f" (окно вокруг реплики {around})" if around is not None else "") + ":"]
        size, prev = 0, None
        for x in lines:
            who = x.get("speaker") or ""
            ln = f"[{x['n']}] " + (f"{who}: " if who and who != prev else "") + x["text"]
            prev = who or prev
            size += len(ln)
            if size > MEETING_TEXT_MAX:
                out.append(f"… дальше не влезло: всего реплик {v['lines_n']} — читай окнами, around=<номер реплики>.")
                break
            out.append(ln)
    return "\n".join(out)


def meeting_change(url: str, ref: str, title: str | None = None, topic: str | None = None, client: str | None = None,
                   deal: str | None = None, sphere: str | None = None, people: list[str] | None = None,
                   archived: bool | None = None, rename: bool = False) -> str:
    """Поправить встречу словами: что поправил — разбор больше не трогает."""
    s = {k: v for k, v in {"title": title, "topic": topic, "project": client, "deal": deal, "sphere": sphere,
                           "people": people, "archived": archived}.items() if v is not None}
    key = {"id": ref} if len(str(ref)) > 20 else {"ref": str(ref).strip()}
    r = store.apply_ops(url, USER, [{"op": "meeting.set", **key, "set": s, "rename": rename}], via="mcp")["results"][0]
    if not r["ok"]:
        return f"Не вышло: {r['error']}"
    return "Готово: " + r["meeting"]["line"]


def client_rename(url: str, name: str, new_name: str) -> str:
    """Клиент по-новому — везде (владелец 13.10.2026: «переименую клиента — чтобы и в истории встреч
    переименовалось»). Клиент из справочника — project.set name: дела, сделки и хронология показывают его
    ссылкой, встречи переименовывает база (dela_0012), старое имя ложится в другие имена. Нет в справочнике —
    это подпись встреч («Вера (клуб)»): у всех встреч с ней — новое имя (meeting.set everywhere); новое имя —
    клиент из справочника — встречи к нему."""
    new_name = (new_name or "").strip()
    if not new_name:
        return "Не вышло: новое имя пустое."
    with db.session(url, USER, via="mcp") as conn:
        p = meetings.find_project(conn, name, strict=True)
        target = meetings.find_project(conn, new_name, strict=True)
        row = None if p else conn.execute(
            "SELECT id FROM crm.meetings WHERE project_id IS NULL AND crm.norm(label) = crm.norm(%s) "
            "ORDER BY started_at DESC LIMIT 1", (name,)).fetchone()
        n = conn.execute("SELECT count(*) AS n FROM crm.meetings WHERE project_id = %s", (p["id"],)).fetchone()["n"] if p else 0
    if p:
        if target and target["id"] != p["id"]:
            return (f"Не вышло: «{new_name}» — уже другой клиент ({target['name']}). Это слияние двух клиентов, "
                    "а не переименование — скажи Саше.")
        r = store.apply_ops(url, USER, [{"op": "project.set", "id": str(p["id"]), "set": {"name": new_name}}], via="mcp")["results"][0]
        if not r["ok"]:
            return f"Не вышло: {r['error']}"
        return (f"Готово: «{p['name']}» теперь «{new_name}» — в делах, сделках, хронологии и встречах ({n}); "
                f"старое имя осталось другим именем клиента.")
    if not row:
        return f"Не вышло: нет ни клиента, ни встреч с подписью «{name}»."
    s = {"project_id": str(target["id"])} if target else {"label": new_name}
    r = store.apply_ops(url, USER, [{"op": "meeting.set", "id": str(row["id"]), "set": s, "everywhere": True}], via="mcp")["results"][0]
    if not r["ok"]:
        return f"Не вышло: {r['error']}"
    return (f"Готово: встречи с подписью «{name}» ({r['also'] + 1}) — " +
            (f"клиента «{target['name']}» (его в справочнике; «{name}» — теперь его другое имя)." if target
             else f"«{new_name}». В справочнике клиентов такого нет — заведёшь, встречи пристегнутся сами."))


def person(url: str, name: str, also: list[str] | None = None, phone: str | None = None,
           telegram: str | None = None, merge_into: str | None = None) -> str:
    """Один человек на всю систему: другое имя, номер, Telegram — к карточке; дубль — в живую карточку.

    Владелец (06.10.2026): «Женя из встречи — это тот же Евгений из карточки клиента». Карточку находит
    то же узнавание, что у встреч и звонков; неуверенное не трогаем — перечисляем кандидатов.
    """
    with db.session(url, USER, via="mcp") as conn:
        try:
            # Слияние — только точное имя или id обеих карточек (№22): отмены у него нет.
            p = find_person(conn, name, exact_only=bool(merge_into))
            into = find_person(conn, merge_into, exact_only=True) if merge_into else None
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
    # Чужое имя не дописано (№1): так зовут другую живую карточку — иначе имя перестало бы узнаваться у обоих.
    bits += [f"«{x['alias']}» не дописал — так зовут {x['name']}" for x in r.get("skipped") or []]
    return ("Записал. " if r.get("changed") else "Уже было. ") + f"{row['name']} — " + "; ".join(bits) + "."


def ration_view(url: str, name: str | None = None) -> str:
    """Рацион Саши текстом: блюда и продукты, или одно из них с веществами и блюдами, где он есть."""
    from . import ration
    try:
        book, rev, source = ration.load(url, USER)
        return ration.text(book, rev, source, name)
    except ration.RationError as e:
        return f"Не вышло: {e}."


def ration_product(url: str, name: str, **fields) -> str:
    """Продукт рациона: завести, поправить, убрать (и из блюд) — одной новой версией Свода."""
    from . import ration
    what = "убрать" if fields.get("remove") else "продукт"
    try:
        return ration.change(url, ration.put_product, f"claude: {what} «{name}»", name=name, **fields)
    except ration.RationError as e:
        return f"Не вышло: {e}."


def ration_dish(url: str, name: str, **fields) -> str:
    """Блюдо рациона: состав с граммами, приём, как называет Саша; завести или убрать."""
    from . import ration
    what = "убрать блюдо" if fields.get("remove") else "блюдо"
    try:
        return ration.change(url, ration.put_dish, f"claude: {what} «{name}»", name=name, **fields)
    except ration.RationError as e:
        return f"Не вышло: {e}."


# ── Дайджест Telegram (dela_0014, по карточкам — dela_0018, 14.10.2026) ──

DIGEST_DAYS = 7           # список без дат и поиска — за неделю: три сводки в будни, одна в выходной
DIGEST_PREVIEW = 220      # знаков сводки в строке списка


def _digest_ref(ref: str | None) -> str:
    """«digest:2026-10-13T2000», «2026-10-13T2000» или ссылка записи хронологии «…#пункт» — ref сводки."""
    r = str(ref or "").strip().split("#")[0]
    return r if not r or r.startswith("digest:") else "digest:" + r


def _digest_line(d: dict) -> str:
    counts = " · ".join(x for x in (f"чатов {d['chats']}" if d.get("chats") else "",
                                     f"сообщений {d['msgs']}" if d.get("msgs") else "") if x)
    s = d.get("summary") or {}
    parts = [f"ждут ответа {len(s['waiting'])}" if s.get("waiting") else "",
             f"по работе {len(s['work'])}" if s.get("work") else "", "дом" if s.get("family") else ""]
    head = f"[{d['ref']}] {d['label']}" + (f" · {counts}" if counts else "") + (" · " + ", ".join(x for x in parts if x)
                                                                                 if any(parts) else "")
    body = d.get("snippet") or " ".join(str(d.get("text") or "").split())
    if len(body) > DIGEST_PREVIEW:
        body = body[: DIGEST_PREVIEW - 1].rstrip() + "…"
    return head + (f"\n    {body}" if body else "")


def digests_list(url: str, since: str | None = None, until: str | None = None, query: str | None = None,
                 person: str | None = None, client: str | None = None, limit: int = 30) -> str:
    """Сводки дайджеста строками: окно, чаты, сколько ждут ответа; с query — строки сводки, где это есть."""
    from . import digest

    try:
        with db.session(url, USER, via="mcp") as conn:
            pid = str(find_person(conn, person)["id"]) if person else None
            prj = str(find_project(conn, client)["id"]) if client else None
            plain = not (since or until or query or pid or prj)
            v = digest.view_digest(conn, USER, days=DIGEST_DAYS if plain else None, limit=limit, q=query, since=since,
                                   until=until, person_id=pid, project_id=prj, err=store.OpError)
    except store.OpError as e:
        return f"Не вышло: {e}"
    what = []
    if since or until:
        what.append(f"с {since or '…'} по {until or 'сегодня'}")
    elif plain:
        what.append(f"за {DIGEST_DAYS} дней")
    if query:
        what.append(f"поиск «{query}»")
    if person:
        what.append(f"разнесены к человеку «{person}»")
    if client:
        what.append(f"разнесены к клиенту «{client}»")
    items = v["items"]
    if not items:
        return "Сводок нет (" + ", ".join(what) + ")." + (" Последняя — " + v["last"]["label"] + f" [{v['last']['ref']}]."
                                                      if v.get("last") else "")
    out = [f"Сводки дайджеста Telegram ({', '.join(what)}): {len(items)}, свежие сверху. Целиком — digest(ref)."]
    out += [_digest_line(d) for d in items]
    if len(items) >= max(1, int(limit or 30)):
        out.append(f"… показаны {len(items)} — дальше: until раньше последней или limit больше (до 50).")
    return "\n".join(out)


def digest_card(url: str, ref: str | None = None) -> str:
    """Сводка целиком: окно, текст по разделам, «В Дела», и куда её пункты разнесены в CRM."""
    from . import digest

    r = _digest_ref(ref)
    try:
        with db.session(url, USER, via="mcp") as conn:
            if not r or r in ("digest:last", "digest:последний", "digest:последняя"):
                last = digest.view_digest(conn, USER, err=store.OpError)["last"]
                if not last:
                    return "Сводок дайджеста ещё нет."
                r = last["ref"]
            v = digest.view_digest(conn, USER, ref=r, err=store.OpError)
    except store.OpError as e:
        return f"Не вышло: {e}"
    d = v["digest"]
    counts = " · ".join(x for x in (f"чатов {d['chats']}" if d.get("chats") else "",
                                     f"сообщений {d['msgs']}" if d.get("msgs") else "") if x)
    out = [f"Дайджест Telegram [{d['ref']}] — {d['label']}" + (f" · {counts}" if counts else ""), "", d["text"]]
    if v.get("crm"):
        out += ["", "В карточках CRM (видит только Саша):"]
        for x in v["crm"]:
            where = ", ".join(n for n in [*(x.get("people") or []), x.get("project_name") or "",
                                          x.get("deal_name") or ""] if n)
            out.append(f"  {where}: {x['summary'][:200]}")
    # «Только тебе» (этап 6 аудита, 16.10.2026): в текст сводки не входит (его пересылают) — отдельным блоком, с оценкой.
    mine = (d.get("summary") or {}).get("only_you") or []
    if mine:
        state = {x.get("item_key"): x.get("state") for x in v.get("insights") or []}
        out += ["", "Только тебе (видит только Саша; людям не цитировать):"]
        for o in mine:
            head = " ".join(x for x in (o.get("kind") or "", o.get("who") or "", o.get("at") or "") if x)
            tail = f" · оценка: {state[o.get('key')]}" if state.get(o.get("key")) not in (None, "new") else ""
            out.append(f"  {head}: «{o.get('quote') or ''}» — {o.get('why') or ''}"
                       + (f" Сделать: {o['do']}" if o.get("do") else "") + tail)
    return "\n".join(out)


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
    async def dela_view(view: str = "today", sphere: str | None = None, person: str | None = None,
                        project: str | None = None, query: str | None = None, direction: str | None = None,
                        node: str | None = None) -> str:
        """Дела списком. Главная у Саши — три блока сверху вниз: «Сейчас» → «Новое» → «Сегодня» (решение 13.10.2026). view: now («Сейчас» — только дела, которые Саша сам отметил на сегодня, до пяти; срок тут ни при чём; отметка — now=true в dela_add/dela_change, только по его слову), today (по умолчанию — «Сегодня»: свои открытые дела со сроком ровно сегодня, тремя группами по приоритету — «Отбить» (ждут от меня: человек ждёт от Саши ответа или дела, первым делом), «Запустить» (сам придумал), «Мониторить» (ждёт от человека); просроченного и «ждут» без срока здесь нет; внизу — сколько в «Сейчас», сколько просрочено, сколько ждут от Саши без срока и сколько он ждёт от людей больше недели, и «Моё» — подсказка Круга), all («Все дела»: сверху «Просрочено» — срок прошёл; само просроченное ни в «Сейчас», ни в «Сегодня» не попадает, живёт здесь; дальше всё открытое теми же группами; later — старое имя того же), new («Новое» — что предложила автоматика), morning (старое утро), waiting (жду, по людям), person (по человеку: повестка, жду, его просьбы — нужен person), project (проект: сделки, задачи, время из ленты — нужен project), week (неделя: протухшее, жду без движения, проекты без следующего шага, что автоматика за неделю сделала сама и что из этого Саша отменил или вернул, как он ответил на её вопросы), quick (до 10 минут), search (поиск — нужен query), krug (Круг жизни обзором: девять направлений, движение за неделю, цели верхнего уровня), tree (Дерево жизни: направление › цели › подцели › проекты и дела; direction — одно направление, node — ветка одной цели по номеру). Цель — состояние, к которому Саша идёт («сын хорошо знает математику»), цель с ритмом — практика; цели живут в дереве, в списках дел их нет. Дела внутри дела — обычные дела: у них «внутри #N …» (и место среди соседей), у родителя — «внутри открыто N, сначала: #…»; «зачем #N» — только цель. sphere: work, home или пусто (всё). direction — только дела одного направления Круга («Здоровье», «Дело»…). Имена людей и проектов — как говорит Саша («Ромашка», «Ваня»)."""
        return await run(dela.view, view, sphere, person, project, query, direction, node)

    @mcp.tool()
    async def dela_person(name: str, also: list[str] | None = None, phone: str | None = None,
                          telegram: str | None = None, merge_into: str | None = None) -> str:
        """Один человек на всю систему. name — как его называют («Женя Соколов», «Соколов из Ромашки»); also — его другие имена, чтобы встречи, звонки и разбор узнавали; phone — номер; telegram — @имя. merge_into — живая карточка, в которую слить эту (дубль уходит в архив, всё его переезжает); при слиянии обе карточки — только точным именем, коротким или другим именем карточки или её id, без догадок. Неуверенное не трогает — вернёт кандидатов."""
        return await run(dela.person, name, also, phone, telegram, merge_into)

    @mcp.tool()
    async def dela_task(ref: str) -> str:
        """Дело целиком: поля, заметки, комментарии, журнал правок (и когда и куда его вкладывали); у дела с делами внутри — они по порядку (первое — «сначала»). ref — короткий номер («57»)."""
        return await run(dela.card, ref)

    @mcp.tool()
    async def dela_add(title: str, project: str | None = None, deal: str | None = None, person: str | None = None,
                       ball: str = "mine", due_date: str | None = None, due_time: str | None = None,
                       nudge_on: str | None = None, requested_by: str | None = None, estimate_min: int | None = None,
                       money: str | None = None, want: bool | None = None, now: bool | None = None,
                       labels: list[str] | None = None, notes: str | None = None, remind_at: str | None = None,
                       remind_place: str | None = None, directions: list[str] | None = None, why: str | None = None,
                       goal: bool | None = None, rhythm: str | None = None, inside: str | None = None) -> str:
        """Новое дело Саше. title — «Кто: действие». project — проект (пусто — «Входящие»), deal — сделка проекта. ball — группа дела: owed («Отбить»: person ждёт от Саши ответа или дела — спросил, попросил, ждёт звонка), mine («Запустить»: Саша сам придумал), waiting («Мониторить»: ждёт от person), agenda («Запустить»: поднять при встрече с person). due_date, nudge_on — YYYY-MM-DD. money: paid, potential, none (пусто — как у проекта). now — отметить в «Сейчас»: до пяти дел, которые Саша сам берёт на сегодня (только по его слову «в сейчас»; «на сегодня», «срочно» — это due_date сегодня, «Сегодня»), now=false — снять. labels — только контексты вроде «звонок». Напомнить Саше в Telegram (бот Ковчега): remind_at — «YYYY-MM-DD HH:MM» по Москве («напомни завтра в 10»), или remind_place — по приезду в место телефона («дом»); одно из двух. Срок не назван — due_date = день напоминания. Дело внутри дела: inside — номер своего открытого дела («57»), внутри которого это дело — «для этого», «до этого надо», «это часть того дела», «положи в дело про термшит» (обычные дела, вложенные в дело; отдельной сущности «шаг» нет): проект, сделка, человек и срок возьмутся от него (оно в «Сейчас», просрочено или на сегодня — сегодня; позже — тот же срок; без срока — без), названное сильнее. Дерево жизни: why — «Зачем», только ЦЕЛЬ дерева, к которой ведёт дело: номер цели, её название или новая цель словами (заведётся целью); номер или название обычного дела в why — отказ (это inside); место дела — по цели, а не по тому, кто попросил. Не передал — разборщик поставит сам. directions — направления, только если цели нет (главное первым; «Здоровье», «Дело»…); дело в проекте клиента и шаг к цели и так их наследуют. goal=true — завести не дело, а цель дерева (состояние: «сын хорошо знает русский»; без проекта, в списках дел её нет) — только по слову Саши; rhythm — ритм практики («ежедневно», «2 раза в неделю»): цель, которая повторяется."""
        return await run(dela.add, title=title, project=project, deal=deal, person=person, ball=ball, due_date=due_date,
                         due_time=due_time, nudge_on=nudge_on, requested_by=requested_by, estimate_min=estimate_min,
                         money=money, want=want, now=now, labels=labels, notes=notes, remind_at=remind_at,
                         remind_place=remind_place, directions=directions, why=why, goal=goal, rhythm=rhythm,
                         inside=inside)

    @mcp.tool()
    async def dela_change(ref: str, title: str | None = None, notes: str | None = None, project: str | None = None,
                          deal: str | None = None, person: str | None = None, ball: str | None = None,
                          due_date: str | None = None, due_time: str | None = None, nudge_on: str | None = None,
                          requested_by: str | None = None, estimate_min: int | None = None, money: str | None = None,
                          want: bool | None = None, now: bool | None = None, labels: list[str] | None = None,
                          status: str | None = None, comment: str | None = None, remind_at: str | None = None,
                          remind_place: str | None = None, directions: list[str] | None = None, why: str | None = None,
                          goal: bool | None = None, rhythm: str | None = None, inside: str | None = None,
                          pos: int | None = None, comment_private: bool = False) -> str:
        """Поправить дело по номеру: любые поля как у dela_add; status: done (сделано), cancelled (отменено), open (вернуть); comment — дописать комментарий (comment_private=true — «только Саше»: команда, которая видит дело, его не увидит; для личного — оценок людей, пересказа переписки). Пустая строка в поле — очистить его (due_date="" — без срока, remind_at="" — без напоминания). Новое remind_at или remind_place взводит напоминание заново, даже если старое уже приходило. Что не передано — не меняется. directions=[] — снять свои направления (дело снова наследует); why="" — убрать «Зачем» (дело повиснет на направлении); why — только цель дерева. inside — вложить это дело внутрь другого своего открытого дела (номер; «положи это в дело про термшит», «это часть того дела»), inside="" — вынуть (дело станет само по себе). pos — место среди дел внутри того же дела: 1 — первым («сначала»), 0 — снять место (после занумерованных); сменил inside без pos — место снимается само. status=done у последнего открытого дела внутри: дело-родитель не закрывается, а поднимается на сегодня — ответ скажет «всё внутри сделано — теперь: …»; status=open вернёт и прежний срок родителя. goal=true/false — сделать делом-целью дерева или вернуть в дела (только по слову Саши: цели ставит он); rhythm — ритм практики, rhythm="" — уже не практика."""
        return await run(dela.change, ref, comment=comment, comment_private=comment_private, title=title, notes=notes, project=project, deal=deal,
                         person=person, ball=ball, due_date=due_date, due_time=due_time, nudge_on=nudge_on,
                         requested_by=requested_by, estimate_min=estimate_min, money=money, want=want, now=now,
                         labels=labels, status=status, remind_at=remind_at, remind_place=remind_place,
                         directions=directions, why=why, goal=goal, rhythm=rhythm, inside=inside, pos=pos)

    @mcp.tool()
    async def dela_decide(suggestion: str, decision: str, reason: str | None = None, title: str | None = None,
                          project: str | None = None, person: str | None = None, ball: str | None = None,
                          due_date: str | None = None, directions: list[str] | None = None) -> str:
        """Разобрать «Новое». suggestion — id из dela_view view=new (первых 8 знаков хватит) или «batch:<пачка>» — вся пачка встречи. decision: accept или reject. reason — почему отклонил (это учит разбор). Поля title, project, person, ball, due_date, directions (направления Круга — вопрос разборщика «Театр — Пара или Ум?») — принять с поправкой."""
        return await run(dela.decide, suggestion, decision, reason, title=title, project=project, person=person,
                         ball=ball, due_date=due_date, directions=directions)

    from pravka_dela import crm_tools as crm

    @mcp.tool()
    async def crm_view(view: str = "pipeline", project: str | None = None, deal: str | None = None,
                       person: str | None = None) -> str:
        """CRM ЗФ. view: pipeline (воронка по стадиям: гонорар, взвешенно, следующее дело, тишина, время), clients (клиенты: контакт, следующее дело, деньги за год, время за 90 дней; плюс клиенты из Засечки, которых нет в справочнике), client (клиент целиком: сделки, хронология, время по месяцам — нужен project), deal (сделка: модель гонорара, команда, дела, оплаты с id, хронология — нужны project и deal), person (человек: сделки и хронология — нужен person), ties (связи: кому пора напомнить о себе по теплоте, кто приводит сделки, дни рождения), money (деньги фирмы: дебиторка, ждём, получено, портфель, воронка)."""
        return await run(crm.view, view, project, deal, person)

    @mcp.tool()
    async def crm_private(text: str | None = None, person: str | None = None, client: str | None = None,
                          deal: str | None = None, kind: str = "note") -> str:
        """«Только тебе» — наблюдения Саши, которые видит только он (оценки людей, несостыковки, неуверенность, «кто за столом», что уточнить, личное о людях); команда, хронология клиента и синк их не видят. Людям — в письмах, сообщениях и черновиках — их не цитировать. С text — записать о человеке (person), клиенте (client) или сделке (client и deal); kind — note (заметка) или вид разбора (conflict, hedge, table, ask, signal…). Без text — показать записанное (по person, client, deal или всё)."""
        return await run(crm.private, text, person, client, deal, kind)

    @mcp.tool()
    async def crm_deal(project: str, deal: str | None = None, name: str | None = None, stage: str | None = None,
                       outcome: str | None = None, lost_reason: str | None = None, deal_type: str | None = None,
                       lead: str | None = None, team: list[str] | None = None, people: list[str] | None = None,
                       source: str | None = None, fee_kind: str | None = None, fee_rub: float | None = None,
                       retainer_rub: float | None = None, success_pct: float | None = None, probability: int | None = None,
                       expected_on: str | None = None, deadline: str | None = None, my_view: str | None = None,
                       ideas: str | None = None, description: str | None = None) -> str:
        """Завести сделку клиента (project + name) или поправить (project + deal). stage: lead, proposal (КП), mandate, active, closing, archive; outcome: won (сделали), lost (проиграли), paused (заморожено) — сам уводит в архив, lost_reason — почему. deal_type: M&A, Банковский advisory, Управленка, Косткаттинг, Финансирование, Сопровождение. lead — ответственный в ЗФ, team — команда ЗФ, people — люди клиента, source — кто привёл. fee_kind: fixed, retainer, success, hourly, mixed; fee_rub — гонорар всего, retainer_rub — в месяц, success_pct — процент успеха; probability — %, expected_on — когда решение (YYYY-MM-DD). description — что за проект словами (видят все, кто видит сделку; Саша зовёт сделки проектами клиента); my_view — «как я вижу» (видит только Саша). Пустая строка — очистить поле. Ответственный и команда видят клиента в Делах; деньги — только кому открыты."""
        return await run(crm.deal, project, deal=deal, name=name, lead=lead, team=team, people=people, source=source,
                         fee_rub=fee_rub, retainer_rub=retainer_rub, success_pct=success_pct, stage=stage, outcome=outcome,
                         lost_reason=lost_reason, deal_type=deal_type, fee_kind=fee_kind, probability=probability,
                         expected_on=expected_on, deadline=deadline, my_view=my_view, ideas=ideas, description=description)

    @mcp.tool()
    async def crm_payment(project: str, deal: str, amount_rub: float | None = None, kind: str | None = None,
                          title: str | None = None, due_on: str | None = None, invoiced_on: str | None = None,
                          paid_on: str | None = None, payment: str | None = None, cancel: bool = False,
                          note: str | None = None) -> str:
        """Оплата по сделке ЗФ: новая (amount_rub) или правка старой (payment — id из crm_view view=deal). kind: advance (аванс), stage (этап), final, success (success fee), retainer (за месяц), extra (допработы). due_on — когда ждём, invoiced_on — счёт выставлен, paid_on — деньги пришли (YYYY-MM-DD). cancel — оплаты не будет (строка остаётся)."""
        return await run(crm.payment, project, deal, amount_rub=amount_rub, kind=kind, title=title, due_on=due_on,
                         invoiced_on=invoiced_on, paid_on=paid_on, payment=payment, cancel=cancel, note=note)

    @mcp.tool()
    async def meetings(query: str | None = None, client: str | None = None, person: str | None = None,
                       sphere: str | None = None, since: str | None = None, until: str | None = None, limit: int = 30) -> str:
        """Встречи Саши (служба встреч: Zoom, очные, звонки) — название «Клиент: тема», участники, дата, длительность; в квадратных скобках — номер встречи. query — найти в разговорах (словоформы: «счета» найдёт «счёт», «счетов»; имена — подстрокой): у каждой встречи — реплики, где это звучало, с номерами. client — клиент или проект, person — участник, sphere: work (рабочие) или home (личные: терапия, семья — без просьбы Саши не открывай), since/until — ГГГГ-ММ-ДД. Целиком — meeting."""
        return await run(dela.meetings_list, query, client, person, sphere, since, until, limit)

    @mcp.tool()
    async def meeting(ref: str, around: int | None = None) -> str:
        """Встреча целиком: участники, сводка и договорённости службы, дела из неё, расшифровка репликами с номерами. ref — номер встречи из meetings. around — номер реплики: только окно ±15 реплик вокруг неё (длинную встречу читать так, от сниппета поиска)."""
        return await run(dela.meeting_card, ref, around)

    @mcp.tool()
    async def meeting_change(ref: str, title: str | None = None, topic: str | None = None, client: str | None = None,
                             deal: str | None = None, sphere: str | None = None, people: list[str] | None = None,
                             archived: bool | None = None, rename: bool = False) -> str:
        """Поправить встречу по номеру: title — название целиком («Клиент: тема»), topic — тема (название пересоберётся), client — клиент или проект («-» — без клиента), deal — сделка клиента, sphere — work (рабочая) или home (личная), people — участники из справочника (все, кроме Саши), archived — убрать из списка. Что поправлено — разбор встреч больше не трогает. rename — отдать встречу разбору заново (поправленное остаётся)."""
        return await run(dela.meeting_change, ref, title, topic, client, deal, sphere, people, archived, rename)

    @mcp.tool()
    async def client_rename(name: str, new_name: str) -> str:
        """Переименовать клиента везде: в делах, сделках, хронологии и названиях встреч («Клиент: тема»); старое имя остаётся его другим именем — по нему клиент узнаётся и дальше. name — как клиент зовётся сейчас: имя из справочника или подпись встреч, у которых клиента в справочнике нет (как в meetings). Новое имя — уже другой клиент из справочника: у подписи встречи переходят к нему; у клиента из справочника — отказ (это слияние, а не переименование)."""
        return await run(dela.client_rename, name, new_name)

    @mcp.tool()
    async def ration(name: str | None = None) -> str:
        """Рацион Саши — его постоянная еда: блюда (наборы продуктов с граммами: «Моя каша», «Индейка с кускусом») и продукты (КБЖУ на 100 г с его этикеток, обычная порция, как он их называет голосом, вещества на 100 г). Телефон по нему разбирает еду голосом (названо блюдо — все его продукты) и пишет еду тапом. name — одно блюдо или продукт подробно (по названию или как его называет Саша); пусто — весь рацион. Перед правкой посмотри: имена и id — отсюда."""
        return await run(dela.ration_view, name)

    @mcp.tool()
    async def ration_product(name: str, new_name: str | None = None, grams: int | None = None,
                             kcal100: float | None = None, protein100: float | None = None, fat100: float | None = None,
                             carbs100: float | None = None, aliases: list[str] | None = None,
                             add_aliases: list[str] | None = None, sure: str | None = None, pill: bool | None = None,
                             note: str | None = None, micro: dict[str, float] | None = None, remove: bool = False) -> str:
        """Продукт рациона: завести, поправить или убрать. name — продукт (название, id или как его зовёт Саша); нет такого — заведётся новый, и тогда нужны kcal100, protein100, fat100, carbs100 (на 100 г) и grams (обычная порция, г). С фото этикетки — цифры на 100 г прямо с неё и sure «точно»; прикинул сам — sure «примерно». new_name — переименовать. aliases — как Саша его называет голосом (весь список заново), add_aliases — дописать к имеющимся: по ним телефон узнаёт продукт в речи, даже искажённой («индейка сладкий соус чили»). pill=true — добавка без калорий (креатин). note — заметка («упаковка 200 г»), пустая строка — убрать. micro — вещества на 100 г, только заметное: ключи va, vd, ve, vk, vc, b1, b2, b3, b6, b9 (фолат, мкг), b12, ca, fe, mg, zn, k, na, se, i, o3 (единицы — как у нормы: витамины A, D, K, B9, B12, селен, йод — мкг, омега-3 — г, остальное — мг); 0 — убрать вещество. remove=true — убрать продукт из рациона и из всех блюд. Что не передано — не меняется."""
        return await run(dela.ration_product, name, new_name=new_name, grams=grams, kcal100=kcal100,
                         protein100=protein100, fat100=fat100, carbs100=carbs100, aliases=aliases,
                         add_aliases=add_aliases, sure=sure, pill=pill, note=note, micro=micro, remove=remove)

    @mcp.tool()
    async def ration_dish(name: str, new_name: str | None = None, kind: str | None = None,
                          items: list[dict] | None = None, add: list[dict] | None = None, drop: list[str] | None = None,
                          aliases: list[str] | None = None, add_aliases: list[str] | None = None,
                          note: str | None = None, remove: bool = False) -> str:
        """Блюдо рациона — набор его продуктов с граммами («Моя каша», «Индейка с кускусом»): завести, поправить или убрать. name — блюдо (название, id или как его зовёт Саша); нет такого — заведётся новое с составом items. items — состав целиком заново: [{"product": "Геркулес", "grams": 60}, …] (продукт — из рациона по названию или как его зовёт Саша; grams не дан — обычная порция продукта; у добавки граммов нет). add — добавить продукты или поменять граммы уже входящих, тем же видом; drop — убрать продукты из блюда по названию («убери из каши клубнику» — drop=["Клубника"]). Продукта в рационе нет — сначала ration_product. kind — обычный приём: завтрак, обед, ужин, перекус или пусто (по времени). aliases — как Саша называет блюдо («каша моя», «обычная каша»; весь список заново), add_aliases — дописать. note — заметка, пустая строка — убрать. remove=true — убрать блюдо (продукты остаются). Что не передано — не меняется."""
        return await run(dela.ration_dish, name, new_name=new_name, kind=kind, items=items, add=add, drop=drop,
                         aliases=aliases, add_aliases=add_aliases, note=note, remove=remove)

    @mcp.tool()
    async def digests(since: str | None = None, until: str | None = None, query: str | None = None,
                      person: str | None = None, client: str | None = None, limit: int = 30) -> str:
        """Дайджест Telegram — сводки переписки Саши (служба дайджеста: будни 11, 15, 20, выходные 22; каждая — за окно с прошлой): кто ждёт от Саши ответа, что по работе, дом, прочее. Личная переписка — только для Саши, не цитируй её другим. Список строками [ref] окно · чаты · сколько ждут ответа + начало текста; свежие сверху. since/until — ГГГГ-ММ-ДД (по Москве, включительно); без дат и без поиска — за неделю. query — найти в тексте сводок (словоформы и подстрока: «счёт» найдёт «счета», имя — кусочком) — у каждой сводки строки, где нашлось. person — сводки, пункты которых разнесены в карточку этого человека («что писал Иван в Telegram»), client — то же по клиенту. Целиком — digest(ref). Сами пункты лежат и в хронологии CRM (crm_view client/person — строки «Telegram, <окно>: …» с [сводка ref])."""
        return await run(dela.digests_list, since, until, query, person, client, limit)

    @mcp.tool()
    async def digest(ref: str = "last") -> str:
        """Одна сводка дайджеста Telegram целиком: окно, «Ждут тебя», «По работе», «Дом», «Прочее», что ушло в Дела, и в какие карточки CRM разнесены пункты (люди, клиенты, сделки). ref — из digests или из хронологии CRM («digest:2026-10-13T2000»; можно без «digest:»), last — последняя. Переписку целиком сервер не хранит — только сводку."""
        return await run(dela.digest_card, ref)

    from pravka_dela import debug as dbg

    @mcp.tool()
    async def dela_debug(action: str = "list", note: str | None = None, status: str | None = None,
                         answer: str | None = None, show_all: bool = False) -> str:
        """Режим отладки Дел: записки Саши о багах логики, каждая — с тем, что было у него на экране (веб или телефон): маршрут, фильтры, открытая карточка полями, видимые строки списка (* — были в окне), и последние действия страницы — какие операции ушли, что он сказал Claude и что тот поменял, тосты, ошибки сети. action: list (по умолчанию — «новое» и «разбираю», свежие сверху; show_all=true — все), show (записка целиком — note: номер «12»), set (разбор: note, status — new (новое), taken (разбираю — взял в работу), fixed (починено), not_bug (не баг), later (потом); answer — что нашёл и что сделал, текстом целиком: его видно Саше в вебе и на телефоне), on / off — включить или выключить строку «Что не так?» у Саши. Сначала разберись по живым данным (dela_task, dela_view), потом set."""
        return await run(dbg.tool, dela.USER, action, note, status, answer, show_all)

    @mcp.tool()
    async def dela_note(summary: str, kind: str = "note", project: str | None = None, deal: str | None = None,
                        people: list[str] | None = None, next_step: str | None = None, at: str | None = None,
                        private: bool = False) -> str:
        """Запись в хронологию клиента и людей (бывший «Лог взаимодействий» Notion): звонок, встреча, переписка, заметка — не дело. kind: call, meeting, zoom, telegram, email, whatsapp, note, other. at — когда (ISO, пусто — сейчас). private=true — «только Саше»: команда клиента запись не увидит (личное, оценки, пересказ переписки); снять потом нельзя. Команда видит работу по проекту — факт касания, а не личные каналы и оценки."""
        return await run(dela.note, summary, kind, project, deal, people, next_step, at, private)
