package ru.zf.pravka.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.zf.pravka.core.DayProfile
import ru.zf.pravka.core.DayProfile.round
import ru.zf.pravka.core.Fmt

// «Профиль горы» дня (Правка 5.0, этап 2; холст «Ковчег на телефоне», `.pf` в
// `phone.css`): рисунок к `core/DayProfile.kt`, один на «Сегодня» и Засечку.
// Каждый отрезок — цветом своей категории: линия плотная, заливка до нуля тем
// же цветом мягко (.2, как `.pfa`). Ноль пунктиром — только если день уходил
// вниз; «сейчас» — вертикальным пунктиром. Под горой — полоса суток по
// категориям (`.day`) и метки часов (`.ticks`).
//
// Цвет категории — «чей это» (`categoryTone`, `core/CategoryTone.kt`; владелец
// 13.10.2026: «нравилась гамма как на макетах, а не светофор»): гора, полоса и
// квадратики ленты говорят одним языком. Засечка может дать свой [colorOf].

/**
 * Цвет отрезка горы: «не размечено» — бледным `--check`, остальное — цветом
 * категории «чей это». Дорога и потери в горе — полным цветом (`.pfl.c8` холста:
 * штрих `--sp7` без примеси фона): линия в два пикселя пополам с фоном пропадала
 * бы; в полосе и квадратиках они бледные.
 */
fun mountainColor(s: DayProfile.Seg): Color {
    if (s.gap) return categoryTone(DayProfile.UNMARKED, line = false)
    if (Look.v4) return categoryTone(s.category, s.worth)
    return Kv.sp[ru.zf.pravka.core.CategoryTone.of(s.category).sp]
}

/** Цвет того же отрезка в полосе суток: «не размечено» — линией (`.cg` холста), не дырой. */
fun mountainStripColor(s: DayProfile.Seg): Color =
    if (s.gap) categoryTone(DayProfile.UNMARKED) else categoryTone(s.category, s.worth)

/**
 * Гора дня: профиль высотой [height], под ним полоса суток ([strip]) и метки
 * часов 0–24 ([ticks]). Ширина — сутки целиком: утро слева, вечер справа.
 */
@Composable
fun DayMountain(
    profile: DayProfile.Profile,
    modifier: Modifier = Modifier,
    height: Dp = 50.dp,
    strip: Boolean = true,
    ticks: Boolean = true,
    colorOf: (DayProfile.Seg) -> Color = ::mountainColor,
    stripColorOf: (DayProfile.Seg) -> Color = ::mountainStripColor,
) {
    val p = profile
    val described = buildString {
        append("Профиль дня: очки копятся по часам")
        p.peak?.let { append(", пик ${Fmt.points(it.round())}") }
        append(", сейчас ${Fmt.points(p.value.round())}")
    }
    // Цвета — в композиции: Kv — состояние, рисунок перерисуется сам при смене темы.
    val zero = Kv.faint
    val nowLine = Kv.text.copy(alpha = 0.5f)
    val hole = Kv.check
    val track = Kv.lineSoft
    val colors = p.segs.map(colorOf)
    val stripColors = p.segs.map(stripColorOf)
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = described }) {
            val w = size.width
            val h = size.height
            fun x(m: Float) = m / DayProfile.DAY_MIN * w
            fun y(v: Double) = p.y(v) * h
            val y0 = y(0.0)
            val px = 1.dp.toPx()
            if (p.dipped) {
                drawLine(zero, Offset(0f, y0), Offset(w, y0), strokeWidth = px, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4 * px, 4 * px)))
            }
            var prevEnd: Float? = null
            var prevVal = 0.0
            p.segs.forEachIndexed { i, s ->
                val c = colors[i]
                val x0 = x(s.fromMin)
                val x1 = x(s.toMin)
                // Время без записи между отрезками — ровно, бледно и без заливки: очки не шли.
                prevEnd?.let { pe ->
                    if (s.fromMin - pe > 0.5f) drawLine(hole, Offset(x(pe), y(prevVal)), Offset(x0, y(prevVal)), strokeWidth = px)
                }
                val fill = Path().apply {
                    moveTo(x0, y0)
                    lineTo(x0, y(s.before))
                    lineTo(x1, y(s.after))
                    lineTo(x1, y0)
                    close()
                }
                drawPath(fill, c.copy(alpha = 0.2f))
                drawLine(c, Offset(x0, y(s.before)), Offset(x1, y(s.after)), strokeWidth = 2 * px, cap = StrokeCap.Round)
                prevEnd = s.toMin
                prevVal = s.after
            }
            p.nowMin?.let { m ->
                val xn = x(m)
                drawLine(nowLine, Offset(xn, h * 0.04f), Offset(xn, h * 0.96f), strokeWidth = px, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * px, 3 * px)))
            }
        }
        if (strip) {
            Canvas(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp)),
            ) {
                drawRect(track)
                val w = size.width
                val gap = 1.dp.toPx()
                p.segs.forEachIndexed { i, s ->
                    val x0 = s.fromMin / DayProfile.DAY_MIN * w
                    val x1 = s.toMin / DayProfile.DAY_MIN * w
                    // Зазор в пиксель между отрезками — как `gap: 1px` у `.day`: соседние дела не сливаются.
                    val width = (x1 - x0 - gap).coerceAtLeast(minOf(x1 - x0, gap))
                    if (width > 0f) drawRect(stripColors[i], topLeft = Offset(x0, 0f), size = Size(width, size.height))
                }
            }
        }
        if (ticks) {
            val t = LocalPravkaType.current
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                for (hh in listOf("0", "6", "12", "18", "24")) {
                    Text(hh, style = t.caption.copy(fontSize = 11.sp, lineHeight = 13.sp), color = Kv.faint)
                }
            }
        }
    }
}

/**
 * Строка под горой: текущее значение крупно, рядом мелко «очки · пик +97 в
 * 16:08 · не размечено 2 ч 2 м», справа — [trailing] («Стоп» на «Сегодня»).
 */
@Composable
fun MountainValue(
    profile: DayProfile.Profile,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val t = LocalPravkaType.current
    val caption = DayProfile.caption(profile)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            Fmt.points(profile.value.round()),
            style = t.valueL.copy(fontSize = 20.sp, lineHeight = 24.sp),
            color = if (profile.value < 0) Kv.warn else Kv.text,
            maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (caption.isBlank()) "очки" else "очки · $caption",
            style = t.caption,
            color = Kv.faint,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}
