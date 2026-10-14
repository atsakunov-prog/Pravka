"""Дайджест Telegram — по карточкам CRM (14.10.2026, dela_0018): пункт сводки — запись в хронологии человека и клиента.

Владелец: «дайджесты Телеграма надо разносить по карточкам CRM». Сводку пишет служба дайджеста (Opus пачкой, auto.py
на компе) и кладёт в Дела операцией digest.put (digest.py). Здесь, в той же операции, сервер раскладывает её пункты по
карточкам: «Ждут тебя» и «По работе» — записью хронологии (crm.interactions: kind telegram, source digest) у человека,
у его клиента и сделки, как встречи и звонки. Claude тут не зовётся: кто это — то же узнавание, что у встреч и звонков
(people.who), по тому, что служба уже знает о чате.

Кого узнаём (детерминированно, бесплатно):
- личный чат — человек по id Telegram (crm.people.telegram_id), по @имени, иначе по названию чата: с 08.10.2026
  контакты Telegram названы как карточки Дел («Имя Фамилия (Компания)»), и узнавание берёт только уверенное (sure);
- группа — кто писал: по id отправителя (служба шлёт senders — имя → id), иначе по имени уверенно; клиент — по
  названию группы, если в нём целиком стоит имя ровно одного клиента («(Ромашка) модель» — Ромашка);
- «По работе» — тема по частям («Ромашка: модель», «Альфа / Бета»): часть — чат окна, имя клиента или человек.
Не узнали — пункт в карточки не идёт: новых людей и клиентов автоматика не заводит.

Кого не разносим — по слову владельца «семья и личные чаты — нет, только рабочие люди и клиенты»:
- семья: пользователи Дел с флагом family и список Свода people.family (им же звонки узнают «Семью» на телефоне —
  одна правда). Пока списка в Своде нет — ещё однофамильцы владельца: родители и дети в Telegram записаны полным
  именем, а флаг family есть не у всех (он — про карту «Где мы», а не про родство). Список в Своде есть — правда он:
  так владелец сам решает, кто семья. Пункт, где узнан кто-то из семьи, не идёт никуда, даже если в нём назван клиент;
- личные: человек без работы с владельцем — не команда (компания владельца), не человек клиента (компания с проектом
  клиента или участник сделки), не из банка, фонда или партнёра. Его пункт остаётся только в сводке;
- раздел «Дом» и «Прочее» не разносятся вовсе.

Приватность — правило 21: запись private (dela_0018) — команда не видит её ни в карточке клиента, ни в синке, ни в
журнале (его у приватной записи нет). Повтор той же сводки не плодит записей: ключ записи — сводка, пункт и клиент
(source_ref «<ref сводки>#<ключ>», уникальный индекс); пункт пропал из сводки — запись помечается убранной.
"""

from __future__ import annotations

import hashlib
import re
from typing import Any

from . import people

SECTIONS = ("waiting", "work")
PRIVATE_CHAT = "личный"
GROUP_CHAT = "группа"
WORK_ORG_KINDS = {"client", "partner", "fund", "bank"}
PROJECTS_PER_ITEM = 2      # «Альфа / Бета» — в обе карточки; больше — это уже обзор, а не пункт
SUMMARY_MAX = 700
SOURCES_MAX = 300          # чатов в окне — со всем запасом (служба режет окно до ~130 тыс. токенов)
SENDERS_MAX = 80
NAME_MAX = 200
COMPACT_MIN = 6            # имя клиента слитно внутри названия группы («ЯблокоСофт» в «Яблоко Софт_финансы») — от 6 букв
# Части темы «По работе»: «Ромашка: модель», «Альфа / Бета», «Вера · «Клуб на крыше»».
PARTS = re.compile(r"\s*(?:/|\||·|;|,|:|\s—\s|\s–\s|\s-\s|\+|&)\s*")


def _words(s: Any) -> list[str]:
    return people.words(s)


def _key(s: Any) -> str:
    """Название для сравнения: слова без знаков — «Ромашка.ПРО» и «ромашка про» одно."""
    return " ".join(_words(s))


# ── Чаты окна от службы ─────────────────────────────────────────────────


def norm_sources(v: Any, err) -> list[dict] | None:
    """Чаты окна (auto.py): название, личный или группа, id и кто писал. Чужие ключи не храним; None — служба не
    прислала (старая служба или сводка до 14.10.2026): тогда узнаём по названиям из самих пунктов."""
    if v is None:
        return None
    if not isinstance(v, list):
        raise err("дайджест: sources — список чатов")
    out = []
    for x in v[:SOURCES_MAX]:
        if not isinstance(x, dict):
            continue
        name = " ".join(str(x.get("name") or "").split())[:NAME_MAX]
        if not name:
            continue
        row: dict[str, Any] = {"name": name}
        kind = str(x.get("kind") or "").strip().lower()
        if kind in (PRIVATE_CHAT, GROUP_CHAT):
            row["kind"] = kind
        for k in ("tg_id", "peer_id"):
            n = _int(x.get(k))
            if n is not None:
                row[k] = n
        user = str(x.get("username") or "").strip().lstrip("@")[:64]
        if user:
            row["username"] = user
        senders = x.get("senders")
        if isinstance(senders, dict):
            got = {}
            for sname, sid in list(senders.items())[:SENDERS_MAX]:
                n = _int(sid)
                sname = " ".join(str(sname or "").split())[:NAME_MAX]
                if sname and n is not None:
                    got[sname] = n
            if got:
                row["senders"] = got
        out.append(row)
    return out


def _int(v: Any) -> int | None:
    if isinstance(v, bool) or v in (None, ""):
        return None
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


# ── Справочник одним чтением ────────────────────────────────────────────


def load(conn, owner: str) -> dict:
    """Всё, что нужно разносу, — одним заходом под RLS владельца: люди для who, компании, живые клиенты, сделки,
    семья. Разносу сводки из 20 пунктов не нужно 60 раз читать всех людей."""
    me = conn.execute("SELECT person_id FROM crm.users WHERE id = %s", (owner,)).fetchone()
    family = [r["person_id"] for r in conn.execute(
        "SELECT person_id FROM crm.users WHERE family AND person_id IS NOT NULL").fetchall()]
    sv = conn.execute("SELECT value FROM crm.svod WHERE owner_id = %s AND key = 'people.family'", (owner,)).fetchone()
    listed = sv["value"] if sv and isinstance(sv["value"], list) else None  # «Семья» звонков (docs/svod-phone.md, 3.5)
    return build(
        rows=people.who_rows(conn),
        orgs=conn.execute("SELECT id, name, aliases, kind FROM crm.orgs WHERE archived_at IS NULL").fetchall(),
        projects=conn.execute(
            "SELECT id, name, aliases, org_id, archived_at FROM crm.projects WHERE kind = 'client'").fetchall(),
        deals=conn.execute(
            "SELECT id, project_id, stage, lead_person_id, team_ids, person_ids, source_person_id FROM crm.deals").fetchall(),
        me_person=me["person_id"] if me else None,
        family_ids=family,
        family_listed=listed,
    )


def build(rows: list[dict], orgs: list[dict], projects: list[dict], deals: list[dict], me_person: Any,
          family_ids: list[Any], family_listed: list[Any] | None = None) -> dict:
    """Справочник разноса из строк — общий для сервера (load) и прогона вхолостую по снимку синка.

    family_ids — пользователи Дел с флагом family; family_listed — список Свода people.family (None — его нет: тогда
    семья — ещё и однофамильцы владельца)."""
    sid = lambda v: str(v) if v not in (None, "") else None  # noqa: E731 — uuid из базы и строка из JSON — одно
    people_by = {sid(p["id"]): p for p in rows}
    me = sid(me_person)
    me_org = sid((people_by.get(me) or {}).get("org_id"))
    surname = _surname((people_by.get(me) or {}).get("name"))
    family = {sid(x) for x in [*family_ids, *(family_listed or [])] if sid(x)}
    if surname and family_listed is None:
        # И пользователи Дел: флаг family — про карту «Где мы», а не про родство (родственник в команде карту не делит).
        # Однофамилец из команды не по родству — так и скажет список people.family в Своде: он правда, когда есть.
        family |= {pid for pid, p in people_by.items() if pid != me and _has_surname(p, surname)}
    family.discard(me)
    clients = [p for p in projects if not p.get("archived_at")]
    client_orgs = {sid(p["org_id"]) for p in projects if p.get("org_id")}
    work_orgs = {sid(o["id"]) for o in orgs if o.get("kind") in WORK_ORG_KINDS}
    in_deals: set[str] = set()
    for d in deals:
        in_deals |= {sid(x) for x in [d.get("lead_person_id"), d.get("source_person_id"),
                                      *(d.get("team_ids") or []), *(d.get("person_ids") or [])] if sid(x)}
    by_tg = _unique((str(p["telegram_id"]), pid) for pid, p in people_by.items() if p.get("telegram_id"))
    by_user = _unique((people.norm(p["telegram_username"]).lstrip("@"), pid) for pid, p in people_by.items()
                      if p.get("telegram_username"))
    org_names = {sid(o["id"]): [o.get("name"), *(o.get("aliases") or [])] for o in orgs}
    return {
        "rows": rows, "people": people_by, "me": me, "me_org": me_org, "family": family,
        "clients": [{"id": sid(p["id"]), "org_id": sid(p.get("org_id")),
                     "keys": {k for k in (_key(n) for n in [p["name"], *(p.get("aliases") or []),
                                                            *org_names.get(sid(p.get("org_id")), [])]) if k}}
                    for p in clients],
        "client_orgs": client_orgs, "work_orgs": work_orgs, "in_deals": in_deals,
        # Живые сделки: people — все, кто на сделке (по ним — сделка записи), side — только люди клиента (по ним —
        # клиент человека): команда ЗФ стоит на десятке сделок, а кто привёл — посредник, и его переписка о своём
        # клиенте не должна ложиться в карточку чужого.
        "deals": [{"id": sid(d["id"]), "project_id": sid(d["project_id"]),
                   "people": {sid(x) for x in [d.get("lead_person_id"), d.get("source_person_id"),
                                               *(d.get("team_ids") or []), *(d.get("person_ids") or [])] if sid(x)},
                   "side": {sid(x) for x in d.get("person_ids") or [] if sid(x)}}
                  for d in deals if d.get("stage") != "archive"],
        "by_tg": by_tg, "by_user": by_user,
    }


def _unique(pairs) -> dict:
    """Ключ → человек, только если ключ у одного: общий id или ник у двух карточек — не ключ (дубль не слит)."""
    seen: dict[str, Any] = {}
    for k, pid in pairs:
        if not k:
            continue
        seen[k] = None if k in seen and seen[k] != pid else pid
    return {k: v for k, v in seen.items() if v}


def _surname(name: Any) -> str | None:
    """Фамилия из карточки владельца: «Иван Петров (Ромашка)» → «петров». Нет её (одно имя) — правила однофамильцев нет."""
    q, _ = people.split_hint(name or "")
    ws = [w for w in _words(q) if len(w) >= 3 and not people.is_first(w)]
    return ws[-1] if ws else None


def _has_surname(p: dict, surname: str) -> bool:
    """Однофамилец: «Петров», «Петрова», «Петровы» — одна основа (people.same)."""
    q, _ = people.split_hint(p.get("name") or "")
    return any(people.same(w, surname) for w in _words(q) if len(w) >= 3 and not people.is_first(w))


# ── Кто и какой клиент ──────────────────────────────────────────────────


def _find_source(sources: list[dict], name: str) -> dict | None:
    k = _key(name)
    if not k:
        return None
    for s in sources:
        if _key(s["name"]) == k:
            return s
    return None


def _by_name(ctx: dict, name: str) -> str | None:
    """Человек по имени — только уверенно (people.who sure): «Наташа» при двух Натальях — никто."""
    if not _key(name):
        return None
    got = people.who(None, q=name, rows=ctx["rows"])
    return str(got["best"]["id"]) if got["sure"] and got["best"] else None


def _private_person(ctx: dict, src: dict) -> str | None:
    """Собеседник личного чата: id Telegram, @имя, потом название чата (контакт назван как карточка)."""
    for k in ("peer_id", "tg_id"):
        if src.get(k) is not None and ctx["by_tg"].get(str(src[k])):
            return ctx["by_tg"][str(src[k])]
    if src.get("username") and ctx["by_user"].get(people.norm(src["username"])):
        return ctx["by_user"][people.norm(src["username"])]
    return _by_name(ctx, src["name"])


def _sender(ctx: dict, src: dict | None, who: str) -> str | None:
    """Кто писал в группе: id отправителя из senders службы, иначе имя уверенно."""
    if src and who:
        k = _key(who)
        for sname, sid_ in (src.get("senders") or {}).items():
            if _key(sname) == k and ctx["by_tg"].get(str(sid_)):
                return ctx["by_tg"][str(sid_)]
    return _by_name(ctx, who)


def _clients_named(ctx: dict, text: str, whole: bool) -> list[str]:
    """Клиенты, названные в тексте. whole — текст и есть имя («Ромашка»); иначе имя целиком стоит в названии группы
    («(Ромашка) модель и затраты»): словами подряд или слитно от COMPACT_MIN букв. Два клиента в одном названии —
    оба; больше — не угадываем."""
    k = _key(text)
    if not k:
        return []
    out = []
    for c in ctx["clients"]:
        if whole:
            hit = k in c["keys"]
        else:
            padded, compact = f" {k} ", k.replace(" ", "")
            hit = any(f" {n} " in padded or (len(n.replace(" ", "")) >= COMPACT_MIN and n.replace(" ", "") in compact)
                      for n in c["keys"])
        if hit and c["id"] not in out:
            out.append(c["id"])
    return out if len(out) <= PROJECTS_PER_ITEM else []


def is_work(ctx: dict, pid: str) -> bool:
    """Рабочий человек: команда (компания владельца), человек клиента или сделки, банк, фонд, партнёр."""
    if pid == ctx["me"] or pid in ctx["family"]:
        return False
    p = ctx["people"].get(pid) or {}
    org = str(p["org_id"]) if p.get("org_id") else None
    return bool(pid in ctx["in_deals"] or p.get("user_id")
                or (org and (org == ctx["me_org"] or org in ctx["client_orgs"] or org in ctx["work_orgs"])))


def _person_clients(ctx: dict, pid: str) -> list[str]:
    """Клиент человека: живой проект его компании; иначе клиенты живых сделок, где он человек клиента."""
    p = ctx["people"].get(pid) or {}
    org = str(p["org_id"]) if p.get("org_id") else None
    out = [c["id"] for c in ctx["clients"] if org and c["org_id"] == org and org != ctx["me_org"]]
    if not out:
        for d in ctx["deals"]:
            if pid in d["side"] and d["project_id"] not in out and any(c["id"] == d["project_id"] for c in ctx["clients"]):
                out.append(d["project_id"])
    return out if len(out) <= PROJECTS_PER_ITEM else []


def _deal(ctx: dict, project: str, persons: list[str]) -> str | None:
    """Сделка записи — живая сделка клиента, где есть кто-то из узнанных; ровно одна, иначе — у клиента."""
    hits = [d["id"] for d in ctx["deals"] if d["project_id"] == project and d["people"] & set(persons)]
    return hits[0] if len(hits) == 1 else None


def resolve(ctx: dict, section: str, item: dict, sources: list[dict]) -> tuple[list[str], list[str], str]:
    """Пункт → (люди, клиенты, почему пусто). Узнан кто-то из семьи — пусто целиком («family")."""
    found: list[str] = []
    clients: list[str] = []
    if section == "waiting":
        chat, who = item.get("chat") or "", item.get("who") or ""
        src = _find_source(sources, chat) if chat else None
        if src and src.get("kind") == PRIVATE_CHAT:
            found.append(_private_person(ctx, src))
        elif src and src.get("kind") == GROUP_CHAT:
            found.append(_sender(ctx, src, who))
            clients += _clients_named(ctx, src["name"], whole=False)
        else:
            # Чата нет в списке (старая служба): сначала само название — у личного чата это человек, потом кто.
            found.append(_by_name(ctx, chat) or _sender(ctx, None, who))
            clients += _clients_named(ctx, chat, whole=True)
    else:
        topic = item.get("topic") or ""
        parts = [p for p in [topic, *PARTS.split(topic)] if _key(p)]
        for part in dict.fromkeys(parts):
            src = _find_source(sources, part)
            if src and src.get("kind") == PRIVATE_CHAT:
                found.append(_private_person(ctx, src))
            elif src:
                clients += _clients_named(ctx, src["name"], whole=False)
            else:
                named = _clients_named(ctx, part, whole=True)
                clients += named
                if not named:
                    found.append(_by_name(ctx, part))
    found = list(dict.fromkeys(p for p in found if p and p != ctx["me"]))
    if any(p in ctx["family"] for p in found):
        return [], [], "family"
    work = [p for p in found if is_work(ctx, p)]
    for p in work:
        clients += _person_clients(ctx, p)
    clients = list(dict.fromkeys(clients))[:PROJECTS_PER_ITEM]
    if not work and not clients:
        return [], [], "personal" if found else "unknown"
    return work, clients, ""


# ── Записи хронологии ───────────────────────────────────────────────────


def _line(label: str, section: str, item: dict) -> str:
    """«Telegram, <окно>: <что>» — так владелец назвал запись; кто и из какого чата — в самой фразе. Последнее «: » в
    начале — граница темы и итога у веба и телефона (tlLine, DelaCrm.meetingParts): жирным встанет «Telegram, окно:
    ждёт тебя — Кто (чат)» или «… : Тема», а сама фраза пункта — текстом под ней."""
    from .digest import where_of  # здесь, а не наверху: digest зовёт этот модуль

    if section == "waiting":
        what = item.get("what") or ""
        body = f"ждёт тебя — {where_of(item)}" + (f": {what}" if what else "")
    else:
        topic, text = item.get("topic") or "", item.get("text") or ""
        body = f"{topic}: {text}" if topic and text else topic or text
    s = f"Telegram, {label}: {body}"
    return s if len(s) <= SUMMARY_MAX else s[: SUMMARY_MAX - 1].rstrip() + "…"


def plan(ctx: dict, digest: dict, summary: dict, sources: list[dict] | None) -> dict:
    """Что положить в хронологию по сводке: записи и счёт пропущенного (семья, личные, не узнанные)."""
    rows: list[dict] = []
    skipped = {"family": 0, "personal": 0, "unknown": 0}
    for section in SECTIONS:
        for item in summary.get(section) or []:
            persons, clients, why = resolve(ctx, section, item, sources or [])
            if why:
                skipped[why] += 1
                continue
            text = _line(digest["label"], section, item)
            # Ключ — сам пункт: где, кто и что (два человека с одной и той же просьбой — два пункта).
            body = people.norm(f"{item.get('chat')}|{item.get('who')}|{item.get('what')}" if section == "waiting"
                               else f"{item.get('topic')}|{item.get('text')}")
            for i, project in enumerate(clients or [None]):
                key = hashlib.md5(f"{section}|{body}|{project or '-'}".encode()).hexdigest()[:12]
                # Люди — у первой записи: пункт про двух клиентов не должен стоять у человека дважды.
                who = persons if i == 0 else []
                rows.append({
                    "ref": f"{digest['ref']}#{section[:4]}-{key}", "section": section, "summary": text,
                    "person_ids": who, "project_id": project, "deal_id": _deal(ctx, project, persons) if project else None,
                })
    return {"rows": rows, "skipped": skipped}


def apply(conn, owner: str, digest: dict, planned: dict) -> dict:
    """Записи плана — в хронологию (повтор находит свою по ключу), записи этой сводки, которых в плане больше нет, —
    убранными. Убранное владельцем не возвращается: повтор правит только живую запись."""
    made, kept = 0, []
    for r in planned["rows"]:
        got = conn.execute(
            "INSERT INTO crm.interactions (at, kind, summary, project_id, deal_id, person_ids, source, source_ref, "
            "owner_id, private) VALUES (%s, 'telegram', %s, %s, %s, %s::uuid[], 'digest', %s, %s, true) "
            "ON CONFLICT (owner_id, source_ref) WHERE source = 'digest' DO UPDATE SET at = EXCLUDED.at, "
            "summary = EXCLUDED.summary, deal_id = EXCLUDED.deal_id, person_ids = EXCLUDED.person_ids "
            "WHERE crm.interactions.deleted_at IS NULL RETURNING (xmax = 0) AS new",
            (digest["ended_at"], r["summary"], r["project_id"], r["deal_id"], r["person_ids"], r["ref"], owner),
        ).fetchone()
        made += bool(got and got["new"])
        kept.append(r["ref"])
    gone = conn.execute(
        "UPDATE crm.interactions SET deleted_at = now() WHERE owner_id = %s AND source = 'digest' "
        "AND split_part(source_ref, '#', 1) = %s AND deleted_at IS NULL AND NOT (source_ref = ANY (%s)) RETURNING id",
        (owner, digest["ref"], kept)).fetchall()
    rows = planned["rows"]
    return {
        "linked": len(rows), "new": made, "retired": len(gone),
        "people": len({p for r in rows for p in r["person_ids"]}),
        "clients": len({r["project_id"] for r in rows if r["project_id"]}),
        "skipped": planned["skipped"],
    }


def link(conn, owner: str, digest: dict, summary: dict, sources: list[dict] | None) -> dict:
    """Разнести сводку по карточкам: справочник, план, запись. digest — {ref, label, ended_at}."""
    return apply(conn, owner, digest, plan(load(conn, owner), digest, summary, sources))
