"""Вид и меню Ковчега из Свода (14.10.2026, Правка 5.0, этап 7): ui.menu и ui.look.

Правит владелец в вебе Дел; кривое значение не ложится (иначе шапка упала бы на всех страницах и в телефоне);
/api/me отдаёт действующее, а без записи — заводское; сборщик «Дома» читает только ui.* своим токеном.
"""

from __future__ import annotations

import copy
import json
from pathlib import Path

import pytest
from starlette.testclient import TestClient

from pravka_dela import api, kovcheg_ui, store, tokens
from pravka_dela import db as dela_db
from pravka_dela.config import Config, parse_nav
from test_dela_schema import dela  # noqa: F401


def ops(url, user, *items):
    return store.apply_ops(url, user, list(items), "web")["results"]


def moved() -> dict:
    """Владелец переставил «Пару» первой, переименовал подпись и унёс Кино в «Быт»."""
    m = copy.deepcopy(kovcheg_ui.FACTORY_MENU)
    dirs = m["directions"]
    para = dirs.pop(6)
    para["sub"] = "  вдвоём   хорошо "
    para["items"].pop()   # Кино уходит из «Пары»…
    dirs.insert(0, para)
    dirs[-1]["items"].append({"key": "kino", "name": "Фильмы"})
    return m


def test_factory_is_the_contract_and_passes_its_own_check():
    four = json.loads((Path(__file__).resolve().parents[1] / "contract" / "svod.json").read_text(encoding="utf-8"))
    assert four["ui"]["menu_factory"] == kovcheg_ui.FACTORY_MENU
    assert four["ui"]["look_factory"] == kovcheg_ui.FACTORY_LOOK
    assert {"ui.menu", "ui.look"} <= set(four["svod"]["keys"])
    assert kovcheg_ui.check_menu(kovcheg_ui.FACTORY_MENU) == kovcheg_ui.FACTORY_MENU
    # Запас веба Дел (на сервер без look.menu) — то же заводское строкой JSON в app.js.
    js = (Path(kovcheg_ui.__file__).parent / "static" / "app.js").read_text(encoding="utf-8")
    line = next(x for x in js.splitlines() if x.startswith("/* ui.menu_factory */"))
    assert json.loads(line.split("= ", 1)[1].rstrip(";")) == kovcheg_ui.FACTORY_MENU
    assert kovcheg_ui.check_look(kovcheg_ui.FACTORY_LOOK) == kovcheg_ui.FACTORY_LOOK
    # Заводская пара «акцент → мягкий и текст на нём» выводится почти той же, что в kovcheg.css.
    lt = kovcheg_ui.check_look({"light": {"accent": "#2747C9"}})["light"]
    assert lt == {"info": "#2747c9", "accent": "#2747c9", "accent-ink": "#ffffff", "accent-soft": "#e9edfa"}
    dk = kovcheg_ui.check_look({"dark": {"accent": "#93abf8"}})["dark"]
    assert dk["accent-ink"] == "#141414" and dk["accent-soft"].startswith("#3")


def test_check_menu_normalizes_and_refuses_what_would_break():
    m = kovcheg_ui.check_menu(moved())
    assert [d["name"] for d in m["directions"]][:2] == ["Пара", "Здоровье"]
    assert m["directions"][0] == {"name": "Пара", "sub": "вдвоём хорошо", "items": []}
    assert m["directions"][-1]["items"][-1] == {"key": "kino", "name": "Фильмы", "icon": "film"}
    # Частью: только направления — большие кнопки и «Творчество» заводские; create: null — строки нет.
    part = kovcheg_ui.check_menu({"directions": [{"name": "Дело", "items": ["dela"]}]})
    assert part["big"] == kovcheg_ui.FACTORY_MENU["big"] and part["create"] == kovcheg_ui.FACTORY_MENU["create"]
    assert part["directions"][0]["items"] == [{"key": "dela", "name": "Дела", "icon": "dela"}]
    assert kovcheg_ui.check_menu({"directions": [{"name": "Дело"}], "create": None})["create"] is None
    # Новый сервис — с названием и значком: ключ без дороги клиент просто не покажет.
    new = kovcheg_ui.check_menu({"directions": [{"name": "Ум", "items": [{"key": "kursy", "name": "Курсы", "icon": "book"}]}]})
    assert new["directions"][0]["items"][0]["key"] == "kursy"
    bad = [
        ([], "нужен объект"),
        ({"directions": []}, "от 1 до"),
        ({"directions": [{"name": ""}]}, "пусто"),
        ({"directions": [{"name": "Дело", "items": ["dela"]}, {"name": "Ещё дело", "items": ["dela"]}]}, "дважды"),
        ({"directions": [{"name": "Дело"}, {"name": "дело"}]}, "одним названием"),
        ({"directions": [{"name": "<b>Дело</b>"}]}, "угловые"),
        ({"directions": [{"name": "Очень-очень длинное направление"}]}, "длиннее 24"),
        ({"directions": [{"name": "Дело", "items": [{"key": "Sport"}]}]}, "латиницей"),
        ({"directions": [{"name": "Дело", "items": [{"key": "kursy"}]}]}, "название"),
        ({"directions": [{"name": "Дело", "items": [{"key": "sport", "icon": "bad icon"}]}]}, "значок"),
        ({"directions": [{"name": "Дело", "url": "https://x"}]}, "незнакомые поля url"),
        ({"directions": [{"name": "Дело"}], "nav": []}, "незнакомые поля nav"),
        ({"directions": [{"name": "Дело"}], "big": [{"key": f"k{i}", "name": "К", "icon": "sun"} for i in range(5)]}, "до 4"),
    ]
    for value, why in bad:
        with pytest.raises(kovcheg_ui.UiError, match=why):
            kovcheg_ui.check_menu(value)
    for value, why in [({"light": {"accnt": "#fff"}}, "такого цвета"), ({"light": {"accent": "red"}}, "цвет вида"),
                       ({"light": {"accent": "#2747c9;}body{x"}}, "цвет вида"), ({}, "нет ни"), ({"sepia": {}}, "незнакомые")]:
        with pytest.raises(kovcheg_ui.UiError, match=why):
            kovcheg_ui.check_look(value)


def test_svod_set_ui_owner_only_checked_before_write(dela):
    r = ops(dela, "sasha", {"op": "svod.set", "key": "ui.menu", "value": moved(), "author": "web", "base_rev": 0})[0]
    assert r["ok"], r
    row = store.view(dela, "sasha", "svod", key="ui.menu")["items"][0]
    assert row["rev"] == 1 and row["value"]["directions"][0]["sub"] == "вдвоём хорошо"   # лежит приведённое
    # Кривое — отказ словами, прежняя версия цела.
    r = ops(dela, "sasha", {"op": "svod.set", "key": "ui.menu", "value": {"directions": []}, "author": "web"})[0]
    assert not r["ok"] and "направлений" in r["error"]
    assert store.view(dela, "sasha", "svod", key="ui.menu")["items"][0]["rev"] == 1
    # Текстом, незнакомым ключом ui.*, не владельцем — нет.
    r = ops(dela, "sasha", {"op": "svod.set", "key": "ui.menu", "body": "{}", "author": "web"})[0]
    assert not r["ok"] and "JSON" in r["error"]
    r = ops(dela, "sasha", {"op": "svod.set", "key": "ui.font", "value": {}, "author": "web"})[0]
    assert not r["ok"] and "ui.menu" in r["error"]
    r = ops(dela, "natasha", {"op": "svod.set", "key": "ui.look", "value": {"light": {"accent": "#000000"}}, "author": "web"})[0]
    assert not r["ok"] and "владелец" in r["error"]
    # Две вкладки веба: вторая сохраняет поверх устаревшей версии — отказ, а не затирание.
    r = ops(dela, "sasha", {"op": "svod.set", "key": "ui.menu", "value": kovcheg_ui.FACTORY_MENU, "author": "web", "base_rev": 0})[0]
    assert not r["ok"] and "уже версии 1" in r["error"]
    r = ops(dela, "sasha", {"op": "svod.set", "key": "ui.look", "value": {"light": {"accent": "#2F7D4F"}}, "author": "web"})[0]
    assert r["ok"] and r["row"]["value"]["light"]["accent-soft"] == "#eaf2ed"
    # Телефон получает обе записи обычным синком Свода.
    assert {"ui.menu", "ui.look"} <= {x["key"] for x in store.sync(dela, "sasha", 0)["svod"]}


def test_api_me_menu_tokens_and_pages(dela, tmp_path, monkeypatch):
    env = tmp_path / "dela.env"
    env.write_text("DELA_DB_URL=" + dela + "\nDELA_PUBLIC_URL=https://dela.example.test\n"
                   "DELA_NAV=Дом|home|https://home.example.test/;Дела|dela|/;Курсы|book|https://kursy.example.test/|kursy;"
                   "Копии|copies|https://copies.example.test/\n", encoding="utf-8")
    from pravka_dela.config import load

    c = TestClient(api.build(load(str(env))))
    owner = {"Authorization": "Bearer " + tokens.issue(dela, "sasha", "device", "телефон-меню")}
    team = {"Authorization": "Bearer " + tokens.issue(dela, "natasha", "device", "телефон-наташи-меню")}
    look = c.get("/api/me", headers=owner).json()["look"]
    # Ключ сервиса: четвёртым полем настройки или по значку; незнакомый значок — без ключа («Ещё»).
    assert [(x["name"], x["key"]) for x in look["nav"]] == [("Дом", "dom"), ("Дела", "dela"), ("Курсы", "kursy"), ("Копии", "")]
    assert look["menu"] == kovcheg_ui.FACTORY_MENU and look["tokens"]["light"]["accent"] == "#2747c9"
    assert look["pages"] == []   # двойников «Сегодня», «Засечки», «Правки» этот сервер ещё не умеет — кнопок в вебе нет
    ops(dela, "sasha", {"op": "svod.set", "key": "ui.menu", "value": moved(), "author": "web"},
        {"op": "svod.set", "key": "ui.look", "value": {"light": {"accent": "#2f7d4f"}}, "author": "web"})
    look = c.get("/api/me", headers=owner).json()["look"]
    assert look["menu"]["directions"][0]["name"] == "Пара"
    assert look["tokens"] == {"light": {"info": "#2f7d4f", "accent": "#2f7d4f", "accent-ink": "#ffffff", "accent-soft": "#eaf2ed"},
                              "dark": {}}
    # Команде — вид (он у Ковчега один), но не меню владельца и не его страницы.
    tl = c.get("/api/me", headers=team).json()["look"]
    assert "menu" not in tl and "pages" not in tl and tl["nav"] == [] and tl["tokens"]["light"]["accent"] == "#2f7d4f"
    # Страницы, которые сервер умеет, — владельцу списком.
    monkeypatch.setattr(kovcheg_ui, "PAGES", ("day", "pravka"))
    assert c.get("/api/me", headers=owner).json()["look"]["pages"] == ["day", "pravka"]
    # Настройки владельца: действующее, версия и заводское — для редактора «Вид и меню».
    s = c.get("/api/settings", headers=owner).json()["ui"]
    assert s["menu_from"] == "svod" and s["menu_rev"] == 1 and s["factory"]["menu"] == kovcheg_ui.FACTORY_MENU
    assert "accent" in s["tokens_all"] and {"key": "kino", "name": "Кино", "icon": "film"} in s["catalog"]
    assert "ui" not in c.get("/api/settings", headers=team).json()


def test_broken_record_falls_back_to_factory(dela):
    """Запись, лёгшая мимо проверки (руками в базу), не роняет шапку: /api/me отдаёт заводское."""
    with dela_db.session(dela, "system", "test") as conn:
        conn.execute("INSERT INTO crm.svod (owner_id, key, value, author) VALUES "
                     "('sasha', 'ui.menu', '{\"directions\": \"сломано\"}', 'test'), "
                     "('sasha', 'ui.look', '{\"light\": {\"accent\": \"url(x)\"}}', 'test')")
    ui = kovcheg_ui.effective(dela)
    assert ui["menu"] == kovcheg_ui.FACTORY_MENU and ui["menu_from"] == "factory"
    assert ui["tokens"]["light"] == kovcheg_ui.FACTORY_LOOK["light"] and ui["tokens_from"] == "factory"


def test_dom_token_reads_only_ui(dela):
    ops(dela, "sasha", {"op": "svod.set", "key": "ui.menu", "value": moved(), "author": "web"},
        {"op": "svod.set", "key": "prompt.food", "body": "Еда.", "author": "phone"})
    c = TestClient(api.build(Config(db_url=dela)))
    dom = {"Authorization": "Bearer " + tokens.issue(dela, "sasha", "service", "dom")}
    items = c.get("/api/view/svod", headers=dom).json()["items"]
    assert [i["key"] for i in items] == ["ui.menu"]
    assert c.get("/api/view/svod", params={"prefix": "prompt."}, headers=dom).json()["items"] == []
    assert c.get("/api/view/svod", params={"key": "prompt.food"}, headers=dom).status_code == 403
    assert c.get("/api/me", headers=dom).json()["look"]["pages"] == []   # сборщику — ради look.pages
    for path in ("/api/sync", "/api/view/now"):
        assert c.get(path, headers=dom).status_code == 403
    r = c.post("/api/ops", headers=dom, json={"ops": [{"op": "svod.set", "key": "ui.menu", "value": {}, "author": "dom"}]})
    assert r.status_code == 403 and "только чтение" in r.json()["error"]


def test_parse_nav_key_field():
    nav = parse_nav("Дом|home|https://h.test/;Курсы|book|https://k.test/|kursy;Плохой ключ|film|https://f.test/|Bad Key;"
                    "Кино|film|https://f.test/")
    assert nav == (("Дом", "home", "https://h.test/", "dom"), ("Курсы", "book", "https://k.test/", "kursy"),
                   ("Кино", "film", "https://f.test/", "kino"))
