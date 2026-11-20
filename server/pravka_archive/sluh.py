"""Звук тейков телефона — на распознавание домашнему компу (20.11.2026).

Владелец: «давай сделаем выбор в настройках. Сейчас временно сделаем с компа, но если будет сильно
застревать, то тогда перещёлкивает на гугл». Телефон по ходу тейка шлёт сюда кусками свой звук
(тот же PCM 16 кГц моно, что пишет в `take-audio`), а на «стоп» спрашивает итог. Распознаёт не
архив, а служба «Слух» на этом же компе (GigaAM-v3 на процессоре, `127.0.0.1:8110`, без входа):
архив только пускает телефон его токеном и передаёт как есть. Так модель (сотни МБ, свой
onnxruntime) не живёт в процессе, который смотрит наружу, а служба наружу не видна вовсе.

Договор — `contract/sluh.json`. Ошибки «Слуха» доходят до телефона его же словами и кодом:
409 на кусок — «у меня столько-то, шли с этого места», 503 — служба лежит, телефон берёт текст Google.
"""

from __future__ import annotations

import re

import httpx
from starlette.requests import Request
from starlette.responses import JSONResponse

# Кусок — секунда-другая звука (32 КБ на секунду); больше мегабайта — не наш телефон.
MAX_CHUNK = 1024 * 1024
TAKE_ID = re.compile(r"^[A-Za-z0-9_-]{1,80}$")

# Подмена дороги в тестах (httpx.MockTransport); в жизни — обычная сеть до 127.0.0.1.
transport: httpx.AsyncBaseTransport | None = None
_client: httpx.AsyncClient | None = None


def client() -> httpx.AsyncClient:
    """Один клиент на процесс: соединение с «Слухом» живёт между кусками, тейк — десятки запросов.
    Таймауты короткие нарочно: служба рядом, и долгий ответ — уже повод телефону взять Google."""
    global _client
    if _client is None:
        _client = httpx.AsyncClient(
            transport=transport,
            timeout=httpx.Timeout(connect=2.0, read=20.0, write=10.0, pool=5.0),
            trust_env=False,  # никаких прокси из окружения службы: адрес локальный
        )
    return _client


def _int(value: str | None) -> int | None:
    try:
        n = int(value or "")
    except ValueError:
        return None
    return n if n >= 0 else None


async def forward(base: str, request: Request, action: str) -> JSONResponse:
    """Кусок, конец или отмена тейка — в «Слух». Кто пришёл, проверил вызывающий."""
    take = request.query_params.get("take", "")
    if not TAKE_ID.match(take):
        return JSONResponse({"error": "take — [A-Za-z0-9_-], до 80 знаков"}, status_code=400)
    params: dict[str, int] = {}
    body = b""
    if action == "chunk":
        off = _int(request.query_params.get("off"))
        if off is None:
            return JSONResponse({"error": "off — смещение в байтах, целое ≥ 0"}, status_code=400)
        declared = _int(request.headers.get("content-length"))
        if declared is not None and declared > MAX_CHUNK:
            return JSONResponse({"error": "кусок больше 1 МБ — дели мельче"}, status_code=413)
        body = await request.body()
        if len(body) > MAX_CHUNK:
            return JSONResponse({"error": "кусок больше 1 МБ — дели мельче"}, status_code=413)
        params["off"] = off
    elif action == "end":
        total = _int(request.query_params.get("total"))
        if total is None:
            return JSONResponse({"error": "total — сколько байт звука всего, целое ≥ 0"}, status_code=400)
        params["total"] = total
    try:
        r = await client().post(
            f"{base.rstrip('/')}/take/{take}/{action}",
            params=params,
            content=body,
            headers={"content-type": "application/octet-stream"},
        )
    except httpx.HTTPError as e:
        return JSONResponse({"error": f"Слух не отвечает: {e.__class__.__name__}"}, status_code=503)
    try:
        payload = r.json()
    except ValueError:
        payload = {"error": f"Слух ответил не JSON ({r.status_code})"}
    if not isinstance(payload, dict):
        payload = {"error": "Слух ответил не объектом"}
    # 5xx «Слуха» для телефона — одно: службы нет, бери Google. Остальное — как есть.
    return JSONResponse(payload, status_code=503 if r.status_code >= 500 else r.status_code)
