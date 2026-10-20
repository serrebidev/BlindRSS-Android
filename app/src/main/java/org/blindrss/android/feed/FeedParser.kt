package org.blindrss.android.feed
import org.w3c.dom.Element
import java.io.StringReader
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

data class ParsedArticle(val guid: String, val title: String, val url: String, val content: String, val description: String, val date: Long, val author: String, val mediaUrl: String?, val mediaType: String?, val chaptersUrl: String? = null)
data class ParsedFeed(val title: String, val articles: List<ParsedArticle>)

object FeedParser {
    fun stableId(feedUrl: String, guid: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest("$feedUrl|$guid".toByteArray())
        return d.joinToString("") { "%02x".format(it) }.take(32)
    }
    fun parse(xml: String, feedUrl: String): ParsedFeed {
        val t = xml.trim()
        if (t.startsWith("{")) return parseJsonFeed(t, feedUrl)
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = f.newDocumentBuilder().parse(InputSource(StringReader(t)))
        val root = doc.documentElement
        return when (root.localName ?: root.tagName) {
            "feed" -> parseAtom(doc, feedUrl)
            "RDF", "rdf" -> parseRss(doc, feedUrl, true)
            else -> parseRss(doc, feedUrl, false)
        }
    }
    private fun text(e: Element, vararg names: String): String {
        for (n in names) { val nl = e.getElementsByTagNameNS("*", n); if (nl.length > 0) return nl.item(0).textContent.trim() }
        return ""
    }
    private fun attrLink(e: Element): String {
        val nl = e.getElementsByTagNameNS("*", "link")
        var alt = ""
        for (i in 0 until nl.length) { val el = nl.item(i) as Element; val href = el.getAttribute("href"); val rel = el.getAttribute("rel")
            if (href.isNotEmpty() && (rel.isEmpty() || rel == "alternate")) return href; if (href.isNotEmpty()) alt = href }
        if (nl.length > 0) { val c = nl.item(0).textContent.trim(); if (c.isNotEmpty()) return c }
        return alt
    }
    private fun parseDate(s: String): Long {
        if (s.isBlank()) return System.currentTimeMillis()
        val pats = listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd HH:mm:ss")
        for (p in pats) { try { return SimpleDateFormat(p, Locale.US).parse(s.trim())!!.time } catch (_: Exception) {} }
        return System.currentTimeMillis()
    }
    private fun parseRss(doc: org.w3c.dom.Document, feedUrl: String, rdf: Boolean): ParsedFeed {
        val ch = doc.getElementsByTagNameNS("*", "channel").let { if (it.length > 0) it.item(0) as Element else null }
        val title = if (ch != null) text(ch, "title") else "Feed"
        val items = doc.getElementsByTagNameNS("*", "item")
        val out = mutableListOf<ParsedArticle>()
        for (i in 0 until items.length) { val e = items.item(i) as Element
            val link = text(e, "link").ifEmpty { attrLink(e) }
            val guid = text(e, "guid", "id").ifEmpty { link.ifEmpty { text(e, "title") + i } }
            var media: String? = null; var mtype: String? = null
            val enc = e.getElementsByTagNameNS("*", "enclosure")
            if (enc.length > 0) { val el = enc.item(0) as Element; media = el.getAttribute("url").ifEmpty { null }; mtype = el.getAttribute("type").ifEmpty { null } }
            if (media == null) { val mc = e.getElementsByTagNameNS("*", "content"); if (mc.length > 0) { val el = mc.item(0) as Element; media = el.getAttribute("url").ifEmpty { null }; mtype = el.getAttribute("type").ifEmpty { null } } }
            val chNodes = e.getElementsByTagNameNS("*", "chapters")
            val chaptersUrl = if (chNodes.length > 0) (chNodes.item(0) as Element).getAttribute("url").ifEmpty { null } else null
            out += ParsedArticle(guid, text(e, "title").ifEmpty { "(untitled)" }, link, text(e, "encoded", "content").ifEmpty { text(e, "description") }, text(e, "description"), parseDate(text(e, "pubDate", "date", "published", "updated")), text(e, "creator", "author", "name"), media, mtype, chaptersUrl)
        }
        return ParsedFeed(title.ifEmpty { "Feed" }, out)
    }
    private fun parseAtom(doc: org.w3c.dom.Document, feedUrl: String): ParsedFeed {
        val title = text(doc.documentElement, "title").ifEmpty { "Feed" }
        val entries = doc.getElementsByTagNameNS("*", "entry")
        val out = mutableListOf<ParsedArticle>()
        for (i in 0 until entries.length) { val e = entries.item(i) as Element
            val link = attrLink(e)
            val guid = text(e, "id").ifEmpty { link.ifEmpty { text(e, "title") + i } }
            var media: String? = null; var mtype: String? = null
            val links = e.getElementsByTagNameNS("*", "link")
            for (j in 0 until links.length) { val el = links.item(j) as Element; if (el.getAttribute("rel") == "enclosure") { media = el.getAttribute("href"); mtype = el.getAttribute("type") } }
            out += ParsedArticle(guid, text(e, "title").ifEmpty { "(untitled)" }, link, text(e, "content").ifEmpty { text(e, "summary") }, text(e, "summary"), parseDate(text(e, "published", "updated")), text(e, "name", "author"), media, mtype)
        }
        return ParsedFeed(title, out)
    }
    private fun parseJsonFeed(json: String, feedUrl: String): ParsedFeed {
        val title = Regex("\"title\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1) ?: "Feed"
        val items = Regex("\"items\"\\s*:\\s*\\[(.*)\\]\\s*}", RegexOption.DOT_MATCHES_ALL).find(json)?.groupValues?.get(1) ?: return ParsedFeed(title, emptyList())
        val out = mutableListOf<ParsedArticle>()
        for (m in Regex("\\{([^}]*)\\}").findAll(items)) { val b = m.groupValues[1]
            fun g(k: String) = Regex("\"$k\"\\s*:\\s*\"([^\"]*)\"").find(b)?.groupValues?.get(1) ?: ""
            val url = g("url").ifEmpty { g("external_url") }
            out += ParsedArticle(g("id").ifEmpty { url }, g("title").ifEmpty { "(untitled)" }, url, g("content_html").ifEmpty { g("content_text") }, g("summary"), parseDate(g("date_published")), "", null, null)
        }
        return ParsedFeed(title, out)
    }
}

object Opml {
    data class Entry(val title: String, val xmlUrl: String, val category: String)
    fun parse(xml: String): List<Entry> {
        val f = DocumentBuilderFactory.newInstance()
        val doc = f.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val out = mutableListOf<Entry>()
        fun walk(e: Element, path: List<String>) {
            val kids = e.childNodes
            for (i in 0 until kids.length) { val n = kids.item(i); if (n is Element && n.tagName == "outline") {
                val url = n.getAttribute("xmlUrl")
                val t = n.getAttribute("title").ifEmpty { n.getAttribute("text") }
                if (url.isNotEmpty()) out += Entry(t, url, path.joinToString(" / ").ifEmpty { "Uncategorized" }) else walk(n, path + t)
            } }
        }
        val bodies = doc.getElementsByTagName("body"); if (bodies.length > 0) walk(bodies.item(0) as Element, emptyList())
        return out
    }
    fun write(feeds: List<Pair<String, Pair<String, String>>>): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"1.0\"><head><title>BlindRSS Subscriptions</title></head><body>\n")
        val byCat = feeds.groupBy { it.second.second }
        for ((cat, list) in byCat) {
            fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
            if (cat == "Uncategorized" || cat.isBlank()) list.forEach { sb.append("<outline text=\"${esc(it.first)}\" title=\"${esc(it.first)}\" type=\"rss\" xmlUrl=\"${esc(it.second.first)}\"/>\n") }
            else { sb.append("<outline text=\"${esc(cat)}\" title=\"${esc(cat)}\">\n"); list.forEach { sb.append("<outline text=\"${esc(it.first)}\" title=\"${esc(it.first)}\" type=\"rss\" xmlUrl=\"${esc(it.second.first)}\"/>\n") }; sb.append("</outline>\n") }
        }
        return sb.append("</body></opml>").toString()
    }
}

object FeedDiscovery {
    fun normalizeInput(raw: String): String {
        val t = raw.trim()
        // YouTube / Reddit / Lemmy conveniences
        Regex("youtube\\.com/channel/(UC[\\w-]+)").find(t)?.let { return "https://www.youtube.com/feeds/videos.xml?channel_id=${it.groupValues[1]}" }
        Regex("youtube\\.com/@([\\w.-]+)").find(t)?.let { return "https://www.youtube.com/feeds/videos.xml?user=${it.groupValues[1]}" }
        if (t.contains("reddit.com/r/") && !t.endsWith(".rss")) return t.trimEnd('/') + "/.rss"
        Regex("^(https?://[^/]+)/c/([\\w.-]+)").find(t)?.let { return "${it.groupValues[1]}/feeds/c/${it.groupValues[2]}.xml?sort=Active" }
        Regex("^(https?://[^/]+)/u/([\\w.-]+)").find(t)?.let { if (!t.contains("/feeds/")) return "${it.groupValues[1]}/feeds/u/${it.groupValues[2]}.xml?sort=New" }
        if (!t.startsWith("http")) return "https://$t"
        return t
    }
    fun findFeedLinks(html: String, base: String): List<String> {
        val out = mutableListOf<String>()
        for (m in Regex("<link[^>]+>", RegexOption.IGNORE_CASE).findAll(html)) {
            val tag = m.value
            if (tag.contains("alternate", true) && (tag.contains("rss", true) || tag.contains("atom", true))) {
                Regex("href\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.let { href ->
                    out += if (href.startsWith("http")) href else java.net.URI(base).resolve(href).toString()
                }
            }
        }
        return out.distinct()
    }
}

// Podcasting 2.0 chapters JSON: {"chapters":[{"startTime":0,"title":"Intro"}, ...]}
object PodcastChapters {
    fun parse(json: String): List<Pair<Long, String>> {
        val starts = Regex("\"startTime\"\\s*:\\s*([0-9.]+)").findAll(json).map { (it.groupValues[1].toDouble() * 1000).toLong() }.toList()
        val titles = Regex("\"title\"\\s*:\\s*\"([^\"]*)\"").findAll(json).map { it.groupValues[1] }.toList()
        if (starts.isEmpty()) return emptyList()
        return starts.mapIndexed { i, ms -> ms to (titles.getOrNull(i) ?: "Chapter ${i + 1}") }.sortedBy { it.first }
    }
}

object FullText {
    fun extract(html: String): String {
        var h = html
        h = Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE).replace(h, "")
        h = Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE).replace(h, "")
        h = Regex("<nav[\\s\\S]*?</nav>", RegexOption.IGNORE_CASE).replace(h, "")
        h = Regex("<header[\\s\\S]*?</header>", RegexOption.IGNORE_CASE).replace(h, "")
        h = Regex("<footer[\\s\\S]*?</footer>", RegexOption.IGNORE_CASE).replace(h, "")
        val article = Regex("<article[\\s\\S]*?</article>", RegexOption.IGNORE_CASE).find(h)?.value
        val main = Regex("<main[\\s\\S]*?</main>", RegexOption.IGNORE_CASE).find(h)?.value
        val body = article ?: main ?: h
        val paras = Regex("<p[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE).findAll(body).map { strip(it.groupValues[1]) }.filter { it.length > 40 }.toList()
        val heads = Regex("<h[12][^>]*>([\\s\\S]*?)</h[12]>", RegexOption.IGNORE_CASE).findAll(body).map { strip(it.groupValues[1]) }.filter { it.isNotBlank() }.toList()
        val sb = StringBuilder()
        heads.forEach { sb.append("<h2>").append(it).append("</h2>") }
        paras.forEach { sb.append("<p>").append(it).append("</p>") }
        return if (sb.isEmpty()) strip(body).let { "<p>$it</p>" } else sb.toString()
    }
    fun strip(s: String) = s.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()
    fun toPlain(html: String) = strip(html)
}
