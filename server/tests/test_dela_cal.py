"""Календарь Google в Делах (dela_0040, cal.py): окно событий от службы встреч и вид cal для веба /t."""

from __future__ import annotations

import copy
import datetime as dt
import json
from pathlib import Path

import pytest

from pravka_dela import store
from pravka_dela import db as dela_db
from test_dela_schema import dela  # noqa: F401  (фикстура)

CONTRACT = json.loads((Path(__file__).resolve().parents[1] / "contract" / "dela-cal.json").read_text(encoding="utf-8"))


def today(url) -> dt.date:
    with dela_db.session(url, "system", "test") as c:
        return c.execute("SELECT crm.today() AS d").fetchone()["d"]


def at(day: dt.date, hm: str) -> str:
    """Момент по Москве ISO с поясом — как присылает служба встреч из Google."""
    return f"{day.isoformat()}T{hm}:00+03:00"


def put(url, events, a, b, user="sasha"):
    op = {"op": "cal.put", "from": str(a), "to": str(b), "events": events}
    return store.apply_ops(url, user, [op], "app")["results"][0]


def cal(url, user="sasha", **kw):
    return store.view(url, user, "cal", **kw)


def test_put_and_view_moscow_times_all_day_and_midnight(dela):
    d = today(dela)
    a, b = d - dt.timedelta(days=7), d + dt.timedelta(days=56)
    tid = "6f1c2d3e-4a5b-4c6d-8e9f-0a1b2c3d4e5f"
    r = put(dela, [
        {"id": "e1", "title": "  Созвон   с клиентом ", "start": at(d, "15:00"), "end": at(d, "15:45"),
         "link": "https://meet.example.com/abc", "url": "https://calendar.example.com/e1", "location": "Zoom"},
        # 10:00 UTC — 13:00 по Москве
        {"id": "e2", "title": "Утро", "start": f"{(d + dt.timedelta(days=1)).isoformat()}T10:00:00Z",
         "end": f"{(d + dt.timedelta(days=1)).isoformat()}T11:00:00Z", "busy": False},
        {"id": "e3", "title": "Отпуск", "all_day": True, "start_day": str(d + dt.timedelta(days=2)),
         "end_day": str(d + dt.timedelta(days=4))},
        # до полуночи — тот же день, не залезает в завтра
        {"id": "e4", "title": "Поздно", "start": at(d, "23:00"), "end": at(d + dt.timedelta(days=1), "00:00")},
        {"id": "e5", "title": "Встреча из дела", "start": at(d, "12:00"), "end": at(d, "12:30"), "task_id": tid},
        {"id": "e6", "title": "Кривая метка", "start": at(d, "09:00"), "end": at(d, "09:30"), "task_id": "не-uuid",
         "link": "javascript:alert(1)"},
    ], a, b)
    assert r["ok"] and r["put"] == 6 and r["changed"] == 6 and r["removed"] == 0, r
    v = cal(dela, since=str(d), until=str(d + dt.timedelta(days=5)))
    ev = {e["id"]: e for e in v["events"]}
    assert set(ev) == {"e1", "e2", "e3", "e4", "e5", "e6"}
    e1 = ev["e1"]
    assert (e1["title"], e1["start"], e1["end"], e1["mins"], e1["day"]) == ("Созвон с клиентом", "15:00", "15:45", 45, str(d))
    assert e1["link"] == "https://meet.example.com/abc" and e1["busy"] is True
    assert (ev["e2"]["start"], ev["e2"]["end"], ev["e2"]["busy"]) == ("13:00", "14:00", False)
    assert ev["e3"]["all_day"] and ev["e3"]["day"] == str(d + dt.timedelta(days=2)) and ev["e3"]["last_day"] == str(d + dt.timedelta(days=4))
    assert ev["e3"]["start"] is None
    assert ev["e4"]["last_day"] == str(d) and ev["e4"]["end"] == "00:00" and ev["e4"]["mins"] == 60
    assert ev["e5"]["task_id"] == tid
    assert ev["e6"]["task_id"] is None and ev["e6"]["link"] == ""  # чужое — не ссылка
    # Порядок: по дню, «весь день» выше, потом по началу.
    today_ids = [e["id"] for e in v["events"] if e["day"] == str(d)]
    assert today_ids == ["e6", "e5", "e1", "e4"]
    assert v["sync"] and v["sync"]["events"] == 6 and v["sync"]["stale"] is False
    # Многодневное видно и в окне, которое начинается внутри него.
    mid = cal(dela, since=str(d + dt.timedelta(days=3)), until=str(d + dt.timedelta(days=3)))
    assert [e["id"] for e in mid["events"]] == ["e3"]


def test_window_replaces_and_keeps_outside(dela):
    d = today(dela)
    far = d + dt.timedelta(days=90)
    put(dela, [{"id": "far", "title": "Далеко", "start": at(far, "10:00"), "end": at(far, "11:00")}],
        d + dt.timedelta(days=80), d + dt.timedelta(days=100))
    a, b = d - dt.timedelta(days=7), d + dt.timedelta(days=56)
    put(dela, [{"id": "x", "title": "Было", "start": at(d, "10:00"), "end": at(d, "11:00")},
               {"id": "y", "title": "Останется", "start": at(d, "12:00"), "end": at(d, "13:00")}], a, b)
    # Новое окно: x удалили в Google, y переименовали, z — новое, w — вне окна (не кладём).
    r = put(dela, [{"id": "y", "title": "Переименовали", "start": at(d, "12:00"), "end": at(d, "13:00")},
                   {"id": "z", "title": "Новое", "all_day": True, "start_day": str(d)},
                   {"id": "w", "title": "Вне окна", "start": at(far, "10:00"), "end": at(far, "11:00")}], a, b)
    assert (r["put"], r["changed"], r["removed"], r["outside"]) == (2, 2, 1, 1), r
    # Повтор той же присылки ничего не меняет.
    again = put(dela, [{"id": "y", "title": "Переименовали", "start": at(d, "12:00"), "end": at(d, "13:00")},
                       {"id": "z", "title": "Новое", "all_day": True, "start_day": str(d)}], a, b)
    assert (again["changed"], again["removed"]) == (0, 0), again
    ids = {e["id"]: e["title"] for e in cal(dela, since=str(d), until=str(far))["events"]}
    assert ids == {"y": "Переименовали", "z": "Новое", "far": "Далеко"}
    # Пустой календарь в окне — окно пустеет, дальнее остаётся.
    assert put(dela, [], a, b)["removed"] == 2
    assert [e["id"] for e in cal(dela, since=str(d), until=str(far))["events"]] == ["far"]


def test_each_sees_only_own(dela):
    d = today(dela)
    a, b = d, d + dt.timedelta(days=7)
    put(dela, [{"id": "s", "title": "Своё", "start": at(d, "10:00"), "end": at(d, "11:00")}], a, b)
    put(dela, [{"id": "n", "title": "Чужое", "start": at(d, "10:00"), "end": at(d, "11:00")}], a, b, user="natasha")
    assert [e["id"] for e in cal(dela)["events"]] == ["s"]
    assert [e["id"] for e in cal(dela, user="natasha")["events"]] == ["n"]
    assert cal(dela, user="marianna") == {"since": str(d - dt.timedelta(days=1)), "until": str(d + dt.timedelta(days=42)),
                                          "events": [], "sync": None}
    # Окно другого человека не трогает чужие события того же дня.
    put(dela, [], a, b, user="natasha")
    assert [e["id"] for e in cal(dela)["events"]] == ["s"]


@pytest.mark.parametrize("op, word", [
    ({"from": "вчера", "to": "2026-12-01", "events": []}, "from"),
    ({"from": "2026-12-01", "to": "2026-11-01", "events": []}, "раньше"),
    ({"from": "2026-01-01", "to": "2026-12-31", "events": []}, "больше"),
    ({"from": "2026-12-01", "to": "2026-12-31", "events": {}}, "список"),
    ({"from": "2026-12-01", "to": "2026-12-31", "events": [{"title": "без id"}]}, "id"),
    ({"from": "2026-12-01", "to": "2026-12-31", "events": [{"id": "a", "start": "2026-12-02T10:00:00"}]}, "пояса"),
    ({"from": "2026-12-01", "to": "2026-12-31",
      "events": [{"id": "a", "start": "2026-12-02T10:00:00+03:00", "end": "2026-12-02T09:00:00+03:00"}]}, "раньше начала"),
])
def test_validation_words(dela, op, word):
    r = store.apply_ops(dela, "sasha", [{"op": "cal.put", **op}], "app")["results"][0]
    assert not r["ok"] and word in r["error"], r


def test_view_window_and_feature(dela):
    assert "cal" in store.FEATURES
    with pytest.raises(store.OpError):
        cal(dela, since="2026-01-01", until="2026-12-31")
    with pytest.raises(store.OpError):
        cal(dela, since="2026-12-02", until="2026-12-01")


def test_contract_example(dela):
    assert CONTRACT["features"]["flag"] in store.FEATURES
    r = store.apply_ops(dela, "sasha", [copy.deepcopy(CONTRACT["op_put"]["example"])], "app")["results"][0]
    assert r["ok"] and set(r) >= set(CONTRACT["op_put"]["result"]) and r["put"] == 2, r
    v = cal(dela, since="2026-11-24", until="2026-11-30")
    got = {e["id"]: e for e in v["events"]}
    assert got["evt-call-1"]["start"] == "15:00" and got["evt-call-1"]["mins"] == 45
    assert got["evt-trip"]["last_day"] == "2026-11-29" and got["evt-trip"]["busy"] is False and got["evt-trip"]["location"] == "Казань"
    assert set(got["evt-call-1"]) == {"id", "title", "all_day", "day", "last_day", "start", "end", "end_day", "mins", "location", "link",
                                      "url", "task_id", "busy"}
