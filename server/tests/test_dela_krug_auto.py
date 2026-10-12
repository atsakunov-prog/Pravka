"""Разборщик Круга: расписание слотов, пачка, уверенное — само, сомнение — в «Подскажи», учёба на правках."""

from __future__ import annotations

import datetime as dt
import json
from types import SimpleNamespace as NS
from zoneinfo import ZoneInfo

import pytest

from pravka_dela import krug, krug_auto, store
from test_dela_krug import DIRS, mk, ok  # noqa: F401
from test_dela_krug import kr  # noqa: F401  (фикстура)
from test_dela_schema import dela, project  # noqa: F401

MSK = ZoneInfo("Europe/Moscow")


def at(s: str) -> dt.datetime:
    return dt.datetime.fromisoformat(s).replace(tzinfo=MSK)


def test_slots_follow_owner_words():
    mon, sat = "2026-10-12", "2026-10-17"
    assert krug_auto.is_slot(at(f"{mon}T08:00")) and krug_auto.is_slot(at(f"{mon}T08:30"))
    assert krug_auto.is_slot(at(f"{mon}T23:30")) and krug_auto.is_slot(at(f"{mon}T00:00"))
    assert not krug_auto.is_slot(at(f"{mon}T07:30")) and not krug_auto.is_slot(at(f"{mon}T00:30"))
    assert not krug_auto.is_slot(at(f"{mon}T03:00")) and not krug_auto.is_slot(at(f"{mon}T08:15"))
    assert krug_auto.is_slot(at(f"{sat}T14:00")) and not krug_auto.is_slot(at(f"{sat}T14:30"))
    # Ночью последний слот — полночь; утром первый — восемь.
    assert krug_auto.last_slot(at(f"{mon}T05:10")) == at(f"{mon}T00:00")
    assert krug_auto.last_slot(at(f"{mon}T08:44")) == at(f"{mon}T08:30")
    assert krug_auto.last_slot(at(f"{sat}T14:44")) == at(f"{sat}T14:00")


class FakeBatches:
    def __init__(self, answer):
        self.answer, self.sent, self.status = answer, [], "in_progress"

    def create(self, requests):
        self.sent.append(requests)
        return NS(id="msgbatch_1")

    def retrieve(self, bid):
        return NS(processing_status=self.status)

    def results(self, bid):
        msg = NS(stop_reason="end_turn", model="claude-opus-5-5",
                 content=[NS(type="text", text=json.dumps({"items": self.answer}))],
                 usage=NS(input_tokens=6000, output_tokens=800, cache_creation_input_tokens=0, cache_read_input_tokens=0))
        return iter([NS(custom_id="krug", result=NS(type="succeeded", message=msg))])


def fake(answer):
    b = FakeBatches(answer)
    return NS(messages=NS(batches=b)), b


def test_tick_sends_batch_then_sets_sure_and_asks_unsure(kr):
    p = project(kr, "sasha", "Альфа")
    ok(kr, "sasha", {"op": "project.set", "id": str(p), "set": {"directions": ["Дело"]}})
    mk(kr, title="Подписать счёт", project_id=str(p))  # наследует Дело — разборщику не нужен
    magnesium = mk(kr, title="Купить магний")
    theatre = mk(kr, title="Театр")
    ok(kr, "sasha", {"op": "task.set", "id": mk(kr, title="Купить кроссовки")["id"], "set": {"directions": ["Быт"]}})
    cl, b = fake([{"num": magnesium["num"], "directions": ["health"], "sure": True, "why_num": None, "question": ""},
                  {"num": theatre["num"], "directions": ["home"], "sure": False, "why_num": None, "question": "Театр — с кем?"}])
    now = at("2026-10-12T20:31")
    assert krug_auto.tick(kr, "key", None, now, cl) == {"sent": 2}
    prompt = b.sent[0][0]["params"]["messages"][0]["content"]
    assert "Купить магний" in prompt and "Подписать счёт" not in prompt
    assert "«Купить кроссовки»: Быт" in prompt  # правка владельца — примером
    assert b.sent[0][0]["params"]["output_config"]["effort"] == krug_auto.EFFORT
    # Пачка ещё в работе — тик ничего не делает и новую не шлёт.
    assert krug_auto.tick(kr, "key", None, now, cl) is None and len(b.sent) == 1
    b.status = "ended"
    out = krug_auto.tick(kr, "key", None, now, cl)
    assert out["set"] == 1 and out["asked"] == 1
    got = {t["num"]: t for t in store.sync(kr, "sasha", 0)["tasks"]}
    assert got[magnesium["num"]]["directions"] == ["health"] and got[theatre["num"]]["directions"] == []
    ask = [s for s in store.view(kr, "sasha", "new")["batches"][0]["items"]]
    assert ask[0]["payload"]["ask"] == "Театр — с кем?" and ask[0]["source"] == "krug"
    # Тот же слот второй раз не шлёт; о «Театре» уже спросили — следующий слот его не берёт.
    assert krug_auto.tick(kr, "key", None, now, cl) is None
    assert krug_auto.tick(kr, "key", None, at("2026-10-12T21:00"), cl) is None and len(b.sent) == 1


def test_nothing_to_do_costs_nothing(kr):
    cl, b = fake([])
    assert krug_auto.tick(kr, "key", None, at("2026-10-12T09:00"), cl) is None and b.sent == []
    # Ночью не шлёт, даже если есть что: последний слот — полночь, и он давно прошёл.
    mk(kr, title="Купить магний")
    assert krug_auto.tick(kr, "key", None, at("2026-10-13T03:00"), cl) is None and b.sent == []


def test_schema_enum_is_owner_keys(kr):
    s = krug_auto.schema(DIRS)
    assert s["properties"]["items"]["items"]["properties"]["directions"]["items"]["enum"] == [d["key"] for d in DIRS]
    assert "Здоровье" in krug_auto.system_prompt(DIRS) and krug.DIRECTIONS_KEY == "krug.directions"
