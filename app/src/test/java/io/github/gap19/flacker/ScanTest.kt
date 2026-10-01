package io.github.gap19.flacker

import io.github.gap19.flacker.library.Entry
import io.github.gap19.flacker.library.LibraryIndex
import io.github.gap19.flacker.library.canReuse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanTest {
    private val files = listOf(Entry("01.flac", "u1", false, 100, 5), Entry("cover.jpg", "u2", false, 10, 5))

    @Test fun unchangedAlbumFolderIsReused() {
        assertTrue(canReuse(1000, 1000, files))
    }

    @Test fun changedUnknownOrNestedFoldersAreListed() {
        assertFalse(canReuse(1001, 1000, files))                                  // a file was added, removed or renamed
        assertFalse(canReuse(0, 0, files))                                        // the provider gives no time
        assertFalse(canReuse(1000, null, files))                                  // never listed before
        assertFalse(canReuse(1000, 1000, null))
        assertFalse(canReuse(1000, 1000, files + Entry("CD2", "u3", true, 0, 7))) // subfolders may have changed
    }

    @Test fun indexKeepsFolderTimesAndReadsTheOldFormat() {
        val key = LibraryIndex.dirKey("root", "Artist")
        val index = LibraryIndex(emptyList(), mapOf(key to listOf(Entry("Album", "u", true, 0, 1234, "doc:1"))))
        val back = LibraryIndex.fromJson(JSONObject(index.toJson().toString()))
        assertEquals(Entry("Album", "u", true, 0, 1234, "doc:1"), back.dirs[key]!!.single())
        val old = JSONObject("""{"version":1,"tracks":[],"dirs":{"$key":[["Album","u",true]]}}""")
        assertEquals(Entry("Album", "u", true), LibraryIndex.fromJson(old).dirs[key]!!.single())
    }
}
