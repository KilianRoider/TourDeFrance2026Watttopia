package app.rollentrainer.ride

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Short tones for the ride, switchable per kind. ToneGenerator is tiny and needs no assets;
 * the volume is fixed at creation, so the generator is rebuilt when the setting changes.
 */
class Sounds {
    private var tone: ToneGenerator? = null
    private var volume = -1
    /** Until when a cue tone is playing; metronome clicks are skipped meanwhile so they don't cut it off. */
    private var cueUntil = 0L

    fun prepare(volume: Int) {
        val v = volume.coerceIn(0, 100)
        if (v == this.volume && tone != null) return
        tone?.release()
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, v) }.getOrNull()
        this.volume = v
    }

    /** One short beep of the countdown before a new segment. */
    fun countdown() = play(ToneGenerator.TONE_PROP_BEEP, 120)

    /** The new segment has begun; [harder] gets a brighter double tone. */
    fun segment(harder: Boolean) = play(if (harder) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_ACK, 350)

    /** Workout finished. */
    fun finished() = play(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600)

    /** Cadence drifts away from the target. */
    fun cadenceHint() = play(ToneGenerator.TONE_PROP_NACK, 200)

    /** Metronome click; skipped while a cue tone plays. */
    fun click() {
        if (System.currentTimeMillis() < cueUntil) return
        tone?.startTone(ToneGenerator.TONE_DTMF_1, 25)
    }

    /** ERG released because the rider bogged down. */
    fun released() = play(ToneGenerator.TONE_SUP_ERROR, 250)

    private fun play(kind: Int, ms: Int) {
        cueUntil = System.currentTimeMillis() + ms + 50
        tone?.startTone(kind, ms)
    }

    fun release() {
        tone?.release()
        tone = null
        volume = -1
    }
}
