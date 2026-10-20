package org.blindrss.android.ui

// Controls how an article row is read by TalkBack in lists.
// Default order: title, source, author/date/flags, full text, read status last.
data class ListReadingPrefs(
    val readSource: Boolean = true,
    val readAuthor: Boolean = true,
    val readDate: Boolean = true,
    val readText: Boolean = true,
    val textAmount: String = "FULL", // SUMMARY | PARTIAL | FULL
    val readFlags: Boolean = true, // favorite / has media
    val statusPosition: String = "END" // START | END | OFF
)

object ListReading {
    fun articleSpeech(
        title: String, source: String, author: String, dateStr: String,
        descriptionPlain: String, contentPlain: String,
        isRead: Boolean, isFavorite: Boolean, hasMedia: Boolean,
        prefs: ListReadingPrefs
    ): String {
        val status = if (isRead) "Read." else "Unread."
        val parts = mutableListOf<String>()
        parts += title.trim().ifBlank { "(untitled)" }
        if (prefs.statusPosition == "START") parts += status
        if (prefs.readSource && source.isNotBlank()) parts += "From $source."
        if (prefs.readAuthor && author.isNotBlank()) parts += "By $author."
        if (prefs.readDate && dateStr.isNotBlank()) parts += "$dateStr."
        if (prefs.readFlags) {
            if (isFavorite) parts += "Favorite."
            if (hasMedia) parts += "Has media."
        }
        if (prefs.readText) {
            val text = selectText(descriptionPlain, contentPlain, prefs.textAmount)
            if (text.isNotBlank() && !text.equals(title.trim(), ignoreCase = true)) parts += ensureSentence(text)
        }
        if (prefs.statusPosition == "END") parts += status
        return parts.joinToString(" ")
    }

    fun selectText(descriptionPlain: String, contentPlain: String, amount: String): String {
        val desc = descriptionPlain.trim()
        val content = contentPlain.trim()
        return when (amount) {
            "SUMMARY" -> desc.ifBlank { content.take(240) }
            "PARTIAL" -> (content.ifBlank { desc }).take(600)
            else -> content.ifBlank { desc }
        }
    }

    private fun ensureSentence(t: String): String {
        val s = t.trim()
        return if (s.endsWith(".") || s.endsWith("!") || s.endsWith("?") || s.endsWith(":")) s else "$s."
    }
}
