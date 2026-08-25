package ru.zf.pravka.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlinx.coroutines.launch
import ru.zf.pravka.core.DictMode
import ru.zf.pravka.data.PromptStore
import ru.zf.pravka.desktop.Controller
import ru.zf.pravka.desktop.DesktopApp
import ru.zf.pravka.desktop.data.DesktopSettings
import ru.zf.pravka.desktop.input.Hotkeys

// Окно Правки: те же вкладки, что на телефоне, - только словарь здесь наконец
// правится с настоящей клавиатуры.
@Composable
fun MainWindowContent(controller: Controller, onHotkeysChanged: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val titles = listOf("Настройки", "Словарь", "Промпты", "Расшифровки", "Статистика")

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            titles.forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
            }
        }
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            when (tab) {
                0 -> SettingsTab(controller, onHotkeysChanged)
                1 -> DictionaryTab()
                2 -> PromptsTab()
                3 -> TranscriptsTab()
                else -> StatsTab()
            }
        }
    }
}

@Composable
private fun SettingsTab(controller: Controller, onHotkeysChanged: () -> Unit) {
    val app = DesktopApp
    val scope = rememberCoroutineScope()
    val settings = app.settings

    val apiKey by settings.apiKeyFlow.collectAsState()
    val whisperUrl by settings.whisperUrlFlow.collectAsState()
    val whisperModel by settings.whisperModelFlow.collectAsState()
    val autoClean by settings.autoCleanFlow.collectAsState()
    val keepAudio by settings.keepAudioFlow.collectAsState()
    val dictHint by settings.dictHintFlow.collectAsState()
    val prose by settings.proseModeFlow.collectAsState()
    val rulesInProse by settings.rulesInProseFlow.collectAsState()
    val hotkeys by settings.hotkeysFlow.collectAsState()

    var keyDraft by remember(apiKey) { mutableStateOf(apiKey) }
    var urlDraft by remember(whisperUrl) { mutableStateOf(whisperUrl) }
    var modelDraft by remember(whisperModel) { mutableStateOf(whisperModel) }
    var health by remember { mutableStateOf("") }
    var hkDictate by remember(hotkeys) { mutableStateOf(hotkeys.dictate) }
    var hkClean by remember(hotkeys) { mutableStateOf(hotkeys.clean) }
    var hkMenu by remember(hotkeys) { mutableStateOf(hotkeys.menu) }
    var hkUndo by remember(hotkeys) { mutableStateOf(hotkeys.undo) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        SectionTitle("Ключ Anthropic")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = keyDraft,
                onValueChange = { keyDraft = it },
                label = { Text("sk-ant-...") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { settings.setApiKey(keyDraft) }) { Text("Сохранить") }
        }
        Hint("Ключ лежит в %APPDATA%\\Pravka\\settings.json и никуда не уезжает.")

        SectionTitle("Распознаватель")
        OutlinedTextField(
            value = urlDraft,
            onValueChange = { urlDraft = it },
            label = { Text("Адрес сервера") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = modelDraft,
                onValueChange = { modelDraft = it },
                label = { Text("Модель") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                settings.setWhisperUrl(urlDraft)
                settings.setWhisperModel(modelDraft)
                scope.launch {
                    health = app.whisper.health(urlDraft).fold(
                        onSuccess = { it },
                        onFailure = { "Не отвечает: ${it.message}" },
                    )
                }
            }) { Text("Сохранить и проверить") }
        }
        if (health.isNotBlank()) Hint(health)
        Hint("Сервер поднимается скриптами из scripts/whisper.")

        SectionTitle("Поведение")
        Toggle("Причёсывать надиктованное сразу", autoClean) { settings.setAutoClean(it) }
        Toggle("Подсказывать распознавателю словарь", dictHint) { settings.setDictHint(it) }
        Toggle("Хранить записи (WAV)", keepAudio) { settings.setKeepAudio(it) }
        Toggle("Художественный режим", prose) { settings.setProseMode(it) }
        Toggle("Правила и в художественном режиме", rulesInProse) { settings.setRulesInProse(it) }

        SectionTitle("Горячие клавиши")
        HotkeyField("Диктовка", hkDictate) { hkDictate = it }
        HotkeyField("Причесать", hkClean) { hkClean = it }
        HotkeyField("Меню правки", hkMenu) { hkMenu = it }
        HotkeyField("Отменить", hkUndo) { hkUndo = it }
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            settings.setHotkeys(DesktopSettings.Hotkeys(hkDictate, hkClean, hkMenu, hkUndo))
            onHotkeysChanged()
        }) { Text("Применить") }
        Hint(
            "Пишутся как ctrl+alt+space. Диктовка: зажал - говоришь, отпустил - " +
                "текст встал в поле; короткий тап оставляет запись включённой до " +
                "второго нажатия."
        )

        SectionTitle("Проверка")
        Row {
            OutlinedButton(onClick = { controller.clean() }) { Text("Причесать буфер") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { controller.reset() }) { Text("Сброс") }
        }
    }
}

@Composable
private fun DictionaryTab() {
    val app = DesktopApp
    val scope = rememberCoroutineScope()
    val entries by app.dictionaryStore.entriesFlow.collectAsState()

    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(DictMode.HARD) }

    // Первое обращение поднимает файл с диска и наполняет поток.
    remember { scope.launch { app.dictionaryStore.all() } }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(from, { from = it }, label = { Text("Слышится") }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(to, { to = it }, label = { Text("Пишется") }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(note, { note = it }, label = { Text("Пояснение") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (m in DictMode.entries) {
                TextButton(onClick = { mode = m }) {
                    Text(
                        text = modeTitle(m),
                        color = if (mode == m) PravkaIcon.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = from.isNotBlank(),
                onClick = {
                    val f = from; val t = to; val n = note; val m = mode
                    from = ""; to = ""; note = ""
                    DesktopApp.scope.launch { app.dictionaryStore.add(f, t, m, n) }
                },
            ) { Text("Добавить") }
        }
        Hint("HARD - заменить всегда, HINT - подсказать модели, PROTECT - не трогать.")
        Divider(Modifier.padding(vertical = 8.dp))
        Text("Записей: ${entries.size}", fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(entries, key = { it.id }) { entry ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(entry.enabled, onCheckedChange = { on ->
                        DesktopApp.scope.launch { app.dictionaryStore.update(entry.copy(enabled = on)) }
                    })
                    Text(modeTitle(entry.mode), Modifier.width(80.dp), fontSize = 12.sp, color = PravkaIcon.accent)
                    Text(entry.from, Modifier.weight(1f), fontSize = 13.sp)
                    Text(entry.to, Modifier.weight(1f), fontSize = 13.sp)
                    Text(entry.note, Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${entry.hits}", Modifier.width(48.dp), fontSize = 12.sp)
                    TextButton(onClick = { DesktopApp.scope.launch { app.dictionaryStore.delete(entry.id) } }) {
                        Text("Удалить", fontSize = 12.sp)
                    }
                }
                Divider()
            }
        }
    }
}

private fun modeTitle(mode: DictMode) = when (mode) {
    DictMode.HARD -> "Замена"
    DictMode.HINT -> "Подсказка"
    DictMode.PROTECT -> "Не трогать"
}

@Composable
private fun PromptsTab() {
    val app = DesktopApp
    val scope = rememberCoroutineScope()
    var id by remember { mutableStateOf(PromptStore.PromptId.CLEAN_CLAUDE) }
    var text by remember { mutableStateOf("") }
    var loadedFor by remember { mutableStateOf<PromptStore.PromptId?>(null) }

    if (loadedFor != id) {
        loadedFor = id
        scope.launch { text = app.promptStore.effective(id) }
    }

    Column(Modifier.fillMaxSize()) {
        Row {
            for (candidate in PromptStore.PromptId.entries) {
                TextButton(onClick = { id = candidate }) {
                    Text(
                        candidate.storageKey,
                        color = if (id == candidate) PravkaIcon.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = { val t = text; val i = id; DesktopApp.scope.launch { app.promptStore.setOverride(i, t) } }) {
                Text("Сохранить")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                val i = id
                DesktopApp.scope.launch {
                    app.promptStore.resetToFactory(i)
                    text = app.promptStore.factory(i)
                }
            }) { Text("Вернуть заводской") }
        }
        Hint("Заводские тексты живут в ядре и обновляются с программой; здесь - только твои правки.")
    }
}

@Composable
private fun TranscriptsTab() {
    val entries by DesktopApp.transcripts.lastFlow.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Text("Последние распознавания: ${entries.size}", fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(entries) { e ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        val audio = e.audioMs / 1000.0
                        val work = e.transcribeMs / 1000.0
                        val rate = if (work > 0) audio / work else 0.0
                        Text(
                            String.format(
                                Locale.US,
                                "%s · %s · аудио %.1f с · распознано за %.1f с (x%.1f) · %d символов",
                                e.at, e.engine, audio, work, rate, e.chars,
                            ),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (e.error != null) {
                            Text(e.error, fontSize = 13.sp, color = androidx.compose.ui.graphics.Color(0xFFD64545))
                        } else {
                            Spacer(Modifier.height(4.dp))
                            Text(e.text, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsTab() {
    val snapshot by DesktopApp.stats.snapshotFlow.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatRow("Правок всего", snapshot.total.toString())
        StatRow("Без изменений", snapshot.unchanged.toString())
        StatRow("Ошибок", snapshot.errors.toString())
        StatRow("Символов обработано", snapshot.charsProcessed.toString())
        StatRow("Среднее время ответа", "${snapshot.averageLatencyMs} мс")
        StatRow("Токенов входящих", snapshot.tokensIn.toString())
        StatRow("Токенов исходящих", snapshot.tokensOut.toString())
        StatRow("Потрачено сегодня", String.format(Locale.US, "$%.3f", snapshot.costTodayUsd))
        StatRow("Потрачено всего", String.format(Locale.US, "$%.2f", snapshot.costTotalUsd))
        Hint("Считается только эта машина; сведение с телефоном - следующий этап (docs/workstation.md).")
    }
}

@Composable
private fun StatRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(title, Modifier.weight(1f), fontSize = 13.sp)
        Text(value, fontSize = 13.sp, color = PravkaIcon.accent)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(text, fontSize = 15.sp, color = PravkaIcon.accent)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Hint(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Toggle(title: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(value, onCheckedChange = onChange)
        Text(title, fontSize = 13.sp)
    }
}

@Composable
private fun HotkeyField(title: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Text(title, Modifier.width(130.dp), fontSize = 13.sp)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            modifier = Modifier.width(240.dp),
            isError = Hotkeys.parse(value) == null,
        )
        Spacer(Modifier.width(8.dp))
        Text(Hotkeys.describe(value), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
