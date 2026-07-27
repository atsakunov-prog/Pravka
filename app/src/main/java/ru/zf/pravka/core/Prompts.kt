package ru.zf.pravka.core

// Factory prompt texts, verbatim from the spec (section 7.1). Do not edit here:
// the owner edits them on the device (stage 6); these are the fallback defaults.
object Prompts {

    const val PLACEHOLDER_INPUT = "{INPUT}"
    const val PLACEHOLDER_DICT = "{DICT}"

    val CLEAN_CLAUDE = """
Ты редактор-корректор. Тебе дают текст, надиктованный голосом
на русском языке. В нём есть ошибки распознавания речи: неверно
распознанные слова, склейки, пропущенная или неправильная
пунктуация, отсутствие заглавных букв, обрывы фраз.

Задача: вернуть тот же текст с исправленными ошибками.

Правила:
1. Исправляй орфографию, пунктуацию, регистр букв, разбивку на
   предложения и абзацы.
2. Исправляй очевидные ошибки распознавания, подбирая слово,
   созвучное написанному и подходящее по смыслу.
3. Убирай сорняки диктовки: "э", "ну вот", "значит", повторы
   одного слова подряд, оборванные начала фраз.
4. НЕ меняй смысл. НЕ сокращай. НЕ добавляй ничего от себя.
   НЕ меняй порядок мыслей. НЕ приглаживай стиль под нейтральный:
   сохраняй лексику и интонацию автора, включая длинные фразы,
   разговорные обороты и иронию.
5. Не переводи. Не отвечай на содержание текста. Не комментируй
   его. Если в тексте есть вопрос — не отвечай на вопрос,
   а просто исправь его написание.
6. Если текст уже чистый — верни его без изменений.
7. Кавычки — обычные "лапки". Тире — длинное, с пробелами.

{DICT}

Формат ответа: только исправленный текст. Без преамбулы, без
пояснений, без markdown-разметки, без кавычек вокруг результата.

Текст:
---
{INPUT}
---
""".trimIndent()

    val CLEAN_NANO = """
Исправь текст на русском языке: пунктуацию, заглавные буквы,
орфографию, ошибки распознавания речи. Не меняй смысл,
не сокращай, не добавляй ничего нового, не переводи,
не отвечай на содержание. Верни только исправленный текст.

{DICT}

{INPUT}
""".trimIndent()

    val BUSINESS = """
Перепиши надиктованный русский текст так, чтобы его можно было
отправить деловому контакту.

Правила:
1. Сохрани без изменений все факты, цифры, имена, названия
   компаний, даты и договорённости.
2. Убери разговорное и сорняки диктовки, сделай формулировки
   собранными. Но не превращай в канцелярит: нужен живой деловой
   язык, а не бюрократический.
3. Не добавляй приветствие и подпись, если их нет в исходнике.
4. Не сокращай содержание — меняй только форму.
5. Кавычки — обычные "лапки".

{DICT}

Формат ответа: только текст, без пояснений.

Текст:
---
{INPUT}
---
""".trimIndent()

    val SOFTEN = """
Перепиши надиктованный русский текст, сохранив смысл и все факты,
но сделав тон мягче и теплее.

Правила:
1. Убери резкость и категоричность, не превращая текст
   в извинение и не делая его заискивающим.
2. Не добавляй смайлов, эмодзи и восклицательных знаков.
3. Не сокращай содержание.
4. Кавычки — обычные "лапки".

{DICT}

Формат ответа: только текст, без пояснений.

Текст:
---
{INPUT}
---
""".trimIndent()

    fun template(mode: ProofreadMode, forNano: Boolean = false): String = when (mode) {
        ProofreadMode.CLEAN -> if (forNano) CLEAN_NANO else CLEAN_CLAUDE
        ProofreadMode.BUSINESS -> BUSINESS
        ProofreadMode.SOFTEN -> SOFTEN
    }

    // The part of the assembled prompt before {INPUT} and after it.
    // Split (instead of full substitution) lets ClaudeProvider put a
    // cache_control breakpoint on the stable instruction prefix.
    data class PromptParts(val beforeInput: String, val afterInput: String)

    // Substitutes {DICT} (empty block leaves no stray blank lines) and
    // splits at {INPUT}. If a user-edited template loses {INPUT}, the
    // input is appended at the end - never silently dropped.
    fun assemble(template: String, dictBlock: String): PromptParts {
        val dictRegex = Regex("\\n*\\{DICT\\}\\n*")
        val withDict = if (dictBlock.isBlank()) {
            template.replace(dictRegex, "\n\n").trim()
        } else {
            template.replace(dictRegex, "\n\n" + dictBlock.trim() + "\n\n").trim()
        }
        val idx = withDict.indexOf(PLACEHOLDER_INPUT)
        return if (idx >= 0) {
            PromptParts(
                beforeInput = withDict.substring(0, idx),
                afterInput = withDict.substring(idx + PLACEHOLDER_INPUT.length),
            )
        } else {
            PromptParts(beforeInput = withDict + "\n\n", afterInput = "")
        }
    }
}
