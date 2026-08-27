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

/**
 * Разбор «Итогов» человеческими глазами.
 *
 * Владелец: «можно сделать, чтобы это всё показывалось не маркапом с такими
 * решёточками, а красиво оформленным». Модель пишет обычный markdown, и решать
 * это надо на показе, а не запретом в промпте: запрет разметки лишил бы текст
 * структуры, а читать сплошную простыню на телефоне ещё хуже, чем решётки.
 *
 * Намеренно НЕ полноценный markdown: ни таблиц, ни ссылок, ни вложенных
 * списков. Ровно то, чем пользуется аналитик — заголовки, абзацы, списки,
 * цитаты, жирный и курсив, — и ни одной зависимости ради этого.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    baseStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier.fillMaxWidth()) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Heading -> {
                    Spacer(Modifier.height(if (block.level <= 2) 14.dp else 10.dp))
                    Text(
                        block.spans,
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.titleMedium
                            2 -> MaterialTheme.typography.titleSmall
                            else -> MaterialTheme.typography.bodyLarge
                        },
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                }

                is MdBlock.Paragraph -> {
                    Text(block.spans, style = baseStyle)
                    Spacer(Modifier.height(8.dp))
                }

                is MdBlock.Item -> {
                    Row(Modifier.fillMaxWidth().padding(bottom = 5.dp)) {
                        Text(
                            block.marker,
                            style = baseStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(block.spans, style = baseStyle, modifier = Modifier.weight(1f))
                    }
                }

                is MdBlock.Quote -> {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        // Полоска слева вместо значка «>»: цитату видно
                        // боковым зрением, а строка остаётся чистой.
                        Text(
                            "▎",
                            style = baseStyle,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            block.spans,
                            style = baseStyle,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MdBlock.Rule -> {
                    Spacer(Modifier.height(4.dp))
                    androidx.compose.material3.HorizontalDivider(
                        Modifier.padding(vertical = 6.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

private sealed interface MdBlock {
    class Heading(val level: Int, val spans: AnnotatedString) : MdBlock
    class Paragraph(val spans: AnnotatedString) : MdBlock
    class Item(val marker: String, val spans: AnnotatedString) : MdBlock
    class Quote(val spans: AnnotatedString) : MdBlock
    object Rule : MdBlock
}

private val BULLET = Regex("""^\s{0,3}[-*•]\s+""")
private val NUMBERED = Regex("""^\s{0,3}(\d{1,2})[.)]\s+""")
private val HEADING = Regex("""^(#{1,6})\s*(.*)$""")
private val RULE = Regex("""^\s{0,3}([-*_])\s*\1\s*\1[\s\-*_]*$""")

private fun parseMarkdown(source: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()

    fun flush() {
        val text = paragraph.toString().trim()
        paragraph.setLength(0)
        if (text.isNotEmpty()) out.add(MdBlock.Paragraph(inline(text)))
    }

    for (raw in source.lines()) {
        val line = raw.trimEnd()
        when {
            line.isBlank() -> flush()

            RULE.matches(line) -> {
                flush()
                out.add(MdBlock.Rule)
            }

            HEADING.matches(line) -> {
                flush()
                val m = HEADING.find(line)!!
                val body = m.groupValues[2].trim().trimEnd('#').trim()
                // «### » без текста — это не заголовок, а мусор разметки.
                if (body.isNotEmpty()) {
                    out.add(MdBlock.Heading(m.groupValues[1].length, inline(body)))
                }
            }

            BULLET.containsMatchIn(line) -> {
                flush()
                out.add(MdBlock.Item("•", inline(line.replaceFirst(BULLET, ""))))
            }

            NUMBERED.containsMatchIn(line) -> {
                flush()
                val n = NUMBERED.find(line)!!.groupValues[1]
                out.add(MdBlock.Item("$n.", inline(line.replaceFirst(NUMBERED, ""))))
            }

            line.trimStart().startsWith("> ") -> {
                flush()
                out.add(MdBlock.Quote(inline(line.trimStart().removePrefix("> "))))
            }

            else -> {
                // Перенос внутри абзаца — верстка исходника, а не пустая
                // строка: склеиваем, иначе на телефоне выйдет рванина.
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line.trim())
            }
        }
    }
    flush()
    return out
}

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
