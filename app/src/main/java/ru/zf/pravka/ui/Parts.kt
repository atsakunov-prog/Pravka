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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
//
// Вид v2 (Правка 5.0): каждая деталь сама знает свой плоский вид (`Look.v2`) —
// кнопки и поля прямоугольником 8, метки 5, выбранное — мягким акцентом.

// ---------------------------------------------------------------------------
// Лицо клавиши любой формы
// ---------------------------------------------------------------------------

/**
 * Клавиша-капсула (выбранный сегмент, главная кнопка): цвет кнопки режима и
 * свет плашки на диске (`trigger/BubbleSkin.kt`, числа — [KeyLight]): блик
 * продольной полосой по верхней трети, затенение — по нижней пятой, тонкая
 * фаска. Баг №3 (07.10.2026): прежний радиальный блик 0,5 из угла светил
 * фонарём. [cream] — кремовая капсула «Сегодня» (тот же свет).
 */
fun Modifier.keyFace4(color: Color, shape: Shape, pressed: Boolean = false, cream: Boolean = false): Modifier =
    this.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        // Вид v2: заливка без блика, фаски и тени (план §2).
        if (Look.v2) return@drawWithCache onDrawBehind { drawOutline(outline, if (pressed) color.copy(alpha = color.alpha * 0.85f) else color) }
        val h = size.height
        val half = minOf(size.width, h) / 2f
        val lit = if (pressed) KeyLight.PRESSED_LIGHT else 1f
        val sheen = Brush.verticalGradient(
            0f to Color.White.copy(alpha = KeyLight.SHEEN * lit),
            0.55f to Color.White.copy(alpha = KeyLight.SHEEN * 0.3f * lit),
            1f to Color.White.copy(alpha = 0f),
            startY = 0f,
            endY = h * KeyLight.PILL_SHEEN_SPAN,
        )
        val foot = Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0f),
            1f to Color.Black.copy(alpha = KeyLight.PILL_FOOT),
            startY = h * (1f - KeyLight.PILL_FOOT_SPAN),
            endY = h,
        )
        val rimW = (half * KeyLight.RIM_WIDTH).coerceAtLeast(1f)
        val rim = Brush.verticalGradient(
            0f to Color.White.copy(alpha = KeyLight.RIM_LIGHT * lit),
            0.5f to Color.White.copy(alpha = 0f),
            1f to (if (cream) Color(0xFF785A32) else Color.Black).copy(alpha = KeyLight.PILL_RIM_SHADE),
        )
        val stroke = Stroke(rimW)
        onDrawBehind {
            drawOutline(outline, color)
            drawOutline(outline, foot)
            drawOutline(outline, sheen)
            drawOutline(outline, rim, style = stroke)
        }
    }

// ---------------------------------------------------------------------------
// Заголовок раздела
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
    if (Look.v2) {
        SegmentV2(label, selected, onClick, count, icon, enabled, height)
        return
    }
    val shape = RoundedCornerShape(50)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val today = mode === Modes.Today
    val onKey = if (today) Ink.NowInk else Color(0xFFF4EEE3)
    Row(
        Modifier
            .minimumInteractiveComponentSize()
            .height(height)
            .dim(if (enabled) 1f else 0.4f)
            .then(
                if (selected) Modifier
                    .softShadow(shape, Color.Black.copy(alpha = 0.35f), 4.dp, 10.dp)
                    .keyFace4(mode.key, shape, pressed, cream = today)
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
 * Сегмент вида v2 (`.seg span` веба): прямоугольник 8; выбранный — мягкий
 * акцент и слово акцентом, остальные — `--muted` на панели с линией.
 */
@Composable
private fun SegmentV2(label: String, selected: Boolean, onClick: () -> Unit, count: String?, icon: ImageVector?, enabled: Boolean, height: Dp) {
    val t = LocalPravkaType.current
    val shape = RoundedCornerShape(KvShape.Control)
    val ink = if (selected) Kv.accent else Kv.muted
    Row(
        Modifier
            .minimumInteractiveComponentSize()
            .height(height.coerceAtMost(34.dp))
            .dim(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(if (selected) Kv.accentSoft else Kv.panel)
            .then(if (selected) Modifier else Modifier.border(1.dp, Kv.line, shape))
            .clickable(enabled = enabled, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (selected) Kv.accent else Kv.icon, modifier = Modifier.size(16.dp))
        Text(
            label,
            style = t.label.copy(fontSize = 13.5.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = ink,
            maxLines = 1,
        )
        if (count != null) Counter(count, hot = selected)
    }
}

/** Счётчик (`.count` веба): прямоугольник 5, «горячий» — мягким акцентом. */
@Composable
fun Counter(text: String, hot: Boolean = false, modifier: Modifier = Modifier) {
    val t = LocalPravkaType.current
    Text(
        text,
        style = t.caption.copy(fontWeight = FontWeight.SemiBold, lineHeight = 19.sp),
        color = if (hot) Kv.accent else Kv.muted,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(KvShape.Tag))
            .background(if (hot) Kv.accentSoft else Kv.hover2)
            .widthIn(min = 20.dp)
            .padding(horizontal = 6.dp),
    )
}

/** Надстрочник: в 4.0 — прописными с разрядкой, в v2 — с заглавной, как `.eyebrow` веба («Засечка · идёт»). */
internal fun caps(text: String): String =
    if (Look.v4) text.uppercase(Locale.forLanguageTag("ru")) else text.replaceFirstChar { it.titlecase(Locale.forLanguageTag("ru")) }

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
    if (Look.v2) {
        // `.btn.p` веба: акцент, прямоугольник 8, слово 600.
        val shape8 = RoundedCornerShape(KvShape.Control)
        Row(
            modifier
                .minimumInteractiveComponentSize()
                .defaultMinSize(minHeight = height.coerceAtMost(40.dp))
                .dim(if (enabled) 1f else 0.4f)
                .clip(shape8)
                .background(Kv.accent)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(PaddingValues(start = if (icon != null) 12.dp else 14.dp, end = 14.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = Kv.accentInk, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = t.bodyStrong.copy(fontSize = 14.sp), color = Kv.accentInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        return
    }
    val shape = RoundedCornerShape(50)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val today = mode === Modes.Today
    val ink = if (today) Ink.NowInk else Color(0xFFF7F1E6)
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .defaultMinSize(minHeight = height)
            .dim(if (enabled) 1f else 0.4f)
            .softShadow(shape, Color.Black.copy(alpha = 0.35f), 3.dp, 9.dp)
            .keyFace4(if (today) Ink.Cream else mode.key, shape, pressed, cream = today)
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

/**
 * Малая кнопка вида v2 — `.btn.sm` веба (Правка 5.0, этап 2): значок и/или
 * слово, прямоугольник 7, 13/500. [soft] — мягкий акцент (`.btn.soft`: «Да» в
 * «Новом»), иначе панель с линией («Нет», «Стоп»). Зона касания — 48, как у
 * всех кнопок: строка от неё не выше, рисунок — по размеру слова.
 */
@Composable
fun SmallKey(
    icon: ImageVector?,
    text: String?,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    soft: Boolean = false,
    enabled: Boolean = true,
) {
    val t = LocalPravkaType.current
    val shape = RoundedCornerShape(7.dp)
    val ink = if (soft) Kv.accent else Kv.text
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .dim(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(if (soft) Kv.accentSoft else Kv.panel)
            .then(if (soft) Modifier else Modifier.border(1.dp, Kv.line, shape))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = if (text == null) 8.dp else 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = if (soft) Kv.accent else Kv.icon, modifier = Modifier.size(16.dp))
        if (icon != null && text != null) Spacer(Modifier.width(6.dp))
        if (text != null) Text(text, style = t.label.copy(fontSize = 13.sp, fontWeight = if (soft) FontWeight.SemiBold else FontWeight.Medium), color = ink, maxLines = 1)
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
    // Вид v2 — `.btn` веба: панель, линия, прямоугольник 8, слово цветом текста.
    val v2 = Look.v2
    val shape = if (v2) RoundedCornerShape(KvShape.Control) else RoundedCornerShape(50)
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .defaultMinSize(minHeight = if (v2) height.coerceAtMost(38.dp) else height)
            .dim(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(if (v2) Kv.panel else mode.tint.copy(alpha = 0.06f))
            .border(1.dp, if (v2) Kv.line else mode.tint.copy(alpha = 0.30f), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(PaddingValues(start = if (icon != null) 14.dp else 18.dp, end = 18.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (v2) Kv.icon else mode.label, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = t.label.copy(fontSize = 14.sp), color = if (v2) Kv.text else mode.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            .dim(if (enabled) 1f else 0.4f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val v2 = Look.v2
        // v2: без кружков — квадрат 10 с линией, как плитка веба; включённое — мягкий акцент.
        val badge = if (v2) RoundedCornerShape(KvShape.Panel) else CircleShape
        Box(
            Modifier
                .size(48.dp)
                .clip(badge)
                .background(if (v2) (if (active) Kv.accentSoft else Kv.panel) else mode.tint.copy(alpha = if (active) 0.30f else 0.14f))
                .border(1.dp, if (v2) (if (active) Kv.accentSoft else Kv.line) else mode.tint.copy(alpha = 0.28f), badge),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (v2) (if (active) Kv.accent else Kv.icon) else mode.value, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = t.label, color = Ink.Text, textAlign = TextAlign.Center)
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
    if (Look.v2) {
        // `.sw` веба: 34×20, радиус 6; включён — акцент, бегунок — квадрат 14 цвета панели.
        Box(
            modifier
                .minimumInteractiveComponentSize()
                .size(width = 34.dp, height = 20.dp)
                .dim(if (enabled) 1f else 0.4f)
                .clip(RoundedCornerShape(6.dp))
                .background(if (checked) Kv.accent else Kv.hover2)
                .then(
                    if (onCheckedChange != null) Modifier.clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
                    else Modifier
                ),
        ) {
            Box(
                Modifier
                    .padding(3.dp)
                    .offset(x = 14.dp * pos)
                    .size(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Kv.panel)
                    .then(if (checked) Modifier else Modifier.border(1.dp, Kv.line, RoundedCornerShape(4.dp))),
            )
        }
        return
    }
    val track = RoundedCornerShape(50)
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .size(width = 48.dp, height = 28.dp)
            .dim(if (enabled) 1f else 0.4f)
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
            .dim(if (enabled) 1f else 0.4f)
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
            drawRoundRect(if (Look.v2) Kv.hover2 else mode.tint.copy(alpha = 0.18f), cornerRadius = r)
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
        Icon(Glyphs.Info, "пояснение", tint = if (Look.v2) Kv.icon else mode.label, modifier = Modifier.size(if (Look.v2) 18.dp else 20.dp))
    }
    if (open) {
        PaperSheet(onDismiss = { open = false }, title = title.replaceFirstChar { it.uppercase() }) {
            Text(text, style = LocalPravkaType.current.body.copy(fontSize = 15.sp, lineHeight = 21.sp))
        }
    }
}

/** Тонкая линия между строками внутри плашки — tint @ .10 (`border-top` в макетах). */
@Composable
fun Hairline(modifier: Modifier = Modifier, alpha: Float = 0.10f) {
    val mode = LocalMode.current
    // v2: линия между строками — `--line-soft`, как `border-top` строк веба.
    Box(modifier.fillMaxWidth().height(1.dp).background(if (Look.v2) Kv.lineSoft else mode.tint.copy(alpha = alpha)))
}
