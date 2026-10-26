package org.blindrss.android.ui
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import org.blindrss.android.settings.ProviderType
import org.blindrss.android.settings.SettingsStore
import org.blindrss.android.sync.SyncWorker

// Shared pieces: one TalkBack stop per row, radio groups and switches announce their state.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPage(title: String, nav: NavController, vm: AppViewModel, content: LazyListScope.() -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title, Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = "Back" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.padding(pad)) { LiveAnnouncer(vm); LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), content = content) }
    }
}
@Composable private fun Heading(text: String) = Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
@Composable private fun Note(text: String) = Text(text, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
@Composable private fun Status(text: String) { if (text.isNotEmpty()) Text(text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium) }
@Composable private fun SwitchRow(label: String, checked: Boolean, summary: String? = null, onChange: (Boolean) -> Unit) =
    ListItem(headlineContent = { Text(label) }, supportingContent = summary?.let { { Text(it) } }, trailingContent = { Switch(checked, null) },
        modifier = Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange))
@Composable private fun <T> Choices(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Heading(title)
    Column(Modifier.selectableGroup()) { options.forEach { (v, label) ->
        ListItem(headlineContent = { Text(label) }, leadingContent = { RadioButton(v == selected, null) }, modifier = Modifier.selectable(v == selected, role = Role.RadioButton) { onSelect(v) }) } }
}
@Composable private fun Field(label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboard))
@Composable private fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), label = { Text(label) }, singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { IconButton({ show = !show }) { Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (show) "Hide $label" else "Show $label") } })
}
@Composable private fun WideButton(label: String, enabled: Boolean = true, onClick: () -> Unit) =
    Button(onClick, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), enabled = enabled) { Text(label) }

private val sections = listOf(
    Triple("Reading", "reading", Icons.AutoMirrored.Filled.MenuBook), Triple("Article lists", "lists", Icons.AutoMirrored.Filled.ViewList),
    Triple("Sync and notifications", "sync", Icons.Filled.Sync), Triple("Playback", "playback", Icons.Filled.PlayCircle),
    Triple("Discovery", "discovery", Icons.Filled.Explore), Triple("About", "about", Icons.Filled.Info))

@Composable
fun SettingsScreen(nav: NavController, vm: AppViewModel) {
    val s = vm.repo.settings; val scope = rememberCoroutineScope()
    val active by s.provider.collectAsState(ProviderType.LOCAL)
    var who by remember { mutableStateOf("") }
    LaunchedEffect(active) { who = s.account(active).let { it.user.ifEmpty { it.url } } }
    var addMenu by remember { mutableStateOf(false) }; var confirmSignOut by remember { mutableStateOf(false) }
    SettingsPage("Settings", nav, vm) {
        item { Heading("Account") }
        item { ListItem(headlineContent = { Text(active.label) }, leadingContent = { Icon(Icons.Filled.AccountCircle, null) },
            supportingContent = { Text(if (active == ProviderType.LOCAL) "Feeds refresh on this device" else who.ifEmpty { "Signed in" }) },
            modifier = Modifier.semantics(mergeDescendants = true) {}) }
        if (active != ProviderType.LOCAL) item { Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ vm.syncNow() }) { Text("Sync now") }
            OutlinedButton({ nav.navigate("settings/provider/${active.name}") }) { Text("Edit") }
            OutlinedButton({ confirmSignOut = true }) { Text("Sign out") } } }
        item { Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Button({ addMenu = true }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("Add provider") }
            DropdownMenu(addMenu, { addMenu = false }) { ProviderType.entries.filter { it != ProviderType.LOCAL }.forEach { p ->
                DropdownMenuItem({ Text(p.label) }, { addMenu = false; nav.navigate("settings/provider/${p.name}") }) } } } }
        item { Heading("Preferences") }
        items(sections) { (label, route, icon) -> ListItem(headlineContent = { Text(label) }, leadingContent = { Icon(icon, null) }, trailingContent = { Icon(Icons.Filled.ChevronRight, null) },
            modifier = Modifier.clickable(role = Role.Button) { nav.navigate("settings/$route") }) }
    }
    if (confirmSignOut) AlertDialog(onDismissRequest = { confirmSignOut = false }, title = { Text("Sign out of ${active.label}?") }, text = { Text("Feeds already synced stay on this device.") },
        confirmButton = { TextButton({ confirmSignOut = false; scope.launch { vm.repo.signOut(); vm.announce("Signed out") } }) { Text("Sign out") } },
        dismissButton = { TextButton({ confirmSignOut = false }) { Text("Cancel") } })
}

@Composable
fun ProviderSetupScreen(nav: NavController, vm: AppViewModel, type: ProviderType) {
    val s = vm.repo.settings; val scope = rememberCoroutineScope(); val ctx = LocalContext.current
    var url by remember { mutableStateOf("") }; var user by remember { mutableStateOf("") }; var secret by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }; var appKey by remember { mutableStateOf("") }
    var hasSession by remember { mutableStateOf(false) }; var status by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    LaunchedEffect(type) { val a = s.account(type); url = a.url; user = a.user; appId = a.appId; appKey = a.appKey
        if (type == ProviderType.NEWSBLUR) hasSession = a.secret.isNotEmpty() else secret = a.secret }
    // Required fields per provider, in screen order.
    val required = when (type) {
        ProviderType.MINIFLUX -> listOf("Server URL" to url, "API key" to secret)
        ProviderType.GREADER, ProviderType.FEVER -> listOf("Server URL" to url, "Username" to user, "API password" to secret)
        ProviderType.THEOLDREADER, ProviderType.BAZQUX, ProviderType.FEEDBIN -> listOf("Email" to user, "Password" to secret)
        ProviderType.NEWSBLUR -> listOfNotNull("Username" to user, if (hasSession) null else "Password" to secret)
        ProviderType.INOREADER -> listOf("App ID" to appId, "App key" to appKey)
        ProviderType.LOCAL -> emptyList()
    }
    fun missing() = required.firstOrNull { it.second.isBlank() }?.let { "${it.first} is required." }
    SettingsPage(type.label, nav, vm) {
        when (type) {
            ProviderType.MINIFLUX -> item { Note("Create an API key in Miniflux: Settings, API keys.") }
            ProviderType.GREADER -> item { Note("URL ends in /api/greader.php. Use the API password from your FreshRSS profile.") }
            ProviderType.FEVER -> item { Note("URL ends in /api/fever.php. Use the API password from your FreshRSS profile. Fever cannot add or remove feeds; feeds added here stay on this device.") }
            ProviderType.INOREADER -> item { Note("Register an app at inoreader.com/developers. Redirect URI: blindrss://oauth") }
            ProviderType.NEWSBLUR -> if (hasSession) item { Note("Signed in. Leave password blank to keep this session.") }
            else -> {}
        }
        if (type == ProviderType.MINIFLUX || type == ProviderType.GREADER || type == ProviderType.FEVER) item { Field("Server URL", url, KeyboardType.Uri) { url = it } }
        when (type) {
            ProviderType.THEOLDREADER, ProviderType.BAZQUX, ProviderType.FEEDBIN -> item { Field("Email", user, KeyboardType.Email) { user = it } }
            ProviderType.GREADER, ProviderType.FEVER, ProviderType.NEWSBLUR -> item { Field("Username", user) { user = it } }
            else -> {}
        }
        when (type) {
            ProviderType.MINIFLUX -> item { SecretField("API key", secret) { secret = it } }
            ProviderType.GREADER, ProviderType.FEVER -> item { SecretField("API password", secret) { secret = it } }
            ProviderType.THEOLDREADER, ProviderType.BAZQUX, ProviderType.FEEDBIN, ProviderType.NEWSBLUR -> item { SecretField("Password", secret) { secret = it } }
            ProviderType.INOREADER -> { item { Field("App ID", appId) { appId = it } }; item { SecretField("App key", appKey) { appKey = it } } }
            ProviderType.LOCAL -> {}
        }
        item { Spacer(Modifier.height(8.dp))
            if (type == ProviderType.INOREADER) WideButton("Sign in with browser", !busy) {
                missing()?.let { status = it; return@WideButton }
                scope.launch { s.saveAccount(type, s.account(type).copy(appId = appId.trim(), appKey = appKey.trim()))
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(org.blindrss.android.providers.InoreaderProvider.authorizationUrl(appId.trim())))) } }
            else WideButton(if (busy) "Signing in" else "Sign in", !busy) {
                missing()?.let { status = it; return@WideButton }
                busy = true; status = "Signing in"
                scope.launch { try {
                    val result = vm.repo.signIn(type, SettingsStore.Account(url.trim(), user.trim(), secret, appId, appKey))
                    vm.announce("Signed in to ${type.label}. $result"); vm.syncNow(); nav.popBackStack()
                } catch (e: Exception) { status = "Sign-in failed. ${friendlyError(e)}"; busy = false } } } }
        item { Status(status) }
    }
}

// Feed properties: rename, move to another category, and for local feeds change the address.
@Composable
fun EditFeedScreen(nav: NavController, vm: AppViewModel, feedId: Long) {
    val active by vm.repo.settings.provider.collectAsState(ProviderType.LOCAL); val all by vm.feeds.collectAsState()
    var feed by remember { mutableStateOf<org.blindrss.android.data.FeedEntity?>(null) }
    var title by remember { mutableStateOf("") }; var category by remember { mutableStateOf("") }; var url by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    LaunchedEffect(feedId) { vm.repo.feeds.byId(feedId)?.let { feed = it; title = it.title; category = it.category; url = it.url } ?: run { status = "This feed no longer exists." } }
    val categories = remember(all) { (all.map { it.category } + "Uncategorized").distinct().sortedBy { it.lowercase() } }
    SettingsPage("Edit feed", nav, vm) {
        val f = feed
        if (f == null) { item { Status(status.ifEmpty { "Loading" }) }; return@SettingsPage }
        if (f.remoteId != null && active == ProviderType.FEVER) { item { Note("Fever cannot rename or move feeds. Change them on your server.") }; return@SettingsPage }
        item { Field("Title", title) { title = it } }
        item { Field("Category", category) { category = it }; Note("Type a new category, or pick one below.") }
        item { Choices("Existing categories", categories.map { it to it }, category.trim()) { category = it } }
        if (f.remoteId == null) item { Heading("Address"); Field("Feed address", url, KeyboardType.Uri) { url = it }; Note("A website address works too. The new address is checked before saving, and articles you already have keep their read state.") }
        else item { Note("The feed address is managed by ${active.label}.") }
        item { Spacer(Modifier.height(8.dp))
            WideButton(if (busy) "Saving" else "Save", !busy) {
                busy = true; status = "Saving"
                vm.updateFeed(f, title, category, url) { err ->
                    if (err != null) { status = "Not saved. $err"; busy = false }
                    // Only leave this screen if the user is still on it.
                    else if (nav.currentDestination?.route == "feed/edit/{id}") nav.popBackStack() } } }
        item { Status(status) }
    }
}

private fun friendlyError(e: Exception): String { val m = e.message.orEmpty()
    return when {
        "HTTP 401" in m || "HTTP 403" in m || "auth failed" in m -> "Wrong username, password or key."
        e is java.net.UnknownHostException -> "Server not found. Check the URL."
        e is IllegalArgumentException -> "Invalid server URL."
        e is java.net.SocketTimeoutException -> "Server did not respond."
        else -> m.ifEmpty { e.javaClass.simpleName }
    } }

@Composable
fun SettingsSectionScreen(nav: NavController, vm: AppViewModel, section: String) {
    val s = vm.repo.settings; val scope = rememberCoroutineScope(); val ctx = LocalContext.current
    var status by remember { mutableStateOf("") }
    when (section) {
        "reading" -> { val openMode by s.articleOpenMode.collectAsState("BROWSER"); val ytMode by s.youtubeOpenMode.collectAsState("YOUTUBE_APP"); val offline by s.saveFullText.collectAsState(false)
            SettingsPage("Reading", nav, vm) {
                item { Choices("Open articles in", listOf("BROWSER" to "Browser", "FULL_TEXT" to "BlindRSS, full text", "FEED_TEXT" to "BlindRSS, feed text"), openMode) { scope.launch { s.setArticleOpenMode(it) } } }
                item { Choices("Open YouTube videos in", listOf("YOUTUBE_APP" to "YouTube app", "SMARTTUBE" to "SmartTube", "BROWSER" to "Browser", "IN_APP" to "BlindRSS audio player"), ytMode) { scope.launch { s.setYoutubeOpenMode(it) } }
                    Note("Falls back to the browser if the app is not installed.") }
                item { Heading("Offline") }
                item { SwitchRow("Save full text for offline reading", offline, "Downloads up to 50 new unread articles per refresh. Uses more data.") { scope.launch { s.setFlag("save_full_text", it) } } }
            } }
        "lists" -> { val lp by s.listPrefs.collectAsState(ListReadingPrefs()); fun save(p: ListReadingPrefs) { scope.launch { s.setListPrefs(p) } }
            SettingsPage("Article lists", nav, vm) {
                item { Note("Order: title, source, author and date, text, read status.") }
                item { Heading("Read in lists") }
                item { SwitchRow("Source", lp.readSource) { save(lp.copy(readSource = it)) } }
                item { SwitchRow("Author", lp.readAuthor) { save(lp.copy(readAuthor = it)) } }
                item { SwitchRow("Date", lp.readDate) { save(lp.copy(readDate = it)) } }
                item { SwitchRow("Article text", lp.readText) { save(lp.copy(readText = it)) } }
                item { SwitchRow("Favorite and media flags", lp.readFlags) { save(lp.copy(readFlags = it)) } }
                if (lp.readText) item { Choices("Article text", listOf("FULL" to "Full article", "PARTIAL" to "First 600 characters", "SUMMARY" to "Summary"), lp.textAmount) { save(lp.copy(textAmount = it)) } }
                item { Choices("Read status", listOf("END" to "At the end", "START" to "After the title", "OFF" to "Off"), lp.statusPosition) { save(lp.copy(statusPosition = it)) } }
            } }
        "sync" -> { val interval by s.syncInterval.collectAsState(60); val wifi by s.wifiOnly.collectAsState(false)
            val onStart by s.refreshOnStart.collectAsState(false); val notify by s.notifyNew.collectAsState(true); val keep by s.maxPerFeed.collectAsState(0)
            val chime by s.refreshChime.collectAsState(true); val uaPreset by s.feedUaPreset.collectAsState("BLINDRSS")
            var cookies by remember { mutableStateOf("") }; var cookiesLoaded by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { cookies = s.siteCookies(); cookiesLoaded = true }
            val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (!ok) status = "Notifications are blocked. Allow them in Android settings." }
            SettingsPage("Sync and notifications", nav, vm) {
                item { Choices("Sync in background", listOf(0 to "Off", 15 to "Every 15 minutes", 30 to "Every 30 minutes", 60 to "Every hour", 240 to "Every 4 hours", 720 to "Every 12 hours"), interval) {
                    scope.launch { s.setSyncInterval(it); SyncWorker.schedule(ctx) } } }
                item { Heading("Options") }
                item { SwitchRow("Wi-Fi only", wifi) { scope.launch { s.setFlag("sync_wifi_only", it); SyncWorker.schedule(ctx) } } }
                item { SwitchRow("Refresh when app opens", onStart) { scope.launch { s.setFlag("refresh_on_start", it) } } }
                item { SwitchRow("Refresh complete sound", chime, "Plays a short sound when a refresh you started finishes.") { scope.launch { s.setFlag("refresh_chime", it) } } }
                item { Choices("Browser identity for feeds", org.blindrss.android.settings.FetchIdentity.PRESETS, uaPreset) { scope.launch { s.setFeedUaPreset(it) } }
                    Note("Some sites only serve feeds to a browser identity. Provider accounts are not affected.") }
                item { Heading("Clearance cookies") }
                item { Note("Paste a Cookie header from your browser for sites behind bot protection. It is stored encrypted and sent only with feed and article fetches.") }
                item { SecretField("Site cookies", cookies) { cookies = it } }
                item { WideButton("Save cookies", cookiesLoaded) { scope.launch { s.setSiteCookies(cookies); status = "Cookies saved" } } }
                item { Choices("Keep per feed", listOf(0 to "All articles", 100 to "Newest 100", 250 to "Newest 250", 500 to "Newest 500", 1000 to "Newest 1000"), keep) { scope.launch { s.setMaxPerFeed(it) } }
                    Note("Older articles are deleted after each refresh. Favorites are kept.") }
                item { Heading("Notifications") }
                item { SwitchRow("New articles", notify, "Mute single feeds from a feed's actions.") { on -> scope.launch { s.setNotify(on) }
                    if (on && android.os.Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        perm.launch(android.Manifest.permission.POST_NOTIFICATIONS) } }
                item { Status(status) }
            } }
        "playback" -> { val speed by s.playbackSpeed.collectAsState(1f); val silence by s.skipSilence.collectAsState(true); val sponsor by s.sponsorBlock.collectAsState(true); val catDl by s.categoryDownloads.collectAsState(false)
            SettingsPage("Playback", nav, vm) {
                item { Choices("Default speed", listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { it to "${it}x" }, speed) { vm.player.setSpeed(it); scope.launch { s.setSpeed(it) } } }
                item { Heading("Audio") }
                item { SwitchRow("Skip silence", silence) { vm.player.setSkipSilence(it) } }
                item { SwitchRow("SponsorBlock", sponsor, "Skips sponsor segments in YouTube videos.") { scope.launch { s.setSponsorBlock(it) } } }
                item { Heading("Downloads") }
                item { SwitchRow("Download whole categories", catDl, "Adds a Download category action to categories on the home screen. Downloads every unplayed episode.") { scope.launch { s.setFlag("category_downloads", it) } } }
                item { Note("Equalizer and sleep timer are in the player.") }
            } }
        "discovery" -> { var newsApi by remember { mutableStateOf("") }; var theNewsApi by remember { mutableStateOf("") }
            LaunchedEffect(Unit) { newsApi = s.newsApiKey(); theNewsApi = s.theNewsApiKey() }
            SettingsPage("Discovery", nav, vm) {
                item { Note("Optional keys. They add news publishers to Discover. Country, language and city are set in Discover.") }
                item { SecretField("NewsAPI.org key", newsApi) { newsApi = it } }
                item { SecretField("The News API token", theNewsApi) { theNewsApi = it } }
                item { WideButton("Save keys") { scope.launch { s.setApiKeys(newsApi.trim(), theNewsApi.trim()); status = "Keys saved" } } }
                item { Status(status) }
            } }
        else -> { val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "unknown" }
            var newer by remember { mutableStateOf<String?>(null) }
            SettingsPage("About", nav, vm) {
                item { ListItem(headlineContent = { Text("BlindRSS $version") }, supportingContent = { Text("Accessible RSS and podcast reader") }, modifier = Modifier.semantics(mergeDescendants = true) {}) }
                item { WideButton("Open the user guide") { nav.navigate("help") } }
                item { WideButton("Check for updates") { scope.launch { status = "Checking"; val latest = org.blindrss.android.feed.UpdateCheck.latestVersion()
                    newer = latest?.takeIf { it != version }
                    status = when { latest == null -> "Update check failed. Are you online?"; newer == null -> "Up to date."; else -> "Version $latest is available." } } } }
                item { var ytdl by remember { mutableStateOf("") }; LaunchedEffect(Unit) { if (org.blindrss.android.playback.Ytdl.supported()) vm.ytdlVersion { ytdl = it } }
                    if (org.blindrss.android.playback.Ytdl.supported()) {
                        ListItem(headlineContent = { Text("yt-dlp (nightly)") }, supportingContent = { Text(ytdl.ifEmpty { "Starts on first use" }) }, modifier = Modifier.semantics(mergeDescendants = true) {})
                        WideButton("Update yt-dlp") { status = "Updating yt-dlp"; vm.updateYtdl { status = it; vm.ytdlVersion { v -> ytdl = v } } }
                    } else Note("YouTube audio needs Android 11 or newer on a 64-bit device.") }
                if (newer != null) item { WideButton("Download update") { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/serrebidev/BlindRSS-Android/releases/latest"))) } }
                item { Status(status) }
            } }
    }
}
