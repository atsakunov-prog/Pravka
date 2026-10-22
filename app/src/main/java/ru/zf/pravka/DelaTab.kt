package ru.zf.pravka

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex

import ru.zf.pravka.ui.glass


import androidx.compose.foundation.layout.imePadding

import androidx.compose.foundation.layout.navigationBarsPadding

import androidx.compose.foundation.layout.PaddingValues

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.zf.pravka.core.Asks
import ru.zf.pravka.core.Dela
import ru.zf.pravka.core.DelaRemind
import ru.zf.pravka.core.DelaNotices
import ru.zf.pravka.core.DelaAsk
import ru.zf.pravka.core.DelaViews
import ru.zf.pravka.core.DelaGroups
import ru.zf.pravka.core.DelaKrug
import ru.zf.pravka.core.DelaSteps
import ru.zf.pravka.trigger.PravkaAccessibilityService
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
import ru.zf.pravka.ui.scrollFade

// Вкладка «Дела» на домашнем сервере (03.10.2026, docs/dela-server.md,
// «Задание телефону», пункт 3). Центр системы — ежедневный и еженедельный
// разбор («Утро», «Неделя»), а не ввод: ввода у владельца и так в избытке
// (321 задача из сентябрьских встреч лежала без дела). Поэтому вкладка
// открывается на «Утре», а ввод — та же строка, что у «Д» (подпись с 16.10.2026 — «Добавь дело или скажи, что сделать»).
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
    /**
     * «Сейчас на завтра» (docs/dela-phone-27.md): во всём «Новом» — строкой сверху (`planBox` веба там же), в пачке
     * `plan:<день>` — сама пачка: тогда [asks] — её кандидаты (П-номера для Claude), а поставленного и сделанного само нет.
     */
    val plan: DelaViews.Plan? = null,
    /** Это пачка плана (`plan:<день>`): рисуется своим блоком, а не «Подскажи». */
    val planBatch: Boolean = false,
) {
    /** Дела экрана в порядке показа — то, что видит Claude (и сделанное само: «верни акт» он поймёт). */
    val ids: List<String>
        get() = (if (planBatch) plan?.marked.orEmpty().map { it.id } + asks.mapNotNull { sg -> sg.taskId.takeIf { it.isNotBlank() } } else emptyList()) +
            feed.take(DelaViews.FEED_MAX).map { it.id } + autoDone.mapNotNull { sg -> sg.taskId.takeIf { it.isNotBlank() } }

    val empty: Boolean get() = asks.isEmpty() && feed.isEmpty() && autoDone.isEmpty() && plan?.visible != true

    companion object {
        /** [batch] не пустой — одна пачка (дайджест на «Сегодня» → «Открыть «Новое»», `#/new/<batch_ref>` веба). */
        fun of(snap: Dela.Snapshot, me: String, sphere: String, now: Long, today: String, batch: String = ""): NewParts =
            if (batch.startsWith(DelaViews.PLAN_BATCH) && snap.planOn) {
                val p = DelaViews.plan(snap, me, today, now, day = batch.removePrefix(DelaViews.PLAN_BATCH))
                NewParts(p?.items.orEmpty(), emptyList(), emptyList(), plan = p, planBatch = true)
            } else if (batch.isNotEmpty()) NewParts(
                DelaViews.asksOfBatch(snap, me, batch), DelaViews.feedOfBatch(snap, batch), DelaViews.autoOfBatch(snap, me, batch, now),
            )
            else NewParts(
                DelaViews.asks(snap, me), DelaViews.feed(snap, me, sphere, now), Dela.autoDone(snap, me, now),
                plan = DelaViews.plan(snap, me, today, now)?.takeIf { it.visible },
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
    /**
     * Молния «В «Сейчас»» / «Из «Сейчас»» (`toggleNow` веба, 13.10.2026): отметка владельца, шестое — тост.
     * С 14.10.2026 — единственная отметка у строки и в карточке: солнца «на сегодня / на завтра» нет.
     */
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
     * Короткая строка (разворот вида v2, `.li` холста): ▶ — в карточке справа, куда строка и ведёт: в средней колонке
     * на неё и молнию уходила половина ширины, и названия ломались по слову. С 16.10.2026 «+» и молния — и у короткой
     * строки (Саша: «в вебе есть + и молния около каждого дела… а в телефоне нет»), клавишами чуть меньше.
     */
    val compact: Boolean = false,
    /** Долгое нажатие на строку (15.10.2026): меню дела — «Для этого», «Вложить в…», «Вынуть», «Раньше» / «Позже». */
    val menu: ((Dela.Task) -> Unit)? = null,
    /** «+» у строки (16.10.2026, как «+» строки веба): «Для этого» — лист нового дела внутри; null — «+» нет. */
    val step: ((Dela.Task) -> Unit)? = null,
    /** «Деревом»: свёрнутые родители и плюсик свернуть / раскрыть (помнит устройство). */
    val folded: Set<String> = emptySet(),
    val fold: (String) -> Unit = {},
    /**
     * Сделанные флажком строки, которые стоят на месте (16.10.2026 вечер, запись 30, `core/DelaLinger.kt`): в списке —
     * прежняя открытая копия, рисуется сделанной; повторный тап — «Вернуть в работу».
     */
    val held: Set<String> = emptySet(),
)

/**
 * Пачка «Сейчас на завтра» (`plan:<день>`, docs/dela-phone-27.md; `planBox` веба). Владелец (ответ 1а): «до семи кандидатов,
 * у каждого — почему и откуда; отмечаю до пяти». Сверху — «сегодня сделано x из y» и «третий день в «Сейчас»» из
 * вида `plan` (тот берётся не чаще раза в минуту и не на главном потоке, как `week`; без него — только копия синка),
 * отмеченное заранее — строками с «Убрать» (молния знает только сегодня), ниже — кандидаты: название (тап — карточка),
 * «почему · откуда» и кнопки сервера «☆ На завтра · ✓ Уже сделал · Не надо». «Поставить все» нет: отмечает он сам.
 */
private fun LazyListScope.planBlock(
    parts: NewParts,
    p: DelaViews.Plan?,
    crm: DelaCrmContext,
    actions: DelaActions,
    today: String,
    onAccept: (List<Dela.Suggestion>) -> Unit,
    onAnswer: (Dela.Suggestion, DelaViews.Option) -> Unit,
    onReject: (Dela.Suggestion) -> Unit,
    onRejectNow: (Dela.Suggestion, String) -> Unit,
    onUnmark: (Dela.Task) -> Unit,
    view: org.json.JSONObject?,
    viewAt: Long,
    onView: () -> Unit,
) {
    val snap = crm.snap
    item(key = "n:plan") {
        LaunchedEffect(Unit) { if (System.currentTimeMillis() - viewAt > 60_000L) onView() }
        val mode = ru.zf.pravka.ui.LocalMode.current
        val ty = ru.zf.pravka.ui.LocalPravkaType.current
        val marked = p?.marked.orEmpty()
        PaperCard(label = "Отметь на день · отмечено ${marked.size}") {
            Text(
                listOfNotNull(
                    p?.let { DelaViews.planToday(view, it) }?.takeIf { it.isNotBlank() },
                    "не отмеченное погаснет в 12:00 — само ничего не встанет",
                ).joinToString(" · "),
                style = ty.meta,
                color = mode.meta,
            )
            for (x in DelaViews.planStuck(view)) {
                val t = snap.tasks[x.id]
                Text(
                    DelaViews.stuckLine(x),
                    style = ty.label,
                    color = ru.zf.pravka.ui.Ink.Warn,
                    modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp)).clickable(enabled = t != null) { t?.let(actions.open) },
                )
            }
            if (p != null && p.ahead) marked.forEach { t ->
                RowRule()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Icon(Glyphs.Bolt, contentDescription = null, tint = mode.tint, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        t.title,
                        style = ty.body,
                        color = ru.zf.pravka.ui.Ink.Text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable { actions.open(t) },
                    )
                    PaperTextButton("Убрать", onClick = { onUnmark(t) })
                }
            }
            parts.asks.forEachIndexed { i, sg ->
                RowRule()
                AskRow(
                    sg, i + 1, snap, crm.me, today,
                    onAccept = { onAccept(listOf(sg)) }, onEdit = {}, onReject = { onReject(sg) },
                    onOpen = actions.open, onMerge = {},
                    onAnswer = { o -> onAnswer(sg, o) },
                    onRejectNow = { reason -> onRejectNow(sg, reason) },
                    plan = true,
                )
            }
            if (parts.asks.isEmpty()) {
                PaperHint(
                    if (p?.ahead == true) "Кандидатов больше нет — отмеченное встанет в «Сейчас» само в свой день."
                    else "Кандидатов на этот день больше нет — отмеченное уже в «Сейчас»."
                )
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
            "phone" -> Glyphs.Phone
            "circle" -> ru.zf.pravka.ui.KvGlyphs.byKey("target") ?: Glyphs.Spark
            "bolt" -> Glyphs.Bolt
            else -> Glyphs.Edit
        },
        contentDescription = m.word,
        tint = if (m.plan) ru.zf.pravka.ui.Ink.PlanText else ru.zf.pravka.ui.LocalMode.current.meta,
        modifier = Modifier.size(size),
    )
}

/**
 * Вопрос «Подскажи» (`askItem` веба): сверху — значок и пачка (встреча —
 * ссылкой), П-номер (его же видит Claude: «П1 поставь, срок пятница») и что
 * предлагается, вопрос цветом «внимание», «Ждёт: …» (почему не встало само —
 * `payload.held`), подсказка, цитата одной строкой, «похоже, это уже есть» с
 * «Это оно»; «Поставить» · «Поправить» · «Не надо» — или варианты кнопками.
 *
 * С 15.10.2026 (аудит №1, №25/№60, docs/dela-phone-21.md): у вопроса со своими
 * ответами (`payload.options`) — кнопки вариантов вместо «Да»; у вопроса с
 * `pick` и у двусмысленного имени «Да» нет вовсе (сервер его не примет);
 * у человека без вариантов — «Не для CRM» сразу, без листа причины.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    onAnswer: (DelaViews.Option) -> Unit = {},
    onRejectNow: (String) -> Unit = { onReject() },
    /**
     * Кандидат «Сейчас на завтра» (docs/dela-phone-27.md, `planItem` веба): строки пачки над ним нет (она — заголовок
     * пачки), вопроса `ask` нет (он — для телефона без блока), «похоже, это уже есть» и «Поправить» нет; название — тап в
     * карточку дела, под ним «почему · откуда».
     */
    plan: Boolean = false,
) {
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val txt = DelaViews.sugText(sg, snap, today)
    val q = if (plan) null else DelaViews.askWhy(sg, snap)
    val held = DelaViews.heldLine(sg, q)
    // У кандидата плана после полуночи «☆ На завтра» — уже «☆ На сегодня» (`planLabel` веба).
    val opts = DelaViews.options(sg, snap)?.let { if (DelaViews.isPlan(sg)) DelaViews.planOptions(it, today) else it }
    val choice = DelaViews.choiceOnly(sg)
    // Вопрос разборщика Круга (`askItem` веба): предложенные направления горят, тап — поправить; «Да» — выбранное.
    // С деревом (13.10.2026) разборщик предлагает и цель (`why_id`, путь — `goal_path` в подсказке строкой ниже).
    val krugDirs = DelaKrug.directions()
    val proposed = remember(sg.id) { Dela.run { sg.payloadObj().strings("directions") } }
    val proposedGoal = remember(sg.id) { Dela.run { sg.payloadObj().str("why_id") } }
    var chosen by remember(sg.id) { mutableStateOf(proposed) }
    // Свои ответы кнопками у вопроса разборщика — вместо чипов направлений (`kr` веба: только без вариантов).
    val krugAsk = sg.source == "krug" && DelaKrug.on(snap, krugDirs) && opts == null
    val twin = if (sg.source == "krug" || plan) null else DelaViews.twinOf(sg, snap, me)
    // Встреча — ссылкой: у дела её адрес в source_ref, у вопроса о человеке со встречи — в payload.speaker.url (№25).
    val link = DelaViews.sugLink(sg)
    // У кандидата плана название ведёт в карточку дела (`planItem` веба): отметить — здесь, разбить или отдать — там.
    val planTask = if (plan) snap.tasks[sg.taskId] else null
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (!plan) Row(
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
            modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(6.dp)).clickable(enabled = planTask != null) { planTask?.let(onOpen) },
        )
        if (q != null) Text(q, style = ty.label, fontWeight = FontWeight.SemiBold, color = ru.zf.pravka.ui.Ink.Warn)
        // Почему не встало само — словами сервера, без того, что уже сказал вопрос (`heldLine` веба).
        if (held != null) Text(held, style = ty.label, color = mode.label)
        if (txt.hint.isNotBlank()) Text(txt.hint, style = ty.label, color = mode.meta)
        if (txt.add.isNotBlank()) Text("+ ${txt.add}", style = ty.label, color = mode.label)
        // «▶ послушать» спикера встречи (`clipOf` веба, docs/dela-phone-26.md): 5–8 с его голоса — страница Встреч
        // браузером, как ссылка на встречу выше (вход — паролем Встреч, браузер его помнит).
        val voice = DelaViews.clipLink(sg)
        if (voice != null) PaperTextButton("Послушать", icon = Glyphs.Play, onClick = { openBrowser(context, voice) })
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
        // «Поправить» — у «завести» всегда, у «уточнить» — когда нужен выбор человека: кандидатов кнопками до трёх,
        // нужного среди них может не быть (сверка задания 21, п. 5).
        val canEdit = !plan && (sg.kind == "create" || (sg.kind == "update" && choice))
        if (opts != null) {
            // Свои ответы кнопками (`askItem` веба): первый — главной кнопкой, «нет»-варианты тише. Есть среди них «нет»
            // («Не для CRM», «Никто») — своего «Не надо» не нужно.
            val first = opts.firstOrNull { !it.reject }
            FlowRow(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp, Alignment.End),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                if (opts.none { it.reject }) PaperTextButton("Не надо", onClick = onReject)
                if (canEdit) PaperTextButton("Поправить", onClick = onEdit)
                for (o in opts) {
                    when {
                        o == first -> PaperButton(o.label, icon = Glyphs.Check, primary = true, onClick = { onAnswer(o) })
                        o.reject -> PaperTextButton(o.label, onClick = { onAnswer(o) })
                        else -> PaperButton(o.label, onClick = { onAnswer(o) })
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                // Человек без вариантов («Новый номер — завести?») — «Не для CRM» сразу: служба больше не спросит.
                if (sg.kind == "person") PaperTextButton("Не для CRM", onClick = { onRejectNow(DelaViews.NOT_CRM) })
                // Кандидат плана без кнопок сервера — «Не надо» с его причиной, без листа (`planItem` веба).
                else if (plan) PaperTextButton("Не надо", onClick = { onRejectNow(DelaViews.PLAN_NO) })
                else PaperTextButton("Не надо", onClick = onReject)
                if (canEdit) PaperTextButton("Поправить", onClick = onEdit)
                // «Да» — как есть (и без чипов, если предложена цель); поправил направление — значит, цель не та:
                // дело ляжет прямо на направление (`why_id: null`, как `askItem` веба).
                if (krugAsk) PaperButton("Да", icon = Glyphs.Check, primary = true, enabled = chosen.isNotEmpty() || proposedGoal.isNotBlank(), onClick = {
                    if (chosen == proposed) onAccept()
                    else onAcceptSet(org.json.JSONObject().put("directions", org.json.JSONArray(chosen)).put("why_id", org.json.JSONObject.NULL))
                })
                // Двусмысленный человек без кнопок: «Да» сервер не примет — выбор в «Поправить» или словами Claude.
                else if (!choice) PaperButton(DelaViews.askYes(sg), icon = Glyphs.Check, primary = true, onClick = onAccept)
            }
            if (choice) Text(
                when {
                    sg.kind == "create" -> "выбери человека в «Поправить» или скажи Claude"
                    canEdit -> "скажи Claude, кто это — или «Поправить»"
                    else -> "ответь в вебе Дел или скажи Claude"
                },
                style = ty.meta,
                color = mode.meta,
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

/**
 * Ссылка браузером («▶ послушать» спикера встречи): страница Встреч за её паролем — вход помнит браузер, как у
 * ссылки на встречу в шапке вопроса; не открылась — словами, а не молча.
 */
private fun openBrowser(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Feedback.toast(context, "Не открылось: ${it.message ?: it.javaClass.simpleName}") }
}

private fun stageWord(stage: String): String = ru.zf.pravka.core.DelaCrm.STAGE[stage] ?: stage

// ---------------------------------------------------------------- строки

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
