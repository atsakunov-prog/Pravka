package ru.zf.pravka.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.core.Prompts

// Профиль установки (25.09.2026): кто пользуется и какие режимы включены.
// Держим то, что легко сломать: ключ владельца, «ждём ответа», род в чистке
// и то, что у владельца промпт остаётся байт в байт прежним (кэш промпта).
class ProfileTest {

    @Test
    fun `владелец — только ключ sasha, другой Саша владельцем не становится`() {
        assertTrue(Profile.of(Profile.Preset.SASHA).owner)
        assertFalse(Profile.of(Profile.Preset.MARIANNA).owner)
        assertEquals("sasha-2", Profile.idFor("Саша"))
        assertEquals("misha", Profile.idFor("Миша"))
        assertEquals("anna-mariya", Profile.idFor("Анна Мария"))
        assertEquals("user", Profile.idFor("  ***  "))
    }

    @Test
    fun `профиль переживает файл, а «ждём ответа» — не профиль`() {
        val p = Profile("misha", "Миша", female = false, modes = setOf(Profile.Mode.SPORT, Profile.Mode.FOOD))
        assertEquals(p, Profile.fromJson(Profile.toJson(p)))
        assertNull(Profile.fromJson(Profile.PENDING_JSON))
    }

    @Test
    fun `незнакомый режим в файле пропускается, пустой список — ничего кроме Правки`() {
        val p = Profile.fromJson("""{"id":"x","name":"X","female":true,"modes":["zasechka","telepatia"]}""")!!
        assertEquals(setOf(Profile.Mode.ZASECHKA), p.modes)
        val none = Profile.fromJson("""{"id":"x","name":"X","modes":[]}""")!!
        assertTrue(none.modes.isEmpty())
    }

    @Test
    fun `у владельца шаблон чистки не меняется ни на байт`() {
        val t = Prompts.CLEAN_CLAUDE
        assertEquals(t, Prompts.forAuthor(t, Prompts.Author.OWNER))
    }

    @Test
    fun `у Марианны — женский род и нет тем владельца`() {
        val t = Prompts.CLEAN_CLAUDE
        val her = Prompts.forAuthor(t, Prompts.Author("Марианна", female = true, owner = false))
        assertTrue(her.contains("Кто диктует: Марианна, женщина."))
        assertTrue(her.contains("в женском роде"))
        assertFalse(her.contains("Кто диктует: мужчина"))
        assertFalse(her.contains("сделки M&A"))
        // Всё до и после абзаца — прежнее.
        assertTrue(her.startsWith(t.substringBefore("Кто диктует:")))
        assertTrue(her.endsWith(t.substring(t.indexOf("Сначала пойми по содержанию"))))
    }

    @Test
    fun `свой текст без маркеров не трогается`() {
        val own = "Правь текст бережно.\n{DICT}\n{INPUT}"
        assertEquals(own, Prompts.forAuthor(own, Prompts.Author("Марианна", female = true, owner = false)))
    }

    @Test
    fun `приписка к разговору — в роде автора`() {
        val him = Prompts.assemble("{DICT}\n{INPUT}", "", conversation = "привет")
        val her = Prompts.assemble(
            "{DICT}\n{INPUT}", "", conversation = "привет",
            author = Prompts.Author("Марианна", female = true, owner = false),
        )
        val all = { p: Prompts.PromptParts -> p.stablePrefix + p.dictPart }
        assertTrue(all(him).contains("автор — мужчина, «говорил», а не «говорила»"))
        assertTrue(all(her).contains("автор — женщина, «говорила», а не «говорил»"))
    }

    @Test
    fun `нейтральные категории — без личного владельца, с опорными именами кода`() {
        val names = ZasechkaStore.neutralCategories().map { it.name }
        assertFalse(names.any { it.startsWith("Секс") })
        assertTrue("Учёба" in names)
        for (need in listOf("Сон", "Потери", "Не размечено", "Звонки", "Семья")) assertTrue(need, need in names)
        val hints = ZasechkaStore.neutralCategories().joinToString { it.hint }
        assertFalse(hints.contains("Мариан"))
        assertFalse(hints.contains("Правки"))
    }
}
