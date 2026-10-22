package ru.zf.pravka.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

// Графики Общей статистики (11.10.2026) — с отчёта, который владелец принёс от
// Claude: средний день по часам стопками, ломаные недель с переключателями
// линий, столбики, стопки с линией дохода, полосы с чертой нормы. На Canvas,
// без библиотек. Что под пальцем — строкой под графиком (`Readout`), а не
// всплывашкой: на телефоне всплывашку закрывает тот же палец.
//
// Цвета групп — палитра отчёта в тёмной теме (владелец: «очень нравится»):
// десять различимых оттенков вместо краски одного режима. Это исключение из
// «акцент только краской режима» нарочно — см. docs/agreements.md.

private val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 9f))

/** Подпись мелким серым на Canvas. */
private fun DrawScope.label(
    m: TextMeasurer,
    text: String,
    x: Float,
    y: Float,
    style: TextStyle,
    align: TextAlign = TextAlign.Center,
) {
    val l = m.measure(text, style)
    val left = when (align) {
        TextAlign.End, TextAlign.Right -> x - l.size.width
        TextAlign.Start, TextAlign.Left -> x
        else -> x - l.size.width / 2f
    }.coerceIn(0f, (size.width - l.size.width).coerceAtLeast(0f))
    drawText(l, topLeft = Offset(left, y - l.size.height / 2f))
}
