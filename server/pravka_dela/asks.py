"""Очередь «Подскажи»: время заходов, фазы, срочность и предмет вопроса — одно место правил (шаги 1 и 2 петли этапа 4).

Саша (ответ 1а, 15.10.2026): «с 19:00 в „Подскажи“ до семи кандидатов „Сейчас на завтра“». Тем же вечером он перенёс
заход Пн–Чт на 18:00: по ленте Засечки в 19:00 он уже с семьёй чаще (11 будних вечеров из 26, в 18:00 — 9), а свободные
10 минут чаще всего выпадают на 17:50–18:20. Пятница — раньше: в пятницу вечером шаббат, и вечерний заход пришёлся бы
на свечи. Правило простое и без свечей — Пт 14:00, а с 15.11 по 15.01 (свечи
в Москве раньше 16:00) — 13:30: его же считают веб и телефон, у которых часов восхода нет. Что заход «не позже
свечей − 2 ч» на каждой пятнице, держит тест на 2026–2027 по `sky.shabbat` (Москва с завода); не пройдёт на краю окна —
окно шире, а не точная формула. Сб–Вс заходов нет.

Шаг 2 (порядок Саши: «два захода одной очереди „Подскажи“ на „Сегодня“: 15:04 — только сгорающее; 19:00 — остальное, по
клиентам и людям»). Вопросы и так лежат в одном месте — `tasks.suggestions`; шаг 2 меняет только КОГДА блок раскрыт и
КАК сгруппирован:
- **фаза** (`phase`): до 15:00 — `before` (блок свёрнут строкой), с 15:00 до вечернего захода (Пн–Чт 18:00 — Саша
  перенёс с 19:00 тем же вечером) — `today` (раскрыто только сгорающее), с вечернего захода до 05:00 — `evening` (всё по
  предметам), с пятницы 15:00 (Свод `day.quiet`, часть `weekend`) до понедельника
  05:00 — `quiet`. Пятница: до 14:00 (зимой 13:30) — `before`, 14:00–15:00 — `evening` (последний заход недели);
- **срочность** (`stamp`, `payload.urgency`, `urgent_on`): `today` — вопрос по сегодняшней встрече (или служба сама
  сказала `today`), сгорает в день `urgent_on`; остальное — `evening`. Круг и кандидаты плана — всегда `evening`;
- **предмет** (`payload.about = {kind, id?, title}`): клиент, человек, встреча без клиента, мелочь; кандидат «Сейчас на
  завтра» — `plan` (у него свой блок, очередь его только считает).
Ставит их сервер при записи вопроса (`store.op_suggestion_create`), веб и телефон только раскладывают по фазе — тем же
правилом (`ASKS_FACTORY` в `static/app.js`, `core/Asks.kt`; таблица фаз — `phases.examples` контракта
`server/contract/dela-asks.json`, её читают тесты всех трёх). Расписания нет: заход — это время на часах. Claude не
зовётся.

Время — Свод `asks.rhythm` поверх заводского по полю: кривое поле — заводское и строка журнала (раз на значение, а не
каждую минуту тика). Начало тишины — Свод `day.quiet` (`weekend`: с какого дня недели и часа; шаг 4 петли допишет туда
дневные окна Засечки — их здесь не трогаем). Пишут claude и владелец, читают сервер, веб и телефон; записи нет —
заводское. План «Сейчас на завтра» (`plan.py`) время прогона берёт отсюда же: одно время на план и заходы.
"""

from __future__ import annotations

import datetime as dt
import logging
import re
import uuid
from typing import Any

from psycopg.types.json import Jsonb

from .meetings import MSK

log = logging.getLogger("dela.asks")

SVOD_KEY = "asks.rhythm"
QUIET_KEY = "day.quiet"
# Время по Москве «ЧЧ:ММ»; winter — с какого по какое число (ММ-ДД, через Новый год) пятница идёт по fri_winter.
# Шаг 1: evening, fri, fri_winter, winter (заходы и прогон плана). Шаг 2: today — заход «сгорающего» (15:00, вместе со
# слотом сводки дайджеста 15), night_end — до какого часа ночью длится вечер (Саша отвечает в 23–00 ч: половина ответов),
# today_max и evening_max — сколько вопросов раскрыто в заход (дальше — «ещё K — в «Новом»»).
FACTORY: dict[str, Any] = {"evening": "18:00", "fri": "14:00", "fri_winter": "13:30", "winter": ["11-15", "01-15"],
                           "today": "15:00", "night_end": "05:00", "today_max": 5, "evening_max": 7}
HM_FIELDS = ("evening", "fri", "fri_winter", "today", "night_end")
MAX_FIELDS = ("today_max", "evening_max")
# Тишина очереди (шаг 2): с пятницы 15:00 до понедельника night_end. dow — день недели ISO (1 — пн, 5 — пт). Ключ общий
# с Засечкой: шаг 4 допишет daily (дневные окна тихих часов) — эта часть ключа ему, здесь проходит как есть. weekend:
# null — тишины нет (тогда Сб–Вс заходов всё равно нет: evening_at — None).
QUIET_FACTORY: dict[str, Any] = {"v": 1, "weekend": {"dow": 5, "from": "15:00"}}
PHASES = ("before", "today", "evening", "quiet")
URGENCY = ("today", "evening")
# Предмет вопроса — порядок групп на экране вечером (кандидаты плана — свой блок шага 1, здесь только число).
KINDS = ("client", "person", "meeting", "small")
ABOUT_KINDS = (*KINDS, "plan")
SMALL = "Мелочь"           # подпись группы мелочи на экране и в строке дайджеста
KRUG_TITLE = "Место в дереве"
ELSE_TITLE = "Прочее"
WEEKDAYS = ("пн", "вт", "ср", "чт", "пт", "сб", "вс")
FRIDAY = 4
_HM = re.compile(r"^([01]?\d|2[0-3]):([0-5]\d)$")
_MD = re.compile(r"^(\d{2})-(\d{2})$")
_warned: set[str] = set()


def _hm(v: Any) -> tuple[int, int] | None:
    m = _HM.match(str(v or "").strip())
    return (int(m.group(1)), int(m.group(2))) if m else None


def _md(v: Any) -> tuple[int, int] | None:
    m = _MD.match(str(v or "").strip())
    if not m:
        return None
    try:
        dt.date(2028, int(m.group(1)), int(m.group(2)))  # високосный: «02-29» — тоже день
    except ValueError:
        return None
    return int(m.group(1)), int(m.group(2))


def norm(value: Any) -> tuple[dict, list[str]]:
    """Свод поверх заводского по полю. Ответ — (ритм, что было криво словами). Незнакомые поля (их может дописать
    следующий шаг) проходят как есть: читатель знает свои и не трогает чужие."""
    out = {k: (list(v) if isinstance(v, list) else v) for k, v in FACTORY.items()}
    bad: list[str] = []
    if value is None:
        return out, bad
    if not isinstance(value, dict):
        return out, [f"{SVOD_KEY}: не словарь — заводское"]
    for k, v in value.items():
        if k in HM_FIELDS:
            hm = _hm(v)
            if hm:
                out[k] = f"{hm[0]:02d}:{hm[1]:02d}"
            else:
                bad.append(f"{k}={v!r} — не «ЧЧ:ММ»")
        elif k == "winter":
            if isinstance(v, list) and len(v) == 2 and all(_md(x) for x in v):
                out[k] = [str(x).strip() for x in v]
            else:
                bad.append(f"winter={v!r} — не [«ММ-ДД», «ММ-ДД»]")
        elif k in MAX_FIELDS:
            if isinstance(v, int) and not isinstance(v, bool) and 1 <= v <= 50:
                out[k] = v
            else:
                bad.append(f"{k}={v!r} — не число 1…50")
        else:
            out[k] = v
    return out, bad


def _svod(conn, owner: str, key: str, normf) -> dict:
    """Свод владельца поверх заводского: кривое — заводское и строка журнала (раз на значение)."""
    row = conn.execute("SELECT value FROM crm.svod WHERE owner_id = %s AND key = %s", (owner, key)).fetchone()
    out, bad = normf((row or {}).get("value"))
    for b in bad:
        if b not in _warned:
            _warned.add(b)
            log.warning("Свод %s: %s — беру заводское", key, b)
    return out


def rhythm(conn, owner: str) -> dict:
    """Ритм владельца: Свод asks.rhythm поверх заводского. Кривое поле — заводское и строка журнала."""
    return _svod(conn, owner, SVOD_KEY, norm)


def norm_quiet(value: Any) -> tuple[dict, list[str]]:
    """Свод day.quiet поверх заводского. Своя здесь только часть weekend ({dow: 1…7, from: «ЧЧ:ММ»} по полю; null —
    тишины нет); остальное (v, daily шага 4) проходит как есть — его читает Засечка."""
    out = {k: (dict(v) if isinstance(v, dict) else v) for k, v in QUIET_FACTORY.items()}
    bad: list[str] = []
    if value is None:
        return out, bad
    if not isinstance(value, dict):
        return out, [f"{QUIET_KEY}: не словарь — заводское"]
    for k, v in value.items():
        if k != "weekend":
            out[k] = v
        elif v is None or v is False:
            out["weekend"] = None
        elif not isinstance(v, dict):
            bad.append(f"weekend={v!r} — не словарь")
        else:
            w = dict(QUIET_FACTORY["weekend"])
            for kk, vv in v.items():
                if kk == "dow":
                    if isinstance(vv, int) and not isinstance(vv, bool) and 1 <= vv <= 7:
                        w["dow"] = vv
                    else:
                        bad.append(f"weekend.dow={vv!r} — не 1…7")
                elif kk == "from":
                    hm = _hm(vv)
                    if hm:
                        w["from"] = f"{hm[0]:02d}:{hm[1]:02d}"
                    else:
                        bad.append(f"weekend.from={vv!r} — не «ЧЧ:ММ»")
                else:
                    w[kk] = vv
            out["weekend"] = w
    return out, bad


def quiet(conn, owner: str) -> dict:
    """Тишина владельца: Свод day.quiet поверх заводского (с пятницы 15:00)."""
    return _svod(conn, owner, QUIET_KEY, norm_quiet)


def _f(rh: dict, key: str) -> Any:
    """Поле ритма; нет его или кривое — заводское (ритм мог прийти не через norm: тест, старый вызов)."""
    v = (rh or {}).get(key)
    if key in HM_FIELDS:
        return v if _hm(v) else FACTORY[key]
    if key == "winter":
        return v if isinstance(v, list) and len(v) == 2 and all(_md(x) for x in v) else FACTORY[key]
    return v if isinstance(v, int) and not isinstance(v, bool) and v > 0 else FACTORY[key]


def _at(day: dt.date, hm: str) -> dt.datetime:
    h, m = _hm(hm)
    return dt.datetime(day.year, day.month, day.day, h, m, tzinfo=MSK)


def _min(hm: str) -> int:
    h, m = _hm(hm)
    return h * 60 + m


def winter(rh: dict, day: dt.date) -> bool:
    """Зимнее окно пятницы (с завода 15.11–15.01): свечи в Москве раньше 16:00, заход — на полчаса раньше."""
    w = _f(rh, "winter")
    lo, hi = _md(w[0]), _md(w[1])
    md = (day.month, day.day)
    return lo <= md <= hi if lo <= hi else (md >= lo or md <= hi)


def evening_at(rh: dict, day: dt.date) -> dt.datetime | None:
    """Время вечернего захода дня по Москве: Пн–Чт — evening (18:00), Пт — fri (14:00) или в зимнем окне fri_winter
    (13:30), Сб–Вс — None (заходов нет)."""
    wd = day.weekday()
    if wd > FRIDAY:
        return None
    key = "evening" if wd < FRIDAY else "fri_winter" if winter(rh, day) else "fri"
    return _at(day, _f(rh, key))


# ── Фазы: когда блок «Подскажи» раскрыт ────────────────────────────────

def in_quiet(rh: dict, qt: dict | None, at: dt.datetime) -> bool:
    """Тишина выходных: с weekend.dow weekend.from (Пт 15:00) до понедельника night_end (05:00)."""
    w = (qt or {}).get("weekend")
    if not isinstance(w, dict) or not _hm(w.get("from")) or w.get("dow") not in range(1, 8):
        return False
    msk = at.astimezone(MSK)
    pos = msk.weekday() * 1440 + msk.hour * 60 + msk.minute
    return pos >= (w["dow"] - 1) * 1440 + _min(w["from"]) or pos < _min(_f(rh, "night_end"))


def phase(rh: dict, qt: dict | None, at: dt.datetime) -> str:
    """Фаза очереди в миг at: before (до захода «сгорающего»), today (с today до вечера), evening (с вечернего захода
    до night_end следующего утра), quiet (тишина выходных; дни без захода). Пятница: before до fri, evening с fri до
    тишины — today у неё нет, раз fri раньше today."""
    msk = at.astimezone(MSK)
    if in_quiet(rh, qt, msk):
        return "quiet"
    day = msk.date()
    if msk.hour * 60 + msk.minute < _min(_f(rh, "night_end")):
        # Ночь — хвост вчерашнего вечера (Саша отвечает и в 23–00 ч).
        return "evening" if evening_at(rh, day - dt.timedelta(days=1)) else "quiet"
    ev = evening_at(rh, day)
    if ev is None:
        return "quiet"
    if msk >= ev:
        return "evening"
    return "today" if msk >= _at(day, _f(rh, "today")) else "before"


def next_start(rh: dict, qt: dict | None, at: dt.datetime) -> dt.datetime | None:
    """Ближайший заход после at — начало фазы today или evening (не в тишине)."""
    msk = at.astimezone(MSK)
    for k in range(9):
        day = msk.date() + dt.timedelta(days=k)
        ev = evening_at(rh, day)
        if ev is None:
            continue
        t = _at(day, _f(rh, "today"))
        for s in ((t, ev) if t < ev else (ev,)):
            if s > msk and phase(rh, qt, s) in ("today", "evening"):
                return s
    return None


def when_word(start: dt.datetime | None, at: dt.datetime) -> str | None:
    """«15:00» — сегодня, «пн 15:00» — другой день."""
    if start is None:
        return None
    s = start.astimezone(MSK)
    return f"{s:%H:%M}" if s.date() == at.astimezone(MSK).date() else f"{WEEKDAYS[s.weekday()]} {s:%H:%M}"


def state(rh: dict, qt: dict | None, at: dt.datetime) -> dict:
    """Фаза, ближайший заход словами и ISO и какой он (next_phase: today — «к 15:00: N · к вечеру: M»; evening — в
    пятницу «к 14:00: всего»)."""
    nxt = next_start(rh, qt, at)
    return {"phase": phase(rh, qt, at), "next": when_word(nxt, at), "next_at": nxt.isoformat() if nxt else None,
            "next_phase": phase(rh, qt, nxt) if nxt else None}


# ── Штамп: срочность и предмет вопроса при записи ───────────────────────

def _uid(v: Any) -> str | None:
    try:
        return str(uuid.UUID(str(v))) if v else None
    except ValueError:
        return None


def _day(v: Any) -> dt.date | None:
    """День по Москве из «ГГГГ-ММ-ДД» или ISO-времени (с поясом — в Москву, без пояса — уже Москва)."""
    s = str(v or "").strip()
    if not s:
        return None
    try:
        if len(s) > 10:
            t = dt.datetime.fromisoformat(s.replace("Z", "+00:00"))
            return (t if t.tzinfo else t.replace(tzinfo=MSK)).astimezone(MSK).date()
        return dt.date.fromisoformat(s)
    except ValueError:
        return None


def _meeting(conn, data: dict) -> dict | None:
    """Встреча пачки «Нового» (batch_ref meeting:<ref>) — день, клиент и сделка из карточки Дел (RLS: только владельцу)."""
    ref = str(data.get("batch_ref") or "")
    if not ref.startswith("meeting:") or not ref[8:]:
        return None
    return conn.execute("SELECT id, started_at, project_id, deal_id FROM crm.meetings WHERE ref = %s "
                        "ORDER BY started_at DESC LIMIT 1", (ref[8:],)).fetchone()


_BATCH_DAY = re.compile(r" · (\d{1,2})\.(\d{1,2})$")


def _batch_day(data: dict, today: dt.date) -> dt.date | None:
    """День встречи из названия пачки службы «<название> · дд.мм» (как store._meeting_of) — когда карточки встречи в Делах
    ещё нет. Люди и спикеры встречи (поезд 16.10.2026: вагон 5, people.py и people_ident.py службы встреч) уходят в Дела
    сразу после разбора, без payload.meeting_at, а сама встреча (meeting.upsert) — следующим кругом службы, когда у неё
    не осталось работы; без этого вопрос о человеке сегодняшней встречи стоял бы вечерним. Год — ближайший не в будущем:
    встреча уже прошла."""
    if not str(data.get("batch_ref") or "").startswith("meeting:"):
        return None
    m = _BATCH_DAY.search(" ".join(str(data.get("batch_title") or "").split()))
    if not m:
        return None
    for year in (today.year, today.year - 1):
        try:
            d = dt.date(year, int(m.group(2)), int(m.group(1)))
        except ValueError:
            continue
        if d <= today:
            return d
    return None


def urgency(data: dict, payload: dict, meeting: dict | None, today: dt.date) -> tuple[str, dt.date | None]:
    """(срочность, день сгорания). Круг и план — всегда вечер. Служба сказала сама (контракт этапа 3: срочность ставит
    служба) — её слово; today без даты — сегодня. Иначе today — вопрос по сегодняшней встрече (любой вид: завести,
    уточнить, закрыть, человек и спикер): завтра те люди и договорённости уже не вспомнятся. День встречи — payload.
    meeting_at, иначе карточка встречи по batch_ref, иначе название пачки службы (_batch_day). Остальное — вечер: Telegram,
    «Люди сами», и фраза самого Саши — «это закрывает …?» и «Какой Иван?» захвата (его ответ 3а: «похожих обещаний два и
    больше — спросить вечером»)."""
    src = data.get("source")
    if src in ("krug", "plan"):
        return "evening", None
    u = payload.get("urgency")
    if u in URGENCY:
        return (u, _day(payload.get("urgent_on")) or today) if u == "today" else (u, None)
    if src == "meeting":
        d = (_day(payload.get("meeting_at")) or (meeting["started_at"].astimezone(MSK).date() if meeting else None)
             or _batch_day(data, today))
        if d == today:
            return "today", today
    return "evening", None


def _client(conn, ids: list) -> dict | None:
    for pid in ids:
        pid = _uid(pid)
        r = conn.execute("SELECT id, name FROM crm.projects WHERE id = %s AND kind = 'client'", (pid,)).fetchone() \
            if pid else None
        if r:
            return {"kind": "client", "id": str(r["id"]), "title": r["name"]}
    return None


def about(conn, data: dict, payload: dict, names=None, meeting: dict | None = None) -> dict:
    """Предмет вопроса — первым подошедшим: кандидат плана; Круг — мелочь «Место в дереве»; клиент (проект вопроса,
    узнанный по имени, проект дела у «уточнить» и «закрыть», клиент сделки, клиент встречи); человек (id, узнанное имя,
    у вопроса о человеке — сама карточка или имя; не у спикера встречи; у «Какой Иван?» захвата — сам вопрос по имени
    тёзок, без id); встреча
    без клиента; остальное — мелочь. names — store._names параметром (имена автоматики в id тем же правилом, что при
    «Да»), без кругового импорта."""
    src, kind = data.get("source"), data.get("kind") or "create"
    if src == "plan":
        return {"kind": "plan", "title": data.get("batch_title") or "Сейчас на завтра"}
    if src == "krug":
        return {"kind": "small", "title": KRUG_TITLE}
    got = names(payload) if names else {}
    tid = _uid(data.get("task_id"))
    task = conn.execute("SELECT project_id, deal_id, person_id FROM tasks.tasks WHERE id = %s", (tid,)).fetchone() \
        if tid else None
    deals = [_uid(x) for x in (payload.get("deal_id"), got.get("deal_id"), task and task["deal_id"],
                               meeting and meeting["deal_id"])]
    of_deal = [r["project_id"] for d in deals if d for r in
               conn.execute("SELECT project_id FROM crm.deals WHERE id = %s", (d,)).fetchall()]
    c = _client(conn, [payload.get("project_id"), got.get("project_id"), task and task["project_id"], *of_deal,
                       meeting and meeting["project_id"]])
    if c:
        return c
    for pid in (payload.get("person_id"), got.get("person_id"), task and task["person_id"]):
        pid = _uid(pid)
        r = conn.execute("SELECT id, coalesce(nullif(btrim(short), ''), name) AS t FROM crm.people WHERE id = %s",
                         (pid,)).fetchone() if pid else None
        if r:
            return {"kind": "person", "id": str(r["id"]), "title": r["t"]}
    # Спикер встречи (поезд 16.10.2026, вагон 5: payload.ident — store._meeting_speaker): предмет — встреча, кто на ней был
    # (или её клиент выше), а не человек по названию: у вопроса о безымянном оно — метка «SPEAKER_01» или догадка
    # «Ольга С.?», и до трёх вопросов одной встречи разошлись бы по своим «людям».
    speaker = isinstance(payload.get("ident"), dict)
    person = payload.get("person") if isinstance(payload.get("person"), dict) else {}
    for n in (() if speaker else (payload.get("person_name"), person.get("name"),
                                   payload.get("title") if kind == "person" else None)):
        if str(n or "").strip():
            return {"kind": "person", "title": " ".join(str(n).split())[:80]}
    if kind == "capture":
        # «Какой Иван?» захвата (поезд 16.10.2026, вагон 4; capture._ask_who): тёзки — один человек по имени, как его
        # назвал Саша (короткое имя первой кнопки — capture._twin_short); какая карточка — решит ответ. Заголовок — сам
        # вопрос «Какой Иван?» («Какая Ольга?»), а не голое «Иван» (проверка вагона 7): у карточки «Иван Тестов» короткое
        # имя то же, и вечером «Иван · 1» стоял бы дважды — группа тёзок (без id) и группа карточки (по id) — и читался
        # как разъехавшиеся группы. Решено по умолчанию — Саша меняет словом.
        from .capture import _who_word  # круговой импорт: capture зовёт asks.stamp

        for o in payload.get("options") or []:
            pid = _uid(o.get("person_id")) if isinstance(o, dict) else None
            r = conn.execute("SELECT coalesce(nullif(btrim(short), ''), split_part(btrim(name), ' ', 1)) AS t "
                             "FROM crm.people WHERE id = %s", (pid,)).fetchone() if pid else None
            if r and str(r["t"] or "").strip():
                who = _who_word(str(r["t"]).strip()[:60])
                return {"kind": "person", "title": who[:1].upper() + who[1:] + "?"}
    if str(data.get("batch_ref") or "").startswith("meeting:"):
        return {"kind": "meeting", **({"id": str(meeting["id"])} if meeting else {}),
                "title": data.get("batch_title") or "Встреча"}
    return {"kind": "small", "title": data.get("batch_title") or ELSE_TITLE}


def stamp(conn, data: dict, payload: dict, names=None) -> None:
    """Срочность и предмет — в payload вопроса до записи (store.op_suggestion_create): urgency, urgent_on, about.
    В поля дела они не идут (op_suggestion_decide берёт только TASK_FIELDS), старые клиенты лишних ключей не читают."""
    if not isinstance(payload, dict):
        return
    today = conn.execute("SELECT crm.today() AS d").fetchone()["d"]
    meeting = _meeting(conn, data)
    u, on = urgency(data, payload, meeting, today)
    payload["urgency"] = u
    if on:
        payload["urgent_on"] = on.isoformat()
    else:
        payload.pop("urgent_on", None)
    payload["about"] = about(conn, data, payload, names, meeting)


def restamp_meeting(conn, mid, names=None) -> int:
    """Встреча получила клиента, сделку или новое название (разбор, правка владельца, присылка службы —
    meetings.after_edit) — предмет ждущих вопросов её пачки пересчитать тем же правилом about. Иначе он застывал при
    записи: вопрос сверки, записанный до разбора Дел, стоял вечером отдельной группой «встреча» со старым названием, а не
    у клиента, которого встрече потом дал разборщик (проверка шага 2, 15.10.2026). Срочность не трогаем: она — про день
    встречи и слово службы, а они не менялись. Вопрос без штампа (записан до выката шага 2) остаётся мелочью — разовой
    правки данных нет. Ответ — сколько вопросов поправлено."""
    m = conn.execute("SELECT ref, owner_id FROM crm.meetings WHERE id = %s", (mid,)).fetchone()
    if not m or not m["ref"]:
        return 0
    ref = "meeting:" + m["ref"]
    rows = conn.execute(
        "SELECT id, kind, source, task_id, batch_ref, batch_title, payload FROM tasks.suggestions "
        "WHERE batch_ref = %s AND for_user = %s AND status = 'pending'", (ref, m["owner_id"])).fetchall()
    meeting = _meeting(conn, {"batch_ref": ref}) if rows else None
    n = 0
    for r in rows:
        p = r["payload"]
        old = p.get("about") if isinstance(p, dict) else None
        if not isinstance(old, dict) or old.get("kind") not in KINDS or r["source"] in ("krug", "plan"):
            continue
        new = about(conn, r, p, names, meeting)
        if new != old:
            conn.execute("UPDATE tasks.suggestions SET payload = jsonb_set(payload, '{about}', %s) WHERE id = %s",
                         (Jsonb(new), r["id"]))
            n += 1
    return n


# ── Очередь: группы в порядке экрана ────────────────────────────────────

def is_urgent(payload: dict | None, day: str) -> bool:
    """Сгорает в этот день: urgency today и urgent_on — этот день. Завтра тот же вопрос — обычный вечерний."""
    p = payload if isinstance(payload, dict) else {}
    return p.get("urgency") == "today" and str(p.get("urgent_on") or "") == day


def about_of(s: dict) -> dict:
    """Предмет из payload.about; вопрос без штампа (до выката шага 2) — мелочь по названию пачки."""
    if s.get("source") == "plan":
        return {"kind": "plan", "title": s.get("batch_title") or ""}
    a = (s.get("payload") or {}).get("about")
    if isinstance(a, dict) and a.get("kind") in KINDS:
        return {"kind": a["kind"], "id": a.get("id"), "title": str(a.get("title") or "")}
    return {"kind": "small", "id": None, "title": s.get("batch_title") or ELSE_TITLE}


def _ts(v: Any) -> int:
    """Миг вопроса с точностью до секунды — так created_at отдаёт синк (store.jsonable): веб и телефон сравнивают его же,
    и порядок «кто дольше ждёт» у всех один; равные — по id."""
    if isinstance(v, dt.datetime):
        return int(v.timestamp())
    try:
        return int(dt.datetime.fromisoformat(str(v).replace("Z", "+00:00")).timestamp())
    except ValueError:
        return 0


def _title(a: dict, s: dict) -> str:
    """Название группы: мелочь — одна «Мелочь»; встреча — текущее название её пачки (batch_title: сервер переименовывает
    пачку вслед за встречей — и триггером клиента в базе, мимо restamp_meeting), title штампа — запас; клиент и человек —
    title штампа, свежее имя по id ставит _fresh."""
    if a["kind"] == "small":
        return SMALL
    if a["kind"] == "meeting":
        return s.get("batch_title") or a["title"]
    return a["title"]


def queue(sugs: list[dict], day: str) -> dict:
    """Ждущие вопросы одного человека → {total, urgent, rest, plan, groups, urgent_ids}. Группа — предмет (клиент и
    человек — по id, без id — по имени; встреча — по id или названию пачки; мелочь — одна). Порядок групп — как на
    экране вечером: со сгорающим — первыми, дальше клиенты, люди, встречи, мелочь; внутри вида — кто дольше ждёт. В
    группе (ids) — сгорающее, потом старые сверху. Кандидаты плана («Сейчас на завтра») — только числом plan."""
    plan = 0
    groups: dict[tuple, dict] = {}
    for s in sorted(sugs, key=lambda x: (_ts(x.get("created_at")), str(x.get("id")))):
        a = about_of(s)
        if a["kind"] == "plan":
            plan += 1
            continue
        small = a["kind"] == "small"
        key = (a["kind"], None if small else (a.get("id") or a["title"]))
        g = groups.setdefault(key, {"kind": a["kind"], "id": None if small else a.get("id"),
                                    "title": _title(a, s), "n": 0, "urgent": 0,
                                    "_u": [], "_r": [], "_old": _ts(s.get("created_at"))})
        hot = is_urgent(s.get("payload"), day)
        g["n"] += 1
        g["urgent"] += hot
        (g["_u"] if hot else g["_r"]).append(str(s["id"]))
    order = sorted(groups.values(), key=lambda g: (g["urgent"] == 0, KINDS.index(g["kind"]), g["_old"]))
    out = [{"kind": g["kind"], "id": g["id"], "title": g["title"], "n": g["n"], "urgent": g["urgent"],
            "ids": g["_u"] + g["_r"]} for g in order]
    total = sum(g["n"] for g in out)
    hot = sum(g["urgent"] for g in out)
    return {"total": total, "urgent": hot, "rest": total - hot, "plan": plan, "groups": out,
            "urgent_ids": [i for g in order for i in g["_u"]]}


def _fresh(conn, groups: list[dict]) -> None:
    """Клиент и человек — свежим именем по id (переименовали после вопроса); title штампа — запас."""
    for g in groups:
        if g["kind"] == "client" and _uid(g["id"]):
            r = conn.execute("SELECT name AS t FROM crm.projects WHERE id = %s", (g["id"],)).fetchone()
        elif g["kind"] == "person" and _uid(g["id"]):
            r = conn.execute("SELECT coalesce(nullif(btrim(short), ''), name) AS t FROM crm.people WHERE id = %s",
                             (g["id"],)).fetchone()
        else:
            continue
        if r and r["t"]:
            g["title"] = r["t"]


def _when(at: Any) -> dt.datetime:
    from .store import OpError  # круговой импорт: store зовёт stamp при записи вопроса

    if at in (None, ""):
        return dt.datetime.now(dt.timezone.utc)
    try:
        # «+03:00» в адресе без кодирования приходит пробелом — его возвращаем плюсом.
        t = dt.datetime.fromisoformat(str(at).strip().replace("Z", "+00:00").replace(" ", "+"))
    except ValueError:
        raise OpError(f"at={at!r} — не время ISO («2026-10-15T15:00:00+03:00»)") from None
    return t if t.tzinfo else t.replace(tzinfo=MSK)


def view_asks(conn, user, at=None, **_) -> dict:
    """Очередь «Подскажи» человека на миг at (ISO; с завода — сейчас; дайджест зовёт концом окна, тесты — своим часом):
    фаза и ближайший заход, ритм и тишина (Свод поверх заводского), числа и группы в порядке экрана, кандидатов плана
    — числом, «ждут от тебя» (store.OWED_HOT_IF на день at) и «Сейчас: сделано x из y» (store.now_counts). Только свои
    вопросы (for_user)."""
    from . import store  # круговой импорт: store зовёт stamp при записи вопроса

    now = _when(at)
    msk = now.astimezone(MSK)
    day = msk.date()
    rh, qt = rhythm(conn, user), quiet(conn, user)
    rows = conn.execute(
        "SELECT id, kind, source, batch_ref, batch_title, payload, created_at FROM tasks.suggestions "
        "WHERE for_user = %s AND status = 'pending' AND (expires_at IS NULL OR expires_at > %s)", (user, now)).fetchall()
    q = queue(rows, day.isoformat())
    _fresh(conn, q["groups"])
    owed = conn.execute(
        f"SELECT count(*) AS n FROM tasks.v_tasks t WHERE t.owner_id = %(u)s AND t.status = 'open' AND NOT t.goal "
        f"AND {store.DEED_KIND_IF} AND {store.OWED_HOT_IF('%(d)s::date')}",
        {"u": user, "d": day.isoformat()}).fetchone()["n"]
    return {"at": msk.isoformat(), "day": day.isoformat(), **state(rh, qt, now), "rhythm": rh, "quiet": qt, **q,
            "owed_hot": owed, "now": store.now_counts(conn, user, day)}


def register(views: dict) -> None:
    views["asks"] = view_asks
