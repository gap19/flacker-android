package io.github.gap19.flacker

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import io.github.gap19.flacker.data.Filter
import io.github.gap19.flacker.data.Settings
import io.github.gap19.flacker.data.SettingsStore
import io.github.gap19.flacker.library.Album
import io.github.gap19.flacker.library.Covers
import io.github.gap19.flacker.library.FlacReader
import io.github.gap19.flacker.library.LibraryIndex
import io.github.gap19.flacker.library.LibraryStore
import io.github.gap19.flacker.library.Lyrics
import io.github.gap19.flacker.library.LyricsText
import io.github.gap19.flacker.library.Scanner
import io.github.gap19.flacker.library.ShelfStore
import io.github.gap19.flacker.library.Track
import io.github.gap19.flacker.library.albums
import io.github.gap19.flacker.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

sealed interface Screen {
    data object Albums : Screen
    data object Artists : Screen
    data object Tracks : Screen
    data object Favorites : Screen
    data class Artist(val name: String) : Screen
    data class AlbumPage(val key: String) : Screen
    data object Now : Screen
    data object Focus : Screen
    data class Gallery(val key: String) : Screen
    data object Preferences : Screen
}

data class PlayerState(
    val current: Track? = null,
    val index: Int = -1,
    val queue: List<Track> = emptyList(),
    val playing: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val durationMs: Long = 0,
)

/** [read] of [queued] new or changed files have been opened; zero queued means only checking folders. */
data class ScanState(val running: Boolean = false, val read: Int = 0, val queued: Int = 0, val full: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
class AppModel(app: Application, saved: androidx.lifecycle.SavedStateHandle) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()
    val settings = MutableStateFlow(SettingsStore.load(app))
    /** Loaded off the main thread at start; large libraries take a moment to parse. */
    val index = MutableStateFlow(LibraryIndex.EMPTY)
    val albums = MutableStateFlow<List<Album>>(emptyList())
    val scan = MutableStateFlow(ScanState())
    /** True until the saved index has been loaded and the shelf built from it. */
    val loading = MutableStateFlow(true)
    val player = MutableStateFlow(PlayerState())
    val lyrics = MutableStateFlow(Lyrics())
    val query = MutableStateFlow("")
    /** Snackbar texts; a channel keeps messages sent before the screen starts listening. */
    val messages = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val covers = Covers(app) { index.value }.apply { preferEmbedded = settings.value.preferEmbedded }
    val stack = mutableStateListOf<Screen>(Screen.Albums)
    val screen get() = stack.last()

    private var controller: MediaController? = null
    private val future: com.google.common.util.concurrent.ListenableFuture<MediaController>
    private var byUri: Map<String, Track> = emptyMap()
    private var coverOf: Map<String, Track> = emptyMap()
    private var generation = 0
    private var scanJob: Job? = null
    private var scanAgain = false
    private var lyricsJob: Job? = null
    private var removed: Pair<Int, MediaItem>? = null
    /** Queue edits apply in the order they were asked, even though items are built off the main thread. */
    private val queueLock = Mutex()
    private var playJob: Job? = null
    /** One writer for state.json, so an older snapshot can never be renamed over a newer one. */
    private val writer = Dispatchers.IO.limitedParallelism(1)

    /** Completed once the full index is loaded; scans and folder lookups wait for it. */
    private val indexReady = kotlinx.coroutines.CompletableDeferred<Unit>()
    private var warmJob: Job? = null

    init {
        // Decide before the controller below starts the playback service. Returning to the app
        // (the screen is recreated while the process lives, often with music playing) or opening
        // it while the service already runs is not a new start and never scans.
        // Saved screen state also survives the system ending the process in the background.
        val newStart = !FlackerApp.startHandled && !PlaybackService.running && !saved.contains(STARTED)
        FlackerApp.startHandled = true
        saved[STARTED] = true
        // Returning to a living process: the shelf and index already in memory are shown in the very
        // first frame, with nothing to read.
        val kept = FlackerApp.lastShelf as? Shelf
        val keptIndex = FlackerApp.lastIndex
        if (kept != null && keptIndex != null) {
            publish(kept)
            index.value = keptIndex
            indexReady.complete(Unit)
            loading.value = false
        }
        if (kept == null || keptIndex == null) viewModelScope.launch {
            val t0 = android.os.SystemClock.elapsedRealtime()
            // The saved shelf comes first and alone: it is small and needs no further reads.
            val shelf = withContext(Dispatchers.IO) { FlackerApp.takePreloadedShelf() ?: ShelfStore.load(context) }
            if (shelf != null && albums.value.isEmpty()) {
                publish(withContext(Dispatchers.Default) { Shelf(shelf) })
                loading.value = false
            }
            val t1 = android.os.SystemClock.elapsedRealtime()
            // The full index (every folder entry, for scans, covers, lyrics and booklets) follows.
            val loaded = withContext(Dispatchers.IO) { LibraryStore.load(context) }
            // A rescan started meanwhile already holds a newer index.
            if (index.value === LibraryIndex.EMPTY) index.value = loaded
            FlackerApp.lastIndex = index.value
            indexReady.complete(Unit)
            val t2 = android.os.SystemClock.elapsedRealtime()
            if (shelf == null || stamp(shelf.flatMap { it.tracks }) != stamp(index.value.tracks)) {
                // First start of this version, or the shelf is older than the index.
                rebuild(index.value, warm = true)
            } else {
                warmCovers(shelf)
            }
            loading.value = false
            android.util.Log.i(
                "FLACker",
                "Start: shelf ${if (shelf == null) "missing" else "shown"} in ${t1 - t0} ms, index loaded in ${t2 - t1} ms " +
                    "(${loaded.tracks.size} tracks, ${loaded.dirs.size} folders), ready in ${android.os.SystemClock.elapsedRealtime() - t0} ms",
            )
            checkPermissions()
            val s = settings.value
            // The first reading after choosing a folder always runs, even if it was interrupted.
            val neverRead = index.value.tracks.isEmpty() && index.value.dirs.isEmpty()
            if (s.roots.isNotEmpty() && (neverRead || (s.refreshOnLaunch && newStart))) rescan()
        }
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        future = MediaController.Builder(app, token)
            .setListener(object : MediaController.Listener {
                override fun onCustomCommand(controller: MediaController, command: SessionCommand, args: Bundle) =
                    com.google.common.util.concurrent.Futures.immediateFuture(
                        androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS),
                    ).also {
                        if (command.customAction == PlaybackService.PLAY_ERROR) {
                            val title = args.getString("title") ?: "track"
                            messages.trySend("Could not play “$title”. The file may have been moved or damaged.")
                        }
                    }
            })
            .buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.onFailure {
                android.util.Log.e("FLACker", "No connection to the playback service", it)
            }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refresh(
                    events.contains(Player.EVENT_TIMELINE_CHANGED),
                )
            })
            refresh(true)
        }, ContextCompat.getMainExecutor(app))
    }

    // Navigation -----------------------------------------------------------------------------

    fun open(s: Screen) {
        if (s == screen) return
        if (s in listOf(Screen.Albums, Screen.Artists, Screen.Tracks, Screen.Favorites)) {
            stack.clear()
            stack.add(s)
            return
        }
        // Reaching a screen that is already open returns to it, so the stack cannot grow in loops.
        val at = stack.indexOf(s)
        if (at >= 0) {
            while (stack.size > at + 1) stack.removeAt(stack.size - 1)
        } else stack.add(s)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1)
        return true
    }

    // Library --------------------------------------------------------------------------------

    fun addRoot(uri: Uri) {
        val granted = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        if (!granted) {
            messages.trySend("Android did not grant lasting access to that folder. Try choosing it again.")
            return
        }
        val s = uri.toString()
        val roots = settings.value.roots
        if (s in roots) return rescan()
        // A folder inside one already chosen would list every track twice.
        if (roots.any { contains(it, s) }) {
            messages.trySend("That folder is already part of the library.")
            return
        }
        val inside = roots.filter { contains(s, it) }
        inside.forEach(::releaseRoot)
        update { it.copy(roots = it.roots - inside.toSet() + s) }
        rescan()
    }

    /** True when [child] is [parent] or lies inside it (same provider and document tree). */
    private fun contains(parent: String, child: String): Boolean = runCatching {
        val p = Uri.parse(parent); val c = Uri.parse(child)
        if (p.authority != c.authority) return false
        val a = android.provider.DocumentsContract.getTreeDocumentId(p)
        val b = android.provider.DocumentsContract.getTreeDocumentId(c)
        b == a || b.startsWith(if (a.endsWith(":") || a.endsWith("/")) a else "$a/")
    }.getOrDefault(false)

    private fun releaseRoot(uri: String) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Folders restored from a backup or revoked in system settings need to be chosen again. */
    private fun checkPermissions() {
        val held = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        val lost = settings.value.roots.filter { it !in held }
        if (lost.isNotEmpty()) {
            messages.trySend("No access to ${io.github.gap19.flacker.library.count(lost.size, "folder", "folders")} of the library. Choose them again in Preferences.")
        }
    }

    fun removeRoot(uri: String) {
        releaseRoot(uri)
        update { it.copy(roots = it.roots - uri) }
        rescan()
    }

    /**
     * Look for new or changed files. Unchanged album folders come from the saved index, so a
     * refresh only opens what was not read before; [full] lists and compares every file again.
     */
    fun rescan(full: Boolean = false) {
        // Folders added or removed during a scan are read by one more pass right after it.
        if (scanJob?.isActive == true) { scanAgain = true; return }
        scanAgain = false
        scanJob = viewModelScope.launch {
            try {
                scan.value = ScanState(true, full = full)
                indexReady.await()
                val roots = settings.value.roots.map(Uri::parse)
                val before = index.value
                val previousErrors = before.errors
                val job = coroutineContext[Job]
                val result = withContext(Dispatchers.IO) {
                    Scanner(context).scan(roots, before, { job?.isCancelled == true }, full) { read, queued ->
                        scan.value = ScanState(true, read, queued, full)
                    }
                }
                index.value = result
                FlackerApp.lastIndex = result
                // An unchanged library keeps the shelf as it is: no rebuild, no cover warm-up.
                if (result.tracks != before.tracks) rebuild(result, warm = true)
                val known = before.tracks.mapTo(HashSet()) { it.uri }
                val added = result.tracks.count { it.uri !in known }
                if (added > 0 && known.isNotEmpty()) messages.trySend(if (added == 1) "1 new track added." else "$added new tracks added.")
                runCatching { withContext(Dispatchers.IO) { LibraryStore.save(context, result) } }
                    .onFailure { messages.trySend("Could not save the library index (${it.message}).") }
                update { it.copy(lastScan = System.currentTimeMillis()) }
                // Report only files that became unreadable since the last scan, not the same ones at every launch.
                val fresh = (result.errors - previousErrors.toSet()).size
                if (fresh > 0 && !scanAgain) messages.trySend(
                    if (fresh == 1) "1 file could not be read or is still incomplete."
                    else "$fresh files could not be read or are still incomplete.",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.trySend("Reading the library failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                scan.value = ScanState(false)
            }
            if (scanAgain) viewModelScope.launch { scanJob?.join(); rescan() }
        }
    }

    fun cancelScan() = scanJob?.cancel()

    /** Albums with the lookups the player needs, built off the main thread. */
    internal class Shelf(val albums: List<Album>) {
        val byUri: Map<String, Track> = albums.flatMap { it.tracks }.associateBy { it.uri }
        val coverOf: Map<String, Track> = albums.flatMap { a -> a.tracks.map { it.uri to a.cover } }.toMap()
    }

    private fun publish(shelf: Shelf) {
        FlackerApp.lastShelf = shelf
        albums.value = shelf.albums
        byUri = shelf.byUri
        coverOf = shelf.coverOf
        dropStaleFilter(shelf.albums)
    }

    /** Build the shelf from the index (reading each album's playlist) and save it for the next start. */
    private suspend fun rebuild(idx: LibraryIndex, warm: Boolean) {
        val g = ++generation
        val built = withContext(Dispatchers.IO) { Shelf(albums(idx) { uri -> readText(uri) }) }
        // Two rebuilds can overlap at start; only the newest one is published.
        if (g != generation) return
        publish(built)
        viewModelScope.launch(writer) { runCatching { ShelfStore.save(context, built.albums) } }
        if (warm) warmCovers(built.albums)
    }

    private fun warmCovers(list: List<Album>) {
        warmJob?.cancel()
        warmJob = viewModelScope.launch(Dispatchers.IO) { covers.warm(list); covers.prune() }
    }


    /** A genre or year that left the library would hide every album with no visible reason. */
    private fun dropStaleFilter(list: List<Album>) {
        val f = settings.value.filter
        if (list.isEmpty() || (f.genre.isEmpty() && f.year.isEmpty())) return
        val tracks = list.asSequence().flatMap { it.tracks }
        val genreOk = f.genre.isEmpty() || tracks.any { t -> t.genres.any { it.equals(f.genre, ignoreCase = true) } }
        val yearOk = f.year.isEmpty() || tracks.any { io.github.gap19.flacker.library.year(if (f.originalYear) it.originalDate else it.date) == f.year }
        if (!genreOk || !yearOk) {
            setFilter(f.copy(genre = if (genreOk) f.genre else "", year = if (yearOk) f.year else ""))
        }
    }

    private fun readText(uri: String): String? =
        covers.read(uri, LyricsText.MAX_TEXT)?.let(LyricsText::decode)

    fun album(key: String) = albums.value.firstOrNull { it.key == key }

    fun filteredAlbums(): List<Album> {
        val s = settings.value
        var list = s.filter.albums(albums.value, s.favorites, query.value)
        (screen as? Screen.Artist)?.let { a -> list = list.filter { it.artist == a.name } }
        return list
    }

    fun tracks(favoritesOnly: Boolean): List<Track> {
        val s = settings.value
        val terms = query.value.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        return albums.value.flatMap { it.tracks }.filter { t ->
            (!favoritesOnly || t.uri in s.favorites) && s.filter.accepts(t, s.favorites) &&
                "${t.title} ${t.artist} ${t.album}".lowercase(Locale.ROOT).let { text -> terms.all { it in text } }
        }
    }

    // Playback -------------------------------------------------------------------------------

    private fun item(t: Track): MediaItem {
        val cover = coverOf[t.uri] ?: t
        // Albums without art get none, rather than a path the notification keeps failing to open.
        val art = covers.file(cover).takeIf { it.isFile || cover.hasCover || covers.hasExternal(cover) }
        return PlaybackService.item(t, art)
    }

    /** Build media items off the main thread (thousands for a whole library), then edit the queue in order. */
    private fun queueEdit(tracks: List<Track>, edit: (MediaController, List<MediaItem>) -> Unit): Job =
        viewModelScope.launch {
            queueLock.withLock {
                val items = withContext(Dispatchers.Default) { tracks.map(::item) }
                val c = controller ?: return@withLock
                edit(c, items)
            }
        }

    fun play(tracks: List<Track>, start: Int = 0) {
        if (controller == null || tracks.isEmpty()) return
        playJob?.cancel()
        playJob = queueEdit(tracks) { c, items ->
            c.setMediaItems(items, start.coerceIn(0, items.size - 1), 0)
            c.prepare()
            c.play()
        }
    }

    fun playNext(t: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(listOf(t))
        queueEdit(listOf(t)) { ctl, items ->
            ctl.addMediaItems((ctl.currentMediaItemIndex + 1).coerceAtMost(ctl.mediaItemCount), items)
            messages.trySend("Added to play next.")
        }
    }

    fun append(ts: List<Track>) {
        if (controller == null || ts.isEmpty()) return
        queueEdit(ts) { c, items ->
            if (c.mediaItemCount == 0) {
                c.setMediaItems(items); c.prepare()
            } else c.addMediaItems(items)
            messages.trySend(if (ts.size == 1) "1 track added to the queue" else "${ts.size} tracks added to the queue")
        }
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(c.currentMediaItemIndex, 0)
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() = controller?.seekToNext()
    fun previous() = controller?.seekToPrevious()
    fun seek(ms: Long) = controller?.seekTo(ms.coerceAtLeast(0))
    fun jump(i: Int) = controller?.run { seekTo(i, 0); play() }
    fun position(): Long = controller?.currentPosition ?: 0
    fun bufferedTo(): Long = controller?.bufferedPosition ?: 0

    fun shuffle() = controller?.run { shuffleModeEnabled = !shuffleModeEnabled }
    fun repeat() = controller?.run {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun move(from: Int, to: Int) {
        val c = controller ?: return
        if (to in 0 until c.mediaItemCount) c.moveMediaItem(from, to)
    }

    fun remove(i: Int) {
        val c = controller ?: return
        if (i !in 0 until c.mediaItemCount) return
        removed = i to c.getMediaItemAt(i)
        c.removeMediaItem(i)
    }

    fun undoRemove() {
        val (i, item) = removed ?: return
        controller?.addMediaItem(i.coerceAtMost(controller?.mediaItemCount ?: 0), item)
        removed = null
    }

    fun randomAlbum() {
        val a = filteredAlbums().randomOrNull() ?: return run { messages.trySend("No album matches the filters.") }
        controller?.run { shuffleModeEnabled = false; repeatMode = Player.REPEAT_MODE_OFF }
        play(a.tracks)
        open(Screen.AlbumPage(a.key))
    }

    private fun refresh(timeline: Boolean) {
        val c = controller ?: return
        val queue = if (timeline || player.value.queue.size != c.mediaItemCount) {
            // One entry per player item, always, so queue positions match the player's indices.
            List(c.mediaItemCount) { i ->
                val it = c.getMediaItemAt(i)
                byUri[it.mediaId] ?: PlaybackService.track(it) ?: PlaybackService.fallback(it)
            }
        } else player.value.queue
        val current = c.currentMediaItem?.let { byUri[it.mediaId] ?: PlaybackService.track(it) }
        val old = player.value.current
        player.value = PlayerState(
            current, c.currentMediaItemIndex, queue, c.isPlaying, c.shuffleModeEnabled, c.repeatMode,
            c.duration.takeIf { it > 0 } ?: ((current?.duration ?: 0.0) * 1000).toLong(),
        )
        if (current?.uri != old?.uri) loadLyrics(current)
    }

    // Lyrics ---------------------------------------------------------------------------------

    private fun loadLyrics(t: Track?) {
        lyricsJob?.cancel()
        if (t == null) { lyrics.value = Lyrics(); return }
        lyrics.value = Lyrics("", "Reading local lyrics…")
        lyricsJob = viewModelScope.launch(Dispatchers.IO) {
            // Sidecar lyrics are found through the index, which may still be loading at start.
            indexReady.await()
            val found = findLyrics(t)
            // Reading is blocking; a slow earlier file must not replace the current track's lyrics.
            if (isActive && player.value.current?.uri == t.uri) lyrics.value = found
        }
    }

    private fun findLyrics(t: Track): Lyrics {
        var marker: String? = null
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(t.uri))!!.buffered(64 * 1024).use {
                FlacReader.read(it, t.size, details = true).lyrics
            }
        }.getOrNull()?.takeIf { it.length <= LyricsText.MAX_TEXT }?.let { raw ->
            val text = LyricsText.plain(raw)
            if (LyricsText.instrumental(raw)) marker = "Marked instrumental in the local file"
            else if (text.isNotBlank()) return Lyrics(text, "Lyrics embedded in the FLAC")
        }
        val idx = index.value
        val folder = idx.children(t.root, t.directory)
        val lyricDirs = folder.filter { it.isDir && it.name.lowercase(Locale.ROOT) in setOf("lyrics") }
            .sortedBy { it.name }
            .map { idx.children(t.root, if (t.directory.isEmpty()) it.name else "${t.directory}/${it.name}") }
        for (e in LyricsText.candidates(t, folder, lyricDirs)) {
            val raw = readText(e.uri) ?: continue
            if (LyricsText.instrumental(raw)) { marker = "Marked instrumental in the local file"; continue }
            val text = LyricsText.plain(raw)
            if (text.isNotBlank()) return Lyrics(text, e.name)
        }
        return Lyrics("", marker ?: "No local lyrics for this track")
    }

    /** All tags of a file for the edition sheet, read on demand. */
    suspend fun tags(t: Track): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(t.uri))!!.buffered(64 * 1024).use {
                FlacReader.read(it, t.size, details = true).tags
            }.toSortedMap().map { (k, v) -> k to v.joinToString("; ").take(4096) }
        }.getOrElse { listOf("Error" to (it.message ?: "Could not read the tags")) }
    }

    // Preferences ----------------------------------------------------------------------------

    fun update(change: (Settings) -> Settings) {
        settings.value = change(settings.value)
        viewModelScope.launch(writer) {
            // Always the latest state: queued writes collapse to the newest snapshot.
            runCatching { SettingsStore.save(context, settings.value) }
                .onFailure { messages.trySend("Could not save preferences (${it.message}).") }
        }
    }

    fun toggleFavorite(t: Track) = update {
        it.copy(favorites = if (t.uri in it.favorites) it.favorites - t.uri else it.favorites + t.uri)
    }

    fun setFilter(f: Filter) = update { it.copy(filter = f) }

    fun setReplayGain(mode: String) {
        update { it.copy(replayGain = mode) }
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.REFRESH_GAIN, Bundle.EMPTY),
            Bundle().apply { putString(PlaybackService.MODE, mode) },
        )
    }

    fun setPreferEmbedded(on: Boolean) {
        update { it.copy(preferEmbedded = on) }
        covers.preferEmbedded = on
        viewModelScope.launch(Dispatchers.IO) { covers.warm(albums.value) }
    }

    fun cycleClock() = update { it.copy(clock = (it.clock + 1) % 3) }

    /** Remaining time of the current album in queue order; unknown in shuffle. */
    fun albumRemainingMs(): Long? {
        val p = player.value
        val c = controller ?: return null
        if (p.shuffle || p.current == null || p.index < 0) return null
        val key = p.current.albumKey
        val rest = p.queue.drop(p.index).takeWhile { it.albumKey == key }.sumOf { it.duration }
        return (rest * 1000).toLong() - c.currentPosition
    }

    override fun onCleared() {
        // Also covers a controller that is still connecting.
        MediaController.releaseFuture(future)
    }

    private companion object {
        const val STARTED = "started"

        /** Order-independent fingerprint of a track set, to tell whether the saved shelf matches the index. */
        fun stamp(tracks: List<Track>): Long =
            tracks.fold(tracks.size.toLong()) { acc, t -> acc + (t.uri.hashCode().toLong() * 1_000_003L xor t.modified xor t.size) }
    }
}
