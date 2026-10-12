package ru.zf.pravka.core

import ru.zf.pravka.data.IcuSportSync
import ru.zf.pravka.data.PlanStore

// План дня — из календаря intervals: сессия, тип, длительность, нагрузка и
// нумерованный список упражнений в описании. Структура, ключ уже есть, кэш
// офлайн тривиален.
//
// До 12.10.2026 у плана было ещё два источника в Notion: ПРАВИЛА блока
// (потолок пульса, каденс, серая зона, светофор колена — Сонет вынимал числа
// из прозы раз в сутки) и СПРАВОЧНИК упражнений. Владелец убрал Notion («я
// ничего с ним не делаю»): правила и справочник заморожены на последнем
// полученном — `PlanStore` и `ExerciseBook` читают их с диска как раньше,
// светофор и тренер работают на них же, просто они больше не обновляются.
//
// Падение календаря оставляет кэш как был.
class PlanSync(
    private val icu: IcuSportSync,
    private val store: PlanStore,
) {

    companion object {
        // Календарь — раз в сутки, по слову владельца: «правлю план в чате —
        // сам и обновлю». Кнопка «Обновить» идёт в сеть сразу, фон не суетится.
        private const val EVENTS_PERIOD_MS = 24 * 3_600_000L
    }

    @Volatile private var lastEvents = 0L
    @Volatile private var lastError = ""

    fun lastError(): String = lastError

    data class Outcome(
        val events: Boolean,
        val error: String,
    )

    /**
     * Только если календарь старше суток. Владелец сказал прямо: «поменял
     * план в чате — сам и обновлю» (кнопкой), а фоновая суета при каждом
     * открытии вкладки ему не нужна.
     */
    suspend fun refreshEventsIfStale(maxAgeMs: Long = 24 * 3_600_000L): Boolean {
        store.load()
        val age = System.currentTimeMillis() - store.eventsFetchedAt()
        if (age < maxAgeMs) return false
        val ok = runCatching { icu.refreshPlan(store) }.getOrDefault(false)
        if (ok) lastEvents = System.currentTimeMillis()
        return ok
    }

    /**
     * Обновить план. [force] — владелец сам потянул экран: идём в календарь,
     * не глядя на таймер.
     */
    suspend fun refresh(force: Boolean = false): Outcome {
        val now = System.currentTimeMillis()
        store.load()
        var events = false
        val errors = mutableListOf<String>()

        if (force || now - lastEvents > EVENTS_PERIOD_MS) {
            events = runCatching { icu.refreshPlan(store) }.getOrElse { e ->
                errors.add("календарь: ${e.message ?: e.javaClass.simpleName}")
                false
            }
            if (events) lastEvents = now
            else if (icu.lastError().isNotBlank()) errors.add(icu.lastError())
        }

        lastError = errors.joinToString("; ")
        return Outcome(events, lastError)
    }
}
