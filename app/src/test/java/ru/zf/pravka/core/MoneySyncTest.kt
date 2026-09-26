package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Общие Деньги двух телефонов (25.09.2026): журналы событий, сложенные в любом
// порядке, дают одно и то же; решение человека сильнее справочника; первое
// появление записи не перебивает правку; записи не удаляются.
class MoneySyncTest {

    private val day = 86_400_000L
    private val t0 = 1_790_000_000_000L

    private fun entry(id: String, owner: String = "sasha", what: String = "ВкусВилл", rub: Long = -38_000L) = MoneyEntry(
        id = id, owner = owner, source = MoneyEntry.Source.TINKOFF, ts = t0, rubKop = rub, what = what,
        account = "Black Premium *1519",
    )

    /** Телефон в тесте: его база и сложенный журнал. */
    private class Phone(val device: String, var local: MoneySync.Local) {
        val merged = MoneySync.Merged()
        val own = ArrayList<MoneySync.Event>()
    }

    private fun phone(device: String, entries: List<MoneyEntry>, rules: List<MoneyRules.Rule> = emptyList()) =
        Phone(device, MoneySync.Local(entries, rules, emptyList(), emptySet(), emptySet()))

    /** Один обмен тем же порядком, что у приложения: своё → в журнал, чужое → сложить, базу → к журналу. */
    private fun sync(p: Phone, drive: MutableList<MoneySync.Event>, now: Long) {
        val before = p.local.flat()
        val mine = MoneySync.diff(before, p.merged, now, p.device)
        p.merged.fold(mine)
        p.own.addAll(mine)
        drive.addAll(mine)
        p.merged.fold(drive.filter { it.d != p.device })
        p.local = MoneySync.apply(p.local, before, p.merged).local
    }

    @Test fun entryGoesThroughFieldsUnchanged() {
        val e = entry("t-1").copy(
            timeKnown = false, origMinor = -2_425L, currency = "EUR", rubBasis = MoneyEntry.RubBasis.CBR_PRELIM,
            note = "кофе", mcc = "5814", bankCategory = "Рестораны", category = "food_cafe", who = "wife",
            categoryBy = MoneyEntry.CategoryBy.OWNER, dropped = true, matchId = "v-1", takeId = 42L, replacedBy = "",
        )
        val base = MoneyEntry(id = "t-1", owner = "", source = MoneyEntry.Source.MANUAL, ts = 0L, rubKop = 0L, what = "")
        assertEquals(e, MoneySync.withFields(base, MoneySync.entryFields(e)))
        // Пустые поля — значения по умолчанию: рубль, время известно, валюта как сумма.
        val plain = entry("t-2")
        assertEquals(plain, MoneySync.withFields(base.copy(id = "t-2"), MoneySync.entryFields(plain).filterValues { it.isNotEmpty() }))
    }

    @Test fun origFollowsNewSumWhenSameAsRubles() {
        val e = entry("t-1")
        val next = MoneySync.withFields(e, mapOf("rub" to "-40000"))
        assertEquals(-40_000L, next.rubKop)
        assertEquals(-40_000L, next.origMinor)
    }

    @Test fun lastEditWinsInAnyFoldOrder() {
        val a = MoneySync.Event("e:x", t0 + 10, "sasha-aa", mapOf("note" to "Сашино"))
        val b = MoneySync.Event("e:x", t0 + 20, "marianna-bb", mapOf("note" to "Марианнино"))
        val m1 = MoneySync.Merged().apply { fold(listOf(a, b)) }
        val m2 = MoneySync.Merged().apply { fold(listOf(b, a, b)) }
        assertEquals("Марианнино", m1.values("e:x")["note"])
        assertEquals(m1.flat(), m2.flat())
    }

    @Test fun humanCategoryBeatsLaterRule() {
        val owner = MoneySync.cat("food_cafe", "", MoneyEntry.CategoryBy.OWNER)
        val rule = MoneySync.cat("food_shop", "", MoneyEntry.CategoryBy.RULE)
        val m = MoneySync.Merged()
        m.fold(listOf(MoneySync.Event("e:x", t0, "sasha-aa", mapOf(MoneySync.CAT to owner))))
        m.fold(listOf(MoneySync.Event("e:x", t0 + day, "marianna-bb", mapOf(MoneySync.CAT to rule))))
        assertEquals(owner, m.values("e:x")[MoneySync.CAT])
        // А последняя из двух человеческих — побеждает (владелец: «никто не главнее»).
        val hers = MoneySync.cat("gifts", "wife", MoneyEntry.CategoryBy.OWNER)
        m.fold(listOf(MoneySync.Event("e:x", t0 + 2 * day, "marianna-bb", mapOf(MoneySync.CAT to hers))))
        assertEquals(hers, m.values("e:x")[MoneySync.CAT])
    }

    @Test fun firstAppearanceNeverOverridesARealEdit() {
        // У Саши запись вычеркнута и разложена им; у Марианны та же строка
        // выписки — не вычеркнута, разложена справочником. Первый обмен Марианны
        // случается ПОЗЖЕ — и всё равно не воскрешает вычеркнутое.
        val sashas = entry("t-1").copy(dropped = true, category = "food_cafe", categoryBy = MoneyEntry.CategoryBy.OWNER)
        val hers = entry("t-1").copy(category = "food_shop", categoryBy = MoneyEntry.CategoryBy.RULE)
        val drive = ArrayList<MoneySync.Event>()
        val sasha = phone("sasha-aa", listOf(sashas))
        val marianna = phone("marianna-bb", listOf(hers))
        sync(sasha, drive, t0 + day)
        sync(marianna, drive, t0 + 2 * day)
        sync(sasha, drive, t0 + 3 * day)
        for (p in listOf(sasha, marianna)) {
            val e = p.local.entries.single()
            assertTrue(p.device, e.dropped)
            assertEquals(p.device, "food_cafe", e.category)
            assertEquals(MoneyEntry.CategoryBy.OWNER, e.categoryBy)
        }
    }

    @Test fun twoPhonesConverge() {
        val drive = ArrayList<MoneySync.Event>()
        val sasha = phone("sasha-aa", listOf(entry("t-1"), entry("t-2", what = "Перекрёсток")))
        val marianna = phone("marianna-bb", listOf(entry("a-1", owner = "marianna", what = "Лента")))
        sync(sasha, drive, t0 + 1_000)
        sync(marianna, drive, t0 + 2_000)
        sync(sasha, drive, t0 + 3_000)
        assertEquals(setOf("t-1", "t-2", "a-1"), sasha.local.entries.map { it.id }.toSet())
        assertEquals(sasha.local.flat(), marianna.local.flat())

        // Марианна раскладывает Сашину трату, Саша — её: обе правки доезжают.
        marianna.local = marianna.local.copy(entries = marianna.local.entries.map {
            if (it.id == "t-2") it.copy(category = "food_shop", who = "wife", categoryBy = MoneyEntry.CategoryBy.OWNER) else it
        })
        sasha.local = sasha.local.copy(entries = sasha.local.entries.map {
            if (it.id == "a-1") it.copy(note = "продукты на неделю") else it
        })
        sync(marianna, drive, t0 + 4_000)
        sync(sasha, drive, t0 + 5_000)
        sync(marianna, drive, t0 + 6_000)
        assertEquals(sasha.local.flat(), marianna.local.flat())
        assertEquals("food_shop", sasha.local.entries.first { it.id == "t-2" }.category)
        assertEquals("продукты на неделю", marianna.local.entries.first { it.id == "a-1" }.note)

        // Обмен без правок ничего не пишет.
        val before = drive.size
        sync(sasha, drive, t0 + 7_000)
        sync(marianna, drive, t0 + 8_000)
        assertEquals(before, drive.size)
    }

    @Test fun editMadeDuringExchangeIsNotOverwritten() {
        val m = MoneySync.Merged()
        m.fold(listOf(MoneySync.Event("e:t-1", t0 + day, "marianna-bb", mapOf("note" to "её"))))
        val local = MoneySync.Local(listOf(entry("t-1")), emptyList(), emptyList(), emptySet(), emptySet())
        val before = local.flat()
        // Пока шёл обмен, Саша вписал свою заметку.
        val now = local.copy(entries = listOf(entry("t-1").copy(note = "моя")))
        val out = MoneySync.apply(now, before, m)
        assertEquals("моя", out.local.entries.single().note)
        assertFalse(out.any)
    }

    @Test fun entriesAreNeverRemovedAndDraftsStayLocal() {
        val draft = entry("v-1").copy(source = MoneyEntry.Source.VOICE, draft = true)
        val local = MoneySync.Local(listOf(entry("t-1"), draft), emptyList(), emptyList(), emptySet(), emptySet())
        val flat = local.flat()
        assertNull("черновик до «ОК» не едет", flat["e:v-1"])
        // В журнале нет ни одной из записей — ничего не удаляется.
        val out = MoneySync.apply(local, flat, MoneySync.Merged())
        assertEquals(listOf("t-1", "v-1"), out.local.entries.map { it.id })
    }

    @Test fun newRemoteEntryIsAddedWithoutQuestion() {
        val theirs = entry("a-1", owner = "marianna").copy(question = "что это?", doubt = "50 или 50 000?")
        val m = MoneySync.Merged()
        m.fold(MoneySync.diff(MoneySync.Local(listOf(theirs), emptyList(), emptyList(), emptySet(), emptySet()).flat(), MoneySync.Merged(), t0, "marianna-bb"))
        val local = MoneySync.Local(emptyList(), emptyList(), emptyList(), emptySet(), emptySet())
        val out = MoneySync.apply(local, local.flat(), m)
        val e = out.local.entries.single()
        assertEquals(1, out.added)
        assertEquals(theirs.copy(question = "", doubt = ""), e)
    }

    @Test fun diffWritesFirstAppearanceOnlyWithFilledFields() {
        val events = MoneySync.diff(
            MoneySync.Local(listOf(entry("t-1")), emptyList(), emptyList(), emptySet(), emptySet()).flat(),
            MoneySync.Merged(), t0, "sasha-aa",
        )
        val e = events.single()
        assertEquals(MoneySync.T_FIRST, e.t)
        assertFalse(e.f.containsKey("drop"))
        assertFalse(e.f.containsKey(MoneySync.CAT))
        assertEquals("ВкусВилл", e.f["what"])
    }

    @Test fun rulesTravelAndDeletionsToo() {
        val drive = ArrayList<MoneySync.Event>()
        val nanny = MoneyRules.Rule(pattern = "Ирина С.", category = "home_nanny", sign = -1)
        val shop = MoneyRules.Rule(pattern = "Азбука", category = "food_shop")
        val sasha = phone("sasha-aa", emptyList(), listOf(nanny, shop))
        val marianna = phone("marianna-bb", emptyList())
        sync(sasha, drive, t0 + 1_000)
        sync(marianna, drive, t0 + 2_000)
        assertEquals(listOf(nanny, shop), marianna.local.rules)

        // Саша удалил «Азбуку», Марианна поправила няню.
        sasha.local = sasha.local.copy(rules = listOf(nanny))
        marianna.local = marianna.local.copy(rules = listOf(nanny.copy(comment = "няня Ромы"), shop))
        sync(sasha, drive, t0 + 3_000)
        sync(marianna, drive, t0 + 4_000)
        sync(sasha, drive, t0 + 5_000)
        assertEquals(listOf(nanny.copy(comment = "няня Ромы")), sasha.local.rules)
        assertEquals(sasha.local.rules, marianna.local.rules)
    }

    @Test fun balancesAndZfAccountsTravel() {
        val drive = ArrayList<MoneySync.Event>()
        val anchor = MoneyCashflow.Anchor("Т-Банк · Black Premium", t0, 23_248_372L, "вписано")
        val sasha = Phone("sasha-aa", MoneySync.Local(emptyList(), emptyList(), listOf(anchor), setOf("Т-Бизнес"), setOf("МКБ")))
        val marianna = phone("marianna-bb", emptyList())
        sync(sasha, drive, t0 + 1_000)
        sync(marianna, drive, t0 + 2_000)
        assertEquals(listOf(anchor), marianna.local.balances)
        assertEquals(setOf("Т-Бизнес"), marianna.local.zf)
        assertEquals(setOf("МКБ"), marianna.local.notZf)

        marianna.local = marianna.local.copy(zf = emptySet(), notZf = setOf("МКБ", "Т-Бизнес"))
        sync(marianna, drive, t0 + 3_000)
        sync(sasha, drive, t0 + 4_000)
        assertEquals(emptySet<String>(), sasha.local.zf)
        assertEquals(setOf("МКБ", "Т-Бизнес"), sasha.local.notZf)
    }

    @Test fun journalLineRoundTrip() {
        val e = MoneySync.Event(
            "e:push-3f9a", t0, "marianna-bb",
            mapOf("what" to "Диана Т.", "note" to "за «Лего»\nи торт", MoneySync.CAT to MoneySync.cat("gifts", "kids", MoneyEntry.CategoryBy.OWNER)),
        )
        val line = MoneySync.encode(e)
        assertFalse("одна строка", line.contains('\n'))
        assertEquals(e, MoneySync.decode(line))
        // Оборванная последняя строка не ломает остальные.
        val text = line + "\n" + line.take(line.length / 2)
        assertEquals(listOf(e), MoneySync.decodeAll(text))
    }

    @Test fun fileNames() {
        assertEquals("marianna-3f9a2c.000007.jsonl", MoneySync.chunkName("marianna-3f9a2c", 7))
        assertEquals(MoneySync.Chunk("marianna-3f9a2c", 7), MoneySync.parseChunk("marianna-3f9a2c.000007.jsonl"))
        assertNull(MoneySync.parseChunk("money.json"))
        assertEquals("sasha-3f9a2c", MoneySync.parseDeviceFile("sasha-3f9a2c.device.json"))
        assertEquals("sasha-2-3f9a2c", MoneySync.deviceId("sasha-2", "3f9a2c"))
        assertEquals("user-3f9a2c", MoneySync.deviceId("Саша", "3f9a2c"))
    }
}
