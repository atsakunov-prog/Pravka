package ru.zf.pravka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import ru.zf.pravka.core.Micronutrients

// Общие кирпичи бумажной вёрстки: заголовок раздела, карточка, подсказка,
// полоска «сколько от цели». Настройки Правки держат такие же у себя внутри
// (private в MainActivity.kt), но вкладкам «Спорт» и «Еда» они нужны обеим -
// а два экрана с чуть разными отступами выглядят как два приложения.

/**
 * Заголовок раздела над плашкой — overline Правки 4.0 (DESIGN §11.7
 * SectionHeader): прописными с разрядкой, в подписи режима.
 */
@Composable
fun PaperLabel(text: String, color: Color? = null) {
    Text(
        text.uppercase(Locale.forLanguageTag("ru")),
        style = LocalPravkaType.current.overline,
        color = color ?: LocalMode.current.label,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
    )
}

/**
 * Фаска по кромке плашки: светлая линия сверху, тёмная снизу, посередине её
 * нет. Один штрих с продольным градиентом — ровный кант по всему периметру
 * читался бы как рамка виджета, а разный сверху и снизу — как толщина
 * предмета. То же правило, что у `trigger/BubbleSkin.kt` на стекле: свет в
 * приложении и на кнопках должен падать с одной стороны, иначе это два
 * дизайна в одном экране.
 */
fun Modifier.bevel(shape: Shape? = null): Modifier = composed {
    border(
        width = 1.dp,
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = CardLook.RIM_LIGHT),
            0.5f to Color.Transparent,
            1f to Color.Black.copy(alpha = CardLook.RIM_SHADE),
        ),
        shape = shape ?: MaterialTheme.shapes.medium,
    )
}

/**
 * Плашка с необязательным заголовком раздела над ней. Правка 4.0 (07.10.2026,
 * DESIGN §8): плашка — стекло в чернилах режима (на «Сегодня» — нейтральное,
 * на служебных экранах — в цвете Правки), радиус 22; заголовок — overline
 * над плашкой, справа «i» и [trailing]. [info] — пояснение за «i»: на
 * плашке — сама вещь, а не инструкция.
 */
@Composable
fun PaperCard(
    label: String? = null,
    labelColor: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
    info: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val mode = LocalMode.current
    Column(Modifier.fillMaxWidth()) {
        if (label != null || trailing != null || info != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    (label ?: "").uppercase(Locale.forLanguageTag("ru")),
                    style = LocalPravkaType.current.overline,
                    color = labelColor ?: mode.label,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (info != null) InfoDot(label ?: "Пояснение", info)
                trailing?.invoke()
            }
        }
        val shape = RoundedCornerShape(PLATE_RADIUS)
        Box(Modifier.fillMaxWidth().glass(shape, mode.glass)) {
            CompositionLocalProvider(LocalContentColor provides Ink.Text) {
                Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
            }
        }
    }
}

/** Радиус плашки-стекла (DESIGN §7: плашки 22–24). */
val PLATE_RADIUS = 22.dp

/** Мелкий серый текст под значением: «база 45 за две недели». */
@Composable
fun PaperHint(text: String, color: Color? = null) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Полоска «сколько уже съедено от цели». Перебор рисуется своим цветом за
 * границей, а не обрезается: съеденное сверх цели - это ровно то, что владелец
 * и хочет видеть.
 */
@Composable
fun GoalBar(
    value: Int,
    target: Int,
    color: Color,
    overColor: Color = MaterialTheme.colorScheme.error,
    height: Dp = 8.dp,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(track, RoundedCornerShape(height / 2)),
    ) {
        if (target <= 0 || value <= 0) return@Box
        val fraction = (value.toFloat() / target).coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(height)
                .background(
                    if (value > target) overColor else color,
                    RoundedCornerShape(height / 2),
                )
        )
    }
}

/** «620 / 2500 ккал» и полоска под ним — одна строка сводки дня. */
@Composable
fun GoalRow(
    label: String,
    value: Int,
    target: Int,
    unit: String,
    color: Color,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (target > 0) "$value / $target $unit" else "$value $unit",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (target > 0 && value > target) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
        }
        GoalBar(value, target, color)
    }
}

// ---- Витамины и элементы: полоска со светофором и засечкой нормы ----

/** Цвета светофора веществ. Держатся здесь, чтобы вкладка и отчёт светили одинаково. */
val MicroLow = Color(0xFFDC2626)     // меньше половины нормы
val MicroMid = Color(0xFFCA8A04)     // половина - четыре пятых
val MicroOk = Color(0xFF16A34A)      // норма закрыта
val MicroOver = Color(0xFFEA580C)    // выше верхнего предела

fun microColor(level: Micronutrients.Level): Color = when (level) {
    Micronutrients.Level.LOW -> MicroLow
    Micronutrients.Level.MID -> MicroMid
    Micronutrients.Level.OK -> MicroOk
    Micronutrients.Level.OVER -> MicroOver
}

/**
 * Полоска вещества: заливка светофором и ЗАСЕЧКА нормы поперёк.
 *
 * Почему засечка, а не край полоски (как у калорий). У калорий цель — это
 * потолок, и упереться в правый край там осмысленно. У витамина норма — не
 * потолок, а отметка «достаточно»: с таблетками её переходят легко и иногда
 * нарочно (витамин D зимой), и перебор надо ВИДЕТЬ, а не упирать в границу.
 * Поэтому шкала полоски шире нормы, норма стоит риской внутри, и сразу видно
 * не только «добрал ли», но и «насколько мимо».
 */
@Composable
fun MicroBar(
    nutrient: Micronutrients.Nutrient,
    value: Double,
    height: Dp = 10.dp,
) {
    val scale = nutrient.scale()
    val level = Micronutrients.level(nutrient, value)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val shape = RoundedCornerShape(height / 2)
    // Риска должна читаться и на пустом треке, и поверх заливки, поэтому она
    // тёмная с прозрачностью, а не белая и не цвета темы.
    val notchColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        .compositeOver(track)
    val fill = if (scale <= 0) 0f else (value / scale).toFloat().coerceIn(0f, 1f)
    val notch = if (scale <= 0) 0f else (nutrient.norm / scale).toFloat().coerceIn(0.02f, 0.98f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(track, shape)
    ) {
        if (fill > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fill)
                    .height(height)
                    .background(microColor(level), shape)
            )
        }
        // Засечка нормы. Долями ширины, а не смещением в dp: ширина полоски
        // известна только на измерении, а вес в Row отдаёт её сам.
        Row(Modifier.fillMaxWidth().height(height)) {
            Spacer(Modifier.weight(notch))
            Box(Modifier.width(2.dp).fillMaxHeight().background(notchColor))
            Spacer(Modifier.weight(1f - notch))
        }
    }
}
