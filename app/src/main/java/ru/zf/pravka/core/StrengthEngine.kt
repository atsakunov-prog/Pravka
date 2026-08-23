package ru.zf.pravka.core

import ru.zf.pravka.data.ExerciseBook
import ru.zf.pravka.data.EventLog
import ru.zf.pravka.data.IcuSportSync
import ru.zf.pravka.data.PlanStore
import ru.zf.pravka.data.StrengthStore
import ru.zf.pravka.data.dayKey
import ru.zf.pravka.data.fmtWeight
import ru.zf.pravka.provider.ClaudeProvider

// Журнал силовых: сказанное → подходы → прошлый раз → в активность Garmin.
//
// Прогрессивная перегрузка — это «сегодня чуть больше, чем прошлый раз», и вся
// ценность здесь в том, что прошлый раз ИЗВЕСТЕН в момент подхода. Поэтому
// движок делает три вещи и делает их в этом порядке:
//
//   1. кладёт сказанное на диск, до всякого разбора (см. StrengthStore);
//   2. раскладывает по упражнениям из справочника и считает дельту к прошлому
//      разу — по подходам, повторам, весу и объёму;
//   3. дописывает журнал в активность Garmin за этот день, а если её ещё нет —
//      ждёт, пока часы синхронизируются, и не теряет запись.
//
// Идемпотентность живёт в сторе (ключ — дата плюс упражнение), но правило её
// задаёт модель: «replace» по умолчанию, «add» только когда владелец явно
// договаривает («ещё два подхода гоблета»). Поэтому повторить фразу целиком
// безопасно, а дописать — намеренно.
class StrengthEngine(
    private val store: StrengthStore,
    private val book: ExerciseBook,
    private val planStore: PlanStore,
    private val icu: IcuSportSync,
    private val eventLog: EventLog,
) {

    companion object {
        // Сколько ждём активность от часов, прежде чем положить журнал
        // заметкой в календарь. Garmin синхронизируется минутами, но дача без
        // интернета — сутками, и терять подходы из-за этого нельзя.
        private const val WAIT_FOR_ACTIVITY_MS = 36 * 3_600_000L
        // Досыл сам себя дросселирует: тик службы приходит каждые пять минут,
        // а стучаться в чужой API так часто незачем.
        private const val SYNC_PERIOD_MS = 10 * 60_000L
        private const val ICU_STRENGTH_TYPE = "WeightTraining"
    }

    @Volatile private var lastSync = 0L

    /** Итог записи: что легло, к чему это по сравнению с прошлым разом. */
    data class Logged(
        val session: StrengthStore.Session,
        val deltas: List<Delta>,
        val unknown: List<String>,
    )

    /**
     * Насколько сегодня больше прошлого раза. Смотрим на подходы, повторы и
     * вес по отдельности, а не на один сводный балл: «на два повтора больше»
     * и «на два кило тяжелее» — разные новости, и владельцу нужны обе.
     */
    data class Delta(
        val name: String,
        val today: String,
        val previous: String,
        val previousDate: String,
        val text: String,
        /** true — рост, false — просадка, null — прошлого раза не было. */
        val up: Boolean?,
    )

    // ---- Запись ----

    /**
     * Разобранные подходы → тренировка дня. Блок и название берём из плана
     * (календарь intervals), потому что владелец сам их туда положил; плана
     * нет — блок пустой, и тренировка всё равно записывается.
     */
    suspend fun record(
        parsed: ClaudeProvider.StrengthParse,
        rawId: Long,
        date: String = dayKey(System.currentTimeMillis()),
    ): Logged? {
        book.load()
        store.load()
        planStore.load()
        val planned = planStore.mainOf(date)
        val block = planned?.block.orEmpty()
        val title = planned?.name.orEmpty().ifBlank {
            if (block.isNotBlank()) "Силовая $block" else "Силовая"
        }
        val session = store.sessionFor(date, block, title)

        val logs = mutableListOf<StrengthStore.ExerciseLog>()
        val unknown = mutableListOf<String>()
        for (item in parsed.exercises) {
            val exercise = book.match(item.name)
            if (exercise == null) {
                // Не выдумываем движение и не выбрасываем его: кладём как
                // сказано, с самодельным id, и говорим об этом наружу.
                unknown.add(item.name)
                logs.add(
                    StrengthStore.ExerciseLog(
                        exerciseId = "free-" + ExerciseBook.normalize(item.name).replace(' ', '-').take(40),
                        name = item.name,
                        unit = ExerciseBook.UNIT_REPS,
                        rows = item.sets.map {
                            StrengthStore.SetRow(it.amount, it.weightKg, it.note)
                        },
                        note = item.note,
                    )
                )
                continue
            }
            logs.add(
                StrengthStore.ExerciseLog(
                    exerciseId = exercise.id,
                    name = exercise.name,
                    unit = exercise.unit,
                    rows = item.sets.map { StrengthStore.SetRow(it.amount, it.weightKg, it.note) },
                    note = item.note,
                )
            )
        }
        if (logs.isEmpty() && parsed.feel == 0 && parsed.rpe == 0) return null

        // Дельту считаем ДО влития: после него прошлым разом станет сегодня.
        val deltas = logs.map { deltaFor(it, date) }

        var result = store.mergeExercises(
            sessionId = session.id,
            incoming = logs.sortedBy { orderOf(it.exerciseId, block) },
            replace = parsed.mode != "add",
            rawId = rawId,
        ) ?: session
        if (parsed.feel > 0 || parsed.rpe > 0 || parsed.minutes > 0) {
            result = store.setFeel(session.id, parsed.feel, parsed.rpe, "") ?: result
            if (parsed.minutes > 0) {
                result = store.setDone(session.id, done = true, minutes = parsed.minutes) ?: result
            }
        }
        eventLog.add(
            "силовые: ${date} ${block.ifBlank { "без блока" }} — упражнений ${logs.size}, " +
                "подходов ${logs.sumOf { it.sets }}" +
                (if (unknown.isEmpty()) "" else ", не узнал: " + unknown.joinToString(", "))
        )
        return Logged(result, deltas, unknown)
    }

    /** Порядок упражнения — как в справочнике блока, чтобы карточка шла по плану. */
    private fun orderOf(exerciseId: String, block: String): Int {
        val exercise = book.byId(exerciseId) ?: return 999
        return if (block.isBlank()) exercise.order else exercise.order
    }

    /** Дельта одного упражнения к его же прошлому разу. */
    fun deltaFor(today: StrengthStore.ExerciseLog, date: String): Delta {
        val previous = store.lastTime(today.exerciseId, date)
        if (previous == null) {
            return Delta(
                name = today.name,
                today = today.compact(),
                previous = "",
                previousDate = "",
                text = "первый раз",
                up = null,
            )
        }
        val (session, was) = previous
        val bits = mutableListOf<String>()
        val setsDiff = today.sets - was.sets
        if (setsDiff != 0) bits.add(signed(setsDiff) + " " + podhod(kotlin.math.abs(setsDiff)))
        val amountDiff = today.totalAmount - was.totalAmount
        if (amountDiff != 0) bits.add(signed(amountDiff) + " " + unitWord(today.unit, kotlin.math.abs(amountDiff)))
        val weightDiff = today.topWeight - was.topWeight
        if (kotlin.math.abs(weightDiff) >= 0.5) {
            bits.add((if (weightDiff > 0) "+" else "−") + fmtWeight(kotlin.math.abs(weightDiff)))
        }
        // Объём — последний аргумент: он ловит рост, который не виден в
        // подходах и повторах порознь (меньше повторов, но тяжелее).
        val volumeUp = today.volume > was.volume * 1.02
        val volumeDown = today.volume < was.volume * 0.98
        val text = when {
            bits.isNotEmpty() -> bits.joinToString(", ")
            volumeUp -> "объём чуть выше"
            volumeDown -> "объём чуть ниже"
            else -> "как в прошлый раз"
        }
        return Delta(
            name = today.name,
            today = today.compact(),
            previous = was.compact(),
            previousDate = session.date,
            text = text,
            up = when {
                volumeUp -> true
                volumeDown -> false
                else -> null
            },
        )
    }

    /** Прошлый раз по каждому упражнению блока — то, что показывает карточка. */
    fun lastTimeFor(block: String, date: String): List<Pair<ExerciseBook.Exercise, StrengthStore.ExerciseLog?>> {
        val list = if (block.isBlank()) emptyList() else book.ofBlock(block)
        return list.map { exercise ->
            exercise to store.lastTime(exercise.id, date)?.second
        }
    }

    /**
     * Блок для промпта: прошлый раз по упражнениям сегодняшнего блока. Модель
     * с ним не гадает, «шестнадцать» — это вес или повторы, потому что видит,
     * с чем владелец работал в прошлый раз.
     */
    fun lastTimeBlock(block: String, date: String): String {
        val pairs = lastTimeFor(block, date).filter { it.second != null }
        if (pairs.isEmpty()) return ""
        return buildString {
            append("Прошлый раз по этим упражнениям (для сверки чисел):\n")
            for ((exercise, log) in pairs) {
                append("- ").append(exercise.name).append(": ").append(log!!.compact()).append('\n')
            }
        }
    }

    // ---- Правка руками ----

    suspend fun editRows(sessionId: Long, exerciseId: String, rows: List<StrengthStore.SetRow>) {
        if (rows.isEmpty()) store.dropExercise(sessionId, exerciseId)
        else store.replaceRows(sessionId, exerciseId, rows)
    }

    suspend fun setFeel(sessionId: Long, feel: Int, rpe: Int, note: String) =
        store.setFeel(sessionId, feel, rpe, note)

    /**
     * Галочка упражнения силовой в чек-листе дня — «сделал по схеме», без
     * чисел. Сессии ещё нет — заводится, как при наговоре. Отмечены все
     * упражнения блока — сессия закрывается сама (done), останется спросить
     * самочувствие.
     */
    suspend fun toggleChecked(
        exerciseId: String,
        date: String = dayKey(System.currentTimeMillis()),
    ): StrengthStore.Session? {
        store.load()
        planStore.load()
        book.load()
        val planned = planStore.mainOf(date)
        val block = planned?.block.orEmpty()
        val session = store.sessionsOn(date).firstOrNull()
            ?: store.sessionFor(date, block, planned?.name.orEmpty())
        val updated = store.toggleChecked(session.id, exerciseId) ?: return null
        if (!updated.done && block.isNotBlank()) {
            val all = book.ofBlock(block).map { it.id }
            if (all.isNotEmpty() && all.all { updated.isChecked(it) }) {
                return store.setDone(updated.id, done = true, minutes = planned?.minutes ?: 0)
            }
        }
        return updated
    }

    suspend fun markDone(date: String, minutes: Int = 0): StrengthStore.Session {
        store.load()
        planStore.load()
        val planned = planStore.mainOf(date)
        val session = store.sessionFor(
            date,
            planned?.block.orEmpty(),
            planned?.name.orEmpty().ifBlank { "Тренировка" },
        )
        return store.setDone(session.id, done = true, minutes = minutes) ?: session
    }

    // ---- Обратная дорога в intervals ----

    /**
     * Журнал подходов текстом — ровно то, что уезжает в описание активности.
     * Формат человеческий, а не JSON: его читает владелец в intervals и в
     * недельной сводке, а не программа.
     */
    fun setLogText(session: StrengthStore.Session): String = buildString {
        if (session.title.isNotBlank()) append(session.title).append('\n')
        for (e in session.exercises) {
            append("• ").append(e.name).append(": ").append(e.compact())
            if (e.note.isNotBlank()) append(" — ").append(e.note)
            val perSet = e.rows.mapIndexedNotNull { i, r ->
                if (r.note.isBlank()) null else "${i + 1}-й: ${r.note}"
            }
            if (perSet.isNotEmpty()) append(" (").append(perSet.joinToString("; ")).append(")")
            append('\n')
        }
        if (session.feel in 1..5) append("Самочувствие ").append(session.feel).append("/5 (1 — отлично)\n")
        if (session.rpe in 1..10) append("Тяжесть RPE ").append(session.rpe).append("/10\n")
        if (session.note.isNotBlank()) append(session.note).append('\n')
        append("Записано голосом в Правке")
    }

    data class SyncOutcome(val sent: Int, val waiting: Int, val failed: Int, val error: String)

    /**
     * Досыл тренировок в intervals.
     *
     * Порядок такой: сначала ищем активность WeightTraining за этот день —
     * владелец пишет силовые на часы, и дописывать надо именно туда. Активности
     * ещё нет (часы не синхронизировались, дача без интернета) — ЖДЁМ, ничего
     * не портим. Не появилась за полтора суток — кладём журнал заметкой в
     * календарь на ту же дату: пусть лежит рядом с планом, чем нигде.
     */
    /**
     * Куда уехал журнал этой сессии — словами, для карточки.
     *
     * Механика тут неочевидная, и без объяснения она читается как «ничего не
     * произошло»: силовую владелец пишет часами, часы отдают её в intervals как
     * активность WeightTraining, и журнал подходов должен лечь в ОПИСАНИЕ этой
     * активности, а не рядом с ней. Значит между «сказал в телефон» и «уехало»
     * есть ожидание — обычно минуты, на даче сутки. Карточка обязана говорить,
     * чего именно ждёт, иначе владелец решит, что запись потерялась, и
     * продиктует всё второй раз.
     */
    data class Route(
        val headline: String,
        val hint: String,
        /** −1 не вышло · 0 ждём · 1 уехало. */
        val tone: Int,
        val canRetry: Boolean,
        val canNote: Boolean,
    )

    fun routeOf(session: StrengthStore.Session): Route = when {
        session.icuSynced && session.icuActivityId.isNotBlank() -> Route(
            headline = "Дописано в тренировку с часов",
            hint = "Журнал лежит в описании активности Garmin за этот день — " +
                "одной записью, а не двумя. Повторная надиктовка перепишет тот же " +
                "блок, дубля не будет.",
            tone = 1,
            canRetry = false,
            canNote = false,
        )
        session.icuSynced && session.icuNoteId.isNotBlank() -> Route(
            headline = "Записано заметкой в календарь",
            hint = "Активности с часов за этот день не нашлось, поэтому журнал " +
                "лёг отдельной заметкой. Если часы её потом пришлют, записи " +
                "останутся двумя: слить их можно только руками в intervals.",
            tone = 1,
            canRetry = false,
            canNote = false,
        )
        session.icuSynced -> Route("Уехало", "", 1, false, false)
        session.lastError.isNotBlank() -> Route(
            headline = "Не уехало: ${session.lastError}",
            hint = "Попыток ${session.attempts}. Подходы на телефоне целы — " +
                "отправка повторится сама, и её можно подтолкнуть.",
            tone = -1,
            canRetry = true,
            canNote = true,
        )
        else -> Route(
            headline = "Ждём тренировку с часов",
            hint = "Часы отдадут силовую в intervals как WeightTraining, и журнал " +
                "допишется в её описание. Не придёт за полтора суток — ляжет " +
                "отдельной заметкой в календарь сама. Подходы на телефоне уже " +
                "записаны, потерять их нельзя.",
            tone = 0,
            canRetry = true,
            canNote = true,
        )
    }

    /**
     * Не ждать часов и положить журнал заметкой прямо сейчас. Нужно, когда
     * силовая прошла без часов вообще: ждать сутки с половиной ради заметки,
     * которая всё равно будет заметкой, незачем.
     */
    suspend fun pushAsNote(sessionId: Long): Result<String> {
        store.load()
        val session = store.sessionsFlow.value.firstOrNull { it.id == sessionId }
            ?: return Result.failure(IllegalStateException("Сессия не найдена"))
        val outcome = icu.writeNote(
            date = session.date,
            name = session.title.ifBlank { "Силовая (из Правки)" },
            body = setLogText(session),
            existingId = session.icuNoteId,
        )
        outcome.onSuccess { id ->
            store.markSynced(session.id, "", id)
            eventLog.add("силовые → intervals: заметка за ${session.date} по кнопке")
        }.onFailure { e -> store.markAttempt(session.id, e.message.orEmpty()) }
        return outcome
    }

    suspend fun syncPending(force: Boolean = false): SyncOutcome {
        val now = System.currentTimeMillis()
        if (!force && now - lastSync < SYNC_PERIOD_MS) return SyncOutcome(0, 0, 0, "")
        lastSync = now
        store.load()
        var sent = 0
        var waiting = 0
        var failed = 0
        var error = ""
        for (session in store.pendingSync()) {
            if (session.empty && session.feel == 0 && !session.done) continue
            val body = setLogText(session)
            val activityId = session.icuActivityId.ifBlank {
                runCatching { icu.findActivity(session.date, ICU_STRENGTH_TYPE) }.getOrNull().orEmpty()
            }
            if (activityId.isNotBlank()) {
                val outcome = icu.writeSetLog(activityId, body, session.feel, session.rpe)
                outcome.onSuccess {
                    store.markSynced(session.id, activityId, "")
                    sent++
                }.onFailure { e ->
                    failed++
                    if (error.isBlank()) error = e.message ?: "не вышло"
                    store.markAttempt(session.id, e.message.orEmpty())
                }
                continue
            }
            val age = now - dayStartOf(session.date)
            if (age < WAIT_FOR_ACTIVITY_MS) {
                waiting++
                continue
            }
            // Часы так и не прислали активность — журнал уходит заметкой.
            val outcome = icu.writeNote(
                date = session.date,
                name = session.title.ifBlank { "Силовая (из Правки)" },
                body = body,
                existingId = session.icuNoteId,
            )
            outcome.onSuccess { id ->
                store.markSynced(session.id, "", id)
                sent++
            }.onFailure { e ->
                failed++
                if (error.isBlank()) error = e.message ?: "не вышло"
                store.markAttempt(session.id, e.message.orEmpty())
            }
        }
        // Зарядка — той же очередью: день с отметками уезжает строкой в
        // комментарий wellness. Активности у зарядки обычно нет, а календарный
        // день в intervals есть всегда.
        for (day in store.gtgNeedingSync().take(5)) {
            val outcome = icu.spliceWellnessComment(day.date, day.line())
            outcome.onSuccess {
                store.markGtgSynced(day.date)
                sent++
            }.onFailure { e ->
                failed++
                if (error.isBlank()) error = e.message ?: "не вышло"
            }
        }
        if (sent > 0 || failed > 0) {
            eventLog.add(
                "силовые → intervals: отправлено $sent, ждут $waiting, не вышло $failed" +
                    (if (error.isBlank()) "" else " ($error)")
            )
        }
        return SyncOutcome(sent, waiting, failed, error)
    }

    /**
     * Комментарий к тренировке с часов (бег, вело): сказанное сохраняется
     * сырой надиктовкой навсегда и вклеивается своим блоком в описание этой
     * активности в intervals. Модель не зовём: комментарий — это слова, а не
     * числа, чистить в них нечего.
     */
    suspend fun commentWorkout(activityId: String, text: String): Result<Unit> {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return Result.failure(IllegalArgumentException("Пусто"))
        store.load()
        val raw = store.addRaw(trimmed, "comment")
        store.markRaw(raw.id, "comment", 0L)
        val outcome = icu.writeSetLog(activityId, trimmed, 0, 0)
        outcome.onSuccess {
            eventLog.add("комментарий → активность $activityId: ${trimmed.length} зн.")
        }
        return outcome
    }

    // ---- Мелочи ----

    private fun dayStartOf(date: String): Long = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(date)?.time
    }.getOrNull() ?: System.currentTimeMillis()

    private fun signed(v: Int) = if (v > 0) "+$v" else "−${kotlin.math.abs(v)}"

    private fun podhod(n: Int) = when {
        n % 10 == 1 && n % 100 != 11 -> "подход"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "подхода"
        else -> "подходов"
    }

    private fun unitWord(unit: String, n: Int) = when (unit) {
        ExerciseBook.UNIT_SEC -> "сек"
        ExerciseBook.UNIT_M -> "м"
        ExerciseBook.UNIT_MIN -> "мин"
        else -> when {
            n % 10 == 1 && n % 100 != 11 -> "повтор"
            n % 10 in 2..4 && n % 100 !in 12..14 -> "повтора"
            else -> "повторов"
        }
    }
}
