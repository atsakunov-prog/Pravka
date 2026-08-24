package ru.zf.pravka.core

import ru.zf.pravka.data.DictionaryStore
import ru.zf.pravka.data.EventLog
import ru.zf.pravka.data.ExerciseBook
import ru.zf.pravka.data.FoodStore
import ru.zf.pravka.data.PlanStore
import ru.zf.pravka.data.RationBook
import ru.zf.pravka.data.Stats
import ru.zf.pravka.data.StrengthStore
import ru.zf.pravka.data.dayKey
import ru.zf.pravka.provider.ClaudeProvider

// Один микрофон на всё тело: подходы, зарядка, еда, самочувствие, вопрос.
//
// Смысл роутера — убрать вопрос «а куда это нажимать». Между подходами, с
// телефоном в потной руке, выбирать кнопку невозможно и не нужно: «гоблет
// четыре по десять шестнадцать» это очевидно силовая, «съел арбуз» — еда,
// «вис сорок секунд» — зарядка, «стоит ли сегодня бежать» — вопрос. Решает
// модель, одним вызовом вместе с разбором.
//
// Порядок операций здесь важнее их содержания:
//
//   1. СНАЧАЛА сказанное ложится на диск. До модели, до сети, до всего.
//      Текст незаменим, разбор — производная: промпт можно переписать и
//      разобрать заново, а услышанное второй раз не сказать.
//   2. Потом разбор, и его результат помечает надиктовку — но не заменяет её.
//   3. И только потом дороги наружу, каждая со своей очередью и своим
//      правом не сработать.
class BodyEngine(
    private val claude: ClaudeProvider,
    private val dictionary: DictionaryApplier,
    private val dictionaryStore: DictionaryStore,
    private val strengthStore: StrengthStore,
    private val strengthEngine: StrengthEngine,
    private val foodEngine: FoodEngine,
    private val book: ExerciseBook,
    private val ration: RationBook,
    private val planStore: PlanStore,
    private val stats: Stats,
    private val eventLog: EventLog,
) {

    /** Что вышло из одной фразы. Заполнено то, что относится к [kind]. */
    data class Outcome(
        val kind: String,
        val raw: StrengthStore.RawTake,
        val strength: StrengthEngine.Logged? = null,
        val gtg: StrengthStore.GtgDay? = null,
        val meal: FoodStore.Meal? = null,
        val feel: Int = 0,
        val knee: String = "",
        val question: String = "",
        val note: String = "",
        val costUsd: Double = 0.0,
    ) {
        /** Короткая строка для плашки и тоста: что именно записалось. */
        fun headline(): String = when {
            strength != null -> {
                val s = strength.session
                "Силовая: упражнений ${s.exercises.size}, подходов ${s.setCount}"
            }
            meal != null -> "Еда: ${meal.kcal} ккал · Б${meal.protein} Ж${meal.fat} У${meal.carbs}"
            gtg != null -> buildString {
                append("Зарядка")
                if (gtg.pullups > 0) append(" · ПОДТЯГИВАНИЯ ${gtg.pullups}!")
                if (gtg.hangSec > 0) append(" · вис ${gtg.hangSec} сек")
                if (gtg.negatives > 0) append(" · негативы ${gtg.negatives}")
                if (gtg.scapular > 0) append(" · лопаточные ${gtg.scapular}")
            }
            feel > 0 || knee.isNotBlank() -> buildString {
                if (feel > 0) append("Самочувствие $feel/5")
                if (knee.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append("колено ").append(knee)
                }
            }
            question.isNotBlank() -> "Вопрос: " + question.take(60)
            else -> "Не понял, что это"
        }
    }

    /**
     * Услышать фразу и сделать с ней всё, что следует.
     *
     * Возвращает failure только если разобрать не удалось — но и тогда
     * сказанное уже на диске, и его видно во вкладке как неразобранное. Это и
     * есть смысл первого шага: неудача модели не стоит владельцу его слов.
     */
    suspend fun hear(
        rawText: String,
        source: String = "voice",
        date: String = dayKey(System.currentTimeMillis()),
        /** Откуда фраза: «в карточке зарядки» и т.п. — смещает роутер модели. */
        whereSaid: String = "",
    ): Result<Outcome> {
        val text = rawText.trim()
        if (text.isBlank()) return Result.failure(IllegalArgumentException("Пустая фраза"))
        strengthStore.load()
        // Шаг первый и главный: на диск, до всего остального.
        val raw = strengthStore.addRaw(text, source)

        book.load()
        ration.load()
        planStore.load()
        val prepared = dictionary.prepare(text)
        val planned = planStore.mainOf(date)
        val block = planned?.block.orEmpty()

        val result = claude.parseBody(
            text = prepared.text,
            dictBlock = prepared.dictBlock,
            // Справочник блока, если день известен: тридцать строк вместо
            // сорока двух — и модель не путает гоблет с воздушным приседом,
            // которого в сегодняшнем блоке нет.
            exerciseBook = book.promptBlock(block.takeIf { it.isNotBlank() }).ifBlank {
                book.promptBlock()
            },
            rationBook = ration.promptBlock(),
            planBlock = planBlock(date),
            lastTimeBlock = strengthEngine.lastTimeBlock(block, date),
            whereSaid = whereSaid,
        )
        val parse = result.getOrElse { e ->
            strengthStore.markRaw(raw.id, "unknown", 0L, e.message.orEmpty())
            eventLog.add("тело: разбор не вышел — ${e.message}")
            return Result.failure(e)
        }
        runCatching { dictionaryStore.incrementHits(prepared.firedIds) }
        runCatching { stats.recordAux(parse.costUsd, parse.tokensIn, parse.tokensOut) }

        var outcome = Outcome(
            kind = parse.kind,
            raw = raw,
            question = parse.question,
            note = parse.note,
            costUsd = parse.costUsd,
        )

        // Подходы и еда могут приехать вместе — в один наговор влезает и то и
        // другое, и терять половину нельзя.
        parse.strength?.let { strength ->
            val logged = strengthEngine.record(strength, raw.id, date)
            if (logged != null) {
                // Общий комментарий фразы — в заметку сессии: он уедет в
                // intervals вместе с журналом («очень рад» тоже данные).
                if (parse.note.isNotBlank()) {
                    strengthStore.setFeel(logged.session.id, 0, 0, parse.note)
                }
                outcome = outcome.copy(strength = logged)
            }
        }
        parse.food?.let { food ->
            val meal = foodEngine.record(
                items = food.items,
                kind = food.kind,
                timeOfDay = food.timeOfDay,
                raw = text,
                note = food.note,
                source = if (source == "text") "text" else "voice",
                costUsd = 0.0,   // стоимость уже посчитана на разборе целиком
                model = parse.model,
            )
            outcome = outcome.copy(meal = meal.meal)
        }
        parse.gtg?.let { gtg ->
            val day = strengthStore.putGtg(
                date = date,
                charged = if (gtg.charged) true else null,
                hangSec = gtg.hangSec.takeIf { it > 0 },
                negatives = gtg.negatives.takeIf { it > 0 },
                scapular = gtg.scapular.takeIf { it > 0 },
                pullups = gtg.pullups.takeIf { it > 0 },
                note = parse.note.ifBlank { null },
            )
            outcome = outcome.copy(gtg = day)
        }
        parse.feel?.let { f ->
            if (f.knee.isNotBlank()) {
                strengthStore.putGtg(date = date, knee = f.knee, note = f.note)
            }
            if (f.feel > 0) {
                // Самочувствие цепляем к сегодняшней тренировке, если она есть:
                // оттуда оно уедет в intervals вместе с журналом. Сессия без
                // упражнений («сделано» кнопкой) — тоже тренировка: feel после
                // неё терять нельзя.
                val session = strengthStore.sessionsOn(date).firstOrNull { !it.empty }
                    ?: strengthStore.sessionsOn(date).firstOrNull()
                if (session != null) strengthStore.setFeel(session.id, f.feel, 0, f.note)
            }
            outcome = outcome.copy(feel = f.feel, knee = f.knee)
        }

        strengthStore.markRaw(
            raw.id,
            parse.kind,
            outcome.strength?.session?.id ?: outcome.meal?.id ?: 0L,
        )
        eventLog.add(
            "тело: «${text.take(60)}» → ${parse.kind}, " +
                String.format(java.util.Locale.US, "%.3f", parse.costUsd) + " USD"
        )
        return Result.success(outcome)
    }

    /** Переиграть сохранённую надиктовку: промпт поправлен или модель сменилась. */
    suspend fun rehear(rawId: Long): Result<Outcome> {
        strengthStore.load()
        val raw = strengthStore.rawById(rawId)
            ?: return Result.failure(IllegalStateException("Надиктовка не найдена"))
        return hear(raw.text, raw.source, dayKey(raw.ts))
    }

    /** «Зарядка сделана» одной кнопкой — без модели и без токенов. */
    suspend fun chargedToday(date: String = dayKey(System.currentTimeMillis())): StrengthStore.GtgDay {
        strengthStore.load()
        return strengthStore.putGtg(date = date, charged = true)
    }

    /**
     * Галочка одного упражнения зарядки в чек-листе дня. Когда отмечены ВСЕ
     * упражнения блока «Зарядка» — день закрывается сам: charged встаёт без
     * отдельной кнопки, цепочка растёт. Снял галочку — charged НЕ снимаем:
     * может, он отметил зарядку голосом, а галочки тыкал потом.
     */
    suspend fun toggleZaryadka(
        exerciseId: String,
        date: String = dayKey(System.currentTimeMillis()),
        /**
         * Список задач зарядки ЭТОГО дня — тот, что видит владелец. Он приходит
         * из события календаря и меняется неделя к неделе; считать «всё
         * сделано» по статическому блоку значило бы требовать упражнения,
         * которых сегодня в списке нет.
         */
        allIds: List<String> = emptyList(),
    ): StrengthStore.GtgDay {
        strengthStore.load()
        book.load()
        val day = strengthStore.toggleGtgItem(date, exerciseId)
        val all = allIds.ifEmpty { book.ofBlock("Зарядка").map { it.id } }
        if (!day.charged && all.isNotEmpty() && all.all { it in day.doneIds }) {
            return strengthStore.putGtg(date = date, charged = true)
        }
        return day
    }

    /** Промахнулся кнопкой — снять отметку. Числа турника при этом остаются. */
    suspend fun unchargeToday(date: String = dayKey(System.currentTimeMillis())): StrengthStore.GtgDay {
        strengthStore.load()
        return strengthStore.putGtg(date = date, charged = false)
    }

    suspend fun putGtgNumbers(
        date: String = dayKey(System.currentTimeMillis()),
        charged: Boolean? = null,
        hangSec: Int? = null,
        negatives: Int? = null,
        scapular: Int? = null,
        pullups: Int? = null,
        knee: String? = null,
    ): StrengthStore.GtgDay {
        strengthStore.load()
        return strengthStore.putGtg(
            date = date,
            charged = charged,
            hangSec = hangSec,
            negatives = negatives,
            scapular = scapular,
            pullups = pullups,
            knee = knee,
            // Руками — значит заменить: только так чинится ослышка «вис 400
            // секунд», иначе она травила бы лучший вис вечно. Голосовая дорога
            // (hear → putGtg) остаётся на максимуме дня.
            replace = true,
        )
    }

    /** План на сегодня словами — он же уезжает в промпт разбора. */
    private fun planBlock(date: String): String {
        val days = planStore.dayOf(date)
        if (days.isEmpty()) return ""
        return buildString {
            append("Сегодня по плану:\n")
            for (d in days) {
                append("- ").append(d.name)
                if (d.minutes > 0) append(", ").append(d.minutes).append(" мин")
                if (d.load > 0) append(", load ").append(d.load)
                append('\n')
                val planned = d.plannedLines()
                if (planned.isNotEmpty()) {
                    for (line in planned) append("  · ").append(line).append('\n')
                }
            }
        }
    }

    /** Голосовые имена упражнений и продуктов — смещение распознавателя. */
    fun biasing(): List<String> = (book.biasing() + ration.biasing()).distinct()
}
