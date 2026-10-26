package org.blindrss.android.ui
import org.blindrss.android.feed.FullText

// The offline user guide (desktop: Help > User Guide), as bundled markdown.
// The parser is deliberately small: headings, paragraphs, list items, table
// rows read as comma-joined cells. Each block becomes one TalkBack stop.
object HelpText {
    fun blocks(markdown: String): List<FullText.Block> {
        val out = mutableListOf<FullText.Block>()
        val para = StringBuilder()
        fun flush() { val t = clean(para.toString()); if (t.length >= 2) out += FullText.Block(false, t); para.clear() }
        for (raw in markdown.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> flush()
                line.startsWith("#") -> { flush()
                    val text = clean(line.trimStart('#').trim().replace(Regex("\\s*\\{#[^}]+}\\s*$"), ""))
                    if (text.length >= 2) out += FullText.Block(true, text) }
                line.startsWith("---") || line.startsWith("===") -> flush()
                line.startsWith("![") -> flush() // images carry no readable text
                line.startsWith("|") -> { flush()
                    val cells = line.trim('|').split("|").map { clean(it) }.filter { it.isNotEmpty() && !it.all { c -> c in "-: " } }
                    if (cells.isNotEmpty()) out += FullText.Block(false, cells.joinToString(", ")) }
                line.startsWith("- ") || line.startsWith("* ") || Regex("^\\d+\\.\\s").containsMatchIn(line) -> { flush()
                    val t = clean(line.replace(Regex("^(-|\\*|\\d+\\.)\\s+"), ""))
                    if (t.length >= 2) out += FullText.Block(false, t) }
                else -> { if (para.isNotEmpty()) para.append(" "); para.append(line) }
            }
        }
        flush()
        return out
    }
    private fun clean(s: String): String = s
        .replace(Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)"), "$1 ($2)")
        .replace("**", "").replace("__", "").replace("`", "")
        .replace(Regex("\\s+"), " ").trim()
}
