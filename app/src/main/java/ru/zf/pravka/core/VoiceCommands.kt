package ru.zf.pravka.core

// Deterministic, on-device handling of dictated formatting commands - applied
// to the recognized text BEFORE it is inserted (and before CLEAN), so a
// paragraph break the owner asked for out loud is instant and free, not
// something the model must infer.
object VoiceCommands {

    // (?iU): case-insensitive + Unicode word boundaries (Cyrillic).
    // Surrounding commas/periods the recognizer may have added are swallowed
    // into the break.
    private val newParagraph = Regex(
        "(?iU)\\s*[,.]?\\s*\\b(с новой строки|новая строка|новый абзац|абзац)\\b[,.]?\\s*",
    )

    fun apply(text: String): String {
        var out = newParagraph.replace(text, "\n")
        // A command at the very edge leaves a dangling break.
        out = out.trim('\n', ' ')
        return out
    }
}
