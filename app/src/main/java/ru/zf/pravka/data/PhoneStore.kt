package ru.zf.pravka.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

// The phone-usage side of Засечка: how the day looked THROUGH the screen.
// Deliberately a separate layer from the voice ribbon (owner's design):
// most app time is tooling inside a bigger activity and must not pollute the
// timeline - only "attention eaters" (YouTube-класс) and calls cross over
// into the ribbon, via PhoneSweeper.
//
// Storage is day-level AGGREGATES, not raw sessions: per day - screen time,
// pickups, glances (short peeks = отвлечения), per-app foreground
// minutes/sessions/glances. Bounded forever (≈120 days kept), one small file.
class PhoneStore(private val context: Context) {

    companion object {
        const val FORMAT = "pravka-phone"
        private const val FILE_NAME = "phone.json"
        private const val KEEP_DAYS = 120

        val DAY_KEY_FORMAT = "yyyy-MM-dd"

        // First seed: the canonical attention eater. Everything else the
        // owner adds by tapping an app row in the tab.
        val DEFAULT_IMMERSIVE = mapOf("com.google.android.youtube" to "Отдых")
    }

    /** One day of phone life, everything additive. */
    data class Day(
        val screenMs: Long = 0,
        val pickups: Int = 0,          // screen became interactive
        val glances: Int = 0,          // interactive sessions < 2 min = отвлечения
        val apps: Map<String, Long> = emptyMap(),        // pkg -> foreground ms
        val appSessions: Map<String, Int> = emptyMap(),  // pkg -> sessions >= 5s
        val glanceApps: Map<String, Int> = emptyMap(),   // pkg -> glances ended on it
    )

    /** Additive per-day delta produced by one sweep. */
    class DayDelta {
        var screenMs = 0L
        var pickups = 0
        var glances = 0
        val apps = HashMap<String, Long>()
        val appSessions = HashMap<String, Int>()
        val glanceApps = HashMap<String, Int>()
        fun isEmpty(): Boolean =
            screenMs == 0L && pickups == 0 && glances == 0 &&
                apps.isEmpty() && appSessions.isEmpty() && glanceApps.isEmpty()
    }

    // Sweep continuity: sessions/screen state open at the end of one sweep
    // must keep accumulating in the next (and an immersive session keeps its
    // ORIGINAL start for the auto-entry, however many sweeps it spans).
    data class SweepState(
        val lastSweep: Long = 0,
        val lastCallSweep: Long = 0,
        val carryPkg: String = "",
        val carryPkgStartedAt: Long = 0,   // real session start (for immersive spans)
        val carryScreenOnAt: Long = 0,     // 0 = screen was off
    )

    private val mutex = Mutex()
    private var loaded = false
    private var days = LinkedHashMap<String, Day>()
    private var immersive = LinkedHashMap<String, String>()  // pkg -> category
    private var labels = HashMap<String, String>()           // pkg -> human name
    private var state = SweepState()

    private val _daysFlow = MutableStateFlow<Map<String, Day>>(emptyMap())
    val daysFlow: StateFlow<Map<String, Day>> = _daysFlow
    private val _immersiveFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    val immersiveFlow: StateFlow<Map<String, String>> = _immersiveFlow
    private val _labelsFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    val labelsFlow: StateFlow<Map<String, String>> = _labelsFlow

    private val file: File get() = File(context.filesDir, FILE_NAME)

    suspend fun sweepState(): SweepState = mutex.withLock {
        ensureLoaded()
        state
    }

    suspend fun immersiveMap(): Map<String, String> = mutex.withLock {
        ensureLoaded()
        immersive.toMap()
    }

    /** category = null removes the app from the attention-eater list. */
    suspend fun setImmersive(pkg: String, category: String?): Unit = mutex.withLock {
        ensureLoaded()
        if (category.isNullOrBlank()) immersive.remove(pkg) else immersive[pkg] = category
        persist()
    }

    /**
     * Merges one sweep's result: per-day additive deltas, fresh labels and
     * the carry state for the next sweep. One lock, one write.
     */
    suspend fun applySweep(
        deltas: Map<String, DayDelta>,
        newLabels: Map<String, String>,
        newState: SweepState,
    ): Unit = mutex.withLock {
        ensureLoaded()
        for ((key, d) in deltas) {
            if (d.isEmpty()) continue
            val old = days[key] ?: Day()
            days[key] = Day(
                screenMs = old.screenMs + d.screenMs,
                pickups = old.pickups + d.pickups,
                glances = old.glances + d.glances,
                apps = mergeLong(old.apps, d.apps),
                appSessions = mergeInt(old.appSessions, d.appSessions),
                glanceApps = mergeInt(old.glanceApps, d.glanceApps),
            )
        }
        labels.putAll(newLabels)
        state = newState
        // Bound the file: drop the oldest days beyond the keep window.
        if (days.size > KEEP_DAYS) {
            val sorted = days.keys.sorted()
            for (key in sorted.take(days.size - KEEP_DAYS)) days.remove(key)
        }
        persist()
    }

    private fun mergeLong(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> {
        if (b.isEmpty()) return a
        val out = HashMap(a)
        for ((k, v) in b) out[k] = (out[k] ?: 0L) + v
        return out
    }

    private fun mergeInt(a: Map<String, Int>, b: Map<String, Int>): Map<String, Int> {
        if (b.isEmpty()) return a
        val out = HashMap(a)
        for ((k, v) in b) out[k] = (out[k] ?: 0) + v
        return out
    }

    // ---- persistence (same discipline as the other stores) ----

    private suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            val root = StoreFiles.readOrQuarantine(file) { JSONObject(it) }
            days = LinkedHashMap()
            root?.optJSONObject("days")?.let { d ->
                for (key in d.keys()) {
                    val o = d.optJSONObject(key) ?: continue
                    days[key] = Day(
                        screenMs = o.optLong("screenMs"),
                        pickups = o.optInt("pickups"),
                        glances = o.optInt("glances"),
                        apps = o.optJSONObject("apps").toLongMap(),
                        appSessions = o.optJSONObject("appSessions").toIntMap(),
                        glanceApps = o.optJSONObject("glanceApps").toIntMap(),
                    )
                }
            }
            immersive = LinkedHashMap()
            root?.optJSONObject("immersive")?.let { m ->
                for (key in m.keys()) m.optString(key).takeIf { it.isNotBlank() }?.let { immersive[key] = it }
            } ?: run { immersive.putAll(DEFAULT_IMMERSIVE) }
            labels = HashMap()
            root?.optJSONObject("labels")?.let { m ->
                for (key in m.keys()) m.optString(key).takeIf { it.isNotBlank() }?.let { labels[key] = it }
            }
            state = SweepState(
                lastSweep = root?.optLong("lastSweep") ?: 0,
                lastCallSweep = root?.optLong("lastCallSweep") ?: 0,
                carryPkg = root?.optString("carryPkg").orEmpty(),
                carryPkgStartedAt = root?.optLong("carryPkgStartedAt") ?: 0,
                carryScreenOnAt = root?.optLong("carryScreenOnAt") ?: 0,
            )
            if (root == null) persistQueued()
        }
        loaded = true
        publish()
    }

    private fun publish() {
        _daysFlow.value = days.toMap()
        _immersiveFlow.value = immersive.toMap()
        _labelsFlow.value = labels.toMap()
    }

    private fun persist() {
        persistQueued()
        publish()
    }

    private fun persistQueued() {
        val json = toJson().toString()
        DiskWriter.post { StoreFiles.writeAtomic(file, json) }
    }

    private fun toJson(): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("version", 1)
        put("lastSweep", state.lastSweep)
        put("lastCallSweep", state.lastCallSweep)
        put("carryPkg", state.carryPkg)
        put("carryPkgStartedAt", state.carryPkgStartedAt)
        put("carryScreenOnAt", state.carryScreenOnAt)
        put("immersive", JSONObject(immersive.toMap()))
        put("labels", JSONObject(labels.toMap()))
        put(
            "days",
            JSONObject().apply {
                for ((key, d) in days) {
                    put(
                        key,
                        JSONObject().apply {
                            put("screenMs", d.screenMs)
                            put("pickups", d.pickups)
                            put("glances", d.glances)
                            put("apps", JSONObject(d.apps))
                            put("appSessions", JSONObject(d.appSessions))
                            put("glanceApps", JSONObject(d.glanceApps))
                        }
                    )
                }
            }
        )
    }
}

private fun JSONObject?.toLongMap(): Map<String, Long> {
    if (this == null) return emptyMap()
    val out = HashMap<String, Long>()
    for (key in keys()) out[key] = optLong(key)
    return out
}

private fun JSONObject?.toIntMap(): Map<String, Int> {
    if (this == null) return emptyMap()
    val out = HashMap<String, Int>()
    for (key in keys()) out[key] = optInt(key)
    return out
}

/** Local-calendar day key ("2026-08-21") for a timestamp. */
fun phoneDayKey(at: Long): String =
    SimpleDateFormat(PhoneStore.DAY_KEY_FORMAT, Locale.US).format(Date(at))
