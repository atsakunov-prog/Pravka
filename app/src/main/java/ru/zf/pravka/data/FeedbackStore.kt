package ru.zf.pravka.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.FeedbackDone
import java.io.File

/**
 * Баги и предложения, сказанные с кнопок (07.10.2026, владелец: «на долгом
 * нажатии на кнопках… записать баг или предложение. И тогда у нас будет
 * копиться и раз в день можно это открывать и обновлять»).
 *
 * Запись живёт с номером (№1, №2…): по нему её называют сессия, которая
 * чинит, и строка в `assets/feedback_done.txt` (`core/FeedbackDone.kt`),
 * по которой приехавшая сборка отмечает её сделанной. Сказанное (`raw`) не
 * удаляется и не правится никогда — рядом лежит текст после чистки Правкой.
 * Записи не удаляются: «не нужно» — это статус «отложено», а не пропажа.
 *
 * Файл — `feedback.json` в базе (`DataRoot.dir`), запись атомарная
 * (`StoreFiles`); в архив на компе записи уезжают видом `pravka.feedback`.
 */
class FeedbackStore(private val context: Context) {

    data class Item(
        val num: Int,
        val ts: Long,
        /** С какой кнопки сказано: П, З, Д, ₽ — или «приложение». */
        val origin: String,
        /** Сказанное как есть — сырьё, не меняется. */
        val raw: String,
        /** После чистки Правкой; пусто — чистка ещё не прошла или упала. */
        val text: String = "",
        /** Сборка, в которой сказано («3.0.787»). */
        val version: String = "",
        val status: String = NEW,
        /** Что сделано или почему отложено — из `feedback_done.txt` или руками. */
        val note: String = "",
        val doneAt: Long = 0L,
        /** Номер сборки, которая отметила запись. */
        val build: Int = 0,
        /** Статус поставлен руками — сборка его больше не перебивает. */
        val manual: Boolean = false,
    ) {
        val shown: String get() = text.ifBlank { raw }
        val open: Boolean get() = status == NEW
    }

    private val mutex = Mutex()
    private var loaded = false
    private val items = mutableListOf<Item>()

    private val _flow = MutableStateFlow<List<Item>>(emptyList())
    /** Свежие сверху. */
    val flow: StateFlow<List<Item>> = _flow

    private fun file() = File(DataRoot.dir(context), FILE_NAME)

    /** Чтение с диска и сверка с тем, что сборка знает о сделанном. */
    suspend fun load(): List<Item> = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            items.sortedByDescending { it.num }
        }
    }

    suspend fun all(): List<Item> = load()

    /** Новая запись: сырьё сразу на диск — чистка догонит потом. */
    suspend fun add(raw: String, origin: String, version: String): Item = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val item = Item(
                num = (items.maxOfOrNull { it.num } ?: 0) + 1,
                ts = System.currentTimeMillis(),
                origin = origin,
                raw = raw.take(8000),
                version = version,
            )
            items.add(item)
            persist()
            item
        }
    }

    suspend fun setText(num: Int, text: String) = update(num) { it.copy(text = text.take(8000)) }

    /** Статус руками: «сделано», «отложено» или снова «новое». */
    suspend fun setStatus(num: Int, status: String, note: String = "") = update(num) {
        it.copy(
            status = status,
            note = note.ifBlank { if (status == NEW) "" else it.note },
            doneAt = if (status == NEW) 0L else System.currentTimeMillis(),
            build = if (status == NEW) 0 else it.build,
            manual = true,
        )
    }

    private suspend fun update(num: Int, change: (Item) -> Item) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val i = items.indexOfFirst { it.num == num }
            if (i < 0) return@withLock
            val next = change(items[i])
            if (next == items[i]) return@withLock
            items[i] = next
            persist()
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        val parsed = StoreFiles.readOrQuarantine(file()) { text ->
            val a = JSONArray(text)
            (0 until a.length()).mapNotNull { k -> a.optJSONObject(k)?.let(::fromJson) }
        }
        loaded = true
        if (parsed != null) items.addAll(parsed)
        // Сборка знает, что по каким номерам сделано, — отмечаем только новые:
        // поставленное руками («снова новое») сборка не перебивает.
        val done = runCatching {
            FeedbackDone.parse(context.assets.open(DONE_ASSET).bufferedReader().use { it.readText() })
        }.getOrDefault(emptyMap())
        var changed = false
        val now = System.currentTimeMillis()
        for (k in items.indices) {
            val it = items[k]
            val d = done[it.num] ?: continue
            if (!it.open || it.manual) continue
            items[k] = it.copy(
                status = if (d.skip) SKIP else DONE,
                note = d.note,
                doneAt = now,
                build = ru.zf.pravka.BuildConfig.VERSION_CODE,
            )
            changed = true
        }
        if (changed) persist() else publish()
    }

    private fun publish() {
        _flow.value = items.sortedByDescending { it.num }
    }

    private fun persist() {
        publish()
        // Пустой список поверх файла не пишем (железное правило 1): записи не
        // удаляются, пустота здесь — только поломка чтения.
        if (items.isEmpty()) return
        val a = JSONArray()
        for (it in items) a.put(toJson(it))
        runCatching { StoreFiles.writeAtomic(file(), a.toString()) }
    }

    companion object {
        const val FILE_NAME = "feedback.json"
        const val DONE_ASSET = "feedback_done.txt"
        const val NEW = "new"
        const val DONE = "done"
        const val SKIP = "skip"

        fun statusWord(status: String): String = when (status) {
            DONE -> "сделано"
            SKIP -> "отложено"
            else -> "новое"
        }

        private fun toJson(i: Item) = JSONObject()
            .put("num", i.num).put("ts", i.ts).put("origin", i.origin)
            .put("raw", i.raw).put("text", i.text).put("version", i.version)
            .put("status", i.status).put("note", i.note).put("doneAt", i.doneAt).put("build", i.build)
            .put("manual", i.manual)

        private fun fromJson(o: JSONObject): Item? {
            val num = o.optInt("num", 0)
            if (num <= 0) return null
            return Item(
                num = num,
                ts = o.optLong("ts"),
                origin = o.optString("origin"),
                raw = o.optString("raw"),
                text = o.optString("text"),
                version = o.optString("version"),
                status = o.optString("status", NEW).ifBlank { NEW },
                note = o.optString("note"),
                doneAt = o.optLong("doneAt"),
                build = o.optInt("build"),
                manual = o.optBoolean("manual"),
            )
        }
    }
}
