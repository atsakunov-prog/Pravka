package ru.zf.slushalka.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Загиб листа под пальцем.
 *
 * Четвёртая попытка переворота в книге, и первая не «картоном»: три прежних
 * крутили страницу целиком, как жёсткую пластину вокруг корешка, и владелец
 * забраковал все («перелистывались гигантскими картонами»). Здесь лист
 * **гнётся**: там, где его тянут, бумага заворачивается валиком, за валиком
 * видна следующая страница, поверх нетронутой части ложится изнанка листа,
 * и всё это - под пальцем: точка, за которую взялись, идёт за ним, сгиб
 * встаёт перпендикулярно ходу руки, и тянуть можно хоть за угол, хоть за
 * середину края.
 *
 * Считается шейдером ([CURL_AGSL]) по готовому слою страницы: для каждой
 * точки экрана шейдер решает, что в ней видно - нетронутый лист, лицо листа
 * на валике, его изнанка, или ничего (тогда сквозь неё видна страница под
 * ним) - и тянет цвет из нужного места слоя. Геометрия классическая:
 * цилиндр радиуса [radius], касающийся страницы по линии сгиба; лист за
 * сгибом обёрнут вокруг цилиндра на полоборота и дальше лежит плоско, изнанкой
 * вверх. Изнанка - бумага с еле заметным зеркальным просветом лица.
 *
 * Нужен `RuntimeShader`, то есть Android 13. Ниже, на электронной бумаге и
 * на половине разворота (там книга ездит камерой, и гнуть лист поверх этого
 * хода некуда) - растворение, как и было.
 */

/** Есть ли на этой прошивке шейдер загиба: `RuntimeShader` появился в Android 13. */
val curlSupported: Boolean
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * За что взялись и куда ведут. Координаты - слота пейджера, в пикселях;
 * читается в фазе рисования, поэтому всё - снимки состояния.
 */
class CurlState {
    /** Слот пейджера, чей лист сейчас гнётся; -1 - никакой. */
    var slot by mutableIntStateOf(-1)
    /**
     * Вперёд - лист текущего слота уходит налево; назад - лист предыдущего
     * возвращается направо. Шейдеру это важно: у оборота назад «нетронутая»
     * часть листа - это уже лёгшая страница, и красить её бумагой нельзя.
     */
    var forward by mutableStateOf(true)
    /** Точка листа, за которую взялись. */
    var grab by mutableStateOf(Offset.Zero)
    /** Куда палец ушёл по вертикали от места захвата. */
    var dy by mutableFloatStateOf(0f)
    /**
     * Наклон сгиба для оборота без пальца (тап, кнопка): доля пути, на
     * которую точка захвата уходит вверх или вниз. У пальца наклон свой.
     */
    var tilt by mutableFloatStateOf(0f)
    /**
     * Путь пальца на полный оборот: столько, чтобы точка захвата дошла до
     * корешка и ушла за него на столько же - тогда лист ложится целиком.
     */
    var travel by mutableFloatStateOf(1f)
    /** Доводка после отпускания; новый жест её обрывает. */
    var settling: Job? = null

    /** Оборот без пальца: за наружный край листа на этой высоте. */
    fun grabEdge(slot: Int, sheet: Rect, y: Float, forward: Boolean) {
        this.slot = slot
        this.forward = forward
        grab = Offset(sheet.right - 2f, y.coerceIn(sheet.top, sheet.bottom))
        dy = 0f
        // Верхнюю половину тянут вниз, нижнюю вверх: угол загибается
        // наискось, как когда берут страницу за уголок.
        tilt = if (y < sheet.center.y) 0.2f else -0.2f
        travel = travelFor(sheet)
    }

    /** Путь, за который лист ложится: до корешка и на столько же за него. */
    fun travelFor(sheet: Rect): Float = (2f * (grab.x - sheet.left)).coerceAtLeast(0.45f * sheet.width)
}

@Composable
fun rememberCurlShader(): RuntimeShader? = remember {
    if (curlSupported) makeCurlShader() else null
}

/**
 * Шейдер собирается прошивкой при создании; если она его не приняла (у
 * вендора свой Skia, синтаксис разошёлся), загиба не будет - будет
 * растворение, а не падение читалки.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun makeCurlShader(): RuntimeShader? = runCatching { RuntimeShader(CURL_AGSL) }.getOrNull()

/**
 * Загиб на слое слота. [offset] - положение слота в пейджере: 0 - лист лежит,
 * 1 - перевёрнут, между - гнётся. [sheet] - лист, который гнётся: правая
 * страница разворота или единственная; [landLeft] - левее этого изнанка не
 * рисуется (корешок закрытой книги, за который лист уходит), null - рисуется
 * везде (в развороте изнанка ложится на левую страницу).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun Modifier.pageCurl(
    shader: RuntimeShader,
    state: CurlState,
    slot: Int,
    paper: Color,
    sheet: Density.(Size) -> Rect,
    landLeft: Density.(Size) -> Float?,
    offset: () -> Float,
): Modifier = graphicsLayer {
    val off = offset()
    when {
        // Перевёрнут: невидим и там, где его оставил пейджер, - за экраном,
        // чтобы не ловить нажатия поверх читаемой страницы.
        off >= 1f -> {
            alpha = 0f
            renderEffect = null
        }
        // Ещё не тронут: лежит на своём месте под верхним.
        off <= 0f -> {
            translationX = off * size.width
            alpha = 1f
            renderEffect = null
        }
        else -> {
            translationX = off * size.width
            alpha = 1f
            val s = sheet(this, size)
            val dp = 1.dp.toPx()
            // Радиус валика растёт с ходом и к концу опадает: в начале лист
            // только-только приподнят, к концу ложится плоско. Без этого в
            // самом конце у корешка оставался бы стоячий валик.
            val radiusMax = (s.width * 0.16f).coerceIn(16f * dp, 64f * dp)
            val radius = radiusMax * minOf(1f, off * 5f, (1f - off) * 5f) + 0.5f
            val mine = state.slot == slot
            val grab = if (mine) state.grab else Offset(s.right - 2f, s.center.y)
            val travel = if (mine) state.travel else 2f * (grab.x - s.left)
            val dest = Offset(
                grab.x - off * travel,
                grab.y + (if (mine) state.dy + state.tilt * off * travel else 0f),
            )
            // Сгиб перпендикулярен ходу руки: от пальца к месту захвата.
            var dx = grab.x - dest.x
            var dyv = grab.y - dest.y
            val len = hypot(dx, dyv)
            if (len < 1f) {
                dx = 1f
                dyv = 0f
            } else {
                dx /= len
                dyv /= len
            }
            // Сильнее сорока градусов лист не гнут: у корешка он пришит.
            val maxTilt = sin(Math.toRadians(40.0)).toFloat()
            if (abs(dyv) > maxTilt) {
                dyv = if (dyv > 0f) maxTilt else -maxTilt
                dx = cos(Math.toRadians(40.0)).toFloat()
            }
            if (dx <= 0f) {
                dx = 1f
                dyv = 0f
            }
            // Линия сгиба: точка захвата, обёрнутая через валик, должна
            // оказаться под пальцем - отсюда середина между ними минус
            // половина полуокружности валика.
            val pi = Math.PI.toFloat()
            val fold = Offset(
                (grab.x + dest.x) / 2f - dx * pi * radius / 2f,
                (grab.y + dest.y) / 2f - dyv * pi * radius / 2f,
            )
            // На самом излёте лист растворяется: валик уже опал, и остаток -
            // тени по краям - уступает странице под ним.
            val opacity = 1f - smoothstep(0.9f, 1f, off)
            shader.setFloatUniform("sheet", s.left, s.top, s.right, s.bottom)
            shader.setFloatUniform("landLeft", landLeft(this, size) ?: -1e9f)
            shader.setFloatUniform("fold", fold.x, fold.y)
            shader.setFloatUniform("dir", dx, dyv)
            shader.setFloatUniform("radius", radius)
            shader.setFloatUniform("paper", paper.red, paper.green, paper.blue)
            shader.setFloatUniform("ghost", 0.16f)
            shader.setFloatUniform("backward", if (mine && !state.forward) 1f else 0f)
            shader.setFloatUniform("opacity", opacity)
            // Эффект собирается заново на каждый кадр: значения uniform
            // снимаются при сборке, поменять их на живом эффекте нельзя.
            renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
        }
    }
}

private fun smoothstep(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * Жест загиба: палец ведёт лист, пейджер идёт следом.
 *
 * Пейджеру своё листание выключено (`userScrollEnabled = false`): его ход
 * равен ширине экрана, а листу до корешка - другой путь, и точка захвата
 * должна идти ровно за пальцем. Поэтому смещение пальца пересчитывается в
 * ход пейджера через [CurlState.travel], а доводка после отпускания - своя.
 *
 * [sheet] - лист в координатах этого узла; [onSettled] зовётся с целевым
 * слотом, когда палец отпущен и решено, ложится лист обратно или переворачивается.
 */
fun Modifier.curlDrag(
    state: CurlState,
    pager: PagerState,
    scope: CoroutineScope,
    enabled: Boolean,
    sheet: Density.(Size) -> Rect,
    settle: suspend (target: Int) -> Unit,
): Modifier = if (!enabled) this else pointerInput(state, pager) {
    val tracker = VelocityTracker()
    var moved = Offset.Zero
    var decided = false
    var baseDy = 0f
    val flick = 700f * density
    detectDragGestures(
        onDragStart = {
            state.settling?.cancel()
            state.settling = null
            moved = Offset.Zero
            decided = false
            baseDy = 0f
            tracker.resetTracking()
            // Лист поймали на лету, посреди доводки: гнём его дальше с того
            // же места, а не начинаем новый оборот другого листа.
            val pos = pager.currentPage + pager.currentPageOffsetFraction
            if (state.slot >= 0 && pos - state.slot > 0f && pos - state.slot < 1f) {
                decided = true
                baseDy = state.dy
            } else {
                state.slot = -1
            }
        },
        onDrag = { change, amount ->
            change.consume()
            moved += amount
            tracker.addPosition(change.uptimeMillis, change.position)
            val s = sheet(this@pointerInput, Size(size.width.toFloat(), size.height.toFloat()))
            if (!decided) {
                // Пока не ясно, куда тянут, - ждём: направление решает, чей
                // лист гнётся, и менять его посреди хода нельзя.
                if (abs(moved.x) < 2f * density) return@detectDragGestures
                decided = true
                val start = change.position - moved
                val forward = moved.x < 0f
                val pos = pager.currentPage + pager.currentPageOffsetFraction
                state.forward = forward
                if (forward) {
                    if (pager.currentPage >= pager.pageCount - 1) {
                        decided = false
                        return@detectDragGestures
                    }
                    state.slot = pager.currentPage
                    // Взялись левее листа (за левую страницу, за поле) -
                    // значит, за его ближний край.
                    state.grab = Offset(
                        start.x.coerceIn(s.left + 0.35f * s.width, s.right - 2f),
                        start.y.coerceIn(s.top, s.bottom),
                    )
                } else {
                    if (pager.currentPage <= 0 && pos <= 0f) {
                        decided = false
                        return@detectDragGestures
                    }
                    // Назад: лист лежит перевёрнутым слева, и под пальцем -
                    // его точка, отражённая через корешок.
                    state.slot = pager.currentPage - 1
                    state.grab = Offset(
                        (2f * s.left - start.x).coerceIn(s.left + 0.05f * s.width, s.right - 2f),
                        start.y.coerceIn(s.top, s.bottom),
                    )
                }
                state.dy = 0f
                state.tilt = 0f
                state.travel = state.travelFor(s)
            }
            if (state.slot < 0) return@detectDragGestures
            state.dy = baseDy + moved.y
            // Пейджер меряет ход своей шириной, лист - своим путём.
            pager.dispatchRawDelta(-amount.x * size.width / state.travel)
        },
        onDragEnd = { finish(state, pager, scope, tracker, flick, settle) },
        onDragCancel = { finish(state, pager, scope, tracker, flick, settle) },
    )
}

/** Палец отпущен: лист либо ложится обратно, либо доворачивается. */
private fun finish(
    state: CurlState,
    pager: PagerState,
    scope: CoroutineScope,
    tracker: VelocityTracker,
    flick: Float,
    settle: suspend (Int) -> Unit,
) {
    val slot = state.slot
    if (slot < 0) return
    val velocity = tracker.calculateVelocity().x
    val pos = pager.currentPage + pager.currentPageOffsetFraction
    // Сколько листа перевёрнуто: 0 - лежит на месте, 1 - перевёрнут.
    val progress = (pos - slot).coerceIn(0f, 1f)
    val forwardTarget = (slot + 1).coerceAtMost(pager.pageCount - 1)
    // Начатый оборот доводится раньше половины: страницу, которую уже
    // потянули, редко хотят вернуть, а вот отпустить на трети хода - сплошь.
    val target = when {
        velocity < -flick -> forwardTarget
        velocity > flick -> slot
        state.forward -> if (progress > 0.35f) forwardTarget else slot
        else -> if (progress < 0.65f) slot else forwardTarget
    }
    state.settling = scope.launch {
        settle(target)
        // Лист лёг: дальше слои живут по месту в пейджере, а не по жесту.
        state.slot = -1
    }
}

/**
 * Шейдер загиба. Координаты - пиксели слоя; `content` - сам слой страницы.
 *
 * `d` - расстояние точки экрана от линии сгиба вдоль хода: отрицательное -
 * по эту сторону сгиба (нетронутый лист, левая страница, и над ними, может
 * быть, плоско лежащий завёрнутый край), от нуля до радиуса - валик (сверху
 * изнанка, под ней лицо), дальше - лист ушёл: под ним видна следующая
 * страница и тень валика на ней. Точка листа на валике ищется по дуге:
 * `theta = asin(d / radius)`, лицо на `theta`, изнанка на `pi - theta` от сгиба.
 *
 * Что показывать в лежащей плоско части листа, зависит от места. Над своей
 * страницей (правее корешка) это изнанка - бумага с еле заметным просветом
 * лица; при обороте назад та же часть - уже лёгшая страница, и там просто
 * содержимое слоя. Над левой страницей (левее корешка) лист рисуется
 * прозрачным: под ним в этом же месте лежит ровно та страница, которая на
 * нём напечатана с изнанки, - следующий разворот уже подложен снизу, - и
 * подставлять вместо неё бумагу значило бы прятать текст. Левее [landLeft]
 * (за корешком закрытой книги) листа нет.
 *
 * Свет сверху: лицо у сгиба светлое и темнеет к силуэту валика, изнанка
 * от силуэта светлеет к макушке и лежит плоско светлой, с тенью в складке у
 * самого сгиба. Все переходы сшиты по яркости, чтобы на границах зон не было
 * швов. Там, где материала листа нет (валик над чужой страницей, край с
 * наклоном), показывается содержимое слоя как есть.
 */
private const val CURL_AGSL = """
uniform shader content;
uniform float4 sheet;
uniform float landLeft;
uniform float2 fold;
uniform float2 dir;
uniform float radius;
uniform float3 paper;
uniform float ghost;
uniform float backward;
uniform float opacity;

const float PI = 3.141592653589793;

float inSheet(float2 p) {
    return (p.x >= sheet.x && p.x <= sheet.z && p.y >= sheet.y && p.y <= sheet.w) ? 1.0 : 0.0;
}

float outside(float2 p) {
    return max(max(sheet.x - p.x, p.x - sheet.z), max(sheet.y - p.y, p.y - sheet.w));
}

half4 backFace(float2 src, float shade) {
    half4 c = content.eval(src);
    half3 rgb = mix(half3(paper), c.rgb, half(ghost));
    return half4(rgb * half(shade), 1.0);
}

half4 main(float2 p) {
    float d = dot(p - fold, dir);
    float2 base = p - d * dir;
    half4 outc;
    if (d < 0.0) {
        float2 src = base + dir * (PI * radius - d);
        float crease = 0.16 * exp(d / (0.4 * radius + 1.0));
        if (inSheet(src) > 0.5 && p.x >= landLeft) {
            if (p.x < sheet.x) {
                outc = half4(0.0, 0.0, 0.0, crease);
            } else if (backward > 0.5) {
                outc = content.eval(p);
                outc.rgb *= half(1.0 - crease);
            } else {
                outc = backFace(src, 1.0 - crease);
            }
        } else {
            outc = content.eval(p);
            float gap = outside(src);
            float s = (p.x >= landLeft) ? 0.20 * (1.0 - smoothstep(0.0, 0.8 * radius + 4.0, gap)) : 0.0;
            outc.rgb *= half(1.0 - s);
        }
    } else if (d <= radius) {
        float theta = asin(clamp(d / radius, 0.0, 1.0));
        float2 srcBack = base + dir * ((PI - theta) * radius);
        float2 srcFront = base + dir * (theta * radius);
        if (inSheet(srcBack) > 0.5 && p.x >= landLeft) {
            outc = backFace(srcBack, 0.84 - 0.26 * sin(theta));
        } else if (inSheet(srcFront) > 0.5) {
            half4 c = content.eval(srcFront);
            outc = half4(c.rgb * half(0.58 + 0.42 * cos(theta)), c.a);
        } else if (inSheet(p) > 0.5) {
            outc = half4(0.0, 0.0, 0.0, 0.30);
        } else {
            outc = content.eval(p);
        }
    } else {
        float s = 0.30 * (1.0 - smoothstep(radius, radius * 2.8 + 6.0, d));
        if (inSheet(p) > 0.5) {
            outc = half4(0.0, 0.0, 0.0, s);
        } else {
            outc = content.eval(p);
            outc.rgb *= half(1.0 - s);
        }
    }
    return outc * half(opacity);
}
"""
