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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.runtime.mutableFloatStateOf
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
// кнопки на стекле; с 27.09 к нему — два соседних оттенка на полшага по кругу
// («переливы с чуть соседними цветами»), чужих цветов нет (сторожит
// `ModeGlowTest`). Пока Claude работает в этой вкладке — ярче, одним плавным
// переходом, без пульса: пульсацию кнопки владелец снял («дрожание отвлекает»).

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
        ModeDecor.TODAY, ModeDecor.SERVICE -> null
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
 * Насколько приглушить свет экрана — «Сегодня» со сжатой шапкой смотрит в
 * прошлое, и свет там тише (DESIGN §4.5: ×0.65). 1 — как есть.
 */
val LocalGlowDim = compositionLocalOf { mutableFloatStateOf(1f) }

/**
 * Свет экрана — Правка 4.0 (07.10.2026, DESIGN §4.5): два-три круглых пятна
 * сверху, к середине экрана сходят в ночь. Числа — первый `div` каждого
 * макета (`docs/design/redesign-4/mockups`): в режиме — пятно цвета кнопки
 * слева, светлый сосед справа и слабое пятно ниже к середине; на «Сегодня» —
 * крем слева и тёплый янтарь справа. Пятна медленно плывут по своим петлям
 * (17, 23 и 29 с), без мигания; пока Claude работает — на 0.15 ярче, одним
 * переходом 600 мс.
 *
 * Рисуется ОДНИМ проходом, без слоёв (27.09.2026: «с края едет тёмная
 * резкая полоса» — слой с прозрачностью рисуется в свой буфер, и сдвинутый
 * буфер показывал край). Кисти строятся раз на размер, движение — сдвиг
 * холста, сила — альфа самого пятна. Эллипс CSS `radial-gradient(60% 40% …)`
 * — круг, сжатый по высоте. Пока экран не на виду, часы стоят.
 */
@Composable
fun ModeGlowLayer(decor: ModeDecor) {
    val spec = Modes.of(decor).light
    val look = LocalCardLook.current
    val busy = (LocalGlowState.current?.busy ?: 0) > 0
    // Ползунок «Свечение режима» (`CardLook.glow`) — множитель к силе макета:
    // заводское 0.9 — ровно макет.
    val user = (look.glow / ModeGlow.DEFAULT).coerceIn(0f, 1.5f)
    val dim by LocalGlowDim.current
    val target = if (user <= 0f) 0f else (spec.opacity * user * dim + if (busy) BUSY_BOOST else 0f).coerceIn(0f, 1f)
    val strength = animateFloatAsState(target, tween(BUSY_FADE_MS), label = "modeGlow")
    if (target <= 0f && strength.value <= 0.001f) return
    val top = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()
    val drift: Drift? = if (look.glowMotion) rememberDrift(spec.blobs.size) else null
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val w = size.width
                // Слой света макета — `heightDp` от самого верха экрана, вместе
                // со строкой состояния: свет идёт и под ней, как у Gemini.
                val h = spec.heightDp.dp.toPx()
                val brushes = spec.blobs.map { b ->
                    val rx = w * b.rx
                    Brush.radialGradient(
                        0f to b.color,
                        0.72f to b.color.copy(alpha = 0f),
                        center = Offset.Zero,
                        radius = rx,
                    )
                }
                onDrawBehind {
                    val s = strength.value
                    if (s <= 0.001f) return@onDrawBehind
                    clipRect(left = 0f, top = -top, right = w, bottom = size.height) {
                        spec.blobs.forEachIndexed { i, b ->
                            val rx = w * b.rx
                            val ry = h * b.ry
                            val p = drift?.phases?.getOrNull(i)?.value
                            val dx = p?.let { cos(it) * DRIFT_DP.dp.toPx() } ?: 0f
                            val dy = p?.let { sin(it) * DRIFT_DP.dp.toPx() * 0.6f } ?: 0f
                            val cx = w * b.cx + dx
                            val cy = h * b.cy - top + dy
                            translate(cx, cy) {
                                scale(1f, ry / rx, pivot = Offset.Zero) {
                                    drawCircle(brushes[i], radius = rx, center = Offset.Zero, alpha = s)
                                }
                            }
                        }
                    }
                }
            },
    )
}

/** Пока Claude работает — свет ярче на столько (DESIGN §4.5). */
private const val BUSY_BOOST = 0.15f
private const val BUSY_FADE_MS = 600

/** Размах петли пятна, dp: дрейф, а не полёт. */
private const val DRIFT_DP = 18

/** Периоды петель пятен — 17, 23 и 29 с (DESIGN §4.5), не кратные: свет не повторяет узор. */
private val DRIFT_PERIODS_MS = listOf(17_000, 23_000, 29_000)

/** Часы дрейфа: по петле на пятно. */
private class Drift(val phases: List<State<Float>>)

@Composable
private fun rememberDrift(count: Int): Drift {
    val t = rememberInfiniteTransition(label = "modeGlowDrift")
    val phases = (0 until count).map { i ->
        t.loop(DRIFT_PERIODS_MS[i % DRIFT_PERIODS_MS.size], "drift$i")
    }
    return remember(phases) { Drift(phases) }
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

/**
 * Доля обещанного времени, которая уже прошла (0…1), — для заливки слева
 * направо у строки «сказать» и у «Claude думает» (DESIGN §4.4): то же
 * обещание, что секунды. null — запроса нет.
 */
val LocalClaudeProgress = staticCompositionLocalOf<State<Float?>> { mutableStateOf(null) }

/** Часы для [LocalClaudeProgress] — рядом с секундами, в `MainActivity`. */
@Composable
fun rememberClaudeProgress(work: StateFlow<PravkaApp.LiveWork?>): State<Float?> {
    val live by work.collectAsState()
    val progress = remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(live) {
        val w = live
        if (w == null) {
            progress.value = null
            return@LaunchedEffect
        }
        while (true) {
            val passed = android.os.SystemClock.uptimeMillis() - w.startedAt
            progress.value = if (w.expectMs <= 0L) null else (passed.toFloat() / w.expectMs).coerceIn(0f, 1f)
            if (passed >= w.expectMs) break
            delay(100)
        }
    }
    return progress
}

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
