from __future__ import annotations

import pytest

from pravka_archive import tools
from pravka_archive.ingest import ingest_batch, store_events
from test_views import RUN, WELL, icu


@pytest.fixture()
def full(clean, batch):
    ingest_batch(clean, batch, "sasha")
    store_events(clean, "intervals", [icu("icu.activity", "i9001", RUN, 1), icu("icu.wellness", "2026-09-07", WELL, 2)])
    return clean


def test_schema_lists_views_and_freshness(full, cfg):
    out = tools.schema(cfg)
    assert out.startswith("Свежесть: ")
    assert "телефон:" in out
    for v in ("entries —", "money_live —", "said —", "days —", "workouts —"):
        assert v in out
    one = tools.schema(cfg, "entries")
    assert "minutes integer — Минуты суток куска" in one
    assert "нет" in tools.schema(cfg, "nope").lower()


def test_sql_table_and_errors(full, cfg):
    out = tools.run_sql(cfg, "SELECT category, sum(minutes) AS m FROM entries GROUP BY 1 ORDER BY 2 DESC")
    lines = out.splitlines()
    assert lines[0] == "category | m"
    assert lines[1] == "Дети | 620"
    assert lines[-1] == "(строк: 6)"
    assert tools.run_sql(cfg, "SELECT * FROM entries", max_rows=2).endswith("(показаны первые 2 строк — сузь запрос или сложи в SQL)")
    err = tools.run_sql(cfg, "SELECT nope FROM entries")
    assert err.startswith("Ошибка SQL:") and "nope" in err
    assert tools.run_sql(cfg, "DELETE FROM entries").startswith("Ошибка SQL:")
    assert tools.run_sql(cfg, "SELECT count(*) FROM core.events").startswith("Ошибка SQL:")


def test_search_word_forms_names_and_fallbacks(full, cfg):
    out = tools.search(cfg, "Марианна")
    assert "засечка" in out and "правка" in out
    assert "**Марианной**" in out
    sub = tools.search(cfg, "кофе")  # нет нигде — ни словоформ, ни подстроки
    assert "Ничего не нашлось" in sub
    typo = tools.search(cfg, "отчетнасть")  # опечатка распознавания
    assert "похожие слова" in typo and "отчётност" in typo
    only = tools.search(cfg, "колено", domain="тренер")
    assert "тренер" in only and "засечка" not in only


def test_day_reads_like_a_day(full, cfg):
    out = tools.day(cfg, "2026-09-07")
    assert "2026-09-07, пн" in out
    assert "Лента (1440 мин из 1440" in out
    assert "07:40–12:00 260м · Работа · разбор отчётности · Тестовый клиент" in out
    assert "комментарий: разобрал две формы" in out
    assert "сказал: завтракаем с Борей и Ромой" in out and "КБЖУ" not in out.split("Еда:")[0]
    assert "Еда: 1 приёмов, 520 ккал" in out
    assert "Run · Москва Бег" in out and "6:10/км" in out
    assert "сон 7.2 ч" in out
    assert "Махи гирей: 15×16кг 15×16кг" in out
    assert "Зарядка: частично" in out
    assert "траты семьи 2 150 ₽" in out and "−2 150 ₽ ВкусВилл · Продукты" in out
    assert "Telegram" in out and "Марианна 12 мин" in out
    assert "Правка: 1 диктовок" in out
    assert "Тренеру в 20:00" in out
    empty = tools.day(cfg, "2026-01-01")
    assert "ничего нет" in empty
