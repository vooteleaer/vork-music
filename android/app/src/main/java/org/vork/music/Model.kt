package org.vork.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A song in a library, as the core reads it (its tags, else its path). */
data class Track(
    val ns: String,
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val albumArtist: String?,
    val number: Int?,
    val disc: Int?,
    val year: Int?,
    val genre: String?,
    /** Seconds, if known. */
    val duration: Long?,
    val cap: String,
    val mime: String,
    val favorite: Boolean,
) {
    val key: String get() = "$ns|$id"

    /** The bytes it has: the read-cap's last field. */
    val size: Long get() = cap.substringAfterLast(':').toLongOrNull() ?: -1

    fun uri(): Uri = Uri.Builder().scheme(SCHEME).authority("track").appendQueryParameter("ns", ns).appendQueryParameter("cap", cap).build()

    /** For the player: what it plays and shows (the notification, lock screen, car). */
    fun mediaItem(album: Album?): MediaItem {
        val uri = uri()
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist.ifEmpty { null })
            .setAlbumTitle(album?.name ?: this.album)
            .setAlbumArtist(album?.artist ?: albumArtist)
            .setTrackNumber(number)
            .setDiscNumber(disc)
            .setRecordingYear(year)
            .setGenre(genre)
            .setArtworkUri(album?.cover?.uri())
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        return MediaItem.Builder()
            .setMediaId(key)
            .setUri(uri)
            .setMimeType(mime.ifEmpty { null })
            .setMediaMetadata(meta)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .build()
    }

    companion object {
        const val SCHEME = "vork"

        fun of(j: JSONObject) = Track(
            ns = j.getString("ns"),
            id = j.getString("id"),
            title = j.optString("title"),
            artist = j.optString("artist"),
            album = j.str("album"),
            albumArtist = j.str("albumartist"),
            number = j.int("track"),
            disc = j.int("disc"),
            year = j.int("year"),
            genre = j.str("genre"),
            duration = j.int("duration")?.toLong(),
            cap = j.getString("cap"),
            mime = j.optString("mime"),
            favorite = j.optBoolean("favorite"),
        )
    }
}

private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).ifEmpty { null }
private fun JSONObject.int(k: String): Int? = if (isNull(k) || !has(k)) null else optInt(k).takeIf { it > 0 }

/** An album's cover: an image record's small block and its whole content. */
data class Cover(val ns: String, val thumb: String, val full: String) {
    fun uri(): Uri = Uri.Builder().scheme(Track.SCHEME).authority("cover").appendQueryParameter("ns", ns).appendQueryParameter("thumb", thumb).appendQueryParameter("full", full).build()

    companion object {
        fun of(u: Uri): Cover? {
            if (u.scheme != Track.SCHEME || u.authority != "cover") return null
            return Cover(u.getQueryParameter("ns") ?: return null, u.getQueryParameter("thumb") ?: return null, u.getQueryParameter("full") ?: return null)
        }
    }
}

/** An album: tracks with one name and album artist, from any of the libraries shown. */
data class Album(
    val key: String,
    val name: String,
    val artist: String,
    val year: Int?,
    val cover: Cover?,
    val tracks: List<Track>,
) {
    val duration: Long get() = tracks.sumOf { it.duration ?: 0 }
    val libraries: Set<String> get() = tracks.map { it.ns }.toSet()

    companion object {
        fun of(j: JSONObject): Album {
            val t = j.optJSONArray("tracks") ?: JSONArray()
            val c = j.optJSONObject("cover")
            return Album(
                key = j.optString("key"),
                name = j.optString("name"),
                artist = j.optString("artist"),
                year = j.int("year"),
                cover = c?.let { Cover(it.getString("ns"), it.getString("thumb"), it.getString("full")) },
                tracks = (0 until t.length()).map { Track.of(t.getJSONObject(it)) },
            )
        }
    }
}

/** A library this app may use. */
data class Lib(val name: String, val writable: Boolean)

/**
 * The collection: the libraries this app may use (each shown or not), and
 * the albums in those shown, read library by library so each shows as soon
 * as it's read.
 */
class Collection(private val context: Context) {
    var libraries by mutableStateOf<List<Lib>>(emptyList())
    val hidden = mutableStateListOf<String>().apply { addAll(prefs().getStringSet("hidden", emptySet())!!) }
    /** Each library's albums as last read. */
    var byLibrary by mutableStateOf<Map<String, List<Album>>>(emptyMap())
    /** The albums of the libraries shown: one album in two libraries is one. */
    val albums: List<Album> by derivedStateOf { merge(libraries.map { it.name }.filter { it !in hidden }.flatMap { byLibrary[it].orEmpty() }) }
    val tracks: List<Track> by derivedStateOf { albums.flatMap { it.tracks } }
    /** "Reading libraries…": which, how many of how many; null when done. */
    var reading by mutableStateOf<String?>(null)
    var progress by mutableStateOf(0f)
    var error by mutableStateOf("")
    private var counts = JSONObject()

    private fun prefs() = context.getSharedPreferences("collection", Context.MODE_PRIVATE)

    val shown: List<String> get() = libraries.map { it.name }.filter { it !in hidden }

    fun toggle(name: String) {
        if (name in hidden) hidden.remove(name) else hidden.add(name)
        prefs().edit().putStringSet("hidden", hidden.toSet()).apply()
    }

    fun songs(ns: String): Int = byLibrary[ns]?.sumOf { it.tracks.size } ?: 0

    fun album(key: String): Album? = albums.find { it.key == key }

    fun albumOf(t: Track): Album? = albums.find { a -> a.tracks.any { it.key == t.key } }

    fun track(key: String): Track? = tracks.find { it.key == key }

    /** Reads every library this app may use (hidden ones too, to say what's in them). */
    suspend fun load() {
        val answer = core { Core.libraries() }
        if (answer.trimStart().startsWith("{")) {
            error = JSONObject(answer).optString("error")
            return
        }
        error = ""
        val a = JSONArray(answer)
        libraries = (0 until a.length()).map { a.getJSONObject(it) }.map { Lib(it.getString("name"), it.optBoolean("writable")) }
        // The shown ones first: they're what the user waits for.
        val names = libraries.map { it.name }.sortedBy { it in hidden }
        val read = HashMap<String, List<Album>>(byLibrary)
        read.keys.retainAll(names.toSet())
        names.forEachIndexed { i, ns ->
            reading = "Reading libraries… $ns (${i + 1} of ${names.size})"
            progress = i.toFloat() / names.size
            val r = core { Core.albums(ns) }
            if (r.trimStart().startsWith("{")) {
                error = "$ns: " + JSONObject(r).optString("error")
            } else {
                val list = JSONArray(r)
                read[ns] = (0 until list.length()).map { Album.of(list.getJSONObject(it)) }
                byLibrary = HashMap(read)
            }
        }
        byLibrary = HashMap(read)
        counts = JSONObject(core { Core.changeCounts(JSONArray(names).toString()) })
        reading = null
    }

    /** What to wait on (`{library: count}`): every library, with its count in `known` (the last wait's), else its read's. */
    fun watching(known: JSONObject): String {
        val w = JSONObject()
        for (l in libraries) {
            if (known.has(l.name)) w.put(l.name, known.getLong(l.name)) else if (counts.has(l.name)) w.put(l.name, counts.getLong(l.name))
        }
        return w.toString()
    }

    /** Stars a track here at once, as the core was asked to. */
    fun starred(t: Track, on: Boolean) {
        byLibrary = byLibrary.mapValues { (_, albums) ->
            albums.map { a -> if (a.tracks.any { it.key == t.key }) a.copy(tracks = a.tracks.map { if (it.key == t.key) it.copy(favorite = on) else it }) else a }
        }
    }

    companion object {
        fun merge(all: List<Album>): List<Album> {
            val by = LinkedHashMap<String, Album>()
            for (a in all) {
                val had = by[a.key]
                by[a.key] = if (had == null) a else had.copy(
                    cover = had.cover ?: a.cover,
                    year = had.year ?: a.year,
                    tracks = (had.tracks + a.tracks).sortedWith(compareBy<Track>({ it.disc ?: 1 }, { it.number ?: Int.MAX_VALUE }, { it.title.lowercase() })),
                )
            }
            return by.values.sortedWith(compareBy<Album>({ it.key.isEmpty() }, { sortName(it.artist) }, { it.year ?: 0 }, { it.name.lowercase() }))
        }

        fun sortName(s: String): String = s.trim().lowercase().removePrefix("the ")
    }
}

/** Artists: the album artists, each with their albums. */
data class Artist(val name: String, val albums: List<Album>) {
    val cover: Cover? get() = albums.firstNotNullOfOrNull { it.cover }

    companion object {
        fun of(albums: List<Album>): List<Artist> = albums.filter { it.key.isNotEmpty() }
            .groupBy { it.artist.lowercase() }
            .map { (_, list) -> Artist(list.first().artist, list.sortedBy { it.year ?: 0 }) }
            .sortedBy { Collection.sortName(it.name) }
    }
}

/**
 * Covers as bitmaps: read through the core, decoded no bigger than shown,
 * kept in memory while there's room.
 */
object Covers {
    private const val MAX = 16L shl 20

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val reads = Dispatchers.IO.limitedParallelism(4)

    fun cached(c: Cover, side: Int): Bitmap? = cache.get(key(c, side))

    private fun key(c: Cover, side: Int) = "${c.full}@$side"

    /** The cover at about `side` pixels: its small block when that's enough, else the whole picture. */
    suspend fun load(c: Cover, side: Int): Bitmap? {
        cache.get(key(c, side))?.let { return it }
        val caps = if (side <= 320) listOf(c.thumb, c.full) else listOf(c.full, c.thumb)
        for (cap in caps.distinct()) {
            val bytes = withContext(reads) { Core.read(c.ns, cap, MAX) } ?: continue
            val b = withContext(Dispatchers.Default) { decode(bytes, side) } ?: continue
            cache.put(key(c, side), b)
            return b
        }
        return null
    }

    /** Blocking, for the player's notification (off the main thread). */
    fun loadNow(c: Cover, side: Int): Bitmap? {
        cache.get(key(c, side))?.let { return it }
        for (cap in listOf(c.full, c.thumb).distinct()) {
            val bytes = Core.read(c.ns, cap, MAX) ?: continue
            val b = decode(bytes, side) ?: continue
            cache.put(key(c, side), b)
            return b
        }
        return null
    }

    fun decode(bytes: ByteArray, side: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

fun duration(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds / 60 % 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
