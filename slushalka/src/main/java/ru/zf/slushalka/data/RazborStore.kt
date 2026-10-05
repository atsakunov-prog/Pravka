package ru.zf.slushalka.data

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Копии разборов книг на телефоне - файлом на книгу в `razbor/`, как он
 * пришёл с сервера. Подлинник живёт в папке книги на сервере; копия нужна,
 * чтобы разбор открывался и без сети - в метро, на даче, в самолёте.
 */
class RazborStore(context: Context) {

    private val dir = File(context.filesDir, "razbor").apply { mkdirs() }

    fun load(bookId: String): String? =
        runCatching { file(bookId).takeIf { it.exists() }?.readText() }.getOrNull()

    fun has(bookId: String): Boolean = file(bookId).exists()

    fun save(bookId: String, raw: String) {
        Store.post { Store.writeAtomic(file(bookId), raw) }
    }

    /** Копия разбора - под ключ сервера, если там своей нет. */
    fun rekey(old: String, new: String): Boolean {
        val from = file(old)
        if (old == new || !from.exists()) return false
        val to = file(new)
        if (!to.exists()) runCatching { from.copyTo(to, overwrite = false) }
        from.delete()
        return true
    }

    private fun file(bookId: String): File {
        val md = MessageDigest.getInstance("SHA-1").digest(bookId.toByteArray())
        return File(dir, md.joinToString("") { "%02x".format(it) }.take(16) + ".json")
    }
}
