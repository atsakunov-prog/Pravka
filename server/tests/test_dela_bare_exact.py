"""Голое имя и точное имя (16.11.2026) — на выдуманных именах: репозиторий публичный.

«Кто это» с 16.11 не уверен, когда одно слово запроса — голое имя карточки («Глеб (Бета)» с коротким «Глеб») при живом
тёзке. Но точное имя шло мимо него: коннектор (mcp_tools.find_person) и автоматика (store._names поверх
crm.match_name) отдавали такую карточку как единственную своим именем. Теперь то же правило (people.bare_namesakes) и
там: коннектор переспрашивает с именами тёзок, автоматика оставляет дело без человека.
"""

from __future__ import annotations

from pravka_dela import mcp_tools, store
from test_dela_people import org, person
from test_dela_schema import as_, dela  # noqa: F401  (фикстура)


def _find(c, name, exact_only=False):
    try:
        return str(mcp_tools.find_person(c, name, exact_only=exact_only)["id"])
    except mcp_tools.NameError_ as e:
        return str(e)


def test_bare_first_name_is_not_an_exact_person(dela):
    beta = person(dela, "sasha", "Глеб (Бета)", short="Глеб", org_id=org(dela, "sasha", "Бета"))
    test = person(dela, "sasha", "Глеб Тестов")
    alone = person(dela, "sasha", "Тихон (Альфа)", short="Тихон")
    with as_(dela, "sasha") as c:
        # Коннектор: и поиск, и слияние переспрашивают — с обоими именами.
        for exact_only in (False, True):
            got = _find(c, "Глеб", exact_only)
            assert "не определился" in got and "Глеб (Бета)" in got and "Глеб Тестов" in got, got
        assert _find(c, "Глеб (Бета)") == beta and _find(c, "Глеб Тестов") == test
        assert _find(c, "Тихон") == alone      # тёзки нет — одно имя и есть он
        # Автоматика: дело из встречи с «Глебом» — без человека; полное имя и имя без тёзок — как раньше.
        assert "person_id" not in store._names(c, {"person_name": "Глеб"})
        assert str(store._names(c, {"person_name": "Глеб (Бета)"})["person_id"]) == beta
        assert str(store._names(c, {"person_name": "Тихон"})["person_id"]) == alone
        # «Кто это» — то же правило.
        w = store.view(dela, "sasha", "who", q="Глеб")
        assert not w["sure"] and str(w["best"]["id"]) == beta, w
