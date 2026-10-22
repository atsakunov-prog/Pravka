"""Один вход на все сервисы Ковчега (22.10.2026): пароль владельца и общая кука на весь домен веба.

Владелец: «давай уберём эти пароли везде… И эти Дела регулярно открываются через „обратись за ссылкой к Саше“.
Бесит дико». Остальные сервисы Ковчега пускают паролем и ставят браузеру куку на весь домен (kv_owner — владелец,
kv_family — семья); Дела принимают куку владельца и сами пускают паролем владельца — вход ссылкой-приглашением
остаётся команде. Семейная кука Дела не открывает: в них CRM, деньги ЗФ и дела владельца.

Ключ куки — PBKDF2 пароля на общей соли из family.json (кладёт скрипт паролей на компе, путь — DELA_FAMILY_FILE);
в файле только sha256 ключей. Домен куки — родитель адреса веба (dela.<домен> → <домен>), в код он не вписан:
репозиторий публичный. Перебор: 5 неверных за 15 минут — вход паролем закрыт на 15 минут для всех (снаружи все
запросы приходят от роутера, по адресу их не различить); кука и ссылки работают и во время блокировки.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import threading
import time
from pathlib import Path

OWNER_COOKIE = "kv_owner"
COOKIES = (OWNER_COOKIE, "kv_family")
DAYS = 3650
ITERATIONS = 600_000
MAX_FAILS = 5
WINDOW_SEC = 15 * 60

_lock = threading.Lock()
_fails: list[float] = []
_blocked_until = 0.0


def _conf(path: Path) -> dict:
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return data if isinstance(data, dict) else {}


def _seen(key: str) -> str:
    return hashlib.sha256(key.encode()).hexdigest()


def configured(path: Path) -> bool:
    c = _conf(path)
    return bool(c.get("salt") and c.get("owner"))


def owner_key(path: Path, password: str) -> str | None:
    """Ключ куки владельца, если это его пароль; иначе None (и неверная попытка в счёт)."""
    global _blocked_until
    c = _conf(path)
    if not c.get("salt") or not c.get("owner") or not password:
        return None
    now = time.time()
    with _lock:
        if now < _blocked_until:
            return None
    key = hashlib.pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(c["salt"]), ITERATIONS).hex()
    if hmac.compare_digest(_seen(key), c["owner"]):
        with _lock:
            _fails.clear()
        return key
    with _lock:
        _fails[:] = [t for t in _fails if now - t < WINDOW_SEC] + [now]
        if len(_fails) >= MAX_FAILS:
            _blocked_until = now + WINDOW_SEC
            _fails.clear()
    return None


def note_login(journal: Path | None, user_agent: str) -> None:
    """Вход паролем — строка в журнал входов: сборщик «Дома» шлёт её владельцу в Telegram («🔐 Вход», чужой вход
    видно сразу). Адреса нет: снаружи все приходят от роутера. Не записалось — вход всё равно состоялся."""
    if not journal:
        return
    line = json.dumps({"at": time.time(), "scope": OWNER_COOKIE, "ua": (user_agent or "")[:300]}, ensure_ascii=False)
    try:
        with open(journal, "a", encoding="utf-8") as fh:
            fh.write(line + "\n")
    except OSError:
        pass


def blocked_min() -> int:
    """Сколько ещё минут вход паролем закрыт (0 — открыт)."""
    with _lock:
        left = _blocked_until - time.time()
    return int(left // 60) + 1 if left > 0 else 0


def owner_valid(path: Path, cookies: dict) -> bool:
    """Пускает ли кука владельца с общего домена."""
    want = _conf(path).get("owner")
    key = cookies.get(OWNER_COOKIE)
    return bool(want and key and hmac.compare_digest(_seen(key), want))


def domain_of(public_url: str) -> str:
    """Домен общей куки — родитель адреса веба (dela.<домен> → <домен>); у адреса без родителя — пусто."""
    from urllib.parse import urlparse

    host = (urlparse(public_url).hostname or "").lower()
    parts = host.split(".")
    if len(parts) < 3 or host.replace(".", "").isdigit() or host.endswith(".localhost"):
        return ""
    return ".".join(parts[1:])


def set_cookie(key: str, domain: str) -> str:
    return f"{OWNER_COOKIE}={key}; Domain={domain}; Path=/; Max-Age={DAYS * 86400}; HttpOnly; Secure; SameSite=Lax"


def clear_cookies(domain: str) -> list[str]:
    return [f"{n}=; Domain={domain}; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Lax" for n in COOKIES]
