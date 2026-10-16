"""Режим отладки Дел (debug.py, dela_0031, правило 28): записки владельца о багах с экраном данными.

Записка ложится одна на повтор (op_id и id записки), видит её только владелец (не команда, не службы с его токеном, не
узкие токены), вид — список и одна целиком, разбор — статус и ответ, текст и снимок не переписываются. Все данные
выдуманные: репозиторий публичный.
"""

from __future__ import annotations

import json
import uuid
from pathlib import Path

import psycopg
import pytest
from starlette.testclient import TestClient

from pravka_dela import api, debug, store, tokens
from pravka_dela import db as dela_db
from pravka_dela.config import Config
from test_dela_schema import dela  # noqa: F401  (фикстура)

CONTRACT = json.loads((Path(__file__).resolve().parents[1] / "contract" / "dela-debug.json").read_text(encoding="utf-8"))

SNAP = {"v": 1, "route": "#/today", "title": "Сегодня", "sub": "четверг, 16 октября",
        "mode": {"sphere": "work", "levels": ["grp"]},
        "card": {"kind": "task", "id": "0b9a8c7d-6e5f-4a3b-8c2d-1e0f9a8b7c6d", "num": 57, "title": "Иван Тестов: прислать модель",
                 "fields": {"срок": "16.10", "человек": "Иван Тестов", "проект": "Ромашка"}},
        "rows": [{"id": "0b9a8c7d-6e5f-4a3b-8c2d-1e0f9a8b7c6d", "num": 57, "title": "Иван Тестов: прислать модель",
                  "group": "Мониторить", "due": "2026-10-16", "person": "Иван Тестов", "project": "Ромашка", "in_view": True}],
        "rows_total": 1}
ACTIONS = [{"at": "2026-10-16T10:14:40+03:00", "kind": "ask", "text": "перенеси Ивана на пятницу"},
           {"at": "2026-10-16T10:14:52+03:00", "kind": "reply", "text": "Перенёс два дела на 17.10.", "data": {"changed": 2}}]


def add(url, user="sasha", text="Сказал перенести Ивана — перенёс и Ромашку", via="web", actor=None, **note):
    op = {"op": "debug.add", "op_id": str(uuid.uuid4()),
          "note": {"text": text, "source": "web", "version": "app.js 16.10", "screen": "1280×800", "snapshot": SNAP,
                   "actions": ACTIONS, **note}}
    return store.apply_ops(url, user, [op], via, actor)["results"][0], op


def test_add_numbers_and_keeps_screen(dela):
    r1, _ = add(dela)
    r2, _ = add(dela, text="Второе: «Сейчас» не обновилось", source="phone", screen="412×915 сложен")
    assert r1["ok"] and r2["ok"], (r1, r2)
    assert (r1["note"]["num"], r2["note"]["num"]) == (1, 2)
    assert r1["note"]["status"] == "new" and r1["note"]["via"] == "web" and r1["note"]["has_shot"] is False
    one = store.view(dela, "sasha", "debug", id="1")["note"]
    assert one["snapshot"]["card"]["num"] == 57 and one["actions"][1]["kind"] == "reply"
    assert one["version"] == "app.js 16.10" and one["screen"] == "1280×800"
    assert "debug" in store.FEATURES and "debug.add" in store.HANDLERS and "debug" in store.VIEWS


def test_repeat_is_harmless(dela):
    """Телефон повторил пачку (ответ сервера потерялся): тот же op_id — тот же ответ; тот же id записки с новым op_id —
    прежняя записка, второй не появляется."""
    nid = str(uuid.uuid4())
    r1, op = add(dela, id=nid)
    again = store.apply_ops(dela, "sasha", [op], "app")["results"][0]
    assert again == r1
    r3, _ = add(dela, id=nid, text="другой текст той же записки")
    assert r3["ok"] and r3.get("again") and r3["note"]["num"] == 1 and r3["note"]["text"] == r1["note"]["text"]
    assert len(store.view(dela, "sasha", "debug")["items"]) == 1
    bad, _ = add(dela, id="не-uuid")
    assert not bad["ok"] and "не uuid" in bad["error"]


def test_words_are_checked(dela):
    r, _ = add(dela, text="   ")
    assert not r["ok"] and "пустая" in r["error"]
    r, _ = add(dela, source="telegram")
    assert not r["ok"] and "web или phone" in r["error"]
    # Будущее время устройства — миг приёма; кривой снимок — пустой объект; длинная версия — обрезана.
    r, _ = add(dela, at="2099-01-01T00:00:00+03:00", snapshot=["не", "объект"], version="x" * 300)
    assert r["ok"] and r["note"]["said_at"].startswith("20") and not r["note"]["said_at"].startswith("2099")
    assert len(r["note"]["version"]) == debug.SHORT_MAX
    assert store.view(dela, "sasha", "debug", id=str(r["note"]["num"]))["note"]["snapshot"] == {}


def test_big_snapshot_is_cut_but_note_lands(dela):
    rows = [{"id": str(uuid.uuid4()), "num": i, "title": "Дело " + "очень длинное " * 40} for i in range(2000)]
    r, _ = add(dela, snapshot={**SNAP, "rows": rows, "rows_total": 2000}, actions=[{"kind": "op", "text": "x" * 9000}] * 150)
    assert r["ok"], r
    one = store.view(dela, "sasha", "debug", id=str(r["note"]["num"]))["note"]
    assert one["snapshot"]["rows_cut"] and 0 < len(one["snapshot"]["rows"]) < 2000 and one["snapshot"]["title"] == "Сегодня"
    assert len(json.dumps(one["snapshot"], ensure_ascii=False)) <= debug.SNAPSHOT_MAX
    assert len(one["actions"]) == debug.ACTIONS_MAX and one["actions"][0]["cut"] and len(one["actions"][0]["text"]) <= 500


def test_shot_only_image_data(dela):
    good, _ = add(dela, shot="data:image/jpeg;base64," + "A" * 1000)
    bad, _ = add(dela, shot="https://example.com/x.png")
    assert good["note"]["has_shot"] and not bad["note"]["has_shot"]
    one = store.view(dela, "sasha", "debug", id=str(bad["note"]["num"]))["note"]
    assert one["shot"] is None and "shot_dropped" in one["snapshot"]
    assert store.view(dela, "sasha", "debug", id=str(good["note"]["num"]))["note"]["shot"].startswith("data:image/jpeg")


def test_only_owner_sees_and_writes(dela):
    add(dela)
    r, _ = add(dela, user="natasha")
    assert not r["ok"] and "только владельцу" in r["error"]
    with pytest.raises(store.OpError, match="только владельцу"):
        store.view(dela, "natasha", "debug")
    # RLS: прямой запрос команды строк не видит; синк команды — без поля debug.
    with dela_db.session(dela, "natasha", via="app") as c:
        assert c.execute("SELECT count(*) AS n FROM crm.debug_notes").fetchone()["n"] == 0
    assert "debug" not in store.sync(dela, "natasha", 0)
    # Строку на не-владельца не положит даже system (триггер), текст и снимок не переписываются.
    with pytest.raises(psycopg.errors.RaiseException, match="только владельцу"):
        with dela_db.session(dela, "system", "test") as c:
            c.execute("INSERT INTO crm.debug_notes (owner_id, num, text, source) VALUES ('natasha', 99, 'x', 'web')")
    with pytest.raises(psycopg.errors.RaiseException, match="не переписывают"):
        with dela_db.session(dela, "sasha", via="app") as c:
            c.execute("UPDATE crm.debug_notes SET text = 'другое' WHERE num = 1")
    # Коннектор и командная строка (один вход — debug.tool) под членом команды — тот же отказ, и включить строку он не
    # может: не владельцу её нет (поезд 16.10.2026, приёмка).
    for action in ("list", "on", "off"):
        assert debug.tool(dela, "natasha", action).startswith("Не вышло: Отладка — только владельцу"), action
    assert debug.tool(dela, "natasha", "show", "1").startswith("Не вышло")
    assert debug.tool(dela, "natasha", "set", "1", "fixed", "чужой ответ").startswith("Не вышло")
    assert store.view(dela, "sasha", "debug", id="1")["note"]["status"] == "new"


def test_services_and_narrow_tokens_are_closed(dela):
    add(dela)
    cl = TestClient(api.build(Config(db_url=dela)))
    h = lambda kind, name: {"Authorization": "Bearer " + tokens.issue(dela, "sasha", kind, name)}  # noqa: E731
    op = {"op": "debug.add", "op_id": str(uuid.uuid4()), "note": {"text": "x", "source": "web"}}
    for name in ("dengi", "eda", "sport", "dom"):  # узкие — белым списком путей и видов
        assert cl.get("/api/view/debug", headers=h("service", name)).status_code == 403
        assert cl.post("/api/ops", headers=h("service", name), json={"ops": [op]}).status_code == 403
    svc = h("service", "meetings")  # служба с токеном владельца: вид — 403, операция — отказ словами
    assert cl.get("/api/view/debug", headers=svc).status_code == 403
    r = cl.post("/api/ops", headers=svc, json={"ops": [op]}).json()["results"][0]
    assert not r["ok"] and "службе meetings" in r["error"]
    phone = h("device", "fold")
    assert cl.get("/api/view/debug", headers=phone).json()["items"][0]["num"] == 1
    r = cl.post("/api/ops", headers=phone, json={"ops": [{**op, "op_id": str(uuid.uuid4())}]}).json()["results"][0]
    assert r["ok"] and r["note"]["num"] == 2 and r["note"]["via"] == "app"


def test_setting_in_me_and_sync(dela):
    cl = TestClient(api.build(Config(db_url=dela)))
    phone = {"Authorization": "Bearer " + tokens.issue(dela, "sasha", "device", "fold")}
    assert store.sync(dela, "sasha", 0)["debug"] == {"on": False, "new": 0, "last": 0, "answered": None}
    assert "debug" in cl.get("/api/me", headers=phone).json()["features"]
    assert debug.tool(dela, "sasha", "on").startswith("Режим отладки включён")
    assert cl.get("/api/me", headers=phone).json()["settings"]["debug"] is True
    add(dela)
    s = store.sync(dela, "sasha", 0)["debug"]
    assert s["on"] and s["new"] == 1 and s["last"] == 1
    # Не владелец включить себе может (это его настройка), но строки у него нет: поля в синке нет, вид закрыт.
    store.apply_ops(dela, "natasha", [{"op": "user.settings", "settings": {"debug": True}}], "web")
    assert "debug" not in store.sync(dela, "natasha", 0)
    assert debug.tool(dela, "sasha", "off").startswith("Режим отладки выключен")
    assert store.sync(dela, "sasha", 0)["debug"]["on"] is False


def test_view_list_filters_and_one(dela):
    for i in range(3):
        add(dela, text=f"Записка {i + 1}")
    store.apply_ops(dela, "sasha", [{"op": "debug.set", "id": "2", "status": "fixed", "answer": "Поправил."}], "web")
    v = store.view(dela, "sasha", "debug")
    assert [x["num"] for x in v["items"]] == [3, 2, 1] and v["counts"] == {"new": 2, "taken": 0, "fixed": 1, "not_bug": 0, "later": 0}
    assert v["items"][0]["route"] == "#/today" and v["items"][0]["title"] == "Сегодня" and "snapshot" not in v["items"][0]
    assert [x["num"] for x in store.view(dela, "sasha", "debug", status="open")["items"]] == [3, 1]
    assert [x["num"] for x in store.view(dela, "sasha", "debug", status="fixed,later")["items"]] == [2]
    assert [x["num"] for x in store.view(dela, "sasha", "debug", limit="1")["items"]] == [3]
    one = store.view(dela, "sasha", "debug", id="№2")["note"]
    assert one["answer"] == "Поправил." and one["snapshot"]["title"] == "Сегодня"
    assert store.view(dela, "sasha", "debug", id=one["id"])["note"]["num"] == 2
    with pytest.raises(store.OpError, match="нет записки"):
        store.view(dela, "sasha", "debug", id="99")
    with pytest.raises(store.OpError, match="статус"):
        store.view(dela, "sasha", "debug", status="всякое")


def test_set_status_and_answer(dela):
    add(dela)
    run = lambda **op: store.apply_ops(dela, "sasha", [{"op": "debug.set", **op}], "web")["results"][0]  # noqa: E731
    r = run(id="1", status="taken")
    assert r["ok"] and r["note"]["status"] == "taken" and r["note"]["status_by"] == "sasha" and r["note"]["status_at"]
    r = run(id="1", status="не баг", answer="Так и задумано: «Сейчас» — до пяти.")
    assert r["note"]["status"] == "not_bug" and r["note"]["answer"].startswith("Так и задумано")
    assert not run(id="1")["ok"] and "поменять нечего" in run(id="1")["error"]
    assert not run(id="1", status="закрыто")["ok"]
    assert not run(id="7", status="fixed")["ok"]
    rev = r["note"]["rev"]
    assert run(id="1", status="not_bug")["note"]["rev"] == rev  # то же самое — без новой версии
    # Слова статусов — владельца (приёмка 16.10.2026): «новое / разбираю / починено / не баг / потом»; словом в set —
    # и они, и прежние слова ветки («взял», «новая»).
    assert [debug.STATUS_WORD[s] for s in debug.STATUSES] == ["новое", "разбираю", "починено", "не баг", "потом"]
    assert {debug.status_of(w) for w in ("разбираю", "Разбираю", "взял", "в работе")} == {"taken"}
    assert debug.status_of("новое") == debug.status_of("новая") == "new"
    assert run(id="1", status="разбираю")["note"]["status"] == "taken"
    assert "разбираю" in debug.tool(dela, "sasha", "list")


def test_claude_reads_and_answers(dela):
    """Коннектор и командная строка — один вход (debug.tool): список, записка целиком словами, разбор от claude."""
    add(dela)
    add(dela, text="Вторая записка")
    out = debug.tool(dela, "sasha", "list")
    assert out.startswith("Записки отладки (новое и разбираю): 2") and "№2" in out and "№1" in out
    card = debug.tool(dela, "sasha", "show", "1")
    assert "Что было на экране:" in card and "#57 Иван Тестов: прислать модель" in card and "срок: 16.10" in card
    assert "reply: Перенёс два дела" in card and "перенеси Ивана на пятницу" in card
    done = debug.tool(dela, "sasha", "set", "1", "починено", "Поправил разбор: человек — по точному имени.", via="cli")
    assert done == "№1: починено · ответ записан"
    one = store.view(dela, "sasha", "debug", id="1")["note"]
    assert one["status_by"] == "claude" and one["answer"].startswith("Поправил")
    assert "№1" not in debug.tool(dela, "sasha", "list") and "№1" in debug.tool(dela, "sasha", "list", show_all=True)
    assert debug.tool(dela, "sasha", "show", "5").startswith("Не вышло")
    assert debug.tool(dela, "sasha", "set", "2", "-", "только ответ").endswith("ответ записан")


def test_cli_as_owner_and_refuses_others(dela, tmp_path, monkeypatch, capsys):
    """`python -m pravka_dela debug …` — от владельца (с завода), `--user X` — от X: члену команды — отказ словами и
    код 1, записка не тронута."""
    from pravka_dela import __main__ as cli

    add(dela)
    env = tmp_path / "dela.env"
    env.write_text(f"DELA_DB_URL={dela}\n", encoding="utf-8")
    monkeypatch.delenv("DELA_DB_URL", raising=False)
    assert cli.main(["--env", str(env), "debug", "list"]) == 0
    assert "№1" in capsys.readouterr().out
    assert cli.main(["--env", str(env), "debug", "list", "--user", "natasha"]) == 1
    assert "только владельцу" in capsys.readouterr().out
    assert cli.main(["--env", str(env), "debug", "show", "1", "--json", "--user", "natasha"]) == 1
    assert cli.main(["--env", str(env), "debug", "set", "1", "починено", "Чужой ответ.", "--user", "natasha"]) == 1
    assert store.view(dela, "sasha", "debug", id="1")["note"]["status"] == "new"
    capsys.readouterr()
    assert cli.main(["--env", str(env), "debug", "set", "1", "разбираю", "Смотрю."]) == 0
    assert capsys.readouterr().out.strip() == "№1: разбираю · ответ записан"


def test_card_text_shows_nesting(dela):
    """Поезд 16.10.2026 (правило 27): веб и телефон шлют вложенность данными — Claude читает её словами: отступ «↳»,
    «внутри #12 …», место, сколько внутри открыто (и свёрнут ли родитель), «Внутри» карточки строкой на дело, вложить и
    подъём родителя — в действиях."""
    snap = {**SNAP, "mode": {"all_tree": True},
            "card": {"kind": "task", "id": "p", "num": 12, "title": "Отправить термшит Ивану",
                     "fields": {"inside": ["1. #14 Взять цифры · pos 1", "2. #13 Набросать драфт · pos 2"], "inside_done": 1,
                                # Комментарии карточки (проверка вагона 3): приватный — с пометкой «только тебе».
                                "comments": ["2026-10-16 09:30 · sasha · только тебе · Основание из Telegram"]}},
            "rows": [{"id": "p", "num": 12, "title": "Отправить термшит Ивану", "inside": 2, "in_view": True},
                     {"id": "k2", "num": 14, "title": "Взять цифры", "in": "#12 Отправить термшит Ивану", "pos": 1, "depth": 1,
                      "in_view": True},
                     {"id": "q", "num": 40, "title": "Свёрнутый родитель", "inside": 3, "folded": True, "in_view": False}],
            "rows_total": 3}
    acts = [{"at": "2026-10-16T10:14:40+03:00", "kind": "op",
             "text": "task.set «Взять цифры» — внутрь «#12 Отправить термшит Ивану», место 1"},
            {"at": "2026-10-16T10:15:00+03:00", "kind": "op_result",
             "text": "всё внутри сделано — поднят «#12 Отправить термшит Ивану», на сегодня"}]
    r, _ = add(dela, snapshot=snap, actions=acts)
    card = debug.tool(dela, "sasha", "show", str(r["note"]["num"]))
    assert "  * #12 Отправить термшит Ивану — внутри открыто 2" in card, card
    assert "  *   ↳ #14 Взять цифры — внутри #12 Отправить термшит Ивану · место 1" in card, card
    assert "#40 Свёрнутый родитель — внутри открыто 3 (свёрнуто)" in card, card
    assert "    inside:\n      1. #14 Взять цифры · pos 1\n      2. #13 Набросать драфт · pos 2" in card, card
    assert "    comments:\n      2026-10-16 09:30 · sasha · только тебе · Основание из Telegram" in card, card
    assert "op: task.set «Взять цифры» — внутрь «#12" in card and "op_result: всё внутри сделано" in card, card


def test_snapshot_shows_reminders_and_later(dela):
    """Поезд 16.10.2026 (вагоны 3 и 4, правило 29): снимок несёт захват данными — у напоминания «напоминание <когда>», у
    дела «потом» — день возврата и как сказано; строки «Напоминания» и «Вернулось» — тоже строки списка (веб собирает и
    .rem-row, телефон — «Все дела → Напоминания»). Claude читает их словами, а не хвостом JSON; карточка — полями."""
    snap = {**SNAP, "route": "#/all", "title": "Все дела",
            "card": {"kind": "task", "id": "r", "num": 70, "title": "Отправить счёт в Контуре",
                     "fields": {"kind": "remind", "remind_at": "2026-10-16T12:00:00+03:00"}},
            "rows": [{"id": "b", "num": 71, "title": "Позвонить Ивану", "group": "Вернулось",
                      "back": "потом · вернётся 2026-10-16 («на неделе»)", "in_view": True},
                     {"id": "r", "num": 70, "title": "Отправить счёт в Контуре", "group": "Напоминания",
                      "remind": "напоминание 2026-10-16T12:00:00+03:00", "in_view": True}],
            "rows_total": 2}
    r, _ = add(dela, snapshot=snap)
    card = debug.tool(dela, "sasha", "show", str(r["note"]["num"]))
    assert "#71 Позвонить Ивану — Вернулось · потом · вернётся 2026-10-16 («на неделе»)" in card, card
    assert "#70 Отправить счёт в Контуре — Напоминания · напоминание 2026-10-16T12:00:00+03:00" in card, card
    assert "    kind: remind" in card, card
    # Веб: строки «Напоминания» и «Вернулось» с id, снимок их собирает и пишет remind / back.
    static = Path(debug.__file__).parent / "static"
    js, app = (static / "debug.js").read_text(encoding="utf-8"), (static / "app.js").read_text(encoding="utf-8")
    assert "main.list .rem-row[data-id]" in js and "x.remind = 'напоминание '" in js and "x.back = 'потом · вернётся '" in js
    assert "if (row.classList.contains('rem-row')) x.in_view = inWin(row);" in js  # «в окне» — тем же правилом, что строки дел
    assert "'data-id': t.id" in app[app.index("function remindRow("):app.index("function remindBox(")]
    assert "'data-id': t.id" in app[app.index("function backBox("):app.index("const ideasOn")]


def test_snapshot_shows_plan_block(dela):
    """Поезд 16.10.2026 (вагоны 3 и 6, правила 28 и 30): блок «Сейчас на завтра» в снимке — веб шлёт шапку блока,
    «N дня в «Сейчас»» и отмеченное заранее, телефон — строку плана и отмеченное; кандидаты — в «new» П-номерами со
    «почему · откуда» и днём. Claude читает это словами, а не хвостом JSON."""
    web = {**SNAP, "route": "#/now", "title": "Сейчас",
           "plan": {"head": "Сейчас на завтра сегодня сделано 2 из 4 · завтра встреч ~3 — возьми 3 · отмечено 1 из 5",
                    "stuck": ["3 дня в «Сейчас»: Собрать отчёт — разбить, отдать или убрать?"],
                    "marked": ["Иван Тестов: прислать договор"]},
           "new": [{"n": "П1", "id": "s1", "kind": "update", "source": "plan", "title": "Ответить Петру",
                    "hint": "Пётр ждёт 6 дней · руками 10.10", "status": "pending", "day": "2026-10-19"}]}
    r, _ = add(dela, snapshot=web)
    card = debug.tool(dela, "sasha", "show", str(r["note"]["num"]))
    assert "  «Сейчас на завтра»: Сейчас на завтра сегодня сделано 2 из 4 · завтра встреч ~3 — возьми 3 · отмечено 1 из 5" in card, card
    assert "    3 дня в «Сейчас»: Собрать отчёт — разбить, отдать или убрать?" in card, card
    assert "    отмечено: Иван Тестов: прислать договор" in card, card
    assert "Пётр ждёт 6 дней · руками 10.10" in card and "2026-10-19" in card, card
    assert "  plan: " not in card, "блок — словами, не одной строкой JSON"
    phone = {**SNAP, "route": "nav:new/plan:2026-10-19", "title": "Сейчас на понедельник — кандидаты, отметить до пяти",
             "plan": {"line": "Сейчас на понедельник: предложено 3 · отмечено 1 из 5", "for_date": "2026-10-19",
                      "offered": 3, "pending": 2, "title": "Сейчас на пн 19.10 · встреч ~3 — возьми 3", "batch": True,
                      "marked": ["#61 Иван Тестов: прислать договор"]}}
    r, _ = add(dela, snapshot=phone, source="phone")
    card = debug.tool(dela, "sasha", "show", str(r["note"]["num"]))
    assert ("  «Сейчас на завтра» (пачка): Сейчас на понедельник: предложено 3 · отмечено 1 из 5 · Сейчас на пн 19.10 · "
            "встреч ~3 — возьми 3") in card, card
    assert "    отмечено: #61 Иван Тестов: прислать договор" in card, card
    # Контракт снимка описывает обе формы plan и поля кандидата в new (проверка вагона 6).
    words = CONTRACT["snapshot"]["plan"]
    for k in [*web["plan"], *phone["plan"]]:
        assert f"{k} — " in words, k
    assert all(k in CONTRACT["snapshot"]["new"] for k in web["new"][0]), CONTRACT["snapshot"]["new"]
    # Веб: снимок собирает блок плана (planBox) и кандидата словами planItem, а не «Уточнить: …».
    static = Path(debug.__file__).parent / "static"
    js, app = (static / "debug.js").read_text(encoding="utf-8"), (static / "app.js").read_text(encoding="utf-8")
    assert "document.querySelector('main.list .plan-box')" in js and "lines('.plan-marked')" in js
    st = app[app.index("function sugText("):app.index("function autoWords(")]
    assert "if (isPlan(s)) return" in st and "[p.why, p.from]" in st
    assert st.index("if (isPlan(s)) return") < st.index("return { title: 'Уточнить: ' + ref")


def test_migration_again_is_harmless(dela, cfg):
    """Сборка поезда (16.10.2026) дала файлу номер 31 (в ветке — dela_0024_debug.sql): на копии, где он уже стоял под
    прежним именем, dela_0031 применится второй раз — и не падает, и записки целы."""
    from importlib import resources

    # Ровно один файл заводит crm.debug_notes — и это dela_0031 (номер поезда, без «0024_debug» рядом).
    found = [(f.name, t) for f in (resources.files("pravka_dela") / "sql").iterdir() if f.name.startswith("dela_")
             and "CREATE TABLE IF NOT EXISTS crm.debug_notes" in (t := f.read_text(encoding="utf-8"))]
    assert [n for n, _ in found] == ["dela_0031.sql"], [n for n, _ in found]
    sql_text = found[0][1]
    add(dela)
    with psycopg.connect(cfg.db_url, autocommit=True) as c:
        with c.transaction():
            c.execute(sql_text)
    assert store.view(dela, "sasha", "debug")["items"][0]["num"] == 1


def test_contract_matches_server(dela):
    from test_dela_contract import fields

    assert CONTRACT["features"]["flag"] in store.FEATURES
    assert set(CONTRACT["ops"]) <= set(store.HANDLERS) and set(CONTRACT["views"]) <= set(store.VIEWS)
    ex = json.loads(json.dumps(CONTRACT["ops"]["debug.add"]["example"]))
    r = store.apply_ops(dela, "sasha", [ex], "app")["results"][0]
    assert r["ok"], r
    resp = {k: v for k, v in CONTRACT["ops"]["debug.add"]["response"].items()}
    fields(resp, store.jsonable(r), "debug.add")
    st = json.loads(json.dumps(CONTRACT["ops"]["debug.set"]["example"]))
    st["id"] = str(r["note"]["num"])
    assert store.apply_ops(dela, "sasha", [st], "app")["results"][0]["ok"]
    fields({k: v for k, v in CONTRACT["views"]["debug"]["response_list"].items() if k != "ok"}, store.view(dela, "sasha", "debug"),
           "вид debug")
    fields({k: v for k, v in CONTRACT["views"]["debug"]["response_one"].items() if k != "ok"},
           store.view(dela, "sasha", "debug", id=str(r["note"]["num"])), "вид debug?id")
    fields(CONTRACT["sync"]["example"], store.sync(dela, "sasha", 0)["debug"], "синк debug")
