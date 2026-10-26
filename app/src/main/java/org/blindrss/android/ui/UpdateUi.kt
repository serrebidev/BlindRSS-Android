package org.blindrss.android.ui
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.blindrss.android.R
import org.blindrss.android.update.AppUpdate
import java.io.File

// The self-update flow, shared by the Home update dialog and Settings >
// About: explain, ask for the install permission when Android requires it,
// download with spoken progress, then hand the APK to the system installer.
// Nothing installs silently: the user taps through, and Android's installer
// shows its own confirmation.
@Composable
fun UpdateInstallerPanel(info: AppUpdate.ReleaseInfo, onFinished: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf("idle") } // idle | permission | downloading | ready | error
    var progress by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf("") }
    var apk by remember { mutableStateOf<File?>(null) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (AppUpdate.canInstall(ctx)) { phase = "idle"; message = "Permission granted. Tap Download and install." }
        else message = "Install permission is still off for BlindRSS."
    }
    fun install() { apk?.let { try { ctx.startActivity(AppUpdate.installIntent(ctx, it)); onFinished() } catch (e: Exception) { phase = "error"; message = "Could not open the installer: ${e.message ?: "no installer found"}" } } }
    fun start() {
        if (!AppUpdate.canInstall(ctx)) { phase = "permission"; return }
        phase = "downloading"; progress = 0; message = ""
        scope.launch {
            try { val f = AppUpdate.downloadApk(ctx, info) { p -> progress = p }; apk = f; phase = "ready"; install() }
            catch (e: Exception) { phase = "error"; message = "Download failed: ${e.message ?: "network error"}" }
        }
    }
    Column {
        Text(stringResource(R.string.update_version_available, info.version), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        when (phase) {
            "permission" -> {
                Text("Android asks you to allow BlindRSS to install apps. Open the settings screen, turn on Allow from this source, then come back.", Modifier.padding(top = 8.dp))
                Button(onClick = { permLauncher.launch(AppUpdate.unknownSourcesIntent(ctx)) }, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.update_open_permission_settings)) }
            }
            "downloading" -> Text(stringResource(R.string.update_downloading, progress), Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
            "error" -> Text(message, Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
            "idle", "ready" -> if (message.isNotEmpty()) Text(message, Modifier.padding(top = 8.dp))
        }
        Button(onClick = { if (phase == "ready") install() else start() }, enabled = phase != "downloading",
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(if (phase == "ready") stringResource(R.string.update_install) else stringResource(R.string.update_download_install)) }
    }
}

// Home surfaces a waiting update as a real dialog, not a silent toast.
@Composable
fun AppUpdateDialog(vm: AppViewModel, info: AppUpdate.ReleaseInfo) {
    AlertDialog(
        onDismissRequest = { vm.dismissAppUpdate() },
        title = { Text(stringResource(R.string.update_available_title)) },
        text = { UpdateInstallerPanel(info) { vm.dismissAppUpdate() } },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { vm.dismissAppUpdate() }) { Text(stringResource(R.string.update_later)) } },
    )
}
