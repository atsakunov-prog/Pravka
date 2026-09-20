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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlinx.coroutines.launch
import ru.zf.pravka.core.DiskLook
import ru.zf.pravka.core.StackGeometry
import ru.zf.pravka.data.Settings

// Все настройки в одном месте, разложенные по режимам.
//
// Раньше они жили по вкладкам: ключ Anthropic здесь, категории Засечки под
// кнопкой внизу ленты, токен Todoist за словом «Токен» рядом с поиском, цели по
// еде — в конце дневника. Каждая на своём месте по логике «настройка рядом с
// тем, что она настраивает», и всё вместе — «а где это было?» каждый раз.
//
// Теперь одна вкладка и шесть групп в порядке от общего к частному: Общее,
// Модели, Правка, Засечка, Дела, Тело. Группы свёрнуты — открытая вкладка это короткое
// меню на один экран, а не полотно на десять прокруток. Открытая группа
// помнится, пока живёт композиция: правишь цели по еде — не закрывается от
// каждого нажатия.
//
// Сами настройки живут рядом со своим режимом (ZasechkaSettings в ZasechkaTab,
// BodySportSettings в SportTab), а тут только собираются. Так правка режима не
// растаскивается по двум файлам.

/**
 * Группы. Порядок сверху вниз — от общего к частному. Шестерёнка в шапке
 * каждой вкладки открывает СВОЮ группу отдельным экраном (`ModeSettingsScreen`),
 * а «Ещё → Настройки» — всё вместе, с службой и обновлениями сверху.
 */
internal enum class SettingsGroup(val title: String, val hint: String) {
    COMMON("Общее", "Ключ Anthropic, кнопки на экране, сохранённые записи"),
    MODELS("Модели", "Какая модель и с каким усилием работает в каждом режиме"),
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
    var open by remember { mutableStateOf<SettingsGroup?>(null) }

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

        // Обновления — тоже вне групп: «есть свежая сборка» должно быть видно,
        // не открывая ничего, ровно как состояние службы.
        UpdatesCard(app)

        for (group in SettingsGroup.entries) {
            SettingsGroupRow(
                group = group,
                open = open == group,
                // «Тело» рисует свои карточки само (PaperCard) — обёртка дала бы
                // карточку в карточке.
                card = group != SettingsGroup.BODY,
                onToggle = { open = if (open == group) null else group },
            ) {
                GroupContent(app, group, serviceEnabled)
            }
        }
    }
}

/** Содержимое одной группы — общее для свёрнутого списка и отдельного экрана режима. */
@Composable
private fun GroupContent(app: PravkaApp, group: SettingsGroup, serviceEnabled: Boolean) {
    when (group) {
        SettingsGroup.COMMON -> CommonSettings(app, serviceEnabled)
        SettingsGroup.MODELS -> ModelsSettings(app)
        SettingsGroup.PRAVKA -> PravkaSettings(app)
        SettingsGroup.ZASECHKA -> ZasechkaSettings(app)
        SettingsGroup.DELA -> TodoistSettings(app)
        SettingsGroup.BODY -> BodySettings(app)
    }
}

/**
 * Настройки одного режима — экран за шестерёнкой в шапке вкладки. Владелец
 * (15.09.2026): «шестерёнка открывает именно настройки Правки, Засечки, Тела,
 * а общие настройки живут в Настройках». Ни службы, ни обновлений, ни чужих
 * групп — только своя.
 */
@Composable
internal fun ModeSettingsScreen(app: PravkaApp, group: SettingsGroup, serviceEnabled: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
    ) {
        if (group == SettingsGroup.BODY) {
            GroupContent(app, group, serviceEnabled)
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) { GroupContent(app, group, serviceEnabled) }
            }
        }
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
private fun SettingsGroupRow(
    group: SettingsGroup,
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

/**
 * Обновления: приложение само ходит в ветку `apk-builds`. Здесь видно, что
 * стоит сейчас, что лежит в ветке, и есть ручная кнопка — на случай «я только
 * что запушил, хочу прямо сейчас».
 */
@Composable
private fun UpdatesCard(app: PravkaApp) {
    val context = LocalContext.current
    val updates = app.updates
    val scope = app.appScope
    val state by updates.state.collectAsState()
    val auto by app.settings.updAutoFlow.collectAsState(initial = true)
    val mobile by app.settings.updMobileFlow.collectAsState(initial = false)
    val stamp = remember {
        java.text.SimpleDateFormat("d MMMM, HH:mm", Locale.forLanguageTag("ru"))
    }
    val latest = state.latest
    val fresh = latest != null && updates.isNewer(latest)
    // Сборка соседней ветки: номер больше, но это другая работа, не «новее».
    val otherLine = latest != null && latest.versionCode > BuildConfig.VERSION_CODE && !fresh
    val branchPref by app.settings.updBranchFlow.collectAsState(initial = "")
    var branchDraft by remember(branchPref) { mutableStateOf(branchPref) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.upd_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    latest == null -> stringResource(R.string.upd_never)
                    fresh -> stringResource(R.string.upd_found, latest.versionName, latest.builtAt)
                    otherLine -> stringResource(
                        R.string.upd_other_branch, latest.versionName, latest.branch,
                    )
                    else -> stringResource(R.string.upd_latest, BuildConfig.VERSION_NAME)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.lastCheck > 0) {
                Spacer(Modifier.height(4.dp))
                HintText(
                    stringResource(R.string.upd_checked, stamp.format(java.util.Date(state.lastCheck)))
                )
            }
            if (state.error.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.upd_error, state.error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val ready = state.ready?.takeIf { it.exists() }
                when {
                    state.progress >= 0 -> Button(onClick = {}, enabled = false) {
                        Text(stringResource(R.string.upd_downloading, state.progress))
                    }
                    ready != null && latest != null -> Button(onClick = {
                        if (updates.canInstall()) {
                            runCatching { context.startActivity(updates.installIntent(ready)) }
                        } else {
                            runCatching { context.startActivity(updates.allowInstallIntent()) }
                        }
                    }) { Text(stringResource(R.string.upd_install, latest.versionName)) }
                    fresh && latest != null -> Button(onClick = {
                        scope.launch { updates.download(latest) }
                    }) { Text(stringResource(R.string.upd_download, latest.versionName)) }
                }
                if (state.progress < 0) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { scope.launch { updates.check(force = true) } },
                        enabled = !state.checking,
                    ) {
                        Text(
                            stringResource(
                                if (state.checking) R.string.upd_checking else R.string.upd_check
                            )
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = auto,
                    onCheckedChange = { scope.launch { app.settings.setUpdAuto(it) } },
                )
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.upd_auto), style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = mobile,
                    onCheckedChange = { scope.launch { app.settings.setUpdMobile(it) } },
                )
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.upd_mobile), style = MaterialTheme.typography.bodySmall)
            }
            // Линия обновления. Заводская — константа `pravka`; поле осталось
            // на случай переезда линии, чтобы вернуть обновления одной строкой
            // без новой сборки.
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = branchDraft,
                onValueChange = { branchDraft = it },
                label = { Text(stringResource(R.string.upd_branch_label)) },
                placeholder = { Text(ru.zf.pravka.data.Updates.LINE) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            HintText(stringResource(R.string.upd_branch_hint, updates.line.ifBlank { "—" }))
            if (branchDraft.trim() != branchPref) {
                TextButton(onClick = {
                    scope.launch {
                        app.settings.setUpdBranch(branchDraft)
                        updates.check(force = true)
                    }
                }) { Text(stringResource(R.string.settings_save)) }
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

    val fabSize by settings.fabSizeFlow.collectAsState(initial = Settings.FAB_SIZE_DEFAULT)
    val fabAlpha by settings.fabAlphaFlow.collectAsState(initial = Settings.FAB_ALPHA_DEFAULT)
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
    // Круг или стопка (владелец, 19.09.2026): явный выбор из двух, не тумблер
    // «вместо» — «сделай переключалку в настройках: круг и стопка». Стопка
    // остаётся живой дорогой: «если не получится — откатим» одним движением.
    val diskMode by settings.diskModeFlow.collectAsState(initial = true)
    Text("Как стоят кнопки", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = diskMode,
            onClick = { scope.launch { settings.setDiskMode(true) } },
            label = { Text("Круг") },
        )
        FilterChip(
            selected = !diskMode,
            onClick = { scope.launch { settings.setDiskMode(false) } },
            label = { Text("Стопка") },
        )
    }
    HintText(
        "Круг: «П» сверху, «З» снизу, «Д» дальше — вокруг шестерёнки на стекле. " +
            "Повёл кнопку по кругу — крутится весь диск и щёлкает, махнул сильнее — " +
            "провернётся дальше; тап и долгое нажатие по кнопке — как были, в меню " +
            "долгого нажатия у каждой есть «Настройки». Тянешь за стекло (края круга, " +
            "промежутки между кнопками) — диск переезжает; двумя пальцами — тоже, " +
            "где бы ни взял, хоть за кнопки; у края экрана прячется до " +
            "середины шестерёнки: «П» и «З» внутри, «Д» за краем, докрутить — пальцем. " +
            "Шестерёнка: тап — веер быстрых настроек, двойной тап — всё в точку «П» " +
            "(тап по точке возвращает), долгое нажатие и тянуть — тоже переезд. Двойной " +
            "тап по свободному стеклу — тот же веер; долгое нажатие на стекло — " +
            "выдвинуть диск целиком на экран (и убрать обратно). Стопка: прежний " +
            "столбик с ручкой-галочкой. " +
            "Зелёная «Е» выключена — вернуть её можно тумблером в настройках Еды."
    )
    Spacer(Modifier.height(10.dp))
    // Светлее или темнее (владелец, 19.09.2026, ночь): «давай его сделаем
    // наоборот, светлее, чем бэкграунд. А то теряется иногда. И сделаем
    // тумблер в настройках: светлее/темнее». Двумя чипами, как «Круг ·
    // Стопка»: оба положения видны сразу, а тумблер «Светлое стекло» пришлось
    // бы читать. Числа обеих шкурок — `core/DiskLook.kt`.
    val diskLight by settings.diskLightFlow.collectAsState(initial = true)
    Text("Стекло диска", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = diskLight,
            onClick = { scope.launch { settings.setDiskLight(true) } },
            label = { Text("Светлее") },
        )
        FilterChip(
            selected = !diskLight,
            onClick = { scope.launch { settings.setDiskLight(false) } },
            label = { Text("Темнее") },
        )
    }
    HintText(
        "Светлее — тарелка под кнопками из бумаги: на тёмных экранах диск не " +
            "теряется. Темнее — прежние чернила. Тень под стеклом в обоих случаях " +
            "тёмная: на светлом фоне диск отделяет от него именно она. Переключается " +
            "на живом диске, смотреть лучше прямо на том экране, где он терялся."
    )

    // Ручки вида диска (владелец, 19.09.2026: «и нужно всё это в настройки.
    // Прозрачность, размер»). Плотности до первого касания ползунка НЕ
    // записаны: пока ключа нет, их считает core/DiskLook.kt, следя за
    // прозрачностью кнопок и за тем, какое стекло. Ползунок показывает это
    // счётное число, и владелец видит, откуда стартует.
    Spacer(Modifier.height(14.dp))
    Text("Вид диска", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))

    val diskGap by settings.diskGapFlow.collectAsState(initial = Settings.DISK_GAP_DEFAULT)
    var gapSlider by remember(diskGap) { mutableStateOf(diskGap.toFloat()) }
    Text("Размер диска: просвет ${gapSlider.toInt()} dp", style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = gapSlider,
        onValueChange = { gapSlider = it },
        onValueChangeFinished = { scope.launch { settings.setDiskGap(gapSlider.toInt()) } },
        valueRange = Settings.DISK_GAP_MIN.toFloat()..Settings.DISK_GAP_MAX.toFloat(),
    )
    HintText(
        "Просвет между шестерёнкой и кнопками; от него считается всё кольцо и " +
            "тарелка под ним. Больше просвет — шире диск и дальше кнопки друг от друга."
    )

    val diskGear by settings.diskGearFlow.collectAsState(initial = StackGeometry.GEAR_PCT_DEFAULT)
    var gearSlider by remember(diskGear) { mutableStateOf(diskGear.toFloat()) }
    Text("Шестерёнка: ${gearSlider.toInt()} % от кнопки", style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = gearSlider,
        onValueChange = { gearSlider = it },
        onValueChangeFinished = { scope.launch { settings.setDiskGear(gearSlider.toInt()) } },
        valueRange = StackGeometry.GEAR_PCT_MIN.toFloat()..StackGeometry.GEAR_PCT_MAX.toFloat(),
    )
    HintText("Она же кружки веера. Заводские 72 %; меньше 45 % по ней трудно попасть.")

    val plateOverride by settings.diskPlateAlphaFlow.collectAsState(initial = null)
    val plateAuto = DiskLook.plateAlpha(alphaSlider, diskLight)
    var plateSlider by remember(plateOverride, plateAuto) {
        mutableStateOf(plateOverride ?: plateAuto)
    }
    Text(
        "Плотность стекла: ${(plateSlider * 100).toInt()} %" +
            if (plateOverride == null) " (по счёту)" else "",
        style = MaterialTheme.typography.bodyMedium,
    )
    Slider(
        value = plateSlider,
        onValueChange = { plateSlider = it },
        onValueChangeFinished = { scope.launch { settings.setDiskPlateAlpha(plateSlider) } },
        valueRange = 0f..1f,
    )
    HintText("Сама тарелка. Ноль — стекла нет, остаются кнопки и тень под ними.")

    val faceOverride by settings.diskFaceAlphaFlow.collectAsState(initial = null)
    val faceAuto = DiskLook.faceAlpha(alphaSlider, onDisk = true)
    var faceSlider by remember(faceOverride, faceAuto) {
        mutableStateOf(faceOverride ?: faceAuto)
    }
    Text(
        "Плотность кнопок на диске: ${(faceSlider * 100).toInt()} %" +
            if (faceOverride == null) " (по счёту)" else "",
        style = MaterialTheme.typography.bodyMedium,
    )
    Slider(
        value = faceSlider,
        onValueChange = { faceSlider = it },
        onValueChangeFinished = { scope.launch { settings.setDiskFaceAlpha(faceSlider) } },
        valueRange = 0.2f..1f,
    )
    HintText(
        "Только на диске: в стопке у кнопки своя прозрачность, та, что выше. " +
            "Полупрозрачная кнопка на светлом стекле читается как лежащая ПОД ним — " +
            "отсюда и ручка."
    )

    val socketOverride by settings.diskSocketAlphaFlow.collectAsState(initial = null)
    val socketAuto = DiskLook.socketAlpha(plateSlider, diskLight)
    var socketSlider by remember(socketOverride, socketAuto) {
        mutableStateOf(socketOverride ?: socketAuto)
    }
    Text(
        "Тень кнопок на стекле: ${(socketSlider * 100).toInt()} %" +
            if (socketOverride == null) " (по счёту)" else "",
        style = MaterialTheme.typography.bodyMedium,
    )
    Slider(
        value = socketSlider,
        onValueChange = { socketSlider = it },
        onValueChangeFinished = { scope.launch { settings.setDiskSocketAlpha(socketSlider) } },
        valueRange = 0f..0.6f,
    )
    HintText("Ноль — теней нет. Они и делают кнопки лежащими НА стекле, а не под ним.")

    Spacer(Modifier.height(8.dp))
    // Три тумблера вида и поведения (владелец, 20.09.2026): «матовое стекло
    // делаем, но с выключением в настройках», «рельс давай попробуем… тоже с
    // выключением», «инерция выглядит круто. Делаем. Выключалкой в настройках».
    val frost by settings.diskFrostFlow.collectAsState(initial = true)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = frost, onCheckedChange = { on -> scope.launch { settings.setDiskFrost(on) } })
        Spacer(Modifier.width(8.dp))
        Text("Матовое стекло", style = MaterialTheme.typography.bodyMedium)
    }
    HintText(
        "Мелкое зерно по тарелке: стекло перестаёт быть плёнкой. Системное " +
            "размытие фона сюда не годится — оно размывает ПРЯМОУГОЛЬНИК окна, а " +
            "окно у круглой тарелки квадратное, и вокруг диска висел бы размытый " +
            "квадрат."
    )

    val rail by settings.diskRailFlow.collectAsState(initial = true)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = rail, onCheckedChange = { on -> scope.launch { settings.setDiskRail(on) } })
        Spacer(Modifier.width(8.dp))
        Text("Рельс под кнопками", style = MaterialTheme.typography.bodyMedium)
    }
    HintText(
        "Канавка по кольцу, на котором сидят кнопки: видна в промежутках между " +
            "ними, и глазу сразу понятно, что диск крутится, а не просто лежит. " +
            "Это не те засечки, что мы сняли: рельс ровный и под кнопки не лезет."
    )

    val inertia by settings.diskInertiaFlow.collectAsState(initial = true)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = inertia, onCheckedChange = { on -> scope.launch { settings.setDiskInertia(on) } })
        Spacer(Modifier.width(8.dp))
        Text("Инерция при переезде", style = MaterialTheme.typography.bodyMedium)
    }
    if (inertia) {
        val roll by settings.diskRollFlow.collectAsState(initial = Settings.DISK_ROLL_DEFAULT)
        var rollSlider by remember(roll) { mutableStateOf(roll) }
        Text(
            "Охота катиться: ${(rollSlider * 100).toInt()} %",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = rollSlider,
            onValueChange = { rollSlider = it },
            onValueChangeFinished = { scope.launch { settings.setDiskRoll(rollSlider) } },
            valueRange = 0f..1.5f,
        )
    }
    HintText(
        "Везёшь диск вбок — кольцо проворачивается, как колесо по поверхности. " +
            "Сто процентов — настоящее качение без проскальзывания; заводские " +
            "пятьдесят, потому что на полном взмахе через экран кнопки успевают " +
            "уехать из-под руки. На отпускании диск всё равно щёлкает по четверти."
    )

    // Утопание после долгого простоя (владелец, 20.09.2026): «если я не
    // использую правку пять минут и больше, то она залезает ещё дальше в
    // край: на 75 % кнопок где-то. И я тапаю по ней, и она вылезает».
    val diskSink by settings.diskSinkFlow.collectAsState(initial = true)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = diskSink, onCheckedChange = { on -> scope.launch { settings.setDiskSink(on) } })
        Spacer(Modifier.width(8.dp))
        Text("Утопить диск после долгого простоя", style = MaterialTheme.typography.bodyMedium)
    }
    if (diskSink) {
        val sinkMin by settings.diskSinkMinutesFlow.collectAsState(initial = Settings.DISK_SINK_MIN_DEFAULT)
        var minSlider by remember(sinkMin) { mutableStateOf(sinkMin.toFloat()) }
        Text("Через ${minSlider.toInt()} мин", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = minSlider,
            onValueChange = { minSlider = it },
            onValueChangeFinished = { scope.launch { settings.setDiskSinkMinutes(minSlider.toInt()) } },
            valueRange = Settings.DISK_SINK_MIN_MIN.toFloat()..Settings.DISK_SINK_MIN_MAX.toFloat(),
        )
        val sinkPct by settings.diskSinkPctFlow.collectAsState(initial = Settings.DISK_SINK_PCT_DEFAULT)
        var pctSlider by remember(sinkPct) { mutableStateOf(sinkPct.toFloat()) }
        Text("Глубже на ${pctSlider.toInt()} % кнопки", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = pctSlider,
            onValueChange = { pctSlider = it },
            onValueChangeFinished = { scope.launch { settings.setDiskSinkPct(pctSlider.toInt()) } },
            valueRange = Settings.DISK_SINK_PCT_MIN.toFloat()..Settings.DISK_SINK_PCT_MAX.toFloat(),
        )
    }
    HintText(
        "Диск, который давно не трогали, уходит за край ещё глубже — видна " +
            "полоска кнопок. Тап по ней ДОСТАЁТ диск и ничего не нажимает: " +
            "иначе первое касание после простоя запускало бы запись из-за края. " +
            "Работает поверх автоуборки: диск, оставленный посреди экрана, " +
            "никуда не уезжает — он там нарочно."
    )

    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = { scope.launch { settings.resetDiskLook() } }) {
        Text("Вернуть вид диска в счёт")
    }
    HintText(
        "Размеры — к заводским, плотности — обратно к счёту: они снова поедут за " +
            "прозрачностью кнопок и за выбором «Светлее · Темнее»."
    )

    Spacer(Modifier.height(10.dp))
    // Автоуборка диска (владелец, 19.09.2026): «через 30 секунд диск пришёл к
    // ближайшему краю и прилепился, так что остались только засечка и правка».
    val diskTuck by settings.diskTuckFlow.collectAsState(initial = true)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = diskTuck,
            onCheckedChange = { on -> scope.launch { settings.setDiskTuck(on) } },
        )
        Spacer(Modifier.width(8.dp))
        Text("Автоматически убирать диск к краю", style = MaterialTheme.typography.bodyMedium)
    }
    HintText(
        "Полминуты без касаний — диск сам уезжает к ближайшему краю и " +
            "поворачивается домой: видны только «П» и «З». Посреди записи или " +
            "разбора не уезжает никогда. Выключено — стоит, где оставил, и как " +
            "повернул."
    )
    // Бегущая строка у всех четырёх кнопок — одна ширина (владелец, 15.09:
    // «поставим в общих настройках размер плашки по горизонтали»). На экране
    // режется так, чтобы кнопка и поле рядом оставались видны.
    val tickerWidth by settings.tickerWidthFlow.collectAsState(initial = Settings.TICKER_WIDTH_DEFAULT)
    var tickerSlider by remember(tickerWidth) { mutableStateOf(tickerWidth.toFloat()) }
    Text(
        "Ширина бегущей строки: ${tickerSlider.toInt()} dp",
        style = MaterialTheme.typography.bodyMedium,
    )
    Slider(
        value = tickerSlider,
        onValueChange = { tickerSlider = it },
        onValueChangeFinished = { scope.launch { settings.setTickerWidth(tickerSlider.toInt()) } },
        valueRange = Settings.TICKER_WIDTH_MIN.toFloat()..Settings.TICKER_WIDTH_MAX.toFloat(),
    )
    HintText("Одна на «П», «З», «Д» и «Т»; шире экрана не станет — кнопка рядом остаётся видна.")

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
        "«П/З/Д/Е» станут пиктограммами, как в нижней ленте: перо, часы, " +
            "галочка, тарелка. Применяется сразу."
    )

    Spacer(Modifier.height(10.dp))
    val stackIdle by settings.stackIdleFlow.collectAsState(initial = true)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = stackIdle,
            onCheckedChange = { on -> scope.launch { settings.setStackIdle(on) } },
        )
        Spacer(Modifier.width(8.dp))
        Text("Складывать кнопки в стопку", style = MaterialTheme.typography.bodyMedium)
    }
    HintText(
        "Стопка: полминуты без касаний остаются две: «П» и «З» — они на своих местах " +
            "(«Д» уезжает в «З»; ниже про «Е» — то же самое, если она включена) " +
            "и работают как обычно, первый тап сразу пишет. «Д» и «Е» уезжают " +
            "в «З» и пропадают; вернуть их — серая ручка с галочкой под " +
            "стопкой, ею же можно свернуть и разложить когда угодно. Вторая " +
            "серая ручка, с многоточием, стоит над «П»: убирает с экрана ВСЁ " +
            "разом, нажатие возвращает все четыре. Её можно таскать, как " +
            "кнопку, — вся связка едет за ней. Перетаскивание больше не " +
            "разворачивает спрятанное, и посреди записи или разбора кнопки " +
            "сами не складываются никогда."
    )

    Spacer(Modifier.height(10.dp))
    val phoneMicOnly by settings.phoneMicOnlyFlow.collectAsState(initial = true)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = phoneMicOnly,
            onCheckedChange = { on -> scope.launch { settings.setPhoneMicOnly(on) } },
        )
        Spacer(Modifier.width(8.dp))
        Text("Микрофон телефона (выкл. — гарнитура Bluetooth)", style = MaterialTheme.typography.bodyMedium)
    }
    HintText(
        "То же самое переключает плашка между «П» и «З»: телефончик — слушает " +
            "телефон, Bluetooth машины и наушники диктовку не перехватывают, в " +
            "дороге Правка слышит тебя, а не салон; наушники — перед тейком " +
            "поднимается канал гарнитуры и слушает её микрофон, после тейка " +
            "канал опускается. Выбирает не подключение, а ты: гарнитура на шее " +
            "слышит хуже кармана. Не подключена — плашка бледная, слушает телефон."
    )

    Spacer(Modifier.height(18.dp))
    Text("Отладка", style = MaterialTheme.typography.titleSmall)
    val debugLog by settings.debugLogFlow.collectAsState(initial = false)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = debugLog,
            onCheckedChange = { on -> scope.launch { settings.setDebugLog(on) } },
        )
        Spacer(Modifier.width(8.dp))
        Text("Писать запросы к Claude в лог", style = MaterialTheme.typography.bodyMedium)
    }
    HintText(
        "Каждый запрос целиком — правка, Засечка, Тело, Еда: стабильная и " +
            "переменная части с размерами. Смотреть и выгружать — «Ещё → Логи», " +
            "«Запросы к Claude». Лог растёт быстро, держи включённым, пока смотришь."
    )
    // Нерасшифрованные записи переехали наверх вкладки «Правка» (15.09.2026):
    // это то, что ждёт действия с утра, а не настройка.
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
    val rulesOn by settings.rulesInPromptFlow.collectAsState(initial = false)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = rulesOn, onCheckedChange = { on -> scope.launch { settings.setRulesInPrompt(on) } })
        Spacer(Modifier.width(8.dp))
        Text("Постоянные правила в промпте", style = MaterialTheme.typography.bodyMedium)
    }
    HintText(
        "Выключено (16.09): по журналу 1200 чисток правила не меняли пунктуацию и длину " +
            "предложений, а в запрос из 54 попадали только первые 8 (потолок 2000 знаков) — " +
            "и те повторяют промпт. Заметная разница одна: приветствие с «!». Включи, если " +
            "её не хватает; во вкладке Обучение красным помечено, что в потолок не влезает."
    )
    Spacer(Modifier.height(10.dp))
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
    if (prose && rulesOn) {
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
    // Ночной разбор и тень второй модели — в «Ещё → Разборы» (ReviewsTab.kt),
    // своей плашкой: владелец (16.09) хотел их там, а не среди настроек.
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
