package ru.zf.pravka.core

import java.io.File
import ru.zf.pravka.data.DictionaryStore
import ru.zf.pravka.data.EventLog
import ru.zf.pravka.data.FoodStore
import ru.zf.pravka.data.IcuSportSync
import ru.zf.pravka.data.OpenFoodFacts
import ru.zf.pravka.data.Settings
import ru.zf.pravka.data.SportStore
import ru.zf.pravka.data.Stats
import ru.zf.pravka.data.ZasechkaStore
import ru.zf.pravka.data.dayKey
import ru.zf.pravka.provider.ClaudeProvider

// Еда: сказанное → КБЖУ. Четвёртый движок рядом с ProofreadEngine (текст в
// поле), ZasechkaEngine (время в ленте) и RaznoskaEngine (дела в Todoist).
//
// Устроен как Разноска, и это сознательно: РАЗБОР (Сонет превращает «омлет из
// трёх яиц» в позиции с граммами и КБЖУ) и ПОДТВЕРЖДЕНИЕ (приём становится
// частью дня) - два раздельных шага, между ними стоит владелец и смотрит на
// плашку. Разобранное лежит на диске с первой секунды: модель отвечала не
// бесплатно, и потерять её ответ из-за упавшего приложения нельзя.
//
// Подтверждение тянет за собой две дороги наружу, обе необязательные:
//   - примечание к записи «Еда» в ленте Засечки (лента своих записей от еды
//     НЕ отращивает - её инварианты не наше дело);
//   - итог дня в wellness intervals.icu, где эти поля пустуют.
// Обе могут не сработать (нет сети, нет ключа) - дневник от этого не страдает,
// отметки «уехало» остаются снятыми, и следующее подтверждение донесёт.
class FoodEngine(
    private val claude: ClaudeProvider,
    private val dictionary: DictionaryApplier,
    private val dictionaryStore: DictionaryStore,
    private val store: FoodStore,
    private val sportStore: SportStore,
    private val icu: IcuSportSync,
    private val zasechkaStore: ZasechkaStore,
    private val offf: OpenFoodFacts,
    private val settings: Settings,
    private val stats: Stats,
    private val eventLog: EventLog,
) {

    companion object {
        // Насколько далеко от приёма пищи искать запись «Еда» в ленте: обед
        // записан в 13:10, а сказано про него в 13:40 - это тот же обед.
        private const val RIBBON_WINDOW_MS = 2 * 3_600_000L
        // Снимок к API едет сжатым: 1568 px по длинной стороне - предел, дальше
        // Anthropic всё равно уменьшает сам, а мы бы платили трафиком.
        const val PHOTO_MAX_SIDE = 1568
        // Как часто фоновый тик службы имеет право стучаться в intervals.icu с
        // недоставленными днями. Без этого один упорно отвергаемый день
        // (сменился ключ, поменялось поле в их API) означал бы PUT каждые пять
        // минут в чужой сервис - и так до конца времён.
        private const val SYNC_RETRY_MS = 30 * 60_000L
        // Сколько дней доносим за один заход: обычно их один-два, а очередь в
        // сотню дней (первый запуск после долгого перерыва) не должна
        // превращаться в сотню запросов подряд.
        private const val SYNC_BATCH = 5
    }

    @Volatile private var lastSyncSweep = 0L

    /** Что делать с разбором: показать плашку по этому приёму. */
    data class Parsed(val meal: FoodStore.Meal, val note: String)

    // ---- Разбор ----

    /**
     * Сказанное (или набранное) → приём пищи на диске, ещё не подтверждённый.
     *
     * [photo] - снимок тарелки: он копируется в своё место в filesDir и уезжает
     * модели вместе со словами. Слова при этом можно не говорить вовсе.
     */
    suspend fun parse(
        rawText: String,
        photo: File? = null,
        source: String = "voice",
    ): Result<Parsed> {
        val text = rawText.trim()
        if (text.isBlank() && photo == null) {
            return Result.failure(IllegalArgumentException("Ни слов, ни снимка — разбирать нечего"))
        }
        store.load()
        // Словарь чинит услышанные названия ДО модели (HARD) и подсказывает
        // остальное блоком {DICT} — те же правила, что у Правки и Разноски.
        val prepared = dictionary.prepare(text)
        val image = photo?.let { PhotoBytes.forApi(it) }
        val result = claude.parseFood(
            text = prepared.text,
            dictBlock = prepared.dictBlock,
            profileLine = profileLine(),
            image = image,
        )
        val parse = result.getOrElse { e ->
            eventLog.add("еда: разбор не вышел — ${e.message}")
            return Result.failure(e)
        }
        runCatching { dictionaryStore.incrementHits(prepared.firedIds) }
        runCatching { stats.recordAux(parse.costUsd, parse.tokensIn, parse.tokensOut) }
        if (parse.items.isEmpty()) {
            eventLog.add("еда: в сказанном еды не нашлось" + noteTail(parse.note))
            return Result.failure(
                IllegalStateException(parse.note.ifBlank { "Еды в сказанном не нашлось" })
            )
        }
        // Снимок кладём рядом с дневником ТОЛЬКО когда разбор удался: иначе в
        // filesDir копились бы кадры от неудачных попыток.
        val photoName = photo?.let { savePhoto(it) }.orEmpty()
        val meal = store.add(
            ts = mealTimeOf(parse.timeOfDay),
            kind = parse.kind.ifBlank { MealItem.kindByHour(hourNow()) },
            raw = text,
            items = parse.items,
            note = parse.note,
            source = source,
            photo = photoName,
            costUsd = parse.costUsd,
            model = parse.model,
        )
        eventLog.add(
            "еда: «${text.take(60)}» → позиций ${meal.items.size}, ${meal.kcal} ккал, " +
                String.format(java.util.Locale.US, "%.3f", parse.costUsd) + " USD"
        )
        return Result.success(Parsed(meal, parse.note))
    }

    /**
     * Штрихкод: КБЖУ берётся из Open Food Facts, а не у модели — на упаковке
     * он написан, и такой позиции можно верить. Токенов это не стоит вообще.
     *
     * [grams] = 0 значит «порция с упаковки, а если её нет — сто граммов»:
     * дальше владелец правит вес руками, и позиция пересчитывается сама.
     */
    suspend fun parseBarcode(barcode: String, grams: Int = 0): Result<Parsed> {
        store.load()
        val product = offf.lookup(barcode).getOrElse { e ->
            eventLog.add("еда: штрихкод $barcode — ${e.message}")
            return Result.failure(e)
        }
        val weight = when {
            grams > 0 -> grams
            product.servingGrams > 0 -> product.servingGrams
            else -> 100
        }
        val meal = store.add(
            ts = System.currentTimeMillis(),
            kind = MealItem.kindByHour(hourNow()),
            raw = "штрихкод ${product.code}: ${product.title}",
            items = listOf(product.item(weight)),
            note = if (grams > 0 || product.servingGrams > 0) ""
            else "Порция на упаковке не указана — поставил 100 г, поправь вес",
            source = "barcode",
            photo = "",
            costUsd = 0.0,
            model = "openfoodfacts",
        )
        eventLog.add("еда: штрихкод ${product.code} → ${product.title}, ${meal.kcal} ккал")
        return Result.success(Parsed(meal, meal.note))
    }

    /** Тот же текст — заново на разбор (модель ошиблась, промпт поправлен). */
    suspend fun reparse(mealId: Long): Result<Parsed> {
        val meal = store.byId(mealId)
            ?: return Result.failure(IllegalStateException("Приём не найден"))
        if (meal.raw.isBlank() && meal.photo.isBlank()) {
            return Result.failure(IllegalStateException("Ни слов, ни снимка — разбирать нечего"))
        }
        val fresh = parse(
            rawText = meal.raw,
            photo = store.photoFile(meal.photo),
            source = meal.source,
        ).getOrElse { return Result.failure(it) }
        // Старый разбор уходит только когда новый уже на диске.
        if (!meal.confirmed) store.delete(meal.id)
        return Result.success(fresh)
    }

    // ---- Подтверждение и дороги наружу ----

    data class ConfirmOutcome(
        val meal: FoodStore.Meal?,
        val ribbon: String,   // «» = не приписывали, иначе название записи
        val icuError: String,
    )

    /** «ОК» на плашке: приём в дневник, а оттуда — в ленту и в intervals.icu. */
    suspend fun confirm(mealId: Long): ConfirmOutcome {
        val meal = store.confirm(mealId) ?: return ConfirmOutcome(null, "", "")
        val ribbon = runCatching { annotateRibbon(meal) }.getOrDefault("")
        val icuError = runCatching { syncDay(dayKey(meal.ts)) }.getOrElse {
            it.message ?: "не вышло"
        }
        eventLog.add(
            "еда: «${meal.shortList.take(60)}» ${meal.kcal} ккал в дневник" +
                (if (ribbon.isNotBlank()) ", приписано к «$ribbon»" else "") +
                (if (icuError.isNotBlank()) ", intervals.icu: $icuError" else "")
        )
        return ConfirmOutcome(meal, ribbon, icuError)
    }

    /**
     * «↩︎» на записке: приём выходит из дня, но разбор остаётся ждать на
     * плашке — удалять его вместе с решением «пока не считать» было бы
     * расточительно, модель за него платила.
     *
     * Приписка в ленте снимается тоже: строка про КБЖУ относилась к приёму,
     * которого в дне больше нет. Убираем РОВНО свою строку, всё остальное в
     * `raw` — сказанное владельцем, и оно остаётся.
     */
    suspend fun unconfirm(mealId: Long): FoodStore.Meal? {
        val before = store.byId(mealId) ?: return null
        val line = ribbonLine(before)
        val meal = store.unconfirm(mealId) ?: return null
        runCatching { stripRibbonLine(before, line) }
        // Сумма дня стала меньше — доносим её, иначе в intervals.icu останется
        // прежняя (пуш перетирающий, а не складывающий).
        runCatching { syncDay(dayKey(meal.ts)) }
        eventLog.add("еда: «${meal.shortList.take(60)}» убран из дня, разбор ждёт")
        return meal
    }

    private suspend fun stripRibbonLine(meal: FoodStore.Meal, line: String) {
        if (!meal.ribbonSynced || line.isBlank()) return
        val from = meal.ts - RIBBON_WINDOW_MS
        val to = meal.ts + RIBBON_WINDOW_MS
        val target = zasechkaStore.forRange(from, to)
            .filter { it.raw.contains(line) }
            .minByOrNull { kotlin.math.abs(it.start - meal.ts) } ?: return
        val kept = target.raw.lines().filterNot { it.trim() == line }.joinToString("\n").trim()
        zasechkaStore.annotate(target.id, kept)
    }

    /**
     * Итог дня уезжает в wellness. Именно ИТОГ: каждый пуш перетирает
     * предыдущий, поэтому суммируем все подтверждённые приёмы дня заново.
     * Пустая строка в ответе = всё хорошо (или отправлять было нечего).
     */
    suspend fun syncDay(date: String): String {
        if (!settings.foodToIcu()) return ""
        val total = store.dayTotal(date)
        if (total.empty) return ""
        val outcome = icu.pushNutrition(
            date = date,
            kcal = total.kcal,
            protein = total.protein,
            fat = total.fat,
            carbs = total.carbs,
        )
        return outcome.fold(
            onSuccess = {
                store.markIcuSynced(store.mealIdsOn(date))
                ""
            },
            onFailure = { it.message ?: "не вышло" },
        )
    }

    /**
     * Всё, что ещё не уехало в intervals.icu, — одним заходом. Зовётся из
     * фонового тика службы, поэтому сама себя дросселирует: [force] для
     * кнопки «донести» в настройках, где владелец ждёт ответа сейчас.
     */
    suspend fun syncPending(force: Boolean = false): Int {
        if (!settings.foodToIcu()) return 0
        val now = System.currentTimeMillis()
        if (!force && now - lastSyncSweep < SYNC_RETRY_MS) return 0
        lastSyncSweep = now
        store.load()
        var done = 0
        var failed = ""
        for (date in store.daysNeedingIcu().take(SYNC_BATCH)) {
            val error = syncDay(date)
            if (error.isBlank()) done++ else if (failed.isBlank()) failed = error
        }
        // Про неудачу говорим один раз за проход, а не по разу на день:
        // причина у них всегда одна и та же.
        if (failed.isNotBlank()) eventLog.add("еда: в intervals.icu не уехало — $failed")
        return done
    }

    /**
     * Приписка к ленте. Ищем БЛИЖАЙШУЮ запись «Еда»/«Быт»-подобной категории
     * вокруг времени приёма и дописываем к ней съеденное. Записи нет - ничего
     * не делаем и говорим об этом наружу: пусть владелец решит, заводить ли
     * засечку. Лента еду сама не отращивает.
     */
    private suspend fun annotateRibbon(meal: FoodStore.Meal): String {
        if (!settings.foodToRibbon()) return ""
        val from = meal.ts - RIBBON_WINDOW_MS
        val to = meal.ts + RIBBON_WINDOW_MS
        val candidates = zasechkaStore.forRange(from, to)
            .filter { it.category.trim().equals("Еда", ignoreCase = true) }
        if (candidates.isEmpty()) return ""
        // Ближайшая по началу к моменту приёма: за два часа их может быть две.
        val target = candidates.minByOrNull { kotlin.math.abs(it.start - meal.ts) } ?: return ""
        val line = ribbonLine(meal)
        // Дописываем, а не затираем: там уже может стоять сказанное владельцем
        // («обедаю с Марианной»), и это ценнее нашей арифметики.
        val existing = target.raw.trim()
        if (existing.contains(line)) return target.title
        val merged = if (existing.isBlank()) line else existing + "\n" + line
        return if (zasechkaStore.annotate(target.id, merged)) {
            store.markRibbonSynced(meal.id)
            target.title
        } else ""
    }

    /** «КБЖУ: 620 ккал · Б42 Ж28 У45 — омлет, тост, кофе». */
    private fun ribbonLine(meal: FoodStore.Meal): String =
        "КБЖУ: ${meal.kcal} ккал · Б${meal.protein} Ж${meal.fat} У${meal.carbs}" +
            (if (meal.shortList.isBlank()) "" else " — ${meal.shortList}")

    // ---- Правка руками ----

    suspend fun replaceItems(mealId: Long, items: List<MealItem>) {
        store.replaceItems(mealId, items.filter { it.name.isNotBlank() })
        // Приём уже был в дне - значит и в intervals.icu надо донести новую сумму.
        val meal = store.byId(mealId) ?: return
        if (meal.confirmed) runCatching { syncDay(dayKey(meal.ts)) }
    }

    /** Поправить вес позиции: КБЖУ пересчитывается пропорционально. */
    suspend fun rescaleItem(mealId: Long, index: Int, grams: Int) {
        val meal = store.byId(mealId) ?: return
        if (index !in meal.items.indices) return
        val items = meal.items.toMutableList()
        items[index] = items[index].scaledTo(grams)
        replaceItems(mealId, items)
    }

    suspend fun dropItem(mealId: Long, index: Int) {
        val meal = store.byId(mealId) ?: return
        if (index !in meal.items.indices) return
        val items = meal.items.toMutableList().also { it.removeAt(index) }
        if (items.isEmpty()) {
            // Последнюю позицию убрали — приёма больше нет.
            delete(mealId)
            return
        }
        replaceItems(mealId, items)
    }

    suspend fun delete(mealId: Long) {
        val meal = store.byId(mealId)
        store.delete(mealId)
        // Сумма дня изменилась: доносим её, иначе в intervals.icu останется
        // старая (пуш перетирающий, а не складывающий).
        if (meal != null && meal.confirmed) runCatching { syncDay(dayKey(meal.ts)) }
    }

    suspend fun setKind(mealId: Long, kind: String) = store.setKind(mealId, kind)

    suspend fun setTime(mealId: Long, ts: Long) {
        val before = store.byId(mealId) ?: return
        store.setTime(mealId, ts)
        // Приём переехал на другой день - оба дня надо пересчитать.
        val wasDay = dayKey(before.ts)
        val nowDay = dayKey(ts)
        if (before.confirmed) {
            runCatching { syncDay(nowDay) }
            if (wasDay != nowDay) runCatching { syncDay(wasDay) }
        }
    }

    // ---- Мелочи ----

    /** Строка про владельца для промпта: вес и то, чем он вообще занят. */
    private fun profileLine(): String {
        val weight = sportStore.lastWeight().takeIf { it > 0 }
            ?: sportStore.profileFlow.value.weightKg.takeIf { it > 0 }
        val parts = mutableListOf<String>()
        if (weight != null) parts.add("вес ${String.format(java.util.Locale.US, "%.0f", weight)} кг")
        val load = sportStore.weekLoad()
        if (load > 0) parts.add("тренировочная нагрузка за неделю $load")
        return parts.joinToString(", ")
    }

    private fun hourNow(): Int =
        java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)

    /**
     * «в час дня» из слов владельца → метка времени сегодняшнего дня. Час,
     * который ещё не наступил, считаем вчерашним: «ел в 23:30» сказанное в
     * 00:10 - это про вчера.
     */
    private fun mealTimeOf(clock: String): Long {
        val now = System.currentTimeMillis()
        if (clock.isBlank()) return now
        val parts = clock.split(':')
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return now
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: return now
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis > now + 60_000L) cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        return cal.timeInMillis
    }

    private fun noteTail(note: String) = if (note.isBlank()) "" else " ($note)"

    /** Снимок переезжает в filesDir/food под именем по времени приёма. */
    private fun savePhoto(source: File): String {
        val name = "eda-" + System.currentTimeMillis() + ".jpg"
        val target = File(store.photoDir(), name)
        return runCatching {
            PhotoBytes.writeShrunk(source, target)
            name
        }.getOrElse {
            eventLog.add("еда: снимок не сохранился — ${it.message}")
            ""
        }
    }
}
