package ru.zf.pravka

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import ru.zf.pravka.core.Fmt
import ru.zf.pravka.core.MoneyCategories
import ru.zf.pravka.core.MoneyEntry
import ru.zf.pravka.core.MoneyFormat
import ru.zf.pravka.core.MoneyMerchants
import ru.zf.pravka.core.MoneyStats
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.Kv
import ru.zf.pravka.ui.KvListRow
import ru.zf.pravka.ui.KvNote
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.PaperCard

// «Деньги» в виде v2 (Правка 5.0, этап 6, холст `Dengi.dc.html`): разделы
// вкладками, как в вебе Денег, — траты, разбор, ДДС, баланс, журнал; на
// «Тратах» — сколько ушло крупно, статьи строками с полосой, сегодняшние
// операции. Раскладка — в `MoneyTab.kt` (ветка v2); расчёты — те же
// `MoneyStats` и `MoneyEngine`, что у вкладки 4.0.

private val hm = SimpleDateFormat("HH:mm", Locale.US)
