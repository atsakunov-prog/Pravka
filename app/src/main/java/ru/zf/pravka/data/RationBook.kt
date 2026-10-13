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
//
// Правят его двое (владелец: «надо иметь возможность этот рацион
// редактировать»): сам владелец во вкладке «Еда» — [save], целым значением
// записи без `base_rev` (правка владельца — последнее слово), и Claude в
// коннекторе claude.ai (`server/pravka_dela/ration.py`, с `base_rev`).
class RationBook(
    private val context: Context,
    private val log: (String) -> Unit = {},
    /** Свод, куда уходит правка владельца (`save`); ставит приложение. */
    private val svod: () -> SvodStore? = { null },
) {

    companion object {
        const val ASSET = "ration.json"
    }

    @Volatile private var factory: Ration.Book? = null
    // Разобранная запись Свода и её текст — новая версия разбирается один раз, а
    // не на каждом тапе. По тексту, а не по записи: своя неотправленная правка
    // пересобирает записи Свода на каждую перемену, а текст у неё тот же.
    @Volatile private var memo: Pair<String, Ration.Book?>? = null

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
        val text = e.value ?: return null
        memo?.takeIf { it.first == text }?.let { return it.second }
        val parsed = Ration.parse(Svod.parseJson(text))?.takeIf { !it.empty }
        if (parsed == null) {
            log("рацион: запись Свода ${Svod.FOOD_RATION} (версия ${e.rev}) не читается — работаю по заводскому")
        } else if (parsed.problems.isNotEmpty()) {
            log("рацион: в Своде ${parsed.problems.take(3).joinToString("; ")}")
        }
        memo = text to parsed
        return parsed
    }

    /**
     * Правка владельца: рацион целиком — новой версией записи Свода. Видна
     * сразу (своя правка Свода), на сервер уходит очередью Дел; без сети и без
     * сервера Дел — ждёт в очереди и работает на телефоне уже сейчас.
     */
    suspend fun save(book: Ration.Book, reason: String): Boolean {
        val sv = svod() ?: return false
        val today = java.time.LocalDate.now().toString()
        sv.set(Svod.FOOD_RATION, value = Ration.toJson(book, today).toString(), author = "phone", reason = reason)
        log("рацион: $reason")
        return true
    }

    /** Блок промпта разбора еды: продукты и блюда с правилами. */
    fun promptBlock(): String = Ration.promptBlock(book)

    /** Голосовые имена продуктов и блюд для смещения распознавателя. */
    fun biasing(): List<String> = book.biasing()
}
