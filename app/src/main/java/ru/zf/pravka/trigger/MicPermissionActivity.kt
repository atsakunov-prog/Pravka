package ru.zf.pravka.trigger

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import ru.zf.pravka.R
import ru.zf.pravka.ui.Feedback

// Invisible one-shot: the accessibility service can't request a runtime
// permission (no Activity), so a short tap without RECORD_AUDIO bounces
// here. On grant it kicks the recording back off in the service and closes.
class MicPermissionActivity : ComponentActivity() {

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            PravkaAccessibilityService.instance?.onMicPermissionGranted()
        } else {
            Feedback.toast(this, getString(R.string.dictation_no_mic_permission))
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            PravkaAccessibilityService.instance?.onMicPermissionGranted()
            finish()
        } else {
            requestPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
