"""Настройки телефона на сервере (14.10.2026, dela_0015): снимок от телефона, правка с компьютера, синк, спор и журнал.

Свои телефоны видит только их хозяин; ключи и токены не хранятся и не правятся; правка проверяется по схеме
телефона; ждущую правку телефон берёт синком и отвечает в следующем снимке; кто поменял позже — тот и прав."""

from __future__ import annotations

import copy
import json
import time
from pathlib import Path

import psycopg
import pytest

from pravka_dela import db as dela_db
from pravka_dela import phone, store
from test_dela_schema import dela  # noqa: F401  (фикстура)

CONTRACT = json.loads((Path(__file__).resolve().parents[1] / "contract" / "dela-phone.json").read_text(encoding="utf-8"))
SCHEMA = CONTRACT["schema"]["example"]
SNAP = CONTRACT["op_snapshot"]["example"]
DEV = SNAP["device"]


@pytest.fixture()
def ph(dela, conn):
    conn.execute("TRUNCATE crm.phone_edits, crm.phone_devices")
    return dela


def run(url, user, *ops):
    return store.apply_ops(url, user, list(ops), "app")["results"]


def snap(**kw) -> dict:
    """Снимок примера контракта со схемой; kw — поверх (values сливаются)."""
    s = copy.deepcopy(SNAP)
    s["schema"] = copy.deepcopy(SCHEMA)
    s.pop("schema_hash")
    vals = kw.pop("values", None)
    if vals:
        s["values"].update(vals)
    s.update(kw)
    return s


def edits(url, user="sasha", device=DEV):
    return store.view(url, user, "phone", device=device)["edits"]


def test_snapshot_and_view_only_own_phone(ph):
    r = run(ph, "sasha", snap())[0]
    assert r["ok"] and r["need_schema"] is False, r
    v = store.view(ph, "sasha", "phone")
    assert [d["device"] for d in v["devices"]] == [DEV]
    d = v["device"]
    assert d["name"] == "Pixel 10 Pro Fold" and d["values"]["z_gap_min"] == 45 and d["seen_at"]
    assert {g["key"] for g in d["schema"]["groups"]} == {"zasechka", "models", "links"}
    # Первый снимок — знакомство, а не «поменяли на телефоне»: журнал пуст.
    assert v["edits"] == [] and v["pending"] == []
    # Марианна чужой телефон не видит и не может прислать снимок под его именем.
    # Без поля now: два вызова на границе секунды давали разное время — тест падал через раз.
    hers = store.view(ph, "marianna", "phone")
    assert hers["now"] and {k: x for k, x in hers.items() if k != "now"} == {"devices": [], "device": None, "pending": [],
                                                                            "edits": []}
    assert store.view(ph, "marianna", "phone", device=DEV)["device"] is None
    bad = run(ph, "marianna", snap(values={"z_gap_min": 0}))[0]
    assert not bad["ok"] and "другого человека" in bad["error"]
    assert store.view(ph, "sasha", "phone")["device"]["values"]["z_gap_min"] == 45
    # И правку чужому телефону не поставишь.
    r = run(ph, "marianna", {"op": "phone.set", "device": DEV, "key": "z_gap_min", "value": 60})[0]
    assert not r["ok"] and "не твой" in r["error"]


def test_secrets_never_stored_nor_editable(ph):
    s = snap(values={"anthropic_api_key": "sk-ant-api03-SECRET", "todoist_token": "0123456789abcdef"})
    s["schema"]["groups"][2]["items"] += [
        {"key": "anthropic_api_key", "title": "Ключ", "type": "text", "edit": True},
        {"key": "todoist_token", "title": "Токен", "type": "text", "edit": True},
    ]
    assert run(ph, "sasha", s)[0]["ok"]
    d = store.view(ph, "sasha", "phone")["device"]
    assert "anthropic_api_key" not in d["values"] and "todoist_token" not in d["values"]
    items = phone.items_of(d["schema"])
    assert items["anthropic_api_key"]["edit"] is False and items["todoist_token"]["edit"] is False
    raw = json.dumps(store.view(ph, "sasha", "phone"), ensure_ascii=False, default=str)
    assert "sk-ant" not in raw and "0123456789abcdef" not in raw
    r = run(ph, "sasha", {"op": "phone.set", "device": DEV, "key": "anthropic_api_key", "value": "sk-ant-x"})[0]
    assert not r["ok"] and "не правится" in r["error"]
    # Показ «ключ есть» — можно: в имени нет слова key.
    assert d["values"]["link.anthropic"] == "ключ есть"
    for k in ("keep_on_fold", "link.anthropic", "auto.places", "z_gap_min"):
        assert not phone.is_secret(k), k
    for k in ("anthropic_api_key", "icu.key", "home.password", "dela_token", "archive.auth"):
        assert phone.is_secret(k), k


def test_set_checks_by_phone_schema(ph):
    run(ph, "sasha", snap())
    bad = run(ph, "sasha",
              {"op": "phone.set", "device": DEV, "key": "z_gap_min", "value": 200},
              {"op": "phone.set", "device": DEV, "key": "z_gap_min", "value": 50},
              {"op": "phone.set", "device": DEV, "key": "z_checkins", "value": "да"},
              {"op": "phone.set", "device": DEV, "key": "model.pravka", "value": "claude-haiku"},
              {"op": "phone.set", "device": DEV, "key": "auto.places", "value": "дом"},
              {"op": "phone.set", "device": DEV, "key": "nope", "value": 1})
    assert [r["ok"] for r in bad] == [False] * 6
    assert "от 0 до 120" in bad[0]["error"] and "шаг 15" in bad[1]["error"] and "да или нет" in bad[2]["error"]
    assert "варианта нет" in bad[3]["error"]
    assert "не правится" in bad[4]["error"] and "Wi-Fi" in bad[4]["error"]
    assert "нет настройки" in bad[5]["error"]
    assert store.view(ph, "sasha", "phone")["pending"] == []


def test_edit_waits_for_phone_in_sync_and_is_taken(ph):
    run(ph, "sasha", snap())
    r = run(ph, "sasha", {"op": "phone.set", "op_id": None, "device": DEV, "key": "z_gap_min", "value": 60})[0]
    assert r["ok"] and r["edit"]["status"] == "pending" and r["edit"]["was"] == 45 and r["edit"]["value"] == 60
    eid = r["edit"]["id"]
    # Синк телефона несёт ждущую правку; у Марианны — ничего.
    got = store.sync(ph, "sasha", 0)
    assert got["phone"]["edits"] == [{**got["phone"]["edits"][0], "id": eid, "device": DEV, "key": "z_gap_min", "value": 60}]
    assert "phone" not in store.sync(ph, "marianna", 0)
    assert "phone" in got["features"]
    # Телефон поставил и ответил в снимке — правка взята; «поменяли на телефоне» не пишется.
    out = run(ph, "sasha", snap(values={"z_gap_min": 60}, changed={"z_gap_min": int(time.time() * 1000)},
                                 acks=[{"id": eid, "ok": True}]))[0]
    assert out["acked"] == 1 and out["pending"] == 0
    log = edits(ph)
    assert len(log) == 1 and log[0]["status"] == "applied" and log[0]["taken_at"]
    assert "phone" not in store.sync(ph, "sasha", 0)
    # Повтор того же ответа (телефон не узнал, что дошло) ничего не ломает.
    assert run(ph, "sasha", snap(values={"z_gap_min": 60}, acks=[{"id": eid, "ok": True}]))[0]["acked"] == 0


def test_new_edit_replaces_waiting_and_same_value_cancels(ph):
    run(ph, "sasha", snap())
    a = run(ph, "sasha", {"op": "phone.set", "device": DEV, "key": "z_gap_min", "value": 60})[0]["edit"]
    b = run(ph, "sasha", {"op": "phone.set", "device": DEV, "key": "z_gap_min", "value": 90})[0]
    assert b["superseded"] == 1
    by = {e["id"]: e for e in edits(ph)}
    assert by[a["id"]]["status"] == "superseded" and by[b["edit"]["id"]]["status"] == "pending"
    # Вернул как на телефоне — ждать нечего: правки нет, ждущая снята.
    c = run(ph, "sasha", {"op": "phone.set", "device": DEV, "key": "z_gap_min", "value": 45})[0]
    assert c["same"] is True and c["edit"] is None and c["superseded"] == 1
    assert store.view(ph, "sasha", "phone")["pending"] == []


def test_change_on_phone_is_logged(ph):
    run(ph, "sasha", snap())
    at = 1791633600000
    run(ph, "sasha", snap(values={"z_checkins": False}, changed={"z_checkins": at}))
    log = edits(ph)
    assert len(log) == 1
    e = log[0]
    assert (e["source"], e["status"], e["key"], e["was"], e["value"], e["title"]) == \
        ("phone", "applied", "z_checkins", True, False, "Спрашивать «всё ещё …?»")
    # Тот же снимок ещё раз — не новая строка.
    run(ph, "sasha", snap(values={"z_checkins": False}, changed={"z_checkins": at}))
    assert len(edits(ph)) == 1


def test_later_change_on_phone_wins(ph, conn):
    run(ph, "sasha", snap())
    e = run(ph, "sasha", {"op": "phone.set", "device": DEV, "key": "z_gap_min", "value": 60})[0]["edit"]
    # Телефон поменял ту же настройку раньше правки (до того, как её сделали на компе) — правка ждёт дальше.
    early = int(time.time() * 1000) - 3_600_000
    run(ph, "sasha", snap(values={"z_gap_min": 30}, changed={"z_gap_min": early}))
    assert [p["id"] for p in store.view(ph, "sasha", "phone")["pending"]] == [e["id"]]
    # А поменял позже правки — побеждает телефон: спор с причиной, правка больше не ждёт.
    late = int(time.time() * 1000) + 2_000
    run(ph, "sasha", snap(values={"z_gap_min": 15}, changed={"z_gap_min": late}))
    got = {x["id"]: x for x in edits(ph)}[e["id"]]
    assert got["status"] == "conflict" and "поменяли позже" in got["note"] and "15 мин" in got["note"]
    assert store.view(ph, "sasha", "phone")["pending"] == []


def test_phone_refusal_and_cancel(ph):
    run(ph, "sasha", snap())
    a = run(ph, "sasha", {"op": "phone.set", "device": DEV, "key": "z_checkins", "value": False})[0]["edit"]
    b = run(ph, "sasha", {"op": "phone.set", "device": DEV, "key": "model.pravka", "value": "claude-opus-5-5"})[0]["edit"]
    run(ph, "sasha", snap(acks=[{"id": a["id"], "ok": False, "note": "служба не ответила"},
                                {"id": b["id"], "ok": False, "conflict": True, "note": "на телефоне поменяли позже"}]))
    by = {x["id"]: x for x in edits(ph)}
    assert (by[a["id"]]["status"], by[a["id"]]["note"]) == ("rejected", "служба не ответила")
    assert by[b["id"]]["status"] == "conflict"
    c = run(ph, "sasha", {"op": "phone.set", "device": DEV, "key": "effort.pravka", "value": "low"})[0]["edit"]
    assert run(ph, "sasha", {"op": "phone.cancel", "id": c["id"]})[0]["edit"]["status"] == "cancelled"
    again = run(ph, "sasha", {"op": "phone.cancel", "id": c["id"]})[0]
    assert not again["ok"] and "уже нет" in again["error"]


def test_new_build_without_schema_asks_for_it(ph):
    run(ph, "sasha", {**snap(), "schema_hash": "v1"})
    s = snap(values={"z_gap_min": 15})
    s.pop("schema")
    s["schema_hash"] = "v2"
    r = run(ph, "sasha", s)[0]
    assert r["need_schema"] is True
    # Значения до новой схемы не трогаем: могли поменяться и ключи.
    assert store.view(ph, "sasha", "phone")["device"]["values"]["z_gap_min"] == 45
    s2 = snap(values={"z_gap_min": 15})
    s2["schema_hash"] = "v2"
    assert run(ph, "sasha", s2)[0]["need_schema"] is False
    assert store.view(ph, "sasha", "phone")["device"]["values"]["z_gap_min"] == 15


def test_removed_setting_rejects_waiting_edit(ph):
    run(ph, "sasha", snap())
    e = run(ph, "sasha", {"op": "phone.set", "device": DEV, "key": "z_checkins", "value": False})[0]["edit"]
    s = snap()
    s["schema"]["groups"][0]["items"] = [i for i in s["schema"]["groups"][0]["items"] if i["key"] != "z_checkins"]
    run(ph, "sasha", s)
    got = {x["id"]: x for x in edits(ph)}[e["id"]]
    assert got["status"] == "rejected" and "больше нет" in got["note"]


def test_bad_schema_is_refused_whole(ph):
    s = snap()
    s["schema"]["groups"][0]["items"].append({"key": "Плохой ключ", "type": "bool"})
    r = run(ph, "sasha", s)[0]
    assert not r["ok"] and "латиница" in r["error"]
    s = snap()
    s["schema"]["groups"][1]["items"][0]["options"] = []
    assert "нет вариантов" in run(ph, "sasha", s)[0]["error"]
    assert store.view(ph, "sasha", "phone")["devices"] == []


def test_hide_and_come_back(ph):
    run(ph, "sasha", snap())
    run(ph, "sasha", snap(device="sasha-old1"))
    assert len(store.view(ph, "sasha", "phone")["devices"]) == 2
    assert run(ph, "sasha", {"op": "phone.hide", "device": "sasha-old1"})[0]["ok"]
    assert [d["device"] for d in store.view(ph, "sasha", "phone")["devices"]] == [DEV]
    run(ph, "sasha", snap(device="sasha-old1"))
    assert len(store.view(ph, "sasha", "phone")["devices"]) == 2


def test_base_keeps_edit_on_own_phone(ph):
    """Правило базы на любой клиент: правка ложится только на свой телефон (код и так проверяет)."""
    run(ph, "sasha", snap())
    with pytest.raises(psycopg.errors.RaiseException):
        with dela_db.session(ph, "system", "test") as c:
            c.execute("INSERT INTO crm.phone_edits (device, user_id, key, source, value) VALUES (%s, 'marianna', 'z_gap_min', 'web', '60')",
                      (DEV,))
    # Журнал общего вида (crm.history) снимками не засоряется.
    with dela_db.session(ph, "system", "test") as c:
        assert c.execute("SELECT count(*) AS n FROM crm.history WHERE entity LIKE 'crm.phone%'").fetchone()["n"] == 0


def test_contract_examples(ph):
    assert CONTRACT["features"]["flag"] in store.FEATURES
    for name in ("op_snapshot", "op_set", "op_cancel", "op_hide"):
        assert CONTRACT[name]["op"] in store.HANDLERS
    assert "phone" in store.VIEWS
    run(ph, "sasha", snap())
    r = run(ph, "sasha", CONTRACT["op_set"]["example"])[0]
    assert set(CONTRACT["op_set"]["result"]) - {"op_id"} <= set(r)
    assert set(CONTRACT["op_set"]["result"]["edit"]) <= set(r["edit"])
    sync = store.sync(ph, "sasha", 0)
    assert set(CONTRACT["sync"]["example"]["phone"]["edits"][0]) <= set(sync["phone"]["edits"][0])
    v = store.view(ph, "sasha", "phone")
    ex = CONTRACT["view"]["example"]
    assert set(ex) <= set(v)
    assert set(ex["devices"][0]) <= set(v["devices"][0])
    assert set(ex["device"]) <= set(v["device"])
    assert set(ex["edits"][0]) <= set(v["edits"][0])
    snap_res = run(ph, "sasha", snap())[0]
    assert set(CONTRACT["op_snapshot"]["result"]) - {"op_id"} <= set(snap_res)
