package ru.zf.pravka.core

/**
 * Текст итога для пилюли (`DictationPill.result`): без значков в начале
 * строк. Записки Засечки и Еды писались под прежнюю плашку и начинаются с
 * эмодзи — «⏱ Созвон…», «💬 Мысль…», «🍽 Геркулес…», «✓ 3 дела». В пилюле
 * значок режима стоит слева сам, а успех и ошибку говорит цвет; эмодзи в
 * тексте был бы вторым значком и тем самым «значком из старого набора»,
 * который владелец просил убрать.
 *
 * Снимаем только ведущие не-буквы и не-цифры: кавычка-ёлочка, скобка и
 * «+» / «−» перед суммой — это текст, их оставляем.
 */
object PillText {

    fun plain(text: String): String =
        text.lines().map { stripLead(it) }.filter { it.isNotBlank() }.joinToString("\n")

    private fun stripLead(line: String): String {
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            if (Character.isLetterOrDigit(cp) || cp in KEEP) break
            i += Character.charCount(cp)
        }
        return line.substring(i).trim()
    }

    private val KEEP = setOf('«'.code, '"'.code, '('.code, '+'.code, '−'.code, '-'.code, '·'.code)
}
