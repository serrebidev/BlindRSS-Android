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
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import org.blindrss.android.R
import org.blindrss.android.settings.ProviderType
import org.blindrss.android.settings.SettingsStore
import org.blindrss.android.sync.SyncWorker

// Shared pieces: one TalkBack stop per row, radio groups and switches announce their state.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPage(title: String, nav: NavController, vm: AppViewModel, content: LazyListScope.() -> Unit) {
    val ctx = LocalContext.current
    Scaffold(topBar = { TopAppBar(title = { Text(title, Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = ctx.getString(R.string.back) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
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

private fun sectionTitleRes(route: String): Int = when (route) {
    "reading" -> R.string.settings_reading; "lists" -> R.string.settings_lists
    "sync" -> R.string.settings_sync; "playback" -> R.string.settings_playback
    "discovery" -> R.string.settings_discovery; else -> R.string.settings_about
}
private val sections = listOf(
    Triple("Reading", "reading", Icons.AutoMirrored.Filled.MenuBook), Triple("Article lists", "lists", Icons.AutoMirrored.Filled.ViewList),
    Triple("Sync and notifications", "sync", Icons.Filled.Sync), Triple("Playback", "playback", Icons.Filled.PlayCircle),
    Triple("Discovery", "discovery", Icons.Filled.Explore), Triple("About", "about", Icons.Filled.Info))

@Composable
fun SettingsScreen(nav: NavController, vm: AppViewModel) {
    val ctx = LocalContext.current
    val s = vm.repo.settings; val scope = rememberCoroutineScope()
    val active by s.provider.collectAsState(ProviderType.LOCAL)
    var who by remember { mutableStateOf("") }
    LaunchedEffect(active) { who = try { s.account(active).let { it.user.ifEmpty { it.url } } } catch (_: Exception) { "" } }
    var addMenu by remember { mutableStateOf(false) }; var confirmSignOut by remember { mutableStateOf(false) }
    SettingsPage("Settings", nav, vm) {
        item { Heading(stringResource(R.string.account)) }
        item { ListItem(headlineContent = { Text(active.label) }, leadingContent = { Icon(Icons.Filled.AccountCircle, null) },
            supportingContent = { Text(if (active == ProviderType.LOCAL) "Feeds refresh on this device" else who.ifEmpty { "Signed in" }) },
            modifier = Modifier.semantics(mergeDescendants = true) {}) }
        if (active != ProviderType.LOCAL) item { Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ vm.syncNow() }) { Text(stringResource(R.string.sync_now)) }
            OutlinedButton({ nav.navigate("settings/provider/${active.name}") }) { Text(stringResource(R.string.edit)) }
            OutlinedButton({ confirmSignOut = true }) { Text(stringResource(R.string.sign_out)) } } }
        item { Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Button({ addMenu = true }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.add_provider)) }
            DropdownMenu(addMenu, { addMenu = false }) { ProviderType.entries.filter { it != ProviderType.LOCAL }.forEach { p ->
                DropdownMenuItem({ Text(p.label) }, { addMenu = false; nav.navigate("settings/provider/${p.name}") }) } } } }
        item { val ytName by vm.repo.settings.youtubeAccountName.collectAsState("")
            ListItem(headlineContent = { Text(stringResource(R.string.youtube_account)) }, leadingContent = { Icon(Icons.Filled.PlayCircle, null) },
            supportingContent = { Text(if (ytName.isBlank()) "Not signed in" else "Signed in. Recommendations and subscriptions on the YouTube home view.") },
            trailingContent = { Icon(Icons.Filled.ChevronRight, null) },
            modifier = Modifier.clickable(role = Role.Button) { nav.navigate("ytaccount") }.semantics(mergeDescendants = true) { contentDescription = ctx.getString(R.string.youtube_account) }) }
        item { Heading(stringResource(R.string.preferences)) }
        items(sections) { (label, route, icon) -> ListItem(headlineContent = { Text(stringResource(sectionTitleRes(route))) }, leadingContent = { Icon(icon, null) }, trailingContent = { Icon(Icons.Filled.ChevronRight, null) },
            modifier = Modifier.clickable(role = Role.Button) { nav.navigate("settings/$route") }) }
    }
    if (confirmSignOut) AlertDialog(onDismissRequest = { confirmSignOut = false }, title = { Text("Sign out of ${active.label}?") }, text = { Text(stringResource(R.string.feeds_already_synced_stay_on)) },
        confirmButton = { TextButton({ confirmSignOut = false; scope.launch { vm.repo.signOut(); vm.announce("Signed out") } }) { Text(stringResource(R.string.sign_out)) } },
        dismissButton = { TextButton({ confirmSignOut = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
fun ProviderSetupScreen(nav: NavController, vm: AppViewModel, type: ProviderType) {
    val s = vm.repo.settings; val scope = rememberCoroutineScope(); val ctx = LocalContext.current
    var url by remember { mutableStateOf("") }; var user by remember { mutableStateOf("") }; var secret by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }; var appKey by remember { mutableStateOf("") }
    var hasSession by remember { mutableStateOf(false) }; var status by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    LaunchedEffect(type) { try { val a = s.account(type); url = a.url; user = a.user; appId = a.appId; appKey = a.appKey
        if (type == ProviderType.NEWSBLUR) hasSession = a.secret.isNotEmpty() else secret = a.secret } catch (_: Exception) {} }
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
            ProviderType.MINIFLUX -> item { Note(stringResource(R.string.create_an_api_key_in)) }
            ProviderType.GREADER -> item { Note(stringResource(R.string.url_ends_in_api_greader)) }
            ProviderType.FEVER -> item { Note(stringResource(R.string.url_ends_in_api_fever)) }
            ProviderType.INOREADER -> item { Note(stringResource(R.string.register_an_app_at_inoreader)) }
            ProviderType.NEWSBLUR -> if (hasSession) item { Note(stringResource(R.string.signed_in_leave_password_blank)) }
            else -> {}
        }
        if (type == ProviderType.MINIFLUX || type == ProviderType.GREADER || type == ProviderType.FEVER) item { Field(stringResource(R.string.server_url), url, KeyboardType.Uri) { url = it } }
        when (type) {
            ProviderType.THEOLDREADER, ProviderType.BAZQUX, ProviderType.FEEDBIN -> item { Field(stringResource(R.string.email), user, KeyboardType.Email) { user = it } }
            ProviderType.GREADER, ProviderType.FEVER, ProviderType.NEWSBLUR -> item { Field(stringResource(R.string.username), user) { user = it } }
            else -> {}
        }
        when (type) {
            ProviderType.MINIFLUX -> item { SecretField(stringResource(R.string.api_key), secret) { secret = it } }
            ProviderType.GREADER, ProviderType.FEVER -> item { SecretField(stringResource(R.string.api_password), secret) { secret = it } }
            ProviderType.THEOLDREADER, ProviderType.BAZQUX, ProviderType.FEEDBIN, ProviderType.NEWSBLUR -> item { SecretField(stringResource(R.string.password), secret) { secret = it } }
            ProviderType.INOREADER -> { item { Field(stringResource(R.string.app_id), appId) { appId = it } }; item { SecretField(stringResource(R.string.app_key), appKey) { appKey = it } } }
            ProviderType.LOCAL -> {}
        }
        item { Spacer(Modifier.height(8.dp))
            if (type == ProviderType.INOREADER) WideButton(stringResource(R.string.sign_in_with_browser), !busy) {
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
        if (f.remoteId != null && active == ProviderType.FEVER) { item { Note(stringResource(R.string.fever_cannot_rename_or_move)) }; return@SettingsPage }
        if (f.description.isNotBlank()) item { Note(f.description) }
        item { Field(stringResource(R.string.title), title) { title = it } }
        if (f.siteTitle.isNotBlank() && f.siteTitle != f.title) item { WideButton("Reset title to \"${f.siteTitle}\"") {
            busy = true; vm.updateFeed(f, f.siteTitle, category, url) { err -> if (err != null) { status = "Not saved. $err"; busy = false } else if (nav.currentDestination?.route == "feed/edit/{id}") nav.popBackStack() } }
            Note(stringResource(R.string.the_title_the_feed_itself)) }
        item { Field(stringResource(R.string.category), category) { category = it }; Note(stringResource(R.string.type_a_new_category_or)) }
        item { Choices(stringResource(R.string.existing_categories), categories.map { it to it }, category.trim()) { category = it } }
        if (f.remoteId == null) item { Heading(stringResource(R.string.address)); Field(stringResource(R.string.feed_address), url, KeyboardType.Uri) { url = it }; Note(stringResource(R.string.a_website_address_works_too)) }
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
                item { Choices(stringResource(R.string.open_articles_in), listOf("BROWSER" to "Browser", "FULL_TEXT" to "BlindRSS, full text", "FEED_TEXT" to "BlindRSS, feed text"), openMode) { scope.launch { s.setArticleOpenMode(it) } } }
                item { Choices(stringResource(R.string.open_youtube_videos_in), listOf("YOUTUBE_APP" to "YouTube app", "SMARTTUBE" to "SmartTube", "BROWSER" to "Browser", "IN_APP" to "BlindRSS audio player"), ytMode) { scope.launch { s.setYoutubeOpenMode(it) } }
                    Note(stringResource(R.string.falls_back_to_the_browser)) }
                item { Heading(stringResource(R.string.offline)) }
                item { SwitchRow(stringResource(R.string.save_full_text_for_offline), offline, "Downloads up to 50 new unread articles per refresh. Uses more data.") { scope.launch { s.setFlag("save_full_text", it) } } }
                item { Heading(stringResource(R.string.reader_presentation)) }
                item { val v by s.readerBullets.collectAsState(false); SwitchRow(stringResource(R.string.list_bullets), v, "Speaks a bullet before list items in the reader.") { scope.launch { s.setFlag("reader_bullets", it) } } }
                item { val v by s.readerQuotes.collectAsState(false); SwitchRow(stringResource(R.string.quote_markers), v, "Announces quoted passages in the reader.") { scope.launch { s.setFlag("reader_quotes", it) } } }
                item { val v by s.readerLinkTargets.collectAsState(false); SwitchRow(stringResource(R.string.link_targets), v, "Speaks each link's address after its text.") { scope.launch { s.setFlag("reader_link_targets", it) } } }
                item { val v by s.readerImageAlt.collectAsState(false); SwitchRow(stringResource(R.string.image_descriptions), v, "Reads image alt text in the reader.") { scope.launch { s.setFlag("reader_image_alt", it) } } }
                item { Heading(stringResource(R.string.search)) }
                item { val v by s.searchTitlesOnly.collectAsState(false); SwitchRow(stringResource(R.string.search_titles_only), v, "Off searches titles and article text.") { scope.launch { s.setFlag("search_titles_only", it) } } }
            } }
        "lists" -> { val lp by s.listPrefs.collectAsState(ListReadingPrefs()); fun save(p: ListReadingPrefs) { scope.launch { s.setListPrefs(p) } }
            SettingsPage("Article lists", nav, vm) {
                item { Note(stringResource(R.string.order_title_source_author_and)) }
                item { Heading(stringResource(R.string.read_in_lists)) }
                item { SwitchRow(stringResource(R.string.source), lp.readSource) { save(lp.copy(readSource = it)) } }
                item { SwitchRow(stringResource(R.string.author), lp.readAuthor) { save(lp.copy(readAuthor = it)) } }
                item { SwitchRow(stringResource(R.string.date), lp.readDate) { save(lp.copy(readDate = it)) } }
                item { SwitchRow(stringResource(R.string.article_text), lp.readText) { save(lp.copy(readText = it)) } }
                item { SwitchRow(stringResource(R.string.favorite_and_media_flags), lp.readFlags) { save(lp.copy(readFlags = it)) } }
                if (lp.readText) item { Choices(stringResource(R.string.article_text), listOf("FULL" to "Full article", "PARTIAL" to "First 600 characters", "SUMMARY" to "Summary"), lp.textAmount) { save(lp.copy(textAmount = it)) } }
                item { Choices(stringResource(R.string.read_status), listOf("END" to "At the end", "START" to "After the title", "OFF" to "Off"), lp.statusPosition) { save(lp.copy(statusPosition = it)) } }
            } }
        "sync" -> { val interval by s.syncInterval.collectAsState(60); val wifi by s.wifiOnly.collectAsState(false)
            val onStart by s.refreshOnStart.collectAsState(false); val notify by s.notifyNew.collectAsState(true); val keep by s.maxPerFeed.collectAsState(0)
            val chime by s.refreshChime.collectAsState(true); val uaPreset by s.feedUaPreset.collectAsState("BLINDRSS")
            var cookies by remember { mutableStateOf("") }; var cookiesLoaded by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { try { cookies = s.siteCookies() } catch (_: Exception) {}; cookiesLoaded = true }
            val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (!ok) status = "Notifications are blocked. Allow them in Android settings." }
            SettingsPage("Sync and notifications", nav, vm) {
                item { Choices(stringResource(R.string.sync_in_background), listOf(0 to "Off", 15 to "Every 15 minutes", 30 to "Every 30 minutes", 60 to "Every hour", 240 to "Every 4 hours", 720 to "Every 12 hours"), interval) {
                    scope.launch { s.setSyncInterval(it); SyncWorker.schedule(ctx) } } }
                item { Heading(stringResource(R.string.options)) }
                item { SwitchRow(stringResource(R.string.wi_fi_only), wifi) { scope.launch { s.setFlag("sync_wifi_only", it); SyncWorker.schedule(ctx) } } }
                item { SwitchRow(stringResource(R.string.refresh_when_app_opens), onStart) { scope.launch { s.setFlag("refresh_on_start", it) } } }
                item { SwitchRow(stringResource(R.string.refresh_complete_sound), chime, "Plays a short sound when a refresh you started finishes.") { scope.launch { s.setFlag("refresh_chime", it) } } }
                item { Choices(stringResource(R.string.browser_identity_for_feeds), org.blindrss.android.settings.FetchIdentity.PRESETS, uaPreset) { scope.launch { s.setFeedUaPreset(it) } }
                    Note(stringResource(R.string.some_sites_only_serve_feeds)) }
                item { Heading(stringResource(R.string.clearance_cookies)) }
                item { Note(stringResource(R.string.paste_a_cookie_header_from)) }
                item { SecretField(stringResource(R.string.site_cookies), cookies) { cookies = it } }
                item { WideButton(stringResource(R.string.save_cookies), cookiesLoaded) { scope.launch { s.setSiteCookies(cookies); status = "Cookies saved" } } }
                item { Choices(stringResource(R.string.keep_per_feed), listOf(0 to "All articles", 100 to "Newest 100", 250 to "Newest 250", 500 to "Newest 500", 1000 to "Newest 1000"), keep) { scope.launch { s.setMaxPerFeed(it) } }
                    Note(stringResource(R.string.older_articles_are_deleted_after)) }
                item { val retention by s.retentionDays.collectAsState(0)
                    Choices(stringResource(R.string.article_retention), listOf(0 to "Keep forever", 7 to "7 days", 30 to "30 days", 90 to "90 days", 180 to "180 days", 365 to "365 days"), retention) { scope.launch { s.setRetentionDays(it) } }
                    Note(stringResource(R.string.articles_older_than_this_are)) }
                item { Heading(stringResource(R.string.deleting_articles)) }
                item { val behaviour by s.deleteBehaviour.collectAsState("TOMBSTONE"); val moveTarget by s.deleteMoveTarget.collectAsState("Uncategorized")
                    Choices(stringResource(R.string.when_i_delete_an_article), listOf("TOMBSTONE" to "Move to Deleted Articles", "PERMANENT" to "Delete permanently", "MOVE" to "Move to a category"), behaviour) { scope.launch { s.setDeleteBehaviour(it, moveTarget) } }
                    if (behaviour == "MOVE") { var mt by remember(moveTarget) { mutableStateOf(moveTarget) }
                        Field(stringResource(R.string.move_to_category), mt) { mt = it }; WideButton(stringResource(R.string.save_category)) { scope.launch { s.setDeleteBehaviour("MOVE", mt.ifBlank { "Uncategorized" }); status = "Saved" } } } }
                item { val v by s.confirmDelete.collectAsState(false); SwitchRow(stringResource(R.string.ask_before_deleting), v, "Shows a confirmation before an article is deleted.") { scope.launch { s.setFlag("confirm_delete", it) } } }
                item { Heading(stringResource(R.string.groups_io)) }
                item { Note(stringResource(R.string.member_only_group_archives_need)) }
                item { var gio by remember { mutableStateOf("") }; var gioLoaded by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { try { gio = s.groupsioApiKey() } catch (_: Exception) {}; gioLoaded = true }
                    SecretField(stringResource(R.string.groups_io_api_key), gio) { gio = it }
                    WideButton(stringResource(R.string.save_groups_io_key), gioLoaded) { scope.launch { s.setGroupsioApiKey(gio.trim()); status = "Groups.io key saved" } } }
                item { Heading(stringResource(R.string.google_groups)) }
                item { Note(stringResource(R.string.sign_in_only_groups_need)) }
                item { WideButton(stringResource(R.string.sign_in_to_google_groups)) { nav.navigate("googlesignin") } }
                item { val cookieFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                        if (uri != null) { val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                            val header = org.blindrss.android.settings.FetchIdentity.netscapeToHeader(text)
                            if (header.isBlank()) status = "No cookies found in that file" else { cookies = header; status = "Cookies loaded from file. Choose Save cookies to store them." } } }
                    WideButton(stringResource(R.string.import_cookies_txt_file)) { cookieFile.launch("text/*") } }
                item { Heading(stringResource(R.string.notifications)) }
                item { SwitchRow(stringResource(R.string.new_articles), notify, "Mute single feeds from a feed's actions.") { on -> scope.launch { s.setNotify(on) }
                    if (on && android.os.Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        perm.launch(android.Manifest.permission.POST_NOTIFICATIONS) } }
                item { Status(status) }
            } }
        "playback" -> { val speed by s.playbackSpeed.collectAsState(1f); val silence by s.skipSilence.collectAsState(true); val sponsor by s.sponsorBlock.collectAsState(true); val catDl by s.categoryDownloads.collectAsState(false)
            SettingsPage("Playback", nav, vm) {
                item { Choices(stringResource(R.string.default_speed), listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { it to "${it}x" }, speed) { vm.player.setSpeed(it); scope.launch { s.setSpeed(it) } } }
                item { Heading(stringResource(R.string.audio)) }
                item { SwitchRow(stringResource(R.string.skip_silence), silence) { vm.player.setSkipSilence(it) } }
                item { SwitchRow(stringResource(R.string.sponsorblock), sponsor, "Skips sponsor segments in YouTube videos.") { scope.launch { s.setSponsorBlock(it) } } }
                item { Heading(stringResource(R.string.downloads)) }
                item { SwitchRow(stringResource(R.string.download_whole_categories), catDl, "Adds a Download category action to categories on the home screen. Downloads every unplayed episode.") { scope.launch { s.setFlag("category_downloads", it) } } }
                item { Note(stringResource(R.string.equalizer_and_sleep_timer_are)) }
            } }
        "discovery" -> { var newsApi by remember { mutableStateOf("") }; var theNewsApi by remember { mutableStateOf("") }
            LaunchedEffect(Unit) { try { newsApi = s.newsApiKey(); theNewsApi = s.theNewsApiKey() } catch (_: Exception) {} }
            SettingsPage("Discovery", nav, vm) {
                item { Note(stringResource(R.string.optional_keys_they_add_news)) }
                item { SecretField(stringResource(R.string.newsapi_org_key), newsApi) { newsApi = it } }
                item { SecretField(stringResource(R.string.the_news_api_token), theNewsApi) { theNewsApi = it } }
                item { WideButton(stringResource(R.string.save_keys)) { scope.launch { s.setApiKeys(newsApi.trim(), theNewsApi.trim()); status = "Keys saved" } } }
                item { Status(status) }
            } }
        else -> { val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "unknown" }
            val pending by vm.appUpdate.collectAsState()
            val autoCheck by s.autoUpdateCheck.collectAsState(true)
            SettingsPage("About", nav, vm) {
                item { ListItem(headlineContent = { Text("BlindRSS $version") }, supportingContent = { Text(stringResource(R.string.about_tagline)) }, modifier = Modifier.semantics(mergeDescendants = true) {}) }
                item { WideButton(stringResource(R.string.open_user_guide)) { nav.navigate("help") } }
                item { val crash = remember { org.blindrss.android.CrashLog.read(ctx) }
                    if (crash.isNotEmpty()) {
                        ListItem(headlineContent = { Text("Crash report") }, supportingContent = { Text("BlindRSS crashed and saved a report. Open it to read or share it.") }, modifier = Modifier.semantics(mergeDescendants = true) {})
                        WideButton("View crash report") { nav.navigate("crashreport") } } }
                item { SwitchRow(stringResource(R.string.auto_update_check), autoCheck) { v -> scope.launch { s.setFlag("auto_update_check", v) } } }
                item { WideButton(stringResource(R.string.check_for_updates)) { status = "Checking"; vm.checkAppUpdate(manual = true) { status = it } } }
                if (pending != null) item { UpdateInstallerPanel(pending!!) { vm.dismissAppUpdate() } }
                item { var ytdl by remember { mutableStateOf("") }; LaunchedEffect(Unit) { if (org.blindrss.android.playback.Ytdl.supported()) vm.ytdlVersion { ytdl = it } }
                    if (org.blindrss.android.playback.Ytdl.supported()) {
                        ListItem(headlineContent = { Text(stringResource(R.string.yt_dlp_nightly)) }, supportingContent = { Text(ytdl.ifEmpty { "Starts on first use" }) }, modifier = Modifier.semantics(mergeDescendants = true) {})
                        WideButton(stringResource(R.string.update_yt_dlp)) { status = "Updating yt-dlp"; vm.updateYtdl { status = it; vm.ytdlVersion { v -> ytdl = v } } }
                    } else Note(stringResource(R.string.youtube_audio_needs_android_11)) }
                item { Status(status) }
            } }
    }
}

// Settings > About > Crash report: the uncaught exceptions CrashLog
// recorded. Readable on screen, shareable, clearable — before 0.13.1 a
// crash left no trace anywhere.
@Composable
fun CrashReportScreen(nav: NavController, vm: AppViewModel) {
    val ctx = LocalContext.current
    var report by remember { mutableStateOf(org.blindrss.android.CrashLog.read(ctx)) }
    SettingsPage("Crash report", nav, vm) {
        if (report.isEmpty()) {
            item { Note("No crashes recorded.") }
        } else {
            item { WideButton("Share crash report") {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                try { ctx.startActivity(Intent.createChooser(send, "Share crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            } }
            item { WideButton("Clear crash report") { org.blindrss.android.CrashLog.clear(ctx); report = "" } }
            item { Text(report, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall) }
        }
    }
}
