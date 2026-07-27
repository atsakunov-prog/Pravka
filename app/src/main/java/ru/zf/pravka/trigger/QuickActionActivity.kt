package ru.zf.pravka.trigger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.zf.pravka.R
import ru.zf.pravka.core.ProofreadMode
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.PravkaTheme

// Mode chooser behind the quick-settings tile (spec stage 4, Quick Tap):
// a transparent sheet over the current app. Actions run through the
// accessibility service's cached focus node, so the field keeps its text
// even though this activity briefly steals focus.
class QuickActionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = PravkaAccessibilityService.instance
        if (service == null) {
            Feedback.toast(this, getString(R.string.toast_no_service))
            finish()
            return
        }
        setContent {
            PravkaTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { finish() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    // onClick = {} keeps taps on the sheet from falling
                    // through to the dismiss area behind it.
                    Card(onClick = {}, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Action(R.string.quick_clean) { service.trigger(ProofreadMode.CLEAN) }
                            Action(R.string.fab_menu_business) { service.trigger(ProofreadMode.BUSINESS) }
                            Action(R.string.fab_menu_soften) { service.trigger(ProofreadMode.SOFTEN) }
                            Action(R.string.fab_menu_undo) { service.triggerUndo() }
                        }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Action(labelRes: Int, run: () -> Unit) {
        TextButton(
            onClick = {
                run()
                finish()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(labelRes),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
