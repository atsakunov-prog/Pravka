package ru.zf.pravka

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import ru.zf.pravka.core.DictEntry
import ru.zf.pravka.core.DictMode
import ru.zf.pravka.core.Prompts
import ru.zf.pravka.data.DebugLog
import ru.zf.pravka.data.DictionaryStore
import ru.zf.pravka.data.HistoryLog
import ru.zf.pravka.data.PromptStore
import ru.zf.pravka.data.Settings
import ru.zf.pravka.data.Stats
import ru.zf.pravka.ui.Feedback

// Four tabs: Settings, Dictionary, Prompts, Statistics.
// Visual design pass comes as the final stage.
class MainActivity : ComponentActivity() {

    private val serviceEnabled = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PravkaApp
        setContent {
            MaterialTheme {
                MainScreen(
                    settings = app.settings,
                    promptStore = app.promptStore,
                    stats = app.stats,
                    dictionaryStore = app.dictionaryStore,
                    historyLog = app.historyLog,
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
    STATS(R.string.tab_stats),
}

@Composable
private fun MainScreen(
    settings: Settings,
    promptStore: PromptStore,
    stats: Stats,
    dictionaryStore: DictionaryStore,
    historyLog: HistoryLog,
    serviceEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
) {
    var tab by remember { mutableStateOf(Tab.SETTINGS) }

    Scaffold(
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
                Tab.SETTINGS -> SettingsTab(settings, serviceEnabled, onOpenAccessibilitySettings)
                Tab.DICTIONARY -> DictionaryTab(dictionaryStore)
                Tab.PROMPTS -> PromptsTab(promptStore)
                Tab.STATS -> StatsTab(stats, historyLog)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

@Composable
private fun SettingsTab(
    settings: Settings,
    serviceEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(Settings.MODEL_SONNET) }
    var keyVisible by remember { mutableStateOf(false) }
    var savedMark by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    val fabSize by settings.fabSizeFlow.collectAsState(initial = Settings.FAB_SIZE_DEFAULT)
    val fabAlpha by settings.fabAlphaFlow.collectAsState(initial = Settings.FAB_ALPHA_DEFAULT)
    var sizeSlider by remember(fabSize) { mutableStateOf(fabSize.toFloat()) }
    var alphaSlider by remember(fabAlpha) { mutableStateOf(fabAlpha) }

    LaunchedEffect(Unit) {
        apiKey = settings.apiKey()
        model = settings.cleanModel()
        loaded = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Text(
            stringResource(R.string.build_info, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.BUILD_TIME),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(24.dp))

        Text(
            stringResource(if (serviceEnabled) R.string.service_status_on else R.string.service_status_off),
            style = MaterialTheme.typography.titleMedium,
            color = if (serviceEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        if (!serviceEnabled) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onOpenAccessibilitySettings) {
                Text(stringResource(R.string.service_enable))
            }
            Text(stringResource(R.string.service_hint), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))

        Text(stringResource(R.string.settings_api_key_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
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
        Text(stringResource(R.string.settings_api_key_hint), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))

        Text(stringResource(R.string.settings_model_title), style = MaterialTheme.typography.titleMedium)
        ModelOption(stringResource(R.string.settings_model_sonnet), model == Settings.MODEL_SONNET) {
            model = Settings.MODEL_SONNET; savedMark = false
        }
        ModelOption(stringResource(R.string.settings_model_haiku), model == Settings.MODEL_HAIKU) {
            model = Settings.MODEL_HAIKU; savedMark = false
        }
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                scope.launch {
                    settings.setApiKey(apiKey)
                    settings.setCleanModel(model)
                    savedMark = true
                }
            },
            enabled = loaded,
        ) {
            Text(stringResource(if (savedMark) R.string.settings_saved else R.string.settings_save))
        }
        Spacer(Modifier.height(32.dp))

        Text(stringResource(R.string.settings_fab_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
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
        Spacer(Modifier.height(24.dp))

        Text(stringResource(R.string.settings_usage_hint), style = MaterialTheme.typography.bodyMedium)
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
private fun DictionaryTab(store: DictionaryStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by store.entriesFlow.collectAsState()
    var search by remember { mutableStateOf("") }
    var dialogEntry by remember { mutableStateOf<DictEntry?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.tab_dictionary),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showAddDialog = true }) { Text(stringResource(R.string.dict_add)) }
            }
            Row {
                TextButton(onClick = { export() }) { Text(stringResource(R.string.dict_export)) }
                TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                    Text(stringResource(R.string.dict_import))
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
                Text(
                    stringResource(
                        when (mode) {
                            DictMode.HARD -> R.string.dict_section_hard
                            DictMode.HINT -> R.string.dict_section_hint
                            DictMode.PROTECT -> R.string.dict_section_protect
                        },
                        sectionEntries.size,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            items(sectionEntries, key = { it.id }) { entry ->
                DictRow(entry, onClick = { dialogEntry = entry }, onToggle = { enabled ->
                    scope.launch { store.update(entry.copy(enabled = enabled)) }
                })
            }
        }
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
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (entry.to.isNotBlank()) "${entry.from} → ${entry.to}" else entry.from,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (entry.note.isNotBlank()) {
                    Text(entry.note, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (entry.hits > 0) {
                Text("×${entry.hits}", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(0.dp))
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
    PromptStore.PromptId.CLEAN_NANO to R.string.prompt_title_clean_nano,
    PromptStore.PromptId.BUSINESS to R.string.prompt_title_business,
    PromptStore.PromptId.SOFTEN to R.string.prompt_title_soften,
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.prompts_header), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.prompts_subheader), style = MaterialTheme.typography.bodySmall)
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
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        effective.lineSequence().take(2).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.prompt_char_count, effective.length, effective.length / 3),
                        style = MaterialTheme.typography.labelSmall,
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
            .padding(16.dp),
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
                if (!text.contains(Prompts.PLACEHOLDER_INPUT)) {
                    error = R.string.prompt_error_no_input
                    return@Button
                }
                warning = when {
                    !text.contains(Prompts.PLACEHOLDER_DICT) -> R.string.prompt_warning_no_dict
                    id == PromptStore.PromptId.CLEAN_NANO && text.length > 800 -> R.string.prompt_warning_nano_long
                    else -> null
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

@Composable
private fun StatsTab(stats: Stats, historyLog: HistoryLog) {
    val context = LocalContext.current
    val snapshot by stats.snapshotFlow.collectAsState(initial = null)
    val log = remember { DebugLog.snapshot().asReversed() }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.forLanguageTag("ru")) }
    val ru = Locale.forLanguageTag("ru")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.stats_header), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        snapshot?.let { s ->
            Text(stringResource(R.string.stats_cost_header), style = MaterialTheme.typography.titleMedium)
            StatRow(R.string.stats_cost_today, "$%.4f".format(Locale.US, s.costTodayUsd))
            StatRow(R.string.stats_cost_week, "$%.4f".format(Locale.US, s.costWeekUsd))
            StatRow(R.string.stats_cost_month, "$%.4f".format(Locale.US, s.costMonthUsd))
            StatRow(R.string.stats_cost_total, "$%.4f".format(Locale.US, s.costTotalUsd))
            Spacer(Modifier.height(12.dp))

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
        Spacer(Modifier.height(16.dp))

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
            enabled = historyLog.exists(),
        ) {
            Text(stringResource(R.string.stats_share_history))
        }
        Text(stringResource(R.string.stats_history_hint), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))

        Text(stringResource(R.string.stats_log_header), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.stats_log_hint), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        if (log.isEmpty()) {
            Text(stringResource(R.string.stats_log_empty), style = MaterialTheme.typography.bodyMedium)
        }
        for (entry in log) {
            HorizontalDivider()
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(
                    "${timeFormat.format(Date(entry.timestamp))} · ${entry.mode} · ${entry.providerId} · ${entry.latencyMs} мс" +
                        (entry.error?.let { " · ОШИБКА" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                )
                entry.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Text("← " + entry.input.take(80), style = MaterialTheme.typography.bodySmall)
                if (entry.output.isNotEmpty()) {
                    Text("→ " + entry.output.take(80), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun StatRow(labelRes: Int, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(stringResource(labelRes), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
