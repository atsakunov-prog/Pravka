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

// Группа «Модели» в настройках: какая модель и с каким усилием работает на
// каждой дороге в Claude. Дороги перечислены в ModelRoute — по режимам, с
// заводскими значениями, которые раньше были зашиты в код. Владелец: «сделай
// в настройках выбор моделей и выбор усилий, прямо отдельной графой».
//
// Чипы, а не выпадающие списки: варианта три и шесть, они видны разом и
// переключаются одним тапом. FlowRow — чтобы шесть усилий переносились на
// второй ряд на узком внешнем экране Fold, а не уезжали за край.

@Composable
internal fun ModelsSettings(app: PravkaApp) {
    // Область жизни приложения, а не композиции: тап по чипу и уход с экрана
    // не должны рвать запись в DataStore на полпути (см. CommonSettings).
    val scope = app.appScope
    val settings = app.settings

    HintText(
        "Каждая дорога в Claude — своя модель и своё усилие. Заводские значения " +
            "— те, что были зашиты в код; действует со следующего запроса, без " +
            "пересборки. Усилие — глубина размышлений: «по умолчанию» значит не " +
            "передавать параметр (API берёт high), low быстрее и дешевле, xhigh и " +
            "max — для трудных случаев. Сонет до high включительно отвечает без " +
            "размышлений — правка это механика; xhigh и max включают их. Fable 5.1 " +
            "вдвое дороже Опуса и умеет отказываться от текста — тогда придёт " +
            "ошибка «модель отказалась»."
    )

    var lastMode = ""
    for (route in ModelRoute.entries) {
        if (route.mode != lastMode) {
            lastMode = route.mode
            Spacer(Modifier.height(16.dp))
            Text(route.mode, style = MaterialTheme.typography.titleSmall)
        } else {
            Spacer(Modifier.height(6.dp))
            HorizontalDivider()
        }
        RouteRow(settings, scope, route)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteRow(settings: Settings, scope: CoroutineScope, route: ModelRoute) {
    // Flow создаётся один раз на дорогу: новый экземпляр на каждой
    // перекомпозиции переподписывал бы DataStore при каждом тапе.
    val flow = remember(route) { settings.modelChoiceFlow(route) }
    val choice by flow.collectAsState(initial = ModelChoice.defaultOf(route))

    Spacer(Modifier.height(8.dp))
    Text(route.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    HintText(route.hint)
    Spacer(Modifier.height(4.dp))
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (model in Models.ALL) {
            FilterChip(
                selected = choice.model == model,
                onClick = { scope.launch { settings.setModel(route, model) } },
                label = { Text(Models.label(model)) },
            )
        }
    }
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (effort in Models.EFFORTS) {
            FilterChip(
                selected = choice.effort == effort,
                onClick = { scope.launch { settings.setEffort(route, effort) } },
                label = { Text(Models.effortLabel(effort)) },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        HintText(Models.priceLabel(choice.model))
        if (!choice.isDefaultFor(route)) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { scope.launch { settings.resetModelChoice(route) } }) {
                Text(
                    "заводское: ${Models.label(route.defaultModel)}, " +
                        Models.effortLabel(route.defaultEffort),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
