package org.blindrss.android.ui
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import org.blindrss.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.blindrss.android.feed.FullText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(nav: NavController, vm: AppViewModel) {
    val ctx = LocalContext.current
    var blocks by remember { mutableStateOf<List<FullText.Block>>(emptyList()) }
    LaunchedEffect(Unit) {
        val md = withContext(Dispatchers.IO) { try { ctx.assets.open("help_en.md").bufferedReader().readText() } catch (_: Exception) { "" } }
        blocks = HelpText.blocks(md)
    }
    Scaffold(topBar = { TopAppBar(title = { Text("User guide", Modifier.semantics { heading() }) },
        navigationIcon = { IconButton(onClick = { nav.popBackStack() }, Modifier.semantics { contentDescription = ctx.getString(R.string.back) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            LiveAnnouncer(vm)
            if (blocks.isEmpty()) Text("The guide could not be loaded.", style = MaterialTheme.typography.bodyLarge)
            else LazyColumn {
                items(blocks) { b ->
                    if (b.heading) Text(b.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp).semantics { heading() })
                    else Text(b.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 10.dp))
                }
            }
        }
    }
}
