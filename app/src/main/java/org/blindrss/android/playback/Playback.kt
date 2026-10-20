package org.blindrss.android.playback
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.blindrss.android.data.ArticleEntity
import org.blindrss.android.data.DownloadEntity
import org.blindrss.android.data.PlaybackStateEntity
import org.blindrss.android.data.Repository
import java.io.File
import java.io.IOException

// Background audio: the one process-wide ExoPlayer is wrapped in a MediaSession here. Media3 keeps this service
// in the foreground while playing and provides the media notification, lock screen and headset button controls.
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    override fun onCreate() {
        super.onCreate()
        val open = PendingIntent.getActivity(this, 0, Intent(this, org.blindrss.android.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, PlayerController.get(this).exo).setSessionActivity(open).build()
    }
    override fun onGetSession(info: MediaSession.ControllerInfo): MediaSession? = session
    // Swiping the app away keeps audio playing; the service only goes when nothing is playing.
    override fun onTaskRemoved(rootIntent: Intent?) { val p = session?.player; if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf() }
    override fun onDestroy() { session?.release(); session = null; super.onDestroy() }
}

data class QueueEntry(val articleId: String, val title: String)

// One per process (the service and every screen share it). The ExoPlayer playlist is the Up next queue; it is
// saved on every change and restored, paused, at the next launch. Positions are saved while playing.
class PlayerController private constructor(private val ctx: Context, private val repo: Repository) {
    companion object {
        @Volatile private var inst: PlayerController? = null
        fun get(ctx: Context): PlayerController = inst ?: synchronized(this) { inst ?: PlayerController(ctx.applicationContext, Repository(ctx.applicationContext)).also { inst = it } }
        const val BACK_MS = 10_000L; const val FORWARD_MS = 30_000L
    }
    // "ytdl:<page url>" items (YouTube and other sites without a direct audio link) are resolved by yt-dlp on
    // ExoPlayer's loading thread, only when the item actually loads.
    private val sources = DefaultMediaSourceFactory(ResolvingDataSource.Factory(DefaultDataSource.Factory(ctx)) { spec ->
        if (spec.uri.scheme == Ytdl.SCHEME) spec.withUri(Uri.parse(Ytdl.streamUrlBlocking(ctx, spec.uri.schemeSpecificPart))) else spec })
    val exo: ExoPlayer = ExoPlayer.Builder(ctx).setMediaSourceFactory(sources)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true) // audio focus
        .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
        .setWakeMode(C.WAKE_MODE_NETWORK) // keep streaming with the screen off
        .setSeekBackIncrementMs(BACK_MS).setSeekForwardIncrementMs(FORWARD_MS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _title = MutableStateFlow(""); val title: StateFlow<String> = _title
    private val _playing = MutableStateFlow(false); val playing: StateFlow<Boolean> = _playing
    private val _speed = MutableStateFlow(1f); val speed: StateFlow<Float> = _speed
    private val _sleepAt = MutableStateFlow(0L); val sleepAt: StateFlow<Long> = _sleepAt
    private val _stopAtEnd = MutableStateFlow(false); val stopAtEnd: StateFlow<Boolean> = _stopAtEnd
    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList()); val queue: StateFlow<List<QueueEntry>> = _queue
    private val _index = MutableStateFlow(-1); val index: StateFlow<Int> = _index
    private val _error = MutableStateFlow(""); val error: StateFlow<String> = _error
    private var sleepJob: Job? = null
    private var chapters: List<org.blindrss.android.data.ChapterEntity> = emptyList()
    private var connection: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var eq: android.media.audiofx.Equalizer? = null
    private var sponsorSegments: List<Pair<Long, Long>> = emptyList()
    // True while read aloud owns the player: its parts are not episodes (no resume, no played marks, queue not saved).
    private var spoken = false
    // Set by read aloud to move on when a spoken article ends; any episode playback clears it.
    var onEnded: (() -> Unit)? = null
    private var currentEpisode: String? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var ticks = 0
    private val tick = object : Runnable { override fun run() {
        if (exo.isPlaying) { val pos = exo.currentPosition
            sponsorSegments.firstOrNull { pos >= it.first && pos < it.second - 500 }?.let { exo.seekTo(it.second) }
            if (++ticks % 20 == 0) scope.launch { saveState() } } // every 10 s
        mainHandler.postDelayed(this, 500) } }

    init {
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { _playing.value = p; if (p) _error.value = "" else scope.launch { saveState() } }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    if (!spoken) currentEpisode?.let { markPlayed(it) }
                    if (_stopAtEnd.value) { exo.pause(); _stopAtEnd.value = false }
                }
                started(item); syncQueue()
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { syncQueue() }
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED) return
                if (!spoken) { currentEpisode?.let { markPlayed(it) }; _stopAtEnd.value = false }
                onEnded?.invoke()
            }
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                _error.value = "Playback failed: ${e.cause?.message ?: e.errorCodeName}"
            }
        })
        scope.launch { try { exo.skipSilenceEnabled = repo.settings.skipSilence.first(); setSpeed(repo.settings.playbackSpeed.first())
            val bands = repo.settings.eqBands.first(); val enabled = repo.settings.eqEnabled.first()
            if (enabled || bands.isNotEmpty()) applyEqualizer(enabled, bands) } catch (_: Exception) {}
            restoreQueue() }
        mainHandler.post(tick)
    }
    // Binding a controller starts PlaybackService, which then owns the foreground notification while playing.
    private fun connect() { if (connection == null) connection = MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))).buildAsync() }

    private fun started(item: MediaItem?) {
        if (spoken) return
        currentEpisode = item?.mediaId?.takeIf { it.isNotEmpty() }
        _title.value = item?.mediaMetadata?.title?.toString().orEmpty()
        chapters = emptyList(); sponsorSegments = emptyList()
        val id = currentEpisode ?: return
        scope.launch {
            val a = repo.articles.byId(id) ?: return@launch
            chapters = repo.misc.chapters(id).ifEmpty { repo.ensureChapters(a) }
            loadSponsorBlock(a.url)
            // Resume where this episode was left, unless it was finished.
            val saved = repo.misc.playback(id)
            if (saved != null && !saved.completed && saved.positionMs > 5_000 && exo.currentMediaItem?.mediaId == id && exo.currentPosition < 2_000) exo.seekTo(saved.positionMs)
        }
    }
    private fun syncQueue() {
        if (spoken) return
        _queue.value = (0 until exo.mediaItemCount).map { exo.getMediaItemAt(it).let { m -> QueueEntry(m.mediaId, m.mediaMetadata.title?.toString().orEmpty()) } }
        _index.value = exo.currentMediaItemIndex
        val ids = _queue.value.map { it.articleId }; val i = _index.value
        scope.launch { repo.settings.saveQueue(ids, i) }
    }
    private fun markPlayed(id: String) { scope.launch {
        repo.misc.upsertPlayback(PlaybackStateEntity(id, 0, 0, true))
        repo.articles.byId(id)?.let { if (!it.isRead) { repo.articles.setRead(id, true); repo.pushRead(it, true) } } } }

    private suspend fun itemFor(a: ArticleEntity): MediaItem {
        val local = repo.misc.download(a.id)?.filePath?.let { File(it) }?.takeIf { it.exists() }
        val feed = repo.feeds.byId(a.feedId)?.title
        val uri = when { local != null -> Uri.fromFile(local); a.mediaUrl != null -> Uri.parse(a.mediaUrl); else -> Ytdl.uriFor(a.url) }
        return MediaItem.Builder().setMediaId(a.id).setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(a.title).setArtist(feed).setAlbumTitle(feed).build()).build()
    }
    private fun leaveSpoken() { if (spoken) { spoken = false; onEnded = null; exo.clearMediaItems() } }
    // Play now: replaces the current episode and keeps the rest of Up next.
    suspend fun play(a: ArticleEntity) {
        connect(); saveState(); leaveSpoken(); _error.value = ""
        val existing = (0 until exo.mediaItemCount).firstOrNull { exo.getMediaItemAt(it).mediaId == a.id }
        if (existing != null) exo.seekTo(existing, C.TIME_UNSET)
        else if (exo.mediaItemCount == 0) exo.setMediaItem(itemFor(a))
        else { val cur = exo.currentMediaItemIndex; exo.addMediaItem(cur + 1, itemFor(a)); exo.seekTo(cur + 1, C.TIME_UNSET); exo.removeMediaItem(cur) }
        exo.prepare(); exo.play()
    }
    suspend fun playNext(a: ArticleEntity) { leaveSpoken(); removeById(a.id)
        if (exo.mediaItemCount == 0) { exo.setMediaItem(itemFor(a)); exo.prepare() } else exo.addMediaItem(exo.currentMediaItemIndex + 1, itemFor(a)) }
    suspend fun enqueue(a: ArticleEntity) { leaveSpoken(); if ((0 until exo.mediaItemCount).any { exo.getMediaItemAt(it).mediaId == a.id }) return
        exo.addMediaItem(itemFor(a)); if (exo.mediaItemCount == 1) exo.prepare() }
    private fun removeById(id: String) { (0 until exo.mediaItemCount).firstOrNull { exo.getMediaItemAt(it).mediaId == id && it != exo.currentMediaItemIndex }?.let { exo.removeMediaItem(it) } }
    fun removeAt(i: Int) { if (i in 0 until exo.mediaItemCount) exo.removeMediaItem(i) }
    fun playAt(i: Int) { if (i in 0 until exo.mediaItemCount) { connect(); exo.seekTo(i, C.TIME_UNSET); exo.prepare(); exo.play() } }
    fun moveUp(i: Int) { if (i in 1 until exo.mediaItemCount) exo.moveMediaItem(i, i - 1) }
    fun clearQueue() { scope.launch { saveState() }; exo.stop(); exo.clearMediaItems() }
    private suspend fun restoreQueue() {
        val (ids, i) = repo.settings.savedQueue(); if (ids.isEmpty() || exo.mediaItemCount > 0) return
        val items = ids.mapNotNull { id -> repo.articles.byId(id)?.let { itemFor(it) } }; if (items.isEmpty()) return
        exo.setMediaItems(items, i.coerceIn(0, items.size - 1), C.TIME_UNSET); exo.playWhenReady = false; exo.prepare()
    }

    // Read aloud: first audio part now, later parts appended as they are synthesized.
    fun playSpoken(articleId: String, title: String, first: File) {
        connect(); scope.launch { saveState() }; spoken = true; currentEpisode = null; _title.value = title; chapters = emptyList(); sponsorSegments = emptyList()
        exo.setMediaItem(MediaItem.Builder().setUri(Uri.fromFile(first)).setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()); exo.prepare(); exo.play()
    }
    // If playback already ran out while this part was being made, continue from it.
    fun appendSpoken(part: File) {
        exo.addMediaItem(MediaItem.Builder().setUri(Uri.fromFile(part)).setMediaMetadata(MediaMetadata.Builder().setTitle(_title.value).build()).build())
        if (exo.playbackState == Player.STATE_ENDED) { exo.seekTo(exo.mediaItemCount - 1, 0); exo.play() }
    }
    fun isEnded() = exo.playbackState == Player.STATE_ENDED

    fun setSkipSilence(on: Boolean) { exo.skipSilenceEnabled = on; scope.launch { repo.settings.setSkipSilence(on) } }
    fun applyEqualizer(enabled: Boolean, bands: List<Int>) {
        try { if (eq == null) eq = android.media.audiofx.Equalizer(0, exo.audioSessionId)
            eq!!.enabled = enabled
            val n = minOf(eq!!.numberOfBands.toInt(), bands.size)
            for (i in 0 until n) { val range = eq!!.getBandLevelRange(); eq!!.setBandLevel(i.toShort(), bands[i].coerceIn(range[0].toInt(), range[1].toInt()).toShort()) }
            scope.launch { repo.settings.setEq(enabled, bands) }
        } catch (_: Exception) {}
    }
    suspend fun loadSponsorBlock(articleUrl: String) {
        sponsorSegments = emptyList()
        if (!repo.settings.sponsorBlock.first()) return
        val vid = Regex("(?:v=|youtu\\.be/|/shorts/)([\\w-]{11})").find(articleUrl)?.groupValues?.get(1) ?: return
        try { val json = withContext(Dispatchers.IO) { java.net.URL("https://sponsor.ajay.app/api/skipSegments?videoID=$vid&categories=[%22sponsor%22,%22selfpromo%22,%22interaction%22]").readText() }
            sponsorSegments = Regex("\"segment\"\\s*:\\s*\\[\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*\\]").findAll(json).map { (it.groupValues[1].toDouble() * 1000).toLong() to (it.groupValues[2].toDouble() * 1000).toLong() }.toList()
        } catch (_: Exception) {}
    }
    fun toggle() { connect(); if (exo.isPlaying) exo.pause() else { if (exo.playbackState == Player.STATE_IDLE) exo.prepare(); exo.play() } }
    fun back() = exo.seekBack()
    fun forward() = exo.seekForward()
    fun nextEpisode() { if (exo.hasNextMediaItem()) exo.seekToNextMediaItem() }
    fun previousEpisode() = exo.seekToPreviousMediaItem()
    fun seekTo(ms: Long) = exo.seekTo(ms)
    fun setSpeed(s: Float) { _speed.value = s; exo.setPlaybackSpeed(s) }
    fun position() = exo.currentPosition
    fun duration() = exo.duration.coerceAtLeast(0)
    // minutes > 0: pause after that long. -1: pause when this episode ends. 0: off.
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel(); _sleepAt.value = 0; _stopAtEnd.value = minutes == -1
        if (minutes <= 0) return
        _sleepAt.value = System.currentTimeMillis() + minutes * 60000L
        sleepJob = scope.launch { delay(minutes * 60000L); exo.pause(); _sleepAt.value = 0 }
    }
    fun chapterList() = chapters
    fun currentId(): String? = currentEpisode
    fun seekChapter(i: Int) { chapters.getOrNull(i)?.let { exo.seekTo(it.startMs) } }
    fun nextChapter() { val pos = position(); seekChapter(chapters.indexOfLast { it.startMs <= pos } + 1) }
    fun prevChapter() { val pos = position(); val i = chapters.indexOfLast { it.startMs < pos - 3000 }; seekChapter(if (i >= 0) i else 0) }
    suspend fun saveState() {
        val id = currentEpisode ?: return; val dur = exo.duration
        if (dur > 0 && exo.currentPosition > dur - 30_000) return // near the end: the end handler marks it played
        repo.misc.upsertPlayback(PlaybackStateEntity(id, exo.currentPosition, dur.coerceAtLeast(0), false))
    }
}

// Episode downloads use Android's DownloadManager: they continue with the app closed, show progress in a
// notification, and survive network drops. DownloadDoneReceiver records each finished file. Articles without a
// direct audio link (YouTube and other sites) get their audio stream address from yt-dlp first.
object Downloads {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    suspend fun enqueue(ctx: Context, a: ArticleEntity) {
        val url = a.mediaUrl ?: if (Ytdl.supported()) withContext(Dispatchers.IO) { Ytdl.streamUrlBlocking(ctx, a.url) } else throw IOException("No episode audio to download")
        val ext = if (a.mediaUrl == null) "m4a" else Uri.parse(url).lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.length in 2..4 } ?: "mp3"
        val req = DownloadManager.Request(Uri.parse(url)).setTitle(a.title).setDescription("BlindRSS episode")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, "episodes", "${a.id.take(16)}.$ext")
        val id = ctx.getSystemService(DownloadManager::class.java).enqueue(req)
        prefs(ctx).edit().putString(id.toString(), a.id).apply()
    }
    suspend fun delete(repo: Repository, articleId: String) = withContext(Dispatchers.IO) {
        repo.misc.download(articleId)?.let { File(it.filePath).delete() }; repo.misc.deleteDownload(articleId)
    }
    fun finished(ctx: Context, downloadId: Long): Pair<String, String>? {
        val articleId = prefs(ctx).getString(downloadId.toString(), null) ?: return null
        prefs(ctx).edit().remove(downloadId.toString()).apply()
        ctx.getSystemService(DownloadManager::class.java).query(DownloadManager.Query().setFilterById(downloadId)).use { c ->
            if (!c.moveToFirst() || c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) != DownloadManager.STATUS_SUCCESSFUL) return null
            val path = Uri.parse(c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))).path ?: return null
            return articleId to path
        }
    }
}
class DownloadDoneReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        val (articleId, path) = Downloads.finished(ctx, id) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { Repository(ctx.applicationContext).misc.insertDownload(DownloadEntity(articleId, path)) } finally { pending.finish() } }
    }
}
