package ru.zf.pravka

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import ru.zf.pravka.data.ModelChoice
import ru.zf.pravka.data.ModelRoute
import ru.zf.pravka.data.Models
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.SignedColumns

// Стоимость обращений к API — экран за долларом в шапке любой вкладки.
// Владелец (15.09.2026): «у меня подозрение, что это стоимость только правки —
// надо обязательно проверить, что туда включается все обращения по API».
// Проверено: каждая дорога к Claude пишет в Stats (recordSuccess у чистки,
// recordAux у всего остального) — Правка и её ответы, обучение и словарь,
// разбор фраз Засечки, Разноска, Тело и Еда, тренер и подсказки, план из
// Notion, эвалы, сверка паттернов при синке Notion. Список ниже — не
// обещание, а перечень тех самых вызовов.

@Composable
internal fun CostScreen(app: PravkaApp) {
    val snapshot by app.stats.snapshotFlow.collectAsState(initial = null)
    var daily by remember { mutableStateOf<List<Pair<String, Double>>>(emptyList()) }
    var routes by remember { mutableStateOf<List<Pair<String, Double>>>(emptyList()) }
    LaunchedEffect(snapshot?.costTodayUsd) {
        daily = runCatching { app.stats.dailyCosts(14) }.getOrDefault(emptyList())
        routes = runCatching { app.stats.routeCosts(7) }.getOrDefault(emptyList())
    }
    val ru = remember { Locale.forLanguageTag("ru") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        snapshot?.let { s ->
            PaperCard(label = stringResource(R.string.stats_cost_header)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "$%.2f".format(Locale.US, s.costTodayUsd),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.stats_cost_today).lowercase(ru),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                CostLine(stringResource(R.string.stats_cost_week), "$%.2f".format(Locale.US, s.costWeekUsd))
                CostLine(stringResource(R.string.stats_cost_month), "$%.2f".format(Locale.US, s.costMonthUsd))
                CostLine(stringResource(R.string.stats_cost_total), "$%.2f".format(Locale.US, s.costTotalUsd))
                Spacer(Modifier.height(6.dp))
                CostLine(stringResource(R.string.stats_tokens), "%,d / %,d".format(ru, s.tokensIn, s.tokensOut))
                // Кэш промпта: сколько входа пришло из кэша. Чтение стоит десятую
                // часть (у Fable — сороковую), запись — вдвое: доля показывает,
                // окупается ли кэш, а не только что он «включён».
                val cachePct = if (s.tokensIn > 0) 100.0 * s.cacheReadTokens / s.tokensIn else 0.0
                CostLine(
                    "Кэш промпта (прочитано / записано)",
                    "%,d (%.0f%%) / %,d".format(ru, s.cacheReadTokens, cachePct, s.cacheWriteTokens),
                )
            }
        }

        if (daily.isNotEmpty()) {
            PaperCard(label = "по дням, две недели") {
                // Свежий день — справа, как на календаре.
                val asc = daily.asReversed()
                val bar = MaterialTheme.colorScheme.primary
                SignedColumns(
                    values = asc.map { it.second.toFloat() },
                    colorOf = { bar },
                    labels = asc.map { it.first.substring(8) },
                    height = 110.dp,
                    valueText = { v -> if (v >= 0.005f) "%.2f".format(Locale.US, v) else "" },
                )
                Spacer(Modifier.height(6.dp))
                val sum = daily.sumOf { it.second }
                PaperHint("За 14 дней: $%.2f · в среднем $%.2f в день".format(Locale.US, sum, sum / daily.size))
            }
        }

        // Куда уходят деньги (17.09.2026; владелец: «за день шесть долларов, и
        // растёт каждый день — что-то не то»). Общая сумма на это не отвечает,
        // разложенная по дорогам с моделью — отвечает: Засечка на Fable стоила
        // две трети дня, и это было не видно ниоткуда.
        if (routes.isNotEmpty()) {
            PaperCard(label = "по дорогам, семь дней") {
                val total = routes.sumOf { it.second }
                for ((key, usd) in routes) {
                    val route = ModelRoute.entries.firstOrNull { it.key == key }
                    val choice = route?.let { r ->
                        app.settings.modelChoiceFlow(r).collectAsState(initial = ModelChoice.defaultOf(r)).value
                    }
                    val title = route?.let { "${it.mode} · ${it.title}" } ?: when (key) {
                        "eval" -> "Правка · Эвал золотого набора"
                        else -> key
                    }
                    val model = choice?.let { " · " + Models.label(it.model) + (if (it.effort.isNotBlank()) " ${it.effort}" else "") }.orEmpty()
                    val share = if (total > 0) " (%.0f%%)".format(Locale.US, 100 * usd / total) else ""
                    CostLine("$title$model", "$%.2f$share".format(Locale.US, usd))
                }
                Spacer(Modifier.height(6.dp))
                PaperHint(
                    "Считается с сборки 17.09: дни до неё — только в общей сумме. Дорогая строка — " +
                        "повод сменить модель или усилие в настройках, группа «Модели»: Fable думает всегда и " +
                        "стоит вдвое дороже Опуса на входе и выходе."
                )
            }
        }

        PaperCard(label = "что сюда входит") {
            PaperHint(
                "Все обращения к Claude: чистка и ответы Правки, «сильнее», обучение и " +
                    "поиск словаря по истории, эвалы промпта; разбор фраз Засечки; Разноска; " +
                    "Тело и Еда — подходы, зарядка, КБЖУ по словам, снимку и штрихкоду; " +
                    "тренер и подсказки между подходами; план и правила блока из Notion; " +
                    "сверка паттернов при синке Notion. Считается по токенам и ценам модели, " +
                    "кэш промпта — по своей цене."
            )
        }
    }
}

@Composable
private fun CostLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
