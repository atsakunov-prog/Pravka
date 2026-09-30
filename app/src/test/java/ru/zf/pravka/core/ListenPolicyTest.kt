package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenPolicyTest {

    @Test
    fun `тишина — не поломка`() {
        assertTrue(ListenPolicy.isSilence(6))  // SPEECH_TIMEOUT
        assertTrue(ListenPolicy.isSilence(7))  // NO_MATCH
        assertFalse(ListenPolicy.isSilence(8)) // RECOGNIZER_BUSY
        assertFalse(ListenPolicy.isSilence(5)) // CLIENT
        assertFalse(ListenPolicy.isSilence(2)) // NETWORK
    }

    @Test
    fun `полминуты тишины — слушаем дальше`() {
        // Случай 23.09.2026: замолк на 30 с — и Правка ушла расшифровывать.
        assertTrue(ListenPolicy.resumeAfterSessionEnd(true, false, lastWordsAtMs = 0, nowMs = 30_000))
        assertTrue(ListenPolicy.resumeAfterSessionEnd(true, false, lastWordsAtMs = 0, nowMs = 9 * 60_000))
    }

    @Test
    fun `десять минут без слов — нажали случайно`() {
        assertFalse(ListenPolicy.resumeAfterSessionEnd(true, false, lastWordsAtMs = 0, nowMs = ListenPolicy.IDLE_CAP_MS))
        assertTrue(ListenPolicy.idleExpired(1_000, 1_000 + ListenPolicy.IDLE_CAP_MS))
        assertFalse(ListenPolicy.idleExpired(1_000, ListenPolicy.IDLE_CAP_MS))
    }

    @Test
    fun `считается от последнего слова, а не от старта`() {
        // Двадцать минут диктовки, последнее слово минуту назад — живо.
        assertTrue(ListenPolicy.resumeAfterSessionEnd(true, false, lastWordsAtMs = 19 * 60_000, nowMs = 20 * 60_000))
    }

    @Test
    fun `сорвавшаяся сессия считается, отработавшая обнуляет счёт`() {
        // Движок закрыл сессию, не дослушав своей тишины, — это срыв.
        assertEquals(1, ListenPolicy.countCollapse(0, ranMs = 50))
        assertEquals(3, ListenPolicy.countCollapse(2, ranMs = ListenPolicy.SESSION_MIN_MS - 1))
        // Сессия честно отстояла свою тишину — счёт срывов ни при чём.
        assertEquals(0, ListenPolicy.countCollapse(4, ranMs = 30_000))
        assertEquals(0, ListenPolicy.countCollapse(4, ranMs = ListenPolicy.SESSION_MIN_MS))
    }

    @Test
    fun `пять срывов подряд — поднимать больше нечего`() {
        assertFalse(ListenPolicy.giveUpOnCollapses(0))
        assertFalse(ListenPolicy.giveUpOnCollapses(ListenPolicy.MAX_QUICK_ENDS - 1))
        assertTrue(ListenPolicy.giveUpOnCollapses(ListenPolicy.MAX_QUICK_ENDS))
        assertTrue(ListenPolicy.giveUpOnCollapses(ListenPolicy.MAX_QUICK_ENDS + 1))
    }

    @Test
    fun `потолок локскрина - сорок секунд пальцем, у гарнитуры его нет`() {
        // Палец на замке: «через 40 секунд, я больше и не говорю».
        assertEquals(40_000L, ListenPolicy.lockedCapMs(fromHeadset = false))
        // Гарнитура: как у Правки — до кнопки или десяти минут без слов.
        assertEquals(0L, ListenPolicy.lockedCapMs(fromHeadset = true))
    }

    @Test
    fun `остановленную запись не поднимаем`() {
        assertFalse(ListenPolicy.resumeAfterSessionEnd(true, true, 0, 1_000))
        assertFalse(ListenPolicy.resumeAfterSessionEnd(false, false, 0, 1_000))
    }

    @Test
    fun `тишина после настоящей сессии - слушаем сразу, без паузы`() {
        // Владелец замолчал и сейчас продолжит: каждая миллисекунда паузы —
        // сказанное в никуда («много пропускает слов», 28.09.2026).
        assertEquals(0L, ListenPolicy.restartDelayMs(hardErrors = 0, quickSilences = 0))
    }

    @Test
    fun `настоящие ошибки и мгновенные тишины - с паузой, растущей до полутора секунд`() {
        assertEquals(600L, ListenPolicy.restartDelayMs(hardErrors = 1, quickSilences = 0))
        // Движок отвечает «не разобрал» тут же после старта — горячую петлю не крутим.
        assertEquals(600L, ListenPolicy.restartDelayMs(hardErrors = 0, quickSilences = 1))
        assertEquals(900L, ListenPolicy.restartDelayMs(hardErrors = 2, quickSilences = 1))
        assertEquals(1_500L, ListenPolicy.restartDelayMs(hardErrors = 40, quickSilences = 0))
    }

    @Test
    fun `речь без слов шесть секунд - распознаватель застрял`() {
        // Журнал 28.09: пять секунд речи, потом ещё две с половиной — и куски пустые.
        assertFalse(ListenPolicy.stuck(5_300))
        assertTrue(ListenPolicy.stuck(8_100))
        assertTrue(ListenPolicy.stuck(ListenPolicy.STUCK_SPEECH_MS))
    }

    @Test
    fun `подъёмы без слов - четыре подряд сразу, дальше раз в полминуты речи`() {
        assertTrue(ListenPolicy.mayRestartStuck(0, mutedSpeechMs = 6_000))
        assertTrue(ListenPolicy.mayRestartStuck(ListenPolicy.STUCK_MAX_RESTARTS - 1, mutedSpeechMs = 6_000))
        assertFalse(ListenPolicy.mayRestartStuck(ListenPolicy.STUCK_MAX_RESTARTS, mutedSpeechMs = 6_000))
        // Но не бросаем навсегда: застрявшая сессия иначе молчала бы до конца тейка.
        assertTrue(ListenPolicy.mayRestartStuck(ListenPolicy.STUCK_MAX_RESTARTS, mutedSpeechMs = ListenPolicy.STUCK_LATE_MS))
    }

    @Test
    fun `повтор звука - с последнего слова, но не дальше двадцати секунд`() {
        assertEquals(95_000L, ListenPolicy.replayFrom(lastWordsAtMs = 95_000, nowMs = 100_000))
        // Слово было две минуты назад — повторяем только последние двадцать секунд.
        assertEquals(80_000L, ListenPolicy.replayFrom(lastWordsAtMs = -20_000, nowMs = 100_000))
    }

    @Test
    fun `облако не отвечает - перекидываем на офлайн-пакет`() {
        // Сеть, таймаут, сервер, «слишком много запросов» — дорога до облака.
        for (code in listOf(1, 2, 4, 10)) assertTrue(ListenPolicy.cloudLost(code))
        // Тишина, занятый распознаватель, обрыв службы — не про облако.
        for (code in listOf(5, 6, 7, 8, 11)) assertFalse(ListenPolicy.cloudLost(code))
        assertTrue(ListenPolicy.toOffline(onCloud = true, offlineAvailable = true, cloudError = true, stuckRestarts = 0, stalled = false))
        assertTrue(ListenPolicy.toOffline(onCloud = true, offlineAvailable = true, cloudError = false, stuckRestarts = 0, stalled = true))
    }

    @Test
    fun `застрял - первый раз поднимаем облако, второй подряд - офлайн-пакет`() {
        assertFalse(ListenPolicy.toOffline(onCloud = true, offlineAvailable = true, cloudError = false, stuckRestarts = 1, stalled = false))
        assertTrue(ListenPolicy.toOffline(onCloud = true, offlineAvailable = true, cloudError = false, stuckRestarts = 2, stalled = false))
    }

    @Test
    fun `некуда перекидывать - не перекидываем`() {
        // Уже на пакете.
        assertFalse(ListenPolicy.toOffline(onCloud = false, offlineAvailable = true, cloudError = true, stuckRestarts = 3, stalled = true))
        // Пакета на телефоне нет.
        assertFalse(ListenPolicy.toOffline(onCloud = true, offlineAvailable = false, cloudError = true, stuckRestarts = 3, stalled = true))
    }

    @Test
    fun `отказ пути помнится полчаса, потом пробуем снова`() {
        assertTrue(ListenPolicy.feedRefusalLive(refusedAtMs = 1_000_000, nowMs = 1_000_000 + 60_000))
        assertFalse(ListenPolicy.feedRefusalLive(refusedAtMs = 1_000_000, nowMs = 1_000_000 + ListenPolicy.FEED_REFUSAL_TTL_MS))
        // Часы ушли назад (перезагрузка, смена времени) — отказ не в силе.
        assertFalse(ListenPolicy.feedRefusalLive(refusedAtMs = 1_000_000, nowMs = 500_000))
    }

    @Test
    fun `наушники, а облако звук не берёт - тейк сразу офлайн-пакетом`() {
        assertTrue(ListenPolicy.headsetToOffline(wantHeadset = true, onCloud = true, cloudRefused = true, offlineAvailable = true, offlineRefused = false))
        // Телефон — облако и без своей записи слышит, пакет не нужен.
        assertFalse(ListenPolicy.headsetToOffline(wantHeadset = false, onCloud = true, cloudRefused = true, offlineAvailable = true, offlineRefused = false))
        // Облако не отказывало — пробуем облако.
        assertFalse(ListenPolicy.headsetToOffline(wantHeadset = true, onCloud = true, cloudRefused = false, offlineAvailable = true, offlineRefused = false))
        // Пакет тоже не берёт — некуда.
        assertFalse(ListenPolicy.headsetToOffline(wantHeadset = true, onCloud = true, cloudRefused = true, offlineAvailable = true, offlineRefused = true))
        // Облако отказалось посреди тейка в наушниках — перекидываем.
        assertTrue(ListenPolicy.toOffline(onCloud = true, offlineAvailable = true, cloudError = false, stuckRestarts = 0, stalled = false, headsetRefused = true))
    }

    @Test
    fun `канал закрыла кнопка гарнитуры - это стоп, а не наушники отвалились`() {
        val closed = 1_000_000L
        assertTrue(ListenPolicy.headsetStopPending(closed, closed + 800))
        assertTrue(ListenPolicy.headsetStopPending(closed, closed + ListenPolicy.HEADSET_STOP_GRACE_MS - 1))
        // Давно — значит, отвалились по-настоящему.
        assertFalse(ListenPolicy.headsetStopPending(closed, closed + ListenPolicy.HEADSET_STOP_GRACE_MS))
        // Кнопка канал не закрывала.
        assertFalse(ListenPolicy.headsetStopPending(0L, closed))
    }

    @Test
    fun `кусок с парой слов на много секунд речи - сессия оглохла`() {
        // Журнал 30.09, 10:28:04: 13,5 с речи — и 10 знаков. Дальше куски пустые.
        assertTrue(ListenPolicy.fewWords(chars = 10, speechMs = 13_500))
        // Этого одного хватает, чтобы поднять заново: копится вся речь куска.
        assertTrue(ListenPolicy.stuck(13_500))
        // Пустой кусок — без слов при любой речи.
        assertTrue(ListenPolicy.fewWords(chars = 0, speechMs = 1_000))
        // Живые куски того же утра: 67 знаков за 10,9 с, 212 за 21,9 с, самый тощий — 20 за 6,6 с.
        assertFalse(ListenPolicy.fewWords(chars = 67, speechMs = 10_900))
        assertFalse(ListenPolicy.fewWords(chars = 212, speechMs = 21_900))
        assertFalse(ListenPolicy.fewWords(chars = 20, speechMs = 6_600))
        // Короткий кусок по густоте не судим: «да» за три секунды — нормально.
        assertFalse(ListenPolicy.fewWords(chars = 2, speechMs = ListenPolicy.THIN_MIN_SPEECH_MS - 1))
    }

    @Test
    fun `вход ушёл с наушников, а они на связи - это их кнопка, стоп со сказанным`() {
        // Владелец, 30.09: долгое нажатие в конце — «наушники отвалились», и ничего не ушло.
        val heard = 1_000_000L
        val later = heard + 30_000L
        // Стек не сказал (или опоздал), а гарнитура подключена — стоп.
        assertEquals(
            ListenPolicy.HeadsetDrop.BUTTON_STOP,
            ListenPolicy.headsetDrop(closedAtMs = 0L, nowMs = later, stillConnected = true, heardFromMs = heard),
        )
        // Стек успел сказать — стоп уже идёт своей дорогой, второй не нужен.
        assertEquals(
            ListenPolicy.HeadsetDrop.STOP_PENDING,
            ListenPolicy.headsetDrop(closedAtMs = later - 300, nowMs = later, stillConnected = true, heardFromMs = heard),
        )
        // Гарнитуры нет среди подключённых — отвалились по-настоящему.
        assertEquals(
            ListenPolicy.HeadsetDrop.LOST,
            ListenPolicy.headsetDrop(closedAtMs = 0L, nowMs = later, stillConnected = false, heardFromMs = heard),
        )
        // Первые секунды наушников — старт: канал делят стек и тейк, моргание не стоп.
        assertEquals(
            ListenPolicy.HeadsetDrop.LOST,
            ListenPolicy.headsetDrop(
                closedAtMs = 0L, nowMs = heard + ListenPolicy.HEADSET_BUTTON_MIN_MS - 1,
                stillConnected = true, heardFromMs = heard,
            ),
        )
        assertEquals(
            ListenPolicy.HeadsetDrop.BUTTON_STOP,
            ListenPolicy.headsetDrop(
                closedAtMs = 0L, nowMs = heard + ListenPolicy.HEADSET_BUTTON_MIN_MS,
                stillConnected = true, heardFromMs = heard,
            ),
        )
        // Наушники так и не встали — не их кнопка.
        assertEquals(
            ListenPolicy.HeadsetDrop.LOST,
            ListenPolicy.headsetDrop(closedAtMs = 0L, nowMs = later, stillConnected = true, heardFromMs = 0L),
        )
    }
}
