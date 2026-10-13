package ru.zf.pravka.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Вид Правки 5.0 (13.10.2026, docs/redesign-5-plan.md §2): телефон — копия общего
// вида Ковчега версии 2, того же, что у веба Дел и всех страниц сервера.
// Тёплый светлый фон и белые панели, тёмная — тёплый графит; один акцент;
// панели плоские — радиус 10 и линия; без стекла, свечения и градиентов.
//
// Почему состояние, а не CompositionLocal. Цвета 4.0 читаются константами
// `Ink.*` из сотни мест, и половина из них — внутри `drawBehind` и прочих
// лямбд рисования, куда CompositionLocal не достаёт. Поэтому `Ink`, `Modes` и
// всё остальное стали геттерами, которые смотрят сюда: это состояние Compose
// (снимок), и экран, прочитавший цвет хоть в композиции, хоть в рисовании,
// перерисуется сам, когда тему или вид переключат. Тема у приложения одна на
// процесс — глобальное здесь честно.

/** Какой вид и какая тема сейчас. Ставит `PravkaApp` из настроек, тёмность решает `PravkaTheme`. */
object Look {

    /** Тема v2: как в системе или выбором (у 4.0 своей светлой нет — она всегда ночь). */
    enum class Theme(val key: String) {
        SYSTEM("system"),
        LIGHT("light"),
        DARK("dark"),
        ;

        companion object {
            fun of(key: String?): Theme = entries.firstOrNull { it.key == key } ?: SYSTEM
        }
    }

    /**
     * Вид 4.0 — переключатель на время переезда (план §6, этап 1): стекло,
     * свечение, монеты и прежние шапки целиком, как было до 5.0. С завода — нет.
     */
    var v4 by mutableStateOf(false)

    /** Выбор темы из настроек. */
    var theme by mutableStateOf(Theme.SYSTEM)

    /**
     * Тёмная ли тема v2 прямо сейчас: выбор владельца или, при «как в
     * системе», тема телефона. Пишет `PravkaTheme` — только он знает систему.
     */
    var dark by mutableStateOf(false)

    val v2: Boolean get() = !v4

    /**
     * Палитры v2 с поправками Свода `ui.look` поверх заводских (Правка 5.0,
     * этап 7, 14.10.2026): акцент и при желании любые токены обеих тем
     * владелец выбирает в вебе Дел — и тот же вид у телефона, веба и всех
     * страниц Ковчега. Ставит `PravkaApp` по синку Свода ([tokens]); без
     * записи — заводские [KvPalette.LIGHT] и [KvPalette.DARK]. Состояние:
     * экран, прочитавший `Kv`, перерисуется сам.
     */
    var paletteLight by mutableStateOf(KvPalette.LIGHT)
        private set
    var paletteDark by mutableStateOf(KvPalette.DARK)
        private set
    private var applied = ru.zf.pravka.core.Kovcheg.Tokens()

    /** Токены из Свода → палитры обеих тем. Те же — ничего не меняется (без лишней перерисовки). */
    fun tokens(t: ru.zf.pravka.core.Kovcheg.Tokens) {
        if (t == applied) return
        applied = t
        paletteLight = KvPalette.LIGHT.with(t.light, dark = false)
        paletteDark = KvPalette.DARK.with(t.dark, dark = true)
    }
}

/**
 * Токены вида v2 — ровно переменные общего слоя Ковчега (`kovcheg.css`, у
 * Дел — `server/pravka_dela/static/style.css`), имена те же.
 */
@Immutable
class KvPalette(
    val bg: Color,
    val side: Color,
    val panel: Color,
    val panel2: Color,
    val hover: Color,
    val hover2: Color,
    val line: Color,
    val lineSoft: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val icon: Color,
    val check: Color,
    val ok: Color,
    val warn: Color,
    val bad: Color,
    val claude: Color,
    val accent: Color,
    val accentInk: Color,
    val accentSoft: Color,
    val barFill: Color,
    /** «Чей это» — категории ленты, спикеры: `--sp0…--sp7`. */
    val sp: List<Color>,
    /** Затемнение под меню и листами. */
    val scrim: Color,
    /** Шкала графиков: [1] — главный цвет, [0] и [2] — соседи, [3] и [4] — бледнее (выключенное, фон). */
    val ramp: List<Color>,
) {
    /**
     * Та же палитра с поправками Свода `ui.look` (имя токена — переменная
     * общего слоя без «--», цвет ARGB). Незнакомое имя — мимо. Новый акцент —
     * и новая шкала графиков от него (`Kovcheg.ramp`): иначе столбики остались
     * бы прежним синим. Пусто — эта же палитра, тем же объектом.
     */
    fun with(t: Map<String, Long>, dark: Boolean): KvPalette {
        if (t.isEmpty()) return this
        fun c(name: String, was: Color) = t[name]?.let { Color(it) } ?: was
        val accentNew = t["accent"]?.let { Color(it) }?.takeIf { it != accent }
        return KvPalette(
            bg = c("bg", bg), side = c("side", side), panel = c("panel", panel), panel2 = c("panel-2", panel2),
            hover = hover, hover2 = hover2, line = c("line", line), lineSoft = c("line-soft", lineSoft),
            text = c("text", text), muted = c("muted", muted), faint = c("faint", faint), icon = c("icon", icon),
            check = c("check", check), ok = c("ok", ok), warn = c("warn", warn), bad = c("bad", bad), claude = c("claude", claude),
            accent = c("accent", accent), accentInk = c("accent-ink", accentInk), accentSoft = c("accent-soft", accentSoft),
            barFill = c("bar-fill", barFill),
            sp = sp.mapIndexed { i, was -> c("sp$i", was) },
            scrim = scrim,
            ramp = if (accentNew == null) ramp else ru.zf.pravka.core.Kovcheg.ramp(t.getValue("accent"), dark).map { Color(it) },
        )
    }

    companion object {
        val LIGHT = KvPalette(
            bg = Color(0xFFF6F4EF), side = Color(0xFFF1EEE8), panel = Color(0xFFFFFFFF), panel2 = Color(0xFFF3F1EC),
            hover = Color(0xFF1C1B19).copy(alpha = 0.045f), hover2 = Color(0xFF1C1B19).copy(alpha = 0.08f),
            line = Color(0xFFE6E2D9), lineSoft = Color(0xFFEEEBE4),
            text = Color(0xFF1C1B19), muted = Color(0xFF5F5B53), faint = Color(0xFF77736A), icon = Color(0xFF77736A),
            check = Color(0xFFA19C92),
            ok = Color(0xFF2F7D4F), warn = Color(0xFF9A5B00), bad = Color(0xFFB42318), claude = Color(0xFFC15F3C),
            accent = Color(0xFF2747C9), accentInk = Color(0xFFFFFFFF), accentSoft = Color(0xFFE9EDFB),
            barFill = Color(0xFF3A3833),
            sp = listOf(
                Color(0xFF2747C9), Color(0xFF9A5B00), Color(0xFF2F7D4F), Color(0xFFA23B72),
                Color(0xFF0E7490), Color(0xFF6D4FC4), Color(0xFFB4461F), Color(0xFF5F5B53),
            ),
            scrim = Color(0xFF14120E).copy(alpha = 0.30f),
            ramp = listOf(Color(0xFF6D84E0), Color(0xFF2747C9), Color(0xFF1C3497), Color(0xFFB8C4F2), Color(0xFFDFE5FA)),
        )

        val DARK = KvPalette(
            bg = Color(0xFF161513), side = Color(0xFF1A1917), panel = Color(0xFF1F1E1B), panel2 = Color(0xFF262521),
            hover = Color(0xFFFFFAF0).copy(alpha = 0.04f), hover2 = Color(0xFFFFFAF0).copy(alpha = 0.075f),
            line = Color(0xFF2E2C28), lineSoft = Color(0xFF282622),
            text = Color(0xFFECEAE5), muted = Color(0xFFB3AFA6), faint = Color(0xFF8F8B82), icon = Color(0xFF9C988F),
            check = Color(0xFF6E6A62),
            ok = Color(0xFF7CC59A), warn = Color(0xFFE3A957), bad = Color(0xFFF08A7E), claude = Color(0xFFE08A63),
            accent = Color(0xFF93ABF8), accentInk = Color(0xFF141414), accentSoft = Color(0xFF252C47),
            barFill = Color(0xFFB3AFA6),
            sp = listOf(
                Color(0xFF93ABF8), Color(0xFFE3A957), Color(0xFF7CC59A), Color(0xFFE58CBC),
                Color(0xFF67C3D8), Color(0xFFB9A3F5), Color(0xFFEC9C74), Color(0xFFB3AFA6),
            ),
            scrim = Color(0xFF000000).copy(alpha = 0.45f),
            ramp = listOf(Color(0xFFC3D1FB), Color(0xFF93ABF8), Color(0xFF6F8BE6), Color(0xFF3C4A80), Color(0xFF2A3359)),
        )
    }
}

/** Токены текущей темы v2 (с поправками Свода `ui.look`) — `Kv.accent`, `Kv.line`… Читать там, где рисуют: это состояние. */
val Kv: KvPalette get() = if (Look.dark) Look.paletteDark else Look.paletteLight

/** Размеры вида v2: панели 10, кнопки и поля 8, счётчики и метки 5 (план §2). */
object KvShape {
    val Panel = 10.dp
    val Control = 8.dp
    val Tag = 5.dp
}
