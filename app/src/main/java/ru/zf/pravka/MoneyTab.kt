package ru.zf.pravka

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import ru.zf.pravka.core.MoneyCategories
import ru.zf.pravka.core.MoneyEngine
import ru.zf.pravka.core.MoneyEntry
import ru.zf.pravka.core.MoneyFormat
import ru.zf.pravka.core.MoneyMerchants
import ru.zf.pravka.core.MoneyScope
import ru.zf.pravka.core.MoneyStats
import ru.zf.pravka.trigger.PravkaAccessibilityService
import ru.zf.pravka.trigger.onMoneyTap
import ru.zf.pravka.trigger.showMoneyPlate
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperHint

// Вкладка «Деньги» (23.09.2026). Сверху — неделя: сколько ушло и пришло, по
// категориям, с тумблером «+ ЗФ». Дальше то, что ждёт владельца: траты без
// «ОК» и вопросы сверки. Потом выписки (файл, буфер, «Поделиться»), журнал
// недели и справочник получателей. Всё тяжёлое — в `MoneyEngine`, здесь
// только показать и передать ответ.
//
// Справочник — два слоя: заводской в assets и свои правила текстом на
// экране; свои перебивают заводские (см. `core/MoneyRules.kt`).


@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MoneyTab(app: PravkaApp) {
    val state by app.moneyStore.stateFlow.collectAsState()
    val pOn by app.settings.mScopePersonalFlow.collectAsState(initial = true)
    val zOn by app.settings.mScopeZfFlow.collectAsState(initial = false)
    // «Личное · ЗФ»: итоги — по назначению, ДДС и счета — по стороне (см. `MoneyScope`).
    val ms = remember(state, pOn, zOn) { app.moneyEngine.scope(pOn, zOn) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val today = remember { MoneyStats.dayOf(System.currentTimeMillis()) }
    var kind by remember { mutableStateOf(MoneyStats.Kind.MONTH) }
    var period by remember(kind) { mutableStateOf(MoneyStats.of(kind, today)) }
    var busy by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MoneyEntry?>(null) }

    LaunchedEffect(Unit) { runCatching { app.moneyStore.load() } }

    // Выписки файлами: CSV Тинькова и Альфы, .xlsx МКБ. Несколько за раз — недельный пакет.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val files = uris.mapNotNull { u ->
            runCatching { context.contentResolver.openInputStream(u)?.use { it.readBytes() } }.getOrNull()
        }
        busy = "загружаю…"
        scope.launch {
            val lines = app.moneyEngine.importFiles(files)
            busy = ""
            Feedback.toast(context, lines.joinToString("\n"), long = true)
        }
    }

    val entries = state.entries
    val totals = remember(state, period, ms) { MoneyStats.totals(entries, period, ms) }
    val cats = remember(state, period, ms) { MoneyStats.categories(entries, period, ms) }
    val daily = remember(state, period, ms) { MoneyStats.daily(entries, period, ms) }
    val pace = remember(state, period, ms) { MoneyStats.pace(entries, period, ms, today) }
    val trend = remember(state, ms) { MoneyStats.trend(entries, today, 6, ms) }
    val recurring = remember(state, ms) { MoneyStats.recurring(entries, today, ms) }
    val biggest = remember(state, period, ms) { MoneyStats.biggest(entries, period, ms) }
    val drafts = remember(state) { entries.filter { it.draft && !it.dropped }.sortedByDescending { it.ts } }
    val questions = remember(state) { app.moneyEngine.questions() }
    val journal = remember(state, period) {
        entries.filter { !it.draft && !it.dropped && it.ts >= period.from && it.ts < period.to }.sortedByDescending { it.ts }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---- Спросить Claude — наверху, как просил владелец ----
        // ---- Личное · ЗФ — сверху: от них зависит вся вкладка ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = ms.personal,
                onClick = { if (ms.zf) scope.launch { app.settings.setMScope(personal = !ms.personal, zf = true) } },
                label = { Text("Личное") },
            )
            Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = ms.zf,
                onClick = { if (ms.personal) scope.launch { app.settings.setMScope(personal = true, zf = !ms.zf) } },
                label = { Text("ЗФ") },
            )
            Spacer(Modifier.width(10.dp))
            // Последнюю включённую не выключить: пустая вкладка читается как поломка.
            PaperHint(if (ms.both) "всё вместе, без ВГО" else if (ms.zf) "только ЗФ" else "только личное")
        }
        AskCard(app)
        // Траты голосом прямо отсюда — тот же тап, что по «₽»: плашка с суммами и «ОК».
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                val service = PravkaAccessibilityService.instance
                if (service == null) Feedback.toast(app, app.getString(R.string.toast_no_service))
                else service.onMoneyTap()
            }) { Text("🎙 Наговорить траты") }
        }

        // ---- Период: неделя или месяц, «ушло» и «пришло» по краям ----
        PaperCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = kind == MoneyStats.Kind.WEEK, onClick = { kind = MoneyStats.Kind.WEEK }, label = { Text("Неделя") })
                Spacer(Modifier.width(6.dp))
                FilterChip(selected = kind == MoneyStats.Kind.MONTH, onClick = { kind = MoneyStats.Kind.MONTH }, label = { Text("Месяц") })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { period = period.prev() }) { Icon(Icons.Filled.KeyboardArrowLeft, "раньше") }
                Text(
                    periodTitle(period),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { period = period.next() }, enabled = period.to <= System.currentTimeMillis()) {
                    Icon(Icons.Filled.KeyboardArrowRight, "позже")
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    PaperHint("ушло")
                    Text("−" + MoneyFormat.k(totals.spentKop), style = MaterialTheme.typography.headlineSmall, color = spentColor())
                    totals.spentDelta?.let { d ->
                        val up = d > 0
                        PaperHint(
                            (if (up) "▲ " else "▼ ") + "${kotlin.math.abs(Math.round(d * 100))} % к прошл${if (period.kind == MoneyStats.Kind.WEEK) "ой неделе" else "ому месяцу"}",
                            color = if (up) spentColor() else incomeColor(),
                        )
                    }
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    PaperHint("пришло")
                    Text("+" + MoneyFormat.k(totals.incomeKop), style = MaterialTheme.typography.headlineSmall, color = incomeColor())
                    PaperHint("сальдо " + MoneyFormat.k(totals.balanceKop, sign = true) + " " + MoneyFormat.K)
                }
            }
        }

        // ---- ДДС и баланс — сразу под периодом (владелец, 23.09.2026: «кеш и баланс должны быть выше») ----
        CashflowCard(app, entries, java.time.YearMonth.from(period.firstDay.plusDays((period.days - 1).toLong())), ms)
        BalanceCard(app, entries, ms)
        AccountsCard(app, entries, period, ms)

        // ---- Плитки ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ru.zf.pravka.ui.KpiTile("в день", MoneyFormat.k(pace.perDayKop), Modifier.weight(1f), hint = "${MoneyFormat.K} · за ${pace.daysPassed} дн.")
            if (pace.forecastKop != null) {
                ru.zf.pravka.ui.KpiTile("прогноз", MoneyFormat.k(pace.forecastKop), Modifier.weight(1f), hint = "${MoneyFormat.K} · если тратить так же")
            } else {
                ru.zf.pravka.ui.KpiTile("трат", totals.count.toString(), Modifier.weight(1f), hint = "за период")
            }
            val top = biggest.firstOrNull()
            ru.zf.pravka.ui.KpiTile(
                "крупнейшая", top?.let { MoneyFormat.k(-it.rubKop) } ?: "—", Modifier.weight(1f),
                hint = top?.let { MoneyMerchants.canonical(it.what) },
            )
        }

        // ---- Вопросы карточками ----
        QuestionCards(app, questions)
        if (questions.isNotEmpty()) {
            TextButton(
                enabled = busy.isEmpty(),
                onClick = {
                    busy = "Claude думает…"
                    scope.launch {
                        app.moneyEngine.hint().onSuccess { n ->
                            Feedback.toast(context, if (n == 0) "Спрашивать не о чем" else "Claude подсказал по $n получателям")
                        }.onFailure { e -> Feedback.toast(context, "Не вышло: ${e.message}", long = true) }
                        busy = ""
                    }
                },
            ) { Text("Пусть Claude сам разложит очевидное") }
        }

        // ---- Категории: донат по группам и строки, которые раскрываются магазинами ----
        CategoriesCard(cats, totals.spentKop)

        // ---- Траты по дням ----
        PaperCard(label = "по дням · " + MoneyFormat.K) {
            val avg = if (pace.daysPassed > 0) pace.perDayKop / 100f else null
            ru.zf.pravka.ui.StackedColumns(
                columns = daily.mapIndexed { i, kop ->
                    val day = period.firstDay.plusDays(i.toLong())
                    ru.zf.pravka.ui.StackedColumn(
                        label = if (period.kind == MoneyStats.Kind.WEEK) dayLetter(day) else if (day.dayOfMonth % 5 == 1) day.dayOfMonth.toString() else "",
                        parts = listOf(ru.zf.pravka.ui.ChartSlice("", kop / 100f, spentColor())),
                        faded = day == today,
                    )
                },
                target = avg,
                valueText = if (period.kind == MoneyStats.Kind.WEEK) { v -> MoneyFormat.k((v * 100).toLong()) } else null,
                highlight = daily.indices.firstOrNull { period.firstDay.plusDays(it.toLong()) == today } ?: -1,
            )
            PaperHint("пунктир — средний день")
        }

        // ---- Полгода: ушло и пришло ----
        PaperCard(label = "полгода · " + MoneyFormat.K) {
            MonthPairs(
                labels = trend.map { monthShort(it.month) },
                spent = trend.map { it.spentKop },
                income = trend.map { it.incomeKop },
                valueText = { MoneyFormat.k(it) },
                highlight = trend.lastIndex,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendDot(spentColor()); PaperHint(" ушло   ")
                LegendDot(incomeColor()); PaperHint(" пришло")
            }
            val avgSpent = trend.dropLast(1).filter { it.spentKop > 0 }.map { it.spentKop }.average().takeIf { !it.isNaN() }
            if (avgSpent != null) PaperHint("средний месяц (без текущего): " + MoneyFormat.k(avgSpent.toLong()) + " " + MoneyFormat.K)
        }


        // ---- Регулярные платежи ----
        if (recurring.isNotEmpty()) {
            PaperCard(label = "регулярные · ${MoneyFormat.k(recurring.sumOf { it.avgKop })} ${MoneyFormat.K} в месяц") {
                for (r in recurring.take(15)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        LegendDot(moneyCategoryColor(r.category))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            PaperHint(MoneyCategories.title(r.category) + " · ${r.months} мес. из 6")
                        }
                        Text(MoneyFormat.k(r.avgKop), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // ---- Паттерны от Claude ----
        PatternsCard(app, state.insight, state.insightTs)

        // ---- Ждут «ОК» ----
        if (drafts.isNotEmpty()) {
            PaperCard(label = "ждут «ОК» · " + drafts.size) {
                for ((takeId, group) in drafts.groupBy { it.takeId }) {
                    for (e in group) EntryRow(e) { editing = e }
                    Row {
                        if (PravkaAccessibilityService.instance != null) {
                            TextButton(onClick = { PravkaAccessibilityService.instance?.showMoneyPlate(takeId) }) { Text("Показать плашку") }
                        }
                        TextButton(onClick = { scope.launch { app.moneyEngine.confirm(takeId, group.map { it.id }) } }) {
                            Text("ОК · " + group.size)
                        }
                    }
                }
            }
        }

        // ---- Выписки ----
        PaperCard(label = "выписки") {
            PaperHint(
                "Тиньков и Альфа — CSV, МКБ — .xlsx, можно несколько файлов разом; «Плати по миру» — текстом чата (скопируй и вставь). " +
                    "Можно и через «Поделиться» → «Деньги: выписка». Повторы не удваиваются."
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = busy.isEmpty(), onClick = { picker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream")) }) {
                    Text("Файлы выписок")
                }
                OutlinedButton(enabled = busy.isEmpty(), onClick = {
                    val text = clipboard.getText()?.text.orEmpty()
                    if (text.isBlank()) { Feedback.toast(context, "В буфере пусто"); return@OutlinedButton }
                    busy = "загружаю…"
                    scope.launch {
                        app.moneyEngine.import(text)
                            .onSuccess { o -> Feedback.toast(context, "${o.kind}: ${o.rows}, новых ${o.added}" + if (o.note.isNotBlank()) "\n${o.note}" else "", long = true) }
                            .onFailure { e -> Feedback.toast(context, e.message ?: "не вышло", long = true) }
                        busy = ""
                    }
                }) { Text("Вставить из буфера") }
            }
            if (busy.isNotEmpty()) PaperHint(busy)
            val lastImports = state.imports.takeLast(4).reversed()
            for (i in lastImports) {
                PaperHint("${stamp(i.ts)} · ${i.kind}: ${i.rows} строк, новых ${i.added}" + if (i.note.isNotBlank()) " · ${i.note}" else "")
            }
        }

        // ---- Журнал периода ----
        PaperCard(label = "журнал · " + journal.size) {
            if (journal.isEmpty()) PaperHint("За этот период записей нет.")
            for (e in journal.take(150)) EntryRow(e) { editing = e }
            if (journal.size > 150) PaperHint("и ещё ${journal.size - 150} — в книге")
        }

        // ---- Справочник ----
        PayeesCard(app)

        TextButton(onClick = {
            scope.launch {
                runCatching { app.moneyExport.shareIntent() }
                    .onSuccess { intent ->
                        runCatching { context.startActivity(android.content.Intent.createChooser(intent, "Деньги (.xlsx)")) }
                    }
                    .onFailure { e -> Feedback.toast(context, "Не собралась: ${e.javaClass.simpleName}: ${e.message}", long = true) }
            }
        }) { Text("Выгрузить книгу «Деньги» (.xlsx)") }
    }

    editing?.let { e ->
        EntryDialog(app, e, onDismiss = { editing = null })
    }
}

/** Одна строка журнала: что, когда, откуда, сумма; тап — правка. */
@Composable
private fun EntryRow(e: MoneyEntry, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(e.what, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val bits = mutableListOf(stamp(e.ts, e.timeKnown), e.source.title)
            bits.add(if (e.category.isBlank()) "без категории" else MoneyCategories.title(e.category))
            if (e.who.isNotBlank()) bits.add(MoneyCategories.whoTitle(e.who))
            if (e.matchId.isNotBlank()) bits.add("✓ сверено")
            if (e.account == MoneyEntry.CASH) bits.add("наличные")
            if (e.rubBasis == MoneyEntry.RubBasis.CBR_PRELIM) bits.add("курс предварительный")
            PaperHint(bits.joinToString(" · "))
            if (e.doubt.isNotBlank()) PaperHint("⚠ " + e.doubt, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                MoneyFormat.rub(e.rubKop, sign = true),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (e.currency != "RUB") PaperHint(MoneyFormat.orig(e.origMinor, e.currency))
        }
    }
}

/** Выбор категории: список по группам, как владелец читает бюджет. */
@Composable
internal fun CategoryPicker(current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(if (current.isBlank()) "Категория…" else MoneyCategories.title(current))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            var lastGroup = ""
            for (c in MoneyCategories.ALL) {
                if (c.group != lastGroup) {
                    lastGroup = c.group
                    DropdownMenuItem(
                        text = { Text(c.group.uppercase(Locale.forLanguageTag("ru")), style = MaterialTheme.typography.labelSmall) },
                        onClick = {},
                        enabled = false,
                    )
                }
                DropdownMenuItem(text = { Text(c.title) }, onClick = { open = false; onPick(c.key) })
            }
        }
    }
}

/** «Для кого» — пометка, не категория. Повторный тап снимает. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WhoChips(current: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((key, title) in MoneyCategories.WHO) {
            FilterChip(selected = current == key, onClick = { onPick(if (current == key) "" else key) }, label = { Text(title) })
        }
    }
}

/** Правка одной записи: сумма, что это, категория, для кого, вычеркнуть. */
@Composable
private fun EntryDialog(app: PravkaApp, e: MoneyEntry, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var amount by remember { mutableStateOf((kotlin.math.abs(e.rubKop) / 100.0).let { if (it % 1.0 == 0.0) it.toLong().toString() else "%.2f".format(Locale.US, it) }) }
    var what by remember { mutableStateOf(e.what) }
    var category by remember { mutableStateOf(e.category) }
    var who by remember { mutableStateOf(e.who) }
    val bank = e.fromBank
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (bank) e.source.title else "Трата") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (bank) {
                    // Сумма и название из банка — правда; их не правим, только раскладываем.
                    Text(e.what, style = MaterialTheme.typography.bodyMedium)
                    Text(MoneyFormat.rub(e.rubKop, sign = true), style = MaterialTheme.typography.titleMedium)
                    if (e.note.isNotBlank()) PaperHint("«${e.note}»")
                    if (e.account.isNotBlank()) PaperHint(e.account)
                } else {
                    OutlinedTextField(
                        value = amount, onValueChange = { amount = it },
                        label = { Text("Сумма, ₽") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(value = what, onValueChange = { what = it }, label = { Text("Что это") }, singleLine = true)
                    if (e.note.isNotBlank()) PaperHint(e.note)
                    if (e.doubt.isNotBlank()) PaperHint("⚠ " + e.doubt, color = MaterialTheme.colorScheme.error)
                }
                CategoryPicker(category) { category = it }
                WhoChips(who) { who = it }
                PaperHint(stamp(e.ts, e.timeKnown) + " · " + MoneyCategories.whoTitle(e.owner))
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    val kop = if (bank) null else MoneyFormat.parseKop(amount.replace(" ", ""))
                    app.moneyEngine.edit(
                        e.id,
                        rubKop = kop,
                        what = if (bank) null else what,
                        category = category.takeIf { it != e.category },
                        who = who.takeIf { it != e.who },
                    )
                    onDismiss()
                }
            }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { scope.launch { app.moneyEngine.drop(listOf(e.id)); onDismiss() } }) { Text("Вычеркнуть") }
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
        },
    )
}

/**
 * Справочник получателей текстом. Одна строка — одно правило:
 * «Иван П. = Помощь по дому · дети», «− Пётр С. = Лето и лагеря · Серёжа»,
 * «Марианна: Сидор С. = Между нами». Свои правила — поверх заводских.
 */
@Composable
private fun PayeesCard(app: PravkaApp) {
    val state by app.moneyStore.stateFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var draft by remember(state.rules) { mutableStateOf(app.moneyEngine.rulesText()) }
    var errors by remember { mutableStateOf(emptyList<String>()) }
    PaperCard(
        label = "справочник получателей · " + state.rules.size + " свои + " + app.moneyEngine.factoryCount() + " с завода",
        trailing = { TextButton(onClick = { open = !open }) { Text(if (open) "Свернуть" else "Открыть") } },
    ) {
        PaperHint("Кто есть кто в выписках. Здесь — твои правила: они растут от ответов на вопросы и перебивают заводские (assets/money_payees.txt в репозитории).")
        if (!open) return@PaperCard
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 6,
            label = { Text("шаблон = категория · для кого") },
        )
        PaperHint("«−» в начале — только списания, «+» — только поступления, «Марианна:» — только её счета, «|» — или.")
        for (err in errors) PaperHint(err, color = MaterialTheme.colorScheme.error)
        Row {
            Button(onClick = {
                scope.launch {
                    errors = app.moneyEngine.setRulesText(draft)
                    Feedback.toast(context, if (errors.isEmpty()) "Справочник сохранён, выписки переразложены" else "Сохранил, но есть строки с ошибкой")
                }
            }) { Text("Сохранить") }
        }
    }
}

/** Настройки Денег — группа за шестерёнкой в шапке вкладки. */
@Composable
internal fun MoneySettings(app: PravkaApp) {
    val on by app.settings.mEnabledFlow.collectAsState(initial = true)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Кнопка «₽» на экране", style = MaterialTheme.typography.bodyMedium)
                PaperHint("Четвёртая на диске: наговорил трату — плашка с суммой и «ОК».")
            }
            Switch(checked = on, onCheckedChange = { v -> app.appScope.launch { app.settings.setMEnabled(v) } })
        }
        Spacer(Modifier.height(12.dp))
        PushSettings(app)
        Spacer(Modifier.height(8.dp))
        PaperHint("Модели — в «Моделях», дороги «Деньги»: траты голосом и подсказки сверки. Промпты — во вкладке «Промпты».")
    }
}

/**
 * Пуши банка: тумблер, есть ли «Доступ к уведомлениям» и что поймано.
 * Отозванный доступ — не «пушей просто нет», а строка словами и кнопка
 * (железное правило 6: молчаливая механика читается как поломка).
 */
@Composable
private fun PushSettings(app: PravkaApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val on by app.settings.mPushFlow.collectAsState(initial = true)
    val state by app.moneyStore.stateFlow.collectAsState()
    var granted by remember { mutableStateOf(pushAccess(context)) }
    // Доступ выдают в системных настройках и возвращаются «назад» — проверяем
    // по кругу, пока группа открыта: это чтение одной строки настроек.
    LaunchedEffect(Unit) {
        while (true) {
            granted = pushAccess(context)
            kotlinx.coroutines.delay(1500)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Ловить пуши банка", style = MaterialTheme.typography.bodyMedium)
            PaperHint("Т-Банк и чат «Плати по миру» в Телеграме: трата ложится в журнал сразу, выписка потом её заменяет.")
        }
        Switch(checked = on, onCheckedChange = { v -> scope.launch { app.settings.setMPush(v) } })
    }
    if (!on) return
    Spacer(Modifier.height(6.dp))
    if (!granted) {
        PaperHint(
            "Нет доступа к уведомлениям — пуши не ловятся. Если Android не даёт включить: " +
                "«О приложении» → ⋮ → «Разрешить ограниченные настройки», как было со службой доступности.",
            color = MaterialTheme.colorScheme.error,
        )
        OutlinedButton(onClick = {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }) { Text("Открыть доступ к уведомлениям") }
        return
    }
    val pushes = state.pushes
    val money = pushes.count { it.result == ru.zf.pravka.data.MoneyStore.MONEY }
    val replaced = state.entries.count { it.source == MoneyEntry.Source.PUSH && it.replacedBy.isNotEmpty() }
    PaperHint(
        if (pushes.isEmpty()) "Доступ есть. Пока ничего не поймано — первый пуш Т-Банка появится здесь."
        else "Поймано ${pushes.size}, из них операций $money; выпиской уже заменено $replaced."
    )
    for (p in pushes.takeLast(6).reversed()) {
        Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
            Text(
                stamp(p.ts) + "  " + p.title.ifBlank { p.pkg.substringAfterLast('.') },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            PaperHint(p.result)
        }
    }
}

private fun pushAccess(context: android.content.Context): Boolean =
    androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun periodTitle(p: MoneyStats.Period): String {
    val ru = Locale.forLanguageTag("ru")
    return if (p.kind == MoneyStats.Kind.MONTH) {
        java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", ru).format(p.firstDay).replaceFirstChar { it.uppercase() }
    } else {
        val f = java.time.format.DateTimeFormatter.ofPattern("d MMM", ru)
        f.format(p.firstDay) + " — " + f.format(p.firstDay.plusDays(6))
    }
}

private fun monthShort(m: java.time.YearMonth): String =
    java.time.format.DateTimeFormatter.ofPattern("LLL", Locale.forLanguageTag("ru")).format(m.atDay(1)).trimEnd('.')

private fun dayLetter(d: java.time.LocalDate): String = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")[d.dayOfWeek.value - 1]

private fun stamp(ts: Long, withTime: Boolean = true): String =
    SimpleDateFormat(if (withTime) "d MMM, HH:mm" else "d MMM", Locale.forLanguageTag("ru")).format(Date(ts))
