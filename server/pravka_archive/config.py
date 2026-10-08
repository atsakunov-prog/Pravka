"""Настройки сервиса: файл секретов на компе плюс переменные окружения.

Файл — `D:\\PravkaArchive\\secrets\\server.env` (строки `ИМЯ=значение`), путь
можно сменить переменной `PRAVKA_ENV_FILE`. Переменная окружения сильнее
файла: так тесты и разовые запуски не трогают настоящие секреты.
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

DEFAULT_ENV_FILE = r"D:\PravkaArchive\secrets\server.env"

# Ключ профиля телефона (data/Profile.kt: латиница, цифры, дефис). Он же —
# person в core и, с дефисом через подчёркивание, имя схемы видов.
PERSON_RE = re.compile(r"^[a-z][a-z0-9-]{0,30}$")
# Схемы, которые уже заняты: архив, Дела, системные.
RESERVED_SCHEMAS = {"life", "core", "public", "crm", "tasks", "information_schema"}


@dataclass(frozen=True)
class Person:
    """Человек архива (08.10.2026): чей телефон шлёт пачки и где его виды.

    Хозяин архива — PRAVKA_PROFILE: его записи лежат с person = '' и видны в
    схеме life, как было до второго человека. Остальные — PRAVKA_PEOPLE: у
    каждого свой person (ключ профиля) и своя схема с теми же видами.
    """

    profile: str
    key: str
    schema: str
    icu_athlete: str = ""
    icu_key: str = ""

    @property
    def owner(self) -> bool:
        return self.key == ""

    @property
    def icu_source(self) -> str:
        """Устройство и строка свежести его сборщика intervals."""
        return "intervals" if self.owner else f"intervals-{self.key}"

    @property
    def has_icu(self) -> bool:
        return bool(self.icu_athlete and self.icu_key)


def schema_of(profile: str) -> str:
    return profile.replace("-", "_")


def valid_person(profile: str, owner: str) -> bool:
    """Годится ли ключ из PRAVKA_PEOPLE: он идёт в SQL именем схемы и строкой."""
    return bool(PERSON_RE.match(profile)) and schema_of(profile) not in RESERVED_SCHEMAS \
        and not profile.startswith("pg") and profile != owner


def env_suffix(profile: str) -> str:
    """marianna → MARIANNA: хвост переменных ICU_ATHLETE_ID_<…>, ICU_API_KEY_<…>."""
    return profile.upper().replace("-", "_")


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
    # Дела (pravka_dela): адрес базы ролью службы Дел. Пусто — инструментов дел у Claude нет.
    dela_db_url: str = ""
    # Остальные люди архива (PRAVKA_PEOPLE) и их intervals: (профиль, athlete id, ключ).
    people: tuple[str, ...] = ()
    icu_people: tuple[tuple[str, str, str], ...] = ()

    def persons(self) -> list[Person]:
        """Хозяин первым, за ним остальные — в порядке PRAVKA_PEOPLE."""
        out = [Person(self.profile, "", "life", self.icu_athlete, self.icu_key)]
        icu = {p: (a, k) for p, a, k in self.icu_people}
        for p in self.people:
            if not valid_person(p, self.profile):
                continue  # problems() скажет словами; в SQL такое имя не пойдёт
            a, k = icu.get(p, ("", ""))
            out.append(Person(p, p, schema_of(p), a, k))
        return out

    def person_of(self, profile: object) -> Person | None:
        """Чей телефон: хозяин, один из PRAVKA_PEOPLE или чужой (None)."""
        return next((p for p in self.persons() if p.profile == profile), None)

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
        for p in self.people:
            if not valid_person(p, self.profile):
                out.append(f"PRAVKA_PEOPLE: «{p}» не годится — ключ профиля латиницей (marianna), не {self.profile} и не имя служебной схемы")
        return out


def load(env_file: str | None = None) -> Config:
    path = env_file or os.environ.get("PRAVKA_ENV_FILE") or DEFAULT_ENV_FILE
    values = read_env_file(path)
    values.update({k: v for k, v in os.environ.items() if k.startswith(("PRAVKA_", "ICU_", "DELA_DB_URL"))})

    def get(name: str, default: str = "") -> str:
        return values.get(name, default).strip()

    people = tuple(dict.fromkeys(p for p in re.split(r"[\s,;]+", get("PRAVKA_PEOPLE").lower()) if p))
    icu_people = tuple((p, get(f"ICU_ATHLETE_ID_{env_suffix(p)}"), get(f"ICU_API_KEY_{env_suffix(p)}")) for p in people)

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
        # Хозяин архива: его записи — схема life. Чужой профиль, которого нет в
        # PRAVKA_PEOPLE, сервер не пускает.
        profile=get("PRAVKA_PROFILE", "sasha"),
        # Кому верить X-Forwarded-For: роутер, который публикует сервис.
        proxies=get("PRAVKA_PROXIES", "127.0.0.1"),
        # Куда ходит телефон. claude.ai ходит только на 443, поэтому
        # PRAVKA_PUBLIC_URL смотрит на вход через VPS; телефону этот крюк через
        # Нидерланды ни к чему — ему прямой адрес роутера (CrazeDNS, 8443).
        phone_url=get("PRAVKA_PHONE_URL").rstrip("/"),
        dela_db_url=get("DELA_DB_URL"),
        # Второй человек (08.10.2026): Марианна шлёт со своего телефона, её
        # intervals сервер забирает её ключом. PRAVKA_PEOPLE=marianna,
        # ICU_ATHLETE_ID_MARIANNA, ICU_API_KEY_MARIANNA.
        people=people,
        icu_people=icu_people,
    )
