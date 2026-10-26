package org.blindrss.android.ui
import android.content.Intent
import android.net.Uri
import android.text.Html
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.blindrss.android.R
import org.blindrss.android.data.*
import org.blindrss.android.feed.FullText
import org.blindrss.android.settings.ProviderType
import java.io.File
import java.text.DateFormat
import java.util.Date

fun shareText(ctx: android.content.Context, title: String, url: String) {
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "$title\n$url"), "Share article"))
}
fun stripHtml(h: String) = FullText.toPlain(h)

@Composable
fun LiveAnnouncer(vm: AppViewModel) {
    val msg by vm.announcements.collectAsState()
    Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = msg }) {}
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ArticleRow(a: ArticleEntity, feeds: List<FeedEntity>, onOpen: () -> Unit, onReadInApp: () -> Unit = onOpen, vm: AppViewModel, prefs: ListReadingPrefs = ListReadingPrefs()) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val feedTitle = feeds.firstOrNull { it.id == a.feedId }?.title ?: ""
    val dateStr = DateFormat.getDateInstance().format(Date(a.date))
    val canSave by vm.canSaveToIntegrations.collectAsState()
    val downloaded = a.id in vm.downloadedIds.collectAsState().value
    val playable = vm.canPlay(a)
    val state = buildString { if (!a.isRead) append("Unread. "); if (a.isFavorite) append("Favorite. "); if (a.mediaUrl != null) append("Has media. "); if (downloaded) append("Downloaded. ") }
    // Reading order (user-configurable): title, source, info, article text, status.
    val speech = ListReading.articleSpeech(a.title, feedTitle, a.author, dateStr,
        stripHtml(a.description), stripHtml(a.content.ifBlank { a.description }),
        a.isRead, a.isFavorite, a.mediaUrl != null, prefs)
    Box(Modifier.fillMaxWidth()) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).semantics(mergeDescendants = true) {
            contentDescription = "$speech Double tap to open. Double tap and hold for actions."
            stateDescription = if (a.isRead) "Read" else "Unread"
            customActions = listOf(
                CustomAccessibilityAction(if (a.isRead) "Mark unread" else "Mark read") { vm.setRead(a, !a.isRead); true },
                CustomAccessibilityAction(if (a.isFavorite) "Remove favorite" else "Add favorite") { vm.toggleFavorite(a); true },
                CustomAccessibilityAction("Open externally") { vm.openInBrowser(ctx, a); true },
                CustomAccessibilityAction("Read in app") { onReadInApp(); true },
                CustomAccessibilityAction("Read aloud") { vm.readAloud(a); true },
                CustomAccessibilityAction("Share") { shareText(ctx, a.title, a.url); true },
            ) + listOfNotNull(if (canSave && a.remoteId != null) CustomAccessibilityAction("Save to integrations") { vm.saveToIntegrations(a); true } else null) + listOf(
                CustomAccessibilityAction("Delete") { vm.deleteArticle(a); true }
            ) + (if (playable) listOf(
                CustomAccessibilityAction("Play") { vm.play(a); true },
                CustomAccessibilityAction("Play next") { vm.playNext(a); true },
                CustomAccessibilityAction("Add to Up next") { vm.enqueue(a); true },
                if (downloaded) CustomAccessibilityAction("Delete download") { vm.deleteDownload(a); true } else CustomAccessibilityAction("Download episode") { vm.download(a); true }
            ) else emptyList())
        }.combinedClickable(onClick = { onOpen() }, onLongClick = { menu = true })) {
            Column(Modifier.padding(12.dp)) {
                Text(a.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                if (feedTitle.isNotEmpty()) Text(feedTitle, style = MaterialTheme.typography.labelMedium)
                Text(dateStr, style = MaterialTheme.typography.labelSmall)
                if (a.description.isNotBlank()) Text(stripHtml(a.description).take(160), style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem({ Text(if (a.isRead) "Mark unread" else "Mark read") }, { menu = false; vm.setRead(a, !a.isRead) })
            DropdownMenuItem({ Text(if (a.isFavorite) "Remove favorite" else "Add favorite") }, { menu = false; vm.toggleFavorite(a) })
            if (playable) {
                DropdownMenuItem({ Text("Play") }, { menu = false; vm.play(a) })
                DropdownMenuItem({ Text("Play next") }, { menu = false; vm.playNext(a) })
                DropdownMenuItem({ Text("Add to Up next") }, { menu = false; vm.enqueue(a) })
                if (downloaded) DropdownMenuItem({ Text("Delete download") }, { menu = false; vm.deleteDownload(a) })
                else DropdownMenuItem({ Text("Download episode") }, { menu = false; vm.download(a) })
            }
            DropdownMenuItem({ Text("Open externally") }, { menu = false; vm.openInBrowser(ctx, a) })
            DropdownMenuItem({ Text("Read in app") }, { menu = false; onReadInApp() })
            DropdownMenuItem({ Text("Read aloud") }, { menu = false; vm.readAloud(a) })
            DropdownMenuItem({ Text("Share") }, { menu = false; shareText(ctx, a.title, a.url) })
            if (canSave && a.remoteId != null) DropdownMenuItem({ Text("Save to integrations") }, { menu = false; vm.saveToIntegrations(a) })
            DropdownMenuItem({ Text("Delete") }, { menu = false; vm.deleteArticle(a) })
        }
    }
}

@Composable
fun ArticleList(articles: List<ArticleEntity>, feeds: List<FeedEntity>, nav: NavController, vm: AppViewModel, emptyText: String) {
    val ctx = LocalContext.current
    val prefs by vm.repo.settings.listPrefs.collectAsState(ListReadingPrefs())
    if (articles.isEmpty()) { Text(emptyText, Modifier.padding(16.dp).semantics { contentDescription = emptyText }); return }
    LazyColumn { items(articles, key = { it.id }) { a -> ArticleRow(a, feeds, onOpen = { vm.openArticle(ctx, a, nav) }, onReadInApp = { nav.navigate("reader/${Uri.encode(a.id)}") }, vm = vm, prefs = prefs) } }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FeedRow(f: FeedEntity, cat: String, nav: NavController, vm: AppViewModel) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        ListItem(headlineContent = { Text(f.title) }, supportingContent = { Text(f.url) },
            modifier = Modifier.combinedClickable(onClick = { nav.navigate("articles/feed/${f.id}") }, onLongClick = { menu = true })
                .semantics(mergeDescendants = true) {
                    contentDescription = "Feed ${f.title}, category $cat.${if (f.muted) " Notifications muted." else ""} ${f.lastError?.let { "Error: $it. " } ?: ""}Double tap to open. Double tap and hold for actions."
                    role = Role.Button
                    customActions = listOf(
                        CustomAccessibilityAction("Refresh feed") { vm.refreshFeed(f); true },
                        CustomAccessibilityAction("Edit feed") { nav.navigate("feed/edit/${f.id}"); true },
                        CustomAccessibilityAction(if (f.muted) "Unmute notifications" else "Mute notifications") { vm.toggleMute(f); true },
                        CustomAccessibilityAction("Mark feed read") { vm.markFeedRead(f); true },
                        CustomAccessibilityAction("Delete feed") { vm.deleteFeed(f); true }
                    )
                })
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem({ Text("Refresh feed") }, { menu = false; vm.refreshFeed(f) })
            DropdownMenuItem({ Text("Edit feed") }, { menu = false; nav.navigate("feed/edit/${f.id}") })
            DropdownMenuItem({ Text(if (f.muted) "Unmute notifications" else "Mute notifications") }, { menu = false; vm.toggleMute(f) })
            DropdownMenuItem({ Text("Mark feed read") }, { menu = false; vm.markFeedRead(f) })
            DropdownMenuItem({ Text("Delete feed") }, { menu = false; vm.deleteFeed(f) })
        }
    }
}

// One TalkBack stop per category. Double tap opens its articles; expand, play and download are actions.
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CategoryRow(cat: String, feeds: List<FeedEntity>, open: Boolean, nav: NavController, vm: AppViewModel, onToggle: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val canDownload by vm.repo.settings.categoryDownloads.collectAsState(false)
    val actions = listOf((if (open) "Collapse" else "Expand") to onToggle, "Play category" to { vm.playCategory(cat, feeds) }) +
        (if (canDownload) listOf("Download category" to { vm.downloadCategory(cat, feeds) }) else emptyList())
    val count = if (feeds.size == 1) "1 feed" else "${feeds.size} feeds"
    Box(Modifier.fillMaxWidth()) {
        ListItem(headlineContent = { Text(cat) }, supportingContent = { Text(count) }, leadingContent = { Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null) },
            modifier = Modifier.combinedClickable(onClick = { nav.navigate("categories/${Uri.encode(cat)}") }, onLongClick = { menu = true })
                .semantics(mergeDescendants = true) {
                    contentDescription = "Category $cat, $count. Double tap to open. Double tap and hold for actions."
                    stateDescription = if (open) "Expanded" else "Collapsed"
                    role = Role.Button
                    customActions = actions.map { (label, act) -> CustomAccessibilityAction(label) { act(); true } }
                })
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            actions.forEach { (label, act) -> DropdownMenuItem({ Text(label) }, { menu = false; act() }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavController, vm: AppViewModel) {
    val feeds by vm.feeds.collectAsState(); val unread by vm.unreadCount.collectAsState()
    val pendingUpdate by vm.appUpdate.collectAsState()
    var menu by remember { mutableStateOf(false) }
    // Categories start collapsed; expanding is a row action.
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    Scaffold(topBar = { TopAppBar(title = { Text("BlindRSS", Modifier.semantics { heading() }) }, actions = {
        IconButton(onClick = { vm.refreshAll() }, Modifier.semantics { contentDescription = "Refresh all feeds" }) { Icon(Icons.Filled.Refresh, null) }
        Box { IconButton(onClick = { menu = true }, Modifier.semantics { contentDescription = "More options menu" }) { Icon(Icons.Filled.MoreVert, null) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(stringResource(R.string.add_feed)) }, { menu = false; nav.navigate("addfeed") })
                DropdownMenuItem({ Text(stringResource(R.string.search)) }, { menu = false; nav.navigate("search") })
                DropdownMenuItem({ Text(stringResource(R.string.video_search_title)) }, { menu = false; nav.navigate("videosearch") })
                DropdownMenuItem({ Text("Mark all read") }, { menu = false; vm.markAllRead() })
                DropdownMenuItem({ Text("User guide") }, { menu = false; nav.navigate("help") })
                DropdownMenuItem({ Text("Settings") }, { menu = false; nav.navigate("settings") })
            } }
    }) }) { pad ->
        Column(Modifier.padding(pad)) {
            LiveAnnouncer(vm)
            pendingUpdate?.let { AppUpdateDialog(vm, it) }
            Text(stringResource(R.string.unread_articles, unread), Modifier.padding(16.dp).semantics { contentDescription = "$unread unread articles" })
            val views = listOf(Triple(stringResource(R.string.all_articles), "articles/all", Icons.AutoMirrored.Filled.List), Triple(stringResource(R.string.unread), "articles/unread", Icons.Filled.MarkEmailUnread), Triple(stringResource(R.string.read_articles), "articles/read", Icons.Filled.MarkEmailRead), Triple(stringResource(R.string.favorites), "articles/favorites", Icons.Filled.Star), Triple(stringResource(R.string.categories_and_labels), "categories", Icons.AutoMirrored.Filled.Label), Triple(stringResource(R.string.discover_sources), "discover", Icons.Filled.Explore), Triple(stringResource(R.string.video_search_title), "videosearch", Icons.Filled.OndemandVideo), Triple(stringResource(R.string.deleted_articles), "deleted", Icons.Filled.Delete), Triple(stringResource(R.string.feeds_with_errors), "errors", Icons.Filled.Error), Triple(stringResource(R.string.smart_folders), "smart", Icons.Filled.Folder), Triple(stringResource(R.string.filter_rules), "filters", Icons.Filled.FilterList), Triple(stringResource(R.string.podcast_episodes), "articles/podcasts", Icons.Filled.Podcasts), Triple(stringResource(R.string.player_title), "player", Icons.Filled.PlayCircle))
            LazyColumn {
                items(views) { (label, route, icon) ->
                    ListItem(headlineContent = { Text(label) }, leadingContent = { Icon(icon, null) }, modifier = Modifier.clickable { nav.navigate(route) }.semantics { contentDescription = "Open $label"; role = Role.Button })
                    HorizontalDivider()
                }
                items(feeds.groupBy { it.category }.entries.toList()) { (cat, list) ->
                    val open = cat in expanded
                    CategoryRow(cat, list, open, nav, vm) { expanded = if (open) expanded - cat else expanded + cat }
                    if (open) list.forEach { f -> FeedRow(f, cat, nav, vm) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticlesScreen(nav: NavController, vm: AppViewModel, mode: String) {
    val ctx = LocalContext.current
    val feeds by vm.feeds.collectAsState()
    val flow = remember(mode) { when (mode) { "unread" -> vm.repo.articles.observeUnread(); "read" -> vm.repo.articles.observeRead(); "favorites" -> vm.repo.articles.observeFavorites(); "podcasts" -> vm.repo.articles.observePodcasts(); else -> vm.repo.articles.observeAll() } }
    val articles by flow.collectAsState(emptyList())
    val title = when (mode) { "unread" -> "Unread"; "read" -> "Read"; "favorites" -> "Favorites"; "podcasts" -> "Podcast Episodes"; else -> "All Articles" }
    Scaffold(topBar = { TopAppBar(title = { Text(title, Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = ctx.getString(R.string.back) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }, actions = { IconButton(onClick = { vm.markAllRead() }, Modifier.semantics { contentDescription = "Mark all read" }) { Icon(Icons.Filled.DoneAll, null) } }) }) { pad ->
        Column(Modifier.padding(pad)) { LiveAnnouncer(vm); ArticleList(articles, feeds, nav, vm, "No articles. Add a feed or refresh.") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedArticlesScreen(nav: NavController, vm: AppViewModel, feedId: Long) {
    val ctx = LocalContext.current
    val feeds by vm.feeds.collectAsState()
    val articles by remember(feedId) { vm.repo.articles.observeByFeed(feedId) }.collectAsState(emptyList())
    val feed = feeds.firstOrNull { it.id == feedId }
    Scaffold(topBar = { TopAppBar(title = { Text(feed?.title ?: "Feed", Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = ctx.getString(R.string.back) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.padding(pad)) { LiveAnnouncer(vm); ArticleList(articles, feeds, nav, vm, "No articles in this feed yet. Pull refresh from home.") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(nav: NavController, vm: AppViewModel, articleId: String) {
    val article by remember(articleId) { vm.repo.articles.observeById(articleId) }.collectAsState(null)
    val ctx = LocalContext.current
    var fullText by remember(articleId) { mutableStateOf<String?>(null) }
    var loadingFull by remember(articleId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.article), Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = ctx.getString(R.string.back) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }, actions = {
        article?.let { a ->
            IconButton(onClick = { vm.readAloud(a) }, Modifier.semantics { contentDescription = ctx.getString(R.string.read_aloud) }) { Icon(Icons.Filled.RecordVoiceOver, null) }
            IconButton(onClick = { shareText(ctx, a.title, a.url) }, Modifier.semantics { contentDescription = ctx.getString(R.string.share_article) }) { Icon(Icons.Filled.Share, null) }
            if (vm.canSaveToIntegrations.collectAsState().value && a.remoteId != null) IconButton(onClick = { vm.saveToIntegrations(a) }, Modifier.semantics { contentDescription = "Save to integrations" }) { Icon(Icons.Filled.BookmarkAdd, null) }
            IconButton(onClick = { vm.openInBrowser(ctx, a) }, Modifier.semantics { contentDescription = "Open original article externally" }) { Icon(Icons.Filled.OpenInBrowser, null) }
        }
    }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            LiveAnnouncer(vm)
            val a = article
            if (a == null) { Text("Loading article") } else {
                LaunchedEffect(a.id) { if (!a.isRead) vm.setRead(a, true)
                    if (vm.repo.settings.articleOpenMode.first() == "FULL_TEXT" && fullText == null) { loadingFull = true; vm.loadFullText(a) { t -> if (t != null && stripHtml(t).length > stripHtml(a.content.ifBlank { a.description }).length) fullText = t; loadingFull = false } } }
                val bodyHtml = fullText ?: a.content.ifBlank { a.description }
                val blocks = remember(bodyHtml) { org.blindrss.android.feed.FullText.blocks(bodyHtml) }
                // Reading position, the desktop status bar's "Line X of Y" as
                // an Android readout: the three fixed rows (title, buttons,
                // fetch) precede the paragraph blocks in the list.
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                val firstVisible by remember { derivedStateOf { listState.firstVisibleItemIndex } }
                val readingPos = (firstVisible - 2).coerceIn(1, maxOf(1, blocks.size))
                LazyColumn(state = listState) {
                    item { Text(a.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }); Text(DateFormat.getDateTimeInstance().format(Date(a.date)), style = MaterialTheme.typography.labelMedium); if (a.author.isNotBlank()) Text("By ${a.author}", style = MaterialTheme.typography.labelMedium); if (blocks.size > 1) Text(stringResource(R.string.reading_position, readingPos, blocks.size), style = MaterialTheme.typography.labelMedium, modifier = Modifier.semantics { contentDescription = "Reading position: paragraph $readingPos of ${blocks.size}" }) }
                    item { Row { Button(onClick = { vm.toggleFavorite(a) }, Modifier.semantics { contentDescription = if (a.isFavorite) "Remove favorite" else "Add favorite" }) { Text(if (a.isFavorite) stringResource(R.string.unfavorite) else stringResource(R.string.favorite)) }; Spacer(Modifier.width(8.dp)); Button(onClick = { vm.setRead(a, !a.isRead) }, Modifier.semantics { contentDescription = if (a.isRead) ctx.getString(R.string.mark_unread) else ctx.getString(R.string.mark_read) }) { Text(if (a.isRead) stringResource(R.string.mark_unread) else stringResource(R.string.mark_read)) }; if (a.mediaUrl != null) { Spacer(Modifier.width(8.dp)); Button(onClick = { vm.play(a); nav.navigate("player") }, Modifier.semantics { contentDescription = "Play media" }) { Text(stringResource(R.string.play)) } } } }
                    item { Button(onClick = { loadingFull = true; vm.loadFullText(a) { t -> if (t != null) fullText = t; loadingFull = false } }, Modifier.semantics { contentDescription = "Fetch full article text" }) { Text(if (loadingFull) "Fetching full text..." else if (fullText != null) "Full text shown. Fetch again" else stringResource(R.string.fetch_full_text)) } }
                    // Each paragraph is its own TalkBack stop with its real text.
                    // Do NOT set contentDescription here: it would replace the
                    // paragraph text TalkBack reads (the old "Article body" bug).
                    if (blocks.isEmpty()) {
                        item { Text("No article text available. Use Open externally to read in your browser.", style = MaterialTheme.typography.bodyLarge) }
                    } else {
                        items(blocks) { b ->
                            if (b.heading) Text(b.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() })
                            else Text(b.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 10.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(nav: NavController, vm: AppViewModel) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    val feeds by vm.feeds.collectAsState()
    val results by remember(q) { if (q.isBlank()) kotlinx.coroutines.flow.flowOf(emptyList()) else vm.repo.articles.search(q) }.collectAsState(emptyList())
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.search), Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = ctx.getString(R.string.back) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            OutlinedTextField(q, { q = it }, label = { Text(stringResource(R.string.search_articles)) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search articles edit field" }, singleLine = true)
            ArticleList(results, feeds, nav, vm, if (q.isBlank()) "Type to search titles and content." else "No results for $q")
        }
    }
}

// One TalkBack stop per row. No trailing buttons: actions live in TalkBack
// custom actions and in the double-tap-and-hold (long press) menu.
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ActionListItem(title: String, supporting: String, description: String, onOpen: (() -> Unit)?, actions: List<Pair<String, () -> Unit>>) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        ListItem(headlineContent = { Text(title) }, supportingContent = { Text(supporting) },
            modifier = Modifier.combinedClickable(onClick = { if (onOpen != null) onOpen() else menu = true }, onLongClick = { menu = true })
                .semantics(mergeDescendants = true) {
                    contentDescription = "$description Double tap and hold for actions."
                    role = Role.Button
                    customActions = actions.map { (label, act) -> CustomAccessibilityAction(label) { act(); true } }
                })
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            actions.forEach { (label, act) -> DropdownMenuItem({ Text(label) }, { menu = false; act() }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeletedScreen(nav: NavController, vm: AppViewModel) {
    val ctx = LocalContext.current
    val deleted by remember { vm.repo.misc.observeDeleted() }.collectAsState(emptyList())
    Scaffold(topBar = { TopAppBar(title = { Text("Deleted Articles", Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = ctx.getString(R.string.back) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }, actions = { IconButton(onClick = { vm.purgeAllDeleted() }, Modifier.semantics { contentDescription = "Purge all deleted articles" }) { Icon(Icons.Filled.DeleteForever, null) } }) }) { pad ->
        Column(Modifier.padding(pad)) { LiveAnnouncer(vm)
            if (deleted.isEmpty()) Text("No deleted articles.", Modifier.padding(16.dp))
            LazyColumn { items(deleted, key = { it.id }) { d ->
                ActionListItem(d.title, "Deleted ${DateFormat.getDateInstance().format(Date(d.deletedAt))}", "Deleted article ${d.title}. Double tap to restore.", onOpen = { vm.restoreDeleted(d.id) },
                    actions = listOf("Restore" to { vm.restoreDeleted(d.id) }, "Delete forever" to { vm.purgeDeleted(d.id) }))
                HorizontalDivider() } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ErrorsScreen(nav: NavController, vm: AppViewModel) {
    val ctx = LocalContext.current
    val errs by vm.errorFeeds.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("Feeds with Errors", Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = ctx.getString(R.string.back) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.padding(pad)) { LiveAnnouncer(vm)
            if (errs.isEmpty()) Text("No feeds with errors.", Modifier.padding(16.dp))
            LazyColumn { items(errs, key = { it.id }) { f ->
                ActionListItem(f.title, "${f.lastError}\n${f.url}\nFailures: ${f.consecutiveFailures}", "Feed ${f.title} error ${f.lastError}. Double tap to retry.", onOpen = { vm.refreshFeed(f) },
                    actions = listOf("Retry feed" to { vm.refreshFeed(f) }, "Open feed" to { nav.navigate("articles/feed/${f.id}") }, "Edit feed" to { nav.navigate("feed/edit/${f.id}") }, "Delete feed" to { vm.deleteFeed(f) }))
                HorizontalDivider() } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartFoldersScreen(nav: NavController, vm: AppViewModel) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }; var value by remember { mutableStateOf("") }; var field by remember { mutableStateOf("title") }
    val folders by remember { vm.repo.misc.observeSmart() }.collectAsState(emptyList())
    val feeds by vm.feeds.collectAsState()
    var selected by remember { mutableStateOf<SmartFolderEntity?>(null) }
    val all by remember { vm.repo.articles.observeAll() }.collectAsState(emptyList())
    Scaffold(topBar = { TopAppBar(title = { Text("Smart Folders", Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = ctx.getString(R.string.back) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) { LiveAnnouncer(vm)
            OutlinedTextField(name, { name = it }, label = { Text("Folder name") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Smart folder name" })
            OutlinedTextField(value, { value = it }, label = { Text("Contains text in $field") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Smart folder search text" })
            Row { listOf("title", "content", "author").forEach { f -> FilterChip(field == f, { field = f }, { Text(f) }, modifier = Modifier.semantics { contentDescription = "Match field $f" }) } }
            Button(onClick = { if (name.isNotBlank() && value.isNotBlank()) { val n = name; val v = value; val fl = field; kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { vm.repo.misc.insertSmart(SmartFolderEntity(name = n, field = fl, value = v)) }; name = ""; value = "" } }, Modifier.semantics { contentDescription = "Create smart folder" }) { Text("Create smart folder") }
            LazyColumn { items(folders, key = { it.id }) { f -> ActionListItem(f.name, "${f.field} contains ${f.value}", "Smart folder ${f.name}, ${f.field} contains ${f.value}. Double tap to show results.", onOpen = { selected = f },
                actions = listOf("Show results" to { selected = f }, "Delete smart folder" to { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { vm.repo.misc.deleteSmart(f) } })) } }
            selected?.let { f -> Text("Results for ${f.name}", Modifier.semantics { heading() }); val matched = all.filter { a -> val v = when (f.field) { "content" -> a.content; "author" -> a.author; else -> a.title }; v.contains(f.value, true) }; ArticleList(matched, feeds, nav, vm, "No matching articles.") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltersScreen(nav: NavController, vm: AppViewModel) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }; var value by remember { mutableStateOf("") }; var action by remember { mutableStateOf("mark_read") }; var target by remember { mutableStateOf("") }
    val rules by remember { vm.repo.misc.observeRules() }.collectAsState(emptyList())
    Scaffold(topBar = { TopAppBar(title = { Text("Filter Rules", Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = ctx.getString(R.string.back) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) { LiveAnnouncer(vm)
            Text("Rules run on new articles as they arrive.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(name, { name = it }, label = { Text("Rule name") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Rule name" })
            OutlinedTextField(value, { value = it }, label = { Text("Title contains") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Rule match text, title contains" })
            Row { listOf("mark_read" to "Mark read", "mark_favorite" to "Favorite", "delete" to "Delete", "move" to "Move", "label" to "Label", "skip_notification" to "Skip notify").forEach { (v, l) -> FilterChip(action == v, { action = v }, { Text(l) }, modifier = Modifier.semantics { contentDescription = "Action $l" }) } }
            if (action == "move" || action == "label") OutlinedTextField(target, { target = it }, label = { Text(if (action == "move") "Move to category" else "Label name") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = if (action == "move") "Move to category" else "Label name" })
            Button(onClick = { if (name.isNotBlank() && value.isNotBlank()) { val n = name; val v = value; val ac = action; val tg = target; kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { vm.repo.misc.insertRule(FilterRuleEntity(name = n, field = "title", op = "contains", value = v, action = ac, target = tg.ifBlank { null })) }; name = ""; value = ""; target = "" } }, Modifier.semantics { contentDescription = "Create filter rule" }) { Text("Create rule") }
            LazyColumn { items(rules, key = { it.id }) { r -> ActionListItem(r.name, "title contains ${r.value} then ${r.action}${r.target?.let { " to $it" } ?: ""}", "Filter rule ${r.name}.", onOpen = null,
                actions = listOf("Delete rule" to { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { vm.repo.misc.deleteRule(r) } })) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFeedScreen(nav: NavController, vm: AppViewModel) {
    var url by remember { mutableStateOf("") }; var cat by remember { mutableStateOf("Uncategorized") }
    val ctx = LocalContext.current
    val opmlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { val xml = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""; vm.importOpml(xml) }
    }
    val scope = rememberCoroutineScope()
    var exportMsg by remember { mutableStateOf("") }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.add_feed), Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = ctx.getString(R.string.back) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { LiveAnnouncer(vm)
            Text("Paste a feed URL, website URL, YouTube channel or playlist, Reddit community, Lemmy or PieFed community, Mastodon or Bluesky profile, or GitHub repository URL. Discovery finds the feed automatically.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.feed_url_label)) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Feed or website URL edit field" }, singleLine = true)
            OutlinedTextField(cat, { cat = it }, label = { Text(stringResource(R.string.category)) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Category edit field" }, singleLine = true)
            Button(onClick = { vm.addFeed(url, cat); nav.popBackStack() }, Modifier.fillMaxWidth().semantics { contentDescription = "Subscribe to feed" }) { Text(stringResource(R.string.subscribe)) }
            HorizontalDivider()
            Text("OPML", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Button(onClick = { opmlLauncher.launch("text/*") }, Modifier.fillMaxWidth().semantics { contentDescription = "Import OPML file" }) { Text(stringResource(R.string.import_opml)) }
            Button(onClick = { scope.launch { val opml = vm.repo.exportOpml(); val f = File(ctx.getExternalFilesDir(null), "blindrss-export.opml"); f.writeText(opml); exportMsg = "Exported to ${f.absolutePath}"; shareText(ctx, "BlindRSS OPML", opml.take(500)) } }, Modifier.fillMaxWidth().semantics { contentDescription = "Export OPML file" }) { Text(stringResource(R.string.export_opml)) }
            if (exportMsg.isNotEmpty()) Text(exportMsg)
            HorizontalDivider()
            Text("Search podcast directories", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            var dq by remember { mutableStateOf("") }; var hits by remember { mutableStateOf<List<org.blindrss.android.feed.SearchHit>>(emptyList()) }; var searching by remember { mutableStateOf(false) }
            OutlinedTextField(dq, { dq = it }, label = { Text("Search Apple Podcasts, gPodder, Feedsearch, Mastodon, Bluesky and PieFed") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search podcast directories" }, singleLine = true)
            Button(onClick = { searching = true; vm.searchDirectory(dq) { hits = it; searching = false } }, Modifier.fillMaxWidth().semantics { contentDescription = "Search directories" }) { Text(if (searching) "Searching..." else "Search") }
            hits.forEach { h -> ActionListItem(h.title, "${h.source}: ${h.feedUrl}", "Podcast ${h.title} from ${h.source}. Double tap to subscribe.", onOpen = { vm.addFeed(h.feedUrl, cat) },
                actions = listOf("Subscribe" to { vm.addFeed(h.feedUrl, cat) })) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(nav: NavController, vm: AppViewModel) {
    val ctx = LocalContext.current
    val feeds by vm.feeds.collectAsState()
    val all by remember { vm.repo.articles.observeAll() }.collectAsState(emptyList())
    val feedCat = feeds.associate { it.id to it.category }
    val names = remember(all, feeds) { val counts = mutableMapOf<String, Int>()
        for (a in all) { val folder = a.folder ?: feedCat[a.feedId] ?: "Uncategorized"; counts[folder] = (counts[folder] ?: 0) + 1
            for (l in a.labels.split(",").map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("__") }) counts["Label: $l"] = (counts["Label: $l"] ?: 0) + 1 }
        counts.entries.sortedBy { it.key } }
    Scaffold(topBar = { TopAppBar(title = { Text("Categories and Labels", Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = ctx.getString(R.string.back) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.padding(pad)) { LiveAnnouncer(vm)
            if (names.isEmpty()) Text("No articles yet.", Modifier.padding(16.dp))
            LazyColumn { items(names) { (name, count) ->
                ListItem(headlineContent = { Text(name) }, supportingContent = { Text("$count articles") }, modifier = Modifier.clickable { nav.navigate("categories/${Uri.encode(name)}") }.semantics { contentDescription = "Category $name, $count articles"; role = Role.Button })
                HorizontalDivider() } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryArticlesScreen(nav: NavController, vm: AppViewModel, name: String) {
    val ctx = LocalContext.current
    val feeds by vm.feeds.collectAsState()
    val all by remember { vm.repo.articles.observeAll() }.collectAsState(emptyList())
    val feedCat = feeds.associate { it.id to it.category }
    val matched = remember(all, name) { all.filter { a -> if (name.startsWith("Label: ")) name.removePrefix("Label: ") in a.labels.split(",").map { it.trim() } else (a.folder ?: feedCat[a.feedId] ?: "Uncategorized") == name } }
    Scaffold(topBar = { TopAppBar(title = { Text(name, Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = ctx.getString(R.string.back) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.padding(pad)) { LiveAnnouncer(vm); ArticleList(matched, feeds, nav, vm, "No articles in $name") }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DiscoverRow(s: org.blindrss.android.discovery.DiscoveredSource, selected: Boolean, vm: AppViewModel) {
    var menu by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    Box(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(s.name) },
            supportingContent = { Text(buildString { append(s.provider); if (s.category.isNotBlank()) append(" · ").append(s.category); append(if (s.subscribed) " · Already subscribed" else " · Not subscribed"); if (selected) append(" · Selected"); if (s.isGoogleNews) append(" · Search feed, not a single publisher"); if (s.feedUrl != null && !s.verified) append(" · Feed not yet verified") }) },
            modifier = Modifier.combinedClickable(onClick = { vm.previewDiscover(s) }, onLongClick = { menu = true })
                .semantics(mergeDescendants = true) {
                    contentDescription = s.describe() + (if (selected) " Selected." else "") + " Double tap to preview. Double tap and hold for actions."
                    role = Role.Button
                    stateDescription = when { s.subscribed -> "Subscribed"; selected -> "Selected"; else -> "Not subscribed" }
                    customActions = listOf(
                        CustomAccessibilityAction("Preview") { vm.previewDiscover(s); true },
                        CustomAccessibilityAction(if (s.subscribed) "Unsubscribe" else "Subscribe") { vm.toggleSubscribeDiscover(s); true },
                        CustomAccessibilityAction(if (selected) "Remove from selection" else "Select for batch subscribe") { vm.toggleDiscoverSelect(s); true }
                    ) + if (s.homepage.isNotBlank()) listOf(CustomAccessibilityAction("Open website") { vm.launchBrowser(ctx, s.homepage); true }) else emptyList()
                })
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem({ Text("Preview") }, { menu = false; vm.previewDiscover(s) })
            DropdownMenuItem({ Text(if (s.subscribed) "Unsubscribe" else "Subscribe") }, { menu = false; vm.toggleSubscribeDiscover(s) })
            DropdownMenuItem({ Text(if (selected) "Remove from selection" else "Select for batch subscribe") }, { menu = false; vm.toggleDiscoverSelect(s) })
            if (s.homepage.isNotBlank()) DropdownMenuItem({ Text("Open website") }, { menu = false; vm.launchBrowser(ctx, s.homepage) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(nav: NavController, vm: AppViewModel) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Canada") }
    var country by remember { mutableStateOf("CA") }; var language by remember { mutableStateOf("en") }
    var city by remember { mutableStateOf("") }; var region by remember { mutableStateOf("") }
    val results by vm.discoverResults.collectAsState(); val loading by vm.discoverLoading.collectAsState()
    val selected by vm.discoverSelected.collectAsState(); val preview by vm.discoverPreview.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { country = vm.repo.settings.discoveryCountry.first(); language = vm.repo.settings.discoveryLanguage.first(); city = vm.repo.settings.discoveryCity.first(); region = vm.repo.settings.discoveryRegion.first(); vm.loadCatalogue(); vm.browseDiscover(category) }
    val categories = listOf("General news", "World", "Canada", "Local news", "Politics", "Business", "Technology", "Science", "Health", "Entertainment", "Sports", "Gaming")
    Scaffold(topBar = { TopAppBar(title = { Text("Discover News Sources", Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = ctx.getString(R.string.back) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LiveAnnouncer(vm)
            OutlinedTextField(query, { query = it }, label = { Text("Publisher name, topic, or website URL") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search publishers by name, topic, or website URL" }, singleLine = true)
            Button(onClick = { vm.searchDiscover(query) }, Modifier.fillMaxWidth().semantics { contentDescription = "Search discovery sources" }) { Text(if (loading) "Searching..." else "Search") }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Searching" })
            Text("Categories", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(categories) { c -> FilterChip(category == c, { category = c; vm.browseDiscover(c) }, { Text(c) }, modifier = Modifier.semantics { contentDescription = "Browse category $c" }) } }
            Text("Country and language", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(listOf("CA" to "Canada", "US" to "USA", "GB" to "UK", "AU" to "Australia", "FR" to "France", "DE" to "Germany")) { (code, label) -> FilterChip(country == code, { country = code; scope.launch { vm.repo.settings.setDiscoveryLocale(code, language, city, region) }; vm.browseDiscover(category) }, { Text(label) }, modifier = Modifier.semantics { contentDescription = "Country $label" }) } }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(listOf("en" to "English", "fr" to "Français", "es" to "Español", "de" to "Deutsch")) { (code, label) -> FilterChip(language == code, { language = code; scope.launch { vm.repo.settings.setDiscoveryLocale(country, code, city, region) }; vm.browseDiscover(category) }, { Text(label) }, modifier = Modifier.semantics { contentDescription = "Language $label" }) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(city, { city = it }, label = { Text("City (for local news)") }, modifier = Modifier.weight(1f).semantics { contentDescription = "City for local news, for example Maple Ridge" }, singleLine = true)
                OutlinedTextField(region, { region = it }, label = { Text("Region") }, modifier = Modifier.weight(1f).semantics { contentDescription = "Region or province for local news, for example BC" }, singleLine = true)
            }
            Button(onClick = { scope.launch { vm.repo.settings.setDiscoveryLocale(country, language, city, region) }; category = "Local news"; vm.browseDiscover("Local news") }, Modifier.fillMaxWidth().semantics { contentDescription = "Save location and show local news" }) { Text("Save location and show local news") }
            if (selected.isNotEmpty()) Button(onClick = { vm.subscribeDiscover(results.filter { it.feedUrl in selected }) }, Modifier.fillMaxWidth().semantics { contentDescription = "Subscribe to ${selected.size} selected sources" }) { Text("Subscribe to ${selected.size} selected") }
            preview?.let { (src, titles) ->
                Card(Modifier.fillMaxWidth().semantics { contentDescription = "Preview of ${src.name}" }) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(src.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    if (src.description.isNotBlank()) Text(src.description, style = MaterialTheme.typography.bodySmall)
                    Text(if (src.verified) "Verified feed: ${src.feedUrl}" else "Feed: ${src.feedUrl}", style = MaterialTheme.typography.bodySmall)
                    titles.forEach { t -> Text("• $t", style = MaterialTheme.typography.bodyMedium) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.toggleSubscribeDiscover(src) }, Modifier.semantics { contentDescription = if (src.subscribed) "Unsubscribe from ${src.name}" else "Subscribe to ${src.name}" }) { Text(if (src.subscribed) "Unsubscribe" else "Subscribe") }
                        Button(onClick = { vm.discoverPreview.value = null }, Modifier.semantics { contentDescription = "Close preview" }) { Text("Close preview") }
                    }
                } }
            }
            Text("${results.size} sources", style = MaterialTheme.typography.labelMedium, modifier = Modifier.semantics { contentDescription = "${results.size} sources found" })
            LazyColumn { items(results, key = { (it.feedUrl ?: it.name) + it.provider }) { s -> DiscoverRow(s, s.feedUrl != null && s.feedUrl in selected, vm); HorizontalDivider() } }
        }
    }
}
