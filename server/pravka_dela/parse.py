"""Разбор свободного текста в дела — Claude, как Разноска в Правке.

Владелец пишет или надиктовывает как есть («Наташе сверку до пятницы, жду от
Ивана модель, обсудить с Толкушкиным фонды»), Claude режет это на дела по
промпту TASKS_DELA (тот же, что у телефона: core/prompts/PromptsRaznoska.kt)
со справочником проектов, людей и меток — только тех, что видит этот
пользователь. Кнопку жмёт сам человек, поэтому дела заводятся сразу, без
«Нового» (туда идёт только автоматика); отмена — одним движением в вебе.
Факты, которые не дела, ложатся заметками в хронологию.

К API — через xray бота (из России API Anthropic закрыт), ключ —
ANTHROPIC_API_KEY в dela.env (установщик берёт ключ встреч).
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import re

from . import db, llm, store

log = logging.getLogger("dela.parse")

MODEL = "claude-opus-5-5"
FALLBACK_BETA = "server-side-fallback-2026-07-01"
MAX_INPUT = 20_000
WD = ["понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"]

# Промпт — копия TASKS_DELA из Правки: правила одни, разбор на телефоне и в вебе
# не должен расходиться. Правишь одно — поправь и другое.
SYSTEM = """Ты разбираешь наговор владельца на дела для его сервиса «Дела».

Кто говорит: Саша — финансовый советник. Его мир: сделки M&A,
банковский advisory, управленческая отчётность, финмодели,
финансирование, налоги; своя команда; клиенты и их собственники;
семья; свои приложения. Он наговаривает на ходу: несколько дел
подряд, а между ними факты, мысли вслух и оговорки.

Текст набран руками или пришёл из распознавания речи: пунктуации может
не быть, имена и термины могут быть услышаны неверно. Сначала пойми смысл целиком,
потом разбирай на дела.

ТВОЯ РАБОТА
Вытащи КАЖДОЕ дело отдельной задачей. Одна задача = одно действие.
Не склеивай два дела в одно и не дроби одно дело на шаги.
Сказанное самим Сашей вслух — уже решение: это дела, а не идеи.

ФОРМУЛИРОВКА (title)
«Кто: глагол + конкретное действие». «Кто» — тот, у кого мяч:
человек из справочника, клиент, контрагент. Мяч у самого Саши —
без префикса, сразу с действия. Коротко и проверяемо: понятно, что
сделать и когда это сделано.
Плохо: «помнить про Майю», «ПТИЦ: юнит-экономика», «ждём реакцию».
Хорошо: «Майя: рассмотреть для казначейских проектов»,
«Тимофей: прислать фидбек по юнит-экономике».

МЯЧ (ball) И ЧЕЛОВЕК (person)
— mine: делает сам Саша. Если он кому-то пообещал («обещал Ивану
  прислать модель») — тоже mine, а person — тот, кому обещал;
— waiting: мяч на чужой стороне, Саша ждёт от person ответа или
  результата («жду от Наташи сверку»);
— agenda: поднять при следующей встрече или звонке с person
  («обсудить с Толкушкиным фонды»).
person — короткое имя ровно из справочника ЛЮДИ ниже. Человека нет
в справочнике или не уверен — пустая строка, title всё равно
начинай с его имени. Для waiting и agenda человек обязателен по
смыслу: без него оставь mine.
Марианне дел не ставь: просьба к ней — это waiting с person
«Марианна», дело остаётся Сашиным.

ПРОЕКТ (project)
Ровно одно имя из справочника ПРОЕКТЫ — так, как оно там записано
(в скобках — как ещё называют, это тоже он). Проект = клиент или
служебное направление. Не уверен — пустая строка, владелец выберет
сам. Проект, которого нет в справочнике, не выдумывай.

ДЕНЬГИ (money)
Обычно пустая строка — деньги наследуются от проекта. «paid» — только
если дело прямо про согласованную оплату, инвойс, деньги по сделке;
«potential» — развитие, будущий клиент.

ОЦЕНКА (estimate_min)
Минуты, если по сказанному ясно: «быстро», «пять минут», «звонок»
— 5–10; «посидеть над моделью час» — 60. Неясно — 0.

ХОЧУ (want)
true — если Саша делает это потому, что сам хочет, а не потому, что
попросили. Обычно false.

МЕТКИ (labels)
Только свободные контексты из справочника МЕТКИ («звонок»). Люди,
мяч, деньги и срочность — поля дела, а не метки. Не уверен — пусто.

СРОК (due)
Сегодняшняя дата названа ниже. Считай от неё: «завтра», «в пятницу»,
«через две недели», «к концу месяца» — точная дата ГГГГ-ММ-ДД.
Срок не назван — пустая строка, не придумывай. Повторяющихся дел в
сервисе нет: «каждый вторник» — одно дело на ближайший вторник, а
повтор словами допиши в notes.

ЗАМЕТКИ К ДЕЛУ (notes)
Только если через три дня дело без них будет непонятно: с кем, о
чём, что считать выполненным. Две-три строки, без истории.

ЧТО НЕ ЯВЛЯЕТСЯ ДЕЛОМ
Факты («комитет пройден», «выручка 2,6 млрд»), результаты встреч,
статусы, рассуждения — не дела. Каждое — отдельной заметкой в поле
notes верхнего уровня: текст, проект и человек из справочника, если
понятно. Они лягут в хронологию клиента. Если дел нет — tasks пустой.

{CATALOG}

Сегодня {TODAY}.{SPEAKER}"""

SCHEMA = {
    "type": "object",
    "properties": {
        "tasks": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "title": {"type": "string"},
                    "notes": {"type": "string"},
                    "project": {"type": "string"},
                    "person": {"type": "string"},
                    "ball": {"type": "string", "enum": ["mine", "waiting", "agenda"]},
                    "due": {"type": "string"},
                    "estimate_min": {"type": "integer"},
                    "money": {"type": "string", "enum": ["", "paid", "potential", "none"]},
                    "want": {"type": "boolean"},
                    "labels": {"type": "array", "items": {"type": "string"}},
                },
                "required": ["title", "notes", "project", "person", "ball", "due", "estimate_min", "money", "want", "labels"],
                "additionalProperties": False,
            },
        },
        "notes": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"text": {"type": "string"}, "project": {"type": "string"}, "person": {"type": "string"}},
                "required": ["text", "project", "person"],
                "additionalProperties": False,
            },
        },
    },
    "required": ["tasks", "notes"],
    "additionalProperties": False,
}


class ParseError(Exception):
    """Разбор не сложился — словами для человека."""


def _norm(s: str | None) -> str:
    return re.sub(r"\s+", " ", (s or "").replace("ё", "е").replace("Ё", "Е").strip().lower())


def catalog(conn) -> tuple[str, dict]:
    """Справочник для промпта (как promptCatalog в Правке) и указатели имя → id."""
    projects = conn.execute(
        "SELECT id, name, aliases, sphere, kind FROM crm.projects WHERE archived_at IS NULL ORDER BY name"
    ).fetchall()
    people = conn.execute(
        "SELECT id, name, short, aliases FROM crm.people WHERE archived_at IS NULL ORDER BY coalesce(short, name)"
    ).fetchall()
    labels = [r["name"] for r in conn.execute("SELECT name FROM tasks.labels ORDER BY name")]
    lines = []
    if projects:
        lines.append("ПРОЕКТЫ (project — ровно одно имя из списка; в скобках — как ещё называют):")
        for p in projects:
            more = f" ({', '.join(p['aliases'])})" if p["aliases"] else ""
            lines.append(f"— {p['name']}{more} · {'дом' if p['sphere'] == 'home' else 'работа'}"
                         + (" · клиент" if p["kind"] == "client" else ""))
    if people:
        lines.append("")
        lines.append("ЛЮДИ (person — короткое имя из списка; в скобках — полное и как ещё зовут):")
        for pe in people:
            label = pe["short"] or pe["name"]
            more = [x for x in [pe["name"] if pe["name"] != label else None, *pe["aliases"]] if x]
            lines.append(f"— {label}" + (f" ({', '.join(more)})" if more else ""))
    if labels:
        lines.append("")
        lines.append("МЕТКИ (только свободные контексты из этого списка, новых не выдумывать):")
        lines.append(", ".join(labels))
    index = {"projects": {}, "people": {}, "labels": set(labels)}
    for p in projects:
        for n in [p["name"], *p["aliases"]]:
            index["projects"].setdefault(_norm(n), []).append(p["id"])
    for pe in people:
        for n in {pe["name"], pe["short"] or "", *pe["aliases"]}:
            if n:
                index["people"].setdefault(_norm(n), []).append(pe["id"])
    return "\n".join(lines), index


def _one(index: dict, kind: str, name: str):
    ids = list(dict.fromkeys(index[kind].get(_norm(name), [])))
    return ids[0] if len(ids) == 1 else None


def client(key: str, proxy: str | None):
    import anthropic
    from anthropic import DefaultHttpxClient

    http = DefaultHttpxClient(proxy=proxy) if proxy else None
    return anthropic.Anthropic(api_key=key, http_client=http, timeout=180.0, max_retries=2)


def ask(cl, system: str, user_text: str) -> dict:
    """Один запрос к Claude со строгой JSON-схемой ответа (вызов — как у страницы шаббата)."""
    with cl.beta.messages.stream(
        model=MODEL,
        max_tokens=16000,
        system=system,
        output_config={"effort": "high", "format": {"type": "json_schema", "schema": SCHEMA}},
        messages=[{"role": "user", "content": user_text}],
        # Отказ модели — повтор на резервной внутри того же запроса (как у встреч).
        betas=[FALLBACK_BETA],
        fallbacks="default",
    ) as stream:
        msg = stream.get_final_message()
    if msg.stop_reason == "refusal":
        raise ParseError("Claude отказался разбирать этот текст")
    if msg.stop_reason == "max_tokens":
        raise ParseError("текст слишком длинный для одного разбора — раздели на части")
    text = next((b.text for b in msg.content if getattr(b, "type", "") == "text"), "")
    try:
        data = json.loads(text)
    except ValueError as e:
        raise ParseError(f"Claude ответил не JSON ({e})") from e
    data["_usage"] = llm.usage_of(msg, MODEL)
    return data


def to_ops(data: dict, index: dict, defaults: dict, source: str) -> list[dict]:
    """Ответ Claude — в операции Дел. Имена — в id по справочнику; не нашлось — пусто."""
    ops = []
    for t in data.get("tasks") or []:
        title = (t.get("title") or "").strip()
        if not title:
            continue
        task = {"title": title, "source": source}
        if (t.get("notes") or "").strip():
            task["notes"] = t["notes"].strip()
        pid = _one(index, "projects", t.get("project") or "") or defaults.get("project_id")
        if pid:
            task["project_id"] = str(pid)
        person = _one(index, "people", t.get("person") or "") or (defaults.get("person_id") if not t.get("person") else None)
        if person:
            task["person_id"] = str(person)
        ball = t.get("ball") or "mine"
        task["ball"] = ball if (ball == "mine" or person) else "mine"
        due = (t.get("due") or "").strip()
        if re.fullmatch(r"\d{4}-\d{2}-\d{2}", due):
            try:
                dt.date.fromisoformat(due)
                task["due_date"] = due
            except ValueError:
                pass
        if int(t.get("estimate_min") or 0) > 0:
            task["estimate_min"] = int(t["estimate_min"])
        if t.get("money") in ("paid", "potential", "none"):
            task["money"] = t["money"]
        if t.get("want"):
            task["want"] = True
        labels = [x for x in t.get("labels") or [] if x in index["labels"]]
        if labels:
            task["labels"] = labels
        ops.append({"op": "task.create", "task": task})
    for n in data.get("notes") or []:
        text = (n.get("text") or "").strip()
        if not text:
            continue
        item = {"at": dt.datetime.now(dt.timezone.utc).isoformat(), "kind": "note", "summary": text, "source": "raznoska"}
        pid = _one(index, "projects", n.get("project") or "") or defaults.get("project_id")
        if pid:
            item["project_id"] = str(pid)
        person = _one(index, "people", n.get("person") or "")
        if person:
            item["person_ids"] = [str(person)]
        ops.append({"op": "interaction.add", "data": item})
    return ops


def run(url: str, user: str, text: str, defaults: dict, key: str, proxy: str | None,
        via: str = "web", actor: str | None = None, source: str = "web", ask_fn=None) -> dict:
    """Разобрать и записать. ask_fn — подмена Claude в тестах."""
    text = (text or "").strip()
    if not text:
        raise ParseError("пусто — нечего разбирать")
    if len(text) > MAX_INPUT:
        raise ParseError(f"больше {MAX_INPUT} знаков — раздели на части")
    with db.session(url, user, via="parse") as conn:
        cat, index = catalog(conn)
        today = conn.execute("SELECT crm.today() AS d").fetchone()["d"]
        me = conn.execute("SELECT name, id = crm.owner_id() AS owner FROM crm.users WHERE id = %s", (user,)).fetchone()
    # Промпт написан от лица Саши; Наташе и Марианне — та же логика от их имени.
    speaker = "" if not me or me["owner"] else (
        f"\n\nСейчас говорит не Саша, а {me['name']}. Везде выше, где «Саша», читай «{me['name']}»: "
        "mine — дело этого человека, waiting и agenda — от его имени.")
    system = (SYSTEM.replace("{CATALOG}", cat)
              .replace("{TODAY}", f"{today.isoformat()}, {WD[today.weekday()]}")
              .replace("{SPEAKER}", speaker))
    user_text = f"Наговор:\n{text}"
    if ask_fn is None:
        if not key:
            raise ParseError("разбор Claude не настроен: нет ключа в dela.env")
        cl = client(key, proxy)
        ask_fn = lambda s, u: ask(cl, s, u)  # noqa: E731
    data = ask_fn(system, user_text)
    usage = data.get("_usage")
    llm.account(url, "parse", llm.cost(usage, MODEL), (usage or {}).get("model") or MODEL)
    ops = to_ops(data, index, defaults or {}, source)
    res = store.apply_ops(url, user, ops, via, actor) if ops else {"results": []}
    tasks = [r["task"] for r in res["results"] if r.get("ok") and r.get("task")]
    notes = [r["row"] for r in res["results"] if r.get("ok") and r.get("row")]
    errors = [r["error"] for r in res["results"] if not r.get("ok")]
    if errors:
        log.warning("разбор: не записались %d из %d: %s", len(errors), len(ops), errors[0])
    return {"tasks": tasks, "notes": notes, "errors": errors, "usage": data.get("_usage")}
