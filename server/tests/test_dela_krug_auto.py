"""Разборщик Круга: расписание слотов, пачка, уверенное — само (цель или направление), сомнение — в «Подскажи»,
память просмотренного, учёба на правках владельца."""

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


def test_tick_places_sure_under_goal_and_asks_unsure(kr):
    client = project(kr, "sasha", "Альфа")
    ok(kr, "sasha", {"op": "project.set", "id": str(client), "set": {"directions": ["Дело"]}})
    mk(kr, title="Подписать счёт", project_id=str(client))  # клиент висит на цели целиком — разборщику не нужен
    food = mk(kr, title="Еда", goal=True, directions=["Здоровье"])
    protein = mk(kr, title="Ежедневно набираю белок", goal=True, why_id=food["id"], rhythm="ежедневно")
    mk(kr, title="Ориентир", goal=True, directions=["Здоровье"], no_steps=True)  # под ним ничего — и в список не идёт
    magnesium = mk(kr, title="Купить протеин", directions=["Здоровье"])
    theatre = mk(kr, title="Театр")
    lamp = mk(kr, title="Поменять лампу")
    ok(kr, "sasha", {"op": "task.set", "id": mk(kr, title="Купить кроссовки")["id"], "set": {"directions": ["Быт"]}})
    cl, b = fake([{"num": magnesium["num"], "goal_num": protein["num"], "directions": ["health"], "sure": True, "question": ""},
                  {"num": theatre["num"], "goal_num": None, "directions": ["home"], "sure": False, "question": "Театр — с кем?"},
                  {"num": lamp["num"], "goal_num": None, "directions": ["home"], "sure": True, "question": ""}])
    now = at("2026-10-12T20:31")
    assert krug_auto.tick(kr, "key", None, now, cl) == {"sent": 4}  # протеин, театр, лампа, кроссовки
    prompt = b.sent[0][0]["params"]["messages"][0]["content"]
    assert "Купить протеин" in prompt and "Подписать счёт" not in prompt
    assert f"#{protein['num']} Здоровье › Еда › Ежедневно набираю белок (практика: ежедневно)" in prompt
    assert "Ориентир" not in prompt and "Еда (" not in prompt
    assert "«Купить кроссовки»: Комфортный быт" in prompt  # правка владельца — примером
    assert b.sent[0][0]["params"]["output_config"]["effort"] == krug_auto.EFFORT
    # Пачка ещё в работе — тик ничего не делает и новую не шлёт.
    assert krug_auto.tick(kr, "key", None, now, cl) is None and len(b.sent) == 1
    b.status = "ended"
    out = krug_auto.tick(kr, "key", None, now, cl)
    assert out["set"] == 2 and out["asked"] == 1
    got = {t["num"]: t for t in store.sync(kr, "sasha", 0)["tasks"]}
    # Цель есть — «Зачем»; своё направление, совпавшее с главным, снято (место — по дереву).
    assert got[magnesium["num"]]["why_id"] == protein["id"] and got[magnesium["num"]]["directions"] == []
    assert got[lamp["num"]]["directions"] == ["home"] and got[theatre["num"]]["directions"] == []
    ask = store.view(kr, "sasha", "new")["batches"][0]["items"]
    assert ask[0]["payload"]["ask"] == "Театр — с кем?" and ask[0]["source"] == "krug"
    # Тот же слот второй раз не шлёт; о «Театре» уже спросили, лампу и кроссовки уже смотрели — следующий слот пуст.
    assert krug_auto.tick(kr, "key", None, now, cl) is None
    assert krug_auto.tick(kr, "key", None, at("2026-10-12T21:00"), cl) is None and len(b.sent) == 1
    # Дело поменялось — его смотрят заново.
    ok(kr, "sasha", {"op": "task.set", "id": lamp["id"], "set": {"title": "Поменять лампу в коридоре"}})
    assert krug_auto.tick(kr, "key", None, at("2026-10-12T21:30"), cl) == {"sent": 1}


def test_nothing_to_do_costs_nothing(kr):
    cl, b = fake([])
    assert krug_auto.tick(kr, "key", None, at("2026-10-12T09:00"), cl) is None and b.sent == []
    # Ночью не шлёт, даже если есть что: последний слот — полночь, и он давно прошёл.
    mk(kr, title="Купить магний")
    assert krug_auto.tick(kr, "key", None, at("2026-10-13T03:00"), cl) is None and b.sent == []


def test_schema_enum_is_owner_keys(kr):
    s = krug_auto.schema(DIRS)
    item = s["properties"]["items"]["items"]["properties"]
    assert item["directions"]["items"]["enum"] == [d["key"] for d in DIRS] and "goal_num" in item
    assert "Здоровье (ключ health, зона «Я»)" in krug_auto.system_prompt(DIRS) and krug.DIRECTIONS_KEY == "krug.directions"
