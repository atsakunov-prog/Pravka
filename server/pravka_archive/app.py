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

from . import icu, sync, tools
from .auth import SCOPE, OwnerAuth
from .config import Config, env_suffix
from .ingest import ingest_batch

log = logging.getLogger("pravka")

MAX_BODY = 64 * 1024 * 1024

INSTRUCTIONS = """Архив Правки — вся жизнь Саши (владельца) из его приложения и из intervals.icu: лента времени (Засечка), телефон по дням, еда с КБЖУ и витаминами, силовые и зарядка, тренировки с часов, сон и форма, деньги семьи, каждая диктовка. Жизнь — только чтение.

Как читать:
- Начни со schema() — там виды схемы life и свежесть источников. sql() — любой SELECT по ним, считает база, не ты. search() — поиск по всему сказанному (русские словоформы, подстрока, опечатки). day() — сутки целиком одним ответом.
- Сутки — поле day (его решил телефон). Никогда не выводи сутки из времени в UTC.
- Лента: сумма minutes за полные сутки = 1440. source gap — заполнитель «не размечено», auto — сон или тренировка с телефона и часов. raw — дословная надиктовка с ошибками распознавания, comment — осознанные слова о деле: весит больше.
- Еда: в счёт только confirmed. confidence «наугад» — числа прикидочные.
- Деньги: целые копейки (amount_kop, минус — ушло). В счёт — только live (money_live). Траты семьи — shelf = 'family', amount_kop < 0, не income. owner — чьи (sasha, marianna).
- Тренировки и форма — из intervals как есть; поле, которого нет колонкой, — raw->>'поле'.
- Число, которого нет в архиве, не произносится. Свежесть телефона старше часа — скажи, что про сегодня данные могут быть неполными.
- Как ты читал архив — не тема разговора: Саше нужна его жизнь, а не названия видов."""

PEOPLE_INSTRUCTIONS = """
Кроме Саши в архиве — {names}: со своего телефона и своих часов (свой intervals). Те же виды своей схемой: {examples}. schema, search и day — с who (ключ профиля или имя: who="{first}"); в sql пиши схему явно ({first_schema}.entries). Без who — всё про Сашу.
- Деньги семьи — один общий журнал в life.money: owner = '{first}' — операции и надиктовки с того телефона. В схеме {first_schema} денежные виды пусты — общий журнал шлёт телефон Саши.
- «Как спала Марианна», «что она ела», «её тренировки» — её схема; сравнение «у нас с ней» — обе схемы рядом."""

DELA_INSTRUCTIONS = """

Дела — задачи, люди, проекты и сделки Саши (его сервис «Дела» вместо Todoist и Notion CRM). Тут можно и писать.
- Смотреть: dela_view (утро, новое, жду, по человеку, проект, неделя, быстрое, сейчас, поиск), dela_task — дело целиком с журналом. Для счёта и связок с жизнью — sql по life.tasks, life.projects, life.deals, life.people, life.interactions, life.work_time.
- Менять: dela_add, dela_change, dela_decide («Новое»), dela_note (хронология клиента). Дело называется коротким номером «57».
- Человек один на всю систему (встречи, звонки, Telegram, CRM): «Женя Соколов — это Евгений Соколов из Ромашки» — dela_person (name, also — другие имена, phone, telegram; merge_into — слить дубль в живую карточку).
- Формат названия — «Кто: действие». Мяч: mine — моё, waiting — жду от person, agenda — поднять при встрече с person. Деньги: paid — оплата согласована, potential — развитие, пусто — как у проекта.
- Даты пиши сам: YYYY-MM-DD, сегодняшняя дата — в первой строке dela_view.
- «Напомни мне…» — remind_at («YYYY-MM-DD HH:MM» по Москве) или remind_place (место телефона, «дом») в dela_add / dela_change: напоминание придёт Саше в Telegram ботом Ковчега.
- Задачу на Марианну не ставь: только с её согласия, через неё саму.

Вопросы человеческим голосом — дела вместе с жизнью:
- «Что я делаю чаще, что реже, на что уходит время» — время считай по ленте (life.entries: category и title, life.work_time: часы по клиенту и проекту), дела — по life.tasks (created_at, completed_at, project, person, labels, source). История Дел — с 03.10.2026 (Todoist раньше не переносился): частоту по делам называй с этой оговоркой, а за длинный срок опирайся на ленту.
- «Что у меня было» — day() (там и сделанные, и заведённые дела, и встречи, звонки, переписка с клиентами); за неделю и месяц — sql по life.days, life.tasks, life.interactions.
- «Что мне делать» — dela_view morning, потом week; с кем пора поговорить — crm_view ties; что застыло в сделках — crm_view pipeline. Состояние (сон, HRV, нагрузка — life.wellness) учитывай, если спрашивают про силы и план дня.
- Деньги в делах (paid, potential) Саша в интерфейсе убрал: «нагружает». Сам их не поднимай, только если спросит.

CRM ЗФ — в тех же Делах: клиент = проект, внутри сделки со стадиями (lead, proposal — КП, mandate, active, closing, archive с итогом won/lost/paused), гонорар, оплаты (план, счёт, получено), хронология контактов, время из Засечки.
- Смотреть: crm_view (pipeline — воронка, clients — клиенты и «чужие» клиенты Засечки, client, deal, person, ties — кому пора напомнить о себе, money — деньги фирмы). Для счёта — sql по life.deals, life.payments, life.interactions, life.work_time.
- Менять: crm_deal (завести или поправить сделку: стадия, итог, гонорар, команда, кто привёл), crm_payment (оплаты), dela_note (запись в хронологию). Следующий шаг сделки — открытое дело с этой сделкой (dela_add с project и deal), а не текст.
- Деньги фирмы (life.payments) — не деньги семьи (life.money_live).

Встречи — всё, что записала служба встреч (Zoom, очные, звонки), с единым названием «Клиент: тема», участниками, датой и длительностью.
- Найти, где о чём говорили («где мы обсуждали счета с Яблоковым и сколько это стоит»): meetings с query (и client, person) — реплики с номерами; потом meeting с around — окно разговора вокруг реплики. Число и договорённость — из самих реплик, со ссылкой на встречу и дату.
- Список и счёт: meetings, sql по life.meetings (title, client, topic, people, guests, day, duration_min, sphere) и life.meeting_lines (tsv — словоформы, text ILIKE — имена).
- sphere home — личные (терапия, семья): не открывай и не цитируй их без прямой просьбы Саши.
- Поправить название, клиента, участников, «личная / рабочая» — meeting_change: поправленное разбор больше не трогает.

Рацион Саши — его постоянная еда: продукты (КБЖУ на 100 г с его этикеток, обычная порция, как он их называет) и блюда — наборы продуктов с граммами («Моя каша», «Индейка с кускусом»). По нему телефон разбирает еду голосом и пишет её тапом; съеденное — в архиве (life.meals), а не здесь.
- Смотреть: ration (весь или одно блюдо, продукт). Менять: ration_product (завести, поправить, убрать), ration_dish (состав, граммы, приём, как называет; завести, убрать).
- «Убери из каши клубнику» — ration_dish(name="Моя каша", drop=["Клубника"]); «каша теперь с бананом» — add=[{"product": "Банан"}]. Новый продукт с фото этикетки — цифры на 100 г с неё, sure «точно»; цифры на глаз — sure «примерно».
- Как Саша называет продукт и блюдо (aliases) — главное для голоса: дописывай его слова, особенно те, что распознавание коверкает."""


def build(cfg: Config) -> tuple[FastMCP, OwnerAuth]:
    auth = OwnerAuth(cfg)
    others = [p for p in cfg.persons() if not p.owner]
    people_text = ""
    if others:
        people_text = PEOPLE_INSTRUCTIONS.format(
            names=", ".join(p.profile for p in others),
            examples=", ".join(f"{p.schema}.entries, {p.schema}.workouts, {p.schema}.wellness" for p in others),
            first=others[0].profile,
            first_schema=others[0].schema,
        )
    mcp = FastMCP(
        "Архив Правки",
        instructions=INSTRUCTIONS + people_text + (DELA_INSTRUCTIONS if cfg.dela_db_url else ""),
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
    async def schema(view: str | None = None, who: str | None = None) -> str:
        """Карта архива: виды схемы life с описанием и колонками, и свежесть источников. С именем вида — его колонки с типами и пояснениями. Звать первым. who — другой человек архива (marianna): виды его схемы."""
        return await anyio.to_thread.run_sync(tools.schema, cfg, view, who)

    @mcp.tool()
    async def sql(query: str, max_rows: int = 200) -> str:
        """Один SELECT по схеме life (только чтение, 15 секунд, до 500 строк). Суммы, средние, сравнения периодов — здесь: считает база. Другой человек архива — его схемой явно (marianna.entries). Ответ — таблица текстом."""
        return await anyio.to_thread.run_sync(tools.run_sql, cfg, query, max_rows)

    @mcp.tool()
    async def search(
        query: str,
        date_from: str | None = None,
        date_to: str | None = None,
        domain: str | None = None,
        limit: int = 30,
        order: str = "new",
        who: str | None = None,
    ) -> str:
        """Поиск по всему, что Саша говорил и писал: надиктовки ленты, еды, силовых и денег, комментарии к делам, каждая диктовка Правки, вопросы тренеру, комментарии к тренировкам и форме. Русские словоформы («Анной» по «Марианна»), если пусто — подстрока, потом похожие слова. Даты — ГГГГ-ММ-ДД; domain — засечка, еда, силовые, деньги, правка, тренер, форма, тренировки; order — new (свежее сверху) или best (точнее сверху); who — другой человек архива (marianna): искать в его сказанном."""
        return await anyio.to_thread.run_sync(tools.search, cfg, query, date_from, date_to, domain, limit, order, who)

    @mcp.tool()
    async def day(date: str, full: bool = False, who: str | None = None) -> str:
        """Сутки целиком одной лентой: лента времени со словами Саши, сделанные и заведённые дела, встречи и звонки с клиентами, еда, тренировки, сон и форма, силовые и зарядка, траты, телефон, сколько диктовал. date — ГГГГ-ММ-ДД. full — тексты целиком, а не первые строки. who — другой человек архива (marianna): его сутки."""
        return await anyio.to_thread.run_sync(tools.day, cfg, date, full, who)

    if cfg.dela_db_url:
        _dela_tools(mcp, cfg.dela_db_url)

    @mcp.custom_route("/login", methods=["GET", "POST"])
    async def login(request: Request) -> Response:
        return await auth.login_page(request)

    @mcp.custom_route("/health", methods=["GET"])
    async def health(request: Request) -> Response:
        return JSONResponse({"ok": True, "service": "pravka-archive", "time": dt.datetime.now().astimezone().isoformat(timespec="seconds")})

    async def caller(request: Request):
        """Кто пришёл: «phone» (общий токен телефона), (имя, виды) службы компа или None."""
        header = request.headers.get("authorization", "")
        token = header[7:] if header.lower().startswith("bearer ") else ""
        if not token:
            return None
        if hmac.compare_digest(token.encode(), cfg.ingest_token.encode()):
            return "phone"

        def find():
            with psycopg.connect(cfg.db_url, autocommit=True) as conn:
                return sync.service_of(conn, token)

        return await anyio.to_thread.run_sync(find)

    async def body_json(request: Request):
        body = await request.body()
        if len(body) > MAX_BODY:
            return None, JSONResponse({"error": "пачка больше 64 МБ — дели на части"}, status_code=413)
        if request.headers.get("content-encoding", "").lower() == "gzip":
            try:
                body = gzip.decompress(body)
            except OSError:
                return None, JSONResponse({"error": "gzip не распаковался"}, status_code=400)
        try:
            return json.loads(body), None
        except ValueError as e:
            return None, JSONResponse({"error": f"не JSON: {e}"}, status_code=400)

    @mcp.custom_route("/ingest", methods=["POST"])
    async def ingest(request: Request) -> Response:
        who = await caller(request)
        if who is None:
            return JSONResponse({"error": "нет или не тот токен телефона"}, status_code=401)
        batch, err = await body_json(request)
        if err is not None:
            return err

        def work():
            with psycopg.connect(cfg.db_url, autocommit=True) as conn:
                others = [p.profile for p in cfg.persons() if not p.owner]
                return ingest_batch(conn, batch, cfg.profile, others, service=None if who == "phone" else who)

        status, payload = await anyio.to_thread.run_sync(work)
        return JSONResponse(payload, status_code=status)

    @mcp.custom_route("/sync", methods=["POST"])
    async def sync_route(request: Request) -> Response:
        """Обратная дорога (13.10.2026): что поменял кто-то другой — телефону правки с компа,
        службе компа — правки с телефона. Тело: device, profile, kinds, since (cursor прошлого
        ответа или пусто), own. Ответ: cursor и записи — последнее слово по каждой."""
        who = await caller(request)
        if who is None:
            return JSONResponse({"error": "нет или не тот токен"}, status_code=401)
        req, err = await body_json(request)
        if err is not None:
            return err
        if not isinstance(req, dict):
            return JSONResponse({"error": "тело — объект JSON"}, status_code=400)
        device = req.get("device")
        if not isinstance(device, str) or not device:
            return JSONResponse({"error": "device — имя устройства, как в /ingest"}, status_code=400)
        allowed = sync.SYNC_KINDS if who == "phone" else who[1]
        if who != "phone" and device != sync.service_device(who[0]):
            return JSONResponse({"error": f"служба «{who[0]}» — только устройство {sync.service_device(who[0])}"}, status_code=403)
        kinds = req.get("kinds") or sorted(allowed)
        if not isinstance(kinds, list) or any(k not in allowed for k in kinds):
            return JSONResponse({"error": f"забрать можно только {', '.join(sorted(allowed))}"}, status_code=403)
        person = cfg.person_of(req.get("profile"))
        if person is None:
            return JSONResponse({"error": f"профиль «{req.get('profile')}» архив не знает"}, status_code=403)

        def work():
            with psycopg.connect(cfg.db_url, autocommit=True) as conn:
                return sync.pull(conn, person.key, kinds, req.get("since"), device, own=bool(req.get("own")))

        try:
            payload = await anyio.to_thread.run_sync(work)
        except sync.SyncError as e:
            return JSONResponse({"error": str(e)}, status_code=400)
        return JSONResponse(payload)

    return mcp, auth


async def pull_forever(cfg: Config) -> None:
    """Сборщики intervals всех людей архива: раз в 10 минут свежее, раз в сутки глубоко, один раз всё."""
    pullers = [icu.Puller(cfg, person=p) for p in cfg.persons() if p.has_icu]
    for p in cfg.persons():
        if not p.has_icu:
            suffix = "" if p.owner else f"_{env_suffix(p.profile)}"
            log.info("%s: ключа нет — сборщик спит (ICU_ATHLETE_ID%s, ICU_API_KEY%s)", p.icu_source, suffix, suffix)
    # Каждый своим циклом: первая выгрузка всей истории Марианны идёт до часа,
    # и свежие тренировки хозяина всё это время не должны ждать её.
    await asyncio.gather(*(_pull_loop(cfg, p) for p in pullers))


async def _pull_loop(cfg: Config, puller: icu.Puller) -> None:
    while True:
        try:
            def once():
                with psycopg.connect(cfg.db_url, autocommit=True) as conn:
                    row = conn.execute("SELECT note FROM core.sources WHERE source = %s", (puller.device,)).fetchone()
                mode = icu.due(row[0] if row else None, dt.datetime.now(dt.timezone.utc)) or "recent"
                return mode, puller.run(mode)
            mode, out = await anyio.to_thread.run_sync(once)
            changed = sum(out.values())
            if changed or mode != "recent":
                log.info("%s %s: %s", puller.device, mode, out)
        except Exception as e:  # сборщик не должен ронять сервис
            log.warning("%s: %s: %s", puller.device, e.__class__.__name__, e)
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
        for p in cfg.persons():
            if p.has_icu:
                conn.execute(
                    "INSERT INTO core.sources (source, note) VALUES (%s, %s) ON CONFLICT (source) DO NOTHING",
                    (p.icu_source, Jsonb({"why": "сборщик только запущен"})),
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
    from .night import night_forever

    jobs = [server.serve(), pull_forever(cfg), night_forever(cfg)]
    parent = os.environ.get("PRAVKA_SUPERVISOR_PID", "")
    if parent.isdigit():
        jobs.append(watch_supervisor(int(parent)))
    await asyncio.gather(*jobs)


def _dela_tools(mcp: FastMCP, url: str) -> None:
    """Инструменты Дел и CRM живут у Дел (pravka_dela/mcp_tools.register): подпись, описание для Claude
    и код — в одном месте. До 06.10.2026 подписи жили здесь, и новое поле дела правилось дважды."""
    from pravka_dela import mcp_tools

    mcp_tools.register(mcp, url)
