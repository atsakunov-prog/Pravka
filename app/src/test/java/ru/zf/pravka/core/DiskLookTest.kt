package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Вид диска (docs/telo.md, «Светлое стекло»): две шкурки стекла и плотности
// слоёв. Числа таковы, что светлая тарелка не пропадает ни на тёмном экране,
// ни на белом: на тёмном её держит плотность бумаги, на белом — тень.
class DiskLookTest {

    private val fab = 0.35f  // прозрачность кнопок с завода

    @Test
    fun `светлое стекло - бумага, тёмное - чернила`() {
        assertEquals(DiskLook.GLASS_LIGHT, DiskLook.glass(true))
        assertEquals(DiskLook.GLASS_DARK, DiskLook.glass(false))
        // Бумага действительно светлее чернил — иначе тумблер ничего не меняет.
        assertTrue((DiskLook.GLASS_LIGHT and 0xFF) > (DiskLook.GLASS_DARK and 0xFF))
    }

    @Test
    fun `бумага стоит плотнее чернил`() {
        val light = DiskLook.plateAlpha(fab, light = true)
        val dark = DiskLook.plateAlpha(fab, light = false)
        assertTrue("светлому стеклу нужно больше плотности", light > dark)
        // И то и другое — заметно, но под кнопками: стекло не перекрывает их.
        assertTrue(light < fab)
    }

    @Test
    fun `прозрачность кнопок 0 - стекла нет`() {
        assertEquals(0f, DiskLook.plateAlpha(0f, light = true), 0f)
        assertEquals(0f, DiskLook.plateAlpha(0f, light = false), 0f)
    }

    @Test
    fun `центр прозрачнее края`() {
        val fill = DiskLook.plateAlpha(fab, light = true)
        assertTrue(DiskLook.centreAlpha(fill) < fill)
    }

    @Test
    fun `тень плотнее стекла, но с потолком`() {
        val fill = DiskLook.plateAlpha(fab, light = true)
        assertTrue(DiskLook.shadowAlpha(fill) > fill)
        // Кнопки непрозрачны — тень всё равно не чернит пол-экрана.
        assertEquals(0.45f, DiskLook.shadowAlpha(DiskLook.plateAlpha(1f, true)), 0f)
    }

    @Test
    fun `фаска - блик ярче кромки, у бумаги ярче, чем у чернил`() {
        val fill = DiskLook.plateAlpha(fab, light = true)
        assertTrue(DiskLook.rimLightAlpha(fill, true) > DiskLook.rimShadeAlpha(fill, true))
        assertTrue(DiskLook.rimLightAlpha(fill, true) > DiskLook.rimLightAlpha(fill, false))
        // Потолки держат: на непрозрачных кнопках фаска не превращается в кант.
        val full = DiskLook.plateAlpha(1f, true)
        assertEquals(0.55f, DiskLook.rimLightAlpha(full, true), 0f)
        assertEquals(0.32f, DiskLook.rimShadeAlpha(full, true), 0f)
    }

    @Test
    fun `на диске лицо кнопки плотнее стекла — иначе оно просвечивает`() {
        val face = DiskLook.faceAlpha(fab, onDisk = true)
        val plate = DiskLook.plateAlpha(fab, light = true)
        assertTrue("кнопка должна быть плотнее тарелки", face > plate)
        assertTrue("и заметно плотнее настройки", face > fab)
        // В стопке кнопка висит прямо на приложении — там настройка как есть.
        assertEquals(fab, DiskLook.faceAlpha(fab, onDisk = false), 0f)
    }

    @Test
    fun `слайдер прозрачности на диске жив, но в верхней трети`() {
        assertEquals(0.7f, DiskLook.faceAlpha(0f, onDisk = true), 0.001f)
        assertEquals(1f, DiskLook.faceAlpha(1f, onDisk = true), 0.001f)
        assertTrue(DiskLook.faceAlpha(0.8f, true) > DiskLook.faceAlpha(0.3f, true))
        // За края не выходим даже на кривой настройке.
        assertEquals(1f, DiskLook.faceAlpha(5f, onDisk = true), 0f)
        assertEquals(0.7f, DiskLook.faceAlpha(-1f, onDisk = true), 0.001f)
    }

    @Test
    fun `ползунок владельца сильнее счёта, но только на диске`() {
        // Двинул — его число.
        assertEquals(0.5f, DiskLook.faceAlpha(fab, onDisk = true, override = 0.5f), 0f)
        // Не двигал — счёт как был.
        assertEquals(
            DiskLook.faceAlpha(fab, true),
            DiskLook.faceAlpha(fab, true, null),
            0f,
        )
        // В стопке ползунка нет: там прозрачность кнопки — это её прозрачность.
        assertEquals(fab, DiskLook.faceAlpha(fab, onDisk = false, override = 0.5f), 0f)
    }

    @Test
    fun `тень кнопки на бумаге заметнее, чем на чернилах`() {
        val light = DiskLook.plateAlpha(fab, light = true)
        val dark = DiskLook.plateAlpha(fab, light = false)
        assertTrue(DiskLook.socketAlpha(light, true) > DiskLook.socketAlpha(dark, false))
        // Потолок держит: тень кнопки не превращается в кляксу.
        assertEquals(0.3f, DiskLook.socketAlpha(1f, true), 0f)
    }

    @Test
    fun `свет, рельс и иней слабее самой заливки и не растут без края`() {
        val fill = DiskLook.plateAlpha(fab, light = true)
        // Все три — слои ПОВЕРХ стекла: заметны, но стекло остаётся главным.
        assertTrue(DiskLook.topLightAlpha(fill, true) < fill)
        assertTrue(DiskLook.railAlpha(fill, true) < fill)
        assertTrue(DiskLook.frostAlpha(fill, true) < fill)
        // На непрозрачных кнопках потолки держат: иначе стекло стало бы белым.
        val full = DiskLook.plateAlpha(1f, true)
        assertEquals(0.18f, DiskLook.topLightAlpha(full, true), 0f)
        assertEquals(0.16f, DiskLook.railAlpha(full, true), 0f)
        assertEquals(0.14f, DiskLook.frostAlpha(full, true), 0f)
    }

    @Test
    fun `на чернилах свет и иней заметнее, а рельс тише`() {
        val fill = DiskLook.plateAlpha(fab, light = false)
        assertTrue(DiskLook.topLightAlpha(fill, false) > DiskLook.topLightAlpha(fill, true))
        assertTrue(DiskLook.frostAlpha(fill, false) > DiskLook.frostAlpha(fill, true))
        assertTrue(DiskLook.railAlpha(fill, false) < DiskLook.railAlpha(fill, true))
    }

    @Test
    fun `шестерёнка берёт цвет у стекла наоборот`() {
        assertEquals(DiskLook.GLASS_DARK, DiskLook.gearInk(light = true))
        assertEquals(DiskLook.GLASS_LIGHT, DiskLook.gearInk(light = false))
    }

    @Test
    fun `полоса прогресса выпуклая, а не цветная нитка с белой`() {
        // Профиль поперёк: у внешнего края скат, сразу за ним блик, к
        // середине чистый цвет, у внутреннего края тень и слабый отсвет.
        val edge = DiskLook.bandTone(0f)
        val gloss = DiskLook.bandTone(0.22f)
        val middle = DiskLook.bandTone(0.55f)
        val deep = DiskLook.bandTone(0.85f)
        val inner = DiskLook.bandTone(1f)
        assertTrue("блик — самое светлое место полосы", gloss > edge && gloss > middle)
        assertTrue("сам край чуть темнее блика, он скатывается", edge > 0f && edge < gloss)
        assertEquals("в середине — чистый цвет полосы", 0f, middle, 0.001f)
        assertTrue("у внутреннего края тень", deep < 0f)
        assertTrue("и слабый отсвет у самой кромки", inner > deep && inner < 0f)
    }

    @Test
    fun `профиль полосы идёт плавно и не выходит за края`() {
        var prev = DiskLook.bandTone(0f)
        var step = 0f
        for (i in 1..100) {
            val t = i / 100f
            val v = DiskLook.bandTone(t)
            step = maxOf(step, Math.abs(v - prev))
            assertTrue("тон в пределах разумного: $v", v > -0.5f && v < 0.6f)
            prev = v
        }
        // Ни одной ступеньки: иначе полоса распадается на кольца.
        assertTrue("шаг профиля $step слишком крупный", step < 0.05f)
        // За краями профиль не продолжается, а упирается.
        assertEquals(DiskLook.bandTone(0f), DiskLook.bandTone(-1f), 0f)
        assertEquals(DiskLook.bandTone(1f), DiskLook.bandTone(2f), 0f)
        assertTrue("колец должно хватать на плавность", DiskLook.BAND_SLICES >= 7)
    }

    @Test
    fun `прозрачность кладётся в старший байт, цвет не трогается`() {
        assertEquals(0xFF3A342B.toInt(), DiskLook.withAlpha(DiskLook.GLASS_DARK, 1f))
        assertEquals(0x003A342B, DiskLook.withAlpha(DiskLook.GLASS_DARK, 0f))
        assertEquals(0x80FFFFFF.toInt(), DiskLook.white(0.502f))
        assertEquals(0x80000000.toInt(), DiskLook.black(0.502f))
        // За края не выходим: краскам Android нужен байт, а не отрицательное.
        assertEquals(0xFFFFFFFF.toInt(), DiskLook.white(2f))
        assertEquals(0x00000000, DiskLook.black(-1f))
    }
}
