"""Звук тейков телефона — в «Слух» через архив (contract/sluh.json, 20.11.2026).

«Слух» здесь поддельный (httpx.MockTransport): проверяем, что архив пускает только телефон,
передаёт пути, смещения и тело как есть, а падение службы превращает в 503, по которому телефон
берёт текст Google.
"""

from __future__ import annotations

import dataclasses
import json
from pathlib import Path

import httpx
import pytest
from starlette.testclient import TestClient

from pravka_archive import sluh, sync
from pravka_archive.app import asgi

CONTRACT = json.loads((Path(__file__).resolve().parents[1] / "contract" / "sluh.json").read_text(encoding="utf-8"))


class FakeSluh:
    """Как настоящий: копит звук по смещению, end — текст, нет службы — обрыв соединения."""

    def __init__(self) -> None:
        self.calls: list[tuple[str, dict, int]] = []
        self.have: dict[str, int] = {}
        self.down = False
        self.crash = False

    def __call__(self, request: httpx.Request) -> httpx.Response:
        if self.down:
            raise httpx.ConnectError("нет службы", request=request)
        if self.crash:
            return httpx.Response(500, text="Internal Server Error")
        parts = request.url.path.strip("/").split("/")
        assert parts[0] == "take" and len(parts) == 3, request.url.path
        take, action = parts[1], parts[2]
        q = dict(request.url.params)
        body = request.read()
        self.calls.append((action, q, len(body)))
        have = self.have.get(take, 0)
        if action == "chunk":
            off = int(q["off"])
            if off > have:
                return httpx.Response(409, json={"have": have, "error": f"у меня {have} байт — шли с этого места"})
            self.have[take] = max(have, off + len(body))
            return httpx.Response(200, json={"have": self.have[take]})
        if action == "end":
            if take not in self.have:
                return httpx.Response(404, json={"error": "такого тейка нет"})
            if int(q["total"]) > have:
                return httpx.Response(409, json={"have": have, "error": "звук ещё не весь"})
            return httpx.Response(200, json={"text": "иван привет", "sec": have / 32000, "ms": 40, "regions": 1})
        if action == "drop":
            self.have.pop(take, None)
            return httpx.Response(200, json={"ok": True})
        return httpx.Response(404, json={"error": "нет такого"})


@pytest.fixture
def fake(monkeypatch):
    f = FakeSluh()
    monkeypatch.setattr(sluh, "transport", httpx.MockTransport(f))
    monkeypatch.setattr(sluh, "_client", None)
    yield f
    monkeypatch.setattr(sluh, "_client", None)


@pytest.fixture
def client(cfg, clean, fake):
    app_cfg = dataclasses.replace(cfg, sluh_url="http://127.0.0.1:8110")
    with TestClient(asgi(app_cfg), base_url="http://192.168.1.77") as c:
        yield c


def phone(cfg) -> dict:
    return {"authorization": f"Bearer {cfg.ingest_token}", "content-type": "application/octet-stream"}


def test_take_goes_through_in_pieces_and_ends_with_text(client, cfg, fake):
    h = phone(cfg)
    sec = b"\x01\x00" * 16000  # секунда звука — 32000 байт
    r = client.post("/asr/chunk?take=take_1795180000000&off=0", content=sec, headers=h)
    assert r.status_code == 200 and r.json() == {"have": 32000}
    r = client.post("/asr/chunk?take=take_1795180000000&off=32000", content=sec, headers=h)
    assert r.json() == {"have": 64000}
    # повтор после обрыва — с перекрытием, служба отрежет; архив передаёт как есть
    r = client.post("/asr/chunk?take=take_1795180000000&off=32000", content=sec, headers=h)
    assert r.status_code == 200
    # дыра — 409 со смещением, с которого слать
    r = client.post("/asr/chunk?take=take_1795180000000&off=96000", content=sec, headers=h)
    assert r.status_code == 409 and r.json()["have"] == 64000
    r = client.post("/asr/end?take=take_1795180000000&total=64000", headers=h)
    assert r.status_code == 200 and r.json()["text"] == "иван привет"
    assert [c[0] for c in fake.calls] == ["chunk", "chunk", "chunk", "chunk", "end"]
    assert fake.calls[0] == ("chunk", {"off": "0"}, 32000)
    assert fake.calls[-1] == ("end", {"total": "64000"}, 0)


def test_drop_and_unknown_take(client, cfg, fake):
    h = phone(cfg)
    assert client.post("/asr/end?take=nope&total=0", headers=h).status_code == 404
    client.post("/asr/chunk?take=t1&off=0", content=b"\x00\x00" * 10, headers=h)
    assert client.post("/asr/drop?take=t1", headers=h).json() == {"ok": True}


def test_only_phone_token(client, clean, cfg):
    sec = b"\x00\x00" * 100
    assert client.post("/asr/chunk?take=t&off=0", content=sec).status_code == 401
    assert client.post("/asr/chunk?take=t&off=0", content=sec, headers={"authorization": "Bearer not-the-phone-token"}).status_code == 401
    token = sync.issue(clean, "diktovka", ["pravka.take", "pravka.clean"])
    r = client.post("/asr/chunk?take=t&off=0", content=sec, headers={"authorization": f"Bearer {token}"})
    assert r.status_code == 403


def test_bad_requests_stop_at_archive(client, cfg, fake):
    h = phone(cfg)
    assert client.post("/asr/chunk?take=../etc&off=0", content=b"\x00\x00", headers=h).status_code == 400
    assert client.post("/asr/chunk?take=" + "x" * 81 + "&off=0", content=b"\x00\x00", headers=h).status_code == 400
    assert client.post("/asr/chunk?take=t&off=-2", content=b"\x00\x00", headers=h).status_code == 400
    assert client.post("/asr/end?take=t", headers=h).status_code == 400
    big = b"\x00" * (sluh.MAX_CHUNK + 2)
    assert client.post("/asr/chunk?take=t&off=0", content=big, headers=h).status_code == 413
    assert fake.calls == []


def test_service_down_or_broken_is_503(client, cfg, fake):
    h = phone(cfg)
    fake.down = True
    r = client.post("/asr/chunk?take=t&off=0", content=b"\x00\x00", headers=h)
    assert r.status_code == 503 and "Слух не отвечает" in r.json()["error"]
    fake.down, fake.crash = False, True
    r = client.post("/asr/end?take=t&total=2", headers=h)
    assert r.status_code == 503 and "не JSON" in r.json()["error"]


def test_contract_names_the_same_paths():
    assert CONTRACT["phone"]["chunk"]["request"].startswith("POST /asr/chunk?take=")
    assert CONTRACT["phone"]["end"]["request"].startswith("POST /asr/end?take=")
    assert CONTRACT["phone"]["drop"]["request"].startswith("POST /asr/drop?take=")
    assert sluh.TAKE_ID.match("take_1795180000000")


def test_phone_take_keeps_google_and_timing_in_view(clean, batch):
    """Тейк с итогом компа: текст Google о том же звуке и замер видны в life.dictations (20.11.2026)."""
    import copy

    from pravka_archive.ingest import ingest_batch

    ev = copy.deepcopy(next(e for e in batch["events"] if e["kind"] == "pravka.take"))
    ev["data"].update(engine="gigaam-pc", text="иван привет скажи", google="иван привет",
                      pc_note="комп: 2,0 с звука, итог 410 мс",
                      timing={"v": 1, "line": "pc", "final": "pc", "word_lag_med": 420, "rtt_med": 180,
                              "g_words": [600, 1000], "pc_words": [1020, 1400, 1800]})
    status, _ = ingest_batch(clean, {**batch, "events": [ev]}, "sasha")
    assert status == 200
    row = clean.execute(
        "SELECT engine, google, pc_note, (timing->>'word_lag_med')::int, jsonb_array_length(timing->'pc_words') "
        "FROM life.dictations"
    ).fetchone()
    assert row == ("gigaam-pc", "иван привет", "комп: 2,0 с звука, итог 410 мс", 420, 3)
