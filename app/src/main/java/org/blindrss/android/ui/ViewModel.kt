package org.blindrss.android.ui
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.blindrss.android.data.*
import org.blindrss.android.playback.Downloads
import org.blindrss.android.playback.Ytdl
import org.blindrss.android.playback.PlayerController
import org.blindrss.android.settings.ProviderType
import org.blindrss.android.sync.SyncWorker

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val repo = Repository(app)
    val player = PlayerController.get(app)
    private val reader = org.blindrss.android.playback.ReadAloud(app, repo, player) { announce(it) }
    fun readAloud(a: ArticleEntity) { announce("Preparing speech"); reader.start(a) }
    val announcements = MutableStateFlow("")
    fun announce(msg: String) { announcements.value = msg }
    val feeds: StateFlow<List<FeedEntity>> = repo.feeds.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val errorFeeds: StateFlow<List<FeedEntity>> = repo.feeds.observeErrors().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val unreadCount: StateFlow<Int> = repo.articles.unreadCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun addFeed(url: String, category: String = "Uncategorized") { viewModelScope.launch { try { val f = repo.addFeed(url, category); announce("Subscribed to ${f.title}") } catch (e: Exception) { announce("Error adding feed: ${e.message}") } } }
    // chime: the desktop app sounds a chime when a refresh the user started
    // completes; automatic refreshes (launch, background) stay silent.
    fun refreshAll(chime: Boolean = true) { viewModelScope.launch { announce("Refreshing"); val r = repo.refreshAll(manual = true)
        announce(buildString { append("Refresh complete. ${r.newArticles} new articles.")
            if (r.failedFeeds > 0) append(" ${r.failedFeeds} feeds with errors."); r.providerError?.let { append(" Sync failed: $it") } })
        if (chime && repo.settings.refreshChime.first()) org.blindrss.android.playback.Chime.play(getApplication()) } }
    // Background sync is scheduled on every launch so it runs without a manual refresh first.
    // Miniflux sends saved articles to the read-later services set up on the server (Instapaper, Pocket, Wallabag...).
    val canSaveToIntegrations: StateFlow<Boolean> = repo.settings.provider.map { it == ProviderType.MINIFLUX }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun saveToIntegrations(a: ArticleEntity) { viewModelScope.launch { announce(try { repo.saveToIntegrations(a); "Saved: ${a.title}" } catch (e: Exception) { "Save failed: ${e.message}" }) } }
    fun refreshOnLaunch() { viewModelScope.launch { SyncWorker.schedule(getApplication()); if (repo.settings.refreshOnStart.first()) refreshAll(chime = false) } }
    // ---- Self-update (GitHub releases) ----
    val appUpdate = MutableStateFlow<org.blindrss.android.update.AppUpdate.ReleaseInfo?>(null)
    fun dismissAppUpdate() { appUpdate.value = null }
    // On start, throttled to one check a day; manual checks (Settings >
    // About) pass manual = true. A newer release lands in appUpdate, which
    // Home surfaces as a dialog — never a silent toast.
    fun checkAppUpdate(manual: Boolean = false, onResult: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            try {
                if (!manual) {
                    if (!repo.settings.autoUpdateCheck.first()) return@launch
                    val last = repo.settings.updateLastCheck.first()
                    if (System.currentTimeMillis() - last < 24 * 60 * 60 * 1000L) return@launch
                }
                repo.settings.setUpdateLastCheck(System.currentTimeMillis())
                val info = org.blindrss.android.update.AppUpdate.latestRelease()
                val installed = org.blindrss.android.update.AppUpdate.installedVersion(getApplication())
                when {
                    info == null -> onResult?.invoke("Update check failed. Are you online?")
                    org.blindrss.android.update.AppUpdate.isNewer(info.version, installed) -> { appUpdate.value = info; onResult?.invoke("Version ${info.version} is available.") }
                    else -> onResult?.invoke("Up to date.")
                }
            } catch (_: Exception) { onResult?.invoke("Update check failed. Are you online?") }
        }
    }
    // ---- Video Search ----
    // A search result becomes a throwaway ArticleEntity (feed 0): the player
    // and downloader resolve its page URL through yt-dlp, exactly like a
    // YouTube article with no direct media link.
    fun videoArticle(r: org.blindrss.android.feed.VideoResult): ArticleEntity = ArticleEntity(
        id = org.blindrss.android.feed.FeedParser.stableId("video-search", r.url), feedId = 0,
        title = r.title, url = r.url,
        date = if (r.uploadDateMs > 0) r.uploadDateMs else System.currentTimeMillis(),
        author = r.uploader)
    fun canPlayVideo(r: org.blindrss.android.feed.VideoResult): Boolean =
        Ytdl.supported() && org.blindrss.android.feed.MediaSites.isPlayablePage(r.url)
    fun searchVideos(query: String, siteId: String, onDone: (List<org.blindrss.android.feed.VideoResult>, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val results = when (siteId) {
                    "all" -> { val out = mutableListOf<org.blindrss.android.feed.VideoResult>()
                        for (id in listOf("youtube", "soundcloud")) org.blindrss.android.feed.VideoSearch.site(id)?.let { s ->
                            try { out += org.blindrss.android.feed.VideoSearch.parseFlatEntries(
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Ytdl.flatJsonBlocking(getApplication(), org.blindrss.android.feed.VideoSearch.queryTarget(s, query, 20), 20) }, s.label) } catch (_: Exception) {} }
                        try { out += org.blindrss.android.feed.MixcloudApi.searchMedia(query, 20) } catch (_: Exception) {}
                        out }
                    "mixcloud" -> org.blindrss.android.feed.MixcloudApi.searchMedia(query, 40)
                    else -> { val s = org.blindrss.android.feed.VideoSearch.site(siteId) ?: throw IllegalArgumentException("Unknown site")
                        if (!Ytdl.supported()) { onDone(emptyList(), "Video search needs yt-dlp support (ARM64 device, Android 10 or newer)."); return@launch }
                        org.blindrss.android.feed.VideoSearch.parseFlatEntries(
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Ytdl.flatJsonBlocking(getApplication(), org.blindrss.android.feed.VideoSearch.queryTarget(s, query, 40), 40) }, s.label) }
                }
                onDone(results, if (results.isEmpty()) "No results." else null)
            } catch (e: Exception) { onDone(emptyList(), "Search failed: ${e.message}") }
        }
    }
    fun syncNow() { viewModelScope.launch { announce("Syncing"); announce(try { repo.syncProvider() } catch (e: Exception) { "Sync failed: ${e.message}" }) } }
    // Provider feeds come only through the provider; fetching them directly would duplicate articles.
    fun refreshFeed(f: FeedEntity) { if (f.remoteId != null) return syncNow(); viewModelScope.launch { try { val n = repo.refreshFeed(f); announce("Refreshed ${f.title}. $n new articles.") } catch (e: Exception) { announce("Error refreshing ${f.title}: ${e.message}") } } }
    // onDone(null) when saved, else the reason, so the edit screen can stay open with the user's input.
    fun updateFeed(f: FeedEntity, title: String, category: String, url: String, onDone: (String?) -> Unit) { viewModelScope.launch {
        try { repo.updateFeed(f, title, category, url); announce("Saved ${title.trim()}"); onDone(null) }
        catch (e: Exception) { onDone(e.message ?: e.javaClass.simpleName) } } }
    fun deleteFeed(f: FeedEntity) { viewModelScope.launch { try { repo.deleteFeed(f); announce("Deleted feed ${f.title}") } catch (e: Exception) { announce("Could not delete ${f.title}: ${e.message}") } } }
    fun toggleMute(f: FeedEntity) { viewModelScope.launch { repo.feeds.setMuted(f.id, !f.muted); announce(if (f.muted) "Notifications on for ${f.title}" else "Notifications muted for ${f.title}") } }
    fun setRead(a: ArticleEntity, read: Boolean) { viewModelScope.launch { repo.articles.setRead(a.id, read); repo.pushRead(a, read); announce(if (read) "Marked read: ${a.title}" else "Marked unread: ${a.title}") } }
    fun toggleFavorite(a: ArticleEntity) { viewModelScope.launch { repo.articles.setFavorite(a.id, !a.isFavorite); repo.pushFavorite(a, !a.isFavorite); announce(if (!a.isFavorite) "Favorited: ${a.title}" else "Removed favorite: ${a.title}") } }
    fun deleteArticle(a: ArticleEntity) { viewModelScope.launch { repo.deleteArticle(a); announce("Deleted: ${a.title}. Available in Deleted Articles.") } }
    fun restoreDeleted(id: String) { viewModelScope.launch { repo.restoreDeleted(id); announce("Article restored") } }
    fun purgeDeleted(id: String) { viewModelScope.launch { repo.misc.purgeDeleted(id); announce("Article permanently deleted") } }
    fun purgeAllDeleted() { viewModelScope.launch { repo.misc.purgeAllDeleted(); announce("All deleted articles purged") } }
    fun markAllRead() { viewModelScope.launch { repo.markAllRead(); announce("All articles marked read") } }
    fun markFeedRead(f: FeedEntity) { viewModelScope.launch { repo.markFeedRead(f); announce("Marked all read in ${f.title}") } }
    fun importOpml(xml: String) { viewModelScope.launch { val n = repo.importOpml(xml); announce("Imported $n feeds from OPML") } }
    fun importTakeout(bytes: ByteArray) { viewModelScope.launch { try { val n = repo.importTakeout(bytes); announce("Imported $n subscriptions from YouTube Takeout") } catch (e: Exception) { announce("Takeout import failed: ${e.message}") } } }
    // YouTube account sign-in (device-code flow): Idle / Waiting(code) /
    // Working / Failed(message). Signed-in state itself lives in settings.
    sealed interface YtSignIn { object Idle : YtSignIn; object Working : YtSignIn
        data class Waiting(val code: org.blindrss.android.feed.YouTubeAccount.DeviceCode) : YtSignIn
        data class Failed(val message: String) : YtSignIn }
    val ytSignIn = MutableStateFlow<YtSignIn>(YtSignIn.Idle)
    fun startYouTubeSignIn() { viewModelScope.launch {
        ytSignIn.value = YtSignIn.Working
        try { val c = org.blindrss.android.feed.YouTubeAccount.fetchClientConstants()
            val dc = org.blindrss.android.feed.YouTubeAccount.requestDeviceCode(c)
            ytSignIn.value = YtSignIn.Waiting(dc); announce("Enter code ${dc.userCode} at google.com/device")
            val deadline = System.currentTimeMillis() + dc.expiresInSec * 1000L
            var delayMs = dc.intervalSec.coerceAtLeast(5) * 1000L
            while (System.currentTimeMillis() < deadline) { kotlinx.coroutines.delay(delayMs)
                val (tokens, err) = org.blindrss.android.feed.YouTubeAccount.pollToken(c, dc.deviceCode)
                if (tokens != null) { repo.settings.saveYoutubeSession(tokens.refreshToken, c.id, c.secret, "YouTube account")
                    ytSignIn.value = YtSignIn.Idle; announce("Signed in to YouTube"); return@launch }
                if (err == "slow_down") delayMs += 5000
                else if (err != "pending") { ytSignIn.value = YtSignIn.Failed("Sign-in failed: $err"); announce("YouTube sign-in failed"); return@launch } }
            ytSignIn.value = YtSignIn.Failed("The code expired. Try again.")
        } catch (e: Exception) { ytSignIn.value = YtSignIn.Failed(e.message ?: "Sign-in failed"); announce("YouTube sign-in failed: ${e.message}") } } }
    fun signOutYouTube() { viewModelScope.launch { repo.settings.clearYoutubeSession(); ytSignIn.value = YtSignIn.Idle; announce("Signed out of YouTube") } }
    fun recoverArchive(f: FeedEntity) { viewModelScope.launch { announce("Checking the Wayback Machine for ${f.title}"); try { val n = repo.recoverArchive(f); announce("Recovered $n archived episodes for ${f.title}") } catch (e: Exception) { announce("Archive recovery failed: ${e.message}") } } }
    fun renameCategory(old: String, new: String) { viewModelScope.launch { repo.renameCategory(old, new); announce("Category renamed to $new") } }
    fun deleteCategory(name: String) { viewModelScope.launch { repo.deleteCategory(name); announce("Category $name deleted. Its feeds moved to Uncategorized.") } }
    // Open Media by URL: a throwaway article (feed 0) the player resolves —
    // direct media links play as-is, pages go through yt-dlp like Video Search.
    fun openMediaUrl(url: String) { val u = url.trim(); if (u.isBlank()) return
        val direct = u.lowercase().substringBefore("?").let { it.endsWith(".mp3") || it.endsWith(".m4a") || it.endsWith(".mp4") || it.endsWith(".opus") || it.endsWith(".ogg") || it.endsWith(".wav") }
        val a = ArticleEntity(id = org.blindrss.android.feed.FeedParser.stableId("open-media", u), feedId = 0, title = u, url = u,
            date = System.currentTimeMillis(), mediaUrl = if (direct) u else null, mediaType = if (direct) "audio" else null)
        play(a) }
    // Episodes, and pages yt-dlp can play (YouTube and similar) on supported devices.
    fun canPlay(a: ArticleEntity) = a.mediaUrl != null || (Ytdl.supported() && (isYouTube(a.url) || org.blindrss.android.feed.MediaSites.isPlayablePage(a.url)))
    fun play(a: ArticleEntity) { viewModelScope.launch { player.play(a); announce(if (a.mediaUrl == null) "Loading audio: ${a.title}" else "Playing: ${a.title}") } }
    fun playNext(a: ArticleEntity) { viewModelScope.launch { player.playNext(a); announce("Playing next: ${a.title}") } }
    fun enqueue(a: ArticleEntity) { viewModelScope.launch { player.enqueue(a); announce("Added to Up next: ${a.title}") } }
    fun deleteDownload(a: ArticleEntity) { viewModelScope.launch { Downloads.delete(repo, a.id); announce("Download deleted: ${a.title}") } }
    val downloadedIds: StateFlow<Set<String>> = repo.misc.observeDownloadIds().map { it.toSet() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    fun updateYtdl(cb: (String) -> Unit) { viewModelScope.launch { cb(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { try { Ytdl.updateNow(getApplication()) } catch (e: Exception) { "yt-dlp: ${e.message}" } }) } }
    fun ytdlVersion(cb: (String) -> Unit) { viewModelScope.launch { cb(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { try { Ytdl.version(getApplication()) } catch (e: Exception) { "unknown" } }) } }
    fun download(a: ArticleEntity) { viewModelScope.launch { try { Downloads.enqueue(getApplication(), a); announce("Downloading: ${a.title}. Progress is in the notification.") } catch (e: Exception) { announce("Download failed: ${e.message}") } } }
    // Whole-category actions use the unplayed (unread) episodes of its feeds, newest first.
    private suspend fun categoryEpisodes(feeds: List<FeedEntity>) = feeds.flatMap { repo.articles.observeByFeed(it.id).first() }.filter { !it.isRead && canPlay(it) }.sortedByDescending { it.date }
    fun playCategory(name: String, feeds: List<FeedEntity>) { viewModelScope.launch {
        val eps = categoryEpisodes(feeds); if (eps.isEmpty()) return@launch announce("No unplayed episodes in $name")
        player.play(eps.first()); eps.drop(1).forEach { player.enqueue(it) }
        announce("Playing $name. ${eps.size} episodes in Up next.") } }
    fun downloadCategory(name: String, feeds: List<FeedEntity>) { viewModelScope.launch {
        val eps = categoryEpisodes(feeds).filter { it.id !in downloadedIds.value }; if (eps.isEmpty()) return@launch announce("Nothing new to download in $name")
        announce("Downloading ${eps.size} episodes from $name. Progress is in the notification.")
        var failed = 0; for (a in eps) try { Downloads.enqueue(getApplication(), a) } catch (_: Exception) { failed++ }
        if (failed > 0) announce("$failed downloads from $name failed to start") } }
    fun loadFullText(a: ArticleEntity, cb: (String?) -> Unit) { viewModelScope.launch { try { val t = repo.fullText(a); announce("Full text loaded"); cb(t) } catch (e: Exception) { announce("Full text unavailable. Showing feed text. ${e.message}"); cb(null) } } }
    fun isYouTube(url: String): Boolean = Regex("youtube\\.com|youtu\\.be", RegexOption.IGNORE_CASE).containsMatchIn(url)
    // Single entry point for opening an article, honoring the open-mode settings.
    fun openArticle(ctx: android.content.Context, a: ArticleEntity, nav: androidx.navigation.NavController) {
        viewModelScope.launch {
            if (!a.isRead) { repo.articles.setRead(a.id, true); repo.pushRead(a, true) }
            if (isYouTube(a.url)) {
                when (repo.settings.youtubeOpenMode.first()) {
                    "SMARTTUBE" -> { if (!launchPackage(ctx, a.url, listOf("org.smarttube.stable", "org.smarttube.beta", "com.liskovsoft.smarttubetv", "com.tehmazmaz.smarttubenext"))) { announce("SmartTube is not installed. Opening in browser."); launchBrowser(ctx, a.url) } }
                    "BROWSER" -> launchBrowser(ctx, a.url)
                    "IN_APP" -> if (Ytdl.supported()) { player.play(a); announce("Loading audio: ${a.title}"); nav.navigate("player") } else nav.navigate("reader/${android.net.Uri.encode(a.id)}")
                    else -> { if (!launchPackage(ctx, a.url, listOf("com.google.android.youtube"))) launchBrowser(ctx, a.url) }
                }
                return@launch
            }
            when (repo.settings.articleOpenMode.first()) {
                "FULL_TEXT", "FEED_TEXT" -> nav.navigate("reader/${android.net.Uri.encode(a.id)}")
                else -> launchBrowser(ctx, a.url)
            }
        }
    }
    fun launchBrowser(ctx: android.content.Context, url: String) { try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { announce("No browser found") } }
    private fun launchPackage(ctx: android.content.Context, url: String, packages: List<String>): Boolean {
        for (pkg in packages) { try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).setPackage(pkg).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); return true } catch (_: Exception) {} }
        return false
    }
    fun openInBrowser(ctx: android.content.Context, a: ArticleEntity) { if (isYouTube(a.url)) openExternalVideo(ctx, a) else launchBrowser(ctx, a.url) }
    private fun openExternalVideo(ctx: android.content.Context, a: ArticleEntity) { viewModelScope.launch {
        when (repo.settings.youtubeOpenMode.first()) {
            "SMARTTUBE" -> if (!launchPackage(ctx, a.url, listOf("org.smarttube.stable", "org.smarttube.beta", "com.liskovsoft.smarttubetv"))) launchBrowser(ctx, a.url)
            "BROWSER", "IN_APP" -> launchBrowser(ctx, a.url)
            else -> if (!launchPackage(ctx, a.url, listOf("com.google.android.youtube"))) launchBrowser(ctx, a.url)
        } } }
    // ---- News publisher discovery ----
    val discoverResults = MutableStateFlow<List<org.blindrss.android.discovery.DiscoveredSource>>(emptyList())
    val discoverLoading = MutableStateFlow(false)
    val discoverSelected = MutableStateFlow<Set<String>>(emptySet())
    val discoverPreview = MutableStateFlow<Pair<org.blindrss.android.discovery.DiscoveredSource, List<String>>?>(null)
    private var catalogue: List<org.blindrss.android.discovery.DiscoveredSource> = emptyList()
    private var discoverJob: kotlinx.coroutines.Job? = null
    fun loadCatalogue() { viewModelScope.launch { catalogue = org.blindrss.android.discovery.AwesomeCatalogue.loadCached(getApplication()); catalogue = org.blindrss.android.discovery.AwesomeCatalogue.refreshIfStale(getApplication()) } }
    fun browseDiscover(category: String) { viewModelScope.launch {
        val country = repo.settings.discoveryCountry.first(); val lang = repo.settings.discoveryLanguage.first()
        val city = repo.settings.discoveryCity.first(); val region = repo.settings.discoveryRegion.first()
        if (catalogue.isEmpty()) catalogue = org.blindrss.android.discovery.AwesomeCatalogue.loadCached(getApplication())
        val local = if (category == "Canada" || category == "Local news")
            org.blindrss.android.discovery.AwesomeCatalogue.search(catalogue, "", "", country, "")
        else org.blindrss.android.discovery.AwesomeCatalogue.search(catalogue, "", category, "", "")
        val google = org.blindrss.android.discovery.GoogleNewsUrls.sourcesFor(category, country, lang, city, region)
        setDiscoverResults(google + local) } }
    fun searchDiscover(query: String) {
        discoverJob?.cancel()
        discoverJob = viewModelScope.launch {
            discoverLoading.value = true
            try {
                val country = repo.settings.discoveryCountry.first(); val lang = repo.settings.discoveryLanguage.first()
                val out = mutableListOf<org.blindrss.android.discovery.DiscoveredSource>()
                val q = query.trim()
                if (q.isNotEmpty()) {
                    out += org.blindrss.android.discovery.GoogleNewsUrls.searchSource(q, country, lang)
                    try { out += org.blindrss.android.discovery.FeedlyDiscovery.search(q, "$lang-$country") } catch (_: Exception) {}
                    if (catalogue.isEmpty()) catalogue = org.blindrss.android.discovery.AwesomeCatalogue.loadCached(getApplication())
                    out += org.blindrss.android.discovery.AwesomeCatalogue.search(catalogue, q, "", "", "")
                    if (q.contains(".") && !q.contains(" ")) { try { out += org.blindrss.android.discovery.WebsiteDiscovery.discover(q) } catch (_: Exception) {} }
                    try { out += org.blindrss.android.discovery.NewsApisDiscovery.search(q, repo.settings.newsApiKey(), repo.settings.theNewsApiKey()) } catch (_: Exception) {}
                }
                setDiscoverResults(out)
                announce("Discovery search finished. ${discoverResults.value.size} sources found.")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { announce("Discovery search failed: ${e.message}") }
            finally { discoverLoading.value = false }
        }
    }
    private suspend fun setDiscoverResults(list: List<org.blindrss.android.discovery.DiscoveredSource>) {
        val subs = repo.feeds.all().map { it.url }
        discoverResults.value = org.blindrss.android.discovery.DiscoveryAggregator.markSubscribed(org.blindrss.android.discovery.DiscoveryAggregator.dedup(list), subs)
    }
    fun toggleDiscoverSelect(s: org.blindrss.android.discovery.DiscoveredSource) { val u = s.feedUrl ?: return; discoverSelected.value = if (u in discoverSelected.value) discoverSelected.value - u else discoverSelected.value + u }
    fun previewDiscover(s: org.blindrss.android.discovery.DiscoveredSource) { viewModelScope.launch {
        val url = s.feedUrl ?: return@launch announce("No feed to preview")
        try { val parsed = org.blindrss.android.feed.FeedParser.parse(repo.fetchText(url), url)
            discoverPreview.value = s.copy(name = parsed.title.ifBlank { s.name }, verified = true) to parsed.articles.take(5).map { it.title }
            announce("Previewing ${parsed.title}. ${parsed.articles.size} recent articles.") } catch (e: Exception) { announce("Preview failed. This feed could not be read: ${e.message}") } } }
    fun subscribeDiscover(sources: List<org.blindrss.android.discovery.DiscoveredSource>) { viewModelScope.launch {
        var ok = 0; var failed = 0
        for (s in sources) { val u = s.feedUrl ?: continue
            try { repo.addFeed(u, s.category.ifBlank { "News" }); ok++ } catch (_: Exception) { failed++ } }
        discoverSelected.value = emptySet()
        val subs = repo.feeds.all().map { it.url }
        discoverResults.value = org.blindrss.android.discovery.DiscoveryAggregator.markSubscribed(discoverResults.value, subs)
        announce("Subscribed to $ok sources." + if (failed > 0) " $failed failed." else "") } }
    fun toggleSubscribeDiscover(s: org.blindrss.android.discovery.DiscoveredSource) { viewModelScope.launch {
        // Same path as Delete feed: unsubscribes on the server and removes the articles, so nothing comes back on sync.
        if (s.subscribed) { val f = repo.feeds.all().firstOrNull { org.blindrss.android.discovery.UrlSafety.canonicalFeedUrl(it.url) == org.blindrss.android.discovery.UrlSafety.canonicalFeedUrl(s.feedUrl ?: "") }
            try { f?.let { repo.deleteFeed(it) } } catch (e: Exception) { return@launch announce("Could not unsubscribe from ${s.name}: ${e.message}") }
            discoverResults.value = discoverResults.value.map { if (it.feedUrl == s.feedUrl) it.copy(subscribed = false) else it }; announce("Unsubscribed from ${s.name}") }
        else subscribeDiscover(listOf(s)) } }
    fun exchangeOAuth(code: String) { viewModelScope.launch { try { announce(repo.exchangeInoreaderCode(code)); val s = repo.syncProvider(); announce(s) } catch (e: Exception) { announce("Inoreader connect failed: ${e.message}") } } }
    val castDevices = MutableStateFlow<List<org.blindrss.android.cast.DlnaDevice>>(emptyList())
    var castTarget: org.blindrss.android.cast.DlnaDevice? = null
    fun discoverCast(ctx: android.content.Context) { viewModelScope.launch { announce("Searching for DLNA renderers"); castDevices.value = org.blindrss.android.cast.Dlna.discover(ctx); announce("Found ${castDevices.value.size} DLNA devices") } }
    // Google Cast session start: hand it whatever is playing locally.
    fun castToGoogleCast(session: com.google.android.gms.cast.framework.CastSession) { viewModelScope.launch {
        val id = player.currentId() ?: return@launch announce("Nothing playing to cast")
        val a = repo.articles.byId(id) ?: return@launch announce("Nothing playing to cast")
        val url = a.mediaUrl ?: return@launch announce("Current article has no direct media to cast")
        val ok = org.blindrss.android.cast.GoogleCast.load(session, url, a.title, org.blindrss.android.cast.GoogleCast.contentTypeFor(url, a.mediaType))
        if (ok) { player.exo.pause(); announce("Casting ${a.title}") } else announce("Cast failed") } }
    fun castCurrent(device: org.blindrss.android.cast.DlnaDevice) { viewModelScope.launch { val id = player.currentId() ?: return@launch announce("Nothing playing to cast"); val a = repo.articles.byId(id) ?: return@launch announce("Nothing playing to cast"); val url = a.mediaUrl ?: return@launch announce("Current article has no media"); val ok = org.blindrss.android.cast.Dlna.play(device, url, a.title); castTarget = device; player.exo.pause(); announce(if (ok) "Casting ${a.title} to ${device.name}" else "Cast failed to ${device.name}") } }
    fun searchDirectory(q: String, cb: (List<org.blindrss.android.feed.SearchHit>) -> Unit) { viewModelScope.launch { try { cb(org.blindrss.android.feed.DiscoverySearch.search(q)) } catch (e: Exception) { announce("Directory search failed: ${e.message}"); cb(emptyList()) } } }
    // One-time import files pushed to the app's external files dir (desktop account migration).
    fun runStartupImports(ctx: android.content.Context) { viewModelScope.launch {
        try { val dir = ctx.getExternalFilesDir(null) ?: return@launch
            val acctFile = java.io.File(dir, "blindrss-account-import.json")
            if (acctFile.exists()) { val j = org.json.JSONObject(acctFile.readText())
                val arr = j.optJSONArray("accounts")
                if (arr != null) for (i in 0 until arr.length()) { val o = arr.getJSONObject(i)
                    val t = runCatching { ProviderType.valueOf(o.optString("type")) }.getOrNull() ?: continue
                    repo.settings.saveAccount(t, org.blindrss.android.settings.SettingsStore.Account(o.optString("url"), o.optString("user"), o.optString("secret"), o.optString("appId"), o.optString("appKey"), o.optString("refresh"))) }
                j.optString("active").takeIf { it.isNotEmpty() }?.let { a -> val t = runCatching { ProviderType.valueOf(a) }.getOrNull(); if (t != null) { val acct = repo.settings.account(t); repo.settings.saveProvider(t, acct.url, acct.user, acct.secret, acct.appId, acct.appKey, acct.refresh) } }
                if (j.has("playbackSpeed")) repo.settings.setSpeed(j.optDouble("playbackSpeed", 1.0).toFloat())
                if (j.has("notify")) repo.settings.setNotify(j.optBoolean("notify", true))
                if (j.has("skipSilence")) repo.settings.setSkipSilence(j.optBoolean("skipSilence", true))
                if (j.has("sponsorBlock")) repo.settings.setSponsorBlock(j.optBoolean("sponsorBlock", true))
                acctFile.delete()
                try { announce(repo.syncProvider()) } catch (e: Exception) { announce("Account imported. Sync failed: ${e.message}") }
            }
            val opmlFile = java.io.File(dir, "blindrss-feeds.opml")
            if (opmlFile.exists()) { val n = repo.importOpml(opmlFile.readText()); opmlFile.delete(); if (n > 0) announce("Imported $n local feeds") }
        } catch (e: Exception) { announce("Import failed: ${e.message}") }
    } }
}
