package ru.zf.pravka.core

import ru.zf.pravka.data.ZasechkaStore

/**
 * «Переключить на …» — категории ленты кнопками на долгом нажатии «З» (08.11.2026). Владелец: «если я долго нажимаю
 * на засечку, пускай там будет список, на что сейчас можно переключить… посмотри по тому, насколько я часто что-то
 * делаю, и отсортируй. Все категории… это тогда будет просто кнопка переключения».
 *
 * Порядок — сколько раз категория НАЧИНАЛАСЬ за последние [DAYS] суток: куски одного дела, разрезанные полночью или
 * фактом робота (сон, тренировка с часов), несут одно время создания и считаются одним началом. При равенстве — больше
 * минут, потом порядок справочника. Неиспользованные — в конце, порядком справочника: список целиком, как просили.
 * «Не размечено» — не дело, а дыра: кнопки нет. Считается по своей копии ленты — работает и без сети.
 */
object ZSwitch {
    const val DAYS = 30
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val GAP = "Не размечено"

    fun order(categories: List<String>, entries: List<ZasechkaStore.Entry>, now: Long): List<String> {
        val names = categories.map { it.trim() }.filter { it.isNotEmpty() && !it.equals(GAP, ignoreCase = true) }.distinct()
        val from = now - DAYS * DAY_MS
        val starts = HashMap<String, MutableSet<String>>()
        val minutes = HashMap<String, Long>()
        for (e in entries) {
            if (e.source == "gap" || e.start < from || e.start > now) continue
            val cat = names.firstOrNull { it.equals(e.category.trim(), ignoreCase = true) } ?: continue
            // Одно дело — одно начало: у кусков одного дела одно время создания.
            starts.getOrPut(cat) { HashSet() } += if (e.createdAt > 0) "c${e.createdAt}|${e.title.trim()}" else "i${e.id}"
            minutes[cat] = (minutes[cat] ?: 0L) + e.durationMs(now) / 60_000L
        }
        return names.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<String>> { starts[it.value]?.size ?: 0 }
                    .thenByDescending { minutes[it.value] ?: 0L }
                    .thenBy { it.index },
            )
            .map { it.value }
    }
}
