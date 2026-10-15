"""python -m pravka_dela <команда>

  migrate  схемы crm и tasks, права роли службы, виды для Claude (ролью владельца базы)
  serve    служба: API на DELA_LISTEN
  check    настройки и база — словами
  user     завести или поправить пользователя
  telegram  чей Telegram: напоминания дел уходят на этот id (бот Ковчега)
  remind-notes  перенос строк «⏰ Напомнить…» из заметок в напоминания (разово, повтор безвреден)
  token    выдать токен службе или устройству (печатается один раз)
  pair     QR для телефона: адрес и новый токен устройства
  family   кто в семье для «Где мы» (карта Правки): видит точки семьи и делится своей
  invite   одноразовая ссылка входа в веб для пользователя
  revoke   отозвать токены по имени
  import   перенос из Todoist, Notion и ленты (сухой прогон по умолчанию)
  export-todoist  выгрузка Todoist по API (с комментариями) для переноса
  svod     Свод: list, get <ключ>, set <ключ> --file (промпты, словарь, правила — одна правда)
  krug-seed  Круг жизни: направления и принципы из файла владельца в Свод, направления проектам
  meetings  встречи: что с разбором; rename [--all | --ref N] — разобрать заново (замки владельца остаются);
            touch [--since 2026-09-01] [--dry] — записи встреч в хронологии и пачки «Нового» по карточкам (dela_0021)
  digest-relink [--dry]  сводки дайджеста заново по карточкам CRM: упоминание и сделка записи (dela_0022, разово)
  privacy-fix [--dry] [--locked-private]  пакет «Приватность» по прежним данным: основания и заметки машины —
            приватными, записи встреч — по переключателю, события доступа (dela_0024–0028, разово, после своей копии)
  zachem   «Зачем» по-человечески: propose [--limit N] [--now] --out файл; apply --file файл; rollback
  guard    защита от петли Claude (аудит №12): status — что стоит; on ЗАДАЧА — «Продолжать» (час пороги втрое);
           keep ЗАДАЧА — оставить остановленным (когда Telegram недоступен)
  debug    режим отладки: list [--all], show N [--json] [--shot файл], set N статус ["ответ" | --answer-file f], on, off
  plan     «Сейчас на завтра»: без действия — только показать кандидатов на завтра (ничего не пишет); run [--dry]
           [--for ДАТА] — записать (на день, где кандидаты уже были, — ничего); --list — и сами кандидаты; snap [--day
           ДАТА] — снимок «Сейчас» дня; status — последний прогон и снимки (служба делает всё это сама раз в минуту)
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
from . import db, guard, tokens

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
    """Завести или поправить пользователя. Доступ к клиентам и деньгам — только так
    (самому человеку база их менять не даёт)."""
    cfg = config_mod.load(args.env)
    settings = json.loads(args.settings) if args.settings else {}
    with db.session(cfg.db_url, "system", "svc:cli") as conn:
        person = None
        if args.person:
            found = conn.execute(
                "SELECT id, name FROM crm.people WHERE archived_at IS NULL AND (id::text = %s OR crm.norm(name) = crm.norm(%s) "
                "OR crm.norm(short) = crm.norm(%s))", (args.person, args.person, args.person)
            ).fetchall()
            if len(found) != 1:
                print(f"человек «{args.person}» не определился: " + (", ".join(r["name"] for r in found) or "нет такого"))
                return 1
            person = found[0]["id"]
        conn.execute(
            "INSERT INTO crm.users (id, name, role, telegram_id, settings, person_id, clients, sees_money) "
            "VALUES (%s, %s, %s, %s, %s, %s, coalesce(%s, 'own'), coalesce(%s, false)) "
            "ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, role = EXCLUDED.role, "
            "telegram_id = coalesce(EXCLUDED.telegram_id, crm.users.telegram_id), "
            "settings = crm.users.settings || EXCLUDED.settings, "
            "person_id = coalesce(%s, crm.users.person_id), "
            "clients = coalesce(%s, crm.users.clients), sees_money = coalesce(%s, crm.users.sees_money)",
            (args.id, args.name, args.role, args.telegram, json.dumps(settings), person, args.clients, args.money,
             person, args.clients, args.money),
        )
        if person:
            conn.execute("UPDATE crm.people SET user_id = %s WHERE id = %s AND user_id IS DISTINCT FROM %s",
                         (args.id, person, args.id))
        u = conn.execute("SELECT clients, sees_money, person_id FROM crm.users WHERE id = %s", (args.id,)).fetchone()
    print(f"пользователь {args.id} записан: клиенты — {u['clients']}, деньги — {'да' if u['sees_money'] else 'нет'}"
          + ("" if u["person_id"] else " (не связан с человеком справочника: --person)"))
    return 0


def cmd_family(args) -> int:
    """Кто в семье для «Где мы» (10.10.2026): видит точки семьи на карте Правки и делится своей.
    Только из командной строки: сам себя в семью человек не добавит (crm.users_guard). Без
    аргументов — кто сейчас в семье. Убранный из семьи сразу перестаёт видеть и его точка уходит."""
    cfg = config_mod.load(args.env)
    with db.session(cfg.db_url, "system", "svc:cli") as conn:
        if args.user:
            row = conn.execute("UPDATE crm.users SET family = %s WHERE id = %s RETURNING id",
                               (not args.off, args.user)).fetchone()
            if not row:
                print(f"пользователя {args.user} нет — сначала команда user")
                return 1
            if args.off:
                conn.execute("DELETE FROM crm.where_points WHERE user_id = %s", (args.user,))
                conn.execute("DELETE FROM crm.where_asks WHERE user_id = %s", (args.user,))
        rows = conn.execute("SELECT id, name FROM crm.users WHERE family ORDER BY id").fetchall()
    print("в семье: " + (", ".join(f"{r['id']} ({r['name']})" for r in rows) or "никого"))
    return 0


def cmd_telegram(args) -> int:
    """Telegram пользователя — куда бот Ковчега шлёт его напоминания. Только из командной строки:
    самому человеку база telegram_id менять не даёт (crm.users_guard)."""
    cfg = config_mod.load(args.env)
    with db.session(cfg.db_url, "system", "svc:cli") as conn:
        row = conn.execute("UPDATE crm.users SET telegram_id = %s WHERE id = %s RETURNING id", (args.telegram_id, args.user)).fetchone()
    if not row:
        print(f"пользователя {args.user} нет")
        return 1
    print(f"{args.user}: Telegram {str(args.telegram_id)[:4]}… — напоминания пойдут туда")
    return 0


def cmd_remind_notes(args) -> int:
    from . import remind

    cfg = config_mod.load(args.env)
    done = remind.move_notes(cfg.db_url, apply=args.apply)
    for line in done:
        print(("перенёс  " if args.apply else "перенесу ") + line)
    print(f"напоминаний из заметок: {len(done)}" + ("" if args.apply else " (сухой прогон — нужен --apply)"))
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
        if not args.wait:
            ask("Отсканировал? Enter — картинка удалится (в ней токен). ")
            return 0
        # Без консоли (запуск из Claude): ждём, пока телефон сам проверит токен
        # (GET /api/me), и убираем картинку. Не дождались — токен отзываем.
        import time

        deadline = time.time() + args.wait
        while time.time() < deadline:
            with db.session(cfg.db_url, "system", "svc:pair") as conn:
                seen = conn.execute(
                    "SELECT last_seen_at FROM crm.tokens WHERE token_hash = %s", (tokens._hash(tok),)
                ).fetchone()["last_seen_at"]
            if seen:
                print("телефон подключился — картинка удалена")
                return 0
            time.sleep(2)
        with db.session(cfg.db_url, "system", "svc:pair") as conn:
            conn.execute("UPDATE crm.tokens SET revoked_at = now() WHERE token_hash = %s", (tokens._hash(tok),))
        print("телефон так и не подключился — токен отозван, картинка удалена")
        return 1
    finally:
        png.unlink(missing_ok=True)


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

    from . import api, capture, spend, store

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
        # Один раз: прежний учёт трат Claude Дел — в книгу строками «до книги» (spend.backfill, 14.10.2026).
        try:
            n = await asyncio.to_thread(spend.backfill, cfg.db_url)
            if n:
                log.info("книга трат Claude: прежний учёт Дел — %d дней", n)
        except Exception:
            log.exception("книга трат Claude: прежний учёт")
        # Раз в час: неразобранные предложения старше недели гаснут; дела «потом», которые вернулись и остались
        # нетронутыми, уходят на ближайший понедельник (захват, capture.back_shift).
        while True:
            try:
                n = await asyncio.to_thread(store.expire_suggestions, cfg.db_url)
                if n:
                    log.info("погасло предложений: %d", n)
            except Exception:
                log.exception("уборка предложений упала")
            try:
                n = await asyncio.to_thread(capture.back_shift, cfg.db_url)
                if n:
                    log.info("«Вернулось» не тронуто — на понедельник: %d", n)
            except Exception:
                log.exception("перенос дел «потом»")
            await asyncio.sleep(3600)

    async def krug_loop():
        # Разборщик Круга (krug_auto.py): раз в минуту — досмотреть пачку или, в свой слот, отправить новую.
        # Слоты — по слову владельца: будни раз в полчаса, выходные раз в час, ночью с 00 до 8 — тишина.
        # Там же (place.py, 13.10.2026): блок «дерево жизни» для разборов — в Свод, если поменялся (не чаще раза в
        # пять минут), и раз в неделю — выводы из правок владельца.
        from . import krug_auto, place

        while cfg.anthropic_key:
            try:
                await asyncio.to_thread(krug_auto.tick, cfg.db_url, cfg.anthropic_key, cfg.claude_proxy)
            except guard.Stopped:
                pass  # защита от петли (аудит №12): тик пропускается, строка журнала — одна, при срабатывании
            except Exception:
                log.exception("разборщик Круга")
            try:
                await asyncio.to_thread(place.refresh, cfg.db_url)
            except Exception:
                log.exception("дерево жизни для разборов")
            try:
                await asyncio.to_thread(place.tune, cfg.db_url, cfg.anthropic_key, cfg.claude_proxy)
            except guard.Stopped:
                pass
            except Exception:
                log.exception("выводы Круга из правок")
            await asyncio.sleep(60)

    async def meetings_loop():
        # Разборщик встреч (meetings_auto.py): раз в минуту — досмотреть пачку или, если есть неразобранные
        # встречи, отправить новую. Нечего разбирать — ни запроса.
        from . import meetings_auto

        while cfg.anthropic_key:
            try:
                await asyncio.to_thread(meetings_auto.tick, cfg.db_url, cfg.anthropic_key, cfg.claude_proxy)
            except guard.Stopped:
                pass
            except Exception:
                log.exception("разборщик встреч")
            await asyncio.sleep(60)

    async def spend_loop():
        # Советник по тратам Claude (spend_advice.py, 14.10.2026): раз в минуту — досмотреть пачку или, в воскресенье
        # в 21:30 по Москве, отправить сводку недели. Трат нет — ни запроса.
        from . import spend_advice

        while cfg.anthropic_key:
            try:
                await asyncio.to_thread(spend_advice.tick, cfg.db_url, cfg.anthropic_key, cfg.claude_proxy)
            except guard.Stopped:
                pass
            except Exception:
                log.exception("советы по тратам Claude")
            await asyncio.sleep(60)

    async def capture_loop():
        # Захват (capture.py, 15.10.2026): фразы, которые правило не узнало, ждут модели (pending) — capture.loop.
        await capture.loop(cfg.db_url, cfg.anthropic_key, cfg.claude_proxy)
    async def rules_loop():
        # Правила без Claude раз в минуту (шаг 1 петли этапа 4, 15.10.2026) — без условия на ключ: им он не нужен.
        # «Сейчас на завтра» (plan.py): уборка кандидатов, снимок «Сейчас» (11:00, 19:00, 23:55) и в свой слот прогон.
        # Шаг 6 добавит сюда же карточку недели ПОСЛЕ plan.tick (снимок 23:55 раньше мер дня) — у каждого свой try.
        from . import plan

        while True:
            try:
                await asyncio.to_thread(plan.tick, cfg.db_url)
            except Exception:
                log.exception("«Сейчас на завтра»")
            await asyncio.sleep(60)

    async def main():
        server = uvicorn.Server(uvicorn.Config(
            api.build(cfg), host=cfg.listen_host, port=cfg.listen_port,
            log_level="info", log_config=None, proxy_headers=False, server_header=False,
        ))
        await asyncio.gather(server.serve(), chores(), bridge_loop(), krug_loop(), meetings_loop(), spend_loop(),
                             capture_loop(), rules_loop())

    log.info("Дела: слушаю %s:%d", cfg.listen_host, cfg.listen_port)
    asyncio.run(main())
    return 0


def cmd_import(args) -> int:
    from . import importer

    cfg = config_mod.load(args.env)
    return importer.run(cfg, args)


def cmd_import_log(args) -> int:
    """Лог взаимодействий Notion — в хронологию CRM. Без --apply — только отчёт."""
    from . import importer

    cfg = config_mod.load(args.env)
    text = importer.notion_log(cfg.db_url, Path(args.file), args.apply)
    out = Path(args.report or "notion-log-report.md")
    out.write_text(text, encoding="utf-8")
    print(f"отчёт: {out}" + ("" if args.apply else " (сухой прогон: база не тронута, --apply — записать)"))
    return 0


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


def cmd_svod(args) -> int:
    """Свод из командной строки: сессии Claude на компе правят промпт здесь, а не копией в коде."""
    from . import store

    cfg = config_mod.load(args.env)
    user = args.user
    if args.action == "list":
        for it in store.view(cfg.db_url, user, "svod", prefix=args.key)["items"]:
            size = len(it["body"]) if it["body"] is not None else len(json.dumps(it["value"], ensure_ascii=False))
            print(f"{it['key']:32} v{it['rev']:<4} {it['author']:10} {str(it['updated_at'])[:16]}  {size} зн.  {it['reason'] or ''}")
        return 0
    if not args.key:
        print("нужен ключ")
        return 2
    if args.action == "get":
        items = store.view(cfg.db_url, user, "svod", key=args.key)["items"]
        if not items:
            print(f"нет ключа {args.key}")
            return 1
        it = items[0]
        print(it["body"] if it["body"] is not None else json.dumps(it["value"], ensure_ascii=False, indent=1))
        return 0
    text = Path(args.file).read_text(encoding="utf-8-sig") if args.file else sys.stdin.read()
    op = {"op": "svod.set", "key": args.key, "author": args.author, "reason": args.reason}
    op["value" if args.json else "body"] = json.loads(text) if args.json else text.strip()
    r = store.apply_ops(cfg.db_url, user, [op], via="cli", actor=f"cli:{args.author}")["results"][0]
    print(f"записал {args.key} v{r['row']['rev']}" if r["ok"] else f"не записал: {r['error']}")
    return 0 if r["ok"] else 1


def cmd_krug_seed(args) -> int:
    """Круг жизни: направления и принципы из файла владельца — в Свод, направления — проектам по правилам файла,
    дерево целей (7.2) — один раз на версию файла. Файл личный и лежит на компе (не в репозитории); повтор не
    трогает уже записанное (--force — переписать Свод и пройти дерево ещё раз)."""
    from . import krug

    cfg = config_mod.load(args.env)
    data = json.loads(Path(args.file).read_text(encoding="utf-8-sig"))
    out = krug.seed(cfg.db_url, args.user, data, force=args.force)
    print("Свод:", ", ".join(out["svod"]) or "уже был — не трогал")
    if out["merged"]:
        print("Слил направления в строках Круга:", out["merged"])
    print("Проекты:", "; ".join(out["projects"]) or "размечены раньше — не трогал")
    t = out["tree"]
    if t:
        print(f"Дерево: целей заведено {t['goals']}, дел {t['created'] - t['goals']}, перевешено дел {t['linked']}, "
              f"проектов на цели {len(t['projects'])}" + (f", переименовано: {'; '.join(t['renamed'])}" if t["renamed"] else "")
              + (f"; не нашёл: {', '.join(t['missing'])}" if t["missing"] else ""))
    else:
        print("Дерево: уже посажено — не трогал")
    return 0


def cmd_meetings(args) -> int:
    """Встречи: что с разбором, и отправить на разбор заново (все, по номерам или неудавшиеся)."""
    from . import meetings_auto

    cfg = config_mod.load(args.env)
    if args.action == "touch":
        # Записи встреч в хронологии и пачки «Нового» — по карточкам (аудит №6, №43; dela_0021). Перед запуском без --dry
        # — своя копия базы (правило 9): pravka-before-meetings-touch.dump.
        from . import meetings

        r = meetings.touch_all(cfg.db_url, since=args.since, dry=args.dry)
        a = r["actions"]
        same = {k: v for k, v in a.items() if k not in ("заведена", "обновлена", "убрана", "убрана у двойника")}
        print(("вхолостую (ничего не записано): " if r["dry"] else "") +
              f"встреч с {args.since}: {r['meetings']}; записи в хронологии — создано {a.get('заведена', 0)}, "
              f"обновлено {a.get('обновлена', 0)}, убрано {a.get('убрана', 0) + a.get('убрана у двойника', 0)}, "
              f"без изменений {sum(same.values())}"
              + (f" ({', '.join(f'{k} {v}' for k, v in sorted(same.items()))})" if same else "")
              + f"; пачек «Нового» с новым названием: {r['retitled']}")
        if r["held"]:
            # Запись этих встреч правил или убирал человек (и до установки — замок по журналу, dela_0021): сервер их не
            # трогает. Вернуть встречу правилу — meeting.set unlock touch («убрано из хронологии — вернуть» в вебе).
            print("под замком — запись в руках человека, не трогаю: "
                  + "; ".join(f"№{h['ref']} {h['day']} «{h['title'] or 'без названия'}»" for h in r["held"]))
        return 0
    if args.action == "rename":
        n = meetings_auto.rename(cfg.db_url, everything=args.all, refs=args.ref)
        print(f"на разбор заново: {n} (замки владельца остаются; служба разберёт в ближайшие минуты)")
    st = meetings_auto.status(cfg.db_url)
    print(f"встреч: {st['n']}, ждут разбора: {st['waiting']}, не вышло: {st['failed']}, с вопросом: {st['asks']}")
    print(f"пачка в работе: {st['batch'] or 'нет'}; последний итог: {st['last'] or '—'}")
    return 0


def cmd_digest_relink(args) -> int:
    """Сводки дайджеста — заново по карточкам CRM (аудит №28, №3; dela_0022): флаг упоминания и сделка касания старым
    записям. Перед запуском без --dry — своя копия базы (правило 9): pravka-before-digest-relink.dump."""
    from . import digest_crm

    cfg = config_mod.load(args.env)
    r = digest_crm.relink(cfg.db_url, dry=args.dry)
    b, a = r["before"], r.get("after") or r["before"]
    print(("вхолостую (ничего не записано): " if r["dry"] else "")
          + f"сводок: {r['digests']}; записей дайджеста в хронологии было {b['live']}, стало {a['live']} "
          f"(новых {r['new']}, убрано {r['retired']}); упоминаний было {b['mentions']}, стало {a['mentions']}; "
          f"со сделкой было {b['with_deal']}, стало {a['with_deal']}")
    # Какие записи уйдут и придут — списком: relink разносит историю заново нынешним «кто это», и пункт, где человек или
    # клиент перестал узнаваться уверенно, теряет запись (её правка владельцем журнала не имеет). «Убрано» больше нуля —
    # решить до запуска без --dry.
    for title, rows in (("убрано", r["retired_rows"]), ("новые", r["new_rows"])):
        if rows:
            print(f"{title} ({len(rows)}):")
            for x in rows:
                print(f"  {x['ref']}  {(x['summary'] or '')[:140]}")
    return 0


def cmd_privacy_fix(args) -> int:
    """Пакет «Приватность» по прежним данным (аудит №8–11; privacy_fix.py). Перед запуском без --dry — своя копия базы
    (правило 9): pravka-before-privacy.dump."""
    from . import privacy_fix

    cfg = config_mod.load(args.env)
    print(privacy_fix.words(privacy_fix.run(cfg.db_url, dry=args.dry, locked_private=args.locked_private)))
    return 0


def cmd_guard(args) -> int:
    """Защита от петли Claude (аудит №12, guard.py): что стоит; «Продолжать» и «Оставить» — когда Telegram недоступен."""
    cfg = config_mod.load(args.env)
    if args.action == "status":
        st = guard.status(cfg.db_url)
        if not st["stopped"]:
            print(f"ничего не стоит; вызовов Claude за час: {st['hits_hour']}")
        for x in st["stopped"]:
            print(f"стоит: {x['title']} — {x['why']} (с {x['at']})")
        for x in st["relax"]:
            print(f"пороги втрое до {x['until']}: {x['title']}")
        return 0
    out = guard.answer(cfg.db_url, args.task or guard.ALL, "resume" if args.action == "on" else "keep", "cli")
    print(("продолжаю — час пороги втрое: " if args.action == "on" else "оставлено остановленным: ") + (args.task or "всё")
          + (f"; стоит ещё: {', '.join(out['stopped'])}" if out["stopped"] else ""))
    return 0


def cmd_plan(args) -> int:
    """«Сейчас на завтра» (шаг 1 петли, plan.py): показать кандидатов, прогон руками (только явным run), снимок дня,
    состояние.

    Без действия — вхолостую: выкат велит звать plan на живой базе, и забытый --dry при действии run «с завода» положил
    бы Саше кандидатов не в свой час. Писать — только plan run (и он на день, где кандидаты уже были, ничего не пишет)."""
    import datetime as dt

    from . import plan

    cfg = config_mod.load(args.env)
    if args.action == "snap":
        day = dt.date.fromisoformat(args.day) if args.day else dt.datetime.now(plan.MSK).date()
        print(f"снимок «Сейчас» {day}: строк {plan.snap(cfg.db_url, day)}")
        return 0
    if args.action == "status":
        st = plan._state(cfg.db_url)
        snaps = ", ".join(d + " " + "/".join(v) for d, v in sorted((st.get("snaps") or {}).items()))
        print(f"последний прогон: {st.get('run_day') or '—'}; итог: {st.get('last') or '—'}; снимки: {snaps or '—'}")
        return 0
    dry = args.dry or args.action is None
    out = plan.run(cfg.db_url, dry=dry, for_date=dt.date.fromisoformat(args.for_date) if args.for_date else None)
    if out.get("skipped"):
        print(out["skipped"])
        return 0 if out.get("offered") else 1
    by: dict[str, int] = {}
    for x in out["items"]:
        by[x["basket"]] = by.get(x["basket"], 0) + 1
    # Первая строка — только числа: её можно отдать в чат с живой базы (названия дел — личное).
    print(("вхолостую (ничего не записано): " if out.get("dry") else "")
          + f"на {out['label']}: кандидатов {len(out['items'])} из {out['limit']} ("
          + ", ".join(f"{b} {by[b]}" for b in plan.SHOW if b in by) + f"); встреч ~{out['meetings']} — возьми "
          f"{out['take']}; третий день в «Сейчас»: {len(out['stuck'])}"
          + (f"; на этот день уже предложено {out['offered']} — run ничего не запишет" if out.get("dry") and out.get("offered")
             else "; записать — plan run" if out.get("dry") and args.action is None else "")
          + ("" if out.get("dry") else f"; записано {out.get('made', 0)}, ошибок {len(out.get('errors') or [])}"))
    if args.list:
        for x in out["items"]:
            print(f"  [{x['basket']}] {x['title']} — {x['why']} ({x['from']})")
        for x in out["stuck"]:
            print(f"  [stuck] #{x['num']} {x['title']} — {x['days']}-й день")
    return 0


def cmd_zachem(args) -> int:
    """«Зачем» по-человечески (13.10.2026, zachem.py): предложения Claude — в файл, применить файл, вернуть как было.
    Перед apply — своя копия базы (правило 9): pravka-before-zachem-rename.dump."""
    from . import zachem

    cfg = config_mod.load(args.env)
    if args.action == "propose":
        out = zachem.propose(cfg.db_url, args.user, cfg.anthropic_key, cfg.claude_proxy, limit=args.limit, batch=not args.now)
        Path(args.out).write_text(json.dumps(out, ensure_ascii=False, indent=1, default=str), encoding="utf-8")
        print(f"посмотрел {out.get('seen', 0)}, предлагаю переименовать {len(out['items'])}, ${out['usd']:.4f} → {args.out}")
        for x in out["items"]:
            print(f"  {x.get('client', 'цель') + ': ' if x['kind'] == 'deal' else 'цель: '}{x['was']} → {x['now']}")
        return 0
    if args.action == "apply":
        data = json.loads(Path(args.file).read_text(encoding="utf-8"))
        out = zachem.apply(cfg.db_url, args.user, data["items"])
    else:
        out = zachem.rollback(cfg.db_url, args.user)
    print(f"переименовано: {len(out['done'])}" + (f"; не вышло: {'; '.join(out['errors'])}" if out["errors"] else ""))
    return 0 if not out["errors"] else 1


def cmd_spend(args) -> int:
    """Книга трат Claude (14.10.2026, spend.py): status — неделя по службам и задачам; advise — советы прямо сейчас
    (проба советника на малом: --direct — без пачки, вдвое дороже, но сразу; --dry — только показать вход)."""
    import datetime as dt

    from . import spend, spend_advice, store

    cfg = config_mod.load(args.env)
    if args.action == "status":
        with db.session(cfg.db_url, "system", via="claude") as conn:
            owner = conn.execute("SELECT crm.owner_id() AS id").fetchone()["id"]
        v = store.view(cfg.db_url, owner, "spend")
        w = v["periods"]["week"]
        print(f"за 7 дней ${w['usd']:.2f} (прошлая неделя ${v['prev_week']:.2f}); кэш сэкономил ${w['cache_saved']:.2f}, "
              f"пачки ${w['batch_saved']:.2f}")
        for t in w["tasks"]:
            print(f"  ${t['usd']:8.3f}  {t['service_title']} · {t['title']}  ({t['calls']} выз., {', '.join(t['models'])})")
        for s_ in v["sources"]:
            print(f"  источник {s_['src']}: {', '.join(s_['titles'])} — по {s_['day']}, обновлён {s_['at']}")
        return 0
    last = dt.date.fromisoformat(args.day) if args.day else dt.datetime.now(spend_advice.MSK).date()
    if args.dry:
        with db.session(cfg.db_url, "system", via="claude") as conn:
            owner = conn.execute("SELECT crm.owner_id() AS id").fetchone()["id"]
        f = spend_advice.facts(cfg.db_url, owner, last)
        print(spend_advice.message(f) if f else "трат за неделю нет — советнику нечего смотреть")
        return 0
    if not cfg.anthropic_key:
        print("нет ключа Claude в dela.env")
        return 2
    out = spend_advice.send(cfg.db_url, cfg.anthropic_key, cfg.claude_proxy, last, batch=not args.direct)
    print(out or "трат за неделю нет — советнику нечего смотреть")
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
    u.add_argument("--clients", choices=["own", "team", "all"],
                   help="own — только своё; team — клиенты, где он на сделке; all — все клиенты фирмы")
    u.add_argument("--money", type=lambda v: v.lower() in ("1", "yes", "да", "true"), metavar="да|нет",
                   help="видит гонорары и оплаты")
    u.add_argument("--person", help="кто он в справочнике людей (имя или короткое имя)")
    u.set_defaults(fn=cmd_user)

    fm = sub.add_parser("family", help="кто в семье для «Где мы»")
    fm.add_argument("user", nargs="?", help="кого добавить (или убрать с --off); без него — список")
    fm.add_argument("--off", action="store_true", help="убрать из семьи: перестаёт видеть, его точка уходит")
    fm.set_defaults(fn=cmd_family)

    tg = sub.add_parser("telegram")
    tg.add_argument("user")
    tg.add_argument("telegram_id", type=int)
    tg.set_defaults(fn=cmd_telegram)

    rn = sub.add_parser("remind-notes")
    rn.add_argument("--apply", action="store_true", help="записать (без него — только список)")
    rn.set_defaults(fn=cmd_remind_notes)

    t = sub.add_parser("token")
    t.add_argument("--user", required=True)
    t.add_argument("--kind", default="service", choices=["service", "device"])
    t.add_argument("--name", required=True, help="mcp, meetings, userbot, «телефон Саши»…")
    t.add_argument("--out", help="записать в файл, а не на экран")
    t.set_defaults(fn=cmd_token)

    p = sub.add_parser("pair")
    p.add_argument("--user", default="sasha")
    p.add_argument("--name", default="телефон")
    p.add_argument("--wait", type=int, default=0, help="ждать подключения телефона N секунд вместо Enter")
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

    il = sub.add_parser("import-log", help="лог взаимодействий Notion в хронологию CRM")
    il.add_argument("--file", required=True, help="notion-log.json")
    il.add_argument("--apply", action="store_true")
    il.add_argument("--report")
    il.set_defaults(fn=cmd_import_log)

    e = sub.add_parser("export-todoist")
    e.add_argument("--out", required=True)
    e.set_defaults(fn=cmd_export_todoist)

    sv = sub.add_parser("svod", help="Свод: промпты, словарь, правила")
    sv.add_argument("action", choices=["list", "get", "set"])
    sv.add_argument("key", nargs="?")
    sv.add_argument("--file", help="текст записи (без него — со стандартного входа)")
    sv.add_argument("--json", action="store_true", help="запись — JSON, а не текст")
    sv.add_argument("--user", default="sasha")
    sv.add_argument("--author", default="claude")
    sv.add_argument("--reason")
    sv.set_defaults(fn=cmd_svod)

    mt = sub.add_parser("meetings", help="встречи: что с разбором; rename — разобрать заново; touch — записи в хронологии")
    mt.add_argument("action", nargs="?", choices=["status", "rename", "touch"], default="status")
    mt.add_argument("--since", default="2026-09-01", help="touch: встречи с этого дня (архив раньше 01.09 не трогается)")
    mt.add_argument("--dry", action="store_true", help="touch: только посчитать, ничего не записывать")
    mt.add_argument("--all", action="store_true", help="rename: все встречи (замки владельца остаются)")
    mt.add_argument("--ref", action="append", help="rename: номер встречи у службы (можно несколько)")
    mt.set_defaults(fn=cmd_meetings)
    dr = sub.add_parser("digest-relink", help="сводки дайджеста заново по карточкам CRM: упоминания и сделка записи")
    dr.add_argument("--dry", action="store_true", help="только посчитать, ничего не записывать")
    dr.set_defaults(fn=cmd_digest_relink)
    pf = sub.add_parser("privacy-fix", help="пакет «Приватность» по прежним данным: основания и заметки машины — "
                                            "приватными, записи встреч — по переключателю")
    pf.add_argument("--dry", action="store_true", help="только посчитать, ничего не записывать")
    pf.add_argument("--locked-private", action="store_true",
                    help="записи встреч под замком владельца — скрыть от команды (только по «да» владельца)")
    pf.set_defaults(fn=cmd_privacy_fix)
    pl = sub.add_parser("plan", help="«Сейчас на завтра»: показать (без действия), прогон (run), снимок дня, состояние")
    # Без действия — только показать: писать в базу — явным run (забытый --dry не кладёт Саше кандидатов).
    pl.add_argument("action", nargs="?", choices=["run", "snap", "status"], default=None)
    pl.add_argument("--dry", action="store_true", help="run: только посчитать кандидатов, ничего не записывать")
    pl.add_argument("--for", dest="for_date", help="на какой день (с завода — следующий будний)")
    pl.add_argument("--list", action="store_true", help="и сами кандидаты строками (названия дел — личное)")
    pl.add_argument("--day", help="snap: какой день (с завода — сегодня по Москве)")
    pl.set_defaults(fn=cmd_plan)
    zc = sub.add_parser("zachem", help="«Зачем» по-человечески: предложить, применить, вернуть")
    zc.add_argument("action", choices=["propose", "apply", "rollback"])
    zc.add_argument("--user", default="sasha")
    zc.add_argument("--limit", type=int, help="propose: только первые N (проба на малом)")
    zc.add_argument("--now", action="store_true", help="propose: сразу, а не пачкой (дороже вдвое)")
    zc.add_argument("--out", default="zachem-propose.json", help="propose: куда положить предложения")
    zc.add_argument("--file", help="apply: файл предложений (можно поправить руками)")
    zc.set_defaults(fn=cmd_zachem)
    gd = sub.add_parser("guard", help="защита от петли Claude: что стоит, «Продолжать», «Оставить»")
    gd.add_argument("action", nargs="?", choices=["status", "on", "keep"], default="status")
    gd.add_argument("task", nargs="?", help="задача (ask, parse, when, krug, meetings…) или * — всё")
    gd.set_defaults(fn=cmd_guard)
    sp = sub.add_parser("spend", help="траты Claude: сводка недели; советы экономии сейчас")
    sp.add_argument("action", nargs="?", choices=["status", "advise"], default="status")
    sp.add_argument("--day", help="advise: последний день недели (с завода сегодня)")
    sp.add_argument("--direct", action="store_true", help="advise: сразу, без пачки (дороже вдвое)")
    sp.add_argument("--dry", action="store_true", help="advise: только показать сводку, Claude не звать")
    sp.set_defaults(fn=cmd_spend)
    ks = sub.add_parser("krug-seed", help="Круг жизни: направления и принципы из файла владельца")
    ks.add_argument("--file", required=True, help="seed.json владельца (на компе, не в репозитории)")
    ks.add_argument("--user", default="sasha")
    ks.add_argument("--force", action="store_true", help="переписать записи Свода файлом")
    ks.set_defaults(fn=cmd_krug_seed)
    from . import debug as debug_mod  # режим отладки: debug list | show N | set N статус «ответ» | on | off

    debug_mod.add_cli(sub)

    args = ap.parse_args(argv)
    try:
        return args.fn(args)
    except guard.Stopped as e:
        # Защита от петли остановила Claude (zachem propose, spend advise…) — словами, а не трейсбеком (контракт §4.3).
        print(str(e))
        return 1


if __name__ == "__main__":
    sys.exit(main())
