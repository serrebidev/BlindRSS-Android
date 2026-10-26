package org.blindrss.android.settings

// The identity feeds and article pages are fetched with, mirroring desktop's
// configurable browser identity for bot-protected sites (core/user_agents.py).
// Presets only; the app's own identity stays the default.
object FetchIdentity {
    val PRESETS = listOf(
        "BLINDRSS" to "BlindRSS",
        "CHROME_WINDOWS" to "Chrome on Windows",
        "CHROME_ANDROID" to "Chrome on Android",
        "FIREFOX" to "Firefox on Windows"
    )
    fun userAgent(preset: String): String = when (preset) {
        "CHROME_WINDOWS" -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        "CHROME_ANDROID" -> "Mozilla/5.0 (Linux; Android 14; SM-X820) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        "FIREFOX" -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
        else -> "BlindRSS-Android/1.0"
    }
    // A pasted Cookie header is sent verbatim; strip anything that is not a
    // header-safe cookie string so a bad paste cannot break every fetch.
    fun sanitizeCookies(raw: String): String = raw.trim().replace(Regex("[\\r\\n]"), "").take(4096)
    // A cookies.txt (Netscape format, as browsers and the desktop export)
    // becomes a Cookie header: name=value pairs from every cookie line.
    fun netscapeToHeader(text: String): String {
        val pairs = mutableListOf<String>()
        for (line in text.lineSequence()) { val l = line.removePrefix("#HttpOnly_")
            if (l.startsWith("#") || l.isBlank()) continue
            val parts = l.split("\t"); if (parts.size < 7) continue
            val name = parts[5].trim(); val value = parts[6].trim()
            if (name.isNotEmpty()) pairs += "$name=$value" }
        return sanitizeCookies(pairs.joinToString("; "))
    }
}
