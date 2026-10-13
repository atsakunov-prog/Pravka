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
| GET  /api/me | кто я; look — шапка веба: домен общей куки темы, значок «Ковчега», соседние сервисы (только владельцу) |
| GET  /api/sync?since=N | всё изменившееся после N (телефон, веб) |
| POST /api/ops | пачка операций с op_id (офлайн-очередь) |
| GET  /api/view/<имя> | готовый список: morning, new, waiting, person, quick, now, project, week, search; CRM — pipeline, clients, client, deal (с журналом), dossier, ties, money; «Где мы» — where, where_avatar?device= (where.py); встречи — meetings?sphere&q&project_id&person_id&sort&since&until&archived, meeting?id или ref (meetings.py); «Когда» — when?q=фраза&local=1&date=1: срок и напоминание словами в поля дела (when.py; без local непонятое — Claude) |
| GET  /api/task/<id или номер> | дело с комментариями, журналом и откуда оно (origin) |
| POST /api/parse | {"text", "project_id"?, "person_id"?} — Claude режет текст на дела и заводит их; ответ — номер задания |
| GET  /api/parse/<номер> | run, пока думает; потом done с делами и заметками или error |
| POST /api/ask | {"text", "scope": {"title", "task_ids", "visible_ids"?, "selected_ids"?, "open"?, "deal_ids"?, "visible_deal_ids"?, "focus"?, "project_id"?, "person_id"?, "deal_id"?, "card"?, "kstati"?, "suggestion_ids"?, "history"?}} — Claude правит дела словами: видит страницу (что в окне, что выбрано, что открыто) и все свои открытые дела коротко, в «Новом» — и решает предложения на экране, в карточке (card: client, person, deal) — и её хронологию, людей, сделки; в карточке дела (card: task, open) — только это дело, kstati — мысль вдогонку к нему; history — разговор в окне ответа (ask.py); ответ — номер задания |
| GET  /api/ask/<номер> | как у разбора; done — что поменялось (changed: как было и стало), новые дела, решения по «Новому» (decided), ответ Claude |
| GET  /api/reminders/due | только бот Ковчега: напоминания, которым пора в Telegram (remind.py) |
| POST /api/reminders/act | только бот Ковчега: {"telegram_id", "num", "action": done, snooze, tomorrow, "minutes"?} — кнопка под напоминанием от имени нажавшего |

Бот ходит на 127.0.0.1:8102 мимо сайта-посредника: пути напоминаний в его список не нужны.
"""

from __future__ import annotations

import asyncio
import base64
import gzip
import json
import logging
import secrets
import time
import uuid
from importlib import resources
from pathlib import Path
from urllib.parse import urlparse

import anyio
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse, Response
from starlette.routing import Route

from . import ask, db, llm, parse, remind, store, tokens, when
from .config import Config

log = logging.getLogger("dela.api")

MAX_BODY = 8 * 1024 * 1024
MAX_OPS = 2000
JOB_TTL = 15 * 60  # готовый разбор ждёт, пока веб его заберёт
COOKIE = "dela_session"
# Стили и шрифты — ещё и с Google Fonts (13.10.2026): шрифт в шестерёнке общего вида Ковчега выбирает человек, Golos
# Text остаётся своим файлом. Скриптам и соединениям — по-прежнему только свой адрес.
SECURITY = {
    "Content-Security-Policy": "default-src 'self'; img-src 'self' data:; style-src 'self' https://fonts.googleapis.com; "
                               "font-src 'self' https://fonts.gstatic.com; script-src 'self'; "
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
    # Golos Text (OFL), только латиница и кириллица — шрифт общего вида Ковчега (13.10.2026; Literata Правки 4.0
    # ушла вместе с ним). Свой файл, а не Google Fonts: CSP Дел чужих шрифтов не пускает. Меняется раз в годы —
    # кэш на месяц, иначе телефон тянул бы 50 КБ при каждом открытии.
    "golos.woff2": "font/woff2",
}
BRAND_MAX = 96 * 1024  # значок «Ковчега» уходит в /api/me строкой data: — большой картинке там не место


def _cookie_domain(public_url: str) -> str:
    """Домен общей куки темы (kv-theme): родитель адреса веба — dela.<домен> → <домен>, чтобы выбор темы был
    общим с соседними сервисами. Сам домен в код не вписан: репозиторий публичный."""
    host = (urlparse(public_url).hostname or "").lower()
    parts = host.split(".")
    if len(parts) < 3 or host.replace(".", "").isdigit() or host.endswith(".localhost"):
        return ""
    return ".".join(parts[1:])


def _brand_icon(path: str) -> str:
    """Значок «Ковчега» строкой data: (CSP пускает data: в img-src; новый путь в static — правка сайта-посредника)."""
    if not path:
        return ""
    p = Path(path)
    kind = {".svg": "image/svg+xml", ".png": "image/png"}.get(p.suffix.lower())
    try:
        body = p.read_bytes() if kind else b""
    except OSError as e:
        log.warning("значок Ковчега %s не прочитался: %s", path, e)
        return ""
    if not body or len(body) > BRAND_MAX:
        return ""
    return f"data:{kind};base64,{base64.b64encode(body).decode()}"


def _look(cfg: Config) -> tuple[dict, dict]:
    """Шапка веба (общий вид Ковчега, 13.10.2026): владельцу — соседние сервисы из настроек на компе, команде —
    только Дела. Это не защита (у каждого сервиса свой вход), а чтобы Наташе и Алёне не мешали чужие ссылки."""
    base = {"domain": _cookie_domain(cfg.public_url), "icon": _brand_icon(cfg.brand_icon), "nav": []}
    owner = {**base, "nav": [{"name": n, "icon": i, "url": u} for n, i, u in cfg.nav]}
    return owner, base



# Службы с узкими правами (06.10.2026). «Деньги» смотрят наружу: их токену — только свои записи
# Свода (money.*), без дел, людей и синка. Остальные службы (встречи, дайджест, бот) действуют как
# владелец токена, как раньше. С 13.10.2026 — «Еда» и «Спорт» на компе: «Еде» — рацион и цели
# (food.*) и читать промпт разбора еды и выбор модели (тот же разбор, что у телефона: одно на два окна),
# «Спорту» — правила блока и справочник упражнений (sport.*) и читать цель веса (food.goals).
# svod_read — что ещё можно читать.
SCOPED = {
    "dengi": {"ops": {"svod.set"}, "views": {"svod"}, "svod_prefix": "money."},
    "eda": {"ops": {"svod.set"}, "views": {"svod"}, "svod_prefix": "food.", "svod_read": ("prompt.food", "models.routes")},
    "sport": {"ops": {"svod.set"}, "views": {"svod"}, "svod_prefix": "sport.", "svod_read": ("food.goals",)},
}


def _scope(who) -> dict | None:
    return SCOPED.get(who.name) if who and who.kind == "service" else None


def _may_read(sc: dict, key: str) -> bool:
    return key.startswith(sc["svod_prefix"]) or key in sc.get("svod_read", ())

def _json(data, status: int = 200) -> JSONResponse:
    return JSONResponse(data, status_code=status, headers={"Cache-Control": "no-store", **SECURITY})


def _err(why: str, status: int) -> JSONResponse:
    return _json({"ok": False, "error": why}, status)


def _static(name: str) -> Response:
    if name not in STATIC:
        return Response("нет такого", status_code=404, headers=SECURITY)
    body = (resources.files(__package__) / "static" / name).read_bytes()
    cache = "public, max-age=2592000" if name.endswith(".woff2") else "no-cache"
    return Response(body, media_type=STATIC[name], headers={"Cache-Control": cache, **SECURITY})


def build(cfg: Config) -> Starlette:
    url = cfg.db_url
    secure_cookie = not cfg.public_url.startswith("http://")
    look_owner, look_team = _look(cfg)

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
                      "features": store.FEATURES, "look": look_owner if info.get("role") == "owner" else look_team})

    async def sync(request: Request):
        who = await auth(request)
        if not who:
            return _err("нужен вход", 401)
        if _scope(who):
            return _err(f"службе {who.name} синк не положен", 403)
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
        sc = _scope(who)
        if sc and any(not isinstance(o, dict) or o.get("op") not in sc["ops"]
                      or not str(o.get("key") or "").startswith(sc["svod_prefix"]) for o in items):
            return _err(f"службе {who.name} можно только {', '.join(sorted(sc['ops']))} с ключами {sc['svod_prefix']}*", 403)
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
        if name == "when" and not _scope(who):
            # «Когда» в карточке дела (13.10.2026, when.py): фраза → срок и напоминание. Путь — вид, а не новый адрес:
            # список адресов Дел на Aeza закрытый, а /api/view/<имя> в нём уже есть. local=1 — только свой разбор
            # (пока человек печатает), без него непонятое спрашиваем у Claude; date=1 — нужен только день.
            q = request.query_params
            try:
                out = await anyio.to_thread.run_sync(lambda: when.understand(
                    url, who.user, q.get("q") or "", cfg.anthropic_key, cfg.claude_proxy,
                    use_model=q.get("local") != "1", only_date=q.get("date") == "1"))
            except when.WhenError as e:
                return _json({"ok": False, "error": str(e)})
            except Exception as e:  # noqa: BLE001 — сеть, ключ, лимит: человеку коротко, в журнал подробно
                log.exception("«когда» %s", who.name)
                return _json({"ok": False, "error": f"Claude недоступен: {type(e).__name__} — напиши проще"})
            return _json(out)
        params = {k: v for k, v in request.query_params.items()
                  if k in {"sphere", "person_id", "project_id", "deal_id", "q", "status", "closed_days",
                           # Свод и «кто это» (06.10.2026)
                           "key", "prefix", "phone", "telegram", "telegram_id", "email",
                           # «Где мы» (10.10.2026): аватар одного телефона
                           "device",
                           # Встречи (12.10.2026): карточка (id, ref, окно расшифровки, сырьё) и список (сортировка,
                           # даты, убранные, неразобранные). Без них вид молча получал пусто: карточка — «нужен id».
                           "id", "ref", "around", "lines", "raw", "sort", "dir", "since", "until", "archived",
                           "unnamed", "limit"}}
        sc = _scope(who)
        if sc:
            if name not in sc["views"]:
                return _err(f"службе {who.name} этот вид не положен", 403)
            if name == "svod":
                if params.get("key"):
                    if not _may_read(sc, params["key"]):
                        return _err(f"службе {who.name} — только {sc['svod_prefix']}*", 403)
                    params.pop("prefix", None)
                else:
                    params["prefix"] = sc["svod_prefix"] + (params.pop("prefix", "") or "").removeprefix(sc["svod_prefix"])
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
            for k in ("task_ids", "visible_ids", "selected_ids"):  # экран: дела страницы, в окне, выбранные
                scope[k] = [str(uuid.UUID(str(x))) for x in (raw.get(k) or [])][:ask.MAX_TASKS]
            for k in ("deal_ids", "visible_deal_ids"):  # сделки на экране: Воронка, клиенты, человек
                scope[k] = [str(uuid.UUID(str(x))) for x in (raw.get(k) or [])][:ask.MAX_DEALS]
            scope["suggestion_ids"] = [str(uuid.UUID(str(x))) for x in (raw.get("suggestion_ids") or [])][:ask.MAX_SUGS]
            for k in ("focus", "open", "project_id", "person_id", "deal_id"):
                if raw.get(k):
                    scope[k] = str(uuid.UUID(str(raw[k])))
        except (ValueError, TypeError):
            return _err("scope: id — не uuid", 400)
        if raw.get("card") in ("client", "person", "deal"):
            scope["card"] = raw["card"]  # карточка на экране: Claude видит и правит её (ask.card_ops)
        if raw.get("card") == "task":
            # Карточка дела (13.10.2026): Claude видит только это дело; «Кстати» — мысль вдогонку к нему.
            if not scope.get("open"):
                return _err("scope: карточке дела нужен open — id дела", 400)
            scope.update(card="task", kstati=bool(raw.get("kstati")))
        # Разговор в окне ответа (09.10.2026): веб шлёт его с каждой репликой, а сюда он не доходил — Claude не видел,
        # о чём спрашивал (13.10.2026). Только строки: что сказал, что ответил, что сделано (ask._history режет длину).
        # Разговор под строкой (13.10.2026): его номер — экран разговора один на все реплики (кэш, ask._snapshot).
        talk = "".join(ch for ch in str(raw.get("talk_id") or "") if ch.isalnum() or ch in "-_")[:64]
        if talk:
            scope["talk_id"] = talk
        if isinstance(raw.get("history"), list):
            scope["history"] = [{k: str(x.get(k) or "")[:2000] for k in ("said", "reply", "done")}
                                for x in raw["history"][-ask.TALK_TURNS:] if isinstance(x, dict)]
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
        # Модель и глубина Claude — дороги владельца (llm.ROUTES, Свод models.dela), а не настройка человека (13.10.2026).
        routes = await anyio.to_thread.run_sync(lambda: {k: dict(zip(("model", "effort"), llm.route(url, k))) for k in llm.ROUTES})
        out = {"ok": True, "settings": info.get("settings") or {}, "claude": bool(cfg.anthropic_key), "routes": routes}
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
    """Имя и что человеку открыто: веб по этому прячет «Деньги» и время Засечки. telegram_id — свой же:
    по нему Встречи узнают владельца (одна правда — crm.users, 06.10.2026), а не по числу в своём коде."""
    with db.session(url, user, via="view") as conn:
        row = conn.execute(
            "SELECT name, role, clients, crm.money_ok(id) AS money, settings, telegram_id FROM crm.users WHERE id = %s", (user,)
        ).fetchone()
    if not row:
        return {"name": user, "role": "member", "clients": "own", "money": False, "settings": {}, "telegram_id": None}
    return {"name": row["name"], "role": row["role"], "clients": row["clients"], "money": row["money"],
            "settings": row["settings"] or {}, "telegram_id": row["telegram_id"]}


def task_card(url: str, user: str, ref: str) -> dict | None:
    """Дело, его комментарии, журнал и откуда оно (наговорка, встреча, Telegram). Журнал читает
    system — но только после того, как сама база показала дело этому пользователю."""
    with db.session(url, user, via="view") as conn:
        t = store.task_by(conn, ref)
        if not t:
            return None
        comments = conn.execute(
            "SELECT * FROM tasks.comments WHERE task_id = %s AND deleted_at IS NULL ORDER BY created_at", (t["id"],)
        ).fetchall()
        origin = store.task_origin(conn, t)
    with db.session(url, "system", "svc:view") as conn:
        history = conn.execute(
            "SELECT at, actor, via, op, before, after FROM crm.history WHERE entity = 'tasks.tasks' AND entity_id = %s ORDER BY id",
            (str(t["id"]),),
        ).fetchall()
    return store.jsonable({"task": t, "comments": comments, "history": history, "origin": origin})
