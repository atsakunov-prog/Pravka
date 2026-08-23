package ru.zf.pravka.core

import ru.zf.pravka.data.EventLog
import ru.zf.pravka.data.IcuSportSync
import ru.zf.pravka.data.NotionPlanSync
import ru.zf.pravka.data.PlanStore
import ru.zf.pravka.data.Stats
import ru.zf.pravka.provider.ClaudeProvider

// Сборка плана из двух источников — ровно так, как владелец их разделил сам:
// «Notion — оперативка и знания, intervals.icu — календарь и факт».
//
//   СКЕЛЕТ приезжает из календаря intervals: сессия, тип, длительность,
//   нагрузка и нумерованный список упражнений в описании. Структура, ключ уже
//   есть, кэш офлайн тривиален.
//
//   ПРАВИЛА приезжают со страницы блока в Notion: потолок пульса, каденс,
//   серая зона, лимит пробежек, светофор колена. Их владелец правит руками, и
//   они прозой — поэтому страница читается раз в сутки, а числа из неё вынимает
//   Сонет одним вызовом.
//
// Оба источника необязательные и независимые: календарь без Notion даёт
// карточку дня без порогов, Notion без календаря — правила без сессии. Падение
// любого из них оставляет кэш как был.
class PlanSync(
    private val icu: IcuSportSync,
    private val notion: NotionPlanSync,
    private val claude: ClaudeProvider,
    private val store: PlanStore,
    private val stats: Stats,
    private val eventLog: EventLog,
) {

    companion object {
        // Календарь — раз в сутки, по слову владельца: «правлю план в чате —
        // сам и обновлю». Кнопка «Обновить» идёт в сеть сразу, фон не суетится.
        private const val EVENTS_PERIOD_MS = 24 * 3_600_000L
        // Правила — раз в сутки. Страница блока меняется раз в месяц.
        private const val RULES_PERIOD_MS = 24 * 3_600_000L
    }

    @Volatile private var lastEvents = 0L
    @Volatile private var lastRules = 0L
    @Volatile private var lastError = ""

    fun lastError(): String = lastError

    data class Outcome(val events: Boolean, val rules: Boolean, val error: String)

    /**
     * Только календарь, и только если он старше суток. Владелец сказал прямо:
     * «поменял план в чате — сам и обновлю» (кнопкой), а фоновая суета при
     * каждом открытии вкладки ему не нужна. Правила Notion не трогаем: их
     * чтение стоит вызова Сонета.
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
     * Обновить план. [force] — владелец сам потянул экран: идём в оба источника,
     * не глядя на таймеры.
     */
    suspend fun refresh(force: Boolean = false): Outcome {
        val now = System.currentTimeMillis()
        store.load()
        var events = false
        var rules = false
        val errors = mutableListOf<String>()

        if (force || now - lastEvents > EVENTS_PERIOD_MS) {
            events = runCatching { icu.refreshPlan(store) }.getOrElse { e ->
                errors.add("календарь: ${e.message ?: e.javaClass.simpleName}")
                false
            }
            if (events) lastEvents = now
            else if (icu.lastError().isNotBlank()) errors.add(icu.lastError())
        }

        if (force || now - lastRules > RULES_PERIOD_MS) {
            rules = runCatching { refreshRules(force) }.getOrElse { e ->
                errors.add("правила: ${e.message ?: e.javaClass.simpleName}")
                false
            }
            if (rules) lastRules = now
            else if (notion.lastError().isNotBlank()) errors.add(notion.lastError())
        }

        lastError = errors.joinToString("; ")
        return Outcome(events, rules, lastError)
    }

    /**
     * Прочитать правила и вынуть из них числа. Текст страниц кладём целиком:
     * числа идут в светофор, а проза — в вопрос тренеру, где объясняет ПОЧЕМУ.
     */
    private suspend fun refreshRules(force: Boolean): Boolean {
        val page = notion.fetchBlockPage(force) ?: return false
        val parsed = claude.extractRules(page.text).getOrElse { e ->
            // Правила не разобрались — но текст страницы всё равно ценен:
            // он уезжает в контекст вопроса тренеру прозой, как есть.
            eventLog.add("план: правила не разобрались — ${e.message}")
            store.setRules(
                store.rulesFlow.value.copy(
                    blockTitle = page.title,
                    sourceText = page.text,
                    pageId = page.pageId,
                    fetchedAt = System.currentTimeMillis(),
                )
            )
            return true
        }
        runCatching { stats.recordAux(parsed.costUsd, parsed.tokensIn, parsed.tokensOut) }
        store.setRules(
            PlanStore.Rules(
                blockTitle = page.title,
                runHrCeiling = parsed.hrCeiling,
                greyZoneLow = parsed.greyLow,
                greyZoneHigh = parsed.greyHigh,
                cadenceMin = parsed.cadence,
                runsPerWeekMax = parsed.runsMax,
                hoursBetweenRuns = parsed.hoursBetween,
                rampNeedsPositiveTsb = parsed.rampNeedsPositiveTsb,
                testPrep = parsed.testPrep,
                cancelOrder = parsed.cancel,
                kneeGreen = parsed.kneeGreen,
                kneeYellow = parsed.kneeYellow,
                kneeRed = parsed.kneeRed,
                weekPlan = parsed.week,
                extra = parsed.extra,
                sourceText = page.text,
                fetchedAt = System.currentTimeMillis(),
                pageId = page.pageId,
            )
        )
        eventLog.add(
            "план: правила блока «${page.title}» — потолок ${parsed.hrCeiling}, " +
                "каденс ${parsed.cadence}, пробежек ${parsed.runsMax}"
        )
        return true
    }
}
