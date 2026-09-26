package ru.zf.pravka.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.core.Countdown
import ru.zf.pravka.core.ModeGlow

// Свечение режима — версия 3 (26.09.2026). Владелец, глядя на Gemini: «мне
// очень нравится дизайн… у нас же даже цвета для них есть свои, можно всё это
// сделать очень красивым»; и, увидев макет: «сохраним цветность каждого
// направления и сделаем вот это свечение». Свет сверху вкладки — в цвете её
// кнопки на стекле и ТОЛЬКО в нём (оттенок сторожит `ModeGlowTest`); пока
// Claude работает в этой вкладке — ярче, одним плавным переходом, без
// пульса: пульсацию кнопки владелец снял в тот же день («дрожание отвлекает»).

/**
 * Цвет кнопки режима на стекле — отсюда свет вкладки и одежда строки ввода.
 * Константы берутся у самих кнопок: один цвет на стекле и в приложении, а не
 * два похожих. Еда — оливой своей вкладки: на стекле у неё общая с Телом
 * зелёная «Е», а вкладка оливковая, и свет должен быть цвета вкладки.
 * Служебным экранам своего света нет: там читают списки, а не день.
 */
val ModeDecor.glowAccent: Int?
    get() = when (this) {
        ModeDecor.PRAVKA -> ru.zf.pravka.trigger.FloatingButtonController.ACCENT
        ModeDecor.ZASECHKA -> ru.zf.pravka.trigger.ZasechkaButtonController.AMBER
        ModeDecor.DELA -> ru.zf.pravka.trigger.RaznoskaButtonController.INK
        ModeDecor.SPORT -> ru.zf.pravka.trigger.BodyButtonController.INK
        ModeDecor.FOOD -> FOOD_OLIVE
        ModeDecor.MONEY -> ru.zf.pravka.trigger.PravkaAccessibilityService.MONEY_INK
        ModeDecor.SERVICE -> null
    }

/** Олива Еды — тот же тон, что у краски вкладки (`ModeDecor.tint`, светлая ветка). */
private val FOOD_OLIVE = 0xFF5E7A1F.toInt()

/** Цвет пилюли строки ввода: свой у режима, у служебных — родной оранжевый «П». */
val ModeDecor.pillAccent: Int
    get() = glowAccent ?: ru.zf.pravka.trigger.FloatingButtonController.ACCENT

/**
 * Кто во вкладке сейчас ждёт Claude. Счётчик, а не флаг: в одной вкладке
 * бывает два ожидания сразу (вопрос тренеру и разбор подхода), и первое
 * кончившееся не должно гасить свет второго.
 */
class GlowState {
    var busy by mutableIntStateOf(0)
}

val LocalGlowState = compositionLocalOf<GlowState?> { null }

/** Пока [active] — вкладка ждёт Claude, и её свет ярче. Ставится там, где видно ожидание. */
@Composable
fun GlowBusy(active: Boolean) {
    val state = LocalGlowState.current ?: return
    DisposableEffect(state, active) {
        if (active) state.busy++
        onDispose { if (active) state.busy-- }
    }
}

/**
 * Свет вкладки — слой под содержимым. Рисуется один раз на размер
 * (`drawWithCache`), а сила — прозрачностью слоя: прокрутка ленты и переход
 * «ждёт — не ждёт» его не перерисовывают. Три пятна одной краски: сам цвет
 * слева, светлый тон справа, глубокий посередине, — к середине экрана они
 * сходят в фон.
 */
@Composable
fun ModeGlowLayer(decor: ModeDecor) {
    val accent = decor.glowAccent ?: return
    val look = LocalCardLook.current
    val busy = (LocalGlowState.current?.busy ?: 0) > 0
    val target = ModeGlow.alpha(look.glow, busy)
    val alpha by animateFloatAsState(target, tween(ModeGlow.FADE_MS), label = "modeGlow")
    if (target <= 0f && alpha <= 0.001f) return
    // Свет начинается с самого верха экрана, под строкой состояния, как у
    // Gemini: слой стоит под шапкой, а рисует выше себя на высоту строки.
    // Обрезки у слоя нет, и строку состояния система рисует поверх.
    val top = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()
    val tone = remember(accent) { Color(ModeGlow.tone(accent)) }
    val light = remember(accent) { Color(ModeGlow.light(accent)) }
    val deep = remember(accent) { Color(ModeGlow.deep(accent)) }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha }
            .drawWithCache {
                val w = size.width
                val h = minOf(size.height * ModeGlow.HEIGHT_FRACTION, ModeGlow.MAX_HEIGHT_DP.dp.toPx()) + top
                onDrawBehind {
                    translate(top = -top) {
                        ellipse(deep, 0.8f, cx = w * 0.55f, cy = h * 0.42f, rx = w * 0.7f, ry = h * 0.58f)
                        ellipse(tone, 1f, cx = 0f, cy = 0f, rx = w * 1.05f, ry = h)
                        ellipse(light, 0.85f, cx = w, cy = 0f, rx = w * 0.95f, ry = h * 0.9f)
                    }
                }
            },
    )
}

/**
 * Эллиптическое пятно света: круговой градиент, сжатый по вертикали. Радиус
 * круга — [rx]; сжатие делает из него [ry]. Центр и прямоугольник заданы в
 * несжатых координатах, поэтому делятся на коэффициент сжатия.
 */
private fun DrawScope.ellipse(color: Color, peak: Float, cx: Float, cy: Float, rx: Float, ry: Float) {
    if (rx <= 0f || ry <= 0f) return
    val k = ry / rx
    val brush = Brush.radialGradient(
        0f to color.copy(alpha = peak),
        0.45f to color.copy(alpha = peak * 0.4f),
        1f to color.copy(alpha = 0f),
        center = Offset(cx, cy / k),
        radius = rx,
    )
    scale(1f, k, pivot = Offset.Zero) {
        drawRect(brush, topLeft = Offset(0f, 0f), size = Size(size.width, (cy + ry) / k))
    }
}

// ---------------------------------------------------------------------------
// Секунды до ответа — в приложении
// ---------------------------------------------------------------------------

/**
 * Сколько осталось ждать идущий запрос к Claude: «12», «9,4» — или null
 * (запроса нет или срок вышел, и тогда честно — просто искры). Те же
 * обещание и правило подписи, что у секунд на кнопке (`core/Countdown.kt`).
 * Состояние, а не значение: тикает раз в 100 мс, и перерисовываются только
 * те, кто его читает, — строка «Причёсываю» и кружок строки ввода.
 */
val LocalClaudeSeconds = staticCompositionLocalOf<State<String?>> { mutableStateOf(null) }

/** Часы для [LocalClaudeSeconds] — один раз на приложение, в `MainActivity`. */
@Composable
fun rememberClaudeSeconds(work: StateFlow<PravkaApp.LiveWork?>): State<String?> {
    val live by work.collectAsState()
    val seconds = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(live) {
        val w = live
        if (w == null) {
            seconds.value = null
            return@LaunchedEffect
        }
        while (true) {
            val left = w.expectMs - (android.os.SystemClock.uptimeMillis() - w.startedAt)
            seconds.value = Countdown.label(left)
            if (left < 0L) break
            delay(100)
        }
    }
    return seconds
}
