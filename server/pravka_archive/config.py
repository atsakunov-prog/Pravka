"""Настройки сервиса: файл секретов на компе плюс переменные окружения.

Файл — `D:\\PravkaArchive\\secrets\\server.env` (строки `ИМЯ=значение`), путь
можно сменить переменной `PRAVKA_ENV_FILE`. Переменная окружения сильнее
файла: так тесты и разовые запуски не трогают настоящие секреты.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

DEFAULT_ENV_FILE = r"D:\PravkaArchive\secrets\server.env"


def read_env_file(path: str | os.PathLike[str]) -> dict[str, str]:
    """`ИМЯ=значение` построчно; пустые строки и `#` — мимо, кавычки не нужны."""
    out: dict[str, str] = {}
    p = Path(path)
    if not p.exists():
        return out
    for line in p.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        out[name.strip()] = value.strip()
    return out


@dataclass(frozen=True)
class Config:
    db_url: str
    reader_url: str
    listen_host: str
    listen_port: int
    public_url: str
    ingest_token: str
    owner_password: str
    blobs: Path
    logs: Path
    icu_athlete: str
    icu_key: str
    profile: str = "sasha"
    proxies: str = "127.0.0.1"
    phone_url: str = ""

    @property
    def mcp_url(self) -> str:
        return self.public_url + "/mcp"

    @property
    def ingest_base(self) -> str:
        return self.phone_url or self.public_url

    @property
    def reader_role(self) -> str:
        return urlparse(self.reader_url).username or "pravka_reader"

    def problems(self) -> list[str]:
        """Что не так в настройках — словами, для `check` и для старта."""
        out = []
        if not self.db_url:
            out.append("PRAVKA_DB_URL пуст: не к чему подключаться")
        if not self.reader_url:
            out.append("PRAVKA_DB_READER_URL пуст: инструменту sql нечем читать")
        if not self.public_url.startswith("https://"):
            out.append("PRAVKA_PUBLIC_URL должен начинаться с https:// (адрес, по которому роутер публикует сервис)")
        if len(self.ingest_token) < 32:
            out.append("PRAVKA_INGEST_TOKEN короче 32 знаков: телефону нужен длинный случайный токен")
        if len(self.owner_password) < 12:
            out.append("PRAVKA_OWNER_PASSWORD короче 12 знаков: им закрыт вход Claude ко всей жизни")
        return out


def load(env_file: str | None = None) -> Config:
    path = env_file or os.environ.get("PRAVKA_ENV_FILE") or DEFAULT_ENV_FILE
    values = read_env_file(path)
    values.update({k: v for k, v in os.environ.items() if k.startswith(("PRAVKA_", "ICU_"))})

    def get(name: str, default: str = "") -> str:
        return values.get(name, default).strip()

    listen = get("PRAVKA_LISTEN", "0.0.0.0:8090")
    host, _, port = listen.rpartition(":")
    return Config(
        db_url=get("PRAVKA_DB_URL"),
        reader_url=get("PRAVKA_DB_READER_URL"),
        listen_host=host or "0.0.0.0",
        listen_port=int(port or 8090),
        public_url=get("PRAVKA_PUBLIC_URL").rstrip("/"),
        ingest_token=get("PRAVKA_INGEST_TOKEN"),
        owner_password=get("PRAVKA_OWNER_PASSWORD"),
        blobs=Path(get("PRAVKA_BLOBS", r"D:\PravkaArchive\blobs")),
        logs=Path(get("PRAVKA_LOGS", r"D:\PravkaArchive\logs")),
        icu_athlete=get("ICU_ATHLETE_ID"),
        icu_key=get("ICU_API_KEY"),
        # Архив одного человека: телефон другого профиля перетёр бы его сутки.
        profile=get("PRAVKA_PROFILE", "sasha"),
        # Кому верить X-Forwarded-For: роутер, который публикует сервис.
        proxies=get("PRAVKA_PROXIES", "127.0.0.1"),
        # Куда ходит телефон. claude.ai ходит только на 443, поэтому
        # PRAVKA_PUBLIC_URL смотрит на вход через VPS; телефону этот крюк через
        # Нидерланды ни к чему — ему прямой адрес роутера (CrazeDNS, 8443).
        phone_url=get("PRAVKA_PHONE_URL").rstrip("/"),
    )
