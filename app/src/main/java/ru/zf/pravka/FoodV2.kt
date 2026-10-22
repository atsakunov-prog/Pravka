package ru.zf.pravka

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.util.Date
import ru.zf.pravka.core.Fmt
import ru.zf.pravka.data.FoodStore
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.Kv
import ru.zf.pravka.ui.KvListRow
import ru.zf.pravka.ui.KvNote
import ru.zf.pravka.ui.KvSetRow
import ru.zf.pravka.ui.KvSmallButton
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.trigger.onFoodTap

// «Еда» в виде v2 (Правка 5.0, этап 6, холст `Eda.dc.html`): итог дня
// крупным числом и полосами КБЖУ, дневник строками, цели, неделя столбиками.
// Раскладка — в `FoodTab.kt` (ветка v2); приёмы, витамины и рацион — те же
// данные и те же действия, что у вкладки 4.0, другая только вёрстка.

/** Голосом — та же дорога, что тап по кнопке еды (`onFoodTap`); службы нет — словами. */
internal fun mealVoice(context: android.content.Context) {
    val service = ru.zf.pravka.trigger.PravkaAccessibilityService.instance
    if (service == null) ru.zf.pravka.ui.Feedback.toast(context, context.getString(R.string.toast_no_service))
    else with(service) { onFoodTap() }
}
