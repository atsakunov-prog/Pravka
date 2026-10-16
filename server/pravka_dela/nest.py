"""Куда класть дело и что оно берёт от родителя — одно место для всех входов (правило 27; вагоны A и B плана
вложенности поезда, 16.10.2026).

Владелец 15.10.2026: «чтобы я мог свободно делать nesting и говорить дела и они нормально подцеплялись»; 16.10.2026
(отладка, записи 20–23): строка Claude завела новую цель и ответила «сам вложить его не могу — перетащи его туда в
дереве», а на «да, ставь» — «мне пока не видно, вложить не получается». Решать «куда» и «что взять от родителя» должен
сервер, одним правилом, а не каждый вход своей копией (до этого правило жило в store.step_of и копиями в ask.py и
mcp_tools, а службы, захват и телефон без связи заводили дело внутри дела голым). Этот модуль зовут: store
(op_task_create — для всех входов), строка Claude (ask.py), коннектор (mcp_tools), дальше — захват голосом,
перетаскивание и кнопки «Вложить в…» «Нового».

Три куска:

- resolve — куда класть, из строки, которую называет вход (поле into строки Claude, inside захвата и служб):
  «#57» или «57» — своё открытое дело или цель по номеру; «^2» — второе новое из этого же ответа (новый родитель:
  «подготовить КП: собрать, согласовать, отправить»); «направление Здоровье» — прямо на направление, без «Зачем»
  (так же кладёт бросок на колонку в дереве веба); «-» — вынуть (направления остаются); «» — не трогать; слова — rank.
  Чужое, закрытое, напоминание, ориентир, цель в дело, в самого себя и петля — отказ словами; базу и так страхует
  tasks.krug_rules (dela_0013).
- rank — родитель по словам («к делу про сверку», название нового дела для кнопок «Вложить в…»): свои открытые дела
  (не цели, не напоминания), доля общих основ слов (people.stem, без служебных), +0,2 за тот же проект, +0,1 за того
  же человека, полное вхождение названия — 1,0. Уверен — лучший не ниже 0,6 и второй ниже хотя бы на 0,2; иначе до трёх
  кандидатов от 0,34 (cands) — их показывают кнопками, сами не вкладывают.
- nest_fill — что новое дело внутри ДЕЛА (не цели и не напоминания) берёт от родителя: проект, сделку (только с тем же
  проектом), человека и срок (step_due). Названное сильнее — заполняется только то, чего в операции нет совсем; явная
  пустота (null) — тоже слово («без человека», «без срока»). «Потом» не перебивает унаследованный срок (поезд
  16.10.2026, вагоны 2 и 4: ребёнок вернётся с родителем, а не своей строкой «Вернулось»). Зовёт его
  store.op_task_create для любого task.create с why_id на дело — так наследуют строка Claude, коннектор, захват,
  службы, «Новое» и телефон; ask.py зовёт его и сам — для «Для этого» не-хозяина: «Зачем» ему не поставить (Круг
  личный), а проект и срок от дела — по-прежнему.

Решено по умолчанию (Саша меняет словом; правило 27 карты Дел): названное сильнее унаследованного; цель ничего не
передаёт; напоминание не вкладывается.

Модуль не зовёт Claude и ничего не пишет: только читает (resolve, rank) и дополняет словарь операции (nest_fill).
"""

from __future__ import annotations

import datetime as dt
import re

from . import krug, people

# Поля, которые новое дело внутри дела берёт от родителя (сделка — только вместе с тем же проектом). Телефон шлёт их
# явным null, когда Саша очистил поле в листе «Для этого» (Dela.createOp, docs/dela-phone-29.md §1).
FILL_KEYS = ("project_id", "deal_id", "person_id", "due_date")

_DIRECTION = re.compile(r"^(?:в\s+|на\s+)?направлени[еяию]\s+(.+)$", re.I)
_QUOTES = "«»\"'“”„ "
_LOOP_DEPTH = 50  # как цепочка «зачем» в tasks.krug_rules: глубже — уже круг

# Поиск по словам (rank, PLAN §2.2 п. 3). Служебные слова — не основа сходства: «к делу про сверку» — это «сверка».
STOP = frozenset({
    "дело", "дела", "делу", "делом", "деле", "про", "по", "для", "к", "ко", "с", "со", "в", "во", "на", "за", "о", "об",
    "обо", "и", "а", "из", "от", "у", "до", "или", "не", "это", "этого", "этому", "этом", "то", "того", "тому", "том",
})
SURE_MIN = 0.6  # уверен: лучший не ниже…
SURE_GAP = 0.2  # …и второй ниже хотя бы на столько
CAND_MIN = 0.34  # кандидат кнопкой — от этого
CANDS_MAX = 3
PROJECT_BONUS = 0.2
PERSON_BONUS = 0.1
_EPS = 1e-9  # 1,2 − 1,0 в плавающей точке — 0,19999…


def day_of(v) -> dt.date | None:
    """Дата из даты, времени или строки «ГГГГ-ММ-ДД…»; пусто и кривое — None."""
    if isinstance(v, dt.datetime):
        return v.date()
    if isinstance(v, dt.date):
        return v
    try:
        return dt.date.fromisoformat(str(v)[:10]) if v else None
    except ValueError:
        return None


def step_due(parent: dict, today: dt.date) -> dt.date | None:
    """Срок дела внутри, когда его не назвали: родитель в «Сейчас», просрочен или на сегодня — сегодня (до него надо
    успеть); на будущую дату — тот же срок; без срока — без срока. «Сейчас» ребёнку — только если сказал. Одно правило
    с вебом (stepDue) и телефоном (DelaSteps.due)."""
    due = day_of(parent.get("due_date"))
    if day_of(parent.get("focus_on")) == today or (due and due <= today):
        return today
    return due


def deed(t: dict | None) -> bool:
    """Дело, а не цель дерева и не напоминание: только от него новое внутри что-то берёт (у цели нет проекта, сделки,
    человека и срока — она живёт в дереве; в напоминание дела не вкладываются)."""
    return bool(t) and not t.get("goal") and (t.get("kind") or "task") != "remind"


def nest_fill(data: dict, parent: dict | None, today: dt.date) -> dict:
    """Новое дело внутри [parent] (поля операции task.create): проект, сделка, человек и срок — от родителя, если их
    в операции нет. Ключ есть, хоть null, — решение того, кто заводит («-» строки Claude, пустое у коннектора, очищенное
    поле телефона): не трогаем. Сделка — только когда проект нового тот же, что у родителя (назвал другой проект —
    сделка родителя ему чужая). Срок — step_due; унаследованный срок снимает «потом» (back_on): дело внутри вернётся
    вместе с родителем (до 16.10.2026 — store._back_inside). Родитель — цель или напоминание — ничего не даёт. «Зачем»
    здесь не ставится: его ставит тот, кто знает, что можно (только хозяину, правило базы). Меняет и возвращает data."""
    if not deed(parent):
        return data
    for k in ("project_id", "person_id"):
        if k not in data and parent.get(k):
            data[k] = str(parent[k])
    if ("deal_id" not in data and parent.get("deal_id")
            and str(data.get("project_id") or "") == str(parent.get("project_id") or "")):
        data["deal_id"] = str(parent["deal_id"])
    if "due_date" not in data:
        due = step_due(parent, today)
        if due:
            data["due_date"] = due.isoformat()
            if data.get("back_on"):
                data["back_on"] = data["back_said"] = None
    return data


# ── Поиск по словам ─────────────────────────────────────────────────────


def _keys(text) -> list[str]:
    """Основы значимых слов: падеж не мешает («сверку» = «Сверка»), служебные — мимо, мягкий знак не считается."""
    out: list[str] = []
    for w in people.words(text):
        if w in STOP:
            continue
        k = people.stem(w).replace("ь", "").replace("ъ", "")
        if k and k not in out:
            out.append(k)
    return out


def _hit(a: str, b: str) -> bool:
    """Одно слово: основы равны («кп» и «КП» — тоже, хоть и коротко) или одна продолжает другую буквами окончания."""
    return a == b or people.same(a, b)


def _share(said: list[str], title: list[str]) -> float:
    """Доля общих основ — от меньшего из двух: короткое «сверка» целиком есть в «Сверка с банком» (1,0), и короткое
    название целиком есть в длинной фразе. Длинное название не тонет оттого, что Саша назвал его двумя словами."""
    if not said or not title:
        return 0.0
    common = sum(1 for t in title if any(_hit(s, t) for s in said))
    return common / min(len(said), len(title))


def score(said: str, title: str, said_keys: list[str] | None = None) -> float:
    """Сходство сказанного с названием дела, 0…1: полное вхождение названия словами — 1,0, иначе доля общих основ."""
    ws, wt = people.words(said), people.words(title)
    if wt and f" {' '.join(wt)} " in f" {' '.join(ws)} ":
        return 1.0
    return _share(said_keys if said_keys is not None else _keys(said), _keys(title))


def rank(conn, user: str, said: str, project_id=None, person_id=None, child_id: str | None = None) -> dict:
    """Родитель по словам — среди своих открытых дел (не целей и не напоминаний; RLS — глазами того, кто кладёт).

    Оценка — score, плюс PROJECT_BONUS за тот же проект и PERSON_BONUS за того же человека (только у дела с хоть одним
    общим словом: проект сам по себе родителя не делает). Ответ: sure, у уверенного — why_id, num, title, score; cands —
    до CANDS_MAX кандидатов от CAND_MIN, лучший первым ({id, num, title, score}). child_id — что кладём: само дело и всё
    внутри него не кандидаты (петля)."""
    keys = _keys(said)
    if not keys:  # одни служебные слова («к делу») — искать нечего
        return {"sure": False, "cands": []}
    rows = conn.execute(
        "SELECT t.id, t.num, t.title, t.project_id, t.person_id FROM tasks.tasks t "
        "LEFT JOIN tasks.krug k ON k.id = t.id AND k.kind = 'task' "
        "WHERE t.owner_id = %s AND t.status = 'open' AND t.kind = 'task' AND NOT coalesce(k.goal, false)",
        (user,)).fetchall()
    scored = []
    for r in rows:
        if child_id and str(r["id"]) == str(child_id):
            continue
        s = score(said, r["title"], keys)
        if s <= 0:
            continue
        if project_id and r["project_id"] and str(r["project_id"]) == str(project_id):
            s += PROJECT_BONUS
        if person_id and r["person_id"] and str(r["person_id"]) == str(person_id):
            s += PERSON_BONUS
        scored.append((round(s, 3), r))
    scored.sort(key=lambda x: (-x[0], x[1]["num"]))
    top = []
    for s, r in scored:
        if s + _EPS < CAND_MIN or len(top) >= CANDS_MAX:
            break
        if child_id and _below(conn, str(r["id"]), str(child_id)):
            continue
        top.append({"id": str(r["id"]), "num": r["num"], "title": r["title"], "score": s})
    best, second = (top[0]["score"] if top else 0.0), (top[1]["score"] if len(top) > 1 else 0.0)
    out = {"sure": bool(top) and best + _EPS >= SURE_MIN and best - second + _EPS >= SURE_GAP, "cands": top}
    if out["sure"]:
        out.update(why_id=top[0]["id"], num=top[0]["num"], title=top[0]["title"], score=best)
    return out


# ── Куда класть ────────────────────────────────────────────────────────


class _Bad(Exception):
    pass


def _out(kind: str, error: str) -> dict:
    return {"kind": kind, "sure": False, "error": error}


def _name(t: dict) -> str:
    title = str(t.get("title") or "")
    return f"#{t['num']} «{title if len(title) <= 60 else title[:59] + '…'}»"


def _below(conn, target_id: str, child_id: str) -> bool:
    """Лежит ли [target_id] где-то внутри [child_id] (вверх по «Зачем» от цели встречается само дело) — тогда вложить
    дело туда значит замкнуть круг. Строки Круга — свои (RLS), а вкладывать и так можно только в своё."""
    got = conn.execute(
        "WITH RECURSIVE up(id, n) AS (SELECT %s::uuid, 0 UNION ALL "
        "SELECT k.why_id, up.n + 1 FROM tasks.krug k JOIN up ON k.id = up.id WHERE k.why_id IS NOT NULL AND up.n < %s) "
        "SELECT 1 FROM up WHERE id = %s::uuid LIMIT 1", (target_id, _LOOP_DEPTH, child_id)).fetchone()
    return bool(got)


_FORMS = "номером дела или цели («#57»), «^1» (новое из этого ответа), «направление …» или «-» (вынуть)"


def resolve(conn, user: str, into, made: list | None = None, child: dict | None = None) -> dict:
    """Куда класть — из строки входа. Ответ:

    - kind: keep («» — не трогать), out («-» — вынуть), task (дело), goal (цель дерева), new («^k» — новое из этого же
      ответа), direction (прямо на направление), words (слова — уверенного дела не нашлось);
    - why_id — что ставить в «Зачем» (у keep его нет; out и direction — None);
    - directions — [ключ] у direction (дело повиснет прямо на направлении, как бросок на колонку дерева);
    - sure — решено; error — почему нельзя, словами Саши (тогда sure False и why_id нет);
    - title, num — у task и goal, для строки ответа; by: "words" и cands — у найденного словами (rank).

    made — id новых дел этого же ответа по порядку (to_ops даёт им uuid заранее: «^k» и родитель ложатся одной пачкой
    apply_ops, родители первыми). child — что кладём: {"id"} у существующего дела (петля и «в самого себя»), {"goal":
    True} у цели (цель кладут на цель или направление, а не в дело), {"project_id", "person_id"} — для бонуса rank."""
    s = str(into if into is not None else "").strip()
    child = child or {}
    me = str(child.get("id") or "")
    if not s:
        return {"kind": "keep", "sure": True}
    if s == "-":
        return {"kind": "out", "why_id": None, "sure": True}
    if s.startswith("^"):
        k = s[1:].strip()
        if not k.isdigit() or not 1 <= int(k) <= len(made or []):
            return _out("new", f"«{s}»: в этом ответе нет {k or '?'}-го нового дела")
        got = str((made or [])[int(k) - 1])
        if me and got == me:
            return _out("new", "в самого себя не вложить")
        return {"kind": "new", "why_id": got, "sure": True}
    m = _DIRECTION.match(s)
    if m:
        name = m.group(1).strip(_QUOTES)
        try:
            keys = krug.norm_directions(conn, user, [name], _Bad)
        except _Bad as e:
            return _out("direction", str(e))
        if not keys:
            return _out("direction", "какое направление — не названо")
        return {"kind": "direction", "why_id": None, "directions": keys[:1], "sure": True}
    if s.lstrip("#").isdigit():
        from . import store  # store зовёт этот модуль из op_task_create: импорт здесь, а не наверху

        t = store.task_by(conn, s)
        if not t:
            return _out("task", f"дела #{s.lstrip('#')} нет или оно не видно")
        kind = "goal" if t.get("goal") else "task"
        if str(t.get("owner_id")) != str(user):
            return _out(kind, f"{_name(t)} — чужое дело: вкладывать можно только в своё (Круг личный)")
        if t.get("status") != "open":
            return _out(kind, f"{_name(t)} уже закрыто — в закрытое не кладут")
        if t.get("kind") == "remind":
            return _out(kind, f"{_name(t)} — напоминание, а не дело: в него ничего не вкладывают")
        if t.get("no_steps"):
            return _out(kind, f"под целью {_name(t)} дел не заводят — она ориентир, а не задача")
        if child.get("goal") and kind == "task":
            return _out(kind, f"цель кладут в цель или на направление, а не в дело {_name(t)}")
        if me and str(t["id"]) == me:
            return _out(kind, "в самого себя не вложить")
        if me and _below(conn, str(t["id"]), me):
            return _out(kind, f"Нельзя: {_name(t)} уже внутри того, что вкладываешь («зачем» по кругу)")
        return {"kind": kind, "why_id": str(t["id"]), "sure": True, "num": t["num"], "title": t["title"]}
    if child.get("goal"):
        return _out("words", f"«{s}»: цель кладут в цель или на направление — {_FORMS}")
    got = rank(conn, user, s, child.get("project_id"), child.get("person_id"), me or None)
    if got["sure"]:
        return {"kind": "task", "why_id": got["why_id"], "sure": True, "num": got["num"], "title": got["title"],
                "by": "words", "cands": got["cands"]}
    if got["cands"]:
        names = "; ".join(_name(c) for c in got["cands"])
        return {**_out("words", f"«{s}» — похоже на несколько дел: {names} — назови номером («#57»)"),
                "cands": got["cands"]}
    return {**_out("words", f"«{s}»: такого открытого дела не нашёл — куда: {_FORMS}"), "cands": []}
