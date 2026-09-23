package ru.zf.pravka

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import ru.zf.pravka.core.MoneyCategories
import ru.zf.pravka.core.MoneyFormat
import ru.zf.pravka.core.MoneyStats
import ru.zf.pravka.ui.ChartSlice
import ru.zf.pravka.ui.DonutChart
import ru.zf.pravka.ui.LegendRow
import ru.zf.pravka.ui.MarkdownText
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperHint

// Панели вкладки «Деньги»: «Спросить Claude», категории с разбивкой по
// магазинам и паттерны. Сама вкладка (`MoneyTab.kt`) только раскладывает их.

@Composable
internal fun LegendDot(color: Color) {
    Box(Modifier.size(9.dp).background(color, CircleShape))
}

/**
 * «Спросить Claude» — поле наверху вкладки (владелец, 23.09.2026: «достаточно
 * высоко должен быть текстбокс, где можно спросить у Клода что-то про
 * расходы»). Голосом — нашим движком, как у кнопок; ответ — по выжимке
 * журнала за год и строкам за 90 дней (`MoneyContext`).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AskCard(app: PravkaApp) {
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var asked by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun send(q: String) {
        val text = q.trim()
        if (text.isEmpty() || busy) return
        busy = true
        asked = text
        answer = ""
        app.appScope.launch {
            answer = app.moneyEngine.ask(text).getOrElse { e -> "Не вышло: ${e.message}" }
            busy = false
        }
    }

    PaperCard(label = "спросить Claude") {
        OutlinedTextField(
            value = question,
            onValueChange = { question = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Сколько я трачу на кафе в месяц?") },
            maxLines = 4,
        )
        MoneyVoiceBar("ask")
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                startMoneyVoice(app, "ask", "спроси про деньги") { spoken -> question = spoken; send(spoken) }
            }) { Text("🎙 Голосом") }
            Spacer(Modifier.weight(1f))
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Button(enabled = !busy && question.isNotBlank(), onClick = { send(question) }) { Text("Спросить") }
        }
        if (answer.isEmpty() && !busy) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in listOf("На что уходит больше всего?", "Что выросло за полгода?", "Сколько стоят кружки детей?", "Сколько мелочи на кафе и такси?")) {
                    AssistChip(onClick = { question = s; send(s) }, label = { Text(s, style = MaterialTheme.typography.labelSmall) })
                }
            }
        }
        if (answer.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            PaperHint("«$asked»")
            Spacer(Modifier.height(4.dp))
            MarkdownText(answer)
            TextButton(onClick = { answer = ""; question = "" }) { Text("Новый вопрос") }
        }
    }
}

/**
 * Категории периода: донат по группам (цвет держится за группой) и строки
 * категорий с полосой доли и изменением к прошлому периоду; тап по строке
 * раскрывает её по магазинам и получателям — «Продукты» → Яндекс Лавка,
 * Азбука Вкуса, ВкусВилл (владелец, 23.09.2026).
 */
@Composable
internal fun CategoriesCard(cats: List<MoneyStats.Category>, spentKop: Long) {
    var open by remember { mutableStateOf(setOf<String>()) }
    PaperCard(label = "куда ушло") {
        if (cats.isEmpty()) {
            PaperHint("За этот период трат нет.")
            return@PaperCard
        }
        val groups = MoneyStats.groups(cats)
        Row(verticalAlignment = Alignment.CenterVertically) {
            DonutChart(
                slices = groups.map { (g, kop) -> ChartSlice(g, kop / 100f, groupColor(g)) },
                size = 128.dp,
                thickness = 18.dp,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(MoneyFormat.short(spentKop), style = MaterialTheme.typography.titleMedium)
                    PaperHint("₽")
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                for ((g, kop) in groups) {
                    LegendRow(groupColor(g), g, MoneyFormat.short(kop), sub = pct(kop, spentKop))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        val peak = cats.maxOf { it.kop }.coerceAtLeast(1L)
        for (c in cats) {
            val expanded = c.key in open
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { open = if (expanded) open - c.key else open + c.key }
                    .padding(vertical = 5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LegendDot(moneyCategoryColor(c.key))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        (if (c.key.isBlank()) "Без категории" else MoneyCategories.title(c.key)) + if (expanded) "  ▾" else "  ▸",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    delta(c.kop, c.prevKop)?.let { (text, up) ->
                        Text(text, style = MaterialTheme.typography.labelSmall, color = if (up) spentColor() else incomeColor())
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(MoneyFormat.rub(c.kop), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(3.dp))
                ShareBar(c.kop.toFloat() / peak, moneyCategoryColor(c.key), Modifier.padding(start = 17.dp))
                if (expanded) {
                    Spacer(Modifier.height(4.dp))
                    for (m in c.merchants.take(12)) {
                        Row(Modifier.fillMaxWidth().padding(start = 17.dp, top = 2.dp)) {
                            Text(m.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            PaperHint(if (m.count > 1) "${m.count}× " else "")
                            Text(MoneyFormat.rub(m.kop), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (c.merchants.size > 12) PaperHint("   и ещё ${c.merchants.size - 12}")
                }
            }
        }
    }
}

/** «▲ 12 %» к прошлому периоду; мелочь (меньше 5 %) и новое не показываем — это шум. */
private fun delta(now: Long, prev: Long): Pair<String, Boolean>? {
    if (prev <= 0L) return null
    val d = (now - prev).toDouble() / prev
    if (kotlin.math.abs(d) < 0.05) return null
    return (if (d > 0) "▲ " else "▼ ") + "${kotlin.math.abs(Math.round(d * 100))} %" to (d > 0)
}

private fun pct(part: Long, whole: Long): String =
    if (whole <= 0) "" else "${Math.round(part * 100.0 / whole)} %"

/**
 * Паттерны от Claude: год трат глазами CFO — структура, ежемесячный ритм,
 * тренды, утечки и три совета. Считаются по кнопке и лежат в журнале: вкладка
 * не платит за них при каждом открытии.
 */
@Composable
internal fun PatternsCard(app: PravkaApp, insight: String, insightTs: Long) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    PaperCard(
        label = "паттерны",
        trailing = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                error = ""
                scope.launch {
                    app.moneyEngine.patterns().onFailure { e -> error = "Не вышло: ${e.message}" }
                    busy = false
                }
            }) { Text(if (busy) "Claude смотрит…" else if (insight.isBlank()) "Найти" else "Обновить") }
        },
    ) {
        if (insight.isBlank() && !busy) {
            PaperHint("Claude посмотрит на год трат и скажет, что повторяется: ритм месяцев, что растёт, где утекает мелочь, и даст три совета с цифрами.")
        }
        if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            PaperHint("это минута-другая: год данных")
        }
        if (error.isNotBlank()) PaperHint(error, color = MaterialTheme.colorScheme.error)
        if (insight.isNotBlank()) {
            PaperHint("посчитано " + SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru")).format(Date(insightTs)))
            Spacer(Modifier.height(4.dp))
            MarkdownText(insight)
        }
    }
}
