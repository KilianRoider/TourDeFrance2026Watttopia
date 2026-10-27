package app.rollentrainer.ride

import android.content.Context
import app.rollentrainer.core.coach.CoachInput
import app.rollentrainer.core.coach.CoachOutput
import app.rollentrainer.core.coach.CoachPriority
import app.rollentrainer.core.coach.CoachScript
import app.rollentrainer.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The sound of the spinning view: the rider's music (or the studio beat when no folder is chosen),
 * the spoken coach, and the mood that drives lights and beat. Active only while the spinning screen
 * is shown; the ride itself keeps running in [RideSession] whichever view is open.
 */
class SpinningAudio(
    context: Context,
    private val settings: SettingsStore,
    private val session: RideSession,
    private val scope: CoroutineScope,
) {
    private val music = MusicPlayer(context)
    private val beat = StudioBeat()
    private val coach = Coach(context)
    private var script = CoachScript()
    private var job: Job? = null
    private var clearJob: Job? = null
    /** The folder had playable files; otherwise the beat takes over. */
    private var usingFolder = false

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
    /** The rider's own on/off switch for music and beat. */
    val musicEnabled: StateFlow<Boolean> = _musicEnabled.asStateFlow()

    val active: Boolean get() = job?.isActive == true

    fun setActive(on: Boolean) {
        if (on == active) return
        if (!on) {
            job?.cancel()
            job = null
            stopAll()
            return
        }
        script = CoachScript()
        job = scope.launch {
            val folder = settings.value.musicFolder
            usingFolder = folder.isNotBlank() && withContext(Dispatchers.IO) { music.load(folder) } > 0
            applyVolume(settings.value.musicVolume)
            launch { music.track.collect { if (usingFolder) _track.value = it } }
            launch { coach.speaking.collect { speaking -> music.duck(speaking); beat.duck(speaking) } }
            launch { settings.state.collect { applyVolume(it.musicVolume) } }
            launch {
                // Music follows the pause state and the on/off switch, whichever changes.
                combine(session.state, _musicEnabled) { st, on -> st != null && !st.engine.paused && !st.engine.finished && on }
                    .distinctUntilChanged()
                    .collect { play -> updatePlayback(play) }
            }
            session.state.collect { st -> tick(st) }
        }
    }

    private fun applyVolume(percent: Int) {
        val v = percent.coerceIn(0, 100) / 100f
        music.setVolume(v)
        beat.setVolume(v * 0.8f)
    }

    private fun updatePlayback(play: Boolean) {
        val cfg = settings.value
        if (play && usingFolder) {
            music.play()
            _playing.value = true
        } else if (play && cfg.studioBeat) {
            beat.start()
            _track.value = null
            _playing.value = true
        } else {
            music.pause()
            beat.stop()
            _playing.value = false
        }
    }

    private fun tick(st: RideState?) {
        if (st == null) {
            // The ride ended (or was discarded) while the spinning view was open.
            stopAll()
            return
        }
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
            val voice = settings.value.coachVoice
            for (line in out.lines) if (voice) coach.say(line.text, line.priority)
            // The screen shows the most important line of this second, HIGH before NORMAL before LOW.
            show(out.lines.maxBy { it.priority.ordinal }.text)
        }
        // When the class is over the playback collector has already stopped the music; the coach's last line still goes out.
    }

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

    private fun stopAll() {
        music.stop()
        beat.stop()
        coach.stop()
        clearJob?.cancel()
        _coachLine.value = null
        _track.value = null
        _playing.value = false
        usingFolder = false
    }

    fun release() {
        setActive(false)
        music.release()
        beat.release()
        coach.release()
    }
}
