from __future__ import annotations

import dataclasses
import os
import subprocess
import sys

from pravka_archive import tools
from pravka_archive.__main__ import next_delay
from pravka_archive.app import pid_alive, seed_sources
from pravka_archive.ingest import ingest_batch


def test_restart_delay_backs_off_and_resets():
    assert next_delay(5, 1) == 10
    assert next_delay(10, 1) == 20
    assert next_delay(200, 1) == 300          # потолок — пять минут
    assert next_delay(300, 3600) == 5         # проработала час — снова быстро


def test_pid_alive():
    assert pid_alive(os.getpid())
    p = subprocess.Popen([sys.executable, "-c", "pass"])
    p.wait()
    assert not pid_alive(p.pid)


def test_silent_sources_are_visible_and_phone_placeholder_goes(clean, cfg, batch):
    seed_sources(cfg)
    line = tools.freshness_line(clean)
    assert "телефон: ни разу не присылал — телефон ещё не подключён" in line
    assert "intervals: ни разу не присылал" in line
    ingest_batch(clean, batch, "sasha")
    line = tools.freshness_line(clean)
    assert "ни разу не присылал — телефон" not in line and "телефон: 0 мин назад" in line
    seed_sources(cfg)  # перезапуск службы заглушку не возвращает
    assert clean.execute("SELECT count(*) FROM core.sources WHERE source = 'phone'").fetchone()[0] == 0


def test_full_pull_progress_is_shown(clean, cfg):
    from pravka_archive.db import mark_source

    mark_source(clean, "intervals", ok=True, note={"full_progress": "2021 из 2026"})
    assert "идёт первая выгрузка всей истории: дошла до 2021 из 2026" in tools.freshness_line(clean)
    mark_source(clean, "intervals", ok=True, note={"full_progress": "2026 из 2026", "full_at": "2026-10-01T21:50:00+00:00"})
    assert "идёт первая" not in tools.freshness_line(clean)


def test_phone_gets_direct_address(cfg):
    direct = dataclasses.replace(cfg, phone_url="https://server.znakomiy.netcraze.pro:8443")
    assert direct.ingest_base == "https://server.znakomiy.netcraze.pro:8443"
    assert cfg.ingest_base == cfg.public_url
