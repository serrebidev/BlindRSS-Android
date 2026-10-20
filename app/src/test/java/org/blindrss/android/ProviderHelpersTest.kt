package org.blindrss.android
import org.blindrss.android.feed.DiscoverySearch
import org.blindrss.android.providers.FeverProvider
import org.blindrss.android.providers.NewsBlurProvider
import org.junit.Assert.*
import org.junit.Test

class ProviderHelpersTest {
    @Test fun feverChunksAllIds() {
        val ids = (1..120).map { it.toString() }
        val chunks = FeverProvider.chunkIds(ids, 50)
        assertEquals(listOf(50, 50, 20), chunks.map { it.size })
        assertEquals(ids, chunks.flatten())
    }
    @Test fun newsBlurFolders() {
        val raw = """{"feeds":{"12":{"feed_title":"A"},"34":{"feed_title":"B"},"56":{"feed_title":"C"}},"flat_folders":[{"Tech":[12,34],"News":[56]}]}"""
        val map = NewsBlurProvider.parseFolders(raw)
        assertEquals("Tech", map["12"]); assertEquals("Tech", map["34"]); assertEquals("News", map["56"])
    }
    @Test fun itunesParse() {
        val hits = DiscoverySearch.parseItunes("""{"results":[{"collectionName":"My Pod","feedUrl":"https://e.com/feed.xml"}]}""")
        assertEquals(1, hits.size); assertEquals("My Pod", hits[0].title); assertEquals("https://e.com/feed.xml", hits[0].feedUrl)
    }
    @Test fun gpodderParse() {
        val hits = DiscoverySearch.parseGpodder("""[{"title":"GP","url":"https://e.com/gp.xml"}]""")
        assertEquals(1, hits.size); assertEquals("GP", hits[0].title)
    }
}
