package app.rollentrainer.ride

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plays the rider's own music from a folder chosen with the system file picker. No music ships with
 * the app (licensing), so everything comes from a tree URI the user granted. Tracks are shuffled once
 * and played in a loop; broken files are skipped. All calls are expected on the main thread, except
 * [scan] which does I/O.
 */
class MusicPlayer(private val context: Context) {
    data class Track(val uri: Uri, val name: String)

    private var tracks: List<Track> = emptyList()
    private var index = -1
    private var player: MediaPlayer? = null
    private var volume = 0.7f
    private var ducked = false
    private var wantPlaying = false
    /** Consecutive failures, so a folder full of unreadable files does not loop forever. */
    private var failures = 0

    private val _track = MutableStateFlow<String?>(null)
    /** Display name of the current track, without extension. */
    val track: StateFlow<String?> = _track.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    val available: Boolean get() = tracks.isNotEmpty()

    /** Loads and shuffles the audio files of [treeUri]; call from a background thread. Returns the number found. */
    fun load(treeUri: String): Int {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return 0
        val found = scan(context, uri).shuffled()
        tracks = found
        index = -1
        failures = 0
        return found.size
    }

    fun play() {
        wantPlaying = true
        if (tracks.isEmpty()) return
        val p = player
        if (p != null) {
            runCatching { if (!p.isPlaying) p.start() }
            _playing.value = true
        } else {
            open(if (index < 0) 0 else index)
        }
    }

    fun pause() {
        wantPlaying = false
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
        _playing.value = false
    }

    fun next() {
        if (tracks.isEmpty()) return
        open((index + 1) % tracks.size)
    }

    fun previous() {
        if (tracks.isEmpty()) return
        open((index - 1 + tracks.size) % tracks.size)
    }

    fun stop() {
        wantPlaying = false
        closePlayer()
        _track.value = null
        _playing.value = false
    }

    fun release() {
        stop()
        tracks = emptyList()
    }

    /** 0..1; the duck factor is applied on top. */
    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        applyVolume()
    }

    /** Lowers the music while the coach speaks. */
    fun duck(on: Boolean) {
        if (ducked == on) return
        ducked = on
        applyVolume()
    }

    private fun applyVolume() {
        val v = volume * (if (ducked) DUCK_FACTOR else 1f)
        runCatching { player?.setVolume(v, v) }
    }

    private fun open(i: Int) {
        closePlayer()
        val t = tracks.getOrNull(i) ?: return
        index = i
        val p = MediaPlayer()
        val ok = runCatching {
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            p.setDataSource(context, t.uri)
            p.setOnPreparedListener { mp ->
                if (player !== mp) return@setOnPreparedListener
                applyVolume()
                if (wantPlaying) {
                    runCatching { mp.start() }
                    _playing.value = true
                }
            }
            p.setOnCompletionListener { mp -> if (player === mp) next() }
            p.setOnErrorListener { mp, _, _ ->
                // Unreadable file: move on, unless every file failed.
                if (player === mp) skipBroken()
                true
            }
            p.prepareAsync()
        }.isSuccess
        if (!ok) {
            runCatching { p.release() }
            skipBroken()
            return
        }
        player = p
        failures = 0
        _track.value = t.name
    }

    private fun skipBroken() {
        failures++
        closePlayer()
        if (failures >= tracks.size) {
            _track.value = null
            _playing.value = false
            return
        }
        open((index + 1) % tracks.size)
    }

    private fun closePlayer() {
        val p = player ?: return
        player = null
        // Callbacks check `player === mp`, so a late event from this instance is ignored after release.
        runCatching { if (p.isPlaying) p.stop() }
        runCatching { p.release() }
    }

    companion object {
        const val DUCK_FACTOR = 0.25f

        /**
         * All audio documents in the tree, recursing one level into subfolders (an album folder per
         * artist is common; deeper trees are rare and would make scanning slow). Does I/O.
         */
        fun scan(context: Context, treeUri: Uri): List<Track> {
            val root = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return emptyList()
            val out = mutableListOf<Track>()
            scanFolder(context, treeUri, root, out, depth = 0)
            return out.sortedBy { it.name.lowercase() }
        }

        private fun scanFolder(context: Context, treeUri: Uri, documentId: String, out: MutableList<Track>, depth: Int) {
            val children = runCatching { DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId) }.getOrNull() ?: return
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            val cursor = runCatching { context.contentResolver.query(children, projection, null, null, null) }.getOrNull() ?: return
            val folders = mutableListOf<String>()
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR -> folders += id
                        isAudio(mime, name) -> out += Track(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), name.substringBeforeLast('.'))
                    }
                }
            }
            if (depth < 1) folders.forEach { scanFolder(context, treeUri, it, out, depth + 1) }
        }

        private fun isAudio(mime: String, name: String): Boolean {
            if (mime.startsWith("audio/")) return true
            // Some providers report a generic type; the extension is good enough then.
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav")
        }

        /** Keeps the read grant across app restarts; without it the folder would be unreadable tomorrow. */
        fun persist(context: Context, uri: Uri) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }

        /** Display name of the chosen folder, for the settings panel. Does I/O. */
        fun folderName(context: Context, treeUri: Uri): String? {
            val root = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
            val doc = runCatching { DocumentsContract.buildDocumentUriUsingTree(treeUri, root) }.getOrNull() ?: return null
            val cursor = runCatching {
                context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            }.getOrNull() ?: return null
            return cursor.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }
    }
}
