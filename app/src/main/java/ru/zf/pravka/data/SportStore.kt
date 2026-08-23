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

// Спорт: кэш тренировочной жизни владельца на диске (`sport.json`).
//
// Почему кэш, а не источник правды: настоящие данные живут в intervals.icu,
// куда их пишут часы. Здесь их копия, чтобы вкладка открывалась мгновенно и
// работала в самолёте - то же решение, что у кэша дел Todoist. Значит и
// дисциплина мягче, чем у ленты: этот файл можно потерять без последствий,
// он отрастёт со следующей выгрузкой. Пустой ответ сети сюда не пишется -
// это единственное железное правило (иначе одна ошибка API стирает вкладку).
//
// Разговор с ассистентом (`talks`) - НЕ кэш: это переписка владельца, и она
// живёт в том же файле, но при выгрузке никогда не перетирается.
class SportStore(private val context: Context) {

    companion object {
        const val FILE_NAME = "sport.json"
        // Сколько разговоров держим: последние сто вопросов - это уже история
        // «а что я спрашивал в марте», дальше не интересно.
        private const val KEEP_TALKS = 100
    }

    /**
     * Одна тренировка, как её отдаёт intervals.icu. Поля не обязательны -
     * силовая без пульсометра приезжает почти пустой, и это нормально:
     * ноль значит «неизвестно», а не «нуль ватт».
     */
    data class Workout(
        val id: String,
        val start: Long,            // epoch ms, местное время часов
        val type: String,           // "Run", "Ride", "WeightTraining"...
        val name: String,
        val seconds: Long,          // elapsed
        val movingSeconds: Long,
        val distanceM: Double,
        val elevationM: Double,
        val load: Int,              // icu_training_load (TSS-подобный)
        val intensity: Int,         // % от порога
        val avgHr: Int,
        val maxHr: Int,
        val avgWatts: Int,
        val normWatts: Int,
        val paceSecPerKm: Int,      // 0 = не бег/ходьба
        val gapSecPerKm: Int,       // с поправкой на рельеф
        val calories: Int,
        val feel: Int,              // 1..5, как владелец себя ощущал
        val rpe: Int,               // 1..10
        val decoupling: Double,     // Pw:HR, % расхождения
        val efficiency: Double,     // efficiency factor
        val zoneMinutes: List<Int>, // минуты по пульсовым зонам, z1..z7
        val icuUrl: String,
    ) {
        val minutes: Long get() = (seconds + 30) / 60
        val km: Double get() = distanceM / 1000.0
    }

    /** День здоровья: то, что часы намерили ночью, плюс еда, если она есть. */
    data class Health(
        val date: String,           // yyyy-MM-dd
        val restingHr: Int,
        val hrv: Int,
        val sleepHours: Double,
        val sleepScore: Int,
        val sleepQuality: Int,      // 1..4
        val steps: Int,
        val weightKg: Double,
        val vo2max: Double,
        val ctl: Double,            // тренированность
        val atl: Double,            // усталость
        val readiness: Int,
        val kcal: Int,              // из нашей же вкладки «Еда», если уехало
        val protein: Int,
        val fat: Int,
        val carbs: Int,
        val comments: String,
    ) {
        /** Форма (TSB): свежесть = тренированность − усталость. */
        val tsb: Double get() = ctl - atl
    }

    /** Пороги и зоны: без них разбор тренировки — цифры без смысла. */
    data class Profile(
        val athleteName: String = "",
        val weightKg: Double = 0.0,
        val restingHr: Int = 0,
        val runFtp: Int = 0,
        val runLthr: Int = 0,
        val runMaxHr: Int = 0,
        val runThresholdPaceSecPerKm: Int = 0,
        val rideFtp: Int = 0,
        val rideLthr: Int = 0,
        val swimThresholdPer100m: Int = 0,
        val hrZonesRun: List<Int> = emptyList(),
        val fetchedAt: Long = 0L,
    ) {
        val known: Boolean get() = fetchedAt > 0L
    }

    /** Вопрос владельца и ответ Опуса: переписка, а не кэш. */
    data class Talk(
        val id: Long,
        val ts: Long,
        val question: String,
        val answer: String,
        val costUsd: Double,
        val error: String = "",
    )

    private val mutex = Mutex()
    private val file: File get() = File(context.filesDir, FILE_NAME)
    private var loaded = false

    private val _workoutsFlow = MutableStateFlow<List<Workout>>(emptyList())
    val workoutsFlow: StateFlow<List<Workout>> = _workoutsFlow

    private val _healthFlow = MutableStateFlow<List<Health>>(emptyList())
    val healthFlow: StateFlow<List<Health>> = _healthFlow

    private val _profileFlow = MutableStateFlow(Profile())
    val profileFlow: StateFlow<Profile> = _profileFlow

    private val _talksFlow = MutableStateFlow<List<Talk>>(emptyList())
    val talksFlow: StateFlow<List<Talk>> = _talksFlow

    @Volatile private var syncedAt = 0L
    fun lastSyncAt(): Long = syncedAt

    suspend fun load() = mutex.withLock { ensureLoaded() }

    /**
     * Свежая выгрузка. Пустые списки НЕ принимаются: ответ без тренировок
     * почти всегда значит «сеть/ключ подвели», а не «владелец не тренируется»,
     * и затирать этим кэш нельзя. Приехавшее сливается по id/дате, поэтому
     * короткая выгрузка (двое суток) не съедает длинную историю.
     */
    suspend fun merge(
        workouts: List<Workout>?,
        health: List<Health>?,
        profile: Profile?,
        keepDays: Int,
    ) = mutex.withLock {
        ensureLoaded()
        val cutoff = System.currentTimeMillis() - keepDays.coerceAtLeast(7) * 86_400_000L
        if (!workouts.isNullOrEmpty()) {
            val byId = LinkedHashMap<String, Workout>()
            for (w in _workoutsFlow.value) byId[w.id] = w
            for (w in workouts) byId[w.id] = w      // свежая версия побеждает
            _workoutsFlow.value = byId.values
                .filter { it.start >= cutoff }
                .sortedByDescending { it.start }
        }
        if (!health.isNullOrEmpty()) {
            val byDate = LinkedHashMap<String, Health>()
            for (h in _healthFlow.value) byDate[h.date] = h
            for (h in health) byDate[h.date] = mergeHealth(byDate[h.date], h)
            _healthFlow.value = byDate.values.sortedByDescending { it.date }.take(420)
        }
        if (profile != null && profile.known) _profileFlow.value = profile
        syncedAt = System.currentTimeMillis()
        persist()
    }

    /**
     * Пришедшее из intervals.icu поверх старого дня. Ноль в свежем ответе -
     * это «поле не заполнено», а не «стало нулём»: часы дописывают день
     * порциями (сон утром, вес днём), и второй ответ не должен стирать то,
     * что принёс первый.
     */
    private fun mergeHealth(old: Health?, fresh: Health): Health {
        if (old == null) return fresh
        fun pick(a: Int, b: Int) = if (a != 0) a else b
        fun pickD(a: Double, b: Double) = if (a != 0.0) a else b
        return Health(
            date = fresh.date,
            restingHr = pick(fresh.restingHr, old.restingHr),
            hrv = pick(fresh.hrv, old.hrv),
            sleepHours = pickD(fresh.sleepHours, old.sleepHours),
            sleepScore = pick(fresh.sleepScore, old.sleepScore),
            sleepQuality = pick(fresh.sleepQuality, old.sleepQuality),
            steps = pick(fresh.steps, old.steps),
            weightKg = pickD(fresh.weightKg, old.weightKg),
            vo2max = pickD(fresh.vo2max, old.vo2max),
            ctl = pickD(fresh.ctl, old.ctl),
            atl = pickD(fresh.atl, old.atl),
            readiness = pick(fresh.readiness, old.readiness),
            kcal = pick(fresh.kcal, old.kcal),
            protein = pick(fresh.protein, old.protein),
            fat = pick(fresh.fat, old.fat),
            carbs = pick(fresh.carbs, old.carbs),
            comments = fresh.comments.ifBlank { old.comments },
        )
    }

    suspend fun addTalk(question: String, answer: String, costUsd: Double, error: String = ""): Talk =
        mutex.withLock {
            ensureLoaded()
            val now = System.currentTimeMillis()
            val talk = Talk(now, now, question, answer, costUsd, error)
            _talksFlow.value = (listOf(talk) + _talksFlow.value).take(KEEP_TALKS)
            persist()
            talk
        }

    suspend fun deleteTalk(id: Long) = mutex.withLock {
        ensureLoaded()
        _talksFlow.value = _talksFlow.value.filterNot { it.id == id }
        persist()
    }

    suspend fun clearTalks() = mutex.withLock {
        ensureLoaded()
        _talksFlow.value = emptyList()
        persist()
    }

    // ---- Выборки для вкладки и для промпта ----

    /** Тренировки за последние [days] дней, свежие сверху. */
    fun recentWorkouts(days: Int): List<Workout> {
        val from = System.currentTimeMillis() - days * 86_400_000L
        return _workoutsFlow.value.filter { it.start >= from }
    }

    fun healthOn(date: String): Health? = _healthFlow.value.firstOrNull { it.date == date }

    /** Последний непустой замер поля: HRV приходит не каждый день. */
    fun lastWeight(): Double = _healthFlow.value.firstOrNull { it.weightKg > 0 }?.weightKg ?: 0.0

    /**
     * Среднее по непустым значениям за окно — базовая линия для HRV и пульса.
     *
     * [skipDays] выбрасывает свежие дни из расчёта: сравнивать сегодняшний HRV
     * с базой, в которую он сам же и входит, значит гасить собственный сигнал.
     */
    fun average(days: Int, skipDays: Int = 0, of: (Health) -> Double): Double {
        val list = _healthFlow.value.drop(skipDays).take(days).map(of).filter { it > 0.0 }
        return if (list.isEmpty()) 0.0 else list.sum() / list.size
    }

    /** Нагрузка за неделю: сумма load по тренировкам последних 7 дней. */
    fun weekLoad(weeksAgo: Int = 0): Int {
        val end = System.currentTimeMillis() - weeksAgo * 7 * 86_400_000L
        val start = end - 7 * 86_400_000L
        return _workoutsFlow.value.filter { it.start in start..end }.sumOf { it.load }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val parsed = withContext(Dispatchers.IO) {
            StoreFiles.readOrQuarantine(file) { text -> parse(JSONObject(text)) }
        }
        loaded = true
        if (parsed != null) {
            _workoutsFlow.value = parsed.workouts
            _healthFlow.value = parsed.health
            _profileFlow.value = parsed.profile
            _talksFlow.value = parsed.talks
            syncedAt = parsed.syncedAt
        }
    }

    private class Snapshot(
        val workouts: List<Workout>,
        val health: List<Health>,
        val profile: Profile,
        val talks: List<Talk>,
        val syncedAt: Long,
    )

    private fun persist() {
        val json = serialize().toString()
        DiskWriter.post { StoreFiles.writeAtomic(file, json) }
    }

    private fun serialize(): JSONObject = JSONObject().apply {
        put("syncedAt", syncedAt)
        put("workouts", JSONArray().apply { _workoutsFlow.value.forEach { put(workoutJson(it)) } })
        put("health", JSONArray().apply { _healthFlow.value.forEach { put(healthJson(it)) } })
        put("profile", profileJson(_profileFlow.value))
        put("talks", JSONArray().apply { _talksFlow.value.forEach { put(talkJson(it)) } })
    }

    private fun workoutJson(w: Workout) = JSONObject().apply {
        put("id", w.id)
        put("start", w.start)
        put("type", w.type)
        put("name", w.name)
        put("sec", w.seconds)
        put("movSec", w.movingSeconds)
        put("dist", w.distanceM)
        put("elev", w.elevationM)
        put("load", w.load)
        put("intensity", w.intensity)
        put("avgHr", w.avgHr)
        put("maxHr", w.maxHr)
        put("watts", w.avgWatts)
        put("np", w.normWatts)
        put("pace", w.paceSecPerKm)
        put("gap", w.gapSecPerKm)
        put("kcal", w.calories)
        put("feel", w.feel)
        put("rpe", w.rpe)
        put("decoupling", w.decoupling)
        put("ef", w.efficiency)
        put("zones", JSONArray().apply { w.zoneMinutes.forEach { put(it) } })
        put("url", w.icuUrl)
    }

    private fun healthJson(h: Health) = JSONObject().apply {
        put("date", h.date)
        put("rhr", h.restingHr)
        put("hrv", h.hrv)
        put("sleepH", h.sleepHours)
        put("sleepScore", h.sleepScore)
        put("sleepQ", h.sleepQuality)
        put("steps", h.steps)
        put("weight", h.weightKg)
        put("vo2max", h.vo2max)
        put("ctl", h.ctl)
        put("atl", h.atl)
        put("readiness", h.readiness)
        put("kcal", h.kcal)
        put("protein", h.protein)
        put("fat", h.fat)
        put("carbs", h.carbs)
        put("comments", h.comments)
    }

    private fun profileJson(p: Profile) = JSONObject().apply {
        put("name", p.athleteName)
        put("weight", p.weightKg)
        put("rhr", p.restingHr)
        put("runFtp", p.runFtp)
        put("runLthr", p.runLthr)
        put("runMaxHr", p.runMaxHr)
        put("runPace", p.runThresholdPaceSecPerKm)
        put("rideFtp", p.rideFtp)
        put("rideLthr", p.rideLthr)
        put("swimPace", p.swimThresholdPer100m)
        put("hrZonesRun", JSONArray().apply { p.hrZonesRun.forEach { put(it) } })
        put("fetchedAt", p.fetchedAt)
    }

    private fun talkJson(t: Talk) = JSONObject().apply {
        put("id", t.id)
        put("ts", t.ts)
        put("q", t.question)
        put("a", t.answer)
        put("cost", t.costUsd)
        put("error", t.error)
    }

    private fun parse(o: JSONObject): Snapshot {
        val workouts = mutableListOf<Workout>()
        o.optJSONArray("workouts")?.let { a ->
            for (i in 0 until a.length()) {
                val w = a.optJSONObject(i) ?: continue
                val zones = mutableListOf<Int>()
                w.optJSONArray("zones")?.let { z -> for (j in 0 until z.length()) zones.add(z.optInt(j)) }
                workouts.add(
                    Workout(
                        id = w.optString("id"),
                        start = w.optLong("start"),
                        type = w.optString("type"),
                        name = w.optString("name"),
                        seconds = w.optLong("sec"),
                        movingSeconds = w.optLong("movSec"),
                        distanceM = w.optDouble("dist", 0.0),
                        elevationM = w.optDouble("elev", 0.0),
                        load = w.optInt("load"),
                        intensity = w.optInt("intensity"),
                        avgHr = w.optInt("avgHr"),
                        maxHr = w.optInt("maxHr"),
                        avgWatts = w.optInt("watts"),
                        normWatts = w.optInt("np"),
                        paceSecPerKm = w.optInt("pace"),
                        gapSecPerKm = w.optInt("gap"),
                        calories = w.optInt("kcal"),
                        feel = w.optInt("feel"),
                        rpe = w.optInt("rpe"),
                        decoupling = w.optDouble("decoupling", 0.0),
                        efficiency = w.optDouble("ef", 0.0),
                        zoneMinutes = zones,
                        icuUrl = w.optString("url"),
                    )
                )
            }
        }
        val health = mutableListOf<Health>()
        o.optJSONArray("health")?.let { a ->
            for (i in 0 until a.length()) {
                val h = a.optJSONObject(i) ?: continue
                health.add(
                    Health(
                        date = h.optString("date"),
                        restingHr = h.optInt("rhr"),
                        hrv = h.optInt("hrv"),
                        sleepHours = h.optDouble("sleepH", 0.0),
                        sleepScore = h.optInt("sleepScore"),
                        sleepQuality = h.optInt("sleepQ"),
                        steps = h.optInt("steps"),
                        weightKg = h.optDouble("weight", 0.0),
                        vo2max = h.optDouble("vo2max", 0.0),
                        ctl = h.optDouble("ctl", 0.0),
                        atl = h.optDouble("atl", 0.0),
                        readiness = h.optInt("readiness"),
                        kcal = h.optInt("kcal"),
                        protein = h.optInt("protein"),
                        fat = h.optInt("fat"),
                        carbs = h.optInt("carbs"),
                        comments = h.optString("comments"),
                    )
                )
            }
        }
        val p = o.optJSONObject("profile")
        val zonesRun = mutableListOf<Int>()
        p?.optJSONArray("hrZonesRun")?.let { z -> for (j in 0 until z.length()) zonesRun.add(z.optInt(j)) }
        val profile = if (p == null) Profile() else Profile(
            athleteName = p.optString("name"),
            weightKg = p.optDouble("weight", 0.0),
            restingHr = p.optInt("rhr"),
            runFtp = p.optInt("runFtp"),
            runLthr = p.optInt("runLthr"),
            runMaxHr = p.optInt("runMaxHr"),
            runThresholdPaceSecPerKm = p.optInt("runPace"),
            rideFtp = p.optInt("rideFtp"),
            rideLthr = p.optInt("rideLthr"),
            swimThresholdPer100m = p.optInt("swimPace"),
            hrZonesRun = zonesRun,
            fetchedAt = p.optLong("fetchedAt"),
        )
        val talks = mutableListOf<Talk>()
        o.optJSONArray("talks")?.let { a ->
            for (i in 0 until a.length()) {
                val t = a.optJSONObject(i) ?: continue
                talks.add(
                    Talk(
                        id = t.optLong("id"),
                        ts = t.optLong("ts"),
                        question = t.optString("q"),
                        answer = t.optString("a"),
                        costUsd = t.optDouble("cost", 0.0),
                        error = t.optString("error"),
                    )
                )
            }
        }
        return Snapshot(
            workouts = workouts.sortedByDescending { it.start },
            health = health.sortedByDescending { it.date },
            profile = profile,
            talks = talks.sortedByDescending { it.ts },
            syncedAt = o.optLong("syncedAt"),
        )
    }
}
