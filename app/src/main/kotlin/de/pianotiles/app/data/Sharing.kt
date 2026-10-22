package de.pianotiles.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import de.pianotiles.app.i18n.tr
import de.pianotiles.core.io.MidiExporter
import de.pianotiles.core.model.Song
import java.io.File

object Sharing {
    /** Exports the song as MIDI file and opens the system share sheet. */
    fun shareMidi(context: Context, song: Song) {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val safe = song.title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifEmpty { "Stueck" }
        val file = File(dir, "$safe.mid")
        file.writeBytes(MidiExporter.export(song))
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/midi"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, tr("Als MIDI teilen")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
