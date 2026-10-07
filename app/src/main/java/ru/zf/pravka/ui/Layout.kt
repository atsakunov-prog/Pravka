package ru.zf.pravka.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

// Разворот (DESIGN §13): две колонки, когда окно шире 600 dp И выше 600 dp.
// Сложенный Fold боком (900×411) — одна колонка: высоты на две колонки нет.
// `WindowWidthSizeClass` не берём — 791 dp у него «Medium», а нам нужно
// правило из двух чисел, и считается оно по окну напрямую.

const val TWO_PANE_DP = 600

/** Две колонки: ширина ≥ 600 и высота ≥ 600 dp. */
@Composable
fun twoPane(): Boolean {
    val c = LocalConfiguration.current
    return c.screenWidthDp >= TWO_PANE_DP && c.screenHeightDp >= TWO_PANE_DP
}
