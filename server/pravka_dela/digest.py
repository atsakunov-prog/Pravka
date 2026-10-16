"""Дайджест Telegram на телефоне (13.10.2026, «Правка 5.0», dela_0014): сводку кладёт служба дайджеста, видит владелец.

Владелец хочет видеть последний дайджест на экране «Сегодня» телефона: карточка растворяется книзу, нажатие —
целиком. Сводка рождается у службы дайджеста на компе (C:\\Bot\\Digest\\auto.py, Opus пачкой по расписанию) и до
сих пор уходила только ботом в Telegram. Теперь служба после каждой сводки кладёт её копию сюда — операцией
digest.put через /api/ops тем же токеном, которым разносит переписку в «Новое», — а телефон забирает:
- последнюю — обычным синком Дел, полем digest: синк телефон и так тянет на каждом тике, лишнего запроса нет, и
  карточка есть и без сети (лежит в кэше синка);
- список за пару дней — видом digest (/api/view/digest?days=2), когда владелец листает назад.
Новых путей HTTP нет: /api/ops и /api/view уже в закрытом списке сайта-посредника, а служба ходит на 127.0.0.1.

Кто что может:
- пишет только служба дайджеста (actor svc:digest) — ни телефон, ни веб, ни другие службы владельца; узкие токены
  («Деньги», «Еда», «Спорт») до операции не доходят вовсе (api.SCOPED);
- читает только владелец Дел (роль owner): RLS «только своё», триггер базы не даёт строке лечь на не-владельца, а
  вид и синк ещё и сами сверяют роль — семья и команда дайджеста не видят ни в синке, ни видом.
Повтор безвреден: строка находится по (владелец, ref), та же сводка версии не двигает. op_id службе не нужен — с
ним отказ старого сервера («нет такой операции») запомнился бы в crm.ops_seen и вернулся бы и после установки.
Claude здесь не участвует: сводка уже готова, сервер только хранит её и собирает из пунктов простой текст.

С 14.10.2026 (dela_0018, digest_crm.py) та же операция раскладывает пункты сводки по карточкам CRM — записью
хронологии у рабочего человека и его клиента, видимой только владельцу; служба для этого шлёт ещё чаты окна
(sources: название, личный или группа, id в Telegram, кто писал). Разнос не удался — сводка всё равно ложится: телефону
она нужна и без карточек. Сводки ищутся и читаются видом digest (q, since, until, person_id, project_id) — им же
пользуются веб (#/digest) и коннектор claude.ai (инструменты digests и digest, mcp_tools.py).

С 16.10.2026 (этап 6 аудита, слово владельца «да. Делай», флаг lens) сводка несёт ещё since у «Ждут тебя», «Команду»
(team) и «Только тебе» (only_you) — выводы о людях и сделках. «Только тебе» видит только владелец: в простой текст
сводки (его копируют и пересылают) и в хронологию клиента оно не идёт, а ложится ещё строками crm.insights — с
состоянием для кнопок «Пригодилось / Мимо / Неверно» (insight.rate) и мерила (вид lens, insights.py).
"""

from __future__ import annotations

import datetime as dt
import hashlib
import logging
import re
import uuid
from typing import Any

from psycopg.types.json import Jsonb

from . import digest_crm, insights, people
from .meetings import MSK

log = logging.getLogger("pravka_dela.digest")

ACTOR = "svc:digest"     # токен службы дайджеста: python -m pravka_dela token --kind service --name digest
DAYS = 2                 # вид digest с завода — за двое суток: вчерашний вечер и сегодня
DAYS_MAX = 14
LIMIT = 20
LIMIT_MAX = 50
ITEMS_MAX = 40           # пунктов в разделе: сводка — «за минуту между делами», больше — мусор или сбой
FIELD_MAX = 1000         # знаков в поле пункта
OTHER_MAX = 2000
SNIPPET_LINES = 3        # строк сводки у найденного: искомое и рядом
SNIPPET_MAX = 400
COUNTS = ("created", "asks", "close", "update", "closed", "updated")
WD = ["пн", "вт", "ср", "чт", "пт", "сб", "вс"]
COLUMNS = "id, ref, started_at, ended_at, label, chats, msgs, summary, text, dela, created_at, updated_at, rev, seq"
# «Только тебе» (этап 6 аудита): до пяти пунктов у модели — с запасом; поля строки модели по порядку.
ONLY_YOU_MAX = 10
ONLY_YOU_FIELD = 600
ONLY_YOU_FIELDS = ("kind", "who", "chat", "at", "quote", "why", "do", "deal", "service")
KEY_OK = re.compile(r"^[a-z0-9][a-z0-9_-]{0,39}$")  # ключ пункта от службы (кнопки в Telegram) — латиница и цифры
KIND_OK = re.compile(r"^[a-z][a-z0-9_]{0,23}$")      # как crm.insights.kind
AT_OK = re.compile(r"^\d{1,2}\.\d{1,2}(?:\s+\d{1,2}:\d{2})?$")  # «ДД.ММ ЧЧ:ММ» пункта «Только тебе»


def is_owner(conn, user: str) -> bool:
    row = conn.execute("SELECT role FROM crm.users WHERE id = %s", (user,)).fetchone()
    return bool(row) and row["role"] == "owner"


def _s(v: Any, n: int = FIELD_MAX) -> str:
    """Строка в одну линию: пункт сводки — одна строка карточки; список или объект на месте строки — пусто."""
    if v is None or isinstance(v, (dict, list)):
        return ""
    return " ".join(str(v).split())[:n]


def _at(v: Any, what: str, err) -> dt.datetime | None:
    if v in (None, ""):
        return None
    try:
        t = dt.datetime.fromisoformat(str(v).strip().replace("Z", "+00:00"))
    except ValueError:
        raise err(f"дайджест: {what} — дата ISO («2026-10-13T20:00:00+03:00»)") from None
    return t if t.tzinfo else t.replace(tzinfo=MSK)  # без пояса — Москва: служба живёт на компе владельца


def _count(v: Any, what: str, err) -> int | None:
    if v in (None, ""):
        return None
    try:
        return max(0, int(v))
    except (TypeError, ValueError):
        raise err(f"дайджест: {what} — число") from None


def _key_of(*parts: str) -> str:
    """Ключ пункта «Только тебе» — по сути пункта (вид, кто, когда, цитата): та же сводка, присланная заново, даёт те же
    ключи, и оценка владельца («Пригодилось», «Мимо») остаётся у своего пункта."""
    return "oy-" + hashlib.md5("|".join(people.norm(p or "") for p in parts).encode()).hexdigest()[:12]


def parse_only_you(x: Any) -> dict | None:
    """Пункт «Только тебе» сводки → поля. Служба шлёт строку модели «kind · who · chat · at · «quote» · why · do · deal ·
    service» (объектом схема не прошла предел грамматики API, проба 15.10) или уже разобранный объект. Цитата в «ёлочках»
    может сама нести « · » — её собираем до закрывающей «»»; лишние части — в why. Пустое поле — «—». Не разобралось —
    вся строка в why: пункт не теряется, просто без полей."""
    if isinstance(x, dict):
        row = {f: _s(x.get(f), ONLY_YOU_FIELD) for f in ONLY_YOU_FIELDS}
        row["quote"] = row["quote"].strip("«»\"").strip()
        key = _s(x.get("key"), 40)
    elif isinstance(x, str) and x.strip():
        parts = [p.strip() for p in " ".join(x.split()).split(" · ")]
        if len(parts) < 5:
            return {**{f: "" for f in ONLY_YOU_FIELDS}, "why": _s(x, ONLY_YOU_FIELD), "key": _key_of(x)}
        # Название чата само бывает с « · » («Бета Групп · сделка»): цитату ищем по «ёлочке», время — перед ней,
        # чат — всё между «кто» и временем. Цитаты нет — время по виду «ДД.ММ ЧЧ:ММ», иначе — по порядку полей.
        q = next((i for i, p in enumerate(parts) if i >= 3 and p.startswith("«")), None)
        if q is None:
            q = next((i + 1 for i, p in enumerate(parts) if i >= 3 and AT_OK.match(p)), 4)
        head, rest = [parts[0], parts[1], " · ".join(parts[2:q - 1]), parts[q - 1]], parts[q:]
        if not rest:
            rest = [""]
        quote = rest.pop(0)
        if quote.startswith("«") and not quote.endswith("»"):
            while rest and not quote.endswith("»"):
                quote += " · " + rest.pop(0)
        tail = rest[-3:] if len(rest) > 4 else rest[1:]
        why = " · ".join(rest[: len(rest) - 3]) if len(rest) > 4 else (rest[0] if rest else "")
        vals = [*head, quote.strip("«»").strip(), why, *tail]
        row = {f: _s(v, ONLY_YOU_FIELD) for f, v in zip(ONLY_YOU_FIELDS, vals + [""] * len(ONLY_YOU_FIELDS))}
        key = ""
    else:
        return None
    row = {f: ("" if v in ("—", "-", "–") else v) for f, v in row.items()}
    row["kind"] = row["kind"].lower()
    if not any(row.values()):
        return None
    row["key"] = key if key and KEY_OK.match(key) else _key_of(row["kind"], row["who"], row["at"], row["quote"] or row["why"])
    return row


def norm_summary(s: Any, err) -> dict:
    """Сводка пунктами — ровно те разделы, что пишет служба (auto.SUMMARY_SCHEMA); чужие ключи не храним: незнакомый
    раздел молча пропал бы на экране, а в базе выглядел бы показанным.

    С 16.10.2026 (этап 6 аудита, слово владельца «да. Делай») — ещё три поля, которые до того молча пропадали:
    since у «Ждут тебя» (с какого дня ждёт), team — «Команда» (что команда ЗФ пообещала и к какому сроку) и only_you —
    «Только тебе» (выводы о людях и сделках, видит только владелец: в простой текст сводки не идут — его копируют и
    пересылают; пункты ещё и в crm.insights — op_put). Старый телефон рисует прежние четыре раздела и новых не видит."""
    if not isinstance(s, dict):
        raise err("дайджест: summary — объект {waiting, work, team, family, other, only_you}")

    def rows(key: str, fields: tuple[str, ...]) -> list[dict]:
        v = s.get(key) or []
        if not isinstance(v, list):
            raise err(f"дайджест: summary.{key} — список")
        out = []
        for x in v[:ITEMS_MAX]:
            if isinstance(x, dict):
                row = {f: _s(x.get(f)) for f in fields}
                if any(row.values()):
                    out.append(row)
        return out

    family = s.get("family") or []
    if not isinstance(family, list):
        raise err("дайджест: summary.family — список строк")
    mine = s.get("only_you") or []
    if not isinstance(mine, list):
        raise err("дайджест: summary.only_you — список строк «вид · кто · чат · когда · «цитата» · …»")
    only_you: list[dict] = []
    for x in mine[:ONLY_YOU_MAX]:
        row = parse_only_you(x)
        if row and row["key"] not in {r["key"] for r in only_you}:
            only_you.append(row)
    return {
        "waiting": rows("waiting", ("chat", "who", "what", "since")),
        "work": rows("work", ("topic", "text")),
        "team": rows("team", ("who", "what", "due")),
        "family": [t for t in (_s(x) for x in family[:ITEMS_MAX]) if t],
        "other": _s(s.get("other"), OTHER_MAX),
        "only_you": only_you,
    }


def norm_dela(d: Any, err) -> dict | None:
    """Что ушло в Дела из окна — числами и номером пачки; null — Дела в тот миг не ответили (строки «В Дела» нет)."""
    if d in (None, {}):
        return None
    if not isinstance(d, dict):
        raise err("дайджест: dela — объект с числами или null")
    out: dict[str, Any] = {k: _count(d.get(k), f"dela.{k}", err) or 0 for k in COUNTS}
    out["batch"] = _s(d.get("batch"), 64) or None
    return out


def where_of(w: dict) -> str:
    """«Кто (чат)» — как в Telegram (auto.render): чат не повторяем, если он и есть человек."""
    who, chat = w.get("who") or "", w.get("chat") or ""
    if not chat or chat.lower() in who.lower() or (who and who.lower() in chat.lower()):
        return who or chat
    return f"{who} ({chat})" if who else chat


def dela_line(d: dict | None) -> str:
    """Строка «В Дела» теми же словами, что под сводкой в Telegram (auto.dela_line), без «списком следом»."""
    if not d:
        return ""
    bits = [f"поставлено {d['created']}" if d.get("created") else None,
            f"вопросов {d['asks']}" if d.get("asks") else None,
            f"закрыть {d['close']}" if d.get("close") else None,
            f"уточнить {d['update']}" if d.get("update") else None,
            f"закрыто само {d['closed']}" if d.get("closed") else None,
            f"уточнено само {d['updated']}" if d.get("updated") else None]
    bits = [b for b in bits if b]
    return "В Дела: " + (", ".join(bits) if bits else "новых дел нет") + "."


def waiting_line(w: dict) -> str:
    """«Кто (чат): что — ждёт с 06.10» — как в Telegram (auto.render): с какого дня ждёт, если служба знает."""
    s = f"{where_of(w)}: {w['what']}" if w.get("what") else where_of(w)
    return f"{s} — ждёт с {w['since']}" if w.get("since") else s


def team_line(t: dict) -> str:
    """«Кто — что, срок» строки «Команда»."""
    s = " — ".join(x for x in (t.get("who"), t.get("what")) if x)
    return f"{s}, {t['due']}" if t.get("due") else s


def text_of(summary: dict, dela: dict | None = None) -> str:
    """Сводка простым текстом: разделы с пунктами, «Прочее» строкой, «В Дела». Телефону — запас и «скопировать»;
    рисует он по пунктам (summary), чтобы разделы были заголовками его вида. «Только тебе» сюда не входит никогда:
    текст копируют и пересылают (в Telegram оно уходит отдельным сообщением — ответ владельца 15.10, вариант б)."""
    parts = []
    if summary.get("waiting"):
        parts.append("Ждут тебя\n" + "\n".join(f"• {waiting_line(w)}" for w in summary["waiting"]))
    if summary.get("work"):
        parts.append("По работе\n" + "\n".join(
            f"• {w['topic']}: {w['text']}" if w.get("topic") and w.get("text") else f"• {w.get('topic') or w.get('text')}"
            for w in summary["work"]))
    if summary.get("team"):
        parts.append("Команда\n" + "\n".join(f"• {team_line(t)}" for t in summary["team"]))
    if summary.get("family"):
        parts.append("Дом\n" + "\n".join(f"• {x}" for x in summary["family"]))
    if summary.get("other"):
        parts.append(f"Прочее: {summary['other']}")
    line = dela_line(dela)
    if line:
        parts.append(line)
    return "\n\n".join(parts) or "Ничего, что ждёт тебя."


def label_of(ended: dt.datetime) -> str:
    """Подпись окна, если служба её не прислала: «вт 13.10, 20:00»."""
    e = ended.astimezone(MSK)
    return f"{WD[e.weekday()]} {e:%d.%m}, {e:%H:%M}"


def op_put(conn, user: str, op: dict, err) -> dict:
    """Сводка от службы дайджеста: завести или обновить по (владелец, ref), и её пункты — по карточкам CRM."""
    if conn.execute("SELECT crm.actor() AS a").fetchone()["a"] != ACTOR:
        raise err("дайджест пишет только служба дайджеста (токен digest)")
    if not is_owner(conn, user):
        raise err("дайджест — только владельцу Дел")
    d = op.get("digest")
    if not isinstance(d, dict):
        raise err("дайджест: нужен объект digest")
    ref = _s(d.get("ref"), 200)
    if not ref:
        raise err("дайджест: нужен ref — окно у службы (digest:2026-10-13T2000)")
    if len(ref) > 64:
        raise err("дайджест: ref длиннее 64 знаков")
    if "#" in ref:
        raise err("дайджест: в ref нет «#» — им запись хронологии отделяет пункт от сводки")
    ended = _at(d.get("ended_at"), "ended_at", err)
    if ended is None:
        raise err("дайджест: нужен ended_at — конец окна")
    started = _at(d.get("started_at"), "started_at", err)
    if started and started >= ended:
        raise err("дайджест: started_at не раньше конца окна")
    summary = norm_summary(d.get("summary"), err)
    dela = norm_dela(d.get("dela"), err)
    sources = digest_crm.norm_sources(d.get("sources"), err)
    data = {
        "started_at": started, "ended_at": ended, "label": _s(d.get("label"), 120) or label_of(ended),
        "chats": _count(d.get("chats"), "chats", err), "msgs": _count(d.get("msgs"), "msgs", err),
        "summary": Jsonb(summary), "text": text_of(summary, dela), "dela": Jsonb(dela) if dela else None,
        "sources": Jsonb(sources) if sources is not None else None,
    }
    cols = list(data)
    # Чаты окна старая служба (или догрузка без них) не шлёт — прежние не затираем: по ним узнаются люди.
    sets = ", ".join("sources = coalesce(EXCLUDED.sources, crm.digests.sources)" if c == "sources"
                     else f"{c} = EXCLUDED.{c}" for c in cols)
    row = conn.execute(
        f"INSERT INTO crm.digests (owner_id, ref, {', '.join(cols)}) VALUES (%s, %s, {', '.join(['%s'] * len(cols))}) "
        f"ON CONFLICT (owner_id, ref) DO UPDATE SET {sets} "
        "RETURNING id, ref, label, rev, seq, ended_at, sources",
        [user, ref, *data.values()]).fetchone()
    return {"digest": {k: row[k] for k in ("id", "ref", "label", "rev", "seq")},
            "crm": _to_crm(conn, user, row, summary),
            "insights": _to_insights(conn, user, row, summary, err)}


# ── «Только тебе» сводки — в crm.insights (этап 6 аудита, 16.10.2026) ─────────
#
# Пункты «Только тебе» живут в сводке (их рисуют веб и телефон) и ещё строками crm.insights — одним хранилищем
# наблюдений владельца со встречами: там у пункта состояние (кнопки «Пригодилось», «Мимо», «Неверно» — insight.rate),
# по нему считается мерило недели в тени и четырёх недель (вид lens), и пункт о рабочем человеке или клиенте виден в его
# карточке блоком «Только тебе». Всё это — только владельцу (RLS crm.insights и правило строки), как и сама сводка.
# Ключ строки — (digest, ref сводки, ключ пункта): повтор той же сводки не плодит строк и оценку не сбрасывает.


def _at_of(at: str, ended: dt.datetime) -> dt.datetime:
    """«ДД.ММ ЧЧ:ММ» пункта — время по Москве в году окна (дата позже конца окна — прошлый год: окно через Новый год).
    Не разобралось — конец окна."""
    m = re.match(r"^\s*(\d{1,2})\.(\d{1,2})(?:\.\d{2,4})?(?:,?\s+(\d{1,2}):(\d{2}))?", at or "")
    e = ended.astimezone(MSK)
    if not m:
        return ended
    try:
        t = dt.datetime(e.year, int(m[2]), int(m[1]), int(m[3] or 0), int(m[4] or 0), tzinfo=MSK)
    except ValueError:
        return ended
    return t.replace(year=e.year - 1) if t > e + dt.timedelta(days=1) else t


def insight_text(o: dict) -> str:
    """Строка наблюдения: «Кто (чат): чем важно. Сделать: что. Сделка: …; услуга: …» — подпись в карточке без полей
    сводки (кто — и тогда, когда человека не узнали)."""
    who = where_of({"who": o.get("who") or "", "chat": o.get("chat") or ""})
    body = " ".join(x for x in (o.get("why") or "", f"Сделать: {o['do']}" if o.get("do") else "") if x)
    tail = "; ".join(x for x in (f"сделка: {o['deal']}" if o.get("deal") else "",
                                 f"услуга: {o['service']}" if o.get("service") else "") if x)
    s = f"{who}: {body}" if who and body else who or body
    s = f"{s} ({tail})" if tail else s
    return (s or o.get("quote") or "—")[:2000]


def _team(ctx: dict, pid: str) -> bool:
    """Свой: пользователь Дел или человек компании владельца. О команде «Только тебе» не пишет (rules.dela п. 12) — и к
    их карточкам пункт не пристёгивается, даже если модель ошиблась."""
    p = ctx["people"].get(pid) or {}
    org = str(p["org_id"]) if p.get("org_id") else None
    return bool(p.get("user_id")) or bool(org and org == ctx["me_org"])


def _about(conn, ctx: dict, o: dict, sources: list[dict]) -> dict:
    """О ком пункт: рабочий человек и клиент — тем же узнаванием, что «Ждут тебя» (digest_crm.resolve: чат, кто, только
    уверенно; семья и личные — никто), сделка — по точному имени среди живых (у клиента или одна на всё). Команда — мимо."""
    persons, clients, why, _ = digest_crm.resolve(ctx, "waiting", {"chat": o.get("chat") or "", "who": o.get("who") or ""},
                                                  sources)
    out: dict = {"person_ids": [p for p in persons if not _team(ctx, p)]}
    project = clients[0] if len(clients) == 1 else None
    if o.get("deal"):
        q = ("SELECT id, project_id FROM crm.deals WHERE stage <> 'archive' AND lower(btrim(name)) = lower(btrim(%s))"
             + (" AND project_id = %s" if project else ""))
        hits = conn.execute(q, [o["deal"], project] if project else [o["deal"]]).fetchall()
        if len(hits) == 1:
            out["deal_id"], project = str(hits[0]["id"]), str(hits[0]["project_id"])
    if project:
        out["project_id"] = project
    return out


def _to_insights(conn, user: str, row: dict, summary: dict, err) -> dict:
    """Пункты «Только тебе» сводки — строками crm.insights (source digest) своей точкой сохранения: не вышло — сводка
    всё равно ложится, в ответе — почему. Пропавший из присланной заново сводки пункт без оценки убирается; оценённый
    остаётся — это слово владельца для мерила. Ответ службе — ключ и id строки каждого пункта (кнопки в Telegram)."""
    items = summary.get("only_you") or []
    try:
        with conn.transaction():
            ctx = digest_crm.load(conn, user) if items else None
            sources = row["sources"] if isinstance(row.get("sources"), list) else []
            got, keys = [], []
            for o in items:
                ins = {"kind": o["kind"] if KIND_OK.match(o.get("kind") or "") else "other", "text": insight_text(o),
                       "quote": o.get("quote") or None, "at": _at_of(o.get("at") or "", row["ended_at"]).isoformat(),
                       "source": "digest", "source_ref": row["ref"], "item_key": o["key"],
                       **_about(conn, ctx, o, sources)}
                r = insights.op_add(conn, user, {"insight": ins}, err)["insight"]
                got.append({"key": o["key"], "id": str(r["id"]), "state": r["state"], "gone": r["deleted_at"] is not None})
                keys.append(o["key"])
            gone = conn.execute(
                "UPDATE crm.insights SET deleted_at = now() WHERE owner_id = %s AND source = 'digest' AND source_ref = %s "
                "AND deleted_at IS NULL AND state = 'new' AND NOT (item_key = ANY (%s)) RETURNING id",
                (user, row["ref"], keys)).fetchall()
            return {"items": got, "retired": len(gone)}
    except Exception as e:  # noqa: BLE001 — «Только тебе» в карточках не должно ронять сводку
        log.exception("дайджест %s: «Только тебе» в crm.insights не легло", row["ref"])
        return {"error": type(e).__name__}


def _to_crm(conn, user: str, row: dict, summary: dict) -> dict:
    """Пункты сводки — по карточкам CRM (digest_crm.py) своей точкой сохранения: не вышло — сводка всё равно
    ложится (телефону она нужна и без карточек), а в ответе службе — почему."""
    try:
        with conn.transaction():
            return digest_crm.link(conn, user, {"ref": row["ref"], "label": row["label"], "ended_at": row["ended_at"]},
                                   summary, row["sources"])
    except Exception as e:  # noqa: BLE001 — разнос не должен ронять сводку
        log.exception("дайджест %s: разнос по карточкам CRM не вышел", row["ref"])
        return {"error": type(e).__name__}


def last(conn, user: str) -> dict | None:
    return conn.execute(f"SELECT {COLUMNS} FROM crm.digests WHERE owner_id = %s ORDER BY ended_at DESC LIMIT 1",
                        (user,)).fetchone()


def for_sync(conn, user: str, lo: int, full: bool) -> tuple[bool, dict | None]:
    """Поле digest синка: (класть ли, последняя сводка). Полный синк — всегда (null — сводок нет: очистить кэш),
    после seq — только если последняя новее. Не владельцу поля нет вовсе: семье и команде дайджест не положен."""
    if not is_owner(conn, user):
        return False, None
    row = last(conn, user)
    if full:
        return True, row
    if row and row["seq"] > lo:
        return True, row
    return False, None


def _day(v, what: str, err) -> dt.date | None:
    if v in (None, ""):
        return None
    try:
        return dt.date.fromisoformat(str(v).strip()[:10])
    except ValueError:
        raise err(f"дайджест: {what} — дата ГГГГ-ММ-ДД") from None


def snippet(text: str, q: str, summary: dict | None = None) -> str:
    """Строки сводки, где нашлось искомое: целиком (подстрокой) или основой слова («счёт» — «счета»). Пункт «Ждут тебя»
    — с полным названием чата: в тексте сводки «Кто (чат)» сокращён, а искать человека будут по фамилии."""
    s = summary or {}
    if s:
        lines = [*(f"{w.get('who') or ''} ({w['chat']}): {w.get('what') or ''}" if w.get("chat") and w.get("chat") != w.get("who")
                   else f"{w.get('who') or ''}: {w.get('what') or ''}" for w in s.get("waiting") or []),
                 *(f"{w.get('topic') or ''}: {w.get('text') or ''}" for w in s.get("work") or []),
                 *(team_line(t) for t in s.get("team") or []),
                 *(s.get("family") or []), s.get("other") or "",
                 # «Только тебе» тоже ищется: вид digest — только владельцу (кто, «цитата», чем важно).
                 *(f"{o.get('who') or ''}: «{o.get('quote') or ''}» — {o.get('why') or ''}" for o in s.get("only_you") or [])]
    else:
        lines = (text or "").splitlines()
    stems = [people.stem(w) for w in people.words(q) if len(w) >= 3]
    ql = people.norm(q)
    hits = [ln.strip().lstrip("•").strip() for ln in lines
            if ln.strip() and (ql in people.norm(ln) or any(st in people.norm(ln) for st in stems))]
    out = " … ".join(list(dict.fromkeys(hits))[:SNIPPET_LINES])
    return out if len(out) <= SNIPPET_MAX else out[: SNIPPET_MAX - 1].rstrip() + "…"


def links(conn, user: str, ref: str) -> list[dict]:
    """Куда сводка разнесена (dela_0018): записи хронологии с людьми и клиентом — «В карточках» у сводки. people и
    people_ids — в одном порядке (по имени): имя и ссылка на карточку — одна пара."""
    return conn.execute(
        "SELECT i.id, i.summary, i.project_id, p.name AS project_name, i.deal_id, d.name AS deal_name, i.person_ids, "
        "ARRAY(SELECT pe.name FROM crm.people pe WHERE pe.id = ANY (i.person_ids) ORDER BY pe.name, pe.id) AS people, "
        "ARRAY(SELECT pe.id FROM crm.people pe WHERE pe.id = ANY (i.person_ids) ORDER BY pe.name, pe.id) AS people_ids "
        "FROM crm.v_interactions i LEFT JOIN crm.projects p ON p.id = i.project_id LEFT JOIN crm.deals d ON d.id = i.deal_id "
        "WHERE i.owner_id = %s AND i.source = 'digest' AND i.deleted_at IS NULL AND split_part(i.source_ref, '#', 1) = %s "
        "ORDER BY i.created_at, i.id", (user, ref)).fetchall()


def view_digest(conn, user: str, days=None, limit=None, id=None, ref=None, q=None, since=None, until=None,
                person_id=None, project_id=None, err=None, **_) -> dict:
    """Вид digest: последняя сводка (last) и список, свежие сверху; id или ref — одна (и crm — куда она разнесена).

    Список — за days суток (с завода двое), либо за даты since..until (ГГГГ-ММ-ДД по Москве, включительно), либо
    поиск q по тексту сводок (словоформы и подстрока; у каждой — snippet), либо сводки, разнесённые в карточку
    человека (person_id) или клиента (project_id). Поиск и карточка без дат — за всё время."""
    if not is_owner(conn, user):
        raise err("дайджест — только владельцу Дел")
    if id or ref:
        if id:
            try:
                key, where = str(uuid.UUID(str(id))), "id = %s"
            except ValueError:
                raise err("дайджест: id — не uuid") from None
        else:
            key, where = str(ref).split("#")[0], "ref = %s"
        row = conn.execute(f"SELECT {COLUMNS} FROM crm.digests WHERE owner_id = %s AND {where}", (user, key)).fetchone()
        if not row:
            raise err("нет такого дайджеста")
        # «Только тебе» сводки строками crm.insights — с состоянием (кнопки «Пригодилось / Мимо»), по ключу пункта.
        mine = insights.of(conn, "i.source = 'digest' AND i.source_ref = %s", [row["ref"]], ONLY_YOU_MAX * 2)
        return {"digest": row, "crm": links(conn, user, row["ref"]), "insights": mine}
    try:
        days = min(DAYS_MAX, max(1, int(days or DAYS)))
        limit = min(LIMIT_MAX, max(1, int(limit or LIMIT)))
    except (TypeError, ValueError):
        raise err("дайджест: days и limit — числа") from None
    lo, hi = _day(since, "since", err), _day(until, "until", err)
    q = " ".join(str(q or "").split())[:200]
    where, params = ["owner_id = %s"], [user]
    if lo or hi:
        if lo:
            where.append("ended_at >= (%s::date)::timestamp AT TIME ZONE 'Europe/Moscow'")
            params.append(lo)
        if hi:
            where.append("ended_at < (%s::date + 1)::timestamp AT TIME ZONE 'Europe/Moscow'")
            params.append(hi)
    elif not (q or person_id or project_id):
        where.append("ended_at > now() - make_interval(days => %s)")
        params.append(days)
    if q:
        # Ищем и в пунктах (summary): названия чатов текст сводки сокращает («Иван» вместо «Иван Соколов (Ромашка)»).
        # ё — как е с обеих сторон: русский стеммер их не сводит, и «счёт» иначе не нашёл бы «счета».
        doc = "translate(text || ' ' || summary::text, 'ёЁ', 'еЕ')"
        where.append(f"(to_tsvector('russian', {doc}) @@ websearch_to_tsquery('russian', translate(%s, 'ёЁ', 'еЕ')) "
                     f"OR position(translate(lower(%s), 'ё', 'е') in lower({doc})) > 0)")
        params += [q, q]
    for col, v in (("person", person_id), ("project", project_id)):
        if not v:
            continue
        try:
            v = str(uuid.UUID(str(v)))
        except ValueError:
            raise err(f"дайджест: {col}_id — не uuid") from None
        cond = "%s::uuid = ANY (i.person_ids)" if col == "person" else "i.project_id = %s::uuid"
        where.append("ref IN (SELECT split_part(i.source_ref, '#', 1) FROM crm.interactions i WHERE i.owner_id = %s "
                     f"AND i.source = 'digest' AND i.deleted_at IS NULL AND {cond})")
        params += [user, v]
    rows = conn.execute(f"SELECT {COLUMNS} FROM crm.digests WHERE {' AND '.join(where)} ORDER BY ended_at DESC LIMIT %s",
                        [*params, limit]).fetchall()
    if q:
        rows = [{**r, "snippet": snippet(r["text"], q, r["summary"])} for r in rows]
    plain = not (lo or hi or q or person_id or project_id)
    # Последняя — даже если старше окна (выходные, комп спал): карточке «Сегодня» всегда есть что показать.
    out = {"last": rows[0] if plain and rows else last(conn, user), "items": rows, "days": days}
    return {**out, **{k: v for k, v in (("q", q), ("since", lo), ("until", hi)) if v}}


def register(handlers: dict, views: dict, err) -> None:
    handlers["digest.put"] = lambda conn, user, op: op_put(conn, user, op, err)
    views["digest"] = lambda conn, user, **p: view_digest(conn, user, err=err, **p)
