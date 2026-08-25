package ru.zf.pravka.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.zf.pravka.core.Prompts
import ru.zf.pravka.desktop.Controller

// Меню правки - то же, что открывается долгим нажатием на "П" на телефоне:
// красный ряд правит текст, оранжевый отвечает и переводит.
private val RED = Color(0xFFD64545)
private val ORANGE = Color(0xFFFF8A3D)

private class Action(val title: String, val color: Color, val run: () -> Unit)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionMenuContent(controller: Controller, close: () -> Unit) {
    val actions = listOf(
        Action("Причесать", RED) { controller.clean() },
        Action("Короче", RED) { controller.clean(Prompts.REDO_SHORTER, strong = true) },
        Action("Длиннее", RED) { controller.clean(Prompts.REDO_LONGER, strong = true) },
        Action("Отшлифовать", RED) { controller.clean(Prompts.REDO_POLISH, strong = true) },
        Action("Отменить", RED) { controller.undo() },
        Action("Сброс", RED) { controller.reset() },
        Action("Коротко", ORANGE) { controller.assist("Коротко", Prompts.ASSIST_SUMMARY) },
        Action("Ответить", ORANGE) { controller.assist("Ответить", Prompts.ASSIST_REPLY) },
        Action("Перевод", ORANGE) { controller.assist("Перевод", Prompts.ASSIST_TRANSLATE) },
    )

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xF2141414), RoundedCornerShape(14.dp))
            .padding(10.dp)
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (action in actions) {
                Button(
                    onClick = {
                        // Окно закрываем ДО действия: иначе Ctrl+C уедет в него,
                        // а не в то приложение, где владелец выделил текст.
                        close()
                        action.run()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = action.color),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) { Text(action.title, fontSize = 13.sp, color = Color.White) }
            }
        }
    }
}
