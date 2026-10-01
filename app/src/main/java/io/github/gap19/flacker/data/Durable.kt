package io.github.gap19.flacker.data

import androidx.core.util.AtomicFile
import java.io.File

/**
 * Small state files replaced durably: AtomicFile syncs the new bytes before the rename and
 * keeps a backup while writing, so a crash or power loss leaves the old or the new file.
 */
object Durable {
    fun write(file: File, text: String) = writeBytes(file, text.toByteArray(Charsets.UTF_8))

    fun writeBytes(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (e: Throwable) {
            atomic.failWrite(out)
            throw e
        }
    }

    /** Contents, or null when the file does not exist or cannot be read. */
    fun readBytes(file: File): ByteArray? = runCatching { AtomicFile(file).readFully() }.getOrNull()

    /** Contents, or null when the file does not exist or cannot be read. */
    fun read(file: File): String? =
        runCatching { String(AtomicFile(file).readFully(), Charsets.UTF_8) }.getOrNull()

    /** Keep an unreadable file for inspection instead of silently losing it. */
    fun quarantine(file: File) {
        runCatching {
            val copy = File(file.parentFile, file.name + ".corrupt")
            if (file.isFile && !copy.exists()) file.copyTo(copy)
        }
    }
}
