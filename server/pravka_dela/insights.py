"""«Только тебе» (dela_0027, аудит 15.10.2026 №8–11; место этапа 6): наблюдения только владельцу Дел.

Разбор встреч и переписки (этап 6) будет замечать несостыковки, неуверенность, «кто за столом» и оценки людей;
владелец и сам пишет такое о людях и клиентах. Это видно только ему — не команде, не в общей хронологии клиента, не в
синке и не в коннекторе для чужих. Здесь — одно хранилище (crm.insights) и его операции и виды:

- insight.add {insight: {id?, kind?, text, quote?, person_ids?, project_id?, deal_id?, org_id?, meeting_id? | meeting_ref?,
  at?, at_sec?, source?, source_ref?, item_key?}} — запись. meeting_ref — номер встречи службы (ref): сервер найдёт её у
  владельца. С item_key — повтор той же находки службы находит прежнюю строку: живую обновляет (текст, цитата, когда,
  о ком; состояние не трогает), убранную не возвращает — ответ {insight, existing: true};
- insight.set {id, set: {state?, text?, kind?, person_ids?, project_id?, deal_id?, org_id?}} — «Видел», «Мимо»,
  «Неверно», правка;
- insight.delete {id} — убрать (deleted_at; строки не удаляются);
- insight.retire {source, source_ref, keep: [item_key]} — служба разобрала заново (флаг lens, 16.10.2026): её неоценённые
  пункты этого источника (state new), которых нет в keep, убираются — оценённое остаётся словом владельца;
- вид insights?person_id|project_id|deal_id|org_id|meeting_id|state|limit, поле insights у видов client, deal, dossier и
  meeting (of). Команде — пусто: RLS «только владельцу» и правило строки (сотруднику insight.add — отказ базы словами).
В синк не входит: телефон берёт «Только тебе» видами. Клиенты шлют операции только при флаге privacy.
"""

from __future__ import annotations

import datetime as dt
import uuid
from typing import Any

FIELDS = {"id", "kind", "text", "quote", "person_ids", "project_id", "deal_id", "org_id", "meeting_id", "meeting_ref",
          "at", "at_sec", "source", "source_ref", "item_key"}
SET_FIELDS = {"state", "text", "kind", "person_ids", "project_id", "deal_id", "org_id", "quote"}
STATES = ("new", "seen", "useful", "dismissed", "wrong", "moved")
SOURCES = ("manual", "web", "phone", "mcp", "meeting", "digest")
CARD_MAX = 50
LIST_MAX = 500

# Строка вида: наблюдение и названия того, о ком оно (для подписи в карточке без лишних запросов).
SELECT = (
    "SELECT i.*, coalesce(nullif(btrim(m.title), ''), m.source_title) AS meeting_title, m.ref AS meeting_ref, "
    "p.name AS project_name, d.name AS deal_name, o.name AS org_name, "
    "ARRAY(SELECT coalesce(pe.short, pe.name) FROM unnest(i.person_ids) WITH ORDINALITY AS u(pid, n) "
    "      JOIN crm.people pe ON pe.id = u.pid ORDER BY u.n) AS people "
    "FROM crm.insights i LEFT JOIN crm.meetings m ON m.id = i.meeting_id LEFT JOIN crm.projects p ON p.id = i.project_id "
    "LEFT JOIN crm.deals d ON d.id = i.deal_id LEFT JOIN crm.orgs o ON o.id = i.org_id ")


def _uuid(v: Any, what: str, err) -> str | None:
    if v in (None, ""):
        return None
    try:
        return str(uuid.UUID(str(v)))
    except ValueError:
        raise err(f"«Только тебе»: {what} — не id") from None


def _clean(conn, user: str, d: dict, err, new: bool) -> dict:
    """Поля наблюдения: проверка и приведение (id — uuid, люди — список id, когда — ISO, встреча по номеру службы)."""
    out: dict = {}
    for k in ("project_id", "deal_id", "org_id", "meeting_id"):
        if k in d:
            out[k] = _uuid(d[k], k, err)
    if "person_ids" in d:
        ppl = d["person_ids"] or []
        if not isinstance(ppl, list):
            raise err("«Только тебе»: person_ids — список id людей")
        out["person_ids"] = [x for x in (_uuid(p, "person_ids", err) for p in ppl) if x]
    if d.get("meeting_ref") and not out.get("meeting_id"):
        m = conn.execute("SELECT id FROM crm.meetings WHERE owner_id = %s AND ref = %s ORDER BY chars_n DESC LIMIT 1",
                         (user, str(d["meeting_ref"]).strip())).fetchone()
        if not m:
            raise err(f"«Только тебе»: встречи №{d['meeting_ref']} нет")
        out["meeting_id"] = str(m["id"])
    for k in ("text", "quote", "kind", "source", "source_ref", "item_key"):
        if k in d:
            v = d[k]
            out[k] = (" ".join(str(v).split()) if k in ("kind", "source", "item_key") else str(v).strip()) if v is not None else None
            if k != "text" and out[k] == "":
                out[k] = None
    if new and not (out.get("text") or "").strip():
        raise err("«Только тебе»: нужен текст")
    if out.get("source") and out["source"] not in SOURCES:
        raise err(f"«Только тебе»: откуда — {', '.join(SOURCES)}")
    if "at" in d and d["at"]:
        try:
            at = dt.datetime.fromisoformat(str(d["at"]))
        except ValueError:
            raise err("«Только тебе»: at — время ISO") from None
        out["at"] = at if at.tzinfo else at.replace(tzinfo=dt.timezone.utc)
    if "at_sec" in d and d["at_sec"] is not None:
        try:
            out["at_sec"] = round(float(d["at_sec"]), 1)
        except (TypeError, ValueError):
            raise err("«Только тебе»: at_sec — секунда от начала встречи") from None
    if "state" in d:
        if d["state"] not in STATES:
            raise err(f"«Только тебе»: состояние — {', '.join(STATES)}")
        out["state"] = d["state"]
    return out


def _one(conn, iid) -> dict | None:
    return conn.execute(SELECT + "WHERE i.id = %s", (iid,)).fetchone()


def op_add(conn, user: str, op: dict, err) -> dict:
    raw = op.get("insight") or {}
    if not isinstance(raw, dict):
        raise err("«Только тебе»: insight — словарь")
    extra = set(raw) - FIELDS
    if extra:
        raise err(f"«Только тебе»: нет таких полей — {', '.join(sorted(extra))}")
    data = _clean(conn, user, raw, err, new=True)
    data.setdefault("source", "manual")
    if raw.get("id"):
        data["id"] = _uuid(raw["id"], "id", err)
        have = conn.execute("SELECT id FROM crm.insights WHERE id = %s", (data["id"],)).fetchone()
        if have:  # тот же id уже записан: телефон повторил без op_id
            return {"insight": _one(conn, have["id"]), "existing": True}
    if data.get("item_key"):
        same = conn.execute(
            "SELECT id, deleted_at FROM crm.insights WHERE owner_id = %s AND source = %s "
            "AND coalesce(source_ref, '') = coalesce(%s, '') AND item_key = %s",
            (user, data["source"], data.get("source_ref"), data["item_key"])).fetchone()
        if same:
            if same["deleted_at"] is None:
                upd = {k: data[k] for k in ("text", "quote", "at", "at_sec", "person_ids") if k in data}
                if upd:
                    cols = ", ".join(f"{k} = %s" for k in upd)
                    conn.execute(f"UPDATE crm.insights SET {cols} WHERE id = %s", [*upd.values(), same["id"]])
            return {"insight": _one(conn, same["id"]), "existing": True}
    data["owner_id"] = user
    data["author"] = user  # правило базы подпишет crm.actor()
    cols = list(data)
    row = conn.execute(f"INSERT INTO crm.insights ({', '.join(cols)}) VALUES ({', '.join(['%s'] * len(cols))}) RETURNING id",
                       [data[c] for c in cols]).fetchone()
    return {"insight": _one(conn, row["id"])}


def op_set(conn, user: str, op: dict, err) -> dict:
    iid = _uuid(op.get("id"), "id", err)
    raw = op.get("set") or {}
    if not isinstance(raw, dict) or not raw:
        raise err("«Только тебе»: set — словарь полей")
    extra = set(raw) - SET_FIELDS
    if extra:
        raise err(f"«Только тебе»: нельзя менять — {', '.join(sorted(extra))}")
    data = _clean(conn, user, raw, err, new=False)
    if "text" in data and not (data["text"] or "").strip():
        raise err("«Только тебе»: текст не может быть пустым — убери наблюдение")
    cols = ", ".join(f"{k} = %s" for k in data)
    row = conn.execute(f"UPDATE crm.insights SET {cols} WHERE id = %s AND deleted_at IS NULL RETURNING id",
                       [*data.values(), iid]).fetchone()
    if not row:
        raise err("«Только тебе»: нет такого наблюдения")
    return {"insight": _one(conn, row["id"])}


def op_delete(conn, user: str, op: dict, err) -> dict:
    iid = _uuid(op.get("id"), "id", err)
    row = conn.execute("UPDATE crm.insights SET deleted_at = now() WHERE id = %s AND deleted_at IS NULL RETURNING id",
                       (iid,)).fetchone()
    if not row:
        raise err("«Только тебе»: нет такого наблюдения или оно уже убрано")
    return {"insight": _one(conn, row["id"])}


def op_retire(conn, user: str, op: dict, err) -> dict:
    """Служба разобрала встречу заново (или прислала сводку повторно): прежние пункты её источника, которых в новом
    наборе нет, — убрать, если владелец их не оценил (state new). Иначе в карточках копились бы устаревшие наблюдения,
    а мерило считало бы лишнее. Оценённое («Пригодилось», «Мимо», «Неверно», «Видел») — слово владельца, остаётся (как
    у дайджеста, digest._to_insights). Только источники служб (meeting, digest)."""
    source, ref = str(op.get("source") or "").strip(), str(op.get("source_ref") or "").strip()
    keep = op.get("keep")
    if source not in RATE_SOURCES or not ref:
        raise err("«Только тебе»: нужен source (meeting, digest) и source_ref")
    if not isinstance(keep, list) or not all(isinstance(k, str) for k in keep):
        raise err("«Только тебе»: keep — список ключей пунктов, которые остаются")
    gone = conn.execute(
        "UPDATE crm.insights SET deleted_at = now() WHERE owner_id = %s AND source = %s AND source_ref = %s "
        "AND deleted_at IS NULL AND state = 'new' AND item_key IS NOT NULL AND NOT (item_key = ANY (%s)) RETURNING id",
        (user, source, ref, keep)).fetchall()
    return {"retired": len(gone)}


def of(conn, where: str, params: list, limit: int = CARD_MAX) -> list[dict]:
    """Живые наблюдения карточки, свежие сверху (поле insights видов client, deal, dossier, meeting). Команде — []: RLS."""
    return conn.execute(SELECT + f"WHERE i.deleted_at IS NULL AND ({where}) ORDER BY i.at DESC, i.created_at DESC "
                        f"LIMIT {int(limit)}", params).fetchall()


def view_insights(conn, user: str, person_id=None, project_id=None, deal_id=None, org_id=None, meeting_id=None,
                  state=None, limit=None, err=Exception, **_) -> dict:
    """Вид insights: живые наблюдения владельца, отбор по человеку, клиенту, сделке, компании, встрече, состоянию."""
    where, params = ["TRUE"], []
    for col, v in (("project_id", project_id), ("deal_id", deal_id), ("org_id", org_id), ("meeting_id", meeting_id)):
        if v:
            where.append(f"i.{col} = %s")
            params.append(_uuid(v, col, err))
    if person_id:
        where.append("%s = ANY (i.person_ids)")
        params.append(_uuid(person_id, "person_id", err))
    if state:
        if state not in STATES:
            raise err(f"«Только тебе»: состояние — {', '.join(STATES)}")
        where.append("i.state = %s")
        params.append(state)
    try:
        n = max(1, min(int(limit or CARD_MAX), LIST_MAX))
    except ValueError:
        raise err("limit — число") from None
    return {"items": of(conn, " AND ".join(where), params, n)}


# ── Мерило «Только тебе» (16.10.2026, этап 6 аудита, раздел 10.2 п. 8–9; слово владельца «да. Делай») ──────────────
#
# Неделя в тени: у каждого пункта «Только тебе» (разбор встречи, сводка дайджеста) — кнопки «Пригодилось» и «Мимо»
# (и «Неверно» — пункт ошибся). Через 4 недели — мерило по виду: «неверно» больше 10 % или «пригодилось» меньше 30 %
# оценённых — вид выключается строкой Свода meetings.lens (rules.py) без выката. Оценка — состояние строки crm.insights
# (useful / dismissed / wrong; «снять» — new), операция insight.rate: кнопка в Telegram знает не id строки, а ключ
# пункта своей службы (source, source_ref, item_key) — по нему и находит. Счёт — вид lens.

RATES = ("useful", "dismissed", "wrong", "new")
RATE_SOURCES = ("meeting", "digest")
SHADOW_DAYS = 7          # неделя в тени — от первого пункта источника
MEASURE_DAYS = 28        # мерило — через 4 недели
RATED_MIN = 5            # меньше оценок у вида — судить рано, а не «выключить»
WRONG_MAX = 0.10
USEFUL_MIN = 0.30


def op_rate(conn, user: str, op: dict, err) -> dict:
    """Кнопка у пункта «Только тебе»: rate — useful («Пригодилось»), dismissed («Мимо»), wrong («Неверно») или new
    (снять оценку). Пункт — id строки или ключ службы: source (meeting, digest), source_ref (номер встречи у службы, ref
    сводки), item_key. Убранный пункт не оценивается; та же оценка — без записи."""
    rate = str(op.get("rate") or "")
    if rate not in RATES:
        raise err(f"«Только тебе»: оценка — {', '.join(RATES)}")
    if op.get("id"):
        row = conn.execute("SELECT id, state, deleted_at FROM crm.insights WHERE id = %s",
                           (_uuid(op["id"], "id", err),)).fetchone()
    else:
        source, ref, key = (str(op.get(k) or "").strip() for k in ("source", "source_ref", "item_key"))
        if source not in RATE_SOURCES or not key:
            raise err("«Только тебе»: нужен id или source (meeting, digest), source_ref и item_key пункта")
        row = conn.execute(
            "SELECT id, state, deleted_at FROM crm.insights WHERE owner_id = %s AND source = %s "
            "AND coalesce(source_ref, '') = %s AND item_key = %s", (user, source, ref, key)).fetchone()
    if not row:
        raise err("«Только тебе»: нет такого пункта")
    if row["deleted_at"] is not None:
        raise err("«Только тебе»: пункт уже убран — оценивать нечего")
    if row["state"] != rate:
        conn.execute("UPDATE crm.insights SET state = %s WHERE id = %s", (rate, row["id"]))
    return {"insight": _one(conn, row["id"]), "changed": row["state"] != rate}


def _verdict(n_rated: int, useful: int, wrong: int, age_days: int) -> str:
    """Что мерило говорит о виде: тень (первая неделя), копим (до 4 недель), мало оценок, выключить, держим."""
    if age_days < SHADOW_DAYS:
        return "shadow"
    if age_days < MEASURE_DAYS:
        return "collect"
    if n_rated < RATED_MIN:
        return "few"
    return "off" if wrong / n_rated > WRONG_MAX or useful / n_rated < USEFUL_MIN else "keep"


VERDICT_WORD = {"shadow": "неделя в тени", "collect": "копим оценки", "few": "мало оценок", "off": "выключить",
                "keep": "держим"}


def view_lens(conn, user: str, err=Exception, **_) -> dict:
    """Вид lens — счёт «Только тебе» по источнику и виду: пунктов, оценено, пригодилось, мимо, неверно, доли от
    оценённых, с какого дня копится (первый пункт источника) и что говорит мерило; suggest_off — виды к выключению в
    форме meetings.lens; lens — сам выключатель сейчас. Только владельцу Дел: «Только тебе» — его."""
    from . import rules  # здесь: rules — модуль Свода, ему insights не нужен

    role = conn.execute("SELECT role FROM crm.users WHERE id = %s", (user,)).fetchone()
    if not role or role["role"] != "owner":
        raise err("«Только тебе» — только владельцу Дел")
    rows = conn.execute(
        "SELECT source, kind, count(*) AS n, count(*) FILTER (WHERE state = 'useful') AS useful, "
        "count(*) FILTER (WHERE state = 'dismissed') AS dismissed, count(*) FILTER (WHERE state = 'wrong') AS wrong, "
        "count(*) FILTER (WHERE state IN ('seen', 'moved')) AS seen, min(created_at) AS first, max(created_at) AS last "
        "FROM crm.insights WHERE owner_id = %s AND deleted_at IS NULL AND source = ANY (%s) "
        "GROUP BY source, kind ORDER BY source, count(*) DESC, kind", (user, list(RATE_SOURCES))).fetchall()
    starts = {r["source"]: r["first"] for r in conn.execute(
        "SELECT source, min(created_at) AS first FROM crm.insights WHERE owner_id = %s AND source = ANY (%s) "
        "GROUP BY source", (user, list(RATE_SOURCES))).fetchall()}
    now = conn.execute("SELECT now() AS t").fetchone()["t"]
    lens = rules.lens(conn, user)
    kinds, off = [], []
    for r in rows:
        rated = r["useful"] + r["dismissed"] + r["wrong"]
        age = (now - starts[r["source"]]).days
        v = _verdict(rated, r["useful"], r["wrong"], age)
        if v == "off":
            off.append(f"{r['source']}:{r['kind']}")
        kinds.append({
            "source": r["source"], "kind": r["kind"], "n": r["n"], "rated": rated, "useful": r["useful"],
            "dismissed": r["dismissed"], "wrong": r["wrong"], "seen": r["seen"],
            "useful_pct": round(100 * r["useful"] / rated) if rated else None,
            "wrong_pct": round(100 * r["wrong"] / rated) if rated else None,
            "since": starts[r["source"]], "days": age, "last": r["last"],
            "verdict": v, "word": VERDICT_WORD[v], "is_off": rules.is_off(lens, r["source"], r["kind"]),
        })
    return {"kinds": kinds, "suggest_off": off, "lens": lens,
            "rule": {"shadow_days": SHADOW_DAYS, "measure_days": MEASURE_DAYS, "rated_min": RATED_MIN,
                     "wrong_max_pct": round(100 * WRONG_MAX), "useful_min_pct": round(100 * USEFUL_MIN)}}


def register(handlers: dict, views: dict, err, features: list | None = None) -> None:
    handlers["insight.add"] = lambda conn, user, op: op_add(conn, user, op, err)
    handlers["insight.set"] = lambda conn, user, op: op_set(conn, user, op, err)
    handlers["insight.delete"] = lambda conn, user, op: op_delete(conn, user, op, err)
    handlers["insight.rate"] = lambda conn, user, op: op_rate(conn, user, op, err)
    handlers["insight.retire"] = lambda conn, user, op: op_retire(conn, user, op, err)
    views["insights"] = lambda conn, user, **p: view_insights(conn, user, err=err, **p)
    views["lens"] = lambda conn, user, **p: view_lens(conn, user, err=err, **p)
    # «lens» — этап 6 аудита (16.10.2026): у сводки дайджеста since, team, only_you (и «Только тебе» — в crm.insights),
    # оценка insight.rate, уборка прежних пунктов разобранной заново встречи insight.retire и вид lens; «rules» — Свод rules.dela с заводским текстом в виде svod и проверкой записи,
    # выключатель meetings.lens. Службы и веб по флагам знают, что сервер это умеет: старый ответил бы «нет такой операции».
    for f in ("lens", "rules"):
        if features is not None and f not in features:
            features.append(f)
