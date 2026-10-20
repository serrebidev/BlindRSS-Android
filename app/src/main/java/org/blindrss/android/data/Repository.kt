package org.blindrss.android.data
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    // With a provider signed in, the feed is subscribed on the server; Fever (no subscribe API) stays local.
    suspend fun addFeed(urlInput: String, category: String = "Uncategorized", syncAfter: Boolean = true): FeedEntity = withContext(Dispatchers.IO) {
        val url = resolveFeedUrl(urlInput)
        feeds.byUrl(url)?.let { return@withContext it }
        buildProvider()?.let { p -> p.addFeed(url, category)?.let { rf ->
            if (syncAfter) syncWith(p) else feeds.insert(FeedEntity(url = rf.url.ifEmpty { url }, title = rf.title.ifEmpty { url }, category = rf.category, remoteId = rf.remoteId))
            (feeds.byRemoteId(rf.remoteId) ?: feeds.byUrl(url))?.let { return@withContext it } } }
        val xml = fetchText(url); val parsed = FeedParser.parse(xml, url)
        val id = feeds.insert(FeedEntity(url = url, title = parsed.title, category = category))
        val feed = feeds.byId(id)!!
        saveArticles(feed, parsed.articles); feed
    }
    suspend fun refreshFeed(feed: FeedEntity): Int = withContext(Dispatchers.IO) {
        try { val xml = fetchText(feed.url); val parsed = FeedParser.parse(xml, feed.url)
            val named = if (feed.title.isBlank()) feed.copy(title = parsed.title) else feed
            val n = saveArticles(feed, parsed.articles)
            feeds.update(named.copy(lastError = null, lastSuccessAt = System.currentTimeMillis(), consecutiveFailures = 0)); n
        } catch (e: Exception) { feeds.update(feed.copy(lastError = e.message, lastErrorAt = System.currentTimeMillis(), consecutiveFailures = feed.consecutiveFailures + 1)); throw e }
    }
    data class RefreshResult(val newArticles: Int, val toNotify: Int, val failedFeeds: Int, val localFeeds: Int, val providerError: String?, val nothingWorked: Boolean)
    // One refresh for the Refresh button and the background worker: local feeds 8 at a time, then the provider.
    // Provider-owned feeds (remoteId set) come only from the provider, never fetched directly, so nothing doubles.
    suspend fun refreshAll(): RefreshResult = withContext(Dispatchers.IO) {
        val local = feeds.all().filter { it.remoteId == null }; val gate = Semaphore(8)
        val counts = coroutineScope { local.map { f -> async { gate.withPermit { try { refreshFeed(f) to f.muted } catch (_: Exception) { null } } } }.awaitAll() }
        var total = counts.sumOf { it?.first ?: 0 }; var notify = counts.sumOf { if (it == null || it.second) 0 else it.first }
        val p0 = buildProvider()
        val err = p0?.let { p -> try { val (_, n, quiet) = syncWith(p); total += n; notify += n - quiet; null } catch (e: Exception) { e.message ?: e.javaClass.simpleName } }
        val failed = counts.count { it == null }
        RefreshResult(total, notify, failed, local.size, err, failed == local.size && (p0 == null || err != null) && (local.isNotEmpty() || p0 != null))
    }
    private suspend fun saveArticles(feed: FeedEntity, parsed: List<ParsedArticle>): Int {
        val rules = misc.enabledRules()
        // Skip articles the user deleted, and ones a provider already synced into this feed (same link, provider id).
        val deleted = misc.deletedIds().toHashSet(); val synced = articles.syncedUrls(feed.id).filter { it.isNotEmpty() }.toHashSet()
        val entities = parsed.filter { p -> FeedParser.stableId(feed.url, p.guid) !in deleted && p.url !in synced }.mapNotNull { p ->
            applyRules(ArticleEntity(FeedParser.stableId(feed.url, p.guid), feed.id, p.title, p.url, p.content, p.description, p.date, p.author, mediaUrl = p.mediaUrl, mediaType = p.mediaType, chaptersUrl = p.chaptersUrl), rules) }
        val rows = articles.insertAll(entities)
        return entities.zip(rows).count { (a, row) -> row != -1L && "__no_notify" !in a.labels.split(",") }
    }
    // Filter rules run on every arriving article, from feeds and providers alike; null means "delete".
    private fun applyRules(start: ArticleEntity, rules: List<FilterRuleEntity>): ArticleEntity? {
        var a = start
        for (r in rules) { if (ruleMatches(r, a)) { when (r.action) {
            "mark_read" -> a = a.copy(isRead = true); "mark_favorite" -> a = a.copy(isFavorite = true); "delete" -> return null
            "move" -> a = a.copy(folder = r.target?.takeIf { it.isNotBlank() })
            "label" -> r.target?.takeIf { it.isNotBlank() }?.let { t -> a = a.copy(labels = (a.labels.split(",") + t).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")) }
            "skip_notification" -> a = a.copy(labels = (a.labels.split(",") + "__no_notify").filter { it.isNotBlank() }.distinct().joinToString(","))
        } } }
        return a
    }
    fun ruleMatches(r: FilterRuleEntity, a: ArticleEntity): Boolean {
        val fieldVal = when (r.field) { "title" -> a.title; "content" -> a.content; "author" -> a.author; "url" -> a.url; else -> a.title }
        return when (r.op) { "contains" -> fieldVal.contains(r.value, true); "not_contains" -> !fieldVal.contains(r.value, true); "equals" -> fieldVal.equals(r.value, true); "starts_with" -> fieldVal.startsWith(r.value, true); else -> fieldVal.contains(r.value, true) }
    }
    suspend fun deleteArticle(a: ArticleEntity) { misc.insertDeleted(DeletedArticleEntity(a.id, a.feedId, a.title, a.url, a.content, a.description, a.date, a.author, a.isRead, a.isFavorite, a.mediaUrl, a.mediaType)); articles.deleteById(a.id) }
    suspend fun restoreDeleted(id: String) { val d = misc.deletedById(id) ?: return; articles.insertAll(listOf(ArticleEntity(d.id, d.feedId, d.title, d.url, d.content, d.description, d.date, d.author, d.isRead, d.isFavorite, d.mediaUrl, d.mediaType))); misc.purgeDeleted(id) }
    suspend fun importOpml(xml: String): Int { var n = 0; for (e in Opml.parse(xml)) { try { addFeed(e.xmlUrl, e.category, syncAfter = false); n++ } catch (_: Exception) {} }
        if (n > 0) try { syncProvider() } catch (_: Exception) {}; return n }
    // Unsubscribes on the server first; if that fails the feed stays, so it cannot come back on the next sync.
    suspend fun deleteFeed(f: FeedEntity) = withContext(Dispatchers.IO) {
        f.remoteId?.let { id -> buildProvider()?.removeFeed(id, f.category) }
        articles.deleteByFeed(f.id); feeds.delete(f)
    }
    suspend fun markFeedRead(f: FeedEntity) = withContext(Dispatchers.IO) { articles.markFeedRead(f.id); f.remoteId?.let { id -> try { buildProvider()?.markFeedRead(id) } catch (_: Exception) {} } }
    suspend fun markAllRead() = withContext(Dispatchers.IO) { articles.markAllRead(); try { buildProvider()?.markAllRead() } catch (_: Exception) {} }
    suspend fun exportOpml(): String { val list = feeds.all().map { it.title to (it.url to it.category) }; return Opml.write(list) }
    suspend fun fullText(a: ArticleEntity): String {
        // Miniflux can extract server-side; prefer it for Miniflux-synced articles.
        val p = try { buildProvider() } catch (_: Exception) { null }
        if (p is MinifluxProvider && a.remoteId != null) {
            try { val c = withContext(Dispatchers.IO) { p.fetchFullContent(a.remoteId) }; if (FullText.plainLength(c) >= 200) return c } catch (_: Exception) {}
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
        return providerFor(type, SettingsStore.Account(url, user, secret, appId, appKey, refresh))
    }
    fun providerFor(type: ProviderType, a: SettingsStore.Account): SyncProvider? {
        val (url, user, secret, appId, appKey, refresh) = a
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
        }
    }
    // Tests the login first; nothing is saved or switched unless it works.
    suspend fun signIn(type: ProviderType, input: SettingsStore.Account): String = withContext(Dispatchers.IO) {
        // NewsBlur: trade username and password for a session cookie; blank password keeps the saved session.
        val acct = if (type != ProviderType.NEWSBLUR) input
            else input.copy(secret = if (input.secret.isEmpty()) settings.account(type).secret else NewsBlurProvider.login(input.user, input.secret))
        val who = providerFor(type, acct)?.testLogin() ?: ""
        settings.saveAccount(type, acct); switchTo(type); who
    }
    // Feeds from the previous provider become plain local feeds (kept, refreshed directly) when the account changes.
    private suspend fun switchTo(type: ProviderType) { if (settings.provider.first() != type) feeds.clearRemoteIds(); settings.setActive(type) }
    suspend fun signOut() { settings.saveAccount(settings.provider.first(), SettingsStore.Account()); switchTo(ProviderType.LOCAL); settings.saveProvider(ProviderType.LOCAL, "", "", "") }
    suspend fun exchangeInoreaderCode(code: String): String = withContext(Dispatchers.IO) {
        val acct = settings.account(ProviderType.INOREADER)
        val (access, refresh) = InoreaderProvider.exchangeCode(code, acct.appId, acct.appKey)
        settings.saveAccount(ProviderType.INOREADER, acct.copy(secret = access, refresh = refresh.ifEmpty { acct.refresh }))
        switchTo(ProviderType.INOREADER)
        "Inoreader connected"
    }
    suspend fun syncProvider(): String = withContext(Dispatchers.IO) {
        val p = buildProvider() ?: return@withContext "Local mode — use Refresh"
        val (feedCount, added) = syncWith(p)
        "Synced $feedCount feeds, $added new articles from ${p.typeName}"
    }
    // Mirrors the account: feeds added, renamed, moved or removed on the server; new articles inserted;
    // read and favorite state of known articles updated. Returns feeds, new articles, new articles not to notify about.
    private suspend fun syncWith(p: SyncProvider): Triple<Int, Int, Int> {
        val remoteFeeds = p.fetchFeeds(); val remoteIds = remoteFeeds.map { it.remoteId }.toSet()
        for (f in feeds.all()) if (f.remoteId != null && f.remoteId !in remoteIds) { articles.deleteByFeed(f.id); feeds.delete(f) }
        for (rf in remoteFeeds) {
            val cur = feeds.byRemoteId(rf.remoteId) ?: feeds.byUrl(rf.url)
            if (cur == null) feeds.insert(FeedEntity(url = rf.url, title = rf.title, category = rf.category, remoteId = rf.remoteId))
            else { val next = cur.copy(remoteId = rf.remoteId, title = rf.title.ifBlank { cur.title }, category = rf.category); if (next != cur) feeds.update(next) }
        }
        val byRemote = feeds.all().filter { it.remoteId != null }.associateBy { it.remoteId }
        val owned = byRemote.values.map { it.id }.toSet()
        val rules = misc.enabledRules(); val deleted = misc.deletedIds().toHashSet()
        var added = 0; var quiet = 0
        for (ra in p.fetchArticles()) {
            val feed = byRemote[ra.feedRemoteId] ?: continue
            val id = FeedParser.stableId(feed.url, ra.remoteId)
            if (id in deleted) continue
            // Known under this id, or fetched directly before this feed was synced: update state only.
            val existing = articles.byId(id) ?: ra.url.takeIf { it.isNotEmpty() }?.let { articles.localByUrl(feed.id, it) }
            if (existing != null) { articles.setRemoteState(existing.id, ra.isRead, ra.isFavorite, ra.remoteId); continue }
            val a = applyRules(ArticleEntity(id, feed.id, ra.title, ra.url, ra.content, "", ra.date, ra.author, ra.isRead, ra.isFavorite, ra.mediaUrl, remoteId = ra.remoteId), rules) ?: continue
            if (articles.insertAll(listOf(a)).firstOrNull() != -1L) { added++; if (feed.muted || "__no_notify" in a.labels.split(",")) quiet++ }
        }
        // Items read elsewhere drop out of the server's unread list; mirror that here.
        p.unreadIds()?.let { unread -> articles.unreadRemote().filter { it.feedId in owned && it.remoteId !in unread }.map { it.id }.chunked(500).forEach { articles.markRead(it) } }
        return Triple(remoteFeeds.size, added, quiet)
    }
    // Provider clients do blocking HTTP, so every call leaves the main thread.
    // Only articles of feeds the current provider owns; ids from an earlier provider mean nothing to this one.
    private suspend fun remoteOf(a: ArticleEntity): String? = a.remoteId?.takeIf { feeds.byId(a.feedId)?.remoteId != null }
    suspend fun pushRead(a: ArticleEntity, read: Boolean) = withContext(Dispatchers.IO) { val id = remoteOf(a) ?: return@withContext; try { buildProvider()?.setRead(id, read) } catch (_: Exception) {} }
    suspend fun pushFavorite(a: ArticleEntity, fav: Boolean) = withContext(Dispatchers.IO) { val id = remoteOf(a) ?: return@withContext; try { buildProvider()?.setFavorite(id, fav) } catch (_: Exception) {} }
}
