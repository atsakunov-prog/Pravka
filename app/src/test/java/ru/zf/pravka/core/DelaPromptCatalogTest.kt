package ru.zf.pravka.core

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.core.prompts.PromptsRaznoska

/**
 * Справочник разбора надиктовки — связанные в голове, названные в хвосте (аудит 15.10.2026, №67). Замер входа «было /
 * стало» — на выдуманной копии того же размера, что у владельца (442 живые карточки, ~110 связанных), без вызова Claude:
 * собирается ровно та голова, что ушла бы в запрос. Люди выдуманные.
 */
class DelaPromptCatalogTest {

    /** Прежний `promptCatalog` (до 15.10.2026) — образец «как было» побайтно. */
    private fun oldCatalog(s: Dela.Snapshot, maxPeople: Int = 400): String {
        val sb = StringBuilder()
        val projects = s.liveProjects()
        if (projects.isNotEmpty()) {
            sb.append("ПРОЕКТЫ (project — ровно одно имя из списка; в скобках — как ещё называют):\n")
            for (p in projects) {
                sb.append("— ").append(p.name)
                if (p.aliases.isNotEmpty()) sb.append(" (").append(p.aliases.joinToString(", ")).append(')')
                sb.append(" · ").append(if (p.sphere == "home") "дом" else "работа")
                if (p.kind == "client") sb.append(" · клиент")
                sb.append('\n')
            }
        }
        val people = s.livePeople().take(maxPeople)
        if (people.isNotEmpty()) {
            sb.append("\nЛЮДИ (person — короткое имя из списка; в скобках — полное и как ещё зовут):\n")
            for (p in people) {
                sb.append("— ").append(p.label)
                val more = listOfNotNull(p.name.takeIf { it != p.label && it.isNotBlank() }) + p.aliases
                if (more.isNotEmpty()) sb.append(" (").append(more.joinToString(", ")).append(')')
                sb.append('\n')
            }
        }
        if (s.labels.isNotEmpty()) {
            sb.append("\nМЕТКИ (только свободные контексты из этого списка, новых не выдумывать):\n")
            sb.append(s.labels.joinToString(", ")).append('\n')
        }
        return sb.toString().trim()
    }

    private val first = listOf("Александр", "Евгений", "Мария", "Ольга", "Дмитрий", "Сергей", "Анна", "Игорь", "Вера",
        "Павел", "Елена", "Тимофей", "Ксения", "Андрей", "Михаил", "Татьяна", "Виктор", "Юлия", "Роман", "Ирина")
    // Фамилии — основа и суффикс: 132 разные, как в живом справочнике, где однофамильцев единицы.
    private val stems = listOf("Тест", "Пример", "Образц", "Выдумк", "Пробн", "Условн", "Макет", "Чернов", "Заглушк",
        "Модельн", "Шаблон", "Пуст", "Новик", "Сокол", "Ковыл", "Ромашк", "Ландыш", "Васильк", "Клён", "Берёзк", "Осин", "Дуб")
    private val suffixes = listOf("ов", "ин", "овский", "енко", "ук", "ман")
    private val shortOf = mapOf("Александр" to "Саша", "Евгений" to "Женя", "Мария" to "Маша", "Дмитрий" to "Дима",
        "Сергей" to "Серёжа", "Михаил" to "Миша", "Павел" to "Паша", "Тимофей" to "Тима")
    private val orgs = listOf("Ромашка", "Бета", "Гамма", "Дельта", "Ландыш", "Вектор", "Пример", "Образец")

    /**
     * Выдуманная копия размером с настоящую (аудит №65 и №67): 442 живые карточки, из них [related] связаны с делами;
     * у части — короткое имя и другое имя «Имя Фамилия (Компания)», как пишут «Люди сами» и телефонная книжка.
     */
    private fun big(related: Int = 110): Pair<Dela.Snapshot, Set<String>> {
        val people = LinkedHashMap<String, Dela.Person>()
        for (i in 0 until 442) {
            val f = first[i % first.size]
            val sfx = suffixes[(i / stems.size) % suffixes.size]
            val l = stems[(i * 7) % stems.size] + sfx
            val fem = f.endsWith("а") || f.endsWith("я")
            val name = "$f ${if (fem && (sfx == "ов" || sfx == "ин")) l + "а" else l}"
            val short = if (i % 3 == 0) shortOf[f].orEmpty() else ""
            val aliases = if (i % 4 == 0) listOf("$name (${orgs[i % orgs.size]})") else emptyList()
            val id = "p%03d".format(i)
            people[id] = Dela.Person(id, name, short = short, aliases = aliases)
        }
        val projects = (0 until 38).associate { i ->
            val id = "r%02d".format(i)
            id to Dela.Project(id, "ООО ${orgs[i % orgs.size]} ${i + 1}", aliases = if (i % 2 == 0) listOf(orgs[i % orgs.size]) else emptyList())
        }
        val s = Dela.Snapshot(people = people, projects = projects, labels = listOf("звонок", "быстр", "встреча", "почта", "в дороге"))
        return s to people.keys.take(related).toSet()
    }

    @Test
    fun `keep null — побайтно как было`() {
        val (s, _) = big()
        assertEquals(oldCatalog(s), Dela.promptCatalog(s))
        assertEquals(oldCatalog(s, 5), Dela.promptCatalog(s, maxPeople = 5))
    }

    @Test
    fun `keep режет людей, проекты и метки — как были, хвост — только названные вне keep`() {
        val (s, rel) = big()
        val cut = Dela.promptCatalog(s, rel)
        val lines = cut.lines().filter { it.startsWith("— ") }
        val projectLines = s.liveProjects().size
        assertEquals(projectLines + rel.size, lines.size)
        assertTrue(cut.contains("нет человека нигде — пиши имя, как названо"))
        assertTrue(cut.contains("МЕТКИ"))
        // Хвост — названные вне keep, той же строкой справочника.
        val outside = s.people.keys.drop(200).take(2).toSet()
        val more = Dela.promptMorePeople(s, outside + rel.first())
        assertTrue(more.startsWith("ЕЩЁ ЛЮДИ — названы в наговоре"))
        assertEquals(3, more.lines().count { it.startsWith("— ") })
        assertEquals("", Dela.promptMorePeople(s, emptySet()))
    }

    @Test
    fun `голова не зависит от наговора, хвост с ЕЩЁ ЛЮДИ — перед датой`() {
        val template = PromptsRaznoska.TASKS_DELA
        val cut = RaznoskaCache.headCut(template)
        assertTrue(cut > 0)
        // Голова кончается перед строкой с датой целиком: «Сегодня {TODAY}, сейчас {NOW}.» — первая строка хвоста.
        assertTrue(template.substring(cut).startsWith("Сегодня {TODAY}"))
        assertFalse(template.substring(0, cut).contains("{TODAY}"))
        assertTrue(template.substring(0, cut).contains("{CATALOG}"))
        assertEquals(0, RaznoskaCache.headCut("Сегодня {TODAY}.\nостальное"))
        assertEquals(0, RaznoskaCache.headCut("без даты"))
    }

    /**
     * Замер входа «было / стало» на тестовом наговоре — без Claude: голова, которую собрал бы `askSplit` (правила, справочник,
     * места; блок дерева одинаков в обоих случаях и не считается — его укоротил сервер, `place.block`), и хвост с наговором.
     * Цена — Опус 5.5 из запасной таблицы: вход $4, запись часового кэша $8 за млн; токен ≈ 2,13 знака (калибровка аудита).
     */
    @Test
    fun `замер — голова разбора без немых контактов вдвое и больше короче`() {
        val (s, rel) = big()
        val rules = SharedPromptsForTest.rules()
        val text = "Позвонить Евгению Соколову про модель, Ирине Тестовой — договор, и спросить у Ромашки счёт к пятнице"
        val viewOf = run {
            // Индекс ровно правила сервера: ключи — как focus.keys_of (для замера достаточно имён целиком основой).
            val people = JSONObject()
            for (p in s.people.values) people.put(p.id, org.json.JSONArray(Dela.normName(p.name).split(' ').filter { it.length >= 3 }.map { RaznoskaCacheTestHelp.stem(it) }))
            JSONObject().put("rule", 1).put("related", org.json.JSONArray(rel.toList())).put("people", people)
                .put("projects", JSONObject()).put("endings", org.json.JSONArray(RaznoskaCacheTestHelp.ENDINGS)).put("letters", "агеиймоуъыьюя")
                .put("translit", org.json.JSONArray())
        }
        val ix = ContextIndex.parse(viewOf)!!
        val keep = ix.keepFor(s)
        val more = Dela.promptMorePeople(s, ix.namedOutside(s, text, keep))
        fun head(catalog: String): String {
            val cut = RaznoskaCache.headCut(rules)
            return rules.substring(0, cut).replace("{CATALOG}", catalog).replace("{PLACES}", "МЕСТА: дом, офис").replace("{TREE}", "")
        }
        val before = head(oldCatalog(s))
        val after = head(Dela.promptCatalog(s, keep))
        val tailBefore = "Сегодня 2026-10-15, сейчас 12:00.\n\n" + text
        val tailAfter = more + "\n\n" + tailBefore
        val tok = { n: Int -> n / 2.13 }
        val usd = { headChars: Int, tailChars: Int, headRate: Double -> (tok(headChars) * headRate + tok(tailChars) * 4.0) / 1e6 }
        // Было: одиночный разбор писал голову в часовой кэш (×2). Стало: без кэша (×1), голова короче.
        val was = usd(before.length, tailBefore.length, 8.0)
        val now = usd(after.length, tailAfter.length, 4.0)
        val catBefore = oldCatalog(s)
        val catAfter = Dela.promptCatalog(s, keep)
        println(
            "ЗАМЕР справочник: ${catBefore.length} → ${catAfter.length} зн. (строк людей ${catBefore.lines().count { it.startsWith("— ") }} → " +
                "${catAfter.lines().count { it.startsWith("— ") }}, вместе с проектами), правила и места — ${head("").length} зн.",
        )
        println(
            "ЗАМЕР разбора надиктовки (выдуманная копия: ${s.people.size} карточек, связанных ${keep.size}, " +
                "названо вне головы ${more.lines().count { it.startsWith("— ") }}): голова ${before.length} → ${after.length} зн. " +
                "(${tok(before.length).toInt()} → ${tok(after.length).toInt()} ток.), хвост ${tailBefore.length} → ${tailAfter.length} зн.; " +
                "одиночный разбор, вход: \$${"%.4f".format(was)} → \$${"%.4f".format(now)}",
        )
        val peopleBefore = oldCatalog(s).length
        val peopleAfter = Dela.promptCatalog(s, keep).length
        assertTrue("справочник $peopleBefore → $peopleAfter", peopleAfter * 100 <= peopleBefore * 45)
        assertTrue(after.length < before.length)
        assertTrue("названные — в хвосте, перед датой", more.isEmpty() || tailAfter.startsWith("ЕЩЁ ЛЮДИ"))
    }
}

/** Правила разбора из общего файла контракта — как в APK (`SharedPrompts.raznoska`), без Android. */
private object SharedPromptsForTest {
    fun rules(): String {
        val f = listOf(File("../server/contract/prompts/raznoska.txt"), File("server/contract/prompts/raznoska.txt")).first { it.isFile }
        return PromptsRaznoska.withTail(f.readText().replace("\r\n", "\n").trim())
    }
}

/** Основа слова для замера — как `people.stem` сервера (окончание срезается, если остаётся ≥ 4 букв). */
internal object RaznoskaCacheTestHelp {
    val ENDINGS = listOf("ыми", "ими", "ому", "ему", "ого", "его", "ой", "ей", "ий", "ым", "им", "ом", "ем", "ою", "ею",
        "ую", "юю", "ая", "яя", "ии", "ия", "ья", "ье", "ью", "у", "ю", "а", "я", "е", "ы", "и", "о", "й")

    fun stem(w: String): String {
        for (e in ENDINGS) if (w.length - e.length >= 4 && w.endsWith(e)) return w.dropLast(e.length)
        return w
    }
}
