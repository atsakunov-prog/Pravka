"""Токены телефонов и служб: выдать, узнать, отозвать. В базе — только sha256."""

from __future__ import annotations

import hashlib
import secrets
from dataclasses import dataclass

from . import db


@dataclass(frozen=True)
class Who:
    user: str
    kind: str  # device | service
    name: str

    @property
    def actor(self) -> str:
        # Служба пишет в журнал своим именем: «svc:meetings» сразу видно.
        return f"svc:{self.name}" if self.kind == "service" else self.user

    @property
    def via(self) -> str:
        return self.name if self.kind == "service" else "app"


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
