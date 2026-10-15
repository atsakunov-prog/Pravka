"""«Кто это» на двух привязках из журнала службы встреч 15.10.2026 18:36 (этап 6 аудита, п. 6) — на выдуманных именах:
репозиторий публичный.

1. Ложная. Спикер подписан «Имя (Компания Б)», а карточка с этим коротким именем — из компании А. Подсказка в скобках не
   совпала с компанией карточки, и who снимает с балла только 0,15: от 1,0 остаётся 0,85 — ровно порог SURE, второй
   кандидат далеко (0,35) — «уверенно». Так «кто это» обходил защиту службы встреч: голос со спором скобок служба
   отвергла, а «кто это» отдал ей ту же карточку.
2. Верная. Спикер подписан прежним именем с ослышкой в фамилии; это имя — другое имя карточки, а роль карточки называет
   компанию из скобок: 1,0 «другое имя», уверенно. Починка первой не должна её трогать.
3. Риск второй. Фамилия-ослышка лежит в карточке отдельным другим именем — по одной фамилии who уверенно ведёт к этой
   карточке и любого будущего носителя настоящей такой фамилии.

Без базы: who(rows=…) людей сам не читает (так же его зовёт разнос дайджеста по снимку). Ожидание «после починки» —
xfail(strict): на коде 5e0ca088 (живое 15.10) он падает, как и положено ложной привязке; починка who должна снять
пометку, иначе strict напомнит.
"""

from __future__ import annotations

import pytest

from pravka_dela import people as pp


def card(pid, name, short=None, aliases=(), org=None, role=None):
    return {"id": pid, "name": name, "short": short, "aliases": list(aliases), "phones": [], "emails": [],
            "telegram_id": None, "telegram_username": None, "org_id": f"o-{org}" if org else None, "role": role,
            "user_id": None, "merged_into": None, "org_name": org, "org_aliases": []}


ROWS = [
    # Карточка из сверки контактов: короткое имя «Ольга», имя с компанией в скобках, компания — «Альфа Банк».
    card("p-olga-a", "Ольга (Альфа Банк)", short="Ольга", org="Альфа Банк", role="банкир"),
    card("p-olga-f", "Ольга Фёдорова"),                                   # тёзка без компании — 0,35
    # Карточка с ослышкой фамилии в других именах и ролью «бывший партнёр …» без компании.
    card("p-vera", "Вера Соколова", aliases=["Вера Сокольникова", "Сокольникова", "Вера Соколова-Старая"],
         role="бывший партнёр Север Партнерс"),
    card("p-serg", "Сергей Тестов", org="ЗФ"),
]


def who(q):
    return pp.who(None, q=q, rows=ROWS)


def score(w, pid):
    return next((c["score"] for c in w["candidates"] if c["id"] == pid), None)


def test_p6_today_hint_of_other_company_is_still_sure():
    """Что есть на 5e0ca088: 1,0 − 0,15 = 0,85 = SURE — ложная привязка 18:36 воспроизводится."""
    w = who("Ольга (Бета)")
    assert w["best"]["id"] == "p-olga-a" and score(w, "p-olga-a") == 0.85 and w["sure"], w


@pytest.mark.xfail(strict=True, reason="п. 6: подсказка чужой компании не должна оставлять «уверенно» (проект "
                                       "stage6\\speakers.md §6, починка people.who)")
def test_p6_hint_of_other_company_is_a_question_not_a_binding():
    w = who("Ольга (Бета)")
    assert not w["sure"], w
    # Кандидатом остаётся — вопрос «Это Ольга (Альфа Банк)?» с кнопкой, а не тишина.
    assert w["best"]["id"] == "p-olga-a" and pp.ASK_MIN <= score(w, "p-olga-a") < pp.SURE, w


def test_p6_alias_with_role_hint_stays_sure():
    """Вторая привязка 18:36 — верная: другое имя и роль карточки называют компанию из скобок."""
    w = who("Вера Сокольникова (Север партнерс)")
    assert w["sure"] and w["best"]["id"] == "p-vera" and w["best"]["how"] == "другое имя", w
    assert score(w, "p-vera") == 1.0, w


def test_p6_kinship_hint_is_not_a_company():
    """«(отец)», «(терапевт)» — родство и роль, а не компания: спор с компанией карточки им не считается (так спикер
    «Сергей (отец)» не должен стать вопросом при починке первого случая)."""
    w = who("Сергей Тестов (отец)")
    assert w["sure"] and w["best"]["id"] == "p-serg", w


@pytest.mark.xfail(strict=True, reason="п. 6, риск: фамилия-ослышка отдельным другим именем уверенно ведёт к карточке "
                                       "(проект stage6\\speakers.md §6)")
def test_p6_surname_only_alias_is_not_sure():
    w = who("Сокольникова")
    assert w["best"]["id"] == "p-vera" and not w["sure"], w
