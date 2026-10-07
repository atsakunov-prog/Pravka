package ru.zf.pravka.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

// Детали набора Правки 4.0 (07.10.2026, DESIGN §11.7) — всё, из чего
// собираются экраны режимов: заголовок раздела, сегменты, клавиша-капсула,
// значок с подписью, переключатель, ползунок, «i», пусто / считаю / ошибка /
// нет связи. Правило набора прежнее: новая деталь — сначала сюда, потом на
// экран. Цвета — из `LocalMode`, не константами.

// ---------------------------------------------------------------------------
// Лицо клавиши любой формы
// ---------------------------------------------------------------------------

/**
 * Клавиша-капсула (выбранный сегмент, главная кнопка): цвет кнопки режима,
 * блик-эллипс из (34 %, 20 %) и тёмный низ, как у круглой клавиши (`.on` и
 * `.key` в макетах). [hi] — сила блика: 0.38 у сегмента, 0.5–0.6 у кнопок.
 */
fun Modifier.keyFace4(color: Color, shape: Shape, pressed: Boolean = false, hi: Float = 0.5f, low: Float = 0.45f, cream: Boolean = false): Modifier =
    this.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = androidx.compose.ui.graphics.Path().apply {
            when (outline) {
                is androidx.compose.ui.graphics.Outline.Generic -> addPath(outline.path)
                is androidx.compose.ui.graphics.Outline.Rectangle -> addRect(outline.rect)
                is androidx.compose.ui.graphics.Outline.Rounded -> addRoundRect(outline.roundRect)
            }
        }
        val w = size.width
        val h = size.height
        val hiA = (hi * if (pressed) 1.3f else 1f).coerceAtMost(1f)
        val hiBrush = Brush.radialGradient(
            0f to Color.White.copy(alpha = hiA),
            0.32f to Color.White.copy(alpha = hiA * 0.2f),
            0.5f to Color.White.copy(alpha = 0f),
            center = Offset.Zero, radius = w * 1.1f,
        )
        val lowColor = if (cream) Color(0xFF785A32).copy(alpha = 0.35f) else Color.Black.copy(alpha = low)
        val lowBrush = Brush.radialGradient(
            0f to lowColor, 0.7f to lowColor.copy(alpha = 0f),
            center = Offset.Zero, radius = w,
        )
        val rim = Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (cream) 0.6f else 0.32f),
            0.3f to Color.White.copy(alpha = 0f),
            0.75f to Color.Transparent,
            1f to (if (cream) Color(0xFF785A32).copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.30f)),
        )
        val stroke = Stroke(1.dp.toPx())
        onDrawBehind {
            drawOutline(outline, color)
            clipPath(path) {
                translate(w * 0.34f, h * 0.20f) {
                    scale(1f, (0.85f * h) / (1.1f * w), pivot = Offset.Zero) { drawCircle(hiBrush, w * 1.1f, Offset.Zero) }
                }
                translate(w * 0.5f, h * 1.18f) {
                    scale(1f, (0.70f * h) / w, pivot = Offset.Zero) { drawCircle(lowBrush, w, Offset.Zero) }
                }
            }
            drawOutline(outline, rim, style = stroke)
        }
    }

// ---------------------------------------------------------------------------
// Заголовок раздела
// ---------------------------------------------------------------------------

/**
 * Заголовок раздела над плашкой (DESIGN §11.7): слева overline прописными в
 * подписи режима («НА СЕГОДНЯ И ПРОСРОЧЕННОЕ · 4»), справа число или сумма
 * вторым тоном («1 ч 40 м»), иногда маленькая клавиша или «i». Отступы
 * макета — 18 сверху, 24 по краям, 8 снизу; в ленте с полями 16 это 8 по
 * краям, а сверху — 6 сверх общего шага.
 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    color: Color? = null,
    info: Pair<String, String>? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    val mode = LocalMode.current
    val t = LocalPravkaType.current
    Row(
        modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(Locale.forLanguageTag("ru")),
            style = t.overline,
            color = color ?: mode.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            Text(trailing, style = t.label, color = mode.meta, maxLines = 1)
        }
        if (info != null) InfoButton(info.first, info.second)
        action?.invoke(this)
    }
}

// ---------------------------------------------------------------------------
// Сегменты и чипы
// ---------------------------------------------------------------------------

/**
 * Сегмент-капсула (DESIGN §11.7, `.seg` в макетах): выбранный — маленькая
 * клавиша в цвете кнопки режима, остальные — текст подписи с тонкой рамкой.
 * У каждого — число («Новое 4»): у выбранного светлое, у остальных — вторым тоном.
 */
@Composable
fun Segment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    count: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 36.dp,
) {
    val mode = LocalMode.current
    val t = LocalPravkaType.current
    val shape = RoundedCornerShape(50)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val today = mode === Modes.Today
    val onKey = if (today) Ink.NowInk else Color(0xFFF4EEE3)
    Row(
        Modifier
            .minimumInteractiveComponentSize()
            .height(height)
            .alpha(if (enabled) 1f else 0.4f)
            .then(
                if (selected) Modifier
                    .softShadow(shape, Color.Black.copy(alpha = 0.35f), 4.dp, 10.dp)
                    .keyFace4(mode.key, shape, pressed, hi = 0.38f, low = 0.3f, cream = today)
                    .border(1.dp, mode.tint.copy(alpha = 0.55f), shape)
                else Modifier.clip(shape).border(1.dp, mode.tint.copy(alpha = 0.18f), shape)
            )
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (selected) onKey else mode.label, modifier = Modifier.size(16.dp))
        Text(
            label,
            style = t.label.copy(fontSize = 13.5.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = if (selected) onKey else mode.label,
            maxLines = 1,
        )
        if (count != null) {
            Text(
                count,
                style = t.label.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                color = if (selected) (if (today) Ink.NowInk else mode.value) else mode.meta,
                maxLines = 1,
            )
        }
    }
}

/**
 * Ряд сегментов, листается вбок (DESIGN §11.7). Подпись вида «Новое 4» или
 * «Новое · 4» сама делится на слово и число — старые вызовы `Segments`
 * передают строки целиком.
 */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke(this)
        options.forEachIndexed { i, raw ->
            val (label, count) = splitCount(raw)
            Segment(label, selected = i == selected, onClick = { onSelect(i) }, count = count)
        }
    }
}

private val COUNT_TAIL = Regex("""^(.*?)(?:\s*·\s*|\s+)(\d[\d  ]*)$""")

/** «Новое · 4» → («Новое», «4»); без числа — как есть. */
internal fun splitCount(raw: String): Pair<String, String?> {
    val m = COUNT_TAIL.matchEntire(raw.trim()) ?: return raw to null
    return m.groupValues[1] to m.groupValues[2]
}

// ---------------------------------------------------------------------------
// Кнопки
// ---------------------------------------------------------------------------

/**
 * Главная кнопка (DESIGN §11.7 PrimaryKey) — одна на плашку, справа внизу:
 * клавиша-капсула в цвете кнопки режима, значок и слово. На «Сегодня» —
 * кремовая.
 */
@Composable
fun PrimaryKey(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 44.dp,
) {
    val mode = LocalMode.current
    val t = LocalPravkaType.current
    val shape = RoundedCornerShape(50)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val today = mode === Modes.Today
    val ink = if (today) Ink.NowInk else Color(0xFFF7F1E6)
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .defaultMinSize(minHeight = height)
            .alpha(if (enabled) 1f else 0.4f)
            .softShadow(shape, Color.Black.copy(alpha = 0.45f), 6.dp, 14.dp)
            .keyFace4(if (today) Ink.Cream else mode.key, shape, pressed, hi = 0.5f, cream = today)
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(PaddingValues(start = if (icon != null) 16.dp else 20.dp, end = 20.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = t.bodyStrong.copy(fontSize = 15.sp), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Второстепенная кнопка: капсула с тонкой рамкой tint и словом в подписи режима («Начать в Засечке»). */
@Composable
fun GhostKey(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 40.dp,
) {
    val mode = LocalMode.current
    val t = LocalPravkaType.current
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .defaultMinSize(minHeight = height)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(mode.tint.copy(alpha = 0.06f))
            .border(1.dp, mode.tint.copy(alpha = 0.30f), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(PaddingValues(start = if (icon != null) 14.dp else 18.dp, end = 18.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = mode.label, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = t.label.copy(fontSize = 14.sp), color = mode.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Значок с подписью (DESIGN §11.7 IconLabel, лист записи `screens/13`):
 * значок в круге 48 (tint @ .14, рамка tint @ .28), подпись 12.5 под ним.
 */
@Composable
fun RowScope.IconLabel(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val mode = LocalMode.current
    val t = LocalPravkaType.current
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (onLongClick != null) Modifier.combinedTap(enabled, onClick, onLongClick)
                else Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            )
            .padding(vertical = 6.dp)
            .alpha(if (enabled) 1f else 0.4f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(mode.tint.copy(alpha = if (active) 0.30f else 0.14f))
                .border(1.dp, mode.tint.copy(alpha = 0.28f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = mode.value, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = t.label, color = Ink.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

internal fun Modifier.combinedTap(enabled: Boolean, onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)

// ---------------------------------------------------------------------------
// Переключатель и ползунок
// ---------------------------------------------------------------------------

/**
 * Переключатель (DESIGN §11.7 Toggle): трек — чернила режима с рамкой
 * tint @ .3; включён — бегунок-клавиша в цвете кнопки справа, выключен —
 * кремовый контурный бегунок @ .5 слева.
 */
@Composable
fun Toggle(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val mode = LocalMode.current
    val pos by animateFloatAsState(if (checked) 1f else 0f, tween(160), label = "toggle")
    val track = RoundedCornerShape(50)
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .size(width = 48.dp, height = 28.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(track)
            .background(mode.ink.copy(alpha = 0.9f))
            .background(mode.key.copy(alpha = 0.22f * pos))
            .border(1.dp, mode.tint.copy(alpha = 0.3f), track)
            .then(
                if (onCheckedChange != null) Modifier.clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
                else Modifier
            ),
    ) {
        val thumb = 22.dp
        Box(
            Modifier
                .padding(3.dp)
                .offset(x = (48.dp - thumb - 6.dp) * pos)
                .size(thumb)
                .then(
                    if (pos > 0.5f) Modifier.keyDisc(mode.key, shadow = false)
                    else Modifier.border(1.5.dp, Ink.Cream.copy(alpha = 0.5f), CircleShape)
                ),
        )
    }
}

/**
 * Ползунок (DESIGN §11.7 Slider): трек 4 dp tint @ .18, пройденная часть —
 * tint, бегунок — клавиша 20 dp. Пишет по отпусканию, как и прежний.
 */
@Composable
fun Slider4(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val mode = LocalMode.current
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    var width by remember { mutableStateOf(1f) }
    fun at(x: Float): Float {
        val raw = valueRange.start + (x / width).coerceIn(0f, 1f) * span
        if (steps <= 0) return raw
        val n = steps + 1
        val k = kotlin.math.round((raw - valueRange.start) / span * n)
        return valueRange.start + k / n * span
    }
    val frac = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .then(
                if (!enabled) Modifier else Modifier.pointerSlide(
                    onWidth = { width = it },
                    onMove = { onValueChange(at(it)) },
                    onEnd = onValueChangeFinished,
                )
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxWidth().height(4.dp).padding(horizontal = 10.dp)) {
            val r = CornerRadius(2.dp.toPx())
            drawRoundRect(mode.tint.copy(alpha = 0.18f), cornerRadius = r)
            drawRoundRect(mode.tint, size = Size(size.width * frac, size.height), cornerRadius = r)
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 0.dp)) {
            Box(
                Modifier
                    .offsetFraction(frac)
                    .size(20.dp)
                    .keyDisc(mode.key, shadow = true),
            )
        }
    }
}

private fun Modifier.offsetFraction(frac: Float): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val p = measurable.measure(constraints.copy(minWidth = 0))
        val maxW = constraints.maxWidth
        layout(maxW, p.height) {
            p.placeRelative(((maxW - p.width) * frac).toInt(), 0)
        }
    }
)

private fun Modifier.pointerSlide(onWidth: (Float) -> Unit, onMove: (Float) -> Unit, onEnd: () -> Unit): Modifier =
    this
        .onSizeChanged { onWidth(it.width.toFloat().coerceAtLeast(1f)) }
        .pointerInput(Unit) {
            detectTapGestures { o -> onMove(o.x); onEnd() }
        }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { o -> onMove(o.x) },
                onDragEnd = onEnd,
                onDragCancel = onEnd,
            ) { change, _ -> onMove(change.position.x) }
        }

// ---------------------------------------------------------------------------
// «i», пусто, считаю, ошибка, нет связи
// ---------------------------------------------------------------------------

/**
 * «i» — 20 dp в кольце (DESIGN §11.7 InfoButton), зона касания 44. Открывает
 * лист с пояснением: это единственное место для объяснений.
 */
@Composable
fun InfoDot(title: String, text: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val mode = LocalMode.current
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = "пояснение") { open = true },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Glyphs.Info, "пояснение", tint = mode.label, modifier = Modifier.size(20.dp))
    }
    if (open) {
        PaperSheet(onDismiss = { open = false }, title = title.replaceFirstChar { it.uppercase() }) {
            Text(text, style = LocalPravkaType.current.body.copy(fontSize = 15.sp, lineHeight = 21.sp))
        }
    }
}

/** Пусто: одна строка и значок, иногда действие (DESIGN §11.7 EmptyState). */
@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val mode = LocalMode.current
    val t = LocalPravkaType.current
    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = mode.meta, modifier = Modifier.size(20.dp))
        Text(text, style = t.body, color = mode.meta, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) GhostKey(action, onAction)
    }
}

/**
 * «считаю…» и тонкая линия 2 dp, по которой один раз проходит заливка — не
 * пульсирует (DESIGN §11.7 LoadingLine).
 */
@Composable
fun LoadingLine(modifier: Modifier = Modifier, text: String = "считаю…") {
    val mode = LocalMode.current
    val t = LocalPravkaType.current
    var started by remember { mutableStateOf(false) }
    val p by animateFloatAsState(if (started) 1f else 0f, tween(1600), label = "loading")
    androidx.compose.runtime.LaunchedEffect(Unit) { started = true }
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
        Text(text, style = t.label, color = mode.meta)
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(2.dp)) {
            drawLine(mode.tint.copy(alpha = 0.18f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height, StrokeCap.Round)
            drawLine(mode.tint, Offset(0f, size.height / 2), Offset(size.width * p, size.height / 2), size.height, StrokeCap.Round)
        }
    }
}

/**
 * Ошибка (DESIGN §4.4, §11.7 ErrorPlate): плашка режима с рамкой tint @ .7,
 * «!» в кольце, «Не получилось», причина целиком — переносами, не обрезается;
 * «Повторить» (клавиша) и «Скопировать причину». Ошибку не затирать общей
 * фразой (железное правило 6).
 */
@Composable
fun ErrorPlate(
    reason: String,
    modifier: Modifier = Modifier,
    title: String = "Не получилось",
    onRetry: (() -> Unit)? = null,
) {
    val mode = LocalMode.current
    val t = LocalPravkaType.current
    val clipboard = LocalClipboardManager.current
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .fillMaxWidth()
            .glass(shape, mode.glass, shadow = false)
            .border(1.dp, mode.tint.copy(alpha = 0.7f), shape)
            .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Glyphs.Error, null, tint = mode.value, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, style = t.bodyStrong, color = mode.value)
        }
        Spacer(Modifier.height(4.dp))
        Text(reason, style = t.meta.copy(fontSize = 13.sp, lineHeight = 18.sp), color = Ink.Text)
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Скопировать причину",
                style = t.label,
                color = mode.label,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { clipboard.setText(AnnotatedString(reason)) }
                    .minimumInteractiveComponentSize()
                    .padding(horizontal = 6.dp),
            )
            Spacer(Modifier.weight(1f))
            if (onRetry != null) PrimaryKey("Повторить", onRetry, icon = Glyphs.Refresh, height = 40.dp)
        }
    }
}

/** «нет связи» — кремовый контурный чип (DESIGN §4.4); значения рядом — @ .55. */
@Composable
fun OfflineChip(modifier: Modifier = Modifier, text: String = "нет связи") {
    val t = LocalPravkaType.current
    val shape = RoundedCornerShape(7.dp)
    Row(
        modifier
            .clip(shape)
            .border(1.dp, Ink.Cream.copy(alpha = 0.45f), shape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(Glyphs.CloudOff, null, tint = Ink.Cream, modifier = Modifier.size(14.dp))
        Text(text, style = t.caption.copy(fontWeight = FontWeight.SemiBold), color = Ink.Cream)
    }
}

/** Чип-метка (радиус 7): категория, «Календарь», «Спорт · план». */
@Composable
fun TagChip(text: String, color: Color, background: Color, modifier: Modifier = Modifier, border: Color? = null) {
    val t = LocalPravkaType.current
    val shape = RoundedCornerShape(7.dp)
    Text(
        text,
        style = t.caption.copy(fontWeight = FontWeight.SemiBold, lineHeight = 17.sp),
        color = color,
        maxLines = 1,
        modifier = modifier
            .clip(shape)
            .background(background)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .padding(horizontal = 7.dp),
    )
}

/**
 * «Тренер» (DESIGN §11.7 CoachNote): заливка tint @ .10, радиус 16, подпись
 * сверху и текст заметки.
 */
@Composable
fun CoachNote(text: String, modifier: Modifier = Modifier, title: String = "Тренер") {
    val mode = LocalMode.current
    val t = LocalPravkaType.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(mode.tint.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(title, style = t.label.copy(fontWeight = FontWeight.SemiBold), color = mode.label)
        Spacer(Modifier.height(2.dp))
        Text(text, style = t.body.copy(lineHeight = 20.sp), color = Ink.Text)
    }
}

/**
 * Упражнение (DESIGN §11.7 ExerciseRow): название; вторая строка «прошлый
 * раз 4×6 @16 кг · ▲ +2 повтора»; справа цель жирным.
 */
@Composable
fun ExerciseRow(name: String, last: String?, target: String?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val mode = LocalMode.current
    val t = LocalPravkaType.current
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = t.bodyL, color = Ink.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!last.isNullOrBlank()) Text(last, style = t.meta, color = mode.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!target.isNullOrBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(target, style = t.bodyStrong.copy(fontSize = 15.sp), color = mode.value, maxLines = 1)
        }
    }
}

/**
 * Готовые вопросы к Claude (DESIGN §11.7 AskChips, Деньги): ряд капсул,
 * листается вбок, у первого — искры.
 */
@Composable
fun AskChips(questions: List<String>, onAsk: (String) -> Unit, modifier: Modifier = Modifier) {
    val mode = LocalMode.current
    val t = LocalPravkaType.current
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        questions.forEachIndexed { i, q ->
            val shape = RoundedCornerShape(50)
            Row(
                Modifier
                    .minimumInteractiveComponentSize()
                    .height(40.dp)
                    .clip(shape)
                    .glass(shape, mode.glass, shadow = false)
                    .clickable { onAsk(q) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (i == 0) Icon(Glyphs.Spark, null, tint = mode.label, modifier = Modifier.size(16.dp))
                Text(q, style = t.label.copy(fontSize = 13.5.sp), color = Ink.Text, maxLines = 1)
            }
        }
    }
}

/**
 * Плитка показателя (DESIGN §11.7 StatTile): подпись, значение, дельта
 * «▲ 40 м / ▼ 20 м». Направление — стрелкой; цвет дельты — акцентный текст
 * режима, «хуже» — приглушённее ([worse]); в Засечке «хуже» — #F27A45.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    delta: String? = null,
    worse: Boolean = false,
    dim: Boolean = false,
) {
    val mode = LocalMode.current
    val t = LocalPravkaType.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .background(mode.key.copy(alpha = 0.18f))
            .border(1.dp, mode.tint.copy(alpha = 0.26f), shape)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text(label, style = t.caption, color = mode.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            value,
            style = t.tile,
            color = mode.value.copy(alpha = if (dim) 0.55f else 1f),
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (!delta.isNullOrBlank()) {
            val zasechka = mode === Modes.Zasechka
            Text(
                delta,
                style = t.caption.copy(fontSize = 11.sp),
                color = when {
                    worse && zasechka -> Ink.Warn
                    worse -> mode.meta.copy(alpha = 0.8f)
                    else -> mode.meta
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Цитата-источник курсивом (синтетический наклон — у Golos курсива нет). */
@Composable
fun Quote(text: String, modifier: Modifier = Modifier, color: Color = Ink.Text) {
    Text(
        "«$text»",
        style = LocalPravkaType.current.body.copy(fontStyle = FontStyle.Italic, fontSize = 15.sp, lineHeight = 20.sp),
        color = color,
        modifier = modifier,
    )
}

/** Тонкая линия между строками внутри плашки — tint @ .10 (`border-top` в макетах). */
@Composable
fun Hairline(modifier: Modifier = Modifier, alpha: Float = 0.10f) {
    val mode = LocalMode.current
    Box(modifier.fillMaxWidth().height(1.dp).background(mode.tint.copy(alpha = alpha)))
}

/** Текст цветом содержимого — для старых экранов, которым нужен `LocalContentColor`. */
@Composable
fun WithContent(color: Color, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalContentColor provides color, content = content)

/**
 * Текст в одну строку, который ужимается, чтобы поместиться целиком (DESIGN
 * §3.9: «заголовки помещаются целиком», «1 074 898» на плашке). Шаг — 0.5 sp,
 * не мельче [minSize].
 */
@Composable
fun FitText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    minSize: Float = 12f,
) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val max = constraints.maxWidth
        val base = style.fontSize.value
        val size = remember(text, style, max) {
            var s = base
            while (s > minSize) {
                val w = measurer.measure(text, style.copy(fontSize = s.sp, lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified), maxLines = 1, softWrap = false).size.width
                if (w <= max) break
                s -= 0.5f
            }
            s
        }
        Text(text, style = style.copy(fontSize = size.sp), color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}
