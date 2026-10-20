package org.blindrss.android.discovery
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.blindrss.android.feed.FeedDiscovery
import org.blindrss.android.feed.FeedParser
import org.blindrss.android.feed.Opml
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class DiscoveredSource(
    val name: String, val homepage: String, val feedUrl: String?, val description: String = "",
    val category: String = "", val language: String = "", val country: String = "",
    val provider: String, val verified: Boolean = false, val isGoogleNews: Boolean = false,
    var subscribed: Boolean = false
) {
    fun describe(): String = buildString {
        append(name)
        if (description.isNotBlank()) append(". ").append(description.take(120))
        append(". From ").append(provider).append(".")
        if (category.isNotBlank()) append(" Category ").append(category).append(".")
        if (country.isNotBlank()) append(" ").append(country).append(".")
        append(if (feedUrl == null) " No direct feed found." else if (verified) " Verified feed." else " Feed not yet verified.")
        append(if (subscribed) " Already subscribed." else " Not subscribed.")
    }
}

object UrlSafety {
    // Block loopback / private / link-local destinations for user-supplied URLs.
    fun isSafePublicUrl(url: String): Boolean {
        return try {
            val uri = URI(url)
            if (uri.scheme !in listOf("http", "https")) return false
            val host = uri.host?.lowercase() ?: return false
            if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) return false
            val addrs = InetAddress.getAllByName(host)
            addrs.all { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isSiteLocalAddress && !it.isMulticastAddress }
        } catch (_: Exception) { false }
    }
    fun canonicalFeedUrl(url: String): String {
        return try {
            val u = URI(url.trim())
            val scheme = if (u.scheme == "http") "https" else (u.scheme ?: "https")
            val host = (u.host ?: "").lowercase()
            var path = u.path ?: ""
            if (path.length > 1 && path.endsWith("/")) path = path.dropLast(1)
            val query = (u.query ?: "").split("&").filter { it.isNotBlank() && !it.startsWith("utm_") && !it.startsWith("fbclid") }.sorted().joinToString("&")
            "$scheme://$host$path" + if (query.isNotEmpty()) "?$query" else ""
        } catch (_: Exception) { url.trim().lowercase() }
    }
    fun canonicalSite(url: String): String {
        return try { val u = URI(url.trim()); "${(u.scheme ?: "https").lowercase()}://${(u.host ?: "").lowercase().removePrefix("www.")}" } catch (_: Exception) { url.lowercase() }
    }
}

object GoogleNewsUrls {
    val TOPICS = mapOf(
        "General news" to null, "World" to "WORLD", "Politics" to "NATION", "Business" to "BUSINESS",
        "Technology" to "TECHNOLOGY", "Science" to "SCIENCE", "Health" to "HEALTH",
        "Entertainment" to "ENTERTAINMENT", "Sports" to "SPORTS", "Gaming" to "TECHNOLOGY"
    )
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    fun headlines(country: String, language: String): String {
        val gl = country.uppercase(); val hl = "$language-$gl"
        return "https://news.google.com/rss?hl=$hl&gl=$gl&ceid=$gl:$language"
    }
    fun topic(topic: String, country: String, language: String): String {
        val gl = country.uppercase(); val hl = "$language-$gl"
        return "https://news.google.com/rss/headlines/section/topic/$topic?hl=$hl&gl=$gl&ceid=$gl:$language"
    }
    fun search(query: String, country: String, language: String): String {
        val gl = country.uppercase(); val hl = "$language-$gl"
        return "https://news.google.com/rss/search?q=${enc(query)}&hl=$hl&gl=$gl&ceid=$gl:$language"
    }
    fun sourcesFor(category: String, country: String, language: String, city: String, region: String): List<DiscoveredSource> {
        val out = mutableListOf<DiscoveredSource>()
        val countryName = mapOf("CA" to "Canada", "US" to "United States", "GB" to "United Kingdom", "AU" to "Australia", "FR" to "France", "DE" to "Germany").getOrDefault(country.uppercase(), country.uppercase())
        when (category) {
            "Canada", "General news" -> out += DiscoveredSource("Google News: $countryName headlines", "https://news.google.com", headlines(country, language), "Top headlines for $countryName", category, language, country.uppercase(), "Google News", verified = true, isGoogleNews = true)
            "Local news" -> {
                val place = listOf(city, region, countryName).filter { it.isNotBlank() }.joinToString(" ")
                if (place.isNotBlank()) out += DiscoveredSource("Google News: $place", "https://news.google.com", search(place, country, language), "Local news search for $place. This is a search feed, not a single publisher.", category, language, country.uppercase(), "Google News", verified = true, isGoogleNews = true)
                out += DiscoveredSource("Google News: $countryName headlines", "https://news.google.com", headlines(country, language), "Top headlines for $countryName", category, language, country.uppercase(), "Google News", verified = true, isGoogleNews = true)
            }
            else -> {
                val t = TOPICS[category]
                if (t != null) out += DiscoveredSource("Google News: $category", "https://news.google.com", topic(t, country, language), "$category headlines for $countryName", category, language, country.uppercase(), "Google News", verified = true, isGoogleNews = true)
                else out += DiscoveredSource("Google News: $category in $countryName", "https://news.google.com", search("$category $countryName", country, language), "$category news search. This is a search feed, not a single publisher.", category, language, country.uppercase(), "Google News", verified = true, isGoogleNews = true)
            }
        }
        return out
    }
    fun searchSource(query: String, country: String, language: String): DiscoveredSource =
        DiscoveredSource("Google News: $query", "https://news.google.com", search(query, country, language), "News search feed for $query. This is a search feed, not a single publisher.", "", language, country.uppercase(), "Google News", verified = true, isGoogleNews = true)
}

object DiscoveryHttp {
    val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    suspend fun getText(url: String, maxBytes: Int = 512_000): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36").build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            val src = r.body?.source() ?: throw java.io.IOException("Empty response")
            src.request(maxBytes.toLong())
            src.buffer.readUtf8(minOf(src.buffer.size, maxBytes.toLong()))
        }
    }
}

// Feedly's public feed search. Keyless at time of writing; any failure
// (auth change, rate limit, outage) yields no results, never an app error.
object FeedlyDiscovery {
    fun parseResults(json: String): List<DiscoveredSource> {
        // Regex parsing (no org.json) so it is unit-testable and resilient.
        val out = mutableListOf<DiscoveredSource>()
        fun field(obj: String, key: String): String {
            val m = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(obj) ?: return ""
            return m.groupValues[1].replace("\\/", "/").replace("\\\"", "\"").replace("\\u0026", "&")
        }
        for (chunk in json.split("\"feedId\"").drop(1)) {
            val obj = "\"feedId\"" + chunk.take(3000)
            val feedId = field(obj, "feedId"); if (!feedId.startsWith("feed/")) continue
            val feedUrl = feedId.removePrefix("feed/")
            val subs = Regex("\"subscribers\"\\s*:\\s*(\\d+)").find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            var s = DiscoveredSource(field(obj, "title").ifBlank { feedUrl }, field(obj, "website"), feedUrl,
                field(obj, "description"), "", field(obj, "language"), "", "Feedly", verified = false)
            if (subs > 0) s = s.copy(description = (s.description + " $subs subscribers.").trim())
            out += s
        }
        return out
    }
    suspend fun search(query: String, locale: String): List<DiscoveredSource> {
        return try {
            val url = "https://cloud.feedly.com/v3/search/feeds?query=${URLEncoder.encode(query, "UTF-8")}&count=20&locale=${URLEncoder.encode(locale, "UTF-8")}"
            parseResults(DiscoveryHttp.getText(url, 256_000))
        } catch (_: Exception) { emptyList() }
    }
}

// Hardened website autodiscovery: link tags first, then common paths.
// Only feeds that actually parse are returned as verified.
object WebsiteDiscovery {
    val COMMON_PATHS = listOf("/feed", "/feed.xml", "/rss", "/rss.xml", "/atom.xml", "/index.xml", "/feeds/posts/default", "/?feed=rss2")
    suspend fun verifyFeed(feedUrl: String): String? {
        if (!UrlSafety.isSafePublicUrl(feedUrl)) return null
        return try { val body = DiscoveryHttp.getText(feedUrl, 512_000); FeedParser.parse(body, feedUrl).title.ifBlank { null } } catch (_: Exception) { null }
    }
    suspend fun discover(input: String): List<DiscoveredSource> {
        var site = FeedDiscovery.normalizeInput(input)
        if (!site.startsWith("http")) site = "https://$site"
        if (!UrlSafety.isSafePublicUrl(site)) return emptyList()
        val out = mutableListOf<DiscoveredSource>(); val seen = mutableSetOf<String>()
        suspend fun addCandidate(u: String, verified: Boolean, title: String?) {
            val canon = UrlSafety.canonicalFeedUrl(u); if (!seen.add(canon)) return
            val vt = if (verified) title else verifyFeed(u)
            out += DiscoveredSource(title ?: vt ?: u, site, u, "", "", "", "", "Website", verified = vt != null)
            if (vt != null) out[out.lastIndex] = out.last().copy(name = vt)
        }
        try {
            val html = DiscoveryHttp.getText(site)
            val trimmed = html.trimStart()
            if (trimmed.startsWith("<") && (trimmed.contains("<rss") || trimmed.contains("<feed"))) { addCandidate(site, true, null); return out }
            for (link in FeedDiscovery.findFeedLinks(html, site)) addCandidate(link, false, null)
        } catch (_: Exception) {}
        if (out.none { it.verified }) {
            val base = UrlSafety.canonicalSite(site)
            for (p in COMMON_PATHS) { if (out.count { it.verified } >= 3) break; addCandidate(base + p, false, null) }
        }
        return out.filter { it.verified }
    }
}

// Plenary awesome-rss-feeds OPML catalogue, cached on disk (7-day TTL),
// with a small built-in Canadian starter set so it works before any fetch.
object AwesomeCatalogue {
    const val TTL_MS = 7L * 24 * 3600 * 1000
    val COUNTRY_FILES = listOf("Canada", "United States", "United Kingdom", "Australia", "France", "Germany", "India", "Ireland", "Italy", "Japan", "Mexico", "Poland", "Spain", "Ukraine", "Brazil", "South Africa")
    val TOPIC_FILES = listOf("Android", "Apple", "Business & Economy", "Cyber security", "Environment", "Food", "Football", "Gaming", "History", "Movies", "Music", "Politics", "Science", "Technology")
    fun builtin(): List<DiscoveredSource> = listOf(
        DiscoveredSource("CBC Top Stories", "https://www.cbc.ca", "https://www.cbc.ca/webfeed/rss/rss-topstories", "Canadian national news", "General news", "en", "CA", "Publisher catalogue", verified = false),
        DiscoveredSource("CTV News Top Stories", "https://www.ctvnews.ca", "https://www.ctvnews.ca/rss/ctvnews-ca-top-stories-public-rss-1.822009", "Canadian news", "General news", "en", "CA", "Publisher catalogue", verified = false),
        DiscoveredSource("Global News", "https://globalnews.ca", "https://globalnews.ca/feed/", "Canadian news", "General news", "en", "CA", "Publisher catalogue", verified = false),
        DiscoveredSource("Maple Ridge News", "https://www.mapleridgenews.com", "https://www.mapleridgenews.com/rss", "Local news for Maple Ridge and Pitt Meadows, BC", "Local news", "en", "CA", "Publisher catalogue", verified = false),
        DiscoveredSource("BBC News Top Stories", "https://www.bbc.com", "https://feeds.bbci.co.uk/news/rss.xml", "World news", "World", "en", "GB", "Publisher catalogue", verified = false),
        DiscoveredSource("NPR News", "https://www.npr.org", "https://feeds.npr.org/1001/rss.xml", "US news", "General news", "en", "US", "Publisher catalogue", verified = false)
    )
    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "awesome_catalogue.json")
    fun loadCached(ctx: Context): List<DiscoveredSource> {
        return try { val f = cacheFile(ctx); if (!f.exists()) return builtin()
            val arr = org.json.JSONArray(f.readText()); val out = mutableListOf<DiscoveredSource>()
            for (i in 0 until arr.length()) { val o = arr.getJSONObject(i)
                out += DiscoveredSource(o.optString("n"), o.optString("h"), o.optString("f").ifEmpty { null }, o.optString("d"), o.optString("c"), o.optString("l"), o.optString("co"), "Publisher catalogue") }
            if (out.isEmpty()) builtin() else out
        } catch (_: Exception) { builtin() }
    }
    suspend fun refreshIfStale(ctx: Context): List<DiscoveredSource> = withContext(Dispatchers.IO) {
        val f = cacheFile(ctx)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < TTL_MS) return@withContext loadCached(ctx)
        val out = mutableListOf<DiscoveredSource>(); val seen = mutableSetOf<String>()
        fun add(s: DiscoveredSource) { val k = s.feedUrl?.let { UrlSafety.canonicalFeedUrl(it) } ?: return; if (seen.add(k)) out += s }
        for (c in COUNTRY_FILES) {
            try { val opml = DiscoveryHttp.getText("https://raw.githubusercontent.com/plenaryapp/awesome-rss-feeds/master/countries/with_category/${URLEncoder.encode(c, "UTF-8").replace("+", "%20")}.opml", 1_500_000)
                for (e in parseAny(opml)) add(DiscoveredSource(e.title.ifBlank { e.xmlUrl }, "", e.xmlUrl, "", e.category, "", c, "Publisher catalogue")) } catch (_: Exception) {}
        }
        for (t in TOPIC_FILES) {
            try { val file = if (t in listOf("Science", "Technology", "Politics")) null else t; if (file == null) continue
                val opml = DiscoveryHttp.getText("https://raw.githubusercontent.com/plenaryapp/awesome-rss-feeds/master/recommended/without_category/${URLEncoder.encode(file, "UTF-8").replace("+", "%20")}.opml", 1_500_000)
                for (e in parseAny(opml)) add(DiscoveredSource(e.title.ifBlank { e.xmlUrl }, "", e.xmlUrl, "", t, "", "", "Publisher catalogue")) } catch (_: Exception) {}
        }
        for (s in builtin()) add(s)
        if (out.size > 20) { try { val arr = org.json.JSONArray(); for (s in out) arr.put(JSONObject().put("n", s.name).put("h", s.homepage).put("f", s.feedUrl ?: "").put("d", s.description).put("c", s.category).put("l", s.language).put("co", s.country)); f.writeText(arr.toString()) } catch (_: Exception) {} }
        if (out.isEmpty()) loadCached(ctx) else out
    }
    // Some published OPML is not well-formed XML; fall back to a tag scanner.
    fun parseAny(opml: String): List<Opml.Entry> {
        try { val r = Opml.parse(opml); if (r.isNotEmpty()) return r } catch (_: Exception) {}
        val out = mutableListOf<Opml.Entry>(); val stack = mutableListOf<String>()
        fun attr(raw: String, key: String): String {
            val m = Regex("$key\\s*=\\s*\"([^\"]*)\"|$key\\s*=\\s*'([^']*)'", RegexOption.IGNORE_CASE).find(raw) ?: return ""
            return (m.groupValues[1].ifEmpty { m.groupValues[2] }).replace("&amp;", "&").trim()
        }
        for (m in Regex("<(/?)outline\\b([^>]*?)(/?)>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(opml)) {
            if (m.groupValues[1] == "/") { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex); continue }
            val raw = m.groupValues[2]; val url = attr(raw, "xmlUrl")
            val title = attr(raw, "title").ifEmpty { attr(raw, "text") }
            if (url.isNotEmpty()) out += Opml.Entry(title, url, stack.joinToString(" / ").ifEmpty { "Uncategorized" })
            else if (m.groupValues[3] != "/" && title.isNotEmpty()) stack += title
        }
        return out
    }
    fun search(catalogue: List<DiscoveredSource>, query: String, category: String, country: String, language: String): List<DiscoveredSource> {
        var r = catalogue
        if (category.isNotBlank() && category != "All") r = r.filter { it.category.contains(category, true) || category.contains(it.category, true) && it.category.isNotBlank() || it.category.isBlank() && category == "General news" }
        if (country.isNotBlank()) r = r.filter { it.country.isEmpty() || it.country.equals(country, true) || countryName(it.country).equals(countryName(country), true) }
        if (language.isNotBlank()) r = r.filter { it.language.isEmpty() || it.language.equals(language, true) }
        if (query.isNotBlank()) r = r.filter { it.name.contains(query, true) || it.description.contains(query, true) || (it.feedUrl ?: "").contains(query, true) || it.homepage.contains(query, true) }
        return r
    }
    fun countryName(codeOrName: String): String = mapOf("CA" to "Canada", "US" to "United States", "GB" to "United Kingdom", "AU" to "Australia", "FR" to "France", "DE" to "Germany").getOrDefault(codeOrName, codeOrName)
}

// Optional key-based publisher directories. Listings carry no RSS URL, so a
// feed only appears once website autodiscovery verifies one.
object NewsApisDiscovery {
    suspend fun search(query: String, newsApiKey: String, theNewsApiKey: String): List<DiscoveredSource> {
        val out = mutableListOf<DiscoveredSource>()
        if (newsApiKey.isNotBlank()) {
            try { val json = DiscoveryHttp.getText("https://newsapi.org/v2/top-headlines/sources?apiKey=${URLEncoder.encode(newsApiKey, "UTF-8")}", 256_000)
                val arr = JSONObject(json).optJSONArray("sources")
                if (arr != null) for (i in 0 until arr.length()) { val o = arr.getJSONObject(i)
                    if (!o.optString("name").contains(query, true) && !o.optString("description").contains(query, true) && query.isNotBlank()) continue
                    val site = o.optString("url"); val found = WebsiteDiscovery.discover(site)
                    for (f in found) out += f.copy(name = o.optString("name").ifBlank { f.name }, description = o.optString("description"), category = o.optString("category"), language = o.optString("language"), country = o.optString("country").uppercase(), provider = "NewsAPI.org") }
            } catch (_: Exception) {}
        }
        if (theNewsApiKey.isNotBlank()) {
            try { val json = DiscoveryHttp.getText("https://api.thenewsapi.com/v1/news/sources?api_token=${URLEncoder.encode(theNewsApiKey, "UTF-8")}&search=${URLEncoder.encode(query, "UTF-8")}&limit=10", 256_000)
                val arr = JSONObject(json).optJSONArray("data")
                if (arr != null) for (i in 0 until arr.length()) { val o = arr.getJSONObject(i)
                    val domain = o.optString("domain"); if (domain.isBlank()) continue
                    val found = WebsiteDiscovery.discover("https://$domain")
                    for (f in found) out += f.copy(name = o.optString("name").ifBlank { f.name }, description = o.optString("description"), language = o.optString("locale"), provider = "The News API") }
            } catch (_: Exception) {}
        }
        return out
    }
}

object DiscoveryAggregator {
    fun dedup(sources: List<DiscoveredSource>): List<DiscoveredSource> {
        val seen = mutableMapOf<String, DiscoveredSource>()
        for (s in sources) {
            val key = when {
                s.feedUrl != null -> "feed:" + UrlSafety.canonicalFeedUrl(s.feedUrl)
                else -> "site:" + UrlSafety.canonicalSite(s.homepage) + ":" + s.name.lowercase()
            }
            val prev = seen[key]
            if (prev == null || (!prev.verified && s.verified)) seen[key] = s
        }
        return seen.values.toList()
    }
    fun markSubscribed(sources: List<DiscoveredSource>, subscribedFeedUrls: Collection<String>): List<DiscoveredSource> {
        val canon = subscribedFeedUrls.map { UrlSafety.canonicalFeedUrl(it) }.toSet()
        return sources.map { s -> s.copy(subscribed = s.feedUrl != null && UrlSafety.canonicalFeedUrl(s.feedUrl) in canon) }
    }
}
