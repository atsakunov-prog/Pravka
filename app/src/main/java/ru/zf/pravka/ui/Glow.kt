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
 * Свет вкладки — под содержимым: круглые пятна краски режима (второй заход,
 * 26.09.2026: «наверху свечение какое-то круглое», «должно как-то
 * двигаться»). Сам цвет кнопки — большое пятно слева сверху, тёплый сосед —
 * справа, холодный — ниже посередине, блик — у самого верха. Каждое медленно
 * обходит свою петлю, а соседи дышат силой навстречу друг другу — это и есть
 * перелив: цвет верха поворачивается на полшага туда и обратно.
 *
 * Рисуется ОДНИМ проходом, без слоёв (третий заход, 27.09.2026: «с края едет
 * тёмная резкая полоса»). Прежде каждое пятно было своим слоем со сдвигом и
 * прозрачностью, а слой с прозрачностью рисуется в свой буфер размером с
 * себя — сдвинутый, он показывал обрезанный край, и край ехал по экрану.
 * Теперь кисти строятся один раз на размер (`drawWithCache`), движение —
 * сдвиг холста перед кругом, сила — альфа самого круга. Ни буфера, ни края;
 * и свет без буфера рисуется выше себя — под строкой состояния, с самого
 * верха экрана, как у Gemini, без прежней ровной границы по строке. Сбоку
 * свет обрезан своей колонкой: на развороте он не ложится на колонку слева.
 * Пока вкладка не на экране, часы кадров стоят — и перелив тоже.
 */
@Composable
fun ModeGlowLayer(decor: ModeDecor) {
    val accent = decor.glowAccent ?: return
    val look = LocalCardLook.current
    val busy = (LocalGlowState.current?.busy ?: 0) > 0
    val target = ModeGlow.alpha(look.glow, busy)
    val strength = animateFloatAsState(target, tween(ModeGlow.FADE_MS), label = "modeGlow")
    if (target <= 0f && strength.value <= 0.001f) return
    val top = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()
    val blobs = remember(accent) { glowBlobs(accent) }
    // Часы перелива заводятся, только когда он включён: выключенный тумблер
    // «Переливы» — это стоящий свет и ни одного лишнего кадра.
    val drift: Drift? = if (look.glowMotion) rememberDrift() else null
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val w = size.width
                // Мерка — ширина, но не шире [MAX_HEIGHT_DP]: на развороте Fold
                // круги от ширины заливали бы весь экран, а свет — это верх.
                val u = minOf(w, ModeGlow.MAX_HEIGHT_DP.dp.toPx())
                val brushes = blobs.map { b ->
                    Brush.radialGradient(
                        0f to b.color,
                        0.3f to b.color.copy(alpha = 0.78f),
                        0.6f to b.color.copy(alpha = 0.36f),
                        0.85f to b.color.copy(alpha = 0.09f),
                        1f to b.color.copy(alpha = 0f),
                        center = Offset.Zero,
                        radius = u * b.r,
                    )
                }
                onDrawBehind {
                    val s = strength.value
                    if (s <= 0.001f) return@onDrawBehind
                    val wave = drift?.let { (sin(it.breath.value) + 1f) / 2f } ?: 0.5f
                    clipRect(left = 0f, top = -top, right = w, bottom = size.height) {
                        blobs.forEachIndexed { i, b ->
                            val p = drift?.phases?.get(i)?.value
                            val x = w * b.cx + (p?.let { cos(it) * ModeGlow.DRIFT * u * b.dx } ?: 0f)
                            val y = u * b.cy - top + (p?.let { sin(it) * ModeGlow.DRIFT * u * b.dy } ?: 0f)
                            val k = when (b.breath) {
                                Breath.NONE -> 1f
                                Breath.WITH -> 1f - ModeGlow.SHIMMER * (1f - wave)
                                Breath.AGAINST -> 1f - ModeGlow.SHIMMER * wave
                            }
                            translate(x, y) {
                                drawCircle(brushes[i], radius = u * b.r, center = Offset.Zero, alpha = s * b.alpha * k)
                            }
                        }
                    }
                }
            },
    )
}

/** Как пятно дышит силой: не дышит, в такт переливу или навстречу ему. */
private enum class Breath { NONE, WITH, AGAINST }

/**
 * Одно пятно: цвет, центр в долях мерки ([cx] — от ширины, [cy] — от верха
 * экрана, под строкой состояния), радиус [r] в долях мерки, сила [alpha],
 * размах петли по осям ([dx], [dy]) и как дышит.
 */
private class GlowBlob(
    val color: Color,
    val cx: Float,
    val cy: Float,
    val r: Float,
    val alpha: Float,
    val dx: Float,
    val dy: Float,
    val breath: Breath,
)

/** Пятна в порядке рисования: нижнее — первым. */
private fun glowBlobs(accent: Int): List<GlowBlob> = listOf(
    // Холодный сосед — ниже и шире: тело света, дышит навстречу тёплому.
    GlowBlob(Color(ModeGlow.cool(accent)), 0.5f, 0.46f, 0.78f, 0.85f, 0.7f, 0.5f, Breath.AGAINST),
    // Сам цвет кнопки — главное пятно у левого верхнего угла, не дышит.
    GlowBlob(Color(ModeGlow.tone(accent)), 0.2f, 0f, 1f, 1f, 1f, 0.6f, Breath.NONE),
    // Тёплый сосед — справа сверху.
    GlowBlob(Color(ModeGlow.warm(accent)), 0.88f, 0.1f, 0.78f, 0.8f, 0.8f, 0.7f, Breath.WITH),
    // Блик — у самого верха, над шапкой: светлая верхушка, как у кнопки.
    GlowBlob(Color(ModeGlow.lit(accent)), 0.56f, -0.04f, 0.5f, 0.7f, 0.9f, 0.4f, Breath.WITH),
)

/** Часы перелива: по петле на пятно (периоды не кратные) и одно дыхание соседей. */
private class Drift(val phases: List<State<Float>>, val breath: State<Float>)

@Composable
private fun rememberDrift(): Drift {
    val t = rememberInfiniteTransition(label = "modeGlowDrift")
    val phases = listOf(1.71f, 1f, 1.37f, 2.13f).mapIndexed { i, k ->
        t.loop((ModeGlow.DRIFT_MS * k).toInt(), "drift$i")
    }
    val breath = t.loop(ModeGlow.SHIMMER_MS, "breath")
    return remember(phases, breath) { Drift(phases, breath) }
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
