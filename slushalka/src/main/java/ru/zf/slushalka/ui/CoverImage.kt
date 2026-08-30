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
    val tree = app.state.treeUri()
    val bitmap by produceState<Bitmap?>(Covers.cached(book.id), book.id) {
        if (value == null && tree != null) {
            value = runCatching { Covers.load(app, tree, book, app.texts) }.getOrNull()
        }
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
