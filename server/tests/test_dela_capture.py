"""Захват (шаг 0 петли, 15.10.2026): фраза одной дверью — правило, модель, корзины, строка, поправка кнопкой.

Владелец: «захват за три секунды, которому я доверяю… „напомни в 12 в Контуре отправить счёт“ — не знал, куда».
Случаи правила — общий файл contract/capture-cases.json (их же гоняют телефон и сквозной тест). Имена выдуманные.
«Сейчас» фразы — подменой capture._said_now: чт 15.10.2026 10:30 и 14:00 по Москве.
"""

from __future__ import annotations

import datetime as dt
import json
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
    if "did" in case:
        d = case["did"]
        closed = task(dela, ids[d["closed"]])
        assert closed["status"] == "done", closed
        assert item["did"]["closed"] == ids[d["closed"]]
        with dela_db.session(dela, "sasha") as c:
            touch = c.execute("SELECT * FROM crm.interactions WHERE id = %s", (item["did"]["touch"],)).fetchone()
        assert touch["kind"] == d["touch_kind"] and [str(x) for x in touch["person_ids"]] == [ids[d["person"]]]


@pytest.mark.parametrize("case", CASES["silent"], ids=[c["say"] for c in CASES["silent"]])
def test_rule_stays_silent(dela, case):
    seed(dela)
    with dela_db.session(dela, "sasha") as c:
        assert capture.rule(c, "sasha", case["say"], NOWS["a"]) is None, case["why"]


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
    assert capture.when_say(mk(20, 9), now) == "во вторник в 9:00"
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
    cap = say(dela, "созвон с Женей завтра в 12:30", lane="rule", ref=ref)
    assert cap["status"] == "none"
    with dela_db.session(dela, "sasha") as c:
        assert not c.execute("SELECT 1 FROM tasks.dictations WHERE id = %s", ("capture:" + ref,)).fetchone()


def _model(tasks=(), notes=(), ideas=()):
    def task_(title, **kw):
        base = {"title": title, "notes": "", "project": "", "person": "", "ball": "mine", "due": "", "due_time": "",
                "remind_at": "", "remind_place": "", "estimate_min": 0, "money": "", "want": False, "labels": [], "goal": 0,
                "now": False, "back": "", "done": False}
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
    assert got["head"].startswith("Разложил 2: напоминание завтра в 9:00 · напоминание")
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
    assert lines[3] == "Жду от Ивана Тестова: прислать модель · неделя без ответа — на виду"
    assert lines[4] == "Ждёт от тебя Ольга Тестова: ответить Ольге · в «Отбить»"
    assert lines[5] == "При встрече с Иваном Тестовым: обсудить фонды"
    assert task(dela, got["items"][6]["task"]["id"])["back_on"].isoformat() == "2026-10-20"
    assert str(got["items"][7].get("project_id")) == ids["romashka"]
    assert got["items"][8]["title"] == "Предложить дашборд"  # слова «идея» не было — дело


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
    assert count(s) <= 52 and {"now", "back", "done"} <= set(s["properties"]["tasks"]["items"]["required"])
    assert "ideas" in s["required"]


# ── Поправка одним касанием ─────────────────────────────────────────────


def test_move_reminder_to_task_time_and_cancel(dela, at):
    seed(dela)
    cap = say(dela, "напомни в 12 в Контуре отправить счёт")
    tid = cap["items"][0]["task"]["id"]
    got = move(dela, cap["ref"], "task")
    t = task(dela, tid)
    assert t["kind"] == "task" and t["due_date"].isoformat() == "2026-10-15" and t["remind_at"]
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


def test_move_note_idea_task_and_twins(dela, at):
    ids = seed(dela, twin=True)
    cap = say(dela, "Иван сказал, бюджет будет в январе")
    item = cap["items"][0]
    assert item["bucket"] == "note" and item.get("twins") and not item.get("person_id")
    assert item["line"] == "Заметка: бюджет будет в январе — у какого Ивана?"
    assert len(item["chips"]) == 3 and item["chips"][-1]["label"] == "Отменить"
    pick = next(c for c in item["chips"] if c.get("person_id") == ids["ivan"])
    got = move(dela, cap["ref"], "person", person_id=pick["person_id"])
    assert got["items"][0]["line"].startswith("Переложил: Заметка у Ивана Тестова: бюджет будет в январе")
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
    t = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Дело на завтра с напоминанием сегодня",
                                                         "due_date": (today + dt.timedelta(days=1)).isoformat(),
                                                         "remind_at": now_iso(today, 15)}})[0]["task"]
    assert r1["kind"] == "remind" and r1["due_date"] is None
    v = store.view(dela, "sasha", "today")
    rem = [x["title"] for x in v["reminders"]]
    assert rem == ["Вчерашнее", "Отправить счёт", "Дело на завтра с напоминанием сегодня", "Дома"], rem
    every = [x["id"] for g in store.view(dela, "sasha", "all")["groups"] for x in g["items"]]
    assert r1["id"] not in every and r4["id"] not in every and t["id"] in every
    assert [x["title"] for x in store.view(dela, "sasha", "reminders")["items"]] == ["Вчерашнее", "Отправить счёт", "Завтрашнее", "Дома"]
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


def test_phone_shows_bot_waits_three_minutes(dela):
    with dela_db.session(dela, "system", "test") as c:
        c.execute("UPDATE crm.users SET telegram_id = 111 WHERE id = 'sasha'")
    ago = lambda m: remind.iso(remind.now() - dt.timedelta(minutes=m))  # noqa: E731
    fresh = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Свежее", "kind": "remind", "remind_at": ago(1)}})[0]["task"]
    late = ops(dela, "sasha", {"op": "task.create", "task": {"title": "Телефон молчит", "kind": "remind",
                                                            "remind_at": ago(5)}})[0]["task"]
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


def test_web_line_without_key_rule_answers(dela):
    h = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'device', 'веб')}"}
    client = TestClient(api.build(Config(db_url=dela)))
    r = client.post("/api/ask", headers=h, json={"text": "купить магний", "scope": {"title": "Сейчас"}})
    assert r.status_code == 202, r.text
    got = client.get(f"/api/ask/{r.json()['job']}", headers=h).json()
    assert got["status"] == "done" and got["route"] == "capture" and got["capture"]["items"][0]["bucket"] == "back"


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
