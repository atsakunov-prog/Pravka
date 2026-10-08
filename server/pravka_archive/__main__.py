"""python -m pravka_archive migrate | check | serve | supervise | pull [recent|deep|full] [--who marianna] | pair"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import logging.handlers
import os
import subprocess
import sys
import time
from pathlib import Path

import psycopg

from . import config as config_mod
from . import db


def _logging(cfg: config_mod.Config, console: bool = True, name: str = "archive.log") -> None:
    handlers: list[logging.Handler] = []
    if console:
        handlers.append(logging.StreamHandler(sys.stdout))
    try:
        cfg.logs.mkdir(parents=True, exist_ok=True)
        handlers.append(logging.handlers.RotatingFileHandler(
            cfg.logs / name, maxBytes=5_000_000, backupCount=5, encoding="utf-8"))
    except OSError:
        pass
    logging.basicConfig(level=logging.INFO, handlers=handlers, format="%(asctime)s %(levelname)s %(name)s: %(message)s")


def cmd_migrate(cfg: config_mod.Config) -> int:
    done = db.migrate(cfg)
    print("Применено:", ", ".join(done) if done else "ничего нового")
    return 0


def cmd_check(cfg: config_mod.Config) -> int:
    bad = 0

    def say(ok: bool, text: str) -> None:
        nonlocal bad
        bad += 0 if ok else 1
        print(("ОК   " if ok else "НЕТ  ") + text)

    for p in cfg.problems():
        say(False, p)
    try:
        with psycopg.connect(cfg.db_url, autocommit=True) as conn:
            n = conn.execute("SELECT count(*) FROM core.migrations").fetchone()[0]
            ev = conn.execute("SELECT count(*) FROM core.events").fetchone()[0]
            say(True, f"база: вход владельцем, применено файлов схемы {n}, событий в журнале {ev}")
    except psycopg.Error as e:
        say(False, f"база владельцем: {e}")
    try:
        with psycopg.connect(cfg.reader_url, autocommit=True) as conn:
            conn.execute("SELECT count(*) FROM life.days").fetchone()
            try:
                conn.execute("INSERT INTO core.events (eid) VALUES ('x')")
                say(False, "читатель смог писать в журнал — права настроены неверно")
            except psycopg.Error:
                say(True, "читатель видит life и не может писать")
    except psycopg.Error as e:
        say(False, f"база читателем: {e}")
    for folder in (cfg.blobs, cfg.logs):
        try:
            folder.mkdir(parents=True, exist_ok=True)
            probe = folder / ".probe"
            probe.write_text("ok", encoding="utf-8")
            probe.unlink()
            say(True, f"папка {folder} пишется")
        except OSError as e:
            say(False, f"папка {folder}: {e}")
    for person in cfg.persons():
        name = person.icu_source
        if person.has_icu:
            import httpx

            try:
                r = httpx.get(f"https://intervals.icu/api/v1/athlete/{person.icu_athlete}", auth=("API_KEY", person.icu_key), timeout=30)
                say(r.status_code == 200, f"{name}: {r.status_code}" + ("" if r.status_code == 200 else f" {r.text[:200]}"))
            except Exception as e:
                say(False, f"{name}: {e}")
        else:
            suffix = "" if person.owner else f"_{config_mod.env_suffix(person.profile)}"
            print(f"—    {name}: ключа нет, сборщик спит (ICU_ATHLETE_ID{suffix}, ICU_API_KEY{suffix})")
        if not person.owner:
            try:
                with psycopg.connect(cfg.reader_url, autocommit=True) as conn:
                    conn.execute(f"SELECT count(*) FROM {person.schema}.days").fetchone()
                say(True, f"архив профиля «{person.profile}»: схема {person.schema} читается")
            except psycopg.Error as e:
                say(False, f"архив профиля «{person.profile}»: {e} — выполни migrate")
    if cfg.public_url.startswith("https://"):
        import httpx

        # Изнутри домашней сети внешний адрес часто не открывается: Netcraze
        # отдаёт своим устройствам служебный адрес, а не путь через интернет.
        # Поэтому неудача отсюда — не «НЕТ», а «проверь с телефона».
        try:
            r = httpx.get(cfg.public_url + "/health", timeout=20)
            good = r.status_code == 200 and "pravka-archive" in r.text
            if good:
                say(True, f"снаружи {cfg.public_url}/health отвечает сервис")
            else:
                print(f"—    снаружи {cfg.public_url}/health: {r.status_code}, отвечает не сервис. Изнутри сети это бывает; "
                      "проверь с телефона по мобильной сети")
        except Exception as e:
            print(f"—    снаружи {cfg.public_url}/health изнутри сети не открылся ({e.__class__.__name__}). "
                  "Проверь с телефона по мобильной сети: должен ответить pravka-archive")
    print("Всё в порядке." if not bad else f"Проблем: {bad}.")
    return 1 if bad else 0


def cmd_pull(cfg: config_mod.Config, mode: str, who: str | None = None) -> int:
    from .icu import Puller

    persons = [p for p in cfg.persons() if p.has_icu and (not who or who in (p.profile, p.schema))]
    if not persons:
        print(f"intervals: ключа нет{' у ' + who if who else ''} — нечего забирать")
        return 1
    for person in persons:
        out = Puller(cfg, person=person).run(mode)
        print(person.icu_source, json.dumps(out, ensure_ascii=False, indent=1))
    return 0


PAIR_PREFIX = "pravka-archive:"


def pairing_payload(cfg: config_mod.Config) -> str:
    """Что лежит в QR: префикс и JSON с адресом и токеном (`ArchiveSync.parsePairing` на телефоне)."""
    return PAIR_PREFIX + json.dumps({"url": cfg.ingest_base, "token": cfg.ingest_token}, separators=(",", ":"))


def cmd_pair(cfg: config_mod.Config, env_file: str | None, ask=input) -> int:
    """QR для телефона: адрес и токен. Только на экране компа — в сеть не отдаётся.

    Картинкой, а не в консоли: терминальный QR рисует тёмные модули пробелами,
    и на светлой консоли владелец увидел пустоту (01.10.2026). PNG — чёрное
    на белом, лежит рядом с server.env (там же, где токен) и удаляется, как
    только владелец отсканировал.
    """
    import segno

    print(f"Адрес для телефона: {cfg.ingest_base}/ingest  (в браузере не открывать: туда только POST с телефона)")
    folder = Path(env_file or os.environ.get("PRAVKA_ENV_FILE") or config_mod.DEFAULT_ENV_FILE).parent
    png = folder / "pair-qr.png"
    segno.make(pairing_payload(cfg), error="m").save(str(png), kind="png", scale=10, border=4, dark="black", light="white")
    try:
        print(f"QR открыт картинкой: {png}")
        if hasattr(os, "startfile"):
            os.startfile(str(png))  # type: ignore[attr-defined]  # только Windows
        print("В Правке: Настройки → Подключения → Архив → «Сканировать QR с компа».")
        ask("Отсканировал? Нажми Enter — картинка удалится (в ней токен телефона). ")
    finally:
        try:
            png.unlink()
        except FileNotFoundError:
            pass
        except OSError as e:  # открыта просмотрщиком — скажем, а не промолчим
            print(f"Картинку удалить не вышло ({e}) — удали руками: {png}")
    return 0


def next_delay(delay: float, ran: float) -> float:
    """Пауза перед перезапуском: проработала служба дольше десяти минут —
    снова 5 секунд; падает сразу — удваиваем до пяти минут, чтобы сломанный
    server.env не крутил процесс по кругу, но и не ждал часами."""
    if ran >= 600:
        return 5.0
    return min(max(delay, 5.0) * 2, 300.0)


def cmd_supervise(cfg: config_mod.Config, env_file: str | None) -> int:
    """Сторож: держит serve живым.

    Планировщик Windows перезапускает задачу, только если она не смогла
    стартовать; упавший посреди работы процесс он не поднимает (замечено на
    компе 01.10). Поэтому задача запускает сторожа, а сторож — службу, и
    поднимает её заново после любого выхода. Служба следит за сторожем
    (PRAVKA_SUPERVISOR_PID): остановили задачу — уходит и она, порт свободен.
    """
    # Свой файл: два процесса, вращающие один журнал, на Windows мешают друг
    # другу (файл занят).
    _logging(cfg, console=False, name="supervise.log")
    log = logging.getLogger("pravka.supervise")
    args = [sys.executable, "-m", "pravka_archive", "serve"] + (["--env", env_file] if env_file else [])
    env = dict(os.environ, PRAVKA_SUPERVISOR_PID=str(os.getpid()))
    cwd = str(Path(__file__).resolve().parents[1])
    delay = 5.0
    while True:
        started = time.monotonic()
        log.info("сторож: запускаю службу")
        proc = subprocess.Popen(args, env=env, cwd=cwd)
        try:
            code = proc.wait()
        except KeyboardInterrupt:
            proc.terminate()
            return 0
        ran = time.monotonic() - started
        delay = next_delay(delay, ran)
        log.warning("сторож: служба вышла с кодом %s через %d с — перезапуск через %d с", code, ran, delay)
        time.sleep(delay)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="pravka_archive")
    ap.add_argument("command", choices=["migrate", "check", "serve", "supervise", "pull", "pair"])
    ap.add_argument("mode", nargs="?", default="recent", choices=["recent", "deep", "full"])
    ap.add_argument("--env", help="файл секретов (по умолчанию D:\\PravkaArchive\\secrets\\server.env)")
    ap.add_argument("--who", help="pull: только этот человек архива (marianna); без него — все, у кого есть ключ intervals")
    args = ap.parse_args(argv)
    cfg = config_mod.load(args.env)

    if args.command == "migrate":
        return cmd_migrate(cfg)
    if args.command == "check":
        return cmd_check(cfg)
    if args.command == "pull":
        _logging(cfg)
        return cmd_pull(cfg, args.mode, args.who)
    if args.command == "pair":
        return cmd_pair(cfg, args.env)
    if args.command == "supervise":
        return cmd_supervise(cfg, args.env)

    problems = cfg.problems()
    if problems:
        print("Сервис не стартует:\n  " + "\n  ".join(problems))
        return 2
    _logging(cfg)
    from .app import serve

    asyncio.run(serve(cfg))
    return 0


if __name__ == "__main__":
    sys.exit(main())
