package org.blindrss.android.providers
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

data class RemoteFeed(val remoteId: String, val title: String, val url: String, val category: String)
data class RemoteArticle(val remoteId: String, val feedRemoteId: String, val title: String, val url: String, val content: String, val date: Long, val author: String, val isRead: Boolean, val isFavorite: Boolean, val mediaUrl: String? = null)

interface SyncProvider {
    val typeName: String
    suspend fun testLogin(): String
    suspend fun fetchFeeds(): List<RemoteFeed>
    suspend fun fetchArticles(): List<RemoteArticle>
    suspend fun setRead(remoteId: String, read: Boolean)
    suspend fun setFavorite(remoteId: String, fav: Boolean)
    // Returns null when the service cannot subscribe (the app then keeps the feed local).
    suspend fun addFeed(url: String, category: String): RemoteFeed?
    suspend fun removeFeed(remoteId: String, category: String)
    // Rename and move a subscription on the server. Only changed values are sent.
    suspend fun updateFeed(remoteId: String, oldTitle: String, title: String, oldCategory: String, category: String) { throw IOException("$typeName cannot rename or move feeds. Change them on your server.") }
    suspend fun markFeedRead(remoteId: String)
    suspend fun markAllRead()
    // Every unread item id on the server, when the API lists them cheaply. Local copies missing from it are marked read.
    suspend fun unreadIds(): Set<String>? = null
    // Incremental-sync position to store after a successful sync (Miniflux only).
    val nextCursor: Long? get() = null
    // Ask the server to fetch its feeds now (manual refresh). No-op where unsupported.
    suspend fun refreshServer() {}
    // Send an article to the read-later services configured on the server (Miniflux integrations).
    suspend fun saveToIntegrations(remoteId: String) { throw IOException("$typeName has no save integrations") }
}

abstract class BaseHttp {
    protected val client = OkHttpClient()
    protected fun req(url: String, auth: Pair<String, String>? = null, method: String = "GET", body: RequestBody? = null, form: Map<String, String>? = null): String {
        val b = Request.Builder().url(url)
        auth?.let { b.header(it.first, it.second) }
        val rb = when {
            form != null -> FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
            body != null -> body
            method == "POST" || method == "PUT" -> "".toRequestBody("application/json".toMediaType())
            else -> null
        }
        when (method) { "POST" -> b.post(rb!!); "PUT" -> b.put(rb!!); "DELETE" -> b.delete(rb); else -> b.get() }
        client.newCall(b.build()).execute().use { r -> val t = r.body?.string() ?: ""; if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${t.take(200)}"); return t }
    }
    protected fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    protected fun jsonPost(url: String, auth: Pair<String, String>?, json: String) = req(url, auth, "POST", json.toRequestBody("application/json".toMediaType()))
}

// Google Reader API compatible — FreshRSS, BazQux, The Old Reader share this wire protocol.
open class GReaderProvider(val base: String, private val user: String, private val pass: String, override val typeName: String) : BaseHttp(), SyncProvider {
    @Volatile protected var token: String? = null
    @Volatile protected var editToken: String? = null
    protected open fun login() {
        if (typeName == "The Old Reader" || typeName == "BazQux" || user.contains("@") || pass.isNotEmpty()) {
            val host = base.substringBefore("/reader")
            val t = req("$host/accounts/ClientLogin", null, "POST", form = mapOf("Email" to user, "Passwd" to pass, "accountType" to "HOSTED_OR_GOOGLE", "service" to "reader"))
            token = t.lines().firstOrNull { it.startsWith("Auth=") }?.removePrefix("Auth=") ?: throw IOException("Login failed: no Auth token")
        }
    }
    protected fun auth(): Pair<String, String> { if (token == null) login(); return "Authorization" to "GoogleLogin auth=$token" }
    protected open fun authHeader(): Pair<String, String> = auth()
    private fun editTok(): String { editToken?.let { return it }; val t = req("$base/token", authHeader()); editToken = t.trim(); return editToken!! }
    override suspend fun testLogin(): String { login(); val u = req("$base/user-info", authHeader()); return "Logged in: ${JSONObject(u).optString("userName", user)}" }
    override suspend fun fetchFeeds(): List<RemoteFeed> = parseSubscriptions(req("$base/subscription/list?output=json", authHeader()))
    private fun streamIds(stream: String, n: Int): List<String> {
        val refs = JSONObject(req("$base/stream/items/ids?s=${enc(stream)}&n=$n&output=json", authHeader())).optJSONArray("itemRefs") ?: JSONArray()
        return (0 until refs.length()).map { refs.getJSONObject(it).getString("id") }
    }
    // Newest 200 items plus up to 200 starred ones, so Favorites stays complete.
    override suspend fun fetchArticles(): List<RemoteArticle> {
        val ids = (streamIds("user/-/state/com.google/reading-list", 200) + streamIds("user/-/state/com.google/starred", 200)).distinct()
        return ids.chunked(100).flatMap { chunk ->
            val body = (chunk.joinToString("&") { "i=${enc(it)}" } + "&output=json").toRequestBody("application/x-www-form-urlencoded".toMediaType())
            parseItems(req("$base/stream/items/contents", authHeader(), "POST", body)) }
    }
    override suspend fun setRead(remoteId: String, read: Boolean) {
        req("$base/edit-tag", authHeader(), "POST", form = mapOf("i" to remoteId, (if (read) "a" else "r") to "user/-/state/com.google/read", "T" to editTok(), "output" to "json"))
    }
    override suspend fun setFavorite(remoteId: String, fav: Boolean) {
        req("$base/edit-tag", authHeader(), "POST", form = mapOf("i" to remoteId, (if (fav) "a" else "r") to "user/-/state/com.google/starred", "T" to editTok(), "output" to "json"))
    }
    override suspend fun addFeed(url: String, category: String): RemoteFeed? {
        val form = mutableMapOf("s" to "feed/$url", "ac" to "subscribe", "T" to editTok(), "output" to "json")
        if (category.isNotBlank() && category != "Uncategorized") form["a"] = "user/-/label/$category"
        req("$base/subscription/edit", authHeader(), "POST", form = form)
        return fetchFeeds().let { all -> all.firstOrNull { it.remoteId == "feed/$url" } ?: all.firstOrNull { it.url == url } }
    }
    override suspend fun removeFeed(remoteId: String, category: String) { req("$base/subscription/edit", authHeader(), "POST", form = mapOf("s" to remoteId, "ac" to "unsubscribe", "T" to editTok(), "output" to "json")) }
    override suspend fun updateFeed(remoteId: String, oldTitle: String, title: String, oldCategory: String, category: String) {
        req("$base/subscription/edit", authHeader(), "POST", form = editForm(remoteId, oldTitle, title, oldCategory, category) + mapOf("T" to editTok(), "output" to "json"))
    }
    override suspend fun markFeedRead(remoteId: String) { req("$base/mark-all-as-read", authHeader(), "POST", form = mapOf("s" to remoteId, "T" to editTok(), "output" to "json")) }
    override suspend fun markAllRead() = markFeedRead("user/-/state/com.google/reading-list")
    companion object {
        // Users paste the FreshRSS API root (…/api/greader.php); the reader endpoints live under /reader/api/0.
        fun freshRssBase(url: String): String { val u = url.trim().trimEnd('/'); return if (u.endsWith("/reader/api/0")) u else "$u/reader/api/0" }
        // subscription/edit: t renames; a/r add and remove folder labels. Uncategorized means no label.
        fun editForm(remoteId: String, oldTitle: String, title: String, oldCategory: String, category: String): Map<String, String> = buildMap {
            put("s", remoteId); put("ac", "edit")
            if (title != oldTitle) put("t", title)
            if (category != oldCategory) {
                if (oldCategory != "Uncategorized") put("r", "user/-/label/$oldCategory")
                if (category != "Uncategorized") put("a", "user/-/label/$category")
            }
        }
        fun parseSubscriptions(raw: String): List<RemoteFeed> {
            val arr = JSONObject(raw).optJSONArray("subscriptions") ?: JSONArray()
            return (0 until arr.length()).map { val o = arr.getJSONObject(it)
                val cat = o.optJSONArray("categories")?.let { c -> if (c.length() > 0) c.getJSONObject(0).optString("label", "Uncategorized") else null } ?: "Uncategorized"
                RemoteFeed(o.getString("id"), o.optString("title"), o.optString("url"), cat) }
        }
        fun parseItems(raw: String): List<RemoteArticle> {
            val items = JSONObject(raw).optJSONArray("items") ?: JSONArray()
            return (0 until items.length()).map { val o = items.getJSONObject(it)
                val cats = o.optJSONArray("categories")?.let { c -> (0 until c.length()).map { c.getString(it) } } ?: emptyList()
                // Full content wins over summary; some servers only fill one of them.
                val html = listOf("content", "summary").map { k -> o.optJSONObject(k)?.optString("content").orEmpty() }.firstOrNull { it.isNotBlank() } ?: ""
                val link = o.optJSONArray("alternate")?.let { a -> if (a.length() > 0) a.getJSONObject(0).optString("href") else "" } ?: ""
                val media = o.optJSONArray("enclosure")?.let { e -> if (e.length() > 0) e.getJSONObject(0).optString("href").ifEmpty { null } else null }
                RemoteArticle(o.getString("id"), o.optJSONObject("origin")?.optString("streamId") ?: "", o.optString("title"), link, html, o.optLong("published", 0) * 1000, o.optString("author"),
                    cats.any { it.endsWith("state/com.google/read") }, cats.any { it.endsWith("state/com.google/starred") }, media) }
        }
    }
}

class FreshRssProvider(base: String, user: String, pass: String) : GReaderProvider(freshRssBase(base), user, pass, "FreshRSS")
class BazQuxProvider(user: String, pass: String) : GReaderProvider("https://www.bazqux.com/reader/api/0", user, pass, "BazQux")
class TheOldReaderProvider(user: String, pass: String) : GReaderProvider("https://theoldreader.com/reader/api/0", user, pass, "The Old Reader")

class InoreaderProvider(private var accessToken: String, private val appId: String, private val appKey: String, val refreshToken: String, private val onTokens: suspend (String, String) -> Unit = { _, _ -> }) : GReaderProvider("https://www.inoreader.com/reader/api/0", "", "", "Inoreader") {
    override fun authHeader(): Pair<String, String> = "Authorization" to "Bearer $accessToken"
    companion object {
        const val AUTH_URL = "https://www.inoreader.com/oauth2/auth"
        const val TOKEN_URL = "https://www.inoreader.com/oauth2/token"
        const val REDIRECT = "blindrss://oauth"
        fun authorizationUrl(appId: String, redirect: String = REDIRECT) = "$AUTH_URL?response_type=code&client_id=${URLEncoder.encode(appId, "UTF-8")}&redirect_uri=${URLEncoder.encode(redirect, "UTF-8")}&scope=read/write&state=blindrss"
        fun exchangeCode(code: String, appId: String, appKey: String): Pair<String, String> {
            val http = BaseHttpPub()
            val t = http.postForm(TOKEN_URL, mapOf("client_id" to appId, "client_secret" to appKey, "grant_type" to "authorization_code", "code" to code, "redirect_uri" to REDIRECT))
            val j = JSONObject(t); return j.getString("access_token") to j.optString("refresh_token", "")
        }
        fun refreshAccess(refreshToken: String, appId: String, appKey: String): Pair<String, String> {
            val http = BaseHttpPub()
            val t = http.postForm(TOKEN_URL, mapOf("client_id" to appId, "client_secret" to appKey, "grant_type" to "refresh_token", "refresh_token" to refreshToken))
            val j = JSONObject(t); return j.getString("access_token") to j.optString("refresh_token", "")
        }
    }
    private suspend fun <T> authed(block: suspend () -> T): T {
        try { return block() } catch (e: IOException) {
            if (refreshToken.isEmpty() || !e.message.orEmpty().contains("401")) throw e
            val (a, r) = refreshAccess(refreshToken, appId, appKey); accessToken = a; onTokens(a, r); return block()
        }
    }
    override suspend fun testLogin(): String = authed { val u = req("$base/user-info?output=json", authHeader()); "Inoreader: ${JSONObject(u).optString("userName", "ok")}" }
    override suspend fun fetchFeeds(): List<RemoteFeed> = authed { super.fetchFeeds() }
    override suspend fun fetchArticles(): List<RemoteArticle> = authed { super.fetchArticles() }
    override suspend fun setRead(remoteId: String, read: Boolean) = authed { super.setRead(remoteId, read) }
    override suspend fun setFavorite(remoteId: String, fav: Boolean) = authed { super.setFavorite(remoteId, fav) }
    override suspend fun addFeed(url: String, category: String): RemoteFeed? = authed { super.addFeed(url, category) }
    override suspend fun removeFeed(remoteId: String, category: String) = authed { super.removeFeed(remoteId, category) }
    override suspend fun updateFeed(remoteId: String, oldTitle: String, title: String, oldCategory: String, category: String) = authed { super.updateFeed(remoteId, oldTitle, title, oldCategory, category) }
    override suspend fun markFeedRead(remoteId: String) = authed { super.markFeedRead(remoteId) }
    override suspend fun markAllRead() = authed { super.markAllRead() }
}

class BaseHttpPub : BaseHttp() {
    fun postForm(url: String, form: Map<String, String>): String = req(url, null, "POST", form = form)
}

// cursor: changed_at (epoch seconds) of the last sync; 0 = first sync. After a sync, nextCursor holds the new value.
class MinifluxProvider(private val server: String, private val apiKey: String, private val cursor: Long = 0) : BaseHttp(), SyncProvider {
    override val typeName = "Miniflux"
    private val root = server.removeSuffix("/v1").trimEnd('/')
    private fun auth() = "X-Auth-Token" to apiKey
    override var nextCursor: Long? = null; private set
    override suspend fun testLogin(): String { val u = req("$root/v1/me", auth()); return "Miniflux user: ${JSONObject(u).optString("username", "ok")}" }
    override suspend fun fetchFeeds(): List<RemoteFeed> {
        val arr = JSONArray(req("$root/v1/feeds", auth()))
        return (0 until arr.length()).map { val o = arr.getJSONObject(it); RemoteFeed(o.getLong("id").toString(), o.optString("title"), o.optString("feed_url"), o.optJSONObject("category")?.optString("title", "Uncategorized") ?: "Uncategorized") }
    }
    // After the first sync, only entries changed since the last one (60 s overlap): new items plus read and star
    // changes made anywhere, at any age. First sync, or a server that ignores changed_after: newest 200, 500 unread, 200 starred.
    override suspend fun fetchArticles(): List<RemoteArticle> {
        if (cursor > 0) changedSince(cursor - 60)?.let { return it }
        val got = listOf("limit=200", "status=unread&limit=500", "starred=true&limit=200").map { page("$it&order=published_at&direction=desc") }
        nextCursor = got.maxOfOrNull { it.newest }?.takeIf { it > 0 }
        return got.flatMap { it.items }.distinctBy { it.remoteId }
    }
    private fun changedSince(since: Long): List<RemoteArticle>? {
        val out = mutableListOf<RemoteArticle>(); var newest = since; var offset = 0
        while (true) {
            val pg = page("changed_after=$since&order=id&direction=asc&limit=100&offset=$offset")
            if (offset == 0 && pg.oldest in 1 until since) return null // server ignored changed_after
            out += pg.items; newest = maxOf(newest, pg.newest); offset += pg.items.size
            if (pg.items.isEmpty() || offset >= pg.total) break
            if (offset >= 3000) return null // too many changes (bulk edit on the web): resync from windows instead
        }
        nextCursor = newest; return out
    }
    // newest and oldest are changed_at in epoch seconds (0 when absent).
    private class Page(val items: List<RemoteArticle>, val newest: Long, val total: Int, val oldest: Long)
    private fun page(query: String): Page {
        val j = JSONObject(req("$root/v1/entries?$query", auth())); val arr = j.optJSONArray("entries") ?: JSONArray()
        val items = parseEntries(arr.toString())
        val changed = (0 until arr.length()).map { isoSeconds(arr.getJSONObject(it).optString("changed_at")) }.filter { it > 0 }
        return Page(items, changed.maxOrNull() ?: 0, j.optInt("total", items.size), changed.minOrNull() ?: 0)
    }
    override suspend fun refreshServer() { req("$root/v1/feeds/refresh", auth(), "PUT") }
    override suspend fun saveToIntegrations(remoteId: String) { req("$root/v1/entries/$remoteId/save", auth(), "POST") }
    companion object {
        fun isoSeconds(s: String): Long = try { java.time.Instant.parse(s).epochSecond } catch (_: Exception) { 0 }
        fun parseEntries(raw: String): List<RemoteArticle> { val arr = JSONArray(raw)
            return (0 until arr.length()).map { val o = arr.getJSONObject(it)
                val encArr = o.optJSONArray("enclosures"); val media = if (encArr != null && encArr.length() > 0) encArr.getJSONObject(0).optString("url").ifEmpty { null } else null
                val published = isoSeconds(o.optString("published_at")).takeIf { it > 0 }?.times(1000) ?: System.currentTimeMillis()
                RemoteArticle(o.getLong("id").toString(), o.getLong("feed_id").toString(), o.optString("title"), o.optString("url"), o.optString("content"), published, o.optString("author"), o.optString("status") == "read", o.optBoolean("starred"), media) } }
    }
    override suspend fun setRead(remoteId: String, read: Boolean) { req("$root/v1/entries", auth(), "PUT", JSONObject().put("entry_ids", JSONArray().put(remoteId.toLong())).put("status", if (read) "read" else "unread").toString().toRequestBody("application/json".toMediaType())) }
    // The bookmark endpoint toggles, so only call it when the server state differs.
    override suspend fun setFavorite(remoteId: String, fav: Boolean) {
        if (JSONObject(req("$root/v1/entries/$remoteId", auth())).optBoolean("starred") != fav) req("$root/v1/entries/$remoteId/bookmark", auth(), "PUT")
    }
    // Existing category with that title (Miniflux treats titles case-insensitively), else a new one.
    private fun categoryId(title: String): Long {
        val cats = JSONArray(req("$root/v1/categories", auth()))
        for (i in 0 until cats.length()) cats.getJSONObject(i).let { if (it.optString("title").equals(title, ignoreCase = true)) return it.getLong("id") }
        return JSONObject(jsonPost("$root/v1/categories", auth(), JSONObject().put("title", title).toString())).getLong("id")
    }
    override suspend fun addFeed(url: String, category: String): RemoteFeed? {
        val r = JSONObject(jsonPost("$root/v1/feeds", auth(), JSONObject().put("feed_url", url).put("category_id", categoryId(category)).toString()))
        return RemoteFeed(r.getLong("feed_id").toString(), url, url, category)
    }
    override suspend fun removeFeed(remoteId: String, category: String) { req("$root/v1/feeds/$remoteId", auth(), "DELETE") }
    override suspend fun updateFeed(remoteId: String, oldTitle: String, title: String, oldCategory: String, category: String) {
        val body = JSONObject(); if (title != oldTitle) body.put("title", title); if (category != oldCategory) body.put("category_id", categoryId(category))
        if (body.length() > 0) req("$root/v1/feeds/$remoteId", auth(), "PUT", body.toString().toRequestBody("application/json".toMediaType()))
    }
    override suspend fun markFeedRead(remoteId: String) { req("$root/v1/feeds/$remoteId/mark-all-as-read", auth(), "PUT") }
    override suspend fun markAllRead() { val me = JSONObject(req("$root/v1/me", auth())); req("$root/v1/users/${me.getLong("id")}/mark-all-as-read", auth(), "PUT") }
    suspend fun fetchFullContent(remoteId: String): String { val j = JSONObject(req("$root/v1/entries/$remoteId/fetch-content", auth())); return j.optString("content") }
}

class FeverProvider(private val server: String, private val apiKeyMd5: String) : BaseHttp(), SyncProvider {
    override val typeName = "Fever"
    private val endpoint = endpointFor(server)
    private fun post(params: Map<String, String>): JSONObject {
        val j = JSONObject(req(endpoint, null, "POST", form = mapOf("api_key" to apiKeyMd5) + params))
        if (j.optInt("auth", 1) != 1) throw IOException("Fever auth failed"); return j
    }
    private fun now() = (System.currentTimeMillis() / 1000).toString()
    override suspend fun testLogin(): String { post(emptyMap()); return "Fever connected" }
    override suspend fun fetchFeeds(): List<RemoteFeed> {
        val arr = post(mapOf("feeds" to "1")).optJSONArray("feeds") ?: JSONArray()
        val catByFeed = groupMap(post(mapOf("groups" to "1")))
        return (0 until arr.length()).map { val o = arr.getJSONObject(it); val id = o.getLong("id").toString(); RemoteFeed(id, o.optString("title"), o.optString("url"), catByFeed[id] ?: "Uncategorized") }
    }
    private fun idSet(key: String) = post(mapOf(key to "1")).optString(key, "").split(",").filter { it.isNotBlank() }.toSet()
    override suspend fun unreadIds(): Set<String> = idSet("unread_item_ids")
    override suspend fun fetchArticles(): List<RemoteArticle> {
        val unread = unreadIds(); val saved = idSet("saved_item_ids")
        val out = mutableListOf<RemoteArticle>()
        for (chunk in chunkIds((unread + saved).toList(), 50)) {
            val arr = post(mapOf("items" to "1", "with_ids" to chunk.joinToString(","))).optJSONArray("items") ?: continue
            for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); val id = o.getLong("id").toString()
                out += RemoteArticle(id, o.getLong("feed_id").toString(), o.optString("title"), o.optString("url"), o.optString("html"), o.optLong("created_on_time") * 1000, o.optString("author"), id !in unread, id in saved) }
        }
        return out
    }
    override suspend fun setRead(remoteId: String, read: Boolean) { post(mapOf("mark" to "item", "as" to if (read) "read" else "unread", "id" to remoteId)) }
    override suspend fun setFavorite(remoteId: String, fav: Boolean) { post(mapOf("mark" to "item", "as" to if (fav) "saved" else "unsaved", "id" to remoteId)) }
    override suspend fun addFeed(url: String, category: String): RemoteFeed? = null // Fever API has no subscribe endpoint
    override suspend fun removeFeed(remoteId: String, category: String) { throw IOException("Fever cannot unsubscribe. Remove the feed on your server.") }
    override suspend fun markFeedRead(remoteId: String) { post(mapOf("mark" to "feed", "as" to "read", "id" to remoteId, "before" to now())) }
    // Group 0 is Fever's "all items" group.
    override suspend fun markAllRead() { post(mapOf("mark" to "group", "as" to "read", "id" to "0", "before" to now())) }
    companion object {
        fun md5Hex(s: String): String { val d = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray()); return d.joinToString("") { "%02x".format(it) } }
        fun chunkIds(ids: List<String>, size: Int = 50): List<List<String>> = ids.chunked(size)
        // FreshRSS: …/api/fever.php?api. Other servers: <root>/?api.
        fun endpointFor(server: String): String { val s = server.trim().trimEnd('/'); return if (s.endsWith(".php")) "$s?api" else "$s/?api" }
        // groups + feeds_groups ("feed_ids": "1,2,3") to feed id -> group title.
        fun groupMap(j: JSONObject): Map<String, String> {
            val groups = j.optJSONArray("groups") ?: JSONArray(); val links = j.optJSONArray("feeds_groups") ?: JSONArray()
            val title = (0 until groups.length()).associate { groups.getJSONObject(it).let { g -> g.optLong("id").toString() to g.optString("title") } }
            val out = mutableMapOf<String, String>()
            for (i in 0 until links.length()) { val l = links.getJSONObject(i); val t = title[l.optLong("group_id").toString()] ?: continue
                l.optString("feed_ids").split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { out.putIfAbsent(it, t) } }
            return out
        }
    }
}

// Feedbin REST API v2, HTTP Basic auth. Feeds are keyed by feed_id (what entries carry); subscription ids only matter for unsubscribe.
class FeedbinProvider(private val user: String, private val pass: String) : BaseHttp(), SyncProvider {
    override val typeName = "Feedbin"
    private val root = "https://api.feedbin.com/v2"
    private fun auth() = "Authorization" to Credentials.basic(user, pass)
    private fun json(method: String, path: String, body: JSONObject) = req("$root$path", auth(), method, body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
    private fun ids(path: String): List<Long> = JSONArray(req("$root$path", auth())).let { a -> (0 until a.length()).map { a.getLong(it) } }
    // Feedbin caps bulk unread/starred edits at 1000 ids per request.
    private fun bulk(method: String, path: String, key: String, ids: List<Long>) = ids.chunked(1000).forEach { json(method, path, JSONObject().put(key, JSONArray(it))) }
    override suspend fun testLogin(): String { req("$root/authentication.json", auth()); return "Feedbin: $user" }
    override suspend fun fetchFeeds(): List<RemoteFeed> {
        val tags = JSONArray(req("$root/taggings.json", auth())); val cat = mutableMapOf<String, String>()
        for (i in 0 until tags.length()) tags.getJSONObject(i).let { cat.putIfAbsent(it.optLong("feed_id").toString(), it.str("name")) }
        val subs = JSONArray(req("$root/subscriptions.json", auth()))
        return (0 until subs.length()).map { val o = subs.getJSONObject(it); val fid = o.getLong("feed_id").toString(); RemoteFeed(fid, o.str("title"), o.str("feed_url"), cat[fid] ?: "Uncategorized") }
    }
    override suspend fun unreadIds(): Set<String> = ids("/unread_entries.json").map { it.toString() }.toSet()
    // Newest 100, plus up to 500 unread and 200 starred fetched by id, 100 per request.
    override suspend fun fetchArticles(): List<RemoteArticle> {
        val unread = ids("/unread_entries.json").toSet(); val starred = ids("/starred_entries.json").toSet()
        val recent = parseEntries(req("$root/entries.json?per_page=100&include_enclosure=true", auth()), unread, starred)
        val have = recent.map { it.remoteId }.toSet()
        val more = (unread.sortedDescending().take(500) + starred.sortedDescending().take(200)).distinct().filter { it.toString() !in have }
            .chunked(100).flatMap { parseEntries(req("$root/entries.json?include_enclosure=true&ids=${it.joinToString(",")}", auth()), unread, starred) }
        return (recent + more).distinctBy { it.remoteId }
    }
    override suspend fun setRead(remoteId: String, read: Boolean) { bulk(if (read) "DELETE" else "POST", "/unread_entries.json", "unread_entries", listOf(remoteId.toLong())) }
    override suspend fun setFavorite(remoteId: String, fav: Boolean) { bulk(if (fav) "POST" else "DELETE", "/starred_entries.json", "starred_entries", listOf(remoteId.toLong())) }
    override suspend fun addFeed(url: String, category: String): RemoteFeed? {
        val o = JSONObject(json("POST", "/subscriptions.json", JSONObject().put("feed_url", url))); val fid = o.getLong("feed_id")
        if (category.isNotBlank() && category != "Uncategorized") json("POST", "/taggings.json", JSONObject().put("feed_id", fid).put("name", category))
        return RemoteFeed(fid.toString(), o.str("title"), o.str("feed_url").ifEmpty { url }, category)
    }
    private fun subscriptionId(feedId: String): Long? { val subs = JSONArray(req("$root/subscriptions.json", auth()))
        return (0 until subs.length()).map { subs.getJSONObject(it) }.firstOrNull { it.optLong("feed_id").toString() == feedId }?.getLong("id") }
    override suspend fun removeFeed(remoteId: String, category: String) {
        req("$root/subscriptions/${subscriptionId(remoteId) ?: return}.json", auth(), "DELETE")
    }
    // Title lives on the subscription; the folder is a tagging, so moving swaps the old tag for the new one.
    override suspend fun updateFeed(remoteId: String, oldTitle: String, title: String, oldCategory: String, category: String) {
        if (title != oldTitle) json("POST", "/subscriptions/${subscriptionId(remoteId) ?: throw IOException("Feedbin subscription not found")}/update.json", JSONObject().put("title", title))
        if (category == oldCategory) return
        val tags = JSONArray(req("$root/taggings.json", auth()))
        for (i in 0 until tags.length()) tags.getJSONObject(i).takeIf { it.optLong("feed_id").toString() == remoteId && it.str("name") == oldCategory }?.let { req("$root/taggings/${it.getLong("id")}.json", auth(), "DELETE") }
        if (category != "Uncategorized") json("POST", "/taggings.json", JSONObject().put("feed_id", remoteId.toLong()).put("name", category))
    }
    override suspend fun markFeedRead(remoteId: String) {
        val unread = ids("/unread_entries.json").toSet(); val inFeed = mutableListOf<Long>()
        for (page in 1..20) { val a = JSONArray(req("$root/feeds/$remoteId/entries.json?read=false&per_page=100&page=$page", auth())); if (a.length() == 0) break
            for (i in 0 until a.length()) a.getJSONObject(i).getLong("id").takeIf { it in unread }?.let { inFeed += it } }
        bulk("DELETE", "/unread_entries.json", "unread_entries", inFeed)
    }
    override suspend fun markAllRead() { bulk("DELETE", "/unread_entries.json", "unread_entries", ids("/unread_entries.json")) }
    companion object {
        fun parseEntries(raw: String, unread: Set<Long>, starred: Set<Long>): List<RemoteArticle> {
            val a = JSONArray(raw)
            return (0 until a.length()).map { val o = a.getJSONObject(it); val id = o.getLong("id")
                val date = try { java.time.Instant.parse(o.str("published")).toEpochMilli() } catch (_: Exception) { System.currentTimeMillis() }
                RemoteArticle(id.toString(), o.optLong("feed_id").toString(), o.str("title"), o.str("url"), o.str("content"), date, o.str("author"),
                    id !in unread, id in starred, o.optJSONObject("enclosure")?.str("enclosure_url")?.ifEmpty { null }) }
        }
    }
}
// org.json returns the text "null" for JSON null; Feedbin sends null titles and authors often.
private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)

class NewsBlurProvider(private val cookieOrToken: String) : BaseHttp(), SyncProvider {
    override val typeName = "NewsBlur"
    private val root = "https://www.newsblur.com"
    private fun auth() = "Cookie" to "newsblur_sessionid=$cookieOrToken"
    private fun postForm(path: String, form: Map<String, String>) = req("$root$path", auth(), "POST", form = form)
    override suspend fun testLogin(): String { val j = JSONObject(req("$root/social/profile", auth())); return "NewsBlur: ${j.optJSONObject("social_profile")?.optString("username") ?: "ok"}" }
    override suspend fun fetchFeeds(): List<RemoteFeed> {
        val raw = req("$root/reader/feeds?flat=true", auth()); val feeds = JSONObject(raw).optJSONObject("feeds") ?: JSONObject()
        val catMap = parseFolders(raw)
        return feeds.keys().asSequence().map { k -> val o = feeds.getJSONObject(k); RemoteFeed(k, o.optString("feed_title"), o.optString("feed_address"), catMap[k] ?: "Uncategorized") }.toList()
    }
    override suspend fun unreadIds(): Set<String> = parseUnreadHashes(req("$root/reader/unread_story_hashes", auth()))
    // Up to 500 unread stories (by hash, 100 per request) plus the first 5 pages of saved stories.
    override suspend fun fetchArticles(): List<RemoteArticle> {
        val unread = unreadIds().take(500).chunked(100).flatMap { c -> parseStories(req("$root/reader/river_stories?" + c.joinToString("&") { "h=${enc(it)}" }, auth())) }
        val starred = mutableListOf<RemoteArticle>()
        for (p in 1..5) { val page = parseStories(req("$root/reader/starred_stories?page=$p", auth())); if (page.isEmpty()) break; starred += page }
        return (unread + starred).distinctBy { it.remoteId }
    }
    override suspend fun setRead(remoteId: String, read: Boolean) { postForm(if (read) "/reader/mark_story_hashes_as_read" else "/reader/mark_story_hash_as_unread", mapOf("story_hash" to remoteId)) }
    override suspend fun setFavorite(remoteId: String, fav: Boolean) { postForm(if (fav) "/reader/star_story" else "/reader/unstar_story", mapOf("story_hash" to remoteId)) }
    override suspend fun addFeed(url: String, category: String): RemoteFeed? {
        val folder = if (category == "Uncategorized") "" else category
        val f = JSONObject(postForm("/reader/add_url", mapOf("url" to url, "folder" to folder))).optJSONObject("feed") ?: return fetchFeeds().firstOrNull { it.url == url }
        return RemoteFeed(f.optLong("id").toString(), f.optString("feed_title"), f.optString("feed_address", url), category)
    }
    override suspend fun removeFeed(remoteId: String, category: String) { postForm("/reader/delete_feed", mapOf("feed_id" to remoteId, "in_folder" to if (category == "Uncategorized") "" else category)) }
    // NewsBlur answers HTTP 200 with code -1 on failure. Moving into a folder that does not exist drops the feed
    // from every folder, so the folder is created first (add_folder ignores names that already exist).
    override suspend fun updateFeed(remoteId: String, oldTitle: String, title: String, oldCategory: String, category: String) {
        fun folder(c: String) = if (c == "Uncategorized") "" else c
        fun check(raw: String) { val j = JSONObject(raw); if (j.optInt("code", 1) < 0) throw IOException(j.optString("message").ifEmpty { "NewsBlur refused the change" }) }
        if (title != oldTitle) check(postForm("/reader/rename_feed", mapOf("feed_id" to remoteId, "feed_title" to title)))
        if (category == oldCategory) return
        if (folder(category).isNotEmpty()) check(postForm("/reader/add_folder", mapOf("folder" to folder(category), "parent_folder" to "")))
        check(postForm("/reader/move_feed_to_folder", mapOf("feed_id" to remoteId, "in_folder" to folder(oldCategory), "to_folder" to folder(category))))
    }
    override suspend fun markFeedRead(remoteId: String) { postForm("/reader/mark_feed_as_read", mapOf("feed_id" to remoteId)) }
    override suspend fun markAllRead() { postForm("/reader/mark_all_as_read", mapOf("days" to "0")) }
    companion object {
        // Returns the newsblur_sessionid cookie for a username and password.
        fun login(user: String, pass: String): String {
            val body = FormBody.Builder().add("username", user).add("password", pass).build()
            OkHttpClient().newCall(Request.Builder().url("https://www.newsblur.com/api/login").post(body).build()).execute().use { r ->
                val ok = JSONObject(r.body?.string() ?: "{}").optBoolean("authenticated")
                val sid = r.headers("Set-Cookie").firstNotNullOfOrNull { Regex("newsblur_sessionid=([^;]+)").find(it)?.groupValues?.get(1) }
                if (!ok || sid == null) throw IOException("Wrong username or password")
                return sid
            }
        }
        // flat_folders looks like [{"Tech":[12,34],"News":[56]}]; map feed id -> first folder.
        fun parseFolders(raw: String): Map<String, String> {
            val out = mutableMapOf<String, String>()
            val section = raw.substringAfter("\"flat_folders\"", raw)
            for (m in Regex("\"([^\"]+)\"\\s*:\\s*\\[([^\\]]*)\\]").findAll(section)) {
                val folder = m.groupValues[1]; if (folder == "flat_folders") continue
                for (id in Regex("\\d+").findAll(m.groupValues[2])) out.putIfAbsent(id.value, folder)
            }
            return out
        }
        // {"unread_feed_story_hashes": {"12": ["12:abc", ...]}} -> all hashes.
        fun parseUnreadHashes(raw: String): Set<String> {
            val m = JSONObject(raw).optJSONObject("unread_feed_story_hashes") ?: return emptySet()
            return m.keys().asSequence().flatMap { k -> val a = m.optJSONArray(k) ?: JSONArray(); (0 until a.length()).map { a.optString(it) }.asSequence() }.filter { it.isNotEmpty() }.toSet()
        }
        // Story ids are story hashes; every write endpoint takes the hash.
        fun parseStories(raw: String): List<RemoteArticle> {
            val stories = JSONObject(raw).optJSONArray("stories") ?: return emptyList()
            return (0 until stories.length()).map { val o = stories.getJSONObject(it)
                RemoteArticle(o.optString("story_hash"), o.optLong("story_feed_id").toString(), o.optString("story_title"), o.optString("story_permalink"), o.optString("story_content"),
                    o.optLong("story_timestamp") * 1000, o.optString("story_authors"), o.optInt("read_status") == 1, o.optBoolean("starred")) }
        }
    }
}
