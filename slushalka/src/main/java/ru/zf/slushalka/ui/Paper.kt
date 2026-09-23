package ru.zf.slushalka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.data.readerView

/*
 * Бумага для окон поверх читалки.
 *
 * Окна - вопрос, пересказ, справочник, пометки, «Как читать» - раньше были
 * системными: белая простыня Material поверх кремовой страницы, рукоятки
 * вперемешку с ответом. Владелец попросил привести их к языку самой
 * читалки. Здесь этот язык одним набором: цвета - из бумаги, которую выбрал
 * читатель (сепия - и окно сепия, ночь - и окно тёмное), заголовки -
 * книжной антиквой, значки вместо голых слов, карточки с кромкой, как
 * плашки; на e-ink - чёрное, белое и чёткие рамки.
 */

/** Бумага, на которой нарисовано окно: те же цвета, что у страницы. */
val LocalPaper = staticCompositionLocalOf { ReaderPalette(Color(0xFFF4EFE4), Color(0xFF2E2A25), Color(0xFF6E6659)) }

private fun mix(a: Color, b: Color, t: Float) = lerp(a, b, t)

/**
 * Material-схема из бумаги читалки. Главная кнопка - краской по бумаге, без
 * синего: в книге цветных кнопок нет. Тёплый акцент - только для предупреждений
 * вроде снятого барьера спойлеров.
 */
fun paperScheme(p: ReaderPalette, eink: Boolean) = run {
    val dark = p.bg.luminance() < 0.5f
    val k = if (eink) 1.6f else 1f
    val warm = when {
        eink -> p.fg
        dark -> Color(0xFFE2A863)
        else -> Color(0xFFB0712A)
    }
    val error = when {
        eink -> p.fg
        dark -> Color(0xFFE57366)
        else -> Color(0xFFA8261B)
    }
    val base = if (dark) darkColorScheme() else lightColorScheme()
    base.copy(
        primary = p.fg,
        onPrimary = p.bg,
        primaryContainer = mix(p.bg, p.fg, 0.11f * k),
        onPrimaryContainer = p.fg,
        secondary = p.dim,
        onSecondary = p.bg,
        secondaryContainer = mix(p.bg, p.fg, 0.08f * k),
        onSecondaryContainer = p.fg,
        tertiary = warm,
        onTertiary = p.bg,
        tertiaryContainer = mix(p.bg, warm, 0.18f),
        onTertiaryContainer = p.fg,
        background = p.bg,
        onBackground = p.fg,
        surface = p.bg,
        onSurface = p.fg,
        surfaceVariant = mix(p.bg, p.fg, 0.06f * k),
        onSurfaceVariant = p.dim,
        surfaceTint = p.bg,
        surfaceContainerLowest = p.bg,
        surfaceContainerLow = mix(p.bg, p.fg, 0.025f * k),
        surfaceContainer = mix(p.bg, p.fg, 0.04f * k),
        surfaceContainerHigh = mix(p.bg, p.fg, 0.06f * k),
        surfaceContainerHighest = mix(p.bg, p.fg, 0.09f * k),
        outline = if (eink) p.fg else mix(p.bg, p.fg, 0.35f),
        outlineVariant = if (eink) p.fg else mix(p.bg, p.fg, 0.14f),
        error = error,
        onError = p.bg,
        errorContainer = mix(p.bg, error, 0.16f),
        onErrorContainer = p.fg,
        inverseSurface = p.fg,
        inverseOnSurface = p.bg,
    )
}

/** Заголовки - книжной антиквой, как колонтитулы и главы; всё служебное - обычным шрифтом. */
private fun paperType(): Typography {
    val base = Typography()
    val book = fontOf(Settings.FONT_BOOK)
    return base.copy(
        headlineSmall = base.headlineSmall.copy(fontFamily = book, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = book, fontWeight = FontWeight.SemiBold, fontSize = 21.sp),
        titleMedium = base.titleMedium.copy(fontFamily = book, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(lineHeight = 25.sp),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.6.sp),
    )
}

/** Всё внутри рисуется на бумаге читалки: её цвета, её режим e-ink. */
@Composable
fun PaperTheme(app: SlushalkaApp, content: @Composable () -> Unit) {
    val stored by app.state.prefs.collectAsState()
    val palette = readerPalette(stored.readerView().readerTheme, isSystemInDarkTheme())
    val scheme = remember(palette, stored.readerEink) { paperScheme(palette, stored.readerEink) }
    val type = remember { paperType() }
    MaterialTheme(colorScheme = scheme, typography = type, shapes = MaterialTheme.shapes) {
        CompositionLocalProvider(LocalEink provides stored.readerEink, LocalPaper provides palette) {
            content()
        }
    }
}

// --------------------------------------------------------------------- лист

/**
 * Лист снизу на бумаге читалки: ручка, шапка со значком, заголовком и
 * подзаголовком, справа - свои действия и «закрыть». [tall] - во весь рост,
 * для справочника и списков; [scroll] - лист сам прокручивает содержимое
 * (отключается, когда внутри свой список).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperSheet(
    app: SlushalkaApp,
    onClose: () -> Unit,
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    tall: Boolean = false,
    scroll: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    PaperTheme(app) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val eink = LocalEink.current
        ModalBottomSheet(
            onDismissRequest = onClose,
            sheetState = sheet,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            dragHandle = {
                Box(
                    Modifier
                        .padding(top = 10.dp, bottom = 4.dp)
                        .size(width = 38.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (eink) 0.8f else 0.22f)),
                )
            },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (tall) Modifier.fillMaxHeight(0.94f) else Modifier)
                    .imePadding(),
            ) {
                PaperHeader(icon, title, subtitle, actions, onClose)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .then(if (tall) Modifier.weight(1f) else Modifier)
                        .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 20.dp),
                    content = content,
                )
            }
        }
    }
}

/** Шапка окна: значок в круге, заголовок антиквой, строка под ним, действия и «закрыть». */
@Composable
fun PaperHeader(
    icon: ImageVector?,
    title: String,
    subtitle: String?,
    actions: @Composable RowScope.() -> Unit = {},
    onClose: (() -> Unit)?,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                IconBadge(icon)
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
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
            if (onClose != null) PaperIconButton(Icons.Default.Close, "Закрыть", onClick = onClose)
        }
        PaperRule()
    }
}

/**
 * Полноэкранное окно (вопрос, разговор): та же шапка, содержимое и снизу -
 * своя строка ввода, которая поднимается над клавиатурой.
 */
@Composable
fun PaperScreen(
    app: SlushalkaApp,
    icon: ImageVector?,
    title: String,
    subtitle: String?,
    onClose: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    bottom: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    PaperTheme(app) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            PaperHeader(icon, title, subtitle, actions, onClose)
            Column(Modifier.weight(1f).fillMaxWidth(), content = content)
            Column(Modifier.fillMaxWidth(), content = bottom)
        }
    }
}

/** Тонкая линейка - как под колонтитулом страницы. */
@Composable
fun PaperRule(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, thickness = if (LocalEink.current) 1.dp else 0.7.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

// ----------------------------------------------------------------- детали

/** Значок в тонком круге - знак окна в шапке. */
@Composable
fun IconBadge(icon: ImageVector, size: androidx.compose.ui.unit.Dp = 40.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(size * 0.52f))
    }
}

/** Круглая кнопка-значок; [active] - включённое состояние (микрофон слушает). */
@Composable
fun PaperIconButton(
    icon: ImageVector,
    description: String,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) c.primary else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = when {
                active -> c.onPrimary
                enabled -> c.onSurface
                else -> c.onSurface.copy(alpha = 0.3f)
            },
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Подпись раздела - мелко, вразрядку, как колонтитул. */
@Composable
fun PaperLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

/** Пояснение мелко и тускло - под рукояткой, а не простынёй над ней. */
@Composable
fun PaperNote(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color,
        modifier = modifier,
    )
}

/** Карточка с кромкой - как плашки читалки, только без тени: она лежит на той же бумаге. */
@Composable
fun PaperCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    highlight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val eink = LocalEink.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (highlight) c.primaryContainer else c.surfaceContainerHigh)
            .border(if (eink) 1.dp else 0.5.dp, c.outlineVariant, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        content = content,
    )
}

/** Цитата из книги: курсив шрифтом читалки и линейка слева - как в книжной цитате. */
@Composable
fun PaperQuote(text: String, maxLines: Int = 6, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 4.dp)) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(CircleShape).background(c.outline))
        Spacer(Modifier.width(12.dp))
        Text(
            text.trim(),
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = fontOf(Settings.FONT_BOOK), fontStyle = FontStyle.Italic),
            color = c.onSurface,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Кнопка. [primary] - главная кнопка окна, краской по бумаге; остальные -
 * тонкий контур. Значок слева - чтобы кнопку узнавали, не читая.
 */
@Composable
fun PaperButton(
    text: String,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val fg = when {
        !enabled -> c.onSurface.copy(alpha = 0.35f)
        primary -> c.onPrimary
        else -> c.onSurface
    }
    Row(
        modifier
            .heightIn(min = 46.dp)
            .clip(shape)
            .background(if (primary) (if (enabled) c.primary else c.primary.copy(alpha = 0.3f)) else Color.Transparent)
            .then(if (primary) Modifier else Modifier.border(if (LocalEink.current) 1.2.dp else 0.8.dp, c.outline, shape))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Чип-переключатель: пилюля с кромкой, выбранный - закрашен краской по бумаге. */
@Composable
fun PaperChip(
    label: String,
    selected: Boolean,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    warn: Boolean = false,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    val fill = when {
        selected && warn -> c.tertiaryContainer
        selected -> c.primaryContainer
        else -> Color.Transparent
    }
    Row(
        Modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(fill)
            .border(
                if (selected && !LocalEink.current) 0.dp else 0.8.dp,
                if (selected) (if (LocalEink.current) c.onSurface else Color.Transparent) else c.outlineVariant,
                shape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = c.onSurface)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (enabled) c.onSurface else c.onSurface.copy(alpha = 0.35f),
            maxLines = 1,
        )
    }
}

/** Строка-действие: значок, что это, пояснение, стрелка. Как пункты листа «Claude». */
@Composable
fun PaperRow(
    icon: ImageVector?,
    title: String,
    hint: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconBadge(icon, 38.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!hint.isNullOrBlank()) PaperNote(hint)
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(Glyphs.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Тумблер строкой: что, пояснение мелко, переключатель справа. */
@Composable
fun PaperToggle(title: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!hint.isNullOrBlank()) PaperNote(hint)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onPrimary,
                checkedTrackColor = c.primary,
                uncheckedThumbColor = c.onSurfaceVariant,
                uncheckedTrackColor = c.surfaceContainerHighest,
                uncheckedBorderColor = c.outline,
            ),
        )
    }
}

/** «Думаю…» строкой и тонкая полоска под ней. */
@Composable
fun PaperBusy(text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        PaperNote(text)
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            Modifier.fillMaxWidth().height(2.dp).clip(CircleShape),
            color = MaterialTheme.colorScheme.onSurface,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}

@Composable
fun PaperError(text: String) {
    PaperNote(text, Modifier.padding(vertical = 6.dp), MaterialTheme.colorScheme.error)
}

// ----------------------------------------------------------------- разговор

/**
 * Реплика разговора. Свои - справа, закрашенные; ответы - слева, на карточке,
 * книжным кеглем: их читают, как страницу. [footer] - действия под ответом.
 */
@Composable
fun ChatBubble(
    text: String,
    mine: Boolean,
    footer: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = if (mine) 480.dp else 640.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp, topEnd = 18.dp,
                        bottomStart = if (mine) 18.dp else 6.dp, bottomEnd = if (mine) 6.dp else 18.dp,
                    )
                )
                .background(if (mine) c.primaryContainer else c.surfaceContainerHigh)
                .then(if (LocalEink.current) Modifier.border(1.dp, c.onSurface, RoundedCornerShape(18.dp)) else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text,
                style = if (mine) MaterialTheme.typography.bodyMedium
                else MaterialTheme.typography.bodyLarge.copy(fontSize = 16.5.sp, lineHeight = 25.sp),
                color = c.onSurface,
            )
            if (footer != null) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, content = footer)
            }
        }
    }
}

/**
 * Строка ввода снизу: поле на бумаге, микрофон и «отправить» кругом. Поле
 * растёт до четырёх строк; пока микрофон слушает, он закрашен.
 */
@Composable
fun ChatInput(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    listening: Boolean,
    enabled: Boolean,
    onMic: () -> Unit,
    onSend: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Column {
        PaperRule()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PaperIconButton(Glyphs.Mic, if (listening) "Хватит" else "Сказать", active = listening, onClick = onMic)
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(c.surfaceContainerHigh)
                    .border(if (LocalEink.current) 1.dp else 0.dp, c.outlineVariant, RoundedCornerShape(22.dp))
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            ) {
                if (value.isEmpty()) {
                    Text(if (listening) "Слушаю…" else placeholder, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValue,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
                    cursorBrush = SolidColor(c.onSurface),
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(6.dp))
            val can = enabled && value.isNotBlank()
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (can) c.primary else c.surfaceContainerHighest)
                    .clickable(enabled = can, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Отправить",
                    tint = if (can) c.onPrimary else c.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Поле ввода на бумаге - пилюля, как строка вопроса, без рамки Material с
 * плавающей подписью. [trailing] - кнопка справа, внутри той же строки.
 */
@Composable
fun PaperField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 3,
    leading: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .clip(shape)
                .background(c.surfaceContainerHigh)
                .border(if (LocalEink.current) 1.dp else 0.5.dp, c.outlineVariant, shape)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Icon(leading, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
            }
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant, maxLines = 1)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValue,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
                    cursorBrush = SolidColor(c.onSurface),
                    maxLines = maxLines,
                    singleLine = maxLines == 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            trailing()
        }
    }
}

/** Мелкая кнопка под ответом: значок и слово - «Вслух», «Тише», «Копия». */
@Composable
fun MiniAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Текст для обычного `Text` в стиле книжного абзаца - для пересказа и статей справочника. */
@Composable
fun bookBody(): TextStyle =
    MaterialTheme.typography.bodyLarge.copy(fontFamily = fontOf(Settings.FONT_BOOK), fontSize = 17.sp, lineHeight = 27.sp)
