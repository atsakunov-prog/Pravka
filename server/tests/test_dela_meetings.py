"""Встречи в базе (dela_0010): приём от службы, единое название, замки владельца, список, поиск, видимость."""

from __future__ import annotations

import pytest

from pravka_dela import db as dela_db
from pravka_dela import meetings, store
from test_dela_schema import dela, project  # noqa: F401  (фикстура и помощник)

TRANSCRIPT = """[00:00:05] Саша: Добрый день, по сделке у нас два вопроса.
[00:00:12] Олег Ветров: Да, начнём со счетов. Счёт на 300 тысяч рублей мы так и не оплатили.
Его надо перевыставить на новое юрлицо.
[00:01:40] Саша: Хорошо, перевыставим до пятницы.
[00:02:10] Олег Ветров: И второе — модель, сценарии по ставке."""


def ops(url, user, *items, via="app"):
    return store.apply_ops(url, user, list(items), via)["results"]


def ok(url, user, *items):
    res = ops(url, user, *items)
    assert all(r["ok"] for r in res), res
    return res


def push(url, ref="215", user="sasha", **m):
    m = {"ref": ref, "url": f"https://meetings.example.org/m/{ref}", "started_at": "2026-10-12T11:56:00+03:00",
         "ended_at": "2026-10-12T12:43:00+03:00", "kind": "zoom", "title": "Регулярная по сделке",
         "summary": "Счёт перевыставить на новое юрлицо; модель — таблицы чувствительности.", "transcript": TRANSCRIPT, **m}
    return ok(url, user, {"op": "meeting.upsert", "meeting": m})[0]["meeting"]


@pytest.fixture()
def mt(dela):
    """Дела с клиентом, сделкой и людьми: сам владелец, человек клиента, Наташа из команды."""
    p = project(dela, "sasha", "Вектор", aliases=["Вектор Плюс"])
    with dela_db.session(dela, "sasha", via="app") as c:
        c.execute("INSERT INTO crm.deals (project_id, name, stage) VALUES (%s, 'Вектор: рефинансирование', 'active')", (p,))
        ids = {}
        for name, short in (("Александр Саввин", "Саша"), ("Олег Ветров", "Олег"), ("Наталья Ромашкина", "Наташа")):
            ids[short] = c.execute("INSERT INTO crm.people (name, short, owner_id) VALUES (%s, %s, 'sasha') RETURNING id",
                                   (name, short)).fetchone()["id"]
    with dela_db.session(dela, "system", "test") as c:
        c.execute("UPDATE crm.users SET person_id = %s WHERE id = 'sasha'", (ids["Саша"],))
    return {"url": dela, "project": p, **ids}


def test_split_lines_reads_speakers_stamps_and_continuations():
    lines = meetings.split_lines(TRANSCRIPT)
    assert [x["speaker"] for x in lines] == ["Саша", "Олег Ветров", "Олег Ветров", "Саша", "Олег Ветров"]
    assert lines[0]["start_sec"] == 5 and lines[3]["start_sec"] == 100 and lines[2]["start_sec"] is None
    assert lines[2]["text"] == "Его надо перевыставить на новое юрлицо."
    # Начало фразы с двоеточием — не имя; ссылка — не имя.
    odd = meetings.split_lines("Итак, по сделке: три пункта\nСсылка https://example.org/x")
    assert [x["speaker"] for x in odd] == [None, None] and odd[0]["text"] == "Итак, по сделке: три пункта"
    # Сегменты службы сильнее текста; длинный монолог режется по фразам.
    seg = meetings.split_lines(segments=[{"start": "01:02", "end": 70, "speaker": "Ирина", "text": "Фраза. " * 400}])
    assert len(seg) > 1 and all(len(x["text"]) <= meetings.LINE_MAX for x in seg)
    assert seg[0]["start_sec"] == 62 and seg[1]["start_sec"] is None and {x["speaker"] for x in seg} == {"Ирина"}


def test_split_lines_reads_whisperx_headers():
    """Формат службы встреч: строка «таймкод и имя», реплика — строками ниже (docs/meeting-clean-prompt.md)."""
    text = "[00:01:23] Дмитрий\nМодель готова.\nСценарии — к четвергу.\n00:02:10 — Ирина:\nСогласна.\n[00:03:00]\nИ ещё одно."
    lines = meetings.split_lines(text)
    assert [(x["start_sec"], x["speaker"], x["text"]) for x in lines] == [
        (83, "Дмитрий", "Модель готова."), (None, "Дмитрий", "Сценарии — к четвергу."),
        (130, "Ирина", "Согласна."), (180, "Ирина", "И ещё одно.")]


def test_title_is_one_rule():
    assert meetings.title_of("Вектор", "рефинансирование и счета") == "Вектор: рефинансирование и счета"
    assert meetings.title_of(None, "знакомство") == "знакомство" and meetings.title_of("ЗФ", "") == "ЗФ"
    assert meetings.line({"title": "Вектор: счета", "people": ["Олег"], "guests": ["Пётр"],
                          "started_at": "2026-10-12T08:56:00+00:00", "duration_min": 47}) == \
        "Вектор: счета · Олег, Пётр · 12.10.2026 11:56 · 47 мин"


def test_upsert_keeps_everything_and_repeat_is_harmless(mt):
    url = mt["url"]
    m = push(url, project="вектор плюс", recorder="zoom-cloud", cost_usd=0.42)
    assert m["changed"] and m["lines"] == 5 and not m["named"]
    card = store.view(url, "sasha", "meeting", id=m["id"])
    assert card["duration_min"] == 47 and card["kind"] == "zoom" and card["source_title"] == "Регулярная по сделке"
    assert card["client"] == "Вектор" and card["sphere"] == "work"  # подсказка службы — до разбора
    assert card["speakers"] == ["Олег Ветров", "Саша"] and len(card["lines"]) == 5
    with dela_db.session(url, "sasha") as c:
        raw = c.execute("SELECT raw, transcript FROM crm.meeting_raw WHERE meeting_id = %s", (m["id"],)).fetchone()
    assert raw["raw"] == {"recorder": "zoom-cloud", "cost_usd": 0.42} and raw["transcript"] == TRANSCRIPT
    # Та же встреча ещё раз — та же строка, без новой версии и без нового разбора.
    again = push(url, project="вектор плюс", recorder="zoom-cloud", cost_usd=0.42)
    assert again["id"] == m["id"] and not again["changed"] and again["rev"] == m["rev"]


def test_new_content_goes_back_to_naming_but_locks_stay(mt):
    url = mt["url"]
    m = push(url)
    with dela_db.session(url, "sasha", "svc:test") as c:
        row = c.execute("SELECT * FROM crm.meetings WHERE id = %s", (m["id"],)).fetchone()
        meetings.apply_names(c, row, {"sphere": "work", "client": "Вектор", "deal": "рефинансирование",
                                      "topic": "счёт и модель", "people": [{"name": "Олег Ветров", "person": "Олег Ветров"}],
                                      "sure": True}, "test")
    ok(url, "sasha", {"op": "meeting.set", "id": m["id"], "set": {"topic": "перевыставление счёта"}})
    again = push(url, transcript=TRANSCRIPT + "\n[00:03:00] Олег Ветров: И ещё про залог.")
    assert again["changed"] and not again["named"]
    card = store.view(url, "sasha", "meeting", id=m["id"])
    assert len(card["lines"]) == 6 and card["locked"] == ["topic"]
    with dela_db.session(url, "sasha", "svc:test") as c:
        row = c.execute("SELECT * FROM crm.meetings WHERE id = %s", (m["id"],)).fetchone()
        meetings.apply_names(c, row, {"sphere": "work", "client": "Вектор", "topic": "залог", "people": [], "sure": True}, "test")
    card = store.view(url, "sasha", "meeting", id=m["id"])
    assert card["topic"] == "перевыставление счёта" and card["title"] == "Вектор: перевыставление счёта"


def test_naming_resolves_client_people_and_leaves_strangers_as_guests(mt):
    url = mt["url"]
    m = push(url)
    with dela_db.session(url, "sasha", "svc:test") as c:
        row = c.execute("SELECT * FROM crm.meetings WHERE id = %s", (m["id"],)).fetchone()
        meetings.apply_names(c, row, {
            "sphere": "work", "client": "Вектор", "label": "", "deal": "рефинансирование", "topic": "счёт и модель.",
            "people": [{"name": "Олег Ветров", "person": "Олег Ветров"}, {"name": "Саша", "person": "Александр Саввин"},
                       {"name": "Наташа", "person": ""}, {"name": "Пётр из банка", "person": ""}],
            "sure": False, "question": "Это Вектор или его банк?"}, "claude-test")
    card = store.view(url, "sasha", "meeting", id=m["id"])
    assert card["title"] == "Вектор: счёт и модель" and card["deal"] == "Вектор: рефинансирование"
    # Сам владелец — не участник; «Наташа» узнана по короткому имени; незнакомец — гость словами.
    assert card["people"] == ["Олег", "Наташа"] and card["guests"] == ["Пётр из банка"]
    assert card["question"] == "Это Вектор или его банк?" and card["named"] and card["named_by"] == "claude-test"
    # Разбор без клиента из справочника — компания словами в названии.
    m2 = push(url, ref="216")
    with dela_db.session(url, "sasha", "svc:test") as c:
        row = c.execute("SELECT * FROM crm.meetings WHERE id = %s", (m2["id"],)).fetchone()
        meetings.apply_names(c, row, {"sphere": "work", "client": "", "label": "Экспобанк", "topic": "кредит",
                                      "people": [], "sure": True}, "t")
    assert store.view(url, "sasha", "meeting", id=m2["id"])["title"] == "Экспобанк: кредит"


def test_owner_edits_lock_fields_and_rebuild_title(mt):
    url = mt["url"]
    m = push(url)
    r = ok(url, "sasha", {"op": "meeting.set", "id": m["id"],
                          "set": {"project": "Вектор", "topic": "счета", "people": ["Олег"], "sphere": "рабочая"}})[0]
    card = r["meeting"]
    assert card["title"] == "Вектор: счета" and card["people"] == ["Олег"]
    assert card["locked"] == ["people", "project", "sphere", "topic"]
    # То же значение ещё раз — не правка: замок не появляется и не пропадает.
    card = ok(url, "sasha", {"op": "meeting.set", "id": m["id"], "unlock": ["sphere"], "set": {"sphere": "work"}})[0]["meeting"]
    assert card["locked"] == ["people", "project", "topic"]
    card = ok(url, "sasha", {"op": "meeting.set", "id": m["id"], "set": {"title": "Вектор: счета и залог"}})[0]["meeting"]
    assert "title" in card["locked"]
    card = ok(url, "sasha", {"op": "meeting.set", "id": m["id"], "set": {"topic": "залог"}})[0]["meeting"]
    assert card["title"] == "Вектор: счета и залог"  # название под замком — тема его не переписывает
    card = ok(url, "sasha", {"op": "meeting.set", "id": m["id"], "unlock": ["title", "topic"], "rename": True})[0]["meeting"]
    assert card["locked"] == ["people", "project"] and not card["named"]
    bad = ops(url, "sasha", {"op": "meeting.set", "id": m["id"], "set": {"people": ["Незнакомец Странный"]}})[0]
    assert not bad["ok"] and "не определился" in bad["error"]
    bad = ops(url, "sasha", {"op": "meeting.set", "id": m["id"], "set": {"colour": "red"}})[0]
    assert not bad["ok"] and "colour" in bad["error"]
    # «Это не Вектор»: клиент уходит и из названия.
    card = ok(url, "sasha", {"op": "meeting.set", "id": m["id"], "set": {"project": "-", "topic": "знакомство"}})[0]["meeting"]
    assert card["client"] is None and card["title"] == "знакомство"


def test_list_filters_sorts_and_finds_word_forms(mt):
    url = mt["url"]
    a = push(url, ref="1", started_at="2026-10-01T10:00:00+03:00", ended_at="2026-10-01T10:30:00+03:00")
    b = push(url, ref="2", started_at="2026-10-05T10:00:00+03:00", ended_at="2026-10-05T12:00:00+03:00",
             transcript="Ольга: как вы себя чувствуете на этой неделе?", summary="Плановая сессия.", title="Сессия")
    ok(url, "sasha", {"op": "meeting.set", "id": a["id"], "set": {"project": "Вектор", "topic": "счета"}},
       {"op": "meeting.set", "id": b["id"], "set": {"sphere": "личная", "label": "Личное", "topic": "терапия"}})
    v = store.view(url, "sasha", "meetings")
    assert [x["ref"] for x in v["items"]] == ["2", "1"] and v["counts"]["home"] == 1 and v["counts"]["work"] == 1
    assert [x["ref"] for x in store.view(url, "sasha", "meetings", sort="duration")["items"]] == ["2", "1"]
    assert [x["ref"] for x in store.view(url, "sasha", "meetings", sphere="work")["items"]] == ["1"]
    assert {c["name"]: c["n"] for c in v["clients"]} == {"Вектор": 1, "Личное": 1}
    # «счетами» находит «счетов» и «Счёт» в расшифровке — словоформы; сниппет с подсветкой.
    found = store.view(url, "sasha", "meetings", q="счетами")["items"]
    assert [x["ref"] for x in found] == ["1"] and found[0]["hits_n"] >= 1 and "«" in found[0]["hits"][0]["text"]
    # Имя — подстрокой (стеммер режет имена по-разному).
    assert [x["ref"] for x in store.view(url, "sasha", "meetings", q="Ветров")["items"]] == ["1"]
    # Убранная в архив не видна в списке, но видна по запросу.
    ok(url, "sasha", {"op": "meeting.set", "id": b["id"], "set": {"archived": True}})
    assert [x["ref"] for x in store.view(url, "sasha", "meetings")["items"]] == ["1"]
    assert len(store.view(url, "sasha", "meetings", archived="1")["items"]) == 2


def test_card_links_timeline_and_tasks_by_number_across_hosts(mt):
    url = mt["url"]
    m = push(url)
    ok(url, "sasha",
       {"op": "interaction.add", "data": {"at": "2026-10-12T12:00:00+03:00", "kind": "zoom", "summary": "Вектор: счета",
                                          "project_id": str(mt["project"]), "source": "meeting",
                                          "source_ref": "https://old-host.example.org:8443/m/215"}},
       {"op": "task.create", "task": {"title": "Перевыставить счёт", "source": "meeting",
                                      "source_ref": "https://meetings.example.org/m/215"}})
    card = store.view(url, "sasha", "meeting", ref="215", lines="none")
    assert [i["summary"] for i in card["timeline"]] == ["Вектор: счета"]
    assert [t["title"] for t in card["tasks"]] == ["Перевыставить счёт"] and "lines" not in card
    win = store.view(url, "sasha", "meeting", id=m["id"], around=0)
    assert [x["n"] for x in win["lines"]] == [0, 1, 2, 3, 4]


def test_only_owner_sees_meetings(mt):
    url = mt["url"]
    m = push(url, project="Вектор")
    with dela_db.session(url, "system", "test") as c:
        c.execute("INSERT INTO crm.project_access (project_id, user_id, role, granted_by) VALUES (%s, 'natasha', 'edit', 'sasha')",
                  (mt["project"],))
    assert store.view(url, "natasha", "meetings")["items"] == []
    with pytest.raises(store.OpError):
        store.view(url, "natasha", "meeting", id=m["id"])
    bad = ops(url, "natasha", {"op": "meeting.set", "id": m["id"], "set": {"topic": "чужое"}})[0]
    assert not bad["ok"]
    # Номер встречи у службы один на всех: тот же номер от Наташи Сашину встречу не перепишет.
    r = ops(url, "natasha", {"op": "meeting.upsert", "meeting": {"ref": "215", "started_at": "2026-10-12T11:00:00+03:00",
                                                               "summary": "чужое"}})[0]
    assert not r["ok"]
    assert store.view(url, "sasha", "meeting", id=m["id"])["summary"].startswith("Счёт")


def test_upsert_refuses_in_words(mt):
    url = mt["url"]
    r = ops(url, "sasha", {"op": "meeting.upsert", "meeting": {"started_at": "2026-10-12T11:00:00+03:00"}})[0]
    assert not r["ok"] and "ref" in r["error"]
    r = ops(url, "sasha", {"op": "meeting.upsert", "meeting": {"ref": "9", "started_at": "вчера"}})[0]
    assert not r["ok"] and "ISO" in r["error"]
    r = ops(url, "sasha", {"op": "meeting.upsert", "meeting": {"ref": "9"}})[0]
    assert not r["ok"] and "started_at" in r["error"]


def test_life_view_for_claude(mt, conn):
    url = mt["url"]
    m = push(url, project="Вектор")
    ok(url, "sasha", {"op": "meeting.set", "id": m["id"], "set": {"topic": "счета", "people": ["Олег"]}})
    row = conn.execute("SELECT title, client, people, sphere, lines_n FROM life.meetings WHERE ref = '215'").fetchone()
    assert row == ("Вектор: счета", "Вектор", ["Олег"], "work", 5)
    n = conn.execute("SELECT count(*) FROM life.meeting_lines WHERE tsv @@ websearch_to_tsquery('russian', 'счетами')").fetchone()[0]
    assert n >= 1


# ── Разборщик ─────────────────────────────────────────────────────────

import json  # noqa: E402
from types import SimpleNamespace as NS  # noqa: E402

from pravka_dela import meetings_auto  # noqa: E402


class FakeBatches:
    def __init__(self, answers):
        self.answers, self.sent, self.status = answers, [], "in_progress"

    def create(self, requests):
        self.sent.append(requests)
        return NS(id=f"msgbatch_{len(self.sent)}")

    def retrieve(self, bid):
        return NS(processing_status=self.status)

    def results(self, bid):
        for r in self.sent[-1]:
            ans = self.answers.get(r["custom_id"])
            if ans is None:
                yield NS(custom_id=r["custom_id"], result=NS(type="errored"))
                continue
            msg = NS(stop_reason="end_turn", model="claude-opus-5-5",
                     content=[NS(type="text", text=json.dumps(ans, ensure_ascii=False))],
                     usage=NS(input_tokens=9000, output_tokens=300, cache_creation_input_tokens=0, cache_read_input_tokens=0))
            yield NS(custom_id=r["custom_id"], result=NS(type="succeeded", message=msg))


def ans(**kw):
    return {"sphere": "work", "client": "", "label": "", "deal": "", "topic": "", "people": [], "sure": True,
            "question": "", **kw}


def test_namer_sends_unnamed_once_names_and_respects_owner(mt):
    url = mt["url"]
    a = push(url, ref="301")
    b = push(url, ref="302", transcript="Ольга: как прошла неделя?", summary="Плановая сессия.", title="Сессия")
    c = push(url, ref="303")
    ok(url, "sasha", {"op": "meeting.set", "id": c["id"], "set": {"topic": "моё название темы"}})
    fb = FakeBatches({
        str(a["id"]): ans(client="Вектор", topic="счёт и модель", people=[{"name": "Олег Ветров", "person": "Олег Ветров"}]),
        str(b["id"]): ans(sphere="home", label="Личное", topic="плановая сессия"),
        str(c["id"]): ans(client="Вектор", topic="чужая тема"),
    })
    cl = NS(messages=NS(batches=fb))
    assert meetings_auto.tick(url, "key", None, now=1000.0, client=cl) == {"sent": 3}
    req = fb.sent[0][0]["params"]
    sys_text = req["system"][0]["text"]
    assert "— Вектор (Вектор Плюс) · клиент · сделки: Вектор: рефинансирование" in sys_text
    assert "ВЛАДЕЛЕЦ: Александр Саввин" in sys_text and "Олег Ветров (Олег)" in sys_text
    assert req["system"][0]["cache_control"] == {"type": "ephemeral"}
    assert any("РАСШИФРОВКА:\nСаша: Добрый день" in r["params"]["messages"][0]["content"] for r in fb.sent[0])
    # Пачка в работе — тик ждёт и новую не шлёт.
    assert meetings_auto.tick(url, "key", None, now=1060.0, client=cl) is None and len(fb.sent) == 1
    fb.status = "ended"
    out = meetings_auto.tick(url, "key", None, now=1120.0, client=cl)
    assert out["named"] == 3 and out["failed"] == 0
    got = {x["ref"]: x for x in store.view(url, "sasha", "meetings")["items"]}
    assert got["301"]["title"] == "Вектор: счёт и модель" and got["301"]["people"] == ["Олег"]
    assert got["302"]["title"] == "Личное: плановая сессия" and got["302"]["sphere"] == "home"
    assert got["303"]["topic"] == "моё название темы" and got["303"]["title"] == "Вектор: моё название темы"
    # Разбирать нечего — ни запроса; правка владельца — примером в следующий запрос.
    assert meetings_auto.tick(url, "key", None, now=5000.0, client=cl) is None and len(fb.sent) == 1
    push(url, ref="304")
    assert meetings_auto.tick(url, "key", None, now=5100.0, client=cl) == {"sent": 1}
    assert "КАК НАЗЫВАЛ ВЛАДЕЛЕЦ" in fb.sent[1][0]["params"]["system"][0]["text"]
    assert "«Вектор: моё название темы»" in fb.sent[1][0]["params"]["system"][0]["text"]


def test_namer_skips_stale_answers_and_marks_failures(mt):
    url = mt["url"]
    a = push(url, ref="401")
    b = push(url, ref="402")
    fb = FakeBatches({str(a["id"]): ans(client="Вектор", topic="старое")})  # для 402 ответа нет — errored
    cl = NS(messages=NS(batches=fb))
    meetings_auto.tick(url, "key", None, now=1000.0, client=cl)
    push(url, ref="401", transcript="Олег Ветров: совсем другой разговор о залоге.")  # пока шла пачка
    fb.status = "ended"
    out = meetings_auto.tick(url, "key", None, now=1100.0, client=cl)
    assert out == {"named": 0, "failed": 1, "stale": 1, "usd": 0.0}
    st = meetings_auto.status(url)
    assert st["waiting"] == 1 and st["failed"] == 1
    assert meetings_auto.rename(url) == 1  # неудавшиеся — заново
    assert meetings_auto.status(url)["waiting"] == 2
    card = store.view(url, "sasha", "meeting", id=b["id"], lines="none")
    assert not card["named"] and card["title"] is None


# ── Инструменты Claude ───────────────────────────────────────────────

def test_claude_tools_find_read_and_change(mt):
    from pravka_dela import mcp_tools

    url = mt["url"]
    m = push(url, project="Вектор")
    ok(url, "sasha", {"op": "meeting.set", "id": m["id"], "set": {"topic": "счета", "people": ["Олег"]}})
    found = mcp_tools.meetings_list(url, query="счетами")
    assert "[215] Вектор: счета · Олег" in found and "Олег Ветров: " in found and "(реплика 1)" in found
    assert "Ничего не нашлось" in mcp_tools.meetings_list(url, query="залог")
    card = mcp_tools.meeting_card(url, "215")
    assert card.startswith("Вектор: счета · Олег") and "[1] Олег Ветров: Да, начнём" in card and "клиент Вектор" in card
    win = mcp_tools.meeting_card(url, "215", around=4)
    assert "окно вокруг реплики 4" in win and "[4] Олег Ветров" in win
    assert mcp_tools.meeting_change(url, "215", sphere="личная").startswith("Готово: Вектор: счета")
    assert "личная" in mcp_tools.meeting_card(url, "215")
    assert mcp_tools.meeting_change(url, "999", topic="x").startswith("Не вышло")


def test_contract_example_goes_through(mt):
    """Пример из contract/dela-meetings.json — то, что пошлёт служба встреч, — сервер принимает как есть."""
    from pathlib import Path

    four = json.loads((Path(__file__).resolve().parents[1] / "contract" / "dela-meetings.json").read_text(encoding="utf-8"))
    assert four["features"]["flag"] in store.FEATURES
    ex = four["op_upsert"]["example"]
    r = ops(mt["url"], "sasha", ex)[0]
    assert r["ok"] and set(four["op_upsert"]["result"]["meeting"]) == set(r["meeting"]), r
    card = store.view(mt["url"], "sasha", "meeting", ref="215")
    assert card["client"] == "Вектор" and card["duration_min"] == 47 and len(card["agreements"]) == 2
    assert [x["speaker"] for x in card["lines"]] == ["Саша", "Олег Ветров"]
    assert ops(mt["url"], "sasha", ex)[0] == r  # тот же op_id — тот же ответ
