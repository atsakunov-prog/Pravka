"""Круг жизни (12.10.2026): направления дел, «Зачем», «Моё», принцип дня, движение за неделю.

Владелец принёс бриф «Циферблат»: двенадцать направлений жизни — не пирамида, а круг, по которому он идёт
сразу во все стороны. Дела, которые двигают жизнь, никто не ждёт, поэтому в «Отбить / Запустить /
Мониторить» они всегда проигрывают входящим. Круг отвечает на вопрос «зачем»: у каждого дела одно или
несколько направлений, которые оно двигает, и «Зачем» — дело-цель, шагом к которой оно является
(«Распечатать столбики примеров» — зачем? — «уверенно считает до 20» — зачем? — «хорошо знает математику» —
зачем? — «поступить в сильную школу»). Цель — обычное дело, у которого есть шаги.

Справочник направлений и принципы — Свод владельца (krug.directions, krug.principles): личный, в репозиторий
не идёт, телефону едет синком. Строки Круга у дел и проектов — tasks.krug (dela_0009), только хозяину.
Разметку дел без направления делает разборщик (krug_auto.py) по расписанию.
"""

from __future__ import annotations

import datetime as dt
import math

from . import db

DIRECTIONS_KEY = "krug.directions"
PRINCIPLES_KEY = "krug.principles"
PROMPT_KEY = "prompt.krug"  # правила разборщика (krug_auto.RULES — запас без имён)

MINE_MAX = 3  # «Моё» на «Сегодня»: от одного до трёх дел, которых никто не ждёт (бриф, §9)
MOVE_DAYS = 7  # движение — скользящее окно недели
GOAL_POINTS = 3  # закрыть цель (дело, у которого были шаги) — три события, обычное дело — одно


def _norm(s) -> str:
    return " ".join(str(s or "").replace("ё", "е").replace("Ё", "Е").lower().split())


def directions(conn, owner: str) -> list[dict]:
    """Направления владельца из Свода по кругу: сверху 12 (Здоровье), дальше по часовой. Нет записи — []."""
    row = conn.execute("SELECT value FROM crm.svod WHERE owner_id = %s AND key = %s", (owner, DIRECTIONS_KEY)).fetchone()
    items = [d for d in ((row or {}).get("value") or []) if isinstance(d, dict) and d.get("key")]
    return sorted(items, key=lambda d: int(d.get("pos") or 0) % 12)


def directions_of(url: str, owner: str) -> list[dict]:
    with db.session(url, owner, via="krug") as conn:
        return directions(conn, owner)


def principles(conn, owner: str) -> list[str]:
    row = conn.execute("SELECT value FROM crm.svod WHERE owner_id = %s AND key = %s", (owner, PRINCIPLES_KEY)).fetchone()
    return [str(x).strip() for x in ((row or {}).get("value") or []) if str(x).strip()]


def principle_of_day(items: list[str], day: dt.date) -> dict | None:
    """Один принцип в день, по кругу (бриф, §6): без галочек и баллов — просто строка под «Сегодня»."""
    if not items:
        return None
    i = day.toordinal() % len(items)
    return {"n": i + 1, "text": items[i]}


def norm_directions(conn, owner: str, value, error) -> list[str]:
    """Ключи направлений из того, что прислали: ключ (health), название (Здоровье) или метка (Дом).

    Названия — потому что так говорят люди и Claude в коннекторе; в базу ложатся только ключи из Свода.
    """
    if value is None:
        return []
    if isinstance(value, str):
        value = [x for x in value.replace(";", ",").split(",")]
    if not isinstance(value, list):
        raise error("направления — список")
    known = directions(conn, owner)
    if not known:
        raise error("Круг жизни не настроен: в Своде нет krug.directions")
    by: dict[str, str] = {}
    for d in known:
        for name in (d["key"], d.get("name"), d.get("short")):
            if name:
                by[_norm(name)] = d["key"]
    out: list[str] = []
    for x in value:
        if not str(x or "").strip():
            continue
        key = by.get(_norm(x))
        if not key:
            raise error(f"нет такого направления: «{x}» — есть: " + ", ".join(d.get("name") or d["key"] for d in known))
        if key not in out:
            out.append(key)
    return out


def names(known: list[dict], keys) -> list[str]:
    by = {d["key"]: d.get("short") or d.get("name") or d["key"] for d in known}
    return [by.get(k, k) for k in keys or []]


# ── Движение и «Моё» ───────────────────────────────────────────────────


def movement(conn, user: str, today: dt.date) -> dict[str, dict]:
    """Движение за неделю по направлениям: закрытое дело — 1, закрытая цель — 3 (бриф, §5).

    Направление дела — с наследованием (v_tasks.dirs). Радиус точки — min(10, 3·√N): корень, чтобы тридцать
    мелких покупок в Быте не забивали шкалу. Несколько направлений у дела — событие каждому.
    """
    since = today - dt.timedelta(days=MOVE_DAYS - 1)
    rows = conn.execute(
        "SELECT t.id, t.dirs, EXISTS (SELECT 1 FROM tasks.krug c WHERE c.why_id = t.id) AS goal "
        "FROM tasks.v_tasks t WHERE t.owner_id = %s AND t.status = 'done' "
        "AND (t.completed_at AT TIME ZONE 'Europe/Moscow')::date >= %s", (user, since)).fetchall()
    out: dict[str, dict] = {}
    for r in rows:
        for k in r["dirs"] or []:
            m = out.setdefault(k, {"done": 0, "goals": 0, "events": 0})
            m["done"] += 1
            m["goals"] += 1 if r["goal"] else 0
            m["events"] += GOAL_POINTS if r["goal"] else 1
    for m in out.values():
        m["radius"] = round(min(10.0, 3 * math.sqrt(m["events"])), 1)
    return out


def mine_from(items: list[dict], moved: dict[str, dict]) -> list[dict]:
    """«Моё» (бриф, §9; принцип «Делаю то, что хочу я»): до трёх открытых дел, которых никто не ждёт.

    Кандидаты — «Хочу сам» (want — закреплённое руками) и шаги к целям (у дела есть «Зачем», своих открытых
    шагов нет). Не «отбить» и не «мониторить»: там ждут люди, это не моё. Порядок: закреплённое, потом шаги из
    направлений, где за неделю меньше всего движения, потом кто дольше ждёт.
    """
    def weight(t: dict) -> tuple:
        least = min((moved.get(k, {}).get("events", 0) for k in t.get("dirs") or []), default=0)
        return (not t.get("want"), least, str(t.get("created_at") or ""), t.get("num") or 0)

    pool = [t for t in items if t.get("status") == "open" and not t.get("owed") and t.get("ball") != "waiting"
            and (t.get("want") or (t.get("why_id") and not t.get("steps_open")))]
    return sorted(pool, key=weight)[:MINE_MAX]


def mine(conn, user: str, today: dt.date) -> list[dict]:
    items = conn.execute(
        "SELECT * FROM tasks.v_tasks WHERE owner_id = %s AND status = 'open' AND NOT owed AND ball <> 'waiting' "
        "AND (want OR why_id IS NOT NULL)", (user,)).fetchall()
    return mine_from(items, movement(conn, user, today))


# ── Виды ────────────────────────────────────────────────────────────────


def today_extra(conn, user: str) -> dict:
    """Добавка к «Сегодня»: «Моё» и принцип дня. Круг не настроен — пусто, «Сегодня» как было."""
    today = conn.execute("SELECT crm.today() AS d").fetchone()["d"]
    if not directions(conn, user):
        return {"mine": [], "principle": principle_of_day(principles(conn, user), today)}
    return {"mine": mine(conn, user, today), "principle": principle_of_day(principles(conn, user), today)}


def view_krug(conn, user, **_) -> dict:
    """Круг жизни целиком: направления по часовой, движение за неделю, открытые дела каждого направления
    деревом «Зачем» (цели и их шаги), дела без направления. Для коннектора Claude и телефона; веб строит то же
    дерево сам из синка."""
    today = conn.execute("SELECT crm.today() AS d").fetchone()["d"]
    known = directions(conn, user)
    moved = movement(conn, user, today)
    rows = conn.execute(
        "SELECT id, num, title, project_name, deal_name, dirs, directions, why_id, steps_open, due_date, owed, ball, want "
        "FROM tasks.v_tasks WHERE owner_id = %s AND status = 'open' ORDER BY num", (user,)).fetchall()
    ids = {r["id"] for r in rows}
    kids: dict = {}
    for r in rows:
        if r["why_id"] in ids:
            kids.setdefault(r["why_id"], []).append(r)

    def node(r: dict, depth: int = 0) -> dict:
        n = {k: r[k] for k in ("num", "title", "project_name", "deal_name", "due_date", "owed", "ball", "want")}
        n["own"] = bool(r["directions"])
        if depth < 12 and kids.get(r["id"]):
            n["steps"] = [node(c, depth + 1) for c in kids[r["id"]]]
        return n

    out = []
    for d in known:
        mine_rows = [r for r in rows if d["key"] in (r["dirs"] or [])]
        here = {r["id"] for r in mine_rows}
        # Корень дерева направления — дело, чья цель не здесь: шаг со своим направлением виден и в нём, и в
        # дереве своей цели.
        roots = [r for r in mine_rows if r["why_id"] not in here]
        out.append({"key": d["key"], "pos": d.get("pos"), "name": d.get("name"), "short": d.get("short"),
                    "formula": d.get("formula"), "open": len(mine_rows), "move": moved.get(d["key"], {"done": 0, "events": 0, "radius": 0}),
                    "tree": [node(r) for r in roots]})
    loose = [{"num": r["num"], "title": r["title"], "project_name": r["project_name"]} for r in rows if not r["dirs"]]
    return {"directions": out, "without": loose, "principle": principle_of_day(principles(conn, user), today),
            "configured": bool(known)}


def register(views: dict) -> None:
    views["krug"] = view_krug


# ── Засев ──────────────────────────────────────────────────────────────


def seed(url: str, user: str, data: dict, force: bool = False) -> dict:
    """Первый Круг из файла владельца (python -m pravka_dela krug-seed): направления и принципы — в Свод,
    направления проектов — по правилам файла («kind:client» — все клиенты, иначе — по имени проекта).

    Повтор безвреден: что уже есть, не трогает — владелец боится потерять свои правки («тексты пускай
    синхронизируются, а то я боюсь, что потеряю их»); force — переписать записи Свода файлом."""
    from . import store

    out = {"svod": [], "projects": []}
    with db.session(url, user, via="cli") as conn:
        have = {r["key"] for r in conn.execute("SELECT key FROM crm.svod WHERE owner_id = %s AND key IN (%s, %s, %s)",
                                               (user, DIRECTIONS_KEY, PRINCIPLES_KEY, PROMPT_KEY))}
        projects = conn.execute(
            "SELECT p.id, p.name, p.kind FROM crm.projects p WHERE p.owner_id = %s AND p.archived_at IS NULL "
            "AND NOT EXISTS (SELECT 1 FROM tasks.krug k WHERE k.id = p.id)", (user,)).fetchall()
    ops = []
    for key, field in ((DIRECTIONS_KEY, "directions"), (PRINCIPLES_KEY, "principles"), (PROMPT_KEY, "prompt")):
        if data.get(field) and (force or key not in have):
            body = {"body": data[field]} if isinstance(data[field], str) else {"value": data[field]}
            ops.append({"op": "svod.set", "key": key, **body, "author": "seed", "reason": "Круг жизни: первый засев из файла владельца"})
            out["svod"].append(key)
    if ops:
        bad = [r["error"] for r in store.apply_ops(url, user, ops, via="cli", actor="cli:seed")["results"] if not r["ok"]]
        if bad:
            raise RuntimeError("Свод: " + "; ".join(bad))
    rules = {k: v for k, v in (data.get("projects") or {}).items() if not k.startswith("_")}
    ops = []
    for p in projects:
        dirs = rules.get(p["name"]) or rules.get(f"kind:{p['kind']}")
        if dirs:
            ops.append({"op": "project.set", "id": str(p["id"]), "set": {"directions": dirs}})
            out["projects"].append(f"{p['name']}: {', '.join(dirs)}")
    if ops:
        bad = [r["error"] for r in store.apply_ops(url, user, ops, via="cli", actor="cli:seed")["results"] if not r["ok"]]
        if bad:
            raise RuntimeError("проекты: " + "; ".join(bad))
    return out
