package ru.zf.pravka

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.zf.pravka.data.Profile
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperToggle
import ru.zf.pravka.ui.RowRule
import ru.zf.pravka.ui.ScreenPad
import ru.zf.pravka.ui.Segments

// Кто пользуется установкой и какие режимы ему нужны (25.09.2026). Владелец:
// «сначала Правка должна спрашивать, кто юзер: Саша, Марианна, Серёжа или
// ещё кто-то… кому-то Засечка не нужна, или дела, или спорт — отключать одним
// тумблером; круг не перерисовывать, просто от пяти до одной кнопки».
// Первый запуск — экран целиком (ProfileOnboarding), потом то же самое —
// группа «Кто пользуется» в настройках (ProfileSettings). Модель — data/Profile.kt.

/** Выбор человека: три знакомых и «другой» с именем и родом. */
private class WhoState(initial: Profile?) {
    var preset by mutableStateOf(initial?.let { p -> Profile.Preset.entries.firstOrNull { it.id == p.id } })
    var other by mutableStateOf(initial != null && preset == null)
    var name by mutableStateOf(if (initial != null && preset == null) initial.name else "")
    var female by mutableStateOf(initial?.female ?: false)

    val ready: Boolean get() = preset != null || (other && name.isNotBlank())

    /** Профиль из выбора; [keepId] — прежний ключ «другого», если имя поправили. */
    fun build(modes: Set<Profile.Mode>, keepId: String?): Profile? {
        preset?.let { return Profile.of(it, modes) }
        if (!other || name.isBlank()) return null
        val clean = name.trim()
        return Profile(keepId ?: Profile.idFor(clean), clean, female, modes)
    }
}

@Composable
private fun WhoPicker(who: WhoState) {
    ChipRow {
        for (p in Profile.Preset.entries) {
            PaperChip(p.title, selected = who.preset == p, onClick = { who.preset = p; who.other = false })
        }
        PaperChip("Другой", selected = who.other, onClick = { who.other = true; who.preset = null })
    }
    if (who.other) {
        Spacer(Modifier.height(8.dp))
        PaperField(value = who.name, onValueChange = { who.name = it }, label = "Имя")
        Spacer(Modifier.height(6.dp))
        // Род нужен чистке текста: «я сделала» не должно становиться «я сделал».
        Segments(listOf("он", "она"), selected = if (who.female) 1 else 0, onSelect = { who.female = it == 1 })
    }
}

@Composable
private fun ModeToggles(modes: Set<Profile.Mode>, onChange: (Set<Profile.Mode>) -> Unit) {
    PaperToggle(title = "Правка", checked = true, onCheckedChange = {}, hint = "чистка диктовки — всегда", enabled = false)
    for (m in Profile.Mode.entries) {
        RowRule()
        PaperToggle(
            title = m.title,
            checked = m in modes,
            onCheckedChange = { on -> onChange(if (on) modes + m else modes - m) },
            hint = m.hint,
        )
    }
}

private const val MODES_INFO =
    "Выключенный режим пропадает снизу и со стекла и ничего не делает в фоне: ни " +
        "напоминаний, ни синков, ни запросов к Claude. Его данные не стираются — " +
        "включишь, и всё на месте. На диске остаётся столько кнопок, сколько режимов " +
        "с кнопкой включено, от пяти до одной «П»."

/** Первый запуск: кто пользуется и что включить. Пока не ответят — остального приложения нет. */
@Composable
internal fun ProfileOnboarding(app: PravkaApp) {
    val who = remember { WhoState(null) }
    var modes by remember { mutableStateOf(Profile.Mode.entries.toSet()) }
    var saving by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ScreenPad.Padding),
        verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
    ) {
        Text("Правка", style = MaterialTheme.typography.headlineSmall)
        PaperCard(label = "кто пользуется") {
            PaperHint("Чья это установка: у каждого своя база, свой род в чистке текста и свои режимы.")
            Spacer(Modifier.height(8.dp))
            WhoPicker(who)
        }
        PaperCard(label = "что включить", info = MODES_INFO) {
            ModeToggles(modes) { modes = it }
        }
        PaperCard {
            PaperHint(
                "Уже есть база с другого телефона — сначала ответь здесь, потом в Настройках " +
                    "открой «База данных»: там будет «Открыть эту базу»."
            )
        }
        PaperButton(
            "Готово",
            onClick = {
                val p = who.build(modes, keepId = null) ?: return@PaperButton
                saving = true
                app.appScope.launch(Dispatchers.IO) {
                    runCatching {
                        app.profileStore.save(p)
                        app.eventLog.add("профиль: ${p.name} (${p.id}), режимы: ${p.modes.joinToString { it.key }}")
                        // Не владелец: автоматы владельца с завода выключены. Ночной
                        // разбор и правка промпта написаны про Сашу и тратят ключ;
                        // синк «Всей жизни» и Дневника смотрит в его Notion.
                        if (!p.owner) {
                            app.settings.setNightReviewEnabled(false)
                            app.settings.setPromptTuneEnabled(false)
                            app.settings.setNotionLife(false)
                            app.settings.setNotionDiary(false)
                        }
                    }.onFailure { saving = false }
                }
            },
            primary = true,
            enabled = who.ready && !saving,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Группа «Кто пользуется» в настройках: тот же выбор и тумблеры режимов. */
@Composable
internal fun ProfileSettings(app: PravkaApp) {
    val profile by app.profileStore.flow.collectAsState()
    val current = profile ?: return
    val who = remember(current.id, current.name, current.female) { WhoState(current) }
    val save: (Profile) -> Unit = { p ->
        app.appScope.launch(Dispatchers.IO) {
            runCatching { app.profileStore.save(p) }
        }
    }
    PaperCard(label = "кто пользуется") {
        WhoPicker(who)
        // «Другой» поправил имя — ключ прежний: на нём его копии и траты.
        val wasOther = Profile.Preset.entries.none { it.id == current.id }
        val next = who.build(current.modes, keepId = current.id.takeIf { who.other && wasOther })
        if (next != null && next != current) {
            Spacer(Modifier.height(8.dp))
            PaperButton("Сохранить", onClick = { save(next) }, primary = true)
        }
        Spacer(Modifier.height(6.dp))
        PaperHint(
            if (current.owner) "Владелец: заводские наличные и счета в Деньгах, базы Notion и промпты — его."
            else "Заводские данные владельца (его наличные и счета, базы Notion) здесь не используются."
        )
    }
    PaperCard(label = "режимы", info = MODES_INFO) {
        ModeToggles(current.modes) { modes -> save(current.copy(modes = modes)) }
    }
}
