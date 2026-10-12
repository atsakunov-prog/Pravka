package ru.zf.pravka.core

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import ru.zf.pravka.data.NightReviewStore.Run
import ru.zf.pravka.data.PromptVersions

/**
 * Сводка «Разборов» словами (12.10.2026). Владелец: «надо сделать вкладку
 * Разборы какой-то понятной. Наверху должно быть executive summary, а то ничего
 * не понятно» — и «всё должно идти автоматом, я там не нужен». Поэтому сверху
 * не табло автоматов и не чипы решений, а пять ответов: работает ли, что
 * Правка сама выучила, что заметила, что с промптом, когда дальше и сколько
 * это стоит. Считается из прогонов и настроек без сети и без Android — под
 * тестом (`ReviewDigestTest`).
 *
 * Здесь же — красная точка «есть новенькое» на «С» в «Сегодня» и на «Разборах»: закончился
 * прогон, который что-то применил, что-то заметил, обновил промпт или упал, —
 * а владелец его ещё не открывал.
 */
object ReviewDigest {

    data class Digest(
        /** Одна строка состояния: работает, идёт или сломалось — словами. */
        val status: String,
        /** Сломалось — строка красным. */
        val bad: Boolean,
        /** Что Правка сама поправила по последнему удачному разбору. */
        val learned: String,
        /** Сводка модели о периоде — абзац-другой. */
        val summary: String,
        /** Главные наблюдения о модели — до трёх. */
        val ideas: List<String>,
        val prompt: String,
        val next: String,
        val spent: String,
        /** Последний удачный разбор — его карточка идёт сразу под сводкой. */
        val lastReviewId: Long,
    )

    private val dayTime = SimpleDateFormat("EEE dd.MM HH:mm", Locale("ru"))
    private val dayOnly = SimpleDateFormat("EEE dd.MM", Locale("ru"))

    /** Прогон, о котором стоит сказать точкой: что-то применил, заметил, обновил промпт или упал. */
    fun newsworthy(run: Run): Boolean {
        if (run.active || run.finishedAt <= 0L) return false
        if (NightBoard.failed(run)) return !run.error.startsWith("отменено владельцем")
        return when {
            run.isTune -> run.summary.startsWith("ПРОМПТ ОБНОВЛЁН") || "возвращена" in run.summary
            run.isReview -> run.applied() > 0 || run.changes.any { it.kind == "note" }
            else -> false
        }
    }

    /** Есть ли закончившееся после последнего взгляда владельца. */
    fun hasNews(runs: List<Run>, seenAt: Long): Boolean =
        runs.any { it.finishedAt > seenAt && newsworthy(it) }

    /** Отметка «видел» при открытии вкладки — конец самого свежего закончившегося прогона. */
    fun seenMark(runs: List<Run>): Long = runs.maxOfOrNull { it.finishedAt } ?: 0L

    fun build(
        runs: List<Run>,
        activePrompt: PromptVersions.Version?,
        reviewOn: Boolean,
        tuneOn: Boolean,
        hour: Int,
        lastTickMs: Long,
        nowMs: Long,
    ): Digest {
        val reviews = runs.filter { it.isReview }.sortedByDescending { it.startedAt }
        val running = runs.firstOrNull { it.active && (it.isReview || it.isTune) }
        val lastFinished = reviews.firstOrNull { !it.active }
        val lastGood = reviews.firstOrNull { it.stage == "done" && !NightBoard.failed(it) }

        val (status, bad) = when {
            !reviewOn -> "Разбор выключен тумблером в настройках ниже — Правка сейчас сама не учится." to true
            lastTickMs > 0L && nowMs - lastTickMs > NightBoard.TICK_STALE_MS ->
                "Служба не тикала ${NightBoard.age(nowMs - lastTickMs)} — автоматы стоят. Проверь, включена ли служба доступности." to true
            running != null ->
                "Идёт ${if (running.isTune) "правка промпта" else "разбор"}: ${NightBoard.stageLabel(running.stage)}, с ${dayTime.format(Date(running.startedAt))}. Результат придёт сам." to false
            lastFinished != null && NightBoard.failed(lastFinished) ->
                "Последний разбор (${dayTime.format(Date(lastFinished.startedAt))}) не удался: ${short(lastFinished.error, 160)}. Следующий попробует сам." to true
            lastGood != null -> "Всё работает само. Последний разбор — ${dayTime.format(Date(lastGood.startedAt))}." to false
            else -> "Разборов ещё не было — первый пройдёт сам или кнопкой «Разобрать сейчас»." to false
        }

        val learned = lastGood?.let { learnedLine(it) }.orEmpty()
        val summary = lastGood?.let { modelSummary(it.summary) }.orEmpty()
        val ideas = lastGood?.changes?.filter { it.kind == "note" }?.take(3)?.map { short(it.why, 200) }.orEmpty()

        val tune = runs.filter { it.isTune && !it.active }.maxByOrNull { it.startedAt }
        val prompt = buildString {
            append(
                if (activePrompt == null) "Промпт чистки — заводской."
                else "Промпт чистки — версия от ${dayOnly.format(Date(activePrompt.at))}: ${short(activePrompt.note, 140)}"
            )
            if (tune != null) {
                append(" Последняя попытка улучшить (${dayOnly.format(Date(tune.startedAt))}): ")
                append(tuneOutcome(tune))
            }
            if (!tuneOn) append(" Правка промпта выключена тумблером.")
        }

        val next = buildString {
            append("Дальше: разбор недели ").append(NightBoard.nextWeekday(nowMs, hour, Calendar.FRIDAY))
            if (tuneOn) append(", правка промпта ").append(NightBoard.nextWeekday(nowMs, hour, Calendar.SATURDAY))
            append('.')
        }

        val month = runs.filter { it.startedAt > nowMs - 30 * 86_400_000L }
        val spent = "За 30 дней разборы и правка промпта стоили $" + "%.2f".format(Locale.US, month.sumOf { it.costUsd }) +
            " (прогонов ${month.size})."

        return Digest(status, bad, learned, summary, ideas, prompt, next, spent, lastGood?.id ?: 0L)
    }

    /** «Сам поправил словарь: 5 новых, 2 выключено…» — по последнему удачному разбору. */
    fun learnedLine(run: Run): String {
        val applied = run.changes.filter { it.status == "applied" }
        val add = applied.count { it.kind == "dict_add" }
        val off = applied.count { it.kind == "dict_disable" }
        val mode = applied.count { it.kind == "dict_mode" }
        val rules = applied.count { it.kind.startsWith("rule") }
        val skipped = run.changes.count { !it.isNote && it.status in setOf("skipped", "rejected", "proposed") }
        val parts = ArrayList<String>()
        if (add > 0) parts += "$add ${plural(add, "новое слово", "новых слова", "новых слов")}"
        if (off > 0) parts += "выключено $off"
        if (mode > 0) parts += "вид сменён у $mode"
        if (rules > 0) parts += "правил $rules"
        val head = "За ${period(run)} (${dayOnly.format(Date(run.fromMs))}–${dayOnly.format(Date(run.toMs))}) "
        val body = if (parts.isEmpty()) "словарь трогать не пришлось" else "Правка сама поправила словарь: " + parts.joinToString(", ")
        return head + body + (if (skipped > 0) "; не применено $skipped — причины в карточке ниже." else ".")
    }

    /** Сводка модели без первой строки-итога («Применено N…»): её считает экран. */
    fun modelSummary(summary: String): String =
        summary.split("\n\n").filterIndexed { i, p -> !(i == 0 && p.startsWith("Применено ")) }
            .joinToString("\n\n").trim()

    private fun tuneOutcome(run: Run): String {
        if (NightBoard.failed(run)) return "не удалась — ${short(run.error, 120)}."
        val first = run.summary.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        return when {
            first.startsWith("ПРОМПТ ОБНОВЛЁН") -> "принята, промпт обновлён."
            first.startsWith("Промпт не меняю: предложение") -> "новая версия хуже прежней — оставлен прежний."
            first.startsWith("Промпт не меняю") -> "менять нечего."
            first.isBlank() -> "готово."
            else -> short(first, 140)
        }
    }

    private fun period(run: Run): String {
        val days = ((run.toMs - run.fromMs + 43_200_000L) / 86_400_000L).coerceAtLeast(1)
        return when (days) {
            1L -> "сутки"
            7L -> "неделю"
            else -> "$days ${plural(days.toInt(), "день", "дня", "дней")}"
        }
    }

    fun plural(n: Int, one: String, few: String, many: String): String {
        val m10 = n % 10
        val m100 = n % 100
        return when {
            m10 == 1 && m100 != 11 -> one
            m10 in 2..4 && m100 !in 12..14 -> few
            else -> many
        }
    }

    private fun short(s: String, max: Int): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        return if (t.length > max) t.take(max).trimEnd() + "…" else t
    }
}
