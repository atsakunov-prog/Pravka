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


def test_now_and_today_head_is_just_the_title():
    """Запись 15 (владелец: «пятница 16 октября, сейчас 0 из 5, закрыто 8, просрочено 41, ждут без срока — мне зачем вся
    эта информация?»): шапка «Сейчас» и «Сегодня» — одно название; просрочено и «ждут» — мелкой строкой в самом низу,
    теми же ссылками на «Все дела» и фильтры по людям."""
    now, today = fn("function renderNow()"), fn("function renderToday()")
    assert "head('Сейчас')," in now and "head('Сегодня')," in today
    for body in (now, today):
        assert "D.long(S.today)" not in body and "'закрыто '" not in body and "NOW_MAX" not in body
        assert body.rstrip().endswith("tailLine())];"), "хвосты — последней строкой страницы"
    # Порядок сверху: девиз, поле, «Сейчас», …, «Сегодня».
    assert now.index("principleLine()") < now.index("quickAdd(") < now.index("groupBox('Сейчас'") < now.index("todayBox(")
    tail = fn("function tailLine()")
    assert "lateLink()" in tail and "...waitLinks()" in tail and "class: 'tail-line'" in tail
    css = (STATIC / "style.css").read_text(encoding="utf-8")
    assert ".tail-line {" in css and ".tail-line a.late" in css


def test_page_line_is_the_add_field_with_plus_and_mic_on_the_right():
    """Запись 14 (владелец: «нигде нет плюса… очень высоко текстбокс типа „добавить дело“ и справа микрофончик, чтобы
    сразу несколько дел добавлять»): это та же строка — вверху каждой страницы, под заголовком и девизом; слева тихий «+»
    (знак, не кнопка), справа клавиша: пусто — микрофон, с текстом — «отдать». Раскладывает захват: новых путей нет."""
    qa = fn("function quickAdd(")
    assert "el('label', { class: 'q-plus', for: 'quick', 'aria-hidden': 'true' }, icon('plus', 18))" in qa
    assert "}, plus, input, micBtn, sendBtn);" in qa, "«+» слева, микрофон и «отдать» — справа"
    # Строка встаёт под девизом на любой странице, где её не положили сами (listColumn).
    lc = fn("function listColumn(")
    assert "motto.after(say)" in lc
    # Тот же вход, что был: /api/ask (правило захвата → Claude → capture.say_now), ответ «куда легло» — captureBlock.
    assert "runJob('/api/ask'" in fn("async function claudeSay(") and "captureBlock(t)" in JS
    css = (STATIC / "style.css").read_text(encoding="utf-8")
    assert ".q-plus {" in css


def test_no_claude_sees_line_and_krug_tree_heads_are_short():
    """Записи 16–17 (владелец: «наверху много всего… почему „Claude видит экран и открытое дело“ — это вообще надо отовсюду
    убрать… сам круг жизни давай поставим вниз, а сначала направления»). Подписи нет ни над строкой страницы, ни в
    карточке дела (там — только в «Для этого» и «Кстати»); что Claude видит, не поменялось — pageScope тот же."""
    css = (STATIC / "style.css").read_text(encoding="utf-8")
    for bit in ("class: 'say-h'", "class: 'say-model'", "'Claude видит только это дело'", "`Claude видит экран",
                "'Claude видит экран '", "card-scope"):
        assert bit not in JS, bit
    assert ".say-h" not in css and ".say-model" not in css
    cs = fn("function cardSay(")
    assert "st || k ? el('label'" in cs
    assert "if (S.cardId && S.tasks.has(S.cardId)) scope.open = S.cardId;" in fn("function pageScope("), "контекст Claude прежний"
    # Круг: девиз → направления по зонам → «Без направления» → циферблат последним; шапка — одно название.
    kr = fn("function renderKrug()")
    assert kr.index("'krug-zone'") < kr.index("'Без направления'") < kr.index("dialSvg(mv)")
    assert "'Движение за неделю'" in kr and "return [head('Круг жизни'), box];" in kr
    # Дерево: шапка без чисел, «перетащи дело на цель» — всплывающей подсказкой.
    tr = fn("function renderTree()")
    assert "return [head('Дерево жизни')," in tr and "plural(" not in tr
    assert "'Перетащи дело на цель — поменяешь «Зачем»'" in tr
    assert ".krug-dial {" in css


def test_tree_columns_show_due_on_the_right():
    """Запись 18 (владелец: «в Дереве жизни… справа от дела тоже писать, какие у них сроки»): в «Колонках» у дела справа —
    срок коротко, как в списках (dueLabel): просроченное — дата цветом беды, сегодня — акцентом, «потом» — «↩ …»."""
    cd = fn("function colDue(")
    assert "dueLabel(t)" in cd and "D.ddmm(t.due_date)" in cd and "t.back_on && !isRemind(t)" in cd
    row = fn("function treeCols(")
    assert "el('span', { class: 'c-t' }, t.title), colDue(t))" in row
    css = (STATIC / "style.css").read_text(encoding="utf-8")
    due = css[css.index(".col-task .col-due {"):]
    assert "white-space: nowrap" in due[:due.index("}")] and "margin-left: auto" in due[:due.index("}")]
    assert ".col-task .col-due.late { color: var(--bad)" in css and ".col-task.today .c-t" in css
