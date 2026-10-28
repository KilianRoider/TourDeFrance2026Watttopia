package app.rollentrainer.ride

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import app.rollentrainer.core.coach.CoachInput
import app.rollentrainer.core.coach.CoachOutput
import app.rollentrainer.core.coach.CoachPriority
import app.rollentrainer.core.coach.CoachScript
import app.rollentrainer.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The sound of the spinning view: the rider's music (or the studio beat when no folder is chosen),
 * the spoken coach, and the mood that drives lights and beat. Audible only while the spinning screen
 * is shown; the ride itself keeps running in [RideSession] whichever view is open. Leaving the view
 * for a moment (the normal view, a rotation) only pauses: the playlist, the position in the track
 * and what the coach has already said belong to the ride, not to the screen.
 */
class SpinningAudio(
    context: Context,
    private val settings: SettingsStore,
    private val session: RideSession,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val music = MusicPlayer(appContext, scope)
    private val beat = StudioBeat()
    private val coach = Coach(appContext)
    private val audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)
    private var script = CoachScript()
    private var job: Job? = null
    private var clearJob: Job? = null
    /** The folder had playable files; otherwise the beat takes over. */
    private var usingFolder = false
    /** No ride is running; the next ride state seen starts a new class. */
    private var rideOver = true
    /** The playlist belongs to an earlier ride (or none), so the next activation scans and shuffles again. */
    private var reloadMusic = true
    /** The ride wants sound now: running, not paused, not finished, music switched on, view shown. */
    private var wanted = false
    private var focus = FOCUS_NONE
    /** Another app asked us to duck (a navigation prompt, a notification). */
    private var focusDucked = false
    private var coachSpeaking = false
    private var noisyRegistered = false

    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        )
        // During a call the request is delayed instead of refused; the gain arrives when the call ends.
        .setAcceptsDelayedFocusGain(true)
        .setOnAudioFocusChangeListener(AudioManager.OnAudioFocusChangeListener { change -> onFocusChange(change) }, Handler(Looper.getMainLooper()))
        .build()

    /** Headphones unplugged or a Bluetooth speaker gone: the music must not carry on through the tablet speaker. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) musicOff()
        }
    }

    private val _track = MutableStateFlow<String?>(null)
    /** Title of the running track, or null for the studio beat / silence. */
    val track: StateFlow<String?> = _track.asStateFlow()

    private val _coachLine = MutableStateFlow<String?>(null)
    /** The coach's latest line, cleared a few seconds later. */
    val coachLine: StateFlow<String?> = _coachLine.asStateFlow()

    private val _mood = MutableStateFlow(CoachOutput.MOOD_STEADY)
    /** 0 recovery, 1 steady, 2 hard, 3 sprint. */
    val mood: StateFlow<Int> = _mood.asStateFlow()

    private val _beatBpm = MutableStateFlow(100)
    val beatBpm: StateFlow<Int> = _beatBpm.asStateFlow()

    private val _playing = MutableStateFlow(false)
    /** Music or beat is audible right now. */
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _musicEnabled = MutableStateFlow(true)
    /** The rider's own on/off switch for music and beat; on again at the start of every ride. */
    val musicEnabled: StateFlow<Boolean> = _musicEnabled.asStateFlow()

    /** Whether the speech engine is ready, missing or without a German voice. */
    val coachStatus: StateFlow<Coach.Status> = coach.status

    val active: Boolean get() = job?.isActive == true

    private var previewJob: Job? = null
    private val _previewing = MutableStateFlow(false)
    /** A test of the music folder from the settings is running. */
    val previewing: StateFlow<Boolean> = _previewing.asStateFlow()

    /**
     * Plays the first track of the chosen folder (or the studio beat without one) for a few seconds,
     * so the rider can check the music outside a ride. Pressing again stops it.
     */
    fun togglePreview() {
        if (_previewing.value) { stopPreview(); return }
        if (active) return
        _previewing.value = true
        previewJob = scope.launch {
            try {
                val folder = settings.value.musicFolder
                val n = music.load(folder, if (settings.value.builtInMusic) BuiltInMusic.all else emptyList())
                reloadMusic = true
                usingFolder = n > 0
                applyVolume(settings.value.musicVolume)
                wanted = true
                beat.setBpm(120)
                beat.setIntensity(0.6f)
                applySound()
                // The track name is collected only as long as the preview runs; a StateFlow collector never
                // ends by itself and would otherwise outlive the preview and keep this job from completing.
                val names = launch { music.track.collect { if (usingFolder) _track.value = it } }
                var waited = 0
                while (waited < 20_000) {
                    delay(1000)
                    waited += 1000
                    if (wanted && focus == FOCUS_NONE) applySound()
                }
                names.cancel()
            } finally {
                wanted = false
                silence()
                abandonFocus()
                _track.value = null
                _previewing.value = false
            }
        }
    }

    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
    }

    init {
        // Runs for the app's lifetime, so a ride that starts or ends while the normal view is open is noticed too.
        scope.launch {
            session.state.map { it != null }.distinctUntilChanged().collect { riding ->
                if (riding) newRideIfNeeded() else rideEnded()
            }
        }
    }

    fun setActive(on: Boolean) {
        if (on == active) return
        if (!on) {
            job?.cancel()
            job = null
            deactivate()
            return
        }
        stopPreview()
        registerNoisy()
        job = scope.launch {
            if (session.state.value != null) newRideIfNeeded()
            val folder = settings.value.musicFolder
            val shipped = if (settings.value.builtInMusic) BuiltInMusic.all else emptyList()
            usingFolder = when {
                folder.isBlank() && shipped.isEmpty() -> false
                // Coming back to the view within the same ride keeps the playlist and the position in the track.
                !reloadMusic && folder == music.folder && shipped.isNotEmpty() == music.builtInIncluded -> music.available
                else -> {
                    // Scans on IO and takes the result on the main thread; leaving the view cancels it.
                    val n = music.load(folder, shipped)
                    reloadMusic = false
                    n > 0
                }
            }
            applyVolume(settings.value.musicVolume)
            launch { music.track.collect { if (usingFolder) _track.value = it } }
            launch {
                // Every file of the folder failed: fall back to the studio beat.
                music.exhausted.collect { failed ->
                    if (failed && usingFolder) {
                        usingFolder = false
                        _track.value = null
                        applySound()
                    }
                }
            }
            launch { coach.speaking.collect { speaking -> coachSpeaking = speaking; applyDuck() } }
            launch { settings.state.collect { applyVolume(it.musicVolume) } }
            launch {
                // Music plays as long as the class is open and not over, also before the first pedal stroke
                // and in a break: in a studio the music never waits for the riders.
                combine(session.state, _musicEnabled) { st, on -> st != null && !st.engine.finished && on }
                    .distinctUntilChanged()
                    .collect { play -> updatePlayback(play) }
            }
            session.state.collect { st -> tick(st) }
        }
    }

    /** A ride has started since the last one ended: fresh coach memory, music on, a newly shuffled playlist. */
    private fun newRideIfNeeded() {
        if (!rideOver) return
        rideOver = false
        script = CoachScript()
        reloadMusic = true
        _musicEnabled.value = true
    }

    private fun rideEnded() {
        if (rideOver) return
        rideOver = true
        stopAll()
    }

    private fun applyVolume(percent: Int) {
        val v = percent.coerceIn(0, 100) / 100f
        music.setVolume(v)
        beat.setVolume(v * 0.8f)
    }

    private fun updatePlayback(play: Boolean) {
        wanted = play
        applySound()
    }

    /** Makes the speakers match what the ride wants and what audio focus allows. */
    private fun applySound() {
        val source = when {
            !wanted -> SOURCE_NONE
            usingFolder -> SOURCE_MUSIC
            settings.value.studioBeat -> SOURCE_BEAT
            else -> SOURCE_NONE
        }
        if (source == SOURCE_NONE) {
            silence()
            // During a short loss (a call, the assistant) the request stays in the focus stack: the gain
            // still arrives, and resuming the ride does not take the speaker back from the other app.
            if (focus != FOCUS_LOST) abandonFocus()
            return
        }
        if (focus == FOCUS_NONE) requestFocus()
        if (focus != FOCUS_HELD) {
            // Waiting for a delayed grant, or another app has the speaker for a moment.
            silence()
            return
        }
        if (source == SOURCE_MUSIC) {
            beat.stop()
            music.play()
        } else {
            music.pause()
            beat.start()
            _track.value = null
        }
        _playing.value = true
    }

    private fun silence() {
        music.pause()
        beat.stop()
        _playing.value = false
    }

    private fun applyDuck() {
        val d = coachSpeaking || focusDucked
        music.duck(d)
        beat.duck(d)
    }

    private fun requestFocus() {
        val am = audioManager
        if (am == null) {
            focus = FOCUS_HELD
            return
        }
        val result = runCatching { am.requestAudioFocus(focusRequest) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        focus = when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> FOCUS_HELD
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> FOCUS_WAITING
            else -> FOCUS_NONE
        }
    }

    private fun abandonFocus() {
        if (focus == FOCUS_NONE) return
        focus = FOCUS_NONE
        if (focusDucked) {
            focusDucked = false
            applyDuck()
        }
        runCatching { audioManager?.abandonAudioFocusRequest(focusRequest) }
    }

    /** Runs on the main thread (the request's handler). */
    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (focus == FOCUS_NONE) return
                focus = FOCUS_HELD
                if (focusDucked) {
                    focusDucked = false
                    applyDuck()
                }
                applySound()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                focusDucked = true
                applyDuck()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // A call or an alarm: pause, the gain brings the music back. The coach stops mid-sentence.
                if (focus == FOCUS_NONE) return
                focus = FOCUS_LOST
                coach.stop()
                applySound()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Another app plays now; the rider can switch the music back on with the button.
                abandonFocus()
                musicOff()
            }
        }
    }

    /** Switches music and beat off as if the rider had pressed "Musik aus". */
    private fun musicOff() {
        wanted = false
        applySound()
        _musicEnabled.value = false
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

    private fun tick(st: RideState?) {
        if (st == null) {
            // The ride ended (or was discarded) while the spinning view was open.
            rideEnded()
            return
        }
        newRideIfNeeded()
        // A refused focus request is tried again once a second; a delayed or transient loss waits for the callback.
        if (wanted && focus == FOCUS_NONE) applySound()
        // The beat's track died (a write error, a failed build): build a new one.
        if (wanted && focus == FOCUS_HELD && !usingFolder && settings.value.studioBeat && !beat.running) beat.start()
        // A phone call also counts when no music holds focus (music off): the coach must not talk into it.
        val inCall = callActive()
        if (inCall && coachSpeaking) coach.stop()
        val e = st.engine
        val segment = st.timeline?.segments?.getOrNull(e.segmentIndex)
        val next = e.nextSegment
        val input = CoachInput(
            workoutSec = e.workoutSec,
            segmentIndex = e.segmentIndex,
            segmentLabel = e.segmentLabel,
            segmentRemainingSec = e.segmentRemainingSec,
            segmentDurationSec = segment?.durationSec ?: 0,
            targetWatts = e.targetWatts,
            targetCadence = e.targetCadence,
            power = st.displayPower,
            cue = e.cue,
            finished = e.finished,
            paused = e.paused,
            ftp = settings.value.ftp,
            nextLabel = next?.label?.ifBlank { "Abschnitt ${e.segmentIndex + 2}" },
            erg = segment?.erg ?: true,
        )
        val out = script.update(input)
        _mood.value = out.mood
        val bpm = CoachScript.bpmFor(e.targetCadence)
        _beatBpm.value = bpm
        beat.setBpm(bpm)
        beat.setIntensity(
            when (out.mood) {
                CoachOutput.MOOD_RECOVERY -> 0.15f
                CoachOutput.MOOD_STEADY -> 0.4f
                CoachOutput.MOOD_HARD -> 0.7f
                else -> 1f
            }
        )
        if (out.lines.isNotEmpty()) {
            // No talking over a call or an alarm that took the speaker from the music.
            val voice = settings.value.coachVoice && !inCall && focus != FOCUS_LOST && focus != FOCUS_WAITING
            for (line in out.lines) if (voice) coach.say(line.speech, line.priority)
            // The screen shows the most important line of this second, HIGH before NORMAL before LOW.
            show(out.lines.maxBy { it.priority.ordinal }.text)
        }
        // When the class is over the playback collector has already stopped the music; the coach's last line still goes out.
    }

    /** Ringing, in a call or in a voice chat: anything but the normal audio mode. */
    private fun callActive(): Boolean = (audioManager?.mode ?: AudioManager.MODE_NORMAL) != AudioManager.MODE_NORMAL

    private fun show(text: String) {
        _coachLine.value = text
        clearJob?.cancel()
        clearJob = scope.launch {
            delay(6000)
            if (_coachLine.value == text) _coachLine.value = null
        }
    }

    fun nextTrack() {
        if (usingFolder) music.next()
    }

    fun previousTrack() {
        if (usingFolder) music.previous()
    }

    fun toggleMusic() {
        _musicEnabled.value = !_musicEnabled.value
    }

    /** One-off spoken line, e.g. when the rider taps a control. */
    fun say(text: String) {
        if (settings.value.coachVoice) coach.say(text, CoachPriority.NORMAL)
        show(text)
    }

    /** Leaving the view: pause only, keeping the player, the playlist and the coach's memory for coming back. */
    private fun deactivate() {
        unregisterNoisy()
        wanted = false
        silence()
        abandonFocus()
        coach.stop()
        clearJob?.cancel()
        _coachLine.value = null
    }

    /** The ride is over: release the player too; the next ride loads the folder again. */
    private fun stopAll() {
        wanted = false
        music.stop()
        beat.stop()
        coach.stop()
        abandonFocus()
        clearJob?.cancel()
        _coachLine.value = null
        _track.value = null
        _playing.value = false
    }

    fun release() {
        setActive(false)
        music.release()
        beat.release()
        coach.release()
    }

    private companion object {
        const val SOURCE_NONE = 0
        const val SOURCE_MUSIC = 1
        const val SOURCE_BEAT = 2

        const val FOCUS_NONE = 0
        /** Requested during a call; the grant comes later through the listener. */
        const val FOCUS_WAITING = 1
        const val FOCUS_HELD = 2
        /** Lost for a moment (call, alarm); the gain comes back through the listener. */
        const val FOCUS_LOST = 3
    }
}
