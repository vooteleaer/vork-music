package org.vork.music

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val playing = Playing()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(this)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) { App(playing) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        playing.connect(this)
    }

    override fun onStop() {
        playing.release()
        super.onStop()
    }
}

enum class Tab(val label: String) { Albums("Albums"), Artists("Artists"), Songs("Songs") }

/** Where the user is: a tab, or an album or artist opened from one. */
sealed interface Screen {
    data class Of(val tab: Tab) : Screen
    data class AlbumOf(val key: String) : Screen
    data class ArtistOf(val name: String) : Screen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(playing: Playing) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val collection = remember { Collection(context) }
    var tab by remember { mutableStateOf(Tab.Albums) }
    var stack by remember { mutableStateOf(listOf<Screen>()) }
    var nowPlaying by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf<String?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var reloads by remember { mutableStateOf(0) }
    val letIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { reloads++ }

    LaunchedEffect(reloads) { collection.load() }
    // Changes from elsewhere (a new album, a star on another device): read
    // again the moment vorkd says a library changed, while on screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var known = JSONObject()
            while (true) {
                val answer = JSONObject(core { Core.waitChange(collection.watching(known), 60_000) })
                known = answer.optJSONObject("known") ?: JSONObject()
                if (answer.optBoolean("changed") && Vork.problem == null) {
                    snapshotFlow { collection.reading }.first { it == null }
                    collection.load()
                }
            }
        }
    }
    // Where the song is, while one plays.
    LaunchedEffect(playing.playing) {
        while (playing.playing) {
            playing.tick()
            delay(500)
        }
    }

    val star: (Track) -> Unit = { t ->
        val lib = collection.libraries.find { it.name == t.ns }
        if (lib?.writable != true) {
            collection.error = "${t.ns} is read only here: vØrk storage can let Music change it (Choose libraries…)"
        } else {
            val on = !t.favorite
            collection.starred(t, on)
            scope.launch {
                val e = core { Core.setFavorite(t.ns, t.id, on) }
                if (e.isNotEmpty()) {
                    collection.starred(t, !on)
                    collection.error = e
                }
            }
        }
    }
    val actions = TrackActions(
        play = { list, i -> playing.play(collection, list, i) },
        shuffle = { list -> playing.play(collection, list, shuffled = true) },
        playNext = { playing.playNext(collection, it) },
        enqueue = { playing.addToQueue(collection, it) },
        star = star,
        openAlbum = { a -> stack = stack + Screen.AlbumOf(a.key) },
        openArtist = { name -> stack = stack + Screen.ArtistOf(name) },
    )

    if (nowPlaying && playing.current != null) {
        BackHandler { nowPlaying = false }
        NowPlaying(playing, collection, actions, close = { nowPlaying = false })
        return
    }
    val screen = stack.lastOrNull() ?: Screen.Of(tab)
    BackHandler(enabled = stack.isNotEmpty() || search != null) {
        if (stack.isNotEmpty()) stack = stack.dropLast(1) else search = null
    }

    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet { Drawer(collection, letIn = { letIn.launch(Vork.letIn()) }) }
    }) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        val q = search
                        if (q != null && stack.isEmpty()) {
                            TextField(
                                value = q,
                                onValueChange = { search = it },
                                placeholder = { Text("Search ${tab.label.lowercase()}") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text(
                                when (screen) {
                                    is Screen.Of -> screen.tab.label
                                    is Screen.AlbumOf -> collection.album(screen.key)?.name ?: "Album"
                                    is Screen.ArtistOf -> screen.name
                                },
                            )
                        }
                    },
                    navigationIcon = {
                        if (stack.isNotEmpty()) {
                            IconButton(onClick = { stack = stack.dropLast(1) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        } else {
                            IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Libraries") }
                        }
                    },
                    actions = {
                        if (stack.isEmpty()) {
                            if (search == null) {
                                IconButton(onClick = { search = "" }) { Icon(Icons.Filled.Search, "Search") }
                            } else {
                                IconButton(onClick = { search = null }) { Icon(Icons.Filled.Close, "Stop searching") }
                            }
                        }
                    },
                )
            },
            bottomBar = {
                Column {
                    if (playing.current != null) MiniPlayer(playing, open = { nowPlaying = true })
                    NavigationBar {
                        for ((t, icon) in listOf(Tab.Albums to Icons.Filled.Album, Tab.Artists to Icons.Filled.Person, Tab.Songs to Icons.Filled.MusicNote)) {
                            NavigationBarItem(
                                selected = tab == t && stack.isEmpty(),
                                onClick = { tab = t; stack = emptyList() },
                                icon = { Icon(icon, null) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                }
            },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                collection.reading?.let {
                    LinearProgressIndicator(progress = { collection.progress }, modifier = Modifier.fillMaxWidth())
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                }
                if (Vork.problem != null) {
                    Problem(Vork.problem!!, letIn = { letIn.launch(Vork.letIn()) }, retry = { reloads++ })
                    return@Column
                }
                (playing.error ?: collection.error.ifEmpty { null })?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clickable { playing.error = null; collection.error = "" })
                }
                val q = search?.trim()?.lowercase().orEmpty()
                when (screen) {
                    is Screen.AlbumOf -> collection.album(screen.key)?.let { AlbumScreen(it, collection, playing, actions) }
                    is Screen.ArtistOf -> ArtistScreen(screen.name, collection, actions)
                    is Screen.Of -> when (screen.tab) {
                        Tab.Albums -> AlbumGrid(collection.albums.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.artist.lowercase().contains(q) }, actions.openAlbum, empty = emptyText(collection))
                        Tab.Artists -> ArtistList(Artist.of(collection.albums).filter { q.isEmpty() || it.name.lowercase().contains(q) }, actions.openArtist)
                        Tab.Songs -> SongList(
                            collection.tracks.filter { t -> q.isEmpty() || listOfNotNull(t.title, t.artist, t.album).any { it.lowercase().contains(q) } }.sortedBy { it.title.lowercase() },
                            collection,
                            playing,
                            actions,
                        )
                    }
                }
            }
        }
    }
}

private fun emptyText(c: Collection): String = when {
    c.reading != null -> ""
    c.libraries.isEmpty() -> "Music may use no libraries yet. Choose them in the menu (Choose libraries…)."
    else -> "No music in the libraries shown. Put songs into one (from a computer: copy them into its folder), and they show here."
}

/** vØrk storage can't be used yet: why, and what to do. */
@Composable
fun Problem(p: Vork.Problem, letIn: () -> Unit, retry: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (p) {
            Vork.Problem.NOT_INSTALLED -> {
                Text("vØrk storage isn't installed", style = MaterialTheme.typography.titleMedium)
                Text("Music plays the songs in your vØrk storage libraries. Install the vØrk app and the vØrk storage app first.")
            }
            Vork.Problem.NOT_LET_IN -> {
                Text("Let Music use vØrk storage", style = MaterialTheme.typography.titleMedium)
                Text("vØrk storage asks which of your libraries Music may play, and whether it may star songs in them.")
                Button(onClick = letIn) { Text("Choose libraries…") }
            }
            Vork.Problem.NOT_RUNNING -> {
                Text("vØrk storage isn't running", style = MaterialTheme.typography.titleMedium)
                Text("Open vØrk storage: it starts once the vØrk app's node is up.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Vork.open(context)?.let { i -> OutlinedButton(onClick = { context.startActivity(i) }) { Text("Open vØrk storage") } }
                    Button(onClick = retry) { Text("Try again") }
                }
            }
        }
    }
}

/** The libraries: Announces above (in the vØrk storage app), each library with music a toggle. */
@Composable
fun Drawer(collection: Collection, letIn: () -> Unit) {
    val context = LocalContext.current
    LazyColumn(Modifier.padding(vertical = 8.dp)) {
        item {
            OutlinedButton(onClick = { Vork.open(context)?.let { context.startActivity(it) } }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Text("Announces") }
            Text("Libraries", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
        }
        // Libraries without music aren't listed (unless hidden, or not read yet).
        val listed = collection.libraries.filter { collection.songs(it.name) > 0 || it.name in collection.hidden || it.name !in collection.byLibrary }
        items(listed, key = { it.name }) { l ->
            Row(Modifier.fillMaxWidth().clickable { collection.toggle(l.name) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = l.name !in collection.hidden, onCheckedChange = { collection.toggle(l.name) })
                Column {
                    Text(l.name)
                    val access = if (l.writable) "read and write" else "read only"
                    val n = collection.songs(l.name)
                    Text(if (l.name in collection.byLibrary) "$access · $n song(s)" else access, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Music sees only the libraries vØrk storage let it in to.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            Box(Modifier.padding(16.dp)) { OutlinedButton(onClick = letIn) { Text("Choose libraries…") } }
        }
    }
}

/** What can be done with songs, wherever they're listed. */
class TrackActions(
    val play: (List<Track>, Int) -> Unit,
    val shuffle: (List<Track>) -> Unit,
    val playNext: (Track) -> Unit,
    val enqueue: (Track) -> Unit,
    val star: (Track) -> Unit,
    val openAlbum: (Album) -> Unit,
    val openArtist: (String) -> Unit,
)
