package ru.zf.pravka.provider

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.ParsedTask
import ru.zf.pravka.core.ProofreadMode
import ru.zf.pravka.core.ProofreadProvider
import ru.zf.pravka.core.ProofreadResult
import ru.zf.pravka.core.Prompts
import ru.zf.pravka.data.ModelRoute
import ru.zf.pravka.data.PromptStore

import ru.zf.pravka.provider.ClaudeProvider.ApiException
import ru.zf.pravka.provider.ClaudeProvider.ApiReply
import ru.zf.pravka.provider.ClaudeProvider.ImagePart
import ru.zf.pravka.provider.ClaudeProvider.LearnProposals
import ru.zf.pravka.provider.ClaudeProvider.DictProposal
import ru.zf.pravka.provider.ClaudeProvider.RuleProposal
import ru.zf.pravka.provider.ClaudeProvider.OptimizedRules
import ru.zf.pravka.provider.ClaudeProvider.ZasechkaParse
import ru.zf.pravka.provider.ClaudeProvider.SplitResult
import ru.zf.pravka.provider.ClaudeProvider.FoodParse
import ru.zf.pravka.provider.ClaudeProvider.BodyParse
import ru.zf.pravka.provider.ClaudeProvider.SetParse
import ru.zf.pravka.provider.ClaudeProvider.ExerciseParse
import ru.zf.pravka.provider.ClaudeProvider.StrengthParse
import ru.zf.pravka.provider.ClaudeProvider.GtgParse
import ru.zf.pravka.provider.ClaudeProvider.FeelParse
import ru.zf.pravka.provider.ClaudeProvider.RulesParse
import ru.zf.pravka.provider.ClaudeProvider.CoachAnswer
import ru.zf.pravka.provider.ClaudeProvider.BatchAnswer

// Засечка: одна надиктованная фраза -> структурная запись ленты (заводская модель — Опус,
// меняется в настройках → «Модели»; правила под часовым кэшем),
// плюс самообучение: поправки владельца -> правила промпта батчем.
//
// Расширения ClaudeProvider: транспорт (запрос, стрим, кэш, деньги) живёт в нём,
// разбор режима — здесь, чтобы правка промпта Засечки не заставляла читать чужие.

/**
 * Опус превращает «созвон с Ивановым по отчёту, последние полчаса» в
 * {action, title, category, ...}. Был Сонет — владелец: «очень плохо
 * получается у Сонета понимать, как работать с засечкой»: терялись
 * ретро-вставки, категории спорили с прецедентом, названия вырождались
 * в имя категории. Свод правил лежит в stablePrefix под часовым кэшем —
 * Опус ходит сюда десятки раз в день, и кэш окупается с двух вызовов.
 * Падение разбора не теряет сказанное: вызывающий пишет сырым.
 */
suspend fun ClaudeProvider.zasechka(
    raw: String,
    // name -> hint ("что сюда относится"); the hint rides only in the
    // prompt, the reply must return the bare name.
    categories: List<Pair<String, String>>,
    clients: List<String>,
    nowLocal: String,
    previousTitle: String,
    // Numbered lines of today's entries - the edit/delete intents point
    // at one of them by its number.
    todayEntries: List<String>,
    // The owner's own wording from the previous few days: the same дело
    // must come back under the same name and category, otherwise the week
    // never adds up.
    recentEntries: List<String> = emptyList(),
    // Одобренные владельцем правила Засечки: как он говорит о своём
    // времени. Едут в переменный хвост, а не под кэш: список живой.
    ownerRules: String = "",
): Result<ZasechkaParse> = withContext(Dispatchers.IO) {
    runCatchingApi {
        val apiKey = settings.apiKey()
        if (apiKey.isBlank()) throw ApiException("Не задан API-ключ. Открой Правку и вставь ключ в настройках.")
        val categoriesBlock = categories.joinToString("\n") { (name, hint) ->
            "- «$name»" + (if (hint.isBlank()) "" else " — $hint")
        }
        val clientsBlock =
            if (clients.isEmpty()) "(список пуст)"
            else clients.joinToString("\n") { "- $it" }
        val previousBlock =
            if (previousTitle.isBlank()) "" else "Предыдущее дело владельца: «$previousTitle».\n"
        val todayBlock =
            if (todayEntries.isEmpty()) "(записей сегодня ещё нет)"
            else todayEntries.joinToString("\n")
        val recentBlock =
            if (recentEntries.isEmpty()) ""
            else "\nКак владелец называл свои дела в предыдущие дни (его собственные\n" +
                "формулировки, часть он правил руками — держись их):\n" +
                recentEntries.joinToString("\n") + "\n"
        val stableRules = """
Ты — секретарь личного тайм-трекера. Владелец наговорил фразу голосом.
Лента непрерывна: сумма минут дня всегда 24 часа, дыры между записями
заполняются сами. Твоя работа — понять НАМЕРЕНИЕ и вернуть строгий JSON.

СНАЧАЛА ПОЧИСТИ ФРАЗУ. Это живая речь в диктофон, и в ней бывает лишнее:
- САМОПОПРАВКА. «в 8:30 отвёз… так нет, неправильно, в 7:29 отвёз» —
  верно ПОСЛЕДНЕЕ сказанное. Слова «нет», «не так», «неправильно»,
  «то есть», «вернее», «отставить» отменяют всё, что было до них по
  этому же полю. Отменённое просто не существует.
- МУСОР В НАЧАЛЕ. Распознаватель приносит обрывки чужих мыслей и
  реплики не про ленту: «А почему перестала работать», «так», «ну».
  Отбрасывай их молча и работай с делом.
- ОБОРВАННОЕ СЛОВО в конце — достраивай по смыслу, не выдумывая нового.

НАМЕРЕНИЯ ("action"):
1. Чем занят СЕЙЧАС → "new": «пишу отчёт», «сел есть».
2. Чем занят сейчас, но НАЧАЛ РАНЬШЕ → "new" плюс время начала:
   «полчаса назад начал готовить», «с 13:00 программирую», «с 12:00
   время с семьёй», «уже минут двадцать занят детьми».
3. ВСПОМНИЛ про кусок В ПРОШЛОМ, который УЖЕ КОНЧИЛСЯ → "insert":
   «с 16:24 по 16:52 говорил с Марианной», «с 8:00 до 9:00 печатал
   документы», «между делом позвонил маме минут на десять».
   Обрамляющее дело НЕ трогай и не упоминай: приложение само разрежет
   его вокруг вставки и ПРОДОЛЖИТ после неё — текущая запись не гибнет.

   Про new и insert НЕ МУЧАЙСЯ. Разницу приложение выведет само из
   времён: назван конец в прошлом — будет вставка, не назван — будет
   текущее дело. Твоя работа — не угадать намерение, а ТОЧНО ВЕРНУТЬ
   ВСЕ НАЗВАННЫЕ ВРЕМЕНА. Ошибка в "action" стоит дёшево, потерянная
   граница — дорого: из-за неё дело растягивается на полдня.
4. ПОПРАВИТЬ существующую запись → "edit": «в 16:45 был не быт, а
   передвижение», «это была медкомиссия, а не быт», «туалет был до
   18:50», «переименуй…», «еда была с 16:43 до 17:40». Найди запись в
   списке «Сегодня» по времени или названию — верни её номер в "entry".
   Меняй ТОЛЬКО названные поля, остальные пустые ("" или 0). Новые
   границы — "start_time"/"end_time" в «ЧЧ:ММ»; пустая строка = не менять.
5. УДАЛИТЬ запись → "delete": «удали…», «убери запись…», «отмени
   последнюю» (= последний номер списка «Сегодня»). Верни "entry".
6. ЗАКОНЧИЛ, нового дела пока нет → "stop": «всё, закончил», «на этом
   всё», «закончил десять минут назад» (тогда "start_offset_min" = 10)
   или «закончил в 18:50» (тогда "end_time"). Открытое дело закроется,
   дальше лента разметит сама.
7. НЕ ПРО ЛЕНТУ → "none": о будущем («через пять минут пойду бегать» —
   запишется, когда начнёт и скажет), случайный мусор распознавания,
   вопрос без намерения. В "say" — одной строкой почему не записал.
   ТОЛЬКО ФОН — тоже "none": «параллельно слушаю Акунина», «фоном
   подкаст», «ой, пока был в туалете, смотрел ютуб». Второго слоя у ленты
   нет: время занимает то, чем заняты руки и тело, а фон в ленту не идёт
   (телефон считается отдельно по дням). В "say" — «фон не записываю».

Ещё случаи, которые владелец говорит:
- «вернулся к программированию», «продолжаю уборку» → "new" с ТЕМ ЖЕ
  title и категорией, что у этого дела в списках.
- «раздели запись: первые двадцать минут это была готовка» → "insert"
  куска в начало той записи (границы возьми из списка «Сегодня»);
  остальное обрамление приложение дорежет само.
- «туалет занял пять минут, а не двадцать» → "edit" записи «туалет»:
  "end_time" = её старт из списка «Сегодня» плюс пять минут — посчитай
  сам и верни «ЧЧ:ММ».

ДВА ДЕЛА В ОДНОЙ ФРАЗЕ. Если второе шло ОДНОВРЕМЕННО с первым — маркеры
«и параллельно», «в это время», «в то же время», «одновременно», «заодно»,
«фоном», «при этом», «под это», а также «и слушал…», «и разговаривал по
телефону» — записывай ТОЛЬКО ГЛАВНОЕ: то, чем заняты руки и тело. Фон
(что слушал, смотрел, с кем говорил по телефону, не отрываясь) в запись
не идёт и своей записью не становится — он остаётся в надиктовке.
  «готовил еду детям и параллельно смотрел ютуб» →
title «Приготовление еды детям» [Еда]
  «готовил еду и разговаривал с мамой по телефону» →
title «Приготовление еды» [Еда]
  «еду за рулём и слушаю книгу Акунина» →
title «Поездка на машине» [Передвижение: транспорт]
Разговор с человеком, который здесь, рядом, — не фон: «готовил еду и
разговаривал с Марианной» это одно дело «Приготовление еды с Марианной».

Сомневаешься между new и edit — new: данные важнее.
Одна фраза — одно намерение: разбирай главное.

ПРАВИЛА ПОЛЕЙ:
- "title": КОНКРЕТНОЕ название дела, 3–8 слов, именной группой, с большой
  буквы, без глаголов: «Медкомиссия с Серёжей», «Поездка на дачу за
  детьми», «Звонок юристу по ипотеке». ЛУЧШЕ ДЛИННЕЕ, ЧЕМ КОРОЧЕ:
  причеши сказанное, но НЕ сжимай до одного-двух слов («Метка»,
  «Миссия», «Уборка» — плохо) — сохраняй детали: с кем, что именно, где
  («Уборка детской с Марианной»). НОРМАЛИЗУЙ ГРАММАТИКУ, НЕ СМЫСЛ:
  сказал «пошёл в кафе с Марианной» — пиши «Кафе с Марианной», а не
  обезличенное «Время с Марианной»; «готовлю завтрак» — «Приготовление
  завтрака», а не «Приготовление еды». Личная деталь (блюдо, место,
  человек) дороже единообразия: к прошлому имени приводи только когда
  сказанное — ровно то же дело без новых деталей. Через неделю владелец
  должен по title вспомнить этот час, не открывая заметку. НИКОГДА не называй дело
  именем категории: title «Быт», «Отдых», «Потери», «Семья» ЗАПРЕЩЁН —
  категория лежит в своём поле. Однословное название допустимо ТОЛЬКО
  когда это же дело уже ровно так называется в списках («Обед»,
  «Завтрак»): если такое же дело есть в «Сегодня» или «Прошлых днях» —
  назови ТОЧНО так же, буква в букву, одинаковые дела называются
  одинаково, иначе неделя не складывается.
- "category": название категории из списка, БУКВА В БУКВУ (без «кавычек»
  и без пояснения). ПРЕЦЕДЕНТ ПОБЕЖДАЕТ ТВОЮ ЛОГИКУ: если это же дело в
  списках уже лежит в какой-то категории — клади в неё же, не рассуждай
  заново («YouTube» шёл в Потери — значит Потери, даже если «Отдых»
  кажется тебе логичнее; «Лежу в кровати» = Отдых, а «Ничего не делаю» =
  Потери — его решения, не твои). «Не размечено» — техническая категория
  авто-заполнителя дыр: сам её НЕ выбирай никогда. Ничего не подходит —
  пустая строка.
- "client": имя из списка клиентов, если дело явно про него. Назвал не из
  списка — верни как услышано. Иначе пустая строка.
- "useful": целое 1–5, только если владелец сам оценил пользу («полезность
  четыре», «пустая трата времени» = 1, «очень продуктивно» = 5). Иначе 0.
ВРЕМЯ — САМОЕ ВАЖНОЕ ПОЛЕ. Четыре слота, и они не путаются:

- "start_time" — начало, если владелец назвал ЧАСЫ. Формат «ЧЧ:ММ».
  Сюда идёт ЛЮБОЕ названное время начала, в любой формулировке:
  «с 12:00», «с 7:39», «в 18:00», «начиная с половины второго» = 13:30,
  «с девяти двадцати» = 09:20, «с восьми утра» = 08:00,
  «с 8 56» = 08:56, «с полдевятого» = 08:30.
  Без «с» тоже считается: «17:00 до 18:00 была терапия» — начало 17:00.
- "end_time" — конец, если он назван: «по 16:52», «до 9:40», «до
  восемнадцати ноль-ноль» = 18:00, «закончил в 18:50».
- "start_offset_min" — ТОЛЬКО относительный отсчёт назад, когда часов НЕ
  называли: «полчаса назад» = 30, «минут двадцать назад» = 20,
  «последние сорок минут» = 40, «уже минут двадцать занят детьми» = 20,
  «час назад начал» = 60, «часа полтора» = 90. Названы часы — оставь
  ноль, время уже лежит в "start_time".
- "duration_min" — ТОЛЬКО названная длительность куска: «минут на
  десять» = 10, «на полчаса» = 30. Иначе ноль.

Числительные словами переводи в цифры всегда. Верхняя граница разумности
и для отступа, и для длительности — 12 часов.

ЧТО ЗДЕСЬ ЛЕГКО ИСПОРТИТЬ:
- «с 16:24 по 16:52» — это ДВА времени, а не одно. Верни оба. Половина
  интервала хуже, чем ничего: дело растянется до конца дня.
- Приблизительность не отменяет времени: «где-то с восьми» — 08:00.
- Ничего про время не сказано — все четыре поля пустые и нулевые.
  Не выдумывай границ: их отсутствие означает «началось прямо сейчас»,
  и это верный ответ, а придуманное время — испорченный день.

Ответ — СТРОГО JSON без пояснений, по форме намерения:
new:    {"action": "new", "title": "...", "category": "...", "client": "...", "useful": 0, "start_offset_min": 0, "start_time": "", "end_time": ""}
insert: {"action": "insert", "title": "...", "category": "...", "client": "...", "useful": 0, "start_time": "18:30", "end_time": "18:50", "start_offset_min": 0, "duration_min": 0}
edit:   {"action": "edit", "entry": 7, "title": "...", "category": "...", "client": "...", "useful": 0, "start_time": "", "end_time": ""}
delete: {"action": "delete", "entry": 7}
stop:   {"action": "stop", "end_time": "", "start_offset_min": 0}
none:   {"action": "none", "say": "..."}
""".trimIndent() + "\n\n"
        val varTail = """
Сейчас: $nowLocal.
$previousBlock
Записи сегодня (№ · время · категория · название):
$todayBlock
$recentBlock
Категории (после тире — пояснение, что сюда относится):
$categoriesBlock

Клиенты и проекты владельца:
$clientsBlock

Фраза владельца:
<фраза>
$raw
</фраза>
""".trimIndent()
        // Правила стабильны байт-в-байт — под часовым кэшем; всё живое
        // (время, лента, категории, фраза) — в переменном хвосте.
        //
        // Выученные правила владельца живут ЗДЕСЬ, в конце стабильной
        // части, а не в хвосте. Две причины. Читаются они как часть свода
        // правил, а не как ещё одно поле рядом с фразой, — и модель
        // относится к ним соответственно. И меняются они раз в несколько
        // дней, а фраза приходит десятки раз в день: под кэшем они почти
        // всегда бесплатны, в хвосте платились бы каждый раз.
        val stableWithOwner =
            if (ownerRules.isBlank()) stableRules
            else stableRules + ownerRules + "\n\n"
        val parts = Prompts.PromptParts(
            stablePrefix = stableWithOwner,
            cacheStableAlways = true,
            dictPart = varTail,
            afterInput = "",
        )
        val choice = settings.modelChoice(ModelRoute.ZASECHKA)
        val reply = requestWithOneRetry(
            apiKey, choice.model, parts, "", null,
            effortOverride = choice.effort,
        )
        parseZasechka(reply.text).copy(
            costUsd = costUsd(choice.model, reply),
            tokensIn = reply.inputTokens + reply.cacheWriteTokens + reply.cacheReadTokens,
            tokensOut = reply.outputTokens,
        )
    }
}

/**
 * Разбор поправок Засечки в правила — то же самое, что «Обучить» в
 * Правке, только предмет другой: не как владелец пишет, а что у него
 * значат слова про время. «Созвон» — это «Работа: звонки», а не «Звонки».
 * «Разбор почты» он кладёт в «Операционку».
 *
 * Опус, а не Сонет: обобщать поправки в правило — суждение, а не
 * механика. Возвращает предложения; в промпт они попадут только после
 * «да» владельца.
 */
/**
 * Свод правил для разбора поправок — стабильная часть, одна на все заходы.
 */
private fun ClaudeProvider.zasechkaRulesSystem(categories: List<String>, existingRules: List<String>): String {
    val existingBlock =
        if (existingRules.isEmpty()) "(правил пока нет)"
        else existingRules.joinToString("\n") { "- $it" }
    return """
Ты разбираешь, где секретарь тайм-трекера расходится с владельцем, и
превращаешь расхождения в правила.

Категории владельца:
${categories.joinToString("\n") { "- $it" }}

Уже действующие правила (не повторяй их и не противоречь им):
$existingBlock

Твоя работа — найти ЗАКОНОМЕРНОСТИ. Смотри на всё сразу:
- ВРЕМЯ. Сказал «с 18:30 до 18:50», а записалось только начало и дело
  тянется дальше? Назвал длительность («минут двадцать»), а её нет в
  записи? Это самый частый и самый дорогой промах: он растягивает дело на
  полдня, и владелец его почти не правит — привыкает.
- КАТЕГОРИЯ. Одни и те же слова раз за разом уезжают не туда.
- НАЗВАНИЕ. Он говорит одно, а записывается обобщённое или наоборот.
- КЛИЕНТ. Имя названо во фразе, а колонка пуста.

Как писать правило:
- Оно имеет смысл, только если сработает СНОВА: за ним привычка речи или
  устойчивый выбор, а не один случай. Один случай — не закономерность,
  два-три похожих — уже да.
- Повелительно, коротко, одной фразой: «Если названы и начало, и конец —
  ставь обе границы, не только начало», «Созвон и планёрку клади в
  «Работа: звонки», а не в «Звонки»».
- Ничего не выдумывай: только то, что видно в материале.
- Закономерностей не нашлось — верни пустой список. Это нормальный ответ,
  он лучше, чем правило из ничего.

Ответ — СТРОГО JSON: {"rules": ["...", "..."]}
Не больше пяти правил за раз.
""".trimIndent()
}

/** Сам материал: что говорил и что из этого вышло. */
private fun ClaudeProvider.zasechkaRulesUser(spoken: List<String>, corrections: List<String>): String {
    val spokenBlock =
        if (spoken.isEmpty()) "(новых надиктовок нет)" else spoken.joinToString("\n")
    val fixesBlock =
        if (corrections.isEmpty()) "(руками ничего не правил)"
        else corrections.joinToString("\n")
    return """
ЧТО ВЛАДЕЛЕЦ ГОВОРИЛ И ЧТО ИЗ ЭТОГО ПОЛУЧИЛОСЬ.
Это главный материал. Читай пару целиком: во фразе может быть сказано
больше, чем попало в запись, — и вот это и есть промах.
$spokenBlock

ГДЕ ОН ВМЕШАЛСЯ РУКАМИ (сигнал сильнее: тут он сказал прямо):
$fixesBlock
""".trimIndent()
}

private fun ClaudeProvider.parseRulesJson(raw: String): List<String> {
    var text = raw.trim()
    if (text.startsWith("```")) {
        text = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return emptyList()
    val o = runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
        ?: return emptyList()
    val array = o.optJSONArray("rules") ?: return emptyList()
    return (0 until array.length())
        .mapNotNull { array.optString(it).trim().takeIf { r -> r.isNotEmpty() } }
        .take(5)
}

/**
 * Разбор поправок Засечки в правила — синхронно, по кнопке: владелец
 * стоит над экраном и ждёт ответа. Ночью то же самое уходит батчем
 * (вдвое дешевле, см. [zasechkaRulesSubmit]).
 *
 * Опус, а не Сонет: обобщать поправки в правило — суждение, а не
 * механика. Возвращает предложения; в промпт они попадут только после
 * «да» владельца.
 */
suspend fun ClaudeProvider.zasechkaRules(
    spoken: List<String>,
    corrections: List<String>,
    categories: List<String>,
    existingRules: List<String>,
): Result<List<String>> = withContext(Dispatchers.IO) {
    runCatchingApi {
        val apiKey = settings.apiKey()
        if (apiKey.isBlank()) throw ApiException("Не задан API-ключ.")
        if (spoken.isEmpty() && corrections.isEmpty()) return@runCatchingApi emptyList()
        val prompt = zasechkaRulesSystem(categories, existingRules) + "\n\n" +
            zasechkaRulesUser(spoken, corrections)
        val parts = Prompts.PromptParts(stablePrefix = "", dictPart = prompt, afterInput = "")
        val choice = settings.modelChoice(ModelRoute.ZASECHKA_RULES)
        val reply = requestWithOneRetry(
            apiKey, choice.model, parts, "", null,
            effortOverride = choice.effort,
        )
        parseRulesJson(reply.text)
    }
}

/**
 * То же самое, но БАТЧЕМ: ночью никто не ждёт ответа, а батч стоит
 * половину. Возвращает id заявки — ответ забирается позже
 * [zasechkaRulesCollect].
 */
suspend fun ClaudeProvider.zasechkaRulesSubmit(
    spoken: List<String>,
    corrections: List<String>,
    categories: List<String>,
    existingRules: List<String>,
): Result<String> {
    val choice = settings.modelChoice(ModelRoute.ZASECHKA_RULES)
    return submitBatch(
        system = zasechkaRulesSystem(categories, existingRules),
        user = zasechkaRulesUser(spoken, corrections),
        model = choice.model,
        maxTokens = 2000,
        effort = choice.effort,
    )
}

/** Ответ ночного батча; null — ещё считается, спросим на следующем тике. */
suspend fun ClaudeProvider.zasechkaRulesCollect(batchId: String): Result<Pair<List<String>, BatchAnswer>?> =
    // Модель здесь нужна только для цены. Заявка ушла ночью, а к утру
    // владелец мог переставить настройку — тогда цена посчитается по новой.
    batchAnswer(batchId, settings.modelChoice(ModelRoute.ZASECHKA_RULES).model).map { answer ->
        if (answer == null) null else parseRulesJson(answer.text) to answer
    }

private fun ClaudeProvider.parseZasechka(raw: String): ZasechkaParse {
    var text = raw.trim()
    if (text.startsWith("```")) {
        text = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) throw ApiException("Модель вернула не тот формат.")
    val o = runCatching { JSONObject(text.substring(start, end + 1)) }
        .getOrElse { throw ApiException("Модель вернула не тот формат.") }
    return ZasechkaParse(
        // Старый разбор мог ответить "parallel" — второго трека больше нет,
        // такой ответ читается как «фон, не записываю».
        action = o.optString("action", "new").trim().lowercase(java.util.Locale.US)
            .let { if (it == "parallel") "none" else it }
            .takeIf { it in listOf("new", "insert", "edit", "delete", "stop", "none") }
            ?: "new",
        entryIndex = o.optInt("entry", 0),
        title = o.optString("title").trim(),
        // The prompt shows categories as «Название» - strip the quotes if
        // the model echoes them back.
        category = o.optString("category").trim().trim('«', '»').trim(),
        client = o.optString("client").trim(),
        useful = o.optInt("useful", 0).coerceIn(0, 5),
        // 12 hours is the sanity ceiling for "how far back" - anything
        // larger is a parse hallucination, not a real day.
        startOffsetMin = o.optInt("start_offset_min", 0).coerceIn(0, 12 * 60),
        durationMin = o.optInt("duration_min", 0).coerceIn(0, 12 * 60),
        say = o.optString("say").trim().ifBlank { if (o.optString("action") == "parallel") "фон не записываю" else "" },
        startTime = clockField(o, "start_time"),
        endTime = clockField(o, "end_time"),
        costUsd = 0.0,
        tokensIn = 0,
        tokensOut = 0,
    )
}

/** "16:43" or "" - anything that is not a clock time is dropped. */
private fun ClaudeProvider.clockField(o: JSONObject, key: String): String {
    val v = o.optString(key).trim()
    return if (Regex("^\\d{1,2}:\\d{2}$").matches(v)) v else ""
}
