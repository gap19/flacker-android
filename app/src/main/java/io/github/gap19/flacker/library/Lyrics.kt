package io.github.gap19.flacker.library

import java.util.Locale

/** Static lyrics and where they came from. Timestamps are removed; nothing is synchronized. */
data class Lyrics(val text: String = "", val source: String = "")

object LyricsText {
    const val MAX_TEXT = 2 * 1024 * 1024
    private val SIDECARS = listOf("txt", "lyrics", "text", "lrc", "srt", "vtt")
    private val META = setOf("ar", "al", "ti", "au", "by", "re", "ve", "length", "offset")

    fun instrumental(text: String) = text.trim().lowercase(Locale.ROOT) in
        setOf("[instrumental]", "instrumental", "(instrumental)", "[instrumental track]")

    /** Local sidecar names in priority order: extension first, then track folder before Lyrics. */
    fun candidates(track: Track, folder: List<Entry>, lyricDirs: List<List<Entry>>): List<Entry> {
        val dirs = listOf(folder) + lyricDirs
        return dirs.flatMapIndexed { rank, entries ->
            entries.filter { !it.isDir && it.name.substringBeforeLast('.') == track.stem }
                .mapNotNull { e ->
                    val ext = e.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    SIDECARS.indexOf(ext).takeIf { it >= 0 }?.let { Triple(it, rank, e) }
                }
        }.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
    }

    fun decode(bytes: ByteArray): String? {
        if (bytes.size >= 2 && ((bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) ||
                (bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()))
        ) {
            if (bytes.size % 2 != 0) return null
            val cs = if (bytes[0] == 0xff.toByte()) Charsets.UTF_16LE else Charsets.UTF_16BE
            return String(bytes, 2, bytes.size - 2, cs)
        }
        if (bytes.any { it == 0.toByte() }) return null
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: java.nio.charset.CharacterCodingException) {
            // Legacy lyric files commonly use Windows-1252.
            String(bytes, charset("windows-1252"))
        }
    }

    /** Presentation text for embedded, TXT, LRC, SRT or VTT content. */
    fun plain(raw: String): String {
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        return when {
            text.lines().any { "-->" in it } -> subtitles(text)
            text.lines().any { it.trimStart().startsWith("[") } -> lrc(text)
            else -> text.trim()
        }
    }

    fun lrc(text: String): String {
        val out = ArrayList<String>()
        for (line in text.lines()) {
            var rest = line.trim().removePrefix("\uFEFF")
            while (rest.startsWith("[")) {
                val end = rest.indexOf(']')
                if (end < 0) break
                val token = rest.substring(1, end)
                val key = token.substringBefore(':', "")
                val isTime = token.contains(':') && key.isNotEmpty() && key.all(Char::isDigit)
                val isMeta = token.contains(':') && key.lowercase(Locale.ROOT) in META
                if (!isTime && !isMeta) break
                rest = rest.substring(end + 1).trim()
            }
            if (rest.isNotEmpty()) out += stripMarkup(rest)
            else if (line.isBlank()) out += ""
        }
        val joined = out.joinToString("\n").trim()
        return if (instrumental(joined)) "" else joined
    }

    private fun subtitles(text: String): String = text.split("\n\n").mapNotNull { block ->
        val lines = block.lines()
        val timing = lines.indexOfFirst { "-->" in it }
        if (timing < 0) return@mapNotNull null
        val head = lines.firstOrNull().orEmpty()
        if (head.startsWith("NOTE") || head.startsWith("STYLE") || head.startsWith("REGION")) return@mapNotNull null
        lines.drop(timing + 1).joinToString("\n") { stripMarkup(it) }.trimEnd('\n').takeIf { it.isNotBlank() }
    }.joinToString("\n\n")

    private fun stripMarkup(text: String): String {
        val out = StringBuilder()
        var rest = text
        while (true) {
            val start = rest.indexOf('<')
            if (start < 0) break
            out.append(rest, 0, start)
            val end = rest.indexOf('>', start)
            if (end < 0) { out.append(rest.substring(start)); rest = ""; break }
            val tag = rest.substring(start + 1, end)
            val name = tag.trimStart('/').split(' ', '.').first()
            val timestamp = tag.firstOrNull()?.isDigit() == true && ':' in tag
            if (!timestamp && name !in setOf("b", "i", "u", "c", "v", "lang", "ruby", "rt")) {
                out.append(rest, start, end + 1)
            }
            rest = rest.substring(end + 1)
        }
        out.append(rest)
        return out.toString().replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&amp;", "&")
    }
}
