package org.blindrss.android.playback
import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.blindrss.android.data.PlaybackStateEntity
import org.blindrss.android.data.Repository
import java.io.File
import java.net.URL

class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    override fun onCreate() { super.onCreate(); player = ExoPlayer.Builder(this).build(); session = MediaSession.Builder(this, player).build() }
    override fun onGetSession(info: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() { session?.run { player.release(); release() }; super.onDestroy() }
}

class PlayerController(private val ctx: Context, private val repo: Repository) {
    var player: ExoPlayer? = null; private set
    private val _title = MutableStateFlow(""); val title: StateFlow<String> = _title
    private val _playing = MutableStateFlow(false); val playing: StateFlow<Boolean> = _playing
    private val _speed = MutableStateFlow(1f); val speed: StateFlow<Float> = _speed
    private val _sleepAt = MutableStateFlow(0L); val sleepAt: StateFlow<Long> = _sleepAt
    private var sleepJob: Job? = null
    private var currentArticleId: String? = null
    private var chapters: List<org.blindrss.android.data.ChapterEntity> = emptyList()

    private var eq: android.media.audiofx.Equalizer? = null
    private var sponsorSegments: List<Pair<Long, Long>> = emptyList()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val sponsorTick = object : Runnable { override fun run() {
        val p = player; if (p != null && p.isPlaying) { val pos = p.currentPosition; sponsorSegments.firstOrNull { pos >= it.first && pos < it.second - 500 }?.let { p.seekTo(it.second) } }
        mainHandler.postDelayed(this, 500) } }
    fun ensure() { if (player == null) { player = ExoPlayer.Builder(ctx).build(); player!!.addListener(object : Player.Listener { override fun onIsPlayingChanged(p: Boolean) { _playing.value = p } })
        CoroutineScope(Dispatchers.Main).launch { try { player!!.skipSilenceEnabled = repo.settings.skipSilence.first(); setSpeed(repo.settings.playbackSpeed.first())
            val bands = repo.settings.eqBands.first(); val enabled = repo.settings.eqEnabled.first()
            if (enabled || bands.isNotEmpty()) applyEqualizer(enabled, bands) } catch (_: Exception) {} }
        mainHandler.removeCallbacks(sponsorTick); mainHandler.post(sponsorTick) } }
    fun setSkipSilence(on: Boolean) { player?.skipSilenceEnabled = on; CoroutineScope(Dispatchers.IO).launch { repo.settings.setSkipSilence(on) } }
    fun isSkipSilence() = player?.skipSilenceEnabled ?: true
    fun applyEqualizer(enabled: Boolean, bands: List<Int>) {
        try { val session = player?.audioSessionId ?: return
            if (eq == null) eq = android.media.audiofx.Equalizer(0, session)
            eq!!.enabled = enabled
            val n = minOf(eq!!.numberOfBands.toInt(), bands.size)
            for (i in 0 until n) { val range = eq!!.getBandLevelRange(); val level = bands[i].coerceIn(range[0].toInt(), range[1].toInt()).toShort(); eq!!.setBandLevel(i.toShort(), level) }
            CoroutineScope(Dispatchers.IO).launch { repo.settings.setEq(enabled, bands) }
        } catch (_: Exception) {}
    }
    fun eqBandCount(): Int = try { eq?.numberOfBands?.toInt() ?: 5 } catch (_: Exception) { 5 }
    suspend fun loadSponsorBlock(articleUrl: String) {
        sponsorSegments = emptyList()
        if (!repo.settings.sponsorBlock.first()) return
        val vid = Regex("(?:v=|youtu\\.be/|/shorts/)([\\w-]{11})").find(articleUrl)?.groupValues?.get(1) ?: return
        try { val json = withContext(Dispatchers.IO) { java.net.URL("https://sponsor.ajay.app/api/skipSegments?videoID=$vid&categories=[%22sponsor%22,%22selfpromo%22,%22interaction%22]").readText() }
            val segs = mutableListOf<Pair<Long, Long>>()
            for (m in Regex("\"segment\"\\s*:\\s*\\[\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*\\]").findAll(json)) segs += (m.groupValues[1].toDouble() * 1000).toLong() to (m.groupValues[2].toDouble() * 1000).toLong()
            sponsorSegments = segs
        } catch (_: Exception) {}
    }
    suspend fun play(articleId: String, mediaUrl: String, title: String, startMs: Long = 0) {
        ensure(); currentArticleId = articleId; _title.value = title
        chapters = repo.misc.chapters(articleId)
        if (chapters.isEmpty()) repo.articles.byId(articleId)?.let { chapters = repo.ensureChapters(it) }
        val local = repo.misc.download(articleId)
        val uri = if (local != null && File(local.filePath).exists()) local.filePath else mediaUrl
        player!!.setMediaItem(MediaItem.Builder().setUri(uri).setMediaId(articleId).build())
        val saved = repo.misc.playback(articleId)
        val resume = if (startMs > 0) startMs else (saved?.positionMs ?: 0)
        player!!.prepare(); if (resume > 0) player!!.seekTo(resume); player!!.play()
    }
    fun toggle() { ensure(); if (player!!.isPlaying) player!!.pause() else player!!.play() }
    fun seekBy(ms: Long) { player?.seekTo(((player?.currentPosition ?: 0) + ms).coerceAtLeast(0)) }
    fun seekTo(ms: Long) { player?.seekTo(ms) }
    fun setSpeed(s: Float) { _speed.value = s; player?.setPlaybackSpeed(s) }
    fun position() = player?.currentPosition ?: 0
    fun duration() = player?.duration?.coerceAtLeast(0) ?: 0
    fun setSleepTimer(minutes: Int) { sleepJob?.cancel(); if (minutes <= 0) { _sleepAt.value = 0; return }; _sleepAt.value = System.currentTimeMillis() + minutes * 60000L; sleepJob = CoroutineScope(Dispatchers.Main).launch { delay(minutes * 60000L); player?.pause(); _sleepAt.value = 0 } }
    fun chapterList() = chapters
    fun currentId(): String? = currentArticleId
    fun currentTitle(): String = _title.value
    fun seekChapter(index: Int) { chapters.getOrNull(index)?.let { player?.seekTo(it.startMs) } }
    fun nextChapter() { val pos = position(); val i = chapters.indexOfLast { it.startMs <= pos }; seekChapter(i + 1) }
    fun prevChapter() { val pos = position(); val i = chapters.indexOfLast { it.startMs < pos - 3000 }; seekChapter(if (i >= 0) i else 0) }
    suspend fun saveState() { val id = currentArticleId ?: return; val p = player ?: return; repo.misc.upsertPlayback(PlaybackStateEntity(id, p.currentPosition, (p.duration ?: 0).coerceAtLeast(0), p.duration != null && p.duration!! > 0 && p.currentPosition > p.duration!! - 5000)) }
    fun release() { player?.release(); player = null }
}

object Downloader {
    suspend fun downloadEpisode(ctx: Context, repo: Repository, articleId: String, mediaUrl: String): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.getExternalFilesDir(null), "episodes").apply { mkdirs() }
        val name = articleId.take(16) + "_" + URL(mediaUrl).path.substringAfterLast('/').ifEmpty { "episode.mp3" }
        val f = File(dir, name)
        if (!f.exists()) { URL(mediaUrl).openStream().use { input -> f.outputStream().use { input.copyTo(it) } } }
        repo.misc.insertDownload(org.blindrss.android.data.DownloadEntity(articleId, f.absolutePath)); f
    }
}
