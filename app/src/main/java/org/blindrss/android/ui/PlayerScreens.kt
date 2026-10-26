package org.blindrss.android.ui
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import org.blindrss.android.R
import org.blindrss.android.data.ChapterEntity

private fun clock(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
private fun spoken(ms: Long): String { val s = ms / 1000; val h = s / 3600; val m = s / 60 % 60
    return listOfNotNull(if (h > 0) "$h hours" else null, if (m > 0) "$m minutes" else null, "${s % 60} seconds").joinToString(" ") }

// Shown under every screen except the full player while something is loaded: title (opens the full player),
// back 10 s, play or pause, forward 30 s.
@Composable
fun MiniPlayer(nav: NavController, vm: AppViewModel) {
    val ctx = LocalContext.current
    val title by vm.player.title.collectAsState(); val playing by vm.player.playing.collectAsState()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    if (title.isBlank() || route == "player") return
    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClickLabel = "open player", role = Role.Button) { nav.navigate("player") }
                .semantics(mergeDescendants = true) { contentDescription = "Now playing: $title. ${if (playing) "Playing" else "Paused"}" }.padding(vertical = 10.dp)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(if (playing) "Playing" else "Paused", style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = { vm.player.back() }, Modifier.semantics { contentDescription = ctx.getString(R.string.back_10_seconds) }) { Icon(Icons.Filled.Replay10, null) }
            IconButton(onClick = { vm.player.toggle() }, Modifier.semantics { contentDescription = if (playing) "Pause" else "Play" }) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null) }
            IconButton(onClick = { vm.player.forward() }, Modifier.semantics { contentDescription = ctx.getString(R.string.forward_30_seconds) }) { Icon(Icons.Filled.Forward30, null) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(nav: NavController, vm: AppViewModel) {
    val ctx = LocalContext.current
    val p = vm.player
    val title by p.title.collectAsState(); val playing by p.playing.collectAsState(); val speed by p.speed.collectAsState()
    val queue by p.queue.collectAsState(); val index by p.index.collectAsState(); val error by p.error.collectAsState()
    val sleepAt by p.sleepAt.collectAsState(); val stopAtEnd by p.stopAtEnd.collectAsState()
    val downloads by remember { vm.repo.misc.observeDownloaded() }.collectAsState(emptyList())
    var pos by remember { mutableStateOf(0L) }; var dur by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { pos = p.position(); dur = p.duration(); kotlinx.coroutines.delay(500) } }
    var chapters by remember { mutableStateOf(emptyList<ChapterEntity>()) }
    LaunchedEffect(title) { kotlinx.coroutines.delay(1500); chapters = p.chapterList() }
    Scaffold(topBar = { TopAppBar(title = { Text("Now playing", Modifier.semantics { heading() }) },
        navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = ctx.getString(R.string.minimize_player) }) { Icon(Icons.Filled.KeyboardArrowDown, null) } }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { LiveAnnouncer(vm)
                Text(title.ifBlank { "Nothing playing. Use Play on an episode." }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if (dur > 0) item {
                Text("${clock(pos)} of ${clock(dur)}", Modifier.semantics { contentDescription = "${spoken(pos)} of ${spoken(dur)}" })
                Slider((pos.toFloat() / dur).coerceIn(0f, 1f), { p.seekTo((it * dur).toLong()) }, Modifier.fillMaxWidth().semantics { contentDescription = ctx.getString(R.string.position) })
            }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = { p.previousEpisode() }, Modifier.semantics { contentDescription = ctx.getString(R.string.previous_episode) }) { Icon(Icons.Filled.SkipPrevious, null) }
                IconButton(onClick = { p.back() }, Modifier.semantics { contentDescription = ctx.getString(R.string.back_10_seconds) }) { Icon(Icons.Filled.Replay10, null) }
                FilledIconButton(onClick = { p.toggle() }, Modifier.size(56.dp).semantics { contentDescription = if (playing) "Pause" else "Play" }) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null) }
                IconButton(onClick = { p.forward() }, Modifier.semantics { contentDescription = ctx.getString(R.string.forward_30_seconds) }) { Icon(Icons.Filled.Forward30, null) }
                IconButton(onClick = { p.nextEpisode() }, Modifier.semantics { contentDescription = ctx.getString(R.string.next_episode) }) { Icon(Icons.Filled.SkipNext, null) }
            } }
            if (chapters.isNotEmpty()) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ p.prevChapter() }, Modifier.weight(1f)) { Text(stringResource(R.string.previous_chapter)) }
                OutlinedButton({ p.nextChapter() }, Modifier.weight(1f)) { Text(stringResource(R.string.next_chapter)) } } }
            item { Text("Speed: ${speed}x", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f).forEach { s ->
                    FilterChip(speed == s, { p.setSpeed(s) }, { Text("${s}x") }, modifier = Modifier.semantics { contentDescription = "Speed ${s}x" }) } } }
            item { Text("Sleep timer", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                var chosen by remember { mutableStateOf(0) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val active = when { stopAtEnd -> -1; sleepAt > 0 -> chosen; else -> 0 }
                    listOf(0 to "Off", -1 to "End of episode", 15 to "15 min", 30 to "30 min", 60 to "60 min").forEach { (m, l) ->
                        FilterChip(active == m, { chosen = m; p.setSleepTimer(m) }, { Text(l) }, modifier = Modifier.semantics { contentDescription = "Sleep timer: $l" }) } }
                if (sleepAt > 0) Text("Pauses at ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(sleepAt))}") }
            if (chapters.isNotEmpty()) {
                item { Text("Chapters", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall) }
                itemsIndexed(chapters) { i, c -> ListItem(headlineContent = { Text(c.title) }, supportingContent = { Text(clock(c.startMs)) },
                    modifier = Modifier.clickable(role = Role.Button) { p.seekChapter(i) }.semantics(mergeDescendants = true) { contentDescription = "Chapter ${c.title} at ${spoken(c.startMs)}" }) }
            }
            item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Up next", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                if (queue.isNotEmpty()) TextButton({ p.clearQueue() }) { Text(stringResource(R.string.clear)) } } }
            if (queue.isEmpty()) item { Text("Empty. Use Add to Up next on an episode.", style = MaterialTheme.typography.bodySmall) }
            itemsIndexed(queue) { i, q -> val label = if (i == index) "Now playing" else if (i > index) "Up next ${i - index}" else "Played"
                ActionListItem(q.title, label, "${q.title}, $label. Double tap to play. Double tap and hold for actions.", onOpen = { p.playAt(i) },
                    actions = listOfNotNull("Play now" to { p.playAt(i) }, if (i > 0) "Move up" to { p.moveUp(i) } else null, "Remove from Up next" to { p.removeAt(i) })) }
            item { Text("Downloads", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall) }
            if (downloads.isEmpty()) item { Text("No downloaded episodes. Use Download episode on an episode.", style = MaterialTheme.typography.bodySmall) }
            itemsIndexed(downloads, key = { _, a -> a.id }) { _, a ->
                ActionListItem(a.title, if (a.isRead) "Played" else "Not played", "Downloaded: ${a.title}. Double tap to play. Double tap and hold for actions.", onOpen = { vm.play(a) },
                    actions = listOf("Play" to { vm.play(a) }, "Add to Up next" to { vm.enqueue(a) }, "Delete download" to { vm.deleteDownload(a) })) }
            item { AudioOptions(vm) }
        }
    }
}

// The Cast SDK's route button (a platform view), labeled for TalkBack,
// plus the session listener that hands the current media to a new session.
@Composable
private fun GoogleCastButton(vm: AppViewModel) {
    val ctx = LocalContext.current
    val castContext = remember { org.blindrss.android.cast.GoogleCast.context(ctx) }
    if (castContext == null) { Text(stringResource(R.string.google_cast_is_not_available)); return }
    DisposableEffect(castContext) {
        val listener = object : com.google.android.gms.cast.framework.SessionManagerListener<com.google.android.gms.cast.framework.CastSession> {
            override fun onSessionStarted(session: com.google.android.gms.cast.framework.CastSession, sessionId: String) { vm.castToGoogleCast(session) }
            override fun onSessionResumed(session: com.google.android.gms.cast.framework.CastSession, wasSuspended: Boolean) {}
            override fun onSessionEnded(session: com.google.android.gms.cast.framework.CastSession, error: Int) {}
            override fun onSessionEnding(session: com.google.android.gms.cast.framework.CastSession) {}
            override fun onSessionStarting(session: com.google.android.gms.cast.framework.CastSession) {}
            override fun onSessionResuming(session: com.google.android.gms.cast.framework.CastSession, sessionId: String) {}
            override fun onSessionResumeFailed(session: com.google.android.gms.cast.framework.CastSession, error: Int) {}
            override fun onSessionStartFailed(session: com.google.android.gms.cast.framework.CastSession, error: Int) {}
            override fun onSessionSuspended(session: com.google.android.gms.cast.framework.CastSession, reason: Int) {}
        }
        castContext.sessionManager.addSessionManagerListener(listener, com.google.android.gms.cast.framework.CastSession::class.java)
        onDispose { castContext.sessionManager.removeSessionManagerListener(listener, com.google.android.gms.cast.framework.CastSession::class.java) }
    }
    androidx.compose.ui.viewinterop.AndroidView(factory = { c ->
        androidx.mediarouter.app.MediaRouteButton(c).apply {
            contentDescription = ctx.getString(R.string.cast_to_chromecast)
            try { com.google.android.gms.cast.framework.CastButtonFactory.setUpMediaRouteButton(c, this) } catch (_: Exception) {}
        }
    }, modifier = Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = ctx.getString(R.string.cast_to_chromecast) })
}

// Skip silence, SponsorBlock, equalizer and DLNA casting, as before.
@Composable
private fun AudioOptions(vm: AppViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val ctx = LocalContext.current
        var silence by remember { mutableStateOf(true) }; var sponsor by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) { silence = vm.repo.settings.skipSilence.first(); sponsor = vm.repo.settings.sponsorBlock.first() }
        Text("Audio", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.skip_silence)); Switch(silence, { silence = it; vm.player.setSkipSilence(it) }, Modifier.semantics { contentDescription = ctx.getString(R.string.skip_silence) }) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.sponsorblock_youtube)); Switch(sponsor, { sponsor = it; kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { vm.repo.settings.setSponsorBlock(it) } }, Modifier.semantics { contentDescription = ctx.getString(R.string.sponsorblock) }) }
        var eqOn by remember { mutableStateOf(false) }; var bands by remember { mutableStateOf(List(5) { 0 }) }
        LaunchedEffect(Unit) { eqOn = vm.repo.settings.eqEnabled.first(); val saved = vm.repo.settings.eqBands.first(); if (saved.isNotEmpty()) bands = (saved + List(5) { 0 }).take(5) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(R.string.equalizer)); Switch(eqOn, { eqOn = it; vm.player.applyEqualizer(it, bands.map { b -> b * 15 }) }, Modifier.semantics { contentDescription = ctx.getString(R.string.equalizer) }) }
        if (eqOn) bands.forEachIndexed { i, v -> Text("Band ${i + 1}: $v"); Slider(v.toFloat(), { nv -> bands = bands.toMutableList().also { it[i] = nv.toInt() }; vm.player.applyEqualizer(eqOn, bands.map { b -> b * 15 }) }, valueRange = -100f..100f, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Equalizer band ${i + 1}" }) }
        Text("Cast to Chromecast", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.uses_google_s_default_media))
        GoogleCastButton(vm)
        Text("Cast to DLNA / UPnP", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
        val devices by vm.castDevices.collectAsState()
        OutlinedButton(onClick = { vm.discoverCast(ctx) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.search_for_dlna_devices)) }
        devices.forEach { d -> ListItem(headlineContent = { Text(d.name) }, modifier = Modifier.clickable(role = Role.Button) { vm.castCurrent(d) }.semantics(mergeDescendants = true) { contentDescription = "Cast to ${d.name}" }) }
    }
}
