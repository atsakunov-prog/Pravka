package ru.zf.pravka

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.util.Date
import ru.zf.pravka.core.Fmt
import ru.zf.pravka.data.FoodStore
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.Kv
import ru.zf.pravka.ui.KvBar
import ru.zf.pravka.ui.KvBarRow
import ru.zf.pravka.ui.KvColumn
import ru.zf.pravka.ui.KvColumns
import ru.zf.pravka.ui.KvGap
import ru.zf.pravka.ui.KvKpi
import ru.zf.pravka.ui.KvListRow
import ru.zf.pravka.ui.KvNote
import ru.zf.pravka.ui.KvPill
import ru.zf.pravka.ui.KvSetRow
import ru.zf.pravka.ui.KvSmallButton
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.trigger.onFoodTap

// «Еда» в виде v2 (Правка 5.0, этап 6, холст `Eda.dc.html`): итог дня
// крупным числом и полосами КБЖУ, дневник строками, цели, неделя столбиками.
// Раскладка — в `FoodTab.kt` (ветка v2); приёмы, витамины и рацион — те же
// данные и те же действия, что у вкладки 4.0, другая только вёрстка.

/** Самая слабая уверенность среди позиций дня: «наугад» сильнее «примерно» (как в архиве). */
private fun daySureness(meals: List<FoodStore.Meal>): String? {
    val all = meals.flatMap { m -> m.items.filterNot { it.pill }.map { it.sureness } }
    return when {
        all.any { it == "наугад" } -> "есть наугад"
        all.any { it == "примерно" } -> "числа примерные"
        else -> null
    }
}

/**
 * Итог дня (холст: «1 200 из 2 000 ккал» крупно, полоса акцентом, справа —
 * «числа примерные»; под ней белок, жиры и углеводы строками с полосой). Перебор
 * — полоса цветом «на заметку» и словами; ждущее «✓» — строкой под полосой.
 */
@Composable
internal fun FoodTotalCardV2(
    total: FoodStore.DayTotal,
    meals: List<FoodStore.Meal>,
    pending: List<FoodStore.Meal>,
    targetKcal: Int,
    macros: List<Triple<String, Int, Int>>,
    wide: Boolean,
) {
    val eaten = total.kcal
    val sure = daySureness(meals)
    PaperCard {
        Row(verticalAlignment = Alignment.Top) {
            val left = targetKcal - eaten
            KvKpi(
                Fmt.num(eaten),
                Modifier.weight(1f),
                unit = if (targetKcal > 0) "из ${Fmt.num(targetKcal)} ккал" else "ккал",
                label = when {
                    targetKcal <= 0 -> "цель — в настройках Еды"
                    !wide -> null
                    left >= 0 -> "осталось ${Fmt.num(left)}" + (sure?.let { " · $it" } ?: "")
                    else -> "перебор ${Fmt.num(-left)}" + (sure?.let { " · $it" } ?: "")
                },
            )
            if (!wide && sure != null) KvPill(sure, modifier = Modifier.padding(top = 6.dp, start = 8.dp))
        }
        if (targetKcal > 0) {
            KvBar(eaten.toFloat() / targetKcal, Modifier.padding(top = 8.dp, bottom = 10.dp), color = if (eaten > targetKcal) Kv.warn else Kv.accent)
            if (!wide && eaten > targetKcal) KvNote("перебор ${Fmt.num(eaten - targetKcal)} ккал", Modifier.padding(bottom = 6.dp), color = Kv.warn)
        } else {
            KvGap()
        }
        val waiting = pending.sumOf { it.kcal }
        if (waiting > 0 && targetKcal > 0) {
            val after = targetKcal - eaten - waiting
            KvNote(
                "ждёт «✓» ещё ${Fmt.num(waiting)} ккал — " +
                    if (after < 0) "с ним перебор ${Fmt.num(-after)}" else "с ним останется ${Fmt.num(after)}",
                Modifier.padding(bottom = 6.dp),
            )
        }
        for ((label, value, target) in macros) {
            KvBarRow(
                label,
                if (target > 0) value.toFloat() / target else 0f,
                if (target > 0) "$value из $target г" else "$value г",
                labelWidth = 82.dp,
                valueWidth = 84.dp,
                color = if (target in 1 until value) Kv.warn else Kv.barFill,
            )
        }
        if (total.fiber > 0) {
            KvGap(6.dp)
            KvNote("клетчатка ${total.fiber} г")
        }
    }
}

/**
 * Дневник (холст: время мелко, что съел, «завтрак · Б · Ж · У», калории
 * справа; на развороте — вид приёма жирным, состав под ним). Тап по строке —
 * позиции, вещества, сказанное и ручки: «Из дня», убрать, поправить.
 */
@Composable
internal fun FoodDiaryCardV2(
    app: PravkaApp,
    meals: List<FoodStore.Meal>,
    wide: Boolean,
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onUnconfirm: (Long) -> Unit,
    pending: List<FoodStore.Meal> = emptyList(),
    onConfirm: (Long) -> Unit = {},
    onReparse: (Long) -> Unit = {},
) {
    PaperCard(label = "Дневник", note = if (meals.isEmpty() || wide) null else plural(meals.size, "приём", "приёма", "приёмов")) {
        // Разобранное, но не записанное — в дневнике же, строкой «на заметку» с «Записать»
        // (холст: «Ужин? — есть в ленте, нет в дневнике · Записать»): в сумму дня оно не идёт.
        pending.forEachIndexed { i, meal ->
            var open by remember(meal.id) { mutableStateOf(false) }
            KvListRow(
                title = meal.kind.ifBlank { "приём" }.replaceFirstChar { it.uppercase() } + "?",
                titleColor = Kv.warn,
                titleWeight = FontWeight.SemiBold,
                sub = "ждёт «✓» · ≈ ${Fmt.num(meal.kcal)} ккал · " + mealList(meal),
                lead = mealTimeFormat.format(Date(meal.ts)),
                divider = i > 0,
                onClick = { open = !open },
                trailing = { KvSmallButton("Записать", { onConfirm(meal.id) }, soft = true) },
                below = {
                    if (open) {
                        MealDetails(app, meal)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                            KvSmallButton("Заново", { onReparse(meal.id) }, icon = Glyphs.Refresh)
                            KvSmallButton("Поправить", { onEdit(meal.id) }, icon = Glyphs.Edit)
                            KvSmallButton("Убрать", { onDelete(meal.id) }, icon = Glyphs.Delete)
                        }
                    }
                },
            )
        }
        if (meals.isEmpty()) {
            if (pending.isEmpty()) KvNote("За этот день ничего не записано — скажи или сними тарелку внизу.")
            return@PaperCard
        }
        meals.forEachIndexed { i, meal ->
            var open by remember(meal.id) { mutableStateOf(false) }
            val kind = meal.kind.ifBlank { "приём" }
            KvListRow(
                title = if (wide) kind.replaceFirstChar { it.uppercase() } else mealList(meal),
                titleWeight = if (wide) FontWeight.SemiBold else FontWeight.Normal,
                sub = if (wide) null else "$kind · " + macros(meal.protein, meal.fat, meal.carbs),
                lead = mealTimeFormat.format(Date(meal.ts)),
                value = "${Fmt.num(meal.kcal)} ккал",
                valueSmall = true,
                titleLines = if (open) 6 else 3,
                divider = i > 0 || pending.isNotEmpty(),
                onClick = { open = !open },
                below = {
                    if (wide) {
                        Text(mealList(meal), style = LocalPravkaType.current.body.copy(fontSize = 13.sp, lineHeight = 18.sp), color = Kv.muted, modifier = Modifier.padding(top = 3.dp))
                        Text(macros(meal.protein, meal.fat, meal.carbs), style = LocalPravkaType.current.meta, color = Kv.faint, modifier = Modifier.padding(top = 2.dp))
                    }
                    if (open) {
                        MealDetails(app, meal)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                            // «Из дня» — не удаление: разбор остаётся ждать, его можно записать снова.
                            KvSmallButton("Из дня", { onUnconfirm(meal.id) }, icon = Glyphs.Undo)
                            KvSmallButton("Поправить", { onEdit(meal.id) }, icon = Glyphs.Edit)
                            KvSmallButton("Убрать", { onDelete(meal.id) }, icon = Glyphs.Delete)
                        }
                    }
                },
            )
        }
    }
}

/**
 * Цели (холст: «Калории», «Белок · жиры · углеводы», «Вес» строками `.set`).
 * Числа — из настроек Еды и Спорта; правятся шестерёнкой в шапке.
 */
@Composable
internal fun FoodGoalsCardV2(kcal: Int, protein: Int, fat: Int, carbs: Int, goalWeight: Int, weightNow: Double?) {
    PaperCard(label = "Цели", note = "из настроек Еды и Спорта") {
        KvSetRow("Калории", value = if (kcal > 0) "${Fmt.num(kcal)} ккал" else "не задано", divider = false, chevron = false)
        KvSetRow("Белок · жиры · углеводы", value = if (protein + fat + carbs > 0) "$protein · $fat · $carbs г" else "не задано", chevron = false)
        if (goalWeight > 0 || weightNow != null) {
            KvSetRow(
                "Вес",
                value = listOfNotNull(
                    goalWeight.takeIf { it > 0 }?.let { "цель $it" },
                    weightNow?.let { "сейчас ${ru1(it)} кг" },
                ).joinToString(" · "),
                chevron = false,
            )
        }
    }
}

/**
 * Неделя (столбики `.cols-ch`: калории по дням, сегодня — акцентом, пунктир —
 * цель). Тап по столбику — дневник того дня.
 */
@Composable
internal fun FoodWeekCardV2(week: List<FoodStore.DayTotal>, targetKcal: Int, selected: String, onDay: (String) -> Unit) {
    val withData = week.filter { it.kcal > 0 }
    val todayKey = week.lastOrNull()?.date
    PaperCard(
        label = "Неделя",
        note = if (withData.isEmpty()) null else "в среднем ${Fmt.num(withData.sumOf { it.kcal } / withData.size)} ккал",
    ) {
        KvColumns(
            week.map { d ->
                val ld = runCatching { LocalDate.parse(d.date) }.getOrNull()
                KvColumn(d.kcal.toFloat(), ld?.let { Fmt.wd(it) }.orEmpty(), accent = d.date == todayKey || d.date == selected)
            },
            height = 96.dp,
            target = targetKcal.takeIf { it > 0 }?.toFloat(),
            onClick = { i -> onDay(week[i].date) },
        )
        if (targetKcal > 0) {
            KvGap()
            KvNote("пунктир — цель ${Fmt.num(targetKcal)} ккал")
        }
    }
}

/** Неделя строками (разворот, «Неделя»): день, калории, БЖУ; тап — дневник того дня. */
@Composable
internal fun FoodDaysCardV2(week: List<FoodStore.DayTotal>, selected: String, onDay: (String) -> Unit) {
    PaperCard(label = "По дням") {
        if (week.isEmpty()) {
            KvNote("Записей за неделю нет.")
            return@PaperCard
        }
        week.forEachIndexed { i, d ->
            val ld = runCatching { LocalDate.parse(d.date) }.getOrNull()
            KvListRow(
                title = if (d.kcal > 0) "${Fmt.num(d.kcal)} ккал" else "ничего не записано",
                titleColor = if (d.kcal > 0) Kv.text else Kv.faint,
                sub = if (d.kcal > 0) macros(d.protein, d.fat, d.carbs) + " · " + plural(d.meals, "приём", "приёма", "приёмов") else null,
                lead = ld?.let { Fmt.wd(it) + " " + it.dayOfMonth } ?: d.date,
                selected = d.date == selected,
                divider = i > 0,
                onClick = { onDay(d.date) },
            )
        }
    }
}

/**
 * Отрезки «Еды», про которые сказано «не надо» (подсказка «в ленте едим, а в
 * дневнике пусто»): одни на «Сегодня» и вкладку Еды, живут до перезапуска
 * приложения — подсказка про старый обед после него уже не встанет сама
 * (`MealGap.RECENT_MS`).
 */
internal object MealGapDismissed {
    val ids = androidx.compose.runtime.mutableStateListOf<Long>()
}

/**
 * Подсказка `MealGap` (доводка 5.0, 14.10.2026): «Сейчас в ленте «Обед» (с 13:10),
 * а в дневнике еды пусто. Записать?» — голосом (тот же тап, что у кнопки еды),
 * снимком (камера вкладки Еды) и «не надо». Ни Claude, ни записи сама она не делает.
 */
@Composable
internal fun MealGapCallout(gap: ru.zf.pravka.core.MealGap.Gap, onVoice: () -> Unit, onPhoto: () -> Unit) {
    ru.zf.pravka.ui.KvCallout(
        ru.zf.pravka.core.MealGap.words(gap),
        icon = ru.zf.pravka.ui.KvGlyphs.byKey("food") ?: Glyphs.Food,
        buttons = {
            KvSmallButton("Голосом", onVoice, soft = true, icon = Glyphs.Mic)
            KvSmallButton("Снять", onPhoto, icon = Glyphs.Camera)
            KvSmallButton("Не надо", { MealGapDismissed.ids.add(gap.entryId) }, icon = Glyphs.Close)
        },
    )
}

/** Голосом — та же дорога, что тап по кнопке еды (`onFoodTap`); службы нет — словами. */
internal fun mealVoice(context: android.content.Context) {
    val service = ru.zf.pravka.trigger.PravkaAccessibilityService.instance
    if (service == null) ru.zf.pravka.ui.Feedback.toast(context, context.getString(R.string.toast_no_service))
    else with(service) { onFoodTap() }
}
