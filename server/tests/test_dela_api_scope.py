"""Узкие токены служб — белым списком путей (аудит 15.10.2026, №4).

До того узкие токены «Денег», «Еды», «Спорта» и «Дома» сужала строка в синке, операциях, видах и настройках, а пути без
неё (разбор, правка словами, карточка дела по номеру, /api/me) были им открыты с правами владельца. Здесь — перебор ВСЕХ
маршрутов приложения: новый путь, забытый в api.SCOPED_PATHS, тест поймает сам.
"""

from __future__ import annotations

import pytest
from starlette.routing import Route
from starlette.testclient import TestClient

from pravka_dela import api, db, store, tokens
from pravka_dela.config import Config
from test_dela_schema import db_today, dela  # noqa: F401  (фикстура и помощник)

NARROW = ("dengi", "eda", "sport", "dom")
FILL = {"{name}": "svod", "{ref}": "1", "{job}": "nojob"}


def _api_routes(app):
    for r in app.routes:
        if isinstance(r, Route) and r.path.startswith("/api/"):
            for m in sorted((r.methods or {"GET"}) - {"HEAD"}):
                yield m, r.path


def _url(path: str) -> str:
    for k, v in FILL.items():
        path = path.replace(k, v)
    return path


@pytest.fixture
def client(dela):
    # Без ключа Claude: даже если дверь не закроется, разбор ответит 422, а не пойдёт в Claude.
    return TestClient(api.build(Config(db_url=dela)))


def _h(url, name, kind="service", user="sasha"):
    return {"Authorization": f"Bearer {tokens.issue(url, user, kind, name)}"}


def test_every_path_outside_whitelist_is_closed_to_narrow_tokens(dela, client):
    routes = list(_api_routes(client.app))
    # Перебор настоящий: путей много, и среди них есть то, что раньше было открыто.
    assert ("POST", "/api/parse") in routes and ("POST", "/api/ask") in routes and ("GET", "/api/task/{ref}") in routes
    assert set(api.SCOPED_PATHS) <= set(routes)
    for name in NARROW:
        h = _h(dela, name)
        for method, path in routes:
            if (method, path) in api.SCOPED_PATHS:
                continue
            r = client.request(method, _url(path), headers=h, json={"text": "заведи дело", "ops": []})
            assert r.status_code == 403, (name, method, path, r.status_code, r.text[:200])
            assert f"службе {name} этот путь не положен" in r.json()["error"], (name, path)
    # Ни одно дело не завелось: запрос дальше двери не прошёл.
    with db.session(dela, "system", "t") as c:
        assert c.execute("SELECT count(*) AS n FROM tasks.tasks").fetchone()["n"] == 0


def test_same_paths_stay_open_to_owner_and_other_services(dela, client):
    """Белый список — только для узких: телефон владельца и служба встреч (токен владельца без SCOPED) ходят, как раньше."""
    phone, meet = _h(dela, "телефон", kind="device"), _h(dela, "meetings")
    for h in (phone, meet):
        assert client.get("/api/sync", headers=h).status_code == 200
        assert client.get("/api/settings", headers=h).status_code == 200
        assert client.get("/api/task/1", headers=h).status_code == 404  # дела №1 нет — но путь открыт
        assert client.get("/api/parse/nojob", headers=h).status_code == 404


def test_me_for_narrow_token_is_look_only(dela, client):
    for name in NARROW:
        me = client.get("/api/me", headers=_h(dela, name))
        assert me.status_code == 200
        body = me.json()
        assert set(body) == {"ok", "user", "kind", "features", "look"}, (name, set(body))
        for secret in ("settings", "telegram_id", "role", "name", "clients", "money", "claude"):
            assert secret not in body, (name, secret)
        look = body["look"]
        assert "nav" not in look, name          # адреса сервисов — только владельцу в вебе
        assert isinstance(look["pages"], list)  # «Дом» берёт страницы-двойники отсюда
        assert {"tokens", "menu", "pages", "domain", "icon", "icon_png"} <= set(look), (name, set(look))
        assert body["kind"] == "service" and "people" in body["features"]
    # Владелец своим телефоном видит всё, как раньше.
    full = client.get("/api/me", headers=_h(dela, "телефон", kind="device")).json()
    assert full["role"] == "owner" and "settings" in full and "nav" in full["look"]


def test_allowed_calls_still_work(dela, client):
    """Прежние разрешённые вызовы служб — 200: Свод своих ключей и траты «Дома»."""
    store.apply_ops(dela, "sasha", [{"op": "svod.set", "key": "prompt.food", "body": "Еда.", "author": "phone"},
                                    {"op": "svod.set", "key": "food.goals", "value": {"kcal": 2500}, "author": "eda"},
                                    {"op": "svod.set", "key": "money.partner", "value": {"pct": 30}, "author": "dengi"}], "app")
    dengi, eda, sport, dom = (_h(dela, n) for n in NARROW)
    assert client.get("/api/view/svod", headers=dengi).json()["items"][0]["key"] == "money.partner"
    r = client.post("/api/ops", headers=dengi, json={"ops": [{"op": "svod.set", "key": "money.summary", "value": {"debt": 1}}]})
    assert r.status_code == 200 and r.json()["results"][0]["ok"]
    assert client.get("/api/view/svod", params={"key": "prompt.food"}, headers=eda).status_code == 200
    assert client.get("/api/view/svod", params={"key": "food.goals"}, headers=sport).status_code == 200
    assert client.get("/api/view/svod", params={"prefix": "ui."}, headers=dom).status_code == 200
    day = db_today(dela).isoformat()
    row = {"day": day, "service": "digest", "task": "window", "model": "claude-opus-5-5", "calls": 1, "batch_calls": 0,
           "input": 10, "output": 20, "cache_write": 0, "cache_write_1h": 0, "cache_read": 0, "usd": 0.01, "usd_list": 0.01}
    r = client.post("/api/ops", headers=dom, json={"ops": [{"op": "spend.put", "rows": [row]}]})
    assert r.status_code == 200 and r.json()["results"][0]["ok"], r.text
    # Внутри разрешённых путей — прежние проверки: чужой вид и чужая операция — 403.
    assert client.get("/api/view/who", params={"q": "Иван"}, headers=dengi).status_code == 403
    assert client.get("/api/view/when", params={"q": "завтра"}, headers=eda).status_code == 403
    assert client.post("/api/ops", headers=sport, json={"ops": [{"op": "task.create", "task": {"title": "x"}}]}).status_code == 403
