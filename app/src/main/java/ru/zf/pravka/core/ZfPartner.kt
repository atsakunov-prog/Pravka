package ru.zf.pravka.core

import java.time.LocalDate

// Наташа — партнёр ЗФ, не на зарплате (владелец, 05.10.2026). Её доля —
// 30 % прибыли ЗФ с начала партнёрства; всё, что ей выплачено, долю гасит.
// Прежнее правило («Долг: начислено» = 30 % от выплаты ЗФ владельцу, записи
// в `money_manual.txt`) снято: новых начислений нет, две старые записи
// остаются в журнале как были, но в долг больше не идут.
//
// Прибыль ЗФ — выручка минус расходы ЗФ ПО НАЗНАЧЕНИЮ: категории полки ЗФ
// с любого счёта (юрист с личной карты — тоже расход ЗФ). Выплаты Наташе —
// «ЗФ: выплата доли Наташе» со счёта ЗФ и «Доля Наташи (ЗФ)» с личных карт.
//
// Точный долг считает сервис «Деньги» на домашнем компе; на телефоне — та же
// формула для вкладки. Файл без Android: проверяют JVM-тесты.
object ZfPartner {

    // ---- Правила партнёрства: ДОЛЖНЫ СОВПАДАТЬ с сервисом «Деньги» ----
    // Там их решает владелец («Деньги» → «Правила»), здесь — копия для
    // вкладки. Поменял там — поменяй здесь, иначе телефон и сервер покажут
    // разный долг.

    /** С какого дня считается прибыль для доли (на сервере: «с 01.01.2026»). */
    val START: LocalDate = LocalDate.of(2026, 1, 1)

    /**
     * Считать ли выплаты семье из ЗФ (зарплата Саши и Марианны — «ЗФ: выплата
     * владельцу» со стороны ЗФ) расходом ЗФ. На сервере: «зарплата семьи — не
     * расход».
     */
    const val FAMILY_SALARY_IS_EXPENSE = false

    /** Доля партнёра, процент прибыли. */
    const val PERCENT = 30L

    // ---- Счёт ----

    val startTs: Long get() = MoneyStats.startOf(START)

    /** Выплата Наташе: со счёта ЗФ или с личной карты. */
    fun isPayout(e: MoneyEntry): Boolean = e.category == "zf_partner" || e.category == "zf_share"

    /** Входит ли запись в прибыль ЗФ: выручка (+) и расходы ЗФ по назначению (−). */
    fun inProfit(e: MoneyEntry): Boolean {
        val c = MoneyCategories.of(e.category) ?: return false
        if (c.shelf == MoneyCategories.Shelf.ZF) return true
        return FAMILY_SALARY_IS_EXPENSE && e.category == "zf_owner" && MoneyMatch.zfSide(e)
    }

    /** Доля от суммы в копейках, с округлением до копейки. */
    fun share(kop: Long): Long = Math.floorDiv(kop * PERCENT + 50, 100)

    /**
     * Движения счёта «Долг Наташе»: доля каждой операции прибыли с обратным
     * знаком (выручка растит долг — минус, расход его уменьшает) и выплаты
     * ей с обратным знаком (заплатил — долг меньше). Только то, что идёт в
     * счёт, и только с [START].
     */
    fun moves(entries: List<MoneyEntry>): List<MoneyEntry> {
        val from = startTs
        return entries.filter { it.live() && it.ts >= from }.mapNotNull { e ->
            when {
                isPayout(e) -> e.copy(id = e.id + "~доля", rubKop = -e.rubKop)
                inProfit(e) -> e.copy(id = e.id + "~доля", rubKop = -share(e.rubKop))
                else -> null
            }
        }
    }

    /** Подпись под долгом во вкладке: по какому правилу он посчитан. */
    val HINT: String
        get() = "$PERCENT % прибыли ЗФ с ${START.dayOfMonth}.${"%02d".format(START.monthValue)}.${START.year} минус выплаты · точный — в «Деньгах» на компе"
}
