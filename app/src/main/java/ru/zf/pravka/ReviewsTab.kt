package ru.zf.pravka

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.zf.pravka.core.ComparePolicy
import ru.zf.pravka.core.NightBoard
import ru.zf.pravka.core.NightReviewPolicy
import ru.zf.pravka.core.PromptTunePolicy
import ru.zf.pravka.core.ReviewDigest
import ru.zf.pravka.core.ShadowPolicy
import ru.zf.pravka.data.NightReviewStore
import ru.zf.pravka.data.PromptVersions
import ru.zf.pravka.data.StoreFiles
import ru.zf.pravka.data.shareFileIntent
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.InfoButton
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.PaperToggle
import ru.zf.pravka.ui.ScreenPad
import ru.zf.pravka.ui.fadingScroll

// «Ещё → Разборы» (16–18.09.2026): ночной разбор диктовок, правка промпта и
// ручное сравнение моделей (CompareCard); ночная тень снята 18.09, её старые
// прогоны остались в истории. Владелец, глядя на первые две версии (простыня изменений, потом
// группы по видам): «в самой плашке день сделаем овальные кнопки наверху:
// применено, предложено, идеи; в каждой можно отменять решение модели или
// принимать, причём если я принял — оно пропадает. Применено: у каждого
// крестик. Предложено: галка и крестик, наверху «принять все». Идеи: по
// каждому внизу совет; советы вместе с предложениями выгружать логом и
// отправлять в Claude Code». Так и сделано: чипы переключают список, строки
// уходят из списка по действию, лог — кнопкой.
//
// Второе издание (24.09.2026): тумблеры — строками с «i» (длинные пояснения
// под каждым тумблером ушли за неё), степперы — значками «−/+», чипы и
// кнопки — из набора; главная кнопка — одна на плашку.
//
// Третье издание (12.10.2026). Владелец: «надо сделать вкладку Разборы какой-то
// понятной, наверху должно быть executive summary»; «всё должно идти
// автоматом, я там не нужен»; «наверху прямо кнопку разобрать сейчас». Сверху —
// сводка словами (core/ReviewDigest.kt) с кнопкой «Разобрать сейчас», под ней
// последний удачный разбор (применено · не применено · идеи — решать нечего,
// только вернуть применённое), промпт, «что работает», настройки, сравнение,
// история. Открыл вкладку — красная точка на «С» в «Сегодня» гаснет.

private val dayTime = SimpleDateFormat("EEE dd.MM HH:mm", Locale("ru"))

private fun stageLabel(stage: String): String = NightBoard.stageLabel(stage)
private fun kindLabel(run: NightReviewStore.Run): String = NightBoard.kindLabel(run)

@Composable
internal fun ReviewsTab(app: PravkaApp) {
    val runs by app.nightReviewStore.runsFlow.collectAsState()
    LaunchedEffect(Unit) { app.nightReviewStore.all() }
    // Открыл вкладку — видел всё, что уже закончилось: красная точка гаснет.
    // Закончится новое, пока вкладка открыта, — тоже видел.
    val seen = ReviewDigest.seenMark(runs)
    LaunchedEffect(seen) { if (seen > 0L) app.settings.setReviewsSeenAt(seen) }
    var openHistory by remember { mutableStateOf(setOf<Long>()) }

    val reviews = runs.filter { it.isReview }
    // Под сводкой — последний удачный разбор (из него и «что выучено»); если
    // удачных не было — последний вообще, чтобы сбой был виден с причиной.
    val main = reviews.filter { it.stage == "done" && !NightBoard.failed(it) }.maxByOrNull { it.startedAt }
        ?: reviews.maxByOrNull { it.startedAt }
    val tune = runs.filter { it.isTune }.maxByOrNull { it.startedAt }
    val compare = runs.filter { it.isCompare }.maxByOrNull { it.startedAt }
    val shown = setOfNotNull(main?.id, tune?.id, compare?.id)
    val history = runs.filter { it.id !in shown }.sortedByDescending { it.startedAt }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .fadingScroll()
            .padding(ScreenPad.Padding),
        verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
    ) {
        SummaryCard(app, runs)
        if (main != null) ReviewCard(app, "Последний разбор · ${kindLabel(main)}", main)
        TuneCard(app, tune)
        BoardCard(app, runs)
        ControlsCard(app, runs)
        CompareCard(app, compare)
        if (history.isNotEmpty()) {
            SectionCard(label = "История") {
                for (run in history) {
                    val open = run.id in openHistory
                    val toggle = { openHistory = if (open) openHistory - run.id else openHistory + run.id }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = toggle),
                    ) {
                        Text(
                            "${dayTime.format(Date(run.startedAt))} · ${kindLabel(run)} · ${statusLine(run)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (NightBoard.failed(run)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        GlyphButton(
                            Glyphs.ChevronDown,
                            if (open) "скрыть" else "открыть",
                            onClick = toggle,
                            size = 34.dp,
                            modifier = Modifier.rotate(if (open) 180f else 0f),
                        )
                    }
                }
            }
            for (run in history.filter { it.id in openHistory }) {
                when {
                    run.isShadow -> ShadowCard(run)
                    run.isTune -> TuneCard(app, run)
                    run.isCompare -> CompareHistoryCard(app, run)
                    else -> ReviewCard(app, kindLabel(run).replaceFirstChar { it.uppercase() }, run)
                }
            }
        }
    }
}

/**
 * Сводка — первой плашкой (12.10.2026): работает ли, что Правка сама выучила,
 * что заметила, что с промптом, когда дальше и сколько стоит. Текст считает
 * `ReviewDigest`; здесь только раскладка и главная кнопка «Разобрать сейчас»
 * — батчем, окно от конца прошлого разбора до сейчас.
 */
@Composable
private fun SummaryCard(app: PravkaApp, runs: List<NightReviewStore.Run>) {
    val context = LocalContext.current
    val settings = app.settings
    val versions by app.promptVersions.flow.collectAsState()
    LaunchedEffect(Unit) { app.promptVersions.all() }
    val reviewOn by settings.nightReviewEnabledFlow.collectAsState(initial = true)
    val tuneOn by settings.promptTuneEnabledFlow.collectAsState(initial = true)
    val hour by settings.nightReviewHourFlow.collectAsState(initial = 3)
    var busy by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val reviewRunning = runs.any { it.active && it.isReview }
    val d = ReviewDigest.build(
        runs, versions.firstOrNull { it.status == "active" }, reviewOn, tuneOn, hour,
        app.lastNightTickMs, System.currentTimeMillis(),
    )
    val c = MaterialTheme.colorScheme
    PaperCard(label = "Сводка") {
        Text(
            d.status,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (d.bad) c.error else c.onSurface,
        )
        if (d.learned.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(d.learned, style = MaterialTheme.typography.bodyMedium)
        }
        if (d.summary.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                d.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = c.onSurfaceVariant,
                maxLines = if (more) Int.MAX_VALUE else 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { more = !more },
            )
        }
        if (d.ideas.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Заметил за моделью", style = MaterialTheme.typography.labelMedium, color = c.primary)
            for (idea in d.ideas) {
                Text(
                    "· $idea",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (more) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp).clickable { more = !more },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(d.prompt, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text("${d.next} ${d.spent}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            PaperButton(
                if (reviewRunning) "Разбор идёт…" else "Разобрать сейчас",
                icon = Glyphs.Play,
                primary = true,
                enabled = !busy && !reviewRunning,
                onClick = {
                    busy = true
                    app.appScope.launch {
                        app.nightReview.start(NightReviewPolicy.NOW, manual = true)
                            .onSuccess {
                                Feedback.toast(
                                    context,
                                    if (it.active) "Отправлено батчем — результат обычно в течение часа, на «С» в «Сегодня» загорится точка" else it.summary,
                                )
                            }
                            .onFailure { Feedback.toast(context, "Не запустился: ${it.message}") }
                        busy = false
                    }
                },
            )
        }
    }
}

/**
 * Ступенька «−  значение  +» строкой: число с подписью слева, значки справа.
 * В наборе такой детали нет, а здесь их две — потолок денег и час запуска
 * (24.09.2026); `PaperSlider` сюда не подходит: шаг один, а ряд короткий.
 */
@Composable
private fun StepperRow(
    text: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    info: String? = null,
    infoTitle: String = text,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (info != null) InfoButton(infoTitle, info)
        GlyphButton(Glyphs.Minus, "меньше", onClick = onMinus, tint = MaterialTheme.colorScheme.onSurface)
        GlyphButton(Glyphs.Plus, "больше", onClick = onPlus, tint = MaterialTheme.colorScheme.onSurface)
    }
}

/** Тумблеры, ручной запуск, идущие прогоны с прогрессом и кнопками; лог за месяц. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlsCard(app: PravkaApp, runs: List<NightReviewStore.Run>) {
    val context = LocalContext.current
    val scope = app.appScope
    val settings = app.settings
    val enabled by settings.nightReviewEnabledFlow.collectAsState(initial = true)
    val tuneOn by settings.promptTuneEnabledFlow.collectAsState(initial = true)
    val hour by settings.nightReviewHourFlow.collectAsState(initial = 3)
    var busy by remember { mutableStateOf(false) }
    val tuneRunning = runs.any { it.active && it.isTune }

    SectionCard(label = "Настройки разбора") {
        PaperToggle(
            title = "Разбор журналов батчем",
            hint = "разбор и проверка моделью, согласование — в коде",
            checked = enabled,
            onCheckedChange = { on -> scope.launch { settings.setNightReviewEnabled(on) } },
            info = "Всё само, решать ничего не нужно: одобренное проверкой ложится в словарь, остальное — «не применено» с причиной. Промпт не трогает. Недельный — в ночь на пятницу; пропущенный (потолок трат, выключенный телефон) догоняется при первой возможности. Обрезанный по длине проход повторяется на ступень ниже усилия. Модель и усилие — в настройках, группа «Модели».",
        )
        val dailyOn by settings.nightDailyEnabledFlow.collectAsState(initial = false)
        PaperToggle(
            title = "Сутки каждую ночь",
            checked = dailyOn,
            enabled = enabled,
            onCheckedChange = { on -> scope.launch { settings.setNightDailyEnabled(on) } },
            info = "Выключено с завода: ослышка становится словарной, когда повторяется, а за сутки она редко повторится — шесть ночей по $0,6–0,7 давали ту же картину, что одна недельная. «Разобрать сейчас» в сводке работает и так.",
        )
        PaperToggle(
            title = "Правка промпта раз в неделю",
            hint = "Опус 5.5 по идеям недели, с измерением",
            checked = tuneOn,
            onCheckedChange = { on -> scope.launch { settings.setPromptTuneEnabled(on) } },
            info = "В ночь на субботу, после недельного разбора (ждёт его идей до двух суток). Новый промпт перечищает диктовки недели, слепой судья сравнивает с прежним; принимается только заметный перевес; через неделю откат, если правок руками стало больше.",
        )
        Spacer(Modifier.height(4.dp))
        val budget by settings.nightBudgetUsdFlow.collectAsState(initial = 4)
        StepperRow(
            text = "Потолок дня для автоматов: $$budget",
            infoTitle = "Потолок дня",
            onMinus = { scope.launch { settings.setNightBudgetUsd(budget - 1) } },
            onPlus = { scope.launch { settings.setNightBudgetUsd(budget + 1) } },
            info = "Расход по приложению за вчера или за сегодня выше потолка — разбор и правка промпта сами не стартуют, идущее измерение ждёт завтра. Кнопки потолок не смотрят.",
        )
        StepperRow(
            text = "Запуск в ${"%02d".format(hour)}:00",
            onMinus = { scope.launch { settings.setNightReviewHour((hour + 23) % 24) } },
            onPlus = { scope.launch { settings.setNightReviewHour((hour + 1) % 24) } },
        )
        Spacer(Modifier.height(6.dp))
        // Разбор сейчас — главной кнопкой в сводке; здесь — только правка промпта вне очереди.
        Row {
            Spacer(Modifier.weight(1f))
            PaperButton(
                "Правка промпта сейчас",
                enabled = !busy && !tuneRunning,
                onClick = {
                    busy = true
                    scope.launch {
                        app.promptTuner.start(manual = true)
                            .onSuccess { Feedback.toast(context, if (it.active) "Отправлено: предложение, измерение, судья — час-два" else it.summary) }
                            .onFailure { Feedback.toast(context, "Не запустилась: ${it.message}") }
                        busy = false
                    }
                },
            )
        }
        // Ревизия батчей у Anthropic по кнопке: то же, что делает старт приложения.
        PaperTextButton("Проверить батчи у Anthropic", icon = Glyphs.Refresh, enabled = !busy, onClick = {
            busy = true
            scope.launch {
                val closed = runCatching { ru.zf.pravka.core.NightSweep.closeShadowRuns(app) }.getOrDefault(0)
                val r = ru.zf.pravka.core.NightSweep.sweepBatches(app)
                Feedback.toast(context, (if (closed > 0) "закрыто прогонов тени $closed; " else "") + r)
                busy = false
            }
        })
        val month = runs.filter { it.stage == "done" && it.startedAt > System.currentTimeMillis() - 30 * 86_400_000L }
        if (month.isNotEmpty()) {
            PaperTextButton(
                "Лог за 30 дней для Claude Code",
                icon = Glyphs.Export,
                onClick = { exportLog(context, app, month, "Лог разборов за 30 дней") },
            )
        }
    }
}

private fun statusLine(run: NightReviewStore.Run): String {
    if (run.active) return "идёт ${stageLabel(run.stage)}"
    if (NightBoard.failed(run)) return "не удался"
    val money = if (run.costUsd > 0) " · $" + "%.2f".format(Locale.US, run.costUsd) else ""
    if (run.isShadow || run.isTune || run.isCompare) return "готово$money"
    val parts = ArrayList<String>()
    parts += "применено ${run.applied()}"
    val skipped = run.changes.count { !it.isNote && it.status in NOT_APPLIED }
    if (skipped > 0) parts += "не применено $skipped"
    val ideas = run.changes.count { it.kind == "note" }
    if (ideas > 0) parts += "идей $ideas"
    return parts.joinToString(" · ") + money
}

/** Что разбор не применил — с причиной; «предложено» и «возвращено» — у прогонов до 12.10.2026. */
private val NOT_APPLIED = setOf("skipped", "rejected", "proposed", "reverted", "failed")

/** Сводка без первой строки-заголовка («Применено N…»): её карточка считает сама и показывает живьём. */
private fun summaryBody(summary: String): String =
    summary.split("\n\n").filterIndexed { i, p -> !(i == 0 && p.startsWith("Применено ")) }.joinToString("\n\n").trim()

/** Подзаголовки внутри списка: добавления по виду, потом выключенное, переведённое, правила. */
private fun groupOf(c: NightReviewStore.Change): String = when (c.kind) {
    "dict_add" -> c.mode.ifBlank { "?" }
    "dict_disable" -> "Выключено"
    "dict_mode" -> "Вид изменён"
    "rule_enable", "rule_disable" -> "Правила обработки"
    else -> ""
}

private val groupOrder = listOf("HARD", "HINT", "PROTECT", "Выключено", "Вид изменён", "Правила обработки")

private fun exportLog(context: Context, app: PravkaApp, runs: List<NightReviewStore.Run>, title: String) {
    app.appScope.launch {
        runCatching {
            // Табло и хвост журнала — в каждый лог: чтобы по нему было видно не только находки, но и что работает.
            val all = app.nightReviewStore.all()
            val board = NightBoard.render(
                NightBoard.build(
                    all, app.settings.nightReviewEnabledFlow.first(),
                    app.settings.promptTuneEnabledFlow.first(), app.settings.nightReviewHourFlow.first(),
                    withContext(Dispatchers.IO) { evalSummary(app) }, app.lastNightTickMs, System.currentTimeMillis(),
                    dailyOn = app.settings.nightDailyEnabledFlow.first(),
                )
            )
            val journal = withContext(Dispatchers.IO) { app.nightLog.readLast(120) }
            val text = NightReviewPolicy.exportLog(runs, dateOf = { dayTime.format(Date(it)) }, board = board, journal = journal)
            val file = File(context.filesDir, "night-review-log.md")
            withContext(Dispatchers.IO) { StoreFiles.writeAtomic(file, text) }
            context.startActivity(Intent.createChooser(shareFileIntent(context, file, "text/markdown"), title))
        }.onFailure { Feedback.toast(context, "Лог не собрался: ${it.message}") }
    }
}

/** «Лог для Claude Code» — одна и та же тихая кнопка у каждого прогона. */
@Composable
private fun LogButton(onClick: () -> Unit) {
    PaperTextButton("Лог для Claude Code", icon = Glyphs.Export, onClick = onClick)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewCard(app: PravkaApp, title: String, run: NightReviewStore.Run) {
    val context = LocalContext.current
    var showSummary by remember(run.id) { mutableStateOf(false) }
    val applied = run.changes.filter { it.status == "applied" }
    // Решать нечего (12.10.2026: «я там не нужен»): не применённое — с причиной,
    // у старых прогонов сюда же ложатся их «предложено».
    val skipped = run.changes.filter { !it.isNote && it.status in NOT_APPLIED }
    val ideas = run.changes.filter { it.kind == "note" }
    val dropped = run.changes.count { it.status == "dropped" }
    var tab by remember(run.id) {
        mutableStateOf(if (applied.isNotEmpty()) "applied" else if (ideas.isNotEmpty()) "ideas" else "skipped")
    }
    SectionCard(label = "$title · ${dayTime.format(Date(run.startedAt))}${if (run.manual) " · вручную" else ""}") {
        Text(
            if (run.active) "идёт ${stageLabel(run.stage)}" + (if (run.progress.isNotBlank()) " · ${run.progress}" else "")
            else if (NightBoard.failed(run)) "не удался"
            else "готово" + (if (run.costUsd > 0) " · $" + "%.2f".format(Locale.US, run.costUsd) else "") +
                (if (dropped > 0) " · отброшено низких $dropped" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (run.error.isNotBlank()) {
            // Причина целиком (правило 6), но одной строкой на сбой — не простынёй.
            Text(run.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (run.changes.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            ChipRow {
                PaperChip("Применено ${applied.size}", selected = tab == "applied", onClick = { tab = "applied" })
                PaperChip("Не применено ${skipped.size}", selected = tab == "skipped", onClick = { tab = "skipped" })
                PaperChip("Идеи ${ideas.size}", selected = tab == "ideas", onClick = { tab = "ideas" })
            }
            Spacer(Modifier.height(4.dp))
            when (tab) {
                "applied" -> if (applied.isEmpty()) HintText("Само ничего не применилось.") else GroupedRows(app, run, applied)
                "skipped" -> if (skipped.isEmpty()) HintText("Всё найденное легло в словарь.") else GroupedRows(app, run, skipped)
                else -> if (ideas.isEmpty()) HintText("Заметок про модель и распознаватель нет.") else for (c in ideas) NoteRow(c)
            }
        }
        FlowRow {
            if (run.summary.isNotBlank()) {
                PaperTextButton(
                    if (showSummary) "Скрыть сводку" else "Сводка",
                    icon = if (showSummary) Glyphs.ChevronUp else Glyphs.ChevronDown,
                    onClick = { showSummary = !showSummary },
                )
            }
            LogButton { exportLog(context, app, listOf(run), "Лог разбора") }
        }
        if (showSummary) {
            Text(summaryBody(run.summary), style = MaterialTheme.typography.bodySmall)
            for (p in run.replies.takeLast(3)) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Ты: ${p.text}\nРазбор: ${p.result}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GroupedRows(app: PravkaApp, run: NightReviewStore.Run, items: List<NightReviewStore.Change>) {
    val byGroup = items.groupBy { groupOf(it) }
    for (g in groupOrder + (byGroup.keys - groupOrder.toSet())) {
        val list = byGroup[g] ?: continue
        Text(
            "$g · ${list.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        for (c in list) ChangeRow(app, run, c)
    }
}

/**
 * Строка изменения: у применённого слева крестик «вернуть» (единственное
 * действие, которое осталось владельцу), справа слово и причина в две строки,
 * тап раскрывает. У не применённого вместо причины находки — почему не легло.
 */
@Composable
private fun ChangeRow(app: PravkaApp, run: NightReviewStore.Run, c: NightReviewStore.Change) {
    val context = LocalContext.current
    var open by remember(c.id) { mutableStateOf(false) }
    // Вид уже назван подзаголовком группы — в строке само слово.
    val title = when (c.kind) {
        "dict_add", "dict_disable" -> c.from + if (c.to.isNotBlank()) " → ${c.to}" else ""
        "dict_mode" -> "${c.from}: ${c.mode} → ${c.toMode}"
        else -> c.title()
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        // Пока прогон идёт, кнопки нет: следующий проход перепишет список своей копией.
        if (!run.active && c.status == "applied") {
            GlyphButton(Glyphs.Close, "Вернуть", onClick = {
                app.appScope.launch {
                    app.nightReview.act(run.id, c.id, "revert")
                        .onFailure { Feedback.toast(context, "Не получилось: ${it.message}") }
                }
            }, tint = MaterialTheme.colorScheme.error, size = 36.dp)
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f).clickable { open = !open }) {
            Text(
                (if (c.status == "reverted") "↩ " else "") + title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (c.status == "applied") FontWeight.SemiBold else FontWeight.Normal,
            )
            val reason = when (c.status) {
                "applied" -> ""
                "reverted" -> "ты вернул"
                "rejected" -> "проверка отклонила" + if (c.verdictWhy.isNotBlank()) ": ${c.verdictWhy}" else ""
                "proposed" -> "старый прогон: ждало решения"
                else -> c.statusNote
            }
            val details = listOf(
                reason,
                c.why,
                if (open && c.verdict.isNotBlank() && c.verdict != "hold" && c.status != "rejected") "проверка: ${c.verdict}${if (c.verdictWhy.isNotBlank()) " — ${c.verdictWhy}" else ""}" else "",
            ).filter { it.isNotBlank() }.joinToString(" · ")
            if (details.isNotBlank()) {
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (open) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Идея: наблюдение разбора и совет разработчику — что поменять в промпте, словаре, распознавателе. */
@Composable
private fun NoteRow(c: NightReviewStore.Change) {
    var open by remember(c.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { open = !open }) {
        Text(
            "· ${c.why}",
            style = MaterialTheme.typography.bodySmall,
            maxLines = if (open) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (c.note.isNotBlank()) {
            Text(
                "Совет: ${c.note}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = if (open) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@Composable
private fun ShadowCard(run: NightReviewStore.Run) {
    val context = LocalContext.current
    val app = context.applicationContext as PravkaApp
    SectionCard(label = "Тень · ${dayTime.format(Date(run.startedAt))}${if (run.manual) " · вручную" else ""}") {
        Text(
            statusLine(run) + (if (run.active && run.progress.isNotBlank()) " · ${run.progress}" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (run.error.isNotBlank()) {
            Text(run.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (run.summary.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(run.summary, style = MaterialTheme.typography.bodyMedium)
        }
        val examples = run.changes.filter { it.kind == ShadowPolicy.KIND }
        if (examples.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Примеры", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            for (c in examples) ShadowRow(c)
        }
        if (run.stage == "done") LogButton { exportLog(context, app, listOf(run), "Лог тени") }
    }
}

/** Пример тени: кто лучше и почему; тексты — по тапу, чтобы карточка не разъезжалась. */
@Composable
private fun ShadowRow(c: NightReviewStore.Change) {
    var open by remember(c.id) { mutableStateOf(false) }
    val head = when (c.verdict) {
        "tie", "same" -> "равноценно"
        "both_bad" -> "оба плохо"
        "unjudged" -> "без вердикта"
        else -> "лучше ${c.verdict}"
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { open = !open }) {
        Text(
            "$head — ${c.verdictWhy}" + (if (c.why.isNotBlank()) " (${c.why})" else ""),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (open) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (open) {
            Text("Надиктовано: ${c.from}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(3.dp))
            Text("${c.mode}: ${c.to}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(3.dp))
            Text("${c.toMode}: ${c.note}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Плашка «Сравнение моделей» (18.09.2026) — ручная тень. Владелец: «вернём
 * тень, но без этих 200 сообщений и без автостарта; я буду сам запускать и
 * смотреть; сравнивай всегда Сонет и Опус на обыкновенном режиме и Опус на
 * low; период будем выбирать». Период и потолок диктовок — чипами, ориентир
 * цены до запуска, кнопка «Сравнить»; ниже — последний прогон: ход, сводка,
 * примеры с тремя текстами по тапу. Автостарта нет: тик службы только
 * докручивает начатое.
 */
@Composable
private fun CompareCard(app: PravkaApp, run: NightReviewStore.Run?) {
    val context = LocalContext.current
    val scope = app.appScope
    var days by remember { mutableStateOf(1) }
    var cap by remember { mutableStateOf(20) }
    var busy by remember { mutableStateOf(false) }
    val running = run?.active == true
    SectionCard(
        label = "Сравнение моделей",
        info = "Три плеча чистят одни и те же диктовки периода тем же промптом и словарём, что кнопка «П»: " +
            ComparePolicy.ARMS.joinToString(", ") { it.label } + ". Судья слепой (дорога «Судья правки промпта»). " +
            "Само не запускается, ничего не меняет.\n\n" +
            "Берутся последние чистки периода без контекста поля. Ориентир цены — три чистки и судья " +
            "на каждую диктовку. Потолок дня кнопка не смотрит.",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Период", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(84.dp))
            ChipRow {
                for (d in ComparePolicy.DAYS) PaperChip(daysLabel(d), selected = days == d, onClick = { days = d })
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Диктовок", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(84.dp))
            ChipRow {
                for (n in ComparePolicy.CAPS) PaperChip("до $n", selected = cap == n, onClick = { cap = n })
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Ориентир цены — живое число, оно меняется с выбором потолка.
            Box(Modifier.weight(1f).padding(end = 8.dp)) {
                HintText("около $" + "%.2f".format(Locale.US, cap * ComparePolicy.USD_PER_ITEM) + " за $cap диктовок")
            }
            PaperButton(
                if (running) "Идёт…" else "Сравнить",
                icon = Glyphs.Play,
                primary = true,
                enabled = !busy && !running,
                onClick = {
                    busy = true
                    scope.launch {
                        val started = app.modelCompare.start(days, cap)
                            .onSuccess { Feedback.toast(context, if (it.active) "Пошло: ${it.progress}" else it.summary) }
                            .onFailure { Feedback.toast(context, "Не запустилось: ${it.message}") }
                            .getOrNull()
                        busy = false
                        // Первый кусок — сразу, не дожидаясь пятиминутного тика службы; прогресс виден в карточке.
                        if (started?.active == true) app.modelCompare.pollNow(started.id)
                    }
                },
            )
        }
        if (run != null) {
            Spacer(Modifier.height(8.dp))
            Text("Последнее · ${dayTime.format(Date(run.startedAt))}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            CompareBody(app, run)
        }
    }
}

private fun daysLabel(d: Int): String = when (d) {
    1 -> "сутки"
    7 -> "неделя"
    else -> "$d дня"
}

/** Прогон сравнения из «Истории». */
@Composable
private fun CompareHistoryCard(app: PravkaApp, run: NightReviewStore.Run) {
    SectionCard(label = "Сравнение моделей · ${dayTime.format(Date(run.startedAt))}") { CompareBody(app, run) }
}

/** Тело прогона сравнения: ход с кнопками, сбой, сводка, примеры, лог. */
@Composable
private fun CompareBody(app: PravkaApp, run: NightReviewStore.Run) {
    val context = LocalContext.current
    Text(
        statusLine(run) + (if (run.active && run.progress.isNotBlank()) " · ${run.progress}" else ""),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (run.active) Row {
        PaperTextButton("Продолжить сейчас", icon = Glyphs.Refresh, onClick = {
            app.appScope.launch {
                app.modelCompare.pollNow(run.id)
                    .onSuccess { Feedback.toast(context, it) }.onFailure { Feedback.toast(context, "Не прошло: ${it.message}") }
            }
        })
        PaperTextButton("Отменить", icon = Glyphs.Close, color = MaterialTheme.colorScheme.error, onClick = {
            app.appScope.launch {
                app.modelCompare.cancel(run.id)
                    .onSuccess { Feedback.toast(context, "Отменено") }.onFailure { Feedback.toast(context, "Не отменилось: ${it.message}") }
            }
        })
    }
    if (run.error.isNotBlank()) {
        Text(run.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (run.summary.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(run.summary, style = MaterialTheme.typography.bodyMedium)
    }
    val examples = run.changes.filter { it.kind == ComparePolicy.KIND }
    if (examples.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Примеры", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        for (c in examples) CompareRow(c)
    }
    if (run.stage == "done") LogButton { exportLog(context, app, listOf(run), "Лог сравнения моделей") }
}

/** Пример сравнения: лучшее и худшее плечо, почему; надиктовка и три текста — по тапу. */
@Composable
private fun CompareRow(c: NightReviewStore.Change) {
    var open by remember(c.id) { mutableStateOf(false) }
    val head = (if (c.verdict.isBlank()) "без вердикта" else "лучше ${c.verdict}") + (if (c.note.isNotBlank()) ", хуже ${c.note}" else "")
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { open = !open }) {
        Text(
            "$head — ${c.verdictWhy}" + (if (c.why.isNotBlank()) " (${c.why})" else ""),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (open) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (open) {
            Text("Надиктовано: ${c.from}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for ((label, text) in ComparePolicy.armTexts(c.to)) {
                Spacer(Modifier.height(3.dp))
                Text("$label: $text", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Плашка «Промпт»: какая версия CLEAN действует и как принималась, последний
 * прогон недельной правки с примерами судьи, кнопка «Вернуть прежний промпт».
 */
@Composable
private fun TuneCard(app: PravkaApp, run: NightReviewStore.Run?) {
    val context = LocalContext.current
    val versions by app.promptVersions.flow.collectAsState()
    LaunchedEffect(Unit) { app.promptVersions.all() }
    val active = versions.firstOrNull { it.status == "active" }
    SectionCard(label = "Промпт" + (run?.let { " · ${dayTime.format(Date(it.startedAt))}${if (it.manual) " · вручную" else ""}" } ?: "")) {
        if (active == null) {
            Text("Действует заводской промпт CLEAN из сборки.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                "Действует версия подбора от ${dayTime.format(Date(active.at))}: ${active.note}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            val metrics = buildString {
                append("Принята: судья ${active.judgeBetter}:${active.judgeWorse} (равноценных ${active.judgeTie})")
                if (active.ownerPairs > 0) append(", к правкам владельца ${PromptTunePolicy.pct(active.simNew)} против ${PromptTunePolicy.pct(active.simOld)}")
                if (active.corrAfter >= 0) append(". Правок руками после: ${PromptTunePolicy.pct(active.corrAfter)} чисток против ${PromptTunePolicy.pct(active.corrBefore)} до")
                else append(". Доля правок руками сверится через неделю")
                append('.')
            }
            Text(metrics, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PaperTextButton(
                "Вернуть прежний промпт",
                icon = Glyphs.Undo,
                color = MaterialTheme.colorScheme.error,
                onClick = {
                    app.appScope.launch {
                        app.promptTuner.revertActive()
                            .onSuccess { Feedback.toast(context, it) }
                            .onFailure { Feedback.toast(context, "Не получилось: ${it.message}") }
                    }
                },
            )
        }
        if (run == null) {
            HintText("Правки промпта ещё не было: идёт сама в ночь на субботу, после недельного разбора.")
            return@SectionCard
        }
        Spacer(Modifier.height(6.dp))
        Text(
            statusLine(run) + (if (run.active && run.progress.isNotBlank()) " · ${run.progress}" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (run.error.isNotBlank()) Text(run.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (run.summary.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(run.summary, style = MaterialTheme.typography.bodyMedium)
        }
        val examples = run.changes.filter { it.kind == ShadowPolicy.KIND }
        if (examples.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Примеры судьи", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            for (c in examples) ShadowRow(c)
        }
        val history = versions.filter { it.status != "active" }.sortedByDescending { it.at }.take(6)
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Прежние версии", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            for (v in history) VersionRow(v)
        }
        if (run.stage == "done") LogButton { exportLog(context, app, listOf(run), "Лог правки промпта") }
    }
}

@Composable
private fun VersionRow(v: PromptVersions.Version) {
    val status = when (v.status) {
        "superseded" -> "сменена"
        "reverted" -> "возвращена"
        "rejected" -> "отклонена"
        else -> v.status
    }
    Text(
        "· ${dayTime.format(Date(v.at))} · $status · ${v.note}" + (if (v.statusNote.isNotBlank()) " — ${v.statusNote}" else ""),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

/** Последний эвал из файла результатов — для табло (читать не на главном потоке). */
private fun evalSummary(app: PravkaApp): NightBoard.EvalSummary? =
    app.evalStore.lastRun()?.let { o ->
        NightBoard.EvalSummary(o.optLong("at"), o.optDouble("avg", 0.0), o.optInt("exact"), o.optInt("total"))
    }

/**
 * Плашка «Что работает»: по строке на автомат — пульс службы, разбор суток и
 * недели, тень, правка промпта, эвал; под идущими — «Проверить сейчас» и
 * «Отменить». Ниже — журнал ночных автоматов (night.log): последние строки,
 * сбои красным. Владелец (17.09): «единый лог, который будет показывать, что
 * работает, что нет». «Обновить» — значком в строке подписи (24.09.2026):
 * три слова в ряд под табло на узком экране не помещались.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardCard(app: PravkaApp, runs: List<NightReviewStore.Run>) {
    val context = LocalContext.current
    val settings = app.settings
    val reviewOn by settings.nightReviewEnabledFlow.collectAsState(initial = true)
    val dailyOn by settings.nightDailyEnabledFlow.collectAsState(initial = false)
    val tuneOn by settings.promptTuneEnabledFlow.collectAsState(initial = true)
    val hour by settings.nightReviewHourFlow.collectAsState(initial = 3)
    var eval by remember { mutableStateOf<NightBoard.EvalSummary?>(null) }
    var journal by remember { mutableStateOf<List<String>>(emptyList()) }
    var showJournal by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    // Перечитывать при каждом движении прогонов и по кнопке «Обновить»; файлы — не на главном потоке.
    val stamp = runs.joinToString("|") { "${it.id}:${it.stage}:${it.progress}" }
    LaunchedEffect(stamp, tick) {
        eval = withContext(Dispatchers.IO) { evalSummary(app) }
        journal = withContext(Dispatchers.IO) { app.nightLog.readLast(40) }
    }
    val lines = NightBoard.build(runs, reviewOn, tuneOn, hour, eval, app.lastNightTickMs, System.currentTimeMillis(), dailyOn = dailyOn)
    PaperCard(
        label = "Что работает",
        trailing = { GlyphButton(Glyphs.Refresh, "обновить", onClick = { tick++ }, size = 30.dp) },
    ) {
        for (l in lines) {
            val color = when (l.state) {
                "fail" -> MaterialTheme.colorScheme.error
                "stale" -> MaterialTheme.colorScheme.error
                "running" -> MaterialTheme.colorScheme.primary
                "off", "none" -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            }
            val mark = when (l.state) { "ok" -> "✓"; "fail" -> "✗"; "running" -> "●"; "stale" -> "!"; "off" -> "—"; else -> "·" }
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text("$mark ${l.title}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = color)
                Text(l.text, style = MaterialTheme.typography.bodySmall, color = color)
                if (l.next.isNotBlank()) {
                    Text("следующий: ${l.next}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val run = runs.firstOrNull { it.id == l.runId && it.active }
                if (run != null) Row {
                    PaperTextButton("Проверить сейчас", icon = Glyphs.Refresh, onClick = {
                        app.appScope.launch {
                            val r = when {
                                run.isTune -> app.promptTuner.pollNow(run.id)
                                run.isCompare -> app.modelCompare.pollNow(run.id)
                                else -> app.nightReview.pollNow(run.id)
                            }
                            r.onSuccess { Feedback.toast(context, it) }.onFailure { Feedback.toast(context, "Опрос не прошёл: ${it.message}") }
                            tick++
                        }
                    })
                    PaperTextButton("Отменить", icon = Glyphs.Close, color = MaterialTheme.colorScheme.error, onClick = {
                        app.appScope.launch {
                            val r = when {
                                run.isTune -> app.promptTuner.cancel(run.id)
                                run.isCompare -> app.modelCompare.cancel(run.id)
                                else -> app.nightReview.cancel(run.id)
                            }
                            r.onSuccess { Feedback.toast(context, "Отменено") }.onFailure { Feedback.toast(context, "Не отменилось: ${it.message}") }
                            tick++
                        }
                    })
                }
            }
        }
        FlowRow {
            val fails = journal.count { NightBoard.isFailureLine(it) }
            PaperTextButton(
                (if (showJournal) "Скрыть журнал" else "Журнал") + (if (fails > 0) " · сбоев $fails" else ""),
                icon = Glyphs.ListLines,
                color = if (fails > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                onClick = { showJournal = !showJournal },
            )
            PaperTextButton("Весь журнал", icon = Glyphs.Export, onClick = {
                runCatching { context.startActivity(Intent.createChooser(app.nightLog.shareIntent(), "Журнал ночных автоматов")) }
                    .onFailure { Feedback.toast(context, "Журнала ещё нет") }
            })
        }
        if (showJournal) {
            if (journal.isEmpty()) HintText("Журнал пуст: автоматы ещё ничего не писали.")
            for (line in journal.asReversed()) {
                val fail = NightBoard.isFailureLine(line)
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (fail) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}
