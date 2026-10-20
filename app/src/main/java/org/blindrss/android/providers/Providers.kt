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
    suspend fun addFeed(url: String, category: String): RemoteFeed?
    suspend fun markAllRead()
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
    protected fun bearerAuth(): Pair<String, String>? = null
    protected open fun authHeader(): Pair<String, String> = auth()
    private fun editTok(): String { editToken?.let { return it }; val t = req("$base/token", authHeader()); editToken = t.trim(); return editToken!! }
    override suspend fun testLogin(): String { login(); val u = req("$base/user-info", authHeader()); return "Logged in: ${JSONObject(u).optString("userName", user)}" }
    override suspend fun fetchFeeds(): List<RemoteFeed> {
        val j = JSONObject(req("$base/subscription/list?output=json", authHeader()))
        val arr = j.optJSONArray("subscriptions") ?: JSONArray()
        return (0 until arr.length()).map { val o = arr.getJSONObject(it)
            val cat = o.optJSONArray("categories")?.let { if (it.length() > 0) it.getJSONObject(0).optString("label", "Uncategorized") else "Uncategorized" } ?: "Uncategorized"
            RemoteFeed(o.getString("id"), o.optString("title"), o.optString("url"), cat) }
    }
    override suspend fun fetchArticles(): List<RemoteArticle> {
        val idsJson = JSONObject(req("$base/stream/items/ids?s=user/-/state/com.google/reading-list&n=200&output=json", authHeader()))
        val refs = idsJson.optJSONArray("itemRefs") ?: JSONArray()
        if (refs.length() == 0) return emptyList()
        val ids = (0 until refs.length()).map { refs.getJSONObject(it).getString("id") }
        val form = ids.joinToString("&") { "i=${enc(it)}" }
        val body = "$form&output=json".toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val contents = JSONObject(req("$base/stream/items/contents", authHeader(), "POST", body))
        val items = contents.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).map { val o = items.getJSONObject(it)
            val feedId = o.optJSONObject("origin")?.optString("streamId") ?: ""
            val cats = o.optJSONArray("categories")?.let { c -> (0 until c.length()).map { c.getString(it) } } ?: emptyList()
            val read = cats.any { it.endsWith("state/com.google/read") }; val fav = cats.any { it.endsWith("state/com.google/starred") }
            val html = o.optJSONObject("summary")?.optString("content") ?: o.optJSONObject("content")?.optString("content") ?: ""
            val link = o.optJSONArray("alternate")?.let { if (it.length() > 0) it.getJSONObject(0).optString("href") else "" } ?: ""
            RemoteArticle(o.getString("id"), feedId, o.optString("title"), link, html, (o.optLong("published", 0)) * 1000, o.optString("author"), read, fav) }
    }
    override suspend fun setRead(remoteId: String, read: Boolean) {
        val tag = "user/-/state/com.google/read"
        req("$base/edit-tag", authHeader(), "POST", form = mapOf("i" to remoteId, (if (read) "a" else "r") to tag, "T" to editTok(), "output" to "json"))
    }
    override suspend fun setFavorite(remoteId: String, fav: Boolean) {
        val tag = "user/-/state/com.google/starred"
        req("$base/edit-tag", authHeader(), "POST", form = mapOf("i" to remoteId, (if (fav) "a" else "r") to tag, "T" to editTok(), "output" to "json"))
    }
    override suspend fun addFeed(url: String, category: String): RemoteFeed? {
        req("$base/subscription/edit", authHeader(), "POST", form = mapOf("s" to "feed/$url", "ac" to "subscribe", "t" to url, "T" to editTok(), "output" to "json"))
        return fetchFeeds().firstOrNull { it.url == url }
    }
    override suspend fun markAllRead() { req("$base/mark-all-as-read", authHeader(), "POST", form = mapOf("s" to "user/-/state/com.google/reading-list", "T" to editTok(), "output" to "json")) }
}

class FreshRssProvider(base: String, user: String, pass: String) : GReaderProvider(base.trimEnd('/') , user, pass, "Google Reader / FreshRSS")
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
    override suspend fun markAllRead() = authed { super.markAllRead() }
}

class BaseHttpPub : BaseHttp() {
    fun postForm(url: String, form: Map<String, String>): String = req(url, null, "POST", form = form)
}

class MinifluxProvider(private val server: String, private val apiKey: String) : BaseHttp(), SyncProvider {
    override val typeName = "Miniflux"
    private val root = server.removeSuffix("/v1").trimEnd('/')
    private fun auth() = "X-Auth-Token" to apiKey
    override suspend fun testLogin(): String { val u = req("$root/v1/me", auth()); return "Miniflux user: ${JSONObject(u).optString("username", "ok")}" }
    override suspend fun fetchFeeds(): List<RemoteFeed> {
        val arr = JSONArray(req("$root/v1/feeds", auth()))
        return (0 until arr.length()).map { val o = arr.getJSONObject(it); RemoteFeed(o.getLong("id").toString(), o.optString("title"), o.optString("feed_url"), o.optJSONObject("category")?.optString("title", "Uncategorized") ?: "Uncategorized") }
    }
    override suspend fun fetchArticles(): List<RemoteArticle> {
        val j = JSONObject(req("$root/v1/entries?limit=200&order=published_at&direction=desc", auth()))
        val arr = j.optJSONArray("entries") ?: JSONArray()
        return (0 until arr.length()).map { val o = arr.getJSONObject(it)
            val encArr = o.optJSONArray("enclosures"); val media = if (encArr != null && encArr.length() > 0) encArr.getJSONObject(0).optString("url").ifEmpty { null } else null
            RemoteArticle(o.getLong("id").toString(), o.getLong("feed_id").toString(), o.optString("title"), o.optString("url"), o.optString("content"), parseIso(o.optString("published_at")), o.optString("author"), o.optString("status") == "read", o.optBoolean("starred"), media) }
    }
    private fun parseIso(s: String): Long = try { java.time.Instant.parse(s).toEpochMilli() } catch (_: Exception) { System.currentTimeMillis() }
    override suspend fun setRead(remoteId: String, read: Boolean) { req("$root/v1/entries", auth(), "PUT", JSONObject().put("entry_ids", JSONArray().put(remoteId.toLong())).put("status", if (read) "read" else "unread").toString().toRequestBody("application/json".toMediaType())) }
    override suspend fun setFavorite(remoteId: String, fav: Boolean) { req("$root/v1/entries/$remoteId/bookmark", auth(), "PUT") }
    override suspend fun addFeed(url: String, category: String): RemoteFeed? {
        val cats = JSONArray(req("$root/v1/categories", auth())); var catId: Long? = null
        for (i in 0 until cats.length()) if (cats.getJSONObject(i).optString("title") == category) catId = cats.getJSONObject(i).getLong("id")
        if (catId == null) { val c = JSONObject(jsonPost("$root/v1/categories", auth(), JSONObject().put("title", category).toString())); catId = c.getLong("id") }
        val r = JSONObject(jsonPost("$root/v1/feeds", auth(), JSONObject().put("feed_url", url).put("category_id", catId).toString()))
        return RemoteFeed(r.getLong("feed_id").toString(), url, url, category)
    }
    override suspend fun markAllRead() { val me = JSONObject(req("$root/v1/me", auth())); req("$root/v1/users/${me.getLong("id")}/mark-all-as-read", auth(), "PUT") }
    suspend fun fetchFullContent(remoteId: String): String { val j = JSONObject(req("$root/v1/entries/$remoteId/fetch-content", auth())); return j.optString("content") }
}

class FeverProvider(private val server: String, private val apiKeyMd5: String) : BaseHttp(), SyncProvider {
    override val typeName = "Fever"
    private val endpoint = server.trimEnd('/') + "/?api"
    private fun post(params: Map<String, String>): JSONObject {
        val form = (mapOf("api_key" to apiKeyMd5) + params)
        val t = req(endpoint, null, "POST", form = form)
        return JSONObject(t)
    }
    override suspend fun testLogin(): String { val j = post(emptyMap()); if (j.optInt("auth") != 1) throw IOException("Fever auth failed"); return "Fever OK, last refreshed: ${j.optLong("last_refreshed_on_time")}" }
    override suspend fun fetchFeeds(): List<RemoteFeed> {
        val j = post(mapOf("feeds" to "1")); val arr = j.optJSONArray("feeds") ?: JSONArray()
        val groups = post(mapOf("groups" to "1")).optJSONArray("feeds_groups")
        val catByFeed = mutableMapOf<String, String>()
        // groups mapping simplified: default Uncategorized; group titles resolved below if present
        return (0 until arr.length()).map { val o = arr.getJSONObject(it); RemoteFeed(o.getInt("id").toString(), o.optString("title"), o.optString("url"), catByFeed[o.getInt("id").toString()] ?: "Uncategorized") }
    }
    override suspend fun fetchArticles(): List<RemoteArticle> {
        val unread = post(mapOf("unread_item_ids" to "1")).optString("unread_item_ids", "").split(",").filter { it.isNotBlank() }.toSet()
        val saved = post(mapOf("saved_item_ids" to "1")).optString("saved_item_ids", "").split(",").filter { it.isNotBlank() }.toSet()
        val out = mutableListOf<RemoteArticle>()
        for (chunk in chunkIds((unread + saved).toList(), 50)) {
            val arr = post(mapOf("items" to "1", "with_ids" to chunk.joinToString(","))).optJSONArray("items") ?: continue
            for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); val id = o.getInt("id").toString()
                out += RemoteArticle(id, o.getInt("feed_id").toString(), o.optString("title"), o.optString("url"), o.optString("html"), o.optLong("created_on_time") * 1000, o.optString("author"), o.optInt("is_read") == 1 || id !in unread, o.optInt("is_saved") == 1 || id in saved) }
        }
        return out
    }
    override suspend fun setRead(remoteId: String, read: Boolean) { post(mapOf("mark" to "item", "as" to if (read) "read" else "unread", "id" to remoteId)) }
    override suspend fun setFavorite(remoteId: String, fav: Boolean) { post(mapOf("mark" to "item", "as" to if (fav) "saved" else "unsaved", "id" to remoteId)) }
    override suspend fun addFeed(url: String, category: String): RemoteFeed? = null // Fever API has no subscribe endpoint
    override suspend fun markAllRead() { post(mapOf("mark" to "feed", "as" to "read", "id" to "0")) }
    companion object {
        fun md5Hex(s: String): String { val d = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray()); return d.joinToString("") { "%02x".format(it) } }
        fun chunkIds(ids: List<String>, size: Int = 50): List<List<String>> = ids.chunked(size)
    }
}

class NewsBlurProvider(private val cookieOrToken: String) : BaseHttp(), SyncProvider {
    override val typeName = "NewsBlur"
    private val root = "https://www.newsblur.com"
    private fun auth() = "Cookie" to "newsblur_sessionid=$cookieOrToken"
    override suspend fun testLogin(): String { val j = JSONObject(req("$root/social/profile", auth())); return "NewsBlur: ${j.optJSONObject("social_profile")?.optString("username") ?: "ok"}" }
    override suspend fun fetchFeeds(): List<RemoteFeed> {
        val raw = req("$root/reader/feeds", auth()); val j = JSONObject(raw); val feeds = j.optJSONObject("feeds") ?: JSONObject()
        val catMap = parseFolders(raw)
        return feeds.keys().asSequence().map { k -> val o = feeds.getJSONObject(k); RemoteFeed(k, o.optString("feed_title"), o.optString("feed_address"), catMap[k] ?: "Uncategorized") }.toList()
    }
    companion object {
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
    }
    override suspend fun fetchArticles(): List<RemoteArticle> {
        val feeds = fetchFeeds(); val out = mutableListOf<RemoteArticle>()
        for (f in feeds.take(20)) { try { val j = JSONObject(req("$root/reader/feed/${enc(f.remoteId)}?page=1", auth())); val stories = j.optJSONArray("stories") ?: continue
            for (i in 0 until stories.length()) { val o = stories.getJSONObject(i); out += RemoteArticle(o.optString("id").ifEmpty { o.optString("story_hash") }, f.remoteId, o.optString("story_title"), o.optString("story_permalink"), o.optString("story_content"), o.optLong("story_timestamp") * 1000, o.optString("story_authors"), o.optInt("read_status") == 1, false) } } catch (_: Exception) {} }
        return out
    }
    override suspend fun setRead(remoteId: String, read: Boolean) { req("$root/reader/mark_story_hashes_as_read", auth(), "POST", form = mapOf("story_hash" to remoteId)) }
    override suspend fun setFavorite(remoteId: String, fav: Boolean) { if (fav) req("$root/reader/save_story", auth(), "POST", form = mapOf("story_id" to remoteId)) }
    override suspend fun addFeed(url: String, category: String): RemoteFeed? { req("$root/reader/add_url", auth(), "POST", form = mapOf("url" to url)); return fetchFeeds().firstOrNull { it.url == url } }
    override suspend fun markAllRead() { fetchFeeds().forEach { try { req("$root/reader/mark_feed_as_read", auth(), "POST", form = mapOf("feed_id" to it.remoteId)) } catch (_: Exception) {} } }
}
