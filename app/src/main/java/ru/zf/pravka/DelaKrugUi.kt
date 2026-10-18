package ru.zf.pravka

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import ru.zf.pravka.core.Dela
import ru.zf.pravka.core.DelaKrug
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.Ink
import ru.zf.pravka.ui.LocalMode
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperSheet
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.RowRule
import ru.zf.pravka.ui.glass

// Круг жизни и дерево жизни на телефоне (13.10.2026, docs/dela-phone-14.md, бриф 7.2) — то, что
// не укладывается в списки дел: циферблат девяти направлений с карточками по зонам, дерево
// (плюсиками, колонками, списком), лист «Куда повесить» и «Место в дереве» в карточке дела.
// Логика — `core/DelaKrug.kt` под тестами, здесь только вид. Перетаскивания веба на телефоне
// нет: пальцем по узкому экрану дело на цель не донести — вместо него долгое нажатие на дело,
// цель или проект и лист «Куда повесить».

/** Виды дерева (`TREE_MODES` веба): ключ помнится устройством, как браузером у веба. */
internal val TREE_MODES = listOf("tree" to "Дерево", "cols" to "Колонки", "list" to "Список")

/** Отступ ступени дерева. */
private val STEP = 14.dp

/** Плюсик или минус ветки; [enabled] = false — ветка пустая, место под плюсик остаётся. */
@Composable
private fun Caret(open: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val mode = LocalMode.current
    Box(
        Modifier.size(28.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (enabled) Text(if (open) "−" else "+", style = LocalPravkaType.current.valueS, color = mode.label)
    }
}

// ---------------------------------------------------------------- «Круг жизни»

/**
 * Циферблат Круга (`dialSvg` веба): оси направлений через 40°, Здоровье сверху, три кольца,
 * подписи коротких имён; на конце оси — точка движения за неделю радиусом по min(10, 3·√N).
 * Это не круг дня Засечки (`ui/Dial.kt`): там часы, здесь стороны жизни. Тап по подписи —
 * дерево этого направления.
 */
@Composable
internal fun KrugDial(dirs: List<DelaKrug.Direction>, mv: Map<String, DelaKrug.Move>, onDir: ((String) -> Unit)?) {
    val mode = LocalMode.current
    val ty = LocalPravkaType.current
    val measurer = rememberTextMeasurer()
    val style = ty.dialLabel.copy(color = Ink.TextSecondary, fontWeight = FontWeight.Medium)
    val labels = remember(dirs, style) { dirs.map { measurer.measure(it.label, style) } }
    val angles = remember(dirs) { dirs.mapIndexed { i, d -> Math.toRadians(DelaKrug.angle(d, i, dirs.size).toDouble()) } }
    // Поля под подписи слева и справа (как viewBox веба): ось короче половины ширины.
    fun radius(w: Float, h: Float, px: Float) = min(w / 2 - 64 * px, h / 2 - 22 * px).coerceAtLeast(20 * px)
    fun at(w: Float, h: Float, r: Float, a: Double) = Offset(w / 2 + (sin(a) * r).toFloat(), h / 2 - (cos(a) * r).toFloat())
    Box(
        Modifier.fillMaxWidth().height(250.dp).then(
            if (onDir != null) Modifier.pointerInput(dirs) {
                detectTapGestures { o ->
                    val px = density
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val r = radius(w, h, px)
                    val hit = dirs.indices.minByOrNull { i -> at(w, h, r + 18 * px, angles[i]).let { hypot(it.x - o.x, it.y - o.y) } }
                    if (hit != null && at(w, h, r + 18 * px, angles[hit]).let { hypot(it.x - o.x, it.y - o.y) } < 44 * px) onDir(dirs[hit].key)
                }
            } else Modifier,
        ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val px = density
            val w = size.width
            val h = size.height
            val r = radius(w, h, px)
            val c = Offset(w / 2, h / 2)
            for (f in listOf(1f / 3, 2f / 3, 1f)) drawCircle(mode.tint.copy(alpha = 0.16f), r * f, c, style = Stroke(1.dp.toPx()))
            dirs.forEachIndexed { i, d ->
                val a = angles[i]
                val end = at(w, h, r, a)
                drawLine(mode.tint.copy(alpha = 0.30f), c, end, strokeWidth = 1.dp.toPx())
                val m = mv[d.key]
                if (m != null && m.events > 0) {
                    val dot = ((3 + m.radius * 1.4) * r / 120).toFloat()
                    drawCircle(mode.value.copy(alpha = 0.28f), dot, end)
                    drawCircle(mode.value, dot, end, style = Stroke(1.4.dp.toPx()))
                }
                val l = labels[i]
                val q = at(w, h, r + 18 * px, a)
                val s = sin(a)
                val x = when {
                    abs(s) < 0.3 -> q.x - l.size.width / 2f
                    s > 0 -> q.x - 4 * px
                    else -> q.x - l.size.width + 4 * px
                }
                drawText(l, topLeft = Offset(x, q.y - l.size.height / 2f))
            }
        }
    }
}

/**
 * «Круг жизни» — обзор (`renderKrug` веба): циферблат, ниже по зонам («Я», «Мир», «Семья»)
 * карточки направлений — название, открыто, «за неделю N», формула курсивом, цели верхнего
 * уровня (тап — карточка цели); внизу — «Без направления». Тап по направлению — его дерево.
 */
internal fun LazyListScope.krugOverview(
    dirs: List<DelaKrug.Direction>,
    m: DelaKrug.Tree,
    mv: Map<String, DelaKrug.Move>,
    actions: DelaActions,
    /** Тап по направлению — его дерево; null — сервер дерева не знает, вести некуда. */
    onDir: ((String) -> Unit)?,
    onHang: ((Dela.Task) -> Unit)?,
) {
    item(key = "krug:dial") {
        PaperCard {
            KrugDial(dirs, mv, onDir)
            Text(
                "Точка на оси — движение за неделю: закрытое дело — одно событие, цель — три." +
                    if (onDir != null) " Тап по направлению — его дерево." else "",
                style = LocalPravkaType.current.meta,
                color = LocalMode.current.meta,
            )
        }
    }
    // Ключ — номер зоны, а не имя: зона, разорванная порядком круга, дала бы два одинаковых ключа списка.
    for ((i, z) in DelaKrug.zones(dirs).withIndex()) {
        val (zone, list) = z
        item(key = "krug:z:$i") {
            PaperCard(label = zone.ifBlank { null }) {
                list.forEachIndexed { i, d ->
                    if (i > 0) RowRule()
                    KrugDirCard(d, m, mv[d.key], actions, onDir)
                }
            }
        }
    }
    val none = m.kids(DelaKrug.NONE).filter { it.kind == DelaKrug.Kind.TASK }.mapNotNull { it.task }
    if (none.isNotEmpty()) {
        item(key = "krug:none") {
            PaperCard(label = "Без направления · ${none.size}") {
                // Честное время (16.10.2026): «в ближайшие полчаса» ночью и в выходные обещало то, чего не будет.
                PaperHint(
                    "разборщик Круга посмотрит ${DelaKrug.krugAt(DelaKrug.mskNow())}" + if (onHang != null) " — или повесь сам: долгое нажатие на дело" else "",
                )
                TaskRows(none, actions, onLong = onHang)
            }
        }
    }
}

@Composable
private fun KrugDirCard(d: DelaKrug.Direction, m: DelaKrug.Tree, move: DelaKrug.Move?, actions: DelaActions, onDir: ((String) -> Unit)?) {
    val mode = LocalMode.current
    val ty = LocalPravkaType.current
    val key = DelaKrug.dirKey(d.key)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .clickable(enabled = onDir != null) { onDir?.invoke(d.key) }.padding(vertical = 4.dp),
        ) {
            Text(d.name, style = ty.bodyStrong, color = Ink.TextStrong, modifier = Modifier.weight(1f))
            val open = m.open(key)
            if (open > 0) Text(open.toString(), style = ty.valueS, color = mode.value, modifier = Modifier.padding(end = 8.dp))
            Text(if ((move?.done ?: 0) > 0) "за неделю ${move?.done}" else "за неделю —", style = ty.meta, color = mode.meta)
        }
        if (d.formula.isNotBlank()) Text(d.formula, style = ty.label.copy(fontStyle = FontStyle.Italic), color = mode.label)
        val goals = m.kids(key).filter { it.kind == DelaKrug.Kind.GOAL }
        if (goals.isEmpty()) Text("Целей пока нет.", style = ty.meta, color = mode.meta, modifier = Modifier.padding(top = 2.dp))
        for (g in goals) {
            val t = g.task ?: continue
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { actions.open(t) }.padding(vertical = 5.dp),
            ) {
                Text(t.title, style = ty.body, color = Ink.Text, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                val n = m.open(g.key)
                if (n > 0) Text(n.toString(), style = ty.meta, color = mode.label)
            }
        }
    }
}

// ---------------------------------------------------------------- «Дерево жизни»

/**
 * Что дереву нужно от вкладки: вид и направление (помнит устройство), свёрнутые ветки,
 * фильтры «Списка» и куда ведут долгое нажатие и ссылки.
 */
internal class TreeUi(
    val mode: String,
    val dir: String,
    val folded: (String) -> Boolean,
    val onFold: (String, Boolean) -> Unit,
    /** До какого уровня раскрыто (1…4, 0 — «Всё», −1 — ветки раскрывали руками). */
    val level: Int,
    /** Нажали уровень: он и ветки разом — свёрнута ли каждая (`DelaKrug.levelFolds`). */
    val onLevel: (Int, Map<String, Boolean>) -> Unit,
    val onMode: (String) -> Unit,
    val onDir: (String) -> Unit,
    val onDirPick: () -> Unit,
    /** «Список»: цель-фильтр (id; "" — любая), «без зачем». */
    val goal: String,
    val goalTitle: String,
    val loose: Boolean,
    val onGoalPick: () -> Unit,
    val onGoal: (String) -> Unit,
    val onLoose: () -> Unit,
    val onHangTask: (Dela.Task) -> Unit,
    val onHangProject: (Dela.Project) -> Unit,
    val onProject: (String) -> Unit,
)

/**
 * «Дерево жизни» (`renderTree` веба): переключатель видов и направления сверху, дальше
 * «Дерево» (направление › цели › подцели › проекты › дела, плюсиками), «Колонки» (девять
 * направлений рядом) или «Список» (дела плоско с путём и фильтрами).
 */
internal fun LazyListScope.treeView(
    dirs: List<DelaKrug.Direction>,
    m: DelaKrug.Tree,
    list: List<DelaKrug.Placed>,
    ui: TreeUi,
    actions: DelaActions,
) {
    val shown = dirs.filter { ui.dir.isBlank() || it.key == ui.dir }
    item(key = "tree:tools") {
        PaperCard {
            ChipRow { for ((k, l) in TREE_MODES) PaperChip(l, selected = k == ui.mode, onClick = { ui.onMode(k) }) }
            ChipRow {
                PaperChip((if (ui.dir.isBlank()) "Все направления" else DelaKrug.label(ui.dir, dirs)) + " ▾", selected = ui.dir.isNotBlank(), onClick = ui.onDirPick)
                if (ui.dir.isNotBlank()) PaperChip("✕ все", selected = false, onClick = { ui.onDir("") })
                if (ui.mode == "list") {
                    PaperChip(if (ui.goal.isBlank()) "Любая цель ▾" else "Цель: ${ui.goalTitle.take(30)}", selected = ui.goal.isNotBlank(), onClick = ui.onGoalPick)
                    if (ui.goal.isNotBlank()) PaperChip("✕ любая", selected = false, onClick = { ui.onGoal("") })
                    PaperChip("Без зачем", selected = ui.loose, onClick = ui.onLoose)
                }
            }
            if (ui.mode != "list") {
                // Уровни вместо «Раскрыть всё / Свернуть всё» — своей строкой, чтобы влезли все: «1» — только
                // направления, «2» — их цели, … «Всё».
                val roots = shown.map { DelaKrug.dirKey(it.key) }
                ChipRow {
                    Text("Уровень", style = LocalPravkaType.current.meta, color = LocalMode.current.meta, modifier = Modifier.align(Alignment.CenterVertically))
                    for (n in (1..DelaKrug.TREE_LEVELS) + 0) {
                        PaperChip(if (n == 0) "Всё" else n.toString(), selected = ui.level == n, onClick = { ui.onLevel(n, DelaKrug.levelFolds(m, roots, n)) })
                    }
                }
            }
            Text(
                if (ui.mode == "list") "дела без целей, по пути в дереве; долгое нажатие — «Куда повесить»"
                else "долгое нажатие на дело, цель или проект — «Куда повесить»",
                style = LocalPravkaType.current.meta,
                color = LocalMode.current.meta,
            )
        }
    }
    when (ui.mode) {
        "list" -> item(key = "tree:list") {
            PaperCard(label = "Дела · ${list.size}") {
                if (list.isEmpty()) PaperHint("Под этот фильтр дел нет.")
                val path = list.associate { it.task.id to it.path }
                TaskRows(list.map { it.task }, actions, inTree = true, note = { path[it.id].orEmpty() }, onLong = ui.onHangTask)
            }
        }
        "cols" -> item(key = "tree:cols") { TreeColumns(shown, m, ui, actions) }
        else -> for (d in shown) {
            item(key = "tree:d:${d.key}") { TreeDirCard(d, m, ui, actions) }
        }
    }
    if (ui.dir.isBlank() && ui.mode != "list") {
        val none = m.kids(DelaKrug.NONE).filter { it.kind == DelaKrug.Kind.TASK }.mapNotNull { it.task }
        if (none.isNotEmpty()) {
            item(key = "tree:none") {
                PaperCard(label = "Без направления · ${none.size}") {
                    PaperHint("разборщик Круга посмотрит ${DelaKrug.krugAt(DelaKrug.mskNow())} — или повесь на цель сам: долгое нажатие")
                    TaskRows(none, actions, onLong = ui.onHangTask)
                }
            }
        }
    }
}

/** Направление «Дерева»: шапка с плюсиком (зона, название, открыто, движение, формула) и ветки. */
@Composable
private fun TreeDirCard(d: DelaKrug.Direction, m: DelaKrug.Tree, ui: TreeUi, actions: DelaActions) {
    val mode = LocalMode.current
    val ty = LocalPravkaType.current
    val key = DelaKrug.dirKey(d.key)
    val open = !ui.folded(key)
    PaperCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { ui.onFold(key, open) },
        ) {
            Caret(open, true) { ui.onFold(key, open) }
            Column(Modifier.weight(1f)) {
                if (d.zone.isNotBlank()) Text(d.zone.uppercase(Locale.forLanguageTag("ru")), style = ty.overline, color = mode.meta)
                Text(d.name, style = ty.bodyStrong, color = Ink.TextStrong)
            }
            val n = m.open(key)
            if (n > 0) Text(n.toString(), style = ty.valueS, color = mode.value, modifier = Modifier.padding(end = 8.dp))
            Text(m.moved(key).takeIf { it > 0 }?.let { "за неделю +$it" } ?: "за неделю —", style = ty.meta, color = mode.meta)
        }
        if (d.formula.isNotBlank()) Text(d.formula, style = ty.label.copy(fontStyle = FontStyle.Italic), color = mode.label)
        if (open) {
            val rows = DelaKrug.outline(m, key, ui.folded)
            if (rows.isEmpty()) PaperHint("Здесь пока ничего — цели заводятся в карточке дела («Сделать целью») или словами Claude.")
            for (r in rows) TreeLine(r, m, ui, actions)
        }
    }
}

/** Строка «Дерева»: цель (плюсик, если есть что раскрыть; открыто, движение), проект (плюсик, число) или дело — строкой дела. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeLine(r: DelaKrug.Row, m: DelaKrug.Tree, ui: TreeUi, actions: DelaActions) {
    val mode = LocalMode.current
    val ty = LocalPravkaType.current
    val c = r.item
    when (c.kind) {
        DelaKrug.Kind.TASK -> {
            val t = c.task ?: return
            TaskRow(t, actions, java.time.LocalDate.now().toString(), depth = r.depth, inTree = true, onLong = { ui.onHangTask(t) })
        }
        DelaKrug.Kind.GOAL -> {
            val t = c.task ?: return
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = STEP * r.depth).clip(RoundedCornerShape(10.dp))
                    .combinedClickable(onClick = { actions.open(t) }, onLongClick = { ui.onHangTask(t) }).padding(vertical = 4.dp),
            ) {
                Caret(r.expanded, r.hasKids) { ui.onFold(c.key, r.expanded) }
                Spacer(Modifier.width(2.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = ty.bodyStrong, color = Ink.TextStrong)
                    val more = listOfNotNull(
                        t.rhythm.takeIf { it.isNotBlank() }?.let { "практика · $it" },
                        "ориентир".takeIf { t.noSteps },
                    ).joinToString(" · ")
                    if (more.isNotBlank()) Text(more, style = ty.meta, color = mode.label)
                }
                val n = m.open(c.key)
                Text(if (n > 0) n.toString() else if (!r.hasKids) "пусто" else "", style = ty.meta, color = if (n > 0) mode.value else mode.meta)
                val mv = m.moved(c.key)
                if (mv > 0) Text(" +$mv", style = ty.meta, color = mode.label)
            }
        }
        DelaKrug.Kind.PROJECT -> {
            val p = c.project ?: return
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = STEP * r.depth).clip(RoundedCornerShape(10.dp))
                    .combinedClickable(onClick = { ui.onFold(c.key, r.expanded) }, onLongClick = { ui.onHangProject(p) }).padding(vertical = 4.dp),
            ) {
                Caret(r.expanded, true) { ui.onFold(c.key, r.expanded) }
                Text(
                    "▣ " + p.name,
                    style = ty.body,
                    color = mode.label,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable { ui.onProject(p.id) },
                )
                Text(m.open(c.key).toString(), style = ty.meta, color = mode.value)
                val mv = m.moved(c.key)
                if (mv > 0) Text(" +$mv", style = ty.meta, color = mode.label)
            }
        }
    }
}

/**
 * «Колонки» (`treeCols` веба): направления рядом, прокрутка вбок; колонка ~280 dp со своей
 * прокруткой вниз. В шапке — зона, название, открыто; внутри то же дерево коротко: цели,
 * проекты, дела одной строкой с кружком.
 */
@Composable
private fun TreeColumns(dirs: List<DelaKrug.Direction>, m: DelaKrug.Tree, ui: TreeUi, actions: DelaActions) {
    val mode = LocalMode.current
    val ty = LocalPravkaType.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (d in dirs) {
            val key = DelaKrug.dirKey(d.key)
            val shape = RoundedCornerShape(18.dp)
            Column(Modifier.width(280.dp).height(560.dp).glass(shape, mode.glass).padding(10.dp)) {
                if (d.zone.isNotBlank()) Text(d.zone.uppercase(Locale.forLanguageTag("ru")), style = ty.overline, color = mode.meta)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.name, style = ty.bodyStrong, color = Ink.TextStrong, modifier = Modifier.weight(1f))
                    Text(m.open(key).toString(), style = ty.valueS, color = mode.value)
                    val mv = m.moved(key)
                    if (mv > 0) Text(" +$mv", style = ty.meta, color = mode.label)
                }
                RowRule()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    val rows = DelaKrug.outline(m, key, ui.folded)
                    if (rows.isEmpty()) Text("пусто", style = ty.meta, color = mode.meta, modifier = Modifier.padding(top = 6.dp))
                    for (r in rows) ColumnLine(r, m, ui, actions)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnLine(r: DelaKrug.Row, m: DelaKrug.Tree, ui: TreeUi, actions: DelaActions) {
    val mode = LocalMode.current
    val ty = LocalPravkaType.current
    val c = r.item
    val t = c.task
    val p = c.project
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 10.dp * r.depth).clip(RoundedCornerShape(8.dp)).combinedClickable(
            onClick = {
                when {
                    p != null -> ui.onFold(c.key, r.expanded)
                    t != null -> actions.open(t)
                }
            },
            onLongClick = {
                when {
                    p != null -> ui.onHangProject(p)
                    t != null -> ui.onHangTask(t)
                }
            },
        ).padding(vertical = 3.dp),
    ) {
        when (c.kind) {
            DelaKrug.Kind.GOAL -> {
                Caret(r.expanded, r.hasKids) { ui.onFold(c.key, r.expanded) }
                Text(t?.title.orEmpty(), style = ty.label, fontWeight = FontWeight.SemiBold, color = Ink.TextStrong, modifier = Modifier.weight(1f))
                m.open(c.key).takeIf { it > 0 }?.let { Text(it.toString(), style = ty.meta, color = mode.value) }
            }
            DelaKrug.Kind.PROJECT -> {
                Caret(r.expanded, true) { ui.onFold(c.key, r.expanded) }
                Text("▣ " + p?.name.orEmpty(), style = ty.label, color = mode.label, modifier = Modifier.weight(1f))
                Text(m.open(c.key).toString(), style = ty.meta, color = mode.value)
            }
            DelaKrug.Kind.TASK -> {
                Box(Modifier.size(28.dp).clip(CircleShape).clickable { t?.let(actions.done) }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(14.dp).clip(CircleShape).border(1.5.dp, mode.tint, CircleShape))
                }
                Text(t?.title.orEmpty(), style = ty.label, color = Ink.Text, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------- «Куда повесить»

/**
 * Выбор цели (`goalPop` веба): открытые цели с путём от направления и поиском; по набранному
 * — «Новая цель: «…»» ([onNew]), без набранного — «Убрать «Зачем»» ([onNone]) и направления
 * («прямо на направление», [onDir]). Проекту направлений нет: он вешается только на цель.
 */
@Composable
internal fun GoalSheet(
    title: String,
    pool: List<DelaKrug.Placed>,
    dirs: List<DelaKrug.Direction>,
    current: String,
    onDismiss: () -> Unit,
    onGoal: (Dela.Task) -> Unit,
    onDir: ((String) -> Unit)? = null,
    onNone: (() -> Unit)? = null,
    onNew: ((String) -> Unit)? = null,
) {
    var q by remember { mutableStateOf("") }
    val mode = LocalMode.current
    PaperSheet(onDismiss = onDismiss, title = title, icon = Glyphs.Layers) {
        PaperField(value = q, onValueChange = { q = it }, label = if (onNew != null) "К какой цели ведёт? Найти или написать новую" else "Найти цель")
        val shown = DelaKrug.findGoals(pool, q)
        val typed = q.trim()
        if (typed.isNotEmpty() && onNew != null && shown.none { Dela.norm(it.task.title) == Dela.norm(typed) }) {
            GoalPickLine("Новая цель: «$typed»", "", goal = false, selected = false) { onNew(typed) }
        }
        if (typed.isEmpty() && current.isNotBlank() && onNone != null) GoalPickLine("Убрать «Зачем»", "", goal = false, selected = false, onClick = onNone)
        for (g in shown) {
            val path = g.path.split(" › ").dropLast(1).joinToString(" › ")
            val more = listOfNotNull(path.ifBlank { null }, g.task.rhythm.takeIf { it.isNotBlank() }?.let { "практика · $it" }).joinToString(" · ")
            GoalPickLine(g.task.title, more, goal = g.task.goal, practice = g.task.rhythm.isNotBlank(), selected = g.task.id == current) { onGoal(g.task) }
        }
        if (shown.isEmpty() && pool.isNotEmpty() && typed.isNotEmpty() && onNew == null) PaperHint("Не нашёл — таких целей нет")
        if (pool.isEmpty() && typed.isEmpty()) PaperHint("Целей пока нет: «Сделать целью» в карточке дела или новая — словами выше.")
        if (typed.isEmpty() && onDir != null) {
            Text("Или прямо на направление", style = LocalPravkaType.current.label, color = mode.meta, modifier = Modifier.padding(top = 8.dp))
            for (d in dirs) GoalPickLine(d.name, d.zone, goal = false, selected = false) { onDir(d.key) }
        }
    }
}

@Composable
private fun GoalPickLine(text: String, more: String, goal: Boolean, selected: Boolean, practice: Boolean = false, onClick: () -> Unit) {
    val mode = LocalMode.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, style = LocalPravkaType.current.body, color = if (selected) mode.value else Ink.Text, fontWeight = if (selected) FontWeight.SemiBold else null)
            if (more.isNotBlank()) Text(more, style = LocalPravkaType.current.meta, color = mode.meta)
        }
    }
}

// ---------------------------------------------------------------- место в дереве

/** Место под значок у каждой строки «Места в дереве»: текст глубины d всегда начинается с d·[STEP] + [MARK]. */
private val MARK = 18.dp

/** Ромб цели в «Месте в дереве». */
private val Diamond = androidx.compose.foundation.shape.GenericShape { size, _ ->
    moveTo(size.width / 2f, 0f)
    lineTo(size.width, size.height / 2f)
    lineTo(size.width / 2f, size.height)
    lineTo(0f, size.height / 2f)
    close()
}

/** Глубже — уже не сдвигаем (цепочка длиннее десяти — патология, текст не должен уехать за край). */
private const val PLACE_DEPTH_MAX = 10

/**
 * «Место в дереве» карточки дела (`placeBlock` веба): «направление · …» мелко капслоком, дальше строки
 * [DelaKrug.Place.rows] ровными ступенями (16.10.2026, записи 3 и 10 отладки): предки, на уровне родителя — соседи
 * вместе с самим делом (жирно, заливкой), под ним — что внутри на два уровня, у свёрнутых — «· 3 внутри». У каждой
 * строки своё место под значок (цель — ромб, дело — кружок, проект — ▣), поэтому дело цели не «прилипает» к последней
 * подцели. Тап по цели или делу — его карточка, по проекту (›) — страница проекта; ниже — строка «дальше».
 */
@Composable
internal fun PlaceBlock(
    t: Dela.Task,
    p: DelaKrug.Place,
    dirs: List<DelaKrug.Direction>,
    snap: Dela.Snapshot,
    onTask: (Dela.Task) -> Unit,
    onProject: (String) -> Unit,
) {
    val mode = LocalMode.current
    val ty = LocalPravkaType.current
    val shape = RoundedCornerShape(16.dp)
    val ru = Locale.forLanguageTag("ru")
    Column(Modifier.fillMaxWidth().glass(shape, mode.glass).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text("МЕСТО В ДЕРЕВЕ", style = ty.overline, color = mode.meta)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            // «Направление · Дело», а не голое «ДЕЛО»: направление «Дело» сливалось с подписью «Дело» карточки (запись 10).
            Text(
                (if (p.main.isNotBlank()) "направление · " + (dirs.firstOrNull { it.key == p.main }?.name ?: p.main) else "без направления").uppercase(ru),
                style = ty.overline,
                color = mode.label,
            )
            if (p.labels.isNotEmpty()) Text(" · метки: " + DelaKrug.names(p.labels, dirs), style = ty.meta, color = mode.meta)
        }
        for (r in p.rows) PlaceRowLine(r, snap, onTask, onProject)
        Text(
            p.next,
            style = ty.label.copy(fontStyle = FontStyle.Italic),
            color = Ink.PlanText,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun PlaceRowLine(r: DelaKrug.PlaceRow, snap: Dela.Snapshot, onTask: (Dela.Task) -> Unit, onProject: (String) -> Unit) {
    val mode = LocalMode.current
    val ty = LocalPravkaType.current
    val task = r.task
    val click: (() -> Unit)? = when {
        r.role == DelaKrug.Role.ME || r.role == DelaKrug.Role.REST -> null
        r.mark == DelaKrug.Mark.PROJECT -> {
            {
                // Переходы по дереву — в действия записки «Что не так?» (запись 10: порядок тапов пришлось восстанавливать).
                ru.zf.pravka.core.DelaDebug.note("nav", "Место в дереве → проект «${snap.projects[r.projectId]?.name ?: r.text}»")
                onProject(r.projectId)
            }
        }
        task != null -> {
            {
                ru.zf.pravka.core.DelaDebug.note("nav", "Место в дереве → «${task.title.take(80)}»" + if (task.goal) " (цель)" else "")
                onTask(task)
            }
        }
        else -> null
    }
    val (style, color) = when (r.role) {
        DelaKrug.Role.ME -> ty.bodyStrong to Ink.TextStrong
        DelaKrug.Role.UP -> ty.body to (if (r.mark == DelaKrug.Mark.PROJECT) mode.label else Ink.Text)
        DelaKrug.Role.REST -> ty.meta to mode.meta
        else -> ty.body to (if (r.mark == DelaKrug.Mark.PROJECT) mode.label else Ink.TextSecondary)
    }
    // Значок — у первой строки текста, а не посередине переноса: иначе у длинного названия он съезжал к соседям.
    val line = with(androidx.compose.ui.platform.LocalDensity.current) {
        style.lineHeight.takeIf { it.isSp }?.toDp() ?: style.fontSize.takeIf { it.isSp }?.toDp()?.times(1.3f) ?: 20.dp
    }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().padding(start = STEP * minOf(r.depth, PLACE_DEPTH_MAX)).clip(RoundedCornerShape(8.dp))
            .then(if (click != null) Modifier.clickable(onClick = click) else Modifier).padding(vertical = 3.dp),
    ) {
        Box(Modifier.width(MARK).height(line), contentAlignment = Alignment.CenterStart) { PlaceMark(r) }
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                append(r.text)
                // Свёрнутый узел — бледно, сколько внутри: «· 3 внутри», «· 2 подцели, 1 дело».
                r.more?.let {
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = mode.meta, fontSize = ty.meta.fontSize))
                    append("  · " + it.words)
                    pop()
                }
                // Проект открывает страницу в списке, а не карточку — стрелкой.
                if (r.mark == DelaKrug.Mark.PROJECT) append(" ›")
            },
            style = style,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Значок строки в своих 18 dp: цель — ромб контуром, дело — кружок контуром, само дело — заливкой своей формы, проект — ▣. */
@Composable
private fun PlaceMark(r: DelaKrug.PlaceRow) {
    val mode = LocalMode.current
    val diamond = Diamond
    val pad = Modifier.padding(start = 2.dp)
    when (r.mark) {
        DelaKrug.Mark.GOAL -> Box(pad.size(9.dp).border(1.2.dp, mode.label, diamond))
        DelaKrug.Mark.DEED -> Box(pad.padding(start = 1.5.dp).size(6.dp).clip(CircleShape).border(1.dp, mode.label, CircleShape))
        DelaKrug.Mark.ME -> if (r.task?.goal == true) Box(pad.size(10.dp).clip(diamond).background(mode.value))
        else Box(pad.size(8.dp).clip(CircleShape).background(mode.value))
        DelaKrug.Mark.PROJECT -> Text("▣", style = LocalPravkaType.current.meta, color = mode.label, modifier = pad)
        DelaKrug.Mark.NONE -> Unit
    }
}
