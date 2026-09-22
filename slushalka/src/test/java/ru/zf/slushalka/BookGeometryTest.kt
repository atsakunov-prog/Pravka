package ru.zf.slushalka

import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.ui.BOOK_PEEK
import ru.zf.slushalka.ui.BookShape
import ru.zf.slushalka.ui.PageLook
import ru.zf.slushalka.ui.PageSide
import ru.zf.slushalka.ui.bookPan
import ru.zf.slushalka.ui.bookPhase
import ru.zf.slushalka.ui.cardMetrics
import ru.zf.slushalka.ui.pagePadding

/**
 * Половина разворота на узком экране: разворот шире экрана на две полоски
 * подглядывания, камера ездит по нему. 22.09 книга съехала на полэкрана, и
 * это было видно только на живой сборке - здесь те же соотношения числами,
 * в пикселях при плотности 1: у левой страницы край переплёта у левого края
 * экрана, у правой - у правого, текст обеих страниц не заходит за корешок.
 */
class BookGeometryTest {

    private val w = 400f
    private val peek = BOOK_PEEK.value
    private val look = PageLook(Settings.PAGE_VOLUME, 10.dp, Settings.SHADOW_SOFT, bevel = true, sheen = true, grain = true)
    private val shape = BookShape(thickness = 8.dp, progress = 0.3f, half = true)
    private val card = cardMetrics(look, shape)

    /** Разворот - два экрана без двух полосок; левый край - там, куда уехала камера. */
    private val spread = 2 * w - 2 * peek
    private fun bookLeft(phase: Float) = bookPan(phase, w, peek)
    private fun spineX(phase: Float) = bookLeft(phase) + spread / 2

    @Test
    fun `фаза - левая страница 0, правая 1, и так по кругу`() {
        assertEquals(0f, bookPhase(0f), 1e-6f)
        assertEquals(1f, bookPhase(1f), 1e-6f)
        assertEquals(0f, bookPhase(2f), 1e-6f)
        assertEquals(1f, bookPhase(3f), 1e-6f)
        assertEquals(0.5f, bookPhase(0.5f), 1e-6f)
        assertEquals(0.5f, bookPhase(1.5f), 1e-6f)
    }

    @Test
    fun `переплёт у края экрана с той стороны, какую читаешь`() {
        // Левая страница: левый край книги - левый край экрана.
        assertEquals(0f, bookLeft(0f), 1e-3f)
        // Правая: правый край книги - правый край экрана.
        assertEquals(w, bookLeft(1f) + spread, 1e-3f)
    }

    @Test
    fun `текст не заходит за корешок ни на левой, ни на правой`() {
        val half = card.spine.value / 2
        // Левая страница стоит на экране целиком, текст кончается до корешка.
        val left = pagePadding(look, card, PageSide.LEFT, 0.dp, 0.dp, half = true)
        val leftTextEnd = w - left.calculateEndPadding(LayoutDirection.Ltr).value
        assertEquals(spineX(0f) - half, leftTextEnd, 1e-3f)
        // Правая - тоже целиком на экране, когда камера доехала; текст - после корешка.
        val right = pagePadding(look, card, PageSide.RIGHT, 0.dp, 0.dp, half = true)
        val rightTextStart = right.calculateStartPadding(LayoutDirection.Ltr).value
        assertEquals(spineX(1f) + half, rightTextStart, 1e-3f)
    }
}
