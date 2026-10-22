package ru.zf.pravka

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import ru.zf.pravka.core.Fmt
import ru.zf.pravka.core.SportCoach
import ru.zf.pravka.core.TrafficLight
import ru.zf.pravka.data.PlanStore
import ru.zf.pravka.data.SportStore
import ru.zf.pravka.data.dayKey
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.Kv
import ru.zf.pravka.ui.KvListRow
import ru.zf.pravka.ui.KvNote
import ru.zf.pravka.ui.KvSmallButton
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.PaperCard

// «Тело» и «Спорт» в виде v2 (Правка 5.0, этап 6, холст `Sport.dc.html`):
// плашки, которых у вкладки 4.0 не было, — готовность с крупными числами,
// «Сегодня» строками, план недели, нагрузка столбиками, последние тренировки.
// Раскладка экрана — в `SportTab.kt` (ветка v2), прежние плашки (зарядка,
// упражнения, силовая, графики «Пути», журнал) — там же. Логика не здесь:
// всё — из тех же сторов, что у 4.0, и считается тем же светофором.

private val RU = Locale.forLanguageTag("ru")
