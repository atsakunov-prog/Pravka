package ru.zf.pravka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Шапки Правки 4.0 (07.10.2026, DESIGN §11.2): шапка режима (‹ · монета ·
// название с засечками · значки без капсулы) и шапка дня «Сегодня» —
// надстрочник, день недели, пилюля «+84» с монетой Засечки, статистика и
// аватар «С»; сжатая — в одну строку с пятью мини-плашками.

/** Значок действия в шапке — 22 dp в цели 44, цвет подписи режима, без капсулы. */
@Composable
fun HeaderIcon(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color? = null) {
    val mode = LocalMode.current
    if (Look.v2) {
        // `.ib` веба: 40, прямоугольник 8, значок 20 цветом `--icon`.
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(KvShape.Control))
                .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = tint ?: Kv.icon, modifier = Modifier.size(20.dp))
        }
        return
    }
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint ?: if (mode === Modes.Today) Ink.PlanText else mode.label, modifier = Modifier.size(22.dp))
    }
}

/**
 * Шапка режима (DESIGN §11.2 ModeHeader, `mockups/06`): «‹» к «Сегодня»,
 * монета 34, название Literata 24; вторым тоном под ним — главный выбор
 * режима («Сонет · high ⌄», «Личное ⌄»), тап — [onSubtitle]. Справа —
 * статистика, $ и шестерёнка ([actions]), значки без капсулы.
 */
@Composable
fun ModeHeader(
    title: String,
    decor: ModeDecor?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onSubtitle: (() -> Unit)? = null,
    glyph: ImageVector? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val mode = LocalMode.current
    val t = LocalPravkaType.current
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(start = if (onBack != null) 2.dp else 20.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) HeaderIcon(Glyphs.Back, "назад", onBack)
        val coin = decor != null && decor != ModeDecor.SERVICE && decor != ModeDecor.TODAY
        if (coin) {
            Spacer(Modifier.width(4.dp))
            Coin(decor!!, 34.dp)
            Spacer(Modifier.width(10.dp))
        } else if (glyph != null) {
            Spacer(Modifier.width(2.dp))
            Icon(glyph, null, tint = mode.label, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        } else if (onBack != null) {
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = if (subtitle != null) t.titleM.copy(lineHeight = 26.sp) else t.titleM,
                color = mode.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .then(if (onSubtitle != null) Modifier.clickable(role = Role.DropdownList, onClick = onSubtitle) else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(subtitle, style = t.label.copy(lineHeight = 16.sp), color = mode.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (onSubtitle != null) {
                        Icon(Glyphs.ChevronDown, null, tint = mode.label, modifier = Modifier.padding(start = 2.dp).size(14.dp))
                    }
                }
            }
        }
        if (actions != null) Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

// ---------------------------------------------------------------------------
// «Сегодня»
// ---------------------------------------------------------------------------

/** Красная точка «есть новенькое» (12.10.2026: новый разбор — на «С» и на «Разборах» в «Ещё»). */
val NewsDot = Color(0xFFE5484D)

@Composable
fun NewsDotMark(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(NewsDot)
            .border(1.5.dp, Ink.Bg, CircleShape),
    )
}

/** Мини-плашка сжатой шапки (DESIGN §11.2 MiniStat): 28 dp, цвета режима, тап — режим. */
@Composable
fun MiniStat(text: String, mode: ModeColors, description: String, onClick: () -> Unit, dim: Boolean = false) {
    val t = LocalPravkaType.current
    if (Look.v2) {
        // v2 — метка `.tag` веба: прямоугольник 5, без краски режима.
        Box(
            Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(KvShape.Control))
                .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = t.mini,
                color = Kv.muted.copy(alpha = if (dim) 0.55f else 1f),
                maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(KvShape.Tag)).background(Kv.hover2).padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        return
    }
    val shape = RoundedCornerShape(14.dp)
    val zasechka = mode === Modes.Zasechka
    Box(
        Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .height(28.dp)
                .clip(shape)
                .background(mode.key.copy(alpha = if (zasechka) 0.22f else 0.36f))
                .border(1.dp, (if (zasechka) mode.key else mode.tint).copy(alpha = 0.50f), shape)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = t.mini,
                color = (if (zasechka) Color(0xFFFFC261) else mode.value).copy(alpha = if (dim) 0.55f else 1f),
                maxLines = 1,
            )
        }
    }
}

/**
 * Значок Ковчега в шапке (план §3, `.kb` холста): 48 dp, во всю высоту
 * заголовка с подписью, без стрелки. Тап — меню всех сервисов
 * (`LocalKovcheg`); пока меню открыто — рамка акцентом вокруг, есть
 * новенькое (новый разбор) — метка `--bad` в углу. Сам значок — значок
 * Ковчега с сервера (`KovchegHost.icon`, в репозитории его нет); пока он не
 * пришёл — иконка приложения: фон адаптивной иконки и передний слой.
 */
@Composable
fun KovchegMark(modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val host = LocalKovcheg.current
    val open = host?.isOpen == true
    val ring = Kv.accent
    val bg = androidx.compose.ui.res.colorResource(ru.zf.pravka.R.color.ic_launcher_background)
    Box(
        modifier
            .size(size)
            .drawWithContent {
                drawContent()
                if (open) {
                    val w = 2.dp.toPx()
                    drawRoundRect(
                        ring,
                        // Кольцо 2 dp снаружи значка — `box-shadow: 0 0 0 2px` холста.
                        topLeft = androidx.compose.ui.geometry.Offset(-w / 2f, -w / 2f),
                        size = androidx.compose.ui.geometry.Size(this.size.width + w, this.size.height + w),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(w),
                    )
                }
            }
            .then(
                if (host != null) Modifier
                    .clip(RoundedCornerShape(11.dp))
                    .clickable(role = Role.Button, onClickLabel = "все сервисы Ковчега", onClick = host.toggle)
                else Modifier
            )
            .semantics { contentDescription = if (host?.news == true) "Все сервисы Ковчега, есть новый разбор" else "Все сервисы Ковчега" },
    ) {
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(11.dp)).background(bg),
            contentAlignment = Alignment.Center,
        ) {
            val brand = host?.icon
            if (brand != null) {
                androidx.compose.foundation.Image(
                    brand,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            } else {
                // Передний слой адаптивной иконки — 108 dp, из них видно среднее 72: показываем ровно его.
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(ru.zf.pravka.R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.requiredSize(size * 1.5f),
                )
            }
        }
        if (host?.news == true) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = 3.dp)
                    .size(10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Kv.bad)
                    .border(1.5.dp, Kv.bg, RoundedCornerShape(3.dp)),
            )
        }
    }
}

/**
 * Шапка экрана вида v2 (план §3, `.top` холста): слева значок Ковчега 48,
 * рядом название 23/600 и подпись `--faint`; справа значки `.ib`. [onBack] —
 * только у экранов стопкой поверх (настройки, «Ещё»): у режимов назад —
 * системный жест, а переход — через меню. [onSubtitle] — подпись нажимается
 * («Сонет · high ⌄»); [onTitle] / [onTitleDouble] — тап по названию (у
 * «Сегодня» — навигатор дня и шапка до минимума).
 */
@Composable
fun KovchegHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onSubtitle: (() -> Unit)? = null,
    onTitle: (() -> Unit)? = null,
    onTitleDouble: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    /**
     * Клавиши сразу за названием (16.10.2026 вечер, запись 32: «новое дело должно быть высоко — рядом с „Дела“ наверху
     * плюс и микрофон»): не у правого края со статистикой и шестерёнкой, а рядом с названием. null — шапка как была.
     */
    lead: (@Composable RowScope.() -> Unit)? = null,
) {
    // Строка «Что не так?» (режим отладки, с 16.10.2026 на всех экранах) — под шапкой: одно место на все экраны вида
    // v2 («Сегодня», режимы, «Телефон», настройки, страницы сервисов). Её нет — шапка ровно как была.
    val bar = LocalScreenBar.current
    if (bar == null) {
        KovchegHeaderRow(title, modifier, subtitle, onSubtitle, onTitle, onTitleDouble, onBack, actions, lead)
        return
    }
    Column(modifier.fillMaxWidth()) {
        KovchegHeaderRow(title, Modifier, subtitle, onSubtitle, onTitle, onTitleDouble, onBack, actions, lead)
        bar(title, subtitle)
    }
}

@Composable
private fun KovchegHeaderRow(
    title: String,
    modifier: Modifier,
    subtitle: String?,
    onSubtitle: (() -> Unit)?,
    onTitle: (() -> Unit)?,
    onTitleDouble: (() -> Unit)?,
    onBack: (() -> Unit)?,
    actions: (@Composable RowScope.() -> Unit)?,
    lead: (@Composable RowScope.() -> Unit)? = null,
) {
    val t = LocalPravkaType.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = if (onBack != null) 4.dp else 16.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            HeaderIcon(Glyphs.Back, "назад", onBack)
            Spacer(Modifier.width(2.dp))
        }
        KovchegMark()
        Spacer(Modifier.width(10.dp))
        // С клавишами за названием — название своей ширины, клавиши сразу за ним, остальное место — пустое до значков
        // справа; название длиннее места ужимается (переносится), а клавиши не уезжают за край.
        val titleModifier = if (lead == null) Modifier.weight(1f) else Modifier
        val titleBlock: @Composable (Modifier) -> Unit = { m -> Column(
            m
                .clip(RoundedCornerShape(KvShape.Control))
                .then(
                    if (onTitle != null || onTitleDouble != null) Modifier.combinedClickable(
                        onClickLabel = "другой день",
                        onClick = onTitle ?: {},
                        onDoubleClick = onTitleDouble,
                    ) else Modifier
                ),
        ) {
            Text(title, style = t.titleM, color = Kv.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                // Подпись — до двух строк переносом по словам (14.10.2026, владелец: «где-то можно переносить по
                // словам»): в одну строку рядом со значками она на сложенном резалась «…». Число держится за своё
                // слово, « · » остаётся в конце строки (`HeadLine.wrap`); многоточие — только если не хватило и двух.
                // «⌄» — внутри текста за последним словом: при переносе он едет со второй строкой, а не висит
                // справа от первой.
                val chevron = onSubtitle != null
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append(ru.zf.pravka.core.HeadLine.wrap(subtitle))
                        if (chevron) {
                            append(ru.zf.pravka.core.HeadLine.NBSP)
                            appendInlineContent(SUB_CHEVRON, "⌄")
                        }
                    },
                    style = t.meta.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = Kv.faint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    inlineContent = if (chevron) subChevron else emptyMap(),
                    modifier = Modifier
                        .clip(RoundedCornerShape(KvShape.Tag))
                        .then(if (onSubtitle != null) Modifier.clickable(role = Role.DropdownList, onClick = onSubtitle) else Modifier),
                )
            }
        } }
        if (lead == null) titleBlock(titleModifier)
        else Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            titleBlock(Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(6.dp))
            lead()
        }
        if (actions != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp), content = actions)
    }
}

private const val SUB_CHEVRON = "chevron"

/** «⌄» подписи шапки — значок 14 dp в строке текста (`appendInlineContent`). */
private val subChevron = mapOf(
    SUB_CHEVRON to androidx.compose.foundation.text.InlineTextContent(
        androidx.compose.ui.text.Placeholder(14.sp, 14.sp, androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter),
    ) {
        Icon(Glyphs.ChevronDown, null, tint = Kv.faint, modifier = Modifier.fillMaxSize())
    },
)
