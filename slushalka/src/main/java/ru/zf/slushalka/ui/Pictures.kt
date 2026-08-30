package ru.zf.slushalka.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Картинки книги: карты, планы, портреты. Читаются с диска и держатся в памяти. */
object Pictures {

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(file: File): Bitmap? = cache.get(file.absolutePath)

    suspend fun load(file: File, target: Int = 1400): Bitmap? = withContext(Dispatchers.IO) {
        val key = file.absolutePath + "@" + target
        cache.get(key)?.let { return@withContext it }
        if (!file.exists()) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, bounds) }
        var sample = 1
        var side = maxOf(bounds.outWidth, bounds.outHeight)
        while (side / 2 >= target) {
            sample *= 2
            side /= 2
        }
        val bmp = runCatching {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull() ?: return@withContext null
        cache.put(key, bmp)
        bmp
    }
}
