package org.blindrss.android.cast
import android.content.Context
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.MediaStatus

// Google Cast on the Default Media Receiver: Google's own docs and Cast
// SDK samples initialize with CastMediaControlIntent.
// DEFAULT_MEDIA_RECEIVER_APPLICATION_ID (CC1AD845), which "doesn't
// require registration" — no developer account, no application id of
// Brandon's. (The 0.12-era note "Chromecast needs the owner's Cast
// credentials" was wrong for media casting; a custom receiver would.)
// DLNA/UPnP in Dlna.kt remains for non-Google renderers.
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(com.google.android.gms.cast.CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(CastMediaOptions.Builder().build())
            .build()
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

object GoogleCast {
    fun context(ctx: Context): CastContext? = try { CastContext.getSharedInstance(ctx) } catch (_: Exception) { null }
    // Hand the currently playing media to a fresh Cast session.
    fun load(session: CastSession, url: String, title: String, contentType: String): Boolean {
        val client = session.remoteMediaClient ?: return false
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply { putString(MediaMetadata.KEY_TITLE, title) }
        val info = MediaInfo.Builder(url).setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(contentType).setMetadata(metadata).build()
        client.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
        return true
    }
    fun contentTypeFor(url: String, mediaType: String?): String = when {
        mediaType != null && "/" in mediaType -> mediaType
        url.contains(".mp4", true) -> "video/mp4"
        url.contains(".m4a", true) -> "audio/mp4"
        url.contains(".opus", true) -> "audio/ogg"
        url.contains(".ogg", true) -> "audio/ogg"
        url.contains(".wav", true) -> "audio/wav"
        else -> "audio/mpeg"
    }
    val STATE_IDLE: Int = MediaStatus.IDLE_REASON_FINISHED
}
