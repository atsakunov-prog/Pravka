"""Один матчер у сервера и телефона (аудит 15.10.2026, №67): образец вида context_index — contract/dela-context-sample.json.

Телефон (core/ContextIndex.kt) повторяет focus.forms/focus.found буква в букву, таблицы берёт из ответа. Образец — ответ
вида на выдуманных людях и проектах и что матчер сервера находит в тексте; телефон читает тот же файл (ContextIndexTest)
и обязан найти то же. Этот тест держит образец правдой сервера: поменял правило, ключи или таблицы people.py —
образец устарел, тест скажет; перегенерировать — DELA_SAMPLE_WRITE=1 и этот тест. Базы не нужно.
"""

from __future__ import annotations

import json
import os
from pathlib import Path

from pravka_dela import focus, people

FILE = Path(__file__).resolve().parents[1] / "contract" / "dela-context-sample.json"

# Выдуманные люди и проекты: имя, падежи, уменьшительное, латиница, короткое имя с «ь».
PEOPLE = [
    ("00000000-0000-4000-8000-000000000001", ["Евгений Соколов"]),
    ("00000000-0000-4000-8000-000000000002", ["Мария Тестова"]),
    ("00000000-0000-4000-8000-000000000003", ["Пётр Новиков"]),
    ("00000000-0000-4000-8000-000000000004", ["Миша Иванов"]),
    ("00000000-0000-4000-8000-000000000005", ["Иван Тестов", "Ваня"]),
    ("00000000-0000-4000-8000-000000000006", ["Дмитрий Ландышев"]),
    ("00000000-0000-4000-8000-000000000007", ["Илья"]),
]
PROJECTS = [
    ("00000000-0000-4000-8000-0000000000a1", ["ООО Ромашка"]),
    ("00000000-0000-4000-8000-0000000000a2", ["Бета Групп", "Бета"]),
]
TEXTS = [
    "встреча с Соколовым", "Евгением", "передать Жене", "Женя сказал", "Марии", "позвать Марину", "Новик пришёл",
    "с Мишей", "Мише", "Ваня обещал", "письмо от Dmitry Landyshev", "у Ильи", "у Ромашки", "Ромашке", "ООО",
    "Бете отправить", "спросить у Ивана Тестова и Марии про Ромашку",
]


def sample() -> dict:
    pk = {pid: focus.keys_of(names) for pid, names in PEOPLE}
    rk = {pid: focus.keys_of(names, generic=True, min_len=focus.PROJECT_MIN) for pid, names in PROJECTS}
    return {
        "_": ("Образец вида context_index (аудит №67): ключи построил focus.py сервера на выдуманных людях и проектах, "
              "cases — что матчер сервера находит в тексте. Сервер держит файл тестом test_dela_context_sample.py, телефон — "
              "ContextIndexTest (тот же ответ — то же найдено). Поменял правило или таблицы people.py — перегенерируй: "
              "DELA_SAMPLE_WRITE=1 pytest tests/test_dela_context_sample.py."),
        "names": {pid: names for pid, names in PEOPLE + PROJECTS},
        "view": {
            "ok": True, "rule": focus.RULE, "days": focus.DAYS,
            "related": [PEOPLE[0][0], PEOPLE[4][0]],
            "people": pk, "projects": rk,
            "endings": list(people.ENDINGS), "letters": "".join(sorted(focus.LETTERS)),
            "translit": [list(x) for x in people.TRANSLIT], "translit_y_end": True,
        },
        "cases": [{"text": t, "people": sorted(focus.found(t, pk)), "projects": sorted(focus.found(t, rk))} for t in TEXTS],
    }


def test_sample_is_the_server_rule():
    want = sample()
    if os.environ.get("DELA_SAMPLE_WRITE") == "1":
        FILE.write_text(json.dumps(want, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    have = json.loads(FILE.read_text(encoding="utf-8"))
    assert have == want, "образец устарел: DELA_SAMPLE_WRITE=1 и этот тест — и прогнать ContextIndexTest телефона"


def test_sample_covers_the_contract_cases():
    """Случаи контракта (dela-context.json, cases) — в образце с тем же ответом: «Жене», «Соколовым» — да, «Марину» —
    не «Мария», «Новик» — не «Новиков», «у Ромашки» — да, «ООО» — нет, «у Ильи» — известная дыра."""
    by = {c["text"]: c for c in sample()["cases"]}
    sok, mar, mish = PEOPLE[0][0], PEOPLE[1][0], PEOPLE[3][0]
    for t in ("встреча с Соколовым", "Евгением", "передать Жене", "Женя сказал"):
        assert by[t]["people"] == [sok], t
    assert by["Марии"]["people"] == [mar] and by["позвать Марину"]["people"] == []
    assert by["Новик пришёл"]["people"] == [] and by["с Мишей"]["people"] == [mish] and by["Мише"]["people"] == [mish]
    assert by["у Ромашки"]["projects"] == [PROJECTS[0][0]] and by["ООО"]["projects"] == []
    assert by["у Ильи"]["people"] == []
    assert by["письмо от Dmitry Landyshev"]["people"] == [PEOPLE[5][0]]
