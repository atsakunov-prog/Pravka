package ru.zf.slushalka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.zf.slushalka.BuildConfig
import ru.zf.slushalka.SlushalkaApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: SlushalkaApp, onBack: () -> Unit, onPickTree: () -> Unit) {
    val state = app.state
    val prefs by state.prefs.collectAsState()
    val books by state.books.collectAsState()
    val scope = rememberCoroutineScope()

    var key by remember { mutableStateOf(prefs.apiKey) }
    var profile by remember { mutableStateOf(prefs.profile) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            Section("Книги")
            Text(
                if (prefs.libraryUri.isBlank()) "Папка не выбрана"
                else "Найдено книг: ${books.size}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPickTree) { Text("Выбрать папку") }
                TextButton(onClick = { state.rescan() }) { Text("Перечитать") }
            }

            Section("Кто слушает")
            OutlinedTextField(
                value = profile,
                onValueChange = {
                    profile = it
                    scope.launch { state.settings.setProfile(it) }
                },
                label = { Text("Имя для синхронизации") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Note(
                "В корне библиотеки заводится папка «_Слушалка», и это имя становится твоей " +
                    "дорожкой в ней. Если папка синхронизируется между устройствами, книга " +
                    "продолжается там, где остановилась, а на карточке видно, докуда дошёл второй."
            )
            Toggle("Синхронизировать позиции", prefs.syncPositions) {
                scope.launch { state.settings.setSyncPositions(it) }
            }

            Section("Плеер")
            Text("Перемотка кнопками: ${prefs.skipSec} с", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 15, 20, 30, 60).forEach { s ->
                    FilterChip(
                        selected = prefs.skipSec == s,
                        onClick = { scope.launch { state.settings.setSkipSec(s) } },
                        label = { Text("$s") },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Toggle("Откатываться назад после паузы", prefs.autoRewind) {
                scope.launch { state.settings.setAutoRewind(it) }
            }
            Note(
                "Через пять минут паузы книга отматывается на три секунды, через неделю - " +
                    "на полминуты: иначе включаешься в середину фразы."
            )
            Toggle("Проглатывать тишину", prefs.skipSilence) {
                scope.launch { state.settings.setSkipSilence(it) }
                app.player.setSkipSilence(it)
            }

            Section("Вопросы")
            OutlinedTextField(
                value = key,
                onValueChange = {
                    key = it
                    scope.launch { state.settings.setApiKey(it) }
                },
                label = { Text("Ключ Anthropic") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Note("Ключ живёт только на этом телефоне - как в Правке, без всяких прокси.")

            Spacer(Modifier.height(8.dp))
            NumberSlider(
                label = { "Страниц в вопрос: ${it.toInt()}" },
                value = prefs.contextPages.toFloat(),
                range = 1f..20f,
                steps = 18,
            ) { scope.launch { state.settings.setContextPages(it.toInt()) } }
            NumberSlider(
                label = { "Запас против спойлера: ${it.toInt()} мин" },
                value = (prefs.spoilerMarginSec / 60).toFloat(),
                range = 0f..10f,
                steps = 9,
            ) { scope.launch { state.settings.setSpoilerMargin(it.toInt() * 60) } }
            Note(
                "Текст режется не по текущей секунде, а на столько раньше: привязка " +
                    "приблизительная, и ошибаться она должна в сторону уже услышанного."
            )
            Toggle("Ответ вслух", prefs.speakAnswers) {
                scope.launch { state.settings.setSpeakAnswers(it) }
            }
            Toggle("Ставить книгу на паузу на время вопроса", prefs.pauseWhileAsking) {
                scope.launch { state.settings.setPauseWhileAsking(it) }
            }
            Toggle("Отдавать всю книгу до текущего места", prefs.wholeBookContext) {
                scope.launch { state.settings.setWholeBookContext(it) }
            }
            Note(
                "Дороже, но отвечает на «кто это?» про героя из первой главы. Кэш держится час, " +
                    "поэтому второй вопрос в тот же вечер стоит копейки."
            )
            Spacer(Modifier.height(6.dp))
            NumberSlider(
                label = { "Пересказ предлагать после перерыва: ${it.toInt()} ч" },
                value = prefs.recapAfterHours.toFloat(),
                range = 1f..48f,
                steps = 46,
            ) { scope.launch { state.settings.setRecapAfterHours(it.toInt()) } }
            Text(
                "Потрачено на вопросы: %.2f $".format(app.askLog.totalUsd()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section("О приложении")
            Text(
                "Слушалка ${BuildConfig.VERSION_NAME}, сборка ${BuildConfig.BUILD_TIME}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(22.dp))
    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium)
    HorizontalDivider(Modifier.padding(top = 4.dp, bottom = 10.dp))
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun Toggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Ползунок, который пишет настройку **один раз** - когда палец отпустили.
 * Запись на каждый пиксель протаскивания давала бы полсотни обращений к
 * DataStore на одно движение.
 */
@Composable
private fun NumberSlider(
    label: (Float) -> String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onCommit: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Text(label(local), style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = local,
        onValueChange = { local = it },
        onValueChangeFinished = { onCommit(local) },
        valueRange = range,
        steps = steps,
    )
}
