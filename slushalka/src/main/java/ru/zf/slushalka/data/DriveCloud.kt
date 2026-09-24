package ru.zf.slushalka.data

import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject

/**
 * Облако через семейный Google Drive (25.09.2026) — те же шесть дел, что у
 * WebDAV (`Cloud`): проверить, список, текст, скачать, записать текст,
 * выгрузить файл. Раскладка та же — от папки из настроек (заводская
 * «Слушалка»): `Книги/<папка книги>/…` и `_Слушалка/…` с позициями.
 *
 * В Drive нет путей, есть папки с номерами: путь проходится по именам от
 * корня, номера папок запоминаются. Одноимённых бывает несколько — берётся
 * самая ранняя, её выберут и другие устройства.
 *
 * Читать можно всё (`drive.readonly`): книги, закинутые в Drive с компьютера,
 * видны. Писать — только в своё (`drive.file`): папку «Слушалка» и её
 * подпапки Слушалка заводит сама, в них ложатся и выгрузки с полки, и позиции.
 * Папку, заведённую руками, Google писать не даст — ошибка это и скажет.
 */
class DriveCloud(private val settings: Settings, private val auth: GoogleAuth) {

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER = "application/vnd.google-apps.folder"
        const val FIELDS = "id,name,size,mimeType,modifiedTime,createdTime"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Книга качается файлами по сотне мегабайт: чтение без предела.
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .build()

    private data class Node(val id: String, val name: String, val folder: Boolean, val size: Long, val modified: Long)

    /** Путь папки (от корня Drive, с папкой из настроек) → её номер. */
    private val folders = ConcurrentHashMap<String, String>()

    fun forget() = folders.clear()

    suspend fun check(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            folder("", create = true)
            Unit
        }
    }

    suspend fun list(path: String): Result<List<Cloud.Item>> = withContext(Dispatchers.IO) {
        runCatching {
            val id = folder(path, create = false) ?: return@runCatching emptyList()
            children(id).map { n ->
                Cloud.Item(path.trimEnd('/') + "/" + n.name, n.name, n.folder, n.size, n.modified)
            }
        }
    }

    suspend fun getText(path: String): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            val n = file(path) ?: return@runCatching null
            call { t -> get("$API/files/${n.id}?alt=media", t) }.use { it.body?.string() }
        }
    }

    suspend fun download(path: String, sink: (InputStream, Long) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val n = file(path) ?: throw Cloud.CloudException("В Drive нет файла $path")
            call { t -> get("$API/files/${n.id}?alt=media", t) }.use { resp ->
                val b = resp.body ?: throw Cloud.CloudException("Пустой ответ Drive на $path")
                b.byteStream().use { sink(it, b.contentLength().takeIf { l -> l > 0 } ?: n.size) }
            }
        }
    }

    suspend fun putText(path: String, text: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = text.toByteArray(Charsets.UTF_8)
            upload(path, bytes.size.toLong(), "application/json") { bytes.inputStream() }
        }
    }

    /** Выгрузить файл потоком: аудио книги в память не помещается. */
    suspend fun upload(path: String, size: Long, open: () -> InputStream?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { upload(path, size, "application/octet-stream", open) }
    }

    private suspend fun upload(path: String, size: Long, mime: String, open: () -> InputStream?) {
        val parentPath = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val parent = folder(parentPath, create = true) ?: throw Cloud.CloudException("Не завелась папка $parentPath")
        val existing = children(parent).firstOrNull { !it.folder && it.name == name }
        // Сессия загрузки: тело идёт потоком, а не массивом.
        val meta = if (existing == null) JSONObject().put("name", name).put("parents", JSONArray().put(parent)) else JSONObject()
        val start = if (existing == null) "$UPLOAD/files?uploadType=resumable&fields=id"
        else "$UPLOAD/files/${existing.id}?uploadType=resumable&fields=id"
        val session = call { t ->
            Request.Builder().url(start)
                .header("Authorization", "Bearer $t")
                .header("X-Upload-Content-Type", mime)
                .header("X-Upload-Content-Length", size.toString())
                .method(if (existing == null) "POST" else "PATCH", meta.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        }.use { it.header("Location") } ?: throw Cloud.CloudException("Drive не дал адрес загрузки для $path")
        val body = object : RequestBody() {
            override fun contentType() = mime.toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                (open() ?: throw Cloud.CloudException("Не открылся файл $path")).source().use { sink.writeAll(it) }
            }
        }
        http.newCall(Request.Builder().url(session).put(body).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw Cloud.CloudException("Drive не принял $path: HTTP ${resp.code} — ${reason(resp.body?.string().orEmpty())}")
        }
    }

    /**
     * Номер папки по пути от папки из настроек. [create] — заводить
     * недостающие (для записи); без него нет папки — null.
     */
    private suspend fun folder(path: String, create: Boolean): String? {
        val parts = (settings.now().cloudDir.split('/') + path.split('/')).filter { it.isNotBlank() }
        var parent = "root"
        var key = ""
        for (part in parts) {
            key += "/$part"
            val known = folders[key]
            if (known != null) { parent = known; continue }
            val found = query("name = '${esc(part)}' and mimeType = '$FOLDER' and '$parent' in parents and trashed = false", "createdTime")
                .firstOrNull()
            val id = found?.id ?: if (create) mkdir(parent, part) else return null
            folders[key] = id
            parent = id
        }
        return parent
    }

    private suspend fun file(path: String): Node? {
        val parent = folder(path.substringBeforeLast('/', ""), create = false) ?: return null
        val name = path.substringAfterLast('/')
        // Два одноимённых файла — берётся свежий: так пишет и сама Слушалка.
        return query("name = '${esc(name)}' and '$parent' in parents and trashed = false and mimeType != '$FOLDER'", "modifiedTime desc")
            .firstOrNull()
    }

    private suspend fun children(id: String): List<Node> = query("'$id' in parents and trashed = false", "name")

    private suspend fun mkdir(parent: String, name: String): String {
        val meta = JSONObject().put("name", name).put("mimeType", FOLDER).put("parents", JSONArray().put(parent))
        return call { t ->
            Request.Builder().url("$API/files?fields=id").header("Authorization", "Bearer $t")
                .post(meta.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        }.use { JSONObject(it.body?.string().orEmpty()).getString("id") }
    }

    private suspend fun query(q: String, orderBy: String): List<Node> {
        val out = ArrayList<Node>()
        var page = ""
        do {
            val url = "$API/files?q=${enc(q)}&orderBy=${enc(orderBy)}&pageSize=1000&spaces=drive" +
                "&fields=${enc("nextPageToken,files($FIELDS)")}" + if (page.isNotEmpty()) "&pageToken=${enc(page)}" else ""
            val o = call { t -> get(url, t) }.use { JSONObject(it.body?.string().orEmpty()) }
            val files = o.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                out += Node(
                    id = f.optString("id"),
                    name = f.optString("name"),
                    folder = f.optString("mimeType") == FOLDER,
                    size = f.optString("size").toLongOrNull() ?: 0L,
                    modified = runCatching { java.time.Instant.parse(f.optString("modifiedTime")).toEpochMilli() }.getOrDefault(0L),
                )
            }
            page = o.optString("nextPageToken")
        } while (page.isNotEmpty())
        return out
    }

    private fun get(url: String, token: String) =
        Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()

    /** Запрос с ключом; 401 — один повтор со свежим ключом; остальное — словами Google. */
    private suspend fun call(build: (String) -> Request): okhttp3.Response {
        var retried = false
        while (true) {
            val token = auth.accessToken()
            val resp = http.newCall(build(token)).execute()
            if (resp.isSuccessful) return resp
            val text = resp.body?.string().orEmpty()
            resp.close()
            if (resp.code == 401 && !retried) {
                retried = true
                auth.invalidate()
                continue
            }
            val why = reason(text)
            val hint = if (resp.code == 403 && why.contains("insufficient", ignoreCase = true))
                " (писать Слушалка может только в папки, которые завела сама: папку «${settings.now().cloudDir}», заведённую руками, переименуй — Слушалка заведёт свою)"
            else ""
            throw Cloud.CloudException("Google Drive: HTTP ${resp.code} — $why$hint")
        }
    }

    private fun reason(text: String): String = runCatching {
        val e = JSONObject(text).getJSONObject("error")
        val first = e.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
        listOf(e.optString("message"), first).filter { it.isNotBlank() }.joinToString(" · ")
    }.getOrNull()?.ifBlank { null } ?: text.take(300)

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
