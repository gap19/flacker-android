package io.github.gap19.flacker

import androidx.media3.common.C
import io.github.gap19.flacker.data.Durable
import io.github.gap19.flacker.library.Track
import io.github.gap19.flacker.playback.QueueShuffleOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class QueueTest {
    private fun walk(o: QueueShuffleOrder): List<Int> {
        val out = ArrayList<Int>()
        var i = o.firstIndex
        while (i != C.INDEX_UNSET) { out += i; i = o.getNextIndex(i) }
        return out
    }

    @Test fun shuffleStartsAtTheChosenTrackAndCoversAll() {
        val o = QueueShuffleOrder.startingAt(4, 10, Random(1))
        val seq = walk(o)
        assertEquals(4, seq.first())
        assertEquals((0 until 10).toList(), seq.sorted())
        assertEquals(seq.last(), o.lastIndex)
        assertEquals(C.INDEX_UNSET, o.getPreviousIndex(4))
    }

    @Test fun playNextFollowsTheCurrentItemInShuffle() {
        val o = QueueShuffleOrder(intArrayOf(2, 0, 3, 1))
        // Current is 0; "Play next" inserts at timeline index 1.
        val next = o.cloneAndInsert(1, 1) as QueueShuffleOrder
        assertEquals(listOf(3, 0, 1, 4, 2), walk(next))
        assertEquals(1, next.getNextIndex(0))
    }

    @Test fun insertingIntoAnEmptyQueueShuffles() {
        val o = QueueShuffleOrder(IntArray(0), Random(7)).cloneAndInsert(0, 6) as QueueShuffleOrder
        assertEquals((0 until 6).toList(), walk(o).sorted())
    }

    @Test fun removingKeepsTheRelativeOrder() {
        val o = QueueShuffleOrder(intArrayOf(4, 1, 3, 0, 2)).cloneAndRemove(1, 3) as QueueShuffleOrder
        // 1 and 2 are gone; 3 → 1 and 4 → 2.
        assertEquals(listOf(2, 1, 0), walk(o))
        assertEquals(0, (o.cloneAndClear() as QueueShuffleOrder).length)
    }

    @Test fun savedOrderMustStillMatchTheQueue() {
        assertArrayEquals(intArrayOf(1, 0, 2), QueueShuffleOrder.restore(intArrayOf(1, 0, 2), 3)!!.indices())
        assertNull(QueueShuffleOrder.restore(intArrayOf(1, 0), 3))
        assertNull(QueueShuffleOrder.restore(intArrayOf(1, 1, 2), 3))
    }

    @Test fun durableWritesReplaceAndReadBack() {
        val dir = Files.createTempDirectory("flacker").toFile()
        val f = File(dir, "state.json")
        assertNull(Durable.read(f))
        Durable.write(f, "{\"a\":1}")
        Durable.write(f, "{\"a\":2}")
        assertEquals("{\"a\":2}", Durable.read(f))
        // No temporary or backup file is left behind.
        assertEquals(listOf("state.json"), dir.list()!!.toList())
        f.writeText("{broken")
        Durable.quarantine(f)
        assertEquals("{broken", File(dir, "state.json.corrupt").readText())
        dir.deleteRecursively()
    }

    @Test fun bylineSkipsMissingParts() {
        val t = Track(uri = "u", root = "r", path = "a/b.flac", title = "T", artist = "", albumArtist = "", album = "Record")
        assertEquals("Record", t.byline)
        assertEquals("Ana · Record", t.copy(artist = "Ana").byline)
        assertEquals("r\u001fa\u001fRecord\u001f", t.albumKey)
    }
}
