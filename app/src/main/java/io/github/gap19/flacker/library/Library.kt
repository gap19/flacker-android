package io.github.gap19.flacker.library

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * A child of a library folder, remembered so covers, lyrics and scans need no new queries.
 * [modified] of a folder lets the next scan skip it when nothing was added, removed or renamed.
 */
data class Entry(
    val name: String,
    val uri: String,
    val isDir: Boolean,
    val size: Long = 0,
    val modified: Long = 0,
    val id: String = "",
)

/**
 * A folder can be reused from the previous index without asking the provider again when its
 * modification time (seen in its parent's fresh listing) is known and unchanged, and it held no
 * subfolders: adding, removing or renaming a file always changes the folder's time.
 */
fun canReuse(observed: Long, previous: Long?, children: List<Entry>?): Boolean =
    observed > 0 && previous == observed && children != null && children.none { it.isDir }

class LibraryIndex(
    val tracks: List<Track>,
    /** "root\u001frelative/dir" → children. */
    val dirs: Map<String, List<Entry>>,
    val errors: List<String> = emptyList(),
    /** Format of the saved index; older entries may need reading again (see [VERSION]). */
    val version: Int = VERSION,
) {
    fun children(root: String, dir: String): List<Entry> = dirs[dirKey(root, dir)].orEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("tracks", JSONArray().apply { tracks.forEach { put(it.toJson()) } })
        put("errors", JSONArray(errors))
        put("dirs", JSONObject().apply {
            for ((k, v) in dirs) put(k, JSONArray().apply {
                v.forEach { put(JSONArray().put(it.name).put(it.uri).put(it.isDir).put(it.size).put(it.modified).put(it.id)) }
            })
        })
    }

    companion object {
        /** 2: embedded pictures other than the front cover count as artwork. */
        const val VERSION = 2
        val EMPTY = LibraryIndex(emptyList(), emptyMap())
        fun dirKey(root: String, dir: String) = "$root\u001f$dir"

        fun fromJson(o: JSONObject): LibraryIndex {
            val ts = o.getJSONArray("tracks")
            val tracks = List(ts.length()) { Track.fromJson(ts.getJSONObject(it)) }
            val d = o.getJSONObject("dirs")
            val dirs = HashMap<String, List<Entry>>()
            for (k in d.keys()) {
                val a = d.getJSONArray(k)
                dirs[k] = List(a.length()) { i ->
                    val e = a.getJSONArray(i)
                    // Indexes before 1.0.1 stored only name, URI and kind.
                    Entry(e.getString(0), e.getString(1), e.getBoolean(2), e.optLong(3), e.optLong(4), e.optString(5))
                }
            }
            val e = o.optJSONArray("errors")
            return LibraryIndex(
                tracks, dirs, if (e == null) emptyList() else List(e.length()) { e.getString(it) },
                o.optInt("version", 1),
            )
        }
    }
}

/** Hidden folders, folders starting with "_" (tools, backups, work folders) and build folders are skipped. */
fun excluded(name: String) = name.startsWith('.') || name.startsWith('_') ||
    name in setOf("Provenance", "node_modules", "target")

/**
 * Walks the chosen document trees. Files whose size and modification time match the
 * previous index are reused, so only new or changed FLACs are opened.
 */
class Scanner(private val context: Context) {
    private val resolver get() = context.contentResolver

    /** What a scan did, for the log and the progress line. */
    class Stats {
        val listed = AtomicInteger()
        val reused = AtomicInteger()
        val queued = AtomicInteger()
        val read = AtomicInteger()
    }

    /**
     * [full] lists every folder and compares every file; otherwise unchanged album folders are
     * taken from [previous] and only new or changed files are opened. Folders are listed in
     * parallel: each listing is an IPC round trip to the documents provider, and the provider
     * serves several at once. [progress] receives (new files read, new files found so far).
     */
    fun scan(
        roots: List<Uri>,
        previous: LibraryIndex,
        cancelled: () -> Boolean,
        full: Boolean = false,
        progress: (Int, Int) -> Unit,
    ): LibraryIndex {
        val started = SystemClock.elapsedRealtime()
        val walk = Walk(previous, full, cancelled, progress)
        try {
            return walk.run(roots)
        } finally {
            walk.close()
            val s = walk.stats
            android.util.Log.i(
                "FLACker",
                "Scan${if (full) " (full)" else ""}: ${s.listed} folders listed, ${s.reused} reused, " +
                    "${s.read} of ${s.queued} new files read in ${SystemClock.elapsedRealtime() - started} ms",
            )
        }
    }

    private class Pending(val tree: Uri, val root: String, val id: String, val rel: String, val modified: Long)

    private inner class Walk(
        val previous: LibraryIndex,
        val full: Boolean,
        val cancelled: () -> Boolean,
        val progress: (Int, Int) -> Unit,
    ) {
        val stats = Stats()
        private val tracks = ConcurrentLinkedQueue<Track>()
        private val errors = ConcurrentLinkedQueue<String>()
        private val dirs = ConcurrentHashMap<String, List<Entry>>()
        // Entries from an older index format are read again only where the format changed
        // what they would hold: tracks without artwork, for the non-front cover rule.
        private val cached = previous.tracks
            .filter { previous.version >= 2 || it.hasCover }
            .associateBy { it.uri }
        private val previousTimes = HashMap<String, Long>()
        private val lister = Executors.newFixedThreadPool(LISTERS)
        private val readers = Executors.newFixedThreadPool(READERS)
        private val reads = ConcurrentLinkedQueue<Future<*>>()
        private val outstanding = AtomicInteger()
        private val idle = Object()
        @Volatile private var failure: Throwable? = null

        init {
            // Folder times recorded at the last scan, from each parent's listing.
            for ((key, entries) in previous.dirs) {
                val root = key.substringBefore('\u001f')
                val rel = key.substringAfter('\u001f')
                for (e in entries) if (e.isDir) {
                    previousTimes[LibraryIndex.dirKey(root, if (rel.isEmpty()) e.name else "$rel/${e.name}")] = e.modified
                }
            }
        }

        fun run(roots: List<Uri>): LibraryIndex {
            for (tree in roots) {
                val root = tree.toString()
                val rootId = try {
                    DocumentsContract.getTreeDocumentId(tree)
                } catch (e: IllegalArgumentException) {
                    errors += "Invalid folder: $root"; continue
                }
                submit(Pending(tree, root, rootId, "", 0))
            }
            // Wait for every folder, checking for cancellation now and then.
            synchronized(idle) {
                while (outstanding.get() > 0 && failure == null) {
                    if (cancelled()) return partial()
                    idle.wait(100)
                }
            }
            failure?.let { throw it }
            for (r in reads) {
                if (cancelled()) return partial()
                r.get()
            }
            report()
            // Parallel work finishes in any order; keep the index deterministic.
            return LibraryIndex(tracks.sortedBy { it.root + "\u0000" + it.path }, HashMap(dirs), errors.toList())
        }

        fun close() {
            lister.shutdownNow()
            readers.shutdownNow()
        }

        private fun partial() = LibraryIndex(tracks.toList(), HashMap(dirs), errors.toList() + "Reading canceled")

        private fun report() = progress(stats.read.get(), stats.queued.get())

        private fun submit(p: Pending) {
            outstanding.incrementAndGet()
            lister.execute {
                try {
                    if (!cancelled()) visit(p)
                } catch (e: Throwable) {
                    failure = e
                } finally {
                    if (outstanding.decrementAndGet() == 0 || failure != null) synchronized(idle) { idle.notifyAll() }
                }
            }
        }

        private fun read(uri: String, root: String, path: String, size: Long, modified: Long) {
            if (size in 1 until 42) { errors += "Waiting for a complete file: $path"; return }
            stats.queued.incrementAndGet()
            report()
            reads += readers.submit {
                try {
                    val m = resolver.openInputStream(Uri.parse(uri))!!.buffered(16 * 1024).use { FlacReader.read(it, size) }
                    tracks += FlacReader.track(m, uri, root, path, size, modified)
                } catch (e: Throwable) {
                    // OutOfMemoryError on a pathological file must not end the whole scan.
                    errors += "$path: ${e.message ?: e.javaClass.simpleName}"
                }
                stats.read.incrementAndGet()
                report()
            }
        }

        private fun isFlac(name: String) = !name.startsWith('.') && name.lowercase(Locale.ROOT).endsWith(".flac")

        private fun visit(dir: Pending) {
            val key = LibraryIndex.dirKey(dir.root, dir.rel)
            val known = previous.dirs[key]
            if (!full && canReuse(dir.modified, previousTimes[key], known)) {
                // Nothing was added, removed or renamed here since the last scan.
                stats.reused.incrementAndGet()
                dirs[key] = known!!
                for (e in known) {
                    if (!isFlac(e.name)) continue
                    val path = if (dir.rel.isEmpty()) e.name else "${dir.rel}/${e.name}"
                    val old = cached[e.uri]
                    // Files that failed or were incomplete last time are tried again.
                    if (old != null && old.path == path) tracks += old else read(e.uri, dir.root, path, e.size, e.modified)
                }
                return
            }
            val children = try {
                list(dir.tree, dir.id).also { stats.listed.incrementAndGet() }
            } catch (e: Exception) {
                errors += "Folder unavailable: ${dir.rel.ifEmpty { "/" }} (${e.message})"
                if (dir.rel.isEmpty()) {
                    // An unmounted card or a lost permission is often temporary: keep what was known.
                    tracks += previous.tracks.filter { it.root == dir.root }
                    dirs.putAll(previous.dirs.filterKeys { it.startsWith(LibraryIndex.dirKey(dir.root, "")) })
                }
                return
            }
            dirs[key] = children.map { Entry(it.name, it.uri, it.isDir, it.size, it.modified, it.id) }
            for (c in children) {
                val path = if (dir.rel.isEmpty()) c.name else "${dir.rel}/${c.name}"
                if (c.isDir) {
                    if (!excluded(c.name)) submit(Pending(dir.tree, dir.root, c.id, path, c.modified))
                    continue
                }
                // Hidden files include macOS "._name.flac" resource forks, which are not audio.
                if (!isFlac(c.name)) continue
                val old = cached[c.uri]
                if (old != null && old.size == c.size && old.modified == c.modified && old.path == path) {
                    tracks += old
                } else read(c.uri, dir.root, path, c.size, c.modified)
            }
        }
    }

    private companion object {
        /** Concurrent folder listings; the external storage provider answers them in parallel. */
        const val LISTERS = 8
        /** Concurrent FLAC header reads. */
        const val READERS = 4
    }

    private class Child(val id: String, val name: String, val uri: String, val isDir: Boolean, val size: Long, val modified: Long)

    private fun list(tree: Uri, docId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<Child>()
        resolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val dir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                out += Child(
                    id, name, DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), dir,
                    if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4),
                )
            }
        }
        return out
    }
}

object LibraryStore {
    private fun file(context: Context) = File(context.filesDir, "library.json")

    fun load(context: Context): LibraryIndex = try {
        io.github.gap19.flacker.data.Durable.read(file(context))?.let { LibraryIndex.fromJson(JSONObject(it)) } ?: LibraryIndex.EMPTY
    } catch (_: Exception) {
        LibraryIndex.EMPTY
    }

    fun save(context: Context, index: LibraryIndex) =
        io.github.gap19.flacker.data.Durable.write(file(context), index.toJson().toString())
}

/** Albums in collection order; each album in playlist order, else disc, track and path. */
fun albums(index: LibraryIndex, readText: (String) -> String?): List<Album> =
    index.tracks.groupBy { it.albumKey }.map { (key, group) ->
        var ts = group.sortedWith(compareBy<Track>({ it.disc }, { it.number }, { it.path }))
        val first = ts.first()
        val playlist = index.children(first.root, first.albumDir)
            .firstOrNull { it.name.equals("playlist.m3u8", true) || it.name.equals("playlist.m3u", true) }
        if (playlist != null) {
            readText(playlist.uri)?.let { text ->
                val order = playlistOrder(text, first.albumDir)
                if (order.isNotEmpty()) ts = ts.sortedBy { t -> order.rank(t.path) }
            }
        }
        val head = ts.first()
        Album(key, head.album, head.albumArtist, year(head.date).ifEmpty { head.date.take(4) }, ts)
    }.sortedWith(
        compareBy<Album>({ it.artist.lowercase(Locale.ROOT) }, { it.year }, { it.title }, { it.key })
    )

/** Entries of an M3U/M3U8 resolved against the playlist's folder. Remote URLs are ignored. */
fun playlistOrder(text: String, dir: String): List<String> {
    val out = ArrayList<String>()
    for (raw in text.removePrefix("\uFEFF").lines()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        var p = when {
            line.startsWith("file:") -> percentDecode(line.removePrefix("file://").removePrefix("file:"))
            line.contains("://") -> continue
            else -> line
        }.replace('\\', '/')
        if (!p.lowercase(Locale.ROOT).endsWith(".flac")) continue
        if (!p.startsWith("/")) p = normalize(if (dir.isEmpty()) p else "$dir/$p")
        out += p
    }
    return out
}

/** Decode %XX sequences as UTF-8; '+' stays literal, as in file URIs. */
internal fun percentDecode(s: String): String {
    val out = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 2 < s.length && s.substring(i + 1, i + 3).toIntOrNull(16) != null) {
            out.write(s.substring(i + 1, i + 3).toInt(16)); i += 3
        } else {
            out.write(c.toString().toByteArray(Charsets.UTF_8)); i++
        }
    }
    return out.toString("UTF-8")
}

private fun normalize(path: String): String {
    val parts = ArrayList<String>()
    for (s in path.split('/')) when (s) {
        "", "." -> {}
        ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
        else -> parts += s
    }
    return parts.joinToString("/")
}

/** Position of a track in the playlist: exact relative path, else the same trailing path or file name. */
private fun List<String>.rank(path: String): Int {
    indexOf(path).takeIf { it >= 0 }?.let { return it }
    val tail = path.split('/').takeLast(2).joinToString("/")
    indexOfFirst { it.endsWith("/$tail") || it == tail }.takeIf { it >= 0 }?.let { return it }
    val name = path.substringAfterLast('/')
    return indexOfFirst { it.substringAfterLast('/') == name }.takeIf { it >= 0 } ?: Int.MAX_VALUE
}
