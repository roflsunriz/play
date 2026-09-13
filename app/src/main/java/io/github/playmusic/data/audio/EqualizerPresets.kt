package io.github.playmusic.data.audio

/** Starting curves with preamp headroom; every band remains independently editable. */
fun EqualizerPreset.settings(): EqualizerSettings {
    val preamp: Float
    val gains: List<Float>
    when (this) {
        EqualizerPreset.FLAT -> { preamp = 0f; gains = List(EqualizerBands.COUNT) { 0f } }
        EqualizerPreset.BASS_BOOST -> {
            preamp = -7f
            gains = listOf(6f, 6f, 6f, 5.5f, 5f, 4f, 3f, 2f, 1f, 0f,
                0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }
        EqualizerPreset.TREBLE_BOOST -> {
            preamp = -6f
            gains = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.5f, 1f, 2f, 3f, 4f, 5f, 5f, 5f, 5f, 5f)
        }
        EqualizerPreset.VOCAL -> {
            preamp = -4f
            gains = listOf(-3f, -3f, -3f, -2.5f, -2f, -1f, 0f, 0f, 0f, 0f,
                0f, 0.5f, 1f, 1.5f, 2f, 2.5f, 3f, 3f, 3f, 2.5f, 2f, 1f, 0f, 0f, 0f, -1f, -1f, -1f, -1f, -1f)
        }
        EqualizerPreset.ROCK -> {
            preamp = -5f
            gains = listOf(3f, 3f, 3f, 3f, 2.5f, 2f, 1f, 0f, -1f, -1.5f,
                -2f, -2f, -2f, -1.5f, -1f, 0f, 0.5f, 1f, 1.5f, 2f, 2.5f, 3f, 3f, 3f, 3f, 2.5f, 2f, 2f, 1.5f, 1f)
        }
        EqualizerPreset.POP -> {
            preamp = -4f
            gains = listOf(1f, 1f, 1.5f, 2f, 2f, 1.5f, 1f, 0f, -0.5f, -1f,
                -1f, -0.5f, 0f, 1f, 2f, 2.5f, 3f, 3f, 2.5f, 2f, 1f, 0f, 0f, 0.5f, 1f, 1.5f, 2f, 2f, 1.5f, 1f)
        }
        EqualizerPreset.JAZZ -> {
            preamp = -3.5f
            gains = listOf(2f, 2f, 2f, 2f, 1.5f, 1f, 0.5f, 0f, 0f, -0.5f,
                -0.5f, -0.5f, 0f, 0f, 0f, 0.5f, 1f, 1f, 1f, 1f, 1.5f, 2f, 2f, 2f, 2f, 2f, 1.5f, 1.5f, 1f, 1f)
        }
    }
    return EqualizerSettings(enabled = true, preampDb = preamp, bandGainsDb = gains)
}
