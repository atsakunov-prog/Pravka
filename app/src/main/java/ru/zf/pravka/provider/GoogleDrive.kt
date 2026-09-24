package ru.zf.pravka.provider

import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Google Drive REST v3 — ровно то, что нужно обмену: папка по пути, список,
 * скачать, создать, переписать. Доступ `drive.file`: видны только файлы,
 * созданные самой Правкой (на любом телефоне под тем же аккаунтом), — поэтому
 * папка «Правка» одна на семью, а чужое в Drive обмену не видно вовсе.
 *
 * Ошибка — целиком: код HTTP и что сказал Google (железное правило 6).
 */
class GoogleDrive(private val auth: GoogleAuth, private val http: OkHttpClient) {

    data class Item(val id: String, val name: String, val size: Long, val md5: String, val folder: Boolean)

    class DriveException(message: String) : Exception(message)

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER = "application/vnd.google-apps.folder"
        const val FIELDS = "id,name,size,md5Checksum,mimeType,createdTime"
    }

    private val folders = HashMap<String, String>()

    /** Вошли под другим аккаунтом — папки прежнего здесь не годятся. */
    fun forget() = synchronized(folders) { folders.clear() }

    /**
     * Папка по пути от корня («Правка/Деньги»), заводится при первом обмене.
     * Две одноимённые (два телефона завели её в одну секунду) — берётся самая
     * ранняя: оба телефона выберут одну и ту же.
     */
    suspend fun folder(path: List<String>): String = withContext(Dispatchers.IO) {
        val key = path.joinToString("/")
        folders[key]?.let { return@withContext it }
        var parent = "root"
        for (name in path) {
            val q = "name = '${esc(name)}' and mimeType = '$FOLDER' and '$parent' in parents and trashed = false"
            val found = query(q, orderBy = "createdTime").firstOrNull()
            parent = found?.id ?: create(parent, name)
        }
        folders[key] = parent
        parent
    }

    /** Всё, что лежит в папке (без удалённых в корзину). */
    suspend fun list(folderId: String): List<Item> = withContext(Dispatchers.IO) {
        query("'$folderId' in parents and trashed = false", orderBy = "name")
    }

    suspend fun download(id: String): ByteArray = withContext(Dispatchers.IO) {
        call { token ->
            Request.Builder().url("$API/files/$id?alt=media").header("Authorization", "Bearer $token").get().build()
        }.use { resp ->
            resp.body?.bytes() ?: ByteArray(0)
        }
    }

    /** Новый файл в папке. */
    suspend fun create(folderId: String, name: String, bytes: ByteArray, mime: String): Item = withContext(Dispatchers.IO) {
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(folderId)).put("mimeType", mime)
        call { token ->
            val body = MultipartBody.Builder()
                .setType("multipart/related".toMediaType())
                .addPart(meta.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .addPart(bytes.toRequestBody(mime.toMediaType()))
                .build()
            Request.Builder().url("$UPLOAD/files?uploadType=multipart&fields=$FIELDS")
                .header("Authorization", "Bearer $token").post(body).build()
        }.use { resp -> item(JSONObject(resp.body?.string().orEmpty())) }
    }

    /** Переписать содержимое файла целиком. */
    suspend fun update(id: String, bytes: ByteArray, mime: String): Item = withContext(Dispatchers.IO) {
        call { token ->
            Request.Builder().url("$UPLOAD/files/$id?uploadType=media&fields=$FIELDS")
                .header("Authorization", "Bearer $token")
                .patch(bytes.toRequestBody(mime.toMediaType())).build()
        }.use { resp -> item(JSONObject(resp.body?.string().orEmpty())) }
    }

    private suspend fun create(parent: String, name: String): String {
        val meta = JSONObject().put("name", name).put("mimeType", FOLDER).put("parents", JSONArray().put(parent))
        return call { token ->
            Request.Builder().url("$API/files?fields=id").header("Authorization", "Bearer $token")
                .post(meta.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        }.use { resp -> JSONObject(resp.body?.string().orEmpty()).getString("id") }
    }

    private suspend fun query(q: String, orderBy: String): List<Item> {
        val out = ArrayList<Item>()
        var page = ""
        do {
            val url = "$API/files?q=${enc(q)}&orderBy=${enc(orderBy)}&pageSize=1000&spaces=drive" +
                "&fields=${enc("nextPageToken,files($FIELDS)")}" + if (page.isNotEmpty()) "&pageToken=${enc(page)}" else ""
            val o = call { token -> Request.Builder().url(url).header("Authorization", "Bearer $token").get().build() }
                .use { resp -> JSONObject(resp.body?.string().orEmpty()) }
            val files = o.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) out.add(item(files.getJSONObject(i)))
            page = o.optString("nextPageToken")
        } while (page.isNotEmpty())
        return out
    }

    private fun item(o: JSONObject) = Item(
        id = o.optString("id"),
        name = o.optString("name"),
        size = o.optString("size").toLongOrNull() ?: 0L,
        md5 = o.optString("md5Checksum"),
        folder = o.optString("mimeType") == FOLDER,
    )

    /**
     * Запрос с ключом. 401 — ключ доступа протух раньше срока: один повтор со
     * свежим. Остальные ошибки — словами Google.
     */
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
            throw DriveException("Google Drive: HTTP ${resp.code} — ${reason(text)}")
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
