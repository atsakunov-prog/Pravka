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
import ru.zf.pravka.core.MoneyStats
import ru.zf.pravka.trigger.PravkaAccessibilityService
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
    val withZf by app.settings.mWithZfFlow.collectAsState(initial = false)
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
    val totals = remember(state, period, withZf) { MoneyStats.totals(entries, period, withZf) }
    val cats = remember(state, period, withZf) { MoneyStats.categories(entries, period, withZf) }
    val daily = remember(state, period, withZf) { MoneyStats.daily(entries, period, withZf) }
    val pace = remember(state, period, withZf) { MoneyStats.pace(entries, period, withZf, today) }
    val trend = remember(state, withZf) { MoneyStats.trend(entries, today, 6, withZf) }
    val recurring = remember(state, withZf) { MoneyStats.recurring(entries, today, withZf) }
    val biggest = remember(state, period, withZf) { MoneyStats.biggest(entries, period, withZf) }
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
        AskCard(app)

        // ---- Период: неделя или месяц, «ушло» и «пришло» по краям ----
        PaperCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = kind == MoneyStats.Kind.WEEK, onClick = { kind = MoneyStats.Kind.WEEK }, label = { Text("Неделя") })
                Spacer(Modifier.width(6.dp))
                FilterChip(selected = kind == MoneyStats.Kind.MONTH, onClick = { kind = MoneyStats.Kind.MONTH }, label = { Text("Месяц") })
                Spacer(Modifier.weight(1f))
                FilterChip(
                    selected = withZf,
                    onClick = { scope.launch { app.settings.setMWithZf(!withZf) } },
                    label = { Text("+ ЗФ") },
                )
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
                    Text("−" + MoneyFormat.rub(totals.spentKop), style = MaterialTheme.typography.headlineSmall, color = spentColor())
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
                    Text("+" + MoneyFormat.rub(totals.incomeKop), style = MaterialTheme.typography.headlineSmall, color = incomeColor())
                    PaperHint("сальдо " + MoneyFormat.rub(totals.balanceKop, sign = true))
                }
            }
        }

        // ---- Плитки ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ru.zf.pravka.ui.KpiTile("в день", MoneyFormat.rub(pace.perDayKop), Modifier.weight(1f), hint = "за ${pace.daysPassed} дн.")
            if (pace.forecastKop != null) {
                ru.zf.pravka.ui.KpiTile("прогноз", MoneyFormat.short(pace.forecastKop) + " ₽", Modifier.weight(1f), hint = "если тратить так же")
            } else {
                ru.zf.pravka.ui.KpiTile("трат", totals.count.toString(), Modifier.weight(1f), hint = "за период")
            }
            val top = biggest.firstOrNull()
            ru.zf.pravka.ui.KpiTile(
                "крупнейшая", top?.let { MoneyFormat.short(-it.rubKop) + " ₽" } ?: "—", Modifier.weight(1f),
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
        PaperCard(label = "по дням") {
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
                valueText = if (period.kind == MoneyStats.Kind.WEEK) { v -> MoneyFormat.short((v * 100).toLong()) } else null,
                highlight = daily.indices.firstOrNull { period.firstDay.plusDays(it.toLong()) == today } ?: -1,
            )
            PaperHint("пунктир — средний день")
        }

        // ---- Полгода: ушло и пришло ----
        PaperCard(label = "полгода") {
            MonthPairs(
                labels = trend.map { monthShort(it.month) },
                spent = trend.map { it.spentKop },
                income = trend.map { it.incomeKop },
                valueText = { MoneyFormat.short(it) },
                highlight = trend.lastIndex,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendDot(spentColor()); PaperHint(" ушло   ")
                LegendDot(incomeColor()); PaperHint(" пришло")
            }
            val avgSpent = trend.dropLast(1).filter { it.spentKop > 0 }.map { it.spentKop }.average().takeIf { !it.isNaN() }
            if (avgSpent != null) PaperHint("средний месяц (без текущего): " + MoneyFormat.rub(avgSpent.toLong()))
        }

        // ---- Регулярные платежи ----
        if (recurring.isNotEmpty()) {
            PaperCard(label = "регулярные · ${MoneyFormat.short(recurring.sumOf { it.avgKop })} ₽ в месяц") {
                for (r in recurring.take(15)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        LegendDot(moneyCategoryColor(r.category))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            PaperHint(MoneyCategories.title(r.category) + " · ${r.months} мес. из 6")
                        }
                        Text(MoneyFormat.rub(r.avgKop), style = MaterialTheme.typography.bodyMedium)
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
        Spacer(Modifier.height(8.dp))
        PaperHint("Модели — в «Моделях», дороги «Деньги»: траты голосом и подсказки сверки. Промпты — во вкладке «Промпты».")
    }
}

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
