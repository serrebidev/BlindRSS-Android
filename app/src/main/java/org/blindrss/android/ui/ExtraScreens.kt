package org.blindrss.android.ui
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.blindrss.android.R
import org.blindrss.android.feed.VideoResult

// YouTube account (device-code sign-in, SmartTube-style): shows the code to
// enter at google.com/device and polls until Google answers.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeAccountScreen(nav: androidx.navigation.NavController, vm: AppViewModel) {
    val ctx = LocalContext.current
    val accountName by vm.repo.settings.youtubeAccountName.collectAsState("")
    val state by vm.ytSignIn.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("YouTube account", Modifier.semantics { heading() }) },
        navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = ctx.getString(R.string.back) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LiveAnnouncer(vm)
            if (accountName.isNotBlank()) {
                Text(stringResource(R.string.signed_in_to_youtube_recommendations))
                Button(onClick = { nav.navigate("ythome") }, Modifier.fillMaxWidth().semantics { contentDescription = ctx.getString(R.string.open_youtube_home) }) { Text(stringResource(R.string.youtube_home)) }
                OutlinedButton(onClick = { vm.signOutYouTube() }, Modifier.fillMaxWidth().semantics { contentDescription = ctx.getString(R.string.sign_out_of_youtube) }) { Text(stringResource(R.string.sign_out)) }
            } else when (val st = state) {
                is AppViewModel.YtSignIn.Working -> Text(stringResource(R.string.contacting_google))
                is AppViewModel.YtSignIn.Waiting -> {
                    Text(stringResource(R.string.on_another_device_or_in))
                    Text(st.code.userCode, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { contentDescription = "Code: ${st.code.userCode}" })
                    Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(st.code.verificationUrl))) },
                        Modifier.fillMaxWidth().semantics { contentDescription = ctx.getString(R.string.open_google_com_device_in) }) { Text(stringResource(R.string.open_google_com_device)) }
                    Text(stringResource(R.string.waiting_for_you_to_finish))
                }
                is AppViewModel.YtSignIn.Failed -> { Text(st.message); Button(onClick = { vm.startYouTubeSignIn() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.try_again)) } }
                else -> {
                    Text(stringResource(R.string.sign_in_with_the_same))
                    Button(onClick = { vm.startYouTubeSignIn() }, Modifier.fillMaxWidth().semantics { contentDescription = ctx.getString(R.string.sign_in_to_youtube) }) { Text(stringResource(R.string.sign_in_to_youtube)) }
                }
            }
        }
    }
}

// Recommendations + subscriptions shelf for the signed-in account.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeHomeScreen(nav: androidx.navigation.NavController, vm: AppViewModel) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("Loading") }
    var recs by remember { mutableStateOf<List<VideoResult>>(emptyList()) }
    var subs by remember { mutableStateOf<List<VideoResult>>(emptyList()) }
    LaunchedEffect(Unit) { try { val (r, s) = vm.repo.youtubeHome(); recs = r; subs = s; status = if (r.isEmpty() && s.isEmpty()) "Nothing returned. The sign-in may have expired." else "" }
        catch (e: Exception) { status = "Could not load YouTube home: ${e.message}" } }
    Scaffold(topBar = { TopAppBar(title = { Text("YouTube home", Modifier.semantics { heading() }) },
        navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = ctx.getString(R.string.back) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) { LiveAnnouncer(vm)
            if (status.isNotEmpty()) Text(status)
            LazyColumn {
                if (recs.isNotEmpty()) { item { Text("Recommendations", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium) }
                    items(recs) { r -> YouTubeResultRow(r, vm, ctx) } }
                if (subs.isNotEmpty()) { item { Text("From your subscriptions", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium) }
                    items(subs) { r -> YouTubeResultRow(r, vm, ctx) } }
            }
        }
    }
}

@Composable
private fun YouTubeResultRow(r: VideoResult, vm: AppViewModel, ctx: android.content.Context) {
    val article = vm.videoArticle(r)
    val actions = mutableListOf<Pair<String, () -> Unit>>()
    if (vm.canPlayVideo(r)) { actions += "Play" to { vm.play(article) }; actions += "Play next" to { vm.playNext(article) }
        actions += "Add to Up next" to { vm.enqueue(article) }; actions += "Download" to { vm.download(article) } }
    if (r.channelUrl.isNotEmpty()) actions += "Subscribe to channel" to { vm.addFeed(r.channelUrl) }
    actions += "Open in browser" to { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url))) }
    ActionListItem(r.title, r.uploader, "${r.title}. ${r.uploader}.", null, actions)
}

// Open Article by URL: the page's readable text, in the reader's block form.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenArticleScreen(nav: androidx.navigation.NavController, vm: AppViewModel, url: String) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf("") }; var blocks by remember { mutableStateOf<List<org.blindrss.android.feed.FullText.Block>>(emptyList()) }
    var status by remember { mutableStateOf("Loading") }
    LaunchedEffect(url) { try { val (t, html) = vm.repo.openUrlArticle(url); title = t; blocks = org.blindrss.android.feed.FullText.blocks(html); status = if (blocks.isEmpty()) "No readable text found on this page." else "" }
        catch (e: Exception) { status = "Could not open the page: ${e.message}" } }
    Scaffold(topBar = { TopAppBar(title = { Text(title.ifEmpty { "Article" }, Modifier.semantics { heading() }) },
        navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = ctx.getString(R.string.back) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) { LiveAnnouncer(vm)
            if (status.isNotEmpty()) Text(status)
            LazyColumn { items(blocks) { b ->
                if (b.heading) Text(b.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() })
                else Text(b.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 10.dp)) } }
        }
    }
}

// Sign in to Google Groups in a WebView; on Save, the session cookies are
// copied into the app's encrypted cookie store, so sign-in-only groups read
// through the same fetch identity as everything else. This is the route
// other Android apps use: Google publishes no Groups content API for
// consumer accounts (the Groups API is Workspace admin directory only).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleSignInScreen(nav: androidx.navigation.NavController, vm: AppViewModel) {
    val ctx = LocalContext.current
    var webView by remember { mutableStateOf<android.webkit.WebView?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text("Sign in to Google Groups", Modifier.semantics { heading() }) },
        navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = ctx.getString(R.string.back) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
        actions = { TextButton(onClick = {
            val cookies = android.webkit.CookieManager.getInstance().getCookie("https://groups.google.com") ?: ""
            if (cookies.isBlank()) { vm.announce("No Google session cookies found yet. Sign in first.") }
            else { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    val existing = vm.repo.settings.siteCookies()
                    vm.repo.settings.setSiteCookies(if (existing.isBlank()) cookies else "$existing; $cookies") }
                vm.announce("Google sign-in cookies saved"); nav.popBackStack() }
        }, Modifier.semantics { contentDescription = ctx.getString(R.string.save_sign_in_cookies) }) { Text(stringResource(R.string.save)) } }) }) { pad ->
        Column(Modifier.padding(pad)) {
            Text("Sign in with your Google account below. When your groups load, choose Save at the top.", Modifier.padding(16.dp))
            AndroidView(factory = { c -> android.webkit.WebView(c).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = android.webkit.WebViewClient()
                loadUrl("https://groups.google.com/"); webView = this } },
                modifier = Modifier.fillMaxSize())
        }
    }
    DisposableEffect(Unit) { onDispose { webView?.destroy() } }
}
