package ru.zf.pravka.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.zf.pravka.R

// Материал Правки 4.0 (07.10.2026, DESIGN §8): все поверхности — стекло в
// чернилах режима, всё круглое нажимаемое — клавиша с бликом. Тот же
// материал, что у кнопок на стекле и пилюли диктовки, поэтому приложение с
// ними рифмуется. Числа слоёв — из `mockups/*.html` (классы `.glass`, `.key`,
// `.cream`, `.coin`), CSS переложен в слои Compose: фон, налёт, блик, рамка
// с более светлым верхом, внутренняя линия, тень. Размытия фона нет — в
// Android оно бывает только прямоугольником окна.

// ---------------------------------------------------------------------------
// Тень
// ---------------------------------------------------------------------------

/**
 * Тень как `box-shadow: 0 dy blur color` — размытым контуром формы. Обычный
 * `Modifier.shadow` рисует тень по высоте материала и светлую кромку сверху —
 * это не тот вид (DESIGN §14), поэтому — `BlurMaskFilter` фреймворка: контур
 * формы, сдвинутый вниз и размытый. Радиус размытия CSS — это две сигмы;
 * радиус `BlurMaskFilter` ≈ сигма / 0.57735.
 */
fun Modifier.softShadow(
    shape: Shape,
    color: Color = Color.Black.copy(alpha = 0.40f),
    dy: Dp = 14.dp,
    blur: Dp = 30.dp,
): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val sigma = blur.toPx() / 2f
    val paint = Paint().apply {
        asFrameworkPaint().apply {
            isAntiAlias = true
            this.color = color.toArgb()
            if (sigma > 0.5f) maskFilter = android.graphics.BlurMaskFilter(sigma / 0.57735f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
    }
    val dyPx = dy.toPx()
    onDrawBehind {
        drawIntoCanvas { canvas ->
            canvas.save()
            canvas.translate(0f, dyPx)
            when (outline) {
                is Outline.Rectangle -> canvas.drawRect(outline.rect, paint)
                is Outline.Rounded -> {
                    val r = outline.roundRect
                    canvas.drawRoundRect(r.left, r.top, r.right, r.bottom, r.topLeftCornerRadius.x, r.topLeftCornerRadius.y, paint)
                }
                is Outline.Generic -> canvas.drawPath(outline.path, paint)
            }
            canvas.restore()
        }
    }
}

// ---------------------------------------------------------------------------
// Стекло
// ---------------------------------------------------------------------------

/**
 * Стеклянная плашка (DESIGN §8): снизу вверх — чернила режима, налёт цвета
 * кнопки сверху вниз, блик светлым tint до 30 % высоты, рамка 1 dp со светлым
 * верхом, внутренняя линия по верху и тень 0/14/30. [glass] — числа режима
 * (`LocalMode.current.glass`); [strong] — плотнее, для листа снизу.
 */
fun Modifier.glass(
    shape: Shape,
    glass: GlassSpec,
    shadow: Boolean = true,
    alphaScale: Float = 1f,
): Modifier = this
    .then(if (shadow) Modifier.softShadow(shape) else Modifier)
    .drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = outlinePath(outline)
        val h = size.height.coerceAtLeast(1f)
        val base = glass.base.copy(alpha = glass.inkAlpha * alphaScale)
        val keyBrush = if (glass.keyTop > 0f || glass.keyBottom > 0f) Brush.verticalGradient(
            0f to glass.key.copy(alpha = glass.keyTop * alphaScale),
            1f to glass.key.copy(alpha = glass.keyBottom * alphaScale),
        ) else null
        val sheen = Brush.verticalGradient(
            0f to glass.sheenColor.copy(alpha = glass.sheen),
            0.30f to glass.sheenColor.copy(alpha = glass.sheen * 0.23f),
            1f to glass.sheenColor.copy(alpha = 0f),
        )
        // Верх рамки светлее: CSS `border-top-color`. Переход — на высоте угла.
        val corner = cornerOf(outline).coerceAtMost(h / 2f)
        val rimBrush = Brush.verticalGradient(
            0f to glass.rimTopColor.copy(alpha = glass.rimTop),
            (corner / h).coerceIn(0.01f, 0.5f) to glass.rimColor.copy(alpha = glass.rim),
            1f to glass.rimColor.copy(alpha = glass.rim),
        )
        val stroke = Stroke(1.dp.toPx())
        val line = 1.dp.toPx()
        onDrawBehind {
            drawOutline(outline, base)
            if (keyBrush != null) drawOutline(outline, keyBrush)
            drawOutline(outline, sheen)
            // Внутренняя линия по верху: CSS `inset 0 1px 0`.
            clipPath(path) {
                drawRect(glass.innerTop, topLeft = Offset(0f, line), size = Size(size.width, line))
            }
            drawOutline(outline, rimBrush, style = stroke)
        }
    }

private fun outlinePath(o: Outline): Path = when (o) {
    is Outline.Generic -> o.path
    is Outline.Rectangle -> Path().apply { addRect(o.rect) }
    is Outline.Rounded -> Path().apply { addRoundRect(o.roundRect) }
}

private fun cornerOf(o: Outline): Float = when (o) {
    is Outline.Rounded -> o.roundRect.topLeftCornerRadius.y
    else -> 0f
}

/** Стекло текущего режима: `Modifier.glass(shape, LocalMode.current.glass)`. */
@Composable
fun Modifier.modeGlass4(shape: Shape, shadow: Boolean = true): Modifier =
    this.glass(shape, LocalMode.current.glass, shadow)

// ---------------------------------------------------------------------------
// Клавиши
// ---------------------------------------------------------------------------

/**
 * Лицо круглой клавиши (DESIGN §8 «Клавиша», `.key` в макетах): блик —
 * радиальный из (34 %, 20 %) белым, низ — радиальный тёмный из (50 %, 118 %),
 * внутри — светлая линия сверху и тёмная снизу, тень 0/6/14. Нажатая —
 * блик ярче (палец «давит» свет), сама клавиша на 3 % меньше.
 */
fun Modifier.keyDisc(
    color: Color,
    pressed: Boolean = false,
    cream: Boolean = false,
    shadow: Boolean = true,
): Modifier = this
    .then(if (shadow) Modifier.softShadow(CircleShape, Color.Black.copy(alpha = 0.45f), 6.dp, 14.dp) else Modifier)
    .drawWithCache {
        val w = size.width
        val h = size.height
        val hiA = if (cream) 0.75f else 0.60f
        val hi = Brush.radialGradient(
            0f to Color.White.copy(alpha = (hiA * if (pressed) 1.25f else 1f).coerceAtMost(1f)),
            0.32f to Color.White.copy(alpha = 0.12f),
            0.5f to Color.White.copy(alpha = 0f),
            center = Offset.Zero,
            radius = w * 1.1f,
        )
        val lowColor = if (cream) Color(0xFF785A32).copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.55f)
        val low = Brush.radialGradient(
            0f to lowColor,
            0.70f to lowColor.copy(alpha = 0f),
            center = Offset.Zero,
            radius = w,
        )
        val topLine = Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (cream) 0.6f else 0.42f),
            0.22f to Color.White.copy(alpha = 0f),
        )
        val bottomLine = Brush.verticalGradient(
            0.72f to Color.Transparent,
            1f to (if (cream) Color(0xFF785A32).copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.35f)),
        )
        val r = minOf(w, h) / 2f
        val c = Offset(w / 2f, h / 2f)
        val stroke1 = Stroke(1.dp.toPx())
        val stroke2 = Stroke(2.dp.toPx())
        onDrawBehind {
            drawCircle(color, r, c)
            // Эллипсы CSS (110 % × 85 %) — круг, сжатый по высоте.
            translate(w * 0.34f, h * 0.20f) {
                scale(1f, 0.85f / 1.1f, pivot = Offset.Zero) { drawCircle(hi, w * 1.1f, Offset.Zero) }
            }
            clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }) {
                translate(w * 0.5f, h * 1.18f) {
                    scale(1f, 0.70f, pivot = Offset.Zero) { drawCircle(low, w, Offset.Zero) }
                }
            }
            drawCircle(topLine, r - 0.5.dp.toPx(), c, style = stroke1)
            drawCircle(bottomLine, r - 1.dp.toPx(), c, style = stroke2)
        }
    }

/**
 * Круглая клавиша с значком (DESIGN §11.1): 44 / 50 / 52 / 56 dp. В цвете
 * кнопки режима ([color], по умолчанию — `LocalMode.key`) или кремовая
 * ([cream] — главная кнопка «Сегодня», рифма с «ОК · 3» на плашке дел).
 * Неактивная — @ 0.4. Зона касания не меньше 48 dp.
 */
@Composable
fun Key(
    icon: ImageVector?,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    cream: Boolean = false,
    color: Color? = null,
    enabled: Boolean = true,
    iconSize: Dp = size * 0.46f,
    content: (@Composable BoxScope.() -> Unit)? = null,
) {
    val mode = LocalMode.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // На «Сегодня» кнопка режима — кремовая (DESIGN §8 CreamKey).
    @Suppress("NAME_SHADOWING")
    val cream = cream || (color == null && mode === Modes.Today)
    val face = if (cream) Ink.Cream else color ?: mode.key
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .size(size)
            .alpha(if (enabled) 1f else 0.4f)
            .scale(if (pressed) 0.97f else 1f)
            .keyDisc(face, pressed, cream)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClickLabel = description,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) content()
        else if (icon != null) Icon(
            icon,
            contentDescription = description,
            tint = if (cream) Ink.NowInk else Ink.KeyIcon,
            modifier = Modifier.size(iconSize),
        )
    }
}

// ---------------------------------------------------------------------------
// Монета
// ---------------------------------------------------------------------------

/** Буква режима на монете — векторы плавающих кнопок (оверлей их не теряет: мы только читаем). */
fun coinGlyph(decor: ModeDecor): Int = when (decor) {
    ModeDecor.PRAVKA, ModeDecor.SERVICE, ModeDecor.TODAY -> R.drawable.ic_fab_glyph
    ModeDecor.ZASECHKA -> R.drawable.ic_zfab_glyph
    ModeDecor.DELA -> R.drawable.ic_razn_glyph
    // «Т» и «Е» в том же брусковом языке уже есть в проекте: Т — бывшая
    // буква Тела, Е — буква выключенной с завода кнопки Еды.
    ModeDecor.SPORT -> R.drawable.ic_body_glyph
    ModeDecor.FOOD -> R.drawable.ic_efab_glyph
    ModeDecor.MONEY -> R.drawable.ic_money_glyph
}

/**
 * Монета (DESIGN §8): круг в цвете кнопки режима с бликом и буквой режима.
 * Диаметры: 20 — отметка в ленте, 24 — ждёт подтверждения, 30 — пилюля
 * «+84», 34 — шапка режима. Буква — около 0.55 диаметра (векторы кнопок
 * держат её ~0.52 своего поля, поэтому рисуются чуть крупнее поля монеты).
 */
@Composable
fun Coin(decor: ModeDecor, size: Dp, modifier: Modifier = Modifier) {
    val key = Modes.of(decor).key
    Box(
        modifier
            .size(size)
            .softShadow(CircleShape, Color.Black.copy(alpha = 0.45f), 3.dp, 8.dp)
            .drawBehind { drawCoin(key) },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(coinGlyph(decor)),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Ink.CoinLetter),
            modifier = Modifier.size(size * 1.06f),
        )
    }
}

private fun DrawScope.drawCoin(key: Color) {
    val w = size.width
    val r = w / 2f
    val c = Offset(r, size.height / 2f)
    drawCircle(key, r, c)
    // radial-gradient(70% 55% at 36% 24%, rgba(255,236,210,.50) 0, 0 60%)
    translate(w * 0.36f, size.height * 0.24f) {
        scale(1f, 0.55f / 0.70f, pivot = Offset.Zero) {
            drawCircle(
                Brush.radialGradient(
                    0f to Color(0xFFFFECD2).copy(alpha = 0.50f),
                    0.60f to Color(0xFFFFECD2).copy(alpha = 0f),
                    center = Offset.Zero, radius = w * 0.70f,
                ),
                w * 0.70f, Offset.Zero,
            )
        }
    }
    clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }) {
        translate(w * 0.5f, size.height * 1.15f) {
            scale(1f, 0.60f / 0.90f, pivot = Offset.Zero) {
                drawCircle(
                    Brush.radialGradient(
                        0f to Color.Black.copy(alpha = 0.38f),
                        0.70f to Color.Black.copy(alpha = 0f),
                        center = Offset.Zero, radius = w * 0.90f,
                    ),
                    w * 0.90f, Offset.Zero,
                )
            }
        }
    }
    drawCircle(
        Brush.verticalGradient(0f to Color.White.copy(alpha = 0.25f), 0.2f to Color.Transparent),
        r - 0.5.dp.toPx(), c, style = Stroke(1.dp.toPx()),
    )
}

// ---------------------------------------------------------------------------
// Штриховка
// ---------------------------------------------------------------------------

/**
 * Штриховка (DESIGN §4.3): полосы под 135° — 2 dp краски и просвет той же
 * краски @ [gapAlpha] (0.30 у потерь, 0.07 у «впереди»), период [period].
 * Рисуется по прямоугольнику [topLeft]–[size] внутри уже заданного клипа.
 */
fun DrawScope.hatch(
    color: Color,
    gapAlpha: Float,
    topLeft: Offset = Offset.Zero,
    size: Size = this.size,
    stripeAlpha: Float = color.alpha,
    period: Dp = 5.dp,
) {
    val p = period.toPx()
    val stripe = 2.dp.toPx()
    drawRect(color.copy(alpha = gapAlpha), topLeft, size)
    // Полоса «/»: от низа-слева к верху-справа; шаг по горизонтали — p·√2.
    val step = p * 1.41421f
    val w = size.width
    val h = size.height
    var x = -h
    val brush = color.copy(alpha = stripeAlpha)
    clipPath(Path().apply { addRect(androidx.compose.ui.geometry.Rect(topLeft, size)) }) {
        while (x < w + h) {
            drawLine(
                brush,
                start = Offset(topLeft.x + x, topLeft.y + h),
                end = Offset(topLeft.x + x + h, topLeft.y),
                strokeWidth = stripe,
            )
            x += step
        }
    }
}

/** Кнопка без лица — зона касания 48 dp вокруг маленькой видимой цели (DESIGN §3.8). */
@Composable
fun Modifier.tap(onClick: () -> Unit, label: String? = null, enabled: Boolean = true): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = LocalIndication.current,
        enabled = enabled,
        role = Role.Button,
        onClickLabel = label,
        onClick = onClick,
    )

/** Прямоугольник без формы — для `glass` у полос во всю ширину. */
val NoShape: Shape = RectangleShape
