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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
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
 * слово цветом текста; ряд листается вбок, если не влез. Остальные разделы, фильтры, CRM, проекты и люди — в
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
                    KvCount(tab.count, hot = tab.hot, bad = tab.bad)
                }
            }
        }
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

/** Надстрочник-переключатель группы (свернуть или раскрыть): «Клиенты · 12 ›». [trailing] — действие справа. */
@Composable
private fun NavFold(text: String, count: Int, open: Boolean, onToggle: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(7.dp))
            .clickable(role = Role.Button, onClickLabel = if (open) "свернуть «$text»" else "раскрыть «$text»", onClick = onToggle)
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = LocalPravkaType.current.overline, color = Kv.faint)
        Spacer(Modifier.width(6.dp))
        Text(count.toString(), style = LocalPravkaType.current.overline.copy(fontWeight = FontWeight.Normal), color = Kv.faint)
        Icon(
            KvGlyphs.byKey("chevron") ?: Glyphs.Forward,
            contentDescription = null,
            tint = Kv.faint,
            modifier = Modifier.padding(start = 2.dp).size(13.dp).rotate(if (open) 90f else 0f),
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Проект клиента под ним (`side-deals` веба): квадратик цвета стадии, короткое имя, число открытых дел. */
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(stage))
        Spacer(Modifier.width(8.dp))
        Text(
            d.short,
            style = t.body.copy(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (selected) Kv.accent else Kv.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        KvCount(d.open.takeIf { it > 0 }?.toString().orEmpty(), bad = d.late)
    }
}

/** Что показывает боковина Дел — одно на разложенный (колонкой) и сложенный (шторкой). */
internal class DelaSideModel(
    val nav: String,
    val counts: DelaViews.Counts,
    val views: List<DelaViews.View>,
    val filters: List<Pair<DelaGroups.Filter, Int>>,
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
)

/**
 * Боковина Дел (`.dside` холста и боковая колонка веба): поиск, разделы с числами (Сейчас, Сегодня, Новое, Все
 * дела, Круг и дерево жизни), фильтры, CRM, избранное, проекты по видам (у клиента — его проекты под стрелкой,
 * «раскрыть все»), люди, архив и сфера. Одна на разложенный — колонкой 172 dp, и сложенный — шторкой поверх
 * (13.10.2026, владелец: «боковина, которая вылезает и скрывается»); то, что раньше открывал «☰», — теперь здесь.
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
            NavRow(viewIcon(v), v.title, selected = m.nav == v.key, count = m.counts.label(v), hot = m.counts.hot(v), bad = m.counts.bad(v)) { a.onNav(v.key) }
        }
        if (m.filters.isNotEmpty()) {
            NavEyebrow("Фильтры")
            for ((f, n) in m.filters) {
                NavRow(kv("bookmark", Glyphs.ListLines), f.name, selected = m.nav == m.filterNav + f.id, count = n.takeIf { it > 0 }?.toString().orEmpty()) {
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
                NavRow(icon, r.project.name, selected = false, count = r.open.takeIf { it > 0 }?.toString().orEmpty(), bad = r.late) { a.onProject(r.project.id) }
            } else {
                val open = r.project.id in m.clientOpen || r.project.id == m.hereClient
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        NavRow(icon, r.project.name, selected = false, count = r.open.takeIf { it > 0 }?.toString().orEmpty(), bad = r.late) { a.onProject(r.project.id) }
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
            NavFold(title, rows.size, open, onToggle = { open = !open }, trailing = if (open && withDeals.isNotEmpty()) {
                {
                    val allOpen = withDeals.all { it in m.clientOpen }
                    Text(
                        // Колонка узкая (172 dp): видно одно слово, целиком — для чтения с экрана.
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
            NavFold("Люди", m.projects.people.size, open, onToggle = { open = !open })
            if (open) for ((p, n) in m.projects.people) {
                NavRow(kv("user", Glyphs.Groups), p.label, selected = false, count = n.takeIf { it > 0 }?.toString().orEmpty()) { a.onPerson(p.id) }
            }
        }
        if (m.projects.archived.isNotEmpty()) {
            var open by rememberSaveable("side:arch") { mutableStateOf(false) }
            NavFold("Архив", m.projects.archived.size, open, onToggle = { open = !open })
            if (open) for (r in m.projects.archived) row(r)
        }
        // Сфера (поле `sphere` проекта; дела без проекта видны в любой) — словами, выбранная мягким акцентом.
        NavEyebrow("Сфера")
        Row(Modifier.padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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

/**
 * Язычок боковины (`.dgrip` холста; владелец 13.10.2026: «кнопка у края со стрелкой, которая её вытаскивает, и
 * такая же кнопка, которая убирает»): 18×56, панель с линией, шеврон линией — › показать, ‹ спрятать. [attached] —
 * прижат к краю экрана: скругление 8 только со стороны экрана, линии у края нет. Жеста от края нет — он спорит с
 * системным «назад».
 */
@Composable
internal fun SideGrip(open: Boolean, attached: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = if (attached) RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp) else RoundedCornerShape(8.dp)
    val label = if (open) "Спрятать разделы" else "Показать разделы"
    Box(
        modifier
            // Прижатый к краю — на 1 dp за край: левая линия уходит за экран, как `border-left: 0` холста.
            .then(if (attached) Modifier.offset(x = (-1).dp) else Modifier)
            .size(width = if (attached) 19.dp else 18.dp, height = 56.dp)
            .clip(shape)
            .background(Kv.panel)
            .border(1.dp, Kv.line, shape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            KvGlyphs.byKey("chevron") ?: Glyphs.Forward,
            contentDescription = null,
            tint = Kv.muted,
            modifier = Modifier.padding(start = if (attached) 1.dp else 0.dp).size(15.dp).rotate(if (open) 180f else 0f),
        )
    }
}
