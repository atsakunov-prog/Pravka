package ru.zf.slushalka.text

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.zf.slushalka.library.Book
import ru.zf.slushalka.library.documentUri

/**
 * Текст книги: разбирается один раз и ложится в кэш приложения. Разбор
 * восьмисотстраничного романа стоит секунду-другую, и платить её на каждый
 * вопрос не за что.
 */
class TextRepo(private val context: Context) {

    private val dir get() = File(context.filesDir, "text").apply { mkdirs() }
    private val coverDir get() = File(context.filesDir, "covers").apply { mkdirs() }
    private val memory = HashMap<String, BookText>()

    fun coverFile(bookId: String): File = File(coverDir, key(bookId) + ".img")

    fun cached(bookId: String): BookText? = memory[bookId]

    /** Разбирает текст (или достаёт из кэша). null - текста рядом с аудио нет. */
    suspend fun textFor(treeUri: Uri, book: Book): BookText? = withContext(Dispatchers.IO) {
        memory[book.id]?.let { return@withContext it }
        val k = key(book.id)
        val txt = File(dir, "$k.txt")
        val meta = File(dir, "$k.json")
        if (txt.exists() && meta.exists()) {
            runCatching {
                BookText.fromMeta(txt.readText(), JSONObject(meta.readText()))
            }.getOrNull()?.let {
                memory[book.id] = it
                return@withContext it
            }
        }
        val docId = book.textDocId ?: return@withContext null
        val parsed = runCatching { parse(treeUri, docId, book.textName.orEmpty()) }.getOrNull()
            ?: return@withContext null
        if (parsed.text.length < 200) return@withContext null
        runCatching {
            txt.writeText(parsed.text.plain)
            meta.writeText(parsed.text.metaJson().toString())
            parsed.cover?.let { bytes -> if (bytes.size > 1000) coverFile(book.id).writeBytes(bytes) }
        }
        memory[book.id] = parsed.text
        parsed.text
    }

    private fun parse(treeUri: Uri, docId: String, name: String): ParsedBook {
        val uri = documentUri(treeUri, docId)
        val lower = name.lowercase()
        return when {
            lower.endsWith(".fb2") -> context.contentResolver.openInputStream(uri)!!
                .use { Fb2Parser.parse(it) }
            lower.endsWith(".epub") -> EpubParser.parse(copyToCache(uri, "book.epub"))
            else -> {
                // .fb2.zip и просто .zip: внутри почти всегда один fb2.
                val f = copyToCache(uri, "book.zip")
                val fb2 = ZipFile(f).use { zip ->
                    val e = zip.entries().asSequence()
                        .firstOrNull { it.name.endsWith(".fb2", true) }
                    if (e == null) null else zip.getInputStream(e).use { Fb2Parser.parse(it) }
                }
                fb2 ?: EpubParser.parse(f)
            }
        }
    }

    private fun copyToCache(uri: Uri, name: String): File {
        val out = File(context.cacheDir, name)
        context.contentResolver.openInputStream(uri)!!.use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    private fun key(bookId: String): String {
        val md = MessageDigest.getInstance("SHA-1").digest(bookId.toByteArray())
        return md.joinToString("") { "%02x".format(it) }.take(16)
    }
}
