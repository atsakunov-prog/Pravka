package ru.zf.pravka.data

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.provider.ClaudeProvider

/**
 * Сказанное, которое не дошло до Claude (22.10.2026, Правка на диете). Владелец: «если вдруг нет интернета, то это
 * просто будет записываться куда-то». Случай: 22.10 в 10:38 «с утра моя стандартная каша» — телефон дважды по 10 с
 * не достучался до api.anthropic.com, фраза легла в «Неразобранное» вкладки Спорта, которую никто не открывает, и
 * приёма в дневнике нет. За неделю 16–22.10 таких обрывов было около десяти.
 *
 * Теперь фраза ложится сюда со своим временем и уходит заново тиком службы и при появлении сети, пока не
 * разберётся (`trigger/ServicePending.kt`). Сюда — только обрыв связи ([isNetwork]): модель ответила «еды не
 * нашлось» — повтор дал бы то же самое, и такая фраза идёт старой дорогой.
 *
 * Файл — в папке базы (`pending-takes.json`), снимки — рядом в `pending/`: переезжают с базой и попадают в копию.
 */
internal class PendingTakes(private val context: Context) {

    enum class Kind(val key: String) {
        /** Еда голосом, строкой или снимком: разбор и сразу в дневник. */
        FOOD("food"),
        /** Траты голосом: черновики и сразу в журнал, как молчаливое «ОК» плашки. */
        MONEY("money"),
        /** Засечка: запись уже лежит в ленте сырой ([Take.entryId]) — при повторе ей дописывается разбор. */
        ZASECHKA("zasechka"),
        ;

        companion object {
            fun of(key: String): Kind? = entries.firstOrNull { it.key == key }
        }
    }

    data class Take(
        val id: Long,
        val kind: Kind,
        val text: String,
        /** Когда сказано — время приёма, траты и записи ленты, а не время повтора. */
        val at: Long,
        /** Откуда пришло: voice, text, photo — как поле source у приёма. */
        val source: String = "voice",
        /** Имя снимка в `pending/`, если еда снята камерой. */
        val photo: String = "",
        /** Сырая запись ленты, которой повтор допишет разбор. */
        val entryId: Long = 0L,
        val tries: Int = 0,
        val error: String = "",
    )

    private val mutex = Mutex()
    private val state = MutableStateFlow<List<Take>>(emptyList())
    @Volatile private var loaded = false

    val flow: StateFlow<List<Take>> get() = state

    private val file: File get() = File(DataRoot.dir(context), FILE)

    fun photoDir(): File = File(DataRoot.dir(context), PHOTO_DIR).apply { mkdirs() }

    fun photoFile(t: Take): File? = t.photo.takeIf { it.isNotBlank() }?.let { File(photoDir(), it) }?.takeIf { it.exists() }

    private fun ensureLoaded() {
        if (loaded) return
        val f = file
        state.value = if (f.exists()) StoreFiles.readOrQuarantine(f) { parse(it) } ?: emptyList() else emptyList()
        loaded = true
    }

    private fun save(list: List<Take>) {
        StoreFiles.writeAtomic(file, toJson(list))
        state.value = list
    }

    suspend fun all(): List<Take> = withContext(Dispatchers.IO) { mutex.withLock { ensureLoaded(); state.value } }

    /**
     * Положить сказанное в очередь. Снимок [photo] копируется в `pending/` — исходник у вызывающего временный
     * (кэш камеры), и его можно стирать сразу.
     */
    suspend fun add(
        kind: Kind,
        text: String,
        at: Long,
        source: String = "voice",
        photo: File? = null,
        entryId: Long = 0L,
        error: String = "",
    ): Take = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val id = maxOf(System.currentTimeMillis(), (state.value.maxOfOrNull { it.id } ?: 0L) + 1)
            val photoName = photo?.takeIf { it.exists() }?.let { src ->
                val name = "$id.jpg"
                src.copyTo(File(photoDir(), name), overwrite = true)
                name
            }.orEmpty()
            val take = Take(id, kind, text.trim(), at, source, photoName, entryId, 0, error)
            save(state.value + take)
            take
        }
    }

    /** Повтор не прошёл снова из-за связи: попытка в счёт, фраза ждёт следующего раза. */
    suspend fun failed(id: Long, error: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            save(state.value.map { if (it.id == id) it.copy(tries = it.tries + 1, error = error) else it })
        }
    }

    /** Разобралось (или разбирать больше нечего): из очереди вон, снимок — тоже. */
    suspend fun done(id: Long): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val gone = state.value.firstOrNull { it.id == id } ?: return@withLock
            save(state.value.filterNot { it.id == id })
            photoFile(gone)?.let { runCatching { it.delete() } }
        }
    }

    companion object {
        const val FILE = "pending-takes.json"
        const val PHOTO_DIR = "pending"

        /**
         * Обрыв связи, а не ответ модели: не соединилось, оборвалось молча, рубильник флуда (тоже IOException),
         * 429 и 5xx после своего повтора транспорта. Всё прочее (не JSON, «еды не нашлось», 400) — не сюда.
         */
        fun isNetwork(e: Throwable): Boolean =
            e is IOException || (e is ClaudeProvider.ApiException && e.retryable)

        fun toJson(list: List<Take>): String = JSONArray().apply {
            list.forEach { t ->
                put(
                    JSONObject()
                        .put("id", t.id)
                        .put("kind", t.kind.key)
                        .put("text", t.text)
                        .put("at", t.at)
                        .put("source", t.source)
                        .put("photo", t.photo)
                        .put("entryId", t.entryId)
                        .put("tries", t.tries)
                        .put("error", t.error)
                )
            }
        }.toString(2)

        fun parse(text: String): List<Take> {
            val arr = JSONArray(text)
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val kind = Kind.of(o.optString("kind")) ?: return@mapNotNull null
                Take(
                    id = o.optLong("id"),
                    kind = kind,
                    text = o.optString("text"),
                    at = o.optLong("at"),
                    source = o.optString("source").ifBlank { "voice" },
                    photo = o.optString("photo"),
                    entryId = o.optLong("entryId"),
                    tries = o.optInt("tries"),
                    error = o.optString("error"),
                )
            }
        }
    }
}
