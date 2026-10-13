package ru.zf.pravka.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.zf.pravka.core.Ration
import ru.zf.pravka.core.Svod

// Рацион владельца: его постоянные продукты с КБЖУ на 100 г и блюда — наборы
// этих продуктов («моя каша», «индейка с кускусом»). Модель и формат —
// `core/Ration.kt`.
//
// Зачем, если модель и так считает: «творог» в её голове — средний творог из
// интернета, а у владельца это ВкусВилл 5% на 117 ккал/100 г, и «моя каша» —
// ровно шесть вещей с ровно этими граммами. Половина его еды повторяется
// каждый день, и по этой половине угадывать нечего.
//
// Где правда (13.10.2026, владелец: «положить на сервер, и я бы говорил
// "индейка, сладкий соус чили" — и он понимал бы»): запись Свода
// `food.ration` на сервере Дел. Телефон читает её из кэша Свода и работает
// без сети; `assets/ration.json` — заводское семя: им телефон один раз кладёт
// рацион в Свод (`svodSeedTexts`) и по нему работает, пока Свода нет.
class RationBook(
    private val context: Context,
    private val log: (String) -> Unit = {},
) {

    companion object {
        const val ASSET = "ration.json"
    }

    @Volatile private var factory: Ration.Book? = null
    // Разобранная запись Свода и сама запись — новая версия с сервера
    // разбирается один раз, а не на каждом тапе.
    @Volatile private var memo: Pair<Svod.Entry, Ration.Book?>? = null

    /** Откуда сейчас рацион — строка во вкладке: «молчаливая механика читается как поломка». */
    enum class Source { SVOD, FACTORY, NONE }

    /** Рацион сейчас: запись Свода, а нет её или не читается — файл сборки. */
    val book: Ration.Book get() = svodBook() ?: factory ?: Ration.Book()

    val source: Source
        get() = when {
            svodBook() != null -> Source.SVOD
            factory != null -> Source.FACTORY
            else -> Source.NONE
        }

    /** Версия записи Свода (0 — рацион из сборки). */
    val svodRev: Int get() = if (svodBook() != null) Svod.current[Svod.FOOD_RATION]?.rev ?: 0 else 0

    val loaded: Boolean get() = !book.empty

    /** Заводское семя в память; Свод уже в памяти — его читать не надо. */
    suspend fun load(): Ration.Book {
        if (factory == null) {
            factory = withContext(Dispatchers.IO) {
                runCatching { Ration.parse(assetText()) }.getOrNull()?.takeIf { !it.empty }
            }
        }
        return book
    }

    /** Текст файла сборки — его и кладёт в Свод первое знакомство. */
    fun assetText(): String = context.assets.open(ASSET).bufferedReader().use { it.readText() }

    private fun svodBook(): Ration.Book? {
        val e = Svod.current[Svod.FOOD_RATION] ?: return null
        memo?.takeIf { it.first === e }?.let { return it.second }
        val parsed = e.value?.let { Ration.parse(Svod.parseJson(it)) }?.takeIf { !it.empty }
        if (parsed == null) {
            log("рацион: запись Свода ${Svod.FOOD_RATION} (версия ${e.rev}) не читается — работаю по заводскому")
        } else if (parsed.problems.isNotEmpty()) {
            log("рацион: в Своде ${parsed.problems.take(3).joinToString("; ")}")
        }
        memo = e to parsed
        return parsed
    }

    /** Блок промпта разбора еды: продукты и блюда с правилами. */
    fun promptBlock(): String = Ration.promptBlock(book)

    /** Голосовые имена продуктов и блюд для смещения распознавателя. */
    fun biasing(): List<String> = book.biasing()
}
