package ru.zf.pravka.core

import ru.zf.pravka.data.PlanStore
import ru.zf.pravka.data.SportStore
import ru.zf.pravka.data.StrengthStore
import ru.zf.pravka.data.dayBefore
import ru.zf.pravka.data.dayKey
import java.util.Locale

// Утренний светофор: не дашборд, а одно решение.
//
// Три графика — это отчёт, а владельцу нужен ответ: «по плану», «срежь
// интенсивность» или «сегодня отдых, потому что…». HRV, сон, пульс покоя и
// форма никуда не деваются, но уходят в три числа мелким шрифтом под фразой.
//
// Считается ЗДЕСЬ, на телефоне, и не стоит ни копейки. И это не экономия ради
// экономии: у владельца правила ЖЁСТКИЕ и записаны цифрами — потолок пульса,
// каденс, серая зона, три пробежки в неделю, 48 часов между ними, рамп-тест
// только на плюсовом TSB, «первым выпадает бег, силовые не двигаются никогда».
// Такое проверяется арифметикой честнее, чем суждением, и работает в самолёте.
//
// Правила приезжают из страницы блока в Notion (владелец их там правит), и
// если какого-то числа в тексте нет — про него светофор МОЛЧИТ. Выдуманный
// порог хуже отсутствующего: им будут светить каждое утро.
class TrafficLight(
    private val sport: SportStore,
    private val plan: PlanStore,
    private val strength: StrengthStore,
) {

    companion object {
        private const val BASELINE_DAYS = 14
    }

    /** Куда светит: −2 отдых · −1 полегче · 0 по плану · +1 можно грузиться. */
    data class Verdict(
        val headline: String,
        val because: String,
        val tone: Int,
        /** Три числа мелким шрифтом. Больше трёх — это уже дашборд. */
        val numbers: List<Number>,
        /** Нарушения его собственных правил на сегодня, если они есть. */
        val warnings: List<String>,
        val planLine: String,
    )

    data class Number(val label: String, val value: String, val hint: String, val tone: Int)

    fun today(date: String = dayKey(System.currentTimeMillis())): Verdict {
        val health = sport.healthFlow.value.firstOrNull()
        // Свежесть — часть вердикта. На даче выгрузка молчит сутками, и
        // светофор, который светит позавчерашним HRV как сегодняшним, врёт
        // уверенно — хуже, чем молчал бы.
        val stale = health != null && health.date < date
        val rules = plan.rulesFlow.value
        val planned = plan.dayOf(date)
        val main = plan.mainOf(date)

        val numbers = mutableListOf<Number>()
        var score = 0
        var counted = 0
        val reasons = mutableListOf<String>()

        // HRV против своей базы БЕЗ сегодняшнего дня: иначе сегодняшнее
        // значение тянет базу к себе и гасит собственный сигнал.
        val hrvBase = sport.average(BASELINE_DAYS, skipDays = 1) { it.hrv.toDouble() }
        val hrv = health?.hrv ?: 0
        if (hrv > 0) {
            val tone = if (hrvBase > 0) {
                val delta = (hrv - hrvBase) / hrvBase
                when {
                    delta <= -0.20 -> -2
                    delta <= -0.10 -> -1
                    delta >= 0.10 -> 1
                    else -> 0
                }
            } else 0
            if (hrvBase > 0) {
                score += tone
                counted++
                if (tone <= -1) {
                    reasons.add("HRV $hrv против ${fmt0(hrvBase)} за две недели")
                }
            }
            numbers.add(
                Number(
                    "HRV", "$hrv",
                    if (hrvBase > 0) "база ${fmt0(hrvBase)}" else "базы пока нет",
                    tone,
                )
            )
        }

        val sleep = health?.sleepHours ?: 0.0
        if (sleep > 0) {
            val tone = when {
                sleep < 5.5 -> -2
                sleep < 6.5 -> -1
                sleep >= 7.5 -> 1
                else -> 0
            }
            score += tone
            counted++
            // Его же правило контроля: «Сон 7+ — мышцы растут ночью».
            if (tone <= -1) reasons.add("сон ${fmt1(sleep)} ч при цели 7+")
            val week = sport.average(7, skipDays = 0) { it.sleepHours }
            numbers.add(
                Number(
                    "Сон", fmt1(sleep) + " ч",
                    if (week > 0) "за неделю ${fmt1(week)}" else "",
                    tone,
                )
            )
        }

        val ctl = health?.ctl ?: 0.0
        val atl = health?.atl ?: 0.0
        val tsb = ctl - atl
        if (ctl > 0 || atl > 0) {
            val tone = when {
                tsb <= -15 -> -2
                tsb <= -7 -> -1
                tsb >= 5 -> 1
                else -> 0
            }
            score += tone
            counted++
            if (tone <= -1) reasons.add("форма ${signed(Math.round(tsb).toInt())}")
            numbers.add(
                Number(
                    "Форма", signed(Math.round(tsb).toInt()),
                    "тренированность ${fmt1(ctl)}",
                    tone,
                )
            )
        }

        // Пульс покоя в числа не идёт — их и так три, — но в решении участвует:
        // подъём на пять ударов это болезнь или крепкий недосып.
        val rhrBase = sport.average(BASELINE_DAYS, skipDays = 1) { it.restingHr.toDouble() }
        val rhr = health?.restingHr ?: 0
        var sick = false
        if (rhr > 0 && rhrBase > 0) {
            val delta = rhr - rhrBase
            val tone = when {
                delta >= 5 -> -2
                delta >= 2 -> -1
                delta <= -2 -> 1
                else -> 0
            }
            score += tone
            counted++
            if (delta >= 5) {
                sick = true
                reasons.add("пульс покоя $rhr против ${fmt0(rhrBase)}")
            } else if (tone == -1) {
                reasons.add("пульс покоя выше обычного")
            }
        }

        val warnings = buildList {
            if (stale) add("Часы молчат: данные за ${health!!.date}, не за сегодня")
            addAll(ruleWarnings(date, rules, planned, tsb))
        }
        val knee = strength.gtgOn(date)?.knee.orEmpty()

        // Колено — отдельный светофор, и он старше общего: красный отменяет
        // всё независимо от HRV.
        if (knee.startsWith("красн")) {
            return Verdict(
                headline = "Стоп и к врачу",
                because = rules.kneeRed.ifBlank {
                    "Красный светофор колена: отёк, блокировка или острая боль — тренировки отменяются."
                },
                tone = -2,
                numbers = numbers.take(3),
                warnings = warnings,
                planLine = planLine(main),
            )
        }

        if (counted == 0) {
            return Verdict(
                headline = "Данных нет",
                because = "Часы ещё не прислали ни сна, ни HRV. Проверь athlete id и ключ " +
                    "intervals.icu — «Настройки» → «Засечка».",
                tone = 0,
                numbers = numbers,
                warnings = warnings,
                planLine = planLine(main),
            )
        }

        val average = score.toDouble() / counted
        val tone = when {
            sick || average <= -1.2 -> -2
            average <= -0.4 -> -1
            average >= 0.5 -> 1
            else -> 0
        }
        val headline = when {
            sick -> "Сегодня отдых"
            tone == -2 -> "Сегодня отдых"
            tone == -1 -> "Срежь интенсивность"
            tone == 1 -> "По плану, и можно тяжёлое"
            else -> "По плану"
        }
        val because = buildString {
            if (sick) {
                append("Похоже на болезнь или крепкий недосып: ")
                append(reasons.joinToString(", "))
                append(". Тренировку лучше отложить.")
                return@buildString
            }
            when (tone) {
                -2 -> {
                    append("Тело просит покоя — ")
                    append(reasons.joinToString(", "))
                    append(". Спокойная первая зона или выходной.")
                }
                -1 -> {
                    append(reasons.joinToString(", "))
                    append(". Объём можно, интенсивность лучше отложить")
                    if (knee.startsWith("жёлт") || knee.startsWith("желт")) {
                        append(" — и колено жёлтое, значит режем бег: он младший")
                    }
                    append('.')
                }
                1 -> append("Восстановление хорошее — подходящий день для тяжёлого.")
                else -> append("Ничего не выбивается: работай по плану.")
            }
        }
        return Verdict(
            headline = headline,
            because = because,
            tone = tone,
            numbers = numbers.take(3),
            warnings = warnings,
            planLine = planLine(main),
        )
    }

    /**
     * Нарушения его собственных правил на сегодня. Каждая проверка молчит,
     * если правила про неё в блоке нет: приложение не знает лучше владельца,
     * сколько ему бегать.
     */
    private fun ruleWarnings(
        date: String,
        rules: PlanStore.Rules,
        planned: List<PlanStore.PlanDay>,
        tsb: Double,
    ): List<String> {
        val out = mutableListOf<String>()
        val runPlanned = planned.any { it.type.equals("Run", ignoreCase = true) }
        val rampPlanned = planned.any {
            val n = it.name.lowercase()
            n.contains("рамп") || n.contains("ramp") || n.contains("тест")
        }

        // Рамп-тест только свежим — его правило, и оно дословно про TSB.
        if (rampPlanned && rules.rampNeedsPositiveTsb && tsb < 0) {
            out.add(
                "Тест по плану, а форма ${signed(Math.round(tsb).toInt())}: " +
                    "по твоему правилу тест только на плюсовом TSB"
            )
        }

        if (runPlanned) {
            // Три пробежки в неделю — лимит соединительной ткани, не кардио.
            if (rules.runsPerWeekMax > 0) {
                val runs = runsInWindow(date, 7)
                if (runs >= rules.runsPerWeekMax) {
                    out.add(
                        "Пробежек за семь дней уже $runs при лимите ${rules.runsPerWeekMax}"
                    )
                }
            }
            // 48 часов между любыми пробежками.
            if (rules.hoursBetweenRuns > 0) {
                val last = lastRun()
                if (last != null) {
                    val hours = (System.currentTimeMillis() - last) / 3_600_000L
                    if (hours < rules.hoursBetweenRuns) {
                        out.add(
                            "С прошлой пробежки $hours ч, а надо ${rules.hoursBetweenRuns}"
                        )
                    }
                }
            }
            // Две быстрые подряд — тот самый паттерн, который вернул колено.
            if (rules.runHrCeiling > 0) {
                val fast = sport.workoutsFlow.value
                    .filter { it.type.equals("Run", ignoreCase = true) && it.avgHr > 0 }
                    .sortedByDescending { it.start }
                    .take(2)
                if (fast.size == 2 && fast.all { it.avgHr > rules.runHrCeiling }) {
                    out.add(
                        "Две последние пробежки выше потолка (${fast[0].avgHr} и ${fast[1].avgHr} " +
                            "при ${rules.runHrCeiling}) — сегодня строго легко"
                    )
                }
            }
        }
        return out
    }

    private fun runsInWindow(date: String, days: Int): Int {
        var cursor = date
        val dates = mutableSetOf<String>()
        repeat(days) {
            dates.add(cursor)
            cursor = dayBefore(cursor)
        }
        return sport.workoutsFlow.value.count {
            it.type.equals("Run", ignoreCase = true) && dayKey(it.start) in dates
        }
    }

    private fun lastRun(): Long? = sport.workoutsFlow.value
        .filter { it.type.equals("Run", ignoreCase = true) }
        .maxOfOrNull { it.start }

    /** Что сегодня по плану — одной строкой с ключевыми параметрами. */
    private fun planLine(main: PlanStore.PlanDay?): String {
        if (main == null) return ""
        val rules = plan.rulesFlow.value
        val bits = mutableListOf<String>()
        if (main.minutes > 0) bits.add("${main.minutes} мин")
        if (main.load > 0) bits.add("load ${main.load}")
        if (main.type.equals("Run", ignoreCase = true)) {
            if (rules.runHrCeiling > 0) bits.add("потолок ${rules.runHrCeiling}")
            if (rules.cadenceMin > 0) bits.add("каденс ${rules.cadenceMin}+")
            if (rules.greyZoneLow > 0 && rules.greyZoneHigh > 0) {
                bits.add("серая зона ${rules.greyZoneLow}–${rules.greyZoneHigh}")
            }
        }
        return if (bits.isEmpty()) main.name else main.name + " · " + bits.joinToString(" · ")
    }

    private fun fmt0(v: Double) = String.format(Locale.US, "%.0f", v)
    private fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun signed(v: Int) = if (v > 0) "+$v" else "$v"
}
