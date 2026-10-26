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
        try { out += parseFeedsearch(get("https://feedsearch.dev/api/v1/search?q=$q")) } catch (_: Exception) {}
        try { out += parseMastodon(get(mastodonSearchUrl(query))) } catch (_: Exception) {}
        try { out += parseBluesky(get("https://public.api.bsky.app/xrpc/app.bsky.actor.searchActorsTypeahead?q=$q&limit=10")) } catch (_: Exception) {}
        try { out += parsePiefed(get("https://piefed.social/api/alpha/search?q=$q&type_=Communities&limit=10"), "PieFed community") } catch (_: Exception) {}
        try { out += parsePiefed(get("https://piefed.social/api/alpha/search?q=$q&type_=Users&limit=10"), "PieFed user") } catch (_: Exception) {}
        // SoundCloud/Mixcloud hits are page addresses; subscribing enumerates
        // them as listing feeds (see MediaSites).
        try { out += SoundCloudApi.searchFeeds(query) } catch (_: Exception) {}
        try { out += MixcloudApi.searchFeeds(query) } catch (_: Exception) {}
        return out.distinctBy { it.feedUrl }
    }
    fun mastodonSearchUrl(query: String): String {
        val resolve = if ("@" in query && "." in query) "&resolve=true" else ""
        return "https://mastodon.social/api/v2/search?q=${URLEncoder.encode(query, "UTF-8")}&type=accounts&limit=12$resolve"
    }
    // Feedsearch.dev: keyless feed search returning feed urls directly.
    fun parseFeedsearch(json: String): List<SearchHit> {
        val urls = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1].replace("\\/", "/") }.toList()
        val titles = Regex("\"title\"\\s*:\\s*\"([^\"]*)\"").findAll(json).map { it.groupValues[1] }.toList()
        return urls.mapIndexed { i, u -> SearchHit(titles.getOrNull(i)?.ifBlank { u } ?: u, u, "Feedsearch") }.filter { it.feedUrl.startsWith("http") }
    }
    // Mastodon account search (any account's profile serves RSS at <profile>.rss).
    // Only the accounts array is read; statuses in the same response also carry "url" fields.
    fun parseMastodon(json: String): List<SearchHit> {
        val accounts = json.substringAfter("\"accounts\"", "").substringBefore("\"statuses\"").ifEmpty { json.substringAfter("\"accounts\"", "") }
        val urls = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").findAll(accounts).map { it.groupValues[1].replace("\\/", "/") }.toList()
        val names = Regex("\"display_name\"\\s*:\\s*\"([^\"]*)\"").findAll(accounts).map { it.groupValues[1] }.toList()
        val accts = Regex("\"acct\"\\s*:\\s*\"([^\"]+)\"").findAll(accounts).map { it.groupValues[1] }.toList()
        return urls.mapIndexed { i, u -> val label = names.getOrNull(i)?.ifBlank { null } ?: accts.getOrNull(i) ?: u
            SearchHit(label, u.trimEnd('/') + ".rss", "Mastodon") }.filter { it.feedUrl.startsWith("http") }
    }
    // Bluesky actor search; every public profile serves RSS at bsky.app/profile/<handle>/rss.
    fun parseBluesky(json: String): List<SearchHit> {
        val handles = Regex("\"handle\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()
        val names = Regex("\"displayName\"\\s*:\\s*\"([^\"]*)\"").findAll(json).map { it.groupValues[1] }.toList()
        return handles.mapIndexed { i, h -> SearchHit(names.getOrNull(i)?.ifBlank { h } ?: h, "https://bsky.app/profile/$h/rss", "Bluesky") }
    }
    // PieFed (Lemmy-shaped) search: community and user pages convert to feeds via the Lemmy URL rules.
    fun parsePiefed(json: String, source: String): List<SearchHit> {
        val urls = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1].replace("\\/", "/") }.toList()
        val names = Regex("\"(name|title)\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[2] }.toList()
        return urls.mapIndexedNotNull { i, u -> val feed = FeedDiscovery.normalizeInput(u)
            if (feed == u && !feed.endsWith(".xml") && !feed.endsWith(".rss")) null
            else SearchHit(names.getOrNull(i) ?: u, feed, source) }
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

// App updates live in update/AppUpdate.kt (full release info + installer).
