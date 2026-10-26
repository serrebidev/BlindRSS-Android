package org.blindrss.android.update
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

// Self-update from this repo's GitHub releases (Brandon's ask): check the
// latest release, compare with the installed version, download the release
// APK and hand it to the Android installer. Releases are signed with the
// repo's own key (see app/build.gradle.kts), so an update installs over the
// current build. Parsing and version comparison are pure and unit-tested.
object AppUpdate {
    const val RELEASES_API = "https://api.github.com/repos/serrebidev/BlindRSS-Android/releases/latest"
    const val RELEASES_PAGE = "https://github.com/serrebidev/BlindRSS-Android/releases/latest"

    data class ReleaseInfo(val version: String, val apkUrl: String, val apkName: String, val sizeBytes: Long, val pageUrl: String)

    fun parseRelease(json: String): ReleaseInfo? {
        val root = try { JSONObject(json) } catch (_: Exception) { return null }
        val version = root.optString("tag_name").trim().removePrefix("v").removePrefix("V")
        if (version.isEmpty()) return null
        val assets = root.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            if (name.endsWith(".apk", true) && url.isNotEmpty())
                return ReleaseInfo(version, url, name, a.optLong("size"), root.optString("html_url").ifEmpty { RELEASES_PAGE })
        }
        return null
    }
    // Dotted-numeric compare ("0.11.0" > "0.10.0"); non-numeric suffixes
    // ("1.2-beta") compare lexically after their numeric part.
    fun compareVersions(a: String, b: String): Int {
        val pa = a.trim().removePrefix("v").split(".")
        val pb = b.trim().removePrefix("v").split(".")
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { "0" }; val y = pb.getOrElse(i) { "0" }
            val xn = x.takeWhile { it.isDigit() }; val yn = y.takeWhile { it.isDigit() }
            val c = (xn.toLongOrNull() ?: 0L).compareTo(yn.toLongOrNull() ?: 0L)
            if (c != 0) return c
            val s = x.drop(xn.length).compareTo(y.drop(yn.length))
            if (s != 0) return s
        }
        return 0
    }
    fun isNewer(candidate: String, installed: String): Boolean = compareVersions(candidate, installed) > 0

    private val http = OkHttpClient()
    suspend fun latestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try { http.newCall(Request.Builder().url(RELEASES_API).header("User-Agent", "BlindRSS-Android").build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()?.let { parseRelease(it) } } } catch (_: Exception) { null }
    }
    fun installedVersion(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

    suspend fun downloadApk(ctx: Context, info: ReleaseInfo, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, info.apkName.ifEmpty { "BlindRSS-${info.version}.apk" })
        http.newCall(Request.Builder().url(info.apkUrl).header("User-Agent", "BlindRSS-Android").build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("Download failed: HTTP ${r.code}")
            val body = r.body ?: throw java.io.IOException("Download failed: empty response")
            val total = if (info.sizeBytes > 0) info.sizeBytes else body.contentLength()
            body.byteStream().use { input -> out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) { val n = input.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n
                    if (total > 0) onProgress((done * 100 / total).toInt().coerceIn(0, 100)) }
            } }
        }
        out
    }
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()
    // The system settings screen where the user allows this app to install.
    fun unknownSourcesIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun installIntent(ctx: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
