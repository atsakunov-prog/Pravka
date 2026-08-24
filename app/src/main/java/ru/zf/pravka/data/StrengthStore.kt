package ru.zf.pravka.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// Журнал силовых, зарядки и GTG (`strength.json`).
//
// Самые незаменимые данные в приложении, и вот почему: подходы владельца не
// живут больше НИГДЕ. Тренировок в силовой в intervals.icu не было ни одной за
// сто двадцать дней, Excel — расчётный черновик, часы пишут время, но не
// «гоблет четыре по десять шестнадцать». Прогрессивная перегрузка — это
// «сегодня чуть больше, чем прошлый раз», и без прошлого раза её нет.
//
// Отсюда три железных правила, и они дороже удобства:
//
// 1. СЫРАЯ НАДИКТОВКА НЕ УДАЛЯЕТСЯ НИКОГДА. Что владелец сказал — незаменимо;
//    разбор — производная, её можно переиграть при смене модели или промпта.
//    Даже неразобранная фраза остаётся на диске: лучше строка «свинги пять
//    пятнадцать» без разбора, чем пустота.
// 2. Пустой журнал поверх непустого файла отклоняется — тот же заслон, что у
//    ленты Засечки и дневника еды.
// 3. Идемпотентность по дате и упражнению: повторная надиктовка того же
//    подхода заменяет его, а не удваивает. «Ещё два подхода» — это отдельное
//    намерение, и модель помечает его явно.
class StrengthStore(private val context: Context) {

    companion object {
        const val FILE_NAME = "strength.json"
        // Сырые надиктовки: держим год. Это единственное, что нельзя добыть
        // заново, но и бесконечно их копить незачем — разбор давно на месте.
        private const val KEEP_RAW_DAYS = 400
    }

    /**
     * Что владелец сказал, буква в букву. Не удаляется вместе с разбором:
     * [consumedBy] лишь помечает, в какую тренировку это ушло.
     */
    data class RawTake(
        val id: Long,
        val ts: Long,
        val text: String,
        val kind: String,        // strength | gtg | feel | food | question | unknown
        val source: String,      // voice | text
        val consumedBy: Long = 0L,
        val error: String = "",
    )

    /** Один подход: сколько, с каким весом и что владелец о нём сказал. */
    data class SetRow(
        val amount: Int,          // повторы, или секунды/метры/минуты по unit
        val weightKg: Double = 0.0,
        val note: String = "",
    )

    /** Одно упражнение внутри тренировки со всеми своими подходами. */
    data class ExerciseLog(
        val exerciseId: String,
        val name: String,
        val unit: String = ExerciseBook.UNIT_REPS,
        val rows: List<SetRow> = emptyList(),
        val note: String = "",
    ) {
        val sets: Int get() = rows.size
        val totalAmount: Int get() = rows.sumOf { it.amount }
        val topWeight: Double get() = rows.maxOfOrNull { it.weightKg } ?: 0.0

        /** Объём подхода — то, по чему считается прогрессия: Σ(повторы × вес). */
        val volume: Double
            get() = rows.sumOf { it.amount * (if (it.weightKg > 0) it.weightKg else 1.0) }

        /**
         * «4×10 @16» — одинаковые подходы сворачиваются, разные пишутся
         * подряд: «3×8, 1×6 @16». Так строка читается с одного взгляда.
         */
        fun compact(): String {
            if (rows.isEmpty()) return "—"
            val groups = mutableListOf<Pair<SetRow, Int>>()
            for (row in rows) {
                val last = groups.lastOrNull()
                if (last != null && last.first.amount == row.amount &&
                    last.first.weightKg == row.weightKg
                ) {
                    groups[groups.size - 1] = last.first to (last.second + 1)
                } else {
                    groups.add(row to 1)
                }
            }
            val body = groups.joinToString(", ") { (row, count) ->
                val amount = when (unit) {
                    ExerciseBook.UNIT_SEC -> "${row.amount} сек"
                    ExerciseBook.UNIT_M -> "${row.amount} м"
                    ExerciseBook.UNIT_MIN -> "${row.amount} мин"
                    else -> row.amount.toString()
                }
                if (count > 1) "$count×$amount" else amount
            }
            val weight = topWeight
            return if (weight > 0) body + " @" + fmtWeight(weight) else body
        }
    }

    /** Тренировка одного дня: подходы, самочувствие и куда она уже уехала. */
    data class Session(
        val id: Long,
        val date: String,               // yyyy-MM-dd
        val block: String,              // «A · дом», «Зарядка», «Турник», «»
        val title: String,              // название сессии из плана
        val exercises: List<ExerciseLog>,
        val feel: Int = 0,              // 1 отлично … 5 развалина (шкала intervals)
        val rpe: Int = 0,               // 1..10
        val note: String = "",
        val minutes: Int = 0,
        val icuActivityId: String = "",  // активность Garmin, куда дописали
        val icuSynced: Boolean = false,
        val icuNoteId: String = "",      // NOTE-событие, если активности так и не было
        val attempts: Int = 0,
        val lastError: String = "",
        val rawIds: List<Long> = emptyList(),
        val done: Boolean = false,       // владелец нажал «сделано»
        /**
         * Галочки чек-листа: упражнения, отмеченные «ок» БЕЗ надиктовки чисел.
         * Журнал (rows) — отдельно: галочка значит «сделал по схеме», числа
         * значат «сделал вот так». Журнальное упражнение считается отмеченным
         * само собой.
         */
        val checkedIds: List<String> = emptyList(),
    ) {
        fun isChecked(exerciseId: String): Boolean =
            exerciseId in checkedIds || exercises.any { it.exerciseId == exerciseId && it.rows.isNotEmpty() }

        val setCount: Int get() = exercises.sumOf { it.sets }
        val volume: Double get() = exercises.sumOf { it.volume }
        val empty: Boolean get() = exercises.isEmpty()

        /** Ждёт отправки: есть что отправить и ещё не уехало. */
        val pendingSync: Boolean get() = !icuSynced && (!empty || feel > 0 || done)
    }

    /**
     * GTG-день: зарядка сделана, вис в секундах, негативы и лопаточные в
     * повторах. Цепочка галочек, которая не должна рваться, — единственная
     * метрика, где визуализация streak правда меняет поведение. И этих данных
     * нет больше нигде.
     */
    data class GtgDay(
        val date: String,
        val charged: Boolean = false,   // зарядка сделана
        val hangSec: Int = 0,
        val negatives: Int = 0,
        val scapular: Int = 0,
        // Подтягивания. Ради этого числа затевался весь GTG: первый раз, когда
        // оно станет больше нуля, — и есть цель №2.
        val pullups: Int = 0,
        // Светофор колена на этот день: «зелёный» | «жёлтый» | «красный».
        // Живёт рядом с зарядкой, потому что это тоже ежедневная отметка — и
        // потому что именно она решает, режем ли бег (он младший).
        val knee: String = "",
        val note: String = "",
        val ts: Long = 0L,
        /** Галочки чек-листа зарядки: id упражнений блока «Зарядка», отмеченных сегодня. */
        val doneIds: List<String> = emptyList(),
        /** День уехал в комментарий wellness intervals; любая правка снимает. */
        val icuSynced: Boolean = false,
    ) {
        val any: Boolean
            get() = charged || hangSec > 0 || negatives > 0 || scapular > 0 ||
                pullups > 0 || knee.isNotBlank()

        /** День одной строкой — для комментария wellness, сводки и CSV. */
        fun line(): String = buildString {
            append("Зарядка: ")
            append(if (charged) "сделана" else "не отмечена")
            if (pullups > 0) append(" · подтягивания $pullups")
            if (hangSec > 0) append(" · вис $hangSec сек")
            if (negatives > 0) append(" · негативы $negatives")
            if (scapular > 0) append(" · лопаточные $scapular")
            if (knee.isNotBlank()) append(" · колено $knee")
            if (note.isNotBlank()) append(" — ").append(note)
        }
    }

    private val mutex = Mutex()
    private val file: File get() = File(context.filesDir, FILE_NAME)
    private var loaded = false

    private val _sessionsFlow = MutableStateFlow<List<Session>>(emptyList())
    val sessionsFlow: StateFlow<List<Session>> = _sessionsFlow

    private val _rawFlow = MutableStateFlow<List<RawTake>>(emptyList())
    val rawFlow: StateFlow<List<RawTake>> = _rawFlow

    private val _gtgFlow = MutableStateFlow<List<GtgDay>>(emptyList())
    val gtgFlow: StateFlow<List<GtgDay>> = _gtgFlow

    var logger: ((String) -> Unit)? = null

    suspend fun load() = mutex.withLock { ensureLoaded() }

    // ---- Сырые надиктовки ----

    /**
     * Первое, что происходит с услышанным: оно ложится на диск ДО разбора.
     * Модель может не ответить, приложение может умереть, промпт может
     * оказаться плохим — сказанное уже сохранено.
     */
    suspend fun addRaw(text: String, source: String): RawTake = mutex.withLock {
        ensureLoaded()
        val now = System.currentTimeMillis()
        val take = RawTake(id = now, ts = now, text = text, kind = "", source = source)
        _rawFlow.value = listOf(take) + _rawFlow.value
        persist()
        take
    }

    suspend fun markRaw(id: Long, kind: String, consumedBy: Long, error: String = "") =
        mutex.withLock {
            ensureLoaded()
            _rawFlow.value = _rawFlow.value.map {
                if (it.id == id) it.copy(kind = kind, consumedBy = consumedBy, error = error) else it
            }
            persist()
        }

    fun rawById(id: Long): RawTake? = _rawFlow.value.firstOrNull { it.id == id }

    /**
     * Надиктовки, которые ничем не стали: их можно переиграть. Именно по kind,
     * а не по consumedBy: у зарядки, самочувствия и вопроса consumedBy пустой,
     * но они разобраны.
     */
    fun rawUnparsed(): List<RawTake> =
        _rawFlow.value.filter { it.kind.isBlank() || it.kind == "unknown" }

    // ---- Тренировки ----

    /**
     * Тренировка дня, одна на дату и блок. Идемпотентность живёт здесь: второй
     * наговор в тот же день дописывает УПРАЖНЕНИЯ в ту же тренировку, а не
     * заводит вторую.
     *
     * Пустой блок — это «блок ещё неизвестен», а не другой ключ. Утром на даче
     * плана в кэше может не быть (блок пустой), к обеду календарь приехал (блок
     * «C · полевой») — и без этого правила день раскалывался бы на ДВЕ
     * тренировки, которые потом обе писали бы себя в одну активность Garmin,
     * затирая друг друга: маркеры-то одни. Поэтому: нашёлся день с пустым
     * блоком — дописываем его и заодно доучиваем блоку; просят пустой блок, а
     * день уже есть с настоящим — отдаём настоящий.
     */
    suspend fun sessionFor(date: String, block: String, title: String): Session =
        mutex.withLock {
            ensureLoaded()
            val sameDay = _sessionsFlow.value.filter { it.date == date }
            val existing = sameDay.firstOrNull { it.block == block }
                ?: sameDay.firstOrNull { it.block.isBlank() }
                ?: sameDay.firstOrNull { block.isBlank() }
            if (existing != null) {
                // День был записан до того, как приехал план: доучиваем блок и
                // название, чтобы карточка и intervals звали его по-настоящему.
                if (existing.block.isBlank() && block.isNotBlank()) {
                    var upgraded: Session = existing
                    write(
                        _sessionsFlow.value.map { s ->
                            if (s.id != existing.id) s
                            else s.copy(
                                block = block,
                                title = s.title.ifBlank { title },
                            ).also { upgraded = it }
                        }
                    )
                    return@withLock upgraded
                }
                return@withLock existing
            }
            val now = System.currentTimeMillis()
            val session = Session(
                id = now,
                date = date,
                block = block,
                title = title,
                exercises = emptyList(),
            )
            write(listOf(session) + _sessionsFlow.value)
            session
        }

    /**
     * Влить упражнения в тренировку.
     *
     * [replace] = true (обычный случай): упражнение с тем же id заменяется
     * целиком — повторная надиктовка того же подхода не удваивает его. Это и
     * есть идемпотентность «по дате плюс упражнению».
     * [replace] = false: подходы ДОПИСЫВАЮТСЯ к тем, что уже есть — это
     * «сделал ещё два подхода», отдельное намерение, и модель помечает его
     * явно, а не мы догадываемся.
     */
    suspend fun mergeExercises(
        sessionId: Long,
        incoming: List<ExerciseLog>,
        replace: Boolean = true,
        rawId: Long = 0L,
    ): Session? = mutex.withLock {
        ensureLoaded()
        var result: Session? = null
        write(
            _sessionsFlow.value.map { s ->
                if (s.id != sessionId) return@map s
                val merged = s.exercises.toMutableList()
                for (fresh in incoming) {
                    val at = merged.indexOfFirst { it.exerciseId == fresh.exerciseId }
                    if (at < 0) {
                        merged.add(fresh)
                    } else if (replace) {
                        merged[at] = fresh
                    } else {
                        val old = merged[at]
                        merged[at] = old.copy(
                            rows = old.rows + fresh.rows,
                            note = listOf(old.note, fresh.note).filter { it.isNotBlank() }
                                .joinToString("; "),
                        )
                    }
                }
                // Порядок — как в плане: по номеру упражнения в справочнике
                // порядок задаёт вызывающий, здесь сохраняем как пришло.
                s.copy(
                    exercises = merged,
                    rawIds = if (rawId != 0L && rawId !in s.rawIds) s.rawIds + rawId else s.rawIds,
                    // Тренировка изменилась — донести её надо заново.
                    icuSynced = false,
                    attempts = 0,
                    lastError = "",
                ).also { result = it }
            }
        )
        result
    }

    suspend fun setFeel(sessionId: Long, feel: Int, rpe: Int, note: String): Session? =
        mutex.withLock {
            ensureLoaded()
            var result: Session? = null
            write(
                _sessionsFlow.value.map { s ->
                    if (s.id != sessionId) s
                    else s.copy(
                        feel = if (feel in 1..5) feel else s.feel,
                        rpe = if (rpe in 1..10) rpe else s.rpe,
                        note = note.ifBlank { s.note },
                        icuSynced = false,
                        attempts = 0,
                    ).also { result = it }
                }
            )
            result
        }

    suspend fun setDone(sessionId: Long, done: Boolean, minutes: Int = 0): Session? =
        mutex.withLock {
            ensureLoaded()
            var result: Session? = null
            write(
                _sessionsFlow.value.map { s ->
                    if (s.id != sessionId) s
                    else s.copy(
                        done = done,
                        minutes = if (minutes > 0) minutes else s.minutes,
                        icuSynced = false,
                    ).also { result = it }
                }
            )
            result
        }

    suspend fun dropExercise(sessionId: Long, exerciseId: String) = mutex.withLock {
        ensureLoaded()
        write(
            _sessionsFlow.value.map { s ->
                if (s.id != sessionId) s
                else s.copy(
                    exercises = s.exercises.filterNot { it.exerciseId == exerciseId },
                    icuSynced = false,
                )
            }
        )
    }

    suspend fun replaceRows(sessionId: Long, exerciseId: String, rows: List<SetRow>) =
        mutex.withLock {
            ensureLoaded()
            write(
                _sessionsFlow.value.map { s ->
                    if (s.id != sessionId) s
                    else s.copy(
                        exercises = s.exercises.map {
                            if (it.exerciseId == exerciseId) it.copy(rows = rows) else it
                        },
                        icuSynced = false,
                    )
                }
            )
        }

    /** Отметки об отправке: активность Garmin, куда дописали, или NOTE-событие. */
    suspend fun markSynced(sessionId: Long, activityId: String, noteId: String) = mutex.withLock {
        ensureLoaded()
        write(
            _sessionsFlow.value.map { s ->
                if (s.id != sessionId) s
                else s.copy(
                    icuActivityId = activityId.ifBlank { s.icuActivityId },
                    icuNoteId = noteId.ifBlank { s.icuNoteId },
                    icuSynced = true,
                    lastError = "",
                )
            }
        )
    }

    suspend fun markAttempt(sessionId: Long, error: String) = mutex.withLock {
        ensureLoaded()
        write(
            _sessionsFlow.value.map { s ->
                if (s.id != sessionId) s
                else s.copy(attempts = s.attempts + 1, lastError = error)
            }
        )
    }

    suspend fun deleteSession(sessionId: Long) = mutex.withLock {
        ensureLoaded()
        // allowEmpty: удалить последнюю тренировку — законное действие. Сырые
        // надиктовки при этом остаются: они и есть незаменимое.
        write(_sessionsFlow.value.filterNot { it.id == sessionId }, allowEmpty = true)
    }

    fun sessionById(id: Long): Session? = _sessionsFlow.value.firstOrNull { it.id == id }

    fun sessionsOn(date: String): List<Session> = _sessionsFlow.value.filter { it.date == date }

    /** Ждут отправки в intervals — их подбирает фоновый досыл. */
    fun pendingSync(): List<Session> = _sessionsFlow.value.filter { it.pendingSync }

    /**
     * Прошлый раз по этому упражнению — то, без чего прогрессивной перегрузки
     * не существует. Ищем строго РАНЬШЕ [beforeDate], иначе сегодняшняя запись
     * стала бы сама себе прошлым разом.
     */
    fun lastTime(exerciseId: String, beforeDate: String): Pair<Session, ExerciseLog>? =
        _sessionsFlow.value
            .filter { it.date < beforeDate }
            .sortedByDescending { it.date }
            .firstNotNullOfOrNull { s ->
                s.exercises.firstOrNull { it.exerciseId == exerciseId }?.let { s to it }
            }

    /** История по упражнению, свежее первым — для графика прогрессии. */
    fun history(exerciseId: String, limit: Int = 12): List<Pair<String, ExerciseLog>> =
        _sessionsFlow.value
            .sortedByDescending { it.date }
            .mapNotNull { s -> s.exercises.firstOrNull { it.exerciseId == exerciseId }?.let { s.date to it } }
            .take(limit)

    // ---- GTG ----

    suspend fun putGtg(
        date: String,
        charged: Boolean? = null,
        hangSec: Int? = null,
        negatives: Int? = null,
        scapular: Int? = null,
        pullups: Int? = null,
        knee: String? = null,
        note: String? = null,
        /**
         * true — числа ЗАМЕНЯЮТ записанные (ручная правка в диалоге: только так
         * чинится ослышка «вис 400 секунд», иначе она травила бы лучший вис
         * вечно). false — берём максимум: голосовая вечерняя попытка на 20 сек
         * не должна портить утреннюю на 45.
         */
        replace: Boolean = false,
    ): GtgDay = mutex.withLock {
        ensureLoaded()
        val old = _gtgFlow.value.firstOrNull { it.date == date }
        fun best(fresh: Int?, was: Int): Int =
            if (replace && fresh != null) fresh else maxOf(fresh ?: 0, was)
        val fresh = GtgDay(
            date = date,
            charged = charged ?: old?.charged ?: false,
            hangSec = best(hangSec, old?.hangSec ?: 0),
            negatives = best(negatives, old?.negatives ?: 0),
            scapular = best(scapular, old?.scapular ?: 0),
            pullups = best(pullups, old?.pullups ?: 0),
            // Колено — наоборот, ПОСЛЕДНЕЕ сказанное: «к вечеру отпустило»
            // должно перебивать утреннее «ноет», а не проигрывать максимуму.
            knee = knee?.trim()?.ifBlank { old?.knee.orEmpty() } ?: old?.knee.orEmpty(),
            // Заметки дня КОПЯТСЯ, а не затираются: «вис тяжело» утром и
            // «негативы легче» вечером — обе нужны тому, кто правит план.
            note = appendNote(old?.note.orEmpty(), note),
            ts = System.currentTimeMillis(),
            doneIds = old?.doneIds ?: emptyList(),
            // День изменился — довезти его в intervals заново.
            icuSynced = false,
        )
        _gtgFlow.value = (_gtgFlow.value.filterNot { it.date == date } + fresh)
            .sortedByDescending { it.date }
        persist()
        fresh
    }

    fun gtgOn(date: String): GtgDay? = _gtgFlow.value.firstOrNull { it.date == date }

    /** Дописать заметку к уже записанным; дубль и пустота не дописываются. */
    private fun appendNote(old: String, fresh: String?): String {
        val add = fresh?.trim().orEmpty()
        return when {
            add.isBlank() -> old
            old.isBlank() -> add
            old.contains(add) -> old
            else -> "$old; $add"
        }
    }

    /** Галочка одного упражнения зарядки: тап ставит, повторный тап снимает. */
    suspend fun toggleGtgItem(date: String, exerciseId: String): GtgDay = mutex.withLock {
        ensureLoaded()
        val old = _gtgFlow.value.firstOrNull { it.date == date } ?: GtgDay(date = date)
        val ids = if (exerciseId in old.doneIds) old.doneIds - exerciseId
        else old.doneIds + exerciseId
        val fresh = old.copy(doneIds = ids, ts = System.currentTimeMillis(), icuSynced = false)
        _gtgFlow.value = (_gtgFlow.value.filterNot { it.date == date } + fresh)
            .sortedByDescending { it.date }
        persist()
        fresh
    }

    /** Галочка упражнения силовой без чисел: «сделал по схеме». */
    suspend fun toggleChecked(sessionId: Long, exerciseId: String): Session? = mutex.withLock {
        ensureLoaded()
        var result: Session? = null
        write(
            _sessionsFlow.value.map { s ->
                if (s.id != sessionId) s
                else s.copy(
                    checkedIds = if (exerciseId in s.checkedIds) s.checkedIds - exerciseId
                    else s.checkedIds + exerciseId,
                ).also { result = it }
            }
        )
        result
    }

    /**
     * Длина непрерывной цепочки зарядки, считая назад от [today]. Сегодняшний
     * день, если он ещё пустой, цепочку НЕ рвёт: утро не кончилось.
     */
    fun streak(today: String): Int {
        val done = _gtgFlow.value.filter { it.charged }.map { it.date }.toSet()
        var count = 0
        var cursor = today
        if (cursor !in done) cursor = dayBefore(cursor)   // сегодня ещё можно успеть
        while (cursor in done) {
            count++
            cursor = dayBefore(cursor)
        }
        return count
    }

    /** Лучший вис за всё время — метрика пути к первому подтягиванию. */
    fun bestHang(): Int = _gtgFlow.value.maxOfOrNull { it.hangSec } ?: 0

    /** Лучшие подтягивания за всё время. Больше нуля = цель №2 взята. */
    fun bestPullups(): Int = _gtgFlow.value.maxOfOrNull { it.pullups } ?: 0

    /** Дни зарядки, не доехавшие в intervals, свежие первыми. */
    fun gtgNeedingSync(): List<GtgDay> =
        _gtgFlow.value.filter { it.any && !it.icuSynced }.sortedByDescending { it.date }

    suspend fun markGtgSynced(date: String) = mutex.withLock {
        ensureLoaded()
        _gtgFlow.value = _gtgFlow.value.map {
            if (it.date == date) it.copy(icuSynced = true) else it
        }
        persist()
    }

    fun recentGtg(days: Int): List<GtgDay> {
        val from = dayKey(System.currentTimeMillis() - days * 86_400_000L)
        return _gtgFlow.value.filter { it.date >= from }.sortedByDescending { it.date }
    }

    // ---- Диск ----

    private suspend fun ensureLoaded() {
        if (loaded) return
        val parsed = withContext(Dispatchers.IO) {
            StoreFiles.readOrQuarantine(file) { text -> parse(JSONObject(text)) }
        }
        loaded = true
        if (parsed != null) {
            _sessionsFlow.value = parsed.sessions
            _rawFlow.value = parsed.raw
            _gtgFlow.value = parsed.gtg
        }
    }

    private class Snapshot(
        val sessions: List<Session>,
        val raw: List<RawTake>,
        val gtg: List<GtgDay>,
    )

    private fun write(list: List<Session>, allowEmpty: Boolean = false) {
        if (list.isEmpty() && !allowEmpty && _sessionsFlow.value.isNotEmpty()) {
            logger?.invoke(
                "силовые: запись пустого журнала поверх ${_sessionsFlow.value.size} тренировок отклонена"
            )
            return
        }
        _sessionsFlow.value = list.sortedByDescending { it.date }
        persist()
    }

    private fun persist() {
        // Сырые надиктовки обрезаются по сроку, но НИКОГДА по «уже разобрано»:
        // разбор можно переиграть, сказанное — нет.
        val cutoff = dayKey(System.currentTimeMillis() - KEEP_RAW_DAYS * 86_400_000L)
        _rawFlow.value = _rawFlow.value.filter { dayKey(it.ts) >= cutoff }
        val json = serialize().toString()
        DiskWriter.post { StoreFiles.writeAtomic(file, json) }
    }

    private fun serialize(): JSONObject = JSONObject().apply {
        put("sessions", JSONArray().apply { _sessionsFlow.value.forEach { put(sessionJson(it)) } })
        put("raw", JSONArray().apply { _rawFlow.value.forEach { put(rawJson(it)) } })
        put("gtg", JSONArray().apply { _gtgFlow.value.forEach { put(gtgJson(it)) } })
    }

    private fun sessionJson(s: Session) = JSONObject().apply {
        put("id", s.id)
        put("date", s.date)
        put("block", s.block)
        put("title", s.title)
        put("feel", s.feel)
        put("rpe", s.rpe)
        put("note", s.note)
        put("minutes", s.minutes)
        put("activityId", s.icuActivityId)
        put("synced", s.icuSynced)
        put("noteId", s.icuNoteId)
        put("attempts", s.attempts)
        put("error", s.lastError)
        put("done", s.done)
        put("rawIds", JSONArray().apply { s.rawIds.forEach { put(it) } })
        put("checked", JSONArray().apply { s.checkedIds.forEach { put(it) } })
        put(
            "exercises",
            JSONArray().apply {
                for (e in s.exercises) put(
                    JSONObject().apply {
                        put("id", e.exerciseId)
                        put("name", e.name)
                        put("unit", e.unit)
                        put("note", e.note)
                        put(
                            "rows",
                            JSONArray().apply {
                                for (r in e.rows) put(
                                    JSONObject().apply {
                                        put("a", r.amount)
                                        put("w", r.weightKg)
                                        put("n", r.note)
                                    }
                                )
                            }
                        )
                    }
                )
            }
        )
    }

    private fun rawJson(r: RawTake) = JSONObject().apply {
        put("id", r.id)
        put("ts", r.ts)
        put("text", r.text)
        put("kind", r.kind)
        put("source", r.source)
        put("consumedBy", r.consumedBy)
        put("error", r.error)
    }

    private fun gtgJson(g: GtgDay) = JSONObject().apply {
        put("date", g.date)
        put("charged", g.charged)
        put("hang", g.hangSec)
        put("neg", g.negatives)
        put("scap", g.scapular)
        put("pullups", g.pullups)
        put("knee", g.knee)
        put("note", g.note)
        put("ts", g.ts)
        put("doneIds", JSONArray().apply { g.doneIds.forEach { put(it) } })
        put("icu", g.icuSynced)
    }

    private fun parse(o: JSONObject): Snapshot {
        val sessions = mutableListOf<Session>()
        o.optJSONArray("sessions")?.let { a ->
            for (i in 0 until a.length()) {
                val s = a.optJSONObject(i) ?: continue
                val exercises = mutableListOf<ExerciseLog>()
                s.optJSONArray("exercises")?.let { ea ->
                    for (j in 0 until ea.length()) {
                        val e = ea.optJSONObject(j) ?: continue
                        val rows = mutableListOf<SetRow>()
                        e.optJSONArray("rows")?.let { ra ->
                            for (k in 0 until ra.length()) {
                                val r = ra.optJSONObject(k) ?: continue
                                rows.add(
                                    SetRow(
                                        amount = r.optInt("a"),
                                        weightKg = r.optDouble("w", 0.0),
                                        note = r.optString("n"),
                                    )
                                )
                            }
                        }
                        exercises.add(
                            ExerciseLog(
                                exerciseId = e.optString("id"),
                                name = e.optString("name"),
                                unit = e.optString("unit").ifBlank { ExerciseBook.UNIT_REPS },
                                rows = rows,
                                note = e.optString("note"),
                            )
                        )
                    }
                }
                val rawIds = mutableListOf<Long>()
                s.optJSONArray("rawIds")?.let { ra ->
                    for (k in 0 until ra.length()) rawIds.add(ra.optLong(k))
                }
                val checked = mutableListOf<String>()
                s.optJSONArray("checked")?.let { ca ->
                    for (k in 0 until ca.length()) {
                        ca.optString(k).takeIf { it.isNotBlank() }?.let { checked.add(it) }
                    }
                }
                sessions.add(
                    Session(
                        id = s.optLong("id"),
                        date = s.optString("date"),
                        block = s.optString("block"),
                        title = s.optString("title"),
                        exercises = exercises,
                        feel = s.optInt("feel"),
                        rpe = s.optInt("rpe"),
                        note = s.optString("note"),
                        minutes = s.optInt("minutes"),
                        icuActivityId = s.optString("activityId"),
                        icuSynced = s.optBoolean("synced", false),
                        icuNoteId = s.optString("noteId"),
                        attempts = s.optInt("attempts"),
                        lastError = s.optString("error"),
                        rawIds = rawIds,
                        done = s.optBoolean("done", false),
                        checkedIds = checked,
                    )
                )
            }
        }
        val raw = mutableListOf<RawTake>()
        o.optJSONArray("raw")?.let { a ->
            for (i in 0 until a.length()) {
                val r = a.optJSONObject(i) ?: continue
                raw.add(
                    RawTake(
                        id = r.optLong("id"),
                        ts = r.optLong("ts"),
                        text = r.optString("text"),
                        kind = r.optString("kind"),
                        source = r.optString("source"),
                        consumedBy = r.optLong("consumedBy"),
                        error = r.optString("error"),
                    )
                )
            }
        }
        val gtg = mutableListOf<GtgDay>()
        o.optJSONArray("gtg")?.let { a ->
            for (i in 0 until a.length()) {
                val g = a.optJSONObject(i) ?: continue
                val doneIds = mutableListOf<String>()
                g.optJSONArray("doneIds")?.let { da ->
                    for (k in 0 until da.length()) {
                        da.optString(k).takeIf { it.isNotBlank() }?.let { doneIds.add(it) }
                    }
                }
                gtg.add(
                    GtgDay(
                        date = g.optString("date"),
                        charged = g.optBoolean("charged", false),
                        hangSec = g.optInt("hang"),
                        negatives = g.optInt("neg"),
                        scapular = g.optInt("scap"),
                        pullups = g.optInt("pullups"),
                        knee = g.optString("knee"),
                        note = g.optString("note"),
                        ts = g.optLong("ts"),
                        doneIds = doneIds,
                        icuSynced = g.optBoolean("icu", false),
                    )
                )
            }
        }
        return Snapshot(
            sessions = sessions.sortedByDescending { it.date },
            raw = raw.sortedByDescending { it.ts },
            gtg = gtg.sortedByDescending { it.date },
        )
    }
}

/** «2026-08-23» → «2026-08-22». Календарь, а не арифметика на миллисекундах. */
internal fun dayBefore(date: String): String {
    val parsed = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(date)
    }.getOrNull() ?: return date
    val cal = java.util.Calendar.getInstance().apply {
        time = parsed
        add(java.util.Calendar.DAY_OF_YEAR, -1)
    }
    return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
}

/** Вес без лишних нулей: «16», «17.5». */
internal fun fmtWeight(kg: Double): String =
    if (kg == Math.floor(kg)) "${kg.toInt()} кг"
    else String.format(java.util.Locale.US, "%.1f кг", kg)
