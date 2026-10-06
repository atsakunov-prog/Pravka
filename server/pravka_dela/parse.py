"""Разбор свободного текста в дела — Claude, как Разноска в Правке.

Владелец пишет или надиктовывает как есть («Наташе сверку до пятницы, жду от
Ивана модель, обсудить с Толкушкиным фонды»), Claude режет это на дела по
общему с телефоном промпту (server/contract/prompts/raznoska.txt) со справочником
проектов, людей и меток — только тех, что видит этот пользователь — и его местами
для напоминаний «когда приеду». Кнопку жмёт сам человек, поэтому дела заводятся
сразу, без «Нового» (туда идёт только автоматика); отмена — одним движением в вебе.
Факты, которые не дела, ложатся заметками в хронологию.

К API — через xray бота (из России API Anthropic закрыт), ключ —
ANTHROPIC_API_KEY в dela.env (установщик берёт ключ встреч).
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import re
import uuid
from pathlib import Path

from . import db, llm, remind, store

log = logging.getLogger("dela.parse")

MODEL = "claude-opus-5-5"
FALLBACK_BETA = "server-side-fallback-2026-07-01"
MAX_INPUT = 20_000
WD = ["понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"]

# Правила разбора — один файл на телефон и сервер (06.10.2026): server/contract/prompts/raznoska.txt
# и схема ответа рядом. Телефон берёт его в APK, сервер читает с диска при каждом разборе — правка
# правил меняет разбор и там, и там. Служба работает из клона репозитория, контракт — рядом с кодом.
PROMPTS = Path(__file__).resolve().parents[1] / "contract" / "prompts"


def template() -> str:
    """Текст правил с подстановками {CATALOG}, {PLACES}, {TODAY}, {NOW}."""
    return (PROMPTS / "raznoska.txt").read_text(encoding="utf-8")


def schema() -> dict:
    """Схема ответа (structured output) — та же, по которой телефон разбирает ответ."""
    return json.loads((PROMPTS / "raznoska.schema.json").read_text(encoding="utf-8"))


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
        output_config={"effort": "high", "format": {"type": "json_schema", "schema": schema()}},
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


def to_ops(data: dict, index: dict, defaults: dict, source: str, places: list[str] | None = None,
           ref: str | None = None) -> list[dict]:
    """Ответ Claude — в операции Дел. Имена — в id по справочнику; не нашлось — пусто.

    Напоминание — как у телефона (parseTasksDela): время — местное «ГГГГ-ММ-ДД ЧЧ:ММ» по
    Москве в timestamptz; место — только из мест телефона (places), иное не теряется, а
    ложится строкой в заметки дела. ref — метка наговорки (source_ref дел, у заметок — ref:номер),
    по ней «Новое» показывает, что из сказанного куда попало.
    """
    ops = []
    for t in data.get("tasks") or []:
        title = (t.get("title") or "").strip()
        if not title:
            continue
        task = {"title": title, "source": source, **({"source_ref": ref} if ref else {})}
        notes = (t.get("notes") or "").strip()
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
        # Время срока — только при сроке: due_time без due_date база не примет.
        hm = remind.norm_time(t.get("due_time"))
        if hm and task.get("due_date"):
            task["due_time"] = hm
        at = remind.local_iso(t.get("remind_at"))
        said = (t.get("remind_place") or "").strip()
        place = "" if at else remind.match_place(said, places or [])
        if at:
            task["remind_at"] = at
        elif place:
            task["remind_place"] = place
        elif said and said.lower() not in notes.lower():
            notes = (notes + "\n" + remind.place_note(said)).strip()
        if notes:
            task["notes"] = notes
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
    for i, n in enumerate(data.get("notes") or []):
        text = (n.get("text") or "").strip()
        if not text:
            continue
        item = {"at": dt.datetime.now(dt.timezone.utc).isoformat(), "kind": "note", "summary": text, "source": "raznoska"}
        if ref:
            item["source_ref"] = f"{ref}:{i}"
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
        clock = conn.execute(
            "SELECT crm.today() AS d, to_char(now() AT TIME ZONE 'Europe/Moscow', 'HH24:MI') AS hm").fetchone()
        today = clock["d"]
        me = conn.execute("SELECT name, id = crm.owner_id() AS owner FROM crm.users WHERE id = %s", (user,)).fetchone()
        places = remind.places_of(conn, user)
    # Промпт написан от лица Саши; Наташе и Марианне — та же логика от их имени.
    speaker = "" if not me or me["owner"] else (
        f"\n\nСейчас говорит не Саша, а {me['name']}. Везде выше, где «Саша», читай «{me['name']}»: "
        "mine — дело этого человека, waiting и agenda — от его имени.")
    system = (template().replace("{CATALOG}", cat)
              .replace("{PLACES}", remind.places_block(places))
              .replace("{TODAY}", f"{today.isoformat()}, {WD[today.weekday()]}")
              .replace("{NOW}", clock["hm"])) + speaker
    user_text = f"Наговор:\n{text}"
    if ask_fn is None:
        if not key:
            raise ParseError("разбор Claude не настроен: нет ключа в dela.env")
        cl = client(key, proxy)
        ask_fn = lambda s, u: ask(cl, s, u)  # noqa: E731
    data = ask_fn(system, user_text)
    usage = data.get("_usage")
    llm.account(url, "parse", llm.cost(usage, MODEL), (usage or {}).get("model") or MODEL)
    # Наговорка — строкой рядом с делами: «Новое» показывает, что сказано и куда что попало.
    ref = f"parse:{uuid.uuid4()}"
    ops = to_ops(data, index, defaults or {}, source, places, ref)
    if ops:
        ops.insert(0, {"op": "dictation.add", "dictation": {"id": ref, "text": text, "source": {"app": "phone", "web": "web"}.get(via, "bot")}})
    res = store.apply_ops(url, user, ops, via, actor) if ops else {"results": []}
    tasks = [r["task"] for r in res["results"] if r.get("ok") and r.get("task")]
    notes = [r["row"] for r in res["results"] if r.get("ok") and r.get("row")]
    errors = [r["error"] for r in res["results"] if not r.get("ok")]
    if errors:
        log.warning("разбор: не записались %d из %d: %s", len(errors), len(ops), errors[0])
    return {"tasks": tasks, "notes": notes, "errors": errors, "usage": data.get("_usage")}
