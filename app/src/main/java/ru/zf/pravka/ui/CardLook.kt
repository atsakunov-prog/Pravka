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
    CompositionLocalProvider(
        LocalCardLook provides CardLook(darken, bevel, light, grain),
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
