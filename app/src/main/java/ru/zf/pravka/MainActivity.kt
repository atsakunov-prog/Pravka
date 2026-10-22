package ru.zf.pravka

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
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
import kotlinx.coroutines.flow.first
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
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.ModeDecor
import ru.zf.pravka.ui.ModeFrame
import ru.zf.pravka.ui.PravkaTheme
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
import ru.zf.pravka.ui.fadingScroll
import ru.zf.pravka.ui.scrollFade

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
        /**
         * Приложение на экране прямо сейчас. Служба, которая собралась
         * перезапустить сама себя из-за невидимых окон (`OverlayWatch`),
         * ждёт, пока владелец из него выйдет: перезапуск процесса захлопнул бы
         * открытую вкладку у него в руках.
         */
        @Volatile var shown = false

        // The Засечка button's long press and its notifications land straight
        // on the timesheet tab.
        const val EXTRA_TAB = "tab"
        /** «Сегодня» — с виджета Засечки (11.10.2026): из любой вкладки на главный экран. */
        const val TAB_TODAY = "today"
        const val TAB_PRAVKA = "pravka"
        const val TAB_ZASECHKA = "zasechka"
        const val TAB_TODOIST = "todoist"
        const val TAB_SPORT = "sport"
        const val TAB_FOOD = "food"
        const val TAB_MONEY = "money"
        const val TAB_SETTINGS = "settings"
        const val TAB_PROMPTS = "prompts"
        /** Баги и предложения — записка с кнопки открывает список (07.10.2026). */
        const val TAB_FEEDBACK = "feedback"

        /**
         * Группа настроек, в которую приземлить «Настройки» из меню плавающей
         * кнопки (24.09.2026): долгое нажатие на «З» → «Настройки» открывает
         * Засечку, а не меню всех групп. Значение — имя [SettingsGroup].
         */
        const val EXTRA_SETTINGS_GROUP = "settings_group"

        // Кнопка еды: «сфоткай тарелку» / «штрихкод» с длинного нажатия
        // открывают Тело (Е) и сразу запускают камеру или сканер. С виджета
        // Засечки (11.10.2026) ещё «gallery» — снимок из галереи — и «text» —
        // строка «что съел?» сразу с клавиатурой.
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

    private fun tabOf(intent: android.content.Intent?): Tab? =
        when (intent?.getStringExtra(EXTRA_TAB)) {
            TAB_SETTINGS -> Tab.SETTINGS
            TAB_PROMPTS -> Tab.PROMPTS
            TAB_FEEDBACK -> Tab.FEEDBACK
            TAB_TODAY -> Tab.TODAY
            TAB_PRAVKA -> Tab.PRAVKA
            TAB_ZASECHKA -> Tab.ZASECHKA
            TAB_TODOIST -> Tab.TODOIST
            TAB_SPORT -> Tab.SPORT
            TAB_FOOD -> Tab.FOOD
            TAB_MONEY -> Tab.MONEY
            else -> null
        }

    /**
     * Просьба «еда снимком / из галереи / строкой» (кнопка «Е», пилюля, старые ярлыки) — быстрому окну еды, вкладки
     * «Еда» больше нет (22.10.2026). true — просьба ушла туда.
     */
    private fun foodToQuick(intent: android.content.Intent?): Boolean {
        val what = when (intent?.getStringExtra(EXTRA_FOOD_ACTION)) {
            "photo" -> ru.zf.pravka.trigger.FoodQuickActivity.W_PHOTO
            "gallery" -> ru.zf.pravka.trigger.FoodQuickActivity.W_GALLERY
            "text" -> ru.zf.pravka.trigger.FoodQuickActivity.W_TEXT
            else -> return false
        }
        intent.removeExtra(EXTRA_FOOD_ACTION)
        startActivity(
            android.content.Intent(this, ru.zf.pravka.trigger.FoodQuickActivity::class.java)
                .putExtra(ru.zf.pravka.trigger.FoodQuickActivity.EXTRA_WHAT, what)
        )
        return true
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (foodToQuick(intent)) return
        tabOf(intent)?.let { tabRequest.value = it }
        groupOf(intent)?.let { groupRequest.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Еда снимком без приложения на экране: окно Правки под камерой не нужно.
        if (savedInstanceState == null && foodToQuick(intent)) {
            finish()
            return
        }
        val app = application as PravkaApp
        // Своих экранов нет (22.10.2026): просьба снаружи — страница сервера или «Телефон» (MainScreen).
        tabOf(intent)?.let { tabRequest.value = it }
        groupOf(intent)?.let { groupRequest.value = it }
        // Почерк значков — до первого кадра (версия 3): иначе на холодном
        // старте первый кадр рисовался бы заводским почерком и значки
        // перескакивали бы на глазах. Хранилище к этому моменту уже прогрето
        // подписками приложения — чтение занимает миллисекунды.
        runCatching {
            ru.zf.pravka.ui.Glyphs.gemini = kotlinx.coroutines.runBlocking { app.settings.iconsGeminiFlow.first() }
        }
        // Вид и тема (Правка 5.0) — тоже до первого кадра: иначе холодный старт
        // мигнул бы чужой темой. Дальше их держит `PravkaApp` подпиской.
        runCatching {
            kotlinx.coroutines.runBlocking {
                ru.zf.pravka.ui.Look.theme = ru.zf.pravka.ui.Look.Theme.of(app.settings.lookThemeFlow.first())
                ru.zf.pravka.ui.Look.v4 = app.settings.lookV4Flow.first()
            }
        }
        setContent {
            PravkaTheme {
                // Окно под экраном и значки строки состояния — по теме: на светлой
                // v2 белые значки на тёплом фоне не видны (тема окна — всегда ночь).
                val windowBg = MaterialTheme.colorScheme.background
                val lightBars = ru.zf.pravka.ui.Look.v2 && !ru.zf.pravka.ui.Look.dark
                androidx.compose.runtime.SideEffect {
                    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.argb(
                        (windowBg.alpha * 255).toInt(), (windowBg.red * 255).toInt(), (windowBg.green * 255).toInt(), (windowBg.blue * 255).toInt(),
                    )))
                    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }
                // Вид плашек — из настроек, один раз на всё приложение
                // (`ui/CardLook.kt`): `PaperCard` зовут из десятка мест, и
                // протаскивать четыре тумблера через каждый вызов значило бы
                // править их все ради одного.
                ru.zf.pravka.ui.ProvideCardLook(app.settings) {
                // Секунды до ответа Claude — одни часы на всё приложение
                // (версия 3): строка «Причёсываю · ещё 6 с» и кружок строки ввода.
                val claudeSeconds = ru.zf.pravka.ui.rememberClaudeSeconds(app.liveWork)
                val claudeProgress = ru.zf.pravka.ui.rememberClaudeProgress(app.liveWork)
                androidx.compose.runtime.CompositionLocalProvider(
                    ru.zf.pravka.ui.LocalClaudeSeconds provides claudeSeconds,
                    ru.zf.pravka.ui.LocalClaudeProgress provides claudeProgress,
                ) {
                MainScreen(
                    app = app,
                    tabRequest = tabRequest.value,
                    onTabRequestHandled = { tabRequest.value = null },
                    groupRequest = groupRequest.value,
                    onGroupRequestHandled = { groupRequest.value = null },
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
    }

    override fun onResume() {
        super.onResume()
        shown = true
        serviceEnabled.value = ru.zf.pravka.trigger.PravkaAccessibilityService.instance != null
        notifEnabled.value = notificationsOn()
        // Доступ к файлам выдаётся на системном экране — вернулись оттуда,
        // экран «База данных» должен увидеть его сразу.
        // Доступ вернулся, а процесс начинал без него — перезапуск: сторы
        // прочли папку пустой и записали бы эту пустоту поверх базы.
        if (ru.zf.pravka.data.DataRoot.refreshAccess(this)) ru.zf.pravka.data.DataRoot.restart(this)
        maybeAskNotifications()
        // Открыл Правку — забрать с сервера команды веба (Засечка с компа, правки дел) сразу, а не ждать тика вкладки
        // Дел (записка отладки 25, 18.10.2026: «открыл телефон после правок в Засечке — ничего не меняется»).
        // Не чаще раза в минуту: возвраты из других приложений не должны дёргать сеть каждый раз.
        (application as? PravkaApp)?.let { app ->
            if (System.currentTimeMillis() - app.delaSync.status.value.lastOk > 60_000) {
                app.appScope.launch { runCatching { app.delaSync.sync("открыл Правку") } }
            }
        }
    }

    override fun onPause() {
        shown = false
        super.onPause()
    }
}

internal enum class Tab(val titleRes: Int) {
    /** «Сегодня» — главный экран Правки 4.0 (07.10.2026). */
    TODAY(R.string.tab_today),
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
    /** Витрина набора деталей Правки 4.0 — только в debug-сборке, «Ещё → Служебное». */
    SHOWCASE(R.string.tab_showcase),
    /** Баги и предложения с кнопок (07.10.2026, `docs/feedback.md`). */
    FEEDBACK(R.string.tab_feedback),
    /** «Где мы»: семья на карте аватарами (10.10.2026, docs/gde.md). */
    WHERE(R.string.tab_where),
}

/**
 * Вкладка прежней Правки → страница сервера, куда теперь ведёт просьба «открыть …» с кнопки, уведомления или
 * виджета (22.10.2026). Дел здесь нет: их страница и есть дом.
 */
private val TAB_PAGE = mapOf(
    Tab.TODAY to "today", Tab.ZASECHKA to "zasechka", Tab.PRAVKA to "pravka",
    Tab.FOOD to "eda", Tab.MONEY to "dengi", Tab.SPORT to "sport",
)

/**
 * Экран поверх дома: «Телефон» (группы настроек строками) или одна группа.
 */
private sealed class Page {
    data object Phone : Page()
    data class Group(val group: SettingsGroup) : Page()
}

/**
 * Приложение целиком (22.10.2026, Правка на диете): дом — страница Дел сервера, поверх него — страница сервиса из меню
 * Ковчега или «Телефон» с настройками. Своих экранов режимов нет: живая часть Правки — кнопки на стекле.
 */
@Composable
private fun MainScreen(
    app: PravkaApp,
    tabRequest: Tab? = null,
    onTabRequestHandled: () -> Unit = {},
    groupRequest: SettingsGroup? = null,
    onGroupRequestHandled: () -> Unit = {},
    serviceEnabled: Boolean,
    notifEnabled: Boolean,
    onFixNotifications: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    var pages by remember { mutableStateOf<List<Page>>(emptyList()) }
    val page = pages.lastOrNull()
    val pop = { pages = pages.dropLast(1) }
    // Страница сервиса внутри приложения (14.10.2026, `ServiceWebScreen.kt`): сохраняется в состоянии — складывание
    // Fold пересоздаёт активити, и без этого страница закрылась бы на полуслове.
    var web by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = ServicePageSaver) {
        mutableStateOf<ru.zf.pravka.core.ServicePage?>(null)
    }
    // «Сервисы — в браузере» (настройки вида): запасной путь, если внутри что-то не работает.
    val servicesInBrowser = app.settings.servicesInBrowserFlow.collectAsState(initial = false)
    val webContext = LocalContext.current
    val openServicePage: (ru.zf.pravka.core.ServicePage) -> Unit = { s ->
        openService(webContext, s, servicesInBrowser.value) { web = it; pages = emptyList() }
    }

    LaunchedEffect(Unit) { runCatching { app.updates.tick() } }
    // Просьба снаружи (меню кнопки, уведомление, виджет): «Настройки» — «Телефон», режим — его страница сервера
    // («Открыть Засечку» — веб Засечки), страницы нет — дом.
    LaunchedEffect(tabRequest) {
        val want = tabRequest ?: return@LaunchedEffect
        pages = if (want == Tab.SETTINGS || want == Tab.MORE) listOf(Page.Phone) else emptyList()
        web = TAB_PAGE[want]?.let { key ->
            ru.zf.pravka.core.Kovcheg.pageFor(key, app.delaSync.services.value, app.delaSync.link.value?.url)
        }
        onTabRequestHandled()
    }
    LaunchedEffect(groupRequest) {
        val g = groupRequest ?: return@LaunchedEffect
        pages = listOf(Page.Phone, Page.Group(g))
        web = null
        onGroupRequestHandled()
    }
    BackHandler(enabled = pages.isNotEmpty()) { pop() }

    // Меню Ковчега — одно на приложение, поверх любого экрана.
    var menuOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = menuOpen) { menuOpen = false }
    // Значок Ковчега — картинкой с сервера (в публичном репозитории его нет); пока не пришла — запасной.
    val brandBytes by app.delaSync.brandIcon.collectAsState()
    val brand = remember(brandBytes) {
        brandBytes?.let { b -> runCatching { android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size) }.getOrNull() }
            ?.asImageBitmap()
    }
    val host = ru.zf.pravka.ui.KovchegHost(toggle = { menuOpen = !menuOpen }, isOpen = menuOpen, news = false, icon = brand)

    // Клавиатура — один раз и здесь (отладка 16.10.2026 вечер, запись 26): окно с `adjustResize` не сдвигается, а
    // экраны кончаются над клавиатурой. imePadding у корня ПОГЛОЩАЕТ отступ клавиатуры для всего дерева ниже.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        androidx.compose.runtime.CompositionLocalProvider(ru.zf.pravka.ui.LocalKovcheg provides host) {
            // Переходы — растворением, 250 мс (DESIGN §10): экран не едет, а сменяется.
            androidx.compose.animation.AnimatedContent(
                targetState = web to page,
                transitionSpec = {
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(250)) togetherWith
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200))
                },
                contentKey = { (w, p) -> w?.let { "web:" + it.url } ?: p?.toString() ?: "home" },
                label = "screen",
            ) { (w, p) ->
                when {
                    w != null -> ServiceWebScreen(app, w, onClose = { web = null })
                    p != null -> PageScreen(
                        app, p, pop,
                        openGroup = { g -> pages = pages + Page.Group(g) },
                        serviceEnabled = serviceEnabled,
                        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    )
                    // Дом: страница Дел сервера (/t); «назад» с неё — свернуть приложение.
                    else -> PravkaHome(
                        app,
                        onPhone = { web = null; pages = listOf(Page.Phone) },
                        onLeave = { (webContext as? android.app.Activity)?.moveTaskToBack(true) },
                    )
                }
            }
        }
        // Пуши выключены — говорим сверху, а не в глубине настроек.
        if (!notifEnabled) {
            Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 92.dp)) {
                NotificationsBanner(onFixNotifications)
            }
        }
        if (menuOpen && ru.zf.pravka.ui.Look.v2) {
            KovchegMenuHost(
                app = app,
                current = web?.url,
                onPhone = { menuOpen = false; web = null; pages = listOf(Page.Phone) },
                onWeb = { s -> menuOpen = false; openServicePage(s) },
                onDismiss = { menuOpen = false },
            )
        }
    }
}

/**
 * Дом приложения (22.10.2026, Правка на диете): страница Дел сервера `/t` с шапкой Ковчега — из её меню открываются
 * остальные страницы и «Телефон». Сервер не подключён — одна строка и дорога в «Телефон», где вставляется QR.
 * «Назад» с главной страницы — свернуть приложение, а не закрыть: WebView остаётся тёплым.
 */
@Composable
private fun PravkaHome(app: PravkaApp, onPhone: () -> Unit, onLeave: () -> Unit) {
    val link by app.delaSync.link.collectAsState()
    val base = link?.url
    if (base != null) {
        DelaWebTab(app, base = base, openTaskId = null, onOpenHandled = {}, onClose = onLeave)
        return
    }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Правка показывает страницы домашнего сервера. Сервер Дел не подключён — подключи его в «Телефоне», " +
                "кнопки на экране работают и без него.",
            style = MaterialTheme.typography.bodyLarge,
        )
        androidx.compose.material3.TextButton(onClick = onPhone, modifier = Modifier.padding(top = 12.dp)) {
            Text("Открыть «Телефон»")
        }
    }
}

/** «Телефон» (группы настроек строками) или одна группа настроек. */
@Composable
private fun PageScreen(
    app: PravkaApp,
    p: Page,
    pop: () -> Unit,
    openGroup: (SettingsGroup) -> Unit,
    serviceEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
) {
    val decor = when (p) {
        is Page.Group -> p.group.decor
        Page.Phone -> ModeDecor.PRAVKA
    }
    ModeFrame(decor) {
        Column(Modifier.fillMaxSize()) {
            when (p) {
                is Page.Group -> {
                    TabHeader(title = p.group.title, glyph = p.group.glyph, onBack = pop)
                    WideCap {
                        ModeSettingsScreen(
                            app,
                            p.group,
                            serviceEnabled,
                            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                            onOpen = openGroup,
                        )
                    }
                }
                // «Телефон» — пункт меню Ковчега; назад — системным жестом или «‹».
                Page.Phone -> {
                    TabHeader(title = "Телефон", subtitle = phoneSubtitle(), onBack = pop)
                    PhoneSettingsScreen(
                        app,
                        serviceEnabled,
                        onOpenAccessibilitySettings,
                        onOpenGroup = openGroup,
                        links = emptyList(),
                    )
                }
            }
        }
    }
}

/**
 * Меню значка Ковчега (Правка 5.0, этап 7): разметка — запись Свода `ui.menu`, а если её нет или она кривая —
 * заводское `Kovcheg.FACTORY`. С 22.10.2026 все пункты — страницы сервера внутри приложения ([onWeb],
 * `ServiceWebScreen.kt`; настройкой — Custom Tab браузера), Claude — его приложение (нет — claude.ai).
 */
@Composable
private fun KovchegMenuHost(
    app: PravkaApp,
    current: String?,
    onPhone: () -> Unit,
    onWeb: (ru.zf.pravka.core.ServicePage) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val nav by app.delaSync.services.collectAsState()
    val link by app.delaSync.link.collectAsState()
    val svod by app.svodStore.view.collectAsState()
    val model = remember(nav, link, current, svod) {
        val K = ru.zf.pravka.core.Kovcheg
        val menu = K.menuFrom(ru.zf.pravka.core.Svod.value(K.MENU_KEY)) ?: K.FACTORY
        val built = K.build(menu, emptyList(), nav, link?.url, webScreens = true)
        ru.zf.pravka.ui.KovchegMenuModel(
            big = built.big,
            directions = built.directions,
            create = built.create,
            current = current,
            news = false,
        )
    }
    ru.zf.pravka.ui.KovchegMenu(
        model = model,
        onPick = { target ->
            when (target) {
                is ru.zf.pravka.core.Kovcheg.Target.Screen -> onDismiss()
                is ru.zf.pravka.core.Kovcheg.Target.Web -> {
                    // Страница сервиса — внутри приложения с шапкой Ковчега; название и значок — из пункта.
                    val item = (model.big + (model.directions + listOfNotNull(model.create)).flatMap { it.items })
                        .firstOrNull { it.target == target }
                    onDismiss()
                    onWeb(ru.zf.pravka.core.ServicePage(item?.name ?: target.url, item?.icon ?: "globe", target.url))
                }
                ru.zf.pravka.core.Kovcheg.Target.Claude -> { onDismiss(); openClaude(context) }
            }
        },
        onPhone = onPhone,
        onTheme = {
            // Переключатель меню — между светлой и тёмной; «как в системе» — в настройках вида.
            val next = if (ru.zf.pravka.ui.Look.dark) ru.zf.pravka.ui.Look.Theme.LIGHT else ru.zf.pravka.ui.Look.Theme.DARK
            ru.zf.pravka.ui.Look.theme = next
            app.appScope.launch { app.settings.setLookTheme(next.key) }
        },
        onDismiss = onDismiss,
    )
}

/**
 * Служебная страница одной колонкой (группа настроек, словарь, логи…): на
 * развороте — не шире 640 dp по центру, а не растянутой во весь экран
 * (DESIGN §13). На сложенном — как есть.
 */
@Composable
private fun WideCap(content: @Composable () -> Unit) {
    if (!ru.zf.pravka.ui.twoPane()) {
        content()
        return
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxHeight().widthIn(max = 640.dp)) { content() }
    }
}

/**
 * Страница сервиса — Custom Tab браузера, если он их умеет, иначе обычный
 * браузер. Протокол Custom Tabs — это просто пара extras у `ACTION_VIEW`
 * (`androidx.browser` делает ровно это), поэтому без лишней библиотеки.
 */
internal fun openWeb(context: android.content.Context, url: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
        putExtras(android.os.Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) })
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { e -> Feedback.toast(context, "Не открылось: ${e.javaClass.simpleName}: ${e.message}") }
}

/** Приложение Claude по пакету; не установлено — claude.ai в браузере. */
internal fun openClaude(context: android.content.Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(ru.zf.pravka.core.Kovcheg.CLAUDE_PACKAGE)
    if (launch != null && runCatching { context.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    openWeb(context, ru.zf.pravka.core.Kovcheg.CLAUDE_WEB)
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
