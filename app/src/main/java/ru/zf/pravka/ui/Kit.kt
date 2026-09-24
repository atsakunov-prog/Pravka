package ru.zf.pravka.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Набор деталей Правки — «второе издание» (24.09.2026).
//
// Владелец, увидев разбор (две системы плашек, 26 окон Material, 131
// текстовая кнопка, 277 пояснений): «сделай всё как считаешь нужным». Урок
// Слушалки, где такой же набор (`Paper.kt`) и собрал читалку в одно целое:
// приложение расползается не от плохих деталей, а от того, что каждая
// вкладка заводит свои. Поэтому здесь — ВСЁ, из чего собираются экраны:
// окно-лист, шапка окна, строка-действие, тумблер, чип, кнопки, ряд значков,
// строка ввода, «i» с пояснением, навигатор дня, строка-сводка. Голый
// Material в экранах больше не появляется; новая деталь — сначала сюда.
//
// Плашка (`PaperCard`), подпись раздела и фаска живут рядом, в `Blocks.kt`;
// шапка вкладки — в `Frame.kt`; значки — `Glyphs.kt`.

// ---------------------------------------------------------------------------
// Краска режима
// ---------------------------------------------------------------------------

/**
 * Краска вкладки — от её кнопки на стекле: «З» янтарная, «Д» синяя, «Т»
 * зелёная, «Е» оливковая, «₽» фиолетовая. Раньше внутри приложения всё было
 * оранжевым, и стекло с приложением жили порознь; теперь по цвету видно, где
 * ты, раньше, чем прочитал название.
 *
 * Красится ровно то, что Material берёт из `primary`: полоска под названием,
 * значок в шапке, подписи разделов, главная кнопка, тумблеры, выбранный чип.
 * Фон, плашки и текст не трогаются — ночь остаётся ночью. Правка и
 * служебные экраны остаются в родном оранжевом.
 */
internal fun ModeDecor.tint(base: ColorScheme): ColorScheme {
    val dark = base.background.luminance() < 0.5f
    val ink = when (this) {
        ModeDecor.ZASECHKA -> if (dark) ModeInk(0xFFF7A23A, 0xFF3A2205, 0xFF5E3A0C, 0xFFFCDCA8)
        else ModeInk(0xFFB45309, 0xFFFFF8F0, 0xFFFCE7C4, 0xFF7A3A06)
        ModeDecor.DELA -> if (dark) ModeInk(0xFF86B6DC, 0xFF0E2436, 0xFF1E3E57, 0xFFD2E6F6)
        else ModeInk(0xFF2A5D82, 0xFFF4F8FC, 0xFFD6E6F3, 0xFF173A55)
        ModeDecor.SPORT -> if (dark) ModeInk(0xFF78C49C, 0xFF0C2A1C, 0xFF1D4633, 0xFFCBEBD9)
        else ModeInk(0xFF2F6B4F, 0xFFF3FAF6, 0xFFD3EBDD, 0xFF173D2B)
        ModeDecor.FOOD -> if (dark) ModeInk(0xFFB4C872, 0xFF232C07, 0xFF3D4718, 0xFFE6EFC6)
        else ModeInk(0xFF5E7A1F, 0xFFF8FBEE, 0xFFE7EFCC, 0xFF34440E)
        ModeDecor.MONEY -> if (dark) ModeInk(0xFFAE9CE0, 0xFF221845, 0xFF3D3263, 0xFFE4DCFA)
        else ModeInk(0xFF5B4A8C, 0xFFF7F4FC, 0xFFE4DDF5, 0xFF33285A)
        else -> return base
    }
    return base.copy(
        primary = Color(ink.primary),
        onPrimary = Color(ink.onPrimary),
        primaryContainer = Color(ink.container),
        onPrimaryContainer = Color(ink.onContainer),
    )
}

private class ModeInk(val primary: Long, val onPrimary: Long, val container: Long, val onContainer: Long)

// ---------------------------------------------------------------------------
// Отступы экрана
// ---------------------------------------------------------------------------

/**
 * Одни поля и один шаг на всех экранах. До 24.09 полей было четыре вида
 * (16/16/8/32, 16/16/16/32, 16 со всех сторон, 20 со всех сторон), а шагов
 * между плашками — пять: соседние вкладки выглядели сделанными разными руками.
 */
object ScreenPad {
    val Padding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp)
    val Gap: Dp = 12.dp
}

// ---------------------------------------------------------------------------
// Значок в круге, кнопки
// ---------------------------------------------------------------------------

/** Значок в круге краски режима — шапка окна, строка настроек. */
@Composable
fun IconBadge(
    icon: ImageVector,
    size: Dp = 40.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
    container: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
) {
    Box(
        Modifier.size(size).clip(CircleShape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * Кнопка. Главная ([primary]) — одна на плашку или окно, залита краской
 * режима и стоит справа; остальные — контуром. Значок слева, если есть.
 */
@Composable
fun PaperButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val inner: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val padding = PaddingValues(start = if (icon != null) 14.dp else 18.dp, end = 18.dp, top = 8.dp, bottom = 8.dp)
    if (primary) {
        Button(onClick = onClick, modifier = modifier, enabled = enabled, contentPadding = padding, content = inner)
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            contentPadding = padding,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (enabled) 1f else 0.4f),
            ),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            content = inner,
        )
    }
}

/** Тихое действие словом: «Очистить», «Закрыть». Краской режима, без рамки. */
@Composable
fun PaperTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    color: Color? = null,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = if (color != null) ButtonDefaults.textButtonColors(contentColor = color) else ButtonDefaults.textButtonColors(),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Круглая кнопка-значок с кромкой (микрофон у строки ввода, «удалить» в
 * низу окна). [active] — залита краской: так горит микрофон, пока слушает.
 */
@Composable
fun PaperIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 42.dp,
    tint: Color? = null,
) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) c.primary else Color.Transparent)
            .border(1.dp, if (active) Color.Transparent else c.outlineVariant, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = when {
                active -> c.onPrimary
                tint != null -> tint
                else -> c.onSurface
            }.copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(size * 0.46f),
        )
    }
}

/** Значок в строке без кромки — маленькие действия в ряду записи или шапке плашки. */
@Composable
fun GlyphButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
    size: Dp = 40.dp,
) {
    IconButton(onClick = onClick, modifier = modifier.size(size), enabled = enabled) {
        Icon(
            icon,
            contentDescription = description,
            tint = tint.copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/**
 * Действие значком с подписью снизу — ряд под плашкой («Таймер · Как делать
 * · Спросить · Видео»). Подпись оставлена нарочно, как в нижней плашке
 * читалки Слушалки: без слов «Как делать» и «Спросить» не отличить.
 */
@Composable
fun RowScope.IconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) c.primary.copy(alpha = 0.12f) else Color.Transparent)
            .then(
                if (onLongClick != null) Modifier.combinedClickableCompat(enabled, onClick, onLongClick)
                else Modifier.clickable(enabled = enabled, onClick = onClick)
            )
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = c.primary.copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            color = c.onSurface.copy(alpha = if (enabled) 1f else 0.4f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(enabled: Boolean, onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)

/** Ряд [IconAction] во всю ширину плашки. */
@Composable
fun IconActionRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

// ---------------------------------------------------------------------------
// Чипы
// ---------------------------------------------------------------------------

/**
 * Чип выбора: выбранный залит краской режима, остальные — кромкой. Один на
 * всё приложение вместо `FilterChip` с галочкой: галочка у выбранного
 * дублировала заливку и сдвигала подпись.
 */
@Composable
fun PaperChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    warn: Boolean = false,
) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    val fill = when {
        selected && warn -> c.error.copy(alpha = 0.18f)
        selected -> c.primary.copy(alpha = 0.18f)
        else -> Color.Transparent
    }
    val ink = when {
        !enabled -> c.onSurface.copy(alpha = 0.38f)
        selected && warn -> c.error
        selected -> c.primary
        else -> c.onSurface
    }
    Row(
        Modifier
            .defaultMinSize(minHeight = 34.dp)
            .clip(shape)
            .background(fill)
            .then(if (selected) Modifier else Modifier.border(1.dp, c.outlineVariant, shape))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = ink,
            maxLines = 1,
        )
    }
}

/** Ряд чипов с прокруткой вбок — выбор из нескольких. */
@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Части вкладки чипами сверху: «Сегодня · Путь · Журнал». Длинная вкладка
 * делится на то, что смотришь сейчас, и то, что открываешь иногда, — а не
 * прокручивается пятнадцатью плашками подряд.
 */
@Composable
fun Segments(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    ChipRow(modifier) {
        options.forEachIndexed { i, label ->
            PaperChip(label, selected = i == selected, onClick = { onSelect(i) })
        }
    }
}

// ---------------------------------------------------------------------------
// Строки: действие, тумблер, сводка
// ---------------------------------------------------------------------------

/**
 * Строка, которая ведёт дальше: значок в круге, название, подсказка в одну
 * строку, справа состояние и шеврон. Меню настроек и «Ещё» — из них.
 */
@Composable
fun PaperRow(
    title: String,
    onClick: (() -> Unit)?,
    icon: ImageVector? = null,
    hint: String? = null,
    status: String? = null,
    statusColor: Color? = null,
    badgeTint: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            val tint = badgeTint ?: c.onSurface
            IconBadge(
                icon,
                size = 36.dp,
                tint = tint,
                container = if (badgeTint != null) badgeTint.copy(alpha = 0.16f) else c.onSurface.copy(alpha = 0.07f),
            )
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!hint.isNullOrBlank()) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!status.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                status,
                style = MaterialTheme.typography.labelMedium,
                color = statusColor ?: c.onSurfaceVariant,
                maxLines = 1,
            )
        }
        trailing?.let { Spacer(Modifier.width(6.dp)); it() }
        if (onClick != null) {
            Spacer(Modifier.width(4.dp))
            Icon(Glyphs.Forward, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

/** Тонкая линия между строками внутри плашки. */
@Composable
fun RowRule() {
    HorizontalDivider(thickness = 0.7.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}

/**
 * Тумблер строкой: название, короткая подсказка под ним и «i» с длинным
 * пояснением. Тап по всей строке переключает — попадать в сам Switch
 * пальцем на ходу трудно.
 */
@Composable
fun PaperToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    hint: String? = null,
    info: String? = null,
    enabled: Boolean = true,
) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = c.onSurface.copy(alpha = if (enabled) 1f else 0.45f),
            )
            if (!hint.isNullOrBlank()) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
        if (!info.isNullOrBlank()) InfoButton(title, info)
        Spacer(Modifier.width(6.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = c.primary, checkedThumbColor = c.onPrimary),
        )
    }
}

/**
 * Строка-сводка: рукоятки свёрнуты в одну строку «Опус 5.5 · medium», тап
 * раскрывает их под ней. [changed] — выбор владельца, отличный от
 * заводского: сводка тогда краской режима, а не серым.
 */
@Composable
fun SummaryLine(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    changed: Boolean = false,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onToggle)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(
                summary,
                style = MaterialTheme.typography.labelMedium,
                color = if (changed) c.primary else c.onSurfaceVariant,
                fontWeight = if (changed) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Glyphs.ChevronDown,
                contentDescription = null,
                tint = c.onSurfaceVariant,
                modifier = Modifier.size(16.dp).rotate(if (expanded) 180f else 0f),
            )
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

/** Точка состояния: зелёная — работает, красная — ошибка, серая — не задано. */
@Composable
fun StatusDot(ok: Boolean?) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(
                when (ok) {
                    true -> MicroOk
                    false -> c.error
                    null -> c.outline
                }
            )
    )
}

// ---------------------------------------------------------------------------
// «i» — пояснение по требованию
// ---------------------------------------------------------------------------

/**
 * «i» в строке заголовка плашки или у тумблера. Пояснения никуда не делись —
 * их просто больше не видно, пока не спросишь: на экране вещь, а не
 * инструкция к ней (договорённость 15.09 «пояснений нет», которую 277
 * абзацев-подсказок успели размыть).
 */
@Composable
fun InfoButton(title: String, text: String, size: Dp = 32.dp) {
    var open by remember { mutableStateOf(false) }
    GlyphButton(Glyphs.Info, "пояснение", onClick = { open = true }, size = size)
    if (open) {
        PaperSheet(onDismiss = { open = false }, title = title.replaceFirstChar { it.uppercase() }, icon = Glyphs.Info) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---------------------------------------------------------------------------
// Окно-лист
// ---------------------------------------------------------------------------

/**
 * Шапка окна: значок в круге краски режима, заголовок с засечками, подпись
 * под ним, справа действия и крестик. Та же схема, что у окон читалки.
 */
@Composable
fun SheetHeader(
    title: String,
    onClose: (() -> Unit)?,
    icon: ImageVector? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconBadge(icon, size = 40.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        actions()
        if (onClose != null) GlyphButton(Glyphs.Close, "закрыть", onClick = onClose, tint = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * Окно-лист снизу — вместо `AlertDialog`. Шапка, линейка, тело с прокруткой
 * и низ с кнопками: главная одна и справа, куда дотягивается большой палец.
 * Лист на материале плашек: тот же цвет, что у `PaperCard`, и та же фаска.
 *
 * Клавиатура и системная панель поднимают низ листа сами: поля ввода в
 * окнах есть почти везде, и кнопка «Сохранить» под клавиатурой — это окно,
 * которое не закрыть.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperSheet(
    onDismiss: () -> Unit,
    title: String,
    icon: ImageVector? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    footer: (@Composable RowScope.() -> Unit)? = null,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val look = LocalCardLook.current
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = shape,
        containerColor = darkened(c.surfaceContainerLow, look.darken * 0.6f),
        contentColor = c.onSurface,
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 8.dp)
                    .size(width = 38.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(c.onSurface.copy(alpha = 0.22f))
            )
        },
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (look.bevel) Modifier.bevel(shape) else Modifier)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
        ) {
            SheetHeader(title, onClose = onDismiss, icon = icon, subtitle = subtitle, actions = actions)
            HorizontalDivider(thickness = 0.7.dp, color = c.outlineVariant)
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = if (footer == null) 22.dp else 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
            if (footer != null) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = footer,
                )
            }
        }
    }
}

/**
 * Лист на месте `AlertDialog` с тем же разговором: подтверждение и отмена.
 * [confirm] — главная кнопка (справа, залита), [dismiss] — слева от неё
 * контуром, [destructive] — значок корзины у левого края. Нет [confirm] —
 * окно только читают, закрывает крестик.
 */
@Composable
fun PaperAlert(
    onDismiss: () -> Unit,
    title: String,
    icon: ImageVector? = null,
    subtitle: String? = null,
    confirm: SheetAction? = null,
    dismiss: SheetAction? = null,
    destructive: SheetAction? = null,
    extra: SheetAction? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val footer: (@Composable RowScope.() -> Unit)? =
        if (confirm == null && dismiss == null && destructive == null && extra == null) null
        else {
            {
                if (destructive != null) {
                    PaperIconButton(
                        destructive.icon ?: Glyphs.Delete,
                        destructive.text,
                        onClick = destructive.onClick,
                        enabled = destructive.enabled,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                if (extra != null) PaperButton(extra.text, extra.onClick, icon = extra.icon, enabled = extra.enabled)
                Spacer(Modifier.weight(1f))
                if (dismiss != null) PaperButton(dismiss.text, dismiss.onClick, icon = dismiss.icon, enabled = dismiss.enabled)
                if (confirm != null) {
                    PaperButton(confirm.text, confirm.onClick, icon = confirm.icon, primary = true, enabled = confirm.enabled)
                }
            }
        }
    PaperSheet(onDismiss = onDismiss, title = title, icon = icon, subtitle = subtitle, footer = footer, content = content)
}

/** Кнопка низа окна: слово, действие и, если надо, значок. */
class SheetAction(
    val text: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

// ---------------------------------------------------------------------------
// Ввод
// ---------------------------------------------------------------------------

/**
 * Поле формы — со скруглением плашки, а не прямоугольником Material. Подпись
 * остаётся: в окне «Правка записи» без неё не понять, где начало, где конец.
 */
@Composable
fun PaperField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        enabled = enabled,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
    )
}

/**
 * Одна строка ввода на все вкладки: микрофон · поле-пилюля · «отправить».
 * Засечка, Еда, Деньги и «спросить про тренировки» пишут в одно и то же
 * место одинаково: раньше у каждой была своя сборка из поля и кнопок.
 * [extras] — значки перед полем (камера, галерея, штрихкод у Еды).
 */
@Composable
fun VoiceInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    onMic: (() -> Unit)? = null,
    listening: Boolean = false,
    sendEnabled: Boolean = value.isNotBlank(),
    enabled: Boolean = true,
    maxLines: Int = 4,
    sendIcon: ImageVector = Glyphs.Send,
    extras: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (extras != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = extras)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onMic != null) {
                PaperIconButton(Glyphs.Mic, if (listening) "остановить" else "голосом", onClick = onMic, active = listening, enabled = enabled)
                Spacer(Modifier.width(8.dp))
            }
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                enabled = enabled,
                placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                maxLines = maxLines,
                shape = RoundedCornerShape(22.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(imeAction = if (maxLines == 1) ImeAction.Send else ImeAction.Default),
                keyboardActions = KeyboardActions(onSend = { if (sendEnabled) onSend() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = c.onSurface.copy(alpha = 0.07f),
                    unfocusedContainerColor = c.onSurface.copy(alpha = 0.07f),
                    disabledContainerColor = c.onSurface.copy(alpha = 0.04f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (sendEnabled && enabled) c.primary else c.onSurface.copy(alpha = 0.08f))
                    .clickable(enabled = sendEnabled && enabled, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    sendIcon,
                    contentDescription = "отправить",
                    tint = if (sendEnabled && enabled) c.onPrimary else c.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Навигатор дня
// ---------------------------------------------------------------------------

/**
 * Один навигатор дня на Засечку, Еду и Статистику: стрелки по краям,
 * посередине день словом и дата под ним. Раньше их было три разных — с
 * «сегодня» строчной, с «Сегодня» заголовком и с датой дважды.
 * [onNext] = null — вперёд некуда (сегодня), стрелка гаснет.
 */
@Composable
fun DayNav(
    title: String,
    onPrev: () -> Unit,
    onNext: (() -> Unit)?,
    subtitle: String? = null,
    onTitleClick: (() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphButton(Glyphs.Back, "день назад", onClick = onPrev, tint = c.onSurface)
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .then(if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier)
                .padding(vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, maxLines = 1)
            }
        }
        GlyphButton(Glyphs.Forward, "день вперёд", onClick = { onNext?.invoke() }, enabled = onNext != null, tint = c.onSurface)
    }
}

// ---------------------------------------------------------------------------
// Ползунок настроек
// ---------------------------------------------------------------------------

/**
 * Ползунок строкой: название и значение над дорожкой, «i» с пояснением.
 * Пишет в настройки по отпусканию, а не на каждом шаге: DataStore на каждый
 * кадр перетаскивания — это десятки записей файла за секунду.
 */
@Composable
fun PaperSlider(
    title: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    info: String? = null,
    enabled: Boolean = true,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            if (!info.isNullOrBlank()) InfoButton(title, info)
        }
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
        )
    }
}
