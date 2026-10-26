package org.blindrss.android.feed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

// SoundCloud, Mixcloud, Google Groups and Groups.io support, mirroring the
// desktop's core/discovery.py and core/groups_io.py / forum_sources.py:
// - Feed search over keyless public APIs (Mixcloud's own API; SoundCloud
//   api-v2 with a client_id fetched from SoundCloud's web assets at runtime,
//   the same approach yt-dlp and the desktop use — nothing is hardcoded).
// - Listing feeds: a SoundCloud/Mixcloud user or playlist page, or a Google
//   Groups group page, subscribed as a feed and enumerated on refresh.
// - Groups.io group pages map to the group's native RSS.
// Parsing is pure where it can be; the network calls live in the Api objects.

data class ListingItem(val url: String, val title: String, val author: String = "", val dateMs: Long = 0)

enum class ListingKind { SOUNDCLOUD, MIXCLOUD, GOOGLE_GROUPS, YOUTUBE }

object MediaSites {
    fun listingKind(url: String): ListingKind? = when {
        SoundCloud.listingKind(url).isNotEmpty() -> ListingKind.SOUNDCLOUD
        Mixcloud.listingKind(url).isNotEmpty() -> ListingKind.MIXCLOUD
        GoogleGroups.isGroupUrl(url) -> ListingKind.GOOGLE_GROUPS
        YoutubeListing.isListingUrl(url) -> ListingKind.YOUTUBE
        else -> null
    }
    // Pages whose articles play through yt-dlp (the player resolves the page
    // URL itself), so rows offer Play/Download even with no direct media link.
    private val playableHosts = listOf("youtube.com", "youtu.be", "soundcloud.com", "mixcloud.com", "vimeo.com", "dailymotion.com", "twitch.tv", "bandcamp.com")
    fun isPlayablePage(url: String): Boolean {
        val host = try { java.net.URI(url.trim()).host?.lowercase() ?: "" } catch (_: Exception) { "" }
        return playableHosts.any { host == it || host.endsWith(".$it") }
    }
    // Title of the listing (channel/user/group name) when a parse provides one.
    fun parseIsoDate(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        for (p in listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy/MM/dd HH:mm:ss +0000")) {
            try { return java.text.SimpleDateFormat(p, java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(s.trim())!!.time } catch (_: Exception) {}
        }
        return 0
    }
}

object SoundCloud {
    private val RESERVED = setOf("you", "search", "discover", "stream", "upload", "settings", "pages", "tags")
    fun isUrl(url: String): Boolean {
        val host = host(url); if (host == "feeds.soundcloud.com") return false
        return host == "soundcloud.com" || host.endsWith(".soundcloud.com")
    }
    // "user", "playlist", "track" or "" — mirrors desktop soundcloud_listing_kind.
    fun listingKind(url: String): String {
        if (!isUrl(url)) return ""
        val parts = path(url).split("/").filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        if ("sets" in parts) return "playlist"
        if (parts[0].lowercase() in RESERVED) return ""
        return when (parts.size) { 1 -> "user"; 2 -> "track"; else -> "" }
    }
    private fun host(url: String) = try { java.net.URI(url.trim()).host?.lowercase() ?: "" } catch (_: Exception) { "" }
    private fun path(url: String) = try { java.net.URI(url.trim()).path ?: "" } catch (_: Exception) { "" }

    // The client_id ships inside SoundCloud's own JS bundles; take the
    // last-loaded ones first, as the app bundle loads late (desktop order).
    fun clientIdFromAssets(html: String, jsBodies: List<String>): String {
        for (body in jsBodies) Regex("client_id[:=]\"([0-9A-Za-z]{20,})\"").find(body)?.let { return it.groupValues[1] }
        return ""
    }
    fun assetUrls(html: String): List<String> =
        Regex("https://[a-z0-9\\-]+\\.sndcdn\\.com/assets/[^\"']+\\.js").findAll(html).map { it.value }.toList().reversed()

    fun parseUserSearch(json: String): List<SearchHit> = parseCollection(json) { o ->
        val url = o.optString("permalink_url"); if (url.isEmpty()) null else {
            val followers = o.optLong("followers_count")
            SearchHit(o.optString("username").ifEmpty { url }, url, "SoundCloud user" + if (followers > 0) " • $followers followers" else "") }
    }
    fun parsePlaylistSearch(json: String): List<SearchHit> = parseCollection(json) { o ->
        val url = o.optString("permalink_url"); if (url.isEmpty()) null else {
            val owner = o.optJSONObject("user")?.optString("username") ?: ""
            val name = o.optString("title")
            SearchHit(if (owner.isNotEmpty()) "$name ($owner)" else name.ifEmpty { url }, url, "SoundCloud playlist") }
    }
    private fun parseCollection(json: String, f: (JSONObject) -> SearchHit?): List<SearchHit> {
        val out = mutableListOf<SearchHit>()
        try { val coll = JSONObject(json).optJSONArray("collection") ?: return out
            for (i in 0 until coll.length()) { val o = coll.optJSONObject(i) ?: continue; f(o)?.let { out += it } }
        } catch (_: Exception) {}
        return out
    }
    // api-v2 resolve of a user page -> the user object (id + track count).
    fun parseResolvedUser(json: String): JSONObject? = try { JSONObject(json) } catch (_: Exception) { null }
    fun parseTracks(json: String, defaultAuthor: String): List<ListingItem> {
        val out = mutableListOf<ListingItem>()
        try { val coll = JSONObject(json).optJSONArray("collection") ?: JSONArray()
            for (i in 0 until coll.length()) { val t = coll.optJSONObject(i) ?: continue
                val url = t.optString("permalink_url"); if (url.isEmpty()) continue
                val author = t.optJSONObject("user")?.optString("username")?.ifEmpty { defaultAuthor } ?: defaultAuthor
                out += ListingItem(url, t.optString("title").ifEmpty { url }, author, MediaSites.parseIsoDate(t.optString("created_at"))) }
        } catch (_: Exception) {}
        return out
    }
    // A playlist object embeds its tracks.
    fun parsePlaylistTracks(json: String, defaultAuthor: String): List<ListingItem> {
        val out = mutableListOf<ListingItem>()
        try { val tracks = JSONObject(json).optJSONArray("tracks") ?: return out
            for (i in 0 until tracks.length()) { val t = tracks.optJSONObject(i) ?: continue
                val url = t.optString("permalink_url"); if (url.isEmpty()) continue
                val author = t.optJSONObject("user")?.optString("username")?.ifEmpty { defaultAuthor } ?: defaultAuthor
                out += ListingItem(url, t.optString("title").ifEmpty { url }, author, MediaSites.parseIsoDate(t.optString("created_at"))) }
        } catch (_: Exception) {}
        return out
    }
}

object Mixcloud {
    fun isUrl(url: String): Boolean { val h = host(url); return h == "mixcloud.com" || h.endsWith(".mixcloud.com") }
    // "/<user>/" or "/<user>/playlists/<slug>/" style key, "" when not Mixcloud.
    fun keyFromUrl(url: String): String {
        if (!isUrl(url)) return ""
        val p = path(url).trim('/'); if (p.isEmpty()) return ""
        return "/$p/"
    }
    fun listingKind(url: String): String {
        val key = keyFromUrl(url); if (key.isEmpty()) return ""
        val parts = key.split("/").filter { it.isNotEmpty() }
        return when { parts.size == 1 -> "user"; parts.size == 3 && parts[1] == "playlists" -> "playlist"; else -> "" }
    }
    private fun host(url: String) = try { java.net.URI(url.trim()).host?.lowercase() ?: "" } catch (_: Exception) { "" }
    private fun path(url: String) = try { java.net.URI(url.trim()).path ?: "" } catch (_: Exception) { "" }

    fun parseUserSearch(json: String): List<SearchHit> {
        val out = mutableListOf<SearchHit>()
        try { val data = JSONObject(json).optJSONArray("data") ?: return out
            for (i in 0 until data.length()) { val u = data.optJSONObject(i) ?: continue
                val url = u.optString("url"); if (url.isEmpty()) continue
                val followers = u.optLong("follower_count")
                out += SearchHit(u.optString("name").ifEmpty { u.optString("username") }.ifEmpty { url }, url,
                    "Mixcloud user" + if (followers > 0) " • $followers followers" else "") }
        } catch (_: Exception) {}
        return out
    }
    fun parseMediaSearch(json: String): List<VideoResult> {
        val out = mutableListOf<VideoResult>()
        try { val data = JSONObject(json).optJSONArray("data") ?: return out
            for (i in 0 until data.length()) { val o = data.optJSONObject(i) ?: continue
                val url = o.optString("url"); if (url.isEmpty()) continue
                val author = o.optJSONObject("user")?.optString("name") ?: ""
                val userUrl = o.optJSONObject("user")?.optString("url") ?: ""
                out += VideoResult(o.optString("name").ifEmpty { url }, url, "Mixcloud", author, 0, o.optLong("play_count"), 0, userUrl) }
        } catch (_: Exception) {}
        return out
    }
    fun parseListing(json: String, feedTitle: String): List<ListingItem> {
        val out = mutableListOf<ListingItem>()
        try { val data = JSONObject(json).optJSONArray("data") ?: return out
            for (i in 0 until data.length()) { val o = data.optJSONObject(i) ?: continue
                val url = o.optString("url"); if (url.isEmpty()) continue
                val author = o.optJSONObject("user")?.optString("name")?.ifEmpty { feedTitle } ?: feedTitle
                out += ListingItem(url, o.optString("name").ifEmpty { url }, author, MediaSites.parseIsoDate(o.optString("created_time"))) }
        } catch (_: Exception) {}
        return out
    }
    fun parseName(json: String): String = try { JSONObject(json).optString("name") } catch (_: Exception) { "" }
}

// Listing pages on YouTube itself: channel pages, playlist pages and search
// pages, enumerated by yt-dlp (the desktop's YouTube listing/search feeds).
object YoutubeListing {
    fun isListingUrl(url: String): Boolean {
        val u = try { java.net.URI(url.trim()) } catch (_: Exception) { return false }
        val host = (u.host ?: "").lowercase(); if (host != "youtube.com" && !host.endsWith(".youtube.com")) return false
        val p = u.path ?: ""; val q = u.query ?: ""
        return p.startsWith("/channel/") || p.startsWith("/@") || (p == "/playlist" && "list=" in q) || (p == "/results" && "search_query=" in q) || p.startsWith("/user/")
    }
}

// Google Groups: a group page is a listing feed (newest conversations), and a
// conversation page reads as one whole thread — public pages only, scraped
// with jsoup exactly like the desktop (forum_sources.py). Sign-in-only groups
// answer with a permission page; that stays a clear error, not a session.
object GoogleGroups {
    private val GROUP_RE = Regex("^/g/([\\w.\\-]+)/?$")
    private val THREAD_RE = Regex("^/g/([\\w.\\-]+)/c/([\\w\\-]+)(?:/m/([\\w\\-]+))?/?$")
    fun isGroupUrl(url: String): Boolean = host(url) == "groups.google.com" && GROUP_RE.containsMatchIn(path(url))
    fun isThreadUrl(url: String): Boolean = host(url) == "groups.google.com" && THREAD_RE.containsMatchIn(path(url))
    fun groupName(url: String): String = GROUP_RE.find(path(url))?.groupValues?.get(1) ?: ""
    fun permissionDenied(html: String): Boolean {
        val t = Jsoup.parse(html).text().lowercase()
        return "don't have permission to access this content" in t || "you don’t have permission to access this content" in t
    }
    fun parseGroupItems(html: String, maxItems: Int = 40): Pair<String, List<ListingItem>> {
        val doc = Jsoup.parse(html)
        var title = doc.title().replace(Regex("\\s+-\\s+Google Groups\\s*$"), "").trim()
        val out = mutableListOf<ListingItem>(); val seen = mutableSetOf<String>()
        for (row in doc.select("[data-rowid]")) {
            val convId = row.attr("data-rowid"); if (convId.isEmpty() || convId in seen) continue
            val anchor = row.select("a[href*=\"/c/\"]").firstOrNull { it.select(".o1DPKc").isNotEmpty() || it.select(".t17a0d").isNotEmpty() } ?: continue
            val subject = anchor.select(".o1DPKc").first()?.text()?.trim() ?: anchor.select(".t17a0d").first()?.text()?.trim() ?: ""
            if (subject.isEmpty()) continue
            val itemUrl = java.net.URI("https://groups.google.com/").resolve(anchor.attr("href")).toString()
            if (!isThreadUrl(itemUrl)) continue
            seen += convId
            val authors = row.select(".z0zUgf").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", ")
            out += ListingItem(itemUrl, subject, authors, 0)
            if (out.size >= maxItems) break
        }
        if (title.isEmpty()) title = "Google Group"
        return title to out
    }
    // One conversation page -> one readable document of its messages.
    fun threadHtml(html: String, canonicalUrl: String): String {
        if (permissionDenied(html)) return ""
        val doc = Jsoup.parse(html)
        var title = doc.title().replace(Regex("\\s+-\\s+Google Groups\\s*$"), "").trim()
        if (title.isEmpty()) title = "Google Groups conversation"
        val sb = StringBuilder("<h1>").append(esc(title)).append("</h1>")
        var n = 0
        for (block in doc.select("div.eH2Xlc")) {
            val bodyNode = block.select("[role=\"region\"][aria-labelledby]").first() ?: block.select(".ptW7te").first() ?: continue
            val body = cleanPostHtml(bodyNode.html()); if (body.isBlank()) continue
            val author = block.select("h3").first()?.text()?.trim()?.ifEmpty { "Unknown author" } ?: "Unknown author"
            val date = block.select(".zX2W9c").first()?.text()?.trim() ?: block.select("time").first()?.text()?.trim() ?: ""
            n++
            sb.append("<h2>#").append(n).append(if (n == 1) " Posted by " else " Message by ").append(esc(author))
            if (date.isNotEmpty()) sb.append(" — ").append(esc(date))
            sb.append("</h2>").append(body)
        }
        return if (n == 0) "" else sb.toString()
    }
    fun cleanPostHtml(raw: String): String {
        val doc = Jsoup.parseBodyFragment(raw)
        doc.select("script, style, iframe, object, embed, form, input, button, .gmail_quote, .wqmMgb").remove()
        return doc.body().html().trim()
    }
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun host(url: String) = try { java.net.URI(url.trim()).host?.lowercase() ?: "" } catch (_: Exception) { "" }
    private fun path(url: String) = try { java.net.URI(url.trim()).path ?: "" } catch (_: Exception) { "" }
}

// Groups.io: group pages map to the group's native RSS; public topic pages
// read as whole threads (the desktop's HTML path). The optional API-key path
// (member-only archives) has no Android account setting and stays desktop-only.
object GroupsIo {
    private val GROUP_RE = Regex("^/g/([\\w.\\-]+)(?:/(?:topics|messages|rss|search))?/?$")
    private val TOPIC_RE = Regex("^/g/([\\w.\\-]+)/topic/(?:[^/]+/)?(\\d+)/?$")
    private val MESSAGE_RE = Regex("^/g/([\\w.\\-]+)/message/(\\d+)/?$")
    fun isGroupsIo(url: String): Boolean { val h = host(url); return h == "groups.io" || h.endsWith(".groups.io") }
    fun groupFeedUrl(url: String): String? {
        if (!isGroupsIo(url)) return null
        val m = GROUP_RE.find(path(url)) ?: return null
        return "https://${host(url)}/g/${m.groupValues[1]}/rss"
    }
    fun isThreadUrl(url: String) = isGroupsIo(url) && (TOPIC_RE.containsMatchIn(path(url)) || MESSAGE_RE.containsMatchIn(path(url)))
    fun canonicalTopicUrl(url: String): String? {
        if (!isGroupsIo(url)) return null
        val m = TOPIC_RE.find(path(url)) ?: return null
        return "https://${host(url)}/g/${m.groupValues[1]}/topic/${m.groupValues[2]}"
    }
    // A message page links to its topic; find that link in the fetched page.
    fun topicUrlFromMessagePage(html: String, pageUrl: String): String? {
        val doc = Jsoup.parse(html, pageUrl)
        for (a in doc.select("a[href*=\"/topic/\"]")) {
            val abs = a.attr("abs:href"); canonicalTopicUrl(abs)?.let { return it }
        }
        return null
    }
    data class TopicPage(val title: String, val messages: List<Pair<String, String>>, val nextUrl: String?)
    // author to body-html per .expanded-message block; next page link if any.
    fun parseTopicPage(html: String, pageUrl: String): TopicPage {
        val doc = Jsoup.parse(html, pageUrl)
        var title = doc.select("h1").first()?.text()?.trim() ?: ""
        if (title.isEmpty()) title = doc.title().split(" | ").first().trim()
        val messages = mutableListOf<Pair<String, String>>()
        for (block in doc.select(".expanded-message")) {
            val bodyNode = block.select(".user-content").first() ?: continue
            val author = block.select(".user-chip-name").first()?.text()?.trim()?.ifEmpty { "Unknown author" } ?: "Unknown author"
            messages += author to bodyNode.html()
        }
        var next: String? = null
        for (a in doc.select("a[href]")) if (a.text().trim().lowercase() == "next page") { next = a.attr("abs:href"); break }
        return TopicPage(title, messages, next)
    }
    fun renderDocument(title: String, pages: List<TopicPage>): String {
        val sb = StringBuilder("<h1>").append(esc(title.ifEmpty { "Groups.io topic" })).append("</h1>")
        var n = 0
        for (p in pages) for ((author, body) in p.messages) {
            n++; sb.append("<h2>#").append(n).append(" Message by ").append(esc(author)).append("</h2>").append(body)
        }
        return if (n == 0) "" else sb.toString()
    }
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun host(url: String) = try { java.net.URI(url.trim()).host?.lowercase() ?: "" } catch (_: Exception) { "" }
    private fun path(url: String) = try { java.net.URI(url.trim()).path ?: "" } catch (_: Exception) { "" }
}

// ---- network clients (own OkHttp, like DiscoverySearch) ----

object SoundCloudApi {
    private val http = OkHttpClient()
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    @Volatile private var clientId = ""
    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code} for $url"); r.body?.string() ?: "" }
    }
    suspend fun fetchClientId(force: Boolean = false): String {
        if (clientId.isNotEmpty() && !force) return clientId
        val html = get("https://soundcloud.com/")
        val assets = SoundCloud.assetUrls(html)
        // The app bundle loads last; cap the fan-out at the last few assets.
        for (js in assets.takeLast(4)) { val id = SoundCloud.clientIdFromAssets(html, listOf(try { get(js) } catch (_: Exception) { "" }))
            if (id.isNotEmpty()) { clientId = id; return id } }
        return ""
    }
    private suspend fun apiGet(pathAndQuery: String): String {
        val cid = fetchClientId(); if (cid.isEmpty()) throw java.io.IOException("SoundCloud client id not found")
        val sep = if ("?" in pathAndQuery) "&" else "?"
        try { return get("https://api-v2.soundcloud.com$pathAndQuery${sep}client_id=$cid") }
        catch (e: Exception) { // A rotated client id answers 401/403: refetch once.
            if ("401" in (e.message ?: "") || "403" in (e.message ?: "")) { val cid2 = fetchClientId(force = true)
                if (cid2.isNotEmpty()) return get("https://api-v2.soundcloud.com$pathAndQuery${sep}client_id=$cid2") }
            throw e }
    }
    suspend fun searchFeeds(query: String, limit: Int = 20): List<SearchHit> {
        val q = URLEncoder.encode(query, "UTF-8"); val per = maxOf(3, limit / 2)
        val out = mutableListOf<SearchHit>()
        try { out += SoundCloud.parseUserSearch(apiGet("/search/users?q=$q&limit=$per")) } catch (_: Exception) {}
        try { out += SoundCloud.parsePlaylistSearch(apiGet("/search/playlists?q=$q&limit=$per")) } catch (_: Exception) {}
        return out.distinctBy { it.feedUrl }.take(limit)
    }
    suspend fun fetchListing(url: String, maxItems: Int = 60): Pair<String, List<ListingItem>> {
        val resolved = JSONObject(apiGet("/resolve?url=${URLEncoder.encode(url, "UTF-8")}"))
        val kind = SoundCloud.listingKind(url)
        return when (kind) {
            "user" -> { val id = resolved.optLong("id"); val name = resolved.optString("username")
                name to SoundCloud.parseTracks(apiGet("/users/$id/tracks?limit=$maxItems&linked_partitioning=1"), name) }
            "playlist" -> { val name = resolved.optString("title")
                name to SoundCloud.parsePlaylistTracks(resolved.toString(), resolved.optJSONObject("user")?.optString("username") ?: "") }
            else -> "" to emptyList()
        }
    }
}

object MixcloudApi {
    private val http = OkHttpClient()
    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("User-Agent", "BlindRSS-Android/1.1").build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code} for $url"); r.body?.string() ?: "" }
    }
    suspend fun searchFeeds(query: String, limit: Int = 20): List<SearchHit> =
        Mixcloud.parseUserSearch(get("https://api.mixcloud.com/search/?q=${URLEncoder.encode(query, "UTF-8")}&type=user&limit=$limit"))
    suspend fun searchMedia(query: String, limit: Int = 30): List<VideoResult> =
        Mixcloud.parseMediaSearch(get("https://api.mixcloud.com/search/?q=${URLEncoder.encode(query, "UTF-8")}&type=cloudcast&limit=$limit"))
    suspend fun fetchListing(url: String, maxItems: Int = 60): Pair<String, List<ListingItem>> {
        val key = Mixcloud.keyFromUrl(url); if (key.isEmpty()) return "" to emptyList()
        val title = Mixcloud.parseName(get("https://api.mixcloud.com$key"))
        val items = Mixcloud.parseListing(get("https://api.mixcloud.com${key}cloudcasts/?limit=${minOf(100, maxItems)}"), title)
        return title to items
    }
}
