package ru.zf.pravka.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import ru.zf.pravka.data.Settings
import java.util.Random

/**
 * Как выглядят плашки приложения. Владелец (20.09.2026): «плашки в самом
 * приложении стали другие, но мне понравилось. Давай их только сделаем
 * потемнее и с такими же эффектами, как и диск. И это должно быть в настройках
 * отдельных».
 *
 * Те же три слоя, что у стекла: свет сверху, фаска по кромке, зерно. Числа у
 * них свои — плашка большая и читается вблизи, поэтому и свет, и зерно тут
 * слабее, чем на диске: на ладони незаметное становится заметным.
 *
 * Ходит это через `CompositionLocal`, а не параметрами: `PaperCard` зовут из
 * десятка мест по всем вкладкам, и протаскивать четыре настройки через каждый
 * вызов значило бы править их все ради одного тумблера.
 */
data class CardLook(
    /** Насколько плашка темнее заводского тона темы. */
    val darken: Float = Settings.CARD_DARK_DEFAULT,
    val bevel: Boolean = true,
    val light: Boolean = true,
    val grain: Boolean = true,
    /** Сила свечения режима во вкладке, 0 — выключено (`core/ModeGlow.kt`). */
    val glow: Float = ru.zf.pravka.core.ModeGlow.DEFAULT,
) {
    companion object {
        /**
         * Свет — ровно такой же, как у плашек на стекле (`BubbleSkin`,
         * прямоугольная ветка): блик полосой по верхней трети, к низу в ноль.
         * Владелец (20.09.2026): «внутри приложения сделай такой же стиль
         * плашек». Раньше здесь был градиент во всю высоту — он и читался
         * иначе: не объёмом, а заливкой.
         */
        const val SHEEN = 0.07f
        const val SHEEN_SPAN = 0.34f
        /**
         * Низ — заметно светлее верха, и по той же причине, что у плашек на
         * стекле: ровная тёмная полоса во всю ширину под текстом читается не
         * тенью, а налётом («нижняя часть выглядит грязноватой»).
         */
        const val FOOT = 0.03f
        const val FOOT_SPAN = 0.2f
        /** Фаска: светлая сверху, тёмная снизу — вдвое мягче светлой. */
        const val RIM_LIGHT = 0.1f
        const val RIM_SHADE = 0.05f
        /** Зерно: у диска оно на просвет, тут — по плотному тону, и хватает меньшего. */
        const val GRAIN = 0.03f

        /**
         * Плашка — матовое стекло над свечением режима (версия 3): сквозь
         * неё просвечивает свет вкладки, поэтому у верхних плашек тон сам
         * берёт цвет режима, а нижние, куда свет не достаёт, остаются
         * прежними. Цвет не подмешивается нарочно — его приносит свет, и
         * ровно там, где он есть. Ниже — текст на плашке начинает плыть.
         */
        const val GLASS = 0.84f

        /**
         * Главная кнопка и выбранный чип — клавиши, как кнопки на стекле
         * (версия 3, владелец: «их сделать как кнопки с таким же вот
         * отблеском?»). Что нажимается — блестит клавишей; что читается —
         * матовая плашка: на большой плашке с текстом такой же блик читался
         * бы пластиком. Числа — прямоугольной ветки `trigger/BubbleSkin.kt`:
         * блик по верхней трети, низ вдвое мягче кружка, фаска светлая
         * сверху и тёмная снизу.
         */
        const val KEY_SHEEN = 0.26f
        const val KEY_SHEEN_SPAN = 0.34f
        const val KEY_FOOT = 0.1f
        const val KEY_FOOT_SPAN = 0.2f
        const val KEY_RIM_LIGHT = 0.34f
        const val KEY_RIM_SHADE = 0.12f
        /** Нажатая клавиша: блик гаснет до этой доли, как у кнопок на стекле. */
        const val KEY_PRESSED_LIGHT = 0.3f
    }
}

val LocalCardLook: ProvidableCompositionLocal<CardLook> = compositionLocalOf { CardLook() }

/** Настройки вида плашек — в композицию, один раз на всё приложение. */
@Composable
fun ProvideCardLook(settings: Settings, content: @Composable () -> Unit) {
    val darken by settings.cardDarkFlow.collectAsState(initial = Settings.CARD_DARK_DEFAULT)
    val bevel by settings.cardBevelFlow.collectAsState(initial = true)
    val light by settings.cardLightFlow.collectAsState(initial = true)
    val grain by settings.cardGrainFlow.collectAsState(initial = true)
    val glow by settings.appGlowFlow.collectAsState(initial = ru.zf.pravka.core.ModeGlow.DEFAULT)
    CompositionLocalProvider(
        LocalCardLook provides CardLook(darken, bevel, light, grain, glow),
        content = content,
    )
}

/** Тон плашки, затемнённый на долю [darken]: чёрное подмешивается поверх. */
fun darkened(base: Color, darken: Float): Color =
    Color.Black.copy(alpha = darken.coerceIn(0f, 1f)).compositeOver(base)

/**
 * Зерно по плашке — то же, что иней на стекле: плитка шума 64×64, повтором.
 * Рисуется ПОД содержимым (`drawBehind`): зерно поверх текста читалось бы как
 * грязное стекло, а не как материал плашки.
 */
fun Modifier.grain(alpha: Float): Modifier = composed {
    val brush = remember {
        ShaderBrush(ImageShader(grainBitmap(), TileMode.Repeated, TileMode.Repeated))
    }
    drawBehind { drawRect(brush = brush, alpha = alpha) }
}

/** Seed постоянный: зерно не должно «кипеть» при каждой перерисовке. */
private fun grainBitmap(): ImageBitmap {
    val size = 64
    val random = Random(20_260_920L)
    val pixels = IntArray(size * size)
    for (i in pixels.indices) {
        val v = random.nextInt(256)
        pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * Лицо клавиши: заливка [color], блик по верхней трети, мягкая тень снизу и
 * фаска — те же три слоя, что у кнопок на стекле (`trigger/BubbleSkin.kt`).
 * [lit] = false — клавиша под пальцем: свет с неё соскользнул, тень осталась.
 */
fun Modifier.keyFace(color: Color, shape: Shape, lit: Boolean = true): Modifier {
    val sheen = CardLook.KEY_SHEEN * if (lit) 1f else CardLook.KEY_PRESSED_LIGHT
    return this
        .clip(shape)
        .background(color)
        .background(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = sheen),
                CardLook.KEY_SHEEN_SPAN * 0.55f to Color.White.copy(alpha = sheen * 0.3f),
                CardLook.KEY_SHEEN_SPAN to Color.Transparent,
            )
        )
        .background(
            Brush.verticalGradient(
                1f - CardLook.KEY_FOOT_SPAN to Color.Transparent,
                1f to Color.Black.copy(alpha = CardLook.KEY_FOOT),
            )
        )
        .border(
            1.dp,
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = CardLook.KEY_RIM_LIGHT * if (lit) 1f else CardLook.KEY_PRESSED_LIGHT),
                0.5f to Color.Transparent,
                1f to Color.Black.copy(alpha = CardLook.KEY_RIM_SHADE),
            ),
            shape,
        )
}
