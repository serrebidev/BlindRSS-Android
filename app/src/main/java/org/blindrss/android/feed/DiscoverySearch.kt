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
        // The remaining keyless directories the iOS port searches (0.1.12):
        // fyyd, Podverse, Feedly, NewsBlur, Reddit, Lemmy, Groups.io, Feedspot,
        // plus Google News / Bing News search feeds as hits in their own right.
        try { out += parseFyyd(get("https://api.fyyd.de/0.2/search/podcast?term=$q&count=20")) } catch (_: Exception) {}
        try { out += parsePodverse(get("https://api.podverse.fm/api/v1/podcast?searchTitle=$q")) } catch (_: Exception) {}
        try { out += parseFeedly(get("https://cloud.feedly.com/v3/search/feeds?q=$q")) } catch (_: Exception) {}
        try { out += parseNewsBlur(get("https://www.newsblur.com/discover/autocomplete?term=$q&v=2")) } catch (_: Exception) {}
        try { out += parseReddit(get("https://www.reddit.com/subreddits/search.json?q=$q&limit=10")) } catch (_: Exception) {}
        try { out += parseLemmy(get("https://lemmy.world/api/v3/search?q=$q&type_=Communities&sort=TopAll&limit=15")) } catch (_: Exception) {}
        try { out += parseGroupsIoSearch(get("https://groups.io/search?q=$q")) } catch (_: Exception) {}
        try { out += parseFeedspot(get("https://rss.feedspot.com/${feedspotSlug(query)}_rss_feeds/")) } catch (_: Exception) {}
        out += SearchHit("Google News: $query", "https://news.google.com/rss/search?q=$q&hl=en&gl=US&ceid=US:en", "Google News")
        out += SearchHit("Bing News: $query", "https://www.bing.com/news/search?q=$q&format=rss", "Bing News")
        return out.distinctBy { it.feedUrl }
    }
    // Directory parsers below use org.json (nested shapes); the older ones
    // above use regexes. All are pure and unit-tested with fixtures.
    fun parseFyyd(json: String): List<SearchHit> { val out = mutableListOf<SearchHit>()
        val data = try { org.json.JSONObject(json).optJSONArray("data") } catch (_: Exception) { null } ?: return out
        for (i in 0 until data.length()) { val o = data.optJSONObject(i) ?: continue; val url = o.optString("xmlURL"); if (url.isBlank()) continue
            out += SearchHit(o.optString("title").ifBlank { url }, url, "fyyd") }
        return out }
    fun parsePodverse(json: String): List<SearchHit> { val out = mutableListOf<SearchHit>()
        // Response shape: { "data": [ [items...], total_count ] } (a bare
        // top-level array of the same shape is also accepted).
        val items = try { org.json.JSONObject(json).optJSONArray("data")?.optJSONArray(0) } catch (_: Exception) { null }
            ?: try { org.json.JSONArray(json).optJSONArray(0) } catch (_: Exception) { null } ?: return out
        for (i in 0 until items.length()) { val o = items.optJSONObject(i) ?: continue
            val url = o.optJSONArray("feedUrls")?.optJSONObject(0)?.optString("url").orEmpty(); if (url.isBlank()) continue
            out += SearchHit(o.optString("title").ifBlank { url }, url, "Podverse") }
        return out }
    fun parseFeedly(json: String): List<SearchHit> { val out = mutableListOf<SearchHit>()
        val results = try { org.json.JSONObject(json).optJSONArray("results") } catch (_: Exception) { null } ?: return out
        for (i in 0 until results.length()) { val o = results.optJSONObject(i) ?: continue; val id = o.optString("feedId")
            if (!id.startsWith("feed/")) continue; val url = id.removePrefix("feed/")
            out += SearchHit(o.optString("title").ifBlank { url }, url, "Feedly") }
        return out }
    fun parseNewsBlur(json: String): List<SearchHit> { val out = mutableListOf<SearchHit>(); val seen = mutableSetOf<String>()
        // Autocomplete answers a bare array; tolerate object wrappers too.
        val feeds = try { val r = org.json.JSONObject(json); r.optJSONArray("feeds") ?: r.optJSONArray("results") } catch (_: Exception) { null }
            ?: try { org.json.JSONArray(json) } catch (_: Exception) { null } ?: return out
        for (i in 0 until feeds.length()) { val o = feeds.optJSONObject(i) ?: continue
            val url = o.optString("value").ifEmpty { o.optString("address") }
            if (!url.contains("://") || !seen.add(url)) continue
            out += SearchHit(o.optString("label").ifBlank { url }, url, "NewsBlur") }
        return out }
    fun parseReddit(json: String): List<SearchHit> { val out = mutableListOf<SearchHit>()
        val children = try { org.json.JSONObject(json).optJSONObject("data")?.optJSONArray("children") } catch (_: Exception) { null } ?: return out
        for (i in 0 until children.length()) { val name = children.optJSONObject(i)?.optJSONObject("data")?.optString("display_name").orEmpty()
            if (name.isNotBlank()) out += SearchHit("r/$name", "https://www.reddit.com/r/$name/.rss", "Reddit") }
        return out }
    fun parseLemmy(json: String): List<SearchHit> { val out = mutableListOf<SearchHit>()
        val communities = try { org.json.JSONObject(json).optJSONArray("communities") } catch (_: Exception) { null } ?: return out
        for (i in 0 until communities.length()) { val c = communities.optJSONObject(i)?.optJSONObject("community") ?: continue
            val actor = c.optString("actor_id"); val feed = lemmyCommunityFeed(actor) ?: continue
            out += SearchHit(c.optString("title").ifEmpty { c.optString("name") }.ifEmpty { actor }, feed, "Lemmy") }
        return out }
    // .../c/name on an instance -> <instance>/feeds/c/name.xml (iOS/desktop rule).
    fun lemmyCommunityFeed(actorId: String): String? { val idx = actorId.indexOf("/c/"); if (idx < 0) return null
        val name = actorId.substring(idx + 3).split("/").firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return actorId.substring(0, idx) + "/feeds/c/$name.xml" }
    fun feedspotSlug(term: String): String = term.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        .replace(Regex("_+"), "_").trim('_')
    // Groups.io public directory pages are HTML: group links are /g/<name>.
    fun parseGroupsIoSearch(html: String): List<SearchHit> { val out = mutableListOf<SearchHit>(); val seen = mutableSetOf<String>()
        try { for (a in org.jsoup.Jsoup.parse(html).select("a[href]")) { val href = a.attr("href")
            val m = Regex("/g/([\\w.\\-]+)").find(href) ?: continue; val name = m.groupValues[1]
            if (name == "search" || !seen.add(name)) continue
            out += SearchHit(a.text().trim().ifEmpty { name }, "https://groups.io/g/$name/rss", "Groups.io")
            if (out.size >= 20) break } } catch (_: Exception) { }
        return out }
    // Feedspot topic pages: each entry's feed link is an anchor with class
    // "ext" (but not "extdomain"); its title is the preceding feed_heading.
    fun parseFeedspot(html: String): List<SearchHit> { val out = mutableListOf<SearchHit>(); val seen = mutableSetOf<String>()
        try { val doc = org.jsoup.Jsoup.parse(html); var pendingTitle = ""
            for (a in doc.select("a[href]")) { val cls = a.classNames()
                if (a.parents().any { it.hasClass("feed_heading") }) { pendingTitle = a.text().trim(); continue }
                if ("ext" !in cls || "extdomain" in cls) continue
                val href = a.attr("abs:href").ifEmpty { a.attr("href") }
                if (!href.startsWith("http") || href.contains("feedspot.com") || !seen.add(href)) continue
                out += SearchHit(pendingTitle.ifEmpty { href }, href, "Feedspot"); pendingTitle = ""
                if (out.size >= 50) break } } catch (_: Exception) { }
        return out }
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
