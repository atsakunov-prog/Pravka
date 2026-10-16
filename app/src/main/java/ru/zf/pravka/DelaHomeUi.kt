package ru.zf.pravka

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import ru.zf.pravka.core.Asks
import ru.zf.pravka.core.Dela
import ru.zf.pravka.core.DelaColumns
import ru.zf.pravka.core.DelaGroups
import ru.zf.pravka.core.DelaViews
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.Kv
import ru.zf.pravka.ui.KvGlyphs
import ru.zf.pravka.ui.KvShape
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.Look
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.RowRule
import kotlinx.coroutines.launch

// Дела в виде v2 (этап 4 «Правки 5.0», 13.10.2026, docs/dela-phone-19.md; лист «Дела» холста «Ковчег на
// телефоне», утверждён владельцем): главная «Сейчас» — блоки «Сейчас», «Новое», «Сегодня»; вкладки с чертой
// на сложенном и колонка разделов с числами на разложенном — как веб Дел (`renderNow`, боковая панель).
// Правила — в `core/DelaGroups.kt` и `core/DelaViews.kt`, здесь только как это выглядит.

/**
 * Главная «Сейчас» (`renderNow` веба; владелец: «Сейчас (то, что отметил) и Сегодня (с датой сегодня)»,
 * порядок — Сейчас → Новое → Сегодня): что владелец взял сам (до пяти, во всех сферах), вопросы автоматики
 * с теми же кнопками и П-номерами, что в «Новом», и дела со сроком сегодня без уже взятого. Просроченного
 * здесь нет — только его число ссылкой во «Все дела».
 */
internal class Home(
    val now: List<Dela.Task>,
    /** «Сегодня» без взятого в «Сейчас» — по группам. */
    val today: List<Dela.Task>,
    /** Сколько дел со сроком сегодня уже в «Сейчас» — подпись «что уже в «Сейчас» — выше». */
    val taken: Int,
    /** Вопросы «Подскажи» на экране в порядке показа — по ним П-номера (их видит Claude); свёрнуто — пусто. */
    val asks: List<Dela.Suggestion>,
    /** «Новое», которое ответа не ждёт: поставлено за три дня и сделано само — строкой-ссылкой. */
    val fed: Int,
    val auto: Int,
    /**
     * Дела внутри дел из «Сейчас» с глубиной (15.10.2026, `DelaSteps.inner`, `renderNow` веба): прямо над родителем
     * отступом, в предел пяти не считаются; в «Сегодня» ниже их нет.
     */
    val inner: List<Pair<Dela.Task, Int>> = emptyList(),
    /** «Сейчас на завтра» (docs/dela-phone-27.md): строкой под «Новым», тап — пачка кандидатов; null — строки нет. */
    val plan: DelaViews.Plan? = null,
    /**
     * «Подскажи» по фазе, как `newBox` веба (шаг 2 петли, docs/dela-phone-28.md; раскладку решает `Asks.homeCard` под
     * JVM-тестом): до 15:00 — строка «Подскажи · к 15:00: N · к вечеру: M», с 15:00 — сгорающее, вечером — группами.
     * Без флага `asks` — [Asks.Mode.LEGACY]: все вопросы, как раньше. null — вопросов нет.
     */
    val card: Asks.Card? = null,
    /** Тап по свёрнутой строке — раскрыть до смены фазы (ключ — `Asks.Queue.openKey`). */
    val onOpen: () -> Unit = {},
    /** Очередь, из которой разложен [card], — снимку «Что не так?» (фаза, строка, группы; поезд 16.10.2026). */
    val queue: Asks.Queue? = null,
)

/** Значок набора Ковчега по имени веба; нет такого — запасной штрих приложения. */
private fun kv(key: String, fallback: ImageVector): ImageVector = KvGlyphs.byKey(key) ?: fallback

/** Значок раздела Дел — как в боковой панели веба и на холсте. */
internal fun viewIcon(v: DelaViews.View): ImageVector = when (v) {
    DelaViews.View.NOW -> Glyphs.Bolt
    DelaViews.View.TODAY -> Glyphs.Sunny
    DelaViews.View.NEW -> kv("inbox", Glyphs.Groups)
    DelaViews.View.ALL -> Glyphs.ListLines
    DelaViews.View.KRUG -> kv("target", Glyphs.Layers)
    DelaViews.View.TREE -> kv("tree", Glyphs.Layers)
}

/**
 * Вкладка ряда: ключ раздела и название; [full] — полное название для чтения с экрана («Все дела»). Чисел у вкладок
 * нет с 14.10.2026 (владелец: «Давай уберём эти количества дел везде, потому что они съедают пространство»):
 * просроченное видно строкой «просрочено N — во «Всех делах»» под вкладками и в шапке.
 */
internal data class DelaTabItem(val key: String, val title: String, val full: String = title)

/**
 * Вкладки с чертой (`.tabs` холста): Сейчас · Сегодня · Новое · Все дела, у выбранной — черта акцентом и
 * слово цветом текста, без чисел (14.10.2026); ряд листается вбок, если не влез. Остальные разделы, фильтры, CRM, проекты и люди — в
 * боковине (язычок у левого края, `SideGrip`): «☰» в ряду с 13.10.2026 нет.
 */
@Composable
internal fun DelaTabsV2(tabs: List<DelaTabItem>, selected: String, onSelect: (String) -> Unit) {
    val t = LocalPravkaType.current
    val line = Kv.line
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val w = 1.dp.toPx()
                drawRect(line, topLeft = Offset(0f, size.height - w), size = Size(size.width, w))
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.Bottom) {
            for (tab in tabs) {
                val on = tab.key == selected
                val accent = Kv.accent
                Row(
                    Modifier
                        .clip(RoundedCornerShape(topStart = KvShape.Tag, topEnd = KvShape.Tag))
                        .clickable(role = Role.Tab, onClickLabel = tab.full, onClick = { onSelect(tab.key) })
                        .semantics { contentDescription = tab.full }
                        .drawBehind {
                            if (on) {
                                val h = 2.dp.toPx()
                                drawRect(accent, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
                            }
                        }
                        .padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        tab.title,
                        style = t.body.copy(fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                        color = if (on) Kv.text else Kv.muted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Хвост дня мелкой строкой внизу «Сейчас» и «Сегодня» (`DelaViews.tail`; отладка 16.10.2026, запись 15 — до этого
 * «просрочено N» и «ждут от тебя без срока / жду больше недели» стояли крупно наверху, над делами): ссылками —
 * просроченное во «Все дела» (цветом беды, без жирного), «ждут» и «жду» — фильтры по людям (акцентом). Строкой с
 * переносом: на узком экране части уходят вниз, а не сжимаются.
 */
@Composable
internal fun TailLinks(parts: List<Pair<String, DelaViews.Tail>>, onTail: (DelaViews.Tail) -> Unit) {
    if (parts.isEmpty()) return
    val t = LocalPravkaType.current
    val mode = ru.zf.pravka.ui.LocalMode.current
    androidx.compose.foundation.layout.FlowRow(Modifier.padding(start = 6.dp, top = 6.dp)) {
        parts.forEachIndexed { i, (word, kind) ->
            if (i > 0) Text(" · ", style = t.meta, color = if (Look.v2) Kv.faint else mode.meta, modifier = Modifier.padding(vertical = 6.dp))
            Text(
                "$word ›",
                style = t.meta,
                color = when {
                    kind == DelaViews.Tail.LATE -> if (Look.v2) Kv.bad else mode.value
                    Look.v2 -> Kv.accent
                    else -> mode.label
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(KvShape.Tag))
                    .clickable(role = Role.Button) { onTail(kind) }
                    .padding(vertical = 6.dp),
            )
        }
    }
}

/**
 * Блок главной (`.card` и `.sec` холста): значок, название, мелкая подпись (не влезла — второй строкой) — и дела
 * панелью с линией. [onTitle] — заголовок ведёт в свой раздел («Сегодня» → «Сегодня»). Числа-плашки у названия нет с
 * 14.10.2026 («в плашках номера» — владелец, «уберём эти количества дел везде»); [count] остался для
 * «Вида 4.0»: там та же плашка `PaperCard`, что у остальных групп, с «Сейчас · 3» в заголовке — как была.
 */
@Composable
internal fun HomeBlock(
    icon: ImageVector,
    title: String,
    count: Int,
    sub: String,
    onTitle: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!Look.v2) {
        PaperCard(
            label = "$title · $count",
            trailing = trailing?.let { tr -> { Row(verticalAlignment = Alignment.CenterVertically, content = tr) } },
        ) {
            if (sub.isNotBlank()) PaperHint(sub)
            content()
        }
        return
    }
    val t = LocalPravkaType.current
    val shape = RoundedCornerShape(KvShape.Panel)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Kv.panel)
            .border(1.dp, Kv.line, shape)
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(KvShape.Control))
                .then(if (onTitle != null) Modifier.clickable(role = Role.Button, onClickLabel = "открыть раздел «$title»", onClick = onTitle) else Modifier)
                .padding(top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = Kv.icon, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, style = t.bodyStrong, color = Kv.text, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            // Подпись — переносом, а не «…» (владелец 14.10.2026: «эти многоточия очень сильно не нравятся»).
            Text(sub, style = t.meta, color = Kv.faint, modifier = Modifier.weight(1f))
            if (onTitle != null) Icon(Glyphs.Forward, contentDescription = null, tint = Kv.faint, modifier = Modifier.size(16.dp))
            trailing?.invoke(this)
        }
        CompositionLocalProvider(LocalContentColor provides Kv.text) { content() }
    }
}

/** Тихая строка внутри блока: «Пусто…», подсказка под делами (`.xs.faint` холста). */
@Composable
private fun BlockNote(text: String, top: Boolean = false) {
    val t = LocalPravkaType.current
    Text(
        text,
        style = t.meta,
        color = if (Look.v2) Kv.faint else ru.zf.pravka.ui.LocalMode.current.meta,
        modifier = Modifier.padding(top = if (top) 6.dp else 2.dp, bottom = 2.dp),
    )
}

/**
 * Главная «Сейчас» в ленте: «Сейчас» (что взял сам, до пяти), «Новое» — вопросы «Подскажи» с кнопками и
 * П-номерами «Нового» (остальное «Нового» — строкой-ссылкой: оно ответа не ждёт), «Сегодня» — срок сегодня
 * без уже взятого, тремя группами. «Остального» (просроченного, «Моё», «Поставил») здесь нет.
 */
internal fun LazyListScope.homeView(
    h: Home,
    crm: DelaCrmContext,
    actions: DelaActions,
    today: String,
    onNew: () -> Unit,
    onToday: () -> Unit,
    onAccept: (List<Dela.Suggestion>) -> Unit,
    onRejectAll: (List<Dela.Suggestion>) -> Unit,
    onEdit: (Dela.Suggestion) -> Unit,
    onReject: (Dela.Suggestion) -> Unit,
    onMerge: (Dela.Suggestion, Dela.Task) -> Unit,
    onAcceptSet: (Dela.Suggestion, org.json.JSONObject) -> Unit,
    onAnswer: (Dela.Suggestion, DelaViews.Option) -> Unit = { _, _ -> },
    onRejectNow: (Dela.Suggestion, String) -> Unit = { sg, _ -> onReject(sg) },
    /** Строка «Сейчас на завтра» — открыть пачку `plan:<день>`. */
    onPlan: (String) -> Unit = {},
) {
    item(key = "home:now") {
        HomeBlock(Glyphs.Bolt, "Сейчас", h.now.size, sub = "отметил сам — сделать сегодня") {
            if (h.now.isEmpty()) {
                BlockNote("Пусто. Возьми до ${DelaViews.NOW_MAX} дел на сегодня — молния у дела или в его карточке, «!сейчас» в строке.")
            } else {
                // Жирным здесь не выделяем: весь блок — «Сейчас». Дела внутри — прямо над родителем, отступом.
                if (h.inner.isEmpty()) TaskRows(h.now, actions, today, nowBold = false)
                else TaskRows(
                    ru.zf.pravka.core.DelaSteps.stepsFirst(h.now + h.inner.map { it.first }, crm.snap), actions, today, nowBold = false,
                    indent = h.inner.associate { it.first.id to it.second },
                )
                if (h.now.size < DelaViews.NOW_MAX) {
                    BlockNote("Ещё ${DelaViews.NOW_MAX - h.now.size} — молнией у дела или в его карточке", top = true)
                }
            }
        }
    }
    // Строка вопроса — та же, что в «Новом»; П-номер — место на экране (h.asks — в порядке показа).
    val ask: @Composable (Dela.Suggestion, Int) -> Unit = { sg, i ->
        AskRow(
            sg, i + 1, crm.snap, crm.me, today,
            onAccept = { onAccept(listOf(sg)) }, onEdit = { onEdit(sg) }, onReject = { onReject(sg) },
            onOpen = actions.open, onMerge = { t -> onMerge(sg, t) },
            onAcceptSet = { set -> onAcceptSet(sg, set) },
            onAnswer = { o -> onAnswer(sg, o) },
            onRejectNow = { reason -> onRejectNow(sg, reason) },
        )
    }
    // «Все» — под вопросами, а не в заголовке: там они съедали подпись блока. Только когда у каждого вопроса ответ —
    // одно «Да» (как веб): при вариантах или выборе человека «Поставить все» соврало бы.
    val bulk: @Composable (List<Dela.Suggestion>) -> Unit = { list ->
        if (DelaViews.bulkOk(list, crm.snap)) Row {
            Spacer(Modifier.weight(1f))
            PaperTextButton("Не надо все", onClick = { onRejectAll(list) })
            PaperTextButton("Поставить все", onClick = { onAccept(list) })
        }
    }
    val card = h.card
    when (card?.mode) {
        null -> Unit
        // Без флага `asks` у сервера — как раньше: «Новое», все вопросы.
        Asks.Mode.LEGACY -> item(key = "home:new") {
            HomeBlock(kv("inbox", Glyphs.Groups), "Новое", h.asks.size, sub = "подскажи — без тебя не поставлю", onTitle = onNew) {
                h.asks.forEachIndexed { i, sg ->
                    if (i > 0) RowRule()
                    ask(sg, i)
                }
                bulk(h.asks)
            }
        }
        // Шаг 2 петли (флаг `asks`): до 15:00, с 15:00 без сгорающего и в тишине выходных — одна строка (`asksPhased` веба).
        Asks.Mode.FOLD -> item(key = "home:new") { AsksFoldLine(card.fold.orEmpty(), lead = true, onOpen = h.onOpen) }
        // С 15:00 — только сгорающее (люди и договорённости сегодняшних встреч), под ним «к вечеру: M».
        Asks.Mode.URGENT -> item(key = "home:new") {
            Column(Modifier.fillMaxWidth()) {
                HomeBlock(
                    kv("help", Glyphs.Ask), "Подскажи", card.count, sub = "сгорает сегодня — люди и договорённости сегодняшних встреч",
                    onTitle = onNew,
                ) {
                    h.asks.forEachIndexed { i, sg ->
                        if (i > 0) RowRule()
                        ask(sg, i)
                    }
                    bulk(h.asks)
                    card.fold?.let {
                        RowRule()
                        AsksFoldLine(it, lead = false, onOpen = h.onOpen)
                    }
                }
                if (card.more > 0) NewLine("ещё сгорает ${card.more} — в «Новом»", onNew)
            }
        }
        // Вечер (или раскрыто тапом) — всё группами «ООО Ромашка · 2», до `evening_max`, дальше «ещё K — в «Новом»».
        Asks.Mode.GROUPED -> item(key = "home:new") {
            Column(Modifier.fillMaxWidth()) {
                HomeBlock(kv("help", Glyphs.Ask), "Подскажи", card.count, sub = card.sub.orEmpty(), onTitle = onNew) {
                    var i = 0
                    card.groups.forEachIndexed { gi, (g, items) ->
                        GroupHead(Asks.groupHead(g), top = gi > 0, hot = Asks.groupUrgent(g))
                        items.forEachIndexed { k, sg ->
                            if (k > 0) RowRule()
                            ask(sg, i++)
                        }
                    }
                    bulk(h.asks)
                }
                if (card.more > 0) NewLine("ещё ${card.more} — в «Новом»", onNew)
            }
        }
    }
    val rest = listOfNotNull(
        "добавлено за ${DelaViews.FEED_DAYS} дня: ${h.fed}".takeIf { h.fed > 0 },
        "сделано само: ${h.auto}".takeIf { h.auto > 0 },
    ).joinToString(" · ")
    if (rest.isNotBlank()) {
        item(key = "home:newlink") {
            NewLine(if (h.card == null) "Новое: вопросов нет · $rest" else "Всё «Новое»: $rest", onNew)
        }
    }
    // «Сейчас на завтра» — под «Новым» (судья §3.3, `planBox` веба): сначала ответить на вопросы, потом план. Кандидаты
    // не занимают строк «Нового» — одна строка, тап — их пачка с кнопками «☆ На завтра · ✓ Уже сделал · Не надо».
    h.plan?.let { p ->
        item(key = "home:plan") { PlanLine(DelaViews.planLine(p), onClick = { onPlan(p.batch) }) }
    }
    item(key = "home:today") {
        HomeBlock(
            Glyphs.Sunny, "Сегодня", h.today.size,
            sub = "срок сегодня" + if (h.taken > 0) " · что уже в «Сейчас» — выше" else "",
            onTitle = onToday,
        ) {
            if (h.today.isEmpty()) {
                BlockNote(if (h.taken > 0) "Остальное со сроком на сегодня — уже в «Сейчас»." else "Со сроком на сегодня — ничего.")
            } else {
                // Три группы подзаголовками внутри одной панели: сам блок — первый уровень, группы — второй.
                val nodes = DelaGroups.nested(h.today, listOf(DelaGroups.Level.GRP), crm.snap, today)
                nodes.forEachIndexed { i, n ->
                    GroupHead("${n.title} · ${n.items.size}", top = i > 0)
                    TaskRows(n.items, actions, today, by = DelaViews.By.GRP)
                }
            }
        }
    }
}

/** Подзаголовок группы внутри блока: «Отбить · 2»; у группы «Подскажи» — сгорающее в ней «· сгорает 1» тоном внимания. */
@Composable
private fun GroupHead(text: String, top: Boolean, hot: String? = null) {
    val t = LocalPravkaType.current
    val ink = if (Look.v2) Kv.muted else ru.zf.pravka.ui.LocalMode.current.label
    Text(
        androidx.compose.ui.text.buildAnnotatedString {
            append(text)
            if (hot != null) {
                pushStyle(androidx.compose.ui.text.SpanStyle(color = if (Look.v2) Kv.warn else ink))
                append(" · $hot")
                pop()
            }
        },
        style = t.label.copy(fontWeight = FontWeight.SemiBold),
        color = ink,
        modifier = Modifier.padding(top = if (top) 10.dp else 2.dp, bottom = 2.dp),
    )
}

/**
 * Свёрнутая очередь «Подскажи» одной строкой (`.asks-fold` веба): «Подскажи · к 15:00: 1 · к вечеру: 6» (или «к
 * вечеру: M» под сгорающим) и «раскрыть» — тап раскрывает всё до смены фазы. [lead] — значок слева, когда строка —
 * весь блок.
 */
@Composable
private fun AsksFoldLine(text: String, lead: Boolean, onOpen: () -> Unit) {
    val t = LocalPravkaType.current
    val v2 = Look.v2
    val ink = if (v2) Kv.text else ru.zf.pravka.ui.LocalMode.current.label
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KvShape.Control))
            .clickable(role = Role.Button, onClickLabel = "раскрыть «Подскажи»", onClick = onOpen)
            .padding(horizontal = if (lead) 8.dp else 0.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (lead) {
            Icon(kv("help", Glyphs.Ask), contentDescription = null, tint = if (v2) Kv.warn else ink, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(ru.zf.pravka.core.HeadLine.wrap(text), style = t.label, color = ink, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        Text("раскрыть", style = t.label, color = if (v2) Kv.accent else ink, maxLines = 1)
    }
}

/** Строка-ссылка в «Новое» (`.new-link` веба): значок, слова, стрелка. */
@Composable
private fun NewLine(text: String, onClick: () -> Unit) = LinkLine(kv("inbox", Glyphs.Groups), text, onClick)

/**
 * Строка «Сейчас на завтра: предложено 7 · отмечено 2 из 5 ›» (docs/dela-phone-27.md) — в «Сейчас» Дел под «Новым» и
 * сверху «Нового»: молния, слова, стрелка; тап — пачка кандидатов. Акцентом: это выбор на завтра, а не справка.
 */
@Composable
internal fun PlanLine(text: String, onClick: () -> Unit) = LinkLine(Glyphs.Bolt, text, onClick, accent = true)

/** Строка-ссылка блока: значок, слова переносом, стрелка; [accent] — краской акцента («Сейчас на завтра»). */
@Composable
private fun LinkLine(icon: ImageVector, text: String, onClick: () -> Unit, accent: Boolean = false) {
    val t = LocalPravkaType.current
    val v2 = Look.v2
    val ink = if (v2) (if (accent) Kv.accent else Kv.muted) else ru.zf.pravka.ui.LocalMode.current.label
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KvShape.Control))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (v2) (if (accent) Kv.accent else Kv.icon) else ink, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        // v2 — переносом по словам целиком, без «…» (14.10.2026: «Всё «Новое»: добавлено за 3 дня…» на сложенном
        // обрезалось посреди счёта); «Вид 4.0» — как был.
        if (v2) Text(ru.zf.pravka.core.HeadLine.wrap(text), style = t.label, color = ink, modifier = Modifier.weight(1f))
        else Text(text, style = t.label, color = ink, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Icon(Glyphs.Forward, contentDescription = null, tint = if (v2) Kv.faint else ink, modifier = Modifier.size(16.dp))
    }
}

// ---------------------------------------------------------------- колонка разделов

/**
 * Строка колонки разделов (`.dside a` холста): значок и название; выбранная — мягким акцентом. С 14.10.2026 без
 * числа (владелец: «Фильтры не до конца влезают, и эти многоточия очень сильно не нравятся… плашки с номерами в
 * клиентах не нужно»): длинное название не режется «…», а переносится — значок остаётся у первой строки.
 */
@Composable
private fun NavRow(icon: ImageVector, title: String, selected: Boolean, onClick: () -> Unit) {
    val t = LocalPravkaType.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Kv.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Значок — по середине первой строки (18 sp), а не всего пункта: у пункта в две строки он стоит у начала.
        Icon(icon, contentDescription = null, tint = if (selected) Kv.accent else Kv.icon, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = t.body.copy(fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (selected) Kv.accent else Kv.muted,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Надстрочник колонки (`.eyebrow` холста): «Фильтры», «CRM», «Клиенты». */
@Composable
private fun NavEyebrow(text: String) {
    Text(
        text,
        style = LocalPravkaType.current.overline,
        color = Kv.faint,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 3.dp),
    )
}

/**
 * Надстрочник-переключатель группы (свернуть или раскрыть): «Клиенты ›». [trailing] — действие справа. Числа нет
 * с 14.10.2026, как у пунктов: в узкой боковине оно выталкивало «раскрыть» за край. Действие меряется первым,
 * название переносится, если места не хватило, — ничего не режется.
 */
@Composable
private fun NavFold(text: String, open: Boolean, onToggle: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(7.dp))
            .clickable(role = Role.Button, onClickLabel = if (open) "свернуть «$text»" else "раскрыть «$text»", onClick = onToggle)
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = LocalPravkaType.current.overline, color = Kv.faint, modifier = Modifier.weight(1f, fill = false))
            Icon(
                KvGlyphs.byKey("chevron") ?: Glyphs.Forward,
                contentDescription = null,
                tint = Kv.faint,
                modifier = Modifier.padding(start = 4.dp).size(13.dp).rotate(if (open) 90f else 0f),
            )
        }
        trailing?.invoke()
    }
}

/** Проект клиента под ним (`side-deals` веба): квадратик цвета стадии и короткое имя — без числа, с переносом. */
@Composable
private fun NavDealRow(d: ru.zf.pravka.core.DelaCrm.NavDeal, selected: Boolean, onClick: () -> Unit) {
    val t = LocalPravkaType.current
    val stage = stageColor(d.deal.stage)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Kv.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 32.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 5.dp).size(7.dp).clip(RoundedCornerShape(2.dp)).background(stage))
        Spacer(Modifier.width(8.dp))
        Text(
            d.short,
            style = t.body.copy(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (selected) Kv.accent else Kv.muted,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Что показывает боковина Дел — одно на разложенный (колонкой) и сложенный (шторкой). */
internal class DelaSideModel(
    val nav: String,
    val views: List<DelaViews.View>,
    val filters: List<DelaGroups.Filter>,
    val filterNav: String,
    val crm: List<Pair<String, String>>,
    val projects: Dela.ProjectsNav,
    /** Есть CRM — люди живут в разделе «Люди», отдельного списка людей в боковине нет (как в вебе). */
    val crmOn: Boolean,
    val sphere: String,
    val spheres: List<Pair<String, String>>,
    val query: String,
    val clientOpen: Set<String>,
    val hereClient: String,
    val hereDeal: String,
    val clientDeals: (String) -> List<ru.zf.pravka.core.DelaCrm.NavDeal>,
)

/** Что умеет боковина: перейти в раздел, на страницу, поискать, сменить сферу, раскрыть клиентов. */
internal class DelaSideActions(
    val onNav: (String) -> Unit,
    val onQuery: (String) -> Unit,
    val onProject: (String) -> Unit,
    val onPerson: (String) -> Unit,
    val onDeal: (String) -> Unit,
    val onSphere: (String) -> Unit,
    val onClientOpen: (Collection<String>, Boolean) -> Unit,
    /** Поиск отправлен клавишей: на сложенном шторка уезжает — находки под ней (как Enter в поиске веба). */
    val onSubmit: () -> Unit = {},
    /** «Отладка» — записки режима отладки (DelaDebugUi.kt); null — пункта нет (не владелец или сервер старый). */
    val onDebug: (() -> Unit)? = null,
)

/**
 * Боковина Дел (`.dside` холста и боковая колонка веба): поиск, разделы (Сейчас, Сегодня, Новое, Все дела, Круг и
 * дерево жизни), фильтры, CRM, избранное, проекты по видам (у клиента — его проекты под стрелкой, «раскрыть все»),
 * люди, архив и сфера. Одна на разложенный — колонкой (с завода 172 dp, ширину тянут язычком), и сложенный —
 * шторкой поверх (13.10.2026, владелец: «боковина, которая вылезает и скрывается»); то, что раньше открывал «☰», —
 * теперь здесь. Чисел у пунктов нет с 14.10.2026: они съедали ширину, и названия резались «…».
 */
@Composable
internal fun DelaSide(m: DelaSideModel, a: DelaSideActions, modifier: Modifier = Modifier) {
    val t = LocalPravkaType.current
    Column(modifier.verticalScroll(rememberScrollState()).padding(start = 10.dp, end = 6.dp, top = 2.dp, bottom = 28.dp)) {
        // Поиск (`.dfind`): набрал — список показывает находки, как поиск веба.
        val shape = RoundedCornerShape(KvShape.Control)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .clip(shape)
                .background(Kv.panel)
                .border(1.dp, Kv.line, shape)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Glyphs.Search, contentDescription = null, tint = Kv.faint, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (m.query.isEmpty()) Text("Поиск", style = t.body.copy(fontSize = 13.5.sp), color = Kv.faint, maxLines = 1)
                BasicTextField(
                    value = m.query,
                    onValueChange = a.onQuery,
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { a.onSubmit() }),
                    textStyle = t.body.copy(fontSize = 13.5.sp, color = Kv.text),
                    cursorBrush = SolidColor(Kv.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (m.query.isNotEmpty()) GlyphButton(Glyphs.Close, "закрыть поиск", onClick = { a.onQuery("") }, tint = Kv.faint, size = 24.dp)
        }
        for (v in m.views) {
            NavRow(viewIcon(v), v.title, selected = m.nav == v.key) { a.onNav(v.key) }
        }
        a.onDebug?.let { open -> NavRow(Glyphs.Bug, "Отладка", selected = false, onClick = open) }
        if (m.filters.isNotEmpty()) {
            NavEyebrow("Фильтры")
            for (f in m.filters) {
                NavRow(kv("bookmark", Glyphs.ListLines), f.name, selected = m.nav == m.filterNav + f.id) {
                    a.onNav(m.filterNav + f.id)
                }
            }
        }
        if (m.crm.isNotEmpty()) {
            NavEyebrow("CRM")
            for ((k, title) in m.crm) {
                val icon = when (k) {
                    "crm" -> kv("funnel", Glyphs.Layers)
                    "clients" -> kv("briefcase", Glyphs.Layers)
                    "people" -> kv("users", Glyphs.Groups)
                    else -> kv("link", Glyphs.Link)
                }
                NavRow(icon, title, selected = m.nav == k) { a.onNav(k) }
            }
        }
        // Проекты — строкой, клиент — со своими проектами под стрелкой (`projItem` веба).
        val row: @Composable (Dela.ProjectRow) -> Unit = { r ->
            val deals = if (r.project.kind == "client") m.clientDeals(r.project.id) else emptyList()
            val icon = if (r.project.kind == "client") kv("briefcase", Glyphs.Layers) else kv("folder", Glyphs.ListLines)
            if (deals.isEmpty()) {
                NavRow(icon, r.project.name, selected = false) { a.onProject(r.project.id) }
            } else {
                val open = r.project.id in m.clientOpen || r.project.id == m.hereClient
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f)) {
                        NavRow(icon, r.project.name, selected = false) { a.onProject(r.project.id) }
                    }
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(KvShape.Tag))
                            .clickable(
                                role = Role.Button,
                                onClickLabel = if (open) "свернуть проекты" else "проекты клиента: ${deals.size}",
                                onClick = { a.onClientOpen(listOf(r.project.id), !open) },
                            )
                            .semantics { contentDescription = if (open) "свернуть проекты" else "проекты клиента: ${deals.size}" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            KvGlyphs.byKey("chevron") ?: Glyphs.Forward,
                            contentDescription = null,
                            tint = Kv.faint,
                            modifier = Modifier.size(14.dp).rotate(if (open) 90f else 0f),
                        )
                    }
                }
                if (open) for (d in deals) NavDealRow(d, selected = d.deal.id == m.hereDeal) { a.onDeal(d.deal.id) }
            }
        }
        if (m.projects.favorites.isNotEmpty()) {
            NavEyebrow("Избранное")
            for (r in m.projects.favorites) row(r)
        }
        for ((title, rows) in m.projects.groups) {
            var open by rememberSaveable("side:$title") { mutableStateOf(true) }
            // «Раскрыть все / свернуть все» — проекты всех клиентов разом (`openAllBtn` веба).
            val withDeals = rows.filter { it.project.kind == "client" && m.clientDeals(it.project.id).isNotEmpty() }.map { it.project.id }
            NavFold(title, open, onToggle = { open = !open }, trailing = if (open && withDeals.isNotEmpty()) {
                {
                    val allOpen = withDeals.all { it in m.clientOpen }
                    Text(
                        // Колонка узкая (с завода 172 dp): видно одно слово, целиком — для чтения с экрана.
                        if (allOpen) "свернуть" else "раскрыть",
                        style = t.meta,
                        color = Kv.faint,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(KvShape.Tag))
                            .clickable(role = Role.Button) { a.onClientOpen(withDeals, !allOpen) }
                            .semantics { contentDescription = if (allOpen) "свернуть все" else "раскрыть все" }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            } else null)
            if (open) for (r in rows) row(r)
        }
        // С CRM люди — разделом «Люди» по компаниям; без неё — с кем больше открытых дел.
        if (m.projects.people.isNotEmpty() && !m.crmOn) {
            var open by rememberSaveable("side:people") { mutableStateOf(false) }
            NavFold("Люди", open, onToggle = { open = !open })
            if (open) for ((p, _) in m.projects.people) {
                NavRow(kv("user", Glyphs.Groups), p.label, selected = false) { a.onPerson(p.id) }
            }
        }
        if (m.projects.archived.isNotEmpty()) {
            var open by rememberSaveable("side:arch") { mutableStateOf(false) }
            NavFold("Архив", open, onToggle = { open = !open })
            if (open) for (r in m.projects.archived) row(r)
        }
        // Сфера (поле `sphere` проекта; дела без проекта видны в любой) — словами, выбранная мягким акцентом.
        NavEyebrow("Сфера")
        // Строкой с переносом: в узкой (140 dp) боковине «Всё» уходит вниз, а не сжимается в столбик.
        androidx.compose.foundation.layout.FlowRow(
            Modifier.padding(start = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for ((k, title) in m.spheres) {
                val on = m.sphere == k
                Text(
                    title,
                    style = t.body.copy(fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (on) Kv.accent else Kv.muted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (on) Kv.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(role = Role.RadioButton) { a.onSphere(k) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }
    }
}

/** Сторона колонки: боковина — слева, карточка дела — справа; язычки у них зеркальные. */
internal enum class GripEdge { START, END }

/** Что язычок умеет пальцем, кроме тапа: тянуть ширину колонки и шаг «шире / уже» для чтения с экрана. */
internal class GripDrag(
    val onStart: () -> Unit,
    /** Сдвиг пальца в dp: плюс — колонка шире (у карточки это движение влево). */
    val onDrag: (Float) -> Unit,
    val onEnd: () -> Unit,
    val onStep: (Float) -> Unit,
)

/** Язычок в щели между колонками — как на холсте («Боковина Дел», `.dgrip`): 18×56. */
private val GRIP_W = 18.dp
private val GRIP_H = 56.dp
/**
 * В доке у края — крупнее (владелец 14.10.2026: «когда колонка сжата, стрелочка должна быть по горизонтали
 * побольше — иначе не каждый раз пальцем получается нажать, слишком надо целиться у края экрана»).
 */
internal val GRIP_DOCK_W = 24.dp
private val GRIP_DOCK_H = 64.dp
/** Зона касания шире и выше видимого: палец попадает в неё, а не в край корпуса. */
private val GRIP_ZONE_W = 36.dp
private val GRIP_ZONE_H = 76.dp

/**
 * Язычок колонки (14.10.2026, одна механика у боковины и карточки; владелец: «если я держу эту кнопку, она двигает
 * ширину столбца… И то же самое с правой колонкой»). Тап — спрятать или показать колонку; удержание и протяжка
 * ([drag]) — менять её ширину, отпустил у самого края — колонка уходит в док. [at] — ширина колонки сейчас (с
 * анимацией): пока она открыта, язычок стоит по середине щели между ней и списком, ушла — прижат к краю экрана,
 * крупнее, и зона касания заходит на список. Шеврон смотрит, куда поедет колонка. Жеста от края нет — он спорит
 * с системным «назад».
 *
 * Палец — одно решение `core/GripGesture.kt` (отладка 16.10.2026, запись 9: «держу стрелку — колонки не
 * двигаются»): у `combinedClickable` после долгого нажатия (foundation 1.9.4) движение до отпускания съедалось, и
 * `draggable` рядом отменял протяжку — вибрация была, колонка стояла. Шторка разделов на сложенном ([drag] = null) —
 * на прежнем `combinedClickable`: протяжки у неё нет.
 */
@Composable
internal fun ColumnGrip(
    edge: GripEdge,
    at: Dp,
    open: Boolean,
    label: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    dockW: Dp = GRIP_DOCK_W,
    zoneW: Dp = GRIP_ZONE_W,
    drag: GripDrag? = null,
) {
    val docked = at < 4.dp
    val start = edge == GripEdge.START
    val visW = if (docked) dockW else GRIP_W
    val zone = max(zoneW, visW)
    // Открыта — видимая часть по середине щели (и не уходит за край, пока колонка уезжает), зона — вокруг неё.
    // В доке зона идёт от края экрана внутрь.
    val x = if (docked) 0.dp else (at - GRIP_W / 2).coerceAtLeast(0.dp) - (zone - GRIP_W) / 2
    val shape = when {
        !docked -> RoundedCornerShape(8.dp)
        start -> RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
        else -> RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
    }
    val haptic = LocalHapticFeedback.current
    // Жест живёт дольше перерисовки, а `grab(edge)` в DelaColumnsRow отдаёт новый GripDrag на каждой: читать свежие.
    val dragNow by rememberUpdatedState(drag)
    val toggleNow by rememberUpdatedState(onToggle)
    Box(
        modifier
            .offset(x = if (start) x else -x)
            .size(zone, GRIP_ZONE_H)
            .then(
                if (drag == null) Modifier.combinedClickable(role = Role.Button, onClickLabel = label, onClick = onToggle)
                else Modifier.pointerInput(start) {
                    gripPointer(
                        sign = if (start) 1f else -1f,
                        drag = { dragNow },
                        onToggle = { toggleNow() },
                        // Удержание — отклик пальцу «держу, можно тянуть»; отпустил не сдвинув — колонка как была.
                        onHeld = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                    )
                },
            )
            .semantics {
                contentDescription = label
                if (drag != null) {
                    // Тап для чтения с экрана — руками: `pointerInput` его не объявляет (у `combinedClickable` был сам).
                    role = Role.Button
                    onClick(label) { toggleNow(); true }
                    if (open) customActions = listOf(
                        CustomAccessibilityAction("Шире") { drag.onStep(DelaColumns.STEP); true },
                        CustomAccessibilityAction("Уже") { drag.onStep(-DelaColumns.STEP); true },
                    )
                }
            },
        contentAlignment = when {
            !docked -> Alignment.Center
            start -> Alignment.CenterStart
            else -> Alignment.CenterEnd
        },
    ) {
        Box(
            Modifier
                // В доке — на 1 dp за край: линия у края уходит за экран, как `border-left: 0` холста.
                .then(if (docked) Modifier.offset(x = if (start) (-1).dp else 1.dp) else Modifier)
                .size(visW + if (docked) 1.dp else 0.dp, if (docked) GRIP_DOCK_H else GRIP_H)
                .clip(shape)
                .background(Kv.panel)
                .border(1.dp, Kv.line, shape),
            contentAlignment = Alignment.Center,
        ) {
            // Боковина открыта — ‹ (уедет влево), спрятана — ›; у карточки справа — наоборот.
            val left = if (start) open else !open
            Icon(
                KvGlyphs.byKey("chevron") ?: Glyphs.Forward,
                contentDescription = null,
                tint = Kv.muted,
                modifier = Modifier
                    .padding(start = if (docked && start) 1.dp else 0.dp, end = if (docked && !start) 1.dp else 0.dp)
                    .size(if (docked) 18.dp else 15.dp)
                    .rotate(if (left) 180f else 0f),
            )
        }
    }
}

/**
 * Палец на язычке разворота — по `GripGesture`: тап, удержание (вибрация), протяжка сразу или после удержания.
 * [sign] — куда шире колонка: у боковины слева палец вправо (+1), у карточки справа — влево (−1). Жест оборвался
 * посреди протяжки (сложили телефон, ушли с экрана) — `onEnd` всё равно: иначе `held` в DelaColumnsRow залипнет.
 */
private suspend fun PointerInputScope.gripPointer(
    sign: Float,
    drag: () -> GripDrag?,
    onToggle: () -> Unit,
    onHeld: () -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val g = ru.zf.pravka.core.GripGesture(slop = viewConfiguration.touchSlop.toDp().value)
    val longAt = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
    var now = down.uptimeMillis
    var ended = false
    fun act(a: ru.zf.pravka.core.GripGesture.Act) {
        when (a) {
            ru.zf.pravka.core.GripGesture.Act.Held -> onHeld()
            is ru.zf.pravka.core.GripGesture.Act.Start -> drag()?.let { it.onStart(); it.onDrag(sign * a.dx) }
            is ru.zf.pravka.core.GripGesture.Act.Drag -> drag()?.onDrag(sign * a.dx)
            ru.zf.pravka.core.GripGesture.Act.End -> drag()?.onEnd()
            ru.zf.pravka.core.GripGesture.Act.Toggle -> onToggle()
            ru.zf.pravka.core.GripGesture.Act.None -> {}
        }
    }
    try {
        while (true) {
            // Пока палец стоит — ждём событие не дольше, чем до долгого нажатия; вышло время — «держу».
            val ev = if (g.waiting) {
                val left = longAt - now
                if (left <= 0) null else withTimeoutOrNull(left) { awaitPointerEvent() }
            } else awaitPointerEvent()
            if (ev == null) {
                act(g.longPress())
                continue
            }
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: continue
            now = ch.uptimeMillis
            if (!ch.pressed) {
                ended = true
                act(g.up(longHeld = ch.uptimeMillis >= longAt))
                break
            }
            val d = ch.positionChange()
            act(g.move(d.x.toDp().value, d.y.toDp().value))
            // Протяжка — наша: движение не отдаём ни списку, ни системе.
            if (g.dragging) ch.consume()
        }
    } finally {
        if (!ended) act(g.cancel())
    }
}

/**
 * Язычок шторки разделов на сложенном (`.dgrip` холста; владелец 13.10.2026: «кнопка у края со стрелкой, которая
 * её вытаскивает, и такая же кнопка, которая убирает»): тот же [ColumnGrip], только без протяжки и поуже — на
 * узком экране зона касания не должна ложиться на молнию у строк. [at] — ширина выехавшей шторки.
 */
@Composable
internal fun SideGrip(open: Boolean, at: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) =
    ColumnGrip(
        GripEdge.START, at, open, if (open) "Спрятать разделы" else "Показать разделы", onClick, modifier,
        dockW = 19.dp, zoneW = 26.dp,
    )

/**
 * Разворот вида v2 тремя колонками (14.10.2026): боковина | список | карточка дела, у боковины и карточки — язычки
 * ([ColumnGrip], одна механика). Ширины и «спрятана» считает `core/DelaColumns.kt`, здесь — анимация и палец. Пока
 * колонка уезжает или выезжает, её содержимое не сжимается: оно прижато к внешнему краю и уходит за него (без
 * рывка, как боковина с 13.10.2026); пока тянут — перестраивается под палец. Список — главный: забирает всё, что
 * отдали колонки.
 */
@Composable
internal fun DelaColumnsRow(
    cols: DelaColumns.State,
    onCols: (DelaColumns.State) -> Unit,
    modifier: Modifier,
    side: @Composable (Modifier) -> Unit,
    list: @Composable () -> Unit,
    pane: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val total = maxWidth.value
        val fit = DelaColumns.fit(total, cols)
        val sideW = remember { Animatable(fit.side) }
        val paneW = remember { Animatable(fit.pane) }
        // Кого тянут, сколько под пальцем (dp) и что из этого видно: пока тянут, колонки идут за пальцем без анимации.
        var held by remember { mutableStateOf<GripEdge?>(null) }
        var raw by remember { mutableFloatStateOf(0f) }
        var live by remember { mutableStateOf<DelaColumns.Fit?>(null) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(fit, held) {
            if (held != null) return@LaunchedEffect
            launch { sideW.animateTo(fit.side, tween(220)) }
            launch { paneW.animateTo(fit.pane, tween(220)) }
        }
        val sideNow = live?.side ?: sideW.value
        val paneNow = live?.pane ?: paneW.value
        fun grab(edge: GripEdge) = GripDrag(
            onStart = {
                held = edge
                live = DelaColumns.Fit(sideW.value, paneW.value)
                raw = if (edge == GripEdge.START) sideW.value else paneW.value
            },
            onDrag = { d ->
                raw += d
                live = if (edge == GripEdge.START) DelaColumns.dragSide(total, cols, raw) else DelaColumns.dragPane(total, cols, raw)
            },
            onEnd = {
                val from = live
                onCols(if (edge == GripEdge.START) DelaColumns.dropSide(total, cols, raw) else DelaColumns.dropPane(total, cols, raw))
                // С того места, где отпустил, — к тому, куда встала колонка (или в док): анимация идёт от пальца.
                scope.launch {
                    if (from != null) {
                        sideW.snapTo(from.side)
                        paneW.snapTo(from.pane)
                    }
                    live = null
                    held = null
                }
            },
            onStep = { d ->
                val now = if (edge == GripEdge.START) fit.side else fit.pane
                onCols(if (edge == GripEdge.START) DelaColumns.dropSide(total, cols, now + d) else DelaColumns.dropPane(total, cols, now + d))
            },
        )
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(sideNow.dp).fillMaxHeight().clipToBounds()) {
                if (sideNow > 0.5f) {
                    val content = if (held != null) kotlin.math.max(sideNow, DelaColumns.SIDE_MIN) else DelaColumns.openSide(total, cols)
                    side(Modifier.wrapContentWidth(Alignment.End, unbounded = true).width(content.dp).fillMaxHeight())
                }
            }
            Box(Modifier.weight(1f)) { list() }
            Box(Modifier.width(paneNow.dp).fillMaxHeight().clipToBounds()) {
                if (paneNow > 0.5f) {
                    val content = if (held != null) kotlin.math.max(paneNow, DelaColumns.PANE_MIN) else DelaColumns.openPane(total, cols)
                    pane(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(content.dp).fillMaxHeight())
                }
            }
        }
        // Оба язычка — на одной высоте, чуть выше середины (как боковина с 13.10.2026): зеркально.
        ColumnGrip(
            GripEdge.START, sideNow.dp, cols.sideOn, if (cols.sideOn) "Спрятать разделы" else "Показать разделы",
            onToggle = { onCols(cols.copy(sideOn = !cols.sideOn)) },
            modifier = Modifier.align(BiasAlignment(-1f, -0.08f)),
            drag = grab(GripEdge.START),
        )
        ColumnGrip(
            GripEdge.END, paneNow.dp, cols.paneOn, if (cols.paneOn) "Спрятать карточку" else "Показать карточку",
            onToggle = { onCols(cols.copy(paneOn = !cols.paneOn)) },
            modifier = Modifier.align(BiasAlignment(1f, -0.08f)),
            drag = grab(GripEdge.END),
        )
    }
}
