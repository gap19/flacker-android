package io.github.gap19.flacker.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.gap19.flacker.AppModel
import io.github.gap19.flacker.Screen
import io.github.gap19.flacker.library.Album
import io.github.gap19.flacker.library.Covers
import io.github.gap19.flacker.library.Track
import io.github.gap19.flacker.library.count
import io.github.gap19.flacker.library.time
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** Cover from memory on the first frame when available; otherwise loaded off the main thread. */
@Composable
fun Cover(model: AppModel, track: Track?, modifier: Modifier = Modifier, size: Int = Covers.THUMB, corner: Dp = 6.dp) {
    // Reading the preference subscribes, so toggling "Prefer embedded cover" reloads covers on screen.
    val prefer = model.covers.preferEmbedded
    var bitmap by remember(track?.uri, size, prefer) { mutableStateOf(track?.let { model.covers.cached(it, size) }) }
    LaunchedEffect(track?.uri, size, prefer) {
        if (bitmap == null && track != null) bitmap = model.covers.load(track, size)
    }
    Box(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Icon(Icons.Filled.Album, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
fun AlbumTile(model: AppModel, album: Album, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(6.dp)
            .semantics { contentDescription = listOf(album.title, album.artist).filter { it.isNotBlank() }.joinToString(", ") },
    ) {
        Cover(model, album.cover, Modifier.fillMaxWidth())
        Text(
            album.title, style = MaterialTheme.typography.titleSmall, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
        )
        Text(album.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOf(album.year, count(album.tracks.size, "track", "tracks")).filter { it.isNotEmpty() }.joinToString("  ·  "),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ArtistCard(model: AppModel, name: String, albums: Int, cover: Track?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(model, cover, Modifier.size(56.dp), corner = 4.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(name.ifEmpty { "Unknown artist" }, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(count(albums, "album", "albums"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One track: tap plays the list from here; ⋮ or long press offers queue and favorite actions. */
@Composable
fun TrackRow(
    model: AppModel,
    track: Track,
    favorite: Boolean,
    current: Boolean,
    showAlbum: Boolean,
    onPlay: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .background(if (current) scheme.primary.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onClickLabel = "Play", onLongClickLabel = "More actions", onClick = onPlay, onLongClick = { menu = true })
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (track.discTotal > 1) "%02d–%02d".format(track.disc, track.number) else "%02d".format(track.number),
            style = MaterialTheme.typography.bodySmall, color = if (current) scheme.primary else scheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 30.dp),
        )
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (current) scheme.primary else scheme.onSurface)
            val meta = buildList {
                if (showAlbum || track.artist != track.albumArtist) add(track.artist)
                if (showAlbum) add(track.album)
                add("${track.bits} bit / ${io.github.gap19.flacker.library.khz(track.sampleRate)} kHz")
            }.filter { it.isNotEmpty() }.joinToString("  ·  ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (favorite) Icon(Icons.Filled.Star, "Favorite", tint = scheme.primary, modifier = Modifier.size(16.dp))
        Text(time(track.duration), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More track actions", tint = scheme.onSurfaceVariant) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Play next") }, onClick = { menu = false; model.playNext(track) })
                DropdownMenuItem(text = { Text("Add to queue") }, onClick = { menu = false; model.append(listOf(track)) })
                DropdownMenuItem(text = { Text(if (favorite) "Remove from favorites" else "Favorite") }, onClick = { menu = false; model.toggleFavorite(track) })
                if (showAlbum) DropdownMenuItem(text = { Text("View album") }, onClick = { menu = false; model.open(Screen.AlbumPage(track.albumKey)) })
            }
        }
    }
}

/** Compact player above the navigation bar; tapping opens the full player. */
@Composable
fun MiniPlayer(model: AppModel, track: Track, playing: Boolean, durationMs: Long) {
    var position by remember { mutableLongStateOf(model.position()) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(playing, track.uri) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) { position = model.position(); delay(if (playing) 500 else 1500) }
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 2.dp,
        // With three-button navigation in landscape the bar sits at the side.
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        Column {
            LinearProgressIndicator(
                progress = { if (durationMs > 0) (position.toFloat() / durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.onSurface,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
            Row(
                Modifier.fillMaxWidth().clickable(onClickLabel = "Open the player") { model.open(Screen.Now) }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(model, track, Modifier.size(44.dp), corner = 4.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.byline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = model::toggle) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play")
                }
                IconButton(onClick = { model.next() }) { Icon(Icons.Filled.SkipNext, "Next track") }
            }
        }
    }
}

@Composable
fun SectionHeading(title: String, subtitle: String, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

@Composable
fun EmptyState(title: String, body: String, icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.Album, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(20.dp)); action() }
    }
}

@Composable
fun Spacer8() = Spacer(Modifier.width(8.dp))
