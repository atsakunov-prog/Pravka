"""Место дела в дереве жизни — сразу при разборе (13.10.2026).

Владелец 13.10.2026, на другой день после дерева целей (7.2): «нужно брать текущее Дерево жизни и смотреть, куда
дела попадают… если я надиктовываю в телефоне… в разборах встречи… каждое дело, которое он добавляет в новое,
чтобы он тоже это встраивал в Дерево жизни сразу… и чтобы он сам учился на этом».

Как устроено:
- Одно описание дерева на всех — блок «дерево жизни» (block): как раскладывать (правила разборщика Круга из Свода,
  без формы его ответа), выводы из правок владельца, направления с границами, цели деревом с номерами и свежие
  правки владельца примерами. Его вставляют в свой промпт все, кто заводит дела: разбор надиктовки на сервере
  (parse.py) и в Правке на телефоне, сверка встреч и разноска переписки (служба встреч, дайджест). Модель
  называет у каждого нового дела goal — номер цели или 0.
- Транспорт — Свод, запись krug.place: Дела сами переписывают её, когда дерево, правила или примеры поменялись
  (refresh из тика службы, не чаще раза в REFRESH_EVERY). Телефон получает её синком, службы на компе — тем же
  /api/sync. Свод отдаёт только свои строки: дерево видит только хозяин, как и сам Круг.
- Номер цели едет в операции полем why_auto (task.create, предложение «завести»). Сервер проверяет его сам
  (resolve): цель своя и открыта, под ней можно ставить дела, дело не цель, не клиентское и не в сделке (их место —
  клиент, висящий на цели целиком, и сама сделка). Не подошло — дело заводится без места и без ошибки: разборщик
  Круга (krug_auto) доберёт его по расписанию, а не уверен — спросит.
- Место, которое выбрала модель, пишется в журнал службой svc:krug, а не владельцем, даже если дело надиктовал он
  сам: иначе учёба училась бы на своих же догадках.
- Учёба (learned): что владелец переставил после автоматики («стояло в … — переставил в …»), что ответил на вопрос
  о месте и что поставил сам — свежие сверху, примерами в блок и в разборщик: правка доходит до всех разборов с
  ближайшим refresh. Раз в неделю (tune) правки сводятся в выводы — Свод krug.lessons, пачкой Opus: примеры —
  короткая память, выводы — долгая.
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import time
from zoneinfo import ZoneInfo

from psycopg.types.json import Jsonb

from . import db, krug, llm

log = logging.getLogger("dela.place")

PLACE_KEY = "krug.place"      # Свод: блок «дерево жизни» для промптов разбора (пишет refresh)
LESSONS_KEY = "krug.lessons"  # Свод: выводы из правок владельца (пишет tune раз в неделю)
ACTOR = "svc:krug"            # так в журнале — место, которое выбрала модель
REFRESH_EVERY = 300           # секунд между пересборками блока
EXAMPLES = 40                 # правок и решений владельца в блок
FIXES = 25                    # из них — поправок автоматики и ответов на вопросы (они учат сильнее)
HISTORY_DAYS = 180            # сколько журнала смотреть
LESSONS_MAX = 20              # выводов в записи
MSK = ZoneInfo("Europe/Moscow")

# Строки правил разборщика о форме его ответа (goal_num, directions, sure, question) — не для чужого промпта.
FORMAT_WORDS = ("goal_num", "sure", "question", "directions —", "Отвечай по")

HEAD = """ДЕРЕВО ЖИЗНИ ВЛАДЕЛЬЦА — место каждого нового дела (поле goal)
Под направлениями жизни — его цели: цель — состояние, к которому он идёт; цель с ритмом — практика. Новое дело —
шаг к какой-то цели, его «зачем». goal — номер этой цели из списка ЦЕЛИ ниже: самой точной, к которой дело явно
ведёт (подцель лучше цели над ней). 0 — когда:
- дело про клиента, его сделку или проект: место у них уже есть, клиент висит на цели целиком;
- ни одна цель явно не подходит или две подходят одинаково: такое дело позже поставит разборщик Круга или спросит
  владельца. Не угадывай — лучше 0, чем не туда.
Целей не выдумывай. Под «ориентиром» дел не ставь."""


def _int(v) -> int:
    try:
        return int(str(v or "").strip().lstrip("#") or 0)
    except ValueError:
        return 0


# ── Проверка номера ────────────────────────────────────────────────────


def resolve(conn, task: dict, num) -> str | None:
    """id цели, которую модель назвала делу номером (why_auto); None — не ставить.

    Не ставим: дело — само цель; у дела уже есть «Зачем»; дело в сделке или в проекте-клиенте (клиент висит на
    цели целиком, у сделки «зачем» — она сама); цель чужая, закрыта, «ориентир» (no_steps) или это не цель и не
    дело с шагами (то же, что разборщик Круга видит в своём списке ЦЕЛИ)."""
    n = _int(num)
    if n <= 0:
        return None
    t = conn.execute("SELECT goal, why_id, deal_id, project_kind, owner_id FROM tasks.v_tasks WHERE id = %s",
                     (task["id"],)).fetchone()
    if not t or t["goal"] or t["why_id"] or t["deal_id"] or t["project_kind"] == "client":
        return None
    g = conn.execute(
        "SELECT id FROM tasks.v_tasks WHERE num = %s AND owner_id = %s AND status = 'open' AND id <> %s "
        "AND NOT no_steps AND (goal OR steps_open > 0)", (n, t["owner_id"], task["id"])).fetchone()
    return g["id"] if g else None


def write_auto(conn, user: str, task: dict, num) -> bool:
    """Поставить делу «Зачем» по номеру, который назвала модель. Тихо: не вышло — дело остаётся без места.

    Только хозяину дела (Круг личный). В журнал — службой ACTOR, если пишет не служба: место выбрал не владелец."""
    if user not in (task.get("owner_id"), "system"):
        return False
    goal = resolve(conn, task, num)
    if not goal:
        return False
    was = conn.execute("SELECT current_setting('dela.actor', true) AS a").fetchone()["a"] or ""
    mine = not was.startswith(("svc:", "cli:"))
    if mine:
        conn.execute("SELECT set_config('dela.actor', %s, true)", (ACTOR,))
    try:
        conn.execute("INSERT INTO tasks.krug (id, kind, owner_id, why_id) VALUES (%s, 'task', %s, %s) "
                     "ON CONFLICT (id) DO UPDATE SET why_id = EXCLUDED.why_id", (task["id"], task["owner_id"], goal))
    finally:
        if mine:
            conn.execute("SELECT set_config('dela.actor', %s, true)", (was,))
    return True


# ── Дерево ─────────────────────────────────────────────────────────────


def targets(conn, owner: str) -> list[dict]:
    """Узлы дерева, на которые вешают дела: открытые цели и дела с шагами. Ориентиры (no_steps) — тоже: под ними
    бывают подцели; сами они в блоке помечены, а resolve под них не ставит."""
    return conn.execute(
        "SELECT id, num, title, why_id, dirs, goal, rhythm, no_steps FROM tasks.v_tasks WHERE owner_id = %s "
        "AND status = 'open' AND (goal OR steps_open > 0) ORDER BY num", (owner,)).fetchall()


def paths(rows: list[dict], known: list[dict]) -> dict:
    """id узла → «Направление › Цель › Подцель»."""
    by = {r["id"]: r for r in rows}
    name = {d["key"]: d.get("name") or d["key"] for d in known}
    out = {}
    for r in rows:
        chain, x, seen = [], r, set()
        while x and x["id"] not in seen and len(chain) < krug.TREE_DEPTH:
            seen.add(x["id"])
            chain.append(x["title"])
            x = by.get(x["why_id"])
        root = name.get((r["dirs"] or [None])[0])
        out[r["id"]] = " › ".join(([root] if root else []) + list(reversed(chain)))
    return out


def tree_lines(rows: list[dict], known: list[dict]) -> list[str]:
    """Цели деревом: направление, под ним цели с номерами, подцели — отступом. Ориентир без подцелей не нужен."""
    by = {r["id"]: r for r in rows}
    kids: dict = {}
    for r in rows:
        kids.setdefault(r["why_id"] if r["why_id"] in by else ("dir", (r["dirs"] or [None])[0]), []).append(r)
    out: list[str] = []
    seen: set = set()

    def walk(items: list[dict], depth: int) -> None:
        for r in sorted(items, key=lambda r: r["num"]):
            if r["id"] in seen or depth > krug.TREE_DEPTH:
                continue
            seen.add(r["id"])
            below = kids.get(r["id"]) or []
            if r["no_steps"] and not below:
                continue
            mark = (f" — практика: {r['rhythm']}" if r.get("rhythm") else "") + (" — ориентир, дел не ставить" if r["no_steps"] else "")
            out.append("  " * depth + f"#{r['num']} {r['title']}{mark}")
            walk(below, depth + 1)

    for d in known:
        items = kids.get(("dir", d["key"]))
        if items:
            out.append(d.get("name") or d["key"])
            walk(items, 1)
    loose = kids.get(("dir", None))
    if loose:
        out.append("Без направления")
        walk(loose, 1)
    return out


def dir_lines(known: list[dict]) -> list[str]:
    """Направления коротко: формула, что входит, где граница, примеры (смысл и ключи разбору не нужны)."""
    out = []
    for d in known:
        out.append(f"{d.get('name') or d['key']}" + (f" — {d['formula']}" if d.get("formula") else ""))
        for k, title in (("includes", "входит"), ("excludes", "не сюда"), ("notes", "ещё"), ("examples", "примеры")):
            v = d.get(k)
            if isinstance(v, list):
                v = "; ".join(str(x).strip().rstrip(".;") for x in v if str(x).strip())
            if v:
                out.append(f"  {title}: {v}")
    return out


def rules_for_place(text: str) -> str:
    """Правила разборщика Круга без формы его ответа: только пункты «куда что ставить».

    Пункт — строка с «-», «—» или «•» в начале и её продолжения с отступом. Пунктов нет (правила переписаны
    иначе) — текст целиком: смысл важнее формы."""
    items: list[list[str]] = []
    for line in (text or "").splitlines():
        if line[:1] in ("-", "—", "•"):
            items.append([line])
        elif line[:1].isspace() and line.strip() and items:
            items[-1].append(line)
        else:
            items.append([])
    keep = ["\n".join(i) for i in items if i and not any(w in " ".join(i) for w in FORMAT_WORDS)]
    return "\n".join(keep) if keep else (text or "").strip()


# ── Учёба ──────────────────────────────────────────────────────────────


def _auto(actor: str | None) -> bool:
    return str(actor or "").startswith(("svc:", "cli:"))


def _same(a: dict, b: dict) -> bool:
    """Одно и то же место: цель решает, если она есть; иначе — направления."""
    if a.get("why") or b.get("why"):
        return a.get("why") == b.get("why")
    return list(a.get("dirs") or []) == list(b.get("dirs") or [])


def learned(url: str, owner: str, since: dt.datetime | None = None) -> list[dict]:
    """Правки и решения владельца о месте дел, свежие сверху.

    kind fix — место ставила автоматика (разборщик, разбор встречи или надиктовки, засев), владелец переставил:
    was — где стояло, now — куда. kind own — поставил сам, автоматики до него не было. kind answer — ответ на
    вопрос «Подскажи» о месте: отклонил с причиной (reason). Места — {why: id} или {dirs: [ключи]}; цели самих
    по себе не в счёт: учимся месту дел. since — только то, что случилось позже (для недельных выводов)."""
    since = since or dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=HISTORY_DAYS)
    with db.session(url, "system", via="krug") as conn:
        hist = conn.execute(
            "SELECT h.entity_id, h.actor, h.at, h.after FROM crm.history h "
            "JOIN tasks.krug k ON k.id::text = h.entity_id AND k.kind = 'task' AND NOT k.goal AND k.owner_id = %s "
            "WHERE h.entity = 'tasks.krug' AND h.at > now() - make_interval(days => %s) "
            "AND (h.after ? 'why_id' OR h.after ? 'directions') ORDER BY h.at, h.id",
            (owner, HISTORY_DAYS)).fetchall()
        answers = conn.execute(
            "SELECT s.task_id, s.payload ->> 'ask' AS ask, s.reason, s.decided_at AS at FROM tasks.suggestions s "
            "WHERE s.source = 'krug' AND s.status = 'rejected' AND coalesce(s.reason, '') <> '' AND s.decided_by = %s "
            "AND s.decided_at > %s ORDER BY s.decided_at", (owner, since)).fetchall()
    out: list[dict] = []
    by: dict[str, list] = {}
    for h in hist:
        by.setdefault(h["entity_id"], []).append(h)
    for tid, events in by.items():
        place: dict = {}
        who: dict = {}  # кто последним ставил why и dirs
        last = None
        for h in events:
            before, before_who = dict(place), dict(who)
            for col, k in (("why_id", "why"), ("directions", "dirs")):
                if col in h["after"]:
                    v = h["after"][col]
                    place[k] = list(v or []) if k == "dirs" else v
                    who[k] = h["actor"]
            if not _auto(h["actor"]):
                last = (before, before_who, dict(place), h["at"])
        if not last or last[3] <= since:
            continue
        was, was_who, now, at = last
        now = {k: v for k, v in now.items() if v}
        was = {k: v for k, v in was.items() if v}
        if not now or (was and _same(was, now)):
            continue
        if was and _auto(was_who.get("why" if was.get("why") else "dirs")):
            out.append({"kind": "fix", "task": tid, "was": was, "now": now, "at": at})
        else:
            out.append({"kind": "own", "task": tid, "now": now, "at": at})
    for a in answers:
        out.append({"kind": "answer", "task": str(a["task_id"]), "ask": a["ask"] or "", "reason": a["reason"], "at": a["at"]})
    return sorted(out, key=lambda e: e["at"], reverse=True)


def _where(p: dict, path: dict, known: list[dict], titles: dict) -> str:
    """Место словами: путь цели или направления."""
    name = {}
    for d in known:
        for n in (d["key"], *(d.get("aliases") or [])):
            name[krug._norm(n)] = d.get("name") or d["key"]
    if p.get("why"):
        return path.get(p["why"]) or titles.get(p["why"], {}).get("title") or ""
    dirs = [name.get(krug._norm(k)) for k in p.get("dirs") or []]
    return ", ".join(x for x in dirs if x)


def example_lines(url: str, owner: str, known: list[dict], rows: list[dict] | None = None,
                  events: list[dict] | None = None) -> tuple[list[str], list[str]]:
    """(поправки и ответы, поставленное самим) строками для промпта: «название»: где стояло — куда переставил."""
    events = learned(url, owner) if events is None else events
    fixes = [e for e in events if e["kind"] != "own"][:FIXES]
    own = [e for e in events if e["kind"] == "own"][:EXAMPLES - len(fixes)]
    ids = {e["task"] for e in fixes + own} | {p.get("why") for e in fixes + own for p in (e.get("was") or {}, e.get("now") or {})}
    ids.discard(None)
    with db.session(url, owner, via="krug") as conn:
        rows = targets(conn, owner) if rows is None else rows
        titles = {str(r["id"]): r for r in conn.execute(
            "SELECT id, title FROM tasks.tasks WHERE id::text = ANY(%s)", (list(ids),)).fetchall()} if ids else {}
    path = {str(k): v for k, v in paths(rows, known).items()}

    def line(e: dict) -> str | None:
        title = (titles.get(e["task"]) or {}).get("title")
        if not title:
            return None
        if e["kind"] == "answer":
            q = f"спросили «{e['ask']}» — " if e["ask"] else ""
            return f"— «{title}»: {q}владелец ответил: «{e['reason']}»"
        now = _where({k: str(v) if k == "why" else v for k, v in e["now"].items()}, path, known, titles)
        if not now:
            return None
        if e["kind"] == "fix":
            was = _where({k: str(v) if k == "why" else v for k, v in e["was"].items()}, path, known, titles)
            return f"— «{title}»: стояло в {was} — переставил в {now}" if was else f"— «{title}»: {now}"
        return f"— «{title}»: {now}"

    return [x for x in map(line, fixes) if x], [x for x in map(line, own) if x]


# ── Блок ───────────────────────────────────────────────────────────────


def lessons(conn, owner: str) -> list[str]:
    row = conn.execute("SELECT body FROM crm.svod WHERE owner_id = %s AND key = %s", (owner, LESSONS_KEY)).fetchone()
    return [x.strip().lstrip("-—• ").strip() for x in ((row or {}).get("body") or "").splitlines() if x.strip()]


def block(url: str, owner: str) -> str:
    """Блок «дерево жизни» для промптов разбора. Круг не настроен (нет направлений) или целей нет — пусто."""
    from . import krug_auto

    with db.session(url, owner, via="krug") as conn:
        known = krug.directions(conn, owner)
        if not known:
            return ""
        rows = targets(conn, owner)
        rules = krug_auto.rules(conn, owner)
        learnt = lessons(conn, owner)
    tree = tree_lines(rows, known)
    if not tree:
        return ""
    fixes, own = example_lines(url, owner, known, rows)
    parts = [HEAD,
             "КАК ВЛАДЕЛЕЦ РАСКЛАДЫВАЕТ (правила его разборщика Круга):\n" + rules_for_place(rules),
             "НАПРАВЛЕНИЯ (что входит и где граница):\n" + "\n".join(dir_lines(known)),
             "ЦЕЛИ (номер и цель; отступ — подцель):\n" + "\n".join(tree)]
    if learnt:
        parts.append("ВЫВОДЫ ИЗ ЕГО ПРАВОК (сильнее общих правил):\n" + "\n".join(f"- {x}" for x in learnt))
    if fixes:
        parts.append("КАК ОН ПОПРАВЛЯЛ АВТОМАТИКУ (свежие сверху — сильнее общих правил):\n" + "\n".join(fixes))
    if own:
        parts.append("КАК ОН СТАВИЛ САМ (свежие сверху):\n" + "\n".join(own))
    return "<дерево жизни>\n" + "\n\n".join(parts) + "\n</дерево жизни>"


_refreshed = {"at": -REFRESH_EVERY}


def refresh(url: str, force: bool = False) -> bool:
    """Блок — в Свод (krug.place), если поменялся. Не чаще раза в REFRESH_EVERY. True — переписан."""
    from . import store

    if not force and time.monotonic() - _refreshed["at"] < REFRESH_EVERY:
        return False
    _refreshed["at"] = time.monotonic()
    with db.session(url, "system", via="krug") as conn:
        owner = conn.execute("SELECT crm.owner_id() AS id").fetchone()["id"]
    if not owner:
        return False
    text = block(url, owner)
    with db.session(url, owner, via="krug") as conn:
        row = conn.execute("SELECT body, rev FROM crm.svod WHERE owner_id = %s AND key = %s", (owner, PLACE_KEY)).fetchone()
    if not text or (row and row["body"] == text):
        return False
    res = store.apply_ops(url, owner, [{"op": "svod.set", "key": PLACE_KEY, "body": text, "author": "krug",
                                        "reason": "дерево, правила или правки владельца поменялись"}], "krug", ACTOR)["results"]
    if not res[0].get("ok"):
        log.warning("дерево жизни в Свод не легло: %s", res[0].get("error"))
        return False
    log.info("дерево жизни для разбора — в Своде (%d знаков)", len(text))
    return True


# ── Недельные выводы ───────────────────────────────────────────────────

TUNE_KEY = "krug_tune"  # crm.state: неделя, до какого мига правки учтены, пачка в работе
TUNE_MODEL = "claude-opus-5-5"
TUNE_EFFORT = "medium"
TUNE_BATCH_MAX_AGE = 6 * 3600

TUNE_RULES = """Ты ведёшь короткий список выводов о том, как владелец раскладывает свои дела по дереву жизни. Список
читают все, кто заводит его дела (разбор надиктовки, встреч, переписки), — рядом с общими правилами, деревом целей и
свежими примерами. Выводы нужны, чтобы его правка учила надолго, а не только пока пример свежий.

На входе: общие правила, направления, дерево целей, текущие выводы и правки владельца за неделю — где дело стояло
и куда он его переставил, что ответил на вопрос о месте, что поставил сам.

Как вести:
- Вывод — одна строка, правило с причиной и примером: «Покупки для тренировок (магний, протеин) — Здоровье › Еда,
  а не Быт: покупку ставит по зачем». Не пересказ одной правки, а то, что повторится.
- Правка противоречит выводу — перепиши вывод или убери его: последнее слово владельца сильнее.
- Не повторяй общие правила и границы направлений: выводы — только то, чего в них нет или где автоматика ошибается.
- Одна правка без ясного правила — не повод для вывода; не выдумывай.
- Не больше 20 выводов; старое и мелкое уходит первым. Цели называй словами, без номеров: номера меняются.
- Ответ: lessons — список целиком (новый вместо старого), note — одной фразой, что поменял."""


def tune_schema() -> dict:
    return {"type": "object", "additionalProperties": False, "required": ["lessons", "note"],
            "properties": {"lessons": {"type": "array", "items": {"type": "string"}}, "note": {"type": "string"}}}


def tune_slot(now: dt.datetime) -> dt.datetime:
    """Последний недельный слот: воскресенье, 21:00 по Москве."""
    t = now.astimezone(MSK).replace(hour=21, minute=0, second=0, microsecond=0)
    t -= dt.timedelta(days=(t.weekday() - 6) % 7)
    return t if t <= now.astimezone(MSK) else t - dt.timedelta(days=7)


def _state(url: str) -> dict:
    with db.session(url, "system", via="krug") as conn:
        row = conn.execute("SELECT value FROM crm.state WHERE key = %s", (TUNE_KEY,)).fetchone()
    return dict(row["value"]) if row else {}


def _save(url: str, value: dict) -> None:
    with db.session(url, "system", ACTOR, "krug") as conn:
        conn.execute("INSERT INTO crm.state (key, value) VALUES (%s, %s) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value",
                     (TUNE_KEY, Jsonb(value)))


def tune_request(url: str, owner: str, since: dt.datetime) -> dict | None:
    """Запрос недельных выводов или None — правок за неделю нет, спрашивать не о чем."""
    from . import krug_auto

    events = learned(url, owner, since)
    if not events:
        return None
    with db.session(url, owner, via="krug") as conn:
        known = krug.directions(conn, owner)
        rows = targets(conn, owner)
        rules = krug_auto.rules(conn, owner)
        cur = lessons(conn, owner)
    fixes, own = example_lines(url, owner, known, rows, events)
    if not fixes and not own:
        return None
    system = (TUNE_RULES + "\n\nОБЩИЕ ПРАВИЛА:\n" + rules_for_place(rules) + "\n\nНАПРАВЛЕНИЯ:\n" + "\n".join(dir_lines(known))
              + "\n\nЦЕЛИ:\n" + "\n".join(tree_lines(rows, known)))
    user = ("ТЕКУЩИЕ ВЫВОДЫ:\n" + ("\n".join(f"- {x}" for x in cur) or "(пусто)")
            + "\n\nПРАВКИ ЗА НЕДЕЛЮ — поправки автоматики и ответы:\n" + ("\n".join(fixes) or "(нет)")
            + "\n\nПОСТАВИЛ САМ:\n" + ("\n".join(own) or "(нет)"))
    return {"model": TUNE_MODEL, "max_tokens": 8000, "system": system,
            "output_config": {"effort": TUNE_EFFORT, "format": {"type": "json_schema", "schema": tune_schema()}},
            "messages": [{"role": "user", "content": user}]}


def tune_apply(url: str, owner: str, msg, batch: bool = True) -> dict:
    # Сначала траты, потом проверка ответа: оборванный ответ тоже оплачен (аудит трат 14.10.2026).
    usage = llm.usage_of(msg, TUNE_MODEL)
    usd = llm.cost(usage, TUNE_MODEL, url) * (0.5 if batch else 1.0)
    llm.account(url, "krug_tune", usd, usage.get("model") or TUNE_MODEL, usage=usage, batch=batch)
    if getattr(msg, "stop_reason", None) in ("refusal", "max_tokens"):
        raise RuntimeError(f"выводы Круга: ответ оборван ({msg.stop_reason})")
    data = json.loads(next((b.text for b in msg.content if getattr(b, "type", "") == "text"), "") or "{}")
    items = [" ".join(str(x).split())[:300] for x in data.get("lessons") or [] if str(x).strip()][:LESSONS_MAX]
    from . import store

    with db.session(url, owner, via="krug") as conn:
        row = conn.execute("SELECT body, rev FROM crm.svod WHERE owner_id = %s AND key = %s", (owner, LESSONS_KEY)).fetchone()
    body = "\n".join(f"- {x}" for x in items)
    if items and (not row or row["body"] != body):
        res = store.apply_ops(url, owner, [{"op": "svod.set", "key": LESSONS_KEY, "body": body, "author": "tuner",
                                            "reason": (data.get("note") or "недельные выводы").strip()[:300],
                                            "base_rev": row["rev"] if row else 0}], "krug", ACTOR)["results"]
        if not res[0].get("ok"):
            log.warning("выводы Круга не легли: %s", res[0].get("error"))
    return {"lessons": len(items), "usd": round(usd, 4), "note": data.get("note") or ""}


def tune(url: str, key: str, proxy: str | None, now: dt.datetime | None = None, client=None) -> dict | None:
    """Раз в неделю (воскресенье, 21:00 по Москве): правки владельца за неделю — в выводы. Тик раз в минуту.

    Правок нет — ни одного запроса. Пачка (−50 %): досматривается следующими тиками; не кончилась за
    TUNE_BATCH_MAX_AGE — отмена, выводы подождут неделю (они не срочные)."""
    now = now or dt.datetime.now(dt.timezone.utc)
    st = _state(url)
    if st.get("batch"):
        b = st["batch"]
        if client is None:
            from . import parse
            client = parse.client(key, proxy)
        info = client.messages.batches.retrieve(b["id"])
        if info.processing_status != "ended":
            if time.time() - b["at"] < TUNE_BATCH_MAX_AGE:
                return None
            try:
                client.messages.batches.cancel(b["id"])
            except Exception:  # noqa: BLE001
                pass
            _save(url, {**st, "batch": None, "last": {"week": b["week"], "error": "пачка застряла"}})
            return {"error": True}
        res = next(iter(client.messages.batches.results(b["id"])), None)
        if res is None or res.result.type != "succeeded":
            _save(url, {**st, "batch": None, "last": {"week": b["week"], "error": res.result.type if res else "пусто"}})
            return {"error": True}
        out = tune_apply(url, b["owner"], res.result.message)
        _save(url, {**st, "batch": None, "since": b["until"], "last": {"week": b["week"], **out}})
        log.info("Круг: выводы из правок — %d ($%.3f)", out["lessons"], out["usd"])
        return out
    slot = tune_slot(now)
    week = slot.strftime("%Y-%m-%d")
    if st.get("week") == week or now.astimezone(MSK) - slot > dt.timedelta(hours=6) or not key:
        return None
    with db.session(url, "system", via="krug") as conn:
        owner = conn.execute("SELECT crm.owner_id() AS id").fetchone()["id"]
    since = dt.datetime.fromisoformat(st["since"]) if st.get("since") else now - dt.timedelta(days=7)
    until = now.isoformat()
    p = tune_request(url, owner, since) if owner else None
    if not p:
        _save(url, {**st, "week": week, "since": until})
        return None
    if client is None:
        from . import parse
        client = parse.client(key, proxy)
    b = client.messages.batches.create(requests=[{"custom_id": "krug-tune", "params": p}])
    _save(url, {**st, "week": week, "batch": {"id": b.id, "at": time.time(), "week": week, "owner": owner, "until": until}})
    log.info("Круг: правки за неделю — на выводы, пачка %s", b.id)
    return {"sent": True}
