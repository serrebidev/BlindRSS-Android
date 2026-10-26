package org.blindrss.android.feed
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

// YouTube Takeout import, mirroring the desktop importer (core/takeout.py)
// and the iOS port: subscription CSVs, the owner's channel CSV, playlist
// CSVs, and channel links in watch history all become native YouTube RSS
// feeds. Pure functions over bytes so the pipeline is unit-testable.
object Takeout {
    data class Entry(val title: String, val feedUrl: String)

    fun feedEntries(zipBytes: ByteArray): List<Entry> {
        val out = mutableListOf<Entry>(); val seen = mutableSetOf<String>()
        fun add(entries: List<Entry>) { for (e in entries) if (seen.add(e.feedUrl)) out += e }
        try { ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.lowercase()
                if (!entry.isDirectory && entry.size != 0L) {
                    val text = try { String(zip.readBytes(), Charsets.UTF_8) } catch (_: Exception) { "" }
                    when {
                        name.endsWith("subscriptions.csv") || name.endsWith("channel.csv") ->
                            add(csvEntries(text, "channel id", "channel title", "https://www.youtube.com/feeds/videos.xml?channel_id={id}"))
                        name.endsWith("playlists.csv") ->
                            add(csvEntries(text, "playlist id", "playlist title", "https://www.youtube.com/feeds/videos.xml?playlist_id={id}"))
                        name.endsWith("watch-history.html") -> add(historyHtmlEntries(text))
                        name.endsWith("watch-history.json") -> add(historyJsonEntries(text))
                    }
                }
                entry = zip.nextEntry
            }
        } } catch (_: Exception) { }
        return out
    }

    // Minimal RFC 4180 CSV: quoted fields, embedded commas/quotes, CRLF —
    // CRLF is two characters on Android (the iOS round found Swift treats it
    // as one grapheme; here rows split on \n and a trailing \r is trimmed).
    fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); var field = StringBuilder()
        var inQuotes = false; var i = 0; val s = text.removePrefix("﻿")
        fun endField() { row += field.toString(); field = StringBuilder() }
        fun endRow() { endField(); rows += row; row = mutableListOf() }
        while (i < s.length) { val c = s[i]
            when {
                inQuotes -> if (c == '"') { if (i + 1 < s.length && s[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false } else field.append(c)
                c == '"' && field.isEmpty() -> inQuotes = true
                c == ',' -> endField()
                c == '\n' -> { if (field.isNotEmpty() || row.isNotEmpty()) { if (field.endsWith('\r')) field.deleteCharAt(field.length - 1); endRow() } }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { if (field.endsWith('\r')) field.deleteCharAt(field.length - 1); endRow() }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    fun csvEntries(text: String, idHeader: String, titleHeader: String, template: String): List<Entry> {
        val rows = parseCsv(text); val header = rows.firstOrNull() ?: return emptyList()
        val lowered = header.map { it.trim().lowercase() }
        val idIndex = lowered.indexOfFirst { it.contains(idHeader) }; if (idIndex < 0) return emptyList()
        val titleIndex = lowered.indexOfFirst { it.contains(titleHeader) }
        return rows.drop(1).mapNotNull { row ->
            val id = row.getOrNull(idIndex)?.trim() ?: return@mapNotNull null
            if (id.length < 2) return@mapNotNull null
            val title = if (titleIndex >= 0) row.getOrNull(titleIndex)?.trim().orEmpty() else ""
            Entry(title.ifEmpty { id }, template.replace("{id}", id))
        }
    }

    // Channel links inside watch-history.html: youtube.com/channel/UC…
    fun historyHtmlEntries(html: String): List<Entry> {
        val out = mutableListOf<Entry>(); val seen = mutableSetOf<String>()
        for (m in Regex("youtube\\.com/channel/([A-Za-z0-9_\\-]{8,})").findAll(html)) {
            val id = m.groupValues[1]
            if (id.startsWith("UC") && seen.add(id)) out += Entry("YouTube channel", "https://www.youtube.com/feeds/videos.xml?channel_id=$id")
        }
        return out
    }

    // watch-history.json: each record's subtitles block names the channel and
    // links its page; the channel page subscribes through normal discovery.
    fun historyJsonEntries(json: String): List<Entry> {
        val out = mutableListOf<Entry>(); val seen = mutableSetOf<String>()
        try { val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) { val subs = arr.optJSONObject(i)?.optJSONArray("subtitles") ?: continue
                for (j in 0 until subs.length()) { val s = subs.optJSONObject(j) ?: continue
                    val url = s.optString("url"); val name = s.optString("name")
                    if (url.contains("youtube.com/") && seen.add(url)) out += Entry(name.ifEmpty { "YouTube channel" }, url) } }
        } catch (_: Exception) { }
        return out
    }
}

// Wayback Machine podcast archive recovery (desktop hosted podcast archive;
// iOS ported it in 0.1.0): old snapshots of a feed recover episodes the live
// feed no longer lists. CDX index is keyless.
object WaybackArchive {
    fun cdxUrl(feedUrl: String) = "https://web.archive.org/cdx/search/cdx?url=${java.net.URLEncoder.encode(feedUrl, "UTF-8")}&output=json&fl=timestamp,original&filter=statuscode:200&collapse=digest"
    // CDX JSON rows (first row is the header) -> snapshot file URLs, oldest first.
    fun snapshotUrls(cdxJson: String, limit: Int = 24): List<String> {
        val out = mutableListOf<String>()
        try { val arr = org.json.JSONArray(cdxJson)
            for (i in 1 until arr.length()) { val row = arr.optJSONArray(i) ?: continue
                val ts = row.optString(0); val orig = row.optString(1)
                if (ts.isNotBlank() && orig.isNotBlank()) out += "https://web.archive.org/web/${ts}id_/$orig" }
        } catch (_: Exception) { }
        return out.take(limit)
    }
}
