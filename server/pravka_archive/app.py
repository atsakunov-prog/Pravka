"""Сервис целиком: MCP для claude.ai, приёмник телефона, вход, сборщик intervals.

Один процесс, один порт: роутер публикует его по HTTPS (порт 8443),
сюда приходит обычный HTTP. Адреса для OAuth берутся из PRAVKA_PUBLIC_URL,
а не из заголовка Host: роутер пересылает запросы под своим адресом.
"""

from __future__ import annotations

import asyncio
import datetime as dt
import gzip
import hmac
import json
import logging

import anyio
import psycopg
from pydantic import AnyHttpUrl
from starlette.requests import Request
from starlette.responses import JSONResponse, Response

from mcp.server.auth.settings import AuthSettings, ClientRegistrationOptions, RevocationOptions
from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings

from . import icu, tools
from .auth import SCOPE, OwnerAuth
from .config import Config
from .ingest import ingest_batch

log = logging.getLogger("pravka")

MAX_BODY = 64 * 1024 * 1024

INSTRUCTIONS = """Архив Правки — вся жизнь Саши (владельца) из его приложения и из intervals.icu: лента времени (Засечка), телефон по дням, еда с КБЖУ и витаминами, силовые и зарядка, тренировки с часов, сон и форма, деньги семьи, каждая диктовка. Только чтение.

Как читать:
- Начни со schema() — там виды схемы life и свежесть источников. sql() — любой SELECT по ним, считает база, не ты. search() — поиск по всему сказанному (русские словоформы, подстрока, опечатки). day() — сутки целиком одним ответом.
- Сутки — поле day (его решил телефон). Никогда не выводи сутки из времени в UTC.
- Лента: сумма minutes за полные сутки = 1440. source gap — заполнитель «не размечено», auto — сон или тренировка с телефона и часов. raw — дословная надиктовка с ошибками распознавания, comment — осознанные слова о деле: весит больше.
- Еда: в счёт только confirmed. confidence «наугад» — числа прикидочные.
- Деньги: целые копейки (amount_kop, минус — ушло). В счёт — только live (money_live). Траты семьи — shelf = 'family', amount_kop < 0, не income. owner — чьи (sasha, marianna).
- Тренировки и форма — из intervals как есть; поле, которого нет колонкой, — raw->>'поле'.
- Число, которого нет в архиве, не произносится. Свежесть телефона старше часа — скажи, что про сегодня данные могут быть неполными.
- Как ты читал архив — не тема разговора: Саше нужна его жизнь, а не названия видов."""


def build(cfg: Config) -> tuple[FastMCP, OwnerAuth]:
    auth = OwnerAuth(cfg)
    mcp = FastMCP(
        "Архив Правки",
        instructions=INSTRUCTIONS,
        host=cfg.listen_host,
        port=cfg.listen_port,
        stateless_http=True,
        json_response=True,
        # Проверка заголовка Host — защита локальных серверов от чужих страниц
        # в браузере. Здесь сервис публичный и закрыт OAuth, а Host зависит от
        # того, кто стоит впереди: прокси роутера подставлял свой адрес, Caddy
        # передаёт имя домена. С 127.0.0.1 SDK включил бы её сам и отверг бы
        # всё, что пришло через прокси.
        transport_security=TransportSecuritySettings(enable_dns_rebinding_protection=False),
        auth_server_provider=auth,
        auth=AuthSettings(
            issuer_url=AnyHttpUrl(cfg.public_url),
            resource_server_url=AnyHttpUrl(cfg.mcp_url),
            validate_token_resource=False,
            required_scopes=[SCOPE],
            client_registration_options=ClientRegistrationOptions(enabled=True, valid_scopes=[SCOPE], default_scopes=[SCOPE]),
            revocation_options=RevocationOptions(enabled=True),
        ),
    )

    @mcp.tool()
    async def schema(view: str | None = None) -> str:
        """Карта архива: виды схемы life с описанием и колонками, и свежесть источников. С именем вида — его колонки с типами и пояснениями. Звать первым."""
        return await anyio.to_thread.run_sync(tools.schema, cfg, view)

    @mcp.tool()
    async def sql(query: str, max_rows: int = 200) -> str:
        """Один SELECT по схеме life (только чтение, 15 секунд, до 500 строк). Суммы, средние, сравнения периодов — здесь: считает база. Ответ — таблица текстом."""
        return await anyio.to_thread.run_sync(tools.run_sql, cfg, query, max_rows)

    @mcp.tool()
    async def search(
        query: str,
        date_from: str | None = None,
        date_to: str | None = None,
        domain: str | None = None,
        limit: int = 30,
        order: str = "new",
    ) -> str:
        """Поиск по всему, что Саша говорил и писал: надиктовки ленты, еды, силовых и денег, комментарии к делам, каждая диктовка Правки, вопросы тренеру, комментарии к тренировкам и форме. Русские словоформы («Марианной» по «Марианна»), если пусто — подстрока, потом похожие слова. Даты — ГГГГ-ММ-ДД; domain — засечка, еда, силовые, деньги, правка, тренер, форма, тренировки; order — new (свежее сверху) или best (точнее сверху)."""
        return await anyio.to_thread.run_sync(tools.search, cfg, query, date_from, date_to, domain, limit, order)

    @mcp.tool()
    async def day(date: str, full: bool = False) -> str:
        """Сутки целиком одной лентой: дела по времени со словами Саши, еда, тренировки, сон и форма, силовые и зарядка, траты, телефон, сколько диктовал. date — ГГГГ-ММ-ДД. full — тексты целиком, а не первые строки."""
        return await anyio.to_thread.run_sync(tools.day, cfg, date, full)

    @mcp.custom_route("/login", methods=["GET", "POST"])
    async def login(request: Request) -> Response:
        return await auth.login_page(request)

    @mcp.custom_route("/health", methods=["GET"])
    async def health(request: Request) -> Response:
        return JSONResponse({"ok": True, "service": "pravka-archive", "time": dt.datetime.now().astimezone().isoformat(timespec="seconds")})

    @mcp.custom_route("/ingest", methods=["POST"])
    async def ingest(request: Request) -> Response:
        header = request.headers.get("authorization", "")
        token = header[7:] if header.lower().startswith("bearer ") else ""
        if not token or not hmac.compare_digest(token.encode(), cfg.ingest_token.encode()):
            return JSONResponse({"error": "нет или не тот токен телефона"}, status_code=401)
        body = await request.body()
        if len(body) > MAX_BODY:
            return JSONResponse({"error": "пачка больше 64 МБ — дели на части"}, status_code=413)
        if request.headers.get("content-encoding", "").lower() == "gzip":
            try:
                body = gzip.decompress(body)
            except OSError:
                return JSONResponse({"error": "gzip не распаковался"}, status_code=400)
        try:
            batch = json.loads(body)
        except ValueError as e:
            return JSONResponse({"error": f"не JSON: {e}"}, status_code=400)

        def work():
            with psycopg.connect(cfg.db_url, autocommit=True) as conn:
                return ingest_batch(conn, batch, cfg.profile)

        status, payload = await anyio.to_thread.run_sync(work)
        return JSONResponse(payload, status_code=status)

    return mcp, auth


async def pull_forever(cfg: Config) -> None:
    """Сборщик intervals: раз в 10 минут свежее, раз в сутки глубоко, один раз всё."""
    if not (cfg.icu_athlete and cfg.icu_key):
        log.info("intervals: ключа нет — сборщик спит (ICU_ATHLETE_ID, ICU_API_KEY)")
        return
    puller = icu.Puller(cfg)
    while True:
        try:
            def once():
                with psycopg.connect(cfg.db_url, autocommit=True) as conn:
                    row = conn.execute("SELECT note FROM core.sources WHERE source = 'intervals'").fetchone()
                mode = icu.due(row[0] if row else None, dt.datetime.now(dt.timezone.utc)) or "recent"
                return mode, puller.run(mode)

            mode, out = await anyio.to_thread.run_sync(once)
            changed = sum(out.values())
            if changed or mode != "recent":
                log.info("intervals %s: %s", mode, out)
        except Exception as e:  # сборщик не должен ронять сервис
            log.warning("intervals: %s: %s", e.__class__.__name__, e)
        await asyncio.sleep(600)


async def serve(cfg: Config) -> None:
    import uvicorn

    mcp, _ = build(cfg)
    app = mcp.streamable_http_app()
    server = uvicorn.Server(uvicorn.Config(
        app, host=cfg.listen_host, port=cfg.listen_port, log_level="info",
        proxy_headers=False, server_header=False,
    ))
    await asyncio.gather(server.serve(), pull_forever(cfg))
