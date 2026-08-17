package ru.zf.pravka.target

// Abstraction over "where to take the text from and where to put it back".
interface TextTarget {
    suspend fun read(): String?
    suspend fun write(text: String): Boolean   // false = could not write

    /**
     * What the undo stack should remember for the fix [input] -> [output].
     * Targets that fix a fragment of a larger field override this to return
     * the field-level before/after instead.
     */
    fun undoPair(input: String, output: String): Pair<String, String> = input to output

    /**
     * True when the last read() returned a fragment the user explicitly
     * selected - deliberate enough to skip the minimum-length guard.
     */
    fun isExplicitFragment(): Boolean = false

    /**
     * Up to ~300 chars standing in the field BEFORE the fragment read() 
     * returned - lets the model pick the right capitalization and joint
     * punctuation for a mid-field insert. Empty when whole-field.
     */
    fun contextBefore(): String = ""
}
