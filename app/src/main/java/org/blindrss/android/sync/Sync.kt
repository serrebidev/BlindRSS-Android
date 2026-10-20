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
            val r = repo.refreshAll()
            if (r.toNotify > 0 && repo.settings.notifyNew.first()) notifyNew(applicationContext, r.toNotify)
            // Retry only when nothing worked at all (likely offline); one broken feed must not cause endless retries.
            if (r.nothingWorked) Result.retry() else Result.success()
        } catch (_: Exception) { Result.retry() }
    }
    companion object {
        // Reads interval (0 = off) and Wi-Fi-only from settings.
        suspend fun schedule(ctx: Context) {
            val s = org.blindrss.android.settings.SettingsStore(ctx); val intervalMin = s.syncInterval.first()
            if (intervalMin <= 0) { WorkManager.getInstance(ctx).cancelUniqueWork("blindrss-sync"); return }
            val net = if (s.wifiOnly.first()) NetworkType.UNMETERED else NetworkType.CONNECTED
            val req = PeriodicWorkRequestBuilder<SyncWorker>(intervalMin.coerceAtLeast(15).toLong(), TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(net).build()).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("blindrss-sync", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
fun notifyNew(ctx: Context, count: Int) {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("new_articles", "New articles", NotificationManager.IMPORTANCE_DEFAULT))
    val n = NotificationCompat.Builder(ctx, "new_articles").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("BlindRSS").setContentText(if (count == 1) "1 new article" else "$count new articles").setAutoCancel(true)
        .setContentIntent(android.app.PendingIntent.getActivity(ctx, 0, android.content.Intent(ctx, org.blindrss.android.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP), android.app.PendingIntent.FLAG_IMMUTABLE)).build()
    try { nm.notify(1001, n) } catch (_: SecurityException) {}
}
