package io.github.gap19.flacker.library

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * One FLAC file. [path] is relative to its library root ("Artist/1994 - Album/01 - Song.flac")
 * and [uri] is the document the app may read; the library itself is never written.
 */
data class Track(
    val uri: String,
    val root: String,
    val path: String,
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val date: String = "",
    val originalDate: String = "",
    val releaseId: String = "",
    val genres: List<String> = emptyList(),
    val disc: Int = 1,
    val number: Int = 0,
    val discTotal: Int = 1,
    val duration: Double = 0.0,
    val sampleRate: Int = 0,
    val bits: Int = 0,
    val channels: Int = 0,
    val size: Long = 0,
    val modified: Long = 0,
    val gainTrack: Double? = null,
    val gainAlbum: Double? = null,
    val peakTrack: Double? = null,
    val peakAlbum: Double? = null,
    val hasCover: Boolean = false,
    val hasLyrics: Boolean = false,
) {
    val directory: String get() = path.substringBeforeLast('/', "")

    /** "Artist · Album" without a dangling separator when either is missing. */
    val byline: String get() = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" · ")
    val fileName: String get() = path.substringAfterLast('/')
    val stem: String get() = fileName.substringBeforeLast('.')

    /** Disc subfolders (CD1, Disc 2) belong to the album folder above them. */
    val albumDir: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val name = directory.substringAfterLast('/').lowercase(Locale.ROOT)
        // Every prefix is tried, so "cd 1" and "disc1" both count.
        val disc = listOf("cd", "disc").any { prefix ->
            name.startsWith(prefix) && name.removePrefix(prefix).trim().toIntOrNull() != null
        }
        if (disc) directory.substringBeforeLast('/', "") else directory
    }

    /** Folder, album title and edition identify an album; equal titles never merge. Computed once. */
    val albumKey: String by lazy(LazyThreadSafetyMode.PUBLICATION) { "$root\u001f$albumDir\u001f$album\u001f$releaseId" }

    val quality: String get() = "FLAC  ·  $bits bit / ${khz(sampleRate)} kHz"
    val highRes: Boolean get() = bits > 16 || sampleRate > 48000

    fun toJson(): JSONObject = JSONObject().apply {
        put("uri", uri); put("root", root); put("path", path); put("title", title)
        put("artist", artist); put("albumArtist", albumArtist); put("album", album)
        put("date", date); put("originalDate", originalDate); put("releaseId", releaseId)
        put("genres", JSONArray(genres)); put("disc", disc); put("number", number)
        put("discTotal", discTotal); put("duration", duration); put("sampleRate", sampleRate)
        put("bits", bits); put("channels", channels); put("size", size); put("modified", modified)
        gainTrack?.let { put("gainTrack", it) }; gainAlbum?.let { put("gainAlbum", it) }
        peakTrack?.let { put("peakTrack", it) }; peakAlbum?.let { put("peakAlbum", it) }
        put("hasCover", hasCover); put("hasLyrics", hasLyrics)
    }

    companion object {
        fun fromJson(o: JSONObject) = Track(
            uri = o.getString("uri"), root = o.getString("root"), path = o.getString("path"),
            title = o.optString("title"), artist = o.optString("artist"),
            albumArtist = o.optString("albumArtist"), album = o.optString("album"),
            date = o.optString("date"), originalDate = o.optString("originalDate"),
            releaseId = o.optString("releaseId"),
            genres = o.optJSONArray("genres")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
            disc = o.optInt("disc", 1), number = o.optInt("number"), discTotal = o.optInt("discTotal", 1),
            duration = o.optDouble("duration", 0.0), sampleRate = o.optInt("sampleRate"),
            bits = o.optInt("bits"), channels = o.optInt("channels"), size = o.optLong("size"),
            modified = o.optLong("modified"),
            gainTrack = o.optDoubleOrNull("gainTrack"), gainAlbum = o.optDoubleOrNull("gainAlbum"),
            peakTrack = o.optDoubleOrNull("peakTrack"), peakAlbum = o.optDoubleOrNull("peakAlbum"),
            hasCover = o.optBoolean("hasCover"), hasLyrics = o.optBoolean("hasLyrics"),
        )
    }
}

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key)) optDouble(key).takeIf { it.isFinite() } else null

data class Album(
    val key: String,
    val title: String,
    val artist: String,
    val year: String,
    val tracks: List<Track>,
) {
    val duration: Double get() = tracks.sumOf { it.duration }
    val cover: Track get() = tracks.first()
}

fun khz(rate: Int): String {
    val v = rate / 1000.0
    return if (v == Math.floor(v)) v.toInt().toString() else v.toString()
}

/** Durations as m:ss, or h:mm:ss from one hour. */
fun time(seconds: Double): String {
    val s = seconds.coerceAtLeast(0.0).toLong()
    return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

/** Count with the correct singular or plural noun. */
fun count(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

/** First four digits of a date, or nothing; dates are never invented. */
fun year(date: String): String = date.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }.orEmpty()
