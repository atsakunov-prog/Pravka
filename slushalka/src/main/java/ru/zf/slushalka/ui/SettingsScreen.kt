package ru.zf.slushalka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.zf.slushalka.BuildConfig
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.data.Settings

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(app: SlushalkaApp, onBack: () -> Unit, onPickTree: () -> Unit) {
    val state = app.state
    val prefs by state.prefs.collectAsState()
    val books by state.books.collectAsState()
    val scope = rememberCoroutineScope()

    var key by remember { mutableStateOf(prefs.apiKey) }
    var profile by remember { mutableStateOf(prefs.profile) }
    var flibusta by remember { mutableStateOf(prefs.flibustaUrl) }
    var showGallery by remember { mutableStateOf(false) }
    val current by state.current.collectAsState()
    val text by state.text.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            Section("Книги")
            Text(
                if (prefs.libraryUri.isBlank()) "Папка не выбрана"
                else "${ru.zf.slushalka.data.Saf.humanPath(prefs.libraryUri)} · книг: ${books.size}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Note(
                "Одна папка на всё: аудиокниги, которые кладёшь сам, и книги из Флибусты. " +
                    "Договорились держать её в Downloads/Books - пикер открывается сразу там."
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPickTree) { Text("Выбрать папку") }
                TextButton(onClick = { state.rescan() }) { Text("Перечитать") }
            }

            // Раздел про открытую книгу - здесь, а не только в настройках
            // читалки: сюда заходят в первую очередь, и «где мои картинки»
            // спрашивают именно тут.
            current?.let { book ->
                Section("Открытая книга")
                Text(book.title, style = MaterialTheme.typography.bodyLarge)
                val pics = text?.pictures?.size ?: 0
                val extracted = state.picturesOnDisk()
                Text(
                    when {
                        text == null -> "Текст книги ещё не разобран."
                        pics > 0 -> "Картинок в тексте: $pics — стоят на своих местах, " +
                            "тап открывает во весь экран."
                        extracted > 0 -> "Вынуто из файла: $extracted, но место в тексте для них " +
                            "не нашлось — смотри списком."
                        else -> "Картинок в книге не нашлось."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (text != null && pics == 0) {
                    state.parseReport()?.let { r ->
                        Note("Разбор увидел: ${r.line()}")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (extracted > 0) {
                        TextButton(onClick = { showGallery = true }) { Text("Показать картинки") }
                    }
                    TextButton(onClick = { state.reparseText() }) { Text("Разобрать заново") }
                }
            }

            Section("Флибуста")
            OutlinedTextField(
                value = flibusta,
                onValueChange = {
                    flibusta = it
                    scope.launch { state.settings.setFlibustaUrl(it) }
                    // Ленты уже открытого каталога вели на прежний адрес.
                    app.catalog.reset()
                },
                label = { Text("Адрес каталога") },
                placeholder = { Text(Settings.DEFAULT_FLIBUSTA_URL) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Note(
                "Каталог открывается лупой на полке. Книга скачивается в папку библиотеки " +
                    "своей папкой «Автор - Название» с fb2 и обложкой и появляется на полке как " +
                    "книга без записи: читалка, озвучка, вопросы и пересказ работают, плеера нет. " +
                    "Появится начитка - положи файлы в ту же папку.\n\n" +
                    "Если сайт в этой сети не открывается, помогает VPN или адрес зеркала здесь."
            )

            Section("Озвучка")
            val speech by app.readAloud.state.collectAsState()
            Note(
                "Книгу без записи читает вслух синтез речи телефона: кнопка «Озвучить» в " +
                    "читалке. Голос - тот, что установлен в системе: у Google и Samsung есть " +
                    "русские голоса получше заводского, их докачивают в настройках синтеза " +
                    "речи (кнопка ниже), а здесь выбирают. Голоса с пометкой «сеть» звучат " +
                    "естественнее, но требуют интернета."
            )
            val voices = remember(speech.ready) { app.readAloud.voices() }
            when {
                speech.error != null && !speech.ready -> Text(speech.error!!, style = MaterialTheme.typography.bodyMedium)
                !speech.ready -> Text("Движок синтеза речи поднимается…", style = MaterialTheme.typography.bodyMedium)
                voices.isEmpty() -> Text(
                    "Русских голосов в движке не нашлось - поставь их в настройках синтеза речи.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    voices.forEach { v ->
                        val chosen = if (prefs.ttsVoice.isNotBlank()) prefs.ttsVoice == v.name
                        else app.readAloud.currentVoice() == v.name
                        FilterChip(
                            selected = chosen,
                            onClick = {
                                app.readAloud.setVoice(v.name)
                                scope.launch { state.settings.setTtsVoice(v.name) }
                            },
                            label = { Text(voiceLabel(v)) },
                        )
                    }
                }
            }
            NumberSlider(
                label = { "Темп: " + formatSpeed(it) },
                value = prefs.ttsRate,
                range = 0.6f..2.0f,
                steps = 13,
            ) { r ->
                app.readAloud.setRate(r)
                scope.launch { state.settings.setTtsRate(r) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { app.readAloud.sample() }, enabled = speech.ready) { Text("Послушать") }
                TextButton(onClick = { app.readAloud.openSystemSettings() }) { Text("Голоса системы") }
            }

            Section("Кто слушает")
            OutlinedTextField(
                value = profile,
                onValueChange = {
                    profile = it
                    scope.launch { state.settings.setProfile(it) }
                },
                label = { Text("Имя для синхронизации") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Note(
                "В корне библиотеки заводится папка «_Слушалка», и это имя становится твоей " +
                    "дорожкой в ней. Если папка синхронизируется между устройствами, книга " +
                    "продолжается там, где остановилась, а на карточке видно, докуда дошёл второй."
            )
            Toggle("Синхронизировать позиции", prefs.syncPositions) {
                scope.launch { state.settings.setSyncPositions(it) }
            }

            Section("Плеер")
            Text("Перемотка кнопками: ${prefs.skipSec} с", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 15, 20, 30, 60).forEach { s ->
                    FilterChip(
                        selected = prefs.skipSec == s,
                        onClick = { scope.launch { state.settings.setSkipSec(s) } },
                        label = { Text("$s") },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Toggle("Откатываться назад после паузы", prefs.autoRewind) {
                scope.launch { state.settings.setAutoRewind(it) }
            }
            Note(
                "Через пять минут паузы книга отматывается на три секунды, через неделю - " +
                    "на полминуты: иначе включаешься в середину фразы."
            )
            Toggle("Проглатывать тишину", prefs.skipSilence) {
                scope.launch { state.settings.setSkipSilence(it) }
                app.player.setSkipSilence(it)
            }

            Section("Вопросы")
            OutlinedTextField(
                value = key,
                onValueChange = {
                    key = it
                    scope.launch { state.settings.setApiKey(it) }
                },
                label = { Text("Ключ Anthropic") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Note("Ключ живёт только на этом телефоне - как в Правке, без всяких прокси.")

            Spacer(Modifier.height(8.dp))
            NumberSlider(
                label = { "Страниц в вопрос: ${it.toInt()}" },
                value = prefs.contextPages.toFloat(),
                range = 1f..20f,
                steps = 18,
            ) { scope.launch { state.settings.setContextPages(it.toInt()) } }
            NumberSlider(
                label = { "Запас против спойлера: ${it.toInt()} мин" },
                value = (prefs.spoilerMarginSec / 60).toFloat(),
                range = 0f..10f,
                steps = 9,
            ) { scope.launch { state.settings.setSpoilerMargin(it.toInt() * 60) } }
            Note(
                "Текст режется не по текущей секунде, а на столько раньше: привязка " +
                    "приблизительная, и ошибаться она должна в сторону уже услышанного."
            )
            Toggle("Ответ вслух", prefs.speakAnswers) {
                scope.launch { state.settings.setSpeakAnswers(it) }
            }
            Toggle("Ставить книгу на паузу на время вопроса", prefs.pauseWhileAsking) {
                scope.launch { state.settings.setPauseWhileAsking(it) }
            }
            Toggle("Отдавать всю книгу до текущего места", prefs.wholeBookContext) {
                scope.launch { state.settings.setWholeBookContext(it) }
            }
            Note(
                "Дороже, но отвечает на «кто это?» про героя из первой главы. Кэш держится час, " +
                    "поэтому второй вопрос в тот же вечер стоит копейки."
            )
            Spacer(Modifier.height(6.dp))
            NumberSlider(
                label = { "Пересказ предлагать после перерыва: ${it.toInt()} ч" },
                value = prefs.recapAfterHours.toFloat(),
                range = 1f..48f,
                steps = 46,
            ) { scope.launch { state.settings.setRecapAfterHours(it.toInt()) } }
            Text(
                "Потрачено на вопросы: %.2f $".format(app.askLog.totalUsd()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Модели — отдельным разделом, как в Правке: владелец просил
            // выбирать и модель, и усилие, а не искать это под вопросами.
            Section("Модели")
            Note(
                "Какая модель отвечает и с каким усилием. Усилие — глубина размышлений: " +
                    "«по умолчанию» значит не передавать параметр (API берёт high), low " +
                    "быстрее и дешевле, xhigh и max — для трудных вопросов. Заводские — " +
                    "Опус на вопрос, Сонет на пересказ, Fable 5.1 советнику. Fable вдвое дороже Опуса."
            )
            ModelPicker(
                title = "Вопрос по книге",
                model = prefs.askModel,
                effort = prefs.askEffort,
                onModel = { m -> scope.launch { state.settings.setAskModel(m) } },
                onEffort = { e -> scope.launch { state.settings.setAskEffort(e) } },
            )
            Spacer(Modifier.height(10.dp))
            ModelPicker(
                title = "Пересказ «что там было»",
                model = prefs.recapModel,
                effort = prefs.recapEffort,
                onModel = { m -> scope.launch { state.settings.setRecapModel(m) } },
                onEffort = { e -> scope.launch { state.settings.setRecapEffort(e) } },
            )
            Spacer(Modifier.height(10.dp))
            ModelPicker(
                title = "Советник в каталоге Флибусты",
                model = prefs.adviseModel,
                effort = prefs.adviseEffort,
                onModel = { m -> scope.launch { state.settings.setAdviseModel(m) } },
                onEffort = { e -> scope.launch { state.settings.setAdviseEffort(e) } },
            )
            Toggle("Советник смотрит в интернет", prefs.adviseWeb) {
                scope.launch { state.settings.setAdviseWeb(it) }
            }
            Note(
                "С интернетом советник отвечает, что о книге говорят читатели и критики; " +
                    "каждый поиск стоит около цента сверх токенов, не больше трёх на ответ. " +
                    "Выключатель здесь - заводское положение, в самом листе его можно переключить."
            )

            Section("Обновление")
            val update by app.updater.status.collectAsState()
            // Своя версия - прямо здесь, а не только в «О приложении» внизу:
            // без неё вопрос «почему не обновляется» не с чем сопоставить.
            Text(
                "Установлено: ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                when (val u = update) {
                    is ru.zf.slushalka.data.Updater.Status.Ready ->
                        "Есть версия ${u.update.versionName}" +
                            (if (u.update.builtAt.isBlank()) "" else " от ${u.update.builtAt}")
                    is ru.zf.slushalka.data.Updater.Status.Downloading -> "Качаю: ${u.percent}%"
                    ru.zf.slushalka.data.Updater.Status.Checking -> "Смотрю…"
                    is ru.zf.slushalka.data.Updater.Status.UpToDate ->
                        "Стоит последняя версия · проверено ${formatAgo(u.at)}"
                    is ru.zf.slushalka.data.Updater.Status.Failed -> u.message
                    else -> "Проверяется само при каждом запуске, не чаще раза в полчаса"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { scope.launch { app.updater.check(manual = true) } }) {
                    Text("Проверить сейчас")
                }
                (update as? ru.zf.slushalka.data.Updater.Status.Ready)?.let { ready ->
                    TextButton(onClick = {
                        scope.launch { app.updater.downloadAndInstall(ready.update) }
                    }) { Text("Обновить") }
                }
            }
            Toggle("Проверять само", prefs.updateAuto) {
                scope.launch { state.settings.setUpdateAuto(it) }
            }
            Note(
                "Сборка каждого пуша в ветку slushalka уезжает в apk-builds со своим файлом " +
                    "версий, оттуда приложение её и берёт. " +
                    "Подпись та же, поэтому обновление ставится поверх и ничего не стирает: " +
                    "позиции, закладки и разметка книг остаются на месте.\n\n" +
                    "В первый раз Андроид спросит разрешение «Установка неизвестных приложений» — " +
                    "его надо дать один раз."
            )

            Section("О приложении")
            Text(
                "Слушалка ${BuildConfig.VERSION_NAME}, сборка ${BuildConfig.BUILD_TIME}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            LoveLine(alpha = 0.45f, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(48.dp))
        }
    }

    if (showGallery) {
        PictureGallery(app) { showGallery = false }
    }
}

/**
 * Имя голоса по-человечески. У Google голоса называются вроде «ru-ru-x-rud-network»:
 * префикс языка убираем, «network» и «local» переводим, качество - звёздочками.
 */
private fun voiceLabel(v: android.speech.tts.Voice): String {
    val raw = v.name.removePrefix("ru-ru-").removePrefix("ru-RU-").removePrefix("ru_RU-")
        .replace("-network", "").replace("-local", "").replace("_", " ")
    val stars = when {
        v.quality >= android.speech.tts.Voice.QUALITY_VERY_HIGH -> " ★★★"
        v.quality >= android.speech.tts.Voice.QUALITY_HIGH -> " ★★"
        v.quality >= android.speech.tts.Voice.QUALITY_NORMAL -> " ★"
        else -> ""
    }
    return raw.ifBlank { v.name } + stars + if (v.isNetworkConnectionRequired) " · сеть" else ""
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(22.dp))
    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium)
    HorizontalDivider(Modifier.padding(top = 4.dp, bottom = 10.dp))
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
    )
}

/** Одна дорога к модели: ряд чипов модели и ряд чипов усилия. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelPicker(
    title: String,
    model: String,
    effort: String,
    onModel: (String) -> Unit,
    onEffort: (String) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Settings.MODELS.forEach { m ->
            FilterChip(
                selected = model == m,
                onClick = { onModel(m) },
                label = { Text(Settings.modelLabel(m)) },
            )
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Settings.EFFORTS.forEach { e ->
            FilterChip(
                selected = effort == e,
                onClick = { onEffort(e) },
                label = { Text(Settings.effortLabel(e)) },
            )
        }
    }
}

@Composable
private fun Toggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Ползунок, который пишет настройку **один раз** - когда палец отпустили.
 * Запись на каждый пиксель протаскивания давала бы полсотни обращений к
 * DataStore на одно движение.
 */
@Composable
private fun NumberSlider(
    label: (Float) -> String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onCommit: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Text(label(local), style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = local,
        onValueChange = { local = it },
        onValueChangeFinished = { onCommit(local) },
        valueRange = range,
        steps = steps,
    )
}
