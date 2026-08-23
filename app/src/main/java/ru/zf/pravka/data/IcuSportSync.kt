package ru.zf.pravka.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

// intervals.icu → вкладка «Спорт», и обратно: КБЖУ дня → wellness.
//
// Это ВТОРАЯ дорога к тому же API, и разделение сознательное. IcuSweeper
// работает на ленту Засечки: два дня назад, минимум полей, ни строчки лишней -
// он трогает незаменимые данные, и его хочется держать скучным. Здесь наоборот:
// широкая выгрузка на 120 дней в кэш, который можно потерять без последствий.
//
// Выгрузка двух видов, чтобы не платить сетью за одно и то же:
//   мелкая  - последние 10 дней, каждые 30 минут (сегодня дописывается весь день);
//   глубокая - всё окно, раз в сутки и когда кэш пуст (после установки APK).
class IcuSportSync(
    private val settings: Settings,
    private val store: SportStore,
    private val client: OkHttpClient,
    private val eventLog: EventLog,
) {

    companion object {
        private const val PERIOD_MS = 30 * 60_000L
        private const val DEEP_PERIOD_MS = 24 * 3_600_000L
        private const val SHALLOW_DAYS = 10
        private const val BASE = "https://intervals.icu/api/v1/athlete"
        private const val ACTIVITY = "https://intervals.icu/api/v1/activity"

        // Наш блок в описании активности. Границы нужны для идемпотентности:
        // повторная отправка ЗАМЕНЯЕТ то, что между ними, а не дописывает
        // второй раз. Без этого одна потерянная сеть удваивала бы журнал в
        // чужой системе, где чинить его руками неудобно.
        const val MARK_OPEN = "[Правка]"
        const val MARK_CLOSE = "[/Правка]"

        /**
         * Вставить [body] в описание активности вместо прошлого нашего блока.
         * Всё, что владелец написал в описании сам, остаётся как было.
         */
        fun spliceOwnBlock(existing: String, body: String): String {
            val block = MARK_OPEN + "\n" + body.trim() + "\n" + MARK_CLOSE
            val start = existing.indexOf(MARK_OPEN)
            val end = existing.indexOf(MARK_CLOSE)
            if (start >= 0 && end > start) {
                val before = existing.substring(0, start).trimEnd()
                val after = existing.substring(end + MARK_CLOSE.length).trimStart()
                return listOf(before, block, after).filter { it.isNotBlank() }.joinToString("\n\n")
            }
            val kept = existing.trim()
            return if (kept.isEmpty()) block else kept + "\n\n" + block
        }
    }

    private val running = AtomicBoolean(false)
    @Volatile private var lastRun = 0L
    @Volatile private var lastDeep = 0L
    @Volatile private var lastError = ""
    @Volatile private var arrived: List<SportStore.Workout> = emptyList()

    /**
     * Тренировки, приехавшие ПОСЛЕДНЕЙ выгрузкой и которых кэш раньше не
     * видел. Забираются один раз — служба показывает по ним уведомление
     * («пробежка приехала: пульс 152 против потолка 150») и просит feel.
     */
    fun takeArrived(): List<SportStore.Workout> {
        val list = arrived
        arrived = emptyList()
        return list
    }

    /** Что показать во вкладке, если выгрузка не удалась. */
    fun lastError(): String = lastError

    suspend fun configured(): Boolean =
        settings.icuAthlete().isNotBlank() && settings.icuKey().isNotBlank()

    /**
     * Обновить кэш. [force] - владелец сам потянул экран: идём в сеть, не
     * глядя на таймер, и делаем глубокую выгрузку.
     */
    suspend fun refresh(force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!force && now - lastRun < PERIOD_MS) return false
        if (!running.compareAndSet(false, true)) return false
        try {
            val done = withContext(Dispatchers.IO) { doRefresh(now, force) }
            if (done) lastRun = now
            return done
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            lastError = e.message ?: e.javaClass.simpleName
            eventLog.add("спорт: выгрузка упала — ${e.javaClass.simpleName}: ${e.message}")
            return false
        } finally {
            running.set(false)
        }
    }

    private suspend fun doRefresh(now: Long, force: Boolean): Boolean {
        val athlete = settings.icuAthlete().trim()
        val key = settings.icuKey().trim()
        if (athlete.isBlank() || key.isBlank()) {
            lastError = "Не заданы athlete id и ключ intervals.icu — «Настройки» → «Засечка»"
            return false
        }
        val auth = Credentials.basic("API_KEY", key)
        val keepDays = settings.sportDays()
        store.load()
        val deep = force || lastDeep == 0L || now - lastDeep > DEEP_PERIOD_MS ||
            store.workoutsFlow.value.isEmpty()
        val days = if (deep) keepDays else SHALLOW_DAYS
        val oldest = dayString(now - days * 86_400_000L)
        // Завтра как «newest»: тренировка, записанная вечером в часовом поясе
        // впереди нашего, иначе не попадает в окно.
        val newest = dayString(now + 86_400_000L)

        val workouts = fetchWorkouts(athlete, auth, oldest, newest)
        val health = fetchHealth(athlete, auth, oldest, newest)
        // Профиль тянем только на глубокой выгрузке: пороги и зоны меняются
        // раз в месяц, а не раз в полчаса.
        val profile = if (deep) fetchProfile(athlete, auth) else null

        if (workouts == null && health == null && profile == null) return false
        // Что нового: сравниваем с кэшем ДО слияния. Пустой кэш — не «всё
        // новое», а первая выгрузка: заваливать уведомлениями сто двадцать
        // дней истории нельзя.
        val known = store.workoutsFlow.value.map { it.id }.toSet()
        if (known.isNotEmpty() && workouts != null) {
            val freshCut = now - 48 * 3_600_000L
            arrived = workouts.filter { it.id !in known && it.start >= freshCut }
        }
        store.merge(workouts, health, profile, keepDays)
        if (deep) lastDeep = now
        lastError = ""
        eventLog.add(
            "спорт: " + (if (deep) "глубокая" else "мелкая") + " выгрузка за $days дн. — " +
                "тренировок ${workouts?.size ?: 0}, дней здоровья ${health?.size ?: 0}"
        )
        return true
    }

    // ---- Тренировки ----

    private fun fetchWorkouts(
        athlete: String,
        auth: String,
        oldest: String,
        newest: String,
    ): List<SportStore.Workout>? {
        val body = get("$BASE/$athlete/activities?oldest=$oldest&newest=$newest", auth) ?: return null
        val array = runCatching { JSONArray(body) }.getOrNull() ?: run {
            lastError = "intervals.icu ответил не списком тренировок — проверь athlete id"
            return null
        }
        // start_date_local приходит без зоны: разбираем в зоне телефона, где и
        // живут владелец с часами (то же правило, что в IcuSweeper).
        val parseFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val out = mutableListOf<SportStore.Workout>()
        for (i in 0 until array.length()) {
            val a = array.optJSONObject(i) ?: continue
            val id = a.optString("id")
            if (id.isBlank()) continue
            val start = runCatching { parseFormat.parse(a.optString("start_date_local"))?.time }
                .getOrNull() ?: continue
            val zones = mutableListOf<Int>()
            a.optJSONArray("icu_hr_zone_times")?.let { z ->
                // Приходят секунды по зонам — в минуты, чтобы не считать в UI.
                for (j in 0 until z.length()) zones.add((z.optInt(j) + 30) / 60)
            }
            val movingSeconds = a.optLong("moving_time", 0)
            val distance = a.optDouble("distance", 0.0)
            out.add(
                SportStore.Workout(
                    id = id,
                    start = start,
                    type = a.optString("type"),
                    name = a.optString("name").ifBlank { a.optString("type") },
                    seconds = a.optLong("elapsed_time", 0).takeIf { it > 0 } ?: movingSeconds,
                    movingSeconds = movingSeconds,
                    distanceM = distance,
                    elevationM = a.optDouble("total_elevation_gain", 0.0),
                    load = a.optInt("icu_training_load", 0),
                    intensity = a.optInt("icu_intensity", 0),
                    avgHr = a.optInt("average_heartrate", 0),
                    maxHr = a.optInt("max_heartrate", 0),
                    avgWatts = a.optInt("icu_average_watts", 0),
                    normWatts = a.optInt("icu_weighted_avg_watts", 0),
                    // Скорость приходит м/с; в темп переводим здесь, чтобы UI
                    // не занимался арифметикой на каждой перерисовке.
                    paceSecPerKm = paceOf(a.optDouble("average_speed", 0.0)),
                    gapSecPerKm = paceOf(a.optDouble("gap", 0.0)),
                    // Каденс бега intervals отдаёт «на одну ногу» — удваиваем до
                    // привычных шагов в минуту, которыми написано правило «168+».
                    cadence = a.optInt("average_cadence", 0).let { if (it in 1..130) it * 2 else it },
                    calories = a.optInt("calories", 0),
                    feel = a.optInt("feel", 0),
                    rpe = a.optInt("icu_rpe", 0),
                    decoupling = a.optDouble("decoupling", 0.0),
                    efficiency = a.optDouble("icu_efficiency_factor", 0.0),
                    zoneMinutes = zones,
                    icuUrl = "https://intervals.icu/activities/$id",
                )
            )
        }
        return out
    }

    /** м/с → сек/км; ноль и мусор дают ноль («темпа нет»). */
    private fun paceOf(metersPerSecond: Double): Int =
        if (metersPerSecond <= 0.1) 0 else (1000.0 / metersPerSecond).toInt().coerceIn(0, 3600)

    // ---- Здоровье ----

    private fun fetchHealth(
        athlete: String,
        auth: String,
        oldest: String,
        newest: String,
    ): List<SportStore.Health>? {
        val body = get("$BASE/$athlete/wellness?oldest=$oldest&newest=$newest", auth) ?: return null
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return null
        val out = mutableListOf<SportStore.Health>()
        for (i in 0 until array.length()) {
            val w = array.optJSONObject(i) ?: continue
            // Дата — это и есть id записи wellness.
            val date = w.optString("id").ifBlank { w.optString("date") }
            if (date.isBlank()) continue
            out.add(
                SportStore.Health(
                    date = date,
                    restingHr = w.optInt("restingHR", 0),
                    hrv = w.optInt("hrv", 0),
                    sleepHours = w.optLong("sleepSecs", 0).let {
                        if (it <= 0) 0.0 else it / 3600.0
                    },
                    sleepScore = w.optInt("sleepScore", 0),
                    sleepQuality = w.optInt("sleepQuality", 0),
                    steps = w.optInt("steps", 0),
                    weightKg = w.optDouble("weight", 0.0),
                    vo2max = w.optDouble("vo2max", 0.0),
                    ctl = w.optDouble("ctl", 0.0),
                    atl = w.optDouble("atl", 0.0),
                    readiness = w.optInt("readiness", 0),
                    kcal = w.optInt("kcalConsumed", 0),
                    protein = w.optInt("protein", 0),
                    fat = w.optInt("fatTotal", 0),
                    carbs = w.optInt("carbohydrates", 0),
                    comments = w.optString("comments"),
                )
            )
        }
        return out
    }

    // ---- Пороги и зоны ----

    /**
     * Профиль разбирается терпимо: intervals.icu кладёт пороги то в сам
     * объект атлета, то в `sportSettings`, и набор ключей у них разный по
     * видам спорта. Не нашли — оставляем ноль: вкладка и разбор без порогов
     * работают, просто говорят меньше.
     */
    private fun fetchProfile(athlete: String, auth: String): SportStore.Profile? {
        val body = get("$BASE/$athlete", auth) ?: return null
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        var settingsArray = o.optJSONArray("sportSettings")
        if (settingsArray == null) {
            val alt = get("$BASE/$athlete/sport-settings", auth)
            settingsArray = alt?.let { runCatching { JSONArray(it) }.getOrNull() }
        }
        var runFtp = 0
        var runLthr = 0
        var runMaxHr = 0
        var runPace = 0
        var rideFtp = 0
        var rideLthr = 0
        var swimPace = 0
        var runZones: List<Int> = emptyList()
        if (settingsArray != null) {
            for (i in 0 until settingsArray.length()) {
                val s = settingsArray.optJSONObject(i) ?: continue
                val types = mutableListOf<String>()
                s.optJSONArray("types")?.let { t ->
                    for (j in 0 until t.length()) types.add(t.optString(j))
                }
                val isRun = types.any { it.equals("Run", true) || it.equals("TrailRun", true) }
                val isRide = types.any { it.equals("Ride", true) || it.equals("VirtualRide", true) }
                val isSwim = types.any { it.equals("Swim", true) }
                // threshold_pace — метры в секунду.
                val pace = paceOf(s.optDouble("threshold_pace", 0.0))
                when {
                    isRun -> {
                        runFtp = s.optInt("ftp", 0)
                        runLthr = s.optInt("lthr", 0)
                        runMaxHr = s.optInt("max_hr", 0)
                        runPace = pace
                        val z = mutableListOf<Int>()
                        s.optJSONArray("hr_zones")?.let { a ->
                            for (j in 0 until a.length()) z.add(a.optInt(j))
                        }
                        runZones = z
                    }
                    isRide -> {
                        rideFtp = s.optInt("ftp", 0)
                        rideLthr = s.optInt("lthr", 0)
                    }
                    // «/100 м» вместо «/км»: плавание считают сотнями.
                    isSwim -> swimPace = if (pace > 0) pace / 10 else 0
                }
            }
        }
        return SportStore.Profile(
            athleteName = o.optString("name"),
            weightKg = o.optDouble("icu_weight", 0.0).takeIf { it > 0 } ?: o.optDouble("weight", 0.0),
            restingHr = o.optInt("icu_resting_hr", 0).takeIf { it > 0 } ?: o.optInt("resting_hr", 0),
            runFtp = runFtp,
            runLthr = runLthr,
            runMaxHr = runMaxHr,
            runThresholdPaceSecPerKm = runPace,
            rideFtp = rideFtp,
            rideLthr = rideLthr,
            swimThresholdPer100m = swimPace,
            hrZonesRun = runZones,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    // ---- Обратная дорога: КБЖУ дня в wellness ----

    /**
     * Итог дня уезжает в intervals.icu. Поля именно такие, какими их знает их
     * API: `kcalConsumed`, `carbohydrates`, `protein` и — вот это неочевидно —
     * `fatTotal`, а не `fat`.
     *
     * PUT частичный: приезжают только эти четыре поля, а сон, HRV и пульс,
     * которые туда пишут часы, остаются как были. Итог ДНЯ, а не приёма пищи:
     * каждый пуш перетирает предыдущий, поэтому считаем всю сумму заново.
     */
    suspend fun pushNutrition(
        date: String,
        kcal: Int,
        protein: Int,
        fat: Int,
        carbs: Int,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val athlete = settings.icuAthlete().trim()
            val key = settings.icuKey().trim()
            if (athlete.isBlank() || key.isBlank()) {
                throw IllegalStateException("Нет athlete id или ключа intervals.icu")
            }
            val payload = JSONObject().apply {
                put("id", date)
                put("kcalConsumed", kcal)
                put("carbohydrates", carbs)
                put("protein", protein)
                put("fatTotal", fat)
            }
            val request = Request.Builder()
                .url("$BASE/$athlete/wellness/$date")
                .header("Authorization", Credentials.basic("API_KEY", key))
                .put(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val text = response.body?.string()?.take(200).orEmpty()
                    throw IllegalStateException("intervals.icu: HTTP ${response.code} $text")
                }
            }
            eventLog.add("еда → intervals.icu: $date — $kcal ккал, Б$protein Ж$fat У$carbs")
        }
    }

    /**
     * Вклеить свой блок в комментарий wellness-дня. Комментарий — его поле:
     * читаем, что там есть, и заменяем ровно свой кусок между маркерами, как в
     * описаниях активностей. Не прочиталось — не пишем: затереть его слова
     * из-за одного неудачного GET нельзя.
     */
    suspend fun spliceWellnessComment(date: String, body: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val athlete = settings.icuAthlete().trim()
                val key = settings.icuKey().trim()
                if (athlete.isBlank() || key.isBlank()) {
                    throw IllegalStateException("Нет athlete id или ключа intervals.icu")
                }
                val auth = Credentials.basic("API_KEY", key)
                val existingBody = get("$BASE/$athlete/wellness/$date", auth)
                    ?: throw IllegalStateException("wellness-день не прочитался — запись отложена")
                val existing = runCatching { JSONObject(existingBody).optString("comments") }
                    .getOrDefault("")
                val payload = JSONObject().apply {
                    put("id", date)
                    put("comments", spliceOwnBlock(existing, body))
                }
                val request = Request.Builder()
                    .url("$BASE/$athlete/wellness/$date")
                    .header("Authorization", auth)
                    .put(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("intervals.icu: HTTP ${response.code}")
                    }
                }
                eventLog.add("зарядка → wellness $date: ${body.length} зн.")
            }
        }

    // ---- План: календарь intervals ----

    /**
     * Календарь на окно вокруг сегодня → кэш плана. Скелет дня приезжает
     * отсюда: название сессии, тип, длительность, нагрузка и нумерованный
     * список упражнений прямо в описании — владелец пушит их сам, когда
     * собирает блок.
     */
    suspend fun refreshPlan(store: PlanStore, back: Int = 21, ahead: Int = 21): Boolean =
        withContext(Dispatchers.IO) {
            val athlete = settings.icuAthlete().trim()
            val key = settings.icuKey().trim()
            if (athlete.isBlank() || key.isBlank()) return@withContext false
            val auth = Credentials.basic("API_KEY", key)
            val now = System.currentTimeMillis()
            val oldest = dayString(now - back * 86_400_000L)
            val newest = dayString(now + ahead * 86_400_000L)
            val body = get("$BASE/$athlete/events?oldest=$oldest&newest=$newest", auth)
                ?: return@withContext false
            val array = runCatching { JSONArray(body) }.getOrNull() ?: return@withContext false
            val out = mutableListOf<PlanStore.PlanDay>()
            for (i in 0 until array.length()) {
                val e = array.optJSONObject(i) ?: continue
                val local = e.optString("start_date_local")
                val date = local.substringBefore('T').ifBlank { continue }
                val seconds = e.optLong("moving_time", 0).takeIf { it > 0 }
                    ?: e.optLong("duration", 0)
                out.add(
                    PlanStore.PlanDay(
                        eventId = e.optString("id"),
                        date = date,
                        name = e.optString("name"),
                        type = e.optString("type"),
                        minutes = ((seconds + 30) / 60).toInt(),
                        load = e.optInt("icu_training_load", 0).takeIf { it > 0 }
                            ?: e.optInt("load_target", 0),
                        description = e.optString("description"),
                        carbsPerHour = e.optInt("carbs_per_hour", 0),
                    )
                )
            }
            if (out.isEmpty()) return@withContext false
            store.mergeDays(out)
            eventLog.add("план: календарь intervals — событий ${out.size} за $back+$ahead дн.")
            true
        }

    // ---- Обратная дорога: журнал подходов в активность Garmin ----

    /** Активность нужного типа за этот день, если часы её уже прислали. */
    suspend fun findActivity(date: String, type: String): String? = withContext(Dispatchers.IO) {
        val athlete = settings.icuAthlete().trim()
        val key = settings.icuKey().trim()
        if (athlete.isBlank() || key.isBlank()) return@withContext null
        val auth = Credentials.basic("API_KEY", key)
        val body = get("$BASE/$athlete/activities?oldest=$date&newest=$date", auth)
            ?: return@withContext null
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return@withContext null
        for (i in 0 until array.length()) {
            val a = array.optJSONObject(i) ?: continue
            if (!a.optString("type").equals(type, ignoreCase = true)) continue
            val id = a.optString("id")
            if (id.isNotBlank()) return@withContext id
        }
        null
    }

    /**
     * Текущее описание активности — чтобы заменить в нём наш блок, а не чужой
     * текст. null значит «прочитать не вышло», и это ОТМЕНЯЕТ запись: PUT с
     * пустым existing затёр бы то, что владелец написал в описании руками.
     * Не дописать сейчас — можно, досыл повторит; затереть чужое — нельзя.
     */
    private fun activityDescription(activityId: String, auth: String): String? {
        val body = get("$ACTIVITY/$activityId", auth) ?: return null
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return o.optString("description")
    }

    /**
     * Дописать журнал подходов к активности. Поля на запись у завершённой
     * активности небогатые: описание — единственный свободный текст, плюс
     * `feel` (1 отлично … 5 развалина, шкала перевёрнутая) и `icu_rpe` 1–10.
     *
     * Идемпотентно: наш блок в описании заменяется целиком, поэтому повторная
     * отправка после потерянной сети ничего не удваивает.
     */
    suspend fun writeSetLog(
        activityId: String,
        body: String,
        feel: Int = 0,
        rpe: Int = 0,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val key = settings.icuKey().trim()
            if (key.isBlank()) throw IllegalStateException("Нет ключа intervals.icu")
            val auth = Credentials.basic("API_KEY", key)
            val existing = activityDescription(activityId, auth)
                ?: throw IllegalStateException(
                    "не прочиталось описание активности — запись отложена, чтобы не затереть чужой текст"
                )
            val payload = JSONObject().apply {
                put("description", spliceOwnBlock(existing, body))
                if (feel in 1..5) put("feel", feel)
                if (rpe in 1..10) put("icu_rpe", rpe)
            }
            val request = Request.Builder()
                .url("$ACTIVITY/$activityId")
                .header("Authorization", auth)
                .put(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val text = response.body?.string()?.take(200).orEmpty()
                    throw IllegalStateException("intervals.icu: HTTP ${response.code} $text")
                }
            }
            eventLog.add("силовые → активность $activityId: ${body.length} зн.")
        }
    }

    /**
     * Самочувствие в активность — из уведомления «тренировка приехала».
     * Только feel, описание не трогаем вовсе.
     */
    suspend fun pushFeel(activityId: String, feel: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val key = settings.icuKey().trim()
                if (key.isBlank()) throw IllegalStateException("Нет ключа intervals.icu")
                require(feel in 1..5) { "feel вне шкалы" }
                val payload = JSONObject().put("feel", feel)
                val request = Request.Builder()
                    .url("$ACTIVITY/$activityId")
                    .header("Authorization", Credentials.basic("API_KEY", key))
                    .put(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("intervals.icu: HTTP ${response.code}")
                    }
                }
                eventLog.add("спорт: feel $feel → активность $activityId")
            }
        }

    /**
     * Запасная дорога: активности от часов так и не появилось, и оставлять
     * журнал только на телефоне нельзя. Тогда подходы ложатся NOTE-событием на
     * ту же дату — рядом с запланированной сессией, так что «план против
     * факта» всё равно видно в одном месте.
     *
     * [existingId] непустой — правим прежнюю заметку, а не плодим вторую.
     */
    suspend fun writeNote(
        date: String,
        name: String,
        body: String,
        existingId: String = "",
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val athlete = settings.icuAthlete().trim()
            val key = settings.icuKey().trim()
            if (athlete.isBlank() || key.isBlank()) {
                throw IllegalStateException("Нет athlete id или ключа intervals.icu")
            }
            val auth = Credentials.basic("API_KEY", key)
            val payload = JSONObject().apply {
                put("start_date_local", date + "T00:00:00")
                put("category", "NOTE")
                put("name", name)
                put("description", body)
            }
            val url = if (existingId.isBlank()) "$BASE/$athlete/events"
            else "$BASE/$athlete/events/$existingId"
            val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
            val builder = Request.Builder().url(url).header("Authorization", auth)
            val request = (if (existingId.isBlank()) builder.post(requestBody)
            else builder.put(requestBody)).build()
            val id = client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("intervals.icu: HTTP ${response.code} ${text.take(200)}")
                }
                runCatching { JSONObject(text).optString("id") }.getOrNull().orEmpty()
            }
            eventLog.add("силовые → заметка календаря $date (${id.ifBlank { "без id" }})")
            id.ifBlank { existingId }
        }
    }

    private fun get(url: String, auth: String): String? {
        val request = Request.Builder().url(url).header("Authorization", auth).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                lastError = when (response.code) {
                    401, 403 -> "intervals.icu не принял ключ (HTTP ${response.code})"
                    404 -> "intervals.icu: не нашёл атлета (HTTP 404) — проверь athlete id"
                    else -> "intervals.icu: HTTP ${response.code}"
                }
                eventLog.add("спорт: HTTP ${response.code} на $url")
                return null
            }
            return response.body?.string()
        }
    }

    private fun dayString(at: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(at))
}
