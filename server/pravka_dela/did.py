"""«Позвонил X» — сказал, значит сделано (15.10.2026; шаг 5 петли этапа 4, ядро взял шаг 0 «Захват»).

Владелец, ответ 3а: «„Позвонил X“, а у X висит „позвонить“ — закрыть само и сказать „закрыл: позвонить X“, „Вернуть“ 7
дней; похожих обещаний два и больше — спросить вечером». Фраза Саши — его ответ: исключение из правила этапа 3
(«Отбить» без ответа само не закрывается) — он сам сказал, что сделал.

Одна операция task.did — одно сделанное дело-основа (поля как у task.create, статус всегда done) и фраза, как сказана:
- «сделал я» (ball не waiting) ищет обещания этого человека в «Отбить», повестке и напоминаниях; «прислали мне» (ball
  waiting: «Иван прислал сверку») — в «Мониторить»;
- кандидат — своё открытое дело (не цель дерева) того же человека, заведённое за DID_DAYS дней, с той же основой глагола
  (семьи — VERB_FAMILIES: «позвонил» = «перезвонить» = «созвониться»); «Запустить» с той же основой — «похожее»: его само
  не закрываем (Саша сказал «закрыть само» про обещание), только спрашиваем;
- точное одно — закрыть само (предложение close «закрыто само», комментарий «Сказал · дд.мм: …», в вебе «Сделано само» с
  «Вернуть»), нового дела нет; точных два и больше или только похожие — дело-основа заводится сделанным, на каждого
  кандидата (до трёх, свежие первыми) — вопрос «Это закрывает «X»?»; ни одного — дело-основа сделанным;
- касание — одна запись хронологии человека (и его клиента; сделку ставит crm.touch_deal), семья — мимо (одна правда со
  звонками и дайджестом — digest_crm.load), звонок уже лёг за CALL_TWIN_MIN минут — второй записи нет.
Без человека — без сравнения, кроме напоминаний: «позвонил маме» гасит «напомни позвонить маме» по тем же словам.

store импортирует этот модуль — поэтому store берётся внутри функций, а OpError приходит параметром (как people).
"""

from __future__ import annotations

import datetime as dt
import re
import uuid

from psycopg.types.json import Jsonb

DID_DAYS = 30
CALL_TWIN_MIN = 60
ASK_MAX = 3
SAID_PREFIX = "said:"
# Основа глагола — первые 5 букв (как TaskMatcher телефона: «подготовил» = «подготовить»); семьи — одно действие разными
# глаголами. Порядок не важен: основы в семьях не пересекаются.
VERB_FAMILIES = {
    "call": ("позво", "перез", "созво", "звони", "набра"),
    "send": ("отпра", "присл", "высла", "скину", "перес", "перед", "перев"),
    "write": ("напис", "ответ"),
    "pay": ("оплат", "запла"),
    "meet": ("встре",),
}
SKIP_FIRST = {"я", "уже", "сегодня", "наконец", "еще", "ещё", "только", "вот", "сделано", "сделал", "сделала"}
TOUCH_KIND = {"call": "call", "meet": "meeting", "send": "email", "write": "email"}


def _norm(s) -> str:
    return re.sub(r"\s+", " ", str(s or "").replace("ё", "е").replace("Ё", "Е").lower()).strip()


def _words(s) -> list[str]:
    return [w for w in re.split(r"[^0-9a-zа-я-]+", _norm(s)) if w]


def body(title: str) -> list[str]:
    """Слова названия без «Кто:» и без вводных в начале: «Иван: прислать сверку» → [прислать, сверку]."""
    s = re.sub(r"^[^:]{1,40}:\s*", "", str(title or ""))
    ws = _words(s)
    while ws and ws[0] in SKIP_FIRST:
        ws = ws[1:]
    return ws


def verb(title: str) -> str:
    """Основа глагола дела: первые 5 букв первого слова (без «Кто:» и вводных)."""
    ws = body(title)
    return ws[0][:5] if ws else ""


def family(stem: str) -> str:
    for name, stems in VERB_FAMILIES.items():
        if stem in stems:
            return name
    return stem


def _rest_key(title: str) -> tuple[str, ...]:
    """Слова после глагола основой (для сравнения без человека: «позвонил маме» ~ «Позвонить маме»)."""
    from . import people

    return tuple(people.stem(w) for w in body(title)[1:] if len(w) > 1)


# Служебные слова — не предмет: «позвонил Ольге про договор» и «Позвонить Ольге про отпуск» не совпадают по «про».
_NOT_SUBJECT = frozenset("""про для при без над под через после перед около насчет насчёт это его ему ей её ее им их мне
меня нам нас вам вас тебе тебя уже еще ещё тоже также очень""".split())


def _subject(title: str, names: set[str]) -> set[str]:
    """Предмет обещания или сказанного — основы слов после глагола без имени человека и служебных слов."""
    from . import people

    return {people.stem(w) for w in body(title)[1:] if len(w) >= 3 and w not in _NOT_SUBJECT} - names


def _name_keys(conn, person_id) -> set[str]:
    """Основы слов имени человека — их во «что сделано» не считаем предметом («позвонил Ольге» — без предмета)."""
    from . import people

    if not person_id:
        return set()
    p = conn.execute("SELECT name, short, aliases FROM crm.people WHERE id = %s", (str(person_id),)).fetchone()
    if not p:
        return set()
    return {people.stem(w) for n in [p["name"], p["short"] or "", *(p["aliases"] or [])] for w in people.words(n)}


def candidates(conn, user: str, person_id, title: str, waiting: bool) -> tuple[list[dict], list[dict]]:
    """(точные, похожие) — открытые свои дела того же человека и той же семьи глагола за DID_DAYS дней, свежие первыми."""
    fam = family(verb(title))
    if not fam:
        return [], []
    if person_id:
        rows = conn.execute(
            "SELECT id, num, title, ball, owed, kind, person_id, created_at FROM tasks.v_tasks "
            "WHERE owner_id = %s AND status = 'open' AND NOT goal AND person_id = %s "
            "AND created_at > now() - make_interval(days => %s) ORDER BY created_at DESC",
            (user, str(person_id), DID_DAYS)).fetchall()
    else:
        # Без человека — только напоминания и обещания без человека теми же словами («позвонить маме»).
        rows = conn.execute(
            "SELECT id, num, title, ball, owed, kind, person_id, created_at FROM tasks.v_tasks "
            "WHERE owner_id = %s AND status = 'open' AND NOT goal AND person_id IS NULL "
            "AND (kind = 'remind' OR owed OR ball = 'agenda') "
            "AND created_at > now() - make_interval(days => %s) ORDER BY created_at DESC",
            (user, DID_DAYS)).fetchall()
        key = _rest_key(title)
        rows = [r for r in rows if key and _rest_key(r["title"]) == key]
    rows = [r for r in rows if family(verb(r["title"])) == fam]
    # Сказан предмет («Иван прислал сверку») — из обещаний того же глагола берём те, где он тот же: «прислать сверку», а не
    # «прислать модель». Совпавших нет — точные только обещания без своего предмета («Позвонить Ольге» закрывает и
    # «позвонил Ольге по договору»); обещание с другим предметом — не точное, а похожее: вопрос «Это закрывает…?», само
    # не закрываем (проверка 16.10.2026: «Иван прислал модель» при одном «Иван: прислать сверку» закрывало сверку).
    names = _name_keys(conn, person_id)
    said = _subject(title, names)
    other: list[dict] = []
    if said:
        subj = {str(r["id"]): _subject(r["title"], names) for r in rows}
        same = [r for r in rows if subj[str(r["id"])] & said]
        if same:
            rows = same
        else:
            other = [r for r in rows if subj[str(r["id"])]]
            rows = [r for r in rows if not subj[str(r["id"])]]
    if waiting:
        exact = [r for r in rows if r["ball"] == "waiting"]
        like = [r for r in other if r["ball"] == "waiting"]
    else:
        exact = [r for r in rows if r["ball"] != "waiting" and (r["owed"] or r["ball"] == "agenda" or r["kind"] == "remind")]
        like = [r for r in rows if r["ball"] == "mine" and not r["owed"] and r["kind"] != "remind"]
        like += [r for r in other if r["ball"] != "waiting"]
    return exact, like


def touch_kind(title: str, said: str) -> str:
    s = _norm(said) + " " + _norm(title)
    if re.search(r"телеграм|телеграмм|\bтг\b|telegram", s):
        return "telegram"
    if re.search(r"вотсап|ватсап|whatsapp|вацап", s):
        return "whatsapp"
    return TOUCH_KIND.get(family(verb(title)), "other")


def _touch(conn, user: str, task: dict, person_id, said: str, at, error) -> dict:
    """Касание у человека: одна запись хронологии. Семья — мимо; звонок, уже записанный за CALL_TWIN_MIN минут, — его."""
    from . import digest_crm, store

    out: dict = {"touch": None, "touch_existing": None, "skip": None}
    if not person_id:
        return out
    ctx = digest_crm.load(conn, user)
    pid = str(person_id)
    if pid in ctx["family"]:
        out["skip"] = "семья"
        return out
    if pid == ctx["me"]:
        return out
    kind = touch_kind(task["title"], said)
    when = at or dt.datetime.now(dt.timezone.utc)
    if kind == "call":
        twin = conn.execute(
            "SELECT id FROM crm.interactions WHERE deleted_at IS NULL AND kind = 'call' AND %s::uuid = ANY (person_ids) "
            "AND at BETWEEN %s::timestamptz - make_interval(mins => %s) AND %s::timestamptz + make_interval(mins => %s) "
            "LIMIT 1", (pid, when, CALL_TWIN_MIN, when, CALL_TWIN_MIN)).fetchone()
        if twin:
            out["touch_existing"] = twin["id"]
            return out
    project = None
    if task.get("project_id") and task.get("project_kind") == "client":
        project = str(task["project_id"])
    else:
        mine = digest_crm._person_clients(ctx, pid)
        project = mine[0] if len(mine) == 1 else None
    # В хронологии — как сказано («Позвонил Ольге»), а не название закрытого обещания («Позвонить Ольге»): это факт.
    said_ = " ".join(str(said or "").split())
    summary = (said_[:1].upper() + said_[1:]) if said_ else task["title"]
    data = {"at": when.isoformat() if isinstance(when, dt.datetime) else str(when), "kind": kind,
            "summary": summary[:2000], "person_ids": [pid], "source": "raznoska", "source_ref": f"did:{task['id']}"}
    if project:
        data["project_id"] = project
    row = store.op_interaction_add(conn, user, {"data": data})["row"]
    out["touch"] = row["id"]
    out["touch_kind"] = kind
    return out


def _gen(conn, person_id) -> str:
    """«у Ивана» — короткое имя человека в родительном (для строки)."""
    from . import capture

    if not person_id:
        return ""
    p = conn.execute("SELECT name, short FROM crm.people WHERE id = %s", (str(person_id),)).fetchone()
    return capture.genitive(p["short"] or p["name"]) if p else ""


TOUCH_WORD = {"call": "звонок", "meeting": "встреча"}


def line(res: dict, gen: str = "") -> str:
    """Одна строка для всех клиентов: «закрыл «Позвонить Ивану» · звонок у Ивана». Вопрос о похожих встаёт в «Подскажи»
    сразу — так и сказано (проверка 16.10.2026: «спрошу вечером» обещало заход, которого до шага 2 нет)."""
    word = TOUCH_WORD.get(res.get("touch_kind") or "", "касание")
    at = f" · {word} у {gen}" if gen else f" · {word} записано"
    if res.get("closed_title"):
        out = f"закрыл «{res['closed_title']}»"
        if res.get("asked"):  # сквозной тест 15.10.2026: вопросы о похожих уходили в «Подскажи», а строка о них молчала
            n = len(res["asked"])
            out += " · ещё " + ("одно похожее — вопрос" if n == 1 else f"{n} похожих — вопросы") + " в «Подскажи»"
    elif res.get("asked"):
        n = len(res["asked"])
        at_ = f" у {gen}" if gen else ""
        out = "записал сделанным · " + (f"есть похожее{at_} — вопрос" if n == 1 else f"похожих{at_} {n} — вопросы") + " в «Подскажи»"
    else:
        out = "записал сделанным"
    if res.get("skip") == "семья":
        return out + " · семья — в CRM не пишу"
    if res.get("touch") or res.get("touch_existing"):
        return out + (at if res.get("touch") else " · звонок уже записан")
    return out


def op_task_did(conn, user, op, error):
    """task.did — «сделано: …» одной операцией (spec-5 §2). Ответ: {task, did: {closed, created, asked, touch,
    touch_existing, skip, line, undo}}."""
    from . import store

    raw = dict(op.get("task") or {})
    raw.pop("status", None)
    said = " ".join(str(op.get("said") or raw.get("title") or "").split())[:500]
    if not str(raw.get("title") or "").strip():
        raise error("сделано: у дела нет названия")
    tid = str(raw.get("id") or uuid.uuid4())
    try:
        tid = str(uuid.UUID(tid))
    except ValueError:
        raise error("сделано: id дела — не uuid") from None
    raw["id"] = tid
    at = store._instant(op.get("at")) or dt.datetime.now(dt.timezone.utc)
    # Повтор без op_id: дело с этим id уже есть или фраза уже закрыла обещание (предложение said:<id>) — прежнее, без
    # второго закрытия и второго касания.
    have = conn.execute("SELECT * FROM tasks.v_tasks WHERE id = %s", (tid,)).fetchone()
    prev = conn.execute("SELECT * FROM tasks.suggestions WHERE batch_ref = %s AND for_user = %s "
                        "AND reason = %s ORDER BY created_at LIMIT 1",
                        (SAID_PREFIX + tid, user, store.AUTO_CLOSE_REASON)).fetchone()
    if have or prev:
        task = have or store._full(conn, prev["task_id"])
        return {"task": task, "did": {"repeat": True, "closed": prev["task_id"] if prev else None,
                                       "created": tid if have else None, "asked": [], "line": "уже записано", "undo": []}}
    data = store._ball_norm(store._pick({k: v for k, v in raw.items() if k not in store.KRUG_FIELDS},
                                        store.TASK_FIELDS | {"id"}, "дело"))
    waiting = data.get("ball") == "waiting"
    pid = data.get("person_id")
    exact, like = candidates(conn, user, pid, data["title"], waiting)
    day = at.astimezone(store.meetings.MSK).strftime("%d.%m")
    batch = {"batch_ref": SAID_PREFIX + tid, "batch_title": f"Сказал · {day}", "quote": said or data["title"]}
    res: dict = {"closed": None, "created": None, "asked": [], "comment": None}
    undo: list[dict] = []
    if len(exact) == 1:
        c = exact[0]
        s = store._insert(conn, "tasks.suggestions", {
            "kind": "close", "task_id": c["id"], "source": "user", "for_user": user, "created_by": user,
            "expires_at": dt.datetime.now(dt.timezone.utc) + store.SUGGESTION_TTL,
            "payload": Jsonb({"said": said}), **batch})
        got = store.op_suggestion_decide(conn, user, {"id": s["id"], "decision": "accept",
                                                      "reason": store.AUTO_CLOSE_REASON})
        task = got["task"]
        result = got["suggestion"]["result"] or {}
        res.update(closed=c["id"], closed_title=c["title"], comment=result.get("comment_id"))
        for q in like[:ASK_MAX]:
            res["asked"].append(_ask(conn, user, q, said, pid, batch))
        undo += [{"op": "task.reopen", "id": str(c["id"])}]
        if res["comment"]:
            undo.append({"op": "comment.delete", "id": str(res["comment"])})
    else:
        task = store.op_task_create(conn, user, {"task": {**data, "status": "done"}})["task"]
        res["created"] = task["id"]
        undo.append({"op": "task.cancel", "id": str(task["id"])})
        for q in (exact or like)[:ASK_MAX]:
            res["asked"].append(_ask(conn, user, q, said, pid, batch))
    res.update(_touch(conn, user, task, pid, said, at, error))
    if res["touch"]:
        undo.append({"op": "interaction.delete", "id": str(res["touch"])})
    undo += [{"op": "suggestion.decide", "id": str(a), "decision": "reject", "reason": "вернул"} for a in res["asked"]]
    res["line"] = line(res, _gen(conn, pid))
    res["undo"] = undo
    return {"task": task, "did": res}


def _ask(conn, user: str, cand: dict, said: str, person_id, batch: dict):
    """Вопрос «Это закрывает «X»? Ты сказал: «…»» — ждёт ответа владельца (молчание — держать)."""
    from . import store

    got = store.op_suggestion_create(conn, user, {"suggestion": {
        "kind": "close", "task_id": str(cand["id"]), "source": "user",
        "payload": {"ask": f"Это закрывает «{cand['title']}»? Ты сказал: «{said}»",
                    **({"person_id": str(person_id)} if person_id else {})}, **batch}})
    return got["suggestion"]["id"]


def register(handlers: dict, error) -> None:
    handlers["task.did"] = lambda conn, user, op: op_task_did(conn, user, op, error)
