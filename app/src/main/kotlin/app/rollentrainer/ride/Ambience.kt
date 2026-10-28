package app.rollentrainer.ride

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** What the tyres roll on; cobbles (Paris-Roubaix sectors, the Champs-Élysées) rumble instead of hiss. */
enum class RoadSurface { ASPHALT, COBBLES }

/**
 * The sound of a route ride: air rushing past, tyres and freewheel, spectators, the race around the
 * rider. All of it was synthesised for this app (res/raw/amb_*.ogg) and is mixed live into one
 * streaming AudioTrack, so the wind can be filtered by speed, the freewheel ticks at the real wheel
 * speed and crowd shouts, whistles, horns and cowbells come at irregular times, never as an audible
 * loop.
 *
 * Feed it with [update] (any rate; the mixer smooths everything, once a second is enough) and call
 * [triggerPass] when the rider passes a group of spectators. All public calls are cheap and may come
 * from any thread.
 *
 * Audio focus: none is requested. The ambience is a bed under whatever else plays: the ride's tones
 * (STREAM_MUSIC), the coach, or the rider's own music app, which a focus request would stop. During
 * a phone call it falls silent by itself, and when headphones or a Bluetooth speaker go away it
 * mutes until the next [start], the way the spinning music switches off.
 */
class Ambience(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)
    /**
     * The current ride's mixer. [release] replaces it with a fresh one, so the next ride starts from silence
     * with none of this one's smoothed state, voices or clips; the coroutines still finishing keep the old one.
     */
    @Volatile private var mixer = AmbienceMixer(SAMPLE_RATE, System.nanoTime())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    /** The render coroutine; null once it has retired. Guarded by [lock]. */
    private var renderJob: Job? = null
    /** Decodes clips on demand; guarded by [lock]. */
    private var loadJob: Job? = null
    /** Set by [start]: the loader forgets the clips it could not decode (a codec error may have been passing). */
    @Volatile private var retryFailed = false
    @Volatile private var stopping = true
    @Volatile private var noisyMuted = false
    private var noisyRegistered = false

    /** Headphones unplugged or a Bluetooth speaker gone: the crowd must not carry on through the tablet speaker. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) noisyMuted = true
        }
    }

    /**
     * Starts (or resumes) the ambience with a fade-in; also lifts a mute from disconnected headphones.
     * The first start decodes the clips it needs in the background, so the wind and the tyres come
     * within a fraction of a second and the crowd joins as its clips arrive.
     */
    fun start() {
        noisyMuted = false
        registerNoisy()
        synchronized(lock) {
            stopping = false
            retryFailed = true
            // Render and loader work on the mixer they were launched for; release() swaps it for the next ride.
            val m = mixer
            if (loadJob?.isActive != true) loadJob = scope.launch(Dispatchers.IO) { loadLoop(m) }
            if (renderJob != null) return
            renderJob = scope.launch {
                try {
                    renderLoop(m)
                } finally {
                    val self = coroutineContext[Job]
                    synchronized(lock) { if (renderJob === self) renderJob = null }
                }
            }
        }
    }

    /** Fades out and releases the audio track; the decoded clips stay for a quick [start]. */
    fun stop() {
        stopping = true
        unregisterNoisy()
    }

    /**
     * Stops at once and frees the decoded clips (some tens of MB). The instance can be started again; the
     * next [start] begins afresh, nothing of this ride's sound carries over.
     */
    fun release() {
        unregisterNoisy()
        val old = synchronized(lock) {
            stopping = true
            renderJob?.cancel()
            renderJob = null
            loadJob?.cancel()
            loadJob = null
            // The cancelled coroutines may still finish a block or a decode on the old mixer; they drop it
            // when they end, together with the clips its playing voices or a late decode still hold.
            val m = mixer
            mixer = AmbienceMixer(SAMPLE_RATE, System.nanoTime()).also { it.volume = m.volume; it.ducked = m.ducked }
            m
        }
        old.clearClips()
    }

    /**
     * The ride's situation. [speedKmh] is the virtual speed (0 when paused), [pedalling] false makes the
     * freewheel tick, [crowdDensity] 0..1 how many spectators line the road near the rider (0 none,
     * 0.3 a village, 1 a mountain-top finish), [climbing] turns the crowd into the dense roar of a
     * pass, [nearFinish] the last kilometre with barriers and the speaker, [inPeloton] riding in the
     * bunch (sheltered, tyres and freewheels all around). [cowbells] adds Alpine cowbells to the
     * crowd; by default on climbs.
     */
    fun update(
        speedKmh: Double,
        pedalling: Boolean,
        crowdDensity: Double,
        climbing: Boolean,
        surface: RoadSurface,
        nearFinish: Boolean,
        inPeloton: Boolean,
        cowbells: Boolean = climbing,
    ) {
        mixer.speedKmh = if (speedKmh.isFinite()) speedKmh.coerceIn(0.0, 120.0) else 0.0
        mixer.pedalling = pedalling
        mixer.crowd = if (crowdDensity.isFinite()) crowdDensity.coerceIn(0.0, 1.0) else 0.0
        mixer.climbing = climbing
        mixer.cobbles = surface == RoadSurface.COBBLES
        mixer.nearFinish = nearFinish
        mixer.inPeloton = inPeloton
        mixer.cowbells = cowbells
    }

    /** The rider passes a group of spectators: a cheer swells up beside him and falls away behind. */
    fun triggerPass() {
        mixer.passes.incrementAndGet()
    }

    /** A race motorbike overtakes (they also come by themselves now and then). */
    fun triggerMoto() {
        mixer.motos.incrementAndGet()
    }

    /** A team car behind gives two short toots. */
    fun triggerTeamCarHorn() {
        mixer.carHorns.incrementAndGet()
    }

    /** Overall level 0..1, e.g. from a setting; changes are smoothed. */
    fun setVolume(v: Float) {
        mixer.volume = if (v.isFinite()) v.coerceIn(0f, 1f) else 0f
    }

    /** Lowers the ambience while the coach speaks or a cue plays. */
    fun duck(on: Boolean) {
        mixer.ducked = on
    }

    // ---------------------------------------------------------------------------------- rendering

    private suspend fun CoroutineScope.renderLoop(mixer: AmbienceMixer) {
        val self = coroutineContext[Job]
        val buffer = ShortArray(AmbienceMixer.BLOCK * 2)
        while (isActive) {
            val track = buildTrack()
            if (track == null) {
                // No audio path right now (the audio server restarting): try again shortly, unless stopped.
                if (stopping && retire(self)) return
                delay(1000)
                continue
            }
            var broken = false
            try {
                runCatching { track.play() }
                var blocks = 0
                while (isActive) {
                    if (blocks++ % CALL_CHECK_BLOCKS == 0) mixer.muted = noisyMuted || callActive()
                    mixer.running = !stopping
                    mixer.render(buffer, AmbienceMixer.BLOCK)
                    // Blocking write: paces this loop to the hardware.
                    val written = track.write(buffer, 0, buffer.size)
                    if (written < 0) {
                        broken = true
                        break
                    }
                    // Faded out: what is still queued in the track is silence, so it can be dropped.
                    if (stopping && mixer.silent && retire(self)) return
                }
            } finally {
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.release() }
            }
            if (!broken) return
            // The track died (route change, audio server restart): build a new one after a moment.
            delay(300)
        }
    }

    /**
     * Decides under the lock whether to end: a start() racing with this either keeps this loop or launches a new
     * one. A loop that release() let go ends without touching [renderJob], which may already be the next ride's.
     */
    private fun retire(self: Job?): Boolean = synchronized(lock) {
        when {
            renderJob !== self -> true
            stopping -> {
                renderJob = null
                true
            }
            else -> false
        }
    }

    private fun buildTrack(): AudioTrack? {
        val minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        // About 90 ms of buffer: latency does not matter for ambience, a GC pause must not starve the track.
        val bytes = max(minBytes * 2, TRACK_FRAMES * 4)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return null
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            return null
        }
        return track
    }

    /** Ringing, in a call or in a voice chat: anything but the normal audio mode. */
    private fun callActive(): Boolean = (audioManager?.mode ?: AudioManager.MODE_NORMAL) != AudioManager.MODE_NORMAL

    // ---------------------------------------------------------------------------------- loading

    /** Decodes every clip the mixer asks for, one at a time, until the ambience is stopped and nothing is pending. */
    private suspend fun CoroutineScope.loadLoop(mixer: AmbienceMixer) {
        val res = appContext.resources
        val self = coroutineContext[Job]
        for (id in AmbienceClips.PRELOAD) mixer.wanted.set(id, 1)
        // Clips whose file is missing or could not be decoded: skipped until the next start().
        val failed = BooleanArray(AmbienceClips.COUNT)
        var idle = 0
        while (isActive) {
            if (retryFailed) {
                retryFailed = false
                failed.fill(false)
            }
            var did = false
            for (id in 0 until AmbienceClips.COUNT) {
                if (!isActive) return
                if (mixer.wanted.get(id) == 0) continue
                mixer.wanted.set(id, 0)
                if (mixer.hasClip(id) || failed[id]) continue
                val spec = AmbienceClips.SPECS[id]
                val clip = decode(res, spec)
                if (clip == null) failed[id] = true else mixer.setClip(id, clip)
                did = true
            }
            if (did) {
                idle = 0
                continue
            }
            // Stopped and nothing asked for a while: retire; the next start() launches a new loader.
            if (!stopping) {
                idle = 0
            } else if (++idle > 25) {
                if (retireLoader(self)) return
                idle = 0
            }
            delay(200)
        }
    }

    /** Like [retire], for the loader. */
    private fun retireLoader(self: Job?): Boolean = synchronized(lock) {
        when {
            loadJob !== self -> true
            stopping -> {
                loadJob = null
                true
            }
            else -> false
        }
    }

    /** OGG Vorbis raw resource -> 16-bit PCM in memory, or null. Blocking; runs on the IO dispatcher. */
    private fun decode(res: Resources, spec: AmbienceClips.Spec): Clip? {
        @Suppress("DiscouragedApi") // like the built-in music: resources by name, so a missing file is not a build error
        val resId = res.getIdentifier(spec.res, "raw", appContext.packageName)
        if (resId == 0) return null
        val afd = runCatching { res.openRawResourceFd(resId) }.getOrNull() ?: return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            var track = -1
            for (i in 0 until extractor.trackCount) {
                if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    break
                }
            }
            if (track < 0) return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var floatPcm = false
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            var pcm = ShortArray(max(16_384L, (durationUs * rate / 1_000_000L + 8192L) * max(channels, 1)).coerceAtMost(40_000_000L).toInt())
            var size = 0
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            while (true) {
                if (!inputDone) {
                    val inIndex = c.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = c.getInputBuffer(inIndex)
                        val n = if (buf == null) -1 else extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = c.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                when {
                    outIndex >= 0 -> {
                        idle = 0
                        val buf = c.getOutputBuffer(outIndex)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.order(ByteOrder.nativeOrder())
                            val count = if (floatPcm) info.size / 4 else info.size / 2
                            if (size + count > pcm.size) pcm = pcm.copyOf(max(pcm.size + pcm.size / 2, size + count))
                            if (floatPcm) {
                                val fb = buf.asFloatBuffer()
                                for (i in 0 until count) pcm[size + i] = (fb.get(i).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                            } else {
                                buf.asShortBuffer().get(pcm, size, count)
                            }
                            size += count
                        }
                        c.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    // A decoder that never signals the end of the stream must not hang the loader.
                    inputDone && ++idle > 50 -> break
                }
            }
            if (channels < 1 || rate < 8000 || size < channels * 2) return null
            if (channels > 2) {
                // Not expected (the files are mono or stereo); keep the front pair.
                val frames = size / channels
                val two = ShortArray(frames * 2)
                for (i in 0 until frames) {
                    two[2 * i] = pcm[i * channels]
                    two[2 * i + 1] = pcm[i * channels + 1]
                }
                return Clip.of(two, 2, rate, spec.loopFrames)
            }
            return Clip.of(if (size == pcm.size) pcm else pcm.copyOf(size), channels, rate, spec.loopFrames)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { afd.close() }
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        noisyRegistered = runCatching {
            // Only the system sends this broadcast, so the receiver need not be visible to other apps.
            ContextCompat.registerReceiver(
                appContext, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.isSuccess
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        noisyRegistered = false
        runCatching { appContext.unregisterReceiver(noisyReceiver) }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val TRACK_FRAMES = 4096
        /** About four times a second. */
        const val CALL_CHECK_BLOCKS = 20
        const val CODEC_TIMEOUT_US = 10_000L
    }
}

/**
 * Decoded audio, interleaved 16-bit. A loop plays [loopStart] until [loopStart] + [loopLen] and wraps;
 * the files carry the loop's end before and its start after the body, so the decoder may trim or pad
 * a few hundred samples at either end without a click at the seam, and interpolation may read one
 * frame past the end.
 */
internal class Clip(val pcm: ShortArray, val channels: Int, val sampleRate: Int, val loopStart: Int, val loopLen: Int) {
    val frames: Int = pcm.size / channels

    companion object {
        /** Frames of pre- and post-roll around a loop body in the files (see the build script). */
        const val PAD = 4096

        fun of(pcm: ShortArray, channels: Int, rate: Int, loopFrames: Int): Clip {
            val frames = pcm.size / channels
            if (loopFrames <= 0) return Clip(pcm, channels, rate, 0, 0)
            // Normally the body sits after the pre-roll; a file of unexpected length loops as a whole.
            return if (frames >= PAD + loopFrames + 1) Clip(pcm, channels, rate, PAD, loopFrames)
            else Clip(pcm, channels, rate, 0, max(1, frames - 1))
        }
    }
}

/** The ambience files in res/raw, by index. Loop lengths are in frames of the file's own sample rate. */
internal object AmbienceClips {
    class Spec(val res: String, val loopFrames: Int = 0)

    const val WIND_LOW = 0
    const val WIND_HIGH = 1
    const val ROLL_ASPHALT = 2
    const val ROLL_COBBLES = 3
    const val CHAIN = 4
    const val PELOTON = 5
    const val CROWD_MURMUR = 6
    const val CROWD_ROAR = 7
    const val FINISH = 8
    const val HELI = 9
    const val MOTO = 10
    const val TEAMCAR = 11
    // Groups of one-shots: first index and count.
    const val TICK = 12
    const val TICKS = 4
    const val CHEER = TICK + TICKS
    const val CHEERS = 6
    const val RUNNER = CHEER + CHEERS
    const val RUNNERS = 2
    const val CLAP = RUNNER + RUNNERS
    const val CLAPS = 4
    const val SHOUT = CLAP + CLAPS
    const val SHOUTS = 16
    const val WHISTLE = SHOUT + SHOUTS
    const val WHISTLES = 6
    const val HORN = WHISTLE + WHISTLES
    const val HORNS = 6
    const val COWBELL = HORN + HORNS
    const val COWBELLS = 8
    const val COUNT = COWBELL + COWBELLS

    val SPECS: Array<Spec> = Array(COUNT) { i ->
        when (i) {
            WIND_LOW -> Spec("amb_wind_low", 352_800)         // 16 s at 22.05 kHz
            WIND_HIGH -> Spec("amb_wind_high", 705_600)       // 16 s
            ROLL_ASPHALT -> Spec("amb_roll_asphalt", 352_800) // 8 s
            ROLL_COBBLES -> Spec("amb_roll_cobbles", 352_800) // 8 s
            CHAIN -> Spec("amb_chain", 176_400)               // 4 s
            PELOTON -> Spec("amb_peloton", 882_000)           // 20 s
            CROWD_MURMUR -> Spec("amb_crowd_murmur", 441_000) // 20 s at 22.05 kHz
            CROWD_ROAR -> Spec("amb_crowd_roar", 1_058_400)   // 24 s
            FINISH -> Spec("amb_finish", 1_058_400)           // 24 s
            HELI -> Spec("amb_heli", 264_600)                 // 12 s at 22.05 kHz
            MOTO -> Spec("amb_moto_pass")
            TEAMCAR -> Spec("amb_teamcar_horn")
            in TICK until TICK + TICKS -> Spec("amb_tick_${i - TICK + 1}")
            in CHEER until CHEER + CHEERS -> Spec("amb_cheer_${i - CHEER + 1}")
            in RUNNER until RUNNER + RUNNERS -> Spec("amb_runner_${i - RUNNER + 1}")
            in CLAP until CLAP + CLAPS -> Spec("amb_clap_${i - CLAP + 1}")
            in SHOUT until SHOUT + SHOUTS -> Spec("amb_shout_${i - SHOUT + 1}")
            in WHISTLE until WHISTLE + WHISTLES -> Spec("amb_whistle_${i - WHISTLE + 1}")
            in HORN until HORN + HORNS -> Spec("amb_horn_${i - HORN + 1}")
            else -> Spec("amb_cowbell_${i - COWBELL + 1}")
        }
    }

    /** Decoded right at the first start: what every ride needs at once. The rest comes when first wanted. */
    val PRELOAD: IntArray = intArrayOf(WIND_LOW, ROLL_ASPHALT, TICK, TICK + 1, TICK + 2, TICK + 3, CHAIN, WIND_HIGH, HELI, CROWD_MURMUR)
}

/**
 * The mixer behind [Ambience], free of Android types so it can be rendered offline. Controls are
 * written from any thread and read once per block; everything audible is smoothed or ramped per
 * block, so 1 Hz updates do not step.
 */
internal class AmbienceMixer(private val outRate: Int, seed: Long) {
    private val clips = AtomicReferenceArray<Clip?>(AmbienceClips.COUNT)
    /** Set when a voice needs a clip that is not decoded yet; the loader picks it up. */
    val wanted = AtomicIntegerArray(AmbienceClips.COUNT)

    @Volatile var speedKmh = 0.0
    @Volatile var pedalling = true
    @Volatile var crowd = 0.0
    @Volatile var climbing = false
    @Volatile var cobbles = false
    @Volatile var nearFinish = false
    @Volatile var inPeloton = false
    @Volatile var cowbells = false
    @Volatile var volume = 1f
    @Volatile var ducked = false
    /** Phone call or lost headphones. */
    @Volatile var muted = false
    /** False fades everything out (stop). */
    @Volatile var running = true
    val passes = AtomicInteger()
    val motos = AtomicInteger()
    val carHorns = AtomicInteger()

    private val rnd = Random(seed)

    // Smoothed state.
    private var speed = 0.0
    private var density = 0.0
    private var wClimb = 0.0
    private var wFinish = 0.0
    private var wPeloton = 0.0
    private var wCobbles = 0.0
    private var wCowbells = 0.0
    private var wPedal = 1.0
    private var master = 0f
    private var limGain = 1f
    private var clock = 0.0
    private var heliPresence = 0.4
    private var heliTarget = 0.4
    private var heliNext = 20.0
    private var lastTriggeredPass = -1e9
    private var nextMotoOk = 60.0
    private var nextCarOk = 45.0
    private var nextRunnerOk = 20.0
    private val lastPick = IntArray(AmbienceClips.COUNT)
    /** Clock time each clip was last heard or started, for giving memory back. */
    private val lastUse = DoubleArray(AmbienceClips.COUNT)
    private var nextSweep = UNLOAD_SEC

    private class LoopVoice(val id: Int, val width: Float) {
        var pos = -1.0
        var gain = 0f
        var y1L = 0f
        var y1R = 0f
        var y2L = 0f
        var y2R = 0f
    }

    private class Shot {
        var clip: Clip? = null
        var pos = 0.0
        var step = 1.0
        var gL = 0f
        var gR = 0f
        var mirror = false
        var a = 1f
        var yL = 0f
        var yR = 0f
        var delay = 0
    }

    private val windLow = LoopVoice(AmbienceClips.WIND_LOW, 0f)
    private val windHigh = LoopVoice(AmbienceClips.WIND_HIGH, 0f)
    private val asphalt = LoopVoice(AmbienceClips.ROLL_ASPHALT, 0.35f)
    private val cobbleLoop = LoopVoice(AmbienceClips.ROLL_COBBLES, 0.5f)
    private val chainLoop = LoopVoice(AmbienceClips.CHAIN, 0.2f)
    private val pelotonLoop = LoopVoice(AmbienceClips.PELOTON, 0f)
    private val murmur = LoopVoice(AmbienceClips.CROWD_MURMUR, 0f)
    private val roar = LoopVoice(AmbienceClips.CROWD_ROAR, 0f)
    private val finishLoop = LoopVoice(AmbienceClips.FINISH, 0f)
    private val heliLoop = LoopVoice(AmbienceClips.HELI, 0f)
    private val shots = Array(MAX_SHOTS) { Shot() }
    private val ticks = Array(MAX_TICKS) { Shot() }
    /** Frames until the next freewheel click. */
    private var tickIn = 0.0
    private val mix = FloatArray(BLOCK * 2)

    fun setClip(id: Int, clip: Clip?) = clips.set(id, clip)
    fun hasClip(id: Int): Boolean = clips.get(id) != null

    fun clearClips() {
        for (i in 0 until AmbienceClips.COUNT) clips.set(i, null)
    }

    /** Faded out completely after [running] went false. */
    val silent: Boolean get() = !running && master < 1e-4f

    /** Renders [frames] interleaved stereo frames into [out]. */
    fun render(out: ShortArray, frames: Int) {
        var done = 0
        while (done < frames) {
            val n = min(BLOCK, frames - done)
            block(out, done, n)
            done += n
        }
    }

    private fun block(out: ShortArray, offset: Int, n: Int) {
        val dt = n.toDouble() / outRate
        clock += dt
        smoothControls(dt)
        java.util.Arrays.fill(mix, 0, 2 * n, 0f)

        val v = speed
        // Wind: aerodynamic noise climbs steeply with speed (~24 dB per tenfold here, compressed from the real
        // thing), opens up in brightness, and the bunch shelters the rider from it.
        val moving = smoothstep(2.0, 9.0, v)
        val wind = undb(WIND_DB + 24.0 * log10(max(v, 1.0) / 30.0) - 6.0 * wPeloton) * moving
        val hi = smoothstep(20.0, 50.0, v)
        loop(windLow, (wind * sqrt(1.0 - 0.75 * hi)).toFloat(), 1.0, (900.0 + 60.0 * v).toFloat(), n)
        loop(windHigh, (wind * 1.15 * sqrt(hi)).toFloat(), 1.0, (500.0 * (max(v, 15.0) / 15.0).pow(1.8)).toFloat(), n)

        // Tyres: the loops were made at 30 km/h; faster plays them faster (and higher), like the real thing.
        val roll = undb(ROLL_DB + 10.0 * log10(max(v, 1.0) / 30.0)) * smoothstep(0.5, 5.0, v)
        val rollRate = (max(v, 1.0) / 30.0).pow(0.75).coerceIn(0.35, 1.8)
        loop(asphalt, (roll * (1.0 - 0.85 * wCobbles)).toFloat(), rollRate, 16000f, n)
        val cobRate = (max(v, 1.0) / 30.0).pow(0.9).coerceIn(0.35, 1.8)
        loop(cobbleLoop, (roll * undb(COBBLES_OVER_ASPHALT_DB) * wCobbles).toFloat(), cobRate, 16000f, n)
        val chainG = undb(CHAIN_DB + 6.0 * log10(max(v, 1.0) / 30.0)) * wPedal * smoothstep(2.0, 8.0, v)
        loop(chainLoop, chainG.toFloat(), (0.85 + v / 200.0).coerceIn(0.85, 1.2), 16000f, n)
        loop(pelotonLoop, (undb(PELOTON_DB) * wPeloton * smoothstep(8.0, 20.0, v)).toFloat(), sqrt(max(v, 10.0) / 42.0).coerceIn(0.75, 1.25), 16000f, n)

        // Crowd beds: a murmur for scattered spectators, the roar of a lined climb, the finish straight.
        val d = density
        val roarW = wClimb * smoothstep(0.3, 0.9, d)
        val murmurG = undb(MURMUR_DB) * smoothstep(0.02, 0.5, d) * (1.0 - 0.75 * max(roarW, wFinish))
        loop(murmur, murmurG.toFloat(), 1.0, 16000f, n)
        loop(roar, (undb(ROAR_DB) * roarW * (1.0 - 0.7 * wFinish)).toFloat(), 1.0, 16000f, n)
        loop(finishLoop, (undb(FINISH_DB) * wFinish).toFloat(), 1.0, 16000f, n)

        // The TV helicopter comes and goes.
        heliNext -= dt
        if (heliNext <= 0) {
            heliTarget = if (rnd.nextDouble() < 0.25) 0.0 else 0.25 + 0.75 * rnd.nextDouble()
            heliNext = 25.0 + 50.0 * rnd.nextDouble()
        }
        heliPresence += (heliTarget - heliPresence) * (1 - exp(-dt / 8.0))
        loop(heliLoop, (undb(HELI_DB) * heliPresence * (0.7 + 0.3 * max(wPeloton, wFinish))).toFloat(), 1.0, 16000f, n)

        events(dt)
        freewheel(n)
        for (s in shots) if (s.clip != null) shot(s, n)
        for (s in ticks) if (s.clip != null) shot(s, n)
        output(out, offset, n, dt)
        if (clock >= nextSweep) {
            nextSweep = clock + 10.0
            sweep()
        }
    }

    /**
     * Gives back clips nobody has used for a few minutes (the finish straight, cobbles, the bunch, cowbells
     * after the climb), except the few every ride needs; they are decoded again when next wanted.
     */
    private fun sweep() {
        for (id in 0 until AmbienceClips.COUNT) {
            val c = clips.get(id) ?: continue
            if (id == AmbienceClips.WIND_LOW || id == AmbienceClips.ROLL_ASPHALT || id in AmbienceClips.TICK until AmbienceClips.TICK + AmbienceClips.TICKS) continue
            if (clock - lastUse[id] < UNLOAD_SEC) continue
            if (shots.any { it.clip === c } || ticks.any { it.clip === c }) continue
            clips.set(id, null)
        }
    }

    private fun smoothControls(dt: Double) {
        speed += (speedKmh - speed) * (1 - exp(-dt / 0.6))
        if (speed < 0.05 && speedKmh == 0.0) speed = 0.0
        density += (crowd - density) * (1 - exp(-dt / 1.5))
        wClimb = follow(wClimb, climbing, dt, 2.5)
        wFinish = follow(wFinish, nearFinish, dt, 3.0)
        wPeloton = follow(wPeloton, inPeloton, dt, 1.5)
        wCobbles = follow(wCobbles, cobbles, dt, 0.25)
        wCowbells = follow(wCowbells, cowbells, dt, 2.0)
        wPedal = follow(wPedal, pedalling, dt, 0.15)
    }

    // ------------------------------------------------------------------------------------ events

    private fun events(dt: Double) {
        val d = density
        val crowdOn = d > 0.02
        if (crowdOn) {
            val c = wClimb
            val f = wFinish
            if (chance(d * (0.3 + 1.1 * c + 0.7 * f), dt)) {
                spawn(pick(AmbienceClips.SHOUT, AmbienceClips.SHOUTS), gain(-8.0, 3.0) * crowdNear(d), rndPan(0.9f), 0.93 + 0.14 * rnd.nextDouble(), 2500f + 9000f * rnd.nextFloat())
            }
            if (chance(d * (0.04 + 0.18 * c + 0.25 * f), dt)) {
                spawn(pick(AmbienceClips.WHISTLE, AmbienceClips.WHISTLES), gain(-15.0, 3.0), rndPan(0.9f), 0.96 + 0.08 * rnd.nextDouble(), 4000f + 6000f * rnd.nextFloat())
            }
            if (chance(d * (0.015 + 0.09 * c + 0.2 * f), dt)) {
                spawn(pick(AmbienceClips.HORN, AmbienceClips.HORNS), gain(-15.0, 3.0), rndPan(0.9f), 0.95 + 0.1 * rnd.nextDouble(), 3000f + 6000f * rnd.nextFloat())
            }
            if (chance(d * (0.05 + 0.08 * c) * (1.0 - 0.6 * f), dt)) {
                spawn(pick(AmbienceClips.CLAP, AmbienceClips.CLAPS), gain(-12.0, 3.0), rndPan(0.8f), 0.95 + 0.1 * rnd.nextDouble(), 6000f + 8000f * rnd.nextFloat())
            }
            if (d > 0.1 && chance(wCowbells * d * 0.55, dt)) {
                // Fans with bells stand in groups: often a second bell answers the first.
                val pan = rndPan(0.9f)
                spawn(pick(AmbienceClips.COWBELL, AmbienceClips.COWBELLS), gain(-10.0, 3.0), pan, 0.92 + 0.16 * rnd.nextDouble(), 5000f + 9000f * rnd.nextFloat())
                if (rnd.nextDouble() < 0.45) {
                    spawn(
                        pick(AmbienceClips.COWBELL, AmbienceClips.COWBELLS), gain(-13.0, 3.0), (pan + rndPan(0.5f)).coerceIn(-1f, 1f),
                        0.92 + 0.16 * rnd.nextDouble(), 4000f + 8000f * rnd.nextFloat(), (outRate * (0.2 + 0.9 * rnd.nextDouble())).toInt(),
                    )
                }
            }
            // On a lined climb a fan sometimes runs alongside, shouting into the rider's ear.
            if (speed in 6.0..32.0 && clock > nextRunnerOk && chance(0.035 * wClimb * smoothstep(0.5, 1.0, d), dt)) {
                nextRunnerOk = clock + 15.0
                spawn(pick(AmbienceClips.RUNNER, AmbienceClips.RUNNERS), gain(-4.0, 1.5), 0f, 0.97 + 0.06 * rnd.nextDouble(), 16000f, 0, rnd.nextBoolean())
            }
            // Cheers as the rider passes groups: on their own when nobody triggers them, rarely when someone does.
            val recent = clock - lastTriggeredPass < 25.0
            val passRate = d.pow(1.5) * 0.11 * (speed / 25.0).coerceIn(0.3, 1.5) * (if (recent) 0.15 else 1.0)
            if (speed > 3.0 && chance(passRate, dt)) cheer(gain(-5.0, 2.0))
        }
        repeat(passes.getAndSet(0)) {
            lastTriggeredPass = clock
            cheer(gain(-3.0, 1.5) * (0.6f + 0.4f * smoothstep(0.0, 0.5, d).toFloat()))
        }
        // Race traffic: motorbikes, the odd team car; not in the last kilometre behind the barriers.
        val manualMoto = motos.getAndSet(0)
        if (manualMoto > 0 || (speed > 12.0 && wFinish < 0.5 && clock > nextMotoOk && chance(if (wPeloton > 0.5) 1.0 / 120 else 1.0 / 200, dt))) {
            nextMotoOk = clock + 25.0
            spawn(AmbienceClips.MOTO, gain(-6.0, 1.5), 0f, 0.96 + 0.08 * rnd.nextDouble(), 16000f, 0, rnd.nextDouble() < 0.3)
        }
        val manualCar = carHorns.getAndSet(0)
        if (manualCar > 0 || (speed > 10.0 && wFinish < 0.5 && clock > nextCarOk && chance(if (wPeloton > 0.5) 1.0 / 150 else 1.0 / 300, dt))) {
            nextCarOk = clock + 30.0
            spawn(AmbienceClips.TEAMCAR, gain(-12.0, 2.0), -0.45f - 0.3f * rnd.nextFloat(), 0.97 + 0.06 * rnd.nextDouble(), 7000f)
        }
    }

    private fun cheer(level: Float) {
        spawn(pick(AmbienceClips.CHEER, AmbienceClips.CHEERS), level, 0f, 0.97 + 0.06 * rnd.nextDouble(), 16000f, 0, rnd.nextBoolean())
    }

    /** A crowd of 1 is close by; a sparse one shouts from further away. */
    private fun crowdNear(d: Double): Float = (0.55 + 0.45 * d).toFloat()

    /** Freewheel pawls: one click per engagement point passed, so the ticking follows the wheel exactly. */
    private fun freewheel(n: Int) {
        val v = speed
        if (wPedal > 0.5 || v < 1.5) {
            tickIn = rnd.nextDouble() * 200
            return
        }
        val wheelRps = v / 3.6 / WHEEL_M
        val interval = outRate / (wheelRps * ENGAGEMENTS)
        // The click rate alone makes coasting louder the faster it goes; above 30 km/h a little less per
        // click, so the buzz stays under the wind.
        val level = (undb(FREEWHEEL_DB) * sqrt(30.0 / max(v, 30.0)) * (1.0 - wPedal)).toFloat()
        while (tickIn < n) {
            val slot = ticks.firstOrNull { it.clip == null } ?: break
            val id = AmbienceClips.TICK + rnd.nextInt(AmbienceClips.TICKS)
            lastUse[id] = clock
            val c = clips.get(id)
            if (c == null) {
                wanted.set(id, 1)
                tickIn += interval
                continue
            }
            arm(slot, c, level * (0.75f + 0.25f * rnd.nextFloat()), rndPan(0.15f), 0.97 + 0.06 * rnd.nextDouble(), 16000f, tickIn.toInt(), false)
            tickIn += interval * (0.94 + 0.12 * rnd.nextDouble())
        }
        tickIn -= n
    }

    private fun spawn(id: Int, level: Float, pan: Float, rate: Double, cutoff: Float, delayFrames: Int = 0, mirror: Boolean = false) {
        lastUse[id] = clock
        val c = clips.get(id)
        if (c == null) {
            wanted.set(id, 1)
            return
        }
        val slot = shots.firstOrNull { it.clip == null } ?: return
        arm(slot, c, level, pan, rate, cutoff, delayFrames, mirror)
    }

    private fun arm(s: Shot, c: Clip, level: Float, pan: Float, rate: Double, cutoff: Float, delayFrames: Int, mirror: Boolean) {
        s.clip = c
        s.pos = 0.0
        s.step = rate * c.sampleRate / outRate
        s.delay = max(0, delayFrames)
        s.mirror = mirror
        if (c.channels >= 2) {
            s.gL = level
            s.gR = level
        } else {
            val ang = (pan.coerceIn(-1f, 1f) + 1f) * (PI / 4).toFloat()
            s.gL = cos(ang) * SQRT2 * level
            s.gR = sin(ang) * SQRT2 * level
        }
        s.a = lpCoef(cutoff)
        s.yL = 0f
        s.yR = 0f
    }

    // ------------------------------------------------------------------------------------ voices

    private fun loop(v: LoopVoice, target: Float, rate: Double, cutoff: Float, n: Int) {
        if (target <= SILENT && v.gain <= SILENT) {
            v.gain = 0f
            return
        }
        lastUse[v.id] = clock
        val c = clips.get(v.id)
        if (c == null || c.loopLen <= 0) {
            if (c == null) wanted.set(v.id, 1)
            return
        }
        if (v.pos < c.loopStart || v.pos >= c.loopStart + c.loopLen) v.pos = c.loopStart + rnd.nextDouble() * c.loopLen
        val step = rate * c.sampleRate / outRate
        val a = lpCoef(cutoff)
        val data = c.pcm
        val ch = c.channels
        val start = c.loopStart
        val len = c.loopLen
        val end = start + len
        val half = len / 2
        val k = v.width
        val norm = 1f / sqrt(1f + k * k)
        // The pre-roll before the body holds a second coding of the loop's last frames; fading into it
        // over the last XF frames hides the small waveform mismatch of a lossy codec at the seam.
        val xf = if (start >= XF) XF else 0
        var g = v.gain
        val dg = (target - g) / n
        var pos = v.pos
        var y1L = v.y1L
        var y1R = v.y1R
        var y2L = v.y2L
        var y2R = v.y2R
        for (i in 0 until n) {
            var l: Float
            var r: Float
            if (ch >= 2) {
                l = loopRead(data, 2, 0, pos, end, len, xf)
                r = loopRead(data, 2, 1, pos, end, len, xf)
            } else {
                val s = loopRead(data, 1, 0, pos, end, len, xf)
                if (k > 0f) {
                    // A second read half a loop away decorrelates the channels: width from a mono file.
                    var p2 = pos + half
                    if (p2 >= end) p2 -= len
                    val s2 = loopRead(data, 1, 0, p2, end, len, xf)
                    l = (s + k * s2) * norm
                    r = (s - k * s2) * norm
                } else {
                    l = s
                    r = s
                }
            }
            if (a < 1f) {
                y1L += a * (l - y1L)
                y2L += a * (y1L - y2L)
                y1R += a * (r - y1R)
                y2R += a * (y1R - y2R)
                l = y2L
                r = y2R
            }
            g += dg
            mix[2 * i] += l * g
            mix[2 * i + 1] += r * g
            pos += step
            if (pos >= end) pos -= len
        }
        v.pos = pos
        v.gain = target
        v.y1L = y1L
        v.y1R = y1R
        v.y2L = y2L
        v.y2R = y2R
    }

    /** Linear-interpolated sample of channel [c] at [pos] frames, cross-faded into the pre-roll over the last [xf] frames. */
    private fun loopRead(data: ShortArray, ch: Int, c: Int, pos: Double, end: Int, len: Int, xf: Int): Float {
        val ip = pos.toInt()
        val fr = (pos - ip).toFloat()
        val j = ip * ch + c
        val a0 = data[j].toFloat()
        val a = a0 + fr * (data[j + ch].toFloat() - a0)
        val into = ip - (end - xf)
        if (xf == 0 || into < 0) return a
        val q = j - len * ch
        val b0 = data[q].toFloat()
        val b = b0 + fr * (data[q + ch].toFloat() - b0)
        return a + (into + fr) / xf * (b - a)
    }

    private fun shot(s: Shot, n: Int) {
        val c = s.clip ?: return
        var i0 = 0
        if (s.delay > 0) {
            if (s.delay >= n) {
                s.delay -= n
                return
            }
            i0 = s.delay
            s.delay = 0
        }
        val data = c.pcm
        val ch = c.channels
        val last = c.frames - 1
        var pos = s.pos
        var yL = s.yL
        var yR = s.yR
        val a = s.a
        var finished = false
        for (i in i0 until n) {
            val ip = pos.toInt()
            if (ip >= last) {
                finished = true
                break
            }
            val fr = (pos - ip).toFloat()
            var l: Float
            var r: Float
            if (ch >= 2) {
                val j = ip * ch
                val l0 = data[j].toFloat()
                val r0 = data[j + 1].toFloat()
                l = l0 + fr * (data[j + ch].toFloat() - l0)
                r = r0 + fr * (data[j + ch + 1].toFloat() - r0)
                if (s.mirror) {
                    val t = l
                    l = r
                    r = t
                }
            } else {
                val s0 = data[ip].toFloat()
                l = s0 + fr * (data[ip + 1].toFloat() - s0)
                r = l
            }
            if (a < 1f) {
                yL += a * (l - yL)
                yR += a * (r - yR)
                l = yL
                r = yR
            }
            mix[2 * i] += l * s.gL
            mix[2 * i + 1] += r * s.gR
            pos += s.step
        }
        s.pos = pos
        s.yL = yL
        s.yR = yR
        if (finished) s.clip = null
    }

    private fun output(out: ShortArray, offset: Int, n: Int, dt: Double) {
        val target = if (!running || muted) 0f else volume * (if (ducked) DUCK else 1f)
        val tau = if (target > master) 0.6 else 0.12
        val m1 = master + (target - master) * (1 - exp(-dt / tau)).toFloat()
        val m0 = master
        master = if (abs(m1 - target) < 1e-5f) target else m1
        val dm = (master - m0) / n
        var m = m0
        var lg = limGain
        for (i in 0 until n) {
            m += dm
            val l = mix[2 * i] * m * INV_SHORT
            val r = mix[2 * i + 1] * m * INV_SHORT
            // Peak limiter: instant attack, ~150 ms release. Only busy moments (finish, horns) reach it.
            val pk = max(abs(l), abs(r))
            if (pk * lg > LIMIT) lg = LIMIT / pk else lg += (1f - lg) * LIM_RELEASE
            val o = 2 * (offset + i)
            out[o] = (l * lg).coerceIn(-1f, 1f).times(32767f).toInt().toShort()
            out[o + 1] = (r * lg).coerceIn(-1f, 1f).times(32767f).toInt().toShort()
        }
        limGain = lg
    }

    // ------------------------------------------------------------------------------------ helpers

    private fun lpCoef(cutoff: Float): Float =
        if (cutoff >= 0.4f * outRate) 1f else (1.0 - exp(-2.0 * PI * cutoff / outRate)).toFloat()

    private fun chance(ratePerSec: Double, dt: Double): Boolean = ratePerSec > 0 && rnd.nextDouble() < ratePerSec * dt

    /** Level in linear gain: [db] with a random spread of [spread] dB. */
    private fun gain(db: Double, spread: Double): Float = undb(db + spread * (2 * rnd.nextDouble() - 1)).toFloat()

    private fun rndPan(width: Float): Float = width * (2f * rnd.nextFloat() - 1f)

    /** A clip of the group starting at [first], never the same one twice in a row. */
    private fun pick(first: Int, count: Int): Int {
        var k = rnd.nextInt(count)
        if (count > 1 && first + k == lastPick[first]) k = (k + 1 + rnd.nextInt(count - 1)) % count
        lastPick[first] = first + k
        return first + k
    }

    private fun follow(w: Double, on: Boolean, dt: Double, tau: Double): Double = w + ((if (on) 1.0 else 0.0) - w) * (1 - exp(-dt / tau))

    companion object {
        const val BLOCK = 512

        // Mix levels in dB against the files (loops are mastered to -19 LUFS, one-shots to -17 LUFS). With
        // these, a ride sits around -26 LUFS on an open road at 30 km/h, -20 on a fast descent and
        // -16 to -18 in the roar of a climb or a finish, before [volume].
        private const val WIND_DB = -10.0          // at 30 km/h
        private const val ROLL_DB = -10.0          // at 30 km/h
        private const val COBBLES_OVER_ASPHALT_DB = 5.0
        private const val CHAIN_DB = -19.0
        private const val PELOTON_DB = -5.0
        private const val MURMUR_DB = -6.0
        private const val ROAR_DB = -2.0
        private const val FINISH_DB = 0.0
        private const val HELI_DB = -13.0
        /** Per freewheel click. */
        private const val FREEWHEEL_DB = -20.0
        private const val MAX_SHOTS = 24
        private const val MAX_TICKS = 24
        private const val SILENT = 1e-5f
        /** A clip unused this long (s) is dropped from memory. */
        private const val UNLOAD_SEC = 180.0
        /** Seam cross-fade in frames; the files carry Clip.PAD frames of pre-roll. */
        private const val XF = 1024
        private const val DUCK = 0.35f
        private const val LIMIT = 0.89f
        private const val INV_SHORT = 1f / 32768f
        private val SQRT2 = sqrt(2f)
        private val LIM_RELEASE = (1.0 - exp(-1.0 / (0.15 * 44_100))).toFloat()
        /** Wheel circumference of a 700x28C tyre. */
        private const val WHEEL_M = 2.136
        /** Ratchet teeth of a typical race hub (DT Swiss 36T): a buzz when coasting fast, ticks when slow. */
        private const val ENGAGEMENTS = 36

        private fun undb(db: Double): Double = 10.0.pow(db / 20.0)

        private fun smoothstep(a: Double, b: Double, x: Double): Double {
            val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
            return t * t * (3 - 2 * t)
        }
    }
}
