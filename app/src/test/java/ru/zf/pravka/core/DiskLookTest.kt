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
    fun `тень кнопки на бумаге заметнее, чем на чернилах`() {
        val light = DiskLook.plateAlpha(fab, light = true)
        val dark = DiskLook.plateAlpha(fab, light = false)
        assertTrue(DiskLook.socketAlpha(light, true) > DiskLook.socketAlpha(dark, false))
        // Потолок держит: тень кнопки не превращается в кляксу.
        assertEquals(0.3f, DiskLook.socketAlpha(1f, true), 0f)
    }

    @Test
    fun `шестерёнка берёт цвет у стекла наоборот`() {
        assertEquals(DiskLook.GLASS_DARK, DiskLook.gearInk(light = true))
        assertEquals(DiskLook.GLASS_LIGHT, DiskLook.gearInk(light = false))
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
