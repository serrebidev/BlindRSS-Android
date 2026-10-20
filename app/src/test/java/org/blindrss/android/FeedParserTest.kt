package org.blindrss.android
import org.blindrss.android.feed.FeedDiscovery
import org.blindrss.android.feed.FeedParser
import org.blindrss.android.feed.FullText
import org.blindrss.android.feed.Opml
import org.blindrss.android.feed.PodcastChapters
import org.junit.Assert.*
import org.junit.Test

class FeedParserTest {
    @Test fun rss() { val f = FeedParser.parse("""<rss version="2.0"><channel><title>Example</title><item><title>Hello</title><link>https://e.com/1</link><guid>g1</guid><description>Desc</description><enclosure url="https://e.com/a.mp3" type="audio/mpeg"/></item></channel></rss>""", "https://e.com/feed")
        assertEquals("Example", f.title); assertEquals(1, f.articles.size); assertEquals("Hello", f.articles[0].title); assertEquals("https://e.com/a.mp3", f.articles[0].mediaUrl) }
    @Test fun atom() { val f = FeedParser.parse("""<feed xmlns="http://www.w3.org/2005/Atom"><title>AtomFeed</title><entry><title>Post</title><id>urn:1</id><link href="https://e.com/p"/><summary>Sum</summary></entry></feed>""", "https://e.com/atom")
        assertEquals("AtomFeed", f.title); assertEquals("Post", f.articles[0].title); assertEquals("https://e.com/p", f.articles[0].url) }
    @Test fun rdf() { val f = FeedParser.parse("""<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/"><channel><title>RDF</title></channel><item><title>R1</title><link>https://e.com/r</link></item></rdf:RDF>""", "https://e.com/rdf")
        assertEquals(1, f.articles.size) }
    @Test fun opmlRoundTrip() { val xml = Opml.write(listOf("Feed A" to ("https://e.com/a" to "Tech"), "Feed B" to ("https://e.com/b" to "Uncategorized")))
        val entries = Opml.parse(xml); assertEquals(2, entries.size); assertTrue(entries.any { it.title == "Feed A" && it.category == "Tech" }) }
    @Test fun opmlNested() { val entries = Opml.parse("""<opml><body><outline text="News"><outline text="F1" xmlUrl="https://e.com/1"/></outline></body></opml>"""); assertEquals("News", entries[0].category) }
    @Test fun discovery() { assertEquals("https://www.youtube.com/feeds/videos.xml?channel_id=UCabc", FeedDiscovery.normalizeInput("https://youtube.com/channel/UCabc")); assertTrue(FeedDiscovery.normalizeInput("https://reddit.com/r/kotlin").endsWith("/.rss"))
        val links = FeedDiscovery.findFeedLinks("""<link rel="alternate" type="application/rss+xml" href="/feed.xml"/>""", "https://e.com/blog"); assertEquals(listOf("https://e.com/feed.xml"), links) }
    @Test fun fullText() { val out = FullText.extract("<html><body><nav>x</nav><article><h1>T</h1><p>${"word ".repeat(20)}</p></article></body></html>"); assertTrue(out.contains("word")) }
    @Test fun fullTextWordPressNews() {
        // Mirrors mapleridgenews.com: no <article>/<main>, nav text in giant
        // link-heavy paragraphs, real story in a plain div further down.
        val navLinks = (1..30).joinToString(" ") { "<a href=\"/n$it\">Section $it news</a>" }
        val story = listOf(
            "Police officers arrived on scene and observed the bird, later determined to be an adult male red-tailed hawk, suffering an apparent broken wing near the road.",
            "The Mounties determined to save the raptor, relying on their limited birds of prey expertise, officers carefully captured the hawk and kept it calm.",
            "A pet ambulance volunteer was notified, and soon arrived on scene to transport the hawk to the wildlife rehabilitation centre for treatment and care.").joinToString("") { "<p>$it</p>" }
        val html = "<html><body><div class=\"site-wrap\"><div class=\"nav-menu\"><p>$navLinks Navigation menu with every section listed for browsing the whole site today.</p></div>" +
            "<div class=\"post-wrap\"><h1>Hawk rescued</h1>$story</div><div class=\"footer-links\"><p><a href=\"/about\">About us</a> <a href=\"/contact\">Contact the newsroom</a></p></div></div></body></html>"
        val out = FullText.toPlain(FullText.extract(html))
        assertTrue(out.contains("adult male red-tailed hawk"))
        assertTrue(out.contains("wildlife rehabilitation centre"))
        assertFalse(out.contains("Navigation menu with every section"))
    }
    @Test fun podcastChapters() { val f = FeedParser.parse("""<rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0"><channel><title>P</title><item><title>Ep1</title><link>https://e.com/ep1</link><guid>ep1</guid><enclosure url="https://e.com/ep1.mp3" type="audio/mpeg"/><podcast:chapters url="https://e.com/ep1-chapters.json" type="application/json+chapters"/></item></channel></rss>""", "https://e.com/feed")
        assertEquals("https://e.com/ep1-chapters.json", f.articles[0].chaptersUrl)
        val ch = PodcastChapters.parse("""{"version":"1.2.0","chapters":[{"startTime":0,"title":"Intro"},{"startTime":95.5,"title":"Main"}]}""")
        assertEquals(listOf(0L to "Intro", 95500L to "Main"), ch) }
    @Test fun stableIdDeterministic() { assertEquals(FeedParser.stableId("https://e.com/f", "g1"), FeedParser.stableId("https://e.com/f", "g1")) }
}
