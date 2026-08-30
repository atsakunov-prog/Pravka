package ru.zf.slushalka.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import ru.zf.slushalka.library.documentUri

/**
 * Позиции рядом с книгами.
 *
 * В корне библиотеки заводится папка `_Слушалка`, и каждое устройство пишет в
 * неё **свой** файл `позиции-<имя>.json`. Никаких слияний и конфликтов: у
 * дорожки один хозяин. Если папка библиотеки синхронизируется (Drive,
 * Syncthing, кабель) - начатое на телефоне продолжается на планшете, а на
 * карточке книги видно, докуда дошёл второй слушатель.
 */
class PositionSync(private val context: Context) {

    data class Remote(val profile: String, val states: Map<String, BookState>, val at: Long)

    fun push(treeUri: Uri, profile: String, states: Map<String, BookState>) {
        if (profile.isBlank()) return
        runCatching {
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val dirId = ensureDir(treeUri, rootId, DIR) ?: return
            val fileId = ensureFile(treeUri, dirId, fileName(profile)) ?: return
            val body = JSONObject().apply {
                put("profile", profile)
                put("at", System.currentTimeMillis())
                put("books", JSONObject().apply {
                    states.forEach { (id, s) ->
                        put(id, JSONObject()
                            .put("file", s.fileIndex).put("pos", s.posMs).put("abs", s.absMs)
                            .put("at", s.updatedAt).put("finished", s.finished))
                    }
                })
            }.toString()
            context.contentResolver.openOutputStream(documentUri(treeUri, fileId), "wt")?.use {
                it.write(body.toByteArray())
            }
        }
    }

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

    private fun fileName(profile: String): String {
        val safe = profile.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }.trim()
        return "$PREFIX${safe.ifBlank { "без-имени" }}.json"
    }

    companion object {
        private const val DIR = "_Слушалка"
        private const val PREFIX = "позиции-"
    }
}
