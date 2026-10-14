"""Книга трат Claude и советы экономии (dela_0017, spend.py, spend_advice.py).

Числа выдуманные: репозиторий публичный, а настоящие траты — деньги владельца.
"""

from __future__ import annotations

import copy
import datetime as dt
import json
from pathlib import Path
from types import SimpleNamespace as NS

import pytest
from starlette.testclient import TestClient

from pravka_dela import api, llm, spend, spend_advice, store, tokens
from pravka_dela import db as dela_db
from pravka_dela.config import Config
from pravka_dela.meetings import MSK
from test_dela_schema import dela  # noqa: F401  (фикстура)

CONTRACT = json.loads((Path(__file__).resolve().parents[1] / "contract" / "dela-spend.json").read_text(encoding="utf-8"))
EXAMPLE = CONTRACT["op_put"]["example"]


def today(url) -> dt.date:
    with dela_db.session(url, "system", "test") as c:
        return c.execute("SELECT crm.today() AS d").fetchone()["d"]


def row(day, service="phone", task="zasechka", model="claude-opus-5-5", **kw) -> dict:
    r = {"day": str(day), "service": service, "task": task, "model": model, "calls": 3, "batch_calls": 0,
         "input": 1000, "output": 2000, "cache_write": 0, "cache_write_1h": 500, "cache_read": 40000, "usd": 0.06,
         "usd_list": 0.06}
    r.update(kw)
    return r


def put(url, rows, user="sasha", actor=None, via="app", src="Fold"):
    op = {"op": "spend.put", "rows": rows}
    if src:
        op["src"] = src
    return store.apply_ops(url, user, [op], via, actor)["results"][0]


def book(url) -> list[dict]:
    with dela_db.session(url, "system", "test") as c:
        return c.execute("SELECT * FROM crm.claude_spend ORDER BY day, service, task, model, src").fetchall()


def test_contract_example_and_feature(dela):
    assert CONTRACT["features"]["flag"] in store.FEATURES
    ex = copy.deepcopy(EXAMPLE)
    for r in ex["rows"]:
        r["day"] = str(today(dela))
    r = store.apply_ops(dela, "sasha", [ex], "app")["results"][0]
    assert r["ok"] and r["put"] == 2 and r["changed"] == 2 and r["src"] == "Fold 7", r
    assert set(r) >= set(CONTRACT["op_put"]["result"])
    # Каталог контракта — подписи вида: служба и задача названы словами.
    assert spend.service_title("phone") == "Правка (телефон)" and spend.task_title("dela", "spend_advice") == "Советник по тратам"


def test_put_replaces_day_and_repeat_is_harmless(dela):
    d = today(dela)
    assert put(dela, [row(d)])["changed"] == 1
    again = put(dela, [row(d)])
    assert again["ok"] and again["changed"] == 0          # тот же итог дня — ничего не двигается
    assert put(dela, [row(d, usd=0.09, usd_list=0.09, calls=5)])["changed"] == 1
    got = book(dela)
    assert len(got) == 1 and float(got[0]["usd"]) == 0.09 and got[0]["calls"] == 5  # заменило, а не сложило
    # Другой телефон — своя строка: источники не складываются в одну.
    put(dela, [row(d)], src="Pixel")
    assert len(book(dela)) == 2


def test_validation_words(dela):
    d = today(dela)
    bad = [
        (row(d + dt.timedelta(days=3)), "из будущего"),
        (row(d - dt.timedelta(days=200)), "старше"),
        (row(d, calls=-1), "не меньше нуля"),
        (row(d, calls=1.5), "целое"),
        (row(d, usd=0.2, usd_list=0.1), "не меньше usd"),
        (row(d, task="Засечка"), "латиница"),
        ({**row(d), "prompt": "секрет"}, "нет таких полей"),
    ]
    for r, words in bad:
        res = put(dela, [r])
        assert not res["ok"] and words in res["error"], (r, res)
    res = put(dela, [row(d), row(d)])
    assert not res["ok"] and "дважды" in res["error"]
    res = put(dela, [row(d)], src=None)
    assert not res["ok"] and "src" in res["error"]
    assert book(dela) == []


def test_who_writes_what(dela):
    d = today(dela)
    # Телефон — только phone.
    res = put(dela, [row(d, service="meetings", task="clean")])
    assert not res["ok"] and "телефону нельзя" in res["error"]
    # Сборщик «Дома» — службы компа, но не телефон и не сами Дела; src с завода pc.
    res = put(dela, [row(d, service="meetings", task="clean")], actor="svc:dom", via="dom", src=None)
    assert res["ok"] and res["src"] == "pc", res
    for s in ("phone", "dela"):
        res = put(dela, [row(d, service=s, task="x")], actor="svc:dom", via="dom", src=None)
        assert not res["ok"] and "сборщику" in res["error"], res
    # Служба своим токеном — только себя.
    assert put(dela, [row(d, service="digest", task="window")], actor="svc:digest", via="digest", src=None)["ok"]
    res = put(dela, [row(d, service="meetings", task="clean")], actor="svc:digest", via="digest", src=None)
    assert not res["ok"] and "службе digest" in res["error"]


def test_family_phone_writes_own_owner_reads_all(dela):
    d = today(dela)
    assert put(dela, [row(d, usd=0.5, usd_list=0.5)], user="marianna", src="Телефон М")["ok"]
    assert put(dela, [row(d, usd=1.0, usd_list=1.0)])["ok"]
    v = store.view(dela, "sasha", "spend")
    assert v["periods"]["today"]["usd"] == 1.5
    assert {s["src"] for s in v["sources"]} == {"Fold", "Телефон М"}
    with pytest.raises(store.OpError, match="только владельцу"):
        store.view(dela, "marianna", "spend")
    with dela_db.session(dela, "marianna", via="app") as c:
        assert c.execute("SELECT count(*) AS n FROM crm.claude_spend").fetchone()["n"] == 1  # видит только своё
    assert "spend" not in store.sync(dela, "marianna", 0)
    assert store.sync(dela, "sasha", 0)["spend"]["week"] == 1.5


def test_dela_own_calls_add_up_with_tokens(dela):
    usage = {"model": "claude-opus-5-5", "input": 1000, "output": 500, "cache_write": 0, "cache_write_1h": 24000,
             "cache_read": 0}
    usd = llm.cost(usage, "claude-opus-5-5")
    llm.account(dela, "ask", usd, "claude-opus-5-5", usage=usage)
    llm.account(dela, "ask", usd, "claude-opus-5-5", usage={**usage, "cache_write_1h": 0, "cache_read": 24000})
    llm.account(dela, "krug", 0.01, "claude-opus-5-5", usage={"input": 6000, "output": 300}, batch=True)
    got = {(r["task"]): r for r in book(dela)}
    a = got["ask"]
    assert (a["src"], a["key"], a["calls"], a["cache_write_1h"], a["cache_read"]) == ("dela", "1", 2, 24000, 24000)
    assert abs(float(a["usd"]) - 2 * usd) < 1e-6 and a["exact"]
    k = got["krug"]
    assert (k["batch_calls"], float(k["usd"]), float(k["usd_list"])) == (1, 0.01, 0.02)
    # Прежний учёт Дел (llm_cost) — по-прежнему считается: «Настройки» веба не ломаются.
    assert llm.spent(dela)["by"]["ask"] == round(2 * usd, 4)
    # Чужому вызову (телефон шлёт итог) прибавка Дел не мешает: разные источники.
    v = store.view(dela, "sasha", "spend")
    t = {x["task"]: x for x in v["periods"]["today"]["tasks"]}
    assert t["ask"]["title"] == "Строка Claude и разговор" and t["ask"]["calls"] == 2
    # Кэш: 24 тыс. записи на час (+4 $/млн к входу) и 24 тыс. чтения (−3,8 $/млн) — почти в ноль; пачка — 0,01.
    assert v["periods"]["today"]["batch_saved"] == 0.01
    assert abs(v["periods"]["today"]["cache_saved"] - (24000 * (4 - 8) + 24000 * (4 - 0.2)) / 1e6) < 1e-4


def test_failed_answer_is_still_paid(dela):
    from pravka_dela import parse

    def refusing(system, user_text):
        msg = NS(usage=NS(input_tokens=900, output_tokens=40, cache_creation_input_tokens=0, cache_read_input_tokens=0,
                          cache_creation=None), model="claude-opus-5-5")
        raise llm.paid(parse.ParseError("Claude отказался разбирать этот текст"), msg, parse.MODEL)

    with pytest.raises(parse.ParseError):
        parse.run(dela, "sasha", "купить молоко", {}, key="", proxy=None, ask_fn=refusing)
    got = book(dela)
    assert len(got) == 1 and got[0]["task"] == "parse" and got[0]["input"] == 900


def test_backfill_once(dela):
    d = today(dela)
    with dela_db.session(dela, "system", "test") as c:
        c.execute("INSERT INTO crm.state (key, value) VALUES ('llm_cost', %s)",
                  (json.dumps({"total": 3.5, "days": {str(d - dt.timedelta(days=2)): 1.25, str(d): 2.25}}),))
    assert spend.backfill(dela) == 2
    assert spend.backfill(dela) == 0  # второй раз — ничего
    got = book(dela)
    assert [(r["task"], float(r["usd"]), r["exact"]) for r in got] == [("before", 1.25, False), ("before", 2.25, False)]


def test_view_periods_trend_and_order(dela):
    d = today(dela)
    put(dela, [row(d, task="zasechka", usd=1.0, usd_list=1.0),
               row(d - dt.timedelta(days=3), task="pravka", model="claude-sonnet-5-5", usd=0.4, usd_list=0.4),
               row(d - dt.timedelta(days=10), task="night_review", usd=2.0, usd_list=4.0, batch_calls=1, calls=1)])
    put(dela, [row(d, service="meetings", task="clean", model="claude-sonnet-5-5", usd=0.3, usd_list=0.6,
                   batch_calls=3)], actor="svc:dom", via="dom", src=None)
    v = store.view(dela, "sasha", "spend")
    w, m = v["periods"]["week"], v["periods"]["month"]
    assert w["usd"] == 1.7 and m["usd"] == 3.7 and v["prev_week"] == 2.0
    assert [s["service"] for s in w["services"]] == ["phone", "meetings"]       # дороже — выше
    assert [t["task"] for t in w["tasks"]][:2] == ["zasechka", "pravka"]
    assert w["batch_saved"] == 0.3 and m["batch_saved"] == 2.3
    # Два блока: «Телефон» и «Сервер» — свои итоги, службы и задачи; сверху — общий итог.
    ph, sv = w["blocks"]
    assert (ph["block"], ph["title"], ph["usd"]) == ("phone", "Телефон", 1.4)
    assert (sv["block"], sv["title"], sv["usd"]) == ("server", "Сервер", 0.3)
    assert [s["service"] for s in sv["services"]] == ["meetings"] and [t["task"] for t in sv["tasks"]] == ["clean"]
    assert m["blocks"][0]["usd"] == 3.4
    assert len(v["trend"]) == spend.TREND_DAYS and v["trend"][-1] == {"day": str(d), "usd": 1.3}
    assert v["gaps"] and v["tips"] is None
    # Значок: за 7 дней и сегодня.
    b = store.sync(dela, "sasha", 0)["spend"]
    assert (b["week"], b["today"], b["week_phone"], b["week_server"], b["tips"]) == (1.7, 1.3, 1.4, 0.3, None)


class FakeBatches:
    def __init__(self, answer: dict, usage=None):
        self.answer, self.sent, self.status = answer, [], "in_progress"
        self.usage = usage or NS(input_tokens=6000, output_tokens=3000, cache_creation_input_tokens=0,
                                 cache_read_input_tokens=0, cache_creation=None)

    def create(self, requests):
        self.sent.append(requests)
        return NS(id=f"msgbatch_{len(self.sent)}")

    def retrieve(self, bid):
        return NS(processing_status=self.status)

    def cancel(self, bid):
        self.status = "canceled"

    def results(self, bid):
        msg = NS(content=[NS(type="text", text=json.dumps(self.answer, ensure_ascii=False))], usage=self.usage,
                 model="claude-opus-5-5", stop_reason="end_turn")
        return iter([NS(custom_id="spend-advice", result=NS(type="succeeded", message=msg))])


ANSWER = {"note": "Неделя дороже прошлой из-за Засечки.",
          "tips": [{"where": "phone", "title": "Засечка: xhigh → high", "why": "Засечка — половина недели.", "how": "Модели → Засечка",
                    "save_usd_month": 6.5, "risk": "чуть реже ловит тонкие развилки"},
                   {"where": "server", "title": "Сверка встреч: medium", "why": "вход 50 тыс.", "how": "dela.py", "save_usd_month": 1.2,
                    "risk": "—"},
                   {"title": "  ", "why": "пусто", "how": "", "save_usd_month": 1, "risk": ""},
                   {"title": "Флибуста: low", "why": "", "how": "", "save_usd_month": "много", "risk": ""}]}


def test_advice_weekly_batch_red_dot_and_seen(dela):
    d = today(dela)
    b = FakeBatches(ANSWER)
    cl = NS(messages=NS(batches=b))
    sun = spend_advice.slot(dt.datetime.now(MSK))
    assert sun.weekday() == 6 and (sun.hour, sun.minute) == (21, 30)
    # Трат нет — ни запроса, неделя отмечена.
    assert spend_advice.tick(dela, "key", None, sun, cl) is None and b.sent == []
    later = sun + dt.timedelta(days=7)
    put(dela, [row(d, usd=2.0, usd_list=2.0)])
    # Не в слот (за час до) — ничего.
    assert spend_advice.tick(dela, "key", None, later - dt.timedelta(hours=1), cl) is None and b.sent == []
    out = spend_advice.tick(dela, "key", None, later, cl)
    assert out and out["sent"] and len(b.sent) == 1
    p = b.sent[0][0]["params"]
    assert p["model"] == "claude-opus-5-5" and p["output_config"]["effort"] == "medium"
    text = p["messages"][0]["content"]
    assert "Засечка: разбор фразы" in text and "НЕ УЧИТЫВАЕТСЯ" in text and "claude-opus-5-5:" in text
    assert "ТЕЛЕФОН (" in text and "СЕРВЕР (" in text and "- [телефон] Правка (телефон)" in text
    assert p["output_config"]["format"]["schema"]["properties"]["tips"]["items"]["properties"]["where"]["enum"] == ["phone", "server"]
    assert spend_advice.tick(dela, "key", None, later, cl) is None  # пачка в работе
    b.status = "ended"
    got = spend_advice.tick(dela, "key", None, later, cl)
    assert got["tips"] == 3  # совет без названия — не совет; «много» — не число, прикидка 0
    s = store.sync(dela, "sasha", 0)["spend"]
    assert s["tips"]["unseen"] and s["tips"]["count"] == 3
    tips = store.view(dela, "sasha", "spend")["tips"]
    assert tips["tips"][0]["where"] == "phone" and tips["tips"][1]["where"] == "server" and tips["tips"][2]["where"] == ""
    assert tips["tips"][0]["save_usd_month"] == 6.5 and tips["tips"][2]["save_usd_month"] == 0 and tips["note"].startswith("Неделя") and tips["seen_at"] is None
    # Траты советника — в книге: Дела, пачкой.
    adv = [r for r in book(dela) if r["task"] == "spend_advice"]
    assert len(adv) == 1 and adv[0]["batch_calls"] == 1 and float(adv[0]["usd"]) == round((6000 * 4 + 3000 * 20) / 1e6 / 2, 6)
    # В ту же неделю второй раз не шлёт.
    assert spend_advice.tick(dela, "key", None, later + dt.timedelta(minutes=5), cl) is None and len(b.sent) == 1
    # Открыл — точка гаснет; не владельцу нельзя.
    r = store.apply_ops(dela, "marianna", [{"op": "spend.seen"}], "app")["results"][0]
    assert not r["ok"] and "только владельцу" in r["error"]
    r = store.apply_ops(dela, "sasha", [{"op": "spend.seen", "id": tips["id"]}], "app")["results"][0]
    assert r["ok"], r
    assert store.sync(dela, "sasha", 0)["spend"]["tips"]["unseen"] is False


def test_advice_dry_message_only_numbers(dela):
    d = today(dela)
    put(dela, [row(d, usd=1.0, usd_list=1.0)])
    put(dela, [row(d, service="shabbat", task="study", usd=0.4, usd_list=0.8, batch_calls=3)], actor="svc:dom",
        via="dom", src=None)
    f = spend_advice.facts(dela, "sasha", d)
    msg = spend_advice.message(f)
    assert "[сервер] Шаббат · Подготовка главы [2]" in msg and "[телефон] Правка (телефон) · Засечка: разбор фразы [phone]" in msg
    assert "ТЕЛЕФОН (приложение Правка, ключ телефона): неделя $1.00" in msg and "СЕРВЕР (службы на домашнем компьютере): неделя $0.400" in msg
    assert f["total"] == 1.4


def test_api_dom_token_and_owner_only(dela):
    url = dela
    client = TestClient(api.build(Config(db_url=url)))
    dom = {"Authorization": f"Bearer {tokens.issue(url, 'sasha', 'service', 'dom')}"}
    phone = {"Authorization": f"Bearer {tokens.issue(url, 'sasha', 'device', 'Fold')}"}
    eda = {"Authorization": f"Bearer {tokens.issue(url, 'sasha', 'service', 'eda')}"}
    wife = {"Authorization": f"Bearer {tokens.issue(url, 'marianna', 'device', 'телефон М')}"}
    d = str(today(url))
    body = {"ops": [{"op": "spend.put", "rows": [row(d, service="digest", task="window")]}]}
    r = client.post("/api/ops", headers=dom, json=body).json()
    assert r["results"][0]["ok"] and r["results"][0]["src"] == "pc", r
    # Узкий токен «Дома» — по-прежнему ничего, кроме трат и чтения ui.*.
    assert client.post("/api/ops", headers=dom, json={"ops": [{"op": "task.create", "task": {"title": "x"}}]}).status_code == 403
    assert client.post("/api/ops", headers=dom, json={"ops": [{"op": "svod.set", "key": "ui.menu", "value": {}}]}).status_code == 403
    assert client.get("/api/view/spend", headers=dom).status_code == 403
    # Настройки владельца (и траты в них) узким токенам не положены (до 14.10.2026 «Дом», «Еда» и «Деньги» их видели).
    assert client.get("/api/settings", headers=dom).status_code == 403
    assert client.get("/api/settings", headers=eda).status_code == 403
    assert client.post("/api/ops", headers=eda, json=body).status_code == 403
    # Телефон владельца: свои траты пишет, вид и синк видит.
    pb = {"ops": [{"op": "spend.put", "src": "Fold", "rows": [row(d)]}]}
    assert client.post("/api/ops", headers=phone, json=pb).json()["results"][0]["ok"]
    assert "spend" in client.get("/api/me", headers=phone).json()["features"]
    v = client.get("/api/view/spend", headers=phone).json()
    assert v["ok"] and v["periods"]["today"]["usd"] == 0.12
    assert client.get("/api/sync?since=0", headers=phone).json()["spend"]["today"] == 0.12
    w = client.get("/api/view/spend", headers=wife).json()
    assert w["ok"] is False and "только владельцу" in w["error"]
