package app.rollentrainer.ride

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import app.rollentrainer.core.coach.CoachPriority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaks the coach's lines with the system's text-to-speech in German. If no German voice is
 * installed the coach stays silent; the lines are still shown on screen. [speaking] tells the music
 * when to duck.
 */
class Coach(context: Context) {
    /** Outcome of binding the speech engine, so the settings can say why the coach is silent. */
    enum class Status { STARTING, READY, NO_ENGINE, NO_GERMAN }

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _status = MutableStateFlow(Status.STARTING)
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile private var ready = false
    private var tts: TextToSpeech? = null
    /** Utterances handed to the engine and not yet finished; speaking ends when it drops to zero. */
    private val pending = AtomicInteger(0)
    private var counter = 0

    init {
        tts = runCatching {
            // The init listener runs on the main thread once the engine is bound; until then lines are dropped.
            TextToSpeech(context.applicationContext) { status -> onInit(status) }
        }.getOrNull()
        if (tts == null) _status.value = Status.NO_ENGINE
    }

    private fun onInit(status: Int) {
        // Checked before `tts`: without any engine the constructor reports the error synchronously, before it returns.
        if (status != TextToSpeech.SUCCESS) {
            // No engine installed or enabled, or none visible to the app.
            Log.w(TAG, "Text-to-speech init failed: $status")
            _status.value = Status.NO_ENGINE
            return
        }
        val t = tts ?: return
        val lang = runCatching { t.setLanguage(Locale.GERMANY) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "No German voice: $lang")
            _status.value = Status.NO_GERMAN
            return
        }
        runCatching {
            t.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            t.setSpeechRate(1.05f)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { _speaking.value = true }
                override fun onDone(utteranceId: String?) = finished()
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finished()
                override fun onError(utteranceId: String?, errorCode: Int) = finished()
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finished()
            })
        }
        ready = true
        _status.value = Status.READY
    }

    private fun finished() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            _speaking.value = false
        }
    }

    val available: Boolean get() = ready

    /**
     * Queues a line. High lines interrupt whatever is being said (a segment start must not wait for
     * a motivational phrase), normal lines queue up, low lines are dropped while the coach is busy.
     */
    fun say(text: String, priority: CoachPriority = CoachPriority.NORMAL) {
        val t = tts ?: return
        if (!ready || text.isBlank()) return
        val busy = pending.get() > 0
        val mode = when (priority) {
            CoachPriority.HIGH -> TextToSpeech.QUEUE_FLUSH
            CoachPriority.NORMAL -> TextToSpeech.QUEUE_ADD
            CoachPriority.LOW -> if (busy) return else TextToSpeech.QUEUE_ADD
        }
        if (mode == TextToSpeech.QUEUE_FLUSH) pending.set(0)
        val id = "coach-${counter++}"
        pending.incrementAndGet()
        val result = runCatching { t.speak(text, mode, Bundle(), id) }.getOrDefault(TextToSpeech.ERROR)
        if (result != TextToSpeech.SUCCESS) finished()
    }

    fun stop() {
        runCatching { tts?.stop() }
        pending.set(0)
        _speaking.value = false
    }

    fun release() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    private companion object {
        const val TAG = "Coach"
    }
}
