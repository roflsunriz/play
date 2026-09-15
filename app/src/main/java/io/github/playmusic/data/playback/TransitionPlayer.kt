package io.github.playmusic.data.playback

import android.content.Context
import android.media.AudioManager
import android.util.Log
import io.github.playmusic.BuildConfig
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/** A stable MediaSession player over two standard Media3/DRM decoders.
 * All mutable state is owned by the application looper. Only the incoming decoder is exposed
 * as the current song, so notification controls, lyrics and seeking share the same position.
 */
@OptIn(UnstableApi::class)
class TransitionPlayer(
    context: Context,
    first: ExoPlayer,
    second: ExoPlayer,
    private val settings: () -> PlaybackTransitionSettings,
    private val automix: AutomixResolver? = null,
    private val beforeAudioFocus: suspend () -> Unit = {},
    private val onAutomixFailure: () -> Unit = {},
) : ForwardingSimpleBasePlayer(first) {
    private val engines = listOf(first, second)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var active = first
    private var tail: ExoPlayer? = null
    private var preparedIndex = C.INDEX_UNSET
    private var preparedFor: MediaItem? = null
    private var preparedRecipe: AutomixTransition? = null
    private var preparedSeconds = 0
    private var preparedAutomix = false
    private var lastExplicitSeekPositionMs: Long? = null
    private var skippedRecipeForSeek = false
    private var preparation: Job? = null
    private var ramp: Job? = null
    private var starting: Job? = null
    private var startGeneration = 0L
    private var overlap: Job? = null
    private var userVolume = first.volume
    private var envelope = 1f
    private var headGain = 1f
    private var tailGain = 0f
    private var duckGain = 1f
    private var desiredPlaying: Boolean? = null
    private var sleeping = false
    private var released = false
    private var resumeAfterFocus = false
    private var finalFadeStarted = false
    private var transportError: PlaybackException? = null
    private val focus = PlaybackAudioFocus(context) { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                duckGain = 1f; applyVolumes()
                if (resumeAfterFocus) { resumeAfterFocus = false; startPlayback() }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { duckGain = .2f; applyVolumes() }
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeAfterFocus = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && desiredPlaying == true
                pauseImmediately(abandonFocus = change == AudioManager.AUDIOFOCUS_LOSS)
            }
        }
    }

    init {
        engines.forEach { engine -> engine.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (released || engine !== active) return
                // A removed headset must stop both decoders immediately.
                if (!engine.playWhenReady && desiredPlaying == true && starting == null && ramp == null && overlap == null) {
                    desiredPlaying = false; stopTail(); focus.release(); invalidateState()
                }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!released && !playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
                    resumeAfterFocus = false; pauseImmediately()
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (released || engine !== active) return
                finalFadeStarted = false
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    lastExplicitSeekPositionMs = null; skippedRecipeForSeek = false
                }
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && desiredPlaying == true && overlap == null) {
                    cancelPreparation()
                    envelope = 0f; applyVolumes(); rampTo(1f, settings().fadeInMs) { }
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!released && engine === active && playbackState == Player.STATE_ENDED && overlap == null) {
                    ramp?.cancel(); ramp = null; desiredPlaying = false
                    focus.release(); invalidateState()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (!released && engine === active) {
                    desiredPlaying = false; ramp?.cancel(); ramp = null
                    stopTail(); focus.release(); invalidateState()
                }
            }
        }) }
        scope.launch {
            while (isActive) { delay(40); maybePrepareTransition() }
        }
    }

    /** Debug meters can observe both decoders without assuming the session is an ExoPlayer. */
    fun audioEngines(): List<ExoPlayer> = engines

    override fun getState(): State {
        val state = super.getState()
        return state.buildUpon().setVolume(userVolume).setPlayerError(transportError ?: state.playerError)
            .setPlayWhenReady(desiredPlaying ?: player.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .apply { if (transportError != null) { setPlaybackState(Player.STATE_IDLE); setIsLoading(false) } }.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        resumeAfterFocus = false
        if (playWhenReady) startPlayback() else fadeToStop(stop = false)
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        resumeAfterFocus = false; fadeToStop(stop = true)
        return Futures.immediateVoidFuture()
    }

    private fun startPlayback() {
        val generation = ++startGeneration
        starting?.cancel(); starting = null
        transportError = null
        finalFadeStarted = false
        desiredPlaying = true
        if (overlap != null) {
            // setMediaItems+play for the next song starts its overlap before this
            // redundant play() arrives. The overlap owns the gains: keep both engines
            // playing and leave the envelope alone instead of silencing them and
            // restarting the fade after focus/buffering.
            active.play(); tail?.play()
            invalidateState()
            return
        }
        if (active.isPlaying && ramp == null && envelope == 1f) {
            // Already sounding at full gain; only re-assert play state.
            active.play()
            invalidateState()
            return
        }
        ramp?.cancel(); ramp = null
        if (!active.isPlaying) { envelope = 0f; applyVolumes() }
        // Android 15+ only grants background audio focus after the playback service is
        // actually foreground. Advertise the user play intent, await that notification,
        // and only then acquire focus and start both decoders.
        invalidateState()
        starting = scope.launch(start = CoroutineStart.LAZY) {
            try {
                beforeAudioFocus()
                if (!focus.acquire()) { desiredPlaying = false; invalidateState(); return@launch }
                active.play()
                tail?.play()
                rampTo(1f, settings().fadeInMs) { }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                desiredPlaying = false
                active.pause(); active.stop(); cancelTransition(); focus.release()
                transportError = PlaybackException("Playback service could not become ready", error, PlaybackException.ERROR_CODE_UNSPECIFIED)
                invalidateState()
            } finally { if (startGeneration == generation) starting = null }
        }
        starting?.start()
    }

    private fun fadeToStop(stop: Boolean) {
        startGeneration++
        starting?.cancel(); starting = null
        desiredPlaying = false
        invalidateState()
        if (!active.isPlaying && tail?.isPlaying != true) {
            pauseImmediately()
            if (stop) active.stop()
            return
        }
        rampTo(0f, settings().fadeOutMs) {
            active.pause(); stopTail()
            if (stop) active.stop()
            focus.release()
            envelope = 1f; applyVolumes(); invalidateState()
        }
    }

    private fun rampTo(target: Float, duration: Long, finished: () -> Unit) {
        ramp?.cancel()
        val from = envelope
        if (duration <= 0 || from == target) { envelope = target; applyVolumes(); ramp = null; finished(); return }
        ramp = scope.launch {
            var elapsed = 0L
            var previous = active.currentPosition
            while (elapsed < duration) {
                delay(20)
                val position = active.currentPosition
                // Advance by rendered audio, not buffering time; a new song may reset position.
                val delta = (position - previous).takeIf { it in 0..500 } ?: 20
                previous = position
                if (active.isPlaying || tail?.isPlaying == true) elapsed += delta
                else if (target == 0f) break
                envelope = TransitionEnvelope.gain(from, target, elapsed, duration)
                applyVolumes()
            }
            envelope = target; applyVolumes(); ramp = null; finished()
        }
    }

    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> {
        userVolume = when (volumeOperationType) {
            C.VOLUME_OPERATION_TYPE_MUTE -> 0f
            C.VOLUME_OPERATION_TYPE_UNMUTE -> 1f
            else -> volume.coerceIn(0f, 1f)
        }
        applyVolumes(); invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        cancelTransition(); ramp?.cancel(); ramp = null
        lastExplicitSeekPositionMs = startPositionMs.takeIf { it >= 0 }; skippedRecipeForSeek = false
        val selectedIndex = if (startIndex == C.INDEX_UNSET) 0 else startIndex
        val selected = mediaItems.getOrNull(selectedIndex)
        if (selected != null && selected.mediaId != active.currentMediaItem?.mediaId && active.isPlaying &&
            desiredPlaying == true && settings().crossfadeMs > 0 && !sleeping) {
            val incoming = spare()
            incoming.pause(); incoming.volume = 0f; incoming.pauseAtEndOfMediaItems = false
            incoming.repeatMode = active.repeatMode
            incoming.shuffleModeEnabled = active.shuffleModeEnabled
            incoming.playbackParameters = active.playbackParameters
            incoming.setMediaItems(mediaItems, selectedIndex, startPositionMs)
            incoming.prepare()
            val incomingDuration = selected.mediaMetadata.extras?.getLong("durationMs")?.takeIf { it > 0 }
                ?: settings().crossfadeMs * 2
            val duration = TransitionEnvelope.overlapMs(settings().crossfadeMs,
                (active.duration - active.currentPosition).coerceAtLeast(0) * 2, incomingDuration)
            beginOverlap(incoming, duration, null)
            return Futures.immediateVoidFuture()
        }
        envelope = if (desiredPlaying == true) 0f else 1f
        applyVolumes()
        val result = super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
        // play() may be a no-op when a new queue replaces an already playing queue.
        if (desiredPlaying == true && mediaItems.isNotEmpty()) rampTo(1f, settings().fadeInMs) { }
        return result
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val wasFinalFade = finalFadeStarted
        finalFadeStarted = false
        if (wasFinalFade && desiredPlaying == true) rampTo(1f, settings().fadeInMs) { }
        val targetIndex = when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> active.nextMediaItemIndex
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> active.previousMediaItemIndex
            Player.COMMAND_SEEK_TO_PREVIOUS -> if (active.currentPosition > active.maxSeekToPreviousPosition) active.currentMediaItemIndex else active.previousMediaItemIndex
            else -> mediaItemIndex
        }
        if (targetIndex != C.INDEX_UNSET && targetIndex != active.currentMediaItemIndex && active.isPlaying &&
            settings().crossfadeMs > 0 && !sleeping) {
            prepare(targetIndex, manual = true)
            return Futures.immediateVoidFuture()
        }
        val sameItem = targetIndex == active.currentMediaItemIndex ||
            (targetIndex == C.INDEX_UNSET && seekCommand in setOf(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD))
        if (sameItem && tail == null && active.isPlaying && !sleeping && seekOverlap(positionMs)) {
            return Futures.immediateVoidFuture()
        }
        if (sameItem && tail == null) {
            // Seeking within this song does not change the queued successor. Keep its DRM
            // session and decoder ready; rebuilding it near the exit cue can miss Automix.
            val result = super.handleSeek(mediaItemIndex, positionMs, seekCommand)
            lastExplicitSeekPositionMs = active.currentPosition
            if (skippedRecipeForSeek || preparedRecipe?.let { active.currentPosition > it.outgoingStartMs + 100 } == true) cancelPreparation()
            return result
        }
        lastExplicitSeekPositionMs = null; skippedRecipeForSeek = false
        cancelTransition()
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        cancelPreparation()
        return super.handleSetShuffleModeEnabled(shuffleModeEnabled)
    }
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        cancelPreparation()
        return super.handleSetRepeatMode(repeatMode)
    }
    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        cancelTransition()
        return super.handleRemoveMediaItems(fromIndex, toIndex)
    }
    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        cancelPreparation()
        return super.handleAddMediaItems(index, mediaItems)
    }
    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        cancelPreparation()
        return super.handleMoveMediaItems(fromIndex, toIndex, newIndex)
    }
    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        cancelPreparation()
        return super.handleReplaceMediaItems(fromIndex, toIndex, mediaItems)
    }

    private fun maybePrepareTransition() {
        if (released || sleeping || desiredPlaying != true || !active.isPlaying || overlap != null ||
            active.repeatMode == Player.REPEAT_MODE_ONE) return
        val next = active.nextMediaItemIndex
        val config = settings()
        if (next == C.INDEX_UNSET) {
            val remaining = active.duration - active.currentPosition
            if (!finalFadeStarted && config.fadeOutMs > 0 && active.duration != C.TIME_UNSET &&
                remaining in 1..config.fadeOutMs) {
                finalFadeStarted = true
                rampTo(0f, remaining) { }
            }
            return
        }
        if (next == active.currentMediaItemIndex) return
        if (config.crossfadeMs <= 0 && !config.automixEnabled) {
            if (preparedFor != null || preparation != null) cancelPreparation()
            return
        }
        val duration = active.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        if (preparedFor != active.currentMediaItem || preparedIndex != next ||
            preparedSeconds != config.crossfadeSeconds || preparedAutomix != config.automixEnabled) {
            // Cue points can precede the normal outro. Resolve once the queue is playing.
            if (config.automixEnabled || duration - active.currentPosition <= config.crossfadeMs + 15_000) prepare(next, manual = false)
            return
        }
        val incoming = spare()
        if (preparation?.isActive == true || incoming.playbackState != Player.STATE_READY) return
        val prepared = preparedRecipe
        val fitted = prepared?.fitWithin(duration, incoming.duration)
        if (prepared != null && (!config.automixEnabled || fitted == null)) {
            trace("reject disabled=${!config.automixEnabled} outgoingRange=${prepared.outgoingStartMs + prepared.durationMs > duration}" +
                " incomingRange=${prepared.incomingStartMs + prepared.durationMs * prepared.incomingSpeed > incoming.duration}" +
                " unusable=${fitted == null}" +
                " outStart=${prepared.outgoingStartMs}" +
                " inStart=${prepared.incomingStartMs} length=${prepared.durationMs} speed=${prepared.incomingSpeed}" +
                " outDuration=$duration inDuration=${incoming.duration} outPosition=${active.currentPosition}")
            preparedRecipe = null
            incoming.playbackParameters = PlaybackParameters.DEFAULT
            incoming.seekTo(0)
            return
        }
        if (fitted != null && fitted != prepared) {
            trace("clip length=${prepared?.durationMs} available=${fitted.durationMs} outDuration=$duration" +
                " inDuration=${incoming.duration} outStart=${fitted.outgoingStartMs} inStart=${fitted.incomingStartMs}" +
                " speed=${fitted.incomingSpeed}")
            preparedRecipe = fitted
        }
        val recipe = preparedRecipe?.takeIf { config.automixEnabled }
        val length = recipe?.durationMs ?: TransitionEnvelope.overlapMs(config.crossfadeMs, duration, incoming.duration)
        if (length <= 0) return
        val start = recipe?.outgoingStartMs ?: (duration - length)
        if (active.currentPosition < start || active.currentPosition >= duration - 100) return
        if (recipe != null) {
            val adjusted = recipe.advanceTo(active.currentPosition)
            if (adjusted == null) {
                trace("expired outPosition=${active.currentPosition} outStart=${recipe.outgoingStartMs} length=${recipe.durationMs}")
                preparedRecipe = null
                incoming.playbackParameters = PlaybackParameters.DEFAULT
                incoming.seekTo(0)
                return
            }
            if (adjusted.incomingStartMs != recipe.incomingStartMs) {
                trace("late-adjust late=${adjusted.outgoingStartMs - recipe.outgoingStartMs}" +
                    " inStart=${adjusted.incomingStartMs} length=${adjusted.durationMs} speed=${adjusted.incomingSpeed}")
                incoming.seekTo(adjusted.incomingStartMs)
            }
            beginOverlap(incoming, adjusted.durationMs, adjusted)
        } else beginOverlap(incoming, minOf(length, duration - active.currentPosition), null)
    }

    private fun prepare(index: Int, manual: Boolean) {
        cancelPreparation()
        if (overlap != null) stopTail()
        if (index !in 0 until active.mediaItemCount) return
        val owner = active
        val incoming = spare()
        val current = owner.currentMediaItem ?: return
        val next = owner.getMediaItemAt(index)
        preparedFor = current; preparedIndex = index
        preparedSeconds = settings().crossfadeSeconds; preparedAutomix = settings().automixEnabled
        preparation = scope.launch {
            val context = current.mediaMetadata.extras?.getString("contextUri")
            val resolved = if (!manual && settings().automixEnabled && context != null && automix != null) try {
                automix.resolve(context, current.mediaId, next.mediaId)?.takeIf { it.fromUri == current.mediaId && it.toUri == next.mediaId }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                onAutomixFailure()
                null // An optional transition must not prevent the normal queue from playing.
            } else null
            if (active !== owner || preparedFor != current) return@launch
            skippedRecipeForSeek = resolved != null && lastExplicitSeekPositionMs?.let { it > resolved.outgoingStartMs + 100 } == true
            val recipe = resolved?.takeUnless { skippedRecipeForSeek }
            trace("prepare manual=$manual context=${context != null} recipe=${recipe != null}" +
                " skippedSeek=$skippedRecipeForSeek" +
                " outStart=${recipe?.outgoingStartMs} inStart=${recipe?.incomingStartMs} length=${recipe?.durationMs}" +
                " speed=${recipe?.incomingSpeed} outDuration=${owner.duration} inDuration=${incoming.duration}" +
                " outPosition=${owner.currentPosition}")
            preparedRecipe = recipe
            if (!manual) {
                val overlapMs = recipe?.durationMs ?: settings().crossfadeMs
                if (overlapMs <= 0) return@launch
                val transitionStart = recipe?.outgoingStartMs ?: (owner.duration - overlapMs)
                // Resolve cuepoints early, but hold the second decoder only near its entry point.
                while (owner.currentPosition < transitionStart - 15_000) {
                    delay(500)
                    if (active !== owner || preparedFor != current) return@launch
                }
            }
            incoming.pause()
            incoming.volume = 0f
            incoming.pauseAtEndOfMediaItems = false
            incoming.repeatMode = owner.repeatMode
            incoming.shuffleModeEnabled = owner.shuffleModeEnabled
            incoming.playbackParameters = PlaybackParameters(recipe?.incomingSpeed ?: owner.playbackParameters.speed, owner.playbackParameters.pitch)
            incoming.setMediaItems(List(owner.mediaItemCount, owner::getMediaItemAt), index, recipe?.incomingStartMs ?: 0)
            copyShuffleOrder(owner, incoming)
            incoming.prepare()
            if (manual) {
                // Publish the selected song immediately, even while it buffers. A second next,
                // pause or seek now operates on that selection instead of the previous song.
                val incomingDuration = next.mediaMetadata.extras?.getLong("durationMs")?.takeIf { it > 0 }
                    ?: settings().crossfadeMs * 2
                val duration = TransitionEnvelope.overlapMs(settings().crossfadeMs,
                    (owner.duration - owner.currentPosition).coerceAtLeast(0) * 2, incomingDuration)
                beginOverlap(incoming, duration, null)
            }
        }
    }

    private fun copyShuffleOrder(from: ExoPlayer, to: ExoPlayer) {
        if (!from.shuffleModeEnabled || from.currentTimeline.isEmpty) return
        val timeline = from.currentTimeline
        val order = mutableListOf<Int>()
        var index = timeline.getFirstWindowIndex(true)
        while (index != C.INDEX_UNSET && order.size < timeline.windowCount) {
            order.add(index); index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        if (order.size == from.mediaItemCount) to.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), 0L))
    }

    /**
     * Crossfades within the playing song when the seek crossfade is enabled: the spare
     * decoder starts the same queue at the target position while the old position fades
     * out. Returns false when the overlap is unavailable so the caller seeks directly.
     */
    private fun seekOverlap(positionMs: Long): Boolean {
        val owner = active
        val config = settings()
        if (config.seekCrossfadeMs <= 0 || sleeping || tail != null || !owner.isPlaying) return false
        val index = owner.currentMediaItemIndex
        if (index == C.INDEX_UNSET || owner.currentMediaItem == null) return false
        val duration = owner.duration
        if (duration == C.TIME_UNSET || duration <= 0) return false
        val target = (if (positionMs == C.TIME_UNSET) 0 else positionMs).coerceIn(0, duration)
        if (abs(target - owner.currentPosition) < 500) return false
        val length = TransitionEnvelope.overlapMs(config.seekCrossfadeMs,
            (duration - owner.currentPosition).coerceAtLeast(0) * 2, (duration - target).coerceAtLeast(0) * 2)
        if (length <= 0) return false
        cancelPreparation()
        val incoming = spare()
        incoming.pause()
        incoming.volume = 0f
        incoming.pauseAtEndOfMediaItems = false
        incoming.repeatMode = owner.repeatMode
        incoming.shuffleModeEnabled = owner.shuffleModeEnabled
        incoming.playbackParameters = owner.playbackParameters
        incoming.setMediaItems(List(owner.mediaItemCount, owner::getMediaItemAt), index, target)
        copyShuffleOrder(owner, incoming)
        incoming.prepare()
        beginOverlap(incoming, length, null)
        return true
    }

    private fun beginOverlap(incoming: ExoPlayer, duration: Long, recipe: AutomixTransition?) {
        if (overlap != null || desiredPlaying != true || sleeping) return
        val outgoing = active
        trace("begin recipe=${recipe != null} length=$duration outPosition=${outgoing.currentPosition}" +
            " inPosition=${incoming.currentPosition} outDuration=${outgoing.duration} inDuration=${incoming.duration}" +
            " outStart=${recipe?.outgoingStartMs} inStart=${recipe?.incomingStartMs} speed=${incoming.playbackParameters.speed}")
        // Every overlap owns the gains from full envelope: a stale fade must neither
        // silence the mix nor pause the new head when it finishes.
        ramp?.cancel(); ramp = null; envelope = 1f
        finalFadeStarted = false
        lastExplicitSeekPositionMs = null; skippedRecipeForSeek = false
        outgoing.pauseAtEndOfMediaItems = true
        tail = outgoing
        active = incoming
        headGain = 0f; tailGain = 1f
        preparedFor = null; preparedIndex = C.INDEX_UNSET; preparedRecipe = null
        incoming.play()
        setPlayer(incoming)
        applyVolumes()
        overlap = scope.launch(start = CoroutineStart.LAZY) {
            val start = incoming.currentPosition
            while (isActive && active === incoming) {
                if (incoming.playerError != null) {
                    // Keep the requested song and its error visible; do not silently restore a
                    // different item or allow its outgoing tail to keep playing indefinitely.
                    break
                }
                val elapsed = ((incoming.currentPosition - start) / (recipe?.incomingSpeed ?: 1f)).toLong()
                val gain = TransitionEnvelope.gain(0f, 1f, elapsed, duration)
                if (incoming.isPlaying) { headGain = gain; tailGain = 1f - gain; applyVolumes() }
                if (gain >= 1f || (outgoing.playbackState == Player.STATE_ENDED && incoming.isPlaying)) break
                delay(20)
            }
            outgoing.pause(); outgoing.stop(); outgoing.clearMediaItems(); outgoing.pauseAtEndOfMediaItems = false
            tail = null; headGain = 1f; tailGain = 0f; overlap = null
            if (recipe != null && active === incoming) incoming.playbackParameters = PlaybackParameters.DEFAULT
            applyVolumes(); invalidateState()
        }
        overlap?.start()
    }

    private fun spare(): ExoPlayer = if (active === engines[0]) engines[1] else engines[0]
    private fun applyVolumes() {
        active.volume = userVolume * envelope * headGain * duckGain
        tail?.volume = userVolume * envelope * tailGain * duckGain
    }
    private fun cancelPreparation() {
        preparation?.cancel(); preparation = null
        preparedFor = null; preparedIndex = C.INDEX_UNSET; preparedRecipe = null
        if (tail == null) spare().run { pause(); stop(); clearMediaItems() }
    }
    private fun stopTail() {
        overlap?.cancel(); overlap = null
        tail?.run { pause(); stop(); clearMediaItems(); pauseAtEndOfMediaItems = false }
        tail = null; headGain = 1f; tailGain = 0f
        active.playbackParameters = PlaybackParameters.DEFAULT
        applyVolumes()
    }
    private fun cancelTransition() { cancelPreparation(); stopTail() }
    private fun pauseImmediately(abandonFocus: Boolean = true) {
        startGeneration++
        starting?.cancel(); starting = null
        ramp?.cancel(); ramp = null; desiredPlaying = false
        active.pause(); cancelTransition(); envelope = 1f; applyVolumes()
        if (abandonFocus) focus.release()
        invalidateState()
    }
    fun hasActiveAudioOrPendingPlayback(): Boolean = engines.any {
        it.playWhenReady && it.playbackState != Player.STATE_IDLE && it.playbackState != Player.STATE_ENDED
    }
    fun setSleepFading(value: Boolean) {
        sleeping = value
        if (value) {
            cancelPreparation(); ramp?.cancel(); ramp = null
        } else if (active.isPlaying) {
            if (desiredPlaying == true) rampTo(1f, settings().fadeInMs) { } else fadeToStop(stop = false)
        }
    }
    /** Sleep already faded the master volume; do not start a second fade after locking. */
    fun stopImmediately() { pauseImmediately(); active.stop() }

    /** Development diagnostics contain timing/flags only, never identities or media payloads. */
    private fun trace(message: String) { if (BuildConfig.DEBUG) Log.d("PlayTransitions", message) }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        scope.cancel(); focus.release(); engines.forEach(ExoPlayer::release)
        return Futures.immediateVoidFuture()
    }
}
