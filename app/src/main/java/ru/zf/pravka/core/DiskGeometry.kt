package ru.zf.pravka.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Геометрия диска плавающих кнопок: шестерёнка в центре, кнопки по кольцу
 * вокруг неё, под ними стекло-тарелка. Владелец (19.09.2026): «крутящийся
 * диск, который наполовину виден из края экрана; четыре кнопки и посередине
 * кнопка настроек; если нажимаю на неё долго, за неё двигаю диск; у края он
 * виден только наполовину, и я могу его крутить».
 *
 * Углы — в градусах, по часовой, 0° — вправо: экранная ось y смотрит вниз,
 * так что «по часовой» здесь совпадает с ростом угла. Единицы — пиксели.
 * Без Android-зависимостей: JVM-тесты гоняют ту же арифметику, что и
 * `trigger/DiskController.kt`.
 *
 * Дом диска — поворот 0: первые две кнопки («П» и «З», ими пользуются чаще
 * всего) стоят по бокам от «лица» — направления внутрь экрана, «П» сверху,
 * «З» снизу. Докованный к правому краю диск смотрит влево: «П» вверху-слева,
 * «З» внизу-слева, «Д» и инструменты за краем. Так половина, которая видна,
 * всегда та, что нужна.
 */
object DiskGeometry {

    /**
     * Радиус кольца, на котором стоят центры кнопок: половина шестерёнки,
     * полтора просвета, половина кнопки. Полтора, а не один: от шестерёнки до
     * кнопок пальцу нужен зазор — тап по краю кнопки не должен задевать её.
     */
    fun ringRadius(buttonSize: Int, gearSize: Int, gap: Int): Float =
        gearSize / 2f + gap * 1.5f + buttonSize / 2f

    /** Радиус тарелки: до внешнего края кнопок плюс три четверти просвета полей. */
    fun plateRadius(buttonSize: Int, gearSize: Int, gap: Int): Float =
        ringRadius(buttonSize, gearSize, gap) + buttonSize / 2f + gap * 0.75f

    /**
     * На сколько центр докованного диска отступает от края экрана: ни на
     * сколько — центр ровно на краю, шестерёнка видна наполовину. Владелец
     * (19.09.2026, вечер): «сам круг должен заходить больше за край: до
     * середины кружка настроек». Первая версия отступала на полшестерёнки и
     * полпросвета, чтобы шестерёнка была целиком, — оказалось, диск торчит.
     * Точка «всё убрано» при этом остаётся на экране целиком (её прижимает
     * сама голова): по ней жмут, чтобы вернуть всё.
     */
    const val DOCK_INSET = 0

    /**
     * Шаг между соседними кнопками и щелчок поворота: четыре — 90°, три —
     * 120°. Две — тоже 90°, а не 180°: пара рядом, как у четырёх, иначе
     * докованный диск показывал бы обе кнопки разрезанными краем.
     */
    fun spread(count: Int): Float = if (count <= 2) 90f else 360f / count

    /**
     * Куда смотрит диск — внутрь экрана: в левой половине вправо (0°), в
     * правой влево (180°). Только по горизонтали: докуется диск к боковым
     * краям, под большой палец.
     */
    fun facing(cx: Float, frameW: Int): Float = if (cx < frameW / 2f) 0f else 180f

    /**
     * В какую сторону идёт раскладка по кольцу: от «П» к «З» — ВНИЗ по
     * внутренней стороне (владелец: «правка должна быть сверху полукруга, а
     * засечка внизу, я так привык»). У правого края (лицо влево) вниз по
     * внутренней стороне — это против часовой (углы убывают), у левого — по
     * часовой. Экранная ось y смотрит вниз, отсюда и знак: первая версия
     * шла по часовой с обеих сторон, и у правого края «П» оказалась внизу.
     * Раскладки двух краёв зеркальны — при переезде на другую половину экрана
     * диск перестраивается (см. `DiskController.slide`).
     */
    fun sense(facing: Float): Float = if (facing < 90f || facing > 270f) 1f else -1f

    /**
     * Угол кнопки в слоте [index] из [count] при повороте [rotation] и лице
     * [facing]: дома первая стоит на полшага ВВЕРХ от лица, дальше вниз по
     * внутренней стороне через шаг ([sense]). Одна кнопка — прямо на лице.
     */
    fun slotAngle(index: Int, count: Int, facing: Float, rotation: Float): Float {
        if (count <= 1) return norm(facing + rotation)
        val step = spread(count)
        val s = sense(facing)
        return norm(facing - s * step / 2f + s * index * step + rotation)
    }

    /** Левый верхний угол окна кнопки размером [size] на кольце радиуса [radius] под углом [angle]. */
    fun slotOrigin(cx: Float, cy: Float, radius: Float, angle: Float, size: Int): Pair<Int, Int> {
        val a = Math.toRadians(angle.toDouble())
        val x = cx + radius * cos(a).toFloat() - size / 2f
        val y = cy + radius * sin(a).toFloat() - size / 2f
        return x.roundToInt() to y.roundToInt()
    }

    /** Угол точки ([x], [y]) относительно центра, в [0, 360). */
    fun angleOf(cx: Float, cy: Float, x: Float, y: Float): Float =
        norm(Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat())

    /** Кратчайший поворот от [from] к [to], в (−180, 180]. */
    fun delta(from: Float, to: Float): Float {
        var d = (to - from) % 360f
        if (d <= -180f) d += 360f
        if (d > 180f) d -= 360f
        return d
    }

    /** Угол в [0, 360). */
    fun norm(angle: Float): Float {
        var a = angle % 360f
        if (a < 0f) a += 360f
        return a
    }

    /**
     * Ближайший щелчок поворота с учётом броска: палец отпустил диск со
     * скоростью [velocity] градусов в секунду — диск считает, куда долетит за
     * [FLICK_LEAD] секунды, и щёлкает там. Лёгкий бросок — соседний щелчок,
     * сильный — через один; ещё сильнее не бывает ([MAX_FLICK]): диск не
     * рулетка.
     */
    fun snap(rotation: Float, step: Float, velocity: Float = 0f): Float {
        val projected = rotation + velocity.coerceIn(-MAX_FLICK, MAX_FLICK) * FLICK_LEAD
        return (projected / step).roundToInt() * step
    }

    /** Ближайший «дом» — поворот, кратный 360°: возвращаться коротким путём, а не через весь круг. */
    fun home(rotation: Float): Float = (rotation / 360f).roundToInt() * 360f

    /**
     * Докование после броска. Тарелка радиуса [plateRadius] зашла за левый
     * или правый край рабочей области [frameW]×[frameH] — центр прижимается
     * к этому краю на [inset]; иначе остаётся где отпустили. По вертикали
     * тарелка всегда целиком на экране (или по центру, если экран ниже
     * тарелки).
     */
    fun dock(cx: Float, cy: Float, frameW: Int, frameH: Int, plateRadius: Float, inset: Int): Pair<Float, Float> {
        val x = when {
            cx - plateRadius < 0f -> inset.toFloat()
            cx + plateRadius > frameW -> (frameW - inset).toFloat()
            else -> cx
        }
        val minY = minOf(plateRadius, frameH / 2f)
        val maxY = maxOf(frameH - plateRadius, frameH / 2f)
        return x to cy.coerceIn(minY, maxY)
    }

    /**
     * Виден ли хоть кусок окна ([x], [y], [size]) в рабочей области с запасом
     * [margin]: окно целиком за краем снимается из WindowManager — складывание
     * Fold платит за каждое окно, видимое или нет.
     */
    fun onScreen(x: Int, y: Int, size: Int, frameW: Int, frameH: Int, margin: Int): Boolean =
        x + size > margin && x < frameW - margin && y + size > margin && y < frameH - margin

    /** Сколько секунд полёта учитывает бросок. */
    const val FLICK_LEAD = 0.15f
    /** Потолок скорости броска, градусов в секунду: с [FLICK_LEAD] это полкруга, два щелчка за мах — предел. */
    const val MAX_FLICK = 1200f
}
