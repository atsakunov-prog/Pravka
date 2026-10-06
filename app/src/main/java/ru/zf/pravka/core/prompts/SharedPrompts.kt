package ru.zf.pravka.core.prompts

/**
 * Промпты, общие с сервером Дел (`server/contract/prompts`, 06.10.2026).
 *
 * Владелец: «на сервере есть промпт, который неплохо разбирает, и в Правке
 * тоже есть — надо их синхронизировать». До этого правила разбора наговора
 * на дела жили двумя копиями (`TASKS_DELA` здесь и `SYSTEM` в
 * `server/pravka_dela/parse.py`) с припиской «правишь одно — правь и
 * другое», и копии уже начали расходиться. Теперь текст один: файл лежит в
 * контракте, сервер читает его с диска, а телефон получает его в APK
 * Java-ресурсом (`app/build.gradle.kts`, `resources.srcDir`). Поправил файл —
 * поправил разбор и в вебе, и на телефоне со следующей сборкой.
 *
 * Своё у каждой стороны — только хвост: телефон дописывает словарь Правки и
 * сам наговор, сервер — «говорит не Саша» и схему ответа.
 */
internal object SharedPrompts {
    const val RAZNOSKA = "raznoska.txt"
    const val RAZNOSKA_SCHEMA = "raznoska.schema.json"

    /** Правила разбора наговора на дела. Плейсхолдеры: {CATALOG}, {PLACES}, {TODAY}, {NOW}. */
    val raznoska: String by lazy { read(RAZNOSKA) }

    /**
     * Текст файла из ресурсов. Нет файла — сборка без `server/contract/prompts`:
     * это поломка сборки, а не повод разбирать пустым промптом, поэтому громко.
     */
    fun read(name: String): String {
        val stream = SharedPrompts::class.java.getResourceAsStream("/$name")
            ?: error("Нет общего промпта $name — APK собран без server/contract/prompts")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
            .replace("\r\n", "\n")
            .removePrefix("﻿")
            .trim()
    }
}
