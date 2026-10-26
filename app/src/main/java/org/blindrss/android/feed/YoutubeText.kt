package org.blindrss.android.feed
import org.json.JSONArray
import org.json.JSONObject

// YouTube articles as full text, mirroring desktop core/youtube_fulltext.py:
// description, chapters, transcript and top comments, all from yt-dlp's info
// JSON (never by scraping the watch page). Parsing here is pure; fetching
// lives in Ytdl + Repository.
object YoutubeText {
    private val HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "www.youtu.be")

    fun videoId(url: String): String {
        val u = try { java.net.URI(url.trim()) } catch (_: Exception) { return "" }
        val host = (u.host ?: "").lowercase()
        if (host !in HOSTS) return ""
        val path = u.path ?: ""
        val candidate = when {
            host.endsWith("youtu.be") -> path.trim('/').split("/").firstOrNull() ?: ""
            path == "/watch" -> (u.query ?: "").split("&").firstOrNull { it.startsWith("v=") }?.removePrefix("v=") ?: ""
            else -> Regex("^/(?:shorts|embed|live)/([^/?#]+)").find(path)?.groupValues?.get(1) ?: ""
        }
        return candidate.takeIf { Regex("[A-Za-z0-9_-]{6,20}").matches(it) } ?: ""
    }
    fun isVideoUrl(url: String) = videoId(url).isNotEmpty()

    // Manual subtitles beat auto captions; English beats other languages
    // (same ranking as desktop _subtitle_candidates). Returns a json3 URL.
    fun subtitleUrl(infoJson: String): String? {
        val info = try { JSONObject(infoJson) } catch (_: Exception) { return null }
        for (key in listOf("subtitles", "automatic_captions")) {
            val tracks = info.optJSONObject(key) ?: continue
            val langs = tracks.keys().asSequence().toList()
            if (langs.isEmpty()) continue
            val ranked = langs.sortedWith(compareBy({ langRank(it) }, { it }))
            for (lang in ranked) {
                val formats = tracks.optJSONArray(lang) ?: continue
                for (i in 0 until formats.length()) {
                    val f = formats.optJSONObject(i) ?: continue
                    if (f.optString("ext") == "json3") return f.optString("url").ifEmpty { null }
                }
            }
        }
        return null
    }
    private fun langRank(lang: String): Int = when {
        lang == "en" || lang == "en-orig" -> 0
        lang.startsWith("en") -> 1
        else -> 2
    }

    // json3 caption events -> (startMs, text) lines. Auto captions repeat the
    // previous line as they roll; consecutive duplicates are dropped.
    fun transcriptLines(json3: String): List<Pair<Long, String>> {
        val root = try { JSONObject(json3) } catch (_: Exception) { return emptyList() }
        val events = root.optJSONArray("events") ?: return emptyList()
        val out = mutableListOf<Pair<Long, String>>()
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            val segs = ev.optJSONArray("segs") ?: continue
            val text = buildString { for (j in 0 until segs.length()) append(segs.optJSONObject(j)?.optString("utf8") ?: "") }
                .replace("\n", " ").replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) continue
            val start = ev.optLong("tStartMs", 0)
            if (out.lastOrNull()?.second == text) continue
            out += start to text
        }
        return out
    }

    fun timestamp(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun paragraphs(text: String): String = text.split(Regex("\\n\\s*\\n|\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString("") { "<p>${esc(it)}</p>" }

    // The reader document: description, chapters, transcript, top comments.
    fun buildHtml(infoJson: String, transcript: List<Pair<Long, String>>): String {
        val info = try { JSONObject(infoJson) } catch (_: Exception) { return "" }
        val sb = StringBuilder()
        val description = info.optString("description").trim()
        if (description.isNotEmpty()) sb.append("<h2>Description</h2>").append(paragraphs(description))
        val chapters = info.optJSONArray("chapters")
        if (chapters != null && chapters.length() > 0) {
            sb.append("<h2>Chapters</h2>")
            for (i in 0 until chapters.length()) {
                val c = chapters.optJSONObject(i) ?: continue
                val title = c.optString("title").trim(); if (title.isEmpty()) continue
                sb.append("<p>").append(timestamp((c.optDouble("start_time", 0.0) * 1000).toLong())).append(" ").append(esc(title)).append("</p>")
            }
        }
        if (transcript.isNotEmpty()) {
            sb.append("<h2>Transcript</h2>")
            // Group lines into readable paragraphs (~500 chars), each starting with its timestamp.
            var para = StringBuilder(); var paraStart = transcript.first().first
            fun flush() { if (para.isNotEmpty()) { sb.append("<p>").append(timestamp(paraStart)).append(" ").append(esc(para.toString().trim())).append("</p>"); para = StringBuilder() } }
            for ((start, line) in transcript) {
                if (para.isNotEmpty() && para.length + line.length > 500) { flush(); paraStart = start }
                if (para.isEmpty()) paraStart = start
                para.append(line).append(" ")
            }
            flush()
        }
        val comments = topComments(info.optJSONArray("comments"))
        if (comments.isNotEmpty()) {
            sb.append("<h2>Top comments</h2>")
            for ((author, text) in comments) sb.append("<p>").append(esc(author)).append(": ").append(esc(text)).append("</p>")
        }
        return sb.toString()
    }
    // Highest-liked first, pinned first if present; plain text only.
    fun topComments(arr: JSONArray?, limit: Int = 25): List<Pair<String, String>> {
        if (arr == null) return emptyList()
        data class C(val author: String, val text: String, val likes: Int, val pinned: Boolean)
        val all = mutableListOf<C>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val text = o.optString("text").replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) continue
            all += C(o.optString("author").ifBlank { "Unknown" }, text.take(1000), o.optInt("like_count", 0), o.optBoolean("is_pinned", false))
        }
        return all.sortedWith(compareByDescending<C> { it.pinned }.thenByDescending { it.likes }).take(limit).map { it.author to it.text }
    }
}
