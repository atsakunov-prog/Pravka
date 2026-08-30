package ru.zf.slushalka.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import ru.zf.slushalka.library.documentUri

/** Общие мелочи работы с деревом документов: найти, завести, прочитать, записать. */
internal object Saf {

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
