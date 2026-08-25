package ru.zf.pravka.desktop.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import ru.zf.pravka.data.DiskWriter

// Журнал распознаваний воркстанции - тот же transcriptions.jsonl и те же поля,
// что на телефоне: движок, длина аудио, время, символы. Формат общий, потому
// что дальше обе машины сводят метрики в одну таблицу.
class TranscriptionJournal(dir: File) {

    companion object {
        private const val FILE_NAME = "transcriptions.jsonl"
        private const val MAX_BYTES = 5L * 1024 * 1024
    }

    data class Entry(
        val at: String,
        val engine: String,
        val audioMs: Long,
        val transcribeMs: Long,
        val chars: Int,
        val text: String,
        val error: String?,
    )

    private val file = File(dir, FILE_NAME)
    private val timestamp = SimpleDateFormat("yyyy-MM-dd\'T\'HH:mm:ssXXX", Locale.US)

    private val _last = MutableStateFlow<List<Entry>>(emptyList())
    val lastFlow: StateFlow<List<Entry>> = _last

    fun append(engine: String, audioMs: Long, transcribeMs: Long, text: String, error: String?) {
        val at = timestamp.format(Date())
        val entry = Entry(at, engine, audioMs, transcribeMs, text.length, text, error)
        _last.value = (listOf(entry) + _last.value).take(200)
        DiskWriter.post {
            if (file.exists() && file.length() > MAX_BYTES) {
                val backup = File(file.parentFile, "$FILE_NAME.1")
                backup.delete()
                file.renameTo(backup)
            }
            val json = JSONObject().apply {
                put("ts", at)
                put("engine", engine)
                put("audio_ms", audioMs)
                put("transcribe_ms", transcribeMs)
                put("chars", text.length)
                put("text", text)
                if (error != null) put("error", error)
            }
            file.appendText(json.toString() + "\n")
        }
    }

    /** Последние записи с диска - чтобы вкладка не была пустой после запуска. */
    fun load(limit: Int = 200) {
        if (!file.exists()) return
        val entries = runCatching {
            file.readLines().asReversed().asSequence()
                .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
                .take(limit)
                .map {
                    Entry(
                        at = it.optString("ts"),
                        engine = it.optString("engine"),
                        audioMs = it.optLong("audio_ms"),
                        transcribeMs = it.optLong("transcribe_ms"),
                        chars = it.optInt("chars"),
                        text = it.optString("text"),
                        error = it.optString("error").takeIf { e -> e.isNotBlank() },
                    )
                }
                .toList()
        }.getOrElse { emptyList() }
        if (entries.isNotEmpty()) _last.value = entries
    }

    val exists: Boolean get() = file.exists() && file.length() > 0
    val path: File get() = file

    /** Метрики CSV - тем же столбцами, что выгружает телефон. */
    fun metricsCsv(): String = buildString {
        append("ts;engine;audio_s;transcribe_s;realtime;chars\n")
        for (e in _last.value.asReversed()) {
            val audio = e.audioMs / 1000.0
            val transcribe = e.transcribeMs / 1000.0
            val realtime = if (transcribe > 0) audio / transcribe else 0.0
            append(e.at).append(";").append(e.engine).append(";")
            append(String.format(Locale.US, "%.1f", audio)).append(";")
            append(String.format(Locale.US, "%.1f", transcribe)).append(";")
            append(String.format(Locale.US, "%.1f", realtime)).append(";")
            append(e.chars).append("\n")
        }
    }
}
