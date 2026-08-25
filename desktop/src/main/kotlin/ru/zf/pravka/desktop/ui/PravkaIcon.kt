package ru.zf.pravka.desktop.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp

// Значок в трее и в заголовке окна: та же оранжевая "П", что на телефоне.
// Рисуется кодом, чтобы не тащить в сборку файл иконки ради двадцати пикселей.
object PravkaIcon : Painter() {

    val accent = Color(0xFFFF8A3D)

    override val intrinsicSize: Size = Size(64f, 64f)

    override fun DrawScope.onDraw() {
        val side = minOf(size.width, size.height)
        drawCircle(color = accent, radius = side / 2, center = center)

        // "П": две ножки и перекладина.
        val barW = side * 0.12f
        val left = center.x - side * 0.22f
        val top = center.y - side * 0.24f
        val height = side * 0.48f
        drawRect(Color.White, Offset(left, top), Size(side * 0.44f, barW))
        drawRect(Color.White, Offset(left, top), Size(barW, height))
        drawRect(Color.White, Offset(left + side * 0.44f - barW, top), Size(barW, height))
    }

    val previewSize: Dp = Dp(64f)
}
