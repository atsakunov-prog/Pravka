"""Движок ленты Засечки на сервере (26.10.2026, docs/server-truth.md): порт `ZasechkaStore` телефона один к одному.

До 26.10 правда ленты жила в файле телефона, архив был её копией, а веб ставил телефону команды («ждёт телефона ·
до 5 мин»). Владелец: «основная база всё равно на сервере… переносим всё». Правда переехала в `crm.zas_entries`, а
правила ленты — сюда. Числа и порядок шагов — те же, что в `data/ZasechkaStore.kt`: телефон держит ту же логику для
работы без сети и для мгновенного виджета, и две ленты обязаны сходиться. Сходство держат общие примеры
`server/contract/zas-cases.json` — их гоняют и pytest, и тест телефона.

Здесь только чистая логика над списком записей: ни базы, ни часов — «сейчас» и пояс приходят снаружи (`Ledger(now=…)`),
поэтому любой случай воспроизводится тестом. Хранение, операции и публикация суток в архив — `zas.py`.

Железные правила ленты (docs/zasechka.md «Железные правила»):
1. лента не пересекается, сутки складываются ровно в 24 часа (полночь режет дело на куски);
2. человек сильнее робота: ручная запись режется ВОКРУГ закрытых авто-фактов (сон, тренировка с часов), а не наоборот;
3. неучтённого времени не бывает: дыра от минуты через 45 минут становится «Не размечено», а когда ничего не идёт —
   открытый живой заполнитель;
4. щели меньше минуты закрываются концом предыдущей записи, обрезки меньше 30 секунд исчезают.
"""

from __future__ import annotations

import dataclasses
import datetime as dt
from dataclasses import dataclass, replace
from typing import Iterable

# Сутки ленты — московские, как у телефона владельца (TimeZone.getDefault()). Смещение фиксированное, как MSK в ask.py:
# переходов на летнее время в России нет с 2014-го, а ZoneInfo на Windows без пакета tzdata не знает ни одного пояса.
TZ = dt.timezone(dt.timedelta(hours=3), "MSK")

GAP_FILL_MIN_MS = 60_000                # дыра короче минуты — не «Не размечено», а щель округления
GAP_FILL_QUARANTINE_MS = 45 * 60_000    # дыра зарастает, только когда её правый край постоял 45 минут
GAP_SOURCE = "gap"
GAP_CATEGORY = "Не размечено"
GAP_TITLE = "не размечено"
NORMALIZE_WINDOW_MS = 10 * 86_400_000   # историю старше десяти суток нормализация не трогает
CRUMB_MS = 30_000                       # обрезки сплайса короче полминуты — артефакт, не факт
NEAR_MS = 60_000                        # «встык» для врезок, слияния и дублей авто-фактов
CALL_PREFIX = "звонок"
PHONE_SIT_PREFIX = "Телефон: "          # сидение в телефоне (13.10.2026) — догадка робота, узнаётся по комментарию


@dataclass(frozen=True)
class Entry:
    """Кусок ленты — поля `ZasechkaStore.Entry`. Время — мс эпохи, end == 0 — дело ещё идёт."""
    id: int
    start: int
    end: int
    raw: str = ""
    title: str = ""
    category: str = ""
    client: str = ""
    useful: int = 0
    source: str = "voice"   # voice | text | edit | auto | gap | task | calendar
    created_at: int = 0
    pomodoros: int = 0
    comment: str = ""
    task: str = ""
    project: str = ""
    person: str = ""

    @property
    def open(self) -> bool:
        return self.end == 0

    def eff_end(self, now: int) -> int:
        return now if self.open else self.end

    def to_dict(self) -> dict:
        return dataclasses.asdict(self)

    @staticmethod
    def from_dict(d: dict) -> "Entry":
        names = {f.name for f in dataclasses.fields(Entry)}
        kw = {k: v for k, v in d.items() if k in names}
        for k in ("id", "start", "end", "useful", "created_at", "pomodoros"):
            if k in kw:
                kw[k] = int(kw[k] or 0)
        for k in ("raw", "title", "category", "client", "source", "comment", "task", "project", "person"):
            if k in kw:
                kw[k] = str(kw[k] or "")
        return Entry(**kw)


def next_midnight_ms(ms: int, tz: dt.tzinfo = TZ) -> int:
    """Следующая МЕСТНАЯ полночь после ms — календарём, а не +24 часа: переживает переход часов."""
    d = dt.datetime.fromtimestamp(ms / 1000, tz).date() + dt.timedelta(days=1)
    return int(dt.datetime.combine(d, dt.time(0), tz).timestamp() * 1000)


def day_start_ms(ms: int, tz: dt.tzinfo = TZ) -> int:
    d = dt.datetime.fromtimestamp(ms / 1000, tz).date()
    return int(dt.datetime.combine(d, dt.time(0), tz).timestamp() * 1000)


def day_of(ms: int, tz: dt.tzinfo = TZ) -> str:
    return dt.datetime.fromtimestamp(ms / 1000, tz).date().isoformat()


def iso(ms: int, tz: dt.tzinfo = TZ) -> str:
    """Миг как у телефона в архиве: yyyy-MM-dd'T'HH:mm:ss.SSS+03:00."""
    return dt.datetime.fromtimestamp(ms / 1000, tz).isoformat(timespec="milliseconds")


def is_call_title(t: str) -> bool:
    return t.lstrip().lower().startswith(CALL_PREFIX)


class Ledger:
    """Лента одного человека в памяти. Мутаторы — те же, что у `ZasechkaStore`, и с тем же порядком шагов."""

    def __init__(self, entries: Iterable[Entry], now: int, last_id: int = 0, tz: dt.tzinfo = TZ):
        self.entries: list[Entry] = sorted(entries, key=lambda e: e.start)
        self.now = now
        self.tz = tz
        self.last_id = max([last_id] + [e.id for e in self.entries])

    # ── служебное ─────────────────────────────────────────────────────────────

    def next_id(self, proposed: int | None = None) -> int:
        """Номер записи: мс «сейчас», но не меньше прежнего+1. Телефон может предложить свой (запись, которую он уже
        завёл у себя без сети) — берём, если такого номера ещё нет: на него ссылаются его пуши и очередь."""
        if proposed and proposed > 0 and all(e.id != proposed for e in self.entries):
            self.last_id = max(self.last_id, proposed)
            return proposed
        nid = max(self.now, self.last_id + 1)
        self.last_id = nid
        return nid

    def _sort(self) -> None:
        self.entries.sort(key=lambda e: e.start)

    def _index(self, eid: int) -> int:
        for i, e in enumerate(self.entries):
            if e.id == eid:
                return i
        return -1

    def _last_open_index(self) -> int:
        for i in range(len(self.entries) - 1, -1, -1):
            if self.entries[i].open:
                return i
        return -1

    def by_id(self, eid: int) -> Entry | None:
        i = self._index(eid)
        return self.entries[i] if i >= 0 else None

    def open_entry(self) -> Entry | None:
        i = self._last_open_index()
        return self.entries[i] if i >= 0 else None

    def gap_entry(self, start: int, end: int) -> Entry:
        return Entry(id=self.next_id(), start=start, end=end, title=GAP_TITLE, category=GAP_CATEGORY,
                     source=GAP_SOURCE, created_at=self.now)

    # ── нормализация (normalizeLocked) ───────────────────────────────────────

    def split_midnight(self) -> bool:
        """Ни одна запись не пересекает местную полночь: голова держит id, надиктовку и комментарий, остальные куски —
        новые номера и пустой текст; у идущего дела закрывается голова, хвост идёт дальше уже в новых сутках."""
        cutoff = self.now - NORMALIZE_WINDOW_MS
        changed = False
        out: list[Entry] = []
        for e in self.entries:
            eff_end = e.eff_end(self.now)
            if eff_end <= cutoff:
                out.append(e)
                continue
            day_end = next_midnight_ms(e.start, self.tz)
            if eff_end <= day_end:
                out.append(e)
                continue
            changed = True
            seg_start = e.start
            first = True
            while day_end < eff_end:
                out.append(replace(e, id=e.id if first else self.next_id(), start=seg_start, end=day_end,
                                   raw=e.raw if first else "", comment=e.comment if first else "",
                                   pomodoros=e.pomodoros if first else 0))
                first = False
                seg_start = day_end
                day_end = next_midnight_ms(seg_start, self.tz)
            out.append(replace(e, id=self.next_id(), start=seg_start, end=0 if e.open else e.end,
                               raw="", comment="", pomodoros=0))
        if changed:
            self.entries = out
            self._sort()
        return changed

    def drop_crumbs(self) -> bool:
        kept = [e for e in self.entries if e.open or e.end - e.start >= CRUMB_MS]
        if len(kept) == len(self.entries):
            return False
        self.entries = kept
        return True

    def trim_fillers(self) -> bool:
        """Настоящая запись вычитается из заполнителя «Не размечено»; идущий заполнитель настоящее открытое дело убивает,
        а закрытое, заходящее за его начало, — подрезает."""
        if not any(e.source == GAP_SOURCE for e in self.entries):
            return False
        cutoff = self.now - NORMALIZE_WINDOW_MS
        real = [e for e in self.entries if e.source != GAP_SOURCE and e.eff_end(self.now) > cutoff]
        changed = False
        out: list[Entry] = []
        for e in self.entries:
            if e.source != GAP_SOURCE or (not e.open and e.end <= cutoff):
                out.append(e)
                continue
            if e.open:
                if any(r.open for r in real):
                    changed = True
                    continue
                ends = [r.end for r in real if r.end > e.start and r.start < self.now]
                claim_end = max(ends) if ends else None
                if claim_end is None or claim_end <= e.start:
                    out.append(e)
                elif self.now - claim_end < GAP_FILL_MIN_MS:
                    changed = True  # крошка — уходит
                else:
                    out.append(replace(e, start=claim_end))
                    changed = True
                continue
            pieces = [(e.start, e.end)]
            for r in real:
                r_end = r.eff_end(self.now)
                if r.start >= e.end or r_end <= e.start:
                    continue
                nxt = []
                for s, en in pieces:
                    if r.start >= en or r_end <= s:
                        nxt.append((s, en))
                        continue
                    if r.start > s:
                        nxt.append((s, r.start))
                    if r_end < en:
                        nxt.append((r_end, en))
                pieces = nxt
            kept = [(s, en) for s, en in pieces if en - s >= GAP_FILL_MIN_MS]
            if len(kept) == 1 and kept[0] == (e.start, e.end):
                out.append(e)
                continue
            changed = True
            first = True
            for s, en in kept:
                out.append(replace(e, id=e.id if first else self.next_id(), start=s, end=en))
                first = False
        if changed:
            self.entries = out
            self._sort()
        return changed

    def splice_overlaps(self) -> bool:
        """Ручное дело, накрывшее закрытые авто-факты, режется на куски ВОКРУГ них; голова держит id (даже нулевой
        длины — якорь начала), надиктовка и комментарий — только на ней."""
        cutoff = self.now - NORMALIZE_WINDOW_MS
        autos = sorted((e for e in self.entries if e.source == "auto" and not e.open and e.end > cutoff),
                       key=lambda e: e.start)
        if not autos:
            return False
        rebuilt: list[Entry] = []
        changed = False
        for m in self.entries:
            if m.source == "auto" or m.source == GAP_SOURCE or (not m.open and m.end <= cutoff):
                rebuilt.append(m)
                continue
            m_end = 2**62 if m.open else m.end
            inside = [a for a in autos if a.start >= m.start and a.end <= m_end and a.start < m_end and a.end > m.start]
            if not inside:
                rebuilt.append(m)
                continue
            changed = True
            cursor = m.start
            first = True
            for a in inside:
                if a.start > cursor or first:
                    rebuilt.append(replace(m, id=m.id if first else self.next_id(), start=cursor,
                                           end=max(a.start, cursor), raw=m.raw if first else "",
                                           comment=m.comment if first else "", pomodoros=0))
                    first = False
                cursor = max(cursor, a.end)
            rebuilt.append(replace(m, id=m.id if first else self.next_id(), start=cursor,
                                   end=0 if m.open else max(m.end, cursor), raw=m.raw if first else "",
                                   comment=m.comment if first else ""))
        if changed:
            self.entries = rebuilt
            self._sort()
        return changed

    def fill_gaps(self) -> bool:
        cutoff = self.now - GAP_FILL_QUARANTINE_MS
        window_start = self.now - NORMALIZE_WINDOW_MS
        changed = False
        prev_end = -1
        for e in sorted(self.entries, key=lambda x: x.start):
            if prev_end > 0 and e.start - prev_end >= GAP_FILL_MIN_MS and e.start <= cutoff and e.start > window_start:
                self.entries.append(self.gap_entry(prev_end, e.start))
                changed = True
            e_end = e.eff_end(self.now)
            if e_end > prev_end:
                prev_end = e_end
        # Живой хвост: ничего не идёт, лента молчит минуту — «не размечено» считается открыто, прямо в ленте.
        if not any(e.open for e in self.entries) and prev_end > 0 and self.now - prev_end >= GAP_FILL_MIN_MS:
            self.entries.append(self.gap_entry(prev_end, 0))
            changed = True
        if changed:
            self._sort()
        return changed

    def close_hairline_gaps(self) -> bool:
        window_start = self.now - NORMALIZE_WINDOW_MS
        asc = sorted((e for e in self.entries if e.start > window_start), key=lambda e: e.start)
        changed = False
        for a, b in zip(asc, asc[1:]):
            if a.open or b.start <= a.end:
                continue
            if b.start - a.end >= GAP_FILL_MIN_MS:
                continue
            i = self._index(a.id)
            if i < 0:
                continue
            self.entries[i] = replace(a, end=b.start)
            changed = True
        return changed

    def normalize(self) -> bool:
        changed = self.split_midnight()
        changed = self.drop_crumbs() or changed
        changed = self.trim_fillers() or changed
        changed = self.splice_overlaps() or changed
        changed = self.fill_gaps() or changed
        changed = self.split_midnight() or changed     # заполнитель, только что заведённый, мог сам перейти полночь
        changed = self.close_hairline_gaps() or changed  # последним: после него лента непрерывна по-настоящему
        return changed

    def close_stale_open(self) -> bool:
        """Открытым может быть только последнее дело: остальные закрываются встык со следующим (загрузка ленты)."""
        opened = [e for e in self.entries if e.open]
        if len(opened) <= 1:
            return False
        changed = False
        for e in opened:
            later = [x for x in self.entries if x.start > e.start]
            if not later:
                continue
            nxt = min(later, key=lambda x: x.start)
            i = self._index(e.id)
            if i >= 0:
                self.entries[i] = replace(e, end=nxt.start)
                changed = True
        return changed

    # ── мутаторы (те же имена, что у телефона) ───────────────────────────────

    def close_open_raw(self, at: int) -> Entry | None:
        i = self._last_open_index()
        if i < 0:
            return None
        e = self.entries[i]
        closed = replace(e, end=max(at, e.start))
        self.entries[i] = closed
        return closed

    def start_entry(self, start: int, raw: str = "", title: str = "", category: str = "", client: str = "",
                    useful: int = 0, source: str = "voice", task: str = "", project: str = "",
                    id: int | None = None) -> Entry:
        """Новое дело с start; идущее закрывается в тот же миг. Авто-факт, накрывающий start, подрезается до него.
        Возвращает открытый хвост — идущее дело после сплайса."""
        self.close_open_raw(start)
        for i, e in enumerate(self.entries):
            if e.source == "auto" and not e.open and e.start < start < e.end:
                self.entries[i] = replace(e, end=start)
        opened = Entry(id=self.next_id(id), start=start, end=0, raw=raw.strip(), title=title.strip(),
                       category=category.strip(), client=client.strip(), useful=min(5, max(0, int(useful or 0))),
                       source=source, created_at=self.now, task=task.strip(), project=project.strip())
        self.entries.append(opened)
        self.normalize()
        self._sort()
        return self.open_entry() or opened

    def close_open(self, at: int) -> Entry | None:
        closed = self.close_open_raw(at)
        if closed is not None:
            self.normalize()
        return closed

    def insert_closed(self, start: int, end: int, raw: str = "", title: str = "", category: str = "",
                      client: str = "", useful: int = 0, id: int | None = None) -> Entry | None:
        """Вставка завершённого куска задним числом («с 18:30 до 18:50 ходил в туалет»): накрывающее ручное дело
        режется вокруг и продолжается после; ручная запись целиком внутри — ей дорогу, вставка ужимается."""
        s0, e0 = start, end
        if e0 <= s0:
            return None
        for b in self.entries:
            if b.source == "auto" or b.source == GAP_SOURCE or b.open:
                continue
            if b.start >= s0 and b.end <= e0:
                if b.start - s0 >= e0 - b.end:
                    e0 = b.start
                else:
                    s0 = b.end
        if e0 - s0 < 60_000:
            return None
        out: list[Entry] = []
        for e in self.entries:
            if e.source == "auto" or e.source == GAP_SOURCE:
                out.append(e)
                continue
            e_end = 2**62 if e.open else e.end
            if e.start >= e0 or e_end <= s0:
                out.append(e)
                continue
            if e.start < s0 and e_end > e0:
                out.append(replace(e, end=s0))
                out.append(replace(e, id=self.next_id(), raw="", comment="", start=e0,
                                   end=0 if e.open else e.end, pomodoros=0))
            elif e.start < s0:
                out.append(replace(e, end=s0))
            else:
                out.append(replace(e, start=e0))
        entry = Entry(id=self.next_id(id), start=s0, end=e0, raw=raw.strip(), title=title.strip(),
                      category=category.strip(), client=client.strip(), useful=min(5, max(0, int(useful or 0))),
                      source="voice", created_at=self.now)
        out.append(entry)
        self.entries = out
        self.normalize()
        self._sort()
        return entry

    def auto_duplicate(self, start: int, end: int, title: str) -> bool:
        clean = title.strip()
        span = end - start
        for e in self.entries:
            if e.source != "auto" or e.open:
                continue
            if e.title == clean and abs(e.start - start) < NEAR_MS:
                return True
            if is_call_title(e.title) and is_call_title(clean) and \
                    max(0, min(e.end, end) - max(e.start, start)) * 2 >= span:
                return True
        return False

    def insert_interruption(self, start: int, end: int, title: str, category: str, resume_previous: bool,
                            client: str = "", project: str = "", person: str = "", comment: str = "",
                            id: int | None = None) -> Entry | None:
        """Факт телефона, занимающий время (сон по экрану, звонок): идущее дело режется на start, а со звонком
        (resume_previous) его копия продолжается после. Дело, НАЧАТОЕ внутри врезки, сильнее: врезка ужимается."""
        if end <= start:
            return None
        if self.auto_duplicate(start, end, title):
            return None
        actual_end = end
        resume: Entry | None = None
        i = self._last_open_index()
        if i >= 0:
            o = self.entries[i]
            if o.start <= start:
                self.entries[i] = replace(o, end=max(start, o.start))
                if resume_previous:
                    resume = o
            elif o.start < end:
                actual_end = o.start
                if actual_end <= start:
                    return None
        entry = Entry(id=self.next_id(id), start=start, end=actual_end, title=title.strip(), category=category.strip(),
                      client=client.strip(), source="auto", created_at=self.now, project=project.strip(),
                      person=person.strip(), comment=comment.strip())
        self.entries.append(entry)
        if resume is not None:
            # Продолжение — дело владельца, не факт робота: источник прежний, а надиктовка — только у головы.
            self.entries.append(replace(resume, id=self.next_id(), raw="", start=actual_end, end=0,
                                        created_at=self.now))
        self.normalize()
        self._sort()
        return entry

    @staticmethod
    def dissolved(entries: list[Entry], eid: int) -> tuple[list[Entry], Entry | None] | None:
        call = next((e for e in entries if e.id == eid and not e.open and
                     (e.source == "auto" or e.comment.startswith(PHONE_SIT_PREFIX))), None)
        if call is None:
            return None

        def near(a: int, b: int) -> bool:
            return abs(a - b) < NEAR_MS

        before = next((e for e in entries if not e.open and e.id != eid and e.source != GAP_SOURCE
                       and near(e.end, call.start)), None)
        after = next((e for e in entries if e.id != eid and e.source != GAP_SOURCE and near(e.start, call.end)), None)
        out = [e for e in entries if e.id != eid]
        kept: Entry | None = None

        def put(e: Entry) -> None:
            for k, x in enumerate(out):
                if x.id == e.id:
                    out[k] = e
                    return

        if before is not None and after is not None and not after.raw.strip() and after.title == before.title \
                and after.category == before.category and after.client == before.client:
            kept = replace(before, end=after.end)
            out = [e for e in out if e.id != after.id]
            put(kept)
        elif before is not None:
            kept = replace(before, end=call.end)
            put(kept)
        elif after is not None:
            kept = replace(after, start=call.start)
            put(kept)
        return out, kept

    def dissolve_interruption(self, eid: int) -> Entry | None:
        """Убрать врезку звонка: дело, которое он разрезал, сшивается обратно одним шагом."""
        if self.by_id(eid) is None:
            return None
        got = self.dissolved(self.entries, eid)
        if got is None:
            return None
        nxt, kept = got
        self.entries = list(nxt)
        self.normalize()
        self._sort()
        return self.by_id(kept.id) if kept is not None else None

    def update(self, entry: Entry) -> bool:
        """Полная замена по id; правка времени, накрывшая авто-факты, делает дело главным — сплайс прямо здесь."""
        i = self._index(entry.id)
        if i < 0:
            return False
        self.entries[i] = entry
        self.normalize()
        self._sort()
        return True

    def delete(self, eid: int) -> bool:
        """Как у телефона: без нормализации — дыру заполнитель закроет на следующем проходе."""
        n = len(self.entries)
        self.entries = [e for e in self.entries if e.id != eid]
        return len(self.entries) != n

    def revert_auto_start(self, started_ids: Iterable[int], reopen_id: int) -> Entry | None:
        ids = set(started_ids)
        started = [e for e in self.entries if e.id in ids]
        if not started:
            return None
        self.entries = [e for e in self.entries if e.id not in ids]
        reopened = None
        i = self._index(reopen_id)
        if i >= 0 and not any(e.open for e in self.entries):
            reopened = replace(self.entries[i], end=0)
            self.entries[i] = reopened
        self.normalize()
        return reopened

    def reopen(self, eid: int) -> Entry | None:
        i = self._index(eid)
        if i < 0 or any(e.open for e in self.entries):
            return None
        reopened = replace(self.entries[i], end=0)
        self.entries[i] = reopened
        self.normalize()
        return reopened

    def absorb(self, guess_id: int, said_id: int) -> Entry | None:
        guess = self.by_id(guess_id)
        i = self._index(said_id)
        if guess is None or i < 0 or guess.open or abs(self.entries[i].start - guess.end) >= NEAR_MS:
            return None
        merged = replace(self.entries[i], start=guess.start)
        self.entries[i] = merged
        self.entries = [e for e in self.entries if e.id != guess_id]
        self.normalize()
        self._sort()
        return self.by_id(said_id) or merged

    def annotate(self, eid: int, raw: str) -> bool:
        i = self._index(eid)
        if i < 0:
            return False
        self.entries[i] = replace(self.entries[i], raw=raw)
        return True

    def set_comment(self, eid: int, comment: str) -> bool:
        i = self._index(eid)
        if i < 0:
            return False
        text = comment.strip()
        if self.entries[i].comment != text:
            self.entries[i] = replace(self.entries[i], comment=text)
        return True

    def rename_source(self, frm: str, to: str) -> int:
        n = 0
        for i, e in enumerate(self.entries):
            if e.source == frm:
                self.entries[i] = replace(e, source=to)
                n += 1
        return n

    def covered_by_owner(self, frm: int, to: int) -> bool:
        """Человек сильнее робота: закрытые записи владельца покрывают не меньше половины отрезка."""
        if to <= frm:
            return True
        manual = 0
        for e in self.entries:
            eff = 2**62 if e.open else e.end
            if not (e.start < to and eff > frm):
                continue
            if e.open or e.source in ("auto", GAP_SOURCE):
                continue
            manual += max(0, min(e.end, to) - max(e.start, frm))
        return manual * 2 >= to - frm


# ── сутки для архива (ArchiveEvents.zasechkaDays) ────────────────────────────

def day_snapshot(entries: Iterable[Entry], day: str, now: int, tz: dt.tzinfo = TZ) -> dict | None:
    """Сутки ленты так, как их кладёт в архив телефон (`zasechka.day`): записи, НАЧАВШИЕСЯ в этих сутках; minutes —
    минуты суток сверх покрытого более ранними кусками (на этом держится сумма 1440), у идущего — null."""
    lst = [e for e in entries if day_of(e.start, tz) == day]
    if not lst:
        return None
    base = day_start_ms(min(e.start for e in lst), tz)

    def minute_of(ms: int) -> int:
        return (ms - base + 30_000) // 60_000

    ordered = sorted(lst, key=lambda e: (e.start, -e.eff_end(now), e.id))
    covered = -(2**62)
    out = []
    for e in ordered:
        frm = max(e.start, covered)
        to = max(e.eff_end(now), covered)
        covered = to
        row = {
            "id": e.id, "start": iso(e.start, tz), "end": None if e.open else iso(e.end, tz),
            "minutes": None if e.open else minute_of(to) - minute_of(frm),
            "title": e.title, "category": e.category, "client": e.client, "source": e.source, "raw": e.raw,
            "comment": e.comment, "pomodoros": e.pomodoros, "rating": e.useful, "created": iso(e.created_at, tz),
        }
        if e.task.strip():
            row["task"] = e.task
        if e.project.strip():
            row["project"] = e.project
        if e.person.strip():
            row["person"] = e.person
        out.append(row)
    return {"day": day, "entries": out}
