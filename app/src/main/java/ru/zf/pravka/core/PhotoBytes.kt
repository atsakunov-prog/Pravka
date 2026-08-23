package ru.zf.pravka.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import ru.zf.pravka.provider.ClaudeProvider

// Снимок тарелки на пути к модели: уменьшить, повернуть как снято, отдать
// base64.
//
// Уменьшать обязательно. Кадр с Pixel - это 4000×3000 и мегабайты, а
// Anthropic всё равно сжимает картинку до 1568 px по длинной стороне: послать
// оригинал значит заплатить трафиком на LTE за то, что на другом конце
// выбросят. Заодно и файл в filesDir остаётся человеческого размера - дневник
// еды живёт год, и год оригиналов с камеры заполнил бы память телефона.
//
// Поворот тоже обязателен: камера пишет кадр как есть и кладёт ориентацию в
// EXIF, а BitmapFactory про EXIF не знает. Модель на лежащей на боку тарелке
// путает и блюда, и порции.
internal object PhotoBytes {

    private const val MAX_SIDE = FoodEngine.PHOTO_MAX_SIDE
    private const val QUALITY = 85

    /** Уменьшенный и повёрнутый кадр в base64 для мультимодального запроса. */
    fun forApi(file: File): ClaudeProvider.ImagePart? {
        val bytes = shrink(file) ?: return null
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return ClaudeProvider.ImagePart("image/jpeg", base64)
    }

    /** Тот же уменьшенный кадр, но на диск: так он и хранится в дневнике. */
    fun writeShrunk(source: File, target: File) {
        val bytes = shrink(source) ?: source.readBytes()
        target.writeBytes(bytes)
    }

    private fun shrink(file: File): ByteArray? {
        if (!file.exists() || file.length() == 0L) return null
        return runCatching {
            // Сначала только размеры: разворачивать 12 мегапикселей в память
            // ради того, чтобы узнать ширину, - верный путь к OutOfMemory.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return null
            // inSampleSize только степень двойки: берём такую, чтобы кадр стал
            // не меньше предела, а точный размер добираем масштабированием.
            var sample = 1
            while (longest / (sample * 2) >= MAX_SIDE) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
                ?: return null
            val scaled = scaleToFit(decoded)
            val rotated = applyExif(file, scaled)
            val out = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            if (rotated !== decoded) rotated.recycle()
            if (scaled !== decoded && scaled !== rotated) scaled.recycle()
            decoded.recycle()
            out.toByteArray()
        }.getOrNull()
    }

    private fun scaleToFit(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_SIDE) return bitmap
        val k = MAX_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * k).toInt().coerceAtLeast(1),
            (bitmap.height * k).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun applyExif(file: File, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = android.graphics.Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }
}
