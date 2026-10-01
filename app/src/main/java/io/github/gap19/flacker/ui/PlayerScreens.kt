package io.github.gap19.flacker.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import io.github.gap19.flacker.AppModel
import io.github.gap19.flacker.PlayerState
import io.github.gap19.flacker.Screen
import io.github.gap19.flacker.data.Settings
import io.github.gap19.flacker.library.Covers
import io.github.gap19.flacker.library.Track
import io.github.gap19.flacker.library.count
import io.github.gap19.flacker.library.time
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * Elapsed time, a slider that seeks once the finger lifts, and a right-hand time that
 * cycles between duration, track remaining and album remaining.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun Timeline(model: AppModel, player: PlayerState, settings: Settings, light: Boolean = false) {
    var position by remember { mutableLongStateOf(model.position()) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(player.playing, player.current?.uri) {
        // Only while visible: no wakeups with the screen off or the app in the background.
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) { position = model.position(); delay(if (player.playing) 250 else 1000) }
        }
    }
    val duration = player.durationMs.coerceAtLeast(1)
    val shown = dragging?.let { (it * duration).toLong() } ?: position
    val tint = if (light) Color.White else MaterialTheme.colorScheme.onSurface
    val muted = if (light) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Slider(
            value = dragging ?: (position.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { model.seek((it * duration).toLong()); position = (it * duration).toLong() }; dragging = null },
            thumb = {
                Box(Modifier.size(14.dp).background(tint, androidx.compose.foundation.shape.CircleShape))
            },
            track = { state ->
                // Thin line: elapsed in full tint, the rest muted.
                val fraction = (state.value - state.valueRange.start) /
                    (state.valueRange.endInclusive - state.valueRange.start).coerceAtLeast(1e-6f)
                Box(Modifier.fillMaxWidth().height(3.dp).background(muted.copy(alpha = 0.3f), androidx.compose.foundation.shape.RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(tint, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
                }
            },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(time(shown / 1000.0), style = MaterialTheme.typography.bodySmall, color = muted)
            Spacer(Modifier.weight(1f))
            val right = when (settings.clock) {
                1 -> "−" + time((duration - shown) / 1000.0)
                2 -> model.albumRemainingMs()?.let { "−" + time(it / 1000.0) } ?: "—:—"
                else -> time(duration / 1000.0)
            }
            Text(
                right, style = MaterialTheme.typography.bodySmall, color = muted,
                modifier = Modifier.clickable(onClickLabel = "Switch between duration and time remaining") { model.cycleClock() },
            )
        }
    }
}

@Composable
fun Transport(model: AppModel, player: PlayerState, light: Boolean = false) {
    val tint = if (light) Color.White else MaterialTheme.colorScheme.onSurface
    val active = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { model.shuffle() }) {
            Icon(Icons.Filled.Shuffle, if (player.shuffle) "Shuffle: on" else "Shuffle: off", tint = if (player.shuffle) active else tint.copy(alpha = 0.7f))
        }
        IconButton(onClick = { model.previous() }, Modifier.size(56.dp)) { Icon(Icons.Filled.SkipPrevious, "Previous track", tint = tint, modifier = Modifier.size(34.dp)) }
        FilledIconButton(
            onClick = model::toggle, modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = tint, contentColor = if (light) Color.Black else MaterialTheme.colorScheme.background),
        ) { Icon(if (player.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (player.playing) "Pause" else "Play", Modifier.size(38.dp)) }
        IconButton(onClick = { model.next() }, Modifier.size(56.dp)) { Icon(Icons.Filled.SkipNext, "Next track", tint = tint, modifier = Modifier.size(34.dp)) }
        IconButton(onClick = { model.repeat() }) {
            val (icon, label) = when (player.repeat) {
                Player.REPEAT_MODE_ONE -> Icons.Filled.RepeatOne to "Repeat: this track"
                Player.REPEAT_MODE_ALL -> Icons.Filled.Repeat to "Repeat: whole queue"
                else -> Icons.Filled.Repeat to "Repeat: off"
            }
            Icon(icon, label, tint = if (player.repeat != Player.REPEAT_MODE_OFF) active else tint.copy(alpha = 0.7f))
        }
    }
}

@Composable
fun NowScreen(model: AppModel, player: PlayerState, settings: Settings) {
    val t = player.current ?: return EmptyState("The next record is yours", "Choose an album to start.") {
        FilledTonalButton(onClick = { model.back() }) { Text("Back") }
    }
    var tab by rememberSaveable { mutableStateOf(0) }
    val favorite = t.uri in settings.favorites
    LightSystemIcons()
    Box(Modifier.fillMaxSize()) {
        ArtBackdrop(model, t, Modifier.fillMaxSize())
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val wide = maxWidth > 720.dp
            // A short (landscape) window puts the cover beside the title, so timeline and
            // transport stay in view; a tall one stacks them.
            val short = wide && maxHeight < 560.dp
            val sideCover = minOf(160.dp, maxHeight * 0.38f)
            val head: @Composable () -> Unit = {
                Column(Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!short) Cover(model, t, Modifier.widthIn(max = 360.dp).fillMaxWidth(if (wide) 1f else 0.82f), size = Covers.LARGE, corner = 4.dp)
                    Row(Modifier.fillMaxWidth().padding(top = if (short) 4.dp else 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (short) Cover(model, t, Modifier.size(sideCover).padding(end = 16.dp), size = Covers.LARGE, corner = 4.dp)
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(t.byline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { model.open(Screen.AlbumPage(t.albumKey)) })
                            Text(t.quality, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { model.toggleFavorite(t) }) {
                            Icon(if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder, if (favorite) "Remove from favorites" else "Favorite", tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    Timeline(model, player, settings)
                    Transport(model, player)
                }
            }
            val panel: @Composable (Modifier) -> Unit = { m ->
                Column(m) {
                    PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                        listOf("Lyrics", "Queue", "Audio").forEachIndexed { i, s ->
                            Tab(
                                selected = tab == i, onClick = { tab = i }, text = { Text(s) },
                                selectedContentColor = MaterialTheme.colorScheme.primary,
                                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    when (tab) {
                        0 -> LyricsPanel(model, settings)
                        1 -> QueuePanel(model, player)
                        else -> AudioPanel(model, t, settings)
                    }
                }
            }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { model.back() }) { Icon(Icons.Filled.KeyboardArrowDown, "Close") }
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(onClick = { model.open(Screen.Focus) }) { Icon(Icons.Filled.Visibility, null); Text("Focus Mode", Modifier.padding(start = 6.dp)) }
                    IconButton(onClick = { model.open(Screen.AlbumPage(t.albumKey)) }) { Icon(Icons.Filled.Album, "View album") }
                }
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) { head() }
                        panel(Modifier.weight(1f).padding(end = 16.dp))
                    }
                } else {
                    Box(Modifier.weight(1f)) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            item { head() }
                            item { Spacer(Modifier.height(8.dp)); panel(Modifier.fillMaxWidth().height(520.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LyricsPanel(model: AppModel, settings: Settings, light: Boolean = false) {
    val lyrics by model.lyrics.collectAsState()
    val muted = if (light) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(lyrics.source, style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            IconButton(onClick = { model.update { it.copy(lyricsSize = (it.lyricsSize - 1).coerceAtLeast(14)) } }) { Icon(Icons.Filled.TextDecrease, "Smaller lyrics text", tint = muted) }
            IconButton(onClick = { model.update { it.copy(lyricsSize = (it.lyricsSize + 1).coerceAtMost(30)) } }) { Icon(Icons.Filled.TextIncrease, "Larger lyrics text", tint = muted) }
        }
        val scroll = rememberScrollState()
        // Each track's lyrics start at the top.
        LaunchedEffect(lyrics.text) { scroll.scrollTo(0) }
        SelectionContainer(Modifier.verticalScroll(scroll)) {
            Text(
                lyrics.text.ifEmpty { "No local lyrics for this track." },
                fontSize = settings.lyricsSize.sp, lineHeight = (settings.lyricsSize * 1.6).sp,
                color = if (light) Color.White else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 32.dp),
            )
        }
    }
}

@Composable
fun QueuePanel(model: AppModel, player: PlayerState) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (player.index - 1).coerceAtLeast(0))
    Column(Modifier.fillMaxSize()) {
        Text("${count(player.queue.size, "track", "tracks")} in the queue", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(player.queue, key = { i, t -> "$i:${t.uri}" }) { i, t ->
                val current = i == player.index
                Row(
                    Modifier.fillMaxWidth().background(if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable { model.jump(i) }.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("%02d".format(i + 1), style = MaterialTheme.typography.bodySmall, color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 28.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(t.byline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(time(t.duration), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = { model.move(i, i - 1) }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
                    IconButton(onClick = { model.move(i, i + 1) }, enabled = i + 1 < player.queue.size) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
                    IconButton(onClick = { model.remove(i) }) { Icon(Icons.Filled.Remove, "Remove from queue") }
                }
            }
        }
    }
}

@Composable
fun AudioPanel(model: AppModel, t: Track, settings: Settings) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Fact("File", t.fileName)
        Fact("Folder", t.directory)
        Fact("Original", "${t.quality}  ·  ${count(t.channels, "channel", "channels")}")
        Fact("ReplayGain", when (settings.replayGain) { "track" -> "By track"; "album" -> "By album"; else -> "Off" })
        Fact("Edition", t.releaseId.ifEmpty { "No edition ID" })
        Text(
            "Android may resample the signal to the output device rate; the app does not claim bit-perfect playback.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** Full-screen listening: art, title, timeline and transport; lyrics on demand. */
@Composable
fun FocusScreen(model: AppModel, player: PlayerState, settings: Settings) {
    val t = player.current ?: return LaunchedEffect(Unit) { model.back() }
    val context = LocalContext.current
    var lyrics by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(settings.focusKeepsScreenOn) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        if (settings.focusKeepsScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ArtBackdrop(model, t, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout).padding(20.dp)) {
            val wide = maxWidth > maxHeight
            val info: @Composable (Modifier) -> Unit = { m ->
                Column(m, verticalArrangement = Arrangement.Center) {
                    Text(t.title, style = MaterialTheme.typography.displayMedium, color = Color.White, maxLines = if (wide) 2 else 3, overflow = TextOverflow.Ellipsis)
                    Text(t.byline, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.75f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    Timeline(model, player, settings, light = true)
                    Transport(model, player, light = true)
                }
            }
            if (lyrics) {
                Column(Modifier.fillMaxSize()) {
                    Text(t.title, style = MaterialTheme.typography.headlineMedium, color = Color.White, modifier = Modifier.padding(top = 48.dp, start = 4.dp))
                    Box(Modifier.weight(1f)) { LyricsPanel(model, settings, light = true) }
                    Timeline(model, player, settings, light = true)
                    Transport(model, player, light = true)
                }
            } else if (wide) {
                Row(Modifier.fillMaxSize().padding(top = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(model, t, Modifier.fillMaxHeight(0.85f), size = Covers.LARGE, corner = 4.dp)
                    // Scrolls rather than squeezing the controls on a short landscape screen.
                    info(Modifier.weight(1f).padding(start = 32.dp).verticalScroll(rememberScrollState()))
                }
            } else {
                Column(Modifier.fillMaxSize().padding(top = 40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    // The cover gives way first, so title, timeline and transport always fit.
                    Cover(model, t, Modifier.weight(1f, fill = false).fillMaxWidth(), size = Covers.LARGE, corner = 4.dp)
                    Spacer(Modifier.height(28.dp))
                    info(Modifier.fillMaxWidth())
                }
            }
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { model.back() }) { Icon(Icons.Filled.Close, "Leave Focus Mode", tint = Color.White) }
                Text("Focus Mode", style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                IconButton(onClick = { lyrics = !lyrics }) { Icon(Icons.Filled.Lyrics, if (lyrics) "Hide lyrics" else "Show lyrics", tint = Color.White) }
            }
        }
    }
}
