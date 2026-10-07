package ru.zf.pravka

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import ru.zf.pravka.ui.hatch
import ru.zf.pravka.ui.glass
import ru.zf.pravka.ui.bottomFade
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import ru.zf.pravka.core.MealItem
import ru.zf.pravka.core.Micronutrients
import ru.zf.pravka.data.FoodStore
import ru.zf.pravka.data.RationBook
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.DayNav
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.IconAction
import ru.zf.pravka.ui.PaperAlert
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.PaperToggle
import ru.zf.pravka.ui.ScreenPad
import ru.zf.pravka.ui.SheetAction
import ru.zf.pravka.ui.SummaryLine
import ru.zf.pravka.ui.scrollFade
import ru.zf.pravka.trigger.onFoodTap

// Вкладка «Еда»: дневник приёмов с КБЖУ.
//
// Ввод четырьмя дорогами, и у каждой своя правда:
//   голос      - кнопка «Т», на ходу, самая частая (движок Правки);
//   текст      - поле здесь же, когда говорить неудобно;
//   снимок     - тарелку и этикетку модель читает точнее, чем описание;
//   штрихкод   - на упаковке КБЖУ НАПИСАН, и база его знает точно.
//
// Правило одно на все четыре: разобранное сначала показывается, и только «✓»
// делает его частью дня. До «✓» приём не идёт ни в сумму, ни в ленту, ни в
// intervals.icu - но на диске лежит с первой секунды.

private val mealTimeFormat = SimpleDateFormat("HH:mm", Locale.US)
private val dayTitleFormat = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))
private val isoFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)


@Composable
internal fun FoodTab(
    app: PravkaApp,
    // «Сфоткай тарелку»/«штрихкод» с кнопки еды: вкладка открывается и сразу
    // запускает камеру или сканер, без лишнего тапа.
    autoAction: String? = null,
    onAutoConsumed: () -> Unit = {},
    /**
     * Шапка режима (Правка 4.0): её рисует вкладка — вторым тоном в ней день
     * дневника («Еда пн, 5 октября ⌄», `screens/10`), а день живёт здесь.
     */
    header: @Composable (day: String, onDay: () -> Unit) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val store = app.foodStore
    val meals by store.mealsFlow.collectAsState()
    val targetKcal by app.settings.foodKcalFlow.collectAsState(initial = 0)
    val targetProtein by app.settings.foodProteinFlow.collectAsState(initial = 0)
    val targetFat by app.settings.foodFatFlow.collectAsState(initial = 0)
    val targetCarbs by app.settings.foodCarbsFlow.collectAsState(initial = 0)

    var dayOffset by remember { mutableStateOf(0) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val ownerName = app.profileStore.flow.collectAsState().value?.name
    // Еда голосом разбирается в службе — её ожидание видно по общему потоку запросов.
    val talking = ru.zf.pravka.ui.rememberRouteBusy(app.liveWork, "food")
    var editing by remember { mutableStateOf<Long?>(null) }
    // Куда камера положит кадр: файл нужен ДО съёмки, чтобы отдать в интент URI.
    var pendingPhoto by remember { mutableStateOf<File?>(null) }
    var autoFired by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { store.load() }

    val dayStart = remember(dayOffset) { dayStartBackFood(dayOffset) }
    val date = remember(dayStart) { isoFormat.format(Date(dayStart)) }
    val dayMeals = remember(meals, date) { store.mealsOn(date) }
    val total = remember(meals, date) { store.dayTotal(date) }
    val pending = remember(meals) { store.pending() }

    /** Один путь на все четыре дороги: разобрал → показал → «✓» подтверждает. */
    val parseText: (String) -> Unit = parse@{ text ->
        if (busy || text.isBlank()) return@parse
        busy = true
        draft = ""
        // App-scope, не scope композиции: уйти со вкладки, пока Сонет считает,
        // не должно стоить разбора.
        app.appScope.launch {
            val result = runCatching { app.foodEngine.parse(text, source = "text") }
                .getOrElse { Result.failure(it) }
            busy = false
            result.onFailure { e ->
                Feedback.toast(app, e.message ?: "Не разобрал", long = true)
            }
        }
    }

    val parsePhoto: (File, String) -> Unit = { file, caption ->
        busy = true
        app.appScope.launch {
            val result = runCatching {
                app.foodEngine.parse(caption, photo = file, source = "photo")
            }.getOrElse { Result.failure(it) }
            busy = false
            // Кадр из кэша убираем всегда: движок уже положил свою копию рядом
            // с дневником, а этот файл был только транспортом.
            runCatching { file.delete() }
            result.onFailure { e ->
                Feedback.toast(app, e.message ?: "Не разобрал снимок", long = true)
            }
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingPhoto
        pendingPhoto = null
        if (ok && file != null) {
            val caption = draft.trim()
            draft = ""
            parsePhoto(file, caption)
        } else {
            runCatching { file?.delete() }
        }
    }

    LaunchedEffect(autoAction) {
        // Просьба погашена — следующая (второй карандаш с пилюли при открытой
        // вкладке) должна сработать снова: иначе вкладка слушалась бы один раз.
        val action = autoAction ?: run { autoFired = false; return@LaunchedEffect }
        if (autoFired) return@LaunchedEffect
        autoFired = true
        onAutoConsumed()
        // Карандаш у позиции в итоге еды (пилюля, 26.09.2026): открыть этот
        // приём в редакторе — «где уже можно будет и редактировать, и всё делать».
        if (action.startsWith("edit:")) {
            editing = action.removePrefix("edit:").toLongOrNull()
            return@LaunchedEffect
        }
        when (action) {
            "photo" -> {
                val file = File(context.cacheDir, "eda-shot.jpg")
                pendingPhoto = file
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, BuildConfig.APPLICATION_ID + ".files", file
                )
                camera.launch(uri)
            }
            "barcode" -> {
                ru.zf.pravka.ui.scanBarcode(
                    context = context,
                    onFail = { message -> Feedback.toast(app, message, long = true) },
                ) { code ->
                    app.appScope.launch {
                        val result = runCatching { app.foodEngine.parseBarcode(code) }
                            .getOrElse { Result.failure(it) }
                        result.onFailure { e ->
                            Feedback.toast(
                                app,
                                (e.message ?: "Штрихкод не нашёлся") + " — сними этикетку камерой",
                                long = true,
                            )
                        }
                    }
                }
            }
        }
    }

    val gallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val copied = runCatching { copyToCache(context, uri) }.getOrNull()
        if (copied == null) {
            Feedback.toast(app, "Снимок не прочитался")
        } else {
            val caption = draft.trim()
            draft = ""
            parsePhoto(copied, caption)
        }
    }

    val listState = rememberLazyListState()
    var daySheet by remember { mutableStateOf(false) }
    val dayDate = remember(dayStart) { java.time.Instant.ofEpochMilli(dayStart).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
    // Ждёт «✓» за этот день — штриховкой на полосе и строкой «с ужином перебор 65 ккал».
    val pendingDay = remember(pending, date) { pending.filter { isoFormat.format(Date(it.ts)) == date } }
    val takePhoto: () -> Unit = {
        val file = File(context.cacheDir, "eda-shot.jpg")
        pendingPhoto = file
        val uri = androidx.core.content.FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".files", file)
        camera.launch(uri)
    }
    val pickImage: () -> Unit = {
        gallery.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val scanCode: () -> Unit = {
        ru.zf.pravka.ui.scanBarcode(
            context = context,
            onFail = { message -> Feedback.toast(app, message, long = true) },
        ) { code ->
            busy = true
            app.appScope.launch {
                val result = runCatching { app.foodEngine.parseBarcode(code) }.getOrElse { Result.failure(it) }
                busy = false
                result.onFailure { e ->
                    Feedback.toast(app, (e.message ?: "Штрихкод не нашёлся") + " — сними этикетку камерой", long = true)
                }
            }
        }
    }
    val confirmMeal: (Long) -> Unit = { id ->
        scope.launch {
            val outcome = app.foodEngine.confirm(id)
            if (outcome.icuError.isNotBlank()) {
                Feedback.toast(app, "Записал. В intervals.icu не уехало: ${outcome.icuError}", long = true)
            }
        }
    }
    val reparseMeal: (Long) -> Unit = { id ->
        busy = true
        app.appScope.launch {
            val result = runCatching { app.foodEngine.reparse(id) }.getOrElse { Result.failure(it) }
            busy = false
            result.onFailure { e -> Feedback.toast(app, e.message ?: "Не вышло", long = true) }
        }
    }
    Column(Modifier.fillMaxSize()) {
    header(ru.zf.pravka.core.Fmt.dayList(dayDate)) { daySheet = true }
    Box(Modifier.weight(1f)) {
    LazyColumn(
        Modifier.fillMaxSize().bottomFade().scrollFade(listState),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
    ) {
        // ---- Итог дня: сколько осталось крупно, полоса с ожидающим, три макро (`screens/10`) ----
        item(key = "total") {
            DayTotalCard(
                total = total,
                pending = pendingDay,
                targetKcal = targetKcal,
                macros = listOf(
                    Triple("Белки", total.protein, targetProtein),
                    Triple("Жиры", total.fat, targetFat),
                    Triple("Углеводы", total.carbs, targetCarbs),
                ),
                today = dayOffset == 0,
            )
        }

        // ---- Разобранное, но не записанное ----
        if (pending.isNotEmpty()) {
            item(key = "pending:h") {
                ru.zf.pravka.ui.SectionHeader("ждёт подтверждения", trailing = pending.first().let { mealTimeFormat.format(Date(it.ts)) })
            }
            items(pending.size, key = { i -> "p" + pending[i].id }) { i ->
                val meal = pending[i]
                MealCard(
                    app = app,
                    meal = meal,
                    pendingState = true,
                    onEdit = { editing = meal.id },
                    onConfirm = { confirmMeal(meal.id) },
                    onDelete = { scope.launch { app.foodEngine.delete(meal.id) } },
                    onReparse = { reparseMeal(meal.id) },
                )
            }
        }

        // ---- Приёмы дня — одной плашкой строками ----
        item(key = "meals:h") {
            ru.zf.pravka.ui.SectionHeader(
                if (dayMeals.isEmpty()) "приёмов нет" else "приёмы · ${dayMeals.size}",
                trailing = if (dayMeals.isEmpty()) null else ru.zf.pravka.core.Fmt.num(dayMeals.sumOf { it.kcal }) + " ккал",
            )
        }
        item(key = "meals") {
            if (dayMeals.isEmpty()) {
                ru.zf.pravka.ui.EmptyState("За этот день ничего не записано — скажи или сними тарелку внизу", icon = Glyphs.Food)
            } else {
                val mode = ru.zf.pravka.ui.LocalMode.current
                Column(
                    Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(22.dp), mode.glass)
                        .padding(horizontal = 18.dp, vertical = 6.dp),
                ) {
                    dayMeals.forEachIndexed { k, meal ->
                        if (k > 0) ru.zf.pravka.ui.Hairline()
                        MealRow(
                            app = app,
                            meal = meal,
                            onEdit = { editing = meal.id },
                            onDelete = { scope.launch { app.foodEngine.delete(meal.id) } },
                            onUnconfirm = { scope.launch { app.foodEngine.unconfirm(meal.id) } },
                        )
                    }
                }
            }
        }

        // ---- Витамины и элементы ----
        item(key = "micro") { MicroCard(total) }

        // ---- Мой рацион: то, что повторяется каждый день ----
        item(key = "ration") { RationSection(app, dayStart) }

        // ---- Неделя столбиками ----
        item(key = "week") {
            val week = remember(meals) { store.recentDays(7) }
            if (week.isNotEmpty()) {
                WeekKcalCard(
                    week = week.reversed(),
                    targetKcal = targetKcal,
                    pendingToday = pending.filter { isoFormat.format(Date(it.ts)) == week.first().date }.sumOf { it.kcal },
                    selected = date,
                    onDay = { d -> dayOffset = daysBetween(d) },
                )
            }
        }

        // Настройки режима — за шестерёнкой в шапке вкладки.
    }
    // ---- Что съел: строка «сказать» внизу, четыре дороги в ней (DESIGN §11.4) ----
    // Снимок, галерея и штрихкод — значками справа перед микрофоном (`screens/10`);
    // набранное поле — подпись к снимку (масло в салате, сахар в кофе).
    ru.zf.pravka.ui.SayBar(
        value = draft,
        onValueChange = { draft = it },
        placeholder = ru.zf.pravka.core.PillHint.say(ownerName, "что съел?"),
        onSend = { parseText(draft.trim()) },
        onMic = {
            val service = ru.zf.pravka.trigger.PravkaAccessibilityService.instance
            if (service == null) Feedback.toast(app, app.getString(R.string.toast_no_service))
            else service.onFoodTap()
        },
        enabled = !busy,
        sendEnabled = !busy && draft.isNotBlank(),
        busy = busy || talking,
        maxLines = 4,
        trailing = {
            if (draft.isBlank()) {
                ru.zf.pravka.ui.SayIcon(Glyphs.Camera, "снять", takePhoto, enabled = !busy)
                ru.zf.pravka.ui.SayIcon(Glyphs.Image, "галерея", pickImage, enabled = !busy)
                ru.zf.pravka.ui.SayIcon(Glyphs.Barcode, "штрихкод", scanCode, enabled = !busy)
            }
        },
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 18.dp),
    )
    }
    }
    if (daySheet) {
        ru.zf.pravka.ui.PaperSheet(onDismiss = { daySheet = false }, title = "День дневника") {
            val dayTitle = dayTitleFormat.format(Date(dayStart))
            DayNav(
                title = when (dayOffset) {
                    0 -> "Сегодня"
                    1 -> "Вчера"
                    else -> dayTitle.replaceFirstChar { it.uppercase() }
                },
                subtitle = if (dayOffset in 0..1) dayTitle else null,
                onPrev = { dayOffset += 1 },
                onNext = if (dayOffset > 0) ({ dayOffset -= 1 }) else null,
            )
            if (dayOffset != 0) {
                Spacer(Modifier.height(10.dp))
                ru.zf.pravka.ui.GhostKey("К сегодняшнему", { dayOffset = 0; daySheet = false })
            }
        }
    }

    val editMeal = editing?.let { id -> meals.firstOrNull { it.id == id } }
    if (editMeal != null) {
        MealEditDialog(
            app = app,
            meal = editMeal,
            onClose = { editing = null },
        )
    }
}


/**
 * Витамины и элементы за день (Правка 4.0, `screens/10`): строка на вещество —
 * имя, полоса с риской нормы и процент. Светофорных цветов нет: «мало» —
 * жирным словом у имени, перебор — тёплым процентом. Сначала то, чего мало
 * (это единственное, что можно поправить сегодня), остальное — строкой «ещё
 * N — в норме ›». Тап по строке — зачем вещество и где его брать.
 */
@Composable
private fun MicroCard(total: FoodStore.DayTotal) {
    var open by remember { mutableStateOf(false) }
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    val totals = total.micro
    val all = Micronutrients.ALL
    // По доле нормы: меньше всего — сверху; у натрия норма — потолок, он в конце.
    val sorted = remember(totals) { all.sortedWith(compareBy({ it.limit }, { it.share(totals[it.id] ?: 0.0) })) }
    val low = remember(totals) { Micronutrients.lacking(totals) }
    val head = if (open) sorted else sorted.filter { n ->
        val lvl = Micronutrients.level(n, totals[n.id] ?: 0.0)
        lvl == Micronutrients.Level.LOW || lvl == Micronutrients.Level.MID || lvl == Micronutrients.Level.OVER
    }.take(8)
    Column(Modifier.fillMaxWidth()) {
        ru.zf.pravka.ui.SectionHeader(
            "витамины и элементы · ${all.size}",
            info = "Витамины и элементы" to ("Сначала то, чего мало; все ${all.size} — строкой внизу. Вещества приезжают " +
                "с разбором еды и с рационом; таблетки скажи отдельно — «выпил витамин D и магний». Риска на " +
                "полосе — суточная норма мужчины 43 лет. Цифры считает модель по составу еды и точно — по " +
                "рациону и штрихкоду: это порядок величины, а не анализ крови. У натрия норма — потолок, а не цель."),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(22.dp), mode.glass)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            if (totals.isEmpty()) {
                Text("За этот день веществ не посчитано.", style = ty.body, color = mode.meta, modifier = Modifier.padding(vertical = 6.dp))
            }
            if (total.pills.isNotBlank()) {
                Text("Из банки: " + total.pills, style = ty.meta, color = mode.meta, modifier = Modifier.padding(vertical = 4.dp))
            }
            if (totals.isNotEmpty()) {
                for (n in head) MicroRow(n, totals[n.id] ?: 0.0, total.microPills[n.id] ?: 0.0)
            }
            val rest = all.size - head.size
            if (rest > 0 || open) {
                ru.zf.pravka.ui.Hairline(Modifier.padding(top = 6.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (open) "свернуть" else if (low.isEmpty() && totals.isNotEmpty()) "все ${all.size} — в норме" else "ещё $rest — в норме",
                        style = ty.bodyL,
                        color = mode.label,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (open) Glyphs.ChevronDown else Glyphs.Forward,
                        contentDescription = null,
                        tint = mode.label,
                        modifier = Modifier.size(20.dp).rotate(if (open) 180f else 0f),
                    )
                }
            }
        }
    }
}

/** Одно вещество строкой: имя («мало» жирным), полоса с риской нормы, процент; тап — зачем. */
@Composable
private fun MicroRow(
    nutrient: Micronutrients.Nutrient,
    value: Double,
    fromPills: Double,
) {
    var why by remember(nutrient.id) { mutableStateOf(false) }
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    val level = Micronutrients.level(nutrient, value)
    val low = level == Micronutrients.Level.LOW
    val over = level == Micronutrients.Level.OVER
    Column(Modifier.fillMaxWidth().clickable { why = !why }.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = if (low) FontWeight.SemiBold else FontWeight.Normal))
                    append(nutrient.name)
                    pop()
                    if (low) {
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = ty.caption.fontSize))
                        append(" мало")
                        pop()
                    }
                },
                style = ty.body,
                color = ru.zf.pravka.ui.Ink.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(132.dp),
            )
            androidx.compose.foundation.Canvas(Modifier.weight(1f).height(8.dp)) {
                val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2, size.height / 2)
                drawRoundRect(mode.tint.copy(alpha = 0.14f), cornerRadius = r)
                val scale = nutrient.scale()
                val w = size.width * (value / scale).toFloat().coerceIn(0f, 1f)
                if (w > 0f) drawRoundRect(if (over) ru.zf.pravka.ui.Ink.Warn else mode.ramp[1], size = androidx.compose.ui.geometry.Size(w, size.height), cornerRadius = r)
                val x = size.width * (nutrient.norm / scale).toFloat()
                drawLine(ru.zf.pravka.ui.Ink.Cream.copy(alpha = 0.7f), androidx.compose.ui.geometry.Offset(x, -3f), androidx.compose.ui.geometry.Offset(x, size.height + 3f), strokeWidth = 2f)
            }
            Text(
                "${(nutrient.share(value) * 100).toInt()} %",
                style = ty.valueS.copy(fontSize = ty.body.fontSize),
                color = if (over) ru.zf.pravka.ui.Ink.Warn else ru.zf.pravka.ui.Ink.TextStrong,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.width(64.dp),
            )
        }
        if (why) {
            Text(
                Micronutrients.amount(value) + " / " + Micronutrients.amount(nutrient.norm) + " " + nutrient.unit +
                    (if (fromPills > 0) " · из банки " + Micronutrients.amount(fromPills) + " " + nutrient.unit else "") +
                    " · " + nutrient.why + ". " + nutrient.source,
                style = ty.meta,
                color = mode.meta,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** «Ужин: паста с курицей, салат» — вид приёма и что в нём. */
private fun mealTitle(meal: FoodStore.Meal): String {
    // Внутри строки позиции — с маленькой буквы («паста с курицей, салат»);
    // аббревиатуры («ПП-батончик») не трогаем.
    val list = meal.items.joinToString(", ") { item ->
        val n = item.name
        if (n.length > 1 && n[1].isUpperCase()) n else n.replaceFirstChar { it.lowercase() }
    }.ifBlank { meal.raw }
    return meal.kind.replaceFirstChar { it.uppercase() } + ": " + list
}

private fun macros(p: Int, f: Int, c: Int) = "Б $p · Ж $f · У $c"

private fun sourceWord(source: String): String? = when (source) {
    "photo" -> "по фото"
    "barcode" -> "по штрихкоду"
    "voice" -> "голосом"
    else -> null
}

/**
 * Разобранный, но не записанный приём (Правка 4.0, `screens/10`): «Ужин:
 * паста с курицей, салат» жирным, «≈ 720 ккал · Б 42 · Ж 24 · У 80 · по
 * фото», справа ✕ · ✎ и «Записать» главной клавишей. Тап по плашке — позиции,
 * замечание модели, «Сказано: …» и «разобрать заново».
 */
@Composable
private fun MealCard(
    app: PravkaApp,
    meal: FoodStore.Meal,
    pendingState: Boolean,
    onEdit: () -> Unit,
    onConfirm: () -> Unit,
    onDelete: () -> Unit,
    onReparse: (() -> Unit)?,
    onUnconfirm: (() -> Unit)? = null,
) {
    var open by remember(meal.id) { mutableStateOf(false) }
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    Column(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(22.dp), mode.glass)
            .clip(RoundedCornerShape(22.dp))
            .clickable { open = !open }
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(mealTitle(meal), style = ty.bodyStrong.copy(fontSize = ty.bodyL.fontSize, lineHeight = ty.bodyL.lineHeight),
            color = ru.zf.pravka.ui.Ink.TextStrong, maxLines = if (open) 6 else 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull("≈ ${ru.zf.pravka.core.Fmt.num(meal.kcal)} ккал", macros(meal.protein, meal.fat, meal.carbs), sourceWord(meal.source))
                .joinToString(" · "),
            style = ty.meta,
            color = mode.meta,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (open) {
            MealDetails(app, meal)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (open && onReparse != null) GlyphButton(Glyphs.Refresh, "разобрать заново", onClick = onReparse, tint = mode.label)
            if (onUnconfirm != null) PaperTextButton("Из дня", onClick = onUnconfirm, icon = Glyphs.Undo)
            Spacer(Modifier.weight(1f))
            GlyphButton(Glyphs.Close, "убрать приём", onClick = onDelete, tint = mode.label)
            GlyphButton(Glyphs.Edit, "поправить", onClick = onEdit, tint = mode.label)
            if (pendingState) {
                Spacer(Modifier.width(6.dp))
                ru.zf.pravka.ui.PrimaryKey("Записать", onClick = onConfirm)
            }
        }
    }
}

/** Позиции приёма, его витамины, замечание модели, снимок и сказанное — по тапу. */
@Composable
private fun MealDetails(app: PravkaApp, meal: FoodStore.Meal) {
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    Spacer(Modifier.height(8.dp))
    for (item in meal.items) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = ty.body, color = ru.zf.pravka.ui.Ink.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        if (item.pill) "таблетка" else null,
                        if (item.grams > 0) "${item.grams} г" else null,
                        // У таблетки макросов нет — писать «Б0 Ж0 У0» значит занимать строку ничем.
                        if (item.pill) null else macros(item.protein, item.fat, item.carbs),
                        item.sureness.takeIf { it.isNotBlank() && !item.pill },
                        item.micro.takeIf { it.isNotEmpty() }?.let { Micronutrients.short(it, limit = 4) },
                    ).joinToString(" · "),
                    style = ty.meta,
                    color = mode.meta,
                )
            }
            Text("${item.kcal}", style = ty.valueS, color = ru.zf.pravka.ui.Ink.Text)
        }
    }
    val mealMicro = meal.micro
    if (mealMicro.isNotEmpty()) {
        Text("Витамины приёма: " + Micronutrients.short(mealMicro, limit = 8), style = ty.meta, color = mode.meta, modifier = Modifier.padding(top = 6.dp))
    }
    if (meal.note.isNotBlank()) {
        Row(Modifier.padding(top = 6.dp)) {
            Icon(Glyphs.Error, contentDescription = null, tint = ru.zf.pravka.ui.Ink.Warn, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(meal.note, style = ty.label, color = ru.zf.pravka.ui.Ink.Warn)
        }
    }
    val photo = app.foodStore.photoFile(meal.photo)
    if (photo != null) {
        Text("Снимок сохранён (${photo.length() / 1024} КБ)", style = ty.meta, color = mode.meta, modifier = Modifier.padding(top = 6.dp))
    }
    if (meal.raw.isNotBlank() && meal.raw != meal.shortList) {
        Text("Сказано: «${meal.raw}»", style = ty.meta, color = mode.meta, modifier = Modifier.padding(top = 6.dp))
    }
    if (meal.confirmed) {
        val marks = listOfNotNull(if (meal.icuSynced) "intervals.icu" else null, if (meal.ribbonSynced) "лента" else null)
        if (marks.isNotEmpty()) Text("Уехало: " + marks.joinToString(", "), style = ty.meta, color = mode.meta, modifier = Modifier.padding(top = 6.dp))
    }
}

/**
 * Записанный приём строкой плашки «Приёмы» (`screens/10`): время, «Завтрак:
 * овсянка, два яйца» и «Б 28 · Ж 18 · У 60», справа калории. Тап — позиции и
 * ручки: «Из дня», ✕, ✎.
 */
@Composable
private fun MealRow(
    app: PravkaApp,
    meal: FoodStore.Meal,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onUnconfirm: () -> Unit,
) {
    var open by remember(meal.id) { mutableStateOf(false) }
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(mealTimeFormat.format(Date(meal.ts)), style = ty.time, color = mode.meta, modifier = Modifier.width(56.dp))
            Column(Modifier.weight(1f)) {
                Text(mealTitle(meal), style = ty.bodyL, color = ru.zf.pravka.ui.Ink.Text, maxLines = if (open) 6 else 2, overflow = TextOverflow.Ellipsis)
                Text(macros(meal.protein, meal.fat, meal.carbs), style = ty.meta, color = mode.meta)
            }
            Spacer(Modifier.width(10.dp))
            Text(ru.zf.pravka.core.Fmt.num(meal.kcal), style = ty.valueS, color = ru.zf.pravka.ui.Ink.TextStrong)
        }
        if (open) {
            Column(Modifier.padding(start = 56.dp)) {
                MealDetails(app, meal)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    // Убрать из дня — не то же, что удалить: разбор остаётся ждать, и
                    // приём можно записать заново, поправив.
                    PaperTextButton("Из дня", onClick = onUnconfirm, icon = Glyphs.Undo)
                    Spacer(Modifier.weight(1f))
                    GlyphButton(Glyphs.Delete, "убрать приём", onClick = onDelete, tint = mode.label)
                    GlyphButton(Glyphs.Edit, "поправить", onClick = onEdit, tint = mode.label)
                }
            }
        }
    }
}

/**
 * Итог дня (`screens/10`): сколько осталось — крупно, справа «1 545 съедено ·
 * цель 2 200»; полоса — съеденное заливкой, ждущее «✓» штриховкой, цель —
 * риской; под ней «с ужином перебор 65 ккал»; три макро полосками.
 */
@Composable
private fun DayTotalCard(
    total: FoodStore.DayTotal,
    pending: List<FoodStore.Meal>,
    targetKcal: Int,
    macros: List<Triple<String, Int, Int>>,
    today: Boolean,
) {
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    val eaten = total.kcal
    val waiting = pending.sumOf { it.kcal }
    Column(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(22.dp), mode.glass)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                if (targetKcal > 0) {
                    val left = targetKcal - eaten
                    Text(ru.zf.pravka.core.Fmt.num(kotlin.math.abs(left)), style = ty.displayXL, color = ru.zf.pravka.ui.Ink.TextStrong, maxLines = 1)
                    Text(
                        if (left >= 0) "ккал осталось" + (if (today) " на сегодня" else "") else "ккал перебор",
                        style = ty.label,
                        color = if (left >= 0) mode.label else ru.zf.pravka.ui.Ink.Warn,
                    )
                } else {
                    Text(ru.zf.pravka.core.Fmt.num(eaten), style = ty.displayXL, color = ru.zf.pravka.ui.Ink.TextStrong, maxLines = 1)
                    Text("ккал за день · цель — в настройках Еды", style = ty.label, color = mode.label)
                }
            }
            if (targetKcal > 0) {
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 2.dp)) {
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = ru.zf.pravka.ui.Ink.TextStrong))
                            append(ru.zf.pravka.core.Fmt.num(eaten))
                            pop()
                            append(" съедено")
                        },
                        style = ty.bodyL,
                        color = mode.label,
                    )
                    Text("цель ${ru.zf.pravka.core.Fmt.num(targetKcal)}", style = ty.meta, color = mode.meta)
                }
            }
        }
        if (targetKcal > 0) {
            Spacer(Modifier.height(12.dp))
            val scale = maxOf(targetKcal, eaten + waiting).toFloat() * 1.04f
            val fill = mode.ramp[1]
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2, size.height / 2)
                drawRoundRect(mode.tint.copy(alpha = 0.14f), cornerRadius = r)
                val we = size.width * (eaten / scale)
                if (waiting > 0) {
                    val ww = size.width * (waiting / scale)
                    clipRect(left = we - 4f, right = we + ww) {
                        hatch(fill, gapAlpha = 0.18f, topLeft = androidx.compose.ui.geometry.Offset(we - 4f, 0f), size = androidx.compose.ui.geometry.Size(ww + 4f, size.height), stripeAlpha = 0.7f)
                    }
                }
                drawRoundRect(fill, size = androidx.compose.ui.geometry.Size(we, size.height), cornerRadius = r)
                val x = size.width * (targetKcal / scale)
                drawLine(ru.zf.pravka.ui.Ink.Cream, androidx.compose.ui.geometry.Offset(x, -3f), androidx.compose.ui.geometry.Offset(x, size.height + 3f), strokeWidth = 2.5f)
            }
            if (waiting > 0) {
                val after = targetKcal - eaten - waiting
                val with = pending.firstOrNull()?.kind?.let { k ->
                    when (k) { "завтрак" -> "с завтраком"; "обед" -> "с обедом"; "ужин" -> "с ужином"; "перекус" -> "с перекусом"; else -> "с ожидающим" }
                } ?: "с ожидающим"
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append("$with ")
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = ru.zf.pravka.ui.Ink.Text))
                        append(if (after < 0) "перебор ${ru.zf.pravka.core.Fmt.num(-after)} ккал" else "останется ${ru.zf.pravka.core.Fmt.num(after)} ккал")
                        pop()
                    },
                    style = ty.label,
                    color = mode.label,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        for ((label, value, target) in macros) {
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = ty.body, color = mode.label, modifier = Modifier.width(96.dp))
                androidx.compose.foundation.Canvas(Modifier.weight(1f).height(8.dp)) {
                    val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2, size.height / 2)
                    drawRoundRect(mode.tint.copy(alpha = 0.14f), cornerRadius = r)
                    if (target > 0 && value > 0) {
                        val w = size.width * (value / target.toFloat()).coerceAtMost(1f)
                        drawRoundRect(mode.ramp[1], size = androidx.compose.ui.geometry.Size(w, size.height), cornerRadius = r)
                    }
                }
                Text(
                    if (target > 0) "$value / $target г" else "$value г",
                    style = ty.valueS.copy(fontSize = ty.body.fontSize),
                    color = ru.zf.pravka.ui.Ink.TextStrong,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.width(96.dp),
                )
            }
        }
        if (total.fiber > 0) {
            Text("клетчатка ${total.fiber} г · приёмов ${total.meals}", style = ty.meta, color = mode.meta, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/**
 * Неделя столбиками (`screens/10`): калории по дням, пунктиром — цель,
 * сегодня — светлее и со штриховкой ждущего «✓»; под столбиком — число и
 * день недели. Тап по столбику листает дневник на тот день.
 */
@Composable
private fun WeekKcalCard(
    week: List<FoodStore.DayTotal>,
    targetKcal: Int,
    pendingToday: Int,
    selected: String,
    onDay: (String) -> Unit,
) {
    val mode = ru.zf.pravka.ui.LocalMode.current
    val ty = ru.zf.pravka.ui.LocalPravkaType.current
    val withData = week.filter { it.kcal > 0 }
    val todayKey = week.lastOrNull()?.date
    Column(Modifier.fillMaxWidth()) {
        ru.zf.pravka.ui.SectionHeader(
            "неделя",
            trailing = if (withData.isNotEmpty()) "среднее ${ru.zf.pravka.core.Fmt.num(withData.sumOf { it.kcal } / withData.size)} за ${withData.size} ${dayWord(withData.size)}" else null,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(22.dp), mode.glass)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            val top = maxOf(targetKcal, week.maxOf { it.kcal + if (it.date == todayKey) pendingToday else 0 }).coerceAtLeast(1) * 1.08f
            if (targetKcal > 0) {
                Text("цель ${ru.zf.pravka.core.Fmt.num(targetKcal)}", style = ty.caption, color = mode.meta, modifier = Modifier.align(Alignment.End))
            }
            Box(Modifier.fillMaxWidth().height(120.dp)) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (d in week) {
                        val isToday = d.date == todayKey
                        androidx.compose.foundation.Canvas(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onDay(d.date) },
                        ) {
                            val r = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx(), 5.dp.toPx())
                            val h = size.height * d.kcal / top
                            val pend = if (isToday) size.height * pendingToday / top else 0f
                            if (pend > 0f) {
                                hatch(
                                    mode.ramp[1],
                                    gapAlpha = 0.16f,
                                    topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h - pend),
                                    size = androidx.compose.ui.geometry.Size(size.width, pend),
                                    stripeAlpha = 0.7f,
                                )
                            }
                            if (h > 0f) {
                                drawRoundRect(
                                    if (isToday) mode.ramp[1] else mode.key.copy(alpha = 0.95f),
                                    topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h),
                                    size = androidx.compose.ui.geometry.Size(size.width, h),
                                    cornerRadius = r,
                                )
                            }
                            if (d.date == selected && !isToday) {
                                drawRoundRect(mode.tint.copy(alpha = 0.6f), cornerRadius = r, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                            }
                        }
                    }
                }
                if (targetKcal > 0) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        val y = size.height * (1f - targetKcal / top)
                        drawLine(
                            ru.zf.pravka.ui.Ink.Cream.copy(alpha = 0.45f),
                            androidx.compose.ui.geometry.Offset(0f, y),
                            androidx.compose.ui.geometry.Offset(size.width, y),
                            strokeWidth = 1.5f,
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (d in week) {
                    val isToday = d.date == todayKey
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (d.kcal > 0) ru.zf.pravka.core.Fmt.num(d.kcal) else "—",
                            style = ty.caption.copy(fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal),
                            color = if (isToday) ru.zf.pravka.ui.Ink.TextStrong else mode.label,
                            maxLines = 1,
                        )
                        val ld = runCatching { java.time.LocalDate.parse(d.date) }.getOrNull()
                        Text(
                            ld?.let { ru.zf.pravka.core.Fmt.wd(it) }.orEmpty(),
                            style = ty.caption.copy(fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal),
                            color = if (isToday) ru.zf.pravka.ui.Ink.TextStrong else mode.meta,
                        )
                    }
                }
            }
        }
    }
}

private fun dayWord(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> "день"
        m10 in 2..4 && m100 !in 12..14 -> "дня"
        else -> "дней"
    }
}

/** Правка приёма: веса позиций, вид, время; каждая позиция — своей строкой. */
@Composable
private fun MealEditDialog(app: PravkaApp, meal: FoodStore.Meal, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var kind by remember(meal.id) { mutableStateOf(meal.kind) }
    var time by remember(meal.id) { mutableStateOf(mealTimeFormat.format(Date(meal.ts))) }
    // Веса правим строками: пустая строка = «оставь как было».
    var grams by remember(meal.id) {
        mutableStateOf(meal.items.map { if (it.grams > 0) it.grams.toString() else "" })
    }
    var names by remember(meal.id) { mutableStateOf(meal.items.map { it.name }) }

    // Лист вместо AlertDialog (24.09.2026): одно окно на всё приложение, и
    // лист сам поднимается над клавиатурой — полей тут по два на позицию.
    // «Отмены» нет: закрывают крестик и свайп, а сохранение ничего не ломает.
    PaperAlert(
        onDismiss = onClose,
        title = "Поправить приём",
        icon = Glyphs.Edit,
        subtitle = meal.kind.replaceFirstChar { it.uppercase() } + " · " +
            mealTimeFormat.format(Date(meal.ts)) + " · ${meal.kcal} ккал",
        confirm = SheetAction("Сохранить", icon = Glyphs.Check) {
            scope.launch {
                // Сначала позиции: вес меняет КБЖУ пропорционально, а имя
                // просто переписывается — модель за него не отвечает.
                val updated = meal.items.mapIndexed { index, item ->
                    val newGrams = grams.getOrElse(index) { "" }.toIntOrNull() ?: 0
                    val renamed = names.getOrElse(index) { item.name }.trim()
                        .ifBlank { item.name }
                    val scaled = if (newGrams > 0 && newGrams != item.grams) {
                        item.scaledTo(newGrams)
                    } else item
                    scaled.copy(name = renamed)
                }
                app.foodEngine.replaceItems(meal.id, updated)
                if (kind != meal.kind) app.foodEngine.setKind(meal.id, kind)
                parseClock(meal.ts, time)?.let { ts ->
                    if (ts != meal.ts) app.foodEngine.setTime(meal.id, ts)
                }
                onClose()
            }
        },
    ) {
        ChipRow {
            for (k in MealItem.KINDS) {
                PaperChip(k, selected = kind == k, onClick = { kind = k })
            }
        }
        PaperField(
            value = time,
            onValueChange = { time = it },
            label = "Во сколько (ЧЧ:ММ)",
        )
        meal.items.forEachIndexed { index, item ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PaperField(
                        value = names.getOrElse(index) { item.name },
                        onValueChange = { v ->
                            names = names.toMutableList().also { it[index] = v }
                        },
                        modifier = Modifier.weight(1f),
                        label = "Что",
                    )
                    Spacer(Modifier.width(6.dp))
                    PaperField(
                        value = grams.getOrElse(index) { "" },
                        onValueChange = { v ->
                            grams = grams.toMutableList().also {
                                it[index] = v.filter { c -> c.isDigit() }.take(4)
                            }
                        },
                        modifier = Modifier.width(96.dp),
                        label = "Грамм",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                PaperHint(
                    "${item.kcal} ккал · Б${item.protein} Ж${item.fat} У${item.carbs}" +
                        " — пересчитается по новому весу"
                )
            }
        }
    }
}

@Composable
internal fun BodyFoodSettings(app: PravkaApp) {
    val context = LocalContext.current
    // Настройки открываются и без захода в «Еду» — дневник мог быть не прочитан.
    LaunchedEffect(Unit) { runCatching { app.foodStore.load() } }
    val kcal by app.settings.foodKcalFlow.collectAsState(initial = 0)
    val protein by app.settings.foodProteinFlow.collectAsState(initial = 0)
    val fat by app.settings.foodFatFlow.collectAsState(initial = 0)
    val carbs by app.settings.foodCarbsFlow.collectAsState(initial = 0)
    val toIcu by app.settings.foodToIcuFlow.collectAsState(initial = true)
    val toRibbon by app.settings.foodToRibbonFlow.collectAsState(initial = true)
    val meals by app.foodStore.mealsFlow.collectAsState()

    var kcalText by remember(kcal) { mutableStateOf(kcal.toString()) }
    var proteinText by remember(protein) { mutableStateOf(protein.toString()) }
    var fatText by remember(fat) { mutableStateOf(fat.toString()) }
    var carbsText by remember(carbs) { mutableStateOf(carbs.toString()) }

    val saveTargets: () -> Unit = {
        app.appScope.launch {
            app.settings.setFoodTargets(
                kcalText.toIntOrNull() ?: kcal,
                proteinText.toIntOrNull() ?: protein,
                fatText.toIntOrNull() ?: fat,
                carbsText.toIntOrNull() ?: carbs,
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap)) {
        PaperCard(
            label = "цели на день",
            info = "«Посчитать от веса» берёт настоящий вес из intervals.icu: Миффлин-Сан-Жеор " +
                "при умеренной активности, белок 1,8 г/кг, жиры 0,9 г/кг, углеводы — остаток. " +
                "Посчитанное ложится в поля — проверь и сохрани.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField("Ккал", kcalText, Modifier.weight(1f)) { kcalText = it }
                NumberField("Белки", proteinText, Modifier.weight(1f)) { proteinText = it }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField("Жиры", fatText, Modifier.weight(1f)) { fatText = it }
                NumberField("Углеводы", carbsText, Modifier.weight(1f)) { carbsText = it }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PaperButton("От веса", icon = Glyphs.Sport, onClick = {
                    // Считаем от настоящего веса из intervals.icu, а не от памяти.
                    val weight = app.sportStore.lastWeight()
                        .takeIf { it > 0 } ?: app.sportStore.profileFlow.value.weightKg
                    if (weight <= 0) {
                        Feedback.toast(app, "Вес неизвестен — он приезжает из intervals.icu")
                    } else {
                        val computed = computeTargets(weight)
                        kcalText = computed.kcal.toString()
                        proteinText = computed.protein.toString()
                        fatText = computed.fat.toString()
                        carbsText = computed.carbs.toString()
                        Feedback.toast(app, "Посчитал от ${Math.round(weight)} кг — проверь и сохрани")
                    }
                })
                Spacer(Modifier.weight(1f))
                PaperButton("Сохранить", primary = true, onClick = saveTargets)
            }
        }

        PaperCard(label = "куда уходит еда") {
            PaperToggle(
                title = "Приписывать к ленте",
                checked = toRibbon,
                onCheckedChange = { v -> app.appScope.launch { app.settings.setFoodToRibbon(v) } },
                info = "КБЖУ дописывается к записи «Еда» в Засечке, если она в это время " +
                    "есть. Своих записей лента от еды не отращивает.",
            )
            PaperToggle(
                title = "Писать в intervals.icu",
                checked = toIcu,
                onCheckedChange = { v -> app.appScope.launch { app.settings.setFoodToIcu(v) } },
                info = "Итог дня уезжает в wellness (ккал, Б/Ж/У) — там эти поля пустуют, " +
                    "и оттуда их видит разбор тренировок.",
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PaperHint(
                    "Записанных приёмов: ${meals.count { it.confirmed }}. " +
                        "Файл food.json — в часовых копиях вместе с лентой.",
                )
            }
            Spacer(Modifier.height(6.dp))
            PaperButton("Донести в intervals.icu", icon = Glyphs.Upload, onClick = {
                app.appScope.launch {
                    val done = app.foodEngine.syncPending(force = true)
                    Feedback.toast(app, if (done > 0) "Дней уехало: $done" else "Всё уже на месте")
                }
            })
        }
    }
}


@Composable
private fun NumberField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    PaperField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() }.take(4)) },
        modifier = modifier,
        label = label,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

// ---- Мелочи ----

/**
 * Цели от веса. Миффлин-Сан-Жеор для мужчины 43 лет, 180 см, умеренная
 * активность (тренировки считаются отдельно — их видно во «Спорте», и еду под
 * тренировку добирают сознательно, а не средним коэффициентом).
 */
private fun computeTargets(weightKg: Double): Targets4 {
    val bmr = 10 * weightKg + 6.25 * 180 - 5 * 43 + 5
    val kcal = Math.round(bmr * 1.4).toInt()
    val protein = Math.round(weightKg * 1.8).toInt()
    val fat = Math.round(weightKg * 0.9).toInt()
    val carbs = ((kcal - protein * 4 - fat * 9) / 4).coerceAtLeast(0)
    return Targets4(kcal, protein, fat, carbs)
}

private class Targets4(val kcal: Int, val protein: Int, val fat: Int, val carbs: Int)

private fun dayStartBackFood(offsetDays: Int): Long {
    val cal = java.util.Calendar.getInstance().apply {
        timeInMillis = System.currentTimeMillis()
        add(java.util.Calendar.DAY_OF_YEAR, -offsetDays)
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

/** «2026-08-21» → сколько дней назад это было (для листания дневника). */
private fun daysBetween(date: String): Int {
    val parsed = runCatching { isoFormat.parse(date)?.time }.getOrNull() ?: return 0
    val today = dayStartBackFood(0)
    return ((today - parsed) / 86_400_000L).toInt().coerceAtLeast(0)
}


/** «13:40» на дне приёма → метка времени; мусор даёт null (оставить как было). */
private fun parseClock(baseTs: Long, clock: String): Long? {
    val parts = clock.trim().split(':')
    val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val minute = parts.getOrNull(1)?.toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    val cal = java.util.Calendar.getInstance().apply {
        timeInMillis = baseTs
        set(java.util.Calendar.HOUR_OF_DAY, hour)
        set(java.util.Calendar.MINUTE, minute)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

/** Снимок из галереи — во временный файл: движку нужен File, а не Uri. */
private fun copyToCache(context: android.content.Context, uri: Uri): File {
    val out = File(context.cacheDir, "eda-pick.jpg")
    context.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { input.copyTo(it) }
    } ?: throw java.io.IOException("Снимок не открылся")
    return out
}


/**
 * «Мой рацион» — сворачиваемый список штатной еды, где порция записывается
 * тапом.
 *
 * Зачем он, если есть микрофон. Половина еды владельца повторяется каждый день
 * и уже посчитана с настоящих этикеток в базе Notion «Рацион». Говорить про неё
 * — это платить модели за то, что и так известно точно, и получать «примерно»
 * там, где есть «точно». Хуже того, владелец говорит блюдами: на «каша моя»
 * модель отвечала «сказано только каша без подробностей» и не записывала
 * ничего.
 *
 * Промпт теперь такие фразы разворачивает сам (рацион уезжает в него по
 * приёмам), но тап надёжнее любого промпта: он бесплатный, мгновенный и
 * работает на даче без интернета.
 *
 * Приёмы взяты из Notion как есть. Граммы там работают переключателем: у
 * выбранного варианта порция стоит, у альтернативы ноль — поэтому «весь обед»
 * берёт грудку ИЛИ бедро, а не обе, а вариант на замену показан бледным и
 * записывается только по отдельному тапу.
 */
@Composable
private fun RationSection(app: PravkaApp, dayStart: Long) {
    var open by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(app.rationBook.loaded) }
    var asking by remember { mutableStateOf<RationBook.Product?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(open) {
        if (open && !loaded) {
            app.rationBook.load()
            loaded = app.rationBook.loaded
        }
    }

    /** Записать порции сразу подтверждённым приёмом: цифры с этикеток, спорить не о чем. */
    val put: (String, List<Pair<RationBook.Product, Int>>) -> Unit = { meal, chosen ->
        if (chosen.isNotEmpty()) {
            app.appScope.launch {
                val parsed = app.foodEngine.record(
                    items = chosen.map { (p, g) -> p.item(g) },
                    kind = RationBook.mealKind(meal),
                    timeOfDay = "",
                    raw = "рацион: " + chosen.joinToString(", ") {
                        it.first.shortName + " " + it.second + " г"
                    },
                    note = "",
                    source = "ration",
                    // Листаешь дневник назад — запись ложится в тот день, а не в
                    // сегодняшний.
                    at = mealStampFor(dayStart),
                )
                val outcome = app.foodEngine.confirm(parsed.meal.id)
                Feedback.toast(
                    app,
                    "Записал: ${parsed.meal.kcal} ккал · Б${parsed.meal.protein} " +
                        "Ж${parsed.meal.fat} У${parsed.meal.carbs}" +
                        (if (outcome.icuError.isNotBlank()) " (в intervals не уехало)" else ""),
                )
            }
        }
    }

    // Свёрнутый рацион — одна строка-сводка с шевроном (24.09.2026), а что это
    // и как им пользоваться — за «i»: абзац про этикетки нужен раз, а стоял
    // на свёрнутой плашке каждый день.
    PaperCard(
        label = "мой рацион",
        info = "Штатная еда с настоящих этикеток: завтрак, обед, ужин и восемь " +
            "вариантов углеводного слота. Тап вместо диктовки — бесплатно, " +
            "точно и без интернета. Тап по строке — записать порцию, тап по " +
            "граммам — сменить вес.",
    ) {
        SummaryLine(
            title = "Штатная еда с этикеток",
            summary = if (loaded) "${app.rationBook.byMeal().sumOf { it.second.size }} поз." else "",
            expanded = open,
            onToggle = { open = !open },
        ) {
            if (!loaded) {
                PaperHint("Читаю справочник…")
                return@SummaryLine
            }
            // Своя колонка без шага: у набора шаг 8 между всеми детьми, а тут
            // строки рациона плотные и отбиваются только заголовки приёмов.
            Column {
                for ((meal, list) in app.rationBook.byMeal()) {
                    val set = list.filter { it.defaultGrams > 0 }
                    val kcal = set.sumOf { Math.round(it.kcal100 * it.defaultGrams / 100.0).toInt() }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                RationBook.mealTitle(meal),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            PaperHint("${set.size} поз. · $kcal ккал")
                        }
                        // «Слот · опция» — это варианты на замену пюре, а не приём:
                        // целиком их не едят, и кнопки «весь слот» быть не должно.
                        if (set.size > 1 && !meal.contains("Слот")) {
                            PaperButton(
                                "Весь " + RationBook.mealTitle(meal).lowercase(),
                                onClick = { put(meal, set.map { it to it.defaultGrams }) },
                                icon = Glyphs.Plus,
                            )
                        }
                    }
                    for (p in list) {
                        RationRow(
                            product = p,
                            onPut = { put(meal, listOf(p to p.defaultGrams)) },
                            onGrams = { asking = p },
                        )
                    }
                }
            }
        }
    }

    val ask = asking
    if (ask != null) {
        GramsDialog(
            product = ask,
            onClose = { asking = null },
            onPut = { grams ->
                asking = null
                put(ask.meal, listOf(ask to grams))
            },
        )
    }
}

@Composable
private fun RationRow(
    product: RationBook.Product,
    onPut: () -> Unit,
    onGrams: () -> Unit,
) {
    val switchable = product.defaultGrams <= 0
    val grams = if (switchable) 100 else product.defaultGrams
    val kcal = Math.round(product.kcal100 * grams / 100.0).toInt()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPut)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                product.shortName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (switchable) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
            PaperHint(
                if (switchable) "вариант на замену · $kcal ккал за 100 г"
                else "$kcal ккал · Б${r(product.protein100, grams)} " +
                    "Ж${r(product.fat100, grams)} У${r(product.carbs100, grams)}"
            )
        }
        Spacer(Modifier.width(10.dp))
        // Граммы — своя кнопка: порция меняется чаще, чем состав.
        PaperTextButton("$grams г", onClick = onGrams)
    }
}

@Composable
private fun GramsDialog(
    product: RationBook.Product,
    onClose: () -> Unit,
    onPut: (Int) -> Unit,
) {
    var text by remember {
        mutableStateOf(product.defaultGrams.takeIf { it > 0 }?.toString() ?: "100")
    }
    val grams = text.toIntOrNull() ?: 0
    PaperAlert(
        onDismiss = onClose,
        title = product.shortName,
        icon = Glyphs.Food,
        subtitle = RationBook.mealTitle(product.meal),
        confirm = SheetAction("Записать", icon = Glyphs.Check, enabled = grams > 0) {
            if (grams > 0) onPut(grams)
        },
    ) {
        NumberField("Граммы", text, Modifier.fillMaxWidth()) { text = it }
        if (grams > 0) {
            val i = product.item(grams)
            Text(
                "${i.kcal} ккал · Б${i.protein} Ж${i.fat} У${i.carbs}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (product.note.isNotBlank()) PaperHint(product.note)
    }
}

private fun r(per100: Double, grams: Int): Int = Math.round(per100 * grams / 100.0).toInt()

/**
 * Метка времени для записи на показанный день: сегодня — сейчас, прошлый день —
 * его полдень. Полдень, а не начало суток: приём попадает внутрь дня при любом
 * часовом поясе и не съезжает на сутки назад в выгрузке.
 */
private fun mealStampFor(dayStart: Long): Long {
    val now = System.currentTimeMillis()
    return if (now - dayStart in 0 until 86_400_000L) now else dayStart + 12 * 3_600_000L
}
