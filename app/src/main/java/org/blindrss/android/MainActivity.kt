package org.blindrss.android
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import org.blindrss.android.ui.*

class MainActivity : ComponentActivity() {
    private var vmRef: AppViewModel? = null
    private fun handleOAuth(intent: android.content.Intent?) {
        val code = intent?.data?.takeIf { it.scheme == "blindrss" && it.host == "oauth" }?.getQueryParameter("code") ?: return
        vmRef?.exchangeOAuth(code)
    }
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); handleOAuth(intent) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appVm = androidx.lifecycle.ViewModelProvider(this)[AppViewModel::class.java]
        vmRef = appVm
        appVm.runStartupImports(this)
        handleOAuth(intent)
        if (savedInstanceState == null) {
            appVm.refreshOnLaunch()
            appVm.checkAppUpdate()
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            MaterialTheme {
                Surface {
                    val vm: AppViewModel = viewModel()
                    val nav = rememberNavController()
                    androidx.compose.foundation.layout.Column {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) {
                    NavHost(nav, startDestination = "home") {
                        composable("home") { HomeScreen(nav, vm) }
                        composable("articles/{mode}", listOf(navArgument("mode") { type = NavType.StringType })) { ArticlesScreen(nav, vm, it.arguments?.getString("mode") ?: "all") }
                        composable("articles/feed/{id}", listOf(navArgument("id") { type = NavType.LongType })) { FeedArticlesScreen(nav, vm, it.arguments?.getLong("id") ?: 0) }
                        composable("reader/{id}", listOf(navArgument("id") { type = NavType.StringType })) { ReaderScreen(nav, vm, it.arguments?.getString("id") ?: "") }
                        composable("feed/edit/{id}", listOf(navArgument("id") { type = NavType.LongType })) { EditFeedScreen(nav, vm, it.arguments?.getLong("id") ?: 0) }
                        composable("search") { SearchScreen(nav, vm) }
                        composable("videosearch") { VideoSearchScreen(nav, vm) }
                        composable("deleted") { DeletedScreen(nav, vm) }
                        composable("errors") { ErrorsScreen(nav, vm) }
                        composable("smart") { SmartFoldersScreen(nav, vm) }
                        composable("filters") { FiltersScreen(nav, vm) }
                        composable("addfeed") { AddFeedScreen(nav, vm) }
                        composable("player") { PlayerScreen(nav, vm) }
                        composable("settings") { SettingsScreen(nav, vm) }
                        composable("help") { HelpScreen(nav, vm) }
                        composable("settings/provider/{type}") { ProviderSetupScreen(nav, vm, org.blindrss.android.settings.ProviderType.valueOf(it.arguments?.getString("type") ?: "MINIFLUX")) }
                        composable("settings/{section}") { SettingsSectionScreen(nav, vm, it.arguments?.getString("section") ?: "") }
                        composable("categories") { CategoriesScreen(nav, vm) }
                        composable("discover") { DiscoverScreen(nav, vm) }
                        composable("categories/{name}", listOf(navArgument("name") { type = NavType.StringType })) { CategoryArticlesScreen(nav, vm, it.arguments?.getString("name") ?: "") }
                    }
                    }
                    MiniPlayer(nav, vm)
                    }
                }
            }
        }
    }
}
