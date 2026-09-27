package ru.zf.slushalka.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan
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
 * вверх. Изнанка - настоящая: снимок страницы, напечатанной на обороте этого
 * листа ([CurlState.back]), а пока снимка нет - бумага с просветом лица.
 *
 * Лист пришит к корешку, и сгиб к нему привязан: линия сгиба не заходит за
 * корешок в пределах высоты страницы - иначе валик залезал на соседнюю
 * страницу, и «гнулось всё и после корешка», как увидел владелец. Наклон
 * сгиба при этом уменьшается, пока хватает места.
 *
 * Нужен `RuntimeShader`, то есть Android 13. Ниже и на электронной бумаге -
 * растворение, как и было.
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
    /**
     * Изнанка листа: снимок страницы, напечатанной на его обороте, размером в
     * лист. Берётся в начале оборота со слоя просвета той же страницы
     * (`PageFace`), поэтому набрана она в точности так, как её потом увидят.
     */
    var back: ImageBitmap? = null
        private set
    var backShader by mutableStateOf<BitmapShader?>(null)
        private set

    fun setBack(bitmap: ImageBitmap?) {
        back = bitmap
        backShader = bitmap?.let { BitmapShader(it.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    }

    /** Оборот без пальца: за наружный край листа на этой высоте. */
    fun grabEdge(slot: Int, sheet: Rect, y: Float, forward: Boolean) {
        this.slot = slot
        this.forward = forward
        grab = Offset(sheet.right - 2f, y.coerceIn(sheet.top, sheet.bottom))
        dy = 0f
        // Верхнюю половину тянут вниз, нижнюю вверх: угол загибается
        // наискось, как когда берут страницу за уголок. Чуть-чуть: сильный
        // наклон всё равно упрётся в корешок.
        tilt = if (y < sheet.center.y) 0.12f else -0.12f
        travel = travelFor(sheet)
    }

    /** Лист лёг: дальше слои живут по месту в пейджере, а не по жесту. */
    fun done() {
        slot = -1
        setBack(null)
    }

    /** Путь, за который лист ложится: до корешка и на столько же за него. */
    fun travelFor(sheet: Rect): Float = (2f * (grab.x - sheet.left)).coerceAtLeast(0.45f * sheet.width)
}

/** Шейдер загиба и пустая изнанка на случай, когда снимка ещё нет. */
class CurlShader(val runtime: RuntimeShader, val blank: BitmapShader)

@Composable
fun rememberCurlShader(): CurlShader? = remember {
    if (curlSupported) makeCurlShader() else null
}

/**
 * Шейдер собирается прошивкой при создании; если она его не приняла (у
 * вендора свой Skia, синтаксис разошёлся), загиба не будет - будет
 * растворение, а не падение читалки.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun makeCurlShader(): CurlShader? = runCatching {
    val blank = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    CurlShader(RuntimeShader(CURL_AGSL), BitmapShader(blank, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
}.getOrNull()

/**
 * Загиб на слое слота. [offset] - положение слота в пейджере: 0 - лист лежит,
 * 1 - перевёрнут, между - гнётся. [sheet] - лист, который гнётся: правая
 * страница разворота или единственная; [landLeft] - левее этого изнанка не
 * рисуется (корешок закрытой книги, за который лист уходит), null - рисуется
 * везде (в развороте изнанка ложится на левую страницу). [translate] - ставить
 * ли слот на место самому; на половине разворота его возит [halfPan].
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun Modifier.pageCurl(
    shader: CurlShader,
    state: CurlState,
    slot: Int,
    paper: Color,
    sheet: Density.(Size) -> Rect,
    landLeft: Density.(Size) -> Float?,
    offset: () -> Float,
    translate: Boolean = true,
): Modifier = graphicsLayer {
    val off = offset()
    when {
        // Перевёрнут: за экраном, чтобы не ловить нажатия поверх читаемой
        // страницы. Не прозрачность: слой должен и дальше рисоваться, с него
        // берётся снимок изнанки для оборота назад.
        off >= 1f -> {
            translationX = -10f * size.width
            renderEffect = null
        }
        // Ещё не тронут: лежит на своём месте под верхним.
        off <= 0f -> {
            if (translate) translationX = off * size.width
            renderEffect = null
        }
        else -> {
            if (translate) translationX = off * size.width
            val s = sheet(this, size)
            val dp = 1.dp.toPx()
            // Радиус валика растёт с ходом и к концу опадает: в начале лист
            // только-только приподнят, к концу ложится плоско. Без этого в
            // самом конце у корешка оставался бы стоячий валик. Сам валик
            // невелик: бумага гнётся туго, толстый валик читался трубой.
            val radiusMax = (s.width * 0.12f).coerceIn(14f * dp, 48f * dp)
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
            val pi = Math.PI.toFloat()
            val mid = Offset((grab.x + dest.x) / 2f, (grab.y + dest.y) / 2f)
            // Линия сгиба: точка захвата, обёрнутая через валик, должна
            // оказаться под пальцем - отсюда середина между ними минус
            // половина полуокружности валика.
            fun foldFor(ddx: Float, ddy: Float) = Offset(mid.x - ddx * pi * radius / 2f, mid.y - ddy * pi * radius / 2f)
            var fold = foldFor(dx, dyv)
            // Сгиб пришит к корешку: в пределах высоты страницы линия сгиба не
            // заходит левее него. Наклон убавляется, пока места хватает, а
            // если сгиб уже за корешком и вертикальный - лист просто лёг.
            repeat(2) {
                if (dyv == 0f) return@repeat
                val yFar = if (dyv > 0f) s.bottom else s.top
                val xFar = fold.x - (dyv / dx) * (yFar - fold.y)
                if (xFar >= s.left) return@repeat
                val room = fold.x - s.left
                if (room <= 0f) {
                    dx = 1f
                    dyv = 0f
                } else {
                    val angle = atan(room / abs(yFar - fold.y).coerceAtLeast(1f))
                    dx = cos(angle)
                    dyv = (if (dyv > 0f) 1f else -1f) * sin(angle)
                }
                fold = foldFor(dx, dyv)
            }
            // На самом излёте лист растворяется: валик уже опал, и остаток -
            // тени по краям - уступает странице под ним.
            val opacity = 1f - smoothstep(0.9f, 1f, off)
            val back = if (mine) state.backShader else null
            val rt = shader.runtime
            rt.setFloatUniform("sheet", s.left, s.top, s.right, s.bottom)
            rt.setFloatUniform("landLeft", landLeft(this, size) ?: -1e9f)
            rt.setFloatUniform("fold", fold.x, fold.y)
            rt.setFloatUniform("dir", dx, dyv)
            rt.setFloatUniform("radius", radius)
            rt.setFloatUniform("paper", paper.red, paper.green, paper.blue)
            rt.setFloatUniform("ghost", 0.16f)
            rt.setFloatUniform("backward", if (mine && !state.forward) 1f else 0f)
            rt.setFloatUniform("hasBack", if (back != null) 1f else 0f)
            rt.setInputBuffer("back", back ?: shader.blank)
            rt.setFloatUniform("opacity", opacity)
            // Эффект собирается заново на каждый кадр: значения uniform
            // снимаются при сборке, поменять их на живом эффекте нельзя.
            renderEffect = RenderEffect.createRuntimeShaderEffect(rt, "content").asComposeRenderEffect()
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
 * На половине разворота ([pan]) не всякий ход - оборот листа: с левой
 * страницы на правую книга просто едет камерой, лист переворачивается
 * только с правой на следующую левую (и назад - с левой на предыдущую
 * правую), это решает [leafTurn]. Там палец ведёт ход пейджера как есть: книга
 * едет под пальцем, и точку захвата за ним не удержать.
 *
 * [sheet] - лист в координатах этого узла; [onCurlStart] зовётся, когда
 * решено, чей лист гнётся (с его слотом), - там берётся снимок изнанки;
 * [settle] - куда доводить, когда палец отпущен.
 */
fun Modifier.curlDrag(
    state: CurlState,
    pager: PagerState,
    scope: CoroutineScope,
    enabled: Boolean,
    pan: Boolean,
    leafTurn: (from: Int, forward: Boolean) -> Boolean,
    sheet: Density.(Size) -> Rect,
    onCurlStart: (slot: Int) -> Unit,
    settle: suspend (target: Int) -> Unit,
): Modifier = if (!enabled) this else pointerInput(state, pager, pan) {
    val tracker = VelocityTracker()
    var moved = Offset.Zero
    var decided = false
    var baseDy = 0f
    var from = 0
    var forward = true
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
                forward = state.forward
                from = if (forward) state.slot else state.slot + 1
            } else {
                state.done()
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
                val start = change.position - moved
                forward = moved.x < 0f
                from = pager.currentPage
                if (forward && from >= pager.pageCount - 1) return@detectDragGestures
                if (!forward && from <= 0) return@detectDragGestures
                decided = true
                if (leafTurn(from, forward)) {
                    state.forward = forward
                    if (forward) {
                        state.slot = from
                        // Взялись левее листа (за левую страницу, за поле) -
                        // значит, за его ближний край.
                        state.grab = Offset(
                            start.x.coerceIn(s.left + 0.35f * s.width, s.right - 2f),
                            start.y.coerceIn(s.top, s.bottom),
                        )
                    } else {
                        // Назад: лист лежит перевёрнутым слева, и под пальцем -
                        // его точка, отражённая через корешок.
                        state.slot = from - 1
                        state.grab = Offset(
                            (2f * s.left - start.x).coerceIn(s.left + 0.05f * s.width, s.right - 2f),
                            start.y.coerceIn(s.top, s.bottom),
                        )
                    }
                    state.dy = 0f
                    state.tilt = 0f
                    state.travel = state.travelFor(s)
                    onCurlStart(state.slot)
                } else {
                    state.done()
                }
            }
            if (state.slot >= 0) state.dy = baseDy + moved.y
            // Пейджер меряет ход своей шириной, лист - своим путём; книга,
            // едущая камерой, - как пейджер.
            val scale = if (pan || state.slot < 0) 1f else size.width / state.travel
            pager.dispatchRawDelta(-amount.x * scale)
        },
        onDragEnd = { if (decided) finish(state, pager, scope, tracker, flick, from, forward, settle) },
        onDragCancel = { if (decided) finish(state, pager, scope, tracker, flick, from, forward, settle) },
    )
}

/** Палец отпущен: лист либо ложится обратно, либо доворачивается. */
private fun finish(
    state: CurlState,
    pager: PagerState,
    scope: CoroutineScope,
    tracker: VelocityTracker,
    flick: Float,
    from: Int,
    forward: Boolean,
    settle: suspend (Int) -> Unit,
) {
    val velocity = tracker.calculateVelocity().x
    val pos = pager.currentPage + pager.currentPageOffsetFraction
    val to = (if (forward) from + 1 else from - 1).coerceIn(0, pager.pageCount - 1)
    // Сколько хода пройдено: 0 - откуда начали, 1 - куда идём.
    val progress = abs(pos - from).coerceIn(0f, 1f)
    // Начатый оборот доводится раньше половины: страницу, которую уже
    // потянули, редко хотят вернуть, а вот отпустить на трети хода - сплошь.
    val onward = if (forward) velocity < -flick else velocity > flick
    val backOff = if (forward) velocity > flick else velocity < -flick
    val target = when {
        onward -> to
        backOff -> from
        progress > 0.35f -> to
        else -> from
    }
    state.settling = scope.launch {
        settle(target)
        state.done()
    }
}

/**
 * Шейдер загиба. Координаты - пиксели слоя; `content` - сам слой страницы,
 * `back` - снимок изнанки размером в лист (`hasBack` - есть ли он).
 *
 * `d` - расстояние точки экрана от линии сгиба вдоль хода: отрицательное -
 * по эту сторону сгиба (нетронутый лист, левая страница, и над ними, может
 * быть, плоско лежащий завёрнутый край), от нуля до радиуса - валик (сверху
 * изнанка, под ней лицо), дальше - лист ушёл: под ним видна следующая
 * страница и тень валика на ней. Точка листа на валике ищется по дуге:
 * `theta = asin(d / radius)`, лицо на `theta`, изнанка на `pi - theta` от сгиба.
 *
 * Изнанка - снимок оборота, отражённый по ширине листа: оборот напечатан с
 * той же стороны корешка, и его точка под точкой лица - зеркальная. Пока
 * снимка нет - бумага с еле заметным просветом лица.
 *
 * Что показывать в лежащей плоско части листа, зависит от места. Над своей
 * страницей (правее корешка) это изнанка; при обороте назад та же часть -
 * уже лёгшая страница, и там просто содержимое слоя. Над левой страницей
 * (левее корешка) лист прозрачен: под ним в этом же месте лежит ровно та
 * страница, которая на нём напечатана с изнанки, - следующий разворот уже
 * подложен снизу, - и подставлять вместо неё снимок значило бы двоить текст.
 * Левее `landLeft` (за корешком закрытой книги) листа нет.
 *
 * Свет сверху: лицо у сгиба светлое и темнеет к силуэту валика, изнанка от
 * силуэта светлеет к макушке с бликом чуть ниже неё и лежит плоско светлой,
 * с тенью в складке у самого сгиба. Все переходы сшиты по яркости, чтобы на
 * границах зон не было швов. Тени не выходят за высоту листа: над обрезом
 * и под ним тень листа на переплёте не читалась бы тенью.
 */
private const val CURL_AGSL = """
uniform shader content;
uniform shader back;
uniform float4 sheet;
uniform float landLeft;
uniform float2 fold;
uniform float2 dir;
uniform float radius;
uniform float3 paper;
uniform float ghost;
uniform float backward;
uniform float hasBack;
uniform float opacity;

const float PI = 3.141592653589793;

float inSheet(float2 p) {
    return (p.x >= sheet.x && p.x <= sheet.z && p.y >= sheet.y && p.y <= sheet.w) ? 1.0 : 0.0;
}

float inRows(float2 p) {
    return (p.y >= sheet.y && p.y <= sheet.w) ? 1.0 : 0.0;
}

float outside(float2 p) {
    return max(max(sheet.x - p.x, p.x - sheet.z), max(sheet.y - p.y, p.y - sheet.w));
}

half4 backFace(float2 src, float shade) {
    half3 rgb = half3(paper);
    if (hasBack > 0.5) {
        half4 t = back.eval(float2(sheet.z - src.x, src.y - sheet.y));
        rgb = rgb * (1.0 - t.a) + t.rgb;
    } else {
        half4 c = content.eval(src);
        rgb = mix(rgb, c.rgb, half(ghost));
    }
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
                outc = half4(0.0, 0.0, 0.0, crease * inRows(p));
            } else if (backward > 0.5) {
                outc = content.eval(p);
                outc.rgb *= half(1.0 - crease);
            } else {
                outc = backFace(src, 1.0 - crease);
            }
        } else {
            outc = content.eval(p);
            float gap = outside(src);
            float s = (p.x >= landLeft) ? 0.20 * (1.0 - smoothstep(0.0, 0.8 * radius + 4.0, gap)) * inRows(p) : 0.0;
            outc.rgb *= half(1.0 - s);
        }
    } else if (d <= radius) {
        float theta = asin(clamp(d / radius, 0.0, 1.0));
        float2 srcBack = base + dir * ((PI - theta) * radius);
        float2 srcFront = base + dir * (theta * radius);
        if (inSheet(srcBack) > 0.5 && p.x >= landLeft) {
            float k = d / radius;
            float gloss = 0.10 * exp(-pow((k - 0.55) / 0.2, 2.0));
            outc = backFace(srcBack, 0.84 - 0.30 * sin(theta) + gloss);
        } else if (inSheet(srcFront) > 0.5) {
            half4 c = content.eval(srcFront);
            outc = half4(c.rgb * half(0.54 + 0.46 * cos(theta)), c.a);
        } else if (inSheet(p) > 0.5) {
            outc = half4(0.0, 0.0, 0.0, 0.34);
        } else {
            outc = content.eval(p);
        }
    } else {
        float s = 0.34 * (1.0 - smoothstep(radius, radius * 2.4 + 6.0, d)) * inRows(p);
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
