package ru.zf.pravka

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.zf.pravka.core.Fmt
import ru.zf.pravka.core.MoneyCategories
import ru.zf.pravka.data.PhoneSweeper
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.Hairline
import ru.zf.pravka.ui.Ink
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.ScreenPad
import ru.zf.pravka.ui.Segments
import ru.zf.pravka.ui.scrollFade
import ru.zf.pravka.ui.tap
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

// Общая статистика, издание «Куда уходит день» (11.10.2026). Владелец принёс
// разбор, который Claude собрал по архиву Правки за 43 дня, и сказал: «я
// никогда не заглядывал в нашу статистику… давай уберём нашу, а поставим вот
// ровно такую». Прежняя вкладка (день в графиках, тот же день неделю назад,
// треугольник, коэффициенты) снята целиком; здесь — разделы того разбора по
// порядку, только живые и за выбранный отрезок:
//
//   средний день по часам (выбор: будни, выходные, любой день недели) ·
//   средние сутки · четыре больших числа · окно будней 10–17 с календарём ·
//   недели качелями и цена в теле · мастерская против работы · встречи ·
//   план тренировок против факта, борьба, вис, пробежки · вес и белок · еда на
//   главном рецепте, БЖУ и вещества · деньги по месяцам, баланс, ЗФ ·
//   телефон по неделям · как это читать.
//
// Все числа — `core/LifeStats.kt` (JVM-тесты), графики — `ui/LifeCharts.kt`.
// Гипотез «почему так» модель здесь не пишет: вывод «Что видно» — из чисел,
// без толкований. Отрезок кончается вчера: сегодняшний неполный день не в счёте.

private val PERIODS = listOf(4 to "4 недели", 6 to "6 недель", 13 to "3 месяца")
private val MONTHS = listOf("янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")
private fun f2(v: Double): String = String.format(Locale.US, "%.2f", v).replace('.', ',').trimEnd('0').trimEnd(',')
private fun pct(v: Double): String = "${(v * 100).roundToInt()}%"
private fun mins(v: Double): String = "${v.roundToInt()} мин"
private fun rubShort(kop: Long): String {
    val rub = kop / 100.0
    return when {
        kotlin.math.abs(rub) >= 1_000_000 -> f2(rub / 1_000_000) + " млн"
        kotlin.math.abs(rub) >= 1_000 -> "${(rub / 1000).roundToInt()} тыс."
        else -> "${rub.roundToInt()} ₽"
    }
}

@Composable
private fun Cap(text: String) {
    Text(text, style = LocalPravkaType.current.caption, color = Ink.TextMeta, modifier = Modifier.padding(bottom = 6.dp))
}
