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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import ru.zf.pravka.ui.GoalBar
import ru.zf.pravka.ui.GoalRow
import ru.zf.pravka.ui.IconAction
import ru.zf.pravka.ui.MicroBar
import ru.zf.pravka.ui.MicroOver
import ru.zf.pravka.ui.microColor
import ru.zf.pravka.ui.PaperAlert
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperIconButton
import ru.zf.pravka.ui.PaperLabel
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.PaperToggle
import ru.zf.pravka.ui.ScreenPad
import ru.zf.pravka.ui.SheetAction
import ru.zf.pravka.ui.SummaryLine
import ru.zf.pravka.ui.VoiceInput

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

private val KCAL_COLOR = Color(0xFFEA580C)
private val PROTEIN_COLOR = Color(0xFF0E7490)
private val FAT_COLOR = Color(0xFFCA8A04)
private val CARBS_COLOR = Color(0xFF16A34A)

@Composable
internal fun FoodTab(
    app: PravkaApp,
    // «Сфоткай тарелку»/«штрихкод» с кнопки еды: вкладка открывается и сразу
    // запускает камеру или сканер, без лишнего тапа.
    autoAction: String? = null,
    onAutoConsumed: () -> Unit = {},
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

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = ScreenPad.Padding,
        verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
    ) {
        // ---- День и его итог ----
        // Один навигатор дня на все вкладки (24.09.2026): «Сегодня» и дата под
        // ним; дальше вчерашнего дата и есть название — второй раз её не пишем.
        item {
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
        }
        item {
            PaperCard(label = if (total.empty) "за день ничего" else "за день") {
                GoalRow("Калории", total.kcal, targetKcal, "ккал", KCAL_COLOR)
                GoalRow("Белки", total.protein, targetProtein, "г", PROTEIN_COLOR)
                GoalRow("Жиры", total.fat, targetFat, "г", FAT_COLOR)
                GoalRow("Углеводы", total.carbs, targetCarbs, "г", CARBS_COLOR)
                if (total.fiber > 0) {
                    Spacer(Modifier.height(6.dp))
                    PaperHint("Клетчатки ${total.fiber} г · приёмов ${total.meals}")
                }
                if (targetKcal > 0 && !total.empty) {
                    Spacer(Modifier.height(8.dp))
                    val left = targetKcal - total.kcal
                    Text(
                        if (left >= 0) "Осталось $left ккал"
                        else "Перебор ${-left} ккал",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (left >= 0) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // ---- Витамины и элементы ----
        item { MicroCard(total) }

        // ---- Что съел: четыре дороги ----
        item {
            // Строка ввода — общая на все вкладки (24.09.2026); снимок, галерея
            // и штрихкод — значками с подписью над полем: три кнопки словами и
            // значками в ряд на узком внешнем экране Fold не встают. Пока Claude разбирает,
            // крутилка — в строке подписи плашки: поле и значки при этом гаснут.
            PaperCard(
                label = "записать",
                info = "Голосом — кнопка «Т» на экране. Снимок читается вместе с " +
                    "подписью из поля: там уточняют невидимое (масло в салате, " +
                    "сахар в кофе).",
                trailing = if (busy) {
                    { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
                } else null,
            ) {
                VoiceInput(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = "Омлет из трёх яиц и тост с авокадо",
                    onSend = { parseText(draft.trim()) },
                    enabled = !busy,
                    sendEnabled = !busy && draft.isNotBlank(),
                    busy = busy,
                    extras = {
                        IconAction(Glyphs.Camera, "Снять", enabled = !busy, onClick = {
                            val file = File(context.cacheDir, "eda-shot.jpg")
                            pendingPhoto = file
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, BuildConfig.APPLICATION_ID + ".files", file
                            )
                            camera.launch(uri)
                        })
                        IconAction(Glyphs.Image, "Галерея", enabled = !busy, onClick = {
                            gallery.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        })
                        IconAction(Glyphs.Barcode, "Штрихкод", enabled = !busy, onClick = {
                            ru.zf.pravka.ui.scanBarcode(
                                context = context,
                                onFail = { message -> Feedback.toast(app, message, long = true) },
                            ) { code ->
                                busy = true
                                app.appScope.launch {
                                    val result = runCatching { app.foodEngine.parseBarcode(code) }
                                        .getOrElse { Result.failure(it) }
                                    busy = false
                                    result.onFailure { e ->
                                        Feedback.toast(
                                            app,
                                            (e.message ?: "Штрихкод не нашёлся") +
                                                " — сними этикетку камерой",
                                            long = true,
                                        )
                                    }
                                }
                            }
                        })
                    },
                )
            }
        }

        // ---- Мой рацион: то, что повторяется каждый день ----
        item { RationSection(app, dayStart) }

        // ---- Разобранное, но не записанное ----
        if (pending.isNotEmpty()) {
            item { PaperLabel("разобрано, ждёт «✓»") }
            items(pending.size, key = { i -> "p" + pending[i].id }) { i ->
                val meal = pending[i]
                MealCard(
                    app = app,
                    meal = meal,
                    pendingState = true,
                    onEdit = { editing = meal.id },
                    onConfirm = {
                        scope.launch {
                            val outcome = app.foodEngine.confirm(meal.id)
                            if (outcome.icuError.isNotBlank()) {
                                Feedback.toast(
                                    app,
                                    "Записал. В intervals.icu не уехало: ${outcome.icuError}",
                                    long = true,
                                )
                            }
                        }
                    },
                    onDelete = { scope.launch { app.foodEngine.delete(meal.id) } },
                    onReparse = {
                        busy = true
                        app.appScope.launch {
                            val result = runCatching { app.foodEngine.reparse(meal.id) }
                                .getOrElse { Result.failure(it) }
                            busy = false
                            result.onFailure { e ->
                                Feedback.toast(app, e.message ?: "Не вышло", long = true)
                            }
                        }
                    },
                )
            }
        }

        // ---- Приёмы дня ----
        item { PaperLabel(if (dayMeals.isEmpty()) "приёмов нет" else "приёмы дня") }
        if (dayMeals.isEmpty()) {
            item {
                PaperCard {
                    Text(
                        "За этот день ничего не записано.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    PaperHint("Скажи кнопкой «Т», набери выше или сними тарелку.")
                }
            }
        } else {
            items(dayMeals.size, key = { i -> dayMeals[i].id }) { i ->
                val meal = dayMeals[i]
                MealCard(
                    app = app,
                    meal = meal,
                    pendingState = false,
                    onEdit = { editing = meal.id },
                    onConfirm = {},
                    onDelete = { scope.launch { app.foodEngine.delete(meal.id) } },
                    onReparse = null,
                    onUnconfirm = { scope.launch { app.foodEngine.unconfirm(meal.id) } },
                )
            }
        }

        // ---- Неделя одной полоской ----
        item {
            val week = remember(meals) { store.recentDays(7) }
            if (week.isNotEmpty()) {
                PaperCard(label = "неделя") {
                    for (d in week) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    // Тап по дню недели листает дневник туда.
                                    dayOffset = daysBetween(d.date)
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    humanDay(d.date),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                GoalBar(d.kcal, targetKcal, KCAL_COLOR, height = 6.dp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    "${d.kcal}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (targetKcal > 0 && d.kcal > targetKcal)
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                                PaperHint("Б${d.protein} Ж${d.fat} У${d.carbs}")
                            }
                        }
                    }
                    val avg = week.sumOf { it.kcal } / week.size
                    Spacer(Modifier.height(6.dp))
                    PaperHint(
                        "В среднем $avg ккал по ${week.size} дн. с записями" +
                            (if (targetKcal > 0) ", цель $targetKcal" else "")
                    )
                }
            }
        }

        // Настройки режима — за шестерёнкой в шапке вкладки.
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

/** Сколько дефицитов показывает свёрнутая карточка — и полосками, и в строке. */
private const val LOW_SHOWN = 4

/**
 * Витамины и элементы за день: полоска на вещество, засечка нормы, светофор.
 *
 * Свёрнутая карточка показывает не первые попавшиеся вещества, а те, которых
 * МАЛО: это единственная строка, по которой можно что-то сделать сегодня.
 * Остальные полоски — по тапу: они нужны раз в неделю, а места занимают
 * весь экран.
 */
@Composable
private fun MicroCard(total: FoodStore.DayTotal) {
    var open by remember { mutableStateOf(false) }
    val totals = total.micro
    val low = remember(totals) { Micronutrients.lacking(totals) }
    val over = remember(totals) { Micronutrients.over(totals) }
    val shown = if (open) Micronutrients.ALL else low.take(LOW_SHOWN)
    // Пояснения — за «i», раскрытие — шевроном (24.09.2026): на плашке сама
    // сводка дня, а откуда цифры и что значит риска — по требованию.
    PaperCard(
        label = "витамины и элементы",
        info = "Свёрнутая плашка показывает то, чего мало; все ${Micronutrients.ALL.size} " +
            "полосок — по стрелке. Вещества приезжают с разбором еды и с рационом; " +
            "таблетки скажи отдельно — «выпил витамин D и магний». Риска на полоске — " +
            "суточная норма мужчины 43 лет. Цифры считает модель по составу еды и точно — " +
            "по рациону и штрихкоду: это порядок величины, а не анализ крови. У натрия " +
            "норма — потолок, а не цель.",
        trailing = {
            GlyphButton(
                Glyphs.ChevronDown,
                if (open) "свернуть" else "все ${Micronutrients.ALL.size}",
                onClick = { open = !open },
                modifier = Modifier.rotate(if (open) 180f else 0f),
                size = 32.dp,
            )
        },
    ) {
        if (totals.isEmpty() && !open) {
            PaperHint("За этот день веществ не посчитано.")
            return@PaperCard
        }
        // Одна строка вместо двадцати полосок: что закрыто, чего мало, что
        // перебрано. Свёрнутую карточку читают именно её.
        val okCount = Micronutrients.ALL.count {
            Micronutrients.level(it, totals[it.id] ?: 0.0) == Micronutrients.Level.OK
        }
        // Список дефицитов режем: в пустой день их девятнадцать, и строка
        // превращается в стену, из которой не следует ничего.
        val lowNames = low.take(LOW_SHOWN).joinToString(", ") { it.name.lowercase() } +
            (if (low.size > LOW_SHOWN) " и ещё ${low.size - LOW_SHOWN}" else "")
        Text(
            "Норму закрыли $okCount из ${Micronutrients.ALL.size}" +
                (if (low.isEmpty()) "" else " · мало: $lowNames"),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (over.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Перебор: " + over.joinToString(", ") { it.name.lowercase() },
                style = MaterialTheme.typography.bodyMedium,
                color = MicroOver,
            )
        }
        if (total.pills.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            PaperHint("Из банки: " + total.pills)
        }
        if (shown.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            PaperHint("Ничего в дефиците — все полоски по стрелке.")
            return@PaperCard
        }
        Spacer(Modifier.height(10.dp))
        for (n in shown) {
            MicroRow(
                nutrient = n,
                value = totals[n.id] ?: 0.0,
                fromPills = total.microPills[n.id] ?: 0.0,
            )
        }
    }
}

/** Одно вещество: сколько от нормы, светофором, и зачем оно нужно. */
@Composable
private fun MicroRow(
    nutrient: Micronutrients.Nutrient,
    value: Double,
    fromPills: Double,
) {
    val level = Micronutrients.level(nutrient, value)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(nutrient.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                Micronutrients.amount(value) + " / " + Micronutrients.amount(nutrient.norm) +
                    " " + nutrient.unit + " · " + Micronutrients.word(level),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = microColor(level),
            )
        }
        Spacer(Modifier.height(4.dp))
        MicroBar(nutrient, value)
        Spacer(Modifier.height(4.dp))
        PaperHint(
            (if (fromPills > 0) "из банки " + Micronutrients.amount(fromPills) + " " +
                nutrient.unit + " · " else "") + nutrient.why + ". " + nutrient.source
        )
    }
}

/** Один приём: позиции, итог и ручки. */
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
    var open by remember(meal.id) { mutableStateOf(pendingState) }
    val accent = kindColor(meal.kind)
    PaperCard {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(38.dp)
                    .background(accent, MaterialTheme.shapes.extraSmall)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    meal.kind.replaceFirstChar { it.uppercase() } + " · " +
                        mealTimeFormat.format(Date(meal.ts)),
                    style = MaterialTheme.typography.bodyLarge,
                )
                // Длинный список позиций в одну строку не влезает — раскрытие
                // карточки покажет всё.
                PaperHint(meal.shortList.ifBlank { meal.raw })
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${meal.kcal} ккал",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                PaperHint("Б${meal.protein} Ж${meal.fat} У${meal.carbs}")
            }
        }
        if (!open) return@PaperCard
        Spacer(Modifier.height(8.dp))
        for (item in meal.items) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    PaperHint(
                        listOfNotNull(
                            if (item.pill) "таблетка" else null,
                            if (item.grams > 0) "${item.grams} г" else null,
                            // У таблетки макросов нет — писать «Б0 Ж0 У0» значит
                            // занимать строку ничем.
                            if (item.pill) null else "Б${item.protein} Ж${item.fat} У${item.carbs}",
                            item.sureness.takeIf { it.isNotBlank() && !item.pill },
                            item.micro.takeIf { it.isNotEmpty() }?.let { Micronutrients.short(it, limit = 4) },
                        ).joinToString(" · ")
                    )
                }
                Text("${item.kcal}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        val mealMicro = meal.micro
        if (mealMicro.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            PaperHint("Витамины приёма: " + Micronutrients.short(mealMicro, limit = 8))
        }
        if (meal.note.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "⚠ " + meal.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        val photo = app.foodStore.photoFile(meal.photo)
        if (photo != null) {
            Spacer(Modifier.height(6.dp))
            PaperHint("Снимок сохранён (${photo.length() / 1024} КБ)")
        }
        if (meal.raw.isNotBlank() && meal.raw != meal.shortList) {
            Spacer(Modifier.height(6.dp))
            PaperHint("Сказано: «${meal.raw}»")
        }
        Spacer(Modifier.height(10.dp))
        // Одна главная — «Записать» справа, под большим пальцем (24.09.2026).
        // Мелкие ручки слева значками; «Поправить» — кружком с кромкой: словом
        // рядом с «Записать» и двумя значками он на внешнем экране Fold не
        // встаёт, а у обоих видов карточки ручка правки должна быть одна.
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphButton(Glyphs.Delete, "убрать приём", onClick = onDelete)
            if (onReparse != null) {
                GlyphButton(Glyphs.Refresh, "разобрать заново", onClick = onReparse)
            }
            // Убрать из дня — не то же, что удалить: разбор остаётся ждать, и
            // приём можно записать заново, поправив.
            if (onUnconfirm != null) {
                PaperTextButton("Из дня", onClick = onUnconfirm, icon = Glyphs.Undo)
            }
            Spacer(Modifier.weight(1f))
            PaperIconButton(Glyphs.Edit, "поправить", onClick = onEdit)
            if (pendingState) {
                Spacer(Modifier.width(8.dp))
                PaperButton("Записать", onClick = onConfirm, icon = Glyphs.Check, primary = true)
            }
        }
        if (!pendingState) {
            val marks = listOfNotNull(
                if (meal.icuSynced) "intervals.icu" else null,
                if (meal.ribbonSynced) "лента" else null,
            )
            if (marks.isNotEmpty()) PaperHint("Уехало: " + marks.joinToString(", "))
        }
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

@Composable
private fun kindColor(kind: String): Color {
    val hue = when (kind.trim().lowercase()) {
        "завтрак" -> 45f
        "обед" -> 25f
        "ужин" -> 280f
        else -> 150f
    }
    return Color.hsv(hue, 0.5f, 0.92f)
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

private fun humanDay(date: String): String {
    val back = daysBetween(date)
    if (back == 0) return "Сегодня"
    if (back == 1) return "Вчера"
    val parsed = runCatching { isoFormat.parse(date) }.getOrNull() ?: return date
    return dayTitleFormat.format(parsed)
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
