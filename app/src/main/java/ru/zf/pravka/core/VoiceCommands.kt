package ru.zf.pravka.core

// Deterministic, on-device handling of dictated formatting commands - applied
// to the recognized text BEFORE it is inserted (and before CLEAN), so a
// paragraph break the owner asked for out loud is instant and free, not
// something the model must infer.
object VoiceCommands {

    // (?i) only: Android's ICU regex rejects the JVM-only (?U) flag with a
    // PatternSyntaxException in the class initializer - which killed the whole
    // insert path on every dictation (ExceptionInInitializerError, then
    // NoClassDefFoundError forever after). ICU's \b and character classes are
    // Unicode-aware by default, so (?U) is unnecessary on Android anyway.
    // Surrounding commas/periods the recognizer may have added are swallowed
    // into the break.
    private val newParagraph = Regex(
        "(?i)\\s*[,.]?\\s*\\b(с новой строки|новая строка|новый абзац|абзац)\\b[,.]?\\s*",
    )

    fun apply(text: String): String {
        var out = newParagraph.replace(text, "\n")
        // A command at the very edge leaves a dangling break.
        out = out.trim('\n', ' ')
        return out
    }
}
