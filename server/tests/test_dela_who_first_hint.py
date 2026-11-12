"""«Кто это» для подписи «Имя (Компания)», когда имени нет в справочнике уменьшительных (12.11.2026) — на выдуманных
именах: репозиторий публичный.

Случай из встреч. Спикер подписан одним полным именем с компанией в скобках. Карточка нужного человека — «Имя Фамилия»,
компания латиницей, у компании другое имя кириллицей, у карточки другое имя ровно этой подписью (так его назвал
владелец, досье дописало). Рядом — тёзка из другой компании с коротким именем «Имя». До починки оба получали 0,8:
- одно имя с подтверждённой компанией должно было подниматься до 0,9, но порог проверял is_first, а полных имён вроде
  «Глеб», «Кирилл», «Марина» в справочнике уменьшительных нет (они в MORE_FIRST, их видит только looks_first);
- даже 0,9 против 0,8 тёзки — меньше GAP, «не уверенно»;
- точная подпись со скобкой в других именах карточки ничего не давала: сверялось только имя без скобки.

Без базы: who(rows=…) людей сам не читает (как в test_dela_people_p6.py).
"""

from __future__ import annotations

from pravka_dela import people as pp


def card(pid, name, short=None, aliases=(), org=None, org_aliases=(), role=None):
    return {"id": pid, "name": name, "short": short, "aliases": list(aliases), "phones": [], "emails": [],
            "telegram_id": None, "telegram_username": None, "org_id": f"o-{org}" if org else None, "role": role,
            "user_id": None, "merged_into": None, "org_name": org, "org_aliases": list(org_aliases)}


GLEB = card("p-gleb", "Глеб Тестов", aliases=["Глеб (Кофикс)"], org="Kofix", org_aliases=["Кофикс"])
GLEB_BARE = card("p-gleb", "Глеб Тестов", org="Kofix", org_aliases=["Кофикс"])     # без другого имени-подписи
NAMESAKE = card("p-gleb-b", "Глеб (Бета Ферма)", short="Глеб", org="Бета Ферма")  # тёзка из другой компании
KIR = card("p-kir", "Кирилл Примеров", org="Север Капитал")
KIR_I = card("p-kir-i", "Кирилл Иванов")


def who(q, rows):
    return pp.who(None, q=q, rows=rows)


def score(w, pid):
    return next((c["score"] for c in w["candidates"] if c["id"] == pid), None)


def test_label_with_company_is_the_alias_and_sure():
    """Случай встречи: подпись — другое имя карточки целиком, тёзка из другой компании рядом. Кириллица, латиница
    компании (подпись Zoom) и слитная скобка — одна подпись."""
    for q in ("Глеб (Кофикс)", "Глеб (Kofix)", "Глеб(Кофикс)", "глеб  (кофикс)"):
        w = who(q, [GLEB, NAMESAKE])
        assert w["sure"] and w["best"]["id"] == "p-gleb", (q, w)
        assert score(w, "p-gleb") == 1.0 and w["best"]["how"] == "другое имя", (q, w)
        assert score(w, "p-gleb-b") == pp.HINT_CAP, (q, w)   # тёзка — кандидат, как и был


def test_full_first_name_with_company_reaches_first_only_floor():
    """Без подписи в других именах: одно полное имя (не из уменьшительных) с подтверждённой компанией — 0,9, как
    «Дмитрий (Ромашка)»; один кандидат — уверенно."""
    for q in ("Глеб (Кофикс)", "Глеб (Kofix)"):
        w = who(q, [GLEB_BARE])
        assert w["sure"] and w["best"]["id"] == "p-gleb" and score(w, "p-gleb") == 0.9, (q, w)
    w = who("Кирилл (Север)", [KIR, KIR_I])
    assert w["sure"] and w["best"]["id"] == "p-kir" and score(w, "p-kir") == 0.9, w


def test_namesake_from_other_company_does_not_block():
    """Тёзка рядом (0,8 — спорит со скобкой) не соперник тому, чью компанию скобка подтвердила (0,9)."""
    w = who("Глеб (Кофикс)", [GLEB_BARE, NAMESAKE])
    assert w["sure"] and w["best"]["id"] == "p-gleb", w
    assert score(w, "p-gleb") == 0.9 and score(w, "p-gleb-b") == pp.HINT_CAP, w


def test_still_not_sure_where_it_should_not_be():
    # Компания в скобках ничья — тёзка остаётся вопросом (п. 6 этапа 6).
    w = who("Глеб (Гамма)", [GLEB_BARE, NAMESAKE])
    assert not w["sure"] and score(w, "p-gleb-b") == pp.HINT_CAP, w
    # Двое из той же компании — оба подтверждены, уникальности нет.
    two = [GLEB_BARE, card("p-gleb-2", "Глеб Второв", org="Kofix", org_aliases=["Кофикс"])]
    w = who("Глеб (Кофикс)", two)
    assert not w["sure"] and score(w, "p-gleb") == score(w, "p-gleb-2") == 0.9, w
    # Подпись совпала с другим именем, а компания карточки уже другая — кандидат, не «уверенно» (служба встреч всё равно
    # не привязала бы: у неё своя проверка скобок).
    moved = card("p-gleb", "Глеб Тестов", aliases=["Глеб (Кофикс)"], org="Бета Ферма")
    w = who("Глеб (Кофикс)", [moved])
    assert not w["sure"] and score(w, "p-gleb") == pp.HINT_CAP, w
    # Одно имя без компании — как раньше: не по нему узнавать.
    assert not who("Глеб", [GLEB_BARE, card("p-gleb-3", "Глеб Третьяков")])["sure"]


def test_full_first_names_still_part_from_surnames():
    """MORE_FIRST в FIRST_NAMES не попал: «Кирилл Петров» против «Кирилл Иванов» — однофамилец с тем же именем, 0,5
    (проверка 15.10), а не совпадение."""
    w = who("Кирилл Петров", [KIR_I])
    assert score(w, "p-kir-i") == 0.5 and not w["sure"], w
    assert "кирилл" not in pp.FIRST_NAMES and pp.looks_first("Кирилл")
