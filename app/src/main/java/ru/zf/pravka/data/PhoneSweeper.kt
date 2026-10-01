package ru.zf.pravka.data

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.CallLog
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import ru.zf.pravka.core.AutoWitness
import ru.zf.pravka.core.SleepGuess

// Reads the phone's own memory of the day - UsageStatsManager events and the
// call log - and turns it into day aggregates in PhoneStore: screen time,
// pickups, glances=отвлечения, per-app minutes, calls. Это ОТДЕЛЬНЫЙ слой,
// и в ленту он не пишет ничего, кроме сна.
//
// Так было не всегда. Сначала пожиратели внимания и звонки резали дело
// врезками («засоряет ленту, и не всегда это потеря»), потом ложились
// параллельным треком поверх дела - и владелец закрыл оба опыта одним словом:
// «эксперимент оказался неудачным, засоряет ленту. просто давай считать
// каждый день, сколько на Клод, телеграм, звонки, сколько на ютуб». Теперь
// ровно так: телефон считается по дням, лента остаётся лентой.
// Runs retrospectively every few minutes, so nothing is lost while Правка's
// process was dead - the system kept the history for us.
class PhoneSweeper(
    private val context: Context,
    private val phoneStore: PhoneStore,
    private val zasechkaStore: ZasechkaStore,
    private val settings: Settings,
    private val eventLog: EventLog,
    private val sync: ZasechkaSync,
    private val scope: CoroutineScope,
    /** Автопилот службы: узнаёт о найденной ночи и начинает дело по подъёму. */
    private val witness: () -> AutoWitness? = { null },
) {

    companion object {
        // A "glance" (отвлечение): screen on-and-off in under two minutes.
        private const val GLANCE_MS = 2 * 60_000L
        // Foreground blips shorter than this are not counted as sessions.
        private const val MIN_SESSION_MS = 5_000L
        // How far back a sweep may reach after a long dead period.
        private const val WINDOW_CAP_MS = 3L * 24 * 3600 * 1000
        // Shorter calls are pings, not conversations.
        private const val MIN_CALL_SEC = 60
        // Calls are re-scanned over a sliding window: a call STILL RUNNING at
        // sweep time gets its log row only after it ends, with a start in the
        // past. The insert dedup makes the re-scan idempotent.
        private const val CALL_RESCAN_MS = 2L * 3600 * 1000
        // Висящий хвост фоновой службы: служба могла умереть без события.
        private const val AUDIO_CARRY_CAP_MS = 24L * 3600 * 1000
        // Пауза короче этого - тот же сеанс слушания.
        private const val AUDIO_GAP_MS = 5 * 60_000L
        /** Когда встал по экрану (ms) — в `pravka_internal`; читает `IcuSweeper` для сна от Garmin. */
        const val KEY_WAKE_HINT = "z_wake_hint"
        /** Отбой по зарядке и тишине (ms) — пишет автопилот, читает детектор ночи. */
        const val KEY_BEDTIME = "z_bedtime"

        /** The special "Доступ к статистике использования" toggle. */
        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
                )
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }

        fun hasCallLogAccess(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
                PackageManager.PERMISSION_GRANTED
    }

    private val running = AtomicBoolean(false)

    private data class Candidate(val pkg: String, val start: Long, val end: Long)

    /** Safe to call often: single-flight, cheap when nothing new happened. */
    suspend fun sweep() {
        if (!running.compareAndSet(false, true)) return
        try {
            withContext(Dispatchers.IO) { doSweep() }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // A sweep must never take the service down; the next tick retries.
            runCatching { eventLog.add("телефон-свип упал: ${e.javaClass.simpleName}: ${e.message}") }
        } finally {
            running.set(false)
        }
    }

    private suspend fun doSweep() {
        if (!hasUsageAccess(context)) return
        val now = System.currentTimeMillis()
        val st = phoneStore.sweepState()
        val begin = if (st.lastSweep <= 0) dayStartMs(now) else max(st.lastSweep, now - WINDOW_CAP_MS)
        if (now - begin < 15_000) return

        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return
        val events = usm.queryEvents(begin, now)

        val deltas = HashMap<String, PhoneStore.DayDelta>()
        fun delta(at: Long): PhoneStore.DayDelta =
            deltas.getOrPut(phoneDayKey(at)) { PhoneStore.DayDelta() }

        // Per-day splitting keeps a session that crosses midnight honest.
        fun addAppTime(pkg: String, from: Long, to: Long) {
            var f = from
            while (f < to) {
                val dayEnd = dayStartMs(f) + 86_400_000L
                val chunk = min(to, dayEnd)
                val d = delta(f)
                d.apps[pkg] = (d.apps[pkg] ?: 0L) + (chunk - f)
                f = chunk
            }
        }

        fun addScreenTime(from: Long, to: Long) {
            var f = from
            while (f < to) {
                val dayEnd = dayStartMs(f) + 86_400_000L
                val chunk = min(to, dayEnd)
                delta(f).screenMs += chunk - f
                f = chunk
            }
        }

        val excluded = excludedPackages()
        val audioApps = phoneStore.audioApps()
        // «Слушающие» приложения (аудиокниги, подкасты, музыка) считаются по
        // ФОНОВОЙ СЛУЖБЕ, а не по переднему плану: книга играет с погасшим
        // экраном, и сессия приложения кончается на первом же гашении - её
        // время не поймать вовсе. Служба живёт ровно столько, сколько идёт
        // воспроизведение, и это единственный честный источник.
        var audioPkg = st.carryAudioPkg.takeIf { it.isNotBlank() }
        var audioAt = st.carryAudioAt
        // Служба могла умереть без события (приложение убили) - висящий
        // хвост старше суток бросаем, иначе он не закроется никогда.
        if (audioPkg != null && now - audioAt > AUDIO_CARRY_CAP_MS) {
            audioPkg = null
            audioAt = 0
        }
        val audioSpans = ArrayList<Candidate>()

        // Carry-in: a session/screen still open when the last sweep ended.
        // Aggregation resumes from `begin` (time before it is already
        // counted); the REAL start survives for session/glance judgements.
        var currentPkg: String? = st.carryPkg.takeIf { it.isNotBlank() }
        var currentAggFrom = begin
        var currentStartedAt = if (currentPkg != null) st.carryPkgStartedAt else 0L
        var screenOnAt = st.carryScreenOnAt
        // Per-screen-window app time: a glance's "who distracted me" is the
        // app that held the screen LONGEST in that window, not whatever
        // (launcher, player) happened to be up when it went dark.
        val windowApps = HashMap<String, Long>()

        fun closeSession(pkg: String, at: Long) {
            if (isExcluded(pkg, excluded) || at <= currentAggFrom) return
            addAppTime(pkg, currentAggFrom, at)
            if (screenOnAt > 0) {
                val overlap = at - max(currentAggFrom, screenOnAt)
                if (overlap > 0) windowApps[pkg] = (windowApps[pkg] ?: 0L) + overlap
            }
            val span = at - currentStartedAt
            if (span >= MIN_SESSION_MS) {
                val d = delta(currentStartedAt)
                d.appSessions[pkg] = (d.appSessions[pkg] ?: 0) + 1
            }
        }

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val ts = event.timeStamp
            if (ts < begin) continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val pkg = event.packageName ?: continue
                    if (pkg != currentPkg) {
                        currentPkg?.let { closeSession(it, ts) }
                        currentPkg = pkg
                        currentAggFrom = ts
                        currentStartedAt = ts
                    }
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    if (event.packageName == currentPkg) {
                        currentPkg?.let { closeSession(it, ts) }
                        currentPkg = null
                    }
                }
                UsageEvents.Event.FOREGROUND_SERVICE_START -> {
                    val pkg = event.packageName ?: continue
                    // Событие есть с Android 10; на девятке его просто не
                    // будет, и слушающие приложения останутся без времени.
                    if (pkg in audioApps && audioPkg == null) {
                        audioPkg = pkg
                        audioAt = ts
                    }
                }
                UsageEvents.Event.FOREGROUND_SERVICE_STOP -> {
                    val pkg = event.packageName ?: continue
                    if (pkg == audioPkg) {
                        if (ts - audioAt >= MIN_SESSION_MS) audioSpans.add(Candidate(pkg, audioAt, ts))
                        audioPkg = null
                        audioAt = 0
                    }
                }
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    delta(ts).pickups += 1
                    screenOnAt = ts
                    windowApps.clear()
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    // Screen off = not watching: the app session ends here
                    // (background audio deliberately does not count).
                    currentPkg?.let { closeSession(it, ts) }
                    currentPkg = null
                    if (screenOnAt > 0 && ts > screenOnAt) {
                        addScreenTime(max(begin, screenOnAt), ts)
                        if (ts - screenOnAt < GLANCE_MS) {
                            val d = delta(screenOnAt)
                            d.glances += 1
                            val glancePkg = windowApps.maxByOrNull { it.value }?.key
                            if (glancePkg != null) {
                                d.glanceApps[glancePkg] = (d.glanceApps[glancePkg] ?: 0) + 1
                            }
                        }
                    }
                    windowApps.clear()
                    screenOnAt = 0
                }
            }
        }

        // Carry-out: count what is still open up to `now`, remember the real
        // starts so the next sweep continues seamlessly.
        currentPkg?.let { pkg ->
            if (!isExcluded(pkg, excluded) && now > currentAggFrom) addAppTime(pkg, currentAggFrom, now)
        }
        if (screenOnAt > 0 && now > screenOnAt) addScreenTime(max(begin, screenOnAt), now)
        // Книга в наушниках считается по фоновой службе - её минуты идут в
        // суточную сумму приложения, как у всех остальных, просто источник
        // другой (передний план у книги пуст, экран погашен). Пауза короче
        // пяти минут - один сеанс слушания, а не два.
        for (c in mergeSpans(audioSpans)) {
            val f = max(c.start, begin)
            val t = min(c.end, now)
            if (t > f) addAppTime(c.pkg, f, t)
        }

        // Human names for everything new this sweep.
        val knownLabels = phoneStore.labelsFlow.value
        val newLabels = HashMap<String, String>()
        val seenPkgs = HashSet<String>()
        deltas.values.forEach { seenPkgs.addAll(it.apps.keys) }
        for (pkg in seenPkgs) {
            if (pkg in knownLabels) continue
            appLabel(pkg)?.let { newLabels[pkg] = it }
        }

        phoneStore.applySweep(
            deltas,
            newLabels,
            PhoneStore.SweepState(
                lastSweep = now,
                lastCallSweep = st.lastCallSweep,
                carryPkg = currentPkg.orEmpty(),
                carryPkgStartedAt = if (currentPkg != null) currentStartedAt else 0L,
                carryScreenOnAt = screenOnAt,
                carryAudioPkg = audioPkg.orEmpty(),
                carryAudioAt = if (audioPkg != null) audioAt else 0L,
            ),
        )

        // ---- сон и звонки: единственное, что идёт дальше телефонного слоя ----

        var insertedAny = false
        if (detectSleep(now, usm)) insertedAny = true

        if (settings.zCallsFlow.first() && hasCallLogAccess(context)) {
            sweepCalls(now, st.lastCallSweep)
        }

        if (insertedAny) sync.kickSoon(scope)
    }

    /**
     * Ночь по экрану: самый длинный ночной разрыв «погас — включился» между
     * 18:00 вчера и сейчас, СШИТЫЙ через ночные взгляды на телефон
     * (`core/SleepGuess.kt` — там, почему первый детектор ночь терял). Телефон
     * знает, когда владелец лёг и встал, лучше любого API: intervals.icu отдаёт
     * только длительность — её `IcuSweeper` приписывает к записи, а если ночь
     * по экрану не нашлась, записывает сон от подъёма по ней. Раз в сутки после
     * 05:00; закрыть вечернее дело моментом отбоя — это и есть автоматическое
     * «закрыть день». Не нашлась к 14:00 — сдаёмся до завтра и пишем в журнал,
     * чего не хватило: молчание читалось как поломка.
     */
    private suspend fun detectSleep(now: Long, usm: UsageStatsManager): Boolean {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        if (hour < 5) return false
        val prefs = context.getSharedPreferences("pravka_internal", Context.MODE_PRIVATE)
        val todayKey = phoneDayKey(now)
        if (prefs.getString("z_sleep_day", "") == todayKey) return false
        val from = dayStartMs(now) - 6 * 3600_000L
        val events = usm.queryEvents(from, now)
        val event = UsageEvents.Event()
        val spans = ArrayList<SleepGuess.Span>()
        var onAt = -1L
        var screenEvents = 0
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    screenEvents++
                    if (onAt < 0) onAt = event.timeStamp
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    screenEvents++
                    // Первым пришёл «погас» — значит, с начала окна экран горел.
                    val s = if (onAt < 0) from else onAt
                    if (event.timeStamp > s) spans.add(SleepGuess.Span(s, event.timeStamp))
                    onAt = -1L
                }
            }
        }
        // Экран горит прямо сейчас — последний отрезок до [now]: разрыв перед
        // ним закрыт включением. Погашен — тишина ещё идёт, она не ночь.
        if (onAt >= 0) spans.add(SleepGuess.Span(onAt, now))
        // Подсказка «когда встал» — для сна от Garmin, если ночь не найдётся.
        SleepGuess.wakeHint(spans, from).takeIf { it > 0L }?.let {
            prefs.edit().putLong(KEY_WAKE_HINT, it).apply()
        }
        // Отбой, если автопилот его видел вчера вечером: ночь не раньше него.
        val bedtime = prefs.getLong(KEY_BEDTIME, 0L).takeIf { it in from..now } ?: 0L
        val verdict = SleepGuess.guess(spans, from, now, bedtime = bedtime)
        val night = verdict.night
        if (night == null) {
            if (hour >= SleepGuess.WAKE_TO_HOUR) {
                prefs.edit().putString("z_sleep_day", todayKey).apply()
                eventLog.add(
                    if (screenEvents == 0) {
                        "телефон: событий экрана за ночь нет — Android их не отдал; сон возьмёт Garmin"
                    } else {
                        "телефон: ночь по экрану не нашлась (самый длинный разрыв " +
                            "${verdict.longestMs / 60_000} мин из ${verdict.gaps}); сон возьмёт Garmin"
                    }
                )
            }
            return false
        }
        // Сон идёт открытым (автопилот начал его вечером, или метка NFC): ночь
        // поверх него не пишем. Свой сон автопилот закрывает этим подъёмом
        // сам (утро — с пяти); иначе ждём, пока сон закроется толчком или
        // словом владельца, и тогда время уже занято.
        val openNow = zasechkaStore.openEntry()
        if (openNow != null && ru.zf.pravka.core.DayReport.isSleep(openNow.category)) {
            val handled = runCatching { witness()?.nightSeen(night.end) == true }.getOrDefault(false)
            if (handled) {
                prefs.edit().putString("z_sleep_day", todayKey).apply()
                eventLog.add("телефон: подъём по экрану ${hm(night.end)} закрыл сон автопилота")
                return true
            }
            if (openSleepToldDay != todayKey) {
                openSleepToldDay = todayKey
                eventLog.add("телефон: ночь по экрану ${hm(night.start)}–${hm(night.end)}, но сон ещё идёт открытым — жду подъёма")
            }
            // К двум часам дня ждать нечего: сон в ленте уже есть — открытый.
            if (hour >= SleepGuess.WAKE_TO_HOUR) prefs.edit().putString("z_sleep_day", todayKey).apply()
            return false
        }
        prefs.edit().putString("z_sleep_day", todayKey).apply()
        if (zasechkaStore.coveredByOwner(night.start, night.end)) {
            eventLog.add("телефон: сон ${hm(night.start)}–${hm(night.end)} не записан — время занято твоими записями")
            return false
        }
        val entry = zasechkaStore.insertInterruption(
            start = night.start,
            end = night.end,
            title = "сон",
            category = "Сон",
            resumePrevious = false,
        )
        if (entry != null) {
            eventLog.add(
                "телефон: сон ${night.ms / 60_000} мин → в ленту" +
                    (if (night.pieces > 1) {
                        " (сшито из ${night.pieces}: ${night.stitchedMs / 60_000} мин экрана ночью — взгляды, не подъём)"
                    } else "") +
                    (if (bedtime > 0L && night.start == bedtime) " (начало — отбой по зарядке)" else "")
            )
            // Подъём известен — дело по подъёму решает автопилот.
            runCatching { witness()?.woke(night.start, night.end) }
        }
        return entry != null
    }

    /** День, за который уже сказано «сон идёт открытым — жду»: раз в день, не каждым тиком. */
    @Volatile private var openSleepToldDay = ""

    private fun hm(ms: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ms))

    /**
     * Звонки ≥ минуты из журнала - в суточные счётчики: минуты, число,
     * собеседники. Окно перечитывается скользящим (строка идущего разговора
     * появляется только после его конца, с началом в прошлом), поэтому уже
     * посчитанные звонки узнаются по времени начала в PhoneStore.
     */
    private suspend fun sweepCalls(now: Long, lastCallSweep: Long) {
        val from = if (lastCallSweep <= 0) dayStartMs(now) else max(lastCallSweep - CALL_RESCAN_MS, 0L)
        val deltas = HashMap<String, PhoneStore.DayDelta>()
        val starts = ArrayList<Long>()
        var watermark = lastCallSweep
        var counted = 0
        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME),
                "${CallLog.Calls.DATE} > ?",
                arrayOf(from.toString()),
                "${CallLog.Calls.DATE} ASC",
            )?.use { cursor ->
                val dateCol = cursor.getColumnIndex(CallLog.Calls.DATE)
                val durCol = cursor.getColumnIndex(CallLog.Calls.DURATION)
                val typeCol = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val nameCol = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                while (cursor.moveToNext()) {
                    val date = cursor.getLong(dateCol)
                    val durationSec = cursor.getLong(durCol)
                    val type = cursor.getInt(typeCol)
                    val name = if (nameCol >= 0) cursor.getString(nameCol) else null
                    watermark = max(watermark, date)
                    if (type != CallLog.Calls.INCOMING_TYPE && type != CallLog.Calls.OUTGOING_TYPE) continue
                    if (durationSec < MIN_CALL_SEC) continue
                    val end = min(date + durationSec * 1000, now)
                    if (end <= date) continue
                    if (phoneStore.callSeen(date)) continue
                    val d = deltas.getOrPut(phoneDayKey(date)) { PhoneStore.DayDelta() }
                    d.callsMs += end - date
                    d.calls += 1
                    name?.trim()?.takeIf { it.isNotBlank() }?.let { d.callers[it] = (d.callers[it] ?: 0L) + (end - date) }
                    starts.add(date)
                    counted++
                }
            }
        }.onFailure { eventLog.add("журнал звонков не прочитался: ${it.message}") }
        if (counted > 0 || watermark > lastCallSweep) {
            phoneStore.applyCalls(deltas, starts, watermark)
        }
        if (counted > 0) eventLog.add("телефон: звонков за свип $counted → в счётчики дня")
    }

    private fun excludedPackages(): Set<String> {
        val set = hashSetOf(
            context.packageName,
            "com.android.systemui",
            "com.google.android.apps.nexuslauncher",
        )
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let { set.add(it) }
        }
        return set
    }

    // Furniture, not phone use: launchers, the docked-hub screensaver
    // (Pixel's hubui sits "foregrounded" for hours on a stand), dreams and
    // the in-call UI (call time is already a ribbon entry, not app time).
    private fun isExcluded(pkg: String, set: Set<String>): Boolean =
        pkg in set ||
            pkg.contains("launcher", ignoreCase = true) ||
            pkg.contains("hubui", ignoreCase = true) ||
            pkg.contains("dream", ignoreCase = true) ||
            pkg.contains("dialer", ignoreCase = true) ||
            pkg.contains("incallui", ignoreCase = true) ||
            pkg.contains("telecom", ignoreCase = true)

    /** Склеивает соседние куски одного приложения, если пауза между ними мала. */
    private fun mergeSpans(spans: List<Candidate>): List<Candidate> {
        if (spans.size < 2) return spans
        val out = ArrayList<Candidate>()
        for (c in spans.sortedWith(compareBy({ it.pkg }, { it.start }))) {
            val last = out.lastOrNull()
            if (last != null && last.pkg == c.pkg && c.start - last.end <= AUDIO_GAP_MS) {
                out[out.size - 1] = last.copy(end = max(last.end, c.end))
            } else {
                out.add(c)
            }
        }
        return out
    }

    private fun appLabel(pkg: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    // ---- досчёт прошлых дней ----
    //
    // Телефон помнит больше, чем успел увидеть свип: суточные суммы по
    // приложениям система держит долго, журнал звонков - месяцами. Дни ДО
    // установки (или долгого простоя) можно досчитать из них - без границ
    // сессий, но с честными минутами: для «сколько на ютуб за день» этого
    // достаточно. День, который свип видел сам, не трогается: он точнее.

    /** Что нашлось за окно и как далеко назад данные вообще есть. */
    data class BackfillScan(
        val days: Map<String, PhoneStore.Day>,
        val appsFrom: Long,
        val callsFrom: Long,
        val noUsageAccess: Boolean,
        val noCallAccess: Boolean,
    ) {
        val count: Int get() = days.size
    }

    /** Смотрит суточные суммы за [daysBack] суток. Ничего не пишет. */
    suspend fun scanBackfill(daysBack: Int): BackfillScan = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val from = dayStartMs(now) - daysBack * 86_400_000L
        val known = phoneStore.daysFlow.value
        val filled = HashMap<String, PhoneStore.Day>()
        var appsFrom = 0L
        var callsFrom = 0L
        val excluded = excludedPackages()

        val usm = context.getSystemService(UsageStatsManager::class.java)
        if (hasUsageAccess(context) && usm != null) {
            val stats = runCatching { usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, from, now) }
                .getOrNull().orEmpty()
            for (u in stats) {
                val pkg = u.packageName ?: continue
                if (isExcluded(pkg, excluded)) continue
                val ms = u.totalTimeInForeground
                if (ms <= 0) continue
                val key = phoneDayKey(u.firstTimeStamp)
                // Сегодня и дни, которые свип видел сам, не досчитываются.
                if (key == phoneDayKey(now)) continue
                known[key]?.let { if (!it.backfilled) return@let null } ?: run {
                    if (appsFrom == 0L || u.firstTimeStamp < appsFrom) appsFrom = u.firstTimeStamp
                    val old = filled[key] ?: PhoneStore.Day()
                    filled[key] = old.copy(apps = old.apps + (pkg to ((old.apps[pkg] ?: 0L) + ms)))
                }
            }
        }

        if (hasCallLogAccess(context)) {
            runCatching {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME),
                    "${CallLog.Calls.DATE} > ?",
                    arrayOf(from.toString()),
                    "${CallLog.Calls.DATE} ASC",
                )?.use { cursor ->
                    val dateCol = cursor.getColumnIndex(CallLog.Calls.DATE)
                    val durCol = cursor.getColumnIndex(CallLog.Calls.DURATION)
                    val typeCol = cursor.getColumnIndex(CallLog.Calls.TYPE)
                    val nameCol = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                    while (cursor.moveToNext()) {
                        val date = cursor.getLong(dateCol)
                        if (callsFrom == 0L || date < callsFrom) callsFrom = date
                        val durationSec = cursor.getLong(durCol)
                        val type = cursor.getInt(typeCol)
                        if (type != CallLog.Calls.INCOMING_TYPE && type != CallLog.Calls.OUTGOING_TYPE) continue
                        if (durationSec < MIN_CALL_SEC) continue
                        val key = phoneDayKey(date)
                        if (key == phoneDayKey(now)) continue
                        val knownDay = known[key]
                        if (knownDay != null && !knownDay.backfilled) continue
                        val name = (if (nameCol >= 0) cursor.getString(nameCol) else null)?.trim().orEmpty()
                        val old = filled[key] ?: PhoneStore.Day()
                        val ms = durationSec * 1000
                        filled[key] = old.copy(
                            callsMs = old.callsMs + ms,
                            calls = old.calls + 1,
                            callers = if (name.isBlank()) old.callers else old.callers + (name to ((old.callers[name] ?: 0L) + ms)),
                        )
                    }
                }
            }.onFailure { eventLog.add("досчёт: журнал звонков не прочитался: ${it.message}") }
        }

        BackfillScan(
            days = filled,
            appsFrom = appsFrom,
            callsFrom = callsFrom,
            noUsageAccess = !hasUsageAccess(context),
            noCallAccess = !hasCallLogAccess(context),
        )
    }

    /** Кладёт досчитанные дни в телефонный слой. Возвращает, сколько дней легло. */
    suspend fun applyBackfill(scan: BackfillScan): Int {
        val added = phoneStore.backfillDays(scan.days)
        if (added > 0) eventLog.add("досчёт: телефон за $added прошлых дней посчитан из суточных сумм")
        return added
    }
}
