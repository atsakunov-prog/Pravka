package ru.zf.slushalka.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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

/**
 * Цвет переплёта по обложке книги.
 *
 * Владелец: «цвет обложки книги изнутри должен совпадать с обложкой самой
 * книги из fb2». Среднее по картинке для этого не годится - у пёстрой обложки
 * оно всегда бурое; берём самый весомый цвет по кубам 6x6x6, где вес считает и
 * количество точек, и насыщенность: у обложки с белым полем и красной полосой
 * переплёт должен стать красным, а не белым.
 *
 * Найденный цвет потом приводится к картону (см. [bookCloth]): переплёт не
 * бывает ни кислотным, ни чёрным - он ткань или крашеный картон.
 */
fun coverTone(bitmap: Bitmap): Int? {
    val side = 72
    val small = runCatching {
        Bitmap.createScaledBitmap(bitmap, side, side, true)
    }.getOrNull() ?: return null
    val pixels = IntArray(side * side)
    small.getPixels(pixels, 0, side, 0, 0, side, side)
    if (small !== bitmap) small.recycle()

    val bins = 6
    val weight = FloatArray(bins * bins * bins)
    val sumR = FloatArray(bins * bins * bins)
    val sumG = FloatArray(bins * bins * bins)
    val sumB = FloatArray(bins * bins * bins)
    val hsv = FloatArray(3)
    for (p in pixels) {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        android.graphics.Color.RGBToHSV(r, g, b, hsv)
        // Почти белое и почти чёрное в переплёт не годятся: белый картон
        // сливается с бумагой, чёрный - с тенью под книгой.
        if (hsv[2] < 0.12f || (hsv[1] < 0.07f && hsv[2] > 0.90f)) continue
        val i = (r * bins / 256) * bins * bins + (g * bins / 256) * bins + (b * bins / 256)
        // Насыщенный цвет весит больше: на обложке он и есть «цвет книги».
        val w = 0.35f + hsv[1]
        weight[i] += w
        sumR[i] += r * w
        sumG[i] += g * w
        sumB[i] += b * w
    }
    var best = -1
    for (i in weight.indices) if (best < 0 || weight[i] > weight[best]) best = i
    if (best < 0 || weight[best] <= 0f) return null
    val w = weight[best]
    return android.graphics.Color.rgb(
        (sumR[best] / w).toInt().coerceIn(0, 255),
        (sumG[best] / w).toInt().coerceIn(0, 255),
        (sumB[best] / w).toInt().coerceIn(0, 255),
    )
}

/**
 * Края обложки, завёрнутые внутрь переплёта.
 *
 * В настоящем переплёте обложечная бумага загибается через край картона и
 * видна изнутри полосой вокруг форзаца - «загибкой». Владелец: «обложка не
 * должна быть однотонной - брать края от этой обложки и повторять их, как в
 * настоящей книге, когда обложка немножко загибается». Отсюда четыре полосы
 * с краёв картинки: у самого края картона лежит крайняя точка обложки, дальше
 * внутрь - то, что было ближе к её середине.
 *
 * Левая крышка раскрытой книги - лицевая обложка, вывернутая наизнанку: её
 * полосы отражены по горизонтали ([rightFlipped], [topFlipped],
 * [bottomFlipped]). Правая крышка - задняя обложка; своей картинки у неё нет,
 * и она повторяет края лицевой без отражения. Левая полоса не нужна: у
 * корешка загибки нет.
 */
class CoverEdges(
    val top: ImageBitmap,
    val bottom: ImageBitmap,
    val right: ImageBitmap,
    val topFlipped: ImageBitmap,
    val bottomFlipped: ImageBitmap,
    val rightFlipped: ImageBitmap,
    /** Средняя светлота краёв, 0..1: у тёмной обложки загибка тёмная, и блики по ней надо гасить. */
    val luma: Float,
)

/**
 * Полосы шириной в двадцатую часть обложки, уменьшенные до считанных точек:
 * на экране загибка в восемь точек, и полного разрешения ей незачем.
 */
fun coverEdges(bitmap: Bitmap): CoverEdges? = runCatching {
    val w = bitmap.width
    val h = bitmap.height
    if (w < 16 || h < 16) return null
    val band = (minOf(w, h) / 20).coerceAtLeast(3)
    val along = 192
    val across = 12
    fun strip(x: Int, y: Int, sw: Int, sh: Int, dw: Int, dh: Int): Bitmap =
        Bitmap.createScaledBitmap(Bitmap.createBitmap(bitmap, x, y, sw, sh), dw, dh, true)
    fun flipH(b: Bitmap): Bitmap = Bitmap.createBitmap(
        b, 0, 0, b.width, b.height,
        Matrix().apply { preScale(-1f, 1f) }, true,
    )
    val top = strip(0, 0, w, band, along, across)
    val bottom = strip(0, h - band, w, band, along, across)
    val right = strip(w - band, 0, band, h, across, along)
    var sum = 0f
    var n = 0
    for (b in listOf(top, bottom, right)) {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        for (c in px) {
            sum += (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f
            n++
        }
    }
    CoverEdges(
        top = top.asImageBitmap(),
        bottom = bottom.asImageBitmap(),
        right = right.asImageBitmap(),
        topFlipped = flipH(top).asImageBitmap(),
        bottomFlipped = flipH(bottom).asImageBitmap(),
        rightFlipped = flipH(right).asImageBitmap(),
        luma = if (n > 0) sum / n else 0.5f,
    )
}.getOrNull()
