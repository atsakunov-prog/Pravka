package ru.zf.pravka

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import ru.zf.pravka.core.Prompts
import ru.zf.pravka.data.DebugLog
import ru.zf.pravka.data.PromptStore
import ru.zf.pravka.data.Settings
import ru.zf.pravka.data.Stats

// Three tabs: Settings, Prompts, Statistics. Dictionary and full
// diagnostics arrive in their stages; visual design pass comes last.
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
    PROMPTS(R.string.tab_prompts),
    STATS(R.string.tab_stats),
}

@Composable
private fun MainScreen(
    settings: Settings,
    promptStore: PromptStore,
    stats: Stats,
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
                Tab.PROMPTS -> PromptsTab(promptStore)
                Tab.STATS -> StatsTab(stats)
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

        // Autocorrect/autocapitalize in a prompt editor would fight the owner
        // (spec 9.1) - both are off, monospace font.
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
private fun StatsTab(stats: Stats) {
    val snapshot by stats.snapshotFlow.collectAsState(initial = null)
    val log = remember { DebugLog.snapshot().asReversed() }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.forLanguageTag("ru")) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.stats_header), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        snapshot?.let { s ->
            StatRow(R.string.stats_total, s.total.toString())
            StatRow(R.string.stats_clean, s.clean.toString())
            StatRow(R.string.stats_business, s.business.toString())
            StatRow(R.string.stats_soften, s.soften.toString())
            StatRow(R.string.stats_unchanged, s.unchanged.toString())
            StatRow(R.string.stats_errors, s.errors.toString())
            StatRow(R.string.stats_chars, "%,d".format(Locale.forLanguageTag("ru"), s.charsProcessed))
            StatRow(
                R.string.stats_latency,
                String.format(Locale.forLanguageTag("ru"), "%.1f с", s.averageLatencyMs / 1000.0),
            )
        }
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
                Text(
                    "← " + entry.input.take(80),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (entry.output.isNotEmpty()) {
                    Text(
                        "→ " + entry.output.take(80),
                        style = MaterialTheme.typography.bodySmall,
                    )
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
