"""Разборщик Круга: дела без цели получают место в дереве жизни — сами, пачкой, по расписанию.

Владелец 12.10.2026: «если я сам не проставил направление, то разборщик это делает… сервер пускай смотрит
количество дел без направления и раз в полчаса отправляет. Если нет никаких дел без направлений, то ничего
не отправляет. Ночью раз в 8 часов: последний раз в 00 и первый раз в 8. В выходные раз в час».

Как устроено:
- Тик службы — раз в минуту (__main__). Слот — по Москве: будни — :00 и :30 с 08:00 до 23:30, выходные — :00,
  и последний слот суток — 00:00. Слот уже отработан (crm.state krug_auto) — ничего.
- Дела без цели (бриф 7.2, 13.10.2026: «для каждого дела автоматика предлагает родителя — конкретную цель
  дерева, а не только направление»): открытые свои дела без «Зачем», не в проекте клиента и не в сделке (у них
  место уже есть: клиент висит на цели целиком, у сделки «зачем» — сама сделка), которые разборщик ещё не
  смотрел в этом виде и о которых ещё не спрашивал. Нет таких — ни одного запроса, ни цента.
- Есть — один запрос Opus в Message Batches (−50 %, обычно минуты). Пачка в работе — крутится следующими
  тиками; не кончилась за два часа — отмена и тот же запрос напрямую.
- Ответ: уверен — цель («Зачем») ставится сама, а если ни одна цель не подходит — направление (дело висит на
  нём «без зачем»); от имени владельца со службой svc:krug в журнале. Не уверен — вопрос в «Подскажи» (правило
  Дел №5: «уверен — поставил, не уверен — подскажи»). Цели сам не заводит («Узлы создаёт Саша»), под целью с
  пометкой no_steps ничего не ставит, свои направления владельца не переписывает.
- Учится на правках владельца — так же, как разбор надиктовки, встреч и переписки (place.py, 13.10.2026): что он
  переставил после автоматики, что ответил на вопрос о месте, что поставил сам — примерами в запрос; недельные
  выводы из правок (Свод krug.lessons) — рядом с правилами.
- С 13.10.2026 место ставят уже при разборе (place.py): сюда доходят дела, которым модель разбора места не нашла,
  и всё, что завели руками без места.
- Кэша нет сознательно: запросы редкие (полчаса и реже), кэш на 5 минут не доживёт, а запись часового кэша
  дороже обычного входа, пока за час нет двух запросов.
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import time
import uuid
from zoneinfo import ZoneInfo

from psycopg.types.json import Jsonb

from . import db, krug, llm, place

log = logging.getLogger("dela.krug")

MODEL = "claude-opus-5-5"
EFFORT = "low"  # разметка по готовому справочнику; глубже — дороже без пользы (сначала на малом)
FALLBACK_BETA = "server-side-fallback-2026-07-01"
STATE_KEY = "krug_auto"
BATCH_MAX_AGE = 2 * 3600  # пачка не кончилась за два часа — тот же запрос напрямую
MAX_TASKS = 80  # за один запрос; остальные — следующим слотом
ASKED_DAYS = 14  # о деле уже спрашивали (вопрос висит, отклонён или погас) — не переспрашивать
SEEN_MAX = 2000  # сколько просмотренных дел помнить (id → версия); старое выпадает
MSK = ZoneInfo("Europe/Moscow")


# ── Расписание ─────────────────────────────────────────────────────────


def is_slot(t: dt.datetime) -> bool:
    """Минута t (по Москве) — слот разборщика? Будни: :00 и :30 с 8 до 23:30; выходные: :00; и 00:00."""
    if t.minute not in (0, 30):
        return False
    if t.hour == 0:
        return t.minute == 0
    if t.hour < 8:
        return False
    return t.minute == 0 or t.weekday() < 5


def last_slot(now: dt.datetime) -> dt.datetime:
    """Последний слот не позже now (по Москве, с точностью до минуты)."""
    t = now.astimezone(MSK).replace(second=0, microsecond=0)
    t -= dt.timedelta(minutes=t.minute % 30)
    while not is_slot(t):
        t -= dt.timedelta(minutes=30)
    return t


# ── Что размечать ──────────────────────────────────────────────────────


def version(t: dict) -> int:
    """Версия дела для памяти разборщика: поменялось дело или его строка Круга — смотреть заново."""
    return max(int(t.get("seq") or 0), int(t.get("krug_seq") or 0))


def candidates(conn, owner: str, seen: dict | None = None) -> list[dict]:
    """Открытые свои дела без цели, которые разборщик ещё не смотрел в этом виде и о которых не спрашивал.

    Не берёт: цели (узлы дерева), дела с «Зачем», дела проектов-клиентов (клиент висит на цели целиком) и дела
    сделок (их «зачем» — сама сделка)."""
    seen = seen or {}
    rows = conn.execute(
        "SELECT t.id, t.num, t.title, t.notes, t.project_name, t.project_kind, t.deal_name, t.person_name, t.grp, "
        "t.dirs, t.directions, t.seq, t.krug_seq FROM tasks.v_tasks t "
        "WHERE t.owner_id = %s AND t.status = 'open' AND NOT t.goal AND t.kind = 'task' AND t.why_id IS NULL AND t.deal_id IS NULL "
        "AND coalesce(t.project_kind, '') <> 'client' "
        "AND NOT EXISTS (SELECT 1 FROM tasks.suggestions s WHERE s.task_id = t.id AND s.source = 'krug' "
        "                AND (s.status = 'pending' OR s.created_at > now() - make_interval(days => %s))) "
        "ORDER BY t.created_at", (owner, ASKED_DAYS)).fetchall()
    return [r for r in rows if seen.get(str(r["id"])) != version(r)][:MAX_TASKS]


def goals(conn, owner: str) -> list[dict]:
    """Цели дерева — куда разборщик может повесить «Зачем»: открытые узлы, кроме тех, под которыми дел не заводят. С
    путём от направления: «Здоровье › Еда › Белок». Дело с делами внутри (правило 27) — не цель: до 15.10.2026 сюда шли
    и «дела с шагами», и разборщик мог бы повесить дело под клиентское дело как под цель."""
    rows = conn.execute(
        "SELECT id, num, title, why_id, dirs, goal, rhythm, no_steps FROM tasks.v_tasks WHERE owner_id = %s "
        "AND status = 'open' AND goal ORDER BY num", (owner,)).fetchall()
    by = {r["id"]: r for r in rows}
    out = []
    for r in rows:
        if r["no_steps"]:
            continue
        path, x = [], r
        for _ in range(krug.TREE_DEPTH):
            path.append(x["title"])
            x = by.get(x["why_id"])
            if not x:
                break
        out.append({"num": r["num"], "path": list(reversed(path)), "dirs": r["dirs"], "rhythm": r["rhythm"]})
    return out


def examples(url: str, owner: str, known: list[dict] | None = None) -> tuple[list[str], list[str]]:
    """Как владелец ставил сам и поправлял автоматику — строками (place.example_lines): (поправки и ответы, своё).
    Правки самих целей не в счёт: учимся месту дел. Ответ владельца — пример на будущее (бриф 7.2, §10)."""
    if known is None:
        known = krug.directions_of(url, owner)
    return place.example_lines(url, owner, known)


# ── Запрос ─────────────────────────────────────────────────────────────

# Правила разметки — запись Свода prompt.krug (правда — там: в ней примеры владельца с именами семьи, а репозиторий
# публичный; засевается из его файла командой krug-seed). Здесь — запас без имён, пока записи нет.
PROMPT_KEY = krug.PROMPT_KEY
RULES = """Ты находишь место дел владельца в его дереве жизни. Направления — параллельные стороны жизни, по которым
он идёт одновременно; у каждого — формула, смысл, границы (ниже, целиком). Под направлениями — его цели
(список ЦЕЛИ, с путём от направления): цель — состояние, к которому он идёт; цель с ритмом — практика.

Как решать:
- Место дела — по его «зачем»: к какой цели оно ведёт. Не по тому, кто попросил, и не по типу действия:
  покупка добавок — Здоровье, покупка одежды — Быт; бытовая просьба близкого — Быт.
- goal_num — номер самой точной цели из списка ЦЕЛИ, к которой дело явно ведёт; нет такой — null (дело
  повиснет прямо на направлении). Целей не выдумывай и новых не предлагай.
- directions — главное направление первым (то, где цель, или куда дело относится); второе — только если дело
  явно двигает и другую сторону. Обычно одно.
- Уверен в месте — sure: true, question пустой. Сомневаешься (две цели или два направления подходят одинаково) —
  sure: false, лучший вариант в goal_num и directions, в question — короткий вопрос владельцу («Театр — вдвоём
  (Пара) или для себя (Ум)?»).
- Отвечай по каждому делу из списка ДЕЛА, num — его номер."""


def rules(conn, owner: str) -> str:
    row = conn.execute("SELECT body FROM crm.svod WHERE owner_id = %s AND key = %s AND body IS NOT NULL",
                       (owner, PROMPT_KEY)).fetchone()
    return (row["body"] if row and (row["body"] or "").strip() else RULES)


def _dir_block(d: dict) -> str:
    lines = [f"{d.get('name')} (ключ {d['key']}" + (f", зона «{d['zone']}»" if d.get("zone") else "") + ")"]
    for k, title in (("formula", "Формула"), ("meaning", "Смысл"), ("includes", "Входит"), ("excludes", "Границы — не сюда"),
                     ("notes", "Ещё"), ("examples", "Примеры")):
        v = d.get(k)
        if isinstance(v, list):
            v = "; ".join(str(x) for x in v if str(x).strip())
        if v:
            lines.append(f"  {title}: {v}")
    return "\n".join(lines)


# Ответы кнопками — в коде, а не в Своде: правила Свода (prompt.krug) пишет владелец, а форма ответа — наша.
OPTIONS_RULE = """ВАРИАНТЫ ОТВЕТА (options): когда sure — false, дай 2–4 варианта, между которыми сомневаешься, — владелец
ответит одним нажатием кнопки. label — надпись на кнопке: коротко, название направления или цели, как он сам их
зовёт («Родительство», «Комфортный быт», «Внешний вид»); у каждого варианта — свои goal_num и directions; первым —
лучший (тот же, что в goal_num и directions самого дела). question — сам вопрос одной фразой («Стрижка с сыном —
это время с ним или бытовое дело?»). Когда sure — true, options пустой."""


def system_prompt(known: list[dict], rules_text: str = RULES) -> str:
    return rules_text + "\n\n" + OPTIONS_RULE + "\n\nНАПРАВЛЕНИЯ\n" + "\n\n".join(_dir_block(d) for d in known)


def user_prompt(tasks: list[dict], open_goals: list[dict], learned: tuple[list[str], list[str]], known: list[dict],
                lessons: list[str] | None = None) -> str:
    name = {d["key"]: d.get("name") or d["key"] for d in known}
    parts = []
    fixes, own = learned
    if lessons:
        parts.append("ВЫВОДЫ ИЗ ПРАВОК ВЛАДЕЛЬЦА (сильнее общих правил):\n" + "\n".join(f"- {x}" for x in lessons))
    if fixes:
        parts.append("КАК ВЛАДЕЛЕЦ ПОПРАВЛЯЛ АВТОМАТИКУ (свежие сверху — это сильнее общих правил):\n" + "\n".join(fixes))
    if own:
        parts.append("КАК ВЛАДЕЛЕЦ СТАВИЛ САМ (свежие сверху — это сильнее общих правил):\n" + "\n".join(own))
    if open_goals:
        parts.append("ЦЕЛИ (номер — путь от направления):\n" + "\n".join(
            f"#{g['num']} " + " › ".join([name.get((g["dirs"] or [""])[0], "?"), *g["path"]])
            + (f" (практика: {g['rhythm']})" if g.get("rhythm") else "") for g in open_goals))
    rows = []
    for t in tasks:
        bits = [f"#{t['num']} {t['title']}"]
        if t.get("project_name"):
            bits.append(f"проект {t['project_name']}" + (" (клиент ЗФ)" if t.get("project_kind") == "client" else ""))
        if t.get("deal_name"):
            bits.append(f"сделка {t['deal_name']}")
        if t.get("person_name"):
            bits.append(f"человек {t['person_name']}")
        if t.get("directions"):
            bits.append("владелец сам поставил: " + ", ".join(name.get(k, k) for k in t["directions"]))
        notes = " ".join(str(t.get("notes") or "").split())
        if notes:
            bits.append("заметки: " + (notes[:300] + "…" if len(notes) > 300 else notes))
        rows.append(" · ".join(bits))
    parts.append("ДЕЛА:\n" + "\n".join(rows))
    return "\n\n".join(parts)


def schema(known: list[dict]) -> dict:
    keys = [d["key"] for d in known]
    return {
        "type": "object",
        "additionalProperties": False,
        "required": ["items"],
        "properties": {"items": {"type": "array", "items": {
            "type": "object",
            "additionalProperties": False,
            "required": ["num", "goal_num", "directions", "sure", "question", "options"],
            "properties": {
                "num": {"type": "integer"},
                "goal_num": {"type": ["integer", "null"]},
                "directions": {"type": "array", "items": {"type": "string", "enum": keys}},
                "sure": {"type": "boolean"},
                "question": {"type": "string"},
                # Ответы кнопками (13.10.2026): варианты, между которыми модель сомневается.
                "options": {"type": "array", "items": {
                    "type": "object",
                    "additionalProperties": False,
                    "required": ["label", "goal_num", "directions"],
                    "properties": {
                        "label": {"type": "string"},
                        "goal_num": {"type": ["integer", "null"]},
                        "directions": {"type": "array", "items": {"type": "string", "enum": keys}},
                    }}},
            }}}},
    }


def params(system: str, user: str, known: list[dict]) -> dict:
    return {
        "model": MODEL,
        "max_tokens": 16000,
        "system": system,
        "output_config": {"effort": EFFORT, "format": {"type": "json_schema", "schema": schema(known)}},
        "messages": [{"role": "user", "content": user}],
    }


def _parse(msg) -> list[dict]:
    if getattr(msg, "stop_reason", None) in ("refusal", "max_tokens"):
        raise RuntimeError(f"разборщик Круга: ответ оборван ({msg.stop_reason})")
    text = next((b.text for b in msg.content if getattr(b, "type", "") == "text"), "")
    return list(json.loads(text).get("items") or [])


# ── Ответ — в Дела ─────────────────────────────────────────────────────


def apply(url: str, owner: str, slot: str, items: list[dict], known: list[dict], seen: dict | None = None) -> dict:
    """Уверенное — ставится само (task.set от владельца, в журнале svc:krug); остальное — вопрос в «Подскажи».

    Цель есть — ставится «Зачем»; свои направления дела, кроме главного направления цели, остаются метками.
    Цели нет — направление, если своих у дела нет и проект не даёт того же. Дело успели поставить руками,
    закрыть или удалить — не трогаем: человек сильнее разборщика. seen — память просмотренных (id → версия
    дела после ответа): дело, которое смотрели и оставили как есть, не уходит в следующий запрос."""
    from . import store

    keys = {d["key"] for d in known}
    name = {d["key"]: d.get("name") or d["key"] for d in known}
    ops, looked = [], []
    with db.session(url, owner, via="krug") as conn:
        paths = {g["num"]: g for g in goals(conn, owner)}
        for it in items:
            dirs = [k for k in it.get("directions") or [] if k in keys][:krug.DIRS_MAX]
            t = conn.execute("SELECT id, num, title, status, goal, why_id, directions, dirs FROM tasks.v_tasks "
                             "WHERE num = %s AND owner_id = %s", (it.get("num"), owner)).fetchone()
            if not t or t["status"] != "open" or t["goal"] or t["why_id"]:
                continue
            looked.append(t["id"])
            g = None
            if it.get("goal_num") in paths:
                g = conn.execute("SELECT id, title, dirs FROM tasks.v_tasks WHERE num = %s AND owner_id = %s AND id <> %s",
                                 (it["goal_num"], owner, t["id"])).fetchone()
            own = list(t["directions"] or [])
            if g:
                main = (g["dirs"] or [None])[0]
                tags = [k for k in own if k != main]
                patch = {"why_id": str(g["id"]), **({"directions": tags} if tags != own else {})}
            elif dirs and not own and (t["dirs"] or [None])[0] != dirs[0]:
                patch = {"directions": dirs}
            else:
                continue
            if it.get("sure"):
                ops.append({"op": "task.set", "op_id": str(uuid.uuid4()), "id": str(t["id"]), "set": patch})
                continue
            p = paths.get(it.get("goal_num")) if g else None
            where = " › ".join([name.get((p["dirs"] or [""])[0], "?"), *p["path"]]) if p else ", ".join(name[k] for k in dirs)
            q = (it.get("question") or "").strip() or f"«{t['title']}» — {where}?"
            # Варианты кнопками (13.10.2026): у каждого своё место — цель («Зачем» и метки) или направление без цели.
            opts = []
            for o in it.get("options") or []:
                od = [k for k in o.get("directions") or [] if k in keys][:krug.DIRS_MAX]
                og = conn.execute("SELECT id, dirs FROM tasks.v_tasks WHERE num = %s AND owner_id = %s AND id <> %s",
                                  (o["goal_num"], owner, t["id"])).fetchone() if o.get("goal_num") in paths else None
                if og:
                    om = (og["dirs"] or [None])[0]
                    opts.append({"label": o.get("label"), "why_id": str(og["id"]), "directions": [k for k in own if k != om]})
                elif od:
                    opts.append({"label": o.get("label"), "why_id": None, "directions": od})
            ops.append({"op": "suggestion.create", "op_id": str(uuid.uuid4()), "suggestion": {
                "kind": "update", "task_id": str(t["id"]), "source": "krug", "batch_ref": f"krug:{slot}",
                "batch_title": "Место в дереве", "payload": {**patch, "ask": q, **({"goal_path": where} if g else {}),
                                                              **({"directions": dirs} if not g else {}),
                                                              **({"options": opts} if len(opts) >= 2 else {})}}})
    res = store.apply_ops(url, owner, ops, "krug", "svc:krug")["results"] if ops else []
    bad = [r.get("error") for r in res if not r.get("ok")]
    if bad:
        log.warning("Круг: не записалось %d из %d: %s", len(bad), len(ops), bad[0])
    if seen is not None and looked:
        with db.session(url, owner, via="krug") as conn:
            for r in conn.execute("SELECT id, seq, krug_seq FROM tasks.v_tasks WHERE id = ANY(%s)", (looked,)).fetchall():
                seen.pop(str(r["id"]), None)
                seen[str(r["id"])] = version(r)
        for k in list(seen)[:-SEEN_MAX]:
            seen.pop(k, None)
    return {"set": sum(1 for o, r in zip(ops, res) if r.get("ok") and o["op"] == "task.set"),
            "asked": sum(1 for o, r in zip(ops, res) if r.get("ok") and o["op"] == "suggestion.create"), "errors": bad}


# ── Тик ────────────────────────────────────────────────────────────────


def _state(conn) -> dict:
    row = conn.execute("SELECT value FROM crm.state WHERE key = %s", (STATE_KEY,)).fetchone()
    return dict(row["value"]) if row else {}


def _save(url: str, value: dict) -> None:
    with db.session(url, "system", "svc:krug", "krug") as conn:
        conn.execute("INSERT INTO crm.state (key, value) VALUES (%s, %s) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value",
                     (STATE_KEY, Jsonb(value)))


def _account(url: str, msg, batch: bool) -> float:
    usage = llm.usage_of(msg, MODEL)
    usd = llm.cost(usage, MODEL, url) * (0.5 if batch else 1.0)
    llm.account(url, "krug", usd, usage.get("model") or MODEL, usage=usage, batch=batch)
    return usd


def tick(url: str, key: str, proxy: str | None, now: dt.datetime | None = None, client=None) -> dict | None:
    """Один тик (раз в минуту): досмотреть пачку в работе или, если пришёл слот, отправить новую.

    client — подмена клиента Anthropic в тестах. Возвращает, что сделано, или None — делать было нечего."""
    now = now or dt.datetime.now(dt.timezone.utc)
    with db.session(url, "system", via="krug") as conn:
        owner = conn.execute("SELECT crm.owner_id() AS id").fetchone()["id"]
        st = _state(conn)
    if not owner or not key:
        return None
    if client is None:
        from . import guard
        client = guard.client(url, key, proxy, "krug")  # защита от петли (аудит №12): стоит — тик пропускается

    if st.get("batch"):
        b = st["batch"]
        info = client.messages.batches.retrieve(b["id"])
        if info.processing_status != "ended":
            if time.time() - b["at"] < BATCH_MAX_AGE:
                return None
            # Пачка застряла — отмена и тот же запрос напрямую (с резервной моделью на отказ).
            try:
                client.messages.batches.cancel(b["id"])
            except Exception:  # noqa: BLE001
                log.warning("Круг: пачку %s отменить не вышло", b["id"])
            msg = client.beta.messages.create(**b["params"], betas=[FALLBACK_BETA], fallbacks="default")
            usd = _account(url, msg, batch=False)
            seen = {**(st.get("seen") or {}), **(b.get("looked") or {})}  # отправленное — просмотрено
            out = apply(url, owner, b["slot"], _parse(msg), krug.directions_of(url, owner), seen)
            _save(url, {**st, "seen": seen, "batch": None, "last": {"slot": b["slot"], "direct": True, "usd": round(usd, 4), **out}})
            log.info("Круг: пачка застряла — напрямую: поставил %d, спросил %d ($%.3f)", out["set"], out["asked"], usd)
            return out
        res = next(iter(client.messages.batches.results(b["id"])), None)
        if res is None or res.result.type != "succeeded":
            log.warning("Круг: пачка %s — %s", b["id"], res.result.type if res else "без ответа")
            _save(url, {**st, "batch": None, "last": {"slot": b["slot"], "error": res.result.type if res else "пусто"}})
            return {"error": True}
        usd = _account(url, res.result.message, batch=True)
        seen = {**(st.get("seen") or {}), **(b.get("looked") or {})}  # отправленное — просмотрено
        out = apply(url, owner, b["slot"], _parse(res.result.message), krug.directions_of(url, owner), seen)
        _save(url, {**st, "seen": seen, "batch": None, "last": {"slot": b["slot"], "usd": round(usd, 4), **out}})
        log.info("Круг: поставил %d, спросил %d ($%.3f)", out["set"], out["asked"], usd)
        return out

    slot = last_slot(now)
    key_ = slot.strftime("%Y-%m-%dT%H:%M")
    # Слот уже отработан — или служба поднялась посреди ночи: старый слот не догоняем.
    if st.get("slot") == key_ or now.astimezone(MSK) - slot > dt.timedelta(minutes=29):
        return None
    with db.session(url, owner, via="krug") as conn:
        known = krug.directions(conn, owner)
        tasks = candidates(conn, owner, st.get("seen")) if known else []
        open_goals = goals(conn, owner) if tasks else []
        rules_text = rules(conn, owner)
        learnt = place.lessons(conn, owner)
    if not tasks:
        _save(url, {**st, "slot": key_})
        return None
    p = params(system_prompt(known, rules_text), user_prompt(tasks, open_goals, examples(url, owner, known), known, learnt), known)
    b = client.messages.batches.create(requests=[{"custom_id": "krug", "params": p}])
    looked = {str(t["id"]): version(t) for t in tasks}  # ответ про дело не пришёл — всё равно не слать его снова
    _save(url, {**st, "slot": key_, "batch": {"id": b.id, "at": time.time(), "slot": key_, "params": p, "n": len(tasks),
                                              "looked": looked}})
    log.info("Круг: %d дел без цели — пачка %s", len(tasks), b.id)
    return {"sent": len(tasks)}
