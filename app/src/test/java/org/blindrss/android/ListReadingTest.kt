package org.blindrss.android
import org.blindrss.android.ui.ListReading
import org.blindrss.android.ui.ListReadingPrefs
import org.junit.Assert.*
import org.junit.Test

class ListReadingTest {
    @Test fun defaultOrderTitleSourceTextStatus() {
        val out = ListReading.articleSpeech("Hawk rescued", "Maple Ridge News", "Staff", "Oct 19, 2026",
            "Short summary.", "Police rescued a hawk with a broken wing near the road today.",
            isRead = false, isFavorite = false, hasMedia = false, prefs = ListReadingPrefs())
        assertTrue(out.startsWith("Hawk rescued"))
        assertTrue(out.indexOf("From Maple Ridge News") > out.indexOf("Hawk rescued"))
        assertTrue(out.indexOf("Police rescued a hawk") > out.indexOf("From Maple Ridge News"))
        assertTrue(out.endsWith("Unread."))
        assertFalse(out.startsWith("Hawk rescued Unread"))
    }
    @Test fun statusOffAndTextOff() {
        val out = ListReading.articleSpeech("T", "Feed", "", "Oct 19, 2026", "D", "Content body here.",
            true, false, false, ListReadingPrefs(readText = false, statusPosition = "OFF"))
        assertEquals("T From Feed. Oct 19, 2026.", out)
    }
    @Test fun statusStart() {
        val out = ListReading.articleSpeech("T", "Feed", "", "", "", "Body text.",
            true, false, false, ListReadingPrefs(statusPosition = "START"))
        assertTrue(out.startsWith("T Read. From Feed."))
    }
    @Test fun amounts() {
        val long = "x".repeat(1000)
        assertEquals(600, ListReading.selectText("", long, "PARTIAL").length)
        assertEquals(long, ListReading.selectText("", long, "FULL"))
        assertEquals("desc", ListReading.selectText("desc", long, "SUMMARY"))
    }
    @Test fun flagsBeforeTextStatusLast() {
        val out = ListReading.articleSpeech("T", "Feed", "", "", "", "Body.",
            false, isFavorite = true, hasMedia = true, prefs = ListReadingPrefs())
        assertTrue(out.indexOf("Favorite.") < out.indexOf("Body."))
        assertTrue(out.endsWith("Unread."))
    }
}
