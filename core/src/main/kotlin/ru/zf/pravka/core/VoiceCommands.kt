package ru.zf.pravka.core

// Deterministic, on-device handling of dictated formatting commands - applied
// to the recognized text BEFORE it is inserted (and before CLEAN), so a
// paragraph break the owner asked for out loud is instant and free, not
// something the model must infer.
object VoiceCommands {

    // Границы слова - через lookaround по \p{L}\p{N}, а НЕ через \b.
    //
    // На Android ICU \b знает про кириллицу, а на обычной JVM (воркстанция)
    // \w - это ASCII, поэтому \b перед "с" границей не считается и команда
    // не срабатывала совсем. Тот же приём, что в DictionaryApplier, работает
    // одинаково на обеих машинах.
    //
    // Флаг (?U) на Android бросает PatternSyntaxException в инициализаторе
    // класса и убивает вставку навсегда; (?iu) - безопасен и там, и там.
    // Запятые и точки, которые распознаватель поставил вокруг команды,
    // съедаются вместе с ней.
    private val newParagraph = Regex(
        "(?iu)\\s*[,.]?\\s*(?<![\\p{L}\\p{N}])(с новой строки|новая строка|новый абзац|абзац)" +
            "(?![\\p{L}\\p{N}])[,.]?\\s*",
    )

    fun apply(text: String): String {
        var out = newParagraph.replace(text, "\n")
        // A command at the very edge leaves a dangling break.
        out = out.trim('\n', ' ')
        return out
    }
}
