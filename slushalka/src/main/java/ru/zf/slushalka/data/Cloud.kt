package ru.zf.slushalka.data

import android.util.Xml
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.xmlpull.v1.XmlPullParser

/**
 * Облако: семейный Google Drive (`DriveCloud`, вход браузером — 25.09.2026)
 * или WebDAV. Дела одни и те же, выбор — в настройках (`Prefs.cloudKind`);
 * ниже — WebDAV.
 *
 * Облако по WebDAV: Яндекс.Диск (логин и «пароль приложения»), Nextcloud,
 * Box, Koofr - кто угодно, кто говорит WebDAV. Протокол простой - PROPFIND,
 * GET, PUT, MKCOL поверх HTTP - и не требует ни регистрации приложения, ни
 * своего сервера: это и есть «базовая синхронизация» без сторонней программы.
 *
 * Раскладка в облаке, от папки из настроек (заводская - «Слушалка»):
 * `_Слушалка/` - те же файлы позиций, вопросов и пометок, что в папке
 * библиотеки ([PositionSync]); `Книги/<папка книги>/` - книги целиком, как
 * они лежат на полке.
 */
class Cloud(private val settings: Settings, private val drive: DriveCloud? = null) {

    /** Выбран семейный Google Drive: все дела — туда, WebDAV молчит. */
    private val onDrive: Boolean get() = drive != null && settings.now().cloudDrive


    data class Item(val path: String, val name: String, val dir: Boolean, val size: Long, val modified: Long)

    class CloudException(message: String) : Exception(message)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Книга качается файлами по сотне мегабайт: чтение без предела, иначе
        // медленный Диск обрывал бы главу на середине.
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .build()

    val ready: Boolean get() = settings.now().cloudReady

    /** Корень наших файлов в облаке: адрес сервера плюс папка из настроек. */
    private fun url(path: String): String {
        val p = settings.now()
        val parts = (p.cloudDir.split('/') + path.split('/')).filter { it.isNotBlank() }
        return p.cloudUrl.trimEnd('/') + "/" + parts.joinToString("/") { enc(it) } +
            if (path.endsWith("/") || path.isEmpty()) "/" else ""
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun auth(b: Request.Builder): Request.Builder {
        val p = settings.now()
        return b.header("Authorization", Credentials.basic(p.cloudUser, p.cloudPass, Charsets.UTF_8))
    }

    /** Проверка настроек: папка есть или заводится, логин и пароль приняты. */
    suspend fun check(): Result<Unit> = if (onDrive) drive!!.check() else withContext(Dispatchers.IO) {
        runCatching {
            if (!ready) throw CloudException("Облако не настроено: адрес, логин и пароль приложения")
            ensureDir("")
            list("").getOrThrow()
            Unit
        }
    }

    /** Что лежит в папке (без неё самой). */
    suspend fun list(path: String): Result<List<Item>> = if (onDrive) drive!!.list(path) else withContext(Dispatchers.IO) {
        runCatching {
            val body = """<?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
                .toRequestBody("application/xml; charset=utf-8".toMediaType())
            val req = auth(Request.Builder().url(url(path.trimEnd('/') + "/")))
                .method("PROPFIND", body)
                .header("Depth", "1")
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 404) return@runCatching emptyList<Item>()
                if (resp.code == 401) throw CloudException("Облако не приняло логин или пароль приложения")
                if (!resp.isSuccessful) throw CloudException("Облако ответило ${resp.code}")
                val self = url(path.trimEnd('/') + "/").substringAfter("://").substringAfter('/')
                parse(resp.body?.byteStream() ?: return@runCatching emptyList<Item>())
                    .filter { it.path.trim('/') != URLDecoder.decode(self, "UTF-8").trim('/') }
            }
        }
    }

    /** Файл целиком - для маленьких файлов синхронизации. null - файла нет. */
    suspend fun getText(path: String): Result<String?> = if (onDrive) drive!!.getText(path) else withContext(Dispatchers.IO) {
        runCatching {
            val req = auth(Request.Builder().url(url(path))).get().build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 404) return@runCatching null
                if (!resp.isSuccessful) throw CloudException("Облако ответило ${resp.code}")
                resp.body?.string()
            }
        }
    }

    /** Большой файл - потоком, с долей скачанного: книга качается главами по сотне мегабайт. */
    suspend fun download(path: String, sink: (InputStream, Long) -> Unit): Result<Unit> = if (onDrive) drive!!.download(path, sink) else withContext(Dispatchers.IO) {
        runCatching {
            val req = auth(Request.Builder().url(url(path))).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw CloudException("Облако ответило ${resp.code} на $path")
                val b = resp.body ?: throw CloudException("Пустой ответ облака на $path")
                b.byteStream().use { sink(it, b.contentLength()) }
            }
        }
    }

    suspend fun putText(path: String, text: String): Result<Unit> = if (onDrive) drive!!.putText(path, text) else withContext(Dispatchers.IO) {
        runCatching {
            ensureDir(path.substringBeforeLast('/', ""))
            val req = auth(Request.Builder().url(url(path)))
                .put(text.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw CloudException("Облако не приняло $path: ${resp.code}")
            }
        }
    }

    /** Выгрузить файл потоком - аудио книги в память не помещается. */
    suspend fun upload(path: String, size: Long, open: () -> InputStream?): Result<Unit> = if (onDrive) drive!!.upload(path, size, open) else withContext(Dispatchers.IO) {
        runCatching {
            ensureDir(path.substringBeforeLast('/', ""))
            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = size
                override fun writeTo(sink: BufferedSink) {
                    (open() ?: throw CloudException("Не открылся файл $path")).source().use { sink.writeAll(it) }
                }
            }
            val req = auth(Request.Builder().url(url(path))).put(body).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw CloudException("Облако не приняло $path: ${resp.code}")
            }
        }
    }

    /**
     * Завести папку со всеми родителями. MKCOL на существующую папку отвечает
     * 405 - это не ошибка, папка уже есть.
     */
    private fun ensureDir(path: String) {
        val parts = path.split('/').filter { it.isNotBlank() }
        // Синхронизация пишет раз в две минуты: заводить одни и те же папки
        // каждый раз - три лишних запроса. Помним заведённые до смены настроек.
        val key = settings.now().let { it.cloudUrl + "|" + it.cloudUser + "|" + it.cloudDir } + "|" + parts.joinToString("/")
        if (key in made) return
        for (i in 0..parts.size) {
            val sub = parts.take(i).joinToString("/") + "/"
            val req = auth(Request.Builder().url(url(sub))).method("MKCOL", null).build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 401) throw CloudException("Облако не приняло логин или пароль приложения")
                if (!resp.isSuccessful && resp.code != 405 && resp.code != 409 && resp.code != 301) {
                    throw CloudException("Не завелась папка $sub: ${resp.code}")
                }
            }
        }
        made += key
    }

    private val made = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun parse(input: InputStream): List<Item> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(input, "UTF-8")
        val out = ArrayList<Item>()
        var href = ""
        var dir = false
        var size = 0L
        var modified = 0L
        var text = StringBuilder()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (parser.name) {
                        "response" -> { href = ""; dir = false; size = 0L; modified = 0L }
                        "collection" -> dir = true
                    }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "href" -> href = text.toString().trim()
                    "getcontentlength" -> size = text.toString().trim().toLongOrNull() ?: 0L
                    "getlastmodified" -> modified = runCatching { HTTP_DATE.get()!!.parse(text.toString().trim())?.time }.getOrNull() ?: 0L
                    "response" -> {
                        // href бывает и полным адресом, и путём от корня сервера.
                        val path = URLDecoder.decode(href.substringAfter("://").let { if (href.contains("://")) "/" + it.substringAfter('/') else href }, "UTF-8")
                        val name = path.trimEnd('/').substringAfterLast('/')
                        if (name.isNotBlank()) out += Item(path, name, dir, size, modified)
                    }
                }
            }
        }
        return out
    }

    companion object {
        /** Папки внутри облака: синхронизация и книги. */
        const val SYNC_DIR = PositionSync.DIR
        const val BOOKS_DIR = "Книги"

        private val HTTP_DATE = ThreadLocal.withInitial {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        }
    }
}
