package ru.zf.pravka

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.zf.pravka.core.WeatherDay
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.Ink
import ru.zf.pravka.ui.Kv
import ru.zf.pravka.ui.KvShape
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.Modes
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperSheet
import java.time.LocalDate
import java.time.LocalTime

// Шапка «Сегодня» сверх модели дня (07.10.2026): строка состояния под днём
// недели (баг №13) и лист погоды по тапу на ряд (баг №4: «погоду на сегодня
// по часам и с вероятностью дождя, и дальше погода на ближайшие 10 дней»).
// Правила строк — `core/DayState.kt`, `core/WeatherDay.kt`; здесь только вид.

/** Осадки — холодным голубым: единственный цвет в листе погоды, кроме кремового текста. */
/** Осадки — голубым 4.0; в v2 — акцентом (на белом голубой пропадал). */
private val RAIN: Color get() = if (ru.zf.pravka.ui.Look.v4) Color(0xFF8FB4D4) else ru.zf.pravka.ui.Kv.accent
