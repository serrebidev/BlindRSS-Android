package org.blindrss.android.playback
import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.*
import org.blindrss.android.data.ArticleEntity
import org.blindrss.android.data.Repository
import org.blindrss.android.feed.FullText
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Read aloud: Android text-to-speech renders the article to audio files that play through the normal player,
// so speed, skip silence, sleep timer and screen-off listening all apply. When an article ends it is marked
// read and the newest remaining unread article starts.
class ReadAloud(private val ctx: Context, private val repo: Repository, private val player: PlayerController, private val announce: (String) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tts: TextToSpeech? = null
    private var job: Job? = null
    @Volatile private var synthesizing = false
    private val dir get() = File(ctx.cacheDir, "tts")

    fun start(a: ArticleEntity) { job?.cancel(); job = scope.launch {
        try { speak(a) } catch (e: CancellationException) { throw e } catch (e: Exception) { synthesizing = false; announce("Read aloud failed: ${e.message}") } } }

    private suspend fun speak(a: ArticleEntity) {
        val engine = engine()
        withContext(Dispatchers.IO) { dir.deleteRecursively(); dir.mkdirs() }
        val body = try { repo.fullText(a) } catch (_: Exception) { a.content.ifBlank { a.description } }
        val feed = repo.feeds.byId(a.feedId)?.title.orEmpty()
        val text = "${a.title}. ${if (feed.isNotEmpty()) "From $feed. " else ""}${FullText.toPlain(body)}"
        val parts = chunks(text, TextToSpeech.getMaxSpeechInputLength() - 100)
        synthesizing = true
        player.onEnded = { if (!synthesizing) finished(a) }
        try {
            parts.forEachIndexed { i, part -> val f = synth(engine, part, File(dir, "part_$i.wav"))
                if (i == 0) { player.playSpoken(a.id, a.title, f); announce("Reading aloud: ${a.title}") } else player.appendSpoken(f) }
        } finally { synthesizing = false }
        // Ran out exactly while the last part was being added: the ended event was ignored, so finish here.
        if (player.isEnded()) finished(a)
    }

    private fun finished(a: ArticleEntity) { player.onEnded = null; scope.launch {
        repo.articles.setRead(a.id, true); repo.pushRead(a, true)
        repo.articles.nextUnread(a.id)?.let { start(it) } ?: announce("No more unread articles") } }

    private suspend fun engine(): TextToSpeech = tts ?: suspendCancellableCoroutine { c ->
        var t: TextToSpeech? = null
        t = TextToSpeech(ctx) { status -> if (status == TextToSpeech.SUCCESS) { tts = t; c.resume(t!!) } else c.resumeWithException(IOException("Text to speech is not available")) }
    }

    private suspend fun synth(engine: TextToSpeech, text: String, f: File): File = suspendCancellableCoroutine { c ->
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { if (id == f.name && c.isActive) c.resume(f) }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { if (id == f.name && c.isActive) c.resumeWithException(IOException("Speech synthesis failed")) }
        })
        if (engine.synthesizeToFile(text, Bundle(), f, f.name) != TextToSpeech.SUCCESS) c.resumeWithException(IOException("Speech synthesis failed"))
    }

    companion object {
        // Sentence-aligned parts no longer than max; the first part is short so speech starts quickly.
        fun chunks(text: String, max: Int, firstMax: Int = 600): List<String> {
            val out = mutableListOf<String>(); val cur = StringBuilder()
            for (raw in text.split(Regex("(?<=[.!?])\\s+|\\n+"))) { val p = raw.trim(); if (p.isEmpty()) continue
                val limit = if (out.isEmpty()) minOf(firstMax, max) else max
                if (cur.isNotEmpty() && cur.length + p.length + 1 > limit) { out += cur.toString(); cur.clear() }
                if (p.length > max) p.chunked(max).forEach { out += it } else { if (cur.isNotEmpty()) cur.append(' '); cur.append(p) }
            }
            if (cur.isNotEmpty()) out += cur.toString()
            return out
        }
    }
}
