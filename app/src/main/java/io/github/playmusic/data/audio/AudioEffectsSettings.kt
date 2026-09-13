package io.github.playmusic.data.audio

/** Thirty third-octave centres, from 25 Hz through 20 kHz. */
object EqualizerBands {
    val frequenciesHz = listOf(25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f, 200f,
        250f, 315f, 400f, 500f, 630f, 800f, 1000f, 1250f, 1600f, 2000f,
        2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f, 20000f)
    const val COUNT = 30
    const val MIN_GAIN_DB = -12f
    const val MAX_GAIN_DB = 12f
    const val MIN_PREAMP_DB = -10f
    const val MAX_PREAMP_DB = 10f
    const val SLOT_COUNT = 5
    const val MAX_NAME_LENGTH = 40
}

data class EqualizerSettings(
    val enabled: Boolean = false,
    val preampDb: Float = 0f,
    val bandGainsDb: List<Float> = List(EqualizerBands.COUNT) { 0f },
) {
    init {
        require(preampDb.isFinite() && preampDb in EqualizerBands.MIN_PREAMP_DB..EqualizerBands.MAX_PREAMP_DB)
        require(bandGainsDb.size == EqualizerBands.COUNT && bandGainsDb.all {
            it.isFinite() && it in EqualizerBands.MIN_GAIN_DB..EqualizerBands.MAX_GAIN_DB
        })
    }
}

data class EqualizerSlot(val name: String, val settings: EqualizerSettings)

data class AudioEffectsState(
    val settings: EqualizerSettings = EqualizerSettings(),
    val slots: List<EqualizerSlot?> = List(EqualizerBands.SLOT_COUNT) { null },
    val isReady: Boolean = false,
    val storageFailed: Boolean = false,
)

enum class EqualizerPreset { FLAT, BASS_BOOST, TREBLE_BOOST, VOCAL, ROCK, POP, JAZZ }
