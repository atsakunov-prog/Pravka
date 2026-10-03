"""HTTP API и веб Дел: телефон, браузер, бот и службы — клиенты одного API.

Вход:
- `Authorization: Bearer <токен>` — телефон и службы;
- кука `dela_session` — браузер. Её даёт одноразовая ссылка-приглашение
  (`python -m pravka_dela invite <кто>`), паролей нет. Запрос с кукой, который
  что-то меняет, обязан нести заголовок `X-Dela: 1` — чужая страница его не
  подставит (защита от подделки запроса).

| Путь | Что |
|---|---|
| GET  /health | жив ли |
| GET  / , /static/… | веб (одна страница) |
| POST /auth/redeem, /auth/logout | вход по приглашению, выход |
| GET  /api/me | кто я |
| GET  /api/sync?since=N | всё изменившееся после N (телефон, веб) |
| POST /api/ops | пачка операций с op_id (офлайн-очередь) |
| GET  /api/view/<имя> | готовый список: morning, new, waiting, person, quick, now, project, week, search |
| GET  /api/task/<id или номер> | дело с комментариями и журналом |
"""

from __future__ import annotations

import gzip
import json
import logging
from importlib import resources

import anyio
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse, Response
from starlette.routing import Route

from . import db, store, tokens
from .config import Config

log = logging.getLogger("dela.api")

MAX_BODY = 8 * 1024 * 1024
MAX_OPS = 2000
COOKIE = "dela_session"
SECURITY = {
    "Content-Security-Policy": "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; "
                               "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
    "X-Frame-Options": "DENY",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
}
STATIC = {
    "index.html": "text/html; charset=utf-8",
    "app.js": "text/javascript; charset=utf-8",
    "style.css": "text/css; charset=utf-8",
    "icon.svg": "image/svg+xml",
    "manifest.webmanifest": "application/manifest+json",
}


def _json(data, status: int = 200) -> JSONResponse:
    return JSONResponse(data, status_code=status, headers={"Cache-Control": "no-store", **SECURITY})


def _err(why: str, status: int) -> JSONResponse:
    return _json({"ok": False, "error": why}, status)


def _static(name: str) -> Response:
    if name not in STATIC:
        return Response("нет такого", status_code=404, headers=SECURITY)
    body = (resources.files(__package__) / "static" / name).read_bytes()
    return Response(body, media_type=STATIC[name], headers={"Cache-Control": "no-cache", **SECURITY})


def build(cfg: Config) -> Starlette:
    url = cfg.db_url
    secure_cookie = not cfg.public_url.startswith("http://")

    async def auth(request: Request) -> tokens.Who | None:
        header = request.headers.get("authorization", "")
        if header.lower().startswith("bearer "):
            return await anyio.to_thread.run_sync(tokens.who, url, header[7:].strip())
        cookie = request.cookies.get(COOKIE, "")
        if not cookie:
            return None
        if request.method not in ("GET", "HEAD") and request.headers.get("x-dela") != "1":
            return None  # кука без нашего заголовка — запрос не с нашей страницы
        return await anyio.to_thread.run_sync(tokens.session_who, url, cookie)

    async def health(request: Request):
        return _json({"ok": True, "service": "pravka-dela"})

    async def page(request: Request):
        return _static("index.html")

    async def static(request: Request):
        return _static(request.path_params["name"])

    async def redeem(request: Request):
        if request.headers.get("x-dela") != "1":
            return _err("нет заголовка", 400)
        try:
            code = (await request.json()).get("code", "")
        except ValueError:
            return _err("ожидается JSON", 400)
        got = await anyio.to_thread.run_sync(tokens.redeem, url, code, request.headers.get("user-agent", ""))
        if not got:
            return _err("ссылка уже использована или устарела — попроси новую", 403)
        token, user = got
        resp = _json({"ok": True, "user": user})
        resp.set_cookie(COOKIE, token, max_age=tokens.SESSION_DAYS * 86400, httponly=True,
                        secure=secure_cookie, samesite="strict", path="/")
        return resp

    async def logout(request: Request):
        cookie = request.cookies.get(COOKIE, "")
        if cookie and request.headers.get("x-dela") == "1":
            await anyio.to_thread.run_sync(tokens.logout, url, cookie)
        resp = _json({"ok": True})
        resp.delete_cookie(COOKIE, path="/")
        return resp

    async def me(request: Request):
        who = await auth(request)
        if not who:
            return _err("нужен вход", 401)
        name = await anyio.to_thread.run_sync(_user_name, url, who.user)
        return _json({"ok": True, "user": who.user, "name": name, "kind": who.kind})

    async def sync(request: Request):
        who = await auth(request)
        if not who:
            return _err("нужен вход", 401)
        try:
            since = int(request.query_params.get("since", "0"))
        except ValueError:
            return _err("since — целое число", 400)
        out = await anyio.to_thread.run_sync(store.sync, url, who.user, since)
        return _json({"ok": True, **out})

    async def ops(request: Request):
        who = await auth(request)
        if not who:
            return _err("нужен вход", 401)
        body = await request.body()
        if len(body) > MAX_BODY:
            return _err("пачка больше 8 МБ — дели на части", 413)
        if request.headers.get("content-encoding", "").lower() == "gzip":
            body = gzip.decompress(body)
        try:
            items = json.loads(body).get("ops")
        except (ValueError, AttributeError):
            return _err("ожидается JSON {\"ops\": [...]}", 400)
        if not isinstance(items, list) or len(items) > MAX_OPS:
            return _err(f"ops — список до {MAX_OPS} операций", 400)
        out = await anyio.to_thread.run_sync(lambda: store.apply_ops(url, who.user, items, who.via, who.actor))
        bad = [r for r in out["results"] if not r.get("ok")]
        if bad:
            log.info("ops %s: %d из %d не приняты, первая: %s", who.name, len(bad), len(items), bad[0].get("error"))
        return _json({"ok": True, **out})

    async def view(request: Request):
        who = await auth(request)
        if not who:
            return _err("нужен вход", 401)
        name = request.path_params["name"]
        params = {k: v for k, v in request.query_params.items() if k in {"sphere", "person_id", "project_id", "q", "status"}}
        try:
            out = await anyio.to_thread.run_sync(lambda: store.view(url, who.user, name, **params))
        except store.OpError as e:
            return _err(str(e), 400)
        return _json({"ok": True, **out})

    async def task(request: Request):
        who = await auth(request)
        if not who:
            return _err("нужен вход", 401)
        out = await anyio.to_thread.run_sync(task_card, url, who.user, request.path_params["ref"])
        if out is None:
            return _err("нет такого дела или оно не видно", 404)
        return _json({"ok": True, **out})

    return Starlette(routes=[
        Route("/health", health),
        Route("/", page),
        Route("/static/{name}", static),
        Route("/auth/redeem", redeem, methods=["POST"]),
        Route("/auth/logout", logout, methods=["POST"]),
        Route("/api/me", me),
        Route("/api/sync", sync),
        Route("/api/ops", ops, methods=["POST"]),
        Route("/api/view/{name}", view),
        Route("/api/task/{ref}", task),
    ])


def _user_name(url: str, user: str) -> str:
    with db.session(url, user, via="view") as conn:
        row = conn.execute("SELECT name FROM crm.users WHERE id = %s", (user,)).fetchone()
    return row["name"] if row else user


def task_card(url: str, user: str, ref: str) -> dict | None:
    """Дело, его комментарии и журнал. Журнал читает system — но только после
    того, как сама база показала дело этому пользователю."""
    with db.session(url, user, via="view") as conn:
        t = store.task_by(conn, ref)
        if not t:
            return None
        comments = conn.execute(
            "SELECT * FROM tasks.comments WHERE task_id = %s AND deleted_at IS NULL ORDER BY created_at", (t["id"],)
        ).fetchall()
    with db.session(url, "system", "svc:view") as conn:
        history = conn.execute(
            "SELECT at, actor, via, op, before, after FROM crm.history WHERE entity = 'tasks.tasks' AND entity_id = %s ORDER BY id",
            (str(t["id"]),),
        ).fetchall()
    return store.jsonable({"task": t, "comments": comments, "history": history})
