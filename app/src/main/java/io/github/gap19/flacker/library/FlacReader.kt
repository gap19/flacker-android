package io.github.gap19.flacker.library

import java.io.IOException
import java.io.InputStream

/**
 * Bounded FLAC header reader, ported from the desktop player. Reads STREAMINFO,
 * Vorbis comments and optionally the front cover; never reads audio frames.
 */
object FlacReader {
    private const val MAX_METADATA = 64L * 1024 * 1024
    private val LYRIC_KEYS = listOf(
        "UNSYNCEDLYRICS", "LYRICS", "USER_UNSYNCED_LYRICS", "UNSYNCED LYRICS",
        "SYNCEDLYRICS", "SYNCHRONIZEDLYRICS",
    )

    class Metadata(
        val sampleRate: Int,
        val channels: Int,
        val bits: Int,
        val duration: Double,
        val tags: Map<String, List<String>>,
        val picture: ByteArray?,
        val hasCover: Boolean,
        val hasLyrics: Boolean,
    ) {
        fun get(vararg keys: String): String =
            keys.firstNotNullOfOrNull { k -> tags[k]?.firstOrNull { it.isNotBlank() } }.orEmpty()

        val lyrics: String get() = get(*LYRIC_KEYS.toTypedArray())
    }

    /**
     * [details] keeps lyric tags (large); [cover] keeps the front picture bytes.
     * The stream is read sequentially and skipped past unused blocks.
     */
    fun read(input: InputStream, size: Long, details: Boolean = false, cover: Boolean = false): Metadata {
        var magic = input.readFully(4)
        // Some taggers put an ID3v2 tag before the FLAC stream; the player handles such
        // files, so the index reads past it too.
        var start = 0L
        if (magic[0].toInt() == 0x49 && magic[1].toInt() == 0x44 && magic[2].toInt() == 0x33) {
            val rest = input.readFully(6)
            var tagSize = 0L
            for (i in 2 until 6) tagSize = (tagSize shl 7) or (rest[i].toLong() and 0x7f)
            val footer = if (rest[1].toInt() and 0x10 != 0) 10L else 0L
            input.skipFully(tagSize + footer)
            start = 10 + tagSize + footer
            magic = input.readFully(4)
        }
        if (!magic.contentEquals(byteArrayOf(0x66, 0x4c, 0x61, 0x43))) {
            throw IOException("FLAC header missing; file incomplete or unsupported")
        }
        var total = start + 4
        var first = true
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var duration = 0.0
        val tags = LinkedHashMap<String, MutableList<String>>()
        var picture: ByteArray? = null
        var pictureRank = Int.MAX_VALUE
        var hasCover = false
        var hasLyrics = false
        while (true) {
            val h = input.readFully(4)
            val kind = h[0].toInt() and 127
            val last = h[0].toInt() and 128 != 0
            val len = ((h[1].toInt() and 255) shl 16) or ((h[2].toInt() and 255) shl 8) or (h[3].toInt() and 255)
            total += 4 + len
            if (total > MAX_METADATA || (size > 0 && total > size)) {
                throw IOException("FLAC: metadata limit reached or file incomplete")
            }
            if (first && (kind != 0 || len != 34)) throw IOException("FLAC: invalid STREAMINFO")
            first = false
            when {
                kind == 0 -> {
                    val b = input.readFully(len)
                    var v = 0L
                    for (i in 10 until 18) v = (v shl 8) or (b[i].toLong() and 255)
                    sampleRate = (v ushr 44).toInt()
                    channels = ((v ushr 41) and 7).toInt() + 1
                    bits = ((v ushr 36) and 31).toInt() + 1
                    val samples = v and 0xfffffffffL
                    if (sampleRate == 0 || samples == 0L) throw IOException("FLAC has no finished samples yet")
                    duration = samples.toDouble() / sampleRate
                }
                kind == 4 -> {
                    val b = input.readFully(len)
                    val r = Cursor(b)
                    r.bytes(r.le32())
                    val n = r.le32()
                    if (n > 100_000) throw IOException("FLAC: too many comments")
                    repeat(n) {
                        val value = String(r.bytes(r.le32()), Charsets.UTF_8)
                        val eq = value.indexOf('=')
                        if (eq > 0) {
                            val key = value.substring(0, eq).uppercase(java.util.Locale.ROOT)
                            val v = value.substring(eq + 1)
                            if (key in LYRIC_KEYS) {
                                if (v.isNotBlank()) hasLyrics = true
                                if (!details) return@repeat
                            }
                            tags.getOrPut(key) { mutableListOf() }.add(v)
                        }
                    }
                }
                kind == 6 && cover -> {
                    val r = Cursor(input.readFully(len))
                    val type = r.be32()
                    val mime = r.bytes(r.be32())
                    r.bytes(r.be32())
                    val width = r.be32()
                    val height = r.be32()
                    r.bytes(8)
                    val n = r.be32()
                    val data = r.bytes(n)
                    val rank = coverRank(type)
                    if (rank < pictureRank && !mime.contentEquals("-->".toByteArray())) {
                        hasCover = true
                        if (n <= 12 * 1024 * 1024 && width <= 12000 && height <= 12000) {
                            picture = data
                            pictureRank = rank
                        }
                    }
                }
                kind == 6 && len >= 4 -> {
                    val t = Cursor(input.readFully(4)).be32()
                    if (coverRank(t) != Int.MAX_VALUE) hasCover = true
                    input.skipFully(len - 4L)
                }
                else -> input.skipFully(len.toLong())
            }
            if (last) break
        }
        return Metadata(sampleRate, channels, bits, duration, tags, picture, hasCover, hasLyrics)
    }

    /**
     * Which embedded picture shows the album: the front cover, else an untyped picture, else
     * any other picture except file icons, with the back cover last.
     */
    private fun coverRank(type: Int): Int = when (type) {
        3 -> 0
        0 -> 1
        1, 2 -> Int.MAX_VALUE
        4 -> 3
        else -> 2
    }

    /** Build a library entry; titles and albums fall back to file and folder names. */
    fun track(m: Metadata, uri: String, root: String, path: String, size: Long, modified: Long): Track {
        fun number(key: String, default: Int) =
            m.get(key).substringBefore('/').trim().toIntOrNull() ?: default
        fun float(key: String) = m.get(key).trim().split(Regex("\\s+")).firstOrNull()
            ?.toDoubleOrNull()?.takeIf { it.isFinite() }
        val name = path.substringAfterLast('/')
        val folder = path.substringBeforeLast('/', "").substringAfterLast('/')
        return Track(
            uri = uri, root = root, path = path,
            title = m.get("TITLE").ifEmpty { name.substringBeforeLast('.') },
            artist = m.get("ARTIST"),
            albumArtist = m.get("ALBUMARTIST", "ALBUM ARTIST", "ARTIST"),
            album = m.get("ALBUM").ifEmpty { folder },
            date = m.get("DATE"),
            originalDate = m.get("ORIGINALDATE", "ORIGINALYEAR"),
            releaseId = m.get("RELEASE_ID", "MUSICBRAINZ_ALBUMID"),
            genres = m.tags["GENRE"].orEmpty(),
            disc = number("DISCNUMBER", 1),
            number = number("TRACKNUMBER", 0),
            discTotal = number("DISCTOTAL", number("TOTALDISCS", 1)),
            duration = m.duration, sampleRate = m.sampleRate, bits = m.bits, channels = m.channels,
            size = size, modified = modified,
            gainTrack = float("REPLAYGAIN_TRACK_GAIN"), gainAlbum = float("REPLAYGAIN_ALBUM_GAIN"),
            peakTrack = float("REPLAYGAIN_TRACK_PEAK"), peakAlbum = float("REPLAYGAIN_ALBUM_PEAK"),
            hasCover = m.hasCover, hasLyrics = m.hasLyrics,
        )
    }

    private class Cursor(val b: ByteArray) {
        var p = 0
        fun bytes(n: Int): ByteArray {
            if (n < 0 || p + n > b.size) throw IOException("FLAC: truncated block")
            return b.copyOfRange(p, p + n).also { p += n }
        }
        fun le32(): Int {
            val a = bytes(4)
            val v = (a[0].toLong() and 255) or ((a[1].toLong() and 255) shl 8) or
                ((a[2].toLong() and 255) shl 16) or ((a[3].toLong() and 255) shl 24)
            if (v > Int.MAX_VALUE) throw IOException("FLAC: invalid size")
            return v.toInt()
        }
        fun be32(): Int {
            val a = bytes(4)
            val v = ((a[0].toLong() and 255) shl 24) or ((a[1].toLong() and 255) shl 16) or
                ((a[2].toLong() and 255) shl 8) or (a[3].toLong() and 255)
            if (v > Int.MAX_VALUE) throw IOException("FLAC: truncated picture")
            return v.toInt()
        }
    }
}

internal fun InputStream.readFully(n: Int): ByteArray {
    val b = ByteArray(n)
    var off = 0
    while (off < n) {
        val r = read(b, off, n - off)
        if (r < 0) throw IOException("FLAC: truncated file")
        off += r
    }
    return b
}

internal fun InputStream.skipFully(n: Long) {
    var left = n
    while (left > 0) {
        val s = skip(left)
        if (s > 0) left -= s
        else if (read() < 0) throw IOException("FLAC: truncated file") else left--
    }
}
