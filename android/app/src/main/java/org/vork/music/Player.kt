package org.vork.music

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/**
 * The app's side of the player: a controller of [PlaybackService]'s
 * session, its state as Compose state.
 */
class Playing {
    private var controller: MediaController? = null
    var current by mutableStateOf<MediaItem?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeat by mutableStateOf(Player.REPEAT_MODE_OFF)
        private set
    /** Milliseconds. */
    var position by mutableStateOf(0L)
        private set
    var length by mutableStateOf(0L)
        private set
    /** The queue in play order (shuffled as it will play). */
    var queue by mutableStateOf<List<MediaItem>>(emptyList())
        private set
    var index by mutableStateOf(0)
        private set
    var error by mutableStateOf<String?>(null)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()

        override fun onPlayerError(e: PlaybackException) {
            // The deepest reason with words (ours, from VorkDataSource).
            error = generateSequence(e as Throwable) { it.cause }.lastOrNull { !it.message.isNullOrEmpty() }?.message ?: e.errorCodeName
        }
    }

    fun connect(context: Context) {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.onSuccess { c ->
                controller = c
                c.addListener(listener)
                refresh()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    /** Called often while playing: where it is. */
    fun tick() {
        val c = controller ?: return
        position = c.currentPosition
        length = c.duration.takeIf { it > 0 } ?: length
    }

    private fun refresh() {
        val c = controller ?: return
        current = c.currentMediaItem
        playing = c.isPlaying
        buffering = c.playbackState == Player.STATE_BUFFERING
        shuffle = c.shuffleModeEnabled
        repeat = c.repeatMode
        index = c.currentMediaItemIndex
        val tl = c.currentTimeline
        queue = if (tl.isEmpty) emptyList() else buildList {
            var i = tl.getFirstWindowIndex(c.shuffleModeEnabled)
            while (i != androidx.media3.common.C.INDEX_UNSET) {
                add(c.getMediaItemAt(i))
                i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
            }
        }
        position = c.currentPosition
        length = c.duration.takeIf { it > 0 } ?: 0
    }

    /** Plays `tracks` from `start` (shuffled from a random one when `shuffled`). */
    fun play(collection: Collection, tracks: List<Track>, start: Int = 0, shuffled: Boolean = false) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        error = null
        val items = tracks.map { it.mediaItem(collection.albumOf(it)) }
        c.shuffleModeEnabled = shuffled
        c.setMediaItems(items, if (shuffled) tracks.indices.random() else start, 0)
        c.prepare()
        c.play()
    }

    /** Next after the current one (or first, when nothing plays). */
    fun playNext(collection: Collection, t: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(collection, listOf(t))
        c.addMediaItem(c.nextMediaItemIndex.takeIf { it >= 0 } ?: c.mediaItemCount, t.mediaItem(collection.albumOf(t)))
    }

    fun addToQueue(collection: Collection, t: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(collection, listOf(t))
        c.addMediaItem(t.mediaItem(collection.albumOf(t)))
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
            return
        }
        // After an error the player stops: start it again where it was.
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.playbackState == Player.STATE_ENDED) c.seekTo(c.currentMediaItemIndex, 0)
        error = null
        c.play()
    }

    fun next() = controller?.seekToNext()
    fun previous() = controller?.seekToPrevious()
    fun seek(ms: Long) {
        controller?.seekTo(ms)
        position = ms
    }

    fun jump(item: MediaItem) {
        val c = controller ?: return
        val i = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == item.mediaId } ?: return
        c.seekTo(i, 0)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    /** Off → all → one → off. */
    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }
}
