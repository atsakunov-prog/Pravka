"""Встречи в базе (dela_0010, 12.10.2026): приём от службы встреч, правка владельцем, список и карточка.

Владелец, 12.10.2026: «у встречи должно быть единообразное название: клиент, основная тема, кто присутствовал,
дата и duration… каждая встреча разбивается в таблицы… в списке встреч сортировка по клиентам, по времени, по
присутствующим… закинем вообще всё, что возможно… встречи поправить на личные и рабочие».

Название одно на все встречи: «Клиент: тема» (title_of). Дата, длительность и участники — полями рядом, а не
внутри названия: в списке они столбцы, по ним сортируют, а название в 120 знаков не читается ни в списке, ни в
Telegram. Где нужна одна строка целиком (Claude, выгрузка) — line(): «Додо: фонды · Дмитрий, Наташа · 12.10 ·
47 мин». Клиент в названии — имя проекта из справочника, поэтому «ДоДо» и «Додо» не расходятся; без клиента —
компания или человек, «ЗФ» для своих, «Личное» для личной.

Кто что пишет:
- служба встреч — meeting.upsert: факты (когда, сколько, сводка, договорённости, расшифровка) и всё остальное
  «как есть» в сырьё (crm.meeting_raw). Повтор безвреден: та же встреча — по (source, ref). Поменялись сводка или
  расшифровка — встреча снова уходит на разбор;
- разбор (meetings_auto.py) — клиент, сделка, тема, участники, рабочая или личная, название;
- владелец — meeting.set: что он поправил руками, ложится в locked, и разбор это больше не трогает (человек
  сильнее разборщика, как в Круге). «Разобрать заново» — rename.
- переименованный клиент (13.10.2026): клиент из справочника — правилом базы (dela_0012: его встречи берут новое
  имя, старое ложится в другие имена, встречи с его подписью без клиента пристёгиваются); подпись без
  справочника («Вера (клуб)») — meeting.set с everywhere: у всех встреч с той же подписью.

Новых путей HTTP нет — те же /api/ops и /api/view (новый путь пришлось бы ещё вписывать в закрытый список
сайта-посредника, а служба встреч ходит в Дела на 127.0.0.1 мимо него).
"""

from __future__ import annotations

import datetime as dt
import hashlib
import re
import uuid
from typing import Any
from zoneinfo import ZoneInfo

from psycopg.types.json import Jsonb

from . import people

MSK = ZoneInfo("Europe/Moscow")
KINDS = {"meeting", "zoom", "call", "other"}
SPEAKER_NAME_KEYS = ("name", "dela_name", "display", "label", "speaker")
KIND_WORDS = {"встреча": "meeting", "offline": "meeting", "office": "meeting", "звонок": "call", "phone": "call",
              "telegram": "call", "zoom": "zoom", "зум": "zoom", "meet": "zoom", "teams": "zoom"}
SPHERE_WORDS = {"work": "work", "рабочая": "work", "работа": "work", "home": "home", "personal": "home",
                "личная": "home", "личное": "home", "private": "home"}
# Поле карточки — какой замок оно ставит, когда его правит владелец.
LOCK_OF = {"title": "title", "topic": "topic", "label": "project", "project_id": "project", "deal_id": "project",
           "sphere": "sphere", "person_ids": "people", "guests": "people"}
LOCKS = {"title", "topic", "project", "sphere", "people"}
# Поля upsert, которые служба шлёт по имени; всё прочее — в сырьё как есть.
UPSERT_KNOWN = {
    "ref", "source", "url", "started_at", "ended_at", "duration_min", "duration_sec", "kind", "title", "summary",
    "agreements", "speakers", "transcript", "segments", "project", "project_id", "sphere", "raw",
}
# Разбор спрашивает владельца только о свежей встрече (владелец 13.10.2026: «старые уже ушли в прошлое»): про
# мартовский созвон без расшифровки вопрос никому не нужен — у старой встречи остаётся лучшая догадка без вопроса.
QUESTION_DAYS = 7
LINE_MAX = 1500          # знаков в реплике поиска: длинный монолог режется по фразам
TRANSCRIPT_MAX = 4_000_000
SPEAKER = re.compile(r"^\s*(?:\[?((?:\d{1,2}:)?\d{1,2}:\d{2}(?:[.,]\d+)?)\]?\s*[-–—]?\s*)?"
                     r"(?:([^\s:\[\]][^:\n\[\]]{0,38}?)\s*:\s+)?(.*)$")
# Формат WhisperX у службы встреч (docs/meeting-clean-prompt.md): строка «таймкод и имя», реплика — строками ниже.
HEADER = re.compile(r"^\s*\[?((?:\d{1,2}:)?\d{1,2}:\d{2}(?:[.,]\d+)?)\]?\s*[-–—]?\s*([^:\[\]]{0,40}?)\s*:?\s*$")
SORTS = {
    "date": "m.started_at {dir}",
    "client": "lower(coalesce(p.name, m.label, 'яяя')) {dir}, m.started_at DESC",
    "duration": "m.duration_min {dir} NULLS LAST, m.started_at DESC",
    "title": "lower(coalesce(m.title, m.source_title, '')) {dir}, m.started_at DESC",
    "people": "cardinality(m.person_ids) + cardinality(m.guests) {dir}, m.started_at DESC",
}
LIST_MAX = 1000


# ── Название ─────────────────────────────────────────────────────────


def clean_topic(topic: str | None) -> str | None:
    """Тема без точки в конце и без двоеточий: «урок математики: задачи» дало бы «Семья: урок математики: задачи»
    (12.10.2026, на настоящих данных — 10 встреч). Двоеточие в названии одно — после клиента; внутри темы — тире."""
    t = re.sub(r"\s*:\s*", " — ", (topic or "").strip()).strip(" —").rstrip(".").strip()
    return t or None


def title_of(label: str | None, topic: str | None) -> str | None:
    """Единое название встречи: «Клиент: тема». Нет темы — клиент; нет клиента — тема."""
    label, topic = (label or "").strip(), (topic or "").strip()
    if label and topic:
        return f"{label}: {topic}"
    return label or topic or None


def line(m: dict) -> str:
    """Встреча одной строкой: название · участники · дата · длительность."""
    when = m.get("started_at")
    if isinstance(when, str):
        when = dt.datetime.fromisoformat(when)
    bits = [m.get("title") or m.get("source_title") or "Встреча без названия"]
    who = [*(m.get("people") or []), *(m.get("guests") or [])]
    if who:
        bits.append(", ".join(who))
    if when:
        bits.append(when.astimezone(MSK).strftime("%d.%m.%Y %H:%M"))
    if m.get("duration_min"):
        bits.append(f"{m['duration_min']} мин")
    if m.get("sphere") == "home":
        bits.append("личная")
    return " · ".join(bits)


# ── Приём от службы ──────────────────────────────────────────────────


def _when(v: Any, what: str, err) -> dt.datetime | None:
    if v in (None, ""):
        return None
    if isinstance(v, (int, float)):
        return dt.datetime.fromtimestamp(v, dt.timezone.utc)
    try:
        t = dt.datetime.fromisoformat(str(v).strip().replace("Z", "+00:00"))
    except ValueError:
        raise err(f"встреча: {what} — дата ISO («2026-10-12T11:56:00+03:00»)") from None
    # Без пояса — Москва: служба встреч живёт на ПК владельца.
    return t if t.tzinfo else t.replace(tzinfo=MSK)


def _sec(v: Any) -> float | None:
    """Секунды от начала: число или «1:02:03», «02:03», «02:03.5»."""
    if v in (None, ""):
        return None
    if isinstance(v, (int, float)):
        return round(float(v), 1)
    parts = str(v).strip().replace(",", ".").split(":")
    try:
        nums = [float(x) for x in parts]
    except ValueError:
        return None
    s = 0.0
    for x in nums:
        s = s * 60 + x
    return round(s, 1)


def _chunks(text: str) -> list[str]:
    """Длинная реплика — кусками по фразам до LINE_MAX: у поиска строка короткая, сниппет читается."""
    text = text.strip()
    if len(text) <= LINE_MAX:
        return [text] if text else []
    out, cur = [], ""
    for s in re.split(r"(?<=[.!?…])\s+", text):
        while len(s) > LINE_MAX:  # фраза без точек длиннее предела — режем по словам
            cut = s.rfind(" ", 0, LINE_MAX)
            cut = cut if cut > LINE_MAX // 2 else LINE_MAX
            if cur:
                out.append(cur)
                cur = ""
            out.append(s[:cut].strip())
            s = s[cut:].strip()
        if cur and len(cur) + 1 + len(s) > LINE_MAX:
            out.append(cur)
            cur = s
        else:
            cur = f"{cur} {s}".strip()
    if cur:
        out.append(cur)
    return out


def split_lines(transcript: str | None = None, segments: list | None = None) -> list[dict]:
    """Расшифровка — реплики {start_sec, end_sec, speaker, text}.

    segments службы (с кем и когда) сильнее текста. Текст — по строкам; «Саша: …», «[01:02] Дмитрий: …»,
    «00:01:02 — Ирина: …» узнаются; строка без имени — продолжение того же говорящего."""
    out: list[dict] = []
    if segments:
        for s in segments:
            if isinstance(s, str):
                s = {"text": s}
            if not isinstance(s, dict):
                continue
            text = str(s.get("text") or "").strip()
            who = str(s.get("speaker") or s.get("who") or "").strip() or None
            start = _sec(s.get("start_sec", s.get("start")))
            end = _sec(s.get("end_sec", s.get("end")))
            for i, part in enumerate(_chunks(text)):
                out.append({"start_sec": start if i == 0 else None, "end_sec": end, "speaker": who, "text": part})
        return out
    who, pending = None, None
    for raw in (transcript or "").splitlines():
        if not raw.strip():
            continue
        h = HEADER.match(raw)
        if h and not re.search(r"[.!?,;]", h.group(2) or "") and len((h.group(2) or "").split()) <= 4:
            # «[00:01:23] Дмитрий» — заголовок реплики: время и кто; текст — строками ниже.
            pending = _sec(h.group(1))
            who = (h.group(2) or "").strip() or who
            continue
        m = SPEAKER.match(raw)
        stamp, name, text = (m.group(1), m.group(2), m.group(3)) if m else (None, None, raw)
        if name and (len(name.split()) > 4 or re.search(r"[.!?,;]", name)):
            # «Итак, по сделке: …» — не имя, а начало фразы.
            text, name = raw.strip(), None
        if name:
            who = name.strip()
        start = _sec(stamp) if stamp else pending
        pending = None
        for i, part in enumerate(_chunks(text or "")):
            out.append({"start_sec": start if i == 0 else None, "end_sec": None, "speaker": who, "text": part})
    return out


def _md5(summary: str | None, lines: list[dict]) -> str:
    h = hashlib.md5()
    h.update((summary or "").encode())
    for x in lines:
        h.update(b"\n" + (x.get("speaker") or "").encode() + b"\t" + x["text"].encode())
    return h.hexdigest()


def _project_hint(conn, m: dict) -> dict | None:
    """Проект, который служба угадала сама (id или имя), — видимый владельцу, живой, однозначный."""
    if m.get("project_id"):
        return conn.execute("SELECT id, name, sphere FROM crm.projects WHERE id::text = %s", (str(m["project_id"]),)).fetchone()
    if m.get("project"):
        return find_project(conn, str(m["project"]), strict=True)
    return None


def _speaker_name(x: Any) -> str:
    if isinstance(x, dict):
        x = next((x.get(k) for k in SPEAKER_NAME_KEYS if x.get(k)), "")
    return str(x or "").strip()


def voice_people(conn, meeting_id) -> list[dict]:
    """Кого служба встреч узнала по голосу и связала с карточкой Дел: raw.speakers[].person_id (или speakers
    верхнего уровня объектами). Это сильнее догадки по разговору. Слитый дубль ведёт в живую карточку; чужой или
    кривой id — мимо."""
    row = conn.execute("SELECT raw FROM crm.meeting_raw WHERE meeting_id = %s", (meeting_id,)).fetchone()
    raw = (row and row["raw"]) or {}
    found: list[dict] = []
    for x in raw.get("speakers") if isinstance(raw.get("speakers"), list) else []:
        pid = str(x.get("person_id") or "") if isinstance(x, dict) else ""
        if not re.fullmatch(r"[0-9a-fA-F-]{36}", pid):
            continue
        p = conn.execute(
            "SELECT coalesce(m.id, pe.id) AS id FROM crm.people pe LEFT JOIN crm.people m ON m.id = pe.merged_into "
            "WHERE pe.id = %s::uuid", (pid,)).fetchone()
        if p:
            found.append({"id": p["id"], "name": _speaker_name(x)})
    return found


def _kind(v: Any) -> str:
    k = str(v or "meeting").strip().lower()
    return k if k in KINDS else KIND_WORDS.get(k, "other")


def _sphere(v: Any) -> str | None:
    return SPHERE_WORDS.get(str(v or "").strip().lower())


def op_upsert(conn, user, op, err):
    """Встреча от службы: завести или обновить по (source, ref). Разбор и правки владельца не трогаются."""
    m = op.get("meeting")
    if not isinstance(m, dict):
        raise err("встреча: нужен объект meeting")
    ref = str(m.get("ref") or "").strip()
    if not ref:
        raise err("встреча: нужен ref — номер встречи у службы")
    source = str(m.get("source") or "meetings").strip().lower()
    started = _when(m.get("started_at"), "started_at", err)
    if started is None:
        raise err("встреча: нужен started_at — когда началась")
    transcript = m.get("transcript")
    if transcript is not None and not isinstance(transcript, str):
        raise err("встреча: transcript — текст")
    if transcript and len(transcript) > TRANSCRIPT_MAX:
        raise err(f"встреча: расшифровка длиннее {TRANSCRIPT_MAX} знаков")
    segments = m.get("segments")
    if segments is not None and not isinstance(segments, list):
        raise err("встреча: segments — список реплик")
    lines = split_lines(transcript, segments)
    agreements = m.get("agreements") or []
    if isinstance(agreements, str):
        agreements = [x.strip(" -•—") for x in agreements.splitlines() if x.strip(" -•—")]
    if not isinstance(agreements, list):
        raise err("встреча: agreements — список")
    speakers = m.get("speakers") or sorted({x["speaker"] for x in lines if x["speaker"]})
    if not isinstance(speakers, list):
        raise err("встреча: speakers — список имён")

    dur = m.get("duration_min")
    if dur in (None, "") and m.get("duration_sec") not in (None, ""):
        dur = round(float(m["duration_sec"]) / 60)
    ended = _when(m.get("ended_at"), "ended_at", err)
    if dur in (None, "") and ended:
        dur = round((ended - started).total_seconds() / 60)
    if dur in (None, ""):
        ends = [x["end_sec"] or x["start_sec"] for x in lines if (x["end_sec"] or x["start_sec"])]
        dur = round(max(ends) / 60) if ends else None
    try:
        dur = max(0, int(dur)) if dur not in (None, "") else None
    except (TypeError, ValueError):
        raise err("встреча: duration_min — минуты числом") from None

    summary = (m.get("summary") or "").strip() or None
    md5 = _md5(summary, lines)
    facts = {
        "url": (m.get("url") or "").strip() or None, "started_at": started, "duration_min": dur,
        "kind": _kind(m.get("kind")), "source_title": (m.get("title") or "").strip() or None, "summary": summary,
        "agreements": Jsonb([x if isinstance(x, (dict, str)) else str(x) for x in agreements]),
        "speakers": [n for n in (_speaker_name(x) for x in speakers) if n][:50],
        "hint_project": (str(m.get("project") or "") or None),
        "content_md5": md5, "lines_n": len(lines), "chars_n": sum(len(x["text"]) for x in lines),
    }
    raw = dict(m.get("raw") or {}) if isinstance(m.get("raw"), dict) else ({"raw": m["raw"]} if m.get("raw") else {})
    raw.update({k: v for k, v in m.items() if k not in UPSERT_KNOWN})
    if segments:
        raw["segments_n"] = len(segments)

    row = conn.execute("SELECT * FROM crm.meetings WHERE source = %s AND ref = %s", (source, ref)).fetchone()
    hint = _project_hint(conn, m)
    sphere_hint = _sphere(m.get("sphere")) or (hint and ("home" if hint["sphere"] == "home" else "work"))
    if row is None:
        data = {"source": source, "ref": ref, "owner_id": user, **facts}
        if hint:
            data.update(project_id=hint["id"], label=hint["name"])
        if sphere_hint:
            data["sphere"] = sphere_hint
        cols = list(data)
        row = conn.execute(
            f"INSERT INTO crm.meetings ({', '.join(cols)}) VALUES ({', '.join(['%s'] * len(cols))}) RETURNING *",
            [data[c] for c in cols]).fetchone()
        changed = True
    else:
        if row["owner_id"] != user:
            raise err("встреча: чужая встреча")
        changed = row["content_md5"] != md5
        data = dict(facts)
        locked = set(row["locked"] or [])
        if row["named_at"] is None:  # подсказки службы — только пока разбора не было
            if hint and "project" not in locked and not row["project_id"]:
                data.update(project_id=hint["id"], label=hint["name"])
            if sphere_hint and "sphere" not in locked and not row["sphere"]:
                data["sphere"] = sphere_hint
        if changed:
            data["named_at"] = None  # новое содержание — разобрать заново (замки владельца остаются)
        sets = ", ".join(f"{k} = %s" for k in data)
        row = conn.execute(f"UPDATE crm.meetings SET {sets} WHERE id = %s RETURNING *", [*data.values(), row["id"]]).fetchone()
    if changed:
        conn.execute("DELETE FROM crm.meeting_lines WHERE meeting_id = %s", (row["id"],))
        with conn.cursor() as cur:
            cur.executemany(
                "INSERT INTO crm.meeting_lines (meeting_id, n, start_sec, end_sec, speaker, text, owner_id) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s)",
                [(row["id"], i, x["start_sec"], x["end_sec"], x["speaker"], x["text"], user) for i, x in enumerate(lines)])
    conn.execute(
        "INSERT INTO crm.meeting_raw (meeting_id, transcript, raw, owner_id) VALUES (%s, %s, %s, %s) "
        "ON CONFLICT (meeting_id) DO UPDATE SET transcript = coalesce(EXCLUDED.transcript, crm.meeting_raw.transcript), "
        "raw = EXCLUDED.raw, received_at = now()",
        (row["id"], transcript, Jsonb(raw), user))
    return {"meeting": {"id": row["id"], "ref": row["ref"], "rev": row["rev"], "lines": row["lines_n"],
                        "changed": changed, "named": row["named_at"] is not None, "title": row["title"]}}


# ── Справочник: имена в id ───────────────────────────────────────────


def norm(s: Any) -> str:
    return re.sub(r"\s+", " ", str(s or "").replace("ё", "е").replace("Ё", "Е").strip().lower())


def find_project(conn, name: str, strict: bool = False) -> dict | None:
    """Проект по имени или алиасу (видимый, живой). strict — только однозначное, иначе None."""
    n = norm(name)
    if not n:
        return None
    rows = conn.execute("SELECT id, name, aliases, sphere, kind FROM crm.projects WHERE archived_at IS NULL").fetchall()
    hit = [r for r in rows if n in {norm(r["name"]), *map(norm, r["aliases"] or [])}]
    if len(hit) == 1:
        return hit[0]
    if strict:
        return None
    part = [r for r in rows if n in norm(r["name"])]
    return part[0] if len(part) == 1 else None


def find_deal(conn, project_id, name: str) -> dict | None:
    n = norm(name)
    if not n or not project_id:
        return None
    rows = conn.execute("SELECT id, name FROM crm.deals WHERE project_id = %s", (project_id,)).fetchall()
    hit = [r for r in rows if norm(r["name"]) == n] or [r for r in rows if n in norm(r["name"])]
    return hit[0] if len(hit) == 1 else None


def find_person(conn, *names: str) -> dict | None:
    """Человек из справочника по имени из справочника или из разговора — только уверенно (people.who)."""
    for name in names:
        if not (name or "").strip():
            continue
        w = people.who(conn, q=name)
        if w["sure"]:
            return w["best"]
    return None


def owner_person(conn, user: str):
    row = conn.execute("SELECT person_id FROM crm.users WHERE id = %s", (user,)).fetchone()
    return row and row["person_id"]


def apply_names(conn, row: dict, ans: dict, by: str) -> dict:
    """Разбор встречи — в карточку, кроме того, что владелец поправил сам (locked).

    ans: sphere, client (имя проекта из справочника или ""), label, deal, topic, people [{name, person}], sure,
    question. Клиент и люди — только однозначные: неузнанный человек остаётся гостем словами."""
    locked = set(row["locked"] or [])
    data: dict[str, Any] = {"named_at": dt.datetime.now(dt.timezone.utc), "named_by": by}
    sphere = _sphere(ans.get("sphere"))
    if "sphere" not in locked and sphere:
        data["sphere"] = sphere
    label = row["label"]
    if "project" not in locked:
        # Подпись, совпавшая с проектом («ЗФ» — служебный проект), — это и есть проект: иначе в списке клиентов
        # «ЗФ» стоял дважды — проектом и подписью без проекта (12.10.2026, 5 встреч).
        p = find_project(conn, ans.get("client") or "", strict=True) or find_project(conn, ans.get("label") or "", strict=True)
        d = find_deal(conn, p["id"], ans.get("deal") or "") if p else None
        label = p["name"] if p else ((ans.get("label") or "").strip() or None)
        data.update(project_id=p and p["id"], deal_id=d and d["id"], label=label)
    topic = row["topic"]
    if "topic" not in locked:
        topic = clean_topic(ans.get("topic"))
        data["topic"] = topic
    if "people" not in locked:
        me = owner_person(conn, row["owner_id"])
        ids: list = []
        guests: list[str] = []
        voice = voice_people(conn, row["id"])
        for v in voice:  # узнанные по голосу — первыми и без догадок
            if v["id"] != me and v["id"] not in ids:
                ids.append(v["id"])
        heard_by_voice = {norm(v["name"]) for v in voice if v["name"]}
        for x in ans.get("people") or []:
            if not isinstance(x, dict):
                continue
            heard = (x.get("name") or "").strip()
            p = find_person(conn, x.get("person") or "", heard)
            if p:
                if p["id"] != me and p["id"] not in ids:
                    ids.append(p["id"])
            elif heard and norm(heard) not in map(norm, guests) and norm(heard) not in heard_by_voice:
                guests.append(heard)
        data.update(person_ids=ids, guests=guests[:30])
    if "title" not in locked:
        data["title"] = title_of(label, topic)
    fresh = row["started_at"] >= dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=QUESTION_DAYS)
    data["question"] = None if ans.get("sure", True) or not fresh else ((ans.get("question") or "").strip() or None)
    sets = ", ".join(f"{k} = %s" for k in data)
    return conn.execute(f"UPDATE crm.meetings SET {sets} WHERE id = %s RETURNING *", [*data.values(), row["id"]]).fetchone()


# ── Правка владельцем ───────────────────────────────────────────────


def _meeting(conn, op, err) -> dict:
    mid, ref = op.get("id"), op.get("ref")
    if mid:
        try:
            uuid.UUID(str(mid))
        except ValueError:
            raise err("встреча: кривой id") from None
        row = conn.execute("SELECT * FROM crm.meetings WHERE id = %s", (str(mid),)).fetchone()
    elif ref:
        row = conn.execute("SELECT * FROM crm.meetings WHERE source = %s AND ref = %s",
                           (op.get("source") or "meetings", str(ref))).fetchone()
    else:
        raise err("встреча: нужен id или ref")
    if not row:
        raise err("встреча: нет такой или нет прав")
    return row


def op_set(conn, user, op, err):
    """Владелец правит карточку: что поправил — то под замком от разбора.

    set: title, topic, label, project (имя) или project_id, deal (имя) или deal_id, sphere, people (имена) или
    person_ids, guests, archived (true/false). unlock — снять замки (отдать поле разбору), rename — разобрать
    заново (с unlock: всё, что не под замком). everywhere — у встречи без клиента из справочника: тот же клиент
    или подпись — всем встречам с её подписью (_everywhere)."""
    row = _meeting(conn, op, err)
    before = row
    s = dict(op.get("set") or {})
    extra = set(s) - {"title", "topic", "label", "project", "project_id", "deal", "deal_id", "sphere", "people",
                      "person_ids", "guests", "archived"}
    if extra:
        raise err(f"встреча: нет таких полей — {', '.join(sorted(extra))}")
    data: dict[str, Any] = {}
    implied = None  # клиент назван, а рабочая или личная — ещё нет: берётся у проекта, без замка
    if "project" in s or "project_id" in s:
        v = s.pop("project", None) if "project" in s else s.pop("project_id")
        if v in (None, "", "-"):
            data.update(project_id=None, deal_id=None, label=None)  # «это не Додо»: и из названия уходит
        else:
            p = (conn.execute("SELECT id, name, sphere FROM crm.projects WHERE id::text = %s", (str(v),)).fetchone()
                 or find_project(conn, str(v)))
            if not p:
                raise err(f"встреча: проект «{v}» не определился")
            data.update(project_id=p["id"], label=p["name"])
            implied = "home" if p["sphere"] == "home" else "work"
            if p["id"] != row["project_id"]:
                data["deal_id"] = None
    if "deal" in s or "deal_id" in s:
        v = s.pop("deal", None) if "deal" in s else s.pop("deal_id")
        pid = data.get("project_id", row["project_id"])
        if v in (None, "", "-"):
            data["deal_id"] = None
        else:
            d = (conn.execute("SELECT id FROM crm.deals WHERE id::text = %s AND project_id = %s", (str(v), pid)).fetchone()
                 if pid else None) or find_deal(conn, pid, str(v))
            if not d:
                raise err(f"встреча: сделка «{v}» не определилась у этого клиента")
            data["deal_id"] = d["id"]
    if "people" in s or "person_ids" in s:
        if "people" in s:
            ids = []
            for name in s.pop("people") or []:
                p = find_person(conn, str(name))
                if not p:
                    w = people.who(conn, q=str(name))
                    cands = ", ".join(c["name"] for c in w["candidates"][:5]) or "нет похожих"
                    raise err(f"встреча: человек «{name}» не определился: {cands}")
                ids.append(p["id"])
        else:
            ids = [str(x) for x in s.pop("person_ids") or []]
            seen = {str(r["id"]) for r in conn.execute(
                "SELECT id FROM crm.people WHERE id::text = ANY(%s)", (ids,)).fetchall()}
            if set(ids) - seen:
                raise err("встреча: таких людей в справочнике нет или они не видны")
        data["person_ids"] = ids
    if "guests" in s:
        data["guests"] = [str(x).strip() for x in s.pop("guests") or [] if str(x).strip()][:30]
    if "sphere" in s:
        v = _sphere(s.pop("sphere"))
        if not v:
            raise err("встреча: sphere — work (рабочая) или home (личная)")
        data["sphere"] = v
    for k in ("title", "topic", "label"):
        if k in s:
            data[k] = clean_topic(s.pop(k)) if k == "topic" else (str(s.pop(k) or "").strip() or None)
    archived = s.pop("archived", None)
    if archived is not None:
        data["archived_at"] = dt.datetime.now(dt.timezone.utc) if archived else None

    locked = set(row["locked"] or [])
    for k, v in data.items():
        if k in LOCK_OF and not _same(row.get(k), v):
            locked.add(LOCK_OF[k])
    unlock = {str(x) for x in op.get("unlock") or []}
    if unlock - LOCKS:
        raise err(f"встреча: нет таких замков — {', '.join(sorted(unlock - LOCKS))}")
    locked -= unlock
    if implied and "sphere" not in data and not row["sphere"]:
        data["sphere"] = implied
    if "title" not in data and "title" not in locked and {"topic", "label", "project_id"} & set(data):
        data["title"] = title_of(data.get("label", row["label"]), data.get("topic", row["topic"]))
    if "title" in data and data["title"] is None:  # «стереть название» — вернуть его правилу
        locked.discard("title")
        data["title"] = title_of(data.get("label", row["label"]), data.get("topic", row["topic"]))
    data["locked"] = sorted(locked)
    if op.get("rename"):
        data.update(named_at=None, question=None)
    elif "sphere" in data or "project_id" in data:
        data["question"] = None  # владелец ответил на вопрос разбора делом
    if op.get("everywhere") and before["project_id"] and {"project_id", "label"} & set(data):
        raise err("встреча: у неё клиент из справочника — переименуй самого клиента, его встречи переименуются сами")
    sets = ", ".join(f"{k} = %s" for k in data)
    row = conn.execute(f"UPDATE crm.meetings SET {sets} WHERE id = %s RETURNING *", [*data.values(), row["id"]]).fetchone()
    also = _everywhere(conn, before, row) if op.get("everywhere") else 0
    return {"meeting": _card(conn, row, with_lines=False), "also": also}


def retitle(title: str | None, old: str | None, new: str | None) -> str | None:
    """Название, написанное владельцем: «Старое: …» → «Новое: …», остальное как есть (как crm.meeting_retitle)."""
    if not title or not norm(old) or norm(title[:len(old)]) != norm(old) or title[len(old):len(old) + 1] != ":":
        return title
    return (new or "").strip() + title[len(old):] if (new or "").strip() else title[len(old) + 1:].strip() or None


def _everywhere(conn, before: dict, after: dict) -> int:
    """Клиент без справочника назван по-новому у одной встречи — так же у всех встреч с той же подписью.

    Владелец, 13.10.2026: «переименую клиента — чтобы и в истории встреч переименовалось». Клиент из справочника
    переименовывается сам (dela_0012: его встречи следом). А «Вера (клуб)» или «Сигма Пэй» разбор пишет подписью
    (label), пока клиента нет в справочнике, — у каждой встречи своей копией. Здесь подпись или клиент, выбранные
    владельцем у одной встречи, ложатся на все встречи с прежней подписью, под замок (решил владелец), а прежняя
    подпись становится другим именем выбранного клиента: следующую такую встречу разбор узнает сам."""
    old = before["label"]
    if before["project_id"] or not norm(old) or (after["project_id"] is None and norm(after["label"]) == norm(old)):
        return 0
    rows = conn.execute(
        "SELECT * FROM crm.meetings WHERE project_id IS NULL AND id <> %s AND crm.norm(label) = crm.norm(%s)",
        (after["id"], old)).fetchall()
    for r in rows:
        locked = set(r["locked"] or []) | {"project"}
        title = retitle(r["title"], old, after["label"]) if "title" in locked else title_of(after["label"], r["topic"])
        conn.execute("UPDATE crm.meetings SET project_id = %s, label = %s, title = %s, locked = %s, question = NULL "
                     "WHERE id = %s", (after["project_id"], after["label"], title, sorted(locked), r["id"]))
    if after["project_id"]:
        conn.execute(
            "UPDATE crm.projects SET aliases = array_append(aliases, %s) WHERE id = %s AND crm.project_named(%s) IS NULL",
            (old, after["project_id"], old))
    return len(rows)


def _same(a, b) -> bool:
    norm_ = (lambda v: [str(x) for x in v] if isinstance(v, (list, tuple)) else (str(v) if v is not None else None))
    return norm_(a) == norm_(b) or (a in (None, "", []) and b in (None, "", []))


# ── Виды ─────────────────────────────────────────────────────────────


def _names(conn, ids) -> dict:
    ids = list({str(x) for x in ids})
    if not ids:
        return {}
    rows = conn.execute("SELECT id, name, short FROM crm.people WHERE id::text = ANY(%s)", (ids,)).fetchall()
    return {str(r["id"]): r for r in rows}


def _item(r: dict, names: dict) -> dict:
    ppl = [names.get(str(x)) for x in r["person_ids"] or []]
    out = {
        "id": r["id"], "ref": r["ref"], "url": r["url"], "started_at": r["started_at"],
        "day": r["started_at"].astimezone(MSK).date(), "duration_min": r["duration_min"], "kind": r["kind"],
        "sphere": r["sphere"], "title": r["title"], "source_title": r["source_title"], "topic": r["topic"],
        "label": r["label"], "client": r.get("project_name") or r["label"], "project_id": r["project_id"],
        "deal": r.get("deal_name"), "deal_id": r["deal_id"],
        "people": [p["short"] or p["name"] for p in ppl if p], "people_full": [p["name"] for p in ppl if p],
        "person_ids": r["person_ids"], "guests": r["guests"], "summary": r["summary"], "question": r["question"],
        "named": r["named_at"] is not None, "locked": r["locked"], "lines_n": r["lines_n"],
        "archived": r["archived_at"] is not None,
    }
    out["line"] = line(out)
    return out


def view_meetings(conn, user, sphere=None, q=None, project_id=None, person_id=None, since=None, until=None,
                  sort="date", dir=None, archived=None, unnamed=None, limit=None, **_):
    """Список встреч: фильтры (рабочие и личные, клиент, человек, даты, поиск по названию, сводке и расшифровке),
    сортировка и разрезы для веба — клиенты и люди со счётом встреч. Группирует веб сам: встреч сотни, не тысячи."""
    where, params = ["TRUE"], []
    if str(archived or "") not in ("1", "true", "all"):
        where.append("m.archived_at IS NULL")
    if sphere in ("work", "home"):
        where.append("m.sphere = %s")
        params.append(sphere)
    elif sphere == "none":
        where.append("m.sphere IS NULL")
    if project_id:
        if project_id == "none":
            where.append("m.project_id IS NULL")
        else:
            where.append("m.project_id::text = %s")
            params.append(str(project_id))
    if person_id:
        where.append("%s::uuid = ANY(m.person_ids)")
        params.append(str(person_id))
    if since:
        where.append("(m.started_at AT TIME ZONE 'Europe/Moscow')::date >= %s::date")
        params.append(str(since)[:10])
    if until:
        where.append("(m.started_at AT TIME ZONE 'Europe/Moscow')::date <= %s::date")
        params.append(str(until)[:10])
    if str(unnamed or "") in ("1", "true"):
        where.append("(m.named_at IS NULL OR (m.question IS NOT NULL AND m.started_at >= now() - make_interval(days => %s)))")
        params.append(QUESTION_DAYS)
    hits: dict = {}
    if q and str(q).strip():
        q = str(q).strip()
        hits = _search(conn, q)
        where.append("(m.search @@ websearch_to_tsquery('russian', %s) OR m.id = ANY(%s::uuid[]) "
                     "OR concat_ws(' ', m.title, m.label, m.source_title, m.summary, array_to_string(m.guests, ' '), "
                     "array_to_string(m.speakers, ' ')) "
                     "ILIKE '%%' || %s || '%%')")
        params += [q, list(hits), q]
    d = "ASC" if str(dir or "").lower() == "asc" else "DESC"
    order = SORTS.get(sort or "date", SORTS["date"]).format(dir=d)
    lim = min(int(limit or LIST_MAX), LIST_MAX)
    rows = conn.execute(
        "SELECT m.*, p.name AS project_name, dl.name AS deal_name FROM crm.meetings m "
        "LEFT JOIN crm.projects p ON p.id = m.project_id LEFT JOIN crm.deals dl ON dl.id = m.deal_id "
        f"WHERE {' AND '.join(where)} ORDER BY {order} LIMIT {lim}", params).fetchall()
    names = _names(conn, [x for r in rows for x in r["person_ids"] or []])
    items = [_item(r, names) for r in rows]
    for it in items:
        h = hits.get(str(it["id"]))
        if h:
            it["hits"], it["hits_n"] = h["lines"], h["n"]
    if q and sort == "relevance":  # сначала, где про это говорили больше всего
        items.sort(key=lambda it: -(it.get("hits_n") or 0))
    facets = conn.execute(
        "SELECT count(*) AS all_, count(*) FILTER (WHERE sphere = 'work') AS work, "
        "count(*) FILTER (WHERE sphere = 'home') AS home, count(*) FILTER (WHERE sphere IS NULL) AS none, "
        "count(*) FILTER (WHERE named_at IS NULL) AS unnamed, "
        "count(*) FILTER (WHERE question IS NOT NULL AND started_at >= now() - make_interval(days => %s)) AS asks, "
        "coalesce(sum(duration_min), 0) AS minutes FROM crm.meetings WHERE archived_at IS NULL", (QUESTION_DAYS,)).fetchone()
    clients = conn.execute(
        "SELECT m.project_id AS id, coalesce(p.name, m.label, 'Без клиента') AS name, count(*) AS n, "
        "max(m.started_at) AS last FROM crm.meetings m LEFT JOIN crm.projects p ON p.id = m.project_id "
        "WHERE m.archived_at IS NULL GROUP BY 1, 2 ORDER BY n DESC, name").fetchall()
    ppl = conn.execute(
        "SELECT u.pid AS id, count(*) AS n, max(m.started_at) AS last FROM crm.meetings m, unnest(m.person_ids) AS u(pid) "
        "WHERE m.archived_at IS NULL GROUP BY 1 ORDER BY n DESC LIMIT 200").fetchall()
    pn = _names(conn, [r["id"] for r in ppl])
    return {
        "items": items,
        "total": facets["all_"],
        "counts": {"all": facets["all_"], "work": facets["work"], "home": facets["home"], "none": facets["none"],
                   "unnamed": facets["unnamed"], "asks": facets["asks"], "minutes": facets["minutes"]},
        "clients": clients,
        "people": [{"id": r["id"], "name": pn[str(r["id"])]["name"], "short": pn[str(r["id"])]["short"],
                    "n": r["n"], "last": r["last"]} for r in ppl if str(r["id"]) in pn],
    }


def _search(conn, q: str, per: int = 3, top: int = 200) -> dict:
    """Реплики расшифровок по словоформам или подстроке (имена стеммер режет по-разному): {id встречи: n, lines}."""
    rows = conn.execute(
        "WITH q AS (SELECT websearch_to_tsquery('russian', %s) AS ru), hit AS ("
        "  SELECT l.meeting_id, l.n, l.start_sec, l.speaker, l.text, ts_rank(l.tsv, q.ru) AS rank,"
        "         count(*) OVER (PARTITION BY l.meeting_id) AS total,"
        "         row_number() OVER (PARTITION BY l.meeting_id ORDER BY ts_rank(l.tsv, q.ru) DESC, l.n) AS k"
        "  FROM crm.meeting_lines l, q WHERE l.tsv @@ q.ru OR l.text ILIKE '%%' || %s || '%%') "
        "SELECT h.*, ts_headline('russian', h.text, q.ru, 'StartSel=«, StopSel=», MaxWords=30, MinWords=12') AS snip "
        "FROM hit h, q WHERE h.k <= %s ORDER BY h.total DESC, h.meeting_id, h.k LIMIT %s",
        (q, q, per, top * per)).fetchall()
    out: dict = {}
    for r in rows:
        d = out.setdefault(str(r["meeting_id"]), {"n": r["total"], "lines": []})
        d["lines"].append({"n": r["n"], "start_sec": r["start_sec"], "speaker": r["speaker"], "text": r["snip"]})
    return out


def _card(conn, r: dict, with_lines: bool = True, around: int | None = None, raw: bool = False) -> dict:
    full = conn.execute(
        "SELECT m.*, p.name AS project_name, dl.name AS deal_name FROM crm.meetings m "
        "LEFT JOIN crm.projects p ON p.id = m.project_id LEFT JOIN crm.deals dl ON dl.id = m.deal_id WHERE m.id = %s",
        (r["id"],)).fetchone()
    names = _names(conn, full["person_ids"] or [])
    out = _item(full, names)
    out.update(agreements=full["agreements"], speakers=full["speakers"], named_by=full["named_by"],
               named_at=full["named_at"], hint_project=full["hint_project"], rev=full["rev"],
               people_cards=[{"id": str(x), "name": names[str(x)]["name"], "short": names[str(x)]["short"]}
                             for x in full["person_ids"] or [] if str(x) in names])
    if with_lines:
        lo, hi = (around - 15, around + 15) if around is not None else (0, 10 ** 9)
        out["lines"] = conn.execute(
            "SELECT n, start_sec, end_sec, speaker, text FROM crm.meeting_lines WHERE meeting_id = %s "
            "AND n BETWEEN %s AND %s ORDER BY n", (full["id"], lo, hi)).fetchall()
    # Что уже связано с этой встречей по её номеру: сводка в хронологии клиента и дела из её разбора.
    out["timeline"] = conn.execute(
        "SELECT i.id, i.at, i.kind, i.summary, i.next_step, p.name AS project FROM crm.interactions i "
        "LEFT JOIN crm.projects p ON p.id = i.project_id "
        "WHERE i.deleted_at IS NULL AND crm.meeting_ref(i.source_ref) = %s ORDER BY i.at", (full["ref"],)).fetchall()
    out["tasks"] = conn.execute(
        "SELECT id, num, title, status, project_name, person_short, due_date FROM tasks.v_tasks "
        "WHERE crm.meeting_ref(source_ref) = %s ORDER BY num", (full["ref"],)).fetchall()
    # Клиента нет в справочнике — сколько ещё встреч с той же подписью: веб спросит «поменять у всех?» (everywhere).
    out["same_label"] = 0 if full["project_id"] or not norm(full["label"]) else conn.execute(
        "SELECT count(*) AS n FROM crm.meetings WHERE project_id IS NULL AND id <> %s AND crm.norm(label) = crm.norm(%s)",
        (full["id"], full["label"])).fetchone()["n"]
    if raw:
        rr = conn.execute("SELECT raw, length(transcript) AS chars, received_at FROM crm.meeting_raw WHERE meeting_id = %s",
                          (full["id"],)).fetchone()
        out["raw"] = rr
    return out


def view_meeting(conn, user, id=None, ref=None, around=None, lines="all", raw=None, err=Exception, **_):
    """Карточка встречи: всё из списка, договорённости, говорящие, расшифровка (целиком или окно ±15 реплик
    вокруг around), что связано — хронология клиента и дела из разбора. raw — что прислала служба как есть."""
    row = _meeting(conn, {"id": id, "ref": ref}, err)
    return _card(conn, row, with_lines=lines != "none", around=int(around) if around not in (None, "") else None,
                 raw=str(raw or "") in ("1", "true"))


def register(handlers: dict, views: dict, err) -> None:
    handlers["meeting.upsert"] = lambda conn, user, op: op_upsert(conn, user, op, err)
    handlers["meeting.set"] = lambda conn, user, op: op_set(conn, user, op, err)
    views["meetings"] = view_meetings
    views["meeting"] = lambda conn, user, **p: view_meeting(conn, user, err=err, **p)
