package org.vork.music

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.IOException
import java.util.concurrent.Executors

/**
 * The player, as a media session: it plays on with the screen off, and the
 * notification, lock screen, headset and car talk to it. Songs play straight
 * from vØrk storage in pieces ([VorkDataSource]): vorkd fetches the pieces that
 * aren't on this phone from the library's other nodes as they're needed.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(VorkDataSource.Factory(), extractors))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setBitmapLoader(CacheBitmapLoader(CoverLoader()))
            .setCallback(Callback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiped away from the recent apps: stop unless it's playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Songs come from a controller without where they are (Media3 leaves that out): put it back. */
    private object Callback : MediaSession.Callback {
        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { item -> item.requestMetadata.mediaUri?.let { item.buildUpon().setUri(it).build() } ?: item }.toMutableList())
    }
}

/**
 * A song's bytes from vØrk storage (`vork://track?ns=…&cap=…`), read in pieces
 * as the player asks for them; seeking reads from there.
 */
@OptIn(UnstableApi::class)
class VorkDataSource : BaseDataSource(true) {
    private var uri: Uri? = null
    private var ns = ""
    private var cap = ""
    private var size = 0L
    private var position = 0L
    private var remaining = 0L
    private var piece: ByteArray? = null
    private var pieceAt = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val u = dataSpec.uri
        ns = u.getQueryParameter("ns") ?: throw IOException("not a vØrk storage song: $u")
        cap = u.getQueryParameter("cap") ?: throw IOException("not a vØrk storage song: $u")
        size = cap.substringAfterLast(':').toLongOrNull() ?: throw IOException("no size in $cap")
        if (dataSpec.position > size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        uri = u
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, size - position) else size - position
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        var p = piece
        if (p == null || position < pieceAt || position >= pieceAt + p.size) {
            p = Core.readRange(ns, cap, position, minOf(PIECE, size - position), WAIT_MS)
                ?: throw IOException(if (Vork.problem != null) "vØrk storage isn't reachable" else "this song isn't reachable now (no node that has it answered)")
            if (p.isEmpty()) return C.RESULT_END_OF_INPUT
            piece = p
            pieceAt = position
        }
        val from = (position - pieceAt).toInt()
        val n = minOf(length.toLong(), remaining, (p.size - from).toLong()).toInt()
        System.arraycopy(p, from, buffer, offset, n)
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        piece = null
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = VorkDataSource()
    }

    companion object {
        /** What one read asks vorkd for. */
        const val PIECE = 256L shl 10
        /** How long a piece may take to come from another node. */
        const val WAIT_MS = 60_000L
    }
}

/** Album covers for the notification and lock screen (`vork://cover?…`). */
@OptIn(UnstableApi::class)
class CoverLoader : BitmapLoader {
    private val pool = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(2))

    override fun supportsMimeType(mimeType: String): Boolean = Util.isBitmapFactorySupportedMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        pool.submit<Bitmap> { Covers.decode(data, SIDE) ?: throw IOException("not a picture") }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        pool.submit<Bitmap> { Cover.of(uri)?.let { Covers.loadNow(it, SIDE) } ?: throw IOException("no cover at $uri") }

    companion object {
        const val SIDE = 720
    }
}
