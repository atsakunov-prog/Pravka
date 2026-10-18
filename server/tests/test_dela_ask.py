"""Правка дел словами (ask.py): что видит Claude, как его ответ становится правками, отмена.

Claude подменён: проверяется всё вокруг вызова, а не сама модель.
"""

from __future__ import annotations

import datetime as dt
import time

from starlette.testclient import TestClient

from pravka_dela import api, ask, parse, store, tokens
from pravka_dela.config import Config
from test_dela_parse import _people
from test_dela_schema import db_today, dela, project  # noqa: F401


def item(num, **kw):
    """Запись правки, как её вернёт Claude: всё пусто, кроме сказанного."""
    base = {"num": num, "title": "", "notes_add": "", "project": "", "deal": "", "person": "", "due": "", "due_time": "",
            "ball": "", "now": "", "estimate_min": 0, "labels_add": [], "labels_remove": [], "status": "", "into": ""}
    return {**base, **kw}


def mk(url, title, **kw):
    return store.apply_ops(url, "sasha", [{"op": "task.create", "task": {"title": title, **kw}}], "web")["results"][0]["task"]


def test_ask_edits_tasks_on_screen(dela):
    p = project(dela, "sasha", "ПТИЦ", aliases=["Птиц"])
    _people(dela)
    with parse.db.session(dela, "system", "test") as c:
        c.execute("INSERT INTO crm.people (name, short, owner_id) VALUES ('Наталья Ш', 'Наташа', 'sasha')")
    today = db_today(dela)
    late = (today - dt.timedelta(days=3)).isoformat()
    a = mk(dela, "Бюджет на квартал", due_date=late, project_id=str(p))
    b = mk(dela, "Смета по ремонту", due_date=late)
    c_ = mk(dela, "Закрыть акт")
    off = mk(dela, "Чужое на другой странице")
    seen = {}
    tomorrow = (today + dt.timedelta(days=1)).isoformat()

    def fake(system, user_text):
        seen["system"], seen["user"] = system, user_text
        return {"route": "edit", "reply": "Бюджет — первым делом сегодня, смету Наташе, акт закрыл.", "create": [], "changes": [
            item(a["num"], now="on", due=today.isoformat(), notes_add="сначала маркетинг"),
            item(b["num"], person="Наташа", ball="waiting", title="Наташа: смета по ремонту", due=tomorrow, project="Птиц"),
            item(c_["num"], status="done"),
            item(off["num"], due=tomorrow),  # названо не с этой страницы — его открытое дело
            item(99999, due=tomorrow),       # такого дела нет вовсе
            item(a["num"], due="-"),  # второй раз то же дело — не трогаем
        ], "_usage": {"input": 1000, "output": 200, "cache_write": 0, "cache_read": 3000}}

    scope = {"title": "Проект ПТИЦ", "task_ids": [a["id"], b["id"], c_["id"]], "project_id": str(p)}
    out = ask.run(dela, "sasha", "бюджет первым делом, смету Наташе к завтра, акт закрыл", scope, "", None, ask_fn=fake)
    assert "— ПТИЦ (Птиц)" in seen["system"] and "— Наташа (Наталья Ш)" in seen["system"]
    screen, other = seen["user"].split("ДРУГИЕ МОИ ОТКРЫТЫЕ ДЕЛА")
    assert "Страница: Проект ПТИЦ" in screen and "(просрочено)" in screen and "Чужое" not in screen
    assert "Входящие:" in other and f"#{off['num']} Чужое на другой странице" in other and "Бюджет" not in other
    assert f"{today.isoformat()} — сегодня" in seen["user"] and seen["user"].endswith("акт закрыл")
    assert out["route"] == "edit" and out["reply"].startswith("Бюджет")
    with parse.db.session(dela, "sasha", "t") as c:
        st = {r["num"]: r for r in c.execute("SELECT * FROM tasks.v_tasks")}
    assert st[a["num"]]["focus_on"] == today and st[a["num"]]["due_date"] == today and st[a["num"]]["notes"] == "сначала маркетинг"
    nb = st[b["num"]]
    assert (nb["person_short"], nb["ball"], nb["title"], nb["project_id"]) == ("Наташа", "waiting", "Наташа: смета по ремонту", p)
    assert st[c_["num"]]["status"] == "done"
    assert st[off["num"]]["due_date"].isoformat() == tomorrow  # названное по имени находится и не с этой страницы
    assert any("одного из названных дел нет ни на экране, ни среди открытых" in e for e in out["errors"])
    # Как было — для «Вернуть»: только изменённые поля и статус.
    undo = {u["num"]: u for u in out["changed"]}
    assert undo[a["num"]]["before"] == {"due_date": late, "focus_on": None, "notes": None}
    assert undo[c_["num"]]["status"] == ["open", "done"] and undo[c_["num"]]["before"] == {}
    assert ask.cost(fake("", "")["_usage"]) > 0
    with parse.db.session(dela, "system", "t") as c:
        spent = c.execute("SELECT value FROM crm.state WHERE key = 'llm_cost'").fetchone()["value"]
    assert spent["by"]["ask"] > 0 and spent["total"] == spent["by"]["ask"]


def test_ask_focus_create_and_new_route(dela):
    _people(dela)
    t = mk(dela, "Иван: прислать модель")

    def fake(system, user_text):
        assert "ДЕЛО (команда про него):" in user_text and "Иван: прислать модель" in user_text.split("ДЕЛА НА ЭКРАНЕ")[0]
        return {"route": "new", "reply": "", "changes": [item(t["num"], due="2099-01-30", due_time="9:30")],
                "create": [item(0, title="Позвонить Ивану", person="Ваня", ball="agenda")]}

    # У микрофона дела — всегда правка, даже если Claude решил, что это новое.
    out = ask.run(dela, "sasha", "перенеси на 30 января в полдесятого и напомни позвонить", {"focus": t["id"]}, "", None,
                  ask_fn=fake, parse_fn=lambda *a: (_ for _ in ()).throw(AssertionError("разбор не нужен")))
    assert out["route"] == "edit" and out["changed"][0]["after"] == {"due_date": "2099-01-30", "due_time": "09:30"}
    assert out["tasks"][0]["title"] == "Позвонить Ивану" and out["tasks"][0]["ball"] == "agenda"

    # Строка наверху, команда целиком про новые дела — разбор надиктовки, как у звёздочки.
    calls = []

    def fake_parse(system, user_text):
        calls.append(user_text)
        return {"tasks": [{"title": "Наташе сверку", "notes": "", "project": "", "person": "", "ball": "mine", "due": "",
                           "estimate_min": 0, "money": "", "want": False, "labels": []}], "notes": []}

    out = ask.run(dela, "sasha", "Наташе сверку к пятнице", {"task_ids": [t["id"]]}, "", None,
                  ask_fn=lambda s, u: {"route": "new", "reply": "", "changes": [], "create": []}, parse_fn=fake_parse)
    assert out["route"] == "new" and out["tasks"][0]["title"] == "Наташе сверку" and calls


def test_ask_sees_window_selection_and_card(dela):
    """Владелец 08.10.2026: «показал ему несколько дел… сказал, что не видит никаких дел». Экран — как в миг
    отправки: что в окне, что выбрано галочками, что открыто в карточке; остальное открытое — одной строкой."""
    a, b, c_, d = (mk(dela, t) for t in ("Полку повесить", "Учебник с дачи", "Карта для оплаты", "Смета ремонта"))
    done = mk(dela, "Старое сделанное")
    store.apply_ops(dela, "sasha", [{"op": "task.done", "id": done["id"]}], "web")
    scope = {"title": "Новое", "task_ids": [a["id"], b["id"], c_["id"]], "visible_ids": [a["id"], b["id"]],
             "selected_ids": [b["id"], c_["id"]], "open": d["id"]}
    msg = ask.context(dela, "sasha", "эти на субботу", scope)["message"]
    pick, rest = msg.split("ВЫБРАНО ГАЛОЧКАМИ (2) — «эти» про них:")[1].split("ОТКРЫТО В КАРТОЧКЕ СПРАВА:")
    assert f"#{b['num']} Учебник с дачи" in pick and f"#{c_['num']} Карта" in pick and "Полку" not in pick
    screen = rest.split("ДЕЛА НА ЭКРАНЕ (4), сверху вниз; «в окне» — 2")[1]
    assert rest.startswith(f" #{d['num']} Смета ремонта")  # открытое в карточке — на экране, хоть список его и не рисует
    assert f"в окне · #{a['num']} Полку" in screen and f"в окне · #{b['num']} Учебник" in screen
    assert f"\n#{c_['num']} Карта" in screen and "ДРУГИЕ МОИ" not in msg  # других открытых нет, сделанное не тащим

    # Пустой экран (страница без дел) — Claude всё равно видит открытые дела и находит названное.
    ctx = ask.context(dela, "sasha", "полку на субботу", {"title": "Клиенты", "task_ids": []})
    assert "ДЕЛА НА ЭКРАНЕ (0)" in ctx["message"] and f"#{a['num']} Полку повесить" in ctx["message"]
    assert "Старое сделанное" not in ctx["message"] and a["num"] in ctx["tasks"]


def sug(n, decision="accept", **kw):
    """Решение по предложению «Нового», как его вернёт Claude."""
    base = {"n": n, "decision": decision, "reason": "", "title": "", "notes_add": "", "project": "", "person": "", "due": "",
            "ball": "", "now": ""}
    return {**base, **kw}


def offer(url, user="sasha", **kw):
    return store.apply_ops(url, "system", [{"op": "suggestion.create", "suggestion": {
        "for_user": user, "source": "meeting", **kw}}], "test")["results"][0]["suggestion"]


def test_ask_decides_suggestions_on_new_page(dela):
    """«Новое»: «первое прими — это Наташе к пятнице, второе не надо, акт закрой, фонды — первым делом»."""
    p = project(dela, "sasha", "Альфа Групп", aliases=["Альфа"])
    _people(dela)
    with parse.db.session(dela, "system", "test") as c:
        c.execute("INSERT INTO crm.people (name, short, owner_id) VALUES ('Наталья Ш', 'Наташа', 'sasha')")
    today = db_today(dela)
    later, friday = (today + dt.timedelta(days=3)).isoformat(), (today + dt.timedelta(days=4)).isoformat()
    week = (today + dt.timedelta(days=7)).isoformat()
    act = mk(dela, "Акт сверки")
    fonds = mk(dela, "Список фондов", due_date=later)
    alfa = {"batch_ref": "meeting:1", "batch_title": "Альфа, 05.10"}
    beta = {"batch_ref": "meeting:2", "batch_title": "Бета, 04.10"}
    s1 = offer(dela, kind="create", quote="Иван пришлёт модель", **alfa,
               payload={"title": "Иван: прислать модель", "ball": "waiting", "person_name": "Иван Петров", "project_name": "Альфа"})
    s2 = offer(dela, kind="create", **alfa, payload={"title": "Идея: фонд"})
    s3 = offer(dela, kind="close", task_id=act["id"], quote="акт подписали", **beta)
    s4 = offer(dela, kind="update", task_id=fonds["id"], quote="фонды к следующей неделе", **beta,
               payload={"due_date": week, "note": "нужно 40 фондов"})
    theirs = offer(dela, user="natasha", kind="create", payload={"title": "Чужое предложение"})
    rest = offer(dela, kind="create", **beta, payload={"title": "Не на экране"})
    seen = {}

    def fake(system, user_text):
        seen["user"] = user_text
        # route new вместе с решениями — всё равно правка: решения не должны уйти в разбор надиктовки.
        return {"route": "new", "reply": "Модель — Наташе к пятнице, идею убрал, акт закрыл, фонды в «Сейчас».",
                "changes": [], "create": [], "suggestions": [
                    sug(1, title="Наташа: прислать модель", person="Наташа", ball="waiting", due=friday),
                    sug(2, "reject", reason="не дело, просто мысль"),
                    sug(3),
                    sug(4, notes_add="срочно", now="on"),
                    sug(5),                       # чужое: на экране его нет
                    sug(1, "reject"),             # второй раз то же — не трогаем
                    sug(4, project="Альфа", ball="mine"),
                ]}

    scope = {"title": "Новое", "task_ids": [], "suggestion_ids": [s1["id"], s2["id"], s3["id"], s4["id"], theirs["id"]]}
    out = ask.run(dela, "sasha", "первое прими это Наташе к пятнице второе не надо акт закрой фонды первым делом", scope, "", None,
                  ask_fn=fake, parse_fn=lambda *a: (_ for _ in ()).throw(AssertionError("разбор не нужен")))
    u = seen["user"]
    assert "НОВОЕ НА ЭКРАНЕ — ждут решения (4):" in u
    assert "Пачка: Альфа, 05.10\nП1. завести: «Иван: прислать модель» · Альфа · жду от Иван Петров — «Иван пришлёт модель»" in u
    assert f"Пачка: Бета, 04.10\nП3. закрыть #{act['num']} Акт сверки — основание: «акт подписали»" in u
    assert f"П4. уточнить #{fonds['num']} Список фондов: срок {week}; подробности: нужно 40 фондов — «фонды к следующей неделе»" in u
    assert "Чужое" not in u and "Не на экране" not in u and "П5" not in u
    assert out["route"] == "edit" and out["reply"].startswith("Модель")
    assert any("П5" in e for e in out["errors"])

    with parse.db.session(dela, "sasha", "t") as c:
        st = {str(r["id"]): r for r in c.execute("SELECT * FROM tasks.suggestions")}
        made = c.execute("SELECT * FROM tasks.v_tasks WHERE id = %s", (st[s1["id"]]["result_task_id"],)).fetchone()
        tasks = {r["num"]: r for r in c.execute("SELECT * FROM tasks.v_tasks")}
        comments = [r["text"] for r in c.execute("SELECT text FROM tasks.comments WHERE task_id = %s", (fonds["id"],))]
    # Принято с правкой — «поправлено»: автоматика узнает, что её поправили.
    assert st[s1["id"]]["status"] == "edited"
    assert (made["title"], made["person_short"], made["ball"], made["due_date"], made["project_id"]) == (
        "Наташа: прислать модель", "Наташа", "waiting", dt.date.fromisoformat(friday), p)
    assert (st[s2["id"]]["status"], st[s2["id"]]["reason"]) == ("rejected", "не дело, просто мысль")
    assert st[s3["id"]]["status"] == "accepted" and tasks[act["num"]]["status"] == "done"
    f = tasks[fonds["num"]]
    assert st[s4["id"]]["status"] == "edited" and f["due_date"] == dt.date.fromisoformat(week)
    assert (f["notes"], f["focus_on"], f["project_id"]) == ("срочно", today, None)
    assert comments == ["Бета, 04.10: нужно 40 фондов"]
    assert st[rest["id"]]["status"] == "pending" and theirs["id"] not in st  # чужое Саше и не видно
    with parse.db.session(dela, "system", "t") as c:
        assert c.execute("SELECT status FROM tasks.suggestions WHERE id = %s", (theirs["id"],)).fetchone()["status"] == "pending"

    d = {x["n"]: x for x in out["decided"]}
    assert sorted(d) == [1, 2, 3, 4]
    assert d[1]["created"] and d[1]["num"] == made["num"] and d[1]["after"]["person_id"]
    assert d[2]["decision"] == "reject" and d[2]["reason"] == "не дело, просто мысль" and "id" not in d[2]
    assert d[3]["status"] == ["open", "done"] and d[3]["before"] == {}
    assert d[4]["before"] == {"due_date": later, "focus_on": None, "notes": None} and d[4]["status"] is None

    # «Вернуть всё» — те же операции, что шлёт веб: заведённое отменить, поправленное и закрытое — как было.
    back = [{"op": "task.cancel", "id": d[1]["id"]}, {"op": "task.set", "id": d[4]["id"], "set": d[4]["before"]},
            {"op": "task.reopen", "id": d[3]["id"]}]
    assert all(r["ok"] for r in store.apply_ops(dela, "sasha", back, "web")["results"])
    with parse.db.session(dela, "sasha", "t") as c:
        tasks = {r["num"]: r for r in c.execute("SELECT * FROM tasks.v_tasks")}
    assert tasks[made["num"]]["status"] == "cancelled" and tasks[act["num"]]["status"] == "open"
    assert (tasks[fonds["num"]]["due_date"], tasks[fonds["num"]]["focus_on"], tasks[fonds["num"]]["notes"]) == (
        dt.date.fromisoformat(later), None, None)

    # Разобранное с другого устройства выпадает, а номера остаются местами на экране.
    def again(system, user_text):
        assert "(ничего — всё уже разобрано)" in user_text
        return {"route": "edit", "reply": "", "changes": [], "create": [], "suggestions": [sug(2)]}

    out = ask.run(dela, "sasha", "второе всё-таки прими", scope, "", None, ask_fn=again)
    assert out["decided"] == [] and any("П2" in e for e in out["errors"])


def _wait(client, h, jid):
    for _ in range(100):
        r = client.get(f"/api/ask/{jid}", headers=h).json()
        if r.get("status") != "run":
            return r
        time.sleep(0.05)
    raise AssertionError("правка не закончилась")


def test_ask_api(dela, monkeypatch):
    h = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'device', 'телефон')}"}
    t = mk(dela, "Сверка")
    client = TestClient(api.build(Config(db_url=dela)))
    r = client.post("/api/ask", headers=h, json={"text": "на завтра", "scope": {"task_ids": [t["id"]]}})
    assert r.status_code == 422 and "нет ключа" in r.json()["error"]
    monkeypatch.setattr(parse, "client", lambda key, proxy: object())  # клиент строит parse.client (в обёртке guard)
    seen = {}

    def fake(cl, s, u, model, effort, **_):
        seen["model"], seen["effort"] = model, effort
        return {"route": "edit", "reply": "Готово", "create": [], "changes": [item(t["num"], estimate_min=15)],
                "_usage": {"model": model, "input": 500, "output": 100, "cache_write": 0, "cache_read": 0}}

    monkeypatch.setattr(ask, "ask", fake)
    nat = {"Authorization": f"Bearer {tokens.issue(dela, 'natasha', 'device', 'телефон Наташи')}"}
    with TestClient(api.build(Config(db_url=dela, anthropic_key="sk-test"))) as client:
        assert client.post("/api/ask", headers=h, json={"text": "x", "scope": {"task_ids": ["nope"]}}).status_code == 400
        assert client.post("/api/ask", headers=h, json={"text": "x", "scope": {"suggestion_ids": ["nope"]}}).status_code == 400
        assert client.post("/api/ask", json={"text": "x"}).status_code == 401
        done = _wait(client, h, client.post("/api/ask", headers=h, json={"text": "15 минут", "scope": {"focus": t["id"]}}).json()["job"])
        assert done["status"] == "done" and done["reply"] == "Готово" and done["changed"][0]["after"] == {"estimate_min": 15}
        # Модель и глубина — дорога «talk» (13.10.2026): одна на всех, «Настройки» человека её не меняют.
        assert (seen["model"], seen["effort"]) == ("claude-opus-5-5", "low")
        store.apply_ops(dela, "sasha", [{"op": "user.settings", "settings": {"claude_model": "sonnet", "claude_effort": "medium"}}], "web")
        _wait(client, h, client.post("/api/ask", headers=h, json={"text": "15 минут", "scope": {"focus": t["id"]}}).json()["job"])
        assert (seen["model"], seen["effort"]) == ("claude-opus-5-5", "low")
        mine = client.get("/api/settings", headers=h).json()
        assert mine["routes"]["talk"] == {"model": "claude-opus-5-5", "effort": "low"} and mine["cost"]["by"]["ask"] > 0
        assert "claude-opus-5-5" in mine["cost"]["models"] and mine["cost"]["today"] == mine["cost"]["total"]
        theirs = client.get("/api/settings", headers=nat).json()
        assert "cost" not in theirs and "routes" in theirs


def test_schema_is_strict():
    def walk(node):
        if node.get("type") == "object":
            assert node["additionalProperties"] is False
            assert set(node["required"]) == set(node["properties"])
            for v in node["properties"].values():
                walk(v)
        if node.get("type") == "array":
            walk(node["items"])

    walk(ask.SCHEMA)
    walk(ask.SCHEMA_LITE)  # запасная (16.10.2026) — тоже строгая


def test_talk_screen_is_one_cached_head(dela):
    """Разговор под строкой (13.10.2026): экран — голова сообщения, одна на разговор (вторая реплика читает её из
    кэша); час, «Сейчас», разговор и команда — хвост. Ответ на вопрос — дела ссылками (refs)."""
    a, b = mk(dela, "Сверка с банком"), mk(dela, "Позвонить юристу")
    scope = {"title": "Сегодня", "task_ids": [a["id"], b["id"]], "talk_id": "t-1"}
    one = ask.context(dela, "sasha", "что сегодня важнее всего?", scope)
    assert one["message"] == one["screen"] + "\n\n" + one["tail"]
    assert "#%d Сверка с банком" % a["num"] in one["screen"] and "КОМАНДА" not in one["screen"] and ", сейчас " not in one["screen"]
    assert one["tail"].rstrip().endswith("КОМАНДА: что сегодня важнее всего?") and "В «Сейчас» сегодня" in one["tail"]
    store.apply_ops(dela, "sasha", [{"op": "task.set", "id": a["id"], "set": {"title": "Сверка с банком до обеда"}}], "web")
    two = ask.context(dela, "sasha", "а потом?", {**scope, "history": [{"said": "что важнее?", "reply": "Сверка", "done": ""}]})
    assert two["screen"] == one["screen"] and "РАЗГОВОР" in two["tail"]  # тот же разговор — та же голова
    fresh = ask.context(dela, "sasha", "а потом?", {**scope, "talk_id": "t-2"})
    assert "Сверка с банком до обеда" in fresh["screen"]  # новый разговор — свежий экран

    def fake(system, user_text):
        return {"route": "edit", "reply": "Сначала сверка, потом юрист.", "changes": [], "create": [], "suggestions": [], "card": [],
                "refs": [a["num"], 999999, b["num"], a["num"]], "_usage": None}

    out = ask.run(dela, "sasha", "что сегодня важнее всего?", scope, "", None, ask_fn=fake)
    assert [r["num"] for r in out["refs"]] == [a["num"], b["num"]] and not out["changed"]


def test_ask_sends_screen_as_cached_block():
    """Запрос: справочник и экран — две точки кэша, хвост — после них."""
    seen = {}

    class Stream:
        def __init__(self, **kw):
            seen.update(kw)

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def get_final_message(self):
            from types import SimpleNamespace as NS
            return NS(stop_reason="end_turn", model="claude-opus-5-5", content=[NS(type="text", text='{"route": "edit", "reply": "ок"}')],
                      usage=NS(input_tokens=10, output_tokens=5, cache_creation_input_tokens=0, cache_read_input_tokens=900))

    from types import SimpleNamespace as NS
    cl = NS(beta=NS(messages=NS(stream=lambda **kw: Stream(**kw))))
    data = ask.ask(cl, "СПРАВОЧНИК", "ХВОСТ", "claude-opus-5-5", "low", prefix="ЭКРАН")
    blocks = seen["messages"][0]["content"]
    assert blocks[0] == {"type": "text", "text": "ЭКРАН", "cache_control": {"type": "ephemeral"}} and blocks[1]["text"] == "ХВОСТ"
    # Справочник — часовой кэш, и он раньше пятиминутного экрана (правило API: 1h — до 5m).
    assert seen["system"][0]["cache_control"] == {"type": "ephemeral", "ttl": "1h"} and data["_usage"]["cache_read"] == 900


def test_cache_write_is_priced_by_ttl():
    """Запись часового кэша — по своей цене (у Opus $8 за млн против $5 у пятиминутной): учёт трат не должен путать."""
    from types import SimpleNamespace as NS

    from pravka_dela import llm

    msg = NS(model="claude-opus-5-5", usage=NS(input_tokens=100, output_tokens=10, cache_creation_input_tokens=30000,
                                               cache_read_input_tokens=0,
                                               cache_creation=NS(ephemeral_1h_input_tokens=24000, ephemeral_5m_input_tokens=6000)))
    u = llm.usage_of(msg, "claude-opus-5-5")
    assert (u["cache_write"], u["cache_write_1h"]) == (6000, 24000)
    assert abs(llm.cost(u) - (100 * 4 + 10 * 20 + 6000 * 5 + 24000 * 8) / 1e6) < 1e-9
    old = NS(model="claude-opus-5-5", usage=NS(input_tokens=1, output_tokens=1, cache_creation_input_tokens=50, cache_read_input_tokens=0))
    assert llm.usage_of(old, "x")["cache_write"] == 50 and "cache_write_1h" not in llm.usage_of(old, "x")


# ── Строка Claude: место в дереве, дела разговора, кнопки ответа (16.10.2026, записи 21–22) ─────


def _v(url, tid, user="sasha"):
    with parse.db.session(url, user, "t") as c:
        return c.execute("SELECT * FROM tasks.v_tasks WHERE id = %s", (tid,)).fetchone()


def test_task_line_marks_goal_and_place(dela):
    """Строка дела для Claude несёт место в дереве: «цель» (у практики — ритм), «под целью #N», «внутри #N». Без этого
    Claude на «Дереве жизни» не видел структуры и отсылал перетаскивать руками (запись 21)."""
    g = mk(dela, "Бегаю", goal=True, rhythm="3 раза в неделю")
    d = mk(dela, "Купить кроссовки", why_id=g["id"])
    i = mk(dela, "Выбрать размер", why_id=d["id"])
    today = db_today(dela)
    rg, rd, ri = (_v(dela, x["id"]) for x in (g, d, i))
    assert ask._task_line(rg, today).startswith(f"#{g['num']} Бегаю · цель · практика: 3 раза в неделю")
    assert "Входящие" not in ask._task_line(rg, today)  # у цели проекта нет — и «Входящие» про неё неправда
    assert ask._task_line(rd, today).startswith(f"#{d['num']} Купить кроссовки · под целью #{g['num']}")
    assert ask._task_line(ri, today, short=True).startswith(f"#{i['num']} Выбрать размер · внутри #{d['num']}")
    plain = ask._task_line(_v(dela, mk(dela, "Само по себе")["id"]), today)
    assert "цель" not in plain and "внутри" not in plain


def test_talk_sees_its_own_tasks_fresh_with_cached_head(dela):
    """Запись 22: «Да, ставь» — тот же разговор, голова экрана из кэша (25787 + 13368 токенов первой реплики), а дела,
    заведённого первой репликой, в ней нет — «мне пока не видно». Теперь реплика шлёт ids заведённого и поправленного,
    сервер кладёт их свежими в ХВОСТ («ДЕЛА ИЗ ЭТОГО РАЗГОВОРА»): голова та же (кэш цел), а в новое можно вложить. То же
    в карточке дела. Кнопки answers доходят до ответа run — только под вопросом."""
    top = mk(dela, "Расту в профессии", goal=True)
    old = mk(dela, "Читаю по главе в день", why_id=top["id"])
    scope = {"title": "Дерево жизни", "task_ids": [top["id"], old["id"]], "talk_id": "t-heavy-21", "rule_tried": True}
    one = ask.context(dela, "sasha", "поставь учу испанский в расту в профессии", scope)

    def first(system, user_text):
        return {"route": "edit", "reply": "Переложить «Читаю по главе в день» внутрь?", "changes": [], "suggestions": [], "card": [],
                "create": [{"title": "Учу испанский", "notes_add": "", "project": "", "person": "", "due": "", "ball": "", "now": "",
                            "into": f"#{top['num']}", "goal": True}],
                "answers": ["Да, переложи", "  Нет  ", "", 5, "Только его", "Четвёртое лишнее"], "_usage": None}

    out = ask.run(dela, "sasha", "поставь учу испанский в расту в профессии", scope, "", None, ask_fn=first)
    new = out["tasks"][0]
    assert out["answers"] == ["Да, переложи", "Нет", "Только его"]
    assert out["reply"] == "Завёл целью «Учу испанский» внутри «Расту в профессии». Переложить «Читаю по главе в день» внутрь?"
    hist = [{"said": "поставь учу испанский в расту в профессии", "reply": out["reply"], "done": "завёл: Учу испанский", "ids": [new["id"]]}]
    two = ask.context(dela, "sasha", "Да, переложи", {**scope, "history": hist})
    assert two["screen"] == one["screen"] and "Учу испанский" not in two["screen"]  # голова — из кэша, как была
    block = two["tail"].split("ДЕЛА ИЗ ЭТОГО РАЗГОВОРА")[1].split("РАЗГОВОР —")[0]
    assert f"#{new['num']} Учу испанский · цель · под целью #{top['num']}" in block
    assert new["num"] in two["tasks"]
    out = ask.run(dela, "sasha", "Да, переложи", {**scope, "history": hist}, "", None,
                  ask_fn=lambda s, u: {"route": "edit", "reply": "Переложил.", "changes": [item(old["num"], into=f"#{new['num']}")],
                                       "create": [], "answers": ["Ещё?"], "_usage": None})
    assert not out["errors"] and str(_v(dela, old["id"])["why_id"]) == new["id"]
    assert out["answers"] == []  # ответ без вопроса — без кнопок
    # Карточка дела: те же дела разговора — хвостом, их можно и править, и класть в них.
    x = mk(dela, "Слушаю подкаст на испанском")
    ctx = ask.context(dela, "sasha", "и это туда же", {"card": "task", "open": x["id"], "talk_id": "c-heavy-21", "history": hist})
    assert f"#{new['num']} Учу испанский" in ctx["tail"] and new["num"] in ctx["tasks"] and new["num"] in ctx["pool"]
    assert f"#{new['num']}" not in ctx["screen"]
    out = ask.run(dela, "sasha", "и это туда же", {"card": "task", "open": x["id"], "history": hist}, "", None,
                  ask_fn=lambda s, u: {"route": "edit", "reply": "", "changes": [item(x["num"], into=f"#{new['num']}")],
                                       "create": [], "_usage": None})
    assert not out["errors"] and str(_v(dela, x["id"])["why_id"]) == new["id"]
    assert out["reply"] == "Поставил «Слушаю подкаст на испанском» под цель «Учу испанский»."
    # Чужое и невидимое RLS не отдаёт: id из Входящих коллеги в разговоре Саши — мимо.
    hers = store.apply_ops(dela, "natasha", [{"op": "task.create", "task": {"title": "Личное коллеги"}}], "web")["results"][0]["task"]
    ctx = ask.context(dela, "sasha", "x", {"title": "Сегодня", "task_ids": [], "rule_tried": True,
                                           "history": [{"said": "a", "reply": "", "done": "", "ids": [hers["id"], "не uuid"]}]})
    assert "Личное коллеги" not in ctx["message"] and "ДЕЛА ИЗ ЭТОГО РАЗГОВОРА" not in ctx["message"]


def test_api_history_ids_only_uuid_and_twenty(dela, monkeypatch):
    """API: у реплики history — ещё ids (дела, которые она завела и поправила): только uuid, не больше 20; кривые —
    мимо без отказа (это память разговора), реплика без ids — без ключа."""
    import uuid as uu

    t = mk(dela, "Сверка с банком")
    seen = {}

    def fake_run(url, user, text, scope, key, proxy, via="web", actor=None, **_):
        seen["scope"] = scope
        return {"route": "edit", "reply": "ок", "answers": [], "changed": [], "tasks": [], "decided": [], "crm": [], "notes": [],
                "errors": []}

    monkeypatch.setattr(ask, "run", fake_run)
    h = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'device', 'веб-разговор')}"}
    ids = [str(uu.uuid4()) for _ in range(25)]
    hist = [{"said": "заведи", "reply": "Завёл.", "done": "завёл: x", "ids": ["nope", 7, None, ids[0], ids[0], *ids[1:]]},
            {"said": "и ещё", "reply": "", "done": "", "ids": "не список"}]
    with TestClient(api.build(Config(db_url=dela, anthropic_key="sk-test"))) as client:
        jid = client.post("/api/ask", headers=h, json={"text": "да", "scope": {"task_ids": [t["id"]], "history": hist}}).json()["job"]
        assert _wait(client, h, jid)["reply"] == "ок"
    got = seen["scope"]["history"]
    assert got[0]["ids"] == ids[:20] and "ids" not in got[1]
    assert set(got[1]) == {"said", "reply", "done"}


def test_schema_refused_retries_with_lite(monkeypatch, caplog):
    """56 полей схемы API может не принять (06.10.2026: «compiled grammar is too large»). Тогда — один повтор с
    SCHEMA_LITE (54: без answers и create.goal) и строка в журнал «схема не прошла»; час дальше — сразу запасная. Другой
    400 не повторяем."""
    import logging
    from types import SimpleNamespace as NS

    monkeypatch.setitem(ask._LITE, "until", 0.0)
    schemas = []

    class Bad(Exception):
        status_code = 400

    class Stream:
        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def get_final_message(self):
            return NS(stop_reason="end_turn", model="claude-opus-5-5", content=[NS(type="text", text='{"route": "edit", "reply": "ок"}')],
                      usage=NS(input_tokens=10, output_tokens=5, cache_creation_input_tokens=0, cache_read_input_tokens=0))

    def stream(**kw):
        sc = kw["output_config"]["format"]["schema"]
        schemas.append(sc)
        if sc is ask.SCHEMA:
            raise Bad("Error code: 400 - {'error': {'message': 'compiled grammar is too large'}}")
        return Stream()

    cl = NS(beta=NS(messages=NS(stream=stream)))
    with caplog.at_level(logging.WARNING, logger="dela.ask"):
        data = ask.ask(cl, "СПРАВОЧНИК", "ХВОСТ", "claude-opus-5-5", "low", prefix="ЭКРАН")
    assert data["reply"] == "ок" and schemas == [ask.SCHEMA, ask.SCHEMA_LITE] and "схема не прошла" in caplog.text
    ask.ask(cl, "СПРАВОЧНИК", "ХВОСТ", "claude-opus-5-5", "low")
    assert schemas[-1] is ask.SCHEMA_LITE and len(schemas) == 3  # час — сразу запасная, без лишнего отказа
    # Другой 400 (не схема) — не повторяем: ошибка как есть.
    monkeypatch.setitem(ask._LITE, "until", 0.0)

    def other(**kw):
        raise Bad("Error code: 400 - prompt is too long")

    cl = NS(beta=NS(messages=NS(stream=other)))
    try:
        ask.ask(cl, "С", "Х", "claude-opus-5-5", "low")
        raise AssertionError("должно было упасть")
    except Bad:
        pass
    assert ask._LITE["until"] == 0.0
