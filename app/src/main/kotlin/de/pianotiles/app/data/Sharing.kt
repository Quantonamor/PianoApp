package de.pianotiles.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import de.pianotiles.app.i18n.tr
import de.pianotiles.core.io.MidiExporter
import de.pianotiles.core.io.MusicXmlExporter
import de.pianotiles.core.library.SongPackage
import de.pianotiles.core.model.Song
import java.io.File

object Sharing {
    fun safeName(title: String) = title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifEmpty { "Stueck" }

    private fun share(context: Context, name: String, mime: String, bytes: ByteArray, chooserTitle: String) {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, name)
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Exports the song as MIDI file and opens the system share sheet. */
    fun shareMidi(context: Context, song: Song) =
        share(context, "${safeName(song.title)}.mid", "audio/midi", MidiExporter.export(song), tr("Als MIDI teilen"))

    /** The notes as MusicXML (opens in MuseScore, Finale, Sibelius, Dorico …). */
    fun shareMusicXml(context: Context, song: Song) =
        share(context, "${safeName(song.title)}.musicxml", "application/vnd.recordare.musicxml+xml", MusicXmlExporter.export(song).toByteArray(), tr("Als MusicXML teilen"))

    /** The complete song package (notes, sheet, original, tiles, hands) for other PianoTiles users. */
    suspend fun sharePackage(context: Context, repo: SongRepository, song: Song) =
        share(context, "${safeName(song.title)}.${SongPackage.EXTENSION}", "application/octet-stream", repo.exportPackage(song.id), tr("Stück teilen"))

    /** Several songs as one ZIP of packages (can be opened like a backup). */
    suspend fun shareCollection(context: Context, repo: SongRepository, songs: List<Song>) {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            for (s in songs) {
                zip.putNextEntry(java.util.zip.ZipEntry("${safeName(s.title)}-${s.id.take(8)}.${SongPackage.EXTENSION}"))
                zip.write(repo.exportPackage(s.id))
                zip.closeEntry()
            }
        }
        share(context, tr("PianoTiles-Stücke") + ".zip", "application/zip", out.toByteArray(), tr("Stücke teilen"))
    }
}
