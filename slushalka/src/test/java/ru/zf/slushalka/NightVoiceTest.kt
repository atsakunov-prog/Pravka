package ru.zf.slushalka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.slushalka.data.NightVoice
import ru.zf.slushalka.data.ServerLibrary

/**
 * Озвучка нейросетью: файл заказа ведёт сервер, и статус, понятый не так,
 * виден только живьём - кнопкой «заказать» поверх живого заказа или вечным
 * «озвучу ночью».
 */
class NightVoiceTest {

    private val now = System.currentTimeMillis()

    @Test
    fun `статусы - словами из таблицы сервера`() {
        fun line(json: String) = NightVoice.Order.parse(json)!!.line
        assertEquals("Заказано", line("""{"status": "заказан", "at": $now}"""))
        assertEquals("Готовлю текст", line("""{"status": "правка", "at": $now}"""))
        assertEquals("Озвучу ночью", line("""{"status": "ждёт ночи", "at": $now}"""))
        assertEquals(
            "Озвучено 37%, продолжу ночью",
            line("""{"status": "ждёт ночи", "at": $now, "progress": {"done": 37, "total": 100}}"""),
        )
        assertEquals(
            "Озвучивается, 37%",
            line("""{"status": "озвучивается", "at": $now, "progress": {"done": 370, "total": 1000}}"""),
        )
        assertEquals("Не вышло: нет текста", line("""{"status": "ошибка", "at": $now, "error": "нет текста"}"""))
    }

    @Test
    fun `живой заказ, замолчавший и законченный`() {
        val alive = NightVoice.Order.parse("""{"status": "ждёт ночи", "at": ${now - 5 * 3600_000L}}""")!!
        assertTrue(alive.alive)
        assertFalse(alive.silent)
        val silent = NightVoice.Order.parse("""{"status": "озвучивается", "at": ${now - 7 * 3600_000L}}""")!!
        assertFalse(silent.alive)
        assertTrue("служба молчит - можно заказать заново", silent.silent)
        val done = NightVoice.Order.parse("""{"status": "готово", "at": ${now - 30 * 3600_000L}, "files": 12}""")!!
        assertTrue(done.doneOk)
        assertFalse(done.alive)
        assertFalse("законченный заказ не «молчит»", done.silent)
    }

    @Test
    fun `проверку сервера на N кусках приложение не замечает`() {
        assertNull(NightVoice.Order.parse("""{"status": "озвучивается", "at": $now, "limit": 20}"""))
        assertNull(NightVoice.Order.parse("не json"))
    }

    @Test
    fun `прикидка - формулами сервера`() {
        // «Этюд в багровых тонах»: 4,6 ч, 1 ночь, ~0,7 $.
        val etude = NightVoice.estimate(213_620)
        assertEquals(4.56, etude.hours, 0.01)
        assertEquals(1, etude.nights)
        assertEquals(0.68, etude.usd, 0.01)
        assertTrue(etude.caption, etude.caption.contains("~4,6 ч звука, 1 ночь, правка текста ~0,7 $"))
        // «Джордж»: 5,8 ч, 2 ночи (оценка с запасом), ~0,9 $.
        val george = NightVoice.estimate(270_389)
        assertEquals(2, george.nights)
        assertTrue(george.caption, george.caption.startsWith("Озвучит нейросетью за 2 ночи: ~5,8 ч звука"))
        assertTrue(george.caption.endsWith("Сама появится, когда будет готова."))
    }

    @Test
    fun `пометка - по файлу озвучка_json в other`() {
        val idx = ServerLibrary.parse(
            """{"books": [
                {"folder": "Этюд", "text": [{"path": "etude.fb2", "size": 1}], "audio": [{"path": "Этюд - 01 Глава.mp3", "size": 1, "ms": 1000}],
                 "other": [{"path": "озвучка.json", "size": 1}, {"path": "слушалка-разметка.json", "size": 1}]},
                {"folder": "Живая", "audio": [{"path": "01.mp3", "size": 1}], "other": []}
            ]}""",
        )
        assertTrue(idx.byFolder("этюд")!!.machineVoiced)
        assertFalse(idx.byFolder("Живая")!!.machineVoiced)
    }
}
