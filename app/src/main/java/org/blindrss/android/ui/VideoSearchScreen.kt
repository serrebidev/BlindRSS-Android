package org.blindrss.android.ui
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.blindrss.android.R
import org.blindrss.android.feed.VideoResult
import org.blindrss.android.feed.VideoSearch

// Video Search (the desktop's Tools menu surface): search YouTube,
// SoundCloud, Mixcloud and the other yt-dlp sites, then play, download or
// subscribe without leaving the app. Every row is one TalkBack stop with
// the result's actions, like the article lists.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoSearchScreen(nav: androidx.navigation.NavController, vm: AppViewModel) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var siteId by remember { mutableStateOf("all") }
    var searching by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<VideoResult>>(emptyList()) }
    fun detail(r: VideoResult): String = buildString {
        append(r.site)
        if (r.uploader.isNotEmpty()) append(". ").append(r.uploader)
        if (r.durationSec > 0) append(". ").append(formatDur(r.durationSec))
        if (r.viewCount > 0) append(". ").append(r.viewCount).append(" plays")
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.video_search_title), Modifier.semantics { heading() }) },
        navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = ctx.getString(R.string.back) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.video_search_query)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Video search edit field" })
            LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(siteId == "all", { siteId = "all" }, { Text(stringResource(R.string.video_search_all_sites)) }) }
                items(VideoSearch.SITES) { s -> FilterChip(siteId == s.id, { siteId = s.id }, { Text(s.label) }) }
            }
            Button(onClick = { if (query.isBlank()) { status = "Type a search first."; return@Button }
                searching = true; status = "Searching"; results = emptyList()
                vm.searchVideos(query.trim(), siteId) { found, error -> searching = false; results = found
                    status = error ?: "${found.size} results."; if (found.isNotEmpty()) vm.announce("${found.size} results") } },
                enabled = !searching, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.search)) }
            if (status.isNotEmpty()) Text(status, Modifier.padding(top = 8.dp))
            LazyColumn(Modifier.padding(top = 8.dp)) {
                items(results) { r ->
                    val article = vm.videoArticle(r)
                    val actions = mutableListOf<Pair<String, () -> Unit>>()
                    if (vm.canPlayVideo(r)) {
                        actions += "Play" to { vm.play(article) }
                        actions += "Play next" to { vm.playNext(article) }
                        actions += "Add to Up next" to { vm.enqueue(article) }
                        actions += "Download" to { vm.download(article) }
                    }
                    if (r.channelUrl.isNotEmpty()) actions += "Subscribe to channel" to { vm.addFeed(r.channelUrl) }
                    actions += "Open in browser" to { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url))) }
                    ActionListItem(r.title, detail(r), "${r.title}. ${detail(r)}.", null, actions)
                }
            }
        }
    }
}

private fun formatDur(sec: Long): String =
    if (sec >= 3600) String.format("%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
    else String.format("%d:%02d", sec / 60, sec % 60)
