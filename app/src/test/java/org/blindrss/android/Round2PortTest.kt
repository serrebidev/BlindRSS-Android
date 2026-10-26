package org.blindrss.android
import org.blindrss.android.feed.GoogleGroups
import org.blindrss.android.feed.GroupsIo
import org.blindrss.android.feed.MediaSites
import org.blindrss.android.feed.Mixcloud
import org.blindrss.android.feed.ListingKind
import org.blindrss.android.feed.SoundCloud
import org.blindrss.android.feed.VideoSearch
import org.blindrss.android.update.AppUpdate
import org.junit.Assert.*
import org.junit.Test

class Round2PortTest {
    // ---- self-update ----
    @Test fun versionCompare() {
        assertTrue(AppUpdate.compareVersions("0.11.0", "0.10.0") > 0)
        assertTrue(AppUpdate.compareVersions("0.10.0", "0.11.0") < 0)
        assertEquals(0, AppUpdate.compareVersions("v1.2.3", "1.2.3"))
        assertTrue(AppUpdate.compareVersions("1.0", "0.99.9") > 0)
        assertTrue(AppUpdate.compareVersions("2.0.0", "10.0.0") < 0)
        assertTrue(AppUpdate.isNewer("0.11.0", "0.10.0"))
        assertFalse(AppUpdate.isNewer("0.10.0", "0.10.0"))
    }
    @Test fun releaseParsing() {
        val json = """{"tag_name":"v0.11.0","html_url":"https://github.com/x/releases/v0.11.0","assets":[
            {"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":10},
            {"name":"BlindRSS-Android-v0.11.0.apk","browser_download_url":"https://x/app.apk","size":12345}]}"""
        val info = AppUpdate.parseRelease(json)!!
        assertEquals("0.11.0", info.version)
        assertEquals("https://x/app.apk", info.apkUrl)
        assertEquals(12345L, info.sizeBytes)
        assertNull(AppUpdate.parseRelease("""{"tag_name":"v1.0.0","assets":[]}"""))
        assertNull(AppUpdate.parseRelease("not json"))
    }
    // ---- video search parsing ----
    @Test fun flatSearchEntries() {
        val json = """{"entries":[
            {"id":"abc123","title":"Cats","webpage_url":"https://www.youtube.com/watch?v=abc123","uploader":"Cat Channel","channel_url":"https://www.youtube.com/@cats","duration":95,"view_count":42,"upload_date":"20240102"},
            {"id":"def456","title":"Dogs","url":"https://soundcloud.com/dogs/bark","uploader":"Dogs"}]}"""
        val out = VideoSearch.parseFlatEntries(json, "YouTube")
        assertEquals(2, out.size)
        assertEquals("Cats", out[0].title)
        assertEquals("Cat Channel", out[0].uploader)
        assertEquals(95L, out[0].durationSec)
        assertTrue(out[0].uploadDateMs > 0)
        assertEquals("https://soundcloud.com/dogs/bark", out[1].url)
        assertEquals("ytsearch40:cats", VideoSearch.queryTarget(VideoSearch.site("youtube")!!, "cats", 40))
        assertEquals(2, VideoSearch.entriesToListingItems(json).size)
    }
    // ---- listing classification ----
    @Test fun listingKinds() {
        assertEquals(ListingKind.SOUNDCLOUD, MediaSites.listingKind("https://soundcloud.com/someuser"))
        assertEquals(ListingKind.SOUNDCLOUD, MediaSites.listingKind("https://soundcloud.com/someuser/sets/mix"))
        assertEquals("track", SoundCloud.listingKind("https://soundcloud.com/someuser/a-track"))
        // The native RSS host stays a normal feed, never a listing.
        assertNull(MediaSites.listingKind("https://feeds.soundcloud.com/users/123/sounds.rss"))
        assertEquals(ListingKind.MIXCLOUD, MediaSites.listingKind("https://www.mixcloud.com/someuser/"))
        assertEquals(ListingKind.GOOGLE_GROUPS, MediaSites.listingKind("https://groups.google.com/g/nvda"))
        assertEquals(ListingKind.YOUTUBE, MediaSites.listingKind("https://www.youtube.com/results?search_query=blindrss"))
        assertNull(MediaSites.listingKind("https://example.com/feed.xml"))
        assertTrue(MediaSites.isPlayablePage("https://soundcloud.com/a/b"))
        assertFalse(MediaSites.isPlayablePage("https://example.com/post"))
    }
    // ---- SoundCloud ----
    @Test fun soundcloudParsing() {
        val users = """{"collection":[{"username":"DJ Test","permalink_url":"https://soundcloud.com/djtest","followers_count":1200}]}"""
        val hits = SoundCloud.parseUserSearch(users)
        assertEquals(1, hits.size); assertEquals("DJ Test", hits[0].title)
        assertTrue(hits[0].source.contains("1200"))
        val tracks = """{"collection":[{"title":"Set 1","permalink_url":"https://soundcloud.com/djtest/set1","created_at":"2024/01/15 10:30:00 +0000","user":{"username":"DJ Test"}}]}"""
        val items = SoundCloud.parseTracks(tracks, "DJ Test")
        assertEquals(1, items.size); assertTrue(items[0].dateMs > 0)
        val html = """<script src="https://a-v2.sndcdn.com/assets/app.js"></script><script src="https://a-v2.sndcdn.com/assets/12-abc.js"></script>"""
        assertEquals(listOf("https://a-v2.sndcdn.com/assets/12-abc.js", "https://a-v2.sndcdn.com/assets/app.js"), SoundCloud.assetUrls(html))
        assertEquals("abcdef0123456789abcdef01", SoundCloud.clientIdFromAssets("", listOf("""x={client_id:"abcdef0123456789abcdef01"}""")))
    }
    // ---- Mixcloud ----
    @Test fun mixcloudParsing() {
        val users = """{"data":[{"name":"Radio X","username":"radiox","url":"https://www.mixcloud.com/radiox/","follower_count":50}]}"""
        val hits = Mixcloud.parseUserSearch(users)
        assertEquals(1, hits.size); assertEquals("Radio X", hits[0].title)
        val casts = """{"data":[{"name":"Show 1","url":"https://www.mixcloud.com/radiox/show-1/","play_count":77,"user":{"name":"Radio X","url":"https://www.mixcloud.com/radiox/"}}]}"""
        val media = Mixcloud.parseMediaSearch(casts)
        assertEquals(1, media.size); assertEquals(77L, media[0].viewCount)
        val items = Mixcloud.parseListing(casts, "Radio X")
        assertEquals(1, items.size); assertEquals("Show 1", items[0].title)
        assertEquals("/radiox/", Mixcloud.keyFromUrl("https://www.mixcloud.com/radiox/"))
        assertEquals("user", Mixcloud.listingKind("https://www.mixcloud.com/radiox/"))
    }
    // ---- Google Groups ----
    @Test fun googleGroupsParsing() {
        assertTrue(GoogleGroups.isGroupUrl("https://groups.google.com/g/nvda"))
        assertTrue(GoogleGroups.isThreadUrl("https://groups.google.com/g/nvda/c/AbC123"))
        val html = """<html><head><title>NVDA Users - Google Groups</title></head><body>
            <div data-rowid="c1"><a href="./g/nvda/c/AbC123"><span class="o1DPKc">How do I?</span></a><span class="z0zUgf">Jane</span></div>
            </body></html>"""
        val (title, items) = GoogleGroups.parseGroupItems(html)
        assertEquals("NVDA Users", title)
        assertEquals(1, items.size); assertEquals("How do I?", items[0].title)
        assertEquals("https://groups.google.com/g/nvda/c/AbC123", items[0].url)
        val thread = """<html><head><title>How do I? - Google Groups</title></head><body>
            <div class="eH2Xlc"><h3>Jane</h3><span class="zX2W9c">Jan 2</span><div role="region" aria-labelledby="x"><p>Press NVDA+N.</p></div></div></body></html>"""
        val doc = GoogleGroups.threadHtml(thread, "https://groups.google.com/g/nvda/c/AbC123")
        assertTrue(doc.contains("Jane")); assertTrue(doc.contains("Press NVDA+N."))
    }
    // ---- Groups.io ----
    @Test fun groupsIoParsing() {
        assertEquals("https://groups.io/g/nvda/rss", GroupsIo.groupFeedUrl("https://groups.io/g/nvda"))
        assertEquals("https://groups.io/g/nvda/rss", GroupsIo.groupFeedUrl("https://groups.io/g/nvda/topics"))
        assertTrue(GroupsIo.isThreadUrl("https://groups.io/g/nvda/topic/12345678"))
        assertEquals("https://groups.io/g/nvda/topic/12345678", GroupsIo.canonicalTopicUrl("https://groups.io/g/nvda/topic/hello/12345678"))
        val page = """<html><head><title>Topic | nvda</title></head><body><h1>Hello thread</h1>
            <div class="expanded-message"><span class="user-chip-name">Jane</span><div class="user-content"><p>First post.</p></div></div>
            <div class="expanded-message"><span class="user-chip-name">John</span><div class="user-content"><p>Reply here.</p></div></div>
            </body></html>"""
        val parsed = GroupsIo.parseTopicPage(page, "https://groups.io/g/nvda/topic/1")
        assertEquals("Hello thread", parsed.title)
        assertEquals(2, parsed.messages.size)
        val doc = GroupsIo.renderDocument(parsed.title, listOf(parsed))
        assertTrue(doc.contains("First post.")); assertTrue(doc.contains("Reply here."))
        assertTrue(doc.indexOf("Jane") < doc.indexOf("John"))
    }
    // ---- subscribe shortcut: groups.io group page -> native RSS ----
    @Test fun groupsIoShortcut() {
        assertEquals("https://groups.io/g/nvda/rss",
            org.blindrss.android.feed.FeedDiscovery.normalizeInput("https://groups.io/g/nvda"))
    }
}
