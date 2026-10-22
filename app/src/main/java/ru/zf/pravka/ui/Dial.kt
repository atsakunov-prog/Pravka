package ru.zf.pravka.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.zf.pravka.core.Fmt
import kotlin.math.cos
import kotlin.math.sin

// Засечка Правки 4.0 (07.10.2026, DESIGN §11.6, §12.3): круг дня, плашка
// времени, полоса по категориям, строки ленты и итоги. Круг заменяет полосу
// баланса −5…+10 (`RainbowScoreBar` ушёл из набора).
// С 10.10.2026 круг — циферблат часов на 12 часов (владелец: «надо сделать
// его циферблатом… внутренняя часть от 0 ночью до 12:00, внешняя — с 12:00
// до 24»; «как на часах: от 0 до 12 рядом цифры и рисочки»): утро — столбиками
// внутрь от базового круга, вечер — наружу, длина — цена часа, цифры 1…12 и
// риски по кромке. Числа — `core/DialGeometry.kt` под тестом, здесь рисунок.
// Краска — светофор цены часа (`categoryFill`, 10.10.2026): сектор и полоса
// итога — цветом своей категории, полоса плашки и легенда — типичной ценой
// часа группы (`ZGroup`).

/** Сектор циферблата: запись ленты внутри суток. */
class DialSector(
    val id: Long,
    /** Минуты от начала суток. */
    val fromMin: Float,
    val toMin: Float,
    /** Очки за час категории. */
    val worth: Int,
    val color: Color,
    val current: Boolean,
    val hatched: Boolean = false,
)

/** Отрезок полосы: доля, группа (цвет и штриховка). */
class BarPart(val weight: Float, val group: ZGroup)

/** Состояние плашки времени: день идёт, до подъёма, день закрыт (после сна), прошлый день. */
enum class TimePlateState { AWAKE, BEFORE_WAKE, CLOSED, PAST }
