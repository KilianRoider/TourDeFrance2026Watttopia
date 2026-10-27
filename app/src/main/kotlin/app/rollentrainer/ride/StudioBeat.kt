package app.rollentrainer.ride

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * A synthesised four-on-the-floor beat for the spinning view, so the class has a pulse even without
 * a music folder (no music can be bundled). Everything is rendered on the fly into a streaming
 * AudioTrack: kick on every beat, hats on the off-beats, a clap on two and four, a filtered bass
 * line over a four-chord progression and a quiet pad. The tempo follows the target cadence and
 * harder efforts add 16th-note hats and a lead.
 *
 * Audio is rendered one 16th note at a time, so a tempo change is heard within a beat. The maths is
 * a few multiplies per sample, which costs well under a percent of one core.
 */
class StudioBeat {
    @Volatile private var bpm = 120
    @Volatile private var volume = 0.6f
    @Volatile private var intensity = 0.4f
    @Volatile private var ducked = false
    @Volatile private var stopping = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    /** The render coroutine; null once it has retired, so the next [start] builds a new one. Guarded by [lock]. */
    private var job: Job? = null

    val running: Boolean get() = synchronized(lock) { job != null }

    fun setBpm(value: Int) { bpm = value.coerceIn(60, 180) }
    fun setVolume(value: Float) { volume = value.coerceIn(0f, 1f) }
    /** 0 = recovery, 1 = sprint: adds 16th hats and a lead on top of the groove. */
    fun setIntensity(value: Float) { intensity = value.coerceIn(0f, 1f) }
    fun duck(on: Boolean) { ducked = on }

    fun start() {
        synchronized(lock) {
            stopping = false
            if (job != null) return
            val j = scope.launch {
                try {
                    render()
                } finally {
                    // Covers a failed track build or a write error; a retired loop has cleared it already.
                    val self = coroutineContext[Job]
                    synchronized(lock) { if (job === self) job = null }
                }
            }
            job = j
        }
    }

    /**
     * Fades out, lets the fade play out and pauses the track; a hard cut would pop. [start] within a
     * few seconds resumes the same track, after that the track is released and [start] builds a new one.
     */
    fun stop() {
        stopping = true
    }

    fun release() {
        synchronized(lock) {
            stopping = true
            job?.cancel()
            job = null
        }
    }

    private suspend fun CoroutineScope.render() {
        val minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // Two of the longest chunks (60 bpm) fit, so writes never starve the hardware at slow tempos.
        val maxChunk = SAMPLE_RATE * 60 / 60 / 4
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes(max(minBytes, maxChunk * 2 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return
        val synth = Synth()
        val buffer = ShortArray(maxChunk)
        val mix = FloatArray(maxChunk)
        var gain = 0f
        runCatching { track.play() }
        try {
            while (isActive) {
                if (stopping && gain <= 0f) {
                    // The fade and up to a buffer of audio before it are still queued in the track; a
                    // pause()+flush() now would drop them and cut the beat. Push them out with silence.
                    if (!drain(track, buffer)) continue
                    runCatching { track.pause() }
                    runCatching { track.flush() }
                    // Park briefly, so a pause/resume within a few seconds does not rebuild the audio path.
                    var parkedMs = 0L
                    while (isActive && stopping && parkedMs < PARK_MS) {
                        delay(50)
                        parkedMs += 50
                    }
                    if (!isActive) break
                    // Still stopped: retire, releasing the track. The check and clearing [job] happen under the
                    // lock, so a start() racing with this either resumes this loop or launches a new one.
                    val retire = synchronized(lock) {
                        if (stopping) {
                            if (job === coroutineContext[Job]) job = null
                            true
                        } else {
                            false
                        }
                    }
                    if (retire) break
                    runCatching { track.play() }
                    continue
                }
                val chunk = (SAMPLE_RATE * 60 / bpm / 4).coerceIn(1, maxChunk)
                val target = if (stopping) 0f else volume * (if (ducked) DUCK_FACTOR else 1f)
                synth.step(intensity, mix, chunk)
                // Gain ramps over 40 ms at most, so volume changes and the fade-out are click-free.
                val rate = 1f / (SAMPLE_RATE * 0.04f)
                for (i in 0 until chunk) {
                    if (gain < target) gain = minOf(target, gain + rate) else if (gain > target) gain = maxOf(target, gain - rate)
                    val v = mix[i] * gain
                    // Soft clip: the voices sum above 1.0 on strong beats.
                    val c = v / (1f + abs(v) * 0.5f)
                    buffer[i] = (c.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
                val written = runCatching { track.write(buffer, 0, chunk) }.getOrDefault(-1)
                if (written < 0) break
            }
        } finally {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
    }

    /**
     * Writes one track buffer of silence, so everything queued before it has reached the speaker when
     * this returns. Returns false when [start] was called meanwhile (the loop then fades back in).
     */
    private fun CoroutineScope.drain(track: AudioTrack, buffer: ShortArray): Boolean {
        buffer.fill(0)
        var left = runCatching { track.bufferSizeInFrames }.getOrDefault(buffer.size * 2).coerceAtLeast(buffer.size)
        while (left > 0) {
            if (!stopping || !isActive) return false
            val n = minOf(left, buffer.size)
            val written = runCatching { track.write(buffer, 0, n) }.getOrDefault(-1)
            if (written <= 0) break
            left -= written
        }
        return stopping
    }

    /**
     * All voices with their phases, so notes continue seamlessly across chunk boundaries. Per 16th
     * note the sequencer triggers drums and bass; per sample the voices are summed.
     */
    private class Synth {
        private var step = 0          // 16th within the bar, 0..15
        private var bar = 0           // chord index
        private var noise = 0x2545F491

        // Drum voices: samples since trigger, or -1 when silent.
        private var kickT = -1
        private var kickPhase = 0.0
        private var clapT = -1
        private var hatT = -1
        private var hatGain = 0f

        // Bass: saw through a one-pole low-pass with an envelope per note.
        private var bassPhase = 0.0
        private var bassFreq = 55.0
        private var bassEnv = 0f
        private var bassGate = false
        private var bassLp = 0f

        // Pad: three sines on the chord, an octave up, slowly breathing.
        private val padPhase = DoubleArray(3)
        private var padLfo = 0.0

        // Lead: square arpeggio in hard efforts.
        private var leadPhase = 0.0
        private var leadFreq = 220.0
        private var leadEnv = 0f
        private var leadLp = 0f
        private var leadOn = false

        fun step(intensity: Float, out: FloatArray, n: Int) {
            trigger(intensity)
            val chord = CHORDS[bar % CHORDS.size]
            val root = ROOTS[bar % ROOTS.size]
            val bassCut = 1f - exp(-2f * PI.toFloat() * (250f + 1800f * intensity) / SAMPLE_RATE)
            val leadCut = 1f - exp(-2f * PI.toFloat() * 2500f / SAMPLE_RATE)
            val hatLevel = 0.18f + 0.1f * intensity
            val padLevel = 0.05f + 0.03f * (1f - intensity)
            val leadLevel = ((intensity - 0.7f) / 0.3f).coerceIn(0f, 1f) * 0.14f
            for (i in 0 until n) {
                var v = 0f
                // Kick: sine sweeping 160 -> 45 Hz with an exponential decay over ~120 ms.
                if (kickT >= 0) {
                    val t = kickT / SAMPLE_RATE.toFloat()
                    val f = 45.0 + 115.0 * exp(-t / 0.03)
                    kickPhase += 2 * PI * f / SAMPLE_RATE
                    v += (sin(kickPhase) * exp(-t * 22.0)).toFloat() * 0.9f
                    if (++kickT > KICK_LEN) kickT = -1
                }
                // Clap: noise burst plus a 180 Hz body, 80 ms.
                if (clapT >= 0) {
                    val t = clapT / SAMPLE_RATE.toFloat()
                    val env = exp(-t * 40f)
                    v += (white() * 0.45f + sin(2 * PI * 180.0 * t).toFloat() * 0.25f) * env
                    if (++clapT > CLAP_LEN) clapT = -1
                }
                // Closed hat: 25 ms of bright noise.
                if (hatT >= 0) {
                    val t = hatT / SAMPLE_RATE.toFloat()
                    val w = white()
                    // Crude high-pass: difference of two noise samples.
                    v += (w - white()) * 0.5f * exp(-t * 160f) * hatLevel * hatGain
                    if (++hatT > HAT_LEN) hatT = -1
                }
                // Bass: a saw wave, gated and filtered; the envelope has a 4 ms attack so the gate never clicks.
                bassEnv += if (bassGate) (1f - bassEnv) * 0.006f else -bassEnv * 0.0015f
                bassPhase += bassFreq / SAMPLE_RATE
                if (bassPhase >= 1.0) bassPhase -= 1.0
                val saw = (2.0 * bassPhase - 1.0).toFloat()
                bassLp += (saw - bassLp) * bassCut
                v += bassLp * bassEnv * 0.32f
                // Pad: root, third and fifth two octaves up, with a slow tremolo.
                padLfo += 2 * PI * 0.25 / SAMPLE_RATE
                val trem = (0.75 + 0.25 * sin(padLfo)).toFloat()
                for (k in 0 until 3) {
                    padPhase[k] += 2 * PI * root * 4.0 * SEMITONE.pow(chord[k]) / SAMPLE_RATE
                    v += sin(padPhase[k]).toFloat() * padLevel * trem
                }
                // Lead: square arpeggio, only audible in hard efforts.
                if (leadLevel > 0f) {
                    leadEnv += if (leadOn) (1f - leadEnv) * 0.01f else -leadEnv * 0.004f
                    leadPhase += leadFreq / SAMPLE_RATE
                    if (leadPhase >= 1.0) leadPhase -= 1.0
                    val sq = if (leadPhase < 0.5) 1f else -1f
                    leadLp += (sq - leadLp) * leadCut
                    v += leadLp * leadEnv * leadLevel
                }
                out[i] = v
            }
            // Phases of the pad grow without bound otherwise; a wrap keeps the sine precise.
            for (k in 0 until 3) if (padPhase[k] > 1e6) padPhase[k] -= 1e6
            if (kickPhase > 1e6) kickPhase -= 1e6
            if (++step == 16) { step = 0; bar++ }
        }

        /** Sequencer for the 16th note that starts now. */
        private fun trigger(intensity: Float) {
            val chord = CHORDS[bar % CHORDS.size]
            val root = ROOTS[bar % ROOTS.size]
            if (step % 4 == 0) { kickT = 0; kickPhase = 0.0 }
            if (step == 4 || step == 12) clapT = 0
            when {
                step % 4 == 2 -> { hatT = 0; hatGain = 1f }
                step % 4 != 0 && intensity >= 0.55f -> { hatT = 0; hatGain = 0.5f }
            }
            // Bass on every 8th; the off-beat 8ths jump an octave when it gets hard.
            if (step % 2 == 0) {
                val octave = if (step % 4 == 2 && intensity >= 0.45f) 2.0 else 1.0
                bassFreq = root * octave
                bassPhase = 0.0
                bassGate = true
            } else {
                bassGate = false
            }
            // Lead: arpeggio over the chord, 16th notes, gated on and off alternately for a stabbing feel.
            leadOn = step % 2 == 0
            if (leadOn) {
                val n = (step / 2) % 4
                val semis = if (n < 3) chord[n] else 12
                leadFreq = root * 8.0 * SEMITONE.pow(semis)
            }
        }

        /** Xorshift white noise in -1..1; good enough for drums and far cheaper than Random. */
        private fun white(): Float {
            var x = noise
            x = x xor (x shl 13)
            x = x xor (x ushr 17)
            x = x xor (x shl 5)
            noise = x
            return (x ushr 8) / 8388608f - 1f
        }

        companion object {
            const val KICK_LEN = 6615   // 150 ms
            const val CLAP_LEN = 3528   // 80 ms
            const val HAT_LEN = 1102    // 25 ms
            val SEMITONE = 2.0.pow(1.0 / 12)
            /** A minor, F, C, G: the progression half of all dance music is built on. */
            val ROOTS = doubleArrayOf(55.0, 43.654, 65.406, 48.999)
            val CHORDS = arrayOf(intArrayOf(0, 3, 7), intArrayOf(0, 4, 7), intArrayOf(0, 4, 7), intArrayOf(0, 4, 7))
        }
    }

    companion object {
        const val SAMPLE_RATE = 44_100
        const val DUCK_FACTOR = 0.3f
        /** How long a stopped beat keeps its track before releasing it. */
        const val PARK_MS = 4_000L
    }
}
