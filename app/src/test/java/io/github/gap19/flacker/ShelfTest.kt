package io.github.gap19.flacker

import io.github.gap19.flacker.library.Album
import io.github.gap19.flacker.library.ShelfStore
import io.github.gap19.flacker.library.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class ShelfTest {
    @Test fun shelfRoundTripKeepsAlbumsTracksAndOrder() {
        val a = Track(uri = "content://a/2", root = "r", path = "Ü/Ñ/02.flac", title = "Señal ü", artist = "A", albumArtist = "A", album = "X",
            date = "2001", genres = listOf("Rock", "Jazz"), disc = 2, number = 2, discTotal = 2, duration = 61.5, sampleRate = 96000,
            bits = 24, channels = 2, size = 123, modified = 456, gainTrack = -6.5, peakAlbum = 0.98, hasCover = true)
        val b = a.copy(uri = "content://a/1", path = "Ü/Ñ/01.flac", number = 1, gainTrack = null, hasLyrics = true)
        // Playlist order (2 before 1) must survive as it is.
        val albums = listOf(Album("key", "X", "A", "2001", listOf(a, b)), Album("k2", "Y", "", "", emptyList()))
        val back = ShelfStore.decode(ShelfStore.encode(albums))
        assertEquals(albums, back)
        assertEquals(listOf("content://a/2", "content://a/1"), back[0].tracks.map { it.uri })
    }
}
