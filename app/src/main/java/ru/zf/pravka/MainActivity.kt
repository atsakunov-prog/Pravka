package ru.zf.pravka

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
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
import ru.zf.pravka.trigger.PravkaAccessibilityService
import ru.zf.pravka.ui.CostAction
import ru.zf.pravka.ui.ExportAction
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.ModeDecor
import ru.zf.pravka.ui.ModeFrame
import ru.zf.pravka.ui.PravkaTheme
import ru.zf.pravka.ui.SettingsAction
import ru.zf.pravka.ui.StatsAction
import ru.zf.pravka.ui.TabHeader
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.bevel
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.PaperToggle
import ru.zf.pravka.ui.PaperAlert
import ru.zf.pravka.ui.PaperSheet
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.SheetAction
import ru.zf.pravka.ui.PaperRow
import ru.zf.pravka.ui.RowRule
import ru.zf.pravka.ui.ScreenPad
import ru.zf.pravka.ui.tint

// Tabs: Засечка (the daily surface), then the Правка service tabs.
// Editorial "proofreader" design: paper, ink, red pen (ui/Theme.kt).
//
// Каждая вкладка — под общей шапкой (ui/Frame.kt): пиктограмма режима,
// название с полоской и три значка справа — статистика, доллар (стоимость
// API), шестерёнка настроек своего режима. Владелец (15.09.2026): «в каждой
// вкладке справа наверху шестерёнка именно этого режима, кнопка статистики и
// доллар; пояснения под названием убрать». Служебные экраны из «Ещё», экран
// стоимости и настройки режима открываются ПОВЕРХ нижней вкладки (Page) и
// закрываются «‹» или системным «назад» — владелец возвращается туда, откуда
// пришёл, а не в список «Ещё».

class MainActivity : ComponentActivity() {

    companion object {
        // The Засечка button's long press and its notifications land straight
        // on the timesheet tab.
        const val EXTRA_TAB = "tab"
        const val TAB_PRAVKA = "pravka"
        const val TAB_ZASECHKA = "zasechka"
        const val TAB_TODOIST = "todoist"
        const val TAB_SPORT = "sport"
        const val TAB_FOOD = "food"
        const val TAB_MONEY = "money"
        const val TAB_SETTINGS = "settings"
        const val TAB_PROMPTS = "prompts"

        /**
         * Группа настроек, в которую приземлить «Настройки» из меню плавающей
         * кнопки (24.09.2026): долгое нажатие на «З» → «Настройки» открывает
         * Засечку, а не меню всех групп. Значение — имя [SettingsGroup].
         */
        const val EXTRA_SETTINGS_GROUP = "settings_group"

        // Кнопка еды: «сфоткай тарелку» / «штрихкод» с длинного нажатия
        // открывают Тело (Е) и сразу запускают камеру или сканер.
        const val EXTRA_FOOD_ACTION = "food_action"

        /** На какой сборке приложение уже само просило уведомления — раз на сборку. */
        private const val KEY_NOTIF_APP_ASKED_BUILD = "notif_app_asked_build"
    }

    private val serviceEnabled = mutableStateOf(false)

    // Разрешены ли уведомления — для плашки над вкладками. Владелец
    // (08.09.2026): после обновления пуши «видимо, уходят», и автопилот молчит
    // в пустоту, пока не заглянешь в настройки автопилота. Теперь об этом
    // говорит само приложение, а первое открытие на новой сборке само
    // поднимает системный диалог (см. maybeAskNotifications).
    private val notifEnabled = mutableStateOf(true)
    private val requestNotif = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onNotifResult(granted) }

    private fun notificationsOn(): Boolean = runCatching {
        getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
    }.getOrDefault(true)

    /** Первое открытие на этой сборке при выключенных уведомлениях — диалог сам. */
    private fun maybeAskNotifications() {
        if (notifEnabled.value || android.os.Build.VERSION.SDK_INT < 33) return
        val prefs = getSharedPreferences(
            ru.zf.pravka.trigger.PravkaAccessibilityService.PREFS_INTERNAL, MODE_PRIVATE,
        )
        val build = BuildConfig.VERSION_CODE
        if (prefs.getInt(KEY_NOTIF_APP_ASKED_BUILD, 0) == build) return
        prefs.edit().putInt(KEY_NOTIF_APP_ASKED_BUILD, build).apply()
        (application as PravkaApp).eventLog.add("уведомления выключены — приложение просит заново (сборка $build)")
        requestNotif.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Кнопка «Разрешить» на плашке: диалог, а если система его уже не покажет — настройки. */
    private fun fixNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestNotif.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings()
        }
    }

    private fun onNotifResult(granted: Boolean) {
        val app = application as PravkaApp
        notifEnabled.value = notificationsOn()
        when {
            granted -> app.eventLog.add("уведомления: разрешены из приложения")
            android.os.Build.VERSION.SDK_INT >= 33 &&
                !shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS) -> {
                // Диалог система уже не показывает — только руками.
                app.eventLog.add("уведомления: система диалог не показывает — открываю настройки")
                openNotificationSettings()
            }
            else -> app.eventLog.add("уведомления: владелец отказал в диалоге приложения")
        }
    }

    private fun openNotificationSettings() {
        runCatching {
            startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            ru.zf.pravka.ui.Feedback.toast(this, "Включи «Разрешить уведомления» для Правки", long = true)
        }
    }

    // «Открыть Засечку» из меню кнопки обязано приземлять именно на Засечку.
    // Раньше вкладка читалась только в onCreate — а приложение почти всегда
    // уже запущено, интент приходит в onNewIntent, и владелец оставался там,
    // где был («должно попадать на засечку»). Теперь просьба живёт состоянием,
    // и экран на неё реагирует в любой момент жизни активити.
    private val tabRequest = mutableStateOf<Tab?>(null)
    private val groupRequest = mutableStateOf<SettingsGroup?>(null)

    private fun groupOf(intent: android.content.Intent?): SettingsGroup? =
        intent?.getStringExtra(EXTRA_SETTINGS_GROUP)?.let { name ->
            SettingsGroup.entries.firstOrNull { it.name == name }
        }
    private val foodActionRequest = mutableStateOf("")

    private fun tabOf(intent: android.content.Intent?): Tab? =
        when (intent?.getStringExtra(EXTRA_TAB)) {
            TAB_SETTINGS -> Tab.SETTINGS
            TAB_PROMPTS -> Tab.PROMPTS
            TAB_PRAVKA -> Tab.PRAVKA
            TAB_ZASECHKA -> Tab.ZASECHKA
            TAB_TODOIST -> Tab.TODOIST
            TAB_SPORT -> Tab.SPORT
            TAB_FOOD -> Tab.FOOD
            TAB_MONEY -> Tab.MONEY
            else -> null
        }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tabOf(intent)?.let { tabRequest.value = it }
        groupOf(intent)?.let { groupRequest.value = it }
        intent.getStringExtra(EXTRA_FOOD_ACTION)?.takeIf { it.isNotBlank() }?.let {
            foodActionRequest.value = it
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PravkaApp
        val foodAction = intent?.getStringExtra(EXTRA_FOOD_ACTION).orEmpty()
        // Без явной просьбы открываем таймшит: это экран, который он смотрит
        // каждый день, всё остальное — служебное.
        val initialTab = tabOf(intent) ?: Tab.ZASECHKA
        groupOf(intent)?.let { groupRequest.value = it }
        setContent {
            PravkaTheme {
                // Вид плашек — из настроек, один раз на всё приложение
                // (`ui/CardLook.kt`): `PaperCard` зовут из десятка мест, и
                // протаскивать четыре тумблера через каждый вызов значило бы
                // править их все ради одного.
                ru.zf.pravka.ui.ProvideCardLook(app.settings) {
                MainScreen(
                    app = app,
                    initialTab = initialTab,
                    foodAction = foodAction,
                    tabRequest = tabRequest.value,
                    onTabRequestHandled = { tabRequest.value = null },
                    groupRequest = groupRequest.value,
                    onGroupRequestHandled = { groupRequest.value = null },
                    foodActionRequest = foodActionRequest.value,
                    onFoodActionHandled = { foodActionRequest.value = "" },
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
                    notifEnabled = notifEnabled.value,
                    onFixNotifications = { fixNotifications() },
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
    }

    override fun onResume() {
        super.onResume()
        serviceEnabled.value = ru.zf.pravka.trigger.PravkaAccessibilityService.instance != null
        notifEnabled.value = notificationsOn()
        // Доступ к файлам выдаётся на системном экране — вернулись оттуда,
        // экран «База данных» должен увидеть его сразу.
        // Доступ вернулся, а процесс начинал без него — перезапуск: сторы
        // прочли папку пустой и записали бы эту пустоту поверх базы.
        if (ru.zf.pravka.data.DataRoot.refreshAccess(this)) ru.zf.pravka.data.DataRoot.restart(this)
        maybeAskNotifications()
    }
}

internal enum class Tab(val titleRes: Int) {
    PRAVKA(R.string.tab_pravka),
    ZASECHKA(R.string.tab_zasechka),
    TODOIST(R.string.tab_todoist),
    SPORT(R.string.tab_sport),
    FOOD(R.string.tab_food),
    MONEY(R.string.tab_money),
    MORE(R.string.tab_more),
    REPORT(R.string.tab_report),
    SETTINGS(R.string.tab_settings),
    DICTIONARY(R.string.tab_dictionary),
    PROMPTS(R.string.tab_prompts),
    LEARNING(R.string.tab_learning),
    LOGS(R.string.tab_logs),
    /** Статистика диктовки — открывается значком статистики в шапке Правки, в «Ещё» её нет. */
    STATS(R.string.tab_stats),
    /** Разборы: ночной разбор диктовок и тень второй модели — отчёты, ответ текстом (16.09.2026). */
    REVIEWS(R.string.tab_reviews),
}

/**
 * Что живёт под «Ещё»: всё, что обслуживает Правку, а не день. Порядок — по
 * тому, как часто туда правда заходят; общие настройки — последними: у
 * каждого режима есть своя шестерёнка в шапке, сюда ходят за ключом и службой.
 *
 * Снято 15.09.2026: «Паттерны» (и их ночной поиск), «Выгрузки» (книга Excel
 * теперь за значком выгрузки в Общей статистике), «Статистика» (деньги — за
 * долларом в шапке любой вкладки).
 */
private val SERVICE_TABS = listOf(
    // «Общая статистика» в списке не нужна (владелец, 15.09): к ней ведёт
    // значок статистики в шапке Засечки, Дел, Спорта и Еды.
    // «Разборы» — первыми: утренний отчёт ночного разбора и тени читается каждый день
    // (владелец, 16.09: «это не в Ещё → Разборы, как я просил»).
    Tab.REVIEWS,
    Tab.DICTIONARY,
    Tab.PROMPTS,
    Tab.LEARNING,
    Tab.LOGS,
    Tab.SETTINGS,
)

/** Нижние кнопки и их значки. */
private val BOTTOM_TABS: List<Pair<Tab, androidx.compose.ui.graphics.vector.ImageVector>> = listOf(
    Tab.PRAVKA to Glyphs.Pravka,
    Tab.ZASECHKA to Glyphs.Zasechka,
    Tab.TODOIST to Glyphs.Delo,
    Tab.SPORT to Glyphs.Sport,
    Tab.FOOD to Glyphs.Food,
    Tab.MONEY to Glyphs.Money,
    Tab.MORE to Glyphs.More,
)

/** Режим профиля, которому принадлежит вкладка; null — вкладка есть всегда. */
private fun modeOf(tab: Tab): ru.zf.pravka.data.Profile.Mode? = when (tab) {
    Tab.ZASECHKA -> ru.zf.pravka.data.Profile.Mode.ZASECHKA
    Tab.TODOIST -> ru.zf.pravka.data.Profile.Mode.DELA
    Tab.SPORT -> ru.zf.pravka.data.Profile.Mode.SPORT
    Tab.FOOD -> ru.zf.pravka.data.Profile.Mode.FOOD
    Tab.MONEY -> ru.zf.pravka.data.Profile.Mode.MONEY
    else -> null
}

/** Режим вкладки: узор плашек и краска. Служебное — в родном оранжевом. */
private fun decorOf(tab: Tab): ModeDecor = when (tab) {
    Tab.PRAVKA -> ModeDecor.PRAVKA
    Tab.ZASECHKA -> ModeDecor.ZASECHKA
    Tab.TODOIST -> ModeDecor.DELA
    Tab.SPORT -> ModeDecor.SPORT
    Tab.FOOD -> ModeDecor.FOOD
    Tab.MONEY -> ModeDecor.MONEY
    else -> ModeDecor.SERVICE
}

/** Одна строка про то, зачем эта вкладка — чтобы не открывать её наугад. */
private fun serviceHint(tab: Tab): String = when (tab) {
    Tab.REPORT -> "день в графиках и тот же день неделю назад"
    Tab.SETTINGS -> "режимы, голос и Claude, подключения, кнопки"
    Tab.DICTIONARY -> "как писать имена и термины"
    Tab.PROMPTS -> "тексты запросов ко всем режимам"
    Tab.LEARNING -> "разбор твоих правок и принятые правила"
    Tab.REVIEWS -> "ночной разбор, правка промпта, сравнение"
    Tab.LOGS -> "что делала служба, выгрузки для разбора"
    else -> ""
}

/** Экран поверх нижней вкладки: служебный из «Ещё», настройки одного режима или стоимость. */
private sealed class Page {
    data class Service(val tab: Tab) : Page()
    data class ModeSettings(val group: SettingsGroup) : Page()
    data object Cost : Page()
}

/** Значок служебного экрана — тот же, что у него в шапке. */
private fun serviceGlyph(tab: Tab): androidx.compose.ui.graphics.vector.ImageVector = when (tab) {
    Tab.REVIEWS -> Glyphs.Moon
    Tab.DICTIONARY -> Glyphs.Dictionary
    Tab.PROMPTS -> Glyphs.Scroll
    Tab.LEARNING -> Glyphs.Learn
    Tab.LOGS -> Glyphs.ListLines
    Tab.SETTINGS -> Glyphs.Gear
    Tab.REPORT -> Glyphs.Stats
    Tab.STATS -> Glyphs.Stats
    else -> Glyphs.More
}

/**
 * «Ещё» — две полки (24.09.2026): что учит и чистит саму Правку (разборы,
 * словарь, промпты, обучение) и служебное (логи, настройки). Раньше — шесть
 * голых карточек подряд с абзацем под каждой; значок и строка в одну линию
 * читаются быстрее, чем пояснение.
 */
@Composable
private fun MoreList(onOpen: (Tab) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ScreenPad.Padding),
        verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
    ) {
        for ((label, items) in MORE_SHELVES) {
            ru.zf.pravka.ui.PaperCard(label = label) {
                items.forEachIndexed { i, item ->
                    if (i > 0) RowRule()
                    PaperRow(
                        title = stringResource(item.titleRes),
                        hint = serviceHint(item),
                        icon = serviceGlyph(item),
                        badgeTint = MaterialTheme.colorScheme.primary,
                        onClick = { onOpen(item) },
                    )
                }
            }
        }
    }
}

private val MORE_SHELVES: List<Pair<String, List<Tab>>> = listOf(
    "правка изнутри" to listOf(Tab.REVIEWS, Tab.DICTIONARY, Tab.PROMPTS, Tab.LEARNING),
    "служебное" to listOf(Tab.LOGS, Tab.SETTINGS),
)

/**
 * Единственная выгрузка приложения — вся жизнь одной книгой Excel. Живёт за
 * значком выгрузки в шапке «Общей статистики» (владелец, 15.09: «выгрузки я бы
 * сделал в виде иконки справа вверху в статистике»).
 *
 * Владелец (09.09): «уберём все эти экспорты csv и сводки, я ими не пользуюсь,
 * и сделаем один экспорт, который будет экспортировать всё то, что в Notion…
 * мне надоел этот Notion, в Excel мне как-то удобнее». Книгу собирает
 * `LifeExport` из тех же строк, что уезжают в Notion: лист на базу, колонка в
 * колонку, свежее сверху. Ничего своего у файла нет — что в Notion, то и тут.
 */
@Composable
private fun LifeExportDialog(app: PravkaApp, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf("") }
    val notionStatus by app.notionLifeSync.statusFlow.collectAsState()
    val export: () -> Unit = {
        busy = true
        lastError = ""
        app.appScope.launch {
            val intent = runCatching { app.lifeExport.shareIntent() }
                .onFailure { e ->
                    // Причина целиком: «не собрался» без причины читается как поломка.
                    lastError = "Не собралась: ${e.javaClass.simpleName}: ${e.message}"
                    app.eventLog.add("выгрузка xlsx: $lastError")
                }
                .getOrNull()
            busy = false
            if (intent != null) {
                onDismiss()
                runCatching {
                    context.startActivity(
                        android.content.Intent.createChooser(intent, "Вся жизнь (.xlsx)")
                    )
                }
            }
        }
    }
    PaperAlert(
        onDismiss = onDismiss,
        title = "Вся жизнь одной книгой",
        icon = Glyphs.Export,
        subtitle = "одна книга .xlsx — как в Notion",
        confirm = SheetAction(if (busy) "Собираю…" else "Выгрузить .xlsx", icon = Glyphs.Export, enabled = !busy, onClick = export),
    ) {
        HintText(
            "Та же структура, что в Notion под «Правка: разборы»: листы " +
                ru.zf.pravka.core.NotionLifeSchema.ALL.joinToString(", ") { it.name } +
                ". Свежее сверху, шапка закреплена, фильтр включён."
        )
        HintText("Notion: " + notionStatus.ifBlank { "синк ещё не запускался" })
        if (lastError.isNotBlank()) {
            Text(
                lastError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun MainScreen(
    app: PravkaApp,
    initialTab: Tab,
    foodAction: String = "",
    tabRequest: Tab? = null,
    onTabRequestHandled: () -> Unit = {},
    groupRequest: SettingsGroup? = null,
    onGroupRequestHandled: () -> Unit = {},
    foodActionRequest: String = "",
    onFoodActionHandled: () -> Unit = {},
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
    notifEnabled: Boolean = true,
    onFixNotifications: () -> Unit = {},
    onOpenAccessibilitySettings: () -> Unit,
) {
    // Кто пользуется (data/Profile.kt): свежая установка сначала спрашивает —
    // пока не ответили, остального приложения нет, иначе оно завелось бы с
    // заводскими владельца и мужским родом в чистке.
    val profile by app.profileStore.flow.collectAsState()
    val asking by app.profileStore.asking.collectAsState()
    if (profile == null && asking) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            ModeFrame(ModeDecor.SERVICE) { ProfileOnboarding(app) }
        }
        return
    }
    // Вкладка выключенного в профиле режима не показывается: ни снизу, ни по
    // ссылке из уведомления — тогда первая живая.
    val live: (Tab) -> Boolean = { t -> modeOf(t)?.let { m -> profile?.has(m) ?: true } ?: true }
    val firstLive = { BOTTOM_TABS.map { it.first }.first(live) }
    // Служебные вкладки живут под «Ещё» и открываются поверх текущей. Ссылка
    // снаружи (уведомление, меню кнопки) может показывать прямо на служебную —
    // тогда открываем «Ещё» и сразу её.
    val service = remember { SERVICE_TABS }
    var tab by remember {
        mutableStateOf(
            when {
                initialTab in service -> Tab.MORE
                live(initialTab) -> initialTab
                else -> firstLive()
            }
        )
    }
    // Режим выключили тумблером, пока его вкладка открыта, или ссылка извне
    // привела на выключенный — на первую живую.
    LaunchedEffect(profile, tab) {
        if (!live(tab)) tab = firstLive()
    }
    // Стопка экранов поверх вкладки (24.09.2026): из Настроек открывается
    // группа, из группы — подключение, и «назад» должен вести на шаг, а не
    // сразу на вкладку. Тап по нижней кнопке снимает всю стопку.
    var pages by remember {
        mutableStateOf<List<Page>>(if (initialTab in service) listOf(Page.Service(initialTab)) else emptyList())
    }
    val page = pages.lastOrNull()
    val pop = { pages = pages.dropLast(1) }
    // Одноразовый автозапуск камеры/сканера в Теле (Е) — из меню кнопки еды.
    var foodActionPending by remember { mutableStateOf(foodAction.ifBlank { null }) }
    // Диалоги выгрузок, которые открывают значки в шапке служебных экранов.
    var dictationExport by remember { mutableStateOf(false) }
    var lifeExport by remember { mutableStateOf(false) }
    // Выгрузка Денег — значком в шапке, как у Статистики (24.09.2026); раньше
    // кнопка жила в самом низу длинной ленты.
    var moneyExport by remember { mutableStateOf(false) }

    // Открытие приложения — тоже повод посмотреть, нет ли сборки свежее:
    // служба доступности может быть выключена, а суточный тик живёт в ней.
    LaunchedEffect(Unit) { runCatching { app.updates.tick() } }

    // Просьба извне при живом приложении: переключаемся и сообщаем, что
    // услышали, — иначе следующая перерисовка увела бы вкладку обратно.
    LaunchedEffect(tabRequest) {
        val want = tabRequest ?: return@LaunchedEffect
        if (want in service) {
            tab = Tab.MORE
            pages = listOf(Page.Service(want))
        } else {
            tab = want
            pages = emptyList()
        }
        onTabRequestHandled()
    }
    // Группа настроек из меню кнопки: Настройки, над ними — эта группа, чтобы
    // «назад» вёл в меню настроек, а не на вкладку.
    LaunchedEffect(groupRequest) {
        val g = groupRequest ?: return@LaunchedEffect
        tab = Tab.MORE
        pages = listOf(Page.Service(Tab.SETTINGS), Page.ModeSettings(g))
        onGroupRequestHandled()
    }
    LaunchedEffect(foodActionRequest) {
        if (foodActionRequest.isBlank()) return@LaunchedEffect
        foodActionPending = foodActionRequest
        onFoodActionHandled()
    }
    // Системное «назад» закрывает верхний экран, а не приложение.
    BackHandler(enabled = pages.isNotEmpty()) { pop() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            val navLine = MaterialTheme.colorScheme.outlineVariant
            // Семь кнопок — порядок и подписи владельца: Правка, Засечка,
            // Дело, Спорт, Еда, Деньги, Ещё. Пиктограммы те же, что могут
            // встать на плавающие кнопки: перо, часы, галочка, гантеля,
            // вилка, рубль — один язык на всё приложение (`ui/Glyphs.kt`).
            // Тап по кнопке закрывает и верхний экран: владелец хочет
            // вкладку, а не то, что над ней.
            // Панель — на фоне вкладки, а не своей плитой (владелец,
            // 20.09.2026: «кнопки внизу темноватые, выглядит как будто они
            // немного грязные»). Тёмно-тёплая плита под тёмным фоном и была
            // той грязью: два почти одинаковых тона, между ними ступенька.
            // Теперь панель — продолжение фона, сверху волосяная линия, а
            // выбранная кнопка держится акцентом, а не подложкой-пятном.
            // Акцент — краской своего режима (24.09.2026): Засечка янтарная,
            // Дело синее, как их кнопки на стекле.
            // Седьмая кнопка — Деньги (владелец, 23.09.2026: «вкладка внизу
            // точно должна быть»). Договорённость «ровно шесть» этим
            // пересмотрена — см. docs/agreements.md.
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.background,
                modifier = Modifier.drawBehind {
                    drawLine(
                        color = navLine,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = 1.dp.toPx(),
                    )
                },
            ) {
                for ((item, glyph) in BOTTOM_TABS.filter { live(it.first) }) {
                    val selected = if (item == Tab.MORE) tab == Tab.MORE || page != null
                    else tab == item && page == null
                    val ink = decorOf(item).tint(MaterialTheme.colorScheme).primary
                    NavigationBarItem(
                        selected = selected,
                        // Повторный тап по «Ещё» возвращает список: иначе из
                        // Логов обратно к списку пришлось бы жать «назад».
                        onClick = { tab = item; pages = emptyList() },
                        icon = { Icon(glyph, contentDescription = null) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = ink,
                            selectedTextColor = ink,
                            indicatorColor = ink.copy(alpha = 0.14f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        label = { Text(stringResource(item.titleRes), maxLines = 1, softWrap = false) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            // Пуши выключены — говорим сверху, а не в глубине настроек
            // автопилота: без них молчат автопилот, напоминания и обновления.
            if (!notifEnabled) NotificationsBanner(onFixNotifications)
            val openCost = { pages = pages + Page.Cost }
            val openReport = { pages = listOf(Page.Service(Tab.REPORT)) }
            val p = page
            when {
                p != null -> ModeFrame((p as? Page.ModeSettings)?.group?.decor ?: ModeDecor.SERVICE) {
                    Column {
                        when (p) {
                            is Page.Cost -> {
                                TabHeader(title = stringResource(R.string.stats_header), glyph = Glyphs.Stats, onBack = pop)
                                CostScreen(app)
                            }
                            is Page.ModeSettings -> {
                                TabHeader(
                                    title = p.group.title,
                                    glyph = p.group.glyph,
                                    onBack = pop,
                                    actions = { CostAction(openCost) },
                                )
                                ModeSettingsScreen(
                                    app,
                                    p.group,
                                    serviceEnabled,
                                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                                    onOpen = { g -> pages = pages + Page.ModeSettings(g) },
                                )
                            }
                            is Page.Service -> {
                                TabHeader(
                                    title = stringResource(p.tab.titleRes),
                                    glyph = serviceGlyph(p.tab),
                                    onBack = pop,
                                    actions = {
                                        when (p.tab) {
                                            Tab.REPORT -> ExportAction { lifeExport = true }
                                            Tab.STATS -> ExportAction { dictationExport = true }
                                            else -> Unit
                                        }
                                        CostAction(openCost)
                                    },
                                )
                                when (p.tab) {
                                    Tab.REPORT -> ReportTab(app)
                                    Tab.SETTINGS -> SettingsTab(
                                        app,
                                        serviceEnabled,
                                        onOpenAccessibilitySettings,
                                        onOpen = { g -> pages = pages + Page.ModeSettings(g) },
                                    )
                                    Tab.DICTIONARY -> DictionaryTab(dictionaryStore, historyLog, dictMiner)
                                    Tab.PROMPTS -> PromptsTab(promptStore)
                                    Tab.LEARNING -> LearningTab(app)
                                    Tab.REVIEWS -> ReviewsTab(app)
                                    Tab.LOGS -> LogsTab(app)
                                    Tab.STATS -> DictationStatsTab(
                                        app,
                                        exportRequested = dictationExport,
                                        onExportHandled = { dictationExport = false },
                                    )
                                    else -> Unit
                                }
                            }
                        }
                    }
                }
                tab == Tab.MORE -> ModeFrame(ModeDecor.SERVICE) {
                    Column {
                        TabHeader(
                            title = stringResource(R.string.tab_more),
                            glyph = Glyphs.More,
                            actions = { CostAction(openCost) },
                        )
                        MoreList(onOpen = { pages = listOf(Page.Service(it)) })
                    }
                }
                else -> {
                    ModeFrame(decorOf(tab)) {
                        Column {
                            when (tab) {
                                Tab.PRAVKA -> {
                                    TabHeader(
                                        title = stringResource(R.string.tab_pravka),
                                        icon = painterResource(R.drawable.ic_mode_pravka),
                                        actions = {
                                            StatsAction { pages = listOf(Page.Service(Tab.STATS)) }
                                            CostAction(openCost)
                                            SettingsAction { pages = listOf(Page.ModeSettings(SettingsGroup.PRAVKA)) }
                                        },
                                    )
                                    PravkaTab(app, serviceEnabled)
                                }
                                Tab.ZASECHKA -> {
                                    TabHeader(
                                        title = stringResource(R.string.tab_zasechka),
                                        icon = painterResource(R.drawable.ic_mode_zasechka),
                                        actions = {
                                            StatsAction(openReport)
                                            CostAction(openCost)
                                            SettingsAction { pages = listOf(Page.ModeSettings(SettingsGroup.ZASECHKA)) }
                                        },
                                    )
                                    ZasechkaTab(app)
                                }
                                Tab.TODOIST -> {
                                    TabHeader(
                                        title = "Дела",
                                        icon = painterResource(R.drawable.ic_mode_delo),
                                        actions = {
                                            StatsAction(openReport)
                                            CostAction(openCost)
                                            SettingsAction { pages = listOf(Page.ModeSettings(SettingsGroup.DELA)) }
                                        },
                                    )
                                    TodoistTab(app)
                                }
                                Tab.SPORT -> {
                                    TabHeader(
                                        title = stringResource(R.string.tab_sport),
                                        icon = painterResource(R.drawable.ic_mode_sport),
                                        actions = {
                                            StatsAction(openReport)
                                            CostAction(openCost)
                                            SettingsAction { pages = listOf(Page.ModeSettings(SettingsGroup.SPORT)) }
                                        },
                                    )
                                    SportTab(app)
                                }
                                Tab.MONEY -> {
                                    TabHeader(
                                        title = stringResource(R.string.tab_money),
                                        icon = painterResource(R.drawable.ic_mode_money),
                                        actions = {
                                            ExportAction { moneyExport = true }
                                            StatsAction(openReport)
                                            CostAction(openCost)
                                            SettingsAction { pages = listOf(Page.ModeSettings(SettingsGroup.MONEY)) }
                                        },
                                    )
                                    MoneyTab(
                                        app,
                                        exportRequested = moneyExport,
                                        onExportHandled = { moneyExport = false },
                                    )
                                }
                                else -> {
                                    TabHeader(
                                        title = stringResource(R.string.tab_food),
                                        icon = painterResource(R.drawable.ic_mode_food),
                                        actions = {
                                            StatsAction(openReport)
                                            CostAction(openCost)
                                            SettingsAction { pages = listOf(Page.ModeSettings(SettingsGroup.FOOD)) }
                                        },
                                    )
                                    FoodTab(
                                        app,
                                        autoAction = foodActionPending,
                                        onAutoConsumed = { foodActionPending = null },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (lifeExport) LifeExportDialog(app, onDismiss = { lifeExport = false })
}

/**
 * Плашка над вкладками, пока уведомления Правки выключены. Владелец увидел
 * автопилот целиком только когда включил пуши руками — и заметил, что после
 * обновления они, «видимо», выключаются снова. Причина не найдена, но
 * молчание приложения об этом — точно поломка.
 */
@Composable
private fun NotificationsBanner(onFix: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Уведомления выключены: автопилот Засечки, напоминания и обновления молчат.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        PaperTextButton("Разрешить", icon = Glyphs.Bell, onClick = onFix)
    }
}

// ---------------------------------------------------------------------------
// Shared design pieces
// ---------------------------------------------------------------------------

/** The wide "П" mark - same as the launcher icon and the floating button. */
@Composable
internal fun BrandMark(size: androidx.compose.ui.unit.Dp, textSize: androidx.compose.ui.unit.TextUnit) {
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

/** Прежнее имя подписи раздела — теперь та же [PaperLabel], чтобы подписи не расходились. */
@Composable
internal fun SectionLabel(text: String) = ru.zf.pravka.ui.PaperLabel(text)

/**
 * Служебные экраны (Разборы, Обучение, Логи, Словарь, Промпты, Настройки)
 * до 24.09.2026 лежали на голой карточке Material — без фаски, света, зерна
 * и узора, и выглядели другим приложением рядом с вкладками. Теперь это та
 * же плашка, что везде.
 */
@Composable
internal fun SectionCard(
    label: String? = null,
    info: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) = ru.zf.pravka.ui.PaperCard(label = label, info = info, content = content)

@Composable
internal fun HintText(text: String) = ru.zf.pravka.ui.PaperHint(text)

@Composable
internal fun SpeechSection(
    settings: Settings,
    whisperProvider: ru.zf.pravka.provider.WhisperProvider,
) {
    val context = LocalContext.current
    // App-lifetime scope for persistence: rememberCoroutineScope dies with
    // the composition and cancels DataStore/file writes mid-flight when the
    // owner switches tabs (the "принял четыре правила, записалось одно" bug
    // class - fixed in Learning, but these older tabs kept the old scope).
    val scope = (LocalContext.current.applicationContext as PravkaApp).appScope
    val engine by settings.speechEngineFlow.collectAsState(initial = Settings.SPEECH_GOOGLE)
    // Путь Google: офлайн-пакет (заводское) или системный с сетью — см. Settings.
    val network by settings.speechNetworkFlow.collectAsState(initial = false)
    var status by remember { mutableStateOf("…") }
    var downloading by remember { mutableStateOf(false) }

    val isGoogle = engine == Settings.SPEECH_GOOGLE
    suspend fun statusFor(e: String): String = when {
        e == Settings.SPEECH_GOOGLE ->
            if (ru.zf.pravka.provider.GoogleSpeechSession.isAvailable(context)) {
                val onDevice = ru.zf.pravka.provider.GoogleSpeechSession.isOnDevice(context)
                val path = when {
                    // Сетевой путь — тот, каким клавиатура Google идёт на русском;
                    // офлайн-пакет остаётся и её, и нашим запасом без сети.
                    network && onDevice -> " · путь: облако, офлайн-пакет — запас"
                    network -> " · путь: облако; офлайн-пакета нет — без сети распознавать нечем"
                    onDevice -> " · путь: офлайн-пакет на устройстве"
                    else -> " · офлайн-пакета нет — пока распознаёт сетевой сервис, скачай русскую модель"
                }
                // Кто именно распознаёт по сети: «облако» в настройке ещё не
                // значит «Google» — системная служба по умолчанию на части
                // телефонов своя. Владелец спросил прямо: точно ли главный
                // гугловский? Значит, это должно быть видно, а не угадываться.
                val service =
                    if (network) "\nСлужба: " + ru.zf.pravka.provider.GoogleSpeechSession.networkServiceLabel(context)
                    else ""
                context.getString(R.string.google_ready) + path + service
            } else context.getString(R.string.google_unavailable)
        else -> whisperProvider.statusText(e)
    }

    LaunchedEffect(engine, downloading, network) { status = statusFor(engine) }

    // Движок и путь — чипами (24.09.2026): три радиокнопки с подписью на две
    // строки читались анкетой; выбор из трёх виден разом и одним тапом.
    SectionCard(
        label = stringResource(R.string.settings_speech_title),
        info = context.getString(if (isGoogle) R.string.speech_hint_google else R.string.speech_hint),
    ) {
        PaperHint(stringResource(R.string.speech_engine_label))
        Spacer(Modifier.height(4.dp))
        ChipRow {
            PaperChip("Google", selected = isGoogle, onClick = { scope.launch { settings.setSpeechEngine(Settings.SPEECH_GOOGLE) } })
            PaperChip(
                "Whisper small",
                selected = engine == Settings.SPEECH_WHISPER_SMALL,
                onClick = { scope.launch { settings.setSpeechEngine(Settings.SPEECH_WHISPER_SMALL) } },
            )
            PaperChip(
                "Whisper base",
                selected = engine == Settings.SPEECH_WHISPER_BASE,
                onClick = { scope.launch { settings.setSpeechEngine(Settings.SPEECH_WHISPER_BASE) } },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(status, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PaperButton(
                when {
                    isGoogle -> stringResource(R.string.google_prepare)
                    downloading -> stringResource(R.string.speech_downloading)
                    else -> stringResource(R.string.speech_download)
                },
                icon = Glyphs.Download,
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
            )
            GlyphButton(Glyphs.Refresh, stringResource(R.string.speech_refresh), onClick = { scope.launch { status = statusFor(engine) } })
        }
    }
    if (isGoogle) {
        // Recognition mode: continuous (build 55, "распознаёт идеально")
        // vs per-segment restarts - side-by-side comparison by the owner.
        val segmented by settings.speechSegmentedFlow.collectAsState(initial = true)
        val formatting by settings.speechFormattingFlow.collectAsState(initial = false)
        val biasingOn by settings.speechBiasingFlow.collectAsState(initial = true)
        SectionCard(
            label = "путь google",
            info = "Клавиатура Google на русском распознаёт на серверах Google (пиксельная модель " +
                "Assistant русского не знает) — поэтому она чётче на именах, редких словах и " +
                "английских терминах. Облачный путь — та же дорога; без сети сам падает на " +
                "офлайн-пакет. Службу при этом называем явно (пакет Google), а не полагаемся " +
                "на выбранную системой: на части телефонов там стоит своя, и «сеть» в " +
                "настройке ещё не значила бы «Google» — строка «Служба» выше показывает, " +
                "кто отвечает на самом деле. В «Расшифровках» такие тейки значатся " +
                "«Google (сеть)». Действует со следующей диктовки.",
        ) {
            ChipRow {
                PaperChip("Облако", selected = network, onClick = { scope.launch { settings.setSpeechNetwork(true) } }, icon = Glyphs.Wifi)
                PaperChip("Офлайн-пакет", selected = !network, onClick = { scope.launch { settings.setSpeechNetwork(false) } }, icon = Glyphs.Phone)
            }
            PaperHint(
                if (network) "как голосовой ввод клавиатуры на русском (заводское)"
                else "без сети, голос не уходит"
            )
            Spacer(Modifier.height(10.dp))
            PaperHint("Режим распознавания")
            Spacer(Modifier.height(4.dp))
            ChipRow {
                PaperChip("Непрерывный", selected = segmented, onClick = { scope.launch { settings.setSpeechSegmented(true) } })
                PaperChip("Посегментный", selected = !segmented, onClick = { scope.launch { settings.setSpeechSegmented(false) } })
            }
            PaperHint(
                if (segmented) "одна сессия, без перезапусков (как в сборке 55)"
                else "перезапуск на каждой паузе"
            )
            Spacer(Modifier.height(6.dp))
            PaperToggle(
                title = "Подсказывать слова словаря",
                checked = biasingOn,
                onCheckedChange = { on -> scope.launch { settings.setSpeechBiasing(on) } },
                info = "До 40 верных форм из словаря уходят движку подсказками, слова владельца впереди " +
                    "заводских. Кажется медленнее клавиатуры Google — выключи и сравни; в логе " +
                    "диктовки видно «ready +N ms» и «first partial +N ms».",
            )
            PaperToggle(
                title = "Пунктуация распознавателя",
                checked = formatting,
                onCheckedChange = { on -> scope.launch { settings.setSpeechFormatting(on) } },
                info = "Выключено (как в сборке 55): распознаватель отдаёт сырой поток слов, " +
                    "знаки расставляет Правка. Действует со следующей диктовки.",
            )
        }
    }
}

@Composable
internal fun RecordingsSection(recordings: ru.zf.pravka.data.Recordings, serviceEnabled: Boolean) {
    val context = LocalContext.current
    // listFiles() plus a length() stat per file - off the composition pass.
    var items by remember { mutableStateOf<List<ru.zf.pravka.data.Recordings.Item>>(emptyList()) }
    var busyId by remember { mutableStateOf<String?>(null) }
    // NB: not named `ru` - that would shadow the `ru.zf.pravka` package.
    val loc = remember { Locale.forLanguageTag("ru") }
    var refreshTick by remember { mutableStateOf(0) }
    LaunchedEffect(refreshTick) {
        val found = withContext(Dispatchers.IO) { recordings.list() }
        items = found
    }
    if (items.isEmpty()) return

    SectionCard(label = stringResource(R.string.rec_header), info = stringResource(R.string.rec_hint)) {
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
                // «Расшифровать» — главное действие записи, удаление — значком
                // корзины цветом ошибки (24.09.2026): два слова рядом читались
                // равными, а равными они не бывают.
                PaperButton(
                    stringResource(R.string.rec_transcribe),
                    icon = Glyphs.Spark,
                    primary = true,
                    enabled = busyId == null,
                    onClick = {
                        val service = PravkaAccessibilityService.instance
                        if (service == null || !serviceEnabled) {
                            Feedback.toast(context, context.getString(R.string.rec_need_service))
                        } else {
                            busyId = item.id
                            service.retryRecording(item.file) { ok: Boolean, msg: String ->
                                busyId = null
                                refreshTick++
                                if (!ok) Feedback.toast(context, context.getString(R.string.rec_failed, msg))
                            }
                        }
                    },
                )
                GlyphButton(
                    Glyphs.Delete,
                    stringResource(R.string.rec_delete),
                    onClick = {
                        recordings.delete(item.id)
                        refreshTick++
                    },
                    tint = MaterialTheme.colorScheme.error,
                )
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
                when (sug.mode) {
                    "PROTECT" -> DictMode.PROTECT
                    "HINT" -> DictMode.HINT
                    else -> DictMode.HARD
                },
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
            .padding(ScreenPad.Padding),
        verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
    ) {
        // Автообучения больше нет (владелец, 15.09.2026: «он уже обучился
        // достаточно, оставить только по кнопке»): ни слежки за полями, ни
        // авторазбора по расписанию. Разбор — только отсюда и из меню «П».
        SectionCard(
            label = "Разбор по кнопке",
            info = "Одно поправленное слово уходит в словарь само, без модели. Здесь — " +
                "сложные правки очередью для Опуса; «Обучить» в меню «П» разбирает " +
                "текст под курсором сразу. Находки — в словарь с пометкой «авто-обучение».",
        ) {
            var watch by remember { mutableStateOf<List<ru.zf.pravka.data.EditWatchStore.Entry>>(emptyList()) }
            var watchTick by remember { mutableStateOf(0) }
            var lastBatch by remember { mutableStateOf(0L) }
            LaunchedEffect(watchTick, loadTick) {
                watch = app.editWatch.all()
                // Prefs read off the composition pass: recomposition is not
                // the place for disk IO.
                lastBatch = ctx.getSharedPreferences(
                    PravkaAccessibilityService.PREFS_INTERNAL, android.content.Context.MODE_PRIVATE,
                ).getLong(PravkaAccessibilityService.KEY_LAST_LEARN_BATCH, 0L)
            }
            val fmt = remember { java.text.SimpleDateFormat("dd.MM HH:mm", Locale.forLanguageTag("ru")) }
            val edited = watch.count { it.editedTs > 0 }
            Text(
                "В наблюдении текстов: ${watch.size}, из них правленых: $edited." +
                    (if (lastBatch > 0) "\nПоследний разбор: " + fmt.format(java.util.Date(lastBatch)) + "." else ""),
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(Glyphs.Refresh, "обновить", onClick = {
                    watchTick++
                    loadTick++
                    Feedback.toast(ctx, "Обновлено")
                })
                Spacer(Modifier.weight(1f))
                PaperButton(
                    if (analyzing) "Разбираю…" else "Разобрать сейчас",
                    icon = Glyphs.Spark,
                    primary = true,
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
                )
            }
        }

        // Три столбца владельца: надиктовано → модель → он. Журнал навсегда
        // (data/CorrectionsLog.kt), выгрузка CSV — в статистике диктовки.
        SectionCard(label = "Правки руками") {
            var rows by remember { mutableStateOf<List<ru.zf.pravka.data.CorrectionsLog.Entry>>(emptyList()) }
            LaunchedEffect(loadTick) { rows = app.corrections.all().takeLast(12).asReversed() }
            val fmtC = remember { java.text.SimpleDateFormat("dd.MM HH:mm", Locale.forLanguageTag("ru")) }
            if (rows.isEmpty()) {
                HintText("Пока пусто: правь текст, который прислала Правка, — правка запишется сюда.")
            } else {
                HintText("Надиктовано → модель → ты; справа — что с этим сделано. Все строки — CSV в выгрузке статистики диктовки.")
                for (r in rows) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        fmtC.format(java.util.Date(r.ts)) + " · " + r.pkg.substringAfterLast('.') + " · " +
                            when {
                                r.result.startsWith("dict:") -> "в словарь: " + r.result.substringAfter(':').substringAfter(':')
                                r.result == "pending" -> "ждёт «Разобрать сейчас»"
                                r.result.startsWith("same:") -> "уже в словаре"
                                else -> r.result
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    // Три столбца значками, а не эмодзи (24.09.2026): микрофон —
                    // надиктовано, искры — модель, карандаш — ты.
                    MarkedLine(Glyphs.Mic, r.dictated.take(160), soft = true)
                    MarkedLine(Glyphs.Spark, r.cleaned.take(160), soft = true)
                    MarkedLine(Glyphs.Edit, r.edited.take(160), soft = false)
                }
            }
        }

        SectionCard(label = "Предложения (${pending.size})") {
            if (pending.isEmpty()) {
                HintText(
                    "Пусто — и так и останется: разбор правок больше не придумывает " +
                        "правил, а словарные находки («одно слово вместо другого») " +
                        "уходят в словарь сами, с пометкой «авто-обучение»."
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PaperTextButton("Отклонить все", color = MaterialTheme.colorScheme.error, onClick = {
                        app.appScope.launch {
                            for (sug in app.learnStore.all()) {
                                app.learnLog.add("отклонено: ${if (sug.kind == "rule") sug.text else sug.from}")
                                app.learnStore.remove(sug.id)
                            }
                            refresh()
                            PravkaAccessibilityService.instance?.refreshLearnBadge()
                        }
                    })
                    Spacer(Modifier.weight(1f))
                    PaperButton("Принять все", icon = Glyphs.Check, primary = true, onClick = {
                        app.appScope.launch {
                            for (sug in app.learnStore.all()) accept(sug)
                            refresh()
                            PravkaAccessibilityService.instance?.refreshLearnBadge()
                        }
                    })
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
                            PaperTextButton("Принять", icon = Glyphs.Check, onClick = {
                                app.appScope.launch {
                                    accept(sug)
                                    refresh()
                                    PravkaAccessibilityService.instance?.refreshLearnBadge()
                                }
                            })
                            PaperTextButton("Отклонить", icon = Glyphs.Close, color = MaterialTheme.colorScheme.error, onClick = {
                                app.appScope.launch {
                                    app.learnLog.add("отклонено: ${if (sug.kind == "rule") sug.text else sug.from}")
                                    app.learnStore.remove(sug.id)
                                    refresh()
                                    PravkaAccessibilityService.instance?.refreshLearnBadge()
                                }
                            })
                        }
                    }
                }
            }
        }

        SectionCard(
            label = "Принятые правила (${rules.size})",
            info = "Набор правится только руками: новых правил разбор не предлагает, " +
                "сам себя набор не переписывает. «Оптимизировать» — Опус сливает дубли " +
                "и противоречия, с предпросмотром. В запрос чистки правила уходят, если " +
                "включён тумблер «Постоянные правила в промпте» в настройках Правки.",
        ) {
            if (rules.isEmpty()) {
                HintText("Принятые правила появятся здесь.")
            } else {
                var optimizing by remember { mutableStateOf(false) }
                var optimized by remember {
                    mutableStateOf<ru.zf.pravka.provider.ClaudeProvider.OptimizedRules?>(null)
                }
                if (rules.size >= 2) {
                    PaperButton(
                        if (optimizing) "Оптимизирую (Опус)…" else "Оптимизировать набор",
                        icon = Glyphs.Spark,
                        enabled = !optimizing,
                        onClick = {
                            optimizing = true
                            app.appScope.launch {
                                val result = app.claudeProvider.optimizeRules(rules)
                                optimizing = false
                                result.onSuccess { opt ->
                                    app.stats.recordAux(opt.costUsd, opt.tokensIn, opt.tokensOut, route = ru.zf.pravka.data.ModelRoute.PRAVKA_LEARN.key)
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
                    )
                    Spacer(Modifier.height(6.dp))
                }
                optimized?.let { opt ->
                    PaperAlert(
                        onDismiss = { optimized = null },
                        title = "Оптимизированный набор",
                        icon = Glyphs.Spark,
                        subtitle = "${rules.size} → ${opt.rules.size} правил",
                        dismiss = SheetAction("Отмена") { optimized = null },
                        confirm = SheetAction("Заменить набор", icon = Glyphs.Check) {
                            val chosen = opt.rules
                            optimized = null
                            app.appScope.launch {
                                app.rulesStore.replaceAll(chosen.map { Triple(it.text, it.before, it.after) })
                                app.learnLog.add("набор правил ЗАМЕНЁН оптимизированным (${chosen.size})")
                                loadTick++
                            }
                        },
                    ) {
                        opt.rules.forEachIndexed { i, r ->
                            Text("${i + 1}. ${r.text}", style = MaterialTheme.typography.bodyMedium)
                            if (r.before.isNotBlank()) {
                                Text(
                                    "«${r.before}» → «${r.after}»",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                // Что реально уезжает в промпт: потолок RulesStore.PROMPT_CAP по
                // порядку. Владелец видел 54 одобренных правила и думал, что
                // работают все; работали первые восемь (16.09.2026).
                val fit = remember(rules) { ru.zf.pravka.data.RulesStore.fitsInPrompt(rules) }
                rules.forEachIndexed { i, rule ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${i + 1}. ${rule.text}", style = MaterialTheme.typography.bodyMedium)
                            if (rule.enabled && !rule.pending && rule.id !in fit) {
                                Text(
                                    "в промпт не попадает: потолок ${ru.zf.pravka.data.RulesStore.PROMPT_CAP} знаков исчерпан правилами выше",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (rule.exampleBefore.isNotBlank() && rule.exampleAfter.isNotBlank()) {
                                Text(
                                    "«${rule.exampleBefore}» → «${rule.exampleAfter}»",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { on ->
                                app.appScope.launch { app.rulesStore.setEnabled(rule.id, on); refresh() }
                            },
                        )
                        GlyphButton(
                            Glyphs.Delete,
                            "удалить правило",
                            tint = MaterialTheme.colorScheme.error,
                            onClick = {
                                app.appScope.launch {
                                    app.learnLog.add("правило удалено: ${rule.text}")
                                    app.rulesStore.delete(rule.id)
                                    refresh()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Строка журнала правок со значком столбца: надиктовано, модель, ты. */
@Composable
private fun MarkedLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, soft: Boolean) {
    val color = if (soft) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 2.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(top = 2.dp).size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

// The logs tab: the few logs that matter, each with copy and file export -
// "нажал и показал" instead of hunting through screens.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogsTab(app: PravkaApp) {
    val context = LocalContext.current
    var exportOpen by remember { mutableStateOf(false) }
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
            .padding(ScreenPad.Padding),
        verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
    ) {
        // Выгрузки для разбора — наверху, одной кнопкой каждая (владелец,
        // 16.09.2026: искал расшифровки в «Логах», а они жили за значком
        // выгрузки в «Статистике диктовки», куда из «Ещё» хода нет). Каждая
        // кнопка отдаёт файл целиком; иной период — тем же диалогом, что там.
        SectionCard(
            label = "Выгрузить для разбора",
            info = "Файл целиком в шаринг — Drive, почта, чат. Расшифровки — сырой выход " +
                "распознавателя; история — надиктовано и правка модели; правки руками — " +
                "надиктовано · модель · ты; словарь и правила — то, что уходит в промпт. " +
                "Иной период — «За период…».",
        ) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogExport(onClick = {
                    share(app.transcriptionLog.shareJsonIntent(), "Расшифровки")
                }, label = "Расшифровки (JSON)")
                LogExport(onClick = {
                    share(app.historyLog.shareIntent(), "История правок")
                }, label = "История правок (JSONL)")
                LogExport(onClick = {
                    // shareCsvIntent — suspend: собирается в области приложения.
                    app.appScope.launch {
                        val intent = runCatching { app.corrections.shareCsvIntent() }.getOrNull()
                        if (intent == null) Feedback.toast(context, "Не собралась")
                        else share(intent, "Правки руками")
                    }
                }, label = "Правки руками (CSV)")
                LogExport(onClick = {
                    share(app.transcriptionLog.shareMetricsCsvIntent(), "Метрики диктовки")
                }, label = "Метрики (CSV)")
                LogExport(onClick = {
                    // exportJson — suspend: собирается в области приложения, файл во
                    // временной папке, как у выгрузки во вкладке «Словарь».
                    app.appScope.launch {
                        val intent = runCatching {
                            val f = File(context.cacheDir, "pravka_dictionary.json")
                            f.writeText(app.dictionaryStore.exportJson())
                            ru.zf.pravka.data.shareFileIntent(context, f, "application/json")
                        }.getOrNull()
                        if (intent == null) Feedback.toast(context, "Не собрался") else share(intent, "Словарь")
                    }
                }, label = "Словарь (JSON)")
                LogExport(onClick = { share(app.rulesStore.shareIntent(), "Правила") }, label = "Правила (JSON)")
                PaperButton("За период…", icon = Glyphs.Calendar, onClick = { exportOpen = true })
            }
        }
        if (exportOpen) DictationExportDialog(app, onDismiss = { exportOpen = false })

        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            PaperTextButton("Обновить логи", icon = Glyphs.Refresh, onClick = { loadTick++ })
        }

        SectionCard(
            label = "Эвал промпта",
            info = "Золотой набор: вход диктовки и эталонный результат. Каждое " +
                "изменение промпта прогоняется по набору и меряется цифрой. " +
                "Запросы той же формы, что дневная чистка, по одному — минута-две.",
        ) {
            var items by remember { mutableStateOf<List<ru.zf.pravka.data.EvalStore.Item>>(emptyList()) }
            var evalTick by remember { mutableStateOf(0) }
            var showSet by remember { mutableStateOf(false) }
            var running by remember { mutableStateOf(ru.zf.pravka.core.EvalRunner.running) }
            var progress by remember { mutableStateOf(0 to 0) }
            var evalStage by remember { mutableStateOf("") }
            var last by remember { mutableStateOf<org.json.JSONObject?>(null) }
            LaunchedEffect(evalTick) {
                items = app.evalStore.all()
                // File read off the composition pass.
                last = withContext(Dispatchers.IO) { app.evalStore.lastRun() }
            }
            LaunchedEffect(running) {
                while (ru.zf.pravka.core.EvalRunner.running) {
                    progress = ru.zf.pravka.core.EvalRunner.done to ru.zf.pravka.core.EvalRunner.total
                    evalStage = ru.zf.pravka.core.EvalRunner.stage
                    kotlinx.coroutines.delay(1500)
                }
                running = false
                evalTick++
            }
            Text("Эталонов: ${items.size}", style = MaterialTheme.typography.bodyMedium)
            last?.let { run ->
                Text(
                    "Последний прогон: средний ${"%.1f".format(run.optDouble("avg") * 100)}%, " +
                        "точных ${run.optInt("exact")} из ${run.optInt("total")}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (running) {
                Text(
                    "Идёт прогон: ${progress.first}/${progress.second}" + (if (evalStage.isNotBlank()) " · $evalStage" else "") + "…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PaperButton("Из истории", icon = Glyphs.Download, onClick = {
                    app.appScope.launch {
                        val added = app.evalStore.addAll(
                            withContext(Dispatchers.IO) { app.historyLog.readPairs(40) }
                        )
                        Feedback.toast(context, "Добавлено эталонов: $added")
                        evalTick++
                    }
                })
                Spacer(Modifier.weight(1f))
                PaperButton(
                    if (running) "Идёт…" else "Прогнать",
                    icon = Glyphs.Play,
                    primary = true,
                    enabled = !running && items.isNotEmpty(),
                    onClick = {
                        ru.zf.pravka.core.EvalRunner.start(app)
                        running = true
                    },
                )
            }
            PaperTextButton(
                if (showSet) "Скрыть набор" else "Показать набор",
                icon = if (showSet) Glyphs.ChevronUp else Glyphs.ChevronDown,
                onClick = { showSet = !showSet },
            )
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
                        GlyphButton(
                            Glyphs.Delete,
                            "убрать эталон",
                            tint = MaterialTheme.colorScheme.error,
                            onClick = { app.appScope.launch { app.evalStore.remove(item.id); evalTick++ } },
                        )
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
                PaperTextButton("Копировать", icon = Glyphs.Copy, onClick = { copy(eventTail.joinToString("\n")) })
                PaperTextButton("Файлом", icon = Glyphs.Share, onClick = { share(app.eventLog.shareIntent(), "Журнал событий") })
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
                PaperTextButton("Копировать", icon = Glyphs.Copy, onClick = { copy(learnTail.joinToString("\n")) })
                PaperTextButton("Файлом", icon = Glyphs.Share, onClick = { share(app.learnLog.shareIntent(), "Журнал обучения") })
            }
        }

        SectionCard(label = "Запросы к Claude (отладка)") {
            val debugOn by app.settings.debugLogFlow.collectAsState(initial = false)
            var reqTail by remember { mutableStateOf<List<String>>(emptyList()) }
            var reqTick by remember { mutableStateOf(0) }
            LaunchedEffect(reqTick, loadTick) {
                reqTail = withContext(Dispatchers.IO) { app.requestLog.readLast(60) }
            }
            HintText(
                if (debugOn) "Режим отладки включён: сюда пишется каждый запрос целиком."
                else "Режим отладки выключен (Настройки → Обновления и служба → Отладка). Ниже — что успело записаться."
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (reqTail.isEmpty()) "Пусто." else reqTail.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 40,
            )
            Row {
                PaperTextButton("Файлом", icon = Glyphs.Share, onClick = { share(app.requestLog.shareIntent(), "Запросы к Claude") })
                PaperTextButton("Очистить", icon = Glyphs.Delete, color = MaterialTheme.colorScheme.error, onClick = {
                    app.requestLog.clear()
                    reqTick++
                    Feedback.toast(context, "Лог запросов очищен")
                })
            }
        }
    }
}

/** Кнопка выгрузки файла из «Логов»: одна форма на все семь. */
@Composable
private fun LogExport(onClick: () -> Unit, label: String) {
    PaperButton(label, icon = Glyphs.Export, onClick = onClick)
}

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
    // App-lifetime scope for persistence: rememberCoroutineScope dies with
    // the composition and cancels DataStore/file writes mid-flight when the
    // owner switches tabs (the "принял четыре правила, записалось одно" bug
    // class - fixed in Learning, but these older tabs kept the old scope).
    val scope = (LocalContext.current.applicationContext as PravkaApp).appScope
    val entries by store.entriesFlow.collectAsState()
    var search by remember { mutableStateOf("") }
    var dialogEntry by remember { mutableStateOf<DictEntry?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var mining by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<ru.zf.pravka.provider.DictMiner.Suggestion>?>(null) }
    var picked by remember { mutableStateOf<Set<Int>>(emptySet()) }
    // Разделы свёрнуты, пока не тапнули по заголовку (владелец, 15.09.2026:
    // «иначе приходится крутить очень долго»); поиск раскрывает все.
    var opened by remember { mutableStateOf<Set<DictMode>>(emptySet()) }

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
        contentPadding = ScreenPad.Padding,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            // Поиск и действия одной плашкой (24.09.2026): три слова-кнопки
            // над полем читались строкой текста, а не кнопками.
            ru.zf.pravka.ui.PaperCard {
                PaperField(
                    value = search,
                    onValueChange = { search = it },
                    label = stringResource(R.string.dict_search),
                    trailing = { Icon(Glyphs.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphButton(Glyphs.Upload, stringResource(R.string.dict_export), onClick = { export() })
                    GlyphButton(Glyphs.Download, stringResource(R.string.dict_import), onClick = {
                        importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    })
                    PaperTextButton(
                        stringResource(if (mining) R.string.dict_mine_running else R.string.dict_mine),
                        icon = Glyphs.Spark,
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
                    )
                    Spacer(Modifier.weight(1f))
                    PaperButton(stringResource(R.string.dict_add), icon = Glyphs.Plus, primary = true, onClick = { showAddDialog = true })
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        for (mode in DictMode.entries) {
            val sectionEntries = section(mode)
            val expanded = query.isNotEmpty() || mode in opened
            item(key = "header_$mode") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { opened = if (mode in opened) opened - mode else opened + mode }
                        .padding(top = 14.dp, bottom = 6.dp, start = 4.dp),
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
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Glyphs.ChevronDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp).rotate(if (expanded) 180f else 0f),
                    )
                }
            }
            if (expanded) items(sectionEntries, key = { it.id }) { entry ->
                DictRow(entry, onClick = { dialogEntry = entry }, onToggle = { enabled ->
                    scope.launch { store.update(entry.copy(enabled = enabled)) }
                })
            }
        }
    }

    suggestions?.let { list ->
        PaperAlert(
            onDismiss = { suggestions = null },
            title = stringResource(R.string.dict_mine_title),
            icon = Glyphs.Spark,
            subtitle = "отмечено ${picked.size} из ${list.size}",
            dismiss = SheetAction(stringResource(R.string.dict_cancel)) { suggestions = null },
            confirm = SheetAction(stringResource(R.string.dict_mine_add), icon = Glyphs.Plus) {
                val chosen = list.filterIndexed { i, _ -> i in picked }
                suggestions = null
                scope.launch {
                    for (sug in chosen) store.add(sug.from, sug.to, sug.mode, sug.note)
                    Feedback.toast(context, context.getString(R.string.dict_mine_added, chosen.size))
                }
            },
        ) {
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
    // Строка словаря — на материале плашки, а не голой карточкой Material
    // (24.09.2026): фаска и тон те же, что у всех плашек.
    val look = ru.zf.pravka.ui.LocalCardLook.current
    val shape = MaterialTheme.shapes.medium
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().then(if (look.bevel) Modifier.bevel(shape) else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = ru.zf.pravka.ui.darkened(MaterialTheme.colorScheme.surfaceContainerLow, look.darken),
        ),
    ) {
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

    PaperAlert(
        onDismiss = onDismiss,
        title = stringResource(if (entry == null) R.string.dict_add else R.string.dict_edit),
        icon = Glyphs.Dictionary,
        subtitle = entry?.let { if (it.hits > 0) "сработало ×${it.hits}" else null },
        destructive = onDelete?.let { SheetAction(stringResource(R.string.dict_delete), onClick = it) },
        confirm = SheetAction(stringResource(R.string.settings_save), icon = Glyphs.Check, enabled = from.isNotBlank()) {
            if (from.isNotBlank()) onSave(from, to, mode, note, enabled)
        },
    ) {
        PaperField(
            value = from,
            onValueChange = { from = it },
            label = stringResource(R.string.dict_from),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        )
        if (mode != DictMode.PROTECT) {
            PaperField(
                value = to,
                onValueChange = { to = it },
                label = stringResource(R.string.dict_to),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            )
        }
        ChipRow {
            for (m in DictMode.entries) {
                PaperChip(
                    stringResource(
                        when (m) {
                            DictMode.HARD -> R.string.dict_mode_hard
                            DictMode.HINT -> R.string.dict_mode_hint
                            DictMode.PROTECT -> R.string.dict_mode_protect
                        }
                    ),
                    selected = mode == m,
                    onClick = { mode = m },
                )
            }
        }
        if (mode == DictMode.HINT) {
            PaperField(
                value = note,
                onValueChange = { note = it },
                label = stringResource(R.string.dict_note),
                singleLine = false,
            )
        }
        if (entry != null) {
            PaperToggle(stringResource(R.string.dict_enabled), enabled, { enabled = it })
        }
    }
}

// ---------------------------------------------------------------------------
// Prompts
// ---------------------------------------------------------------------------

private val promptTitles = mapOf(
    PromptStore.PromptId.CLEAN_CLAUDE to R.string.prompt_title_clean_claude,
    PromptStore.PromptId.BUSINESS to R.string.prompt_title_business,
    PromptStore.PromptId.SOFTEN to R.string.prompt_title_soften,
    PromptStore.PromptId.PROSE to R.string.prompt_title_prose,
    PromptStore.PromptId.MEETING to R.string.prompt_title_meeting,
    PromptStore.PromptId.TASKS to R.string.prompt_title_tasks,
    PromptStore.PromptId.FOOD to R.string.prompt_title_food,
    PromptStore.PromptId.MONEY to R.string.prompt_title_money,
    PromptStore.PromptId.MONEY_MATCH to R.string.prompt_title_money_match,
    PromptStore.PromptId.MONEY_ASK to R.string.prompt_title_money_ask,
    PromptStore.PromptId.MONEY_PATTERNS to R.string.prompt_title_money_patterns,
    PromptStore.PromptId.MONEY_ANSWER to R.string.prompt_title_money_answer,
    PromptStore.PromptId.COACH to R.string.prompt_title_coach,
    PromptStore.PromptId.TRAINER to R.string.prompt_title_trainer,
    PromptStore.PromptId.BODY to R.string.prompt_title_body,
    PromptStore.PromptId.RULES to R.string.prompt_title_rules,
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

/**
 * Набор промптов: отдать свои тексты другой установке и принять чужие
 * (владелец, 25.09.2026: Марианне «промпты надо дать мои»). Свои правки и
 * принятые недельной правкой версии живут только в базе владельца — без
 * набора у неё были бы заводские из APK. Род и «без прозы» у получателя
 * накладываются при сборке запроса, текст набора не трогается.
 */
@Composable
private fun PromptSetCard(promptStore: PromptStore) {
    val context = LocalContext.current
    val app = context.applicationContext as PravkaApp
    val scope = app.appScope
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                }
                val (from, texts) = ru.zf.pravka.data.PromptSet.decode(text)
                val n = promptStore.importSet(texts)
                app.eventLog.add("промпты: принят набор от ${from.ifBlank { "?" }} — $n")
                if (n == 0) "В наборе нет своих текстов — у отправителя заводские, как и здесь"
                else "Принято промптов: $n" + (if (from.isNotBlank()) " (от $from)" else "")
            }.getOrElse { e -> "Набор не принят: ${e.message ?: e.javaClass.simpleName}" }
            Feedback.toast(context, result)
        }
    }
    SectionCard(
        label = "Набор промптов",
        info = "«Поделиться» собирает в один файл все промпты, которые здесь правили руками " +
            "или приняла недельная правка, — отправь его на другой телефон. «Принять» кладёт " +
            "такие тексты поверх здешних. Заводские промпты у всех одинаковые, в набор они не " +
            "едут. У другого пользователя чистка сама пишет в его роде, а художественной " +
            "прозы у него нет — это накладывается при запросе, не в тексте.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperButton("Поделиться набором", icon = Glyphs.Share, onClick = {
                scope.launch {
                    runCatching {
                        val texts = promptStore.overrides().mapKeys { it.key.storageKey }
                        if (texts.isEmpty()) {
                            Feedback.toast(context, "Своих текстов нет — все промпты заводские")
                            return@launch
                        }
                        val from = app.profileStore.current?.name.orEmpty()
                        val file = withContext(Dispatchers.IO) {
                            File(context.cacheDir, "pravka-prompts.json").also {
                                it.writeText(ru.zf.pravka.data.PromptSet.encode(from, texts, System.currentTimeMillis()))
                            }
                        }
                        context.startActivity(
                            android.content.Intent.createChooser(
                                ru.zf.pravka.data.shareFileIntent(context, file, "application/json"),
                                "Набор промптов",
                            )
                        )
                    }.onFailure { e -> Feedback.toast(context, "Набор не собрался: ${e.message}") }
                }
            })
            PaperButton("Принять набор", icon = Glyphs.Download, onClick = {
                importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            })
        }
    }
}

@Composable
private fun PromptList(promptStore: PromptStore, onOpen: (PromptStore.PromptId) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ScreenPad.Padding),
        verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
    ) {
        PromptSetCard(promptStore)
        // Business-season workflow: Whisper transcribes meetings on the
        // owner's computer; this assembles the MEETING prompt + the FULL
        // current dictionary + the approved rules into one clipboard-ready
        // request for a Claude chat. Nothing is sent from the app.
        SectionCard(
            label = "Для встреч",
            info = "Собирает полный запрос для чистки расшифровки встречи — промпт " +
                "«Встреча» + весь текущий словарь + принятые правила — и кладёт " +
                "в буфер. Вставь его в чат с Клодом и добавь расшифровку.",
        ) {
            val ctx = LocalContext.current
            val app = ctx.applicationContext as PravkaApp
            PaperHint("Промпт «Встреча», словарь и правила — одним запросом в буфер.")
            Spacer(Modifier.height(8.dp))
            PaperButton("Скопировать промпт для встречи", icon = Glyphs.Copy, primary = true, onClick = {
                app.appScope.launch {
                    val template = promptStore.effective(PromptStore.PromptId.MEETING)
                    val entries = app.dictionaryStore.all().filter { it.enabled }
                    val dictListing = if (entries.isEmpty()) "—" else buildString {
                        for (e in entries) {
                            when (e.mode) {
                                DictMode.HARD -> append("«").append(e.from).append("» → «").append(e.to).append("»")
                                DictMode.HINT -> {
                                    append("«").append(e.from).append("» → «").append(e.to).append("»")
                                    if (e.note.isNotBlank()) append(" (").append(e.note).append(")")
                                }
                                DictMode.PROTECT -> append("«").append(e.from)
                                    .append("» — правильное написание, не менять")
                            }
                            append('\n')
                        }
                    }.trim()
                    val rulesBlock = app.rulesStore.enabledBlock()
                    val full = buildString {
                        append(template.replace(Prompts.PLACEHOLDER_DICT, dictListing))
                        if (rulesBlock.isNotBlank()) append("\n\n").append(rulesBlock)
                        append("\n\n=== РАСШИФРОВКА ВСТРЕЧИ (вставь ниже) ===\n")
                    }
                    ru.zf.pravka.target.ClipboardTarget(ctx).write(full)
                    Feedback.toast(ctx, "Скопировано: ${full.length} зн., словарь: ${entries.size}. Вставь в чат с Клодом.")
                }
            })
        }

        // Промпт ночного поиска паттернов не показываем: поиск снят (15.09.2026),
        // а сам текст остался в заводских на случай сохранённой правки.
        // Список — одной плашкой строк (24.09.2026), а не восемнадцать голых
        // карточек: так он читается оглавлением.
        ru.zf.pravka.ui.PaperCard(label = "промпты") {
            val ids = PromptStore.PromptId.entries.filter { it != PromptStore.PromptId.PATTERNS }
            ids.forEachIndexed { i, id ->
                if (i > 0) RowRule()
                val override by promptStore.overrideFlow(id).collectAsState(initial = null)
                val effective = override ?: promptStore.factory(id)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onOpen(id) }
                        .padding(vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            // getValue кидал NoSuchElement на промпте, для
                            // которого забыли заголовок, — и весь экран падал.
                            // Новый промпт не должен ронять Промпты: покажем
                            // его ключом, это уродливо и видно, что чинить.
                            promptTitles[id]?.let { stringResource(it) } ?: id.storageKey,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (override != null) {
                            Text(
                                stringResource(R.string.prompt_modified),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), MaterialTheme.shapes.small)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Icon(Glyphs.Forward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        effective.lineSequence().take(2).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
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
    // App-lifetime scope for persistence: rememberCoroutineScope dies with
    // the composition and cancels DataStore/file writes mid-flight when the
    // owner switches tabs (the "принял четыре правила, записалось одно" bug
    // class - fixed in Learning, but these older tabs kept the old scope).
    val scope = (LocalContext.current.applicationContext as PravkaApp).appScope
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

    // Системное «назад» возвращает к списку промптов, а не закрывает экран:
    // вторая кнопка «← Назад» под шапкой с «‹» снята (24.09.2026).
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ScreenPad.Padding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(Glyphs.Back, stringResource(R.string.prompt_back), onClick = onBack, tint = MaterialTheme.colorScheme.primary)
            Text(
                promptTitles[id]?.let { stringResource(it) } ?: id.storageKey,
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PaperButton(
                stringResource(if (confirmReset) R.string.prompt_reset_confirm else R.string.prompt_reset),
                icon = Glyphs.Undo,
                onClick = {
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
                },
            )
            Spacer(Modifier.weight(1f))
            PaperButton(
                stringResource(if (savedMark) R.string.settings_saved else R.string.settings_save),
                icon = if (savedMark) Glyphs.Check else null,
                primary = true,
                onClick = {
                    // Placeholders live only in the CLEAN master prompt; BUSINESS
                    // and SOFTEN are directives layered on top of it.
                    val ok = if (id == PromptStore.PromptId.CLEAN_CLAUDE) {
                        if (!text.contains(Prompts.PLACEHOLDER_INPUT)) {
                            error = R.string.prompt_error_no_input
                            false
                        } else {
                            warning = when {
                                !text.contains(Prompts.PLACEHOLDER_DICT) -> R.string.prompt_warning_no_dict
                                else -> null
                            }
                            true
                        }
                    } else true
                    if (ok) scope.launch {
                        promptStore.setOverride(id, text)
                        savedMark = true
                    }
                },
            )
        }
    }
}

