package io.github.gap19.flacker

import io.github.gap19.flacker.data.Filter
import io.github.gap19.flacker.library.Entry
import io.github.gap19.flacker.library.FlacReader
import io.github.gap19.flacker.library.LibraryIndex
import io.github.gap19.flacker.library.LyricsText
import io.github.gap19.flacker.library.Track
import io.github.gap19.flacker.library.albums
import io.github.gap19.flacker.library.count
import io.github.gap19.flacker.library.excluded
import io.github.gap19.flacker.library.naturalCompare
import io.github.gap19.flacker.library.percentDecode
import io.github.gap19.flacker.library.playlistOrder
import io.github.gap19.flacker.library.time
import io.github.gap19.flacker.library.year
import io.github.gap19.flacker.playback.PlaybackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class CoreTest {
    /** A minimal FLAC header: STREAMINFO, Vorbis comments and an optional front cover. */
    private fun flac(
        tags: List<String>, rate: Int = 96000, bits: Int = 24, samples: Long = 96000L * 3, cover: ByteArray? = null,
        pictures: List<Pair<Int, ByteArray>> = listOfNotNull(cover?.let { 3 to it }),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        fun header(kind: Int, len: Int, last: Boolean) {
            out.write((if (last) 128 else 0) or kind); out.write(len shr 16); out.write(len shr 8 and 255); out.write(len and 255)
        }
        fun le(v: Int, o: ByteArrayOutputStream) { for (i in 0 until 4) o.write(v shr (8 * i) and 255) }
        fun be(v: Int, o: ByteArrayOutputStream) { for (i in 3 downTo 0) o.write(v shr (8 * i) and 255) }
        val info = ByteArray(34)
        val v = (rate.toLong() shl 44) or (1L shl 41) or ((bits - 1).toLong() shl 36) or samples
        for (i in 0 until 8) info[10 + i] = (v ushr (56 - 8 * i)).toByte()
        val comments = ByteArrayOutputStream().apply {
            val vendor = "test".toByteArray(); le(vendor.size, this); write(vendor)
            le(tags.size, this)
            tags.forEach { val b = it.toByteArray(); le(b.size, this); write(b) }
        }.toByteArray()
        val blocks = pictures.map { (type, data) ->
            ByteArrayOutputStream().apply {
                be(type, this); val mime = "image/png".toByteArray(); be(mime.size, this); write(mime)
                be(0, this); be(10, this); be(10, this); be(24, this); be(0, this); be(data.size, this); write(data)
            }.toByteArray()
        }
        header(0, 34, false); out.write(info)
        header(4, comments.size, blocks.isEmpty()); out.write(comments)
        blocks.forEachIndexed { i, b -> header(6, b.size, i == blocks.lastIndex); out.write(b) }
        out.write(ByteArray(16))
        return out.toByteArray()
    }

    @Test fun readsStreamInfoAndTags() {
        val bytes = flac(listOf("TITLE=First Light", "ARTIST=Example Artist", "ALBUM=Example Album", "TRACKNUMBER=1/10", "DISCNUMBER=1", "DATE=1994", "RELEASE_ID=abc", "REPLAYGAIN_TRACK_GAIN=-6.5 dB", "LYRICS=Line"))
        val m = FlacReader.read(ByteArrayInputStream(bytes), bytes.size.toLong())
        assertEquals(96000, m.sampleRate); assertEquals(24, m.bits); assertEquals(2, m.channels)
        assertEquals(3.0, m.duration, 1e-9)
        assertTrue(m.hasLyrics)
        assertEquals("", m.lyrics) // lyrics are kept only when details are requested
        val t = FlacReader.track(m, "u", "r", "Example Artist/1994 - Example Album/01 - First Light.flac", bytes.size.toLong(), 1)
        assertEquals("First Light", t.title); assertEquals("Example Artist", t.albumArtist); assertEquals(1, t.number)
        assertEquals(-6.5, t.gainTrack!!, 1e-9); assertEquals("abc", t.releaseId)
        assertEquals("Line", FlacReader.read(ByteArrayInputStream(bytes), bytes.size.toLong(), details = true).lyrics)
    }

    @Test fun keepsFrontCoverOnlyWhenAsked() {
        val png = byteArrayOf(1, 2, 3, 4)
        val bytes = flac(listOf("TITLE=x"), cover = png)
        val quick = FlacReader.read(ByteArrayInputStream(bytes), bytes.size.toLong())
        assertTrue(quick.hasCover); assertEquals(null, quick.picture)
        assertTrue(FlacReader.read(ByteArrayInputStream(bytes), bytes.size.toLong(), cover = true).picture!!.contentEquals(png))
    }

    @Test fun frontCoverWinsAndOtherPicturesAreAFallback() {
        fun pick(vararg p: Pair<Int, ByteArray>): FlacReader.Metadata {
            val bytes = flac(listOf("TITLE=x"), pictures = p.toList())
            return FlacReader.read(ByteArrayInputStream(bytes), bytes.size.toLong(), cover = true)
        }
        val back = byteArrayOf(4); val icon = byteArrayOf(1); val front = byteArrayOf(3); val other = byteArrayOf(0)
        assertTrue(pick(4 to back, 1 to icon, 3 to front, 0 to other).picture!!.contentEquals(front))
        assertTrue(pick(4 to back, 0 to other).picture!!.contentEquals(other))
        val untyped = flac(listOf("TITLE=x"), pictures = listOf(0 to other))
        assertTrue(FlacReader.read(ByteArrayInputStream(untyped), untyped.size.toLong()).hasCover)
        val onlyIcon = flac(listOf("TITLE=x"), pictures = listOf(1 to icon))
        assertTrue(!FlacReader.read(ByteArrayInputStream(onlyIcon), onlyIcon.size.toLong()).hasCover)
    }

    @Test fun readsPastAnId3Prefix() {
        val plain = flac(listOf("TITLE=Tagged twice"))
        // ID3v2.4 header with syncsafe size 200 (1 shl 7 or 72), then that many bytes.
        val bytes = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 0, 0, 1, 72) + ByteArray(200) + plain
        val m = FlacReader.read(ByteArrayInputStream(bytes), bytes.size.toLong())
        assertEquals("Tagged twice", m.get("TITLE")); assertEquals(3.0, m.duration, 1e-9)
    }

    @Test fun rejectsTruncatedAndForeignFiles() {
        val bytes = flac(listOf("TITLE=x"))
        assertThrows { FlacReader.read(ByteArrayInputStream(bytes.copyOf(30)), 30) }
        assertThrows { FlacReader.read(ByteArrayInputStream("RIFF0000".toByteArray()), 8) }
    }

    @Test fun fallsBackToFileAndFolderNames() {
        val bytes = flac(emptyList())
        val m = FlacReader.read(ByteArrayInputStream(bytes), bytes.size.toLong())
        val t = FlacReader.track(m, "u", "r", "A/Album Name/07 - Song.flac", 1, 1)
        assertEquals("07 - Song", t.title); assertEquals("Album Name", t.album); assertEquals(1, t.disc)
    }

    private fun track(path: String, album: String = "A", disc: Int = 1, number: Int = 1, release: String = "") =
        Track("content://$path", "root", path, path.substringAfterLast('/'), "Artist", "Artist", album, releaseId = release, disc = disc, number = number)

    @Test fun discFoldersBelongToTheAlbumAndEditionsStaySeparate() {
        assertEquals("Artist/Album", track("Artist/Album/CD2/01.flac").albumDir)
        assertEquals("Artist/Album", track("Artist/Album/Disc 1/01.flac").albumDir)
        assertEquals("Artist/Album/Bonus", track("Artist/Album/Bonus/01.flac").albumDir)
        val a = track("X/A/01.flac", release = "one")
        val b = track("X/A/02.flac", release = "two")
        assertEquals(2, albums(LibraryIndex(listOf(a, b), emptyMap())) { null }.size)
    }

    @Test fun albumOrderFollowsDiscsThenPlaylist() {
        val a = track("X/A/01.flac", disc = 2, number = 1)
        val b = track("X/A/02.flac", disc = 1, number = 10)
        assertEquals(listOf(b, a), albums(LibraryIndex(listOf(a, b), emptyMap())) { null }[0].tracks)
        val dirs = mapOf(LibraryIndex.dirKey("root", "X/A") to listOf(Entry("playlist.m3u8", "pl", false)))
        val ordered = albums(LibraryIndex(listOf(a, b), dirs)) { "#EXTM3U\n01.flac\n02.flac\n" }[0].tracks
        assertEquals(listOf(a, b), ordered)
    }

    @Test fun playlistsResolveRelativeAndIgnoreRemote() {
        val order = playlistOrder("﻿#EXTM3U\nCD1/01.flac\n../Other/02.flac\nhttp://x/y.flac\nfile:///home/me/Music/X/A/03%20Song.flac\nnotes.txt\n", "X/A")
        assertEquals(listOf("X/A/CD1/01.flac", "X/Other/02.flac", "/home/me/Music/X/A/03 Song.flac"), order)
        assertEquals("a+b é", percentDecode("a+b%20%C3%A9"))
    }

    @Test fun lyricsPresentationAndPriority() {
        assertEquals("Line one\n\nLine two", LyricsText.plain("[ar:X]\n[00:01.00]Line one\n\n[00:02.50]<b>Line</b> two"))
        assertEquals("Hello\nworld", LyricsText.plain("1\n00:00:01,000 --> 00:00:02,000\nHello\nworld\n"))
        assertTrue(LyricsText.instrumental(" [Instrumental] "))
        assertEquals("", LyricsText.lrc("[00:01.00]Instrumental"))
        val t = track("A/B/01 - Song.flac")
        val folder = listOf(Entry("01 - Song.lrc", "lrc", false), Entry("01 - Song.txt", "txt", false), Entry("02 - Other.txt", "o", false))
        val lyricsDir = listOf(Entry("01 - Song.txt", "deep", false))
        assertEquals(listOf("txt", "deep", "lrc"), LyricsText.candidates(t, folder, listOf(lyricsDir)).map { it.uri })
    }

    @Test fun lyricEncodings() {
        assertEquals("señal ü", LyricsText.decode("señal ü".toByteArray(Charsets.UTF_8)))
        assertEquals("señal ü", LyricsText.decode(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "señal ü".toByteArray(Charsets.UTF_16LE)))
        assertEquals("“señal ü”", LyricsText.decode("“señal ü”".toByteArray(charset("windows-1252"))))
        assertEquals(null, LyricsText.decode(byteArrayOf(65, 0, 66)))
    }

    @Test fun filtersNeverInventDates() {
        val a = track("X/A/01.flac").copy(date = "2002", originalDate = "1978-05", genres = listOf("Jazz"))
        val b = a.copy(uri = "two", bits = 24)
        val f = Filter(year = "1978", originalYear = true, quality = 1, genre = "jazz")
        assertFalse(f.accepts(a, emptySet())); assertTrue(f.accepts(b, emptySet()))
        assertEquals("", year("19")); assertEquals("", year("unknown")); assertEquals("1978", year("1978-05"))
    }

    @Test fun replayGainRespectsPeakAndNeverAmplifies() {
        assertEquals(1.0, PlaybackService.gain(null, null), 1e-9)
        assertEquals(0.5012, PlaybackService.gain(-6.0, null), 1e-3)
        assertEquals(1.0 / 1.25, PlaybackService.gain(0.0, 1.25), 1e-9)
        assertEquals(1.0, PlaybackService.gain(6.0, 0.5), 1e-9)
    }

    @Test fun textHelpers() {
        assertEquals("5:42", time(342.4)); assertEquals("59:59", time(3599.0)); assertEquals("1:37:26", time(5846.0))
        assertEquals("1 album", count(1, "album", "albums")); assertEquals("0 tracks", count(0, "track", "tracks"))
        assertTrue(excluded(".git")); assertTrue(excluded("_backups")); assertFalse(excluded("Pink Floyd"))
        assertTrue(naturalCompare("booklet-2.png", "booklet-10.png") < 0)
        assertNotNull(Track.fromJson(track("a/b.flac").copy(gainTrack = -1.0).toJson()).gainTrack)
    }

    private fun assertThrows(block: () -> Unit) {
        try { block() } catch (_: IOException) { return }
        throw AssertionError("expected IOException")
    }
}
