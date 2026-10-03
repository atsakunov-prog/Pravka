"""HTTP API Дел: телефон, веб, бот и службы — клиенты одного API.

Вход — `Authorization: Bearer <токен>` (телефон, служба). Веб с сессиями и
вход через бота приходят следующей фазой поверх тех же путей.

| Путь | Что |
|---|---|
| GET  /health | жив ли |
| GET  /api/me | кто я по токену |
| GET  /api/sync?since=N | всё изменившееся после N (телефон) |
| POST /api/ops | пачка операций с op_id (офлайн-очередь) |
| GET  /api/view/<имя> | готовый список: morning, new, waiting, person, quick, now, project, week, search |
| GET  /api/task/<id или номер> | дело с комментариями и журналом |
"""

from __future__ import annotations

import gzip
import json
import logging

import anyio
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route

from . import db, store, tokens
from .config import Config

log = logging.getLogger("dela.api")

MAX_BODY = 8 * 1024 * 1024
MAX_OPS = 2000


def _json(data, status: int = 200) -> JSONResponse:
    return JSONResponse(data, status_code=status, headers={"Cache-Control": "no-store"})


def _err(why: str, status: int) -> JSONResponse:
    return _json({"ok": False, "error": why}, status)


def build(cfg: Config) -> Starlette:
    url = cfg.db_url

    async def auth(request: Request) -> tokens.Who | None:
        header = request.headers.get("authorization", "")
        token = header[7:].strip() if header.lower().startswith("bearer ") else ""
        return await anyio.to_thread.run_sync(tokens.who, url, token)

    async def health(request: Request):
        return _json({"ok": True, "service": "pravka-dela"})

    async def me(request: Request):
        who = await auth(request)
        if not who:
            return _err("нет токена или он отозван", 401)
        return _json({"ok": True, "user": who.user, "kind": who.kind, "name": who.name})

    async def sync(request: Request):
        who = await auth(request)
        if not who:
            return _err("нет токена или он отозван", 401)
        try:
            since = int(request.query_params.get("since", "0"))
        except ValueError:
            return _err("since — целое число", 400)
        out = await anyio.to_thread.run_sync(store.sync, url, who.user, since)
        return _json({"ok": True, **out})

    async def ops(request: Request):
        who = await auth(request)
        if not who:
            return _err("нет токена или он отозван", 401)
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
            return _err("нет токена или он отозван", 401)
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
            return _err("нет токена или он отозван", 401)
        out = await anyio.to_thread.run_sync(task_card, url, who.user, request.path_params["ref"])
        if out is None:
            return _err("нет такого дела или оно не видно", 404)
        return _json({"ok": True, **out})

    return Starlette(routes=[
        Route("/health", health),
        Route("/api/me", me),
        Route("/api/sync", sync),
        Route("/api/ops", ops, methods=["POST"]),
        Route("/api/view/{name}", view),
        Route("/api/task/{ref}", task),
    ])


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
