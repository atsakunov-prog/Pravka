package ru.zf.pravka.target

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ClipboardTarget(private val context: Context) : TextTarget {

    override suspend fun read(): String? = withContext(Dispatchers.Main) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
    }

    override suspend fun write(text: String): Boolean = withContext(Dispatchers.Main) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("Правка", text))
        true
    }
}
