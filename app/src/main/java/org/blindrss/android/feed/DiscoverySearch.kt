package org.blindrss.android.feed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

data class SearchHit(val title: String, val feedUrl: String, val source: String)

// Podcast directory search with no API key: Apple iTunes Search + gPodder.
object DiscoverySearch {
    private val http = OkHttpClient()
    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("User-Agent", "BlindRSS-Android/1.1").build()).execute().use { r -> r.body?.string() ?: "" }
    }
    suspend fun search(query: String): List<SearchHit> {
        val q = URLEncoder.encode(query, "UTF-8"); val out = mutableListOf<SearchHit>()
        try { out += parseItunes(get("https://itunes.apple.com/search?media=podcast&limit=25&term=$q")) } catch (_: Exception) {}
        try { out += parseGpodder(get("https://gpodder.net/search.json?q=$q")) } catch (_: Exception) {}
        return out.distinctBy { it.feedUrl }
    }
    fun parseItunes(json: String): List<SearchHit> {
        val urls = Regex("\"feedUrl\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1].replace("\\/", "/") }.toList()
        val names = Regex("\"collectionName\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()
        return urls.mapIndexed { i, u -> SearchHit(names.getOrNull(i) ?: u, u, "Apple Podcasts") }
    }
    fun parseGpodder(json: String): List<SearchHit> {
        val urls = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1].replace("\\/", "/") }.toList()
        val titles = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()
        return urls.mapIndexed { i, u -> SearchHit(titles.getOrNull(i) ?: u, u, "gPodder") }.filter { it.feedUrl.startsWith("http") }
    }
}

// App update check against this repo's GitHub releases.
object UpdateCheck {
    private val http = OkHttpClient()
    suspend fun latestVersion(): String? = withContext(Dispatchers.IO) {
        try { http.newCall(Request.Builder().url("https://api.github.com/repos/serrebidev/BlindRSS-Android/releases/latest").header("User-Agent", "BlindRSS-Android").build()).execute().use { r ->
            val t = r.body?.string() ?: return@use null; Regex("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"").find(t)?.groupValues?.get(1) } } catch (_: Exception) { null }
    }
}
