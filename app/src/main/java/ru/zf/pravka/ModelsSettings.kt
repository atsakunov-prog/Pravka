package ru.zf.pravka

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.zf.pravka.data.ModelChoice
import ru.zf.pravka.data.ModelRoute
import ru.zf.pravka.data.Models
import ru.zf.pravka.data.Settings
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.RowRule
import ru.zf.pravka.ui.SummaryLine

// Группа «Модели» в настройках: какая модель и с каким усилием работает на
// каждой дороге в Claude. Дороги перечислены в ModelRoute — по режимам, с
// заводскими значениями, которые раньше были зашиты в код. Владелец: «сделай
// в настройках выбор моделей и выбор усилий, прямо отдельной графой».
//
// Чипы, а не выпадающие списки: варианта три и шесть, они видны разом и
// переключаются одним тапом — но только у раскрытой дороги; остальные
// свёрнуты в строку «Опус 5.5 · medium» (24.09.2026). Ряд чипов едет вбок,
// а не переносится: на узком внешнем экране Fold так не прыгает высота.

@Composable
internal fun ModelsSettings(app: PravkaApp) {
    // Область жизни приложения, а не композиции: тап по чипу и уход с экрана
    // не должны рвать запись в DataStore на полпути (см. AnthropicSettings).
    val scope = app.appScope
    val settings = app.settings
    // Раскрыта одна дорога за раз (24.09.2026): семнадцать дорог по девять
    // чипов — это 153 чипа на одном экране, и искать среди них свою было
    // труднее, чем выбирать. Теперь каждая дорога — строка-сводка.
    var open by remember { mutableStateOf<ModelRoute?>(null) }

    val byMode = ModelRoute.entries.groupBy { it.mode }
    byMode.entries.forEachIndexed { index, (mode, routes) ->
        PaperCard(
            label = "${mode.lowercase()} · ${routes.size}",
            info = if (index == 0) MODELS_INFO else null,
        ) {
            routes.forEachIndexed { i, route ->
                if (i > 0) RowRule()
                RouteRow(settings, scope, route, expanded = open == route) {
                    open = if (open == route) null else route
                }
            }
        }
    }
}

private const val MODELS_INFO =
    "Каждая дорога в Claude — своя модель и своё усилие. Заводские значения " +
        "действуют со следующего запроса, без пересборки. Усилие — глубина " +
        "размышлений: «по умолчанию» значит не передавать параметр (API берёт " +
        "medium у Опуса 5.5 и high у остальных), low быстрее и дешевле, xhigh и " +
        "max — для трудных случаев. Сонет до high включительно отвечает без " +
        "размышлений — правка это механика; xhigh и max включают их. Опус 5.5 и " +
        "Fable 5.1 думают всегда, Fable в два с половиной раза дороже Опуса и " +
        "оба умеют отказываться от текста — тогда придёт ошибка «модель отказалась». " +
        "Своё — краской, заводское — серым."

@Composable
private fun RouteRow(
    settings: Settings,
    scope: CoroutineScope,
    route: ModelRoute,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    // Flow создаётся один раз на дорогу: новый экземпляр на каждой
    // перекомпозиции переподписывал бы DataStore при каждом тапе.
    val flow = remember(route) { settings.modelChoiceFlow(route) }
    val choice by flow.collectAsState(initial = ModelChoice.defaultOf(route))
    val changed = !choice.isDefaultFor(route)

    SummaryLine(
        title = route.title,
        summary = Models.label(choice.model) + " · " + Models.effortLabel(choice.effort),
        expanded = expanded,
        onToggle = onToggle,
        changed = changed,
    ) {
        PaperHint(route.hint)
        ChipRow {
            for (model in Models.ALL) {
                PaperChip(
                    Models.label(model),
                    selected = choice.model == model,
                    onClick = { scope.launch { settings.setModel(route, model) } },
                )
            }
        }
        ChipRow {
            for (effort in Models.EFFORTS) {
                PaperChip(
                    Models.effortLabel(effort),
                    selected = choice.effort == effort,
                    onClick = { scope.launch { settings.setEffort(route, effort) } },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PaperHint(Models.priceLabel(choice.model))
            Spacer(Modifier.weight(1f))
            if (changed) {
                PaperTextButton(
                    "заводское: ${Models.label(route.defaultModel)} · ${Models.effortLabel(route.defaultEffort)}",
                    icon = Glyphs.Undo,
                    onClick = { scope.launch { settings.resetModelChoice(route) } },
                )
            }
        }
    }
}
