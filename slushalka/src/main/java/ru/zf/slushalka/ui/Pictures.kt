package ru.zf.slushalka.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Картинки книги: карты, планы, портреты. Читаются с диска и держатся в памяти. */
object Pictures {

    const val DEFAULT_TARGET = 1400

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(file: File, target: Int = DEFAULT_TARGET): Bitmap? = cache.get(keyOf(file, target))

    private fun keyOf(file: File, target: Int) = file.absolutePath + "@" + target

    suspend fun load(file: File, target: Int = DEFAULT_TARGET): Bitmap? = withContext(Dispatchers.IO) {
        val key = keyOf(file, target)
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

/**
 * Картинка для экрана.
 *
 * Важная мелочь: при смене файла значение сбрасывается и грузится заново.
 * Прежний вариант грузил «только если пусто» - и при перемотке на экране
 * оставалась предыдущая картинка, хотя приложение уже знало про новую.
 */
@androidx.compose.runtime.Composable
fun rememberPicture(file: java.io.File, target: Int = Pictures.DEFAULT_TARGET): Bitmap? {
    val state = androidx.compose.runtime.produceState<Bitmap?>(
        Pictures.cached(file, target), file.path, target,
    ) {
        value = Pictures.cached(file, target) ?: Pictures.load(file, target)
    }
    return state.value
}
