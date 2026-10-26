package org.blindrss.android.data
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.blindrss.android.feed.*
import org.blindrss.android.providers.*
import org.blindrss.android.settings.ProviderType
import org.blindrss.android.settings.SettingsStore

class Repository(val ctx: Context) {
    // One database and one HTTP cache per process: the background worker builds its own Repository, and two Room
    // instances do not see each other's writes (lists would not update), two caches on one folder corrupt it.
    val db: AppDatabase = shared(ctx).first
    val feeds = db.feedDao(); val articles = db.articleDao(); val misc = db.miscDao()
    val settings = SettingsStore(ctx)
    private val http: OkHttpClient = shared(ctx).second

    suspend fun fetchText(url: String): String = fetchFeed(url, ifChanged = false)!!
    // The identity every feed/page fetch presents (Settings > Fetching): a
    // browser user-agent preset plus optional clearance cookies for sites
    // behind bot protection. Provider API clients use their own auth.
    private suspend fun identity(): Pair<String, String> =
        org.blindrss.android.settings.FetchIdentity.userAgent(settings.feedUaPreset.first()) to settings.siteCookies()
    private fun Request.Builder.identity(ua: String, cookies: String): Request.Builder {
        header("User-Agent", ua); if (cookies.isNotBlank()) header("Cookie", cookies); return this
    }
    // Revalidates against the HTTP cache (ETag / Last-Modified). ifChanged: null when the server answered 304.
    private suspend fun fetchFeed(url: String, ifChanged: Boolean = true): String? = withContext(Dispatchers.IO) {
        val (ua, cookies) = identity()
        http.newCall(Request.Builder().url(url).identity(ua, cookies).cacheControl(CacheControl.Builder().noCache().build()).build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code} for $url")
            if (ifChanged && r.networkResponse?.code == 304) null else r.body?.let { b -> decode(b.bytes(), b.contentType()?.charset()) } ?: "" }
    }
    suspend fun resolveFeedUrl(input: String): String {
        val normalized = FeedDiscovery.normalizeInput(input)
        // Listing feeds (SoundCloud/Mixcloud pages, Google Groups, YouTube
        // search pages) keep their page address: it is enumerated, not parsed.
        if (MediaSites.listingKind(normalized) != null) return normalized
        val body = try { fetchText(normalized) } catch (_: Exception) { return normalized }
        if (looksLikeFeed(body)) return normalized
        val links = FeedDiscovery.findFeedLinks(body, normalized)
        return links.firstOrNull() ?: normalized
    }
    // Enumerate a listing feed into a ParsedFeed. SoundCloud uses its public
    // API (yt-dlp flat listing as fallback); Mixcloud its public API; Google
    // Groups and YouTube listings are enumerated from the page / by yt-dlp,
    // the same mechanisms as the desktop.
    private suspend fun fetchListingFeed(url: String): ParsedFeed {
        val kind = MediaSites.listingKind(url) ?: throw java.io.IOException("Not a listing feed: $url")
        val (title, items) = when (kind) {
            ListingKind.SOUNDCLOUD -> try { SoundCloudApi.fetchListing(url) }
                catch (e: Exception) { ytdlpListing(url) ?: throw e }
            ListingKind.MIXCLOUD -> MixcloudApi.fetchListing(url)
            ListingKind.GOOGLE_GROUPS -> { val html = fetchText(url + if ("?" in url) "&hl=en" else "?hl=en")
                if (GoogleGroups.permissionDenied(html)) throw java.io.IOException("This Google Group requires sign-in; only public groups can be read.")
                GoogleGroups.parseGroupItems(html) }
            ListingKind.YOUTUBE -> ytdlpListing(url) ?: throw java.io.IOException("Video listing feeds need yt-dlp support (ARM64 device, Android 10 or newer).")
        }
        if (items.isEmpty()) throw java.io.IOException("No items found at $url")
        val now = System.currentTimeMillis()
        return ParsedFeed(title.ifEmpty { url }, items.map { ParsedArticle(it.url, it.title, it.url, "", "", if (it.dateMs > 0) it.dateMs else now, it.author, null, null) })
    }
    private suspend fun ytdlpListing(url: String): Pair<String, List<ListingItem>>? {
        if (!org.blindrss.android.playback.Ytdl.supported()) return null
        val json = withContext(Dispatchers.IO) { org.blindrss.android.playback.Ytdl.flatJsonBlocking(ctx, url, 80) }
        val items = VideoSearch.entriesToListingItems(json)
        return if (items.isEmpty()) null else VideoSearch.flatTitle(json).ifEmpty { url } to items
    }
    // With a provider signed in, the feed is subscribed on the server; Fever (no subscribe API) stays local.
    suspend fun addFeed(urlInput: String, category: String = "Uncategorized", syncAfter: Boolean = true): FeedEntity = withContext(Dispatchers.IO) {
        val url = resolveFeedUrl(urlInput)
        feeds.byUrl(url)?.let { return@withContext it }
        val listing = MediaSites.listingKind(url) != null
        if (!listing) buildProvider()?.let { p -> p.addFeed(url, category)?.let { rf ->
            if (syncAfter) syncWith(p) else feeds.insert(FeedEntity(url = rf.url.ifEmpty { url }, title = rf.title.ifEmpty { url }, category = rf.category, remoteId = rf.remoteId))
            (feeds.byRemoteId(rf.remoteId) ?: feeds.byUrl(url))?.let { return@withContext it } } }
        val parsed = if (listing) fetchListingFeed(url) else FeedParser.parse(fetchText(url), url)
        val id = feeds.insert(FeedEntity(url = url, title = parsed.title, category = category, description = parsed.description, siteTitle = parsed.title))
        val feed = feeds.byId(id)!!
        saveArticles(feed, parsed.articles); feed
    }
    suspend fun refreshFeed(feed: FeedEntity): Int = withContext(Dispatchers.IO) {
        try { val parsed = if (MediaSites.listingKind(feed.url) != null) fetchListingFeed(feed.url) else {
                val xml = fetchFeed(feed.url) ?: run { feeds.markSuccess(feed.id, System.currentTimeMillis(), ""); return@withContext 0 }
                val p = try { FeedParser.parse(xml, feed.url) } catch (_: Exception) { null }
                // Not a feed after all: a listing page yt-dlp can enumerate
                // (Vimeo/Dailymotion channel pages and the like).
                if (p != null && (p.articles.isNotEmpty() || looksLikeFeed(xml))) p
                else ytdlpListing(feed.url)?.let { (t, items) -> ParsedFeed(t, items.map { ParsedArticle(it.url, it.title, it.url, "", "", if (it.dateMs > 0) it.dateMs else System.currentTimeMillis(), it.author, null, null) }) } ?: (p ?: FeedParser.parse(xml, feed.url))
            }
            val n = saveArticles(feed, parsed.articles)
            feeds.markSuccess(feed.id, System.currentTimeMillis(), parsed.title)
            feeds.updateMeta(feed.id, parsed.title, parsed.description); n
        } catch (e: Exception) { feeds.markFailure(feed.id, e.message, System.currentTimeMillis()); throw e }
    }
    // Feed properties. Provider feeds are renamed and moved on the server first, so the next sync keeps the change;
    // their address belongs to the server. A local feed's new address must answer with a feed before anything is saved.
    suspend fun updateFeed(f: FeedEntity, titleInput: String, categoryInput: String, urlInput: String) = withContext(Dispatchers.IO) {
        val title = titleInput.trim(); val category = categoryInput.trim().ifEmpty { "Uncategorized" }
        if (title.isEmpty()) throw java.io.IOException("Title is required")
        val cur = feeds.byId(f.id) ?: throw java.io.IOException("This feed no longer exists")
        cur.remoteId?.let { remote ->
            if (title != cur.title || category != cur.category) (buildProvider() ?: throw java.io.IOException("No provider signed in")).updateFeed(remote, cur.title, title, cur.category, category)
            feeds.update(cur.copy(title = title, category = category)); return@withContext
        }
        val typed = urlInput.trim(); if (typed.isEmpty()) throw java.io.IOException("Feed address is required")
        if (typed == cur.url) { feeds.update(cur.copy(title = title, category = category)); return@withContext }
        val url = resolveFeedUrl(typed)
        feeds.byUrl(url)?.takeIf { it.id != cur.id }?.let { throw java.io.IOException("Already subscribed at that address: ${it.title}") }
        val parsed = if (MediaSites.listingKind(url) != null) fetchListingFeed(url) else {
            val body = fetchText(url); if (!looksLikeFeed(body)) throw java.io.IOException("No feed found at $url"); FeedParser.parse(body, url) }
        db.withTransaction { adoptArticles(cur.id, url, parsed.articles)
            feeds.update(cur.copy(url = url, title = title, category = category, lastError = null, lastSuccessAt = System.currentTimeMillis(), consecutiveFailures = 0)) }
        saveArticles(feeds.byId(cur.id)!!, parsed.articles)
    }
    // Article ids hash the feed address, so a moved feed would come back as all new. Articles (and deleted ones) that
    // the new address still lists keep their read, favorite, download, resume and chapter state under the new ids.
    // ponytail: the saved Up next queue keeps old ids; a moved episode drops out of it at the next launch.
    private suspend fun adoptArticles(feedId: Long, newUrl: String, parsed: List<ParsedArticle>) {
        val incoming = parsed.map { Keyed(FeedParser.stableId(newUrl, it.guid), it.url, it.mediaUrl) }
        for ((from, to) in rekeyPlan(articles.byFeed(feedId).map { Keyed(it.id, it.url, it.mediaUrl) }, incoming)) {
            articles.rekey(from, to); misc.rekeyDownload(from, to); misc.rekeyPlayback(from, to); misc.rekeyChapters(from, to) }
        for ((from, to) in rekeyPlan(misc.deletedByFeed(feedId).map { Keyed(it.id, it.url, it.mediaUrl) }, incoming)) misc.rekeyDeleted(from, to)
    }
    data class RefreshResult(val newArticles: Int, val toNotify: Int, val failedFeeds: Int, val localFeeds: Int, val providerError: String?, val nothingWorked: Boolean)
    // One refresh for the Refresh button and the background worker: local feeds 8 at a time, then the provider.
    // Provider-owned feeds (remoteId set) come only from the provider, never fetched directly, so nothing doubles.
    // manual: also asks the provider's server to fetch its feeds first (Miniflux), so new items appear on the next sync.
    suspend fun refreshAll(manual: Boolean = false): RefreshResult = withContext(Dispatchers.IO) {
        val local = feeds.all().filter { it.remoteId == null }; val gate = Semaphore(8)
        val counts = coroutineScope { local.map { f -> async { gate.withPermit { try { refreshFeed(f) to f.muted } catch (_: Exception) { null } } } }.awaitAll() }
        var total = counts.sumOf { it?.first ?: 0 }; var notify = counts.sumOf { if (it == null || it.second) 0 else it.first }
        val p0 = buildProvider()
        if (manual) try { p0?.refreshServer() } catch (_: Exception) {}
        val err = p0?.let { p -> try { val (_, n, quiet) = syncWith(p); total += n; notify += n - quiet; null } catch (e: Exception) { e.message ?: e.javaClass.simpleName } }
        trimFeeds()
        saveOfflineText()
        val failed = counts.count { it == null }
        RefreshResult(total, notify, failed, local.size, err, failed == local.size && (p0 == null || err != null) && (local.isNotEmpty() || p0 != null))
    }
    // Settings > Sync > Keep per feed: drop the oldest beyond the limit, favorites always kept.
    // Retention by age (Settings > Sync) drops non-favorites older than the cutoff.
    private suspend fun trimFeeds() { val keep = settings.maxPerFeed.first(); if (keep > 0) for (f in feeds.all()) articles.trimFeed(f.id, keep)
        val days = settings.retentionDays.first(); if (days > 0) articles.deleteOlderThan(System.currentTimeMillis() - days * 86_400_000L) }
    private suspend fun saveArticles(feed: FeedEntity, all: List<ParsedArticle>): Int {
        // With a per-feed limit, a long feed only contributes its newest items, so trimmed ones never return as new.
        val keep = settings.maxPerFeed.first(); var parsed = if (keep > 0) all.sortedByDescending { it.date }.take(keep) else all
        // Retention insert cutoff: articles already older than the window are
        // not inserted, so a refresh cannot resurrect what retention removed.
        val days = settings.retentionDays.first(); if (days > 0) { val cutoff = System.currentTimeMillis() - days * 86_400_000L; parsed = parsed.filter { it.date <= 0 || it.date >= cutoff } }
        val rules = misc.enabledRules()
        // Skip articles the user deleted, and ones a provider already synced into this feed (same link, provider id).
        val deleted = misc.deletedIds().toHashSet(); val synced = articles.syncedUrls(feed.id).filter { it.isNotEmpty() }.toHashSet()
        val entities = parsed.filter { p -> FeedParser.stableId(feed.url, p.guid) !in deleted && p.url !in synced }.mapNotNull { p ->
            applyRules(ArticleEntity(FeedParser.stableId(feed.url, p.guid), feed.id, p.title, p.url, p.content, p.description, p.date, p.author, mediaUrl = p.mediaUrl, mediaType = p.mediaType, chaptersUrl = p.chaptersUrl), rules) }
        val rows = articles.insertAll(entities)
        return entities.zip(rows).count { (a, row) -> row != -1L && "__no_notify" !in a.labels.split(",") }
    }
    // Filter rules run on every arriving article, from feeds and providers alike; null means "delete".
    // A rule's action may be an ordered comma-separated pipeline (desktop
    // semantics); a rule with stop set ends processing after it applies.
    private fun applyRules(start: ArticleEntity, rules: List<FilterRuleEntity>): ArticleEntity? {
        var a = start
        for (r in rules) { if (!ruleMatches(r, a)) continue
            for (act in r.action.split(",").map { it.trim() }.filter { it.isNotEmpty() }) { when (act) {
                "mark_read" -> a = a.copy(isRead = true); "mark_favorite" -> a = a.copy(isFavorite = true); "delete" -> return null
                "move" -> a = a.copy(folder = r.target?.takeIf { it.isNotBlank() })
                "label" -> r.target?.takeIf { it.isNotBlank() }?.let { t -> a = a.copy(labels = (a.labels.split(",") + t).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")) }
                "skip_notification" -> a = a.copy(labels = (a.labels.split(",") + "__no_notify").filter { it.isNotBlank() }.distinct().joinToString(","))
            } }
            if (r.stop) break
        }
        return a
    }
    fun ruleMatches(r: FilterRuleEntity, a: ArticleEntity): Boolean {
        val fieldVal = when (r.field) { "title" -> a.title; "content" -> a.content; "author" -> a.author; "url" -> a.url; else -> a.title }
        return when (r.op) { "contains" -> fieldVal.contains(r.value, true); "not_contains" -> !fieldVal.contains(r.value, true); "equals" -> fieldVal.equals(r.value, true); "starts_with" -> fieldVal.startsWith(r.value, true); else -> fieldVal.contains(r.value, true) }
    }
    // Delete behaviour setting: TOMBSTONE keeps a restorable copy in Deleted
    // Articles (default); PERMANENT keeps only a blank-title id marker in the
    // tombstone table — invisible in Deleted Articles (its query skips blank
    // titles) but still blocking resurrection on refresh; MOVE files the
    // article under a category instead of deleting it.
    suspend fun deleteArticle(a: ArticleEntity) {
        when (settings.deleteBehaviour.first()) {
            "MOVE" -> articles.setFolder(a.id, settings.deleteMoveTarget.first().ifBlank { "Uncategorized" })
            "PERMANENT" -> { misc.insertDeleted(DeletedArticleEntity(a.id, a.feedId, "", a.url)); articles.deleteById(a.id) }
            else -> { misc.insertDeleted(DeletedArticleEntity(a.id, a.feedId, a.title, a.url, a.content, a.description, a.date, a.author, a.isRead, a.isFavorite, a.mediaUrl, a.mediaType)); articles.deleteById(a.id) }
        }
    }
    suspend fun restoreDeleted(id: String) { val d = misc.deletedById(id) ?: return; articles.insertAll(listOf(ArticleEntity(d.id, d.feedId, d.title, d.url, d.content, d.description, d.date, d.author, d.isRead, d.isFavorite, d.mediaUrl, d.mediaType))); misc.purgeDeleted(id) }
    suspend fun importOpml(xml: String): Int { var n = 0; for (e in Opml.parse(xml)) { try { addFeed(e.xmlUrl, e.category, syncAfter = false); n++ } catch (_: Exception) {} }
        if (n > 0) try { syncProvider() } catch (_: Exception) {}; return n }
    // YouTube Takeout ZIP: subscriptions, playlists and watched channels
    // become subscriptions in the YouTube category.
    suspend fun importTakeout(zipBytes: ByteArray): Int { var n = 0
        for (e in Takeout.feedEntries(zipBytes)) { try { if (feeds.byUrl(FeedDiscovery.normalizeInput(e.feedUrl)) == null && feeds.byUrl(e.feedUrl) == null) { addFeed(e.feedUrl, "YouTube", syncAfter = false); n++ } } catch (_: Exception) {} }
        return n }
    // Podcast archive: recover episodes from Wayback snapshots of this feed.
    // Snapshots parse like the live feed; already-known articles are ignored
    // by insert, deleted ones stay deleted (saveArticles filters them).
    suspend fun recoverArchive(feed: FeedEntity): Int = withContext(Dispatchers.IO) {
        val cdx = fetchText(WaybackArchive.cdxUrl(feed.url))
        var added = 0
        for (snap in WaybackArchive.snapshotUrls(cdx)) {
            val parsed = try { FeedParser.parse(fetchText(snap), feed.url) } catch (_: Exception) { null } ?: continue
            val before = articles.byFeed(feed.id).size
            saveArticles(feed, parsed.articles)
            added += (articles.byFeed(feed.id).size - before).coerceAtLeast(0)
        }
        added }
    // YouTube account: access tokens are refreshed from the stored refresh
    // token and cached in memory for the session.
    @Volatile private var ytAccessCache: Pair<String, Long>? = null
    suspend fun youtubeAccessToken(): String {
        ytAccessCache?.let { (token, at) -> if (System.currentTimeMillis() - at < 50 * 60_000L) return token }
        val refresh = settings.youtubeRefreshToken(); if (refresh.isBlank()) throw java.io.IOException("Not signed in to YouTube")
        val (id, secret) = settings.youtubeClientConstants()
        val tokens = YouTubeAccount.refresh(YouTubeAccount.ClientConstants(id, secret), refresh)
        if (tokens.refreshToken.isNotBlank() && tokens.refreshToken != refresh)
            settings.saveYoutubeSession(tokens.refreshToken, id, secret, settings.youtubeAccountName.first())
        ytAccessCache = tokens.accessToken to System.currentTimeMillis(); return tokens.accessToken }
    suspend fun youtubeHome(): Pair<List<VideoResult>, List<VideoResult>> { val t = youtubeAccessToken()
        return YouTubeAccount.browse(t, "FEwhat_to_watch") to YouTubeAccount.browse(t, "FEsubscriptions") }
    // Open Article by URL: fetch a page and extract its readable text.
    suspend fun openUrlArticle(url: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val html = fetchArticleHtml(url)
        val title = try { org.jsoup.Jsoup.parse(html).title().trim() } catch (_: Exception) { "" }
        title.ifEmpty { url } to FullText.extract(html) }
    // Unsubscribes on the server first; if that fails the feed stays, so it cannot come back on the next sync.
    suspend fun deleteFeed(f: FeedEntity) = withContext(Dispatchers.IO) {
        f.remoteId?.let { id -> buildProvider()?.removeFeed(id, f.category) }
        articles.deleteByFeed(f.id); feeds.delete(f)
    }
    suspend fun markFeedRead(f: FeedEntity) = withContext(Dispatchers.IO) { articles.markFeedRead(f.id); f.remoteId?.let { id -> try { buildProvider()?.markFeedRead(id) } catch (_: Exception) {} } }
    // Category management (Categories screen): a category is the string on
    // its feeds plus matching article folder overrides from filter rules.
    suspend fun renameCategory(old: String, new: String) = withContext(Dispatchers.IO) { feeds.renameCategory(old, new); articles.renameFolder(old, new) }
    suspend fun deleteCategory(name: String) = withContext(Dispatchers.IO) { feeds.renameCategory(name, "Uncategorized"); articles.renameFolder(name, null) }
    suspend fun markAllRead() = withContext(Dispatchers.IO) { articles.markAllRead(); try { buildProvider()?.markAllRead() } catch (_: Exception) {} }
    suspend fun exportOpml(): String { val list = feeds.all().map { it.title to (it.url to it.category) }; return Opml.write(list) }
    suspend fun fullText(a: ArticleEntity): String {
        if (OFFLINE in a.labels.split(",")) return a.content
        // Miniflux can extract server-side; prefer it for Miniflux-synced articles.
        val p = try { buildProvider() } catch (_: Exception) { null }
        if (p is MinifluxProvider && a.remoteId != null) {
            try { val c = withContext(Dispatchers.IO) { p.fetchFullContent(a.remoteId) }; if (FullText.plainLength(c) >= 200) return c } catch (_: Exception) {}
        }
        // YouTube videos read as articles: description, chapters, transcript
        // and top comments from yt-dlp (the watch page itself hides all three).
        if (YoutubeText.isVideoUrl(a.url) && org.blindrss.android.playback.Ytdl.supported()) {
            try { val yt = youtubeArticleHtml(a.url); if (FullText.plainLength(yt) >= 200) return yt } catch (_: Exception) {}
        }
        // Forum threads (Reddit, Lemmy, Groups.io, Google Groups) read as one
        // whole discussion.
        ForumText.targetFor(a.url, feeds.byId(a.feedId)?.url ?: "")?.let { t ->
            try {
                // Member-only Groups.io archives: with an API key set, the
                // topic comes from api.groups.io instead of the public pages.
                if (t.kind == ForumText.Kind.GROUPS_IO) { val apiKey = settings.groupsioApiKey()
                    if (apiKey.isNotBlank()) { val apiHtml = GroupsIoApi.topicRef(t.jsonUrl)?.let { (_, topicId) ->
                        try { GroupsIoApi.messagesHtml(fetchJsonAuth(GroupsIoApi.messagesUrl(topicId), apiKey)) } catch (_: Exception) { "" } } ?: ""
                        if (FullText.plainLength(apiHtml) >= 200) return apiHtml } }
                val payloads = when (t.kind) {
                    // Public HTML pages: follow Groups.io paging (a message
                    // page first resolves to its topic), cap 5 pages.
                    ForumText.Kind.GROUPS_IO -> { val pages = mutableListOf<String>()
                        var next = GroupsIo.canonicalTopicUrl(t.jsonUrl)
                        if (next == null) { val msgPage = fetchText(t.jsonUrl); next = GroupsIo.topicUrlFromMessagePage(msgPage, t.jsonUrl) }
                        var hops = 0
                        while (next != null && hops < 5) { val h = fetchText(next); pages += h; next = GroupsIo.parseTopicPage(h, next).nextUrl; hops++ }
                        pages }
                    ForumText.Kind.GOOGLE_GROUPS -> listOf(fetchText(t.jsonUrl + if ("?" in t.jsonUrl) "&hl=en" else "?hl=en"))
                    else -> listOf(fetchJson(t.jsonUrl)) + (t.commentsUrl?.let { listOf(fetchJson(it)) } ?: emptyList())
                }
                val thread = ForumText.buildHtml(payloads, t); if (FullText.plainLength(thread) >= 200) return thread } catch (_: Exception) {}
        }
        val html = fetchArticleHtml(a.url)
        val extracted = FullText.extract(html)
        if (FullText.plainLength(extracted) < 200) throw java.io.IOException("Could not extract article text from this page")
        return extracted
    }
    private suspend fun fetchArticleHtml(url: String): String = withContext(Dispatchers.IO) {
        val (ua, cookies) = identity()
        http.newCall(Request.Builder().url(url).identity(ua, cookies)
            .header("Accept", "text/html,application/xhtml+xml").build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code} for $url"); r.body?.string() ?: "" }
    }
    // Authenticated JSON API (Groups.io): Bearer API key, no site cookies.
    private suspend fun fetchJsonAuth(url: String, bearer: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $bearer")
            .header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code} for $url"); r.body?.string() ?: "" }
    }
    // JSON APIs (Reddit, Lemmy) with the same identity as article pages.
    private suspend fun fetchJson(url: String): String = withContext(Dispatchers.IO) {
        val (ua, cookies) = identity()
        http.newCall(Request.Builder().url(url).identity(ua, cookies)
            .header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code} for $url"); r.body?.string() ?: "" }
    }
    // yt-dlp is blocking; its info JSON plus the subtitle track become the article.
    private suspend fun youtubeArticleHtml(pageUrl: String): String = withContext(Dispatchers.IO) {
        val info = org.blindrss.android.playback.Ytdl.infoJsonBlocking(ctx, pageUrl)
        val lines = YoutubeText.subtitleUrl(info)?.let { sub ->
            try { YoutubeText.transcriptLines(fetchJson(sub)) } catch (_: Exception) { emptyList() } }
            ?: emptyList()
        YoutubeText.buildHtml(info, lines)
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
        return providerFor(type, SettingsStore.Account(url, user, secret, appId, appKey, refresh), settings.minifluxCursor.first())
    }
    fun providerFor(type: ProviderType, a: SettingsStore.Account, cursor: Long = 0): SyncProvider? {
        val (url, user, secret, appId, appKey, refresh) = a
        return when (type) {
            ProviderType.MINIFLUX -> MinifluxProvider(url, secret, cursor)
            ProviderType.INOREADER -> InoreaderProvider(secret, appId, appKey, refresh) { newAccess, newRefresh ->
                val cur = settings.account(ProviderType.INOREADER)
                settings.saveAccount(ProviderType.INOREADER, cur.copy(secret = newAccess, refresh = newRefresh.ifEmpty { cur.refresh })) }
            ProviderType.THEOLDREADER -> TheOldReaderProvider(user, secret)
            ProviderType.BAZQUX -> BazQuxProvider(user, secret)
            ProviderType.GREADER -> FreshRssProvider(url, user, secret)
            ProviderType.FEVER -> FeverProvider(url, if (secret.length == 32 && secret.all { it.isDigit() || it in 'a'..'f' }) secret else FeverProvider.md5Hex("$user:$secret"))
            ProviderType.NEWSBLUR -> NewsBlurProvider(secret)
            ProviderType.FEEDBIN -> FeedbinProvider(user, secret)
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
    private suspend fun switchTo(type: ProviderType) {
        if (settings.provider.first() != type) { feeds.clearRemoteIds(); settings.editOutbox { emptySet() } }
        settings.setMinifluxCursor(0); settings.setActive(type)
    }
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
        flushOutbox(p)
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
        // Local changes still waiting in the outbox win over server state until they are delivered.
        val pending = settings.outbox.first().mapNotNull { it.split("|").getOrNull(1) }.toHashSet()
        for (ra in p.fetchArticles()) {
            val feed = byRemote[ra.feedRemoteId] ?: continue
            val id = FeedParser.stableId(feed.url, ra.remoteId)
            if (id in deleted) continue
            // Known under this id, or fetched directly before this feed was synced: update state only.
            val existing = articles.byId(id) ?: ra.url.takeIf { it.isNotEmpty() }?.let { articles.localByUrl(feed.id, it) }
            if (existing != null) { if (existing.id !in pending) articles.setRemoteState(existing.id, ra.isRead, ra.isFavorite, ra.remoteId); continue }
            val a = applyRules(ArticleEntity(id, feed.id, ra.title, ra.url, ra.content, "", ra.date, ra.author, ra.isRead, ra.isFavorite, ra.mediaUrl, remoteId = ra.remoteId), rules) ?: continue
            if (articles.insertAll(listOf(a)).firstOrNull() != -1L) { added++; if (feed.muted || "__no_notify" in a.labels.split(",")) quiet++ }
        }
        // Items read elsewhere drop out of the server's unread list; mirror that here.
        p.unreadIds()?.let { unread -> articles.unreadRemote().filter { it.feedId in owned && it.remoteId !in unread && it.id !in pending }.map { it.id }.chunked(500).forEach { articles.markRead(it) } }
        p.nextCursor?.let { settings.setMinifluxCursor(it) }
        return Triple(remoteFeeds.size, added, quiet)
    }
    // Provider clients do blocking HTTP, so every call leaves the main thread.
    // Only articles of feeds the current provider owns; ids from an earlier provider mean nothing to this one.
    private suspend fun remoteOf(a: ArticleEntity): String? = a.remoteId?.takeIf { feeds.byId(a.feedId)?.remoteId != null }
    // Read/favorite changes go through an outbox: sent now when online, kept and retried on every sync when not.
    suspend fun pushRead(a: ArticleEntity, read: Boolean) = queue("R", a, read)
    suspend fun pushFavorite(a: ArticleEntity, fav: Boolean) = queue("F", a, fav)
    private suspend fun queue(kind: String, a: ArticleEntity, value: Boolean) = withContext(Dispatchers.IO) {
        remoteOf(a) ?: return@withContext
        settings.editOutbox { box -> box.filterNot { it.startsWith("$kind|${a.id}|") }.toSet() + "$kind|${a.id}|${if (value) 1 else 0}" }
        try { flushOutbox(buildProvider() ?: return@withContext) } catch (_: Exception) {}
    }
    // Stops at the first failure (usually offline); entries whose article is gone are dropped.
    private suspend fun flushOutbox(p: SyncProvider) {
        for (entry in settings.outbox.first()) {
            val (kind, id, value) = entry.split("|").takeIf { it.size == 3 } ?: run { settings.editOutbox { it - entry }; null } ?: continue
            val remote = articles.byId(id)?.let { remoteOf(it) }
            try { if (remote != null) { if (kind == "R") p.setRead(remote, value == "1") else p.setFavorite(remote, value == "1") } } catch (_: Exception) { return }
            settings.editOutbox { it - entry }
        }
    }
    suspend fun saveToIntegrations(a: ArticleEntity) = withContext(Dispatchers.IO) {
        val id = remoteOf(a) ?: throw java.io.IOException("This article is not from your provider")
        (buildProvider() ?: throw java.io.IOException("No provider signed in")).saveToIntegrations(id)
    }
    // Settings > Reading > Save full text: up to 50 unread articles from the last 7 days per refresh, newest first. Text goes into content,
    // tagged so the reader uses it offline; failures are tagged too so they are not retried every refresh.
    private suspend fun saveOfflineText() {
        if (!settings.saveFullText.first()) return
        val gate = Semaphore(4)
        coroutineScope { articles.offlineCandidates(50, System.currentTimeMillis() - 7L * 86_400_000).filter { !it.url.contains("youtube.com") && !it.url.contains("youtu.be") }.map { a -> async { gate.withPermit {
            val text = try { fullText(a) } catch (_: Exception) { null }
            articles.setContent(a.id, text ?: a.content, (a.labels.split(",") + (if (text != null) OFFLINE else OFFLINE_FAILED)).filter { it.isNotBlank() }.distinct().joinToString(","))
        } } }.awaitAll() }
    }
    companion object { const val OFFLINE = "__fulltext"; const val OFFLINE_FAILED = "__fulltext_none"
        fun looksLikeFeed(body: String): Boolean { val t = body.trimStart()
            return t.startsWith("{") || (t.startsWith("<") && (t.contains("<rss") || t.contains("<feed") || t.contains("<RDF") || t.contains("\"version\""))) }
        data class Keyed(val id: String, val link: String, val media: String?)
        // Old id to new id. An item matches by its enclosure, else its link, and only when exactly one existing article
        // has that value (podcast feeds often give every episode the show's home page as link).
        fun rekeyPlan(have: List<Keyed>, incoming: List<Keyed>): Map<String, String> {
            fun unique(key: (Keyed) -> String?) = have.groupBy(key).filter { (k, v) -> !k.isNullOrEmpty() && v.size == 1 }.mapValues { it.value[0].id }
            val byMedia = unique { it.media }; val byLink = unique { it.link }; val haveIds = have.map { it.id }.toHashSet()
            val out = mutableMapOf<String, String>(); val taken = mutableSetOf<String>()
            for (n in incoming) { if (n.id in haveIds || n.id in taken) continue
                val from = n.media?.let { byMedia[it] } ?: byLink[n.link] ?: continue
                if (from in out) continue
                out[from] = n.id; taken += n.id }
            return out
        }
        // HTTP charset first, then the XML declaration (<?xml encoding="ISO-8859-1"?>), then UTF-8.
        fun decode(bytes: ByteArray, header: java.nio.charset.Charset?): String {
            val declared = Regex("""^\s*<\?xml[^>]*encoding\s*=\s*["']([\w.:-]+)["']""").find(String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1))?.groupValues?.get(1)
            val cs = header ?: declared?.let { runCatching { java.nio.charset.Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            return String(bytes, cs).removePrefix("﻿")
        }
        @Volatile private var instance: Pair<AppDatabase, OkHttpClient>? = null
        private fun shared(ctx: Context) = instance ?: synchronized(this) { instance ?: Pair(
            Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "blindrss.db")
                .addMigrations(object : androidx.room.migration.Migration(3, 4) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE feeds ADD COLUMN description TEXT NOT NULL DEFAULT ''")
                        db.execSQL("ALTER TABLE feeds ADD COLUMN siteTitle TEXT NOT NULL DEFAULT ''")
                        db.execSQL("ALTER TABLE filter_rules ADD COLUMN stop INTEGER NOT NULL DEFAULT 0")
                    }
                }).fallbackToDestructiveMigration().build(),
            OkHttpClient.Builder().cache(Cache(java.io.File(ctx.applicationContext.cacheDir, "http"), 50L * 1024 * 1024)).build()).also { instance = it } }
    }
}
