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
 * Своя книга - в её папке на телефоне, как всегда. Книга, которая есть и в
 * библиотеке на сервере (скачанная оттуда, взятая текстом или открытая, не
 * скачиваясь), - ещё и в её папке там. Так одну книгу не разбирают дважды:
 * справочник, заказанный с телефона, получит Марианна на своём; разметку,
 * посчитанную на Boox, - телефон; а то, что сервер разберёт сам ночью, -
 * все. Сервер в `Книги/` ничего не переименовывает, поэтому путь стабилен;
 * запись - через `.partial` и MOVE, чтобы читающий не увидел половину.
 *
 * Чтение - сперва с сервера: там свежее, его могли дописать другое
 * устройство или сам сервер; без сети - своя копия. Есть ли книга на
 * сервере, решает оглавление ([serverDirOf]): писать в папку, которой там
 * нет, нельзя - завелась бы пустая «книга» из одного json.
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
     * Папка книги на сервере - своя у книги со звуком оттуда, иначе по
     * оглавлению. null - на сервере такой книги нет. Ставит приложение: здесь
     * оглавления не видно.
     */
    @Volatile
    var serverDirOf: (Book) -> String? = { it.remoteDir.ifBlank { null } }

    private fun serverDir(book: Book): String? =
        if (settings.now().cloudReady) serverDirOf(book) else null

    /** Книга есть в библиотеке на сервере: её файлы Слушалки живут и там. */
    fun onServer(book: Book): Boolean = serverDir(book) != null

    suspend fun read(book: Book, name: String): String? = withContext(Dispatchers.IO) {
        serverDir(book)?.let { dir ->
            cloud.getText("$dir/$name").getOrNull()?.let { return@withContext it }
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
        serverDir(book)?.let { dir -> ok = cloud.putTextAtomic("$dir/$name", body).isSuccess || ok }
        ok
    }

    /** Только на сервере: метка «справочник уже заказан» - на телефоне ей делать нечего. */
    suspend fun readServer(book: Book, name: String): String? =
        serverDir(book)?.let { dir -> cloud.getText("$dir/$name").getOrNull() }

    suspend fun writeServer(book: Book, name: String, body: String): Boolean =
        serverDir(book)?.let { dir -> cloud.putTextAtomic("$dir/$name", body).isSuccess } ?: false

    suspend fun deleteServer(book: Book, name: String): Boolean =
        serverDir(book)?.let { dir -> cloud.delete("$dir/$name").isSuccess } ?: true

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
        serverDir(book)?.let { dir -> ok = cloud.delete("$dir/$name").isSuccess && ok }
        ok
    }
}
