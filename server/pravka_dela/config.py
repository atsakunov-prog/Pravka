"""Настройки службы Дел: свой файл секретов плюс переменные окружения.

Файл — `C:\\ProgramData\\ZF-Dela\\secrets\\dela.env`, отдельный от server.env
архива: служба смотрит в интернет (веб для Наташи и Марианны) и не должна
видеть ни пароль владельца базы, ни токен телефона архива. Переменная
окружения сильнее файла.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

from pravka_archive.config import read_env_file

DEFAULT_ENV_FILE = r"C:\ProgramData\ZF-Dela\secrets\dela.env"


@dataclass(frozen=True)
class Config:
    db_url: str
    listen_host: str = "0.0.0.0"
    listen_port: int = 8102
    public_url: str = ""
    phone_url: str = ""
    logs: Path = Path(r"C:\Bot\ZFbot\logs\dela")
    data: Path = Path(r"C:\ProgramData\ZF-Dela")
    # Мост из Todoist до переезда телефона: токен — забирать новые задачи; пусто — моста нет.
    todoist_token: str = ""

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
        todoist_token=get("DELA_TODOIST_TOKEN"),
    )
