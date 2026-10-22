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
import ru.zf.pravka.ui.glass
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
import ru.zf.pravka.ui.ChipRow
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

private fun sourceWord(meal: FoodStore.Meal): String? = when {
    // Записано на компе (docs/eda-server.md) — это и есть источник, важнее «как».
    fromPc(meal) != null -> fromPc(meal)
    meal.source == "photo" -> "по фото"
    meal.source == "barcode" -> "по штрихкоду"
    meal.source == "voice" -> "голосом"
    else -> null
}

/** «с компа» у приёма, записанного службой «Еда» на домашнем компе; свой — null. */
internal fun fromPc(meal: FoodStore.Meal): String? = if (meal.origin == "pc") "с компа" else null

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
            // Правда — запись Свода `food.goals` (docs/eda-server.md): правка уходит туда.
            app.saveFoodGoals(
                kcalText.toIntOrNull() ?: kcal,
                proteinText.toIntOrNull() ?: protein,
                fatText.toIntOrNull() ?: fat,
                carbsText.toIntOrNull() ?: carbs,
            )
        }
    }
    val svodView by app.svodStore.view.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap)) {
        PaperCard(
            label = "цели на день",
            info = "«Посчитать от веса» берёт настоящий вес из intervals.icu: Миффлин-Сан-Жеор " +
                "при умеренной активности, белок 1,8 г/кг, жиры 0,9 г/кг, углеводы — остаток. " +
                "Посчитанное ложится в поля — проверь и сохрани. Цели живут на сервере Дел " +
                "(запись food.goals): их видит и правит «Еда» на компе, сохранённое здесь уходит туда.",
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
            Spacer(Modifier.height(6.dp))
            PaperHint(app.foodGoalsSource(svodView))
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
internal fun NumberField(
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

/** Снимок из галереи — во временный файл: движку нужен File, а не Uri. */
private fun copyToCache(context: android.content.Context, uri: Uri): File {
    val out = File(context.cacheDir, "eda-pick.jpg")
    context.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { input.copyTo(it) }
    } ?: throw java.io.IOException("Снимок не открылся")
    return out
}
