"""Настройки службы Дел: свой файл секретов плюс переменные окружения.

Файл — `C:\\ProgramData\\ZF-Dela\\secrets\\dela.env`, отдельный от server.env
архива: служба смотрит в интернет (веб для Наташи и Марианны) и не должна
видеть ни пароль владельца базы, ни токен телефона архива. Переменная
окружения сильнее файла.
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

from pravka_archive.config import read_env_file

DEFAULT_ENV_FILE = r"C:\ProgramData\ZF-Dela\secrets\dela.env"

_NAV_ICON = re.compile(r"^[a-z][a-zA-Z0-9]{0,23}$")
_NAV_URL = re.compile(r"^(/|https?://[^\s\"'<>]+)$")
_NAV_KEY = re.compile(r"^[a-z][a-z0-9_-]{0,23}$")
_FROM_LABEL = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
_FROM_ORIGIN = re.compile(r"^https?://[a-z0-9.-]+(?::\d{1,5})?$")


def parse_nav(text: str) -> tuple[tuple[str, str, str, str], ...]:
    """Сервисы в шапке веба (13.10.2026, общий вид Ковчега): «Название|значок|адрес» через «;». Адреса — только в
    файле настроек на компе: репозиторий публичный. Адрес «/» — сами Дела (так задаётся их место в ряду).
    Четвёртое поле (с 14.10.2026, этап 7) — ключ сервиса в меню Свода (ui.menu): без него ключ узнаётся по значку
    (kovcheg_ui.KEY_BY_ICON — home → dom, film → kino…), незнакомый значок — без ключа, и сервис встаёт в «Ещё».
    Кривая запись молча пропускается: шапка — не повод не подняться службе."""
    from .kovcheg_ui import KEY_BY_ICON

    out = []
    for part in (text or "").split(";"):
        bits = [b.strip() for b in part.split("|")]
        if len(bits) not in (3, 4) or not bits[0] or len(bits[0]) > 40:
            continue
        name, icon, url = bits[:3]
        key = bits[3] if len(bits) == 4 else KEY_BY_ICON.get(icon, "")
        if _NAV_ICON.match(icon) and _NAV_URL.match(url) and (not key or _NAV_KEY.match(key)):
            out.append((name, icon, url, key))
    return tuple(out)


def parse_debug_from(text: str) -> tuple[str, ...]:
    """С каких страниц окно-приёмник Дел (#/debug-in, static/debugin.js) берёт записки отладки (16.10.2026, правило 28):
    имена служб (`kino` — тогда `kino.<родитель Дел>`, ровно одним уровнем) и точные адреса
    (`https://kino.example.org:8443`) через «;», «,» или пробел; список заменяет заводской целиком. Пусто — заводские
    имена служб (debug.KOVCHEG_FROM). Адреса — только здесь, в настройке на компе: репозиторий публичный. Кривое молча
    пропускается."""
    out = []
    for part in re.split(r"[;,\s]+", (text or "").strip().lower()):
        part = part.rstrip("/")
        if (_FROM_LABEL.match(part) or _FROM_ORIGIN.match(part)) and part not in out:
            out.append(part)
    return tuple(out)


@dataclass(frozen=True)
class Config:
    db_url: str
    listen_host: str = "0.0.0.0"
    listen_port: int = 8102
    public_url: str = ""
    phone_url: str = ""
    logs: Path = Path(r"C:\Bot\ZFbot\logs\dela")
    data: Path = Path(r"C:\ProgramData\ZF-Dela")
    # Разбор текста Claude (кнопка рядом с «+»): ключ и xray бота — из России API закрыт.
    anthropic_key: str = ""
    claude_proxy: str = "http://127.0.0.1:11809"
    # Шапка веба в общем виде Ковчега: соседние сервисы (видит только владелец) и значок «Ковчега» — файл на
    # компе (картинка семейная, в публичный репозиторий её не кладём). Пусто — в шапке только «Дела».
    nav: tuple[tuple[str, str, str, str], ...] = ()
    brand_icon: str = ""
    # Режим отладки со страниц служб Ковчега: откуда окно-приёмник берёт записки (parse_debug_from). Пусто — заводское.
    debug_from: tuple[str, ...] = ()

    @property
    def app_role(self) -> str:
        return urlparse(self.db_url).username or "dela_app"

    def problems(self) -> list[str]:
        out = []
        if not self.db_url:
            out.append("DELA_DB_URL пуст: не к чему подключаться")
        elif self.app_role in ("pravka", "postgres"):
            out.append("DELA_DB_URL ходит владельцем базы: права в базе (RLS) на него не действуют — нужна роль dela_app")
        return out


def load(env_file: str | None = None) -> Config:
    path = env_file or os.environ.get("DELA_ENV_FILE") or DEFAULT_ENV_FILE
    values = read_env_file(path)
    values.update({k: v for k, v in os.environ.items() if k.startswith("DELA_")})

    def get(name: str, default: str = "") -> str:
        return values.get(name, default).strip()

    host, _, port = get("DELA_LISTEN", "0.0.0.0:8102").rpartition(":")
    return Config(
        db_url=get("DELA_DB_URL"),
        listen_host=host or "0.0.0.0",
        listen_port=int(port or 8102),
        # Адрес веба (через Aeza, 443) — из него строятся ссылки бота и входа.
        public_url=get("DELA_PUBLIC_URL").rstrip("/"),
        # Куда ходит телефон: прямой адрес роутера, крюк через Нидерланды ему ни к чему.
        phone_url=get("DELA_PHONE_URL").rstrip("/"),
        logs=Path(get("DELA_LOGS", r"C:\Bot\ZFbot\logs\dela")),
        data=Path(get("DELA_DATA", r"C:\ProgramData\ZF-Dela")),
        anthropic_key=get("ANTHROPIC_API_KEY"),
        claude_proxy=get("DELA_CLAUDE_PROXY", "http://127.0.0.1:11809"),
        nav=parse_nav(get("DELA_NAV")),
        brand_icon=get("DELA_BRAND_ICON"),
        debug_from=parse_debug_from(get("DELA_DEBUG_FROM")),
    )
