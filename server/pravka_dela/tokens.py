"""Токены телефонов и служб: выдать, узнать, отозвать. В базе — только sha256."""

from __future__ import annotations

import hashlib
import secrets
from dataclasses import dataclass

from . import db


@dataclass(frozen=True)
class Who:
    user: str
    kind: str  # device | service | web
    name: str

    @property
    def actor(self) -> str:
        # Служба пишет в журнал своим именем: «svc:meetings» сразу видно.
        return f"svc:{self.name}" if self.kind == "service" else self.user

    @property
    def via(self) -> str:
        return {"service": self.name, "web": "web"}.get(self.kind, "app")


def _hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def issue(url: str, user: str, kind: str, name: str) -> str:
    token = secrets.token_urlsafe(32)
    with db.session(url, "system", "svc:tokens") as conn:
        conn.execute(
            "INSERT INTO crm.tokens (user_id, kind, name, token_hash) VALUES (%s, %s, %s, %s)",
            (user, kind, name, _hash(token)),
        )
    return token


def who(url: str, token: str) -> Who | None:
    if not token or len(token) < 32:
        return None
    with db.session(url, "system", "svc:tokens") as conn:
        row = conn.execute(
            "UPDATE crm.tokens SET last_seen_at = now() WHERE token_hash = %s AND revoked_at IS NULL "
            "RETURNING user_id, kind, name",
            (_hash(token),),
        ).fetchone()
    return Who(row["user_id"], row["kind"], row["name"]) if row else None


def revoke(url: str, name: str) -> int:
    with db.session(url, "system", "svc:tokens") as conn:
        return conn.execute(
            "UPDATE crm.tokens SET revoked_at = now() WHERE name = %s AND revoked_at IS NULL", (name,)
        ).rowcount


# ── Веб: приглашения и сессии ────────────────────────────────────────────
# Команда входит одноразовой ссылкой-приглашением (её выдаёт владелец: команда
# invite или Claude); ссылка живёт двое суток и работает один раз. Владелец с
# 22.10.2026 — ещё и своим паролем Ковчега или общей кукой домена (family.py).
# Сессия — годы: «попроси ссылку у Саши» самому Саше больше не показывается.

INVITE_TTL_H = 48
SESSION_DAYS = 3650


def invite(url: str, user: str) -> str:
    code = secrets.token_urlsafe(24)
    with db.session(url, "system", "svc:auth") as conn:
        conn.execute(
            "INSERT INTO crm.login_requests (poll_hash, code, user_id, expires_at, confirmed_at) "
            "VALUES (%s, 'invite', %s, now() + make_interval(hours => %s), now())",
            (_hash(code), user, INVITE_TTL_H),
        )
    return code


def redeem(url: str, code: str, user_agent: str = "") -> tuple[str, str] | None:
    """Приглашение — в сессию. Возвращает (токен сессии, пользователь) или None."""
    if not code or len(code) < 20:
        return None
    with db.session(url, "system", "svc:auth") as conn:
        row = conn.execute(
            "UPDATE crm.login_requests SET used_at = now() WHERE poll_hash = %s AND used_at IS NULL "
            "AND confirmed_at IS NOT NULL AND expires_at > now() RETURNING user_id",
            (_hash(code),),
        ).fetchone()
        if not row:
            return None
        token = secrets.token_urlsafe(32)
        conn.execute(
            "INSERT INTO crm.sessions (token_hash, user_id, expires_at, user_agent) "
            "VALUES (%s, %s, now() + make_interval(days => %s), %s)",
            (_hash(token), row["user_id"], SESSION_DAYS, user_agent[:200]),
        )
    return token, row["user_id"]


def session_who(url: str, token: str) -> Who | None:
    if not token or len(token) < 32:
        return None
    with db.session(url, "system", "svc:auth") as conn:
        row = conn.execute(
            "UPDATE crm.sessions SET last_seen_at = now() WHERE token_hash = %s AND expires_at > now() RETURNING user_id",
            (_hash(token),),
        ).fetchone()
    return Who(row["user_id"], "web", "веб") if row else None


def owner_session(url: str, user_agent: str = "") -> tuple[str, str] | None:
    """Сессия веба владельцу — вход его паролем (family.py, 22.10.2026). Возвращает (токен, пользователь)."""
    token = secrets.token_urlsafe(32)
    with db.session(url, "system", "svc:auth") as conn:
        row = conn.execute("SELECT crm.owner_id() AS id").fetchone()
        if not row or not row["id"]:
            return None
        conn.execute(
            "INSERT INTO crm.sessions (token_hash, user_id, expires_at, user_agent) "
            "VALUES (%s, %s, now() + make_interval(days => %s), %s)",
            (_hash(token), row["id"], SESSION_DAYS, user_agent[:200]),
        )
    return token, row["id"]


_owner_cache: dict[str, str] = {}


def owner_who(url: str) -> Who | None:
    """Владелец — для куки владельца с общего домена (family.py): своей сессии в базе у неё нет."""
    owner = _owner_cache.get(url)
    if not owner:
        with db.session(url, "system", "svc:auth") as conn:
            row = conn.execute("SELECT crm.owner_id() AS id").fetchone()
        owner = (row or {}).get("id") or ""
        if owner:
            _owner_cache[url] = owner
    return Who(owner, "web", "веб") if owner else None


def logout(url: str, token: str) -> None:
    with db.session(url, "system", "svc:auth") as conn:
        conn.execute("DELETE FROM crm.sessions WHERE token_hash = %s", (_hash(token),))
