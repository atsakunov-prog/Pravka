package ru.zf.pravka

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.zf.pravka.data.Settings

// Minimal settings surface - API key, CLEAN model choice, accessibility
// service status. The full four-screen UI (Prompts, Dictionary, Settings,
// Diagnostics) arrives in stages 5-8.
class MainActivity : ComponentActivity() {

    private val serviceEnabled = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = (application as PravkaApp).settings
        setContent {
            MaterialTheme {
                SettingsLiteScreen(
                    settings = settings,
                    serviceEnabled = serviceEnabled.value,
                    onOpenAccessibilitySettings = {
                        startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        serviceEnabled.value = ru.zf.pravka.trigger.PravkaAccessibilityService.instance != null
    }
}

@Composable
private fun SettingsLiteScreen(
    settings: Settings,
    serviceEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(Settings.MODEL_SONNET) }
    var keyVisible by remember { mutableStateOf(false) }
    var savedMark by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        apiKey = settings.apiKey()
        model = settings.cleanModel()
        loaded = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Text(
            stringResource(R.string.build_info, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.BUILD_TIME),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(24.dp))

        Text(
            stringResource(if (serviceEnabled) R.string.service_status_on else R.string.service_status_off),
            style = MaterialTheme.typography.titleMedium,
            color = if (serviceEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        if (!serviceEnabled) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onOpenAccessibilitySettings) {
                Text(stringResource(R.string.service_enable))
            }
        }
        Text(
            stringResource(R.string.service_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(24.dp))

        Text(stringResource(R.string.settings_api_key_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; savedMark = false },
            enabled = loaded,
            singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            label = { Text(stringResource(R.string.settings_api_key_label)) },
            trailingIcon = {
                TextButton(onClick = { keyVisible = !keyVisible }) {
                    Text(stringResource(if (keyVisible) R.string.settings_hide else R.string.settings_show))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.settings_api_key_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(24.dp))

        Text(stringResource(R.string.settings_model_title), style = MaterialTheme.typography.titleMedium)
        ModelOption(
            label = stringResource(R.string.settings_model_sonnet),
            selected = model == Settings.MODEL_SONNET,
        ) { model = Settings.MODEL_SONNET; savedMark = false }
        ModelOption(
            label = stringResource(R.string.settings_model_haiku),
            selected = model == Settings.MODEL_HAIKU,
        ) { model = Settings.MODEL_HAIKU; savedMark = false }
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                scope.launch {
                    settings.setApiKey(apiKey)
                    settings.setCleanModel(model)
                    savedMark = true
                }
            },
            enabled = loaded,
        ) {
            Text(stringResource(if (savedMark) R.string.settings_saved else R.string.settings_save))
        }

        Spacer(Modifier.height(32.dp))
        Text(
            stringResource(R.string.settings_usage_hint),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ModelOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
