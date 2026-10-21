package de.pianotiles.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import de.pianotiles.app.ui.editor.EditorScreen
import de.pianotiles.app.ui.importflow.ImportScreen
import de.pianotiles.app.ui.library.LibraryScreen
import de.pianotiles.app.ui.practice.PracticeScreen
import de.pianotiles.app.ui.settings.HelpScreen
import de.pianotiles.app.ui.settings.SettingsScreen
import de.pianotiles.app.ui.theme.PianoTilesTheme
import de.pianotiles.app.ui.theme.PtColors

sealed interface Screen {
    data object Library : Screen
    data class Practice(val songId: String) : Screen
    data class Import(val uri: Uri) : Screen
    data class Editor(val songId: String) : Screen
    data object Settings : Screen
    data object Help : Screen
}

class MainActivity : ComponentActivity() {
    private val backStack = mutableStateListOf<Screen>(Screen.Library)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Full screen: hide system bars, swipe from the edge shows them temporarily.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        handleIntent(intent)
        val app = application as PianoTilesApp
        setContent {
            PianoTilesTheme {
                LaunchedEffect(Unit) { app.songs.load() }
                Box(Modifier.fillMaxSize().background(PtColors.Background).safeDrawingPadding()) { Navigation() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) backStack.add(Screen.Import(uri))
    }

    private fun push(s: Screen) { backStack.add(s) }
    private fun pop() { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) else finish() }
    private fun replaceTop(s: Screen) { backStack[backStack.lastIndex] = s }

    @Composable
    private fun Navigation() {
        BackHandler(enabled = backStack.size > 1) { pop() }
        when (val screen = backStack.last()) {
            Screen.Library -> LibraryScreen(
                onOpen = { push(Screen.Practice(it)) },
                onImport = { push(Screen.Import(it)) },
                onEdit = { push(Screen.Editor(it)) },
                onSettings = { push(Screen.Settings) },
                onHelp = { push(Screen.Help) },
            )
            is Screen.Practice -> PracticeScreen(screen.songId, onBack = ::pop, onEdit = { push(Screen.Editor(it)) })
            is Screen.Import -> ImportScreen(screen.uri, onBack = ::pop, onDone = { id, edit -> replaceTop(if (edit) Screen.Editor(id) else Screen.Practice(id)) })
            is Screen.Editor -> EditorScreen(screen.songId, onBack = ::pop)
            Screen.Settings -> SettingsScreen(onBack = ::pop)
            Screen.Help -> HelpScreen(onBack = ::pop)
        }
    }
}
