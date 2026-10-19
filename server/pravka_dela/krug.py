"""Круг жизни (12.10.2026, дерево целей — 13.10.2026): направления, цели, «Зачем», «Моё», принцип дня, движение.

Владелец принёс бриф «Циферблат»: направления жизни — не пирамида, а круг, по которому он идёт сразу во все
стороны. Дела, которые двигают жизнь, никто не ждёт, поэтому в «Отбить / Запустить / Мониторить» они всегда
проигрывают входящим. Круг отвечает на вопрос «зачем».

Бриф 7.2 (13.10.2026) свёл это в одно дерево жизни: девять направлений; под каждым — цели, которые ставит сам
владелец (цель — состояние: «сын хорошо знает математику»), подцели до четырёх уровней, на целях висят проекты
(клиент — целиком) и дела. Цель с ритмом («ежедневно 8 часов сна») — практика. Дело без цели висит прямо на
направлении («без зачем»). Цель — дело с пометкой goal в строке Круга (dela_0013): в списках дел её нет.

Направление дела одно главное — то, в которое упирается цепочка «Зачем»; в нём считается движение. Свои
направления дела, кроме главного, — метки (не больше двух). Правило — tasks.krug_dirs в базе; веб и телефон
считают так же (dirsOf).

Справочник направлений и принципы — Свод владельца (krug.directions, krug.principles): личный, в репозиторий
не идёт, телефону едет синком. Строки Круга у дел и проектов — tasks.krug (dela_0009, 0013), только хозяину.
Цель для дел без цели предлагает разборщик (krug_auto.py) по расписанию; узлы он не заводит.
"""

from __future__ import annotations

import datetime as dt
import math

from . import db

DIRECTIONS_KEY = "krug.directions"
PRINCIPLES_KEY = "krug.principles"
PROMPT_KEY = "prompt.krug"  # правила разборщика (krug_auto.RULES — запас без имён)

DIRS_MAX = 3  # главное направление и до двух меток (бриф 7.2, §3)
MINE_MAX = 3  # «Моё» на «Сегодня»: от одного до трёх дел, которых никто не ждёт (бриф, §9)
MOVE_DAYS = 7  # движение — скользящее окно недели
GOAL_POINTS = 3  # закрыть цель — три события, обычное дело — одно
TREE_DEPTH = 12  # глубже — значит, где-то круг; дальше не идём
ORDER = {"goal": 0, "project": 1, "task": 2}  # под узлом: цели, проекты, дела


def _norm(s) -> str:
    return " ".join(str(s or "").replace("ё", "е").replace("Ё", "Е").lower().split())


def directions(conn, owner: str) -> list[dict]:
    """Направления владельца из Свода по кругу: pos — место на круге (0 — сверху, Здоровье). Нет записи — []."""
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
    """Один принцип в день, по кругу (бриф, §8): без галочек и баллов — просто строка под «Сегодня»."""
    if not items:
        return None
    i = day.toordinal() % len(items)
    return {"n": i + 1, "text": items[i]}


def norm_directions(conn, owner: str, value, error) -> list[str]:
    """Ключи направлений из того, что прислали: ключ (health), название (Здоровье) или метка (Быт).

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
        for name in (d["key"], d.get("name"), d.get("short"), *(d.get("aliases") or [])):
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
    """Движение за неделю по направлениям: закрытое дело — 1, закрытая цель — 3 (бриф 7.2, §6).

    Считается только в главном направлении дела (v_tasks.dirs[1]): метка движения не даёт. Радиус точки —
    min(10, 3·√N): корень, чтобы тридцать мелких покупок в Быте не забивали шкалу. Цель — узел дерева (goal). До
    15.10.2026 целью считалось и дело, у которого были шаги, но с делами внутри дела (правило 27) так стало бы любое
    клиентское дело с драфтом внутри — 3 очка и +1 к целям; на живой базе таких «родителей-дел» не было — история не
    сдвинулась.
    """
    since = today - dt.timedelta(days=MOVE_DAYS - 1)
    # По книге выполнений (dela_0037): каждое выполнение — и повтора, и дела внутри, которое повтор снова открыл.
    rows = conn.execute(
        "SELECT t.id, t.dirs, t.goal FROM tasks.done_log l JOIN tasks.v_tasks t ON t.id = l.task_id "
        "WHERE l.owner_id = %s AND (l.at AT TIME ZONE 'Europe/Moscow')::date >= %s", (user, since)).fetchall()
    out: dict[str, dict] = {}
    for r in rows:
        if not r["dirs"]:
            continue
        m = out.setdefault(r["dirs"][0], {"done": 0, "goals": 0, "events": 0})
        m["done"] += 1
        m["goals"] += 1 if r["goal"] else 0
        m["events"] += GOAL_POINTS if r["goal"] else 1
    for m in out.values():
        m["radius"] = round(min(10.0, 3 * math.sqrt(m["events"])), 1)
    return out


def toward_goal(links: dict) -> set:
    """Шаги к целям: дела, у которых вверх по цепочке «Зачем» есть цель дерева. links — {id: (why_id, goal)} строк Круга
    дел хозяина. Дело внутри обычного дела (правило 27), над которым целей нет (драфт для термшита клиенту), — не шаг
    к цели: «Моё» — то, что хочу я, а не часть того, что ждёт клиент. Дело внутри дела, которое само ведёт к цели, — шаг."""
    out = set()
    for start in links:
        x, n = links[start][0], 0
        while x is not None and n < 60:
            up = links.get(x)
            if up is None:
                break
            if up[1]:
                out.add(start)
                break
            x, n = up[0], n + 1
    return out


def mine_from(items: list[dict], moved: dict[str, dict], toward: set | None = None) -> list[dict]:
    """«Моё» (бриф, §7; принцип «Делаю то, что хочу я»): до трёх открытых дел, которых никто не ждёт.

    Кандидаты — «Хочу сам» (want — закреплённое руками) и шаги к целям (вверх по «Зачем» есть цель — toward_goal;
    своих открытых дел внутри нет). Сами цели — не дела: их нет. Не «отбить» и не «мониторить»: там ждут люди, это не
    моё. Порядок: закреплённое, потом шаги из направлений, где за неделю меньше всего движения, потом кто дольше ждёт.
    toward — id шагов к целям; None — как до 15.10.2026: любое дело с «Зачем».
    """
    def weight(t: dict) -> tuple:
        main = (t.get("dirs") or [None])[0]
        least = moved.get(main, {}).get("events", 0) if main else 0
        return (not t.get("want"), least, str(t.get("created_at") or ""), t.get("num") or 0)

    def step(t: dict) -> bool:
        return bool(t.get("why_id")) and not t.get("steps_open") and (toward is None or t["id"] in toward)

    pool = [t for t in items if t.get("status") == "open" and not t.get("goal") and not t.get("owed")
            and t.get("ball") != "waiting" and (t.get("want") or step(t))]
    return sorted(pool, key=weight)[:MINE_MAX]


def mine(conn, user: str, today: dt.date, skip: set | frozenset = frozenset()) -> list[dict]:
    """skip — что и так на виду («Сегодня» и «Сейчас», 13.10.2026): отсеивается до отбора трёх, а не после, иначе
    «Моё» показывало бы меньше, чем есть."""
    items = conn.execute(
        "SELECT * FROM tasks.v_tasks WHERE owner_id = %s AND status = 'open' AND NOT goal AND NOT owed AND kind = 'task' "
        "AND ball <> 'waiting' AND (want OR why_id IS NOT NULL)", (user,)).fetchall()
    links = {r["id"]: (r["why_id"], r["goal"]) for r in conn.execute(
        "SELECT id, why_id, goal FROM tasks.krug WHERE kind = 'task' AND owner_id = %s", (user,)).fetchall()}
    return mine_from([t for t in items if t["id"] not in skip], movement(conn, user, today), toward_goal(links))


# ── Дерево ─────────────────────────────────────────────────────────────


def tree(conn, user: str, today: dt.date) -> dict:
    """Дерево жизни (бриф 7.2, §7): направления › цели › подцели › проекты и дела. У узла — сколько открытых дел
    под ним и сколько событий движения за неделю. Место дела — по «Зачем»; нет его — по проекту (если проект
    размечен в Круге); нет и там — прямо на направлении («без зачем»). Без направления вовсе — в none.

    Для коннектора Claude и вида tree; веб и телефон строят то же дерево сами из синка (treeModel, DelaKrug.tree).
    Напоминание (захват, kind remind, правило 29) — не дело: открытым в дереве его нет и в «открытых» узла оно не
    считается, даже с «Зачем»-целью (её ставит модель захвата); сделанное — движение, как в movement. Одно правило с вебом
    (treeModel) и телефоном (DelaKrug.treeModel: !it.remind).
    """
    since = today - dt.timedelta(days=MOVE_DAYS - 1)
    rows = conn.execute(
        "SELECT id, num, title, status, kind, goal, rhythm, no_steps, why_id, dirs, project_id, project_name, deal_name, "
        "due_date, owed, ball, want, pos FROM tasks.v_tasks WHERE owner_id = %s AND (status = 'open' "
        "OR (status = 'done' AND (completed_at AT TIME ZONE 'Europe/Moscow')::date >= %s)) ORDER BY num",
        (user, since)).fetchall()
    projects = {r["id"]: r for r in conn.execute(
        "SELECT p.id, p.name, p.kind, k.directions, k.why_id FROM tasks.krug k JOIN crm.projects p ON p.id = k.id "
        "WHERE k.kind = 'project' AND k.owner_id = %s AND p.archived_at IS NULL", (user,)).fetchall()}
    live = {r["id"]: r for r in rows if r["status"] == "open" and r["kind"] == "task"}

    def parent(r: dict, kind: str) -> tuple:
        if r.get("why_id") in live:
            return ("t", r["why_id"])
        if kind == "t" and not r.get("goal") and r.get("project_id") in projects:
            return ("p", r["project_id"])
        if kind == "p":
            return ("d", (r.get("directions") or [None])[0])
        return ("d", (r.get("dirs") or [None])[0])

    kids: dict[tuple, list] = {}
    for r in live.values():
        kids.setdefault(parent(r, "t"), []).append(("t", r))
    for p in projects.values():
        kids.setdefault(parent(p, "p"), []).append(("p", p))

    # Закрытое за неделю — вверх по дереву: каждому узлу на пути.
    moved: dict[tuple, int] = {}
    for r in rows:
        if r["status"] != "done":
            continue
        pts = GOAL_POINTS if r["goal"] else 1
        key = parent(r, "t")
        for _ in range(TREE_DEPTH * 2):
            moved[key] = moved.get(key, 0) + pts
            if key[0] == "t" and key[1] in live:
                key = parent(live[key[1]], "t")
            elif key[0] == "p" and key[1] in projects:
                key = parent(projects[key[1]], "p")
            else:
                break

    def sort(items: list[dict], by_pos: bool = False) -> list[dict]:
        # Дела внутри обычного дела — по месту среди соседей (pos, dela_0030; пусто — после, по номеру), как веб и
        # телефон; под целями и направлениями места нет (его там не ставят — старое место вынутого дела не в счёт).
        if not by_pos:
            return sorted(items, key=lambda c: (ORDER[c["kind"]], c.get("num") or 0, c.get("name") or ""))
        return sorted(items, key=lambda c: (ORDER[c["kind"]], c.get("pos") is None, c.get("pos") or 0, c.get("num") or 0,
                                            c.get("name") or ""))

    def node(kind: str, r: dict, depth: int) -> dict:
        key = (kind, r["id"])
        if kind == "p":
            n = {"kind": "project", "name": r["name"], "client": r["kind"] == "client"}
        else:
            n = {"kind": "goal" if r["goal"] else "task", "num": r["num"], "title": r["title"]}
            for k in ("rhythm", "project_name", "deal_name", "due_date", "owed", "ball", "want", "no_steps", "pos"):
                if r.get(k):
                    n[k] = r[k]
        inside = kind == "t" and not r["goal"]
        below = sort([node(k, c, depth + 1) for k, c in kids.get(key, [])], inside) if depth < TREE_DEPTH else []
        n["open"] = sum(c["open"] + (1 if c["kind"] == "task" else 0) for c in below)
        n["moved"] = moved.get(key, 0)
        if below:
            n["children"] = below
        return n

    out = []
    for d in directions(conn, user):
        root = ("d", d["key"])
        below = sort([node(k, c, 1) for k, c in kids.get(root, [])])
        out.append({"key": d["key"], "pos": d.get("pos"), "zone": d.get("zone"), "name": d.get("name"),
                    "short": d.get("short"), "formula": d.get("formula"),
                    "open": sum(c["open"] + (1 if c["kind"] == "task" else 0) for c in below),
                    "moved": moved.get(root, 0), "children": below})
    none = sort([node(k, c, 1) for k, c in kids.get(("d", None), [])])
    return {"directions": out, "none": none}


def find(tree_: dict, num: int | None = None) -> dict | None:
    """Ветка дерева по номеру узла (цели или дела)."""
    stack = [c for d in tree_["directions"] for c in d["children"]]
    while stack:
        n = stack.pop()
        if n.get("num") == num:
            return n
        stack.extend(n.get("children") or [])
    return None


# ── Виды ────────────────────────────────────────────────────────────────


def today_extra(conn, user: str, skip: set | frozenset = frozenset()) -> dict:
    """Добавка к «Сегодня»: «Моё» и принцип дня. Круг не настроен — пусто, «Сегодня» как было. skip — дела, которые
    уже на виду (срок сегодня, «Сейчас»): в «Моё» они не повторяются."""
    today = conn.execute("SELECT crm.today() AS d").fetchone()["d"]
    if not directions(conn, user):
        return {"mine": [], "principle": principle_of_day(principles(conn, user), today)}
    return {"mine": mine(conn, user, today, skip), "principle": principle_of_day(principles(conn, user), today)}


def view_krug(conn, user, **_) -> dict:
    """Круг жизни обзором: направления по кругу (зона, формула), сколько открыто, движение за неделю, цели
    верхнего уровня; дела без направления. Дерево целиком — вид tree."""
    today = conn.execute("SELECT crm.today() AS d").fetchone()["d"]
    moved = movement(conn, user, today)
    t = tree(conn, user, today)
    out = [{**{k: d[k] for k in ("key", "pos", "zone", "name", "short", "formula", "open")},
            "move": moved.get(d["key"], {"done": 0, "events": 0, "radius": 0}),
            "goals": [{"num": c["num"], "title": c["title"], "open": c["open"]} for c in d["children"] if c["kind"] == "goal"]}
           for d in t["directions"]]
    return {"directions": out, "without": t["none"], "principle": principle_of_day(principles(conn, user), today),
            "configured": bool(out)}


def view_tree(conn, user, direction=None, node=None, **_) -> dict:
    """Дерево жизни: всё, одно направление (direction — ключ) или ветка одного узла (node — номер цели)."""
    today = conn.execute("SELECT crm.today() AS d").fetchone()["d"]
    t = tree(conn, user, today)
    if node:
        return {"node": find(t, num=int(str(node).lstrip("#"))), "configured": bool(t["directions"])}
    if direction:
        return {"directions": [d for d in t["directions"] if d["key"] == direction], "none": [], "configured": True}
    return {**t, "configured": bool(t["directions"])}


def register(views: dict) -> None:
    views["krug"] = view_krug
    views["tree"] = view_tree


# ── Засев ──────────────────────────────────────────────────────────────


SEED_STATE = "krug_seed"  # crm.state: какую версию файла владельца уже засеяли и посажено ли дерево


def _seed_state(url: str) -> dict:
    with db.session(url, "system", "cli:seed", "cli") as conn:
        row = conn.execute("SELECT value FROM crm.state WHERE key = %s", (SEED_STATE,)).fetchone()
        return dict(row["value"]) if row else {}


def _seed_save(url: str, value: dict) -> None:
    from psycopg.types.json import Jsonb

    with db.session(url, "system", "cli:seed", "cli") as conn:
        conn.execute("INSERT INTO crm.state (key, value) VALUES (%s, %s) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value",
                     (SEED_STATE, Jsonb(value)))


def seed(url: str, user: str, data: dict, force: bool = False) -> dict:
    """Круг из файла владельца (python -m pravka_dela krug-seed): направления, принципы и правила разборщика — в
    Свод; merge — слить направления (ключ → ключ: 7.2 свёл двенадцать в девять); направления проектов — по
    правилам файла («kind:client» — все клиенты, иначе — по имени проекта); tree — дерево целей (import_tree).

    Повтор безвреден: что уже есть, не трогает — владелец боится потерять свои правки («тексты пускай
    синхронизируются, а то я боюсь, что потеряю их»), а установщик зовёт засев при каждой установке. Новая
    version файла (7.1 → 7.2) — один раз переписать Свод и посадить дерево; дальше дерево — его, засев к нему не
    возвращается (иначе следующая установка вернула бы перенесённые дела на места из брифа). force — переписать
    Свод и пройти дерево ещё раз (что есть — то же название под тем же родителем — второй раз не заводится);
    прежние версии Свода остаются в журнале."""
    from . import store

    out = {"svod": [], "merged": 0, "projects": [], "tree": None}
    st = _seed_state(url)
    ver = str(data.get("version") or "")
    fresh = force or bool(ver and st.get("version") != ver)
    with db.session(url, user, via="cli") as conn:
        have = {r["key"] for r in conn.execute("SELECT key FROM crm.svod WHERE owner_id = %s AND key IN (%s, %s, %s)",
                                               (user, DIRECTIONS_KEY, PRINCIPLES_KEY, PROMPT_KEY))}
    ops = []
    for key, field in ((DIRECTIONS_KEY, "directions"), (PRINCIPLES_KEY, "principles"), (PROMPT_KEY, "prompt")):
        if data.get(field) and (fresh or key not in have):
            body = {"body": data[field]} if isinstance(data[field], str) else {"value": data[field]}
            ops.append({"op": "svod.set", "key": key, **body, "author": "seed", "reason": "Круг жизни: засев из файла владельца"})
            out["svod"].append(key)
    if ops:
        bad = [r["error"] for r in store.apply_ops(url, user, ops, via="cli", actor="cli:seed")["results"] if not r["ok"]]
        if bad:
            raise RuntimeError("Свод: " + "; ".join(bad))
    merge = {k: v for k, v in (data.get("merge") or {}).items() if not k.startswith("_")}
    if merge:
        out["merged"] = remap(url, user, merge)
    with db.session(url, user, via="cli") as conn:
        projects = conn.execute(
            "SELECT p.id, p.name, p.kind FROM crm.projects p WHERE p.owner_id = %s AND p.archived_at IS NULL "
            "AND NOT EXISTS (SELECT 1 FROM tasks.krug k WHERE k.id = p.id)", (user,)).fetchall()
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
    if data.get("tree") and (fresh or not st.get("tree")):
        out["tree"] = import_tree(url, user, data["tree"])
    if fresh or out["tree"] or (ver and not st.get("version")):
        _seed_save(url, {**st, **({"version": ver} if ver else {}), "tree": bool(st.get("tree") or out["tree"])})
    return out


def remap(url: str, user: str, merge: dict[str, str]) -> int:
    """Слить направления (бриф 7.2, §11: Корни, Образование и Отцовство — в Родительство…): ключи в строках Круга
    хозяина — по карте merge, повтор одного направления схлопывается. Журнал видит каждую строку (cli:seed)."""
    n = 0
    with db.session(url, user, "cli:seed", "cli") as conn:
        rows = conn.execute("SELECT id, directions FROM tasks.krug WHERE owner_id = %s AND directions && %s",
                            (user, list(merge))).fetchall()
        for r in rows:
            new: list[str] = []
            for k in r["directions"]:
                k = merge.get(k, k)
                if k not in new:
                    new.append(k)
            conn.execute("UPDATE tasks.krug SET directions = %s WHERE id = %s", (new[:DIRS_MAX], r["id"]))
            n += 1
    return n


def import_tree(url: str, user: str, tree_: dict) -> dict:
    """Дерево целей из файла владельца (бриф 7.2, §4 и §11): {ключ направления: [узел…]}, узел — {title, num?,
    rhythm?, task?, no_steps?, tasks: [номера], projects: [имена или «kind:client»], children: [узел…]}.

    num — существующее дело становится этим узлом и берёт его название («переименовать #916»); task — пункт —
    действие, а не состояние: заводится делом под родителем, а не целью. tasks — дела, которые перевесить на узел
    (свои направления у них снимаются: место — по дереву). projects — проекты на узел; «kind:client» — все
    клиенты, которых файл не назвал по имени. Узел уже есть (то же название под тем же родителем) — второй раз
    не заводится: повтор безвреден. Всё — одной транзакцией: сломалось посередине — не записалось ничего."""
    from . import store

    stats = {"created": 0, "goals": 0, "renamed": [], "linked": 0, "projects": [], "missing": []}
    with db.session(url, user, "cli:seed", "cli") as conn:
        known = {d["key"] for d in directions(conn, user)}
        named: set[str] = set()
        clients: list = []

        def op_set(tid, set_: dict) -> None:
            store.op_task_set(conn, user, {"id": str(tid), "set": set_})

        def project_of(name: str):
            return conn.execute(
                "SELECT id, name FROM crm.projects WHERE owner_id = %s AND archived_at IS NULL "
                "AND (lower(name) = lower(%s) OR lower(%s) = ANY (SELECT lower(a) FROM unnest(aliases) a))",
                (user, name, name)).fetchone()

        def walk(key: str, parent, n: dict) -> None:
            title = " ".join(str(n.get("title") or "").split())
            is_goal = not n.get("task")
            krug_set = {"goal": is_goal, "why_id": str(parent) if parent else None, "directions": [] if parent else [key]}
            if n.get("rhythm"):
                krug_set["rhythm"] = n["rhythm"]
            if n.get("num"):
                t = conn.execute("SELECT id, title FROM tasks.v_tasks WHERE num = %s AND owner_id = %s",
                                 (n["num"], user)).fetchone()
                if not t:
                    stats["missing"].append(f"#{n['num']}")
                    return
                if title and t["title"] != title:
                    stats["renamed"].append(f"#{n['num']}: {t['title']} → {title}")
                    op_set(t["id"], {"title": title})
                op_set(t["id"], krug_set)
                me = t["id"]
            else:
                t = conn.execute(
                    "SELECT id FROM tasks.v_tasks WHERE owner_id = %s AND status = 'open' AND lower(title) = lower(%s) "
                    "AND why_id IS NOT DISTINCT FROM %s AND goal = %s", (user, title, parent, is_goal)).fetchone()
                if t:
                    me = t["id"]
                    if n.get("rhythm"):
                        op_set(me, {"rhythm": n["rhythm"]})
                else:
                    me = store.op_task_create(conn, user, {"task": {
                        "title": title, "owner_id": user, "source": "manual", **krug_set}})["task"]["id"]
                    stats["created"] += 1
                    stats["goals"] += 1 if is_goal else 0
            for num in n.get("tasks") or []:
                t = conn.execute("SELECT id FROM tasks.v_tasks WHERE num = %s AND owner_id = %s", (num, user)).fetchone()
                if not t:
                    stats["missing"].append(f"#{num}")
                    continue
                op_set(t["id"], {"why_id": str(me), "directions": []})
                stats["linked"] += 1
            for name in n.get("projects") or []:
                if name == "kind:client":
                    clients.append(me)
                    continue
                p = project_of(name)
                if not p:
                    stats["missing"].append(f"проект {name}")
                    continue
                named.add(str(p["id"]))
                store.project_set(conn, user, {"id": str(p["id"]), "set": {"why_id": str(me)}})
                stats["projects"].append(p["name"])
            for c in n.get("children") or []:
                walk(key, me, c)
            if n.get("no_steps"):
                op_set(me, {"no_steps": True})

        for key, nodes in tree_.items():
            if key.startswith("_"):
                continue
            if key not in known:
                raise RuntimeError(f"дерево: нет направления {key} в Своде")
            for n in nodes:
                walk(key, None, n)
        for goal in clients[:1]:
            for p in conn.execute(
                    "SELECT p.id, p.name FROM crm.projects p LEFT JOIN tasks.krug k ON k.id = p.id "
                    "WHERE p.owner_id = %s AND p.kind = 'client' AND p.archived_at IS NULL AND k.why_id IS NULL",
                    (user,)).fetchall():
                if str(p["id"]) not in named:
                    store.project_set(conn, user, {"id": str(p["id"]), "set": {"why_id": str(goal)}})
                    stats["projects"].append(p["name"])
    return stats
