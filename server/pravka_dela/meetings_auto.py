"""Разборщик встреч: каждой встрече — клиент, тема, участники, рабочая или личная и единое название.

Владелец, 12.10.2026: «по всем транскриптам встреч пройти и правильно их назвать… единообразное название: клиент,
основная тема, кто присутствовал, дата и duration». Дата и длительность — факты службы; клиента, тему, участников
и «рабочая или личная» решает Claude, название собирает код одним правилом (meetings.title_of): модель не
форматирует, поэтому все названия одинаковые.

Как устроено (как разборщик Круга, krug_auto.py):
- Тик службы — раз в минуту (__main__). Пачка в работе — досмотреть; нет — есть ли неразобранные встречи
  (named_at пусто: новая, присланная заново с новым содержанием или «Разобрать заново»). Нет — ни запроса, ни цента.
- Есть — одна пачка Message Batches (−50 %), по запросу на встречу, до BATCH_MAX за раз: весь накопленный архив
  встреч разбирается несколькими пачками подряд. Справочник (клиенты, сделки, люди) и примеры владельца — в общем
  системном блоке с кэшем: он одинаков у всех запросов пачки.
- Ответ ложится в карточку (meetings.apply_names), кроме того, что владелец поправил руками (locked). Встречу
  успели прислать заново с другим содержанием — ответ про старое не кладётся, её разберёт следующая пачка.
- Учится на правках владельца: встречи, где он сам назвал клиента, тему или название, — примерами в запрос.
- Людей встречи не угадывает, когда их уже определила служба встреч (этап 6 аудита, флаг people_final: raw.people_final
  и raw.speakers): строка «ЛЮДИ ВСТРЕЧИ» в запросе, а ответ модели о людях apply_names не читает. Без поля (старая
  служба, архив) — как раньше: участники из разбора, узнанные по голосу — первыми.
- Траты — crm.state llm_cost, вид meetings.
"""

from __future__ import annotations

import json
import logging
import time
from zoneinfo import ZoneInfo

from psycopg.types.json import Jsonb

from . import db, llm, meetings

log = logging.getLogger("dela.meetings")

MODEL = "claude-opus-5-5"
EFFORT = "medium"  # участников и клиента надо узнать по разговору с ослышками — low путал бы людей
STATE_KEY = "meetings_auto"
PROMPT_KEY = "prompt.meetings"
BATCH_MAX = 100          # встреч в пачке
BATCH_MAX_AGE = 3 * 3600  # пачка не кончилась за три часа — отмена, встречи уйдут следующей
MIN_GAP = 300            # секунд между пачками: свежая встреча ждёт не дольше пяти минут плюс сама пачка
CACHE_MIN = 10           # с какой пачки справочник метить для кэша
EXCERPT = 14000          # знаков расшифровки в запрос: начала хватает на клиента, тему и участников
TAIL = 2500              # и конец — там договорённости и прощание по именам
EXAMPLES = 30
MSK = ZoneInfo("Europe/Moscow")
WD = ["понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"]

# Правила — запись Свода prompt.meetings (владелец правит словами, как остальные промпты); здесь — запас без имён:
# репозиторий публичный. С 15.10.2026 (этап 6 аудита, stage6/new-meetings.md §5) — людей встречи не угадывает, если
# служба дала строку «ЛЮДИ ВСТРЕЧИ» (people_final); новый текст в Свод ставит сессия svod.set после выката, а до того
# старый текст Свода безвреден: при people_final ответ о людях apply_names не читает.
RULES = """Ты разбираешь встречи владельца — партнёра ЗФ (финансовый консалтинг: клиенты, сделки, банки, фонды, команда) — и
даёшь каждой единое название и поля. Встречу записала и расшифровала служба встреч: распознавание ошибается в именах
и словах — сначала пойми смысл.

ЧТО ВЕРНУТЬ
sphere — "work", если встреча о работе: клиенты, сделки, банки, фонды, партнёры, команда ЗФ, знакомства ради дела.
  "home" — личная: терапия и психолог, семья и дети, занятия детей, врачи, дом, друзья без дела. Строка "служба
  думает: рабочая/личная" — подсказка, решай по разговору.
client — ровно имя клиента или проекта из СПРАВОЧНИКА (как написано в начале строки), если встреча о нём.
  Встреча с банком или фондом о сделке клиента — это клиент сделки, а не банк. Компания, которую клиент покупает или
  продаёт, его актив и компании его группы — тот же клиент (смотри его сделки и другие имена в справочнике), а не
  новый. Встреча команды ЗФ о конкретном клиенте — этот клиент; о своём (найм, план, процессы) — служебный проект ЗФ
  из справочника, если он есть. Личная — личный проект из справочника, если есть. Нет подходящего — "".
  Имя из разговора или подписи, похожее по звучанию на клиента или компанию из справочника, — скорее всего он:
  распознавание путает имена. Ставь его, но sure false и вопрос владельцу.
label — только когда client пустой: коротко, с кем встреча — компания ("Банк Ромашка"), человек ("Иван Петров"), "ЗФ"
  для своих, "Личное" для личной. Имя, а не описание ("инвест-бутик бывшего главы фонда" — не label: найди имя в
  разговоре, не нашёл — sure false). Когда client есть — "".
deal — имя сделки этого клиента из справочника, если встреча именно о ней; иначе "".
topic — основная тема в 2–7 словах: что решали или о чём договаривались, а не "обсуждение", "встреча", "созвон".
  Без имени клиента и без даты, без двоеточий (двоеточие в названии одно — после клиента), с маленькой буквы
  (имена собственные — с большой), без точки. Например: "фонды и сенситивити модели", "кредит на покупку магазинов",
  "найм финдиректора", "плановая сессия".
people — есть в запросе строка "ЛЮДИ ВСТРЕЧИ" — пустой список: людей встречи уже определили служба встреч (голос,
  Zoom, разбор) и сверка кодом, второй раз их не угадывают; эту строку бери, чтобы понять клиента и label. Строки
  нет (старая встреча, архив Zoom) — все, кто говорил или точно был на встрече, кроме самого владельца: name — как
  назвали в разговоре или подписала служба; person — точное имя из СПРАВОЧНИКА (как в начале строки), только если это
  точно он; иначе "". "Speaker 1", "SPEAKER_02" — не имена; имени нет, а роль ясна ("финдиректор клиента") — name
  ролью, person "". Имя учётки Zoom — не всегда тот, кто говорит ("сейчас Иван отправит" — говорит другой).
  Упомянутый в разговоре — не участник.
sure — true, если клиент и sphere ясны. false — если гадаешь; тогда question — короткий вопрос владельцу
  ("Это Ромашка или Лютик?"), иначе question "".

ПРАВИЛА
— КАК НАЗЫВАЛ ВЛАДЕЛЕЦ сильнее общих правил: похожие встречи называй так же.
— Не выдумывай: нет уверенности в клиенте — client "", label словами и sure false."""

SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": ["sphere", "client", "label", "deal", "topic", "people", "sure", "question"],
    "properties": {
        "sphere": {"type": "string", "enum": ["work", "home"]},
        "client": {"type": "string"},
        "label": {"type": "string"},
        "deal": {"type": "string"},
        "topic": {"type": "string"},
        "people": {"type": "array", "items": {
            "type": "object", "additionalProperties": False, "required": ["name", "person"],
            "properties": {"name": {"type": "string"}, "person": {"type": "string"}}}},
        "sure": {"type": "boolean"},
        "question": {"type": "string"},
    },
}


# ── Что разбирать ──────────────────────────────────────────────────────


def candidates(conn, owner: str, limit: int = BATCH_MAX) -> list[dict]:
    """Неразобранные встречи владельца, свежие первыми: новая встреча важнее архива."""
    return conn.execute(
        "SELECT * FROM crm.meetings WHERE owner_id = %s AND named_at IS NULL AND archived_at IS NULL "
        "ORDER BY started_at DESC LIMIT %s", (owner, limit)).fetchall()


def catalog(conn, owner: str) -> str:
    """Справочник для разбора: клиенты и проекты со сделками, люди с компаниями, кто сам владелец."""
    projects = conn.execute(
        "SELECT p.id, p.name, p.aliases, p.kind, p.sphere, o.name AS org FROM crm.projects p "
        "LEFT JOIN crm.orgs o ON o.id = p.org_id WHERE p.archived_at IS NULL ORDER BY p.kind, p.name").fetchall()
    deals: dict = {}
    for d in conn.execute(
            "SELECT project_id, name FROM crm.deals WHERE stage <> 'archive' OR closed_on > crm.today() - 365 "
            "OR closed_on IS NULL ORDER BY name").fetchall():
        deals.setdefault(d["project_id"], []).append(d["name"])
    kind = {"client": "клиент", "internal": "служебный ЗФ", "personal": "личный"}
    lines = ["СПРАВОЧНИК", "Клиенты и проекты (в начале строки — как писать в client; в скобках — другие имена, "
             "компания; дальше — сделки для deal):"]
    for p in projects:
        other = [*(p["aliases"] or []), *([p["org"]] if p["org"] and p["org"] != p["name"] else [])]
        s = f"— {p['name']}" + (f" ({', '.join(other)})" if other else "") + f" · {kind.get(p['kind'], p['kind'])}"
        if deals.get(p["id"]):
            s += " · сделки: " + "; ".join(deals[p["id"]])
        lines.append(s)
    me = conn.execute(
        "SELECT u.name AS uname, pe.name, pe.short, pe.aliases FROM crm.users u "
        "LEFT JOIN crm.people pe ON pe.id = u.person_id WHERE u.id = %s", (owner,)).fetchone()
    lines.append("\nЛюди (в начале строки — как писать в person; в скобках — короткое и другие имена; дальше — "
                 "компания и роль):")
    for r in conn.execute(
            "SELECT pe.name, pe.short, pe.aliases, pe.role, o.name AS org FROM crm.people pe "
            "LEFT JOIN crm.orgs o ON o.id = pe.org_id WHERE pe.archived_at IS NULL AND pe.merged_into IS NULL "
            "ORDER BY pe.name").fetchall():
        other = [x for x in [r["short"], *(r["aliases"] or [])] if x and x != r["name"]]
        tail = " · ".join(x for x in (r["org"], r["role"]) if x)
        lines.append(f"— {r['name']}" + (f" ({', '.join(other)})" if other else "") + (f" · {tail}" if tail else ""))
    if me:
        who = me["name"] or me["uname"]
        also = [x for x in [me["short"], me["uname"], *(me["aliases"] or [])] if x and x != who]
        lines.append(f"\nВЛАДЕЛЕЦ: {who}" + (f" ({', '.join(dict.fromkeys(also))})" if also else "")
                     + " — он ведёт встречи; в people его не включай.")
    return "\n".join(lines)


def examples(conn, owner: str) -> list[str]:
    """Как владелец называл сам: встречи, где он правил клиента, тему, название или «личная/рабочая»."""
    rows = conn.execute(
        "SELECT m.title, m.source_title, m.sphere, coalesce(p.name, m.label) AS client FROM crm.meetings m "
        "LEFT JOIN crm.projects p ON p.id = m.project_id "
        "WHERE m.owner_id = %s AND cardinality(m.locked) > 0 AND m.title IS NOT NULL "
        "ORDER BY m.updated_at DESC LIMIT %s", (owner, EXAMPLES)).fetchall()
    out = []
    for r in rows:
        s = f"— «{r['title']}»" + (" · личная" if r["sphere"] == "home" else "")
        if r["source_title"]:
            s += f" (служба называла: «{r['source_title']}»)"
        out.append(s)
    return out


def rules(conn, owner: str) -> str:
    row = conn.execute("SELECT body FROM crm.svod WHERE owner_id = %s AND key = %s AND body IS NOT NULL",
                       (owner, PROMPT_KEY)).fetchone()
    return row["body"] if row and (row["body"] or "").strip() else RULES


def system_blocks(rules_text: str, cat: str, learned: list[str], n: int = BATCH_MAX) -> list[dict]:
    text = rules_text + "\n\n" + cat
    if learned:
        text += "\n\nКАК НАЗЫВАЛ ВЛАДЕЛЕЦ (свежие сверху):\n" + "\n".join(learned)
    # Один блок на всю пачку: кэш делится между её запросами. Только у большой пачки: запросы пачки идут
    # параллельно, и пачка из одной-двух свежих встреч кэш только пишет (+25 % к цене справочника вместо
    # скидки; служба встреч, 12.10.2026). А после архива пачки и есть одна-две встречи.
    block = {"type": "text", "text": text}
    if n >= CACHE_MIN:
        block["cache_control"] = {"type": "ephemeral"}
    return [block]


def _excerpt(conn, mid) -> str:
    rows = conn.execute("SELECT n, start_sec, speaker, text FROM crm.meeting_lines WHERE meeting_id = %s ORDER BY n",
                        (mid,)).fetchall()
    out, prev = [], None
    for r in rows:
        who = r["speaker"] or ""
        out.append((f"{who}: " if who and who != prev else "") + r["text"])
        prev = who or prev
    full = "\n".join(out)
    if len(full) <= EXCERPT + TAIL:
        return full
    return full[:EXCERPT] + "\n[…середина пропущена…]\n" + full[-TAIL:]


def people_line(conn, m: dict, final: list[dict]) -> str:
    """«ЛЮДИ ВСТРЕЧИ (определила служба встреч — не угадывай): Иван Петров (Альфа); Глеб? — не подтверждён; юрист
    клиента — имя не прозвучало». Привязанные — именем карточки Дел с компанией (одна правда: не как подписала служба),
    владелец — мимо; неподтверждённые — как их показывает служба («Имя Ф.?»); одна роль — ролью."""
    me = meetings.owner_person(conn, m["owner_id"])
    ids = [x["person_id"] for x in final if x["person_id"] and x["person_id"] != me]
    cards = {str(r["id"]): r for r in conn.execute(
        "SELECT pe.id, pe.name, o.name AS org FROM crm.people pe LEFT JOIN crm.orgs o ON o.id = pe.org_id "
        "WHERE pe.id = ANY(%s)", (ids,)).fetchall()} if ids else {}
    items: list[str] = []
    for x in final:
        side = ", ".join(v for v in (x["role"], x["company"]) if v)
        if x["person_id"]:
            c = cards.get(str(x["person_id"]))
            if c:
                items.append(c["name"] + (f" ({c['org']})" if c["org"] and c["org"] not in c["name"] else ""))
        elif x["name"] or x["show"]:
            shown = x["show"] or (x["name"].rstrip("?") + "?")
            items.append(f"{shown}{f' ({side})' if side else ''} — не подтверждён")
        elif side:
            items.append(f"{side} — имя не прозвучало")
    items = list(dict.fromkeys(items))
    return ("ЛЮДИ ВСТРЕЧИ (определила служба встреч — не угадывай): "
            + ("; ".join(items) if items else "никого, кроме владельца") + ".")


def user_prompt(conn, m: dict) -> str:
    t = m["started_at"].astimezone(MSK)
    head = [f"Встреча {t.strftime('%d.%m.%Y %H:%M')}, {WD[t.weekday()]}",
            f"длительность {m['duration_min']} мин" if m["duration_min"] else None,
            {"zoom": "Zoom", "call": "звонок", "meeting": "очно или запись на телефон"}.get(m["kind"])]
    parts = [", ".join(x for x in head if x) + "."]
    if m["source_title"]:
        parts.append(f"Служба назвала: «{m['source_title']}».")
    if m["hint_project"]:
        parts.append(f"Служба думает, что клиент: {m['hint_project']}.")
    if m["sphere"] in ("work", "home"):
        # Подсказка сферы (stage6/new-meetings.md §5): 11 личных встреч разобраны деловым текстом — сфера рождалась только
        # здесь. Своё слово владельца (замок sphere) — не подсказка, разбор его и так не перепишет.
        word = "рабочая" if m["sphere"] == "work" else "личная"
        parts.append(f"Владелец отметил: {word}." if "sphere" in (m["locked"] or [])
                     else f"Прошлый разбор: {word}." if m["named_by"] else f"Служба думает: {word}.")
    final = meetings.speakers_final(conn, m["id"])
    if final is not None:
        # Одна правда о людях (этап 6 аудита, п. 5 Саши): люди встречи — от службы и сверки кодом, разбор их не угадывает.
        parts.append(people_line(conn, m, final))
    if final is None and m["speakers"]:
        parts.append("Говорящие у службы: " + ", ".join(m["speakers"]) + ".")
    voice = meetings.voice_people(conn, m["id"]) if final is None else []
    if voice:
        names = {str(r["id"]): r["name"] for r in conn.execute(
            "SELECT id, name FROM crm.people WHERE id = ANY(%s)", ([v["id"] for v in voice],)).fetchall()}
        parts.append("Служба узнала по голосу (это точно они, в people — с этим именем из справочника): " + ", ".join(
            f"{v['name'] + ' — ' if v['name'] else ''}{names.get(str(v['id']), '?')}" for v in voice) + ".")
    if m["summary"]:
        parts.append("СВОДКА СЛУЖБЫ:\n" + m["summary"])
    if m["agreements"]:
        parts.append("ДОГОВОРЁННОСТИ:\n" + "\n".join(
            f"— {x if isinstance(x, str) else json.dumps(x, ensure_ascii=False)}" for x in m["agreements"]))
    text = _excerpt(conn, m["id"])
    parts.append("РАСШИФРОВКА:\n" + (text or "(нет)"))
    return "\n\n".join(parts)


def params(system: list[dict], user: str) -> dict:
    return {
        "model": MODEL,
        "max_tokens": 8000,
        "system": system,
        "output_config": {"effort": EFFORT, "format": {"type": "json_schema", "schema": SCHEMA}},
        "messages": [{"role": "user", "content": user}],
    }


def _parse(msg) -> dict:
    if getattr(msg, "stop_reason", None) in ("refusal", "max_tokens"):
        raise RuntimeError(f"разбор встречи оборван ({msg.stop_reason})")
    text = next((b.text for b in msg.content if getattr(b, "type", "") == "text"), "")
    return json.loads(text)


# ── Тик ────────────────────────────────────────────────────────────────


def _state(conn) -> dict:
    row = conn.execute("SELECT value FROM crm.state WHERE key = %s", (STATE_KEY,)).fetchone()
    return dict(row["value"]) if row else {}


def _save(url: str, value: dict) -> None:
    with db.session(url, "system", "svc:meetings-names", "names") as conn:
        conn.execute("INSERT INTO crm.state (key, value) VALUES (%s, %s) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value",
                     (STATE_KEY, Jsonb(value)))


def _collect(url: str, owner: str, client, b: dict) -> dict:
    """Ответы законченной пачки — в карточки. Ошибка одной встречи не роняет остальные."""
    named, failed, stale, usd = 0, 0, 0, 0.0
    tokens, calls = {}, 0  # вся пачка — одной строкой книги трат: токены и число ответов
    sent = b.get("md5") or {}
    for res in client.messages.batches.results(b["id"]):
        mid = res.custom_id
        # Ответ оплачен, даже если класть его некуда (встречу уже назвали или прислали заново) — считаем сразу
        # (аудит трат 14.10.2026: раньше пропущенные ответы шли мимо учёта).
        u = llm.usage_of(res.result.message, MODEL) if res.result.type == "succeeded" else None
        if u:
            usd += llm.cost(u, MODEL, url) * 0.5
            tokens, calls = llm.add_usage(tokens, u), calls + 1
        with db.session(url, owner, "svc:meetings-names", "names") as conn:
            row = conn.execute("SELECT * FROM crm.meetings WHERE id = %s", (mid,)).fetchone()
            if row is None or row["named_at"] is not None:
                continue
            if sent.get(mid) and row["content_md5"] != sent[mid]:
                stale += 1  # прислали заново, пока шла пачка: ответ про старое — не кладём
                continue
            if res.result.type != "succeeded":
                failed += 1
                conn.execute("UPDATE crm.meetings SET named_at = now(), named_by = %s WHERE id = %s",
                             (f"error: {res.result.type}", mid))
                continue
            msg = res.result.message
            try:
                ans = _parse(msg)
            except (RuntimeError, ValueError) as e:
                failed += 1
                conn.execute("UPDATE crm.meetings SET named_at = now(), named_by = %s WHERE id = %s",
                             (f"error: {str(e)[:120]}", mid))
                continue
            try:
                with conn.transaction():
                    meetings.apply_names(conn, row, ans, u.get("model") or MODEL)
                named += 1
            except Exception as e:  # noqa: BLE001  правило базы отвергло ответ — встреча не роняет пачку
                failed += 1
                log.warning("встречи: разбор %s не лёг: %s", row["ref"], e)
                conn.execute("UPDATE crm.meetings SET named_at = now(), named_by = %s WHERE id = %s",
                             (f"error: {str(e)[:120]}", mid))
    llm.account(url, "meetings", usd, tokens.get("model") or MODEL, usage=tokens, batch=True, calls=calls)
    return {"named": named, "failed": failed, "stale": stale, "usd": round(usd, 4)}


def tick(url: str, key: str, proxy: str | None, now: float | None = None, client=None) -> dict | None:
    """Один тик (раз в минуту): досмотреть пачку в работе или отправить новую, если есть что разбирать.

    client — подмена клиента Anthropic в тестах. Возвращает, что сделано, или None — делать было нечего."""
    now = now if now is not None else time.time()
    with db.session(url, "system", via="names") as conn:
        owner = conn.execute("SELECT crm.owner_id() AS id").fetchone()["id"]
        st = _state(conn)
    if not owner or not key:
        return None
    if client is None:
        from . import guard
        client = guard.client(url, key, proxy, "meetings")  # защита от петли (аудит №12): стоит — тик пропускается

    if st.get("batch"):
        b = st["batch"]
        info = client.messages.batches.retrieve(b["id"])
        if info.processing_status != "ended":
            if now - b["at"] < BATCH_MAX_AGE:
                return None
            try:
                client.messages.batches.cancel(b["id"])
            except Exception:  # noqa: BLE001
                log.warning("встречи: пачку %s отменить не вышло", b["id"])
            _save(url, {**st, "batch": None, "last": {"id": b["id"], "error": "застряла — отменена"}})
            log.warning("встречи: пачка %s застряла — отменена, встречи уйдут следующей", b["id"])
            return {"error": True}
        out = _collect(url, owner, client, b)
        _save(url, {**st, "batch": None, "last": {"id": b["id"], "n": b["n"], **out}})
        log.info("встречи: назвал %d, не вышло %d, устарело %d ($%.3f)", out["named"], out["failed"], out["stale"], out["usd"])
        return out

    if now - float(st.get("sent_at") or 0) < MIN_GAP:
        return None
    with db.session(url, owner, via="names") as conn:
        todo = candidates(conn, owner)
        if not todo:
            return None
        system = system_blocks(rules(conn, owner), catalog(conn, owner), examples(conn, owner), len(todo))
        reqs = [{"custom_id": str(m["id"]), "params": params(system, user_prompt(conn, m))} for m in todo]
    b = client.messages.batches.create(requests=reqs)
    _save(url, {**st, "sent_at": now, "batch": {"id": b.id, "at": now, "n": len(reqs),
                                                "md5": {str(m["id"]): m["content_md5"] for m in todo}}})
    log.info("встречи: %d на разбор — пачка %s", len(reqs), b.id)
    return {"sent": len(reqs)}


def rename(url: str, everything: bool = False, refs: list[str] | None = None) -> int:
    """Отправить встречи на разбор заново (команда meetings-rename). Замки владельца остаются: разбор их не тронет.

    everything — все встречи; иначе — номера refs или те, что не вышли (ошибка) и без названия."""
    with db.session(url, "system", "svc:meetings-names", "names") as conn:
        if refs:
            q, p = "ref = ANY(%s)", [list(refs)]
        elif everything:
            q, p = "TRUE", []
        else:
            q, p = "(named_by LIKE 'error%%' OR title IS NULL)", []
        return conn.execute(f"UPDATE crm.meetings SET named_at = NULL, question = NULL WHERE named_at IS NOT NULL AND {q}",
                            p).rowcount


def status(url: str) -> dict:
    """Что с разбором: сколько встреч, сколько ждут, пачка в работе, последний итог."""
    with db.session(url, "system", via="names") as conn:
        st = _state(conn)
        c = conn.execute(
            "SELECT count(*) AS n, count(*) FILTER (WHERE named_at IS NULL AND archived_at IS NULL) AS waiting, "
            "count(*) FILTER (WHERE named_by LIKE 'error%%') AS failed, count(*) FILTER (WHERE question IS NOT NULL) AS asks "
            "FROM crm.meetings").fetchone()
    return {**c, "batch": (st.get("batch") or {}).get("id"), "last": st.get("last")}

