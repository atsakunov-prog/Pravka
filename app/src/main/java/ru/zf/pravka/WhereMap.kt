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

/** Человек на карте: где, насколько точно, насколько свежо и кто он. */
internal data class WherePin(
    val device: String,
    val name: String,
    val color: Long,
    val lat: Double,
    val lon: Double,
    val acc: Float,
    /** 1 — свежая точка, меньше — старее. */
    val alpha: Float,
    val avatar: Bitmap?,
)

/** Какие плитки: OpenStreetMap тёмный (наш фильтр) или светлый как есть. */
internal enum class WhereTiles {
    DARK, LIGHT;

    companion object {
        /** Сохранённый выбор; прежние «CARTO» и «OSM» — тёмная. */
        fun of(name: String): WhereTiles = if (name == LIGHT.name) LIGHT else DARK
    }
}

/**
 * Тёмный OpenStreetMap: инверсия и поворот оттенка на 180° — вода остаётся
 * синеватой, парки зеленоватыми, фон тёмный — и чуть тёплый, в тон чернилам
 * приложения (проверено на плитке Кутузовского: подписи читаются).
 */
private val OSM_DARK_FILTER = ColorMatrixColorFilter(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    ).apply {
        postConcat(
            ColorMatrix(
                floatArrayOf(
                    -0.574f, 1.43f, 0.144f, 0f, 0f,
                    0.426f, 0.43f, 0.144f, 0f, 0f,
                    0.426f, 1.43f, -0.856f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
        postConcat(ColorMatrix().apply { setScale(0.90f, 0.84f, 0.76f, 1f) })
    }
)

/**
 * osmdroid держит настройки в одном объекте на процесс: агент для серверов
 * плиток (OpenStreetMap отказывает безымянным) и кэш — в кэше приложения, не
 * в общей памяти телефона (иначе нужно разрешение на файлы).
 */
private fun configure(context: Context) {
    val c = Configuration.getInstance()
    if (c.userAgentValue == context.packageName) return
    c.userAgentValue = context.packageName
    val base = File(context.cacheDir, "osmdroid")
    c.osmdroidBasePath = base
    c.osmdroidTileCache = File(base, "tiles")
    c.tileFileSystemCacheMaxBytes = 200L * 1024 * 1024
    c.tileFileSystemCacheTrimBytes = 150L * 1024 * 1024
}

@SuppressLint("ClickableViewAccessibility")
@Composable
internal fun WhereMap(
    pins: List<WherePin>,
    tiles: WhereTiles,
    /** Сменился — показать всех разом. */
    fitKey: Int,
    /** Сменился — подлететь к человеку. */
    focus: Pair<String, Int>?,
    onPinTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val map = remember {
        configure(context)
        MapView(context).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            isVerticalMapRepetitionEnabled = false
            minZoomLevel = 3.0
            maxZoomLevel = 19.5
            setBackgroundColor(0xFF15171C.toInt())
            overlayManager.tilesOverlay.setLoadingBackgroundColor(0xFF15171C.toInt())
            overlayManager.tilesOverlay.setLoadingLineColor(0xFF1E2128.toInt())
            // Москва, пока точек нет: первая точка всё равно подлетит.
            controller.setZoom(11.0)
            controller.setCenter(GeoPoint(55.751, 37.618))
            // Карта сама ведёт палец: прокрутка вокруг не должна его перехватывать.
            setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
    }

    val layer = remember { PinLayer() }

    // Жизненный цикл карты: плитки качаются только на видимом экране.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) map.onResume()
        onDispose {
            lifecycle.removeObserver(obs)
            map.onPause()
            map.onDetach()
        }
    }

    LaunchedEffect(tiles) {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.overlayManager.tilesOverlay.setColorFilter(if (tiles == WhereTiles.DARK) OSM_DARK_FILTER else null)
        map.invalidate()
    }

    LaunchedEffect(fitKey, pins.isEmpty()) {
        if (pins.isEmpty()) return@LaunchedEffect
        fitAll(map, pins)
    }

    LaunchedEffect(focus) {
        val (device, _) = focus ?: return@LaunchedEffect
        val p = pins.firstOrNull { it.device == device } ?: return@LaunchedEffect
        map.controller.animateTo(GeoPoint(p.lat, p.lon), maxOf(map.zoomLevelDouble, 16.0), 600L)
    }

    AndroidView(
        factory = { map },
        modifier = modifier,
        update = { m -> drawPins(m, layer, pins, onPinTap) },
    )
}

/** Все точки в кадр; одна — крупно на ней. До первой раскладки карта размеров не знает. */
private fun fitAll(map: MapView, pins: List<WherePin>) {
    val go = {
        if (pins.size == 1) {
            map.controller.setZoom(15.5)
            map.controller.animateTo(GeoPoint(pins[0].lat, pins[0].lon))
        } else {
            val box = BoundingBox.fromGeoPointsSafe(pins.map { GeoPoint(it.lat, it.lon) })
            val pad = (64 * map.resources.displayMetrics.density).toInt()
            runCatching { map.zoomToBoundingBox(box, true, pad, 16.5, 700L) }
        }
    }
    if (map.width > 0 && map.height > 0) go() else map.addOnFirstLayoutListener { _, _, _, _, _ -> go() }
}

/** Наши слои: круги точности снизу, аватары сверху. Плитки и чужие слои не трогаем. */
private class PinLayer(var items: List<Overlay> = emptyList())

private fun drawPins(map: MapView, layer: PinLayer, pins: List<WherePin>, onPinTap: (String) -> Unit) {
    layer.items.forEach { map.overlays.remove(it) }
    val d = map.resources.displayMetrics.density
    val circles = pins.filter { it.acc > 15f }.map { p ->
        Polygon(map).apply {
            points = Polygon.pointsAsCircle(GeoPoint(p.lat, p.lon), p.acc.toDouble())
            fillPaint.color = withAlpha(p.color, 0.13f * p.alpha)
            outlinePaint.color = withAlpha(p.color, 0.45f * p.alpha)
            outlinePaint.strokeWidth = 1.2f * d
            setInfoWindow(null)
            setOnClickListener { _, _, _ -> false }
        }
    }
    val markers = pins.map { p ->
        Marker(map).apply {
            position = GeoPoint(p.lat, p.lon)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = BitmapDrawable(map.resources, pinBitmap(p, (46 * d).toInt(), d))
            alpha = p.alpha.coerceIn(0.35f, 1f)
            title = p.name
            setInfoWindow(null)
            setOnMarkerClickListener { _, _ ->
                onPinTap(p.device)
                true
            }
        }
    }
    val items = circles + markers
    map.overlays.addAll(items)
    layer.items = items
    map.invalidate()
}

private fun withAlpha(argb: Long, a: Float): Int = (((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (argb and 0xFFFFFF).toInt())

/** Аватар маркера: кольцо цвета человека, белая кромка для тёмной карты, фото или буква. */
private fun pinBitmap(p: WherePin, size: Int, density: Float): Bitmap {
    val ring = (3.2f * density)
    val edge = (1.2f * density)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val r = size / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = 0xE6FFFFFF.toInt()
    c.drawCircle(r, r, r, paint)
    paint.color = (p.color or 0xFF000000).toInt()
    c.drawCircle(r, r, r - edge, paint)
    drawFace(c, r, r, r - edge - ring, p.avatar, p.name, p.color)
    return bmp
}

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

/** Круглый аватар для списка — то же лицо, что на карте, без кольца. */
internal fun faceBitmap(avatar: Bitmap?, name: String, color: Long, size: Int): Bitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    drawFace(Canvas(bmp), size / 2f, size / 2f, size / 2f, avatar, name, color)
    return bmp
}

/** Фото аватара с диска; нет или не читается — null (тогда буква). */
internal fun loadAvatar(file: File?): Bitmap? = file?.let { f -> runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() }
