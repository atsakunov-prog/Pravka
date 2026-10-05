"""Перенос из Todoist, Notion CRM и ленты Засечки в Дела.

Сухой прогон по умолчанию: строится план и отчёт, база не трогается.
С --apply план записывается от имени службы импорта (svc:import) — каждая
строка в журнале. id строк выводятся из id источника (uuid5), поэтому
повторный перенос не плодит дублей и только дописывает новое: строка, которая
уже есть в Делах, живёт своей жизнью, и перенос её не трогает. Раньше он писал
поверх (ON CONFLICT DO UPDATE), а install-dela.ps1 зовёт его при каждой
установке: 04–05.10 так трижды вернулись в работу закрытые дела, выигранные
сделки — в «в работе», стёрлись оценки сделок, связи дел со сделками и людей с
учётками. Мост Todoist (bridge.py) так же заводит только новое.

Код общий: ни имён, ни клиентов здесь нет. Всё, что знает только владелец
(кто есть кто, какие написания одно и то же, какой проект какой), лежит в
файле решений рядом с выгрузками (--plan) и в репозиторий не попадает:
репозиторий публичный.

Источники:
- Todoist — выгрузка API v1 или снимок коннектора Claude (форма определяется сама);
- Notion — строки баз «Сделки» и «Контакты» (запрос вида коннектора Claude);
- лента — список клиентов как записаны (из архива, life.entries).
"""

from __future__ import annotations

import datetime as dt
import json
import re
import uuid
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from psycopg.types.json import Jsonb

from . import db

NS = uuid.UUID("6f1d2a3e-5c7b-4e8f-9a0b-1c2d3e4f5a6b")

STAGES = {"Лид": "lead", "КП": "proposal", "Мандат": "mandate", "В работе": "active", "Закрытие": "closing", "Архив": "archive"}
CADENCE = {"месяц": "month", "2-3 мес": "quarter", "год": "year", "не видимся": "none"}
PRIORITY_MONEY = {"p1": "paid", "p2": "potential", "p3": "none", "p4": "none"}


def sid(*parts: str) -> str:
    """Постоянный id строки по её источнику: повтор переноса попадает в ту же строку."""
    return str(uuid.uuid5(NS, ":".join(parts)))


def norm(s: str | None) -> str:
    return re.sub(r"\s+", " ", (s or "").replace("ё", "е").replace("Ё", "Е").strip().lower())


# ── Источники ───────────────────────────────────────────────────────────


def load_todoist(path: Path) -> dict:
    """Две формы: API v1 (priority 4 = P1, due{}, project_id) и снимок коннектора."""
    d = json.loads(path.read_text(encoding="utf-8"))
    tasks = []
    for t in d.get("tasks", []):
        if "projectId" in t:  # снимок коннектора
            tasks.append({
                "id": t["id"], "content": t["content"], "description": t.get("description") or "",
                "due": t.get("dueDate"), "recurring": t.get("recurring") or None, "priority": t.get("priority", "p4"),
                "project": t["projectId"], "labels": t.get("labels", []), "added": t.get("addedAt"),
                "checked": t.get("checked", False), "completed": t.get("completedAt"),
            })
        else:  # API v1
            due = t.get("due") or {}
            tasks.append({
                "id": t["id"], "content": t["content"], "description": t.get("description") or "",
                "due": (due.get("date") or "")[:10] or None, "recurring": due.get("string") if due.get("is_recurring") else None,
                "priority": f"p{5 - int(t.get('priority', 1))}", "project": t["project_id"], "labels": t.get("labels", []),
                "added": t.get("added_at"), "checked": t.get("checked", False), "completed": t.get("completed_at"),
            })
    projects = []
    for p in d.get("projects", []):
        projects.append({
            "id": p["id"], "name": p["name"],
            "inbox": bool(p.get("inbox") or p.get("inbox_project") or p.get("is_inbox_project")),
            "folder": p.get("folder") or p.get("folder_id"),
        })
    comments = d.get("comments", [])  # [{id, task_id, content, posted_at}]
    return {"tasks": tasks, "projects": projects, "labels": d.get("labels", []), "comments": comments}


def load_notion(path: Path) -> list[dict]:
    return json.loads(path.read_text(encoding="utf-8")).get("results", [])


def rel(v: Any) -> list[str]:
    if not v:
        return []
    try:
        return list(json.loads(v)) if isinstance(v, str) else list(v)
    except ValueError:
        return []


def clean_md(s: str | None) -> str | None:
    """Текст Notion: <br> — перевод строки, экранирование Markdown — прочь."""
    if not s:
        return None
    s = s.replace("<br>", "\n")
    s = re.sub(r"\\([\[\]\\:*_`>#|~])", r"\1", s)
    return s.strip() or None


PHONE = re.compile(r"\+?\d[\d\s\-()]{8,}\d")
EMAIL = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")
TG = re.compile(r"(?:t\.me/|(?<![\w.])@)([A-Za-z][A-Za-z0-9_]{3,31})\b")


def parse_contacts(s: str | None) -> dict:
    s = s or ""
    emails = EMAIL.findall(s)
    rest = s
    for e in emails:
        rest = rest.replace(e, " ")
    phones = [re.sub(r"[^\d+]", "", p) for p in PHONE.findall(rest)]
    tg = TG.findall(rest)
    return {"phones": phones, "emails": emails, "telegram_username": tg[0] if tg else None}


# ── План ────────────────────────────────────────────────────────────────


@dataclass
class Plan:
    users: list[dict] = field(default_factory=list)
    orgs: list[dict] = field(default_factory=list)
    people: list[dict] = field(default_factory=list)
    projects: list[dict] = field(default_factory=list)
    deals: list[dict] = field(default_factory=list)
    tasks: list[dict] = field(default_factory=list)
    comments: list[dict] = field(default_factory=list)
    interactions: list[dict] = field(default_factory=list)
    labels: list[str] = field(default_factory=list)
    notes: dict[str, list[str]] = field(default_factory=lambda: defaultdict(list))

    def note(self, section: str, text: str) -> None:
        self.notes[section].append(text)


class Directory:
    """Справочник, который план собирает по ходу: поиск по имени, короткому имени и алиасам."""

    def __init__(self, plan: Plan):
        self.plan = plan
        self.people_by: dict[str, list[dict]] = defaultdict(list)
        self.projects_by: dict[str, dict] = {}
        self.orgs_by: dict[str, dict] = {}

    def add_person(self, p: dict) -> dict:
        self.plan.people.append(p)
        for n in {norm(x) for x in (p["name"], p.get("short") or "", *p.get("aliases", [])) if x}:
            self.people_by[n].append(p)
        return p

    def alias_person(self, p: dict, alias: str) -> None:
        if norm(alias) not in {norm(a) for a in p["aliases"]} and norm(alias) not in (norm(p["name"]), norm(p.get("short"))):
            p["aliases"].append(alias)
        if p not in self.people_by[norm(alias)]:
            self.people_by[norm(alias)].append(p)

    def person(self, name: str, among: list[dict] | None = None) -> dict | None:
        found = list({id(p): p for p in self.people_by.get(norm(name), [])}.values())
        if among:
            found = [p for p in found if p in among] or found
        if len(found) > 1 and among:
            return None
        if len(found) == 1:
            return found[0]
        # Только имя («Михаил»): ищем среди людей проекта по первому слову.
        if among and " " not in norm(name):
            hits = [p for p in among if norm(p["name"]).split(" ")[0] == norm(name)]
            return hits[0] if len(hits) == 1 else None
        return None

    def add_project(self, p: dict) -> dict:
        self.plan.projects.append(p)
        for n in {p["name"], *p.get("aliases", [])}:
            self.projects_by[norm(n)] = p
        return p

    def project(self, name: str) -> dict | None:
        return self.projects_by.get(norm(name))

    def org(self, name: str, owner: str) -> dict:
        key = norm(name)
        if key not in self.orgs_by:
            o = {"id": sid("org", key), "name": name.strip(), "aliases": [], "kind": "other", "owner_id": owner}
            self.orgs_by[key] = o
            self.plan.orgs.append(o)
        return self.orgs_by[key]


def build(todoist: dict | None, deals: list[dict], contacts: list[dict], clients: list | None, dec: dict) -> Plan:
    plan = Plan()
    owner = next(u["id"] for u in dec["users"] if u.get("role") == "owner")
    d = Directory(plan)

    # Пользователи и их люди, команда и семья — из решений владельца.
    for p in dec.get("people", []):
        d.add_person({
            "id": sid("person", "dec", norm(p["name"])), "name": p["name"], "short": p.get("short"),
            "aliases": list(p.get("aliases", [])), "role": p.get("role"), "owner_id": owner,
            "phones": [], "emails": [], "note": p.get("note"),
            "birth_day": p.get("birth_day"), "birth_month": p.get("birth_month"), "birth_year": p.get("birth_year"),
            "user_id": p.get("user"), "_org": p.get("org"),
        })
    for u in dec["users"]:
        person = d.person(u["person"]) if u.get("person") else None
        plan.users.append({"id": u["id"], "name": u["name"], "role": u.get("role", "member"),
                           "settings": u.get("settings", {}), "person_id": person["id"] if person else None})

    # Контакты Notion: человек, его организация, как с ним связаться, теплота.
    contact_by_url: dict[str, dict] = {}
    for c in contacts:
        name = (c.get("Имя") or "").strip()
        if not name:
            continue
        known = d.person(name)
        info = parse_contacts(c.get("Контакты"))
        fields = {
            "role": (c.get("Роль") or "").strip() or None,
            "source": clean_md(c.get("Источник")), "seeks": clean_md(c.get("Ищет")),
            "offers": clean_md(c.get("Предлагает")), "traits": clean_md(c.get("Особенности")),
            "cadence": next((v for k, v in CADENCE.items() if k in (c.get("Теплота") or "")), None),
            "hub": c.get("Хаб") == "__YES__", "notion_id": c.get("url"),
            **{k: v for k, v in info.items() if v},
        }
        if known:  # уже есть (решения или такой же контакт выше): дополняем
            if known.get("notion_id") and fields.get("notion_id"):
                plan.note("Notion: дубли контактов слиты в одного человека", name)
                fields.pop("notion_id")
            for k, v in fields.items():
                if v and not known.get(k):
                    known[k] = v
            p = known
        else:
            p = d.add_person({"id": sid("person", "notion", c["url"]), "name": name, "short": None, "aliases": [],
                              "owner_id": owner, "phones": [], "emails": [], **fields})
        company = (c.get("Компания") or "").strip()
        if company and not p.get("_org"):
            p["_org"] = company
        contact_by_url[c["url"]] = p
        last = c.get("date:Посл. контакт:start")
        if last:
            plan.interactions.append({
                "id": sid("interaction", "last", c["url"]), "at": f"{last[:10]}T12:00:00+03:00", "kind": "other",
                "summary": "Последний контакт (по Notion)", "person_ids": [p["id"]], "source": "import",
                "source_ref": c["url"], "owner_id": owner,
            })
        if c.get("Контакты") and not any(info.values()):
            plan.note("Контакты Notion: не разобрал, как связаться", f"{name}: «{c['Контакты'][:80]}»")

    # Проекты Todoist (кроме «Входящих»). Подпроекты папок сливаются в проект клиента.
    merge = dec.get("merge_projects", {})
    kinds = dec.get("project_kinds", {})
    aliases = dec.get("project_aliases", {})
    tproj: dict[str, dict] = {}
    if todoist:
        for tp in todoist["projects"]:
            if tp["inbox"]:
                continue
            target = merge.get(tp["name"], tp["name"])
            p = d.project(target)
            if not p:
                kind = kinds.get(target, "client")
                p = d.add_project({
                    "id": sid("project", "todoist", tp["id"]) if target == tp["name"] else sid("project", "name", norm(target)),
                    "name": target, "aliases": list(aliases.get(target, [])), "kind": kind,
                    "sphere": "home" if kind == "personal" else "work", "owner_id": owner,
                    "import_ref": f"todoist:{tp['id']}" if target == tp["name"] else None, "_money": Counter(),
                })
            tproj[tp["id"]] = p
            if target != tp["name"]:
                p.setdefault("_merged", []).append(tp["name"])
                plan.note("Проекты: слиты в один", f"«{tp['name']}» → «{target}» (задачи помнят сделку, если она есть)")
    personal_name = dec.get("personal_project")
    if personal_name and not d.project(personal_name):
        d.add_project({"id": sid("project", "name", norm(personal_name)), "name": personal_name, "aliases": [],
                       "kind": "personal", "sphere": "home", "owner_id": owner, "_money": Counter()})

    def project_for(name: str) -> dict:
        p = d.project(name)
        if not p:
            p = d.add_project({"id": sid("project", "name", norm(name)), "name": name, "aliases": list(aliases.get(name, [])),
                               "kind": kinds.get(name, "client"), "sphere": "work", "owner_id": owner, "_money": Counter(), "_new": True})
        return p

    # Сделки Notion: проект — по решению владельца, иначе по префиксу «Клиент: тема».
    deal_projects = dec.get("deal_projects", {})
    deal_by_url: dict[str, dict] = {}
    deal_by_name: dict[str, dict] = {}
    for n in deals:
        name = (n.get("Название") or "").strip()
        if not name:
            continue
        pname = deal_projects.get(name) or (name.split(":", 1)[0].strip() if ":" in name else name)
        p = project_for(pname)
        lead = d.person(n.get("Ответственный ЗФ") or "") if n.get("Ответственный ЗФ") else None
        fee = n.get("Деньги")
        deal = {
            "id": sid("deal", "notion", n["url"]), "project_id": p["id"], "name": name,
            "stage": STAGES.get(n.get("Статус") or "", "lead"), "deal_type": n.get("Тип"),
            "lead_person_id": lead["id"] if lead else None,
            "person_ids": [contact_by_url[u]["id"] for u in rel(n.get("Участники")) if u in contact_by_url],
            "fee_kop": int(round(float(fee) * 100)) if fee not in (None, "") else None,
            "deadline": (n.get("date:Дедлайн:start") or "")[:10] or None, "wheel": n.get("Колесо крутится?") == "__YES__",
            "ball": clean_md(n.get("Мяч")), "next_step": clean_md(n.get("Следующий шаг")),
            "my_view": clean_md(n.get("Статус как я вижу")), "ideas": clean_md(n.get("Идеи")), "log": clean_md(n.get("Лог")),
            "notion_id": n["url"], "_project": p["name"],
        }
        plan.deals.append(deal)
        deal_by_url[n["url"]] = deal
        deal_by_name[norm(name)] = deal
        p.setdefault("_stages", Counter())[deal["stage"]] += 1
        if p.get("_new") and not p.get("_noted"):
            p["_noted"] = True
            plan.note("Проекты: новые клиенты из сделок Notion (в Todoist их не было)", p["name"])
        if n.get("Ответственный ЗФ") and not lead:
            plan.note("Сделки: ответственный не нашёлся в команде", f"{name}: {n['Ответственный ЗФ']}")

    # Организации: клиент — у проекта своя, контакт — по полю «Компания».
    org_aliases = dec.get("org_of_company", {})
    for p in plan.projects:
        if p["kind"] == "client":
            o = d.org(p["name"], owner)
            o["kind"] = "client"
            o["aliases"] = sorted(set(o["aliases"]) | set(p.get("aliases", [])))
            p["org_id"] = o["id"]
    for p in plan.people:
        company = p.pop("_org", None)
        if company:
            target = org_aliases.get(company, company)
            proj = d.project(target)
            o = d.org(proj["name"] if proj else target, owner)
            p["org_id"] = o["id"]

    # Задачи Todoist.
    label_people = {k: v for k, v in dec.get("label_people", {}).items()}
    labels_keep = set(dec.get("labels_keep", ["звонок"]))
    birthdays = dec.get("birthdays", {})
    task_ids: set[str] = set()
    if todoist:
        for t in todoist["tasks"]:
            title = t["content"].strip()
            if t["id"] in birthdays:  # повторяющееся «поздравить» — день рождения у человека
                b = birthdays[t["id"]]
                who = d.person(b["person"])
                if who:
                    who["birth_day"], who["birth_month"] = b["day"], b["month"]
                    plan.note("Повторяющиеся: стали днями рождения у людей", f"{title} → {who['name']}, {b['day']:02d}.{b['month']:02d}")
                    continue
            if t.get("recurring"):
                plan.note("Повторяющиеся: перенесены разовыми (повторов в Делах нет)", f"{title} — «{t['recurring']}»")
            labels = list(t["labels"])
            proj_src = next((tp for tp in todoist["projects"] if tp["id"] == t["project"]), None)
            if "личное" in labels and personal_name:
                p = d.project(personal_name)
            elif proj_src and proj_src["inbox"]:
                p = None
            else:
                p = tproj.get(t["project"])
            deal = deal_by_name.get(norm(proj_src["name"])) if proj_src and proj_src["name"] in merge else None
            ball = "agenda" if "повестка" in labels else "waiting" if "жду" in labels else "mine"
            # Человек: метки людей, иначе префикс «Кто: …».
            from_labels = [label_people[l] for l in labels if l in label_people]
            person = None
            if len(from_labels) > 1:
                plan.note("Задачи: несколько людей — взял первого, поправь", f"{title} ({', '.join(from_labels)})")
            if from_labels:
                person = d.person(from_labels[0])
            prefix = title.split(":", 1)[0].strip() if ":" in title[:70] else None
            mapped = dec.get("title_people", {}).get(prefix or "")
            if not person and mapped:
                person = d.person(mapped)
                if not person:
                    plan.note("Задачи: человек из решений не нашёлся", f"{title} → «{mapped}»")
            if not person and not mapped and prefix and not d.project(prefix) and norm(prefix) not in {norm(x) for x in dec.get("not_people", [])}:
                among = [x for x in plan.people if p and (x.get("org_id") == p.get("org_id") or any(
                    dl["project_id"] == p["id"] and x["id"] in dl["person_ids"] for dl in plan.deals))]
                person = d.person(prefix, among) or next(
                    (d.person(part, among) for part in re.split(r"[/(),]", prefix) if part.strip() and d.person(part.strip(), among)), None)
                if not person:
                    person = d.add_person({"id": sid("person", "todoist", norm(prefix)), "name": prefix, "short": prefix,
                                           "aliases": [], "owner_id": owner, "phones": [], "emails": [],
                                           "note": "Заведён переносом из Todoist по названию задачи — уточнить."})
                    plan.note("Люди: новые из названий задач (уточнить, кто это)", f"{prefix} — из «{title}»")
            money = PRIORITY_MONEY.get(t["priority"], "none")
            if p is not None:
                p["_money"][money] += 1
            row = {
                "id": sid("task", "todoist", t["id"]), "title": title, "notes": t["description"] or None,
                "project_id": p["id"] if p else None, "deal_id": deal["id"] if deal else None, "owner_id": owner, "ball": ball,
                "person_id": person["id"] if person else None, "due_date": t["due"],
                # Ждём с тех пор, как задачу завели, а не с дня переноса.
                "waiting_since": (t["added"] or "")[:10] or None if ball == "waiting" else None,
                "estimate_min": 10 if "быстр" in labels else None, "_money": money,
                "labels": sorted(l for l in labels if l in labels_keep),
                "status": "done" if t.get("checked") else "open", "completed_at": t.get("completed"),
                "source": "import", "import_ref": f"todoist:{t['id']}", "created_by": owner, "created_at": t["added"],
            }
            plan.tasks.append(row)
            task_ids.add(t["id"])
            unknown = [l for l in labels if l not in label_people and l not in labels_keep and l not in {"жду", "повестка", "быстр", "личное"}]
            if unknown:
                plan.note("Задачи: метки без правила (пропущены)", f"{title}: {', '.join(unknown)}")
        for c in todoist.get("comments", []):
            if c.get("task_id") in task_ids and (c.get("content") or "").strip():
                plan.comments.append({"id": sid("comment", "todoist", c["id"]), "task_id": sid("task", "todoist", c["task_id"]),
                                      "author_id": owner, "text": c["content"], "created_at": c.get("posted_at"),
                                      "import_ref": f"todoist:{c['id']}"})
    plan.labels = sorted(labels_keep)

    # Деньги проекта: работающая сделка — оплата согласована; лиды — развитие;
    # иначе большинство приоритетов его задач. Задача отличается — своё поле.
    for p in plan.projects:
        stages = p.get("_stages", Counter())
        if p["kind"] != "client":
            p["money_default"], why = "none", "не клиент"
        elif stages["active"] or stages["mandate"] or stages["closing"]:
            p["money_default"], why = "paid", "есть сделка в работе"
        elif stages["lead"] or stages["proposal"]:
            p["money_default"], why = "potential", "только лиды и КП"
        elif p["_money"]:
            p["money_default"], why = p["_money"].most_common(1)[0][0], "по приоритетам задач"
        else:
            p["money_default"], why = "potential", "нет ни сделок, ни задач"
        plan.note("Проекты: деньги по умолчанию", f"{p['name']} — {p['money_default']} ({why})")
        if stages and not (stages.keys() - {"archive"}):
            p["archived"] = True
            plan.note("Проекты: в архив (все сделки в архиве)", p["name"])
    by_id = {p["id"]: p for p in plan.projects}
    for t in plan.tasks:
        m = t.pop("_money")
        pd = by_id[t["project_id"]]["money_default"] if t["project_id"] else "none"
        t["money"] = None if m == pd else m

    # Клиенты ленты: человек, проект или новое — в алиасы, чтобы часы сошлись.
    ledger_map = dec.get("ledger", {})
    for name, n, hours in clients or []:
        target = ledger_map.get(name)
        if target == "-":
            continue
        if target:
            hit = d.person(target) or d.project(target)
        else:
            hit = d.project(name) or d.person(name)
        if hit is None:
            plan.note("Лента: клиент без пары в справочнике", f"«{name}» — {n} записей, {hours} ч")
            continue
        if norm(name) not in {norm(hit["name"]), norm(hit.get("short"))} | {norm(a) for a in hit["aliases"]}:
            hit["aliases"].append(name)
            plan.note("Лента: написание стало алиасом", f"«{name}» → {hit['name']}")

    # Сделки без открытых задач, но со «следующим шагом» в Notion — повод для «Недели».
    open_deal_projects = {t["project_id"] for t in plan.tasks if t["status"] == "open"}
    for dl in plan.deals:
        if dl["stage"] in ("active", "mandate", "closing") and dl["project_id"] not in open_deal_projects:
            plan.note("Сделки в работе без единой открытой задачи", f"{dl['name']} — следующий шаг в Notion: «{(dl['next_step'] or '—')[:90]}»")
    return plan


# ── Отчёт ───────────────────────────────────────────────────────────────


def report(plan: Plan) -> str:
    c = Counter(t["status"] for t in plan.tasks)
    lines = [
        "# Перенос в Дела — сухой прогон",
        "",
        f"Пользователи: {len(plan.users)} · люди: {len(plan.people)} · организации: {len(plan.orgs)} · "
        f"проекты: {len(plan.projects)} · сделки: {len(plan.deals)} · задачи: {len(plan.tasks)} "
        f"(открытых {c['open']}) · комментарии: {len(plan.comments)} · взаимодействия: {len(plan.interactions)}",
        "",
        "## Проекты",
        "",
        "| проект | вид | деньги | сделок | задач | алиасы |",
        "|---|---|---|---|---|---|",
    ]
    per = Counter(t["project_id"] for t in plan.tasks)
    deals = Counter(dl["project_id"] for dl in plan.deals)
    for p in sorted(plan.projects, key=lambda p: (p["kind"], p["name"].lower())):
        mark = " (архив)" if p.get("archived") else ""
        lines.append(f"| {p['name']}{mark} | {p['kind']} | {p['money_default']} | {deals[p['id']]} | {per[p['id']]} | {', '.join(p['aliases'])} |")
    people = {p["id"]: p for p in plan.people}
    projects = {p["id"]: p for p in plan.projects}
    lines += ["", "## Задачи", "", "| задача | проект | мяч | человек | срок | деньги | метки |", "|---|---|---|---|---|---|---|"]
    for t in sorted(plan.tasks, key=lambda t: (projects[t["project_id"]]["name"] if t["project_id"] else "", t["title"])):
        pr = projects[t["project_id"]]["name"] if t["project_id"] else "Входящие"
        pe = people[t["person_id"]].get("short") or people[t["person_id"]]["name"] if t["person_id"] else ""
        money = t["money"] or "как у проекта"
        extra = ", ".join(t["labels"] + (["10 мин"] if t["estimate_min"] else []))
        lines.append(f"| {t['title']} | {pr} | {t['ball']} | {pe} | {t['due_date'] or ''} | {money} | {extra} |")
    for section, items in plan.notes.items():
        lines += ["", f"## {section}", ""] + [f"- {i}" for i in items]
    return "\n".join(lines) + "\n"


# ── Запись ──────────────────────────────────────────────────────────────

COLUMNS = {
    "crm.orgs": ["id", "name", "aliases", "kind", "owner_id"],
    "crm.people": ["id", "name", "short", "aliases", "org_id", "role", "phones", "emails", "telegram_username",
                   "birth_day", "birth_month", "birth_year", "source", "seeks", "offers", "traits", "cadence", "hub",
                   "user_id", "owner_id", "note", "notion_id"],
    "crm.projects": ["id", "name", "aliases", "sphere", "kind", "org_id", "owner_id", "money_default", "import_ref"],
    "crm.deals": ["id", "project_id", "name", "stage", "deal_type", "lead_person_id", "person_ids", "fee_kop", "deadline",
                  "wheel", "ball", "next_step", "my_view", "ideas", "log", "notion_id"],
    "tasks.tasks": ["id", "title", "notes", "project_id", "deal_id", "owner_id", "ball", "person_id", "waiting_since", "due_date",
                    "estimate_min", "money", "labels", "status", "completed_at", "source", "import_ref", "created_by", "created_at"],
    "tasks.comments": ["id", "task_id", "author_id", "text", "created_at", "import_ref"],
    "crm.interactions": ["id", "at", "kind", "summary", "person_ids", "source", "source_ref", "owner_id"],
}


def _insert_new(conn, table: str, row: dict) -> bool:
    """Строку — только если её ещё нет; есть — не трогать (правда о ней уже в Делах). True — записана."""
    cols = [c for c in COLUMNS[table] if c in row]
    vals = [Jsonb(row[c]) if isinstance(row[c], dict) else row[c] for c in cols]
    return conn.execute(
        f"INSERT INTO {table} ({', '.join(cols)}) VALUES ({', '.join(['%s'] * len(cols))}) "
        "ON CONFLICT (id) DO NOTHING RETURNING id",
        vals,
    ).fetchone() is not None


def apply(url: str, plan: Plan) -> dict:
    """План → база, только новое. Ответ — сколько строк записано впервые."""
    new = {"tasks": 0, "projects": 0, "people": 0, "deals": 0}
    with db.session(url, "system", "svc:import", via="import") as conn:
        for u in plan.users:
            # Имя и роль после установки меняет только `user` из командной строки; из плана — лишь недостающие
            # ключи настроек (свои у человека не перетираются).
            conn.execute(
                "INSERT INTO crm.users (id, name, role, settings) VALUES (%s, %s, %s, %s) "
                "ON CONFLICT (id) DO UPDATE SET settings = EXCLUDED.settings || crm.users.settings",
                (u["id"], u["name"], u["role"], Jsonb(u["settings"])),
            )
        for o in plan.orgs:
            _insert_new(conn, "crm.orgs", o)
        for p in plan.people:
            new["people"] += _insert_new(conn, "crm.people", {k: v for k, v in p.items() if not k.startswith("_")})
        for u in plan.users:
            if u["person_id"]:
                conn.execute("UPDATE crm.users SET person_id = %s WHERE id = %s AND person_id IS NULL", (u["person_id"], u["id"]))
        for p in plan.projects:
            if _insert_new(conn, "crm.projects", {k: v for k, v in p.items() if not k.startswith("_")}):
                new["projects"] += 1
                if p.get("archived"):
                    conn.execute("UPDATE crm.projects SET archived_at = now() WHERE id = %s", (p["id"],))
        for dl in plan.deals:
            new["deals"] += _insert_new(conn, "crm.deals", {k: v for k, v in dl.items() if not k.startswith("_")})
        for name in plan.labels:
            conn.execute("INSERT INTO tasks.labels (name) VALUES (%s) ON CONFLICT DO NOTHING", (name,))
        for t in plan.tasks:
            new["tasks"] += _insert_new(conn, "tasks.tasks", t)
        for c in plan.comments:
            _insert_new(conn, "tasks.comments", c)
        for i in plan.interactions:
            _insert_new(conn, "crm.interactions", i)
    return new


def run(cfg, args) -> int:
    dec = json.loads(Path(args.plan).read_text(encoding="utf-8"))
    todoist = load_todoist(Path(args.todoist)) if args.todoist else None
    deals = contacts = []
    if args.notion:
        folder = Path(args.notion)
        deals = load_notion(folder / "notion-deals.json") if (folder / "notion-deals.json").exists() else []
        contacts = load_notion(folder / "notion-contacts.json") if (folder / "notion-contacts.json").exists() else []
    clients = json.loads(Path(args.clients).read_text(encoding="utf-8"))["clients"] if args.clients else None
    plan = build(todoist, deals, contacts, clients, dec)
    text = report(plan)
    out = Path(args.report or "import-report.md")
    out.write_text(text, encoding="utf-8")
    print(f"отчёт: {out}")
    if args.apply:
        from . import bridge

        print("записано впервые (что уже было — не тронуто):", apply(cfg.db_url, plan))
        bridge.save_decisions(cfg.db_url, dec)
    else:
        print("сухой прогон: база не тронута (--apply — записать)")
    return 0


# ── Лог взаимодействий Notion (хронология CRM) ──────────────────────────

LOG_KINDS = {"Звонок": "call", "Встреча": "meeting", "Zoom": "zoom", "Telegram": "telegram", "Email": "email", "WhatsApp": "whatsapp"}
PLACEHOLDER = "Последний контакт (по Notion)"


def hex_id(s: str | None) -> str | None:
    """id страницы Notion — последние 32 шестнадцатеричных знака ссылки, в любом её виде."""
    h = re.sub(r"[^0-9a-f]", "", (s or "").lower())
    return h[-32:] if len(h) >= 32 else None


def log_at(row: dict) -> dt.datetime:
    """Дата записи: со временем — как есть, без времени — полдень по Москве (сутки не съедут)."""
    raw = row.get("date") or row.get("created")
    if row.get("is_datetime") and raw and "T" in raw:
        return dt.datetime.fromisoformat(raw.replace("Z", "+00:00"))
    day = dt.date.fromisoformat(raw[:10])
    created = dt.date.fromisoformat((row.get("created") or raw)[:10])
    if (created - day).days > 200:  # «28.04.2025» в записи, заведённой 28.04.2026, — опечатка в годе
        try:
            day = day.replace(year=created.year)
        except ValueError:
            pass
    return dt.datetime.combine(day, dt.time(12), tzinfo=dt.timezone(dt.timedelta(hours=3)))


def notion_log(url: str, path: Path, apply_: bool) -> str:
    """Хронология из Notion: строка лога — запись crm.interactions (source = notion).

    Контакт и сделка — по id страниц Notion, которые помнят люди и сделки
    справочника. Нет ни того, ни другого — клиент ищется по началу записи
    «Бета/Иван: …» через алиасы справочника (crm.match_name). Повтор безвреден:
    id записи выводится из id строки Notion. Заглушки «Последний контакт
    (по Notion)» из первого переноса убираются (deleted_at) у людей, у кого
    теперь есть настоящая запись не раньше.
    """
    rows = json.loads(path.read_text(encoding="utf-8"))["results"]
    stats: Counter = Counter()
    unmatched: list[str] = []
    with db.session(url, "system", "svc:import", via="import") as conn:
        owner = conn.execute("SELECT crm.owner_id() AS id").fetchone()["id"]
        people = {hex_id(r["notion_id"]): r["id"] for r in conn.execute("SELECT id, notion_id FROM crm.people WHERE notion_id IS NOT NULL")}
        deals = {hex_id(r["notion_id"]): (r["id"], r["project_id"])
                 for r in conn.execute("SELECT id, project_id, notion_id FROM crm.deals WHERE notion_id IS NOT NULL")}
        for r in rows:
            person_ids = [str(people[h]) for h in r.get("contacts") or [] if h in people]
            stats["контакт не найден"] += sum(1 for h in r.get("contacts") or [] if h not in people)
            deal_id = project_id = None
            for h in r.get("deals") or []:
                if h in deals:
                    deal_id, project_id = deals[h]
                    break
            else:
                if r.get("deals"):
                    stats["сделка не найдена"] += 1
            summary = (r.get("summary") or "").strip()
            if not project_id and ":" in summary[:80]:
                # «Альфа: …», «Бета/Иван: …», «Гамма / Ольга: …»
                for part in re.split(r"\s*/\s*", summary.split(":", 1)[0]):
                    m = conn.execute("SELECT * FROM crm.match_name(%s)", (part,)).fetchone()
                    if m["project_id"] and not project_id:
                        project_id = m["project_id"]
                        stats["клиент по началу записи"] += 1
                    if m["person_id"] and str(m["person_id"]) not in person_ids:
                        person_ids.append(str(m["person_id"]))
            if not project_id and not person_ids:
                unmatched.append(f"{r.get('date', '')[:10]} {summary[:90]}")
            stats["записей"] += 1
            if not apply_ or not summary:
                continue
            conn.execute(
                "INSERT INTO crm.interactions (id, at, kind, summary, next_step, project_id, deal_id, person_ids, source, source_ref, "
                "owner_id, notion_id) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, 'notion', %s, %s, %s) "
                "ON CONFLICT (id) DO UPDATE SET at = EXCLUDED.at, kind = EXCLUDED.kind, summary = EXCLUDED.summary, "
                "next_step = EXCLUDED.next_step, project_id = EXCLUDED.project_id, deal_id = EXCLUDED.deal_id, "
                "person_ids = EXCLUDED.person_ids, source_ref = EXCLUDED.source_ref",
                (sid("interaction", "notion-log", r["id"]), log_at(r), LOG_KINDS.get(r.get("kind") or "", "other"), summary,
                 (r.get("next") or "").strip() or None, project_id, deal_id, person_ids, r.get("src"), owner,
                 f"https://app.notion.com/p/{r['id']}"),
            )
        if apply_:
            stats["заглушек убрано"] = conn.execute(
                "UPDATE crm.interactions z SET deleted_at = now() WHERE z.summary = %s AND z.source = 'import' AND z.deleted_at IS NULL "
                "AND EXISTS (SELECT 1 FROM crm.interactions i WHERE i.source = 'notion' AND i.deleted_at IS NULL "
                "            AND i.person_ids && z.person_ids AND i.at >= z.at - interval '1 day')",
                (PLACEHOLDER,),
            ).rowcount
    out = [f"# Лог взаимодействий Notion → хронология CRM ({'записано' if apply_ else 'сухой прогон'})", ""]
    out += [f"- {k}: {v}" for k, v in stats.items()]
    if unmatched:
        out += ["", f"## Без клиента и человека ({len(unmatched)}) — остаются в общей хронологии владельца", ""]
        out += [f"- {u}" for u in unmatched]
    return "\n".join(out) + "\n"
