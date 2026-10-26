package org.blindrss.android
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

// Crash evidence and containment (0.13.1). Before this, the app had no
// crash handler and no coroutine exception handlers: an uncaught
// exception anywhere — a settings read failing, a database hiccup
// during playback bookkeeping — killed the app and left no trace, so
// crashes could only be reported as "it closed again".
//
// Two halves:
// - install(): a default uncaught-exception handler records the trace
//   to a file, then chains to the system handler (Android ends the
//   process either way). The record survives and is readable in
//   Settings > About > Crash report.
// - handler(): a CoroutineExceptionHandler for the app's own scopes
//   (player, read aloud): records the failure instead of letting it
//   reach the default handler, so background bookkeeping can no longer
//   crash the app mid-listen.
object CrashLog {
    private const val MAX_CHARS = 60_000

    fun file(ctx: Context): File = File(ctx.applicationContext.filesDir, "crash-log.txt")

    fun record(ctx: Context, t: Throwable) {
        try {
            val sw = StringWriter(); t.printStackTrace(PrintWriter(sw))
            val f = file(ctx)
            val prior = if (f.exists()) f.readText() else ""
            var text = prior + "\n==== ${java.util.Date()} ====\n$sw"
            if (text.length > MAX_CHARS) text = text.takeLast(MAX_CHARS)
            f.writeText(text)
        } catch (_: Exception) {}
    }

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(app, error)
            previous?.uncaughtException(thread, error)
        }
    }

    fun handler(ctx: Context): CoroutineExceptionHandler {
        val app = ctx.applicationContext
        return CoroutineExceptionHandler { _, e -> if (e !is CancellationException) record(app, e) }
    }

    fun read(ctx: Context): String = try { file(ctx).takeIf { it.exists() }?.readText()?.trim().orEmpty() } catch (_: Exception) { "" }

    fun clear(ctx: Context) { try { file(ctx).delete() } catch (_: Exception) {} }
}

class BlindRSSApp : android.app.Application() {
    override fun onCreate() { super.onCreate(); CrashLog.install(this) }
}
