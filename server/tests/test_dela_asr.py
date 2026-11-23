"""Дверь «Слуха» в Делах (23.11.2026): звук тейков «П» телефонов семьи — на распознавание компу.

У телефона владельца дверь — в архиве (test_sluh.py), а у телефонов семьи архива нет: им комп так и не включался.
Здесь — та же передача `pravka_archive.sluh.forward` своим токеном устройства Дел, и только тем, у кого флаг семьи.
«Слух» поддельный (httpx.MockTransport) — тот же, что у теста архива.
"""

from __future__ import annotations

import httpx
import pytest
from starlette.testclient import TestClient

from pravka_archive import sluh
from pravka_dela import api, db, tokens
from pravka_dela.config import Config
from test_dela_schema import dela  # noqa: F401  (фикстура)
from test_sluh import FakeSluh


@pytest.fixture
def fake(monkeypatch):
    f = FakeSluh()
    monkeypatch.setattr(sluh, "transport", httpx.MockTransport(f))
    monkeypatch.setattr(sluh, "_client", None)
    yield f
    monkeypatch.setattr(sluh, "_client", None)


@pytest.fixture
def client(dela):
    # Семья — как у владельца после миграции 8: он сам и член семьи (marianna); natasha — команда, не семья.
    with db.session(dela, "system", "t") as c:
        c.execute("UPDATE crm.users SET family = (id IN ('sasha', 'marianna'))")
    return TestClient(api.build(Config(db_url=dela, sluh_url="http://sluh.test")))


def _h(url, user, kind="device", name=None):
    return {"Authorization": f"Bearer {tokens.issue(url, user, kind, name or f'{user}-phone')}"}


def test_family_phone_take_goes_to_sluh(dela, client, fake):
    h = _h(dela, "marianna")
    pcm = b"\x01\x00" * 16000
    r = client.post("/api/asr/chunk?take=take_42&off=0", content=pcm, headers=h)
    assert r.status_code == 200 and r.json()["have"] == 32000
    r = client.post("/api/asr/end?take=take_42&total=32000", headers=h)
    assert r.status_code == 200 and r.json()["text"] == "иван привет"
    assert [c[0] for c in fake.calls] == ["chunk", "end"]
    assert fake.calls[0][1] == {"off": "0"} and fake.calls[0][2] == len(pcm)


def test_owner_phone_may_use_it_too(dela, client, fake):
    r = client.post("/api/asr/chunk?take=take_7&off=0", content=b"\x00" * 3200, headers=_h(dela, "sasha"))
    assert r.status_code == 200


def test_team_services_and_strangers_are_refused(dela, client, fake):
    team = client.post("/api/asr/chunk?take=take_1&off=0", content=b"\x00" * 3200, headers=_h(dela, "natasha"))
    assert team.status_code == 403 and "только семье" in team.json()["error"]
    svc = client.post("/api/asr/chunk?take=take_1&off=0", content=b"\x00" * 3200,
                      headers=_h(dela, "sasha", kind="service", name="meetings"))
    assert svc.status_code == 403 and "только телефону" in svc.json()["error"]
    none = client.post("/api/asr/chunk?take=take_1&off=0", content=b"\x00" * 3200)
    assert none.status_code == 401
    assert fake.calls == []                       # ни один кусок до «Слуха» не дошёл


def test_sluh_down_is_503_so_phone_takes_google(dela, client, fake):
    fake.down = True
    r = client.post("/api/asr/chunk?take=take_9&off=0", content=b"\x00" * 3200, headers=_h(dela, "marianna"))
    assert r.status_code == 503
