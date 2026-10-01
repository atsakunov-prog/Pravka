package ru.zf.pravka.core

import java.io.File
import java.nio.file.Files
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.data.ArchiveSync
import ru.zf.pravka.data.FoodStore
import ru.zf.pravka.data.MoneyStore
import ru.zf.pravka.data.PhoneStore
import ru.zf.pravka.data.SportStore
import ru.zf.pravka.data.StrengthStore
import ru.zf.pravka.data.ZasechkaStore

/**
 * Форма событий архива — контракт с сервером (`server/contract/batch.json`):
 * по нему построены виды схемы life. Ключ, который телефон назвал иначе,
 * сервер не уронит — он молча покажет пустую колонку, и Claude ответит
 * «не знаю» там, где данные есть. Поэтому сверка — в обе стороны и до
 * вложенных строк.
 */
class ArchiveEventsTest {

    private val msk = TimeZone.getTimeZone("Europe/Moscow")
    private val clock = ArchiveEvents.Clock(msk)

    // 2026-09-07 07:30 МСК
    private val t0 = 1_788_755_400_000L

    private fun contract(): Map<String, JSONObject> {
        val f = listOf(File("../server/contract/batch.json"), File("server/contract/batch.json")).first { it.isFile }
        val events = JSONObject(f.readText()).getJSONArray("events")
        val out = linkedMapOf<String, JSONObject>()
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            if (e.optString("op") == "put") out.putIfAbsent(e.getString("kind"), e.getJSONObject("data"))
        }
        return out
    }

    /** Ключи объекта и, у вложенных строк и объектов, ключи первой строки — путями. */
    private fun shape(o: JSONObject, prefix: String = ""): Set<String> {
        val out = sortedSetOf<String>()
        for (k in o.keys()) {
            val path = prefix + k
            out += path
            when (val v = o.opt(k)) {
                is JSONObject -> if (k != "micro") out += shape(v, "$path.")
                is JSONArray -> (v.opt(0) as? JSONObject)?.let { out += shape(it, "$path[].") }
            }
        }
        return out
    }

    private fun samples(): List<ArchiveEvents.Item> {
        val entry = ZasechkaStore.Entry(
            id = 1, start = t0, end = t0 + 45 * 60_000, raw = "работаю над отчётом", title = "Отчёт",
            category = "Работа", client = "Клиент", useful = 4, source = "voice", synced = false,
            createdAt = t0, pomodoros = 0, comment = "свели баланс",
        )
        val meal = FoodStore.Meal(
            id = 7, ts = t0, createdAt = t0, kind = "завтрак", raw = "творог и кофе",
            items = listOf(MealItem("творог", 200, 240, 34, 10, 6, 0, "примерно", mapOf("ca" to 300.0), pill = false)),
            confirmed = true, costUsd = 0.01, model = "m",
        )
        val session = StrengthStore.Session(
            id = 9, date = "2026-09-07", block = "A · дом", title = "Гиря",
            exercises = listOf(StrengthStore.ExerciseLog("swing", "Махи", rows = listOf(StrengthStore.SetRow(20, 16.0)))),
            feel = 2, rpe = 7, minutes = 30, rawIds = listOf(3L), done = true,
        )
        val gtg = StrengthStore.GtgDay(
            date = "2026-09-07", charged = true, hangSec = 30, pullups = 1, knee = "зелёный",
            items = listOf(StrengthStore.GtgItem("hang", "Вис", "20–30 сек", "ok", "30")),
        )
        val money = MoneyEntry(
            id = "t:abc", owner = "sasha", source = MoneyEntry.Source.TINKOFF, ts = t0, rubKop = -45_000,
            what = "ВкусВилл", category = "food", takeId = 0L,
        )
        val state = MoneyStore.State(
            rules = listOf(MoneyRules.Rule("вкусвилл", "food")),
            balances = listOf(MoneyCashflow.Anchor("Т-Банк", t0, 100_000, "push")),
            zfAccounts = setOf("*1111"),
            notZfAccounts = setOf("*2222"),
        )
        val day = PhoneStore.Day(
            screenMs = 3_600_000, pickups = 40, glances = 12,
            apps = mapOf("org.telegram" to 1_200_000L), appSessions = mapOf("org.telegram" to 5),
            glanceApps = mapOf("org.telegram" to 3), sites = mapOf("ya.ru" to 60_000L),
            callsMs = 600_000, calls = 2, callers = mapOf("Марианна" to 300_000L),
        )
        val take = JSONObject().put("ts", "2026-09-07T07:30:00+03:00").put("engine", "google")
            .put("audio_ms", 4200).put("text", "привет").put("ok", true).put("error", "")
            .put("audio", "a.wav").put("mic", "phone")
        val clean = JSONObject().put("ts", "2026-09-07T07:30:05+03:00").put("mode", "clean").put("model", "m")
            .put("input", "привет").put("output", "Привет.").put("latency_ms", 900).put("cost_usd", 0.002).put("error", "")
        return listOf(
            ArchiveEvents.zasechkaReference(listOf(ZasechkaStore.Category("Работа", "клиенты", 360, 5)), listOf("Клиент")),
            ArchiveEvents.zasechkaDays(listOf(entry), { 45L }, clock).single(),
            ArchiveEvents.phoneDay("2026-09-07", day, mapOf("org.telegram" to "Telegram")),
            ArchiveEvents.norms(Micronutrients.ALL),
            ArchiveEvents.meal(meal, clock),
            ArchiveEvents.session(session),
            ArchiveEvents.gtg(gtg),
            ArchiveEvents.strengthTake(StrengthStore.RawTake(3, t0, "махи 20 по 16", "strength", "voice", consumedBy = 9), clock),
            ArchiveEvents.moneyReference(state, clock),
            ArchiveEvents.moneyEntry(money, clock),
            ArchiveEvents.moneyTake(MoneyStore.Take(t0, "кофе 300", 0.001, "m"), clock),
            ArchiveEvents.moneyPush(MoneyStore.Push("push-1", t0, "com.idamob.tinkoff.android", "Покупка", "450 ₽", "ok"), clock),
            ArchiveEvents.pravkaTake(take, clock)!!,
            ArchiveEvents.pravkaClean(clean, clock)!!,
            ArchiveEvents.correction(5, t0, "org.telegram", "сказал", "модель", "итог", "dict", clock),
            ArchiveEvents.talk(SportStore.Talk(11, t0, "как форма?", "ровно", 0.02), clock),
        )
    }

    @Test
    fun `каждый вид телефона — ровно форма контракта`() {
        val c = contract()
        val made = samples()
        assertEquals("видов в контракте и у телефона", c.keys.sorted(), made.map { it.kind }.distinct().sorted())
        for (u in made) {
            assertEquals("ключи вида ${u.kind}", shape(c.getValue(u.kind)), shape(u.data))
        }
    }

    @Test
    fun `время — с поясом телефона, сутки — по нему же`() {
        assertEquals("2026-09-07T07:30:00.000+03:00", clock.iso(t0))
        assertEquals("2026-09-07", clock.day(t0))
        // 02:30 МСК — уже следующие сутки, хотя в UTC это ещё 23:30 предыдущих.
        assertEquals("2026-09-08", clock.day(t0 + 19 * 3_600_000L))
        assertEquals(t0, ArchiveEvents.logMillis("2026-09-07T07:30:00+03:00"))
        assertEquals(0L, ArchiveEvents.logMillis("вчера"))
    }

    @Test
    fun `отпечаток не зависит от порядка ключей и замечает правку`() {
        val a = JSONObject().put("a", 1).put("b", JSONArray().put("x")).put("c", 1.5)
        val b = JSONObject().put("c", 1.5).put("b", JSONArray().put("x")).put("a", 1)
        assertEquals(ArchiveEvents.hashOf(a), ArchiveEvents.hashOf(b))
        assertNotEquals(ArchiveEvents.hashOf(a), ArchiveEvents.hashOf(JSONObject(a.toString()).put("a", 2)))
        assertEquals(20, ArchiveEvents.hashOf(a).length)
    }

    @Test
    fun `открытое дело — без конца и без минут`() {
        val open = ZasechkaStore.Entry(
            id = 2, start = t0, end = 0, raw = "", title = "Гитара", category = "Хобби", client = "",
            useful = 0, source = "voice", synced = false, createdAt = t0,
        )
        val e = ArchiveEvents.zasechkaDays(listOf(open), { 99L }, clock).single().data
            .getJSONArray("entries").getJSONObject(0)
        assertTrue(e.isNull("end"))
        assertTrue(e.isNull("minutes"))
    }

    @Test
    fun `лента — сутками по местному дню`() {
        val late = ZasechkaStore.Entry(
            id = 3, start = t0 + 17 * 3_600_000L, end = t0 + 17 * 3_600_000L + 600_000, raw = "", title = "Сон",
            category = "Сон", client = "", useful = 0, source = "auto", synced = false, createdAt = t0,
        )
        val early = late.copy(id = 4, start = t0, end = t0 + 600_000)
        val days = ArchiveEvents.zasechkaDays(listOf(late, early), { 10L }, clock)
        assertEquals(listOf("2026-09-08", "2026-09-07").sorted(), days.map { it.key })
    }

    @Test
    fun `строка журнала без времени — не событие`() {
        assertNull(ArchiveEvents.pravkaTake(JSONObject().put("text", "x"), clock))
        assertNull(ArchiveEvents.pravkaClean(JSONObject().put("ts", "битое"), clock))
    }

    @Test
    fun `QR из pair разбирается, чужой — нет`() {
        val ok = ArchiveSync.parsePairing("pravka-archive:{\"url\":\"https://server.x.netcraze.pro:8443\",\"token\":\"abc\"}")
        assertEquals("https://server.x.netcraze.pro:8443" to "abc", ok)
        assertNull(ArchiveSync.parsePairing("https://example.com"))
        assertNull(ArchiveSync.parsePairing("pravka-archive:{\"url\":\"\",\"token\":\"abc\"}"))
        assertNull(ArchiveSync.parsePairing("pravka-archive:не json"))
    }

    @Test
    fun `адрес — с https и слешем на конце`() {
        assertEquals("https://server.x.pro:8443/", ArchiveSync.normalize(" server.x.pro:8443 "))
        assertEquals("https://server.x.pro:8443/", ArchiveSync.normalize("https://server.x.pro:8443//"))
        assertEquals("https://server.x.pro/", ArchiveSync.normalize("http://server.x.pro"))
    }

    @Test
    fun `квитанции переживают запись и чтение, битые — с нуля`() {
        val dir = Files.createTempDirectory("ledger").toFile()
        val f = File(dir, "archive-ledger.json")
        val l = ArchiveSync.Ledger(lastOk = 5, sent = 42)
        l.kind("food.meal")["f7"] = "abc"
        l.offsets["history.jsonl"] = 1234L
        l.write(f)
        val back = ArchiveSync.Ledger.read(f)
        assertEquals(mapOf("food.meal" to mapOf("f7" to "abc")), back.kinds)
        assertEquals(mapOf("history.jsonl" to 1234L), back.offsets)
        assertEquals(5L, back.lastOk)
        assertEquals(42L, back.sent)
        f.writeText("{битый")
        assertTrue(ArchiveSync.Ledger.read(f).kinds.isEmpty())
        dir.deleteRecursively()
    }
}
