package ru.zf.slushalka.catalog

/**
 * Поиск по каталогу поумнее, чем строка «как набрали».
 *
 * У Флибусты два поиска, и оба буквальные: авторы ищутся по подстроке
 * **фамилии**, книги - по подстроке названия. Отсюда все обиды: «Эстер Перель»
 * не находит ничего (в фамилии «Перель» нет слова «Эстер»), «Конан Дойль» не
 * находит Дойла (в каталоге он «Дойл»), а «Хэмингуэй» промахивается мимо
 * «Хемингуэя».
 *
 * Поэтому запрос сначала раскладывается на слова, для каждого слова
 * подбираются варианты написания, и по авторам уходит **несколько** запросов
 * разом - они у Флибусты быстрые. По названиям книг уходит один запрос целой
 * фразой: этот поиск у сайта медленный, и множить его нельзя. Что пришло по
 * авторам, склеивается, чистится от повторов и ранжируется: сначала те, у кого
 * в имени есть все слова запроса, потом остальные.
 */
object SmartSearch {

    data class Plan(
        /** Исходный запрос, как набрали. */
        val query: String,
        /** Слова запроса после чистки. */
        val words: List<String>,
        /** Что спрашивать у поиска авторов - в порядке убывания надежды. */
        val authorTerms: List<String>,
        /** Что спрашивать у поиска книг по названию. */
        val bookTerm: String,
        /** Запасной запрос по названию, если первый вернул пустоту. */
        val bookFallback: String? = null,
    )

    /** Сколько запросов по авторам позволяем себе на один поиск. */
    private const val MAX_AUTHOR_TERMS = 6

    fun plan(raw: String): Plan {
        val query = raw.trim().replace(Regex("\\s+"), " ")
        val words = query.split(' ')
            .map { it.trim { c -> !c.isLetterOrDigit() } }
            .filter { it.length >= 2 }
        // Ключ - точное написание без регистра, а не нормализованная форма:
        // сервер различает «Дойль» и «Дойл», и оба должны уйти в запрос.
        val terms = LinkedHashMap<String, String>()
        fun offer(t: String) {
            val k = t.lowercase()
            if (k.length >= 2 && k !in terms && terms.size < MAX_AUTHOR_TERMS) terms[k] = t
        }
        // Сначала слова целиком, длинные первыми: фамилия обычно длиннее имени.
        words.filter { it.length >= 3 }.sortedByDescending { it.length }.forEach(::offer)
        // Потом их варианты написания - для каждого слова по очереди.
        words.filter { it.length >= 3 }.sortedByDescending { it.length }.forEach { w ->
            variants(w).forEach(::offer)
        }
        if (terms.isEmpty()) offer(query)
        val longest = words.filter { it.length >= 4 }.maxByOrNull { it.length }
        return Plan(
            query = query,
            words = words,
            authorTerms = terms.values.toList(),
            bookTerm = query,
            bookFallback = longest?.takeIf { words.size >= 2 && !it.equals(query, true) },
        )
    }

    /**
     * План по совету: автор известен отдельно от названия. Фамилия (в русских
     * каталогах она первая) и её варианты - к авторам, название - к книгам.
     */
    fun planFor(author: String, title: String): Plan {
        val surname = author.trim().split(Regex("\\s+")).firstOrNull { it.length >= 2 }.orEmpty()
        val terms = LinkedHashSet<String>()
        if (surname.length >= 2) {
            terms.add(surname)
            variants(surname).forEach(terms::add)
        }
        val words = (author.split(' ') + title.split(' ')).map { it.trim { c -> !c.isLetterOrDigit() } }
            .filter { it.length >= 2 }
        return Plan(
            query = listOf(author, title).filter { it.isNotBlank() }.joinToString(" "),
            words = if (surname.isNotBlank()) listOf(surname) else words,
            authorTerms = terms.toList().take(MAX_AUTHOR_TERMS),
            bookTerm = title.trim(),
            bookFallback = title.split(' ').map { it.trim { c -> !c.isLetterOrDigit() } }
                .filter { it.length >= 4 }.maxByOrNull { it.length }
                ?.takeIf { title.trim().contains(' ') },
        )
    }

    /**
     * Другие написания того же слова - то, чем русская передача иностранных
     * имён расходится от издания к изданию: Дойль/Дойл, Хэмингуэй/Хемингуэй,
     * Толкиен/Толкин, Кэрролл/Кэрол.
     */
    fun variants(word: String): List<String> {
        val out = LinkedHashSet<String>()
        val w = word
        fun add(v: String) { if (v.length >= 3 && !v.equals(w, true)) out.add(v) }
        // э/ё -> е: сначала только первая (Хэмингуэй → Хемингуэй, как и пишут
        // чаще всего), потом все разом; и обратно на первой букве: Эстер/Естер.
        add(w.replace('ё', 'е').replace('Ё', 'Е'))
        add(w.replaceFirst("э", "е").replaceFirst("Э", "Е"))
        add(w.replace('э', 'е').replace('Э', 'Е'))
        if (w.first() == 'Е' || w.first() == 'е') add((if (w.first() == 'Е') "Э" else "э") + w.drop(1))
        // Мягкий знак на конце: Дойль/Дойл, Марсель/Марсел, Рафаэль/Рафаэл.
        if (w.endsWith("ль")) add(w.dropLast(1))
        else if (w.endsWith("л")) add(w + "ь")
        if (w.endsWith("нь")) add(w.dropLast(1))
        // й/и: Дойл/Доил, Толкиен/Толкин.
        add(w.replace("ие", "и").replace("ий", "и"))
        add(w.replace('й', 'и'))
        // Двойные согласные: Кэрролл/Кэрол, Бредбери/Брэдбери уже покрыты э/е.
        add(w.replace(Regex("([бвгджзклмнпрстфхцчшщ])\\1"), "$1"))
        // Первое слово запроса в самой короткой форме - без вариантов длиннее слова.
        return out.filter { it != w }.take(3)
    }

    /**
     * Одинаково записанные слова считаются равными: ё=е, э=е, й=и, мягкий
     * знак не в счёт. Так «Дойль» встречается в «Дойл Артур Конан».
     */
    fun normalize(s: String): String = s.lowercase()
        .replace('ё', 'е').replace('э', 'е').replace('й', 'и')
        .replace("ь", "").replace("ъ", "")
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    /** 0 - лучшее совпадение. Автор, в имени которого есть все слова запроса, - первый. */
    fun rank(entry: OpdsEntry, plan: Plan): Int {
        val name = normalize(entry.title)
        val words = plan.words.map(::normalize).filter { it.isNotBlank() }
        if (words.isEmpty()) return 3
        if (words.all { name.contains(it) }) return 0
        val longest = words.maxByOrNull { it.length }
        if (longest != null && name.contains(longest)) return 1
        if (words.any { name.contains(it) }) return 2
        return 3
    }

    /** Один автор может прийти по нескольким вариантам - узнаём его по ссылке на его ленту. */
    fun keyOf(entry: OpdsEntry): String = entry.feedLink?.href ?: entry.title
}
