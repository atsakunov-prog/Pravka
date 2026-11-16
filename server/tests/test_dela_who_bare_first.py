"""«Кто это» для голого имени (16.11.2026) — на выдуманных именах: репозиторий публичный.

У карточки «Имя (Компания)» короткое имя — само «Имя» (так их заводят «Люди сами», досье и сверка контактов), у карточки
из одного имени — её имя. Запрос «Имя» совпадал с ней точно (1,0), тёзки с фамилией — 0,35–0,6, и разрыв давал
«уверенно»: голый спикер встречи или подпись Zoom уходили к одной случайной карточке при десятке тёзок (на живых данных —
около двадцати имён). Теперь такое совпадение при живом тёзке — кандидат, не привязка. Прозвище, учётка Дел и компания в
скобках — уверенно, как раньше.

Без базы: who(rows=…) людей сам не читает (как в test_dela_people_p6.py).
"""

from __future__ import annotations

from pravka_dela import people as pp


def card(pid, name, short=None, aliases=(), org=None, user_id=None):
    return {"id": pid, "name": name, "short": short, "aliases": list(aliases), "phones": [], "emails": [],
            "telegram_id": None, "telegram_username": None, "org_id": f"o-{org}" if org else None, "role": None,
            "user_id": user_id, "merged_into": None, "org_name": org, "org_aliases": []}


GLEB_B = card("p-gleb-b", "Глеб (Бета)", short="Глеб", org="Бета")
GLEB_T = card("p-gleb-t", "Глеб Тестов", short="Глеб Тестов", org="Ромашка")


def who(q, rows):
    return pp.who(None, q=q, rows=rows)


def test_bare_first_name_with_namesake_is_a_candidate_not_a_binding():
    for q in ("Глеб", "Глеба", "Gleb"):
        w = who(q, [GLEB_B, GLEB_T])
        assert not w["sure"], (q, w)
        # Кандидатом остаётся — первым, для вопроса «Это Глеб (Бета)?».
        assert w["best"]["id"] == "p-gleb-b" and w["best"]["score"] >= pp.SURE, (q, w)
    # Карточка из одного имени — так же.
    w = who("Кирилл", [card("p-kir", "Кирилл"), card("p-kir-i", "Кирилл Иванов")])
    assert not w["sure"] and w["best"]["id"] == "p-kir", w
    # Короткое имя — первое слово её полного имени (досталось от прежней подписи) — тоже не прозвище.
    w = who("Глеб", [card("p-gleb-s", "Глеб Старов (Альфа)", short="Глеб"), GLEB_T])
    assert not w["sure"], w


def test_bare_first_name_stays_sure_where_it_should():
    # Тёзки нет — одно имя и есть этот человек.
    assert who("Глеб", [GLEB_B, card("p-ivan", "Иван Тестов")])["sure"]
    # Тёзка другого рода — не тёзка.
    w = who("Валерий", [card("p-val", "Валерий"), card("p-vala", "Валерия Тестова")])
    assert w["sure"] and w["best"]["id"] == "p-val", w
    # Прозвище записано именно ей.
    w = who("Ваня", [card("p-vanya", "Иван Тестов", short="Ваня"), card("p-ivan2", "Иван Примеров")])
    assert w["sure"] and w["best"]["id"] == "p-vanya", w
    # Учётка Дел — команда и семья: «Ольга» — это она.
    w = who("Ольга", [card("p-ol", "Ольга Тестова (ЗФ)", short="Ольга", org="ЗФ", user_id="u-ol"),
                      card("p-ol2", "Ольга Примерова")])
    assert w["sure"] and w["best"]["id"] == "p-ol", w
    # Компания в скобках подтвердила — уверенно, тёзки не мешают.
    w = who("Глеб (Бета)", [GLEB_B, GLEB_T])
    assert w["sure"] and w["best"]["id"] == "p-gleb-b", w
    # Полное имя — не голое.
    w = who("Глеб Тестов", [GLEB_B, GLEB_T])
    assert w["sure"] and w["best"]["id"] == "p-gleb-t", w
