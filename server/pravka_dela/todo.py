"""Дела в виде Todoist (dela_0036, 19.10.2026): приоритет, дедлайн, повтор, разделы проекта.

Слово Саши 18.10.2026: «скопировать и дизайн, и фичи Todoist; из нашего оставить вопрос Claude, дерево дел и все связи
с CRM». Новый веб (/t) рисует Todoist, а правила, которые не должны зависеть от клиента, — здесь:

- priority — p1…p4 как в интерфейсе (1 — срочно, 4 — без приоритета);
- повтор — due_rule (как сказано) и recur (разобранное правило). Разбирает фразу клиент (веб — свой разборщик, как
  Quick Add у Todoist), сервер проверяет правило и считает следующий срок. Выполнение повторяющегося дела не закрывает
  его, а переносит срок (next_due) — так ведут себя и старые клиенты: телефон и прежний веб шлют task.done, а дело
  остаётся открытым с новым сроком. «Выполнить навсегда» — task.done с forever: true;
- раздел — в проекте дела: назвали раздел без проекта — дело переезжает в его проект; сменили проект — раздел снимается.

Правило повтора (recur):
    {"every": "day" | "workday" | "week" | "month" | "year",
     "n": 1…365,                    — каждые n единиц;
     "days": [1…7],                 — дни недели ISO (1 — пн), только у week;
     "monthday": 1…31 | -1,         — число месяца (-1 — последний день), только у month;
     "from": "due" | "done",        — от планового срока (каждый) или от дня выполнения (каждые!);
     "until": "YYYY-MM-DD"}         — до какого дня (необязательно).
Следующий срок всегда позже сегодняшнего дня (пропущенные разы перескакиваются), как у Todoist.
"""

from __future__ import annotations

import calendar
import datetime as dt
from typing import Any

from psycopg.types.json import Jsonb

EVERY = ("day", "workday", "week", "month", "year")
COLORS = ("berry_red", "red", "orange", "yellow", "olive_green", "lime_green", "green", "mint_green", "teal", "sky_blue",
          "light_blue", "blue", "grape", "violet", "lavender", "magenta", "salmon", "charcoal", "grey", "taupe")
SECTION_FIELDS = {"project_id", "name", "pos", "collapsed", "archived"}
MAX_STEPS = 2000  # защита от вечного цикла: правило, у которого нет следующего дня


def _int(v: Any, lo: int, hi: int) -> int | None:
    if isinstance(v, bool) or not isinstance(v, int) or not lo <= v <= hi:
        return None
    return v


def recur_check(raw: Any, err) -> dict:
    """Правило повтора от клиента — проверенным словарём; кривое — отказ словами (OpError вызывающего)."""
    if not isinstance(raw, dict):
        raise err("повтор: правило — словарь")
    extra = set(raw) - {"every", "n", "days", "monthday", "from", "until"}
    if extra:
        raise err(f"повтор: нет таких полей — {', '.join(sorted(extra))}")
    every = raw.get("every")
    if every not in EVERY:
        raise err("повтор: every — day, workday, week, month или year")
    n = _int(raw.get("n", 1), 1, 365)
    if n is None:
        raise err("повтор: n — целое от 1 до 365")
    out: dict[str, Any] = {"every": every, "n": n, "from": raw.get("from") or "due"}
    if out["from"] not in ("due", "done"):
        raise err("повтор: from — due (от срока) или done (от выполнения)")
    if raw.get("days") is not None:
        days = raw["days"]
        if every != "week" or not isinstance(days, list) or not days or any(_int(d, 1, 7) is None for d in days):
            raise err("повтор: days — дни недели 1…7, только у еженедельного")
        out["days"] = sorted(set(days))
    if raw.get("monthday") is not None:
        md = raw["monthday"]
        if every != "month" or (_int(md, 1, 31) is None and md != -1):
            raise err("повтор: monthday — число 1…31 или -1 (последний день), только у ежемесячного")
        out["monthday"] = md
    if raw.get("until"):
        try:
            out["until"] = dt.date.fromisoformat(str(raw["until"])).isoformat()
        except ValueError:
            raise err("повтор: until — дата ГГГГ-ММ-ДД") from None
    return out


def _add_months(d: dt.date, months: int, monthday: int | None) -> dt.date:
    m = d.month - 1 + months
    y, m = d.year + m // 12, m % 12 + 1
    last = calendar.monthrange(y, m)[1]
    day = last if monthday == -1 else min(monthday or d.day, last)
    return dt.date(y, m, day)


def _step(rule: dict, d: dt.date, anchor: dt.date) -> dt.date:
    """Следующий раз после d. anchor — исходный срок: от него помнится число месяца (31-е в феврале — 28-е, в марте
    снова 31-е) и неделя отсчёта у «каждые 2 недели по пн и чт»."""
    every, n = rule["every"], rule["n"]
    if every == "day":
        return d + dt.timedelta(days=n)
    if every == "workday":
        left, x = n, d
        while left:
            x += dt.timedelta(days=1)
            if x.isoweekday() <= 5:
                left -= 1
        return x
    if every == "week":
        days = rule.get("days")
        if not days:
            return d + dt.timedelta(weeks=n)
        # Неделя отсчёта — та, в которой исходный срок; дни — в «рабочих» неделях (каждая n-я от неё).
        week0 = anchor - dt.timedelta(days=anchor.isoweekday() - 1)
        x = d
        for _ in range(7 * n + 7):
            x += dt.timedelta(days=1)
            weeks = (x - dt.timedelta(days=x.isoweekday() - 1) - week0).days // 7
            if x.isoweekday() in days and weeks % n == 0:
                return x
        return d + dt.timedelta(weeks=n)
    if every == "month":
        months = (d.year - anchor.year) * 12 + d.month - anchor.month + n
        return _add_months(anchor, months, rule.get("monthday"))
    # year: 29 февраля в невисокосный — 28-е
    y = d.year + n
    last = calendar.monthrange(y, anchor.month)[1]
    return dt.date(y, anchor.month, min(anchor.day, last))


def next_due(rule: dict, due: dt.date | None, today: dt.date) -> dt.date | None:
    """Следующий срок после выполнения сегодня; None — повтор кончился (until позади), дело закрывается.

    from due — от планового срока: шагаем от него, пропуская разы, которые уже позади (следующий — позже сегодня).
    from done — от дня выполнения: один шаг от сегодня. Ежедневное — всегда от выполнения, как у Todoist: выполнил
    вчерашнее сегодня — следующее завтра, а не «вчера + 1 = сегодня»."""
    base = due or today
    if rule.get("from") == "done" or (rule["every"] == "day" and rule["n"] == 1):
        nxt = _step(rule, today, base if rule.get("from") != "done" else today)
    else:
        nxt = _step(rule, base, base)
        for _ in range(MAX_STEPS):
            if nxt > today:
                break
            nxt = _step(rule, nxt, base)
    until = rule.get("until")
    if until and nxt > dt.date.fromisoformat(until):
        return None
    return nxt


def norm(conn, cur: dict | None, data: dict, err) -> dict:
    """Поля Todoist у дела (task.create — cur None, task.set — cur нынешняя строка): проверка и правила раздела."""
    if "priority" in data:
        p = data["priority"]
        if p is None:
            data["priority"] = 4
        elif _int(p, 1, 4) is None:
            raise err("приоритет — 1, 2, 3 или 4 (p1 — срочно, p4 — без приоритета)")
    if "recur" in data or "due_rule" in data:
        rec, said = data.get("recur"), data.get("due_rule")
        if rec is None and said is None:
            data["recur"], data["due_rule"] = None, None
        elif rec is None or said is None:
            raise err("повтор: правило (recur) и слова (due_rule) — вместе")
        else:
            if not isinstance(said, str) or not said.strip() or len(said) > 150:
                raise err("повтор: due_rule — слова до 150 знаков")
            rule = recur_check(rec, err)
            due = data.get("due_date", cur.get("due_date") if cur else None)
            if not due:
                raise err("повтор без срока: назови и первый день")
            if rule["every"] == "month" and "monthday" not in rule:
                # «Каждый месяц» от 31-го: в феврале 28-е, в марте снова 31-е — число помнится в правиле, а не в сроке,
                # который после февраля уже 28-й.
                rule["monthday"] = (due if isinstance(due, dt.date) else dt.date.fromisoformat(str(due))).day
            data["recur"] = Jsonb(rule)
    elif cur and cur.get("recur") and "due_date" in data and not data["due_date"]:
        # Сняли срок у повторяющегося — повтор уходит вместе с ним (у Todoist «без срока» снимает и повтор).
        data["recur"], data["due_rule"] = None, None
    if "done_count" in data:
        # Счётчик раз — только вниз: «Отменить» выполнение повтора (книга выполнений снимет последний раз, dela_0037).
        # Вверх его двигает только сервер, выполняя повтор: иначе выполнения можно было бы дописать руками.
        n = _int(data["done_count"], 0, 1_000_000)
        if n is None or cur is None or n > (cur.get("done_count") or 0):
            raise err("done_count — только вниз: «Отменить» выполнение повтора")
        data["done_count"] = n
    # Раздел — в проекте дела.
    sec_id = data.get("section_id", cur.get("section_id") if cur else None)
    if "section_id" in data and data["section_id"]:
        sec = conn.execute("SELECT id, project_id, archived FROM tasks.sections WHERE id = %s", (data["section_id"],)).fetchone()
        if not sec:
            raise err("нет такого раздела или он не виден")
        if sec["archived"]:
            raise err("раздел в архиве — сначала верни его")
        if "project_id" in data and str(data["project_id"] or "") != str(sec["project_id"]):
            raise err("раздел из другого проекта")
        data["project_id"] = sec["project_id"]
    elif "project_id" in data and sec_id and "section_id" not in data:
        sec = conn.execute("SELECT project_id FROM tasks.sections WHERE id = %s", (sec_id,)).fetchone()
        if not sec or str(sec["project_id"]) != str(data["project_id"] or ""):
            data["section_id"] = None
    return data


def done_recurring(conn, cur: dict, today: dt.date) -> dict | None:
    """Выполнили повторяющееся дело: правка вместо «сделано» — новый срок, +1 к счётчику, «Сейчас» гаснет, напоминание
    по времени сдвигается вместе со сроком. None — повтор кончился, дело закрывается как обычное."""
    rule = cur.get("recur")
    if not rule:
        return None
    nxt = next_due(rule, cur.get("due_date"), today)
    if nxt is None:
        return None
    out: dict[str, Any] = {"due_date": nxt, "done_count": (cur.get("done_count") or 0) + 1, "focus_on": None}
    if cur.get("remind_at") and cur.get("due_date"):
        out["remind_at"] = cur["remind_at"] + (nxt - cur["due_date"])
    return out


def reset_inside(conn, task_id) -> tuple[list[str], int]:
    """Повтор выполнен — сделанные дела внутри снова открыты, как у Todoist (выполненные подзадачи сбрасываются; их
    сроки не двигаются; слово Саши 19.10: «давай как тудуист все делать»). Вглубь — по Кругу (дела внутри дел внутри).
    Выполнение остаётся в книге выполнений: сброс идёт с dela.keep_done = on (dela_0037), очки не пропадают.
    Ответ — (кого открыли, сколько всего дел внутри, кроме отменённых) для тоста «Выполнено и снова добавлено…»."""
    rows = conn.execute(
        """
        WITH RECURSIVE inside AS (
            SELECT k.id FROM tasks.krug k WHERE k.why_id = %s AND k.kind = 'task' AND NOT k.goal
            UNION
            SELECT k.id FROM tasks.krug k JOIN inside i ON k.why_id = i.id WHERE k.kind = 'task' AND NOT k.goal
        )
        SELECT t.id, t.status FROM inside i JOIN tasks.tasks t ON t.id = i.id WHERE t.kind = 'task' AND t.status <> 'cancelled'
        """,
        (task_id,),
    ).fetchall()
    done = [r["id"] for r in rows if r["status"] == "done"]
    if done:
        conn.execute("SELECT set_config('dela.keep_done', 'on', true)")
        conn.execute("UPDATE tasks.tasks SET status = 'open' WHERE id = ANY(%s)", (done,))
        conn.execute("SELECT set_config('dela.keep_done', '', true)")
    return [str(x) for x in done], len(rows)


def op_section_create(conn, user, op, err):
    data = dict(op.get("data") or {})
    extra = set(data) - SECTION_FIELDS - {"id"}
    if extra:
        raise err(f"раздел: нет таких полей — {', '.join(sorted(extra))}")
    if not str(data.get("name") or "").strip():
        raise err("у раздела нет названия")
    if not data.get("project_id"):
        raise err("раздел живёт в проекте — назови проект")
    data["name"] = str(data["name"]).strip()
    data["created_by"] = user
    cols = list(data)
    row = conn.execute(
        f"INSERT INTO tasks.sections ({', '.join(cols)}) VALUES ({', '.join(['%s'] * len(cols))}) RETURNING *",
        [data[c] for c in cols],
    ).fetchone()
    return {"row": row}


def op_section_set(conn, user, op, err):
    data = dict(op.get("set") or {})
    extra = set(data) - SECTION_FIELDS
    if extra:
        raise err(f"раздел: нет таких полей — {', '.join(sorted(extra))}")
    if "name" in data:
        data["name"] = str(data["name"] or "").strip()
        if not data["name"]:
            raise err("у раздела нет названия")
    cur = conn.execute("SELECT * FROM tasks.sections WHERE id = %s", (op.get("id"),)).fetchone()
    if not cur:
        raise err("нет такого раздела или он не виден")
    if "project_id" in data and str(data["project_id"]) != str(cur["project_id"]):
        # Раздел переехал в другой проект — с ним его открытые дела (как «переместить раздел» у Todoist).
        conn.execute("UPDATE tasks.tasks SET project_id = %s WHERE section_id = %s AND status = 'open'",
                     (data["project_id"], cur["id"]))
    if not data:
        return {"row": cur}
    sets = ", ".join(f"{k} = %s" for k in data)
    row = conn.execute(f"UPDATE tasks.sections SET {sets} WHERE id = %s RETURNING *", [*data.values(), cur["id"]]).fetchone()
    if row is None:
        raise err("раздел можно только смотреть")
    if data.get("archived"):
        # Архивный раздел: открытые дела остаются в проекте без раздела (не теряются вместе с заголовком).
        conn.execute("UPDATE tasks.tasks SET section_id = NULL WHERE section_id = %s AND status = 'open'", (cur["id"],))
    return {"row": row}


def color_check(v: Any, err) -> Any:
    if v is not None and v not in COLORS:
        raise err("цвет — из палитры: " + ", ".join(COLORS))
    return v
