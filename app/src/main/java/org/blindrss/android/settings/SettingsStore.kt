package org.blindrss.android.settings
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.ds by preferencesDataStore("blindrss_settings")

enum class ProviderType(val label: String) {
    LOCAL("This device"), MINIFLUX("Miniflux"), INOREADER("Inoreader"), THEOLDREADER("The Old Reader"), BAZQUX("BazQux"), FEEDBIN("Feedbin"),
    GREADER("FreshRSS (Google Reader API)"), FEVER("Fever API"), NEWSBLUR("NewsBlur")
}

class SettingsStore(private val ctx: Context) {
    companion object {
        val PROVIDER = stringPreferencesKey("provider")
        val SERVER_URL = stringPreferencesKey("server_url")
        val USERNAME = stringPreferencesKey("username")
        val SECRET = stringPreferencesKey("secret_enc")
        val APP_ID = stringPreferencesKey("app_id")
        val APP_KEY = stringPreferencesKey("app_key")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token_enc")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val SYNC_INTERVAL_MIN = intPreferencesKey("sync_interval_min")
        val NOTIFY_NEW = booleanPreferencesKey("notify_new")
    }
    val provider: Flow<ProviderType> = ctx.ds.data.map { runCatching { ProviderType.valueOf(it[PROVIDER] ?: "LOCAL") }.getOrDefault(ProviderType.LOCAL) }
    val serverUrl: Flow<String> = ctx.ds.data.map { it[SERVER_URL] ?: "" }
    val username: Flow<String> = ctx.ds.data.map { it[USERNAME] ?: "" }
    val playbackSpeed: Flow<Float> = ctx.ds.data.map { it[PLAYBACK_SPEED] ?: 1f }
    val syncInterval: Flow<Int> = ctx.ds.data.map { it[SYNC_INTERVAL_MIN] ?: 60 }
    val notifyNew: Flow<Boolean> = ctx.ds.data.map { it[NOTIFY_NEW] ?: true }
    suspend fun secret(): String = ctx.ds.data.map { it[SECRET] ?: "" }.first().let { if (it.isEmpty()) "" else decrypt(it) }
    suspend fun refreshToken(): String = ctx.ds.data.map { it[REFRESH_TOKEN] ?: "" }.first().let { if (it.isEmpty()) "" else decrypt(it) }
    suspend fun appId(): String = ctx.ds.data.map { it[APP_ID] ?: "" }.first()
    suspend fun appKey(): String = ctx.ds.data.map { it[APP_KEY] ?: "" }.first()
    suspend fun saveProvider(p: ProviderType, url: String, user: String, secret: String, appId: String = "", appKey: String = "", refresh: String = "") {
        ctx.ds.edit { it[PROVIDER] = p.name; it[SERVER_URL] = url.trimEnd('/'); it[USERNAME] = user; it[SECRET] = if (secret.isEmpty()) "" else encrypt(secret); it[APP_ID] = appId; it[APP_KEY] = appKey; it[REFRESH_TOKEN] = if (refresh.isEmpty()) "" else encrypt(refresh) }
    }
    suspend fun setActive(p: ProviderType) { ctx.ds.edit { it[PROVIDER] = p.name } }
    fun flag(name: String, default: Boolean): Flow<Boolean> = ctx.ds.data.map { it[booleanPreferencesKey(name)] ?: default }
    suspend fun setFlag(name: String, v: Boolean) { ctx.ds.edit { it[booleanPreferencesKey(name)] = v } }
    val wifiOnly = flag("sync_wifi_only", false)
    val refreshOnStart = flag("refresh_on_start", false)
    val saveFullText = flag("save_full_text", false)
    // Off by default so a stray gesture cannot start dozens of downloads.
    val categoryDownloads = flag("category_downloads", false)
    // Articles kept per feed; 0 keeps everything.
    val maxPerFeed: Flow<Int> = ctx.ds.data.map { it[intPreferencesKey("max_per_feed")] ?: 0 }
    suspend fun setMaxPerFeed(v: Int) { ctx.ds.edit { it[intPreferencesKey("max_per_feed")] = v } }
    // Read/favorite changes not yet accepted by the provider: "R|articleId|1", "F|articleId|0".
    private val OUTBOX = stringSetPreferencesKey("outbox")
    val outbox: Flow<Set<String>> = ctx.ds.data.map { it[OUTBOX] ?: emptySet() }
    suspend fun editOutbox(f: (Set<String>) -> Set<String>) { ctx.ds.edit { it[OUTBOX] = f(it[OUTBOX] ?: emptySet()) } }
    // Up next queue (article ids) and the current position in it, restored at launch.
    suspend fun saveQueue(ids: List<String>, index: Int) { ctx.ds.edit { it[stringPreferencesKey("queue")] = ids.joinToString(","); it[intPreferencesKey("queue_index")] = index } }
    suspend fun savedQueue(): Pair<List<String>, Int> { val p = ctx.ds.data.first(); return (p[stringPreferencesKey("queue")] ?: "").split(",").filter { it.isNotBlank() } to (p[intPreferencesKey("queue_index")] ?: 0) }
    // Self-update: check GitHub releases on start (throttled), toggleable.
    val autoUpdateCheck = flag("auto_update_check", true)
    val updateLastCheck: Flow<Long> = ctx.ds.data.map { it[longPreferencesKey("update_last_check")] ?: 0L }
    suspend fun setUpdateLastCheck(v: Long) { ctx.ds.edit { it[longPreferencesKey("update_last_check")] = v } }
    val minifluxCursor: Flow<Long> = ctx.ds.data.map { it[longPreferencesKey("miniflux_cursor")] ?: 0L }
    suspend fun setMinifluxCursor(v: Long) { ctx.ds.edit { it[longPreferencesKey("miniflux_cursor")] = v } }
    suspend fun setSpeed(v: Float) { ctx.ds.edit { it[PLAYBACK_SPEED] = v } }
    suspend fun setSyncInterval(v: Int) { ctx.ds.edit { it[SYNC_INTERVAL_MIN] = v } }
    suspend fun setNotify(v: Boolean) { ctx.ds.edit { it[NOTIFY_NEW] = v } }
    // Per-provider saved accounts, so switching providers keeps each login.
    data class Account(val url: String = "", val user: String = "", val secret: String = "", val appId: String = "", val appKey: String = "", val refresh: String = "")
    private fun acctKey(t: ProviderType, f: String) = stringPreferencesKey("acct_${t.name}_$f")
    suspend fun account(t: ProviderType): Account { val p = ctx.ds.data.first()
        fun dec(k: String): String { val v = p[acctKey(t, k)] ?: ""; return if (v.isEmpty()) "" else decrypt(v) }
        return Account(p[acctKey(t, "url")] ?: "", p[acctKey(t, "user")] ?: "", dec("secret"), p[acctKey(t, "appid")] ?: "", p[acctKey(t, "appkey")] ?: "", dec("refresh")) }
    suspend fun saveAccount(t: ProviderType, a: Account) { ctx.ds.edit { it[acctKey(t, "url")] = a.url.trimEnd('/'); it[acctKey(t, "user")] = a.user
        it[acctKey(t, "secret")] = if (a.secret.isEmpty()) "" else encrypt(a.secret); it[acctKey(t, "appid")] = a.appId; it[acctKey(t, "appkey")] = a.appKey
        it[acctKey(t, "refresh")] = if (a.refresh.isEmpty()) "" else encrypt(a.refresh) } }
    // How articles open on tap. Browser is the default.
    val articleOpenMode: Flow<String> = ctx.ds.data.map { it[stringPreferencesKey("article_open_mode")] ?: "BROWSER" }
    suspend fun setArticleOpenMode(v: String) { ctx.ds.edit { it[stringPreferencesKey("article_open_mode")] = v } }
    // Where YouTube links open: YOUTUBE_APP, SMARTTUBE, BROWSER, IN_APP.
    val youtubeOpenMode: Flow<String> = ctx.ds.data.map { it[stringPreferencesKey("youtube_open_mode")] ?: "YOUTUBE_APP" }
    suspend fun setYoutubeOpenMode(v: String) { ctx.ds.edit { it[stringPreferencesKey("youtube_open_mode")] = v } }
    // Discovery preferences and optional directory API keys (keys encrypted).
    val discoveryCountry: Flow<String> = ctx.ds.data.map { it[stringPreferencesKey("discovery_country")] ?: "CA" }
    val discoveryLanguage: Flow<String> = ctx.ds.data.map { it[stringPreferencesKey("discovery_language")] ?: "en" }
    val discoveryCity: Flow<String> = ctx.ds.data.map { it[stringPreferencesKey("discovery_city")] ?: "" }
    val discoveryRegion: Flow<String> = ctx.ds.data.map { it[stringPreferencesKey("discovery_region")] ?: "" }
    suspend fun setDiscoveryLocale(country: String, language: String, city: String, region: String) { ctx.ds.edit { it[stringPreferencesKey("discovery_country")] = country; it[stringPreferencesKey("discovery_language")] = language; it[stringPreferencesKey("discovery_city")] = city; it[stringPreferencesKey("discovery_region")] = region } }
    suspend fun newsApiKey(): String { val v = ctx.ds.data.first()[stringPreferencesKey("newsapi_key_enc")] ?: ""; return if (v.isEmpty()) "" else decrypt(v) }
    suspend fun theNewsApiKey(): String { val v = ctx.ds.data.first()[stringPreferencesKey("thenewsapi_key_enc")] ?: ""; return if (v.isEmpty()) "" else decrypt(v) }
    suspend fun setApiKeys(newsApi: String, theNewsApi: String) { ctx.ds.edit { it[stringPreferencesKey("newsapi_key_enc")] = if (newsApi.isEmpty()) "" else encrypt(newsApi); it[stringPreferencesKey("thenewsapi_key_enc")] = if (theNewsApi.isEmpty()) "" else encrypt(theNewsApi) } }
    // How article lists are read (Settings > Reading).
    val listPrefs: Flow<org.blindrss.android.ui.ListReadingPrefs> = ctx.ds.data.map { p ->
        org.blindrss.android.ui.ListReadingPrefs(
            readSource = p[booleanPreferencesKey("list_read_source")] ?: true,
            readAuthor = p[booleanPreferencesKey("list_read_author")] ?: true,
            readDate = p[booleanPreferencesKey("list_read_date")] ?: true,
            readText = p[booleanPreferencesKey("list_read_text")] ?: true,
            textAmount = p[stringPreferencesKey("list_text_amount")] ?: "FULL",
            readFlags = p[booleanPreferencesKey("list_read_flags")] ?: true,
            statusPosition = p[stringPreferencesKey("list_status_position")] ?: "END") }
    suspend fun setListPrefs(prefs: org.blindrss.android.ui.ListReadingPrefs) { ctx.ds.edit { it[booleanPreferencesKey("list_read_source")] = prefs.readSource; it[booleanPreferencesKey("list_read_author")] = prefs.readAuthor; it[booleanPreferencesKey("list_read_date")] = prefs.readDate; it[booleanPreferencesKey("list_read_text")] = prefs.readText; it[stringPreferencesKey("list_text_amount")] = prefs.textAmount; it[booleanPreferencesKey("list_read_flags")] = prefs.readFlags; it[stringPreferencesKey("list_status_position")] = prefs.statusPosition } }
    // Fetch identity for bot-protected feeds/pages (Settings > Sync and notifications > Fetching).
    val feedUaPreset: Flow<String> = ctx.ds.data.map { it[stringPreferencesKey("feed_ua_preset")] ?: "BLINDRSS" }
    suspend fun setFeedUaPreset(v: String) { ctx.ds.edit { it[stringPreferencesKey("feed_ua_preset")] = v } }
    suspend fun siteCookies(): String { val v = ctx.ds.data.first()[stringPreferencesKey("site_cookies_enc")] ?: ""; return if (v.isEmpty()) "" else decrypt(v) }
    suspend fun setSiteCookies(raw: String) { val clean = FetchIdentity.sanitizeCookies(raw); ctx.ds.edit { it[stringPreferencesKey("site_cookies_enc")] = if (clean.isEmpty()) "" else encrypt(clean) } }
    val refreshChime = flag("refresh_chime", true)
    // Article retention by age in days; 0 keeps everything (desktop retention).
    val retentionDays: Flow<Int> = ctx.ds.data.map { it[intPreferencesKey("retention_days")] ?: 0 }
    suspend fun setRetentionDays(v: Int) { ctx.ds.edit { it[intPreferencesKey("retention_days")] = v } }
    // Delete behaviour: TOMBSTONE (Deleted Articles, default), PERMANENT, or
    // MOVE to a category; optional confirmation prompt before deleting.
    val deleteBehaviour: Flow<String> = ctx.ds.data.map { it[stringPreferencesKey("delete_behaviour")] ?: "TOMBSTONE" }
    val deleteMoveTarget: Flow<String> = ctx.ds.data.map { it[stringPreferencesKey("delete_move_target")] ?: "Uncategorized" }
    val confirmDelete = flag("confirm_delete", false)
    suspend fun setDeleteBehaviour(v: String, target: String) { ctx.ds.edit { it[stringPreferencesKey("delete_behaviour")] = v; it[stringPreferencesKey("delete_move_target")] = target } }
    // Search scope: titles only, or titles and text (default).
    val searchTitlesOnly = flag("search_titles_only", false)
    // Reader presentation toggles (desktop/iOS; all off by default).
    val readerBullets = flag("reader_bullets", false)
    val readerQuotes = flag("reader_quotes", false)
    val readerLinkTargets = flag("reader_link_targets", false)
    val readerImageAlt = flag("reader_image_alt", false)
    // Groups.io API key (Settings > Sync), encrypted like the other keys:
    // unlocks member-only group archives in ForumText via api.groups.io.
    suspend fun groupsioApiKey(): String { val v = ctx.ds.data.first()[stringPreferencesKey("groupsio_key_enc")] ?: ""; return if (v.isEmpty()) "" else decrypt(v) }
    suspend fun setGroupsioApiKey(v: String) { ctx.ds.edit { it[stringPreferencesKey("groupsio_key_enc")] = if (v.isEmpty()) "" else encrypt(v) } }
    // YouTube account (SmartTube-style device-code sign-in): the refresh
    // token and the scraped TV client constants, all encrypted.
    suspend fun youtubeRefreshToken(): String { val v = ctx.ds.data.first()[stringPreferencesKey("yt_refresh_enc")] ?: ""; return if (v.isEmpty()) "" else decrypt(v) }
    suspend fun youtubeClientConstants(): Pair<String, String> { val p = ctx.ds.data.first()
        fun dec(k: String): String { val v = p[stringPreferencesKey(k)] ?: ""; return if (v.isEmpty()) "" else decrypt(v) }
        return dec("yt_client_id_enc") to dec("yt_client_secret_enc") }
    val youtubeAccountName: Flow<String> = ctx.ds.data.map { it[stringPreferencesKey("yt_account_name")] ?: "" }
    suspend fun saveYoutubeSession(refreshToken: String, clientId: String, clientSecret: String, accountName: String) { ctx.ds.edit {
        it[stringPreferencesKey("yt_refresh_enc")] = if (refreshToken.isEmpty()) "" else encrypt(refreshToken)
        it[stringPreferencesKey("yt_client_id_enc")] = if (clientId.isEmpty()) "" else encrypt(clientId)
        it[stringPreferencesKey("yt_client_secret_enc")] = if (clientSecret.isEmpty()) "" else encrypt(clientSecret)
        it[stringPreferencesKey("yt_account_name")] = accountName } }
    suspend fun clearYoutubeSession() { ctx.ds.edit { it.remove(stringPreferencesKey("yt_refresh_enc")); it.remove(stringPreferencesKey("yt_client_id_enc")); it.remove(stringPreferencesKey("yt_client_secret_enc")); it.remove(stringPreferencesKey("yt_account_name")) } }
    // Last content route, restored at launch (desktop remember-last-feed).
    val restoreLastView = flag("restore_last_view", true)
    val lastRoute: Flow<String> = ctx.ds.data.map { it[stringPreferencesKey("last_route")] ?: "" }
    suspend fun setLastRoute(v: String) { ctx.ds.edit { it[stringPreferencesKey("last_route")] = v } }
    val skipSilence: Flow<Boolean> = ctx.ds.data.map { it[booleanPreferencesKey("skip_silence")] ?: true }
    val sponsorBlock: Flow<Boolean> = ctx.ds.data.map { it[booleanPreferencesKey("sponsorblock")] ?: true }
    val eqEnabled: Flow<Boolean> = ctx.ds.data.map { it[booleanPreferencesKey("eq_enabled")] ?: false }
    val eqBands: Flow<List<Int>> = ctx.ds.data.map { p -> (p[stringPreferencesKey("eq_bands")] ?: "").split(",").mapNotNull { it.toIntOrNull() } }
    suspend fun setSkipSilence(v: Boolean) { ctx.ds.edit { it[booleanPreferencesKey("skip_silence")] = v } }
    suspend fun setSponsorBlock(v: Boolean) { ctx.ds.edit { it[booleanPreferencesKey("sponsorblock")] = v } }
    suspend fun setEq(enabled: Boolean, bands: List<Int>) { ctx.ds.edit { it[booleanPreferencesKey("eq_enabled")] = enabled; it[stringPreferencesKey("eq_bands")] = bands.joinToString(",") } }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("blindrss_key", null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder("blindrss_key", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return gen.generateKey()
    }
    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(c.iv + ct)
    }
    private fun decrypt(b64: String): String = try {
        val raw = Base64.getDecoder().decode(b64); val iv = raw.copyOfRange(0, 12); val ct = raw.copyOfRange(12, raw.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(c.doFinal(ct), Charsets.UTF_8)
    } catch (_: Exception) { "" }
}
