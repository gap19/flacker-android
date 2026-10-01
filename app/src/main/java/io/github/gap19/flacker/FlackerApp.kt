package io.github.gap19.flacker

import android.app.Application
import io.github.gap19.flacker.library.Album
import io.github.gap19.flacker.library.ShelfStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class FlackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Start reading the saved shelf while Android creates the first screen.
        shelf = CompletableFuture.supplyAsync { ShelfStore.load(this) }
    }

    companion object {
        /**
         * Set once the first screen of this process has decided whether to look for new files.
         * Coming back to the app recreates the screen but not the process, so it never counts
         * as a new start.
         */
        @Volatile var startHandled = false

        @Volatile private var shelf: CompletableFuture<List<Album>?>? = null

        /** Shelf and index of this process, so a recreated screen shows them at once. */
        @Volatile var lastShelf: Any? = null
        @Volatile var lastIndex: io.github.gap19.flacker.library.LibraryIndex? = null

        /** The shelf read at process start, handed out once; later screens read the current file. */
        fun takePreloadedShelf(): List<Album>? {
            val f = shelf ?: return null
            shelf = null
            return runCatching { f.get(2, TimeUnit.SECONDS) }.getOrNull()
        }
    }
}
