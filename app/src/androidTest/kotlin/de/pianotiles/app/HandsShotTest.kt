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
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * The 3D hands in the real app. Run separately by the CI after the main tests (the emulator's software renderer
 * has crashed on the hands before, so a failure here must not hide the results of the other tests).
 */
@RunWith(AndroidJUnit4::class)
class HandsShotTest {
    @get:Rule val compose = createEmptyComposeRule()
    /** A hanging test fails after 5 minutes with the stack of the stuck thread instead of blocking the CI job. */
    @get:Rule val timeout: Timeout = Timeout.builder().withTimeout(300, TimeUnit.SECONDS).withLookingForStuckThread(true).build()
    /** On a failure all thread stacks (above all the app's main thread) go to screenshots/threads.txt for the CI log. */
    @get:Rule val threadDump = object : org.junit.rules.TestWatcher() {
        override fun failed(e: Throwable?, description: org.junit.runner.Description) {
            runCatching {
                val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
                val text = buildString {
                    append("== ${description.methodName}: $e\n")
                    for ((t, st) in Thread.getAllStackTraces()) {
                        append("-- ${t.name} (${t.state})\n")
                        st.take(25).forEach { append("    at $it\n") }
                    }
                }
                File(dir, "threads.txt").appendText(text)
            }
        }
    }
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
            // scrolling needs frames: let the clock run meanwhile (with a paused clock the test waits for idle forever)
            compose.mainClock.autoAdvance = true
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Ode an die Freude"))
            compose.mainClock.autoAdvance = false
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
