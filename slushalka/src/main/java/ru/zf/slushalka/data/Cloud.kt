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
 * Облако по WebDAV: Яндекс.Диск (логин и «пароль приложения»), Nextcloud,
 * Box, Koofr - кто угодно, кто говорит WebDAV. Протокол простой - PROPFIND,
 * GET, PUT, MKCOL поверх HTTP - и не требует ни регистрации приложения, ни
 * своего сервера: это и есть «базовая синхронизация» без сторонней программы.
 *
 * Раскладка в облаке, от папки из настроек (заводская - «Слушалка»):
 * `_Слушалка/` - те же файлы позиций, вопросов и пометок, что в папке
 * библиотеки ([PositionSync]); `Книги/<папка книги>/` - книги целиком, как
 * они лежат на полке.
 *
 * Домашняя библиотека устроена так же, только от корня сервера (папка «/»), и
 * в корне у неё ещё `index.json` - оглавление всех книг ([ServerLibrary]).
 */
class Cloud(private val settings: Settings) {

    data class Item(val path: String, val name: String, val dir: Boolean, val size: Long, val modified: Long)

    class CloudException(message: String) : Exception(message)

    /** Чем кончился MOVE: форма, что прошла (null - никакая), и последний код. */
    data class MoveOutcome(val form: String?, val code: Int, val tries: Int)

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
        val tail = parts.joinToString("/") { enc(it) }
        // Корень сервера - один слеш, а не «//»: rclone принимает и так, но
        // другой WebDAV на двойном слеше спотыкается.
        val dir = tail.isNotEmpty() && (path.endsWith("/") || path.isEmpty())
        return p.cloudUrl.trimEnd('/') + "/" + tail + if (dir) "/" else ""
    }

    /**
     * Адрес файла целиком - для тех, кто ходит по HTTP сам: плеер, разбор
     * длительности, распознавание куска. Один и тот же путь всегда даёт одну
     * и ту же строку - по ней кэш записи узнаёт уже скачанное.
     */
    fun urlOf(path: String): String = url(path)

    /** Заголовок входа для того же круга: у плеера свой HTTP, не OkHttp. */
    fun authHeader(): String {
        val p = settings.now()
        return Credentials.basic(p.cloudUser, p.cloudPass, Charsets.UTF_8)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /**
     * Запрос с ошибкой по-русски. Сетевые исключения Java («Unable to resolve
     * host…») человеку ничего не говорят, а за ними почти всегда одно из трёх:
     * адреса нет, сервер молчит, сертификат не тот. Отмена корутины - не
     * ошибка облака, она летит дальше.
     */
    private inline fun <T> guard(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(if (e is CloudException) e else CloudException(human(e)))
    }

    private fun auth(b: Request.Builder): Request.Builder = b.header("Authorization", authHeader())

    /** Проверка настроек: папка есть или заводится, логин и пароль приняты. */
    suspend fun check(): Result<Unit> = withContext(Dispatchers.IO) {
        guard {
            if (!ready) throw CloudException("Облако не настроено: адрес, логин и пароль приложения")
            ensureDir("")
            list("").getOrThrow()
            Unit
        }
    }

    /** Что лежит в папке (без неё самой). */
    suspend fun list(path: String): Result<List<Item>> = withContext(Dispatchers.IO) {
        guard {
            val body = """<?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
                .toRequestBody("application/xml; charset=utf-8".toMediaType())
            val req = auth(Request.Builder().url(url(path.trimEnd('/') + "/")))
                .method("PROPFIND", body)
                .header("Depth", "1")
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 404) return@guard emptyList<Item>()
                if (resp.code == 401) throw CloudException("Облако не приняло логин или пароль приложения")
                if (!resp.isSuccessful) throw CloudException("Облако ответило ${resp.code}")
                val self = url(path.trimEnd('/') + "/").substringAfter("://").substringAfter('/')
                parse(resp.body?.byteStream() ?: return@guard emptyList<Item>())
                    .filter { it.path.trim('/') != URLDecoder.decode(self, "UTF-8").trim('/') }
            }
        }
    }

    /** Что лежит по пути: размер и время. null - ничего нет. */
    suspend fun stat(path: String): Result<Item?> = withContext(Dispatchers.IO) {
        guard {
            val body = """<?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
                .toRequestBody("application/xml; charset=utf-8".toMediaType())
            val req = auth(Request.Builder().url(url(path)))
                .method("PROPFIND", body)
                .header("Depth", "0")
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 404) return@guard null
                if (resp.code == 401) throw CloudException("Облако не приняло логин или пароль приложения")
                if (!resp.isSuccessful) throw CloudException("Облако ответило ${resp.code}")
                parse(resp.body?.byteStream() ?: return@guard null).firstOrNull()
            }
        }
    }

    /**
     * Какой формой `Destination` MOVE прошёл на этом сервере: адрес облака и
     * «полным адресом». Роутер перед домашним сервером переписывает хост и
     * на полный адрес отвечает 502 - тогда годится путь от корня; другой
     * WebDAV, наоборот, хочет адрес целиком. Сработавшая форма идёт первой.
     */
    @Volatile
    private var moveForm: Pair<String, Boolean>? = null

    /**
     * Переложить внутри облака. Поверх лежащего - да: так докладывается
     * файл, залитый под временным именем.
     *
     * До 05.10 `Destination` уходил полным адресом, и через роутер каждый MOVE
     * получал 502: все записи телефона застревали в `.partial` - места,
     * справочники, заказы разбора. Теперь первым - путь от корня сервера, на
     * 502 - вторая форма ([moveVia]).
     */
    suspend fun move(from: String, to: String): Result<Unit> = withContext(Dispatchers.IO) {
        guard {
            val base = settings.now().cloudUrl
            val target = url(to)
            val full = moveForm?.takeIf { it.first == base }?.second ?: false
            val outcome = moveVia(destinations(target, preferFull = full)) { destination ->
                val req = auth(Request.Builder().url(url(from)))
                    .method("MOVE", null)
                    .header("Destination", destination)
                    .header("Overwrite", "T")
                    .build()
                try {
                    http.newCall(req).execute().use { it.code }
                } catch (e: java.io.IOException) {
                    // Роутер рвёт соединение вместо ответа - тоже повод попробовать другую форму.
                    NO_ANSWER
                }
            }
            val form = outcome.form ?: throw CloudException(
                "Облако не переложило $from: " + if (outcome.code == NO_ANSWER) "нет ответа" else "${outcome.code}",
            )
            moveForm = base to (form == target)
        }
    }

    /**
     * Записать так, чтобы на месте файл появился целиком: сперва `имя.partial`,
     * потом MOVE. PUT у rclone не атомарен - кто читает в эту секунду, увидел
     * бы половину, а сервер библиотеки `*.partial` не берёт в работу.
     *
     * MOVE не прошёл никакой формой - маленький файл ложится прямо под своим
     * именем, а свой `.partial` удаляется: место, застрявшее во временном
     * имени, не видно никому, а половину JSON читающий просто не разберёт и
     * возьмёт в следующий раз.
     */
    suspend fun putTextAtomic(path: String, text: String): Result<Unit> {
        val temp = "$path$PARTIAL"
        putText(temp, text).onFailure { return Result.failure(it) }
        if (move(temp, path).isSuccess) return Result.success(Unit)
        return putText(path, text).onSuccess { delete(temp) }
    }

    /**
     * Большой файл тем же порядком: залить под `.partial`, сверить размер на
     * той стороне (оборванный PUT оставляет огрызок, а не ошибку) и только
     * тогда переложить на место.
     *
     * Запасного PUT в конечное имя здесь нет: оборванный mp3 по виду не
     * отличить от целого, и сервер разложил бы огрызок. Не переложилось -
     * выгрузка не удалась, свой `.partial` убирается.
     */
    suspend fun uploadAtomic(path: String, size: Long, open: () -> InputStream?): Result<Unit> {
        val temp = "$path$PARTIAL"
        return upload(temp, size, open).mapCatching {
            val got = stat(temp).getOrThrow()?.size
            if (got != null && got != size) {
                throw CloudException("Облако приняло $path не целиком: $got из $size байт")
            }
            move(temp, path).getOrElse { e ->
                delete(temp)
                throw e
            }
        }
    }

    /** Удалить файл. Его и так нет (404) - тоже удалён. */
    suspend fun delete(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        guard {
            val req = auth(Request.Builder().url(url(path))).delete().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 404) throw CloudException("Облако не удалило $path: ${resp.code}")
            }
        }
    }

    /** Файл целиком - для маленьких файлов синхронизации. null - файла нет. */
    suspend fun getText(path: String): Result<String?> = withContext(Dispatchers.IO) {
        guard {
            val req = auth(Request.Builder().url(url(path))).get().build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 404) return@guard null
                if (resp.code == 401) throw CloudException("Облако не приняло логин или пароль")
                if (!resp.isSuccessful) throw CloudException("Облако ответило ${resp.code}")
                resp.body?.string()
            }
        }
    }

    /** Большой файл - потоком, с долей скачанного: книга качается главами по сотне мегабайт. */
    suspend fun download(path: String, sink: (InputStream, Long) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        guard {
            val req = auth(Request.Builder().url(url(path))).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw CloudException("Облако ответило ${resp.code} на $path")
                val b = resp.body ?: throw CloudException("Пустой ответ облака на $path")
                b.byteStream().use { sink(it, b.contentLength()) }
            }
        }
    }

    suspend fun putText(path: String, text: String): Result<Unit> = withContext(Dispatchers.IO) {
        guard {
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
    suspend fun upload(path: String, size: Long, open: () -> InputStream?): Result<Unit> = withContext(Dispatchers.IO) {
        guard {
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
     * Завести папку со всеми родителями, от папки облака из настроек и ниже.
     * MKCOL на существующую папку отвечает 405 - это не ошибка, папка уже есть.
     * Корень сервера не заводится: он есть всегда.
     */
    private fun ensureDir(path: String) {
        val p = settings.now()
        val parts = (p.cloudDir.split('/') + path.split('/')).filter { it.isNotBlank() }
        // Синхронизация пишет раз в две минуты: заводить одни и те же папки
        // каждый раз - три лишних запроса. Помним заведённые до смены настроек.
        val key = p.cloudUrl + "|" + p.cloudUser + "|" + parts.joinToString("/")
        if (key in made) return
        for (i in 1..parts.size) {
            val sub = p.cloudUrl.trimEnd('/') + "/" + parts.take(i).joinToString("/") { enc(it) } + "/"
            val req = auth(Request.Builder().url(sub)).method("MKCOL", null).build()
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

        /** Хвост временного имени: под ним файл льётся, без него лежит готовым. */
        const val PARTIAL = ".partial"

        /** Код «ответа не было»: соединение оборвалось до ответа. */
        const val NO_ANSWER = -1

        /**
         * Путь от корня сервера - адрес без схемы и хоста, закодированный как
         * был: `https://host/dav/Книги/x` → `/dav/Книги/x`.
         */
        fun pathOf(url: String): String {
            val rest = url.substringAfter("://", url)
            val slash = rest.indexOf('/')
            return if (slash < 0) "/" else rest.substring(slash)
        }

        /** Формы `Destination` по порядку: путём от корня (заводская) и полным адресом. */
        fun destinations(url: String, preferFull: Boolean): List<String> =
            if (preferFull) listOf(url, pathOf(url)) else listOf(pathOf(url), url)

        /**
         * MOVE с повтором: [send] шлёт запрос с данным `Destination` и отдаёт
         * код ответа. Следующая форма - на 502 и прочие 5xx, на 400 (сервер не
         * понял форму) и на обрыв; 401, 404, 409 - не про форму, второй раз
         * то же самое.
         */
        fun moveVia(forms: List<String>, send: (String) -> Int): MoveOutcome {
            var code = NO_ANSWER
            for ((i, form) in forms.withIndex()) {
                code = send(form)
                if (code in 200..299) return MoveOutcome(form, code, i + 1)
                val aboutForm = code == NO_ANSWER || code == 400 || code in 500..599
                if (!aboutForm) return MoveOutcome(null, code, i + 1)
            }
            return MoveOutcome(null, code, forms.size)
        }

        /** Сетевая ошибка словами: что случилось и куда смотреть. */
        fun human(e: Throwable): String = when (e) {
            is java.net.UnknownHostException ->
                "Адрес «${hostOf(e.message)}» не находится в сети: такого сервера нет или нет интернета. " +
                    "Проверь адрес в настройках облака"
            is java.net.SocketTimeoutException -> "Сервер не ответил вовремя: он выключен или сеть еле живая"
            is java.net.ConnectException -> "Сервер не принимает соединение: выключен или порт закрыт"
            is javax.net.ssl.SSLException -> "Сертификат сервера не принят: ${e.message ?: "защищённое соединение не сложилось"}"
            is java.io.IOException -> "Связь с сервером оборвалась: ${e.message ?: e.javaClass.simpleName}"
            else -> e.message ?: e.javaClass.simpleName
        }

        /** Имя сервера из «Unable to resolve host "x": …» - сообщение у Android одно и то же. */
        private fun hostOf(message: String?): String =
            message?.let { Regex("\"([^\"]+)\"").find(it)?.groupValues?.get(1) } ?: "сервера"

        private val HTTP_DATE = ThreadLocal.withInitial {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        }
    }
}
