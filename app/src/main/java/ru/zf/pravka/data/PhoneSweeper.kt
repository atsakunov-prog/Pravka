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
//   2. ribbon entries for the crossover cases: an attention-eater session
//      (YouTube-класс, конфигурируемый список) interrupts the open entry and
//      claims the time; a call >= 1 min interrupts AND resumes the entry it
//      cut (a conversation pauses work, it does not end it).
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
        var lastClosedPkg: String? = null

        fun closeSession(pkg: String, at: Long) {
            lastClosedPkg = pkg
            if (pkg in excluded || at <= currentAggFrom) return
            addAppTime(pkg, currentAggFrom, at)
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
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    // Screen off = not watching: the app session ends here
                    // (background audio deliberately does not count).
                    val glancePkg = currentPkg ?: lastClosedPkg
                    currentPkg?.let { closeSession(it, ts) }
                    currentPkg = null
                    if (screenOnAt > 0 && ts > screenOnAt) {
                        addScreenTime(max(begin, screenOnAt), ts)
                        if (ts - screenOnAt < GLANCE_MS) {
                            val d = delta(screenOnAt)
                            d.glances += 1
                            if (glancePkg != null && glancePkg !in excluded) {
                                d.glanceApps[glancePkg] = (d.glanceApps[glancePkg] ?: 0) + 1
                            }
                        }
                    }
                    screenOnAt = 0
                }
            }
        }

        // Carry-out: count what is still open up to `now`, remember the real
        // starts so the next sweep continues seamlessly.
        currentPkg?.let { pkg ->
            if (pkg !in excluded && now > currentAggFrom) addAppTime(pkg, currentAggFrom, now)
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
        val allLabels = knownLabels + newLabels
        candidates.sortBy { it.start }
        for (c in candidates) {
            if (coveredByOwner(c.start, c.end)) continue
            val label = allLabels[c.pkg] ?: c.pkg.substringAfterLast('.')
            val inserted = zasechkaStore.insertInterruption(
                start = c.start,
                end = c.end,
                title = label,
                category = immersive[c.pkg].orEmpty(),
                resumePrevious = false,
            )
            if (inserted != null) {
                insertedAny = true
                eventLog.add("телефон: $label ${(c.end - c.start) / 60_000} мин → в ленту")
            }
        }

        if (settings.zCallsFlow.first() && hasCallLogAccess(context)) {
            if (sweepCalls(now, st.lastCallSweep)) insertedAny = true
        }

        if (insertedAny) sync.kickSoon(scope)
    }

    /** True when the owner's own CLOSED entries already claim most of [start, end). */
    private suspend fun coveredByOwner(start: Long, end: Long): Boolean {
        val overlapping = zasechkaStore.forRange(start, end)
        val manualMs = overlapping
            .filter { !it.open && it.source != "auto" }
            .sumOf { (min(it.end, end) - max(it.start, start)).coerceAtLeast(0L) }
        return manualMs * 2 >= end - start
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
                    if (runCatching { coveredByOwner(date, end) }.getOrDefault(false)) continue
                    val title = "звонок" + (name?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                    val entry = zasechkaStore.insertInterruption(
                        start = date,
                        end = end,
                        title = title,
                        category = callCategory,
                        // A call pauses the current activity; afterwards the
                        // same activity keeps running in the ribbon.
                        resumePrevious = true,
                    )
                    if (entry != null) {
                        inserted = true
                        eventLog.add("телефон: $title ${durationSec / 60} мин → в ленту (с продолжением)")
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
        val set = hashSetOf(context.packageName, "com.android.systemui")
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let { set.add(it) }
        }
        return set
    }

    private fun appLabel(pkg: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()
}
