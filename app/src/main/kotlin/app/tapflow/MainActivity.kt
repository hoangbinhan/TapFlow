package app.tapflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tapflow.ui.A11yStatus
import app.tapflow.ui.GuideScreen
import app.tapflow.ui.HomeScreen
import app.tapflow.ui.ScriptsScreen
import app.tapflow.ui.SettingsScreen
import app.tapflow.ui.TapFlowTheme
import app.tapflow.ui.AppBottomBar
import app.tapflow.ui.NavItem
import app.tapflow.ui.AppSnackbar
import app.tapflow.ui.AppTopBar
import app.tapflow.ui.TestModeScreen
import app.tapflow.ui.isServiceEnabledInSettings
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Bars follow the system theme, matching the app's own light/dark palette.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent { TapFlowTheme { AppRoot() } }
    }
}

private enum class Tab(val label: Int) {
    HOME(R.string.nav_home), SCRIPTS(R.string.nav_scripts), TEST(R.string.nav_test), SETTINGS(R.string.nav_settings)
}
private enum class Sub { NONE, GUIDE }

@Composable
private fun AppRoot() {
    val ctx = LocalContext.current
    val onboarded by AppSettings.onboarded.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sub by rememberSaveable { mutableStateOf(if (onboarded) Sub.NONE else Sub.GUIDE) }
    val connected by Session.serviceConnected.collectAsStateWithLifecycle()

    // Re-read the system setting whenever we come back from Settings; the user may have toggled it.
    var enabledInSettings by remember { mutableStateOf(isServiceEnabledInSettings(ctx)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) enabledInSettings = isServiceEnabledInSettings(ctx)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val a11y by remember(connected) {
        derivedStateOf {
            when {
                connected -> A11yStatus.ON
                enabledInSettings -> A11yStatus.STALE
                else -> A11yStatus.OFF
            }
        }
    }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val say: (String) -> Unit = { msg -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(msg) } }

    BackHandler(enabled = sub != Sub.NONE) {
        if (sub == Sub.GUIDE) AppSettings.setOnboarded(true)
        sub = Sub.NONE
    }

    when (sub) {
        Sub.GUIDE -> GuideScreen(onDone = { AppSettings.setOnboarded(true); sub = Sub.NONE })
        Sub.NONE -> if (Tab.entries[tab] == Tab.TEST) {
            // Full screen on purpose: no bars, so an injected tap can never land on the navigation.
            TestModeScreen(a11y, onExit = { tab = 0 })
        } else Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { AppTopBar(stringResource(R.string.app_name)) },
            snackbarHost = { SnackbarHost(snackbar) { AppSnackbar(it) } },
            bottomBar = {
                val items = Tab.entries.map { t ->
                    when (t) {
                        Tab.HOME -> NavItem(stringResource(t.label), Icons.Default.Home)
                        Tab.SCRIPTS -> NavItem(stringResource(t.label), Icons.AutoMirrored.Filled.List)
                        Tab.TEST -> NavItem(stringResource(t.label), Icons.Default.PlayArrow)
                        Tab.SETTINGS -> NavItem(stringResource(t.label), Icons.Default.Settings)
                    }
                }
                AppBottomBar(items, tab) { tab = it }
            },
        ) { pad ->
            // Edge-to-edge does not resize for the keyboard by itself; without this, focused fields hide under it.
            val m = Modifier.padding(pad).consumeWindowInsets(pad).imePadding()
            when (Tab.entries[tab]) {
                Tab.HOME -> HomeScreen(a11y, say, { sub = Sub.GUIDE }, m)
                Tab.SCRIPTS -> ScriptsScreen(say, { tab = 0 }, m)
                Tab.SETTINGS -> SettingsScreen({ sub = Sub.GUIDE }, m)
                Tab.TEST -> Unit
            }
        }
    }
}
