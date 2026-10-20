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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
fun ArticleRow(a: ArticleEntity, feeds: List<FeedEntity>, onOpen: () -> Unit, onReadInApp: () -> Unit = onOpen, vm: AppViewModel) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val feedTitle = feeds.firstOrNull { it.id == a.feedId }?.title ?: ""
    val dateStr = DateFormat.getDateInstance().format(Date(a.date))
    val state = buildString { if (!a.isRead) append("Unread. "); if (a.isFavorite) append("Favorite. "); if (a.mediaUrl != null) append("Has media. ") }
    Box(Modifier.fillMaxWidth()) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).semantics(mergeDescendants = true) {
            contentDescription = "${a.title}. $feedTitle. $dateStr. $state Double tap to open. Double tap and hold for actions."
            stateDescription = state.ifBlank { "Read" }
            customActions = listOf(
                CustomAccessibilityAction(if (a.isRead) "Mark unread" else "Mark read") { vm.setRead(a, !a.isRead); true },
                CustomAccessibilityAction(if (a.isFavorite) "Remove favorite" else "Add favorite") { vm.toggleFavorite(a); true },
                CustomAccessibilityAction("Download episode") { vm.download(a); true },
                CustomAccessibilityAction("Open externally") { vm.openInBrowser(ctx, a); true },
                CustomAccessibilityAction("Read in app") { onReadInApp(); true },
                CustomAccessibilityAction("Share") { shareText(ctx, a.title, a.url); true },
                CustomAccessibilityAction("Play") { vm.play(a); true },
                CustomAccessibilityAction("Delete") { vm.deleteArticle(a); true }
            )
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
            if (a.mediaUrl != null) {
                DropdownMenuItem({ Text("Play") }, { menu = false; vm.play(a) })
                DropdownMenuItem({ Text("Download episode") }, { menu = false; vm.download(a) })
            }
            DropdownMenuItem({ Text("Open externally") }, { menu = false; vm.openInBrowser(ctx, a) })
            DropdownMenuItem({ Text("Read in app") }, { menu = false; onReadInApp() })
            DropdownMenuItem({ Text("Share") }, { menu = false; shareText(ctx, a.title, a.url) })
            DropdownMenuItem({ Text("Delete") }, { menu = false; vm.deleteArticle(a) })
        }
    }
}

@Composable
fun ArticleList(articles: List<ArticleEntity>, feeds: List<FeedEntity>, nav: NavController, vm: AppViewModel, emptyText: String) {
    val ctx = LocalContext.current
    if (articles.isEmpty()) { Text(emptyText, Modifier.padding(16.dp).semantics { contentDescription = emptyText }); return }
    LazyColumn { items(articles, key = { it.id }) { a -> ArticleRow(a, feeds, onOpen = { vm.openArticle(ctx, a, nav) }, onReadInApp = { nav.navigate("reader/${Uri.encode(a.id)}") }, vm = vm) } }
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
                        CustomAccessibilityAction(if (f.muted) "Unmute notifications" else "Mute notifications") { vm.toggleMute(f); true },
                        CustomAccessibilityAction("Mark feed read") { vm.markFeedRead(f); true },
                        CustomAccessibilityAction("Delete feed") { vm.deleteFeed(f); true }
                    )
                })
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem({ Text("Refresh feed") }, { menu = false; vm.refreshFeed(f) })
            DropdownMenuItem({ Text(if (f.muted) "Unmute notifications" else "Mute notifications") }, { menu = false; vm.toggleMute(f) })
            DropdownMenuItem({ Text("Mark feed read") }, { menu = false; vm.markFeedRead(f) })
            DropdownMenuItem({ Text("Delete feed") }, { menu = false; vm.deleteFeed(f) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavController, vm: AppViewModel) {
    val feeds by vm.feeds.collectAsState(); val unread by vm.unreadCount.collectAsState()
    var menu by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("BlindRSS", Modifier.semantics { heading() }) }, actions = {
        IconButton(onClick = { vm.refreshAll() }, Modifier.semantics { contentDescription = "Refresh all feeds" }) { Icon(Icons.Filled.Refresh, null) }
        Box { IconButton(onClick = { menu = true }, Modifier.semantics { contentDescription = "More options menu" }) { Icon(Icons.Filled.MoreVert, null) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Add feed") }, { menu = false; nav.navigate("addfeed") })
                DropdownMenuItem({ Text("Search") }, { menu = false; nav.navigate("search") })
                DropdownMenuItem({ Text("Mark all read") }, { menu = false; vm.markAllRead() })
                DropdownMenuItem({ Text("Settings") }, { menu = false; nav.navigate("settings") })
            } }
    }) }) { pad ->
        Column(Modifier.padding(pad)) {
            LiveAnnouncer(vm)
            Text("$unread unread articles", Modifier.padding(16.dp).semantics { contentDescription = "$unread unread articles" })
            val views = listOf(Triple("All Articles", "articles/all", Icons.Filled.List), Triple("Unread", "articles/unread", Icons.Filled.MarkEmailUnread), Triple("Read", "articles/read", Icons.Filled.MarkEmailRead), Triple("Favorites", "articles/favorites", Icons.Filled.Star), Triple("Categories and Labels", "categories", Icons.Filled.Label), Triple("Deleted Articles", "deleted", Icons.Filled.Delete), Triple("Feeds with Errors", "errors", Icons.Filled.Error), Triple("Smart Folders", "smart", Icons.Filled.Folder), Triple("Filter Rules", "filters", Icons.Filled.FilterList), Triple("Downloads and Player", "player", Icons.Filled.PlayCircle))
            LazyColumn {
                items(views) { (label, route, icon) ->
                    ListItem(headlineContent = { Text(label) }, leadingContent = { Icon(icon, null) }, modifier = Modifier.clickable { nav.navigate(route) }.semantics { contentDescription = "Open $label"; role = Role.Button })
                    HorizontalDivider()
                }
                item { Text("Feeds by category", Modifier.padding(16.dp).semantics { heading(); contentDescription = "Feeds by category" }) }
                items(feeds.groupBy { it.category }.entries.toList()) { (cat, list) ->
                    Text(cat, Modifier.padding(start = 16.dp, top = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                    list.forEach { f -> FeedRow(f, cat, nav, vm) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticlesScreen(nav: NavController, vm: AppViewModel, mode: String) {
    val feeds by vm.feeds.collectAsState()
    val flow = remember(mode) { when (mode) { "unread" -> vm.repo.articles.observeUnread(); "read" -> vm.repo.articles.observeRead(); "favorites" -> vm.repo.articles.observeFavorites(); else -> vm.repo.articles.observeAll() } }
    val articles by flow.collectAsState(emptyList())
    val title = when (mode) { "unread" -> "Unread"; "read" -> "Read"; "favorites" -> "Favorites"; else -> "All Articles" }
    Scaffold(topBar = { TopAppBar(title = { Text(title, Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = "Back" }) { Icon(Icons.Filled.ArrowBack, null) } }, actions = { IconButton(onClick = { vm.markAllRead() }, Modifier.semantics { contentDescription = "Mark all read" }) { Icon(Icons.Filled.DoneAll, null) } }) }) { pad ->
        Column(Modifier.padding(pad)) { LiveAnnouncer(vm); ArticleList(articles, feeds, nav, vm, "No articles. Add a feed or refresh.") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedArticlesScreen(nav: NavController, vm: AppViewModel, feedId: Long) {
    val feeds by vm.feeds.collectAsState()
    val articles by remember(feedId) { vm.repo.articles.observeByFeed(feedId) }.collectAsState(emptyList())
    val feed = feeds.firstOrNull { it.id == feedId }
    Scaffold(topBar = { TopAppBar(title = { Text(feed?.title ?: "Feed", Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = "Back" }) { Icon(Icons.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.padding(pad)) { LiveAnnouncer(vm); ArticleList(articles, feeds, nav, vm, "No articles in this feed yet. Pull refresh from home.") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(nav: NavController, vm: AppViewModel, articleId: String) {
    val article by remember(articleId) { vm.repo.articles.observeById(articleId) }.collectAsState(null)
    val ctx = LocalContext.current
    var fullText by remember { mutableStateOf<String?>(null) }
    var loadingFull by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { TopAppBar(title = { Text("Article", Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = "Back" }) { Icon(Icons.Filled.ArrowBack, null) } }, actions = {
        article?.let { a ->
            IconButton(onClick = { shareText(ctx, a.title, a.url) }, Modifier.semantics { contentDescription = "Share article" }) { Icon(Icons.Filled.Share, null) }
            IconButton(onClick = { vm.openInBrowser(ctx, a) }, Modifier.semantics { contentDescription = "Open original article externally" }) { Icon(Icons.Filled.OpenInBrowser, null) }
        }
    }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            LiveAnnouncer(vm)
            val a = article
            if (a == null) { Text("Loading article") } else {
                LaunchedEffect(a.id) { if (!a.isRead) vm.setRead(a, true)
                    if (vm.repo.settings.articleOpenMode.first() == "FULL_TEXT" && fullText == null) { loadingFull = true; vm.loadFullText(a) { t -> if (t != null && stripHtml(t).length > stripHtml(a.content.ifBlank { a.description }).length) fullText = t; loadingFull = false } } }
                LazyColumn {
                    item { Text(a.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }); Text(DateFormat.getDateTimeInstance().format(Date(a.date)), style = MaterialTheme.typography.labelMedium); if (a.author.isNotBlank()) Text("By ${a.author}", style = MaterialTheme.typography.labelMedium) }
                    item { Row { Button(onClick = { vm.toggleFavorite(a) }, Modifier.semantics { contentDescription = if (a.isFavorite) "Remove favorite" else "Add favorite" }) { Text(if (a.isFavorite) "Unfavorite" else "Favorite") }; Spacer(Modifier.width(8.dp)); Button(onClick = { vm.setRead(a, !a.isRead) }, Modifier.semantics { contentDescription = if (a.isRead) "Mark unread" else "Mark read" }) { Text(if (a.isRead) "Mark unread" else "Mark read") }; if (a.mediaUrl != null) { Spacer(Modifier.width(8.dp)); Button(onClick = { vm.play(a); nav.navigate("player") }, Modifier.semantics { contentDescription = "Play media" }) { Text("Play") } } } }
                    item { Button(onClick = { loadingFull = true; vm.loadFullText(a) { t -> if (t != null && stripHtml(t).length > stripHtml(a.content.ifBlank { a.description }).length) fullText = t else if (t != null) fullText = t; loadingFull = false } }, Modifier.semantics { contentDescription = "Fetch full article text" }) { Text(if (loadingFull) "Fetching full text..." else if (fullText != null) "Full text shown. Fetch again" else "Fetch full text") } }
                    item { val body = fullText ?: a.content.ifBlank { a.description }; Text(stripHtml(body).ifBlank { "No article text available. Use Open externally to read in your browser." }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { contentDescription = "Article body" }) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(nav: NavController, vm: AppViewModel) {
    var q by remember { mutableStateOf("") }
    val feeds by vm.feeds.collectAsState()
    val results by remember(q) { if (q.isBlank()) kotlinx.coroutines.flow.flowOf(emptyList()) else vm.repo.articles.search(q) }.collectAsState(emptyList())
    Scaffold(topBar = { TopAppBar(title = { Text("Search", Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = "Back" }) { Icon(Icons.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            OutlinedTextField(q, { q = it }, label = { Text("Search articles") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search articles edit field" }, singleLine = true)
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
    val deleted by remember { vm.repo.misc.observeDeleted() }.collectAsState(emptyList())
    Scaffold(topBar = { TopAppBar(title = { Text("Deleted Articles", Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = "Back" }) { Icon(Icons.Filled.ArrowBack, null) } }, actions = { IconButton(onClick = { vm.purgeAllDeleted() }, Modifier.semantics { contentDescription = "Purge all deleted articles" }) { Icon(Icons.Filled.DeleteForever, null) } }) }) { pad ->
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
    val errs by vm.errorFeeds.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("Feeds with Errors", Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = "Back" }) { Icon(Icons.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.padding(pad)) { LiveAnnouncer(vm)
            if (errs.isEmpty()) Text("No feeds with errors.", Modifier.padding(16.dp))
            LazyColumn { items(errs, key = { it.id }) { f ->
                ActionListItem(f.title, "${f.lastError}\n${f.url}\nFailures: ${f.consecutiveFailures}", "Feed ${f.title} error ${f.lastError}. Double tap to retry.", onOpen = { vm.refreshFeed(f) },
                    actions = listOf("Retry feed" to { vm.refreshFeed(f) }, "Open feed" to { nav.navigate("articles/feed/${f.id}") }, "Delete feed" to { vm.deleteFeed(f) }))
                HorizontalDivider() } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartFoldersScreen(nav: NavController, vm: AppViewModel) {
    var name by remember { mutableStateOf("") }; var value by remember { mutableStateOf("") }; var field by remember { mutableStateOf("title") }
    val folders by remember { vm.repo.misc.observeSmart() }.collectAsState(emptyList())
    val feeds by vm.feeds.collectAsState()
    var selected by remember { mutableStateOf<SmartFolderEntity?>(null) }
    val all by remember { vm.repo.articles.observeAll() }.collectAsState(emptyList())
    Scaffold(topBar = { TopAppBar(title = { Text("Smart Folders", Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = "Back" }) { Icon(Icons.Filled.ArrowBack, null) } }) }) { pad ->
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
    var name by remember { mutableStateOf("") }; var value by remember { mutableStateOf("") }; var action by remember { mutableStateOf("mark_read") }; var target by remember { mutableStateOf("") }
    val rules by remember { vm.repo.misc.observeRules() }.collectAsState(emptyList())
    Scaffold(topBar = { TopAppBar(title = { Text("Filter Rules", Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = "Back" }) { Icon(Icons.Filled.ArrowBack, null) } }) }) { pad ->
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
    Scaffold(topBar = { TopAppBar(title = { Text("Add Feed", Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = "Back" }) { Icon(Icons.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { LiveAnnouncer(vm)
            Text("Paste a feed URL, website URL, YouTube channel, Reddit community, or Lemmy community URL. Discovery finds the feed automatically.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(url, { url = it }, label = { Text("Feed or website URL") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Feed or website URL edit field" }, singleLine = true)
            OutlinedTextField(cat, { cat = it }, label = { Text("Category") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Category edit field" }, singleLine = true)
            Button(onClick = { vm.addFeed(url, cat); nav.popBackStack() }, Modifier.fillMaxWidth().semantics { contentDescription = "Subscribe to feed" }) { Text("Subscribe") }
            HorizontalDivider()
            Text("OPML", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Button(onClick = { opmlLauncher.launch("text/*") }, Modifier.fillMaxWidth().semantics { contentDescription = "Import OPML file" }) { Text("Import OPML") }
            Button(onClick = { scope.launch { val opml = vm.repo.exportOpml(); val f = File(ctx.getExternalFilesDir(null), "blindrss-export.opml"); f.writeText(opml); exportMsg = "Exported to ${f.absolutePath}"; shareText(ctx, "BlindRSS OPML", opml.take(500)) } }, Modifier.fillMaxWidth().semantics { contentDescription = "Export OPML file" }) { Text("Export OPML") }
            if (exportMsg.isNotEmpty()) Text(exportMsg)
            HorizontalDivider()
            Text("Search podcast directories", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            var dq by remember { mutableStateOf("") }; var hits by remember { mutableStateOf<List<org.blindrss.android.feed.SearchHit>>(emptyList()) }; var searching by remember { mutableStateOf(false) }
            OutlinedTextField(dq, { dq = it }, label = { Text("Search Apple Podcasts and gPodder") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search podcast directories" }, singleLine = true)
            Button(onClick = { searching = true; vm.searchDirectory(dq) { hits = it; searching = false } }, Modifier.fillMaxWidth().semantics { contentDescription = "Search directories" }) { Text(if (searching) "Searching..." else "Search") }
            hits.forEach { h -> ActionListItem(h.title, "${h.source}: ${h.feedUrl}", "Podcast ${h.title} from ${h.source}. Double tap to subscribe.", onOpen = { vm.addFeed(h.feedUrl, cat) },
                actions = listOf("Subscribe" to { vm.addFeed(h.feedUrl, cat) })) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(nav: NavController, vm: AppViewModel) {
    val title by vm.player.title.collectAsState(); val playing by vm.player.playing.collectAsState(); val speed by vm.player.speed.collectAsState()
    var pos by remember { mutableStateOf(0L) }; var dur by remember { mutableStateOf(0L) }
    var sleepMin by remember { mutableStateOf(0) }
    LaunchedEffect(playing) { while (true) { pos = vm.player.position(); dur = vm.player.duration(); kotlinx.coroutines.delay(500) } }
    val chapters = remember { mutableStateOf(emptyList<ChapterEntity>()) }
    LaunchedEffect(title) { chapters.value = vm.player.chapterList() }
    Scaffold(topBar = { TopAppBar(title = { Text("Player", Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = "Back" }) { Icon(Icons.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { LiveAnnouncer(vm)
            Text(if (title.isBlank()) "Nothing playing. Open an article with media and press Play." else title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading(); contentDescription = "Now playing: $title" })
            Text("Position ${pos / 1000} seconds of ${dur / 1000} seconds", Modifier.semantics { contentDescription = "Position ${pos / 1000} of ${dur / 1000} seconds" })
            if (dur > 0) Slider((pos.toFloat() / dur).coerceIn(0f, 1f), { vm.player.seekTo((it * dur).toLong()) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Seek slider" })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { vm.player.seekBy(-10000) }, Modifier.semantics { contentDescription = "Rewind 10 seconds" }) { Icon(Icons.Filled.Replay10, null) }
                IconButton(onClick = { vm.player.toggle() }, Modifier.semantics { contentDescription = if (playing) "Pause" else "Play" }) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null) }
                IconButton(onClick = { vm.player.seekBy(10000) }, Modifier.semantics { contentDescription = "Forward 10 seconds" }) { Icon(Icons.Filled.Forward10, null) }
                IconButton(onClick = { vm.player.prevChapter() }, Modifier.semantics { contentDescription = "Previous chapter" }) { Icon(Icons.Filled.SkipPrevious, null) }
                IconButton(onClick = { vm.player.nextChapter() }, Modifier.semantics { contentDescription = "Next chapter" }) { Icon(Icons.Filled.SkipNext, null) }
            }
            Text("Speed: ${speed}x", Modifier.semantics { heading() })
            Row { listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s -> FilterChip(speed == s, { vm.player.setSpeed(s); kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { vm.repo.settings.setSpeed(s) } }, { Text("${s}x") }, modifier = Modifier.semantics { contentDescription = "Playback speed ${s}x" }) } }
            Text("Sleep timer", Modifier.semantics { heading() })
            Row { listOf(0, 15, 30, 60).forEach { m -> FilterChip(sleepMin == m, { sleepMin = m; vm.player.setSleepTimer(m) }, { Text(if (m == 0) "Off" else "$m min") }, modifier = Modifier.semantics { contentDescription = if (m == 0) "Sleep timer off" else "Sleep timer $m minutes" }) } }
            var silence by remember { mutableStateOf(true) }; var sponsor by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) { silence = vm.repo.settings.skipSilence.first(); sponsor = vm.repo.settings.sponsorBlock.first() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Skip silence"); Switch(silence, { silence = it; vm.player.setSkipSilence(it) }, Modifier.semantics { contentDescription = "Skip silence switch" }) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("SponsorBlock (YouTube)"); Switch(sponsor, { sponsor = it; kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { vm.repo.settings.setSponsorBlock(it) } }, Modifier.semantics { contentDescription = "SponsorBlock switch" }) }
            Text("Equalizer", Modifier.semantics { heading() })
            var eqOn by remember { mutableStateOf(false) }; var bands by remember { mutableStateOf(List(5) { 0 }) }
            LaunchedEffect(Unit) { eqOn = vm.repo.settings.eqEnabled.first(); val saved = vm.repo.settings.eqBands.first(); if (saved.isNotEmpty()) bands = (saved + List(5) { 0 }).take(5) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Equalizer on"); Switch(eqOn, { eqOn = it; vm.player.applyEqualizer(it, bands.map { b -> b * 15 }) }, Modifier.semantics { contentDescription = "Equalizer on switch" }) }
            bands.forEachIndexed { i, v -> Text("Band ${i + 1}: $v"); Slider(v.toFloat(), { nv -> bands = bands.toMutableList().also { it[i] = nv.toInt() }; vm.player.applyEqualizer(eqOn, bands.map { b -> b * 15 }) }, valueRange = -100f..100f, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Equalizer band ${i + 1}" }) }
            Text("Cast to DLNA / UPnP", Modifier.semantics { heading() })
            val castCtx = LocalContext.current
            val devices by vm.castDevices.collectAsState()
            Button(onClick = { vm.discoverCast(castCtx) }, Modifier.fillMaxWidth().semantics { contentDescription = "Search for DLNA cast devices" }) { Text("Search for DLNA devices") }
            devices.forEach { d -> ListItem(headlineContent = { Text(d.name) }, modifier = Modifier.clickable { vm.castCurrent(d) }.semantics { contentDescription = "Cast current media to ${d.name}"; role = Role.Button }) }
            val ch = chapters.value
            if (ch.isNotEmpty()) { Text("Chapters", Modifier.semantics { heading() }); ch.forEachIndexed { i, c -> ListItem(headlineContent = { Text(c.title) }, supportingContent = { Text("${c.startMs / 1000} seconds") }, modifier = Modifier.clickable { vm.player.seekChapter(i) }.semantics { contentDescription = "Chapter ${c.title} at ${c.startMs / 1000} seconds"; role = Role.Button }) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(nav: NavController, vm: AppViewModel) {
    val feeds by vm.feeds.collectAsState()
    val all by remember { vm.repo.articles.observeAll() }.collectAsState(emptyList())
    val feedCat = feeds.associate { it.id to it.category }
    val names = remember(all, feeds) { val counts = mutableMapOf<String, Int>()
        for (a in all) { val folder = a.folder ?: feedCat[a.feedId] ?: "Uncategorized"; counts[folder] = (counts[folder] ?: 0) + 1
            for (l in a.labels.split(",").map { it.trim() }.filter { it.isNotEmpty() && it != "__no_notify" }) counts["Label: $l"] = (counts["Label: $l"] ?: 0) + 1 }
        counts.entries.sortedBy { it.key } }
    Scaffold(topBar = { TopAppBar(title = { Text("Categories and Labels", Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = "Back" }) { Icon(Icons.Filled.ArrowBack, null) } }) }) { pad ->
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
    val feeds by vm.feeds.collectAsState()
    val all by remember { vm.repo.articles.observeAll() }.collectAsState(emptyList())
    val feedCat = feeds.associate { it.id to it.category }
    val matched = remember(all, name) { all.filter { a -> if (name.startsWith("Label: ")) name.removePrefix("Label: ") in a.labels.split(",").map { it.trim() } else (a.folder ?: feedCat[a.feedId] ?: "Uncategorized") == name } }
    Scaffold(topBar = { TopAppBar(title = { Text(name, Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = "Back" }) { Icon(Icons.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.padding(pad)) { LiveAnnouncer(vm); ArticleList(matched, feeds, nav, vm, "No articles in $name") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController, vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(ProviderType.LOCAL) }; var url by remember { mutableStateOf("") }; var user by remember { mutableStateOf("") }; var secret by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }; var appKey by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var syncInterval by remember { mutableStateOf(60) }; var notify by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { provider = vm.repo.settings.provider.first(); url = vm.repo.settings.serverUrl.first(); user = vm.repo.settings.username.first(); syncInterval = vm.repo.settings.syncInterval.first(); notify = vm.repo.settings.notifyNew.first() }
    Scaffold(topBar = { TopAppBar(title = { Text("Settings", Modifier.semantics { heading() }) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = "Back" }) { Icon(Icons.Filled.ArrowBack, null) } }) }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { LiveAnnouncer(vm); Text("Provider", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                Text("Local stores feeds on device. Hosted providers sync feeds, read and favorite state. Credentials are encrypted with Android Keystore AES-GCM.", style = MaterialTheme.typography.bodySmall) }
            item { Column { ProviderType.entries.forEach { p -> Row(Modifier.clickable { provider = p }.semantics { contentDescription = "Provider ${p.name}"; role = Role.RadioButton; selected = provider == p }) { RadioButton(provider == p, { provider = p }, Modifier.semantics { contentDescription = "Select provider ${p.name}" }); Text(when (p) { ProviderType.LOCAL -> "Local (on device)"; ProviderType.MINIFLUX -> "Miniflux"; ProviderType.INOREADER -> "Inoreader (OAuth token)"; ProviderType.THEOLDREADER -> "The Old Reader"; ProviderType.BAZQUX -> "BazQux"; ProviderType.GREADER -> "Google Reader / FreshRSS"; ProviderType.FEVER -> "Fever (FreshRSS Fever API)"; ProviderType.NEWSBLUR -> "NewsBlur (session cookie)" }) } } } }
            if (provider != ProviderType.LOCAL) {
                item { OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Server URL. For FreshRSS include path ending api/greader.php" }, singleLine = true) }
                item { OutlinedTextField(user, { user = it }, label = { Text("Username or email") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Username or email" }, singleLine = true) }
                item { OutlinedTextField(secret, { secret = it }, label = { Text("Password, API key or token") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Password, API key or token. Stored encrypted." }, singleLine = true) }
                if (provider == ProviderType.INOREADER) { item { OutlinedTextField(appId, { appId = it }, label = { Text("Inoreader App ID") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Inoreader App ID" }) }; item { OutlinedTextField(appKey, { appKey = it }, label = { Text("Inoreader App Key") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Inoreader App Key" }) } }
                item { LaunchedEffect(provider) { val a = vm.repo.settings.account(provider); if (a.url.isNotEmpty()) url = a.url; if (a.user.isNotEmpty()) user = a.user; if (a.appId.isNotEmpty()) appId = a.appId; if (a.appKey.isNotEmpty()) appKey = a.appKey }
                    Button(onClick = { scope.launch { val old = vm.repo.settings.account(provider); val sec = secret.ifEmpty { old.secret }; val ref = old.refresh; vm.repo.settings.saveProvider(provider, url, user, sec, appId, appKey, ref); vm.repo.settings.saveAccount(provider, org.blindrss.android.settings.SettingsStore.Account(url, user, sec, appId, appKey, ref)); status = "Saved" } }, Modifier.fillMaxWidth().semantics { contentDescription = "Save provider settings" }) { Text("Save provider") }
                    if (provider == ProviderType.INOREADER) { val ictx = LocalContext.current
                        Button(onClick = { scope.launch { val a = vm.repo.settings.account(ProviderType.INOREADER); val id = appId.ifEmpty { a.appId }; if (id.isEmpty()) { status = "Enter Inoreader App ID first, then Save provider" } else { vm.repo.settings.saveAccount(ProviderType.INOREADER, a.copy(appId = id, appKey = appKey.ifEmpty { a.appKey })); ictx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(org.blindrss.android.providers.InoreaderProvider.authorizationUrl(id)))) } } }, Modifier.fillMaxWidth().semantics { contentDescription = "Connect Inoreader account in browser" }) { Text("Connect Inoreader (browser)") } }
                    Button(onClick = { scope.launch { status = "Testing..."; try { val p = vm.repo.buildProvider(); status = p?.testLogin() ?: "Local mode" } catch (e: Exception) { status = "Login failed: ${e.message}" } } }, Modifier.fillMaxWidth().semantics { contentDescription = "Test login" }) { Text("Test login") }
                    Button(onClick = { scope.launch { status = "Syncing..."; try { status = vm.repo.syncProvider() } catch (e: Exception) { status = "Sync failed: ${e.message}" } } }, Modifier.fillMaxWidth().semantics { contentDescription = "Sync now with provider" }) { Text("Sync now") } }
            }
            item { Text("Reading", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                var openMode by remember { mutableStateOf("BROWSER") }; var ytMode by remember { mutableStateOf("YOUTUBE_APP") }
                LaunchedEffect(Unit) { openMode = vm.repo.settings.articleOpenMode.first(); ytMode = vm.repo.settings.youtubeOpenMode.first() }
                Text("When I open an article", style = MaterialTheme.typography.bodyMedium)
                listOf("BROWSER" to "Open in default browser", "FULL_TEXT" to "Read full text in app", "FEED_TEXT" to "Read feed text in app").forEach { (v, l) ->
                    Row(Modifier.clickable { openMode = v; scope.launch { vm.repo.settings.setArticleOpenMode(v) } }.semantics { contentDescription = "Article opens: $l"; role = Role.RadioButton; selected = openMode == v }) { RadioButton(openMode == v, { openMode = v; scope.launch { vm.repo.settings.setArticleOpenMode(v) } }, Modifier.semantics { contentDescription = "Select: $l" }); Text(l) } }
                Text("When I open a YouTube video", style = MaterialTheme.typography.bodyMedium)
                listOf("YOUTUBE_APP" to "YouTube app", "SMARTTUBE" to "SmartTube", "BROWSER" to "Browser", "IN_APP" to "Read or play in app").forEach { (v, l) ->
                    Row(Modifier.clickable { ytMode = v; scope.launch { vm.repo.settings.setYoutubeOpenMode(v) } }.semantics { contentDescription = "YouTube opens in: $l"; role = Role.RadioButton; selected = ytMode == v }) { RadioButton(ytMode == v, { ytMode = v; scope.launch { vm.repo.settings.setYoutubeOpenMode(v) } }, Modifier.semantics { contentDescription = "Select: $l" }); Text(l) } }
                Text("If the chosen video app is not installed, BlindRSS opens your browser instead.", style = MaterialTheme.typography.bodySmall) }
            item { Text("Background sync", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                Row { listOf(15, 30, 60, 240).forEach { m -> FilterChip(syncInterval == m, { syncInterval = m; scope.launch { vm.repo.settings.setSyncInterval(m); org.blindrss.android.sync.SyncWorker.schedule(nav.context, m) } }, { Text("$m min") }, modifier = Modifier.semantics { contentDescription = "Sync every $m minutes" }) } }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Notify on new articles"); Switch(notify, { notify = it; scope.launch { vm.repo.settings.setNotify(it) } }, Modifier.semantics { contentDescription = "Notify on new articles switch" }) } }
            item { val uctx = LocalContext.current
                Button(onClick = { scope.launch { status = "Checking for updates..."; val latest = org.blindrss.android.feed.UpdateCheck.latestVersion(); val current = try { uctx.packageManager.getPackageInfo(uctx.packageName, 0).versionName ?: "1.0.0" } catch (_: Exception) { "1.0.0" }; status = when { latest == null -> "Update check failed (offline?)"; latest == current -> "Up to date ($current)"; else -> "Update available: $latest (installed $current). Get it from GitHub releases." } } }, Modifier.fillMaxWidth().semantics { contentDescription = "Check for app updates" }) { Text("Check for updates") } }
            item { if (status.isNotEmpty()) Text(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            item { Text("Ported BlindRSS settings: provider selection and credentials, per-feed notification mute (feed list), playback speed and sleep timer (Player), sync interval, new-article notifications. Desktop-only items are listed in AUDIT.md.", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
