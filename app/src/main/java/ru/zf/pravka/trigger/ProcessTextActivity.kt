package ru.zf.pravka.trigger

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.R
import ru.zf.pravka.core.ProofreadMode
import ru.zf.pravka.target.TextTarget
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.Haptics

// "Правка" item in the text selection menu (ACTION_PROCESS_TEXT, spec 5.1).
// Fully transparent, passes touches through, lives until the request finishes.
class ProcessTextActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )

        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        val readonly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)

        if (text.isBlank()) {
            Haptics.error(this)
            finish()
            return
        }

        Haptics.start(this)
        setContent { WorkingBanner() }

        val target = object : TextTarget {
            override suspend fun read(): String = text

            override suspend fun write(fixed: String): Boolean {
                if (readonly) return false  // engine falls back to the clipboard
                setResult(
                    RESULT_OK,
                    Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, fixed),
                )
                return true
            }

            // The selection menu only appears on a REAL selection - the
            // owner deliberately picked this fragment, so a single word is
            // legitimate work (same contract as a selection under the FAB).
            // The old pre-check rejected short selections that the FAB path
            // happily fixed.
            override fun isExplicitFragment(): Boolean = true
        }

        val app = application as PravkaApp
        lifecycleScope.launch {
            Feedback.report(this@ProcessTextActivity, app.engine.proofread(target, ProofreadMode.CLEAN))
            finish()
        }
    }
}

@Composable
private fun WorkingBanner() {
    Box(
        modifier = Modifier.fillMaxSize().padding(bottom = 64.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = Color(0xCC1B3A5C),
        ) {
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.banner_working),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}
