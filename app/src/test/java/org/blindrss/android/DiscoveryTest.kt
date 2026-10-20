package org.blindrss.android
import org.blindrss.android.discovery.DiscoveredSource
import org.blindrss.android.discovery.DiscoveryAggregator
import org.blindrss.android.discovery.FeedlyDiscovery
import org.blindrss.android.discovery.GoogleNewsUrls
import org.blindrss.android.discovery.UrlSafety
import org.junit.Assert.*
import org.junit.Test

class DiscoveryTest {
    @Test fun googleNewsUrls() {
        assertEquals("https://news.google.com/rss?hl=en-CA&gl=CA&ceid=CA:en", GoogleNewsUrls.headlines("CA", "en"))
        assertTrue(GoogleNewsUrls.topic("TECHNOLOGY", "CA", "en").contains("/headlines/section/topic/TECHNOLOGY?hl=en-CA&gl=CA&ceid=CA:en"))
        assertTrue(GoogleNewsUrls.search("Maple Ridge BC", "CA", "en").contains("q=Maple+Ridge+BC"))
        val local = GoogleNewsUrls.sourcesFor("Local news", "CA", "en", "Maple Ridge", "BC")
        assertTrue(local.any { it.isGoogleNews && it.feedUrl!!.contains("Maple+Ridge") })
    }
    @Test fun feedlyParse() {
        val out = FeedlyDiscovery.parseResults("""{"results":[{"feedId":"feed/https://www.cbc.ca/webfeed/rss/rss-topstories","title":"CBC Top Stories","website":"https://www.cbc.ca","description":"News","language":"en","subscribers":123}]}""")
        assertEquals(1, out.size); assertEquals("https://www.cbc.ca/webfeed/rss/rss-topstories", out[0].feedUrl); assertEquals("CBC Top Stories", out[0].name)
    }
    @Test fun dedupKeepsDistinctFeedsSamePublisher() {
        val a = DiscoveredSource("CBC Top", "https://www.cbc.ca", "https://www.cbc.ca/webfeed/rss/rss-topstories", provider = "Feedly")
        val b = DiscoveredSource("CBC World", "https://www.cbc.ca", "https://www.cbc.ca/webfeed/rss/rss-world", provider = "Feedly")
        val a2 = DiscoveredSource("CBC Top dup", "https://www.cbc.ca", "http://www.cbc.ca/webfeed/rss/rss-topstories/", provider = "Website")
        val out = DiscoveryAggregator.dedup(listOf(a, b, a2))
        assertEquals(2, out.size)
    }
    @Test fun subscribedDetectionCanonical() {
        val s = DiscoveredSource("X", "https://e.com", "https://e.com/feed/", provider = "Website")
        val out = DiscoveryAggregator.markSubscribed(listOf(s), listOf("http://e.com/feed"))
        assertTrue(out[0].subscribed)
    }
    @Test fun urlSafetyBlocksPrivate() {
        assertFalse(UrlSafety.isSafePublicUrl("https://localhost/feed"))
        assertFalse(UrlSafety.isSafePublicUrl("https://127.0.0.1/feed"))
        assertFalse(UrlSafety.isSafePublicUrl("file:///etc/passwd"))
        assertFalse(UrlSafety.isSafePublicUrl("https://192.168.1.10/feed"))
    }
    @Test fun canonicalStripsTracking() {
        assertEquals("https://e.com/feed?a=1", UrlSafety.canonicalFeedUrl("http://e.com/feed/?utm_source=x&a=1"))
    }
    @Test fun malformedOpmlFallback() {
        val bad = "<opml><body><outline text=\"Canada\" title=\"Canada\"><outline text=\"A & B News\" title=\"A & B News\" xmlUrl=\"https://e.com/feed?a=1&b=2\"/></outline></body></opml>"
        val out = org.blindrss.android.discovery.AwesomeCatalogue.parseAny(bad)
        assertEquals(1, out.size); assertEquals("https://e.com/feed?a=1&b=2", out[0].xmlUrl)
    }
    @Test fun catalogueFilterByCountry() {
        val cat = listOf(
            DiscoveredSource("CBC", "https://www.cbc.ca", "https://www.cbc.ca/rss", category = "General news", country = "Canada", provider = "Publisher catalogue"),
            DiscoveredSource("BBC", "https://www.bbc.com", "https://www.bbc.com/rss", category = "General news", country = "United Kingdom", provider = "Publisher catalogue"))
        val out = org.blindrss.android.discovery.AwesomeCatalogue.search(cat, "", "", "CA", "")
        assertEquals(listOf("CBC"), out.map { it.name })
    }
}
