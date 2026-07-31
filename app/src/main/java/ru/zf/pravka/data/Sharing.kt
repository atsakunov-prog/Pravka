package ru.zf.pravka.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import ru.zf.pravka.BuildConfig

// One FileProvider authority and one share-intent builder for every export in
// the app (proofread history, transcription log, metrics CSV, dictation log).
// These were five near-identical copies, plus a raw authority literal in the UI.
private val AUTHORITY: String get() = "${BuildConfig.APPLICATION_ID}.files"

internal fun shareFileIntent(context: Context, file: File, mime: String): Intent {
    val uri = FileProvider.getUriForFile(context, AUTHORITY, file)
    return Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
