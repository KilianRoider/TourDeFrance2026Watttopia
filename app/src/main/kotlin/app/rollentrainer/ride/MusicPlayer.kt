package app.rollentrainer.ride

import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays the rider's own music from a folder chosen with the system file picker. No music ships with
 * the app (licensing), so everything comes from a tree URI the user granted. Tracks are shuffled once
 * and played in a loop; broken files are skipped, and when every file has failed in a row playback
 * stops and [exhausted] says so. All calls are expected on the main thread; files are opened on the
 * IO dispatcher (a cloud or network provider can take seconds), the MediaPlayer itself lives on the
 * main thread so its callbacks arrive there.
 */
class MusicPlayer(private val context: Context, private val scope: CoroutineScope) {
    /** A file from the rider's folder ([uri]) or one of the built-in tracks ([resName]). */
    data class Track(val uri: Uri?, val name: String, val resName: String? = null)

    private var tracks: List<Track> = emptyList()
    private var index = -1
    private var player: MediaPlayer? = null
    /** The player has finished preparing; start() before that would put it into the error state. */
    private var prepared = false
    /** A file is being opened in the background. */
    private var opening = false
    /** Bumped whenever the player is closed, so a file that finishes opening for an older request is dropped. */
    private var generation = 0
    private var volume = 0.7f
    private var ducked = false
    private var wantPlaying = false
    /** Files that failed in a row, so a folder full of unreadable files does not loop forever. Reset by a file that prepares. */
    private var failures = 0

    /** Tree URI the current playlist was loaded from, or null. */
    var folder: String? = null
        private set
    /** Whether the built-in tracks were part of the last [load]. */
    var builtInIncluded: Boolean = false
        private set

    private val _track = MutableStateFlow<String?>(null)
    /** Display name of the current track, without extension. */
    val track: StateFlow<String?> = _track.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _exhausted = MutableStateFlow(false)
    /** Every file of the playlist failed in a row and playback has stopped; cleared by [load]. */
    val exhausted: StateFlow<Boolean> = _exhausted.asStateFlow()

    val available: Boolean get() = tracks.isNotEmpty() && !_exhausted.value

    /**
     * Scans and shuffles the audio files of [treeUri] on the IO dispatcher and takes them as the new
     * playlist on the caller's thread. Cancelling the caller stops the scan and keeps the old playlist.
     * Returns the number found.
     */
    suspend fun load(treeUri: String, builtIn: List<BuiltInTrack> = emptyList()): Int {
        val uri = if (treeUri.isBlank()) null else runCatching { Uri.parse(treeUri) }.getOrNull()
        val own = if (uri == null) emptyList() else withContext(Dispatchers.IO) { scan(context, uri) { isActive } }
        val shipped = builtIn.mapNotNull { t ->
            val id = context.resources.getIdentifier(t.resName, "raw", context.packageName)
            if (id == 0) null else Track(null, t.title, t.resName)
        }
        closePlayer()
        tracks = (own + shipped).shuffled()
        index = -1
        failures = 0
        folder = treeUri
        builtInIncluded = builtIn.isNotEmpty()
        _exhausted.value = false
        _track.value = null
        _playing.value = false
        return tracks.size
    }

    fun play() {
        wantPlaying = true
        if (tracks.isEmpty() || _exhausted.value) return
        val p = player
        when {
            p != null && prepared -> {
                runCatching { if (!p.isPlaying) p.start() }
                _playing.value = true
            }
            // Still preparing or opening: the prepared listener starts it.
            p != null || opening -> Unit
            else -> open(if (index < 0) 0 else index)
        }
    }

    fun pause() {
        wantPlaying = false
        if (prepared) runCatching { player?.takeIf { it.isPlaying }?.pause() }
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

    /** Releases the player; the playlist stays, and [play] starts the current track again from its start. */
    fun stop() {
        wantPlaying = false
        closePlayer()
        _track.value = null
        _playing.value = false
    }

    fun release() {
        stop()
        tracks = emptyList()
        folder = null
    }

    /** 0..1; the duck factor is applied on top. */
    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        applyVolume()
    }

    /** Lowers the music while the coach speaks or another app asks to duck. */
    fun duck(on: Boolean) {
        if (ducked == on) return
        ducked = on
        applyVolume()
    }

    private fun applyVolume() {
        val v = volume * (if (ducked) DUCK_FACTOR else 1f)
        runCatching { player?.setVolume(v, v) }
    }

    /**
     * Opens track [first], or the next one that can be opened. The file descriptor comes from the
     * documents provider on the IO dispatcher; files that cannot be opened are skipped in a loop here,
     * files that fail later (unsupported codec) come back through the error listener.
     */
    private fun open(first: Int) {
        closePlayer()
        if (tracks.isEmpty()) return
        val gen = generation
        opening = true
        scope.launch {
            var i = first
            while (gen == generation) {
                val t = tracks.getOrNull(i) ?: break
                index = i
                _track.value = t.name
                val afd = withContext(Dispatchers.IO) {
                    runCatching {
                        if (t.uri != null) context.contentResolver.openAssetFileDescriptor(t.uri, "r")
                        else context.resources.openRawResourceFd(context.resources.getIdentifier(t.resName ?: "", "raw", context.packageName))
                    }.getOrNull()
                }
                if (gen != generation) {
                    // Closed, skipped or reloaded meanwhile: this file is no longer wanted.
                    runCatching { afd?.close() }
                    return@launch
                }
                val p = afd?.let { createPlayer(it) }
                if (p != null) {
                    player = p
                    opening = false
                    return@launch
                }
                if (!countFailure()) return@launch
                i = (i + 1) % tracks.size
            }
            if (gen == generation) opening = false
        }
    }

    /** A prepared-async player for [afd], or null if the file is refused right away. Closes [afd]. */
    private fun createPlayer(afd: AssetFileDescriptor): MediaPlayer? {
        // Created on the main thread, so all its callbacks run there too.
        val p = MediaPlayer()
        val ok = runCatching {
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            // The player keeps its own copy of the descriptor, so ours can be closed right after.
            p.setDataSource(afd)
            p.setOnPreparedListener { mp ->
                if (player !== mp) return@setOnPreparedListener
                prepared = true
                failures = 0
                applyVolume()
                if (wantPlaying) {
                    runCatching { mp.start() }
                    _playing.value = true
                }
            }
            p.setOnCompletionListener { mp ->
                if (player !== mp) return@setOnCompletionListener
                failures = 0
                next()
            }
            p.setOnErrorListener { mp, _, _ ->
                // Unreadable file: move on, unless every file failed.
                if (player === mp) skipBroken()
                true
            }
            p.prepareAsync()
        }.isSuccess
        runCatching { afd.close() }
        if (!ok) {
            runCatching { p.release() }
            return null
        }
        prepared = false
        return p
    }

    private fun skipBroken() {
        closePlayer()
        if (!countFailure()) return
        open((index + 1) % tracks.size)
    }

    /** Counts a failed file. Returns false, with playback stopped and [exhausted] set, once every file has failed in a row. */
    private fun countFailure(): Boolean {
        failures++
        if (failures < tracks.size) return true
        closePlayer()
        _track.value = null
        _playing.value = false
        _exhausted.value = true
        return false
    }

    private fun closePlayer() {
        generation++
        opening = false
        prepared = false
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
        fun scan(context: Context, treeUri: Uri, keepGoing: () -> Boolean = { true }): List<Track> {
            val root = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return emptyList()
            val out = mutableListOf<Track>()
            scanFolder(context, treeUri, root, out, depth = 0, keepGoing)
            return out.sortedBy { it.name.lowercase() }
        }

        /** [keepGoing] is asked between entries, so a cancelled scan of a large or slow tree stops early. */
        private fun scanFolder(context: Context, treeUri: Uri, documentId: String, out: MutableList<Track>, depth: Int, keepGoing: () -> Boolean) {
            if (!keepGoing()) return
            val children = runCatching { DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId) }.getOrNull() ?: return
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            val cursor = runCatching { context.contentResolver.query(children, projection, null, null, null) }.getOrNull() ?: return
            val folders = mutableListOf<String>()
            cursor.use { c ->
                while (keepGoing() && c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR -> folders += id
                        isAudio(mime, name) -> out += Track(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), name.substringBeforeLast('.'))
                    }
                }
            }
            if (depth < 1) folders.forEach { scanFolder(context, treeUri, it, out, depth + 1, keepGoing) }
        }

        private fun isAudio(mime: String, name: String): Boolean {
            if (mime.startsWith("audio/")) return true
            // Some providers report a generic type; the extension is good enough then.
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav")
        }

        /**
         * Keeps the read grant across app restarts; without it the folder would be unreadable tomorrow.
         * Returns false when the provider refused or the app is out of persisted grants.
         */
        fun persist(context: Context, uri: Uri): Boolean {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            return isPersisted(context, uri)
        }

        /** Whether the read grant for [uri] survives an app restart. Does IPC. */
        fun isPersisted(context: Context, uri: Uri): Boolean =
            runCatching { context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission } }.getOrDefault(false)

        /** Gives back the persisted grant of a folder that is no longer used, so grants do not pile up to the system limit. */
        fun releasePersisted(context: Context, treeUri: String) {
            if (treeUri.isBlank()) return
            val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
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
