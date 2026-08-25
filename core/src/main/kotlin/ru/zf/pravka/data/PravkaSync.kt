package ru.zf.pravka.data

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.flow.firstOrNull
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.DictEntry
import ru.zf.pravka.core.DictMode

/**
 * Один словарь на телефоне и на воркстанции.
 *
 * Общая таблица в Google-аккаунте владельца, за ней - маленькое веб-приложение
 * Apps Script (docs/pravka-sync.md). Тот же приём, что у Засечки: никакого
 * OAuth, секретом служит сам URL скрипта.
 *
 * Обмен - один POST. Словарь и правила уезжают ЦЕЛИКОМ, а не приростом: их
 * сотни записей, это десятки килобайт, зато не надо вести на каждом
 * устройстве учёт отправленного - а именно такой учёт обычно и врёт после
 * сбоя. Расшифровки и статистика идут только вверх.
 *
 * Спор решается временем правки: чья запись новее, та и права. Удаление -
 * надгробием (см. DictionaryStore.delete), иначе удалённое слово возвращалось
 * бы со второго устройства.
 */
class PravkaSync(
    private val client: OkHttpClient,
    private val dictionary: DictionaryStore,
    private val rules: RulesStore,
    private val settings: SyncSettings,
    /** "pixel" или "workstation" - чтобы в таблице было видно, кто что прислал. */
    private val device: String,
    private val log: (String) -> Unit = {},
) {

    /** Что устройство умеет отдать наверх сверх словаря и правил. */
    interface Contributor {
        /** Расшифровки новее [since]: устройство, время, движок, метрики. */
        suspend fun transcripts(since: Long): List<JSONObject> = emptyList()

        /** Сводка расхода этого устройства: одна строка на устройство. */
        suspend fun statsRow(): JSONObject? = null

        /** Переопределённые промпты: ключ, текст, время правки. */
        suspend fun prompts(): List<JSONObject> = emptyList()

        /** Промпты, приехавшие сверху: применить те, что новее наших. */
        suspend fun applyPrompts(incoming: List<JSONObject>) = Unit
    }

    var contributor: Contributor? = null

    data class Report(val dictChanged: Int, val rulesChanged: Int, val pushed: Int)

    /**
     * Полный обмен. Возвращает ошибку в Result.failure - вызывающая сторона
     * решает, показывать её или просто отложить до следующего раза.
     */
    suspend fun syncNow(): Result<Report> = withContext(Dispatchers.IO) {
        runCatching {
            val url = settings.syncUrl().trim()
            if (url.isBlank()) throw IllegalStateException("Адрес синхронизации не задан.")

            val since = settings.lastSyncAt()
            val extra = contributor
            val transcripts = extra?.transcripts(since).orEmpty()

            val payload = JSONObject().apply {
                put("device", device)
                put("since", since)
                put("dict", JSONArray(dictionary.allForSync().map { it.toJson() }))
                put("rules", JSONArray(rules.allForSync().map { it.toJson() }))
                put("prompts", JSONArray(extra?.prompts().orEmpty()))
                put("transcripts", JSONArray(transcripts))
                extra?.statsRow()?.let { put("stats", JSONArray(listOf(it))) }
            }

            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val body = client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("Таблица ответила ${response.code}: ${text.take(200)}")
                }
                text
            }

            val root = JSONObject(body)
            if (root.optBoolean("error")) {
                throw IllegalStateException(root.optString("message", "Скрипт вернул ошибку"))
            }

            val dictChanged = dictionary.mergeFromSync(root.optJSONArray("dict").toDictEntries())
            val rulesChanged = rules.mergeFromSync(root.optJSONArray("rules").toRules())
            extra?.applyPrompts(root.optJSONArray("prompts").toObjects())

            // Время берём с сервера: часы двух машин расходятся, и локальное
            // "сейчас" однажды пропустило бы запись, сделанную на той стороне.
            settings.setLastSyncAt(root.optLong("serverTime", System.currentTimeMillis()))
            val report = Report(dictChanged, rulesChanged, transcripts.size)
            log("sync: словарь +-$dictChanged, правила +-$rulesChanged, расшифровок отправлено ${transcripts.size}")
            report
        }
    }

    /**
     * Синхронизация "если пора": вызывается с тика раз в несколько минут и
     * сама решает, прошло ли [minIntervalMs] с прошлого удачного обмена.
     */
    suspend fun maybeSync(minIntervalMs: Long = TWELVE_HOURS): Result<Report>? {
        if (settings.syncUrl().isBlank()) return null
        val last = settings.lastSyncAt()
        if (last > 0 && System.currentTimeMillis() - last < minIntervalMs) return null
        return syncNow()
    }

    /**
     * Отложенный обмен после правки словаря руками: новое слово должно доехать
     * до второго устройства сразу, а не завтра. Пачка правок подряд сходится
     * в один запрос.
     */
    fun kickSoon(scope: kotlinx.coroutines.CoroutineScope, delayMs: Long = 30_000) {
        if (!kicked.compareAndSet(false, true)) return
        scope.launch {
            kotlinx.coroutines.delay(delayMs)
            kicked.set(false)
            if (settings.syncUrl().isNotBlank()) syncNow()
        }
    }

    private val kicked = java.util.concurrent.atomic.AtomicBoolean(false)

    companion object {

        const val TWELVE_HOURS = 12L * 60 * 60 * 1000

        fun DictEntry.toJson(): JSONObject = JSONObject().apply {
            put("uid", uid)
            put("from", from)
            put("to", to)
            put("mode", mode.name)
            put("note", note)
            put("hits", hits)
            put("enabled", enabled)
            put("createdAt", createdAt)
            put("updatedAt", updatedAt)
            put("deleted", deleted)
        }

        fun RulesStore.Rule.toJson(): JSONObject = JSONObject().apply {
            put("uid", uid)
            put("text", text)
            put("enabled", enabled)
            put("created", createdTs)
            put("updatedAt", updatedAt)
            put("deleted", deleted)
            put("before", exampleBefore)
            put("after", exampleAfter)
        }

        private fun JSONArray?.toObjects(): List<JSONObject> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { optJSONObject(it) }
        }

        private fun JSONArray?.toDictEntries(): List<DictEntry> = toObjects().mapNotNull { o ->
            val mode = runCatching { DictMode.valueOf(o.optString("mode", "HARD")) }.getOrNull()
                ?: return@mapNotNull null
            val from = o.optString("from").trim()
            if (from.isEmpty()) return@mapNotNull null
            DictEntry(
                uid = o.optString("uid"),
                from = from,
                to = o.optString("to").trim(),
                mode = mode,
                note = o.optString("note").trim(),
                hits = o.optInt("hits"),
                enabled = o.optBoolean("enabled", true),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt"),
                deleted = o.optBoolean("deleted", false),
            )
        }

        private fun JSONArray?.toRules(): List<RulesStore.Rule> = toObjects().mapNotNull { o ->
            val text = o.optString("text").trim()
            if (text.isEmpty()) return@mapNotNull null
            RulesStore.Rule(
                id = 0,
                text = text,
                enabled = o.optBoolean("enabled", true),
                createdTs = o.optLong("created"),
                uid = o.optString("uid"),
                updatedAt = o.optLong("updatedAt"),
                deleted = o.optBoolean("deleted", false),
                exampleBefore = o.optString("before"),
                exampleAfter = o.optString("after"),
            )
        }
    }
}

/** Адрес общей таблицы и отметка последней удачной синхронизации. */
interface SyncSettings {
    suspend fun syncUrl(): String
    suspend fun lastSyncAt(): Long
    suspend fun setLastSyncAt(value: Long)
}

/**
 * Время правки промптов. Хранилище самих текстов у платформ разное (DataStore
 * на телефоне, файл на воркстанции), а вот "когда правили" нужно обеим - и
 * заводить ради одного числа миграцию хранилища не стоит.
 */
class PromptSyncMeta(dir: File) {

    private val file = File(dir, "prompt-meta.json")
    private var root: JSONObject = StoreFiles.readOrQuarantine(file) { JSONObject(it) } ?: JSONObject()

    @Synchronized
    fun updatedAt(key: String): Long = root.optLong(key, 0)

    @Synchronized
    fun touch(key: String, at: Long = System.currentTimeMillis()) {
        root.put(key, at)
        val text = root.toString(2)
        DiskWriter.post { StoreFiles.writeAtomic(file, text) }
    }
}


/**
 * Промпты в общей таблице: ключ, текст, время правки. Хранилище текстов у
 * платформ разное, а логика обмена одна, поэтому она здесь.
 */
class PromptSyncSupport(
    private val store: PromptStore,
    private val meta: PromptSyncMeta,
) {

    /** Только переопределённые: заводские тексты приезжают с программой. */
    suspend fun export(): List<JSONObject> = PromptStore.PromptId.entries.mapNotNull { id ->
        val text = store.overrideFlow(id).firstOrNull() ?: return@mapNotNull null
        if (text.isBlank()) return@mapNotNull null
        JSONObject().apply {
            put("key", id.storageKey)
            put("text", text)
            put("updatedAt", meta.updatedAt(id.storageKey))
        }
    }

    /** Применяет то, что новее нашего. Заводской текст ничем не затирается. */
    suspend fun apply(incoming: List<JSONObject>) {
        for (o in incoming) {
            val key = o.optString("key")
            val id = PromptStore.PromptId.entries.firstOrNull { it.storageKey == key } ?: continue
            val text = o.optString("text")
            if (text.isBlank()) continue
            val at = o.optLong("updatedAt")
            if (at <= meta.updatedAt(key)) continue
            store.setOverride(id, text)
            meta.touch(key, at)
        }
    }
}
