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

/**
 * Строка настройки `.set`: значок 18 `--icon`, название 14,5 и под ним
 * значение 12,5 `--faint`, справа [value] или шеврон. Тап — открыть группу.
 * Значение переносится по словам, а не режется «…» (14.10.2026, владелец: «где-то
 * можно переносить по словам»): у «Кто пользуется» «имя · 6 из 6 · имя, род…» на сложенном — на краю.
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
                Text(ru.zf.pravka.core.HeadLine.wrap(sub), style = t.meta, color = Kv.faint, modifier = Modifier.padding(top = 1.dp))
            }
        }
        if (!value.isNullOrBlank()) {
            Text(value, style = t.body.copy(fontSize = 13.5.sp), color = valueColor, maxLines = 1)
        }
        trailing?.invoke(this)
        if (chevron && onClick != null) Icon(Glyphs.Forward, null, tint = Kv.icon, modifier = Modifier.size(16.dp))
    }
}

/** Мелкая строка под числами (`.xs.faint`): «вес 72,5 кг (10.10) · шагов 4 000». */
@Composable
fun KvNote(text: String, modifier: Modifier = Modifier, color: Color = Kv.faint) {
    Text(text, style = LocalPravkaType.current.caption, color = color, modifier = modifier)
}
