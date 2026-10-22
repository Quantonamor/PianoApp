package de.pianotiles.core.library

import de.pianotiles.core.i18n.CoreText.t
import de.pianotiles.core.io.ImportException
import de.pianotiles.core.io.MusicXmlExporter
import de.pianotiles.core.io.MusicXmlImporter
import de.pianotiles.core.model.FingeringSource
import de.pianotiles.core.model.Hand
import de.pianotiles.core.model.Note
import de.pianotiles.core.model.Song
import de.pianotiles.core.model.SongJson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/** One part of a package: file name inside the package + its format (MIME type). */
@Serializable
data class PackagePart(val file: String, val format: String)

/** `manifest.json`: names every part, so each one can be swapped for another format later. */
@Serializable
data class PackageManifest(
    val format: String = SongPackage.FORMAT,
    val version: Int = SongPackage.VERSION,
    val id: String,
    val title: String,
    val composer: String = "",
    val score: PackagePart? = null,
    val sheet: PackagePart? = null,
    val originals: List<PackagePart> = emptyList(),
    val tiles: PackagePart? = null,
    val hands: PackagePart? = null,
    val createdWith: String = "PianoTiles",
)

/** One entry of the hand file: which finger plays the note (pitch, start in beats, hand). */
@Serializable
data class HandEntry(val pitch: Int, val start: Double, val hand: Hand, val finger: Int)

/** `hands.json`: the fingering of a song, kept apart from the notes (no standard format exists for this). */
@Serializable
data class HandFile(
    val format: String = "pianotiles-hands",
    val version: Int = 1,
    val source: FingeringSource = FingeringSource.NONE,
    val notes: List<HandEntry> = emptyList(),
)

/**
 * A song split into its parts ("getrennte Datenhaltung"):
 *
 * | part | file | format |
 * |---|---|---|
 * | notes ("Noten-Datei") | `score.musicxml` | MusicXML 4.0 |
 * | sheet rendered from the notes | `sheet.pdf` | PDF |
 * | original import | `original.pdf`, `original-01.jpg`, `original.mid`, … | as imported |
 * | tiles (everything the practice view needs) | `tiles.json` | PianoTiles JSON |
 * | hands (fingering) | `hands.json` | PianoTiles JSON |
 *
 * In the app each song is one folder with these files; for sharing they are zipped into a `.pianotiles` file.
 */
class SongPackage(
    val song: Song,
    /** Original files: name inside the package → bytes. */
    val originals: Map<String, ByteArray> = emptyMap(),
    val sheetPdf: ByteArray? = null,
) {
    companion object {
        const val FORMAT = "pianotiles"
        const val VERSION = 1
        const val EXTENSION = "pianotiles"
        const val MIME = "application/vnd.pianotiles+zip"
        const val MANIFEST = "manifest.json"
        const val SCORE = "score.musicxml"
        const val SHEET = "sheet.pdf"
        const val TILES = "tiles.json"
        const val HANDS = "hands.json"

        private val json get() = SongJson.json

        fun isPackage(bytes: ByteArray): Boolean =
            MusicXmlImporter.isZip(bytes) && runCatching { unzip(bytes).containsKey(MANIFEST) }.getOrDefault(false)

        /** Reads a package from its files (a folder or an unzipped `.pianotiles`). */
        fun fromFiles(files: Map<String, ByteArray>): SongPackage {
            val manifest = files[MANIFEST]?.let { runCatching { json.decodeFromString(PackageManifest.serializer(), it.decodeToString()) }.getOrNull() }
            if (manifest != null && manifest.format != FORMAT) throw ImportException(t("Kein PianoTiles-Paket", "Not a PianoTiles package"))
            val tilesName = manifest?.tiles?.file ?: TILES
            val scoreName = manifest?.score?.file ?: SCORE
            var song = files[tilesName]?.let { SongJson.decode(it.decodeToString()) }
                ?: files[scoreName]?.let { MusicXmlImporter.import(it, manifest?.title ?: "") }
                    ?.let { s -> if (manifest != null) s.copy(id = manifest.id, title = manifest.title, composer = manifest.composer) else s }
                ?: throw ImportException(t("Das Paket enthält keine Noten", "The package contains no notes"))
            files[manifest?.hands?.file ?: HANDS]?.let { bytes ->
                runCatching { json.decodeFromString(HandFile.serializer(), bytes.decodeToString()) }.getOrNull()?.let { song = applyHands(song, it) }
            }
            val originalNames = manifest?.originals?.map { it.file } ?: files.keys.filter { it.startsWith("original") }
            return SongPackage(
                song,
                originalNames.mapNotNull { n -> files[n]?.let { n to it } }.toMap(),
                files[manifest?.sheet?.file ?: SHEET],
            )
        }

        fun fromZip(bytes: ByteArray): SongPackage = fromFiles(unzip(bytes))

        /** Fingering → hand file. */
        fun handFile(song: Song) = HandFile(
            source = song.fingeringSource,
            notes = song.notes.filter { it.finger in 1..5 }.map { HandEntry(it.pitch, it.start, it.hand, it.finger) },
        )

        /** Puts the fingering of [hands] onto the matching notes (same pitch, hand and start). */
        fun applyHands(song: Song, hands: HandFile): Song {
            if (hands.notes.isEmpty()) return song
            val map = hands.notes.associate { Triple(it.pitch, key(it.start), it.hand) to it.finger }
            return song.copy(
                notes = song.notes.map { n -> map[Triple(n.pitch, key(n.start), n.hand)]?.let { n.copy(finger = it) } ?: n },
                fingeringSource = hands.source,
            )
        }

        private fun key(start: Double) = (start * 48).roundToInt()

        fun formatOf(name: String): String = when (name.substringAfterLast('.').lowercase()) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "mid", "midi", "kar" -> "audio/midi"
            "musicxml", "xml" -> "application/vnd.recordare.musicxml+xml"
            "mxl" -> "application/vnd.recordare.musicxml"
            "abc" -> "text/vnd.abc"
            "mscz" -> "application/x-musescore"
            "mscx" -> "application/x-musescore+xml"
            "mei" -> "application/mei+xml"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }

        fun zip(files: Map<String, ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                for ((name, bytes) in files) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            return out.toByteArray()
        }

        /** Unzips (flat names only, no paths outside the package; at most 200 MB). */
        fun unzip(bytes: ByteArray): Map<String, ByteArray> {
            val out = LinkedHashMap<String, ByteArray>()
            var total = 0L
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory || e.name.contains("..") || e.name.startsWith("/")) continue
                    val data = zip.readBytes()
                    total += data.size
                    if (total > 200L * 1024 * 1024) throw ImportException(t("Paket ist zu groß", "Package is too large"))
                    out[e.name] = data
                }
            }
            return out
        }
    }

    val manifest: PackageManifest
        get() = PackageManifest(
            id = song.id, title = song.title, composer = song.composer,
            score = PackagePart(SCORE, formatOf(SCORE)),
            sheet = sheetPdf?.let { PackagePart(SHEET, formatOf(SHEET)) },
            originals = originals.keys.map { PackagePart(it, formatOf(it)) },
            tiles = PackagePart(TILES, "application/vnd.pianotiles.tiles+json"),
            hands = PackagePart(HANDS, "application/vnd.pianotiles.hands+json"),
        )

    /** All files of the package (for a folder or a ZIP). */
    fun toFiles(): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        files[MANIFEST] = json.encodeToString(PackageManifest.serializer(), manifest).encodeToByteArray()
        files[SCORE] = MusicXmlExporter.export(song).encodeToByteArray()
        files[TILES] = SongJson.encode(song.copy(notes = song.notes.withoutFingers())).encodeToByteArray()
        files[HANDS] = json.encodeToString(HandFile.serializer(), handFile(song)).encodeToByteArray()
        sheetPdf?.let { files[SHEET] = it }
        files.putAll(originals)
        return files
    }

    fun toZip(): ByteArray = zip(toFiles())
}

/** The tile file keeps no fingering – that lives in the hand file. */
internal fun List<Note>.withoutFingers() = map { if (it.finger != 0) it.copy(finger = 0) else it }
