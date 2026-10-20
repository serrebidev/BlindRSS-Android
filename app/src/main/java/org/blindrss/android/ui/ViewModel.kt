package org.blindrss.android.ui
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.blindrss.android.data.*
import org.blindrss.android.playback.Downloader
import org.blindrss.android.playback.PlayerController
import org.blindrss.android.settings.ProviderType
import org.blindrss.android.sync.SyncWorker

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val repo = Repository(app)
    val player = PlayerController(app, repo)
    val announcements = MutableStateFlow("")
    private fun announce(msg: String) { announcements.value = msg }
    val feeds: StateFlow<List<FeedEntity>> = repo.feeds.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val errorFeeds: StateFlow<List<FeedEntity>> = repo.feeds.observeErrors().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val unreadCount: StateFlow<Int> = repo.articles.unreadCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun addFeed(url: String, category: String = "Uncategorized") { viewModelScope.launch { try { val f = repo.addFeed(url, category); announce("Subscribed to ${f.title}") } catch (e: Exception) { announce("Error adding feed: ${e.message}") } } }
    fun refreshAll() { viewModelScope.launch { var total = 0; var errs = 0; for (f in repo.feeds.all()) { try { total += repo.refreshFeed(f) } catch (_: Exception) { errs++ } }; try { repo.syncProvider() } catch (_: Exception) {}; announce(if (errs > 0) "Refresh complete. $total new articles. $errs feeds with errors." else "Refresh complete. $total new articles."); SyncWorker.schedule(getApplication(), repo.settings.syncInterval.first()) } }
    fun refreshFeed(f: FeedEntity) { viewModelScope.launch { try { val n = repo.refreshFeed(f); announce("Refreshed ${f.title}. $n new articles.") } catch (e: Exception) { announce("Error refreshing ${f.title}: ${e.message}") } } }
    fun deleteFeed(f: FeedEntity) { viewModelScope.launch { repo.feeds.delete(f); announce("Deleted feed ${f.title}") } }
    fun toggleMute(f: FeedEntity) { viewModelScope.launch { repo.feeds.update(f.copy(muted = !f.muted)); announce(if (f.muted) "Notifications on for ${f.title}" else "Notifications muted for ${f.title}") } }
    fun setRead(a: ArticleEntity, read: Boolean) { viewModelScope.launch { repo.articles.setRead(a.id, read); repo.pushRead(a, read); announce(if (read) "Marked read: ${a.title}" else "Marked unread: ${a.title}") } }
    fun toggleFavorite(a: ArticleEntity) { viewModelScope.launch { repo.articles.setFavorite(a.id, !a.isFavorite); repo.pushFavorite(a, !a.isFavorite); announce(if (!a.isFavorite) "Favorited: ${a.title}" else "Removed favorite: ${a.title}") } }
    fun deleteArticle(a: ArticleEntity) { viewModelScope.launch { repo.deleteArticle(a); announce("Deleted: ${a.title}. Available in Deleted Articles.") } }
    fun restoreDeleted(id: String) { viewModelScope.launch { repo.restoreDeleted(id); announce("Article restored") } }
    fun purgeDeleted(id: String) { viewModelScope.launch { repo.misc.purgeDeleted(id); announce("Article permanently deleted") } }
    fun purgeAllDeleted() { viewModelScope.launch { repo.misc.purgeAllDeleted(); announce("All deleted articles purged") } }
    fun markAllRead() { viewModelScope.launch { repo.articles.markAllRead(); announce("All articles marked read") } }
    fun markFeedRead(f: FeedEntity) { viewModelScope.launch { repo.articles.markFeedRead(f.id); announce("Marked all read in ${f.title}") } }
    fun importOpml(xml: String) { viewModelScope.launch { val n = repo.importOpml(xml); announce("Imported $n feeds from OPML") } }
    fun play(a: ArticleEntity) { viewModelScope.launch { val url = a.mediaUrl ?: a.url; player.loadSponsorBlock(a.url); player.play(a.id, url, a.title); announce("Playing: ${a.title}") } }
    fun download(a: ArticleEntity) { viewModelScope.launch { val url = a.mediaUrl ?: return@launch announce("No media to download"); try { Downloader.downloadEpisode(getApplication(), repo, a.id, url); announce("Downloaded episode: ${a.title}") } catch (e: Exception) { announce("Download failed: ${e.message}") } } }
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
                    "IN_APP" -> nav.navigate("reader/${android.net.Uri.encode(a.id)}")
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
        val local = org.blindrss.android.discovery.AwesomeCatalogue.search(catalogue, "", category, if (category == "Canada" || category == "Local news") country else "", "")
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
        if (s.subscribed) { repo.feeds.all().firstOrNull { org.blindrss.android.discovery.UrlSafety.canonicalFeedUrl(it.url) == org.blindrss.android.discovery.UrlSafety.canonicalFeedUrl(s.feedUrl ?: "") }?.let { repo.feeds.delete(it) }
            discoverResults.value = discoverResults.value.map { if (it.feedUrl == s.feedUrl) it.copy(subscribed = false) else it }; announce("Unsubscribed from ${s.name}") }
        else subscribeDiscover(listOf(s)) } }
    fun exchangeOAuth(code: String) { viewModelScope.launch { try { announce(repo.exchangeInoreaderCode(code)); val s = repo.syncProvider(); announce(s) } catch (e: Exception) { announce("Inoreader connect failed: ${e.message}") } } }
    val castDevices = MutableStateFlow<List<org.blindrss.android.cast.DlnaDevice>>(emptyList())
    var castTarget: org.blindrss.android.cast.DlnaDevice? = null
    fun discoverCast(ctx: android.content.Context) { viewModelScope.launch { announce("Searching for DLNA renderers"); castDevices.value = org.blindrss.android.cast.Dlna.discover(ctx); announce("Found ${castDevices.value.size} DLNA devices") } }
    fun castCurrent(device: org.blindrss.android.cast.DlnaDevice) { viewModelScope.launch { val id = player.currentId() ?: return@launch announce("Nothing playing to cast"); val a = repo.articles.byId(id) ?: return@launch announce("Nothing playing to cast"); val url = a.mediaUrl ?: return@launch announce("Current article has no media"); val ok = org.blindrss.android.cast.Dlna.play(device, url, a.title); castTarget = device; player.player?.pause(); announce(if (ok) "Casting ${a.title} to ${device.name}" else "Cast failed to ${device.name}") } }
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
