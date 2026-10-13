package ru.zf.pravka

import ru.zf.pravka.ui.glass

import ru.zf.pravka.ui.bottomFade

import androidx.compose.foundation.layout.imePadding

import androidx.compose.foundation.layout.navigationBarsPadding

import androidx.compose.foundation.layout.PaddingValues

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import ru.zf.pravka.core.Dela
import ru.zf.pravka.core.DelaRemind
import ru.zf.pravka.core.DelaAsk
import ru.zf.pravka.core.DelaViews
import ru.zf.pravka.core.DelaGroups
import ru.zf.pravka.core.DelaKrug
import ru.zf.pravka.trigger.PravkaAccessibilityService
import ru.zf.pravka.trigger.finishDelaReason
import ru.zf.pravka.trigger.listenForDelaReason
import ru.zf.pravka.trigger.onRaznoskaTap
import ru.zf.pravka.trigger.onRaznoskaText
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.Kv
import ru.zf.pravka.ui.KvGlyphs
import ru.zf.pravka.ui.KvShape
import ru.zf.pravka.ui.Look
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperIconButton
import ru.zf.pravka.ui.PaperRow
import ru.zf.pravka.ui.PaperSheet
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.PaperToggle
import ru.zf.pravka.ui.RowRule
import ru.zf.pravka.ui.ScreenPad
import ru.zf.pravka.ui.Segments
import ru.zf.pravka.ui.SummaryLine
import ru.zf.pravka.ui.ThinkingLine
import ru.zf.pravka.ui.VoiceInput
import ru.zf.pravka.ui.scrollFade

// Вкладка «Дела» на домашнем сервере (03.10.2026, docs/dela-server.md,
// «Задание телефону», пункт 3). Центр системы — ежедневный и еженедельный
// разбор («Утро», «Неделя»), а не ввод: ввода у владельца и так в избытке
// (321 задача из сентябрьских встреч лежала без дела). Поэтому вкладка
// открывается на «Утре», а ввод — та же пилюля «говори дела», что у «Д».
//
// Всё считается из копии на телефоне (`DelaStore.view`) — вкладка открывается
// мгновенно и работает без сети; правка ложится в очередь и видна сразу.
// Карточка дела правит всё и закрывает (Todoist-вкладка только читала).
// «Новое» — одним движением: принять, поправить, отклонить с причиной.
//
// С 05.10.2026 (docs/dela-phone-2.md) — как веб: «Новое» уточняет открытые
// дела и показывает «закрыто само», денег у дел в интерфейсе нет, правка
// словами — через сервер (микрофон в карточке и строка «Claude» над видом),
// и CRM — воронка, клиенты, связи, сделка (`DelaCrmUi.kt`).

// Разделы — как в вебе (05.10.2026, владелец: «адаптировать приложение так,
// чтобы оно показывало те же категории, людей, группы и т.п., что и в вебе»):
// ряд разделов веба с числами, ☰ — его боковая панель (сфера, разделы, CRM,
// избранное, проекты по видам, люди, архив), списки — его группировками
// (`core/DelaViews.kt`). Ключи разделов — ключи веба (`#/now`, `#/crm`).
//
// С 06.10.2026 (docs/dela-phone-3.md) «Утра» и «Входящих» нет: первым —
// «Сейчас» (до пяти дел на сегодня, молния у строки), «Новое» — по тому,
// откуда пришло: наговорки с их делами, предложения встреч и Telegram,
// поставленное другими, дела без проекта, закрытое само с «Понятно».
private const val NAV_NEW = "new"

/** `openNav` «new/<batch_ref>» — «Новое» одной пачкой (как `#/new/<batch_ref>` веба). */
internal const val NEW_BATCH = "new/"
/** Группа «Без проекта» по проектам (ключ `DelaViews.group`): у неё — «Все в проект…». */
private const val LOOSE_GROUP = "0"
/** Раздел-фильтр: ключ «f:<id>» (`#/f/<id>` веба). */
private const val FILTER_NAV = "f:"
/** Ветка «Просрочено» сверху «Всех дел»: у неё — «Перенести все». */
private const val LATE_NODE = "late"
/** Боковина разложенного: открыта ли — помнит устройство (`dela-favs.json`, рядом с видом дерева): «0» — спрятана. */
private const val SIDE_KEY = "ui:side"
/** Ширина боковины колонкой (холст: 172) и шторкой на сложенном (холст: ~252). */
private val SIDE_W = 172.dp
private val DRAWER_W = 252.dp

/** Список «только одно» экрана [key]: ветки первого уровня [lv] с числом дел. */
private data class BranchPick(val key: String, val lv: DelaGroups.Level, val list: List<DelaGroups.Branch>)

/** Выбор уровня [i] на экране [key] (`levelsBar` веба): уровни сейчас и чего не предлагать. */
private data class LevelPick(val key: String, val i: Int, val levels: List<DelaGroups.Level>, val omit: Set<DelaGroups.Level>)
private val CRM_NAV = listOf("crm" to "Воронка", "clients" to "Клиенты", "people" to "Люди", "ties" to "Связи")
private val SPHERES = listOf("work" to "Работа", "home" to "Дом", "all" to "Всё")

/** Название открытого раздела, которого нет среди четырёх вкладок вида v2: Круг, дерево, CRM, фильтр. */
private fun navTitle(nav: String, view: DelaViews.View?, filters: List<DelaGroups.Filter>): String =
    CRM_NAV.firstOrNull { it.first == nav }?.second
        ?: filters.firstOrNull { FILTER_NAV + it.id == nav }?.name
        ?: view?.title ?: nav

/**
 * Экран раздела или страницы — как `head()` и тело веба: заголовок,
 * подзаголовок, тревожная строка красным («просрочено: 3»), дела группами и
 * какие переключатели над ними (группировка, «Сделанные», «Предстоящее»).
 */
private data class DelaScreen(
    val title: String,
    val sub: String = "",
    val alert: String = "",
    val groups: List<DelaViews.Group> = emptyList(),
    val by: DelaViews.By? = null,
    /** Ключ выбора группировки (`S.groups` веба): «inbox», «all», «project»; null — выбора нет. */
    val groupKey: String? = null,
    val groupOptions: List<DelaViews.By> = DelaViews.By.entries,
    val done: Boolean = false,
    val add: Boolean = false,
    /** «Неделя»: проекты в работе без моего следующего шага. */
    val projects: List<Dela.Project> = emptyList(),
    val empty: String = "",
    /** Подсказка над группами; показывается и при непустых группах. */
    val hint: String = "",
    /**
     * Дела уровнями (10.10.2026, `nested` веба): первый уровень — карточкой,
     * второй и третий — подзаголовками внутри. null — экран группами [groups].
     */
    val nodes: List<DelaGroups.Node>? = null,
    /** Выбранные уровни и куда их помнить (`S.levels` веба): «today», «all», «project», «f:<id>». */
    val levels: List<DelaGroups.Level> = emptyList(),
    val levelKey: String? = null,
    /** Каких уровней не предлагать: на странице проекта — проектов. */
    val levelOmit: Set<DelaGroups.Level> = emptySet(),
    /** «Сохранить фильтр» — что показано (`addFilter` веба); null — кнопки нет. */
    val saveScope: DelaGroups.Scope? = null,
    /** «Только одно» по первому уровню (`pickBar` веба): выбор и ветки с числом дел — до выбора. */
    val pick: DelaGroups.Pick? = null,
    val branches: List<DelaGroups.Branch> = emptyList(),
    /** Круг жизни: принцип дня — строкой под шапкой «Сегодня» и «Круга жизни». */
    val principle: String = "",
    /** Круг жизни: отступ строки — шаг к цели под ней (дерево «Зачем»), по id дела. */
    val indent: Map<String, Int> = emptyMap(),
    /** Дела экрана, нарисованные не группами (Круг и дерево жизни), — их тоже видит Claude. */
    val taskIds: List<String> = emptyList(),
    /** Главная «Сейчас» (13.10.2026): рисуется своими блоками — «Сейчас», «Новое», «Сегодня». */
    val home: Home? = null,
    /** Просрочено N — ссылкой во «Все дела» под разделами (`lateLink` веба); 0 — строки нет. */
    val late: Int = 0,
) {
    /** Дела экрана в порядке показа — то, что видит Claude. */
    val ids: List<String> get() = (nodes?.let { n -> DelaGroups.flat(n).map { it.id } } ?: groups.flatMap { g -> g.items.map { it.id } }) + taskIds
}

/**
 * Страница поверх видов: человек, проект (клиент) или сделка целиком.
 * Страницы — стопкой: воронка → сделка → клиент → человек, «назад» — на шаг.
 */
internal sealed class DelaPage {
    data class Person(val id: String) : DelaPage()
    data class Project(val id: String) : DelaPage()
    data class Deal(val id: String) : DelaPage()
}

/**
 * «Новое» (08.10.2026, `renderNew` веба): «Подскажи» — ждущие предложения,
 * «Поставил» — мои открытые за три дня, «Сделано само» — свёрнуто.
 */
private class NewParts(
    val asks: List<Dela.Suggestion>,
    val feed: List<Dela.Task>,
    val autoDone: List<Dela.Suggestion>,
) {
    /** Дела экрана в порядке показа — то, что видит Claude (и сделанное само: «верни акт» он поймёт). */
    val ids: List<String>
        get() = feed.take(DelaViews.FEED_MAX).map { it.id } + autoDone.mapNotNull { sg -> sg.taskId.takeIf { it.isNotBlank() } }

    val empty: Boolean get() = asks.isEmpty() && feed.isEmpty() && autoDone.isEmpty()

    companion object {
        /** [batch] не пустой — одна пачка (дайджест на «Сегодня» → «Открыть «Новое»», `#/new/<batch_ref>` веба). */
        fun of(snap: Dela.Snapshot, me: String, sphere: String, now: Long, batch: String = ""): NewParts =
            if (batch.isNotEmpty()) NewParts(
                DelaViews.asksOfBatch(snap, me, batch), DelaViews.feedOfBatch(snap, batch), DelaViews.autoOfBatch(snap, me, batch, now),
            )
            else NewParts(DelaViews.asks(snap, me), DelaViews.feed(snap, me, sphere, now), Dela.autoDone(snap, me, now))
    }
}

@Composable
fun DelaTab(
    app: PravkaApp,
    /** Дело, которое открыть карточкой сразу (тап по делу на «Сегодня»). */
    openTaskId: String? = null,
    onOpenHandled: () -> Unit = {},
    /**
     * Шапка режима (Правка 4.0): её рисует вкладка, потому что второй тон
     * шапки — сфера («Все сферы ⌄», `screens/06`), а сфера живёт здесь.
     */
    header: @Composable (sphere: String, onSphere: () -> Unit) -> Unit = { _, _ -> },
    /**
     * Раздел, который открыть сразу (меню Ковчега: «CRM» — `crm`). «new/<batch_ref>» — «Новое» одной
     * пачкой, как `#/new/<batch_ref>` веба (дайджест на «Сегодня» → «Открыть «Новое»»).
     */
    openNav: String? = null,
    onNavHandled: () -> Unit = {},
) {
    val snap by app.delaStore.view.collectAsState()
    val queued by app.delaStore.queued.collectAsState()
    val notices by app.delaStore.noticesFlow.collectAsState()
    val link by app.delaSync.link.collectAsState()
    val st by app.delaSync.status.collectAsState()
    val ribbon by app.zasechkaStore.entriesFlow.collectAsState()
    val ownerName = app.profileStore.flow.collectAsState().value?.name
    val talking = ru.zf.pravka.ui.rememberRouteBusy(app.liveWork, "raznoska")
    val scope = app.appScope

    val views by app.delaStore.viewsFlow.collectAsState()
    val favs by app.delaStore.favsFlow.collectAsState()
    val filtersRaw by app.delaStore.filtersFlow.collectAsState()
    val filters = remember(filtersRaw) { DelaGroups.filters(filtersRaw) }

    // Раздел — ключ веба: now, today, new, all; CRM — crm, clients, ties. Первым — «Сейчас» (13.10.2026).
    var nav by rememberSaveable { mutableStateOf(DelaViews.View.NOW.key) }
    // ☰ — боковая панель листом: только «Вид 4.0». В v2 разделы — боковиной с язычком (13.10.2026).
    var navOpen by remember { mutableStateOf(false) }
    // Боковина v2 на сложенном — шторка поверх экрана: открыта, пока не выбрали раздел, не тапнули мимо или «назад».
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var sphere by rememberSaveable { mutableStateOf("all") }
    // «Новое» одной пачкой (`#/new/<batch_ref>` веба): пусто — всё «Новое». Любой переход по разделам сбрасывает.
    var newBatch by rememberSaveable { mutableStateOf("") }
    // «Сделанные» у списков, как в вебе.
    var showDone by rememberSaveable { mutableStateOf(false) }
    // «Перенести все» у просроченного — дела, которым выбирают новый срок.
    var rescheduling by remember { mutableStateOf<List<Dela.Task>?>(null) }
    // «без проекта» у строки и «Все в проект…» в «Новом» — дела, которым выбирают проект.
    var projectFor by remember { mutableStateOf<List<Dela.Task>?>(null) }
    // «Сделано само» в «Новом» — свёрнуто, пока не попросили показать.
    var autoOpen by rememberSaveable { mutableStateOf(false) }
    val groupsPref by app.delaStore.groupsFlow.collectAsState()
    // Набранное в строке Claude живёт, пока его не отправили или не стёрли: смена раздела и синк его не стирают.
    var draft by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var pages by remember { mutableStateOf(listOf<DelaPage>()) }
    val page = pages.lastOrNull()
    // Фильтр дел проекта по сделке — свой у каждой страницы: сменилась страница — сброс.
    var dealFilter by remember(page) { mutableStateOf("") }
    var openTask by remember { mutableStateOf<Dela.Task?>(null) }
    LaunchedEffect(openTaskId, snap) {
        val id = openTaskId ?: return@LaunchedEffect
        val t = snap.tasks[id] ?: return@LaunchedEffect
        openTask = t
        onOpenHandled()
    }
    LaunchedEffect(openNav) {
        val want = openNav ?: return@LaunchedEffect
        if (want.startsWith(NEW_BATCH)) {
            nav = NAV_NEW
            newBatch = want.removePrefix(NEW_BATCH)
        } else {
            nav = want
            newBatch = ""
        }
        pages = emptyList()
        onNavHandled()
    }
    var newTask by remember { mutableStateOf(false) }
    var editingSuggestion by remember { mutableStateOf<Dela.Suggestion?>(null) }
    var rejecting by remember { mutableStateOf<Dela.Suggestion?>(null) }
    var starting by remember { mutableStateOf("") }
    // Правка словами: одна команда за раз из строки Claude внизу (задание 7).
    val ask = remember { AskState() }
    var sayListening by remember { mutableStateOf(false) }
    // Над строкой: «Claude ничего не поменял — команда снова в поле» или причина отказа.
    var sayNote by remember { mutableStateOf("") }
    var sayNoteError by remember { mutableStateOf(false) }
    // Что в окне в миг отправки — строки дел и плитки сделок отмечают себя сами.
    val seenRows = remember { SeenRows() }

    LaunchedEffect(link) {
        app.delaStore.load()
        // Открытие вкладки — хороший момент отдать очередь и взять новое.
        if (link != null) runCatching { app.delaSync.tick() }
    }

    val me = link?.user ?: app.delaStore.me
    val today = LocalDate.now().toString()
    val now = System.currentTimeMillis()
    // Круг жизни (12.10.2026): справочник — из Свода (едет синком), включён — когда сервер его знает.
    val krugDirs = DelaKrug.directions()
    val krugOn = DelaKrug.on(snap, krugDirs)
    // Дерево жизни (13.10.2026, docs/dela-phone-14.md): вкладка и поля целей — только серверу, который знает `tree`.
    val treeOn = DelaKrug.treeOn(snap, krugDirs)
    val principle = if (krugOn) DelaKrug.principleOfDay(DelaKrug.principles(), today).orEmpty() else ""
    val running = ribbon.firstOrNull { it.open }
    // CRM — владельцу и тем, кому открыты клиенты; деньги фирмы — владельцу и sees_money.
    val crmOn = Dela.crmOn(snap, me)
    val moneyOn = Dela.moneyOn(snap, me)
    // CRM отняли, а выбран её раздел — назад в «Сейчас», а не пустой экран.
    // Сохранённый ключ «Предстоящего», «Жду», «Недели» (до 10.10.2026) ведёт туда, где их дела теперь.
    LaunchedEffect(crmOn, nav, filters) {
        if (nav.startsWith(FILTER_NAV)) {
            if (filters.none { FILTER_NAV + it.id == nav }) nav = DelaViews.View.NOW.key
        } else if (!crmOn && CRM_NAV.any { it.first == nav }) nav = DelaViews.View.NOW.key
        else if (CRM_NAV.none { it.first == nav } && DelaViews.View.entries.none { it.key == nav }) nav = DelaViews.View.of(nav).key
    }
    // Раздел Дел (не CRM) — по ключу; у CRM своё тело.
    val view: DelaViews.View? = if (CRM_NAV.any { it.first == nav } || nav.startsWith(FILTER_NAV)) null else DelaViews.View.of(nav)
    val queuedOps = remember(queued) { queued.map { it.op } }
    val push: (DelaPage) -> Unit = { p -> if (pages.lastOrNull() != p) pages = pages + p }
    /** Перейти в раздел — как ссылка боковой панели веба: страницы и поиск закрываются. */
    val go: (String) -> Unit = { key ->
        nav = key
        newBatch = ""
        pages = emptyList()
        query = ""
        searching = false
        navOpen = false
        drawerOpen = false
    }
    fun groupOf(list: String, def: DelaViews.By) = DelaViews.By.of(groupsPref[list].orEmpty(), def)
    // Уровни группировки на виде (10.10.2026): помнит само устройство, как браузер у веба.
    fun levelsOf(key: String) = DelaGroups.parseLevels(groupsPref["lv:$key"]) ?: DelaGroups.levelsDef(key)
    // Фильтр на экране: правка до «Сохранить» — черновиком (`S.fdraft` веба).
    var fdraft by remember { mutableStateOf(mapOf<String, DelaGroups.Filter>()) }
    val filterId = nav.takeIf { it.startsWith(FILTER_NAV) }?.removePrefix(FILTER_NAV)
    fun saveFilters(list: List<DelaGroups.Filter>, said: String) {
        scope.launch {
            app.delaStore.saveFilters(DelaGroups.json(list), DelaGroups.saveOp(list))
            app.delaSync.poke()
            Feedback.toast(app, said)
        }
    }
    // «Сохранить фильтр», «Переименовать», «Удалить» — листом.
    var naming by remember { mutableStateOf<DelaGroups.Filter?>(null) }
    var namingNew by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<DelaGroups.Filter?>(null) }
    // Выбор уровня: ключ экрана, номер уровня, уровни сейчас, чего не предлагать.
    var levelPick by remember { mutableStateOf<LevelPick?>(null) }
    // «Только одно» на видах — до перезапуска (`S.pick` веба); в фильтре — вместе с ним.
    var picks by remember { mutableStateOf(mapOf<String, DelaGroups.Pick?>()) }
    var branchPick by remember { mutableStateOf<BranchPick?>(null) }
    // Другая страница — выбор проекта не переезжает на неё (сброс `S.pick.project` веба).
    LaunchedEffect(pages.lastOrNull()) { if (picks["project"] != null) picks = picks - "project" }
    fun setPick(key: String, p: DelaGroups.Pick?) {
        if (key.startsWith(FILTER_NAV)) {
            val id = key.removePrefix(FILTER_NAV)
            filters.firstOrNull { it.id == id }?.let { f0 -> fdraft = fdraft + (id to (fdraft[id] ?: f0).copy(pick = p)) }
        } else picks = picks + (key to p)
    }
    // «Назад» листает страницы Дел (как история веба), пока они есть.
    androidx.activity.compose.BackHandler(enabled = pages.isNotEmpty()) { pages = pages.dropLast(1) }

    /**
     * Команда Claude: одна за раз. Из строки внизу ([cont] = false) — новый
     * разговор; из окна разговора — следующая реплика с `scope.history`. Пока он
     * думает — команда в поле серым. Сделал или ответил — реплика в окне
     * разговора (09.10.2026, п. 12 задания 9); ничего не сделал и не ответил —
     * команда снова в поле внизу (поправка задания 10). Не вышло (нет сети,
     * сервер отказал) — причина целиком, текст на месте.
     */
    fun runAsk(text: String, scopeJson: org.json.JSONObject, cont: Boolean = false) {
        if (ask.running.isNotBlank()) {
            Feedback.toast(app, "Claude ещё правит прошлую команду")
            return
        }
        val talk0 = if (cont) ask.talk ?: Talk(scopeJson) else Talk(scopeJson)
        val sent = if (cont) DelaAsk.withHistory(talk0.scope, talk0.turns, snap) else scopeJson
        ask.running = ASK_SCREEN
        if (!cont) sayNote = ""
        if (cont) ask.talk = talk0.copy(busy = text, text = "", error = "")
        scope.launch {
            val r = app.delaSync.ask(text, sent)
            ask.running = ""
            r.onSuccess { res ->
                val cur = if (cont) ask.talk ?: talk0 else talk0
                if (!cont && !res.did && res.reply.isBlank() && res.errors.isEmpty()) {
                    if (draft.isBlank()) draft = text
                    sayNote = "Claude ничего не поменял — команда снова в поле"
                    sayNoteError = false
                } else {
                    if (!cont && draft.trim() == text.trim()) draft = ""
                    // Карточка дела, про которое сказано, закрывается: Claude её уже поправил.
                    if (scopeJson.has("open")) openTask = null
                    ask.talk = cur.copy(turns = cur.turns + DelaAsk.Turn(text, res), busy = "", error = "")
                }
            }.onFailure { e ->
                val why = e.message.orEmpty().ifBlank { "Claude не ответил" }
                if (cont) ask.talk = (ask.talk ?: talk0).copy(busy = "", text = text, error = why)
                else {
                    if (draft.isBlank()) draft = text
                    sayNote = why
                    sayNoteError = true
                }
            }
        }
    }

    // «Вернуть» после крестика, «откуда он», «+ человек», солнца — полоской наверху вкладки, пока не закрыли.
    var undoOffer by remember { mutableStateOf<UndoOffer?>(null) }
    // На развороте дело открывается справа (`DelaTaskPane`), а не листом.
    val wideNow = ru.zf.pravka.ui.twoPane()
    var paneId by rememberSaveable { mutableStateOf<String?>(null) }
    // Открытое справа дело; не выбрано — первое из дел дня: сначала «Сейчас», потом «Сегодня» («отбить» — выше).
    val paneTask: Dela.Task? = if (!wideNow) null
    else paneId?.let { snap.tasks[it] } ?: DelaGroups.dayOrdered(snap, me, today, sphere).firstOrNull()
    val actions = DelaActions(
        open = { if (wideNow) paneId = it.id else openTask = it },
        start = { t ->
            if (starting.isBlank()) {
                starting = t.id
                scope.launch {
                    val entry = runCatching { app.zasechkaEngine.startTask(t) }.getOrNull()
                    Feedback.toast(app, if (entry != null) "⏱ ${entry.title}" else "Не смог записать дело")
                    starting = ""
                }
            }
        },
        done = { t ->
            scope.launch {
                app.delaDo(listOf(Dela.statusOp(if (t.open) "task.done" else "task.reopen", t.id)))
                Feedback.toast(app, if (t.open) "✓ ${t.title.take(40)}" else "Дело снова открыто")
            }
        },
        person = { id -> if (id.isNotBlank()) push(DelaPage.Person(id)) },
        project = { id -> if (id.isNotBlank()) push(DelaPage.Project(id)) },
        // Солнце (10.10.2026, `toggleToday` веба): не на сегодня — «На сегодня» (срок сегодня);
        // уже на сегодня — «На завтра». Тост с «Вернуть» — как в вебе.
        toggleNow = { t ->
            val tomorrow = LocalDate.parse(today).plusDays(1).toString()
            val (set, said) = DelaGroups.toggleToday(t, me, today, tomorrow)
            val was = DelaGroups.was(t, set)
            undoOffer = UndoOffer(said + ": " + t.title.take(40), listOf(Dela.setOp(t.id, was, set)), emptyList())
            scope.launch { app.delaDo(listOf(Dela.setOp(t.id, set, was))) }
        },
        // Молния (13.10.2026, `toggleNow` веба): взял на сегодня — в «Сейчас», снял — дело остаётся, где было.
        // Шестое не встаёт — тост словами, как у веба и у правки словами на сервере.
        bolt = { t ->
            val (set, said) = DelaGroups.toggleNow(t, snap, me, today)
            if (set == null) Feedback.toast(app, said)
            else {
                val was = DelaGroups.was(t, set)
                undoOffer = UndoOffer(said + ": " + t.title.take(40), listOf(Dela.setOp(t.id, was, set)), emptyList())
                scope.launch { app.delaDo(listOf(Dela.setOp(t.id, set, was))) }
            }
        },
        pickProject = { list -> if (list.isNotEmpty()) projectFor = list },
        starting = starting,
        // Связь с лентой видна у дела (05.10.2026): что идёт сейчас и сколько уже ушло.
        running = if (running != null) running.task else "",
        runningMs = running?.durationMs(now) ?: 0L,
        spent = remember(ribbon, now) { ru.zf.pravka.core.ZasechkaTasks.spent(ribbon, now) },
        stop = { scope.launch { app.zasechkaEngine.closeOpen() } },
        snap = snap,
        me = me,
        selectedId = paneTask?.id.orEmpty(),
        compact = wideNow && Look.v2,
    )

    // Дерево жизни: вид, направление и свёрнутые ветки помнит устройство (как браузер у веба), фильтры
    // «Списка» — до перезапуска (`S.treeF` веба). Долгое нажатие — лист «Куда повесить».
    val treeMode = groupsPref["tree:mode"]?.takeIf { m -> TREE_MODES.any { it.first == m } } ?: "tree"
    val treeDir = groupsPref["tree:dir"].orEmpty().takeIf { k -> krugDirs.any { it.key == k } }.orEmpty()
    var treeGoal by rememberSaveable { mutableStateOf("") }
    var treeLoose by rememberSaveable { mutableStateOf(false) }
    var treeDirPick by remember { mutableStateOf(false) }
    var treeGoalPick by remember { mutableStateOf(false) }
    var hangTask by remember { mutableStateOf<Dela.Task?>(null) }
    var hangProject by remember { mutableStateOf<Dela.Project?>(null) }
    fun folded(key: String): Boolean = groupsPref["fold:$key"]?.let { it == "1" } ?: DelaKrug.foldedByDefault(key)
    /** Перейти в дерево направления [key] (тап по циферблату и карточке «Круга жизни»). */
    fun toTree(key: String) {
        scope.launch { app.delaStore.setGroup("tree:dir", key) }
        go(DelaViews.View.TREE.key)
    }
    /**
     * Повесить (лист «Куда повесить» дерева, `moveTo` веба): правка уходит сразу, без
     * десяти секунд тишины — сервер сам откажет «по кругу» и под ориентиром, и отказ
     * должен прийти тостом здесь же, а не карточкой «сервер ответил» потом.
     */
    fun hangSend(ops: List<org.json.JSONObject>, said: String) {
        if (ops.isEmpty()) {
            if (said.isNotBlank()) Feedback.toast(app, said)
            return
        }
        scope.launch {
            val since = System.currentTimeMillis()
            app.delaStore.enqueue(ops)
            val sent = app.delaSync.pushNow(4_000L)
            val refused = app.delaStore.noticesFlow.value.firstOrNull { it.error && it.at >= since }
            Feedback.toast(app, refused?.text ?: if (sent || link == null) said else "$said — уйдёт на сервер, когда будет связь")
        }
    }

    // Что на экране — как `head()` и тело веба: заголовок, подзаголовок, дела
    // группами. По этой модели рисуется список, и её же видит Claude (дела в
    // порядке показа). «Новое» и CRM рисуются своими кусками — модели у них нет.
    val needle = query.trim()
    val by0 = DelaViews.By.DATE
    // Поиск сразу (06.10.2026, docs/dela-phone-4.md, `renderSearch` веба): на каждую букву, из копии,
    // всё — клиенты и разделы, проекты клиентов, люди, дела.
    val found: DelaViews.Found? = if (page == null && needle.isNotEmpty()) DelaViews.searchAll(snap, needle, showDone) else null
    val screen: DelaScreen? = if (page != null) null else when {
        found != null -> DelaScreen(
            "Поиск: $needle",
            listOfNotNull(
                plural(found.total, "находка", "находки", "находок"),
                found.deals.size.takeIf { it > 0 }?.let { plural(it, "проект", "проекта", "проектов") },
                found.people.size.takeIf { it > 0 }?.let { plural(it, "человек", "человека", "человек") },
                plural(found.tasks.size, "дело", "дела", "дел"),
            ).joinToString(" · "),
            groups = DelaViews.group(found.tasks, DelaViews.By.PROJECT, snap, today), by = DelaViews.By.PROJECT,
            done = true,
            empty = if (found.total == 0) "Ничего не нашлось. Ищу по началам и кусочкам слов: «альф», «фонд», «иван»." else "",
        )
        // «Сейчас» — главная (13.10.2026, `renderNow` веба): своими блоками, у модели — только дела в порядке показа.
        view == DelaViews.View.NOW -> {
            val nowList = DelaGroups.nowOrdered(snap, me, today)
            val taken = nowList.map { it.id }.toSet()
            val rest = DelaGroups.todayByGroups(snap, me, today, sphere, taken)
            val done = DelaGroups.doneToday(snap, me, today)
            DelaScreen(
                "Сейчас",
                listOfNotNull(
                    DelaViews.longDate(today),
                    "в «Сейчас» ${nowList.size} из ${DelaViews.NOW_MAX}",
                    "закрыто $done".takeIf { done > 0 },
                ).joinToString(" · "),
                principle = principle,
                taskIds = nowList.map { it.id } + rest.map { it.id },
                home = Home(
                    now = nowList,
                    today = rest,
                    taken = DelaGroups.todayTasks(snap, me, today, sphere).count { it.id in taken },
                    asks = DelaViews.asks(snap, me),
                    fed = DelaViews.feed(snap, me, sphere, now).size,
                    auto = Dela.autoDone(snap, me, now).size,
                ),
                late = DelaGroups.lateTasks(snap, me, today, sphere).size,
            )
        }
        // «Сегодня» (`renderToday` веба, 13.10.2026): свои дела со сроком ровно сегодня, три группы по приоритету —
        // отбить, запустить, мониторить; в каждой выше тот, кому делать раньше и кто дольше ждёт. Пустая группа
        // не видна. Просроченного здесь нет — оно во «Всех делах».
        view == DelaViews.View.TODAY -> {
            val every = DelaGroups.todayTasks(snap, me, today, sphere)
            // «Моё» (Круг жизни, `renderToday` веба): под списком — что двигает жизнь, но срока на сегодня не имеет,
            // подсказка, что взять в «Сейчас». Что и так на виду («Сегодня», «Сейчас»), в нём не повторяется.
            val mine = if (krugOn) DelaKrug.mine(snap, me, today, sphere) else emptyList()
            val done = DelaGroups.doneToday(snap, me, today)
            val lv = levelsOf("today")
            val pk = picks["today"]?.takeIf { DelaGroups.pickOn(it, lv) }
            val items = DelaGroups.picked(every, lv, pk, snap, today)
            val mineNode = if (mine.isEmpty()) emptyList()
            else listOf(DelaGroups.Node("mine", "Моё", 0, null, mine, sub = "никто не ждёт — двигает жизнь; берёшься сегодня — в «Сейчас»"))
            DelaScreen(
                "Сегодня",
                listOfNotNull(
                    DelaViews.longDate(today),
                    "со сроком сегодня ${every.size}".takeIf { every.isNotEmpty() },
                    "закрыто $done".takeIf { done > 0 },
                ).joinToString(" · "),
                principle = principle,
                nodes = DelaGroups.nested(items, lv, snap, today) + mineNode,
                levels = lv, levelKey = "today", saveScope = DelaGroups.Scope.TODAY,
                pick = pk, branches = lv.firstOrNull()?.let { DelaGroups.branches(every, it, snap, today) }.orEmpty(),
                empty = if (pk != null) "У «${pk.title}» здесь дел нет." else if (done > 0) "Со сроком на сегодня всё закрыто." else "Со сроком на сегодня дел нет.",
                late = DelaGroups.lateTasks(snap, me, today, sphere).size,
            )
        }
        view == DelaViews.View.ALL -> {
            // С 10.10.2026 «Все дела» — уровнями: с завода проекты › группы › время (владелец: «по каждому
            // проекту посмотреть, что я могу сделать сегодня»). С 13.10.2026 «Просрочено» — сверху своей группой:
            // само оно ни в «Сейчас», ни в «Сегодня» не попадает и живёт здесь; в уровнях ниже его уже нет.
            val lv = levelsOf("all")
            val every = DelaViews.list(snap, me, sphere, showDone = showDone)
            val pk = picks["all"]?.takeIf { DelaGroups.pickOn(it, lv) }
            val items = DelaGroups.picked(every, lv, pk, snap, today)
            val late = items.filter { DelaViews.isLate(it, today) }.sortedWith(DelaGroups.byTime(today))
            val lateIds = late.map { it.id }.toSet()
            val lateNode = if (late.isEmpty()) emptyList()
            else listOf(DelaGroups.Node(LATE_NODE, "Просрочено", 0, null, late, sub = "срок прошёл — сделать, перенести или взять в «Сейчас»", late = true))
            DelaScreen(
                "Все дела",
                listOfNotNull(
                    plural(items.count { it.open }, "открытое", "открытых", "открытых"),
                    "просрочено ${late.size}".takeIf { late.isNotEmpty() },
                ).joinToString(" · "),
                nodes = lateNode + DelaGroups.nested(items.filter { it.id !in lateIds }, lv, snap, today),
                levels = lv, levelKey = "all", saveScope = DelaGroups.Scope.ALL,
                pick = pk, branches = lv.firstOrNull()?.let { DelaGroups.branches(every, it, snap, today) }.orEmpty(),
                done = true, add = true, empty = if (pk != null) "У «${pk.title}» здесь дел нет." else "Пусто.",
            )
        }
        // Круг жизни (`renderKrug` веба, 13.10.2026): обзор — циферблат девяти направлений и карточки по зонам
        // с целями верхнего уровня; тап по направлению — его дерево. Рисуется своим куском (`krugOverview`).
        view == DelaViews.View.KRUG && krugOn -> {
            val goals = snap.tasks.values.count { it.open && it.goal && (me.isBlank() || it.ownerId == me) }
            DelaScreen(
                "Круг жизни",
                listOfNotNull(plural(krugDirs.size, "направление", "направления", "направлений"), goals.takeIf { it > 0 }?.let { plural(it, "цель", "цели", "целей") })
                    .joinToString(" · "),
                principle = principle,
            )
        }
        // Дерево жизни (`renderTree` веба): плюсиками, колонками или списком — своим куском (`treeView`).
        view == DelaViews.View.TREE -> if (treeOn) {
            val open = snap.tasks.values.filter { it.open && (me.isBlank() || it.ownerId == me) }
            DelaScreen(
                "Дерево жизни",
                listOf(plural(open.count { !it.goal }, "дело", "дела", "дел"), plural(open.count { it.goal }, "цель", "цели", "целей")).joinToString(" · "),
                principle = principle,
                taskIds = if (treeMode == "list") DelaKrug.treeList(snap, me, sphere, krugDirs, today, treeDir, treeGoal, treeLoose).map { it.task.id } else emptyList(),
            )
        } else DelaScreen("Дерево жизни", empty = "Дерево жизни появится, когда сервер Дел его узнает (обновление сервера).")
        // Сохранённый фильтр (`renderFilter` веба): что показать и уровни; поменял — «Сохранить» или «Как было».
        filterId != null -> filters.firstOrNull { it.id == filterId }?.let { f0 ->
            val f = fdraft[f0.id] ?: f0
            val every = DelaGroups.scopeItems(snap, me, today, sphere, f.scope)
            val pk = f.pick?.takeIf { DelaGroups.pickOn(it, f.levels) }
            val items = DelaGroups.picked(every, f.levels, pk, snap, today)
            DelaScreen(
                f0.name,
                listOf(f.scope.title, DelaGroups.levelsName(f.levels) + (pk?.let { " — " + it.title } ?: ""), plural(items.size, "дело", "дела", "дел")).joinToString(" · "),
                nodes = DelaGroups.nested(items, f.levels, snap, today), levels = f.levels, levelKey = FILTER_NAV + f0.id,
                pick = pk, branches = f.levels.firstOrNull()?.let { DelaGroups.branches(every, it, snap, today) }.orEmpty(),
                empty = if (pk != null) "У «${pk.title}» здесь дел нет." else "Здесь пусто.",
            )
        }
        else -> null
    }

    // «Новое» — своими кусками (наговорки, предложения, без проекта, закрытое само); наговорки — вид сервера.
    val newParts: NewParts? =
        if (page == null && needle.isEmpty() && view == DelaViews.View.NEW) NewParts.of(snap, me, sphere, now, newBatch) else null

    // Страница проекта — как в вебе: группировка своя (без «по проектам»), «Сделанные», фильтр сделки.
    val projectScreen: DelaScreen? = (page as? DelaPage.Project)?.let { pg ->
        val p = snap.projects[pg.id]
        val lv = levelsOf("project").filter { it != DelaGroups.Level.PROJECT }
        val all = snap.tasks.values.filter { it.projectId == pg.id }
        val every = all.filter { (showDone || it.open) && (dealFilter.isBlank() || it.dealId == dealFilter) }
        val pk = picks["project"]?.takeIf { DelaGroups.pickOn(it, lv) }
        val items = DelaGroups.picked(every, lv, pk, snap, today)
        val open = all.filter { it.open }
        val late = open.count { it.dueDate.isNotBlank() && it.dueDate < today }
        DelaScreen(
            if (pg.id.isBlank()) "Входящие" else p?.name ?: "Проект",
            listOfNotNull(
                p?.let { Dela.KINDS.firstOrNull { k -> k.first == it.kind }?.second ?: it.kind },
                plural(open.size, "открытое", "открытых", "открытых"),
                p?.aliases?.takeIf { it.isNotEmpty() }?.let { "ещё зовут: " + it.joinToString(", ") },
            ).joinToString(" · "),
            alert = if (late > 0) "просрочено: $late" else "",
            nodes = DelaGroups.nested(items, lv, snap, today), levels = lv, levelKey = "project", levelOmit = setOf(DelaGroups.Level.PROJECT),
            pick = pk, branches = lv.firstOrNull()?.let { DelaGroups.branches(every, it, snap, today) }.orEmpty(),
            done = true, add = true, empty = if (pk != null) "У «${pk.title}» здесь дел нет." else "Дел нет. Следующий шаг — в строке выше.",
        )
    }
    // Страница человека — разделы веба.
    val personScreen: DelaScreen? = (page as? DelaPage.Person)?.let { pg ->
        val p = snap.people[pg.id]
        DelaScreen(
            p?.name ?: "Человек",
            p?.takeIf { it.short.isNotBlank() && it.short != it.name }?.let { "в задачах — ${it.short}" }.orEmpty(),
            groups = DelaViews.personSections(snap, pg.id).filter { it.items.isNotEmpty() }
                .mapIndexed { i, sec -> DelaViews.Group("p$i", sec.title, items = sec.items.sortedWith(DelaGroups.byTime(today))) },
            add = true, empty = "Открытых дел с ним нет.",
        )
    }

    // Что видит Claude с этого экрана — то же, что человек: название и дела в порядке показа.
    val askScreen: AskScreen? = when (val pg = page) {
        // Карточка (06.10.2026, docs/dela-phone-4.md, `pageScope` веба): с CRM сервер
        // берёт Opus и даёт ему карточку целиком — правит и хронологию, людей, сделки.
        is DelaPage.Person -> personScreen?.let { sc ->
            val title = "Человек: " + sc.title
            val card = crmOn && snap.people[pg.id]?.userId.isNullOrBlank()
            AskScreen(title, if (card) DelaAsk.personScope(title, sc.ids, pg.id) else DelaAsk.scope(title, sc.ids, personId = pg.id), card)
        }
        is DelaPage.Project -> projectScreen?.let { sc ->
            val title = if (pg.id.isBlank()) "Входящие" else "Проект: " + sc.title
            val pr = snap.projects[pg.id]
            val card = crmOn && pg.id.isNotBlank() && (pr == null || pr.kind == "client")
            AskScreen(title, if (card) DelaAsk.clientScope(title, sc.ids, pg.id) else DelaAsk.scope(title, sc.ids, projectId = pg.id), card)
        }
        is DelaPage.Deal -> snap.deals[pg.id].let { d ->
            val title = "Сделка: " + (d?.name ?: "?")
            val open = snap.tasks.values.filter { it.dealId == pg.id && it.open }.sortedWith(Dela.ORDER)
            val ids = open.map { it.id }
            // Новые дела со страницы сделки — в её проект и в неё саму (`deal_id` в scope).
            AskScreen(
                title,
                if (crmOn) DelaAsk.dealScope(title, ids, pg.id, d?.projectId.orEmpty())
                else DelaAsk.scope(title, ids, projectId = d?.projectId.orEmpty(), dealId = pg.id),
                crmOn,
            )
        }
        // На главной «Сейчас» П1, П2… — те же, что в «Новом»: «П1 поставь» Claude поймёт и отсюда.
        null -> screen?.let { AskScreen(it.title, DelaAsk.scope(it.title, it.ids, suggestionIds = it.home?.asks?.map { sg -> sg.id }.orEmpty())) }
            // Воронка, клиенты, люди, связи: дел нет, зато сделки экрана (`deal_ids`) — Claude правит их отсюда.
            ?: CRM_NAV.firstOrNull { crmOn && it.first == nav && needle.isEmpty() }?.let { (_, t) -> AskScreen(t, DelaAsk.scope(t, emptyList())) }
            // П1, П2… — те же, что в «Подскажи» (`scope.suggestion_ids`): «П1 поставь, срок пятница».
            ?: newParts?.let {
                // Одна пачка — Claude видит, что на экране только она (`pageScope` веба: «Новое: одна пачка — …»).
                val title = if (newBatch.isNotEmpty()) "Новое: одна пачка — " + DelaViews.batchTitle(snap, newBatch) else "Новое"
                AskScreen(title, DelaAsk.scope(title, it.ids, suggestionIds = it.asks.map { sg -> sg.id }))
            }
    }

    /**
     * Быстрое дело со страницы проекта или человека — как поле «＋» в вебе:
     * название, проект (и сделка фильтра), человек; остальное — карточкой.
     */
    fun quickAdd(title: String, projectId: String = "", dealId: String = "", personId: String = "") {
        // «!отбить» (и «!ждут»), «!мониторить» (и «!жду»), «!запустить» — группа дела; «!повестка» и «!сейчас»
        // понимаются и дальше. «Отбить» — только серверу, который знает `owed`.
        val q = DelaGroups.quick(title)
        var t = Dela.Task(
            id = Dela.newId(), title = q.title, projectId = projectId, dealId = dealId, personId = personId,
            ownerId = me, createdBy = me, source = "manual",
            ball = if (q.agenda) Dela.AGENDA else Dela.MINE, focusOn = if (q.now) today else "",
        )
        q.grp?.takeIf { it != DelaGroups.Grp.OWED || snap.groupsOn }?.let { t = DelaGroups.withGrp(t, it) }
        if (t.title.isBlank()) return
        scope.launch {
            app.delaDo(listOf(Dela.createOp(t)))
            Feedback.toast(app, "✓ ${t.title.take(40)}")
        }
    }

    val crmUi = remember { CrmUiState() }
    val listState = rememberLazyListState()
    /**
     * Пилюли карточки листают к разделу (`sec-*` веба): ключ виден — к нему; нет —
     * пролистать вниз на окно и посмотреть снова (разделы всегда ниже пилюль).
     */
    val scrollTo: (String) -> Unit = { key ->
        scope.launch {
            repeat(30) {
                val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
                if (info != null) { listState.animateScrollToItem(info.index); return@launch }
                val step = (listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset) * 0.8f
                if (listState.scrollBy(step) < 1f) return@launch
            }
        }
    }
    val clientOpen by app.delaStore.clientOpenFlow.collectAsState()
    val crm = DelaCrmContext(
        app, snap, me, today, views, queuedOps, moneyOn, actions, push, back = { pages = pages.dropLast(1) }, ui = crmUi,
        clientOpen = clientOpen,
        scrollTo = scrollTo,
        offerUndo = { said, back, refresh -> undoOffer = UndoOffer(said, back, refresh) },
    )

    var sphereSheet by remember { mutableStateOf(false) }
    // Разворот (`screens/08`): список слева, карточка дела справа, строка «сказать» — под ней. Вид v2 (этап 4
    // «Правки 5.0», лист «Дела» холста) — как веб Дел: колонка разделов с числами, список, карточка.
    val wide = ru.zf.pravka.ui.twoPane()
    val navColumn = wide && Look.v2
    val list: @Composable (Modifier) -> Unit = { listModifier ->
    LazyColumn(
        modifier = listModifier.fillMaxSize().bottomFade().scrollFade(listState)
            .onGloballyPositioned { seenRows.top = it.boundsInWindow().top },
        state = listState,
        // Разворот v2: слева колонка разделов — поля списка поуже, иначе карточкам дел не хватит ширины.
        // Боковина спрятана — слева место под язычок, чтобы он не лёг на карточки.
        contentPadding = PaddingValues(
            start = if (navColumn) (if (groupsPref[SIDE_KEY] == "0") 24.dp else 8.dp) else 16.dp,
            end = if (navColumn) 8.dp else 16.dp, top = 0.dp, bottom = if (navColumn) 24.dp else 150.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
    ) {
        // «▷ Идёт: Ужин с семьёй · 21 м · синк 18:40 · поиск · +» (`screens/06`):
        // что идёт в Засечке, когда сверялись с сервером, и два действия. Не «Сейчас:» — с 13.10.2026
        // «Сейчас» — раздел Дел (что владелец отметил), и одно слово про два разных было бы путаницей.
        item {
            Column(Modifier.fillMaxWidth()) {
                val mode = ru.zf.pravka.ui.LocalMode.current
                val ty = ru.zf.pravka.ui.LocalPravkaType.current
                val bad = st.lastError.isNotBlank() && st.lastErrorAt >= st.lastOk
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp)) {
                    if (running != null) {
                        Icon(Glyphs.Play, contentDescription = null, tint = mode.tint, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            androidx.compose.ui.text.buildAnnotatedString {
                                append("Идёт: ")
                                pushStyle(androidx.compose.ui.text.SpanStyle(color = ru.zf.pravka.ui.Ink.PlanText))
                                append(running.title.ifBlank { "без названия" })
                                append(" · " + ru.zf.pravka.core.Fmt.durMs(now - running.start))
                                pop()
                            },
                            style = ty.label,
                            color = mode.label,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    // Синк — коротко; тап — «отправить очередь и обновить».
                    Text(
                        delaSyncShort(link != null, st, queued.size, snap.syncedAt, now),
                        style = ty.meta.copy(fontWeight = if (bad) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (bad) mode.value else mode.meta,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = link != null && !st.running) { scope.launch { app.delaSync.sync("руками") } }
                            .padding(horizontal = 6.dp, vertical = 10.dp),
                    )
                    GlyphButton(
                        if (searching) Glyphs.Close else Glyphs.Search,
                        if (searching) "закрыть поиск" else "поиск: клиенты, проекты, люди, дела",
                        onClick = {
                            if (searching) query = ""
                            searching = !searching
                        },
                    )
                    GlyphButton(Glyphs.Plus, "новое дело руками", onClick = { newTask = true })
                }
                if (bad) {
                    // Ошибка синка — причиной целиком, как и раньше (железное правило 6).
                    Text(
                        delaStatusLine(link != null, st, queued.size, snap.syncedAt, now),
                        style = ty.meta,
                        color = mode.value,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
                // На развороте v2 поиск — в колонке разделов, второе поле здесь было бы лишним.
                if ((searching || query.isNotEmpty()) && !navColumn) PaperField(value = query, onValueChange = { query = it }, label = "Что найти: клиент, проект, человек, дело")
                if (link == null) {
                    Text(
                        "Дела не подключены — отсканируй QR сервера: «Настройки → Подключения → Дела». " +
                            "Пока показываю, что лежит в копии телефона.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }

        undoOffer?.let { u ->
            item(key = "undo") {
                PaperCard(
                    label = "сделано",
                    trailing = { GlyphButton(Glyphs.Close, "понятно", onClick = { undoOffer = null }, size = 30.dp) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(u.said, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        PaperTextButton("Вернуть", icon = Glyphs.Undo, onClick = {
                            undoOffer = null
                            crm.run(u.back, u.refresh, "Вернул как было")
                        })
                    }
                }
            }
        }

        if (notices.isNotEmpty()) {
            item(key = "notices") {
                PaperCard(
                    label = "сервер ответил",
                    trailing = { PaperTextButton("Понятно", onClick = { scope.launch { app.delaStore.dismissNotices() } }) },
                ) {
                    for (n in notices.take(5)) {
                        PaperHint(n.text, if (n.error) MaterialTheme.colorScheme.error else null)
                    }
                }
            }
        }

        // Шапка — как `head()` веба: «‹» на страницах, ☰ (боковая панель), заголовок и подзаголовок.
        val head: DelaScreen = when (val pg = page) {
            null -> screen ?: DelaScreen(
                CRM_NAV.firstOrNull { it.first == nav }?.second ?: "Новое",
                if (view == DelaViews.View.NEW) "подскажи — где без тебя не поставить; поставил — новое за три дня" else "",
            )
            is DelaPage.Project -> projectScreen ?: DelaScreen("Проект")
            is DelaPage.Person -> personScreen ?: DelaScreen("Человек")
            is DelaPage.Deal -> DelaScreen("Проект")
        }
        // Заголовок раздела — только у страниц (человек, проект, сделка): у
        // разделов его название и так стоит в сегментах (`screens/06`).
        if (page != null || needle.isNotEmpty() || (crmOn && CRM_NAV.any { it.first == nav }) || filterId != null) {
            item(key = "head") {
                DelaHead(
                    head,
                    sphere = "",
                    onBack = if (pages.isNotEmpty()) ({ pages = pages.dropLast(1) }) else null,
                    // v2: разделы — в боковине (язычок у края), своего «☰» у шапки страницы нет.
                    onMenu = if (Look.v4) ({ navOpen = true }) else null,
                )
            }
        }
        val crmNav = crmOn && CRM_NAV.any { it.first == nav }
        if (page == null && needle.isEmpty()) {
            if (navColumn) {
                // Разворот вида v2 (как веб): разделы — боковиной слева (`DelaSide`), ряда вкладок нет.
            } else if (Look.v2) {
                // Вид v2 (холст «Ковчег на телефоне», лист «Дела»): вкладки с чертой — Сейчас, Сегодня, Новое, Все
                // дела; остальное (Круг, дерево, фильтры, CRM, проекты) — в боковине: язычок у левого края. Открыт
                // раздел не из четырёх — он пятой вкладкой, чтобы было видно, где ты.
                item(key = "nav") {
                    val c = DelaViews.counts(snap, me, today, sphere, now)
                    val main = listOf(DelaViews.View.NOW, DelaViews.View.TODAY, DelaViews.View.NEW, DelaViews.View.ALL)
                    // «Все» — коротко, как на холсте: «Все дела» с числами в 411 dp не влезают.
                    val tabs = main.map { DelaTabItem(it.key, if (it == DelaViews.View.ALL) "Все" else it.title, c.label(it), hot = c.hot(it), bad = c.bad(it), full = it.title) } +
                        listOfNotNull(
                            if (main.none { it.key == nav }) DelaTabItem(nav, navTitle(nav, view, filters), "") else null,
                        )
                    DelaTabsV2(tabs, selected = nav, onSelect = go)
                }
            } else {
                // Вид 4.0: разделы веба одним рядом, с его числами («Сегодня 7»); CRM — следом, если она видна.
                item(key = "nav") {
                    val c = DelaViews.counts(snap, me, today, sphere, now)
                    val keys = DelaViews.View.entries.filter { (it != DelaViews.View.KRUG || krugOn) && (it != DelaViews.View.TREE || treeOn) }
                        .map { it.key to it.title } + (if (crmOn) CRM_NAV else emptyList())
                    // ☰ — боковая панель веба листом, рядом — разделы с числами.
                    ru.zf.pravka.ui.Segmented(
                        options = keys.map { (k, t) ->
                            val n = DelaViews.View.entries.firstOrNull { it.key == k }?.let { c.label(it) }.orEmpty()
                            if (n.isNotBlank()) "$t $n" else t
                        },
                        selected = keys.indexOfFirst { it.first == nav },
                        onSelect = { go(keys[it].first) },
                        leading = { GlyphButton(Glyphs.Menu, "разделы, CRM, проекты и люди", onClick = { navOpen = true }, tint = ru.zf.pravka.ui.LocalMode.current.label, size = 44.dp) },
                    )
                }
            }
            // Под разделами — дата, «со сроком сегодня N», «закрыто M» (`head()` веба), принцип дня и «просрочено N»
            // ссылкой во «Все дела»: само просроченное ни в «Сейчас», ни в «Сегодня» не попадает. На «Сейчас» вида v2
            // числа уже в шапке экрана — здесь только принцип и просрочка.
            val headed = view == DelaViews.View.NOW || view == DelaViews.View.TODAY || view == DelaViews.View.KRUG || view == DelaViews.View.TREE
            if (headed && screen != null) {
                val showSub = !(Look.v2 && view == DelaViews.View.NOW)
                if (showSub || screen.principle.isNotBlank() || screen.late > 0) item(key = "today:head") {
                    Column(Modifier.padding(start = 6.dp)) {
                        if (showSub) Text(
                            screen.sub,
                            style = ru.zf.pravka.ui.LocalPravkaType.current.label,
                            color = ru.zf.pravka.ui.LocalMode.current.label,
                        )
                        // Принцип дня (Круг жизни): одной тихой строкой курсивом, без галочек и баллов.
                        if (screen.principle.isNotBlank()) Text(
                            screen.principle,
                            style = ru.zf.pravka.ui.LocalPravkaType.current.body.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                            color = ru.zf.pravka.ui.Ink.PlanText,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (screen.late > 0) LateLink(screen.late) { go(DelaViews.View.ALL.key) }
                    }
                }
            }
        }

        val tools = ListTools(
            showDone = showDone,
            onShowDone = { showDone = !showDone },
            onGroup = { key, by -> scope.launch { app.delaStore.setGroup(key, by.key) } },
            onReschedule = { rescheduling = it },
            // «Без проекта» — не страница: проект выбирается у дела («без проекта» в строке).
            onProject = { id -> if (id.isNotBlank()) push(DelaPage.Project(id)) },
            onPerson = { id -> push(DelaPage.Person(id)) },
            onLevel = { key, i, lv, omit -> levelPick = LevelPick(key, i, lv, omit) },
            onPick = { key, p -> setPick(key, p) },
            onPickOpen = { key, list, lv -> branchPick = BranchPick(key, lv, list) },
            onSaveFilter = { sc ->
                val lv = screen?.levels.orEmpty()
                val pk = screen?.pick
                naming = DelaGroups.Filter("f-" + java.lang.Long.toString(System.currentTimeMillis(), 36), DelaGroups.suggestName(sc, lv, pk), sc, lv, pk)
                namingNew = true
            },
        )

        val pg = page
        if (pg != null) {
            when (pg) {
                is DelaPage.Person -> {
                    val p = snap.people[pg.id]
                    personScreen?.let { sc ->
                        screenBody(sc, "person", actions, tools.copy(onAdd = { title -> quickAdd(title, personId = pg.id) }, addLabel = "Новое дело про ${p?.label ?: "него"}…")) {
                            // Карточка человека — строками веба: роль, клиент, телефон, почта, Telegram…
                            if (p != null) for ((k, v) in DelaViews.personInfo(p, snap)) PaperHint("$k: $v", MaterialTheme.colorScheme.onSurface)
                            // Проекты человека — плашками-ссылками со стороной (09.10.2026, п. 11 задания 9).
                            val his = if (crmOn) ru.zf.pravka.core.DelaCrm.personDeals(snap, pg.id) else emptyList()
                            if (his.isNotEmpty()) {
                                PaperHint("Проекты")
                                ChipRow {
                                    for ((d, role) in his) PaperChip(d.name + " · " + role + if (d.closed) " · архив" else "", selected = false, onClick = { push(DelaPage.Deal(d.id)) })
                                }
                            }
                        }
                    }
                    // Теплота, «хаб», сделки и хронология — тем, кому видна CRM; у пользователя Дел их нет.
                    if (crmOn && p?.userId.isNullOrBlank()) crmPersonBlock(crm, pg.id)
                }
                is DelaPage.Project -> {
                    // Клиент из «Клиентов», которого копия ещё не знает, — тоже клиент: блок CRM спросит сервер сам.
                    val pr = snap.projects[pg.id]
                    val client = crmOn && pg.id.isNotBlank() && (pr == null || pr.kind == "client")
                    // Карточка клиента — одно под другим (09.10.2026): о клиенте, люди, проекты, дела, хронология, деньги, файлы.
                    if (client) {
                        crmClientTop(crm, pg.id)
                        item(key = "sec:tasks") {
                            Text("Дела", style = MaterialTheme.typography.labelLarge, color = ru.zf.pravka.ui.LocalMode.current.label,
                                modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                        }
                    }
                    projectScreen?.let { sc ->
                        val deals = snap.allDealsOf(pg.id)
                        screenBody(
                            sc, "project", actions,
                            tools.copy(
                                onAdd = { title -> quickAdd(title, projectId = pg.id, dealId = dealFilter) },
                                addLabel = if (dealFilter.isNotBlank()) "Новое дело по сделке… (!отбить, !мониторить)" else "Новое дело в проект… (!отбить, !мониторить)",
                            ),
                            onProjectPage = true,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (client) "дела клиента" else "дела проекта", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                if (pr != null) {
                                    val fav = pg.id in favs
                                    GlyphButton(
                                        Glyphs.Heart,
                                        if (fav) "убрать из избранного" else "в избранное — наверх боковой панели",
                                        tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        onClick = { scope.launch { app.delaStore.toggleFav(pg.id) } },
                                        size = 30.dp,
                                    )
                                }
                            }
                            // Сделки — фильтром дел, как в вебе: тап — только её дела, второй тап — все.
                            if (deals.isNotEmpty()) {
                                ChipRow {
                                    for (d in deals) {
                                        PaperChip(
                                            d.name + " · " + stageWord(d.stage) + if (moneyOn && (d.feeKop ?: 0L) > 0) " · " + ru.zf.pravka.core.DelaCrm.rubShort(d.feeKop) else "",
                                            selected = dealFilter == d.id,
                                            onClick = { dealFilter = if (dealFilter == d.id) "" else d.id },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                is DelaPage.Deal -> crmDealPage(crm, pg.id)
            }
            // Низ карточки клиента — под делами: хронология, деньги, файлы.
            if (pg is DelaPage.Project && crmOn && pg.id.isNotBlank() && snap.projects[pg.id].let { it == null || it.kind == "client" }) {
                crmClientBottom(crm, pg.id)
            }
            return@LazyColumn
        }

        if (crmNav && needle.isEmpty()) {
            when (nav) {
                "crm" -> crmPipeline(crm)
                "clients" -> crmClients(crm)
                "people" -> crmPeople(crm)
                else -> crmTies(crm)
            }
            return@LazyColumn
        }

        // Разобранные наговоры, которые ещё ждут решения, — над разделом.
        item(key = "raznoska") { RaznoskaSection(app, voiceInBar = true) }

        // Кнопки вопросов «Подскажи» — одни и те же в «Новом» и в блоке «Новое» на главной «Сейчас».
        val onAccept: (List<Dela.Suggestion>) -> Unit = { items ->
            scope.launch {
                app.delaDo(items.map { Dela.decideOp(it.id, accept = true) })
                Feedback.toast(app, if (items.size == 1) "✓ поставлено" else "✓ поставлено: ${items.size}")
            }
        }
        val onRejectAll: (List<Dela.Suggestion>) -> Unit = { items ->
            scope.launch {
                app.delaDo(items.map { Dela.decideOp(it.id, accept = false) })
                Feedback.toast(app, "Не надо: ${items.size}")
            }
        }
        // «Это оно» (`mergeInto` веба): слова предложения — комментарием к делу, само предложение отклонено с причиной.
        val onMerge: (Dela.Suggestion, Dela.Task) -> Unit = { sg, t ->
            val p = sg.payloadObj()
            val title = p.optString("title").takeIf { it.isNotBlank() && it != "null" }
            val notes = p.optString("notes").takeIf { it.isNotBlank() && it != "null" }
            val said = listOfNotNull(title, sg.quote.takeIf { it.isNotBlank() && it != title }?.let { "«$it»" }, notes).joinToString(" — ")
            val from = sg.batchTitle.ifBlank { DelaViews.mark(sg.source).word }
            scope.launch {
                app.delaDo(listOf(Dela.commentOp(t.id, "$from: $said"), Dela.decideOp(sg.id, accept = false, reason = "уже есть: ${t.title}")))
                Feedback.toast(app, "Срослось с «${t.title.take(40)}»: слова — комментарием к делу")
            }
        }
        val onAcceptSet: (Dela.Suggestion, org.json.JSONObject) -> Unit = { sg, set ->
            scope.launch {
                app.delaDo(listOf(Dela.decideOp(sg.id, accept = true, set = set)))
                Feedback.toast(app, "✓ направление поставлено")
            }
        }
        val home = screen?.home?.takeIf { needle.isEmpty() }
        val np = newParts
        if (np != null) {
            // «Понятно» и возврат закрытого само — только серверу, который их знает (тот же флаг, что у наговорок).
            val seenOn = snap.dictationsOn
            newView(
                parts = np,
                batch = newBatch,
                batchTitle = if (newBatch.isNotEmpty()) DelaViews.batchTitle(snap, newBatch) else "",
                onAllNew = { newBatch = "" },
                crm = crm,
                actions = actions,
                today = today,
                autoOpen = autoOpen,
                onAutoOpen = { autoOpen = !autoOpen },
                // «Вернуть» у сделанного само — одной пачкой: дело как было, без комментария-основания.
                onReopen = { t, sg ->
                    val ops = Dela.autoUndoOps(sg, t, seenOn)
                    if (ops != null) scope.launch {
                        app.delaDo(ops)
                        val was = sg.resultObj().optJSONObject("was")?.optString("title").orEmpty().takeIf { it.isNotBlank() && it != "null" }
                        Feedback.toast(app, if (sg.kind == "close") "Вернул в работу: ${t.title.take(40)}" else "Вернул как было: ${(was ?: t.title).take(40)}")
                    }
                },
                onSeen = if (seenOn) {
                    { items -> scope.launch { app.delaDo(listOf(Dela.seenOp(items.map { it.id }))) } }
                } else null,
                onAccept = onAccept,
                onRejectAll = onRejectAll,
                onMerge = onMerge,
                onEdit = { editingSuggestion = it },
                onReject = { rejecting = it },
                onAcceptSet = onAcceptSet,
            )
        } else if (home != null) {
            // Главная «Сейчас»: Сейчас → Новое → Сегодня; «Остального» здесь нет.
            homeView(
                home, crm, actions, today,
                onNew = { go(NAV_NEW) },
                onToday = { go(DelaViews.View.TODAY.key) },
                onAccept = onAccept,
                onRejectAll = onRejectAll,
                onEdit = { editingSuggestion = it },
                onReject = { rejecting = it },
                onMerge = onMerge,
                onAcceptSet = onAcceptSet,
            )
        } else if (screen != null) {
            // «Сегодня»: что ждёт в «Новом» — ссылкой под делами, как в вебе.
            // Поля «Новое дело…» у разделов нет (Правка 4.0): «+» — в строке
            // состояния, голос и текст — в нижней строке. У проекта и человека оно осталось.
            if (found != null) searchFound(found, snap, today, onProject = { push(DelaPage.Project(it)) }, onDeal = { push(DelaPage.Deal(it)) }, onPerson = { push(DelaPage.Person(it)) })
            val f0 = filterId?.let { id -> filters.firstOrNull { it.id == id } }
            if (f0 != null) {
                val f = fdraft[f0.id] ?: f0
                screenBody(screen, nav, actions, tools) {
                    // «Показать»: сегодня, всё, одна группа (`SCOPES` веба).
                    PaperHint("Показать")
                    ChipRow {
                        for (sc in DelaGroups.Scope.entries) PaperChip(sc.title, selected = f.scope == sc, onClick = {
                            fdraft = fdraft + (f0.id to f.copy(scope = sc))
                        })
                    }
                    ChipRow {
                        if (f != f0) {
                            PaperChip("Сохранить", selected = true, onClick = {
                                fdraft = fdraft - f0.id
                                saveFilters(filters.map { if (it.id == f0.id) f else it }, "Фильтр сохранён")
                            })
                            PaperChip("Как было", selected = false, onClick = { fdraft = fdraft - f0.id })
                        }
                        PaperChip("Переименовать", selected = false, onClick = { naming = f0; namingNew = false })
                        PaperChip("Удалить", selected = false, onClick = { deleting = f0 })
                    }
                }
            } else if (needle.isEmpty() && view == DelaViews.View.KRUG && krugOn) {
                krugOverview(
                    krugDirs, DelaKrug.treeModel(snap, me, today), DelaKrug.movement(snap, me, today), actions,
                    onDir = if (treeOn) ({ k -> toTree(k) }) else null,
                    onHang = if (treeOn) ({ t -> hangTask = t }) else null,
                )
            } else if (needle.isEmpty() && view == DelaViews.View.TREE && treeOn) {
                treeView(
                    krugDirs,
                    DelaKrug.treeModel(snap, me, today),
                    if (treeMode == "list") DelaKrug.treeList(snap, me, sphere, krugDirs, today, treeDir, treeGoal, treeLoose) else emptyList(),
                    TreeUi(
                        mode = treeMode,
                        dir = treeDir,
                        folded = ::folded,
                        // Ветку раскрыли руками — дерево уже не «до уровня N»: подсветка уровня снимается.
                        onFold = { k, v -> scope.launch { app.delaStore.setGroups(mapOf("fold:$k" to if (v) "1" else "0", "tree:level" to "-1")) } },
                        level = groupsPref["tree:level"]?.toIntOrNull() ?: -1,
                        onLevel = { n, folds ->
                            scope.launch {
                                app.delaStore.setGroups(folds.entries.associate { "fold:${it.key}" to if (it.value) "1" else "0" } + ("tree:level" to n.toString()))
                            }
                        },
                        onMode = { m -> scope.launch { app.delaStore.setGroup("tree:mode", m) } },
                        onDir = { k -> scope.launch { app.delaStore.setGroup("tree:dir", k) } },
                        onDirPick = { treeDirPick = true },
                        goal = treeGoal,
                        goalTitle = snap.tasks[treeGoal]?.title.orEmpty(),
                        loose = treeLoose,
                        onGoalPick = { treeGoalPick = true },
                        onGoal = { treeGoal = it },
                        onLoose = { treeLoose = !treeLoose },
                        onHangTask = { hangTask = it },
                        onHangProject = { hangProject = it },
                        onProject = { id -> push(DelaPage.Project(id)) },
                    ),
                    actions,
                )
            } else screenBody(screen, if (needle.isNotEmpty()) "search" else nav, actions, tools)
            // «Новое из встреч и чатов · 4 ›» — под делами «Сегодня» (`screens/06`).
            if (view == DelaViews.View.TODAY && needle.isEmpty()) {
                val waitingNew = DelaViews.newCount(snap, me, sphere, now)
                if (waitingNew > 0) {
                    item(key = "now:links") { NewLinkCard(waitingNew) { go(NAV_NEW) } }
                }
            }
        }

        if (snap.empty && link != null) {
            item { ru.zf.pravka.ui.EmptyState("Копия пустая — нажми «синк» вверху", icon = Glyphs.Refresh) }
        }
    }
    }
    // Строка «сказать» Дел — внизу (DESIGN §11.4): тот же разбор Разноски, что у «Д».
    /**
     * Строка Claude внизу — одна на всё (задания 7 и 9, поправки задания 10): что
     * сказано, уходит на сервер (`/api/ask`) вместе с тем, что на экране: дела
     * страницы, какие из них в окне, открытое дело. Не подключено к серверу —
     * прежняя дорога: разбор Разноски, как у «Д».
     */
    fun sayScope(openId: String): org.json.JSONObject? {
        val base = askScreen?.scope ?: return null
        val o = org.json.JSONObject(base.toString())
        return DelaAsk.see(o, seenRows.visible("t:"), open = openId, dealIds = seenRows.all("d:"), visibleDealIds = seenRows.visible("d:"))
    }
    fun saySend(text: String, openId: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val sc = if (link != null) sayScope(openId) else null
        if (sc == null) {
            val service = PravkaAccessibilityService.instance
            if (service == null) Feedback.toast(app, app.getString(R.string.toast_no_service))
            else { draft = ""; service.onRaznoskaText(t) }
            return
        }
        draft = t
        runAsk(t, sc)
    }
    fun sayMic(openId: String) {
        val service = PravkaAccessibilityService.instance
        when {
            service == null -> Feedback.toast(app, app.getString(R.string.toast_no_service))
            link == null -> service.onRaznoskaTap()
            // Второе нажатие — «готово»: сказанное придёт и уйдёт Claude. Пауза запись не кончает:
            // движок держит одну сессию через паузы и сам поднимает её, если она оборвалась.
            sayListening -> if (!service.finishDelaReason(keep = true)) sayListening = false
            else -> {
                val base = draft.trim()
                sayNote = ""
                sayListening = service.listenForDelaReason("Говори — нажми микрофон ещё раз, отдам Claude") { said ->
                    sayListening = false
                    val full = (base + " " + said.trim()).trim()
                    draft = full
                    if (said.isNotBlank()) saySend(full, openId)
                }
            }
        }
    }
    val sayBar: @Composable (Modifier, Dela.Task?, Boolean) -> Unit = { barModifier, opened, coversList ->
        val openId = opened?.id.orEmpty()
        DelaSayBar(
            value = draft,
            onValueChange = { draft = it; if (sayNote.isNotBlank() && !sayNoteError) sayNote = "" },
            placeholder = ru.zf.pravka.core.PillHint.say(ownerName, "говори дела"),
            about = when {
                opened != null -> "с делом «${opened.title.take(60)}»"
                askScreen?.card == true -> "с карточкой"
                view == DelaViews.View.NEW && page == null -> "с новым"
                page == null && view == null && filterId == null -> "со сделками на экране"
                else -> "с делами на экране"
            },
            card = askScreen?.card == true && opened == null,
            listening = sayListening,
            busy = ask.running.isNotBlank() || (link == null && talking),
            note = sayNote,
            noteError = sayNoteError,
            onMic = { sayMic(openId) },
            onSend = { saySend(draft, openId) },
            modifier = barModifier
                // Строка поверх списка — снизу окно кончается у неё (на развороте она под карточкой справа).
                .onGloballyPositioned { if (coversList) seenRows.bottom = it.boundsInWindow().top }
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 12.dp, end = 12.dp, bottom = 18.dp),
        )
    }
    // Боковина вида v2 (13.10.2026, владелец: «Делу нужна боковина, которая вылезает и скрывается. На разложенном
    // вылезает и сжимает всё, а на сложенном вылезает сверху»): одна модель — колонкой на разложенном и шторкой на
    // сложенном. «Назад» сначала прячет шторку — до страниц стопкой.
    androidx.activity.compose.BackHandler(enabled = drawerOpen && Look.v2 && !wide) { drawerOpen = false }
    LaunchedEffect(wide) { if (wide) drawerOpen = false }
    val sideOn = groupsPref[SIDE_KEY] != "0"
    val sideModel: DelaSideModel? = if (Look.v4) null else DelaSideModel(
        nav = if (page == null && needle.isEmpty()) nav else "",
        counts = DelaViews.counts(snap, me, today, sphere, now),
        views = DelaViews.View.entries.filter { (it != DelaViews.View.KRUG || krugOn) && (it != DelaViews.View.TREE || treeOn) },
        filters = filters.map { it to DelaGroups.scopeItems(snap, me, today, sphere, it.scope).size },
        filterNav = FILTER_NAV,
        crm = if (crmOn) CRM_NAV else emptyList(),
        projects = Dela.projectsNav(snap, me, today, sphere, favs),
        crmOn = crmOn,
        sphere = sphere,
        spheres = SPHERES,
        query = query,
        clientOpen = clientOpen,
        // Открытая сделка раскрывает своего клиента сам.
        hereClient = (page as? DelaPage.Deal)?.let { snap.deals[it.id]?.projectId }.orEmpty(),
        hereDeal = (page as? DelaPage.Deal)?.id.orEmpty(),
        clientDeals = { id -> if (crmOn) ru.zf.pravka.core.DelaCrm.navDeals(snap, id, today) else emptyList() },
    )
    // Из боковины — как переход по ссылке в вебе: страница вместо стопки; шторка уезжает.
    val sideActions = DelaSideActions(
        onNav = go,
        onQuery = { query = it; pages = emptyList() },
        onProject = { id -> drawerOpen = false; query = ""; searching = false; pages = listOf(DelaPage.Project(id)) },
        onPerson = { id -> drawerOpen = false; query = ""; searching = false; pages = listOf(DelaPage.Person(id)) },
        onDeal = { id -> drawerOpen = false; query = ""; searching = false; pages = listOf(DelaPage.Deal(id)) },
        onSphere = { sphere = it },
        onClientOpen = { ids, open -> crm.setClientOpen(ids, open) },
        onSubmit = { drawerOpen = false },
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalSeenRows provides seenRows) {
    Box(Modifier.fillMaxSize()) {
    // Шторка открыта — экран под затемнением для чтения с экрана как бы нет (модальная шторка): касание попадёт
    // в затемнение, а не в дело под ним.
    val covered = drawerOpen && sideModel != null && !wide
    Column(Modifier.fillMaxSize().then(if (covered) Modifier.clearAndSetSemantics {} else Modifier)) {
        val sphereName = if (sphere == "all") "Все сферы" else SPHERES.firstOrNull { it.first == sphere }?.second.orEmpty()
        // Подпись шапки вида v2 (холст: «сегодня 2 · закрыто 6 · просрочено 3») — сводка дня; сфера — словом
        // впереди, когда выбрана не «Всё»; тап по подписи — выбор сферы, как раньше. Вид 4.0 — сфера, как был.
        val headSub = if (Look.v4) sphereName else {
            val c = DelaViews.counts(snap, me, today, sphere, now)
            val done = DelaGroups.doneToday(snap, me, today)
            listOfNotNull(
                sphereName.takeIf { sphere != "all" },
                "сегодня ${c.today}",
                // Просрочка — раньше «закрыто»: строка короткая и режется справа, а это важнее.
                "просрочено ${c.late}".takeIf { c.late > 0 },
                "закрыто $done".takeIf { done > 0 },
            ).joinToString(" · ")
        }
        header(headSub) { sphereSheet = true }
        if (navColumn) {
            // Разворот вида v2 — как веб Дел (лист «Дела» холста): колонка разделов с числами | список | карточка;
            // строка Claude — под всеми тремя, о деле, открытом справа.
            val shown = paneTask
            // Боковина открыта с завода; тап по язычку — уезжает шириной (без рывка), «список | карточка»
            // забирают её место. Выбор помнит устройство — после перезапуска как было.
            val sideW by androidx.compose.animation.core.animateDpAsState(if (sideOn) SIDE_W else 0.dp, label = "side")
            Box(Modifier.weight(1f).fillMaxWidth()) {
            Row(Modifier.fillMaxSize()) {
                LaunchedEffect(Unit) { seenRows.bottom = Float.MAX_VALUE }
                // Содержимое не сжимается, пока боковина уезжает: оно прижато к правому краю и уходит за левый.
                Box(Modifier.width(sideW).fillMaxHeight().clipToBounds()) {
                    if (sideModel != null && sideW > 0.dp) DelaSide(
                        sideModel, sideActions,
                        Modifier.wrapContentWidth(Alignment.End, unbounded = true).width(SIDE_W).fillMaxHeight(),
                    )
                }
                Box(Modifier.weight(1f)) { list(Modifier) }
                Box(Modifier.weight(1f)) {
                    DelaTaskPane(
                        app = app,
                        task = shown,
                        snap = snap,
                        spentMs = shown?.let { actions.spent[it.id] } ?: 0L,
                        onStart = { shown?.let { actions.start(it) } },
                        onEdit = { openTask = shown },
                        onDone = { shown?.let { actions.done(it) } },
                        onPerson = { id -> push(DelaPage.Person(id)) },
                        onTask = { paneId = it.id },
                        onProject = { id -> push(DelaPage.Project(id)) },
                        me = me,
                        onNow = { shown?.let { actions.bolt(it) } },
                        onSun = { shown?.let { actions.toggleNow(it) } },
                        onMove = { shown?.let { rescheduling = listOf(it) } },
                    )
                }
            }
            // Язычок (`.dgrip` холста): открыта — в щели между боковиной и списком, ‹; спрятана — у края экрана, ›.
            SideGrip(
                open = sideOn,
                attached = sideW < 4.dp,
                onClick = { scope.launch { app.delaStore.setGroup(SIDE_KEY, if (sideOn) "0" else "1") } },
                modifier = Modifier.align(androidx.compose.ui.BiasAlignment(-1f, -0.08f)).offset(x = (sideW - 9.dp).coerceAtLeast(0.dp)),
            )
            }
            sayBar(Modifier, shown, false)
        } else if (wide) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                LaunchedEffect(Unit) { seenRows.bottom = Float.MAX_VALUE }
                Box(Modifier.weight(1f)) { list(Modifier) }
                Box(Modifier.weight(1f)) {
                    // Открытое справа дело; пусто — первое из дел дня («Сейчас», потом «Сегодня»).
                    val shown = paneTask
                    DelaTaskPane(
                        app = app,
                        task = shown,
                        snap = snap,
                        spentMs = shown?.let { actions.spent[it.id] } ?: 0L,
                        onStart = { shown?.let { actions.start(it) } },
                        onEdit = { openTask = shown },
                        onDone = { shown?.let { actions.done(it) } },
                        onPerson = { id -> push(DelaPage.Person(id)) },
                        onTask = { paneId = it.id },
                        onProject = { id -> push(DelaPage.Project(id)) },
                        me = me,
                    )
                    sayBar(Modifier.align(Alignment.BottomCenter), shown, false)
                }
            }
        } else {
            Box(Modifier.weight(1f)) {
                list(Modifier)
                sayBar(Modifier.align(Alignment.BottomCenter), null, true)
            }
        }
    }
    // Сложенный вида v2: язычок у левого края, боковина — шторкой ~252 dp поверх всего экрана (фон `--side`,
    // линия справа), остальное — под затемнением; язычок с ‹ — у правого края шторки.
    if (sideModel != null && !wide) {
        androidx.compose.animation.AnimatedVisibility(
            visible = drawerOpen,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Kv.scrim)
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "спрятать разделы",
                    ) { drawerOpen = false },
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = drawerOpen,
            enter = androidx.compose.animation.slideInHorizontally { -it },
            exit = androidx.compose.animation.slideOutHorizontally { -it },
        ) {
            val line = Kv.line
            Box(
                Modifier
                    .width(DRAWER_W)
                    .fillMaxHeight()
                    .background(Kv.side)
                    .drawBehind {
                        drawRect(
                            line,
                            topLeft = androidx.compose.ui.geometry.Offset(size.width - 1.dp.toPx(), 0f),
                            size = androidx.compose.ui.geometry.Size(1.dp.toPx(), size.height),
                        )
                    }
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(top = 18.dp),
            ) {
                DelaSide(sideModel, sideActions, Modifier.fillMaxSize())
            }
        }
        val gx by androidx.compose.animation.core.animateDpAsState(if (drawerOpen) DRAWER_W else 0.dp, label = "grip")
        SideGrip(
            open = drawerOpen,
            attached = !drawerOpen,
            onClick = { drawerOpen = !drawerOpen },
            modifier = Modifier.align(Alignment.CenterStart).offset(x = gx),
        )
    }
    }
    }
    if (sphereSheet) {
        PaperSheet(onDismiss = { sphereSheet = false }, title = "Сфера") {
            ru.zf.pravka.ui.Segmented(
                options = SPHERES.map { it.second },
                selected = SPHERES.indexOfFirst { it.first == sphere }.coerceAtLeast(0),
                onSelect = { sphere = SPHERES[it].first; sphereSheet = false },
            )
        }
    }

    val t = openTask
    if (t != null) {
        // Правка считается от того, что владелец видел, открывая карточку (`was`):
        // синк, пришедший пока она открыта, не откатывается чужим старым значением.
        val fresh = t
        DelaTaskSheet(
            app = app,
            task = fresh,
            snap = snap,
            isNew = false,
            onDismiss = { openTask = null },
            onSave = { after ->
                openTask = null
                val (set, was) = Dela.diff(fresh, after, tree = snap.treeOn)
                if (set.length() > 0) scope.launch { app.delaDo(listOf(Dela.setOp(fresh.id, set, was))) }
            },
            // «Место в дереве»: тап по цели — её карточка (поправленное здесь сохраняется до перехода), по проекту — страница.
            onOpenTask = { after, to ->
                val (set, was) = Dela.diff(fresh, after, tree = snap.treeOn)
                if (set.length() > 0) scope.launch { app.delaDo(listOf(Dela.setOp(fresh.id, set, was))) }
                openTask = snap.tasks[to.id] ?: to
            },
            onOpenProject = { id -> openTask = null; push(DelaPage.Project(id)) },
            onStatus = { op ->
                openTask = null
                scope.launch { app.delaDo(listOf(Dela.statusOp(op, fresh.id))) }
            },
            onStart = { openTask = null; actions.start(fresh) },
            // Микрофона у дела нет (поправки задания 10): та же строка Claude — над карточкой, с `open`.
            say = if (link != null) ({ sayBar(Modifier, fresh, false) }) else null,
        )
    }
    CrmSheets(crm)
    // Боковая панель всегда в дереве: закрытая — пуста, а закрываясь,
    // успевает уехать (баг №1). Числа считаются, только пока она видна.
    run {
        DelaNavSheet(
            visible = navOpen && Look.v4,
            nav = if (page == null && needle.isEmpty()) nav else "",
            sphere = sphere,
            counts = { DelaViews.counts(snap, me, today, sphere, now) },
            filters = { filters.map { it to DelaGroups.scopeItems(snap, me, today, sphere, it.scope).size } },
            crmOn = crmOn,
            krugOn = krugOn,
            treeOn = treeOn,
            projects = { Dela.projectsNav(snap, me, today, sphere, favs) },
            onSphere = { sphere = it },
            onNav = go,
            clientOpen = clientOpen,
            // Открытая сделка раскрывает своего клиента сам.
            hereClient = (page as? DelaPage.Deal)?.let { snap.deals[it.id]?.projectId }.orEmpty(),
            hereDeal = (page as? DelaPage.Deal)?.id.orEmpty(),
            clientDeals = { id -> if (crmOn) ru.zf.pravka.core.DelaCrm.navDeals(snap, id, today) else emptyList() },
            onClientOpen = { ids, open -> crm.setClientOpen(ids, open) },
            onDeal = { id -> navOpen = false; query = ""; searching = false; pages = listOf(DelaPage.Deal(id)) },
            // Из боковой панели — как переход по ссылке в вебе: страница вместо стопки.
            onProject = { id -> navOpen = false; query = ""; searching = false; pages = listOf(DelaPage.Project(id)) },
            onPerson = { id -> navOpen = false; query = ""; searching = false; pages = listOf(DelaPage.Person(id)) },
            onDismiss = { navOpen = false },
        )
    }
    rescheduling?.let { list ->
        RescheduleSheet(list.size, LocalDate.now(), onDismiss = { rescheduling = null }) { day, what ->
            rescheduling = null
            scope.launch {
                // Каждому — своя правка с тем, что было: спор с другой стороной скажет сервер.
                app.delaDo(list.map { t ->
                    Dela.setOp(t.id, org.json.JSONObject().put("due_date", Dela.nul(day)), org.json.JSONObject().put("due_date", Dela.nul(t.dueDate)))
                })
                Feedback.toast(app, "Перенёс" + (if (list.size > 1) " (${list.size})" else "") + ": $what")
            }
        }
    }
    projectFor?.let { list ->
        // «без проекта» у строки или «Все в проект…» — как `projectPop` веба: проект меняется, сделка уходит.
        PickSheet(
            title = if (list.size > 1) "Проект для ${plural(list.size, "дела", "дел", "дел")}" else "Проект",
            none = "без проекта",
            items = snap.liveProjects().map { Triple(it.id, it.name, it.aliases.joinToString(", ")) },
            selected = list.map { it.projectId }.distinct().singleOrNull().orEmpty(),
            onDismiss = { projectFor = null },
        ) { id ->
            projectFor = null
            val ops = list.mapNotNull { t ->
                val (set, was) = Dela.diff(t, t.copy(projectId = id, dealId = if (id == t.projectId) t.dealId else ""))
                if (set.length() > 0) Dela.setOp(t.id, set, was) else null
            }
            if (ops.isNotEmpty()) scope.launch {
                app.delaDo(ops)
                Feedback.toast(app, (snap.projects[id]?.name?.let { "В «$it»" } ?: "Без проекта") + if (ops.size > 1) ": ${ops.size}" else "")
            }
        }
    }
    levelPick?.let { lp ->
        // Уровень [i]: «—» убирает его и всё после; один уровень дважды не встаёт (`levelsBar` веба).
        PickSheet(
            title = "Уровень ${lp.i + 1}",
            none = if (lp.i == 0) "без групп" else "— (убрать)",
            items = DelaGroups.Level.entries.filter { it !in lp.omit && (it != DelaGroups.Level.DIR || krugOn) }.map { Triple(it.key, it.title, "") },
            selected = lp.levels.getOrNull(lp.i)?.key.orEmpty(),
            onDismiss = { levelPick = null },
        ) { k ->
            levelPick = null
            val next = DelaGroups.setLevel(lp.levels, lp.i, DelaGroups.Level.of(k))
            if (lp.key.startsWith(FILTER_NAV)) {
                val id = lp.key.removePrefix(FILTER_NAV)
                filters.firstOrNull { it.id == id }?.let { f0 -> fdraft = fdraft + (id to (fdraft[id] ?: f0).copy(levels = next)) }
            } else scope.launch { app.delaStore.setGroup("lv:" + lp.key, DelaGroups.levelsKey(next)) }
        }
    }
    // Дерево жизни: направление дерева, цель «Списка» и «Куда повесить» — листами.
    if (treeDirPick) {
        PickSheet(
            title = "Направление",
            none = "Все направления",
            items = krugDirs.map { Triple(it.key, it.name, it.zone) },
            selected = treeDir,
            onDismiss = { treeDirPick = false },
        ) { k ->
            treeDirPick = false
            scope.launch { app.delaStore.setGroup("tree:dir", k) }
        }
    }
    if (treeGoalPick) {
        GoalSheet(
            title = "Дела под целью",
            pool = DelaKrug.goalList(snap, me, krugDirs),
            dirs = krugDirs,
            current = treeGoal,
            onDismiss = { treeGoalPick = false },
            onGoal = { g -> treeGoalPick = false; treeGoal = g.id },
            onNone = { treeGoalPick = false; treeGoal = "" },
        )
    }
    hangTask?.let { ht ->
        val t0 = snap.tasks[ht.id] ?: ht
        GoalSheet(
            title = "Куда повесить",
            pool = DelaKrug.goalPool(snap, me, krugDirs, DelaKrug.below(snap, t0.id)),
            dirs = krugDirs,
            current = t0.whyId,
            onDismiss = { hangTask = null },
            onGoal = { g ->
                hangTask = null
                val h = DelaKrug.hang(t0, snap, krugDirs, goal = g)
                hangSend(listOfNotNull(h.set?.let { Dela.setOp(t0.id, it, DelaGroups.was(t0, it)) }), h.said)
            },
            onDir = { k ->
                hangTask = null
                val h = DelaKrug.hang(t0, snap, krugDirs, dir = k)
                hangSend(listOfNotNull(h.set?.let { Dela.setOp(t0.id, it, DelaGroups.was(t0, it)) }), h.said)
            },
            onNone = {
                hangTask = null
                val h = DelaKrug.hang(t0, snap, krugDirs)
                hangSend(listOfNotNull(h.set?.let { Dela.setOp(t0.id, it, DelaGroups.was(t0, it)) }), h.said)
            },
        )
    }
    hangProject?.let { p ->
        // Проект — только на цель (`moveTo` веба: «Проект вешается на цель, а не прямо на направление»).
        val cur = snap.krug[p.id]?.whyId.orEmpty()
        GoalSheet(
            title = "Проект «${p.name.take(40)}» — к цели",
            pool = DelaKrug.goalPool(snap, me, krugDirs),
            dirs = krugDirs,
            current = cur,
            onDismiss = { hangProject = null },
            onGoal = { g ->
                hangProject = null
                if (g.id != cur) hangSend(
                    listOf(ru.zf.pravka.core.DelaCrm.projectSetOp(p.id, org.json.JSONObject().put("why_id", g.id))),
                    "Проект — к цели «${g.title.take(40)}»",
                )
            },
            onNone = {
                hangProject = null
                hangSend(listOf(ru.zf.pravka.core.DelaCrm.projectSetOp(p.id, org.json.JSONObject().put("why_id", org.json.JSONObject.NULL))), "Проект — без цели")
            },
        )
    }
    branchPick?.let { bp ->
        BranchSheet(bp, onDismiss = { branchPick = null }) { b ->
            branchPick = null
            setPick(bp.key, b?.let { DelaGroups.pickOf(it, bp.lv) })
        }
    }
    naming?.let { f ->
        FilterNameSheet(
            title = if (namingNew) "Сохранить фильтр" else "Переименовать фильтр",
            initial = f.name,
            onDismiss = { naming = null },
        ) { name ->
            naming = null
            if (namingNew) {
                saveFilters(filters + f.copy(name = name), "Фильтр сохранён: $name")
                go(FILTER_NAV + f.id)
            } else saveFilters(filters.map { if (it.id == f.id) it.copy(name = name) else it }, "Переименовал")
        }
    }
    deleting?.let { f ->
        PaperSheet(onDismiss = { deleting = null }, title = "Удалить фильтр «${f.name}»?", icon = Glyphs.Delete, subtitle = "Дела останутся.") {
            Row {
                Spacer(Modifier.weight(1f))
                PaperButton("Удалить", primary = true, onClick = {
                    deleting = null
                    saveFilters(filters.filter { it.id != f.id }, "Фильтр удалён")
                    go(DelaViews.View.NOW.key)
                })
            }
        }
    }
    ask.talk?.let { talk ->
        TalkSheet(
            talk = talk,
            snap = snap,
            onText = { v -> ask.talk = ask.talk?.copy(text = v) },
            onSend = { text -> runAsk(text, talk.scope, cont = true) },
            onDismiss = { if (ask.running.isBlank()) ask.talk = null else Feedback.toast(app, "Claude ещё думает — дождусь ответа") },
            onOpen = { id -> snap.tasks[id]?.let { openTask = it } },
            onUndo = { i ->
                val turn = talk.turns.getOrNull(i) ?: return@TalkSheet
                ask.talk = talk.copy(turns = talk.turns.mapIndexed { k, t -> if (k == i) t.copy(undone = true) else t })
                scope.launch {
                    // Отмена — обычными операциями очереди с op_id: дошли не сразу — уйдут сами.
                    app.delaStore.enqueue(DelaAsk.undoOps(turn.result))
                    val sent = app.delaSync.pushNow(4_000L)
                    Feedback.toast(app, if (sent) "Вернул как было" else "Вернул как было — уйдёт на сервер, когда будет связь")
                }
            },
        )
    }
    if (newTask) {
        val blank = remember { Dela.Task(id = Dela.newId(), title = "", ownerId = me, source = "manual") }
        DelaTaskSheet(
            app = app,
            task = blank,
            snap = snap,
            isNew = true,
            onDismiss = { newTask = false },
            onSave = { after ->
                newTask = false
                if (after.title.isNotBlank()) scope.launch { app.delaDo(listOf(Dela.createOp(after, tree = snap.treeOn))) }
            },
            onStatus = { newTask = false },
            onStart = { newTask = false },
        )
    }
    val sg = editingSuggestion
    if (sg != null) {
        val proposed = remember(sg.id) { Dela.draftOf(sg, snap, me) }
        DelaTaskSheet(
            app = app,
            task = proposed,
            snap = snap,
            isNew = true,
            title = if (sg.kind == "update") "Уточнить дело" else "Предложение",
            saveText = "Принять",
            suggestion = true,
            // У уточнения подробности (note) лягут комментарием к делу — видны и здесь.
            quote = listOfNotNull(sg.payloadObj().optString("note").takeIf { sg.kind == "update" && it.isNotBlank() && it != "null" }, sg.quote.takeIf { it.isNotBlank() })
                .joinToString(" · "),
            onDismiss = { editingSuggestion = null },
            onSave = { after ->
                editingSuggestion = null
                // В set — только поправленное: остальное сервер возьмёт из
                // предложения сам (имена — в id по своему справочнику).
                val (set, _) = Dela.diff(proposed, after)
                scope.launch {
                    app.delaDo(listOf(Dela.decideOp(sg.id, accept = true, set = set)))
                    Feedback.toast(app, "✓ принято с поправкой")
                }
            },
            onStatus = { editingSuggestion = null },
            onStart = { editingSuggestion = null },
        )
    }
    val rj = rejecting
    if (rj != null) {
        RejectSheet(
            suggestion = rj,
            onDismiss = { rejecting = null },
            onReject = { reason ->
                rejecting = null
                scope.launch {
                    app.delaDo(listOf(Dela.decideOp(rj.id, accept = false, reason = reason)))
                    Feedback.toast(app, "Отклонено")
                }
            },
        )
    }
}

/**
 * Находки поиска над делами (`renderSearch` веба): клиенты и разделы, проекты
 * клиентов, люди — до [DelaViews.SEARCH_SHOWN] строк на группу, дальше «и ещё N».
 */
private fun LazyListScope.searchFound(
    f: DelaViews.Found,
    snap: Dela.Snapshot,
    today: String,
    onProject: (String) -> Unit,
    onDeal: (String) -> Unit,
    onPerson: (String) -> Unit,
) {
    fun <T> part(key: String, title: String, list: List<T>, row: @Composable (T) -> Unit) {
        if (list.isEmpty()) return
        item(key = "search:$key") {
            PaperCard(label = "$title · ${list.size}") {
                list.take(DelaViews.SEARCH_SHOWN).forEachIndexed { i, x -> if (i > 0) RowRule(); row(x) }
                if (list.size > DelaViews.SEARCH_SHOWN) PaperHint("и ещё ${list.size - DelaViews.SEARCH_SHOWN} — уточни запрос")
            }
        }
    }
    val openBy = snap.tasks.values.filter { it.open && it.projectId.isNotBlank() }.groupingBy { it.projectId }.eachCount()
    part("projects", "Клиенты и разделы", f.projects) { p ->
        val n = openBy[p.id] ?: 0
        FoundLine(
            p.name + if (!p.live) " · архив" else "",
            listOfNotNull(Dela.KINDS.firstOrNull { it.first == p.kind }?.second ?: p.kind, n.takeIf { it > 0 }?.let { plural(it, "открытое дело", "открытых дела", "открытых дел") })
                .joinToString(" · "),
        ) { onProject(p.id) }
    }
    part("deals", "Проекты клиентов", f.deals) { d ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            StageDot(d.stage)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                FoundLine(d.name, listOfNotNull(
                    if (d.closed && d.outcome.isNotBlank()) ru.zf.pravka.core.DelaCrm.OUTCOME[d.outcome] ?: d.outcome else stageWord(d.stage),
                    snap.projects[d.projectId]?.name,
                ).joinToString(" · ")) { onDeal(d.id) }
            }
        }
    }
    part("people", "Люди", f.people) { x ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(x, 28.dp)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                FoundLine(
                    x.name + if (x.short.isNotBlank() && x.short != x.name) " · ${x.short}" else "",
                    listOfNotNull(x.role.takeIf { it.isNotBlank() }, ru.zf.pravka.core.DelaCrm.orgLabel(snap, x.orgId)?.name).joinToString(" · "),
                ) { onPerson(x.id) }
            }
        }
    }
}

@Composable
private fun FoundLine(title: String, more: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        if (more.isNotBlank()) Text(more, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Правка CRM, которую можно вернуть: слова, операции «как было» и какие виды после них обновить. */
private data class UndoOffer(val said: String, val back: List<org.json.JSONObject>, val refresh: List<String>)

/** Что умеет строка дела — одним набором на все виды (и на страницах CRM). */
internal class DelaActions(
    val open: (Dela.Task) -> Unit,
    val start: (Dela.Task) -> Unit,
    val done: (Dela.Task) -> Unit,
    val person: (String) -> Unit,
    val project: (String) -> Unit,
    val starting: String,
    /** Солнце: «на сегодня» и «на завтра» (`toggleToday` веба). Имя старое — до 10.10.2026 тут была молния. */
    val toggleNow: (Dela.Task) -> Unit = {},
    /** Молния «В «Сейчас»» / «Из «Сейчас»» (`toggleNow` веба, 13.10.2026): отметка владельца, шестое — тост. */
    val bolt: (Dela.Task) -> Unit = {},
    /** «без проекта» у строки — кнопка выбора проекта; null — подпись просто текстом. */
    val pickProject: ((List<Dela.Task>) -> Unit)? = null,
    /** Дело, которое сейчас идёт в ленте; "" — никакое. */
    val running: String = "",
    val runningMs: Long = 0L,
    /** Время в ленте по id дела. */
    val spent: Map<String, Long> = emptyMap(),
    val stop: () -> Unit = {},
    /** Копия — подписям строки нужны имена проектов, сделок и людей (как в вебе). */
    val snap: Dela.Snapshot = Dela.Snapshot(),
    /** Кто смотрит: Круг (направления, цели) — только у своих дел. "" — не знаем, всё своё. */
    val me: String = "",
    /** Дело, открытое справа на развороте: строка подсвечена (`.li.sel` холста). "" — никакое. */
    val selectedId: String = "",
    /**
     * Короткая строка (разворот вида v2, `.li` холста): только квадрат и слова. Солнце и ▶ — в карточке справа,
     * куда строка и ведёт: в средней колонке на них уходила половина ширины, и названия ломались по слову.
     */
    val compact: Boolean = false,
)

/** «обновлено 20:42 · в очереди 2» или причина целиком — молчаливая очередь читается как поломка. */
private fun delaStatusLine(
    connected: Boolean,
    st: ru.zf.pravka.data.DelaSync.Status,
    queued: Int,
    syncedAt: Long,
    now: Long,
): String {
    val parts = mutableListOf<String>()
    when {
        !connected -> parts += "не подключено"
        st.running -> parts += "связываюсь…"
        st.lastError.isNotBlank() && st.lastErrorAt >= st.lastOk -> parts += st.lastError
        syncedAt > 0 -> {
            val mins = ((now - syncedAt) / 60_000L).toInt()
            parts += if (mins < 1) "обновлено только что"
            else if (mins < 120) "обновлено $mins мин назад"
            else "обновлено " + SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(syncedAt))
        }
        else -> parts += "ещё не обновлялось"
    }
    if (queued > 0) parts += "в очереди $queued — уйдут сами, «обновить» — сейчас"
    return parts.joinToString(" · ")
}

// ---------------------------------------------------------------- виды

/**
 * «Новое» (08.10.2026, docs/dela-phone-6.md, `renderNew` веба): «Подскажи» —
 * только где без человека не поставить, «Поставил» — новые дела за три дня
 * одной строкой со значком источника справа, «Сделано само» — свёрнуто.
 * Наговорки целиком, «без проекта», минуты и «что сказала автоматика» сюда
 * больше не идут: что сказано — в карточке дела, без проекта — в «Неделе».
 */
private fun LazyListScope.newView(
    parts: NewParts,
    /** Одна пачка (`batch_ref`) или пусто — всё «Новое». */
    batch: String,
    batchTitle: String,
    /** «Всё «Новое»» — снять пачку. */
    onAllNew: () -> Unit,
    crm: DelaCrmContext,
    actions: DelaActions,
    today: String,
    autoOpen: Boolean,
    onAutoOpen: () -> Unit,
    onReopen: (Dela.Task, Dela.Suggestion) -> Unit,
    /** «Понятно» у сделанного само; null — сервер этого ещё не умеет. */
    onSeen: ((List<Dela.Suggestion>) -> Unit)?,
    onAccept: (List<Dela.Suggestion>) -> Unit,
    onRejectAll: (List<Dela.Suggestion>) -> Unit,
    onEdit: (Dela.Suggestion) -> Unit,
    onReject: (Dela.Suggestion) -> Unit,
    onMerge: (Dela.Suggestion, Dela.Task) -> Unit,
    /** «Да» с поправкой — вопрос разборщика Круга: выбранные направления. */
    onAcceptSet: (Dela.Suggestion, org.json.JSONObject) -> Unit = { sg, _ -> onAccept(listOf(sg)) },
) {
    val snap = crm.snap
    val zone = java.time.ZoneId.systemDefault()
    // Одна пачка — сверху её подпись и «Всё «Новое»» (`toolbar` веба у `#/new/<batch_ref>`).
    if (batch.isNotEmpty()) {
        item(key = "n:batch") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Одна пачка: $batchTitle",
                    style = ru.zf.pravka.ui.LocalPravkaType.current.label,
                    fontWeight = FontWeight.SemiBold,
                    color = ru.zf.pravka.ui.LocalMode.current.label,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                PaperTextButton("Всё «Новое»", onClick = onAllNew)
            }
        }
    }
    if (parts.asks.isNotEmpty()) {
        item(key = "n:asks") {
            val mode = ru.zf.pravka.ui.LocalMode.current
            PaperCard(
                label = "Подскажи · ${parts.asks.size}",
                trailing = if (parts.asks.size > 1) {
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PaperTextButton("Не надо все", onClick = { onRejectAll(parts.asks) })
                            PaperTextButton("Поставить все", onClick = { onAccept(parts.asks) })
                        }
                    }
                } else null,
            ) {
                Text("без тебя не поставлю", style = ru.zf.pravka.ui.LocalPravkaType.current.meta, color = mode.meta)
                parts.asks.forEachIndexed { i, sg ->
                    RowRule()
                    AskRow(
                        sg, i + 1, snap, crm.me, today,
                        onAccept = { onAccept(listOf(sg)) }, onEdit = { onEdit(sg) }, onReject = { onReject(sg) },
                        onOpen = actions.open, onMerge = { t -> onMerge(sg, t) },
                        onAcceptSet = { set -> onAcceptSet(sg, set) },
                    )
                }
            }
        }
    }
    if (parts.feed.isNotEmpty()) {
        val from = DelaViews.sugByTask(snap)
        item(key = "n:feed") {
            val mode = ru.zf.pravka.ui.LocalMode.current
            val ty = ru.zf.pravka.ui.LocalPravkaType.current
            PaperCard(label = "Поставил · ${parts.feed.size}") {
                Text("откуда — значок справа, что сказано — в карточке", style = ty.meta, color = mode.meta)
                for ((day, items) in DelaViews.feedDays(parts.feed, today, zone)) {
                    Text(day, style = ty.label, fontWeight = FontWeight.SemiBold, color = mode.label, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    items.forEachIndexed { i, t ->
                        if (i > 0) RowRule()
                        FeedRow(t, actions, today, DelaViews.markWords(t, from[t.id]?.batchTitle.orEmpty(), snap, today, zone))
                    }
                }
                if (parts.feed.size > DelaViews.FEED_MAX) PaperHint("и ещё ${parts.feed.size - DelaViews.FEED_MAX} — во «Всех делах»")
            }
        }
    }
    // Закрыть и уточнить автоматика может сама (правило 5 сервера) — свёрнуто одной
    // строкой; развёрнутое — строки без цитат: «Понятно» убирает, «Вернуть» — как было.
    val autoDone = parts.autoDone
    if (autoDone.isNotEmpty()) {
        item(key = "n:auto") {
            val mode = ru.zf.pravka.ui.LocalMode.current
            PaperCard(
                label = "Сделано само · ${autoDone.size}",
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (autoOpen && autoDone.size > 1 && onSeen != null) PaperTextButton("Понятно, все", onClick = { onSeen(autoDone) })
                        PaperTextButton(if (autoOpen) "Свернуть" else "Показать", onClick = onAutoOpen)
                    }
                },
            ) {
                Text(DelaViews.autoSummary(autoDone) + " по встречам и Telegram", style = ru.zf.pravka.ui.LocalPravkaType.current.meta, color = mode.meta)
                if (autoOpen) autoDone.forEach { sg ->
                    RowRule()
                    AutoDoneRow(sg, snap.tasks[sg.taskId], today, onReopen = { t -> onReopen(t, sg) }, onSeen = onSeen?.let { f -> { f(listOf(sg)) } })
                }
            }
        }
    }
    if (parts.empty) {
        item(key = "n:empty") {
            Box(Modifier.padding(start = 4.dp)) {
                PaperHint(if (batch.isNotEmpty()) "Эта пачка уже разобрана." else "Пусто. Новое появится, когда наговоришь или придёт со встречи и из Telegram.")
            }
        }
    }
}

/**
 * Значок источника (`ORIGIN` веба): форма — откуда, цвет — кремовый `plan` у
 * наговорки, встречи, Telegram и Claude, бледный у сделанного руками и
 * перенесённого (без радуги — поправка задания 10).
 */
@Composable
internal fun SourceMark(source: String, size: androidx.compose.ui.unit.Dp = 16.dp) {
    val m = DelaViews.mark(source)
    Icon(
        when (m.glyph) {
            "mic" -> Glyphs.Mic
            "meet" -> Glyphs.Groups
            "send" -> Glyphs.Telegram
            "chat" -> Glyphs.Ask
            "list" -> Glyphs.ListLines
            else -> Glyphs.Edit
        },
        contentDescription = m.word,
        tint = if (m.plan) ru.zf.pravka.ui.Ink.PlanText else ru.zf.pravka.ui.LocalMode.current.meta,
        modifier = Modifier.size(size),
    )
}

/**
 * Строка «Поставил»: галка, название и сразу за ним срок и проект — без «без
 * проекта», «@человека», минут и меток; справа — значок источника или кружок
 * поставившего. Откуда и когда — касанием или долгим нажатием на значок.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FeedRow(t: Dela.Task, actions: DelaActions, today: String, where: String) {
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val snap = actions.snap
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().seen("t:" + t.id).clip(RoundedCornerShape(12.dp)).clickable { actions.open(t) }.padding(vertical = 2.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).clickable { actions.done(t) }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(20.dp).clip(CircleShape).border(2.dp, mode.tint, CircleShape))
        }
        val meta = listOfNotNull(
            DelaViews.due(t, today)?.text,
            snap.projects[t.projectId]?.name ?: t.projectName.takeIf { t.projectId.isNotBlank() && it.isNotBlank() },
        ).joinToString(" · ")
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                append(t.title)
                if (meta.isNotBlank()) {
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = mode.meta, fontSize = ty.label.fontSize))
                    append("  $meta")
                    pop()
                }
            },
            style = ty.body,
            color = ru.zf.pravka.ui.Ink.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val other = t.createdBy.isNotBlank() && t.createdBy != t.ownerId
        val who = if (other) snap.people.values.firstOrNull { it.userId == t.createdBy } else null
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .combinedClickable(onClick = { Feedback.toast(context, where) }, onLongClick = { Feedback.toast(context, where) }),
            contentAlignment = Alignment.Center,
        ) {
            if (who != null) Avatar(who, 22.dp) else SourceMark(t.source)
        }
    }
}

/**
 * Вопрос «Подскажи» (`askItem` веба): сверху — значок и пачка (встреча —
 * ссылкой), П-номер (его же видит Claude: «П1 поставь, срок пятница») и что
 * предлагается, вопрос цветом «внимание», подсказка, цитата одной строкой,
 * «похоже, это уже есть» с «Это оно»; «Поставить» · «Поправить» · «Не надо».
 */
@Composable
internal fun AskRow(
    sg: Dela.Suggestion,
    n: Int,
    snap: Dela.Snapshot,
    me: String,
    today: String,
    onAccept: () -> Unit,
    onEdit: () -> Unit,
    onReject: () -> Unit,
    onOpen: (Dela.Task) -> Unit,
    onMerge: (Dela.Task) -> Unit,
    onAcceptSet: (org.json.JSONObject) -> Unit = { onAccept() },
) {
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val txt = DelaViews.sugText(sg, snap, today)
    val q = DelaViews.askWhy(sg, snap)
    // Вопрос разборщика Круга (`askItem` веба): предложенные направления горят, тап — поправить; «Да» — выбранное.
    // С деревом (13.10.2026) разборщик предлагает и цель (`why_id`, путь — `goal_path` в подсказке строкой ниже).
    val krugDirs = DelaKrug.directions()
    val proposed = remember(sg.id) { Dela.run { sg.payloadObj().strings("directions") } }
    val proposedGoal = remember(sg.id) { Dela.run { sg.payloadObj().str("why_id") } }
    var chosen by remember(sg.id) { mutableStateOf(proposed) }
    val krugAsk = sg.source == "krug" && DelaKrug.on(snap, krugDirs)
    val twin = if (sg.source == "krug") null else DelaViews.twinOf(sg, snap, me)
    val link = sg.sourceRef.takeIf { it.startsWith("https://") }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = link != null) {
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
        ) {
            SourceMark(sg.source, 14.dp)
            Spacer(Modifier.width(6.dp))
            Text(sg.batchTitle.ifBlank { DelaViews.mark(sg.source).word } + if (link != null) " →" else "", style = ty.meta, color = mode.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                pushStyle(androidx.compose.ui.text.SpanStyle(color = mode.label, fontWeight = FontWeight.SemiBold))
                append("П$n  ")
                pop()
                append(txt.title)
            },
            style = ty.bodyStrong,
            color = ru.zf.pravka.ui.Ink.TextStrong,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (q != null) Text(q, style = ty.label, fontWeight = FontWeight.SemiBold, color = ru.zf.pravka.ui.Ink.Warn)
        if (txt.hint.isNotBlank()) Text(txt.hint, style = ty.label, color = mode.meta)
        if (txt.add.isNotBlank()) Text("+ ${txt.add}", style = ty.label, color = mode.label)
        if (sg.quote.isNotBlank() && sg.quote != txt.title) {
            Text(
                "«${sg.quote}»",
                style = ty.label.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                color = ru.zf.pravka.ui.Ink.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (krugAsk) {
            ChipRow {
                for (d in krugDirs) PaperChip(d.label, selected = d.key in chosen, onClick = {
                    chosen = if (d.key in chosen) chosen - d.key else chosen + d.key
                })
            }
        }
        if (twin != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "похоже, это уже есть: «${twin.title}»",
                    style = ty.label,
                    color = mode.label,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable { onOpen(twin) },
                )
                PaperTextButton("Это оно", onClick = { onMerge(twin) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            PaperTextButton("Не надо", onClick = onReject)
            if (sg.kind == "create") PaperTextButton("Поправить", onClick = onEdit)
            // «Да» — как есть (и без чипов, если предложена цель); поправил направление — значит, цель не та:
            // дело ляжет прямо на направление (`why_id: null`, как `askItem` веба).
            if (krugAsk) PaperButton("Да", icon = Glyphs.Check, primary = true, enabled = chosen.isNotEmpty() || proposedGoal.isNotBlank(), onClick = {
                if (chosen == proposed) onAccept()
                else onAcceptSet(org.json.JSONObject().put("directions", org.json.JSONArray(chosen)).put("why_id", org.json.JSONObject.NULL))
            })
            else PaperButton(DelaViews.askYes(sg.kind), icon = Glyphs.Check, primary = true, onClick = onAccept)
        }
    }
}

/** Сделанное само: что и что поменялось одной строкой, «Понятно» и «Вернуть», справа — значок источника. */
@Composable
private fun AutoDoneRow(sg: Dela.Suggestion, t: Dela.Task?, today: String, onReopen: (Dela.Task) -> Unit, onSeen: (() -> Unit)?) {
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text((if (sg.kind == "close") "Закрыто: " else "Уточнено: ") + (t?.title ?: "дело не видно"), style = ty.body, color = ru.zf.pravka.ui.Ink.Text)
            if (sg.kind == "update") {
                val words = DelaViews.autoWords(sg, today)
                if (words.isNotBlank()) Text(words, style = ty.label, color = mode.meta)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onSeen != null) PaperTextButton("Понятно", onClick = onSeen)
                // «Вернуть» — когда есть что вернуть (`Dela.autoUndoOps`): закрытое ещё закрыто, у уточнения — как было или комментарий.
                when {
                    t != null && Dela.autoUndoOps(sg, t, seen = false) != null -> PaperTextButton("Вернуть", icon = Glyphs.Undo, onClick = { onReopen(t) })
                    t != null && sg.kind == "close" && t.open -> Text("вернул в работу", style = ty.label, color = mode.meta)
                }
            }
        }
        SourceMark(sg.source)
    }
}

/** Переключатели над списком и куда ведут его ссылки — как тулбар и ссылки веба. */
private data class ListTools(
    val showDone: Boolean,
    val onShowDone: () -> Unit,
    /** Выбрана группировка списка [String] (`S.groups` веба). */
    val onGroup: (String, DelaViews.By) -> Unit,
    val onReschedule: (List<Dela.Task>) -> Unit,
    /** Заголовок группы — ссылка: проект ("" — «Входящие») или человек. */
    val onProject: (String) -> Unit,
    val onPerson: (String) -> Unit,
    val onAdd: ((String) -> Unit)? = null,
    val addLabel: String = "Новое дело…",
    /** Тап по уровню группировки: ключ экрана, номер уровня, уровни, чего не предлагать. */
    val onLevel: (String, Int, List<DelaGroups.Level>, Set<DelaGroups.Level>) -> Unit = { _, _, _, _ -> },
    /** «Сохранить фильтр» — что показано. */
    val onSaveFilter: (DelaGroups.Scope) -> Unit = {},
    /** «Только одно»: выбрать ветку (null — показать все) и открыть список с поиском. */
    val onPick: (String, DelaGroups.Pick?) -> Unit = { _, _ -> },
    val onPickOpen: (String, List<DelaGroups.Branch>, DelaGroups.Level) -> Unit = { _, _, _ -> },
)

/**
 * Экран группами — тело любого раздела и страницы, как у веба: сверху
 * переключатели («Только мяч у меня», «И без даты», «Сделанные», группировка)
 * и поле нового дела, ниже — группы карточками с числом; у просроченного —
 * «Перенести все», у проекта и человека — переход к ним. [extra] — своё
 * наверху (сделки проекта, строки человека).
 */
private fun LazyListScope.screenBody(
    sc: DelaScreen,
    key: String,
    actions: DelaActions,
    tools: ListTools,
    onProjectPage: Boolean = false,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val hasTools = sc.done || sc.groupKey != null || sc.levelKey != null || (sc.add && tools.onAdd != null) || extra != null
    if (hasTools) {
        item(key = "$key:tools") {
            PaperCard {
                extra?.invoke(this)
                // Уровни (`levelsBar` веба): «Группировать: проекты › группы › время»; тап — выбрать
                // уровень или «—» (убирает его и всё после). Следующий пустой — «＋».
                val lk = sc.levelKey
                if (lk != null) {
                    ChipRow {
                        Text("Группировать", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically))
                        for (i in 0 until minOf(DelaGroups.MAX_LEVELS, sc.levels.size + 1)) {
                            val lv = sc.levels.getOrNull(i)
                            PaperChip(
                                (if (i > 0) "› " else "") + (lv?.title ?: if (i == 0) "без групп" else "＋"),
                                selected = lv != null,
                                onClick = { tools.onLevel(lk, i, sc.levels, sc.levelOmit) },
                            )
                        }
                        val save = sc.saveScope
                        if (save != null) PaperChip("Сохранить фильтр", selected = false, onClick = { tools.onSaveFilter(save) })
                    }
                    // «Все люди ▾» / «Только: Наташа» и крестик «показать все» (`pickBar` веба).
                    val first = sc.levels.firstOrNull()
                    if (first != null) {
                        ChipRow {
                            val p = sc.pick
                            PaperChip(if (p != null) "Только: ${p.title}" else first.allOf + " ▾", selected = p != null,
                                onClick = { tools.onPickOpen(lk, sc.branches, first) })
                            if (p != null) PaperChip("✕ показать все", selected = false, onClick = { tools.onPick(lk, null) })
                        }
                    }
                }
                if (sc.done || sc.groupKey != null) {
                    ChipRow {
                        if (sc.done) PaperChip("Сделанные", selected = tools.showDone, onClick = tools.onShowDone)
                        val gk = sc.groupKey
                        if (gk != null) for (b in sc.groupOptions) PaperChip(b.title, selected = b == sc.by, onClick = { tools.onGroup(gk, b) })
                    }
                }
                val add = tools.onAdd
                if (sc.add && add != null) QuickAddRow(tools.addLabel, add)
            }
        }
    }
    if (sc.hint.isNotBlank()) {
        item(key = "$key:hint") { Box(Modifier.padding(start = 4.dp)) { PaperHint(sc.hint) } }
    }
    val nodes = sc.nodes
    if (nodes != null) {
        val also = sc.levels.mapNotNull { DelaViews.byOf(it) }.toSet()
        for (n in nodes) {
            item(key = "$key:n:${n.key}") {
                // «Просрочено» сверху «Всех дел» (13.10.2026): цветом беды и «Перенести все», строки — со всеми
                // подписями: уровни его не называют.
                val lateTop = n.key == LATE_NODE
                PaperCard(
                    label = n.title.takeIf { it.isNotBlank() }?.let { "$it · ${n.items.size}" },
                    labelColor = if (n.late) (if (Look.v2) Kv.bad else ru.zf.pravka.ui.LocalMode.current.value) else null,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val est = n.items.filter { it.open }.sumOf { it.estimateMin }
                            if (est > 0 && !lateTop) Text(
                                ru.zf.pravka.core.Fmt.dur(est),
                                style = ru.zf.pravka.ui.LocalPravkaType.current.meta,
                                color = ru.zf.pravka.ui.LocalMode.current.label,
                                modifier = Modifier.padding(end = 4.dp),
                            )
                            val pid = n.projectId
                            if (!pid.isNullOrBlank() && !onProjectPage) GlyphButton(Glyphs.Forward, "проект целиком", onClick = { tools.onProject(pid) }, size = 30.dp)
                            val hid = n.personId
                            if (hid != null) GlyphButton(Glyphs.Forward, "всё по человеку", onClick = { tools.onPerson(hid) }, size = 30.dp)
                        }
                    },
                ) {
                    if (n.sub.isNotBlank()) PaperHint(n.sub)
                    // «Перенести все» — строкой под подписью, а не в заголовке: строка заголовка панели v2 низкая
                    // (24 dp), и кнопка в ней сжимается в черту.
                    if (lateTop && n.items.size > 1) Row {
                        Spacer(Modifier.weight(1f))
                        PaperTextButton("Перенести все", icon = Glyphs.Calendar, onClick = { tools.onReschedule(n.items) })
                    }
                    NodeRows(n, actions, if (lateTop) emptySet() else also, onProjectPage, sc.indent)
                }
            }
        }
    }
    for (g in sc.groups) {
        item(key = "$key:g:${g.key}") {
            PaperCard(
                label = g.title.takeIf { it.isNotBlank() }?.let { "$it · ${g.items.size}" },
                // Просроченное — без красного (DESIGN §11.7): тревогу несут кольцо и «просрочено N дн».
                labelColor = if (g.late) ru.zf.pravka.ui.LocalMode.current.value else null,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Сумма оценок открытых дел группы — «1 ч 40 м» справа (`screens/06`).
                        val est = g.items.filter { it.open }.sumOf { it.estimateMin }
                        if (est > 0 && !(g.late && g.items.size > 1)) {
                            Text(
                                ru.zf.pravka.core.Fmt.dur(est),
                                style = ru.zf.pravka.ui.LocalPravkaType.current.meta,
                                color = ru.zf.pravka.ui.LocalMode.current.label,
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                        if (g.late && g.items.size > 1) PaperTextButton("Перенести все", onClick = { tools.onReschedule(g.items) })
                        val pick = actions.pickProject
                        if (g.key == LOOSE_GROUP && g.items.size > 1 && pick != null) PaperTextButton("Все в проект…", onClick = { pick(g.items) })
                        val pid = g.projectId
                        val hid = g.personId
                        if (!pid.isNullOrBlank() && !onProjectPage) GlyphButton(Glyphs.Forward, "проект целиком", onClick = { tools.onProject(pid) }, size = 30.dp)
                        if (hid != null) GlyphButton(Glyphs.Forward, "всё по человеку", onClick = { tools.onPerson(hid) }, size = 30.dp)
                    }
                },
            ) {
                if (g.sub.isNotBlank()) PaperHint(g.sub)
                TaskRows(g.items, actions, by = sc.by, onProjectPage = onProjectPage)
            }
        }
    }
    if (sc.projects.isNotEmpty()) {
        item(key = "$key:nostep") {
            PaperCard(label = "Проекты в работе без моего следующего шага · ${sc.projects.size}") {
                sc.projects.forEachIndexed { i, p ->
                    if (i > 0) RowRule()
                    ProjectLine(p.name, 0, false) { tools.onProject(p.id) }
                }
            }
        }
    }
    if (sc.groups.isEmpty() && sc.nodes.isNullOrEmpty() && sc.projects.isEmpty() && sc.empty.isNotBlank()) {
        item(key = "$key:empty") { Box(Modifier.padding(start = 4.dp)) { PaperHint(sc.empty) } }
    }
}

/**
 * Шапка раздела — как `head()` веба: «‹» на страницах, ☰ — боковая панель (только «Вид 4.0»: в v2 разделы — в
 * боковине), заголовок, подзаголовок, тревога красным.
 */
@Composable
private fun DelaHead(sc: DelaScreen, sphere: String, onBack: (() -> Unit)?, onMenu: (() -> Unit)?) {
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) GlyphButton(Glyphs.Back, "назад", onClick = onBack)
        if (onMenu != null) GlyphButton(Glyphs.ListLines, "разделы, CRM, проекты и люди — как боковая панель веба", onClick = onMenu)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(sc.title, style = MaterialTheme.typography.titleMedium, color = c.onSurface)
            val sub = listOf(sc.sub, sphere).filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            if (sc.alert.isNotBlank()) Text(sc.alert, style = MaterialTheme.typography.bodySmall, color = c.error)
        }
    }
}

/**
 * ☰ — боковая панель веба листом: сфера, разделы с числами, CRM, избранное,
 * проекты по видам (Клиенты, Внутреннее, Личное) с числом открытых и
 * просрочкой красным, люди с открытыми делами и свёрнутый архив.
 */
@Composable
private fun DelaNavSheet(
    visible: Boolean,
    nav: String,
    sphere: String,
    counts: () -> DelaViews.Counts,
    /** Фильтры с числом дел — первым разделом (как на полке веба). */
    filters: () -> List<Pair<DelaGroups.Filter, Int>> = { emptyList() },
    crmOn: Boolean,
    /** Круг жизни включён — раздел «Круг жизни» в списке. */
    krugOn: Boolean = false,
    /** Сервер знает дерево целей — раздел «Дерево жизни». */
    treeOn: Boolean = false,
    projects: () -> Dela.ProjectsNav,
    onSphere: (String) -> Unit,
    onNav: (String) -> Unit,
    onProject: (String) -> Unit,
    onPerson: (String) -> Unit,
    onDismiss: () -> Unit,
    clientOpen: Set<String> = emptySet(),
    hereClient: String = "",
    hereDeal: String = "",
    clientDeals: (String) -> List<ru.zf.pravka.core.DelaCrm.NavDeal> = { emptyList() },
    onClientOpen: (Collection<String>, Boolean) -> Unit = { _, _ -> },
    onDeal: (String) -> Unit = {},
) {
    // Сбоку, с отступами от краёв, а не листом во весь экран снизу (баг №1:
    // «чтобы сбоку оно вылезало… я его крутил, выбирал и дальше оно выезжало
    // обратно»).
    ru.zf.pravka.ui.SideSheet(visible = visible, onDismiss = onDismiss, title = "Дела", icon = Glyphs.Delo) {
        // Строки без своего цвета (клиенты, заголовки групп) — светлыми: иначе на тёмном листе их не видно.
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        val counts = counts()
        val projects = projects()
        Segments(
            options = SPHERES.map { it.second },
            selected = SPHERES.indexOfFirst { it.first == sphere }.coerceAtLeast(0),
            onSelect = { onSphere(SPHERES[it].first) },
        )
        val fl = filters()
        if (fl.isNotEmpty()) {
            NavHead("Фильтры")
            for ((f, n) in fl) NavLine(f.name, n, selected = nav == FILTER_NAV + f.id) { onNav(FILTER_NAV + f.id) }
            RowRule()
        }
        // «Сейчас» — отдельной строкой над остальными; число красным — где люди ждут («отбить» на сегодня)
        // и просроченное у «Всех дел».
        for (v in DelaViews.View.entries) {
            if ((v == DelaViews.View.KRUG && !krugOn) || (v == DelaViews.View.TREE && !treeOn)) continue
            NavLine(v.title, 0, selected = nav == v.key, countText = counts.label(v), hot = counts.bad(v) || (v == DelaViews.View.TODAY && counts.todayHot)) { onNav(v.key) }
            if (v == DelaViews.View.NOW) RowRule()
        }
        if (crmOn) {
            NavHead("CRM")
            for ((k, t) in CRM_NAV) NavLine(t, 0, selected = nav == k) { onNav(k) }
        }
        // Клиент — со своими проектами (сделками) под стрелкой (06.10.2026, docs/dela-phone-4.md;
        // владелец: «проекты должны группироваться под клиента»), как `projItem` веба.
        val row: @Composable (Dela.ProjectRow) -> Unit = { r ->
            val deals = if (r.project.kind == "client") clientDeals(r.project.id) else emptyList()
            if (deals.isEmpty()) ProjectLine(r.project.name, r.open, r.late) { onProject(r.project.id) }
            else ClientNavLine(r, deals, r.project.id in clientOpen || r.project.id == hereClient, hereDeal,
                onToggle = { onClientOpen(listOf(r.project.id), r.project.id !in clientOpen) }, onProject = onProject, onDeal = onDeal)
        }
        if (projects.favorites.isNotEmpty()) {
            NavHead("Избранное")
            for (r in projects.favorites) row(r)
        }
        for ((title, rows) in projects.groups) {
            var open by rememberSaveable("nav:$title") { mutableStateOf(true) }
            SummaryLine(title = title, summary = rows.size.toString(), expanded = open, onToggle = { open = !open }) {
                // «Раскрыть все / свернуть все» — проекты всех клиентов разом (`openAllBtn` веба).
                val withDeals = rows.filter { it.project.kind == "client" && clientDeals(it.project.id).isNotEmpty() }.map { it.project.id }
                if (withDeals.isNotEmpty()) {
                    val allOpen = withDeals.all { it in clientOpen }
                    Row {
                        Spacer(Modifier.weight(1f))
                        PaperTextButton(if (allOpen) "свернуть все" else "раскрыть все", onClick = { onClientOpen(withDeals, !allOpen) })
                    }
                }
                for (r in rows) row(r)
            }
        }
        // С CRM люди — разделом «Люди» по компаниям; без неё — с кем больше открытых дел.
        if (projects.people.isNotEmpty() && !crmOn) {
            var open by rememberSaveable("nav:people") { mutableStateOf(false) }
            SummaryLine(title = "Люди", summary = projects.people.size.toString(), expanded = open, onToggle = { open = !open }) {
                for ((p, n) in projects.people) ProjectLine(p.label, n, false) { onPerson(p.id) }
            }
        }
        if (projects.archived.isNotEmpty()) {
            var open by rememberSaveable("nav:arch") { mutableStateOf(false) }
            SummaryLine(title = "Архив", summary = projects.archived.size.toString(), expanded = open, onToggle = { open = !open }) {
                for (r in projects.archived) ProjectLine(r.project.name, r.open, r.late) { onProject(r.project.id) }
            }
        }
    }
    }
}

@Composable
private fun NavHead(title: String) {
    Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
}

/**
 * Раздел строкой листа: название и число (или [countText] — «3 из 5»);
 * выбранный — краской режима, [hot] (просрочено, пора напомнить) красит число.
 */
@Composable
private fun NavLine(title: String, count: Int, selected: Boolean, countText: String = "", hot: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 9.dp, horizontal = 2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) c.primary else c.onSurface, modifier = Modifier.weight(1f))
        val n = countText.ifBlank { if (count > 0) count.toString() else "" }
        if (n.isNotBlank()) {
            Text(n, style = MaterialTheme.typography.bodyMedium, fontWeight = if (hot) FontWeight.SemiBold else FontWeight.Normal,
                color = if (hot) c.error else c.onSurfaceVariant)
        }
    }
}

/**
 * «Перенести все» — как `reschedulePop` веба: Сегодня, Завтра, Понедельник,
 * Через неделю, Через месяц, Без даты или своя дата.
 */
@Composable
private fun RescheduleSheet(count: Int, today: LocalDate, onDismiss: () -> Unit, onPick: (String, String) -> Unit) {
    val monday = today.plusDays(((1 - today.dayOfWeek.value + 7) % 7).toLong().let { if (it == 0L) 7L else it })
    var own by remember { mutableStateOf("") }
    PaperSheet(onDismiss = onDismiss, title = "Перенести все", icon = Glyphs.Calendar, subtitle = plural(count, "дело", "дела", "дел")) {
        val iso = today.toString()
        for ((label, d, what) in listOf(
            Triple("Сегодня", iso, "на сегодня"),
            Triple("Завтра", today.plusDays(1).toString(), "на завтра"),
            Triple("Понедельник · " + DelaViews.ddmm(monday.toString(), iso), monday.toString(), "на понедельник"),
            Triple("Через неделю · " + DelaViews.ddmm(today.plusDays(7).toString(), iso), today.plusDays(7).toString(), "через неделю"),
            Triple("Через месяц · " + DelaViews.ddmm(today.plusDays(30).toString(), iso), today.plusDays(30).toString(), "через месяц"),
            Triple("Без даты", "", "без даты"),
        )) {
            NavLine(label, 0, selected = false) { onPick(d, what) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PaperField(value = own, onValueChange = { own = it }, label = "Своя дата ГГГГ-ММ-ДД", modifier = Modifier.weight(1f))
            GlyphButton(Glyphs.Check, "перенести на эту дату", enabled = Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(own), onClick = {
                onPick(own, DelaViews.ddmm(own, iso))
            })
        }
    }
}

/**
 * Клиент в ☰ с проектами под стрелкой: стрелка раскрывает, тап по имени —
 * страница клиента; проект — точка цвета стадии, имя без «Клиент: », число
 * открытых дел (красное при просрочке), тап — страница сделки.
 */
@Composable
private fun ClientNavLine(
    r: Dela.ProjectRow,
    deals: List<ru.zf.pravka.core.DelaCrm.NavDeal>,
    open: Boolean,
    hereDeal: String,
    onToggle: () -> Unit,
    onProject: (String) -> Unit,
    onDeal: (String) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlyphButton(
            if (open) Glyphs.ChevronUp else Glyphs.ChevronDown,
            if (open) "свернуть проекты" else "проекты клиента: ${deals.size}",
            onClick = onToggle,
            size = 30.dp,
        )
        Box(Modifier.weight(1f)) { ProjectLine(r.project.name, r.open, r.late) { onProject(r.project.id) } }
    }
    if (open) {
        for (d in deals) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onDeal(d.deal.id) }
                    .padding(start = 34.dp, end = 2.dp, top = 7.dp, bottom = 7.dp),
            ) {
                StageDot(d.deal.stage)
                Spacer(Modifier.width(8.dp))
                Text(
                    d.short,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (d.deal.id == hereDeal) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (d.deal.id == hereDeal) c.primary else c.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (d.open > 0) {
                    Text(d.open.toString(), style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (d.late) FontWeight.SemiBold else FontWeight.Normal, color = if (d.late) c.error else c.onSurfaceVariant)
                }
            }
        }
    }
}

/** Проект строкой: название и число открытых дел справа; просрочка красит число. */
@Composable
private fun ProjectLine(title: String, open: Int, late: Boolean, hint: String? = null, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 9.dp, horizontal = 2.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        if (open > 0) {
            Text(
                open.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (late) FontWeight.SemiBold else FontWeight.Normal,
                color = if (late) c.error else c.onSurfaceVariant,
            )
        }
    }
}

/** Поле «новое дело» на странице: набрал — Enter или ✈ — дело с проектом или человеком страницы. */
@Composable
internal fun QuickAddRow(label: String, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        PaperField(
            value = text,
            onValueChange = { text = it },
            label = label,
            modifier = Modifier.weight(1f),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = {
                if (text.isNotBlank()) { onAdd(text.trim()); text = "" }
            }),
        )
        GlyphButton(Glyphs.Send, "завести дело", enabled = text.isNotBlank(), onClick = { onAdd(text.trim()); text = "" })
    }
}

private fun stageWord(stage: String): String = ru.zf.pravka.core.DelaCrm.STAGE[stage] ?: stage

// ---------------------------------------------------------------- строки

/**
 * Кольцо-галочка по группе (как в вебе): «отбить» — светлое (`value`),
 * «мониторить» — пунктиром, повестка — точками, остальное — краской режима.
 * [strong] — просрочено: толще и светлым, без красного.
 */
private fun Modifier.groupRing(
    g: DelaGroups.Grp,
    agenda: Boolean,
    width: androidx.compose.ui.unit.Dp,
    bright: androidx.compose.ui.graphics.Color,
    tint: androidx.compose.ui.graphics.Color,
    strong: Boolean,
    /** Вид v2: квадрат с радиусом 5 (`.chk` веба) — кружков в v2 нет. */
    square: Boolean = false,
): Modifier = this.then(Modifier.drawBehind {
    val w = width.toPx()
    val effect = when {
        g == DelaGroups.Grp.CHECK -> androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
        agenda -> androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 3.dp.toPx()))
        else -> null
    }
    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = w, pathEffect = effect,
        cap = if (agenda) androidx.compose.ui.graphics.StrokeCap.Round else androidx.compose.ui.graphics.StrokeCap.Butt)
    val color = if (strong || g == DelaGroups.Grp.OWED) bright else tint
    if (square) {
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
            size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()),
            style = stroke,
        )
    } else {
        drawCircle(color = color, radius = size.minDimension / 2 - w / 2, style = stroke)
    }
})

/**
 * Ветка уровней внутри карточки (`nested` веба): последний уровень — строки,
 * глубже — подзаголовок с числом и его ветки.
 */
@Composable
private fun NodeRows(n: DelaGroups.Node, actions: DelaActions, also: Set<DelaViews.By>, onProjectPage: Boolean, indent: Map<String, Int> = emptyMap()) {
    if (n.leaf) {
        TaskRows(n.items, actions, by = n.level?.let { DelaViews.byOf(it) }, onProjectPage = onProjectPage, also = also, indent = indent)
        return
    }
    val mode = ru.zf.pravka.ui.LocalMode.current
    n.children.forEachIndexed { i, c ->
        Text(
            "${c.title} · ${c.items.size}",
            style = if (c.depth == 1) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
            color = if (c.late) mode.value else mode.label,
            modifier = Modifier.padding(top = if (i > 0) 10.dp else 2.dp, start = if (c.depth > 1) 10.dp else 0.dp),
        )
        if (c.sub.isNotBlank() && c.grp != null) Text(c.sub, style = MaterialTheme.typography.bodySmall, color = mode.meta,
            modifier = Modifier.padding(start = if (c.depth > 1) 10.dp else 0.dp))
        Box(Modifier.padding(start = if (c.depth > 1) 10.dp else 0.dp)) { Column { NodeRows(c, actions, also, onProjectPage) } }
    }
}

/**
 * Дела строками. [by] — группировка списка: то, что уже в заголовке группы
 * (срок, проект, человек, мяч, сделка), в строке не повторяется — как в вебе.
 */
@Composable
internal fun TaskRows(
    list: List<Dela.Task>,
    actions: DelaActions,
    today: String = LocalDate.now().toString(),
    by: DelaViews.By? = null,
    onProjectPage: Boolean = false,
    /** Подписи от места, где строка стоит («Новое»: что потом сказала автоматика), — строками под делом. */
    extra: (Dela.Task) -> List<String> = { emptyList() },
    /** Остальные уровни группировки: названное заголовком строка не повторяет. */
    also: Set<DelaViews.By> = emptySet(),
    /** Круг жизни: отступ шага к цели (дерево «Зачем»), по id дела. */
    indent: Map<String, Int> = emptyMap(),
    /** Дерево жизни: направление и «зачем» строка не повторяет — их называет само дерево (или путь в [note]). */
    inTree: Boolean = false,
    /** Путь в дереве курсивом под делом («Список» дерева жизни); "" — нет. */
    note: (Dela.Task) -> String = { "" },
    /** Долгое нажатие — «Куда повесить» (дерево жизни вместо перетаскивания веба); null — нет. */
    onLong: ((Dela.Task) -> Unit)? = null,
    /** Жирным — дело в «Сейчас»; в самом блоке «Сейчас» это шум — там false. */
    nowBold: Boolean = true,
) {
    list.forEachIndexed { i, t ->
        if (i > 0) RowRule()
        TaskRow(t, actions, today, by, onProjectPage, extra(t), also, indent[t.id] ?: 0, inTree, note(t), onLong?.let { f -> { f(t) } }, nowBold)
    }
}

/**
 * Дело строкой: кружок — закрыть одним касанием (как в Todoist), сама строка —
 * карточка, ▶ — запись в Засечке с id дела и проекта (микрофона у дела нет с
 * 09.10.2026: строка Claude одна — внизу). Под названием —
 * проект, у кого мяч, срок и оценка. Денег дела нет с 05.10.2026: владелец —
 * «тяжело смотреть, нагружает».
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun TaskRow(
    t: Dela.Task,
    actions: DelaActions,
    today: String,
    by: DelaViews.By? = null,
    onProjectPage: Boolean = false,
    extra: List<String> = emptyList(),
    also: Set<DelaViews.By> = emptySet(),
    /** Глубина шага под целью (дерево «Зачем» Круга жизни): отступ и «↳». */
    depth: Int = 0,
    /** Строка в дереве жизни: направление и «зачем» дерево называет само. */
    inTree: Boolean = false,
    /** Путь в дереве — курсивом под подписями. */
    note: String = "",
    /** Долгое нажатие — «Куда повесить»; null — обычная строка. */
    onLong: (() -> Unit)? = null,
    /** Жирным — дело в «Сейчас»; в самом блоке «Сейчас» — нет. */
    nowBold: Boolean = true,
) {
    // Правка 4.0 (DESIGN §11.7 TaskRow в Делах, `screens/06`): кольцо 22 в
    // зоне 44, «Кто: действие» до двух строк, вторая строка — подписи веба;
    // справа ▶ по 40. Просроченное — толще кольцо и «просрочено N дн» жирным,
    // без красного. Солнце «на сегодня» (10.10.2026, вместо молнии «Сейчас») —
    // слева; кольцо по группе: «отбить» — светлое, «мониторить» — пунктир,
    // повестка — точки. Жирным — только дело в «Сейчас» (оно у Засечки).
    // Вид v2 (холст, `.li` и `.chk`): квадрат 18 с радиусом 5 вместо кольца, «просрочено» —
    // цветом «внимание», выбранное справа дело — мягким акцентом (`.li.sel`).
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    val v2 = Look.v2
    val lateDays = if (t.open && t.dueDate.isNotBlank() && t.dueDate < today)
        runCatching { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(t.dueDate.take(10)), LocalDate.parse(today)).toInt() }.getOrDefault(0) else 0
    val picked = v2 && actions.selectedId == t.id
    Column(
        Modifier.fillMaxWidth().seen("t:" + t.id).padding(start = (minOf(depth, 6) * 16).dp)
            .then(if (picked) Modifier.clip(RoundedCornerShape(7.dp)).background(Kv.accentSoft) else Modifier),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(if (v2) 7.dp else 12.dp))
                .then(if (onLong != null) Modifier.combinedClickable(onClick = { actions.open(t) }, onLongClick = onLong) else Modifier.clickable { actions.open(t) })
                .padding(vertical = 4.dp),
        ) {
            if (t.open && !actions.compact) {
                // Солнце горит у дел на сегодня. Владелец дела — «я» для правила «Сегодня»: строка
                // показывает и чужие дела (страница клиента), и у них своё «сегодня».
                val on = DelaGroups.inToday(t, t.ownerId, today)
                Icon(
                    Glyphs.Sunny,
                    contentDescription = if (on) "Не сегодня — на завтра" else "На сегодня",
                    tint = if (v2) (if (on) Kv.accent else Kv.check) else if (on) mode.value else mode.meta.copy(alpha = 0.45f),
                    modifier = Modifier.size(30.dp).clip(if (v2) RoundedCornerShape(KvShape.Control) else CircleShape).clickable { actions.toggleNow(t) }.padding(6.dp),
                )
            }
            Box(
                Modifier.size(if (v2) 40.dp else 44.dp).clip(if (v2) RoundedCornerShape(KvShape.Control) else CircleShape).clickable { actions.done(t) },
                contentAlignment = Alignment.Center,
            ) {
                val box = if (v2) 18.dp else 22.dp
                val shape = if (v2) RoundedCornerShape(5.dp) else CircleShape
                Box(
                    Modifier
                        .size(box)
                        .clip(shape)
                        .then(if (!t.open) Modifier.background(mode.key) else Modifier)
                        .then(
                            if (!t.open && v2) Modifier
                            else Modifier.groupRing(
                                DelaGroups.grpOf(t), t.ball == Dela.AGENDA,
                                if (v2) (if (lateDays > 0) 2.dp else 1.5.dp) else if (lateDays > 0) 2.5.dp else 2.dp,
                                if (v2) (if (lateDays > 0) Kv.warn else Kv.muted) else mode.value,
                                if (v2) Kv.check else mode.tint,
                                strong = lateDays > 0,
                                square = v2,
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!t.open) Icon(Glyphs.Check, contentDescription = "закрыто", tint = if (v2) Kv.accentInk else ru.zf.pravka.ui.Ink.KeyIcon, modifier = Modifier.size(if (v2) 13.dp else 14.dp))
                }
            }
            Column(Modifier.weight(1f).padding(start = 2.dp)) {
                Text(
                    // В дереве жизни ступень видна отступом, стрелка у каждой строки была бы шумом.
                    (if (depth > 0 && !inTree) "↳ " else "") + t.title,
                    style = ty.bodyL.copy(fontWeight = if (nowBold && t.open && t.focusOn == today) FontWeight.SemiBold else ty.bodyL.fontWeight),
                    color = if (t.open) ru.zf.pravka.ui.Ink.Text else mode.meta,
                )
                val live = t.id == actions.running
                // «Олег ждёт 3 дн» — ярче остальных: люди ждут (`taskRow` веба, `value` полужирным).
                val wait = DelaGroups.wait(t, DelaViews.who(t, actions.snap), today, personGrouped = by == DelaViews.By.PERSON || DelaViews.By.PERSON in also)?.takeIf { it.strong }?.text
                val spentMs = actions.spent[t.id] ?: 0L
                // Просроченное: «просрочено N дн» уже впереди жирным — у срока остаётся дата, без второго «N дн. назад»
                // (как `taskRow` веба: «просрочено 2 дн · 11.10»).
                val lateDue = if (lateDays > 0) DelaViews.due(t, today)?.text else null
                val chips = DelaViews.chips(t, by, actions.snap, today, onProjectPage, also, me = actions.me, inTree = inTree)
                    .map { if (lateDue != null && it == lateDue) DelaViews.ddmm(t.dueDate, today) else it }
                val pick = actions.pickProject
                val loose = pick != null && t.open && DelaViews.NO_PROJECT in chips
                val meta = listOfNotNull(
                    ("идёт " + ru.zf.pravka.core.ZasechkaTasks.label(actions.runningMs)).takeIf { live },
                    chips.filter { !(loose && it == DelaViews.NO_PROJECT) }.joinToString(" · ").takeIf { it.isNotBlank() },
                    ("в ленте " + ru.zf.pravka.core.ZasechkaTasks.label(spentMs)).takeIf { spentMs > 0L },
                    when (t.status) { Dela.DONE -> "сделано"; Dela.CANCELLED -> "отменено"; else -> null },
                    // Номеров дел нигде нет (09.10.2026): только в адресах и командах Claude.
                    "⏳".takeIf { t.local },
                ).joinToString(" · ")
                // «Без проекта» — отдельной строкой-ссылкой; «просрочено» и
                // остальное — ОДНИМ текстом: тремя кусками в ряд перенос шёл
                // узкой колонкой справа (баг №10).
                if (loose && pick != null) {
                    Text(
                        DelaViews.NO_PROJECT,
                        style = ty.label,
                        fontWeight = FontWeight.SemiBold,
                        color = mode.label,
                        maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { pick(listOf(t)) }.padding(end = 6.dp),
                    )
                }
                if (lateDays > 0 || meta.isNotBlank()) {
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            if (lateDays > 0) {
                                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold, color = if (v2) Kv.warn else mode.value))
                                append("просрочено $lateDays дн")
                                pop()
                                if (meta.isNotBlank()) append(" · ")
                            }
                            val at = if (wait != null) meta.indexOf(wait) else -1
                            if (at < 0) append(meta) else {
                                append(meta.substring(0, at))
                                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold, color = mode.value))
                                append(wait)
                                pop()
                                append(meta.substring(at + wait!!.length))
                            }
                        },
                        style = ty.label.copy(fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (live) mode.value else mode.meta,
                    )
                }
                for (line in extra) {
                    Text(line, style = ty.label, color = mode.label)
                }
                if (note.isNotBlank()) Text(note, style = ty.label.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), color = ru.zf.pravka.ui.Ink.PlanText)
            }
            // ▶ (или стоп) — запись в Засечке; микрофон — команда Claude про это дело.
            if (actions.starting == t.id) {
                Text("…", style = ty.body, color = mode.tint, modifier = Modifier.size(40.dp).padding(10.dp))
            } else if (t.open && t.id == actions.running) {
                ru.zf.pravka.ui.StopKey(onClick = { actions.stop() }, description = "остановить в ленте")
            } else if (t.open && !actions.compact) {
                // v2: ▶ тише акцента — на холсте у строки только квадрат и слова; акцент — для главного.
                GlyphButton(Glyphs.Play, "начать в ленте", { actions.start(t) }, tint = if (v2) Kv.icon else mode.tint, size = 40.dp)
            }
        }
    }
}

internal fun delaDate(iso: String): String = runCatching {
    LocalDate.parse(iso.take(10)).format(DateTimeFormatter.ofPattern("d MMM", Locale("ru")))
}.getOrDefault(iso)

// ---------------------------------------------------------------- карточка

/**
 * Карточка дела: всё правится — название, заметки, проект и сделка, мяч и
 * человек, срок с временем и напоминание, оценка, «хочу сам», «сейчас», метки,
 * комментарии; дело закрывается, отменяется и возвращается. Сохранение — одна
 * операция `task.set` только с поменявшимися полями и тем, что телефон видел
 * до правки (`was`): если поле успели поменять другие, сервер скажет о споре.
 * Журнал правок — только с сервера, по кнопке. Денег дела в карточке нет с
 * 05.10.2026 (поле в данных осталось); микрофон — команда Claude про это дело.
 */
@Composable
internal fun DelaTaskSheet(
    app: PravkaApp,
    task: Dela.Task,
    snap: Dela.Snapshot,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (Dela.Task) -> Unit,
    onStatus: (String) -> Unit,
    onStart: () -> Unit,
    title: String? = null,
    saveText: String = "Сохранить",
    quote: String = "",
    /** Черновик Разноски: «убрать это дело» корзиной, без «Сейчас» (у черновика его нет). */
    onDrop: (() -> Unit)? = null,
    warn: String = "",
    /**
     * Строка Claude про это дело — та же, что внизу вкладки (`scope.open`): микрофона
     * у дела нет (поправки задания 10). null — новое дело, черновик, нет сервера.
     */
    say: (@Composable () -> Unit)? = null,
    /** Предложение автоматики: «на сегодня» в нём не ставится (как в вебе). */
    suggestion: Boolean = false,
    /**
     * «Место в дереве»: тап по цели — её карточка. Ответ — дело, как его поправили здесь
     * (чтобы правка не пропала при переходе), и куда перейти. null — ссылок нет.
     */
    onOpenTask: ((Dela.Task, Dela.Task) -> Unit)? = null,
    /** «Место в дереве»: тап по проекту (▣) — его страница. */
    onOpenProject: ((String) -> Unit)? = null,
) {
    var f by remember(task.id) { mutableStateOf(task) }
    var picking by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var history by remember { mutableStateOf<List<String>?>(null) }
    var historyNote by remember { mutableStateOf("") }
    // Откуда дело (`origin` у `/api/task`, 08.10.2026) — грузится вместе с карточкой; журнал — оттуда же, по кнопке.
    var card by remember(task.id) { mutableStateOf<ru.zf.pravka.data.DelaSync.Card?>(null) }
    val scope = app.appScope
    val today = LocalDate.now()
    val comments = snap.commentsOf(task.id)
    val existing = !isNew
    LaunchedEffect(task.id, existing) {
        if (existing && !task.local && app.delaSync.link.value != null) app.delaSync.card(task.id).onSuccess { card = it }
    }

    PaperSheet(
        onDismiss = onDismiss,
        title = title ?: if (isNew) "Новое дело" else "Дело",
        icon = Glyphs.Delo,
        subtitle = if (existing) listOfNotNull(
            task.createdAt.take(10).takeIf { it.isNotBlank() }?.let { "заведено " + delaDate(it) },
            sourceName(task.source),
        ).joinToString(" · ") else null,
        actions = {
            if (existing && task.open) GlyphButton(Glyphs.Play, "начать в ленте", onClick = onStart, tint = MaterialTheme.colorScheme.primary)
        },
        footer = {
            if (existing && task.open) {
                PaperIconButton(Glyphs.Delete, "отменить дело", onClick = { onStatus("task.cancel") }, tint = MaterialTheme.colorScheme.error)
                PaperButton("Закрыть", icon = Glyphs.Check, onClick = { onStatus("task.done") })
            } else if (existing) {
                PaperButton("Вернуть", icon = Glyphs.Undo, onClick = { onStatus("task.reopen") })
            } else if (onDrop != null) {
                PaperIconButton(Glyphs.Delete, "убрать это дело", onClick = onDrop, tint = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.weight(1f))
            PaperButton(saveText, primary = true, enabled = f.title.isNotBlank(), onClick = { onSave(f.copy(title = f.title.trim(), notes = f.notes.trim())) })
        },
    ) {
        if (say != null && existing) {
            say()
            RowRule()
        }
        if (quote.isNotBlank()) PaperHint("«${quote.take(300)}»")
        if (warn.isNotBlank()) PaperHint(warn, MaterialTheme.colorScheme.error)
        PaperField(value = f.title, onValueChange = { f = f.copy(title = it) }, label = "Кто: что сделать", singleLine = false)
        // Сразу под названием, как ряд действий карточки веба: молния «В «Сейчас»» (13.10.2026, `toggleNow` веба:
        // «Сейчас — то, что отметил») и солнце «На сегодня» / «На завтра» (`toggleToday` веба) — два разных дела:
        // отметка владельца и срок. В предложении и черновике их нет — как в вебе. Ложится при «Сохранить».
        if (onDrop == null && !suggestion && f.open) {
            val me = app.delaSync.link.value?.user ?: app.delaStore.me
            val iso = today.toString()
            val taken = DelaGroups.isNow(f, iso)
            val on = DelaGroups.inToday(f, me, iso)
            ChipRow {
                if (!f.goal && (me.isBlank() || f.ownerId.isBlank() || f.ownerId == me)) {
                    PaperChip("В «Сейчас»", selected = taken, icon = Glyphs.Bolt, onClick = {
                        // Шестое не встаёт (предел — и на сервере, `store._now_guard`): тост, отметка не ставится.
                        val (set, said) = DelaGroups.toggleNow(f, snap, me, iso)
                        if (set == null) Feedback.toast(app, said) else f = Dela.applyFields(f, set, iso, f.updatedAt)
                    })
                }
                PaperChip(if (on) "На завтра" else "На сегодня", selected = false, icon = Glyphs.Sunny, onClick = {
                    val (set, _) = DelaGroups.toggleToday(f, me, iso, today.plusDays(1).toString())
                    f = Dela.applyFields(f, set, iso, f.updatedAt)
                })
            }
            PaperHint(
                listOfNotNull(
                    if (taken) "в «Сейчас» — взял на сегодня" else null,
                    when {
                        on -> "срок сегодня"
                        DelaViews.isLate(f, iso) -> "просрочено — во «Всех делах»"
                        else -> null
                    },
                ).joinToString(" · ").ifBlank { "не на сегодня" },
            )
        }
        // Круг жизни (`krugProps` веба): только серверу, который знает Круг, и своему делу; у черновика и предложения нет.
        val krugDirs = DelaKrug.directions()
        val krugMe = app.delaSync.link.value?.user ?: app.delaStore.me
        val krugHere = DelaKrug.on(snap, krugDirs) && onDrop == null && !suggestion && (f.ownerId.isBlank() || f.ownerId == krugMe)
        val treeHere = krugHere && snap.treeOn
        // Место в дереве — сразу под названием (13.10.2026, `placeBlock` веба): зачем это дело и что будет, когда
        // сделаешь. Считается с правкой карточки поверх копии: новое «Зачем» видно до «Сохранить».
        if (krugHere && existing) {
            val live = snap.copy(tasks = snap.tasks + (f.id to f))
            PlaceBlock(
                f, DelaKrug.place(f, live, today.toString(), krugMe), krugDirs, snap,
                onTask = { to -> onOpenTask?.invoke(f, to) },
                onProject = { id -> onOpenProject?.invoke(id) },
            )
        }
        DelaViews.origin(card?.origin)?.let { OriginPlate(it) }
        PaperField(value = f.notes, onValueChange = { f = f.copy(notes = it) }, label = "Заметки", singleLine = false, maxLines = 6)

        // У цели проекта нет (сервер его снимает): её текст видит только хозяин.
        if (f.goal && treeHere) PaperHint("Проект: цель живёт без проекта — её видишь только ты")
        else PaperRow(
            title = f.projectId.let { id -> if (id.isBlank()) "Входящие (без проекта)" else snap.projects[id]?.name ?: f.projectName.ifBlank { "проект" } },
            hint = "проект",
            icon = Glyphs.ListLines,
            onClick = { picking = "project" },
        )
        if (!f.goal && f.projectId.isNotBlank() && snap.dealsOf(f.projectId).isNotEmpty()) {
            PaperRow(
                title = snap.deals[f.dealId]?.name ?: "без сделки",
                hint = "сделка",
                icon = Glyphs.Layers,
                onClick = { picking = "deal" },
            )
        }

        // «Группа» (10.10.2026, `grpSet` веба): отбить, запустить, мониторить. «Отбить» — только серверу,
        // который знает `owed` (старый отверг бы правку целиком); черновик Разноски отдаст его мячом «owed».
        val grp = DelaGroups.grpOf(f)
        PaperHint("Группа")
        ChipRow {
            for (g in DelaGroups.Grp.entries) {
                if (g == DelaGroups.Grp.OWED && !snap.groupsOn && onDrop == null && !task.owed) continue
                PaperChip(g.title, selected = grp == g, onClick = { f = DelaGroups.withGrp(f, g) })
            }
        }
        PaperRow(
            title = snap.people[f.personId]?.label ?: "без человека",
            hint = when (grp) {
                DelaGroups.Grp.OWED -> "Кто ждёт"
                DelaGroups.Grp.CHECK -> "От кого жду"
                else -> if (f.ball == Dela.AGENDA) "При встрече с" else "Человек"
            },
            icon = Glyphs.Phone,
            onClick = { picking = "person" },
        )
        if ((f.ball == Dela.WAITING || f.ball == Dela.AGENDA) && f.personId.isBlank()) {
            PaperHint("Без человека «мониторить» и «при встрече» некого ждать — выбери человека.", MaterialTheme.colorScheme.error)
        }
        // Кто просил — как в вебе («Его просьбы ко мне»): строкой, если есть.
        if (f.requestedBy.isNotBlank()) {
            PaperHint("просил: " + (snap.people[f.requestedBy]?.label ?: task.requestedByShort.ifBlank { "?" }))
        }

        DateLine("Срок", f.dueDate, today) { f = f.copy(dueDate = it, dueTime = if (it.isBlank()) "" else f.dueTime) }
        if (f.dueDate.isNotBlank()) TimeLine(f.dueTime) { f = f.copy(dueTime = it) }
        // «Напомнить ему» — только у «Мониторить»: день пнуть того, от кого жду; моё напоминание — ниже, в Telegram.
        if (f.ball == Dela.WAITING) DateLine("Напомнить ему", f.nudgeOn, today) { f = f.copy(nudgeOn = it) }
        val places by app.settings.autoPlacesFlow.collectAsState(initial = emptyMap())
        RemindLine(
            f,
            on = snap.remindOn,
            places = places.values.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { Dela.norm(it) },
        ) { f = it }

        PaperHint("Сколько займёт")
        ChipRow {
            for (m in listOf(5, 10, 30, 60, 120)) {
                PaperChip(if (m < 60) "$m мин" else "${m / 60} ч", selected = f.estimateMin == m, onClick = { f = f.copy(estimateMin = if (f.estimateMin == m) 0 else m) })
            }
        }
        PaperToggle("Хочу сам", checked = f.want, onCheckedChange = { f = f.copy(want = it) }, hint = "не потому, что попросили")
        PaperRow(
            title = if (f.labels.isEmpty()) "метки" else f.labels.joinToString(" ") { "@$it" },
            hint = "свободные контексты: звонок, …",
            icon = Glyphs.Tag,
            onClick = { picking = "labels" },
        )
        // Круг жизни (`krugProps` веба, 13.10.2026): «Зачем» — повесить на цель или поменять её (лист с путями и
        // поиском), «Сделать целью» и ритм практики, направления чипами (свои — метки), «новый шаг» — Enter.
        if (krugHere) {
            PaperHint("Зачем")
            Row(verticalAlignment = Alignment.CenterVertically) {
                PaperButton(if (f.whyId.isBlank()) "Повесить на цель" else "Поменять цель", icon = Glyphs.Layers, onClick = { picking = "why" })
                Spacer(Modifier.width(8.dp))
                if (treeHere) PaperChip(if (f.goal) "Цель" else "Сделать целью", selected = f.goal, onClick = {
                    when {
                        f.goal -> f = f.copy(goal = false, rhythm = "")
                        // Цель живёт без проекта: снять его — спросить (`krugProps` веба).
                        f.projectId.isNotBlank() -> picking = "goal"
                        else -> f = f.copy(goal = true)
                    }
                })
            }
            if (!existing && f.whyId.isNotBlank()) PaperHint("зачем: " + (snap.tasks[f.whyId]?.title ?: "цель"))
            if (treeHere && f.goal) {
                PaperField(
                    value = f.rhythm,
                    onValueChange = { f = f.copy(rhythm = it.take(60)) },
                    label = "Ритм практики: ежедневно, 2 раза в неделю…",
                    supporting = "цель с ритмом — практика: повторяется и не закрывается",
                )
            }
            PaperHint("Направления")
            ChipRow {
                for (d in krugDirs) PaperChip(d.label, selected = d.key in f.directions, onClick = {
                    f = when {
                        d.key in f.directions -> f.copy(directions = f.directions - d.key)
                        // Главное и две метки — больше сервер не примет (`krug.DIRS_MAX`).
                        f.directions.size >= DelaKrug.DIRS_MAX -> f.also { Feedback.toast(app, "Направлений у дела не больше трёх: главное и две метки") }
                        else -> f.copy(directions = f.directions + d.key)
                    }
                })
            }
            PaperHint(
                if (f.directions.isNotEmpty()) "главное — у корня цепочки «Зачем», остальные — метки (до двух)"
                else "своих нет — направление по дереву; отметь, если дело двигает ещё и другую сторону",
            )
            // Новый шаг — Enter: дело с этим «Зачем»; у цели — без проекта, у дела — в его проекте. Под ориентиром
            // (`no_steps`) шагов не заводят — сервер отказал бы, поля нет.
            if (existing && task.noSteps) PaperHint("Ориентир, а не задача: шагов под ним не заводят")
            else if (existing) QuickAddRow(if (task.goal) "Новый шаг к этой цели" else "Новый шаг к этому делу") { name ->
                val step = Dela.Task(id = Dela.newId(), title = name.trim(), projectId = if (task.goal) "" else task.projectId, whyId = task.id, source = "manual")
                if (step.title.isNotBlank()) scope.launch {
                    app.delaDo(listOf(Dela.createOp(step)))
                    Feedback.toast(app, "✓ шаг: ${step.title.take(40)}")
                }
            }
        }

        if (existing) {
            Spacer(Modifier.height(4.dp))
            PaperHint("Комментарии")
            for (cm in comments) {
                Text(
                    (snap.users[cm.authorId]?.name ?: cm.authorId).ifBlank { "—" } + " · " + delaDate(cm.createdAt) + (if (cm.local) " ⏳" else "") + "\n" + cm.text,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PaperField(value = comment, onValueChange = { comment = it }, label = "Комментарий", singleLine = false, maxLines = 4, modifier = Modifier.weight(1f))
                GlyphButton(Glyphs.Send, "добавить комментарий", enabled = comment.isNotBlank(), onClick = {
                    val text = comment.trim()
                    comment = ""
                    scope.launch { app.delaDo(listOf(Dela.commentOp(task.id, text))) }
                })
            }
            val h = history
            if (h == null) {
                PaperTextButton(historyNote.ifBlank { "Журнал правок (с сервера)" }, icon = Glyphs.Scroll, onClick = {
                    historyNote = "Читаю журнал…"
                    val have = card
                    if (have != null) history = have.history.map { historyLine(it, snap) }
                    else scope.launch {
                        app.delaSync.card(task.id)
                            .onSuccess { c -> history = c.history.map { historyLine(it, snap) } }
                            .onFailure { e -> historyNote = "Журнал не прочитался: ${e.message}" }
                    }
                })
            } else {
                PaperHint("Журнал")
                if (h.isEmpty()) PaperHint("Правок нет.")
                for (line in h) Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    when (picking) {
        "project" -> PickSheet(
            title = "Проект",
            none = "Входящие (без проекта)",
            items = snap.liveProjects().map { Triple(it.id, it.name, it.aliases.joinToString(", ")) },
            selected = f.projectId,
            onDismiss = { picking = "" },
        ) { id -> f = f.copy(projectId = id, dealId = ""); picking = "" }
        "deal" -> PickSheet(
            title = "Сделка",
            none = "без сделки",
            items = snap.dealsOf(f.projectId).map { Triple(it.id, it.name, stageWord(it.stage)) },
            selected = f.dealId,
            onDismiss = { picking = "" },
        ) { id -> f = f.copy(dealId = id); picking = "" }
        "person" -> PickSheet(
            title = "Человек",
            none = "без человека",
            items = snap.livePeople().map { Triple(it.id, it.label, (listOf(it.name).filter { n -> n != it.label } + it.aliases).joinToString(", ")) },
            selected = f.personId,
            onDismiss = { picking = "" },
        ) { id -> f = f.copy(personId = id); picking = "" }
        "labels" -> LabelsSheet(snap.labels, f.labels, onDismiss = { picking = "" }) { f = f.copy(labels = it) }
        // «Зачем» (`whyPop` веба): цели с путями и поиском, кроме самого дела и того, что ниже него (иначе круг);
        // «Новая цель: «…»» по набранному, «Убрать «Зачем»», «прямо на направление». Ложится при «Сохранить».
        "why" -> {
            val me = app.delaSync.link.value?.user ?: app.delaStore.me
            val dirs = DelaKrug.directions()
            GoalSheet(
                title = "Зачем",
                pool = DelaKrug.goalPool(snap, me, dirs, DelaKrug.below(snap, task.id)),
                dirs = dirs,
                current = f.whyId,
                onDismiss = { picking = "" },
                onGoal = { g -> f = f.copy(whyId = g.id); picking = "" },
                onDir = { k -> f = f.copy(whyId = "", directions = listOf(k)); picking = "" },
                onNone = { f = f.copy(whyId = ""); picking = "" },
                // Новая цель заводится сразу: с деревом — узлом без проекта в главном направлении дела (как
                // `why_title` сервера), без него — делом в том же проекте; это дело — её шаг при «Сохранить».
                onNew = { name ->
                    val tree = snap.treeOn
                    val main = DelaKrug.dirsOf(f, snap).firstOrNull()
                    val goal = Dela.Task(
                        id = Dela.newId(), title = name.trim(), ownerId = me, source = "manual",
                        goal = tree, projectId = if (tree) "" else f.projectId, directions = if (tree) listOfNotNull(main) else emptyList(),
                    )
                    if (goal.title.isNotBlank()) {
                        scope.launch { app.delaDo(listOf(Dela.createOp(goal, tree = tree))) }
                        f = f.copy(whyId = goal.id)
                    }
                    picking = ""
                },
            )
        }
        // «Сделать целью» у дела с проектом: цель живёт без проекта — спросить (`krugProps` веба).
        "goal" -> PaperSheet(
            onDismiss = { picking = "" },
            title = "Сделать целью?",
            icon = Glyphs.Layers,
            subtitle = "Цель живёт без проекта — её видишь только ты. Убрать дело из проекта и сделать целью?",
        ) {
            Row {
                Spacer(Modifier.weight(1f))
                PaperTextButton("Нет", onClick = { picking = "" })
                PaperButton("Сделать целью", primary = true, onClick = {
                    f = f.copy(goal = true, projectId = "", dealId = "")
                    picking = ""
                })
            }
        }
    }
}

/**
 * «Откуда» под названием дела (`originLine` веба): значок источника и
 * наговорка дословно — или пачка встречи и Telegram с цитатой и «встреча →».
 */
@Composable
private fun OriginPlate(o: DelaViews.Origin) {
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Box(Modifier.padding(top = 2.dp, end = 8.dp)) { SourceMark(if (o.dictation) "voice" else o.source, 14.dp) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(DelaViews.originTop(o, LocalDate.now().toString(), java.time.ZoneId.systemDefault()), style = ty.label, color = mode.label)
                if (o.url.isNotBlank()) {
                    Text(
                        " · встреча →",
                        style = ty.label,
                        color = mode.tint,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(o.url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        },
                    )
                }
            }
            val text = if (o.dictation) o.text else o.quote
            if (text.isNotBlank()) Text(text, style = ty.body, color = ru.zf.pravka.ui.Ink.TextSecondary)
        }
    }
}

private fun sourceName(source: String): String? = when (source) {
    "voice" -> "голосом"
    "web" -> "из веба"
    "bot" -> "из бота"
    "telegram" -> "из Telegram"
    "meeting" -> "со встречи"
    "mcp" -> "от Claude"
    "import" -> "перенесено"
    else -> null
}

/** Строка журнала: когда, кто, что поменялось — по-человечески, коротко. */
private fun historyLine(o: org.json.JSONObject, snap: Dela.Snapshot): String {
    val at = o.optString("at").let { a -> runCatching { a.take(16).replace('T', ' ') }.getOrDefault(a) }
    val who = o.optString("actor").takeIf { it.isNotBlank() && it != "null" }?.let { snap.users[it]?.name ?: it } ?: "?"
    val op = when (o.optString("op")) { "insert" -> "завёл"; "update" -> "поправил"; else -> o.optString("op") }
    val before = o.optJSONObject("before")
    val after = o.optJSONObject("after")
    val changed = if (before != null && after != null) {
        after.keys().asSequence().filter { k ->
            k !in setOf("updated_at", "rev", "seq", "search") && before.opt(k)?.toString() != after.opt(k)?.toString()
        }.take(4).joinToString(", ")
    } else ""
    return "$at · $who · $op" + if (changed.isNotBlank()) ": $changed" else ""
}

/** Дата строкой ГГГГ-ММ-ДД и быстрые чипы: сегодня, завтра, пятница, через неделю, убрать. */
@Composable
private fun DateLine(label: String, value: String, today: LocalDate, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    PaperField(
        value = text,
        onValueChange = { v ->
            text = v
            if (v.isBlank()) onChange("") else if (Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(v)) onChange(v)
        },
        label = "$label ГГГГ-ММ-ДД" + if (value.isNotBlank()) " · " + delaDate(value) else "",
    )
    ChipRow {
        val friday = today.plusDays(((5 - today.dayOfWeek.value + 7) % 7).toLong().let { if (it == 0L) 7L else it })
        for ((name, d) in listOf("сегодня" to today, "завтра" to today.plusDays(1), "пятница" to friday, "через неделю" to today.plusDays(7))) {
            PaperChip(name, selected = value == d.toString(), onClick = { onChange(d.toString()) })
        }
        if (value.isNotBlank()) PaperChip("убрать", selected = false, onClick = { onChange("") })
    }
}

/**
 * Напоминание в Telegram (06.10.2026, docs/dela.md «Напоминания»): время или
 * место — одно из двух. Быстрые чипы (через час, вечером, завтра утром), места
 * автопилота Засечки («приеду: дом») и своё время — днём и часами. Сервер, не
 * знающий напоминаний, получил бы `task.set` с незнакомым полем и отверг бы
 * правку целиком — поэтому без `remindOn` блока нет, только слова, почему.
 */
@Composable
private fun RemindLine(f: Dela.Task, on: Boolean, places: List<String>, onChange: (Dela.Task) -> Unit) {
    if (!on) {
        PaperHint("Напоминания в Telegram появятся, когда сервер Дел обновится; сказанное «напомни…» пока ложится в заметки")
        return
    }
    val zone = java.time.ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val at = DelaRemind.local(f.remindAt, zone)
    var custom by remember(f.id) { mutableStateOf(false) }
    PaperHint("Напомнить в Telegram")
    DelaRemind.state(f, today, zone).takeIf { it.isNotBlank() }?.let { PaperHint(it, MaterialTheme.colorScheme.primary) }
    ChipRow {
        for (q in DelaRemind.quick(java.time.ZonedDateTime.now(zone))) {
            PaperChip(q.label, selected = f.remindAt == q.iso, onClick = { onChange(f.copy(remindAt = q.iso, remindPlace = "", remindedAt = "")) })
        }
        for (p in places) {
            val chosen = f.remindAt.isBlank() && Dela.norm(f.remindPlace) == Dela.norm(p)
            PaperChip("приеду: $p", selected = chosen, onClick = { onChange(f.copy(remindAt = "", remindPlace = p, remindedAt = "")) })
        }
        PaperChip("своё время", selected = custom, onClick = { custom = !custom })
        if (f.remindAt.isNotBlank() || f.remindPlace.isNotBlank()) {
            PaperChip("убрать", selected = false, onClick = { onChange(f.copy(remindAt = "", remindPlace = "")) })
        }
    }
    if (custom) {
        val day = at?.toLocalDate()?.toString().orEmpty()
        val hm = at?.let { String.format(java.util.Locale.ROOT, "%02d:%02d", it.hour, it.minute) }.orEmpty()
        DateLine("Напомнить в день", day, today) { d ->
            val date = runCatching { LocalDate.parse(d) }.getOrNull()
            onChange(
                if (date == null) f.copy(remindAt = "")
                else f.copy(remindAt = DelaRemind.at(date, hm.ifBlank { DelaRemind.MORNING }, zone), remindPlace = "", remindedAt = "")
            )
        }
        if (at != null) TimeLine(hm) { t ->
            onChange(f.copy(remindAt = DelaRemind.at(at.toLocalDate(), t.ifBlank { DelaRemind.MORNING }, zone), remindPlace = "", remindedAt = ""))
        }
    }
}

/**
 * Время срока ЧЧ:ММ — правится, а не только видно (паритет с вебом). Сервер
 * хранит «10:00:00»; показываем и сравниваем первые пять знаков, пустое — без времени.
 */
@Composable
private fun TimeLine(value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value.take(5)) }
    val bad = text.isNotBlank() && Dela.normTime(text) == null
    PaperField(
        value = text,
        onValueChange = { v ->
            text = v
            if (v.isBlank()) onChange("") else Dela.normTime(v)?.let { if (it != value.take(5)) onChange(it) }
        },
        label = "Время ЧЧ:ММ",
        isError = bad,
        supporting = if (bad) "например 9:30 или 18:00" else null,
    )
    ChipRow {
        for (t in listOf("09:00", "12:00", "15:00", "18:00")) PaperChip(t, selected = value.take(5) == t, onClick = { onChange(t) })
        if (value.isNotBlank()) PaperChip("без времени", selected = false, onClick = { onChange("") })
    }
}

/** Выбор из справочника с поиском: тап выбирает и закрывает. */
/**
 * «Показать только» (`pickPop` веба; владелец: «набрать „Наташ“ — выпадут все
 * Наташи, и я выберу свою»): ветки первого уровня с поиском по имени, короткому
 * и другим именам, должности, компании; справа — число дел.
 */
@Composable
private fun BranchSheet(bp: BranchPick, onDismiss: () -> Unit, onPick: (DelaGroups.Branch?) -> Unit) {
    var q by remember { mutableStateOf("") }
    PaperSheet(onDismiss = onDismiss, title = "Показать только", icon = Glyphs.Search) {
        PaperField(value = q, onValueChange = { q = it }, label = "Найти…")
        val shown = DelaGroups.findBranches(bp.list, q)
        if (q.isBlank()) PickLine(bp.lv.allOf, "", false) { onPick(null) }
        for (b in shown.take(40)) PickLine(b.title, listOf(b.note, b.n.toString()).filter { it.isNotBlank() }.joinToString(" · "), false) { onPick(b) }
        if (q.isNotBlank() && shown.isEmpty()) PaperHint("Не нашёл — здесь таких нет")
    }
}

/** Имя фильтра: предложенное (`addFilter` веба) или своё. */
@Composable
private fun FilterNameSheet(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    PaperSheet(onDismiss = onDismiss, title = title, icon = Glyphs.ListLines) {
        PaperField(value = name, onValueChange = { name = it }, label = "Название фильтра")
        Row {
            Spacer(Modifier.weight(1f))
            PaperButton("Готово", primary = true, enabled = name.isNotBlank(), onClick = { onDone(name.trim()) })
        }
    }
}

@Composable
internal fun PickSheet(

    title: String,
    none: String,
    items: List<Triple<String, String, String>>,
    selected: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var q by remember { mutableStateOf("") }
    PaperSheet(onDismiss = onDismiss, title = title, icon = Glyphs.ListLines) {
        if (items.size > 8) PaperField(value = q, onValueChange = { q = it }, label = "Найти")
        PickLine(none, "", selected.isBlank()) { onPick("") }
        val n = Dela.norm(q)
        for ((id, name, more) in items) {
            if (n != null && Dela.norm("$name $more")?.contains(n) != true) continue
            PickLine(name, more, id == selected) { onPick(id) }
        }
    }
}

@Composable
internal fun PickLine(text: String, more: String, selected: Boolean, onClick: () -> Unit) {

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 9.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            if (more.isNotBlank()) Text(more, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(Glyphs.Check, contentDescription = "выбрано", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
    }
}

/** Метки: из справочника сервера отметками, новая — строкой (свободный контекст без пробелов). */
@Composable
private fun LabelsSheet(all: List<String>, chosen: List<String>, onDismiss: () -> Unit, onChange: (List<String>) -> Unit) {
    var set by remember { mutableStateOf(chosen.toSet()) }
    var fresh by remember { mutableStateOf("") }
    PaperSheet(
        onDismiss = { onChange(set.toList()); onDismiss() },
        title = "Метки",
        icon = Glyphs.Tag,
        footer = {
            Spacer(Modifier.weight(1f))
            PaperButton("Готово", icon = Glyphs.Check, primary = true, onClick = { onChange(set.toList()); onDismiss() })
        },
    ) {
        ChipRow {
            for (l in (all + set).distinct()) {
                PaperChip("@$l", selected = l in set, onClick = { set = if (l in set) set - l else set + l })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PaperField(value = fresh, onValueChange = { fresh = it.replace(Regex("[\\s,#@]"), "") }, label = "Новая метка", modifier = Modifier.weight(1f))
            GlyphButton(Glyphs.Plus, "добавить метку", enabled = fresh.isNotBlank(), onClick = {
                set = set + fresh.take(40)
                fresh = ""
            })
        }
    }
}

/**
 * «Отклонить» предложение: причина — материал для промптов, поэтому её
 * просят, но не требуют. Готовые причины — одним касанием; своя — словами
 * или голосом (микрофон — тот же движок, что у «Д»). Один лист на вкладку Дел
 * и «Новое» на «Сегодня» (доводка 5.0, 14.10.2026): причина — одна правда для
 * разбора, и спрашивать её двумя разными окнами незачем.
 */
@Composable
internal fun RejectSheet(suggestion: Dela.Suggestion, onDismiss: () -> Unit, onReject: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    PaperSheet(
        onDismiss = onDismiss,
        title = "Отклонить",
        icon = Glyphs.Close,
        subtitle = suggestion.title.take(80),
        actions = {
            GlyphButton(
                if (listening) Glyphs.Stop else Glyphs.Mic,
                if (listening) "готово" else "причина голосом",
                tint = MaterialTheme.colorScheme.primary,
                onClick = {
                    val service = PravkaAccessibilityService.instance
                    when {
                        service == null -> Feedback.toast(context, context.getString(R.string.toast_no_service))
                        listening -> if (!service.finishDelaReason(keep = true)) listening = false
                        else -> listening = service.listenForDelaReason { text ->
                            listening = false
                            reason = (reason.trim() + " " + text.trim()).trim()
                        }
                    }
                },
            )
        },
        footer = {
            Spacer(Modifier.weight(1f))
            PaperButton("Отклонить", icon = Glyphs.Close, primary = true, onClick = { onReject(reason.trim()) })
        },
    ) {
        if (suggestion.quote.isNotBlank()) PaperHint("«${suggestion.quote.take(300)}»")
        ChipRow {
            for (r in listOf("это идея, не дело", "уже сделано", "дубль", "не моё", "не сейчас")) {
                PaperChip(r, selected = reason == r, onClick = { onReject(r) })
            }
        }
        PaperField(
            value = reason,
            onValueChange = { reason = it },
            label = if (listening) "Слушаю — говори причину…" else "Своя причина",
            singleLine = false,
            maxLines = 4,
        )
    }
}


/** Синк коротко — «синк 18:40», «в очереди 2», «нет связи» (`screens/06`). */
private fun delaSyncShort(linked: Boolean, st: ru.zf.pravka.data.DelaSync.Status, queued: Int, syncedAt: Long, now: Long): String = when {
    !linked -> "не подключено"
    st.running -> "связываюсь…"
    queued > 0 -> "в очереди $queued"
    st.lastError.isNotBlank() && st.lastErrorAt >= st.lastOk -> "нет связи"
    syncedAt > 0 -> "синк " + ru.zf.pravka.core.Fmt.hm(syncedAt)
    else -> "синк"
}

/** «Новое из встреч и чатов · 4 ›» — стеклянная строка-вход в «Новое» (`screens/06`). */
@Composable
private fun NewLinkCard(count: Int, onClick: () -> Unit) {
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .glass(shape, mode.glass)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Glyphs.Groups, contentDescription = null, tint = ru.zf.pravka.ui.Ink.PlanText, modifier = Modifier.size(22.dp))
        Text("Новое из встреч и чатов", style = ty.bodyL, color = ru.zf.pravka.ui.Ink.Text, modifier = Modifier.weight(1f))
        ru.zf.pravka.ui.Segment(count.toString(), selected = true, onClick = onClick, height = 28.dp)
        Icon(Glyphs.Forward, contentDescription = null, tint = mode.label, modifier = Modifier.size(22.dp))
    }
}
