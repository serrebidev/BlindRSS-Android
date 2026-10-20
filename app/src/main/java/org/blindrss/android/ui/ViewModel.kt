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
    fun loadFullText(a: ArticleEntity, cb: (String) -> Unit) { viewModelScope.launch { try { cb(repo.fullText(a)) } catch (e: Exception) { announce("Full text failed: ${e.message}"); cb("") } } }
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
