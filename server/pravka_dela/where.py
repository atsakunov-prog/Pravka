"""«Где мы» (10.10.2026): точки семьи для карты Правки.

Телефон решает сам, когда искать точку и когда её отправить (лестница цены —
`core/WherePolicy.kt` в приложении); сервер только хранит по одной последней точке
на телефон и отдаёт их семье. Новых путей HTTP нет — те же /api/ops и /api/view
(новый путь пришлось бы ещё вписывать в закрытый список сайта-посредника):

| Что | Как |
|---|---|
| where.set | {"device", "point": {...}, "avatar"?: base64 JPEG или null, "avatar_at"?} — своя точка (и аватар) |
| where.off | {"device"} — перестал делиться: точки и просьбы этого телефона больше нет |
| where.ask | {"device"} — «обновите точки»: делящиеся телефоны отвечают своим GPS на ближайшем тике |
| вид where | семья (`family`), точки без аватаров, просьбы, время сервера |
| вид where_avatar?device= | аватар одного телефона base64 — телефон берёт его, только когда сменилась версия |

Журнала нет, строки удаляются — сознательно (sql/dela_0008.sql): журнал превратил бы
точку в историю перемещений. Видимость — только семья (`crm.users.family`), держит RLS.
"""

from __future__ import annotations

import base64
import binascii
import datetime as dt
import json
import re

from psycopg.types.json import Jsonb

DEVICE = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")
MAX_POINT = 4096          # байт JSON точки: в ней полтора десятка коротких полей
MAX_AVATAR = 128 * 1024   # 256×256 JPEG — около 20 КБ; с запасом


def _device(op: dict, err) -> str:
    d = str(op.get("device") or "")
    if not DEVICE.match(d):
        raise err("где мы: device — латиница, цифры и дефис, до 64 знаков")
    return d


def _family(conn, user: str, err) -> None:
    if not conn.execute("SELECT crm.in_family(%s) AS ok", (user,)).fetchone()["ok"]:
        raise err("где мы: ты не в семье — владелец добавляет командой family")


def _point(op: dict, err) -> dict:
    p = op.get("point")
    if not isinstance(p, dict):
        raise err("где мы: point — объект")
    try:
        lat, lon = float(p["lat"]), float(p["lon"])
    except (KeyError, TypeError, ValueError):
        raise err("где мы: в точке нужны lat и lon числами") from None
    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        raise err("где мы: lat или lon вне Земли")
    if len(json.dumps(p, ensure_ascii=False).encode()) > MAX_POINT:
        raise err(f"где мы: точка больше {MAX_POINT} байт")
    return p


def op_set(conn, user: str, op: dict, err) -> dict:
    device = _device(op, err)
    _family(conn, user, err)
    point = _point(op, err)
    has_avatar = "avatar" in op
    avatar = None
    if has_avatar and op["avatar"] is not None:
        try:
            avatar = base64.b64decode(str(op["avatar"]), validate=True)
        except (binascii.Error, ValueError):
            raise err("где мы: avatar — base64") from None
        if len(avatar) > MAX_AVATAR:
            raise err(f"где мы: аватар больше {MAX_AVATAR // 1024} КБ")
    avatar_at = int(op.get("avatar_at") or 0) if has_avatar else None
    row = conn.execute(
        "INSERT INTO crm.where_points (device, user_id, point, avatar, avatar_at) "
        "VALUES (%s, %s, %s, %s, coalesce(%s, 0)) "
        "ON CONFLICT (device) DO UPDATE SET point = EXCLUDED.point, updated_at = now(), "
        "avatar = CASE WHEN %s THEN EXCLUDED.avatar ELSE crm.where_points.avatar END, "
        "avatar_at = CASE WHEN %s THEN EXCLUDED.avatar_at ELSE crm.where_points.avatar_at END "
        "RETURNING device, avatar_at, updated_at",
        (device, user, Jsonb(point), avatar, avatar_at, has_avatar, has_avatar),
    ).fetchone()
    return {"device": row["device"], "avatar_at": row["avatar_at"], "updated_at": row["updated_at"]}


def op_off(conn, user: str, op: dict, err) -> dict:
    """Перестал делиться. Удаляет и не будучи в семье: убрать своё можно всегда."""
    device = _device(op, err)
    gone = conn.execute("DELETE FROM crm.where_points WHERE device = %s AND user_id = %s", (device, user)).rowcount
    conn.execute("DELETE FROM crm.where_asks WHERE device = %s AND user_id = %s", (device, user))
    return {"device": device, "removed": gone > 0}


def op_ask(conn, user: str, op: dict, err) -> dict:
    device = _device(op, err)
    _family(conn, user, err)
    row = conn.execute(
        "INSERT INTO crm.where_asks (device, user_id) VALUES (%s, %s) "
        "ON CONFLICT (device) DO UPDATE SET at = now() RETURNING at", (device, user),
    ).fetchone()
    return {"device": device, "at": _ms(row["at"])}


def _ms(t: dt.datetime) -> int:
    return int(t.timestamp() * 1000)


def view_where(conn, user, **_):
    """Точки семьи без аватаров (их версия — avatar_at) и свежие просьбы. Не в семье — пусто."""
    fam = conn.execute("SELECT crm.in_family(%s) AS ok", (user,)).fetchone()["ok"]
    now = _ms(dt.datetime.now(dt.timezone.utc))
    if not fam:
        return {"family": False, "points": [], "asks": [], "now": now}
    points = conn.execute(
        "SELECT p.device, p.user_id, u.name, p.point, p.avatar_at, p.updated_at, p.avatar IS NOT NULL AS has_avatar "
        "FROM crm.where_points p JOIN crm.users u ON u.id = p.user_id ORDER BY u.name, p.device"
    ).fetchall()
    asks = conn.execute(
        "SELECT device, user_id, at FROM crm.where_asks WHERE at > now() - interval '1 day' ORDER BY at DESC"
    ).fetchall()
    return {
        "family": True,
        "points": [{**r, "updated_at": _ms(r["updated_at"])} for r in points],
        "asks": [{"device": a["device"], "user_id": a["user_id"], "at": _ms(a["at"])} for a in asks],
        "now": now,
    }


def view_where_avatar(conn, user, device=None, **_):
    if not device or not DEVICE.match(str(device)):
        return {"device": device, "avatar": None, "avatar_at": 0}
    row = conn.execute("SELECT avatar, avatar_at FROM crm.where_points WHERE device = %s", (device,)).fetchone()
    if not row or row["avatar"] is None:
        return {"device": device, "avatar": None, "avatar_at": row["avatar_at"] if row else 0}
    return {"device": device, "avatar": base64.b64encode(bytes(row["avatar"])).decode(), "avatar_at": row["avatar_at"]}


def register(handlers: dict, views: dict, err) -> None:
    handlers["where.set"] = lambda conn, user, op: op_set(conn, user, op, err)
    handlers["where.off"] = lambda conn, user, op: op_off(conn, user, op, err)
    handlers["where.ask"] = lambda conn, user, op: op_ask(conn, user, op, err)
    views["where"] = view_where
    views["where_avatar"] = view_where_avatar
