package ru.zf.pravka.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ru.zf.pravka.core.PillLook
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
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.ui.graphics.compositeOver
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
    // Правка 4.0: краска — из цветов режима (`ui/Tokens.kt`). `primary` —
    // светлый tint, а не сама кнопка: старые экраны красят `primary` текст и
    // значки, и тёмно-синий #2A5D82 на ночи не читался бы (расхождение с
    // DESIGN §14 записано в отчёте); сама кнопка — `primaryContainer`.
    val m = Modes.of(this)
    return base.copy(
        primary = m.tint,
        onPrimary = m.ink,
        primaryContainer = m.key,
        onPrimaryContainer = m.value,
        secondary = m.label,
        onSurfaceVariant = if (this == ModeDecor.TODAY || this == ModeDecor.SERVICE) base.onSurfaceVariant else m.label,
        outlineVariant = m.tint.copy(alpha = 0.18f).compositeOver(base.background),
    )
}

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

/** Значок в круге: tint @ .14 с рамкой tint @ .28 (DESIGN §11.7 IconLabel). */
@Composable
fun IconBadge(
    icon: ImageVector,
    size: Dp = 40.dp,
    tint: Color = LocalMode.current.value,
    container: Color = LocalMode.current.tint.copy(alpha = 0.14f),
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .border(1.dp, LocalMode.current.tint.copy(alpha = 0.28f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * Кнопка. Главная ([primary]) — одна на плашку или окно: клавиша-капсула в
 * цвете кнопки режима, справа (DESIGN §11.7 PrimaryKey); остальные —
 * капсула с тонкой рамкой tint и словом в подписи режима.
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
    if (primary) PrimaryKey(text, onClick, modifier, icon = icon, enabled = enabled, height = 40.dp)
    else GhostKey(text, onClick, modifier, icon = icon, enabled = enabled)
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
        colors = ButtonDefaults.textButtonColors(contentColor = color ?: LocalMode.current.label),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Круглая кнопка-значок (микрофон в строке, «удалить» внизу окна): круг
 * tint @ .14 с рамкой tint @ .28, как значки листа (DESIGN §11.7 IconLabel).
 * [active] — клавиша в цвете кнопки режима: так горит микрофон, пока слушает.
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
    val mode = LocalMode.current
    if (active) {
        Key(icon, description, onClick, modifier, size = size, enabled = enabled)
        return
    }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(mode.tint.copy(alpha = 0.10f))
            .border(1.dp, mode.tint.copy(alpha = 0.28f), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = (tint ?: mode.value).copy(alpha = if (enabled) 1f else 0.35f),
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
    tint: Color = LocalMode.current.label,
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
 * · Спросить · Видео»). Правка 4.0: это IconLabel (DESIGN §11.7) — значок в
 * круге 48 и подпись 12.5 под ним.
 */
@Composable
fun RowScope.IconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) = IconLabel(icon, label, onClick, enabled, active, onLongClick)

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
    // Правка 4.0: чип — сегмент-капсула (DESIGN §11.7): выбранный — маленькая
    // клавиша в цвете кнопки режима, остальные — подпись с тонкой рамкой.
    // «warn» силой, а не цветом: в режиме нет чужих красок.
    val (text, count) = splitCount(label)
    Segment(text, selected = selected, onClick = onClick, count = count, icon = icon, enabled = enabled, height = 34.dp)
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
fun Segments(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) =
    Segmented(options, selected, onSelect, modifier)

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
            Text(title, style = LocalPravkaType.current.bodyL, color = Ink.Text)
            if (!hint.isNullOrBlank()) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalMode.current.meta,
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
            Icon(Glyphs.Forward, contentDescription = null, tint = LocalMode.current.label, modifier = Modifier.size(18.dp))
        }
    }
}

/** Тонкая линия между строками внутри плашки — tint @ .10 (DESIGN, `border-top` в макетах). */
@Composable
fun RowRule() = Hairline()

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
                style = LocalPravkaType.current.bodyL,
                color = Ink.Text.copy(alpha = if (enabled) 1f else 0.45f),
            )
            if (!hint.isNullOrBlank()) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = LocalMode.current.meta)
            }
        }
        if (!info.isNullOrBlank()) InfoButton(title, info)
        Spacer(Modifier.width(6.dp))
        Toggle(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
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
                    true -> LocalMode.current.tint
                    false -> Ink.Warn
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
fun InfoButton(title: String, text: String, size: Dp = 32.dp) = InfoDot(title, text)

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
    // Правка 4.0 (лист записи, `screens/13`): значок — в кольце, заголовок —
    // Literata 22 цветом заголовков режима, подпись — второй строкой.
    val mode = LocalMode.current
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconBadge(icon, size = 36.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = LocalPravkaType.current.titleS, color = mode.title)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = mode.label,
                    maxLines = 2,
                )
            }
        }
        actions()
        if (onClose != null) GlyphButton(Glyphs.Close, "закрыть", onClick = onClose, tint = Ink.Text, size = 44.dp)
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
    // Правка 4.0 (DESIGN §11.7 BottomSheet, `screens/13`): лист — стекло
    // режима, верх 28, ручка 32×4 кремом @ .3, затемнение под ним .62.
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val mode = LocalMode.current
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    // Высокий лист не заходит под строку состояния (баг №1, 07.10.2026: лист
    // Дел «вылезает слишком высоко… перекрывается статусной строкой»): окно
    // листа — во весь экран, своих отступов сверху у него нет (`contentWindowInsets`
    // ноль — иначе полоса под клавиатурой). Потолок — высота окна приложения
    // без строки состояния, ручки листа и зазора 16 dp; меряется снаружи
    // листа, в окне приложения.
    val maxHeight = sheetMaxHeight()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = shape,
        containerColor = mode.ink.copy(alpha = 0.97f).compositeOver(Ink.Bg),
        contentColor = Ink.Text,
        scrimColor = Color(0xFF080706).copy(alpha = 0.62f),
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 8.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Ink.Cream.copy(alpha = 0.30f))
            )
        },
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .sheetGlass(mode)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
        ) {
            SheetHeader(title, onClose = onDismiss, icon = icon, subtitle = subtitle, actions = actions)
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = if (footer == null) 22.dp else 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
            if (footer != null) {
                Hairline(Modifier.padding(horizontal = 20.dp), alpha = 0.14f)
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = footer,
                )
            }
        }
    }
}

/** Потолок тела листа: окно приложения без строки состояния, ручки (22 dp) и зазора 16 dp. */
@Composable
internal fun sheetMaxHeight(): androidx.compose.ui.unit.Dp {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val window = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height
    val top = WindowInsets.statusBars.getTop(density)
    if (window <= 0) return androidx.compose.ui.unit.Dp.Infinity
    return with(density) { (window - top).toDp() } - 38.dp
}

/** Налёт и блик листа (`screens/13`): key .16 → .06 сверху вниз и светлый tint у верхней кромки. */
private fun Modifier.sheetGlass(mode: ModeColors): Modifier = this.drawBehind {
    drawRect(Brush.verticalGradient(0f to mode.key.copy(alpha = if (mode === Modes.Today) 0f else 0.16f), 1f to mode.key.copy(alpha = if (mode === Modes.Today) 0f else 0.06f)))
    drawRect(
        Brush.verticalGradient(
            0f to mode.glass.sheenColor.copy(alpha = 0.14f),
            0.26f to mode.glass.sheenColor.copy(alpha = 0.03f),
            1f to Color.Transparent,
        )
    )
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
 * Строка ввода режима — Правка 4.0 (07.10.2026): это строка «сказать»
 * (`ui/SayBar.kt`, DESIGN §11.4). Параметры прежней пилюли сохранены, чтобы
 * вкладки переехали вниз без переделки разбора: [extras] и [leading] —
 * значки перед клавишей (камера, галерея, штрихкод, вставить).
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
    sendIcon: ImageVector = Glyphs.ArrowUp,
    extras: (@Composable RowScope.() -> Unit)? = null,
    busy: Boolean = false,
    leading: (@Composable RowScope.() -> Unit)? = null,
    busyLabel: String = "Разбираю",
) {
    val icons: (@Composable RowScope.() -> Unit)? = when {
        leading != null && extras != null -> ({ leading(); extras() })
        else -> leading ?: extras
    }
    SayBar(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        onSend = onSend,
        modifier = modifier,
        onMic = onMic,
        listening = listening,
        sendEnabled = sendEnabled,
        enabled = enabled,
        maxLines = maxLines,
        busy = busy,
        busyLabel = busyLabel,
        trailing = icons,
        sendIcon = sendIcon,
    )
}

/**
 * Значок внутри строки «сказать» — для [VoiceInput.leading]: снимок,
 * галерея, штрихкод, «из буфера».
 */
@Composable
fun PillAction(icon: ImageVector, description: String, onClick: () -> Unit, enabled: Boolean = true) =
    SayIcon(icon, description, onClick, enabled)

/**
 * Место строки «сказать». До 4.0 пилюля стояла первой строкой вкладки; теперь
 * строка внизу экрана (DESIGN §11.4): вкладки, которые ещё не переехали,
 * получают её отступы.
 */
@Composable
fun TopPill(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp)) { content() }
}

/** Знак режима — тот же, что у его кнопки внизу и на стекле. */
internal fun decorGlyph(decor: ModeDecor): ImageVector = when (decor) {
    ModeDecor.PRAVKA, ModeDecor.SERVICE, ModeDecor.TODAY -> Glyphs.Pravka
    ModeDecor.ZASECHKA -> Glyphs.Zasechka
    ModeDecor.DELA -> Glyphs.Delo
    ModeDecor.SPORT -> Glyphs.Sport
    ModeDecor.FOOD -> Glyphs.Food
    ModeDecor.MONEY -> Glyphs.Money
}

/**
 * «Причёсываю · ещё 6 с» — Claude работает, и видно сколько ждать (версия 3):
 * искры краской режима, слово, секунды из того же обещания, что на кнопке.
 * Секунды вышли — остаётся слово с искрами: замершее «0,0» читалось бы
 * «повисло». Пока строка на экране, свет вкладки ярче.
 */
@Composable
fun ThinkingLine(label: String, modifier: Modifier = Modifier) {
    // Правка 4.0 (DESIGN §4.4): «Claude думает» — заливка слева направо
    // tint @ .25 по строке, слово и секунды до ответа; без пульса.
    GlowBusy(true)
    val mode = LocalMode.current
    val t = LocalPravkaType.current
    val seconds by LocalClaudeSeconds.current
    val progress by LocalClaudeProgress.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .clip(shape)
            .drawBehind {
                val f = (progress ?: 1f).coerceIn(0f, 1f)
                drawRect(mode.tint.copy(alpha = 0.25f), size = androidx.compose.ui.geometry.Size(size.width * f, size.height))
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Glyphs.Spark, contentDescription = null, tint = mode.label, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = t.body, color = Ink.Text, fontWeight = FontWeight.Medium)
        val s = seconds
        if (s != null) {
            Text(" · ещё $s с", style = t.meta, color = mode.meta)
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
    // Правка 4.0 (DESIGN §11.7 DayNavigator): «‹ Сегодня / понедельник, 5
    // октября ›»; вперёд некуда — «›» тоном `textDisabled`.
    val mode = LocalMode.current
    val t = LocalPravkaType.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphButton(Glyphs.Back, "день назад", onClick = onPrev, tint = mode.label, size = 44.dp)
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .then(if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier)
                .padding(vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = t.valueS, color = Ink.Text, maxLines = 1)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = t.label, color = mode.label, maxLines = 1)
            }
        }
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(enabled = onNext != null, role = Role.Button, onClickLabel = "день вперёд") { onNext?.invoke() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Glyphs.Forward, "день вперёд", tint = if (onNext != null) mode.label else Ink.TextDisabled, modifier = Modifier.size(22.dp))
        }
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
            Text(title, style = LocalPravkaType.current.bodyL, color = Ink.Text, modifier = Modifier.weight(1f))
            Text(
                valueText,
                style = LocalPravkaType.current.valueS,
                color = LocalMode.current.value,
            )
            if (!info.isNullOrBlank()) InfoButton(title, info)
        }
        Slider4(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
        )
    }
}
