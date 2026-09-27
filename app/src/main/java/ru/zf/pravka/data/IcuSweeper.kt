package ru.zf.pravka.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import ru.zf.pravka.core.AutoPilotRules
import ru.zf.pravka.core.AutoWitness
import ru.zf.pravka.core.SleepGuess

// Pulls the owner's training life from intervals.icu straight into the
// ribbon: a workout is a timesheet entry nobody should have to dictate.
// The API is queried directly from the phone (athlete id + API key in the
// settings, Basic auth per the intervals.icu convention: user "API_KEY").
//
// Two jobs, every ~30 minutes:
//   1. activities of the last 48h -> ribbon entries (type -> category map),
//      same "closed manual entry wins" rule as every other auto-inserter;
//   2. today's wellness sleep duration/score -> annotated onto the phone-
//      detected "сон" entry (intervals has NO bedtime/wake times - verified -
//      so the screen-gap entry carries the timing and Garmin the quality).
//      Ночь по экрану не нашлась (27.09.2026: «сон перестал синхронизироваться»)
//      — после 14:00 сон пишется ОТ GARMIN: длительность его, подъём — по
//      экрану (`PhoneSweeper.KEY_WAKE_HINT`), отбой считается назад.
//
// Тренировка от двери (владелец, 27.09.2026: «вышел из дома, потом приехало
// Вело — это передвижение вело»): [witness] — автопилот; вело или бег с
// часов, стартовавшие в двадцать минут после потери сети места, начинаются
// с потери сети (`AutoPilotRules.stitchedStart`), вопрос «уехал?» снимается;
// вело, кончившееся в другом месте, — «Передвижение: вело», не спорт.
class IcuSweeper(
    private val settings: Settings,
    private val zasechkaStore: ZasechkaStore,
    private val client: OkHttpClient,
    private val eventLog: EventLog,
    private val sync: ZasechkaSync,
    private val scope: CoroutineScope,
    private val witness: () -> AutoWitness? = { null },
    private val wakeHint: () -> Long = { 0L },
) {

    companion object {
        private const val PERIOD_MS = 30 * 60_000L
        private const val MIN_MINUTES = 5

        // intervals.icu activity types -> the owner's categories. Anything
        // sporty but unmapped lands in "Спорт: прочее".
        private val TYPE_CATEGORY = mapOf(
            "Run" to "Спорт: бег",
            "TrailRun" to "Спорт: бег",
            "VirtualRun" to "Спорт: бег",
            "Ride" to "Спорт: вело",
            "VirtualRide" to "Спорт: вело",
            "GravelRide" to "Спорт: вело",
            "MountainBikeRide" to "Спорт: вело",
            "Walk" to "Передвижение: пешком",
            "Hike" to "Передвижение: пешком",
            "WeightTraining" to "Спорт: силовая",
            "Workout" to "Спорт: силовая",
            "Crossfit" to "Спорт: силовая",
            "HIIT" to "Спорт: силовая",
        )
    }

    private val running = AtomicBoolean(false)
    @Volatile private var lastRun = 0L
    /** «Сон не записан, потому что…» — раз в день, не каждые полчаса. */
    @Volatile private var noSleepLoggedDay = ""

    suspend fun sweep(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRun < PERIOD_MS) return
        if (!running.compareAndSet(false, true)) return
        try {
            withContext(Dispatchers.IO) { doSweep(now) }
            lastRun = now
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            runCatching { eventLog.add("icu-свип упал: ${e.javaClass.simpleName}: ${e.message}") }
        } finally {
            running.set(false)
        }
    }

    private suspend fun doSweep(now: Long) {
        val athleteId = settings.icuAthlete().trim()
        val apiKey = settings.icuKey().trim()
        if (athleteId.isBlank() || apiKey.isBlank()) return
        val auth = Credentials.basic("API_KEY", apiKey)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val oldest = dateFormat.format(Date(now - 2 * 86_400_000L))
        val newest = dateFormat.format(Date(now))

        var inserted = false
        val activitiesUrl =
            "https://intervals.icu/api/v1/athlete/$athleteId/activities?oldest=$oldest&newest=$newest"
        val body = get(activitiesUrl, auth)
        if (body != null) {
            // start_date_local carries no zone - parse it in the phone's zone,
            // which is where the owner and the watch both live.
            val parseFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            val array = runCatching { JSONArray(body) }.getOrNull()
            if (array == null) {
                eventLog.add("icu: ответ не похож на список тренировок — проверь athlete id")
                return
            }
            for (i in 0 until array.length()) {
                val a = array.optJSONObject(i) ?: continue
                val watchStart = runCatching { parseFormat.parse(a.optString("start_date_local"))?.time }
                    .getOrNull() ?: continue
                val seconds = a.optLong("elapsed_time", 0).takeIf { it > 0 }
                    ?: a.optLong("moving_time", 0)
                if (seconds < MIN_MINUTES * 60) continue
                val end = min(watchStart + seconds * 1000, now)
                if (end <= watchStart) continue
                val type = a.optString("type")
                var category = TYPE_CATEGORY[type] ?: "Спорт: прочее"
                val name = a.optString("name").ifBlank { type }
                var start = watchStart
                var note = ""
                // От двери: сеть места пропала в двадцать минут до кнопки на
                // часах — дорога началась там. Уже лежит (прошлый свип, со
                // своим сшитым началом) — не второй раз.
                val w = witness()
                if (w != null && AutoPilotRules.outdoor(type)) {
                    val already = zasechkaStore.forRange(watchStart - AutoPilotRules.CAR_AFTER_LEAVE_MS, end)
                        .any { it.source == "auto" && it.title == name.trim() && kotlin.math.abs(it.end - end) < 60_000L }
                    if (already) continue
                    val leave = w.lastLeave()
                    if (leave != null) {
                        val latest = zasechkaStore.lastEntry()?.start
                        val stitched = AutoPilotRules.stitchedStart(watchStart, leave.place, leave.atMs, latest)
                        if (stitched != watchStart) {
                            start = stitched
                            note = ", от двери «${leave.place}» ${hm(stitched)}"
                            w.leaveAnswered("$name с часов")
                        }
                        val arrival = w.lastArrival()
                        val elsewhere = arrival != null && arrival.place != leave.place &&
                            arrival.atMs >= end && arrival.atMs - end <= AutoPilotRules.CAR_AFTER_LEAVE_MS
                        category = AutoPilotRules.activityCategory(category, type, elsewhere)
                    }
                }
                if (zasechkaStore.coveredByOwner(start, end)) continue
                val entry = zasechkaStore.insertInterruption(
                    start = start,
                    end = end,
                    title = name,
                    category = category,
                    resumePrevious = false,
                )
                if (entry != null) {
                    inserted = true
                    eventLog.add("icu: $name ${(end - start) / 60_000} мин [$category]$note → в ленту")
                }
            }
        }

        runCatching { enrichSleep(now, athleteId, auth) }
        if (inserted) sync.kickSoon(scope)
    }

    /**
     * Garmin's sleep duration/score lands as a note on the "сон" entry. Записи
     * сна нет, а телефон уже сдался (после 14:00, `SleepGuess.WAKE_TO_HOUR`) —
     * сон пишется от Garmin: длительность его, подъём — по экрану, отбой
     * считается назад. Подъёма по экрану нет — сон не выдумываем, пишем в
     * журнал почему (раз в день).
     */
    private suspend fun enrichSleep(now: Long, athleteId: String, auth: String) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        val url = "https://intervals.icu/api/v1/athlete/$athleteId/wellness?oldest=$today&newest=$today"
        val body = get(url, auth) ?: return
        val w = runCatching { JSONArray(body).optJSONObject(0) }.getOrNull() ?: return
        val sleepSecs = w.optLong("sleepSecs", 0)
        if (sleepSecs <= 0) return
        val score = w.optInt("sleepScore", 0)
        val note = "Garmin: сон " +
            String.format(Locale.US, "%.1f", sleepSecs / 3600.0) + " ч" +
            (if (score > 0) ", счёт $score" else "")
        val dayStart = dayStartMs(now)
        val sleeps = zasechkaStore.forRange(dayStart - 8 * 3600_000L, now)
            .filter { it.source == "auto" && it.title == "сон" && !it.open && it.end >= dayStart }
        val target = sleeps.firstOrNull()
        if (target != null) {
            if (target.raw.isBlank()) zasechkaStore.update(target.copy(raw = note))
            return
        }
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        if (cal.get(java.util.Calendar.HOUR_OF_DAY) < SleepGuess.WAKE_TO_HOUR) return
        val wake = wakeHint().takeIf { it in (dayStart + 3 * 3600_000L)..now }
        if (wake == null) {
            if (noSleepLoggedDay != today) {
                noSleepLoggedDay = today
                eventLog.add("icu: $note, но телефон не знает, когда ты встал, — сон не записан")
            }
            return
        }
        val start = wake - sleepSecs * 1000L
        if (zasechkaStore.coveredByOwner(start, wake)) {
            if (noSleepLoggedDay != today) {
                noSleepLoggedDay = today
                eventLog.add("icu: сон от Garmin ${hm(start)}–${hm(wake)} не записан — время занято твоими записями")
            }
            return
        }
        val entry = zasechkaStore.insertInterruption(
            start = start,
            end = wake,
            title = "сон",
            category = "Сон",
            resumePrevious = false,
        ) ?: return
        zasechkaStore.update(entry.copy(raw = "$note · время — от подъёма по экрану ${hm(wake)}, ночь телефон не увидел"))
        eventLog.add("icu: сон от Garmin ${sleepSecs / 60} мин до ${hm(wake)} → в ленту (телефон ночи не нашёл)")
        sync.kickSoon(scope)
    }

    private fun hm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))

    private fun get(url: String, auth: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                eventLog.add("icu: HTTP ${response.code} на $url")
                return null
            }
            return response.body?.string()
        }
    }
}
