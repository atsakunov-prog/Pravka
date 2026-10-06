package ru.zf.pravka.core

import java.time.LocalDate
import org.json.JSONObject

// Наташа — партнёр ЗФ, не на зарплате (владелец, 05.10.2026). Долг ей
// зафиксирован с ней на этот день («Долг Наташе мы зафиксировали сейчас как
// … Дальше уже считаем с ней 30 %»), поэтому счёт в два куска:
//  - до [START] — прежнее правило, история не пересчитывается: начисления
//    «Долг: начислено» (записи в `money_manual.txt`) минус переводы ей с
//    личных карт («Доля Наташи (ЗФ)»). Платежи ей со счёта ЗФ до этого дня
//    в долг не идут: тогда они были расходом ЗФ. Зафиксированная сумма
//    получается из журнала сама — константы в коде нет;
//  - с [START] — 30 % каждой операции прибыли ЗФ минус всё, что ей выплачено
//    («ЗФ: выплата доли Наташе» со счёта ЗФ и «Доля Наташи (ЗФ)» с личных).
//
// Прибыль ЗФ — выручка минус расходы ЗФ ПО НАЗНАЧЕНИЮ: категории полки ЗФ
// с любого счёта (юрист с личной карты — тоже расход ЗФ), плюс не
// разложенное на счёте ЗФ — как у сервера: деньги ЗФ не пропадают, пока
// строка ждёт ответа.
//
// Точный долг считает сервис «Деньги» на домашнем компе (`books.py`,
// `_natasha_flow`); на телефоне — та же формула для вкладки. С 06.10.2026
// правила партнёрства — не копия в коде, а запись Свода `money.partner`
// (её пишут «Деньги» из своих «Правил», docs/svod-phone.md, часть 2):
// процент, день и сумма фиксации, «партнёр с», зарплата семьи — расход ли.
// Константы ниже — только запас, пока Свода нет. Файл без Android:
// проверяют JVM-тесты.
object ZfPartner {

    /**
     * Правила партнёрства. [fixedKop] — долг на день фиксации, сказанный
     * «Деньгами»; null — сумма складывается из журнала прежним правилом
     * (начисления минус переводы до дня фиксации). [shareFrom] — с какого
     * дня партнёр делит прибыль (раньше дня фиксации — значит, с фиксации:
     * до неё всё уже в зафиксированной сумме). [familyPatterns] — описания
     * выплат семье со счёта ЗФ (зарплата папы); [familyIsCost] — считать ли
     * их и «ЗФ: выплату владельцу» расходом ЗФ.
     */
    data class Rules(
        val pct: Long = 30L,
        val start: LocalDate = LocalDate.of(2026, 10, 5),
        val fixedKop: Long? = null,
        val shareFrom: LocalDate? = null,
        val familyIsCost: Boolean = false,
        val familyPatterns: List<String> = emptyList(),
        /** Откуда правила: «Деньги» (Свод) или запас сборки. */
        val fromSvod: Boolean = false,
    ) {
        private val familyRe: List<Regex> = familyPatterns.mapNotNull { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
        val startTs: Long get() = MoneyStats.startOf(start)
        val shareTs: Long get() = MoneyStats.startOf(listOfNotNull(start, shareFrom).max())
        fun familyPay(e: MoneyEntry): Boolean = e.category == "zf_owner" || familyRe.any { it.containsMatchIn(e.what) }
    }

    /** Запас без Свода — правила, как их сказал владелец 05.10.2026. */
    val FACTORY = Rules()

    /** `money.partner` из Свода → правила; непонятное поле — заводское значение этого поля. */
    fun parse(o: JSONObject?): Rules? {
        o ?: return null
        fun day(key: String): LocalDate? = o.optString(key).takeIf { it.isNotBlank() && it != "null" }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
        val pats = o.optJSONArray("family_pay_patterns")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } }.orEmpty()
        return Rules(
            pct = if (o.has("pct") && !o.isNull("pct")) o.optDouble("pct", FACTORY.pct.toDouble()).toLong() else FACTORY.pct,
            start = day("fixed_on") ?: FACTORY.start,
            fixedKop = if (o.has("fixed_kop") && !o.isNull("fixed_kop")) o.optLong("fixed_kop") else null,
            shareFrom = day("share_from"),
            familyIsCost = if (o.has("family_pay_is_cost")) o.optBoolean("family_pay_is_cost") else FACTORY.familyIsCost,
            familyPatterns = pats,
            fromSvod = true,
        )
    }

    @Volatile private var memo: Pair<Any?, Rules>? = null

    /** Действующие правила: Свод, без него — запас. */
    fun rules(): Rules {
        val e = Svod.current[Svod.MONEY_PARTNER]
        memo?.takeIf { it.first === e }?.let { return it.second }
        val r = parse(e?.json() as? JSONObject) ?: FACTORY
        memo = e to r
        return r
    }

    // ---- Счёт ----

    val START: LocalDate get() = rules().start
    /** Зарплата семьи из ЗФ — расход ЗФ? Решают «Деньги» (`family_pay_is_cost`). */
    val FAMILY_SALARY_IS_EXPENSE: Boolean get() = rules().familyIsCost
    val startTs: Long get() = rules().startTs

    /** Выплата Наташе: со счёта ЗФ или с личной карты. */
    fun isPayout(e: MoneyEntry): Boolean = e.category == "zf_partner" || e.category == "zf_share"

    /** Входит ли запись в прибыль ЗФ: выручка (+) и расходы ЗФ по назначению (−). */
    fun inProfit(e: MoneyEntry, r: Rules = rules()): Boolean {
        // Выплаты семье со счёта ЗФ (зарплата владельца, Марианны, папы) —
        // расход ли, решают «Деньги»: с 05.10 зарплата папы — из доли
        // владельца, и долю партнёра она не уменьшает.
        if (MoneyMatch.zfSide(e) && r.familyPay(e)) return r.familyIsCost
        val c = MoneyCategories.of(e.category) ?: return MoneyMatch.zfSide(e)
        return c.shelf == MoneyCategories.Shelf.ZF
    }

    /** Доля от суммы в копейках, с округлением до копейки. */
    fun share(kop: Long, r: Rules = rules()): Long = Math.floorDiv(kop * r.pct + 50, 100)

    /**
     * Движения счёта «Долг Наташе» (минус — долг растёт), только то, что идёт
     * в счёт. Долг на день фиксации — сумма «Денег» одной строкой ([Rules.fixedKop]);
     * без неё — прежнее правило: начисления «Долг: начислено» как есть и
     * переводы ей с личных карт с обратным знаком. С дня фиксации — доля каждой
     * операции прибыли с обратным знаком (выручка растит долг, расход его
     * уменьшает; с [Rules.shareFrom], если он позже) и все выплаты ей с
     * обратным знаком (заплатил — долг меньше).
     */
    fun moves(entries: List<MoneyEntry>, r: Rules = rules()): List<MoneyEntry> {
        val from = r.startTs
        val shareFrom = r.shareTs
        val live = entries.filter { it.live() }
        val out = live.mapNotNull { e ->
            if (e.ts < from) {
                if (r.fixedKop != null) null
                else when (e.category) {
                    "owed" -> e.copy(id = e.id + "~доля")
                    "zf_share" -> e.copy(id = e.id + "~доля", rubKop = -e.rubKop)
                    else -> null
                }
            } else when {
                isPayout(e) -> e.copy(id = e.id + "~доля", rubKop = -e.rubKop)
                e.ts >= shareFrom && inProfit(e, r) -> e.copy(id = e.id + "~доля", rubKop = -share(e.rubKop, r))
                else -> null
            }
        }
        if (r.fixedKop == null || r.fixedKop == 0L) return out
        val fixed = MoneyEntry(
            id = "zf-partner-fixed-${r.start}", owner = "sasha", source = MoneyEntry.Source.MANUAL,
            ts = from, timeKnown = false, rubKop = -r.fixedKop, what = "Долг зафиксирован («Деньги»)",
            category = "owed",
        )
        return listOf(fixed) + out
    }

    /** Подпись под долгом во вкладке: по какому правилу он посчитан. */
    val HINT: String
        get() {
            val r = rules()
            val day = "%02d.%02d.%d".format(r.start.dayOfMonth, r.start.monthValue, r.start.year)
            val sum = r.fixedKop?.let { " — " + MoneyFormat.rub(it) } ?: ""
            return "зафиксирован на $day$sum, дальше ${r.pct} % прибыли ЗФ минус выплаты · " +
                if (r.fromSvod) "правила — из «Денег»" else "точный — в «Деньгах» на компе"
        }
}

/**
 * Числа «Денег» на сейчас (`money.summary` в Своде, docs/svod-phone.md, 2.2):
 * долг партнёру и чистые активы считает сервис на компе, и вкладка
 * показывает их как точные, с временем. Своё — только без записи или если
 * ей больше суток, словами «прикидка телефона».
 */
object MoneySummary {

    data class Summary(
        /** Долг партнёру, копейки, положительный — мы должны. */
        val debtKop: Long?,
        val networthKop: Long?,
        val at: String,
        val atMs: Long,
    )

    /** Старше суток — уже не «сейчас»: показываем своё. */
    const val FRESH_MS = 24 * 3_600_000L

    fun parse(o: JSONObject?): Summary? {
        o ?: return null
        val at = o.optString("at").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val ms = runCatching { java.time.OffsetDateTime.parse(at).toInstant().toEpochMilli() }.getOrNull() ?: return null
        val p = o.optJSONObject("partner")
        fun kop(src: JSONObject?, vararg keys: String): Long? {
            src ?: return null
            for (k in keys) if (src.has(k) && !src.isNull(k)) return src.optLong(k)
            return null
        }
        return Summary(kop(p, "debt_kop", "debt"), kop(o, "networth_kop"), at, ms)
    }

    fun current(): Summary? = parse(Svod.obj(Svod.MONEY_SUMMARY))

    fun fresh(s: Summary?, now: Long): Boolean = s != null && now - s.atMs in 0 until FRESH_MS

    /** «по „Деньгам“, 14:05» — время записи «Денег» по часам телефона. */
    fun label(s: Summary): String {
        val t = java.time.Instant.ofEpochMilli(s.atMs).atZone(java.time.ZoneId.systemDefault())
        return "по «Деньгам», %02d:%02d".format(t.hour, t.minute)
    }

    /** Свой долг (счёт «Долг партнёру», минус — должны) расходится с «Деньгами» больше чем на рубль. */
    fun diverges(ownAccountKop: Long, s: Summary): Boolean =
        s.debtKop != null && kotlin.math.abs(-ownAccountKop - s.debtKop) > 100
}
