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
| GET  /api/view/<имя> | готовый список: morning, new, waiting, person, quick, now, project, week, search; CRM — pipeline, clients, client, deal (с журналом), dossier, ties, money |
| GET  /api/task/<id или номер> | дело с комментариями и журналом |
| POST /api/parse | {"text", "project_id"?, "person_id"?} — Claude режет текст на дела и заводит их; ответ — номер задания |
| GET  /api/parse/<номер> | run, пока думает; потом done с делами и заметками или error |
| POST /api/ask | {"text", "scope": {"title", "task_ids", "focus"?, "project_id"?, "person_id"?, "suggestion_ids"?}} — Claude правит дела страницы словами, в «Новом» — и решает предложения на экране (ask.py); ответ — номер задания |
| GET  /api/ask/<номер> | как у разбора; done — что поменялось (changed: как было и стало), новые дела, решения по «Новому» (decided), ответ Claude |
| GET  /api/reminders/due | только бот Ковчега: напоминания, которым пора в Telegram (remind.py) |
| POST /api/reminders/act | только бот Ковчега: {"telegram_id", "num", "action": done, snooze, tomorrow, "minutes"?} — кнопка под напоминанием от имени нажавшего |

Бот ходит на 127.0.0.1:8102 мимо сайта-посредника: пути напоминаний в его список не нужны.
"""

from __future__ import annotations

import asyncio
import gzip
import json
import logging
import secrets
import time
import uuid
from importlib import resources

import anyio
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse, Response
from starlette.routing import Route

from . import ask, db, llm, parse, remind, store, tokens
from .config import Config

log = logging.getLogger("dela.api")

MAX_BODY = 8 * 1024 * 1024
MAX_OPS = 2000
JOB_TTL = 15 * 60  # готовый разбор ждёт, пока веб его заберёт
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
        info = await anyio.to_thread.run_sync(_user_info, url, who.user)
        return _json({"ok": True, "user": who.user, **info, "kind": who.kind, "claude": bool(cfg.anthropic_key),
                      "features": store.FEATURES})

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
        params = {k: v for k, v in request.query_params.items()
                  if k in {"sphere", "person_id", "project_id", "deal_id", "q", "status", "closed_days"}}
        try:
            out = await anyio.to_thread.run_sync(lambda: store.view(url, who.user, name, **params))
        except store.OpError as e:
            return _err(str(e), 400)
        return _json({"ok": True, **out})

    # Разбор Claude — заданием: ответ модели бывает дольше минуты, а роутер и nginx
    # долгие запросы рвут. POST отдаёт номер сразу, веб спрашивает GET по номеру;
    # оборванная связь не плодит повторов — дела заводит задание, а не запрос.
    # Два разбора разом на весь сервис; ограничитель — внутри цикла событий.
    jobs: dict[str, dict] = {}
    slots: list[anyio.CapacityLimiter] = []

    async def run_parse(job: dict, who: tokens.Who, text: str, defaults: dict) -> None:
        try:
            out = await anyio.to_thread.run_sync(
                lambda: parse.run(url, who.user, text, defaults, cfg.anthropic_key, cfg.claude_proxy,
                                  via=who.via, actor=who.actor),
                limiter=slots[0],
            )
            job.update(status="done", result=store.jsonable(out))
            log.info("разбор %s: %d знаков, дел %d, заметок %d, токены %s",
                     who.name, len(text), len(out["tasks"]), len(out["notes"]), out.get("usage"))
        except parse.ParseError as e:
            job.update(status="error", error=str(e))
        except Exception as e:  # сеть, ключ, лимит — человеку коротко, в журнал подробно
            log.exception("разбор %s", who.name)
            job.update(status="error", error=f"Claude недоступен: {type(e).__name__}")

    async def parse_start(request: Request):
        who = await auth(request)
        if not who:
            return _err("нужен вход", 401)
        try:
            body = await request.json()
            text = str(body.get("text") or "").strip()
        except (ValueError, AttributeError):
            return _err("ожидается JSON {\"text\": ...}", 400)
        if not text:
            return _err("пусто — нечего разбирать", 422)
        if not cfg.anthropic_key:
            return _err("разбор Claude не настроен: нет ключа в dela.env", 422)
        defaults = {}
        for k in ("project_id", "person_id"):
            v = body.get(k)
            if v:
                try:
                    defaults[k] = str(uuid.UUID(str(v)))
                except ValueError:
                    return _err(f"{k} — не uuid", 400)
        now = time.monotonic()
        for k in [k for k, j in jobs.items() if now - j["at"] > JOB_TTL and j["status"] != "run"]:
            del jobs[k]
        if not slots:
            slots.append(anyio.CapacityLimiter(2))
        jid = secrets.token_urlsafe(12)
        job = jobs[jid] = {"user": who.user, "status": "run", "at": now}
        job["task"] = asyncio.get_running_loop().create_task(run_parse(job, who, text, defaults))
        return _json({"ok": True, "job": jid}, 202)

    # Правка словами (микрофон у дела, строка Claude) — тем же заданием, что разбор.
    async def run_ask(job: dict, who: tokens.Who, text: str, scope: dict) -> None:
        try:
            out = await anyio.to_thread.run_sync(
                lambda: ask.run(url, who.user, text, scope, cfg.anthropic_key, cfg.claude_proxy,
                                via=who.via, actor=who.actor),
                limiter=slots[0],
            )
            job.update(status="done", result=store.jsonable(out))
            log.info("правка %s: %d знаков, дел на экране %d, предложений %d, путь %s, поправлено %d, заведено %d, "
                     "решено в «Новом» %d, токены %s",
                     who.name, len(text), len(scope.get("task_ids") or []), len(scope.get("suggestion_ids") or []),
                     out.get("route"), len(out.get("changed") or []), len(out.get("tasks") or []),
                     len(out.get("decided") or []), out.get("usage"))
        except (ask.AskError, parse.ParseError) as e:
            job.update(status="error", error=str(e))
        except Exception as e:  # сеть, ключ, лимит — человеку коротко, в журнал подробно
            log.exception("правка %s", who.name)
            job.update(status="error", error=f"Claude недоступен: {type(e).__name__}")

    async def ask_start(request: Request):
        who = await auth(request)
        if not who:
            return _err("нужен вход", 401)
        try:
            body = await request.json()
            text = str(body.get("text") or "").strip()
        except (ValueError, AttributeError):
            return _err("ожидается JSON {\"text\": ..., \"scope\": ...}", 400)
        if not text:
            return _err("пусто — скажи, что сделать", 422)
        if not cfg.anthropic_key:
            return _err("Claude не настроен: нет ключа в dela.env", 422)
        raw = body.get("scope") or {}
        scope: dict = {"title": str(raw.get("title") or "")[:200]}
        try:
            scope["task_ids"] = [str(uuid.UUID(str(x))) for x in (raw.get("task_ids") or [])][:ask.MAX_TASKS]
            scope["suggestion_ids"] = [str(uuid.UUID(str(x))) for x in (raw.get("suggestion_ids") or [])][:ask.MAX_SUGS]
            for k in ("focus", "project_id", "person_id"):
                if raw.get(k):
                    scope[k] = str(uuid.UUID(str(raw[k])))
        except (ValueError, TypeError):
            return _err("scope: id — не uuid", 400)
        now = time.monotonic()
        for k in [k for k, j in jobs.items() if now - j["at"] > JOB_TTL and j["status"] != "run"]:
            del jobs[k]
        if not slots:
            slots.append(anyio.CapacityLimiter(2))
        jid = secrets.token_urlsafe(12)
        job = jobs[jid] = {"user": who.user, "status": "run", "at": now}
        job["task"] = asyncio.get_running_loop().create_task(run_ask(job, who, text, scope))
        return _json({"ok": True, "job": jid}, 202)

    async def parse_poll(request: Request):
        who = await auth(request)
        if not who:
            return _err("нужен вход", 401)
        job = jobs.get(request.path_params["job"])
        if not job or job["user"] != who.user:
            return _err("нет такого разбора", 404)
        if job["status"] == "run":
            return _json({"ok": True, "status": "run"})
        if job["status"] == "error":
            return _json({"ok": False, "status": "error", "error": job["error"]})
        return _json({"ok": True, "status": "done", **job["result"]})

    async def settings(request: Request):
        """«Настройки» веба: свои настройки; владельцу — ещё траты Claude в Делах."""
        who = await auth(request)
        if not who:
            return _err("нужен вход", 401)
        info = await anyio.to_thread.run_sync(_user_info, url, who.user)
        out = {"ok": True, "settings": info.get("settings") or {}, "claude": bool(cfg.anthropic_key),
               "models": {k: v for k, v in llm.MODELS.items()}, "efforts": list(llm.EFFORTS),
               "default": {"claude_model": "sonnet", "claude_effort": ask.EFFORT}}
        if info.get("role") == "owner":
            out["cost"] = await anyio.to_thread.run_sync(llm.spent, url)
        return _json(out)

    # Напоминания в Telegram: шлёт бот Ковчега (служба встреч), Дела отдают ему «что пора»
    # и принимают его кнопки. Чужому токену — 403: телефону и вебу эти пути не нужны.
    async def bot(request: Request) -> tuple[tokens.Who | None, JSONResponse | None]:
        who = await auth(request)
        if not who:
            return None, _err("нужен вход", 401)
        if who.kind != "service" or who.name != store.REMIND_BOT:
            return None, _err("только боту напоминаний", 403)
        return who, None

    async def reminders_due(request: Request):
        who, bad = await bot(request)
        if bad:
            return bad
        return _json(await anyio.to_thread.run_sync(remind.due, url, cfg.public_url))

    async def reminders_act(request: Request):
        who, bad = await bot(request)
        if bad:
            return bad
        try:
            body = await request.json()
            tg, ref, action = int(body["telegram_id"]), str(body["num"]), str(body["action"])
            minutes = int(body.get("minutes") or 60)
        except (ValueError, TypeError, KeyError, AttributeError):
            return _err("ожидается JSON {\"telegram_id\", \"num\", \"action\", \"minutes\"?}", 400)
        try:
            out = await anyio.to_thread.run_sync(lambda: remind.act(url, tg, ref, action, minutes))
        except store.OpError as e:
            return _err(str(e), 422)
        log.info("напоминание: кнопка %s у #%s (%s)", action, ref, out["user"])
        return _json(out)

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
        Route("/api/parse", parse_start, methods=["POST"]),
        Route("/api/parse/{job}", parse_poll),
        Route("/api/ask", ask_start, methods=["POST"]),
        Route("/api/ask/{job}", parse_poll),  # задания общие с разбором
        Route("/api/settings", settings),
        Route("/api/reminders/due", reminders_due),
        Route("/api/reminders/act", reminders_act, methods=["POST"]),
    ])


def _user_info(url: str, user: str) -> dict:
    """Имя и что человеку открыто: веб по этому прячет «Деньги» и время Засечки."""
    with db.session(url, user, via="view") as conn:
        row = conn.execute(
            "SELECT name, role, clients, crm.money_ok(id) AS money, settings FROM crm.users WHERE id = %s", (user,)
        ).fetchone()
    if not row:
        return {"name": user, "role": "member", "clients": "own", "money": False, "settings": {}}
    return {"name": row["name"], "role": row["role"], "clients": row["clients"], "money": row["money"],
            "settings": row["settings"] or {}}


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
