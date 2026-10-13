"""«Когда» в карточке дела (when.py, 13.10.2026): срок и напоминание одной строкой.

Свой разбор проверяется на часах «вторник, 14:20»; Claude подменён — проверяется всё вокруг вызова.
"""

from __future__ import annotations

import datetime as dt

import pytest
from starlette.testclient import TestClient

from pravka_dela import api, llm, store, tokens, when
from pravka_dela.config import Config
from test_dela_schema import db_today, dela, project  # noqa: F401

NOW = dt.datetime(2026, 10, 13, 14, 20, tzinfo=when.MSK)  # вторник
PLACES = ["дом", "офис", "Дача"]


def at(day: str, hm: str) -> str:
    return f"{day}T{hm}:00+03:00"


@pytest.mark.parametrize("said, want, words", [
    ("сегодня", {"due_date": "2026-10-13", "due_time": None}, "сегодня"),
    ("завтра", {"due_date": "2026-10-14", "due_time": None}, "завтра (ср 14.10)"),
    ("завтра в первой половине", {"due_date": "2026-10-14", "due_time": None, "remind_at": at("2026-10-14", "09:00"),
                                  "remind_place": None}, "завтра (ср 14.10), 9:00–13:00 · напомню в 9:00"),
    ("в пятницу вечером", {"due_date": "2026-10-16", "due_time": None, "remind_at": at("2026-10-16", "19:00"),
                           "remind_place": None}, "пт 16.10, 19:00–22:00 · напомню в 19:00"),
    ("через 2 часа", {"due_date": "2026-10-13", "due_time": None, "remind_at": at("2026-10-13", "16:20"), "remind_place": None},
     "сегодня · напомню в 16:20"),
    ("когда приеду на дачу", {"remind_place": "Дача", "remind_at": None}, "напомню, когда приедешь: Дача"),
    # Часы без «утра» и «вечера» — ближайшее впереди с 07:00 до 22:00 (как в общем промпте разбора).
    ("в 8", {"due_date": "2026-10-13", "due_time": "20:00", "remind_at": at("2026-10-13", "20:00"), "remind_place": None},
     "сегодня, 20:00 · напомню в 20:00"),
    ("в 11", {"due_date": "2026-10-14", "due_time": "11:00", "remind_at": at("2026-10-14", "11:00"), "remind_place": None},
     "завтра (ср 14.10), 11:00 · напомню в 11:00"),
    ("завтра в 3", {"due_date": "2026-10-14", "due_time": "15:00", "remind_at": at("2026-10-14", "15:00"), "remind_place": None},
     "завтра (ср 14.10), 15:00 · напомню в 15:00"),
    ("к 15:00", {"due_date": "2026-10-13", "due_time": "15:00"}, "сегодня, к 15:00"),
    ("15.10", {"due_date": "2026-10-15", "due_time": None}, "послезавтра (чт 15.10)"),
    ("к 20 октября", {"due_date": "2026-10-20", "due_time": None}, "вт 20.10"),
    ("в следующую пятницу", {"due_date": "2026-10-23", "due_time": None}, "пт 23.10"),
    ("на следующей неделе", {"due_date": "2026-10-19", "due_time": None}, "пн 19.10"),
    ("в пн", {"due_date": "2026-10-19", "due_time": None}, "пн 19.10"),
    ("во вторник", {"due_date": "2026-10-20", "due_time": None}, "вт 20.10"),  # сегодняшний день недели — через неделю
    ("через неделю", {"due_date": "2026-10-20", "due_time": None}, "вт 20.10"),
    ("напомни мне завтра в девять", {"due_date": "2026-10-14", "due_time": "09:00", "remind_at": at("2026-10-14", "09:00"),
                                     "remind_place": None}, "завтра (ср 14.10), 9:00 · напомню в 9:00"),
    ("завтра утром в 9", {"due_date": "2026-10-14", "due_time": "09:00", "remind_at": at("2026-10-14", "09:00"),
                          "remind_place": None}, "завтра (ср 14.10), 9:00 · напомню в 9:00"),
    ("в пятницу в 8 вечера", {"due_date": "2026-10-16", "due_time": "20:00", "remind_at": at("2026-10-16", "20:00"),
                              "remind_place": None}, "пт 16.10, 20:00 · напомню в 20:00"),
    ("когда приеду домой завтра", {"remind_place": "дом", "remind_at": None, "due_date": "2026-10-14", "due_time": None},
     "завтра (ср 14.10) · напомню, когда приедешь: дом"),
    ("нет", {"due_date": None, "due_time": None, "remind_at": None, "remind_place": None}, "без срока · без напоминания"),
    ("без напоминания", {"remind_at": None, "remind_place": None}, "без напоминания"),
    ("утром", {"due_date": "2026-10-14", "due_time": None, "remind_at": at("2026-10-14", "09:00"), "remind_place": None},
     "завтра (ср 14.10), 9:00–12:00 · напомню в 9:00"),  # утро сегодня прошло — завтра
])
def test_local_phrases(said, want, words):
    got = when.local(said, NOW, PLACES)
    assert got is not None, said
    assert got["set"] == want
    assert when.words(got, NOW.date()) == words


@pytest.mark.parametrize("said", ["как-нибудь потом", "когда созреет", "в 25", "после встречи с Иваном", "5", "15 потом"])
def test_local_says_it_does_not_know(said):
    assert when.local(said, NOW, PLACES) is None


def test_unknown_place_is_said_in_words():
    with pytest.raises(when.WhenError, match="нет места «баню» — есть: дом, офис, Дача"):
        when.local("когда приеду в баню", NOW, PLACES)


def test_only_date_for_nudge():
    assert when.local("в пятницу вечером", NOW, PLACES, only_date=True) == {"set": {"due_date": "2026-10-16"}}
    assert when.local("когда приеду домой", NOW, PLACES, only_date=True) is None


def model_answer(**kw):
    base = {"understood": True, "due": "", "due_time": "", "window_from": "", "window_to": "", "remind_at": "",
            "remind_place": "", "question": ""}
    return {**base, **kw}


def test_from_model_checks_what_it_sets():
    got = when.from_model(model_answer(due="2026-10-16", window_from="09:00", window_to="13:00", remind_at="2026-10-16 09:00"),
                          NOW, PLACES)
    assert got == {"set": {"due_date": "2026-10-16", "due_time": None, "remind_at": at("2026-10-16", "09:00"),
                           "remind_place": None}, "window": ["09:00", "13:00"]}
    # Напоминание без срока — срок в день напоминания (как в общем промпте разбора).
    got = when.from_model(model_answer(remind_at="2026-10-14 10:00"), NOW, PLACES)
    assert got["set"] == {"remind_at": at("2026-10-14", "10:00"), "remind_place": None, "due_date": "2026-10-14", "due_time": None}
    assert when.from_model(model_answer(remind_place="дачу"), NOW, PLACES)["set"] == {"remind_place": "Дача", "remind_at": None}
    with pytest.raises(when.WhenError, match="нет места"):
        when.from_model(model_answer(remind_place="баня"), NOW, PLACES)
    with pytest.raises(when.WhenError, match="какой день"):
        when.from_model(model_answer(understood=False, question="какой день?"), NOW, PLACES)
    with pytest.raises(when.WhenError, match="не понял день"):
        when.from_model(model_answer(due="пятница"), NOW, PLACES)


def test_schema_is_strict():
    s = when.SCHEMA
    assert s["additionalProperties"] is False and set(s["required"]) == set(s["properties"])


def test_understand_local_then_claude_and_spend(dela, monkeypatch):
    with pytest.raises(when.WhenError, match="пусто"):
        when.understand(dela, "sasha", "  ")
    got = when.understand(dela, "sasha", "завтра", now=NOW)
    assert got["ok"] and got["by"] == "local" and got["set"]["due_date"] == "2026-10-14"
    # Пока человек печатает — только свой разбор: непонятое не стоит денег.
    got = when.understand(dela, "sasha", "как только освобожусь после созвона", use_model=False, now=NOW)
    assert not got["ok"] and got["need_model"]
    seen = {}

    def fake(message):
        seen["message"] = message
        return {**model_answer(due="2026-10-15", remind_at="2026-10-15 18:30"),
                "_usage": {"model": "claude-sonnet-5-5", "input": 700, "output": 60, "cache_write": 0, "cache_read": 0}}

    got = when.understand(dela, "sasha", "как только освобожусь после созвона в четверг", ask_fn=fake, now=NOW)
    assert got["by"] == "claude" and got["words"] == "послезавтра (чт 15.10) · напомню в 18:30" and got["usd"] > 0
    assert "ФРАЗА: как только освобожусь после созвона в четверг" in seen["message"] and "вт 2026-10-13 — сегодня" in seen["message"]
    assert llm.spent(dela)["by"]["when"] > 0
    # Без ключа и без подмены — честно: «напиши проще».
    with pytest.raises(when.WhenError, match="напиши проще"):
        when.understand(dela, "sasha", "как только освобожусь", now=NOW)


def test_model_calls_are_limited_per_person(monkeypatch):
    monkeypatch.setattr(when, "_calls", {})
    assert all(when._room("u") for _ in range(when.MODEL_MAX))
    assert not when._room("u") and when._room("other")


def test_route_comes_from_svod(dela, monkeypatch):
    monkeypatch.setattr(llm, "_routes", {"at": -llm.ROUTES_EVERY, "value": {}})
    assert llm.route(dela, "task") == ("claude-opus-5-5", "low")
    store.apply_ops(dela, "sasha", [{"op": "svod.set", "key": llm.ROUTES_KEY, "author": "test",
                                     "value": {"task": {"model": "sonnet", "effort": "medium"}, "when": {"model": "gpt", "effort": "max"}}}], "web")
    monkeypatch.setattr(llm, "_routes", {"at": -llm.ROUTES_EVERY, "value": {}})
    assert llm.route(dela, "task") == ("claude-sonnet-5-5", "medium")
    assert llm.route(dela, "when") == ("claude-sonnet-5-5", "low")  # чужое слово — заводское


def test_when_view_api(dela):
    h = {"Authorization": f"Bearer {tokens.issue(dela, 'sasha', 'device', 'телефон-когда')}"}
    client = TestClient(api.build(Config(db_url=dela)))
    r = client.get("/api/view/when", params={"q": "завтра", "local": "1"}, headers=h).json()
    tomorrow = (db_today(dela) + dt.timedelta(days=1)).isoformat()
    assert r["ok"] and r["set"] == {"due_date": tomorrow, "due_time": None}
    r = client.get("/api/view/when", params={"q": "когда звёзды сойдутся", "local": "1"}, headers=h).json()
    assert not r["ok"] and r["need_model"]
    r = client.get("/api/view/when", params={"q": "когда звёзды сойдутся"}, headers=h).json()
    assert not r["ok"] and "Claude не настроен" in r["error"]
    r = client.get("/api/view/when", params={"q": "в пятницу вечером", "date": "1"}, headers=h).json()
    assert r["ok"] and list(r["set"]) == ["due_date"]
    assert client.get("/api/view/when", params={"q": "завтра"}).status_code == 401
