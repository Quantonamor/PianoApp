package de.pianotiles.app

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import de.pianotiles.core.model.NoteNaming
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * Clicks through the main flows on a real device/emulator. Screenshots are written to
 * <external files>/screenshots and uploaded by the CI.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    /** A hanging test fails after 5 minutes with the stack of the stuck thread instead of blocking the CI job. */
    @get:Rule val timeout: Timeout = Timeout.builder().withTimeout(300, TimeUnit.SECONDS).withLookingForStuckThread(true).build()
    @get:Rule val mic: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The emulator runs in English, but the checks below use the German texts: force the German UI
     * (the activity applies the language setting again in onCreate).
     */
    @Before
    fun forceGerman() {
        val app = context.applicationContext as PianoTilesApp
        // hands off: the per-frame bitmap upload crashes the emulator's software renderer (swiftshader,
        // "Failed to find ColorBuffer"); the hands are checked with the JVM previews of HandRenderTest instead
        app.settings.update { it.copy(language = "de", noteNaming = NoteNaming.GERMAN, showHands = false) }
        app.applyLanguage()
        // the welcome card is checked in libraryPracticeEditorSettings only (test order is not fixed)
        app.practiceLog.dismissWelcome()
    }

    private fun shot(name: String) {
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        // With a dialog open there are several roots; capture the top-most one.
        val roots = compose.onAllNodes(isRoot())
        val count = roots.fetchSemanticsNodes(atLeastOneRootRequired = false).size
        val bmp = roots[(count - 1).coerceAtLeast(0)].captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // small JPEG for the CI log (the full PNGs are only in the artifact)
        val w = 640
        Bitmap.createScaledBitmap(bmp, w, (bmp.height * w / bmp.width).coerceAtLeast(1), true).let { small ->
            File(dir, "$name.small.jpg").outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 45, it) }
        }
        reportLayoutProblems(name, File(dir, "layout.txt"))
    }

    /**
     * Writes overlapping labels/buttons and cut-off texts of the current screen to [out] (the CI prints the file),
     * so layout problems on phone-sized screens show up without looking at the screenshots.
     */
    private fun reportLayoutProblems(screen: String, out: File) {
        class Item(val label: String, val bounds: androidx.compose.ui.geometry.Rect, val ancestors: Set<Int>, val id: Int)
        val lines = ArrayList<String>()
        for (root in compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)) {
            val items = ArrayList<Item>()
            fun walk(n: androidx.compose.ui.semantics.SemanticsNode, anc: Set<Int>) {
                val text = n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString(" ")
                val desc = n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.joinToString(" ")
                val label = text ?: desc
                val b = n.boundsInRoot
                if (label != null && b.width > 1f && b.height > 1f) {
                    items.add(Item(label.take(40), b, anc, n.id))
                }
                for (c in n.children) walk(c, anc + n.id)
            }
            walk(root, emptySet())
            for (i in items.indices) for (j in i + 1 until items.size) {
                val a = items[i]; val c = items[j]
                if (a.id in c.ancestors || c.id in a.ancestors) continue
                val inter = a.bounds.intersect(c.bounds)
                if (inter.width <= 0f || inter.height <= 0f) continue
                val area = inter.width * inter.height
                val smaller = minOf(a.bounds.width * a.bounds.height, c.bounds.width * c.bounds.height)
                if (area > 20f && area > smaller * 0.15f) lines.add("$screen: OVERLAP '${a.label}' ${a.bounds.let { "${it.left.toInt()},${it.top.toInt()}-${it.right.toInt()},${it.bottom.toInt()}" }} x '${c.label}' ${c.bounds.let { "${it.left.toInt()},${it.top.toInt()}-${it.right.toInt()},${it.bottom.toInt()}" }}")
            }
        }
        out.appendText(if (lines.isEmpty()) "$screen: ok\n" else lines.distinct().joinToString("\n", postfix = "\n"))
    }

    /**
     * The practice screen animates continuously, so Compose never becomes idle. The test therefore drives
     * the clock itself: every check advances it by 100 ms.
     */
    private fun waitText(text: String, timeout: Long = 15_000): SemanticsNodeInteraction {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            compose.mainClock.advanceTimeBy(100)
            if (compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()) {
                return compose.onAllNodesWithText(text, substring = true)[0]
            }
            Thread.sleep(50)
        }
        throw AssertionError("Text '$text' not shown within $timeout ms")
    }

    private fun waitDesc(desc: String, timeout: Long = 15_000) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            compose.mainClock.advanceTimeBy(100)
            if (compose.onAllNodesWithContentDescription(desc).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()) return
            Thread.sleep(50)
        }
        throw AssertionError("'$desc' not shown within $timeout ms")
    }

    private fun play(ms: Long) {
        var t = 0L
        while (t < ms) { compose.mainClock.advanceTimeBy(50); Thread.sleep(25); t += 50 }
    }

    @Test
    fun libraryPracticeEditorSettings() {
        (context.applicationContext as PianoTilesApp).practiceLog.showWelcomeAgain()
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(MainActivity::class.java).use {
            waitText("Meine Bibliothek")
            // the piano recordings (MP3) are decoded with the platform decoder in the background
            val app = context.applicationContext as PianoTilesApp
            val until = System.currentTimeMillis() + 60_000
            while (!app.synth.recordingsLoaded && System.currentTimeMillis() < until) Thread.sleep(200)
            assertTrue("piano recordings decoded", app.synth.recordingsLoaded)
            waitText("Willkommen!")
            shot("01_library")
            // the welcome card is shown once; closing it keeps the library as before
            compose.onNodeWithContentDescription("Schließen").performClick()
            waitText("Alle meine Entchen")
            it.onActivity { a -> a.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            play(1500)
            shot("01b_library_landscape")
            it.onActivity { a -> a.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            play(1500)

            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Ode an die Freude"))
            waitText("Ode an die Freude").performClick()
            waitText("Takt")
            shot("02_practice_paused")

            // Flow mode, play for a while
            compose.onNodeWithContentDescription("Fließend").performClick()
            compose.onAllNodesWithContentDescription("Start")[0].performClick()
            play(4000)
            shot("03_practice_playing")
            compose.onNodeWithContentDescription("Pause").performClick()
            play(600) // controls slide back in

            // sheet engraved from the notes next to the falling notes
            compose.onNodeWithContentDescription("Notenblatt").performClick()
            play(1500)
            shot("03a_engraved_sheet")
            compose.onNodeWithContentDescription("Notenblatt").performClick()
            play(300)

            // view menu: measure numbers off and on again (hands stay off, see forceGerman)
            compose.onNodeWithContentDescription("Ansicht").performClick()
            waitText("Hände über der Klaviatur")
            shot("03b_view_menu")
            compose.onNodeWithText("Taktnummern").performClick()
            play(300)
            shot("03c_no_measure_numbers")
            compose.onNodeWithContentDescription("Ansicht").performClick()
            waitText("Taktnummern").performClick()
            play(300)

            // the same screen in landscape (the app follows the device orientation)
            it.onActivity { a -> a.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            play(1500)
            compose.onAllNodesWithContentDescription("Start")[0].performClick()
            play(2500)
            shot("03d_landscape_playing")
            compose.onNodeWithContentDescription("Pause").performClick()
            play(300)
            shot("03e_landscape_paused")
            it.onActivity { a -> a.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            play(1500)

            // Hands, wait mode with on-screen keys
            compose.onNodeWithText("R").performClick()
            compose.onNodeWithContentDescription("Warten").performClick()
            compose.onAllNodesWithContentDescription("Start")[0].performClick()
            play(2500)
            shot("04_wait_mode")
            compose.onNodeWithContentDescription("Pause").performClick()
            play(600) // controls slide back in

            // Speed dialog
            compose.onNode(hasText("100 %") and hasClickAction()).performClick()
            waitText("Tempo")
            shot("05_speed")
            compose.onNodeWithText("Fertig").performClick()

            // Loop panel
            compose.onNodeWithContentDescription("Schleife an/aus").performClick()
            play(300) // let the manual clock recompose after the toggle
            val loopChip = compose.onAllNodes(hasText("T. ", substring = true) or hasText("Schleife", substring = false))
            loopChip[0].performClick()
            waitText("Übungsschleife")
            shot("06_loop")
            compose.onNodeWithText("Fertig").performClick()

            // Editor
            compose.onNodeWithContentDescription("Noten bearbeiten").performClick()
            waitDesc("Rückgängig") // editor (its title is shortened on phones)
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
            waitText("Meine Bibliothek")
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
        compose.mainClock.autoAdvance = false
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

    /** "Abspielen" on a library card starts the piece in listening mode. */
    @Test
    fun playFromLibrary() {
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(MainActivity::class.java).use {
            waitText("Meine Bibliothek")
            waitText("Alle meine Entchen")
            compose.onAllNodesWithContentDescription("Abspielen")[0].performClick()
            waitDesc("Pause") // playing: only notes, keys and the small back/pause buttons
            play(1500)
            shot("12_play_from_library")
            compose.onNodeWithContentDescription("Zurück").performClick()
            waitText("Meine Bibliothek")
        }
    }

    /** ABC notation opened from another app lands directly in the practice screen. */
    @Test
    fun importAbc() {
        val abc = File(context.cacheDir, "tune.abc")
        abc.writeText("X:1\nT:Smoke Tune\nM:3/4\nL:1/4\nK:G\nG A B | c2 d | B3 |]\n")
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(Uri.fromFile(abc), "text/plain")
        }
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch<MainActivity>(intent).use {
            waitText("Takt", timeout = 20_000)
            compose.onNodeWithContentDescription("Zurück").performClick()
            waitText("Smoke Tune")
            shot("13_library_after_abc")
        }
    }

    /** A rotated, shaded photo of the Mozart page is prepared and recognised. */
    @Test
    fun importPhoto() {
        val pdf = File(context.cacheDir, "mozart.pdf")
        InstrumentationRegistry.getInstrumentation().context.assets.open("mozart.pdf").use { input ->
            pdf.outputStream().use { input.copyTo(it) }
        }
        val page = android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(pdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            r.openPage(0).use { p ->
                Bitmap.createBitmap(2000, 2000 * p.height / p.width, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(android.graphics.Color.WHITE)
                    p.render(it, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
        val photo = Bitmap.createBitmap(2400, 3200, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(photo).apply {
            drawColor(android.graphics.Color.rgb(70, 60, 50)) // table
            rotate(3f, 1200f, 1600f)
            drawBitmap(page, 200f, 200f, null)
            drawRect(0f, 0f, 1200f, 3200f, android.graphics.Paint().apply { color = android.graphics.Color.argb(70, 0, 0, 0) }) // shadow
        }
        val jpg = File(context.cacheDir, "photo.jpg")
        jpg.outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(Uri.fromFile(jpg), "image/jpeg")
        }
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch<MainActivity>(intent).use {
            waitText("Noten in", timeout = 180_000)
            shot("14_photo_review")
            compose.onNodeWithText("Speichern & üben").performClick()
            waitText("Takt", timeout = 20_000)
            compose.onNodeWithContentDescription("Zurück").performClick()
            waitText("Meine Bibliothek")
        }
    }

    /** Song packages keep all parts when exported and imported again. */
    @Test
    fun packageRoundTrip() {
        val app = context.applicationContext as PianoTilesApp
        kotlinx.coroutines.runBlocking {
            app.songs.load()
            val song = app.songs.songs.value.first()
            val bytes = app.songs.exportPackage(song.id)
            val copy = app.songs.importPackage(bytes)
            org.junit.Assert.assertNotEquals(song.id, copy.id)
            org.junit.Assert.assertEquals(song.notes.size, copy.notes.size)
            org.junit.Assert.assertTrue(File(app.songs.dir(copy.id), "score.musicxml").exists())
            org.junit.Assert.assertTrue(File(app.songs.dir(copy.id), "hands.json").exists())
            app.songs.delete(copy.id)
        }
    }
}
