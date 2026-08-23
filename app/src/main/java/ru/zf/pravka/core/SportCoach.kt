package ru.zf.pravka.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ru.zf.pravka.data.EventLog
import ru.zf.pravka.data.FoodStore
import ru.zf.pravka.data.PlanStore
import ru.zf.pravka.data.Settings
import ru.zf.pravka.data.SportStore
import ru.zf.pravka.data.Stats
import ru.zf.pravka.data.ZasechkaStore
import ru.zf.pravka.data.dayKey
import ru.zf.pravka.provider.ClaudeProvider

// Спорт: разбор своих тренировок и ответ на вопрос о них.
//
// Две половины, и они нарочно разного веса.
//
// ГОТОВНОСТЬ считается ЗДЕСЬ, на телефоне, и не стоит ни копейки: HRV против
// своей же двухнедельной базы, пульс покоя против неё же, сон, форма (TSB).
// Такую сводку владелец смотрит каждое утро, и платить за неё моделью было бы
// глупо - тем более что арифметика тут честнее суждения.
//
// ВОПРОС уезжает Опусу. Здесь наоборот: цифры уже посчитаны, трудное - сказать
// «сегодня не грузись, потому что третий день HRV ниже базы, а сон пятый день
// по шесть часов». Это суждение, и оно своих денег стоит.
class SportCoach(
    private val claude: ClaudeProvider,
    private val store: SportStore,
    private val foodStore: FoodStore,
    private val planStore: PlanStore,
    private val zasechkaStore: ZasechkaStore,
    private val settings: Settings,
    private val stats: Stats,
    private val eventLog: EventLog,
) {

    companion object {
        // База, с которой сравниваем сегодняшние HRV и пульс покоя. Две недели -
        // компромисс: короче окно ловит шум, длиннее не замечает, что человек
        // за месяц изменился.
        private const val BASELINE_DAYS = 14
        // Сколько дней тренировок уезжает в промпт. Три недели - это блок:
        // видно и последнюю неделю, и с чем её сравнивать.
        private const val CONTEXT_WORKOUT_DAYS = 21
        private const val CONTEXT_HEALTH_DAYS = 14
        // Правила прозой: восемь тысяч знаков это две страницы Notion целиком.
        // Дороже пары центов на вопрос, и это ровно тот контекст, без которого
        // совет расходится с его собственными записанными правилами.
        private const val CONTEXT_RULES_CHARS = 14_000
        private const val CONTEXT_FOOD_DAYS = 7

        /** Как виды спорта из intervals.icu называются по-русски. */
        fun sportName(type: String): String = when (type) {
            "Run" -> "Бег"
            "TrailRun" -> "Трейл"
            "VirtualRun" -> "Бег на дорожке"
            "Ride", "VirtualRide" -> "Вело"
            "GravelRide" -> "Вело (гравий)"
            "MountainBikeRide" -> "Вело (МТБ)"
            "Walk" -> "Ходьба"
            "Hike" -> "Поход"
            "Swim" -> "Плавание"
            "WeightTraining" -> "Силовая"
            "Workout" -> "Тренировка"
            "Crossfit" -> "Кроссфит"
            "HIIT" -> "Интервальная"
            "Yoga" -> "Йога"
            "Rowing" -> "Гребля"
            "NordicSki", "BackcountrySki", "AlpineSki" -> "Лыжи"
            "Elliptical" -> "Эллипс"
            "" -> "Тренировка"
            else -> type
        }

        /**
         * Фокус-блок для вопроса «как делать X»: полная карточка движения из
         * справочника плюс сегодняшняя строка плана. Правила недели тренер и
         * так увидит ниже — они уезжают в контекст целиком.
         */
        fun exerciseFocus(
            exercise: ru.zf.pravka.data.ExerciseBook.Exercise?,
            planLine: String,
        ): String {
            if (exercise == null && planLine.isBlank()) return ""
            return buildString {
                append("ВОПРОС ПРО КОНКРЕТНОЕ УПРАЖНЕНИЕ\n")
                if (planLine.isNotBlank()) append("Сегодня в плане: ").append(planLine).append('\n')
                if (exercise != null) {
                    append("Карточка из его справочника (собран из его Notion):\n")
                    append("Название: ").append(exercise.name).append('\n')
                    if (exercise.scheme.isNotBlank()) append("Схема: ").append(exercise.scheme).append('\n')
                    if (exercise.gear.isNotEmpty()) {
                        append("Снаряд: ").append(exercise.gear.joinToString(", ")).append('\n')
                    }
                    if (exercise.how.isNotBlank()) append("Как делать: ").append(exercise.how).append('\n')
                    if (exercise.mistakes.isNotBlank()) {
                        append("Главные ошибки: ").append(exercise.mistakes).append('\n')
                    }
                    if (exercise.progression.isNotBlank()) {
                        append("Прогрессия: ").append(exercise.progression).append('\n')
                    }
                }
                append(
                    "Отвечай про технику: по шагам, что чувствовать, чего не делать, " +
                        "как понять, что получается — его правилами недели (они ниже в данных)."
                )
            }
        }
    }

    // ---- Готовность: считается на телефоне, без токенов ----

    /** Одна строка сводки: что за показатель, что показывает и как это читать. */
    data class Signal(
        val label: String,
        val value: String,
        val hint: String,
        /** −2 плохо · −1 хуже обычного · 0 норма · +1 хорошо (для цвета). */
        val tone: Int,
    )

    data class Readiness(
        val verdict: String,
        val detail: String,
        val tone: Int,
        val signals: List<Signal>,
    )

    /**
     * Утренняя сводка. Считает по тем дням, где данные есть: часы могут не
     * записать HRV, и это не повод рисовать ноль - показатель просто выпадает
     * из расчёта, а в подсказке видно, что его нет.
     */
    fun readiness(): Readiness {
        val today = store.healthFlow.value.firstOrNull()
        val signals = mutableListOf<Signal>()
        var score = 0
        var counted = 0

        val hrvBase = store.average(BASELINE_DAYS, skipDays = 1) { it.hrv.toDouble() }
        val hrv = today?.hrv ?: 0
        if (hrv > 0 && hrvBase > 0) {
            // Десять процентов - обычный дневной разброс HRV, за него не ругаем.
            val delta = (hrv - hrvBase) / hrvBase
            val tone = when {
                delta <= -0.20 -> -2
                delta <= -0.10 -> -1
                delta >= 0.10 -> 1
                else -> 0
            }
            score += tone
            counted++
            signals.add(
                Signal(
                    "HRV", "$hrv",
                    "база ${fmt0(hrvBase)} за $BASELINE_DAYS дн. · ${signed(percent(delta))}%",
                    tone,
                )
            )
        } else if (hrv > 0) {
            signals.add(Signal("HRV", "$hrv", "базы ещё нет — мало дней", 0))
        }

        val rhrBase = store.average(BASELINE_DAYS, skipDays = 1) { it.restingHr.toDouble() }
        val rhr = today?.restingHr ?: 0
        if (rhr > 0 && rhrBase > 0) {
            // У пульса покоя знак обратный: выше базы - хуже.
            val delta = rhr - rhrBase
            val tone = when {
                delta >= 5 -> -2
                delta >= 2 -> -1
                delta <= -2 -> 1
                else -> 0
            }
            score += tone
            counted++
            signals.add(
                Signal(
                    "Пульс покоя", "$rhr",
                    "база ${fmt0(rhrBase)} · ${signed(delta.toInt())}",
                    tone,
                )
            )
        }

        val sleep = today?.sleepHours ?: 0.0
        val sleepScore = today?.sleepScore ?: 0
        if (sleep > 0) {
            val tone = when {
                sleep < 5.5 -> -2
                sleep < 6.5 -> -1
                sleep >= 7.5 -> 1
                else -> 0
            }
            score += tone
            counted++
            val score7 = store.average(7) { it.sleepHours }
            signals.add(
                Signal(
                    "Сон", fmt1(sleep) + " ч",
                    (if (sleepScore > 0) "счёт $sleepScore · " else "") +
                        "в среднем за неделю " + fmt1(score7) + " ч",
                    tone,
                )
            )
        }

        val ctl = today?.ctl ?: 0.0
        val atl = today?.atl ?: 0.0
        if (ctl > 0 || atl > 0) {
            val tsb = ctl - atl
            // TSB: минус - накопленная усталость, плюс - свежесть. За −10
            // начинается настоящая нагрузка, за +15 - уже растренированность.
            val tone = when {
                tsb <= -15 -> -2
                tsb <= -7 -> -1
                tsb >= 5 -> 1
                else -> 0
            }
            score += tone
            counted++
            signals.add(
                Signal(
                    "Форма", signed(Math.round(tsb).toInt()),
                    "тренированность ${fmt1(ctl)} · усталость ${fmt1(atl)}",
                    tone,
                )
            )
        }

        val loadWeek = store.weekLoad()
        val loadPrev = store.weekLoad(1)
        if (loadWeek > 0 || loadPrev > 0) {
            signals.add(
                Signal(
                    "Нагрузка за неделю", "$loadWeek",
                    "неделей раньше $loadPrev" + when {
                        loadPrev <= 0 -> ""
                        loadWeek > loadPrev * 1.4 -> " · растёт резко"
                        loadWeek < loadPrev * 0.6 -> " · заметно меньше"
                        else -> ""
                    },
                    0,
                )
            )
        }

        if (counted == 0) {
            return Readiness(
                "Данных нет",
                "Часы ещё не прислали ни сна, ни HRV. Проверь athlete id и ключ intervals.icu в настройках Засечки.",
                0,
                signals,
            )
        }
        // Средний тон по учтённым показателям, а не сумма: иначе день без HRV
        // автоматически выглядел бы лучше дня с ним.
        val average = score.toDouble() / counted
        val tone = when {
            average <= -1.2 -> -2
            average <= -0.4 -> -1
            average >= 0.5 -> 1
            else -> 0
        }
        val verdict = when (tone) {
            -2 -> "Сегодня не грузиться"
            -1 -> "Полегче, чем обычно"
            1 -> "Можно грузиться"
            else -> "Обычный день"
        }
        val detail = when (tone) {
            -2 -> "Тело просит покоя: спокойная первая зона или выходной."
            -1 -> "Объём можно, интенсивность лучше отложить."
            1 -> "Восстановление хорошее — подходящий день для тяжёлого."
            else -> "Ничего не выбивается: работай по плану."
        }
        return Readiness(verdict, detail, tone, signals)
    }

    // ---- Вопрос: уезжает Опусу вместе со всем контекстом ----

    data class Answer(val text: String, val costUsd: Double, val error: String)

    /**
     * Вопрос о тренировках. Контекст собирается здесь и целиком: пороги, форма,
     * две недели здоровья, три недели тренировок, неделя еды и чем владелец
     * вообще был занят. Модель ничего не доспрашивает - у неё либо есть цифра,
     * либо честно нет.
     *
     * Ответ стримится в [onDelta]: первые слова появляются на экране сразу.
     */
    suspend fun ask(
        question: String,
        /**
         * Про что именно спрашивают — карточка упражнения кладёт сюда свой
         * справочник (техника, ошибки, прогрессия) и строку плана дня. Стоит
         * ПЕРВЫМ в контексте: «как правильно вис» должен отвечаться его же
         * техникой и его спина-протоколом, а не общими словами из интернета.
         */
        focus: String = "",
        onDelta: ((String) -> Unit)? = null,
    ): Answer {
        store.load()
        runCatching { foodStore.load() }
        // План и правила могут быть ещё не прочитаны с диска: вопрос задаётся и
        // с плашки кнопки «Т», где вкладка «Спорт» ни разу не открывалась.
        runCatching { planStore.load() }
        val targets = runCatching { settings.foodTargets() }.getOrNull()
        val context = (if (focus.isBlank()) "" else focus.trim() + "\n\n") +
            runCatching { buildContext(targets) }
                .getOrElse { "Контекст собрать не удалось." }
        val result = claude.coach(question, context, onDelta)
        val answer = result.getOrElse { e ->
            eventLog.add("спорт: вопрос не вышел — ${e.message}")
            val text = e.message ?: "Не получилось спросить"
            runCatching { store.addTalk(question, "", 0.0, text) }
            return Answer("", 0.0, text)
        }
        runCatching { stats.recordAux(answer.costUsd, answer.tokensIn, answer.tokensOut) }
        runCatching { store.addTalk(question, answer.text, answer.costUsd) }
        eventLog.add(
            "спорт: вопрос «${question.take(50)}» — ответ ${answer.text.length} зн., " +
                String.format(Locale.US, "%.3f", answer.costUsd) + " USD"
        )
        return Answer(answer.text, answer.costUsd, "")
    }

    /**
     * Всё, что модель должна знать. Собирается ровно один раз на вопрос и в
     * человеческом виде, а не JSON-ом: так дешевле по токенам и модель реже
     * путает поля.
     */
    /**
     * Лёгкий вопрос тренеру-консультанту: без телеметрии, только карточка
     * движения и правила недели. Ответ тоже ложится в «прошлые разборы».
     */
    suspend fun askTrainer(
        question: String,
        focus: String,
        onDelta: ((String) -> Unit)? = null,
    ): Answer {
        runCatching { planStore.load() }
        val result = claude.trainer(question, focus, weekRulesSnippet(), onDelta)
        val answer = result.getOrElse { e ->
            eventLog.add("тренер: вопрос не вышел — ${e.message}")
            return Answer("", 0.0, e.message ?: "Не получилось спросить")
        }
        runCatching { stats.recordAux(answer.costUsd, answer.tokensIn, answer.tokensOut) }
        runCatching { store.addTalk("[тренер] " + question, answer.text, answer.costUsd) }
        eventLog.add(
            "тренер: «${question.take(50)}» — ${answer.text.length} зн., " +
                String.format(Locale.US, "%.3f", answer.costUsd) + " USD"
        )
        return Answer(answer.text, answer.costUsd, "")
    }

    /**
     * Срез правил для лёгкого тренера: раздел текущей недели из Notion
     * (спина-протокол с запретами и заменами), а если его нет — ключевые числа
     * одной строкой. Полная телеметрия сюда нарочно не едет.
     */
    private fun weekRulesSnippet(): String {
        val rules = planStore.rulesFlow.value
        val source = rules.sourceText
        val marker = "# Текущая неделя"
        val week = if (source.contains(marker)) {
            source.substring(source.indexOf(marker)).take(3500)
        } else ""
        if (week.isNotBlank()) return "Его правила недели:\n" + week
        if (!rules.known) return ""
        return buildString {
            append("Его правила: ")
            if (rules.runHrCeiling > 0) append("бег до ${rules.runHrCeiling}; ")
            if (rules.cadenceMin > 0) append("каденс ${rules.cadenceMin}+; ")
            if (rules.cancelOrder.isNotBlank()) append(rules.cancelOrder)
        }
    }

    fun buildContext(targets: Settings.Targets? = null): String = buildString {
        val profile = store.profileFlow.value
        if (profile.known) {
            append("ПОРОГИ И ЗОНЫ\n")
            if (profile.weightKg > 0) append("Вес ${fmt1(profile.weightKg)} кг. ")
            if (profile.restingHr > 0) append("Пульс покоя в настройках ${profile.restingHr}. ")
            append("\n")
            if (profile.runFtp > 0 || profile.runThresholdPaceSecPerKm > 0) {
                append("Бег: ")
                if (profile.runThresholdPaceSecPerKm > 0) {
                    append("порог ${pace(profile.runThresholdPaceSecPerKm)}/км, ")
                }
                if (profile.runFtp > 0) append("FTP ${profile.runFtp} Вт, ")
                if (profile.runLthr > 0) append("ЛПАНО ${profile.runLthr}, ")
                if (profile.runMaxHr > 0) append("макс. пульс ${profile.runMaxHr}")
                append("\n")
            }
            if (profile.rideFtp > 0) {
                append("Вело: FTP ${profile.rideFtp} Вт")
                if (profile.rideLthr > 0) append(", ЛПАНО ${profile.rideLthr}")
                append("\n")
            }
            if (profile.hrZonesRun.isNotEmpty()) {
                append("Пульсовые зоны бега (верхние границы): ")
                append(profile.hrZonesRun.joinToString(", "))
                append("\n")
            }
            append("\n")
        }

        val today = store.healthFlow.value.firstOrNull()
        if (today != null) {
            append("СЕГОДНЯ (${today.date})\n")
            append("Тренированность CTL ${fmt1(today.ctl)}, усталость ATL ${fmt1(today.atl)}, ")
            append("форма TSB ${signed(Math.round(today.tsb).toInt())}.\n")
            val hrvBase = store.average(BASELINE_DAYS, skipDays = 1) { it.hrv.toDouble() }
            val rhrBase = store.average(BASELINE_DAYS, skipDays = 1) { it.restingHr.toDouble() }
            if (today.hrv > 0) {
                append("HRV ${today.hrv}")
                if (hrvBase > 0) append(" (база за $BASELINE_DAYS дн. ${fmt0(hrvBase)})")
                append(". ")
            }
            if (today.restingHr > 0) {
                append("Пульс покоя ${today.restingHr}")
                if (rhrBase > 0) append(" (база ${fmt0(rhrBase)})")
                append(". ")
            }
            if (today.sleepHours > 0) append("Сон ${fmt1(today.sleepHours)} ч")
            if (today.sleepScore > 0) append(", счёт ${today.sleepScore}")
            append("\n\n")
        }

        append("НАГРУЗКА ПО НЕДЕЛЯМ (сумма load, свежая первая)\n")
        append((0..3).joinToString(" · ") { w ->
            val label = if (w == 0) "эта" else "$w нед. назад"
            "$label ${store.weekLoad(w)}"
        })
        append("\n\n")

        val workouts = store.recentWorkouts(CONTEXT_WORKOUT_DAYS)
        append("ТРЕНИРОВКИ за $CONTEXT_WORKOUT_DAYS дн. (${workouts.size})\n")
        if (workouts.isEmpty()) {
            append("Ни одной — либо не тренировался, либо выгрузка не дошла.\n")
        } else {
            for (w in workouts) append(workoutLine(w)).append('\n')
        }
        append('\n')

        val health = store.healthFlow.value.take(CONTEXT_HEALTH_DAYS)
        if (health.isNotEmpty()) {
            append("ЗДОРОВЬЕ по дням (дата · HRV · пульс покоя · сон ч · шаги · вес)\n")
            for (h in health) {
                append(h.date).append(" · ")
                append(if (h.hrv > 0) "${h.hrv}" else "—").append(" · ")
                append(if (h.restingHr > 0) "${h.restingHr}" else "—").append(" · ")
                append(if (h.sleepHours > 0) fmt1(h.sleepHours) else "—").append(" · ")
                append(if (h.steps > 0) "${h.steps}" else "—").append(" · ")
                append(if (h.weightKg > 0) fmt1(h.weightKg) else "—")
                append('\n')
            }
            append('\n')
        }

        appendFood(targets)
        appendDays()
        appendRules()
    }

    /**
     * План на неделю вперёд и ЕГО СОБСТВЕННЫЕ ПРАВИЛА из Notion.
     *
     * Без этого блока совет получался общемедицинским: «стоит ли сегодня
     * бежать» — вопрос не про физиологию вообще, а про то, что у него потолок
     * лёгкого бега 150, серая зона 160–165, три пробежки в неделю максимум и
     * «первым выпадает бег, силовые не двигаются никогда». Правила он написал
     * сам и правит руками; модель, которая их не видит, будет спорить с
     * владельцем его же словами.
     *
     * Текст страницы уезжает прозой и с обрезкой: числа из него уже вынуты в
     * поля, но проза объясняет ПОЧЕМУ, а это ровно то, за чем идут к Опусу.
     */
    private fun StringBuilder.appendRules() {
        val upcoming = runCatching { planStore.upcoming(7) }.getOrNull().orEmpty()
        if (upcoming.isNotEmpty()) {
            append("ПЛАН НА НЕДЕЛЮ (из календаря intervals)\n")
            for (d in upcoming) {
                append(d.date).append(" · ").append(d.name)
                if (d.minutes > 0) append(" · ${d.minutes} мин")
                if (d.load > 0) append(" · load ${d.load}")
                append('\n')
            }
            append('\n')
        }
        val rules = runCatching { planStore.rulesFlow.value }.getOrNull() ?: return
        if (!rules.known && rules.sourceText.isBlank()) return
        append("ЕГО ПРАВИЛА (страница блока в Notion, правит руками)\n")
        if (rules.blockTitle.isNotBlank()) append("Блок: ${rules.blockTitle}\n")
        if (rules.runHrCeiling > 0) append("Потолок лёгкого бега ${rules.runHrCeiling}. ")
        if (rules.greyZoneLow > 0 && rules.greyZoneHigh > 0) {
            append("Серая зона ${rules.greyZoneLow}–${rules.greyZoneHigh} — в ней не работать. ")
        }
        if (rules.cadenceMin > 0) append("Каденс ${rules.cadenceMin}+. ")
        if (rules.runsPerWeekMax > 0) append("Пробежек в неделю не больше ${rules.runsPerWeekMax}. ")
        if (rules.hoursBetweenRuns > 0) append("Между пробежками ${rules.hoursBetweenRuns} ч. ")
        if (rules.rampNeedsPositiveTsb) append("Рамп-тест только на плюсовом TSB. ")
        if (rules.cancelOrder.isNotBlank()) append("Отмена: ${rules.cancelOrder}. ")
        append('\n')
        if (rules.kneeGreen.isNotBlank() || rules.kneeYellow.isNotBlank() || rules.kneeRed.isNotBlank()) {
            append("Светофор колена — зелёный: ${rules.kneeGreen}; ")
            append("жёлтый: ${rules.kneeYellow}; красный: ${rules.kneeRed}\n")
        }
        if (rules.sourceText.isNotBlank()) {
            append("\nСтраница целиком (проза, тут объяснено почему):\n")
            append(rules.sourceText.take(CONTEXT_RULES_CHARS))
            if (rules.sourceText.length > CONTEXT_RULES_CHARS) append("\n…")
            append('\n')
        }
        append('\n')
    }

    /** Еда: сумма дня против цели — тут и видно, чем оплачена усталость. */
    private fun StringBuilder.appendFood(targets: Settings.Targets?) {
        val days = foodStore.recentDays(CONTEXT_FOOD_DAYS)
        if (days.isEmpty()) {
            append("ЕДА\nДневник за неделю пуст — про питание сказать нечего.\n\n")
            return
        }
        append("ЕДА по дням (дата · ккал · Б/Ж/У г · приёмов)\n")
        for (d in days) {
            append(d.date).append(" · ").append(d.kcal).append(" · ")
            append("${d.protein}/${d.fat}/${d.carbs}").append(" · ").append(d.meals)
            append('\n')
        }
        val avg = days.sumOf { it.kcal } / days.size
        append("В среднем $avg ккал в день по ${days.size} дн. с записями.")
        if (targets != null) {
            append(" Его цель: ${targets.kcal} ккал, ")
            append("Б${targets.protein} Ж${targets.fat} У${targets.carbs} г.")
        }
        append("\n\n")
    }

    /**
     * Три дня из ленты Засечки, сжатые до категорий. Тренировка не живёт в
     * пустоте: одиннадцать часов работы и пять часов сна объясняют провал HRV
     * лучше любой тренировочной цифры.
     */
    private fun StringBuilder.appendDays() {
        val now = System.currentTimeMillis()
        val entries = runCatching { zasechkaStore.entriesFlow.value }.getOrNull() ?: return
        if (entries.isEmpty()) return
        append("ЧЕМ БЫЛ ЗАНЯТ (по ленте, часы по категориям)\n")
        for (back in 0..2) {
            val dayStart = ru.zf.pravka.data.dayStartMs(now - back * 86_400_000L)
            val dayEnd = dayStart + 86_400_000L
            val byCategory = entries
                .filter { it.start < dayEnd && (if (it.open) now else it.end) > dayStart }
                .groupBy { it.category.ifBlank { "без категории" } }
                .mapValues { (_, list) -> list.sumOf { it.durationMsIn(dayStart, dayEnd, now) } }
                .filterValues { it >= 30 * 60_000L }
                .toList()
                .sortedByDescending { it.second }
                .take(7)
            if (byCategory.isEmpty()) continue
            append(dayKey(dayStart)).append(": ")
            append(byCategory.joinToString(", ") { (c, ms) -> "$c ${fmt1(ms / 3_600_000.0)} ч" })
            append('\n')
        }
        append('\n')
    }

    /** «22.08 Run «Вечерняя» 52 мин, 8.4 км, 6:12/км, пульс 148, load 61». */
    private fun workoutLine(w: SportStore.Workout): String = buildString {
        append(dayMonth(w.start)).append(' ')
        append(sportName(w.type)).append(' ')
        if (w.name.isNotBlank() && !w.name.equals(w.type, true)) append("«${w.name}» ")
        append("${w.minutes} мин")
        if (w.km >= 0.1) append(", ${fmt1(w.km)} км")
        if (w.elevationM >= 30) append(", набор ${w.elevationM.toInt()} м")
        if (w.paceSecPerKm > 0) append(", ${pace(w.paceSecPerKm)}/км")
        if (w.gapSecPerKm > 0 && kotlin.math.abs(w.gapSecPerKm - w.paceSecPerKm) > 8) {
            append(" (по рельефу ${pace(w.gapSecPerKm)})")
        }
        if (w.avgWatts > 0) append(", ${w.avgWatts} Вт")
        if (w.avgHr > 0) append(", пульс ${w.avgHr}")
        if (w.maxHr > 0) append("/${w.maxHr}")
        if (w.load > 0) append(", load ${w.load}")
        if (w.decoupling != 0.0) append(", расхождение ${fmt1(w.decoupling)}%")
        if (w.rpe > 0) append(", RPE ${w.rpe}")
        if (w.feel > 0) append(", самочувствие ${w.feel}/5")
        if (w.zoneMinutes.any { it > 0 }) {
            append(", по зонам ")
            append(w.zoneMinutes.mapIndexed { i, m -> if (m > 0) "z${i + 1} $m" else "" }
                .filter { it.isNotBlank() }.joinToString(" "))
        }
    }

    // ---- Мелочи форматирования (те же, что во вкладке) ----

    private val dayMonthFormat = SimpleDateFormat("dd.MM", Locale.US)
    private fun dayMonth(at: Long): String = dayMonthFormat.format(Date(at))

    private fun fmt0(v: Double) = String.format(Locale.US, "%.0f", v)
    private fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun percent(fraction: Double) = Math.round(fraction * 100).toInt()
    private fun signed(v: Int) = if (v > 0) "+$v" else "$v"

    private fun pace(secPerKm: Int): String =
        "${secPerKm / 60}:" + String.format(Locale.US, "%02d", secPerKm % 60)
}
