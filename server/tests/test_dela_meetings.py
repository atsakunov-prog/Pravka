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


def summary_mode(url):
    """Команда видит из встреч и итог (Свод privacy.team = summary, аудит №9): старые тесты записи встречи проверяют, что
    запись следует карточке, — а не что команда видит итог; режим «только факт» (с завода) — test_dela_privacy.py."""
    ok(url, "sasha", {"op": "svod.set", "key": "privacy.team", "value": {"meetings": "summary"}, "author": "test"})


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
    # Запись хронологии, которую служба встреч написала сама на прежнем адресе (до 15.10; теперь её ведёт сервер, №6).
    with dela_db.session(url, "sasha", "svc:meetings") as c:
        c.execute("INSERT INTO crm.interactions (at, kind, summary, project_id, source, source_ref, owner_id) VALUES "
                  "('2026-10-12T12:00:00+03:00', 'zoom', 'Вектор: счета', %s, 'meeting', "
                  "'https://old-host.example.org:8443/m/215', 'sasha')", (mt["project"],))
    ok(url, "sasha",
       {"op": "task.create", "task": {"title": "Перевыставить счёт", "source": "meeting",
                                      "source_ref": "https://meetings.example.org/m/215"}})
    card = store.view(url, "sasha", "meeting", ref="215", lines="none")
    # Владельцу запись встречи — с итогом с карточки (crm.v_interactions, аудит №9); в строке — то, что видит команда.
    assert [(i["summary"], i["team_sees"]) for i in card["timeline"]] == [
        ("Регулярная по сделке: Счёт перевыставить на новое юрлицо; модель — таблицы чувствительности.", "fact")]
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
    assert "cache_control" not in req["system"][0]  # пачка из трёх — кэш только писала бы (CACHE_MIN)
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
    # Ответ по встрече, присланной заново, не кладётся, но оплачен — он в учёте (аудит трат 14.10.2026).
    assert {k: out[k] for k in ("named", "failed", "stale")} == {"named": 0, "failed": 1, "stale": 1} and out["usd"] > 0
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


# ── Только то, что уже есть (13.10.2026): без вопросов о старом, тема без двоеточий, «ЗФ» — проектом, голос ───

def name_it(url, mid, **ans_):
    with dela_db.session(url, "sasha", "svc:test") as c:
        row = c.execute("SELECT * FROM crm.meetings WHERE id = %s", (mid,)).fetchone()
        meetings.apply_names(c, row, ans(**ans_), "test")
    return store.view(url, "sasha", "meeting", id=mid, lines="none")


def test_clean_topic_keeps_one_colon():
    assert meetings.clean_topic("урок математики: текстовые задачи.") == "урок математики — текстовые задачи"
    assert meetings.clean_topic(" kick-off :интервью ") == "kick-off — интервью"
    assert meetings.clean_topic(":") is None and meetings.clean_topic(None) is None


def test_naming_asks_only_about_fresh_and_label_becomes_project(mt):
    import datetime as dt

    url = mt["url"]
    with dela_db.session(url, "sasha", via="app") as c:
        zf = c.execute("INSERT INTO crm.projects (name, sphere, kind, owner_id) VALUES ('ЗФ', 'work', 'internal', 'sasha') "
                       "RETURNING id").fetchone()["id"]
    now = dt.datetime.now(dt.timezone.utc)
    old = push(url, ref="501", started_at=(now - dt.timedelta(days=40)).isoformat(), ended_at=None)
    new = push(url, ref="502", started_at=(now - dt.timedelta(days=1)).isoformat(), ended_at=None)
    a = name_it(url, old["id"], label="ЗФ", topic="ретро недели: фонды", sure=False, question="С кем это было?")
    assert a["question"] is None  # старая встреча — лучшая догадка без вопроса
    assert a["project_id"] == str(zf) and a["title"] == "ЗФ: ретро недели — фонды"
    b = name_it(url, new["id"], client="Вектор", topic="счета", sure=False, question="Это Вектор?")
    assert b["question"] == "Это Вектор?"
    v = store.view(url, "sasha", "meetings")
    assert v["counts"]["asks"] == 1
    assert [x["ref"] for x in store.view(url, "sasha", "meetings", unnamed="1")["items"]] == ["502"]
    assert [c["name"] for c in v["clients"]].count("ЗФ") == 1


def test_people_recognised_by_voice_join_and_leave_guests(mt):
    url = mt["url"]
    m = push(url, raw={"speakers": [{"name": "Олег Ветров", "person_id": str(mt["Олег"])},
                                    {"name": "Саша", "person_id": str(mt["Саша"])},  # сам владелец — не участник
                                    {"name": "Чужой", "person_id": "not-a-uuid"}]})
    card = name_it(url, m["id"], client="Вектор", topic="счета",
                   people=[{"name": "Олег Ветров", "person": ""}, {"name": "Пётр из банка", "person": ""}])
    assert card["people"] == ["Олег"] and card["guests"] == ["Пётр из банка"]
    # Говорящие объектами верхнего уровня — имя в колонку, а не repr словаря.
    m2 = push(url, ref="216", speakers=[{"name": "Ирина", "person_id": None}, "Олег"])
    assert store.view(url, "sasha", "meeting", id=m2["id"], lines="none")["speakers"] == ["Ирина", "Олег"]


def test_cache_marker_only_for_big_batches():
    small = meetings_auto.system_blocks("r", "c", [], 1)[0]
    big = meetings_auto.system_blocks("r", "c", [], meetings_auto.CACHE_MIN)[0]
    assert "cache_control" not in small and big["cache_control"] == {"type": "ephemeral"}


def test_migration_11_over_meetings_in_place(admin_dsn):
    """Миграция 11 — поверх живых встреч (у владельца их 198 к моменту установки). В общей тестовой базе миграции
    идут по пустой и правку уже лежащих строк не проверяют (CLAUDE.md, «Проверка»)."""
    import secrets as _secrets
    from urllib.parse import urlparse, urlunparse

    import psycopg
    from psycopg import sql
    from psycopg.types.json import Jsonb

    name = f"pa_mig11_{_secrets.token_hex(4)}"
    with psycopg.connect(admin_dsn, autocommit=True) as a:
        a.execute(sql.SQL("CREATE DATABASE {} ENCODING 'UTF8' TEMPLATE template0").format(sql.Identifier(name)))
    u = urlparse(admin_dsn)
    url = urlunparse((u.scheme, u.netloc, "/" + name, "", "", ""))
    try:
        files = dict(dela_db._sql_files())
        before = {k: v for k, v in files.items() if k.startswith("dela_") and k < "dela_0011.sql"}
        with psycopg.connect(url, autocommit=True) as c:
            c.execute("CREATE SCHEMA core")
            c.execute("CREATE TABLE core.migrations (name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())")
            dela_db.apply_pending(c, before)
        with dela_db.session(url, "system", "test") as c:
            c.execute("INSERT INTO crm.users (id, name, role) VALUES ('sasha', 'Саша', 'owner')")
            zf = c.execute("INSERT INTO crm.projects (name, sphere, kind, owner_id) VALUES ('ЗФ', 'work', 'internal', 'sasha') "
                           "RETURNING id").fetchone()["id"]
            me, oleg, dima = (c.execute("INSERT INTO crm.people (name, short, aliases, owner_id) VALUES (%s, %s, %s, 'sasha') "
                                        "RETURNING id", (n, s, al)).fetchone()["id"]
                              for n, s, al in (("Александр Саввин", "Саша", []), ("Олег Ветров", "Олег", []),
                                               ("Дмитрий Орлов", "Дмитрий", ["Дима"])))
            c.execute("UPDATE crm.users SET person_id = %s WHERE id = 'sasha'", (me,))

            def mk(ref, days, **f):
                row = c.execute(
                    "INSERT INTO crm.meetings (ref, started_at, owner_id, label, topic, title, question, locked, guests, person_ids) "
                    "VALUES (%s, now() - make_interval(days => %s), 'sasha', %s, %s, %s, %s, %s, %s, %s) RETURNING id",
                    (ref, days, f.get("label"), f.get("topic"), f.get("title"), f.get("question"), f.get("locked", []),
                     f.get("guests", []), f.get("person_ids", []))).fetchone()["id"]
                if "raw" in f:
                    c.execute("INSERT INTO crm.meeting_raw (meeting_id, raw, owner_id) VALUES (%s, %s, 'sasha')", (row, Jsonb(f["raw"])))
                return row
            old_q = mk("1", 30, label="Семья", topic="урок математики: задачи", title="Семья: урок математики: задачи",
                       question="С кем?")
            fresh_q = mk("2", 2, label="Вектор", topic="счета", title="Вектор: счета", question="Это Вектор?")
            zf_label = mk("3", 20, label="ЗФ", topic="ретро", title="ЗФ: ретро")
            locked = mk("4", 20, label="ЗФ", topic="мой: заголовок", title="ЗФ: мой: заголовок", locked=["project", "topic"])
            voice = mk("5", 20, label="Вектор", topic="модель", title="Вектор: модель", guests=["Дима", "Пётр"],
                       person_ids=[oleg], raw={"speakers": [{"name": "Дмитрий", "person_id": str(dima)},
                                                            {"name": "Саша", "person_id": str(me)}, "просто строка"]})
        with psycopg.connect(url, autocommit=True) as c:
            assert dela_db.apply_pending(c, {k: v for k, v in files.items() if k <= "dela_0011.sql"}) == ["dela_0011.sql"]
        with dela_db.session(url, "system", "test") as c:
            got = {r["ref"]: r for r in c.execute("SELECT * FROM crm.meetings").fetchall()}
            hist = c.execute("SELECT count(*) AS n FROM crm.history WHERE actor = 'svc:migrate' AND entity = 'crm.meetings'").fetchone()["n"]
        assert got["1"]["question"] is None and got["2"]["question"] == "Это Вектор?"
        assert (got["1"]["topic"], got["1"]["title"]) == ("урок математики — задачи", "Семья: урок математики — задачи")
        assert got["3"]["project_id"] == zf and got["3"]["sphere"] == "work" and got["3"]["title"] == "ЗФ: ретро"
        assert got["4"]["project_id"] is None and got["4"]["title"] == "ЗФ: мой: заголовок"  # под замком — не трогали
        assert got["5"]["person_ids"] == [oleg, dima] and got["5"]["guests"] == ["Пётр"]
        assert hist == 4  # каждая правка — в журнале
        # Повтор той же миграции ничего не меняет.
        with psycopg.connect(url) as c:
            c.execute(files["dela_0011.sql"])
            c.commit()
        with dela_db.session(url, "system", "test") as c:
            again = {r["ref"]: r["rev"] for r in c.execute("SELECT ref, rev FROM crm.meetings").fetchall()}
        assert again == {k: v["rev"] for k, v in got.items()}
    finally:
        with psycopg.connect(admin_dsn, autocommit=True) as a:
            a.execute(sql.SQL("DROP DATABASE IF EXISTS {} WITH (FORCE)").format(sql.Identifier(name)))

# ── Клиент переименован (dela_0012, 13.10.2026) ──────────────────────


def _name(url, mid, **ans):
    """Ответ разбора — в карточку, как его кладёт meetings_auto."""
    with dela_db.session(url, "sasha", "svc:test") as c:
        row = c.execute("SELECT * FROM crm.meetings WHERE id = %s", (mid,)).fetchone()
        meetings.apply_names(c, row, {"sphere": "work", "client": "", "label": "", "topic": "", "people": [], "sure": True,
                                      **ans}, "test")


def _card(url, m):
    return store.view(url, "sasha", "meeting", id=m["id"], lines="none")


def _aliases(url, pid):
    with dela_db.session(url, "sasha") as c:
        return c.execute("SELECT aliases FROM crm.projects WHERE id = %s", (pid,)).fetchone()["aliases"]


def test_renamed_client_renames_its_meetings_and_keeps_old_name(mt):
    """Владелец, 13.10.2026: «переименую клиента — чтобы и в истории встреч тоже переименовалось»."""
    url = mt["url"]
    a, b = push(url, ref="301"), push(url, ref="302")
    _name(url, a["id"], client="Вектор", topic="счета")
    _name(url, b["id"], client="Вектор", topic="модель")
    ok(url, "sasha", {"op": "meeting.set", "id": b["id"], "set": {"title": "Вектор: модель и залог, моё"}})
    ok(url, "sasha", {"op": "project.set", "id": mt["project"], "set": {"name": "Новый Вектор"}})
    ca, cb = _card(url, a), _card(url, b)
    assert (ca["title"], ca["label"], ca["client"]) == ("Новый Вектор: счета", "Новый Вектор", "Новый Вектор")
    assert cb["title"] == "Новый Вектор: модель и залог, моё"  # своё название — меняется только начало
    assert _aliases(url, mt["project"]) == ["Вектор Плюс", "Вектор"]  # по старому имени клиент узнаётся и дальше
    # Обратно: старое имя снова имя, а не другое имя самого себя.
    ok(url, "sasha", {"op": "project.set", "id": mt["project"], "set": {"name": "Вектор"}})
    assert _aliases(url, mt["project"]) == ["Вектор Плюс", "Новый Вектор"]
    assert _card(url, a)["title"] == "Вектор: счета"


def test_new_client_takes_meetings_signed_with_its_name(mt):
    """Разбор подписал встречи «Сигма Пэй», пока клиента не было в справочнике; завели клиента — встречи его."""
    url = mt["url"]
    a, b, no = push(url, ref="311"), push(url, ref="312"), push(url, ref="313")
    for m in (a, b, no):
        _name(url, m["id"], label="Сигма Пэй", topic="оценка", sure=False, question="Сигма Пэй нет в справочнике — завести?")
    ok(url, "sasha", {"op": "meeting.set", "id": no["id"], "set": {"project": "-"}},
       {"op": "meeting.set", "id": no["id"], "set": {"label": "Сигма Пэй"}})  # «это не клиент» — слово владельца
    pid = project(url, "sasha", "Сигма Пэй")
    ca = _card(url, a)
    assert str(ca["project_id"]) == str(pid) and ca["title"] == "Сигма Пэй: оценка" and not ca["question"]
    assert str(_card(url, b)["project_id"]) == str(pid)
    assert not _card(url, no)["project_id"]
    # Другое имя, дописанное клиенту, тоже пристёгивает: «Дельта Капитал» — это Дельта Флоу.
    k = push(url, ref="314")
    _name(url, k["id"], label="Дельта Капитал", topic="знакомство")
    cf = project(url, "sasha", "Дельта Флоу")
    assert not _card(url, k)["project_id"]
    ok(url, "sasha", {"op": "project.set", "id": cf, "set": {"aliases": ["Дельта Капитал"]}})
    assert _card(url, k)["title"] == "Дельта Флоу: знакомство"


def test_unlisted_client_renamed_on_all_its_meetings(mt):
    """Клиента нет в справочнике — подпись «Вера (клуб)» у каждой встречи своя: меняется у всех разом (everywhere)."""
    url = mt["url"]
    ms = [push(url, ref=str(320 + i)) for i in range(3)]
    for m, t in zip(ms, ("финмодель", "КП", "итоги")):
        _name(url, m["id"], label="Вера (клуб)", topic=t)
    other = push(url, ref="330")
    _name(url, other["id"], label="Экспобанк", topic="кредит")
    assert _card(url, ms[0])["same_label"] == 2 and _card(url, other)["same_label"] == 0
    r = ok(url, "sasha", {"op": "meeting.set", "id": ms[0]["id"], "set": {"label": "Новая заря"}, "everywhere": True})[0]
    assert r["also"] == 2
    assert [_card(url, m)["title"] for m in ms] == ["Новая заря: финмодель", "Новая заря: КП", "Новая заря: итоги"]
    assert all("project" in _card(url, m)["locked"] for m in ms)  # решил владелец — разбор не переспорит
    assert _card(url, other)["title"] == "Экспобанк: кредит"
    # Подпись — к клиенту из справочника: все её встречи к нему, подпись — его другое имя.
    r = ok(url, "sasha", {"op": "meeting.set", "id": ms[1]["id"], "set": {"project": "Вектор"}, "everywhere": True})[0]
    assert r["also"] == 2 and {_card(url, m)["title"] for m in ms} == {"Вектор: финмодель", "Вектор: КП", "Вектор: итоги"}
    assert "Новая заря" in _aliases(url, mt["project"])
    # У встречи клиента из справочника «у всех» — отказ словами: переименовывают самого клиента.
    res = ops(url, "sasha", {"op": "meeting.set", "id": ms[0]["id"], "set": {"label": "x"}, "everywhere": True})
    assert not res[0]["ok"] and "переименуй самого клиента" in res[0]["error"]


def test_claude_renames_client_of_both_kinds(mt):
    from pravka_dela import mcp_tools

    url = mt["url"]
    a, b = push(url, ref="340"), push(url, ref="341")
    _name(url, a["id"], client="Вектор", topic="счета")
    _name(url, b["id"], label="Вера", topic="КП")
    assert mcp_tools.client_rename(url, "Вектор", "Вектор Групп").startswith("Готово")
    assert _card(url, a)["title"] == "Вектор Групп: счета"
    assert mcp_tools.client_rename(url, "Вера", "Новая заря").startswith("Готово")
    assert _card(url, b)["title"] == "Новая заря: КП"
    assert mcp_tools.client_rename(url, "Нет такого", "x").startswith("Не вышло")
    project(url, "sasha", "Ромашка")
    assert "уже другой клиент" in mcp_tools.client_rename(url, "Вектор Групп", "Ромашка")
    assert meetings.retitle("Вера: КП и смета", "Вера", "Новая заря") == "Новая заря: КП и смета"
    assert meetings.retitle("КП Веры", "Вера", "Новая заря") == "КП Веры"


# ── Одна правда о встрече: запись в хронологии ведёт сервер по карточке (аудит 15.10.2026, №6, №43, №60; dela_0021) ──


def _records(url, ref=None):
    with dela_db.session(url, "sasha") as c:
        rows = c.execute("SELECT * FROM crm.interactions WHERE source = 'meeting' ORDER BY created_at, id").fetchall()
    return [r for r in rows if ref is None or (r["source_ref"] or "").endswith(f"/m/{ref}")]


def _live(url, ref=None):
    return [r for r in _records(url, ref) if r["deleted_at"] is None]


def test_meeting_record_follows_its_card(mt):
    url = mt["url"]
    summary_mode(url)
    with dela_db.session(url, "system", "test") as c:
        c.execute("UPDATE crm.people SET user_id = 'natasha' WHERE id = %s", (mt["Наташа"],))  # Наташа — команда
    m = push(url, agreements=["Счёт перевыставить до пятницы", {"text": "Таблицы — к среде"}])
    assert _live(url) == []  # не разобрана: рабочая ли и чей клиент — ещё не знаем
    _name(url, m["id"], client="Вектор", deal="рефинансирование", topic="счета",
          people=[{"name": "Олег Ветров", "person": "Олег Ветров"}, {"name": "Наташа", "person": ""},
                  {"name": "Саша", "person": "Александр Саввин"}])
    card = _card(url, m)
    [r] = _live(url)
    # Клиент, сделка и люди — с карточки (люди — без команды и без самого владельца), название — Дел.
    assert (r["project_id"], str(r["deal_id"]), r["person_ids"]) == (mt["project"], card["deal_id"], [mt["Олег"]])
    assert card["deal_id"] is not None
    assert r["summary"] == "Вектор: счета: Счёт перевыставить на новое юрлицо; модель — таблицы чувствительности."
    assert r["next_step"] == "Счёт перевыставить до пятницы; Таблицы — к среде"
    assert (r["kind"], r["duration_min"], r["source_ref"], r["private"]) == (
        "zoom", 47, "https://meetings.example.org/m/215", False)
    assert [i["summary"] for i in card["timeline"]] == [r["summary"]]
    # Владелец поменял клиента — запись следом (сделки у нового клиента нет — и в записи пусто).
    alfa = project(url, "sasha", "Альфа")
    ok(url, "sasha", {"op": "meeting.set", "id": m["id"], "set": {"project": "Альфа"}})
    [r2] = _live(url)
    assert (r2["id"], r2["project_id"], r2["deal_id"]) == (r["id"], alfa, None)
    assert r2["summary"].startswith("Альфа: счета: ")
    # Стала личной — запись убрана; снова рабочая — новая, убранная остаётся историей.
    ok(url, "sasha", {"op": "meeting.set", "id": m["id"], "set": {"sphere": "home"}})
    assert _live(url) == [] and _records(url)[0]["deleted_at"] is not None
    ok(url, "sasha", {"op": "meeting.set", "id": m["id"], "set": {"sphere": "work"}})
    assert len(_live(url)) == 1 and len(_records(url)) == 2
    # Служебный проект — не клиент: записи нет.
    project(url, "sasha", "ЗФ", kind="internal")
    ok(url, "sasha", {"op": "meeting.set", "id": m["id"], "set": {"project": "ЗФ"}})
    assert _live(url) == []
    # Клиента переименовали — название Дел и запись следом; клиент перестал быть клиентом — запись уходит.
    ok(url, "sasha", {"op": "meeting.set", "id": m["id"], "set": {"project": "Вектор"}})
    ok(url, "sasha", {"op": "project.set", "id": mt["project"], "set": {"name": "Новый Вектор"}})
    [r3] = _live(url)
    assert r3["summary"].startswith("Новый Вектор: счета: ")
    ok(url, "sasha", {"op": "project.set", "id": mt["project"], "set": {"kind": "internal"}})
    assert _live(url) == []
    # Убрали встречу из списка — и её запись.
    ok(url, "sasha", {"op": "project.set", "id": mt["project"], "set": {"kind": "client"}})
    assert len(_live(url)) == 1
    ok(url, "sasha", {"op": "meeting.set", "id": m["id"], "set": {"archived": True}})
    assert _live(url) == []


def test_meeting_record_waits_for_naming_even_with_client_hint(mt):
    """Служба шлёт подсказку клиента (её голосование meeting_client — «клиент не тот» у 3 записей из 26), а op_upsert
    по ней ставит клиента и «рабочую». До разбора записи в хронологии нет: команда клиента не должна увидеть личную
    встречу раньше, чем разбор её погасит (инцидент 09.10; проверка 15.10.2026). Разобрал — запись есть; сферу
    поставил сам владелец — тоже; живую запись новая расшифровка не гасит."""
    url = mt["url"]
    summary_mode(url)
    with dela_db.session(url, "system", "test") as c:
        c.execute("INSERT INTO crm.project_access (project_id, user_id, role, granted_by) VALUES (%s, 'natasha', 'view', "
                  "'sasha')", (mt["project"],))

    def natasha_sees():
        return [i["summary"] for i in store.view(url, "natasha", "client", project_id=str(mt["project"]))["timeline"]]

    m = push(url, project="Вектор", summary="Личное: обсуждали развод и здоровье.")
    card = _card(url, m)
    assert (card["named"], card["sphere"], card["project_id"]) == (False, "work", str(mt["project"]))  # подсказка встала
    assert _live(url) == [] and natasha_sees() == []
    assert meetings.touch_all(url)["actions"] == {"ждёт разбора": 1}
    # Разбор сказал «личная» — записи так и не было.
    _name(url, m["id"], sphere="home", client="Вектор", topic="личное")
    assert _live(url) == [] and natasha_sees() == [] and _records(url) == []
    # Вторая встреча с той же подсказкой: разбор — рабочая, тогда запись есть, и команда клиента её видит.
    later = {"started_at": "2026-10-12T15:00:00+03:00", "ended_at": "2026-10-12T15:40:00+03:00"}  # не двойник первой
    w = push(url, ref="216", project="Вектор", **later)
    assert _live(url) == []
    _name(url, w["id"], client="Вектор", topic="счета")
    [r] = _live(url)
    assert natasha_sees() == [r["summary"]]
    # Новая расшифровка (named_at сброшен — ждёт переразбора): живая запись не гаснет, итог в ней — свежий.
    again = push(url, ref="216", project="Вектор", summary="Новый итог.", **later,
                 transcript=TRANSCRIPT + "\n[00:03:00] Олег Ветров: И ещё про залог.")
    assert again["changed"] and not again["named"]
    [r2] = _live(url)
    assert r2["id"] == r["id"] and r2["summary"] == "Вектор: счета: Новый итог."
    # Сферу поставил сам владелец (замок sphere) — запись и без разбора.
    o = push(url, ref="217", started_at="2026-10-12T18:00:00+03:00", ended_at="2026-10-12T18:30:00+03:00")
    assert _card(url, o)["sphere"] is None
    ok(url, "sasha", {"op": "meeting.set", "id": o["id"], "set": {"sphere": "work", "project": "Вектор"}})
    assert "sphere" in _card(url, o)["locked"] and not _card(url, o)["named"] and len(_live(url, "217")) == 1


def test_owner_hand_on_meeting_record(mt):
    """Убрал или поправил запись встречи сам владелец — замок touch: сервер её не трогает, «вернуть» — снова по карточке."""
    url = mt["url"]
    summary_mode(url)
    m = push(url)
    _name(url, m["id"], client="Вектор", topic="счета")
    [r] = _live(url)
    ok(url, "sasha", {"op": "interaction.delete", "id": str(r["id"])})
    assert "touch" in _card(url, m)["locked"]
    ok(url, "sasha", {"op": "meeting.set", "id": m["id"], "set": {"topic": "счета и залог"}})
    push(url, summary="Новый итог.")  # служба прислала заново — запись не вернулась
    assert _live(url) == []
    # «Вернуть» (unlock touch) — снова по карточке.
    ok(url, "sasha", {"op": "meeting.set", "id": m["id"], "unlock": ["touch"]})
    assert "touch" not in _card(url, m)["locked"]
    [r2] = _live(url)
    assert r2["summary"] == "Вектор: счета и залог: Новый итог."
    # Поправил запись руками — правка остаётся и после правки встречи.
    ok(url, "sasha", {"op": "interaction.set", "id": str(r2["id"]), "set": {"summary": "Вектор: моими словами"}})
    assert "touch" in _card(url, m)["locked"]
    ok(url, "sasha", {"op": "meeting.set", "id": m["id"], "set": {"topic": "другое"}})
    assert [x["summary"] for x in _live(url)] == ["Вектор: моими словами"]
    # Личная встреча гаснет всегда — даже взятая в руки (точка №9).
    ok(url, "sasha", {"op": "meeting.set", "id": m["id"], "set": {"sphere": "home"}})
    assert _live(url) == []
    bad = ops(url, "sasha", {"op": "meeting.set", "id": m["id"], "unlock": ["lunch"]})[0]
    assert not bad["ok"] and "lunch" in bad["error"]


def test_old_service_record_is_not_a_second_truth(mt):
    """Старая служба встреч (без флага meeting_touch) шлёт запись сама: та же встреча — запись сервера, не дубль."""
    import uuid

    url = mt["url"]
    summary_mode(url)
    m = push(url)
    old = {"op": "interaction.add", "op_id": str(uuid.uuid4()), "data": {
        "at": "2026-10-12T11:56:00+03:00", "kind": "zoom", "summary": "Регулярная по сделке: итог службы",
        "project_id": str(mt["project"]), "person_ids": [str(mt["Олег"])], "source": "meeting",
        "source_ref": "https://old-host.example.org:8443/m/215"}}
    # Карточка ещё не разобрана — по ней записи нет: отказ словами, а не вторая правда.
    r = ops(url, "sasha", old)[0]
    assert not r["ok"] and "ведёт сервер" in r["error"], r
    _name(url, m["id"], client="Вектор", topic="счета")
    r = ops(url, "sasha", {**old, "op_id": str(uuid.uuid4())})[0]
    assert r["ok"] and r["existing"] and r["row"]["summary"].startswith("Вектор: счета: "), r
    assert len(_live(url)) == 1
    # Встречи в базе ещё нет — запись как раньше, но вторая живая той же встречи — нет (одна на встречу).
    data = {**old["data"], "source_ref": "https://meetings.example.org/m/999"}
    first = ops(url, "sasha", {"op": "interaction.add", "data": data})[0]
    again = ops(url, "sasha", {"op": "interaction.add", "data": {**data, "summary": "ещё раз"}})[0]
    assert first["ok"] and again["ok"] and again["row"]["id"] == first["row"]["id"], (first, again)
    assert len(_live(url, "999")) == 1
    # Заметка владельца (не встреча) — как всегда.
    assert ok(url, "sasha", {"op": "interaction.add", "data": {"at": "2026-10-12T15:00:00+03:00", "kind": "note",
                                                              "summary": "Созвонились", "source": "web"}})


def test_twins_keep_one_record_at_the_fuller(mt):
    """Zoom и Plaud одной встречи: запись одна — у той, где больше текста; вторая настоящая встреча дня — своя."""
    url = mt["url"]
    a = push(url, ref="401", started_at="2026-10-13T10:00:00+03:00", ended_at="2026-10-13T10:40:00+03:00")
    b = push(url, ref="402", kind="meeting", started_at="2026-10-13T10:02:00+03:00", ended_at="2026-10-13T10:41:00+03:00",
             transcript=TRANSCRIPT + "\n[00:03:00] Олег Ветров: И ещё про залог, подробно и с цифрами.")
    for x in (a, b):
        _name(url, x["id"], client="Вектор", topic="счета")
    assert [r["source_ref"] for r in _live(url)] == ["https://meetings.example.org/m/402"]
    assert _live(url)[0]["kind"] == "meeting"
    # Полную убрали из списка — запись переходит ко второй половине.
    ok(url, "sasha", {"op": "meeting.set", "id": b["id"], "set": {"archived": True}})
    assert [r["source_ref"] for r in _live(url)] == ["https://meetings.example.org/m/401"]
    # Встреча днём с тем же клиентом, без пересечения, — своя запись.
    c = push(url, ref="403", started_at="2026-10-13T15:00:00+03:00", ended_at="2026-10-13T15:30:00+03:00")
    _name(url, c["id"], client="Вектор", topic="модель")
    assert len(_live(url)) == 2


def test_owner_lock_on_one_twin_holds_the_whole_pair(mt):
    """Владелец убрал запись встречи (половина Zoom, она вела пару), а вторая половина (Plaud) стала полнее — запись не
    возвращается через неё: замок touch любого из двойников — замок всей пары (проверка 15.10.2026)."""
    url = mt["url"]
    a = push(url, ref="401", started_at="2026-10-13T10:00:00+03:00", ended_at="2026-10-13T10:40:00+03:00")
    b = push(url, ref="402", started_at="2026-10-13T10:02:00+03:00", ended_at="2026-10-13T10:41:00+03:00",
             transcript=TRANSCRIPT + "\n[00:03:00] Олег Ветров: И ещё про залог.")
    for x in (a, b):
        _name(url, x["id"], client="Вектор", topic="счета")
    [r] = _live(url)
    assert r["source_ref"].endswith("/m/402")
    ok(url, "sasha", {"op": "interaction.delete", "id": str(r["id"])})
    assert ("touch" not in _card(url, a)["locked"], "touch" in _card(url, b)["locked"]) == (True, True)
    # Служба прислала 401 с длинной расшифровкой — теперь ведёт она; разбор лёг — записи всё равно нет.
    push(url, ref="401", started_at="2026-10-13T10:00:00+03:00", ended_at="2026-10-13T10:40:00+03:00",
         transcript=TRANSCRIPT + "\n[00:03:00] Олег Ветров: И ещё про залог, подробно, с цифрами и сроками по каждому траншу.")
    _name(url, a["id"], client="Вектор", topic="счета")
    assert _live(url) == []
    assert meetings.touch_all(url, since="2026-10-13")["actions"] == {"под замком": 2}
    # «Вернуть» у той, что владелец убирал, — пара снова по правилу: запись у полной (теперь 401).
    ok(url, "sasha", {"op": "meeting.set", "id": b["id"], "unlock": ["touch"]})
    [r2] = _live(url)
    assert r2["source_ref"].endswith("/m/401")


def test_recognised_speaker_joins_without_renaming(mt):
    """№60: служба узнала спикера (голос, ответ владельца) — встреча уходит с тем же текстом и person_id в raw.speakers:
    он встаёт в участники без переразбора, гость с тем же именем уходит из гостей, запись хронологии — следом."""
    url = mt["url"]
    with dela_db.session(url, "sasha", via="app") as c:
        petr, ivan = (c.execute("INSERT INTO crm.people (name, owner_id) VALUES (%s, 'sasha') RETURNING id", (n,)).fetchone()["id"]
                      for n in ("Пётр Новиков", "Иван Тестов"))
    m = push(url)
    card = name_it(url, m["id"], client="Вектор", topic="счета",
                   people=[{"name": "Олег Ветров", "person": "Олег Ветров"}, {"name": "Петя из банка", "person": ""}])
    assert card["guests"] == ["Петя из банка"] and card["person_ids"] == [str(mt["Олег"])]
    again = push(url, raw={"speakers": [{"label": "SPEAKER_02", "name": "Петя из банка", "person_id": str(petr)},
                                        {"label": "SPEAKER_00", "name": "Саша", "person_id": str(mt["Саша"])}]})
    assert not again["changed"] and again["named"]
    after = store.view(url, "sasha", "meeting", id=m["id"], lines="none")
    assert after["person_ids"] == [str(mt["Олег"]), str(petr)] and after["guests"] == []
    assert after["named_at"] == card["named_at"]  # без переразбора
    [r] = _live(url)
    assert r["person_ids"] == [mt["Олег"], petr]
    # Замок people — слово владельца: новый узнанный сам в участники не встаёт.
    ok(url, "sasha", {"op": "meeting.set", "id": m["id"], "set": {"person_ids": [str(mt["Олег"])]}})
    push(url, raw={"speakers": [{"label": "SPEAKER_03", "name": "Иван Тестов", "person_id": str(ivan)}]})
    assert store.view(url, "sasha", "meeting", id=m["id"], lines="none")["person_ids"] == [str(mt["Олег"])]
    # Встреча ещё не разобрана (разборщик не успел или его нет — тестовая копия Дел): узнанный — уже участник.
    fresh = push(url, ref="216")
    push(url, ref="216", raw={"speakers": [{"label": "SPEAKER_01", "name": "Иван Тестов", "person_id": str(ivan)}]})
    got = store.view(url, "sasha", "meeting", id=fresh["id"], lines="none")
    assert got["person_ids"] == [str(ivan)] and not got["named"]


def test_meeting_batches_take_dela_title(mt):
    """№43: пачка «Нового» встречи (любого статуса) живёт названием Дел — после разбора, правки владельца и
    переименования клиента."""
    url = mt["url"]
    m = push(url)

    def sug(title, **kw):
        return {"op": "suggestion.create", "suggestion": {
            "kind": "create", "source": "meeting", "source_ref": "https://meetings.example.org/m/215",
            "batch_ref": "meeting:215", "batch_title": "Регулярная по сделке · 12.10", "quote": "…",
            "payload": {"title": title}, **kw}}

    a, b = (x["suggestion"] for x in ok(url, "sasha", sug("Перевыставить счёт"), sug("Таблицы чувствительности")))
    ok(url, "sasha", {"op": "suggestion.decide", "id": b["id"], "decision": "reject"})

    def titles():
        with dela_db.session(url, "sasha") as c:
            return {r["batch_title"] for r in c.execute(
                "SELECT batch_title FROM tasks.suggestions WHERE batch_ref = 'meeting:215'").fetchall()}

    assert titles() == {"Регулярная по сделке · 12.10"}
    _name(url, m["id"], client="Вектор", topic="счета")
    assert titles() == {"Вектор: счета · 12.10"}
    ok(url, "sasha", {"op": "meeting.set", "id": m["id"], "set": {"topic": "залог"}})
    assert titles() == {"Вектор: залог · 12.10"}
    ok(url, "sasha", {"op": "project.set", "id": mt["project"], "set": {"name": "Новый Вектор"}})
    assert titles() == {"Новый Вектор: залог · 12.10"}
    # Сверка, пришедшая после разбора, — сразу с названием Дел.
    late = ok(url, "sasha", sug("Позвонить в банк"))[0]["suggestion"]
    assert late["batch_title"] == "Новый Вектор: залог · 12.10"


def test_meetings_touch_command_counts_and_dry_writes_nothing(mt, tmp_path, capsys, monkeypatch):
    """Разово после установки: записи встреч с 01.09 — по карточкам (догнать потерянные, взять сделку и людей, убрать
    личные). --dry — посчитать и ничего не записать."""
    url = mt["url"]
    summary_mode(url)
    with dela_db.session(url, "sasha", "svc:test") as c:
        deal = c.execute("SELECT id FROM crm.deals WHERE project_id = %s", (mt["project"],)).fetchone()["id"]

        def card(ref, at, sphere="work", named="claude-opus-5-5", locked=(), **kw):
            return c.execute(
                "INSERT INTO crm.meetings (ref, url, started_at, duration_min, owner_id, sphere, project_id, deal_id, label, "
                "topic, title, person_ids, summary, named_at, named_by, locked) VALUES (%s, %s, %s, 40, 'sasha', %s, %s, %s, "
                "'Вектор', 'счета', 'Вектор: счета', %s, 'Итог.', CASE WHEN %s::text IS NULL THEN NULL ELSE now() END, %s, "
                "%s) RETURNING id",
                (ref, f"https://meetings.example.org/m/{ref}", at, sphere, mt["project"], kw.get("deal"),
                 kw.get("people", []), named, named, list(locked))).fetchone()["id"]

        card("601", "2026-09-10T11:00:00+03:00", deal=deal, people=[mt["Олег"]])  # потерянная сентябрьская
        card("602", "2026-09-20T11:00:00+03:00")                                   # запись службы — без сделки и людей
        card("603", "2026-09-25T11:00:00+03:00", sphere="home")                     # личная, а запись есть
        card("604", "2026-08-20T11:00:00+03:00")                                   # архив до 01.09 — не трогаем
        card("605", "2026-09-26T11:00:00+03:00", named=None)                       # клиент и «рабочая» — подсказка службы
        card("606", "2026-09-27T11:00:00+03:00", named="error: timeout")           # разбор не вышел — тоже ждёт
        card("607", "2026-09-28T11:00:00+03:00", locked=["touch"])                 # запись убрал человек — не трогаем
        for ref, at in (("602", "2026-09-20T11:00:00+03:00"), ("603", "2026-09-25T11:00:00+03:00")):
            c.execute("INSERT INTO crm.interactions (at, kind, summary, project_id, source, source_ref, owner_id) VALUES "
                      "(%s, 'meeting', 'запись службы', %s, 'meeting', %s, 'sasha')",
                      (at, mt["project"], f"https://old-host.example.org:8443/m/{ref}"))
    before = [(r["id"], r["summary"], r["deleted_at"]) for r in _records(url)]
    # Командой: вхолостую — числа словами, в базе ничего.
    from pravka_dela import __main__ as cli

    env = tmp_path / "dela.env"
    env.write_text(f"DELA_DB_URL={url}\n", encoding="utf-8")
    monkeypatch.delenv("DELA_DB_URL", raising=False)
    assert cli.main(["--env", str(env), "meetings", "touch", "--dry"]) == 0
    said = capsys.readouterr().out
    assert said.startswith("вхолостую (ничего не записано): встреч с 2026-09-01: 6; записи в хронологии — создано 1, "
                           "обновлено 1, убрано 1, без изменений 3 (ждёт разбора 2, под замком 1)"), said
    assert "под замком — запись в руках человека, не трогаю: №607 28.09 «Вектор: счета»" in said, said
    assert [(r["id"], r["summary"], r["deleted_at"]) for r in _records(url)] == before
    dry = meetings.touch_all(url, dry=True)
    assert dry["dry"] and dry["meetings"] == 6, dry  # с 01.09
    assert dry["actions"] == {"заведена": 1, "обновлена": 1, "убрана": 1, "ждёт разбора": 2, "под замком": 1}, dry
    assert [h["ref"] for h in dry["held"]] == ["607"]
    assert [(r["id"], r["summary"], r["deleted_at"]) for r in _records(url)] == before  # ничего не записано
    real = meetings.touch_all(url)
    assert real["actions"] == dry["actions"] and not real["dry"]
    got = {r["source_ref"].rsplit("/", 1)[1]: r for r in _live(url)}
    assert set(got) == {"601", "602"}
    assert (got["601"]["deal_id"], got["601"]["person_ids"]) == (deal, [mt["Олег"]])
    assert got["602"]["summary"] == "Вектор: счета: Итог."
    assert meetings.touch_all(url)["actions"] == {"нечего": 3, "ждёт разбора": 2, "под замком": 1}  # повтор — ничего


def test_migration_21_over_meeting_records(admin_dsn):
    """dela_0021 поверх Дел, где записи встреч уже есть: живые дубли одной встречи (разные адреса службы) разводятся до
    уникального индекса, подпись — svc:migrate; прочие записи не тронуты; замок touch проходит проверку."""
    import secrets as _secrets
    from urllib.parse import urlparse, urlunparse

    import psycopg
    from psycopg import sql

    name = f"pa_mig21_{_secrets.token_hex(4)}"
    with psycopg.connect(admin_dsn, autocommit=True) as a:
        a.execute(sql.SQL("CREATE DATABASE {} ENCODING 'UTF8' TEMPLATE template0").format(sql.Identifier(name)))
    u = urlparse(admin_dsn)
    url = urlunparse((u.scheme, u.netloc, "/" + name, "", "", ""))
    try:
        files = dict(dela_db._sql_files())
        before = {k: v for k, v in files.items() if k.startswith("dela_") and k < "dela_0021.sql"}
        with psycopg.connect(url, autocommit=True) as c:
            c.execute("CREATE SCHEMA core")
            c.execute("CREATE TABLE core.migrations (name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())")
            dela_db.apply_pending(c, before)
        with dela_db.session(url, "system", "test") as c:
            c.execute("INSERT INTO crm.users (id, name, role) VALUES ('sasha', 'Саша', 'owner')")
            pid = c.execute("INSERT INTO crm.projects (name, sphere, kind, owner_id) VALUES ('Вектор', 'work', 'client', 'sasha') "
                            "RETURNING id").fetchone()["id"]
            ids = [c.execute("INSERT INTO crm.interactions (at, kind, summary, project_id, source, source_ref, owner_id, created_at) "
                             "VALUES ('2026-10-01T11:00:00+03:00', 'meeting', %s, %s, 'meeting', %s, 'sasha', %s) RETURNING id",
                             (s, pid, src, at)).fetchone()["id"]
                   for s, src, at in (("первая", "https://old-host.example.org:8443/m/77", "2026-10-01T12:00:00+03:00"),
                                      ("дубль", "https://meetings.example.org/m/77", "2026-10-02T12:00:00+03:00"),
                                      ("другая", "https://meetings.example.org/m/78", "2026-10-02T12:00:00+03:00"),
                                      ("заметка", None, "2026-10-02T12:00:00+03:00"))]
            mids = {}
            for ref, h in (("77", 11), ("78", 13), ("79", 15), ("80", 17)):
                mids[ref] = c.execute(
                    "INSERT INTO crm.meetings (ref, url, started_at, duration_min, owner_id, sphere, project_id, title, "
                    "named_at, named_by) VALUES (%s, %s, %s, 30, 'sasha', 'work', %s, 'Вектор: счета', now(), 'test') "
                    "RETURNING id", (ref, f"https://meetings.example.org/m/{ref}", f"2026-10-01T{h}:00:00+03:00",
                                     pid)).fetchone()["id"]
            mid = mids["77"]
            more = {ref: c.execute("INSERT INTO crm.interactions (at, kind, summary, project_id, source, source_ref, owner_id) "
                                   "VALUES ('2026-10-01T15:00:00+03:00', 'meeting', %s, %s, 'meeting', %s, 'sasha') RETURNING id",
                                   (f"запись {ref}", pid, f"https://meetings.example.org/m/{ref}")).fetchone()["id"]
                    for ref in ("79", "80")}
        # До установки замка не было: решения человека о записях встреч — только в журнале. 78 — переписал своими
        # словами, 79 — убрал; 80 правила только служба (svc:meetings) — это не решение человека.
        with dela_db.session(url, "system", "sasha") as c:
            c.execute("UPDATE crm.interactions SET summary = 'Вектор: моими словами' WHERE id = %s", (ids[2],))
            c.execute("UPDATE crm.interactions SET deleted_at = now() WHERE id = %s", (more["79"],))
        with dela_db.session(url, "system", "svc:meetings") as c:
            c.execute("UPDATE crm.interactions SET summary = 'итог службы' WHERE id = %s", (more["80"],))
        with dela_db.session(url, "system", "test") as c:
            rev = {r["id"]: r["rev"] for r in c.execute("SELECT id, rev FROM crm.interactions").fetchall()}
        with psycopg.connect(url, autocommit=True) as c:
            assert dela_db.apply_pending(c, files)[0] == "dela_0021.sql"
        with dela_db.session(url, "system", "test") as c:
            got = {r["id"]: r for r in c.execute("SELECT * FROM crm.interactions").fetchall()}
            hist = c.execute("SELECT entity_id FROM crm.history WHERE entity = 'crm.interactions' AND op = 'update' "
                             "AND actor = 'svc:migrate'").fetchall()
            assert got[ids[0]]["deleted_at"] is None and got[ids[1]]["deleted_at"] is not None
            assert all(got[i]["rev"] == rev[i] for i in (ids[0], ids[2], ids[3], more["79"], more["80"]))  # не тронуты
            assert [h["entity_id"] for h in hist] == [str(ids[1])]
            # Решения человека — замок touch: запись не затирается карточкой и не заводится заново.
            locks = {r["ref"]: r["locked"] for r in c.execute("SELECT ref, locked FROM crm.meetings").fetchall()}
            assert locks == {"77": [], "78": ["touch"], "79": ["touch"], "80": []}, locks
            touched = [c.execute("SELECT crm.meeting_touch(%s) AS a", (mids[r],)).fetchone()["a"] for r in ("78", "79", "80")]
            assert touched == ["под замком", "под замком", "обновлена"], touched
            got = {r["id"]: r for r in c.execute("SELECT * FROM crm.interactions").fetchall()}
            assert got[ids[2]]["summary"] == "Вектор: моими словами" and got[more["79"]]["deleted_at"] is not None
            assert c.execute("SELECT count(*) AS n FROM crm.interactions WHERE source_ref LIKE '%%/m/79' "
                             "AND deleted_at IS NULL").fetchone()["n"] == 0
            # Запись по карточке — в ту же живую строку, второй живой той же встречи база не даст.
            assert c.execute("SELECT crm.meeting_touch(%s) AS a", (mid,)).fetchone()["a"] == "обновлена"
            c.execute("UPDATE crm.meetings SET locked = '{touch}' WHERE id = %s", (mid,))
            with pytest.raises(psycopg.errors.UniqueViolation), c.transaction():
                c.execute("INSERT INTO crm.interactions (at, kind, summary, source, source_ref, owner_id) VALUES "
                          "(now(), 'meeting', 'ещё', 'meeting', 'https://x.example.org/m/77', 'sasha')")
    finally:
        with psycopg.connect(admin_dsn, autocommit=True) as a:
            a.execute(sql.SQL("DROP DATABASE IF EXISTS {} WITH (FORCE)").format(sql.Identifier(name)))


def test_meeting_record_takes_touch_deal_when_card_has_none(mt):
    """№3 (dela_0022): у карточки встречи сделки нет — запись в хронологии берёт «сделку касания»: у клиента одна живая
    сделка — она; вторая живая — по людям клиента встречи, если такая одна; сделка карточки сильнее."""
    url = mt["url"]
    with dela_db.session(url, "system", "test") as c:
        vec = c.execute("SELECT id FROM crm.deals WHERE project_id = %s", (mt["project"],)).fetchone()["id"]
        c.execute("UPDATE crm.deals SET created_at = '2026-10-01T10:00:00+03:00' WHERE id = %s", (vec,))  # до встречи
    m = push(url)
    _name(url, m["id"], client="Вектор", topic="счета", people=[{"name": "Олег Ветров", "person": "Олег Ветров"}])
    assert _card(url, m)["deal_id"] is None
    [r] = _live(url)
    assert r["deal_id"] == vec
    # Вторая живая сделка клиента, Олег — в ней: запись — к ней.
    with dela_db.session(url, "system", "test") as c:
        other = c.execute("INSERT INTO crm.deals (project_id, name, stage, person_ids, created_at) VALUES "
                          "(%s, 'Вектор: залог', 'lead', %s, '2026-10-01T10:00:00+03:00') RETURNING id",
                          (mt["project"], [mt["Олег"]])).fetchone()["id"]
    ok(url, "sasha", {"op": "meeting.set", "id": m["id"], "set": {"topic": "залог"}})
    [r] = _live(url)
    assert r["deal_id"] == other
    # Сделка на карточке — сильнее.
    ok(url, "sasha", {"op": "meeting.set", "id": m["id"], "set": {"deal_id": str(vec)}})
    [r] = _live(url)
    assert r["deal_id"] == vec
