package ru.zf.pravka.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import ru.zf.pravka.core.NetProbe

/**
 * Замеры связи с облаками (`core/NetProbe.kt`, `provider/NetProber.kt`) —
 * строкой JSON на замер, в папке базы (`DataRoot`): картинке «есть доступ /
 * нет» нужна неделя, держим две. Замер — расходные данные, не лента: битая
 * строка пропускается, файл переписывается только уборкой старого.
 *
 * Два вида строк: проверка по расписанию («probe» — все цели разом, с сетью)
 * и живое событие («live» — тейк ушёл с молчащего облака на пакет, запрос к
 * Claude упёрся в мёртвое соединение): их рисуем на полосе метками.
 */
class NetProbeStore(private val context: Context) {

    data class Probe(val at: Long, val net: String, val hits: Map<NetProbe.Target, NetProbe.Hit>)

    data class Live(val at: Long, val target: NetProbe.Target, val text: String)

    private val file: File get() = File(DataRoot.dir(context), FILE)

    private val _last = MutableStateFlow<Probe?>(null)
    /** Последняя проверка — для строки в меню настроек и «сейчас». */
    val last: StateFlow<Probe?> = _last

    private val _version = MutableStateFlow(0)
    /** Растёт с каждой записью — экран перечитывает неделю. */
    val version: StateFlow<Int> = _version

    @Volatile private var loaded = false

    /** Когда была последняя проверка (0 — не было): чтобы не стучаться чаще интервала после перезапуска. */
    val lastAt: Long get() = _last.value?.at ?: 0L

    /** Прочитать хвост файла один раз — последняя проверка после перезапуска процесса. Не на главном потоке. */
    fun warm() {
        if (loaded) return
        loaded = true
        runCatching {
            if (!file.exists()) return
            file.useLines { lines -> lines.mapNotNull { parse(it) as? Probe }.lastOrNull() }
                ?.let { if (_last.value == null) _last.value = it }
        }
    }

    fun append(p: Probe) {
        _last.value = p
        val line = JSONObject()
            .put("at", p.at)
            .put("src", "probe")
            .put("net", p.net)
            .put("h", JSONObject().apply {
                p.hits.forEach { (t, h) ->
                    put(t.key, JSONObject().put("ok", h.ok).put("ms", h.ms).put("c", h.code).put("w", h.why))
                }
            })
            .toString()
        write(line)
    }

    fun appendLive(l: Live) {
        val line = JSONObject()
            .put("at", l.at)
            .put("src", "live")
            .put("t", l.target.key)
            .put("w", l.text)
            .toString()
        write(line)
    }

    private fun write(line: String) {
        DiskWriter.post {
            runCatching {
                file.appendText(line + "\n")
                pruneIfDue()
            }
            _version.value = _version.value + 1
        }
    }

    /** Замеры и события начиная с [sinceMs]. Читает файл — не на главном потоке. */
    fun read(sinceMs: Long): Pair<List<Probe>, List<Live>> {
        val probes = ArrayList<Probe>()
        val live = ArrayList<Live>()
        runCatching {
            if (!file.exists()) return probes to live
            file.useLines { lines ->
                for (raw in lines) {
                    when (val x = parse(raw)) {
                        is Probe -> if (x.at >= sinceMs) probes += x
                        is Live -> if (x.at >= sinceMs) live += x
                    }
                }
            }
        }
        return probes to live
    }

    fun shareIntent(): android.content.Intent = shareFileIntent(context, file, "application/json")

    private fun parse(raw: String): Any? = runCatching {
        if (raw.isBlank()) return null
        val o = JSONObject(raw)
        val at = o.getLong("at")
        when (o.optString("src")) {
            "live" -> NetProbe.Target.fromKey(o.optString("t"))?.let { Live(at, it, o.optString("w")) }
            else -> {
                val h = o.optJSONObject("h") ?: return null
                val hits = LinkedHashMap<NetProbe.Target, NetProbe.Hit>()
                for (key in h.keys()) {
                    val t = NetProbe.Target.fromKey(key) ?: continue
                    val x = h.getJSONObject(key)
                    hits[t] = NetProbe.Hit(x.optBoolean("ok"), x.optLong("ms"), x.optInt("c"), x.optString("w"))
                }
                Probe(at, o.optString("net"), hits)
            }
        }
    }.getOrNull()

    private var prunedDay = 0L

    /** Раз в сутки — выкинуть старше [KEEP_MS]: на DiskWriter, переписью во временный файл. */
    private fun pruneIfDue() {
        val now = System.currentTimeMillis()
        val day = now / 86_400_000L
        if (day == prunedDay) return
        prunedDay = day
        if (!file.exists() || file.length() < 64 * 1024) return
        val cut = now - KEEP_MS
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.bufferedWriter().use { w ->
            file.useLines { lines ->
                for (raw in lines) {
                    val at = runCatching { JSONObject(raw).getLong("at") }.getOrNull() ?: continue
                    if (at >= cut) { w.write(raw); w.write("\n") }
                }
            }
        }
        if (!tmp.renameTo(file)) tmp.delete()
    }

    companion object {
        private const val FILE = "net-probe.jsonl"
        /** Две недели: картинке нужна неделя, вторая — чтобы было с чем сравнить. */
        const val KEEP_MS = 14L * 86_400_000L
    }
}
