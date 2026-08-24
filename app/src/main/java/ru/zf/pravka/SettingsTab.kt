package ru.zf.pravka

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// Все настройки в одном месте, разложенные по режимам.
//
// Раньше они жили по вкладкам: ключ Anthropic здесь, категории Засечки под
// кнопкой внизу ленты, токен Todoist за словом «Токен» рядом с поиском, цели по
// еде — в конце дневника. Каждая на своём месте по логике «настройка рядом с
// тем, что она настраивает», и всё вместе — «а где это было?» каждый раз.
//
// Теперь одна вкладка и пять групп в порядке от общего к частному: Общее,
// Правка, Засечка, Дела, Тело. Группы свёрнуты — открытая вкладка это короткое
// меню на один экран, а не полотно на десять прокруток. Открытая группа
// помнится, пока живёт композиция: правишь цели по еде — не закрывается от
// каждого нажатия.
//
// Сами настройки живут рядом со своим режимом (ZasechkaSettings в ZasechkaTab,
// BodySportSettings в SportTab), а тут только собираются. Так правка режима не
// растаскивается по двум файлам.

/** Группы. Порядок сверху вниз — от общего к частному. */
private enum class Group(val title: String, val hint: String) {
    COMMON("Общее", "Ключ Anthropic, кнопки на экране, сохранённые записи"),
    PRAVKA("Правка", "Распознавание речи, проза, контекст разговора"),
    ZASECHKA("Засечка", "Кнопка, напоминания, категории, Google Sheets, intervals.icu"),
    DELA("Дела", "Токен Todoist"),
    BODY("Тело", "Notion и правила блока, цели по еде, отдых, глубина выгрузки"),
}

@Composable
internal fun SettingsTab(
    app: PravkaApp,
    serviceEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
) {
    var open by remember { mutableStateOf<Group?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(size = 48.dp, textSize = 26.sp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    stringResource(
                        R.string.build_info,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                        BuildConfig.BUILD_TIME,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Служба — вне групп: без неё не работает ни одна кнопка, и это
        // единственное, что должно быть видно, не открывая ничего.
        ServiceCard(serviceEnabled, onOpenAccessibilitySettings)

        for (group in Group.entries) {
            SettingsGroup(
                group = group,
                open = open == group,
                // «Тело» рисует свои карточки само (PaperCard) — обёртка дала бы
                // карточку в карточке.
                card = group != Group.BODY,
                onToggle = { open = if (open == group) null else group },
            ) {
                when (group) {
                    Group.COMMON -> CommonSettings(app, serviceEnabled)
                    Group.PRAVKA -> PravkaSettings(app)
                    Group.ZASECHKA -> ZasechkaSettings(app)
                    Group.DELA -> TodoistSettings(app)
                    Group.BODY -> BodySettings(app)
                }
            }
        }

        HintText(stringResource(R.string.settings_usage_hint))
    }
}

/**
 * Кнопка «Настройки …» в конце вкладки режима. Не дублирует настройки, а ведёт
 * к ним: из ленты в её группу — один тап, а не «Ещё → Настройки → раскрыть».
 */
@Composable
internal fun SettingsLink(title: String, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("›", style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Свёрнутая группа — строка меню; открытая — та же строка и содержимое под ней.
 * Содержимое НЕ внутри карточки-заголовка: настройки Тела рисуют свои карточки
 * сами, и карточка в карточке читается как ошибка вёрстки.
 */
@Composable
private fun SettingsGroup(
    group: Group,
    open: Boolean,
    card: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        group.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    HintText(group.hint)
                }
                Text(
                    if (open) "▾" else "▸",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (open) {
            if (card) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
                }
            } else {
                Column(Modifier.fillMaxWidth(), content = content)
            }
        }
    }
}

@Composable
private fun ServiceCard(serviceEnabled: Boolean, onOpenAccessibilitySettings: () -> Unit) {
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
                stringResource(
                    if (serviceEnabled) R.string.service_status_on else R.string.service_status_off
                ),
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
}

// ---------------------------------------------------------------------------
// Общее
// ---------------------------------------------------------------------------

@Composable
private fun CommonSettings(app: PravkaApp, serviceEnabled: Boolean) {
    // Своя область жизни приложения, а не композиции: rememberCoroutineScope
    // умирает вместе с экраном и рвёт запись в DataStore на полпути, если
    // владелец переключил вкладку сразу после нажатия.
    val scope = app.appScope
    val settings = app.settings
    var apiKey by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var savedMark by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        apiKey = settings.apiKey()
        loaded = true
    }

    val fabSize by settings.fabSizeFlow.collectAsState(initial = ru.zf.pravka.data.Settings.FAB_SIZE_DEFAULT)
    val fabAlpha by settings.fabAlphaFlow.collectAsState(initial = ru.zf.pravka.data.Settings.FAB_ALPHA_DEFAULT)
    var sizeSlider by remember(fabSize) { mutableStateOf(fabSize.toFloat()) }
    var alphaSlider by remember(fabAlpha) { mutableStateOf(fabAlpha) }

    Text(
        stringResource(R.string.settings_api_key_title),
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = apiKey,
        onValueChange = { apiKey = it; savedMark = false },
        enabled = loaded,
        singleLine = true,
        visualTransformation =
            if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
        label = { Text(stringResource(R.string.settings_api_key_label)) },
        trailingIcon = {
            TextButton(onClick = { keyVisible = !keyVisible }) {
                Text(
                    stringResource(
                        if (keyVisible) R.string.settings_hide else R.string.settings_show
                    )
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    HintText(stringResource(R.string.settings_api_key_hint))
    Spacer(Modifier.height(10.dp))
    Button(
        onClick = { scope.launch { settings.setApiKey(apiKey); savedMark = true } },
        enabled = loaded,
    ) {
        Text(stringResource(if (savedMark) R.string.settings_saved else R.string.settings_save))
    }

    Spacer(Modifier.height(18.dp))
    Text(
        stringResource(R.string.settings_fab_title),
        style = MaterialTheme.typography.titleSmall,
    )
    Text(
        stringResource(R.string.settings_fab_size, sizeSlider.toInt()),
        style = MaterialTheme.typography.bodyMedium,
    )
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

    Spacer(Modifier.height(10.dp))
    val modeIcons by settings.modeIconsFlow.collectAsState(initial = false)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = modeIcons,
            onCheckedChange = { on -> scope.launch { settings.setModeIcons(on) } },
        )
        Spacer(Modifier.width(8.dp))
        Text("Иконки вместо букв на кнопках", style = MaterialTheme.typography.bodyMedium)
    }
    HintText(
        "«П/З/Д/Т» станут пиктограммами, как в нижней ленте: перо, часы, " +
            "галочка, гантеля. Применяется сразу."
    )

    Spacer(Modifier.height(18.dp))
    RecordingsSection(app.recordings, serviceEnabled)
}

// ---------------------------------------------------------------------------
// Правка
// ---------------------------------------------------------------------------

@Composable
private fun PravkaSettings(app: PravkaApp) {
    val scope = app.appScope
    val settings = app.settings

    SpeechSection(settings, app.whisperProvider)

    Spacer(Modifier.height(18.dp))
    Text("Правка текста", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    val prose by settings.proseModeFlow.collectAsState(initial = false)
    val convo by settings.convoContextFlow.collectAsState(initial = true)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = prose, onCheckedChange = { on -> scope.launch { settings.setProseMode(on) } })
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
        HintText(
            "Выключено: правила оформления (списки и т.п.) не применяются к " +
                "художественному тексту."
        )
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

// ---------------------------------------------------------------------------
// Тело: спорт и еда — один режим, одна группа
// ---------------------------------------------------------------------------

@Composable
private fun BodySettings(app: PravkaApp) {
    BodySportSettings(app)
    Spacer(Modifier.height(14.dp))
    BodyFoodSettings(app)
}
