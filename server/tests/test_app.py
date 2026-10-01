"""Сквозь весь сервис: вход Claude по OAuth, вызов инструмента, приём с телефона."""

from __future__ import annotations

import base64
import dataclasses
import gzip
import hashlib
import json
import re
import secrets
from urllib.parse import parse_qs, urlparse

import pytest
from starlette.testclient import TestClient

from pravka_archive.app import build

CALLBACK = "https://claude.ai/api/mcp/auth_callback"
MCP_HEADERS = {"accept": "application/json, text/event-stream", "content-type": "application/json"}


@pytest.fixture()
def client(cfg, clean):
    app_cfg = dataclasses.replace(cfg, listen_host="0.0.0.0")
    mcp, _ = build(app_cfg)
    with TestClient(mcp.streamable_http_app(), base_url="http://192.168.1.10:8090") as c:
        yield c


def login(client) -> dict:
    meta = client.get("/.well-known/oauth-authorization-server").json()
    assert meta["issuer"].rstrip("/") == "https://archive.example.keenetic.pro:8443"
    assert meta["authorization_endpoint"].startswith("https://archive.example.keenetic.pro:8443/")

    reg = client.post("/register", json={
        "redirect_uris": [CALLBACK], "token_endpoint_auth_method": "none",
        "grant_types": ["authorization_code", "refresh_token"], "response_types": ["code"], "client_name": "Claude",
    })
    assert reg.status_code == 201, reg.text
    client_id = reg.json()["client_id"]

    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    r = client.get("/authorize", params={
        "response_type": "code", "client_id": client_id, "redirect_uri": CALLBACK, "state": "st-42",
        "code_challenge": challenge, "code_challenge_method": "S256", "scope": "life",
    }, follow_redirects=False)
    assert r.status_code == 302, r.text
    login_url = urlparse(r.headers["location"])
    assert login_url.netloc == "archive.example.keenetic.pro:8443" and login_url.path == "/login"
    txn = parse_qs(login_url.query)["txn"][0]

    page = client.get(f"/login?txn={txn}")
    assert page.status_code == 200 and "пароль архива" in page.text
    wrong = client.post("/login", data={"txn": txn, "password": "не тот"}, follow_redirects=False)
    assert wrong.status_code == 401 and "Пароль не тот" in wrong.text
    ok = client.post("/login", data={"txn": txn, "password": "верный-пароль-архива"}, follow_redirects=False)
    assert ok.status_code == 302
    back = urlparse(ok.headers["location"])
    assert f"{back.scheme}://{back.netloc}{back.path}" == CALLBACK
    q = parse_qs(back.query)
    assert q["state"] == ["st-42"]

    tok = client.post("/token", data={
        "grant_type": "authorization_code", "code": q["code"][0], "redirect_uri": CALLBACK,
        "client_id": client_id, "code_verifier": verifier,
    })
    assert tok.status_code == 200, tok.text
    body = tok.json()
    body["client_id"] = client_id
    return body


def rpc(client, token: str, method: str, params: dict | None = None, rid: int = 1):
    headers = dict(MCP_HEADERS, authorization=f"Bearer {token}", **{"mcp-protocol-version": "2025-06-18"})
    r = client.post("/mcp", headers=headers, json={"jsonrpc": "2.0", "id": rid, "method": method, "params": params or {}})
    assert r.status_code == 200, r.text
    return r.json()


def test_claude_logs_in_and_reads_a_day(client, batch):
    tok = login(client)
    # Телефон кладёт пачку своим токеном.
    r = client.post("/ingest", headers={"authorization": "Bearer " + "t" * 64, "content-encoding": "gzip",
                                         "content-type": "application/json"},
                    content=gzip.compress(json.dumps(batch).encode()))
    assert r.status_code == 200, r.text
    assert len(r.json()["acked"]) == len(batch["events"])

    init = rpc(client, tok["access_token"], "initialize", {
        "protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "test", "version": "1"}})
    assert init["result"]["serverInfo"]["name"] == "Архив Правки"
    assert "1440" in init["result"]["instructions"]
    names = {t["name"] for t in rpc(client, tok["access_token"], "tools/list", rid=2)["result"]["tools"]}
    assert names == {"schema", "sql", "search", "day"}
    out = rpc(client, tok["access_token"], "tools/call", {"name": "day", "arguments": {"date": "2026-09-07"}}, rid=3)
    text = out["result"]["content"][0]["text"]
    assert "Лента (1440 мин из 1440" in text

    # Обновление токена: старый refresh больше не годится.
    fresh = client.post("/token", data={"grant_type": "refresh_token", "refresh_token": tok["refresh_token"], "client_id": tok["client_id"]})
    assert fresh.status_code == 200, fresh.text
    again = client.post("/token", data={"grant_type": "refresh_token", "refresh_token": tok["refresh_token"], "client_id": tok["client_id"]})
    assert again.status_code == 400
    assert rpc(client, fresh.json()["access_token"], "tools/list", rid=4)["result"]["tools"]


def test_no_token_no_life(client):
    r = client.post("/mcp", headers=MCP_HEADERS, json={"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}})
    assert r.status_code == 401
    assert "resource_metadata" in r.headers.get("www-authenticate", "")
    r = client.post("/mcp", headers=dict(MCP_HEADERS, authorization="Bearer forged-token"),
                    json={"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}})
    assert r.status_code == 401


def test_only_claude_may_register(client):
    reg = client.post("/register", json={"redirect_uris": ["https://evil.example/cb"], "token_endpoint_auth_method": "none"})
    assert reg.status_code == 400
    assert "claude.ai" in reg.text


def test_ingest_needs_phone_token(client, batch):
    assert client.post("/ingest", json=batch).status_code == 401
    assert client.post("/ingest", headers={"authorization": "Bearer " + "x" * 64}, json=batch).status_code == 401
    r = client.post("/ingest", headers={"authorization": "Bearer " + "t" * 64}, content=b"{not json")
    assert r.status_code == 400 and re.search("JSON", r.text)
    assert client.get("/health").json()["service"] == "pravka-archive"
