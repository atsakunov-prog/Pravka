package ru.zf.pravka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

// Общие кирпичи бумажной вёрстки: заголовок раздела, карточка, подсказка,
// полоска «сколько от цели». Настройки Правки держат такие же у себя внутри
// (private в MainActivity.kt), но вкладкам «Спорт» и «Еда» они нужны обеим -
// а два экрана с чуть разными отступами выглядят как два приложения.

/** Малый прописной заголовок над карточкой, чернилами акцента. */
@Composable
fun PaperLabel(text: String, color: Color? = null) {
    Text(
        text.uppercase(Locale.forLanguageTag("ru")),
        style = MaterialTheme.typography.labelMedium,
        color = color ?: MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

/** Карточка с необязательной подписью над ней. */
@Composable
fun PaperCard(
    label: String? = null,
    labelColor: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (label != null || trailing != null) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (label != null) PaperLabel(label, labelColor) else Box {}
                trailing?.invoke()
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                content = content,
            )
        }
    }
}

/** Мелкий серый текст под значением: «база 45 за две недели». */
@Composable
fun PaperHint(text: String, color: Color? = null) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun PaperTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall)
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
