"""«Когда» в карточке дела: срок и напоминание одной строкой (13.10.2026).

Владелец: «срок» и «напомнить» — одна текстовая строка, которую разбирают: «сегодня», «завтра», «завтра в
первой половине», «в пятницу вечером», «через 2 часа», «когда приеду на дачу». До этого в карточке стояли поле
даты (браузер просил мм/дд/гггг) и отдельный блок напоминания с кнопками.

Частые фразы разбирает свой разбор (local) — без модели и мгновенно, пока человек печатает. Чего он не понял —
Sonnet 5.5 без размышлений (llm.ROUTES["when"], Свод models.dela), только по Enter: свой разбор не угадывает,
а честно говорит «не понял», и тогда спрашиваем модель. Ответ — что поставить (поля дела) и словами, как понято
(«завтра (ср 14.10), 9:00–13:00 · напомню в 9:00»): веб показывает это до сохранения, сохраняет сам — операцией
task.set, как любую правку поля.

Смысл слов — как в общем промпте разбора (НАПОМИНАНИЕ, СРОК): утром — 09:00, днём — 13:00, после обеда — 15:00,
вечером — 19:00; часы без «утра» и «вечера» — ближайшее впереди с 07:00 до 22:00. Строка одна на срок и
напоминание, поэтому время в ней — и время дела, и миг напоминания: «завтра в 11» — срок завтра 11:00 и
напомню в 11:00; часть дня — окно («в первой половине» — 9:00–13:00): срок — день, напомню — в начале окна;
«к 15:00» — только срок (напоминать в миг срока поздно); «через 2 часа» — напомню и срок в тот день;
«когда приеду на дачу» — место автопилота телефона (remind.match_place). День без времени снимает время срока.
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import re
import threading
import time

from . import db, llm, parse, remind

log = logging.getLogger("dela.when")

MSK = remind.MSK
MAX_INPUT = 200
WD_SHORT = remind.WD_SHORT
WD_LONG = ["понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"]
# День недели во всех падежах, как его говорят: «в пятницу», «до пятницы», «к пятнице», «пт».
WEEKDAYS = [
    ("понедельник", "понедельника", "понедельнику", "пн", "пон"),
    ("вторник", "вторника", "вторнику", "вт"),
    ("среда", "среду", "среды", "среде", "ср"),
    ("четверг", "четверга", "четвергу", "чт"),
    ("пятница", "пятницу", "пятницы", "пятнице", "пт"),
    ("суббота", "субботу", "субботы", "субботе", "сб"),
    ("воскресенье", "воскресенья", "воскресенью", "вс", "воскр"),
]
MONTHS = ["января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"]
# Части дня: начало и конец окна. Начало — как в общем промпте (утром 09:00, днём 13:00, после обеда 15:00,
# вечером 19:00): в него и напоминание.
WINDOWS = [
    (r"в первой половине(?: дня)?|в первую половину(?: дня)?|до обеда", ("09:00", "13:00")),
    (r"во второй половине(?: дня)?|во вторую половину(?: дня)?", ("14:00", "18:00")),
    (r"после обеда", ("15:00", "18:00")),
    (r"в обед", ("13:00", "14:00")),
    (r"с утра|утром", ("09:00", "12:00")),
    (r"дн[её]м", ("13:00", "16:00")),
    (r"в конце дня", ("17:00", "19:00")),
    (r"вечером", ("19:00", "22:00")),
]
NUM_WORDS = {
    "один": 1, "одну": 1, "одна": 1, "два": 2, "две": 2, "пару": 2, "три": 3, "четыре": 4, "пять": 5, "шесть": 6,
    "семь": 7, "восемь": 8, "девять": 9, "десять": 10, "одиннадцать": 11, "двенадцать": 12, "пятнадцать": 15,
    "двадцать": 20, "тридцать": 30, "сорок": 40, "сорок пять": 45, "полтора": 1.5, "полторы": 1.5,
}
HOUR_WORDS = {k: v for k, v in NUM_WORDS.items() if isinstance(v, int) and 1 <= v <= 12 and k not in ("пару", "одну", "одна")}
# Слова, которые смысла не несут: «напомни мне, пожалуйста, завтра» — это «завтра».
FILLER = {"напомни", "напомнить", "напоминание", "напоминалку", "мне", "пожалуйста", "срок", "сделать", "надо", "нужно",
          "давай", "поставь", "поставить", "в", "во", "на", "и", "это", "его", "ее", "её", "уже", "где-то", "примерно",
          "часов", "часа", "час", "ч", "числа", "года", "г"}
CLEAR_ALL = {"нет", "-", "—", "убрать", "убери", "никогда", "сбросить", "очистить", "без срока и напоминания"}
CLEAR_DUE = {"без срока", "без даты", "срок убрать", "убрать срок"}
CLEAR_REMIND = {"без напоминания", "не напоминать", "не напоминай", "убрать напоминание", "напоминание убрать"}
DAY_FROM, DAY_TO = 7, 22  # часы без «утра» и «вечера» — с 07:00 до 22:00


class WhenError(Exception):
    """Не понял — словами для человека."""


def _norm(s: str) -> str:
    s = (s or "").lower().replace("ё", "е")
    s = re.sub(r"[,;!?«»\"()]", " ", s)
    s = re.sub(r"(?<!\d)[.](?!\d)", " ", s)  # точка в конце фразы — не разделитель числа
    return " ".join(s.split())


def _day_words(day: dt.date, today: dt.date) -> str:
    n = (day - today).days
    tail = day.strftime("%d.%m") + ("" if day.year == today.year else day.strftime(".%y"))
    if n == 0:
        return "сегодня"
    if n == 1:
        return f"завтра ({WD_SHORT[day.weekday()]} {tail})"
    if n == 2:
        return f"послезавтра ({WD_SHORT[day.weekday()]} {tail})"
    if 2 < n < 7:
        return f"{WD_SHORT[day.weekday()]} {tail}"
    return f"{WD_SHORT[day.weekday()]} {tail}" if n < 14 else tail


def _hm(s: str) -> str:
    h, m = s.split(":")
    return f"{int(h)}:{m}"


def words(res: dict, today: dt.date) -> str:
    """Как понято — одной строкой: «завтра (ср 14.10), 9:00–13:00 · напомню в 9:00»."""
    s = res.get("set") or {}
    out = []
    if "due_date" in s:
        if s["due_date"] is None:
            out.append("без срока")
        else:
            day = dt.date.fromisoformat(s["due_date"])
            part = _day_words(day, today)
            if res.get("window"):
                part += f", {_hm(res['window'][0])}–{_hm(res['window'][1])}"
            elif s.get("due_time"):
                part += (", к " if res.get("by_deadline") else ", ") + _hm(s["due_time"])
            out.append(part)
    if "remind_place" in s and s["remind_place"]:
        out.append(f"напомню, когда приедешь: {s['remind_place']}")
    elif "remind_at" in s:
        if s["remind_at"] is None:
            out.append("без напоминания")
        else:
            at = remind.at_of(s["remind_at"])
            same = "due_date" in s and s["due_date"] == at.date().isoformat()
            out.append("напомню в " + (_hm(at.strftime("%H:%M")) if same else remind.when_words(at, today)))
    return " · ".join(out)


# ── Свой разбор ─────────────────────────────────────────────────────────


def _num(tok: str) -> float | None:
    tok = tok.replace(",", ".")
    try:
        return float(tok)
    except ValueError:
        return NUM_WORDS.get(tok)


def _next_wd(today: dt.date, wd: int) -> dt.date:
    """Ближайший такой день впереди; сегодняшний — через неделю (как D.next в вебе и кнопка «Пн»)."""
    n = (wd - today.weekday()) % 7
    return today + dt.timedelta(days=n or 7)


def _pick_hour(h: int, m: int, ampm: str | None, day: dt.date | None, now: dt.datetime) -> tuple[dt.date, str]:
    """Часы словами — в день и ЧЧ:ММ. «утра/дня/вечера/ночи» решают сами; без них — ближайшее впереди с 07:00 до
    22:00 (как в общем промпте разбора): «в 8», сказанное днём, — 20:00 сегодня; «в 11» в 14:00 — 11:00 завтра."""
    today = now.date()
    if ampm == "утра":
        h = 0 if h == 12 else h
    elif ampm in ("дня", "вечера"):
        h = h + 12 if h < 12 and not (ampm == "дня" and h == 12) else h
    elif ampm == "ночи":
        h = 0 if h == 12 else h
    if h > 23 or m > 59:
        raise WhenError("такого времени нет")
    if ampm or h >= 13 or h == 0:
        cands = [h]
    else:
        cands = [x for x in (h, h + 12) if DAY_FROM <= x <= DAY_TO] or [h]
    if day is not None and day != today:
        return day, f"{cands[0]:02d}:{m:02d}"
    ahead = [x for x in cands if dt.datetime(today.year, today.month, today.day, x, m, tzinfo=MSK) > now]
    if ahead:
        return today, f"{ahead[0]:02d}:{m:02d}"
    if day == today:  # «сегодня в 9», а уже 10 — так и ставим: человек сказал «сегодня»
        return today, f"{cands[-1]:02d}:{m:02d}"
    return today + dt.timedelta(days=1), f"{cands[0]:02d}:{m:02d}"


def local(text: str, now: dt.datetime, places: list[str] | None = None, only_date: bool = False) -> dict | None:
    """Частые фразы без модели. Понял — {"set": поля дела, "window"?, "by_deadline"?}; не понял — None (тогда
    модель). Место, которого нет у телефона, — WhenError: модель его тоже не найдёт."""
    s = _norm(text)
    if not s:
        return None
    now = now.astimezone(MSK)
    today = now.date()
    if s in CLEAR_ALL:
        return {"set": {"due_date": None, "due_time": None} if only_date else
                {"due_date": None, "due_time": None, "remind_at": None, "remind_place": None}}
    if s in CLEAR_DUE:
        return {"set": {"due_date": None, "due_time": None}}
    if s in CLEAR_REMIND and not only_date:
        return {"set": {"remind_at": None, "remind_place": None}}

    rest = f" {s} "
    day: dt.date | None = None
    window = None
    hm = None            # время, названное часами: (ч, м, «утра/вечера»)
    deadline = False     # «к 15:00», «до 15» — срок, а не миг напоминания
    at: dt.datetime | None = None  # «через 2 часа» — готовый миг
    place = None

    def take(pattern: str):
        nonlocal rest
        m = re.search(pattern, rest)
        if m:
            rest = rest[:m.start()] + " " + rest[m.end():]
        return m

    # Место: «когда приеду на дачу», «как буду дома», «по приезде в офис» — одно слово: дальше бывает «завтра».
    m = take(r" (?:(?:когда|как|как только) )?(?:приеду|приедем|буду|будем|доберусь|доберемся|окажусь|вернусь|вернемся|появлюсь)"
             r"(?: (?:на|в|во|до|к|ко))? ([а-яa-z-]+) | по приезд[еу] (?:(?:на|в|во|к|ко) )?([а-яa-z-]+) ")
    if m:
        if only_date:
            return None
        said = (m.group(1) or m.group(2)).strip()
        if said in ("домой", "дома"):
            said = "дом"
        known = places or []
        place = remind.match_place(said, known) or next(
            (p for p in known if remind.match_place(said, [p.split(" ")[0]])), "")
        if not place:
            raise WhenError(f"у телефона нет места «{said}»" + (f" — есть: {', '.join(known)}" if known
                                                                  else " — места знает автопилот Засечки на телефоне"))
    # «Через 2 часа», «через полчаса», «через 3 дня», «через неделю».
    m = take(r" через (полчаса|полтора часа|пол часа|(?:(\d+(?:[.,]\d+)?|[а-я]+(?: пять)?) )?(минуту|минуты|минут|мин|час|часа|часов|ч|день|дня|дней|сутки|суток|неделю|недели|недель|месяц|месяца|месяцев)) ")
    if m:
        if m.group(1) in ("полчаса", "пол часа"):
            n, unit = 0.5, "час"
        elif m.group(1) == "полтора часа":
            n, unit = 1.5, "час"
        else:
            n = _num(m.group(2)) if m.group(2) else 1
            unit = m.group(3)
            if n is None:
                return None
        if unit.startswith(("мин", "час")) or unit == "ч":
            if only_date:
                return None
            mins = n if unit.startswith("мин") else n * 60
            if not 1 <= mins <= 7 * 24 * 60:
                return None
            at = (now + dt.timedelta(minutes=mins)).replace(second=0, microsecond=0)
        else:
            days = n * (7 if unit.startswith("нед") else 30 if unit.startswith("мес") else 1)
            if days != int(days) or not 1 <= days <= 366:
                return None
            day = today + dt.timedelta(days=int(days))
    # Даты: ГГГГ-ММ-ДД, ДД.ММ(.ГГ), «15 октября».
    if day is None and at is None:
        m = take(r" (\d{4})-(\d{2})-(\d{2}) ")
        if m:
            try:
                day = dt.date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
            except ValueError:
                return None
    if day is None and at is None:
        m = re.search(r" (?:(в|к|до|около) )?(\d{1,2})[./](\d{1,2})(?:[./](\d{2,4}))? ", rest)
        # «в 11.30» — время; «15.10», «к 15.10» — дата (если месяц похож на месяц).
        if m and not (m.group(1) in ("в", "около") and not m.group(4) and int(m.group(2)) <= 23 and int(m.group(3)) <= 59) \
                and 1 <= int(m.group(3)) <= 12:
            take(re.escape(m.group(0)))
            y = int(m.group(4)) if m.group(4) else today.year
            y = y + 2000 if y < 100 else y
            try:
                day = dt.date(y, int(m.group(3)), int(m.group(2)))
            except ValueError:
                return None
            if not m.group(4) and day < today:
                day = day.replace(year=day.year + 1)
            deadline = m.group(1) in ("к", "до")
    if day is None and at is None:
        m = take(r" (?:(?:к|до|на) )?(\d{1,2})(?:-?го)? (" + "|".join(MONTHS) + r")(?: (\d{4}))? ")
        if m:
            try:
                day = dt.date(int(m.group(3) or today.year), MONTHS.index(m.group(2)) + 1, int(m.group(1)))
            except ValueError:
                return None
            if not m.group(3) and day < today:
                day = day.replace(year=day.year + 1)
    # Дни словами.
    if day is None and at is None:
        for pat, fn in (
            (r" сегодня ", lambda: today),
            (r" послезавтра ", lambda: today + dt.timedelta(days=2)),
            (r" завтра ", lambda: today + dt.timedelta(days=1)),
            (r" (?:на|в) следующей неделе | на след(?:\.)? неделе ", lambda: _next_wd(today, 0)),
            (r" (?:к|в|до) конц[уае] недели ", lambda: today + dt.timedelta(days=(4 - today.weekday()) % 7)),
            (r" (?:к|в|до) конц[уае] месяца ",
             lambda: (today.replace(day=28) + dt.timedelta(days=4)).replace(day=1) - dt.timedelta(days=1)),
            (r" (?:в|на) выходн(?:ые|ых) ", lambda: today + dt.timedelta(days=(5 - today.weekday()) % 7)),
        ):
            if take(pat):
                day = fn()
                break
    if day is None and at is None:
        forms = "|".join(f for wd in WEEKDAYS for f in wd)
        m = take(r" (?:(?:в|во|к|ко|до|на) )?(?:(эт[оау]|этот|следующ(?:ий|ую|ее|ая)|след) )?(" + forms + r") ")
        if m:
            wd = next(i for i, fs in enumerate(WEEKDAYS) if m.group(2) in fs)
            mod = m.group(1) or ""
            if mod.startswith("след"):
                day = _next_wd(today, 0) + dt.timedelta(days=wd)
            elif mod.startswith("эт"):
                day = today + dt.timedelta(days=(wd - today.weekday()) % 7)
            else:
                day = _next_wd(today, wd)
    # Часть дня.
    if at is None:
        for pat, win in WINDOWS:
            if take(r" (?:" + pat + r") "):
                window = win
                break
    # Часы: «в 11», «в 11:30», «к 15:00», «в 9 утра», «в 3 дня», «в девять вечера», «в полдень», голое «11:30».
    m = take(r" (в|к|до|около) (полдень|полночь) ")
    if m:
        hm = (12 if m.group(2) == "полдень" else 0, 0, "дня" if m.group(2) == "полдень" else "ночи")
        deadline = m.group(1) in ("к", "до")
    else:
        words_h = "|".join(sorted(HOUR_WORDS, key=len, reverse=True))
        m = take(r" (?:(в|к|до|около) )?(\d{1,2}|" + words_h + r")(?:[:.](\d{2}))?(?: (?:часов|часа|час|ч))?(?: (утра|дня|вечера|ночи))? ")
        if m and (m.group(1) or m.group(3) or m.group(4)):
            h = int(m.group(2)) if m.group(2).isdigit() else HOUR_WORDS[m.group(2)]
            hm = (h, int(m.group(3) or 0), m.group(4))
            deadline = m.group(1) in ("к", "до")
        elif m:
            return None  # голое число без «в» и двоеточия — не знаем, что это
    if hm is not None and window is not None:
        # «завтра утром в 9», «в пятницу вечером в 8» — часы точнее окна, а окно говорит, утро это или вечер.
        hint = {"09:00": "утра", "19:00": "вечера"}.get(window[0], "дня")
        hm = (hm[0], hm[1], hm[2] or (hint if hm[0] <= 12 else None))
        window = None
    if at is not None and (hm is not None or window is not None):
        return None
    # Всё ли понято: остаться могут только пустые слова.
    left = [w for w in rest.split() if w not in FILLER]
    if left:
        return None
    if day is None and at is None and window is None and hm is None and place is None:
        return None

    out: dict = {}
    res: dict = {"set": out}
    if place:
        out.update(remind_place=place, remind_at=None)
        if day:
            out.update(due_date=day.isoformat(), due_time=None)
        return res
    if at is not None:
        out.update(due_date=at.date().isoformat(), due_time=None)
        if not only_date:
            out.update(remind_at=remind.iso(at), remind_place=None)
        return res if not only_date else {"set": {"due_date": out["due_date"]}}
    if hm is not None:
        try:
            day, t = _pick_hour(hm[0], hm[1], hm[2], day, now)
        except WhenError:
            return None  # «в 25» — не время; пусть посмотрит модель
        if only_date:
            return {"set": {"due_date": day.isoformat()}}
        out.update(due_date=day.isoformat(), due_time=t)
        if deadline:
            res["by_deadline"] = True
        else:
            h, mi = map(int, t.split(":"))
            out.update(remind_at=remind.iso(dt.datetime(day.year, day.month, day.day, h, mi, tzinfo=MSK)), remind_place=None)
        return res
    if window is not None:
        if day is None:
            start = dt.datetime.combine(today, dt.time(*map(int, window[0].split(":"))), MSK)
            day = today if start > now else today + dt.timedelta(days=1)
        if only_date:
            return {"set": {"due_date": day.isoformat()}}
        h, mi = map(int, window[0].split(":"))
        out.update(due_date=day.isoformat(), due_time=None, remind_at=remind.iso(dt.datetime(day.year, day.month, day.day, h, mi, tzinfo=MSK)),
                   remind_place=None)
        res["window"] = list(window)
        return res
    out.update(due_date=day.isoformat(), due_time=None)
    if only_date:
        return {"set": {"due_date": out["due_date"]}}
    return res


# ── Модель — для того, что свой разбор не понял ─────────────────────────

SYSTEM = """Ты понимаешь, когда Саше делать дело и когда напомнить, — по короткой фразе из карточки дела в его
сервисе «Дела». Фразу часто надиктовывают: слова бывают услышаны неверно — сначала пойми смысл.

Сегодняшняя дата, время «сейчас», календарь на две недели и места телефона — в сообщении. Верни поля:
— understood: false, только если во фразе нет совсем ни дня, ни времени, ни места («после праздников» —
  каких?); тогда question — коротко, чего не хватает. Неточное не переспрашивай, а бери разумный день: «на
  днях» — через 2 дня; «не раньше пятницы» — пятница; «к концу квартала», «к концу года» — его последний день;
  «как проснусь» — утром. Иначе true и question "".
— due — день дела ГГГГ-ММ-ДД по календарю; "-" — убрать срок; "" — срок не трогать. «На следующей неделе» —
  её понедельник; «к концу недели» — пятница; «в выходные» — суббота; «в пятницу» — ближайшая впереди
  (сегодняшний день недели — через неделю).
— due_time — ЧЧ:ММ, только если названо время самого дела («в 11», «к 15:00»), иначе "".
— window_from, window_to — часть дня окном ЧЧ:ММ: в первой половине — 09:00–13:00, во второй — 14:00–18:00,
  утром — 09:00–12:00, днём — 13:00–16:00, после обеда — 15:00–18:00, вечером — 19:00–22:00; иначе "".
— remind_at — когда напомнить в Telegram, «ГГГГ-ММ-ДД ЧЧ:ММ»: время во фразе («в 11» — в 11:00), начало окна,
  «через 2 часа» — от времени «сейчас»; «к 15:00» и «до 15» — срок, а не напоминание: тогда "". Нет во фразе
  ни времени, ни части дня, ни «через N минут/часов» («через полтора дня», «в среду», «на днях») — только срок, а
  remind_at "". Напоминание ночью (с 23:00 до 07:00) не ставь. "-" — убрать
  напоминание («не напоминай»); "" — не трогать. Часы без «утра» и «вечера» — ближайшее впереди такое время с
  07:00 до 22:00. Время без дня — сегодня, а если сегодня оно уже прошло — завтра.
— remind_place — «когда приеду на дачу», «как буду дома»: ровно одно имя из МЕСТ; места нет в списке —
  understood false и в question — какого места нет. "-" — убрать; "" — не трогать.
Только то, что сказано: не выдумывай."""

SCHEMA = {
    "type": "object",
    "properties": {
        "understood": {"type": "boolean"},
        **{k: {"type": "string"} for k in ("due", "due_time", "window_from", "window_to", "remind_at", "remind_place", "question")},
    },
    "required": ["understood", "due", "due_time", "window_from", "window_to", "remind_at", "remind_place", "question"],
    "additionalProperties": False,
}


def _calendar(today: dt.date) -> str:
    return "; ".join(f"{WD_SHORT[d.weekday()]} {d.isoformat()}" + (" — сегодня" if i == 0 else " — завтра" if i == 1 else "")
                     for i, d in enumerate(today + dt.timedelta(days=k) for k in range(15)))


def message(text: str, now: dt.datetime, places: list[str], only_date: bool = False) -> str:
    now = now.astimezone(MSK)
    lines = [f"Сегодня {now.date().isoformat()}, {WD_LONG[now.weekday()]}, сейчас {now.strftime('%H:%M')}.",
             "Календарь: " + _calendar(now.date()),
             "МЕСТА: " + (", ".join(places) if places else "(телефон пока не знает ни одного)")]
    if only_date:
        lines.append("Нужен только день (due): время, окно и напоминание оставь пустыми.")
    lines.append(f"ФРАЗА: {text}")
    return "\n".join(lines)


def ask(cl, model: str, effort: str, text: str) -> dict:
    """Один короткий запрос, ответ — строго по схеме. Sonnet 5.5 — без размышлений (between_tools: thinking
    у него не выключается); у другой модели дороги — адаптивные с той же глубиной."""
    extra = {"thinking": {"type": "between_tools"}} if model == "claude-sonnet-5-5" else {}
    msg = cl.beta.messages.create(
        model=model,
        max_tokens=2000,
        system=SYSTEM,
        output_config={"effort": effort, "format": {"type": "json_schema", "schema": SCHEMA}},
        messages=[{"role": "user", "content": text}],
        betas=[parse.FALLBACK_BETA],  # отказ — повтор на резервной внутри того же запроса
        fallbacks="default",
        **extra,
    )
    if msg.stop_reason == "refusal":
        raise WhenError("Claude отказался разбирать эту фразу")
    body = next((b.text for b in msg.content if getattr(b, "type", "") == "text"), "")
    try:
        data = json.loads(body)
    except ValueError as e:
        raise WhenError(f"Claude ответил не JSON ({e})") from e
    data["_usage"] = llm.usage_of(msg, model)
    return data


def from_model(data: dict, now: dt.datetime, places: list[str], only_date: bool = False) -> dict:
    """Ответ модели → то же, что у своего разбора: поля дела, окно. Проверяем сами: чужая дата и чужое место — не
    ставим, а говорим, что не поняли."""
    if not data.get("understood"):
        raise WhenError((data.get("question") or "").strip() or "не понял, когда")
    g = lambda k: (data.get(k) or "").strip()  # noqa: E731
    now = now.astimezone(MSK)
    out: dict = {}
    res: dict = {"set": out}
    if g("due") == "-":
        out.update(due_date=None, due_time=None)
    elif g("due"):
        try:
            out["due_date"] = dt.date.fromisoformat(g("due")).isoformat()
        except ValueError as e:
            raise WhenError(f"не понял день «{g('due')}»") from e
        out["due_time"] = remind.norm_time(g("due_time"))
    if only_date:
        if "due_date" not in out:
            raise WhenError("не понял день")
        return {"set": {"due_date": out["due_date"]}}
    if remind.norm_time(g("window_from")) and remind.norm_time(g("window_to")):
        res["window"] = [remind.norm_time(g("window_from")), remind.norm_time(g("window_to"))]
        out["due_time"] = None
    elif out.get("due_time") and not g("remind_at"):
        res["by_deadline"] = True
    if g("remind_place") == "-" or g("remind_at") == "-":
        out.update(remind_at=None, remind_place=None)
    if g("remind_place") not in ("", "-"):
        place = remind.match_place(g("remind_place"), places)
        if not place:
            raise WhenError(f"у телефона нет места «{g('remind_place')}»" + (f" — есть: {', '.join(places)}" if places else ""))
        out.update(remind_place=place, remind_at=None)
    elif g("remind_at") not in ("", "-"):
        at = remind.local_iso(g("remind_at"))
        if not at:
            raise WhenError(f"не понял время «{g('remind_at')}»")
        out.update(remind_at=at, remind_place=None)
        if "due_date" not in out:  # срок отдельно не назван — день напоминания (как в общем промпте)
            out.update(due_date=remind.at_of(at).date().isoformat(), due_time=None)
    if not out:
        raise WhenError("не понял, когда")
    return res


# ── Вход ────────────────────────────────────────────────────────────────

# Модель — не чаще MODEL_MAX раз за MODEL_WINDOW секунд на человека: строка в вебе зовёт её только по Enter,
# а это — от залипшей клавиши и чужого скрипта с чужим входом.
MODEL_MAX, MODEL_WINDOW = 20, 300
_calls: dict[str, list[float]] = {}
_lock = threading.Lock()


def _room(user: str) -> bool:
    with _lock:
        now = time.monotonic()
        got = [x for x in _calls.get(user, []) if now - x < MODEL_WINDOW]
        if len(got) >= MODEL_MAX:
            _calls[user] = got
            return False
        _calls[user] = got + [now]
        return True


def understand(url: str, user: str, text: str, key: str = "", proxy: str | None = None, use_model: bool = True,
               only_date: bool = False, ask_fn=None, now: dt.datetime | None = None) -> dict:
    """Фраза → что поставить и как понято. use_model=False — только свой разбор (пока человек печатает).
    ask_fn — подмена Claude в тестах."""
    text = " ".join((text or "").split())
    if not text:
        raise WhenError("пусто — напиши, когда")
    if len(text) > MAX_INPUT:
        raise WhenError(f"длиннее {MAX_INPUT} знаков — скажи короче: «завтра в 11», «в пятницу вечером»")
    with db.session(url, user, via="when") as conn:
        clock = conn.execute("SELECT now() AS n").fetchone()["n"]
        places = remind.places_of(conn, user)
    now = (now or clock).astimezone(MSK)
    got = local(text, now, places, only_date)
    if got is not None:
        return {"ok": True, "by": "local", **got, "words": words(got, now.date())}
    if not use_model:
        return {"ok": False, "by": None, "need_model": True, "error": "сам не понял — Enter, спрошу Claude"}
    model, effort = llm.route(url, "when")
    if ask_fn is None:
        if not key:
            raise WhenError("не понял, а Claude не настроен: напиши проще — «завтра в 11», «в пятницу вечером»")
        if not _room(user):
            raise WhenError("слишком часто спрашиваю Claude — подожди пару минут или напиши проще")
        cl = parse.client(key, proxy)
        ask_fn = lambda m: ask(cl, model, effort, m)  # noqa: E731
    data = ask_fn(message(text, now, places, only_date))
    usage = data.get("_usage")
    usd = llm.cost(usage, model, url)
    llm.account(url, "when", usd, (usage or {}).get("model") or model)
    res = from_model(data, now, places, only_date)
    log.info("«когда» Claude: %d знаков, $%.4f, токены %s", len(text), usd, usage)
    return {"ok": True, "by": "claude", **res, "words": words(res, now.date()), "usd": round(usd, 5)}
