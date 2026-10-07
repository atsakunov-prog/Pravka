package ru.zf.pravka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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

/**
 * Пилюля «+84» (DESIGN §11.2 ZPill): монета Засечки 30 и балл дня, ведёт в
 * Засечку. Стекло цвета янтаря — единственный цвет режима в шапке дня, и он
 * — метка источника, а не украшение.
 */
@Composable
fun ZPill(score: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalPravkaType.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .height(40.dp)
            .clip(shape)
            .background(Color(0xFF3C220C).copy(alpha = 0.75f))
            .background(Brush.verticalGradient(listOf(Color(0xFFFFC88C).copy(alpha = 0.18f), Color(0xFFFFC88C).copy(alpha = 0.04f))))
            .border(1.dp, Modes.Zasechka.key.copy(alpha = 0.40f), shape)
            .clickable(role = Role.Button, onClickLabel = "Засечка", onClick = onClick)
            .semantics { contentDescription = "Засечка, балл дня $score" }
            .padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Coin(ModeDecor.ZASECHKA, 30.dp)
        Text(score, style = t.valueS, color = Color(0xFFFFC261), maxLines = 1)
    }
}

/** Аватар «С» — вход в «Ещё» (DESIGN §12.10). */
@Composable
fun AvatarKey(letter: String, onClick: () -> Unit, size: Dp = 44.dp) {
    val t = LocalPravkaType.current
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFFFFE6C8).copy(alpha = 0.08f))
            .border(1.dp, Color(0xFFFFDCB4).copy(alpha = 0.30f), CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Ещё и настройки", onClick = onClick)
            .semantics { contentDescription = "Ещё и настройки" },
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, style = t.valueS.copy(fontSize = 16.sp), color = Ink.Cream)
    }
}

/**
 * Шапка дня, развёрнутая (DESIGN §11.2 DayHeader, `mockups/01`): надстрочник
 * «5 октября · сегодня», день недели Literata 30, справа пилюля «+84»,
 * статистика и аватар.
 */
@Composable
fun DayHeader(
    overline: String,
    weekday: String,
    score: String?,
    onScore: () -> Unit,
    onStats: () -> Unit,
    avatar: String,
    onAvatar: () -> Unit,
    modifier: Modifier = Modifier,
    /** Тап по дню недели — навигатор дня (прошлый и будущий день). */
    onWeekday: (() -> Unit)? = null,
    /** Двойной тап по дню недели — шапка до минимума (баг №6). */
    onWeekdayDouble: (() -> Unit)? = null,
    /** Тихая строка под днём недели — состояние: сон, HRV, форма (баг №13). */
    status: (@Composable () -> Unit)? = null,
) {
    val t = LocalPravkaType.current
    Column(modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 2.dp)) {
        Text(overline, style = t.label.copy(lineHeight = 16.sp), color = Ink.TextSecondary, maxLines = 1)
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FitText(
                weekday, style = t.titleL, color = Ink.TextStrong, minSize = 22f,
                modifier = Modifier.weight(1f).then(
                    if (onWeekday != null || onWeekdayDouble != null) Modifier.combinedClickable(
                        onClickLabel = "другой день",
                        onClick = onWeekday ?: {},
                        onDoubleClick = onWeekdayDouble,
                    ) else Modifier
                ),
            )
            if (score != null) ZPill(score, onScore)
            HeaderIcon(Glyphs.Stats, "Статистика дня", onStats, tint = Ink.PlanText)
            AvatarKey(avatar, onAvatar)
        }
        if (status != null) status()
    }
}

/** Мини-плашка сжатой шапки (DESIGN §11.2 MiniStat): 28 dp, цвета режима, тап — режим. */
@Composable
fun MiniStat(text: String, mode: ModeColors, description: String, onClick: () -> Unit, dim: Boolean = false) {
    val t = LocalPravkaType.current
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
 * Шапка дня, сжатая (DESIGN §11.2, `mockups/02`): 52 dp, слева «пн, 5 окт»
 * и «+7° · дождь с 17», справа пять мини-плашек.
 */
@Composable
fun DayHeaderCompact(
    date: String,
    weatherLine: String?,
    minis: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    /** Тап (и двойной тап) по дате — развернуть шапку обратно. */
    onDate: (() -> Unit)? = null,
) {
    val t = LocalPravkaType.current
    Row(
        modifier.fillMaxWidth().height(52.dp).padding(start = 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .then(if (onDate != null) Modifier.combinedClickable(onClickLabel = "развернуть день", onClick = onDate, onDoubleClick = onDate) else Modifier)
                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Text(date, style = t.valueS.copy(fontSize = 14.5.sp, lineHeight = 17.sp), color = Ink.TextStrong, maxLines = 1)
            if (!weatherLine.isNullOrBlank()) {
                Text(weatherLine, style = t.caption.copy(lineHeight = 17.sp), color = Ink.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        minis()
    }
}

/** Ячейка погоды: значок 20 и строки — «утро», «+4°» и серым «ощущ. +1°» ([feels]). */
class WeatherCell(val icon: ImageVector, val label: String, val value: String, val dim: Boolean = false, val feels: String? = null)

/**
 * Погода (DESIGN §11.2 WeatherRow): четыре ячейки — утро, день, вечер,
 * осадки («дождь 17–21 / 3 мм · 80 %»). Без данных ряд не показывается —
 * это решает вызывающий: пустой список здесь — пустота, не заглушка.
 * Под температурой — серым «как ощущается» (баг №9). [onClick] — тап по
 * ряду: лист с часами и десятью днями (баг №4).
 */
@Composable
fun WeatherRow(cells: List<WeatherCell>, modifier: Modifier = Modifier, offline: Boolean = false, onClick: (() -> Unit)? = null) {
    if (cells.isEmpty()) return
    val t = LocalPravkaType.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "погода по часам и на десять дней", onClick = onClick) else Modifier)
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        cells.forEach { c ->
            Row(
                Modifier.alpha(if (offline) 0.55f else 1f),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(c.icon, null, tint = Ink.WeatherIcon.copy(alpha = if (c.dim) 0.5f else 1f), modifier = Modifier.padding(top = 6.dp).size(20.dp))
                Column {
                    Text(c.label, style = t.caption.copy(fontSize = 11.sp, lineHeight = 13.sp), color = Ink.Caption, maxLines = 1)
                    Text(c.value, style = t.weather, color = Ink.WeatherText, maxLines = 1)
                    if (c.feels != null) {
                        Text(c.feels, style = t.caption.copy(fontSize = 11.sp, lineHeight = 13.sp), color = Ink.Caption.copy(alpha = 0.75f), maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * Кнопка «↓ сейчас · 18:51» (DESIGN §11.5 NowJump): стекло 40 dp, справа над
 * строкой «сказать». Видна ровно тогда, когда шапка сжата.
 */
@Composable
fun NowJump(time: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalPravkaType.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .height(40.dp)
            .glass(shape, Modes.Today.glass)
            .clip(shape)
            .clickable(role = Role.Button, onClickLabel = "к сейчас", onClick = onClick)
            .padding(start = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Glyphs.ArrowDown, null, tint = Ink.Cream, modifier = Modifier.size(18.dp))
        Text("сейчас · $time", style = t.label.copy(fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Ink.Cream)
    }
}
