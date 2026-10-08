"""Сборщик intervals.icu: сервер берёт всё сам, своим ключом.

Не через телефон: у телефона кэш на 120 дней и два десятка полей. Здесь —
вся история с первой активности, полная карточка каждой (с интервалами),
посекундные потоки, исходный файл с часов, wellness по всем полям,
календарь и пороги. Всё, что приложение пишет в intervals (блоки
«[Правка]», feel, итог еды), возвращается этим же путём.

Ритм:
  * каждые 10 минут — последние три дня тренировок, неделя wellness,
    календарь на две недели вперёд;
  * раз в сутки (после 04:00) — последние 60 дней: правки задним числом;
  * один раз — вся история, по году за запрос.

События кладутся тем же store_events, что и телефонные, устройство
«intervals». Тот же снимок события не плодит: опрос каждые 10 минут
журнал не раздувает.

С 08.10.2026 у каждого человека архива свой сборщик своим ключом
(ICU_ATHLETE_ID_<ПРОФИЛЬ>, ICU_API_KEY_<ПРОФИЛЬ>): устройство и строка
свежести «intervals-marianna», записи — под его person.
"""

from __future__ import annotations

import datetime as dt
import hashlib
import logging
import re
import time
from pathlib import Path
from typing import Any, Callable

import httpx
import psycopg

from .config import Config, Person
from .db import mark_source
from .ingest import digest, store_events

log = logging.getLogger("pravka.icu")

BASE = "https://intervals.icu/api/v1"
DEVICE = "intervals"
FULL_FROM = dt.date(2010, 1, 1)


class Puller:
    def __init__(
        self,
        cfg: Config,
        client: httpx.Client | None = None,
        today: Callable[[], dt.date] | None = None,
        person: Person | None = None,
    ):
        self.cfg = cfg
        # Чей intervals: без person — хозяина архива, как было до второго человека.
        self.person = person or cfg.persons()[0]
        self.athlete = self.person.icu_athlete
        self.device = self.person.icu_source
        self.client = client or httpx.Client(
            base_url=BASE, auth=("API_KEY", self.person.icu_key), timeout=httpx.Timeout(60.0, connect=20.0),
            headers={"User-Agent": "pravka-archive"},
        )
        self.today = today or dt.date.today
        self._seq = 0

    # ------------------------------------------------------------ события

    def _event(self, kind: str, key: str, data: dict[str, Any] | None, op: str = "put") -> dict[str, Any]:
        # seq растёт и между перезапусками: время в мс × 1000 + счётчик.
        self._seq = max(self._seq + 1, int(time.time() * 1000) * 1000)
        return {
            "eid": f"{self.device}:{self._seq}",
            "seq": self._seq,
            "kind": kind,
            "key": key,
            "op": op,
            "at": dt.datetime.now(dt.timezone.utc).isoformat(timespec="milliseconds"),
            "data": data,
        }

    def _put(self, conn: psycopg.Connection, events: list[dict[str, Any]]) -> int:
        if not events:
            return 0
        with conn.transaction():
            res = store_events(conn, self.device, events, app="icu-puller", person=self.person.key)
        if res.rejected:
            log.warning("%s: отвергнуто %d событий: %s", self.device, len(res.rejected), res.rejected[:3])
        return res.changed

    def _get(self, path: str, **params: Any) -> Any:
        r = self.client.get(path, params=params or None)
        if r.status_code == 401 or r.status_code == 403:
            raise RuntimeError(f"intervals: {r.status_code} — ключ или athlete id не подходят ({r.text[:200]})")
        r.raise_for_status()
        return r.json()

    # ------------------------------------------------------------ части

    def pull_athlete(self, conn: psycopg.Connection) -> int:
        a = self._get(f"/athlete/{self.athlete}")
        return self._put(conn, [self._event("icu.athlete", self.athlete, a)])

    def pull_wellness(self, conn: psycopg.Connection, oldest: dt.date, newest: dt.date) -> int:
        rows = self._get(f"/athlete/{self.athlete}/wellness", oldest=oldest.isoformat(), newest=newest.isoformat())
        events = [self._event("icu.wellness", str(w.get("id")), w) for w in rows if isinstance(w, dict) and w.get("id")]
        return self._put(conn, events)

    def pull_events(self, conn: psycopg.Connection, oldest: dt.date, newest: dt.date) -> int:
        rows = self._get(f"/athlete/{self.athlete}/events", oldest=oldest.isoformat(), newest=newest.isoformat())
        if not isinstance(rows, list):
            return 0
        seen = {str(e.get("id")) for e in rows if isinstance(e, dict) and e.get("id") is not None}
        events = [self._event("icu.event", str(e["id"]), e) for e in rows if isinstance(e, dict) and e.get("id") is not None]
        # Событие пропало из календаря внутри окна — его удалили.
        gone = conn.execute(
            "SELECT key FROM core.records WHERE person = %s AND kind = 'icu.event' AND NOT deleted "
            "AND left(data->>'start_date_local', 10) BETWEEN %s AND %s",
            (self.person.key, oldest.isoformat(), newest.isoformat()),
        ).fetchall()
        events += [self._event("icu.event", k, None, op="del") for (k,) in gone if k not in seen]
        return self._put(conn, events)

    def pull_activities(self, conn: psycopg.Connection, oldest: dt.date, newest: dt.date, deep: bool = True) -> int:
        rows = self._get(f"/athlete/{self.athlete}/activities", oldest=oldest.isoformat(), newest=newest.isoformat())
        if not isinstance(rows, list):
            return 0
        changed = 0
        listed: set[str] = set()
        for item in rows:
            if not isinstance(item, dict) or not item.get("id"):
                continue
            aid = str(item["id"])
            listed.add(aid)
            h = digest(item)
            seen = conn.execute(
                "SELECT list_hash, streams_done, file_done FROM core.icu_seen WHERE activity_id = %s", (aid,)
            ).fetchone()
            if seen is None or seen[0] != h:
                detail = item
                if deep:
                    try:
                        detail = self._get(f"/activity/{aid}", intervals="true")
                    except httpx.HTTPError as e:
                        log.warning("intervals: карточка %s не пришла (%s), беру из списка", aid, e)
                changed += self._put(conn, [self._event("icu.activity", aid, detail)])
            streams_done = bool(seen and seen[1])
            file_done = bool(seen and seen[2])
            if deep and not streams_done:
                streams_done = self._pull_streams(conn, aid)
            if deep and not file_done:
                file_done = self._pull_file(conn, aid)
            conn.execute(
                "INSERT INTO core.icu_seen (activity_id, list_hash, streams_done, file_done, updated_at) "
                "VALUES (%s, %s, %s, %s, now()) ON CONFLICT (activity_id) DO UPDATE SET "
                "list_hash = EXCLUDED.list_hash, streams_done = EXCLUDED.streams_done, file_done = EXCLUDED.file_done, updated_at = now()",
                (aid, h, streams_done, file_done),
            )
            if deep:
                time.sleep(0.2)
        # Тренировку удалили в intervals — внутри окна её больше нет в списке.
        gone = conn.execute(
            "SELECT key FROM core.records WHERE person = %s AND kind = 'icu.activity' AND NOT deleted "
            "AND left(data->>'start_date_local', 10) BETWEEN %s AND %s",
            (self.person.key, oldest.isoformat(), newest.isoformat()),
        ).fetchall()
        changed += self._put(conn, [self._event("icu.activity", k, None, op="del") for (k,) in gone if k not in listed])
        return changed

    def _pull_streams(self, conn: psycopg.Connection, aid: str) -> bool:
        try:
            streams = self._get(f"/activity/{aid}/streams.json")
        except httpx.HTTPStatusError as e:
            if e.response.status_code == 404:
                return True  # у ручной записи без файла потоков нет — и не будет
            log.warning("intervals: потоки %s не пришли: %s", aid, e)
            return False
        except httpx.HTTPError as e:
            log.warning("intervals: потоки %s не пришли: %s", aid, e)
            return False
        if isinstance(streams, list):
            self._put(conn, [self._event("icu.streams", aid, {"streams": streams})])
        return True

    def _pull_file(self, conn: psycopg.Connection, aid: str) -> bool:
        try:
            r = self.client.get(f"/activity/{aid}/file")
        except httpx.HTTPError as e:
            log.warning("intervals: файл %s не пришёл: %s", aid, e)
            return False
        if r.status_code == 404:
            return True
        if r.status_code != 200 or not r.content:
            log.warning("intervals: файл %s — %s", aid, r.status_code)
            return False
        name = _file_name(aid, r.headers.get("content-disposition", ""), r.headers.get("content-type", ""))
        folder = self.cfg.blobs / "icu"
        folder.mkdir(parents=True, exist_ok=True)
        path = folder / name
        tmp = path.with_suffix(path.suffix + ".part")
        tmp.write_bytes(r.content)
        tmp.replace(path)
        meta = {"activity_id": aid, "path": str(path), "size": len(r.content),
                "sha256": hashlib.sha256(r.content).hexdigest(), "content_type": r.headers.get("content-type", "")}
        self._put(conn, [self._event("icu.file", aid, meta)])
        return True

    # ------------------------------------------------------------ проходы

    def _progress(self, conn: psycopg.Connection, where: str) -> None:
        row = conn.execute("SELECT note FROM core.sources WHERE source = %s", (self.device,)).fetchone()
        note = dict(row[0] or {}) if row else {}
        note["full_progress"] = where
        mark_source(conn, self.device, ok=True, note=note)

    def run(self, mode: str = "recent") -> dict[str, int]:
        """recent — каждые 10 минут, deep — раз в сутки, full — вся история."""
        today = self.today()
        out: dict[str, int] = {}
        with psycopg.connect(self.cfg.db_url, autocommit=True) as conn:
            try:
                if mode == "full":
                    out["athlete"] = self.pull_athlete(conn)
                    year = FULL_FROM
                    while year <= today:
                        end = min(dt.date(year.year, 12, 31), today + dt.timedelta(days=1))
                        out[f"activities {year.year}"] = self.pull_activities(conn, year, end)
                        out[f"wellness {year.year}"] = self.pull_wellness(conn, year, end)
                        # Первая выгрузка идёт до часа. Пока она не кончилась,
                        # источник не должен выглядеть «ни разу не подключался»:
                        # отмечаемся после каждого года, с тем, докуда дошли.
                        self._progress(conn, f"{year.year} из {today.year}")
                        year = dt.date(year.year + 1, 1, 1)
                    out["events"] = self.pull_events(conn, today - dt.timedelta(days=365), today + dt.timedelta(days=120))
                elif mode == "deep":
                    out["athlete"] = self.pull_athlete(conn)
                    out["activities"] = self.pull_activities(conn, today - dt.timedelta(days=60), today + dt.timedelta(days=1))
                    out["wellness"] = self.pull_wellness(conn, today - dt.timedelta(days=60), today + dt.timedelta(days=1))
                    out["events"] = self.pull_events(conn, today - dt.timedelta(days=60), today + dt.timedelta(days=60))
                else:
                    out["activities"] = self.pull_activities(conn, today - dt.timedelta(days=3), today + dt.timedelta(days=1))
                    out["wellness"] = self.pull_wellness(conn, today - dt.timedelta(days=7), today + dt.timedelta(days=1))
                    out["events"] = self.pull_events(conn, today - dt.timedelta(days=3), today + dt.timedelta(days=14))
            except Exception as e:
                mark_source(conn, self.device, ok=False, error=f"{mode}: {e.__class__.__name__}: {e}")
                raise
            note = conn.execute("SELECT note FROM core.sources WHERE source = %s", (self.device,)).fetchone()
            note = dict(note[0] or {}) if note else {}
            note[f"{mode}_at"] = dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")
            mark_source(conn, self.device, ok=True, note=note)
        return out


def _file_name(aid: str, disposition: str, content_type: str) -> str:
    m = re.search(r'filename="?([^";]+)"?', disposition or "")
    if m:
        ext = "".join(Path(m.group(1)).suffixes)[-12:] or ".bin"
    elif "gzip" in content_type:
        ext = ".fit.gz"
    elif "fit" in content_type:
        ext = ".fit"
    else:
        ext = ".bin"
    safe = re.sub(r"[^A-Za-z0-9_-]", "_", aid)
    return f"{safe}{ext}"


def due(note: dict[str, Any] | None, now: dt.datetime) -> str | None:
    """Какой проход пора делать: full (ни разу), deep (не сегодня после 04:00) или None."""
    note = note or {}
    if not note.get("full_at"):
        return "full"
    deep = note.get("deep_at")
    local = now.astimezone()
    if local.hour >= 4:
        if not deep or dt.datetime.fromisoformat(deep).astimezone().date() < local.date():
            return "deep"
    return None
