package ru.zf.slushalka.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import ru.zf.slushalka.R
import ru.zf.slushalka.data.Settings

/**
 * Цитата картинкой: выделенная фраза на бумаге читалки её же шрифтом, внизу -
 * автор и книга. 1080×1350 - пропорция 4:5, в мессенджерах и сторис она не
 * обрезается. Кегль подбирается под длину: короткая фраза крупно, абзац -
 * мельче, но не мельче, чем читается с телефона.
 */
object QuoteCard {

    private const val W = 1080
    private const val H = 1350
    private const val MARGIN = 110
    private const val QUOTE_MAX = 700

    fun render(context: Context, quote: String, title: String, author: String, palette: ReaderPalette, font: String): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(palette.bg.toArgb())
        val face = typeface(context, font)
        val text = quote.trim().replace('\n', ' ').let { if (it.length > QUOTE_MAX) it.take(QUOTE_MAX).trimEnd() + "…" else it }

        // Кавычка-ёлочка крупно и бледно - знак цитаты, а не часть текста.
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.dim.copy(alpha = 0.35f).toArgb()
            typeface = face
            textSize = 260f
        }
        canvas.drawText("«", MARGIN - 20f, 330f, mark)

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.fg.toArgb()
            typeface = face
        }
        val area = W - 2 * MARGIN
        val room = H - 360 - 300
        var size = 72f
        var layout: StaticLayout
        do {
            paint.textSize = size
            layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, area)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.28f)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .build()
            size -= 3f
        } while (layout.height > room && size > 30f)
        val top = 360f + ((room - layout.height) / 2f).coerceAtLeast(0f)
        canvas.save()
        canvas.translate(MARGIN.toFloat(), top)
        layout.draw(canvas)
        canvas.restore()

        // Подпись: тонкая линейка и под ней автор и название - как в книге.
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.dim.copy(alpha = 0.5f).toArgb()
            strokeWidth = 2f
        }
        val y = H - 230f
        canvas.drawLine(MARGIN.toFloat(), y, MARGIN + 160f, y, line)
        val sign = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.dim.toArgb()
            typeface = Typeface.create(face, Typeface.ITALIC)
            textSize = 40f
        }
        val who = listOf(author, "«$title»").filter { it.isNotBlank() && it != "«»" }.joinToString(", ")
        val signLayout = StaticLayout.Builder.obtain(who, 0, who.length, sign, area).setMaxLines(2)
            .setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        canvas.save()
        canvas.translate(MARGIN.toFloat(), y + 36f)
        signLayout.draw(canvas)
        canvas.restore()
        return bmp
    }

    /** Шрифт карточки - тот же, что в читалке; системные - системными. */
    private fun typeface(context: Context, font: String): Typeface {
        val res = when (font) {
            Settings.FONT_BOOK -> R.font.literata_regular
            Settings.FONT_PT_SERIF -> R.font.ptserif_regular
            Settings.FONT_LORA -> R.font.lora_regular
            Settings.FONT_MERRIWEATHER -> R.font.merriweather_regular
            Settings.FONT_BITTER -> R.font.bitter_regular
            Settings.FONT_PT_SANS -> R.font.ptsans_regular
            Settings.FONT_SANS -> return Typeface.SANS_SERIF
            Settings.FONT_MONO -> return Typeface.MONOSPACE
            else -> return Typeface.SERIF
        }
        return runCatching { ResourcesCompat.getFont(context, res) }.getOrNull() ?: Typeface.SERIF
    }

    /** Нарисовать и отдать - в мессенджер, в сторис, себе. */
    fun share(context: Context, quote: String, title: String, author: String, palette: ReaderPalette, font: String) {
        val bmp = render(context, quote, title, author, palette, font)
        val file = Share.file(context, "Цитата - $title.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Share.send(context, file, "image/png", "Цитата: $title")
    }
}
