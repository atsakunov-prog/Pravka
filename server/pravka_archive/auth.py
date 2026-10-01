"""Вход Claude по OAuth: один человек, одна страница с паролем.

claude.ai регистрируется сам (Dynamic Client Registration), открывает
/authorize — мы показываем страницу входа, владелец вводит пароль один раз,
дальше Claude живёт на токенах: доступ на 8 часов, обновление на 90 дней.

Почему не «открытый адрес с секретом»: внутри диктовки Правки (сообщения
людям), деньги семьи и здоровье. Секретный адрес — пароль, который лежит в
настройках коннектора и в журналах; ограничение по IP Anthropic не спасает:
с тех же адресов ходит любой, у кого есть аккаунт Claude.

Токены хранятся хешами (sha256): утечка таблицы входа не даёт. Коды входа и
незавершённые входы — в памяти: живут минуты, после перезапуска владелец
просто войдёт ещё раз.
"""

from __future__ import annotations

import hashlib
import hmac
import html
import secrets
import time
from dataclasses import dataclass
from typing import Any
from urllib.parse import urlparse

import anyio
import psycopg
from psycopg.types.json import Jsonb
from starlette.requests import Request
from starlette.responses import HTMLResponse, RedirectResponse, Response

from mcp.server.auth.provider import (
    AccessToken,
    AuthorizationCode,
    AuthorizationParams,
    RefreshToken,
    RegistrationError,
    construct_redirect_uri,
)
from mcp.shared.auth import OAuthClientInformationFull, OAuthToken

from .config import Config

SCOPE = "life"
ACCESS_TTL = 8 * 3600
REFRESH_TTL = 90 * 86400
CODE_TTL = 300
LOGIN_TTL = 900
LOCK_AFTER = 5          # неудачных паролей подряд …
LOCK_WINDOW = 15 * 60   # … за столько секунд — и вход закрыт на столько же


def _hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def redirect_allowed(uri: str) -> bool:
    """Куда можно вернуть код: claude.ai / claude.com и локальный Claude Code."""
    u = urlparse(uri)
    if u.scheme == "https" and u.hostname in ("claude.ai", "claude.com"):
        return True
    return u.scheme == "http" and u.hostname in ("localhost", "127.0.0.1")


@dataclass
class _Pending:
    client_id: str
    params: AuthorizationParams
    expires: float


class OwnerAuth:
    """Провайдер авторизации для FastMCP (протокол OAuthAuthorizationServerProvider)."""

    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.pending: dict[str, _Pending] = {}
        self.codes: dict[str, AuthorizationCode] = {}
        self.failures: list[float] = []

    # ------------------------------------------------------------ база

    def _db(self) -> psycopg.Connection:
        return psycopg.connect(self.cfg.db_url, autocommit=True)

    async def _run(self, fn, *args):
        return await anyio.to_thread.run_sync(fn, *args)

    # ------------------------------------------------------------ клиенты

    async def get_client(self, client_id: str) -> OAuthClientInformationFull | None:
        def q():
            with self._db() as conn:
                row = conn.execute("SELECT info FROM core.oauth_clients WHERE client_id = %s", (client_id,)).fetchone()
            return OAuthClientInformationFull.model_validate(row[0]) if row else None

        return await self._run(q)

    async def register_client(self, client_info: OAuthClientInformationFull) -> None:
        bad = [str(u) for u in (client_info.redirect_uris or []) if not redirect_allowed(str(u))]
        if bad:
            raise RegistrationError("invalid_redirect_uri", "Этот архив пускает только claude.ai и локальный Claude Code: " + ", ".join(bad))

        def q():
            with self._db() as conn:
                conn.execute(
                    "INSERT INTO core.oauth_clients (client_id, info) VALUES (%s, %s) "
                    "ON CONFLICT (client_id) DO UPDATE SET info = EXCLUDED.info",
                    (client_info.client_id, Jsonb(client_info.model_dump(mode="json"))),
                )

        await self._run(q)

    # ------------------------------------------------------------ вход

    async def authorize(self, client: OAuthClientInformationFull, params: AuthorizationParams) -> str:
        now = time.time()
        for k in [k for k, p in self.pending.items() if p.expires < now]:
            del self.pending[k]
        txn = secrets.token_urlsafe(24)
        self.pending[txn] = _Pending(client.client_id or "", params, now + LOGIN_TTL)
        return f"{self.cfg.public_url}/login?txn={txn}"

    def locked(self) -> bool:
        now = time.time()
        self.failures = [t for t in self.failures if now - t < LOCK_WINDOW]
        return len(self.failures) >= LOCK_AFTER

    async def login_page(self, request: Request) -> Response:
        if request.method == "GET":
            txn = request.query_params.get("txn", "")
            if txn not in self.pending:
                return _page("Ссылка входа устарела. Начни подключение в claude.ai заново.", None, status=400)
            return _page(None, txn)

        form = await request.form()
        txn = str(form.get("txn", ""))
        password = str(form.get("password", ""))
        pending = self.pending.get(txn)
        if pending is None or pending.expires < time.time():
            return _page("Ссылка входа устарела. Начни подключение в claude.ai заново.", None, status=400)
        if self.locked():
            return _page("Слишком много неверных паролей. Вход закрыт на четверть часа.", txn, status=429)
        if not hmac.compare_digest(password.encode("utf-8"), self.cfg.owner_password.encode("utf-8")):
            self.failures.append(time.time())
            await anyio.sleep(1.0)
            return _page("Пароль не тот.", txn, status=401)

        del self.pending[txn]
        self.failures.clear()
        code = secrets.token_urlsafe(32)
        p = pending.params
        self.codes[code] = AuthorizationCode(
            code=code,
            scopes=p.scopes or [SCOPE],
            expires_at=time.time() + CODE_TTL,
            client_id=pending.client_id,
            code_challenge=p.code_challenge,
            redirect_uri=p.redirect_uri,
            redirect_uri_provided_explicitly=p.redirect_uri_provided_explicitly,
            resource=p.resource,
            subject="owner",
        )
        return RedirectResponse(construct_redirect_uri(str(p.redirect_uri), code=code, state=p.state), status_code=302)

    async def load_authorization_code(self, client: OAuthClientInformationFull, authorization_code: str) -> AuthorizationCode | None:
        code = self.codes.get(authorization_code)
        if code is None or code.client_id != client.client_id or code.expires_at < time.time():
            return None
        return code

    # ------------------------------------------------------------ токены

    def _issue(self, client_id: str, scopes: list[str], resource: str | None) -> OAuthToken:
        access = secrets.token_urlsafe(40)
        refresh = secrets.token_urlsafe(48)
        now = time.time()
        with self._db() as conn:
            conn.execute(
                "INSERT INTO core.oauth_tokens (token_hash, kind, client_id, scopes, resource, expires_at) "
                "VALUES (%s, 'access', %s, %s, %s, to_timestamp(%s)), (%s, 'refresh', %s, %s, %s, to_timestamp(%s))",
                (_hash(access), client_id, scopes, resource, now + ACCESS_TTL,
                 _hash(refresh), client_id, scopes, resource, now + REFRESH_TTL),
            )
            conn.execute("DELETE FROM core.oauth_tokens WHERE expires_at < now()")
        return OAuthToken(access_token=access, expires_in=ACCESS_TTL, scope=" ".join(scopes), refresh_token=refresh)

    async def exchange_authorization_code(self, client: OAuthClientInformationFull, authorization_code: AuthorizationCode) -> OAuthToken:
        self.codes.pop(authorization_code.code, None)
        return await self._run(self._issue, client.client_id or "", authorization_code.scopes, authorization_code.resource)

    def _load(self, token: str, kind: str) -> tuple[str, list[str], str | None, int | None] | None:
        with self._db() as conn:
            row = conn.execute(
                "SELECT client_id, scopes, resource, extract(epoch FROM expires_at)::bigint FROM core.oauth_tokens "
                "WHERE token_hash = %s AND kind = %s AND (expires_at IS NULL OR expires_at > now())",
                (_hash(token), kind),
            ).fetchone()
        return (row[0], list(row[1]), row[2], row[3]) if row else None

    async def load_refresh_token(self, client: OAuthClientInformationFull, refresh_token: str) -> RefreshToken | None:
        row = await self._run(self._load, refresh_token, "refresh")
        if row is None or row[0] != client.client_id:
            return None
        return RefreshToken(token=refresh_token, client_id=row[0], scopes=row[1], expires_at=row[3], resource=row[2], subject="owner")

    async def exchange_refresh_token(self, client: OAuthClientInformationFull, refresh_token: RefreshToken, scopes: list[str]) -> OAuthToken:
        def rotate():
            with self._db() as conn:
                conn.execute("DELETE FROM core.oauth_tokens WHERE token_hash = %s", (_hash(refresh_token.token),))
            return self._issue(client.client_id or "", scopes or refresh_token.scopes, refresh_token.resource)

        return await self._run(rotate)

    async def load_access_token(self, token: str) -> AccessToken | None:
        row = await self._run(self._load, token, "access")
        if row is None:
            return None
        return AccessToken(token=token, client_id=row[0], scopes=row[1], expires_at=row[3], resource=row[2], subject="owner")

    async def revoke_token(self, token: Any) -> None:
        def q():
            with self._db() as conn:
                conn.execute("DELETE FROM core.oauth_tokens WHERE token_hash = %s", (_hash(token.token),))

        await self._run(q)


def _page(error: str | None, txn: str | None, status: int = 200) -> HTMLResponse:
    msg = f'<p class="err">{html.escape(error)}</p>' if error else ""
    form = ""
    if txn:
        form = (
            '<form method="post" action="/login">'
            f'<input type="hidden" name="txn" value="{html.escape(txn)}">'
            '<input type="password" name="password" placeholder="пароль архива" autofocus autocomplete="current-password">'
            '<button type="submit">Пустить Claude</button></form>'
        )
    body = f"""<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Архив Правки</title>
<style>body{{font:17px system-ui,sans-serif;background:#111;color:#eee;margin:0;padding:48px 16px}}
main{{max-width:420px;margin:auto}}h1{{font-size:22px}}p{{color:#bbb}}.err{{color:#ff8a80}}
input,button{{width:100%;box-sizing:border-box;font-size:17px;padding:12px;margin-top:12px;border-radius:10px;border:1px solid #444}}
input{{background:#1c1c1c;color:#eee}}button{{background:#e8e2d6;color:#111;border:0}}</style></head>
<body><main><h1>Архив Правки</h1><p>Claude просит доступ ко всей твоей жизни в архиве: ленте, еде, спорту, деньгам и диктовкам. Только чтение.</p>
{msg}{form}</main></body></html>"""
    return HTMLResponse(body, status_code=status)
