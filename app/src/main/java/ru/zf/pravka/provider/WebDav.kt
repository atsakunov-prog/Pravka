package ru.zf.pravka.provider

import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element

/**
 * Домашний сервер по WebDAV (26.09.2026): `rclone serve webdav` на всегда
 * включённом компе владельца, снаружи — через KeenDNS роутера, HTTPS делает
 * роутер (владелец про Google: «ужасно глючная штука… у меня дома сервер
 * постоянно включён»). Ровно то, что нужно обмену и копиям: список папки,
 * прочитать, записать, удалить.
 *
 * Запись — во временное имя и переносом (MOVE) на место: оборванный PUT у
 * rclone оставляет недописанный файл на месте старого (проверено на сервере
 * владельца), а журнал Денег, прочитанный наполовину, другой телефон принял
 * бы за правду. Перенос в пределах диска — одно переименование.
 *
 * Адрес назначения MOVE — путём, без хоста: роутер пересылает запрос на комп
 * под своим адресом, и rclone на чужой хост в Destination отвечает 502.
 *
 * Ошибка — целиком: код HTTP и что это значит здесь (железное правило 6).
 */
class WebDav(private val http: OkHttpClient) {

    data class Config(val url: String, val user: String, val pass: String)

    /** Что лежит в папке. [etag] у rclone — время и размер; md5 он не считает (книги по сотне МБ). */
    data class Item(val name: String, val size: Long, val etag: String, val folder: Boolean, val modified: Long)

    class WebDavException(message: String, val code: Int = 0) : Exception(message)

    /** Папка по пути от корня сервера; [create] — завести недостающие. Нет папки и не заводим — пусто. */
    suspend fun list(c: Config, path: List<String>, create: Boolean): List<Item> = withContext(Dispatchers.IO) {
        propfind(c, path)?.let { return@withContext it }
        if (!create) return@withContext emptyList()
        mkdirs(c, path)
        propfind(c, path) ?: throw WebDavException("Домашний сервер: папка ${path.joinToString("/")} не завелась")
    }

    suspend fun read(c: Config, path: List<String>, name: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(request(c, url(c, path, name)).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw fail(resp.code, resp.message, resp.body?.string().orEmpty(), "чтение ${path.joinToString("/")}/$name")
            resp.body?.bytes() ?: ByteArray(0)
        }
    }

    suspend fun write(c: Config, path: List<String>, name: String, bytes: ByteArray, mime: String) =
        put(c, path, name, bytes.toRequestBody(mime.toMediaType()))

    /** Большой файл (копия базы — десятки МБ) — потоком с диска. */
    suspend fun upload(c: Config, path: List<String>, name: String, file: File, mime: String) =
        put(c, path, name, file.asRequestBody(mime.toMediaType()))

    suspend fun delete(c: Config, path: List<String>, name: String) = withContext(Dispatchers.IO) {
        http.newCall(request(c, url(c, path, name)).delete().build()).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 404) throw fail(resp.code, resp.message, resp.body?.string().orEmpty(), "удаление $name")
        }
    }

    private suspend fun put(c: Config, path: List<String>, name: String, body: RequestBody) = withContext(Dispatchers.IO) {
        val tmp = Dav.tempName(name)
        http.newCall(request(c, url(c, path, tmp)).put(body).build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                // Папку могли удалить руками на сервере — завести и повторить один раз.
                if (resp.code == 409 || resp.code == 404) return@use false
                throw fail(resp.code, resp.message, resp.body?.string().orEmpty(), "запись $name")
            }
            true
        }.let { ok ->
            if (!ok) {
                mkdirs(c, path)
                http.newCall(request(c, url(c, path, tmp)).put(body).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw fail(resp.code, resp.message, resp.body?.string().orEmpty(), "запись $name")
                }
            }
        }
        val move = request(c, url(c, path, tmp))
            .method("MOVE", null)
            .header("Destination", Dav.encodedPath(c.url, path, name))
            .header("Overwrite", "T")
            .build()
        http.newCall(move).execute().use { resp ->
            if (!resp.isSuccessful) {
                runCatching { http.newCall(request(c, url(c, path, tmp)).delete().build()).execute().close() }
                throw fail(resp.code, resp.message, resp.body?.string().orEmpty(), "перенос $name на место")
            }
        }
    }

    /** null — папки нет (404). */
    private fun propfind(c: Config, path: List<String>): List<Item>? {
        val body = Dav.PROPFIND.toRequestBody("application/xml; charset=utf-8".toMediaType())
        val req = request(c, url(c, path, null)).method("PROPFIND", body).header("Depth", "1").build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            val text = resp.body?.string().orEmpty()
            if (resp.code != 207) {
                // 200 со страницей вместо 207 — по адресу отвечает не WebDAV: роутер
                // показывает свою страницу, потому что веб-приложение в нём не заведено.
                if (resp.isSuccessful) throw WebDavException(
                    "Домашний сервер: по адресу ${c.url} отвечает не WebDAV (HTTP ${resp.code}, " +
                        "${resp.header("Content-Type").orEmpty().substringBefore(';').ifBlank { "без типа" }}) — " +
                        "похоже, это страница роутера: в нём не заведено веб-приложение для компа",
                    resp.code,
                )
                throw fail(resp.code, resp.message, text, "список ${path.joinToString("/").ifEmpty { "корня" }}")
            }
            return Dav.parse(text, Dav.decodedPath(c.url, path))
        }
    }

    /** Завести папки пути по одной: rclone на MKCOL без родителя отвечает 409. */
    private fun mkdirs(c: Config, path: List<String>) {
        for (i in path.indices) {
            val sub = path.subList(0, i + 1)
            http.newCall(request(c, url(c, sub, null)).method("MKCOL", null).build()).execute().use { resp ->
                // 405 — уже есть (так отвечает большинство серверов; rclone — 201).
                if (!resp.isSuccessful && resp.code != 405) throw fail(resp.code, resp.message, resp.body?.string().orEmpty(), "папка ${sub.joinToString("/")}")
            }
        }
    }

    private fun request(c: Config, url: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", Credentials.basic(c.user, c.pass, Charsets.UTF_8))

    private fun url(c: Config, path: List<String>, name: String?): String =
        c.url.trimEnd('/') + Dav.encodedPath("", path, name)

    private fun fail(code: Int, message: String, body: String, what: String) =
        WebDavException("Домашний сервер, $what: HTTP $code${if (message.isNotBlank()) " $message" else ""} — ${Dav.meaning(code, body)}", code)

    /** Чистые куски — под тестом (`WebDavTest`). */
    object Dav {
        const val PROPFIND = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getetag/><d:getlastmodified/></d:prop></d:propfind>"""

        /** Временное имя начинается с точки: ни журнал Денег, ни копия базы его за своё не примут. */
        const val TEMP = ".part-"

        fun tempName(name: String): String =
            TEMP + java.util.UUID.randomUUID().toString().replace("-", "").take(8) + "-" + name

        fun isTemp(name: String) = name.startsWith(TEMP)

        /** Кусок пути: пробел — `%20`, не `+` (URLEncoder писал бы форму, а не путь). */
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** Проценты назад; `+` в пути — буква, а не пробел. */
        fun dec(s: String): String = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

        /** Путь запроса от [base] (с его собственным путём, если сервер не в корне). Папка — со слэшем в конце. */
        fun encodedPath(base: String, path: List<String>, name: String?): String {
            val prefix = if (base.isEmpty()) "" else basePath(base).trimEnd('/')
            val parts = path.filter { it.isNotBlank() }.map(::enc) + listOfNotNull(name?.let(::enc))
            val joined = prefix + "/" + parts.joinToString("/")
            return if (name == null && parts.isNotEmpty()) "$joined/" else joined
        }

        fun decodedPath(base: String, path: List<String>): String =
            dec(basePath(base)).trimEnd('/') + "/" + path.filter { it.isNotBlank() }.joinToString("/")

        /** `https://host:8443/dav/` → `/dav/`; без пути — `/`. */
        fun basePath(url: String): String {
            val rest = url.substringAfter("://", url)
            val slash = rest.indexOf('/')
            return if (slash < 0) "/" else rest.substring(slash).substringBefore('?').ifEmpty { "/" }
        }

        /**
         * Ответ PROPFIND (207 multistatus) — что лежит в папке [self] (путь
         * без процентов). Сама папка в ответе тоже есть — её пропускаем.
         * href бывает и путём, и полным адресом.
         */
        fun parse(xml: String, self: String): List<Item> {
            val f = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // Внешние сущности в ответе сервера не разворачиваем.
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                isExpandEntityReferences = false
            }
            val doc = f.newDocumentBuilder().parse(xml.byteInputStream())
            val me = self.trim('/')
            val out = ArrayList<Item>()
            val responses = doc.getElementsByTagNameNS("DAV:", "response")
            for (i in 0 until responses.length) {
                val r = responses.item(i) as Element
                val href = text(r, "href") ?: continue
                val path = dec(if ("://" in href) basePath(href) else href).trim('/')
                if (path == me) continue
                var size = 0L
                var etag = ""
                var modified = 0L
                var folder = false
                val stats = r.getElementsByTagNameNS("DAV:", "propstat")
                for (j in 0 until stats.length) {
                    val st = stats.item(j) as Element
                    val status = text(st, "status").orEmpty()
                    if (status.isNotEmpty() && " 200" !in status) continue
                    text(st, "getcontentlength")?.trim()?.toLongOrNull()?.let { size = it }
                    text(st, "getetag")?.let { etag = it.trim().removePrefix("W/").trim('"') }
                    text(st, "getlastmodified")?.let { modified = httpDate(it) }
                    val rt = st.getElementsByTagNameNS("DAV:", "resourcetype")
                    if (rt.length > 0 && (rt.item(0) as Element).getElementsByTagNameNS("DAV:", "collection").length > 0) folder = true
                }
                val name = path.substringAfterLast('/')
                if (name.isEmpty()) continue
                out.add(Item(name, size, etag, folder, modified))
            }
            return out
        }

        private fun text(e: Element, tag: String): String? {
            val n = e.getElementsByTagNameNS("DAV:", tag)
            return if (n.length == 0) null else n.item(0).textContent
        }

        private fun httpDate(s: String): Long =
            runCatching { ZonedDateTime.parse(s.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrDefault(0L)

        /** Что значит код здесь — словами; тело ответа, если короткое и текстом, — следом. */
        fun meaning(code: Int, body: String): String {
            val why = when (code) {
                401 -> "логин или пароль не подошли"
                403 -> "сервер не пускает к этому пути"
                404 -> "нет такого пути на сервере"
                405 -> "сервер не умеет это действие по этому адресу"
                409 -> "нет родительской папки"
                413 -> "файл больше, чем пропускает сервер или роутер"
                423 -> "файл заперт другим устройством"
                502, 503, 504 -> "роутер не достучался до компа: комп выключен, rclone не запущен или брандмауэр не пускает"
                507 -> "на диске сервера кончилось место"
                else -> ""
            }
            val tail = body.trim().takeIf { it.isNotEmpty() && it.length <= 200 && !it.startsWith("<") }
            return listOfNotNull(why.ifEmpty { null }, tail).joinToString(" · ").ifEmpty { "сервер не объяснил" }
        }
    }
}
