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

// Reads the phone's own memory of the day - UsageStatsManager events and the
// call log - and turns it into:
//   1. day aggregates in PhoneStore (screen time, pickups, glances=отвлечения,
//      per-app minutes) - the SEPARATE layer that never touches the ribbon;
//   2. ribbon entries for the crossover cases: пожиратель внимания
//      (YouTube-класс, конфигурируемый список) и звонок >= 1 мин. Ни тот, ни
//      другой НИЧЕГО НЕ ВЫЧИТАЮТ: если в это время шло настоящее дело, факт
//      ложится ПАРАЛЛЕЛЬНЫМ треком поверх него (готовил еду и смотрел про
//      часы - готовка осталась готовкой), а если время было ничьё - обычной
//      строкой в ленту.
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
        val immersive = phoneStore.immersiveMap()
        val immersiveMinMs = settings.zImmersiveMinFlow.first() * 60_000L
        val candidates = ArrayList<Candidate>()

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
            if (immersive.containsKey(pkg) && span >= immersiveMinMs) {
                candidates.add(Candidate(pkg, currentStartedAt, at))
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

        // Human names for everything new this sweep.
        val knownLabels = phoneStore.labelsFlow.value
        val newLabels = HashMap<String, String>()
        val seenPkgs = HashSet<String>()
        deltas.values.forEach { seenPkgs.addAll(it.apps.keys) }
        candidates.forEach { seenPkgs.add(it.pkg) }
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
            ),
        )

        // ---- crossover into the ribbon ----

        var insertedAny = false
        if (detectSleep(now, usm)) insertedAny = true
        // Пожиратели и звонки больше не режут ленту: они ложатся ПАРАЛЛЕЛЬНЫМ
        // треком поверх того дела, которое шло, - «YouTube за готовкой не
        // потеря» перестало быть проблемой, потому что готовка остаётся
        // готовкой. Если время было ничьё, факт идёт обычной строкой.
        // Сон выше - отдельно, он занимает время по-настоящему.
        val insertsOn = settings.zParallelAutoFlow.first()
        val allLabels = knownLabels + newLabels
        candidates.sortBy { it.start }
        for (c in candidates) {
            if (!insertsOn) break
            val label = allLabels[c.pkg] ?: c.pkg.substringAfterLast('.')
            // Занятое владельцем время больше не повод промолчать: наоборот,
            // именно там пожиратель и становится параллелью. Решает store.
            val inserted = zasechkaStore.insertAutoFact(
                start = c.start,
                end = c.end,
                title = label,
                category = immersive[c.pkg].orEmpty(),
            )
            if (inserted != null) {
                insertedAny = true
                val where = if (inserted.parallel) "параллельно" else "в ленту"
                eventLog.add("телефон: $label ${(c.end - c.start) / 60_000} мин → $where")
            }
        }

        if (insertsOn && settings.zCallsFlow.first() && hasCallLogAccess(context)) {
            if (sweepCalls(now, st.lastCallSweep)) insertedAny = true
        }

        if (insertedAny) sync.kickSoon(scope)
    }

    /**
     * The night's sleep, read off the screen: the longest lights-out gap
     * between 18:00 yesterday and now. The phone knows when the owner really
     * fell asleep and woke up better than any API - intervals.icu only has
     * the duration (IcuSweeper annotates it onto this entry later). Runs once
     * per day after 05:00; closing the evening's open entry at lights-out is
     * the automatic "закрыть день".
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
        var lastOff = 0L
        var bestStart = 0L
        var bestEnd = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> lastOff = event.timeStamp
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    if (lastOff > 0 && event.timeStamp - lastOff > bestEnd - bestStart) {
                        bestStart = lastOff
                        bestEnd = event.timeStamp
                    }
                    lastOff = 0
                }
            }
        }
        if (bestEnd - bestStart < 3 * 3600_000L) {
            // No convincing night gap; stop looking for today after noon.
            if (hour >= 12) prefs.edit().putString("z_sleep_day", todayKey).apply()
            return false
        }
        prefs.edit().putString("z_sleep_day", todayKey).apply()
        if (zasechkaStore.coveredByOwner(bestStart, bestEnd)) return false
        // Спящий человек ничего не слушает: забытая с вечера параллель
        // («слушаю книгу») закрывается вместе с вечерним делом.
        zasechkaStore.closeParallel(bestStart)
        val entry = zasechkaStore.insertInterruption(
            start = bestStart,
            end = bestEnd,
            title = "сон",
            category = "Сон",
            resumePrevious = false,
        )
        if (entry != null) {
            eventLog.add("телефон: сон ${(bestEnd - bestStart) / 60_000} мин → в ленту")
        }
        return entry != null
    }

    private suspend fun sweepCalls(now: Long, lastCallSweep: Long): Boolean {
        val from = if (lastCallSweep <= 0) dayStartMs(now) else max(lastCallSweep - CALL_RESCAN_MS, 0L)
        val callCategory = settings.zCallCategoryFlow.first()
        var inserted = false
        var watermark = lastCallSweep
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
                    val title = "звонок" + (name?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                    // Разговор идёт ПОВЕРХ дела, а не вместо него: два звонка
                    // посреди работы больше не режут работу на четыре куска.
                    val entry = zasechkaStore.insertAutoFact(
                        start = date,
                        end = end,
                        title = title,
                        category = callCategory,
                        // The recognized contact is the counterparty: with it in
                        // its own column a call row is already a CRM log line.
                        client = name?.takeIf { it.isNotBlank() }.orEmpty(),
                    )
                    if (entry != null) {
                        inserted = true
                        val where = if (entry.parallel) "параллельно" else "в ленту"
                        eventLog.add("телефон: $title ${durationSec / 60} мин → $where")
                    }
                }
            }
        }.onFailure { eventLog.add("журнал звонков не прочитался: ${it.message}") }
        if (watermark > lastCallSweep) {
            val st = phoneStore.sweepState()
            phoneStore.applySweep(emptyMap(), emptyMap(), st.copy(lastCallSweep = watermark))
        }
        return inserted
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

    private fun appLabel(pkg: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()
}
