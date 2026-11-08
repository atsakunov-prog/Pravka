package ru.zf.pravka.core

import ru.zf.pravka.data.ZasechkaStore

/**
 * Виджет Засечки на главном экране (11.10.2026). Владелец: «виджет на главный
 * экран по засечке. Что идёт, кнопки записи еды (картинка, фото, текст),
 * открыть „Сегодня“. Поменять текущее. Ну, короче, всё то, что обычно на
 * виджетах».
 *
 * 08.11.2026 — «кнопки и лента»: «давай ещё поправим дизайн виджета так, чтобы
 * там были и кнопки, и ещё была лента». Из трёх вариантов на снимках владелец
 * выбрал «Пульт» — «А, а вширь — В»: категории плитками (касание —
 * «переключить на …», как в меню долгого нажатия «З»), полоса суток и
 * последние куски ленты; растянутый вширь виджет — в две колонки. «Ещё раз»
 * (три недавних дела) ушло: категории его заменили.
 *
 * Здесь только решения (под тестом `ZWidgetTest`): что написать про идущее
 * дело, порядок категорий, лента сегодня и сколько всего влезает в размер,
 * который владелец растянул. Рисует и слушает касания `trigger/ZasechkaWidget.kt`.
 */
object ZWidget {

    /** Что идёт: строка названия, подпись, минуты и значок — как на «З». */
    data class Now(
        val id: Long,
        val title: String,
        val sub: String,
        val start: Long,
        val icon: ZFace.Icon?,
    )

    /** Кнопка категории: касание — «переключить на …», как в меню долгого нажатия «З» ([ZSwitch]). */
    data class Cat(val name: String, val icon: ZFace.Icon?, val current: Boolean)

    /**
     * Кусок сегодняшней ленты: [from]–[to] обрезаны полуночью, [to] 0 — идёт сейчас (длительность считает
     * рисовальщик на каждом тике, а модель от тиков не меняется). [gap] — «не размечено».
     */
    data class Seg(val id: Long, val from: Long, val to: Long, val title: String, val category: String, val gap: Boolean)

    /**
     * Всё, что виджет показывает, кроме минут: минуты считаются на каждом тике. [cats] — все категории
     * в порядке «чаще начинаю — выше», [day] — лента сегодня, свежее сверху.
     */
    data class Model(
        val now: Now?,
        val cats: List<Cat> = emptyList(),
        val day: List<Seg> = emptyList(),
    )

    /**
     * Что влезает в размер виджета. [wide] — две колонки (слева что идёт, категории и кнопки, справа полоса
     * суток и лента), иначе «Пульт» сверху вниз. [narrow] — уже трёх ячеек: минуты в кружке, еды и «Сегодня»
     * нет. [cols]×[catRows] — кнопки категорий, [rows] — строки ленты (идущее дело — в шапке, не здесь).
     */
    data class Plan(
        val wide: Boolean,
        val narrow: Boolean,
        val cols: Int,
        val catRows: Int,
        val rows: Int,
        val strip: Boolean,
        val actions: Boolean,
    ) {
        val cats: Int get() = cols * catRows
    }

    // Высоты частей в dp — по раскладкам `widget_zasechka*.xml` (поля, шапка, плитка с зазором, полоса суток
    // с часами, строка ленты, ряд кнопок с подписями). Ошибка в большую сторону — пустое место внизу, в меньшую —
    // обрезанный низ: числа взяты с запасом по снимкам.
    private const val PAD = 20
    private const val HEAD = 46
    private const val TILE = 68
    private const val STRIP = 34
    private const val ROW = 26
    private const val ROWS_GAP = 8
    private const val ACTIONS = 66
    private const val PILL = 46
    private const val ACTIONS_WIDE = 76
    private const val STRIP_WIDE = 28

    /**
     * Раскладка по размеру, который отдал лаунчер (dp; портрет: ширина минимальная, высота максимальная).
     * Строка ячеек — только «что идёт». Шире 480 и заметно шире, чем выше, — две колонки. Иначе места
     * раздаются по очереди: ряд кнопок категорий, полоса суток, две строки ленты, ряд кнопок записи, ещё
     * два ряда категорий, третья строка ленты — дальше ряд категорий и строки ленты, пока есть место.
     */
    fun plan(widthDp: Int, heightDp: Int): Plan {
        val narrow = widthDp < 220
        if (heightDp < 100) return Plan(false, narrow, 0, 0, 0, strip = false, actions = false)
        if (widthDp >= 480 && widthDp * 3 >= heightDp * 4) {
            var left = heightDp - PAD - HEAD - 4
            var catRows = 0
            var actions = false
            if (left >= PILL) { catRows = 1; left -= PILL }
            if (left >= ACTIONS_WIDE) { actions = true; left -= ACTIONS_WIDE }
            catRows += (left / PILL).coerceIn(0, 5)
            val rows = ((heightDp - PAD - STRIP_WIDE - ROWS_GAP) / ROW).coerceIn(0, 12)
            return Plan(true, false, 2, catRows, rows, strip = true, actions = actions)
        }
        // Плитка — не уже 95 dp: «Систематизация» и «Передвижение:» должны влезть в строку целиком.
        val cols = ((widthDp - 26) / 95).coerceIn(2, 4)
        var left = heightDp - PAD - HEAD
        fun take(n: Int): Boolean = (left >= n).also { if (it) left -= n }
        var catRows = 0
        var rows = 0
        var strip = false
        var actions = false
        if (take(TILE + 4)) catRows = 1
        if (catRows == 1 && take(STRIP)) strip = true
        if (take(ROWS_GAP + 2 * ROW)) rows = 2
        if (take(ACTIONS)) actions = true
        while (catRows < 3 && take(TILE)) catRows++
        if (rows == 2 && take(ROW)) rows = 3
        if (catRows == 3 && take(TILE)) catRows = 4
        while (rows in 1..7 && take(ROW)) rows++
        return Plan(false, narrow, cols, catRows, rows, strip, actions)
    }

    /**
     * Модель из ленты и справочника категорий. Кнопки — все категории в порядке
     * меню «З» ([ZSwitch.order]: чаще начинаю — выше); идущая отмечена (дыра —
     * не категория, при ней не отмечено ничего).
     */
    fun model(entries: List<ZasechkaStore.Entry>, now: Long, categories: List<String> = emptyList()): Model {
        val open = entries.lastOrNull { it.open }
        val nowRow = open?.let {
            Now(
                id = it.id,
                title = it.title.trim().ifBlank { if (it.source == "gap") "не размечено" else "без названия" },
                sub = sub(it.category, it.client, it.start),
                start = it.start,
                icon = ZFace.of(it.category, it.source, it.start).icon,
            )
        }
        val running = open?.takeIf { it.source != "gap" }?.category?.trim().orEmpty()
        val cats = ZSwitch.order(categories, entries, now).map {
            Cat(it, ZFace.icon(it), running.isNotEmpty() && it.equals(running, ignoreCase = true))
        }
        return Model(nowRow, cats, today(entries, now))
    }

    /**
     * Лента сегодня — с полуночи до «сейчас», свежее сверху, куски обрезаны полуночью. Будущего нет: это лента,
     * а не план. Закрытые куски короче минуты не рисуются — на виджете это шум.
     */
    fun today(entries: List<ZasechkaStore.Entry>, now: Long): List<Seg> {
        val midnight = midnight(now)
        val out = ArrayList<Seg>()
        for (e in entries) {
            val end = if (e.open) now else e.end
            if (end <= midnight || e.start >= now) continue
            val from = maxOf(e.start, midnight)
            val to = minOf(end, now)
            if (!e.open && to - from < 60_000L) continue
            val gap = e.source == "gap" || e.category.trim().equals("Не размечено", ignoreCase = true)
            val title = e.title.trim().ifBlank { if (gap) "не размечено" else "без названия" }
            out += Seg(e.id, from, if (e.open) 0L else to, title, e.category.trim(), gap)
        }
        return out.sortedByDescending { it.from }
    }

    /** Полночь суток, в которые попадает [now], по часам телефона. */
    fun midnight(now: Long): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = now
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Длительность строки ленты: «12 мин», «1 ч 05», «8 ч». */
    fun dur(ms: Long): String {
        val m = (ms.coerceAtLeast(0L) / 60_000L).toInt()
        return when {
            m < 60 -> "$m мин"
            m % 60 == 0 -> "${m / 60} ч"
            else -> "${m / 60} ч ${(m % 60).toString().padStart(2, '0')}"
        }
    }

    /** «Еда · с 14:10» или «Работа: звонки · Бета Групп · с 14:10». */
    fun sub(category: String, client: String, start: Long): String =
        listOf(category.trim(), client.trim(), "с " + hm(start))
            .filter { it.isNotEmpty() }
            .joinToString(" · ")

    /**
     * Подпись под числом минут: «мин» под «25», «ч» под «1:25», ничего под
     * «2д» — единица уже в самом числе.
     */
    fun unit(label: String): String = when {
        label.endsWith("д") -> ""
        ':' in label -> "ч"
        else -> "мин"
    }

    /** Время «14:10» — начало строки ленты и подпись идущего. */
    fun hm(ms: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ms))
}
