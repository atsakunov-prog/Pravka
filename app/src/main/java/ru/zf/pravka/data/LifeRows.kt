package ru.zf.pravka.data

import org.json.JSONObject
import ru.zf.pravka.core.NotionLifeSchema
import ru.zf.pravka.core.PhoneDaySummary

/**
 * Строки всей жизни по базам — одним сборщиком для Notion и для xlsx.
 *
 * Синхронизатор Notion (`NotionLifeSync.scan`) и выгрузка книгой Excel
 * (`LifeExport`) обязаны показывать одно и то же: владелец (09.09) — «один
 * экспорт, который будет экспортировать вот всё то, что в Notion». Пока
 * строки собирал сам синхронизатор, у выгрузки была бы своя копия тех же
 * фильтров (закрытые дела, подтверждённые приёмы, «сессия, в которой хоть
 * что-то есть», день формы не из будущего), и через месяц они бы разошлись.
 * Здесь читаются сторы и строятся строки построителями `NotionLifeSchema`;
 * кто и куда их отправит — не наше дело.
 *
 * Ключ строки — тот же служебный ключ, по которому синхронизатор узнаёт свою
 * страницу (`t42`, `f17`, `w…`, `s2026-09-05`, `g…`, `p…`, `h…`, `cat:…`).
 */
class LifeRows(
    private val zasechka: ZasechkaStore,
    private val food: FoodStore,
    private val sport: SportStore,
    private val strength: StrengthStore,
    private val phone: PhoneStore,
) {

    /** База и её строки: ключ синхронизатора → свойства строки в формате Notion. */
    class Table(val db: NotionLifeSchema.Db, val rows: List<Pair<String, JSONObject>>)

    /** Читает сторы и собирает строки всех восьми баз в порядке `NotionLifeSchema.ALL`. */
    suspend fun collect(now: Long = System.currentTimeMillis()): List<Table> {
        food.load(); sport.load(); strength.load()
        val today = NotionLifeSchema.dayKey(now)
        val all = zasechka.all()
        val categories = zasechka.categories()
        val worth = categories.associate { it.name.trim().lowercase() to it.value }
        fun worthOf(cat: String) = worth[cat.trim().lowercase()] ?: 0
        val out = ArrayList<Table>(NotionLifeSchema.ALL.size)

        // Лента — только закрытые дела: у идущего нет конца, минут и очков, а
        // полуфабрикат в таблице читался бы как факт.
        out += Table(
            NotionLifeSchema.ZASECHKA,
            all.filter { !it.open }.sortedBy { it.start }.map { e ->
                "t${e.id}" to NotionLifeSchema.ribbonRow(e, zasechka.budgetMinutes(e, now), worthOf(e.category), now)
            },
        )
        out += Table(
            NotionLifeSchema.EDA,
            food.mealsFlow.value.filter { it.confirmed }.map { m -> "f${m.id}" to NotionLifeSchema.mealRow(m) },
        )
        out += Table(
            NotionLifeSchema.TRENIROVKI,
            sport.workoutsFlow.value.map { w -> "w${w.id.ifBlank { w.start.toString() }}" to NotionLifeSchema.workoutRow(w) },
        )
        // Сессия с одними галочками чек-листа — тоже сессия: иначе «Силовые»
        // стоят пустыми при живом журнале (07.09).
        out += Table(
            NotionLifeSchema.SILOVYE,
            strength.sessionsFlow.value.filter { NotionLifeSchema.sessionMatters(it) }
                .map { s -> "s${s.date}" to NotionLifeSchema.sessionRow(s) },
        )
        out += Table(
            NotionLifeSchema.ZARYADKA,
            strength.gtgFlow.value.filter { it.any }.map { g -> "g${g.date}" to NotionLifeSchema.gtgRow(g) },
        )
        // Телефон по дням: trackedApps() заодно поднимает стор с диска.
        val tracked = phone.trackedApps()
        val labels = phone.labelsFlow.value
        out += Table(
            NotionLifeSchema.TELEFON,
            phone.daysFlow.value.mapNotNull { (date, day) ->
                NotionLifeSchema.phoneRow(date, PhoneDaySummary.forNotion(day, labels, tracked))?.let { "p$date" to it }
            },
        )
        // Форма: завтрашний прогноз CTL/ATL строкой не становится.
        out += Table(
            NotionLifeSchema.FORMA,
            sport.healthFlow.value.mapNotNull { h -> NotionLifeSchema.healthRow(h, today)?.let { "h${h.date}" to it } },
        )
        out += Table(
            NotionLifeSchema.KATEGORII,
            categories.mapIndexed { i, c -> "cat:" + c.name.trim().lowercase() to NotionLifeSchema.categoryRow(c, i + 1) },
        )
        return out
    }
}
