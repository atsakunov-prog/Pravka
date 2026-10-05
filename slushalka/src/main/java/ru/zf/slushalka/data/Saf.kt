package ru.zf.slushalka.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import ru.zf.slushalka.library.documentUri

/** Общие мелочи работы с деревом документов: найти, завести, прочитать, записать. */
internal object Saf {

    /**
     * Папка Downloads на телефоне - как стартовая точка системного пикера.
     * Библиотека договорена жить в `Downloads/Books`: туда же ложатся книги из
     * каталога, и всё видно в одном месте, а не разбросано по приложениям.
     */
    fun downloadsUri(): Uri =
        DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")

    /** «Download/Books» вместо content://…%3ADownload%2FBooks - чтобы в настройках было видно, где книги. */
    fun humanPath(treeUri: String): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)) }.getOrNull()
            ?: return treeUri
        val (volume, path) = id.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val where = if (volume == "primary") "Телефон" else "Карта"
        return if (path.isBlank()) where else "$where/$path"
    }

    fun children(context: Context, treeUri: Uri, parentId: String): List<Pair<String, String>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val out = ArrayList<Pair<String, String>>()
        runCatching {
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
        }
        return out
    }

    /** Файл в папке книги: путь от неё через «/», документ, размер. */
    data class LocalFile(val rel: String, val docId: String, val size: Long)

    /**
     * Все файлы папки книги. Две ступени вложенности хватает дискам; папки
     * «_…» (`_Слушалка` внутри папки книги) - мимо: книгой они не являются.
     */
    fun walk(context: Context, treeUri: Uri, dirId: String, prefix: String = "", depth: Int = 0): List<LocalFile> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
        val rows = ArrayList<Array<Any>>()
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    rows += arrayOf(
                        c.getString(0),
                        c.getString(1) ?: "",
                        c.getString(2) ?: "",
                        if (c.isNull(3)) 0L else c.getLong(3),
                    )
                }
            }
        }
        val out = ArrayList<LocalFile>()
        for (r in rows) {
            val docId = r[0] as String
            val name = r[1] as String
            if (r[2] == DocumentsContract.Document.MIME_TYPE_DIR) {
                if (depth < 2 && !name.startsWith("_")) out += walk(context, treeUri, docId, "$prefix$name/", depth + 1)
            } else out += LocalFile("$prefix$name", docId, r[3] as Long)
        }
        return out
    }

    /**
     * Документ папки по пути от корня дерева: «Books/Акунин/Том 4» - сперва
     * имя корня, потом папки. null - такой папки нет.
     */
    fun dirByPath(context: Context, treeUri: Uri, path: String): String? {
        var id = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        for (part in path.split('/').drop(1).filter { it.isNotBlank() }) {
            id = findChild(context, treeUri, id, part) ?: return null
        }
        return id
    }

    fun findChild(context: Context, treeUri: Uri, parentId: String, name: String): String? =
        children(context, treeUri, parentId).firstOrNull { it.second == name }?.first

    fun ensureChild(
        context: Context,
        treeUri: Uri,
        parentId: String,
        name: String,
        mime: String,
    ): String? {
        findChild(context, treeUri, parentId, name)?.let { return it }
        val created = runCatching {
            DocumentsContract.createDocument(
                context.contentResolver, documentUri(treeUri, parentId), mime, name,
            )
        }.getOrNull() ?: return null
        return DocumentsContract.getDocumentId(created)
    }

    fun readText(context: Context, treeUri: Uri, docId: String): String? = runCatching {
        context.contentResolver.openInputStream(documentUri(treeUri, docId))
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    /** «wt» - усечение: иначе остаток прежнего, более длинного файла остался бы хвостом. */
    fun writeText(context: Context, treeUri: Uri, docId: String, text: String): Boolean = runCatching {
        context.contentResolver.openOutputStream(documentUri(treeUri, docId), "wt")?.use {
            it.write(text.toByteArray())
        } != null
    }.getOrDefault(false)
}
