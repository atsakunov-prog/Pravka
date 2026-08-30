package ru.zf.slushalka.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

/** Картинка, открытая во весь экран: файл, подпись и её место в книге. */
data class ShownPicture(val file: File, val caption: String = "", val charOffset: Int = 0)

/**
 * Картинка во весь экран с двумя пальцами. Ради карт всё и затевалось: карту
 * Москвы или план особняка надо разглядывать, а не угадывать по миниатюре.
 */
@Composable
fun ImageViewer(
    shown: ShownPicture,
    onAsk: (() -> Unit)? = null,
    onClose: () -> Unit,
) {
    val bitmap = rememberPicture(shown.file, target = 2400)
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xF20D0D0F))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        // На единичном увеличении картинка всегда по центру:
                        // иначе её легко утащить за край и «потерять».
                        if (scale > 1.01f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = shown.caption,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY,
                        ),
                )
            }
            TextButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) { Text("Закрыть", color = Color.White) }

            // Подпись и вопрос по картинке - понизу, поверх затемнения.
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (shown.caption.isNotBlank()) {
                    Text(
                        shown.caption,
                        color = Color(0xFFF2F0EC),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                if (onAsk != null) {
                    Row(horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = onAsk) {
                            Text("Расскажи про картинку", color = Color(0xFF9FC0F0))
                        }
                    }
                }
            }
        }
    }
}
