package ru.zf.pravka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Навигация — два вида (версия 3, 26.09.2026). Владелец: «надо сделать
// отдельный вид для сложенного телефона, потому что Марианна в основном
// делает всё на сложенном телефоне, и отдельный — для разложенного. У нас с
// ней у обоих Fold». Сложенный — прежние кнопки внизу, выбранная — клавишей
// краски режима (блик и фаска, как у кнопок на стекле). Разложенный — колонка
// слева, как у Gemini на развороте: семь кнопок внизу на широком экране стоят
// потерянными, а в колонке режимы и сразу всё «Ещё» — служебное на тап ближе.

/** С какой ширины экрана навигация — колонкой слева, dp. Разложенный Fold шире, сложенный — уже. */
const val WIDE_DP = 600

/** Одна кнопка навигации: знак, подпись, краска её режима, выбрана ли и что по тапу. */
class NavItem(
    val glyph: ImageVector,
    val label: String,
    val ink: Color,
    val selected: Boolean,
    val onClick: () -> Unit,
)

/** Подложка выбранной кнопки — прозрачная клавиша краски режима (`keyFace`). */
private fun Modifier.navKey(ink: Color, selected: Boolean): Modifier =
    if (selected) keyFace(ink.copy(alpha = 0.2f), RoundedCornerShape(50)) else this

/**
 * Кнопки внизу — сложенный телефон. Панель — продолжение фона с волосяной
 * линией сверху (20.09.2026: плита другого тона читалась грязью), выбранная
 * кнопка — клавиша краски своего режима вместо плоского пятна.
 */
@Composable
fun ModeBottomBar(items: List<NavItem>) {
    val c = MaterialTheme.colorScheme
    val line = c.outlineVariant
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.background)
            .drawBehind {
                drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
            }
            // Приложение рисуется под системными полосами (Android 15+), и
            // полоску жестов прежде обходил Material NavigationBar сам.
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(top = 8.dp, bottom = 12.dp),
    ) {
        for (item in items) BottomItem(item)
    }
}

@Composable
private fun RowScope.BottomItem(item: NavItem) {
    val c = MaterialTheme.colorScheme
    val color = if (item.selected) item.ink else c.onSurfaceVariant
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Tab, onClick = item.onClick)
            .semantics { selected = item.selected }
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.width(52.dp).height(30.dp).navKey(item.ink, item.selected),
            contentAlignment = Alignment.Center,
        ) {
            Icon(item.glyph, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            item.label,
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp),
            fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Normal,
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Колонка слева — разложенный телефон, как у Gemini: почти чёрная полоса,
 * сверху имя приложения с засечками, режимы, ниже — всё «Ещё» строками, в
 * самом низу — кто пользуется и шестерёнка общих настроек. Выбранная строка
 * — клавиша краски своего режима.
 */
@Composable
fun ModeRail(
    title: String,
    modes: List<NavItem>,
    service: List<NavItem>,
    serviceLabel: String,
    person: String?,
    settings: NavItem,
) {
    val c = MaterialTheme.colorScheme
    // Колонка тянется под строку состояния и полоску жестов: иначе сверху и
    // снизу остались бы полосы тона вкладки над почти чёрной колонкой.
    val density = LocalDensity.current
    val above = WindowInsets.statusBars.getTop(density).toFloat()
    val below = WindowInsets.navigationBars.getBottom(density).toFloat()
    Column(
        Modifier
            .width(224.dp)
            .fillMaxHeight()
            .drawBehind {
                drawRect(RAIL, topLeft = Offset(0f, -above), size = Size(size.width, size.height + above + below))
                drawLine(
                    c.outlineVariant.copy(alpha = 0.5f),
                    Offset(size.width, -above),
                    Offset(size.width, size.height + below),
                    1.dp.toPx(),
                )
            }
            .padding(horizontal = 10.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                title,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                modifier = Modifier.padding(start = 12.dp, top = 20.dp, bottom = 18.dp),
            )
            for (item in modes) RailItem(item, big = true)
            Text(
                serviceLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = c.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, top = 22.dp, bottom = 6.dp),
            )
            for (item in service) RailItem(item, big = false)
            Spacer(Modifier.height(12.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, top = 8.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (person != null) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(c.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        person.take(1).uppercase(),
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = c.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    person,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Box(
                Modifier
                    .size(44.dp)
                    .navKey(settings.ink, settings.selected)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = settings.onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    settings.glyph,
                    contentDescription = settings.label,
                    tint = if (settings.selected) settings.ink else c.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun RailItem(item: NavItem, big: Boolean) {
    val c = MaterialTheme.colorScheme
    val color = when {
        item.selected -> item.ink
        big -> c.onSurface
        else -> c.onSurface.copy(alpha = 0.82f)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (big) 48.dp else 44.dp)
            .navKey(item.ink, item.selected)
            .clip(RoundedCornerShape(50))
            .clickable(role = Role.Tab, onClick = item.onClick)
            .semantics { selected = item.selected }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(item.glyph, contentDescription = null, tint = color, modifier = Modifier.size(if (big) 22.dp else 20.dp))
        Text(
            item.label,
            style = if (big) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Normal,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Колонка — почти чёрная, на тон глубже фона вкладки: так у Gemini колонка отделена от разговора. */
private val RAIL = Color(0xFF080807)
