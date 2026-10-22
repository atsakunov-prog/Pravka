package ru.zf.pravka

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon

// Карта «Где мы» — osmdroid на обычном View внутри Compose. Плитки —
// OpenStreetMap, перекрашенный в тёмный тёплый: приложение всегда тёмное, и
// светлая карта посреди него слепила бы; светлый оригинал — вторым выбором.
// Ключа API у OpenStreetMap нет, подписи в Москве — русские; подпись
// источника — на самой карте, это его условие.
//
// Первой стояла CARTO Dark Matter, и в первый же вечер (10.10.2026) вместо
// карты — «API KEY REQUIRED» на каждой плитке: CARTO закрыла бесплатные
// плитки ключом. Чужой тёмный сервер может так же закрыться завтра; фильтр над
// OpenStreetMap — наш и не закроется.

private fun withAlpha(argb: Long, a: Float): Int = (((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (argb and 0xFFFFFF).toInt())

/** Лицо в круге: фото по центру или первая буква имени на тёмном цвете человека. */
internal fun drawFace(c: Canvas, cx: Float, cy: Float, radius: Float, avatar: Bitmap?, name: String, color: Long) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    if (avatar != null) {
        val shader = BitmapShader(avatar, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val side = minOf(avatar.width, avatar.height).toFloat()
        val scale = radius * 2 / side
        shader.setLocalMatrix(Matrix().apply {
            postTranslate(-(avatar.width - side) / 2f, -(avatar.height - side) / 2f)
            postScale(scale, scale)
            postTranslate(cx - radius, cy - radius)
        })
        paint.shader = shader
        c.drawCircle(cx, cy, radius, paint)
        return
    }
    // Без фото — буква на приглушённом цвете человека.
    val base = color.toInt()
    val dark = android.graphics.Color.rgb(
        (android.graphics.Color.red(base) * 0.45f).toInt(),
        (android.graphics.Color.green(base) * 0.45f).toInt(),
        (android.graphics.Color.blue(base) * 0.45f).toInt(),
    )
    paint.color = dark
    c.drawCircle(cx, cy, radius, paint)
    paint.color = 0xFFFFFFFF.toInt()
    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    paint.textSize = radius * 1.05f
    paint.textAlign = Paint.Align.CENTER
    val letter = name.trim().take(1).uppercase().ifBlank { "?" }
    val fm = paint.fontMetrics
    c.drawText(letter, cx, cy - (fm.ascent + fm.descent) / 2f, paint)
}
