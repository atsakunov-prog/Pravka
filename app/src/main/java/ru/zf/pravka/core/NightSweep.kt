package ru.zf.pravka.core

import kotlinx.coroutines.delay
import ru.zf.pravka.PravkaApp

/**
 * Уборка на старте приложения (18.09.2026). Владелец: «убери эту тень — сейчас
 * зашёл, она снова запустилась и ест дальше деньги. И пускай следующая сборка
 * проверит, какие батчи есть, и убьёт все».
 *
 * Две вещи. (1) Прогоны тени, оставшиеся активными в сторе от прежних сборок,
 * закрываются: движка тени больше нет, а активный прогон без движка висел бы в
 * табло вечно. (2) Ревизия батчей у Anthropic: всё, что там ещё идёт и за чем
 * в приложении нет живого прогона разбора или правки промпта, отменяется —
 * отменённая с телефона тень, снятая сборка или умерший процесс не должны
 * докручивать деньги в чужой очереди. Тот же проход — по кнопке в «Разборах».
 */
object NightSweep {

    suspend fun onStart(app: PravkaApp) {
        // Ключ и сеть нужны не сразу: дать процессу подняться.
        delay(15_000)
        closeShadowRuns(app)
        if (app.settings.apiKey().isBlank()) return
        sweepBatches(app)
    }

    /** Активные прогоны тени → закрыты с причиной. */
    suspend fun closeShadowRuns(app: PravkaApp): Int {
        var n = 0
        for (run in app.nightReviewStore.all()) {
            if (!run.isShadow || !run.active) continue
            app.nightReviewStore.save(run.copy(stage = "failed", error = "тень снята из приложения 18.09", finishedAt = System.currentTimeMillis(), evidence = emptyMap(), progress = ""))
            n++
        }
        if (n > 0) app.nightLog.add("уборка: закрыто прогонов тени — $n")
        return n
    }

    /** Батчи у Anthropic: идущие без живого прогона в приложении — отменить. Возвращает строку для журнала и тоста. */
    suspend fun sweepBatches(app: PravkaApp): String {
        val live = app.nightReviewStore.all()
            .filter { it.active && !it.isShadow }
            .flatMap { listOf(it.analysisBatchId, it.checkBatchId, it.auditBatchId) }
            .filter { it.isNotBlank() }
            .toSet()
        val result = runCatching {
            val all = app.claudeBatches.list()
            val inFlight = all.filter { it.processing == "in_progress" }
            val cancelled = app.claudeBatches.cancelOrphans(live)
            "батчей у Anthropic ${all.size}, идущих ${inFlight.size}, за живыми прогонами ${inFlight.count { it.id in live }}, отменено ${cancelled.size}" +
                (if (cancelled.isNotEmpty()) ": ${cancelled.joinToString(", ")}" else "")
        }.getOrElse { "ревизия батчей не удалась: ${it.message ?: it.javaClass.simpleName}" }
        app.nightLog.add("уборка: $result")
        return result
    }
}
