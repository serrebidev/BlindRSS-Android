package org.blindrss.android.data
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.blindrss.android.feed.*
import org.blindrss.android.providers.*
import org.blindrss.android.settings.ProviderType
import org.blindrss.android.settings.SettingsStore

class Repository(val ctx: Context) {
    val db: AppDatabase = Room.databaseBuilder(ctx, AppDatabase::class.java, "blindrss.db").fallbackToDestructiveMigration().build()
    val feeds = db.feedDao(); val articles = db.articleDao(); val misc = db.miscDao()
    val settings = SettingsStore(ctx)
    private val http = OkHttpClient()

    suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("User-Agent", "BlindRSS-Android/1.0").build()).execute().use { r -> if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code} for $url"); r.body?.string() ?: "" }
    }
    suspend fun resolveFeedUrl(input: String): String {
        val normalized = FeedDiscovery.normalizeInput(input)
        val body = try { fetchText(normalized) } catch (_: Exception) { return normalized }
        val trimmed = body.trimStart()
        if (trimmed.startsWith("<") && (trimmed.contains("<rss") || trimmed.contains("<feed") || trimmed.contains("<RDF") || trimmed.contains("\"version\""))) return normalized
        if (trimmed.startsWith("{")) return normalized
        val links = FeedDiscovery.findFeedLinks(body, normalized)
        return links.firstOrNull() ?: normalized
    }
    suspend fun addFeed(urlInput: String, category: String = "Uncategorized"): FeedEntity = withContext(Dispatchers.IO) {
        val url = resolveFeedUrl(urlInput)
        feeds.byUrl(url)?.let { return@withContext it }
        val xml = fetchText(url); val parsed = FeedParser.parse(xml, url)
        val id = feeds.insert(FeedEntity(url = url, title = parsed.title, category = category))
        val feed = feeds.byId(id)!!
        saveArticles(feed, parsed.articles); feed
    }
    suspend fun refreshFeed(feed: FeedEntity): Int = withContext(Dispatchers.IO) {
        try { val xml = fetchText(feed.url); val parsed = FeedParser.parse(xml, feed.url)
            if (feed.title.isBlank()) feeds.update(feed.copy(title = parsed.title))
            val n = saveArticles(feed, parsed.articles)
            feeds.update(feed.copy(lastError = null, lastSuccessAt = System.currentTimeMillis(), consecutiveFailures = 0)); n
        } catch (e: Exception) { feeds.update(feed.copy(lastError = e.message, lastErrorAt = System.currentTimeMillis(), consecutiveFailures = feed.consecutiveFailures + 1)); throw e }
    }
    private suspend fun saveArticles(feed: FeedEntity, parsed: List<ParsedArticle>): Int {
        val rules = misc.enabledRules()
        val entities = parsed.map { p ->
            var a = ArticleEntity(FeedParser.stableId(feed.url, p.guid), feed.id, p.title, p.url, p.content, p.description, p.date, p.author, mediaUrl = p.mediaUrl, mediaType = p.mediaType, chaptersUrl = p.chaptersUrl)
            for (r in rules) { if (ruleMatches(r, a)) { when (r.action) {
                "mark_read" -> a = a.copy(isRead = true); "mark_favorite" -> a = a.copy(isFavorite = true); "delete" -> return@map null
                "move" -> a = a.copy(folder = r.target?.takeIf { it.isNotBlank() })
                "label" -> r.target?.takeIf { it.isNotBlank() }?.let { t -> a = a.copy(labels = (a.labels.split(",") + t).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")) }
                "skip_notification" -> a = a.copy(labels = (a.labels.split(",") + "__no_notify").filter { it.isNotBlank() }.distinct().joinToString(","))
            } } }
            a
        }.filterNotNull()
        val rows = articles.insertAll(entities)
        return entities.zip(rows).count { (a, row) -> row != -1L && "__no_notify" !in a.labels.split(",") }
    }
    fun ruleMatches(r: FilterRuleEntity, a: ArticleEntity): Boolean {
        val fieldVal = when (r.field) { "title" -> a.title; "content" -> a.content; "author" -> a.author; "url" -> a.url; else -> a.title }
        return when (r.op) { "contains" -> fieldVal.contains(r.value, true); "not_contains" -> !fieldVal.contains(r.value, true); "equals" -> fieldVal.equals(r.value, true); "starts_with" -> fieldVal.startsWith(r.value, true); else -> fieldVal.contains(r.value, true) }
    }
    suspend fun deleteArticle(a: ArticleEntity) { misc.insertDeleted(DeletedArticleEntity(a.id, a.feedId, a.title, a.url, a.content, a.description, a.date, a.author, a.isRead, a.isFavorite, a.mediaUrl, a.mediaType)); articles.deleteById(a.id) }
    suspend fun restoreDeleted(id: String) { val d = misc.deletedById(id) ?: return; articles.insertAll(listOf(ArticleEntity(d.id, d.feedId, d.title, d.url, d.content, d.description, d.date, d.author, d.isRead, d.isFavorite, d.mediaUrl, d.mediaType))); misc.purgeDeleted(id) }
    suspend fun importOpml(xml: String): Int { var n = 0; for (e in Opml.parse(xml)) { try { addFeed(e.xmlUrl, e.category); n++ } catch (_: Exception) {} }; return n }
    suspend fun exportOpml(): String { val list = feeds.all().map { it.title to (it.url to it.category) }; return Opml.write(list) }
    suspend fun fullText(a: ArticleEntity): String {
        // Miniflux can extract server-side; prefer it for Miniflux-synced articles.
        val p = try { buildProvider() } catch (_: Exception) { null }
        if (p is MinifluxProvider && a.remoteId != null) {
            try { val c = p.fetchFullContent(a.remoteId); if (FullText.plainLength(c) >= 200) return c } catch (_: Exception) {}
        }
        val html = fetchArticleHtml(a.url)
        val extracted = FullText.extract(html)
        if (FullText.plainLength(extracted) < 200) throw java.io.IOException("Could not extract article text from this page")
        return extracted
    }
    private suspend fun fetchArticleHtml(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; SM-X820) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml").build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code} for $url"); r.body?.string() ?: "" }
    }
    suspend fun ensureChapters(a: ArticleEntity): List<ChapterEntity> {
        val existing = misc.chapters(a.id); if (existing.isNotEmpty()) return existing
        val url = a.chaptersUrl ?: return emptyList()
        return try { val parsed = PodcastChapters.parse(fetchText(url))
            val rows = parsed.map { ChapterEntity(articleId = a.id, startMs = it.first, title = it.second) }
            misc.deleteChapters(a.id); if (rows.isNotEmpty()) misc.insertChapters(rows); rows
        } catch (_: Exception) { emptyList() }
    }

    suspend fun buildProvider(): SyncProvider? {
        val type = settings.provider.first()
        if (type == ProviderType.LOCAL) return null
        val acct = settings.account(type)
        // Fall back to the legacy single-slot fields for installs from v1.0.
        val url = acct.url.ifEmpty { settings.serverUrl.first() }; val user = acct.user.ifEmpty { settings.username.first() }
        val secret = acct.secret.ifEmpty { settings.secret() }
        val appId = acct.appId.ifEmpty { settings.appId() }; val appKey = acct.appKey.ifEmpty { settings.appKey() }
        val refresh = acct.refresh.ifEmpty { settings.refreshToken() }
        return when (type) {
            ProviderType.MINIFLUX -> MinifluxProvider(url, secret)
            ProviderType.INOREADER -> InoreaderProvider(secret, appId, appKey, refresh) { newAccess, newRefresh ->
                val cur = settings.account(ProviderType.INOREADER)
                settings.saveAccount(ProviderType.INOREADER, cur.copy(secret = newAccess, refresh = newRefresh.ifEmpty { cur.refresh })) }
            ProviderType.THEOLDREADER -> TheOldReaderProvider(user, secret)
            ProviderType.BAZQUX -> BazQuxProvider(user, secret)
            ProviderType.GREADER -> FreshRssProvider(url, user, secret)
            ProviderType.FEVER -> FeverProvider(url, if (secret.length == 32 && secret.all { it.isDigit() || it in 'a'..'f' }) secret else FeverProvider.md5Hex("$user:$secret"))
            ProviderType.NEWSBLUR -> NewsBlurProvider(secret)
            ProviderType.LOCAL -> null
            else -> null
        }
    }
    suspend fun exchangeInoreaderCode(code: String): String {
        val acct = settings.account(ProviderType.INOREADER)
        val (access, refresh) = InoreaderProvider.exchangeCode(code, acct.appId, acct.appKey)
        settings.saveAccount(ProviderType.INOREADER, acct.copy(secret = access, refresh = refresh.ifEmpty { acct.refresh }))
        return "Inoreader connected"
    }
    suspend fun syncProvider(): String = withContext(Dispatchers.IO) {
        val p = buildProvider() ?: return@withContext "Local mode — use Refresh"
        val remoteFeeds = p.fetchFeeds()
        for (rf in remoteFeeds) { if (feeds.byUrl(rf.url) == null) feeds.insert(FeedEntity(url = rf.url, title = rf.title, category = rf.category, remoteId = rf.remoteId)) }
        val all = feeds.all(); val byRemote = all.associateBy { it.remoteId }
        var added = 0
        for (ra in p.fetchArticles()) {
            val feed = byRemote[ra.feedRemoteId] ?: all.firstOrNull() ?: continue
            val rows = articles.insertAll(listOf(ArticleEntity(FeedParser.stableId(feed.url, ra.remoteId), feed.id, ra.title, ra.url, ra.content, "", ra.date, ra.author, ra.isRead, ra.isFavorite, ra.mediaUrl, remoteId = ra.remoteId)))
            if (rows.firstOrNull() != -1L) added++
        }
        "Synced ${remoteFeeds.size} feeds, $added new articles from ${p.typeName}"
    }
    suspend fun pushRead(a: ArticleEntity, read: Boolean) { val p = buildProvider() ?: return; a.remoteId?.let { try { p.setRead(it, read) } catch (_: Exception) {} } }
    suspend fun pushFavorite(a: ArticleEntity, fav: Boolean) { val p = buildProvider() ?: return; a.remoteId?.let { try { p.setFavorite(it, fav) } catch (_: Exception) {} } }
}
