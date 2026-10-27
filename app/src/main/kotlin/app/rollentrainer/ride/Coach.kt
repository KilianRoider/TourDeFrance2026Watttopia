package app.rollentrainer.ride

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

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
    }

    private fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        val lang = runCatching { t.setLanguage(Locale.GERMANY) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) return
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
}
