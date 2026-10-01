package io.github.gap19.flacker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gap19.flacker.AppModel
import io.github.gap19.flacker.PlayerState
import io.github.gap19.flacker.Screen
import io.github.gap19.flacker.data.Filter
import io.github.gap19.flacker.data.Settings
import io.github.gap19.flacker.library.Album
import io.github.gap19.flacker.library.Track
import io.github.gap19.flacker.library.count
import io.github.gap19.flacker.library.time
import io.github.gap19.flacker.library.year
import java.util.Locale

@Composable
fun AlbumsScreen(model: AppModel, settings: Settings, player: PlayerState, albums: List<Album>, artist: String? = null, bottom: PaddingValues) {
    val state = rememberLazyGridState()
    var filters by remember { mutableStateOf(false) }
    val all by model.albums.collectAsState()
    val scan by model.scan.collectAsState()
    val loading by model.loading.collectAsState()
    val query by model.query.collectAsState()
    val hours = all.sumOf { it.duration } / 3600
    val current = player.current?.let { t -> all.firstOrNull { it.key == t.albumKey } }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        state = state,
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = bottom.calculateBottomPadding() + 12.dp),
    ) {
        if (artist == null && current != null) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "feature") { Feature(model, current) }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "heading") {
            val subtitle = if (loading) "" else if (artist == null) {
                val h = if (hours < 1) "${(hours * 60).toInt()} min of music" else "%.1f hours of music".format(Locale.ROOT, hours)
                "${albums.size} of ${count(all.size, "album", "albums")}  ·  $h"
            } else "${count(albums.size, "album", "albums")}  ·  ${time(albums.sumOf { it.duration })}"
            SectionHeading(artist?.ifEmpty { "Unknown artist" } ?: "Collection", subtitle, Modifier.padding(start = 0.dp)) {
                FilledTonalIconButton(onClick = model::randomAlbum) { Icon(Icons.Filled.Shuffle, "Play a random album from this selection, in order") }
                FilledTonalIconButton(
                    onClick = { filters = true },
                    colors = if (settings.filter.active) IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) else IconButtonDefaults.filledTonalIconButtonColors(),
                ) { Icon(Icons.Filled.FilterList, "Filter and sort") }
            }
        }
        // While the saved shelf loads (a moment at start) nothing is shown, not an empty collection.
        if (albums.isEmpty() && !loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    if (scan.running) "Setting up the shelf" else "No albums found",
                    if (settings.filter.active || query.isNotEmpty()) "Change the search or clear the filters." else "Choose your music folder in Preferences.",
                )
            }
        }
        items(albums, key = { it.key }) { a -> AlbumTile(model, a) { model.open(Screen.AlbumPage(a.key)) } }
    }
    if (filters) FilterSheet(model, settings.filter) { filters = false }
}

@Composable
private fun Feature(model: AppModel, album: Album) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(6.dp).clip(RoundedCornerShape(16.dp)).clickable { model.open(Screen.AlbumPage(album.key)) },
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(model, album.cover, Modifier.size(64.dp), corner = 4.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(album.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(album.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { model.open(Screen.AlbumPage(album.key)) }) { Text("Explore the album") }
        }
    }
}

@Composable
fun ArtistsScreen(model: AppModel, albums: List<Album>, query: String, bottom: PaddingValues) {
    val loading by model.loading.collectAsState()
    val terms = query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
    val artists = remember(albums, query) {
        albums.groupBy { it.artist }
            .filterKeys { name -> terms.all { it in name.lowercase(Locale.ROOT) } }
            .map { (name, list) -> Triple(name, list.size, list.first().cover) }
            .sortedBy { it.first.trimStart { c -> !c.isLetterOrDigit() }.lowercase(Locale.ROOT) }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(168.dp),
        state = rememberLazyGridState(),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = bottom.calculateBottomPadding() + 12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeading("Artists", if (loading) "" else "${count(artists.size, "artist", "artists")} in your collection")
        }
        if (artists.isEmpty() && !loading) item(span = { GridItemSpan(maxLineSpan) }) { EmptyState("No artists found", "Change or clear the search.") }
        items(artists, key = { it.first }) { (name, n, cover) ->
            ArtistCard(model, name, n, cover) { model.open(Screen.Artist(name)) }
        }
    }
}

@Composable
fun TracksScreen(model: AppModel, settings: Settings, player: PlayerState, tracks: List<Track>, favorites: Boolean, bottom: PaddingValues) {
    var filters by remember { mutableStateOf(false) }
    val query by model.query.collectAsState()
    val loading by model.loading.collectAsState()
    LazyColumn(state = rememberLazyListState(), contentPadding = PaddingValues(bottom = bottom.calculateBottomPadding() + 12.dp)) {
        item {
            SectionHeading(
                if (favorites) "Favorites" else "All tracks",
                if (loading) "" else count(tracks.size, "track", "tracks"),
            ) {
                if (tracks.isNotEmpty()) FilledTonalButton(onClick = { model.play(tracks) }) {
                    Icon(Icons.Filled.PlayArrow, null); Text("Play", Modifier.padding(start = 6.dp))
                }
                FilledTonalIconButton(onClick = { filters = true }) { Icon(Icons.Filled.FilterList, "Filter") }
            }
        }
        if (tracks.isEmpty() && !loading) item {
            if (favorites && query.isEmpty()) EmptyState("No favorites yet", "Use the star in the player or a track's ⋮ menu to keep it here.", Icons.Filled.Star)
            else EmptyState("No tracks found", "Change the search or the filters.")
        }
        itemsIndexed(tracks, key = { i, t -> "$i:${t.uri}" }) { i, t ->
            TrackRow(model, t, t.uri in settings.favorites, player.current?.uri == t.uri, true) { model.play(tracks, i) }
        }
    }
    if (filters) FilterSheet(model, settings.filter) { filters = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(model: AppModel, filter: Filter, close: () -> Unit) {
    val albums by model.albums.collectAsState()
    val tracks = remember(albums) { albums.flatMap { it.tracks } }
    val genres = remember(tracks) { tracks.flatMap { it.genres }.filter { it.isNotBlank() }.distinctBy { it.lowercase(Locale.ROOT) }.sortedBy { it.lowercase(Locale.ROOT) } }
    val years = remember(tracks) { tracks.flatMap { listOf(year(it.date), year(it.originalDate)) }.filter { it.isNotEmpty() }.distinct().sorted() }
    // A saved genre or year that no longer exists after a rescan would filter invisibly.
    var f by remember {
        mutableStateOf(filter.copy(
            genre = filter.genre.takeIf { g -> genres.any { it.equals(g, true) } }.orEmpty(),
            year = filter.year.takeIf { it in years }.orEmpty(),
        ))
    }
    ModalBottomSheet(onDismissRequest = close) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Filter and sort", style = MaterialTheme.typography.headlineMedium)
            Choice("Genre in tags", listOf("All genres") + genres, genres.indexOfFirst { it.equals(f.genre, true) } + 1) {
                f = f.copy(genre = if (it == 0) "" else genres[it - 1])
            }
            Choice("Year", listOf("All years") + years, years.indexOf(f.year) + 1) { f = f.copy(year = if (it == 0) "" else years[it - 1]) }
            Check("Filter by original year", f.originalYear) { f = f.copy(originalYear = it) }
            Choice("Resolution", listOf("All", "High resolution (>16 bit or >48 kHz)", "Up to 16 bit / 48 kHz"), f.quality) { f = f.copy(quality = it) }
            Check("With favorite tracks", f.favorites) { f = f.copy(favorites = it) }
            Choice("Sort albums", listOf("Artist and edition", "Album title", "Edition year", "Original year"), f.sort) { f = f.copy(sort = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { model.setFilter(Filter()); close() }) { Text("Clear filters") }
                FilledTonalButton(onClick = { model.setFilter(f); close() }) { Text("Apply") }
            }
        }
    }
}

@Composable
private fun Choice(label: String, options: List<String>, selected: Int, pick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(options.getOrElse(selected) { options.first() }, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; pick(i) }) }
            }
        }
    }
}

@Composable
private fun Check(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = value, role = Role.Checkbox, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = value, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
