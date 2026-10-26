package org.blindrss.android.feed
import org.json.JSONArray
import org.json.JSONObject

// Forum threads read as one whole article, mirroring desktop forum_sources.py:
// the original post followed by the discussion, in thread order. Reddit via
// its public .json, Lemmy via its v3 API. Groups.io and Google Groups need
// account sessions and are not covered. Parsing is pure; Repository fetches.
object ForumText {
    enum class Kind { REDDIT, LEMMY }
    data class Target(val kind: Kind, val jsonUrl: String, val commentsUrl: String? = null)

    private const val MAX_COMMENTS = 100

    fun targetFor(articleUrl: String, feedUrl: String): Target? {
        val u = try { java.net.URI(articleUrl.trim()) } catch (_: Exception) { return null }
        val host = (u.host ?: "").lowercase()
        val path = u.path ?: ""
        if (host == "reddit.com" || host.endsWith(".reddit.com")) {
            val m = Regex("^/r/([\\w]+)/comments/([\\w]+)").find(path) ?: return null
            return Target(Kind.REDDIT, "https://www.reddit.com/r/${m.groupValues[1]}/comments/${m.groupValues[2]}.json?raw_json=1")
        }
        // Lemmy post pages are /post/<id> on the instance that served the feed;
        // requiring the feed on the same host keeps blog /post/123 URLs out.
        val post = Regex("^/post/(\\d+)/?$").find(path)
        if (post != null) {
            val feedHost = try { java.net.URI(feedUrl).host?.lowercase() } catch (_: Exception) { null }
            if (feedHost != null && feedHost == host) {
                val id = post.groupValues[1]
                val base = "https://$host"
                return Target(Kind.LEMMY, "$base/api/v3/post?id=$id", "$base/api/v3/comment/list?post_id=$id&max_depth=8&limit=100&sort=Hot")
            }
        }
        return null
    }

    fun buildHtml(payloads: List<String>, target: Target): String = when (target.kind) {
        Kind.REDDIT -> redditHtml(payloads.firstOrNull() ?: "")
        Kind.LEMMY -> lemmyHtml(payloads.getOrNull(0) ?: "", payloads.getOrNull(1) ?: "")
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun paras(text: String): String = text.split(Regex("\\n\\s*\\n|\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString("") { "<p>${esc(it)}</p>" }

    // ---- Reddit ----
    data class Comment(val author: String, val body: String, val depth: Int)
    fun redditComments(listing: JSONObject, out: MutableList<Comment>) {
        val children = listing.optJSONObject("data")?.optJSONArray("children") ?: return
        for (i in 0 until children.length()) {
            if (out.size >= MAX_COMMENTS) return
            val child = children.optJSONObject(i) ?: continue
            if (child.optString("kind") != "t1") continue
            val d = child.optJSONObject("data") ?: continue
            val body = d.optString("body").trim()
            if (body.isNotEmpty() && body != "[deleted]" && body != "[removed]")
                out += Comment(d.optString("author").ifBlank { "Unknown" }, body, d.optInt("depth", 0))
            d.optJSONObject("replies")?.let { redditComments(it, out) }
        }
    }
    fun redditHtml(json: String): String {
        val arr = try { JSONArray(json) } catch (_: Exception) { return "" }
        if (arr.length() < 1) return ""
        val post = arr.optJSONObject(0)?.optJSONObject("data")?.optJSONArray("children")?.optJSONObject(0)?.optJSONObject("data") ?: return ""
        val sb = StringBuilder()
        val selftext = post.optString("selftext").trim()
        if (selftext.isNotEmpty()) sb.append("<h2>Post</h2>").append(paras(selftext))
        val linkUrl = post.optString("url").trim()
        if (selftext.isEmpty() && linkUrl.isNotEmpty() && !linkUrl.contains("reddit.com")) sb.append("<p>Link post: ").append(esc(linkUrl)).append("</p>")
        val comments = mutableListOf<Comment>()
        if (arr.length() > 1) arr.optJSONObject(1)?.let { redditComments(it, comments) }
        if (comments.isNotEmpty()) {
            sb.append("<h2>Comments</h2>")
            for (c in comments) {
                val who = if (c.depth > 0) "Reply by ${c.author}" else c.author
                sb.append("<p>").append(esc(who)).append(": ").append(esc(c.body.replace(Regex("\\s+"), " "))).append("</p>")
            }
        }
        return sb.toString()
    }

    // ---- Lemmy ----
    // Comment paths ("0.12.40") sort into thread order segment by segment.
    fun lemmyOrder(comments: List<Pair<String, Comment>>): List<Comment> =
        comments.sortedWith { x, y ->
            val a = x.first.split(".").map { it.toIntOrNull() ?: 0 }; val b = y.first.split(".").map { it.toIntOrNull() ?: 0 }
            var r = 0; for (i in 0 until minOf(a.size, b.size)) { if (a[i] != b[i]) { r = a[i] - b[i]; break } }
            if (r != 0) r else a.size - b.size
        }.map { it.second }
    fun lemmyHtml(postJson: String, commentsJson: String): String {
        val post = try { JSONObject(postJson).optJSONObject("post_view")?.optJSONObject("post") } catch (_: Exception) { null } ?: return ""
        val sb = StringBuilder()
        val body = post.optString("body").trim()
        if (body.isNotEmpty()) sb.append("<h2>Post</h2>").append(paras(body))
        val comments = mutableListOf<Pair<String, Comment>>()
        try {
            val arr = JSONObject(commentsJson).optJSONArray("comments")
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val c = o.optJSONObject("comment") ?: continue
                val text = c.optString("content").trim()
                if (text.isEmpty() || c.optBoolean("deleted") || c.optBoolean("removed")) continue
                val path = c.optString("path", "0")
                val author = o.optJSONObject("creator")?.optString("name")?.ifBlank { "Unknown" } ?: "Unknown"
                comments += path to Comment(author, text, path.split(".").size - 2)
            }
        } catch (_: Exception) {}
        val ordered = lemmyOrder(comments).take(MAX_COMMENTS)
        if (ordered.isNotEmpty()) {
            sb.append("<h2>Comments</h2>")
            for (c in ordered) {
                val who = if (c.depth > 0) "Reply by ${c.author}" else c.author
                sb.append("<p>").append(esc(who)).append(": ").append(esc(c.body.replace(Regex("\\s+"), " "))).append("</p>")
            }
        }
        return sb.toString()
    }
}
