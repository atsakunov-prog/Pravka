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
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import re

from . import db, llm, parse, store

log = logging.getLogger("dela.ask")

MODEL = "claude-sonnet-5-5"    # по умолчанию; в настройках — llm.MODELS
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
create — новые дела, только если Саша прямо просит завести их внутри правки. Поля — как у changes
  (title обязателен; due, now, project, person, ball, notes_add — заметка нового дела).
reply — одна-две фразы Саше: что сделал. Чего не понял или не нашёл — скажи прямо. Без вступлений.

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

{CATALOG}"""

_TEXT_FIELDS = ["title", "notes_add", "project", "deal", "person", "due", "due_time"]
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
# Одна схема на все страницы: кэш привязан к схеме ответа, и с разными он бы не делился.
SCHEMA = {
    "type": "object",
    "properties": {
        "route": {"type": "string", "enum": ["edit", "new"]},
        "changes": {"type": "array", "items": _ITEM},
        "create": {"type": "array", "items": _ITEM},
        "suggestions": {"type": "array", "items": _SUG},
        "reply": {"type": "string"},
    },
    "required": ["route", "changes", "create", "suggestions", "reply"],
    "additionalProperties": False,
}


class AskError(Exception):
    """Команда не сложилась — словами для человека."""


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
    return text, index


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
    note = _short(t.get("notes"))
    line = " · ".join(bits)
    return line + (" — " + note if note else "")


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


def to_ops(data: dict, tasks: dict, index: dict, today: dt.date, defaults: dict) -> tuple[list[dict], list[dict], list[str]]:
    """Ответ Claude → операции Дел, «как было» для отмены и чего не получилось."""
    ops, undo, miss = [], [], []
    seen = set()
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
        if task.get("ball") in ("waiting", "agenda") and not task.get("person_id"):
            task["ball"] = "mine"
        ops.append({"op": "task.create", "task": task})
    return ops, undo, miss


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
    msg = [f"Сегодня {today.isoformat()}, {WD[today.weekday()]}.", "Календарь: " + _calendar(today),
           f"Страница: {(scope.get('title') or 'Дела').strip()[:200]}"]
    if me and not me["owner"]:
        msg.append(f"Команду даёт не Саша, а {me['name']}: «Саша» и mine выше — про этого человека.")
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
    system = SYSTEM.replace("{CATALOG}", cat)
    if ask_fn is None:
        if not key:
            raise AskError("Claude не настроен: нет ключа в dela.env")
        cl = client(key, proxy)
        ask_fn = lambda s, u: ask(cl, s, u, model, effort)  # noqa: E731
    data = ask_fn(system, "\n".join(msg))
    usage = data.get("_usage")
    llm.account(url, "ask", llm.cost(usage, model), (usage or {}).get("model") or model)
    if data.get("route") == "new" and not focused and not data.get("suggestions"):
        # Команда целиком про новые дела — разбор надиктовки, как у звёздочки (тот же промпт, что у телефона).
        defaults = {k: scope[k] for k in ("project_id", "person_id") if scope.get(k)}
        out = parse.run(url, user, text, defaults, key, proxy, via=via, actor=actor, ask_fn=parse_fn)
        return {"route": "new", "reply": "", "changed": [], "decided": [], **out, "usage": usage}
    defaults = {k: scope[k] for k in ("project_id", "person_id") if scope.get(k)}
    sops, plan, smiss = sug_ops(data, sugs, targets, index, today)
    ops, undo, miss = to_ops(data, tasks, index, today, defaults)
    ops = sops + ops
    res = store.apply_ops(url, user, ops, via, actor) if ops else {"results": []}
    done, serr = decided(plan, res["results"][:len(sops)])
    errors = serr + [r["error"] for r in res["results"][len(sops):] if not r.get("ok")]
    created = [r["task"] for o, r in zip(ops, res["results"]) if o["op"] == "task.create" and r.get("ok") and r.get("task")]
    if errors:
        log.warning("правка: не прошли %d из %d: %s", len(errors), len(ops), errors[0])
    return {"route": "edit", "reply": (data.get("reply") or "").strip(), "changed": undo, "tasks": created, "decided": done,
            "notes": [], "errors": errors + smiss + miss, "usage": usage, "model": (usage or {}).get("model") or model}
