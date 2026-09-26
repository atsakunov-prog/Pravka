package ru.zf.pravka.core

/**
 * Подсказка в пилюле диктовки, пока слов ещё нет. Владелец (26.09.2026):
 * «Тут просто можно брать имя человека, который использует правку: „Саша,
 * слушаю“ или „Марианна, слушаю“. И надо ставить его посередине, а не
 * выезжающим… Вот как Ask Gemini».
 *
 * Имя — из профиля установки (`data/Profile.kt`): телефон Марианны говорит с
 * Марианной. Две фазы остаются, как было у прежней строки: пока движок ещё
 * глух — [waiting] («секунду»), услышал — [listening]. Пустота или «слушаю»
 * до того, как движок проснулся, врала бы: первые слова ушли бы в никуда.
 *
 * Без эмодзи: прежний «🎙 говори» и был тем «значком микрофона из старого
 * набора», который вылезал в строке.
 */
object PillHint {

    fun listening(name: String?): String = address(name, "слушаю")

    fun waiting(name: String?): String = address(name, "секунду…")

    /** Засечка ждёт мысль к текущему делу — это надо узнать с первого взгляда. */
    fun thought(name: String?): String = address(name, "слушаю мысль к делу")

    private fun address(name: String?, what: String): String {
        val n = name?.trim().orEmpty()
        return if (n.isEmpty()) what.replaceFirstChar { it.uppercase() } else "$n, $what"
    }
}
