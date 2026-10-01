package ru.zf.slushalka.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.zf.slushalka.library.Book
import ru.zf.slushalka.library.documentUri

/**
 * Файлы Слушалки рядом с книгой - разметка, справочник - там, где книга живёт.
 *
 * Своя книга - в её папке на телефоне, как всегда. Книга, чей звук на сервере
 * библиотеки (взята только текстом или открыта, не скачиваясь), - ещё и в её
 * папке на сервере: разметку, посчитанную на одном телефоне, получит любой
 * другой, а у книги без папки на телефоне сервер - единственное место. Сервер
 * в `Книги/` ничего не переименовывает, поэтому путь стабилен; запись идёт
 * через `.partial` и MOVE, чтобы читающий не увидел половину.
 *
 * Чтение у книги со звуком на сервере - сперва оттуда: там свежее, его могли
 * дописать с другого устройства; без сети - своя копия.
 */
class BookDir(
    private val context: Context,
    private val settings: Settings,
    private val cloud: Cloud,
) {

    /** Папка, в которой книга найдена; у книг из старой библиотеки - главная. */
    private fun treeOf(book: Book): Uri? =
        book.treeUri ?: settings.now().libraryUri.takeIf { it.isNotBlank() }?.let(Uri::parse)

    /**
     * Сервер - тоже дом этой книги: её звук там, или на телефоне её нет вовсе.
     * Полную свою копию на сервер не пишем - у неё своя папка, и так было всегда.
     */
    private fun onServer(book: Book): Boolean =
        book.remoteDir.isNotBlank() && (book.streams || !book.onPhone) && settings.now().cloudReady

    suspend fun read(book: Book, name: String): String? = withContext(Dispatchers.IO) {
        if (onServer(book)) {
            cloud.getText("${book.remoteDir}/$name").getOrNull()?.let { return@withContext it }
        }
        if (!book.onPhone) return@withContext null
        val tree = treeOf(book) ?: return@withContext null
        val docId = Saf.findChild(context, tree, book.folderDocId, name) ?: return@withContext null
        Saf.readText(context, tree, docId)
    }

    /** Записать; true - легло хоть куда-то: на телефон или на сервер. */
    suspend fun write(book: Book, name: String, body: String): Boolean = withContext(Dispatchers.IO) {
        var ok = false
        if (book.onPhone) {
            val tree = treeOf(book)
            val docId = tree?.let { Saf.ensureChild(context, it, book.folderDocId, name, "application/json") }
            if (tree != null && docId != null) ok = Saf.writeText(context, tree, docId, body)
        }
        if (onServer(book)) ok = cloud.putTextAtomic("${book.remoteDir}/$name", body).isSuccess || ok
        ok
    }

    suspend fun delete(book: Book, name: String): Boolean = withContext(Dispatchers.IO) {
        var ok = true
        if (book.onPhone) {
            val tree = treeOf(book)
            val docId = tree?.let { Saf.findChild(context, it, book.folderDocId, name) }
            if (tree != null && docId != null) {
                ok = runCatching {
                    DocumentsContract.deleteDocument(context.contentResolver, documentUri(tree, docId))
                }.getOrDefault(false)
            }
        }
        if (onServer(book)) ok = cloud.delete("${book.remoteDir}/$name").isSuccess && ok
        ok
    }
}
