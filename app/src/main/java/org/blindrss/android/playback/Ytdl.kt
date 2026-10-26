package org.blindrss.android.playback
import android.content.Context
import android.net.Uri
import android.os.Build
import com.sipun.ffmpeg.FFmpeg
import com.sipun.youtubedl_android.YoutubeDL
import com.sipun.youtubedl_android.YoutubeDLRequest
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

// yt-dlp (nightly channel) through the bundled youtubedl-kotlin runtime: plays and downloads audio from YouTube and
// the other sites yt-dlp supports. QuickJS is the JavaScript runtime it uses for YouTube's challenges (Deno has
// no Android build). Calls block; run them off the main thread.
object Ytdl {
    const val SCHEME = "ytdl"
    // The bundled Python and FFmpeg are built for 64-bit ARM, Android 11 and newer.
    fun supported() = Build.VERSION.SDK_INT >= 30 && "arm64-v8a" in Build.SUPPORTED_ABIS
    fun uriFor(pageUrl: String): Uri = Uri.parse("$SCHEME:$pageUrl")
    private var ready = false
    // Stream addresses expire after a few hours; reuse them for 1 hour.
    private val streams = ConcurrentHashMap<String, Pair<String, Long>>()

    @Synchronized private fun init(ctx: Context) {
        if (!supported()) throw IOException("Playing from web pages needs Android 11 or newer on a 64-bit device")
        if (ready) return
        try { FFmpeg.init(ctx.applicationContext); YoutubeDL.init(ctx.applicationContext) } catch (e: Exception) { throw IOException("yt-dlp could not start: ${e.message}", e) }
        ready = true
        Thread { updateIfDue(ctx) }.start()
    }
    // Nightly yt-dlp, checked at most once a day.
    private fun updateIfDue(ctx: Context) {
        val prefs = ctx.getSharedPreferences("ytdl", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("checked", 0) < 86_400_000L) return
        try { YoutubeDL.updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.NIGHTLY); prefs.edit().putLong("checked", System.currentTimeMillis()).apply() } catch (_: Exception) {}
    }
    fun updateNow(ctx: Context): String {
        init(ctx)
        return try { val s = YoutubeDL.updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)
            ctx.getSharedPreferences("ytdl", Context.MODE_PRIVATE).edit().putLong("checked", System.currentTimeMillis()).apply()
            if (s == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) "yt-dlp is up to date" else "yt-dlp updated to ${version(ctx)}"
        } catch (e: Exception) { "yt-dlp update failed: ${e.message}" }
    }
    fun version(ctx: Context): String = YoutubeDL.versionName(ctx.applicationContext) ?: YoutubeDL.version(ctx.applicationContext) ?: "bundled nightly"

    // Full info JSON for a page (description, chapters, subtitle tracks, top
    // comments) without downloading media. Backs the reader's YouTube full text.
    fun infoJsonBlocking(ctx: Context, pageUrl: String): String {
        init(ctx)
        val req = YoutubeDLRequest(pageUrl).addOption("-J").addOption("--skip-download").addOption("--write-comments")
            .addOption("--no-playlist").addOption("--no-warnings")
        val res = try { YoutubeDL.execute(req) } catch (e: Exception) { throw IOException(e.message?.lines()?.lastOrNull { it.isNotBlank() } ?: "yt-dlp failed", e) }
        val out = res.out.trim()
        if (!out.startsWith("{")) throw IOException(res.err.lines().lastOrNull { it.isNotBlank() } ?: "No video info found on this page")
        return out
    }

    // Direct audio address for a page: m4a first (plays everywhere without conversion), else any audio.
    fun streamUrlBlocking(ctx: Context, pageUrl: String): String {
        streams[pageUrl]?.let { (url, at) -> if (System.currentTimeMillis() - at < 3_600_000L) return url }
        init(ctx)
        val req = YoutubeDLRequest(pageUrl).addOption("-f", "bestaudio[ext=m4a]/bestaudio/best[acodec!=none]")
            .addOption("--get-url").addOption("--no-playlist").addOption("--no-warnings")
        val res = try { YoutubeDL.execute(req) } catch (e: Exception) { throw IOException(e.message?.lines()?.lastOrNull { it.isNotBlank() } ?: "yt-dlp failed", e) }
        val url = res.out.lines().firstOrNull { it.startsWith("http") } ?: throw IOException(res.err.lines().lastOrNull { it.isNotBlank() } ?: "No audio found on this page")
        streams[pageUrl] = url to System.currentTimeMillis()
        return url
    }
}
