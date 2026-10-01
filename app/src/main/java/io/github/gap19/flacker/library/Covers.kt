package io.github.gap19.flacker.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Cover thumbnails: one shared size for grids, cards, the mini player and lists, a
 * bounded memory LRU and a disk cache. After each library read every album cover is
 * warmed in the background so switching screens never decodes again.
 */
class Covers(private val context: Context, private val index: () -> LibraryIndex) {
    companion object {
        const val THUMB = 320
        const val LARGE = 1080
        private val EXTERNAL = listOf("cover.jpg", "folder.jpg", "cover.png", "folder.png", "front.jpg", "cover.jpeg")
        val EXTRA_DIRS = setOf("artwork", "booklet", "covers", "scans", "images")
        val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    }

    /** Compose state, so covers on screen reload when the preference changes. */
    var preferEmbedded by androidx.compose.runtime.mutableStateOf(true)
    private val memory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(96L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val dir = File(context.cacheDir, "covers").apply { mkdirs() }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(2)

    private fun key(t: Track, size: Int) = "${t.uri}:${t.modified}:$size:$preferEmbedded"

    fun cached(t: Track, size: Int = THUMB): Bitmap? = memory.get(key(t, size))

    suspend fun load(t: Track, size: Int = THUMB): Bitmap? {
        cached(t, size)?.let { return it }
        return withContext(io) {
            val k = key(t, size)
            memory.get(k) ?: decode(t, size, k)?.also { memory.put(k, it) }
        }
    }

    /** File of the shared thumbnail, for the media notification and lock screen. */
    fun file(t: Track, size: Int = THUMB): File = File(dir, digest(key(t, size)) + ".jpg")

    suspend fun warm(albums: List<Album>) {
        for (a in albums) load(a.cover)
    }

    private fun decode(t: Track, size: Int, k: String): Bitmap? {
        val target = File(dir, digest(k) + ".jpg")
        if (target.isFile) BitmapFactory.decodeFile(target.path)?.let { return it }
        val bytes = (if (preferEmbedded) embedded(t) ?: external(t) else external(t) ?: embedded(t)) ?: return null
        val bitmap = scaled(bytes, size) ?: return null
        runCatching {
            val tmp = File(dir, target.name + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            tmp.renameTo(target)
        }
        return bitmap
    }

    private fun embedded(t: Track): ByteArray? = runCatching {
        context.contentResolver.openInputStream(Uri.parse(t.uri))!!.buffered(64 * 1024).use {
            FlacReader.read(it, t.size, cover = true).picture
        }
    }.getOrNull()

    private fun externalEntry(t: Track): Entry? {
        val entries = index().children(t.root, t.albumDir) + index().children(t.root, t.directory)
        return EXTERNAL.firstNotNullOfOrNull { name ->
            entries.firstOrNull { !it.isDir && it.name.lowercase(Locale.ROOT) == name }
        }
    }

    /** cover.jpg, folder.jpg or similar next to the album. */
    fun hasExternal(t: Track) = externalEntry(t) != null

    private fun external(t: Track): ByteArray? = externalEntry(t)?.let { read(it.uri, 20 * 1024 * 1024) }

    fun read(uri: String, limit: Int): ByteArray? = runCatching {
        context.contentResolver.openInputStream(Uri.parse(uri))!!.use { s ->
            val b = s.readNBytesCompat(limit + 1)
            b.takeIf { it.size <= limit }
        }
    }.getOrNull()

    /** Decode an image no larger than [size] on its longest side. */
    fun scaled(bytes: ByteArray, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth.toLong() * bounds.outHeight > 64_000_000L) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val longest = maxOf(raw.width, raw.height)
        if (longest <= size) return raw
        val scale = size.toFloat() / longest
        return Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt().coerceAtLeast(1), (raw.height * scale).toInt().coerceAtLeast(1), true)
            .also { if (it != raw) raw.recycle() }
    }

    /** Booklet, back covers and scans of an album, in natural name order. */
    fun extras(album: Album): List<Entry> {
        val t = album.cover
        val idx = index()
        val out = ArrayList<Entry>()
        fun walk(dir: String, depth: Int) {
            for (e in idx.children(t.root, dir)) {
                val lower = e.name.lowercase(Locale.ROOT)
                if (e.isDir) {
                    if (depth < 5 && !e.name.startsWith(".") && (depth > 0 || lower in EXTRA_DIRS)) {
                        walk(if (dir.isEmpty()) e.name else "$dir/${e.name}", depth + 1)
                    }
                } else if (lower.substringAfterLast('.', "") in IMAGE_EXT + "pdf") {
                    out += e
                }
            }
        }
        walk(t.albumDir, 0)
        return out.sortedWith { a, b -> naturalCompare(a.name, b.name) }
    }

    private fun digest(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(40)

    /** Keep the disk cache under 256 MiB, oldest first. */
    fun prune() {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= 256L * 1024 * 1024) break
            total -= f.length(); f.delete()
        }
    }
}

internal fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val r = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (r < 0) break
        out.write(buf, 0, r)
    }
    return out.toByteArray()
}

/** "page 2" sorts before "page 10". */
fun naturalCompare(a: String, b: String): Int {
    val re = Regex("\\d+|\\D+")
    val x = re.findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
    val y = re.findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
    for (i in 0 until minOf(x.size, y.size)) {
        val p = x[i]; val q = y[i]
        val c = if (p[0].isDigit() && q[0].isDigit()) {
            p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 }
                ?: p.trimStart('0').compareTo(q.trimStart('0'))
        } else p.compareTo(q)
        if (c != 0) return c
    }
    return x.size.compareTo(y.size)
}
