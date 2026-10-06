"""Контракт с телефоном (contract/dela.json и dela-crm.json) не врёт: операции и виды в нём есть
на сервере, а каждое поле примеров — в настоящих ответах (телефон строит по ним экраны и тесты)."""

from __future__ import annotations

import datetime as dt
import json
from pathlib import Path

from pravka_dela import ask, crm, stats, store  # noqa: F401  (crm и stats регистрируют свои виды в store.VIEWS)
from test_dela_schema import dela, project  # noqa: F401

ROOT = Path(__file__).resolve().parents[1] / "contract"
TWO = json.loads((ROOT / "dela-crm.json").read_text(encoding="utf-8"))


def test_contract_ops_and_views_exist():
    one = json.loads((ROOT / "dela.json").read_text(encoding="utf-8"))
    ops = set(one["ops_list"]) | set(TWO["ops_list_additions"]) | {o["op"] for o in TWO["ops_examples"]}
    assert ops <= set(store.HANDLERS), ops - set(store.HANDLERS)
    views = {v.split("?")[0] for v in one["views"]} | set(TWO["views_crm"])
    assert views <= set(store.VIEWS), views - set(store.VIEWS)
    # Источник дела — из тех, что примет база (у дела нет phone).
    for o in TWO["ops_examples"]:
        if o["op"] == "task.create":
            assert o["task"]["source"] in {"manual", "voice", "web", "bot", "telegram", "meeting", "mcp", "import"}


FREE = {"after", "before", "payload", "result"}  # свободные слепки (журнал, предложение) — состав зависит от данных


def fields(example, actual, path="ответ"):
    """Каждое поле примера есть в настоящем ответе. Список — ищем запись со всеми полями примера."""
    if isinstance(example, dict) and isinstance(actual, dict):
        missing = set(example) - set(actual) - {"_"}
        assert not missing, f"{path}: в ответе сервера нет {sorted(missing)}"
        for k, v in example.items():
            if k != "_" and k not in FREE and actual.get(k) is not None:
                fields(v, actual[k], f"{path}.{k}")
    elif isinstance(example, list) and example and isinstance(example[0], dict) and isinstance(actual, list) and actual:
        want = set(example[0]) - {"_"}
        hit = next((a for a in actual if isinstance(a, dict) and want <= set(a)), None)
        have = set().union(*(set(a) for a in actual if isinstance(a, dict)))
        assert hit is not None, f"{path}[]: ни в одной записи нет {sorted(want - have) or sorted(want)} разом"
        fields(example[0], hit, f"{path}[]")


def test_contract_examples_match_server(dela):
    """Те же виды на временной базе с клиентом, сделкой, оплатой, хронологией и днём рождения."""
    run = lambda *o: store.apply_ops(dela, "sasha", list(o), "web")["results"]  # noqa: E731
    p = project(dela, "sasha", "Бета Групп")
    today = dt.date.today()
    soon = today + dt.timedelta(days=5)
    person = run({"op": "person.create", "data": {"name": "Иван Петров", "short": "Иван", "cadence": "month", "hub": True,
                                                  "birth_day": soon.day, "birth_month": soon.month}})[0]["row"]
    deal = run({"op": "deal.create", "data": {"project_id": str(p), "name": "Бета: фонды", "stage": "active", "fee_kind": "fixed",
                                              "fee_kop": 50000000, "person_ids": [person["id"]]}})[0]["row"]
    run({"op": "deal.create", "data": {"project_id": str(p), "name": "Бета: модель", "stage": "lead", "fee_kop": 10000000}},
        {"op": "payment.create", "data": {"deal_id": deal["id"], "kind": "advance", "amount_kop": 25000000,
                                          "due_on": (today - dt.timedelta(days=20)).isoformat(),
                                          "invoiced_on": (today - dt.timedelta(days=25)).isoformat(),
                                          "paid_on": (today - dt.timedelta(days=15)).isoformat()}},
        {"op": "payment.create", "data": {"deal_id": deal["id"], "kind": "stage", "amount_kop": 15000000,
                                          "due_on": (today - dt.timedelta(days=3)).isoformat(),
                                          "invoiced_on": (today - dt.timedelta(days=10)).isoformat()}},
        {"op": "payment.create", "data": {"deal_id": deal["id"], "kind": "final", "amount_kop": 10000000,
                                          "due_on": (today + dt.timedelta(days=12)).isoformat()}},
        {"op": "payment.create", "data": {"deal_id": deal["id"], "kind": "extra", "amount_kop": 5000000,
                                          "due_on": (today + dt.timedelta(days=60)).isoformat()}},
        {"op": "interaction.add", "data": {"at": dt.datetime.now(dt.timezone.utc).isoformat(), "kind": "zoom",
                                           "summary": "Созвон: фонды готовы смотреть", "project_id": str(p), "deal_id": deal["id"],
                                           "person_ids": [person["id"]], "source": "manual"}},
        {"op": "task.create", "task": {"title": "Иван: прислать модель", "project_id": str(p), "deal_id": deal["id"],
                                       "ball": "waiting", "person_id": person["id"], "due_date": today.isoformat()}})
    params = {"client": {"project_id": str(p)}, "deal": {"deal_id": deal["id"]}, "dossier": {"person_id": person["id"]}}
    for name, ex in TWO["views_crm"].items():
        fields(ex["response"], store.view(dela, "sasha", name, **params.get(name, {})), f"вид {name}")

    # Правка словами, путь new — то же, что отдаёт разбор.
    def parse_fn(system, user_text):
        return {"tasks": [{"title": "Позвонить Ивану", "notes": "", "project": "", "person": "Иван", "ball": "mine", "due": "",
                           "estimate_min": 0, "money": "", "want": False, "labels": []}],
                "notes": [{"text": "Комитет пройден", "project": "Бета Групп", "person": ""}]}
    out = store.jsonable(ask.run(dela, "sasha", "заведи позвонить Ивану", {"title": "Утро", "task_ids": []}, "", None,
                                 ask_fn=lambda s, u: {"route": "new", "reply": "", "changes": [], "create": []}, parse_fn=parse_fn))
    example = {k: v for k, v in TWO["ask"]["done_new"].items() if k not in ("ok", "status", "_")}
    fields(example, out, "ask route=new")

    # Правка словами в «Новом»: решения по предложениям (decided) — те поля, что в примере.
    s = store.apply_ops(dela, "system", [{"op": "suggestion.create", "suggestion": {
        "for_user": "sasha", "kind": "create", "source": "meeting", "batch_ref": "meeting:5", "batch_title": "Бета, 05.10",
        "payload": {"title": "Иван: прислать модель", "person_name": "Иван", "ball": "waiting"}}}], "test")["results"][0]["suggestion"]
    decision = {"n": 1, "decision": "accept", "reason": "", "title": "Иван: прислать модель к пятнице", "notes_add": "", "project": "",
                "person": "", "due": "", "ball": "", "now": ""}
    out = store.jsonable(ask.run(dela, "sasha", "первое прими", {"title": "Новое", "task_ids": [], "suggestion_ids": [s["id"]]}, "", None,
                                 ask_fn=lambda s_, u: {"route": "edit", "reply": "Принял.", "changes": [], "create": [],
                                                       "suggestions": [decision]}))
    example = {k: v for k, v in TWO["ask"]["done_edit"].items() if k not in ("ok", "status", "_")}
    fields(example, out, "ask route=edit, «Новое»")
    assert TWO["ask"]["request"]["POST /api/ask"]["scope"]["suggestion_ids"]


def test_remind_contract_matches_server(dela):
    """Часть 3 (dela-remind.json): операции есть, флаг в синке, дело синка — со всеми полями примера."""
    three = json.loads((ROOT / "dela-remind.json").read_text(encoding="utf-8"))
    assert {o["op"] for o in three["ops_examples"]} <= set(store.HANDLERS)
    assert set(three["features"]["example"]) <= set(store.FEATURES)
    assert {"remind_at", "remind_place"} <= store.TASK_FIELDS and "reminded_at" not in store.TASK_FIELDS
    store.apply_ops(dela, "sasha", [{"op": "task.create", "task": {"title": "Позвонить Ивану", "due_date": "2026-10-06",
                                                                    "remind_at": "2026-10-06T11:00:00+03:00", "source": "voice"}}], "app")
    out = store.sync(dela, "sasha", 0)
    assert set(three["features"]["example"]) <= set(out["features"])
    fields(three["sync_task_example"], out["tasks"][0], "синк, дело")
