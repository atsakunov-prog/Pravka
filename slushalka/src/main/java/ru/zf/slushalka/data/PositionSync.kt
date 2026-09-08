package ru.zf.slushalka.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.slushalka.library.documentUri

/**
 * Позиции и вопросы рядом с книгами.
 *
 * В корне библиотеки заводится папка `_Слушалка`, и каждое устройство пишет в
 * неё **свой** файл `позиции-<имя>.json`: секунда записи и знак текста, где
 * остановились глаза, по каждой книге. Никаких слияний и конфликтов: у
 * дорожки один хозяин. Если папка библиотеки синхронизируется (Drive,
 * Syncthing, кабель) - начатое на телефоне продолжается на планшете, а на
 * карточке книги видно, докуда дошёл второй слушатель.
 *
 * Рядом лежит `вопросы-<имя>.json` - история вопросов и ответов той же
 * дорожки. Она, наоборот, сливается: два устройства одного человека
 * дописывают друг друга, и разговор с книгой один на оба.
 */
class PositionSync(private val context: Context) {

    data class Remote(val profile: String, val states: Map<String, BookState>, val at: Long)

    fun push(treeUri: Uri, profile: String, states: Map<String, BookState>) {
        if (profile.isBlank()) return
        runCatching {
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val dirId = ensureDir(treeUri, rootId, DIR) ?: return
            val fileId = ensureFile(treeUri, dirId, fileName(PREFIX, profile)) ?: return
            val body = JSONObject().apply {
                put("profile", profile)
                put("at", System.currentTimeMillis())
                put("books", JSONObject().apply {
                    states.forEach { (id, s) ->
                        put(id, JSONObject()
                            .put("file", s.fileIndex).put("pos", s.posMs).put("abs", s.absMs)
                            .put("at", s.updatedAt).put("finished", s.finished)
                            // Место чтения - для книг без записи это единственная
                            // позиция; у аудиокниги оно и так подтягивает запись.
                            .put("read", s.readChar))
                    }
                })
            }.toString()
            context.contentResolver.openOutputStream(documentUri(treeUri, fileId), "wt")?.use {
                it.write(body.toByteArray())
            }
        }
    }

    /** История вопросов этой дорожки - целиком, файл невелик (полсотни на книгу). */
    fun pushAsks(treeUri: Uri, profile: String, asks: Map<String, List<Ask>>) {
        if (profile.isBlank()) return
        runCatching {
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val dirId = ensureDir(treeUri, rootId, DIR) ?: return
            val fileId = ensureFile(treeUri, dirId, fileName(ASKS_PREFIX, profile)) ?: return
            val body = JSONObject().apply {
                put("profile", profile)
                put("at", System.currentTimeMillis())
                put("books", JSONObject().apply {
                    asks.forEach { (id, list) ->
                        put(id, JSONArray().apply {
                            list.forEach {
                                put(JSONObject().put("at", it.at).put("abs", it.absMs)
                                    .put("q", it.question).put("a", it.answer).put("usd", it.costUsd))
                            }
                        })
                    }
                })
            }.toString()
            context.contentResolver.openOutputStream(documentUri(treeUri, fileId), "wt")?.use {
                it.write(body.toByteArray())
            }
        }
    }

    /** Вопросы своей дорожки, записанные другим устройством; null - файла нет. */
    fun pullAsks(treeUri: Uri, profile: String): Map<String, List<Ask>>? = runCatching {
        if (profile.isBlank()) return null
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val dirId = findChild(treeUri, rootId, DIR) ?: return null
        val docId = findChild(treeUri, dirId, fileName(ASKS_PREFIX, profile)) ?: return null
        val text = context.contentResolver.openInputStream(documentUri(treeUri, docId))
            ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
        val books = JSONObject(text).optJSONObject("books") ?: return emptyMap()
        books.keys().asSequence().associateWith { id ->
            val arr = books.getJSONArray(id)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Ask(o.optLong("at"), o.optLong("abs"), o.optString("q"), o.optString("a"), o.optDouble("usd"))
            }
        }
    }.getOrNull()

    /** Всё, что лежит в папке синхронизации, включая чужие дорожки. */
    fun pull(treeUri: Uri): List<Remote> = runCatching {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val dirId = findChild(treeUri, rootId, DIR) ?: return emptyList()
        children(treeUri, dirId)
            .filter { it.second.startsWith(PREFIX) && it.second.endsWith(".json") }
            .mapNotNull { (docId, _) ->
                val text = context.contentResolver.openInputStream(documentUri(treeUri, docId))
                    ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return@mapNotNull null
                val o = JSONObject(text)
                val books = o.optJSONObject("books") ?: JSONObject()
                Remote(
                    profile = o.optString("profile"),
                    at = o.optLong("at"),
                    states = books.keys().asSequence().associateWith { id ->
                        val b = books.getJSONObject(id)
                        BookState(
                            bookId = id,
                            fileIndex = b.optInt("file"),
                            posMs = b.optLong("pos"),
                            absMs = b.optLong("abs"),
                            updatedAt = b.optLong("at"),
                            finished = b.optBoolean("finished"),
                            readChar = b.optInt("read", -1),
                        )
                    }.toMap(),
                )
            }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------ SAF

    private fun children(treeUri: Uri, parentId: String): List<Pair<String, String>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val out = ArrayList<Pair<String, String>>()
        context.contentResolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) out += c.getString(0) to (c.getString(1) ?: "")
        }
        return out
    }

    private fun findChild(treeUri: Uri, parentId: String, name: String): String? =
        children(treeUri, parentId).firstOrNull { it.second == name }?.first

    private fun ensureDir(treeUri: Uri, parentId: String, name: String): String? {
        findChild(treeUri, parentId, name)?.let { return it }
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            documentUri(treeUri, parentId),
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        ) ?: return null
        return DocumentsContract.getDocumentId(created)
    }

    private fun ensureFile(treeUri: Uri, parentId: String, name: String): String? {
        findChild(treeUri, parentId, name)?.let { return it }
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            documentUri(treeUri, parentId),
            "application/json",
            name,
        ) ?: return null
        return DocumentsContract.getDocumentId(created)
    }

    private fun fileName(prefix: String, profile: String): String {
        val safe = profile.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }.trim()
        return "$prefix${safe.ifBlank { "без-имени" }}.json"
    }

    companion object {
        private const val DIR = "_Слушалка"
        private const val PREFIX = "позиции-"
        private const val ASKS_PREFIX = "вопросы-"
    }
}
