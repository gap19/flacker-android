package io.github.gap19.flacker.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/**
 * Shuffle order for the queue. Unlike ExoPlayer's default, items inserted into a non-empty
 * queue follow the item before them, so "Play next" really plays next in shuffle, and
 * [startingAt] puts the chosen track first so no part of the queue is skipped.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class QueueShuffleOrder(private val order: IntArray, private val random: Random = Random.Default) : ShuffleOrder {
    private val position = IntArray(order.size).also { p -> order.forEachIndexed { at, i -> p[i] = at } }

    /** Indices in shuffled order. */
    fun indices(): IntArray = order.copyOf()

    override fun getLength() = order.size
    override fun getNextIndex(index: Int) = position[index].let { if (it + 1 < order.size) order[it + 1] else C.INDEX_UNSET }
    override fun getPreviousIndex(index: Int) = position[index].let { if (it > 0) order[it - 1] else C.INDEX_UNSET }
    override fun getFirstIndex() = if (order.isEmpty()) C.INDEX_UNSET else order[0]
    override fun getLastIndex() = if (order.isEmpty()) C.INDEX_UNSET else order[order.size - 1]

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        val added = IntArray(insertionCount) { insertionIndex + it }
        if (order.isEmpty()) return QueueShuffleOrder(added.also { it.shuffle(random) }, random)
        val shifted = order.map { if (it >= insertionIndex) it + insertionCount else it }.toMutableList()
        val at = if (insertionIndex > 0) position[insertionIndex - 1] + 1 else 0
        shifted.addAll(at, added.toList())
        return QueueShuffleOrder(shifted.toIntArray(), random)
    }

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val n = indexToExclusive - indexFrom
        val kept = order.filter { it < indexFrom || it >= indexToExclusive }.map { if (it >= indexToExclusive) it - n else it }
        return QueueShuffleOrder(kept.toIntArray(), random)
    }

    override fun cloneAndClear(): ShuffleOrder = QueueShuffleOrder(IntArray(0), random)

    companion object {
        /** A fresh random order of [length] items that begins with [first]. */
        fun startingAt(first: Int, length: Int, random: Random = Random.Default): QueueShuffleOrder {
            val rest = (0 until length).filter { it != first }.shuffled(random)
            val order = if (first in 0 until length) intArrayOf(first) + rest.toIntArray() else rest.toIntArray()
            return QueueShuffleOrder(order, random)
        }

        /** A saved order, if it is still a permutation of the queue. */
        fun restore(order: IntArray, length: Int): QueueShuffleOrder? =
            if (order.size == length && order.sorted() == (0 until length).toList()) QueueShuffleOrder(order) else null
    }
}
