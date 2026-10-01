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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.DisposableEffect
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
fun SettingsScreen(app: SlushalkaApp, onBack: () -> Unit, onPickTree: () -> Unit, onPickExtraTree: () -> Unit) {
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
            Group("Библиотека")

            Section("Книги")
            Text(
                if (prefs.libraryUri.isBlank()) "Папка не выбрана"
                else "${ru.zf.slushalka.data.Saf.humanPath(prefs.libraryUri)} · книг: " +
                    "${books.count { it.tree == prefs.libraryUri || it.tree.isBlank() }}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Note(
                "Главная папка: сюда ложатся книги из Флибусты и здесь живёт «_Слушалка» с " +
                    "позициями. Договорились держать её в Downloads/Books - пикер открывается сразу там."
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPickTree) { Text("Выбрать папку") }
                TextButton(onClick = { state.rescan() }) { Text("Перечитать") }
            }
            // Ещё папки: карта памяти, папка, которую синхронизирует с облаком
            // сторонняя программа, чужая коллекция. Читаются наравне с главной.
            prefs.libraryExtra.forEach { uri ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${ru.zf.slushalka.data.Saf.humanPath(uri)} · книг: ${books.count { it.tree == uri }}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { state.removeExtraTree(uri) }) { Text("Убрать") }
                }
            }
            if (prefs.libraryUri.isNotBlank()) {
                TextButton(onClick = onPickExtraTree) { Text("Добавить ещё папку") }
                Note(
                    "Книги из всех папок - на одной полке. Позиции, разметка и справочник у книги " +
                        "лежат в её собственной папке, где бы она ни была."
                )
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

            CloudSettings(app)
            if (prefs.cloudReady) StreamSettings(app)

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

            Group("Чтение и звук")

            Section("Внешний вид")
            Text("На чём читаем", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Settings.DEVICES.forEach { id ->
                    FilterChip(
                        selected = prefs.readerDevice == id,
                        onClick = { scope.launch { state.settings.setReaderDevice(id) } },
                        label = { Text(Settings.deviceLabel(id)) },
                    )
                }
            }
            Note(
                when (prefs.readerDevice) {
                    Settings.DEVICE_EINK_HIGH ->
                        "Цветная мощная читалка (Boox Tab, Bigme, PocketBook Color): книга остаётся книгой - " +
                            "переплёт, обрез, просвет с оборота, тени, - но без анимаций и зерна, шрифт плотнее, " +
                            "страницами. Листать можно кнопками громкости и кнопками самой читалки."
                    Settings.DEVICE_EINK_LOW ->
                        "Простая электронная книга: всё приложение чёрным по белому, читалка крупнее, жирнее, " +
                            "страницами, без теней и анимаций. Листать можно кнопками громкости и кнопками книги."
                    else -> "Телефон или планшет: всё, как выбрано в виде страницы, с загибом листа и анимациями."
                } + if (ru.zf.slushalka.data.EinkDevice.likely) " Это устройство похоже на электронную книгу." else ""
            )
            Toggle("Листать кнопками громкости", prefs.readerVolumeKeys) {
                scope.launch { state.settings.setReaderVolumeKeys(it) }
            }
            Text("Масштаб интерфейса", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Settings.UI_SCALES.forEach { k ->
                    FilterChip(
                        selected = kotlin.math.abs(prefs.uiScale - k) < 0.01f,
                        onClick = { scope.launch { state.settings.setUiScale(k) } },
                        label = { Text("${(k * 100).toInt()}%") },
                    )
                }
            }
            Note(
                "Для планшета или читалки с большим экраном, где всё выходит мелким: " +
                    "растёт разом весь интерфейс, кнопки и надписи. Кегль текста в читалке " +
                    "настраивается отдельно, в «Аа Вид»."
            )

            // Тот же кусок, что в «Аа Вид» читалки: настройка одна, экрана два.
            PageLookSettings(app) { title ->
                Spacer(Modifier.height(12.dp))
                Text(title, style = MaterialTheme.typography.bodyMedium)
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

            Section("Озвучка")
            // Движок синтеза заводится по требованию: пока раздел на экране,
            // он поднят ради списка голосов и пробы, ушли - отпускается.
            DisposableEffect(Unit) {
                app.readAloud.hold(true)
                onDispose { app.readAloud.hold(false) }
            }
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

            Group("Claude")

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
            Note(
                "Сколько книги показать модели (от трёх страниц до всей книги с начала), какая модель " +
                    "отвечает и держать ли текст в кэше час - выбирается прямо в окне вопроса и помнится."
            )
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
                    "«по умолчанию» — high, low быстрее и дешевле, xhigh и max — для " +
                    "трудных вопросов. Заводская везде — Опус 5.5. Сонет вдвое дешевле, " +
                    "Fable 5.1 — в два с половиной раза дороже."
            )
            ModelPicker(
                title = "Вопрос по книге (заводская; меняется и в самом окне)",
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
                title = "Справочник по книге (пакетный запрос, вдвое дешевле)",
                model = prefs.guideModel,
                effort = null,
                onModel = { m -> scope.launch { state.settings.setGuideModel(m) } },
                onEffort = {},
            )
            Note(
                "Книга уезжает целиком, ответ обычно за час. Опус её вытягивает; Сонет дешевле, " +
                    "но на толстом романе путает героев; Fable точнее всех и заметно дороже."
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

            Group("Приложение")

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
                    is ru.zf.slushalka.update.Updater.Status.Ready ->
                        "Есть версия ${u.update.versionName}" +
                            (if (u.update.builtAt.isBlank()) "" else " от ${u.update.builtAt}")
                    is ru.zf.slushalka.update.Updater.Status.Downloading -> "Качаю: ${u.percent}%"
                    ru.zf.slushalka.update.Updater.Status.Checking -> "Смотрю…"
                    is ru.zf.slushalka.update.Updater.Status.UpToDate ->
                        "Стоит последняя (сборка ${u.code}) · проверено ${formatAgo(u.at)}"
                    is ru.zf.slushalka.update.Updater.Status.Failed -> u.message
                    else -> "Проверяется само при каждом запуске, не чаще раза в полчаса"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { scope.launch { app.updater.check(manual = true) } }) {
                    Text("Проверить сейчас")
                }
                (update as? ru.zf.slushalka.update.Updater.Status.Ready)?.let { ready ->
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
            // Лицензия шрифтов едет с приложением: OFL требует копирайт и текст
            // лицензии при каждой копии шрифта.
            var showFonts by remember { mutableStateOf(false) }
            TextButton(onClick = { showFonts = true }) { Text("Шрифты и их лицензия") }
            if (showFonts) {
                val context = androidx.compose.ui.platform.LocalContext.current
                val text = remember {
                    runCatching { context.assets.open("fonts-OFL.txt").bufferedReader().readText() }
                        .getOrDefault("Файл лицензии не нашёлся.")
                }
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showFonts = false },
                    title = { Text("Шрифты") },
                    text = {
                        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                            Text(text, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    confirmButton = { TextButton(onClick = { showFonts = false }) { Text("Закрыть") } },
                )
            }
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

/**
 * Группа разделов. Двенадцать разделов подряд искались листанием: теперь их
 * четыре кучки - библиотека, чтение и звук, Claude, приложение.
 */
@Composable
private fun Group(title: String) {
    Spacer(Modifier.height(30.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
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
    /** null - усилие не выбирается (пакетному запросу оно ни к чему). */
    effort: String?,
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
    if (effort == null) return
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

/**
 * Облако по WebDAV: адрес, логин, пароль приложения, папка - и проверка.
 * Пароль - именно «пароль приложения» (у Яндекса - id.yandex.ru, «Пароли
 * приложений», тип «Файлы»): обычный пароль от почты WebDAV не примет.
 *
 * Домашняя библиотека - то же облако, только папка - корень сервера: там
 * `Книги/`, `_Слушалка/` и оглавление `index.json`. Проверка заодно говорит,
 * нашлось ли оглавление.
 */
@Composable
private fun CloudSettings(app: SlushalkaApp) {
    val prefs by app.state.prefs.collectAsState()
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(prefs.cloudUrl) }
    var user by remember { mutableStateOf(prefs.cloudUser) }
    var pass by remember { mutableStateOf(prefs.cloudPass) }
    var dir by remember { mutableStateOf(if (prefs.cloudAtRoot) "" else prefs.cloudDir) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    // Сменили сервер, вход или папку - прежнее оглавление больше не про них.
    val changed = { app.server.forget(); result = null }

    Section("Облако")
    Note(
        "Синхронизация без сторонней программы, место для книг и домашняя библиотека. Домашняя " +
            "библиотека: адрес ${Settings.HOME_LIBRARY_URL}, логин этого устройства (на Boox - boox), " +
            "его пароль, папка - корень сервера; кнопка ниже подставит адрес и корень. Подходит и любой " +
            "другой WebDAV: Яндекс.Диск (адрес webdav.yandex.ru, логин - почта, пароль - пароль " +
            "приложения из id.yandex.ru → «Пароли приложений» → «Файлы»), Nextcloud, Box, Koofr."
    )
    TextButton(onClick = {
        url = Settings.HOME_LIBRARY_URL
        dir = ""
        changed()
        scope.launch {
            app.settings.setCloudUrl(Settings.HOME_LIBRARY_URL)
            app.settings.setCloudDir(Settings.ROOT_DIR)
        }
    }) { Text("Домашняя библиотека") }
    OutlinedTextField(
        value = url, onValueChange = { url = it; changed(); scope.launch { app.settings.setCloudUrl(it) } },
        label = { Text("Адрес WebDAV") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = user, onValueChange = { user = it; changed(); scope.launch { app.settings.setCloudUser(it) } },
        label = { Text("Логин") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = pass, onValueChange = { pass = it; changed(); scope.launch { app.settings.setCloudPass(it) } },
        label = { Text("Пароль приложения") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Toggle("Корень сервера", prefs.cloudAtRoot) { on ->
        changed()
        scope.launch {
            // Выключили корень - возвращается набранная папка, а нет её - заводская.
            app.settings.setCloudDir(if (on) Settings.ROOT_DIR else dir.ifBlank { Settings.DEFAULT_CLOUD_DIR })
            if (!on && dir.isBlank()) dir = Settings.DEFAULT_CLOUD_DIR
        }
    }
    if (!prefs.cloudAtRoot) {
        OutlinedTextField(
            value = dir, onValueChange = { dir = it; changed(); scope.launch { app.settings.setCloudDir(it) } },
            label = { Text("Папка в облаке") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            enabled = !checking && prefs.cloudReady,
            onClick = {
                checking = true
                result = null
                scope.launch {
                    result = app.cloud.check().fold(
                        onSuccess = {
                            val where = if (prefs.cloudAtRoot) "корень сервера" else "папка «${prefs.cloudDir}»"
                            val idx = app.server.refresh()
                            "Облако на связи: $where готов${if (prefs.cloudAtRoot) "" else "а"}. " + when {
                                idx != null -> "Библиотека: книг ${idx.books.size} - полка «В библиотеке»."
                                else -> "Оглавления index.json здесь нет - это облако, а не домашняя библиотека."
                            }
                        },
                        onFailure = { it.message ?: "Облако не ответило" },
                    )
                    checking = false
                }
            },
        ) { Text(if (checking) "Проверяю…" else "Проверить") }
    }
    result?.let { Note(it) }
    Toggle("Синхронизировать через облако", prefs.cloudSync) {
        scope.launch { app.settings.setCloudSync(it) }
    }
    Note(
        "Позиции, вопросы и пометки ездят через «${prefs.cloudPath("_Слушалка")}» - те же файлы, что в папке " +
            "библиотеки, так что обе дороги работают вместе. Книги - в «${prefs.cloudPath("Книги")}»: у " +
            "домашней библиотеки они на полке «В библиотеке», у другого облака - на экране облака " +
            "(значок на полке)."
    )
}

/**
 * Звук с сервера: мобильная сеть и кэш записи на телефоне. Час mp3 на
 * 128 кбит/с - около 60 МБ: столько уходит и в трафик, и в кэш.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StreamSettings(app: SlushalkaApp) {
    val prefs by app.state.prefs.collectAsState()
    val scope = rememberCoroutineScope()
    // Сколько занято - спрашивается при открытии и после «Очистить»: кэш растёт
    // и сам, пока играет, но цифре на экране незачем дрожать.
    var used by remember { mutableStateOf<Long?>(null) }
    var rev by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(rev) {
        used = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.streaming.cachedBytes() }
    }

    Section("Звук с сервера")
    Text("В мобильной сети", style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Settings.MOBILES.forEach { m ->
            FilterChip(
                selected = prefs.streamMobile == m,
                onClick = { scope.launch { app.settings.setStreamMobile(m) } },
                label = { Text(Settings.mobileLabel(m)) },
            )
        }
    }
    Note(
        when (prefs.streamMobile) {
            Settings.MOBILE_STREAM -> "Книга с сервера играет и в мобильной сети, не спрашивая."
            Settings.MOBILE_ASK -> "В мобильной сети плеер спросит один раз - до следующего Wi-Fi."
            else -> "В мобильной сети играет только то, что уже на телефоне."
        } + " Для справки: mp3 на 128 кбит/с - около 60 МБ в час. Пока играет файл, следующий качается " +
            "целиком заранее - на Wi-Fi это запас на тоннель и дачу."
    )
    Text("Кэш записи на телефоне", style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Settings.CACHE_SIZES_MB.forEach { mb ->
            FilterChip(
                selected = prefs.streamCacheMb == mb,
                onClick = {
                    scope.launch {
                        app.settings.setStreamCacheMb(mb)
                        // Уменьшили - лишнее уходит сразу.
                        app.streaming.trim()
                    }
                },
                label = { Text(if (mb >= 1024) "${mb / 1024} ГБ" else "$mb МБ") },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Занято: " + (used?.let { if (it > 0) formatBytes(it) else "ничего" } ?: "…"),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            enabled = (used ?: 0L) > 0,
            onClick = { scope.launch { app.streaming.clear(); rev++ } },
        ) { Text("Очистить") }
    }
    Note(
        "Сыгранное и подкачанное лежит на телефоне и второй раз не качается - и без сети играет. " +
            "Давно не слушанное вытесняется первым. Скачанные книги в кэш не входят: они на полке."
    )
}
