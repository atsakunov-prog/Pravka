package ru.zf.slushalka.catalog

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import ru.zf.slushalka.data.Settings

/**
 * Сеть каталога: ленты, обложки, файлы книг.
 *
 * Адрес сайта берётся из настроек при каждом запросе, а не запоминается:
 * владелец меняет его на зеркало, и следующий же запрос должен пойти туда.
 * Все ссылки внутри лент относительные (`/opds/new`, `/b/123/fb2`), поэтому
 * каждая разрешается относительно этого адреса.
 *
 * Флибуста живёт за Varnish и время от времени отвечает 503 или молчит
 * полминуты; один повтор с паузой снимает большую часть таких сбоев, а
 * дальше уже честная ошибка с кнопкой «повторить».
 */
class FlibustaClient(private val settings: Settings) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val covers = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun base(): HttpUrl {
        val raw = settings.now().flibustaUrl.trim().ifBlank { Settings.DEFAULT_FLIBUSTA_URL }
        val withScheme = if (raw.contains("://")) raw else "https://$raw"
        return withScheme.trimEnd('/').toHttpUrlOrNull()
            ?: Settings.DEFAULT_FLIBUSTA_URL.toHttpUrlOrNull()!!
    }

    /** Ссылка из ленты - в абсолютный адрес. Абсолютные (на static.…) остаются как есть. */
    fun resolve(href: String): String =
        (base().resolve(href) ?: base()).toString()

    fun rootUrl(): String = resolve("/opds")

    fun searchUrl(query: String, authors: Boolean): String {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return resolve("/opds/search?searchType=${if (authors) "authors" else "books"}&searchTerm=$q")
    }

    suspend fun feed(url: String): OpdsFeed = withContext(Dispatchers.IO) {
        withRetry {
            get(url, accept = "application/atom+xml, application/xml, text/xml").use { r ->
                val body = r.body ?: throw CatalogException("Пустой ответ каталога")
                val type = r.header("Content-Type").orEmpty()
                // Заглушка, капча или страница «слишком много запросов» -
                // это HTML, и разбирать его как ленту бессмысленно.
                if (type.contains("text/html", true)) {
                    throw CatalogException("Вместо каталога пришла страница сайта - адрес ведёт не на OPDS или сайт просит подождать")
                }
                val feed = runCatching { Opds.parse(body.byteStream()) }
                    .getOrElse { throw CatalogException("Ответ не разобрался как лента каталога") }
                feed
            }
        }
    }

    fun cachedCover(href: String?): Bitmap? = href?.let { covers.get(it) }

    suspend fun cover(href: String): Bitmap? = withContext(Dispatchers.IO) {
        covers.get(href)?.let { return@withContext it }
        val bytes = runCatching {
            get(resolve(href), accept = "image/*").use { r -> r.body?.bytes() }
        }.getOrNull() ?: return@withContext null
        val bmp = decode(bytes) ?: return@withContext null
        covers.put(href, bmp)
        bmp
    }

    /**
     * Качает файл во временный, сообщая проценты. Ссылка `/b/123/fb2` отвечает
     * редиректом на static.flibusta.is - OkHttp идёт за ним сам.
     */
    suspend fun download(
        url: String,
        target: File,
        onProgress: (percent: Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        withRetry {
            get(url, accept = "*/*").use { r ->
                val body = r.body ?: throw CatalogException("Пустой ответ")
                if (r.header("Content-Type").orEmpty().contains("text/html", true)) {
                    throw CatalogException("Вместо книги пришла страница сайта - попробуй позже или через другой адрес")
                }
                val total = body.contentLength()
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPercent = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            done += read
                            if (total > 0) {
                                val p = (done * 100 / total).toInt()
                                if (p != lastPercent) { lastPercent = p; onProgress(p) }
                            }
                        }
                    }
                }
            }
            target
        }
    }

    // ------------------------------------------------------------------ низ

    private fun get(url: String, accept: String): Response {
        val request = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", "Slushalka/1.1 (Android; OPDS)")
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw HttpException(code)
        }
        return response
    }

    /** Один повтор после паузы - на 5xx и молчание сервера. Остальное отдаётся сразу. */
    private suspend fun <T> withRetry(block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: Throwable) {
                val transient = (e is HttpException && e.code >= 500) ||
                    e is java.net.SocketTimeoutException ||
                    e is java.io.InterruptedIOException
                if (!transient || attempt >= 1) throw e
                attempt++
                delay(1500)
            }
        }
    }

    private fun decode(bytes: ByteArray, target: Int = 400): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        var side = maxOf(bounds.outWidth, bounds.outHeight)
        while (side / 2 >= target) { sample *= 2; side /= 2 }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
    }

    class HttpException(val code: Int) : Exception("HTTP $code")
    class CatalogException(message: String) : Exception(message)

    companion object {
        /** Человеческое объяснение вместо стека. */
        fun readable(e: Throwable): String = when (e) {
            is CatalogException -> e.message.orEmpty()
            is HttpException -> when (e.code) {
                503, 502, 504 -> "Флибуста прилегла (${e.code}) - попробуй через минуту"
                404 -> "Такой страницы в каталоге нет (404)"
                429 -> "Флибуста просит не спешить (429) - подожди немного"
                else -> "Сервер ответил ошибкой ${e.code}"
            }
            is java.net.UnknownHostException ->
                "Адрес не открывается. Нет сети - или сайт в этой стране недоступен без VPN"
            is java.net.SocketTimeoutException -> "Флибуста не ответила - попробуй ещё раз"
            is javax.net.ssl.SSLException -> "Не удалось установить защищённое соединение"
            is java.io.IOException -> e.message?.takeIf { it.isNotBlank() } ?: "Сеть оборвалась"
            else -> e.message?.takeIf { it.isNotBlank() } ?: "Не вышло"
        }
    }
}
