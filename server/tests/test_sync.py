"""Обратная дорога (13.10.2026): еда — правда архива, пишут телефон и служба компа, каждый забирает чужое."""

from __future__ import annotations

import copy
import dataclasses
import json

import pytest
from starlette.testclient import TestClient

from pravka_archive import sync
from pravka_archive.app import asgi
from pravka_archive.ingest import ingest_batch

PHONE = "sasha-3f9a2c"
SVC = "svc-eda"


def meal_event(batch: dict, eid: str, key_id: int, at: str, kcal: int = 520, op: str = "put") -> dict:
    ev = copy.deepcopy(next(e for e in batch["events"] if e["kind"] == "food.meal"))
    ev.update(eid=eid, seq=int(eid.rsplit(":", 1)[1]), key=f"f{key_id}", at=at, op=op)
    if op == "put":
        ev["data"]["id"] = key_id
        ev["data"]["items"] = ev["data"]["items"][:1]
        ev["data"]["items"][0]["kcal"] = kcal
        it = ev["data"]["items"][0]
        ev["data"]["totals"] = {"kcal": kcal, "protein_g": it["protein_g"], "fat_g": it["fat_g"],
                                "carbs_g": it["carbs_g"], "fiber_g": it["fiber_g"], "grams": it["grams"]}
    else:
        ev.pop("data", None)
    return ev


def svc_batch(events: list[dict]) -> dict:
    return {"device": SVC, "profile": "sasha", "app": "eda 1", "schema": 1, "events": events}


@pytest.fixture()
def eda(clean):
    token = sync.issue(clean, "eda", ["food.meal"])
    return token, sync.service_of(clean, token)


def test_token_is_hashed_and_revocable(clean):
    token = sync.issue(clean, "eda", ["food.meal"])
    assert clean.execute("SELECT count(*) FROM core.tokens WHERE sha256 = %s", (token,)).fetchone()[0] == 0
    assert sync.service_of(clean, token) == ("eda", frozenset({"food.meal"}))
    assert sync.service_of(clean, "чужой") is None
    again = sync.issue(clean, "eda", ["food.meal"])
    assert sync.service_of(clean, token) is None and sync.service_of(clean, again)
    assert sync.revoke(clean, "eda")
    assert sync.service_of(clean, again) is None
    with pytest.raises(sync.SyncError):
        sync.issue(clean, "eda", ["money.entry"])


def test_service_writes_only_its_kinds_from_its_device(clean, batch, eda):
    _, who = eda
    good = meal_event(batch, f"{SVC}:1", 1760000000001, "2026-10-13T12:00:00.000+03:00")
    money = copy.deepcopy(next(e for e in batch["events"] if e["kind"] == "money.entry"))
    money.update(eid=f"{SVC}:2", seq=2)
    status, res = ingest_batch(clean, svc_batch([good, money]), "sasha", service=who)
    assert status == 200
    assert res["acked"] == [f"{SVC}:1"]
    assert "только food.meal" in res["rejected"][0]["why"]
    assert clean.execute("SELECT device FROM core.records WHERE key = 'f1760000000001'").fetchone()[0] == SVC
    assert clean.execute("SELECT 1 FROM core.sources WHERE source = 'svc:eda'").fetchone()
    assert not clean.execute("SELECT 1 FROM core.sources WHERE source = 'phone:svc-eda'").fetchone()

    wrong = dict(svc_batch([good]), device="sasha-3f9a2c")
    assert ingest_batch(clean, wrong, "sasha", service=who)[0] == 403
    # Телефонный токен под видом службы — нельзя.
    assert ingest_batch(clean, svc_batch([good]), "sasha")[0] == 403


def test_broken_meal_from_service_is_refused_and_views_survive(clean, batch, eda):
    _, who = eda
    bad = meal_event(batch, f"{SVC}:3", 1760000000003, "2026-10-13T12:00:00.000+03:00")
    bad["data"]["items"][0]["kcal"] = "много"
    sums = meal_event(batch, f"{SVC}:4", 1760000000004, "2026-10-13T12:00:00.000+03:00")
    sums["data"]["totals"]["kcal"] = 9999
    key = meal_event(batch, f"{SVC}:5", 1760000000005, "2026-10-13T12:00:00.000+03:00")
    key["key"] = "f1"
    _, res = ingest_batch(clean, svc_batch([bad, sums, key]), "sasha", service=who)
    whys = [r["why"] for r in res["rejected"]]
    assert "kcal — число" in whys[0] and "не сходится" in whys[1] and "f<id>" in whys[2]
    assert res["acked"] == []
    assert clean.execute("SELECT count(*) FROM life.meals").fetchone()[0] == 0


def test_check_meal_accepts_contract_meal(batch):
    ev = next(e for e in batch["events"] if e["kind"] == "food.meal")
    assert sync.check_meal(ev["key"], ev["data"]) is None


def test_phone_gets_pc_meals_and_pc_gets_phone_meals(clean, batch, eda):
    _, who = eda
    phone_meal = meal_event(batch, f"{PHONE}:10", 1760000000010, "2026-10-13T09:00:00.000+03:00")
    ingest_batch(clean, {"device": PHONE, "profile": "sasha", "app": "3.0", "schema": 1, "events": [phone_meal]}, "sasha")
    pc_meal = meal_event(batch, f"{SVC}:11", 1760000000011, "2026-10-13T13:00:00.000+03:00", kcal=905)
    ingest_batch(clean, svc_batch([pc_meal]), "sasha", service=who)

    first = sync.pull(clean, "", ["food.meal"], None, PHONE)
    assert [r["key"] for r in first["records"]] == ["f1760000000011"]
    assert first["records"][0]["data"]["totals"]["kcal"] == 905
    assert first["records"][0]["device"] == SVC
    pc_view = sync.pull(clean, "", ["food.meal"], None, SVC)
    assert [r["key"] for r in pc_view["records"]] == ["f1760000000010"]
    both = sync.pull(clean, "", ["food.meal"], None, PHONE, own=True)
    assert len(both["records"]) == 2

    # Курсор — с запасом назад: повтор того же безвреден, а позднее событие не теряется.
    clean.execute("UPDATE core.events SET received_at = received_at - interval '10 minutes'")
    quiet = sync.pull(clean, "", ["food.meal"], first["cursor"], PHONE)
    assert quiet["records"] == []

    # Комп убрал приём — телефон видит del; приём, где последним писал телефон, ему не отдаётся.
    gone = meal_event(batch, f"{SVC}:12", 1760000000011, "2026-10-13T18:00:00.000+03:00", op="del")
    ingest_batch(clean, svc_batch([gone]), "sasha", service=who)
    later = sync.pull(clean, "", ["food.meal"], first["cursor"], PHONE)
    assert [(r["key"], r["op"]) for r in later["records"]] == [("f1760000000011", "del")]
    assert "data" not in later["records"][0]
    assert sync.pull(clean, "", ["food.meal"], None, PHONE)["records"] == []


def test_phone_edit_of_pc_meal_wins_by_time(clean, batch, eda):
    _, who = eda
    pc_meal = meal_event(batch, f"{SVC}:20", 1760000000020, "2026-10-13T13:00:00.000+03:00", kcal=905)
    ingest_batch(clean, svc_batch([pc_meal]), "sasha", service=who)
    edit = meal_event(batch, f"{PHONE}:21", 1760000000020, "2026-10-13T13:05:00.000+03:00", kcal=700)
    ingest_batch(clean, {"device": PHONE, "profile": "sasha", "app": "3.0", "schema": 1, "events": [edit]}, "sasha")
    assert clean.execute("SELECT kcal FROM life.meals WHERE id = 'f1760000000020'").fetchone()[0] == 700
    pc = sync.pull(clean, "", ["food.meal"], None, SVC)
    assert pc["records"][0]["data"]["totals"]["kcal"] == 700
    # Телефон, вернувший тот же снимок, событий не плодит.
    again = copy.deepcopy(edit)
    again.update(eid=f"{PHONE}:22", seq=22, at="2026-10-13T13:06:00.000+03:00")
    _, res = ingest_batch(clean, {"device": PHONE, "profile": "sasha", "app": "3.0", "schema": 1, "events": [again]}, "sasha")
    assert res["stored"] == 0


def test_bad_since_is_refused(clean):
    with pytest.raises(sync.SyncError):
        sync.pull(clean, "", ["food.meal"], "вчера", PHONE)
    with pytest.raises(sync.SyncError):
        sync.pull(clean, "", ["food.meal"], "2026-10-13T10:00:00", PHONE)


@pytest.fixture()
def client(cfg, clean):
    with TestClient(asgi(dataclasses.replace(cfg, listen_host="0.0.0.0")), base_url="http://192.168.1.77") as c:
        yield c


def test_sync_route_tokens_and_scopes(client, clean, batch):
    token = sync.issue(clean, "eda", ["food.meal"])
    phone = {"authorization": "Bearer " + "t" * 64}
    svc = {"authorization": f"Bearer {token}"}
    assert client.post("/sync", json={"device": PHONE, "profile": "sasha"}).status_code == 401

    pc_meal = meal_event(batch, f"{SVC}:30", 1760000000030, "2026-10-13T13:00:00.000+03:00")
    r = client.post("/ingest", headers=svc, json=svc_batch([pc_meal]))
    assert r.status_code == 200 and r.json()["acked"] == [f"{SVC}:30"], r.text

    r = client.post("/sync", headers=phone, json={"device": PHONE, "profile": "sasha", "kinds": ["food.meal"]})
    assert r.status_code == 200, r.text
    assert [x["key"] for x in r.json()["records"]] == ["f1760000000030"]
    assert r.json()["cursor"]

    assert client.post("/sync", headers=phone, json={"device": PHONE, "profile": "sasha", "kinds": ["money.entry"]}).status_code == 403
    assert client.post("/sync", headers=phone, json={"device": PHONE, "profile": "кто-то"}).status_code == 403
    assert client.post("/sync", headers=svc, json={"device": PHONE, "profile": "sasha"}).status_code == 403
    r = client.post("/sync", headers=svc, json={"device": SVC, "profile": "sasha", "since": "не время"})
    assert r.status_code == 400 and "since" in r.text
    r = client.post("/sync", headers=svc, json={"device": SVC, "profile": "sasha"})
    assert r.status_code == 200 and r.json()["records"] == []
    # Служба с чужим видом в /ingest — отказ событию, не пачке.
    money = copy.deepcopy(next(e for e in batch["events"] if e["kind"] == "money.entry"))
    money.update(eid=f"{SVC}:31", seq=31)
    r = client.post("/ingest", headers=svc, content=json.dumps(svc_batch([money])))
    assert r.status_code == 200 and r.json()["rejected"]
