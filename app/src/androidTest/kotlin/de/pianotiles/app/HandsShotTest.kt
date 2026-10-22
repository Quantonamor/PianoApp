package de.pianotiles.app

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import de.pianotiles.core.model.NoteNaming
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The 3D hands in the real app. Run separately by the CI after the main tests (the emulator's software renderer
 * has crashed on the hands before, so a failure here must not hide the results of the other tests).
 */
@RunWith(AndroidJUnit4::class)
class HandsShotTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val mic: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        val app = context.applicationContext as PianoTilesApp
        app.settings.update { it.copy(language = "de", noteNaming = NoteNaming.GERMAN, showHands = true, showFingers = true) }
        app.applyLanguage()
        app.practiceLog.dismissWelcome()
    }

    private fun shot(name: String) {
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val bmp = compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Bitmap.createScaledBitmap(bmp, 640, (bmp.height * 640 / bmp.width).coerceAtLeast(1), true).let { small ->
            File(dir, "$name.small.jpg").outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 55, it) }
        }
    }

    private fun waitText(text: String, timeout: Long = 15_000) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            compose.mainClock.advanceTimeBy(100)
            if (compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()) return
            Thread.sleep(50)
        }
        throw AssertionError("Text '$text' not shown within $timeout ms")
    }

    private fun play(ms: Long) {
        var t = 0L
        while (t < ms) { compose.mainClock.advanceTimeBy(50); Thread.sleep(40); t += 50 }
    }

    @Test
    fun handsOverTheKeys() {
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(MainActivity::class.java).use {
            waitText("Meine Bibliothek")
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Ode an die Freude"))
            compose.onAllNodesWithText("Ode an die Freude")[0].performClick()
            waitText("Takt")
            play(1500)
            shot("08_hands_paused")
            compose.onNodeWithContentDescription("Fließend").performClick()
            compose.onAllNodesWithContentDescription("Start")[0].performClick()
            play(4000)
            shot("08b_hands_playing")
            it.onActivity { a -> a.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            play(2500)
            shot("08c_hands_landscape")
        }
    }
}
