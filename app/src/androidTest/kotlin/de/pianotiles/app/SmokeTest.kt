package de.pianotiles.app

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Clicks through the main flows on a real device/emulator. Screenshots are written to
 * <external files>/screenshots and uploaded by the CI.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val mic: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun shot(name: String) {
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitText(text: String, timeout: Long = 15_000): SemanticsNodeInteraction {
        compose.waitUntil(timeout) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        return compose.onAllNodesWithText(text, substring = true)[0]
    }

    @Test
    fun libraryPracticeEditorSettings() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitText("Meine Bibliothek")
            waitText("Alle meine Entchen")
            shot("01_library")

            waitText("Ode an die Freude").performClick()
            waitText("Takt")
            shot("02_practice_paused")

            // Flow mode, play for a while
            compose.onNodeWithText("Fließend").performClick()
            compose.onAllNodesWithContentDescription("Start")[0].performClick()
            compose.mainClock.autoAdvance = true
            Thread.sleep(4000)
            compose.waitForIdle()
            shot("03_practice_playing")
            compose.onNodeWithContentDescription("Pause").performClick()

            // Hands, wait mode with on-screen keys
            compose.onNodeWithText("R").performClick()
            compose.onNodeWithText("Warten").performClick()
            compose.onAllNodesWithContentDescription("Start")[0].performClick()
            Thread.sleep(2500)
            shot("04_wait_mode")
            compose.onNodeWithContentDescription("Pause").performClick()

            // Speed dialog
            compose.onNodeWithText("100 %").performClick()
            waitText("Tempo")
            shot("05_speed")
            compose.onNodeWithText("Fertig").performClick()

            // Loop panel
            compose.onNodeWithContentDescription("Schleife an/aus").performClick()
            compose.onAllNodesWithText("T. ", substring = true)[0].performClick()
            waitText("Übungsschleife")
            shot("06_loop")
            compose.onNodeWithText("Fertig").performClick()

            // Editor
            compose.onNodeWithContentDescription("Noten bearbeiten").performClick()
            waitText("Noten bearbeiten –")
            shot("07_editor")
            compose.onNodeWithContentDescription("Zurück").performClick()
            waitText("Takt")
            compose.onNodeWithContentDescription("Zurück").performClick()

            // Settings & help
            waitText("Meine Bibliothek")
            compose.onNodeWithContentDescription("Einstellungen").performClick()
            waitText("Notennamen")
            shot("08_settings")
            compose.onNodeWithContentDescription("Zurück").performClick()
            compose.onNodeWithContentDescription("Hilfe").performClick()
            waitText("Die drei Modi")
            compose.onNodeWithContentDescription("Zurück").performClick()
            waitText("Meine Bibliothek")
        }
    }

    @Test
    fun importPdf() {
        val pdf = File(context.cacheDir, "mozart.pdf")
        InstrumentationRegistry.getInstrumentation().context.assets.open("mozart.pdf").use { input ->
            pdf.outputStream().use { input.copyTo(it) }
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(Uri.fromFile(pdf), "application/pdf")
        }
        ActivityScenario.launch<MainActivity>(intent).use {
            waitText("Noten in", timeout = 120_000)
            shot("09_import_review")
            compose.onNodeWithText("Speichern & üben").performClick()
            waitText("Takt", timeout = 20_000)
            shot("10_imported_practice")
            compose.onNodeWithContentDescription("Zurück").performClick()
            waitText("Meine Bibliothek")
            shot("11_library_after_import")
        }
        @Suppress("UNUSED_VARIABLE") val unused = hasText("") to hasContentDescription("")
    }
}
