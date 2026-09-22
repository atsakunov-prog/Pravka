package ru.zf.slushalka.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.blur
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.library.Book

/**
 * Обложка книги. Пока картинка не нашлась (или её нет вовсе) - бумажная
 * плашка с названием: полка не должна выглядеть пустой.
 */
@Composable
fun CoverImage(app: SlushalkaApp, book: Book, modifier: Modifier = Modifier, textSize: Int = 13) {
    val tree = app.state.treeOf(book)
    // Значение сбрасывается при смене книги, а не «грузим, если пусто»: иначе
    // от прошлой книги остаётся её обложка.
    val bitmap by produceState<Bitmap?>(Covers.cached(book.id), book.id) {
        value = Covers.cached(book.id)
            ?: tree?.let { runCatching { Covers.load(app, it, book, app.texts) }.getOrNull() }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = book.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            val scheme = MaterialTheme.colorScheme
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(scheme.surfaceContainerHigh, scheme.surfaceVariant)
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = book.title.take(24),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = textSize.sp),
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
    }
}

/**
 * Обложка в книжной плитке 2:3. У аудиокниг обложки квадратные, у fb2 -
 * книжные; обрезать квадрат под книгу значит срезать треть картинки. Поэтому
 * картинка вписывается целиком, а поля под ней заливает она же - растянутая,
 * размытая и притушенная: плитка выглядит книгой, а обложка остаётся целой.
 * Размытие есть с Android 12; ниже - просто притушенная подложка.
 */
@Composable
fun CoverTile(app: SlushalkaApp, book: Book, modifier: Modifier = Modifier) {
    val tree = app.state.treeOf(book)
    val bitmap by produceState<Bitmap?>(Covers.cached(book.id), book.id) {
        value = Covers.cached(book.id)
            ?: tree?.let { runCatching { Covers.load(app, it, book, app.texts) }.getOrNull() }
    }
    val bmp = bitmap
    if (bmp == null) {
        CoverImage(app, book, modifier, textSize = 12)
        return
    }
    val image = remember(bmp) { bmp.asImageBitmap() }
    // Книжная обложка и так ложится в плитку - подложка ей не нужна.
    val bookish = bmp.height > bmp.width * 1.25f
    Box(modifier, contentAlignment = Alignment.Center) {
        if (!bookish) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.55f,
                modifier = Modifier.fillMaxSize().blur(18.dp),
            )
        }
        Image(
            bitmap = image,
            contentDescription = book.title,
            contentScale = if (bookish) ContentScale.Crop else ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
