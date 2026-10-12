package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Страховка от потерянного «не» (12.10.2026): живые случаи из ночных разборов.
class NegationGuardTest {

    @Test
    fun `потерянное отрицание ловится`() {
        // 10.10, пример судьи: «Нет» стало выдуманным обращением.
        assertEquals(1, NegationGuard.lost("нет Это наша общая встреча в 17:30", "Михаил, это наша общая встреча в 17:30."))
        // 18.09: «не» выброшено вместе с мусором распознавания.
        assertEquals(
            1,
            NegationGuard.lost(
                "конечно бежал столько что не э уже папа мог бы бежать ещё больше",
                "Конечно, бежал столько, что уже, наверное, мог бы бежать ещё больше.",
            ),
        )
        assertEquals(1, NegationGuard.lost("конечно же не помню", "Конечно же, помните?"))
    }

    @Test
    fun `повтор подряд и слитное написание — не потеря`() {
        assertEquals(0, NegationGuard.lost("не не надо так делать", "Не надо так делать."))
        assertEquals(0, NegationGuard.lost("нет нет нет я не про это", "Нет, я не про это."))
        assertEquals(0, NegationGuard.lost("я не знаю я не знаю что сказать", "Я не знаю, что сказать."))
        assertEquals(0, NegationGuard.lost("я не э не знаю", "Я не знаю."))
        assertEquals(0, NegationGuard.lost("это не правильно и не большой дом", "Это неправильно, и небольшой дом."))
        assertEquals(0, NegationGuard.lost("Не написал, но позвонил", "Не написал, но позвонил."))
        // Ответ с лишним отрицанием потерей не считается.
        assertEquals(0, NegationGuard.lost("пиши", "Не пиши."))
        // Ё и регистр не мешают; «нельзя» и «ни» — тоже отрицания.
        assertEquals(1, NegationGuard.lost("Нельзя ни в коем случае", "Ни в коем случае."))
    }

    @Test
    fun `указание для повтора называет число`() {
        assertTrue(NegationGuard.retryDirective("нет не надо").contains("— 2,"))
    }
}
