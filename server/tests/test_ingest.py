from __future__ import annotations

import copy

from pravka_archive.ingest import ingest_batch, store_events


def test_contract_batch_goes_in_whole(clean, batch):
    status, res = ingest_batch(clean, batch, "sasha")
    assert status == 200, res
    assert res["rejected"] == []
    assert len(res["acked"]) == len(batch["events"])
    assert res["stored"] == len(batch["events"])
    n = clean.execute("SELECT count(*) FROM core.records WHERE NOT deleted").fetchone()[0]
    assert n == len(batch["events"])
    src = clean.execute("SELECT source, note->>'app' FROM core.sources").fetchall()
    assert src == [("phone:sasha-3f9a2c", "2.0.900")]


def test_replay_is_acked_and_not_doubled(clean, batch):
    ingest_batch(clean, batch, "sasha")
    status, res = ingest_batch(clean, batch, "sasha")
    assert status == 200
    assert len(res["acked"]) == len(batch["events"])
    assert res["stored"] == 0
    assert clean.execute("SELECT count(*) FROM core.events").fetchone()[0] == len(batch["events"])


def test_same_snapshot_with_new_eid_makes_no_event(clean, batch):
    ingest_batch(clean, batch, "sasha")
    again = copy.deepcopy(batch)
    for i, ev in enumerate(again["events"]):
        ev["eid"] = f"sasha-3f9a2c:{100 + i}"
        ev["seq"] = 100 + i
    _, res = ingest_batch(clean, again, "sasha")
    assert res["stored"] == 0
    assert len(res["acked"]) == len(again["events"])


def test_newer_wins_older_only_lands_in_journal(clean, batch):
    ev = next(e for e in batch["events"] if e["kind"] == "food.meal")
    newer = copy.deepcopy(ev)
    newer.update(eid="sasha-3f9a2c:50", seq=50, at="2026-09-07T09:00:00.000+03:00")
    newer["data"]["totals"]["kcal"] = 600
    older = copy.deepcopy(ev)
    older.update(eid="sasha-3f9a2c:40", seq=40, at="2026-09-07T08:00:00.000+03:00")
    older["data"]["totals"]["kcal"] = 999
    res = store_events(clean, "sasha-3f9a2c", [newer, older])
    assert res.stored == 2 and res.changed == 1
    kcal = clean.execute("SELECT data->'totals'->>'kcal' FROM core.records WHERE kind = 'food.meal'").fetchone()[0]
    assert kcal == "600"


def test_delete_hides_from_views_keeps_history(clean, batch, cfg):
    ingest_batch(clean, batch, "sasha")
    kill = {"eid": "sasha-3f9a2c:60", "seq": 60, "kind": "food.meal", "key": "f1757218205000", "op": "del",
            "at": "2026-09-07T22:00:00.000+03:00"}
    res = store_events(clean, "sasha-3f9a2c", [kill])
    assert res.changed == 1
    assert clean.execute("SELECT count(*) FROM life.meals").fetchone()[0] == 0
    deleted, kcal = clean.execute(
        "SELECT deleted, data->'totals'->>'kcal' FROM core.records WHERE kind = 'food.meal'"
    ).fetchone()
    assert deleted and kcal == "520"  # «что было» не теряется
    ops = [r[0] for r in clean.execute("SELECT op FROM life.history WHERE kind = 'food.meal' ORDER BY at")]
    assert ops == ["put", "del"]
    # Сказанное удалённого приёма из поиска уходит.
    assert clean.execute("SELECT count(*) FROM core.said_index WHERE kind = 'food.meal'").fetchone()[0] == 0


def test_bad_events_are_rejected_one_by_one(clean, batch):
    good = batch["events"][0]
    bad_eid = dict(good, eid="intervals:1")
    no_tz = dict(good, eid="sasha-3f9a2c:70", at="2026-09-07T10:00:00")
    bad_kind = dict(good, eid="sasha-3f9a2c:71", kind="Засечка")
    res = store_events(clean, "sasha-3f9a2c", [bad_eid, no_tz, bad_kind, good])
    assert [r["eid"] for r in res.rejected] == ["intervals:1", "sasha-3f9a2c:70", "sasha-3f9a2c:71"]
    assert res.acked == [good["eid"]]
    assert "пояс" in res.rejected[1]["why"]


def test_batch_level_refusals(clean, batch):
    assert ingest_batch(clean, dict(batch, profile="marianna"), "sasha")[0] == 403
    assert ingest_batch(clean, dict(batch, device="intervals"), "sasha")[0] == 400
    assert ingest_batch(clean, dict(batch, schema=2), "sasha")[0] == 400
    assert ingest_batch(clean, [], "sasha")[0] == 400
