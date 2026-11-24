package org.vork.music

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/** An album's cover, read when shown; a record sleeve's mark until then (or without one). */
@Composable
fun CoverImage(cover: Cover?, size: Dp, modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    var bitmap by remember(cover, px) { mutableStateOf(cover?.let { Covers.cached(it, px) }) }
    LaunchedEffect(cover, px) {
        if (bitmap == null && cover != null) bitmap = Covers.load(cover, px)
    }
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Filled.Album, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxSize(0.4f))
        }
    }
}

@Composable
fun AlbumGrid(albums: List<Album>, open: (Album) -> Unit, empty: String = "", header: (@Composable () -> Unit)? = null) {
    if (albums.isEmpty() && header == null) {
        if (empty.isNotEmpty()) Text(empty, modifier = Modifier.padding(24.dp))
        return
    }
    LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        header?.let { h -> item(span = { GridItemSpan(maxLineSpan) }) { h() } }
        items(albums, key = { it.key }) { a ->
            Column(Modifier.clickable { open(a) }) {
                CoverImage(a.cover, 180.dp, Modifier.fillMaxWidth().aspectRatio(1f))
                Text(a.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Text(listOfNotNull(a.artist.ifEmpty { null }, a.year?.toString()).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun ArtistList(artists: List<Artist>, open: (String) -> Unit) {
    LazyColumn {
        items(artists, key = { it.name.lowercase() }) { a ->
            Row(Modifier.fillMaxWidth().clickable { open(a.name) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverImage(a.cover, 56.dp, Modifier.size(56.dp))
                Column(Modifier.padding(start = 16.dp)) {
                    Text(a.name, style = MaterialTheme.typography.titleMedium)
                    val songs = a.albums.sumOf { it.tracks.size }
                    Text("${a.albums.size} album(s) · $songs song(s)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun ArtistScreen(name: String, collection: Collection, actions: TrackActions) {
    val artist = Artist.of(collection.albums).find { it.name.equals(name, true) } ?: return
    val tracks = artist.albums.flatMap { it.tracks }
    AlbumGrid(artist.albums, actions.openAlbum, header = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { actions.play(tracks, 0) }) { Icon(Icons.Filled.PlayArrow, null); Text("Play") }
            OutlinedButton(onClick = { actions.shuffle(tracks) }) { Icon(Icons.Filled.Shuffle, null); Text("Shuffle") }
        }
    })
}

@Composable
fun AlbumScreen(album: Album, collection: Collection, playing: Playing, actions: TrackActions) {
    val discs = album.tracks.mapNotNull { it.disc }.toSet().size > 1
    LazyColumn {
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CoverImage(album.cover, 280.dp, Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f))
                Text(album.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
                if (album.artist.isNotEmpty()) {
                    Text(album.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { actions.openArtist(album.artist) })
                }
                val facts = listOfNotNull(album.year?.toString(), "${album.tracks.size} song(s)", album.duration.takeIf { it > 0 }?.let { duration(it) }, album.libraries.joinToString(", "))
                Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                    Button(onClick = { actions.play(album.tracks, 0) }) { Icon(Icons.Filled.PlayArrow, null); Text("Play") }
                    OutlinedButton(onClick = { actions.shuffle(album.tracks) }) { Icon(Icons.Filled.Shuffle, null); Text("Shuffle") }
                }
            }
        }
        itemsIndexed(album.tracks, key = { _, t -> t.key }) { i, t ->
            if (discs && (i == 0 || album.tracks[i - 1].disc != t.disc)) {
                Text("Disc ${t.disc ?: 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
            }
            TrackRow(
                t,
                lead = { Text(t.number?.toString() ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(32.dp)) },
                sub = t.artist.takeIf { it.isNotEmpty() && !it.equals(album.artist, true) },
                current = playing.current?.mediaId == t.key,
                collection = collection,
                actions = actions,
                showAlbum = false,
                onClick = { actions.play(album.tracks, i) },
            )
        }
    }
}

@Composable
fun SongList(tracks: List<Track>, collection: Collection, playing: Playing, actions: TrackActions) {
    var favourites by remember { mutableStateOf(false) }
    val shown = if (favourites) tracks.filter { it.favorite } else tracks
    LazyColumn {
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { actions.shuffle(shown) }, enabled = shown.isNotEmpty()) { Icon(Icons.Filled.Shuffle, null); Text("Shuffle all") }
                FilterChip(selected = favourites, onClick = { favourites = !favourites }, label = { Text("Starred") }, leadingIcon = { Icon(Icons.Filled.Star, null, Modifier.size(18.dp)) })
            }
        }
        itemsIndexed(shown, key = { _, t -> t.key }) { i, t ->
            TrackRow(
                t,
                lead = { CoverImage(collection.albumOf(t)?.cover, 48.dp, Modifier.size(48.dp)) },
                sub = listOfNotNull(t.artist.ifEmpty { null }, t.album).joinToString(" · "),
                current = playing.current?.mediaId == t.key,
                collection = collection,
                actions = actions,
                onClick = { actions.play(shown, i) },
            )
        }
    }
}

@Composable
fun TrackRow(
    t: Track,
    lead: @Composable () -> Unit,
    sub: String?,
    current: Boolean,
    collection: Collection,
    actions: TrackActions,
    showAlbum: Boolean = true,
    onClick: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        lead()
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                t.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (current) FontWeight.Bold else null,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!sub.isNullOrEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        t.duration?.let { Text(duration(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        IconButton(onClick = { actions.star(t) }) {
            Icon(if (t.favorite) Icons.Filled.Star else Icons.Filled.StarBorder, if (t.favorite) "Unstar" else "Star", tint = if (t.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Play next") }, onClick = { menu = false; actions.playNext(t) })
                DropdownMenuItem(text = { Text("Add to queue") }, onClick = { menu = false; actions.enqueue(t) })
                if (showAlbum) collection.albumOf(t)?.takeIf { it.key.isNotEmpty() }?.let { a ->
                    DropdownMenuItem(text = { Text("Go to album") }, onClick = { menu = false; actions.openAlbum(a) })
                }
                collection.albumOf(t)?.artist?.takeIf { it.isNotEmpty() }?.let { name ->
                    DropdownMenuItem(text = { Text("Go to artist") }, onClick = { menu = false; actions.openArtist(name) })
                }
            }
        }
    }
}

/** At the bottom while something plays: what, and play/pause and next. Tap for the whole player. */
@Composable
fun MiniPlayer(playing: Playing, open: () -> Unit) {
    val item = playing.current ?: return
    val m = item.mediaMetadata
    Surface(tonalElevation = 3.dp) {
        Column {
            val p = if (playing.length > 0) (playing.position.toFloat() / playing.length).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(2.dp))
            Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverImage(m.artworkUri?.let { Cover.of(it) }, 44.dp, Modifier.size(44.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(m.title?.toString() ?: "", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(m.artist?.toString() ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PlayPause(playing, Modifier)
                IconButton(onClick = { playing.next() }) { Icon(Icons.Filled.SkipNext, "Next") }
            }
        }
    }
}

@Composable
private fun PlayPause(playing: Playing, modifier: Modifier, big: Boolean = false) {
    val icon = if (playing.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow
    val label = if (playing.playing) "Pause" else "Play"
    Box(contentAlignment = Alignment.Center) {
        if (big) {
            FilledIconButton(onClick = { playing.toggle() }, modifier = modifier.size(72.dp)) { Icon(icon, label, Modifier.size(40.dp)) }
        } else {
            IconButton(onClick = { playing.toggle() }, modifier = modifier) { Icon(icon, label) }
        }
        // Waiting for pieces from another node: say so, so it doesn't look stuck.
        if (playing.buffering) CircularProgressIndicator(Modifier.size(if (big) 80.dp else 40.dp), strokeWidth = 2.dp)
    }
}

/** The whole player: the cover, where the song is, the buttons, and what plays next. */
@Composable
fun NowPlaying(playing: Playing, collection: Collection, actions: TrackActions, close: () -> Unit) {
    val item = playing.current ?: return
    val m = item.mediaMetadata
    val track = collection.track(item.mediaId)
    var dragging by remember { mutableStateOf<Float?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = close) { Icon(Icons.Filled.KeyboardArrowDown, "Close") }
                    Spacer(Modifier.weight(1f))
                    Text(m.albumTitle?.toString() ?: "", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable {
                        collection.albumOf(track ?: return@clickable)?.takeIf { it.key.isNotEmpty() }?.let { close(); actions.openAlbum(it) }
                    })
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.size(48.dp))
                }
                CoverImage(m.artworkUri?.let { Cover.of(it) }, 360.dp, Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f).padding(top = 8.dp))
                Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.title?.toString() ?: "", style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(m.artist?.toString() ?: "", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (track != null) {
                        IconButton(onClick = { actions.star(track) }) {
                            Icon(if (track.favorite) Icons.Filled.Star else Icons.Filled.StarBorder, if (track.favorite) "Unstar" else "Star", tint = if (track.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                val length = playing.length.coerceAtLeast(1)
                Slider(
                    value = dragging ?: (playing.position.toFloat() / length).coerceIn(0f, 1f),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { playing.seek((it * length).toLong()) }; dragging = null },
                    modifier = Modifier.padding(top = 12.dp),
                )
                Row(Modifier.fillMaxWidth()) {
                    val at = dragging?.let { (it * length).toLong() } ?: playing.position
                    Text(duration(at / 1000), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    Text(if (playing.length > 0) duration(playing.length / 1000) else "", style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    val on = MaterialTheme.colorScheme.primary
                    val off = MaterialTheme.colorScheme.onSurfaceVariant
                    IconButton(onClick = { playing.toggleShuffle() }) { Icon(Icons.Filled.Shuffle, if (playing.shuffle) "Shuffle on" else "Shuffle off", tint = if (playing.shuffle) on else off) }
                    IconButton(onClick = { playing.previous() }) { Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(36.dp)) }
                    PlayPause(playing, Modifier, big = true)
                    IconButton(onClick = { playing.next() }) { Icon(Icons.Filled.SkipNext, "Next", Modifier.size(36.dp)) }
                    IconButton(onClick = { playing.cycleRepeat() }) {
                        when (playing.repeat) {
                            Player.REPEAT_MODE_ONE -> Icon(Icons.Filled.RepeatOne, "Repeat this song", tint = on)
                            Player.REPEAT_MODE_ALL -> Icon(Icons.Filled.Repeat, "Repeat all", tint = on)
                            else -> Icon(Icons.Filled.Repeat, "Repeat off", tint = off)
                        }
                    }
                }
                playing.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                HorizontalDivider(Modifier.padding(top = 8.dp))
                Text("Up next", style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            }
        }
        val at = playing.queue.indexOfFirst { it.mediaId == item.mediaId }
        val next: List<MediaItem> = if (at >= 0) playing.queue.drop(at + 1) else emptyList()
        // A song can be queued twice: keyed by place.
        itemsIndexed(next.take(100), key = { i, q -> "$i:${q.mediaId}" }) { _, q ->
            val qm = q.mediaMetadata
            Row(Modifier.fillMaxWidth().clickable { playing.jump(q) }.padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverImage(qm.artworkUri?.let { Cover.of(it) }, 40.dp, Modifier.size(40.dp))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(qm.title?.toString() ?: "", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(qm.artist?.toString() ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (next.isEmpty()) item {
            Text(if (playing.repeat == Player.REPEAT_MODE_OFF) "Nothing after this song." else "Starts again from the first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
        }
    }
}
