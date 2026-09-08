package ru.zf.slushalka.data

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.slushalka.library.Book

/**
 * Разобранная библиотека на диске: вкладка открывается мгновенно и без сети.
 * Книги из всех папок библиотеки лежат вместе, каждая помнит своё дерево.
 */
class LibraryStore(context: Context) {

    private val file = File(context.filesDir, "library.json")
    private var books: List<Book> = emptyList()
    /** Главная папка на момент записи; книги без дерева (старый файл) - из неё. */
    private var treeUri: String = ""

    init {
        Store.readOrQuarantine(file) { text ->
            val root = JSONObject(text)
            treeUri = root.optString("tree")
            val arr = root.optJSONArray("books") ?: JSONArray()
            books = (0 until arr.length()).map { Book.fromJson(arr.getJSONObject(it)) }
                .map { if (it.tree.isBlank()) it.copy(tree = treeUri) else it }
        }
    }

    /** Книги из этих папок; из папки, которую убрали из настроек, - не показываются. */
    @Synchronized
    fun books(forTrees: List<String>): List<Book> = books.filter { it.tree in forTrees }

    @Synchronized
    fun replace(primaryTree: String, list: List<Book>) {
        treeUri = primaryTree
        books = list
        persist()
    }

    /** Обновляет одну книгу (например, когда домерились длительности). */
    @Synchronized
    fun update(book: Book) {
        books = books.map { if (it.id == book.id) book else it }
        persist()
    }

    private fun persist() {
        val text = JSONObject()
            .put("tree", treeUri)
            .put("books", JSONArray().apply { books.forEach { put(it.toJson()) } })
            .toString()
        Store.post { Store.writeAtomic(file, text) }
    }
}
