from __future__ import annotations

import datetime as dt
import json

import httpx

from pravka_archive import icu
from test_views import RUN, WELL


def fake_intervals(state: dict):
    def handler(request: httpx.Request) -> httpx.Response:
        state.setdefault("calls", []).append(request.url.path)
        path = request.url.path
        assert request.headers["authorization"].startswith("Basic ")
        if path.endswith("/athlete/i12345"):
            return httpx.Response(200, json={"id": "i12345", "sportSettings": [{"types": ["Run"], "threshold_pace": 3.3}]})
        if path.endswith("/activities"):
            return httpx.Response(200, json=state["activities"])
        if path.endswith("/wellness"):
            return httpx.Response(200, json=[WELL])
        if path.endswith("/events"):
            return httpx.Response(200, json=state["events"])
        if path.endswith("/streams.json"):
            return httpx.Response(200, json=[{"type": "heartrate", "data": [120, 130, 140]}])
        if path.endswith("/file"):
            return httpx.Response(200, content=b"FITDATA", headers={"content-disposition": 'attachment; filename="run.fit.gz"'})
        if "/activity/" in path:
            aid = path.rsplit("/", 1)[1]
            item = next(a for a in state["activities"] if a["id"] == aid)
            return httpx.Response(200, json=dict(item, icu_intervals=[{"type": "WORK"}]))
        return httpx.Response(404)

    return handler


def puller(cfg, state):
    client = httpx.Client(base_url=icu.BASE, transport=httpx.MockTransport(fake_intervals(state)), auth=("API_KEY", "key"))
    return icu.Puller(cfg, client=client, today=lambda: dt.date(2026, 9, 8))


def test_recent_pull_brings_detail_streams_file_and_is_quiet_next_time(clean, cfg):
    state = {"activities": [RUN], "events": [{"id": 77, "category": "WORKOUT", "name": "Лёгкий бег", "start_date_local": "2026-09-09T00:00:00"}]}
    p = puller(cfg, state)
    out = p.run("recent")
    assert out["activities"] == 1 and out["wellness"] == 1 and out["events"] == 1
    detail = clean.execute("SELECT data FROM core.records WHERE kind = 'icu.activity' AND key = 'i9001'").fetchone()[0]
    assert detail["icu_intervals"] == [{"type": "WORK"}]
    streams = clean.execute("SELECT type, data FROM life.workout_streams").fetchall()
    assert streams == [("heartrate", [120, 130, 140])]
    f = clean.execute("SELECT data FROM life.raw WHERE kind = 'icu.file'").fetchone()[0]
    assert f["path"].endswith("i9001.fit.gz") and f["size"] == 7
    assert (cfg.blobs / "icu" / "i9001.fit.gz").read_bytes() == b"FITDATA"
    assert clean.execute("SELECT name FROM life.plan").fetchone()[0] == "Лёгкий бег"

    events_before = clean.execute("SELECT count(*) FROM core.events").fetchone()[0]
    calls_before = len(state["calls"])
    again = p.run("recent")
    assert sum(again.values()) == 0
    assert clean.execute("SELECT count(*) FROM core.events").fetchone()[0] == events_before
    # Карточка не изменилась — подробную, потоки и файл второй раз не тянем.
    assert not any("/activity/" in c for c in state["calls"][calls_before:])
    fresh = clean.execute("SELECT last_ok IS NOT NULL, note ? 'recent_at' FROM core.sources WHERE source = 'intervals'").fetchone()
    assert fresh == (True, True)


def test_removed_in_intervals_becomes_delete(clean, cfg):
    state = {"activities": [RUN], "events": [{"id": 77, "category": "NOTE", "name": "заметка", "start_date_local": "2026-09-07T00:00:00"}]}
    p = puller(cfg, state)
    p.run("recent")
    state["activities"], state["events"] = [], []
    p.run("recent")
    assert clean.execute("SELECT count(*) FROM life.workouts").fetchone()[0] == 0
    assert clean.execute("SELECT count(*) FROM life.plan").fetchone()[0] == 0
    ops = clean.execute("SELECT op FROM core.events WHERE kind = 'icu.activity' ORDER BY seq").fetchall()
    assert [o[0] for o in ops] == ["put", "del"]


def test_bad_key_is_recorded_as_source_error(clean, cfg):
    def handler(request):
        return httpx.Response(401, text="unauthorized")

    client = httpx.Client(base_url=icu.BASE, transport=httpx.MockTransport(handler))
    p = icu.Puller(cfg, client=client, today=lambda: dt.date(2026, 9, 8))
    try:
        p.run("recent")
    except RuntimeError as e:
        assert "ключ" in str(e)
    err = clean.execute("SELECT last_error FROM core.sources WHERE source = 'intervals'").fetchone()[0]
    assert "401" in err


def test_due_schedule():
    now = dt.datetime(2026, 9, 8, 10, 0, tzinfo=dt.timezone.utc)
    assert icu.due(None, now) == "full"
    assert icu.due({"full_at": "2026-09-01T00:00:00+00:00"}, now) == "deep"
    assert icu.due({"full_at": "x", "deep_at": now.isoformat()}, now) is None
    json.dumps(icu.due({}, now))
