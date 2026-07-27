package ru.zf.pravka.ui

import android.content.Context
import android.widget.Toast
import java.util.Locale
import ru.zf.pravka.R
import ru.zf.pravka.core.ProofreadEngine

// Shared haptics + toast reporting for all triggers (spec section 8):
// never lose a result silently, every error gets a readable Russian reason.
object Feedback {

    fun report(context: Context, outcome: ProofreadEngine.Outcome) {
        when (outcome) {
            is ProofreadEngine.Outcome.Applied -> {
                Haptics.success(context)
                val seconds = String.format(
                    Locale.forLanguageTag("ru"), "%.1f", outcome.result.latencyMs / 1000.0,
                )
                toast(context, context.getString(R.string.toast_done, seconds))
            }
            is ProofreadEngine.Outcome.CopiedToClipboard -> {
                Haptics.success(context)
                toast(context, context.getString(R.string.toast_copied))
            }
            is ProofreadEngine.Outcome.Unchanged -> {
                Haptics.success(context)
                toast(context, context.getString(R.string.toast_no_changes))
            }
            ProofreadEngine.Outcome.Rejected -> {
                Haptics.error(context)
            }
            is ProofreadEngine.Outcome.Failed -> {
                Haptics.error(context)
                toast(context, outcome.message, long = true)
            }
        }
    }

    fun toast(context: Context, message: String, long: Boolean = false) {
        Toast.makeText(
            context.applicationContext,
            message,
            if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
        ).show()
    }
}
