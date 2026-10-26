package org.blindrss.android
import org.blindrss.android.feed.DiscoverySearch
import org.blindrss.android.feed.FeedDiscovery
import org.blindrss.android.feed.ForumText
import org.blindrss.android.feed.YoutubeText
import org.blindrss.android.settings.FetchIdentity
import org.blindrss.android.ui.HelpText
import org.junit.Assert.*
import org.junit.Test

class ParityPortTest {
    // ---- subscribe shortcuts ----
    @Test fun shortcutPlaylist() {
        assertEquals("https://www.youtube.com/feeds/videos.xml?playlist_id=PLabc123",
            FeedDiscovery.normalizeInput("https://www.youtube.com/playlist?list=PLabc123"))
    }
    @Test fun shortcutBluesky() {
        assertEquals("https://bsky.app/profile/user.bsky.social/rss",
            FeedDiscovery.normalizeInput("https://bsky.app/profile/user.bsky.social"))
        // A post link is not a profile: untouched.
        assertEquals("https://bsky.app/profile/user.bsky.social/post/3lxyz",
            FeedDiscovery.normalizeInput("https://bsky.app/profile/user.bsky.social/post/3lxyz"))
    }
    @Test fun shortcutGithub() {
        assertEquals("https://github.com/serrebidev/BlindRSS/releases.atom",
            FeedDiscovery.normalizeInput("https://github.com/serrebidev/BlindRSS"))
        assertEquals("https://github.com/serrebidev/BlindRSS/issues",
            FeedDiscovery.normalizeInput("https://github.com/serrebidev/BlindRSS/issues"))
    }
    @Test fun shortcutMastodon() {
        assertEquals("https://mastodon.social/@alice.rss",
            FeedDiscovery.normalizeInput("https://mastodon.social/@alice"))
        assertEquals("https://mastodon.social/tags/android.rss",
            FeedDiscovery.normalizeInput("https://mastodon.social/tags/android"))
    }
    @Test fun shortcutLeavesPlainSitesAlone() {
        assertEquals("https://example.com/blog", FeedDiscovery.normalizeInput("https://example.com/blog"))
    }

    // ---- directory search parsers ----
    @Test fun feedsearchParse() {
        val out = DiscoverySearch.parseFeedsearch("""[{"url":"https://e.com/feed","title":"Example"}]""")
        assertEquals(1, out.size); assertEquals("Example", out[0].title); assertEquals("Feedsearch", out[0].source)
    }
    @Test fun mastodonParseAccountsOnly() {
        val json = """{"accounts":[{"display_name":"Alice","acct":"alice@mastodon.social","url":"https://mastodon.social/@alice"}],"statuses":[{"url":"https://mastodon.social/@bob/123"}],"hashtags":[]}"""
        val out = DiscoverySearch.parseMastodon(json)
        assertEquals(1, out.size); assertEquals("https://mastodon.social/@alice.rss", out[0].feedUrl)
    }
    @Test fun blueskyParse() {
        val out = DiscoverySearch.parseBluesky("""{"actors":[{"handle":"alice.bsky.social","displayName":"Alice A"}]}""")
        assertEquals(1, out.size); assertEquals("https://bsky.app/profile/alice.bsky.social/rss", out[0].feedUrl)
    }
    @Test fun piefedParseConvertsCommunity() {
        val out = DiscoverySearch.parsePiefed("""{"communities":[{"name":"android","title":"Android","url":"https://piefed.social/c/android"}]}""", "PieFed community")
        assertEquals(1, out.size); assertTrue(out[0].feedUrl.contains("/feeds/c/android.xml"))
    }
    @Test fun mastodonUrlResolvesHandles() {
        assertTrue(DiscoverySearch.mastodonSearchUrl("alice@example.com").contains("resolve=true"))
        assertFalse(DiscoverySearch.mastodonSearchUrl("alice").contains("resolve=true"))
    }

    // ---- YouTube full text ----
    @Test fun youtubeIds() {
        assertEquals("dQw4w9WgXcQ", YoutubeText.videoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=5"))
        assertEquals("dQw4w9WgXcQ", YoutubeText.videoId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YoutubeText.videoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("", YoutubeText.videoId("https://www.youtube.com/@somechannel"))
        assertEquals("", YoutubeText.videoId("https://vimeo.com/12345"))
    }
    @Test fun subtitlePickPrefersManualEnglishJson3() {
        val info = """{"subtitles":{"fr":[{"ext":"json3","url":"https://e/fr"}],"en":[{"ext":"vtt","url":"https://e/env"},{"ext":"json3","url":"https://e/en"}]},
            "automatic_captions":{"en":[{"ext":"json3","url":"https://e/auto-en"}]}}"""
        assertEquals("https://e/en", YoutubeText.subtitleUrl(info))
        val autoOnly = """{"automatic_captions":{"de":[{"ext":"json3","url":"https://e/de"}]}}"""
        assertEquals("https://e/de", YoutubeText.subtitleUrl(autoOnly))
    }
    @Test fun transcriptDedupesRollingCaptions() {
        val json3 = """{"events":[{"tStartMs":0,"segs":[{"utf8":"Hello"}]},{"tStartMs":1000,"segs":[{"utf8":"Hello"}]},{"tStartMs":2000,"segs":[{"utf8":"world"}]}]}"""
        val lines = YoutubeText.transcriptLines(json3)
        assertEquals(2, lines.size); assertEquals("world", lines[1].second)
    }
    @Test fun youtubeArticleHtmlSections() {
        val info = """{"description":"Line one.\n\nLine two.","chapters":[{"start_time":0,"title":"Intro"},{"start_time":65,"title":"Main"}],
            "comments":[{"author":"Bob","text":"Great video","like_count":5,"is_pinned":false},{"author":"Ana","text":"First","like_count":50,"is_pinned":true}]}"""
        val html = YoutubeText.buildHtml(info, listOf(0L to "hello transcript"))
        assertTrue(html.contains("<h2>Description</h2>")); assertTrue(html.contains("Line two."))
        assertTrue(html.contains("0:00 Intro")); assertTrue(html.contains("1:05 Main"))
        assertTrue(html.contains("<h2>Transcript</h2>")); assertTrue(html.contains("hello transcript"))
        // Pinned comment first even with fewer likes.
        assertTrue(html.indexOf("Ana: First") < html.indexOf("Bob: Great video"))
    }

    // ---- forum threads ----
    @Test fun forumTargets() {
        val r = ForumText.targetFor("https://www.reddit.com/r/kotlin/comments/abc123/some_title/", "https://www.reddit.com/r/kotlin/.rss")
        assertEquals(ForumText.Kind.REDDIT, r?.kind)
        val l = ForumText.targetFor("https://lemmy.world/post/12345", "https://lemmy.world/feeds/c/android.xml")
        assertEquals(ForumText.Kind.LEMMY, l?.kind); assertTrue(l!!.jsonUrl.contains("/api/v3/post?id=12345"))
        // A WordPress-style /post/123 whose feed lives elsewhere is not a Lemmy target.
        assertNull(ForumText.targetFor("https://blog.example.com/post/123", "https://other.example/feed"))
        assertNull(ForumText.targetFor("https://example.com/news/story", "https://example.com/feed"))
    }
    @Test fun redditThreadRendersPostAndNestedComments() {
        val json = """[{"data":{"children":[{"data":{"selftext":"Post body here.","url":"https://www.reddit.com/r/x/comments/1"}}]}},
            {"data":{"children":[
              {"kind":"t1","data":{"author":"amy","body":"Top comment","depth":0,"replies":{"data":{"children":[{"kind":"t1","data":{"author":"ben","body":"Nested reply","depth":1,"replies":""}}]}}}},
              {"kind":"t1","data":{"author":"gone","body":"[deleted]","depth":0,"replies":""}},
              {"kind":"more","data":{}}]}}]"""
        val html = ForumText.redditHtml(json)
        assertTrue(html.contains("Post body here."))
        assertTrue(html.contains("amy: Top comment"))
        assertTrue(html.contains("Reply by ben: Nested reply"))
        assertFalse(html.contains("[deleted]"))
    }
    @Test fun lemmyThreadOrdersByPath() {
        val post = """{"post_view":{"post":{"body":"Lemmy post body."}}}"""
        val comments = """{"comments":[
            {"comment":{"content":"Child comment","path":"0.10.11"},"creator":{"name":"cid"}},
            {"comment":{"content":"Parent comment","path":"0.10"},"creator":{"name":"pam"}}]}"""
        val html = ForumText.lemmyHtml(post, comments)
        assertTrue(html.contains("Lemmy post body."))
        assertTrue(html.indexOf("pam: Parent comment") < html.indexOf("Reply by cid: Child comment"))
    }

    // ---- fetch identity ----
    @Test fun fetchIdentityPresets() {
        assertEquals("BlindRSS-Android/1.0", FetchIdentity.userAgent("BLINDRSS"))
        assertTrue(FetchIdentity.userAgent("CHROME_WINDOWS").contains("Chrome/126"))
        assertTrue(FetchIdentity.userAgent("UNKNOWN").startsWith("BlindRSS"))
        assertEquals("a=1; b=2", FetchIdentity.sanitizeCookies("  a=1; b=2\r\n"))
    }

    // ---- user guide parser ----
    @Test fun helpBlocks() {
        val md = "# Title\n\nIntro **bold** text\ncontinues here.\n\n## Section {#anchor}\n\n- First item\n- Second [link](https://e.com)\n\n| A | B |\n"
        val blocks = HelpText.blocks(md)
        assertEquals("Title", blocks[0].text); assertTrue(blocks[0].heading)
        assertTrue(blocks.any { it.text == "Intro bold text continues here." })
        assertTrue(blocks.any { it.heading && it.text == "Section" })
        assertTrue(blocks.any { it.text == "First item" })
        assertTrue(blocks.any { it.text == "Second link (https://e.com)" })
        assertTrue(blocks.any { it.text == "A, B" })
    }
}
