package ru.zf.pravka.data

import android.content.Context
import android.content.Intent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Журнал правок руками — три столбца, о которых просил владелец (15.09.2026):
 * «я надиктовал, он поправил с помощью модели, и дальше я поправил — три
 * столбца, которые потом надо обучать и добавлять в словарь».
 *
 * `EditWatchStore` — рабочая тетрадь на несколько дней: помнит доставленное и
 * ловит правку. Здесь — то, что из этого вышло, навсегда: надиктовано → модель
 * → владелец, и что с этим сделали (в словарь сразу, ждёт Опуса, разобрано,
 * ничего словарного). Отсюда «Разобрать сейчас» берёт очередь, отсюда же
 * выгрузка CSV для чата. Файл — JSON-массив под StoreFiles, последние 500
 * строк: правок руками десятки в неделю, не тысячи.
 */
class CorrectionsLog(private val context: Context) {

    data class Entry(
        val id: Long,
        val ts: Long,
        val pkg: String,
        val dictated: String,
        val cleaned: String,
        val edited: String,
        /** Что сделано: dict:HARD:a→b · dict:HINT:a→b · pending · opus:… · none */
        val result: String,
        val done: Boolean,
    ) {
        val pending: Boolean get() = !done && result == "pending"
    }

    private val mutex = Mutex()
    private var loaded = false
    private val entries = mutableListOf<Entry>()
    private var nextId = 1L

    private fun file() = File(DataRoot.dir(context), "corrections.json")

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val parsed = StoreFiles.readOrQuarantine(file()) { text ->
            val array = JSONArray(text)
            val out = mutableListOf<Entry>()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                out.add(
                    Entry(
                        id = o.optLong("id"),
                        ts = o.optLong("ts"),
                        pkg = o.optString("pkg"),
                        dictated = o.optString("dictated"),
                        cleaned = o.optString("cleaned"),
                        edited = o.optString("edited"),
                        result = o.optString("result"),
                        done = o.optBoolean("done", false),
                    )
                )
            }
            out
        }
        if (parsed != null) entries.addAll(parsed)
        nextId = (entries.maxOfOrNull { it.id } ?: 0L) + 1
    }

    private fun persist() {
        runCatching {
            val array = JSONArray()
            for (e in entries) {
                array.put(
                    JSONObject().apply {
                        put("id", e.id); put("ts", e.ts); put("pkg", e.pkg)
                        put("dictated", e.dictated); put("cleaned", e.cleaned); put("edited", e.edited)
                        put("result", e.result); put("done", e.done)
                    }
                )
            }
            StoreFiles.writeAtomic(file(), array.toString())
        }
    }

    suspend fun append(
        pkg: String,
        dictated: String,
        cleaned: String,
        edited: String,
        result: String,
        done: Boolean,
    ): Entry = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val e = Entry(nextId++, System.currentTimeMillis(), pkg, dictated.take(4000), cleaned.take(4000), edited.take(4000), result, done)
            entries.add(e)
            while (entries.size > 500) entries.removeAt(0)
            persist()
            e
        }
    }

    suspend fun all(): List<Entry> = withContext(Dispatchers.IO) { mutex.withLock { ensureLoaded(); entries.toList() } }

    /** Сложные правки, которых Опус ещё не видел, — очередь для «Разобрать сейчас». */
    suspend fun pending(): List<Entry> = withContext(Dispatchers.IO) {
        mutex.withLock { ensureLoaded(); entries.filter { it.pending } }
    }

    suspend fun markDone(ids: Collection<Long>, result: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            var changed = false
            for (i in entries.indices) {
                val e = entries[i]
                if (e.id in ids && !e.done) { entries[i] = e.copy(done = true, result = result); changed = true }
            }
            if (changed) persist()
        }
    }

    /**
     * CSV трёх столбцов (плюс время, приложение и итог) за период — для чата или
     * таблицы. Кавычки и переводы строк экранируются по RFC 4180.
     */
    suspend fun shareCsvIntent(fromMs: Long = 0L, toMs: Long = Long.MAX_VALUE): Intent = withContext(Dispatchers.IO) {
        val rows = all().filter { it.ts in fromMs until toMs }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val csv = buildString {
            append("время,приложение,надиктовано,правка модели,правка владельца,итог\n")
            for (e in rows) {
                append(stamp.format(Date(e.ts))).append(',')
                append(q(e.pkg)).append(',')
                append(q(e.dictated)).append(',')
                append(q(e.cleaned)).append(',')
                append(q(e.edited)).append(',')
                append(q(e.result)).append('\n')
            }
        }
        val out = File(context.cacheDir, "pravka-corrections.csv")
        out.writeText(csv)
        shareFileIntent(context, out, "text/csv")
    }
}
