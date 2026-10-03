"""python -m pravka_dela <команда>

  migrate  схемы crm и tasks, права роли службы, виды для Claude (ролью владельца базы)
  serve    служба: API на DELA_LISTEN
  check    настройки и база — словами
  user     завести или поправить пользователя
  token    выдать токен службе или устройству (печатается один раз)
  pair     QR для телефона: адрес и новый токен устройства
  invite   одноразовая ссылка входа в веб для пользователя
  revoke   отозвать токены по имени
  import   перенос из Todoist, Notion и ленты (сухой прогон по умолчанию)
  export-todoist  выгрузка Todoist по API (с комментариями) для переноса
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import sys
from logging.handlers import RotatingFileHandler
from pathlib import Path

from pravka_archive import config as archive_config

from . import config as config_mod
from . import db, tokens

log = logging.getLogger("dela")


def _logging(cfg: config_mod.Config) -> None:
    cfg.logs.mkdir(parents=True, exist_ok=True)
    fmt = logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s")
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    for h in (logging.StreamHandler(sys.stdout), RotatingFileHandler(cfg.logs / "dela.log", maxBytes=5_000_000, backupCount=5, encoding="utf-8")):
        h.setFormatter(fmt)
        root.addHandler(h)


def cmd_migrate(args) -> int:
    owner = archive_config.load(args.owner_env)
    cfg = config_mod.load(args.env)
    role = args.app_role or cfg.app_role
    done = db.migrate(owner.db_url, role, owner.reader_role)
    print("migrate:", ", ".join(done) or "всё уже на месте")
    return 0


def cmd_check(args) -> int:
    cfg = config_mod.load(args.env)
    bad = cfg.problems()
    for p in bad:
        print("НЕТ ", p)
    if not bad:
        with db.session(cfg.db_url, "system", "svc:check") as conn:
            users = [r["id"] for r in conn.execute("SELECT id FROM crm.users ORDER BY id")]
            n = conn.execute("SELECT count(*) AS n FROM tasks.tasks WHERE status = 'open'").fetchone()["n"]
            life = conn.execute("SELECT has_schema_privilege('life', 'USAGE') AS x").fetchone()["x"]
        print("ок   база:", cfg.app_role, "· пользователи:", ", ".join(users) or "нет", "· открытых дел:", n)
        if life:
            bad.append("роль службы видит схему life — так быть не должно")
            print("НЕТ  роль службы видит life (деньги, здоровье, диктовки)")
        else:
            print("ок   роль службы не видит life")
    return 1 if bad else 0


def cmd_user(args) -> int:
    cfg = config_mod.load(args.env)
    settings = json.loads(args.settings) if args.settings else {}
    with db.session(cfg.db_url, "system", "svc:cli") as conn:
        conn.execute(
            "INSERT INTO crm.users (id, name, role, telegram_id, settings) VALUES (%s, %s, %s, %s, %s) "
            "ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, role = EXCLUDED.role, "
            "telegram_id = coalesce(EXCLUDED.telegram_id, crm.users.telegram_id), "
            "settings = crm.users.settings || EXCLUDED.settings",
            (args.id, args.name, args.role, args.telegram, json.dumps(settings)),
        )
    print(f"пользователь {args.id} записан")
    return 0


def cmd_token(args) -> int:
    cfg = config_mod.load(args.env)
    tok = tokens.issue(cfg.db_url, args.user, args.kind, args.name)
    if args.out:
        Path(args.out).write_text(tok, encoding="utf-8")
        print(f"токен «{args.name}» записан в {args.out}")
    else:
        print(tok)
    return 0


PAIR_PREFIX = "pravka-dela:"


def cmd_pair(args, ask=input) -> int:
    """QR для Правки: адрес службы и новый токен этого телефона. Картинкой и
    только на экране компа, удаляется после сканирования — как у архива."""
    import segno

    cfg = config_mod.load(args.env)
    if not cfg.phone_url.startswith("https://"):
        print("DELA_PHONE_URL не задан или не https — телефону некуда ходить")
        return 1
    tok = tokens.issue(cfg.db_url, args.user, "device", args.name)
    payload = PAIR_PREFIX + json.dumps({"url": cfg.phone_url, "token": tok}, separators=(",", ":"))
    png = cfg.data / "secrets" / "pair-qr.png"
    segno.make(payload, error="m").save(str(png), kind="png", scale=10, border=4, dark="black", light="white")
    try:
        if hasattr(os, "startfile"):
            os.startfile(str(png))  # type: ignore[attr-defined]
        print(f"QR открыт: {png}. В Правке: Настройки → Подключения → Дела → «Сканировать QR с компа».")
        ask("Отсканировал? Enter — картинка удалится (в ней токен). ")
    finally:
        png.unlink(missing_ok=True)
    return 0


def cmd_invite(args) -> int:
    """Одноразовая ссылка входа в веб (двое суток). Отдать человеку лично."""
    cfg = config_mod.load(args.env)
    code = tokens.invite(cfg.db_url, args.user)
    base = args.base or cfg.public_url or f"http://127.0.0.1:{cfg.listen_port}"
    link = f"{base}/#invite={code}"
    if args.out:
        Path(args.out).write_text(link + chr(10), encoding="utf-8")
        print(f"ссылка входа для {args.user} — в {args.out} (одноразовая, двое суток)")
    else:
        print(link)
    return 0


def cmd_revoke(args) -> int:
    cfg = config_mod.load(args.env)
    print("отозвано:", tokens.revoke(cfg.db_url, args.name))
    return 0


def cmd_serve(args) -> int:
    import asyncio

    import uvicorn

    from . import api, store

    cfg = config_mod.load(args.env)
    if cfg.problems():
        for p in cfg.problems():
            print("НЕТ ", p)
        return 2
    _logging(cfg)

    async def bridge_loop():
        # До переезда телефона: новые задачи Todoist — в Дела, раз в 10 минут.
        from . import bridge

        while cfg.todoist_token:
            try:
                data = await asyncio.to_thread(bridge.fetch, cfg.todoist_token)
                await asyncio.to_thread(bridge.pull_new, cfg.db_url, data)
            except Exception as e:
                log.warning("мост Todoist: %s", e)
            await asyncio.sleep(600)

    async def chores():
        # Раз в час: неразобранные предложения старше недели гаснут.
        while True:
            try:
                n = await asyncio.to_thread(store.expire_suggestions, cfg.db_url)
                if n:
                    log.info("погасло предложений: %d", n)
            except Exception:
                log.exception("уборка предложений упала")
            await asyncio.sleep(3600)

    async def main():
        server = uvicorn.Server(uvicorn.Config(
            api.build(cfg), host=cfg.listen_host, port=cfg.listen_port,
            log_level="info", log_config=None, proxy_headers=False, server_header=False,
        ))
        await asyncio.gather(server.serve(), chores(), bridge_loop())

    log.info("Дела: слушаю %s:%d", cfg.listen_host, cfg.listen_port)
    asyncio.run(main())
    return 0


def cmd_import(args) -> int:
    from . import importer

    cfg = config_mod.load(args.env)
    return importer.run(cfg, args)


def cmd_export_todoist(args) -> int:
    """Выгрузка Todoist по API в файл — для переноса с комментариями. Токен — из dela.env."""
    from . import bridge

    cfg = config_mod.load(args.env)
    if not cfg.todoist_token:
        print("DELA_TODOIST_TOKEN в dela.env пуст — выгружать нечем")
        return 1
    data = bridge.export(cfg.todoist_token)
    Path(args.out).write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"Todoist: проектов {len(data['projects'])}, задач {len(data['tasks'])}, комментариев {len(data['comments'])} — {args.out}")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="pravka_dela", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--env", help=f"файл секретов Дел (с завода {config_mod.DEFAULT_ENV_FILE})")
    sub = ap.add_subparsers(dest="cmd", required=True)

    m = sub.add_parser("migrate")
    m.add_argument("--owner-env", default=archive_config.DEFAULT_ENV_FILE, help="server.env архива: оттуда адрес владельца базы")
    m.add_argument("--app-role", help="роль службы (с завода — из DELA_DB_URL)")
    m.set_defaults(fn=cmd_migrate)

    sub.add_parser("check").set_defaults(fn=cmd_check)
    sub.add_parser("serve").set_defaults(fn=cmd_serve)

    u = sub.add_parser("user")
    u.add_argument("id")
    u.add_argument("name")
    u.add_argument("--role", default="member", choices=["owner", "member"])
    u.add_argument("--telegram", type=int)
    u.add_argument("--settings", help='JSON, например {"assign": "ask", "reminders": false}')
    u.set_defaults(fn=cmd_user)

    t = sub.add_parser("token")
    t.add_argument("--user", required=True)
    t.add_argument("--kind", default="service", choices=["service", "device"])
    t.add_argument("--name", required=True, help="mcp, meetings, userbot, «телефон Саши»…")
    t.add_argument("--out", help="записать в файл, а не на экран")
    t.set_defaults(fn=cmd_token)

    p = sub.add_parser("pair")
    p.add_argument("--user", default="sasha")
    p.add_argument("--name", default="телефон")
    p.set_defaults(fn=cmd_pair)

    iv = sub.add_parser("invite")
    iv.add_argument("user")
    iv.add_argument("--base", help="адрес веба (с завода DELA_PUBLIC_URL)")
    iv.add_argument("--out", help="записать ссылку в файл, а не на экран")
    iv.set_defaults(fn=cmd_invite)

    r = sub.add_parser("revoke")
    r.add_argument("name")
    r.set_defaults(fn=cmd_revoke)

    i = sub.add_parser("import")
    i.add_argument("--todoist", help="выгрузка Todoist (JSON)")
    i.add_argument("--notion", help="папка с выгрузкой Notion CRM (JSON)")
    i.add_argument("--clients", help="клиенты из ленты (JSON)")
    i.add_argument("--plan", help="решения владельца по спорному (JSON)")
    i.add_argument("--apply", action="store_true", help="записать в базу (без него — только отчёт)")
    i.add_argument("--report", help="куда положить отчёт (Markdown)")
    i.set_defaults(fn=cmd_import)

    e = sub.add_parser("export-todoist")
    e.add_argument("--out", required=True)
    e.set_defaults(fn=cmd_export_todoist)

    args = ap.parse_args(argv)
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
