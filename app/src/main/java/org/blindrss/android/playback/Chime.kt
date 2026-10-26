package org.blindrss.android.playback
import android.content.Context
import android.media.RingtoneManager

// The desktop app chimes when a manual refresh completes (v2.4.5/v2.4.6).
// Android equivalent: one short system notification sound, only for refreshes
// the user started, controlled by Settings > Sync and notifications.
object Chime {
    fun play(ctx: Context) {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
            RingtoneManager.getRingtone(ctx.applicationContext, uri)?.play()
        } catch (_: Exception) {}
    }
}
