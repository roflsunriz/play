package io.github.playmusic.data.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/** One focus owner for both overlapping decoders; releasing the tail cannot steal focus. */
internal class PlaybackAudioFocus(context: Context, private val onChange: (Int) -> Unit) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val listener = AudioManager.OnAudioFocusChangeListener(onChange)
    private val request = if (Build.VERSION.SDK_INT >= 26) AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper())).build() else null
    private var held = false

    @Suppress("DEPRECATION")
    fun acquire(): Boolean {
        if (held) return true
        val result = if (Build.VERSION.SDK_INT >= 26) manager.requestAudioFocus(checkNotNull(request))
            else manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        held = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return held
    }
    @Suppress("DEPRECATION")
    fun release() {
        if (!held) return
        if (Build.VERSION.SDK_INT >= 26) manager.abandonAudioFocusRequest(checkNotNull(request))
        else manager.abandonAudioFocus(listener)
        held = false
    }
}
