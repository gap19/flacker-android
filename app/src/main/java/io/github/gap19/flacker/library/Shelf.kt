package io.github.gap19.flacker.library

import android.content.Context
import io.github.gap19.flacker.data.Durable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * The shelf exactly as last shown: albums in collection order, tracks in playlist order. A
 * compact binary file read at start, so saved albums appear at once instead of after parsing
 * the full index and reading every album's playlist again.
 */
object ShelfStore {
    private const val MAGIC = 0x464C4B53 // "FLKS"
    private const val VERSION = 1

    private fun file(c: Context) = File(c.filesDir, "shelf.bin")

    fun save(c: Context, albums: List<Album>) = Durable.writeBytes(file(c), encode(albums))

    /** Saved albums, or null when there is no readable shelf (first start, older version). */
    fun load(c: Context): List<Album>? = try {
        Durable.readBytes(file(c))?.let { decode(it) }
    } catch (_: Exception) {
        null
    }

    internal fun decode(bytes: ByteArray): List<Album> = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        if (input.readInt() != MAGIC || input.readInt() != VERSION) throw IOException("Unknown shelf format")
        List(input.readInt()) {
            val key = input.readUTF(); val title = input.readUTF(); val artist = input.readUTF(); val year = input.readUTF()
            Album(key, title, artist, year, List(input.readInt()) { read(input) })
        }
    }

    internal fun encode(albums: List<Album>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(albums.size)
            for (a in albums) {
                out.text(a.key); out.text(a.title); out.text(a.artist); out.text(a.year)
                out.writeInt(a.tracks.size)
                a.tracks.forEach { write(out, it) }
            }
        }
        return bytes.toByteArray()
    }

    // writeUTF holds up to 64 KiB of modified UTF-8; metadata longer than that is cut, not lost from the index.
    private fun DataOutputStream.text(s: String) = writeUTF(if (s.length > 16_000) s.take(16_000) else s)

    private fun DataOutputStream.optional(v: Double?) {
        writeBoolean(v != null); if (v != null) writeDouble(v)
    }

    private fun DataInputStream.optional(): Double? = if (readBoolean()) readDouble() else null

    private fun write(out: DataOutputStream, t: Track) = with(out) {
        text(t.uri); text(t.root); text(t.path); text(t.title); text(t.artist); text(t.albumArtist); text(t.album)
        text(t.date); text(t.originalDate); text(t.releaseId)
        writeInt(t.genres.size); t.genres.forEach { text(it) }
        writeInt(t.disc); writeInt(t.number); writeInt(t.discTotal); writeDouble(t.duration)
        writeInt(t.sampleRate); writeInt(t.bits); writeInt(t.channels); writeLong(t.size); writeLong(t.modified)
        optional(t.gainTrack); optional(t.gainAlbum); optional(t.peakTrack); optional(t.peakAlbum)
        writeBoolean(t.hasCover); writeBoolean(t.hasLyrics)
    }

    private fun read(input: DataInputStream): Track = with(input) {
        Track(
            uri = readUTF(), root = readUTF(), path = readUTF(), title = readUTF(), artist = readUTF(),
            albumArtist = readUTF(), album = readUTF(), date = readUTF(), originalDate = readUTF(), releaseId = readUTF(),
            genres = List(readInt()) { readUTF() },
            disc = readInt(), number = readInt(), discTotal = readInt(), duration = readDouble(),
            sampleRate = readInt(), bits = readInt(), channels = readInt(), size = readLong(), modified = readLong(),
            gainTrack = optional(), gainAlbum = optional(), peakTrack = optional(), peakAlbum = optional(),
            hasCover = readBoolean(), hasLyrics = readBoolean(),
        )
    }
}
