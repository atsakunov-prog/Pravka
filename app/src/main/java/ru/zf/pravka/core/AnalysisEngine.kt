package ru.zf.pravka.core

import java.util.Calendar
import ru.zf.pravka.data.AnalysisStore
import ru.zf.pravka.data.EventLog
import ru.zf.pravka.data.PromptStore
import ru.zf.pravka.data.Settings
import ru.zf.pravka.data.Stats
import ru.zf.pravka.data.dayKey
import ru.zf.pravka.provider.ClaudeProvider

/**
 * Итоги: ночной разбор жизненного лога.
 *
 * Три вещи, ради которых движок отдельный:
 * 1. ЧИСЛА СЧИТАЕТ КОД. [AnalysisBuilder] собирает агрегаты детерминированно,
 *    модель получает готовые цифры и занимается только интерпретацией.
 * 2. ОТПРАВКА БАТЧЕМ. Половина цены за то, что ответ не нужен сию секунду.
 *    Заявка уходит ночью, результат забирается опросом на тике службы.
 * 3. РАСПИСАНИЕ. Разбор дня — ночью за вчера, разбор недели — ночью в
 *    воскресенье. Повторно за тот же период не отправляем: дважды платить за
 *    один и тот же текст незачем.
 */
class AnalysisEngine(
    private val claude: ClaudeProvider,
    private val builder: AnalysisBuilder,
    private val store: AnalysisStore,
    private val prompts: PromptStore,
    private val settings: Settings,
    private val stats: Stats,
    private val eventLog: EventLog,
) {

    companion object {
        // Ночью: и день закрыт, и лента уже нормализована после полуночи.
        private const val NIGHT_HOUR = 4
        private const val MODEL_DAILY = Settings.MODEL_OPUS
        private const val MODEL_WEEKLY = Settings.MODEL_OPUS
        private const val MAX_TOKENS_DAILY = 4000
        private const val MAX_TOKENS_WEEKLY = 12000
        // Опрос батча — не чаще раза в пять минут (тик службы) и не дольше
        // суток: столько живёт заявка по договору.
        private const val PENDING_TTL_MS = 26 * 3_600_000L
    }

    /** Ручной запуск из вкладки: тот же путь, что у ночного. */
    suspend fun requestDaily(date: String = builder.yesterday()): Result<String> =
        submit("daily", date, date, MODEL_DAILY, MAX_TOKENS_DAILY)

    suspend fun requestWeekly(): Result<String> {
        val (from, to) = builder.weekAgo(7)
        return submit("weekly", from, to, MODEL_WEEKLY, MAX_TOKENS_WEEKLY)
    }

    suspend fun requestDeep(days: Int = 30): Result<String> {
        val (from, to) = builder.weekAgo(days)
        return submit("deep", from, to, MODEL_WEEKLY, MAX_TOKENS_WEEKLY)
    }

    private suspend fun submit(
        mode: String,
        from: String,
        to: String,
        model: String,
        maxTokens: Int,
    ): Result<String> {
        store.load()
        if (store.reportsFlow.value.any { it.mode == mode && it.from == from && it.to == to && (it.pending || it.ready) }) {
            return Result.failure(IllegalStateException("Разбор за этот период уже есть"))
        }
        val built = runCatching {
            builder.build(mode, from, to, context = settings.analysisContext())
        }.getOrElse {
            eventLog.add("итоги: не собрались данные — ${it.message}")
            return Result.failure(it)
        }
        if (built.text.length < 400) {
            return Result.failure(IllegalStateException("За этот период почти нет данных"))
        }
        val system = prompts.effective(PromptStore.PromptId.ANALYSIS)
        val outcome = claude.submitBatch(system, built.text, model, maxTokens)
        return outcome.fold(
            onSuccess = { batchId ->
                store.addPending(mode, from, to, batchId, model, built.hash, built.chars)
                eventLog.add(
                    "итоги: заявка $mode $from — $to ушла батчем " +
                        "(${built.chars / 1000} тыс. знаков, батч $batchId)"
                )
                Result.success(batchId)
            },
            onFailure = { e ->
                eventLog.add("итоги: заявка не ушла — ${e.message}")
                Result.failure(e)
            },
        )
    }

    /**
     * Тик службы: сначала забрать готовое, потом — если пора — отправить
     * ночную заявку. Возвращает текст готового разбора, если он приехал
     * прямо сейчас: служба покажет по нему уведомление.
     */
    suspend fun tick(): AnalysisStore.Report? {
        store.load()
        val done = collect()
        if (done == null) runCatching { scheduleNightly() }
        return done
    }

    /** Опрос батчей: готово — сохраняем, просрочено — помечаем. */
    private suspend fun collect(): AnalysisStore.Report? {
        val pending = store.pending()
        if (pending.isEmpty()) return null
        for (report in pending) {
            if (System.currentTimeMillis() - report.createdAt > PENDING_TTL_MS) {
                store.fail(report.id, "батч не ответил за сутки")
                eventLog.add("итоги: батч ${report.batchId} просрочен")
                continue
            }
            val answer = claude.batchAnswer(report.batchId, report.model).getOrElse { e ->
                eventLog.add("итоги: опрос батча не вышел — ${e.message}")
                null
            } ?: continue
            if (answer.error.isNotBlank()) {
                store.fail(report.id, answer.error)
                eventLog.add("итоги: разбор не получился — ${answer.error}")
                continue
            }
            if (answer.text.isBlank()) {
                store.fail(report.id, "пустой ответ")
                continue
            }
            runCatching { stats.recordAux(answer.costUsd, answer.tokensIn, answer.tokensOut) }
            val saved = store.complete(
                report.id, answer.text, answer.costUsd, answer.tokensIn, answer.tokensOut,
            )
            eventLog.add(
                "итоги: готов разбор ${report.mode} ${report.from} — ${report.to}, " +
                    "${answer.text.length} зн., " +
                    String.format(java.util.Locale.US, "%.3f", answer.costUsd) + " USD (батч)"
            )
            return saved
        }
        return null
    }

    /**
     * Ночью после [NIGHT_HOUR]: разбор вчерашнего дня, а по воскресеньям ещё
     * и недельный. Ключ идемпотентности — период разбора: если за вчера уже
     * есть запись (готовая или в очереди), второй раз не отправляем.
     */
    private suspend fun scheduleNightly() {
        if (!settings.analysisNightly()) return
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.HOUR_OF_DAY) < NIGHT_HOUR) return
        val yesterday = builder.yesterday()
        val haveDaily = store.reportsFlow.value.any {
            it.mode == "daily" && it.from == yesterday && (it.pending || it.ready)
        }
        if (!haveDaily) {
            requestDaily(yesterday)
            return
        }
        // Воскресенье: неделя целиком. Отдельным тиком, чтобы две тяжёлые
        // заявки не уходили одной пачкой.
        if (cal.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) return
        val (from, to) = builder.weekAgo(7)
        val haveWeekly = store.reportsFlow.value.any {
            it.mode == "weekly" && it.from == from && it.to == to && (it.pending || it.ready)
        }
        if (!haveWeekly) requestWeekly()
    }

    /** Сегодняшняя дата — для подписи «за какой день считаем». */
    fun today(): String = dayKey(System.currentTimeMillis())
}
