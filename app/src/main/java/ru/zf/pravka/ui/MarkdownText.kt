package ru.zf.pravka.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

private val RULE = Regex("""^\s{0,3}([-*_])\s*\1\s*\1[\s\-*_]*$""")

/**
 * Разбор жирного, курсива, зачёркнутого и `кода` за один проход. Регулярки
 * тут были бы короче и заметно медленнее на тексте в пару тысяч знаков,
 * который перерисовывается на каждом раскрытии карточки.
 */
private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    var bold = false
    var italic = false
    var code = false
    var strike = false

    fun styleNow(): SpanStyle = SpanStyle(
        fontWeight = if (bold) FontWeight.SemiBold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        fontFamily = if (code) androidx.compose.ui.text.font.FontFamily.Monospace else null,
        textDecoration = if (strike) TextDecoration.LineThrough else null,
    )

    fun emit(chunk: String) {
        if (chunk.isEmpty()) return
        val style = styleNow()
        if (style == SpanStyle()) append(chunk) else withStyle(style) { append(chunk) }
    }

    val buffer = StringBuilder()
    fun flush() {
        emit(buffer.toString())
        buffer.setLength(0)
    }

    while (i < text.length) {
        val rest = text.length - i
        val c = text[i]
        when {
            !code && c == '*' && rest >= 2 && text[i + 1] == '*' -> {
                flush(); bold = !bold; i += 2
            }
            !code && c == '~' && rest >= 2 && text[i + 1] == '~' -> {
                flush(); strike = !strike; i += 2
            }
            // Одиночная звёздочка — курсив, но только вплотную к слову:
            // «5 * 3» и «конец *» разметкой не являются.
            !code && (c == '*' || c == '_') && italicBoundary(text, i) -> {
                flush(); italic = !italic; i += 1
            }
            c == '`' -> {
                flush(); code = !code; i += 1
            }
            else -> {
                buffer.append(c); i += 1
            }
        }
    }
    flush()
}

/** Звёздочка или подчёркивание считаются разметкой только у края слова. */
private fun italicBoundary(text: String, at: Int): Boolean {
    val before = text.getOrNull(at - 1)
    val after = text.getOrNull(at + 1)
    val opens = after != null && !after.isWhitespace() && (before == null || !before.isLetterOrDigit())
    val closes = before != null && !before.isWhitespace() &&
        (after == null || !after.isLetterOrDigit())
    return opens || closes
}
