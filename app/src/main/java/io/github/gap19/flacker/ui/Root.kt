package io.github.gap19.flacker.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gap19.flacker.AppModel
import io.github.gap19.flacker.Screen
import io.github.gap19.flacker.library.count

@Composable
fun FlackerRoot(model: AppModel) {
    val settings by model.settings.collectAsState()
    FlackerTheme(settings.theme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App(model) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(model: AppModel) {
    val settings by model.settings.collectAsState()
    val player by model.player.collectAsState()
    val albums by model.albums.collectAsState()
    val query by model.query.collectAsState()
    val scan by model.scan.collectAsState()
    val screen = model.screen
    val snackbar = remember { SnackbarHostState() }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) model.addRoot(uri)
    }
    val chooseFolder = {
        // Some devices ship without the system folder picker.
        runCatching { pickFolder.launch(null) }.onFailure {
            model.messages.trySend("This device does not provide the Android folder picker.")
        }
        Unit
    }
    // Media-session notifications are exempt from the notification permission, so it is never asked.
    LaunchedEffect(Unit) { for (m in model.messages) snackbar.showSnackbar(m) }
    var searching by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    // Leaving a screen (an album opened from the results, back, a tab) closes the keyboard.
    LaunchedEffect(screen) { keyboard?.hide(); focus.clearFocus() }
    BackHandler(enabled = model.stack.size > 1) { model.back() }
    BackHandler(enabled = model.stack.size <= 1 && (searching || query.isNotEmpty())) {
        model.query.value = ""; searching = false
    }
    // Each screen keeps its own scroll position while it is on the stack or is a tab.
    val saved = rememberSaveableStateHolder()
    val keys = remember { mutableSetOf<String>() }
    LaunchedEffect(model.stack.toList()) {
        val live = model.stack.map(::stateKey).toSet() + listOf("albums", "artists", "tracks", "favorites")
        (keys - live).forEach(saved::removeState)
        keys.retainAll(live)
    }

    when (screen) {
        Screen.Focus -> return Box(Modifier.fillMaxSize()) {
            FocusScreen(model, player, settings)
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
        Screen.Now -> return Box(Modifier.fillMaxSize()) {
            NowScreen(model, player, settings)
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
        is Screen.Gallery -> {
            val album = model.album(screen.key)
            if (album == null) LaunchedEffect(screen) { model.back() }
            else return Box(Modifier.fillMaxSize()) {
                GalleryScreen(model, album)
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
            }
            return
        }
        else -> {}
    }

    if (settings.roots.isEmpty() && screen != Screen.Preferences) {
        return Box(Modifier.fillMaxSize()) {
            EmptyState(
                "Your collection, from start to finish",
                "Choose the folder that holds your FLAC files. FLACker only reads them: nothing is changed, renamed or uploaded.",
                Icons.Filled.Folder,
            ) { Button(onClick = chooseFolder) { Text("Choose music folder") } }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }

    val tabs = listOf(
        Triple(Screen.Albums, "Albums", Icons.Filled.Album),
        Triple(Screen.Artists, "Artists", Icons.Filled.Person),
        Triple(Screen.Tracks, "Tracks", Icons.AutoMirrored.Filled.QueueMusic),
        Triple(Screen.Favorites, "Favorites", Icons.Filled.Star),
    )
    val top = when (screen) {
        is Screen.Artist -> Screen.Artists
        is Screen.AlbumPage -> model.stack.firstOrNull() ?: Screen.Albums
        else -> screen
    }
    var menu by remember { mutableStateOf(false) }
    // A phone on its side is short: tabs move to a side rail so content keeps the height.
    // The window, not the whole screen, decides: split screen and free-form windows count too.
    val window = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val heightDp = with(androidx.compose.ui.platform.LocalDensity.current) { window.height.toDp() }
    val rail = heightDp < 480.dp && window.width > window.height
    val searchFocus = remember { FocusRequester() }
    val showSearch = searching || query.isNotEmpty()
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        if (model.stack.size > 1) IconButton(onClick = { model.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    title = {
                        if (showSearch) {
                            TextField(
                                value = query, onValueChange = { model.query.value = it },
                                placeholder = { Text("Search your collection") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }),
                                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                                modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                            )
                            // Opening the search puts the cursor in the field.
                            LaunchedEffect(searching) { if (searching && query.isEmpty()) runCatching { searchFocus.requestFocus() } }
                        } else Text("FLACker", style = MaterialTheme.typography.headlineLarge)
                    },
                    actions = {
                        IconButton(onClick = {
                            if (showSearch) { model.query.value = ""; searching = false; keyboard?.hide() } else searching = true
                        }) {
                            Icon(if (showSearch) Icons.Filled.Close else Icons.Filled.Search, if (showSearch) "Close search" else "Search")
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Refresh library") }, onClick = { menu = false; model.rescan() })
                                if (player.current != null) DropdownMenuItem(text = { Text("Focus Mode") }, onClick = { menu = false; model.open(Screen.Focus) })
                                DropdownMenuItem(text = { Text("Preferences") }, onClick = { menu = false; model.open(Screen.Preferences) })
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                if (scan.running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        when {
                            scan.queued > 0 -> "Reading new files… ${scan.read} of ${scan.queued}"
                            scan.full -> "Checking every file…"
                            else -> "Checking for new files…"
                        },
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
            }
        },
        bottomBar = {
            Column(if (rail) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh).navigationBarsPadding() else Modifier) {
                player.current?.let { MiniPlayer(model, it, player.playing, player.durationMs) }
                if (!rail) NavigationBar {
                    tabs.forEach { (s, label, icon) ->
                        NavigationBarItem(selected = top == s, onClick = { model.open(s) }, icon = { Icon(icon, null) }, label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        Row(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
        if (rail) NavigationRail(
            containerColor = MaterialTheme.colorScheme.background,
            windowInsets = WindowInsets(0),
            modifier = Modifier.padding(start = padding.calculateStartPadding(direction), bottom = padding.calculateBottomPadding()),
        ) {
            tabs.forEach { (s, label, icon) ->
                NavigationRailItem(selected = top == s, onClick = { model.open(s) }, icon = { Icon(icon, null) }, label = { Text(label) })
            }
        }
        Box(
            Modifier.padding(
                start = if (rail) 0.dp else padding.calculateStartPadding(direction),
                end = padding.calculateEndPadding(direction),
            ).consumeWindowInsets(padding).imePadding().weight(1f).fillMaxHeight(),
        ) {
            val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
            // Lists depend on these inputs explicitly so a search or filter recomposes them.
            val inputs = listOf(albums, settings.filter, settings.favorites, query, screen)
            val key = stateKey(screen)
            keys += key
            saved.SaveableStateProvider(key) { when (screen) {
                Screen.Albums -> AlbumsScreen(model, settings, player, remember(inputs) { model.filteredAlbums() }, null, bottom)
                is Screen.Artist -> AlbumsScreen(model, settings, player, remember(inputs) { model.filteredAlbums() }, screen.name, bottom)
                Screen.Artists -> ArtistsScreen(model, albums, query, bottom)
                Screen.Tracks -> TracksScreen(model, settings, player, remember(inputs) { model.tracks(false) }, false, bottom)
                Screen.Favorites -> TracksScreen(model, settings, player, remember(inputs) { model.tracks(true) }, true, bottom)
                is Screen.AlbumPage -> model.album(screen.key)?.let { AlbumScreen(model, it, settings, player, bottom) }
                    ?: EmptyState("Album unavailable", "It may have left the library in the last refresh.")
                Screen.Preferences -> PreferencesScreen(model, bottom, chooseFolder)
                else -> {}
            } }
        }
        }
    }
}

@Composable
private fun PreferencesScreen(model: AppModel, bottom: PaddingValues, addFolder: () -> Unit) {
    val settings by model.settings.collectAsState()
    val context = LocalContext.current
    LazyColumn(state = androidx.compose.foundation.lazy.rememberLazyListState(), contentPadding = PaddingValues(bottom = bottom.calculateBottomPadding() + 16.dp)) {
        item { SectionHeading("Preferences", "") }
        item { Group("Appearance and playback") }
        item {
            Options("Appearance", listOf("dark" to "Dark", "light" to "Light", "system" to "Follow the system"), settings.theme) { v -> model.update { it.copy(theme = v) } }
        }
        item {
            Options("ReplayGain", listOf("off" to "Off", "track" to "By track", "album" to "By album"), settings.replayGain, "Uses the values in the tags, with peak protection. Android never amplifies above the original volume.") { model.setReplayGain(it) }
        }
        item { Toggle("Prefer embedded cover", "Off: cover.jpg or folder.jpg in the folder comes first", settings.preferEmbedded) { model.setPreferEmbedded(it) } }
        item { Toggle("Keep the screen on in Focus Mode", "", settings.focusKeepsScreenOn) { v -> model.update { it.copy(focusKeepsScreenOn = v) } } }
        item { Group("Library folders") }
        item {
            Text(
                "Read-only. FLACker reads the folders when you add them and whenever you choose Refresh library.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        settings.roots.forEach { root ->
            item {
                ListItem(
                    headlineContent = { Text(folderName(root), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(Icons.Filled.Folder, null) },
                    trailingContent = { IconButton(onClick = { model.removeRoot(root) }) { Icon(Icons.Filled.Delete, "Remove from the library without deleting files") } },
                )
            }
        }
        item {
            ListItem(
                headlineContent = { Text("Add folder") },
                leadingContent = { Icon(Icons.Filled.CreateNewFolder, null) },
                modifier = Modifier.clickable(onClick = addFolder),
            )
        }
        item {
            Toggle(
                "Refresh library when the app opens",
                "Off: new files are found only with Refresh library. Never runs when you return to the app while music plays.",
                settings.refreshOnLaunch,
            ) { v -> model.update { it.copy(refreshOnLaunch = v) } }
        }
        item {
            ListItem(
                headlineContent = { Text("Refresh library now") },
                supportingContent = { Text("Reads only new or changed files") },
                modifier = Modifier.clickable { model.rescan() },
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Re-read all files") },
                supportingContent = { Text("Checks every file again, for example after editing tags") },
                modifier = Modifier.clickable { model.rescan(full = true) },
            )
        }
        item { Group("About") }
        item {
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            ListItem(
                headlineContent = { Text("FLACker ${version.orEmpty()}") },
                supportingContent = { Text("A home for your collection. FLAC, covers and lyrics, fully offline. GPL-3.0-or-later; League Gothic and Source Sans 3 fonts under the OFL. Provided as is, without any warranty; use it at your own risk.") },
            )
        }
    }
}

/** Human name of a document tree, e.g. "primary:Music/FLAC" → "Internal storage › Music/FLAC". */
fun folderName(uri: String): String {
    val id = Uri.decode(uri.substringAfter("/tree/").substringBefore("/document/"))
    val volume = id.substringBefore(':')
    val path = id.substringAfter(':', "")
    val where = if (volume == "primary") "Internal storage" else "SD card $volume"
    return if (path.isEmpty()) where else "$where › $path"
}

@Composable
private fun Group(title: String) {
    HorizontalDivider(Modifier.padding(top = 12.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
private fun Toggle(title: String, subtitle: String, value: Boolean, change: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (subtitle.isEmpty()) null else ({ Text(subtitle) }),
        trailingContent = { Switch(checked = value, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = value, role = Role.Switch, onValueChange = change),
    )
}

@Composable
private fun Options(title: String, options: List<Pair<String, String>>, value: String, note: String = "", pick: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            options.forEach { (v, label) ->
                Row(Modifier.fillMaxWidth().selectable(selected = value == v, role = Role.RadioButton) { pick(v) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = value == v, onClick = null, modifier = Modifier.padding(12.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Saved-state key of a screen: its scroll position and local state live under it. */
private fun stateKey(s: Screen): String = when (s) {
    Screen.Albums -> "albums"
    Screen.Artists -> "artists"
    Screen.Tracks -> "tracks"
    Screen.Favorites -> "favorites"
    is Screen.Artist -> "artist:${s.name}"
    is Screen.AlbumPage -> "album:${s.key}"
    is Screen.Gallery -> "gallery:${s.key}"
    Screen.Now -> "now"
    Screen.Focus -> "focus"
    Screen.Preferences -> "preferences"
}
