package org.blindrss.android
import org.blindrss.android.feed.*
import org.blindrss.android.settings.FetchIdentity
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Round 3 (research + iOS parity): Takeout import, Groups.io API,
// Wayback recovery, directory parsers, reader presentation, Google
// sign-in cookies, SmartTube-style YouTube account flow.
class Round3PortTest {
    private fun zipOf(entries: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream(); val z = ZipOutputStream(bos)
        for ((name, body) in entries) { z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry() }
        z.close(); return bos.toByteArray()
    }

    @Test fun takeoutCsvWithQuotedCommas() {
        val csv = "Channel ID,Channel URL,Channel title\r\nUC123,https://www.youtube.com/channel/UC123,\"Doe, John\"\r\n"
        val rows = Takeout.parseCsv(csv)
        assertEquals(2, rows.size); assertEquals("Doe, John", rows[1][2])
    }
    @Test fun takeoutSubscriptionsZip() {
        val zip = zipOf(mapOf("Takeout/YouTube and YouTube Music/subscriptions/subscriptions.csv" to
            "Channel ID,Channel URL,Channel title\nUCabc,https://www.youtube.com/channel/UCabc,Chan\n"))
        val entries = Takeout.feedEntries(zip)
        assertEquals(1, entries.size); assertEquals("https://www.youtube.com/feeds/videos.xml?channel_id=UCabc", entries[0].feedUrl)
        assertEquals("Chan", entries[0].title)
    }
    @Test fun takeoutWatchHistoryJsonChannels() {
        val json = """[{"title":"Watch x","titleUrl":"https://www.youtube.com/watch?v=1","subtitles":[{"name":"Chan","url":"https://www.youtube.com/channel/UCzzz"}]}]"""
        val entries = Takeout.historyJsonEntries(json)
        assertEquals(1, entries.size); assertEquals("Chan", entries[0].title)
    }
    @Test fun netscapeCookiesToHeader() {
        val txt = "# Netscape HTTP Cookie File\n.example.com\tTRUE\t/\tTRUE\t1893456000\tSID\tabc123\n.example.com\tTRUE\t/\tFALSE\t1893456000\tHSID\txyz\n"
        assertEquals("SID=abc123; HSID=xyz", FetchIdentity.netscapeToHeader(txt))
    }
    @Test fun groupsIoApiTopicRef() {
        val ref = GroupsIoApi.topicRef("https://groups.io/g/main/topic/hello_world/12345?p=,,,20")!!
        assertEquals("main", ref.first); assertEquals(12345L, ref.second)
        assertTrue(GroupsIoApi.messagesUrl(ref.second).startsWith("https://api.groups.io/v1/getmessages"))
        assertNull(GroupsIoApi.topicRef("https://example.com/g/main/topic/x/1"))
    }
    @Test fun waybackSnapshotUrls() {
        val cdx = """[["timestamp","original"],["20230101000000","https://feeds.example.com/show.xml"],["20240101000000","https://feeds.example.com/show.xml?x=1"]]"""
        val urls = WaybackArchive.snapshotUrls(cdx)
        assertEquals(2, urls.size); assertTrue(urls[0].contains("web.archive.org/web/20230101000000id_/"))
    }
    @Test fun discoveryParsers() {
        assertEquals("https://f.example/x.xml", DiscoverySearch.parseFyyd("""{"data":[{"title":"P","xmlURL":"https://f.example/x.xml"}]}""")[0].feedUrl)
        assertEquals("https://p.example/feed", DiscoverySearch.parsePodverse("""{"data":[[{"title":"P","feedUrls":[{"url":"https://p.example/feed"}]}],1]}""")[0].feedUrl)
        assertEquals("https://fd.example/rss", DiscoverySearch.parseFeedly("""{"results":[{"title":"P","feedId":"feed/https://fd.example/rss"}]}""")[0].feedUrl)
        assertEquals("https://nb.example/rss", DiscoverySearch.parseNewsBlur("""[{"value":"https://nb.example/rss","label":"P"}]""")[0].feedUrl)
        assertEquals("https://www.reddit.com/r/android/.rss", DiscoverySearch.parseReddit("""{"data":{"children":[{"data":{"display_name":"android","title":"Android"}}]}}""")[0].feedUrl)
        assertEquals("https://lemmy.world/feeds/c/ask.xml", DiscoverySearch.lemmyCommunityFeed("https://lemmy.world/c/ask"))
        assertEquals("https://groups.io/g/mygroup/rss", DiscoverySearch.parseGroupsIoSearch("""<a href="/g/mygroup">My Group</a>""")[0].feedUrl)
    }
    @Test fun readerPresentationToggles() {
        val html = """<ul><li>One</li><li>Two</li></ul><blockquote>Quoted</blockquote><p>See <a href="https://x.example">here</a> now</p><p><img alt="A sleepy dog"></p>"""
        val plain = FullText.blocks(html)
        assertFalse(plain.any { it.text.startsWith("•") })
        val styled = FullText.blocksStyled(html, FullText.Presentation(bullets = true, quotes = true, linkTargets = true, imageAlt = true))
        assertTrue(styled.any { it.text.startsWith("• One") })
        assertTrue(styled.any { it.text.startsWith("Quote: Quoted") })
        assertTrue(styled.any { it.text.contains("(https://x.example)") })
        assertTrue(styled.any { it.text == "Image: A sleepy dog" })
    }
    @Test fun smartTubeClientConstants() {
        val js = """var x={clientId:"861556708454-abc123.apps.googleusercontent.com",otherKey:"s3cr3tValue"};"""
        val c = YouTubeAccount.parseClientConstants(js)!!
        assertTrue(c.id.endsWith(".apps.googleusercontent.com")); assertEquals("s3cr3tValue", c.secret)
    }
    @Test fun deviceCodeAndTokenParsing() {
        val dc = YouTubeAccount.parseDeviceCode("""{"user_code":"ABCD-EFGH","device_code":"dev123","verification_url":"https://www.google.com/device","interval":5,"expires_in":1800}""")
        assertEquals("ABCD-EFGH", dc.userCode)
        val (pending, err) = YouTubeAccount.parseTokenResponse("""{"error":"authorization_pending"}""")
        assertNull(pending); assertEquals("pending", err)
        val (tokens, _) = YouTubeAccount.parseTokenResponse("""{"access_token":"ya29.x","refresh_token":"1//y"}""")
        assertEquals("ya29.x", tokens!!.accessToken); assertEquals("1//y", tokens.refreshToken)
    }
    @Test fun browseParsing() {
        val json = """{"contents":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[{"videoRenderer":{"videoId":"vid1","title":{"runs":[{"text":"A video"}]},"shortBylineText":{"runs":[{"text":"Chan","navigationEndpoint":{"browseEndpoint":{"browseId":"UCchan"}}}]}}}]}}]}}}"""
        val out = YouTubeAccount.parseBrowse(json)
        assertEquals(1, out.size); assertEquals("A video", out[0].title)
        assertEquals("https://www.youtube.com/channel/UCchan", out[0].channelUrl)
    }
}
