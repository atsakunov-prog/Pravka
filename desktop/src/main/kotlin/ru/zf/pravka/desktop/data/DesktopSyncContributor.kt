package ru.zf.pravka.desktop.data

import org.json.JSONObject
import ru.zf.pravka.data.PravkaSync
import ru.zf.pravka.data.PromptSyncSupport

// Что воркстанция отдаёт в общую таблицу сверх словаря и правил.
class DesktopSyncContributor(
    private val settings: DesktopSettings,
    private val journal: TranscriptionJournal,
    private val stats: DesktopStats,
    private val promptSync: PromptSyncSupport,
) : PravkaSync.Contributor {

    override suspend fun transcripts(since: Long): List<JSONObject> {
        val withText = settings.syncTranscriptTextFlow.value
        return journal.lastFlow.value.map { e ->
            JSONObject().apply {
                put("ts", e.at)
                put("device", DEVICE)
                put("engine", e.engine)
                put("audio_s", e.audioMs / 1000.0)
                put("transcribe_s", e.transcribeMs / 1000.0)
                put("chars", e.chars)
                if (withText) put("text", e.text)
            }
        }
    }

    override suspend fun statsRow(): JSONObject {
        val s = stats.snapshotFlow.value
        return JSONObject().apply {
            put("device", DEVICE)
            put("updatedAt", System.currentTimeMillis())
            put("total", s.total)
            put("errors", s.errors)
            put("chars", s.charsProcessed)
            put("tokensIn", s.tokensIn)
            put("tokensOut", s.tokensOut)
            put("costTotalUsd", s.costTotalUsd)
            put("costTodayUsd", s.costTodayUsd)
        }
    }

    override suspend fun prompts(): List<JSONObject> = promptSync.export()

    override suspend fun applyPrompts(incoming: List<JSONObject>) = promptSync.apply(incoming)

    private companion object {
        const val DEVICE = "workstation"
    }
}
