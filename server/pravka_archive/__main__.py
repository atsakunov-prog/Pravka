"""python -m pravka_archive migrate | check | serve | pull [recent|deep|full] | pair"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import logging.handlers
import sys

import psycopg

from . import config as config_mod
from . import db


def _logging(cfg: config_mod.Config, console: bool = True) -> None:
    handlers: list[logging.Handler] = []
    if console:
        handlers.append(logging.StreamHandler(sys.stdout))
    try:
        cfg.logs.mkdir(parents=True, exist_ok=True)
        handlers.append(logging.handlers.RotatingFileHandler(
            cfg.logs / "archive.log", maxBytes=5_000_000, backupCount=5, encoding="utf-8"))
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
    if cfg.icu_athlete and cfg.icu_key:
        import httpx

        try:
            r = httpx.get(f"https://intervals.icu/api/v1/athlete/{cfg.icu_athlete}", auth=("API_KEY", cfg.icu_key), timeout=30)
            say(r.status_code == 200, f"intervals: {r.status_code}" + ("" if r.status_code == 200 else f" {r.text[:200]}"))
        except Exception as e:
            say(False, f"intervals: {e}")
    else:
        print("—    intervals: ключа нет, сборщик спит")
    if cfg.public_url.startswith("https://"):
        import httpx

        try:
            r = httpx.get(cfg.public_url + "/health", timeout=20)
            good = r.status_code == 200 and "pravka-archive" in r.text
            say(good, f"снаружи {cfg.public_url}/health: {r.status_code}" + ("" if good else " — отвечает не сервис (страница роутера?)"))
        except Exception as e:
            say(False, f"снаружи {cfg.public_url}/health: {e} (сервис запущен? приложение в роутере заведено?)")
    print("Всё в порядке." if not bad else f"Проблем: {bad}.")
    return 1 if bad else 0


def cmd_pull(cfg: config_mod.Config, mode: str) -> int:
    from .icu import Puller

    out = Puller(cfg).run(mode)
    print(json.dumps(out, ensure_ascii=False, indent=1))
    return 0


def cmd_pair(cfg: config_mod.Config) -> int:
    """QR для телефона: адрес и токен. Только в консоли компа — в сеть не отдаётся."""
    import segno

    payload = "pravka-archive:" + json.dumps({"url": cfg.public_url, "token": cfg.ingest_token}, separators=(",", ":"))
    segno.make(payload, error="m").terminal(compact=True)
    print("Наведи на это сканер в Правке: Настройки → Подключения → Архив → «Сканировать».")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="pravka_archive")
    ap.add_argument("command", choices=["migrate", "check", "serve", "pull", "pair"])
    ap.add_argument("mode", nargs="?", default="recent", choices=["recent", "deep", "full"])
    ap.add_argument("--env", help="файл секретов (по умолчанию D:\\PravkaArchive\\secrets\\server.env)")
    args = ap.parse_args(argv)
    cfg = config_mod.load(args.env)

    if args.command == "migrate":
        return cmd_migrate(cfg)
    if args.command == "check":
        return cmd_check(cfg)
    if args.command == "pull":
        _logging(cfg)
        return cmd_pull(cfg, args.mode)
    if args.command == "pair":
        return cmd_pair(cfg)

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
