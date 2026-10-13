"""Диктовки компьютера (13.10.2026, Правка 5.0, этап 5): «Диктовка» на ПК пишет каждый тейк
в архив теми же видами, что телефон, а телефон забирает их через /sync."""

from __future__ import annotations

import copy
import dataclasses
import json

import pytest
from starlette.testclient import TestClient

from pravka_archive import sync
from pravka_archive.app import asgi
from pravka_archive.ingest import ingest_batch

from conftest import ROOT

PHONE = "sasha-3f9a2c"
OLD_PHONE = "sasha-0a0b0c"
SVC = "svc-diktovka"


@pytest.fixture()
def contract() -> dict:
    return json.loads((ROOT / "contract" / "diktovka.json").read_text(encoding="utf-8"))


@pytest.fixture()
def dik(clean):
    token = sync.issue(clean, "diktovka", ["pravka.take", "pravka.clean"])
    return token, sync.service_of(clean, token)


def phone_batch(device: str, events: list[dict]) -> dict:
    return {"device": device, "profile": "sasha", "app": "3.0", "schema": 1, "events": events}


def phone_take(batch: dict, device: str, n: int, key: str, text: str) -> dict:
    ev = copy.deepcopy(next(e for e in batch["events"] if e["kind"] == "pravka.take"))
    ev.update(eid=f"{device}:{n}", seq=n, key=key)
    ev["data"]["text"] = text
    return ev


def test_contract_dictations_pass_the_check(contract):
    for ev in contract["events"]:
        assert sync.check_dictation(ev["kind"], ev["key"], ev["data"]) is None, ev["key"]


def test_check_dictation_refuses_foreign_keys_and_broken_fields(contract):
    take = next(e for e in contract["events"] if e["kind"] == "pravka.take")
    cl = next(e for e in contract["events"] if e["kind"] == "pravka.clean")
    # Телефонный ключ («<мс>-<отпечаток>») служба писать не может.
    assert "pc-" in sync.check_dictation("pravka.take", "1791889200123-3fa1c2d4", take["data"])
    bad = copy.deepcopy(take["data"]); bad["origin"] = "phone"
    assert "origin" in sync.check_dictation("pravka.take", take["key"], bad)
    bad = copy.deepcopy(take["data"]); bad["id"] = 1
    assert "id" in sync.check_dictation("pravka.take", take["key"], bad)
    bad = copy.deepcopy(take["data"]); bad["audio_ms"] = "12 с"
    assert "audio_ms" in sync.check_dictation("pravka.take", take["key"], bad)
    bad = copy.deepcopy(take["data"]); bad["status"] = "как-то"
    assert "status" in sync.check_dictation("pravka.take", take["key"], bad)
    bad = copy.deepcopy(cl["data"]); bad["take"] = "pc-1"
    assert "take" in sync.check_dictation("pravka.clean", cl["key"], bad)
    bad = copy.deepcopy(cl["data"]); bad["cost_usd"] = -1
    assert "cost_usd" in sync.check_dictation("pravka.clean", cl["key"], bad)
    bad = copy.deepcopy(cl["data"]); bad["at"] = "2026-10-13T14:00:00"
    assert "at" in sync.check_dictation("pravka.clean", cl["key"], bad)


def test_service_writes_pc_dictations_into_the_same_views(clean, contract, dik):
    _, who = dik
    status, res = ingest_batch(clean, contract, "sasha", service=who)
    assert status == 200, res
    assert res["rejected"] == [] and len(res["acked"]) == 3
    rows = clean.execute(
        "SELECT id, origin, status, ok, final IS NOT NULL, audio, mic FROM life.dictations ORDER BY id"
    ).fetchall()
    assert rows == [
        ("pc-1791889200123", "pc", "pasted", True, True, None, "компьютер"),
        ("pc-1791889500000", "pc", "empty", False, False, None, "компьютер"),
    ]
    c = clean.execute("SELECT id, origin, take_id, style, model, cost_usd FROM life.cleanups").fetchone()
    assert c[:5] == ("pc-1791889200123", "pc", "pc-1791889200123", "clean", "claude-sonnet-5-5")
    assert float(c[5]) == pytest.approx(0.00307)
    # Свежесть — служба, а не телефон.
    assert clean.execute("SELECT 1 FROM core.sources WHERE source = 'svc:diktovka'").fetchone()
    # Сказанное с компа ищется вместе с телефонным.
    assert clean.execute(
        "SELECT count(*) FROM life.said WHERE domain = 'правка' AND ref = 'pc-1791889200123'"
    ).fetchone()[0] == 1


def test_phone_dictations_get_origin_phone(clean, batch):
    ingest_batch(clean, batch, "sasha")
    assert clean.execute("SELECT DISTINCT origin FROM life.dictations").fetchall() == [("phone",)]
    assert clean.execute("SELECT DISTINCT origin, take_id FROM life.cleanups").fetchall() == [("phone", None)]


def test_service_cannot_touch_phone_dictations(clean, batch, contract, dik):
    _, who = dik
    ingest_batch(clean, phone_batch(PHONE, [phone_take(batch, PHONE, 1, "1760000000000-aaaabbbb", "сказано телефоном")]), "sasha")
    take = copy.deepcopy(contract["events"][0])
    take.update(eid=f"{SVC}:5", seq=5, key="1760000000000-aaaabbbb")
    gone = {"eid": f"{SVC}:6", "seq": 6, "kind": "pravka.take", "key": "1760000000000-aaaabbbb", "op": "del",
            "at": "2026-10-13T15:00:00.000+03:00"}
    meal = copy.deepcopy(next(e for e in batch["events"] if e["kind"] == "food.meal"))
    meal.update(eid=f"{SVC}:7", seq=7)
    _, res = ingest_batch(clean, {**contract, "events": [take, gone, meal]}, "sasha", service=who)
    assert res["acked"] == []
    whys = [r["why"] for r in res["rejected"]]
    assert "pc-" in whys[0] and "pc-" in whys[1] and "только pravka.clean, pravka.take" in whys[2]
    assert clean.execute("SELECT text, origin FROM life.dictations").fetchall() == [("сказано телефоном", "phone")]


def test_phone_pulls_only_pc_dictations(clean, batch, contract, dik):
    """Свои диктовки телефону назад не нужны — и диктовки прежнего устройства тоже (переустановка)."""
    _, who = dik
    ingest_batch(clean, phone_batch(PHONE, [phone_take(batch, PHONE, 1, "1760000000001-aaaa0001", "своё")]), "sasha")
    ingest_batch(clean, phone_batch(OLD_PHONE, [phone_take(batch, OLD_PHONE, 1, "1750000000001-bbbb0001", "прежнее")]), "sasha")
    ingest_batch(clean, contract, "sasha", service=who)

    got = sync.pull(clean, "", ["pravka.take", "pravka.clean"], None, PHONE)
    assert sorted((r["kind"], r["key"]) for r in got["records"]) == [
        ("pravka.clean", "pc-1791889200123"),
        ("pravka.take", "pc-1791889200123"),
        ("pravka.take", "pc-1791889500000"),
    ]
    assert all(r["device"] == SVC and r["data"]["origin"] == "pc" for r in got["records"])
    # own=true не возвращает телефонные: по этим видам — только службы.
    assert len(sync.pull(clean, "", ["pravka.take", "pravka.clean"], None, PHONE, own=True)["records"]) == 3

    # Курсор: тихо — пусто; новая диктовка компа — только она.
    clean.execute("UPDATE core.events SET received_at = received_at - interval '10 minutes'")
    assert sync.pull(clean, "", ["pravka.take"], got["cursor"], PHONE)["records"] == []
    fresh = copy.deepcopy(contract["events"][0])
    fresh.update(eid=f"{SVC}:9", seq=9, key="pc-1791896400000")
    fresh["data"].update(id=1791896400000, text="ещё одна")
    ingest_batch(clean, {**contract, "events": [fresh]}, "sasha", service=who)
    later = sync.pull(clean, "", ["pravka.take", "pravka.clean"], got["cursor"], PHONE)
    assert [(r["kind"], r["key"]) for r in later["records"]] == [("pravka.take", "pc-1791896400000")]
    # Еда по-прежнему ходит в обе стороны — общий запрет её не задел.
    meal = copy.deepcopy(next(e for e in batch["events"] if e["kind"] == "food.meal"))
    meal.update(eid=f"{OLD_PHONE}:2", seq=2)
    ingest_batch(clean, phone_batch(OLD_PHONE, [meal]), "sasha")
    assert [r["key"] for r in sync.pull(clean, "", ["food.meal"], None, PHONE)["records"]] == [meal["key"]]


@pytest.fixture()
def client(cfg, clean):
    with TestClient(asgi(dataclasses.replace(cfg, listen_host="0.0.0.0")), base_url="http://192.168.1.77") as c:
        yield c


def test_routes_service_ingest_and_phone_sync(client, clean, contract):
    token = sync.issue(clean, "diktovka", ["pravka.take", "pravka.clean"])
    svc = {"authorization": f"Bearer {token}"}
    phone = {"authorization": "Bearer " + "t" * 64}
    r = client.post("/ingest", headers=svc, json=contract)
    assert r.status_code == 200 and len(r.json()["acked"]) == 3, r.text
    # Тот же снимок ещё раз (досыл после обрыва) — квитанция есть, событий нет.
    r = client.post("/ingest", headers=svc, json=contract)
    assert r.status_code == 200 and len(r.json()["acked"]) == 3 and r.json()["stored"] == 0
    r = client.post("/sync", headers=phone, json={"device": PHONE, "profile": "sasha", "kinds": ["pravka.take", "pravka.clean"]})
    assert r.status_code == 200, r.text
    assert len(r.json()["records"]) == 3
    # Служба Диктовки забирать еду не может — только свои виды.
    r = client.post("/sync", headers=svc, json={"device": SVC, "profile": "sasha", "kinds": ["food.meal"]})
    assert r.status_code == 403
    r = client.post("/sync", headers=svc, json={"device": SVC, "profile": "sasha"})
    assert r.status_code == 200 and r.json()["records"] == []
