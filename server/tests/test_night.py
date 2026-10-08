"""Ночной разбор: будить Claude, только когда есть новые баги (08.10.2026)."""

from __future__ import annotations

import datetime as dt
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from pravka_archive import night  # noqa: E402

AT = dt.time(3, 57)


def msk(h: int, m: int, day: int = 8) -> dt.datetime:
    return dt.datetime(2026, 10, day, h, m, tzinfo=night.MSK)


def test_pora_tolko_posle_vremeni_i_raz_v_sutki():
    assert not night.due(msk(3, 50), AT, None)
    assert night.due(msk(3, 57), AT, None)
    assert night.due(msk(9, 0), AT, "2026-10-07")
    assert not night.due(msk(9, 0), AT, "2026-10-08")
    # 00:58 UTC — это 03:58 по Москве.
    assert night.due(dt.datetime(2026, 10, 8, 0, 58, tzinfo=dt.timezone.utc), AT, "2026-10-07")


def test_adres_i_vremya():
    assert night.fire_url("trig_01ABC") == "https://api.anthropic.com/v1/claude_code/routines/trig_01ABC/fire"
    assert night.fire_url(" https://x/fire ") == "https://x/fire"
    assert night.parse_at("04:10") == dt.time(4, 10)
    assert night.parse_at("утро") == AT


def test_novyh_net_claude_ne_budim(tmp_path):
    st = night.State(tmp_path / "night.json")
    fired = []
    said = night.run_once(msk(4, 0), AT, st, lambda: [], lambda t: fired.append(t) or (200, "{}"))
    assert said.startswith("новых нет")
    assert fired == []
    assert st.day == "2026-10-08"
    # Второй взгляд той же ночью — тишина: день отмечен.
    assert night.run_once(msk(4, 5), AT, st, lambda: ["№20"], lambda t: (200, "{}")) == ""


def test_novye_est_budim_odin_raz(tmp_path):
    st = night.State(tmp_path / "night.json")
    fired = []

    def fire(text):
        fired.append(text)
        return 200, '{"claude_code_session_url":"https://claude.ai/code/session_1"}'

    said = night.run_once(msk(4, 0), AT, st, lambda: ["№20", "marianna №3"], fire)
    assert fired == ["Новые записи с кнопок: №20, marianna №3 (всего 2)."]
    assert "session_1" in said
    assert night.run_once(msk(4, 5), AT, st, lambda: ["№20"], fire) == ""
    assert len(fired) == 1
    # Файл переживает перезапуск службы.
    assert night.State(tmp_path / "night.json").day == "2026-10-08"


def test_oshibka_povtor_potom_sdaemsya(tmp_path):
    st = night.State(tmp_path / "night.json")
    calls = []

    def bad(text):
        calls.append(text)
        return 401, '{"error":"bad token"}'

    for k in range(1, night.TRIES):
        said = night.run_once(msk(4, k), AT, st, lambda: ["№22"], bad)
        assert f"попытка {k}" in said and "HTTP 401" in said
        assert st.day is None
    said = night.run_once(msk(4, 10), AT, st, lambda: ["№22"], bad)
    assert "до следующей ночи" in said
    assert st.day == "2026-10-08"
    assert len(calls) == night.TRIES


def test_rukami_mimo_vremeni_i_vsuhuyu(tmp_path):
    st = night.State(tmp_path / "night.json")
    fired = []
    said = night.run_once(msk(15, 0), AT, st, lambda: ["№23"], lambda t: fired.append(t) or (200, "{}"), force=True, dry=True)
    assert "разбудил бы" in said and fired == []
    assert st.day is None
