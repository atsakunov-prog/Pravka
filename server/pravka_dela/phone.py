"""Настройки телефона на сервере (14.10.2026, dela_0015): страница «Телефон» в вебе Дел.

Владелец («Правка 5.0»): «настройки телефона править с ПК», «две копии одного и того же», «одна база на сервере».
Настройки живут в телефоне — их читают служба, кнопки и движок диктовки, без сети тоже, — а сервер держит их копию
по устройству и очередь правок с компьютера. Схема, а не форма на каждую настройку: телефон сам знает свои
настройки (группы, подписи, тип, допустимые значения, можно ли править издалека и почему нельзя) и присылает их
вместе со значениями; веб рисует страницу по схеме; новая настройка в телефоне появляется в вебе без правки сервера.

| Что | Кто | Как |
|---|---|---|
| phone.snapshot | телефон | {"device", "name", "app", "schema"?, "schema_hash", "values", "changed", "status", "acks"} — снимок: что стоит, когда меняли на телефоне, ответы на правки с компьютера |
| phone.set | веб | {"device", "key", "value"} — правка с компьютера: ждёт телефона |
| phone.cancel | веб | {"id"} — отменить правку, пока телефон её не взял |
| phone.hide | веб | {"device"} — убрать старый телефон из списка (новый снимок его вернёт) |
| синк, поле phone | телефон | {"edits": [{id, device, key, value, at}]} — правки, которые ждут телефоны этого человека |
| вид phone?device= | веб | телефоны человека, схема и значения одного, ждущие правки и журнал |

Новых путей HTTP нет — те же /api/ops, /api/sync и /api/view: список путей сайта-посредника закрытый.

Правила:
- видит и правит только хозяин устройства (RLS «только своё»): владелец не видит телефон Марианны, и наоборот;
- ключей, токенов и паролей в снимке нет никогда: телефон их не кладёт, а сервер ещё и сам выбрасывает значения
  настроек, в имени которых есть key / token / pass / secret, и не даёт их править (SECRET);
- правка проверяется по схеме телефона (тип, варианты, пределы) — и ещё раз телефоном, когда он её ставит;
- спор «правил и там и там» решает время: правка с компьютера, которую телефон перебил своей, более поздней, —
  conflict с причиной; правка, сделанная после — ставится. Всё — в журнал crm.phone_edits.
Контракт — server/contract/dela-phone.json, телефону — docs/phone-settings-server.md.
"""

from __future__ import annotations

import datetime as dt
import hashlib
import json
import re
import uuid
from typing import Any

from psycopg.types.json import Jsonb

FEATURE = "phone"
DEVICE = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")
KEY = re.compile(r"^[a-z][a-z0-9_.]{0,63}$")
GROUP = re.compile(r"^[a-z][a-z0-9_]{0,31}$")
# Секрет — по имени настройки, целым словом между точками и подчёркиваниями: «anthropic_api_key», «todoist_token»,
# «home.password». Ключ «keep_on_fold» сюда не попадает: «keep» — не «key».
SECRET = re.compile(r"(?:^|[._])(?:api_?key|keys?|tokens?|pass|password|passwd|pwd|secret|secrets|cookie|auth|login|pin)(?:$|[._])")
TYPES = ("bool", "choice", "set", "int", "time", "text", "info")
STATUSES = ("pending", "applied", "rejected", "conflict", "superseded", "cancelled")

MAX_SCHEMA = 192 * 1024   # байт JSON схемы: сотня настроек с подсказками — около 40 КБ
MAX_VALUES = 48 * 1024
MAX_GROUPS = 40
MAX_ITEMS = 400
MAX_OPTIONS = 60
MAX_ACKS = 200
TEXT_MAX = 200
LOG = 120                 # строк журнала в виде: неделя-две правок одного телефона
FUTURE = dt.timedelta(minutes=5)  # часы телефона впереди сервера — не дальше этого
_MSK = dt.timezone(dt.timedelta(hours=3))  # в Москве нет перевода часов — сдвиг всегда +3, как в вебе Дел
EDIT_COLS = "id, device, key, title, source, was, value, status, note, actor, at, taken_at, rev, seq"


def _s(v: Any, n: int) -> str:
    """Строка в одну линию: подписи схемы, причины, статусы групп."""
    if v is None or isinstance(v, (dict, list, bool)):
        return ""
    return " ".join(str(v).split())[:n]


def is_secret(key: str) -> bool:
    return bool(SECRET.search(key))


def same(a: Any, b: Any) -> bool:
    """Одно и то же значение настройки: сравнение в каноническом JSON (1 и 1.0, порядок ключей — не разница)."""
    return json.dumps(a, sort_keys=True, ensure_ascii=False) == json.dumps(b, sort_keys=True, ensure_ascii=False)


def _int(v: Any) -> int | None:
    if isinstance(v, bool) or v is None:
        return None
    if isinstance(v, (int, float)) and float(v).is_integer():
        return int(v)
    return None


def _device(op: dict, err) -> str:
    d = str(op.get("device") or "")
    if not DEVICE.match(d):
        raise err("настройки телефона: device — латиница, цифры и дефис, до 64 знаков")
    return d


def _ms(t: dt.datetime | None) -> int | None:
    return int(t.timestamp() * 1000) if t else None


def norm_schema(s: Any, err) -> dict:
    """Схема от телефона — ровно те поля, что рисует веб и проверяет сервер; чужие поля не храним. Кривую схему
    отвергаем целиком: половина страницы хуже честного отказа словами."""
    if not isinstance(s, dict) or not isinstance(s.get("groups"), list):
        raise err("настройки телефона: schema — объект {groups: [...]}")
    groups, seen, total = [], set(), 0
    for g in s["groups"][:MAX_GROUPS]:
        if not isinstance(g, dict):
            raise err("настройки телефона: группа — объект")
        gk = str(g.get("key") or "")
        if not GROUP.match(gk):
            raise err(f"настройки телефона: ключ группы «{gk[:40]}» — латиница, цифры и _")
        items = []
        for it in g.get("items") or []:
            if not isinstance(it, dict):
                raise err(f"настройки телефона: настройка в группе {gk} — объект")
            k, t = str(it.get("key") or ""), it.get("type")
            if not KEY.match(k):
                raise err(f"настройки телефона: ключ «{k[:40]}» — латиница, цифры, точка и _")
            if k in seen:
                raise err(f"настройки телефона: ключ {k} дважды")
            if t not in TYPES:
                raise err(f"настройки телефона: у {k} тип {t!r} — нужен один из {', '.join(TYPES)}")
            seen.add(k)
            total += 1
            item: dict[str, Any] = {"key": k, "type": t, "title": _s(it.get("title"), 120) or k}
            for f, n in (("hint", 400), ("unit", 16), ("why", 300), ("sec", 80), ("row", 64)):
                v = _s(it.get(f), n)
                if v:
                    item[f] = v
            if t in ("choice", "set"):
                opts = []
                for o in (it.get("options") or [])[:MAX_OPTIONS]:
                    if isinstance(o, dict) and isinstance(o.get("v"), str) and len(o["v"]) <= 80:
                        opts.append({"v": o["v"], "t": _s(o.get("t"), 80) or o["v"] or "—"})
                if not opts:
                    raise err(f"настройки телефона: у {k} нет вариантов")
                item["options"] = opts
            if t in ("int", "time"):
                for f in ("min", "max", "step"):
                    v = _int(it.get(f))
                    if v is not None:
                        item[f] = v
                if item.get("step", 1) < 1:
                    raise err(f"настройки телефона: у {k} шаг меньше единицы")
                if "min" in item and "max" in item and item["min"] > item["max"]:
                    raise err(f"настройки телефона: у {k} min больше max")
            if t == "text":
                item["max"] = max(1, min(500, _int(it.get("max")) or TEXT_MAX))
            # Править издалека — только то, что телефон сам разрешил; показ (info) и секреты — никогда.
            item["edit"] = bool(it.get("edit")) and t != "info" and not is_secret(k)
            items.append(item)
        groups.append({"key": gk, "title": _s(g.get("title"), 80) or gk, "hint": _s(g.get("hint"), 200),
                       "icon": _s(g.get("icon"), 32), "note": _s(g.get("note"), 300), "items": items})
    if total > MAX_ITEMS:
        raise err(f"настройки телефона: больше {MAX_ITEMS} настроек")
    return {"v": _int(s.get("v")) or 1, "groups": groups}


def items_of(schema: dict | None) -> dict[str, dict]:
    return {it["key"]: it for g in (schema or {}).get("groups") or [] for it in g.get("items") or []}


def schema_hash(schema: dict) -> str:
    return hashlib.sha256(json.dumps(schema, sort_keys=True, ensure_ascii=False).encode()).hexdigest()[:32]


def check_value(item: dict, v: Any, err) -> Any:
    """Значение правки по схеме телефона. Отказ — словами: веб показывает его тут же, у поля."""
    t, title = item["type"], item.get("title") or item["key"]
    if t == "info":
        raise err(f"«{title}» только показывается")
    if t == "bool":
        if not isinstance(v, bool):
            raise err(f"«{title}»: нужно да или нет")
        return v
    if t in ("int", "time"):
        n = _int(v)
        if n is None:
            raise err(f"«{title}»: нужно целое число")
        lo, hi, step = item.get("min"), item.get("max"), item.get("step") or 1
        if (lo is not None and n < lo) or (hi is not None and n > hi):
            raise err(f"«{title}»: от {lo} до {hi}")
        if step > 1 and (n - (lo or 0)) % step:
            raise err(f"«{title}»: шаг {step}")
        return n
    if t == "choice":
        if not isinstance(v, str) or v not in [o["v"] for o in item["options"]]:
            raise err(f"«{title}»: такого варианта нет")
        return v
    if t == "set":
        allowed = [o["v"] for o in item["options"]]
        if not isinstance(v, list) or any(not isinstance(x, str) or x not in allowed for x in v):
            raise err(f"«{title}»: такого варианта нет")
        return [x for x in allowed if x in v]   # порядок — как в схеме, без повторов
    if t == "text":
        if not isinstance(v, str):
            raise err(f"«{title}»: нужен текст")
        s = " ".join(v.split())
        if len(s) > item.get("max", TEXT_MAX):
            raise err(f"«{title}»: длиннее {item.get('max', TEXT_MAX)} знаков")
        return s
    raise err(f"«{title}»: незнакомый тип {t}")


def _plain(v: Any) -> bool:
    """Значение снимка — простое: да/нет, число, строка, список строк. Объектам там не место."""
    if v is None or isinstance(v, (bool, int, float)):
        return True
    if isinstance(v, str):
        return len(v) <= 500
    return isinstance(v, list) and len(v) <= MAX_OPTIONS and all(isinstance(x, str) and len(x) <= 120 for x in v)


def _label(item: dict | None, v: Any) -> str:
    """Значение словами для причин и журнала: вариант — его подписью, да/нет — словом."""
    if item and item.get("options"):
        names = {o["v"]: o["t"] for o in item["options"]}
        if isinstance(v, list):
            return ", ".join(names.get(x, x) for x in v) or "ничего"
        if v in names:
            return names[v]
    if isinstance(v, bool):
        return "да" if v else "нет"
    if v is None:
        return "—"
    return f"{v} {item['unit']}" if item and item.get("unit") else str(v)


def op_snapshot(conn, user: str, op: dict, err) -> dict:
    """Снимок от телефона: схема (если поменялась), значения, ответы на правки. Что поменяли на самом телефоне —
    строкой журнала; ждущую правку, которую телефон уже перебил своей, более поздней, — в спор."""
    device = _device(op, err)
    owner = conn.execute("SELECT crm.phone_owner(%s) AS u", (device,)).fetchone()["u"]
    if owner and owner != user:
        raise err("настройки телефона: этот телефон записан на другого человека")
    row = conn.execute("SELECT * FROM crm.phone_devices WHERE device = %s FOR UPDATE", (device,)).fetchone()
    sent_hash = _s(op.get("schema_hash"), 80)
    if op.get("schema") is not None:
        if len(json.dumps(op["schema"], ensure_ascii=False).encode()) > MAX_SCHEMA:
            raise err(f"настройки телефона: схема больше {MAX_SCHEMA // 1024} КБ")
        schema = norm_schema(op["schema"], err)
        h = sent_hash or schema_hash(schema)
    else:
        schema = row["schema"] if row else {}
        h = row["schema_hash"] if row else ""
    items = items_of(schema)
    # Схема телефона поменялась (новая сборка), а он её не приложил: значения до новой схемы не трогаем, просим её.
    need_schema = not items or bool(sent_hash and sent_hash != h)

    raw = op.get("values") or {}
    if not isinstance(raw, dict):
        raise err("настройки телефона: values — объект")
    if len(json.dumps(raw, ensure_ascii=False).encode()) > MAX_VALUES:
        raise err(f"настройки телефона: значений больше {MAX_VALUES // 1024} КБ")
    old = (row["vals"] if row else {}) or {}
    if need_schema and row:
        vals = old
    else:
        vals = {k: v for k, v in raw.items() if k in items and not is_secret(k) and _plain(v)}
    changed = {}
    for k, v in (op["changed"] if isinstance(op.get("changed"), dict) else {}).items():
        n = _int(v)
        if k in items and n and n > 0:
            changed[k] = n
    groups = {g["key"] for g in schema.get("groups") or []}
    status = {g: _s(v, 200) for g, v in (op["status"] if isinstance(op.get("status"), dict) else {}).items()
              if g in groups and _s(v, 200)}

    now = conn.execute("SELECT now() AS t").fetchone()["t"]
    conn.execute(
        "INSERT INTO crm.phone_devices (device, user_id, name, app, schema, schema_hash, vals, changed, status, "
        "snapshot_at, seen_at, hidden_at) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, now(), now(), NULL) "
        "ON CONFLICT (device) DO UPDATE SET name = EXCLUDED.name, app = EXCLUDED.app, schema = EXCLUDED.schema, "
        "schema_hash = EXCLUDED.schema_hash, vals = EXCLUDED.vals, changed = EXCLUDED.changed, status = EXCLUDED.status, "
        "snapshot_at = now(), seen_at = now(), hidden_at = NULL",
        (device, user, _s(op.get("name"), 120), _s(op.get("app"), 80), Jsonb(schema), h, Jsonb(vals),
         Jsonb(changed), Jsonb(status)),
    )

    # Ответы на правки с компьютера: взял, не встало (почему), на телефоне поменяли позже.
    acked: dict[str, Any] = {}
    acks = op.get("acks") or []
    if not isinstance(acks, list):
        raise err("настройки телефона: acks — список")
    n_acked = 0
    for a in acks[:MAX_ACKS]:
        if not isinstance(a, dict):
            continue
        try:
            eid = str(uuid.UUID(str(a.get("id"))))
        except ValueError:
            continue
        ok = a.get("ok") is True
        st = "applied" if ok else ("conflict" if a.get("conflict") else "rejected")
        r = conn.execute(
            "UPDATE crm.phone_edits SET status = %s, note = %s, taken_at = now() "
            "WHERE id = %s AND device = %s AND source = 'web' AND status = 'pending' RETURNING key, value",
            (st, _s(a.get("note"), 500), eid, device)).fetchone()
        if r:
            n_acked += 1
            if ok:
                acked[r["key"]] = r["value"]

    # Что поменяли на самом телефоне — в журнал. Первый снимок — не «поменяли», а знакомство; правка с компьютера,
    # которую телефон только что взял, — тоже не его собственная.
    if row and not need_schema:
        for k, v in vals.items():
            if k not in old or same(old[k], v) or (k in acked and same(acked[k], v)):
                continue
            at = dt.datetime.fromtimestamp(changed[k] / 1000, dt.timezone.utc) if k in changed else now
            at = min(at, now + FUTURE)
            conn.execute(
                "INSERT INTO crm.phone_edits (device, user_id, key, title, source, was, value, status, actor, at, taken_at) "
                "VALUES (%s, %s, %s, %s, 'phone', %s, %s, 'applied', 'phone', %s, now())",
                (device, user, k, items[k].get("title") or k, Jsonb(old[k]), Jsonb(v), at))

    # Ждущие правки: той настройки больше нет или её перестали отдавать издалека — не встанет; телефон перебил
    # своей, более поздней, — спор, побеждает последний.
    if not need_schema:
        for e in conn.execute(
                "SELECT id, key, value, at FROM crm.phone_edits WHERE device = %s AND status = 'pending'", (device,)).fetchall():
            it = items.get(e["key"])
            note, st = None, "rejected"
            if not it:
                note = "на телефоне этой настройки больше нет"
            elif not it["edit"]:
                note = "эту настройку с компьютера больше не правят" + (f": {it['why']}" if it.get("why") else "")
            elif e["key"] in changed and changed[e["key"]] > _ms(e["at"]) and e["key"] in vals and not same(vals[e["key"]], e["value"]):
                when = dt.datetime.fromtimestamp(changed[e["key"]] / 1000, dt.timezone.utc).astimezone(_MSK)
                note, st = f"на телефоне поменяли позже ({when:%d.%m %H:%M}) — осталось с телефона: {_label(it, vals[e['key']])}", "conflict"
            if note:
                conn.execute("UPDATE crm.phone_edits SET status = %s, note = %s, taken_at = now() WHERE id = %s",
                             (st, note, e["id"]))
    pending = conn.execute("SELECT count(*) AS n FROM crm.phone_edits WHERE device = %s AND status = 'pending'",
                           (device,)).fetchone()["n"]
    return {"device": device, "need_schema": need_schema, "pending": pending, "acked": n_acked}


def _own(conn, device: str, err) -> dict:
    row = conn.execute("SELECT device, schema, vals FROM crm.phone_devices WHERE device = %s", (device,)).fetchone()
    if not row:
        raise err("настройки телефона: нет такого телефона или он не твой")
    return row


def op_set(conn, user: str, op: dict, err) -> dict:
    """Правка с компьютера: проверить по схеме, прежнюю ждущую того же ключа — заменить, новую — в очередь телефону.
    Вернул как на телефоне — ждать нечего: правки нет, прежняя ждущая снята."""
    device = _device(op, err)
    row = _own(conn, device, err)
    key = str(op.get("key") or "")
    it = items_of(row["schema"]).get(key)
    if not it:
        raise err(f"у телефона нет настройки «{key[:64]}»")
    if not it["edit"]:
        raise err(f"«{it.get('title') or key}» с компьютера не правится" + (f": {it['why']}" if it.get("why") else ""))
    if "value" not in op:
        raise err("настройки телефона: нужно value")
    value = check_value(it, op["value"], err)
    gone = conn.execute(
        "UPDATE crm.phone_edits SET status = 'superseded', note = 'заменена новой правкой с компьютера' "
        "WHERE device = %s AND key = %s AND source = 'web' AND status = 'pending' RETURNING id", (device, key)).fetchall()
    vals = row["vals"] or {}
    if key in vals and same(vals[key], value):
        return {"edit": None, "same": True, "superseded": len(gone)}
    e = conn.execute(
        f"INSERT INTO crm.phone_edits (device, user_id, key, title, source, was, value, status, actor) "
        f"VALUES (%s, %s, %s, %s, 'web', %s, %s, 'pending', crm.actor()) RETURNING {EDIT_COLS}",
        (device, user, key, it.get("title") or key, Jsonb(vals[key]) if key in vals else None, Jsonb(value))).fetchone()
    return {"edit": e, "same": False, "superseded": len(gone)}


def op_cancel(conn, user: str, op: dict, err) -> dict:
    try:
        eid = str(uuid.UUID(str(op.get("id"))))
    except ValueError:
        raise err("настройки телефона: id — не uuid") from None
    r = conn.execute(
        f"UPDATE crm.phone_edits SET status = 'cancelled', note = 'отменена с компьютера' "
        f"WHERE id = %s AND source = 'web' AND status = 'pending' RETURNING {EDIT_COLS}", (eid,)).fetchone()
    if not r:
        raise err("правки уже нет: телефон её взял или её отменили")
    return {"edit": r}


def op_hide(conn, user: str, op: dict, err) -> dict:
    device = _device(op, err)
    r = conn.execute("UPDATE crm.phone_devices SET hidden_at = now() WHERE device = %s RETURNING device", (device,)).fetchone()
    if not r:
        raise err("настройки телефона: нет такого телефона или он не твой")
    return {"device": device}


def for_sync(conn, user: str) -> list[dict]:
    """Поле phone синка: правки с компьютера, которые ждут телефоны этого человека (RLS — только свои). Телефон
    берёт свои по device; уже взятые им снова не ставит — помнит их id."""
    return conn.execute(
        "SELECT id, device, key, value, at FROM crm.phone_edits WHERE source = 'web' AND status = 'pending' "
        "ORDER BY at, id LIMIT 200").fetchall()


def view_phone(conn, user: str, device=None, **_) -> dict:
    """Телефоны человека (свежий сверху), схема и значения одного, его ждущие правки и журнал."""
    now = conn.execute("SELECT now() AS t").fetchone()["t"]
    devices = conn.execute(
        "SELECT device, name, app, snapshot_at, seen_at FROM crm.phone_devices WHERE hidden_at IS NULL "
        "ORDER BY seen_at DESC NULLS LAST, device").fetchall()
    pick = str(device) if device and DEVICE.match(str(device)) else (devices[0]["device"] if devices else None)
    d = None
    if pick:
        d = conn.execute(
            "SELECT device, name, app, schema, vals AS values, changed, status, snapshot_at, seen_at, hidden_at "
            "FROM crm.phone_devices WHERE device = %s", (pick,)).fetchone()
    if not d:
        return {"devices": devices, "device": None, "pending": [], "edits": [], "now": now}
    pending = conn.execute(
        f"SELECT {EDIT_COLS} FROM crm.phone_edits WHERE device = %s AND status = 'pending' ORDER BY at", (pick,)).fetchall()
    edits = conn.execute(
        f"SELECT {EDIT_COLS} FROM crm.phone_edits WHERE device = %s ORDER BY at DESC, seq DESC LIMIT %s", (pick, LOG)).fetchall()
    return {"devices": devices, "device": d, "pending": pending, "edits": edits, "now": now}


def register(handlers: dict, views: dict, err) -> None:
    handlers["phone.snapshot"] = lambda conn, user, op: op_snapshot(conn, user, op, err)
    handlers["phone.set"] = lambda conn, user, op: op_set(conn, user, op, err)
    handlers["phone.cancel"] = lambda conn, user, op: op_cancel(conn, user, op, err)
    handlers["phone.hide"] = lambda conn, user, op: op_hide(conn, user, op, err)
    views["phone"] = view_phone
