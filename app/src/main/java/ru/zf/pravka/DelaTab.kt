package ru.zf.pravka

import ru.zf.pravka.ui.glass

import ru.zf.pravka.ui.bottomFade

import androidx.compose.foundation.layout.imePadding

import androidx.compose.foundation.layout.navigationBarsPadding

import androidx.compose.foundation.layout.PaddingValues

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.rotate
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
import ru.zf.pravka.trigger.PravkaAccessibilityService
import ru.zf.pravka.trigger.finishDelaReason
import ru.zf.pravka.trigger.listenForDelaReason
import ru.zf.pravka.trigger.onRaznoskaTap
import ru.zf.pravka.trigger.onRaznoskaText
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.Glyphs
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
/** Вид сервера с последними наговорками (`store.view_dictations`); кэш — как у видов CRM. */
private const val DICTATIONS = "dictations"
private val CRM_NAV = listOf("crm" to "Воронка", "clients" to "Клиенты", "ties" to "Связи")
private val SPHERES = listOf("work" to "Работа", "home" to "Дом", "all" to "Всё")

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
    val upcoming: Boolean = false,
    /** «Неделя»: проекты в работе без моего следующего шага. */
    val projects: List<Dela.Project> = emptyList(),
    val empty: String = "",
    /** Подсказка над группами («Сейчас» пусто — как выбрать); показывается и при непустых группах. */
    val hint: String = "",
) {
    /** Дела экрана в порядке показа — то, что видит Claude. */
    val ids: List<String> get() = groups.flatMap { g -> g.items.map { it.id } }
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
 * «Новое» по тому, откуда пришло (`renderNew` веба): последние наговорки с их
 * делами ([dictations] null — сервер наговорок не держит), предложения встреч и
 * Telegram пачками, поставленное другими за три дня, дела без проекта, закрытое
 * само. Дело, показанное в наговорке, ниже не повторяется.
 */
private class NewParts(
    val dictations: List<DelaViews.Dictation>?,
    val batches: List<Dela.Batch>,
    val others: List<Dela.Task>,
    val loose: List<Dela.Task>,
    val autoClosed: List<Dela.Suggestion>,
) {
    /** Дела экрана в порядке показа — то, что видит Claude (предложений он здесь не решает). */
    val ids: List<String>
        get() = dictations.orEmpty().flatMap { it.tasks.map { t -> t.id } } + others.map { it.id } + loose.map { it.id } +
            autoClosed.mapNotNull { sg -> sg.taskId.takeIf { it.isNotBlank() } }

    val empty: Boolean
        get() = dictations.isNullOrEmpty() && batches.isEmpty() && others.isEmpty() && loose.isEmpty() && autoClosed.isEmpty()

    companion object {
        fun of(snap: Dela.Snapshot, me: String, sphere: String, now: Long, view: org.json.JSONObject?): NewParts {
            val dicts = if (snap.dictationsOn) DelaViews.dictations(view) else null
            val shown = dicts.orEmpty().flatMap { d -> d.tasks.map { it.id } }.toHashSet()
            val others = DelaViews.fromOthers(snap, me, sphere, now).filter { it.id !in shown }
            shown += others.map { it.id }
            return NewParts(
                dictations = dicts,
                batches = Dela.newBatches(snap, me),
                others = others,
                loose = DelaViews.noProject(snap, me, sphere).filter { it.id !in shown },
                autoClosed = Dela.autoClosed(snap, me, now),
            )
        }
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

    // Раздел — ключ веба: now, new, upcoming, waiting, week, all; CRM — crm, clients, ties.
    var nav by rememberSaveable { mutableStateOf(DelaViews.View.NOW.key) }
    var navOpen by remember { mutableStateOf(false) }
    var sphere by rememberSaveable { mutableStateOf("all") }
    // Переключатели веба: «Сделанные» у списков, «Только мяч у меня» и «И без даты» у «Предстоящего».
    var showDone by rememberSaveable { mutableStateOf(false) }
    var upMineOnly by rememberSaveable { mutableStateOf(false) }
    var upNoDate by rememberSaveable { mutableStateOf(false) }
    // «Перенести все» у просроченного — дела, которым выбирают новый срок.
    var rescheduling by remember { mutableStateOf<List<Dela.Task>?>(null) }
    // «без проекта» у строки и «Все в проект…» в «Новом» — дела, которым выбирают проект.
    var projectFor by remember { mutableStateOf<List<Dela.Task>?>(null) }
    // Наговорки в «Новом», раскрытые целиком (тап по тексту), — по ref.
    var dictOpen by remember { mutableStateOf(setOf<String>()) }
    val groupsPref by app.delaStore.groupsFlow.collectAsState()
    var draft by remember { mutableStateOf("") }
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
    var newTask by remember { mutableStateOf(false) }
    var editingSuggestion by remember { mutableStateOf<Dela.Suggestion?>(null) }
    var rejecting by remember { mutableStateOf<Dela.Suggestion?>(null) }
    var starting by remember { mutableStateOf("") }
    // Правка словами: одна команда за раз, строка над видом и её текст.
    val ask = remember { AskState() }
    var askOpen by rememberSaveable { mutableStateOf(false) }
    var askText by rememberSaveable { mutableStateOf("") }
    // Микрофон в строке дела: у какого дела открыто поле, что в нём и слушает ли.
    var rowAsk by remember { mutableStateOf("") }
    var rowAskText by remember { mutableStateOf("") }
    var rowListening by remember { mutableStateOf(false) }

    LaunchedEffect(link) {
        app.delaStore.load()
        // Открытие вкладки — хороший момент отдать очередь и взять новое.
        if (link != null) runCatching { app.delaSync.tick() }
    }

    val me = link?.user ?: app.delaStore.me
    val today = LocalDate.now().toString()
    val now = System.currentTimeMillis()
    val running = ribbon.firstOrNull { it.open }
    // CRM — владельцу и тем, кому открыты клиенты; деньги фирмы — владельцу и sees_money.
    val crmOn = Dela.crmOn(snap, me)
    val moneyOn = Dela.moneyOn(snap, me)
    // CRM отняли, а выбран её раздел — назад в «Сейчас», а не пустой экран.
    // Сохранённый ключ «Утра» или «Входящих» (до 06.10.2026) ведёт туда, где их дела теперь.
    LaunchedEffect(crmOn, nav) {
        if (!crmOn && CRM_NAV.any { it.first == nav }) nav = DelaViews.View.NOW.key
        else if (CRM_NAV.none { it.first == nav } && DelaViews.View.entries.none { it.key == nav }) nav = DelaViews.View.of(nav).key
    }
    // Раздел Дел (не CRM) — по ключу; у CRM своё тело.
    val view: DelaViews.View? = if (CRM_NAV.any { it.first == nav }) null else DelaViews.View.of(nav)
    val queuedOps = remember(queued) { queued.map { it.op } }
    val push: (DelaPage) -> Unit = { p -> if (pages.lastOrNull() != p) pages = pages + p }
    /** Перейти в раздел — как ссылка боковой панели веба: страницы и поиск закрываются. */
    val go: (String) -> Unit = { key ->
        nav = key
        pages = emptyList()
        query = ""
        searching = false
        navOpen = false
    }
    fun groupOf(list: String, def: DelaViews.By) = DelaViews.By.of(groupsPref[list].orEmpty(), def)
    // «Назад» листает страницы Дел (как история веба), пока они есть.
    androidx.activity.compose.BackHandler(enabled = pages.isNotEmpty()) { pages = pages.dropLast(1) }

    /**
     * Команда Claude: одна за раз. Получилось — итог листом поверх вкладки (и
     * [onDone] — поле очищается); нет — причина у той стороны, что спросила, а
     * текст остаётся. Ошибка у карточки, которую уже закрыли, — листом с текстом.
     */
    fun runAsk(key: String, text: String, scopeJson: org.json.JSONObject, onDone: () -> Unit = {}) {
        if (ask.running.isNotBlank()) {
            Feedback.toast(app, "Claude ещё правит прошлую команду")
            return
        }
        ask.running = key
        ask.errors = ask.errors - key
        scope.launch {
            val r = app.delaSync.ask(text, scopeJson)
            ask.running = ""
            r.onSuccess { res ->
                onDone()
                if (openTask != null && key == askTaskKey(openTask!!.id)) openTask = null
                ask.shown = AskShown(key, text, scopeJson, result = res)
            }.onFailure { e ->
                val why = e.message.orEmpty().ifBlank { "Claude не ответил" }
                ask.errors = ask.errors + (key to why)
                // Видно ли, где спросили: строка над видом открыта, карточка или поле у строки дела на месте.
                val seen = if (key == ASK_SCREEN) askOpen else openTask?.let { askTaskKey(it.id) } == key || askTaskKey(rowAsk) == key
                if (!seen) ask.shown = AskShown(key, text, scopeJson, error = why)
            }
        }
    }

    /** Команда про одно дело из его строки: уходит Claude, поле закрывается, когда он ответил. */
    fun rowSend(t: Dela.Task, text: String) {
        if (text.isBlank()) return
        rowAskText = text
        runAsk(askTaskKey(t.id), text, DelaAsk.taskScope(t.id)) { if (rowAsk == t.id) { rowAsk = ""; rowAskText = "" } }
    }

    /**
     * Микрофон под ▶ (05.10.2026, владелец: «под каждой кнопкой play нужно сделать
     * кнопку микрофончика… зачёркивать дела, уточнять, говорить, что это другое.
     * Точно так же, как в вебе»): открыть поле у дела и сразу слушать тем же
     * движком, что «Д»; сказанное уходит само. Второй тап — хватит слушать.
     */
    fun rowMic(t: Dela.Task) {
        val service = PravkaAccessibilityService.instance
        if (rowListening) {
            if (service?.finishDelaReason(keep = true) != true) rowListening = false
            return
        }
        if (rowAsk != t.id) {
            rowAsk = t.id
            rowAskText = ""
        }
        if (service == null) {
            Feedback.toast(app, app.getString(R.string.toast_no_service))
            return
        }
        if (ask.running == askTaskKey(t.id)) return
        rowListening = service.listenForDelaReason { said ->
            rowListening = false
            val full = (rowAskText.trim() + " " + said.trim()).trim()
            rowAskText = full
            if (full.isNotBlank() && rowAsk == t.id) rowSend(t, full)
        }
    }

    // На развороте дело открывается справа (`DelaTaskPane`), а не листом.
    val wideNow = ru.zf.pravka.ui.twoPane()
    var paneId by rememberSaveable { mutableStateOf<String?>(null) }
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
        // Молния — «Сейчас» на сегодня, не больше пяти (как в вебе): шестое не встаёт.
        toggleNow = { t ->
            val on = t.focusOn == today
            if (!on && !DelaViews.nowRoom(snap, me, today, listOf(t.id))) {
                Feedback.toast(app, DelaViews.NOW_FULL)
            } else {
                scope.launch {
                    app.delaDo(listOf(Dela.setOp(
                        t.id,
                        org.json.JSONObject().put("focus_on", if (on) org.json.JSONObject.NULL else today),
                        org.json.JSONObject().put("focus_on", Dela.nul(t.focusOn)),
                    )))
                }
            }
        },
        pickProject = { list -> if (list.isNotEmpty()) projectFor = list },
        starting = starting,
        // Связь с лентой видна у дела (05.10.2026): что идёт сейчас и сколько уже ушло.
        running = if (running != null) running.task else "",
        runningMs = running?.durationMs(now) ?: 0L,
        spent = remember(ribbon, now) { ru.zf.pravka.core.ZasechkaTasks.spent(ribbon, now) },
        stop = { scope.launch { app.zasechkaEngine.closeOpen() } },
        askId = rowAsk,
        askText = rowAskText,
        onAskText = { rowAskText = it },
        listening = rowListening,
        askRunning = ask.running,
        askError = { id -> ask.error(askTaskKey(id)) },
        onAskMic = { t -> rowMic(t) },
        onAskSend = { t, text -> rowSend(t, text) },
        onAskClose = {
            if (rowListening) PravkaAccessibilityService.instance?.finishDelaReason(keep = false)
            rowListening = false
            rowAsk = ""
            rowAskText = ""
        },
        snap = snap,
    )

    // Что на экране — как `head()` и тело веба: заголовок, подзаголовок, дела
    // группами. По этой модели рисуется список, и её же видит Claude (дела в
    // порядке показа). «Новое» и CRM рисуются своими кусками — модели у них нет.
    val needle = query.trim()
    val by0 = DelaViews.By.DATE
    val screen: DelaScreen? = if (page != null) null else when {
        needle.isNotEmpty() -> DelaViews.search(snap, needle, showDone).let { found ->
            DelaScreen(
                "Поиск: $needle", plural(found.size, "дело", "дела", "дел"),
                groups = DelaViews.group(found, DelaViews.By.PROJECT, snap, today), by = DelaViews.By.PROJECT,
                done = true, empty = "Ничего не нашлось.",
            )
        }
        // «Сейчас» (`renderNow` веба): до пяти дел на сегодня — сначала они, потом всё остальное.
        view == DelaViews.View.NOW -> DelaViews.now(snap, me, today, sphere).let { n ->
            DelaScreen(
                "Сейчас", DelaViews.longDate(today) + " · в «Сейчас» ${n.now.size} из ${DelaViews.NOW_MAX}",
                groups = listOfNotNull(
                    DelaViews.Group("now", "Сделать сегодня", items = n.now).takeIf { n.now.isNotEmpty() },
                    DelaViews.Group("pick", n.pickTitle, late = n.pick.any { DelaViews.isLate(it, today) }, items = n.pick)
                        .takeIf { n.pick.isNotEmpty() },
                    DelaViews.Group("next", "Завтра — если останется время", items = n.next).takeIf { n.next.isNotEmpty() },
                ),
                add = true,
                hint = if (n.now.isEmpty()) "Выбери на сегодня до ${DelaViews.NOW_MAX} дел — молния слева у дела. Сначала они, потом всё остальное." else "",
                empty = "На сегодня и на завтра пусто. Загляни в «Предстоящее».",
            )
        }
        // Просроченное — первой группой (ключ «0late»): «в предстоящих сверху — просроченные».
        view == DelaViews.View.UPCOMING -> DelaViews.upcoming(snap, me, sphere, upMineOnly, upNoDate).let { items ->
            val late = items.count { DelaViews.isLate(it, today) }
            DelaScreen(
                "Предстоящее", plural(items.size, "дело", "дела", "дел"), alert = if (late > 0) "просрочено: $late" else "",
                groups = DelaViews.group(items, by0, snap, today), by = by0, add = true, upcoming = true, empty = "Впереди пусто.",
            )
        }
        // «Пора напомнить» — первой группой, остальное по людям.
        view == DelaViews.View.WAITING -> DelaViews.waitingGroups(snap, me, today, sphere).let { groups ->
            val due = groups.firstOrNull { it.key == "0nudge" }?.items?.size ?: 0
            DelaScreen(
                "Жду", "что должны другие — по людям, с давностью", alert = if (due > 0) "пора напомнить: $due" else "",
                groups = groups, by = DelaViews.By.PERSON, add = true, empty = "Ни от кого ничего не ждём.",
            )
        }
        view == DelaViews.View.WEEK -> DelaViews.week(snap, me, today, sphere, now).let { w ->
            DelaScreen(
                "Неделя", "раз в неделю: что протухло, кто молчит, где нет следующего шага",
                groups = listOf(
                    DelaViews.Group("w1", "Протухшее — закрыть, перенести или отпустить", late = true, items = w.stale.sortedWith(Dela.ORDER)),
                    DelaViews.Group("w2", "Жду без движения больше недели", items = w.waitStale.sortedWith(Dela.ORDER)),
                ).filter { it.items.isNotEmpty() },
                add = true, projects = w.noStep,
                empty = if (w.noStep.isEmpty()) "Чисто. Неделя разобрана." else "",
            )
        }
        view == DelaViews.View.ALL -> {
            val by = groupOf(DelaViews.View.ALL.key, DelaViews.By.PROJECT)
            val items = DelaViews.list(snap, me, sphere, showDone = showDone)
            DelaScreen(
                "Все дела", plural(items.count { it.open }, "открытое", "открытых", "открытых"),
                groups = DelaViews.group(items, by, snap, today), by = by, groupKey = DelaViews.View.ALL.key, done = true, add = true, empty = "Пусто.",
            )
        }
        else -> null
    }

    // «Новое» — своими кусками (наговорки, предложения, без проекта, закрытое само); наговорки — вид сервера.
    val newParts: NewParts? =
        if (page == null && needle.isEmpty() && view == DelaViews.View.NEW) NewParts.of(snap, me, sphere, now, views[DICTATIONS]?.data) else null

    // Страница проекта — как в вебе: группировка своя (без «по проектам»), «Сделанные», фильтр сделки.
    val projectScreen: DelaScreen? = (page as? DelaPage.Project)?.let { pg ->
        val p = snap.projects[pg.id]
        val by = groupOf("project", by0).takeIf { it != DelaViews.By.PROJECT } ?: by0
        val all = snap.tasks.values.filter { it.projectId == pg.id }
        val items = all.filter { (showDone || it.open) && (dealFilter.isBlank() || it.dealId == dealFilter) }
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
            groups = DelaViews.group(items, by, snap, today), by = by, groupKey = "project",
            groupOptions = DelaViews.By.entries.filter { it != DelaViews.By.PROJECT },
            done = true, add = true, empty = "Дел нет. Следующий шаг — в строке выше.",
        )
    }
    // Страница человека — разделы веба.
    val personScreen: DelaScreen? = (page as? DelaPage.Person)?.let { pg ->
        val p = snap.people[pg.id]
        DelaScreen(
            p?.name ?: "Человек",
            p?.takeIf { it.short.isNotBlank() && it.short != it.name }?.let { "в задачах — ${it.short}" }.orEmpty(),
            groups = DelaViews.personSections(Dela.person(snap, pg.id)).filter { it.items.isNotEmpty() }
                .mapIndexed { i, sec -> DelaViews.Group("p$i", sec.title, items = sec.items.sortedWith(Dela.ORDER)) },
            add = true, empty = "Открытых дел с ним нет.",
        )
    }

    // Что видит Claude с этого экрана — то же, что человек: название и дела в порядке показа.
    val askScreen: AskScreen? = when (val pg = page) {
        is DelaPage.Person -> personScreen?.let { sc ->
            val title = "Человек: " + sc.title
            AskScreen(title, DelaAsk.scope(title, sc.ids, personId = pg.id))
        }
        is DelaPage.Project -> projectScreen?.let { sc ->
            val title = if (pg.id.isBlank()) "Входящие" else "Проект: " + sc.title
            AskScreen(title, DelaAsk.scope(title, sc.ids, projectId = pg.id))
        }
        is DelaPage.Deal -> snap.deals[pg.id].let { d ->
            val title = "Сделка: " + (d?.name ?: "?")
            val open = snap.tasks.values.filter { it.dealId == pg.id && it.open }.sortedWith(Dela.ORDER)
            AskScreen(title, DelaAsk.scope(title, open.map { it.id }, projectId = d?.projectId.orEmpty()))
        }
        // «Новое»: дела наговорок, поставленное другими и без проекта — «всё про Альфу — в Альфу».
        null -> screen?.let { AskScreen(it.title, DelaAsk.scope(it.title, it.ids)) }
            ?: newParts?.let { AskScreen("Новое", DelaAsk.scope("Новое", it.ids)) }
    }

    /**
     * Быстрое дело со страницы проекта или человека — как поле «＋» в вебе:
     * название, проект (и сделка фильтра), человек; остальное — карточкой.
     */
    fun quickAdd(title: String, projectId: String = "", dealId: String = "", personId: String = "") {
        val t = Dela.Task(
            id = Dela.newId(), title = title.trim(), projectId = projectId, dealId = dealId, personId = personId,
            ownerId = me, createdBy = me, source = "manual",
        )
        if (t.title.isBlank()) return
        scope.launch {
            app.delaDo(listOf(Dela.createOp(t)))
            Feedback.toast(app, "✓ ${t.title.take(40)}")
        }
    }

    val crmUi = remember { CrmUiState() }
    val crm = DelaCrmContext(app, snap, me, today, views, queuedOps, moneyOn, actions, push, back = { pages = pages.dropLast(1) }, ui = crmUi)

    val listState = rememberLazyListState()
    var sphereSheet by remember { mutableStateOf(false) }
    // Разворот (`screens/08`): список слева, карточка дела справа, строка «сказать» — под ней.
    val wide = ru.zf.pravka.ui.twoPane()
    val list: @Composable (Modifier) -> Unit = { listModifier ->
    LazyColumn(
        modifier = listModifier.fillMaxSize().bottomFade().scrollFade(listState),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
    ) {
        // «▷ Сейчас: Ужин с семьёй · 21 м · синк 18:40 · поиск · +» (`screens/06`):
        // что идёт в Засечке, когда сверялись с сервером, и два действия.
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
                                append("Сейчас: ")
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
                    if (askScreen != null) {
                        GlyphButton(
                            Glyphs.Spark,
                            if (askOpen) "убрать строку Claude" else "Claude: команда на дела этого экрана",
                            tint = if (askOpen || ask.running == ASK_SCREEN) mode.value else mode.label,
                            onClick = { askOpen = !askOpen },
                        )
                    }
                    GlyphButton(
                        if (searching) Glyphs.Close else Glyphs.Search,
                        if (searching) "закрыть поиск" else "поиск по делам",
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
                if (searching || query.isNotEmpty()) PaperField(value = query, onValueChange = { query = it }, label = "Поиск по делам")
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

        if (askOpen && askScreen != null) {
            item(key = "ask") {
                AskBar(
                    screen = askScreen,
                    text = askText,
                    onText = { askText = it },
                    running = ask.running == ASK_SCREEN,
                    error = ask.error(ASK_SCREEN),
                    onSend = { text -> runAsk(ASK_SCREEN, text, askScreen.scope) { askText = "" } },
                    onClose = { askOpen = false },
                )
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
                if (view == DelaViews.View.NEW) "всё, что пришло, по тому, откуда пришло: наговорки, встречи, Telegram — разложи, и оно уйдёт отсюда" else "",
            )
            is DelaPage.Project -> projectScreen ?: DelaScreen("Проект")
            is DelaPage.Person -> personScreen ?: DelaScreen("Человек")
            is DelaPage.Deal -> DelaScreen("Сделка")
        }
        // Заголовок раздела — только у страниц (человек, проект, сделка): у
        // разделов его название и так стоит в сегментах (`screens/06`).
        if (page != null || needle.isNotEmpty() || (crmOn && CRM_NAV.any { it.first == nav })) {
            item(key = "head") {
                DelaHead(
                    head,
                    sphere = "",
                    onBack = if (pages.isNotEmpty()) ({ pages = pages.dropLast(1) }) else null,
                    onMenu = { navOpen = true },
                )
            }
        }
        val crmNav = crmOn && CRM_NAV.any { it.first == nav }
        if (page == null && needle.isEmpty()) {
            // Разделы веба одним рядом, с его числами («Сейчас 3 из 5»); CRM — следом, если она видна.
            item(key = "nav") {
                val c = DelaViews.counts(snap, me, today, sphere, now)
                val keys = DelaViews.View.entries.map { it.key to it.title } + (if (crmOn) CRM_NAV else emptyList())
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
            if (view == DelaViews.View.NOW) {
                item(key = "stats") { DelaStats(snap, me, today, sphere) }
            }
        }

        val tools = ListTools(
            showDone = showDone,
            onShowDone = { showDone = !showDone },
            mineOnly = upMineOnly,
            onMineOnly = { upMineOnly = !upMineOnly },
            noDate = upNoDate,
            onNoDate = { upNoDate = !upNoDate },
            onGroup = { key, by -> scope.launch { app.delaStore.setGroup(key, by.key) } },
            onReschedule = { rescheduling = it },
            // «Без проекта» больше не страница: такие дела ждут в «Новом» («Без проекта — куда их?»).
            onProject = { id -> if (id.isBlank()) go(NAV_NEW) else push(DelaPage.Project(id)) },
            onPerson = { id -> push(DelaPage.Person(id)) },
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
                        }
                    }
                    // Теплота, «хаб», сделки и хронология — тем, кому видна CRM; у пользователя Дел их нет.
                    if (crmOn && p?.userId.isNullOrBlank()) crmPersonBlock(crm, pg.id)
                }
                is DelaPage.Project -> {
                    // Клиент из «Клиентов», которого копия ещё не знает, — тоже клиент: блок CRM спросит сервер сам.
                    val pr = snap.projects[pg.id]
                    val client = crmOn && pg.id.isNotBlank() && (pr == null || pr.kind == "client")
                    if (client) crmClientBlock(crm, pg.id)
                    projectScreen?.let { sc ->
                        val deals = snap.allDealsOf(pg.id)
                        screenBody(
                            sc, "project", actions,
                            tools.copy(
                                onAdd = { title -> quickAdd(title, projectId = pg.id, dealId = dealFilter) },
                                addLabel = if (dealFilter.isNotBlank()) "Новое дело по сделке…" else "Новое дело в проект…",
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
            return@LazyColumn
        }

        if (crmNav && needle.isEmpty()) {
            when (nav) {
                "crm" -> crmPipeline(crm)
                "clients" -> crmClients(crm)
                else -> crmTies(crm)
            }
            return@LazyColumn
        }

        // Разобранные наговоры, которые ещё ждут решения, — над разделом.
        item(key = "raznoska") { RaznoskaSection(app, voiceInBar = true) }

        val np = newParts
        if (np != null) {
            // «Понятно» и возврат закрытого само — только серверу, который их знает (тот же флаг, что у наговорок).
            val seenOn = snap.dictationsOn
            newView(
                parts = np,
                crm = crm,
                actions = actions,
                today = today,
                expanded = dictOpen,
                onExpand = { ref -> dictOpen = if (ref in dictOpen) dictOpen - ref else dictOpen + ref },
                onReopen = { t, sg ->
                    scope.launch {
                        app.delaDo(listOf(Dela.statusOp("task.reopen", t.id)) + if (seenOn) listOf(Dela.seenOp(listOf(sg.id))) else emptyList())
                        Feedback.toast(app, "Вернул в работу: ${t.title.take(40)}")
                    }
                },
                onSeen = if (seenOn) {
                    { items -> scope.launch { app.delaDo(listOf(Dela.seenOp(items.map { it.id }))) } }
                } else null,
                onAccept = { items ->
                    scope.launch {
                        app.delaDo(items.map { Dela.decideOp(it.id, accept = true) })
                        Feedback.toast(app, if (items.size == 1) "✓ принято" else "✓ принято: ${items.size}")
                    }
                },
                onEdit = { editingSuggestion = it },
                onReject = { rejecting = it },
            )
        } else if (screen != null) {
            // «Сейчас»: что ждёт в «Новом» — ссылкой под шапкой, как в вебе («в «Новом» ждут: N»).
            // Поля «Новое дело…» у разделов нет (Правка 4.0): «+» — в строке
            // состояния, голос и текст — в нижней строке. У проекта и человека оно осталось.
            screenBody(screen, if (needle.isNotEmpty()) "search" else nav, actions, tools)
            // «Новое из встреч и чатов · 4 ›» — под делами «Сейчас» (`screens/06`).
            if (view == DelaViews.View.NOW && needle.isEmpty()) {
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
    val sayBar: @Composable (Modifier) -> Unit = { barModifier ->
    ru.zf.pravka.ui.SayBar(
        value = draft,
        onValueChange = { draft = it },
        placeholder = ru.zf.pravka.core.PillHint.say(ownerName, "говори дела"),
        onSend = {
            val text = draft.trim()
            val service = PravkaAccessibilityService.instance
            if (service == null) Feedback.toast(app, app.getString(R.string.toast_no_service))
            else if (text.isNotEmpty()) {
                draft = ""
                service.onRaznoskaText(text)
            }
        },
        onMic = {
            val service = PravkaAccessibilityService.instance
            if (service == null) Feedback.toast(app, app.getString(R.string.toast_no_service))
            else service.onRaznoskaTap()
        },
        sendEnabled = draft.isNotBlank(),
        maxLines = 4,
        busy = talking,
        busyLabel = "Разбираю",
        modifier = barModifier
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 18.dp),
    )
    }
    Column(Modifier.fillMaxSize()) {
        header(if (sphere == "all") "Все сферы" else SPHERES.firstOrNull { it.first == sphere }?.second.orEmpty()) { sphereSheet = true }
        if (wide) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(1f)) { list(Modifier) }
                Box(Modifier.weight(1f)) {
                    // Открытое справа дело; пусто — первое из «Сейчас».
                    val shown = paneId?.let { snap.tasks[it] }
                        ?: snap.tasks.values.filter { it.open && it.focusOn == today && it.ownerId == me }.minByOrNull { it.num }
                    DelaTaskPane(
                        app = app,
                        task = shown,
                        snap = snap,
                        spentMs = shown?.let { actions.spent[it.id] } ?: 0L,
                        onStart = { shown?.let { actions.start(it) } },
                        onEdit = { openTask = shown },
                        onDone = { shown?.let { actions.done(it) } },
                        onPerson = { id -> push(DelaPage.Person(id)) },
                    )
                    sayBar(Modifier.align(Alignment.BottomCenter))
                }
            }
        } else {
            Box(Modifier.weight(1f)) {
                list(Modifier)
                sayBar(Modifier.align(Alignment.BottomCenter))
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
                val (set, was) = Dela.diff(fresh, after)
                if (set.length() > 0) scope.launch { app.delaDo(listOf(Dela.setOp(fresh.id, set, was))) }
            },
            onStatus = { op ->
                openTask = null
                scope.launch { app.delaDo(listOf(Dela.statusOp(op, fresh.id))) }
            },
            onStart = { openTask = null; actions.start(fresh) },
            // Микрофон у дела: команда про одно это дело, правит сервер.
            onAsk = { text -> runAsk(askTaskKey(fresh.id), text, DelaAsk.taskScope(fresh.id)) },
            asking = ask.running == askTaskKey(fresh.id),
            askError = ask.error(askTaskKey(fresh.id)),
        )
    }
    CrmSheets(crm)
    // Боковая панель всегда в дереве: закрытая — пуста, а закрываясь,
    // успевает уехать (баг №1). Числа считаются, только пока она видна.
    run {
        DelaNavSheet(
            visible = navOpen,
            nav = if (page == null && needle.isEmpty()) nav else "",
            sphere = sphere,
            counts = { DelaViews.counts(snap, me, today, sphere, now) },
            crmOn = crmOn,
            projects = { Dela.projectsNav(snap, me, today, sphere, favs) },
            onSphere = { sphere = it },
            onNav = go,
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
    val shown = ask.shown
    if (shown != null) {
        AskResultSheet(
            shown = shown,
            snap = snap,
            running = ask.running == shown.key,
            onDismiss = { ask.shown = null },
            onOpen = { id -> snap.tasks[id]?.let { ask.shown = null; openTask = it } },
            onUndo = { r ->
                ask.shown = null
                scope.launch {
                    // Отмена — обычными операциями очереди с op_id: дошли не сразу — уйдут сами.
                    app.delaStore.enqueue(DelaAsk.undoOps(r))
                    val sent = app.delaSync.pushNow(4_000L)
                    Feedback.toast(app, if (sent) "Вернул как было" else "Вернул как было — уйдёт на сервер, когда будет связь")
                }
            },
            onRetry = { text -> runAsk(shown.key, text, shown.scope) },
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
                if (after.title.isNotBlank()) scope.launch { app.delaDo(listOf(Dela.createOp(after))) }
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
            title = if (sg.kind == "update") "Уточнить ${proposed.numLabel}" else "Предложение",
            saveText = "Принять",
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

/** Что умеет строка дела — одним набором на все виды (и на страницах CRM). */
internal class DelaActions(
    val open: (Dela.Task) -> Unit,
    val start: (Dela.Task) -> Unit,
    val done: (Dela.Task) -> Unit,
    val person: (String) -> Unit,
    val project: (String) -> Unit,
    val starting: String,
    /** Молния: в «Сейчас» и обратно (до пяти, шестое — отказ словами). */
    val toggleNow: (Dela.Task) -> Unit = {},
    /** «без проекта» у строки — кнопка выбора проекта; null — подпись просто текстом. */
    val pickProject: ((List<Dela.Task>) -> Unit)? = null,
    /** Дело, которое сейчас идёт в ленте; "" — никакое. */
    val running: String = "",
    val runningMs: Long = 0L,
    /** Время в ленте по id дела. */
    val spent: Map<String, Long> = emptyMap(),
    val stop: () -> Unit = {},
    /** Микрофон у дела в строке (как в вебе): у какого дела открыто поле команды Claude. */
    val askId: String = "",
    val askText: String = "",
    val onAskText: (String) -> Unit = {},
    /** Слушает ли сейчас микрофон строки. */
    val listening: Boolean = false,
    /** Ключ команды, которую сейчас правит Claude (`askTaskKey`); "" — никакой. */
    val askRunning: String = "",
    val askError: (String) -> String = { "" },
    /** Тап по микрофону строки: открыть поле и слушать (второй тап — хватит слушать). */
    val onAskMic: (Dela.Task) -> Unit = {},
    val onAskSend: (Dela.Task, String) -> Unit = { _, _ -> },
    val onAskClose: () -> Unit = {},
    /** Копия — подписям строки нужны имена проектов, сделок и людей (как в вебе). */
    val snap: Dela.Snapshot = Dela.Snapshot(),
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
 * «Новое» по тому, откуда пришло (06.10.2026, `renderNew` веба): последние
 * наговорки — что сказал и куда что попало, предложения встреч и Telegram,
 * поставленное другими, дела без проекта, закрытое само с «Понятно».
 * Разложил — уходит отсюда (наговорки остаются: это история, а не очередь).
 */
private fun LazyListScope.newView(
    parts: NewParts,
    crm: DelaCrmContext,
    actions: DelaActions,
    today: String,
    expanded: Set<String>,
    onExpand: (String) -> Unit,
    onReopen: (Dela.Task, Dela.Suggestion) -> Unit,
    /** «Понятно» у закрытого само; null — сервер этого ещё не умеет. */
    onSeen: ((List<Dela.Suggestion>) -> Unit)?,
    onAccept: (List<Dela.Suggestion>) -> Unit,
    onEdit: (Dela.Suggestion) -> Unit,
    onReject: (Dela.Suggestion) -> Unit,
) {
    val snap = crm.snap
    val dicts = parts.dictations
    if (dicts != null) {
        // Шапка наговорок со свежестью: вид считает сервер, без сети — прежний ответ.
        item(key = "n:dicts") {
            PaperCard(
                label = "последние наговорки" + if (dicts.isNotEmpty()) " · ${dicts.size}" else "",
                info = "Что сказал и куда что попало: десять последних наговорок с «Д», из вкладки и из веба — дела любого статуса, " +
                    "заметки в хронологию и что потом сказали про эти дела встреча или Telegram. Дело то же — оно срастается и отсюда не уходит.",
            ) {
                Freshness(crm, DICTATIONS)
                if (dicts.isEmpty()) PaperHint("Наговорок пока нет — скажи дела в «Д» или в строке наверху.")
            }
        }
        for (d in dicts) {
            item(key = "n:d:" + d.ref) { DictationCard(d, snap, actions, today, open = d.ref in expanded, onExpand = { onExpand(d.ref) }) }
        }
    }
    if (parts.batches.isNotEmpty()) {
        item(key = "n:sugs") {
            Text(
                "Предложения встреч и Telegram — примешь, станут делами",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
    }
    for (b in parts.batches) {
        item(key = "n:" + (b.ref.ifBlank { b.items.first().id })) {
            val many = b.items.size > 1
            // Пачка — одна встреча или окно дайджеста: заголовок и значок источника, как в вебе.
            PaperCard(
                label = (b.title.ifBlank { sourceWord(b.source) }).take(40) + if (many) " · ${b.items.size}" else "",
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(sourceWord(b.source), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Icon(sourceGlyph(b.source), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    }
                },
            ) {
                b.items.forEachIndexed { i, sg ->
                    if (i > 0) RowRule()
                    SuggestionRow(sg, snap, onAccept = { onAccept(listOf(sg)) }, onEdit = { onEdit(sg) }, onReject = { onReject(sg) })
                }
                if (many) {
                    // Встреча — одна карточка: «мои обещания / всё / по одному».
                    val mine = b.items.filter { val p = it.payloadObj(); p.optString("ball").let { x -> x.isBlank() || x == "null" || x == Dela.MINE } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (mine.isNotEmpty() && mine.size < b.items.size) {
                            PaperTextButton("Мои обещания · ${mine.size}", onClick = { onAccept(mine) })
                        }
                        Spacer(Modifier.weight(1f))
                        PaperButton("Принять все", icon = Glyphs.Check, primary = true, onClick = { onAccept(b.items) })
                    }
                }
            }
        }
    }
    // Поставили другие (за три дня) и дела без проекта — бывшие «Утро» и «Входящие»: разложи — уйдут отсюда.
    if (parts.others.isNotEmpty()) {
        item(key = "n:others") {
            PaperCard(label = "поставили другие · ${parts.others.size}", info = "Твои дела, которые завели другие, — за три дня.") {
                TaskRows(parts.others, actions, today)
            }
        }
    }
    if (parts.loose.isNotEmpty()) {
        val pick = actions.pickProject
        item(key = "n:loose") {
            PaperCard(
                label = "без проекта — куда их? · ${parts.loose.size}",
                trailing = if (parts.loose.size > 1 && pick != null) {
                    { PaperTextButton("Все в проект…", onClick = { pick(parts.loose) }) }
                } else null,
            ) {
                PaperHint("«без проекта» у дела — выбрать проект, или скажи Claude наверху: «всё про Альфу — в Альфу».")
                TaskRows(parts.loose, actions, today)
            }
        }
    }
    // Очевидное автоматика закрывает сама (правило 5 сервера, 05.10.2026) —
    // здесь видно, что и почему; «Вернуть» — в работу, «Понятно» — убрать отсюда
    // (06.10.2026: закрытое висело неделю и «никуда не уходило»).
    val autoClosed = parts.autoClosed
    if (autoClosed.isNotEmpty()) {
        item(key = "n:auto") {
            PaperCard(
                label = "закрыто само · ${autoClosed.size}",
                info = "За неделю: очевидное из встреч и Telegram сервер закрыл сам — по свежей встрече или переписке, " +
                    "только твои открытые дела. Основание легло комментарием к делу. Согласен — «Понятно», зря — «Вернуть».",
                trailing = if (autoClosed.size > 1 && onSeen != null) {
                    { PaperTextButton("Понятно, все", onClick = { onSeen(autoClosed) }) }
                } else null,
            ) {
                autoClosed.forEachIndexed { i, sg ->
                    if (i > 0) RowRule()
                    AutoClosedRow(sg, snap.tasks[sg.taskId], onReopen = { t -> onReopen(t, sg) }, onSeen = onSeen?.let { f -> { f(listOf(sg)) } })
                }
            }
        }
    }
    if (parts.empty) {
        item(key = "n:empty") {
            Box(Modifier.padding(start = 4.dp)) { PaperHint("Всё разложено. Новое появится, когда наговоришь или придёт со встречи и из Telegram.") }
        }
    }
}

/**
 * Наговорка в «Новом»: когда и откуда, текст (длинный — тапом целиком), её
 * дела строками — с тем, что потом сказала про них автоматика («Telegram:
 * закрыть — ждёт решения ниже»), и заметки, ушедшие в хронологию.
 */
@Composable
private fun DictationCard(d: DelaViews.Dictation, snap: Dela.Snapshot, actions: DelaActions, today: String, open: Boolean, onExpand: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val tasks = DelaViews.dictTasks(d, snap)
    val head = listOf(
        DelaViews.whenWords(d.at, today, java.time.ZoneId.systemDefault()),
        DelaViews.dictSource(d.source),
        if (tasks.isEmpty()) "дел нет" else plural(tasks.size, "дело", "дела", "дел"),
    ).filter { it.isNotBlank() }.joinToString(" · ")
    PaperCard(label = head) {
        if (d.text.isNotBlank()) {
            Text(
                d.text,
                style = MaterialTheme.typography.bodyMedium,
                color = c.onSurfaceVariant,
                maxLines = if (open) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onExpand).padding(vertical = 2.dp),
            )
        }
        if (tasks.isNotEmpty()) {
            val touches = d.touches.groupBy { it.taskId }
            TaskRows(tasks, actions, today, extra = { t -> touches[t.id].orEmpty().map { DelaViews.touchWord(it, snap) } })
        }
        for (n in d.notes) {
            val p = snap.projects[n.projectId]?.name
            PaperHint("в хронологию" + (if (p != null) " · $p" else "") + ": " + n.summary, c.onSurface)
        }
    }
}

/** «#9 Продлить Контур», откуда и когда, основание — «Понятно» (согласен) и «Вернуть», пока дело закрыто. */
@Composable
private fun AutoClosedRow(sg: Dela.Suggestion, t: Dela.Task?, onReopen: (Dela.Task) -> Unit, onSeen: (() -> Unit)?) {
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(t?.let { "${it.numLabel} ${it.title}" } ?: "дело не видно", style = MaterialTheme.typography.bodyMedium)
            val meta = listOf(sg.batchTitle.ifBlank { sourceWord(sg.source) }, delaDate(sg.decidedAt)).filter { it.isNotBlank() }.joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1)
            if (sg.quote.isNotBlank()) {
                Text("«${sg.quote.take(200)}»", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            if (onSeen != null) PaperTextButton("Понятно", icon = Glyphs.Check, onClick = onSeen)
            when {
                t != null && t.status == Dela.DONE -> PaperTextButton("Вернуть", icon = Glyphs.Undo, onClick = { onReopen(t) })
                t != null && t.open -> Text("вернул в работу", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
    }
}

/** Значок пачки — откуда она: встреча, Telegram, Claude. */
private fun sourceGlyph(source: String) = when (source) {
    "meeting" -> Glyphs.Mic
    "telegram", "userbot" -> Glyphs.Send
    "mcp" -> Glyphs.Ask
    else -> Glyphs.Spark
}

private fun sourceWord(source: String): String = when (source) {
    "meeting" -> "со встречи"
    "telegram", "userbot" -> "из Telegram"
    "bot" -> "из бота"
    "mcp" -> "от Claude"
    "phone" -> "с телефона"
    "user" -> "от человека"
    else -> "предложение"
}

@Composable
private fun SuggestionRow(
    sg: Dela.Suggestion,
    snap: Dela.Snapshot,
    onAccept: () -> Unit,
    onEdit: () -> Unit,
    onReject: () -> Unit,
) {
    // Правка 4.0 (`screens/07`): цитата источника курсивом сверху, дело —
    // жирным «Кто: действие», под ним подписи; справа ✕ · ✎ и ✓ круглой
    // клавишей режима — главное действие предложения.
    val p = sg.payloadObj()
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    // ✎ — у «завести» и «уточнить»: черновик правится той же карточкой. У «закрыть» правки нет — только да или нет.
    val editable = sg.kind == "create" || sg.kind == "update"
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (sg.quote.isNotBlank() && sg.quote != sg.title) {
            Text(
                "«${sg.quote.take(200)}»",
                style = ty.body.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                color = ru.zf.pravka.ui.Ink.TextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        val t = snap.tasks[sg.taskId]
        val ref = t?.let { "${it.numLabel} ${it.title}" } ?: "дело не видно"
        val head = when (sg.kind) {
            "close" -> "Закрыть: $ref"
            "assign" -> "Взять себе: $ref"
            "update" -> "Уточнить: $ref"
            else -> sg.title
        }
        Text(
            head,
            style = ty.bodyStrong.copy(fontSize = ty.bodyL.fontSize, lineHeight = ty.bodyL.lineHeight),
            color = ru.zf.pravka.ui.Ink.TextStrong,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = editable, onClick = onEdit),
        )
        // Подробности уточнения — отдельной строкой: при принятии лягут комментарием к делу.
        val note = if (sg.kind == "update") p.optString("note").takeIf { it.isNotBlank() && it != "null" } else null
        if (note != null) Text("+ $note", style = ty.label, color = mode.label)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            val meta = if (sg.kind == "update") {
                // Что именно поменяется: название, срок «было → стало», мяч с человеком.
                val u = Dela.update(sg, snap)
                listOfNotNull(
                    u.title.takeIf { it.isNotBlank() }?.let { "название: «$it»" },
                    u.dueTo.takeIf { it.isNotBlank() }?.let { "срок " + (if (u.dueFrom.isNotBlank()) delaDate(u.dueFrom) + " – " else "") + delaDate(it) },
                    if (u.ball.isNotBlank()) "мяч: " + ballWord(u.ball) + (if (u.person.isNotBlank()) " ${u.person}" else "")
                    else u.person.takeIf { it.isNotBlank() }?.let { "человек: $it" },
                ).joinToString(" · ")
            } else if (sg.kind == "create") {
                listOfNotNull(
                    p.optString("due_date").takeIf { it.isNotBlank() && it != "null" }?.let { "до " + delaDate(it) },
                    p.optString("person_name").takeIf { it.isNotBlank() && it != "null" },
                    p.optString("ball").takeIf { it == Dela.WAITING || it == Dela.AGENDA }?.let { ballWord(it) },
                    p.optString("project_name").takeIf { it.isNotBlank() && it != "null" },
                ).joinToString(" · ")
            } else {
                t?.projectName.orEmpty()
            }
            Text(
                meta,
                style = ty.label,
                color = mode.meta,
                modifier = Modifier.weight(1f).padding(end = 4.dp),
            )
            GlyphButton(Glyphs.Close, "отклонить", onClick = onReject, size = 40.dp, tint = mode.label)
            if (editable) GlyphButton(Glyphs.Edit, "поправить и принять", onClick = onEdit, size = 40.dp, tint = mode.label)
            Spacer(Modifier.width(4.dp))
            ru.zf.pravka.ui.Key(Glyphs.Check, "принять", onAccept, size = 44.dp)
        }
    }
}

private fun ballWord(ball: String): String = when (ball) {
    Dela.WAITING -> "жду"
    Dela.AGENDA -> "повестка"
    else -> "моё"
}

/** Переключатели над списком и куда ведут его ссылки — как тулбар и ссылки веба. */
private data class ListTools(
    val showDone: Boolean,
    val onShowDone: () -> Unit,
    val mineOnly: Boolean,
    val onMineOnly: () -> Unit,
    val noDate: Boolean,
    val onNoDate: () -> Unit,
    /** Выбрана группировка списка [String] (`S.groups` веба). */
    val onGroup: (String, DelaViews.By) -> Unit,
    val onReschedule: (List<Dela.Task>) -> Unit,
    /** Заголовок группы — ссылка: проект ("" — «Входящие») или человек. */
    val onProject: (String) -> Unit,
    val onPerson: (String) -> Unit,
    val onAdd: ((String) -> Unit)? = null,
    val addLabel: String = "Новое дело…",
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
    val hasTools = sc.upcoming || sc.done || sc.groupKey != null || (sc.add && tools.onAdd != null) || extra != null
    if (hasTools) {
        item(key = "$key:tools") {
            PaperCard {
                extra?.invoke(this)
                if (sc.upcoming || sc.done || sc.groupKey != null) {
                    ChipRow {
                        if (sc.upcoming) {
                            PaperChip("Только мяч у меня", selected = tools.mineOnly, onClick = tools.onMineOnly)
                            PaperChip("И без даты", selected = tools.noDate, onClick = tools.onNoDate)
                        }
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
                        val pid = g.projectId
                        val hid = g.personId
                        if (pid != null && !onProjectPage) GlyphButton(Glyphs.Forward, if (pid.isBlank()) "в «Новое» — разложить по проектам" else "проект целиком", onClick = { tools.onProject(pid) }, size = 30.dp)
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
    if (sc.groups.isEmpty() && sc.projects.isEmpty() && sc.empty.isNotBlank()) {
        item(key = "$key:empty") { Box(Modifier.padding(start = 4.dp)) { PaperHint(sc.empty) } }
    }
}

/** Шапка раздела — как `head()` веба: «‹» на страницах, ☰ — боковая панель, заголовок, подзаголовок, тревога красным. */
@Composable
private fun DelaHead(sc: DelaScreen, sphere: String, onBack: (() -> Unit)?, onMenu: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) GlyphButton(Glyphs.Back, "назад", onClick = onBack)
        GlyphButton(Glyphs.ListLines, "разделы, CRM, проекты и люди — как боковая панель веба", onClick = onMenu)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(sc.title, style = MaterialTheme.typography.titleMedium)
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
    crmOn: Boolean,
    projects: () -> Dela.ProjectsNav,
    onSphere: (String) -> Unit,
    onNav: (String) -> Unit,
    onProject: (String) -> Unit,
    onPerson: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Сбоку, с отступами от краёв, а не листом во весь экран снизу (баг №1:
    // «чтобы сбоку оно вылезало… я его крутил, выбирал и дальше оно выезжало
    // обратно»).
    ru.zf.pravka.ui.SideSheet(visible = visible, onDismiss = onDismiss, title = "Дела", icon = Glyphs.Delo) {
        val counts = counts()
        val projects = projects()
        Segments(
            options = SPHERES.map { it.second },
            selected = SPHERES.indexOfFirst { it.first == sphere }.coerceAtLeast(0),
            onSelect = { onSphere(SPHERES[it].first) },
        )
        // «Сейчас» — отдельной строкой над остальными, со счётчиком «3 из 5» (как в вебе).
        for (v in DelaViews.View.entries) {
            NavLine(v.title, 0, selected = nav == v.key, countText = counts.label(v), hot = counts.hot(v)) { onNav(v.key) }
            if (v == DelaViews.View.NOW) RowRule()
        }
        if (crmOn) {
            NavHead("CRM")
            for ((k, t) in CRM_NAV) NavLine(t, 0, selected = nav == k) { onNav(k) }
        }
        if (projects.favorites.isNotEmpty()) {
            NavHead("Избранное")
            for (r in projects.favorites) ProjectLine(r.project.name, r.open, r.late) { onProject(r.project.id) }
        }
        for ((title, rows) in projects.groups) {
            var open by rememberSaveable("nav:$title") { mutableStateOf(true) }
            SummaryLine(title = title, summary = rows.size.toString(), expanded = open, onToggle = { open = !open }) {
                for (r in rows) ProjectLine(r.project.name, r.open, r.late) { onProject(r.project.id) }
            }
        }
        if (projects.people.isNotEmpty()) {
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
private fun QuickAddRow(label: String, onAdd: (String) -> Unit) {
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
) {
    list.forEachIndexed { i, t ->
        if (i > 0) RowRule()
        TaskRow(t, actions, today, by, onProjectPage, extra(t))
    }
}

/**
 * Дело строкой: кружок — закрыть одним касанием (как в Todoist), сама строка —
 * карточка, ▶ — запись в Засечке с id дела и проекта, под ним микрофон —
 * команда Claude про это дело (поле раскрывается под строкой). Под названием — номер,
 * проект, у кого мяч, срок и оценка. Денег дела нет с 05.10.2026: владелец —
 * «тяжело смотреть, нагружает».
 */
@Composable
private fun TaskRow(
    t: Dela.Task,
    actions: DelaActions,
    today: String,
    by: DelaViews.By? = null,
    onProjectPage: Boolean = false,
    extra: List<String> = emptyList(),
) {
    // Правка 4.0 (DESIGN §11.7 TaskRow в Делах, `screens/06`): кольцо 22 в
    // зоне 44, «Кто: действие» до двух строк, вторая строка — подписи веба;
    // справа ▶ и микрофон по 40. Просроченное — толще кольцо и «просрочено
    // N дн» жирным, без красного. Молния «Сейчас» (06.10.2026) — слева.
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    val asking = actions.askId == t.id
    val running = actions.askRunning == askTaskKey(t.id)
    val lateDays = if (t.open && t.dueDate.isNotBlank() && t.dueDate < today)
        runCatching { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(t.dueDate.take(10)), LocalDate.parse(today)).toInt() }.getOrDefault(0) else 0
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { actions.open(t) }.padding(vertical = 4.dp),
        ) {
            if (t.open) {
                val on = t.focusOn == today
                Icon(
                    Glyphs.Bolt,
                    contentDescription = if (on) "убрать из «Сейчас»" else "в «Сейчас» — сделать сегодня (до ${DelaViews.NOW_MAX} дел)",
                    tint = if (on) mode.value else mode.meta.copy(alpha = 0.45f),
                    modifier = Modifier.size(30.dp).clip(CircleShape).clickable { actions.toggleNow(t) }.padding(6.dp),
                )
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable { actions.done(t) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .then(if (!t.open) Modifier.background(mode.key) else Modifier)
                        .border(if (lateDays > 0) 2.5.dp else 2.dp, if (lateDays > 0) mode.value else mode.tint, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!t.open) Icon(Glyphs.Check, contentDescription = "закрыто", tint = ru.zf.pravka.ui.Ink.KeyIcon, modifier = Modifier.size(14.dp))
                }
            }
            Column(Modifier.weight(1f).padding(start = 2.dp)) {
                Text(
                    t.title,
                    style = ty.bodyL,
                    color = if (t.open) ru.zf.pravka.ui.Ink.Text else mode.meta,
                )
                val live = t.id == actions.running
                val spentMs = actions.spent[t.id] ?: 0L
                val chips = DelaViews.chips(t, by, actions.snap, today, onProjectPage)
                val pick = actions.pickProject
                val loose = pick != null && t.open && DelaViews.NO_PROJECT in chips
                val meta = listOfNotNull(
                    ("идёт " + ru.zf.pravka.core.ZasechkaTasks.label(actions.runningMs)).takeIf { live },
                    chips.filter { !(loose && it == DelaViews.NO_PROJECT) }.joinToString(" · ").takeIf { it.isNotBlank() },
                    ("в ленте " + ru.zf.pravka.core.ZasechkaTasks.label(spentMs)).takeIf { spentMs > 0L },
                    when (t.status) { Dela.DONE -> "сделано"; Dela.CANCELLED -> "отменено"; else -> null },
                    t.numLabel + if (t.local) " ⏳" else "",
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
                                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold, color = mode.value))
                                append("просрочено $lateDays дн")
                                pop()
                                if (meta.isNotBlank()) append(" · ")
                            }
                            append(meta)
                        },
                        style = ty.label.copy(fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (live) mode.value else mode.meta,
                    )
                }
                for (line in extra) {
                    Text(line, style = ty.label, color = mode.label)
                }
            }
            // ▶ (или стоп) — запись в Засечке; микрофон — команда Claude про это дело.
            if (actions.starting == t.id) {
                Text("…", style = ty.body, color = mode.tint, modifier = Modifier.size(40.dp).padding(10.dp))
            } else if (t.open && t.id == actions.running) {
                ru.zf.pravka.ui.StopKey(onClick = { actions.stop() }, description = "остановить в ленте")
            } else if (t.open) {
                GlyphButton(Glyphs.Play, "начать в ленте", { actions.start(t) }, tint = mode.tint, size = 40.dp)
            }
            GlyphButton(
                if (asking && actions.listening) Glyphs.Stop else Glyphs.Mic,
                if (asking && actions.listening) "хватит слушать" else "сказать Claude, что сделать с этим делом",
                { actions.onAskMic(t) },
                tint = if (asking || running) mode.value else mode.tint,
                size = 40.dp,
            )
        }
        if (asking || running) {
            AskInline(
                // Поле у дела, которое правится, а поле уже открыто у другого, — без чужого текста.
                text = if (asking) actions.askText else "",
                onText = actions.onAskText,
                listening = asking && actions.listening,
                running = running,
                error = actions.askError(t.id),
                onMic = { actions.onAskMic(t) },
                onSend = { actions.onAskSend(t, it) },
                onClose = actions.onAskClose,
            )
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
    /** Команда Claude про это дело (сервер, `/api/ask`); null — микрофона нет (новое дело, черновик). */
    onAsk: ((String) -> Unit)? = null,
    asking: Boolean = false,
    askError: String = "",
) {
    var f by remember(task.id) { mutableStateOf(task) }
    var picking by remember { mutableStateOf("") }
    var askShown by remember(task.id) { mutableStateOf(askError.isNotBlank() || asking) }
    var askText by remember(task.id) { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var comment by remember { mutableStateOf("") }
    var history by remember { mutableStateOf<List<String>?>(null) }
    var historyNote by remember { mutableStateOf("") }
    val scope = app.appScope
    val today = LocalDate.now()
    val comments = snap.commentsOf(task.id)
    val existing = !isNew

    PaperSheet(
        onDismiss = onDismiss,
        title = title ?: if (isNew) "Новое дело" else "Дело ${task.numLabel}",
        icon = Glyphs.Delo,
        subtitle = if (existing) listOfNotNull(
            task.createdAt.take(10).takeIf { it.isNotBlank() }?.let { "заведено " + delaDate(it) },
            sourceName(task.source),
        ).joinToString(" · ") else null,
        actions = {
            if (existing && onAsk != null) {
                GlyphButton(
                    Glyphs.Mic,
                    "сказать Claude, что сделать с этим делом",
                    tint = if (askShown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = {
                        askShown = true
                        // Сразу слушать — как микрофон у дела в вебе; набрать можно и руками.
                        val service = PravkaAccessibilityService.instance
                        if (!listening && !asking && service != null) {
                            listening = service.listenForDelaReason { said ->
                                listening = false
                                val full = (askText.trim() + " " + said.trim()).trim()
                                askText = full
                                if (full.isNotBlank()) onAsk(full)
                            }
                        }
                    },
                )
            }
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
        if (askShown && onAsk != null) {
            // Команда про это дело: «на пятницу, это Наташе, первым делом». Не вышло — текст остаётся.
            PaperField(
                value = askText,
                onValueChange = { askText = it },
                label = if (listening) "Слушаю — что сделать с делом…" else "Claude: «на пятницу, это Наташе»",
                singleLine = false,
                maxLines = 3,
                enabled = !asking,
            )
            if (asking) ThinkingLine("Claude правит…")
            if (askError.isNotBlank() && !asking) PaperHint(askError, MaterialTheme.colorScheme.error)
            Row(verticalAlignment = Alignment.CenterVertically) {
                AskMic(listening, { listening = it }, enabled = !asking, onText = { said ->
                    val full = (askText.trim() + " " + said.trim()).trim()
                    askText = full
                    if (full.isNotBlank()) onAsk(full)
                })
                Spacer(Modifier.weight(1f))
                PaperTextButton(
                    if (askError.isNotBlank()) "Ещё раз" else "Отдать Claude",
                    icon = Glyphs.Ask,
                    enabled = askText.isNotBlank() && !asking,
                    onClick = { onAsk(askText.trim()) },
                )
            }
            RowRule()
        }
        if (quote.isNotBlank()) PaperHint("«${quote.take(300)}»")
        if (warn.isNotBlank()) PaperHint(warn, MaterialTheme.colorScheme.error)
        PaperField(value = f.title, onValueChange = { f = f.copy(title = it) }, label = "Кто: что сделать", singleLine = false)
        PaperField(value = f.notes, onValueChange = { f = f.copy(notes = it) }, label = "Заметки", singleLine = false, maxLines = 6)

        PaperRow(
            title = f.projectId.let { id -> if (id.isBlank()) "Входящие (без проекта)" else snap.projects[id]?.name ?: f.projectName.ifBlank { "проект" } },
            hint = "проект",
            icon = Glyphs.ListLines,
            onClick = { picking = "project" },
        )
        if (f.projectId.isNotBlank() && snap.dealsOf(f.projectId).isNotEmpty()) {
            PaperRow(
                title = snap.deals[f.dealId]?.name ?: "без сделки",
                hint = "сделка",
                icon = Glyphs.Layers,
                onClick = { picking = "deal" },
            )
        }

        PaperHint("У кого мяч")
        ChipRow {
            PaperChip("Моё", selected = f.ball == Dela.MINE, onClick = { f = f.copy(ball = Dela.MINE) })
            PaperChip("Жду", selected = f.ball == Dela.WAITING, onClick = { f = f.copy(ball = Dela.WAITING) })
            PaperChip("При встрече", selected = f.ball == Dela.AGENDA, onClick = { f = f.copy(ball = Dela.AGENDA) })
        }
        PaperRow(
            title = snap.people[f.personId]?.label ?: "без человека",
            hint = when (f.ball) { Dela.WAITING -> "жду от"; Dela.AGENDA -> "поднять с"; else -> "для кого / про кого" },
            icon = Glyphs.Phone,
            onClick = { picking = "person" },
        )
        if (f.ball != Dela.MINE && f.personId.isBlank()) {
            PaperHint("Без человека «жду» и «при встрече» некого ждать — выбери, у кого мяч.", MaterialTheme.colorScheme.error)
        }
        // Кто просил — как в вебе («Его просьбы ко мне»): строкой, если есть.
        if (f.requestedBy.isNotBlank()) {
            PaperHint("просил: " + (snap.people[f.requestedBy]?.label ?: task.requestedByShort.ifBlank { "?" }))
        }

        DateLine("Срок", f.dueDate, today) { f = f.copy(dueDate = it, dueTime = if (it.isBlank()) "" else f.dueTime) }
        if (f.dueDate.isNotBlank()) TimeLine(f.dueTime) { f = f.copy(dueTime = it) }
        // «Напомнить ему» — день пнуть того, у кого мяч (как в вебе); моё напоминание — ниже, в Telegram.
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
        if (onDrop == null) PaperToggle(
            "Сейчас — на сегодня",
            checked = f.focusOn == today.toString(),
            onCheckedChange = { on ->
                // Не больше пяти, как у молнии в строке и в вебе: шестое не встаёт.
                val me = app.delaSync.link.value?.user ?: app.delaStore.me
                if (on && !DelaViews.nowRoom(snap, me, today.toString(), listOf(task.id))) Feedback.toast(app, DelaViews.NOW_FULL)
                else f = f.copy(focusOn = if (on) today.toString() else "")
            },
            hint = "до ${DelaViews.NOW_MAX} дел на сегодня — первыми во вкладке и в Засечке; завтра отметка гаснет сама",
        )
        PaperToggle("Хочу сам", checked = f.want, onCheckedChange = { f = f.copy(want = it) }, hint = "не потому, что попросили")
        PaperRow(
            title = if (f.labels.isEmpty()) "метки" else f.labels.joinToString(" ") { "@$it" },
            hint = "свободные контексты: звонок, …",
            icon = Glyphs.Tag,
            onClick = { picking = "labels" },
        )

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
                    scope.launch {
                        app.delaSync.card(task.id)
                            .onSuccess { card -> history = card.history.map { historyLine(it, snap) } }
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
@Composable
private fun PickSheet(
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
private fun PickLine(text: String, more: String, selected: Boolean, onClick: () -> Unit) {
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
 * или голосом (микрофон — тот же движок, что у «Д»).
 */
@Composable
private fun RejectSheet(suggestion: Dela.Suggestion, onDismiss: () -> Unit, onReject: (String) -> Unit) {
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

/**
 * Плитки над разделом «Сейчас» (`screens/06`): Сегодня «0 из 5 / 2 ч 25 м»,
 * Неделя «14 из 23», Просрочено «1 / на 2 дня», Жду «6 / от 4 людей».
 */
@Composable
private fun DelaStats(snap: Dela.Snapshot, me: String, today: String, sphere: String) {
    val mine = remember(snap, me, sphere) { DelaViews.openMine(snap, me, sphere) }
    val nowOpen = remember(snap, me, today) { DelaViews.nowTasks(snap, me, today) }
    val doneAll = remember(snap, me) { snap.tasks.values.filter { it.status == Dela.DONE && (me.isBlank() || it.ownerId == me) } }
    val doneToday = doneAll.count { it.completedAt.take(10) == today && it.focusOn == today }
    val estimate = nowOpen.sumOf { it.estimateMin.coerceAtLeast(0) }
    val d = runCatching { LocalDate.parse(today) }.getOrDefault(LocalDate.now())
    val monday = d.minusDays((d.dayOfWeek.value - 1).toLong()).toString()
    val sunday = d.plusDays((7 - d.dayOfWeek.value).toLong()).toString()
    val weekOpen = mine.count { it.dueDate.isNotBlank() && it.dueDate <= sunday }
    val weekDone = doneAll.count { it.completedAt.take(10) in monday..sunday }
    val late = mine.filter { DelaViews.isLate(it, today) }
    val lateDays = late.maxOfOrNull {
        runCatching { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(it.dueDate.take(10)), d).toInt() }.getOrDefault(0)
    } ?: 0
    val waiting = mine.filter { it.ball == Dela.WAITING }
    val people = waiting.map { it.personId }.filter { it.isNotBlank() }.toSet().size
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ru.zf.pravka.ui.StatTile("Сегодня", "$doneToday из ${nowOpen.size + doneToday}", Modifier.weight(1f), delta = if (estimate > 0) ru.zf.pravka.core.Fmt.dur(estimate) else null)
        ru.zf.pravka.ui.StatTile("Неделя", "$weekDone из ${weekOpen + weekDone}", Modifier.weight(1f), delta = if (weekOpen > 0) "осталось $weekOpen" else null)
        ru.zf.pravka.ui.StatTile("Просрочено", "${late.size}", Modifier.weight(1f), delta = if (lateDays > 0) "на $lateDays дн" else null, worse = late.isNotEmpty())
        ru.zf.pravka.ui.StatTile("Жду", "${waiting.size}", Modifier.weight(1f), delta = if (people > 0) "от $people чел." else null)
    }
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
