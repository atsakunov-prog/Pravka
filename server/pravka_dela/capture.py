"""Захват — «сказал → легло → вернётся» (шаг 0 петли этапа 4, 15.10.2026).

Владелец 15.10.2026: «Приоритет номер один в петле — захват за три секунды, которому я доверяю. Сегодня я хотел
„напомни в 12 в Контуре отправить счёт“ и не знал, куда: в календарь нельзя — Засечка примет за встречу; в Дела —
утонет среди остального. Сказал Алисе. Значит, у нас этого нет, даже если технически есть». Договор (loop\\README.md,
шаг 0): одна точка входа — Саша не выбирает, куда класть; система раскладывает в корзину («Сейчас», «Сегодня»,
напоминание, «потом», заметка, идея, сделано) и за секунду подтверждает одной строкой, куда легло и когда вернётся;
не угадала — поправка одним касанием.

Одна дверь — сервер: «сказать», «Д», «З» и строка веба шлют фразу одной операцией capture.say (обычная очередь
/api/ops: op_id, без связи — ждёт в очереди телефона). Здесь:
- rule — правило ($0, миллисекунды): одна мысль формы «напомни …», «когда приеду …», «идея …», «Иван сказал …»,
  «заметка по X: …», «позвонил Ольге», «Иван прислал сверку», «… на неделе», «сегодня …», голое «купить магний». Не
  узнало — молчит (None), и фраза уходит модели;
- модель — тот же разбор надиктовки (Свод prompt.raznoska) с блоком КОРЗИНЫ (with_capture) в фоне на сервере (tick),
  итог — видом capture?ref. Корзину по полям ответа выбирает код (from_model), не модель; правило сильнее модели;
- apply — корзина в операции Дел (дело, напоминание kind 'remind', «потом» back_on, заметка в хронологию, идея
  crm.ideas, сделанное task.did); line и chips — строка подтверждения и до трёх кнопок поправки; move — поправка
  одним касанием (capture.move): «Это дело», «Время ▾», «Сегодня», «В «Сейчас»», «Потом», «Идея», «Заметка»,
  «Не закрывать», «Отменить».
Телефон и веб только рисуют строку и кнопки, которые отдал сервер («одна правда — сервер», правило 14).

Почему операция, а не свой путь /api/capture: закрытый список путей посредника на Aeza пропускает api/ops и
api/view/<имя>, новый путь снаружи был бы 404 (правка nginx — только скриптом владельца).
"""

from __future__ import annotations

import calendar
import datetime as dt
import json
import logging
import re
import threading
import time
import uuid
from pathlib import Path

from psycopg.types.json import Jsonb

from . import db, guard, llm, people, remind, when

log = logging.getLogger("dela.capture")

MSK = remind.MSK
MAX_INPUT = 4000
RULE_WORDS_MAX = 25        # длиннее — не «одна мысль», правило молчит
BARE_WORDS_MAX = 12        # голое дело («купить магний») — не длиннее
UNDO_MIN = 10              # «Отменить» в пилюле и меню «Д» — 10 минут, дальше — в самом деле
DEFAULT_EVENING = (19, 0)  # «напомни …» без времени — сегодня 19:00
LATE_HOUR = 18             # после 18:00 — завтра 9:00
MORNING = (9, 0)
WAKE = threading.Event()   # capture.say с ждущей наговоркой будит фоновый разбор (__main__.capture_loop)
SYNC_GRACE_MIN = 5         # наговорку строки веба (say_now) фоновый цикл не трогает 5 минут — её разбирает сам запрос
REF_PREFIX = "capture:"
ROUTE = "capture"
CAPTURE_KEY = "prompt.raznoska_capture"
PROMPTS = Path(__file__).resolve().parents[1] / "contract" / "prompts"

WD_ACC = ["понедельник", "вторник", "среду", "четверг", "пятницу", "субботу", "воскресенье"]
WD_SHORT = ["пн", "вт", "ср", "чт", "пт", "сб", "вс"]
MONTHS_GEN = ["января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября",
              "ноября", "декабря"]
MONTHS_LOC = ["январе", "феврале", "марте", "апреле", "мае", "июне", "июле", "августе", "сентябре", "октябре",
              "ноябре", "декабре"]


class CaptureError(Exception):
    """Захват не сложился — словами для человека."""


# ── Время и дни словами ─────────────────────────────────────────────────


def _hm(at: dt.datetime) -> str:
    return f"{at.hour}:{at.minute:02d}"


def _v(day: dt.date) -> str:
    return "во" if day.weekday() == 1 else "в"


def when_say(at: dt.datetime, now: dt.datetime) -> str:
    """Миг напоминания словами: «сегодня в 12:00», «завтра в 9:00», «во вторник 20.10 в 9:00», «23 октября в 9:00».
    День недели — с датой (проверка 16.10.2026: «напомни 20 октября …» давало «во вторник в 9:00» — какой вторник?)."""
    at = at.astimezone(MSK)
    today = now.astimezone(MSK).date()
    n = (at.date() - today).days
    if n == 0:
        return f"сегодня в {_hm(at)}"
    if n == 1:
        return f"завтра в {_hm(at)}"
    if n == 2:
        return f"послезавтра в {_hm(at)}"
    if 2 < n <= 6:
        return f"{_v(at.date())} {WD_ACC[at.weekday()]} {_ddmm(at.date(), today)} в {_hm(at)}"
    if n < 0:
        return f"{at.day} {MONTHS_GEN[at.month - 1]} в {_hm(at)}" if n < -1 else f"вчера в {_hm(at)}"
    return f"{at.day} {MONTHS_GEN[at.month - 1]} в {_hm(at)}"


def _ddmm(d: dt.date, today: dt.date) -> str:
    return d.strftime("%d.%m") + ("" if d.year == today.year else d.strftime(".%y"))


def day_on(d: dt.date, today: dt.date) -> str:
    """«На …»: «На сегодня», «На завтра», «На пятницу 16.10», «На 23 октября»."""
    n = (d - today).days
    if n == 0:
        return "На сегодня"
    if n == 1:
        return "На завтра"
    if n == 2:
        return "На послезавтра"
    if 2 < n < 14:
        return f"На {WD_ACC[d.weekday()]} {_ddmm(d, today)}"
    return f"На {d.day} {MONTHS_GEN[d.month - 1]}" + ("" if d.year == today.year else f" {d.year}")


def back_say(d: dt.date, today: dt.date) -> str:
    """Когда вернётся «потом»: «верну в пятницу 16.10», «верну в понедельник 19.10», «верну 11 января»."""
    n = (d - today).days
    if n <= 0:
        return "верну сегодня"
    if n < 14:  # и завтра — днём недели с датой: «верну в пятницу 16.10» читается без счёта
        return f"верну {_v(d)} {WD_ACC[d.weekday()]} {_ddmm(d, today)}"
    return f"верну {d.day} {MONTHS_GEN[d.month - 1]}" + ("" if d.year == today.year else f" {d.year}")


def back_chip(d: dt.date, today: dt.date) -> str:
    """Метка дела «потом» в списках: «↩ пт 16.10»."""
    return f"↩ {WD_SHORT[d.weekday()]} {_ddmm(d, today)}"


# ── «Потом»: день возврата правилом (spec-0 §2.3.1) ───────────────────────


def _workday(d: dt.date) -> dt.date:
    return d + dt.timedelta(days=(7 - d.weekday()) % 7) if d.weekday() >= 5 else d


def next_workday(today: dt.date) -> dt.date:
    return _workday(today + dt.timedelta(days=1))


def next_monday(today: dt.date) -> dt.date:
    return today + dt.timedelta(days=7 - today.weekday())


BACK_SOON = ("потом", "позже", "когда-нибудь", "когда нибудь", "не срочно", "на следующей неделе", "на след неделе")
BACK_WEEK = ("на неделе", "на этой неделе")


def default_back(today: dt.date, said: str = "") -> dt.date:
    """День возврата дела «потом» по сказанному. Ничего — следующий рабочий день; «на неделе» — середина недели или
    следующий понедельник; «потом», «на следующей неделе» — понедельник; «через месяц» — +месяц (рабочий день);
    «в январе» — первый рабочий день месяца (у января — после каникул)."""
    s = " ".join(str(said or "").lower().replace("ё", "е").split())
    if not s:
        return next_workday(today)
    if s in BACK_WEEK:
        wd = today.weekday()
        return today + dt.timedelta(days=2 - wd) if wd <= 1 else today + dt.timedelta(days=1) if wd <= 3 else next_monday(today)
    if s in BACK_SOON:
        return next_monday(today)
    if s == "через месяц":
        y, m = (today.year + (today.month == 12), today.month % 12 + 1)
        return _workday(dt.date(y, m, min(today.day, calendar.monthrange(y, m)[1])))
    m = re.fullmatch(r"в (\S+)", s)
    if m and m.group(1) in MONTHS_LOC:
        month = MONTHS_LOC.index(m.group(1)) + 1
        if month == today.month:
            return next_workday(today)
        year = today.year + (month < today.month)
        return _workday(dt.date(year, 1, 9)) if month == 1 else _workday(dt.date(year, month, 1))
    return next_workday(today)


# ── Имена в падежах (для строк: «у Ивана Тестова», «с Иваном Тестовым») ────────

_HUSH = "гкхжшщч"


def _gender(name: str) -> str:
    ws = people.words(name)
    for w in ws:
        g = people.name_gender(w)
        if g:
            return g
    for w in ws:
        if re.search(r"(ова|ева|ина|ына|ская|цкая)$", w):
            return "f"
    last = ws[0] if ws else ""
    return "f" if last.endswith(("а", "я")) and not people.name_gender(last) == "m" else "m"


def _gen_word(w: str, g: str) -> str:
    lw = w.lower()
    if len(lw) < 2 or not re.fullmatch(r"[а-яё-]+", lw):
        return w
    if g == "f":
        if lw.endswith(("ова", "ева", "ина", "ына")):
            return w[:-1] + "ой"
        if lw.endswith(("ская", "цкая")):
            return w[:-2] + "ой"
        if lw.endswith("ия") or lw.endswith("я"):
            return w[:-1] + "и"
        if lw.endswith("а"):
            return w[:-1] + ("и" if lw[-2] in _HUSH else "ы")
        return w
    if lw.endswith(("ский", "цкий")):
        return w[:-2] + "ого"
    if lw.endswith(("й", "ь")):
        return w[:-1] + "я"
    if lw.endswith("а"):
        return w[:-1] + ("и" if lw[-2] in _HUSH else "ы")
    if lw.endswith("я"):
        return w[:-1] + "и"
    if lw[-1] in "бвгджзклмнпрстфхцчшщ":
        return w + "а"
    return w


def _ins_word(w: str, g: str) -> str:
    lw = w.lower()
    if len(lw) < 2 or not re.fullmatch(r"[а-яё-]+", lw):
        return w
    if g == "f":
        if lw.endswith(("ова", "ева", "ина", "ына")):
            return w[:-1] + "ой"
        if lw.endswith(("ская", "цкая")):
            return w[:-2] + "ой"
        if lw.endswith("ия"):
            return w[:-1] + "ей"
        if lw.endswith("я"):
            return w[:-1] + "ей"
        if lw.endswith("а"):
            return w[:-1] + ("ей" if lw[-2] in "жшщчц" else "ой")
        return w
    if lw.endswith(("ский", "цкий")):
        return w[:-2] + "им"
    if lw.endswith(("ов", "ев", "ин", "ын")):
        return w + "ым"
    if lw.endswith("ий"):
        return w[:-1] + "ем"
    if lw.endswith(("й", "ь")):
        return w[:-1] + "ем"
    if lw.endswith("а"):
        return w[:-1] + ("ей" if lw[-2] in "жшщчц" else "ой")
    if lw.endswith("я"):
        return w[:-1] + "ей"
    if lw[-1] in "жшщчц":
        return w + "ем"
    if lw[-1] in "бвгдзклмнпрстфх":
        return w + "ом"
    return w


def genitive(name: str) -> str:
    """«Иван Тестов» → «Ивана Тестова», «Ольга Тестова» → «Ольги Тестовой». Непонятное — как есть."""
    g = _gender(name)
    return " ".join(_gen_word(w, g) for w in str(name or "").split())


def instrumental(name: str) -> str:
    """«Иван Тестов» → «Иваном Тестовым»."""
    g = _gender(name)
    return " ".join(_ins_word(w, g) for w in str(name or "").split())


# ── Слова фразы ─────────────────────────────────────────────────────────

_EDGE = ",.;:!?«»\"()…“”'"


def _n(w: str) -> str:
    return w.lower().replace("ё", "е")


def tokens(text: str) -> list[dict]:
    """Слова фразы: как сказано (w), нижним регистром (n) и кончается ли запятой, двоеточием или тире (comma)."""
    out: list[dict] = []
    for raw in str(text or "").split():
        w = raw.strip(_EDGE)
        if not w or w in ("-", "—", "–"):
            if out:
                out[-1]["comma"] = True
            continue
        tail = raw.rstrip("»\")”")
        out.append({"w": w, "n": _n(w), "comma": tail.endswith((",", ":", "—", ";"))})
    return out


def _words_of(tt: list[dict]) -> list[str]:
    return [t["n"] for t in tt]


def _text(tt: list[dict]) -> str:
    """Слова обратно в текст с запятыми, как сказано (заметка, идея): «в январе, но может сдвинуться», а не «в январе но»."""
    return " ".join(t["w"] + ("," if t["comma"] and i < len(tt) - 1 else "") for i, t in enumerate(tt))


# Неопределённая форма: на -ть, -ться, -чь и известные на -ти. Существительные на -ть и -ость — не глаголы.
_TI = frozenset("""идти пойти прийти зайти найти уйти выйти дойти войти сойти обойти пройти перейти подойти отойти
прийти отнести принести занести вынести перенести привезти отвезти увезти вывезти завезти провести вести нести везти
спасти расти""".split())
_NOT_VERB = frozenset("""мать путь сеть часть власть тетрадь печать кровать смерть плоть нить нефть треть четверть
пять шесть девять десять двадцать тридцать память мазь суть ртуть грусть опять пусть хоть чуть ведь будь есть
стать""".split()) - {"стать"}


def is_inf(n: str) -> bool:
    if len(n) < 4 or n in _NOT_VERB or n.endswith("ость"):
        return False
    return n.endswith(("ть", "ться", "тись", "чь", "чься")) or n in _TI


# Глаголы повестки и ожидания первым — не «моё»: «обсудить с…», «спросить…», «жду…» — разберёт модель.
AGENDA_FIRST = frozenset({"обсудить", "спросить", "поднять", "ждать", "дождаться"})
AGENDA_WITH = {"уточнить": "у", "получить": "от", "узнать": "у"}
LEAD_FILLER = ("пожалуйста", "надо", "нужно", "мне", "бы", "что", "чтобы", "давай")
PREPS = frozenset({"в", "во", "на", "к", "ко", "до", "с", "со", "у", "по", "о", "об", "за", "из", "от", "для", "при",
                   "около", "про", "без", "через"})
BAD_EDGE = frozenset({"напомни", "напомнить", "мне", "пожалуйста", "сделать", "надо", "нужно", "давай", "поставь",
                      "поставить", "и", "это", "его", "ее", "уже", "срок"})

TRIGGERS = (("не", "дай", "мне", "забыть"), ("не", "дай", "забыть"), ("пни", "меня"), ("поставь", "напоминание"),
            ("напомни",), ("напомнить",), ("напомните",), ("напоминалка",), ("напоминание",))
# «Напомни про оплату аренды» — без глагола: напоминание с названием «Про оплату аренды» (проверка 16.10.2026: форма
# частая, а уходила модели — 5–10 с и платно).
ABOUT = frozenset({"про", "о", "об", "обо"})
# Глагол приезда — как сказать Саше в строке: «когда буду в Москве» → «Напомню, когда будешь в Москве».
ARRIVE_YOU = {"приеду": "приедешь", "приедем": "приедете", "буду": "будешь", "будем": "будете", "доберусь": "доберёшься",
              "доберемся": "доберётесь", "окажусь": "окажешься", "вернусь": "вернёшься", "вернемся": "вернётесь",
              "появлюсь": "появишься", "приду": "придёшь", "придем": "придёте", "зайду": "зайдёшь"}
ARRIVE = frozenset(ARRIVE_YOU)
ARRIVE_PREPS = frozenset({"на", "в", "во", "до", "к", "ко"})
HOME = frozenset({"домой", "дома", "дом", "домах"})
NOW_WORDS = (("в", "сейчас"), ("первым", "делом"), ("в", "первую", "очередь"))
IDEA_HEAD = (("есть", "идея"), ("запиши", "идею"), ("мысль", "на", "будущее"), ("идея", "на", "будущее"), ("идея",))
IDEA_TO = frozenset({"для", "про", "к", "ко", "по"})  # «идея для Ивана: …» — адрес идеи
NOTE_HEAD = ("заметка", "заметку", "запиши", "запомни")
DONE_HEAD = ("сделано", "готово", "сделал", "сделала")
SPEECH = frozenset({"сказал", "сказала", "говорит", "говорил", "говорила", "считает", "думает", "пишет", "написал",
                    "написала", "обещал", "обещала", "просил", "просила", "хочет", "предложил", "предложила",
                    "рассказал", "рассказала", "жалуется", "сообщил", "сообщила", "сказали", "говорят"})
# Глагол — уже смысл: «Иван просил прислать модель» — ждут от меня, «Иван хочет скидку» — его ожидание, «обещал отчёт» —
# жду от него. Правило с ними молчит всегда, корзину выбирает модель (проверка 16.10.2026).
SPEECH_TASK = frozenset({"обещал", "обещала", "просил", "просила", "хочет", "предложил", "предложила"})
_PAST_BASE = ("позвонил", "перезвонил", "написал", "ответил", "отправил", "скинул", "переслал", "передал", "оплатил",
              "заплатил", "поговорил", "отписал", "набрал")
_PAST_REFL = ("созвонил", "встретил")
DID_PAST = frozenset([b + e for b in _PAST_BASE for e in ("", "а", "и")]
                     + [b + e for b in _PAST_REFL for e in ("ся", "ась", "ись")])
SENT_PAST = frozenset([b + e for b in ("прислал", "отправил", "ответил", "подписал", "оплатил", "скинул", "перевел",
                                        "переслал", "вернул") for e in ("", "а", "и")] + ["перевела"])


# Вложенность (правило 27; поезд 16.10.2026 — вагоны 2 и 4 вместе): «вынь / вынуть … из …», «вложи / положи / переложи
# … в …», «для этого …», «до этого …», «внутри / внутрь …», «это шаг к …», «к делу (про) …», «для дела …», «в рамках …»,
# «это часть …» — правка существующего дела или дело внутри дела, а не новое дело в куче («Вынуть это из сверки» делом
# «потом») и не заметка. Правило с ними молчит: строка веба отдаёт их Claude (ask.run: changes.into, create.step), «Д» —
# модели. Повелительное «вложи / положи / вынь» — команда всегда; неопределённое «вынуть / вложить / переложить /
# засунуть» — только с «из / в / внутрь» дальше (иначе «вынуть» — обычное дело). Родитель по названию голосом — вагон B
# (train\logic\PLAN.md §2).
NEST_SAID = re.compile(
    r"(?:^|[\s,.:;!(«\"])(?:"
    r"(?:вынь|выньте|вложи|вложите|положи|положите|переложи|переложите|засунь|засуньте)"
    r"|(?:вынуть|вложить|переложить|засунуть)(?=.*\s(?:из|в|во|внутрь)\s)"
    r"|внутр[иь]|для\s+этого|до\s+этого|шаг\w*\s+к|к\s+делу|для\s+дела|в\s+рамках|часть\s+(?:того|этого|дела)"
    r")(?=$|[\s,.:;!)»\"])")


def nest_said(text: str) -> bool:
    """Фраза про вложенность (NEST_SAID): правилу захвата её не брать."""
    return bool(NEST_SAID.search(_n(" ".join(str(text or "").split()))))


def one_thought(text: str) -> bool:
    """Одна мысль: одно предложение без «;», «а потом», двух глаголов и вопроса, не длиннее RULE_WORDS_MAX слов."""
    s = " ".join(str(text or "").split()).rstrip(".!")
    if not s or len(s.split()) > RULE_WORDS_MAX:
        return False
    if re.search(r"[;?\n]", s) or re.search(r"(?<!\d)[.!](?!\d)", s):
        return False
    low = _n(s)
    if re.search(r"(?:^|[\s,])(?:а потом|и потом|а еще|и еще|а также|плюс ко всему)(?:$|[\s,])", low):
        return False
    tt = tokens(s)
    # Слова «напомнить», «не дай забыть» — не глагол дела (проверка 16.10.2026: «не дай мне забыть купить подарок Ольге»
    # считалось двумя мыслями, и правило его не узнавало никогда).
    trig = _find_trigger(tt)
    skip = set(range(*trig)) if trig else set()
    verbs = [i for i, t in enumerate(tt) if i not in skip and is_inf(t["n"])
             and not (i > 0 and tt[i - 1]["n"] in ("чтобы", "что"))]
    return len(verbs) <= 1


# ── Справочник одной фразы: люди и клиенты ──────────────────────────────


class Ctx:
    """Что нужно правилу и раскладке на одну фразу: кто говорит, сейчас, люди (одно чтение who_rows), клиенты, места."""

    def __init__(self, conn, user: str, now: dt.datetime):
        self.conn, self.user = conn, user
        self.now = now.astimezone(MSK)
        self.today = self.now.date()
        self._rows = None
        self._projects = None
        self._places = None
        self._digest = None
        self._who: dict[str, dict] = {}

    @property
    def rows(self):
        if self._rows is None:
            self._rows = people.who_rows(self.conn)
            self._name_stems = {people.stem(w) for p in self._rows
                                for n in [p["name"], p["short"] or "", *(p["aliases"] or [])] for w in people.words(n)}
        return self._rows

    @property
    def places(self) -> list[str]:
        if self._places is None:
            self._places = remind.places_of(self.conn, self.user)
        return self._places

    def who(self, q: str) -> dict:
        if q not in self._who:
            self._who[q] = people.who(self.conn, q=q, limit=8, rows=self.rows)
        return self._who[q]

    def maybe_name(self, n: str) -> bool:
        """Слово может быть именем: основа есть среди имён справочника или это имя вообще (дёшево, до who)."""
        _ = self.rows
        return len(n) >= 2 and (people.stem(n) in self._name_stems or people.is_first(n))

    def person(self, words: list[str]) -> dict | None:
        """Человек по слову или паре слов: {"person": {id, name, short}} — уверенно; {"twins": [...]} — тёзки (двое и
        больше рядом); None — не человек."""
        if not words or not all(self.maybe_name(w) for w in words[:1]):
            return None
        w = self.who(" ".join(words))
        if w["sure"] and w["best"] and w["best"]["score"] >= people.SURE:
            b = w["best"]
            return {"person": {"id": str(b["id"]), "name": b["name"], "short": b.get("short") or b["name"]}}
        c = [x for x in w["candidates"] if x["score"] >= 0.6]
        close = [x for x in c if c and c[0]["score"] - x["score"] < people.GAP]
        if len(close) >= 2:
            return {"twins": [{"id": str(x["id"]), "name": x["name"], "short": x.get("short") or x["name"],
                               "org": x.get("org")} for x in close[:3]]}
        return None

    def find_person(self, tt: list[dict]) -> tuple[dict | None, int, int]:
        """Первый узнанный человек в словах: (итог, с, по). Пара слов («Ивану Тестову») сильнее одного."""
        for i in range(len(tt)):
            n = tt[i]["n"]
            if not self.maybe_name(n):
                continue
            if i + 1 < len(tt) and not tt[i]["comma"]:
                got = self.person([n, tt[i + 1]["n"]])
                if got and "person" in got:
                    return got, i, i + 2
            got = self.person([n])
            if got:
                return got, i, i + 1
        return None, -1, -1

    @property
    def projects(self) -> list[dict]:
        if self._projects is None:
            self._projects = self.conn.execute(
                "SELECT id, name, aliases, kind FROM crm.projects WHERE archived_at IS NULL ORDER BY name, id").fetchall()
        return self._projects

    def project(self, words: list[str], only: str | None = None) -> dict | None:
        """Клиент или проект по основе слова («Ромашке» = «ООО Ромашка»): ровно один живой — он, иначе None."""
        hit: dict[str, dict] = {}
        for p in self.projects:
            if only and p["kind"] != only:
                continue
            keys = {k for n in [p["name"], *(p["aliases"] or [])] for k in people.words(n)
                    if k not in people.GENERIC and len(k) >= 3}
            if any(people.same(w, k) for w in words if len(w) >= 3 for k in keys):
                hit[str(p["id"])] = p
        return next(iter(hit.values())) if len(hit) == 1 else None

    @property
    def digest(self) -> dict:
        if self._digest is None:
            from . import digest_crm

            self._digest = digest_crm.load(self.conn, self.user)
        return self._digest

    def client_of(self, person_id: str) -> str | None:
        """Единственный клиент человека — одна правда с дайджестом и звонками (digest_crm._person_clients)."""
        from . import digest_crm

        got = digest_crm._person_clients(self.digest, str(person_id))
        return got[0] if len(got) == 1 else None


# ── Правило ─────────────────────────────────────────────────────────────


def _cap(s: str) -> str:
    s = s.strip()
    return s[:1].upper() + s[1:] if s else s


def _lowfirst(title: str, ctx: Ctx | None = None) -> str:
    """«Что» в строке — с маленькой буквы, если первое слово не имя («Иван: прислать…» и «Ромашке…» — как есть)."""
    t = str(title or "").strip()
    if not t:
        return t
    head = t.split()[0].strip(_EDGE)
    if (re.match(r"^[^:]{1,40}:", t) or (ctx and (ctx.maybe_name(_n(head)) or ctx.project([_n(head)]))) or head.isupper()
            or (head[:1].isupper() and people.is_first(_n(head)))):  # «Иван хочет скидку» — имя с большой и без справочника
        return t
    return t[:1].lower() + t[1:]


def _strip_lead(tt: list[dict]) -> list[dict]:
    """«мне», «надо», «что», «чтобы», «не забыть», «пожалуйста» в начале — не слова дела."""
    while tt:
        if tt[0]["n"] in LEAD_FILLER:
            tt = tt[1:]
        elif len(tt) > 1 and tt[0]["n"] == "не" and tt[1]["n"] == "забыть":
            tt = tt[2:]
        else:
            break
    return tt


def _verb_start(tt: list[dict]) -> int | None:
    """Где глагол дела: 0 — первым словом; 2–3 — после короткого оборота с предлогом («в Контуре отправить»)."""
    if not tt:
        return None
    if is_inf(tt[0]["n"]):
        return 0
    if tt[0]["n"] in PREPS:
        for k in (2, 3):
            if k < len(tt) and is_inf(tt[k]["n"]) and not any(t["comma"] for t in tt[:k]):
                return k
    return None


def _title(tt: list[dict]) -> str:
    """Название — слова Саши: оборот с предлогом перед глаголом — за глагол («в Контуре отправить счёт» → «Отправить
    счёт в Контуре»), первая буква заглавная."""
    tt = _strip_lead(list(tt))
    k = _verb_start(tt)
    if k:
        tt = tt[k:] + tt[:k]
    return _cap(" ".join(t["w"] for t in tt))


def _agenda(tt: list[dict]) -> bool:
    if not tt:
        return False
    n = tt[0]["n"]
    if n in AGENDA_FIRST:
        return True
    return n in AGENDA_WITH and len(tt) > 1 and tt[1]["n"] == AGENDA_WITH[n]


def _match(tt: list[dict], i: int, seq: tuple) -> bool:
    return tuple(t["n"] for t in tt[i:i + len(seq)]) == seq


def _when_try(words: list[str], now: dt.datetime) -> dict | None:
    """Кусок фразы — «когда» (свой разбор when.local, без модели и без мест). Не кончается предлогом и пустым словом:
    «в 12 в» — не время, а «в 12» и висячее «в» от «в Контуре»."""
    if not words or words[-1] in PREPS or words[-1] in BAD_EDGE or words[0] in BAD_EDGE - {"мне"}:
        return None
    try:
        got = when.local(" ".join(words), now, [], False)
    except when.WhenError:
        return None
    if not got:
        return None
    s = got["set"]
    if s.get("remind_place") or not (s.get("due_date") or s.get("remind_at")):
        return None
    return got


def _when_edges(tt: list[dict], lo: int, hi: int, now: dt.datetime, at_start: bool = True,
                at_end: bool = True) -> list[tuple[int, int, dict]]:
    """Самые длинные куски «когда» в начале и в конце отрезка [lo, hi): [(с, по, разбор)]."""
    found = []
    if at_start:
        for k in range(min(7, hi - lo), 0, -1):
            if any(t["comma"] for t in tt[lo:lo + k - 1]):
                continue
            got = _when_try(_words_of(tt[lo:lo + k]), now)
            if got:
                found.append((lo, lo + k, got))
                break
    if at_end:
        for k in range(min(7, hi - lo), 0, -1):
            a = hi - k
            if found and a < found[0][1]:
                continue
            if any(t["comma"] for t in tt[a:hi - 1]):
                continue
            got = _when_try(_words_of(tt[a:hi]), now)
            if got:
                found.append((a, hi, got))
                break
    return found


def _remind_at(got: dict | None, now: dt.datetime) -> dt.datetime:
    """Миг напоминания из «когда»: время — оно; срок «к 15:00» — его час; только день — 9:00 (сегодня — как без
    времени); ничего — сегодня 19:00, после 18:00 — завтра 9:00."""
    today = now.date()
    if got:
        s = got["set"]
        if s.get("remind_at"):
            return remind.at_of(s["remind_at"])
        d = dt.date.fromisoformat(s["due_date"]) if s.get("due_date") else None
        if d and s.get("due_time"):
            h, m = map(int, s["due_time"].split(":"))
            return dt.datetime(d.year, d.month, d.day, h, m, tzinfo=MSK)
        if d and d != today:
            return dt.datetime(d.year, d.month, d.day, *MORNING, tzinfo=MSK)
    if now.hour < LATE_HOUR:
        return dt.datetime(today.year, today.month, today.day, *DEFAULT_EVENING, tzinfo=MSK)
    if got:  # «напомни сегодня …» вечером — сегодня же, через час
        return (now + dt.timedelta(hours=1)).replace(second=0, microsecond=0)
    d = today + dt.timedelta(days=1)
    return dt.datetime(d.year, d.month, d.day, *MORNING, tzinfo=MSK)


def _nominative(word: str, prep: str | None) -> str:
    """Место, как его сказали («на дачу», «на даче», «в офисе»), — именем места («дача», «офис»)."""
    w = _n(word)
    if w in HOME:
        return "дом"
    if w.endswith("у"):
        return w[:-1] + "а"
    if w.endswith("ю"):
        return w[:-1] + "я"
    if w.endswith("е") and len(w) > 3:
        return w[:-1] + "а" if prep == "на" else w[:-1]
    return w


def _place_words(place: str, prep: str | None) -> str:
    """«домой», «на дачу», «в офис» — куда приедешь, для строки."""
    p = _n(place)
    if p in HOME or remind.is_home(place):
        return "домой"
    acc = p[:-1] + "у" if p.endswith("а") else p[:-1] + "ю" if p.endswith("я") else p
    return f"{prep or 'в'} {acc}"


def _find_place(tt: list[dict], places: list[str], after_trigger: int | None) -> dict | None:
    """«когда приеду домой», «как буду на даче», «по приезде в офис», «напомни дома …» — где и как сказано.
    Кусок — в начале фразы, после запятой или после «напомни»; кончается концом фразы, запятой или «напомни».
    После глагола приезда место — только «домой» / «дома» или предлог и слово: «когда буду свободен», «когда освобожусь»
    — не место, правило молчит, разбирает модель (проверка 16.10.2026: «свободен» ложилось местом напоминания)."""
    starts = {0} | {i + 1 for i, t in enumerate(tt) if t["comma"]} | ({after_trigger} if after_trigger is not None else set())
    for i in sorted(starts):
        j = i
        if j < len(tt) and tt[j]["n"] in ("когда", "как"):
            j += 1
            if j < len(tt) and tt[j]["n"] == "только":
                j += 1
        verb = None
        if j < len(tt) and tt[j]["n"] in ARRIVE:
            verb = ARRIVE_YOU[tt[j]["n"]]
            j += 1
        elif j + 1 < len(tt) and tt[j]["n"] == "по" and tt[j + 1]["n"] in ("приезде", "приезду"):
            j += 2
        elif i == after_trigger and j < len(tt):
            # «напомни дома …», «напомни на даче …» — только «дома» или место, которое телефон знает.
            if tt[j]["n"] in HOME:
                return {"start": i, "end": j + 1, "word": tt[j]["w"], "prep": None, "known": True}
            if tt[j]["n"] in ARRIVE_PREPS and j + 1 < len(tt):
                named = remind.match_place(_nominative(tt[j + 1]["w"], tt[j]["n"]), places)
                if named:
                    return {"start": i, "end": j + 2, "word": tt[j + 1]["w"], "prep": tt[j]["n"], "known": True}
            continue
        else:
            continue
        if j < len(tt) and tt[j]["n"] in HOME:
            return {"start": i, "end": j + 1, "word": tt[j]["w"], "prep": None, "verb": verb}
        if j + 1 < len(tt) and tt[j]["n"] in ARRIVE_PREPS and not tt[j]["comma"]:
            return {"start": i, "end": j + 2, "word": tt[j + 1]["w"], "prep": tt[j]["n"], "verb": verb}
    return None


def _find_trigger(tt: list[dict]) -> tuple[int, int] | None:
    """«напомни», «не дай забыть», «пни меня» — в начале фразы или после запятой; (с, по) вместе с «мне»."""
    for i in range(len(tt)):
        if i and not tt[i - 1]["comma"]:
            continue
        for tr in TRIGGERS:
            if _match(tt, i, tr):
                j = i + len(tr)
                if j < len(tt) and tt[j]["n"] == "мне":
                    j += 1
                return i, j
    return None


def _rest_ok(tt: list[dict]) -> bool:
    """После «напомни» и «когда» — дело с глагола (или с короткого оборота перед ним); «напомни Наташе …» — не нам."""
    tt = _strip_lead(list(tt))
    return bool(tt) and _verb_start(tt) is not None and not _agenda(tt)


def _about(tt: list[dict]) -> str | None:
    """«(напомни) про оплату аренды» — без глагола: название напоминания «Про оплату аренды». Иначе None."""
    tt = _strip_lead(list(tt))
    if len(tt) < 2 or tt[0]["n"] not in ABOUT or any(is_inf(t["n"]) for t in tt):
        return None
    return _cap(_text(tt))


def _remind_title(rest: list[dict]) -> str | None:
    """Название напоминания: дело с глагола или «про X»; иначе — не узнало (None)."""
    if _rest_ok(rest):
        return _title(rest)
    return _about(rest)


def _rule_remind(tt: list[dict], ctx: Ctx) -> dict | None:
    """Н1 «напомни + когда» и Н2 «напомни + место»."""
    trig = _find_trigger(tt)
    place = _find_place(tt, ctx.places, trig[1] if trig else None)
    if not trig and not place:
        return None
    skip: set[int] = set(range(*trig)) if trig else set()
    if place:
        skip |= set(range(place["start"], place["end"]))
        rest = [t for i, t in enumerate(tt) if i not in skip]
        title = _remind_title(rest)
        if not title:
            return None
        word, prep = place["word"], place["prep"]
        named = (remind.match_place(_nominative(word, prep), ctx.places) or remind.match_place(_n(word), ctx.places)
                 or (remind.match_place("дом", ctx.places) if _n(word) in HOME else ""))
        if named:
            item = {"bucket": "place", "title": title, "fields": {"kind": "remind", "remind_place": named},
                    "place_words": _place_words(named, prep)}
        else:
            # Места нет у телефона — как сказано («к маме», «в Москве»), без выдуманного именительного падежа (проверка
            # 16.10.2026: «когда приеду к маме» давало место «мам», «в Москве» — «москв»), и честно: телефон его не знает.
            said = f"{prep} {word}" if prep else word
            if _n(word) not in HOME and ctx.maybe_name(_n(word)):
                return None  # «когда приеду к Ивану» — человек, а не место: модели
            item = {"bucket": "place", "title": title, "fields": {"kind": "remind", "remind_place": said},
                    "place_words": said, "place_verb": place.get("verb") or "приедешь",
                    "warn": f"место «{said}» телефон не знает: добавь его в автопилоте"}
        # Человек и клиент — как у дела (сквозной тест 15.10.2026: «… договор для Ромашки» ложилось без клиента, а
        # «… отправить Ивану договор» — без Ивана, и «отправил договор Ивану» потом не гасило напоминание).
        return _task_people(item, rest, ctx)
    lo, hi = trig
    after = _when_edges(tt, hi, len(tt), ctx.now, at_start=True, at_end=True)
    before = _when_edges(tt, 0, lo, ctx.now, at_start=True, at_end=False) if lo > 0 else []
    chunks = after + before
    got = None
    if len(chunks) >= 2:
        merged = sorted(chunks, key=lambda c: c[0])[:2]
        both = _when_try([t["n"] for a, b, _ in merged for t in tt[a:b]], ctx.now)
        got = both or max(chunks, key=lambda c: c[1] - c[0])[2]
        used = merged if both else [max(chunks, key=lambda c: c[1] - c[0])]
    elif chunks:
        got, used = chunks[0][2], chunks[:1]
    else:
        used = []
    for a, b, _ in used:
        skip |= set(range(a, b))
    rest = [t for i, t in enumerate(tt) if i not in skip]
    title = _remind_title(rest)
    if not title:
        return None
    at = _remind_at(got, ctx.now)
    return _task_people({"bucket": "remind", "title": title, "fields": {"kind": "remind", "remind_at": remind.iso(at)}},
                        rest, ctx)


def _rule_idea(tt: list[dict], ctx: Ctx) -> dict | None:
    """И: «идея: …», «есть идея …», «мысль на будущее …» — идея у клиента и человека из слов. И «Ромашка: идея — …»
    («Иван: идея …»): кому — первыми словами до двоеточия или тире (сквозной тест 15.10.2026: эта форма уходила модели,
    5–6 с вместо доли секунды)."""
    to: list[dict] = []
    for start in [0] + [k + 1 for k in range(min(3, len(tt) - 1)) if tt[k]["comma"]][:1]:
        head = next((h for h in IDEA_HEAD if _match(tt, start, h)), None)
        if head:
            to, rest = tt[:start], tt[start + len(head):]
            break
    else:
        return None
    text = _text(rest).strip(" ,:-—")
    if not text:
        return None
    if to:
        # «X:» — клиент или человек; иначе это не адрес идеи, а начало мысли — модели.
        got = ctx.person(_words_of(to)[:2])
        proj = None if got and "person" in got else ctx.project(_words_of(to))
        if not proj and not (got and "person" in got):
            return None
        item = {"bucket": "idea", "text": text}
        if proj:
            item["project_id"] = str(proj["id"])
        else:
            item["person_id"] = got["person"]["id"]
            client = ctx.client_of(got["person"]["id"])
            if client:
                item["project_id"] = client
        return item
    # «идея для Ивана: показать модель», «идея по Ромашке — дашборд»: адрес — предлог и одно-два слова до двоеточия или
    # тире; узнан — снимаем его с текста (проверка 16.10.2026: текст выходил «для Ивана, показать модель»).
    if len(rest) >= 3 and rest[0]["n"] in IDEA_TO:
        k = next((k for k in (1, 2) if k < len(rest) - 1 and rest[k]["comma"]), None)
        if k:
            named = _words_of(rest[1:k + 1])
            got = ctx.person(named[:2])
            proj = None if got and "person" in got else ctx.project(named)
            body = _text(rest[k + 1:]).strip(" ,:-—")
            if body and (proj or (got and "person" in got)):
                item = {"bucket": "idea", "text": body}
                if proj:
                    item["project_id"] = str(proj["id"])
                else:
                    item["person_id"] = got["person"]["id"]
                    client = ctx.client_of(got["person"]["id"])
                    if client:
                        item["project_id"] = client
                return item
    words = _words_of(rest)
    proj = ctx.project(words)
    who, _, _ = ctx.find_person(rest)
    item = {"bucket": "idea", "text": text}
    if proj:
        item["project_id"] = str(proj["id"])
    if who and "person" in who:
        item["person_id"] = who["person"]["id"]
    return item


def _rule_note(tt: list[dict], ctx: Ctx) -> dict | None:
    """З2: «заметка по X: …» (и «запиши про X …»); X — человек или клиент."""
    if not tt or tt[0]["n"] not in NOTE_HEAD or len(tt) < 3 or tt[1]["n"] not in ("по", "про", "к", "для", "о", "об"):
        return None
    # X — до двоеточия (или запятой), не больше трёх слов.
    end = next((i for i in range(2, min(len(tt), 6)) if tt[i]["comma"]), None)
    if end is None:
        return None
    named = tt[2:end + 1]
    text = _text(tt[end + 1:]).strip(" ,:-—")
    if not text:
        return None
    item = {"bucket": "note", "text": _cap(text)}
    got = ctx.person(_words_of(named)[:2])
    if got and "person" in got:
        item.update(person_id=got["person"]["id"], person_said=" ".join(t["w"] for t in named))
        client = ctx.client_of(got["person"]["id"])
        if client:
            item["project_id"] = client
        return item
    if got and "twins" in got:
        item.update(twins=got["twins"], person_said=" ".join(t["w"] for t in named))
        return item
    proj = ctx.project(_words_of(named))
    if proj:
        item["project_id"] = str(proj["id"])
        return item
    return None


def _person_head(tt: list[dict], ctx: Ctx) -> tuple[dict | None, int]:
    """Человек первыми словами: (итог, сколько слов)."""
    if len(tt) >= 2:
        got = ctx.person(_words_of(tt[:2]))
        if got and "person" in got and not tt[0]["comma"]:
            return got, 2
    got = ctx.person(_words_of(tt[:1]))
    return (got, 1) if got else (None, 0)


def _task_clause(body: list[dict]) -> bool:
    """В словах человека — дело: часть фразы (начало или после запятой) начинается с глагола, хоть после «надо»
    («Ольга говорит, подпишут в среду, надо подготовить счёт»; «Иван сказал подготовить счёт»). Это факт и дело разом —
    модели (сквозной тест 15.10.2026: правило клало всю фразу заметкой, и счёт терялся)."""
    starts = [0] + [j + 1 for j, t in enumerate(body[:-1]) if t["comma"]]
    for j in starts:
        k = j
        while k < len(body) and body[k]["n"] in LEAD_FILLER:
            k += 1
        if k < len(body) and is_inf(body[k]["n"]):
            return True
    return False


def _rule_said(tt: list[dict], ctx: Ctx) -> dict | None:
    """З1 «Иван сказал, …» — заметка у человека; С2 «Иван прислал сверку» — сделано, ждал от него."""
    got, k = _person_head(tt, ctx)
    if not got or k >= len(tt):
        return None
    v = tt[k]["n"]
    said = " ".join(t["w"] for t in tt[:k])
    rest = tt[k + 1:]
    if v in SPEECH_TASK:
        # «Иван хочет скидку», «Иван обещал отчёт», «Ольга просила скидку» — по смыслу «жду от Ивана» или «ждёт от тебя»,
        # а не заметка; без глагола правило клало «Заметка у Ивана: скидку» (проверка 16.10.2026). Решает модель.
        return None
    if v in SPEECH:
        if v in SENT_PAST and not (tt[k]["comma"] or (rest and rest[0]["n"] == "что")):
            pass  # «Иван написал письмо» — не речь; ниже — как «прислал»
        else:
            body = rest[1:] if rest and rest[0]["n"] == "что" else rest
            if not body or _task_clause(body):
                return None
            text = _text(body).strip(" ,:-—")
            item = {"bucket": "note", "text": text, "summary": f"{_cap(said)}: {text}", "person_said": said}
            if "person" in got:
                item["person_id"] = got["person"]["id"]
                client = ctx.client_of(got["person"]["id"])
                if client:
                    item["project_id"] = client
            else:
                item["twins"] = got["twins"]
            return item
    if v in SENT_PAST and "person" in got and rest and rest[0]["n"] != "что":
        title = f"{_cap(said)}: {tt[k]['w'].lower()} " + " ".join(t["w"] for t in rest)
        return {"bucket": "did", "title": title.strip(), "said": " ".join(t["w"] for t in tt),
                "fields": {"ball": "waiting", "person_id": got["person"]["id"]}}
    return None


def _rule_did(tt: list[dict], ctx: Ctx) -> dict | None:
    """С1: «позвонил Ольге», «созвонился с Иваном по модели», «сделано, отправил договор» — сделал я."""
    prefixed = bool(tt) and tt[0]["n"] in DONE_HEAD and tt[0]["comma"]
    body = tt[1:] if prefixed else tt
    if not body or body[0]["n"] not in DID_PAST:
        if prefixed and body and _verb_start(body) is None:
            # «сделано, договор Ромашке» — без глагола: сделанным как сказано.
            return {"bucket": "did", "title": _cap(" ".join(t["w"] for t in body)), "said": " ".join(t["w"] for t in body),
                    "fields": {"ball": "mine"}}
        return None
    rest = body[1:]
    if rest and rest[0]["n"] in ("с", "со"):
        rest = rest[1:]
    # Дальше — только человек (одно-два слова) и «по <теме>»; иначе (без «сделано,») — модель.
    po = next((i for i, t in enumerate(rest) if t["n"] == "по"), len(rest))
    who_words = rest[:po]
    if not prefixed and not (1 <= len(who_words) <= 2):
        return None
    got = ctx.person(_words_of(who_words)[:2]) if who_words else None
    if not got and who_words:
        # «отправил договор Ивану» — предмет, потом человек (сквозной тест 15.10.2026: человек терялся, и обещание
        # «Отправить договор Ивану» оставалось открытым).
        got, _, _ = ctx.find_person(who_words)
    item = {"bucket": "did", "title": _cap(" ".join(t["w"] for t in body)), "said": " ".join(t["w"] for t in body),
            "fields": {"ball": "mine"}}
    if got and "person" in got:
        item["fields"]["person_id"] = got["person"]["id"]
    elif got and "twins" in got:
        item["twins"] = got["twins"]
    return item


def _back_words(tt: list[dict]) -> tuple[str, int, int] | None:
    """«потом», «на неделе», «через месяц», «в январе» — в начале или в конце: (как сказано, с, по)."""
    seqs = [tuple(s.split()) for s in BACK_SOON + BACK_WEEK + ("через месяц",)] + [("в", m) for m in MONTHS_LOC]
    seqs += [("когда-нибудь",)]
    for seq in sorted(seqs, key=len, reverse=True):
        if _match(tt, 0, seq):
            return " ".join(seq), 0, len(seq)
        a = len(tt) - len(seq)
        if a > 0 and _match(tt, a, seq):
            return " ".join(seq), a, len(tt)
    return None


def _now_words(tt: list[dict]) -> tuple[int, int] | None:
    for seq in NOW_WORDS:
        if _match(tt, 0, seq):
            return 0, len(seq)
        a = len(tt) - len(seq)
        if a > 0 and _match(tt, a, seq):
            return a, len(tt)
    return None


def _task_people(item: dict, rest: list[dict], ctx: Ctx) -> dict:
    """Человек и клиент дела по словам — только уверенно; тёзки — дело без человека (spec-0 §11, п. 6)."""
    who, _, _ = ctx.find_person(rest)
    if who and "person" in who:
        item["fields"]["person_id"] = who["person"]["id"]
    proj = ctx.project(_words_of(rest), only="client")
    if proj:
        item["fields"]["project_id"] = str(proj["id"])
    return item


def _rule_task(tt: list[dict], ctx: Ctx) -> dict | None:
    """П «… потом / на неделе», Д «сегодня … / в сейчас … / в пятницу …», Г голое «купить магний» — дело с глагола."""
    back = _back_words(tt)
    if back:
        said, a, b = back
        rest = tt[:a] + tt[b:]
        rest = _strip_lead(rest)
        if not rest or _verb_start(rest) != 0 or _agenda(rest):
            return None
        d = default_back(ctx.today, said)
        return _task_people({"bucket": "back", "title": _title(rest),
                             "fields": {"back_on": d.isoformat(), "back_said": said}}, rest, ctx)
    nw = _now_words(tt)
    if nw:
        rest = _strip_lead(tt[:nw[0]] + tt[nw[1]:])
        if not rest or _verb_start(rest) != 0 or _agenda(rest):
            return None
        return _task_people({"bucket": "now", "form": "Д", "title": _title(rest),
                             "fields": {"focus_on": ctx.today.isoformat()}}, rest, ctx)
    base = _strip_lead(list(tt))
    chunks = _when_edges(base, 0, len(base), ctx.now)
    if chunks:
        a, b, got = max(chunks, key=lambda c: c[1] - c[0])
        rest = base[:a] + base[b:]
        if not rest or _verb_start(rest) != 0 or _agenda(rest):
            return None
        s = got["set"]
        due = s.get("due_date") or (remind.at_of(s["remind_at"]).date().isoformat() if s.get("remind_at") else None)
        if not due:
            return None
        fields = {"due_date": due}
        if s.get("due_time") and not got.get("window"):
            fields["due_time"] = s["due_time"]
        item = {"bucket": "today" if due == ctx.today.isoformat() else "day", "form": "Д", "title": _title(rest),
                "fields": fields}
        if got.get("by_deadline"):
            item["by_deadline"] = True
        return _task_people(item, rest, ctx)
    if len(base) > BARE_WORDS_MAX or not base or _verb_start(base) != 0 or _agenda(base):
        return None
    return _task_people({"bucket": "back", "form": "Г", "title": _title(base),
                         "fields": {"back_on": next_workday(ctx.today).isoformat()}}, base, ctx)


def rule(conn, user: str, text: str, now: dt.datetime | None = None, ctx: Ctx | None = None) -> dict | None:
    """Правило ($0): одна мысль известной формы — {"form", "items": [одна корзина]}; иначе None (молчит — модели)."""
    s = " ".join(str(text or "").split())
    if not s or not one_thought(s) or nest_said(s):
        return None
    ctx = ctx or Ctx(conn, user, now or dt.datetime.now(MSK))
    tt = tokens(s)
    if not tt:
        return None
    for form, fn in (("И", _rule_idea), ("З2", _rule_note), ("Н", _rule_remind), ("С1", _rule_did),
                     ("З1", _rule_said), ("П", _rule_task)):
        got = fn(tt, ctx)
        if got:
            if form == "З1" and got["bucket"] == "did":
                form = "С2"  # «Иван прислал сверку» — сделано, ждал от него
            return {"form": got.pop("form", form), "items": [got]}
    return None


# ── Раскладка: корзина → операции, строка, кнопки ───────────────────────


TASK_BUCKETS = ("remind", "place", "now", "today", "day", "back", "task_remind", "waiting", "owed", "agenda")
MAX_LINE = 160


def _short(s: str, n: int = 80) -> str:
    s = " ".join(str(s or "").split())
    return s if len(s) <= n else s[: n - 1] + "…"


def _person_row(conn, pid) -> dict | None:
    if not pid:
        return None
    return conn.execute("SELECT id, name, short FROM crm.people WHERE id = %s", (str(pid),)).fetchone()


def _project_row(conn, pid) -> dict | None:
    if not pid:
        return None
    return conn.execute("SELECT id, name, kind FROM crm.projects WHERE id = %s", (str(pid),)).fetchone()


_PERSON_PREPS = frozenset({"для", "у", "к", "ко", "с", "со", "от"})


def _without(body: str, p: dict | None) -> str:
    """Строка «Ждёт от тебя Иван Тестов: прислать Ивану модель» → «… : прислать модель»: человек уже назван в начале
    строки (сквозной тест 15.10.2026: «Ждёт от тебя Ольга …: подготовить договор для Ольги»). Только строка — название
    дела остаётся, как сказано. Ничего не осталось — как было."""
    if not p:
        return body
    keys = [k for k in people.words(f"{p['name']} {p.get('short') or ''}") if len(k) >= 3]
    words, out = body.split(), []
    for w in words:
        if any(people.same(_n(w.strip(_EDGE)), k) for k in keys):
            if out and _n(out[-1]) in _PERSON_PREPS:
                out.pop()
            continue
        out.append(w)
    return " ".join(out) if out else body


def _twin_short(item: dict) -> str:
    """Короткое имя тёзок («Иван») — для вопроса «какой Иван?»."""
    tw = item.get("twins") or []
    if not tw:
        return ""
    return str(tw[0].get("short") or str(tw[0].get("name") or "").split()[0] or "")


def _who_word(short: str) -> str:
    """«какой Иван», «какая Ольга» — именительным: падеж фразы («Ивану», «по Ивану») в вопросе не нужен."""
    return ("какая " if _gender(short) == "f" else "какой ") + short


def _who_of(short: str) -> str:
    """«у какого Ивана», «у какой Ольги»."""
    return ("у какой " if _gender(short) == "f" else "у какого ") + genitive(short)


def _has_person(item: dict) -> bool:
    return bool(item.get("person_id") or (item.get("fields") or {}).get("person_id"))


def _remind_what(conn, t: dict, what: str) -> str:
    """Что напомнить — без двойного двоеточия (проверка 16.10.2026: «Напомню, когда приедешь в офис: Наташа: прислать
    сверку»): напоминание о «жду» — «жду от Наташи Тестовой — прислать сверку», об «отбить» — «ждёт Наташа Тестова — …»,
    иначе «Наташа — прислать сверку». Без «Кто:» — как есть."""
    m = re.match(r"^([^:]{1,40}):\s*(.+)$", str(t.get("title") or ""))
    if not m:
        return what
    who, body = m.group(1).strip(), _lowfirst(m.group(2).strip())
    p = _person_row(conn, t.get("person_id"))
    if t.get("ball") == "waiting":
        return _short(f"жду от {genitive(p['name'] if p else who)} — {_without(body, p)}", 110)
    if t.get("owed"):
        return _short(f"ждёт {p['name'] if p else who} — {_without(body, p)}", 110)
    return _short(f"{who} — {body}", 110)


def line_of(conn, item: dict, now: dt.datetime) -> str:
    """Строка подтверждения корзины (spec-0 §2.5): «Напомню сегодня в 12:00: отправить счёт в Контуре». Обещает только
    то, что правда случится (проверка 16.10.2026): заметка — «в карточке» (у встречи телефон показывает только идеи),
    идея без адреса — «в списке «Идеи»», вопрос о похожих и тёзках — «в «Подскажи»» (он встаёт туда сразу)."""
    today = now.date()
    b = item["bucket"]
    t = item.get("task") or {}
    what = _short(_lowfirst(t.get("title") or item.get("title") or item.get("text") or ""), 100)
    if b == "remind":
        return f"Напомню {when_say(remind.at_of(t['remind_at']), now)}: {_remind_what(conn, t, what)}"
    if b == "place":
        where = item.get("place_words") or _place_words(t.get("remind_place") or "", None)
        out = f"Напомню, когда {item.get('place_verb') or 'приедешь'} {where}: {_remind_what(conn, t, what)}"
        return out + (f" — но {item['warn']}" if item.get("warn") else "")
    if b == "now":
        if item.get("now_full"):
            return f"В «Сейчас» места нет — на сегодня: {what}"
        return f"В «Сейчас» ({item.get('now_n') or 1} из 5): {what}"
    if b in ("today", "day"):
        d = dt.date.fromisoformat(str(t.get("due_date"))[:10])
        tail = ""
        if t.get("due_time"):
            tail = f" · {'к' if item.get('by_deadline') else 'в'} {str(t['due_time'])[:5].lstrip('0') or '0:00'}"
        # «Жду» и «Отбить» со сроком — чьё дело, видно в строке (сквозной тест 15.10.2026: «На завтра: Иван: прислать
        # отчёт» читалось как своё дело на завтра).
        p = _person_row(conn, t.get("person_id")) if (t.get("ball") == "waiting" or t.get("owed")) else None
        if p:
            body = _without(_lowfirst(re.sub(r"^[^:]{1,40}:\s*", "", t.get("title") or "")), p)
            day = day_on(d, today)
            day = day[0].lower() + day[1:]
            if t.get("ball") == "waiting":
                return f"Жду от {genitive(p['name'])}: {body} · {day}{tail}"
            return f"Ждёт от тебя {p['name']}: {body} · {day}{tail}"
        return f"{day_on(d, today)}: {what}{tail}"
    if b == "back":
        d = dt.date.fromisoformat(str(t.get("back_on"))[:10])
        return f"Потом: {what} · {back_say(d, today)}"
    if b == "task_remind":
        d = dt.date.fromisoformat(str(t.get("due_date"))[:10])
        at = remind.at_of(t["remind_at"]) if t.get("remind_at") else None
        return f"Дело {day_on(d, today)[0].lower() + day_on(d, today)[1:]}: {what}" + (f" · напомню {when_say(at, now)}" if at else "")
    if b in ("waiting", "owed", "agenda"):
        p = _person_row(conn, t.get("person_id"))
        name = p["name"] if p else ""
        body = _without(re.sub(r"^[^:]{1,40}:\s*", "", t.get("title") or ""), p)
        if b == "waiting":
            return f"Жду от {genitive(name)}: {_lowfirst(body)} · неделя без ответа — на виду" if name else f"Жду: {what}"
        if b == "owed":
            return f"Ждёт от тебя {name}: {_lowfirst(body)} · в «Отбить»" if name else f"Ждут от тебя: {what} · в «Отбить»"
        return f"При встрече с {instrumental(name)}: {_lowfirst(body)}" if name else f"При встрече: {what}"
    if b == "note":
        text = _short(item.get("text") or "", 100)
        if item.get("twins") and not item.get("person_id"):
            return f"Заметка: {_lowfirst(text)} — {_who_of(_twin_short(item))}? · вопрос в «Подскажи»"
        p = _person_row(conn, item.get("person_id"))
        pr = _project_row(conn, item.get("project_id"))
        to = genitive(p["name"]) if p else (pr["name"] if pr else "")
        return (f"Заметка у {to}: {_lowfirst(text)} · в карточке" if to
                else f"Заметка: {_lowfirst(text)} · ни к кому — не понял чья")
    if b == "idea":
        text = _short(item.get("text") or "", 100)
        pr = _project_row(conn, item.get("project_id"))
        p = _person_row(conn, item.get("person_id"))
        to = pr["name"] if pr else genitive(p["name"]) if p else ""
        return (f"Идея у {to}: {_lowfirst(text)} · покажу перед встречей" if to
                else f"Идея: {_lowfirst(text)} · в списке «Идеи»")
    if b == "did":
        d = item.get("did") or {}
        said = item.get("said") or item.get("title") or ""
        pid = (item.get("fields") or {}).get("person_id")
        twins = bool(item.get("twins")) and not pid
        p = _person_row(conn, pid)
        head = _n(said.split()[0]) if said.split() else ""
        named = p and any(people.same(head, w) for w in people.words(f"{p['name']} {p['short'] or ''}"))
        if twins:  # «Иван позвонил…» с тёзками — имя первым словом, большая буква остаётся
            named = any(people.same(head, w) for c in item["twins"] for w in people.words(f"{c['name']} {c.get('short') or ''}"))
        said = _short(said if named else _lowfirst(said), 80)
        if twins:
            return f"Сделано: {said} — {_who_word(_twin_short(item))}? · вопрос в «Подскажи»"
        return f"Сделано: {said} · {d.get('line') or 'записал сделанным'}"
    if b == "cancelled":
        was = re.sub(r"^Переложил:\s*", "", item.get("was_line") or what)
        return f"Отменил: {_short(was, 120)}"
    if b == "error":
        return f"Не разложил: {item.get('error')}"
    return what


TIME_OPTIONS = [{"label": "через час", "at": "hour"}, {"label": "вечером 19:00", "at": "evening"},
                {"label": "завтра 9:00", "at": "tomorrow"}, {"label": "в пн 9:00", "at": "monday"}]


def _who_chip(item: dict) -> dict:
    """Тёзки — одна кнопка «Какой Иван ▾» со списком (проверка 16.10.2026: в пилюле первой в строке стояла кнопка
    «Иван Петров», и одно касание клало заметку к случайному из тёзок)."""
    return {"label": _cap(_who_word(_twin_short(item))), "move": "person",
            "options": [{"label": _short(c["name"] + (f" ({c['org']})" if c.get("org") else ""), 40), "person_id": c["id"]}
                        for c in (item.get("twins") or [])[:3]]}


def chips_of(item: dict) -> list[dict]:
    """До трёх кнопок поправки одним касанием (spec-0 §2.6): подпись и куда переложить (capture.move). Первая — частая
    поправка: пилюля «Д» и «З» показывает в строке только её (у напоминания — «Время ▾»: «в 9», сказанное в 10:30, —
    это сегодня 21:00, а не завтра утром)."""
    b = item["bucket"]
    cancel = {"label": "Отменить", "move": "cancel"}
    if b == "remind":
        return [{"label": "Время", "move": "time", "options": TIME_OPTIONS}, {"label": "Это дело", "move": "task"}, cancel]
    if b == "place":
        return [{"label": "Время", "move": "time", "options": TIME_OPTIONS}, cancel]
    if b == "back":
        return [{"label": "Сегодня", "move": "today"}, {"label": "В «Сейчас»", "move": "now"}, cancel]
    if b in ("today", "day"):
        return [{"label": "В «Сейчас»", "move": "now"}, {"label": "Потом", "move": "back"}, cancel]
    if b == "now":
        return [{"label": "Сегодня", "move": "today"}, {"label": "Потом", "move": "back"}, cancel]
    if b == "task_remind":
        return [{"label": "Только напомнить", "move": "remind"}, cancel]
    if b == "note":
        if item.get("twins") and not item.get("person_id"):
            return [_who_chip(item), cancel]
        return [{"label": "Это дело", "move": "task"}, {"label": "Идея", "move": "idea"}, cancel]
    if b == "idea":
        return [{"label": "Это дело", "move": "task"}, {"label": "Заметка", "move": "note"}, cancel]
    if b == "did":
        if item.get("twins") and not _has_person(item):
            return [_who_chip(item), cancel]
        d = item.get("did") or {}
        return ([{"label": "Не закрывать", "move": "keep_open"}] if d.get("closed") else []) + [cancel]
    if b in ("waiting", "owed", "agenda"):
        return [cancel]
    if b == "error":
        return [{"label": "Повторить", "move": "retry"}, {"label": "Дело потом", "move": "back_task"}]
    return []


def short_of(item: dict, now: dt.datetime) -> str:
    """Корзина коротко — для строки «Разложил 3: …»: куда и что («сделано: позвонил Ольге · на завтра: подготовить
    договор»; проверка 16.10.2026: «Разложил 2: сделано · на завтра» не говорило, что именно)."""
    b, t, today = item["bucket"], item.get("task") or {}, now.date()
    title = str((item.get("said") if b == "did" else None) or t.get("title") or item.get("title") or item.get("text") or "")
    who = re.match(r"^([^:]{1,40}):", t.get("title") or "")
    if who and b in ("day", "waiting", "owed", "agenda"):
        title = title[who.end():]
    what = _short(_lowfirst(title.strip()), 40)
    if b == "remind":
        word = "напоминание " + when_say(remind.at_of(t["remind_at"]), now)
    elif b == "place":
        word = "напоминание по месту"
    elif b == "back" and t.get("back_on"):
        d = dt.date.fromisoformat(str(t["back_on"])[:10])
        word = f"потом — верну {WD_SHORT[d.weekday()]}" if 1 < (d - today).days < 7 else "потом — " + back_say(d, today)
    elif b in ("day", "task_remind") and t.get("due_date"):
        # Сквозной тест 15.10.2026: «Разложил 2: на день · жду» не говорил, когда и от кого, — а строка для того и есть.
        d = dt.date.fromisoformat(str(t["due_date"])[:10])
        word = day_on(d, today)
        word = word[0].lower() + word[1:]
        if b == "day" and who and t.get("ball") == "waiting":
            word = f"жду от {genitive(who.group(1).strip())} {word}"
        elif b == "task_remind":
            word = "дело " + word
    elif b in ("waiting", "owed", "agenda") and who:
        name = who.group(1).strip()
        word = {"waiting": f"жду от {genitive(name)}", "owed": f"ждёт {name}",
                "agenda": f"при встрече с {instrumental(name)}"}[b]
    else:
        word = {"now": "в «Сейчас»", "today": "на сегодня", "day": "на день", "task_remind": "дело с напоминанием",
                "waiting": "жду", "owed": "ждут от тебя", "agenda": "при встрече", "note": "заметка", "idea": "идея",
                "did": "сделано", "cancelled": "отменено", "error": "не разложено"}.get(b, b)
    return f"{word}: {what}" if what and b not in ("cancelled", "error") else word


def _snap(t: dict) -> dict:
    """Дело в итоге захвата — то, что нужно строке, кнопкам и телефону."""
    from . import store

    keys = ("id", "num", "title", "kind", "status", "due_date", "due_time", "remind_at", "remind_place", "focus_on",
            "back_on", "back_said", "person_id", "project_id", "deal_id", "ball", "owed")
    return store.jsonable({k: t.get(k) for k in keys})


def _task_fields(item: dict, source: str, ref: str) -> dict:
    title = (item.get("title") or "").strip()[:500]
    if not title:
        raise CaptureError("у дела нет названия")
    f = {k: v for k, v in (item.get("fields") or {}).items() if v not in (None, "")}
    return {"title": title, "source": source, "source_ref": REF_PREFIX + ref, **f}


def _apply_one(conn, user: str, item: dict, ref: str, source: str, ctx: Ctx, i: int) -> dict:
    from . import did, ideas, store

    b = item["bucket"]
    if b in TASK_BUCKETS:
        fields = _task_fields(item, source, ref)
        if b == "now":
            try:
                with conn.transaction():
                    t = store.op_task_create(conn, user, {"task": fields})["task"]
                n = conn.execute("SELECT count(*) AS n FROM tasks.tasks WHERE owner_id = %s AND status = 'open' "
                                 "AND focus_on = crm.today()", (user,)).fetchone()["n"]
                item["now_n"] = n
            except store.OpError:
                # «Сейчас» полно — на сегодня, и так и сказано.
                fields.pop("focus_on", None)
                fields["due_date"] = ctx.today.isoformat()
                t = store.op_task_create(conn, user, {"task": fields})["task"]
                item["now_full"] = True
        else:
            t = store.op_task_create(conn, user, {"task": fields})["task"]
        item["task"] = _snap(t)
        item["undo"] = [{"op": "task.cancel", "id": str(t["id"])}]
        return item
    if b == "note":
        data = {"at": ctx.now.isoformat(), "kind": "note", "summary": (item.get("summary") or item.get("text") or "")[:2000],
                "source": "raznoska", "source_ref": f"{REF_PREFIX}{ref}:{i}"}
        if item.get("person_id"):
            data["person_ids"] = [str(item["person_id"])]
        if item.get("project_id"):
            data["project_id"] = str(item["project_id"])
        row = store.op_interaction_add(conn, user, {"data": data})["row"]
        item["note_id"] = str(row["id"])
        item["undo"] = [{"op": "interaction.delete", "id": str(row["id"])}]
        return item
    if b == "idea":
        data = {"text": item["text"], "source": "web" if source == "web" else "voice", "source_ref": REF_PREFIX + ref}
        for k in ("project_id", "person_id", "deal_id"):
            if item.get(k):
                data[k] = str(item[k])
        if data.get("project_id") and not data.get("deal_id"):
            only = conn.execute("SELECT crm.only_deal(%s::uuid) AS d", (data["project_id"],)).fetchone()["d"]
            if only:
                data["deal_id"] = str(only)
                item["deal_id"] = str(only)
        row = ideas.op_idea_add(conn, user, {"idea": data}, store.OpError)["idea"]
        item["idea_id"] = str(row["id"])
        item["undo"] = [{"op": "idea.set", "id": str(row["id"]), "set": {"status": "dropped"}}]
        return item
    if b == "did":
        task = {"title": item["title"], "source": source, "source_ref": REF_PREFIX + ref, **(item.get("fields") or {})}
        got = did.op_task_did(conn, user, {"task": task, "said": item.get("said") or item["title"],
                                            "at": ctx.now.isoformat()}, store.OpError)
        item["task"] = _snap(got["task"])
        item["did"] = store.jsonable({k: v for k, v in got["did"].items() if k != "undo"})
        item["undo"] = store.jsonable(got["did"]["undo"])
        return item
    raise CaptureError(f"нет такой корзины: {b}")


ASK_KIND = "capture"  # вопрос «Какой Иван?» в «Подскажи» (dela_0032: kind capture)


def _ask_who(conn, user: str, item: dict, ref: str, i: int, now: dt.datetime) -> None:
    """Тёзки — запись без человека и вопрос в «Подскажи» с кнопками людей (как pick у «Нового»): кнопка под строкой живёт
    12 секунд пилюли, а вопрос дождётся (проверка 16.10.2026: не нажал — заметки не было ни в одной карточке, а «позвонил
    Ивану» при двух Иванах не закрывало «Позвонить Ивану»). Ответ — тем же capture.move person (store: kind capture);
    кнопка под строкой гасит вопрос, «Отменить» — тоже."""
    from . import did, store

    short = _twin_short(item)
    if item["bucket"] == "note":
        text = _short(item.get("text") or "", 120)
        ask_ = f"Заметка «{text}» — {_who_of(short)}?"
        title = f"Заметка: {text}"
        quote = item.get("person_said") and f"{item['person_said']}: {text}" or text
    else:
        said = _short(_cap(item.get("said") or item.get("title") or ""), 120)
        ask_ = f"«{said}» — {_who_word(short)}?"
        title = f"Сделано: {said}"
        quote = said
    opts = [{"label": _short(c["name"] + (f" ({c['org']})" if c.get("org") else ""), 80), "person_id": c["id"]}
            for c in (item.get("twins") or [])[:3]]
    row = store._insert(conn, "tasks.suggestions", {
        "kind": ASK_KIND, "task_id": (item.get("task") or {}).get("id") if item["bucket"] == "did" else None,
        "source": "user", "for_user": user, "created_by": user, "quote": quote,
        "expires_at": dt.datetime.now(dt.timezone.utc) + store.SUGGESTION_TTL,
        # «said:» — фраза владельца: в счётчике автоматики — sources.said, не all (как вопросы did.py).
        "batch_ref": f"{did.SAID_PREFIX}capture-{ref}", "batch_title": f"Сказал · {now.astimezone(MSK):%d.%m}",
        "payload": Jsonb({"ask": ask_, "title": title, "options": opts, "pick": True,
                          "capture": {"ref": ref, "item": i}})})
    item["ask_id"] = str(row["id"])
    item["undo"] = [*(item.get("undo") or []),
                    {"op": "suggestion.decide", "id": str(row["id"]), "decision": "reject", "reason": "вернул"}]


def _close_ask(conn, user: str, item: dict, pid: str, skip: str | None) -> None:
    """Тёзку выбрали кнопкой под строкой — вопрос в «Подскажи» отвечен тем же (skip — его закрывает сам ответ вопроса)."""
    from . import store

    sid = item.get("ask_id")
    if not sid or sid == skip:
        return
    conn.execute("UPDATE tasks.suggestions SET status = 'accepted', reason = 'ответ кнопкой под строкой', decided_by = %s, "
                 "decided_at = now(), result = %s WHERE id = %s AND status = 'pending'",
                 (user, Jsonb(store.jsonable({"person_id": pid})), sid))


def apply(conn, user: str, items: list[dict], ref: str, source: str, ctx: Ctx) -> list[dict]:
    """Корзины → записи в Делах, каждая своей точкой сохранения: упавшая не роняет соседей. У каждой — строка и кнопки."""
    from . import store

    out = []
    for i, item in enumerate(items):
        item = dict(item)
        try:
            with conn.transaction():
                item = _apply_one(conn, user, item, ref, source, ctx, i)
                if item.get("twins") and not _has_person(item) and item["bucket"] in ("note", "did"):
                    _ask_who(conn, user, item, ref, i, ctx.now)
        except (store.OpError, CaptureError) as e:
            item = {"bucket": "error", "error": str(e), "title": item.get("title") or item.get("text"), "was": item}
        except store.PERMANENT as e:
            item = {"bucket": "error", "error": store._db_error(e), "title": item.get("title") or item.get("text"), "was": item}
        item["line"] = line_of(conn, item, ctx.now)
        item["chips"] = chips_of(item) if item["bucket"] != "error" else []
        out.append(item)
    return out


def head_of(items: list[dict], now: dt.datetime) -> str:
    """Одна строка на всю фразу: одна корзина — её строка; несколько — «Разложил 3: …»."""
    if not items:
        return "Ничего не разложил"
    if len(items) == 1:
        return items[0]["line"]
    return f"Разложил {len(items)}: " + " · ".join(short_of(x, now) for x in items)


# ── Операции и вид ──────────────────────────────────────────────────────


FROM = ("say", "d", "z", "web", "pc")
LANES = ("rule", "auto")


def _clean_ref(v) -> str:
    s = "".join(ch for ch in str(v or "") if ch.isalnum() or ch in "-_")[:64]
    return s or str(uuid.uuid4())


def _said_now(op: dict, server_now: dt.datetime) -> dt.datetime:
    """«Сейчас» фразы — когда сказано (телефон без связи держит очередь): не позже сервера и не старше суток."""
    from . import store

    got = store._instant(op.get("said_at"))
    if got and server_now - dt.timedelta(days=1) <= got <= server_now + dt.timedelta(minutes=2):
        return got.astimezone(MSK)
    return server_now.astimezone(MSK)


def _result(row: dict) -> dict:
    """Итог захвата по строке наговорки — ответ операции, вида capture и повтора."""
    from . import store

    r = dict(row.get("capture_result") or {})
    status = {"rule": "done", "model": "done", "pending": "pending", "error": "error"}.get(row.get("capture"), "none")
    out = {"ref": row["id"][len(REF_PREFIX):] if str(row["id"]).startswith(REF_PREFIX) else row["id"], "status": status,
           "text": row.get("text"), **r}
    return store.jsonable(out)


def _source(frm: str) -> tuple[str, str]:
    """(источник дела, источник наговорки)."""
    return ("web", "web") if frm == "web" else ("voice", "phone")


def op_capture_say(conn, user, op):
    """capture.say {text, ref, lane, from, said_at, hint} — фраза в Дела одной дверью.

    Правило узнало — записано сразу, ответ — строка и кнопки. Нет: lane «rule» (телефон параллельно спрашивает свою
    развилку) — ничего не пишем, status «none»; lane «auto» — наговорка ждёт модели (pending), строка «Записал —
    раскладываю», фоновый разбор будится. Повтор с тем же ref — прежний итог."""
    from . import store

    text = " ".join(str(op.get("text") or "").split())
    if not text:
        raise store.OpError("захват: пусто — нечего раскладывать")
    if len(text) > MAX_INPUT:
        raise store.OpError(f"захват: длиннее {MAX_INPUT} знаков — раздели на части")
    ref = _clean_ref(op.get("ref"))
    did_ = REF_PREFIX + ref
    lane = op.get("lane") if op.get("lane") in LANES else "auto"
    frm = op.get("from") if op.get("from") in FROM else "say"
    have = conn.execute("SELECT * FROM tasks.dictations WHERE id = %s", (did_,)).fetchone()
    if have:
        return {"capture": _result(have)}
    server_now = conn.execute("SELECT now() AS n").fetchone()["n"]
    now = _said_now(op, server_now)
    started = time.monotonic()
    ctx = Ctx(conn, user, now)
    got = rule(conn, user, text, now, ctx)
    source, dsource = _source(frm)
    hint = str(op.get("hint") or "")[:4000] or None
    if got is None and lane == "rule":
        return {"capture": {"ref": ref, "status": "none", "text": text}}
    store.op_dictation_add(conn, user, {"dictation": {"id": did_, "text": text, "source": dsource,
                                                       "at": now.isoformat()}})
    if got is None:
        res = {"by": None, "head": f"Записал «{_short(text, 40)}» — раскладываю", "items": [], "from": frm}
        conn.execute("UPDATE tasks.dictations SET capture = 'pending', capture_result = %s, capture_from = %s, "
                     "capture_hint = %s WHERE id = %s", (Jsonb(res), frm, hint, did_))
        WAKE.set()
        row = conn.execute("SELECT * FROM tasks.dictations WHERE id = %s", (did_,)).fetchone()
        return {"capture": _result(row)}
    items = apply(conn, user, got["items"], ref, source, ctx)
    res = {"by": "rule", "form": got["form"], "ms": int((time.monotonic() - started) * 1000),
           "head": head_of(items, now), "items": items, "from": frm, "at": now.isoformat()}
    conn.execute("UPDATE tasks.dictations SET capture = 'rule', capture_result = %s, capture_from = %s, capture_hint = %s "
                 "WHERE id = %s", (Jsonb(store.jsonable(res)), frm, hint, did_))
    row = conn.execute("SELECT * FROM tasks.dictations WHERE id = %s", (did_,)).fetchone()
    log.info("захват правилом %s: %s → %s (%d мс)", got["form"], _short(text, 60), [x["bucket"] for x in items], res["ms"])
    return {"capture": _result(row)}


def view_capture(conn, user, ref=None, **_):
    """capture?ref= — итог захвата: телефон и веб спрашивают, пока модель раскладывает (pending → done / error)."""
    from . import store

    if not ref:
        raise store.OpError("нужен ref")
    row = conn.execute("SELECT * FROM tasks.dictations WHERE id = %s", (REF_PREFIX + _clean_ref(ref),)).fetchone()
    if not row:
        return {"capture": {"ref": ref, "status": "none"}}
    return {"capture": _result(row)}


# ── Поправка одним касанием ─────────────────────────────────────────────


def _at_option(code: str, now: dt.datetime) -> dt.datetime:
    today = now.date()
    if code == "hour":
        return (now + dt.timedelta(hours=1)).replace(second=0, microsecond=0)
    if code == "evening":
        d = today if now.hour < DEFAULT_EVENING[0] else today + dt.timedelta(days=1)
        return dt.datetime(d.year, d.month, d.day, *DEFAULT_EVENING, tzinfo=MSK)
    if code == "tomorrow":
        d = today + dt.timedelta(days=1)
        return dt.datetime(d.year, d.month, d.day, *MORNING, tzinfo=MSK)
    if code == "monday":
        d = next_monday(today)
        return dt.datetime(d.year, d.month, d.day, *MORNING, tzinfo=MSK)
    got = remind.local_iso(code)
    if got:
        return remind.at_of(got)
    raise CaptureError(f"время: не понял «{code}»")


def _run(conn, user, op: dict) -> dict:
    """Одна операция Дел внутри поправки — тем же обработчиком, что /api/ops; отказ — словами."""
    from . import store

    h = store.HANDLERS.get(op["op"])
    return h(conn, user, op)


def _set_task(conn, user, item: dict, set_: dict) -> dict:
    t = _run(conn, user, {"op": "task.set", "id": item["task"]["id"], "set": set_})["task"]
    item["task"] = _snap(t)
    return item


def _undo_all(conn, user, item: dict) -> None:
    for o in item.get("undo") or []:
        try:
            with conn.transaction():
                _run(conn, user, o)
        except Exception as e:  # noqa: BLE001 — уже отменено руками: дальше по списку
            log.info("захват: отмена %s — %s", o.get("op"), e)


def _move(conn, user, item: dict, to: str, op: dict, ctx: Ctx, ref: str, source: str, i: int) -> dict:
    from . import did, store

    b = item["bucket"]
    today = ctx.today.isoformat()
    if to == "cancel":
        _undo_all(conn, user, item)
        return {**item, "bucket": "cancelled", "was_line": item.get("line"), "undo": []}
    if b in TASK_BUCKETS and item.get("task"):
        # Дело могли поправить мимо пилюли (кнопка уведомления «Через час», карточка): кнопка работает с ним, каким оно
        # стало, а не со снимком захвата (сквозной тест 15.10.2026: «Через час», потом «Это дело» — дело ложилось на
        # день прежнего напоминания, а напоминание — на сегодня).
        fresh = store.task_by(conn, str(item["task"]["id"]))
        if fresh:
            item = {**item, "task": _snap(fresh)}
            if fresh["status"] != "open":
                raise CaptureError("дело уже " + ("сделано" if fresh["status"] == "done" else "отменено")
                                   + " — поправь в самом деле")
        clear_back = {"back_on": None, "back_said": None}
        if to == "time":
            at = _at_option(str(op.get("at") or "hour"), ctx.now)
            item = _set_task(conn, user, item, {"remind_at": remind.iso(at), "remind_place": None})
            return {**item, "bucket": "remind" if item["task"].get("kind") == "remind" else "task_remind"}
        if to == "task":  # напоминание → дело на день напоминания, напоминание остаётся
            at = remind.at_of(item["task"]["remind_at"]) if item["task"].get("remind_at") else ctx.now
            item = _set_task(conn, user, item, {"kind": "task", "due_date": at.date().isoformat()})
            return {**item, "bucket": "task_remind"}
        if to == "remind":  # дело с напоминанием → только напомнить
            item = _set_task(conn, user, item, {"kind": "remind", "due_date": None, "due_time": None, **clear_back})
            return {**item, "bucket": "remind" if item["task"].get("remind_at") else "place"}
        if to == "today":
            item = _set_task(conn, user, item, {"kind": "task", "due_date": today, "focus_on": None, **clear_back})
            return {**item, "bucket": "today", "now_full": False}
        if to == "now":
            item = _set_task(conn, user, item, {"kind": "task", "focus_on": today, **clear_back})
            n = conn.execute("SELECT count(*) AS n FROM tasks.tasks WHERE owner_id = %s AND status = 'open' "
                             "AND focus_on = crm.today()", (user,)).fetchone()["n"]
            return {**item, "bucket": "now", "now_n": n, "now_full": False}
        if to == "back":
            d = default_back(ctx.today, "")
            item = _set_task(conn, user, item, {"kind": "task", "due_date": None, "due_time": None, "focus_on": None,
                                                "back_on": d.isoformat(), "back_said": None})
            return {**item, "bucket": "back"}
    if to == "person" and b in ("note", "did"):
        pid = str(op.get("person_id") or "")
        if not any(c["id"] == pid for c in item.get("twins") or []):
            raise CaptureError("такого человека в вопросе не было")
        _close_ask(conn, user, item, pid, op.get("_ask"))
        undo = [o for o in item.get("undo") or [] if o.get("id") != item.get("ask_id")]
        if b == "note":
            client = ctx.client_of(pid)
            set_ = {"person_ids": [pid], **({"project_id": client} if client else {})}
            _run(conn, user, {"op": "interaction.set", "id": item["note_id"], "set": set_})
            return {**item, "person_id": pid, **({"project_id": client} if client else {}), "twins": None,
                    "undo": undo, "ask_id": None}
        # Сделанное: прежнее дело-основу (без человека) — отменить, и то же сказанное — заново с человеком: закроет его
        # обещание и запишет касание (как если бы тёзок не было).
        _undo_all(conn, user, {**item, "undo": undo})
        new = {"bucket": "did", "title": item.get("title"), "said": item.get("said"),
               "fields": {**(item.get("fields") or {}), "person_id": pid}}
        got = _apply_one(conn, user, new, ref, source, ctx, i)
        return {**got, "twins": None, "ask_id": None}
    if b == "note":
        if to in ("task", "idea"):
            _undo_all(conn, user, item)
            if to == "idea":
                new = {"bucket": "idea", "text": item.get("text"), "project_id": item.get("project_id"),
                       "person_id": item.get("person_id")}
            else:
                p = _person_row(conn, item.get("person_id"))
                who_ = (p["short"] or p["name"]) if p else ""
                new = {"bucket": "waiting" if p else "back",
                       "title": f"{who_}: {item.get('text')}" if p else _cap(item.get("text") or ""),
                       "fields": {"ball": "waiting", "person_id": str(p["id"]),
                                  **({"project_id": item["project_id"]} if item.get("project_id") else {})} if p
                       else {"back_on": default_back(ctx.today).isoformat()}}
            return _apply_one(conn, user, new, ref, source, ctx, i)
    if b == "idea" and to in ("task", "note"):
        if to == "note":
            _undo_all(conn, user, item)
            new = {"bucket": "note", "text": item.get("text"), "project_id": item.get("project_id"),
                   "person_id": item.get("person_id")}
            return _apply_one(conn, user, new, ref, source, ctx, i)
        new = {"bucket": "back", "title": _cap(item.get("text") or ""),
               "fields": {"back_on": default_back(ctx.today).isoformat(),
                          **{k: item[k] for k in ("project_id", "person_id") if item.get(k)}}}
        got = _apply_one(conn, user, new, ref, source, ctx, i)
        _run(conn, user, {"op": "idea.set", "id": item["idea_id"], "set": {"status": "used", "task_id": got["task"]["id"]}})
        got["undo"] = [*got["undo"], {"op": "idea.set", "id": item["idea_id"], "set": {"status": "open", "task_id": None}}]
        return got
    if b == "did" and to == "keep_open":
        d = item.get("did") or {}
        if not d.get("closed"):
            raise CaptureError("закрытого дела нет — нечего возвращать")
        _run(conn, user, {"op": "task.reopen", "id": str(d["closed"])})
        if d.get("comment"):
            try:
                with conn.transaction():
                    _run(conn, user, {"op": "comment.delete", "id": str(d["comment"])})
            except store.OpError:
                pass
        word = did.TOUCH_WORD.get(d.get("touch_kind") or "", "касание")
        left = {"звонок": "записан", "встреча": "записана"}.get(word, "записано")
        d = {**d, "closed": None, "line": f"«{d.get('closed_title')}» оставил открытым · {word} {left}"}
        undo = [o for o in item.get("undo") or [] if o["op"] not in ("task.reopen", "comment.delete")]
        return {**item, "did": d, "undo": undo}
    raise CaptureError(f"так переложить нельзя: {b} → {to}")


def op_capture_move(conn, user, op):
    """capture.move {ref, item, to, at?, person_id?} — поправка одной кнопкой; ответ — новый итог и строка."""
    from . import store

    ref = _clean_ref(op.get("ref"))
    row = conn.execute("SELECT * FROM tasks.dictations WHERE id = %s FOR UPDATE", (REF_PREFIX + ref,)).fetchone()
    if not row or not row.get("capture"):
        raise store.OpError("захват: нет такой записи или она не твоя")
    res = dict(row["capture_result"] or {})
    to = str(op.get("to") or "")
    server_now = conn.execute("SELECT now() AS n").fetchone()["n"]
    ctx = Ctx(conn, user, server_now)
    source = "web" if res.get("from") == "web" else "voice"
    if row["capture"] == "error" or to in ("retry", "back_task"):
        if to == "retry":
            res = {**res, "error": None, "head": f"Записал «{_short(row['text'], 40)}» — раскладываю", "items": []}
            for k in ("sync_at", "tries", "retry_at", "last_error"):  # повтор — фоновому циклу, с чистого счёта
                res.pop(k, None)
            conn.execute("UPDATE tasks.dictations SET capture = 'pending', capture_result = %s WHERE id = %s",
                         (Jsonb(store.jsonable(res)), row["id"]))
            WAKE.set()
        elif to == "back_task":
            items = apply(conn, user, [{"bucket": "back", "title": _cap(_short(row["text"], 300)),
                                        "fields": {"back_on": default_back(ctx.today).isoformat()}}], ref, source, ctx)
            res = {**res, "by": res.get("by") or "button", "error": None, "head": head_of(items, ctx.now), "items": items}
            conn.execute("UPDATE tasks.dictations SET capture = 'model', capture_result = %s WHERE id = %s",
                         (Jsonb(store.jsonable(res)), row["id"]))
        else:
            raise store.OpError("захват не разложен — «Повторить» или «Дело потом»")
        row = conn.execute("SELECT * FROM tasks.dictations WHERE id = %s", (row["id"],)).fetchone()
        return {"capture": _result(row)}
    items = list(res.get("items") or [])
    i = op.get("item", 0)
    if not isinstance(i, int) or isinstance(i, bool) or not 0 <= i < len(items):
        raise store.OpError("захват: нет такой строки")
    try:
        with conn.transaction():
            item = _move(conn, user, dict(items[i]), to, op, ctx, ref, source, i)
    except CaptureError as e:
        raise store.OpError(str(e)) from None
    item["line"] = ("Переложил: " + line_of(conn, item, ctx.now)) if to not in ("cancel",) else line_of(conn, item, ctx.now)
    item["chips"] = chips_of(item)
    items[i] = store.jsonable(item)
    res = {**res, "items": items, "head": head_of(items, ctx.now) if len(items) > 1 else item["line"]}
    conn.execute("UPDATE tasks.dictations SET capture_result = %s WHERE id = %s", (Jsonb(store.jsonable(res)), row["id"]))
    row = conn.execute("SELECT * FROM tasks.dictations WHERE id = %s", (row["id"],)).fetchone()
    return {"capture": _result(row), "item": items[i]}


# ── Модель: то, что правило не узнало ───────────────────────────────────


def capture_block(url: str | None = None) -> str:
    """Блок КОРЗИНЫ: Свод prompt.raznoska_capture (правит владелец без выкладки), запас — файл контракта."""
    if url:
        try:
            with db.session(url, "system", via="capture") as conn:
                row = conn.execute("SELECT body FROM crm.svod WHERE owner_id = crm.owner_id() AND key = %s "
                                   "AND body IS NOT NULL", (CAPTURE_KEY,)).fetchone()
            if row and (row["body"] or "").strip():
                return row["body"]
        except Exception:  # noqa: BLE001
            log.exception("prompt.raznoska_capture из Свода — беру файл контракта")
    return (PROMPTS / "raznoska-capture.txt").read_text(encoding="utf-8")


def with_capture(text: str, block: str) -> str:
    """Блок КОРЗИНЫ — один раз: в правилах уже есть «КОРЗИНЫ» (владелец вписал сам) — не трогаем; иначе перед строкой с
    {TODAY} — в голове промпта под кэшем, как блок дерева (parse.with_tree)."""
    if "КОРЗИНЫ" in text or not block.strip():
        return text
    cut = text.find("{TODAY}")
    if cut < 0:
        return text.rstrip() + "\n\n" + block.strip()
    line = text.rfind("\n", 0, cut) + 1
    return text[:line] + block.strip() + "\n\n" + text[line:]


IDEA_WORD = re.compile(r"(?:^|[\s,.:;!(«])(?:идея|идею|идеи|мысль на будущее)(?:$|[\s,.:;!)»])", re.I)


# Только глаголы речи («сказал», «говорит»), не смысла («хочет», «обещал», «просил»): «Иван хочет скидку» — так и остаётся
# (сквозной тест 16.10.2026: модель отдала заметку «Иван хочет скидку», а срез оставил «скидку»).
_SAID_HEAD = re.compile(r"^(?P<who>[^\s,:]+(?:\s+[^\s,:]+)?)\s+(?P<v>" + "|".join(sorted(SPEECH - SPEECH_TASK, key=len, reverse=True))
                        + r")(?=[\s,:—–-])[\s,:—–-]*(?:что\b[\s,]*)?", re.I)


def _said_off(text: str, ctx: Ctx, person_id: str) -> tuple[str, str | None]:
    """«Иван сказал, что бюджет будет в январе» → («бюджет будет в январе», «Иван»): кто сказал — уже человек заметки,
    как у правила З1 (там же и запись хронологии «Иван: …»). Сквозной тест 15.10.2026: модель клала заметку целиком, и
    строка выходила «Заметка у Ивана Петрова: иван сказал, что …». Говорит не этот человек — текст как есть."""
    m = _SAID_HEAD.match(text)
    if not m:
        return text, None
    got = ctx.person([_n(w) for w in m.group("who").split()])
    if not got or "person" not in got or got["person"]["id"] != person_id:
        return text, None
    rest = text[m.end():].strip(" ,:-—")
    return (rest, m.group("who")) if rest else (text, None)


def _model_date(s: str, today: dt.date) -> dt.date | None:
    try:
        d = dt.date.fromisoformat(str(s or "").strip()[:10])
    except ValueError:
        return None
    return d if today < d <= today + dt.timedelta(days=366) else None


def from_model(data: dict, index: dict, text: str, ctx: Ctx, places: list[str], defaults: dict | None = None) -> list[dict]:
    """Ответ модели → корзины. Корзину выбирает код по полям, не модель (spec-0 §2.2):
    done — сделано; напоминание без своего срока (или срок — день напоминания без часа) — remind; напоминание и свой
    срок — дело с напоминанием; now — «Сейчас»; срок — на день; waiting / owed / agenda без срока — свои группы;
    остальное — «потом» (back из слов модели или правилом). Идея без слова «идея» — дело «потом»."""
    from . import parse

    items: list[dict] = []
    today = ctx.today
    defaults = defaults or {}
    idea_said = bool(IDEA_WORD.search(text or ""))
    for t in data.get("tasks") or []:
        title = " ".join(str(t.get("title") or "").split())[:500]
        if not title:
            continue
        fields: dict = {}
        pid = parse._one(index, "projects", t.get("project") or "") or defaults.get("project_id")
        if pid:
            fields["project_id"] = str(pid)
        person = parse._one(index, "people", t.get("person") or "") or (defaults.get("person_id") if not t.get("person") else None)
        if not person and t.get("person"):
            got = ctx.person(people.words(t["person"])[:2])
            if got and "person" in got:
                person = got["person"]["id"]
        if person:
            fields["person_id"] = str(person)
        ball = t.get("ball") or "mine"
        ball = ball if (ball in ("mine", "owed") or person) else "mine"
        notes = (t.get("notes") or "").strip()
        if notes:
            fields["notes"] = notes
        if int(t.get("estimate_min") or 0) > 0:
            fields["estimate_min"] = int(t["estimate_min"])
        if isinstance(t.get("goal"), int) and t["goal"] > 0:
            fields["why_auto"] = t["goal"]
        if t.get("done"):
            items.append({"bucket": "did", "title": title, "said": title,
                          "fields": {"ball": "waiting" if ball == "waiting" else "mine",
                                     **{k: v for k, v in fields.items() if k in ("person_id", "project_id", "notes")}}})
            continue
        fields["ball"] = ball
        due = (t.get("due") or "").strip()
        due_d = None
        if re.fullmatch(r"\d{4}-\d{2}-\d{2}", due):
            try:
                due_d = dt.date.fromisoformat(due)
            except ValueError:
                due_d = None
        due_time = remind.norm_time(t.get("due_time")) if due_d else None
        at = remind.local_iso(t.get("remind_at"))
        said_place = (t.get("remind_place") or "").strip()
        place = "" if at else remind.match_place(said_place, places)
        if at or place:
            at_t = remind.at_of(at) if at else None
            at_d = at_t.date() if at_t else None
            # Свой срок — только другой день или другой час, чем у напоминания. «в 12 … не забыть»: модель ставит и
            # срок с часом 12:00, и напоминание в 12:00 — это один миг, то есть напоминание, а не дело в куче
            # (сквозной тест 15.10.2026: легло «Дело на завтра … напомню завтра в 12:00»).
            same = bool(at_t) and due_d == at_d and (not due_time or due_time == at_t.strftime("%H:%M"))
            own_due = due_d and not same
            if place and not own_due:
                items.append({"bucket": "place", "title": title,
                              "fields": {**fields, "kind": "remind", "remind_place": place}})
                continue
            if at and not own_due:
                items.append({"bucket": "remind", "title": title, "fields": {**fields, "kind": "remind", "remind_at": at}})
                continue
            fields.update({"remind_at": at} if at else {"remind_place": place})
            fields["due_date"] = due_d.isoformat()
            if due_time:
                fields["due_time"] = due_time
            items.append({"bucket": "task_remind", "title": title, "fields": fields})
            continue
        if said_place:
            fields["notes"] = (fields.get("notes", "") + "\n" + remind.place_note(said_place)).strip()
        if t.get("now") and ball in ("mine", "owed"):
            items.append({"bucket": "now", "title": title, "fields": {**fields, "focus_on": today.isoformat()}})
            continue
        if due_d:
            fields["due_date"] = due_d.isoformat()
            if due_time:
                fields["due_time"] = due_time
            items.append({"bucket": "today" if due_d == today else "day", "title": title, "fields": fields})
            continue
        if ball in ("waiting", "owed", "agenda"):
            items.append({"bucket": ball, "title": title, "fields": fields})
            continue
        back = _model_date(t.get("back") or "", today) or default_back(today, "")
        items.append({"bucket": "back", "title": title, "fields": {**fields, "back_on": back.isoformat()}})
    for n in data.get("notes") or []:
        text_ = " ".join(str(n.get("text") or "").split())
        if not text_:
            continue
        pid = parse._one(index, "projects", n.get("project") or "") or defaults.get("project_id")
        person = parse._one(index, "people", n.get("person") or "")
        said_by = None
        if person:
            text_, said_by = _said_off(text_, ctx, str(person))
        item = {"bucket": "note", "text": text_}
        if said_by:
            item.update(summary=f"{_cap(said_by)}: {text_}", person_said=said_by)
        if person:
            item["person_id"] = str(person)
            client = ctx.client_of(str(person)) if not pid else None
            if client:
                item["project_id"] = client
        if pid:
            item["project_id"] = str(pid)
        items.append(item)
    for x in data.get("ideas") or []:
        text_ = " ".join(str(x.get("text") or "").split())
        if not text_:
            continue
        pid = parse._one(index, "projects", x.get("project") or "")
        person = parse._one(index, "people", x.get("person") or "")
        if idea_said:
            item = {"bucket": "idea", "text": text_}
            if pid:
                item["project_id"] = str(pid)
            if person:
                item["person_id"] = str(person)
        else:  # слова «идея» нет — сказанное — решение: дело «потом»
            item = {"bucket": "back", "title": _cap(text_),
                    "fields": {"back_on": default_back(today).isoformat(),
                               **({"project_id": str(pid)} if pid else {}), **({"person_id": str(person)} if person else {})}}
        items.append(item)
    return items


def schema() -> dict:
    """Схема ответа захвата: схема разбора надиктовки (raznoska.schema.json) плюс now, back, done у дела и ideas наверху.
    Отдельным файлом: общая схема — контракт телефона с примером ответа в raznoska.txt (его тест сверяет поля), а блок
    КОРЗИНЫ телефону не нужен — его прежняя Разноска новых полей не ждёт."""
    return json.loads((PROMPTS / "raznoska-capture.schema.json").read_text(encoding="utf-8"))


def ask(cl, model: str, effort: str, system_head: str, user_text: str) -> dict:
    """Один запрос со схемой захвата (schema): голова промпта — под кэшем на 5 минут (фразы идут пачками), дата, время
    и фраза — хвостом сообщения."""
    from . import parse

    with cl.beta.messages.stream(
        model=model,
        max_tokens=16000,
        system=[{"type": "text", "text": system_head, "cache_control": {"type": "ephemeral"}}],
        output_config={"effort": effort, "format": {"type": "json_schema", "schema": schema()}},
        messages=[{"role": "user", "content": user_text}],
        betas=[parse.FALLBACK_BETA],
        fallbacks="default",
    ) as stream:
        msg = stream.get_final_message()
    if msg.stop_reason == "refusal":
        raise llm.paid(CaptureError("Claude отказался разбирать эту фразу"), msg, model)
    if msg.stop_reason == "max_tokens":
        raise llm.paid(CaptureError("фраза слишком длинная для одного разбора — раздели на части"), msg, model)
    body = next((b.text for b in msg.content if getattr(b, "type", "") == "text"), "")
    try:
        data = json.loads(body)
    except ValueError as e:
        raise llm.paid(CaptureError(f"Claude ответил не JSON ({e})"), msg, model) from e
    data["_usage"] = llm.usage_of(msg, model)
    return data


def prompt_parts(url: str, user: str, now: dt.datetime | None = None) -> dict:
    """Голова промпта захвата (правила Свода + дерево + блок КОРЗИНЫ + справочник + места) и хвост — дата и время
    (когда сказано: телефон без связи шлёт фразу позже, «в 12» считается от мига фразы)."""
    from . import parse

    with db.session(url, user, via="capture") as conn:
        cat, index = parse.catalog(conn)
        clock = conn.execute(
            "SELECT crm.today() AS d, to_char(now() AT TIME ZONE 'Europe/Moscow', 'HH24:MI') AS hm").fetchone()
        me = conn.execute("SELECT name, id = crm.owner_id() AS owner FROM crm.users WHERE id = %s", (user,)).fetchone()
        places = remind.places_of(conn, user)
    speaker = "" if not me or me["owner"] else (
        f"\n\nСейчас говорит не Саша, а {me['name']}. Везде выше, где «Саша», читай «{me['name']}»: "
        "mine — дело этого человека, waiting и agenda — от его имени.")
    tree = parse.tree_block(url, user) if me and me["owner"] else ""
    rules = with_capture(parse.with_tree(parse.template(url), tree), capture_block(url))
    rules = rules.replace("{CATALOG}", cat).replace("{PLACES}", remind.places_block(places))
    cut = rules.find("{TODAY}")
    line_at = rules.rfind("\n", 0, cut) + 1 if cut >= 0 else len(rules)
    head, tail = rules[:line_at].rstrip() + speaker, rules[line_at:]
    today, hm = clock["d"], clock["hm"]
    if now is not None:
        today, hm = now.astimezone(MSK).date(), now.astimezone(MSK).strftime("%H:%M")
    tail = tail.replace("{TODAY}", f"{today.isoformat()}, {parse.WD[today.weekday()]}").replace("{NOW}", hm)
    return {"head": head, "tail": tail.strip(), "index": index, "places": places}


def _model_items(url: str, user: str, text: str, hint: str | None, key: str, proxy: str | None, ask_fn,
                 now: dt.datetime, quota: bool = False) -> tuple[list[dict], dict, dict | None]:
    """Модель на фразу: (корзины, указатели справочника, usage). ask_fn(head, user_text) — подмена Claude в тестах.
    quota — фраза идёт к модели первый раз мимо строки веба (фоновый разбор): у не-владельца это один разбор суточного
    предела (guard.quota «parse»), сверх — guard.OverQuota до вызова."""
    from . import parse

    parts = prompt_parts(url, user, now)
    model, effort = llm.route(url, ROUTE)
    user_text = f"{parts['tail']}\n\nНаговор:\n{text}" + (f"\n\nКак телефон слышит имена: {hint}" if hint else "")
    if ask_fn is None:
        if not key:
            raise CaptureError("Claude не настроен: нет ключа в dela.env — сказанное с «напомни», «идея», «потом» "
                               "разложу и так")
        # Защита от петли (аудит №12, правило 24 карты): каждый вызов Claude у Дел — через guard.client; задача
        # «capture» (пороги — общие «*» Свода claude.caps). Стоит — guard.Stopped до вызова: платить нечего.
        cl = guard.client(url, key, proxy, ROUTE)
        ask_fn = lambda h, u: ask(cl, model, effort, h, u)  # noqa: E731
    if quota and not guard.blocked(url, ROUTE):
        # Суточный предел не-владельцу (аудит №12, правило 24; поезд 16.10.2026 — стык вагонов 1 и 4): «Д», «сказать»,
        # «З» и «Повторить» Наташи и Алёны — разбор моделью на сервере, как /api/parse: один разбор user_day.parse на
        # фразу. Владелец и службы — без предела (guard.quota сам). Защита стоит — предел не тратим: вызов и так не уйдёт.
        guard.quota(url, user, "parse")
    try:
        data = ask_fn(parts["head"], user_text)
    except CaptureError as e:
        llm.account_failed(url, ROUTE, e, model)
        raise
    usage = data.get("_usage")
    llm.account(url, ROUTE, llm.cost(usage, model, url), (usage or {}).get("model") or model, usage=usage)
    return data, parts, usage


def lay_out(url: str, user: str, ref: str, text: str, data: dict, parts: dict, source: str, now: dt.datetime,
            via: str = "capture", actor: str | None = None, defaults: dict | None = None) -> dict:
    """Ответ модели → корзины → записи, итог — в строку наговорки (capture = model)."""
    from . import store

    with db.session(url, user, actor, via) as conn:
        ctx = Ctx(conn, user, now)
        items = from_model(data, parts["index"], text, ctx, parts["places"], defaults)
        if not items:
            items = [{"bucket": "back", "title": _cap(_short(text, 300)),
                      "fields": {"back_on": default_back(ctx.today).isoformat()}}]
        laid = apply(conn, user, items, ref, source, ctx)
        res = {"by": "model", "head": head_of(laid, ctx.now), "items": laid, "at": now.isoformat()}
        conn.execute("UPDATE tasks.dictations SET capture = 'model', capture_result = capture_result || %s WHERE id = %s",
                     (Jsonb(store.jsonable(res)), REF_PREFIX + ref))
        row = conn.execute("SELECT * FROM tasks.dictations WHERE id = %s", (REF_PREFIX + ref,)).fetchone()
    return _result(row)


def _fail(url: str, user: str, ref: str, err: str) -> None:
    from . import store

    res = {"by": "model", "error": err, "head": f"Не разложил: {err} — текст в Делах",
           "items": [], "chips": chips_of({"bucket": "error"})}
    with db.session(url, user, via="capture") as conn:
        conn.execute("UPDATE tasks.dictations SET capture = 'error', capture_result = capture_result || %s WHERE id = %s",
                     (Jsonb(store.jsonable(res)), REF_PREFIX + ref))


RETRY_PAUSES = (5, 15, 45)  # сеть, VPN, перегрузка Claude — повторить через 5, 15, 45 с, потом «не разложил»
WORKERS = 2                 # два разбора разом: три сложные фразы подряд не ждут друг друга по 20–30 с


def _transient(e: Exception) -> bool:
    """Сбой, который проходит сам: нет связи (VPN, прокси), таймаут, 5xx, «перегружен», 429. Отказ, не JSON, нет ключа —
    не проходит (CaptureError), повтор их не спасёт."""
    try:
        import anthropic
    except ImportError:  # pragma: no cover — сервер без SDK
        return False
    if isinstance(e, (anthropic.APIConnectionError, anthropic.APITimeoutError)):
        return True
    code = getattr(e, "status_code", None)
    return isinstance(e, anthropic.APIStatusError) and isinstance(code, int) and (code >= 500 or code in (408, 409, 429))


def _retry_later(url: str, user: str, ref: str, tries: int, err: str) -> None:
    """Оставить наговорку ждать (pending) и повторить через RETRY_PAUSES[tries - 1] секунд."""
    pause = RETRY_PAUSES[min(tries, len(RETRY_PAUSES)) - 1]
    with db.session(url, user, via="capture") as conn:
        conn.execute("UPDATE tasks.dictations SET capture_result = capture_result || jsonb_build_object("
                     "'tries', %s::int, 'retry_at', now() + make_interval(secs => %s), 'last_error', %s::text) "
                     "WHERE id = %s AND capture = 'pending'", (tries, pause, err[:200], REF_PREFIX + ref))


def _tick_one(url: str, r: dict, key: str, proxy: str | None, ask_fn) -> None:
    ref = r["id"][len(REF_PREFIX):]
    started = time.monotonic()
    # Предел не-владельцу — один раз на фразу: не на повтор после сбоя сети (tries) и не на наговорку строки веба, которую
    # доразбирает цикл (synced: строка уже взяла правку словами в api.ask_start). «Повторить» обнуляет tries — новый разбор.
    first = not int(r.get("tries") or 0) and not r.get("synced")
    try:
        data, parts, usage = _model_items(url, r["owner_id"], r["text"], r["capture_hint"], key, proxy, ask_fn, r["at"],
                                          quota=first)
        source = "web" if r["capture_from"] == "web" else "voice"
        lay_out(url, r["owner_id"], ref, r["text"], data, parts, source, r["at"].astimezone(MSK))
        log.info("захват моделью: %s — %.1f с, токены %s", _short(r["text"], 60), time.monotonic() - started, usage)
        with db.session(url, r["owner_id"], via="capture") as conn:
            conn.execute("UPDATE tasks.dictations SET capture_result = (capture_result - 'retry_at' - 'last_error') || %s "
                         "WHERE id = %s", (Jsonb({"ms": int((time.monotonic() - started) * 1000)}), r["id"]))
    except (CaptureError, guard.Stopped, guard.OverQuota) as e:
        # Защита от петли стоит — «не разложил» её словами (что стоит и как включить), текст в Делах, «Повторить» —
        # кнопкой; повтором каждые 5 с цикл её не долбит (поезд 16.10.2026: захват и защита вагона 1 вместе). Сверх
        # суточного предела не-владельцу — так же, словами предела; модель не звалась.
        _fail(url, r["owner_id"], ref, str(e))
    except Exception as e:  # noqa: BLE001 — сеть, ключ, лимит: человеку коротко, в журнал подробно
        tries = int(r.get("tries") or 0) + 1
        if _transient(e) and tries <= len(RETRY_PAUSES):
            # Правило Ковчега — служба переживает минуты без VPN: сеть к Claude моргнула — повторить, а не сразу
            # «не разложил» (проверка 16.10.2026).
            log.warning("захват моделью: %s — повтор %d через %d с", type(e).__name__, tries,
                        RETRY_PAUSES[tries - 1])
            _retry_later(url, r["owner_id"], ref, tries, f"{type(e).__name__}: {e}")
            return
        log.exception("захват моделью")
        _fail(url, r["owner_id"], ref, f"Claude недоступен: {type(e).__name__}"
              + (f" (повторял {tries - 1} раза)" if tries > 1 else ""))


def tick(url: str, key: str = "", proxy: str | None = None, ask_fn=None, limit: int = 4, workers: int = WORKERS) -> int:
    """Фоновый разбор ждущих наговорок (pending): старые первыми, до двух разом. Каждая — модель, раскладка, итог в виде
    capture. Сбой сети, таймаут, 5xx и «перегружен» — наговорка ждёт дальше и повторяется через 5, 15, 45 с; не прошло —
    error словами. Отказ, не JSON, нет ключа — error сразу (текст — в наговорке, «Повторить» и «Дело потом» — кнопками).
    Перезапуск службы посреди разбора безвреден: наговорка так и ждёт и доразберётся в следующий тик. Ответ — сколько
    взято."""
    with db.session(url, "system", via="capture") as conn:
        rows = conn.execute("SELECT id, owner_id, text, at, capture_from, capture_hint, "
                            "coalesce((capture_result ->> 'tries')::int, 0) AS tries, "
                            "coalesce(capture_result ? 'sync_at', false) AS synced FROM tasks.dictations "
                            "WHERE capture = 'pending' AND coalesce((capture_result ->> 'sync_at')::timestamptz, "
                            "'-infinity') < now() - make_interval(mins => %s) "
                            "AND coalesce((capture_result ->> 'retry_at')::timestamptz, '-infinity') <= now() "
                            "ORDER BY at LIMIT %s",
                            (SYNC_GRACE_MIN, limit)).fetchall()
    if workers > 1 and len(rows) > 1:
        from concurrent.futures import ThreadPoolExecutor

        with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="capture") as pool:
            list(pool.map(lambda r: _tick_one(url, r, key, proxy, ask_fn), rows))
    else:
        for r in rows:
            _tick_one(url, r, key, proxy, ask_fn)
    return len(rows)


async def loop(url: str, key: str = "", proxy: str | None = None) -> None:
    """Фоновый цикл захвата: ждущие наговорки (pending) — модели. Будит операция capture.say (WAKE), иначе — раз в 5
    секунд; после перезапуска ждущие доразбираются. Ключа нет — ждущие сразу «не разложил» словами (правило работает и
    без ключа). Зовут его служба (__main__.cmd_serve) и тестовая копия (api.build при DELA_CAPTURE_LOOP=1)."""
    import asyncio

    while True:
        try:
            await asyncio.to_thread(tick, url, key, proxy)
        except Exception:  # noqa: BLE001
            log.exception("захват моделью")
        woke = await asyncio.to_thread(WAKE.wait, 5)
        WAKE.clear()
        if woke:
            await asyncio.sleep(0.3)  # операция будит до своей фиксации — дать ей закончиться


def say_now(url: str, user: str, text: str, frm: str, key: str, proxy: str | None, via: str = "web",
            actor: str | None = None, ask_fn=None, ref: str | None = None, skip_rule: bool = False,
            defaults: dict | None = None) -> dict:
    """Фраза целиком здесь и сейчас (строка веба): правило, а не узнало — модель в этом же запросе. skip_rule —
    правило уже молчало (ask.rule_first). defaults — проект и человек страницы, если модель их не назвала."""
    from . import store

    ref = _clean_ref(ref)
    text = " ".join(str(text or "").split())
    if not skip_rule:
        got = store.apply_ops(url, user, [{"op": "capture.say", "text": text, "ref": ref, "lane": "rule", "from": frm}],
                              via, actor)["results"][0]
        if not got.get("ok"):
            raise CaptureError(got.get("error") or "не вышло")
        cap = got["capture"]
        if cap["status"] != "none":
            return cap
    with db.session(url, user, actor, via) as conn:
        store.op_dictation_add(conn, user, {"dictation": {"id": REF_PREFIX + ref, "text": text,
                                                           "source": "web" if frm == "web" else "phone"}})
        # sync_at — «эту наговорку разбирает строка веба сама»: фоновый цикл той же службы (tick) её не берёт, пока не
        # прошло SYNC_GRACE_MIN (запрос веба умер — доразберёт цикл). Без метки цикл брал её через ≤ 5 с, пока строка
        # ждала Claude: два запроса и два набора дел (сквозной тест 15.10.2026).
        conn.execute("UPDATE tasks.dictations SET capture = 'pending', capture_result = %s || jsonb_build_object('sync_at', now()), "
                     "capture_from = %s WHERE id = %s",
                     (Jsonb({"by": None, "from": frm, "items": []}), frm, REF_PREFIX + ref))
    now = dt.datetime.now(MSK)
    try:
        data, parts, _usage = _model_items(url, user, text, None, key, proxy, ask_fn, now)
    except (CaptureError, guard.Stopped) as e:
        _fail(url, user, ref, str(e))  # строка веба (api.run_ask) скажет Stopped словами; цикл её не подхватит
        raise
    out = lay_out(url, user, ref, text, data, parts, "web" if frm == "web" else "voice", now, via, actor, defaults)
    out["usage"] = _usage
    return out


def rows_of(url: str, user: str, cap: dict) -> tuple[list[dict], list[dict]]:
    """Строки дел (как в синке, v_tasks) и записи хронологии, которые лёг захват, — для ответа строки веба."""
    from . import store

    tids = [str((x.get("task") or {}).get("id")) for x in cap.get("items") or [] if (x.get("task") or {}).get("id")]
    nids = [str(x["note_id"]) for x in cap.get("items") or [] if x.get("note_id")]
    with db.session(url, user, via="capture") as conn:
        tasks = conn.execute("SELECT * FROM tasks.v_tasks WHERE id = ANY(%s::uuid[]) ORDER BY num", (tids,)).fetchall() if tids else []
        notes = conn.execute("SELECT * FROM crm.interactions WHERE id = ANY(%s::uuid[]) ORDER BY at", (nids,)).fetchall() if nids else []
    return store.jsonable(tasks), store.jsonable(notes)


# ── «Сегодня»: напоминания и «Вернулось» ────────────────────────────────

# Напоминание на «Сегодня» (одно правило: здесь, app.js isRemindToday, телефон DelaGroups.isRemindToday): открытые
# напоминания с мигом сегодня или вчера (прошедшее неотмеченное — «вчера 12:00 · не отмечено»), по месту без времени;
# плюс дела с напоминанием на сегодня, если их срок не сегодня (иначе они и так в «Сегодня»). Старше вчерашнего — не
# здесь, а во «Все дела → Напоминания» группой «прошли — не отмечено» (REMIND_STALE_IF): смахнул уведомление — и
# строка висела бы на «Сегодня» бессрочно, через пару недель — новая куча (проверка 16.10.2026).
REMIND_TODAY_IF = ("((kind = 'remind' AND ((remind_at AT TIME ZONE 'Europe/Moscow')::date "
                   "BETWEEN crm.today() - 1 AND crm.today() "
                   "OR (remind_at IS NULL AND remind_place IS NOT NULL))) "
                   "OR (kind = 'task' AND (remind_at AT TIME ZONE 'Europe/Moscow')::date = crm.today() "
                   "AND due_date IS DISTINCT FROM crm.today()))")
# Прошло больше суток, не отмечено (одно правило: здесь, app.js isRemindStale, телефон DelaGroups.isRemindStale).
REMIND_STALE_IF = "(kind = 'remind' AND (remind_at AT TIME ZONE 'Europe/Moscow')::date < crm.today() - 1)"
# «Вернулось» (одно правило: здесь, app.js isBack, телефон): своё открытое дело «потом» без срока, день возврата
# наступил, не в «Сейчас».
BACK_IF = ("kind = 'task' AND ball = 'mine' AND NOT owed AND due_date IS NULL AND back_on <= crm.today() "
           "AND focus_on IS DISTINCT FROM crm.today()")


def today_extra(conn, user: str) -> dict:
    """Добавка к виду today: напоминания в своё время и «Вернулось»."""
    rem = conn.execute(
        f"SELECT * FROM tasks.v_tasks WHERE status = 'open' AND owner_id = %s AND NOT goal AND {REMIND_TODAY_IF} "
        "ORDER BY remind_at NULLS LAST, num", (user,)).fetchall()
    back = conn.execute(
        f"SELECT * FROM tasks.v_tasks WHERE status = 'open' AND owner_id = %s AND NOT goal AND {BACK_IF} "
        "ORDER BY back_on, num", (user,)).fetchall()
    return {"reminders": rem, "back": back}


def view_reminders(conn, user, **_):
    """«Все дела → Напоминания»: все открытые напоминания по времени, по месту — в конце; stale — id прошедших больше
    суток назад и не отмеченных (группа «прошли — не отмечено»)."""
    rows = conn.execute(f"SELECT *, {REMIND_STALE_IF} AS stale FROM tasks.v_tasks WHERE status = 'open' AND owner_id = %s "
                        "AND kind = 'remind' ORDER BY remind_at NULLS LAST, num", (user,)).fetchall()
    return {"items": rows, "stale": [str(r["id"]) for r in rows if r["stale"]]}


def back_shift(url: str) -> int:
    """Ночью: «Вернулось» не тронули за день — на ближайший понедельник (не раньше сегодня), back_n + 1. Ничего не
    пропадает: дело во «Всех делах» с меткой «↩ пн»."""
    with db.session(url, "system", "svc:back", "system") as conn:
        return conn.execute(
            "UPDATE tasks.tasks SET back_on = crm.today() + ((8 - extract(isodow FROM crm.today())::int) % 7), "
            "back_n = back_n + 1 WHERE status = 'open' AND kind = 'task' AND back_on < crm.today() "
            "AND due_date IS NULL").rowcount


def view_kuda(conn, user, **_):
    """«Куда что» — короткая страница для Саши (contract/prompts/kuda-chto.md; в APK — ресурсом рядом с промптами):
    видом, а не файлом — новый путь к статике пришлось бы открывать и на посреднике Aeza."""
    return {"text": (PROMPTS / "kuda-chto.md").read_text(encoding="utf-8")}


def register(handlers: dict, views: dict) -> None:
    handlers["capture.say"] = op_capture_say
    handlers["capture.move"] = op_capture_move
    views["capture"] = view_capture
    views["reminders"] = view_reminders
    views["kuda"] = view_kuda
