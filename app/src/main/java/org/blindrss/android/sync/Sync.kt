package org.blindrss.android.sync
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.flow.first
import org.blindrss.android.data.Repository
import java.util.concurrent.TimeUnit

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val repo = Repository(applicationContext)
        return try {
            var total = 0; val errors = mutableListOf<String>()
            for (f in repo.feeds.all()) { try { total += repo.refreshFeed(f) } catch (e: Exception) { errors += f.title } }
            try { repo.syncProvider() } catch (_: Exception) {}
            if (total > 0 && repo.settings.notifyNew.first()) notifyNew(applicationContext, total)
            if (errors.isNotEmpty() && total == 0) Result.retry() else Result.success()
        } catch (_: Exception) { Result.retry() }
    }
    companion object {
        fun schedule(ctx: Context, intervalMin: Int) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(intervalMin.coerceAtLeast(15).toLong(), TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("blindrss-sync", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
        fun runOnce(ctx: Context) { WorkManager.getInstance(ctx).enqueueUniqueWork("blindrss-sync-once", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<SyncWorker>().build()) }
    }
}
fun notifyNew(ctx: Context, count: Int) {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("new_articles", "New articles", NotificationManager.IMPORTANCE_DEFAULT))
    val n = NotificationCompat.Builder(ctx, "new_articles").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("BlindRSS").setContentText("$count new articles").setAutoCancel(true).build()
    try { nm.notify(1001, n) } catch (_: SecurityException) {}
}
fun announceError(ctx: Context, msg: String) {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("new_articles", "New articles", NotificationManager.IMPORTANCE_DEFAULT))
    val n = NotificationCompat.Builder(ctx, "new_articles").setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("BlindRSS sync error").setContentText(msg).setAutoCancel(true).build()
    try { nm.notify(1002, n) } catch (_: SecurityException) {}
}
