package ru.zf.pravka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.zf.pravka.core.Fmt
import ru.zf.pravka.core.Lenta

// Строка ленты вида v2 (Правка 5.0, этап 2; холст `.lr`, `.lb`, `.ls`, `.lr.lx`
// в `phone.css`, поправка владельца 13.10.2026): отрезок — «от–до», квадратик
// категории, название и под ним мелко «категория · откуда», справа
// длительность; событие внутри отрезка — без линии, мельче и приглушённо, на
// месте времени — значок вида и время, квадратика нет (место пустое, чтобы
// текст стоял ровно под названием). Одна строка на «Сегодня» и Засечку.

/** Ширина колонки времени: «18:34–18:42» (`.tm2` — 82). */
val RIBBON_TIME_W: Dp = 82.dp

/** Значок вида события (`GlyphsKv`): еда, активность, дело с галочкой, кошелёк, луна. */
fun ribbonIcon(kind: Lenta.Kind): ImageVector = when (kind) {
    Lenta.Kind.FOOD -> KvGlyphs.byKey("food")
    Lenta.Kind.SPORT -> KvGlyphs.byKey("activity")
    Lenta.Kind.SLEEP -> KvGlyphs.byKey("moon")
    Lenta.Kind.DELA -> KvGlyphs.byKey("dela")
    Lenta.Kind.MONEY -> KvGlyphs.byKey("wallet")
} ?: Glyphs.Info

/**
 * Отрезок ленты. [first] — без линии сверху (первая строка карточки),
 * [current] — идущее дело (мягкий акцент, как `.lr.now`), [faint] — «не
 * размечено» бледным курсивом.
 */
@Composable
fun RibbonSegment(
    time: String,
    color: Color,
    title: String,
    sub: String,
    duration: String,
    modifier: Modifier = Modifier,
    first: Boolean = false,
    current: Boolean = false,
    faint: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val t = LocalPravkaType.current
    Column(modifier.fillMaxWidth()) {
        if (!first && !current) Box(Modifier.fillMaxWidth().height(1.dp).background(Kv.lineSoft))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(7.dp))
                .then(if (current) Modifier.background(Kv.accentSoft) else Modifier)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = if (current) 8.dp else 0.dp, vertical = if (current) 6.dp else 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                time,
                style = t.time.copy(fontSize = 12.5.sp),
                color = if (current) Kv.accent else Kv.faint,
                maxLines = 1,
                modifier = Modifier.width(RIBBON_TIME_W),
            )
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = t.label.copy(
                        fontSize = 13.5.sp,
                        lineHeight = 18.sp,
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                        fontStyle = if (faint) FontStyle.Italic else FontStyle.Normal,
                    ),
                    color = if (faint) Kv.faint else Kv.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sub.isNotBlank()) {
                    Text(sub, style = t.caption.copy(fontSize = 11.5.sp, lineHeight = 15.sp), color = Kv.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(duration, style = t.caption, color = Kv.faint, maxLines = 1)
        }
    }
}

/** Событие внутри отрезка: значок вида и время на месте «от–до», текст под названием отрезка. */
@Composable
fun RibbonEvent(
    icon: ImageVector,
    time: String,
    text: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val t = LocalPravkaType.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(Modifier.width(RIBBON_TIME_W), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Kv.icon, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(time, style = t.time.copy(fontSize = 12.5.sp), color = Kv.muted, maxLines = 1)
        }
        // Место квадратика пустое — текст события стоит ровно под названием отрезка.
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = t.label.copy(fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
            color = Kv.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Строка ленты из `core/Lenta.kt`: отрезок или событие. Цвет квадратика — как
 * у горы дня (`categoryTone`, «не размечено» — линией), время — «от–до».
 */
@Composable
fun RibbonRow(
    row: Lenta.Row,
    first: Boolean,
    onSeg: ((Lenta.Row.Seg) -> Unit)? = null,
    onEvent: ((Lenta.Row.Event) -> Unit)? = null,
) {
    when (row) {
        is Lenta.Row.Seg -> RibbonSegment(
            time = Fmt.hm(row.from) + "–" + if (row.current) "сейчас" else Fmt.hm(row.to),
            color = if (row.gap) categoryTone(ru.zf.pravka.core.DayProfile.UNMARKED) else categoryTone(row.category, row.worth),
            title = row.title,
            sub = row.sub,
            duration = Fmt.dur(row.minutes),
            first = first,
            current = row.current,
            faint = row.gap,
            onClick = onSeg?.let { f -> { f(row) } },
        )
        is Lenta.Row.Event -> RibbonEvent(
            icon = ribbonIcon(row.kind),
            time = Fmt.hm(row.at),
            text = if (row.pending) row.text + " · записать?" else row.text,
            onClick = onEvent?.let { f -> { f(row) } },
        )
    }
}
