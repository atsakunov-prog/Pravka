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
            what = "ВкусВилл", category = "food", takeId = 0L, account = "Карта *1111",
        )
        val state = MoneyStore.State(
            entries = listOf(money),
            rules = listOf(MoneyRules.Rule("вкусвилл", "food")),
            balances = listOf(MoneyCashflow.Anchor("Т-Банк · Карта", t0, 100_000, "вписано")),
            imports = listOf(MoneyStore.Import(t0 + 3_600_000L, "Тиньков", "sasha", 1, 1, t0, t0)),
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
            ArchiveEvents.zasechkaDays(listOf(entry), clock, t0 + 3_600_000L).single(),
            ArchiveEvents.phoneDay("2026-09-07", day, mapOf("org.telegram" to "Telegram")),
            ArchiveEvents.norms(Micronutrients.ALL),
            ArchiveEvents.meal(meal, clock),
            ArchiveEvents.session(session),
            ArchiveEvents.gtg(gtg),
            ArchiveEvents.strengthTake(StrengthStore.RawTake(3, t0, "махи 20 по 16", "strength", "voice", consumedBy = 9), clock),
            ArchiveEvents.moneyReference(state, clock),
            ArchiveEvents.moneyEntry(money, clock),
            ArchiveEvents.moneyTake(MoneyStore.Take(t0, "кофе 300", 0.001, "m"), clock),
            ArchiveEvents.moneyPush(
                MoneyStore.Push("push-1", t0, "com.idamob.tinkoff.android", "Покупка", "450 ₽", "ok"), clock,
                MoneyCashflow.Anchor("Т-Банк · Карта", t0, 100_000, "пуш «Покупка»", covers = setOf("push-push-1"), origin = MoneyCashflow.Origin.PUSH),
            ),
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

    // ------------------------------------------------------------------ Деньги: баланс для сервера

    private fun tx(id: String, ts: Long, kop: Long, account: String, category: String = "groceries", src: MoneyEntry.Source = MoneyEntry.Source.TINKOFF) =
        MoneyEntry(id = id, owner = "sasha", source = src, ts = ts, rubKop = kop, what = id, category = category, account = account)

    @Test
    fun `якоря — снимок и вписанные в справочнике, каждый пуш — в своей записи`() {
        val push1 = tx("push-a", t0, -10_000, "Т-Банк *1111", src = MoneyEntry.Source.PUSH).copy(replacedBy = "t:row")
        val row = tx("t:row", t0 + 30_000, -10_000, "Карта *1111")
        val state = MoneyStore.State(entries = listOf(push1, row))
        val anchors = listOf(
            MoneyCashflow.Anchor("Т-Банк · Карта", t0 - 86_400_000L, 5_000_000, "снимок банка", origin = MoneyCashflow.Origin.SNAPSHOT),
            MoneyCashflow.Anchor("Т-Банк · Карта", t0, 4_990_000, "пуш «А»", covers = setOf("push-a"), origin = MoneyCashflow.Origin.PUSH),
            MoneyCashflow.Anchor("Т-Банк · Карта", t0 + 3_600_000L, 4_980_000, "пуш «Б»", covers = setOf("push-b"), origin = MoneyCashflow.Origin.PUSH),
            MoneyCashflow.Anchor("Наличные", t0, 300_000, "вписано"),
        )
        val ref = ArchiveEvents.moneyReference(state, clock, anchors).data.getJSONArray("anchors")
        val list = (0 until ref.length()).map { ref.getJSONObject(it) }
        // Справочник — только снимок и вписанное: пуши в нём переотправляли бы его на каждый пуш.
        assertEquals(listOf("вписано", "снимок"), list.map { it.getString("source") })
        assertEquals("снимок банка", list[1].getString("note"))
        val raw = listOf(
            MoneyStore.Push("a", t0, "com.idamob.tinkoff.android", "Покупка", "…", "запись"),
            MoneyStore.Push("b", t0 + 3_600_000L, "com.idamob.tinkoff.android", "Покупка", "…", "запись"),
            MoneyStore.Push("c", t0 + 7_200_000L, "com.idamob.tinkoff.android", "Платежи", "Отказ", "отказ"),
        )
        val pushes = ArchiveEvents.moneyPushes(raw, anchors, state.entries, clock).associateBy { it.key }
        val a = pushes.getValue("a").data.getJSONObject("anchor")
        assertEquals("пуш", a.getString("source"))
        assertEquals(4_990_000L, a.getLong("kop"))
        assertEquals("Т-Банк · Карта", a.getString("account"))
        // Пуш, который заменила строка выписки, несёт и её номер: в «Доступно» она уже вошла.
        assertEquals(listOf("push-a", "t:row"), (0 until a.getJSONArray("covers").length()).map { a.getJSONArray("covers").getString(it) })
        assertEquals(4_980_000L, pushes.getValue("b").data.getJSONObject("anchor").getLong("kop"))
        // Отказ — без якоря.
        assertTrue(pushes.getValue("c").data.isNull("anchor"))
    }

    @Test
    fun `счёт каждой записи — как у баланса, округление помнит счёт покупки`() {
        val buy = tx("t:buy", t0, -98_000, "Карта *1111")
        val round = tx("t:round", t0, -2_000, "Копилка", category = "roundup").copy(rubKop = 2_000)
        val cash = MoneyEntry(id = "v:1", owner = "sasha", source = MoneyEntry.Source.VOICE, ts = t0, rubKop = -50_000, what = "кофе", category = "cafe", account = MoneyEntry.CASH)
        val voice = cash.copy(id = "v:2", account = "")
        val draft = cash.copy(id = "v:3", draft = true)
        val items = ArchiveEvents.moneyEntries(listOf(buy, round, cash, voice, draft), clock).associateBy { it.key }
        assertEquals(setOf("t:buy", "t:round", "v:1", "v:2"), items.keys)
        assertEquals("Т-Банк · Карта", items.getValue("t:buy").data.getString("balance_account"))
        assertEquals("Т-Банк · Копилка", items.getValue("t:round").data.getString("balance_account"))
        assertEquals("Т-Банк · Карта", items.getValue("t:round").data.getString("roundup_from"))
        assertTrue(items.getValue("t:buy").data.isNull("roundup_from"))
        assertEquals(MoneyCashflow.WALLET, items.getValue("v:1").data.getString("balance_account"))
        assertTrue(items.getValue("v:2").data.isNull("balance_account"))
    }

    @Test
    fun `реестр счетов и что покрывают выписки`() {
        val bp = tx("t:1", t0, -10_000, "Карта *1111")
        val bp2 = tx("t:2", t0 + 2 * 86_400_000L, -20_000, "Карта *2222")
        val save = tx("t:3", t0 + 86_400_000L, 500, "Копилка", category = "roundup")
        val zf = tx("z:1", t0, 1_000_000, "ЗФ", category = "zf_revenue", src = MoneyEntry.Source.TBIZ)
        val alfa = tx("a:1", t0, -30_000, "Счёт", src = MoneyEntry.Source.ALFA).copy(owner = "marianna")
        val state = MoneyStore.State(
            entries = listOf(bp, bp2, save, zf, alfa),
            imports = listOf(
                MoneyStore.Import(t0 + 5 * 86_400_000L, "Тиньков", "sasha", 3, 3, t0, t0 + 2 * 86_400_000L),
                MoneyStore.Import(t0 + 5 * 86_400_000L + 60_000, "ЗФ", "sasha", 1, 1, t0, t0),
                MoneyStore.Import(t0 + 5 * 86_400_000L + 120_000, "Плати", "sasha", 4, 4, t0, t0),
            ),
        )
        val anchors = listOf(MoneyCashflow.Anchor("Т-Банк · Платинум", t0, -7_000_000, "кредитка", origin = MoneyCashflow.Origin.SNAPSHOT))
        val data = ArchiveEvents.moneyReference(state, clock, anchors, setOf(MoneyCashflow.TBIZ_NAME), "sasha").data
        val acc = data.getJSONArray("accounts").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.associateBy { it.getString("name") }
        assertEquals("zf", acc.getValue(MoneyCashflow.TBIZ_NAME).getString("side"))
        assertEquals("debt", acc.getValue("Т-Банк · Платинум").getString("kind"))
        assertEquals("asset", acc.getValue("Т-Банк · Карта").getString("kind"))
        assertEquals("marianna", acc.getValue("Альфа · Счёт").getString("owner"))
        assertEquals("personal", acc.getValue("Альфа · Счёт").getString("side"))
        val cards = acc.getValue("Т-Банк · Карта").getJSONArray("cards")
        assertEquals(listOf("1111", "2222"), (0 until cards.length()).map { cards.getString(it) })
        val st = data.getJSONArray("statements").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        // Выгрузка «все карты» — строка на счёт; чат «Плати» — не выписка банка.
        assertEquals(listOf("Т-Банк · Карта", "Т-Банк · Копилка", MoneyCashflow.TBIZ_NAME), st.map { it.getString("account") })
        assertEquals(listOf("Т-Банк", "Т-Банк", "Т-Бизнес"), st.map { it.getString("bank") })
        assertEquals("2026-09-07", st[0].getString("from"))
        assertEquals("2026-09-09", st[0].getString("to"))
        assertEquals(2, st[0].getInt("rows"))
        assertEquals("2026-09-08", st[1].getString("from"))
        assertEquals(clock.iso(t0 + 5 * 86_400_000L), st[0].getString("loaded_at"))
    }

    @Test
    fun `запись из дела везёт task и project, остальные — без этих ключей`() {
        val plain = ZasechkaStore.Entry(
            id = 1, start = t0, end = t0 + 600_000, raw = "", title = "Отчёт", category = "Работа", client = "",
            useful = 0, source = "voice", synced = false, createdAt = t0,
        )
        val fromTask = plain.copy(id = 2, start = t0 + 600_000, end = t0 + 1_200_000, source = "task", task = "t-1", project = "p-1")
        val entries = ArchiveEvents.zasechkaDays(listOf(plain, fromTask), clock, t0 + 3_600_000L).single().data.getJSONArray("entries")
        assertTrue(!entries.getJSONObject(0).has("task") && !entries.getJSONObject(0).has("project"))
        assertEquals("t-1", entries.getJSONObject(1).getString("task"))
        assertEquals("p-1", entries.getJSONObject(1).getString("project"))
        // Контракт архива знает эти поля (запись «разбор отчётности»).
        val day = contract().getValue("zasechka.day").getJSONArray("entries")
        val keys = (0 until day.length()).flatMap { day.getJSONObject(it).keys().asSequence().toList() }.toSet()
        assertTrue(keys.containsAll(setOf("task", "project")))
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
        val e = ArchiveEvents.zasechkaDays(listOf(open), clock, t0 + 600_000L).single().data
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
        val days = ArchiveEvents.zasechkaDays(listOf(late, early), clock, t0 + 86_400_000L)
        assertEquals(listOf("2026-09-08", "2026-09-07").sorted(), days.map { it.key })
    }

    @Test
    fun `сутки с нахлёстами — ровно 1440 минут`() {
        // Как на настоящей ленте 22.09: секундные нахлёсты на стыках, дубль
        // тренировки с часов (от двери и с кнопки часов, конец один) и два
        // одинаковых куска голосом.
        val day0 = clock.dayStart(t0)
        fun at(h: Int, m: Int, s: Int = 0) = day0 + ((h * 60L + m) * 60 + s) * 1000
        fun e(id: Long, from: Long, to: Long, title: String, source: String = "voice") = ZasechkaStore.Entry(
            id = id, start = from, end = to, raw = "", title = title, category = "Работа", client = "",
            useful = 0, source = source, synced = false, createdAt = from,
        )
        val day = listOf(
            e(1, at(0, 0), at(7, 30, 40), "Сон", "auto"),
            e(2, at(7, 30, 20), at(10, 55), "Сборы"),            // нахлёст 20 с
            e(3, at(10, 55), at(10, 58), "Звонок"),
            e(4, at(10, 55), at(10, 58), "Звонок"),              // дубль голосом
            e(5, at(10, 58), at(12, 40), "Работа"),
            e(6, at(12, 40), at(13, 53), "BJJ: борьба", "auto"), // от двери
            e(7, at(12, 58), at(13, 53), "BJJ: борьба", "auto"), // с кнопки часов
            e(8, at(13, 53), at(15, 49, 30), "Работа"),
            e(9, at(15, 40), at(16, 17), "Передвижение: вело"),  // нахлёст 9 мин
            e(10, at(16, 17), day0 + 86_400_000L, "Вечер"),
        )
        val rows = ArchiveEvents.zasechkaDays(day.shuffled(java.util.Random(7)), clock, day0 + 90_000_000L)
            .single().data.getJSONArray("entries")
        val minutes = (0 until rows.length()).map { rows.getJSONObject(it) }
        assertEquals(1440L, minutes.sumOf { it.getLong("minutes") })
        // Дубли ушли в ноль, у первого куска — всё его время.
        val byId = minutes.associate { it.getLong("id") to it.getLong("minutes") }
        assertEquals(0L, byId[4L])
        assertEquals(0L, byId[7L])
        assertEquals(73L, byId[6L])
        // Начало и конец — как в ленте: нахлёст виден.
        assertEquals(clock.iso(at(15, 40)), minutes.first { it.getLong("id") == 9L }.getString("start"))
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

    @Test
    fun `с кем было дело — person в записи суток, только когда есть`() {
        val call = ZasechkaStore.Entry(
            id = 2, start = t0, end = t0 + 12 * 60_000, raw = "", title = "Звонок: Женя Соколов",
            category = "Работа: звонки", client = "Ромашка-банк", useful = 0, source = "auto", synced = false,
            createdAt = t0, project = "p-romashka", person = "c2f8",
        )
        val row = ArchiveEvents.zasechkaDays(listOf(call), clock, t0 + 3_600_000L).single().data.getJSONArray("entries").getJSONObject(0)
        assertEquals("c2f8", row.getString("person"))
        assertEquals("p-romashka", row.getString("project"))
        val plain = ArchiveEvents.zasechkaDays(listOf(call.copy(person = "", project = "")), clock, t0 + 3_600_000L).single()
            .data.getJSONArray("entries").getJSONObject(0)
        assertTrue(!plain.has("person"))
    }
}
