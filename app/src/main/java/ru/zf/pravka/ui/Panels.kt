package ru.zf.pravka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Детали вида v2 (Правка 5.0, этап 6), которых в наборе 4.0 не было: экраны
// Спорта, Еды, Денег и Телефона на холсте собраны из классов общего слоя
// Ковчега — крупное число `.kpi`, строка с полосой `.cr`, строка списка `.li`,
// подсказка `.callout`, вкладки `.tabs`, столбики `.cols-ch`, малая кнопка
// `.btn.sm`, статус словом `.pill`, строка настройки `.set`. Здесь они — по
// одной на класс, размеры и цвета — ровно из `phone.css` холста и токенов
// `Kv`. Только для вида v2: в «Виде 4.0» экраны рисуют свои прежние детали.
// Плашка (`.card`) и её заголовок (`.sec`) — это `PaperCard` из `Blocks.kt`.

/**
 * Крупное число `.kpi`: 22/600, единица рядом мельче и бледнее («7,5 ч»),
 * подпись под ним 12 `--faint` («сон · оценка 80»).
 */
@Composable
fun KvKpi(
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    label: String? = null,
    end: Boolean = false,
    color: Color = Kv.text,
) {
    val t = LocalPravkaType.current
    Column(modifier, horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        Text(
            buildAnnotatedString {
                append(value)
                if (!unit.isNullOrBlank()) {
                    withStyle(SpanStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Kv.faint, letterSpacing = 0.sp)) {
                        append(" ")
                        append(unit)
                    }
                }
            },
            style = t.valueL.copy(fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!label.isNullOrBlank()) {
            Text(
                label,
                style = t.caption,
                color = Kv.faint,
                maxLines = 2,
                textAlign = if (end) TextAlign.End else TextAlign.Start,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * Полоса `.bar`: дорожка `--hover-2`, заливка `--bar-fill` (или [color]),
 * радиус 3. Доля обрезается в 0…1 — перебор показывает вызывающий словом.
 */
@Composable
fun KvBar(fraction: Float, modifier: Modifier = Modifier, color: Color = Kv.barFill, height: Dp = 5.dp) {
    val track = Kv.hover2
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                val r = CornerRadius(3.dp.toPx())
                drawRoundRect(track, cornerRadius = r)
                val f = fraction.coerceIn(0f, 1f)
                if (f > 0f) drawRoundRect(color, size = Size(size.width * f, size.height), cornerRadius = r)
            },
    )
}

/**
 * Строка с полосой `.cr`: название · полоса · значение справа (`--muted`).
 * Ширины колонок — как в макете у каждого экрана свои ([labelWidth],
 * [valueWidth]); тап — [onClick] (раскрыть статью магазинами).
 */
@Composable
fun KvBarRow(
    label: String,
    fraction: Float,
    value: String,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 116.dp,
    valueWidth: Dp = 60.dp,
    color: Color = Kv.barFill,
    valueColor: Color = Kv.muted,
    onClick: (() -> Unit)? = null,
) {
    val t = LocalPravkaType.current
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clip(RoundedCornerShape(KvShape.Tag)).clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = t.body.copy(fontSize = 13.5.sp, lineHeight = 18.sp),
            color = Kv.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(labelWidth),
        )
        KvBar(fraction, Modifier.weight(1f), color = color, height = 4.dp)
        Text(
            value,
            style = t.body.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = valueColor,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(valueWidth),
        )
    }
}

/**
 * Строка списка `.li`: слева время мелко (`--faint`, 38) или значок, в
 * середине название 14,5 и подпись 12,5 `--faint`, справа значение 14.
 * Между строками — линия `--line-soft` ([divider]: у первой её нет).
 * [selected] — `.li.sel`: мягкий акцент (выбранная группа на развороте).
 */
@Composable
fun KvListRow(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    lead: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Kv.icon,
    value: String? = null,
    valueColor: Color = Kv.text,
    valueSmall: Boolean = false,
    titleColor: Color = Kv.text,
    titleWeight: FontWeight = FontWeight.Normal,
    titleLines: Int = 3,
    subLines: Int = 3,
    divider: Boolean = true,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val t = LocalPravkaType.current
    val line = Kv.lineSoft
    Row(
        modifier
            .fillMaxWidth()
            // `.li.sel` веба: подложка выходит на 8 за края строки (margin: 0 -8px), текст стоит на месте.
            .then(if (selected) Modifier.layout { m, c ->
                val out = if (c.hasBoundedWidth) 8.dp.roundToPx() else 0
                val p = if (out == 0) m.measure(c) else m.measure(c.copy(minWidth = c.maxWidth + 2 * out, maxWidth = c.maxWidth + 2 * out))
                layout(if (out == 0) p.width else c.maxWidth, p.height) { p.place(-out, 0) }
            } else Modifier)
            .then(
                if (selected) Modifier.clip(RoundedCornerShape(7.dp)).background(Kv.accentSoft)
                else Modifier.drawBehind { if (divider) drawRect(line, size = Size(size.width, 1.dp.toPx())) }
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(
                start = if (selected) 8.dp else 0.dp,
                end = if (selected) 8.dp else 0.dp,
                top = if (divider || selected) 9.dp else 2.dp,
                bottom = 9.dp,
            ),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (lead != null) {
            Text(
                lead,
                style = t.caption,
                color = Kv.faint,
                maxLines = 1,
                modifier = Modifier.width(38.dp).padding(top = 2.dp),
            )
        }
        if (icon != null) {
            Icon(icon, null, tint = if (selected) Kv.accent else iconTint, modifier = Modifier.padding(top = 1.dp).size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = t.body.copy(fontSize = 14.5.sp, lineHeight = 19.5.sp, fontWeight = titleWeight),
                color = if (selected) Kv.accent else titleColor,
                maxLines = titleLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (!sub.isNullOrBlank()) {
                Text(sub, style = t.meta, color = Kv.faint, modifier = Modifier.padding(top = 2.dp), maxLines = subLines, overflow = TextOverflow.Ellipsis)
            }
            below?.invoke(this)
        }
        if (!value.isNullOrBlank()) {
            Text(
                value,
                style = t.body.copy(fontSize = if (valueSmall) 13.sp else 14.sp, lineHeight = 19.sp),
                color = valueColor,
                maxLines = 1,
                textAlign = TextAlign.End,
            )
        }
        trailing?.invoke(this)
    }
}

/** Тон подсказки и статуса: обычный, «на заметку», «сбой», Claude. */
enum class KvTone { PLAIN, WARN, BAD, CLAUDE, INFO, OK }

/**
 * Подсказка `.callout`: вторая панель с мягкой линией, радиус 8, текст 13,5;
 * «на заметку» ([KvTone.WARN]) — тёплая подложка и рамка `--warn`, значок
 * тем же цветом. Справа — действие словом ([action], `.lnk`), под текстом —
 * ряд кнопок ([buttons]).
 */
@Composable
fun KvCallout(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: KvTone = KvTone.PLAIN,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    buttons: (@Composable RowScope.() -> Unit)? = null,
) {
    val t = LocalPravkaType.current
    val shape = RoundedCornerShape(KvShape.Control)
    val (bg, rim, ink) = when (tone) {
        KvTone.WARN -> Triple(Kv.warn.copy(alpha = 0.08f).compositeOver(Kv.panel), Kv.warn.copy(alpha = 0.35f).compositeOver(Kv.line), Kv.warn)
        KvTone.BAD -> Triple(Kv.bad.copy(alpha = 0.07f).compositeOver(Kv.panel), Kv.bad.copy(alpha = 0.35f).compositeOver(Kv.line), Kv.bad)
        KvTone.CLAUDE -> Triple(Kv.claude.copy(alpha = 0.07f).compositeOver(Kv.panel), Kv.claude.copy(alpha = 0.30f).compositeOver(Kv.line), Kv.claude)
        else -> Triple(Kv.panel2, Kv.lineSoft, Kv.icon)
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(1.dp, rim, shape)
            .then(if (onAction != null && buttons == null) Modifier.clickable(onClick = onAction) else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = ink, modifier = Modifier.padding(top = 2.dp).size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = t.body.copy(fontSize = 13.5.sp, lineHeight = 19.sp), color = Kv.text)
            if (buttons != null) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = buttons,
                )
            }
        }
        if (action != null) KvLink(action, onAction)
    }
}

/** Действие словом `.lnk` / `.sec .r`: 13/500 акцентом («Все», «Открыть»). */
@Composable
fun KvLink(text: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val t = LocalPravkaType.current
    Text(
        text,
        style = t.label.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        color = Kv.accent,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(KvShape.Tag))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/**
 * Статус словом `.pill`: 12,5 `--faint`; «на заметку» и «сбой» — своим цветом
 * и 600 (единственные два места, где статус красится), [KvTone.INFO] — акцентом.
 */
@Composable
fun KvPill(text: String, tone: KvTone = KvTone.PLAIN, modifier: Modifier = Modifier) {
    val t = LocalPravkaType.current
    val (color, weight) = when (tone) {
        KvTone.WARN -> Kv.warn to FontWeight.SemiBold
        KvTone.BAD -> Kv.bad to FontWeight.SemiBold
        KvTone.INFO -> Kv.accent to FontWeight.Medium
        KvTone.OK -> Kv.ok to FontWeight.Medium
        else -> Kv.faint to FontWeight.Normal
    }
    Text(text, style = t.meta.copy(fontWeight = weight), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/**
 * Вкладки `.tabs` — разделы экрана, как в вебе сервиса: слово 14/500
 * `--muted`, выбранное — цветом текста 600 и чертой акцента 2 снизу; под всем
 * рядом — линия `--line`. Не помещаются — листаются вбок.
 */
@Composable
fun KvTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalPravkaType.current
    val line = Kv.line
    val accent = Kv.accent
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind { drawRect(line, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx())) }
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val (word, count) = splitCount(label)
            Row(
                Modifier
                    .heightIn(min = 40.dp)
                    .clickable(role = Role.Tab, onClick = { onSelect(i) })
                    .drawBehind {
                        if (on) drawRect(accent, topLeft = Offset(0f, size.height - 2.dp.toPx()), size = Size(size.width, 2.dp.toPx()))
                    }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    word,
                    style = t.body.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (on) Kv.text else Kv.muted,
                    maxLines = 1,
                )
                if (count != null) Counter(count, hot = on)
            }
        }
    }
}

/**
 * Сегмент `.seg` в шапке и в заголовке плашки: панель с линией, радиус 9,
 * поле 2; выбранное — мягкий акцент и слово акцентом 600, остальное — 13/500
 * `--muted`. Компактный — встаёт в строку шапки рядом со значками (у
 * `Segmented` из `Parts.kt` каждый сегмент — своя кнопка во всю ширину ряда).
 */
@Composable
fun KvSeg(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalPravkaType.current
    val outer = RoundedCornerShape(9.dp)
    val inner = RoundedCornerShape(7.dp)
    Row(
        modifier
            .clip(outer)
            .background(Kv.panel)
            .border(1.dp, Kv.line, outer)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Text(
                label,
                style = t.label.copy(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                color = if (on) Kv.accent else Kv.muted,
                maxLines = 1,
                modifier = Modifier
                    .clip(inner)
                    .then(if (on) Modifier.background(Kv.accentSoft) else Modifier)
                    .clickable(role = Role.Tab, onClick = { onSelect(i) })
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/**
 * Малая кнопка `.btn.sm`: 13/500, прямоугольник 7. [soft] — `.btn.soft`
 * (мягкий акцент и слово акцентом 600), [primary] — `.btn.p`; иначе панель с
 * линией. Высота 32 — палец попадает, а ряд из трёх кнопок не толще макета.
 */
@Composable
fun KvSmallButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    soft: Boolean = false,
    primary: Boolean = false,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val t = LocalPravkaType.current
    val shape = RoundedCornerShape(7.dp)
    val ink = when {
        primary -> Kv.accentInk
        soft -> Kv.accent
        else -> Kv.text
    }
    Row(
        modifier
            .height(32.dp)
            .dim(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(
                when {
                    primary -> Kv.accent
                    soft -> Kv.accentSoft
                    else -> Kv.panel
                }
            )
            .then(if (primary || soft) Modifier else Modifier.border(1.dp, Kv.line, shape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (primary || soft) ink else Kv.icon, modifier = Modifier.size(15.dp))
        Text(
            text,
            style = t.label.copy(fontSize = 13.sp, fontWeight = if (primary || soft) FontWeight.SemiBold else FontWeight.Medium),
            color = ink,
            maxLines = 1,
        )
    }
}

/** Столбик `.cols-ch`: высота доли, подпись под ним; [accent] — сегодняшний (акцентом). */
class KvColumn(val value: Float, val label: String, val accent: Boolean = false)

/**
 * Столбики `.cols-ch` (нагрузка по дням, ккал по дням): высота 84 вместе с
 * подписями, столбик `--bar-fill` на .85 и скругление 3 сверху; выделенный —
 * акцентом. Пустой день — черта 2 dp: видно, что день был, а тренировки нет.
 * [target] — пунктир цели (ккал в день), в долях той же шкалы.
 */
@Composable
fun KvColumns(
    columns: List<KvColumn>,
    modifier: Modifier = Modifier,
    height: Dp = 84.dp,
    target: Float? = null,
    onClick: ((Int) -> Unit)? = null,
) {
    if (columns.isEmpty()) return
    val t = LocalPravkaType.current
    val top = maxOf(columns.maxOf { it.value }, target ?: 0f).coerceAtLeast(0.0001f)
    val fill = Kv.barFill
    val accent = Kv.accent
    val dash = Kv.faint
    Box(modifier.fillMaxWidth().height(height)) {
        Row(Modifier.fillMaxWidth().fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            columns.forEachIndexed { i, c ->
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(if (onClick != null) Modifier.clickable { onClick(i) } else Modifier),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .drawBehind {
                                val h = if (c.value <= 0f) 2.dp.toPx() else (size.height * c.value / top).coerceAtLeast(2.dp.toPx())
                                val r = 3.dp.toPx()
                                val color = if (c.accent) accent else fill.copy(alpha = 0.85f)
                                // Скругление только сверху (`border-radius: 3px 3px 0 0`): столбик стоит на земле.
                                // Одной фигурой, ниже края обрезанной, — две наложенные полупрозрачные давали шов.
                                clipRect(0f, 0f, size.width, size.height) {
                                    drawRoundRect(color, topLeft = Offset(0f, size.height - h), size = Size(size.width, h + r), cornerRadius = CornerRadius(r, r))
                                }
                            },
                    )
                    Text(c.label, style = t.caption.copy(fontSize = 11.sp, lineHeight = 13.sp), color = if (c.accent) Kv.text else Kv.faint, maxLines = 1)
                }
            }
        }
        if (target != null && target > 0f) {
            // Цель — пунктиром поперёк, над подписями (они — 13 + 4 снизу).
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(bottom = 17.dp)
                    .drawBehind {
                        val y = size.height * (1f - target / top)
                        val step = 4.dp.toPx()
                        var x = 0f
                        while (x < size.width) {
                            drawLine(dash, Offset(x, y), Offset((x + step).coerceAtMost(size.width), y), strokeWidth = 1.dp.toPx())
                            x += step * 2
                        }
                    },
            )
        }
    }
}

/**
 * Строка настройки `.set`: значок 18 `--icon`, название 14,5 и под ним
 * значение 12,5 `--faint` в одну строку, справа [value] или шеврон. Тап —
 * открыть группу.
 */
@Composable
fun KvSetRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    sub: String? = null,
    value: String? = null,
    valueColor: Color = Kv.muted,
    divider: Boolean = true,
    chevron: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val t = LocalPravkaType.current
    val line = Kv.lineSoft
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind { if (divider) drawRect(line, size = Size(size.width, 1.dp.toPx())) }
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics { contentDescription = title }
            .padding(top = if (divider) 10.dp else 2.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = Kv.icon, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = t.body.copy(fontSize = 14.5.sp, lineHeight = 19.sp), color = Kv.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrBlank()) {
                Text(sub, style = t.meta, color = Kv.faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
            }
        }
        if (!value.isNullOrBlank()) {
            Text(value, style = t.body.copy(fontSize = 13.5.sp), color = valueColor, maxLines = 1)
        }
        trailing?.invoke(this)
        if (chevron && onClick != null) Icon(Glyphs.Forward, null, tint = Kv.icon, modifier = Modifier.size(16.dp))
    }
}

/**
 * Ряд крупных чисел `.kpis`: колонки поровну, шаг 8. Пустой список — ничего:
 * число без данных не рисуется (числа, которого нет, не произносим).
 */
@Composable
fun KvKpiRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    CompositionLocalProvider(LocalContentColor provides Kv.text) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top, content = content)
    }
}

/** Мелкая строка под числами (`.xs.faint`): «вес 72,5 кг (10.10) · шагов 4 000». */
@Composable
fun KvNote(text: String, modifier: Modifier = Modifier, color: Color = Kv.faint) {
    Text(text, style = LocalPravkaType.current.caption, color = color, modifier = modifier)
}

/** Пробел между блоками внутри плашки — 8, как `margin-top: 8px` холста. */
@Composable
fun KvGap(h: Dp = 8.dp) = Spacer(Modifier.height(h))
