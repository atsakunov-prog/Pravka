package ru.zf.pravka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

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

/**
 * Прокручиваемая колонка экрана, которая на развороте делится на две
 * (DESIGN §13: «растянутая одна колонка не допускается»). [content] зовётся
 * с `side`: 0 — сложенный, всё одной лентой; 1 и 2 — левая и правая колонки,
 * каждая прокручивается сама.
 */
@Composable
fun SplitColumns(modifier: Modifier = Modifier, content: @Composable ColumnScope.(side: Int) -> Unit) {
    if (twoPane()) {
        Row(modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            for (side in 1..2) {
                Column(
                    Modifier
                        .weight(1f)
                        .fadingScroll()
                        .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
                ) { content(side) }
            }
        }
    } else {
        Column(
            modifier.fillMaxSize().fadingScroll().padding(ScreenPad.Padding),
            verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
        ) { content(0) }
    }
}
