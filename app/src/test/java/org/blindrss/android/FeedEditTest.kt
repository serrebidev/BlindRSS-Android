package org.blindrss.android
import org.blindrss.android.data.Repository
import org.blindrss.android.data.Repository.Companion.Keyed
import org.blindrss.android.providers.GReaderProvider
import org.junit.Assert.*
import org.junit.Test

class FeedEditTest {
    @Test fun rekeyMatchesEnclosureThenLink() {
        val have = listOf(Keyed("a", "https://x.org/1", "https://cdn/1.mp3"), Keyed("b", "https://x.org/2", null), Keyed("c", "https://x.org/gone", null))
        val incoming = listOf(Keyed("A", "https://moved/1", "https://cdn/1.mp3"), Keyed("B", "https://x.org/2", null), Keyed("N", "https://x.org/new", null))
        assertEquals(mapOf("a" to "A", "b" to "B"), Repository.rekeyPlan(have, incoming))
    }
    @Test fun rekeySkipsSharedLinks() {
        // Podcast feeds that link every episode to the show page must not collapse onto one article.
        val have = listOf(Keyed("a", "https://show", "https://cdn/1.mp3"), Keyed("b", "https://show", "https://cdn/2.mp3"))
        val incoming = listOf(Keyed("B", "https://show", "https://cdn/2.mp3"), Keyed("N", "https://show", "https://cdn/3.mp3"))
        assertEquals(mapOf("b" to "B"), Repository.rekeyPlan(have, incoming))
    }
    @Test fun rekeyNeverReusesSourceOrTarget() {
        val have = listOf(Keyed("a", "https://x.org/1", null), Keyed("keep", "https://x.org/k", null))
        val incoming = listOf(Keyed("A1", "https://x.org/1", null), Keyed("A2", "https://x.org/1", null), Keyed("keep", "https://x.org/k", null))
        assertEquals(mapOf("a" to "A1"), Repository.rekeyPlan(have, incoming))
    }
    @Test fun feedDetection() {
        assertTrue(Repository.looksLikeFeed("<?xml version=\"1.0\"?><rss><channel/></rss>"))
        assertTrue(Repository.looksLikeFeed("  <feed xmlns=\"http://www.w3.org/2005/Atom\"></feed>"))
        assertTrue(Repository.looksLikeFeed("{\"version\":\"https://jsonfeed.org/version/1.1\"}"))
        assertFalse(Repository.looksLikeFeed("<!doctype html><html><body>Moved</body></html>"))
    }
    @Test fun greaderEditSendsOnlyChanges() {
        assertEquals(mapOf("s" to "feed/1", "ac" to "edit", "t" to "New"), GReaderProvider.editForm("feed/1", "Old", "New", "Tech", "Tech"))
        assertEquals(mapOf("s" to "feed/1", "ac" to "edit", "r" to "user/-/label/Tech", "a" to "user/-/label/News"), GReaderProvider.editForm("feed/1", "T", "T", "Tech", "News"))
        assertEquals(mapOf("s" to "feed/1", "ac" to "edit", "a" to "user/-/label/News"), GReaderProvider.editForm("feed/1", "T", "T", "Uncategorized", "News"))
        assertEquals(mapOf("s" to "feed/1", "ac" to "edit", "r" to "user/-/label/Tech"), GReaderProvider.editForm("feed/1", "T", "T", "Tech", "Uncategorized"))
    }
}
