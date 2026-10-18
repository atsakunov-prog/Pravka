"""Захват (шаг 0 петли, 15.10.2026): фраза одной дверью — правило, модель, корзины, строка, поправка кнопкой.

Владелец: «захват за три секунды, которому я доверяю… „напомни в 12 в Контуре отправить счёт“ — не знал, куда».
Случаи правила — общий файл contract/capture-cases.json (их же гоняют телефон и сквозной тест). Имена выдуманные.
«Сейчас» фразы — подменой capture._said_now: чт 15.10.2026 10:30 и 14:00 по Москве.
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import uuid
from pathlib import Path

import pytest
from starlette.testclient import TestClient

from pravka_dela import api, ask, capture, remind, store, tokens
from pravka_dela import db as dela_db
from pravka_dela.config import Config
from test_dela_schema import db_today, dela, project  # noqa: F401

CASES = json.loads((Path(__file__).resolve().parents[1] / "contract" / "capture-cases.json").read_text(encoding="utf-8"))
NOWS = {k: dt.datetime.fromisoformat(v) for k, v in CASES["now"].items() if k != "_"}
BOT = f"svc:{store.REMIND_BOT}"


def ops(url, user, *items, via="app"):
    return store.apply_ops(url, user, list(items), via)["results"]


def seed(url, twin: bool = False) -> dict:
    """Справочник из capture-cases.json: клиент со сделкой, люди, места, «Отбить» и «Мониторить»."""
    d = CASES["directory"]
    ids: dict = {}
    for o in d["orgs"]:
        ids["org:" + o["key"]] = ops(url, "sasha", {"op": "org.create", "data": {"name": o["name"]}})[0]["row"]["id"]
    for c in d["clients"]:
        pid = project(url, "sasha", c["name"], org_id=ids["org:" + c["org"]])
        ids[c["key"]] = str(pid)
        ids["deal:" + c["key"]] = ops(url, "sasha", {"op": "deal.create", "data": {
            "project_id": str(pid), "name": c["deal"], "stage": "active"}})[0]["row"]["id"]
        # «Сейчас» случаев — чт 15.10.2026, а база живёт в настоящем дне: сделка заведена «раньше» фраз, иначе сделка
        # касания (crm.deal_from) не взяла бы записи дня до своего заведения — и тест зависел бы от даты прогона.
        with dela_db.session(url, "system", "test") as conn:
            conn.execute("UPDATE crm.deals SET created_at = '2026-10-01T10:00:00+03:00' WHERE id = %s",
                         (ids["deal:" + c["key"]],))
    people = d["people"] + ([d["twin"]] if twin else [])
    for p in people:
        data = {"name": p["name"], "short": p["short"]}
        if p.get("org"):
            data["org_id"] = ids["org:" + p["org"]]
        ids[p["key"]] = ops(url, "sasha", {"op": "person.create", "data": data})[0]["row"]["id"]
    ops(url, "sasha", {"op": "user.settings", "settings": {"places": d["places"]}})
    for t in d["tasks"]:
        task = {"title": t["title"], "ball": t["ball"], "person_id": ids[t["person"]]}
        ids[t["key"]] = ops(url, "sasha", {"op": "task.create", "task": task})[0]["task"]["id"]
    return ids


@pytest.fixture
def at(monkeypatch):
    """«Сейчас» фразы: at("a") — 10:30, at("b") — 14:00 (чт 15.10.2026)."""
    box = {"now": NOWS["a"]}
    monkeypatch.setattr(capture, "_said_now", lambda op, server_now: box["now"])

    def set_(key):
        box["now"] = NOWS[key] if isinstance(key, str) else key
        return box["now"]
    return set_


def say(url, text, lane="auto", frm="d", user="sasha", ref=None, op_id=None):
    op = {"op": "capture.say", "text": text, "ref": ref or str(uuid.uuid4()), "lane": lane, "from": frm}
    if op_id:
        op["op_id"] = op_id
    r = ops(url, user, op)[0]
    assert r["ok"], r
    return r["capture"]


def move(url, ref, to, item=0, **kw):
    r = ops(url, "sasha", {"op": "capture.move", "ref": ref, "item": item, "to": to, **kw})[0]
    assert r["ok"], r
    return r["capture"]


def task(url, tid):
    with dela_db.session(url, "sasha") as c:
        return c.execute("SELECT * FROM tasks.v_tasks WHERE id = %s", (tid,)).fetchone()


def same_at(a, b) -> bool:
    return remind.at_of(a) == remind.at_of(b)


# ── Правило: общие случаи ───────────────────────────────────────────────


@pytest.mark.parametrize("case", CASES["cases"], ids=[f"{c['say']} @{c['now']}" for c in CASES["cases"]])
def test_capture_cases(dela, at, case):
    ids = seed(dela)
    at(case["now"])
    ref = str(uuid.uuid4())
    cap = say(dela, case["say"], ref=ref)
    assert cap["status"] == "done" and cap["by"] == "rule", cap
    assert cap["form"] == case["form"], cap["form"]
    item = cap["items"][0]
    assert item["bucket"] == case["bucket"], item
    assert item["line"] == case["line"] and cap["head"] == case["line"], item["line"]
    if "chips" in case:
        assert [c["label"] for c in item["chips"]] == case["chips"]
    if "task" in case:
        t = task(dela, item["task"]["id"])
        for k, v in case["task"].items():
            if k == "person":
                assert (str(t["person_id"]) if t["person_id"] else None) == (ids[v] if v else None), (k, t["person_id"])
            elif k == "project":
                assert (str(t["project_id"]) if t["project_id"] else None) == (ids[v] if v else None), (k, t["project_id"])
            elif k == "remind_at" and v:
                assert same_at(t["remind_at"], v), (t["remind_at"], v)
            else:
                got = store.jsonable(t[k])
                assert got == v, (k, got, v)
        assert t["source_ref"] == f"capture:{ref}" and t["source"] == "voice"
    if "idea" in case:
        with dela_db.session(dela, "sasha") as c:
            row = c.execute("SELECT * FROM crm.ideas WHERE id = %s", (item["idea_id"],)).fetchone()
        assert row["text"] == case["idea"]["text"] and str(row["project_id"]) == ids[case["idea"]["project"]]
        assert str(row["deal_id"]) == ids["deal:" + case["idea"]["deal"]] and row["status"] == "open"
    if "note" in case:
        with dela_db.session(dela, "sasha") as c:
            row = c.execute("SELECT * FROM crm.interactions WHERE id = %s", (item["note_id"],)).fetchone()
        n = case["note"]
        assert row["summary"] == n["summary"] and row["kind"] == "note" and row["source"] == "raznoska"
        if n.get("person"):
            assert [str(x) for x in row["person_ids"]] == [ids[n["person"]]]
        if n.get("project"):
            assert str(row["project_id"]) == ids[n["project"]]
            assert str(row["deal_id"]) == ids["deal:" + n["project"]]  # сделка касания: у клиента одна живая
    if "idea" in case and case["idea"].get("person"):
        with dela_db.session(dela, "sasha") as c:
            row = c.execute("SELECT person_id FROM crm.ideas WHERE id = %s", (item["idea_id"],)).fetchone()
        assert str(row["person_id"]) == ids[case["idea"]["person"]]
    if "did" in case:
        d = case["did"]
        if d["closed"]:
            closed = task(dela, ids[d["closed"]])
            assert closed["status"] == "done", closed
        assert item["did"]["closed"] == (ids[d["closed"]] if d["closed"] else None)
        if "asked" in case["did"]:
            assert len(item["did"]["asked"]) == d["asked"]
            if not d["closed"]:  # ничего не закрыто само: все обещания человека — открыты
                assert all(task(dela, ids[k])["status"] == "open" for k in ("ivan_sverka", "ivan_contract", "call_olga"))
        with dela_db.session(dela, "sasha") as c:
            touch = c.execute("SELECT * FROM crm.interactions WHERE id = %s", (item["did"]["touch"],)).fetchone()
        assert touch["kind"] == d["touch_kind"] and [str(x) for x in touch["person_ids"]] == [ids[d["person"]]]


@pytest.mark.parametrize("case", CASES["silent"], ids=[c["say"] for c in CASES["silent"]])
def test_rule_stays_silent(dela, case):
    seed(dela)
    with dela_db.session(dela, "sasha") as c:
        assert capture.rule(c, "sasha", case["say"], NOWS["a"]) is None, case["why"]


def test_said_content_is_not_due(dela):
    """«Сказать / передать / напомнить Ивану, что …» (отладка 16.10.2026, записи 6–7; переделка по замечаниям
    проверяющего, eve-review.md): после глагола, адресата и «что / чтобы» — то, что сказать. Форму, срок и человека решает
    голова до «что», содержание — в название слово в слово; день внутри «что …» у голого дела — вопрос 2 Саши. Ветка —
    только с адресатом сразу за глаголом: «написать отчёт, чтобы …», «передать Ольге документы, чтобы …», «ответить на
    письмо, чтобы …» — прежнее правило со сроком и человеком."""
    ids = seed(dela)
    now = NOWS["a"]   # чт 15.10: «потом» вернётся в пт 16.10
    with dela_db.session(dela, "sasha") as c:
        def one(s, at=now):
            got = capture.rule(c, "sasha", s, at)
            assert got, s
            return got["form"], got["items"][0]

        # «потом» и день внутри «что …» — содержание; запятой распознавание могло не поставить
        form, it = one("сказать Ивану что Ольга уезжает потом")
        assert form == "Г" and it["title"] == "Сказать Ивану, что Ольга уезжает потом"
        assert it["fields"]["back_on"] == "2026-10-16" and "back_said" not in it["fields"]
        # «когда» головы — срок дела, хоть и между адресатом и «что»; содержание целиком
        form, it = one("сказать Ивану на неделе, что Ольга уезжает")
        assert form == "П" and it["fields"]["back_said"] == "на неделе" and it["title"] == "Сказать Ивану, что Ольга уезжает"
        form, it = one("в сейчас сказать Ивану, что встреча в пятницу")
        assert it["bucket"] == "now" and it["title"] == "Сказать Ивану, что встреча в пятницу"
        # человек — кому сказать, а не о ком; роль — дело без человека
        _, it = one("сказать Ольге, что Иван уезжает в понедельник")
        assert it["fields"]["person_id"] == ids["olga"] and it["bucket"] == "back"   # пн позже возврата — «потом»
        _, it = one("сказать бухгалтеру, что Ольга уезжает в понедельник")
        assert "person_id" not in it["fields"] and it["title"] == "Сказать бухгалтеру, что Ольга уезжает в понедельник"
        _, it = one("предупредить Ольгу, что встреча переносится на понедельник")   # у «предупредить» — винительный
        assert it["fields"]["person_id"] == ids["olga"] and it["title"].startswith("Предупредить Ольгу, что")
        # клиент — и из содержания, если голова его не назвала
        _, it = one("передать Ивану, что Ромашка подписала договор в понедельник")
        assert it["fields"]["project_id"] == ids["romashka"] and it["fields"]["person_id"] == ids["ivan"]
        # вопрос 2: день в «что …» не позже возврата «потом» — дело на сегодня (в день возврата сказать уже поздно)
        form, it = one("напомнить Ивану, что договор надо подписать завтра")
        assert form == "Д" and it["bucket"] == "today" and it["fields"]["due_date"] == "2026-10-15"
        assert "back_on" not in it["fields"] and it["title"] == "Напомнить Ивану, что договор надо подписать завтра"
        _, it = one("сказать бухгалтеру, что Ольга уезжает завтра")
        assert it["bucket"] == "today" and "person_id" not in it["fields"]
        fri = NOWS["c"]   # пт 16.10: «потом» вернётся в пн 19.10
        _, it = one("сказать Ивану, что Ольга уезжает в понедельник", fri)
        assert it["bucket"] == "today" and it["fields"]["due_date"] == "2026-10-16"
        _, it = one("сказать Ивану, что Ольга уезжает во вторник", fri)
        assert it["bucket"] == "back" and it["fields"]["back_on"] == "2026-10-19"
        # «до / к <день>» в «что / чтобы …» — срок; голый час — не день
        form, it = one("сказать Ивану, чтобы прислал отчёт до среды")
        assert form == "Д" and it["fields"]["due_date"] == "2026-10-21" and it.get("by_deadline")
        assert it["title"] == "Сказать Ивану, чтобы прислал отчёт до среды"
        _, it = one("сказать Ивану, что договор в 3 экземплярах")
        assert it["bucket"] == "back" and it["title"] == "Сказать Ивану, что договор в 3 экземплярах"
        # «написать» и «ответить» — только человеку: «написать бухгалтеру, что …» — прежнее правило (слова — как раньше)
        _, it = one("написать Ивану, что договор готов")
        assert it["title"] == "Написать Ивану, что договор готов" and it["fields"]["person_id"] == ids["ivan"]
        _, it = one("написать бухгалтеру, что счёт оплачен")
        assert it["title"] == "Написать бухгалтеру что счёт оплачен"
        # цель «чтобы …» после прямого дополнения и не человеку: срок, человек и клиент — как раньше
        for s, due, who in (("написать отчёт, чтобы сдать в пятницу", "2026-10-16", None),
                            ("написать договор, чтобы подписать в среду", "2026-10-21", None),
                            ("передать Ольге документы, чтобы она подписала в пятницу", "2026-10-16", "olga"),
                            ("ответить на письмо, чтобы Иван успел до пятницы", "2026-10-16", "ivan")):
            form, it = one(s)
            assert form == "Д" and it["fields"]["due_date"] == due, s
            assert it["fields"].get("person_id") == (ids[who] if who else None), s
        # «напомни …»: миг — вне «что …», содержание в названии целиком; человек — кому сказать
        form, it = one("напомни завтра сказать Ивану, что встреча переносится на пятницу")
        assert form == "Н" and same_at(it["fields"]["remind_at"], "2026-10-16T09:00:00+03:00")
        assert it["title"] == "Сказать Ивану, что встреча переносится на пятницу" and it["fields"]["person_id"] == ids["ivan"]
        form, it = one("сказать Ивану, что встреча в пятницу, напомни в 12")
        assert form == "Н" and same_at(it["fields"]["remind_at"], "2026-10-15T12:00:00+03:00")
        assert it["title"] == "Сказать Ивану, что встреча в пятницу"
        # как раньше: дательный впереди — модели, вложенность молчит, «напомни мне, что …» правило не берёт
        for s in ("Ивану сказать, что Ольга уезжает на следующей неделе", "сказать Ивану, что для этого нужен паспорт",
                  "напомни мне, что завтра встреча"):
            assert capture.rule(c, "sasha", s, now) is None, s
        ctx = capture.Ctx(c, "sasha", now)
        # не «сказать другому»: себе, без адресата, прямое дополнение, «ответить на …», чужая речь
        for s in ("напомнить мне, что встреча в пятницу", "напомнить себе, что договор завтра", "проверить, что внутри коробки",
                  "Иван сказал, что смета будет к пятнице", "написать отчёт, чтобы сдать в пятницу",
                  "передать Ольге документы, чтобы она подписала в пятницу", "ответить на письмо, чтобы Иван успел",
                  "написать бухгалтеру, что счёт оплачен", "сказать Пете, что Ольга уезжает"):
            assert capture._said_clause(capture.tokens(s), ctx) is None, s
        got = capture._said_clause(capture.tokens("надо бы завтра сказать Ивану Тестову, что договор готов"), ctx)
        assert got["at"] == 6 and got["person"] == ids["ivan"]


def test_back_on_table():
    for c in CASES["back_on"]["cases"]:
        got = capture.default_back(dt.date.fromisoformat(c["today"]), c["said"])
        assert got.isoformat() == c["back_on"], c


def test_when_chunk_is_not_cut_at_preposition():
    """«в 12 в Контуре» — время «в 12», а висячее «в» остаётся названию; «напомни …» без времени — 19:00 / завтра 9:00."""
    tt = capture.tokens("в 12 в Контуре отправить счёт")
    chunks = capture._when_edges(tt, 0, len(tt), NOWS["a"])
    assert [(a, b) for a, b, _ in chunks] == [(0, 2)]
    assert capture._title(tt[2:]) == "Отправить счёт в Контуре"
    late = dt.datetime(2026, 10, 15, 20, 0, tzinfo=capture.MSK)
    assert capture._remind_at(None, NOWS["a"]).hour == 19
    assert capture._remind_at(None, late) == dt.datetime(2026, 10, 16, 9, 0, tzinfo=capture.MSK)


def test_words_say_like_people():
    now = NOWS["a"]
    mk = lambda d, h: dt.datetime(2026, 10, d, h, 0, tzinfo=capture.MSK)  # noqa: E731
    assert capture.when_say(mk(15, 12), now) == "сегодня в 12:00"
    assert capture.when_say(mk(16, 9), now) == "завтра в 9:00"
    assert capture.when_say(mk(20, 9), now) == "во вторник 20.10 в 9:00"
    assert capture.when_say(mk(30, 9), now) == "30 октября в 9:00"
    assert capture.genitive("Иван Тестов") == "Ивана Тестова" and capture.genitive("Ольга Тестова") == "Ольги Тестовой"
    assert capture.genitive("Мария") == "Марии" and capture.genitive("Андрей Белинский") == "Андрея Белинского"
    assert capture.instrumental("Иван Тестов") == "Иваном Тестовым" and capture.instrumental("Ольга") == "Ольгой"


# ── Дверь: повтор, «rule» без совпадения, модель ────────────────────────


def test_repeat_is_harmless(dela, at):
    seed(dela)
    ref, op_id = str(uuid.uuid4()), str(uuid.uuid4())
    a = say(dela, "купить магний", ref=ref, op_id=op_id)
    b = say(dela, "купить магний", ref=ref, op_id=op_id)  # тот же op_id — ответ из ops_seen
    c = say(dela, "купить магний", ref=ref)                # без op_id — тот же ref
    assert a == b and c["items"][0]["task"]["id"] == a["items"][0]["task"]["id"]
    with dela_db.session(dela, "sasha") as conn:
        assert conn.execute("SELECT count(*) AS n FROM tasks.tasks WHERE title = 'Купить магний'").fetchone()["n"] == 1


def test_lane_rule_without_match_leaves_nothing(dela, at):
    seed(dela)
    ref = str(uuid.uuid4())
    cap = say(dela, "созвон с Иваном Тестовым завтра в 12:30", lane="rule", ref=ref)
    assert cap["status"] == "none"
    with dela_db.session(dela, "sasha") as c:
        assert not c.execute("SELECT 1 FROM tasks.dictations WHERE id = %s", ("capture:" + ref,)).fetchone()


def _model(tasks=(), notes=(), ideas=()):
    def task_(title, **kw):
        base = {"title": title, "notes": "", "project": "", "person": "", "ball": "mine", "due": "", "due_time": "",
                "remind_at": "", "remind_place": "", "estimate_min": 0, "money": "", "want": False, "labels": [], "goal": 0,
                "now": False, "back": "", "done": False, "inside": ""}
        return {**base, **kw}
    return {"tasks": [task_(**t) for t in tasks], "notes": list(notes), "ideas": list(ideas),
            "_usage": {"model": "claude-opus-5-5", "input": 100, "output": 20, "cache_write": 0, "cache_read": 0}}


def test_pending_then_model_lays_out_two_reminders(dela, at):
    seed(dela)
    ref = str(uuid.uuid4())
    text = "напомни завтра утром отправить Ивану модель и позвонить маме вечером"
    cap = say(dela, text, ref=ref)
    assert cap["status"] == "pending" and cap["head"].startswith("Записал «") and "раскладываю" in cap["head"]
    seen = {}

    def fake(head, user_text):
        seen["head"], seen["user"] = head, user_text
        return _model(tasks=[{"title": "Отправить Ивану модель", "person": "Иван", "due": "2026-10-16",
                              "remind_at": "2026-10-16 09:00"},
                             {"title": "Позвонить маме", "due": "2026-10-15", "remind_at": "2026-10-15 19:00"}])

    assert capture.tick(dela, ask_fn=fake) == 1
    assert "КОРЗИНЫ" in seen["head"] and "{TODAY}" not in seen["head"] and "{CATALOG}" not in seen["head"]
    assert "Сегодня 2026-" in seen["user"] and seen["user"].endswith(text)
    got = store.view(dela, "sasha", "capture", ref=ref)["capture"]
    assert got["status"] == "done" and got["by"] == "model"
    assert [x["bucket"] for x in got["items"]] == ["remind", "remind"]
    assert got["head"].startswith("Разложил 2: напоминание завтра в 9:00: отправить Ивану модель · напоминание")
    t = task(dela, got["items"][0]["task"]["id"])
    assert t["kind"] == "remind" and t["due_date"] is None and t["person_short"] == "Иван"


def test_model_buckets_by_fields(dela, at):
    """Корзину выбирает код по полям: сделано, своё срок + напоминание, «Сейчас», жду, отбить, при встрече, потом,
    идея без слова «идея» — дело «потом»; заметка — у человека и его клиента."""
    ids = seed(dela)
    text = "много всего разом"
    cap = say(dela, text)
    data = _model(
        tasks=[{"title": "Позвонил Ольге", "person": "Ольга", "done": True},
               {"title": "Подготовить договор", "due": "2026-10-16", "remind_at": "2026-10-15 18:00"},
               {"title": "Разобрать почту", "now": True},
               {"title": "Иван: прислать модель", "person": "Иван", "ball": "waiting"},
               {"title": "Ответить Ольге", "person": "Ольга", "ball": "owed"},
               {"title": "Иван: обсудить фонды", "person": "Иван", "ball": "agenda"},
               {"title": "Купить подарок", "back": "2026-10-20"}],
        notes=[{"text": "Подпишут в среду", "project": "", "person": "Иван"}],
        ideas=[{"text": "предложить дашборд", "project": "ООО Ромашка", "person": ""}])
    capture.tick(dela, ask_fn=lambda h, u: data)
    got = store.view(dela, "sasha", "capture", ref=cap["ref"])["capture"]
    buckets = [x["bucket"] for x in got["items"]]
    assert buckets == ["did", "task_remind", "now", "waiting", "owed", "agenda", "back", "note", "back"], buckets
    assert task(dela, ids["call_olga"])["status"] == "done"
    lines = [x["line"] for x in got["items"]]
    assert lines[1].startswith("Дело на завтра: подготовить договор · напомню сегодня в 18:00")
    # Иван — из компании клиента ООО Ромашка: дело без проекта и цели встаёт к нему (запись 5), строка говорит, к кому.
    assert lines[3] == "Жду от Ивана Тестова: прислать модель · неделя без ответа — на виду · ООО Ромашка"
    assert lines[4] == "Ждёт от тебя Ольга Тестова: ответить · в «Отбить»"  # кто — уже в начале строки
    assert lines[5] == "В повестку с Иваном Тестовым: обсудить фонды · ООО Ромашка"  # слово Саши (запись 19)
    assert task(dela, got["items"][6]["task"]["id"])["back_on"].isoformat() == "2026-10-20"
    assert str(got["items"][7].get("project_id")) == ids["romashka"]
    assert got["items"][8]["title"] == "Предложить дашборд"  # слова «идея» не было — дело


def test_model_same_moment_is_a_reminder(dela, at):
    """«в 12 … не забыть»: модель ставит и срок с часом 12:00, и напоминание в 12:00 — один миг, значит напоминание, не
    дело в куче (сквозной тест 15.10.2026). Свой час или свой день у дела — дело с напоминанием, как было."""
    seed(dela)
    cap = say(dela, "короче в 12 Контур счёт отправить не забыть")
    capture.tick(dela, ask_fn=lambda h, u: _model(tasks=[
        {"title": "Отправить счёт в Контуре", "due": "2026-10-15", "due_time": "12:00", "remind_at": "2026-10-15 12:00"},
        {"title": "Созвон с Иваном", "person": "Иван", "due": "2026-10-15", "due_time": "15:00", "remind_at": "2026-10-15 14:00"}]))
    got = store.view(dela, "sasha", "capture", ref=cap["ref"])["capture"]
    assert [x["bucket"] for x in got["items"]] == ["remind", "task_remind"]
    t = task(dela, got["items"][0]["task"]["id"])
    assert t["kind"] == "remind" and t["due_date"] is None and t["due_time"] is None
    assert got["items"][0]["line"] == "Напомню сегодня в 12:00: отправить счёт в Контуре"


def test_model_note_without_said_prefix(dela, at):
    """Заметка со слов человека — сам факт, «Иван: …» в хронологии, как у правила З1; говорит другой — как есть."""
    ids = seed(dela)
    cap = say(dela, "Иван сказал, бюджет будет в январе. Ольга — что договор в пятницу.")
    capture.tick(dela, ask_fn=lambda h, u: _model(notes=[
        {"text": "Иван сказал, что бюджет будет в январе", "project": "", "person": "Иван"},
        {"text": "Ольга сказала, что договор в пятницу", "project": "", "person": "Иван"}]))
    got = store.view(dela, "sasha", "capture", ref=cap["ref"])["capture"]
    assert got["items"][0]["line"] == "Заметка у Ивана Тестова: бюджет будет в январе · в карточке"
    assert got["items"][1]["line"].startswith("Заметка у Ивана Тестова: Ольга сказала, что договор")  # имя — с большой
    with dela_db.session(dela, "sasha") as c:
        row = c.execute("SELECT summary, project_id FROM crm.interactions WHERE id = %s", (got["items"][0]["note_id"],)).fetchone()
    assert row["summary"] == "Иван: бюджет будет в январе" and str(row["project_id"]) == ids["romashka"]
    # «хочет», «обещал», «просил» — не глагол речи, а смысл: заметка остаётся как сказана (сквозной тест 16.10.2026).
    cap = say(dela, "Иван хочет скидку")
    assert cap["status"] == "pending"  # правило с такими молчит
    capture.tick(dela, ask_fn=lambda h, u: _model(notes=[{"text": "Иван хочет скидку", "project": "", "person": "Иван"}]))
    got = store.view(dela, "sasha", "capture", ref=cap["ref"])["capture"]
    assert got["items"][0]["line"] == "Заметка у Ивана Тестова: Иван хочет скидку · в карточке"


def test_model_say_to_is_one_task(dela, at, caplog):
    """«Ивану сказать, что Ольга уезжает…» — одно дело у Ивана. Модель, как вживую, кладёт ещё и заметку с тем же текстом
    — у Ольги, у самого Ивана, без человека: это содержание дела, а не факт, — их нет (отладка 16.10.2026, записи 6–7:
    лишняя заметка у второго человека читалась как «записал в CRM, а не дело»). Настоящий факт той же фразы остаётся."""
    ids = seed(dela)
    ref = str(uuid.uuid4())
    cap = say(dela, "ивану сказать, что ольга уезжает на следующей неделе и встречи будут через неделю", ref=ref)
    assert cap["status"] == "pending"  # первым — человек в дательном: правило отдаёт модели
    title = "Сказать Ивану, что Ольга уезжает на следующей неделе и встречи будут через неделю"
    with caplog.at_level(logging.INFO, logger="dela.capture"):
        capture.tick(dela, ask_fn=lambda h, u: _model(
            tasks=[{"title": title, "person": "Иван"}],
            notes=[{"text": "Ольга уезжает на следующей неделе, встречи будут через неделю", "project": "", "person": "Ольга"},
                   {"text": "Ольга уезжает на следующей неделе", "project": "", "person": "Иван"},
                   {"text": "Встречи будут через неделю", "project": "ООО Ромашка", "person": ""},
                   {"text": "Ольга вернётся в ноябре", "project": "", "person": "Ольга"}]))
    assert caplog.text.count("заметка = содержание дела, не кладу") == 3
    got = store.view(dela, "sasha", "capture", ref=ref)["capture"]
    assert [x["bucket"] for x in got["items"]] == ["back", "note"]
    t = task(dela, got["items"][0]["task"]["id"])
    assert t["title"] == title and str(t["person_id"]) == ids["ivan"] and t["back_on"].isoformat() == "2026-10-16"
    assert got["items"][1]["text"] == "Ольга вернётся в ноябре"   # факт, а не «что сказать», — заметкой, как был
    with dela_db.session(dela, "sasha") as c:
        rows = c.execute("SELECT summary FROM crm.interactions WHERE source_ref LIKE %s", (f"capture:{ref}:%",)).fetchall()
    assert [r["summary"] for r in rows] == ["Ольга вернётся в ноябре"]


def test_model_said_note_stays(dela, at):
    """Отрицательный случай проверяющего (eve-review.md, «блокирует» к 565783ca): «Иван сказал, что бюджет утвердят в
    январе — передать Ольге» — дело «Передать Ольге, что …» несёт все слова заметки, но заметка у Ивана — его слова, факт:
    она ложится (хронология и «последний контакт» Ивана), как и запись у его клиента без человека. Глагол речи есть, а
    кто сказал — не узнали («он сказал…»): не режем ничего. «Проверить в понедельник, что смета будет готова» — не
    сообщение: заметка «Смета будет готова в понедельник» остаётся."""
    ids = seed(dela)
    send = "Передать Ольге, что бюджет утвердят в январе"
    ref = str(uuid.uuid4())
    cap = say(dela, "Иван сказал, что бюджет утвердят в январе — передать Ольге", ref=ref)
    assert cap["status"] == "pending"  # факт и дело одной фразой — модели
    capture.tick(dela, ask_fn=lambda h, u: _model(
        tasks=[{"title": send, "person": "Ольга"}],
        notes=[{"text": "Бюджет утвердят в январе", "project": "", "person": "Иван"},
               {"text": "Бюджет утвердят в январе", "project": "ООО Ромашка", "person": ""},
               {"text": "Бюджет утвердят в январе", "project": "", "person": "Ольга"}]))
    got = store.view(dela, "sasha", "capture", ref=ref)["capture"]
    assert [x["bucket"] for x in got["items"]] == ["back", "note", "note"]   # у Ольги — содержание дела: нет
    assert task(dela, got["items"][0]["task"]["id"])["title"] == send
    with dela_db.session(dela, "sasha") as c:
        rows = c.execute("SELECT summary, person_ids, project_id, source FROM crm.interactions WHERE source_ref LIKE %s "
                         "ORDER BY source_ref", (f"capture:{ref}:%",)).fetchall()
    assert [[str(x) for x in r["person_ids"] or []] for r in rows] == [[ids["ivan"]], []]
    assert all(str(r["project_id"]) == ids["romashka"] and r["source"] == "raznoska" for r in rows)
    assert rows[0]["summary"] == "Бюджет утвердят в январе"
    # кто сказал — не узнали: заметки остаются все
    cap = say(dela, "он сказал, что бюджет утвердят в январе — передать Ольге")
    capture.tick(dela, ask_fn=lambda h, u: _model(
        tasks=[{"title": send, "person": "Ольга"}],
        notes=[{"text": "Бюджет утвердят в январе", "project": "", "person": "Ольга"}]))
    got = store.view(dela, "sasha", "capture", ref=cap["ref"])["capture"]
    assert [x["bucket"] for x in got["items"]] == ["back", "note"]
    # не сообщение — заметка остаётся; слова заметки не все в «что …» — тоже
    cap = say(dela, "много всего разом")
    capture.tick(dela, ask_fn=lambda h, u: _model(
        tasks=[{"title": "Проверить в понедельник, что смета будет готова", "due": "2026-10-19"},
               {"title": send, "person": "Ольга"}],
        notes=[{"text": "Смета будет готова в понедельник", "project": "", "person": "Ольга"},
               {"text": "Бюджет будет в январе", "project": "", "person": "Ольга"}]))
    got = store.view(dela, "sasha", "capture", ref=cap["ref"])["capture"]
    assert [x["bucket"] for x in got["items"]] == ["day", "back", "note", "note"]


def test_model_waiting_and_owed_with_due_say_whose(dela, at):
    """«Жду» и «Отбить» со сроком — в строке чьё («На завтра: Иван: прислать отчёт» читалось своим делом); человек,
    названный в начале строки, второй раз не повторяется («… : прислать Ивану модель», «… для Ольги» — сквозной тест)."""
    seed(dela)
    cap = say(dela, "Иван обещал прислать отчёт до пятницы и я сказал Ивану что пришлём модель к среде, а Ольге — договор")
    capture.tick(dela, ask_fn=lambda h, u: _model(tasks=[
        {"title": "Иван: прислать отчёт", "person": "Иван", "ball": "waiting", "due": "2026-10-16"},
        {"title": "Прислать Ивану модель", "person": "Иван", "ball": "owed", "due": "2026-10-21"},
        {"title": "Подготовить договор для Ольги", "person": "Ольга", "ball": "owed", "due": "2026-10-16"}]))
    got = store.view(dela, "sasha", "capture", ref=cap["ref"])["capture"]
    assert [x["line"] for x in got["items"]] == ["Жду от Ивана Тестова: прислать отчёт · на завтра · ООО Ромашка",
                                                  "Ждёт от тебя Иван Тестов: прислать модель · на среду 21.10 · ООО Ромашка",
                                                  "Ждёт от тебя Ольга Тестова: подготовить договор · на завтра"]
    assert got["head"] == ("Разложил 3: жду от Ивана на завтра: прислать отчёт · на среду 21.10: прислать Ивану модель · "
                           "на завтра: подготовить договор для Ольги")
    # Название дела — как сказано: строка только не повторяет человека.
    assert task(dela, got["items"][1]["task"]["id"])["title"] == "Прислать Ивану модель"


def test_head_says_task_and_whose_note(dela, at):
    """«Разложил N» говорит «дело» и чья заметка (отладка 16.10.2026, записи 6–7): «дело «потом», верну …» вместо «потом —
    верну …», «заметка (Ольга Тестова): …» вместо голой «заметка: …» — имя в скобках, а не через «·»: «·» разделяет
    записи (мелочь проверки к 565783ca). Фамилия первым словом заметки — с большой. Одна запись — прежняя строка."""
    seed(dela)
    cap = say(dela, "много всего разом")
    capture.tick(dela, ask_fn=lambda h, u: _model(tasks=[{"title": "Подготовить счёт"}],
                                                  notes=[{"text": "Подпишут в среду", "project": "", "person": "Ольга"}]))
    got = store.view(dela, "sasha", "capture", ref=cap["ref"])["capture"]
    assert [x["bucket"] for x in got["items"]] == ["back", "note"]
    assert got["head"] == ("Разложил 2: дело «потом», верну в пятницу 16.10: подготовить счёт · "
                           "заметка (Ольга Тестова): подпишут в среду")
    assert got["items"][1]["whose"] == "Ольга Тестова"
    assert got["items"][1]["line"] == "Заметка у Ольги Тестовой: подпишут в среду · в карточке"  # одна строка — как была
    cap = say(dela, "ещё всего разом")
    capture.tick(dela, ask_fn=lambda h, u: _model(
        tasks=[{"title": "Подготовить договор", "back": "2026-10-20"}],
        notes=[{"text": "Тестова подпишет в среду", "project": "", "person": "Ольга"},
               {"text": "Бюджет утвердят в январе", "project": "ООО Ромашка", "person": ""},
               {"text": "Договорились на пятницу", "project": "", "person": ""}]))
    got = store.view(dela, "sasha", "capture", ref=cap["ref"])["capture"]
    assert got["head"] == ("Разложил 4: дело «потом», верну вт: подготовить договор · "
                           "заметка (Ольга Тестова): Тестова подпишет в среду · "
                           "заметка (ООО Ромашка): бюджет утвердят в январе · заметка: договорились на пятницу")
    assert got["head"].count(" · ") == 3  # «·» — только между записями


def test_head_note_whose_after_twin_pick(dela, at):
    """Тёзки: заметка легла без человека — «заметка: …»; выбрал кнопкой — у записи `whose`, голова говорит чья."""
    ids = seed(dela, twin=True)
    cap = say(dela, "много всего разом")
    capture.tick(dela, ask_fn=lambda h, u: _model(tasks=[{"title": "Подготовить счёт"}], notes=[
        {"text": "Иван сказал, что бюджет будет в январе", "project": "", "person": ""}]))
    got = store.view(dela, "sasha", "capture", ref=cap["ref"])["capture"]
    assert "whose" not in got["items"][1] and got["head"].endswith("· заметка: Иван сказал, что бюджет будет в январе")
    with dela_db.session(dela, "sasha") as c:  # тёзки — как у правила: запись без человека и кнопка «Какой Иван ▾»
        c.execute("UPDATE tasks.dictations SET capture_result = jsonb_set(capture_result, '{items,1,twins}', %s::jsonb) "
                  "WHERE id = %s", (json.dumps([{"id": ids["ivan"], "name": "Иван Тестов", "short": "Иван"},
                                                {"id": ids["ivan2"], "name": "Иван Петров", "short": "Иван"}]),
                                    "capture:" + cap["ref"]))
    got = move(dela, cap["ref"], "person", item=1, person_id=ids["ivan2"])
    assert got["items"][1]["whose"] == "Иван Петров"
    assert got["head"].endswith("· заметка (Иван Петров): Иван сказал, что бюджет будет в январе"), got["head"]


# ── Повестка словами (мелочи 16.10.2026, запись 19) ───────────────────────


def test_agenda_rule_twins_and_just_a_task(dela, at):
    """Повестка правилом: тёзки — молчит (модели, как раньше); «Просто дело» — повестка не то: дело Саши «потом», не
    повестка человека; в «Разложил N» — «в повестку с …» без повтора имени."""
    ids = seed(dela, twin=True)
    cap = say(dela, "обсудить с Иваном фонды")
    assert cap["status"] == "pending", cap  # два Ивана — чья повестка, решит модель
    cap = say(dela, "не забыть обсудить с Ольгой отпуск")
    assert cap["by"] == "rule" and cap["form"] == "Пв" and cap["items"][0]["bucket"] == "agenda", cap
    assert cap["head"] == "В повестку с Ольгой Тестовой: обсудить отпуск"
    tid = cap["items"][0]["task"]["id"]
    assert (task(dela, tid)["ball"], str(task(dela, tid)["person_id"])) == ("agenda", ids["olga"])
    got = move(dela, cap["ref"], "mine")
    t = task(dela, tid)
    back = capture.default_back(db_today(dela), "")  # кнопка — по часам сервера, не фразы
    assert (t["ball"], t["back_on"], str(t["person_id"])) == ("mine", back, ids["olga"])
    assert got["head"] == f"Переложил: Потом: обсудить с Ольгой отпуск · {capture.back_say(back, db_today(dela))}"
    assert [c["label"] for c in got["items"][0]["chips"]] == ["Сегодня", "В «Сейчас»", "Отменить"]
    # Модель: повестка в «Разложил N» — «в повестку с Ольгой Тестовой: …», имя второй раз не повторяется.
    cap = say(dela, "много всего разом")
    capture.tick(dela, ask_fn=lambda h, u: _model(tasks=[
        {"title": "Обсудить с Ольгой отпуск", "person": "Ольга", "ball": "agenda"}, {"title": "Купить магний"}]))
    got = store.view(dela, "sasha", "capture", ref=cap["ref"])["capture"]
    assert got["head"] == ("Разложил 2: в повестку с Ольгой Тестовой: обсудить отпуск · дело «потом», верну в пятницу "
                           "16.10: купить магний"), got["head"]
    # То же открытое дело у Ольги уже есть (выше — «Просто дело»): первой кнопкой — «Внутрь «…»» к нему (вагон B, дубль
    # записи 5), остальное — как у повестки. Тот же тик разложил и ждущую «обсудить с Иваном фонды» тем же ответом —
    # двумя потоками: легла она раньше — похожих дел у Ольги два, и «Внутрь» не предлагается (не уверен — не советуем).
    labels = [c["label"] for c in got["items"][0]["chips"]]
    assert labels[-2:] == ["Просто дело", "Отменить"] and (len(labels) == 2 or labels[0] == "Внутрь «Обсудить с Ольгой отпуск»"), labels


# ── Клиент дела по человеку (отладка 16.10.2026, запись 5) ──────────────


def _beta(url, ids: dict) -> dict:
    """Пётр Сидоров из «Альфа Холдинг» (не клиент), его открытое дело — у клиента «Бета Групп»: компания клиента не
    даёт, а его дела — дают (как запись 5: компания в карточке — не клиент, все дела — у одного клиента)."""
    alfa = ops(url, "sasha", {"op": "org.create", "data": {"name": "Альфа Холдинг"}})[0]["row"]["id"]
    ids["petr"] = ops(url, "sasha", {"op": "person.create", "data": {"name": "Пётр Сидоров", "short": "Пётр",
                                                                     "org_id": alfa}})[0]["row"]["id"]
    ids["beta"] = str(project(url, "sasha", "Бета Групп"))
    ids["petr_task"] = ops(url, "sasha", {"op": "task.create", "task": {
        "title": "Пётр: прислать презентацию", "ball": "waiting", "person_id": ids["petr"], "project_id": ids["beta"]}})[0]["task"]["id"]
    return ids


def test_model_task_gets_person_client_and_no_client_button(dela, at):
    """Дело модели без проекта и цели, у человека один клиент — клиент ставится правилом ($0): по компании (Иван —
    ООО Ромашка) и по его открытым делам (Пётр — «Бета Групп»). Строка говорит «· Клиент», вторая кнопка — «Без
    клиента», она снимает клиента одним касанием."""
    ids = _beta(dela, seed(dela))
    cap = say(dela, "много всего разом")
    capture.tick(dela, ask_fn=lambda h, u: _model(tasks=[
        {"title": "Попросить Петра прислать информацию", "person": "Пётр", "due": "2026-10-20"},
        {"title": "Позвонить Ивану", "person": "Иван"}]))
    got = store.view(dela, "sasha", "capture", ref=cap["ref"])["capture"]
    first, second = got["items"]
    assert str(task(dela, first["task"]["id"])["project_id"]) == ids["beta"] and first["client_auto"] == ids["beta"]
    assert first["line"] == "На вторник 20.10: попросить Петра прислать информацию · Бета Групп"
    assert [c["label"] for c in first["chips"]] == ["В «Сейчас»", "Без клиента", "Отменить"]
    assert str(task(dela, second["task"]["id"])["project_id"]) == ids["romashka"]
    assert second["line"].endswith(" · ООО Ромашка") and [c["label"] for c in second["chips"]] == [
        "Сегодня", "Без клиента", "Отменить"]
    got = move(dela, cap["ref"], "no_client", item=0)
    item = got["items"][0]
    assert task(dela, item["task"]["id"])["project_id"] is None and not item.get("client_auto")
    # День строки «Переложил» считается от сегодняшнего числа, а не от замороженного «сейчас» фразы: 18.10 тот же
    # 20.10 — «послезавтра», 16.10 был «вторник 20.10». Тест не должен зависеть от дня прогона.
    assert item["line"].startswith("Переложил: На ") and item["line"].endswith(": попросить Петра прислать информацию")
    assert [c["label"] for c in item["chips"]] == ["В «Сейчас»", "Потом", "Отменить"]
    r = ops(dela, "sasha", {"op": "capture.move", "ref": cap["ref"], "item": 0, "to": "no_client"})[0]
    assert not r["ok"] and "клиента ставил не захват" in r["error"], r


def test_person_client_rule_stays_silent(dela, at):
    """Не ставит: модель назвала цель или проект; у человека два клиента; напоминание (не дело); человек — команда
    (пользователь Дел) или сам владелец."""
    ids = _beta(dela, seed(dela))
    # Второй клиент у Петра — его дела у двух клиентов: не угадываем.
    ops(dela, "sasha", {"op": "task.create", "task": {"title": "Пётр: счёт", "person_id": ids["petr"],
                                                       "project_id": ids["romashka"]}})
    cap = say(dela, "много всего разом")
    capture.tick(dela, ask_fn=lambda h, u: _model(tasks=[
        {"title": "Позвонить Петру", "person": "Пётр"},
        {"title": "Позвонить Ивану про цель", "person": "Иван", "goal": 777},
        {"title": "Позвонить Ивану про Бету", "person": "Иван", "project": "Бета Групп"},
        {"title": "Позвонить Ивану", "person": "Иван", "remind_at": "2026-10-15 18:00"}]))
    got = store.view(dela, "sasha", "capture", ref=cap["ref"])["capture"]
    assert [x["bucket"] for x in got["items"]] == ["back", "back", "back", "remind"]
    projects = [task(dela, x["task"]["id"])["project_id"] for x in got["items"]]
    assert projects[0] is None and projects[1] is None and str(projects[2]) == ids["beta"] and projects[3] is None
    assert not any(x.get("client_auto") for x in got["items"])
    assert not any(" · ООО Ромашка" in x["line"] or "Без клиента" in str(x["chips"]) for x in got["items"])
    # Команда: Иван — человек пользователя Дел (коллега), его дела — не дела клиента; владелец сам — тоже.
    with dela_db.session(dela, "system", "test") as c:
        c.execute("UPDATE crm.users SET person_id = %s WHERE id = 'natasha'", (ids["ivan"],))
        c.execute("UPDATE crm.users SET person_id = %s WHERE id = 'sasha'", (ids["olga"],))
        c.execute("UPDATE crm.people SET org_id = (SELECT org_id FROM crm.people WHERE id = %s) WHERE id = %s",
                  (ids["ivan"], ids["olga"]))
    cap = say(dela, "позвонить Ивану на неделе")
    t = task(dela, cap["items"][0]["task"]["id"])
    assert t["project_id"] is None and cap["items"][0]["line"] == "Потом: позвонить Ивану · верну в пятницу 16.10"
    cap = say(dela, "позвонить Ольге на неделе")
    assert task(dela, cap["items"][0]["task"]["id"])["project_id"] is None


def test_person_client_sees_only_what_the_speaker_sees(dela, at):
    """Правило — глазами того, кто говорит (RLS): коллеге без доступа к клиенту клиент не ставится, и её дело не уходит
    к команде клиента; с правом смотреть — тоже нет (дело в нём не легло бы); с правом править — ставится."""
    ids = seed(dela)
    # Ивана коллега видит через дело клиента, когда ей открыт клиент (crm.person_linked); без доступа — не видит вовсе.
    ops(dela, "sasha", {"op": "task.create", "task": {"title": "Иван: подписать акт", "ball": "waiting",
                                                       "person_id": ids["ivan"], "project_id": ids["romashka"]}})
    cap = say(dela, "позвонить Ивану на неделе", user="natasha")
    item = cap["items"][0]
    assert item["bucket"] == "back" and not item.get("client_auto"), item
    with dela_db.session(dela, "natasha") as c:
        t = c.execute("SELECT project_id, owner_id FROM tasks.v_tasks WHERE id = %s", (item["task"]["id"],)).fetchone()
    assert t["project_id"] is None and t["owner_id"] == "natasha"
    for role, expect in (("view", None), ("edit", ids["romashka"])):
        with dela_db.session(dela, "system", "test") as c:
            c.execute("DELETE FROM crm.project_access WHERE user_id = 'natasha'")
            c.execute("INSERT INTO crm.project_access (project_id, user_id, role, granted_by) VALUES (%s, 'natasha', %s, "
                      "'sasha')", (ids["romashka"], role))
        cap = say(dela, "позвонить Ивану на неделе", user="natasha")
        item = cap["items"][0]
        assert item["bucket"] == "back" and str(item["task"]["person_id"]) == ids["ivan"], (role, item)  # Иван узнан
        with dela_db.session(dela, "natasha") as c:
            got = c.execute("SELECT project_id FROM tasks.v_tasks WHERE id = %s", (item["task"]["id"],)).fetchone()
        assert (str(got["project_id"]) if got["project_id"] else None) == expect, role


def test_move_sees_task_as_it_is_now(dela, at):
    """Кнопка под строкой — с делом, каким оно стало: «Завтра» в уведомлении, потом «Это дело» — дело на завтра; сделанное
    кнопкой уведомления — пилюля словами отказывает (сквозной тест 15.10.2026)."""
    seed(dela)
    cap = say(dela, "напомни в 12 в Контуре отправить счёт")
    tid = cap["items"][0]["task"]["id"]
    tomorrow = db_today(dela) + dt.timedelta(days=1)  # кнопка живёт в настоящем «сейчас» сервера
    ops(dela, "sasha", {"op": "task.set", "id": tid, "set": {"remind_at": f"{tomorrow.isoformat()}T09:00:00+03:00",
                                                             "remind_place": None}})
    moved = move(dela, cap["ref"], "task")
    assert task(dela, tid)["due_date"] == tomorrow
    assert moved["head"] == "Переложил: Дело на завтра: отправить счёт в Контуре · напомню завтра в 9:00"
    ops(dela, "sasha", {"op": "task.done", "id": tid})
    r = ops(dela, "sasha", {"op": "capture.move", "ref": cap["ref"], "item": 0, "to": "remind"})[0]
    assert not r["ok"] and "уже сделано" in r["error"]


def test_model_idea_only_with_the_word(dela, at):
    ids = seed(dela)
    cap = say(dela, "есть идея на будущее для Ромашки и ещё одна мысль — предложить дашборд")
    capture.tick(dela, ask_fn=lambda h, u: _model(ideas=[{"text": "предложить дашборд", "project": "ООО Ромашка",
                                                           "person": ""}]))
    got = store.view(dela, "sasha", "capture", ref=cap["ref"])["capture"]
    assert [x["bucket"] for x in got["items"]] == ["idea"] and str(got["items"][0]["project_id"]) == ids["romashka"]


def test_model_failure_is_words_and_buttons(dela, at):
    seed(dela)
    cap = say(dela, "что-то длинное и непонятное про разное")

    def boom(h, u):
        raise capture.CaptureError("Claude отказался разбирать эту фразу")

    capture.tick(dela, ask_fn=boom)
    got = store.view(dela, "sasha", "capture", ref=cap["ref"])["capture"]
    assert got["status"] == "error" and got["head"].startswith("Не разложил: Claude отказался")
    assert [c["label"] for c in got["chips"]] == ["Повторить", "Дело потом"]
    again = move(dela, cap["ref"], "retry", item=-1)
    assert again["status"] == "pending"
    capture.tick(dela, ask_fn=boom)
    done = move(dela, cap["ref"], "back_task", item=-1)
    assert done["status"] == "done" and done["items"][0]["bucket"] == "back"
    assert task(dela, done["items"][0]["task"]["id"])["title"] == "Что-то длинное и непонятное про разное"


def test_no_key_is_an_error_at_once_rule_still_works(dela, at):
    seed(dela)
    cap = say(dela, "что-то длинное и непонятное про разное")
    capture.tick(dela, key="")
    got = store.view(dela, "sasha", "capture", ref=cap["ref"])["capture"]
    assert got["status"] == "error" and "нет ключа" in got["error"]
    assert say(dela, "купить магний")["status"] == "done"


def test_capture_block_once_and_svod_wins(dela):
    rules = "ПРАВИЛА\n\nСегодня {TODAY}, сейчас {NOW}."
    once = capture.with_capture(rules, "КОРЗИНЫ — блок")
    assert once.index("КОРЗИНЫ") < once.index("{TODAY}") and capture.with_capture(once, "КОРЗИНЫ — блок") == once
    assert "КОРЗИНЫ" in capture.capture_block(dela)  # файл контракта — запас
    ops(dela, "sasha", {"op": "svod.set", "key": capture.CAPTURE_KEY, "body": "КОРЗИНЫ владельца", "author": "test"})
    assert capture.capture_block(dela) == "КОРЗИНЫ владельца"


def test_capture_schema_fits_grammar():
    """Схема захвата — у предела грамматики API (как ask.SCHEMA, test_schema_fits_grammar): не больше 52 полей."""
    def count(node):
        if node.get("type") == "object":
            return sum(1 + count(v) for v in node["properties"].values())
        if node.get("type") == "array":
            return count(node["items"])
        return 0

    s = capture.schema()
    assert count(s) <= 52 and {"now", "back", "done", "inside"} <= set(s["properties"]["tasks"]["items"]["required"])
    assert "ideas" in s["required"]


# ── Поправка одним касанием ─────────────────────────────────────────────


def test_move_reminder_to_task_time_and_cancel(dela, at):
    seed(dela)
    today = db_today(dela)
    at(dt.datetime(today.year, today.month, today.day, 10, 30, tzinfo=capture.MSK))  # кнопка — в настоящем дне сервера
    cap = say(dela, "напомни в 12 в Контуре отправить счёт")
    assert [c["label"] for c in cap["items"][0]["chips"]] == ["Время", "Это дело", "Отменить"]  # частое — первым
    tid = cap["items"][0]["task"]["id"]
    got = move(dela, cap["ref"], "task")
    t = task(dela, tid)
    assert t["kind"] == "task" and t["due_date"] == today and t["remind_at"]
    assert got["items"][0]["bucket"] == "task_remind" and got["items"][0]["line"].startswith("Переложил: Дело на сегодня")
    assert [c["label"] for c in got["items"][0]["chips"]] == ["Только напомнить", "Отменить"]
    back = move(dela, cap["ref"], "remind")
    assert task(dela, tid)["kind"] == "remind" and task(dela, tid)["due_date"] is None
    assert back["items"][0]["bucket"] == "remind"
    timed = move(dela, cap["ref"], "time", at="tomorrow")
    assert same_at(task(dela, tid)["remind_at"], f"{(db_today(dela) + dt.timedelta(days=1)).isoformat()}T09:00:00+03:00")
    assert timed["items"][0]["line"].startswith("Переложил: Напомню завтра в 9:00")
    gone = move(dela, cap["ref"], "cancel")
    assert task(dela, tid)["status"] == "cancelled" and gone["items"][0]["bucket"] == "cancelled"
    assert gone["items"][0]["line"].startswith("Отменил: Напомню") and gone["items"][0]["chips"] == []


def test_move_back_today_now_and_back(dela, at):
    seed(dela)
    cap = say(dela, "купить магний")
    tid = cap["items"][0]["task"]["id"]
    today = db_today(dela)
    move(dela, cap["ref"], "today")
    t = task(dela, tid)
    assert t["due_date"] == today and t["back_on"] is None
    now = move(dela, cap["ref"], "now")
    assert task(dela, tid)["focus_on"] == today and now["items"][0]["line"] == "Переложил: В «Сейчас» (1 из 5): купить магний"
    move(dela, cap["ref"], "back")
    t = task(dela, tid)
    assert t["back_on"] and t["due_date"] is None and t["focus_on"] is None


def _asks(url):
    with dela_db.session(url, "sasha") as c:
        return c.execute("SELECT * FROM tasks.suggestions WHERE kind = 'capture' ORDER BY created_at").fetchall()


def test_move_note_idea_task_and_twins(dela, at):
    """Тёзки — запись без человека, под строкой одна кнопка «Какой Иван ▾» со списком (не случайный Иван первым), и тот же
    вопрос в «Подскажи» (кнопка живёт 12 секунд пилюли); выбор кнопкой гасит вопрос (проверка 16.10.2026)."""
    ids = seed(dela, twin=True)
    cap = say(dela, "Иван сказал, бюджет будет в январе")
    item = cap["items"][0]
    assert item["bucket"] == "note" and item.get("twins") and not item.get("person_id")
    assert item["line"] == "Заметка: бюджет будет в январе — у какого Ивана? · вопрос в «Подскажи»"
    assert [c["label"] for c in item["chips"]] == ["Какой Иван", "Отменить"]
    who = item["chips"][0]
    assert who["move"] == "person" and {o["person_id"] for o in who["options"]} == {ids["ivan"], ids["ivan2"]}
    [ask_] = _asks(dela)
    assert ask_["status"] == "pending" and ask_["payload"]["pick"] and ask_["payload"]["ask"].endswith("у какого Ивана?")
    assert ask_["batch_ref"].startswith("said:") and len(ask_["payload"]["options"]) == 2
    got = move(dela, cap["ref"], "person", person_id=ids["ivan"])
    assert got["items"][0]["line"].startswith("Переложил: Заметка у Ивана Тестова: бюджет будет в январе")
    assert _asks(dela)[0]["status"] == "accepted"  # ответ кнопкой под строкой — вопрос в «Подскажи» снят
    idea = move(dela, cap["ref"], "idea")
    assert idea["items"][0]["bucket"] == "idea"
    with dela_db.session(dela, "sasha") as c:
        assert c.execute("SELECT deleted_at FROM crm.interactions WHERE id = %s", (item["note_id"],)).fetchone()["deleted_at"]
    made = move(dela, cap["ref"], "task")
    assert made["items"][0]["bucket"] == "back"
    with dela_db.session(dela, "sasha") as c:
        row = c.execute("SELECT status, task_id FROM crm.ideas WHERE id = %s", (idea["items"][0]["idea_id"],)).fetchone()
    assert row["status"] == "used" and str(row["task_id"]) == made["items"][0]["task"]["id"]


def test_twins_task_has_no_person(dela, at):
    seed(dela, twin=True)
    cap = say(dela, "позвонить Ивану на неделе")
    assert cap["items"][0]["bucket"] == "back" and task(dela, cap["items"][0]["task"]["id"])["person_id"] is None


def test_twins_question_is_evening_about_the_name(dela, at):
    """Поезд 16.10.2026 (вагоны 4 и 7): «Какой Иван?» идёт в «Подскажи» мимо suggestion.create — штамп шага 2 ставит
    захват сам. Фраза самого Саши — вечер, как «это закрывает …?» did.py (ответ 3а); предмет — человек по имени тёзок,
    какая карточка — решит ответ; в виде asks — группа «Какой Иван?» (проверка вагона 7: не голое «Иван» — у карточки
    «Иван Тестов» короткое имя то же, и вечером «Иван · 1» стоял бы дважды)."""
    seed(dela, twin=True)
    say(dela, "Иван сказал, бюджет будет в январе")
    say(dela, "позвонил Ивану")
    got = _asks(dela)
    assert len(got) == 2 and {a["batch_ref"][:6] for a in got} == {"said:c"}
    for a in got:
        assert a["payload"]["urgency"] == "evening" and "urgent_on" not in a["payload"], a["payload"]
        assert a["payload"]["about"] == {"kind": "person", "title": "Какой Иван?"}, a["payload"]
    v = store.view(dela, "sasha", "asks")
    assert [(g["kind"], g["title"], g["n"]) for g in v["groups"] if g["kind"] == "person"] == [("person", "Какой Иван?", 2)]
    # Вопрос о самой карточке «Иван …» (short «Иван») — своя группа по id с заголовком «Иван»: заголовки не совпадают.
    ivan = next(o["person_id"] for o in got[0]["payload"]["options"])
    r = ops(dela, "sasha", {"op": "suggestion.create", "suggestion": {
        "kind": "create", "source": "telegram", "batch_ref": "tg:twins", "batch_title": "Telegram",
        "payload": {"title": "Уточнить у Ивана смету", "person_id": ivan}}})
    assert r[0]["ok"], r
    titles = [g["title"] for g in store.view(dela, "sasha", "asks")["groups"] if g["kind"] == "person"]
    assert len(titles) == 2 and len(set(titles)) == 2 and "Какой Иван?" in titles, titles


def test_did_keep_open_and_cancel(dela, at):
    ids = seed(dela)
    cap = say(dela, "позвонил Ольге")
    kept = move(dela, cap["ref"], "keep_open")
    assert task(dela, ids["call_olga"])["status"] == "open"
    assert kept["items"][0]["chips"][0]["label"] == "Отменить"
    with dela_db.session(dela, "sasha") as c:
        touch = c.execute("SELECT deleted_at FROM crm.interactions WHERE id = %s",
                          (cap["items"][0]["did"]["touch"],)).fetchone()
    assert touch["deleted_at"] is None  # звонок был — касание остаётся
    move(dela, cap["ref"], "cancel")
    with dela_db.session(dela, "sasha") as c:
        assert c.execute("SELECT deleted_at FROM crm.interactions WHERE id = %s",
                         (cap["items"][0]["did"]["touch"],)).fetchone()["deleted_at"]


def test_now_full_falls_back_to_today(dela, at):
    seed(dela)
    today = db_today(dela)
    at(dt.datetime(today.year, today.month, today.day, 10, 30, tzinfo=capture.MSK))  # «Сейчас» — день сервера
    for i in range(store.NOW_MAX):
        ops(dela, "sasha", {"op": "task.create", "task": {"title": f"Дело {i}", "focus_on": db_today(dela).isoformat()}})
    cap = say(dela, "в сейчас разобрать почту")
    it = cap["items"][0]
    assert it["bucket"] == "now" and it["line"] == "В «Сейчас» места нет — на сегодня: разобрать почту"
    assert task(dela, it["task"]["id"])["due_date"] == db_today(dela)


# ── Напоминание — отдельно от кучи ──────────────────────────────────────


def test_reminder_not_in_lists_but_on_today(dela):
    today = db_today(dela)
    now_iso = lambda d, h: f"{d.isoformat()}T{h:02d}:00:00+03:00"  # noqa: E731
    r1 = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Отправить счёт", "kind": "remind",
                                                          "remind_at": now_iso(today, 12)}})[0]["task"]
    r2 = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Вчерашнее", "kind": "remind",
                                                          "remind_at": now_iso(today - dt.timedelta(days=1), 12)}})[0]["task"]
    r3 = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Дома", "kind": "remind", "remind_place": "Дом"}})[0]["task"]
    r4 = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Завтрашнее", "kind": "remind",
                                                          "remind_at": now_iso(today + dt.timedelta(days=1), 9)}})[0]["task"]
    # Прошло больше суток и не отмечено — не на «Сегодня» (иначе висело бы бессрочно), а во «Всех делах» группой
    # «прошли — не отмечено» (проверка 16.10.2026).
    r5 = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Позавчерашнее", "kind": "remind",
                                                          "remind_at": now_iso(today - dt.timedelta(days=2), 12)}})[0]["task"]
    t = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Дело на завтра с напоминанием сегодня",
                                                         "due_date": (today + dt.timedelta(days=1)).isoformat(),
                                                         "remind_at": now_iso(today, 15)}})[0]["task"]
    assert r1["kind"] == "remind" and r1["due_date"] is None
    v = store.view(dela, "sasha", "today")
    rem = [x["title"] for x in v["reminders"]]
    assert rem == ["Вчерашнее", "Отправить счёт", "Дело на завтра с напоминанием сегодня", "Дома"], rem
    every = [x["id"] for g in store.view(dela, "sasha", "all")["groups"] for x in g["items"]]
    assert r1["id"] not in every and r4["id"] not in every and t["id"] in every
    rv = store.view(dela, "sasha", "reminders")
    assert [x["title"] for x in rv["items"]] == ["Позавчерашнее", "Вчерашнее", "Отправить счёт", "Завтрашнее", "Дома"]
    assert rv["stale"] == [r5["id"]]
    ops(dela, "sasha", {"op": "task.done", "id": r1["id"]})
    stats = store.view(dela, "sasha", "stats")
    assert stats is not None  # очков за напоминание нет — проверено запросом stats (kind = 'task')
    # Пустые время и место — напоминание ни о чём становится делом «потом».
    empty = ops(dela, "sasha", {"op": "task.set", "id": r2["id"], "set": {"remind_at": None}})[0]["task"]
    assert empty["kind"] == "task" and empty["back_on"]
    # Срок — уже дело с напоминанием.
    dated = ops(dela, "sasha", {"op": "task.set", "id": r3["id"], "set": {"due_date": today.isoformat()}})[0]["task"]
    assert dated["kind"] == "task"


def test_back_today_and_night_shift(dela):
    today = db_today(dela)
    t = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Позвонить Ивану", "back_on": today.isoformat(),
                                                         "back_said": "на неделе"}})[0]["task"]
    old = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Старое потом",
                                                           "back_on": (today - dt.timedelta(days=1)).isoformat()}})[0]["task"]
    assert {x["id"] for x in store.view(dela, "sasha", "today")["back"]} == {t["id"], old["id"]}
    assert capture.back_shift(dela) == 1
    moved = task(dela, old["id"])
    assert moved["back_n"] == 1 and moved["back_on"].weekday() == 0 and moved["back_on"] >= today


def test_phone_shows_bot_waits_ten_minutes(dela):
    """Бот — запасной: ждёт отметки телефона PHONE_GRACE_MIN = 10 минут (с 16.10.2026; было 3 — телефон показал, а
    отметка без сети не успевала, и бот присылал то же второй раз)."""
    assert remind.PHONE_GRACE_MIN == 10
    with dela_db.session(dela, "system", "test") as c:
        c.execute("UPDATE crm.users SET telegram_id = 111 WHERE id = 'sasha'")
    ago = lambda m: remind.iso(remind.now() - dt.timedelta(minutes=m))  # noqa: E731
    fresh = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Свежее", "kind": "remind", "remind_at": ago(5)}})[0]["task"]
    late = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Телефон молчит", "kind": "remind",
                                                            "remind_at": ago(12)}})[0]["task"]
    due = lambda: {r["title"]: r for r in remind.due(dela)["reminders"]}  # noqa: E731
    assert set(due()) == {"Свежее", "Телефон молчит"}  # телефон ещё не включил показ — бот сразу
    ops(dela, "sasha", {"op": "user.settings", "settings": {"remind_phone": {"on": True}}})
    got = due()
    assert set(got) == {"Телефон молчит"} and got["Телефон молчит"]["phone"] is True
    shown = ops(dela, "sasha", {"op": "task.reminded", "id": late["id"], "via": "phone",
                                "remind_at": late["remind_at"]})[0]
    assert shown["ok"] and shown["task"]["reminded_via"] == "phone" and not due()
    # Чужое дело телефон не отмечает.
    nat = ops(dela, "natasha", {"op": "task.reminded", "id": fresh["id"], "via": "phone"})[0]
    assert not nat["ok"]
    bot = store.apply_ops(dela, "sasha", [{"op": "task.reminded", "id": fresh["id"]}], store.REMIND_BOT, BOT)["results"][0]
    assert bot["ok"] and bot["task"]["reminded_via"] == "bot"
    assert remind.state({**bot["task"]}, db_today(dela)).startswith("напоминание отправлено в Telegram")
    assert remind.state({"remind_at": ago(-60)}, db_today(dela), phone=True).startswith("напомню на телефоне")


# ── Идеи ────────────────────────────────────────────────────────────────


def test_ideas_only_owner_sync_and_merge(dela, at):
    ids = seed(dela)
    cap = say(dela, "идея: предложить Ивану дашборд")
    idea_id = cap["items"][0]["idea_id"]
    assert str(cap["items"][0].get("person_id")) == ids["ivan"]
    full = store.sync(dela, "sasha", 0)
    assert [x["id"] for x in full["ideas"]] == [idea_id] and "ideas" in full["features"]
    assert store.sync(dela, "natasha", 0)["ideas"] == []
    with dela_db.session(dela, "natasha") as c:
        assert not c.execute("SELECT 1 FROM crm.ideas").fetchone()
    assert [x["id"] for x in store.view(dela, "sasha", "ideas", person_id=ids["ivan"])["items"]] == [idea_id]
    dup = ops(dela, "sasha", {"op": "person.create", "data": {"name": "Ваня Тестов"}})[0]["row"]["id"]
    ops(dela, "sasha", {"op": "idea.set", "id": idea_id, "set": {"person_id": dup}})
    r = ops(dela, "sasha", {"op": "person.merge", "id": dup, "into": ids["ivan"]})[0]
    assert r["ok"], r
    with dela_db.session(dela, "sasha") as c:
        assert str(c.execute("SELECT person_id FROM crm.ideas WHERE id = %s", (idea_id,)).fetchone()["person_id"]) == ids["ivan"]
    gone = ops(dela, "sasha", {"op": "idea.set", "id": idea_id, "set": {"status": "dropped"}})[0]["idea"]
    assert gone["done_at"] and store.view(dela, "sasha", "ideas")["items"] == []


# ── Строка веба: правило до Claude; узкие токены ────────────────────────


def test_web_line_rule_without_claude(dela, at):
    seed(dela)

    def no_claude(*a):
        raise AssertionError("Claude не нужен")

    out = ask.run(dela, "sasha", "напомни в 12 в Контуре отправить счёт", {"title": "Сейчас"}, "", None,
                  ask_fn=no_claude, parse_fn=no_claude)
    assert out["route"] == "capture" and out["capture"]["items"][0]["bucket"] == "remind"
    assert out["tasks"][0]["kind"] == "remind"
    # В разговоре — правило молчит: «это» и экран важнее.
    with pytest.raises(ask.AskError):
        ask.run(dela, "sasha", "купить магний", {"title": "Сейчас", "history": [{"said": "x", "reply": "y", "done": ""}]},
                "", None)


def test_web_line_model_not_taken_by_loop(dela, at):
    """Строка веба раскладывает моделью сама (say_now, route new): фоновый цикл той же службы её наговорку не берёт,
    пока строка ждёт Claude, — иначе два запроса и два набора дел (сквозной тест 15.10.2026: цикл будится каждые 5 с,
    а Opus high думает 5–15 с). Сбой и «Повторить» — уже циклу."""
    seed(dela)
    calls, loop_calls = [], []

    def fake(head, user_text):
        calls.append(user_text)
        assert capture.tick(dela, ask_fn=lambda h, u: loop_calls.append(u)) == 0  # тик посреди запроса
        return _model(tasks=[{"title": "Подготовить счёт Ромашке"}])

    out = capture.say_now(dela, "sasha", "фраза без формы правила про счёт", "web", "", None, ask_fn=fake, skip_rule=True)
    assert out["status"] == "done" and len(calls) == 1 and loop_calls == []
    assert [x["bucket"] for x in out["items"]] == ["back"]

    def boom(head, user_text):
        raise capture.CaptureError("Claude отказался разбирать эту фразу")

    ref = str(uuid.uuid4())
    with pytest.raises(capture.CaptureError):
        capture.say_now(dela, "sasha", "ещё фраза без формы правила", "web", "", None, ask_fn=boom, skip_rule=True, ref=ref)
    assert move(dela, ref, "retry", item=-1)["status"] == "pending"
    assert capture.tick(dela, ask_fn=lambda h, u: _model(tasks=[{"title": "Ещё фраза"}])) == 1
    assert store.view(dela, "sasha", "capture", ref=ref)["capture"]["status"] == "done"


def test_web_line_without_key_rule_answers(dela):
    h = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'device', 'веб')}"}
    client = TestClient(api.build(Config(db_url=dela)))
    r = client.post("/api/ask", headers=h, json={"text": "купить магний", "scope": {"title": "Сейчас"}})
    assert r.status_code == 202, r.text
    got = client.get(f"/api/ask/{r.json()['job']}", headers=h).json()
    assert got["status"] == "done" and got["route"] == "capture" and got["capture"]["items"][0]["bucket"] == "back"


def test_web_line_rule_outside_quota_and_once(dela, monkeypatch):
    """Поезд 16.10.2026 (захват поверх суточного предела вагона 1): фраза, которую узнаёт правило, раскладывается и у
    не-владельца, чей предел правок словами кончился, — Claude не зовётся, считать нечего; и предел она не тратит.
    Не узнало — 429, как было. С ключом правило спрашивают один раз: ask.run второй раз его не зовёт (rule_tried)."""
    from pravka_dela import guard

    guard._caps.update(at=-guard.CAPS_EVERY, value=None)
    h = {"Authorization": f"Bearer {tokens.issue(dela, 'natasha', 'device', 'телефон-наташи-захват')}"}
    for _ in range(40):
        guard.quota(dela, "natasha", "ask")
    seen = {}

    def fake_run(url, user, text, scope, key, proxy, via="web", actor=None, **_):
        seen["scope"] = scope
        return {"route": "edit", "reply": "ок", "changed": [], "tasks": [], "decided": [], "crm": [], "notes": [], "errors": []}

    with TestClient(api.build(Config(db_url=dela, anthropic_key="sk-test"))) as client:
        r = client.post("/api/ask", headers=h, json={"text": "купить магний", "scope": {"title": "Сейчас"}})
        assert r.status_code == 202, r.text
        got = client.get(f"/api/ask/{r.json()['job']}", headers=h).json()
        assert got["status"] == "done" and got["route"] == "capture" and got["capture"]["items"][0]["bucket"] == "back"
        r = client.post("/api/ask", headers=h, json={"text": "перенеси всё на завтра", "scope": {"title": "Сейчас"}})
        assert r.status_code == 429 and "40 из 40" in r.json()["error"]
    # Владелец: не узнало — Claude (ask.run) с пометкой rule_tried; с ней ask.run правило второй раз не спрашивает.
    monkeypatch.setattr(ask, "run", fake_run)
    own = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'device', 'веб-захват')}"}
    with TestClient(api.build(Config(db_url=dela, anthropic_key="sk-test"))) as client:
        jid = client.post("/api/ask", headers=own, json={"text": "перенеси всё на завтра", "scope": {"title": "Сейчас"}}).json()["job"]
        for _ in range(50):
            if client.get(f"/api/ask/{jid}", headers=own).json()["status"] != "run":
                break
    assert seen["scope"]["rule_tried"] is True
    monkeypatch.undo()
    assert ask.rule_first(dela, "sasha", "купить хлеб", {"title": "Сейчас", "rule_tried": True}) is None
    assert ask.rule_first(dela, "sasha", "купить хлеб", {"title": "Сейчас"})["route"] == "capture"


def test_model_quota_for_non_owner(dela, at):
    """Поезд 16.10.2026 (стык вагонов 1 и 4, проверка): «Д», «сказать», «З» и «Повторить» не-владельца идут к модели
    сервера фоновым разбором — это разбор суточного предела (user_day.parse, правило 24), один на фразу: повтор после
    сбоя сети — не новый разбор. Сверх предела — «не разложил» словами предела, модель не зовётся; «Повторить» — снова
    разбор. Владелец — без предела."""
    from pravka_dela import guard

    guard._caps.update(at=-guard.CAPS_EVERY, value=None)
    seed(dela)
    calls = []

    def fake(h, u):
        calls.append(u)
        return _model(tasks=[{"title": "Разобрать разное"}])

    def blip(h, u):
        calls.append(u)
        raise _conn_error()

    def used(user="natasha"):
        with dela_db.session(dela, "system", "test") as c:
            row = c.execute("SELECT value FROM crm.state WHERE key = %s", (guard.QUOTA,)).fetchone()
        return (((row or {}).get("value") or {}).get("users") or {}).get(user, {}).get("parse", 0)

    cap = say(dela, "что-то длинное и непонятное про разное", user="natasha")
    assert capture.tick(dela, ask_fn=blip) == 1 and used() == 1
    with dela_db.session(dela, "system", "test") as c:
        c.execute("UPDATE tasks.dictations SET capture_result = capture_result || jsonb_build_object('retry_at', "
                  "now() - interval '1 second') WHERE id = %s", ("capture:" + cap["ref"],))
    assert capture.tick(dela, ask_fn=fake) == 1 and used() == 1 and len(calls) == 2  # повтор после сбоя — не новый разбор
    assert store.view(dela, "natasha", "capture", ref=cap["ref"])["capture"]["status"] == "done"
    for _ in range(guard.caps(dela)["user_day"]["parse"] - 1):
        guard.quota(dela, "natasha", "parse")
    cap = say(dela, "ещё одна длинная непонятная фраза", user="natasha")
    assert capture.tick(dela, ask_fn=fake) == 1 and len(calls) == 2  # сверх предела модель не звалась
    got = store.view(dela, "natasha", "capture", ref=cap["ref"])["capture"]
    assert got["status"] == "error" and "20 из 20 разборов" in got["error"] and got["head"].startswith("Не разложил: На сегодня")
    assert [c["label"] for c in got["chips"]] == ["Повторить", "Дело потом"]
    r = ops(dela, "natasha", {"op": "capture.move", "ref": cap["ref"], "item": -1, "to": "retry"})[0]
    assert r["ok"] and r["capture"]["status"] == "pending"
    capture.tick(dela, ask_fn=fake)
    assert len(calls) == 2 and store.view(dela, "natasha", "capture", ref=cap["ref"])["capture"]["status"] == "error"
    # Владелец: без предела.
    own = say(dela, "что-то длинное и непонятное про разное")
    assert capture.tick(dela, ask_fn=fake) == 1 and len(calls) == 3 and used("sasha") == 0
    assert store.view(dela, "sasha", "capture", ref=own["ref"])["capture"]["status"] == "done"


def test_rule_leaves_nesting_to_claude(dela, at):
    """Поезд 16.10.2026 (вагоны 2 и 4): слова вложенности — «вынуть … из …», «вложи … в …», «для этого …», «к делу про
    …» — правило захвата не берёт новым делом: строка веба отдаёт их Claude (ask.run: into, step), «Д» — модели."""
    seed(dela)
    calls = []

    def fake(system, message):
        calls.append(message)
        return {"route": "edit", "reply": "ок", "changes": [], "create": [], "suggestions": [], "card": [], "refs": []}

    for text in ("вынуть звонок в банк из сверки за сентябрь", "положи звонок в банк в дело про сверку",
                 "для этого позвонить в банк", "к делу про сверку: позвонить в банк"):
        assert capture.nest_said(text), text
        out = ask.run(dela, "sasha", text, {"title": "Сейчас"}, "", None, ask_fn=fake)
        assert out["route"] == "edit", text
        assert say(dela, text, lane="rule")["status"] == "none", text
    assert len(calls) == 4
    with dela_db.session(dela, "system", "t") as c:
        assert not c.execute("SELECT 1 FROM tasks.tasks WHERE title ILIKE 'вынуть%%' OR title ILIKE 'положи%%'").fetchone()


def test_narrow_tokens_cannot_capture(dela):
    client = TestClient(api.build(Config(db_url=dela)))
    for name in ("dengi", "eda", "dom"):
        h = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'service', name)}"}
        r = client.post("/api/ops", headers=h, json={"ops": [{"op": "capture.say", "text": "купить магний", "ref": "x"}]})
        assert r.status_code == 403, (name, r.text)


def test_web_line_edit_commands_go_to_claude(dela, at):
    """Строка веба — ещё и правка экрана: «перенести на завтра», «эти — в сейчас» правилу захвата не отдаются (иначе
    «перенести» стало бы новым делом), а «купить магний» — отдаётся."""
    seed(dela)
    calls = []

    def fake(system, message):
        calls.append(message)
        return {"route": "edit", "reply": "ок", "changes": [], "create": [], "suggestions": [], "card": [], "refs": []}

    for text in ("перенести на завтра", "закрыть все просроченные", "эти разобрать сегодня"):
        out = ask.run(dela, "sasha", text, {"title": "Сейчас"}, "", None, ask_fn=fake)
        assert out["route"] == "edit", text
    assert len(calls) == 3


def test_rule_first_with_open_card(dela, at, caplog):
    """Отладка 16.10.2026, запись 14 (правка 04 тяжёлой полосы): «open» больше не в ask.RULE_SKIP. На широком экране
    карточка справа открыта почти всегда, и «купить хлеб» из верхнего поля шло двумя вызовами Opus (строка и захват) —
    около $0,1 и 10–20 с. Теперь простое дело при открытой карточке раскладывает правило ($0, без ключа), а фразу про
    открытое дело — слова «сначала / до этого / ему / туда …», вложенность, правку в любом месте фразы, «сделано» без
    человека, дело одним словом — по-прежнему Claude с карточкой. Без карточки — как раньше."""
    seed(dela)
    card = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Подготовить термшит"}})[0]["task"]
    scope = {"title": "Сейчас", "task_ids": [card["id"]], "open": card["id"]}
    calls = []

    def fake(system, message):
        calls.append(message)
        return {"route": "edit", "reply": "ок", "changes": [], "create": [], "suggestions": [], "card": [], "refs": []}

    def boom(head, user_text):
        raise AssertionError("захват моделью не нужен — правило")

    with caplog.at_level(logging.INFO, logger="dela.ask"):
        out = ask.run(dela, "sasha", "купить хлеб", scope, "", None, ask_fn=fake, parse_fn=boom)
    assert out["route"] == "capture" and calls == [], "Opus не зовётся: ни строка, ни захват"
    [item] = out["capture"]["items"]
    assert item["bucket"] == "back" and out["tasks"][0]["title"] == "Купить хлеб"
    assert out["tasks"][0]["why_id"] is None, "простое дело — не внутри открытого"
    assert "правило при открытой карточке: back" in caplog.text

    def count():
        with dela_db.session(dela, "sasha") as c:
            return c.execute("SELECT count(*) AS n FROM tasks.tasks").fetchone()["n"]

    before = count()
    for text, why in (("до этого надо набросать драфт", "слово «до этого»"), ("позвонить ему", "слово «ему»"),
                      ("сначала позвонить юристу", "слово «сначала»"), ("туда же добавить выписку", "слово «туда»"),
                      ("после этого отправить счёт", "слово «после этого»"), ("вложи в сверку", None),
                      ("перенеси на пятницу", None), ("это в сейчас", None),  # и без карточки: EDIT_FIRST, SCREEN_WORDS
                      ("надо перенести на пятницу", "правка «перенести»"), ("позвонить завтра", "дело одним словом"),
                      ("напомни позвонить в 12", "дело одним словом"),
                      ("сделано, отправил договор", "сделано без человека")):
        caplog.clear()
        with caplog.at_level(logging.INFO, logger="dela"):
            assert ask.rule_first(dela, "sasha", text, scope) is None, text
        assert why is None or f"при открытой карточке молчит ({why})" in caplog.text, (text, caplog.text)
    assert count() == before, "молчание правила ничего не пишет"
    # Названный человек — обычная фраза: правило берёт и при открытой карточке.
    got = ask.rule_first(dela, "sasha", "позвонить Ивану Тестову завтра", scope)
    assert got and got["route"] == "capture" and got["tasks"][0]["title"] == "Позвонить Ивану Тестову"
    # Без карточки — как раньше: строже правило не становится.
    for text in ("позвонить завтра", "сделано, отправил договор"):
        got = ask.rule_first(dela, "sasha", text, {"title": "Сейчас", "task_ids": [card["id"]]})
        assert got and got["route"] == "capture", text
    # Своя строка карточки дела — по-прежнему мимо правила.
    assert ask.rule_first(dela, "sasha", "купить молоко", {"card": "task", "open": card["id"]}) is None

    # Веб без ключа: при открытой карточке простое дело ложится правилом, фраза про карточку — отказ «нет ключа».
    h = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'device', 'веб-открытая-карточка')}"}
    client = TestClient(api.build(Config(db_url=dela)))
    r = client.post("/api/ask", headers=h, json={"text": "купить молоко", "scope": {"title": "Сейчас", "open": card["id"]}})
    assert r.status_code == 202, r.text
    got = client.get(f"/api/ask/{r.json()['job']}", headers=h).json()
    assert got["status"] == "done" and got["route"] == "capture" and got["capture"]["items"][0]["bucket"] == "back"
    r = client.post("/api/ask", headers=h, json={"text": "позвонить ему", "scope": {"title": "Сейчас", "open": card["id"]}})
    assert r.status_code == 422 and "нет ключа" in r.json()["error"]


# ── Тёзки: вопрос в «Подскажи» (проверка 16.10.2026) ─────────────────────


def test_twins_did_asks_and_answer_closes_promise(dela, at):
    """«позвонил Ивану» при двух Иванах: сделанное без человека, кнопка «Какой Иван ▾» и вопрос в «Подскажи». Ответ
    вопросом — то же, что кнопка под строкой: обещание этого Ивана закрыто, касание у него, дело-основа без человека
    отменено (проверка 16.10.2026: «записал сделанным», а «Позвонить Ивану» оставалось открытым)."""
    ids = seed(dela, twin=True)
    promise = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Позвонить Ивану", "ball": "owed",
                                                               "person_id": ids["ivan"]}})[0]["task"]
    cap = say(dela, "позвонил Ивану")
    item = cap["items"][0]
    assert item["bucket"] == "did" and item["twins"] and not item["fields"].get("person_id")
    assert item["line"] == "Сделано: позвонил Ивану — какой Иван? · вопрос в «Подскажи»"
    assert [c["label"] for c in item["chips"]] == ["Какой Иван", "Отменить"]
    assert task(dela, promise["id"])["status"] == "open"
    [q] = _asks(dela)
    assert q["payload"]["ask"] == "«Позвонил Ивану» — какой Иван?" and q["payload"]["capture"] == {"ref": cap["ref"], "item": 0}
    # «Да» без выбора — отказ словами: ответ только кнопкой человека.
    r = ops(dela, "sasha", {"op": "suggestion.decide", "id": str(q["id"]), "decision": "accept"})[0]
    assert not r["ok"] and "какой Иван?" in r["error"]
    i = next(k for k, o in enumerate(q["payload"]["options"]) if o["person_id"] == ids["ivan"])
    r = ops(dela, "sasha", {"op": "suggestion.decide", "id": str(q["id"]), "option": i})[0]
    assert r["ok"], r
    assert task(dela, promise["id"])["status"] == "done"
    assert task(dela, item["task"]["id"])["status"] == "cancelled"  # основа без человека — не нужна
    after = store.view(dela, "sasha", "capture", ref=cap["ref"])["capture"]["items"][0]
    assert after["line"].startswith("Переложил: Сделано: позвонил Ивану · закрыл «Позвонить Ивану» · звонок у Ивана")
    assert [c["label"] for c in after["chips"]] == ["Не закрывать", "Отменить"]
    assert _asks(dela)[0]["status"] == "accepted"
    with dela_db.session(dela, "sasha") as c:
        touch = c.execute("SELECT kind FROM crm.interactions WHERE %s::uuid = ANY (person_ids) AND deleted_at IS NULL",
                          (ids["ivan"],)).fetchone()
    assert touch["kind"] == "call"


def test_twins_note_answer_in_ask_and_cancel_rejects(dela, at):
    ids = seed(dela, twin=True)
    cap = say(dela, "заметка по Ивану: сменил телефон")
    item = cap["items"][0]
    assert item["line"] == "Заметка: сменил телефон — у какого Ивана? · вопрос в «Подскажи»"  # не «у какого Ивану»
    [q] = _asks(dela)
    i = next(k for k, o in enumerate(q["payload"]["options"]) if o["person_id"] == ids["ivan2"])
    assert ops(dela, "sasha", {"op": "suggestion.decide", "id": str(q["id"]), "option": i})[0]["ok"]
    with dela_db.session(dela, "sasha") as c:
        row = c.execute("SELECT person_ids FROM crm.interactions WHERE id = %s", (item["note_id"],)).fetchone()
    assert [str(x) for x in row["person_ids"]] == [ids["ivan2"]]
    # Вторая такая же заметка — «Отменить» под строкой гасит и её вопрос.
    cap2 = say(dela, "заметка по Ивану: уехал в отпуск")
    move(dela, cap2["ref"], "cancel")
    assert [x["status"] for x in _asks(dela)] == ["accepted", "rejected"]


# ── Модель: повтор при сбое сети, два разбора разом ─────────────────────


def _conn_error():
    import anthropic
    import httpx

    return anthropic.APIConnectionError(request=httpx.Request("POST", "https://api.anthropic.com/v1/messages"))


def test_model_network_blip_retries_then_lays_out(dela, at):
    """Сеть к Claude моргнула (VPN) — не «не разложил» сразу, а повтор через 5, 15, 45 с; прошло — раскладка как обычно,
    не прошло трижды — сбой словами (проверка 16.10.2026; правило Ковчега — переживать минуты без VPN)."""
    seed(dela)
    cap = say(dela, "что-то длинное и непонятное про разное")
    ref = "capture:" + cap["ref"]

    def blip(h, u):
        raise _conn_error()

    def due_now():
        with dela_db.session(dela, "system", "test") as c:
            c.execute("UPDATE tasks.dictations SET capture_result = capture_result || "
                      "jsonb_build_object('retry_at', now() - interval '1 second') WHERE id = %s", (ref,))

    assert capture.tick(dela, ask_fn=blip) == 1
    got = store.view(dela, "sasha", "capture", ref=cap["ref"])["capture"]
    assert got["status"] == "pending" and got["tries"] == 1 and got.get("retry_at")
    assert capture.tick(dela, ask_fn=blip) == 0  # пауза ещё не прошла — не трогаем
    due_now()
    assert capture.tick(dela, ask_fn=lambda h, u: _model(tasks=[{"title": "Разобрать разное"}])) == 1
    got = store.view(dela, "sasha", "capture", ref=cap["ref"])["capture"]
    assert got["status"] == "done" and [x["bucket"] for x in got["items"]] == ["back"] and "retry_at" not in got

    cap = say(dela, "ещё одна длинная непонятная фраза")
    ref = "capture:" + cap["ref"]
    for _ in capture.RETRY_PAUSES:
        capture.tick(dela, ask_fn=blip)
        due_now()
    capture.tick(dela, ask_fn=blip)
    got = store.view(dela, "sasha", "capture", ref=cap["ref"])["capture"]
    assert got["status"] == "error" and "APIConnectionError" in got["error"] and "повторял 3 раза" in got["error"]
    assert move(dela, cap["ref"], "retry", item=-1)["status"] == "pending"
    assert "tries" not in store.view(dela, "sasha", "capture", ref=cap["ref"])["capture"]  # «Повторить» — с чистого счёта


def test_model_two_phrases_at_once(dela, at):
    import threading
    import time as _time

    seed(dela)
    refs = [say(dela, f"длинная непонятная фраза номер {n}")["ref"] for n in (1, 2)]
    seen: set[str] = set()

    def slow(h, u):
        seen.add(threading.current_thread().name)
        _time.sleep(0.3)
        return _model(tasks=[{"title": "Дело из фразы"}])

    assert capture.tick(dela, ask_fn=slow) == 2
    assert len(seen) == 2  # два разбора разом — вторая фраза не ждёт первую
    assert all(store.view(dela, "sasha", "capture", ref=r)["capture"]["status"] == "done" for r in refs)


# ── Вложенность голосом (вагон B, 16.10.2026; train\logic\PLAN.md §2.1–2.4, §5.1) ──────────
#
# Саша 15.10: «чтобы я мог свободно делать nesting и говорить дела и они нормально подцеплялись». «Д», «сказать» и «З»
# идут захватом: модель отмечает inside («», «^k» или слова родителя), родителя ищет сервер (nest.resolve, nest.rank),
# сначала nest_fill, потом корзина. Ответы модели — подменённые ($0). Засев — выдуманный (PLAN §5.1).


def _nest_seed(url) -> dict:
    """seed() захвата и дела §5.1: «Договор с Ромашкой» (у клиента, со сроком через 10 дней), «Подготовить КП Ромашке»,
    «Сверка с банком» и «Сверка за сентябрь» (на «сверку» — двусмысленно), «Закрыть сделку с Альфой», цель дерева."""
    ids = seed(url)
    due = db_today(url) + dt.timedelta(days=10)
    for key, title, kw in (("contract", "Договор с Ромашкой", {"project_id": ids["romashka"], "due_date": due.isoformat()}),
                           ("kp", "Подготовить КП Ромашке", {"project_id": ids["romashka"]}),
                           ("bank", "Сверка с банком", {}), ("sept", "Сверка за сентябрь", {}),
                           ("alfa", "Закрыть сделку с Альфой", {}), ("goal", "Расту в профессии", {"goal": True})):
        t = ops(url, "sasha", {"op": "task.create", "task": {"title": title, **kw}})[0]["task"]
        ids[key], ids[key + "#"] = str(t["id"]), t["num"]
    ids["due"] = due
    return ids


def _laid(url, text, data, frm="d", edit_fn=None):
    """Фраза из «Д» → наговорка ждёт модели → подменённый ответ (data) → итог захвата."""
    cap = say(url, text, frm=frm)
    assert cap["status"] == "pending", cap  # правило молчит: вложенность — модели
    assert capture.tick(url, ask_fn=lambda h, u: data, edit_fn=edit_fn) == 1
    return store.view(url, "sasha", "capture", ref=cap["ref"])["capture"]


def _on(d: dt.date, today: dt.date) -> str:
    day = capture.day_on(d, today)
    return day[0].lower() + day[1:]


def test_nest_said_and_edit_words_leave_rule_silent(dela):
    """Признаки вложенности §2.1 — правило молчит (модель отметит inside); одиночные «в рамках проекта», «для Пети» и
    «положить ключи в сейф» — правилом, как раньше. Правка существующего («вынь …», «положи X в Y», «это шаг к …») —
    edit_said: из «Д» её разбирает строка Claude."""
    ids = _nest_seed(dela)
    with dela_db.session(dela, "sasha") as c:
        for text in ("для договора с Ромашкой сначала запросить реквизиты", "это часть сверки с банком",
                     "внутри подготовки КП: собрать цифры", "сначала позвонить в банк, потом сверка",
                     "положить звонок в банк в дело про сверку за сентябрь", "положить звонок в банк в сверку за сентябрь",
                     "вынуть звонок в банк из сверки за сентябрь", "это шаг к закрытию сделки с Альфой"):
            assert capture.rule(c, "sasha", text, NOWS["a"]) is None, text
        for text in ("отправить КП в рамках проекта Ромашка", "купить подарок для Пети", "положить ключи в сейф"):
            got = capture.rule(c, "sasha", text, NOWS["a"])
            assert got and got["items"][0]["bucket"] == "back", text
        for text in ("вынь звонок в банк из сверки", "надо положи это в сверку", "переложи звонок в банк в дело про сверку",
                     "положить звонок в банк в «Сверка с банком»", "это шаг к закрытию сделки с Альфой",
                     "это часть сверки с банком"):
            assert capture.edit_said(text, c, "sasha"), text
        assert capture.edit_said("положить звонок в банк в сверку за сентябрь", c, "sasha")  # nest.rank узнаёт дело
        assert not capture.edit_said("положить звонок в банк в сверку за сентябрь")  # без базы — только явное «в дело»
        for text in ("положить ключи в сейф", "вложить деньги в фонд", "вынуть ключи из сумки", "купить подарок для Пети",
                     "к делу про сверку: позвонить в банк", "для договора с Ромашкой сначала запросить реквизиты"):
            assert not capture.edit_said(text, c, "sasha"), text
    assert {"вложить", "вложи", "положить", "положи", "переложить", "переложи", "вынуть", "вынь", "засунь"} <= ask.EDIT_FIRST
    assert {"step", "kstati"} <= set(ask.RULE_SKIP)
    assert ids["contract"]


def test_nest_inside_by_words_takes_parent_due_and_moves(dela, at):
    """§5.1 п. 1: «для договора с Ромашкой сначала запросить реквизиты» — дело внутри «Договор с Ромашкой», срок и
    клиент — от него; строка «Внутри «…»: …», кнопки «Вынуть» · «Другое ▾» · «Отменить». «Вынуть» — само по себе (срок
    остаётся), «Другое ▾» (into, at = id) — внутрь другого похожего."""
    ids = _nest_seed(dela)
    got = _laid(dela, "для договора с Ромашкой сначала запросить реквизиты", _model(tasks=[
        {"title": "Запросить реквизиты", "project": "ООО Ромашка", "inside": "договор с Ромашкой"}]))
    x = got["items"][0]
    assert x["bucket"] == "day" and x["nest"]["kind"] == "task" and x["nest"]["why_id"] == ids["contract"], x
    assert x["line"] == f"Внутри «Договор с Ромашкой»: запросить реквизиты · {_on(ids['due'], NOWS['a'].date())}", x["line"]
    t = task(dela, x["task"]["id"])
    assert (str(t["why_id"]), t["due_date"], str(t["project_id"]), t["back_on"]) == (ids["contract"], ids["due"], ids["romashka"], None)
    labels = [c["label"] for c in x["chips"]]
    assert labels == ["Вынуть", "Другое", "Отменить"], labels
    other = x["chips"][1]
    assert other["move"] == "into" and ids["kp"] in [o["at"] for o in other["options"]]
    assert ids["contract"] not in [o["at"] for o in other["options"]]
    out = move(dela, got["ref"], "out")
    t = task(dela, x["task"]["id"])
    assert t["why_id"] is None and t["due_date"] == ids["due"], t  # вынули — срок свой, остаётся
    assert out["head"].startswith("Переложил: На ") and "Внутри" not in out["head"], out["head"]
    assert [c["label"] for c in out["items"][0]["chips"]][0] == "Вложить в…"
    into = move(dela, got["ref"], "into", at=ids["kp"])
    assert str(task(dela, x["task"]["id"])["why_id"]) == ids["kp"]
    assert into["head"].startswith("Переложил: Внутри «Подготовить КП Ромашке»: запросить реквизиты"), into["head"]
    bad = ops(dela, "sasha", {"op": "capture.move", "ref": got["ref"], "item": 0, "to": "into", "at": ids["goal"] + "x"})[0]
    assert not bad["ok"] and "не понял" in bad["error"]


def test_nest_three_steps_in_order(dela, at):
    """§5.1 п. 2: «внутри «подготовить КП Ромашке»: собрать цифры, согласовать с Ольгой, отправить» — три дела внутри,
    места 1–3 по порядку сказанного, клиент — от КП, «потом» нет (вернутся с КП). Голова — «Завёл 3 внутри «…»: …»."""
    ids = _nest_seed(dela)
    got = _laid(dela, "внутри «подготовить КП Ромашке»: собрать цифры, согласовать с Ольгой, отправить", _model(tasks=[
        {"title": "Собрать цифры", "inside": "подготовить КП Ромашке"},
        {"title": "Согласовать с Ольгой", "person": "Ольга", "inside": "подготовить КП Ромашке"},
        {"title": "Отправить", "inside": "подготовить КП Ромашке"}]))
    assert got["head"] == "Завёл 3 внутри «Подготовить КП Ромашке»: собрать цифры, согласовать с Ольгой, отправить", got["head"]
    rows = [task(dela, x["task"]["id"]) for x in got["items"]]
    assert [x["bucket"] for x in got["items"]] == ["inside"] * 3
    assert [str(r["why_id"]) for r in rows] == [ids["kp"]] * 3
    assert [r["pos"] for r in rows] == [1, 2, 3]
    assert all(str(r["project_id"]) == ids["romashka"] and r["back_on"] is None and r["due_date"] is None for r in rows)
    assert str(rows[1]["person_id"]) == ids["olga"]  # названное сильнее (у КП человека нет)
    assert got["items"][0]["line"] == "Внутри «Подготовить КП Ромашке»: собрать цифры"


def test_nest_two_candidates_stand_apart_with_buttons(dela, at):
    """§5.1 п. 3: «к делу про сверку: позвонить в банк» при двух сверках — дело стоит отдельно, «— к какому делу?»,
    кнопки «В «Сверка с банком»» · «В «Сверка за сентябрь»» · «Отменить»; кнопка кладёт внутрь выбранного."""
    ids = _nest_seed(dela)
    got = _laid(dela, "к делу про сверку: позвонить в банк", _model(tasks=[{"title": "Позвонить в банк", "inside": "сверка"}]))
    x = got["items"][0]
    assert x["bucket"] == "back" and x["line"] == "Потом: позвонить в банк · верну в пятницу 16.10 — к какому делу?", x["line"]
    assert task(dela, x["task"]["id"])["why_id"] is None
    assert [c["label"] for c in x["chips"]] == ["В «Сверка с банком»", "В «Сверка за сентябрь»", "Отменить"]
    assert x["chips"][1]["move"] == f"into:{ids['sept']}"
    after = move(dela, got["ref"], f"into:{ids['sept']}")
    assert str(task(dela, x["task"]["id"])["why_id"]) == ids["sept"]
    assert after["head"].startswith("Переложил: Внутри «Сверка за сентябрь»: позвонить в банк"), after["head"]
    assert [c["label"] for c in after["items"][0]["chips"]][:1] == ["Вынуть"]


def test_nest_first_then_and_apart(dela, at):
    """§5.1 п. 5 и §2.3: «сначала позвонить Ивану, потом отправить ему договор» — X внутри Y (Y — новое дело того же
    ответа, ложится первым), строка «Позвонить Ивану → потом «…»», кнопка «Отдельно» разделяет (X — «потом» само по
    себе). Человек и клиент — от родителя, где не названы."""
    ids = _nest_seed(dela)
    got = _laid(dela, "сначала позвонить Ивану, потом отправить ему договор", _model(tasks=[
        {"title": "Позвонить Ивану", "person": "Иван", "inside": "^2"},
        {"title": "Отправить Ивану договор", "person": "Иван"}]))
    x, y = got["items"]
    assert x["line"] == "Позвонить Ивану → потом «Отправить Ивану договор»", x["line"]
    assert [c["label"] for c in x["chips"]] == ["Отдельно", "Отменить"]
    tx, ty = task(dela, x["task"]["id"]), task(dela, y["task"]["id"])
    assert str(tx["why_id"]) == str(ty["id"]) and tx["back_on"] is None and tx["due_date"] is None
    assert str(ty["project_id"]) == ids["romashka"] and str(tx["project_id"]) == ids["romashka"]  # клиент Ивана — и внутри
    apart = move(dela, got["ref"], "out", item=0)
    tx = task(dela, x["task"]["id"])
    assert tx["why_id"] is None and tx["back_on"] is not None  # отдельно — «потом», а не плоско без дня
    assert apart["items"][0]["line"].startswith("Переложил: Потом: позвонить Ивану"), apart["items"][0]["line"]
    back = apart["items"][0]["chips"][0]
    assert back["label"] == "Вложить в…" and back["options"][0]["at"] == str(ty["id"])  # вернуть — одной кнопкой


def test_nest_before_this_inside_new_parent(dela, at):
    """§5.1 п. 8: «по Альфе: подготовить тизер, до этого собрать отчётность» — отчётность внутри тизера (родитель сказан
    первым — «Внутри «…»»), кнопка «Отдельно»."""
    _nest_seed(dela)
    got = _laid(dela, "по Альфе: подготовить тизер, до этого собрать отчётность", _model(tasks=[
        {"title": "Подготовить тизер"}, {"title": "Собрать отчётность", "inside": "^1"}]))
    p_, k = got["items"]
    assert k["line"] == "Внутри «Подготовить тизер»: собрать отчётность", k["line"]
    assert [c["label"] for c in k["chips"]] == ["Отдельно", "Отменить"]
    assert str(task(dela, k["task"]["id"])["why_id"]) == str(p_["task"]["id"])


def test_nest_reminder_becomes_task_inside_and_raises_parent(dela, at):
    """§5.1 п. 12: «для сверки завтра в 10 напомни позвонить в банк» — не напоминание, а дело с напоминанием внутри
    сверки (напоминание не вкладывается); сделано — родитель поднимается на сегодня."""
    ids = _nest_seed(dela)
    got = _laid(dela, "для сверки завтра в 10 напомни позвонить в банк", _model(tasks=[
        {"title": "Позвонить в банк", "due": "2026-10-16", "remind_at": "2026-10-16 10:00", "inside": "сверка с банком"}]))
    x = got["items"][0]
    assert x["bucket"] == "task_remind", x
    assert x["line"] == "Внутри «Сверка с банком»: позвонить в банк · на завтра · напомню завтра в 10:00", x["line"]
    t = task(dela, x["task"]["id"])
    assert (t["kind"], str(t["why_id"]), t["due_date"].isoformat()) == ("task", ids["bank"], "2026-10-16")
    assert same_at(t["remind_at"], "2026-10-16T10:00:00+03:00")
    done = ops(dela, "sasha", {"op": "task.done", "id": x["task"]["id"]})[0]
    assert done["ok"] and done.get("raised"), done  # всё внутри сделано — сверка поднялась
    assert task(dela, ids["bank"])["due_date"] == db_today(dela)


def test_nest_parent_due_beats_later_and_no_due_means_inside(dela, at):
    """«Потом» не перебивает срок родителя (поезд, §1.4 п. 3): у договора срок — дело внутри берёт его, back модели
    мимо; у сверки срока нет — дело внутри без срока и без «потом» (корзина inside: вернётся с родителем)."""
    ids = _nest_seed(dela)
    got = _laid(dela, "много про договор и сверку", _model(tasks=[
        {"title": "Запросить реквизиты", "back": "2026-10-20", "inside": "договор с Ромашкой"},
        {"title": "Выгрузить выписку", "inside": "сверка с банком"}]))
    a, b = (task(dela, x["task"]["id"]) for x in got["items"])
    assert (a["due_date"], a["back_on"], str(a["why_id"])) == (ids["due"], None, ids["contract"])
    assert (b["due_date"], b["back_on"], str(b["why_id"])) == (None, None, ids["bank"])
    assert [x["bucket"] for x in got["items"]] == ["day", "inside"]
    assert got["items"][1]["line"] == "Внутри «Сверка с банком»: выгрузить выписку"


def test_nest_not_found_stands_apart_with_put_into(dela, at):
    """Родителя по словам нет — дело отдельно, «— дела «…» не нашёл, завёл отдельно», первая кнопка «Вложить в… ▾» —
    дела того же клиента."""
    ids = _nest_seed(dela)
    got = _laid(dela, "к делу про отчёт для налоговой: собрать акты", _model(tasks=[
        {"title": "Собрать акты", "project": "ООО Ромашка", "inside": "отчёт для налоговой"}]))
    x = got["items"][0]
    assert x["line"].endswith("— дела «отчёт для налоговой» не нашёл, завёл отдельно"), x["line"]
    assert task(dela, x["task"]["id"])["why_id"] is None
    first = x["chips"][0]
    assert first["label"] == "Вложить в…" and {ids["contract"], ids["kp"]} <= {o["at"] for o in first["options"]}


def test_nest_rule_flat_gets_into_button_and_twin(dela, at):
    """§5.1 п. 9: «запросить реквизиты для договора с Ромашкой» — правило кладёт плоско («потом»), а у хвоста «для …»
    уверенное дело — первой кнопкой «В «Договор с Ромашкой»»; она кладёт внутрь, срок — от договора, «потом» снимается.
    Контроль: «купить подарок для Пети» — кнопки прежние. У человека уже есть похожее открытое дело (дубль, запись 5) —
    «Внутрь «…»»; «сегодня отправить Ивану модель» при «Отправить договор Ивану» — не дубль."""
    ids = _nest_seed(dela)
    cap = say(dela, "запросить реквизиты для договора с Ромашкой")
    x = cap["items"][0]
    assert cap["by"] == "rule" and x["bucket"] == "back" and task(dela, x["task"]["id"])["why_id"] is None
    assert [c["label"] for c in x["chips"]] == ["В «Договор с Ромашкой»", "Сегодня", "Отменить"]
    assert x["line"] == "Потом: запросить реквизиты для договора с Ромашкой · верну в пятницу 16.10"  # строка обычная
    got = move(dela, cap["ref"], f"into:{ids['contract']}")
    t = task(dela, x["task"]["id"])
    assert (str(t["why_id"]), t["due_date"], t["back_on"]) == (ids["contract"], ids["due"], None)
    assert got["head"].startswith("Переложил: Внутри «Договор с Ромашкой»: запросить реквизиты"), got["head"]
    gift = say(dela, "купить подарок для Пети")
    assert [c["label"] for c in gift["items"][0]["chips"]] == ["Сегодня", "В «Сейчас»", "Отменить"]
    # «в рамках проекта Ромашка» — клиент, а не родитель: дела Ромашки по имени клиента «уверенными» не становятся.
    kp = say(dela, "отправить КП в рамках проекта Ромашка")
    assert [c["label"] for c in kp["items"][0]["chips"]] == ["Сегодня", "В «Сейчас»", "Отменить"], kp["items"][0]["chips"]
    olga = say(dela, "позвонить Ольге на неделе")
    assert olga["by"] == "rule" and olga["items"][0]["chips"][0]["label"] == "Внутрь «Позвонить Ольге»", olga["items"][0]
    assert olga["items"][0]["chips"][0]["move"] == f"into:{ids['call_olga']}"
    ivan = say(dela, "сегодня отправить Ивану модель")
    assert not any(c["label"].startswith("Внутрь") for c in ivan["items"][0]["chips"]), ivan["items"][0]["chips"]


def test_nest_edit_words_from_d_go_to_claude_line(dela, at):
    """§5.1 п. 7 и п. 11 из «Д»: «вынь …», «положи X в Y» — правка существующего: строка Claude (ask.run со scope
    «Сегодня» — Claude видит все открытые дела), модель захвата не зовётся; строка — ответ сервера, «Отменить» — как
    было. Claude решил, что это новые дела, — раскладывает модель захвата в ту же наговорку."""
    ids = _nest_seed(dela)
    call = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Позвонить в банк", "why_id": ids["bank"]}})[0]["task"]
    seen = []

    def edit(into):
        def fake(system, message):
            seen.append(message)
            return {"route": "edit", "reply": "", "changes": [{"num": call["num"], "title": "", "notes_add": "",
                                                               "project": "", "deal": "", "person": "", "due": "",
                                                               "due_time": "", "ball": "", "now": "", "estimate_min": 0,
                                                               "labels_add": [], "labels_remove": [], "status": "",
                                                               "into": into}],
                    "create": [], "suggestions": [], "card": [], "refs": []}
        return fake

    def no_model(h, u):
        raise AssertionError("правку существующего модель захвата не разбирает")

    cap = say(dela, "вынь звонок в банк из сверки")
    assert cap["status"] == "pending"
    assert capture.tick(dela, ask_fn=no_model, edit_fn=edit("-")) == 1
    got = store.view(dela, "sasha", "capture", ref=cap["ref"])["capture"]
    assert got["status"] == "done" and got["by"] == "claude", got
    assert "КОМАНДА: вынь звонок в банк из сверки" in seen[0] and "ДРУГИЕ МОИ ОТКРЫТЫЕ ДЕЛА" in seen[0]
    assert "Вынул «Позвонить в банк» из «Сверка с банком»." in got["head"], got["head"]
    assert task(dela, call["id"])["why_id"] is None
    assert [c["label"] for c in got["items"][0]["chips"]] == ["Отменить"]
    # «Положи его в дело про сверку за сентябрь» — «его» — сказанное прямо перед этим (память разговора строки Claude).
    cap2 = say(dela, "положи его в дело про сверку за сентябрь")
    assert capture.tick(dela, ask_fn=no_model, edit_fn=edit(f"#{ids['sept#']}")) == 1
    assert str(task(dela, call["id"])["why_id"]) == ids["sept"]
    assert "РАЗГОВОР" in seen[-1] and "вынь звонок в банк из сверки" in seen[-1], seen[-1][-600:]
    undone = move(dela, cap2["ref"], "cancel")
    assert task(dela, call["id"])["why_id"] is None and undone["head"].startswith("Отменил:"), undone["head"]
    move(dela, cap["ref"], "cancel")
    assert str(task(dela, call["id"])["why_id"]) == ids["bank"]
    # Claude: это новые дела — раскладывает модель захвата, своей наговорки строка не заводит.
    cap = say(dela, "положи деньги на телефон")
    capture.tick(dela, ask_fn=lambda h, u: _model(tasks=[{"title": "Положить деньги на телефон"}]),
                 edit_fn=lambda s, m: {"route": "new", "reply": "", "changes": [], "create": [], "suggestions": [],
                                       "card": [], "refs": []})
    got = store.view(dela, "sasha", "capture", ref=cap["ref"])["capture"]
    assert got["by"] == "model" and got["items"][0]["bucket"] == "back", got
    with dela_db.session(dela, "sasha") as c:
        assert c.execute("SELECT count(*) AS n FROM tasks.dictations WHERE text = 'положи деньги на телефон'").fetchone()["n"] == 1
    # Строка веба такие фразы в захват не шлёт: у неё ask.run и так впереди (EDIT_FIRST).
    assert ask.rule_first(dela, "sasha", "положить звонок в банк в дело про сверку", {"title": "Сейчас"}) is None


def test_nest_inside_in_parse_to_ops(dela):
    """/api/parse и старая Разноска (parse.to_ops): inside — тот же nest.resolve; «^k» — родитель первым, свой id;
    неясное — само по себе (кнопок там нет); поля от родителя — сервер (nest_fill в op_task_create)."""
    from pravka_dela import parse

    ids = _nest_seed(dela)
    data = {"tasks": [{"title": "Собрать отчётность", "inside": "^2"}, {"title": "Подготовить тизер"},
                      {"title": "Запросить реквизиты", "inside": "договор с Ромашкой"},
                      {"title": "Позвонить в банк", "inside": "сверка"}], "notes": []}
    with dela_db.session(dela, "sasha") as c:
        got = parse.to_ops(data, {"projects": {}, "people": {}, "labels": set()}, {}, "web", conn=c, user="sasha")
    by = {o["task"]["title"]: o["task"] for o in got}
    order = [o["task"]["title"] for o in got]
    assert order.index("Подготовить тизер") < order.index("Собрать отчётность")  # родитель — первым
    assert by["Собрать отчётность"]["why_id"] == by["Подготовить тизер"]["id"]
    assert by["Запросить реквизиты"]["why_id"] == ids["contract"] and "why_id" not in by["Позвонить в банк"]
    res = store.apply_ops(dela, "sasha", got, "web")["results"]
    assert all(r["ok"] for r in res), res
    req = next(r["task"] for r in res if r["task"]["title"] == "Запросить реквизиты")
    assert task(dela, req["id"])["due_date"] == ids["due"]
    # Без сессии — как раньше: inside не читается.
    assert all("why_id" not in o["task"] for o in parse.to_ops(data, {"projects": {}, "people": {}, "labels": set()}, {}, "web"))


def test_nest_twin_is_not_the_opposite_task(dela, at):
    """«Внутрь «…»» — только к своему же делу: встречное (жду от Ивана сверку ↔ прислать Ивану сверку) — не дубль
    (replay захвата 16.10: такие пары давали кнопку); своё к «ждёт от тебя» — дубль (один и тот же звонок)."""
    ids = _nest_seed(dela)
    got = _laid(dela, "много всего про Ивана", _model(tasks=[
        {"title": "Прислать Ивану сверку", "person": "Иван", "ball": "owed"},
        {"title": "Иван: прислать сверку", "person": "Иван", "ball": "waiting", "due": "2026-10-20"}]))
    a, b = got["items"]
    assert not any(c["label"].startswith("Внутрь") for c in a["chips"]), a["chips"]  # встречное к «жду от Ивана»
    assert b["chips"][0] == {"label": "Внутрь «Иван: прислать сверку»", "move": f"into:{ids['ivan_sverka']}"}, b["chips"]
