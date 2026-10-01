package io.github.gap19.flacker.data

import android.content.Context
import io.github.gap19.flacker.library.Album
import io.github.gap19.flacker.library.Track
import io.github.gap19.flacker.library.year
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Collection filter and order. Filters never rewrite metadata or reorder an album. */
data class Filter(
    val genre: String = "",
    val year: String = "",
    val originalYear: Boolean = false,
    /** 0 all, 1 high resolution, 2 up to 16 bit / 48 kHz. */
    val quality: Int = 0,
    val favorites: Boolean = false,
    /** 0 artist and edition, 1 title, 2 edition year, 3 original year. */
    val sort: Int = 0,
) {
    val active get() = genre.isNotEmpty() || year.isNotEmpty() || quality != 0 || favorites

    fun accepts(t: Track, favoriteUris: Set<String>): Boolean {
        val date = if (originalYear) t.originalDate else t.date
        return (genre.isEmpty() || t.genres.any { it.equals(genre, ignoreCase = true) }) &&
            (year.isEmpty() || year(date) == year) &&
            (quality == 0 || (quality == 1) == t.highRes) &&
            (!favorites || t.uri in favoriteUris)
    }

    fun albums(all: List<Album>, favoriteUris: Set<String>, query: String): List<Album> {
        val terms = query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        val selected = all.filter { a ->
            val text = "${a.artist} ${a.title} ${a.year}".lowercase(Locale.ROOT)
            terms.all { it in text } && a.tracks.any { accepts(it, favoriteUris) }
        }
        fun date(a: Album) = if (sort == 3) year(a.cover.originalDate) else year(a.cover.date)
        return when (sort) {
            1 -> selected.sortedWith(compareBy({ it.title.lowercase(Locale.ROOT) }, { it.artist.lowercase(Locale.ROOT) }, { date(it) }, { it.key }))
            2, 3 -> selected.sortedWith(compareBy({ if (date(it).isEmpty()) "1" else "0" + date(it) }, { it.artist.lowercase(Locale.ROOT) }, { it.title.lowercase(Locale.ROOT) }, { it.key }))
            else -> selected
        }
    }

    fun toJson() = JSONObject().put("genre", genre).put("year", year).put("originalYear", originalYear)
        .put("quality", quality).put("favorites", favorites).put("sort", sort)

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) Filter() else Filter(
            o.optString("genre"), o.optString("year"), o.optBoolean("originalYear"),
            o.optInt("quality"), o.optBoolean("favorites"), o.optInt("sort"),
        )
    }
}

data class Settings(
    val roots: List<String> = emptyList(),
    val favorites: Set<String> = emptySet(),
    /** "dark", "light" or "system". */
    val theme: String = "dark",
    /** "off", "track" or "album". */
    val replayGain: String = "off",
    val preferEmbedded: Boolean = true,
    /** Right-hand time: 0 duration, 1 track remaining, 2 album remaining in the queue. */
    val clock: Int = 0,
    val lyricsSize: Int = 19,
    val focusKeepsScreenOn: Boolean = true,
    /** Look for new files every time the app is started; off by default (refresh from the menu). */
    val refreshOnLaunch: Boolean = false,
    val filter: Filter = Filter(),
    val lastScan: Long = 0,
) {
    fun toJson() = JSONObject().apply {
        put("roots", JSONArray(roots)); put("favorites", JSONArray(favorites.toList()))
        put("theme", theme); put("replayGain", replayGain); put("preferEmbedded", preferEmbedded)
        put("clock", clock); put("lyricsSize", lyricsSize); put("focusKeepsScreenOn", focusKeepsScreenOn)
        put("refreshOnLaunch", refreshOnLaunch)
        put("filter", filter.toJson()); put("lastScan", lastScan)
    }

    companion object {
        private fun strings(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) { a.getString(it) }

        fun fromJson(o: JSONObject) = Settings(
            roots = strings(o.optJSONArray("roots")),
            favorites = strings(o.optJSONArray("favorites")).toSet(),
            theme = o.optString("theme", "dark"),
            replayGain = o.optString("replayGain", "off"),
            preferEmbedded = o.optBoolean("preferEmbedded", true),
            clock = o.optInt("clock").coerceIn(0, 2),
            lyricsSize = o.optInt("lyricsSize", 19).coerceIn(14, 30),
            focusKeepsScreenOn = o.optBoolean("focusKeepsScreenOn", true),
            refreshOnLaunch = o.optBoolean("refreshOnLaunch", false),
            filter = Filter.fromJson(o.optJSONObject("filter")),
            lastScan = o.optLong("lastScan"),
        )
    }
}

object SettingsStore {
    private fun file(c: Context) = File(c.filesDir, "state.json")

    fun load(c: Context): Settings {
        val text = Durable.read(file(c)) ?: return Settings()
        return try {
            Settings.fromJson(JSONObject(text))
        } catch (_: Exception) {
            Durable.quarantine(file(c))
            Settings()
        }
    }

    /** Durable replace; callers serialize writes so an older snapshot never lands last. */
    fun save(c: Context, s: Settings) = Durable.write(file(c), s.toJson().toString())
}
