package ru.zf.pravka.ui

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ru.zf.pravka.R

// Шрифты Правки 4.0 (07.10.2026, DESIGN §5): Literata SemiBold — заголовки и
// крупные цифры, Golos Text — всё остальное. Оба под OFL, лежат в `res/font`
// переменными TTF — без загрузки из сети и без библиотек. До 4.0 засечки были
// системные (Noto Serif у Pixel), а без засечек — Roboto: тот же телефон у
// Марианны рисовал другое.
//
// Literata переменная и по оптическому размеру (opsz 7…72): мелкий заголовок
// чуть шире и контрастнее, крупный балл «+84» — тоньше в волосяных. Поэтому
// у каждого размера своё семейство с `opsz` под себя, а не одно на всё.

@OptIn(ExperimentalTextApi::class)
object PravkaFonts {

    private fun golos(weight: Int) = Font(
        R.font.golos_text,
        FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
    )

    /** Golos Text 400 / 500 / 600 / 700. Курсива нет — наклон синтетический (допустимо, §5). */
    val Golos: FontFamily = FontFamily(golos(400), golos(500), golos(600), golos(700))

    private val literata = HashMap<Int, FontFamily>()

    /** Literata 600 с оптическим размером под кегль [opsz]. */
    fun literata(opsz: Int): FontFamily = synchronized(literata) {
        literata.getOrPut(opsz) {
            FontFamily(
                Font(
                    R.font.literata,
                    FontWeight.SemiBold,
                    variationSettings = FontVariation.Settings(
                        FontVariation.weight(600),
                        FontVariation.Setting("opsz", opsz.coerceIn(7, 72).toFloat()),
                    ),
                ),
            )
        }
    }
}

private const val TNUM = "tnum"

// Перенос по слогам (баг №10, 07.10.2026: «давай везде переносить по
// строкам»): текст больше не режется многоточием, и узкая плитка без слогов
// рвала бы слово посреди («тренирова|нность»). Русский словарь переносов —
// системный (Android 10+), язык — языка телефона.
private val HYPHENS = androidx.compose.ui.text.style.Hyphens.Auto
private val BREAK = androidx.compose.ui.text.style.LineBreak.Paragraph

private fun serif(size: Float, line: Float? = null) = TextStyle(
    fontFamily = PravkaFonts.literata(size.toInt()),
    fontWeight = FontWeight.SemiBold,
    fontSize = size.sp,
    lineHeight = line?.sp ?: TextUnit.Unspecified,
    fontFeatureSettings = TNUM,
    hyphens = HYPHENS,
    lineBreak = BREAK,
)

private fun sans(size: Float, weight: Int, line: Float? = null) = TextStyle(
    fontFamily = PravkaFonts.Golos,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line?.sp ?: TextUnit.Unspecified,
    fontFeatureSettings = TNUM,
    hyphens = HYPHENS,
    lineBreak = BREAK,
)

/**
 * Стили Правки 4.0 — таблица DESIGN §5 один в один. Слоты M3 заполнены
 * ближайшими (`pravkaM3Typography`), а то, чего в M3 нет, — здесь, через
 * [LocalPravkaType]. Во всех стилях — моноширинные цифры (`tnum`).
 */
@Immutable
class PravkaType(
    /** 52/54 — балл в центре круга (на развороте 44). */
    val displayXL: TextStyle = serif(52f, 54f),
    val displayXLWide: TextStyle = serif(44f, 46f),
    /** 38/42 — «11 ч 46 м» в плашке времени (на развороте 34). */
    val displayL: TextStyle = serif(38f, 42f),
    val displayLWide: TextStyle = serif(34f, 38f),
    /** 30/34 — день недели. */
    val titleL: TextStyle = serif(30f, 34f),
    /** 24/30 — название режима в шапке. */
    val titleM: TextStyle = serif(24f, 30f),
    /** 22 — заголовок колонки на развороте, заголовок листа. */
    val titleS: TextStyle = serif(22f, 28f),
    /** Цифра на плашке режима. */
    val valueL: TextStyle = sans(19f, 700, 22f),
    /** Крупная цифра внутри плашки (в Деньгах «−111 441 ₽» — засечками). */
    val valueXL: TextStyle = serif(34f, 38f),
    /** Строка «сказать». */
    val input: TextStyle = sans(16.5f, 400, 22f),
    val body: TextStyle = sans(14.5f, 400, 18f),
    val bodyStrong: TextStyle = sans(14.5f, 600, 18f),
    /** Названия в плашках режима (строка дела 15/19). */
    val bodyL: TextStyle = sans(15f, 400, 19f),
    val points: TextStyle = sans(14f, 600, 18f),
    val label: TextStyle = sans(12.5f, 500, 16f),
    val meta: TextStyle = sans(12f, 400, 17f),
    val caption: TextStyle = sans(11.5f, 400, 14f),
    /** «ДЕЛА НА СЕГОДНЯ · 5 · 2 Ч 25 М» — прописными с разрядкой. */
    val overline: TextStyle = sans(11.5f, 600, 14f).copy(letterSpacing = 0.06.em),
    val time: TextStyle = sans(13f, 400, 16f),
    val valueS: TextStyle = sans(15f, 600, 18f),
    val weather: TextStyle = sans(14f, 600, 17f),
    val nowBadge: TextStyle = sans(12f, 700, 16f),
    val mini: TextStyle = sans(12.5f, 700, 16f),
    val dialLabel: TextStyle = sans(11f, 400, 13f),
    /** Цифра плитки (StatTile) 17/21. */
    val tile: TextStyle = sans(17f, 600, 21f),
)

val LocalPravkaType = staticCompositionLocalOf { PravkaType() }

/** Слоты Material 3 — ближайшими стилями §5: старые экраны говорят на языке M3. */
internal fun pravkaM3Typography(t: PravkaType): Typography {
    val base = Typography()
    return base.copy(
        displayLarge = t.displayXL,
        displayMedium = t.displayL,
        displaySmall = t.titleL,
        headlineLarge = t.titleL,
        headlineMedium = t.titleL,
        headlineSmall = t.titleM,
        titleLarge = t.titleS,
        titleMedium = sans(16f, 600, 21f),
        titleSmall = sans(14f, 600, 18f),
        bodyLarge = sans(16f, 400, 22f),
        bodyMedium = t.body,
        bodySmall = t.meta,
        labelLarge = sans(14f, 600, 18f),
        labelMedium = t.label,
        labelSmall = t.caption,
    )
}
