package app.rollentrainer.ride

/**
 * One of the royalty-free electronic tracks that ship with the app for the spinning-class mode.
 * The audio was synthesised for this project, so there is no licence to clear.
 *
 * @property resName name of the raw resource (`R.raw.<resName>`, an OGG Vorbis file)
 * @property title display title
 * @property bpm tempo; drum & bass at 170 works as a half-time feel at 85 rpm
 * @property energy 0 recovery, 1 steady, 2 hard, 3 sprint
 */
data class BuiltInTrack(val resName: String, val title: String, val bpm: Int, val energy: Int)

/** Catalogue of the built-in tracks, in playlist order. */
object BuiltInMusic {
    val all: List<BuiltInTrack> = listOf(
        BuiltInTrack("music_01_nachtfahrt", "Nachtfahrt", 125, 1),      // progressive house, A minor
        BuiltInTrack("music_02_kaltstart", "Kaltstart", 128, 2),        // melodic techno, F minor
        BuiltInTrack("music_03_gipfelsturm", "Gipfelsturm", 129, 3),    // big-room / festival house, G minor
        BuiltInTrack("music_04_hoehenflug", "Höhenflug", 138, 3),       // trance, E minor
        BuiltInTrack("music_05_doppelschlag", "Doppelschlag", 170, 3),  // drum & bass (half-time at 85 rpm), D minor
        BuiltInTrack("music_06_tiefgang", "Tiefgang", 120, 1),          // deep house, B minor
        BuiltInTrack("music_07_neonlicht", "Neonlicht", 110, 0),        // synthwave, C minor
        BuiltInTrack("music_08_kettenblatt", "Kettenblatt", 126, 2),    // tech house, G-sharp minor
    )

    /** Tracks whose [BuiltInTrack.energy] matches, in catalogue order. */
    fun withEnergy(energy: Int): List<BuiltInTrack> = all.filter { it.energy == energy }
}
