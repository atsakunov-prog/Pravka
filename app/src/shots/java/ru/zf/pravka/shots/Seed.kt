package ru.zf.pravka.shots

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.data.PhoneStore
import ru.zf.pravka.data.PlanStore
import ru.zf.pravka.data.SportStore
import ru.zf.pravka.data.StrengthStore
import ru.zf.pravka.data.ZasechkaStore
import ru.zf.pravka.data.phoneDayKey
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Calendar

/**
 * Правдоподобная жизнь для снимков экранов: рабочий день консультанта,
 * спорт, дела, еда, деньги. Всё выдумано — снимки уходят во внешний сервис
 * дизайна, поэтому ни настоящих клиентов, ни личных категорий ленты.
 */
internal object Seed {

    private const val MIN = 60_000L
    private const val HOUR = 3_600_000L

    fun dayStart(back: Int): Long = Calendar.getInstance().run {
        add(Calendar.DAY_OF_YEAR, -back)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    fun at(back: Int, hm: String): Long {
        val (h, m) = hm.split(':').map(String::toInt)
        return dayStart(back) + h * HOUR + m * MIN
    }

    fun iso(back: Int): String = phoneDayKey(dayStart(back))

    private fun today(): LocalDate = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault()).toLocalDate()
    private fun day(n: Long): String = today().plusDays(n).toString()

    // ------------------------------------------------------------ Засечка

    private data class B(
        val from: String, val title: String, val category: String,
        val client: String = "", val useful: Int = 0, val comment: String = "",
    )

    private const val BED = "23:30"

    private val WORKDAY = listOf(
        B("07:05", "Зарядка и душ", "Спорт: прочее"),
        B("07:30", "Собираю детей в школу и сад", "Семья", comment = "Младший сам оделся — рекорд"),
        B("08:15", "Отвожу младших", "Передвижение: пешком"),
        B("08:45", "Завтрак", "Еда"),
        B("09:10", "План недели и входящие", "Работа: планирование", useful = 4),
        B("09:50", "Созвон: бюджет на IV квартал", "Работа: звонки", "Бета Групп", 5, "Сценарий без кредита — к четвергу"),
        B("10:40", "Залип в ленте", "Потери"),
        B("10:55", "Финмодель: денежный поток 2027", "Работа: текущая", "Бета Групп", 5, "Свёл ДДС, осталась кредитная линия"),
        B("12:40", "Бег Z2, 5 км", "Спорт: бег"),
        B("13:25", "Обед", "Еда"),
        B("14:00", "Знакомство: управленческий учёт", "Работа: привлечение", "Орион Логистик", 4),
        B("14:50", "КП на управленческую отчётность", "Работа: текущая", "Орион Логистик", 4),
        B("16:30", "Пост в канал про ковенанты", "Работа: привлечение", useful = 3),
        B("17:10", "Разбор дел и почты", "Систематизация"),
        B("17:45", "Уроки со старшим", "Семья", useful = 4),
        B("18:30", "Ужин с семьёй", "Еда"),
        B("19:10", "Гитара", "Отдых"),
        B("20:00", "Укладываю младших", "Семья"),
        B("21:00", "Сериал с женой", "Отдых"),
        B("22:20", "Книга", "Чтение"),
        B("23:00", "Ютуб перед сном", "Потери"),
    )

    private val WEEKEND = listOf(
        B("08:30", "Завтрак с семьёй", "Еда"),
        B("09:15", "Шоссе, длинная вело", "Спорт: вело"),
        B("11:45", "Быт и покупки", "Быт"),
        B("13:30", "Обед", "Еда"),
        B("14:15", "С детьми в парке", "Семья"),
        B("17:30", "Друзья в гостях", "Социальное: внешнее"),
        B("21:00", "Кино", "Отдых"),
        B("22:40", "Книга", "Чтение"),
    )

    /** Лента подряд, от старого к новому: дыр нет — нет и «не размечено». */
    suspend fun ribbon(app: PravkaApp, days: Int = 28) {
        val z = app.zasechkaStore
        // Без двух личных категорий владельца: снимки уходят наружу.
        z.setCategories(ZasechkaStore.neutralCategories())
        z.setClients(listOf("Бета Групп", "Орион Логистик", "Северный Ветер"))
        val now = System.currentTimeMillis()
        for (k in days downTo 0) {
            val dow = Calendar.getInstance().apply { timeInMillis = dayStart(k) }.get(Calendar.DAY_OF_WEEK)
            val plan = if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) WEEKEND else WORKDAY
            val sFrom = at(k + 1, BED)
            val sTo = at(k, plan.first().from)
            if (sFrom >= now) break
            z.insertInterruption(sFrom, minOf(sTo, now), "сон", "Сон", resumePrevious = false)
            if (sTo >= now) break
            for ((i, b) in plan.withIndex()) {
                val start = at(k, b.from)
                val end = plan.getOrNull(i + 1)?.let { at(k, it.from) } ?: at(k, BED)
                if (start >= now) break
                val e = if (end > now) {
                    z.startEntry(start, b.title, b.title, b.category, b.client, b.useful, source = "voice")
                } else {
                    z.insertClosed(start, end, b.title, b.title, b.category, b.client, b.useful)
                }
                if (e != null && b.comment.isNotBlank()) z.setComment(e.id, b.comment)
            }
        }
        // Сегодняшняя ночь после полуночного разреза уходит в «не размечено»
        // (сон посеян задним числом, без событий экрана) — возвращаем её сну.
        for (e in z.all()) {
            if (e.open || e.category != "Не размечено") continue
            val h = Calendar.getInstance().apply { timeInMillis = e.start }.get(Calendar.HOUR_OF_DAY)
            if (h == 0 || h >= 23) z.update(e.copy(title = "сон", raw = "", category = "Сон", source = "auto"))
        }
    }

    // ------------------------------------------------------------ телефон

    private val APPS = mapOf(
        "org.telegram.messenger" to 52, "com.anthropic.claude" to 41, "us.zoom.videomeetings" to 33,
        "com.android.chrome" to 24, "com.google.android.youtube" to 16, "com.whatsapp" to 12,
        "com.google.android.gm" to 9, "com.notion.id" to 8, "ru.tinkoff.investing" to 6, "com.zwift.zwiftgame" to 4,
    )
    private val LABELS = mapOf(
        "com.anthropic.claude" to "Claude", "com.whatsapp" to "WhatsApp", "com.google.android.gm" to "Gmail",
        "com.notion.id" to "Notion", "ru.tinkoff.investing" to "Т-Инвестиции", "com.zwift.zwiftgame" to "Zwift",
    )

    suspend fun phone(app: PravkaApp, days: Int = 28) {
        val now = System.currentTimeMillis()
        val filled = (days downTo 0).associate { k ->
            val f = if (k == 0) ((now - at(0, "07:05")) / (16.5 * HOUR)).coerceIn(0.05, 1.0) else 0.85 + (k % 5) * 0.07
            iso(k) to PhoneStore.Day(
                screenMs = (215 * MIN * f).toLong(), pickups = (74 * f).toInt(), glances = (29 * f).toInt(),
                apps = APPS.mapValues { (it.value * MIN * f).toLong() },
                appSessions = APPS.mapValues { it.value / 4 + 1 },
                glanceApps = mapOf("org.telegram.messenger" to (12 * f).toInt(), "com.whatsapp" to (5 * f).toInt()),
                callsMs = (38 * MIN * f).toLong(), calls = maxOf(1, (6 * f).toInt()),
                callers = mapOf("Иван Петров" to 21 * MIN, "Ольга Смирнова" to 11 * MIN, "Жена" to 6 * MIN),
            )
        }
        app.phoneStore.backfillDays(filled)
        app.phoneStore.applySweep(emptyMap(), LABELS, PhoneStore.SweepState(lastSweep = now, lastCallSweep = now))
        app.phoneStore.setImmersive("us.zoom.videomeetings", "Работа: звонки")
    }

    // ------------------------------------------------------------ спорт

    /**
     * Погода — кэш Open-Meteo в базе (сети в снимках нет): свежий, на город
     * с завода, часы на десять дней и дневной прогноз. Вечером дождь, утро
     * холоднее, чем показывает градусник, — чтобы «ощущ.» было видно.
     */
    fun weather(app: PravkaApp) {
        val start = today()
        val time = JSONArray(); val temp = JSONArray(); val feels = JSONArray()
        val pr = JSONArray(); val prob = JSONArray(); val code = JSONArray()
        val dTime = JSONArray(); val dCode = JSONArray(); val dMax = JSONArray(); val dMin = JSONArray()
        val dfMax = JSONArray(); val dfMin = JSONArray(); val dSum = JSONArray(); val dProb = JSONArray()
        for (d in 0 until 10) {
            val day = start.plusDays(d.toLong())
            val base = 6.0 - d * 0.6 + (if (d % 3 == 1) 2.5 else 0.0)
            var hi = -99.0; var lo = 99.0; var fhi = -99.0; var flo = 99.0; var sum = 0.0; var pmax = 0
            for (h in 0 until 24) {
                val t = base + 5.0 * kotlin.math.sin((h - 9) / 24.0 * 2 * Math.PI).coerceAtLeast(-0.6)
                val wet = (d == 0 && h in 17..20) || (d == 3 && h in 8..15) || (d == 6 && h in 12..23)
                val p = if (wet) 0.8 else 0.0
                val pp = if (wet) 70 + h % 3 * 5 else (h * 7 + d * 11) % 30
                val f = t - 3.2 - (if (h < 10) 1.0 else 0.0)
                time.put("%sT%02d:00".format(day, h)); temp.put(t); feels.put(f); pr.put(p); prob.put(pp)
                code.put(if (wet) 61 else if (h in 10..15 && d % 2 == 0) 2 else 3)
                hi = maxOf(hi, t); lo = minOf(lo, t); fhi = maxOf(fhi, f); flo = minOf(flo, f); sum += p; pmax = maxOf(pmax, pp)
            }
            dTime.put(day.toString()); dCode.put(if (sum > 0) 61 else if (d % 2 == 0) 2 else 3)
            dMax.put(hi); dMin.put(lo); dfMax.put(fhi); dfMin.put(flo); dSum.put(sum); dProb.put(pmax)
        }
        val forecast = JSONObject()
            .put("hourly", JSONObject().put("time", time).put("temperature_2m", temp).put("apparent_temperature", feels)
                .put("precipitation", pr).put("precipitation_probability", prob).put("weather_code", code))
            .put("daily", JSONObject().put("time", dTime).put("weather_code", dCode)
                .put("temperature_2m_max", dMax).put("temperature_2m_min", dMin)
                .put("apparent_temperature_max", dfMax).put("apparent_temperature_min", dfMin)
                .put("precipitation_sum", dSum).put("precipitation_probability_max", dProb))
        val save = JSONObject().put("city", ru.zf.pravka.data.Settings.WEATHER_CITY_DEFAULT)
            .put("lat", 55.75).put("lon", 37.62).put("at", System.currentTimeMillis()).put("forecast", forecast)
        java.io.File(ru.zf.pravka.data.DataRoot.dir(app), "weather.json").writeText(save.toString())
    }

    suspend fun sport(app: PravkaApp) {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        fun atd(n: Long, h: Int, m: Int) = today().plusDays(n).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
        fun wo(
            id: String, start: Long, type: String, name: String, sec: Long, mov: Long, dist: Double, load: Int,
            hr: Int, maxHr: Int, pace: Int = 0, watts: Int = 0, cad: Int = 0, kcal: Int = 0, zones: List<Int> = emptyList(),
        ) = SportStore.Workout(
            id = id, start = start, type = type, name = name, seconds = sec, movingSeconds = mov,
            distanceM = dist, elevationM = if (type == "Run") 34.0 else 0.0, load = load, intensity = 75, avgHr = hr,
            maxHr = maxHr, avgWatts = watts, normWatts = if (watts > 0) watts + 7 else 0, paceSecPerKm = pace,
            gapSecPerKm = if (pace > 0) pace - 4 else 0, cadence = cad, calories = kcal, feel = 2, rpe = 4,
            decoupling = 3.2, efficiency = 1.14, zoneMinutes = zones, icuUrl = "https://intervals.icu/activities/$id",
        )
        val workouts = listOf(
            wo("i1002", atd(0, 12, 40), "Run", "Лёгкий бег 5 км", 1860, 1840, 5000.0, 44, 142, 156, pace = 368, cad = 168, kcal = 398, zones = listOf(4, 20, 6, 1, 0, 0, 0)),
            wo("i1001", atd(-1, 19, 0), "VirtualRide", "Zwift — Z2 60 мин", 3600, 3560, 28400.0, 52, 128, 146, watts = 148, cad = 86, kcal = 520),
            wo("i1000", atd(-2, 18, 40), "WeightTraining", "Гиря: спина и плечи", 2760, 2500, 0.0, 28, 112, 141, kcal = 260),
            wo("i0999", atd(-3, 7, 10), "Run", "Бег 6 км", 2220, 2190, 6000.0, 55, 145, 161, pace = 365, cad = 167, kcal = 480),
            wo("i0998", atd(-5, 10, 0), "Ride", "Шоссе: Крылатское", 6900, 6400, 52300.0, 96, 134, 168, watts = 162, cad = 84, kcal = 1210),
            wo("i0997", atd(-7, 18, 30), "WeightTraining", "Силовая A — гиря 16 кг", 2820, 2600, 0.0, 30, 115, 144, kcal = 270),
            wo("i0996", atd(-8, 7, 15), "Run", "Лёгкий бег 5 км", 1890, 1875, 5000.0, 45, 141, 155, pace = 375, cad = 166, kcal = 400),
        ).filter { it.start + it.seconds * 1000 < now }
        val hrv = intArrayOf(52, 47, 45, 44, 48, 46, 43, 47, 45, 46, 44, 49, 45, 46, 44, 47, 45, 46, 48, 44, 45, 46, 47, 45, 44, 46, 48, 45, 46)
        val rhr = intArrayOf(51, 54, 55, 54, 53, 55, 56, 54, 54, 53, 55, 54, 55, 54, 54, 53, 55, 54, 54, 55, 54, 53, 54, 55, 54, 53, 54, 55, 54)
        val sleep = doubleArrayOf(7.6, 6.9, 7.2, 6.6, 7.4, 7.0, 6.8, 7.3, 7.1, 6.5, 7.2, 7.5, 6.9, 7.0, 7.1, 6.8, 7.2, 7.0, 6.7, 7.3, 7.1, 7.0, 6.9, 7.2, 7.4, 6.8, 7.1, 7.0, 7.2)
        val health = (0 until 29).map { i ->
            SportStore.Health(
                date = day(-i.toLong()), restingHr = rhr[i], hrv = hrv[i], sleepHours = sleep[i],
                sleepScore = (sleep[i] * 11).toInt(), sleepQuality = 2, steps = if (i == 0) 9_420 else 8000 + (i * 737) % 5000,
                weightKg = Math.round((85.9 + i * 0.04) * 10) / 10.0, vo2max = if (i % 7 == 0) 44.0 else 0.0,
                ctl = 41.3 - i * 0.15, atl = if (i == 0) 44.0 else 38.0 + (i % 5) * 1.6, readiness = 72,
                kcal = 0, protein = 0, fat = 0, carbs = 0, comments = "",
            )
        }
        app.sportStore.merge(
            workouts, health,
            SportStore.Profile(
                athleteName = "Саша", weightKg = 86.0, restingHr = 53, runLthr = 162, runMaxHr = 178,
                runThresholdPaceSecPerKm = 325, rideFtp = 205, rideLthr = 158,
                hrZonesRun = listOf(131, 145, 154, 162, 168, 173, 178), fetchedAt = now,
            ),
            keepDays = 120,
        )

        app.exerciseBook.load()
        app.planStore.mergeDays(
            listOf(
                PlanStore.PlanDay(
                    eventId = "ev-${day(0)}-kb", date = day(0), name = "Силовая A — гиря 16 кг: ноги и задняя цепь",
                    type = "WeightTraining", minutes = 45, load = 35, time = "19:30", tags = listOf("v3", "гиря"),
                    description = """
                        Неделя 3 из 4 блока. Гиря 16 кг, техника важнее веса: последний повтор чистый.
                        1. Гоблет-присед 4×8: локти вниз, 3 сек вниз
                        2. Свинги с гирей 5×15: таз, а не руки
                        3. Румынская тяга с гирей 3×12
                        4. Болгарский сплит-присед 3×8 на ногу
                        5. Подъёмы на носки стоя 3×15
                        6. Скручивания с паузой 3×15
                        Отдых 60–90 сек. Задача дня — не устать, а сделать свинги взрывными.
                    """.trimIndent(),
                ),
                PlanStore.PlanDay(
                    eventId = "ev-${day(0)}-z", date = day(0), name = "Зарядка · 6 пунктов", type = "WeightTraining",
                    minutes = 15, load = 6, time = "07:05", tags = listOf("зарядка", "v3"),
                    description = """
                        Обычная утренняя, колено спокойное.
                        1. Суставы сверху вниз ~3 мин
                        2. Осанка: chin tuck ×10 · скольжения по стене ×10
                        3. Отжимания 2×6
                        4. Вис на турнике ×2 до предела — секунды в заметку
                        5. Подтягивания с резинкой ×3
                        6. Подъёмы коленей на турнике ×10 медленно
                        Минимум на плохое утро: 1 + 3.
                    """.trimIndent(),
                ),
                PlanStore.PlanDay(eventId = "ev-${day(0)}-run", date = day(0), name = "Лёгкий бег 30 мин — Z2", type = "Run", minutes = 30, load = 30, description = "Пульс до 145, каденс 166+.", time = "12:40"),
                PlanStore.PlanDay(eventId = "ev-${day(1)}-ride", date = day(1), name = "Zwift — Z2 60 мин", type = "VirtualRide", minutes = 60, load = 50, description = "Ровно, 140–150 Вт.", time = "19:00"),
                PlanStore.PlanDay(eventId = "ev-${day(2)}-run", date = day(2), name = "Лёгкий бег 40 мин — Z2", type = "Run", minutes = 40, load = 38, description = "Пульс до 145, каденс 166+.", time = "07:15"),
            ),
        )
        app.planStore.setRules(
            PlanStore.Rules(
                blockTitle = "Блок «Осень-26» · база", runHrCeiling = 145, greyZoneLow = 146,
                greyZoneHigh = 158, cadenceMin = 166, runsPerWeekMax = 3, hoursBetweenRuns = 48, fetchedAt = now,
            ),
        )

        val st = app.strengthStore
        suspend fun past(daysAgo: Long, kg: Double, reps: Int) {
            val s = st.sessionFor(day(-daysAgo), "A · дом", "Силовая A — гиря ${kg.toInt()} кг")
            fun log(id: String, name: String, sets: Int, n: Int, w: Double) =
                StrengthStore.ExerciseLog(id, name, rows = List(sets) { StrengthStore.SetRow(n, w) })
            st.mergeExercises(
                s.id,
                listOf(
                    log("goblet-prised-s-girey", "Гоблет-присед с гирей", 4, reps, kg),
                    log("svingi-s-girey", "Свинги с гирей", 5, 15, kg),
                    log("rumynskaya-tyaga-rdl-s-girey", "Румынская тяга (RDL) с гирей", 3, 12, kg),
                    log("bolgarskiy-split-prised", "Болгарский сплит-присед", 3, 8, 0.0),
                    log("podemy-na-noski-stoya", "Подъёмы на носки стоя", 3, 15, 0.0),
                    log("skruchivaniya-s-pauzoy", "Скручивания с паузой", 3, 15, 0.0),
                ),
            )
            st.setDone(s.id, done = true, minutes = 46)
            st.setFeel(s.id, feel = 2, rpe = 6, note = "")
            st.markSynced(s.id, activityId = "iA$daysAgo", noteId = "")
            delay(5)
        }
        past(14, 12.0, 8)
        past(7, 16.0, 6)
        for (i in 1..12L) st.putGtg(day(-i), charged = true, hangSec = 12 + (13 - i).toInt(), knee = "зелёный", feel = 2)
        for (id in listOf("sustavy-sverhu-vniz", "osanka-podborodok-nazad-skolzheniya-po-stene-gru", "otzhimaniya")) {
            st.toggleGtgItem(day(0), id)
        }
        st.putGtg(day(0), hangSec = 26, knee = "зелёный")
        // Красная строка «не заданы ключи intervals» снимку не нужна: синк считает, что уже идёт.
        val running = ru.zf.pravka.data.IcuSportSync::class.java.getDeclaredField("running").apply { isAccessible = true }
        (running.get(app.icuSportSync) as java.util.concurrent.atomic.AtomicBoolean).set(true)
    }

    // ------------------------------------------------------------ дела

    fun delaResponse(): JSONObject {
        fun d(n: Long) = day(n)
        fun ago(h: Long) = OffsetDateTime.now().minusHours(h).truncatedTo(ChronoUnit.SECONDS).toString()
        fun arr(vararg o: Any) = JSONArray().apply { o.forEach { put(it) } }
        val me = "sasha"
        var seq = 1000L
        fun project(id: String, name: String, sphere: String, kind: String, money: String, vararg al: String) =
            JSONObject().put("id", id).put("name", name).put("aliases", arr(*al)).put("sphere", sphere)
                .put("kind", kind).put("owner_id", me).put("money_default", money).put("rev", 1).put("seq", ++seq)
        fun person(id: String, name: String, short: String, org: String = "", role: String = "") = JSONObject().put("id", id).put("name", name)
            .put("short", short).put("aliases", JSONArray()).put("phones", JSONArray()).put("rev", 1).put("seq", ++seq)
            .put("org_id", org.ifBlank { null }).put("role", role.ifBlank { null })
        // CRM (задание 4): клиенты — с организацией, у клиента проекты-сделки, люди — из компаний.
        fun org(id: String, name: String) = JSONObject().put("id", id).put("name", name).put("aliases", JSONArray()).put("rev", 1).put("seq", ++seq)
        fun deal(id: String, project: String, name: String, stage: String, people: List<String>, team: List<String> = listOf("pe-lena")) =
            JSONObject().put("id", id).put("project_id", project).put("name", name).put("stage", stage)
                .put("person_ids", arr(*people.toTypedArray())).put("team_ids", arr(*team.toTypedArray()))
                .put("deal_type", "финмодель").put("lead_person_id", "pe-lena").put("rev", 1).put("seq", ++seq)
        fun task(num: Int, title: String, f: JSONObject.() -> Unit = {}) = JSONObject()
            .put("id", "t-$num").put("num", num).put("title", title).put("owner_id", me).put("created_by", me)
            .put("ball", "mine").put("status", "open").put("source", "voice")
            .put("created_at", ago(200)).put("updated_at", ago(6)).put("rev", 1).put("seq", ++seq).apply(f)
        fun sug(id: String, p: JSONObject, src: String, quote: String, batch: String = "", bt: String = "", status: String = "pending") =
            JSONObject().put("id", id).put("for_user", me).put("kind", "create").put("payload", p).put("source", src)
                .put("quote", quote).put("batch_ref", batch).put("batch_title", bt).put("status", status)
                .put("created_at", ago(14)).put("decided_at", if (status == "pending") JSONObject.NULL else ago(30))
                .put("rev", 1).put("seq", ++seq)
        val projects = arr(
            project("p-beta", "Бета Групп", "work", "client", "paid", "Бета").put("org_id", "o-beta"),
            project("p-orion", "Орион Логистик", "work", "client", "potential", "Орион").put("org_id", "o-orion"),
            project("p-zf", "Фирма: внутреннее", "work", "internal", "none"),
            project("p-home", "Дом", "home", "personal", "none"),
            project("p-dacha", "Дача", "home", "personal", "none"),
        )
        val people = arr(
            person("pe-ivan", "Иван Петров", "Иван", "o-beta", "финдиректор"), person("pe-olga", "Ольга Смирнова", "Ольга", "o-orion", "CEO"),
            person("pe-dima", "Дмитрий Кузнецов", "Дима", "o-buh", "бухгалтер"), person("pe-lena", "Елена Орлова", "Лена").put("user_id", "lena"),
            person("pe-anna", "Анна Белова", "Анна", "o-beta", "казначей"), person("pe-petr", "Пётр Ершов", "Пётр"),
        )
        val tasks = arr(
            task(61, "Бета: финмодель — сценарий без кредита") { put("project_id", "p-beta"); put("deal_id", "d-beta-model"); put("focus_on", d(0)); put("estimate_min", 60) },
            task(58, "Иван: созвон по бюджету IV квартала") { put("project_id", "p-beta"); put("person_id", "pe-ivan"); put("due_date", d(0)); put("due_time", "11:30"); put("labels", arr("звонок")); put("created_at", ago(3)); put("source", "meeting") },
            task(55, "Орион: КП на управленческий учёт") { put("project_id", "p-orion"); put("deal_id", "d-orion-uu"); put("due_date", d(-1)); put("estimate_min", 120) },
            task(63, "Дача: оплатить электричество за сентябрь") { put("project_id", "p-dacha"); put("due_date", d(0)); put("estimate_min", 5); put("created_at", ago(26)) },
            task(41, "Налоговый вычет: собрать чеки за лечение") { put("project_id", "p-home"); put("due_date", d(-17)) },
            task(57, "Иван: прислать выгрузку из 1С") { put("project_id", "p-beta"); put("ball", "waiting"); put("person_id", "pe-ivan"); put("waiting_since", d(-5)); put("nudge_on", d(0)) },
            task(60, "Ольга: подписанный договор на внедрение") { put("project_id", "p-orion"); put("ball", "waiting"); put("person_id", "pe-olga"); put("waiting_since", d(-9)) },
            task(62, "Дима: акт сверки за сентябрь") { put("project_id", "p-zf"); put("ball", "waiting"); put("person_id", "pe-dima"); put("waiting_since", d(-2)); put("nudge_on", d(3)) },
            task(66, "Иван: обсудить продление договора") { put("project_id", "p-beta"); put("ball", "agenda"); put("person_id", "pe-ivan") },
            task(64, "Бета: проверить платёжный календарь на октябрь") { put("project_id", "p-beta"); put("deal_id", "d-beta-refi") },
            task(65, "Отчёт ДДС: посмотреть шаблон от Лены") { put("project_id", "p-zf"); put("created_by", "lena"); put("created_at", ago(20)); put("source", "web") },
            task(59, "Автосервис: записаться на ТО") { put("estimate_min", 10); put("labels", arr("звонок")); put("created_at", ago(50)); put("source", "mcp") },
            task(67, "Дом: заказать фильтры для воды") { put("project_id", "p-home"); put("due_date", d(4)); put("created_at", ago(5)); put("source", "telegram") },
            task(68, "Продлить Контур") { put("project_id", "p-zf"); put("status", "done"); put("completed_at", ago(5)) },
            task(52, "Бета: отправить счёт за сентябрь") { put("project_id", "p-beta"); put("status", "done"); put("completed_at", ago(26)) },
        )
        val suggestions = arr(
            sug(
                "s-1", JSONObject().put("title", "Ольга: ответить про сроки внедрения").put("project_name", "Орион Логистик").put("person_name", "Ольга")
                    .put("ask", "Ответить сразу или после созвона с Ольгой?"),
                "telegram", "Саша, когда сможете начать? Нам бы до ноября",
            ),
            sug(
                "s-2", JSONObject().put("title", "Бета: пересчитать ковенанты по кредиту").put("project_name", "Бета Холдинг").put("due_date", d(2)),
                "meeting", "Посмотри ещё ковенанты, банк спросит", "meet-beta", "Встреча с Бета Групп",
            ),
            sug(
                "s-3", JSONObject().put("title", "Иван: прислать структуру долга").put("project_name", "Бета Групп").put("person_name", "Иван").put("ball", "waiting"),
                "meeting", "Я пришлю структуру долга до среды", "meet-beta", "Встреча с Бета Групп",
            ),
            sug("s-4", JSONObject().put("title", "Позвонить нотариусу"), "bot", "", status = "expired"),
            // «Сделано само» (задание 5): закрытие и уточнение по свежей встрече, с «как было».
            sug("s-5", JSONObject().put("auto", true), "telegram", "Лицензия продлена до октября 2027", status = "accepted")
                .put("kind", "close").put("task_id", "t-68").put("reason", "закрыто само").put("batch_title", "Telegram · сегодня")
                .put("result", JSONObject().put("status", "done").put("was", JSONObject().put("status", "open")).put("comment_id", "c-1")),
            sug("s-6", JSONObject().put("auto", true).put("person_name", "Ольга").put("note", "две версии: с внедрением и без"), "meeting",
                "Ольга: КП нужно к пятнице, не к концу месяца", "meet-orion", "Встреча с Орионом", status = "accepted")
                .put("kind", "update").put("task_id", "t-55").put("reason", "уточнено само")
                .put("result", JSONObject().put("title", "Орион: КП на управленческий учёт").put("due_date", d(-1))
                    .put("was", JSONObject().put("due_date", d(9))).put("comment_id", "c-2")),
        )
        val users = arr(
            JSONObject().put("id", "sasha").put("name", "Саша").put("role", "owner").put("seq", 1),
            JSONObject().put("id", "lena").put("name", "Лена").put("person_id", "pe-lena").put("role", "member").put("seq", 2),
        )
        return JSONObject().put("ok", true).put("full", true).put("seq", seq).put("today", d(0))
            .put("tasks", tasks).put("projects", projects).put("people", people).put("suggestions", suggestions)
            .put("users", users).put("comments", JSONArray())
            .put("orgs", arr(org("o-beta", "Бета Групп"), org("o-orion", "Орион Логистик"), org("o-buh", "Счётная палата плюс")))
            .put("deals", arr(
                deal("d-beta-model", "p-beta", "Бета Групп: финмодель 2027", "active", listOf("pe-ivan", "pe-anna")),
                deal("d-beta-refi", "p-beta", "Бета Групп: рефинансирование", "proposal", listOf("pe-ivan")),
                deal("d-orion-uu", "p-orion", "Орион: управленческий учёт", "lead", listOf("pe-olga")),
            ))
            .put("labels", arr("звонок", "письмо", "встреча"))
    }

    suspend fun dela(app: PravkaApp) {
        java.io.File(ru.zf.pravka.data.DataRoot.secrets(app), ru.zf.pravka.data.DelaSync.LINK_FILE).writeText(
            JSONObject().put("url", "https://dela.home.lan/").put("token", "t".repeat(40))
                .put("user", "sasha").put("name", "Саша").put("at", System.currentTimeMillis()).toString(),
        )
        app.settings.setDelaBackend(ru.zf.pravka.data.Settings.DELA_BACKEND_SERVER)
        app.delaStore.setMe("sasha")
        check(app.delaStore.applySync(delaResponse(), System.currentTimeMillis() - 4 * MIN).isEmpty())
        // Синк в сеть не ходит: его замок уже взят — строка «обновлено 4 мин назад» остаётся.
        val mutex = ru.zf.pravka.data.DelaSync::class.java.getDeclaredField("mutex").apply { isAccessible = true }
        (mutex.get(app.delaSync) as kotlinx.coroutines.sync.Mutex).tryLock()
    }

    // ------------------------------------------------------------ еда

    private suspend fun eat(app: PravkaApp, ts: Long, kind: String, raw: String, vararg items: ru.zf.pravka.core.MealItem) {
        if (ts > System.currentTimeMillis()) return
        val m = app.foodStore.add(ts = ts, kind = kind, raw = raw, items = items.toList(), note = "", source = "voice", photo = "", costUsd = 0.0, model = "")
        app.foodStore.confirm(m.id)
        Thread.sleep(3) // id = currentTimeMillis: два приёма в одну миллисекунду ломают список
    }

    /** День на ~145 г белка; без рыбы и морепродуктов. */
    suspend fun food(app: PravkaApp) {
        app.foodStore.load()
        app.settings.setFoodTargets(2400, 150, 80, 260)
        for (back in 6 downTo 0) {
            val k = back
            eat(
                app, at(k, "08:45"), "завтрак", "овсянка на молоке с протеином, банан и витамин D",
                ru.zf.pravka.core.MealItem("Овсянка на молоке 2,5 %", 230, 280, 12, 8, 40, fiber = 5, sureness = "примерно",
                    micro = mapOf("b1" to 0.45, "b2" to 0.35, "b9" to 20.0, "b12" to 0.8, "ca" to 230.0, "mg" to 90.0, "fe" to 2.2, "zn" to 2.0, "k" to 420.0, "se" to 12.0, "i" to 25.0, "na" to 80.0)),
                ru.zf.pravka.core.MealItem("Протеин сывороточный", 25, 98, 20, 1, 2, sureness = "точно", micro = mapOf("ca" to 110.0, "k" to 130.0, "na" to 45.0)),
                ru.zf.pravka.core.MealItem("Банан", 120, 107, 1, 0, 27, fiber = 3, sureness = "примерно", micro = mapOf("k" to 430.0, "b6" to 0.4, "vc" to 10.0, "mg" to 32.0, "b9" to 24.0)),
                ru.zf.pravka.core.MealItem("Витамин D3 2000 МЕ", 0, 0, 0, 0, 0, micro = mapOf("vd" to 50.0), pill = true),
                ru.zf.pravka.core.MealItem("Креатин", 5, 0, 0, 0, 0, pill = true),
            )
            eat(
                app, at(k, "13:25"), "обед", "куриная грудка с рисом и салат",
                ru.zf.pravka.core.MealItem("Куриная грудка запечённая", 180, 297, 56, 6, 0, sureness = "примерно",
                    micro = mapOf("b1" to 0.1, "b3" to 18.0, "b6" to 1.1, "b12" to 0.5, "se" to 45.0, "zn" to 1.8, "fe" to 1.3, "k" to 460.0, "na" to 420.0)),
                ru.zf.pravka.core.MealItem("Рис басмати отварной", 180, 234, 5, 1, 51, fiber = 1, sureness = "примерно", micro = mapOf("mg" to 22.0, "se" to 12.0, "na" to 200.0)),
                ru.zf.pravka.core.MealItem("Салат: огурец, помидор, оливковое масло", 180, 110, 2, 9, 6, fiber = 2, sureness = "примерно",
                    micro = mapOf("vc" to 22.0, "vk" to 35.0, "va" to 75.0, "ve" to 2.0, "b9" to 30.0, "k" to 380.0, "na" to 150.0)),
            )
            eat(
                app, at(k, "16:40"), "перекус", "творог с мёдом и грецкими орехами",
                ru.zf.pravka.core.MealItem("Творог 5 %", 200, 242, 34, 10, 6, sureness = "точно", micro = mapOf("ca" to 330.0, "b2" to 0.5, "b12" to 2.0, "se" to 18.0, "i" to 20.0, "na" to 80.0)),
                ru.zf.pravka.core.MealItem("Мёд", 15, 46, 0, 0, 12, sureness = "примерно"),
                ru.zf.pravka.core.MealItem("Грецкие орехи", 20, 131, 3, 13, 3, fiber = 1, sureness = "примерно", micro = mapOf("mg" to 32.0, "ve" to 0.6)),
            )
            if (back > 0) eat(
                app, at(k, "18:40"), "ужин", "говядина с овощами и гречкой",
                ru.zf.pravka.core.MealItem("Говядина тушёная", 120, 264, 31, 16, 0, sureness = "примерно",
                    micro = mapOf("b2" to 0.24, "b3" to 5.0, "b6" to 0.4, "b12" to 3.0, "fe" to 3.3, "zn" to 7.5, "se" to 22.0, "k" to 380.0, "na" to 480.0)),
                ru.zf.pravka.core.MealItem("Гречка отварная", 150, 165, 5, 2, 32, fiber = 4, sureness = "примерно", micro = mapOf("b1" to 0.12, "b9" to 30.0, "mg" to 75.0, "fe" to 1.1, "k" to 130.0)),
                ru.zf.pravka.core.MealItem("Овощи тушёные: брокколи, морковь, перец", 200, 110, 3, 5, 13, fiber = 6, sureness = "примерно",
                    micro = mapOf("va" to 650.0, "vc" to 105.0, "vk" to 110.0, "b9" to 110.0, "ve" to 1.6, "k" to 560.0)),
            )
        }
        // Ужин сегодня разобран, но ещё ждёт «✓» — видно, как выглядит неподтверждённый приём.
        val ts = at(0, "18:40")
        if (ts < System.currentTimeMillis()) {
            app.foodStore.add(
                ts = ts, kind = "ужин", raw = "говядина с гречкой и овощами", note = "", source = "voice", photo = "", costUsd = 0.0, model = "",
                items = listOf(
                    ru.zf.pravka.core.MealItem("Говядина тушёная", 120, 264, 31, 16, 0, sureness = "примерно"),
                    ru.zf.pravka.core.MealItem("Гречка отварная", 150, 165, 5, 2, 32, fiber = 4, sureness = "примерно"),
                    ru.zf.pravka.core.MealItem("Овощи тушёные", 200, 110, 3, 5, 13, fiber = 6, sureness = "наугад"),
                ),
            )
        }
    }

    // ------------------------------------------------------------ деньги

    suspend fun money(app: PravkaApp) {
        val msk = ZoneId.of("Europe/Moscow")
        val now = System.currentTimeMillis()
        val todayMsk = Instant.ofEpochMilli(now).atZone(msk).toLocalDate()
        val rnd = kotlin.random.Random(2026)
        val out = mutableListOf<ru.zf.pravka.core.MoneyEntry>()
        fun r(a: Int, b: Int) = rnd.nextInt(a, b + 1).toLong()
        fun op(day: LocalDate, h: Int, m: Int, rub: Long, what: String, cat: String, who: String = "", card: String = "T") {
            val ts = day.atTime(h, m).atZone(msk).toInstant().toEpochMilli()
            if (ts > now) return
            out += ru.zf.pravka.core.MoneyEntry(
                id = "demo-${out.size}", owner = if (card == "A") "marianna" else "sasha",
                source = when (card) {
                    "A" -> ru.zf.pravka.core.MoneyEntry.Source.ALFA
                    "C" -> ru.zf.pravka.core.MoneyEntry.Source.MANUAL
                    else -> ru.zf.pravka.core.MoneyEntry.Source.TINKOFF
                },
                ts = ts, rubKop = rub * 100, what = what,
                account = when (card) {
                    "A" -> "Текущий счёт *4402"
                    "C" -> ru.zf.pravka.core.MoneyEntry.CASH
                    else -> "Black *1519"
                },
                category = cat, who = who, categoryBy = ru.zf.pravka.core.MoneyEntry.CategoryBy.OWNER,
            )
        }
        var d = todayMsk.minusMonths(5).withDayOfMonth(1)
        while (!d.isAfter(todayMsk)) {
            val dom = d.dayOfMonth
            val dow = d.dayOfWeek.value
            if (dom == 1) op(d, 9, 30, 230_000, "Выплата от фирмы", "inc_zf")
            if (dom == 16) op(d, 9, 30, 110_000, "Выплата от фирмы", "inc_zf")
            if (dom == 3) op(d, 9, 10, 95_000, "Зарплата", "inc_other", card = "A")
            if (dom == 18) op(d, 9, 10, 60_000, "Аванс", "inc_other", card = "A")
            if (dom == 1 || dom == 15) op(d, 10, 5, -45_000, "Няня", "help", "kids")
            if (dom == 1) op(d, 7, 55, -4_900, "Секция BJJ", "sport", "sasha")
            if (dom == 2 && d.monthValue !in 6..8) op(d, 8, 20, -5_400, "Школьное питание", "school", "kids")
            if (dom == 3) op(d, 19, 10, -14_000, "Бассейн", "clubs", "kids")
            if (dom == 4) op(d, 20, 30, -9_600, "Английский онлайн", "clubs", "kids")
            if (dom == 6) op(d, 18, 45, -7_500, "Футбольная секция", "clubs", "kids")
            if (dom == 7) op(d, 3, 12, -449, "Яндекс Плюс", "subs_home", "all")
            if (dom == 9) op(d, 4, 0, -299, "iCloud", "subs_home")
            if (dom == 11) op(d, 2, 30, -399, "Okko", "subs_home", "all")
            if (dom == 12) op(d, 13, 15, -(11_800 + r(0, 900)), "ЖКУ", "utilities")
            if (dom == 15) op(d, 9, 0, -1_450, "Мобильная связь", "utilities")
            if (dom == 16) op(d, 9, 5, -950, "Домашний интернет", "utilities")
            if (dom == 18) op(d, 12, 40, -4_800, "Детская поликлиника", "health", "kids", card = "A")
            if (dom == 22) op(d, 14, 0, -r(4_000, 9_000), "Одежда детям", "clothes", "kids", card = "A")
            op(d, 18, r(0, 59).toInt(), -r(1_400, 4_200), listOf("ВкусВилл", "Перекрёсток", "Яндекс Лавка").random(rnd), "groceries", "all", card = if (rnd.nextInt(3) == 0) "A" else "T")
            if (rnd.nextInt(3) == 0) op(d, 20, 10, -r(900, 2_600), "Азбука Вкуса", "groceries", "all", card = "A")
            if (rnd.nextInt(2) == 0) op(d, 9, r(0, 59).toInt(), -r(380, 950), "Яндекс Такси", "transport", "sasha")
            if (rnd.nextInt(4) == 0) op(d, 13, 30, -r(1_200, 3_800), listOf("Кофемания", "Surf Coffee", "Додо Пицца", "Шоколадница").random(rnd), "cafe", "all")
            if (dow == 6) op(d, 11, 20, -r(3_600, 4_600), "АЗС", "transport")
            if (dow == 7 && rnd.nextBoolean()) op(d, 15, 0, -r(1_800, 3_400), "Кино", "leisure", "all")
            if (rnd.nextInt(12) == 0) op(d, 17, 40, -r(900, 2_400), "Аптека", "health", "kids", card = "A")
            if (rnd.nextInt(14) == 0) op(d, 16, 0, -r(2_500, 8_500), "Детский мир", "kids_stuff", "kids", card = "A")
            if (rnd.nextInt(10) == 0) op(d, 10, 30, -r(800, 2_500), "Рынок: овощи и фрукты", "groceries", "all", card = "C")
            d = d.plusDays(1)
        }
        val s = app.moneyStore
        s.load()
        s.addMissing(out)
        val t = out.filter { it.source == ru.zf.pravka.core.MoneyEntry.Source.TINKOFF }.sumOf { it.rubKop }
        val a = out.filter { it.source == ru.zf.pravka.core.MoneyEntry.Source.ALFA }.sumOf { it.rubKop }
        s.addBalance(ru.zf.pravka.core.MoneyCashflow.Anchor("Т-Банк · Black", now, 180_000_00 + t, "вписано"))
        s.addBalance(ru.zf.pravka.core.MoneyCashflow.Anchor("Альфа · Текущий счёт", now, 60_000_00 + a, "вписано"))
        s.addBalance(ru.zf.pravka.core.MoneyCashflow.Anchor("Наличные", now, 8_400_00, "вписано"))
        s.setInsight(
            "**Структура.** Дом — около 60 % трат: продукты и помощь по дому.\n\n" +
                "**Советы.**\n1. Продукты в трёх магазинах — около 100 тыс. в месяц, половина — доставкой.\n2. Такси по утрам — 10 тыс. в месяц.",
            now,
        )
    }

    // ------------------------------------------------------------ правка

    fun pravka(app: PravkaApp) {
        val zone = ZoneId.systemDefault()
        fun take(hm: String, audioMs: Long, text: String): String {
            val ts = Instant.ofEpochMilli(at(0, hm)).atZone(zone).toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS).toString()
            return JSONObject().put("ts", ts).put("engine", "google")
                .put("audio_ms", audioMs).put("transcribe_ms", 0L).put("chars", text.length)
                .put("words", text.trim().split(Regex("\\s+")).size).put("ok", true).put("text", text)
                .put("startup_ms", 380L).put("deaf_ms", 0L).put("deaf_gaps", 0).toString()
        }
        java.io.File(ru.zf.pravka.data.DataRoot.dir(app), "transcriptions.jsonl").writeText(
            listOf(
                take("08:12", 9_800, "напомни жене что у младшего в среду бассейн в семь а не в шесть"),
                take("09:47", 21_400, "иван добрый день посмотрел договор по второму траншу в целом всё ок но в пункте четыре два нужно убрать штраф за досрочное погашение"),
                take("11:03", 6_200, "созвон с ольгой перенести на четверг на одиннадцать"),
                take("12:26", 15_900, "коллеги прикладываю обновлённую модель денежного потока на четвёртый квартал прогноз по выручке снизил на восемь процентов"),
                take("16:52", 11_300, "дима пришли пожалуйста акт сверки за сентябрь до среды"),
            ).joinToString("\n", postfix = "\n"),
        )
        val cls = Class.forName("ru.zf.pravka.CleanBoxState")
        val inst = cls.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        @Suppress("UNCHECKED_CAST")
        fun st(p: String) = cls.getDeclaredMethod("get$p").apply { isAccessible = true }.invoke(inst) as androidx.compose.runtime.MutableState<Any?>
        st("Sent").value = "привет команда по отчёту за сентябрь в целом всё нормально но по маркетингу перерасход надо до пятницы прислать объяснение и план на октябрь"
        st("Result").value = "Привет, команда! По отчёту за сентябрь в целом всё нормально, но по маркетингу перерасход. До пятницы пришлите, пожалуйста, объяснение и план на октябрь."
    }
}
