package ru.zf.pravka

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import ru.zf.pravka.core.DiskLook
import ru.zf.pravka.core.StackGeometry
import ru.zf.pravka.data.Settings
import ru.zf.pravka.trigger.HeadsetButtonActivity
import ru.zf.pravka.trigger.micStateNow
import ru.zf.pravka.trigger.reloadMicrophone
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.ModeDecor
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperRow
import ru.zf.pravka.ui.PaperSlider
import ru.zf.pravka.ui.PaperToggle
import ru.zf.pravka.ui.RowRule
import ru.zf.pravka.ui.ScreenPad
import ru.zf.pravka.ui.StatusDot
import ru.zf.pravka.ui.tint

// Настройки — пять полок (24.09.2026, «второе издание»).
//
// До этого было восемь гармошек подряд, и рукоятки лежали по логике «в
// каком файле их написали»: ключ Notion — в «Теле», intervals.icu — в
// «Засечке», тумблер кнопки «Д» — вообще во вкладке. Теперь по тому, что
// ищешь:
//   · Режимы — как ведёт себя Правка, Засечка, Дела, Спорт, Еда, Деньги;
//   · Голос и Claude — микрофон, распознавание, модели;
//   · Подключения — ключи и токены, у каждого состояние словами;
//   · Кнопки и вид — какие кнопки на стекле, диск, плашки;
//   · Приложение — служба, обновления, копии ленты, отладка.
// Главный экран — меню строк на один экран; группа открывается своим экраном
// поверх (стопка `Page` в MainActivity), «назад» возвращает в меню.
//
// Сами настройки режима живут рядом со своим режимом (ZasechkaSettings в
// ZasechkaTab, BodySportSettings в SportTab), а тут только собираются. Так
// правка режима не растаскивается по двум файлам.

/** Полка меню настроек. */
internal enum class SettingsShelf(val label: String) {
    MODES("режимы"),
    VOICE("голос и claude"),
    LINKS("подключения"),
    LOOK("кнопки и вид"),
    APP("приложение"),
}

/**
 * Группы. Шестерёнка в шапке каждой вкладки открывает СВОЮ группу
 * (`ModeSettingsScreen`), «Ещё → Настройки» — меню всех, долгое нажатие
 * «Настройки» на плавающей кнопке — группу этой кнопки. [decor] — краска
 * экрана группы: у Засечки янтарная, у Дел синяя.
 */
internal enum class SettingsGroup(
    val title: String,
    val hint: String,
    val shelf: SettingsShelf,
    val glyph: ImageVector,
    val decor: ModeDecor = ModeDecor.SERVICE,
) {
    PROFILE("Кто пользуется", "имя, род, какие режимы включены", SettingsShelf.MODES, Glyphs.Tune),
    PRAVKA("Правка", "проза, контекст, правила в промпте", SettingsShelf.MODES, Glyphs.Pravka, ModeDecor.PRAVKA),
    ZASECHKA("Засечка", "напоминания, категории, автопилот, NFC", SettingsShelf.MODES, Glyphs.Zasechka, ModeDecor.ZASECHKA),
    DELA("Дела", "кнопка «Д», Todoist", SettingsShelf.MODES, Glyphs.Delo, ModeDecor.DELA),
    SPORT("Спорт", "отдых, выгрузка, цель веса, справочник", SettingsShelf.MODES, Glyphs.Sport, ModeDecor.SPORT),
    FOOD("Еда", "цели КБЖУ, лента, intervals", SettingsShelf.MODES, Glyphs.Food, ModeDecor.FOOD),
    MONEY("Деньги", "пуши банка", SettingsShelf.MODES, Glyphs.Money, ModeDecor.MONEY),
    VOICE("Микрофон и распознавание", "телефон или гарнитура, движок", SettingsShelf.VOICE, Glyphs.Mic),
    MODELS("Модели", "какая модель и с каким усилием", SettingsShelf.VOICE, Glyphs.Spark),
    ANTHROPIC("Anthropic", "ключ API", SettingsShelf.LINKS, Glyphs.Key),
    TODOIST("Todoist", "дела и разноска", SettingsShelf.LINKS, Glyphs.Delo, ModeDecor.DELA),
    NOTION("Notion", "план, Дневник, «Вся жизнь»", SettingsShelf.LINKS, Glyphs.Scroll),
    INTERVALS("intervals.icu", "тренировки, сон, вес", SettingsShelf.LINKS, Glyphs.Activity, ModeDecor.SPORT),
    SHEETS("Google Sheets", "таймшит из ленты", SettingsShelf.LINKS, Glyphs.ListLines, ModeDecor.ZASECHKA),
    GOOGLE("Google Drive", "семейный аккаунт: общие Деньги, копии базы", SettingsShelf.LINKS, Glyphs.Cloud),
    BUTTONS("Кнопки на экране", "какие, круг или стопка, размер", SettingsShelf.LOOK, Glyphs.Disk),
    DISK("Вид диска", "стекло, плотности, тени, инерция", SettingsShelf.LOOK, Glyphs.Palette),
    CARDS("Плашки приложения", "темнее, фаска, свет, зерно", SettingsShelf.LOOK, Glyphs.Layers),
    DATA("База данных", "где лежит, переезд в папку, как копировать", SettingsShelf.APP, Glyphs.Archive),
    APP("Обновления и служба", "служба, обновления, копии ленты, отладка", SettingsShelf.APP, Glyphs.Phone),
}

@Composable
internal fun SettingsTab(
    app: PravkaApp,
    serviceEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpen: (SettingsGroup) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ScreenPad.Padding),
        verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
    ) {
        // Служба и обновления — одной строкой сверху: без службы не работает ни
        // одна кнопка, и «есть свежая сборка» должно быть видно, не открывая
        // ничего. Тап — экран «Обновления и служба».
        StatusStrip(app, serviceEnabled, onOpenAccessibilitySettings) { onOpen(SettingsGroup.APP) }
        // База в папке, а доступа к файлам нет — лента пустая не потому, что
        // пустая. Говорим сверху, а не только красной точкой на полке.
        val dbWhere by ru.zf.pravka.data.DataRoot.where.collectAsState()
        if (dbWhere == ru.zf.pravka.data.DataRoot.Where.FOLDER_NO_ACCESS) {
            PaperCard {
                PaperRow(
                    title = "База недоступна",
                    hint = "нет доступа к файлам — лента, еда и деньги не читаются",
                    icon = Glyphs.Archive,
                    badgeTint = MaterialTheme.colorScheme.error,
                    trailing = { StatusDot(false) },
                    onClick = { onOpen(SettingsGroup.DATA) },
                )
            }
        }

        // Группы выключенного в профиле режима не показываются: их настройки
        // ни на что не действуют. Вернуть режим — «Кто пользуется».
        val profile by app.profileStore.flow.collectAsState()
        for (shelf in SettingsShelf.entries) {
            val groups = SettingsGroup.entries.filter { it.shelf == shelf && it != SettingsGroup.APP }
                .filter { g -> g.modes.isEmpty() || g.modes.any { m -> profile?.has(m) ?: true } }
            if (groups.isEmpty()) continue
            PaperCard(label = shelf.label) {
                groups.forEachIndexed { i, g ->
                    if (i > 0) RowRule()
                    val status = groupStatus(app, g)
                    PaperRow(
                        title = g.title,
                        hint = g.hint,
                        icon = g.glyph,
                        badgeTint = if (g.decor == ModeDecor.SERVICE) null
                        else g.decor.tint(MaterialTheme.colorScheme).primary,
                        status = status?.text,
                        statusColor = when (status?.ok) {
                            false -> MaterialTheme.colorScheme.error
                            else -> null
                        },
                        trailing = status?.let { st -> if (st.dot) ({ StatusDot(st.ok) }) else null },
                        onClick = { onOpen(g) },
                    )
                }
            }
        }
        PaperHint(
            stringResource(
                R.string.build_info,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                BuildConfig.BUILD_TIME,
            )
        )
    }
}

/**
 * Режимы, ради которых группа существует; пусто — группа нужна всегда.
 * intervals.icu нужен и Спорту, и Еде (итог дня в wellness).
 */
private val SettingsGroup.modes: Set<ru.zf.pravka.data.Profile.Mode>
    get() = when (this) {
        SettingsGroup.ZASECHKA, SettingsGroup.SHEETS -> setOf(ru.zf.pravka.data.Profile.Mode.ZASECHKA)
        SettingsGroup.DELA, SettingsGroup.TODOIST -> setOf(ru.zf.pravka.data.Profile.Mode.DELA)
        SettingsGroup.SPORT -> setOf(ru.zf.pravka.data.Profile.Mode.SPORT)
        SettingsGroup.FOOD -> setOf(ru.zf.pravka.data.Profile.Mode.FOOD)
        SettingsGroup.MONEY -> setOf(ru.zf.pravka.data.Profile.Mode.MONEY)
        SettingsGroup.INTERVALS -> setOf(ru.zf.pravka.data.Profile.Mode.SPORT, ru.zf.pravka.data.Profile.Mode.FOOD)
        else -> emptySet()
    }

/** Состояние группы для правого края строки меню. */
private class GroupStatus(val text: String, val ok: Boolean? = null, val dot: Boolean = false)

/**
 * Что писать справа от группы. Подключения говорят, есть ли ключ, а где
 * синхронизатор помнит ошибку — её (правило 6: молчаливая механика читается
 * как поломка).
 */
@Composable
private fun groupStatus(app: PravkaApp, g: SettingsGroup): GroupStatus? {
    val s = app.settings
    return when (g) {
        SettingsGroup.VOICE -> {
            val phone by s.phoneMicOnlyFlow.collectAsState(initial = true)
            GroupStatus(if (phone) "телефон" else "гарнитура")
        }
        SettingsGroup.MODELS -> {
            val changed by remember { s.modelChoicesChangedFlow() }.collectAsState(initial = 0)
            if (changed > 0) GroupStatus("своих: $changed") else GroupStatus("заводские")
        }
        SettingsGroup.ANTHROPIC -> {
            val key by s.apiKeyFlow.collectAsState(initial = "")
            keyStatus(key.isNotBlank())
        }
        SettingsGroup.TODOIST -> {
            val token by s.todoistTokenFlow.collectAsState(initial = "")
            keyStatus(token.isNotBlank())
        }
        SettingsGroup.NOTION -> {
            val token by s.notionTokenFlow.collectAsState(initial = "")
            val err = remember(token) { app.notionPlanSync.lastError() }
            when {
                token.isBlank() -> keyStatus(false)
                err.isNotBlank() -> GroupStatus("ошибка", ok = false, dot = true)
                else -> keyStatus(true)
            }
        }
        SettingsGroup.INTERVALS -> {
            val athlete by s.icuAthleteFlow.collectAsState(initial = "")
            val key by s.icuKeyFlow.collectAsState(initial = "")
            keyStatus(athlete.isNotBlank() && key.isNotBlank())
        }
        SettingsGroup.GOOGLE -> {
            val acc by app.googleAuth.account.collectAsState()
            val sync by app.moneyDriveSync.status.collectAsState()
            val copy by app.driveBackup.status.collectAsState()
            when {
                acc == null -> GroupStatus("нет входа", ok = null, dot = true)
                sync.error.isNotBlank() || copy.error.isNotBlank() -> GroupStatus("ошибка", ok = false, dot = true)
                else -> GroupStatus("подключён", ok = true, dot = true)
            }
        }
        SettingsGroup.SHEETS -> {
            val url by s.zWebhookFlow.collectAsState(initial = "")
            if (url.isBlank()) GroupStatus("не задано", ok = null, dot = true) else GroupStatus("задано", ok = true, dot = true)
        }
        SettingsGroup.BUTTONS -> {
            val z by s.zEnabledFlow.collectAsState(initial = true)
            val d by s.rEnabledFlow.collectAsState(initial = true)
            val m by s.mEnabledFlow.collectAsState(initial = true)
            val e by s.tEnabledFlow.collectAsState(initial = false)
            val p by app.profileStore.flow.collectAsState()
            fun on(mode: ru.zf.pravka.data.Profile.Mode) = p?.has(mode) ?: true
            GroupStatus(
                listOfNotNull(
                    "П",
                    "З".takeIf { z && on(ru.zf.pravka.data.Profile.Mode.ZASECHKA) },
                    "Д".takeIf { d && on(ru.zf.pravka.data.Profile.Mode.DELA) },
                    "₽".takeIf { m && on(ru.zf.pravka.data.Profile.Mode.MONEY) },
                    "Е".takeIf { e && on(ru.zf.pravka.data.Profile.Mode.FOOD) },
                ).joinToString(" ")
            )
        }
        SettingsGroup.PROFILE -> {
            val p by app.profileStore.flow.collectAsState()
            p?.let { GroupStatus("${it.name} · ${it.modes.size + 1} из ${ru.zf.pravka.data.Profile.Mode.entries.size + 1}") }
        }
        SettingsGroup.DATA -> {
            val where by ru.zf.pravka.data.DataRoot.where.collectAsState()
            when (where) {
                ru.zf.pravka.data.DataRoot.Where.PRIVATE -> GroupStatus("в памяти", ok = null, dot = true)
                ru.zf.pravka.data.DataRoot.Where.FOLDER -> GroupStatus("в папке", ok = true, dot = true)
                ru.zf.pravka.data.DataRoot.Where.FOLDER_NO_ACCESS -> GroupStatus("нет доступа", ok = false, dot = true)
            }
        }
        else -> null
    }
}

private fun keyStatus(present: Boolean) =
    if (present) GroupStatus("есть", ok = true, dot = true) else GroupStatus("нет ключа", ok = null, dot = true)

/** Строка «служба · обновления» над меню. */
@Composable
private fun StatusStrip(
    app: PravkaApp,
    serviceEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpen: () -> Unit,
) {
    val state by app.updates.state.collectAsState()
    val latest = state.latest
    val fresh = latest != null && app.updates.isNewer(latest)
    PaperCard {
        PaperRow(
            title = if (serviceEnabled) "Служба работает" else "Служба выключена",
            hint = when {
                state.ready?.exists() == true && latest != null -> "готово к установке: ${latest.versionName}"
                fresh && latest != null -> "есть сборка ${latest.versionName}"
                state.error.isNotBlank() -> stringResource(R.string.upd_error, state.error)
                else -> "обновлений нет · ${BuildConfig.VERSION_NAME}"
            },
            icon = Glyphs.Phone,
            badgeTint = if (serviceEnabled) ru.zf.pravka.ui.MicroOk else MaterialTheme.colorScheme.error,
            trailing = { StatusDot(if (serviceEnabled) (if (fresh) null else true) else false) },
            onClick = onOpen,
        )
        if (!serviceEnabled) {
            Spacer(Modifier.height(6.dp))
            PaperButton(
                stringResource(R.string.service_enable),
                onClick = onOpenAccessibilitySettings,
                primary = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Содержимое одной группы. [onOpen] — переход в соседнюю группу (Дела → Todoist). */
@Composable
private fun GroupContent(
    app: PravkaApp,
    group: SettingsGroup,
    serviceEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpen: (SettingsGroup) -> Unit,
) {
    when (group) {
        SettingsGroup.PROFILE -> ProfileSettings(app)
        SettingsGroup.PRAVKA -> PravkaSettings(app)
        SettingsGroup.ZASECHKA -> ZasechkaSettings(app)
        SettingsGroup.DELA -> DelaSettings(app, onOpen)
        SettingsGroup.SPORT -> BodySportSettings(app)
        SettingsGroup.FOOD -> BodyFoodSettings(app)
        SettingsGroup.MONEY -> MoneySettings(app)
        SettingsGroup.VOICE -> VoiceSettings(app)
        SettingsGroup.MODELS -> ModelsSettings(app)
        SettingsGroup.ANTHROPIC -> AnthropicSettings(app)
        SettingsGroup.TODOIST -> TodoistSettings(app)
        SettingsGroup.NOTION -> NotionSettings(app)
        SettingsGroup.INTERVALS -> IntervalsSettings(app)
        SettingsGroup.SHEETS -> ZasechkaSheetsSettings(app)
        SettingsGroup.GOOGLE -> GoogleDriveSettings(app)
        SettingsGroup.BUTTONS -> ButtonsSettings(app)
        SettingsGroup.DISK -> DiskSettings(app)
        SettingsGroup.CARDS -> CardsSettings(app)
        SettingsGroup.DATA -> DataSettings(app)
        SettingsGroup.APP -> AppSettings(app, serviceEnabled, onOpenAccessibilitySettings)
    }
}

/**
 * Экран одной группы — за шестерёнкой вкладки или строкой меню. Владелец
 * (15.09.2026): «шестерёнка открывает именно настройки Правки, Засечки, Тела,
 * а общие настройки живут в Настройках». Только своя группа, в своих плашках.
 */
@Composable
internal fun ModeSettingsScreen(
    app: PravkaApp,
    group: SettingsGroup,
    serviceEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit = {},
    onOpen: (SettingsGroup) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ScreenPad.Padding),
        verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
    ) {
        GroupContent(app, group, serviceEnabled, onOpenAccessibilitySettings, onOpen)
    }
}

// ---------------------------------------------------------------------------
// Приложение: служба, обновления, копии, отладка
// ---------------------------------------------------------------------------

@Composable
private fun AppSettings(app: PravkaApp, serviceEnabled: Boolean, onOpenAccessibilitySettings: () -> Unit) {
    val settings = app.settings
    PaperCard(label = "служба", info = stringResource(R.string.service_hint)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(serviceEnabled)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(if (serviceEnabled) R.string.service_status_on else R.string.service_status_off),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
        if (!serviceEnabled) {
            Spacer(Modifier.height(8.dp))
            PaperButton(stringResource(R.string.service_enable), onClick = onOpenAccessibilitySettings, primary = true)
        }
    }
    UpdatesCard(app)
    HistoryFixesCard(app)
    PaperCard(label = "резервные копии ленты") { BackupsSection(app) }
    PaperCard(label = "отладка") {
        val debugLog by settings.debugLogFlow.collectAsState(initial = false)
        PaperToggle(
            title = "Писать запросы к Claude в лог",
            checked = debugLog,
            onCheckedChange = { on -> app.appScope.launch { settings.setDebugLog(on) } },
            info = "Каждый запрос целиком — правка, Засечка, Тело, Еда: стабильная и " +
                "переменная части с размерами. Смотреть и выгружать — «Ещё → Логи», " +
                "«Запросы к Claude». Лог растёт быстро, держи включённым, пока смотришь.",
        )
    }
    // Что дуга знает о дорогах (владелец: «ты точно рассчитал средние? И ты
    // точно учитываешь модель и количество знаков? Короче, посмотри»).
    // Показываем числа: пересказывать их было бы ответом ни о чём.
    PaperCard(
        label = "что знает дуга прогресса",
        info = "Ожидание считается по своей истории, отдельно на каждую пару «дорога + " +
            "модель»: основание плюс цена знака. Дорога Правки взяла своё прошлое " +
            "из журнала правок — он ведётся с июля, учиться заново было незачем. " +
            "Остальные начинают с заводской прикидки, посчитанной по тому же " +
            "журналу; она тает и к дюжине своих замеров исчезает. Сменил модель " +
            "дороги — старое тускнеет за неделю работы, не мгновенно.",
    ) {
        val pace = remember { app.paceStore.summary() }
        for (line in pace) Text("· $line", style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Переразбор истории (core/HistoryFixes.kt): что новая логика переделала в
 * накопленном при первом запуске сборки — словами, а не молча (правило 6).
 */
@Composable
private fun HistoryFixesCard(app: PravkaApp) {
    val context = LocalContext.current
    val fixes = ru.zf.pravka.core.HistoryFixes
    var done by remember { mutableStateOf<List<ru.zf.pravka.core.HistoryFixes.Done>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        if (!busy) done = withContext(Dispatchers.IO) { runCatching { fixes.done(context) }.getOrDefault(emptyList()) }
    }
    val pending = fixes.pending(fixes.STEPS, done)
    PaperCard(
        label = "переразбор истории",
        info = "Правка разбора, которая меняет уже накопленное, приходит с шагом: в первый запуск " +
            "сборки он проходит по истории своего вида и выводит её заново из сырья — текста пуша, " +
            "надиктовки. Пройденный шаг не повторяется никогда. Упавший пробуется снова при " +
            "следующем запуске, но не больше ${fixes.MAX_ATTEMPTS} раз — и шаг, уронивший приложение, " +
            "тоже: отметка «начал» пишется до шага. Потом шаг откладывается до кнопки «Повторить». " +
            "Решения владельца не трогаются, записи не удаляются, до шага снимается копия его файлов.",
    ) {
        if (done.isEmpty() && pending.isEmpty()) PaperHint("Шагов пока не было.")
        val stamp = remember { java.text.SimpleDateFormat("d MMMM, HH:mm", Locale.forLanguageTag("ru")) }
        for (d in done.sortedByDescending { it.at }.take(8)) {
            Text(d.title, style = MaterialTheme.typography.bodyMedium)
            val at = stamp.format(java.util.Date(d.at))
            PaperHint(
                when {
                    d.ok -> "$at · просмотрено ${d.looked}, поправлено ${d.changed}" +
                        (if (d.note.isNotBlank()) " · ${d.note}" else "")
                    d.gaveUp -> "$at · отложен после ${d.attempts} попыток: ${d.error.ifBlank { "оборвался посреди шага" }}"
                    d.running -> "$at · начат, попытка ${d.attempts} из ${fixes.MAX_ATTEMPTS}"
                    else -> "$at · упал (попытка ${d.attempts} из ${fixes.MAX_ATTEMPTS}): ${d.error} — повторится при следующем запуске"
                },
                color = if (d.ok || d.running) null else MaterialTheme.colorScheme.error,
            )
            if (d.gaveUp) {
                ru.zf.pravka.ui.PaperTextButton("Повторить", icon = Glyphs.Refresh, enabled = !busy, onClick = {
                    busy = true
                    app.appScope.launch(Dispatchers.IO) {
                        runCatching { fixes.retry(app, d.id) { line -> app.eventLog.add(line) } }
                        busy = false
                    }
                })
            }
            Spacer(Modifier.height(4.dp))
        }
        val waiting = pending.count { p -> done.none { it.id == p.id } }
        if (waiting > 0) PaperHint("Ждут запуска: $waiting")
    }
}

/**
 * Обновления: приложение само ходит в ветку `apk-builds`. Здесь видно, что
 * стоит сейчас, что лежит в ветке, и есть ручная кнопка — на случай «я только
 * что запушил, хочу прямо сейчас».
 */
@Composable
private fun UpdatesCard(app: PravkaApp) {
    val context = LocalContext.current
    val updates = app.updates
    val scope = app.appScope
    val state by updates.state.collectAsState()
    val auto by app.settings.updAutoFlow.collectAsState(initial = true)
    val mobile by app.settings.updMobileFlow.collectAsState(initial = false)
    val stamp = remember {
        java.text.SimpleDateFormat("d MMMM, HH:mm", Locale.forLanguageTag("ru"))
    }
    val latest = state.latest
    val fresh = latest != null && updates.isNewer(latest)
    // Сборка соседней ветки: номер больше, но это другая работа, не «новее».
    val otherLine = latest != null && latest.versionCode > BuildConfig.VERSION_CODE && !fresh
    val branchPref by app.settings.updBranchFlow.collectAsState(initial = "")
    var branchDraft by remember(branchPref) { mutableStateOf(branchPref) }

    PaperCard(label = stringResource(R.string.upd_title)) {
        Text(
            when {
                latest == null -> stringResource(R.string.upd_never)
                fresh -> stringResource(R.string.upd_found, latest.versionName, latest.builtAt)
                otherLine -> stringResource(R.string.upd_other_branch, latest.versionName, latest.branch)
                else -> stringResource(R.string.upd_latest, BuildConfig.VERSION_NAME)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state.lastCheck > 0) {
            PaperHint(stringResource(R.string.upd_checked, stamp.format(java.util.Date(state.lastCheck))))
        }
        if (state.error.isNotBlank()) {
            Text(
                stringResource(R.string.upd_error, state.error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.progress < 0) {
                PaperButton(
                    stringResource(if (state.checking) R.string.upd_checking else R.string.upd_check),
                    icon = Glyphs.Refresh,
                    enabled = !state.checking,
                    onClick = { scope.launch { updates.check(force = true) } },
                )
            }
            Spacer(Modifier.weight(1f))
            val ready = state.ready?.takeIf { it.exists() }
            when {
                state.progress >= 0 -> PaperButton(
                    stringResource(R.string.upd_downloading, state.progress),
                    onClick = {},
                    primary = true,
                    enabled = false,
                )
                ready != null && latest != null -> PaperButton(
                    stringResource(R.string.upd_install, latest.versionName),
                    icon = Glyphs.Download,
                    primary = true,
                    onClick = {
                        if (updates.canInstall()) {
                            runCatching { context.startActivity(updates.installIntent(ready)) }
                        } else {
                            runCatching { context.startActivity(updates.allowInstallIntent()) }
                        }
                    },
                )
                fresh && latest != null -> PaperButton(
                    stringResource(R.string.upd_download, latest.versionName),
                    icon = Glyphs.Download,
                    primary = true,
                    onClick = { scope.launch { updates.download(latest) } },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        PaperToggle(
            title = stringResource(R.string.upd_auto),
            checked = auto,
            onCheckedChange = { scope.launch { app.settings.setUpdAuto(it) } },
        )
        PaperToggle(
            title = stringResource(R.string.upd_mobile),
            checked = mobile,
            onCheckedChange = { scope.launch { app.settings.setUpdMobile(it) } },
        )
        // Линия обновления. Заводская — константа `pravka`; поле осталось
        // на случай переезда линии, чтобы вернуть обновления одной строкой
        // без новой сборки.
        Spacer(Modifier.height(6.dp))
        PaperField(
            value = branchDraft,
            onValueChange = { branchDraft = it },
            label = stringResource(R.string.upd_branch_label),
            placeholder = ru.zf.pravka.data.Updates.LINE,
            trailing = {
                ru.zf.pravka.ui.InfoButton(
                    "Ветка обновлений",
                    stringResource(R.string.upd_branch_hint, updates.line.ifBlank { "—" }),
                )
            },
        )
        if (branchDraft.trim() != branchPref) {
            Row {
                Spacer(Modifier.weight(1f))
                PaperButton(stringResource(R.string.settings_save), primary = true, onClick = {
                    scope.launch {
                        app.settings.setUpdBranch(branchDraft)
                        updates.check(force = true)
                    }
                })
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Кнопки на экране · вид диска · плашки
// ---------------------------------------------------------------------------

/**
 * Кнопки на экране: какие стоят, как стоят и какого размера. Тумблеры всех
 * кнопок — здесь рядом (24.09.2026); до этого «З» жила в настройках Засечки,
 * «Д» — во вкладке Дел, «Е» — в настройках Еды, «₽» — в Деньгах.
 */
@Composable
private fun ButtonsSettings(app: PravkaApp) {
    val scope = app.appScope
    val settings = app.settings
    val z by settings.zEnabledFlow.collectAsState(initial = true)
    val d by settings.rEnabledFlow.collectAsState(initial = true)
    val m by settings.mEnabledFlow.collectAsState(initial = true)
    val e by settings.tEnabledFlow.collectAsState(initial = false)
    val diskMode by settings.diskModeFlow.collectAsState(initial = true)
    val fabSize by settings.fabSizeFlow.collectAsState(initial = Settings.FAB_SIZE_DEFAULT)
    val fabAlpha by settings.fabAlphaFlow.collectAsState(initial = Settings.FAB_ALPHA_DEFAULT)
    var sizeSlider by remember(fabSize) { mutableStateOf(fabSize.toFloat()) }
    var alphaSlider by remember(fabAlpha) { mutableStateOf(fabAlpha) }

    // Кнопка выключенного в профиле режима не встанет, что бы ни стояло тут:
    // тумблер гаснет и говорит почему (вернуть режим — «Кто пользуется»).
    val profile by app.profileStore.flow.collectAsState()
    fun on(mode: ru.zf.pravka.data.Profile.Mode) = profile?.has(mode) ?: true
    val off = "режим выключен в «Кто пользуется»"
    PaperCard(label = "какие кнопки", info = "«П» стоит всегда: без неё нет ни диктовки, ни шестерёнки с веером. " +
        "Кнопка режима, выключенного в «Кто пользуется», не ставится вовсе.") {
        val zOn = on(ru.zf.pravka.data.Profile.Mode.ZASECHKA)
        val dOn = on(ru.zf.pravka.data.Profile.Mode.DELA)
        val mOn = on(ru.zf.pravka.data.Profile.Mode.MONEY)
        val eOn = on(ru.zf.pravka.data.Profile.Mode.FOOD)
        PaperToggle("Засечка «З»", z && zOn, { v -> scope.launch { settings.setZEnabled(v) } },
            hint = if (zOn) "видна всегда, в любом приложении" else off, enabled = zOn)
        PaperToggle("Дело «Д»", d && dOn, { v -> scope.launch { settings.setREnabled(v) } },
            hint = if (dOn) "наговорил — задачи в Todoist" else off, enabled = dOn)
        PaperToggle("Деньги «₽»", m && mOn, { v -> scope.launch { settings.setMEnabled(v) } },
            hint = if (mOn) "наговорил трату — плашка с суммой и «ОК»" else off, enabled = mOn)
        PaperToggle(
            "Еда «Е»", e && eOn, { v -> scope.launch { settings.setTEnabled(v) } },
            hint = if (eOn) "пятая: подходы, еда, зарядка, вопрос" else off,
            info = "Одна на всё тело: намерение определяет модель. С 19.09.2026 выключена с завода.",
            enabled = eOn,
        )
    }

    // Круг или стопка (владелец, 19.09.2026): явный выбор из двух, не тумблер
    // «вместо» — «сделай переключалку в настройках: круг и стопка». Стопка
    // остаётся живой дорогой: «если не получится — откатим» одним движением.
    PaperCard(
        label = "как стоят",
        info = "Круг: «П» сверху, «З» снизу, «Д» дальше — вокруг шестерёнки на стекле. " +
            "Повёл кнопку по кругу — крутится весь диск и щёлкает, махнул сильнее — " +
            "провернётся дальше. Тянешь за стекло — диск переезжает; двумя пальцами — тоже, " +
            "где бы ни взял. Шестерёнка: тап — веер быстрых настроек, двойной тап — всё в " +
            "точку «П». Двойной тап по свободному стеклу — тот же веер; долгое нажатие на " +
            "стекло — выдвинуть диск целиком. Стопка: прежний столбик с ручкой-галочкой.",
    ) {
        ChipRow {
            PaperChip("Круг", selected = diskMode, onClick = { scope.launch { settings.setDiskMode(true) } }, icon = Glyphs.Disk)
            PaperChip("Стопка", selected = !diskMode, onClick = { scope.launch { settings.setDiskMode(false) } }, icon = Glyphs.ListLines)
        }
        Spacer(Modifier.height(6.dp))
        if (diskMode) {
            // Автоуборка диска (владелец, 19.09.2026): «через 30 секунд диск пришёл к
            // ближайшему краю и прилепился, так что остались только засечка и правка».
            val diskTuck by settings.diskTuckFlow.collectAsState(initial = true)
            PaperToggle(
                title = "Убирать диск к краю",
                checked = diskTuck,
                onCheckedChange = { on -> scope.launch { settings.setDiskTuck(on) } },
                hint = "через полминуты без касаний",
                info = "Полминуты без касаний — диск сам уезжает к ближайшему краю и " +
                    "поворачивается домой. Стекло при этом уходит ЗА край, а «П» и «З» " +
                    "выдавливаются из него и остаются на экране целиком; между ними " +
                    "виден краешек диска со стрелкой. Тап по стрелке выводит половину " +
                    "диска, второй тап подряд — весь. Посреди записи или разбора не " +
                    "уезжает никогда. Выключено — стоит, где оставил, и как повернул.",
            )
        } else {
            val stackIdle by settings.stackIdleFlow.collectAsState(initial = true)
            PaperToggle(
                title = "Складывать кнопки в стопку",
                checked = stackIdle,
                onCheckedChange = { on -> scope.launch { settings.setStackIdle(on) } },
                hint = "через полминуты без касаний",
                info = "Полминуты без касаний остаются две: «П» и «З» — они на своих местах " +
                    "и работают как обычно, первый тап сразу пишет. «Д» и «Е» уезжают " +
                    "в «З» и пропадают; вернуть их — серая ручка с галочкой под " +
                    "стопкой. Вторая серая ручка, с многоточием, стоит над «П»: убирает " +
                    "с экрана ВСЁ разом. Посреди записи или разбора кнопки сами не " +
                    "складываются никогда.",
            )
        }
    }

    PaperCard(label = "размер и лицо") {
        PaperSlider(
            title = stringResource(R.string.settings_fab_title),
            valueText = "${sizeSlider.toInt()} dp",
            value = sizeSlider,
            onValueChange = { sizeSlider = it },
            onValueChangeFinished = { scope.launch { settings.setFabSize(sizeSlider.toInt()) } },
            valueRange = 36f..72f,
        )
        PaperSlider(
            title = "Прозрачность в покое",
            valueText = "${(alphaSlider * 100).toInt()} %",
            value = alphaSlider,
            onValueChange = { alphaSlider = it },
            onValueChangeFinished = { scope.launch { settings.setFabAlpha(alphaSlider) } },
            valueRange = 0.15f..1f,
        )
        val modeIcons by settings.modeIconsFlow.collectAsState(initial = false)
        PaperToggle(
            title = "Значки вместо букв",
            checked = modeIcons,
            onCheckedChange = { on -> scope.launch { settings.setModeIcons(on) } },
            hint = "перо, часы, галочка, рубль, вилка",
        )
        // Бегущая строка у всех кнопок — одна ширина (владелец, 15.09:
        // «поставим в общих настройках размер плашки по горизонтали»).
        val tickerWidth by settings.tickerWidthFlow.collectAsState(initial = Settings.TICKER_WIDTH_DEFAULT)
        var tickerSlider by remember(tickerWidth) { mutableStateOf(tickerWidth.toFloat()) }
        PaperSlider(
            title = "Бегущая строка",
            valueText = "${tickerSlider.toInt()} dp",
            value = tickerSlider,
            onValueChange = { tickerSlider = it },
            onValueChangeFinished = { scope.launch { settings.setTickerWidth(tickerSlider.toInt()) } },
            valueRange = Settings.TICKER_WIDTH_MIN.toFloat()..Settings.TICKER_WIDTH_MAX.toFloat(),
            info = "Одна ширина на все кнопки; шире экрана не станет — кнопка рядом остаётся видна.",
        )
    }
}

/**
 * Вид диска (владелец, 19.09.2026: «и нужно всё это в настройки.
 * Прозрачность, размер»). Плотности до первого касания ползунка НЕ записаны:
 * пока ключа нет, их считает core/DiskLook.kt, следя за прозрачностью кнопок
 * и за тем, какое стекло. Ползунок показывает это счётное число.
 */
@Composable
private fun DiskSettings(app: PravkaApp) {
    val scope = app.appScope
    val settings = app.settings
    val diskMode by settings.diskModeFlow.collectAsState(initial = true)
    val fabAlpha by settings.fabAlphaFlow.collectAsState(initial = Settings.FAB_ALPHA_DEFAULT)
    val diskLight by settings.diskLightFlow.collectAsState(initial = true)

    if (!diskMode) {
        PaperCard { PaperHint("Кнопки сейчас стоят стопкой — вид диска заработает, когда выберешь «Круг» в «Кнопках на экране».") }
    }

    // Светлее или темнее (владелец, 19.09.2026, ночь): «давай его сделаем
    // наоборот, светлее, чем бэкграунд. А то теряется иногда».
    PaperCard(
        label = "стекло",
        info = "Светлее — тарелка под кнопками из бумаги: на тёмных экранах диск не " +
            "теряется. Темнее — прежние чернила. Тень под стеклом в обоих случаях " +
            "тёмная: на светлом фоне диск отделяет от него именно она. Переключается " +
            "на живом диске, смотреть лучше прямо на том экране, где он терялся.",
    ) {
        ChipRow {
            PaperChip("Светлее", selected = diskLight, onClick = { scope.launch { settings.setDiskLight(true) } })
            PaperChip("Темнее", selected = !diskLight, onClick = { scope.launch { settings.setDiskLight(false) } })
        }
        Spacer(Modifier.height(4.dp))
        // Три тумблера вида и поведения (владелец, 20.09.2026): «матовое стекло
        // делаем, но с выключением в настройках», «рельс давай попробуем… тоже с
        // выключением», «инерция выглядит круто. Делаем. Выключалкой в настройках».
        val frost by settings.diskFrostFlow.collectAsState(initial = true)
        PaperToggle(
            "Матовое стекло", frost, { on -> scope.launch { settings.setDiskFrost(on) } },
            info = "Мелкое зерно по тарелке: стекло перестаёт быть плёнкой. Системное " +
                "размытие фона сюда не годится — оно размывает ПРЯМОУГОЛЬНИК окна, а " +
                "окно у круглой тарелки квадратное, и вокруг диска висел бы размытый квадрат.",
        )
        val rail by settings.diskRailFlow.collectAsState(initial = true)
        PaperToggle(
            "Рельс под кнопками", rail, { on -> scope.launch { settings.setDiskRail(on) } },
            info = "Канавка по кольцу, на котором сидят кнопки: видна в промежутках между " +
                "ними, и глазу сразу понятно, что диск крутится, а не просто лежит.",
        )
    }

    PaperCard(label = "размеры") {
        val diskGap by settings.diskGapFlow.collectAsState(initial = Settings.DISK_GAP_DEFAULT)
        var gapSlider by remember(diskGap) { mutableStateOf(diskGap.toFloat()) }
        PaperSlider(
            title = "Просвет",
            valueText = "${gapSlider.toInt()} dp",
            value = gapSlider,
            onValueChange = { gapSlider = it },
            onValueChangeFinished = { scope.launch { settings.setDiskGap(gapSlider.toInt()) } },
            valueRange = Settings.DISK_GAP_MIN.toFloat()..Settings.DISK_GAP_MAX.toFloat(),
            info = "Просвет между шестерёнкой и кнопками; от него считается всё кольцо и " +
                "тарелка под ним. Больше просвет — шире диск и дальше кнопки друг от друга.",
        )
        val diskGear by settings.diskGearFlow.collectAsState(initial = StackGeometry.GEAR_PCT_DEFAULT)
        var gearSlider by remember(diskGear) { mutableStateOf(diskGear.toFloat()) }
        PaperSlider(
            title = "Шестерёнка",
            valueText = "${gearSlider.toInt()} % от кнопки",
            value = gearSlider,
            onValueChange = { gearSlider = it },
            onValueChangeFinished = { scope.launch { settings.setDiskGear(gearSlider.toInt()) } },
            valueRange = StackGeometry.GEAR_PCT_MIN.toFloat()..StackGeometry.GEAR_PCT_MAX.toFloat(),
            info = "Она же кружки веера. Заводские 72 %; меньше 45 % по ней трудно попасть.",
        )
    }

    PaperCard(label = "плотности") {
        val plateOverride by settings.diskPlateAlphaFlow.collectAsState(initial = null)
        val plateAuto = DiskLook.plateAlpha(fabAlpha, diskLight)
        var plateSlider by remember(plateOverride, plateAuto) { mutableStateOf(plateOverride ?: plateAuto) }
        PaperSlider(
            title = "Стекло",
            valueText = "${(plateSlider * 100).toInt()} %" + if (plateOverride == null) " · по счёту" else "",
            value = plateSlider,
            onValueChange = { plateSlider = it },
            onValueChangeFinished = { scope.launch { settings.setDiskPlateAlpha(plateSlider) } },
            valueRange = 0f..1f,
            info = "Сама тарелка. Ноль — стекла нет, остаются кнопки и тень под ними.",
        )
        val faceOverride by settings.diskFaceAlphaFlow.collectAsState(initial = null)
        val faceAuto = DiskLook.faceAlpha(fabAlpha, onDisk = true)
        var faceSlider by remember(faceOverride, faceAuto) { mutableStateOf(faceOverride ?: faceAuto) }
        PaperSlider(
            title = "Кнопки на диске",
            valueText = "${(faceSlider * 100).toInt()} %" + if (faceOverride == null) " · по счёту" else "",
            value = faceSlider,
            onValueChange = { faceSlider = it },
            onValueChangeFinished = { scope.launch { settings.setDiskFaceAlpha(faceSlider) } },
            valueRange = 0.2f..1f,
            info = "Только на диске: в стопке у кнопки своя прозрачность. " +
                "Полупрозрачная кнопка на светлом стекле читается как лежащая ПОД ним — " +
                "отсюда и ручка.",
        )
        val socketOverride by settings.diskSocketAlphaFlow.collectAsState(initial = null)
        val socketAuto = DiskLook.socketAlpha(plateSlider, diskLight)
        var socketSlider by remember(socketOverride, socketAuto) { mutableStateOf(socketOverride ?: socketAuto) }
        PaperSlider(
            title = "Тени кнопок",
            valueText = "${(socketSlider * 100).toInt()} %" + if (socketOverride == null) " · по счёту" else "",
            value = socketSlider,
            onValueChange = { socketSlider = it },
            onValueChangeFinished = { scope.launch { settings.setDiskSocketAlpha(socketSlider) } },
            valueRange = 0f..0.6f,
            info = "Ноль — теней нет. Они и делают кнопки лежащими НА стекле, а не под ним.",
        )
    }

    PaperCard(label = "движение") {
        val inertia by settings.diskInertiaFlow.collectAsState(initial = true)
        PaperToggle(
            "Инерция при переезде", inertia, { on -> scope.launch { settings.setDiskInertia(on) } },
            info = "Везёшь диск вбок — кольцо проворачивается, как колесо по поверхности. " +
                "Сто процентов — настоящее качение без проскальзывания; заводские " +
                "пятьдесят, потому что на полном взмахе через экран кнопки успевают " +
                "уехать из-под руки. На отпускании диск всё равно щёлкает по четверти.",
        )
        if (inertia) {
            val roll by settings.diskRollFlow.collectAsState(initial = Settings.DISK_ROLL_DEFAULT)
            var rollSlider by remember(roll) { mutableStateOf(roll) }
            PaperSlider(
                title = "Охота катиться",
                valueText = "${(rollSlider * 100).toInt()} %",
                value = rollSlider,
                onValueChange = { rollSlider = it },
                onValueChangeFinished = { scope.launch { settings.setDiskRoll(rollSlider) } },
                valueRange = 0f..1.5f,
            )
        }
    }

    Row {
        Spacer(Modifier.weight(1f))
        PaperButton(
            "Вернуть вид диска в счёт",
            icon = Glyphs.Undo,
            onClick = { scope.launch { settings.resetDiskLook() } },
        )
    }
    PaperHint(
        "Размеры — к заводским, плотности — обратно к счёту: они снова поедут за " +
            "прозрачностью кнопок и за выбором «Светлее · Темнее»."
    )
}

/**
 * Плашки приложения — те же слои, что у стекла (владелец, 20.09.2026:
 * «потемнее и с такими же эффектами, как и диск. И это должно быть в
 * настройках отдельных»). Видно сразу: эта плашка тоже из них.
 */
@Composable
private fun CardsSettings(app: PravkaApp) {
    val scope = app.appScope
    val settings = app.settings
    PaperCard(
        label = "плашки",
        info = "Те же три слоя, что у стекла диска, и та же логика света. Числа у них " +
            "свои: плашку разглядывают вблизи, и незаметное на диске тут " +
            "становится заметным.",
    ) {
        val cardDark by settings.cardDarkFlow.collectAsState(initial = Settings.CARD_DARK_DEFAULT)
        var darkSlider by remember(cardDark) { mutableStateOf(cardDark) }
        PaperSlider(
            title = "Темнее",
            valueText = "на ${(darkSlider * 100).toInt()} %",
            value = darkSlider,
            onValueChange = { darkSlider = it },
            onValueChangeFinished = { scope.launch { settings.setCardDark(darkSlider) } },
            valueRange = 0f..0.6f,
        )
        val cardBevel by settings.cardBevelFlow.collectAsState(initial = true)
        PaperToggle("Фаска по кромке", cardBevel, { on -> scope.launch { settings.setCardBevel(on) } })
        val cardLight by settings.cardLightFlow.collectAsState(initial = true)
        PaperToggle("Свет сверху", cardLight, { on -> scope.launch { settings.setCardLight(on) } })
        val cardGrain by settings.cardGrainFlow.collectAsState(initial = true)
        PaperToggle("Зерно", cardGrain, { on -> scope.launch { settings.setCardGrain(on) } })
    }
}

// ---------------------------------------------------------------------------
// Голос, ключ Anthropic
// ---------------------------------------------------------------------------

/**
 * Микрофон и распознавание — один движок на все диктовки, поэтому своя
 * группа, а не часть «Правки» (24.09.2026).
 */
@Composable
private fun VoiceSettings(app: PravkaApp) {
    val scope = app.appScope
    val settings = app.settings
    PaperCard(label = "микрофон") {
        val phoneMicOnly by settings.phoneMicOnlyFlow.collectAsState(initial = true)
        ChipRow {
            PaperChip("Телефон", selected = phoneMicOnly, onClick = { scope.launch { settings.setPhoneMicOnly(true) } }, icon = Glyphs.Phone)
            PaperChip("Гарнитура", selected = !phoneMicOnly, onClick = { scope.launch { settings.setPhoneMicOnly(false) } }, icon = Glyphs.Mic)
            ru.zf.pravka.ui.InfoButton(
                "Кто слушает",
                "То же самое переключает кружок микрофона в веере шестерёнки: телефончик — " +
                    "слушает телефон, Bluetooth машины и наушники диктовку не перехватывают, в " +
                    "дороге Правка слышит тебя, а не салон; наушники — перед тейком " +
                    "поднимается канал гарнитуры и слушает её микрофон, после тейка " +
                    "канал опускается. Выбирает не подключение, а ты: гарнитура на шее " +
                    "слышит хуже кармана. Не подключена — кружок бледный, слушает телефон.",
            )
        }
        // «Перезагрузить микрофон» (владелец, 22.09.2026: «подключаюсь к машине, и
        // он не слышит… а потом каким-то странным образом начинает»). Кнопка
        // рассказывает, что нашла и что сделала: тишина после нажатия читалась бы
        // как вторая поломка поверх первой.
        Spacer(Modifier.height(8.dp))
        val micContext = LocalContext.current
        var micReport by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PaperButton("Перезагрузить микрофон", icon = Glyphs.Refresh, onClick = {
                micReport = reloadMicrophone(micContext)
                scope.launch {
                    // Маршрут переезжает не мгновенно — состояние спрашиваем, когда он встал.
                    kotlinx.coroutines.delay(900)
                    micReport = micReport + "\nСтало: " + micStateNow(micContext)
                }
            })
            ru.zf.pravka.ui.InfoButton(
                "Перезагрузить микрофон",
                "Возвращает маршрут звука системе (машина держит канал хендс-фри — и " +
                    "распознаватель слушает микрофон у лобового, а не тебя), снимает " +
                    "заглушку с микрофона, снимает залипшее удержание и заново заводит " +
                    "распознаватель. Идёт запись — сначала останови тейк. Во время " +
                    "разговора маршрут не трогается вовсе. То же самое — долгим нажатием " +
                    "на кружок микрофона в веере шестерёнки: в машине это ближе, чем настройки.",
            )
        }
        if (micReport.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(micReport, style = MaterialTheme.typography.bodySmall)
        }
    }
    HeadsetButtonCard()
    SpeechSection(settings, app.whisperProvider)
}

/**
 * Кнопка гарнитуры (25.09.2026): кто отвечает на «помощника» гарнитуры.
 * «Всегда», когда-то выбранное за Google, делает кнопку для Правки немой —
 * снаружи это ровно «не работает», поэтому состояние названо словами.
 */
@Composable
private fun HeadsetButtonCard() {
    val context = LocalContext.current
    var answer by remember { mutableStateOf(HeadsetButtonActivity.whoAnswers(context)) }
    var handlers by remember { mutableStateOf(HeadsetButtonActivity.handlers(context)) }
    var link by remember { mutableStateOf(HeadsetButtonActivity.link(context)) }
    // Вернулся из системного выбора или из карточки чужого приложения —
    // строка перечитывается сама: «спросит» после «Всегда» врало бы.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        answer = HeadsetButtonActivity.whoAnswers(context)
        handlers = HeadsetButtonActivity.handlers(context)
        link = HeadsetButtonActivity.link(context)
        onPauseOrDispose { }
    }
    PaperCard(
        label = "кнопка гарнитуры",
        info = "Долгое нажатие «помощника» на гарнитуре (у Shokz OpenComm2 2025 — Mute две " +
            "секунды вне звонка) заводит тейк: курсор стоит в поле — Правка в поле, поля нет " +
            "или экран заблокирован — Засечка. Слушает гарнитура, какой бы кружок микрофона " +
            "ни стоял: чем нажал, тем и слушаем. Нажатие посреди тейка его заканчивает, как " +
            "второй тап; закрыла распознавание сама гарнитура — тоже. Лимит как у Правки: " +
            "десять минут без единого слова, и на замке тоже. Кнопка голоса на руле машины " +
            "шлёт ту же команду. Кнопку надо один раз отдать Правке: «Назначить Правку» — " +
            "система спросит, чем открывать команду голоса, — «Правка», «Всегда». С гарнитуры " +
            "на заблокированном экране система спросить не может, и нажатие тогда " +
            "пропадает молча — поэтому назначать отсюда. Каждое нажатие, дошедшее до " +
            "Правки, пишется в журнал строкой «гарнитура: команда голоса пришла».",
    ) {
        val (status, hint) = when (val a = answer) {
            HeadsetButtonActivity.Answer.Pravka ->
                "Правка" to "нажатие гарнитуры — диктовка"
            HeadsetButtonActivity.Answer.Ask ->
                "не назначена" to "система спрашивает, чем открыть, — с замка молчит"
            is HeadsetButtonActivity.Answer.Other ->
                a.label to "кнопку забрал он — сбрось «открывать по умолчанию»"
            HeadsetButtonActivity.Answer.Nobody ->
                "никто" to "команду гарнитуры не принимает ни одно приложение"
        }
        PaperRow(
            title = "Кто отвечает на кнопку",
            icon = Glyphs.Mic,
            hint = hint,
            status = status,
            onClick = {
                answer = HeadsetButtonActivity.whoAnswers(context)
                handlers = HeadsetButtonActivity.handlers(context)
                link = HeadsetButtonActivity.link(context)
            },
        )
        // Связь гарнитуры с телефоном: команда помощника ездит только по
        // звонкам (HFP). Нет их — Правке нечего получать, как ни назначай.
        val l = link
        val calls = when (l.calls) {
            true -> "звонки — подключена" +
                (if (l.callNames.isNotEmpty()) " («${l.callNames.joinToString(", ")}»)" else "")
            false -> "звонки — НЕ подключена"
            null -> "звонки — не узнать без разрешения «Устройства поблизости»"
        }
        val music = when (l.music) {
            true -> "музыка — подключена"
            false -> "музыка — нет"
            null -> ""
        }
        Text(
            "Гарнитура у телефона: " + listOf(calls, music).filter { it.isNotBlank() }.joinToString(" · ") +
                if (l.calls == false) {
                    ". Кнопке помощника ехать не по чему: включи «Звонки» у гарнитуры в настройках " +
                        "Bluetooth или отключи её от адаптера Loop120 у компьютера."
                } else ".",
            style = MaterialTheme.typography.bodySmall,
            color = if (l.calls == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Кто вообще принимает команду голоса и кто из них главнее у системы:
        // когда кнопка молчит, это первый вопрос, и отвечать на него надо
        // экраном, а не догадкой.
        if (handlers.isNotEmpty()) {
            val mine = handlers.firstOrNull { it.packageName == context.packageName }
            val top = handlers.first()
            Text(
                "Команду голоса принимают: " + handlers.joinToString(", ") { h ->
                    h.label + (if (h.priority != 0) " (приоритет ${h.priority})" else "")
                } + when {
                    mine == null -> ". Правки среди них нет — сборка без кнопки гарнитуры?"
                    top.packageName != mine.packageName && top.priority > mine.priority ->
                        ". У «${top.label}» приоритет системы выше — «Всегда» его не перебьёт."
                    else -> "."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (val a = answer) {
            HeadsetButtonActivity.Answer.Pravka, HeadsetButtonActivity.Answer.Nobody -> Unit
            HeadsetButtonActivity.Answer.Ask -> {
                Spacer(Modifier.height(8.dp))
                // Выбор «чем открыть» система показывает только на
                // разблокированном экране — здесь он есть всегда.
                PaperButton(
                    "Назначить Правку",
                    icon = Glyphs.Mic,
                    primary = true,
                    onClick = { HeadsetButtonActivity.askAssign(context) },
                )
            }
            is HeadsetButtonActivity.Answer.Other -> {
                Spacer(Modifier.height(8.dp))
                // Чужое «всегда» снимается только в карточке того приложения:
                // «Открывать по умолчанию» → «Сбросить». Дальше — «Назначить».
                PaperButton(
                    "Сбросить у «${a.label}»",
                    icon = Glyphs.Mic,
                    primary = true,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.fromParts("package", a.packageName, null),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AnthropicSettings(app: PravkaApp) {
    // Своя область жизни приложения, а не композиции: rememberCoroutineScope
    // умирает вместе с экраном и рвёт запись в DataStore на полпути, если
    // владелец переключил вкладку сразу после нажатия.
    val scope = app.appScope
    val settings = app.settings
    var apiKey by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var savedMark by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        apiKey = settings.apiKey()
        loaded = true
    }
    PaperCard(label = "ключ api", info = stringResource(R.string.settings_api_key_hint)) {
        PaperField(
            value = apiKey,
            onValueChange = { apiKey = it; savedMark = false },
            enabled = loaded,
            label = stringResource(R.string.settings_api_key_label),
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailing = {
                GlyphButton(
                    if (keyVisible) Glyphs.Close else Glyphs.Search,
                    stringResource(if (keyVisible) R.string.settings_hide else R.string.settings_show),
                    onClick = { keyVisible = !keyVisible },
                )
            },
        )
        Spacer(Modifier.height(6.dp))
        Row {
            Spacer(Modifier.weight(1f))
            PaperButton(
                stringResource(if (savedMark) R.string.settings_saved else R.string.settings_save),
                icon = if (savedMark) Glyphs.Check else null,
                primary = true,
                enabled = loaded,
                onClick = { scope.launch { settings.setApiKey(apiKey); savedMark = true } },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Правка, Дела
// ---------------------------------------------------------------------------

@Composable
private fun PravkaSettings(app: PravkaApp) {
    val scope = app.appScope
    val settings = app.settings
    val prose by settings.proseModeFlow.collectAsState(initial = false)
    val convo by settings.convoContextFlow.collectAsState(initial = true)
    val rulesOn by settings.rulesInPromptFlow.collectAsState(initial = false)

    // Проза — книги владельца; у остальных её нет и в чистке (ClaudeProvider).
    val owner = app.profileStore.flow.collectAsState().value?.owner ?: true
    PaperCard(label = "правка текста") {
        if (owner) PaperToggle(
            title = "Художественная проза",
            checked = prose,
            onCheckedChange = { on -> scope.launch { settings.setProseMode(on) } },
            hint = "бережёт ритм, инверсии, повторы",
            info = "Чистка бережёт авторский стиль: ритм, инверсии, повторы. " +
                "Только ошибки распознавания, орфография и пунктуация. " +
                "Промпт режима редактируется во вкладке «Промпты».",
        )
        PaperToggle(
            title = "Контекст разговора",
            checked = convo,
            onCheckedChange = { on -> scope.launch { settings.setConvoContext(on) } },
            hint = "недавние сообщения в том же приложении",
            info = "Твои недавние сообщения в том же приложении (пауза до 10 минут) " +
                "уходят с новой диктовкой как контекст — ответы держат нить разговора.",
        )
        PaperToggle(
            title = "Постоянные правила в промпте",
            checked = rulesOn,
            onCheckedChange = { on -> scope.launch { settings.setRulesInPrompt(on) } },
            info = "Выключено (16.09): по журналу 1200 чисток правила не меняли пунктуацию и длину " +
                "предложений, а в запрос из 54 попадали только первые 8 (потолок 2000 знаков) — " +
                "и те повторяют промпт. Заметная разница одна: приветствие с «!». Включи, если " +
                "её не хватает; во вкладке Обучение красным помечено, что в потолок не влезает.",
        )
        if (prose && rulesOn) {
            val rulesInProse by settings.rulesInProseFlow.collectAsState(initial = false)
            PaperToggle(
                title = "Правила обучения в прозе",
                checked = rulesInProse,
                onCheckedChange = { on -> scope.launch { settings.setRulesInProse(on) } },
                info = "Выключено: правила оформления (списки и т.п.) не применяются к художественному тексту.",
            )
        }
    }
    // Ночной разбор и тень второй модели — в «Ещё → Разборы» (ReviewsTab.kt),
    // своей плашкой: владелец (16.09) хотел их там, а не среди настроек.
    // Распознавание речи — в «Микрофоне и распознавании»: движок один на все
    // диктовки.
}

/** Дела: кнопка «Д» и дорога к Todoist. */
@Composable
private fun DelaSettings(app: PravkaApp, onOpen: (SettingsGroup) -> Unit) {
    val d by app.settings.rEnabledFlow.collectAsState(initial = true)
    val token by app.settings.todoistTokenFlow.collectAsState(initial = "")
    PaperCard(label = "кнопка") {
        PaperToggle(
            "Кнопка «Д» на экране",
            d,
            { v -> app.appScope.launch { app.settings.setREnabled(v) } },
            hint = "наговорил — задачи разложились по проектам",
        )
    }
    PaperCard(label = "подключение") {
        PaperRow(
            title = "Todoist",
            hint = "токен",
            icon = Glyphs.Delo,
            badgeTint = MaterialTheme.colorScheme.primary,
            status = if (token.isNotBlank()) "есть" else "нет ключа",
            trailing = { StatusDot(if (token.isNotBlank()) true else null) },
            onClick = { onOpen(SettingsGroup.TODOIST) },
        )
    }
}
