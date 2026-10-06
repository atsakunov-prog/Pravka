"""Правка дел словами: микрофон у дела и строка Claude наверху веба — Claude Sonnet 5.5.

Владелец разбирает дела и говорит, что с ними сделать: «перенеси на пятницу», «это Наташе,
жду до среды», «все просроченные — на завтра», «бюджет — первым делом, сегодня». Claude видит
то же, что человек на этой странице (дела с номерами), справочник проектов, сделок и людей и
календарь на две недели — и возвращает правки. Сервер их проверяет (только дела со страницы,
имена — по справочнику) и сразу применяет; ответ — что поменялось и как вернуть одним движением.

Команда целиком про новые дела («завтра позвонить Ивану, Наташе сверку к пятнице») уходит в
разбор parse.py — тот же промпт, что у телефона; короткое новое дело внутри правки Claude заводит сам.

В «Новом» Claude видит и предложения автоматики на экране — П1, П2… по порядку сверху, как их
нумерует веб: «первое прими, срок пятница, второе не надо». Решения идут операцией
suggestion.decide (принять с правками или отклонить с причиной), как у кнопок; для принятого —
«как было», чтобы «Вернуть всё» отменило заведённое и вернуло поправленное. Отклонённое не
возвращается: операции «вернуть в «Новое»» нет, причина отказа — урок автоматике.

Модель и глубина — в «Настройках» веба, у каждого свои (crm.users.settings: claude_model —
sonnet или opus, claude_effort — low, medium, high). По умолчанию Sonnet 5.5 и low — решение
владельца (05.10.2026): правка короткая, ждать её не хочется. Справочник — в кэше (одинаков между
командами одного человека и одной модели), дела страницы и команда — после него.
Траты — в crm.state 'llm_cost' (llm.py).

Напоминания в Telegram (06.10.2026): «напомни в 11», «напомни, когда приеду домой», «через час ещё
раз» — поля remind_at и remind_place. Правила — раздел «НАПОМИНАНИЕ» общего промпта разбора
(server/contract/prompts/raznoska.txt): один текст на телефон, звёздочку и правку словами.

Карточки (06.10.2026). Владелец: «в любой карточке клиента или человека сверху должен быть текстбокс
Клода, который видит именно эти дела… ему можно надиктовать, и он может исправить всё, что нужно в
этой карточке, и это конечно же должен делать Опус». На странице клиента, сделки или человека
(scope.card) Claude видит и саму карточку — сделки, людей, последние записи хронологии — и кроме дел
правит её: пишет в хронологию (notes), людей — откуда, должность, убрать из клиента (people), сделки —
стадия, итог, вероятность (deals). Модель в карточке — всегда Opus 5.5 (CARD_MODEL), глубина — из
«Настроек». «Сейчас» — не больше пяти дел (NOW_MAX): лишнее Claude не ставит, а говорит почему.
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import re
import uuid

from . import db, llm, parse, people, remind, store

log = logging.getLogger("dela.ask")

MODEL = "claude-sonnet-5-5"    # по умолчанию; в настройках — llm.MODELS
CARD_MODEL = "claude-opus-5-5"  # карточка клиента, сделки, человека — решение владельца 06.10.2026
NOW_MAX = 5                     # «Сейчас» — дела, которые обязан сделать сегодня: не больше пяти
TIMELINE_SHOWN = 12             # записей хронологии в карточке для Claude
EFFORT = "low"                 # правка короткая; ошибается — «Вдумчиво» в настройках
MAX_INPUT = 4000
MAX_TASKS = 300
MAX_SUGS = 150
NOTE_CHARS = 100
WD = parse.WD
WD_SHORT = ["пн", "вт", "ср", "чт", "пт", "сб", "вс"]
BALL_WORD = {"waiting": "жду от", "agenda": "повестка с"}
# Что «Вернуть» ставит обратно делу, которое поправило принятое предложение (статус — отдельно).
UNDO_FIELDS = sorted(store.TASK_FIELDS - {"status", "source", "source_ref"})

SYSTEM = """Ты правишь дела Саши в его сервисе «Дела» по его команде. Команду он чаще надиктовывает:
пунктуации может не быть, имена и слова бывают услышаны неверно — сначала пойми смысл.

НА ВХОДЕ
— СПРАВОЧНИК ниже: проекты, сделки, люди, метки.
— В сообщении: сегодняшняя дата и календарь на две недели; где Саша сейчас (страница); дела на
  экране с номерами; если команда про одно дело — оно названо отдельно («ДЕЛО»); в «Новом» —
  предложения автоматики на экране (НОВОЕ: П1, П2 … сверху вниз, по пачкам — встреча или окно
  Telegram; завести, закрыть или уточнить дело); сама команда.

ЧТО ВЕРНУТЬ
route — "edit", если команда про дела или предложения на экране (поправить, перенести, закрыть,
  отменить, отдать другому, дописать, принять, отклонить). "new", если вся команда — только новые
  дела или надиктовка о новом без ссылки на то, что на экране: тогда changes, create и suggestions
  пустые, её разберёт другой разбор.
suggestions — решения по предложениям из НОВОГО, только П-номера из списка.
  n — номер П; decision — "accept" (принять, можно с правками) или "reject" (не нужно, не дело);
  reason — для reject коротко почему, словами Саши («делает Наташа», «уже сделано»); для accept — "".
  Правки при accept — поля как у changes (title, due, now, project, person, ball, notes_add); что
  Саша не менял — пусто. Принять «закрыть» — дело закроется; «уточнить» — поправится, как предложено.
changes — правки дел на экране, по одной записи на дело. Поле, которое Саша не менял, — пусто:
  "" у строк, 0 у чисел, [] у списков. «-» в строке — очистить поле.
  num — номер дела из списка (только из списка на экране).
  due — срок ГГГГ-ММ-ДД по календарю или «-». «Завтра», «в пятницу», «через неделю», «на
    следующей неделе» (её понедельник) — считай по календарю. due_time — ЧЧ:ММ, только если названо.
  now — "on": в «Сейчас», фокус на сегодня; "off" — убрать. «Первым делом», «в приоритет»,
    «срочно», «главное на сегодня» — now "on" и срок сегодня: других приоритетов в Делах нет.
  project — ровно имя проекта из справочника; «-» — во «Входящие». deal — имя сделки, если названа.
  person и ball — кто держит мяч. mine — делает Саша; waiting — ждём от человека (он делает,
    «ответственный», «это за ним», «жду от»); agenda — обсудить с человеком при встрече. waiting
    и agenda — только с человеком: person — короткое имя из справочника. Мяч ушёл к другому —
    поправь и title: «Человек: действие». person «-» — убрать человека.
  title — новая формулировка, только если Саша её меняет или мяч ушёл к другому. Формат
    «Кто: действие»; мяч у Саши — без префикса, сразу с действия.
  notes_add — что дописать к заметке дела: подробности, цифры, условия — коротко, его словами.
  estimate_min — оценка в минутах; -1 — убрать оценку.
  labels_add, labels_remove — метки только из справочника.
  status — "done": сделано; "cancelled": не нужно, отменить; "open": вернуть в работу.
  remind_at — когда напомнить Саше в Telegram, «ГГГГ-ММ-ДД ЧЧ:ММ» по местному времени; «-» —
    убрать напоминание. remind_place — напомнить по приезду: ровно имя из МЕСТ ниже; «-» — убрать.
    Одно из двух: новое время снимает место, новое место — время. Как считать время — раздел
    НАПОМИНАНИЕ ниже; «ещё раз через час» — от времени «сейчас» в сообщении.
create — новые дела, только если Саша прямо просит завести их внутри правки. Поля — как у changes
  (title обязателен; due, now, project, person, ball, notes_add — заметка нового дела).
reply — одна-две фразы Саше: что сделал. Чего не понял или не нашёл — скажи прямо. Без вступлений.

КАРТОЧКА — если Саша на странице клиента, сделки или человека, в сообщении есть раздел КАРТОЧКА:
кто это, сделки, люди, последние записи хронологии. «Он», «они», «по ним», «этот проект» — про неё.
card — правки самой карточки, по записи на правку; do — что это. Поля, которые правке не нужны, — пусто
  ("" у строк, 0 у чисел, false, []). Вне карточки do бывает только "person".
  do "note" — записать в хронологию: что было (звонок, встреча, переписка), о чём договорились, факт
    или новость. Это не дела: что сделать — в changes и create, что уже произошло — сюда. text — суть
    коротко, словами Саши; kind — call, meeting, telegram, email или note; date — ГГГГ-ММ-ДД, если
    сказано когда, иначе пусто (сегодня); name — сделка, если запись про неё; people — короткие имена.
  do "person" — правка человека. name — имя из ЛЮДЕЙ; org — откуда он: ровно имя клиента из ПРОЕКТОВ
    (· клиент) или компании из КОМПАНИЙ, «-» — ни откуда; role — должность («CFO»), «-» — стереть;
    remove — true, если Саша говорит убрать человека из этого клиента или сделки («он не отсюда»);
    new — true, только если Саша просит завести человека, которого в справочнике нет: name — как
    назван, полностью.
  do "deal" — правка сделки (у клиента их несколько — это его проекты). name — ровно имя из СДЕЛОК,
    "" — сделка этой карточки; stage — lead (лид), proposal (КП), mandate (мандат, подписали), active
    (в работе), closing (закрытие) или итог: won (сделали), lost (проиграли, «этого уже нет»), paused
    (заморозили); text — почему итог, словами Саши; probability — вероятность, %, 0 — не менять; date —
    ГГГГ-ММ-ДД, когда ждём решения, «-» — убрать; new — true, если Саша просит завести новую
    сделку: name — «Клиент: тема», клиент — из карточки.
«Сейчас» (now "on") — не больше пяти дел на сегодня: их Саша обязан сделать. Уже пять — скажи в reply.

ПРАВИЛА
— Меняй только то, что сказано. Не выдумывай сроки, людей и проекты.
— «Все», «эти», «просроченные», «по Ивану», «клиентские» — про дела на экране: выбери их сам по
  признакам из списка (срок, мяч, человек, проект).
— ДЕЛО, если оно названо, — главный адресат: «его», «это», «перенеси» — про него.
— «Первое прими», «всё прими», «остальное отклони», «про Ивана», «всё со встречи с Альфой» — про
  предложения НОВОГО по порядку (П1 — первое сверху) и признакам. О каком предложении команда
  молчит, то не трогай: оно останется в «Новом». Предложение решай через suggestions, а не правкой
  его дела в changes.
— Сомневаешься, о каком деле, предложении, человеке или проекте речь, — не меняй, а спроси в reply.

{REMIND}

{CATALOG}

{PLACES}"""

_TEXT_FIELDS = ["title", "notes_add", "project", "deal", "person", "due", "due_time", "remind_at", "remind_place"]
_ITEM = {
    "type": "object",
    "properties": {
        "num": {"type": "integer"},
        **{k: {"type": "string"} for k in _TEXT_FIELDS},
        "ball": {"type": "string", "enum": ["", "mine", "waiting", "agenda"]},
        "now": {"type": "string", "enum": ["", "on", "off"]},
        "estimate_min": {"type": "integer"},
        "labels_add": {"type": "array", "items": {"type": "string"}},
        "labels_remove": {"type": "array", "items": {"type": "string"}},
        "status": {"type": "string", "enum": ["", "done", "cancelled", "open"]},
    },
    "required": ["num", *_TEXT_FIELDS, "ball", "now", "estimate_min", "labels_add", "labels_remove", "status"],
    "additionalProperties": False,
}
# Решение по предложению «Нового»: правки — те же поля, что у changes, только нужные при принятии.
_SUG_TEXT = ["title", "notes_add", "project", "person", "due"]
_SUG = {
    "type": "object",
    "properties": {
        "n": {"type": "integer"},
        "decision": {"type": "string", "enum": ["accept", "reject"]},
        "reason": {"type": "string"},
        **{k: {"type": "string"} for k in _SUG_TEXT},
        "ball": _ITEM["properties"]["ball"],
        "now": _ITEM["properties"]["now"],
    },
    "required": ["n", "decision", "reason", *_SUG_TEXT, "ball", "now"],
    "additionalProperties": False,
}
# Новые дела внутри правки — только нужные поля: каждое поле схемы растит грамматику ответа, а у неё
# предел (06.10.2026: с карточкой полный набор полей у create не прошёл — «compiled grammar is too large»).
_CREATE = {
    "type": "object",
    "properties": {**{k: {"type": "string"} for k in ["title", "notes_add", "project", "person", "due"]},
                   "ball": _ITEM["properties"]["ball"], "now": _ITEM["properties"]["now"]},
    "required": ["title", "notes_add", "project", "person", "due", "ball", "now"],
    "additionalProperties": False,
}
# Карточка (06.10.2026): хронология, люди, сделки — одним массивом и без перечислений, по той же причине.
_CARD = {
    "type": "object",
    "properties": {
        "do": {"type": "string", "enum": ["note", "person", "deal"]},
        **{k: {"type": "string"} for k in ["name", "text", "kind", "date", "org", "role", "stage"]},
        "probability": {"type": "integer"},
        "remove": {"type": "boolean"},
        "new": {"type": "boolean"},
        "people": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["do", "name", "text", "kind", "date", "org", "role", "stage", "probability", "remove", "new", "people"],
    "additionalProperties": False,
}
# Одна схема на все страницы: кэш привязан к схеме ответа, и с разными он бы не делился.
SCHEMA = {
    "type": "object",
    "properties": {
        "route": {"type": "string", "enum": ["edit", "new"]},
        "changes": {"type": "array", "items": _ITEM},
        "create": {"type": "array", "items": _CREATE},
        "suggestions": {"type": "array", "items": _SUG},
        "card": {"type": "array", "items": _CARD},
        "reply": {"type": "string"},
    },
    "required": ["route", "changes", "create", "suggestions", "card", "reply"],
    "additionalProperties": False,
}
NOTE_KINDS = {"call", "meeting", "zoom", "telegram", "email", "whatsapp", "note", "other"}
OPEN_STAGES = {"lead", "proposal", "mandate", "active", "closing"}
OUTCOMES = {"won", "lost", "paused"}


def split_card(items: list[dict] | None) -> dict:
    """card из ответа Claude → notes, people, deals в форме card_ops. Строки без перечислений в схеме —
    поэтому вид записи, стадию и итог проверяем здесь: чужое слово — пусто."""
    out: dict = {"notes": [], "people": [], "deals": []}
    for x in items or []:
        g = lambda k: (x.get(k) or "").strip()  # noqa: E731
        if x.get("do") == "note":
            out["notes"].append({"text": g("text"), "kind": g("kind") if g("kind") in NOTE_KINDS else "note", "date": g("date"),
                                 "deal": g("name"), "people": x.get("people") or []})
        elif x.get("do") == "person":
            out["people"].append({"person": g("name"), "org": g("org"), "role": g("role"), "remove": bool(x.get("remove")),
                                  "new": bool(x.get("new"))})
        elif x.get("do") == "deal":
            st = g("stage")
            out["deals"].append({"deal": g("name"), "stage": st if st in OPEN_STAGES else "", "outcome": st if st in OUTCOMES else "",
                                 "reason": g("text"), "probability": int(x.get("probability") or 0), "expected_on": g("date"),
                                 "name": "", "new": bool(x.get("new"))})
    return out

class AskError(Exception):
    """Команда не сложилась — словами для человека."""


REMIND_HEAD, REMIND_END = "НАПОМИНАНИЕ (remind_at, remind_place)", "ЗАМЕТКИ К ДЕЛУ"


def remind_rules() -> str:
    """Раздел «НАПОМИНАНИЕ» общего промпта разбора — те же слова, что у телефона и звёздочки.
    Заголовки переименовали — пусто (поля описаны и выше), а тест test_remind_rules_cut это поймает."""
    text = parse.template()
    a, b = text.find(REMIND_HEAD), text.find(REMIND_END)
    return text[a:b].strip() if 0 <= a < b else ""


def _catalog(conn) -> tuple[str, dict]:
    """Справочник разбора (проекты, люди, метки) и живые сделки — текст и указатели имя → id."""
    text, index = parse.catalog(conn)
    deals = conn.execute(
        "SELECT d.id, d.name, d.project_id, p.name AS project FROM crm.deals d JOIN crm.projects p ON p.id = d.project_id "
        "WHERE d.stage <> 'archive' AND p.archived_at IS NULL ORDER BY p.name, d.name"
    ).fetchall()
    if deals:
        text += "\n\nСДЕЛКИ (deal — ровно имя из списка; после «·» — проект):\n" + "\n".join(
            f"— {d['name']} · {d['project']}" for d in deals)
    index["deals"] = {}
    for d in deals:
        index["deals"].setdefault(parse._norm(d["name"]), []).append((d["id"], d["project_id"]))
    # Откуда человек: клиент (его организация) или компания без проекта — банк, фонд, партнёр.
    orgs = conn.execute(
        "SELECT o.id, o.name, o.aliases, bool_or(p.id IS NOT NULL) AS client FROM crm.orgs o "
        "LEFT JOIN crm.projects p ON p.org_id = o.id AND p.archived_at IS NULL "
        "WHERE o.archived_at IS NULL GROUP BY o.id ORDER BY o.name"
    ).fetchall()
    free = [o for o in orgs if not o["client"]]
    if free:
        text += "\n\nКОМПАНИИ (org у человека — ровно имя; это не клиенты: банки, фонды, партнёры):\n" + "\n".join(
            f"— {o['name']}" + (f" ({', '.join(o['aliases'])})" if o["aliases"] else "") for o in free)
    index["orgs"] = {}
    for o in orgs:
        for n in [o["name"], *(o["aliases"] or [])]:
            index["orgs"].setdefault(parse._norm(n), []).append(o["id"])
    for pr in conn.execute("SELECT name, aliases, org_id FROM crm.projects WHERE kind = 'client' AND org_id IS NOT NULL "
                           "AND archived_at IS NULL").fetchall():
        for n in [pr["name"], *(pr["aliases"] or [])]:
            index["orgs"].setdefault(parse._norm(n), []).append(pr["org_id"])
    return text, index


STAGE_WORD = {"lead": "лид", "proposal": "КП", "mandate": "мандат", "active": "в работе", "closing": "закрытие", "archive": "архив"}
OUTCOME_WORD = {"won": "сделали", "lost": "проиграли", "paused": "заморожено"}
KIND_WORD = {"call": "звонок", "meeting": "встреча", "zoom": "Zoom", "telegram": "Telegram", "email": "почта",
             "whatsapp": "WhatsApp", "note": "заметка", "other": "другое"}


def _names_of(conn, ids) -> str:
    ids = [str(x) for x in ids or []]
    if not ids:
        return ""
    rows = conn.execute("SELECT coalesce(short, name) AS n FROM crm.people WHERE id = ANY(%s::uuid[]) ORDER BY 1", (ids,)).fetchall()
    return ", ".join(r["n"] for r in rows)


def _deal_line(conn, d: dict) -> str:
    bits = [d["name"], STAGE_WORD.get(d["stage"], d["stage"])
            + (f" — {OUTCOME_WORD.get(d['outcome'], d['outcome'])}" if d.get("outcome") else "")]
    if d.get("lead_person_id"):
        bits.append("ведёт " + _names_of(conn, [d["lead_person_id"]]))
    if d.get("person_ids"):
        bits.append("люди клиента: " + _names_of(conn, d["person_ids"]))
    if d.get("probability"):
        bits.append(f"вероятность {d['probability']}%")
    if d.get("expected_on"):
        bits.append(f"решение ждём {d['expected_on'].isoformat()}")
    return " · ".join(bits)


def _timeline_lines(conn, where: str, params: list) -> list[str]:
    rows = conn.execute(
        "SELECT i.at, i.kind, i.summary, d.name AS deal FROM crm.interactions i LEFT JOIN crm.deals d ON d.id = i.deal_id "
        f"WHERE i.deleted_at IS NULL AND {where} ORDER BY i.at DESC LIMIT %s", [*params, TIMELINE_SHOWN]).fetchall()
    return [f"— {r['at'].astimezone(MSK).strftime('%d.%m')} {KIND_WORD.get(r['kind'], r['kind'])}: {_short(r['summary'], 160)}"
            + (f" · {r['deal']}" if r["deal"] else "") for r in rows] or ["(пусто)"]


def _person_line(conn, pe: dict) -> str:
    org = conn.execute("SELECT name FROM crm.orgs WHERE id = %s", (pe["org_id"],)).fetchone() if pe.get("org_id") else None
    label = pe.get("short") or pe["name"]
    return " · ".join(x for x in [label + (f" ({pe['name']})" if pe["name"] != label else ""), pe.get("role"),
                                  org and f"откуда: {org['name']}"] if x)


def _card(conn, scope: dict) -> tuple[list[str], dict | None]:
    """Карточка на экране — словами для Claude и указатели для правок (клиент, сделка, человек)."""
    kind = scope.get("card")
    if kind == "deal" and scope.get("deal_id"):
        d = conn.execute("SELECT * FROM crm.v_deals WHERE id = %s", (scope["deal_id"],)).fetchone()
        if not d:
            return [], None
        pr = conn.execute("SELECT id, name, org_id FROM crm.projects WHERE id = %s", (d["project_id"],)).fetchone()
        lines = [f"КАРТОЧКА: сделка «{d['name']}» клиента «{pr['name']}» — {_deal_line(conn, d)}"]
        if d.get("deal_type"):
            lines.append(f"Тип: {d['deal_type']}")
        if d.get("team_ids"):
            lines.append("Команда: " + _names_of(conn, d["team_ids"]))
        lines += ["Хронология сделки (свежие сверху):", *_timeline_lines(conn, "i.deal_id = %s", [d["id"]])]
        return lines, {"kind": "deal", "deal": d, "project": pr}
    if kind == "client" and scope.get("project_id"):
        pr = conn.execute("SELECT * FROM crm.projects WHERE id = %s", (scope["project_id"],)).fetchone()
        if not pr:
            return [], None
        lines = [f"КАРТОЧКА: клиент «{pr['name']}»" + (f" (ещё зовут: {', '.join(pr['aliases'])})" if pr["aliases"] else "")]
        deals = conn.execute("SELECT * FROM crm.v_deals WHERE project_id = %s ORDER BY stage = 'archive', name", (pr["id"],)).fetchall()
        lines += ["Сделки клиента (его проекты):", *([f"— {_deal_line(conn, d)}" for d in deals] or ["(нет)"])]
        ppl = conn.execute(
            "SELECT pe.* FROM crm.people pe WHERE pe.archived_at IS NULL AND ((pe.org_id IS NOT NULL AND pe.org_id = %s) "
            "OR EXISTS (SELECT 1 FROM crm.deals d WHERE d.project_id = %s AND pe.id = ANY (d.person_ids))) ORDER BY pe.name",
            (pr["org_id"], pr["id"])).fetchall()
        lines += ["Люди клиента:", *([f"— {_person_line(conn, x)}" for x in ppl] or ["(нет)"])]
        lines += ["Хронология (свежие сверху):", *_timeline_lines(conn, "i.project_id = %s", [pr["id"]])]
        return lines, {"kind": "client", "project": pr, "deals": deals, "people": ppl}
    if kind == "person" and scope.get("person_id"):
        pe = conn.execute("SELECT * FROM crm.people WHERE id = %s", (scope["person_id"],)).fetchone()
        if not pe:
            return [], None
        lines = [f"КАРТОЧКА: человек — {_person_line(conn, pe)}"]
        deals = conn.execute(
            "SELECT * FROM crm.v_deals WHERE lead_person_id = %s OR %s = ANY (team_ids) OR %s = ANY (person_ids) "
            "OR source_person_id = %s ORDER BY stage = 'archive', name", [pe["id"]] * 4).fetchall()
        lines += ["Его сделки:", *([f"— {_deal_line(conn, d)}" for d in deals] or ["(нет)"])]
        lines += ["Хронология с ним (свежие сверху):", *_timeline_lines(conn, "%s = ANY (i.person_ids)", [pe["id"]])]
        return lines, {"kind": "person", "person": pe}
    return [], None


def _calendar(today: dt.date) -> str:
    days = [today + dt.timedelta(days=i) for i in range(15)]
    return "; ".join(f"{WD_SHORT[d.weekday()]} {d.isoformat()}" + (" — сегодня" if i == 0 else " — завтра" if i == 1 else "")
                     for i, d in enumerate(days))


def _task_line(t: dict, today: dt.date) -> str:
    bits = [f"#{t['num']} {t['title']}"]
    if t["status"] != "open":
        bits.append({"done": "сделано", "cancelled": "отменено"}.get(t["status"], t["status"]))
    bits.append(f"проект {t['project_name']}" if t.get("project_name") else "Входящие")
    if t.get("deal_name"):
        bits.append(f"сделка {t['deal_name']}")
    who = t.get("person_short") or t.get("person_name")
    if t["ball"] in ("waiting", "agenda") and who:
        bits.append(f"{'жду от' if t['ball'] == 'waiting' else 'повестка с'} {who}")
    elif who:
        bits.append(f"с {who}")
    if t.get("due_date"):
        d = t["due_date"]
        bits.append(f"срок {d.isoformat()}" + (f" {str(t['due_time'])[:5]}" if t.get("due_time") else "")
                    + (" (просрочено)" if t["status"] == "open" and d < today else ""))
    if t.get("focus_on") == today:
        bits.append("в «Сейчас»")
    if t.get("estimate_min"):
        bits.append(f"{t['estimate_min']} мин")
    if t.get("labels"):
        bits.append("метки " + ", ".join(t["labels"]))
    said = remind.state(t, today)
    if said:
        bits.append(said)
    note = _short(t.get("notes"))
    line = " · ".join(bits)
    return line + (" — " + note if note else "")


MSK = dt.timezone(dt.timedelta(hours=3))


def _short(text: str | None, n: int = NOTE_CHARS) -> str:
    s = " ".join((text or "").split())
    return s[:n] + "…" if len(s) > n else s


def _sug_line(s: dict, t: dict | None) -> str:
    """Предложение словами, как его показывает веб (sugText): завести, закрыть, уточнить, взять себе."""
    p = s.get("payload") or {}
    quote = f" — «{_short(s['quote'], 200)}»" if s.get("quote") else ""
    if s["kind"] == "create":
        title = p.get("title") or s.get("quote") or ""
        who = p.get("person_name")
        bits = [p.get("project_name"),
                f"{BALL_WORD[p['ball']]} {who}" if p.get("ball") in BALL_WORD and who else (f"с {who}" if who else None),
                p.get("due_date") and f"срок {p['due_date']}"]
        line = f"завести: «{title}»" + "".join(f" · {b}" for b in bits if b)
        if p.get("notes"):
            line += f" — заметка: {_short(p['notes'])}"
        return line + (quote if s.get("quote") and s["quote"] != title else "")
    ref = f"#{t['num']} {t['title']}" if t else "дело не видно"
    if t and t["status"] != "open":
        ref += " (" + {"done": "уже сделано", "cancelled": "уже отменено"}.get(t["status"], t["status"]) + ")"
    if s["kind"] == "close":
        return f"закрыть {ref}" + (f" — основание: «{_short(s['quote'], 200)}»" if s.get("quote") else "")
    if s["kind"] == "assign":
        return f"взять себе {ref}"
    who = p.get("person_name")
    what = [p.get("title") and f"название «{p['title']}»",
            p.get("due_date") and f"срок {p['due_date']}",
            p.get("ball") and ("мяч: " + ({"mine": "моё"}.get(p["ball"]) or BALL_WORD.get(p["ball"], p["ball"]))
                               + (f" {who}" if who and p["ball"] != "mine" else "")),
            not p.get("ball") and who and f"человек: {who}",
            p.get("note") and f"подробности: {_short(p['note'], 200)}"]
    return f"уточнить {ref}: " + "; ".join(x for x in what if x) + quote


def _date(s: str) -> dt.date | None:
    if re.fullmatch(r"\d{4}-\d{2}-\d{2}", s or ""):
        try:
            return dt.date.fromisoformat(s)
        except ValueError:
            return None
    return None


def _fields(x: dict, t: dict | None, index: dict, today: dt.date) -> tuple[dict, list[str]]:
    """Запись Claude → поля дела (только то, что меняется) и что не получилось словами."""
    out: dict = {}
    miss: list[str] = []
    s = lambda k: (x.get(k) or "").strip()  # noqa: E731
    if s("title"):
        out["title"] = s("title")
    if s("due") == "-":
        out["due_date"] = None
        out["due_time"] = None
    elif s("due"):
        d = _date(s("due"))
        if d:
            out["due_date"] = d.isoformat()
        else:
            miss.append(f"срок «{s('due')}»")
    if s("due_time") == "-":
        out["due_time"] = None
    elif re.fullmatch(r"\d{1,2}:\d{2}", s("due_time")):
        out["due_time"] = s("due_time").zfill(5)
    if x.get("now") == "on":
        out["focus_on"] = today.isoformat()
    elif x.get("now") == "off":
        out["focus_on"] = None
    if s("project") == "-":
        out["project_id"] = None
        out["deal_id"] = None
    elif s("project"):
        pid = parse._one(index, "projects", s("project"))
        if pid:
            out["project_id"] = str(pid)
            if t is None or str(t.get("project_id")) != str(pid):
                out["deal_id"] = None
        else:
            miss.append(f"проект «{s('project')}»")
    if s("deal"):
        hits = list(dict.fromkeys(index["deals"].get(parse._norm(s("deal")), [])))
        want = out.get("project_id") or (t and t.get("project_id") and str(t["project_id"]))
        hits = [h for h in hits if not want or str(h[1]) == str(want)] or hits
        if len(hits) == 1:
            out["deal_id"], out["project_id"] = str(hits[0][0]), str(hits[0][1])
        else:
            miss.append(f"сделку «{s('deal')}»")
    person = t.get("person_id") if t else None
    if s("person") == "-":
        out["person_id"] = person = None
        if x.get("ball") in ("", None) and t and t.get("ball") != "mine":
            out["ball"] = "mine"
    elif s("person"):
        pe = parse._one(index, "people", s("person"))
        if pe:
            out["person_id"] = person = str(pe)
        else:
            miss.append(f"человека «{s('person')}»")
    if x.get("ball") in ("mine", "waiting", "agenda"):
        if x["ball"] == "mine" or person:
            out["ball"] = x["ball"]
        else:
            miss.append("с кем мяч")
    if s("notes_add"):
        old = (t.get("notes") or "").rstrip() if t else ""
        out["notes"] = (old + "\n" if old else "") + s("notes_add")
    if s("remind_at") == "-":
        out["remind_at"] = None
    elif s("remind_at"):
        at = remind.local_iso(s("remind_at"))
        if at:
            out["remind_at"], out["remind_place"] = at, None  # одно из двух: время снимает место
        else:
            miss.append(f"время напоминания «{s('remind_at')}»")
    if s("remind_place") == "-":
        out["remind_place"] = None
    elif s("remind_place") and not out.get("remind_at"):
        place = remind.match_place(s("remind_place"), index.get("places") or [])
        if place:
            out["remind_place"], out["remind_at"] = place, None
        else:
            miss.append(f"место «{s('remind_place')}» у телефона")
    est = int(x.get("estimate_min") or 0)
    if est > 0:
        out["estimate_min"] = est
    elif est < 0:
        out["estimate_min"] = None
    add = [label for label in x.get("labels_add") or [] if label in index["labels"]]
    rm = set(x.get("labels_remove") or [])
    if add or rm:
        cur = list(t.get("labels") or []) if t else []
        new = [label for label in cur if label not in rm] + [label for label in add if label not in cur]
        if new != cur:
            out["labels"] = new
    return out, miss


_wire = store.jsonable  # значение поля так, как его шлёт веб: даты и uuid строкой, время ЧЧ:ММ


def to_ops(data: dict, tasks: dict, index: dict, today: dt.date, defaults: dict,
           now_ids: set[str] | None = None) -> tuple[list[dict], list[dict], list[str]]:
    """Ответ Claude → операции Дел, «как было» для отмены и чего не получилось.

    now_ids — дела, уже стоящие в «Сейчас»: больше NOW_MAX туда не встанет (None — без счёта).
    """
    ops, undo, miss = [], [], []
    seen = set()
    room = None
    if now_ids is not None:
        off = {str(t["id"]) for x in data.get("changes") or [] if x.get("now") == "off"
               and (t := tasks.get(x.get("num"))) and str(t["id"]) in now_ids}
        room = NOW_MAX - len(now_ids - off)

    def fits(tid: str | None) -> bool:
        nonlocal room
        if room is None or (tid and tid in now_ids):
            return True
        room -= 1
        return room >= 0

    for x in data.get("changes") or []:
        t = tasks.get(x.get("num"))
        if not t:
            miss.append(f"дело #{x.get('num')} не на экране")
            continue
        if t["id"] in seen:
            continue
        seen.add(t["id"])
        set_, why = _fields(x, t, index, today)
        miss += [f"#{t['num']}: не нашёл {w}" for w in why]
        set_ = {k: v for k, v in set_.items() if _wire(t.get(k)) != v and not (t.get(k) in (None, [], "") and v in (None, [], ""))}
        if set_.get("focus_on") and not fits(str(t["id"])):
            del set_["focus_on"]
            miss.append(f"#{t['num']}: в «Сейчас» уже {NOW_MAX} — не поставил, сначала убери одно")
        status = x.get("status") if x.get("status") in ("done", "cancelled", "open") and x.get("status") != t["status"] else None
        if not set_ and not status:
            continue
        if set_:
            ops.append({"op": "task.set", "id": str(t["id"]), "set": set_, "was": {k: _wire(t.get(k)) for k in set_}})
        if status:
            ops.append({"op": {"done": "task.done", "cancelled": "task.cancel", "open": "task.reopen"}[status], "id": str(t["id"])})
        undo.append({"id": str(t["id"]), "num": t["num"], "title": t["title"],
                     "before": {k: _wire(t.get(k)) for k in set_}, "after": set_,
                     "status": [t["status"], status] if status else None})
    for x in data.get("create") or []:
        if not (x.get("title") or "").strip():
            continue
        set_, why = _fields(x, None, index, today)
        miss += [f"«{x['title']}»: не нашёл {w}" for w in why]
        task = {**{k: v for k, v in set_.items() if v is not None}, "title": x["title"].strip(), "source": "web"}
        for k in ("project_id", "person_id"):
            if k not in task and defaults.get(k) and not (k == "project_id" and (x.get("project") or "").strip() == "-"):
                task[k] = defaults[k]
        # На странице сделки новое дело — её следующий шаг, если проект тот же.
        if defaults.get("deal_id") and "deal_id" not in task and task.get("project_id") == defaults.get("project_id"):
            task["deal_id"] = defaults["deal_id"]
        if task.get("ball") in ("waiting", "agenda") and not task.get("person_id"):
            task["ball"] = "mine"
        if task.get("focus_on") and not fits(None):
            del task["focus_on"]
            miss.append(f"«{task['title']}»: в «Сейчас» уже {NOW_MAX} — завёл без него")
        ops.append({"op": "task.create", "task": task})
    return ops, undo, miss


def _person_ref(conn, index: dict, name: str) -> dict | None:
    """Человек по имени: справочник разбора, потом общее узнавание (уменьшительные, падежи) — только уверенное."""
    pid = parse._one(index, "people", name)
    if not pid:
        w = people.who(conn, q=name)
        pid = w["best"]["id"] if w["sure"] else None
    return conn.execute("SELECT * FROM crm.people WHERE id = %s", (pid,)).fetchone() if pid else None


def _org_ref(index: dict, name: str):
    ids = list(dict.fromkeys(index.get("orgs", {}).get(parse._norm(name), [])))
    return ids[0] if len(ids) == 1 else None


def _deal_ref(index: dict, name: str, project_id: str | None):
    hits = list(dict.fromkeys(index["deals"].get(parse._norm(name), [])))
    if project_id:
        hits = [h for h in hits if str(h[1]) == str(project_id)] or hits
    return hits[0] if len(hits) == 1 else None


def _org_name(conn, oid) -> str:
    row = conn.execute("SELECT name FROM crm.orgs WHERE id = %s", (oid,)).fetchone() if oid else None
    return row["name"] if row else "?"


def card_ops(conn, data: dict, card: dict | None, index: dict, today: dt.date) -> tuple[list[dict], list[dict], list[str]]:
    """Хронология, люди и сделки из ответа Claude → операции. plan — по записи на правку: что она значит
    словами (what), сколько операций (n) и чем её вернуть (undo) — для ответа и «Вернуть всё»."""
    ops, plan, miss = [], [], []
    if not card:
        # Людей правим откуда угодно («Иван теперь CFO в Бете» на странице «Люди»), хронологию и сделки — в карточке.
        if any(data.get(k) for k in ("notes", "deals")):
            miss.append("хронологию и сделки правлю на странице клиента, сделки или человека")
        card = {"kind": None}
    project = card.get("project")
    pid = str(project["id"]) if project else None
    now = dt.datetime.now(dt.timezone.utc)

    for n in (data.get("notes") or []) if card["kind"] else []:
        text = (n.get("text") or "").strip()
        if not text:
            continue
        d = _date(n.get("date"))
        item = {"id": str(uuid.uuid4()), "kind": n.get("kind") or "note", "summary": text, "source": "manual",
                "at": (dt.datetime.combine(d, dt.time(12), MSK) if d and d != today else now).isoformat()}
        if pid:
            item["project_id"] = pid
        if card["kind"] == "deal":
            item["deal_id"] = str(card["deal"]["id"])
        elif (n.get("deal") or "").strip():
            hit = _deal_ref(index, n["deal"], pid)
            if hit:
                item["deal_id"], item["project_id"] = str(hit[0]), str(hit[1])
            else:
                miss.append(f"не нашёл сделку «{n['deal']}» — записал без неё")
        who = [str(card["person"]["id"])] if card["kind"] == "person" else []
        for name in n.get("people") or []:
            pe = _person_ref(conn, index, name)
            if pe:
                who.append(str(pe["id"]))
            else:
                miss.append(f"не нашёл человека «{name}» для хронологии")
        if who:
            item["person_ids"] = list(dict.fromkeys(who))
        ops.append({"op": "interaction.add", "data": item})
        plan.append({"what": f"в хронологию: {text}", "n": 1, "undo": [{"op": "interaction.delete", "id": item["id"]}]})

    for x in data.get("people") or []:
        name = (x.get("person") or "").strip()
        if not name:
            continue
        set_: dict = {}
        org = (x.get("org") or "").strip()
        if org == "-":
            set_["org_id"] = None
        elif org:
            oid = _org_ref(index, org)
            if oid:
                set_["org_id"] = str(oid)
            else:
                miss.append(f"{name}: не нашёл компанию «{org}» — заведи её клиентом или скажи точнее")
        role = (x.get("role") or "").strip()
        if role == "-":
            set_["role"] = None
        elif role:
            set_["role"] = role[:120]
        pe = _person_ref(conn, index, name)
        if pe is None:
            if not x.get("new"):
                miss.append(f"не нашёл человека «{name}»")
                continue
            row = {"id": str(uuid.uuid4()), "name": name, **{k: v for k, v in set_.items() if v}}
            if "org_id" not in row and project and project.get("org_id"):
                row["org_id"] = str(project["org_id"])
            ops.append({"op": "person.create", "data": row})
            n_ops, undo = 1, [{"op": "person.set", "id": row["id"], "set": {"archived_at": now.isoformat()}}]
            if card["kind"] == "deal":
                ids = [str(i) for i in card["deal"]["person_ids"] or []]
                ops.append({"op": "deal.set", "id": str(card["deal"]["id"]), "set": {"person_ids": ids + [row["id"]]}})
                undo.append({"op": "deal.set", "id": str(card["deal"]["id"]), "set": {"person_ids": ids}})
                n_ops += 1
            plan.append({"what": f"новый человек: {name}" + (f", {row['role']}" if row.get("role") else "")
                         + (f", откуда — {_org_name(conn, row['org_id'])}" if row.get("org_id") else ""), "n": n_ops, "undo": undo})
            continue
        label = pe["short"] or pe["name"]
        extra = []  # (операция, её отмена): человек уходит из людей сделок клиента
        if x.get("remove"):
            if card["kind"] == "client" and pe["org_id"] and str(pe["org_id"]) == str(project.get("org_id")) and "org_id" not in set_:
                set_["org_id"] = None
            deals = card.get("deals") or ([card["deal"]] if card["kind"] == "deal" else [])
            for d in deals:
                ids = [str(i) for i in d["person_ids"] or []]
                if str(pe["id"]) in ids:
                    extra.append(({"op": "deal.set", "id": str(d["id"]), "set": {"person_ids": [i for i in ids if i != str(pe["id"])]}},
                                  {"op": "deal.set", "id": str(d["id"]), "set": {"person_ids": ids}}))
        set_ = {k: v for k, v in set_.items() if _wire(pe.get(k)) != v and not (_empty(pe.get(k)) and _empty(v))}
        if not set_ and not extra:
            continue
        words = []
        if "org_id" in set_:
            words.append(f"откуда — {_org_name(conn, set_['org_id'])}" if set_["org_id"] else "без компании")
        if "role" in set_:
            words.append(f"должность — {set_['role']}" if set_["role"] else "без должности")
        if x.get("remove"):
            words.append("убран из карточки")
        undo = []
        if set_:
            ops.append({"op": "person.set", "id": str(pe["id"]), "set": set_})
            undo.append({"op": "person.set", "id": str(pe["id"]), "set": {k: _wire(pe.get(k)) for k in set_}})
        for o, u in extra:
            ops.append(o)
            undo.append(u)
        plan.append({"what": f"{label}: " + ", ".join(dict.fromkeys(words)), "n": len(undo), "undo": undo})

    for x in (data.get("deals") or []) if card["kind"] else []:
        nm = (x.get("deal") or "").strip()
        new_name = (x.get("name") or "").strip()
        prob = int(x.get("probability") or 0)
        exp = (x.get("expected_on") or "").strip()
        if x.get("new"):
            title = new_name or nm
            if not pid or not title:
                miss.append("новую сделку завожу только в карточке клиента, с названием")
                continue
            row = {"id": str(uuid.uuid4()), "project_id": pid, "name": title, "stage": x.get("stage") or "lead"}
            if prob > 0:
                row["probability"] = min(prob, 100)
            if _date(exp):
                row["expected_on"] = exp
            ops.append({"op": "deal.create", "data": row})
            plan.append({"what": f"новая сделка: {title} ({STAGE_WORD[row['stage']]})", "n": 1, "undo": []})
            continue
        if card["kind"] == "deal" and (not nm or parse._norm(nm) == parse._norm(card["deal"]["name"])):
            did = card["deal"]["id"]
        else:
            hit = _deal_ref(index, nm, pid) if nm else None
            if not hit:
                miss.append(f"не нашёл сделку «{nm}»")
                continue
            did = hit[0]
        d = conn.execute("SELECT * FROM crm.v_deals WHERE id = %s", (did,)).fetchone()
        set_ = {}
        if x.get("stage") and x["stage"] != d["stage"]:
            set_["stage"] = x["stage"]
        if x.get("outcome"):
            set_["outcome"] = x["outcome"]
            set_["lost_reason"] = (x.get("reason") or "").strip() or None
            set_.pop("stage", None)  # итог сам уводит в архив
        if prob > 0:
            set_["probability"] = min(prob, 100)
        elif prob < 0:
            set_["probability"] = None
        if exp == "-":
            set_["expected_on"] = None
        elif _date(exp):
            set_["expected_on"] = exp
        if new_name and new_name != d["name"]:
            set_["name"] = new_name
        set_ = {k: v for k, v in set_.items() if _wire(d.get(k)) != v and not (_empty(d.get(k)) and _empty(v))}
        if not set_:
            continue
        back = {k: _wire(d.get(k)) for k in set_}
        if "outcome" in set_:  # вернуть из архива: прежняя стадия и без итога
            back.update(stage=d["stage"], outcome=_wire(d.get("outcome")), closed_on=_wire(d.get("closed_on")))
        words = []
        if "stage" in set_:
            words.append("стадия — " + STAGE_WORD[set_["stage"]])
        if "outcome" in set_:
            words.append("закрыта: " + OUTCOME_WORD[set_["outcome"]] + (f" ({set_['lost_reason']})" if set_.get("lost_reason") else ""))
        if "probability" in set_:
            words.append(f"вероятность {set_['probability']}%" if set_["probability"] else "без вероятности")
        if "expected_on" in set_:
            words.append("решение ждём " + set_["expected_on"] if set_["expected_on"] else "без даты решения")
        if "name" in set_:
            words.append(f"название «{set_['name']}»")
        ops.append({"op": "deal.set", "id": str(did), "set": set_})
        plan.append({"what": f"{d['name']}: " + ", ".join(words), "n": 1, "undo": [{"op": "deal.set", "id": str(did), "set": back}]})
    return ops, plan, miss


def card_done(plan: list[dict], results: list[dict]) -> tuple[list[dict], list[str]]:
    """Что прошло из правок карточки: правка с несколькими операциями прошла, если прошли все."""
    out, errors, i = [], [], 0
    for w in plan:
        rs = results[i:i + w["n"]]
        i += w["n"]
        bad = [r for r in rs if not r.get("ok")]
        if bad:
            errors.append(f"{w['what']}: {bad[0].get('error') or 'не вышло'}")
        else:
            out.append({"what": w["what"], "undo": w["undo"]})
    return out, errors


def _empty(v) -> bool:
    return v in (None, "", [])


def sug_ops(data: dict, sugs: dict, targets: dict, index: dict, today: dt.date) -> tuple[list[dict], list[dict], list[str]]:
    """Решения Claude по «Новому» → операции suggestion.decide; plan — что каждая значит (для ответа и «Вернуть»).

    sugs — П-номер → предложение (только ждущие решения и только этого человека), targets — дела,
    которые закрывают и уточняют предложения. Правка при принятии сравнивается с тем, чем станет
    предложение: совпадающее не шлём, чтобы принятое без правок не считалось «поправленным».
    """
    ops, plan, miss = [], [], []
    seen = set()
    for x in data.get("suggestions") or []:
        n = x.get("n")
        s = sugs.get(n)
        if not s:
            miss.append(f"предложения П{n} на экране нет или оно уже разобрано")
            continue
        if s["id"] in seen:
            continue
        seen.add(s["id"])
        t = targets.get(s.get("task_id"))
        what = _sug_line(s, t)
        if x.get("decision") == "reject":
            why = (x.get("reason") or "").strip() or None
            ops.append({"op": "suggestion.decide", "id": str(s["id"]), "decision": "reject", "reason": why})
            plan.append({"n": n, "sid": str(s["id"]), "kind": s["kind"], "decision": "reject", "what": what, "reason": why})
            continue
        if x.get("decision") != "accept":
            continue
        p = s.get("payload") or {}
        if s["kind"] == "create":
            cur = {"title": p.get("title"), "notes": p.get("notes"), "ball": p.get("ball"), "due_date": p.get("due_date"),
                   "labels": p.get("labels") or [],
                   "person_id": parse._one(index, "people", p["person_name"]) if p.get("person_name") else None,
                   "project_id": parse._one(index, "projects", p["project_name"]) if p.get("project_name") else None}
        else:
            cur = dict(t or {})
        fix, why = _fields(x, cur, index, today)
        miss += [f"П{n}: не нашёл {w}" for w in why]
        fix = {k: v for k, v in fix.items() if _wire(cur.get(k)) != v and not (_empty(cur.get(k)) and _empty(v))}
        ops.append({"op": "suggestion.decide", "id": str(s["id"]), "decision": "accept", **({"set": fix} if fix else {})})
        plan.append({"n": n, "sid": str(s["id"]), "kind": s["kind"], "decision": "accept", "what": what, "fix": fix, "target": t})
    return ops, plan, miss


def decided(plan: list[dict], results: list[dict]) -> tuple[list[dict], list[str]]:
    """Что вышло по «Новому»: принятое и отклонённое; у принятого — как было, для «Вернуть всё».

    Заведённое отменяется (created), поправленное и закрытое — как правка дела: before/after и
    status [было, стало], та же форма, что у changes.
    """
    out, errors = [], []
    for w, r in zip(plan, results):
        if not r.get("ok"):
            errors.append(f"П{w['n']}: {r.get('error') or 'не вышло'}")
            continue
        d = {"n": w["n"], "sid": w["sid"], "kind": w["kind"], "decision": w["decision"], "what": w["what"], "reason": w.get("reason")}
        task = r.get("task")
        if w["decision"] == "accept" and task:
            d.update(id=task["id"], num=task["num"], title=task["title"])
            if w["kind"] == "create":
                d.update(created=True, after=w["fix"])
            else:
                # Дела до решения не было видно (чужое «взять себе») — вернуть не к чему: пустое
                # «как было» стёрло бы все поля.
                was = w["target"] or {}
                keys = [k for k in UNDO_FIELDS if was and k in task and _wire(was.get(k)) != task[k]
                        and not (_empty(was.get(k)) and _empty(task[k]))]
                d.update(before={k: _wire(was.get(k)) for k in keys}, after={k: task[k] for k in keys},
                         status=[was["status"], task["status"]] if was.get("status") and was["status"] != task["status"] else None)
        out.append(d)
    return out, errors


def client(key: str, proxy: str | None):
    return parse.client(key, proxy)


def ask(cl, system: str, user_text: str, model: str = MODEL, effort: str = EFFORT) -> dict:
    """Один запрос: справочник в кэше, ответ — строго по схеме. Модель и глубина — из настроек человека."""
    with cl.beta.messages.stream(
        model=model,
        max_tokens=16000,
        system=[{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}],
        output_config={"effort": effort, "format": {"type": "json_schema", "schema": SCHEMA}},
        messages=[{"role": "user", "content": user_text}],
        # Отказ модели — повтор на резервной внутри того же запроса (как у разбора и встреч).
        betas=[parse.FALLBACK_BETA],
        fallbacks="default",
    ) as stream:
        msg = stream.get_final_message()
    if msg.stop_reason == "refusal":
        raise AskError("Claude отказался выполнять эту команду")
    if msg.stop_reason == "max_tokens":
        raise AskError("команда слишком большая для одного раза — раздели на части")
    text = next((b.text for b in msg.content if getattr(b, "type", "") == "text"), "")
    try:
        data = json.loads(text)
    except ValueError as e:
        raise AskError(f"Claude ответил не JSON ({e})") from e
    data["_usage"] = llm.usage_of(msg, model)
    return data


cost = llm.cost


def settings_of(conn, user: str) -> tuple[str, str]:
    """Модель и глубина из «Настроек» человека; чужое значение — по умолчанию."""
    row = conn.execute("SELECT settings FROM crm.users WHERE id = %s", (user,)).fetchone()
    s = (row and row["settings"]) or {}
    return llm.MODELS.get(s.get("claude_model"), MODEL), s.get("claude_effort") if s.get("claude_effort") in llm.EFFORTS else EFFORT


def run(url: str, user: str, text: str, scope: dict, key: str, proxy: str | None,
        via: str = "web", actor: str | None = None, ask_fn=None, parse_fn=None) -> dict:
    """Команда → правки (или разбор новых дел) → применить. ask_fn/parse_fn — подмена Claude в тестах.

    scope: {"title": «где человек», "task_ids": [дела на экране], "focus": id дела у микрофона,
    "project_id"/"person_id": куда класть новые дела, "suggestion_ids": [предложения «Нового» на
    экране, сверху вниз — П1, П2…]}.
    """
    text = (text or "").strip()
    if not text:
        raise AskError("пусто — скажи, что сделать")
    if len(text) > MAX_INPUT:
        raise AskError(f"больше {MAX_INPUT} знаков — для длинной надиктовки нажми звёздочку")
    ids = [str(x) for x in (scope.get("task_ids") or [])][:MAX_TASKS]
    focus = str(scope.get("focus") or "") or None
    if focus and focus not in ids:
        ids.insert(0, focus)
    sids = [str(x) for x in (scope.get("suggestion_ids") or [])][:MAX_SUGS]
    with db.session(url, user, via="ask") as conn:
        cat, index = _catalog(conn)
        today = conn.execute("SELECT crm.today() AS d").fetchone()["d"]
        me = conn.execute("SELECT name, id = crm.owner_id() AS owner FROM crm.users WHERE id = %s", (user,)).fetchone()
        model, effort = settings_of(conn, user)
        card_lines, card = _card(conn, scope)
        if card:
            model = CARD_MODEL
        now_ids = {str(r["id"]) for r in conn.execute(
            "SELECT id FROM tasks.tasks WHERE owner_id = %s AND status = 'open' AND focus_on = crm.today()", (user,)).fetchall()}
        index["places"] = places = remind.places_of(conn, user)
        hm = conn.execute("SELECT to_char(now() AT TIME ZONE 'Europe/Moscow', 'HH24:MI') AS hm").fetchone()["hm"]
        rows = conn.execute("SELECT * FROM tasks.v_tasks WHERE id = ANY(%s::uuid[])", (ids,)).fetchall() if ids else []
        # Только ждущие решения и только свои — как в «Новом» у веба; разобранное с телефона выпадает.
        srows = conn.execute("SELECT * FROM tasks.suggestions WHERE id = ANY(%s::uuid[]) AND status = 'pending' AND for_user = %s",
                             (sids, user)).fetchall() if sids else []
        tids = list({str(s["task_id"]) for s in srows if s["task_id"]})
        trows = conn.execute("SELECT * FROM tasks.v_tasks WHERE id = ANY(%s::uuid[])", (tids,)).fetchall() if tids else []
    order = {i: k for k, i in enumerate(ids)}
    rows.sort(key=lambda r: order.get(str(r["id"]), 0))
    tasks = {r["num"]: r for r in rows}
    focused = next((r for r in rows if str(r["id"]) == focus), None)
    # П-номер — место на экране, а не в выборке: разобранное с другого устройства оставляет дырку,
    # и «третье» по-прежнему про третье сверху.
    by_id = {str(s["id"]): s for s in srows}
    sugs = {k: by_id[i] for k, i in enumerate(sids, 1) if i in by_id}
    targets = {r["id"]: r for r in trows}
    msg = [f"Сегодня {today.isoformat()}, {WD[today.weekday()]}, сейчас {hm}.", "Календарь: " + _calendar(today),
           f"Страница: {(scope.get('title') or 'Дела').strip()[:200]}"]
    if me and not me["owner"]:
        msg.append(f"Команду даёт не Саша, а {me['name']}: «Саша» и mine выше — про этого человека.")
    if card_lines:
        msg += ["", *card_lines]
    msg.append(f"В «Сейчас» сегодня: {len(now_ids)} из {NOW_MAX}.")
    if focused:
        msg += ["", "ДЕЛО (команда про него):", _task_line(focused, today)]
    msg += ["", f"ДЕЛА НА ЭКРАНЕ ({len(rows)}):"] + ([_task_line(r, today) for r in rows] or ["(нет)"])
    if sids:
        msg += ["", f"НОВОЕ НА ЭКРАНЕ — ждут решения ({len(sugs)}):"]
        batch = object()
        for n, s in sugs.items():
            if s["batch_ref"] != batch:
                batch = s["batch_ref"]
                msg.append(f"Пачка: {s['batch_title'] or 'без названия'}" if batch else "Без пачки:")
            msg.append(f"П{n}. {_sug_line(s, targets.get(s['task_id']))}")
        if not sugs:
            msg.append("(ничего — всё уже разобрано)")
    msg += ["", f"КОМАНДА: {text}"]
    # Справочник, правила напоминаний и места — в кэше: между командами одного человека они те же.
    system = (SYSTEM.replace("{REMIND}", remind_rules()).replace("{CATALOG}", cat)
              .replace("{PLACES}", remind.places_block(places)))
    if ask_fn is None:
        if not key:
            raise AskError("Claude не настроен: нет ключа в dela.env")
        cl = client(key, proxy)
        ask_fn = lambda s, u: ask(cl, s, u, model, effort)  # noqa: E731
    data = ask_fn(system, "\n".join(msg))
    if data.get("card"):
        for k, v in split_card(data["card"]).items():
            data[k] = (data.get(k) or []) + v
    usage = data.get("_usage")
    llm.account(url, "ask", llm.cost(usage, model), (usage or {}).get("model") or model)
    defaults = {k: scope[k] for k in ("project_id", "person_id", "deal_id") if scope.get(k)}
    if card and card["kind"] == "deal":
        defaults["project_id"] = str(card["deal"]["project_id"])
    if (data.get("route") == "new" and not focused and not data.get("suggestions")
            and not any(data.get(k) for k in ("notes", "people", "deals"))):
        # Команда целиком про новые дела — разбор надиктовки, как у звёздочки (тот же промпт, что у телефона).
        out = parse.run(url, user, text, {k: v for k, v in defaults.items() if k != "deal_id"}, key, proxy,
                        via=via, actor=actor, ask_fn=parse_fn)
        if defaults.get("deal_id"):  # на странице сделки новые дела — её шаги
            fix = [{"op": "task.set", "id": str(t["id"]), "set": {"deal_id": defaults["deal_id"]}} for t in out["tasks"]
                   if str(t.get("project_id")) == defaults["project_id"] and not t.get("deal_id")]
            if fix:
                got = store.apply_ops(url, user, fix, via, actor)["results"]
                byid = {str(r["task"]["id"]): r["task"] for r in got if r.get("ok") and r.get("task")}
                out["tasks"] = [byid.get(str(t["id"]), t) for t in out["tasks"]]
        return {"route": "new", "reply": "", "changed": [], "decided": [], "crm": [], **out, "usage": usage}
    sops, plan, smiss = sug_ops(data, sugs, targets, index, today)
    tops, undo, miss = to_ops(data, tasks, index, today, defaults, now_ids)
    with db.session(url, user, via="ask") as conn:
        cops, cplan, cmiss = card_ops(conn, data, card, index, today)
    ops = sops + tops + cops
    res = store.apply_ops(url, user, ops, via, actor) if ops else {"results": []}
    results = res["results"]
    done, serr = decided(plan, results[:len(sops)])
    terr = [r["error"] for r in results[len(sops):len(sops) + len(tops)] if not r.get("ok")]
    crm_done, cerr = card_done(cplan, results[len(sops) + len(tops):])
    errors = serr + terr + cerr
    created = [r["task"] for o, r in zip(ops, results) if o["op"] == "task.create" and r.get("ok") and r.get("task")]
    if errors:
        log.warning("правка: не прошли %d из %d: %s", len(errors), len(ops), errors[0])
    return {"route": "edit", "reply": (data.get("reply") or "").strip(), "changed": undo, "tasks": created, "decided": done,
            "crm": crm_done, "notes": [], "errors": errors + smiss + miss + cmiss, "usage": usage,
            "model": (usage or {}).get("model") or model}
