package ru.zf.pravka

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.zf.pravka.core.PhoneRemote
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.Kv
import ru.zf.pravka.ui.KvCallout
import ru.zf.pravka.ui.KvListRow
import ru.zf.pravka.ui.KvNote
import ru.zf.pravka.ui.KvSetRow
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.fadingScroll

// «Телефон» — настройки телефона в виде v2 (Правка 5.0, этап 6, холст
// `Telefon.dc.html`): вход из меню Ковчега строкой «Настройки телефона».
// Сами настройки и их смысл — прежние: группы `SettingsGroup`, их экраны —
// `GroupContent` в `SettingsTab.kt`. Меняется только раскладка: группы
// строками «название — что стоит», на развороте — группы слева, открытая
// группа справа. Служебные экраны бывшего «Ещё» — строками ниже групп.
// С 14.10.2026 те же настройки видны и правятся с компьютера — страницей
// «Телефон» в вебе Дел (docs/phone-settings-server.md, `PhoneKnobs.kt`,
// `data/PhoneRemoteSync.kt`): что поставлено оттуда — пометкой «поменяно с
// компьютера в …» у группы и плашкой «С компьютера» сверху.

/** Строка служебного экрана (словарь, промпты, логи…) — её собирает `MainActivity`, где живут вкладки. */
internal class PhoneLink(
    val title: String,
    val hint: String,
    val icon: ImageVector,
    val news: Boolean = false,
    val onClick: () -> Unit,
)

/** Подпись шапки «Телефона»: модель телефона и сборка Правки. */
internal fun phoneSubtitle(): String = android.os.Build.MODEL.orEmpty().ifBlank { "телефон" } + " · Правка " + BuildConfig.VERSION_NAME

/** Название полки с заглавной: «Голос и Claude» — имя Claude с большой и внутри строки. */
private fun shelfTitle(shelf: SettingsShelf): String =
    shelf.label.replaceFirstChar { it.uppercase() }.replace("claude", "Claude")

/** Полки групп по порядку — как в меню настроек 4.0, «Обновления и служба» — в своей полке. */
@Composable
private fun phoneGroups(app: PravkaApp): List<Pair<SettingsShelf, List<SettingsGroup>>> {
    val profile by app.profileStore.flow.collectAsState()
    // Группы выключенного в профиле режима не показываются: их настройки ни на что не действуют.
    return SettingsShelf.entries.map { shelf ->
        shelf to SettingsGroup.entries.filter { it.shelf == shelf }
            .filter { g -> g.modes.isEmpty() || g.modes.any { m -> profile?.has(m) ?: true } }
    }.filter { it.second.isNotEmpty() }
}

/** «что стоит» под названием группы: состояние, а нет его — о чём группа. Сбой — словом справа. */
@Composable
private fun groupLine(app: PravkaApp, g: SettingsGroup): Pair<String, String?> {
    val st = groupStatus(app, g)
    val line = if (st?.ok == false) g.hint to st.text else (st?.text?.let { "$it · ${g.hint}" } ?: g.hint) to null
    // Поставлено с компьютера за трое суток — первым словом строки: «откуда это у меня?» — отвечено на месте.
    val remote by app.phoneRemote.view.collectAsState()
    val note = g.remoteKey?.let { PhoneRemote.groupNote(remote.applied, it, System.currentTimeMillis()) }
    return if (note == null) line else "$note · ${line.first}" to line.second
}

/**
 * Группа страницы «Телефон» в вебе Дел (`PhoneKnobs.GROUPS`) для группы этого экрана: по ней — пометка
 * «поменяно с компьютера». У групп, которых издалека не правят (ключи, Свод, баги), её нет.
 */
private val SettingsGroup.remoteKey: String?
    get() = when (this) {
        SettingsGroup.MODELS -> "models"
        SettingsGroup.VOICE -> "voice"
        SettingsGroup.ZASECHKA -> "zasechka"
        SettingsGroup.TODAY -> "today"
        SettingsGroup.PRAVKA -> "pravka"
        SettingsGroup.SPORT -> "sport"
        SettingsGroup.FOOD -> "food"
        SettingsGroup.MONEY -> "money"
        SettingsGroup.NET -> "net"
        SettingsGroup.CARDS -> "look"
        SettingsGroup.DATA -> "data"
        SettingsGroup.APP -> "app"
        else -> null
    }

@Composable
internal fun PhoneSettingsScreen(
    app: PravkaApp,
    serviceEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenGroup: (SettingsGroup) -> Unit,
    links: List<Pair<String, List<PhoneLink>>>,
) {
    val wide = ru.zf.pravka.ui.twoPane()
    val shelves = phoneGroups(app)
    if (!wide) {
        Column(
            Modifier
                .fillMaxSize()
                .fadingScroll()
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PhoneServiceBlock(app, serviceEnabled, onOpenAccessibilitySettings) { onOpenGroup(SettingsGroup.APP) }
            for ((shelf, groups) in shelves) {
                PaperCard(label = shelfTitle(shelf)) {
                    groups.forEachIndexed { i, g ->
                        val (sub, bad) = groupLine(app, g)
                        KvSetRow(
                            g.title,
                            icon = g.glyph,
                            sub = sub,
                            value = bad,
                            valueColor = Kv.bad,
                            divider = i > 0,
                            onClick = { onOpenGroup(g) },
                        )
                    }
                }
            }
            PhoneLinks(links)
            BuildLine()
        }
        return
    }
    // Разворот (`.cols.c23` холста): группы слева узкой колонкой, открытая группа — справа.
    var pickedName by rememberSaveable { mutableStateOf(SettingsGroup.MODELS.name) }
    val all = shelves.flatMap { it.second }
    val picked = all.firstOrNull { it.name == pickedName } ?: all.firstOrNull() ?: SettingsGroup.APP
    Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier
                .width(300.dp)
                .fillMaxHeight()
                .fadingScroll()
                .padding(top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Группы — первыми, как на холсте; служба, обновления и служебные экраны — под ними.
            for ((shelf, groups) in shelves) {
                PaperCard(label = shelfTitle(shelf)) {
                    groups.forEachIndexed { i, g ->
                        val (sub, bad) = groupLine(app, g)
                        KvListRow(
                            title = g.title,
                            sub = sub,
                            icon = g.glyph,
                            value = bad,
                            valueColor = Kv.bad,
                            valueSmall = true,
                            titleLines = 1,
                            subLines = 1,
                            selected = g == picked,
                            divider = i > 0 && g != picked && groups.getOrNull(i - 1) != picked,
                            onClick = { pickedName = g.name },
                        )
                    }
                }
            }
            PhoneServiceBlock(app, serviceEnabled, onOpenAccessibilitySettings) { pickedName = SettingsGroup.APP.name }
            PhoneLinks(links)
            BuildLine()
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // Заголовок открытой группы, ниже — её же плашки, что за шестерёнкой режима (поля — как у них, 16).
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp)) {
                Text(picked.title, style = LocalPravkaType.current.titleS, color = Kv.text)
            }
            KvNote(picked.hint, Modifier.padding(start = 16.dp))
            // Что в этой группе поставлено с компьютера — над её плашками, словами и со временем.
            val remote by app.phoneRemote.view.collectAsState()
            val fromPc = picked.remoteKey?.let { k -> PhoneRemote.recent(remote.applied, System.currentTimeMillis()).filter { it.group == k } }.orEmpty()
            if (fromPc.isNotEmpty()) {
                KvCallout(
                    fromPc.take(3).joinToString("\n") { "${it.title}: ${it.value} — поменяно с компьютера ${PhoneRemote.whenWords(it.atMs, System.currentTimeMillis())}" },
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    icon = ru.zf.pravka.ui.KvGlyphs.byKey("monitor") ?: Glyphs.Phone,
                )
            }
            Box(Modifier.weight(1f)) {
                ModeSettingsScreen(
                    app,
                    picked,
                    serviceEnabled,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onOpen = { g -> pickedName = g.name },
                )
            }
        }
    }
}

/**
 * Где лежат настройки — правдой, а не обещанием (14.10.2026): видны и правятся с компьютера страницей
 * «Телефон» в вебе Дел, телефон берёт правку синком; что поставлено оттуда — плашкой «С компьютера». Ниже —
 * что живёт только в руках (холст: «Только в руках»): служба доступности и плавающие кнопки, обновления.
 */
@Composable
private fun PhoneServiceBlock(
    app: PravkaApp,
    serviceEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpen: () -> Unit,
) {
    val remote by app.phoneRemote.view.collectAsState()
    val now = System.currentTimeMillis()
    KvCallout(
        phoneServerLine(remote, now),
        icon = ru.zf.pravka.ui.KvGlyphs.byKey("server") ?: Glyphs.Phone,
        tone = if (remote.linked && remote.error.isNotBlank() && remote.errorAt >= remote.okAt) ru.zf.pravka.ui.KvTone.WARN else ru.zf.pravka.ui.KvTone.PLAIN,
    )
    val fresh = PhoneRemote.recent(remote.applied, now)
    if (fresh.isNotEmpty()) {
        PaperCard(label = "С компьютера", note = "за трое суток") {
            fresh.take(5).forEachIndexed { i, a ->
                KvListRow(
                    title = a.title,
                    sub = "${a.value} — поменяно с компьютера ${PhoneRemote.whenWords(a.atMs, now)}",
                    icon = ru.zf.pravka.ui.KvGlyphs.byKey("monitor") ?: Glyphs.Phone,
                    divider = i > 0,
                )
            }
        }
    }
    PaperCard(label = "Только в руках", note = "с компа видно, менять — тут") {
        KvNote(
            "Служба доступности и плавающие кнопки, разрешения Android, микрофон по умолчанию, места Wi-Fi, машина и метки NFC. Ключи и токены с телефона не уходят вовсе.",
        )
    }
    UpdateNowCard(app)
    StatusStrip(app, serviceEnabled, onOpenAccessibilitySettings, onOpen)
}

/** Строка о сервере: видно ли с компьютера, когда сервер видел настройки, что не так. */
internal fun phoneServerLine(v: ru.zf.pravka.data.PhoneRemoteSync.View, now: Long): String = when {
    !v.linked -> "Настройки этого телефона видно и можно менять с компьютера — на странице «Телефон» в вебе Дел, когда телефон подключён к Делам (Подключения → Дела)."
    v.oldServerAt > 0 -> "Сервер Дел ещё без настроек телефона — его обновят на компе (install-dela.ps1). Пока настройки только здесь."
    else -> "Эти настройки видно и можно менять с компьютера — страница «Телефон» в вебе Дел. Правку оттуда телефон берёт синком Дел и ставит так же, как тумблер здесь." +
        (if (v.okAt > 0) " Сервер видел их ${PhoneRemote.whenWords(v.okAt, now)}." else " Сервер их ещё не видел — первый снимок уйдёт в ближайшие минуты.") +
        (if (v.error.isNotBlank() && v.errorAt >= v.okAt) " Последний раз не ушли: ${v.error}" else "") +
        (if (v.pending > 0) " Ждут телефона: ${v.pending}." else "")
}

/** Служебные экраны бывшего «Ещё»: разборы, словарь, промпты, логи… — строками своих плашек. */
@Composable
private fun PhoneLinks(links: List<Pair<String, List<PhoneLink>>>) {
    for ((label, items) in links) {
        if (items.isEmpty()) continue
        PaperCard(label = label) {
            items.forEachIndexed { i, l ->
                KvSetRow(
                    l.title,
                    icon = l.icon,
                    sub = l.hint,
                    divider = i > 0,
                    onClick = l.onClick,
                    trailing = if (l.news) ({ ru.zf.pravka.ui.NewsDotMark() }) else null,
                )
            }
        }
    }
}

@Composable
private fun BuildLine() {
    KvNote(
        stringResource(R.string.build_info, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.BUILD_TIME),
        Modifier.padding(start = 4.dp),
    )
}
