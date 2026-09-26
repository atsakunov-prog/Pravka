package ru.zf.pravka.core

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import ru.zf.pravka.data.NightReviewStore.Run

/**
 * «Что работает, что нет» (17.09.2026; владелец: «давай всё это засунем в
 * единый лог, который будет показывать, что работает, что нет»). Одна строка на
 * каждый ночной автомат — разбор суток, разбор недели, правка промпта,
 * ручное сравнение моделей, эвал — плюс пульс службы: последний прогон, чем кончился или на какой
 * стадии стоит, когда следующий. Считается из прогонов и настроек, без сети;
 * тот же текст уходит в лог для Claude Code. Молчаливая механика читается как
 * поломка (правило 6) — здесь она обязана говорить словами.
 */
object NightBoard {

    /** ok · fail · running · off · none · stale */
    data class Line(val key: String, val title: String, val state: String, val text: String, val next: String, val runId: Long = 0L)

    data class EvalSummary(val at: Long, val avg: Double, val exact: Int, val total: Int)

    private val dayTime = SimpleDateFormat("EEE dd.MM HH:mm", Locale("ru"))
    private val day = SimpleDateFormat("dd.MM", Locale.US)

    /** Служба тикает раз в пять минут; двадцать без тика — автоматы стоят. */
    const val TICK_STALE_MS = 20 * 60_000L
    /** Идущий прогон без движения дольше двух часов — подозрительно, подсказываем кнопки. */
    const val RUN_STALE_MS = 2 * 3600_000L

    fun stageLabel(stage: String): String = when (stage) {
        "analysis" -> "первый проход"
        "check" -> "проверка"
        "audit" -> "согласование"
        ShadowPolicy.STAGE_CLEAN -> "вторая модель чистит"
        ShadowPolicy.STAGE_JUDGE -> "судья сравнивает"
        PromptTunePolicy.STAGE_PROPOSE -> "модель предлагает правку"
        PromptTunePolicy.STAGE_MEASURE -> "новый промпт перечищает диктовки недели"
        PromptTunePolicy.STAGE_JUDGE -> "судья сравнивает промпты"
        ComparePolicy.STAGE_CLEAN -> "три модели чистят"
        ComparePolicy.STAGE_JUDGE -> "судья сравнивает тройки"
        else -> stage
    }

    fun kindLabel(run: Run): String = when {
        run.isTune -> "промпт"
        run.isCompare -> "сравнение"
        run.isShadow -> "тень"
        run.kind == NightReviewPolicy.WEEKLY -> "неделя"
        else -> "сутки"
    }

    fun age(ms: Long): String {
        val min = (ms / 60_000L).coerceAtLeast(0)
        return if (min < 60) "$min мин" else "${min / 60} ч ${min % 60} мин"
    }

    fun build(
        runs: List<Run>,
        reviewOn: Boolean,
        tuneOn: Boolean,
        hour: Int,
        eval: EvalSummary?,
        lastTickMs: Long,
        nowMs: Long,
        /** Тумблер «сутки каждую ночь»: без него строка суток — «по кнопке», не «завтра в 03:00». */
        dailyOn: Boolean = true,
    ): List<Line> {
        val out = ArrayList<Line>()
        // Пульс службы — первым: если она не тикает, всё остальное стоит.
        out += when {
            lastTickMs <= 0L -> Line("tick", "Служба", "stale", "после запуска приложения ещё не тикала — автоматы ждут службу доступности", "")
            nowMs - lastTickMs > TICK_STALE_MS -> Line("tick", "Служба", "fail", "не тикала ${age(nowMs - lastTickMs)} — ночные автоматы стоят; проверь, включена ли служба доступности", "")
            else -> Line("tick", "Служба", "ok", "тикает, последний раз ${age(nowMs - lastTickMs)} назад", "")
        }
        val daily = runs.filter { it.isReview && it.kind == NightReviewPolicy.DAILY }.maxByOrNull { it.startedAt }
        val weekly = runs.filter { it.isReview && it.kind == NightReviewPolicy.WEEKLY }.maxByOrNull { it.startedAt }
        val tune = runs.filter { it.isTune }.maxByOrNull { it.startedAt }
        out += line("daily", "Разбор суток", daily, reviewOn, if (dailyOn) nextDaily(nowMs, hour) else "по кнопке «Сутки» (каждую ночь выключено)", nowMs)
        out += line("weekly", "Разбор недели", weekly, reviewOn, nextWeekday(nowMs, hour, Calendar.FRIDAY), nowMs)
        out += line("tune", "Правка промпта", tune, tuneOn, nextWeekday(nowMs, hour, Calendar.SATURDAY), nowMs)
        // Сравнение моделей автостарта не имеет — «следующий» всегда «по кнопке».
        val compare = runs.filter { it.isCompare }.maxByOrNull { it.startedAt }
        out += line("compare", "Сравнение моделей", compare, true, "по кнопке в Разборах", nowMs)
        out += if (eval == null) Line("eval", "Эвал золотого набора", "none", "ещё не прогонялся", "по кнопке в Логах")
        else Line("eval", "Эвал золотого набора", "ok", "${dayTime.format(Date(eval.at))} · средний ${"%.1f".format(Locale.US, eval.avg * 100)}%, точных ${eval.exact} из ${eval.total}", "по кнопке в Логах")
        return out
    }

    private fun line(key: String, title: String, run: Run?, enabled: Boolean, next: String, nowMs: Long): Line {
        val nextText = if (enabled) next else "выключен тумблером"
        if (run == null) return Line(key, title, if (enabled) "none" else "off", "ещё не было", nextText)
        val at = dayTime.format(Date(run.startedAt))
        return when {
            run.active -> {
                val stale = nowMs - run.lastPollAt > RUN_STALE_MS || nowMs - run.startedAt > 6 * 3600_000L
                Line(
                    key, title, if (stale) "stale" else "running",
                    "идёт: ${stageLabel(run.stage)} · уже ${age(nowMs - run.startedAt)}" +
                        (if (run.progress.isNotBlank()) " · ${run.progress}" else "") +
                        (if (stale) " — долго; «Проверить сейчас» или «Отменить»" else ""),
                    nextText, run.id,
                )
            }
            run.stage == "failed" -> Line(key, title, "fail", "$at · не удался: ${run.error.ifBlank { "причина не записана" }}", nextText, run.id)
            else -> Line(key, title, "ok", "$at · ${outcome(run)}", nextText, run.id)
        }
    }

    /** Итог завершённого прогона одной строкой. */
    fun outcome(run: Run): String {
        if (run.isShadow || run.isTune || run.isCompare) {
            val first = run.summary.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            return (if (first.length > 160) first.take(160) + "…" else first).ifBlank { "готово" } +
                (if (run.costUsd > 0) " · $" + "%.2f".format(Locale.US, run.costUsd) else "")
        }
        val parts = ArrayList<String>()
        parts += "применено ${run.applied()}"
        val waiting = run.changes.count { it.status == "proposed" || it.status == "reverted" }
        if (waiting > 0) parts += "предложено $waiting"
        val ideas = run.changes.count { it.kind == "note" }
        if (ideas > 0) parts += "идей $ideas"
        if (run.error.isNotBlank()) parts += "со сбоем: ${run.error.take(80)}"
        return parts.joinToString(", ") + (if (run.costUsd > 0) " · $" + "%.2f".format(Locale.US, run.costUsd) else "")
    }

    fun nextDaily(nowMs: Long, hour: Int): String {
        val now = Calendar.getInstance().apply { timeInMillis = nowMs }
        val today = now.get(Calendar.HOUR_OF_DAY) < hour
        return "${if (today) "сегодня" else "завтра"} в ${"%02d".format(hour)}:00"
    }

    fun nextWeekday(nowMs: Long, hour: Int, weekday: Int): String {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        // Ближайший такой день недели с часом запуска впереди.
        while (cal.get(Calendar.DAY_OF_WEEK) != weekday || (cal.timeInMillis == nowMs && cal.get(Calendar.HOUR_OF_DAY) >= hour)) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
        }
        val name = when (weekday) {
            Calendar.FRIDAY -> "пятницу"
            Calendar.SATURDAY -> "субботу"
            Calendar.SUNDAY -> "воскресенье"
            Calendar.MONDAY -> "понедельник"
            else -> "день ${cal.get(Calendar.DAY_OF_WEEK)}"
        }
        return "в ночь на $name ${day.format(cal.time)} в ${"%02d".format(hour)}:00"
    }

    /** Строка сбоя в журнале — подсветить красным и посчитать. */
    fun isFailureLine(line: String): Boolean {
        val l = line.lowercase()
        return "не удал" in l || "упал" in l || "не прошл" in l || "не запустил" in l || "ошибк" in l || "отменён" in l || "отменена" in l
    }

    fun render(lines: List<Line>): String = buildString {
        for (l in lines) {
            val mark = when (l.state) { "ok" -> "✓"; "fail" -> "✗"; "running" -> "●"; "stale" -> "!"; "off" -> "—"; else -> "·" }
            append("- ").append(mark).append(' ').append(l.title).append(": ").append(l.text)
            if (l.next.isNotBlank()) append(" · следующий ").append(l.next)
            append('\n')
        }
    }
}
