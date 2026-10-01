package ru.zf.pravka.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.SpeechUse

/**
 * Счёт службы речи по суткам (`core/SpeechUse.kt`) — один маленький файл в
 * папке базы (`DataRoot`). Расходные данные: битый файл уходит в `.corrupt`,
 * счёт начинается заново. Учитывают с любого потока; читает, считает и пишет
 * только поток `DiskWriter` — по одному, без гонок.
 */
class SpeechUseStore(private val context: Context) {

    private val file: File get() = File(DataRoot.dir(context), FILE)

    private val _days = MutableStateFlow<List<SpeechUse.Day>>(emptyList())
    /** Сутки по возрастанию дат — для экрана. */
    val days: StateFlow<List<SpeechUse.Day>> = _days

    private var loaded = false

    /** Учесть [ms] вида [kind] (сутки — по местному времени сейчас). С любого потока. */
    fun add(kind: SpeechUse.Kind, ms: Long, count: Int = 1) {
        val day = dayKey(System.currentTimeMillis())
        DiskWriter.post {
            load()
            val next = SpeechUse.add(_days.value, day, kind, ms, count)
            _days.value = next
            runCatching { StoreFiles.writeAtomic(file, encode(next)) }
        }
    }

    /** Прочитать файл, если ещё не читали, — экран зовёт при открытии. */
    fun warm() {
        DiskWriter.post { load() }
    }

    /** Сутки по ключу («2026-10-01»). */
    fun day(key: String): SpeechUse.Day? = _days.value.firstOrNull { it.day == key }

    private fun load() {
        if (loaded) return
        loaded = true
        StoreFiles.readOrQuarantine(file, ::decode)?.let { _days.value = it }
    }

    private fun encode(days: List<SpeechUse.Day>): String = JSONObject().put(
        "days",
        JSONArray().apply {
            days.forEach { d ->
                put(
                    JSONObject()
                        .put("d", d.day)
                        .put("ms", JSONObject().apply { d.ms.forEach { (k, v) -> put(k.key, v) } })
                        .put("n", JSONObject().apply { d.n.forEach { (k, v) -> put(k.key, v) } })
                )
            }
        },
    ).toString()

    private fun decode(text: String): List<SpeechUse.Day> {
        val arr = JSONObject(text).getJSONArray("days")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ms = o.optJSONObject("ms")
            val n = o.optJSONObject("n")
            SpeechUse.Day(
                day = o.getString("d"),
                ms = SpeechUse.Kind.entries.mapNotNull { k -> ms?.takeIf { it.has(k.key) }?.let { k to it.getLong(k.key) } }.toMap(),
                n = SpeechUse.Kind.entries.mapNotNull { k -> n?.takeIf { it.has(k.key) }?.let { k to it.getInt(k.key) } }.toMap(),
            )
        }
    }

    companion object {
        private const val FILE = "speech-use.json"

        fun dayKey(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))
    }
}
