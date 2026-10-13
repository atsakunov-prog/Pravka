"""Страницы-двойники экранов телефона «Правка 5.0» в вебе Дел (14.10.2026): «Сегодня», «Засечка», «Правка».

Владелец: телефон и веб — «две копии одного и того же»; на компьютере — та же Засечка, тот же день, те же
диктовки, одна база на сервере. Раскладка — разложенный телефон («мини-компьютер», холст «Ковчег на телефоне»),
правила — телефона: гора дня (`core/DayProfile.kt`), лента с вечера к утру (`core/Lenta.kt`), цвет «чей это»
(`core/CategoryTone.kt`) — перенесены в веб один раз (`static/twins.js`), считает браузер. Здесь — только данные:

- архив жизни (лента, еда, тренировки, сон и форма, деньги семьи, телефон по дням, диктовки) — узко, через
  crm.life_rows (`life.py`, dela_0016): роль службы схему life не видит;
- что знают сами Дела — их же видами: дайджест (`digest.last`), встречи дня, команды Засечки (`zasechka.py`);
  «Сейчас», «Новое» и «Сегодня» дел веб берёт из своего синка — теми же правилами, что страницы Дел;
- числа плиток (еда к цели Свода `food.goals`, тренировки, траты семьи за день и месяц, Шаббат).

Виды: day, zasechka, pravka, pogoda (погода — отдельно: сеть не должна держать день). Только владельцу: семья и
команда этих страниц и данных не видят ни в вебе, ни в API — вид отказывает словами.
"""

from __future__ import annotations

import datetime as dt
import re
from typing import Any

from . import digest, life, sky, zasechka

PAGES = ("day", "zasechka", "pravka")   # маршруты веба #/day, #/zasechka, #/pravka; в /api/me look.pages их объявляет kovcheg_ui.PAGES
TASK_DAYS = 30                          # «в ленте 1 ч 20 мин» у дела в «Дальше» — по записям за месяц
PRAVKA_DAYS = 7                         # история диктовок с завода — неделя; «ещё» — дальше
PRAVKA_MAX = 180
MONTHS = ["январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь"]
SPORT_KIND = {"Run": "бег", "TrailRun": "бег", "VirtualRun": "бег", "Ride": "вело", "VirtualRide": "вело",
              "GravelRide": "вело", "MountainBikeRide": "вело", "Walk": "ходьба", "Hike": "поход",
              "WeightTraining": "силовая", "Workout": "тренировка", "Swim": "плавание", "Yoga": "йога",
              "Snowboard": "сноуборд", "AlpineSki": "лыжи", "Other": "тренировка"}


def _owner(conn, user: str, err) -> None:
    if not digest.is_owner(conn, user):
        raise err("эти страницы — только владельцу Дел: это его день, лента и диктовки")


def _today(conn) -> dt.date:
    return conn.execute("SELECT crm.today() AS d").fetchone()["d"]


def _day(conn, day: Any, err) -> dt.date:
    if not day:
        return _today(conn)
    try:
        return dt.date.fromisoformat(str(day))
    except ValueError:
        raise err("day — сутки ГГГГ-ММ-ДД") from None


def _now() -> str:
    return dt.datetime.now(sky.MSK).isoformat(timespec="seconds")


def _svod(conn, user: str, key: str) -> Any:
    row = conn.execute("SELECT value FROM crm.svod WHERE owner_id = %s AND key = %s", (user, key)).fetchone()
    return row["value"] if row else None


def _fresh(rows: list[dict]) -> dict:
    """Когда телефон (любой из них) и intervals последний раз присылали данные."""
    out: dict[str, Any] = {"phone": None, "intervals": None}
    for r in rows:
        src, at = str(r.get("source") or ""), r.get("last_ok")
        if src == "intervals":
            out["intervals"] = at
        elif src.startswith("phone:") and at and (out["phone"] is None or at > out["phone"]):
            out["phone"] = at
    return out


def _num(v: Any) -> float:
    try:
        return float(v or 0)
    except (TypeError, ValueError):
        return 0.0


def _family_spent(rows: list[dict]) -> int:
    """Траты семьи в копейках, положительным числом: shelf = family, ушло, не доход (как life.days)."""
    return -sum(int(r["amount_kop"]) for r in rows
                if r.get("shelf") == "family" and int(r.get("amount_kop") or 0) < 0 and not r.get("income"))


def _lenta(conn, user: str, d: dt.date) -> dict:
    """Лента и всё, что ложится в неё строками: еда, тренировки, сон, траты. Сутки d и вчерашние записи — сон с вечера
    (телефон режет запись по полуночи: голова вчерашней ночи лежит во вчерашнем дне)."""
    ok, why = life.available(conn)
    out: dict[str, Any] = {"archive": {"ok": ok, "error": why}, "entries": [], "categories": [], "meals": [],
                           "workouts": [], "money": [], "wellness": None, "gtg": None, "plan": [], "phone": None,
                           "fresh": {"phone": None, "intervals": None}, "month_spent_kop": 0}
    if not ok:
        return out
    try:
        out["entries"] = life.rows(conn, "entries", d - dt.timedelta(days=1), d)
        out["categories"] = life.rows(conn, "categories", d)
        out["meals"] = life.rows(conn, "meals", d)
        out["workouts"] = life.rows(conn, "workouts", d)
        well = life.rows(conn, "wellness", d)
        out["wellness"] = well[0] if well else None
        gtg = life.rows(conn, "gtg", d)
        out["gtg"] = gtg[0] if gtg else None
        out["plan"] = life.rows(conn, "plan", d)
        month = life.rows(conn, "money", d.replace(day=1), d)
        out["money"] = [r for r in month if r.get("day") == d.isoformat()]
        out["month_spent_kop"] = _family_spent(month)
        phone = life.rows(conn, "phone", d)
        out["phone"] = phone[0] if phone else None
        out["fresh"] = _fresh(life.rows(conn, "freshness", d))
    except life.NoArchive as e:
        out["archive"] = {"ok": False, "error": str(e)}
    return out


def _tiles(conn, user: str, d: dt.date, x: dict) -> dict:
    """Плитки разложенного «Сегодня»: еда к цели, спорт, деньги, Шаббат — те же числа, что у телефона."""
    meals = [m for m in x["meals"] if m.get("confirmed")]
    goals = _svod(conn, user, "food.goals")
    kcal_goal = int(_num(goals.get("kcal"))) if isinstance(goals, dict) else 0
    work = x["workouts"]
    main = max(work, key=lambda w: _num(w.get("minutes")), default=None)
    well = x["wellness"] or {}
    where = sky.place(conn, user)
    return {
        "kcal": round(sum(_num(m.get("kcal")) for m in meals)),
        "protein": round(sum(_num(m.get("protein_g")) for m in meals)),
        "kcal_goal": kcal_goal,
        "sport_min": round(sum(_num(w.get("minutes")) for w in work)),
        "sport_name": (main.get("name") or SPORT_KIND.get(main.get("type") or "", "тренировка")) if main else "",
        "sleep_h": well.get("sleep_h"),
        "tsb": well.get("tsb") if _num(well.get("ctl")) > 0 else None,
        "money_day_kop": _family_spent(x["money"]),
        "money_month_kop": x["month_spent_kop"],
        "month": MONTHS[d.month - 1],
        "shabbat": sky.shabbat(dt.datetime.now(dt.timezone.utc), where["lat"], where["lon"]),
    }


def view_day(conn, user: str, day=None, err=None, **_) -> dict:
    """«Сегодня»: лента дня с тем, что в ней случилось, плитки, дайджест, встречи дня, команды Засечки."""
    _owner(conn, user, err)
    d = _day(conn, day, err)
    x = _lenta(conn, user, d)
    meetings = conn.execute(
        "SELECT id, title, started_at, duration_min, sphere FROM crm.meetings WHERE owner_id = %s AND archived_at IS NULL "
        "AND (started_at AT TIME ZONE 'Europe/Moscow')::date = %s ORDER BY started_at", (user, d)).fetchall()
    return {"day": d, "today": _today(conn), "now": _now(), **x, "tiles": _tiles(conn, user, d, x),
            "digest": digest.last(conn, user), "meetings": meetings, "commands": zasechka.recent(conn, user)}


def view_zasechka(conn, user: str, day=None, err=None, **_) -> dict:
    """«Засечка»: лента дня, сколько в ленте у каждого дела за месяц и какой категорией его писали, команды телефону."""
    _owner(conn, user, err)
    d = _day(conn, day, err)
    x = _lenta(conn, user, d)
    tasks: dict[str, dict] = {}
    if x["archive"]["ok"]:
        try:
            for e in life.rows(conn, "entries", d - dt.timedelta(days=TASK_DAYS), d):
                tid = e.get("task_id")
                if not tid:
                    continue
                t = tasks.setdefault(tid, {"min": 0, "category": "", "last": ""})
                t["min"] += int(e.get("minutes") or 0)
                if str(e.get("start_at") or "") >= t["last"]:
                    t["last"] = str(e.get("start_at") or "")
                    t["category"] = e.get("category") or t["category"]
        except life.NoArchive as e:
            x["archive"] = {"ok": False, "error": str(e)}
    return {"day": d, "today": _today(conn), "now": _now(), **x, "tasks": tasks,
            "commands": zasechka.recent(conn, user)}


def view_pogoda(conn, user: str, err=None, **_) -> dict:
    """Погода над «Сейчас» — своим видом: прогноз из сети не держит день."""
    _owner(conn, user, err)
    return {"weather": sky.forecast(sky.place(conn, user))}


def view_pravka(conn, user: str, days=None, until=None, err=None, **_) -> dict:
    """«Правка»: диктовки телефона и компьютера за days суток до until (свежие сверху), каждая — с её чисткой."""
    _owner(conn, user, err)
    today = _today(conn)
    try:
        n = min(PRAVKA_MAX, max(1, int(days or PRAVKA_DAYS)))
        end = dt.date.fromisoformat(str(until)) if until else today
    except ValueError:
        raise err("days — число, until — ГГГГ-ММ-ДД") from None
    since = end - dt.timedelta(days=n - 1)
    ok, why = life.available(conn)
    out: dict[str, Any] = {"archive": {"ok": ok, "error": why}, "since": since, "until": end, "days": n, "today": today,
                           "items": []}
    if not ok:
        return out
    try:
        # Чистка телефона пишется секундами позже тейка — у тейка в 23:59 она уже завтрашняя: берём сутки сверху.
        takes = life.rows(conn, "dictations", since, end)
        cleans = life.rows(conn, "cleanups", since, end + dt.timedelta(days=1))
    except life.NoArchive as e:
        out["archive"] = {"ok": False, "error": str(e)}
        return out
    out["items"] = pair(takes, cleans)
    return out


# ── «Правка»: склейка тейка с чисткой — как core/Dictations.kt телефона ────────

DICTATION_MODES = {"CLEAN", "BUSINESS", "SOFTEN"}
CLEAN_BEFORE = dt.timedelta(seconds=2)
CLEAN_AFTER = dt.timedelta(seconds=180)
WORD = re.compile(r"[^\W_]+", re.UNICODE)


def _norm(s: str) -> str:
    return (s or "").lower().replace("ё", "е")


def _words(s: str) -> set[str]:
    return {w for w in WORD.findall(_norm(s)) if len(w) >= 2}


def overlap(take: set[str], inp: str) -> float:
    """Доля слов тейка, нашедшихся во входе чистки."""
    if not take:
        return 0.0
    got = _words(inp)
    return sum(1 for w in take if w in got) / len(take)


def _at(v: Any) -> dt.datetime | None:
    if isinstance(v, dt.datetime):
        return v
    try:
        return dt.datetime.fromisoformat(str(v).replace("Z", "+00:00"))
    except ValueError:
        return None


def device_of(mic: str | None) -> tuple[str, str]:
    """Кто слушал: «телефон», «гарнитура «OpenComm2 by Shokz_II»», при смене — «… → …»; важно последнее."""
    last = (mic or "").rsplit("→", 1)[-1].strip()
    if "гарнитур" not in last:
        return "phone", "телефон"
    name = last.split("«", 1)[1].rsplit("»", 1)[0].strip() if "«" in last and "»" in last else ""
    return "headset", short_headset(name)


def short_headset(name: str) -> str:
    """«OpenComm2 by Shokz_II» → «Shokz»; без имени — «гарнитура»."""
    if not name.strip():
        return "гарнитура"
    brand = name.split(" by ", 1)[1] if " by " in name else name
    brand = re.sub(r"_[A-Za-z0-9]+$", "", brand).strip()[:18]
    return brand or "гарнитура"


def engine_label(engine: str | None) -> str:
    """Чем распознано — словами: «Google, по сети», «GigaAM-v3, по ходу речи», «Whisper medium, видеокарта»."""
    e = (engine or "").strip()
    fixed = {"": "—", "google": "Google", "google-net": "Google, по сети", "google-replay": "Google, заново из звука",
             "whisper-small": "Whisper small", "whisper-base": "Whisper base"}
    if e in fixed:
        return fixed[e]
    if e.startswith("gigaam"):
        name = "GigaAM-v3"
    elif "large-v3" in e:
        name = "Groq large-v3"
    elif e.startswith("small"):
        name = "Whisper small"
    elif e.startswith("medium"):
        name = "Whisper medium"
    else:
        return e
    return name + (", видеокарта" if " cuda" in e else "") + (", по ходу речи" if "по ходу" in e else "")


def model_label(model: str | None) -> str:
    """«claude-sonnet-5-5» → «Сонет 5.5» — как Models.label телефона."""
    m = (model or "").strip()
    for key, word in (("sonnet", "Сонет"), ("opus", "Опус"), ("haiku", "Хайку"), ("fable", "Fable")):
        if key in m:
            ver = re.search(r"(\d+)-(\d+)(?:-\d{8})?$", m)
            return word + (f" {ver.group(1)}.{ver.group(2)}" if ver else "")
    return m


def usd(v: float) -> str:
    """«$0,011», «$0,02», «<$0,001»; ноль — пусто."""
    if v <= 0:
        return ""
    if v < 0.0005:
        return "<$0,001"
    s = f"{v:.3f}"
    if s.endswith("0"):
        s = s[:-1]
    return "$" + s.replace(".", ",")


def duration(ms: int) -> str:
    """«15 с», «3 мин 55 с», «1 ч 2 мин»."""
    s = max(0, (int(ms) + 500) // 1000)
    if s < 60:
        return f"{s} с"
    if s < 3600:
        return f"{s // 60} мин" if s % 60 == 0 else f"{s // 60} мин {s % 60} с"
    return f"{s // 3600} ч {(s % 3600) // 60} мин"


STATUS = {"pasted": "вставлено в окно", "clipboard": "оставлено в буфере", "cancelled": "отменено",
          "empty": "ничего не разобрал", "error": "распознавание упало"}


def _item(key: str, at: dt.datetime, origin: str, device: str, take: dict, clean: dict | None, wrote: str | None) -> dict:
    said = take.get("text") or ""
    ok = bool(take.get("ok", True)) and bool(said.strip())
    c_usd = _num(clean.get("cost_usd")) if clean else 0.0
    return {
        "key": key, "at": at.isoformat(), "day": take.get("day"), "time": take.get("time_local") or at.strftime("%H:%M"),
        "origin": origin, "device": device, "engine": take.get("engine") or "", "engine_label": engine_label(take.get("engine")),
        "audio_ms": int(take.get("audio_ms") or 0), "length": duration(int(take.get("audio_ms") or 0)) if take.get("audio_ms") else "",
        "said": said, "wrote": wrote, "text": wrote if wrote and wrote.strip() else said, "ok": ok,
        "error": take.get("error") or ("пустой результат" if not said.strip() else None),
        "clean_model": (clean or {}).get("model") or None, "clean_model_label": model_label((clean or {}).get("model")),
        "clean_usd": c_usd, "clean_usd_label": usd(c_usd), "clean_ms": int((clean or {}).get("latency_ms") or 0),
        "clean_error": (clean or {}).get("error") or None,
        "clean_mode": ((clean or {}).get("style") or (clean or {}).get("mode") or None) if clean else None,
        "status": take.get("status"), "status_label": STATUS.get(take.get("status") or ""),
    }


def pair(takes: list[dict], cleans: list[dict]) -> list[dict]:
    """Диктовки целиком, свежие сверху. Телефон: тейк находит свою чистку по времени (первая свободная в окне
    −2 с…+180 с) и словам (половина слов тейка во входе модели) — связи в журналах телефона нет. Компьютер: тейк и
    чистка — по ключу «pc-<мс>». Тейк без чистки — тоже диктовка (Засечка, еда, дела: их разбирает не чистка «П»)."""
    out: list[dict] = []
    p_cleans = sorted((c for c in cleans if (c.get("origin") or "phone") != "pc"
                       and str(c.get("mode") or "").upper() in DICTATION_MODES and _at(c.get("at"))),
                      key=lambda c: _at(c["at"]))
    used = [False] * len(p_cleans)
    pc_cleans = {}
    for c in cleans:
        if (c.get("origin") or "") == "pc":
            pc_cleans[c.get("take_id") or c.get("id")] = c
    lo = 0
    for t in sorted((t for t in takes if (t.get("origin") or "phone") != "pc" and _at(t.get("at"))),
                    key=lambda t: _at(t["at"])):
        at = _at(t["at"])
        while lo < len(p_cleans) and _at(p_cleans[lo]["at"]) < at - CLEAN_BEFORE:
            lo += 1
        hit = -1
        if t.get("ok", True) and (t.get("text") or "").strip():
            words = _words(t["text"])
            j = lo
            while j < len(p_cleans) and _at(p_cleans[j]["at"]) <= at + CLEAN_AFTER:
                if not used[j] and overlap(words, p_cleans[j].get("input") or "") >= 0.5:
                    hit = j
                    break
                j += 1
        c = None
        if hit >= 0:
            used[hit] = True
            c = p_cleans[hit]
        origin, device = device_of(t.get("mic"))
        wrote = (c.get("output") or None) if c and not c.get("error") else None
        out.append(_item(str(t.get("id")), at, origin, device, t, c, wrote))
    for t in takes:
        if (t.get("origin") or "") != "pc" or not _at(t.get("at")):
            continue
        key = str(t.get("id"))
        c = pc_cleans.get(key)
        cleaned = (c.get("output") or "").strip() if c and not c.get("error") else ""
        final = (t.get("final") or "").strip()
        wrote = cleaned or (final if final and final != (t.get("text") or "").strip() else None)
        out.append(_item(key, _at(t["at"]), "pc", "компьютер", t, c, wrote))
    out.sort(key=lambda i: i["at"], reverse=True)
    return out


def register(views: dict, err) -> None:
    views["day"] = lambda conn, user, **p: view_day(conn, user, err=err, **p)
    views["zasechka"] = lambda conn, user, **p: view_zasechka(conn, user, err=err, **p)
    views["pravka"] = lambda conn, user, **p: view_pravka(conn, user, err=err, **p)
    views["pogoda"] = lambda conn, user, **p: view_pogoda(conn, user, err=err, **p)
