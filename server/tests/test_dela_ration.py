"""Рацион в Своде (food.ration, 13.10.2026): смотреть и править из коннектора claude.ai.

Случаи — слова владельца: «убери из каши творог», «индейка с кускусом», «добавь рацион в коннектор
и чтобы его можно было редактировать». Чистые правки — без базы; запись — на настоящем PostgreSQL.
"""

from __future__ import annotations

import copy

import pytest

from pravka_dela import mcp_tools, ration, store
from test_dela_schema import dela  # noqa: F401  (фикстура)


def book() -> dict:
    return copy.deepcopy(ration.factory())


def by_name(b: dict, name: str) -> dict:
    return next(x for x in b["products"] + b["dishes"] if x["name"] == name)


# ── Без базы ────────────────────────────────────────────────────────────


def test_factory_is_the_phone_seed_and_whole():
    b = book()
    assert b["version"] == ration.VERSION
    assert ration.check(b) == []
    kasha = by_name(b, "Моя каша")
    # Мягкий творог 1.5% из каши убран — владелец: «ты прав, 1.5%».
    assert "tvorog-soft" not in [i["product"] for i in kasha["items"]]
    assert ration.dish_kcal(b, kasha) == 905


def test_find_by_words_of_the_owner():
    b = book()
    assert ration.find(b["products"], "индейка сладкий соус чили", "продукт")["id"] == "turkey-chili"
    assert ration.find(b["dishes"], "каша моя", "блюдо")["id"] == "kasha"
    assert ration.find(b["products"], "Индейка в сладком соусе чили ВкусВилл", "продукт")["id"] == "turkey-chili"
    assert ration.find(b["products"], "кускус", "продукт")["id"] == "kuskus"
    assert ration.find(b["products"], "ёжевика", "продукт") is None
    # «Творог» — точное слово владельца для 5%, а не «какой из двух».
    assert ration.find(b["products"], "творог", "продукт")["id"] == "tvorog5"
    with pytest.raises(ration.RationError, match="Назови точнее"):
        ration.find(b["products"], "тво", "продукт")


def test_new_product_needs_numbers_and_warns_on_mismatch():
    b = book()
    with pytest.raises(ration.RationError, match="kcal100"):
        ration.put_product(b, "Скумбрия", grams=150)
    msg = ration.put_product(b, "Йогурт греческий 2%", grams=150, kcal100=66, protein100=8, fat100=2,
                             carbs100=4, add_aliases=["греческий йогурт"], micro={"ca": 100})
    assert msg.startswith("Завёл продукт: Йогурт греческий 2% — 66 ккал")
    p = by_name(b, "Йогурт греческий 2%")
    assert p["id"] == "yogurt-grecheskiy-2" and p["aliases"] == ["греческий йогурт"] and p["micro"] == {"ca": 100.0}
    # Калории не сходятся с макросами — пишет, но говорит.
    assert "сверь с этикеткой" in ration.put_product(b, "Йогурт греческий 2%", kcal100=200)
    with pytest.raises(ration.RationError, match="не знаю"):
        ration.put_product(b, "Йогурт греческий 2%", micro={"vitamin_d": 1})
    with pytest.raises(ration.RationError, match="больше 100 г"):
        ration.put_product(b, "Йогурт греческий 2%", protein100=60, carbs100=60)


def test_label_numbers_and_aliases_for_turkey():
    b = book()
    msg = ration.put_product(b, "индейка", kcal100=140, protein100=18, fat100=4, carbs100=8, sure="точно",
                             add_aliases=["индейку"], note="")
    assert "140 ккал" in msg
    t = by_name(b, "Индейка в сладком соусе чили ВкусВилл")
    assert "sure" not in t and "note" not in t and t["aliases"][-1] == "индейку"
    assert ration.dish_kcal(b, by_name(b, "Индейка с кускусом")) == 350 + 224


def test_dish_drop_add_items_and_kind():
    b = book()
    msg = ration.put_dish(b, "моя каша", drop=["Клубника"], add=[{"product": "Банан"}, {"product": "геркулес", "grams": 80}])
    kasha = by_name(b, "Моя каша")
    assert [(i["product"], i["grams"]) for i in kasha["items"]] == [
        ("gerkules", 80), ("golubika", 250), ("protein", 66), ("tvorog5", 200), ("kreatin", 0), ("banan", 120)]
    assert msg.startswith("Поправил блюдо: Моя каша")
    with pytest.raises(ration.RationError, match="нет «Клубника»"):
        ration.put_dish(b, "Моя каша", drop=["Клубника"])
    with pytest.raises(ration.RationError, match="сначала заведи"):
        ration.put_dish(b, "Моя каша", add=[{"product": "Скумбрия", "grams": 100}])
    with pytest.raises(ration.RationError, match="приём"):
        ration.put_dish(b, "Моя каша", kind="полдник")
    ration.put_dish(b, "Моя каша", kind="по времени")
    assert kasha["kind"] == ""
    with pytest.raises(ration.RationError, match="нужен состав"):
        ration.put_dish(b, "Плов")
    ration.put_dish(b, "Индейка с пастой", items=[{"product": "индейка", "grams": 300}, {"product": "паста"}],
                    kind="ужин", aliases=["индейка с макаронами"])
    d = by_name(b, "Индейка с пастой")
    assert d["items"] == [{"product": "turkey-chili", "grams": 300}, {"product": "pasta", "grams": 200}]
    assert ration.check(b) == []
    # Пустое блюдо — не правка, а удаление: его просят словом remove (рабочую копию коннектор выбросит).
    with pytest.raises(ration.RationError, match="remove"):
        ration.put_dish(b, "Индейка с пастой", drop=["индейка", "паста"])


def test_remove_product_leaves_no_dangling_dish():
    b = book()
    msg = ration.put_product(b, "кускус", remove=True)
    # Кускус был в «Индейке с кускусом» — блюдо без него стало одной индейкой, а не ссылкой в пустоту.
    assert "Индейка с кускусом" in msg
    assert ration.check(b) == []
    ration.put_product(b, "индейка", remove=True)
    assert "Индейка с кускусом" not in [d["name"] for d in b["dishes"]]
    assert ration.check(b) == []


def test_text_reads_like_the_ration():
    b = book()
    full = ration.text(b, 3, "свод")
    assert full.startswith("Рацион Саши — Свод food.ration, версия 3")
    assert "- Моя каша (обычно завтрак; говорит: каша моя" in full
    assert "Креатин моногидрат 5 г — 905 ккал" in full
    one = ration.text(b, 3, "свод", "мягкий творог")
    assert "Творог мягкий высокобелковый 1.5%" in one and "Вещества на 100 г: ca 130" in one
    assert "В блюдах: Мой обед, Мой ужин" in one


# ── На базе: коннектор ──────────────────────────────────────────────────


@pytest.fixture
def rd(dela, conn):
    # Свод фикстура Дел не чистит: рацион прошлого теста — долой.
    conn.execute("DELETE FROM crm.svod WHERE key = %s", (ration.KEY,))
    return dela


def test_connector_starts_from_factory_and_writes_svod(rd):
    view = mcp_tools.ration_view(rd)
    assert "в Своде ещё нет" in view and "Моя каша" in view
    out = mcp_tools.ration_dish(rd, "моя каша", drop=["клубника"])
    assert out.startswith("Поправил блюдо: Моя каша") and "Версия рациона 1 — первая на сервере" in out
    got = store.view(rd, "sasha", "svod", key=ration.KEY)["items"][0]
    assert got["rev"] == 1 and got["author"] == "claude"
    assert "klubnika" not in [i["product"] for i in next(d for d in got["value"]["dishes"] if d["id"] == "kasha")["items"]]
    assert got["value"]["updated"] and got["value"]["about"]
    # Телефон с засевом поверх «записи не было» — отказ: рацион на сервере уже правили.
    r = store.apply_ops(rd, "sasha", [{"op": "svod.set", "key": ration.KEY, "value": book(), "author": "seed",
                                       "base_rev": 0}], via="app")["results"][0]
    assert not r["ok"] and "на сервере уже версии 1" in r["error"]
    assert "версия 1" in mcp_tools.ration_view(rd)
    assert "Клубника" not in mcp_tools.ration_view(rd, "Моя каша")


def test_connector_product_edit_and_refusals(rd):
    out = mcp_tools.ration_product(rd, "Скумбрия", grams=150)
    assert out.startswith("Не вышло:") and "kcal100" in out
    assert store.view(rd, "sasha", "svod", key=ration.KEY)["items"] == []
    out = mcp_tools.ration_product(rd, "Скумбрия холодного копчения", grams=150, kcal100=221, protein100=20.7,
                                   fat100=15.5, carbs100=0, add_aliases=["скумбрия"], micro={"o3": 2.5, "vd": 6})
    assert out.startswith("Завёл продукт: Скумбрия холодного копчения — 221 ккал")
    assert "Версия рациона 1" in out
    out = mcp_tools.ration_product(rd, "скумбрия", grams=100)
    assert "порция 100 г" in out and "Версия рациона 2" in out


def test_save_over_stale_version_refuses(rd):
    b, rev, _ = ration.load(rd, "sasha")
    ration.save(rd, b, rev, "первая")
    # Телефон успел поправить рацион (правка владельца — без base_rev).
    phone = copy.deepcopy(b)
    phone["dishes"][0]["note"] = "с телефона"
    r = store.apply_ops(rd, "sasha", [{"op": "svod.set", "key": ration.KEY, "value": phone, "author": "phone"}], via="app")
    assert r["results"][0]["row"]["rev"] == 2
    with pytest.raises(ration.RationError, match="перечитай"):
        ration.save(rd, b, 1, "поверх старой")


def test_broken_record_is_overwritten_not_looped(rd):
    # Рацион текстом вместо JSON (рука ошиблась в svod set) — правка начинает с заводского поверх его версии.
    store.apply_ops(rd, "sasha", [{"op": "svod.set", "key": ration.KEY, "body": "не рацион", "author": "claude"}], via="app")
    out = mcp_tools.ration_dish(rd, "Моя каша", kind="завтрак")
    assert "Версия рациона 2" in out, out
    assert isinstance(store.view(rd, "sasha", "svod", key=ration.KEY)["items"][0]["value"], dict)
