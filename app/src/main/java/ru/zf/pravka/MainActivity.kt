package ru.zf.pravka

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.zf.pravka.core.DictEntry
import ru.zf.pravka.core.DictMode
import ru.zf.pravka.core.Prompts
import ru.zf.pravka.data.DictionaryStore
import ru.zf.pravka.data.HistoryLog
import ru.zf.pravka.data.PromptStore
import ru.zf.pravka.data.Settings
import ru.zf.pravka.data.Stats
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.trigger.PravkaAccessibilityService
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.PravkaTheme

// Four tabs: Settings, Dictionary, Prompts, Statistics.
// Editorial "proofreader" design: paper, ink, red pen (ui/Theme.kt).
class MainActivity : ComponentActivity() {

    private val serviceEnabled = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PravkaApp
        setContent {
            PravkaTheme {
                MainScreen(
                    app = app,
                    settings = app.settings,
                    promptStore = app.promptStore,
                    stats = app.stats,
                    dictionaryStore = app.dictionaryStore,
                    historyLog = app.historyLog,
                    dictMiner = app.dictMiner,
                    learnStore = app.learnStore,
                    rulesStore = app.rulesStore,
                    transcriptionLog = app.transcriptionLog,
                    liveDraft = app.liveDraft,
                    eventLog = app.eventLog,
                    whisperProvider = app.whisperProvider,
                    recordings = app.recordings,
                    serviceEnabled = serviceEnabled.value,
                    onOpenAccessibilitySettings = {
                        startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        serviceEnabled.value = ru.zf.pravka.trigger.PravkaAccessibilityService.instance != null
    }
}

private enum class Tab(val titleRes: Int) {
    SETTINGS(R.string.tab_settings),
    DICTIONARY(R.string.tab_dictionary),
    PROMPTS(R.string.tab_prompts),
    TRANSCRIPTS(R.string.tab_transcripts),
    LEARNING(R.string.tab_learning),
    LOGS(R.string.tab_logs),
    STATS(R.string.tab_stats),
}

@Composable
private fun MainScreen(
    app: PravkaApp,
    settings: Settings,
    promptStore: PromptStore,
    stats: Stats,
    dictionaryStore: DictionaryStore,
    historyLog: HistoryLog,
    dictMiner: ru.zf.pravka.provider.DictMiner,
    learnStore: ru.zf.pravka.data.LearnStore,
    rulesStore: ru.zf.pravka.data.RulesStore,
    transcriptionLog: ru.zf.pravka.data.TranscriptionLog,
    liveDraft: ru.zf.pravka.data.LiveDraft,
    eventLog: ru.zf.pravka.data.EventLog,
    whisperProvider: ru.zf.pravka.provider.WhisperProvider,
    recordings: ru.zf.pravka.data.Recordings,
    serviceEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
) {
    var tab by remember { mutableStateOf(Tab.SETTINGS) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.SETTINGS,
                    onClick = { tab = Tab.SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(stringResource(Tab.SETTINGS.titleRes)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.DICTIONARY,
                    onClick = { tab = Tab.DICTIONARY },
                    icon = { Icon(Icons.Filled.List, contentDescription = null) },
                    label = { Text(stringResource(Tab.DICTIONARY.titleRes)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.PROMPTS,
                    onClick = { tab = Tab.PROMPTS },
                    icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    label = { Text(stringResource(Tab.PROMPTS.titleRes)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.TRANSCRIPTS,
                    onClick = { tab = Tab.TRANSCRIPTS },
                    icon = { Icon(painterResource(R.drawable.ic_transcripts), contentDescription = null) },
                    label = { Text(stringResource(Tab.TRANSCRIPTS.titleRes)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.LEARNING,
                    onClick = { tab = Tab.LEARNING },
                    icon = { Icon(Icons.Filled.Star, contentDescription = null) },
                    label = { Text(stringResource(Tab.LEARNING.titleRes)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.LOGS,
                    onClick = { tab = Tab.LOGS },
                    icon = { Icon(Icons.Filled.Build, contentDescription = null) },
                    label = { Text(stringResource(Tab.LOGS.titleRes)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.STATS,
                    onClick = { tab = Tab.STATS },
                    icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                    label = { Text(stringResource(Tab.STATS.titleRes)) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            when (tab) {
                Tab.SETTINGS -> SettingsTab(settings, whisperProvider, recordings, serviceEnabled, onOpenAccessibilitySettings)
                Tab.DICTIONARY -> DictionaryTab(dictionaryStore, historyLog, dictMiner)
                Tab.PROMPTS -> PromptsTab(promptStore)
                Tab.TRANSCRIPTS -> TranscriptsTab(transcriptionLog, liveDraft, eventLog)
                Tab.LEARNING -> LearningTab(app)
                Tab.LOGS -> LogsTab(app)
                Tab.STATS -> StatsTab(stats, historyLog)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared design pieces
// ---------------------------------------------------------------------------

/** The wide "П" mark - same as the launcher icon and the floating button. */
@Composable
private fun BrandMark(size: androidx.compose.ui.unit.Dp, textSize: androidx.compose.ui.unit.TextUnit) {
    Box(
        modifier = Modifier
            .size(size)
            .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "П",
            fontSize = textSize,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun ScreenTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall)
}

/** Small uppercase label in the accent color above a card. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(Locale.forLanguageTag("ru")),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun SectionCard(
    label: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (label != null) SectionLabel(label)
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

@Composable
private fun SettingsTab(
    settings: Settings,
    whisperProvider: ru.zf.pravka.provider.WhisperProvider,
    recordings: ru.zf.pravka.data.Recordings,
    serviceEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var savedMark by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    val fabSize by settings.fabSizeFlow.collectAsState(initial = Settings.FAB_SIZE_DEFAULT)
    val fabAlpha by settings.fabAlphaFlow.collectAsState(initial = Settings.FAB_ALPHA_DEFAULT)
    var sizeSlider by remember(fabSize) { mutableStateOf(fabSize.toFloat()) }
    var alphaSlider by remember(fabAlpha) { mutableStateOf(fabAlpha) }

    LaunchedEffect(Unit) {
        apiKey = settings.apiKey()
        loaded = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Brand header
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(size = 48.dp, textSize = 26.sp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.build_info, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.BUILD_TIME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Accessibility service status
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (serviceEnabled) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
            ),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(if (serviceEnabled) R.string.service_status_on else R.string.service_status_off),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (serviceEnabled) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                )
                if (!serviceEnabled) {
                    Button(onClick = onOpenAccessibilitySettings) {
                        Text(stringResource(R.string.service_enable))
                    }
                    Text(
                        stringResource(R.string.service_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        SectionCard(label = stringResource(R.string.settings_api_key_title)) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; savedMark = false },
                enabled = loaded,
                singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                label = { Text(stringResource(R.string.settings_api_key_label)) },
                trailingIcon = {
                    TextButton(onClick = { keyVisible = !keyVisible }) {
                        Text(stringResource(if (keyVisible) R.string.settings_hide else R.string.settings_show))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            HintText(stringResource(R.string.settings_api_key_hint))
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    scope.launch {
                        settings.setApiKey(apiKey)
                        savedMark = true
                    }
                },
                enabled = loaded,
            ) {
                Text(stringResource(if (savedMark) R.string.settings_saved else R.string.settings_save))
            }
        }

        SectionCard(label = stringResource(R.string.settings_fab_title)) {
            Text(stringResource(R.string.settings_fab_size, sizeSlider.toInt()), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = sizeSlider,
                onValueChange = { sizeSlider = it },
                onValueChangeFinished = { scope.launch { settings.setFabSize(sizeSlider.toInt()) } },
                valueRange = 36f..72f,
            )
            Text(
                stringResource(R.string.settings_fab_alpha, (alphaSlider * 100).toInt()),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = alphaSlider,
                onValueChange = { alphaSlider = it },
                onValueChangeFinished = { scope.launch { settings.setFabAlpha(alphaSlider) } },
                valueRange = 0.15f..1f,
            )
        }

        SpeechSection(settings, whisperProvider)

        SectionCard(label = "Правка текста") {
            val prose by settings.proseModeFlow.collectAsState(initial = false)
            val convo by settings.convoContextFlow.collectAsState(initial = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = prose,
                    onCheckedChange = { on -> scope.launch { settings.setProseMode(on) } },
                )
                Spacer(Modifier.width(8.dp))
                Text("Художественная проза", style = MaterialTheme.typography.bodyMedium)
            }
            HintText(
                "Чистка бережёт авторский стиль: ритм, инверсии, повторы. " +
                    "Только ошибки распознавания, орфография и пунктуация. " +
                    "Промпт режима редактируется во вкладке «Промпты»."
            )
            if (prose) {
                val rulesInProse by settings.rulesInProseFlow.collectAsState(initial = false)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = rulesInProse,
                        onCheckedChange = { on -> scope.launch { settings.setRulesInProse(on) } },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Правила обучения в прозе", style = MaterialTheme.typography.bodyMedium)
                }
                HintText("Выключено: правила оформления (списки и т.п.) не применяются к художественному тексту.")
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = convo,
                    onCheckedChange = { on -> scope.launch { settings.setConvoContext(on) } },
                )
                Spacer(Modifier.width(8.dp))
                Text("Контекст разговора", style = MaterialTheme.typography.bodyMedium)
            }
            HintText(
                "Твои недавние сообщения в том же приложении (пауза до 10 минут) " +
                    "уходят с новой диктовкой как контекст — ответы держат нить разговора."
            )
        }


        RecordingsSection(recordings, serviceEnabled)

        HintText(stringResource(R.string.settings_usage_hint))
    }
}

@Composable
private fun SpeechSection(
    settings: Settings,
    whisperProvider: ru.zf.pravka.provider.WhisperProvider,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine by settings.speechEngineFlow.collectAsState(initial = Settings.SPEECH_GOOGLE)
    var status by remember { mutableStateOf("…") }
    var downloading by remember { mutableStateOf(false) }

    val isGoogle = engine == Settings.SPEECH_GOOGLE
    suspend fun statusFor(e: String): String = when {
        e == Settings.SPEECH_GOOGLE ->
            if (ru.zf.pravka.provider.GoogleSpeechSession.isAvailable(context)) context.getString(R.string.google_ready)
            else context.getString(R.string.google_unavailable)
        else -> whisperProvider.statusText(e)
    }

    LaunchedEffect(engine, downloading) { status = statusFor(engine) }

    SectionCard(label = stringResource(R.string.settings_speech_title)) {
        HintText(stringResource(R.string.speech_engine_label))
        ModelOption(
            label = stringResource(R.string.speech_engine_google),
            selected = isGoogle,
            onSelect = { scope.launch { settings.setSpeechEngine(Settings.SPEECH_GOOGLE) } },
        )
        ModelOption(
            label = stringResource(R.string.speech_engine_whisper_small),
            selected = engine == Settings.SPEECH_WHISPER_SMALL,
            onSelect = { scope.launch { settings.setSpeechEngine(Settings.SPEECH_WHISPER_SMALL) } },
        )
        ModelOption(
            label = stringResource(R.string.speech_engine_whisper_base),
            selected = engine == Settings.SPEECH_WHISPER_BASE,
            onSelect = { scope.launch { settings.setSpeechEngine(Settings.SPEECH_WHISPER_BASE) } },
        )

        Spacer(Modifier.height(8.dp))
        Text(status, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !downloading,
                onClick = {
                    if (isGoogle) {
                        ru.zf.pravka.provider.GoogleSpeechSession.triggerModelDownload(context)
                        Feedback.toast(context, context.getString(R.string.google_prepare_started))
                        scope.launch { status = statusFor(engine) }
                    } else {
                        downloading = true
                        scope.launch {
                            val result = whisperProvider.download(engine)
                            downloading = false
                            Feedback.toast(
                                context,
                                if (result.isSuccess) context.getString(R.string.speech_download_done)
                                else context.getString(R.string.speech_download_failed, result.exceptionOrNull()?.message ?: ""),
                            )
                            status = statusFor(engine)
                        }
                    }
                },
            ) {
                Text(
                    when {
                        isGoogle -> stringResource(R.string.google_prepare)
                        downloading -> stringResource(R.string.speech_downloading)
                        else -> stringResource(R.string.speech_download)
                    }
                )
            }
            OutlinedButton(onClick = { scope.launch { status = statusFor(engine) } }) {
                Text(stringResource(R.string.speech_refresh))
            }
        }
        if (isGoogle) {
            Spacer(Modifier.height(12.dp))
            // Recognition mode: continuous (build 55, "распознаёт идеально")
            // vs per-segment restarts - side-by-side comparison by the owner.
            val segmented by settings.speechSegmentedFlow.collectAsState(initial = true)
            val formatting by settings.speechFormattingFlow.collectAsState(initial = false)
            HintText("Режим распознавания")
            ModelOption(
                label = "Непрерывный — одна сессия, без перезапусков (как в сборке 55)",
                selected = segmented,
                onSelect = { scope.launch { settings.setSpeechSegmented(true) } },
            )
            ModelOption(
                label = "Посегментный — перезапуск на каждой паузе",
                selected = !segmented,
                onSelect = { scope.launch { settings.setSpeechSegmented(false) } },
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = formatting,
                    onCheckedChange = { on -> scope.launch { settings.setSpeechFormatting(on) } },
                )
                Spacer(Modifier.width(8.dp))
                Text("Пунктуация распознавателя", style = MaterialTheme.typography.bodyMedium)
            }
            HintText(
                "Выключено (как в сборке 55): распознаватель отдаёт сырой поток слов, " +
                    "знаки расставляет Правка. Действует со следующей диктовки."
            )
        }
        Spacer(Modifier.height(6.dp))
        HintText(
            stringResource(
                if (isGoogle) R.string.speech_hint_google else R.string.speech_hint
            )
        )
    }
}

@Composable
private fun RecordingsSection(recordings: ru.zf.pravka.data.Recordings, serviceEnabled: Boolean) {
    val context = LocalContext.current
    // listFiles() plus a length() stat per file - off the composition pass.
    var items by remember { mutableStateOf<List<ru.zf.pravka.data.Recordings.Item>>(emptyList()) }
    var busyId by remember { mutableStateOf<String?>(null) }
    // NB: not named `ru` - that would shadow the `ru.zf.pravka` package.
    val loc = remember { Locale.forLanguageTag("ru") }
    LaunchedEffect(Unit) {
        val found = withContext(Dispatchers.IO) { recordings.list() }
        items = found
    }
    if (items.isEmpty()) return

    SectionCard(label = stringResource(R.string.rec_header)) {
        HintText(stringResource(R.string.rec_hint))
        Spacer(Modifier.height(8.dp))
        for (item in items) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            R.string.rec_item,
                            java.text.SimpleDateFormat("dd.MM HH:mm", loc).format(java.util.Date(item.startedAt)),
                            (item.durationMs / 1000),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (busyId == item.id) {
                        Text(stringResource(R.string.rec_transcribing), style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(
                    enabled = busyId == null,
                    onClick = {
                        val service = PravkaAccessibilityService.instance
                        if (service == null || !serviceEnabled) {
                            Feedback.toast(context, context.getString(R.string.rec_need_service))
                            return@TextButton
                        }
                        busyId = item.id
                        service.retryRecording(item.file) { ok: Boolean, msg: String ->
                            busyId = null
                            items = recordings.list()
                            if (!ok) Feedback.toast(context, context.getString(R.string.rec_failed, msg))
                        }
                    },
                ) { Text(stringResource(R.string.rec_transcribe)) }
                TextButton(onClick = {
                    recordings.delete(item.id)
                    items = recordings.list()
                }) {
                    Text(stringResource(R.string.rec_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// The learning tab: pending proposals (approve/reject, one by one or all at
// once) and the approved rules with their confirmation counters. All actions
// run on the APP scope - they must survive tab switches and screen closes.
@Composable
private fun LearningTab(app: PravkaApp) {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<List<ru.zf.pravka.data.LearnStore.Suggestion>>(emptyList()) }
    var rules by remember { mutableStateOf<List<ru.zf.pravka.data.RulesStore.Rule>>(emptyList()) }
    var loadTick by remember { mutableStateOf(0) }
    LaunchedEffect(loadTick) {
        pending = app.learnStore.all()
        rules = app.rulesStore.all()
    }

    fun refresh() { loadTick++ }

    suspend fun accept(sug: ru.zf.pravka.data.LearnStore.Suggestion) {
        if (sug.kind == "rule") {
            app.rulesStore.add(sug.text, sug.exampleBefore, sug.exampleAfter)
            app.learnLog.add("ПРИНЯТО правило: ${sug.text}")
        } else {
            app.dictionaryStore.add(
                sug.from, sug.to,
                if (sug.mode == "PROTECT") DictMode.PROTECT else DictMode.HARD,
                sug.note,
            )
            app.learnLog.add("ПРИНЯТО в словарь: ${sug.from} → ${sug.to} [${sug.mode}]")
        }
        app.learnStore.remove(sug.id)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle(stringResource(R.string.tab_learning))
        HintText(
            "Правка учится на твоих правках: авто-снимки разбираются Опусом " +
                "раз в ~12 часов, «Обучить» в меню кнопки — сразу. Ничего не " +
                "применяется без твоего одобрения."
        )

        SectionCard(label = "Автообучение — что происходит сейчас") {
            var watch by remember { mutableStateOf<List<ru.zf.pravka.data.EditWatchStore.Entry>>(emptyList()) }
            var watchTick by remember { mutableStateOf(0) }
            LaunchedEffect(watchTick, loadTick) { watch = app.editWatch.all() }
            val internal = ctx.getSharedPreferences("pravka_internal", android.content.Context.MODE_PRIVATE)
            val lastBatch = internal.getLong("last_learn_batch", 0L)
            val fmt = remember { java.text.SimpleDateFormat("dd.MM HH:mm", Locale.forLanguageTag("ru")) }
            val edited = watch.count { it.editedTs > 0 }
            Text(
                "Наблюдается текстов: ${watch.size}, из них ты правил: $edited." +
                    (watch.filter { it.editedTs > 0 }.minOfOrNull { it.editedTs }
                        ?.let { "\nБлижайшая правка созреет: " + fmt.format(java.util.Date(it + 10 * 60 * 1000)) + "." } ?: "") +
                    (if (lastBatch > 0) "\nПоследний авторазбор: " + fmt.format(java.util.Date(lastBatch)) + "."
                    else "\nАвторазбор ещё не запускался."),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            var analyzing by remember { mutableStateOf(false) }
            LaunchedEffect(analyzing) {
                if (analyzing) {
                    // Give the forced batch a moment to start, then track it.
                    kotlinx.coroutines.delay(1200)
                    while (PravkaAccessibilityService.instance?.learnBatchRunning == true) {
                        kotlinx.coroutines.delay(1000)
                    }
                    analyzing = false
                    watchTick++
                    loadTick++
                }
            }
            if (analyzing) {
                Text(
                    "Идёт разбор (Опус)… результат появится в «Предложениях».",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !analyzing,
                    onClick = {
                        val svc = PravkaAccessibilityService.instance
                        if (svc == null) {
                            Feedback.toast(ctx, "Служба доступности выключена.")
                        } else {
                            svc.runLearnBatchNow()
                            analyzing = true
                        }
                    },
                ) { Text(if (analyzing) "Разбираю…" else "Разобрать сейчас") }
                OutlinedButton(onClick = {
                    watchTick++
                    loadTick++
                    Feedback.toast(ctx, "Обновлено")
                }) { Text("Обновить") }
            }
            Spacer(Modifier.height(10.dp))
            val period by app.settings.learnPeriodHoursFlow.collectAsState(initial = 3)
            HintText("Период авторазбора")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (h in listOf(1, 3, 12)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = period == h,
                            onClick = { app.appScope.launch { app.settings.setLearnPeriodHours(h) } },
                        )
                        Text("$h ч", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            HintText(
                "Авторазбор идёт Опусом: сам — не чаще выбранного периода и через " +
                    "10 минут после правки; «Разобрать сейчас» — без ожиданий. " +
                    "Каждый шаг виден в Логи → Обучение."
            )
        }

        SectionCard(label = "Предложения (${pending.size})") {
            if (pending.isEmpty()) {
                HintText("Пока пусто. Появятся после разбора твоих правок.")
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        app.appScope.launch {
                            for (sug in app.learnStore.all()) accept(sug)
                            refresh()
                            PravkaAccessibilityService.instance?.refreshLearnBadge()
                        }
                    }) { Text("Принять все") }
                    TextButton(onClick = {
                        app.appScope.launch {
                            for (sug in app.learnStore.all()) {
                                app.learnLog.add("отклонено: ${if (sug.kind == "rule") sug.text else sug.from}")
                                app.learnStore.remove(sug.id)
                            }
                            refresh()
                            PravkaAccessibilityService.instance?.refreshLearnBadge()
                        }
                    }) { Text("Отклонить все", color = MaterialTheme.colorScheme.error) }
                }
                Spacer(Modifier.height(6.dp))
                for (sug in pending) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            when {
                                sug.kind == "rule" -> "Правило: ${sug.text}"
                                sug.mode == "PROTECT" -> "Защита: ${sug.from}"
                                else -> "${sug.from} → ${sug.to}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (sug.note.isNotBlank()) {
                            Text(sug.note, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (sug.exampleBefore.isNotBlank() && sug.exampleAfter.isNotBlank()) {
                            Text(
                                "«${sug.exampleBefore}» → «${sug.exampleAfter}»",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row {
                            TextButton(onClick = {
                                app.appScope.launch {
                                    accept(sug)
                                    refresh()
                                    PravkaAccessibilityService.instance?.refreshLearnBadge()
                                }
                            }) { Text("Принять") }
                            TextButton(onClick = {
                                app.appScope.launch {
                                    app.learnLog.add("отклонено: ${if (sug.kind == "rule") sug.text else sug.from}")
                                    app.learnStore.remove(sug.id)
                                    refresh()
                                    PravkaAccessibilityService.instance?.refreshLearnBadge()
                                }
                            }) { Text("Отклонить", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }

        SectionCard(label = "Принятые правила (${rules.size})") {
            if (rules.isEmpty()) {
                HintText("Принятые правила появятся здесь и будут уходить в каждый запрос чистки.")
            } else {
                HintText("×N — сколько раз правило подтвердилось новыми правками.")
                Spacer(Modifier.height(6.dp))
                var optimizing by remember { mutableStateOf(false) }
                var optimized by remember {
                    mutableStateOf<ru.zf.pravka.provider.ClaudeProvider.OptimizedRules?>(null)
                }
                if (rules.size >= 2) {
                    Button(
                        enabled = !optimizing,
                        onClick = {
                            optimizing = true
                            app.appScope.launch {
                                val result = app.claudeProvider.optimizeRules(rules)
                                optimizing = false
                                result.onSuccess { opt ->
                                    app.stats.recordAux(opt.costUsd, opt.tokensIn, opt.tokensOut)
                                    app.learnLog.add(
                                        "оптимизация правил: ${rules.size} → ${opt.rules.size}, стоила $" +
                                            "%.4f".format(java.util.Locale.US, opt.costUsd)
                                    )
                                    optimized = opt
                                }.onFailure { e ->
                                    Feedback.toast(ctx, e.message ?: "Не получилось оптимизировать")
                                }
                            }
                        },
                    ) { Text(if (optimizing) "Оптимизирую (Опус)…" else "Оптимизировать набор") }
                    Spacer(Modifier.height(6.dp))
                }
                optimized?.let { opt ->
                    AlertDialog(
                        onDismissRequest = { optimized = null },
                        title = { Text("Оптимизированный набор: ${rules.size} → ${opt.rules.size}") },
                        text = {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                opt.rules.forEachIndexed { i, r ->
                                    Text("${i + 1}. ${r.text}", style = MaterialTheme.typography.bodyMedium)
                                    if (r.before.isNotBlank()) {
                                        Text(
                                            "«${r.before}» → «${r.after}»",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                }
                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                val chosen = opt.rules
                                optimized = null
                                app.appScope.launch {
                                    app.rulesStore.replaceAll(chosen.map { Triple(it.text, it.before, it.after) })
                                    app.learnLog.add("набор правил ЗАМЕНЁН оптимизированным (${chosen.size})")
                                    loadTick++
                                }
                            }) { Text("Заменить набор") }
                        },
                        dismissButton = {
                            TextButton(onClick = { optimized = null }) { Text("Отмена") }
                        },
                    )
                }
                for (rule in rules) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(rule.text, style = MaterialTheme.typography.bodyMedium)
                            if (rule.exampleBefore.isNotBlank() && rule.exampleAfter.isNotBlank()) {
                                Text(
                                    "«${rule.exampleBefore}» → «${rule.exampleAfter}»",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            "×${rule.hits}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (rule.hits > 0) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { on ->
                                app.appScope.launch { app.rulesStore.setEnabled(rule.id, on); refresh() }
                            },
                        )
                        TextButton(onClick = {
                            app.appScope.launch {
                                app.learnLog.add("правило удалено: ${rule.text}")
                                app.rulesStore.delete(rule.id)
                                refresh()
                            }
                        }) { Text("×", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

// The logs tab: the few logs that matter, each with copy and file export -
// "нажал и показал" instead of hunting through screens.
@Composable
private fun LogsTab(app: PravkaApp) {
    val context = LocalContext.current
    var eventTail by remember { mutableStateOf<List<String>>(emptyList()) }
    var learnTail by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadTick by remember { mutableStateOf(0) }
    LaunchedEffect(loadTick) {
        withContext(Dispatchers.IO) {
            val e = app.eventLog.readLast(150)
            val l = app.learnLog.readLast(150)
            eventTail = e
            learnTail = l
        }
    }

    fun copy(text: String) {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Правка", text))
        Feedback.toast(context, "Скопировано")
    }

    fun share(intent: android.content.Intent, title: String) {
        runCatching {
            context.startActivity(android.content.Intent.createChooser(intent, title))
        }.onFailure { Feedback.toast(context, "Лог пуст") }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle(stringResource(R.string.tab_logs))
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { loadTick++ }) { Text("Обновить") }
        }

        SectionCard(label = "Эвал промпта") {
            var items by remember { mutableStateOf<List<ru.zf.pravka.data.EvalStore.Item>>(emptyList()) }
            var evalTick by remember { mutableStateOf(0) }
            var showSet by remember { mutableStateOf(false) }
            var running by remember { mutableStateOf(ru.zf.pravka.core.EvalRunner.running) }
            var progress by remember { mutableStateOf(0 to 0) }
            LaunchedEffect(evalTick) { items = app.evalStore.all() }
            LaunchedEffect(running) {
                while (ru.zf.pravka.core.EvalRunner.running) {
                    progress = ru.zf.pravka.core.EvalRunner.done to ru.zf.pravka.core.EvalRunner.total
                    kotlinx.coroutines.delay(1500)
                }
                running = false
                evalTick++
            }
            HintText(
                "Золотой набор: вход диктовки и эталонный результат. Каждое " +
                    "изменение промпта прогоняется по набору и меряется цифрой."
            )
            Spacer(Modifier.height(6.dp))
            Text("Эталонов: ${items.size}", style = MaterialTheme.typography.bodyMedium)
            val last = remember(evalTick) { app.evalStore.lastRun() }
            if (last != null) {
                Text(
                    "Последний прогон: средний ${"%.1f".format(last.optDouble("avg") * 100)}%, " +
                        "точных ${last.optInt("exact")} из ${last.optInt("total")}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (running) {
                Text("Идёт прогон: ${progress.first}/${progress.second}…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !running && items.isNotEmpty(),
                    onClick = {
                        ru.zf.pravka.core.EvalRunner.start(app)
                        running = true
                    },
                ) { Text(if (running) "Идёт…" else "Прогнать") }
                OutlinedButton(onClick = {
                    app.appScope.launch {
                        val added = app.evalStore.addAll(
                            withContext(Dispatchers.IO) { app.historyLog.readPairs(40) }
                        )
                        Feedback.toast(context, "Добавлено эталонов: $added")
                        evalTick++
                    }
                }) { Text("Набрать из истории") }
            }
            Row {
                TextButton(onClick = { showSet = !showSet }) {
                    Text(if (showSet) "Скрыть набор" else "Показать набор")
                }
            }
            if (showSet) {
                for (item in items) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        Text(
                            item.input.take(80),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            app.appScope.launch { app.evalStore.remove(item.id); evalTick++ }
                        }) { Text("×", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }

        SectionCard(label = "Распознавание и вставка") {
            HintText("Сессии распознавания, сегменты, ошибки, путь вставки.")
            Spacer(Modifier.height(6.dp))
            Text(
                if (eventTail.isEmpty()) "Пусто." else eventTail.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Row {
                TextButton(onClick = { copy(eventTail.joinToString("\n")) }) { Text("Копировать") }
                TextButton(onClick = { share(app.eventLog.shareIntent(), "Журнал событий") }) { Text("Файлом") }
            }
        }

        SectionCard(label = "Обучение") {
            HintText("Снимки правок, батчи, предложения, решения, подтверждения правил.")
            Spacer(Modifier.height(6.dp))
            Text(
                if (learnTail.isEmpty()) "Пусто." else learnTail.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Row {
                TextButton(onClick = { copy(learnTail.joinToString("\n")) }) { Text("Копировать") }
                TextButton(onClick = { share(app.learnLog.shareIntent(), "Журнал обучения") }) { Text("Файлом") }
            }
        }

        SectionCard(label = "История правок") {
            HintText("Полный журнал в JSONL — для разбора качества.")
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = {
                share(app.historyLog.shareIntent(), "История правок")
            }) { Text("Выгрузить JSONL") }
        }
    }
}

@Composable
private fun ModelOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------------------
// Dictionary
// ---------------------------------------------------------------------------

@Composable
private fun dictModeColor(mode: DictMode) = when (mode) {
    DictMode.HARD -> MaterialTheme.colorScheme.primary
    DictMode.HINT -> MaterialTheme.colorScheme.tertiary
    DictMode.PROTECT -> MaterialTheme.colorScheme.secondary
}

@Composable
private fun DictionaryTab(
    store: DictionaryStore,
    historyLog: HistoryLog,
    dictMiner: ru.zf.pravka.provider.DictMiner,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by store.entriesFlow.collectAsState()
    var search by remember { mutableStateOf("") }
    var dialogEntry by remember { mutableStateOf<DictEntry?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var mining by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<ru.zf.pravka.provider.DictMiner.Suggestion>?>(null) }
    var picked by remember { mutableStateOf<Set<Int>>(emptySet()) }

    LaunchedEffect(Unit) { store.all() }  // triggers initial load

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val added = runCatching {
                    val text = context.contentResolver.openInputStream(uri)!!
                        .bufferedReader().use { it.readText() }
                    store.importJson(text)
                }.getOrElse { -1 }
                Feedback.toast(
                    context,
                    if (added >= 0) context.getString(R.string.dict_imported, added)
                    else context.getString(R.string.dict_import_failed),
                )
            }
        }
    }

    fun export() {
        scope.launch {
            val json = store.exportJson()
            val file = File(context.cacheDir, "pravka_dictionary.json")
            file.writeText(json)
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "ru.zf.pravka.files", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.dict_export)))
        }
    }

    val query = search.trim().lowercase()
    fun section(mode: DictMode) = entries
        .filter { it.mode == mode }
        .filter { query.isEmpty() || it.from.lowercase().contains(query) || it.to.lowercase().contains(query) }
        .sortedByDescending { it.hits }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScreenTitle(stringResource(R.string.tab_dictionary))
                Spacer(Modifier.weight(1f))
                Button(onClick = { showAddDialog = true }) { Text(stringResource(R.string.dict_add)) }
            }
            Row {
                TextButton(onClick = { export() }) { Text(stringResource(R.string.dict_export)) }
                TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                    Text(stringResource(R.string.dict_import))
                }
                TextButton(
                    enabled = !mining,
                    onClick = {
                        mining = true
                        scope.launch {
                            val pairs = withContext(Dispatchers.IO) { historyLog.readPairs(100) }
                            val result = dictMiner.mine(pairs)
                            mining = false
                            result.onSuccess { found ->
                                if (found.isEmpty()) {
                                    Feedback.toast(context, context.getString(R.string.dict_mine_empty))
                                } else {
                                    // Skip what the dictionary already has.
                                    val known = store.all().map { it.from.lowercase() }.toHashSet()
                                    val fresh = found.filter { it.from.lowercase() !in known }
                                    if (fresh.isEmpty()) {
                                        Feedback.toast(context, context.getString(R.string.dict_mine_empty))
                                    } else {
                                        suggestions = fresh
                                        picked = fresh.indices.toSet()
                                    }
                                }
                            }.onFailure {
                                Feedback.toast(context, context.getString(R.string.dict_mine_failed, it.message ?: ""))
                            }
                        }
                    },
                ) {
                    Text(stringResource(if (mining) R.string.dict_mine_running else R.string.dict_mine))
                }
            }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                singleLine = true,
                label = { Text(stringResource(R.string.dict_search)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }

        for (mode in DictMode.entries) {
            val sectionEntries = section(mode)
            item(key = "header_$mode") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 14.dp, bottom = 6.dp, start = 4.dp),
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(dictModeColor(mode), CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            when (mode) {
                                DictMode.HARD -> R.string.dict_section_hard
                                DictMode.HINT -> R.string.dict_section_hint
                                DictMode.PROTECT -> R.string.dict_section_protect
                            },
                            sectionEntries.size,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(sectionEntries, key = { it.id }) { entry ->
                DictRow(entry, onClick = { dialogEntry = entry }, onToggle = { enabled ->
                    scope.launch { store.update(entry.copy(enabled = enabled)) }
                })
            }
        }
    }

    suggestions?.let { list ->
        AlertDialog(
            onDismissRequest = { suggestions = null },
            title = { Text(stringResource(R.string.dict_mine_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    list.forEachIndexed { i, sug ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        ) {
                            Checkbox(
                                checked = i in picked,
                                onCheckedChange = { on ->
                                    picked = if (on) picked + i else picked - i
                                },
                            )
                            Column {
                                Text(
                                    if (sug.mode == DictMode.PROTECT) "\u0417\u0430\u0449\u0438\u0442\u0430: ${sug.from}"
                                    else "${sug.from} \u2192 ${sug.to}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (sug.note.isNotBlank()) {
                                    Text(
                                        sug.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val chosen = list.filterIndexed { i, _ -> i in picked }
                    suggestions = null
                    scope.launch {
                        for (sug in chosen) store.add(sug.from, sug.to, sug.mode, sug.note)
                        Feedback.toast(context, context.getString(R.string.dict_mine_added, chosen.size))
                    }
                }) { Text(stringResource(R.string.dict_mine_add)) }
            },
            dismissButton = {
                TextButton(onClick = { suggestions = null }) { Text(stringResource(R.string.dict_cancel)) }
            },
        )
    }

    if (showAddDialog) {
        DictEntryDialog(
            entry = null,
            onDismiss = { showAddDialog = false },
            onSave = { from, to, mode, note, _ ->
                scope.launch { store.add(from, to, mode, note) }
                showAddDialog = false
            },
            onDelete = null,
        )
    }
    dialogEntry?.let { entry ->
        DictEntryDialog(
            entry = entry,
            onDismiss = { dialogEntry = null },
            onSave = { from, to, mode, note, enabled ->
                scope.launch { store.update(entry.copy(from = from, to = to, mode = mode, note = note, enabled = enabled)) }
                dialogEntry = null
            },
            onDelete = {
                scope.launch { store.delete(entry.id) }
                dialogEntry = null
            },
        )
    }
}

@Composable
private fun DictRow(entry: DictEntry, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (entry.to.isNotBlank()) "${entry.from} → ${entry.to}" else entry.from,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (entry.note.isNotBlank()) {
                    Text(
                        entry.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (entry.hits > 0) {
                Text(
                    "×${entry.hits}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Switch(checked = entry.enabled, onCheckedChange = onToggle, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun DictEntryDialog(
    entry: DictEntry?,
    onDismiss: () -> Unit,
    onSave: (from: String, to: String, mode: DictMode, note: String, enabled: Boolean) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var from by remember { mutableStateOf(entry?.from ?: "") }
    var to by remember { mutableStateOf(entry?.to ?: "") }
    var mode by remember { mutableStateOf(entry?.mode ?: DictMode.HARD) }
    var note by remember { mutableStateOf(entry?.note ?: "") }
    var enabled by remember { mutableStateOf(entry?.enabled ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (entry == null) R.string.dict_add else R.string.dict_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = from,
                    onValueChange = { from = it },
                    label = { Text(stringResource(R.string.dict_from)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    singleLine = true,
                )
                if (mode != DictMode.PROTECT) {
                    OutlinedTextField(
                        value = to,
                        onValueChange = { to = it },
                        label = { Text(stringResource(R.string.dict_to)) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                        singleLine = true,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    for (m in DictMode.entries) {
                        RadioButton(selected = mode == m, onClick = { mode = m })
                        Text(
                            stringResource(
                                when (m) {
                                    DictMode.HARD -> R.string.dict_mode_hard
                                    DictMode.HINT -> R.string.dict_mode_hint
                                    DictMode.PROTECT -> R.string.dict_mode_protect
                                }
                            ),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                if (mode == DictMode.HINT) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text(stringResource(R.string.dict_note)) },
                    )
                }
                if (entry != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                        Text(stringResource(R.string.dict_enabled), Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (from.isNotBlank()) onSave(from, to, mode, note, enabled) },
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.dict_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dict_cancel)) }
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Prompts
// ---------------------------------------------------------------------------

private val promptTitles = mapOf(
    PromptStore.PromptId.CLEAN_CLAUDE to R.string.prompt_title_clean_claude,
    PromptStore.PromptId.BUSINESS to R.string.prompt_title_business,
    PromptStore.PromptId.SOFTEN to R.string.prompt_title_soften,
    PromptStore.PromptId.PROSE to R.string.prompt_title_prose,
)

@Composable
private fun PromptsTab(promptStore: PromptStore) {
    var editing by remember { mutableStateOf<PromptStore.PromptId?>(null) }
    val current = editing
    if (current == null) {
        PromptList(promptStore, onOpen = { editing = it })
    } else {
        PromptEditor(promptStore, current, onBack = { editing = null })
    }
}

@Composable
private fun PromptList(promptStore: PromptStore, onOpen: (PromptStore.PromptId) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle(stringResource(R.string.prompts_header))
        HintText(stringResource(R.string.prompts_subheader))
        for (id in PromptStore.PromptId.entries) {
            val override by promptStore.overrideFlow(id).collectAsState(initial = null)
            val effective = override ?: promptStore.factory(id)
            Card(onClick = { onOpen(id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(promptTitles.getValue(id)),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (override != null) {
                            Text(
                                stringResource(R.string.prompt_modified),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.shapes.small)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        effective.lineSequence().take(2).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.prompt_char_count, effective.length, effective.length / 3),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PromptEditor(
    promptStore: PromptStore,
    id: PromptStore.PromptId,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var warning by remember { mutableStateOf<Int?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var savedMark by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        text = promptStore.effective(id)
        loaded = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.prompt_back)) }
            Text(
                stringResource(promptTitles.getValue(id)),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = text,
            onValueChange = { text = it; error = null; savedMark = false },
            enabled = loaded,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp),
        )
        Text(
            stringResource(R.string.prompt_char_count, text.length, text.length / 3),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let {
            Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        warning?.let {
            Text(stringResource(it), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                // Placeholders live only in the CLEAN master prompt; BUSINESS
                // and SOFTEN are directives layered on top of it.
                if (id == PromptStore.PromptId.CLEAN_CLAUDE) {
                    if (!text.contains(Prompts.PLACEHOLDER_INPUT)) {
                        error = R.string.prompt_error_no_input
                        return@Button
                    }
                    warning = when {
                        !text.contains(Prompts.PLACEHOLDER_DICT) -> R.string.prompt_warning_no_dict
                        else -> null
                    }
                }
                scope.launch {
                    promptStore.setOverride(id, text)
                    savedMark = true
                }
            }) {
                Text(stringResource(if (savedMark) R.string.settings_saved else R.string.settings_save))
            }
            OutlinedButton(onClick = {
                if (confirmReset) {
                    scope.launch {
                        promptStore.resetToFactory(id)
                        text = promptStore.factory(id)
                        confirmReset = false
                        savedMark = false
                        error = null
                        warning = null
                    }
                } else {
                    confirmReset = true
                }
            }) {
                Text(stringResource(if (confirmReset) R.string.prompt_reset_confirm else R.string.prompt_reset))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Statistics
// ---------------------------------------------------------------------------

// Full text of every fix/dictation, newest first - tap a card to copy its
// result to the clipboard (owner's request, Wispr-style history).
@Composable
private fun TranscriptsTab(
    transcriptionLog: ru.zf.pravka.data.TranscriptionLog,
    liveDraft: ru.zf.pravka.data.LiveDraft,
    eventLog: ru.zf.pravka.data.EventLog,
) {
    val context = LocalContext.current
    // Reading (and JSON-parsing) these files is real disk work; doing it during
    // composition blocked the first frame of the tab.
    var log by remember { mutableStateOf<List<ru.zf.pravka.data.TranscriptionLog.Entry>>(emptyList()) }
    var draft by remember { mutableStateOf<String?>(null) }
    var hasExports by remember { mutableStateOf(false) }
    var hasEventLog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        data class Loaded(
            val entries: List<ru.zf.pravka.data.TranscriptionLog.Entry>,
            val draft: String?,
            val exports: Boolean,
            val events: Boolean,
        )
        val loaded = withContext(Dispatchers.IO) {
            Loaded(
                entries = transcriptionLog.readLast(200),
                draft = liveDraft.read(),
                exports = transcriptionLog.exists(),
                events = eventLog.exists(),
            )
        }
        log = loaded.entries
        draft = loaded.draft
        hasExports = loaded.exports
        hasEventLog = loaded.events
    }
    val ruLoc = remember { Locale.forLanguageTag("ru") }

    fun copy(text: String) {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Правка", text))
        Feedback.toast(context, context.getString(R.string.transcript_copied))
    }

    fun share(intent: android.content.Intent, chooserRes: Int) {
        runCatching {
            context.startActivity(
                android.content.Intent.createChooser(intent, context.getString(chooserRes))
            )
        }.onFailure { Feedback.toast(context, context.getString(R.string.transcripts_empty)) }
    }

    fun engineLabel(engine: String): String = when (engine) {
        Settings.SPEECH_GOOGLE -> "Google"
        Settings.SPEECH_WHISPER_SMALL -> "Whisper small"
        Settings.SPEECH_WHISPER_BASE -> "Whisper base"
        else -> engine
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle(stringResource(R.string.tab_transcripts))
        HintText(stringResource(R.string.transcripts_hint))

        // Recovery: text from a Google take that was interrupted before it
        // could be inserted (phone died / app killed mid-dictation).
        draft?.let { d ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        stringResource(R.string.draft_header),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(d, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { copy(d) }) { Text(stringResource(R.string.draft_copy)) }
                        TextButton(onClick = { liveDraft.clear(); draft = null }) {
                            Text(stringResource(R.string.draft_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        if (hasExports) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    share(transcriptionLog.shareJsonIntent(), R.string.transcripts_export_json)
                }) { Text(stringResource(R.string.transcripts_export_json)) }
                OutlinedButton(onClick = {
                    share(transcriptionLog.shareMetricsCsvIntent(), R.string.transcripts_export_csv)
                }) { Text(stringResource(R.string.transcripts_export_csv)) }
            }
        }
        if (hasEventLog) {
            OutlinedButton(onClick = {
                share(eventLog.shareIntent(), R.string.transcripts_export_log)
            }) { Text(stringResource(R.string.transcripts_export_log)) }
        }

        if (log.isEmpty()) {
            Text(stringResource(R.string.transcripts_empty), style = MaterialTheme.typography.bodyMedium)
        }
        for (entry in log) {
            Card(
                onClick = { if (entry.text.isNotBlank()) copy(entry.text) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    // Metrics line: engine · audio · transcription time · chars.
                    val meta = buildString {
                        append(engineLabel(entry.engine))
                        append(" · ")
                        append(String.format(ruLoc, "%.1f", entry.audioMs / 1000.0)).append(" с аудио")
                        // Whisper reports its transcription time; the Google
                        // live engine is realtime, so it logs 0 - skip it there.
                        if (entry.transcribeMs > 0) {
                            append(" · ")
                            append(String.format(ruLoc, "%.1f", entry.transcribeMs / 1000.0)).append(" с расшифровка")
                        }
                        append(" · ")
                        append(entry.chars).append(" симв.")
                        if (entry.realtimeFactor > 0) {
                            append(" · ×")
                            append(String.format(ruLoc, "%.2f", entry.realtimeFactor))
                        }
                    }
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (!entry.ok) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        entry.ts.replace('T', ' ').take(16),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    entry.error?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (entry.text.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(entry.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsTab(stats: Stats, historyLog: HistoryLog) {
    val context = LocalContext.current
    val snapshot by stats.snapshotFlow.collectAsState(initial = null)
    val ru = remember { Locale.forLanguageTag("ru") }
    // Was a file stat on every recomposition.
    var hasHistory by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        hasHistory = withContext(Dispatchers.IO) { historyLog.exists() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenTitle(stringResource(R.string.stats_header))

        snapshot?.let { s ->
            SectionCard(label = stringResource(R.string.stats_cost_header)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "$%.4f".format(Locale.US, s.costTodayUsd),
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
                StatRow(R.string.stats_cost_week, "$%.4f".format(Locale.US, s.costWeekUsd))
                StatRow(R.string.stats_cost_month, "$%.4f".format(Locale.US, s.costMonthUsd))
                StatRow(R.string.stats_cost_total, "$%.4f".format(Locale.US, s.costTotalUsd))
            }

            SectionCard(label = stringResource(R.string.stats_total)) {
                StatRow(R.string.stats_total, s.total.toString())
                StatRow(R.string.stats_clean, s.clean.toString())
                StatRow(R.string.stats_business, s.business.toString())
                StatRow(R.string.stats_soften, s.soften.toString())
                StatRow(R.string.stats_unchanged, s.unchanged.toString())
                StatRow(R.string.stats_errors, s.errors.toString())
                StatRow(R.string.stats_chars, "%,d".format(ru, s.charsProcessed))
                StatRow(R.string.stats_tokens, "%,d / %,d".format(ru, s.tokensIn, s.tokensOut))
                StatRow(
                    R.string.stats_latency,
                    String.format(ru, "%.1f с", s.averageLatencyMs / 1000.0),
                )
            }
        }

        Column {
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent.createChooser(
                                historyLog.shareIntent(),
                                context.getString(R.string.stats_share_history),
                            )
                        )
                    }.onFailure { Feedback.toast(context, context.getString(R.string.stats_history_empty)) }
                },
                enabled = hasHistory,
            ) {
                Text(stringResource(R.string.stats_share_history))
            }
            HintText(stringResource(R.string.stats_history_hint))
        }
    }
}

@Composable
private fun StatRow(labelRes: Int, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            stringResource(labelRes),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
