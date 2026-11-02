package app.tapflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.tapflow.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    Column(
        modifier.fillMaxWidth().background(p.surface, CardShape).border(1.dp, p.outline, CardShape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (title != null || headerAction != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleMedium) else Spacer(Modifier.width(1.dp))
                headerAction?.invoke()
            }
        }
        content()
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

/**
 * Digits-only numeric input. Calls [onValid] only for values that pass [validate]; invalid text
 * stays on screen with an error so the user can correct it instead of silently losing keystrokes.
 */
@Composable
fun IntField(
    label: String,
    initial: Int,
    min: Int,
    max: Int,
    onValid: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    resetKey: Any? = null,
) {
    var text by remember(resetKey) { mutableStateOf(initial.toString()) }
    var error by remember(resetKey) { mutableStateOf<String?>(null) }
    val required = stringResource(R.string.err_required)
    val range = stringResource(R.string.err_range, min, max)
    AppTextField(
        value = text,
        onValueChange = { raw ->
            val t = raw.filter(Char::isDigit).take(9)
            text = t
            val v = t.toIntOrNull()
            when {
                t.isEmpty() -> error = required
                v == null || v < min || v > max -> error = range
                else -> {
                    error = null
                    onValid(v)
                }
            }
        },
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
