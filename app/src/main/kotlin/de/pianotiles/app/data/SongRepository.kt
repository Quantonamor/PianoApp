package de.pianotiles.app.data

import android.content.Context
import de.pianotiles.core.model.DemoSongs
import de.pianotiles.core.model.Song
import de.pianotiles.core.model.SongJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The private library: one JSON file per song in the app's internal storage
 * (`files/songs/<id>.json`). Imported PDFs are kept in `files/pdfs/<id>.pdf` so the
 * original sheet can be shown again.
 */
class SongRepository(private val context: Context) {
    private val songsDir = File(context.filesDir, "songs").apply { mkdirs() }
    private val pdfDir = File(context.filesDir, "pdfs").apply { mkdirs() }
    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs

    suspend fun load() = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("demos_installed_v1", false)) {
            DemoSongs.all().forEach { writeFile(it) }
            prefs.edit().putBoolean("demos_installed_v1", true).apply()
        }
        _songs.value = readAll()
    }

    private fun readAll(): List<Song> = songsDir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull {
        runCatching { SongJson.decode(it.readText()) }.getOrNull()
    }

    private fun writeFile(song: Song) {
        val tmp = File(songsDir, "${song.id}.json.tmp")
        tmp.writeText(SongJson.encode(song))
        tmp.renameTo(File(songsDir, "${song.id}.json"))
    }

    fun get(id: String): Song? = _songs.value.firstOrNull { it.id == id }

    suspend fun save(song: Song) = withContext(Dispatchers.IO) {
        writeFile(song)
        _songs.value = _songs.value.filter { it.id != song.id } + song
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(songsDir, "$id.json").delete()
        File(pdfDir, "$id.pdf").delete()
        _songs.value = _songs.value.filter { it.id != id }
    }

    fun pdfFile(id: String) = File(pdfDir, "$id.pdf")

    suspend fun storePdf(id: String, bytes: ByteArray) = withContext(Dispatchers.IO) { pdfFile(id).writeBytes(bytes) }

    /** Re-installs the built-in demo pieces (e.g. after they were deleted). */
    suspend fun restoreDemos() = withContext(Dispatchers.IO) {
        DemoSongs.all().forEach { writeFile(it) }
        _songs.value = readAll()
    }
}
