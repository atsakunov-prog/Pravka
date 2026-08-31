package ru.zf.pravka

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
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
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.trigger.PravkaAccessibilityService
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.PravkaTheme

// Tabs: Засечка (the daily surface), then the Правка service tabs.
// Editorial "proofreader" design: paper, ink, red pen (ui/Theme.kt).
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
        const val TAB_SETTINGS = "settings"
        const val TAB_ITOGI = "itogi"
        const val TAB_PROMPTS = "prompts"

        // Кнопка еды: «сфоткай тарелку» / «штрихкод» с длинного нажатия
        // открывают Тело (Е) и сразу запускают камеру или сканер.
        const val EXTRA_FOOD_ACTION = "food_action"
    }

    private val serviceEnabled = mutableStateOf(false)

    // «Открыть Засечку» из меню кнопки обязано приземлять именно на Засечку.
    // Раньше вкладка читалась только в onCreate — а приложение почти всегда
    // уже запущено, интент приходит в onNewIntent, и владелец оставался там,
    // где был («должно попадать на засечку»). Теперь просьба живёт состоянием,
    // и экран на неё реагирует в любой момент жизни активити.
    private val tabRequest = mutableStateOf<Tab?>(null)
    private val foodActionRequest = mutableStateOf("")

    private fun tabOf(intent: android.content.Intent?): Tab? =
        when (intent?.getStringExtra(EXTRA_TAB)) {
            TAB_SETTINGS -> Tab.SETTINGS
            TAB_ITOGI -> Tab.ITOGI
            TAB_PROMPTS -> Tab.PROMPTS
            TAB_PRAVKA -> Tab.PRAVKA
            TAB_ZASECHKA -> Tab.ZASECHKA
            TAB_TODOIST -> Tab.TODOIST
            TAB_SPORT -> Tab.SPORT
            TAB_FOOD -> Tab.FOOD
            else -> null
        }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tabOf(intent)?.let { tabRequest.value = it }
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
        setContent {
            PravkaTheme {
                MainScreen(
                    app = app,
                    initialTab = initialTab,
                    foodAction = foodAction,
                    tabRequest = tabRequest.value,
                    onTabRequestHandled = { tabRequest.value = null },
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

internal enum class Tab(val titleRes: Int) {
    PRAVKA(R.string.tab_pravka),
    ZASECHKA(R.string.tab_zasechka),
    TODOIST(R.string.tab_todoist),
    SPORT(R.string.tab_sport),
    FOOD(R.string.tab_food),
    MORE(R.string.tab_more),
    ITOGI(R.string.tab_itogi),
    EXPORT(R.string.tab_export),
    SETTINGS(R.string.tab_settings),
    DICTIONARY(R.string.tab_dictionary),
    PROMPTS(R.string.tab_prompts),
    TRANSCRIPTS(R.string.tab_transcripts),
    LEARNING(R.string.tab_learning),
    LOGS(R.string.tab_logs),
    STATS(R.string.tab_stats),
}

/**
 * Что живёт под «Ещё»: всё, что обслуживает Правку, а не день. Порядок — по
 * тому, как часто туда правда заходят.
 *
 * Список стоит рядом с enum нарочно: добавил вкладку — сразу видно, идёт она
 * вниз или сюда. Внизу места ровно на пять кнопок.
 */
private val SERVICE_TABS = listOf(
    Tab.ITOGI,
    Tab.EXPORT,
    Tab.SETTINGS,
    Tab.DICTIONARY,
    Tab.STATS,
    Tab.PROMPTS,
    Tab.LEARNING,
    Tab.LOGS,
)

/** Одна строка про то, зачем эта вкладка — чтобы не открывать её наугад. */
private fun serviceHint(tab: Tab): String = when (tab) {
    Tab.ITOGI -> "Повторы, которые Опус находит по всему логу каждую ночь"
    Tab.EXPORT -> "Вся жизнь одним CSV плюс запрос для чата с твоими паттернами"
    Tab.SETTINGS -> "Ключ Anthropic, распознавание, служба, сохранённые записи"
    Tab.DICTIONARY -> "Как писать имена и термины: заменять, подсказывать, не трогать"
    Tab.STATS -> "Токены и деньги по дням"
    Tab.TRANSCRIPTS -> "Журнал распознаваний с метриками: движок, время, символы"
    Tab.PROMPTS -> "Тексты запросов ко всем режимам — правятся и возвращаются к заводским"
    Tab.LEARNING -> "Правила, выученные на твоих правках, и находки для словаря"
    Tab.LOGS -> "Что делала служба: кнопки, свипы, выгрузки, ошибки"
    else -> ""
}

@Composable
private fun MoreList(onOpen: (Tab) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenTitle(stringResource(R.string.tab_more))
        HintText(
            "Служебное и выгрузки. Внизу — ежедневные режимы: Правка, " +
                "Засечка, Дело, Тело (спорт и еда)."
        )
        Spacer(Modifier.height(4.dp))
        for (item in SERVICE_TABS) {
            Card(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(item) }
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(item.titleRes),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(2.dp))
                        HintText(serviceHint(item))
                    }
                    Icon(
                        Icons.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Выгрузка всего одним файлом — владелец: «файл, где было бы и таймшит, и
 * тело еда и тело спорт, чтобы разбирать». Сам файл собирает DigestBuilder,
 * тот же, что кнопка в «Сводке для чата»; здесь просто короткая дорога.
 */
@Composable
private fun ExportTab(app: PravkaApp) {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        ScreenTitle(stringResource(R.string.tab_export))
        HintText(
            "«Вся жизнь» — один CSV на все домены: таймшит Засечки, еда, " +
                "тренировки, силовые с подходами, зарядка с заметками и " +
                "комментарии. Строка на событие, хронологически, за всю " +
                "глубину хранения — файл кормят Клоду в чат. Начинается " +
                "легендой: что складывать можно только minutes при budget=1 " +
                "(и это ровно сутки), что параллель ссылается на своё дело " +
                "через parallel_of, а тренировки и еда — пометки на том же " +
                "времени, а не время сверх него. Тумблер «выгружать " +
                "параллельный трек» — в настройках Засечки."
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = {
                busy = true
                app.appScope.launch {
                    val intent = runCatching { app.digestBuilder.lifeCsvIntent() }.getOrNull()
                    busy = false
                    if (intent == null) {
                        Feedback.toast(app, "Не собрался — посмотри Логи")
                    } else {
                        runCatching {
                            context.startActivity(
                                android.content.Intent.createChooser(intent, "CSV всей жизни")
                            )
                        }
                    }
                }
            },
            enabled = !busy,
        ) { Text(if (busy) "Собираю…" else "CSV всей жизни") }
        Spacer(Modifier.height(16.dp))
        // Разбор владелец делает в чате, а не здесь. Единственное, чего у
        // чата нет и быть не может, — накопленные паттерны с ЕГО вердиктами:
        // они живут только в приложении. Кнопка ровно про это, и стоит она
        // вплотную к CSV, потому что копируются они парой.
        Text(
            "Запрос для чата",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        HintText(
            "Копирует в буфер текст запроса вместе со всеми паттернами, " +
                "которые ты подтвердил, и теми, что отклонил. Порядок такой: " +
                "выгрузи CSV, прицепи его в чат, вставь этот текст. " +
                "Отклонённые уезжают нарочно — чтобы он не предлагал их снова."
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                app.appScope.launch {
                    runCatching { app.analysisStore.load() }
                    val text = app.promptStore
                        .effective(PromptStore.PromptId.CHAT_HANDOFF)
                        .replace("{PATTERNS}", app.analysisStore.handoffBlock())
                        .replace("{TODAY}", app.analysisEngine.today())
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(
                        android.content.ClipData.newPlainText("Запрос для чата", text)
                    )
                    Feedback.toast(app, "Запрос скопирован — вставляй в чат", long = true)
                }
            },
        ) { Text("Скопировать запрос для чата") }
        Spacer(Modifier.height(14.dp))
        HintText(
            "Сводки текстом за день и неделю — во вкладке Тело (С), карточка " +
                "«Сводка для чата». Выгрузки одной Засечки и одной Еды — в их " +
                "вкладках. Сам текст запроса правится в «Ещё → Промпты»."
        )
    }
}

/** Шапка служебной вкладки: «‹ Ещё» и её название. */
@Composable
private fun MoreHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onBack() }
            .padding(start = 12.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "‹",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        HintText(stringResource(R.string.tab_more))
    }
}

@Composable
private fun MainScreen(
    app: PravkaApp,
    initialTab: Tab,
    foodAction: String = "",
    tabRequest: Tab? = null,
    onTabRequestHandled: () -> Unit = {},
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
    onOpenAccessibilitySettings: () -> Unit,
) {
    // Служебные вкладки живут под «Ещё». Ссылка снаружи (уведомление, меню
    // кнопки) может показывать прямо на служебную — тогда открываем «Ещё» и
    // сразу её.
    val service = remember { SERVICE_TABS }
    var tab by remember {
        mutableStateOf(if (initialTab in service) Tab.MORE else initialTab)
    }
    var moreTab by remember {
        mutableStateOf(if (initialTab in service) initialTab else null)
    }
    // Одноразовый автозапуск камеры/сканера в Теле (Е) — из меню кнопки еды.
    var foodActionPending by remember { mutableStateOf(foodAction.ifBlank { null }) }

    // Открытие приложения — тоже повод посмотреть, нет ли сборки свежее:
    // служба доступности может быть выключена, а суточный тик живёт в ней.
    LaunchedEffect(Unit) { runCatching { app.updates.tick() } }

    // Просьба извне при живом приложении: переключаемся и сообщаем, что
    // услышали, — иначе следующая перерисовка увела бы вкладку обратно.
    LaunchedEffect(tabRequest) {
        val want = tabRequest ?: return@LaunchedEffect
        if (want in service) {
            tab = Tab.MORE
            moreTab = want
        } else {
            tab = want
            moreTab = null
        }
        onTabRequestHandled()
    }
    LaunchedEffect(foodActionRequest) {
        if (foodActionRequest.isBlank()) return@LaunchedEffect
        foodActionPending = foodActionRequest
        onFoodActionHandled()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // Шесть кнопок — порядок и подписи владельца: Правка, Засечка,
            // Дело, Тело (С), Тело (Е), Ещё. Пиктограммы те же, что могут
            // встать на плавающие кнопки: перо, часы, галочка, гантеля,
            // тарелка — один язык на всё приложение.
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.PRAVKA,
                    onClick = { tab = Tab.PRAVKA },
                    icon = {
                        Icon(painterResource(R.drawable.ic_mode_pravka), contentDescription = null)
                    },
                    label = { Text(stringResource(Tab.PRAVKA.titleRes)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.ZASECHKA,
                    onClick = { tab = Tab.ZASECHKA },
                    icon = {
                        Icon(painterResource(R.drawable.ic_mode_zasechka), contentDescription = null)
                    },
                    label = { Text(stringResource(Tab.ZASECHKA.titleRes)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.TODOIST,
                    onClick = { tab = Tab.TODOIST },
                    icon = {
                        Icon(painterResource(R.drawable.ic_mode_delo), contentDescription = null)
                    },
                    label = { Text(stringResource(Tab.TODOIST.titleRes)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.SPORT,
                    onClick = { tab = Tab.SPORT },
                    icon = {
                        Icon(painterResource(R.drawable.ic_mode_sport), contentDescription = null)
                    },
                    label = { Text(stringResource(Tab.SPORT.titleRes)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.FOOD,
                    onClick = { tab = Tab.FOOD },
                    icon = {
                        Icon(painterResource(R.drawable.ic_mode_food), contentDescription = null)
                    },
                    label = { Text(stringResource(Tab.FOOD.titleRes)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.MORE,
                    // Повторный тап по «Ещё» возвращает список: иначе из
                    // Логов обратно к списку пришлось бы жать «назад».
                    onClick = { tab = Tab.MORE; moreTab = null },
                    icon = { Icon(Icons.Filled.MoreVert, contentDescription = null) },
                    label = { Text(stringResource(Tab.MORE.titleRes)) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            val open = moreTab
            if (tab == Tab.MORE && open == null) {
                MoreList(onOpen = { moreTab = it })
            } else {
                val shown = if (tab == Tab.MORE) open!! else tab
                if (tab == Tab.MORE) {
                    MoreHeader(
                        title = stringResource(shown.titleRes),
                        onBack = { moreTab = null },
                    )
                }
                // Из любой вкладки — в её группу настроек одним тапом.
                val openSettings = { tab = Tab.MORE; moreTab = Tab.SETTINGS }
                when (shown) {
                    Tab.PRAVKA -> TranscriptsTab(transcriptionLog, liveDraft, eventLog)
                    Tab.ZASECHKA -> ZasechkaTab(app, openSettings)
                    Tab.TODOIST -> TodoistTab(app, openSettings)
                    Tab.SPORT -> SportTab(app, openSettings)
                    Tab.FOOD -> FoodTab(
                        app, openSettings,
                        autoAction = foodActionPending,
                        onAutoConsumed = { foodActionPending = null },
                    )
                    Tab.ITOGI -> ItogiTab(app)
                    Tab.EXPORT -> ExportTab(app)
                    Tab.SETTINGS -> SettingsTab(app, serviceEnabled, onOpenAccessibilitySettings)
                    Tab.DICTIONARY -> DictionaryTab(dictionaryStore, historyLog, dictMiner)
                    Tab.PROMPTS -> PromptsTab(promptStore)
                    Tab.TRANSCRIPTS -> TranscriptsTab(transcriptionLog, liveDraft, eventLog)
                    Tab.LEARNING -> LearningTab(app)
                    Tab.LOGS -> LogsTab(app)
                    Tab.STATS -> StatsTab(stats, historyLog)
                    // «Ещё» без выбранной вкладки уже обработано выше.
                    Tab.MORE -> Unit
                }
            }
        }
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

@Composable
internal fun ScreenTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall)
}

/** Small uppercase label in the accent color above a card. */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text.uppercase(Locale.forLanguageTag("ru")),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

@Composable
internal fun SectionCard(
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
internal fun HintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

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
                            refreshTick++
                            if (!ok) Feedback.toast(context, context.getString(R.string.rec_failed, msg))
                        }
                    },
                ) { Text(stringResource(R.string.rec_transcribe)) }
                TextButton(onClick = {
                    recordings.delete(item.id)
                    refreshTick++
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
    // The SERVICE adds pending suggestions on its own schedule (auto batches);
    // without this the open tab never noticed them until a manual "Обновить".
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5000)
            loadTick++
        }
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
                "по периоду, выбранному ниже, «Обучить» в меню кнопки — сразу. " +
                "Словарные находки (имена, термины) добавляются в словарь сами, " +
                "с пометкой «авто-обучение». Правила — только с твоего одобрения."
        )

        SectionCard(label = "Автообучение — что происходит сейчас") {
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
                "Наблюдается текстов: ${watch.size}, из них ты правил: $edited." +
                    (watch.filter { it.editedTs > 0 }.minOfOrNull { it.editedTs }
                        ?.let { "\nБлижайшая правка созреет: " + fmt.format(java.util.Date(it + ru.zf.pravka.data.EditWatchStore.RIPE_QUIET_MS)) + "." } ?: "") +
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
            val autoCapture by app.settings.learnAutoFlow.collectAsState(initial = false)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = autoCapture,
                    onCheckedChange = { app.appScope.launch { app.settings.setLearnAuto(it) } },
                )
                Text(
                    "Ловить правки автоматически",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            HintText(
                if (autoCapture)
                    "Служба следит за текстовыми полями во всех приложениях, чтобы заметить " +
                        "твои правки. Это события на каждое нажатие клавиши — держи выключенным, " +
                        "если правила уже собраны."
                else
                    "Выключено: служба не смотрит за полями вообще (событий текста ей больше " +
                        "не присылают). Кнопка «Обучить» в меню «П» и «Разобрать сейчас» работают."
            )
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
                HintText(
                    "Пока пусто. Правила появятся здесь после разбора твоих правок; " +
                        "словарные находки уходят в словарь сами."
                )
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
                HintText(
                    "Набор оптимизируется сам раз в неделю (Опус сливает дубли и " +
                        "противоречия); кнопка ниже — то же вручную, с предпросмотром."
                )
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
                                    // The weekly auto-optimizer counts from here too,
                                    // so it doesn't redo the work right after.
                                    ctx.getSharedPreferences(
                                        PravkaAccessibilityService.PREFS_INTERNAL,
                                        android.content.Context.MODE_PRIVATE,
                                    ).edit().putLong(
                                        PravkaAccessibilityService.KEY_LAST_RULES_OPT,
                                        System.currentTimeMillis(),
                                    ).apply()
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
                rules.forEachIndexed { i, rule ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${i + 1}. ${rule.text}", style = MaterialTheme.typography.bodyMedium)
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
            var last by remember { mutableStateOf<org.json.JSONObject?>(null) }
            LaunchedEffect(evalTick) {
                items = app.evalStore.all()
                // File read off the composition pass.
                last = withContext(Dispatchers.IO) { app.evalStore.lastRun() }
            }
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
            last?.let { run ->
                Text(
                    "Последний прогон: средний ${"%.1f".format(run.optDouble("avg") * 100)}%, " +
                        "точных ${run.optInt("exact")} из ${run.optInt("total")}",
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
    PromptStore.PromptId.MEETING to R.string.prompt_title_meeting,
    PromptStore.PromptId.TASKS to R.string.prompt_title_tasks,
    PromptStore.PromptId.FOOD to R.string.prompt_title_food,
    PromptStore.PromptId.COACH to R.string.prompt_title_coach,
    PromptStore.PromptId.TRAINER to R.string.prompt_title_trainer,
    PromptStore.PromptId.BODY to R.string.prompt_title_body,
    PromptStore.PromptId.RULES to R.string.prompt_title_rules,
    PromptStore.PromptId.PATTERNS to R.string.prompt_title_patterns,
    PromptStore.PromptId.CHAT_HANDOFF to R.string.prompt_title_handoff,
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

        // Business-season workflow: Whisper transcribes meetings on the
        // owner's computer; this assembles the MEETING prompt + the FULL
        // current dictionary + the approved rules into one clipboard-ready
        // request for a Claude chat. Nothing is sent from the app.
        SectionCard(label = "Для встреч") {
            val ctx = LocalContext.current
            val app = ctx.applicationContext as PravkaApp
            HintText(
                "Собирает полный запрос для чистки расшифровки встречи — промпт " +
                    "«Встреча» + весь текущий словарь + принятые правила — и кладёт " +
                    "в буфер. Вставь его в чат с Клодом и добавь расшифровку."
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
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
            }) { Text("Скопировать промпт для встречи") }
        }

        for (id in PromptStore.PromptId.entries) {
            val override by promptStore.overrideFlow(id).collectAsState(initial = null)
            val effective = override ?: promptStore.factory(id)
            Card(onClick = { onOpen(id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            // getValue кидал NoSuchElement на промпте, для
                            // которого забыли заголовок, — и весь экран падал.
                            // Новый промпт не должен ронять Промпты: покажем
                            // его ключом, это уродливо и видно, что чинить.
                            promptTitles[id]?.let { stringResource(it) } ?: id.storageKey,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.prompt_back)) }
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
