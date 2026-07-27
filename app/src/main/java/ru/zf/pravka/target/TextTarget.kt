package ru.zf.pravka.target

// Abstraction over "where to take the text from and where to put it back".
interface TextTarget {
    suspend fun read(): String?
    suspend fun write(text: String): Boolean   // false = could not write
}
