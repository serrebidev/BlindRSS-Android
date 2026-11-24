package org.blindrss.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportIssueTest {
    @Test fun issueLinkCarriesTitleBodyAndVersion() {
        val url = ReportIssue.url("Photo & video", "Line 1\nПривет", "BlindRSS 1.0 on Android 16")
        assertTrue(url, url.startsWith(
            "https://github.com/serrebidev/BlindRSS-Android/issues/new?title=Photo%20%26%20video&body=Line%201%0A%D0%9F"
        ))
        assertTrue(url, url.endsWith("%0A---%0ABlindRSS%201.0%20on%20Android%2016"))
        assertFalse(url, url.any { it == ' ' || it == '\n' || it == '#' })
    }
}
