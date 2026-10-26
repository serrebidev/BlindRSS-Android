package org.blindrss.android.feed
import org.json.JSONObject

// The desktop's Video Search (Tools menu): yt-dlp site searches plus the
// Mixcloud API, normalised to one result shape. `searchKey` is the yt-dlp
// search prefix ("ytsearch" etc.); an empty key means an API provider
// (Mixcloud), handled by VideoSearchProvider in AppViewModel. Only the
// non-adult desktop sites are ported. Parsing is pure and unit-tested.

data class VideoSite(val id: String, val label: String, val searchKey: String)

data class VideoResult(
    val title: String,
    val url: String,
    val site: String,
    val uploader: String = "",
    val durationSec: Long = 0,
    val viewCount: Long = 0,
    val uploadDateMs: Long = 0,
    val channelUrl: String = "",
)

object VideoSearch {
    val SITES = listOf(
        VideoSite("youtube", "YouTube", "ytsearch"),
        VideoSite("youtube-date", "YouTube, newest first", "ytsearchdate"),
        VideoSite("soundcloud", "SoundCloud", "scsearch"),
        VideoSite("mixcloud", "Mixcloud", ""),
        VideoSite("bilibili", "Bilibili", "bilisearch"),
        VideoSite("niconico", "Niconico", "nicosearch"),
        VideoSite("rokfin", "Rokfin", "rkfnsearch"),
        VideoSite("prx-series", "PRX Series", "prxseries"),
        VideoSite("prx-stories", "PRX Stories", "prxstories"),
        VideoSite("netverse", "Netverse", "netsearch"),
    )
    fun site(id: String): VideoSite? = SITES.firstOrNull { it.id == id }
    // The yt-dlp query target: "<key><limit>:<query>".
    fun queryTarget(site: VideoSite, query: String, limit: Int): String = "${site.searchKey}$limit:$query"

    // Normalise one yt-dlp flat-playlist JSON document (from a search key or
    // a listing page) into results; mirrors the desktop's
    // _normalize_ytdlp_search_entries.
    fun parseFlatEntries(json: String, siteLabel: String): List<VideoResult> {
        val out = mutableListOf<VideoResult>()
        val root = try { JSONObject(json) } catch (_: Exception) { return out }
        val entries = root.optJSONArray("entries") ?: return out
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            if (e.optString("_type") == "url" && e.optString("ie_key").equals("Generic", true)) continue
            var url = e.optString("webpage_url").ifEmpty { e.optString("url") }
            if (url.isEmpty() || !url.startsWith("http")) {
                val id = e.optString("id"); val key = e.optString("ie_key").ifEmpty { e.optString("extractor_key") }
                url = when {
                    id.isEmpty() -> continue
                    key.contains("youtube", true) -> "https://www.youtube.com/watch?v=$id"
                    else -> continue
                }
            }
            val title = e.optString("title").ifEmpty { url }
            val uploader = e.optString("uploader").ifEmpty { e.optString("channel") }.ifEmpty { e.optString("creator") }
            val channelUrl = e.optString("channel_url").ifEmpty { e.optString("uploader_url") }
            val date = e.optString("upload_date")
            val dateMs = if (date.length == 8) try {
                java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).parse(date)!!.time
            } catch (_: Exception) { 0 } else 0
            out += VideoResult(title, url, siteLabel, uploader, e.optLong("duration"), e.optLong("view_count"), dateMs, channelUrl)
        }
        return out
    }

    // Flat entries -> listing-feed items (yt-dlp listing feeds: YouTube
    // channel/playlist/search pages, and the generic fallback for any
    // listing page the feed reader could not parse as a feed).
    fun entriesToListingItems(json: String): List<ListingItem> =
        parseFlatEntries(json, "").map { ListingItem(it.url, it.title, it.uploader, it.uploadDateMs) }

    // The playlist/channel title of a flat document, "" when absent.
    fun flatTitle(json: String): String = try { JSONObject(json).optString("title") } catch (_: Exception) { "" }
}
