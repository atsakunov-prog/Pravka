"""Контракт с телефоном (contract/dela.json и dela-crm.json) не врёт: операции и виды в нём есть на сервере."""

from __future__ import annotations

import json
from pathlib import Path

from pravka_dela import crm, store  # noqa: F401  (crm регистрирует свои виды в store.VIEWS)

ROOT = Path(__file__).resolve().parents[1] / "contract"


def test_contract_ops_and_views_exist():
    one = json.loads((ROOT / "dela.json").read_text(encoding="utf-8"))
    two = json.loads((ROOT / "dela-crm.json").read_text(encoding="utf-8"))
    ops = set(one["ops_list"]) | set(two["ops_list_additions"]) | {o["op"] for o in two["ops_examples"]}
    assert ops <= set(store.HANDLERS), ops - set(store.HANDLERS)
    views = {v.split("?")[0] for v in one["views"]} | set(two["views_crm"])
    assert views <= set(store.VIEWS), views - set(store.VIEWS)
    # Источник дела — из тех, что примет база (у дела нет phone).
    for o in two["ops_examples"]:
        if o["op"] == "task.create":
            assert o["task"]["source"] in {"manual", "voice", "web", "bot", "telegram", "meeting", "mcp", "import"}
