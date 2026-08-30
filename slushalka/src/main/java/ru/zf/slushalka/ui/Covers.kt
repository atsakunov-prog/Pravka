package ru.zf.slushalka.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.zf.slushalka.library.Book
import ru.zf.slushalka.library.documentUri
import ru.zf.slushalka.text.TextRepo

/**
 * Обложка книги - три источника по очереди: файл рядом с аудио, картинка,
 * вынутая из fb2/epub, и, если ничего нет, тег самого первого mp3.
 */
object Covers {

    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(bookId: String): Bitmap? = cache.get(bookId)

    suspend fun load(
        context: Context,
        treeUri: Uri,
        book: Book,
        texts: TextRepo,
    ): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(book.id)?.let { return@withContext it }

        val extracted = texts.coverFile(book.id)
        val bytes: ByteArray? = when {
            book.coverDocId != null -> runCatching {
                context.contentResolver.openInputStream(documentUri(treeUri, book.coverDocId))
                    ?.use { it.readBytes() }
            }.getOrNull()
            extracted.exists() -> runCatching { extracted.readBytes() }.getOrNull()
            else -> embeddedArt(context, treeUri, book)?.also { art ->
                runCatching { extracted.parentFile?.mkdirs(); extracted.writeBytes(art) }
            }
        } ?: (if (extracted.exists()) runCatching { extracted.readBytes() }.getOrNull() else null)

        val bmp = bytes?.let { decode(it) } ?: return@withContext null
        cache.put(book.id, bmp)
        bmp
    }

    private fun embeddedArt(context: Context, treeUri: Uri, book: Book): ByteArray? {
        val first = book.files.firstOrNull() ?: return null
        val r = MediaMetadataRetriever()
        return runCatching {
            r.setDataSource(context, documentUri(treeUri, first.docId))
            r.embeddedPicture
        }.getOrNull().also { runCatching { r.release() } }
    }

    /** Уменьшаем при чтении: обложки бывают по 3000 пикселей, экрану хватит 900. */
    private fun decode(bytes: ByteArray, target: Int = 900): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        var side = maxOf(bounds.outWidth, bounds.outHeight)
        while (side / 2 >= target) {
            sample *= 2
            side /= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
    }
}
