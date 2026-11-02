package app.tapflow.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tapflow.AppSettings
import app.tapflow.BuildConfig
import app.tapflow.R

@Composable
fun SettingsScreen(onOpenGuide: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val volume by AppSettings.volumeStop.collectAsStateWithLifecycle()
    val notif by AppSettings.stopNotification.collectAsStateWithLifecycle()
    val haptics by AppSettings.haptics.collectAsStateWithLifecycle()

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        AppSettings.setStopNotification(granted)
    }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(title = stringResource(R.string.settings_safety)) {
            SwitchRow(stringResource(R.string.set_volume_title), stringResource(R.string.set_volume_desc), volume, AppSettings::setVolumeStop)
            SwitchRow(stringResource(R.string.set_notif_title), stringResource(R.string.set_notif_desc), notif) { on ->
                if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    AppSettings.setStopNotification(on)
                }
            }
            SwitchRow(stringResource(R.string.set_haptics_title), null, haptics, AppSettings::setHaptics)
        }

        SectionCard(title = stringResource(R.string.settings_trouble)) {
            Text(stringResource(R.string.trouble_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppOutlinedButton(onClick = { ctx.openAccessibilitySettings() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_open_accessibility)) }
            AppOutlinedButton(onClick = { ctx.openAppInfo() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_open_app_info)) }
            AppOutlinedButton(onClick = { ctx.openBatterySettings() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_battery)) }
            AppOutlinedButton(onClick = onOpenGuide, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_setup_guide)) }
        }

        SectionCard(title = stringResource(R.string.settings_about)) {
            Text(stringResource(R.string.about_privacy), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
