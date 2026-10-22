package ru.zf.pravka

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.OffsetDateTime
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.Dela
import ru.zf.pravka.core.DelaDebug
import ru.zf.pravka.core.ScreenDebug
import ru.zf.pravka.trigger.PravkaAccessibilityService
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.Kv
import ru.zf.pravka.ui.KvShape
import ru.zf.pravka.ui.LocalPravkaType

// Строка «Что не так? — запишу с экраном» — одна на всё приложение (16.10.2026, владелец: «можешь сделать на телефоне
// тоже дебаг строку такую же как на вебе? Чтобы отправлялось вместе с тем же, что и на вебе»). Вид, поле, микрофон,
// «Enter — записать», тост «🐞 №N записал» — здесь; что было на экране, знает тот, кто её поставил: вкладка Дел и
// карточка — снимок Дел (`DelaDebugBar`, DelaDebugUi.kt), остальные экраны — общий снимок (`ScreenDebugBar`,
// ScreenDebugUi.kt). Записка — та же операция `debug.add`, docs/dela-phone-24.md.

/**
 * Снимок к записке. Собирается в миг, когда человек взялся за строку (`capture` строки: фокус поля или микрофон), —
 * до клавиатуры, которая сдвинула бы список; [build] достраивает его при отправке: снимок данными и последние действия.
 */
internal fun interface DebugCapture {
    suspend fun build(version: String, events: List<String>, now: OffsetDateTime): Pair<JSONObject, JSONArray>
}

/** Снимок не собрался — записка уходит всё равно, с причиной вместо экрана. */
private fun failed(e: Throwable): DebugCapture = DebugCapture { version, events, now ->
    JSONObject().put("v", 1).put("route", "?").put("kind", "phone").put("title", "снимок не собрался: ${e.javaClass.simpleName}")
        .put("build", version) to DelaDebug.actions(DelaDebug.recentOps(), events, now, DelaDebug.recentNotes() + ScreenDebug.recentNav())
}
