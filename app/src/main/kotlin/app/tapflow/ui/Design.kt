package app.tapflow.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// Thin wrappers over Material 3 so every screen shares one look; only colours, shapes and sizes are decided here.

private val ButtonHeight = 48.dp

@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val p = LocalPalette.current
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = ButtonHeight),
        enabled = enabled,
        shape = ControlShape,
        colors = if (destructive) ButtonDefaults.buttonColors(containerColor = p.danger, contentColor = p.onAccent) else ButtonDefaults.buttonColors(),
        content = content,
    )
}

@Composable
fun AppOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = ButtonHeight),
        enabled = enabled,
        shape = ControlShape,
        border = BorderStroke(1.dp, LocalPalette.current.outline),
        content = content,
    )

@Composable
fun AppTonalButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    FilledTonalButton(onClick = onClick, modifier = modifier.heightIn(min = ButtonHeight), enabled = enabled, shape = ControlShape, content = content)

@Composable
fun AppTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = ButtonHeight), enabled = enabled, shape = ControlShape, content = content)

@Composable
fun AppIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) =
    IconButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)

@Composable
fun AppChip(selected: Boolean, enabled: Boolean, onClick: () -> Unit, label: String) {
    val p = LocalPalette.current
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        shape = ControlShape,
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = p.accentSoft, selectedLabelColor = p.onAccentSoft),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled, selected = selected,
            borderColor = p.outline, selectedBorderColor = p.accentSoft,
        ),
    )
}

/** A row of mutually exclusive options; the selected one is tinted. No check icon, so short labels fit. */
@Composable
fun AppSegmented(options: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val colors = SegmentedButtonDefaults.colors(
        activeContainerColor = p.accentSoft, activeContentColor = p.onAccentSoft, activeBorderColor = p.accentSoft,
        inactiveContainerColor = p.surface, inactiveContentColor = p.text, inactiveBorderColor = p.outline,
    )
    SingleChoiceSegmentedButtonRow(modifier) {
        options.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selected,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size, baseShape = ControlShape),
                enabled = enabled,
                colors = colors,
                icon = {},
                modifier = Modifier.fillMaxHeight().heightIn(min = ButtonHeight),
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun AppProgress(fraction: Float, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().clip(ControlShape),
        color = p.accent,
        trackColor = p.accentSoft,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) = OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier,
    label = label,
    isError = isError,
    supportingText = supportingText,
    singleLine = singleLine,
    enabled = enabled,
    keyboardOptions = keyboardOptions,
    shape = ControlShape,
    textStyle = MaterialTheme.typography.bodyLarge,
)

@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) = AlertDialog(
    onDismissRequest = onDismissRequest,
    title = title,
    text = text,
    confirmButton = confirmButton,
    dismissButton = dismissButton,
    shape = CardShape,
    containerColor = LocalPalette.current.surface,
)

/** Plain title row on the page background; [navigation] is an optional leading control. */
@Composable
fun AppTopBar(title: String, modifier: Modifier = Modifier, navigation: (@Composable () -> Unit)? = null) {
    val p = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .background(p.bg)
            .statusBarsPadding()
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        navigation?.invoke()
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = p.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = if (navigation == null) 4.dp else 0.dp),
        )
    }
}

class NavItem(val label: String, val icon: ImageVector)

@Composable
fun AppBottomBar(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    NavigationBar(
        containerColor = p.surface,
        tonalElevation = 0.dp,
        modifier = Modifier.drawBehind { drawRect(p.outline, size = Size(size.width, 1.dp.toPx())) },
    ) {
        items.forEachIndexed { i, item ->
            NavigationBarItem(
                selected = i == selected,
                onClick = { onSelect(i) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = p.onAccentSoft, selectedTextColor = p.onAccentSoft, indicatorColor = p.accentSoft,
                    unselectedIconColor = p.muted, unselectedTextColor = p.muted,
                ),
            )
        }
    }
}

@Composable
fun AppSnackbar(data: SnackbarData) =
    Snackbar(data, modifier = Modifier.padding(12.dp), shape = ControlShape)

/** Small label. [tonal] fills it with the soft accent; otherwise it is only outlined. */
@Composable
fun AppBadge(text: String, modifier: Modifier = Modifier, tonal: Boolean = true) {
    val p = LocalPalette.current
    Box(
        modifier
            .clip(ControlShape)
            .then(if (tonal) Modifier.background(p.accentSoft) else Modifier.border(1.dp, p.outline, ControlShape))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (tonal) p.onAccentSoft else p.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
