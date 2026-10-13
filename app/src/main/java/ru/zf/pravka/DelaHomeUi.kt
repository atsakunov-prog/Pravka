package ru.zf.pravka

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.zf.pravka.core.Dela
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
    val asks: List<Dela.Suggestion>,
    /** «Новое», которое ответа не ждёт: поставлено за три дня и сделано само — строкой-ссылкой. */
    val fed: Int,
    val auto: Int,
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
 * Число у раздела (`.count` веба): прямоугольник 5; [hot] — мягким акцентом (взятое, вопросы, «отбить»),
 * [bad] — цветом беды (просроченное у «Всех дел»). Пусто — ничего (`.count:empty`).
 */
@Composable
internal fun KvCount(text: String, hot: Boolean = false, bad: Boolean = false) {
    if (text.isBlank()) return
    val t = LocalPravkaType.current
    val ink = when {
        bad -> Kv.bad
        hot -> Kv.accent
        else -> Kv.muted
    }
    Text(
        text,
        style = t.caption.copy(fontWeight = FontWeight.SemiBold, lineHeight = 19.sp),
        color = ink,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(KvShape.Tag))
            .background(
                when {
                    bad -> Kv.bad.copy(alpha = 0.14f)
                    hot -> Kv.accentSoft
                    else -> Kv.hover2
                },
            )
            .widthIn(min = 20.dp)
            .padding(horizontal = 6.dp),
    )
}

/** Вкладка ряда: ключ раздела, название, число и его цвет; [full] — полное название для чтения с экрана («Все дела»). */
internal data class DelaTabItem(val key: String, val title: String, val count: String, val hot: Boolean = false, val bad: Boolean = false, val full: String = title)

/**
 * Вкладки с чертой (`.tabs` холста): Сейчас · Сегодня · Новое · Все дела, у выбранной — черта акцентом и
 * слово цветом текста; ряд листается вбок, если не влез. Справа — ☰: остальные разделы, фильтры, CRM,
 * проекты и люди (боковая панель веба).
 */
@Composable
internal fun DelaTabsV2(tabs: List<DelaTabItem>, selected: String, onSelect: (String) -> Unit, onMenu: () -> Unit) {
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
                        .padding(start = 7.dp, end = 7.dp, top = 12.dp, bottom = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        tab.title,
                        style = t.body.copy(fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                        color = if (on) Kv.text else Kv.muted,
                        maxLines = 1,
                    )
                    KvCount(tab.count, hot = tab.hot, bad = tab.bad)
                }
            }
        }
        GlyphButton(kv("list", Glyphs.Menu), "разделы, CRM, фильтры, проекты и люди", onClick = onMenu, tint = Kv.icon, size = 44.dp)
    }
}

/** «просрочено 2 — во «Всех делах» ›» (`lateLink` веба): само просроченное ни в «Сейчас», ни в «Сегодня» не встаёт. */
@Composable
internal fun LateLink(n: Int, onClick: () -> Unit) {
    val t = LocalPravkaType.current
    val ink = if (Look.v2) Kv.bad else ru.zf.pravka.ui.LocalMode.current.value
    Text(
        "просрочено $n — во «Всех делах» ›",
        style = t.label.copy(fontWeight = FontWeight.SemiBold),
        color = ink,
        modifier = Modifier
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(KvShape.Tag))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
    )
}

/**
 * Блок главной (`.card` и `.sec` холста): значок, название, число, мелкая подпись в одну строку — и дела
 * панелью с линией. [onTitle] — заголовок ведёт в свой раздел («Сегодня» → «Сегодня»). Вид 4.0 — та же
 * плашка `PaperCard`, что у остальных групп.
 */
@Composable
internal fun HomeBlock(
    icon: ImageVector,
    title: String,
    count: Int,
    hot: Boolean,
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
            Spacer(Modifier.width(8.dp))
            KvCount(if (count > 0) count.toString() else "", hot = hot)
            Spacer(Modifier.width(8.dp))
            Text(sub, style = t.meta, color = Kv.faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
) {
    item(key = "home:now") {
        HomeBlock(Glyphs.Bolt, "Сейчас", h.now.size, hot = true, sub = "отметил сам — сделать сегодня") {
            if (h.now.isEmpty()) {
                BlockNote("Пусто. Возьми до ${DelaViews.NOW_MAX} дел на сегодня — молния «В «Сейчас»» в карточке дела или «!сейчас» в строке.")
            } else {
                // Жирным здесь не выделяем: весь блок — «Сейчас».
                TaskRows(h.now, actions, today, nowBold = false)
                if (h.now.size < DelaViews.NOW_MAX) {
                    BlockNote("Ещё ${DelaViews.NOW_MAX - h.now.size} — молния «В «Сейчас»» в карточке дела", top = true)
                }
            }
        }
    }
    if (h.asks.isNotEmpty()) {
        item(key = "home:new") {
            HomeBlock(
                kv("inbox", Glyphs.Groups), "Новое", h.asks.size, hot = true, sub = "подскажи — без тебя не поставлю",
                onTitle = onNew,
            ) {
                h.asks.forEachIndexed { i, sg ->
                    if (i > 0) RowRule()
                    AskRow(
                        sg, i + 1, crm.snap, crm.me, today,
                        onAccept = { onAccept(listOf(sg)) }, onEdit = { onEdit(sg) }, onReject = { onReject(sg) },
                        onOpen = actions.open, onMerge = { t -> onMerge(sg, t) },
                        onAcceptSet = { set -> onAcceptSet(sg, set) },
                    )
                }
                // «Все» — под вопросами, а не в заголовке: там они съедали подпись блока.
                if (h.asks.size > 1) Row {
                    Spacer(Modifier.weight(1f))
                    PaperTextButton("Не надо все", onClick = { onRejectAll(h.asks) })
                    PaperTextButton("Поставить все", onClick = { onAccept(h.asks) })
                }
            }
        }
    }
    val rest = listOfNotNull(
        "добавлено за ${DelaViews.FEED_DAYS} дня: ${h.fed}".takeIf { h.fed > 0 },
        "сделано само: ${h.auto}".takeIf { h.auto > 0 },
    ).joinToString(" · ")
    if (rest.isNotBlank()) {
        item(key = "home:newlink") {
            NewLine(if (h.asks.isEmpty()) "Новое: вопросов нет · $rest" else "Всё «Новое»: $rest", onNew)
        }
    }
    item(key = "home:today") {
        HomeBlock(
            Glyphs.Sunny, "Сегодня", h.today.size, hot = false,
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

/** Подзаголовок группы внутри блока: «Отбить · 2». */
@Composable
private fun GroupHead(text: String, top: Boolean) {
    val t = LocalPravkaType.current
    Text(
        text,
        style = t.label.copy(fontWeight = FontWeight.SemiBold),
        color = if (Look.v2) Kv.muted else ru.zf.pravka.ui.LocalMode.current.label,
        modifier = Modifier.padding(top = if (top) 10.dp else 2.dp, bottom = 2.dp),
    )
}

/** Строка-ссылка в «Новое» (`.new-link` веба): значок, слова, стрелка. */
@Composable
private fun NewLine(text: String, onClick: () -> Unit) {
    val t = LocalPravkaType.current
    val v2 = Look.v2
    val ink = if (v2) Kv.muted else ru.zf.pravka.ui.LocalMode.current.label
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KvShape.Control))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(kv("inbox", Glyphs.Groups), contentDescription = null, tint = if (v2) Kv.icon else ink, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = t.label, color = ink, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Icon(Glyphs.Forward, contentDescription = null, tint = if (v2) Kv.faint else ink, modifier = Modifier.size(16.dp))
    }
}

// ---------------------------------------------------------------- колонка разделов

/** Строка колонки разделов (`.dside a` холста): значок, название, число; выбранная — мягким акцентом. */
@Composable
private fun NavRow(icon: ImageVector, title: String, selected: Boolean, count: String = "", hot: Boolean = false, bad: Boolean = false, onClick: () -> Unit) {
    val t = LocalPravkaType.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Kv.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) Kv.accent else Kv.icon, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = t.body.copy(fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (selected) Kv.accent else Kv.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        KvCount(count, hot = hot, bad = bad)
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
 * Колонка разделов разворота (`.dside` холста, боковая панель веба): поиск, разделы с числами (Сейчас,
 * Сегодня, Новое, Все дела, Круг и дерево жизни), фильтры, CRM, клиенты по свежести и «Все проекты и
 * люди» — та же панель ☰, что на сложенном (избранное, проекты по видам, люди, архив, сфера).
 */
@Composable
internal fun DelaNavColumn(
    nav: String,
    counts: DelaViews.Counts,
    views: List<DelaViews.View>,
    filters: List<Pair<DelaGroups.Filter, Int>>,
    filterNav: String,
    crm: List<Pair<String, String>>,
    clients: List<Dela.ProjectRow>,
    query: String,
    onQuery: (String) -> Unit,
    onNav: (String) -> Unit,
    onProject: (String) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalPravkaType.current
    Column(modifier.verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 24.dp)) {
        // Поиск (`.dfind`): набрал — список посередине показывает находки, как поиск веба.
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
                if (query.isEmpty()) Text("Поиск", style = t.body.copy(fontSize = 13.5.sp), color = Kv.faint, maxLines = 1)
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = t.body.copy(fontSize = 13.5.sp, color = Kv.text),
                    cursorBrush = SolidColor(Kv.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) GlyphButton(Glyphs.Close, "закрыть поиск", onClick = { onQuery("") }, tint = Kv.faint, size = 24.dp)
        }
        for (v in views) {
            NavRow(viewIcon(v), v.title, selected = nav == v.key, count = counts.label(v), hot = counts.hot(v), bad = counts.bad(v)) { onNav(v.key) }
        }
        if (filters.isNotEmpty()) {
            NavEyebrow("Фильтры")
            for ((f, n) in filters) {
                NavRow(kv("bookmark", Glyphs.ListLines), f.name, selected = nav == filterNav + f.id, count = n.takeIf { it > 0 }?.toString().orEmpty()) { onNav(filterNav + f.id) }
            }
        }
        if (crm.isNotEmpty()) {
            NavEyebrow("CRM")
            for ((k, title) in crm) {
                val icon = when (k) {
                    "crm" -> kv("funnel", Glyphs.Layers)
                    "clients" -> kv("briefcase", Glyphs.Layers)
                    "people" -> kv("users", Glyphs.Groups)
                    else -> kv("link", Glyphs.Link)
                }
                NavRow(icon, title, selected = nav == k) { onNav(k) }
            }
        }
        if (clients.isNotEmpty()) {
            NavEyebrow("Клиенты")
            for (r in clients) {
                NavRow(kv("briefcase", Glyphs.Layers), r.project.name, selected = false, count = r.open.takeIf { it > 0 }?.toString().orEmpty(), bad = r.late) {
                    onProject(r.project.id)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        NavRow(Glyphs.Forward, "Проекты и люди", selected = false, onClick = onMore)
    }
}
