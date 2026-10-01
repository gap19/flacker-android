package io.github.gap19.flacker.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gap19.flacker.AppModel
import io.github.gap19.flacker.PlayerState
import io.github.gap19.flacker.data.Settings
import io.github.gap19.flacker.library.Album
import io.github.gap19.flacker.library.Covers
import io.github.gap19.flacker.library.Entry
import io.github.gap19.flacker.library.Track
import io.github.gap19.flacker.library.count
import io.github.gap19.flacker.library.time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Blurred, darkened copy of the artwork behind a page; the original stays intact elsewhere. */
@Composable
fun ArtBackdrop(model: AppModel, track: Track?, modifier: Modifier = Modifier) {
    // Keyed state: when the track changes, the previous album's art must not stay behind.
    val bitmap by produceState(track?.let { model.covers.cached(it) }, track?.uri) {
        value = track?.let { model.covers.cached(it) ?: model.covers.load(it) }
    }
    Box(modifier.clipToBounds()) {
        bitmap?.let {
            Image(
                it.asImageBitmap(), null, contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().graphicsLayer { scaleX = 1.3f; scaleY = 1.3f }
                    .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(48.dp) else Modifier.alpha(0.35f)),
            )
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), MaterialTheme.colorScheme.background)),
            ),
        )
    }
}

@Composable
fun AlbumScreen(model: AppModel, album: Album, settings: Settings, player: PlayerState, bottom: PaddingValues) {
    var edition by remember { mutableStateOf<Track?>(null) }
    // Booklet pages come from the folder index, which can finish loading after the shelf.
    val idx by model.index.collectAsState()
    val extras = remember(album.key, idx) { model.covers.extras(album) }
    androidx.compose.foundation.layout.BoxWithConstraints {
    // A short (landscape) window keeps the play buttons in view under a smaller cover.
    val coverMax = minOf(280.dp, maxHeight * 0.45f)
    LazyColumn(state = rememberLazyListState(), contentPadding = PaddingValues(bottom = bottom.calculateBottomPadding() + 16.dp)) {
        item {
            Box {
                ArtBackdrop(model, album.cover, Modifier.matchParentSize())
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Cover(model, album.cover, Modifier.widthIn(max = coverMax).fillMaxWidth(0.72f), size = Covers.LARGE, corner = 4.dp)
                    Text(album.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 18.dp), textAlign = TextAlign.Center)
                    Text(album.title, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
                    Text(
                        listOf(album.year, count(album.tracks.size, "track", "tracks"), time(album.duration)).filter { it.isNotEmpty() }.joinToString("  /  "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { model.play(album.tracks) }) { Icon(Icons.Filled.PlayArrow, null); Text("Play album", Modifier.padding(start = 6.dp)) }
                        FilledTonalIconButton(onClick = { model.append(album.tracks) }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Add to queue") }
                        if (extras.isNotEmpty()) FilledTonalIconButton(onClick = { model.open(io.github.gap19.flacker.Screen.Gallery(album.key)) }) { Icon(Icons.Filled.PhotoLibrary, "Booklet") }
                        FilledTonalIconButton(onClick = { edition = album.cover }) { Icon(Icons.Filled.Info, "Edition details") }
                    }
                }
            }
        }
        albumTracks(model, album, settings, player)
    }
    }
    edition?.let { EditionSheet(model, it) { edition = null } }
}

private fun LazyListScope.albumTracks(model: AppModel, album: Album, settings: Settings, player: PlayerState) {
    val discs = album.tracks.map { it.disc }.distinct().size > 1
    itemsIndexed(album.tracks, key = { i, t -> "$i:${t.uri}" }) { i, t ->
        if (discs && (i == 0 || album.tracks[i - 1].disc != t.disc)) {
            Text("Disc ${t.disc}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp))
        }
        TrackRow(model, t, t.uri in settings.favorites, player.current?.uri == t.uri, false) { model.play(album.tracks, i) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditionSheet(model: AppModel, track: Track, close: () -> Unit) {
    val tags by produceState<List<Pair<String, String>>?>(null, track.uri) { value = model.tags(track) }
    ModalBottomSheet(onDismissRequest = close) {
        LazyColumn(Modifier.padding(horizontal = 20.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text("Edition details", style = MaterialTheme.typography.headlineMedium)
                Text("${track.album} — ${track.albumArtist}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Fact("File", track.path)
                Fact("Original", "${track.quality}  ·  ${count(track.channels, "channel", "channels")}")
                Fact("Edition", track.releaseId.ifEmpty { "No edition ID" })
                Text("Local tags", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            }
            val list = tags
            if (list == null) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
            else itemsIndexed(list) { _, (k, v) -> Fact(k, v) }
        }
    }
}

@Composable
fun Fact(name: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 110.dp, max = 140.dp).padding(end = 12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** Booklet pages one at a time, fitted to the screen, with pinch and double-tap zoom. */
@Composable
fun GalleryScreen(model: AppModel, album: Album) {
    val entries = remember(album.key) { model.covers.extras(album) }
    val pager = rememberPagerState { entries.size }
    val context = LocalContext.current
    LightSystemIcons()
    Box(Modifier.fillMaxSize().background(Color(0xFF080C0E))) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
            val e = entries[page]
            if (e.name.lowercase(Locale.ROOT).endsWith(".pdf")) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Filled.PictureAsPdf, null, tint = Color.White, modifier = Modifier.height(72.dp))
                    Text(e.name, color = Color.White, modifier = Modifier.padding(16.dp))
                    FilledTonalButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(e.uri), "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }.onFailure {
                            model.messages.trySend(
                                if (it is android.content.ActivityNotFoundException) "No app to open PDFs is installed."
                                else "Could not open the PDF.",
                            )
                        }
                    }) { Text("Open in PDF reader") }
                }
            } else ZoomableImage(model, e)
        }
        Row(Modifier.statusBarsPadding().fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { model.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            if (entries.isNotEmpty()) Text(
                "${pager.currentPage + 1} / ${entries.size}  ·  ${entries[pager.currentPage].name}",
                color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Page state: still loading, decoded, or not displayable (too large, damaged, unreadable). */
private sealed interface Page {
    data object Loading : Page
    data object Failed : Page
    class Ready(val bitmap: android.graphics.Bitmap) : Page
}

@Composable
private fun ZoomableImage(model: AppModel, entry: Entry) {
    val page by produceState<Page>(Page.Loading, entry.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { model.covers.read(entry.uri, 64 * 1024 * 1024)?.let { model.covers.scaled(it, 2048) } }.getOrNull()
        }?.let { Page.Ready(it) } ?: Page.Failed
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var area by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    LaunchedEffect(entry.uri) { scale = 1f; offset = Offset.Zero }
    // A zoomed page pans only as far as its edges.
    fun clamp(o: Offset): Offset {
        val x = area.width * (scale - 1) / 2; val y = area.height * (scale - 1) / 2
        return Offset(o.x.coerceIn(-x, x), o.y.coerceIn(-y, y))
    }
    Box(
        Modifier.fillMaxSize().onSizeChanged { area = it }
            .pointerInput(entry.uri) {
                detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f })
            }
            .pointerInput(entry.uri) {
                // A single finger at 1× belongs to the pager; pinch or a zoomed image pans here.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else clamp(offset + event.calculatePan())
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (val p = page) {
            Page.Loading -> CircularProgressIndicator(color = Color.White)
            Page.Failed -> Text(
                "Could not show this image.\nIt may be damaged or too large.",
                color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp),
            )
            is Page.Ready -> Image(
            p.bitmap.asImageBitmap(), entry.name, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
        )
        }
    }
}

/** Light status and navigation icons while a page draws on black, in either theme. */
@Composable
fun LightSystemIcons() {
    val view = androidx.compose.ui.platform.LocalView.current
    val dark = LocalDark.current
    androidx.compose.runtime.DisposableEffect(dark) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            controller?.isAppearanceLightStatusBars = !dark
            controller?.isAppearanceLightNavigationBars = !dark
        }
    }
}
