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
        setContent {
            MaterialTheme {
                Surface {
                    val vm: AppViewModel = viewModel()
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = "home") {
                        composable("home") { HomeScreen(nav, vm) }
                        composable("articles/{mode}", listOf(navArgument("mode") { type = NavType.StringType })) { ArticlesScreen(nav, vm, it.arguments?.getString("mode") ?: "all") }
                        composable("articles/feed/{id}", listOf(navArgument("id") { type = NavType.LongType })) { FeedArticlesScreen(nav, vm, it.arguments?.getLong("id") ?: 0) }
                        composable("reader/{id}", listOf(navArgument("id") { type = NavType.StringType })) { ReaderScreen(nav, vm, it.arguments?.getString("id") ?: "") }
                        composable("search") { SearchScreen(nav, vm) }
                        composable("deleted") { DeletedScreen(nav, vm) }
                        composable("errors") { ErrorsScreen(nav, vm) }
                        composable("smart") { SmartFoldersScreen(nav, vm) }
                        composable("filters") { FiltersScreen(nav, vm) }
                        composable("addfeed") { AddFeedScreen(nav, vm) }
                        composable("player") { PlayerScreen(nav, vm) }
                        composable("settings") { SettingsScreen(nav, vm) }
                        composable("categories") { CategoriesScreen(nav, vm) }
                        composable("categories/{name}", listOf(navArgument("name") { type = NavType.StringType })) { CategoryArticlesScreen(nav, vm, it.arguments?.getString("name") ?: "") }
                    }
                }
            }
        }
    }
}
