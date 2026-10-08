"""Ночной разбор багов — будить Claude, только когда есть что разбирать.

Владелец, 08.10.2026: «можно сделать так, что если нет багов, то это и не
запускается?… на самом сервере сделать какую-то задачку, которая будет
смотреть: если нет новых, то и не будет запускаться, если есть — запускать».

Разбор багов с кнопок (`docs/feedback.md`) — Routine в Claude Code на облаке.
По расписанию она будила сессию каждую ночь, и пустая ночь стоила столько же,
сколько полная. Теперь расписания у Routine нет, есть «API»-вход: служба
архива раз в несколько минут смотрит, не пора ли (03:57 по Москве), и если
у кого-то из людей архива (хозяин — `life.feedback`, Марианна —
`marianna.feedback`, с 08.10 у каждого телефона свои номера) есть записи со
статусом `new` — дёргает Routine (`POST …/routines/{id}/fire` с её токеном).
Нет новых — Claude не будится вовсе. Раз в сутки: день, когда уже смотрели, помнит файл `night.json` в
журналах — перезапуск службы утром разбор не повторит.

Адрес и токен — в `server.env`: PRAVKA_NIGHT_ROUTINE (адрес из окна «API» или
просто `trig_…`), PRAVKA_NIGHT_TOKEN (`sk-ant-oat01-…`, показывается один раз),
PRAVKA_NIGHT_AT — время, с завода 03:57. Пусто — проверка спит.
"""

from __future__ import annotations

import asyncio
import datetime as dt
import json
import logging
from pathlib import Path
from typing import Callable

log = logging.getLogger("pravka.night")

# Москва без перехода на летнее время с 2014 года: смещение постоянное, и
# tzdata на Windows не нужна.
MSK = dt.timezone(dt.timedelta(hours=3))
API = "https://api.anthropic.com/v1/claude_code/routines/{id}/fire"
# Сколько раз за ночь пробовать разбудить, если Anthropic не ответил 200.
TRIES = 4

def new_labels(conn, persons) -> list[str]:
    """Новые записи всех людей архива: хозяина — «№20», остальных — «marianna №7».

    Номера у каждого телефона свои (`docs/feedback.md`), поэтому у чужого
    номера — профиль: так их называет и `feedback_done.txt` («marianna:7»).
    """
    from psycopg import sql

    out: list[str] = []
    for p in persons:
        q = sql.SQL("SELECT num FROM {}.feedback WHERE status = 'new' ORDER BY num").format(sql.Identifier(p.schema))
        try:
            nums = [r[0] for r in conn.execute(q).fetchall()]
        except Exception as e:  # схемы ещё нет (человек только добавлен) — остальных это не держит
            log.warning("ночной разбор: %s.feedback не читается: %s", p.schema, e)
            continue
        out += [f"№{n}" if p.owner else f"{p.profile} №{n}" for n in nums]
    return out


def parse_at(text: str) -> dt.time:
    """«03:57» → время; кривое — заводское 03:57 (не молча: пишет в журнал)."""
    try:
        h, m = text.strip().split(":")
        return dt.time(int(h), int(m))
    except (ValueError, AttributeError):
        if text:
            log.warning("ночной разбор: PRAVKA_NIGHT_AT «%s» не время — беру 03:57", text)
        return dt.time(3, 57)


def fire_url(routine: str) -> str:
    """Адрес из окна «API» как есть; одно `trig_…` — достраиваем."""
    r = routine.strip()
    if r.startswith("https://"):
        return r
    return API.format(id=r)


def due(now: dt.datetime, at: dt.time, last_day: str | None) -> bool:
    """Пора: по Москве уже [at] или позже, а сегодня ещё не смотрели."""
    local = now.astimezone(MSK)
    return local.time() >= at and last_day != local.date().isoformat()


def payload(labels: list[str]) -> str:
    """Текст в Routine: какие номера ждут — сессия всё равно перечитает их сама."""
    return "Новые записи с кнопок: " + ", ".join(labels) + f" (всего {len(labels)})."


def post(url: str, token: str, text: str) -> tuple[int, str]:
    """POST в Routine. Ответ — код и тело (ошибку не прячем: правило 6)."""
    import httpx

    r = httpx.post(
        url,
        headers={
            "Authorization": f"Bearer {token}",
            "anthropic-version": "2023-06-01",
            "anthropic-beta": "experimental-cc-routine-2026-04-01",
            "Content-Type": "application/json",
        },
        json={"text": text},
        timeout=60,
    )
    return r.status_code, r.text[:500]


class State:
    """Что было этой ночью: день, попытки, итог словами. Файл — `night.json`."""

    def __init__(self, path: Path):
        self.path = path
        try:
            self.data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            self.data = {}

    @property
    def day(self) -> str | None:
        return self.data.get("day")

    def save(self, **kw) -> None:
        self.data.update(kw)
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps(self.data, ensure_ascii=False, indent=1), encoding="utf-8")
        except OSError as e:
            log.warning("ночной разбор: не записал %s: %s", self.path, e)


def run_once(
    now: dt.datetime,
    at: dt.time,
    state: State,
    fetch_new: Callable[[], list[str]],
    fire: Callable[[str], tuple[int, str]],
    force: bool = False,
    dry: bool = False,
) -> str:
    """Один взгляд: не пора — ничего; пора — новые есть → разбудить, нет → отметить день.

    [force] — мимо времени и отметки дня (ручная проверка из консоли),
    [dry] — только сказать, будил бы или нет.
    """
    today = now.astimezone(MSK).date().isoformat()
    if not force and not due(now, at, state.day):
        return ""
    nums = fetch_new()
    if not nums:
        if not dry:
            state.save(day=today, tries=0, result="новых нет — Claude не будил")
        return "новых нет — Claude не будим"
    if dry:
        return f"новых {len(nums)} ({', '.join(nums)}) — разбудил бы Claude"
    tries = (state.data.get("tries", 0) if state.data.get("tries_day") == today else 0) + 1
    code, body = fire(payload(nums))
    if code == 200:
        session = ""
        try:
            session = json.loads(body).get("claude_code_session_url", "")
        except ValueError:
            pass
        state.save(day=today, tries=0, tries_day=today, result=f"разбудил: {len(nums)} новых · {session}".strip(" ·"))
        return f"разбудил Claude: новых {len(nums)} · {session}".strip(" ·")
    why = f"HTTP {code}: {body}"
    if tries >= TRIES:
        # Сдаёмся до следующей ночи, но причину видно в night.json и журнале.
        state.save(day=today, tries=tries, tries_day=today, result=f"не разбудил за {tries} попыток — {why}")
        return f"не разбудил за {tries} попыток, до следующей ночи — {why}"
    state.save(tries=tries, tries_day=today, result=f"попытка {tries} не вышла — {why}")
    return f"попытка {tries} не вышла, повторю — {why}"


def configured(cfg) -> bool:
    return bool(cfg.night_routine and cfg.night_token)


def run_cfg(cfg, now: dt.datetime | None = None, force: bool = False, dry: bool = False) -> str:
    """run_once на настоящей базе и с настоящим POST — для службы и консоли."""
    import psycopg

    def fetch_new() -> list[str]:
        with psycopg.connect(cfg.db_url, autocommit=True) as conn:
            return new_labels(conn, cfg.persons())

    url = fire_url(cfg.night_routine)
    return run_once(
        now or dt.datetime.now(dt.timezone.utc),
        parse_at(cfg.night_at),
        State(cfg.logs / "night.json"),
        fetch_new,
        lambda text: post(url, cfg.night_token, text),
        force=force,
        dry=dry,
    )


async def night_forever(cfg) -> None:
    """Раз в пять минут — взгляд; служба архива держит его вместе с сборщиком intervals."""
    import anyio

    if not configured(cfg):
        log.info("ночной разбор: адреса Routine нет — проверка спит (PRAVKA_NIGHT_ROUTINE, PRAVKA_NIGHT_TOKEN)")
        return
    log.info("ночной разбор: смотрю новые баги каждую ночь в %s по Москве", parse_at(cfg.night_at).strftime("%H:%M"))
    while True:
        try:
            said = await anyio.to_thread.run_sync(run_cfg, cfg)
            if said:
                log.info("ночной разбор: %s", said)
        except Exception as e:  # проверка не должна ронять службу
            log.warning("ночной разбор: %s: %s", e.__class__.__name__, e)
        await asyncio.sleep(300)
