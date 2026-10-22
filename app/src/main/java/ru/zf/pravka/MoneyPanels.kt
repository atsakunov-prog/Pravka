package ru.zf.pravka

import ru.zf.pravka.ui.glass
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import ru.zf.pravka.core.MoneyCategories
import ru.zf.pravka.core.MoneyFormat
import ru.zf.pravka.core.MoneyStats
import ru.zf.pravka.trigger.MoneyTabVoice
import ru.zf.pravka.trigger.PravkaAccessibilityService
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperTextButton

// Панели вкладки «Деньги»: «Спросить Claude», категории с разбивкой по
// магазинам и паттерны. Сама вкладка (`MoneyTab.kt`) только раскладывает их.

/** «▲ 12 %» к прошлому периоду; мелочь (меньше 5 %) и новое не показываем — это шум. */
private fun delta(now: Long, prev: Long): Pair<String, Boolean>? {
    if (prev <= 0L) return null
    val d = (now - prev).toDouble() / prev
    if (kotlin.math.abs(d) < 0.05) return null
    return (if (d > 0) "▲ " else "▼ ") + "${kotlin.math.abs(Math.round(d * 100))} %" to (d > 0)
}

private fun pct(part: Long, whole: Long): String =
    if (whole <= 0) "" else "${Math.round(part * 100.0 / whole)} %"
