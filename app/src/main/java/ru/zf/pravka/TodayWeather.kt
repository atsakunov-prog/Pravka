package ru.zf.pravka

import androidx.compose.foundation.background
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
import ru.zf.pravka.core.DayState
import ru.zf.pravka.core.WeatherDay
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.Ink
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.Modes
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperSheet
import ru.zf.pravka.ui.SectionHeader
import java.time.LocalDate
import java.time.LocalTime

// Шапка «Сегодня» сверх модели дня (07.10.2026): строка состояния под днём
// недели (баг №13) и лист погоды по тапу на ряд (баг №4: «погоду на сегодня
// по часам и с вероятностью дождя, и дальше погода на ближайшие 10 дней»).
// Правила строк — `core/DayState.kt`, `core/WeatherDay.kt`; здесь только вид.

/** Осадки — холодным голубым: единственный цвет в листе погоды, кроме кремового текста. */
private val RAIN = Color(0xFF8FB4D4)

/** Столбец «утро» / «день» / «вечер» в списке на десять дней. */
private val PART_W = 46.dp

/**
 * Тихая строка состояния: три точки светофора Спорта и «сон 7,2 ч · HRV 58 ·
 * форма +4» вторым тоном; хуже нормы — тёплым. Тап — Спорт. Переносится,
 * а не режется.
 */
@Composable
internal fun DayStateLine(st: DayState, onClick: () -> Unit) {
    val t = LocalPravkaType.current
    val sport = Modes.Sport
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClickLabel = "Спорт: готовность", onClick = onClick)
            .padding(top = 1.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { k ->
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (k < st.lit) sport.value.copy(alpha = 0.9f) else sport.tint.copy(alpha = 0.22f))
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            buildAnnotatedString {
                st.parts.forEachIndexed { i, p ->
                    if (i > 0) append(" · ")
                    if (p.worse) withStyle(SpanStyle(color = Ink.Warn)) { append(p.text) } else append(p.text)
                }
            },
            style = t.meta,
            color = Ink.TextMeta,
        )
    }
}

/**
 * Лист погоды: сегодня по часам (с текущего часа) — небо, температура,
 * серым «как ощущается», вероятность дождя голубым; ниже — десять дней.
 */
@Composable
internal fun WeatherSheet(w: ru.zf.pravka.data.WeatherStore.Result, day: LocalDate, today: LocalDate, onDismiss: () -> Unit) {
    val sub = listOfNotNull(
        w.city.takeIf { it.isNotBlank() },
        if (w.stale) "нет связи — прогноз из кэша" else null,
    ).joinToString(" · ").ifBlank { null }
    PaperSheet(onDismiss = onDismiss, title = "Погода", icon = Glyphs.PartlyCloudy, subtitle = sub) {
        val hours = WeatherDay.hoursFrom(w.hours, if (day == today) LocalTime.now().hour else null)
        if (hours.isNotEmpty()) {
            SectionHeader(if (day == today) "сегодня по часам" else "по часам", trailing = "серым — ощущается, голубым — осадки")
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (h in hours) HourCell(h, now = day == today && h.hour == LocalTime.now().hour)
            }
        }
        if (w.days.isNotEmpty()) {
            SectionHeader("${w.days.size} дней")
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Подписи столбцов — один раз над списком (баг №16: справа утро,
                // день, вечер вместо «+8…+15»).
                if (w.days.any { it.parts.isNotEmpty() }) {
                    Row(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.weight(1f))
                        for (label in listOf("утро", "день", "вечер")) {
                            Text(
                                label,
                                style = LocalPravkaType.current.caption.copy(fontSize = 11.sp),
                                color = Ink.Caption,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(PART_W),
                            )
                        }
                    }
                }
                for (d in w.days) DayLine(d, today)
            }
        }
        if (hours.isEmpty() && w.days.isEmpty()) {
            PaperHint(w.error?.let { "Прогноза нет: $it" } ?: "Прогноза пока нет.")
        }
    }
}

@Composable
private fun HourCell(h: WeatherDay.Hour, now: Boolean) {
    val t = LocalPravkaType.current
    Column(
        Modifier
            .width(54.dp)
            .clip(RoundedCornerShape(14.dp))
            .then(if (now) Modifier.background(Ink.Cream.copy(alpha = 0.07f)) else Modifier)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (now) "сейчас" else "%02d".format(h.hour),
            style = t.meta.copy(fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal),
            color = if (now) Ink.Text else Ink.TextMeta,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Icon(skyIcon(WeatherDay.sky(h.code, h.hour)), null, tint = Ink.WeatherIcon, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(WeatherDay.temp(h.temp), style = t.weather, color = Ink.WeatherText, maxLines = 1)
        Text(
            if (h.feels.isNaN()) " " else WeatherDay.temp(h.feels),
            style = t.caption.copy(fontSize = 11.sp, lineHeight = 14.sp),
            color = Ink.Caption.copy(alpha = 0.8f),
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        // Осадки каждого часа — всегда обе цифры, и нули (баг №17): вероятность
        // и сколько миллиметров.
        Text(
            "${h.prob} %",
            style = t.caption.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = if (h.prob >= 50) FontWeight.SemiBold else FontWeight.Normal),
            color = RAIN.copy(alpha = if (h.prob >= 30) 1f else 0.55f),
            maxLines = 1,
        )
        Text(
            WeatherDay.mm(h.precipMm),
            style = t.caption.copy(fontSize = 10.5.sp, lineHeight = 13.sp, fontWeight = if (h.precipMm >= 0.5) FontWeight.SemiBold else FontWeight.Normal),
            color = RAIN.copy(alpha = if (h.precipMm >= 0.05) 1f else 0.55f),
            maxLines = 1,
        )
    }
}

@Composable
private fun DayLine(d: WeatherDay.Day, today: LocalDate) {
    val t = LocalPravkaType.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            WeatherDay.dayLabel(d.date, today),
            style = t.body.copy(fontWeight = if (d.date == today) FontWeight.SemiBold else FontWeight.Normal),
            color = Ink.Text,
            modifier = Modifier.width(76.dp),
        )
        Icon(skyIcon(WeatherDay.sky(d.code, 12)), null, tint = Ink.WeatherIcon, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            WeatherDay.rainText(d.prob, d.precipMm),
            style = t.meta.copy(fontWeight = if (d.prob >= 50) FontWeight.SemiBold else FontWeight.Normal),
            color = RAIN.copy(alpha = if (d.prob >= 30) 1f else 0.6f),
            modifier = Modifier.weight(1f),
        )
        if (d.parts.isNotEmpty()) {
            // Утро, день, вечер — столбцами под подписями сверху, серым под
            // каждым — как ощущается.
            for (p in d.parts) {
                Column(Modifier.width(PART_W), horizontalAlignment = Alignment.End) {
                    Text(WeatherDay.temp(p.temp), style = t.weather, color = Ink.WeatherText, maxLines = 1)
                    if (!p.feels.isNaN()) {
                        Text(
                            WeatherDay.temp(p.feels),
                            style = t.caption.copy(fontSize = 11.sp, lineHeight = 14.sp),
                            color = Ink.Caption.copy(alpha = 0.8f),
                            maxLines = 1,
                        )
                    }
                }
            }
        } else Column(horizontalAlignment = Alignment.End) {
            Text(WeatherDay.range(d.min, d.max), style = t.weather, color = Ink.WeatherText, textAlign = TextAlign.End)
            if (!d.feelsMin.isNaN() && !d.feelsMax.isNaN()) {
                Text(
                    "ощущ. " + WeatherDay.range(d.feelsMin, d.feelsMax),
                    style = t.caption.copy(fontSize = 11.sp, lineHeight = 14.sp),
                    color = Ink.Caption.copy(alpha = 0.8f),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}
