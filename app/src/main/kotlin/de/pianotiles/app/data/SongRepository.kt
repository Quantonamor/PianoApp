package de.pianotiles.app.data

import android.content.Context
import de.pianotiles.core.engrave.Engraver
import de.pianotiles.core.library.HandFile
import de.pianotiles.core.library.SongPackage
import de.pianotiles.core.model.DemoSongs
import de.pianotiles.core.model.Song
import de.pianotiles.core.model.SongJson
import de.pianotiles.core.model.SongSource
import de.pianotiles.core.model.SongStats
import de.pianotiles.core.model.SongUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The private library. Every song is one folder `files/library/<id>/` holding the parts of a [SongPackage]:
 * `score.musicxml` (notes), `tiles.json` (practice data), `hands.json` (fingering), `original.*` (imported
 * original: PDF, photos, MIDI, …), optionally `sheet.pdf` (sheet set from the notes) and `manifest.json`.
 *
 * Older versions stored `files/songs/<id>.json` + `files/pdfs/<id>.pdf`; these are moved over on first start.
 */
class SongRepository(private val context: Context) {
    private val libraryDir = File(context.filesDir, "library").apply { mkdirs() }
    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs

    suspend fun load() = withContext(Dispatchers.IO) {
        migrateOldLayout()
        val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("demos_installed_v1", false)) {
            DemoSongs.all().forEach { writeSong(it) }
            prefs.edit().putBoolean("demos_installed_v1", true).putBoolean("demos_installed_v2", true).apply()
        } else if (!prefs.getBoolean("demos_installed_v2", false)) {
            // pieces added in 0.3: installed once, without bringing back demos the user deleted
            val old = setOf("demo-scale", "demo-entchen", "demo-ode", "demo-elise")
            DemoSongs.all().filter { it.id !in old && !dir(it.id).exists() }.forEach { writeSong(it) }
            prefs.edit().putBoolean("demos_installed_v2", true).apply()
        }
        val all = readAll().map { song ->
            // songs without an original get their engraved sheet (also for libraries from older versions)
            val engraved = runCatching { withEngravedSheet(song) }.getOrDefault(song)
            if (engraved !== song) runCatching { writeSong(engraved) }
            engraved
        }
        // a song saved while the library was being read (e.g. an import right after start) wins over the disk copy
        _songs.update { cur -> cur + all.filter { a -> cur.none { it.id == a.id } } }
    }

    fun dir(id: String) = File(libraryDir, id)

    private fun readAll(): List<Song> = libraryDir.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { readSong(it) }

    private fun readSong(dir: File): Song? = runCatching {
        val tiles = File(dir, SongPackage.TILES)
        if (!tiles.exists()) return@runCatching SongPackage.fromFiles(filesOf(dir)).song
        var song = SongJson.decode(tiles.readText())
        File(dir, SongPackage.HANDS).takeIf { it.exists() }?.let { h ->
            runCatching { SongJson.json.decodeFromString(HandFile.serializer(), h.readText()) }.getOrNull()
                ?.let { song = SongPackage.applyHands(song, it) }
        }
        song
    }.getOrNull()

    private fun filesOf(dir: File): Map<String, ByteArray> =
        dir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }.orEmpty().associate { it.name to it.readBytes() }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /** Writes the note parts of the package (originals and sheet stay untouched). */
    private fun writeSong(song: Song) {
        val dir = dir(song.id).apply { mkdirs() }
        val files = SongPackage(song, originalNames(song.id).associateWith { ByteArray(0) }, sheetPdf = sheetFile(song.id).takeIf { it.exists() }?.let { ByteArray(0) })
            .toFiles()
        for (name in listOf(SongPackage.TILES, SongPackage.HANDS, SongPackage.MANIFEST)) writeAtomic(File(dir, name), files.getValue(name))
        // The MusicXML score is derived from the notes; a broken export must never lose the song.
        runCatching { writeAtomic(File(dir, SongPackage.SCORE), files.getValue(SongPackage.SCORE)) }
    }

    fun get(id: String): Song? = _songs.value.firstOrNull { it.id == id }

    /** Stores [song]; songs without an original also get their sheet engraved from the notes. Returns what was stored. */
    suspend fun save(song: Song): Song = withContext(Dispatchers.IO) {
        val s = runCatching { withEngravedSheet(song) }.getOrDefault(song)
        writeSong(s)
        _songs.update { cur -> cur.filter { it.id != s.id } + s }
        s
    }

    /**
     * Without an original (MIDI, MusicXML, ABC, demos, …) the sheet next to the falling notes is engraved from the
     * notes: `sheet.pdf` plus the measure positions in [Song.sheetMeasures]. Redone only when the notation changed
     * (`sheet.key` holds a fingerprint of everything that is printed).
     */
    private fun withEngravedSheet(song: Song): Song {
        if (originalPages(song.id).isNotEmpty()) return song
        val key = song.copy(
            stats = SongStats(), sheetMeasures = emptyList(), favorite = false, sections = emptyList(),
            flaggedMeasures = emptyList(), createdAt = 0, source = SongSource.MANUAL,
        ).hashCode().toString() + "/" + Engraver.VERSION
        val keyFile = File(dir(song.id), "sheet.key")
        if (sheetFile(song.id).exists() && keyFile.exists() && keyFile.readText() == key) {
            // unchanged notation: keep the measure positions (a caller's copy of the song may predate them)
            val known = get(song.id)?.sheetMeasures?.takeIf { it.isNotEmpty() } ?: song.sheetMeasures
            if (known.isNotEmpty()) return if (known == song.sheetMeasures) song else song.copy(sheetMeasures = known)
        }
        val sheet = Engraver.engrave(song)
        dir(song.id).mkdirs()
        writeAtomic(sheetFile(song.id), sheet.pdf)
        writeAtomic(keyFile, key.toByteArray())
        File(dir(song.id), "cover.jpg").delete()
        return song.copy(sheetMeasures = sheet.measures)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dir(id).deleteRecursively()
        _songs.update { cur -> cur.filter { it.id != id } }
    }

    /**
     * Names of the original files of a song (`original.pdf`, `original-01.jpg`, `original.mid`, …) and of the
     * prepared photo pages the recognition worked on (`scan-01.jpg`, …; measure positions refer to these).
     */
    fun originalNames(id: String): List<String> =
        dir(id).listFiles { f -> f.isFile && (f.name.startsWith("original") || f.name.startsWith("scan-")) && !f.name.endsWith(".tmp") }
            .orEmpty().map { it.name }.sorted()

    fun pdfFile(id: String) = File(dir(id), "original.pdf")
    fun sheetFile(id: String) = File(dir(id), SongPackage.SHEET)

    /** The original sheet music: the imported PDF or the prepared photo pages. Empty if there is no original. */
    fun originalPages(id: String): List<File> {
        val d = dir(id)
        pdfFile(id).takeIf { it.exists() }?.let { return listOf(it) }
        return originalNames(id).filter { it.startsWith("scan-") }.map { File(d, it) }
    }

    /**
     * Pages that [Song.sheetMeasures] refer to: the original if there is one, otherwise the sheet engraved from the
     * notes (`sheet.pdf`). Empty if neither exists.
     */
    fun sheetPages(id: String): List<File> =
        originalPages(id).ifEmpty { listOfNotNull(sheetFile(id).takeIf { it.exists() }) }

    suspend fun storeOriginal(id: String, name: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        writeAtomic(File(dir(id).apply { mkdirs() }, name), bytes)
    }

    suspend fun storePdf(id: String, bytes: ByteArray) = storeOriginal(id, "original.pdf", bytes)

    suspend fun storeSheet(id: String, bytes: ByteArray) = withContext(Dispatchers.IO) { writeAtomic(sheetFile(id).also { it.parentFile?.mkdirs() }, bytes) }

    /**
     * Small picture of the first original page for the library (`cover.jpg`, not part of the package).
     * Rendered once on demand; null if the song has no original sheet.
     */
    suspend fun cover(id: String): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        val f = File(dir(id), "cover.jpg")
        if (f.exists()) return@withContext android.graphics.BitmapFactory.decodeFile(f.path)
        val pages = sheetPages(id).ifEmpty { return@withContext null }
        val doc = SheetDocument.open(pages) ?: return@withContext null
        val bmp = doc.use { runCatching { it.render(0, 420) }.getOrNull() } ?: return@withContext null
        runCatching { f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) } }
        bmp
    }

    /** The complete package of a song (all parts). */
    suspend fun exportPackage(id: String): ByteArray = withContext(Dispatchers.IO) {
        val song = get(id) ?: readSong(dir(id)) ?: error("unknown song $id")
        val d = dir(id)
        SongPackage(
            song,
            originalNames(id).associateWith { File(d, it).readBytes() },
            sheetFile(id).takeIf { it.exists() }?.readBytes(),
        ).toZip()
    }

    /**
     * Imports a `.pianotiles` package. A song with the same id is kept unless [replace]; then the import gets
     * a new id. Returns the stored song.
     */
    suspend fun importPackage(bytes: ByteArray, replace: Boolean = false): Song = withContext(Dispatchers.IO) {
        val pkg = SongPackage.fromZip(bytes)
        val song = if (!replace && dir(pkg.song.id).exists()) pkg.song.copy(id = SongUtils.newId()) else pkg.song
        val d = dir(song.id)
        if (replace) d.deleteRecursively()
        d.mkdirs()
        for ((name, data) in pkg.originals) if (!name.contains('/')) writeAtomic(File(d, name), data)
        pkg.sheetPdf?.let { writeAtomic(sheetFile(song.id), it) }
        writeSong(song)
        _songs.update { cur -> cur.filter { it.id != song.id } + song }
        song
    }

    /** Writes all songs as a ZIP of packages (`<id>.pianotiles`). Returns the number of songs. */
    suspend fun backup(out: java.io.OutputStream): Int = withContext(Dispatchers.IO) {
        java.util.zip.ZipOutputStream(out).use { zip ->
            for (s in _songs.value) {
                zip.putNextEntry(java.util.zip.ZipEntry("${s.id}.${SongPackage.EXTENSION}"))
                zip.write(exportPackage(s.id))
                zip.closeEntry()
            }
        }
        _songs.value.size
    }

    /**
     * Restores a backup made by [backup] (packages) or by older versions (`songs/<id>.json` + `pdfs/<id>.pdf`);
     * existing songs with the same id are replaced. Returns the count.
     */
    suspend fun restore(input: java.io.InputStream): Int = withContext(Dispatchers.IO) {
        var count = 0
        val oldPdfs = HashMap<String, ByteArray>()
        val oldSongs = ArrayList<Song>()
        java.util.zip.ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name.substringAfterLast('/')
                if (e.isDirectory || name.contains("..") || name.isBlank()) continue
                val bytes = zip.readBytes()
                when {
                    name.endsWith(".${SongPackage.EXTENSION}") -> if (runCatching { importPackage(bytes, replace = true) }.isSuccess) count++
                    e.name.startsWith("songs/") && name.endsWith(".json") ->
                        runCatching { SongJson.decode(bytes.toString(Charsets.UTF_8)) }.getOrNull()?.let { oldSongs.add(it) }
                    e.name.startsWith("pdfs/") && name.endsWith(".pdf") -> oldPdfs[name.removeSuffix(".pdf")] = bytes
                }
            }
        }
        for (s in oldSongs) {
            dir(s.id).deleteRecursively()
            oldPdfs[s.id]?.let { writeAtomic(File(dir(s.id).apply { mkdirs() }, "original.pdf"), it) }
            writeSong(s)
            count++
        }
        _songs.value = readAll()
        count
    }

    /** Re-installs the built-in demo pieces (e.g. after they were deleted). */
    suspend fun restoreDemos() = withContext(Dispatchers.IO) {
        DemoSongs.all().forEach { writeSong(it) }
        _songs.value = readAll()
    }

    /** Moves `songs/<id>.json` + `pdfs/<id>.pdf` (versions ≤ 0.2) into package folders. */
    private fun migrateOldLayout() {
        val oldSongs = File(context.filesDir, "songs")
        val oldPdfs = File(context.filesDir, "pdfs")
        if (!oldSongs.isDirectory) return
        for (f in oldSongs.listFiles { f -> f.extension == "json" }.orEmpty()) {
            val song = runCatching { SongJson.decode(f.readText()) }.getOrNull() ?: continue
            val pdf = File(oldPdfs, "${song.id}.pdf")
            if (pdf.exists()) {
                dir(song.id).mkdirs()
                if (!pdf.renameTo(pdfFile(song.id))) { pdf.copyTo(pdfFile(song.id), overwrite = true); pdf.delete() }
            }
            writeSong(song)
            f.delete()
        }
        oldSongs.deleteRecursively()
        oldPdfs.deleteRecursively()
    }
}
