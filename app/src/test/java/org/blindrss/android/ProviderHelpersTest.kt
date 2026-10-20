package org.blindrss.android
import org.blindrss.android.feed.DiscoverySearch
import org.blindrss.android.providers.FeverProvider
import org.blindrss.android.providers.FeedbinProvider
import org.blindrss.android.providers.GReaderProvider
import org.blindrss.android.providers.MinifluxProvider
import org.json.JSONObject
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
    @Test fun freshRssBaseAddsReaderPath() {
        assertEquals("https://x.org/api/greader.php/reader/api/0", GReaderProvider.freshRssBase("https://x.org/api/greader.php/"))
        assertEquals("https://x.org/api/greader.php/reader/api/0", GReaderProvider.freshRssBase("https://x.org/api/greader.php/reader/api/0"))
    }
    @Test fun feverEndpoint() {
        assertEquals("https://x.org/api/fever.php?api", FeverProvider.endpointFor("https://x.org/api/fever.php"))
        assertEquals("https://x.org/fever/?api", FeverProvider.endpointFor("https://x.org/fever/"))
    }
    @Test fun feverGroups() {
        val j = JSONObject("""{"groups":[{"id":1,"title":"Tech"},{"id":2,"title":"News"}],"feeds_groups":[{"group_id":1,"feed_ids":"3,4"},{"group_id":2,"feed_ids":"5"}]}""")
        assertEquals(mapOf("3" to "Tech", "4" to "Tech", "5" to "News"), FeverProvider.groupMap(j))
    }
    @Test fun greaderItemsPreferContentAndKeepState() {
        val raw = """{"items":[{"id":"tag:google.com,2005:reader/item/1","origin":{"streamId":"feed/1"},"title":"T","published":10,
            "summary":{"content":"short"},"content":{"content":"full"},"alternate":[{"href":"https://a"}],"enclosure":[{"href":"https://a.mp3"}],
            "categories":["user/-/state/com.google/read","user/-/state/com.google/starred"]}]}"""
        val a = GReaderProvider.parseItems(raw).single()
        assertEquals("full", a.content); assertEquals("feed/1", a.feedRemoteId); assertEquals("https://a.mp3", a.mediaUrl)
        assertTrue(a.isRead); assertTrue(a.isFavorite); assertEquals(10_000L, a.date)
    }
    @Test fun newsBlurHashesAndStories() {
        assertEquals(setOf("1:a", "1:b", "2:c"), NewsBlurProvider.parseUnreadHashes("""{"unread_feed_story_hashes":{"1":["1:a","1:b"],"2":["2:c"]}}"""))
        val s = NewsBlurProvider.parseStories("""{"stories":[{"story_hash":"1:a","story_feed_id":1,"story_title":"T","read_status":0,"starred":true,"story_timestamp":"5"}]}""").single()
        assertEquals("1:a", s.remoteId); assertEquals("1", s.feedRemoteId); assertFalse(s.isRead); assertTrue(s.isFavorite); assertEquals(5_000L, s.date)
    }
    @Test fun feedbinEntriesHandleNullsAndState() {
        val raw = """[{"id":7,"feed_id":3,"title":null,"author":null,"url":"https://a","content":"<p>x</p>","published":"2013-02-02T14:07:33.000000Z","enclosure":{"enclosure_url":"https://a.mp3"}},
            {"id":8,"feed_id":3,"title":"B","url":"https://b","content":null,"published":"bad"}]"""
        val (a, b) = FeedbinProvider.parseEntries(raw, unread = setOf(8L), starred = setOf(7L))
        assertEquals("", a.title); assertEquals("", a.author); assertEquals("https://a.mp3", a.mediaUrl); assertTrue(a.isRead); assertTrue(a.isFavorite)
        assertEquals(1359814053000L, a.date); assertFalse(b.isRead); assertFalse(b.isFavorite); assertEquals("", b.content); assertNull(b.mediaUrl)
    }
    @Test fun minifluxEntries() {
        val raw = """[{"id":5,"feed_id":2,"title":"T","url":"https://t","content":"c","author":"me","status":"read","starred":true,"published_at":"2024-01-01T00:00:00Z","changed_at":"2024-01-02T00:00:00Z","enclosures":[{"url":"https://t.mp3"}]}]"""
        val e = MinifluxProvider.parseEntries(raw).single()
        assertEquals("5", e.remoteId); assertEquals("2", e.feedRemoteId); assertTrue(e.isRead); assertTrue(e.isFavorite); assertEquals("https://t.mp3", e.mediaUrl)
        assertEquals(1704067200000L, e.date); assertEquals(1704153600L, MinifluxProvider.isoSeconds("2024-01-02T00:00:00Z"))
    }
    @Test fun readAloudChunksBySentence() {
        val parts = org.blindrss.android.playback.ReadAloud.chunks("One. Two two. " + "x".repeat(250) + ". Four.", max = 100, firstMax = 10)
        assertEquals("One.", parts[0]); assertTrue(parts.all { it.length <= 100 }); assertEquals("Four.", parts.last())
        assertEquals("x".repeat(250) + ".", parts.filter { it.startsWith("x") }.joinToString(""))
    }
    @Test fun decodeUsesXmlDeclaredCharset() {
        val bytes = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><rss>caf\u00e9</rss>".toByteArray(Charsets.ISO_8859_1)
        assertTrue(org.blindrss.android.data.Repository.decode(bytes, null).contains("caf\u00e9"))
        assertEquals("<a/>", org.blindrss.android.data.Repository.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "<a/>".toByteArray(), null))
    }
}
