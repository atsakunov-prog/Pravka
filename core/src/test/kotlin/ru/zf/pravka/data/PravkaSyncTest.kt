package ru.zf.pravka.data

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.DictMode

/**
 * Два устройства и общая таблица. Сервер здесь - маленькая копия скрипта из
 * docs/pravka-sync.md: тот же ключ (uid), тот же спор по времени правки.
 * Проверяем ровно то, что ломается в таких обменах: сходятся ли словари,
 * не воскресает ли удалённое и не удваивается ли одно и то же слово,
 * заведённое на двух машинах порознь.
 */
class PravkaSyncTest {

    private lateinit var server: HttpServer
    private lateinit var client: OkHttpClient
    private var url = ""

    // Общее состояние "таблицы": uid -> запись.
    private val dictRows = LinkedHashMap<String, JSONObject>()
    private val ruleRows = LinkedHashMap<String, JSONObject>()

    @BeforeTest
    fun start() {
        client = OkHttpClient()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/exec") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            val payload = JSONObject(body)
            merge(dictRows, payload.optJSONArray("dict"))
            merge(ruleRows, payload.optJSONArray("rules"))
            val answer = JSONObject().apply {
                put("serverTime", System.currentTimeMillis())
                put("dict", JSONArray(dictRows.values.toList()))
                put("rules", JSONArray(ruleRows.values.toList()))
            }.toString().toByteArray()
            exchange.sendResponseHeaders(200, answer.size.toLong())
            exchange.responseBody.use { it.write(answer) }
        }
        server.start()
        url = "http://127.0.0.1:" + server.address.port + "/exec"
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun merge(rows: LinkedHashMap<String, JSONObject>, incoming: JSONArray?) {
        for (i in 0 until (incoming?.length() ?: 0)) {
            val item = incoming!!.getJSONObject(i)
            val uid = item.optString("uid")
            if (uid.isEmpty()) continue
            val existing = rows[uid]
            val winner = when {
                existing == null -> item
                item.optLong("updatedAt") > existing.optLong("updatedAt") -> item
                else -> existing
            }
            winner.put("hits", maxOf(existing?.optInt("hits") ?: 0, item.optInt("hits")))
            rows[uid] = winner
        }
    }

    private class Device(name: String, url: String, client: OkHttpClient) {
        val dir: File = File(System.getProperty("java.io.tmpdir"), "pravka-sync-" + name + "-" + System.nanoTime())
            .apply { mkdirs(); deleteOnExit() }
        val dictionary = DictionaryStore(dir) { null }
        val rules = RulesStore(dir)
        val settings = object : SyncSettings {
            var last = 0L
            private val address = url
            override suspend fun syncUrl() = address
            override suspend fun lastSyncAt() = last
            override suspend fun setLastSyncAt(value: Long) { last = value }
        }
        val sync = PravkaSync(client, dictionary, rules, settings, name)
    }

    @Test
    fun `two devices converge on one dictionary`() = runBlocking {
        val phone = Device("pixel", url, client)
        val work = Device("workstation", url, client)

        phone.dictionary.add("цакунов", "Цакунов", DictMode.HARD, "")
        work.dictionary.add("зэф", "ЗФ", DictMode.HARD, "")

        phone.sync.syncNow().getOrThrow()
        work.sync.syncNow().getOrThrow()
        phone.sync.syncNow().getOrThrow()

        val onPhone = phone.dictionary.all().map { it.from }.sorted()
        val onWork = work.dictionary.all().map { it.from }.sorted()
        assertEquals(listOf("зэф", "цакунов"), onPhone)
        assertEquals(onPhone, onWork)
    }

    @Test
    fun `the same word entered on both devices does not double`() = runBlocking {
        val phone = Device("pixel", url, client)
        val work = Device("workstation", url, client)

        phone.dictionary.add("сейф", "", DictMode.HINT, "хранилище")
        work.dictionary.add("Сейф", "", DictMode.HINT, "")

        phone.sync.syncNow().getOrThrow()
        work.sync.syncNow().getOrThrow()
        phone.sync.syncNow().getOrThrow()

        assertEquals(1, phone.dictionary.all().size, phone.dictionary.all().toString())
        assertEquals(1, work.dictionary.all().size, work.dictionary.all().toString())
    }

    @Test
    fun `a deleted word does not come back from the other device`() = runBlocking {
        val phone = Device("pixel", url, client)
        val work = Device("workstation", url, client)

        val entry = phone.dictionary.add("осу", "ОСУ", DictMode.HARD, "")
        phone.sync.syncNow().getOrThrow()
        work.sync.syncNow().getOrThrow()
        assertEquals(1, work.dictionary.all().size)

        phone.dictionary.delete(entry.id)
        phone.sync.syncNow().getOrThrow()
        work.sync.syncNow().getOrThrow()

        assertTrue(phone.dictionary.all().isEmpty())
        assertTrue(work.dictionary.all().isEmpty(), work.dictionary.all().toString())
        // На второй машине запись остаётся надгробием - иначе она уехала бы
        // обратно на телефон при следующем обмене.
        assertTrue(work.dictionary.allForSync().single().deleted)
    }

    @Test
    fun `the later edit wins`() = runBlocking {
        val phone = Device("pixel", url, client)
        val work = Device("workstation", url, client)

        phone.dictionary.add("зэф", "ЗФ", DictMode.HARD, "")
        phone.sync.syncNow().getOrThrow()
        work.sync.syncNow().getOrThrow()

        val onWork = work.dictionary.all().single()
        work.dictionary.update(onWork.copy(to = "Знакомый Финансист"))
        work.sync.syncNow().getOrThrow()
        phone.sync.syncNow().getOrThrow()

        assertEquals("Знакомый Финансист", phone.dictionary.all().single().to)
    }

    @Test
    fun `rules travel too and deletions stick`() = runBlocking {
        val phone = Device("pixel", url, client)
        val work = Device("workstation", url, client)

        phone.rules.add("Не начинай письмо с «Доброго времени суток»")
        phone.sync.syncNow().getOrThrow()
        work.sync.syncNow().getOrThrow()
        assertEquals(1, work.rules.all().size)

        work.rules.delete(work.rules.all().single().id)
        work.sync.syncNow().getOrThrow()
        phone.sync.syncNow().getOrThrow()
        assertTrue(phone.rules.all().isEmpty())
    }

    @Test
    fun `without an address nothing is sent`() = runBlocking {
        val quiet = object : SyncSettings {
            override suspend fun syncUrl() = ""
            override suspend fun lastSyncAt() = 0L
            override suspend fun setLastSyncAt(value: Long) = Unit
        }
        val dir = File(System.getProperty("java.io.tmpdir"), "pravka-sync-off-" + System.nanoTime())
        dir.mkdirs()
        dir.deleteOnExit()
        val sync = PravkaSync(client, DictionaryStore(dir) { null }, RulesStore(dir), quiet, "pixel")
        assertNull(sync.maybeSync())
        assertTrue(sync.syncNow().isFailure)
    }
}
