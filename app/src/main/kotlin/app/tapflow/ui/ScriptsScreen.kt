@file:OptIn(ExperimentalMaterial3Api::class)

package app.tapflow.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tapflow.R
import app.tapflow.Session
import app.tapflow.data.InvalidScriptException
import app.tapflow.data.Store
import app.tapflow.model.Limits
import app.tapflow.model.Script
import app.tapflow.model.StopMode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

@Composable
fun ScriptsScreen(onMessage: (String) -> Unit, onLoaded: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    val scripts by Store.scripts.collectAsStateWithLifecycle()
    var rename by remember { mutableStateOf<Script?>(null) }
    var delete by remember { mutableStateOf<Script?>(null) }
    var exportIds by remember { mutableStateOf<List<String>>(emptyList()) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val result = runCatching {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use(::readCapped) ?: throw IOException("unreadable")
            Store.importText(bytes.toString(Charsets.UTF_8))
        }
        onMessage(
            result.fold(
                onSuccess = { res.getQuantityString(R.plurals.import_ok, it, it) },
                onFailure = { res.getString(if (it is InvalidScriptException) R.string.import_invalid else R.string.import_failed) },
            ),
        )
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(Store.exportText(exportIds).toByteArray(Charsets.UTF_8)) }
                ?: throw IOException("unwritable")
        }.isSuccess
        onMessage(res.getString(if (ok) R.string.export_ok else R.string.export_failed))
    }

    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            AppOutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.btn_import)) }
            AppOutlinedButton(
                onClick = {
                    exportIds = scripts.map { it.id }
                    exportLauncher.launch("tapflow-scripts.json")
                },
                enabled = scripts.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.btn_export_all)) }
        }

        if (scripts.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.scripts_empty_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.scripts_empty_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(scripts, key = { it.id }) { s ->
                    ScriptCard(
                        script = s,
                        onLoad = {
                            if (Session.isBusy) onMessage(res.getString(R.string.err_busy_load))
                            else {
                                Session.loadScript(s)
                                onMessage(res.getString(R.string.loaded_snack, s.name))
                                onLoaded()
                            }
                        },
                        onRename = { rename = s },
                        onDuplicate = { Store.duplicate(s.id, res.getString(R.string.copy_suffix)) },
                        onExport = {
                            exportIds = listOf(s.id)
                            exportLauncher.launch("${s.name.replace(Regex("[^\\p{L}\\p{N} _-]"), "_")}.tapflow.json")
                        },
                        onDelete = { delete = s },
                    )
                }
            }
        }
    }

    rename?.let { s ->
        RenameDialog(s.name, onDismiss = { rename = null }) { Store.rename(s.id, it); rename = null }
    }
    delete?.let { s ->
        AppAlertDialog(
            onDismissRequest = { delete = null },
            title = { Text(stringResource(R.string.delete_title, s.name)) },
            text = { Text(stringResource(R.string.delete_desc)) },
            confirmButton = { AppTextButton(onClick = { Store.delete(s.id); delete = null }) { Text(stringResource(R.string.btn_delete)) } },
            dismissButton = { AppTextButton(onClick = { delete = null }) { Text(stringResource(R.string.btn_cancel)) } },
        )
    }
}

/** Reads at most [Limits.MAX_IMPORT_BYTES]; larger input is rejected rather than truncated. (No readNBytes: API 33+.) */
private fun readCapped(input: InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > Limits.MAX_IMPORT_BYTES) throw InvalidScriptException("too large")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

@Composable
private fun ScriptCard(
    script: Script,
    onLoad: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val active = script.id == Session.loadedScriptId.collectAsStateWithLifecycle().value
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(script.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val stop = when (script.config.stopMode) {
                    StopMode.INFINITE -> stringResource(R.string.stop_mode_infinite)
                    StopMode.COUNT -> stringResource(R.string.summary_count, script.config.stopCount)
                    StopMode.DURATION -> stringResource(R.string.summary_duration, script.config.stopDurationSec)
                }
                Text(
                    pluralStringResource(R.plurals.panel_targets, script.targets.size, script.targets.size) +
                        " · " + stringResource(R.string.summary_every, formatMs(script.config.intervalMs)) + " · " + stop,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AppIconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.menu_rename)) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(stringResource(R.string.menu_duplicate)) }, onClick = { menu = false; onDuplicate() })
                DropdownMenuItem(text = { Text(stringResource(R.string.menu_export)) }, onClick = { menu = false; onExport() })
                DropdownMenuItem(text = { Text(stringResource(R.string.btn_delete)) }, onClick = { menu = false; onDelete() })
            }
        }
        AppTonalButton(onClick = onLoad, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (active) R.string.btn_reload else R.string.btn_load))
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.menu_rename)) },
        text = {
            AppTextField(
                value = name,
                onValueChange = { name = it.take(Limits.MAX_NAME_LENGTH) },
                isError = name.isBlank(),
                supportingText = if (name.isBlank()) ({ Text(stringResource(R.string.err_required)) }) else null,
                singleLine = true,
            )
        },
        confirmButton = { AppTextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.btn_save)) } },
        dismissButton = { AppTextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) } },
    )
}
