package de.pianotiles.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/**
 * Gives a screen its own ViewModel store which is cleared when the screen leaves the
 * composition, so ViewModels (and their microphone, statistics, …) live exactly as long as the screen.
 */
@Composable
fun ScreenScope(screenKey: Any, content: @Composable () -> Unit) {
    key(screenKey) {
        val owner = remember {
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        }
        DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content() }
    }
}
