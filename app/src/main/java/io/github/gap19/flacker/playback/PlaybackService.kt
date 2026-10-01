package io.github.gap19.flacker.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import io.github.gap19.flacker.MainActivity
import io.github.gap19.flacker.data.Durable
import io.github.gap19.flacker.data.SettingsStore
import io.github.gap19.flacker.library.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.pow

/** Audio lives here, so playback continues with the screen off or the app closed. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var handler: Handler
    private lateinit var store: QueueStore
    /** ReplayGain mode in memory; the app sends the new value when it changes. */
    private var gainMode = "off"
    /** Consecutive unplayable items skipped, so a queue of missing files cannot loop forever. */
    private var failures = 0
    /** While a saved queue is applied, its stored shuffle order must not be replaced. */
    private var restoring = false
    private val saveSoon = Runnable { session?.player?.let(store::save) }

    override fun onCreate() {
        super.onCreate()
        running = true
        handler = Handler(mainLooper)
        store = QueueStore(this)
        gainMode = SettingsStore.load(this).replayGain
        // The FLACker disc-and-sleeve mark in the status bar instead of a generic note.
        setMediaNotificationProvider(
            androidx.media3.session.DefaultMediaNotificationProvider.Builder(this).build()
                .apply { setSmallIcon(io.github.gap19.flacker.R.drawable.ic_notification) },
        )
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.setShuffleOrder(QueueShuffleOrder(IntArray(0)))
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e("FLACker", "Playback error", error)
                // The app shows which track failed; the error itself is cleared by the skip below.
                session?.broadcastCustomCommand(
                    SessionCommand(PLAY_ERROR, Bundle.EMPTY),
                    Bundle().apply { putString("title", player.currentMediaItem?.mediaMetadata?.title?.toString()) },
                )
                // A missing, revoked or undecodable file: move on instead of silencing the queue.
                if (failures < player.mediaItemCount && player.hasNextMediaItem()) {
                    failures++
                    val resume = player.playWhenReady
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.playWhenReady = resume
                }
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                applyGain(player)
                // A new queue in shuffle starts from the chosen track and covers all the others.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && player.shuffleModeEnabled && !restoring) {
                    reshuffle(player)
                }
                save()
            }

            override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                if (enabled && !restoring) reshuffle(player)
                save()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) failures = 0 else save(now = true)
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) save()
            }
        })
        store.restore(player, start = { restoring = true }) {
            applyGain(player)
            handler.post { restoring = false }
        }
        // Position survives a killed process: save every 15 s while playing.
        handler.post(object : Runnable {
            override fun run() {
                if (player.isPlaying) store.save(player)
                handler.postDelayed(this, 15_000)
            }
        })
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).putExtra(MainActivity.OPEN_NOW, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle,
                ) = if (customCommand.customAction == REFRESH_GAIN) {
                    gainMode = args.getString(MODE) ?: SettingsStore.load(this@PlaybackService).replayGain
                    applyGain(player)
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                } else super.onCustomCommand(session, controller, customCommand, args)

                // Controllers never send URIs; the media id is the document URI.
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ) = Futures.immediateFuture(
                    mediaItems.map { it.buildUpon().setUri(it.mediaId).build() }.toMutableList(),
                )

                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    val result = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    if (controller.packageName == packageName) {
                        result.setAvailableSessionCommands(
                            MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                                .add(SessionCommand(REFRESH_GAIN, Bundle.EMPTY)).build(),
                        )
                    } else {
                        // Other apps (system media controls, Bluetooth) may control playback but not
                        // load arbitrary files into this app's queue.
                        result.setAvailablePlayerCommands(
                            MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                                .removeAll(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS).build(),
                        )
                    }
                    return result.build()
                }
            })
            .build()
    }

    /** Coalesce bursts (a new queue fires a transition and a timeline change) into one write. */
    private fun save(now: Boolean = false) {
        handler.removeCallbacks(saveSoon)
        if (now) saveSoon.run() else handler.postDelayed(saveSoon, 500)
    }

    private fun reshuffle(player: ExoPlayer) {
        if (player.mediaItemCount == 0) return
        player.setShuffleOrder(QueueShuffleOrder.startingAt(player.currentMediaItemIndex, player.mediaItemCount))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            player?.let { store.save(it, sync = true) }
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        session?.run {
            store.save(player, sync = true)
            player.release()
            release()
        }
        session = null
        store.close()
        running = false
        super.onDestroy()
    }

    /** ReplayGain from the tags, limited by the stored peak. Android cannot amplify above 1.0. */
    private fun applyGain(player: Player) {
        val extras = player.currentMediaItem?.mediaMetadata?.extras
        player.volume = gainFor(extras, gainMode).toFloat()
    }

    companion object {
        const val REFRESH_GAIN = "io.github.gap19.flacker.REFRESH_GAIN"
        const val MODE = "mode"
        const val PLAY_ERROR = "io.github.gap19.flacker.PLAY_ERROR"

        /** True while the service exists in this process: music is playing or was paused from the notification. */
        @Volatile var running = false

        fun gainFor(extras: Bundle?, mode: String): Double {
            if (extras == null) return 1.0
            fun value(key: String) = if (extras.containsKey(key)) extras.getDouble(key) else null
            val (db, peak) = when (mode) {
                "track" -> value("gainTrack") to value("peakTrack")
                "album" -> (value("gainAlbum") ?: value("gainTrack")) to (value("peakAlbum") ?: value("peakTrack"))
                else -> null to null
            }
            return gain(db, peak)
        }

        fun gain(db: Double?, peak: Double?): Double {
            if (db == null) return 1.0
            var g = 10.0.pow(db.coerceIn(-60.0, 30.0) / 20.0)
            if (peak != null && peak > 0) g = minOf(g, 1.0 / peak)
            return g.coerceIn(0.0, 1.0)
        }

        fun item(t: Track, artwork: File?): MediaItem = item(t, t.toJson().toString(), artwork?.path)

        private fun item(t: Track, json: String, artwork: String?): MediaItem {
            val extras = Bundle().apply {
                putString("track", json)
                artwork?.let { putString("artwork", it) }
                t.gainTrack?.let { putDouble("gainTrack", it) }
                t.gainAlbum?.let { putDouble("gainAlbum", it) }
                t.peakTrack?.let { putDouble("peakTrack", it) }
                t.peakAlbum?.let { putDouble("peakAlbum", it) }
            }
            val meta = MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artist)
                .setAlbumTitle(t.album)
                .setAlbumArtist(t.albumArtist)
                .setTrackNumber(t.number)
                .setDiscNumber(t.disc)
                .setTotalDiscCount(t.discTotal)
                .setDurationMs((t.duration * 1000).toLong())
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setExtras(extras)
            if (artwork != null) meta.setArtworkUri(Uri.fromFile(File(artwork)))
            return MediaItem.Builder().setMediaId(t.uri).setUri(t.uri).setMediaMetadata(meta.build()).build()
        }

        /** Rebuild a queue item from its stored track JSON. */
        internal fun item(json: String, artwork: String?): MediaItem = item(Track.fromJson(JSONObject(json)), json, artwork)

        fun track(item: MediaItem?): Track? = item?.mediaMetadata?.extras?.getString("track")?.let {
            runCatching { Track.fromJson(JSONObject(it)) }.getOrNull()
        }

        /** A queue entry without stored track data still gets a row, named from its metadata. */
        fun fallback(item: MediaItem): Track = Track(
            uri = item.mediaId, root = "", path = item.mediaId.substringAfterLast("%2F").substringAfterLast('/'),
            title = item.mediaMetadata.title?.toString() ?: Uri.decode(item.mediaId.substringAfterLast("%2F")),
            artist = item.mediaMetadata.artist?.toString().orEmpty(),
            albumArtist = item.mediaMetadata.albumArtist?.toString().orEmpty(),
            album = item.mediaMetadata.albumTitle?.toString().orEmpty(),
        )
    }
}

/**
 * Queue, index, position, modes and shuffle order survive restarts; restoring never starts
 * sound. Snapshots are taken on the main thread (cheap: stored strings) and written on a
 * single background thread.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class QueueStore(private val context: Context) {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(context.mainLooper)
    private val file get() = File(context.filesDir, "queue.json")

    private class Snapshot(
        val tracks: List<String>, val artwork: List<String?>, val index: Int, val position: Long,
        val shuffle: Boolean, val repeat: Int, val order: IntArray?,
    )

    private fun snapshot(player: Player): Snapshot {
        val n = player.mediaItemCount
        val tracks = ArrayList<String>(n)
        val artwork = ArrayList<String?>(n)
        for (i in 0 until n) {
            val extras = player.getMediaItemAt(i).mediaMetadata.extras ?: continue
            tracks += extras.getString("track") ?: continue
            artwork += extras.getString("artwork")
        }
        val order = (player as? ExoPlayer)?.shuffleOrder?.let { it as? QueueShuffleOrder }?.indices()
        return Snapshot(
            tracks, artwork, player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0),
            player.shuffleModeEnabled, player.repeatMode, order?.takeIf { it.size == tracks.size },
        )
    }

    private fun write(s: Snapshot) {
        val items = JSONArray()
        s.tracks.forEachIndexed { i, t -> items.put(JSONObject().put("track", t).put("artwork", s.artwork[i])) }
        val o = JSONObject().put("version", 2).put("items", items).put("index", s.index).put("position", s.position)
            .put("shuffle", s.shuffle).put("repeat", s.repeat)
        s.order?.let { o.put("order", JSONArray(it.toList())) }
        runCatching { Durable.write(file, o.toString()) }
            .onFailure { android.util.Log.e("FLACker", "Queue not saved", it) }
    }

    fun save(player: Player, sync: Boolean = false) {
        val s = runCatching { snapshot(player) }.getOrNull() ?: return
        if (sync) write(s) else io.execute { write(s) }
    }

    fun restore(player: ExoPlayer, start: () -> Unit, done: () -> Unit) {
        io.execute {
            val restored = runCatching {
                val o = JSONObject(Durable.read(file) ?: return@execute)
                val a = o.getJSONArray("items")
                val items = List(a.length()) {
                    val e = a.getJSONObject(it)
                    // Version 1 stored the track as an object, version 2 as its JSON text.
                    val json = e.optJSONObject("track")?.toString() ?: e.getString("track")
                    PlaybackService.item(json, e.optString("artwork").takeIf { p -> p.isNotEmpty() && p != "null" })
                }
                val order = o.optJSONArray("order")?.let { arr -> IntArray(arr.length()) { arr.getInt(it) } }
                Triple(items, o, order)
            }.getOrNull() ?: return@execute
            main.post {
                val (items, o, order) = restored
                // The app may already have started something new while the file was read.
                if (items.isEmpty() || player.mediaItemCount > 0) return@post
                start()
                player.setMediaItems(items, o.optInt("index").coerceIn(0, items.size - 1), o.optLong("position"))
                player.shuffleModeEnabled = o.optBoolean("shuffle")
                order?.let { QueueShuffleOrder.restore(it, items.size) }?.let(player::setShuffleOrder)
                player.repeatMode = o.optInt("repeat")
                player.playWhenReady = false
                player.prepare()
                done()
            }
        }
    }

    fun close() = io.shutdown()
}
