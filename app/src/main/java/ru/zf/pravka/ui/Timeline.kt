package ru.zf.pravka.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.zf.pravka.core.BalanceLine
import ru.zf.pravka.core.DayAssembler
import ru.zf.pravka.core.Fmt

// Хроника (DESIGN §11.5): строки общей ленты — «Сегодня», разворот и
// Засечка. Строка — труба линии баланса 84, колонка времени 38 (вправо) и
// зазор 8, содержимое и колонка очков 30. Труба рисуется в каждой строке своим куском
// (`drawBehind`), поэтому линия и стенки выглядят непрерывными.
//
// Высота строки — НЕ меньше макетной, а не ровно она (баги №7, №10,
// 07.10.2026, владелец: «шрифт красивый в Дне, но регулярно не влезает…
// давай везде переносить по строкам, потому что для меня всё-таки важна
// информация»): текст переносится, строка растёт, рельс тянется за ней
// (`height(IntrinsicSize.Min)` — колонка рельса меряет высоту по соседям).
// Отметки режимов (еда, деньги, спорт, дела) — ТОЧКОЙ цвета режима прямо на
// рельсе во время отметки, текст рядом плашкой. Сначала (07.10, баг №7) на
// рельсе стояла монета со значком; владелец в тот же вечер (№14): «на
// таймлайне плохо выглядят эти иконки, давай просто точками… другого цвета».
// В плашке первым — число (№15: в еде калории, в деньгах деньги, в делах —
// сколько новых), оно жирнее остального.
// Текст всех строк начинается с одной линии (№19: «ровно такой же отступ, как
// и внутри плашек»): у строк без плашки содержимое сдвинуто на внутренний
// отступ плашки — [TEXT_INSET].
// Точка и линия записи — в цвете категории на радуге (`categoryFill`): с
// 10.10.2026 снова радуга вместо оттенков янтаря — «пропало понимание, чем я
// занимаюсь».
// Рельс прошлого — ЛИНИЯ БАЛАНСА, «дорога жизни» (10.10.2026, владелец:
// «вести эту линию как можно правее»): слева своя труба, время и дело —
// справа от неё (линия под цифрами времени владельцу не понравилась).
// Посередине — сплошная, ноль; по краям — стенки дороги, свой медианный день
// к этому часу плюсом и минусом. Стенки стоят на месте, а масштаб едет со
// временем: линия — балл, поделённый на медиану к этому часу («медианный
// день… занимал бы 100% туннеля… дорога расширяется, и масштаб уменьшается»),
// поэтому утром дорога не волосок. За стенкой — мягкая обочина. Точка записи
// — у её времени, на балле «до»; дальше мягкая S-кривая к баллу «после»; и
// через отметки линия плывёт: балл стоит, а медиана растёт. Краска —
// светофор цены часа (`categoryFill`/`worthTint`), на изломе — перелив.
// Отметки (еда, деньги, спорт, дела) точек не ставят — плашки остались.
// «Сейчас» — пульсирующая точка на конце линии, от неё вправо — черта с
// временем; ниже — пунктир ровно под точкой, кольца плана, дел и сна — на нём.
// Числа — `core/BalanceLine.kt`.

/** Как рисуется рельс строки. */
sealed class Rail {
    /** Прошлое: точка 8 и сплошная 2 dp в цвете категории ([line] — цвет линии вниз). */
    data class Dot(val color: Color, val line: Color? = color) : Rail()
    /** Текущая: точка 10 цвета «сейчас» с ореолом 3 dp. */
    data class Current(val line: Color, val halo: Color) : Rail()
    /** Отметка или ждущее: просто линия на всю высоту. */
    data class Through(val color: Color) : Rail()
    /** План: полое кольцо и пунктир в цвете источника. */
    data class Ring(val color: Color, val line: Color?, val size: Dp = 10.dp) : Rail()
    /** Свободно: точечная линия. */
    data class Dotted(val color: Color) : Rail()
    /** Пунктир без кольца (заголовок группы дел). */
    data class Dashed(val color: Color) : Rail()
    data object None : Rail()
}

private fun DrawScope.rail(r: Rail, x: Float) {
    val w2 = 2.dp.toPx()
    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
    when (r) {
        is Rail.Dot -> {
            val top = 5.dp.toPx()
            val d = 8.dp.toPx()
            drawCircle(r.color, d / 2, Offset(x, top + d / 2))
            r.line?.let { drawLine(it, Offset(x, top + d + 2.dp.toPx()), Offset(x, size.height), w2) }
        }
        is Rail.Current -> {
            val top = 4.dp.toPx()
            val d = 10.dp.toPx()
            val c = Offset(x, top + d / 2)
            drawCircle(r.halo, d / 2 + 3.dp.toPx(), c)
            drawCircle(Ink.Now, d / 2, c)
            drawLine(r.line, Offset(x, top + d + 2.dp.toPx()), Offset(x, size.height), w2)
        }
        is Rail.Through -> drawLine(r.color, Offset(x, 0f), Offset(x, size.height), w2)
        is Rail.Ring -> {
            val d = r.size.toPx()
            val top = (if (r.size > 10.dp) 3.dp else 4.dp).toPx()
            drawCircle(r.color, d / 2 - 1.dp.toPx(), Offset(x, top + d / 2), style = Stroke(2.dp.toPx()))
            r.line?.let {
                drawLine(it, Offset(x, top + d + 3.dp.toPx()), Offset(x, size.height), w2, pathEffect = dash)
            }
        }
        is Rail.Dotted -> drawLine(
            r.color, Offset(x, 0f), Offset(x, size.height), w2, cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, 5.dp.toPx())),
        )
        is Rail.Dashed -> drawLine(r.color, Offset(x, 0f), Offset(x, size.height), w2, pathEffect = dash)
        Rail.None -> Unit
    }
}

/** «Созвон: бюджет на IV квартал ★5 · Бета Групп» — ★ сразу после названия и не обрезается раньше клиента. */
fun entryTitle(title: String, useful: Int, client: String) = buildAnnotatedString {
    append(title)
    if (useful > 0) withStyle(SpanStyle(color = PointsPlus)) { append(" ★$useful") }
    if (client.isNotBlank()) withStyle(SpanStyle(color = Ink.TextMeta)) { append(" · $client") }
}

/** Монета режима по источнику отметки. */
fun DayAssembler.Source.decor(): ModeDecor = when (this) {
    DayAssembler.Source.SPORT -> ModeDecor.SPORT
    DayAssembler.Source.FOOD -> ModeDecor.FOOD
    DayAssembler.Source.MONEY -> ModeDecor.MONEY
    DayAssembler.Source.DELA -> ModeDecor.DELA
}
