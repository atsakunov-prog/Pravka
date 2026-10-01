from __future__ import annotations

import psycopg
import pytest

from pravka_archive.ingest import ingest_batch, store_events

RUN = {
    "id": "i9001", "start_date_local": "2026-09-07T13:00:00", "start_date": "2026-09-07T10:00:00Z",
    "type": "Run", "name": "Москва Бег", "elapsed_time": 2400, "moving_time": 2280, "distance": 5200.0,
    "average_speed": 2.7027, "gap": 2.75, "average_heartrate": 142, "max_heartrate": 165,
    "average_cadence": 84, "total_elevation_gain": 35, "icu_training_load": 55, "icu_intensity": 78,
    "decoupling": 3.1, "icu_efficiency_factor": 1.5, "calories": 410, "feel": 2, "icu_rpe": 5,
    "icu_hr_zone_times": [600, 1200, 480, 0, 0], "description": "лёгкий, колено ок\n[Правка]feel 2[/Правка]",
}
RIDE = dict(RUN, id="i9002", type="VirtualRide", name="Zwift", average_cadence=88, start_date_local="2026-09-06T20:00:00",
            start_date="2026-09-06T17:00:00Z", description="")
WELL = {"id": "2026-09-07", "restingHR": 52, "hrv": 58, "sleepSecs": 25920, "sleepScore": 80, "sleepQuality": 2,
        "steps": 9100, "weight": 86.1, "ctl": 45.2, "atl": 52.0, "readiness": 70, "kcalConsumed": 2100,
        "comments": "выспался плохо, но бодрый"}
FUTURE = dict(WELL, id="2099-01-01")


def icu(kind: str, key: str, data: dict, seq: int) -> dict:
    return {"eid": f"intervals:{seq}", "seq": seq, "kind": kind, "key": key, "op": "put",
            "at": "2026-09-07T23:00:00+00:00", "data": data}


@pytest.fixture()
def full(clean, batch):
    ingest_batch(clean, batch, "sasha")
    store_events(clean, "intervals", [
        icu("icu.activity", "i9001", RUN, 1), icu("icu.activity", "i9002", RIDE, 2),
        icu("icu.wellness", "2026-09-07", WELL, 3), icu("icu.wellness", "2099-01-01", FUTURE, 4),
    ])
    return clean


def one(conn, q, *args):
    return conn.execute(q, args).fetchone()


def test_ribbon_day_sums_to_1440_and_scores(full):
    assert one(full, "SELECT sum(minutes) FROM life.entries WHERE day = '2026-09-07'")[0] == 1440
    row = one(full, "SELECT start_local, end_local, client, pomodoros, points FROM life.entries WHERE title = 'разбор отчётности'")
    assert row[:4] == ("07:40", "12:00", "Тестовый клиент", 3)
    assert float(row[4]) == pytest.approx(260 / 60 * 5, abs=0.05)
    assert one(full, "SELECT end_local FROM life.entries WHERE title = 'вечер дома'")[0] == "00:00"
    assert one(full, "SELECT count(*) FROM life.entries WHERE source = 'gap'")[0] == 1


def test_days_row(full):
    d = one(full, "SELECT dow, ribbon_min, kcal, protein_g, workouts, km, sleep_h, family_spent_rub, screen_min, gtg_status "
                  "FROM life.days WHERE day = '2026-09-07'")
    assert d[0] == "пн"
    assert d[1] == 1440
    assert d[2] == 520 and d[3] == 42
    assert d[4] == 1 and float(d[5]) == 5.2
    assert float(d[6]) == 7.2
    assert d[7] == 2150          # голосовой близнец не удваивает
    assert d[8] == 312
    assert d[9] == "частично"


def test_money_live_and_shelves(full):
    rows = full.execute("SELECT id, source, live, category_title, shelf FROM life.money ORDER BY id").fetchall()
    assert ("t:3fa1c0d2e4b5a6978812", "tinkoff", True, "Продукты", "family") in rows
    assert ("v:1757240000000:0", "voice", False, "Продукты", "family") in rows
    assert one(full, "SELECT count(*) FROM life.money_live")[0] == 1
    assert one(full, "SELECT amount_rub FROM life.money_live")[0] == -2150


def test_food_micro_and_norms(full):
    assert one(full, "SELECT confidence, items FROM life.meals")[0:2] == ("примерно", 3)
    mg = one(full, "SELECT amount, pct_of_norm, from_pills FROM life.micro_days WHERE substance = 'mg'")
    assert float(mg[0]) == pytest.approx(120.5) and mg[1] == 29 and mg[2] is None
    vd = one(full, "SELECT amount, from_pills, name FROM life.micro_days WHERE substance = 'vd'")
    assert float(vd[0]) == 50 and float(vd[1]) == 50 and vd[2] == "Витамин D"


def test_strength_sets(full):
    rows = full.execute("SELECT exercise, n, amount, weight_kg FROM life.strength_sets ORDER BY exercise, n").fetchall()
    assert [(r[0], r[1], float(r[2]), r[3] and float(r[3])) for r in rows] == [
        ("Махи гирей", 1, 15.0, 16.0), ("Махи гирей", 2, 15.0, 16.0), ("Подтягивания с резинкой", 1, 5.0, None)]


def test_workouts_pace_cadence_and_raw(full):
    run = one(full, "SELECT day, start_local, km, pace_s_km, cadence, raw->>'icu_rpe' FROM life.workouts WHERE id = 'i9001'")
    assert str(run[0]) == "2026-09-07" and run[1] == "13:00"
    assert float(run[2]) == 5.2 and run[3] == 370 and float(run[4]) == 168 and run[5] == "5"
    ride = one(full, "SELECT cadence FROM life.workouts WHERE id = 'i9002'")
    assert float(ride[0]) == 88  # у вело обороты не удваиваются


def test_wellness_skips_future_forecast(full):
    assert [str(r[0]) for r in full.execute("SELECT day FROM life.wellness")] == ["2026-09-07"]
    assert float(one(full, "SELECT tsb FROM life.wellness")[0]) == pytest.approx(-6.8)


def test_said_finds_word_forms_and_strips_kbju(full):
    hits = full.execute(
        "SELECT domain FROM life.said WHERE tsv @@ websearch_to_tsquery('russian', 'Марианна') "
        "OR tsv_words @@ to_tsquery('simple', 'мариан:*') ORDER BY domain"
    ).fetchall()
    domains = sorted(h[0] for h in hits)
    assert "засечка" in domains and "правка" in domains  # «с Марианной» в ленте, «Марианна привет» в диктовке
    raw = one(full, "SELECT text FROM life.said WHERE ref = 't1757218200000'")[0]
    assert "КБЖУ" not in raw and raw == "завтракаем с Борей и Ромой"
    assert one(full, "SELECT count(*) FROM life.said WHERE domain = 'тренер'")[0] == 2
    assert one(full, "SELECT count(*) FROM life.said WHERE domain = 'тренировки'")[0] == 1


def test_reader_reads_life_only(full, cfg):
    with psycopg.connect(cfg.reader_url, autocommit=True) as r:
        assert r.execute("SELECT count(*) FROM life.entries").fetchone()[0] == 6
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            r.execute("SELECT * FROM core.events")
    with psycopg.connect(cfg.reader_url, autocommit=True) as r:
        with pytest.raises(psycopg.Error):
            r.execute("DELETE FROM life.entries")
