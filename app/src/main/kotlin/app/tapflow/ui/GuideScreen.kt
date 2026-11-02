@file:OptIn(ExperimentalMaterial3Api::class)

package app.tapflow.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tapflow.R

private class Step(val title: Int, val body: Int, val action: Int? = null, val onAction: ((android.content.Context) -> Unit)? = null)

/** First-run walkthrough; also reachable from Settings. Explains the part that trips most users up. */
@Composable
fun GuideScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val steps = buildList {
        add(Step(R.string.guide1_title, R.string.guide1_body))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Step(R.string.guide2_title, R.string.guide2_body, R.string.btn_open_app_info) { it.openAppInfo() })
        }
        add(Step(R.string.guide3_title, R.string.guide3_body, R.string.btn_open_accessibility) { it.openAccessibilitySettings() })
        add(Step(R.string.guide4_title, R.string.guide4_body))
    }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val step = steps[index.coerceIn(0, steps.lastIndex)]

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(stringResource(R.string.guide_title), navigation = {
                AppIconButton(onClick = onDone) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                }
            })
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AppProgress((index + 1f) / steps.size)
            Text(stringResource(R.string.guide_step, index + 1, steps.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(step.title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(step.body), style = MaterialTheme.typography.bodyLarge)
                if (step.action != null && step.onAction != null) {
                    AppOutlinedButton(onClick = { step.onAction.invoke(ctx) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(step.action))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AppTextButton(onClick = { if (index > 0) index-- else onDone() }) {
                    Text(stringResource(if (index > 0) R.string.btn_back else R.string.btn_skip))
                }
                AppButton(onClick = { if (index < steps.lastIndex) index++ else onDone() }) {
                    Text(stringResource(if (index < steps.lastIndex) R.string.btn_next else R.string.btn_done))
                }
            }
        }
    }
}
