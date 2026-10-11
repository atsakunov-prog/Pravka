package ru.zf.pravka.core

import ru.zf.pravka.data.ZasechkaStore

/**
 * Виджет Засечки на главном экране (11.10.2026). Владелец: «виджет на главный
 * экран по засечке. Что идёт, кнопки записи еды (картинка, фото, текст),
 * открыть „Сегодня“. Поменять текущее. Ну, короче, всё то, что обычно на
 * виджетах».
 *
 * Здесь только решения (под тестом `ZWidgetTest`): что написать про идущее
 * дело, какие недавние дела предложить «ещё раз» одним касанием и сколько
 * всего влезает в размер, который владелец растянул. Рисует и слушает касания
 * `trigger/ZasechkaWidget.kt`.
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

    /** Дело, которое можно начать снова одним касанием. */
    data class Again(val id: Long, val title: String, val icon: ZFace.Icon?)

    /** Всё, что виджет показывает, кроме минут: минуты считаются на каждом тике. */
    data class Model(val now: Now?, val again: List<Again>)

    /**
     * Сколько влезает. Высоту лаунчер отдаёт в dp ячеек: одна строка ячеек —
     * только «что идёт»; две — плюс ряд кнопок; три и выше — ещё и «ещё раз».
     * Узкий виджет (в две ячейки) кнопки еды не вмещает — там одна кнопка
     * «сменить», остальное откроется тапом.
     */
    enum class Size { ROW, ACTIONS, FULL }

    fun size(widthDp: Int, heightDp: Int): Size = when {
        heightDp < 100 -> Size.ROW
        heightDp < 170 -> Size.ACTIONS
        else -> Size.FULL
    }

    /** Кнопки еды и «Сегодня» — только если ряд шире двух ячеек. */
    fun wide(widthDp: Int): Boolean = widthDp >= 220

    private const val AGAIN_MAX = 3
    private const val AGAIN_WINDOW_MS = 14 * 86_400_000L

    /**
     * Модель из ленты. «Ещё раз» — последние разные дела за две недели,
     * сказанные самим владельцем: заполнитель дыры, сон и тренировки с часов
     * (`auto`) сюда не идут — их не начинают касанием. Идущее дело в списке
     * не повторяется, одинаковые названия — одной кнопкой (самой свежей).
     */
    fun model(entries: List<ZasechkaStore.Entry>, now: Long): Model {
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
        val seen = HashSet<String>()
        open?.title?.trim()?.lowercase()?.let { seen += it }
        val again = ArrayList<Again>(AGAIN_MAX)
        for (e in entries.asReversed()) {
            if (again.size >= AGAIN_MAX) break
            if (e.start < now - AGAIN_WINDOW_MS) break
            if (e.open || e.source == "gap" || e.source == "auto") continue
            val title = e.title.trim()
            if (title.isEmpty() || e.category.isBlank()) continue
            if (e.category.equals("Не размечено", ignoreCase = true)) continue
            if (!seen.add(title.lowercase())) continue
            again += Again(e.id, title, ZFace.icon(e.category))
        }
        return Model(nowRow, again)
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

    private fun hm(ms: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ms))
}
