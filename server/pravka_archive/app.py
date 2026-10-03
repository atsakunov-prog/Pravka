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
import os

import anyio
import psycopg
from psycopg.types.json import Jsonb
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

DELA_INSTRUCTIONS = """

Дела — задачи, люди, проекты и сделки Саши (его сервис «Дела» вместо Todoist и Notion CRM). Тут можно и писать.
- Смотреть: dela_view (утро, новое, жду, по человеку, проект, неделя, быстрое, сейчас, поиск), dela_task — дело целиком с журналом. Для счёта и связок с жизнью — sql по life.tasks, life.projects, life.deals, life.people, life.interactions, life.work_time.
- Менять: dela_add, dela_change, dela_decide («Новое»), dela_note (хронология клиента). Дело называется коротким номером «57».
- Формат названия — «Кто: действие». Мяч: mine — моё, waiting — жду от person, agenda — поднять при встрече с person. Деньги: paid — оплата согласована, potential — развитие, пусто — как у проекта.
- Даты пиши сам: YYYY-MM-DD, сегодняшняя дата — в первой строке dela_view.
- Задачу на Марианну не ставь: только с её согласия, через неё саму."""


def build(cfg: Config) -> tuple[FastMCP, OwnerAuth]:
    auth = OwnerAuth(cfg)
    mcp = FastMCP(
        "Архив Правки",
        instructions=INSTRUCTIONS + (DELA_INSTRUCTIONS if cfg.dela_db_url else ""),
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

    if cfg.dela_db_url:
        _dela_tools(mcp, cfg.dela_db_url)

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


def asgi(cfg: Config):
    """Приложение целиком, как его видит uvicorn.

    «/mcp/» с косой чертой Starlette перенаправил бы на «/mcp», собрав адрес из
    запроса, — а роутер присылает Host: 192.168.1.77 и http, и Claude ушёл бы
    по внутреннему адресу. Поэтому косую черту срезаем сами, без
    перенаправления: все внешние адреса — только из PRAVKA_PUBLIC_URL.
    """
    mcp, _ = build(cfg)
    inner = mcp.streamable_http_app()

    async def app(scope, receive, send):
        if scope.get("type") == "http" and scope.get("path") == "/mcp/":
            scope = dict(scope, path="/mcp", raw_path=b"/mcp")
        await inner(scope, receive, send)

    return app


def seed_sources(cfg: Config) -> None:
    """Заранее завести источники, которые должны появиться.

    Источник, который ни разу не присылал, иначе в свежести просто не виден —
    и Claude не отличит «телефон ещё не подключён» от «всё хорошо, телефона
    нет и не было». Строка-заглушка «phone» уходит с первой пачкой телефона.
    """
    with psycopg.connect(cfg.db_url, autocommit=True) as conn:
        if cfg.icu_athlete and cfg.icu_key:
            conn.execute(
                "INSERT INTO core.sources (source, note) VALUES ('intervals', %s) ON CONFLICT (source) DO NOTHING",
                (Jsonb({"why": "сборщик только запущен"}),),
            )
        phones = conn.execute("SELECT 1 FROM core.sources WHERE source LIKE 'phone:%' LIMIT 1").fetchone()
        if phones:
            conn.execute("DELETE FROM core.sources WHERE source = 'phone'")
        else:
            conn.execute(
                "INSERT INTO core.sources (source, note) VALUES ('phone', %s) ON CONFLICT (source) DO NOTHING",
                (Jsonb({"why": "телефон ещё не подключён к архиву: нужна сборка Правки с архивом и QR из pair"}),),
            )


def pid_alive(pid: int) -> bool:
    if os.name == "nt":
        import ctypes

        kernel32 = ctypes.windll.kernel32
        handle = kernel32.OpenProcess(0x00100000, False, pid)  # SYNCHRONIZE
        if not handle:
            return False
        try:
            return kernel32.WaitForSingleObject(handle, 0) == 0x102  # WAIT_TIMEOUT — ещё жив
        finally:
            kernel32.CloseHandle(handle)
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


async def watch_supervisor(pid: int) -> None:
    """Сторож ушёл — уходим и мы: иначе осиротевшая служба держит порт, и
    новый сторож её не поднимет."""
    while True:
        await asyncio.sleep(5)
        if not pid_alive(pid):
            log.warning("сторож (PID %d) пропал — служба выходит", pid)
            os._exit(0)


async def serve(cfg: Config) -> None:
    import uvicorn

    await anyio.to_thread.run_sync(seed_sources, cfg)
    app = asgi(cfg)
    # Роутер Netcraze пересылает запросы под своим адресом (192.168.1.1), с
    # Host: 192.168.1.77 и без X-Forwarded-For (проверено 01.10 из домашней
    # сети). Если прокси когда-нибудь начнёт класть адрес клиента в
    # X-Forwarded-For, верим ему только от адресов из PRAVKA_PROXIES.
    # log_config=None — журнал запросов uvicorn идёт в archive.log, а не в
    # консоль задачи планировщика, которую никто не видит.
    server = uvicorn.Server(uvicorn.Config(
        app, host=cfg.listen_host, port=cfg.listen_port, log_level="info", log_config=None,
        proxy_headers=True, forwarded_allow_ips=cfg.proxies, server_header=False,
    ))
    jobs = [server.serve(), pull_forever(cfg)]
    parent = os.environ.get("PRAVKA_SUPERVISOR_PID", "")
    if parent.isdigit():
        jobs.append(watch_supervisor(int(parent)))
    await asyncio.gather(*jobs)


def _dela_tools(mcp: FastMCP, url: str) -> None:
    """Инструменты Дел — тот же код, что у службы Дел, ролью её же базы."""
    from pravka_dela import mcp_tools as dela

    def run(fn, *a, **kw):
        return anyio.to_thread.run_sync(lambda: fn(url, *a, **kw))

    @mcp.tool()
    async def dela_view(view: str = "morning", sphere: str | None = None, person: str | None = None,
                        project: str | None = None, query: str | None = None) -> str:
        """Дела списком. view: morning (утро: сейчас, на сегодня и просроченное, кому пора напомнить, оплачено без даты), new («Новое» — что предложила автоматика), waiting (жду, по людям), person (по человеку: повестка, жду, его просьбы — нужен person), project (проект: сделки, задачи, время из ленты — нужен project), week (неделя: протухшее, жду без движения, проекты без следующего шага), quick (до 10 минут), now (сейчас), search (поиск — нужен query). sphere: work, home или пусто (всё). Имена людей и проектов — как говорит Саша («Додо», «Наташа»)."""
        return await run(dela.view, view, sphere, person, project, query)

    @mcp.tool()
    async def dela_task(ref: str) -> str:
        """Дело целиком: поля, заметки, комментарии, журнал правок. ref — короткий номер («57»)."""
        return await run(dela.card, ref)

    @mcp.tool()
    async def dela_add(title: str, project: str | None = None, deal: str | None = None, person: str | None = None,
                       ball: str = "mine", due_date: str | None = None, due_time: str | None = None,
                       nudge_on: str | None = None, requested_by: str | None = None, estimate_min: int | None = None,
                       money: str | None = None, want: bool | None = None, now: bool | None = None,
                       labels: list[str] | None = None, notes: str | None = None) -> str:
        """Новое дело Саше. title — «Кто: действие». project — проект (пусто — «Входящие»), deal — сделка проекта. ball: mine, waiting (жду от person), agenda (поднять при встрече с person). due_date, nudge_on — YYYY-MM-DD. money: paid, potential, none (пусто — как у проекта). now — в «Сейчас» на сегодня. labels — только контексты вроде «звонок»."""
        return await run(dela.add, title=title, project=project, deal=deal, person=person, ball=ball, due_date=due_date,
                         due_time=due_time, nudge_on=nudge_on, requested_by=requested_by, estimate_min=estimate_min,
                         money=money, want=want, now=now, labels=labels, notes=notes)

    @mcp.tool()
    async def dela_change(ref: str, title: str | None = None, notes: str | None = None, project: str | None = None,
                          deal: str | None = None, person: str | None = None, ball: str | None = None,
                          due_date: str | None = None, due_time: str | None = None, nudge_on: str | None = None,
                          requested_by: str | None = None, estimate_min: int | None = None, money: str | None = None,
                          want: bool | None = None, now: bool | None = None, labels: list[str] | None = None,
                          status: str | None = None, comment: str | None = None) -> str:
        """Поправить дело по номеру: любые поля как у dela_add; status: done (сделано), cancelled (отменено), open (вернуть); comment — дописать комментарий. Пустая строка в поле — очистить его (due_date="" — без срока). Что не передано — не меняется."""
        return await run(dela.change, ref, comment=comment, title=title, notes=notes, project=project, deal=deal,
                         person=person, ball=ball, due_date=due_date, due_time=due_time, nudge_on=nudge_on,
                         requested_by=requested_by, estimate_min=estimate_min, money=money, want=want, now=now,
                         labels=labels, status=status)

    @mcp.tool()
    async def dela_decide(suggestion: str, decision: str, reason: str | None = None, title: str | None = None,
                          project: str | None = None, person: str | None = None, ball: str | None = None,
                          due_date: str | None = None) -> str:
        """Разобрать «Новое». suggestion — id из dela_view view=new (первых 8 знаков хватит) или «batch:<пачка>» — вся пачка встречи. decision: accept или reject. reason — почему отклонил (это учит разбор). Поля title, project, person, ball, due_date — принять с поправкой."""
        return await run(dela.decide, suggestion, decision, reason, title=title, project=project, person=person,
                         ball=ball, due_date=due_date)

    @mcp.tool()
    async def dela_note(summary: str, kind: str = "note", project: str | None = None, deal: str | None = None,
                        people: list[str] | None = None, next_step: str | None = None, at: str | None = None) -> str:
        """Запись в хронологию клиента и людей (бывший «Лог взаимодействий» Notion): звонок, встреча, переписка, заметка — не дело. kind: call, meeting, zoom, telegram, email, whatsapp, note, other. at — когда (ISO, пусто — сейчас)."""
        return await run(dela.note, summary, kind, project, deal, people, next_step, at)
