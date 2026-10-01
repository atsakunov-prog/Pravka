"""Что архив отвечает Claude: схема, SQL, поиск по сказанному, сутки целиком.

Всё читается ролью «только чтение» (PRAVKA_DB_READER_URL) и только из схемы
life. Ответы — компактным текстом, не JSON: так в разговор влезает втрое
больше жизни.
"""

from __future__ import annotations

import datetime as dt
import json
import re
from decimal import Decimal
from typing import Any, Iterable, Sequence

import psycopg

from .config import Config

MAX_ROWS = 500
MAX_CELL = 400


def _reader(cfg: Config) -> psycopg.Connection:
    conn = psycopg.connect(cfg.reader_url, autocommit=True)
    conn.execute("SET search_path = life, public")
    return conn


def cell(v: Any, limit: int = MAX_CELL) -> str:
    if v is None:
        return ""
    if isinstance(v, bool):
        return "да" if v else "нет"
    if isinstance(v, Decimal):
        s = format(v.normalize(), "f")
        return s
    if isinstance(v, float):
        return f"{v:.6g}"
    if isinstance(v, dt.datetime):
        return v.isoformat(timespec="minutes")
    if isinstance(v, (dt.date, dt.time)):
        return v.isoformat()
    if isinstance(v, (dict, list)):
        s = json.dumps(v, ensure_ascii=False, separators=(",", ":"))
    else:
        s = str(v)
    s = s.replace("\r", " ").replace("\n", " ⏎ ").replace("\t", " ")
    return s if len(s) <= limit else s[: limit - 1] + "…"


def table(names: Sequence[str], rows: Iterable[Sequence[Any]], limit: int = MAX_CELL) -> str:
    lines = [" | ".join(names)]
    for r in rows:
        lines.append(" | ".join(cell(v, limit) for v in r))
    return "\n".join(lines)


def freshness_line(conn: psycopg.Connection) -> str:
    rows = conn.execute("SELECT source, last_ok, last_error, last_error_at FROM life.freshness ORDER BY source").fetchall()
    if not rows:
        return "Свежесть: источники ещё ничего не присылали."
    now = dt.datetime.now(dt.timezone.utc)
    parts = []
    for source, ok, err, err_at in rows:
        name = "телефон" if source.startswith("phone:") else source
        if ok is None:
            parts.append(f"{name}: ни разу")
            continue
        mins = int((now - ok).total_seconds() // 60)
        age = f"{mins} мин назад" if mins < 120 else f"{mins // 60} ч назад"
        bad = f" (последняя ошибка {err_at:%d.%m %H:%M}: {cell(err, 120)})" if err and err_at and err_at > ok else ""
        parts.append(f"{name}: {age}{bad}")
    return "Свежесть: " + " · ".join(parts)


# ------------------------------------------------------------------ schema

def schema(cfg: Config, view: str | None = None) -> str:
    with _reader(cfg) as conn:
        fresh = freshness_line(conn)
        if not view:
            rows = conn.execute(
                "SELECT c.relname, obj_description(c.oid, 'pg_class'), "
                "  (SELECT string_agg(a.attname, ', ' ORDER BY a.attnum) FROM pg_attribute a "
                "    WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped) "
                "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                "WHERE n.nspname = 'life' AND c.relkind IN ('v', 'r', 'm') ORDER BY c.relname"
            ).fetchall()
            out = [fresh, "", "Виды схемы life (search_path уже стоит на life). Подробно о колонках — schema(view)."]
            for name, comment, cols in rows:
                out.append(f"\n{name} — {comment or ''}\n  колонки: {cols}")
            return "\n".join(out)
        rows = conn.execute(
            "SELECT a.attname, format_type(a.atttypid, a.atttypmod), col_description(c.oid, a.attnum) "
            "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
            "JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped "
            "WHERE n.nspname = 'life' AND c.relname = %s ORDER BY a.attnum",
            (view,),
        ).fetchall()
        if not rows:
            return f"Вида {view} в схеме life нет. Список — schema() без аргумента."
        comment = conn.execute(
            "SELECT obj_description(('life.' || quote_ident(%s))::regclass, 'pg_class')", (view,)
        ).fetchone()[0]
        lines = [f"{view} — {comment or ''}", ""]
        for name, typ, c in rows:
            lines.append(f"  {name} {typ}" + (f" — {c}" if c else ""))
        return "\n".join(lines)


# ------------------------------------------------------------------ sql

def run_sql(cfg: Config, query: str, max_rows: int = MAX_ROWS) -> str:
    q = query.strip().rstrip(";").strip()
    if not q:
        return "Пустой запрос."
    max_rows = max(1, min(int(max_rows), MAX_ROWS))
    try:
        with _reader(cfg) as conn:
            with conn.transaction():
                conn.execute("SET TRANSACTION READ ONLY")
                cur = conn.execute(q)
                if cur.description is None:
                    return "Запрос выполнился и строк не вернул."
                names = [d.name for d in cur.description]
                rows = cur.fetchmany(max_rows + 1)
    except psycopg.Error as e:
        diag = e.diag
        parts = [f"Ошибка SQL: {diag.message_primary or e}"]
        if diag.message_detail:
            parts.append(f"Подробно: {diag.message_detail}")
        if diag.message_hint:
            parts.append(f"Подсказка: {diag.message_hint}")
        if diag.statement_position:
            parts.append(f"Место в запросе: знак {diag.statement_position}")
        return "\n".join(parts)
    more = len(rows) > max_rows
    rows = rows[:max_rows]
    text = table(names, rows)
    tail = f"\n(строк: {len(rows)})" if not more else f"\n(показаны первые {max_rows} строк — сузь запрос или сложи в SQL)"
    return text + tail


# ------------------------------------------------------------------ search

def prefix_query(q: str) -> str:
    """Начала слов для tsv_words: «Марианна» → мариан:* ловит и «Марианной».

    Русский стеммер режет имена по-разному в разных падежах, поэтому к поиску
    по словоформам добавлен поиск по началу слова: длинное слово теряет два
    последних знака, среднее — один, короткое ищется целиком."""
    terms = []
    for w in re.findall(r"\w+", q.lower().replace("ё", "е")):
        base = w[:-2] if len(w) >= 7 else w[:-1] if len(w) >= 5 else w
        terms.append(base + ":*")
    return " & ".join(terms)


def _like_escape(s: str) -> str:
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def search(
    cfg: Config,
    query: str,
    date_from: str | None = None,
    date_to: str | None = None,
    domain: str | None = None,
    limit: int = 30,
    order: str = "new",
) -> str:
    query = (query or "").strip()
    if not query:
        return "Что искать? Слова или фраза."
    limit = max(1, min(int(limit), 200))
    where = ["true"]
    args: list[Any] = []
    if date_from:
        where.append("day >= %s::date")
        args.append(date_from)
    if date_to:
        where.append("day <= %s::date")
        args.append(date_to)
    if domain:
        where.append("domain = %s")
        args.append(domain)
    filt = " AND ".join(where)
    sort = "rank DESC, day DESC NULLS LAST" if order == "best" else "day DESC NULLS LAST, time_local DESC NULLS LAST, rank DESC"

    with _reader(cfg) as conn:
        fresh = freshness_line(conn)
        how = "по словоформам"
        opts = "MaxWords=35, MinWords=15, MaxFragments=2, FragmentDelimiter=\" … \", StartSel=**, StopSel=**"
        pre = prefix_query(query) or "''"
        sql_fts = (
            "WITH q AS (SELECT websearch_to_tsquery('russian', %s) AS ru, to_tsquery('simple', %s) AS pre) "
            "SELECT domain, day, time_local, ref, part, "
            "  CASE WHEN tsv_words @@ q.pre THEN ts_headline('simple', text, q.pre, %s) "
            "       ELSE ts_headline('russian', text, q.ru, %s) END AS snip, "
            "  ts_rank(tsv, q.ru) + ts_rank(tsv_words, q.pre) AS rank, count(*) OVER () AS total "
            f"FROM life.said, q WHERE (tsv @@ q.ru OR tsv_words @@ q.pre) AND {filt} ORDER BY {sort} LIMIT %s"
        )
        rows = conn.execute(sql_fts, [query, pre, opts, opts, *args, limit]).fetchall()
        if not rows:
            how = "по подстроке"
            sql_like = (
                "SELECT domain, day, time_local, ref, part, "
                "  substring(text FROM greatest(1, strpos(lower(text), lower(%s)) - 120) FOR 300) AS snip, "
                "  1.0 AS rank, count(*) OVER () AS total "
                f"FROM life.said WHERE text ILIKE %s AND {filt} ORDER BY {sort} LIMIT %s"
            )
            rows = conn.execute(sql_like, [query, "%" + _like_escape(query) + "%", *args, limit]).fetchall()
        if not rows and len(query) >= 5:  # короткое слово похоже на всё подряд
            how = "похожие слова (опечатки)"
            # Опечатки распознавания: похожесть по триграммам с порогом 0,4
            # (заводские 0,6 не ловят «отчетнасть» → «отчётностью»), ё как е.
            norm = query.lower().replace("ё", "е")
            sql_fuzzy = (
                "SELECT domain, day, time_local, ref, part, snip, rank, count(*) OVER () AS total FROM ("
                "  SELECT domain, day, time_local, ref, part, left(text, 300) AS snip, "
                "    word_similarity(%s, replace(lower(text), 'ё', 'е')) AS rank "
                f"  FROM life.said WHERE {filt}) s WHERE rank >= 0.4 ORDER BY rank DESC, day DESC NULLS LAST LIMIT %s"
            )
            rows = conn.execute(sql_fuzzy, [norm, *args, limit]).fetchall()

    if not rows:
        return f"{fresh}\nНичего не нашлось ни по словам, ни по подстроке, ни похожего: «{query}»."
    total = rows[0][7]
    head = f"{fresh}\nНайдено {total} ({how}), показано {len(rows)}:"
    lines = [head]
    for domain_, day, time_local, ref, part, snip, _rank, _total in rows:
        when = f"{day or '?'}" + (f" {time_local}" if time_local else "")
        lines.append(f"{when} · {domain_} · [{ref}{' ' + part if part and not part.startswith(('raw', 'text')) else ''}] {cell(snip, 600)}")
    return "\n".join(lines)


# ------------------------------------------------------------------ day

def _fmt_pace(s: Any) -> str:
    if s is None:
        return ""
    s = int(s)
    return f"{s // 60}:{s % 60:02d}/км"


def _rub(kop: Any) -> str:
    if kop is None:
        return ""
    r = Decimal(kop) / 100
    sign = "−" if r < 0 else "+"
    return f"{sign}{abs(r):,.0f} ₽".replace(",", " ")


def day(cfg: Config, date: str, full: bool = False) -> str:
    lim = 4000 if full else 220
    with _reader(cfg) as conn:
        fresh = freshness_line(conn)
        d = conn.execute("SELECT %s::date", (date,)).fetchone()[0]
        out = [fresh, ""]
        summary = conn.execute("SELECT dow, ribbon_min, points FROM life.days WHERE day = %s", (d,)).fetchone()
        out.append(f"{d.isoformat()}, {summary[0] if summary else ''}".rstrip(", "))

        entries = conn.execute(
            "SELECT start_local, end_local, minutes, category, title, client, source, raw, comment, open "
            "FROM life.entries WHERE day = %s ORDER BY start_at", (d,)
        ).fetchall()
        if entries:
            total = sum(e[2] or 0 for e in entries)
            pts = f", очки {summary[2]}" if summary and summary[2] is not None else ""
            out.append(f"\nЛента ({total} мин из 1440{pts}):")
            for st, en, mins, cat, title, client, source, raw, comment, open_ in entries:
                span = f"{st}–{en or 'сейчас'}"
                head = f"  {span} {mins or ''}м · {cat} · {title}" + (f" · {client}" if client else "")
                if source in ("auto", "gap"):
                    head += f" ({'заполнитель' if source == 'gap' else 'авто'})"
                out.append(head)
                if raw:
                    out.append(f"      сказал: {cell(raw.split(chr(10) + 'КБЖУ:')[0], lim)}")
                if comment:
                    out.append(f"      комментарий: {cell(comment, lim)}")

        meals = conn.execute(
            "SELECT m.time_local, m.kind, m.kcal, m.protein_g, m.fat_g, m.carbs_g, m.confidence, m.raw, "
            "  (SELECT string_agg(name || coalesce(' ' || round(grams) || ' г', ''), ', ' ORDER BY n) FROM life.meal_items i WHERE i.meal_id = m.id) "
            "FROM life.meals m WHERE m.day = %s AND m.confirmed ORDER BY m.at", (d,)
        ).fetchall()
        if meals:
            k = sum(float(m[2] or 0) for m in meals)
            p = sum(float(m[3] or 0) for m in meals)
            f_ = sum(float(m[4] or 0) for m in meals)
            c = sum(float(m[5] or 0) for m in meals)
            out.append(f"\nЕда: {len(meals)} приёмов, {k:.0f} ккал, Б {p:.0f} Ж {f_:.0f} У {c:.0f}:")
            for t, kind, kcal, pr, fa, ca, conf, raw, items in meals:
                out.append(f"  {t} {kind} {cell(kcal)} ккал (Б {cell(pr)} Ж {cell(fa)} У {cell(ca)}){' · ' + conf if conf and conf != 'точно' else ''} — {cell(items, lim)}")

        workouts = conn.execute(
            "SELECT start_local, type, name, minutes, km, pace_s_km, hr, load, feel, rpe FROM life.workouts WHERE day = %s ORDER BY start_at", (d,)
        ).fetchall()
        if workouts:
            out.append("\nТренировки:")
            for st, typ, name, mins, km, pace, hr, load, feel, rpe in workouts:
                bits = [f"{cell(mins)} мин"]
                if km:
                    bits.append(f"{cell(km)} км")
                if pace and typ in ("Run", "TrailRun", "VirtualRun", "Walk", "Hike"):
                    bits.append(_fmt_pace(pace))
                if hr:
                    bits.append(f"пульс {cell(hr)}")
                if load:
                    bits.append(f"load {cell(load)}")
                if feel:
                    bits.append(f"feel {feel}")
                if rpe:
                    bits.append(f"RPE {rpe}")
                out.append(f"  {st} {typ} · {name} · " + ", ".join(bits))

        w = conn.execute(
            "SELECT sleep_h, sleep_score, hrv, rhr, weight_kg, ctl, atl, tsb, steps, comment FROM life.wellness WHERE day = %s", (d,)
        ).fetchone()
        if w:
            bits = []
            labels = ["сон {} ч", "оценка сна {}", "HRV {}", "пульс покоя {}", "вес {} кг", "CTL {}", "ATL {}", "TSB {}", "шагов {}"]
            for label, v in zip(labels, w[:9]):
                if v is not None:
                    bits.append(label.format(cell(round(v, 1) if isinstance(v, Decimal) else v)))
            out.append("\nФорма: " + ", ".join(bits))
            if w[9]:
                out.append(f"  комментарий: {cell(w[9], lim)}")

        strength = conn.execute("SELECT id, block, minutes, feel, done, note FROM life.strength WHERE day = %s", (d,)).fetchall()
        for sid, block, mins, feel, done, note in strength:
            sets = conn.execute(
                "SELECT exercise, string_agg(cell, ' ' ORDER BY n) FROM ("
                "  SELECT exercise, n, amount::text || coalesce('×' || weight_kg::text || 'кг', '') AS cell "
                "  FROM life.strength_sets WHERE session_id = %s"
                ") s GROUP BY exercise ORDER BY exercise", (sid,)
            ).fetchall()
            line = f"\nСиловая: {block or ''}{', ' + str(mins) + ' мин' if mins else ''}{', feel ' + str(feel) if feel else ''}{', сделано' if done else ''}"
            out.append(line)
            for name, s in sets:
                out.append(f"  {name}: {s}")
            if note:
                out.append(f"  заметка: {cell(note, lim)}")
        g = conn.execute("SELECT status, hang_s, pullups, knee, note FROM life.gtg WHERE day = %s", (d,)).fetchone()
        if g:
            out.append(f"\nЗарядка: {g[0] or ''}" + (f", вис {g[1]} с" if g[1] else "") + (f", подтягиваний {g[2]}" if g[2] else "") + (f", колено {g[3]}" if g[3] else ""))

        money = conn.execute(
            "SELECT time_local, amount_kop, what, category_title, owner, shelf, income FROM life.money_live WHERE day = %s ORDER BY at", (d,)
        ).fetchall()
        if money:
            spent = -sum(m[1] for m in money if m[1] < 0 and m[5] == "family" and not m[6])
            out.append(f"\nДеньги: {len(money)} операций, траты семьи {_rub(-spent).lstrip('−+')}:")
            for t, kop, what, cat, owner, _shelf, _inc in money[: 60 if full else 25]:
                who = "" if owner == "sasha" else f" ({owner})"
                out.append(f"  {t or '--:--'} {_rub(kop)} {what or ''} · {cat or 'без категории'}{who}")
            if len(money) > (60 if full else 25):
                out.append(f"  … ещё {len(money) - (60 if full else 25)} (life.money_live)")

        ph = conn.execute("SELECT screen_min, pickups, glances, calls_min FROM life.phone_days WHERE day = %s", (d,)).fetchone()
        if ph:
            apps = conn.execute(
                "SELECT app, round(minutes) FROM life.phone_apps WHERE day = %s ORDER BY minutes DESC LIMIT 6", (d,)
            ).fetchall()
            calls = conn.execute(
                "SELECT who, round(minutes) FROM life.phone_calls WHERE day = %s ORDER BY minutes DESC LIMIT 6", (d,)
            ).fetchall()
            line = f"\nТелефон: экран {cell(ph[0])} мин, подъёмов {cell(ph[1])}, отвлечений {cell(ph[2])}"
            if apps:
                line += "; " + ", ".join(f"{a} {cell(m)}" for a, m in apps)
            out.append(line)
            if calls:
                out.append("  звонки: " + ", ".join(f"{w_} {cell(m)} мин" for w_, m in calls))

        dic = conn.execute(
            "SELECT count(*), min(time_local), max(time_local) FROM life.dictations WHERE day = %s", (d,)
        ).fetchone()
        if dic and dic[0]:
            out.append(f"\nПравка: {dic[0]} диктовок, с {dic[1]} до {dic[2]} (тексты — life.dictations или search)")
        talks = conn.execute("SELECT time_local, text FROM life.said WHERE day = %s AND domain = 'тренер' AND part = 'question' ORDER BY at", (d,)).fetchall()
        for t, q in talks:
            out.append(f"\nТренеру в {t}: {cell(q, lim)}")

    if len(out) <= 3:
        out.append("\nЗа эти сутки в архиве ничего нет.")
    return "\n".join(out)
