package ru.zf.pravka.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// Материал для самообучения Засечки: пары «что владелец сказал» → «что робот
// записал» → «на что владелец это ПОПРАВИЛ». Именно поправка и есть сигнал:
// пока он ничего не меняет, разбор его устраивает и учить не на чем.
//
// Тот же приём, что у Правки с её правилами, но предмет другой. У Правки
// учатся на тексте: как он пишет. Здесь - на смысле: что у него значит
// «созвон», куда он кладёт «разбор почты» и как называет время.
//
// Пишется на каждой правке записи, поэтому кольцо: последние MAX штук, и
// хватит. Данные не незаменимые - потеряются, накопятся заново.
class ZasechkaCorrections(private val context: Context) {

    companion object {
        private const val MAX = 200
        private const val FILE = "zasechka-corrections.json"
        private const val PREFS = "pravka_internal"
        private const val KEY_LAST_LEARN = "z_learn_at"
        private const val KEY_LAST_SEEN = "z_learn_seen"
        private const val KEY_BATCH_ID = "z_learn_batch"
        private const val KEY_BATCH_UPTO = "z_learn_batch_upto"
    }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Докуда ночной разбор уже дошёл; 0 — не ходил ни разу. */
    fun lastLearnAt(): Long = prefs().getLong(KEY_LAST_LEARN, 0L)

    fun setLastLearnAt(ts: Long) {
        prefs().edit().putLong(KEY_LAST_LEARN, ts).apply()
    }

    /**
     * Заявка ночного батча: id и надиктовка, до которой он дочитал. Водяной
     * знак двигается ТОЛЬКО когда ответ забран, — батч может не дойти, и
     * материал должен остаться целым.
     */
    fun pendingBatch(): Pair<String, Long>? {
        val id = prefs().getString(KEY_BATCH_ID, "").orEmpty()
        return if (id.isBlank()) null else id to prefs().getLong(KEY_BATCH_UPTO, 0L)
    }

    fun setPendingBatch(id: String, upTo: Long) {
        prefs().edit().putString(KEY_BATCH_ID, id).putLong(KEY_BATCH_UPTO, upTo).apply()
    }

    fun clearPendingBatch() {
        prefs().edit().remove(KEY_BATCH_ID).remove(KEY_BATCH_UPTO).apply()
    }

    /** Самая свежая разобранная надиктовка: с неё начнётся следующий проход. */
    fun lastSeenDictation(): Long = prefs().getLong(KEY_LAST_SEEN, 0L)

    fun setLastSeenDictation(ts: Long) {
        prefs().edit().putLong(KEY_LAST_SEEN, ts).apply()
    }

    /**
     * [raw] - фраза владельца целиком; без неё поправка бесполезна, учить не
     * на чем. Поэтому записи без надиктовки сюда не попадают.
     */
    data class Correction(
        val ts: Long,
        val raw: String,
        val wasTitle: String,
        val wasCategory: String,
        val nowTitle: String,
        val nowCategory: String,
    ) {
        /** Что именно он поправил - одной строкой, для промпта. */
        val what: String
            get() = buildList {
                if (!wasTitle.equals(nowTitle, ignoreCase = true)) add("название")
                if (!wasCategory.equals(nowCategory, ignoreCase = true)) add("категорию")
            }.joinToString(" и ")
    }

    private val mutex = Mutex()
    private var loaded = false
    private val items = mutableListOf<Correction>()

    private fun file() = File(context.filesDir, FILE)

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val parsed = StoreFiles.readOrQuarantine(file()) { text ->
            val array = JSONArray(text)
            val out = mutableListOf<Correction>()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val raw = o.optString("raw").trim()
                if (raw.isEmpty()) continue
                out.add(
                    Correction(
                        ts = o.optLong("ts"),
                        raw = raw,
                        wasTitle = o.optString("wasTitle"),
                        wasCategory = o.optString("wasCat"),
                        nowTitle = o.optString("nowTitle"),
                        nowCategory = o.optString("nowCat"),
                    )
                )
            }
            out
        }
        if (parsed != null) items.addAll(parsed)
    }

    /**
     * Записывает поправку. Молча пропускает то, на чём нечему учиться:
     * фразы нет, или изменилось только время (это про часы, а не про то,
     * как он говорит).
     */
    suspend fun record(before: ZasechkaStore.Entry, after: ZasechkaStore.Entry) =
        withContext(Dispatchers.IO) {
            val raw = before.raw.trim().ifBlank { after.raw.trim() }
            if (raw.isEmpty()) return@withContext
            val changed = !before.title.equals(after.title, ignoreCase = true) ||
                !before.category.equals(after.category, ignoreCase = true)
            if (!changed) return@withContext
            mutex.withLock {
                ensureLoaded()
                items.add(
                    Correction(
                        ts = System.currentTimeMillis(),
                        raw = raw.take(400),
                        wasTitle = before.title,
                        wasCategory = before.category,
                        nowTitle = after.title,
                        nowCategory = after.category,
                    )
                )
                while (items.size > MAX) items.removeAt(0)
                persist()
            }
        }

    suspend fun all(): List<Correction> = withContext(Dispatchers.IO) {
        mutex.withLock { ensureLoaded(); items.toList() }
    }

    /** После удачного разбора: учить на одном и том же дважды незачем. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            items.clear()
            persist()
        }
    }

    private fun persist() {
        val json = JSONArray().apply {
            for (c in items) {
                put(
                    JSONObject().apply {
                        put("ts", c.ts)
                        put("raw", c.raw)
                        put("wasTitle", c.wasTitle)
                        put("wasCat", c.wasCategory)
                        put("nowTitle", c.nowTitle)
                        put("nowCat", c.nowCategory)
                    }
                )
            }
        }.toString()
        DiskWriter.post { StoreFiles.writeAtomic(file(), json) }
    }
}
