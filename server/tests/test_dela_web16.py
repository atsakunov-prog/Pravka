"""Веб Дел — мелочи по записям отладки 16.10.2026 (полоса главной сессии): подписи, шапки, «Круг» и «Дерево», сроки в
«Колонках», микрофоны. Веб — один файл app.js (плюс style.css и debug.js хвостом), сборщика и тестов JS нет, поэтому
правила проверяются текстом — как test_dela_store.test_now_mark_leaves_due_alone и test_dela_nest.test_web_uses_same_rules.
Базы не нужно."""
from pathlib import Path

STATIC = Path(__file__).resolve().parents[1] / "pravka_dela" / "static"
JS = (STATIC / "app.js").read_text(encoding="utf-8")


def fn(head: str, js: str = JS) -> str:
    """Тело функции верхнего уровня: от заголовка до «}» с начала строки (read_text отдаёт «\\n» и для «\\r\\n»)."""
    body = js[js.index(head):]
    return body[:body.index("\n}\n")]


def test_page_line_says_add_task():
    """Запись 24 (владелец: «это не „Саша, что ты хочешь спросить“, а просто текстбокс для дел — он сбивает именно этой
    фразой»): видимая подпись строки страницы — про дело; вопрос Claude по имени ушёл из всех копий текста веба."""
    for name in ("app.js", "debug.js", "phone.js", "twins.js", "day.js"):
        text = (STATIC / name).read_text(encoding="utf-8")
        assert "что ты хочешь спросить?`" not in text and "что ты хочешь спросить?'" not in text, name
    assert "const SAY_HINT = 'Добавь дело или скажи, что сделать';" in JS
    qa = fn("function quickAdd(")
    assert "SAY_HINT" in qa and "'Добавь дело или заметку — или скажи, что сделать'" in qa
    # Примеры страницы — во всплывающей подсказке, перед ними — что строка заводит дела (одно или несколько).
    assert "title: 'Одно или несколько дел — разложу сам." in qa
    assert "Enter или стрелка — разложу сам (можно несколько сразу)" in qa
    # «Новое» с вопросами — своя видимая подпись: туда отвечают П-номерами.
    assert "hint: asks.length ? 'Добавь дело или ответь:" in JS
