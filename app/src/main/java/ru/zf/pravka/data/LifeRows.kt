package ru.zf.pravka.data

import org.json.JSONObject
import ru.zf.pravka.core.LifeSchema
import ru.zf.pravka.core.PhoneDaySummary

/**
 * Строки всей жизни по листам книги Excel (`LifeExport`).
 *
 * Здесь читаются сторы и строятся строки построителями `LifeSchema`; сюда
 * же собраны фильтры (закрытые дела, подтверждённые приёмы, «сессия, в
 * которой хоть что-то есть», день формы не из будущего). До 12.10.2026 те же
 * строки уезжали и в Notion — сборщик был общим, чтобы синк и выгрузка не
 * разошлись; Notion убран, сборщик остался у книги.
 *
 * Ключ строки — служебный ключ (`t42`, `f17`, `v2026-09-13`, `w…`,
 * `s2026-09-05`, `g…`, `p…`, `h…`, `cat:…`, `n:vd`), он же колонка EntryId,
 * MealId или «Ключ» в книге.
 */
class LifeRows(
    private val zasechka: ZasechkaStore,
    private val food: FoodStore,
    private val sport: SportStore,
    private val strength: StrengthStore,
    private val phone: PhoneStore,
) {

    /** Лист и его строки: служебный ключ → значения строки по колонкам. */
    class Table(val db: LifeSchema.Db, val rows: List<Pair<String, JSONObject>>)

    /** Читает сторы и собирает строки всех баз в порядке `LifeSchema.ALL`. */
    suspend fun collect(now: Long = System.currentTimeMillis()): List<Table> {
        food.load(); sport.load(); strength.load()
        val today = LifeSchema.dayKey(now)
        val all = zasechka.all()
        val categories = zasechka.categories()
        val worth = categories.associate { it.name.trim().lowercase() to it.value }
        fun worthOf(cat: String) = worth[cat.trim().lowercase()] ?: 0
        val out = ArrayList<Table>(LifeSchema.ALL.size)

        // Лента — только закрытые дела: у идущего нет конца, минут и очков, а
        // полуфабрикат в таблице читался бы как факт.
        out += Table(
            LifeSchema.ZASECHKA,
            all.filter { !it.open }.sortedBy { it.start }.map { e ->
                "t${e.id}" to LifeSchema.ribbonRow(e, zasechka.budgetMinutes(e, now), worthOf(e.category), now)
            },
        )
        out += Table(
            LifeSchema.EDA,
            food.mealsFlow.value.filter { it.confirmed }.map { m -> "f${m.id}" to LifeSchema.mealRow(m) },
        )
        // Витамины по дням: считаются из тех же подтверждённых приёмов, что и
        // строки «Еды», — просто в другом разрезе. Дни берём только те, где
        // что-то посчиталось: пустая строка на каждый день года сказала бы
        // «витаминов не ел», а правда в том, что их не считали.
        out += Table(
            LifeSchema.VITAMINY,
            food.mealsFlow.value.filter { it.confirmed }
                .map { dayKey(it.ts) }
                .distinct()
                .sorted()
                .mapNotNull { date ->
                    LifeSchema.vitaminRow(food.dayTotal(date))?.let { "v$date" to it }
                },
        )
        out += Table(
            LifeSchema.TRENIROVKI,
            sport.workoutsFlow.value.map { w -> "w${w.id.ifBlank { w.start.toString() }}" to LifeSchema.workoutRow(w) },
        )
        // Сессия с одними галочками чек-листа — тоже сессия: иначе «Силовые»
        // стоят пустыми при живом журнале (07.09).
        out += Table(
            LifeSchema.SILOVYE,
            strength.sessionsFlow.value.filter { LifeSchema.sessionMatters(it) }
                .map { s -> "s${s.date}" to LifeSchema.sessionRow(s) },
        )
        out += Table(
            LifeSchema.ZARYADKA,
            strength.gtgFlow.value.filter { it.any }.map { g -> "g${g.date}" to LifeSchema.gtgRow(g) },
        )
        // Телефон по дням: trackedApps() заодно поднимает стор с диска.
        val tracked = phone.trackedApps()
        val labels = phone.labelsFlow.value
        out += Table(
            LifeSchema.TELEFON,
            phone.daysFlow.value.mapNotNull { (date, day) ->
                LifeSchema.phoneRow(date, PhoneDaySummary.forBook(day, labels, tracked))?.let { "p$date" to it }
            },
        )
        // Форма: завтрашний прогноз CTL/ATL строкой не становится.
        out += Table(
            LifeSchema.FORMA,
            sport.healthFlow.value.mapNotNull { h -> LifeSchema.healthRow(h, today)?.let { "h${h.date}" to it } },
        )
        out += Table(
            LifeSchema.KATEGORII,
            categories.mapIndexed { i, c -> "cat:" + c.name.trim().lowercase() to LifeSchema.categoryRow(c, i + 1) },
        )
        // Справочник веществ: норма, предел и «зачем» рядом с колонками
        // «Витаминов». Он статический — но без него книга Excel показывает
        // двадцать столбцов цифр, о которых неоткуда узнать, много это или мало.
        out += Table(
            LifeSchema.NORMY,
            ru.zf.pravka.core.Micronutrients.ALL.mapIndexed { i, n ->
                "n:" + n.id to LifeSchema.normRow(n, i + 1)
            },
        )
        return out
    }
}
