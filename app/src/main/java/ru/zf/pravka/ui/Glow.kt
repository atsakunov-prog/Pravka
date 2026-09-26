package ru.zf.pravka.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
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
 * Свет вкладки — слой под содержимым: три КРУГЛЫХ пятна одной краски (второй
 * заход, 26.09.2026: «наверху свечение какое-то круглое», «должно как-то
 * двигаться», «переливы… в рамках одного цвета»). Сам цвет — большое пятно
 * слева, светлый тон — справа, глубокий — ниже посередине; каждое медленно
 * обходит свою петлю, светлое и глубокое дышат силой навстречу друг другу —
 * это и есть перелив, оттенок не трогается.
 *
 * Дёшево нарочно: каждое пятно рисуется один раз на размер (`drawWithCache`)
 * в своём слое, а движение и дыхание — свойства слоя (сдвиг и прозрачность),
 * их меняет рендер без перерисовки. Прокрутка ленты слой не трогает. Пока
 * вкладка не на экране, часы кадров стоят — и перелив тоже.
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
    val top = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()
    val tone = remember(accent) { Color(ModeGlow.tone(accent)) }
    val light = remember(accent) { Color(ModeGlow.light(accent)) }
    val deep = remember(accent) { Color(ModeGlow.deep(accent)) }
    val moving = look.glowMotion
    val t = rememberInfiniteTransition(label = "modeGlowDrift")
    val a = t.loop(ModeGlow.DRIFT_MS, "a").takeIf { moving }
    val b = t.loop((ModeGlow.DRIFT_MS * 1.37f).toInt(), "b").takeIf { moving }
    val c = t.loop((ModeGlow.DRIFT_MS * 1.71f).toInt(), "c").takeIf { moving }
    val breath = t.loop(ModeGlow.SHIMMER_MS, "breath").takeIf { moving }
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
        // Глубокое — ниже и шире: тело света; дышит навстречу светлому.
        GlowBlob(deep, cx = 0.52f, cy = 0.5f, r = 0.66f, top = top, phase = c, dx = 0.7f, dy = 0.5f, breath = breath, inverse = true)
        // Сам цвет — главное пятно у левого верхнего угла.
        GlowBlob(tone, cx = 0.18f, cy = 0.04f, r = 0.9f, top = top, phase = a, dx = 1f, dy = 0.6f, breath = null, inverse = false)
        // Светлый тон — справа сверху: блик, который переливается.
        GlowBlob(light, cx = 0.86f, cy = 0.08f, r = 0.7f, top = top, phase = b, dx = 0.8f, dy = 0.7f, breath = breath, inverse = false)
    }
}

/** Угол петли: от нуля до полного круга за [periodMs], ровно, по кругу. */
@Composable
private fun androidx.compose.animation.core.InfiniteTransition.loop(periodMs: Int, label: String): State<Float> =
    animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart),
        label = label,
    )

/**
 * Одно круглое пятно света: круговой градиент с центром в долях ширины
 * ([cx], [cy] — от верха экрана, под строкой состояния; [r] — радиус в
 * долях ширины). [phase] — угол его петли, [breath] — фаза дыхания силы;
 * null — стоит на месте и не дышит (тумблер «Переливы» выключен).
 */
@Composable
private fun GlowBlob(
    color: Color,
    cx: Float,
    cy: Float,
    r: Float,
    top: Float,
    phase: State<Float>?,
    dx: Float,
    dy: Float,
    breath: State<Float>?,
    inverse: Boolean,
) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val u = minOf(size.width, ModeGlow.MAX_HEIGHT_DP.dp.toPx())
                phase?.value?.let { p ->
                    translationX = cos(p) * ModeGlow.DRIFT * u * dx
                    translationY = sin(p) * ModeGlow.DRIFT * u * dy
                }
                breath?.value?.let { q ->
                    val wave = (sin(q) + 1f) / 2f
                    val k = if (inverse) 1f - wave else wave
                    this.alpha = 1f - ModeGlow.SHIMMER * k
                }
            }
            .drawWithCache {
                // Меркой — ширина, но не шире [MAX_HEIGHT_DP]: на развороте Fold
                // круги от ширины заливали бы весь экран, а свет — это верх.
                val w = size.width
                val u = minOf(w, ModeGlow.MAX_HEIGHT_DP.dp.toPx())
                val center = Offset(w * cx, u * cy - top)
                val radius = u * r
                val brush = Brush.radialGradient(
                    0f to color,
                    0.35f to color.copy(alpha = 0.55f),
                    0.7f to color.copy(alpha = 0.14f),
                    1f to color.copy(alpha = 0f),
                    center = center,
                    radius = radius,
                )
                onDrawBehind {
                    drawCircle(brush, radius = radius, center = center)
                }
            },
    )
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

/**
 * Ждёт ли Claude на одной из дорог [routes] прямо сейчас — для пилюль, чья
 * работа идёт в службе (Дела, Деньги, еда голосом): вкладка своего флага не
 * держит, а поток запросов общий на всё приложение.
 */
@Composable
fun rememberRouteBusy(work: StateFlow<PravkaApp.LiveWork?>, vararg routes: String): Boolean {
    val live by work.collectAsState()
    return live?.route?.let { it in routes } == true
}
