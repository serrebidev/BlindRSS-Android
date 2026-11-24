package org.blindrss.android

import java.net.URLEncoder

/**
 * Settings, About, Report an issue on GitHub: GitHub's new-issue page with the title and
 * description the user typed filled in and the version line added. GitHub needs the user's own
 * account, so the last step, Create, is theirs in the browser.
 */
object ReportIssue {
    fun url(title: String, details: String, appLine: String): String {
        val body = "${details.trim()}\n\n---\n$appLine"
        return "https://github.com/serrebidev/BlindRSS-Android/issues/new?title=${encode(title.trim())}&body=${encode(body)}"
    }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}
