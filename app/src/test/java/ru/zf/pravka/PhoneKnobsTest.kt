package ru.zf.pravka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.core.PhoneRemote
import ru.zf.pravka.data.ModelRoute
import ru.zf.pravka.data.Models
import ru.zf.pravka.data.Profile

/**
 * Какие настройки телефон показывает компьютеру (PhoneKnobs.kt): схема годится серверу, у каждой правимой —
 * сеттер, у показа — причина, секретов нет ни ключом, ни значением, выключенный режим не уходит.
 */
class PhoneKnobsTest {

    private val all = PhoneKnobs.all(owner = true)

    @Test
    fun `схема годится серверу`() {
        val groups = PhoneKnobs.groups(all)
        assertTrue(PhoneRemote.problems(groups).toString(), PhoneRemote.problems(groups).isEmpty())
        // Все группы страницы — с настройками, порядок — как в GROUPS.
        assertEquals(PhoneKnobs.GROUPS.map { it.key }, groups.map { it.key })
        // Пределы сервера (phone.MAX_SCHEMA, MAX_ITEMS): схема с подсказками дорог — с большим запасом.
        val bytes = PhoneRemote.schemaJson(groups).toString().toByteArray().size
        assertTrue("схема $bytes байт", bytes < 120 * 1024)
        assertTrue(all.size in 50..400)
    }

    /**
     * Настоящая схема телефона — файлом в build/phone-remote/schema.json: по ней проверяют страницу «Телефон» на
     * тестовой копии Дел (снимок с выдуманными значениями шлют руками). В репозиторий не идёт.
     */
    @Test
    fun `схема для проверки веба`() {
        val dir = java.io.File("build/phone-remote").apply { mkdirs() }
        java.io.File(dir, "schema.json").writeText(PhoneRemote.schemaJson(PhoneKnobs.groups(all)).toString(1))
        assertTrue(java.io.File(dir, "schema.json").length() > 1000)
    }

    @Test
    fun `правимое — с сеттером, показ — с причиной`() {
        for (k in all) {
            if (k.item.type == PhoneRemote.Type.INFO) {
                assertNull(k.item.key, k.write)
                assertNotNull(k.item.key, k.now)
                assertTrue("${k.item.key}: нет причины", k.item.why.isNotBlank())
            } else {
                assertNotNull("${k.item.key}: правится, а сеттера нет", k.write)
                assertNotNull("${k.item.key}: за ней не следят", k.flow)
                assertTrue(k.item.key, k.item.remote)
            }
        }
    }

    @Test
    fun `ни ключей, ни токенов`() {
        for (k in all) assertFalse(k.item.key, PhoneRemote.isSecret(k.item.key))
        // Подключения и разрешения — только показ: «ключ есть / нет», «есть / нет».
        for (k in all.filter { it.group == "links" || it.group == "perms" || it.group == "buttons" || it.group == "profile" }) {
            assertEquals(k.item.key, PhoneRemote.Type.INFO, k.item.type)
        }
        // Места, машина, метки — показ, без SSID, адреса и номеров (их читать нечем: только имена и число).
        for (key in listOf("auto.places", "auto.car", "auto.nfc", "voice.mic", "voice.engine", "app.build", "data.where", "food.targets", "sport.goal_weight")) {
            assertEquals(key, PhoneRemote.Type.INFO, all.first { it.item.key == key }.item.type)
        }
    }

    @Test
    fun `модели — все дороги, варианты из каталога`() {
        for (r in ModelRoute.entries) {
            val m = all.first { it.item.key == "model.${r.key}" }.item
            val e = all.first { it.item.key == "effort.${r.key}" }.item
            assertEquals(Models.ALL, m.options.map { it.v })
            assertEquals(Models.EFFORTS, e.options.map { it.v })
            assertEquals(r.key, m.row)
            assertEquals(r.key, e.row)
        }
    }

    @Test
    fun `выключенный режим и чужая проза не уходят`() {
        val m = PhoneKnobs.all(owner = false) { it != Profile.Mode.MONEY && it != Profile.Mode.ZASECHKA }
        val groups = m.map { it.group }.toSet()
        assertFalse("money" in groups)
        assertFalse("zasechka" in groups)
        assertTrue("sport" in groups && "food" in groups && "models" in groups)
        assertFalse(m.any { it.item.key == "pravka.prose" })
        assertTrue(all.any { it.item.key == "pravka.prose" })
    }

    @Test
    fun `что стоит в группе — одной строкой`() {
        val v = mapOf<String, Any?>(
            "z.quiet" to false, "z.gap" to 45, "z.day_start" to 9, "z.day_end" to 23,
            "today.bedtime" to 1410, "today.city" to "Москва", "net.probe" to true, "net.every" to "15",
            "model.pravka" to "claude-opus-5-5", "effort.pravka" to "medium", "look.theme" to "dark",
            "perm.mic" to "есть", "perm.calls" to "нет",
        )
        val s = PhoneKnobs.status(v)
        assertEquals("дыра от 45 мин · день 9–23", s["zasechka"])
        assertEquals("сон 23:30 · Москва", s["today"])
        assertEquals("проверка раз в 15 мин", s["net"])
        assertEquals("тёмная", s["look"])
        assertEquals("не выдано: 1", s["perms"])
        assertTrue(s["models"]!!.startsWith("своих: "))
        assertEquals("молча: лента пишется сама", PhoneKnobs.status(v + ("z.quiet" to true))["zasechka"])
    }
}
