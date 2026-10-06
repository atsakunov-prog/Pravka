package ru.zf.pravka.core

import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Деньги: числа и правила — у «Денег» (docs/svod-phone.md, часть 2).
 * `ZfPartner` берёт процент, день и сумму фиксации из `money.partner`;
 * константы — запас. Суммы выдуманные.
 */
class ZfPartnerSvodTest {

    private val msk = ZoneId.of("Europe/Moscow")
    private fun at(d: String, h: Int = 12): Long = LocalDateTime.parse(d + "T%02d:00".format(h)).atZone(msk).toInstant().toEpochMilli()
    private fun e(id: String, d: String, rub: Long, cat: String, src: MoneyEntry.Source = MoneyEntry.Source.TBIZ, what: String = id) =
        MoneyEntry(id = id, owner = "sasha", source = src, ts = at(d), rubKop = rub * 100, what = what, category = cat, account = "ЗФ")

    private fun contractPartner(): JSONObject {
        val f = listOf(File("../server/contract/svod.json"), File("server/contract/svod.json")).first { it.isFile }
        val ex = JSONObject(f.readText()).getJSONObject("svod").getJSONArray("sync_example")
        return (0 until ex.length()).map { ex.getJSONObject(it) }.first { it.getString("key") == Svod.MONEY_PARTNER }.getJSONObject("value")
    }

    @Test
    fun `правила из примера контракта`() {
        val r = ZfPartner.parse(contractPartner())!!
        assertEquals(30L, r.pct)
        assertEquals(LocalDate.of(2026, 10, 5), r.start)
        assertEquals(10_000_000L, r.fixedKop)
        assertEquals(LocalDate.of(2026, 9, 1), r.shareFrom)
        assertTrue(r.fromSvod)
        assertNull(ZfPartner.parse(null))
    }

    @Test
    fun `долг по money_partner — сумма фиксации одной строкой, дальше доля`() {
        val r = ZfPartner.parse(JSONObject().put("pct", 25).put("fixed_on", "2026-10-05").put("fixed_kop", 9_000_000).put("share_from", "2026-09-01"))!!
        val entries = listOf(
            // До фиксации журнал в счёт не идёт: сумма — от «Денег».
            e("old-owed", "2026-08-31", 100_000, "owed", MoneyEntry.Source.MANUAL),
            e("old-pay", "2026-09-20", -10_000, "zf_share", MoneyEntry.Source.TINKOFF),
            // После: выручка 40 000 → долг +10 000 (25 %), выплата 5 000 — гасит.
            e("rev", "2026-10-07", 40_000, "zf_revenue"),
            e("pay", "2026-10-08", -5_000, "zf_partner"),
        )
        val m = ZfPartner.moves(entries, r)
        assertEquals(listOf("zf-partner-fixed-2026-10-05", "rev~доля", "pay~доля"), m.map { it.id })
        assertEquals(-9_000_000L - 1_000_000L + 500_000L, m.sumOf { it.rubKop })
        assertEquals(at("2026-10-05", 0), m.first().ts)
    }

    @Test
    fun `партнёр с поздней даты — доля только с неё`() {
        val r = ZfPartner.parse(JSONObject().put("pct", 30).put("fixed_on", "2026-10-05").put("fixed_kop", 0).put("share_from", "2026-11-01"))!!
        val m = ZfPartner.moves(listOf(e("oct", "2026-10-10", 10_000, "zf_revenue"), e("nov", "2026-11-02", 10_000, "zf_revenue")), r)
        assertEquals(listOf("nov~доля"), m.map { it.id })
    }

    @Test
    fun `зарплата семьи — расход ли, решают «Деньги»`() {
        val salary = e("s", "2026-10-10", -50_000, "zf_owner")
        assertFalse(ZfPartner.inProfit(salary, ZfPartner.FACTORY))
        val cost = ZfPartner.parse(JSONObject().put("family_pay_is_cost", true))!!
        assertTrue(ZfPartner.inProfit(salary, cost))
        // Описание из family_pay_patterns — тоже выплата семье, хоть и разложено «командой».
        val papa = e("p", "2026-10-10", -30_000, "zf_team", what = "ПЕТРОВ ИВАН ИП")
        val rules = ZfPartner.parse(JSONObject().put("family_pay_patterns", org.json.JSONArray().put("петров\\s+иван")))!!
        assertTrue(ZfPartner.inProfit(papa, ZfPartner.FACTORY))
        assertFalse(ZfPartner.inProfit(papa, rules))
    }

    @Test
    fun `числа «Денег» — точные со временем, своё — при расхождении больше рубля`() {
        val s = MoneySummary.parse(
            JSONObject().put("partner", JSONObject().put("debt", 9_100_000).put("pct", 30))
                .put("networth_kop", 123_400_000).put("at", "2026-10-06T14:05:00+03:00").put("journal_at", "2026-10-06T14:00:00+03:00")
        )!!
        assertEquals(9_100_000L, s.debtKop)
        assertEquals(123_400_000L, s.networthKop)
        assertTrue(MoneySummary.fresh(s, s.atMs + 3_600_000L))
        assertFalse("старше суток — прикидка телефона", MoneySummary.fresh(s, s.atMs + 25 * 3_600_000L))
        assertFalse(MoneySummary.fresh(null, s.atMs))
        assertFalse(MoneySummary.diverges(-9_100_050L, s))
        assertTrue(MoneySummary.diverges(-9_000_000L, s))
        assertNull(MoneySummary.parse(JSONObject().put("partner", JSONObject())))
    }
}
