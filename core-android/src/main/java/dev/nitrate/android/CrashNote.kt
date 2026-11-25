package dev.nitrate.android

import android.content.Context
import java.io.File

/**
 * When the app closes on an error nothing caught, what went wrong is written to a file on the
 * device (never sent anywhere), so Settings → Tools can show it next time ("Nitrate closed
 * unexpectedly") with a way to copy it into a bug report. The app still closes as before.
 */
object CrashNote {
    /** The file in the app's files folder (read by `AppContainer.lastCrash`). */
    const val FILE = "last-crash.txt"

    fun install(context: Context, version: String) {
        val dir = context.filesDir
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val text = buildString {
                    appendLine("Nitrate $version, Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.MODEL})")
                    appendLine("At ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date())}, thread ${thread.name}")
                    append(error.stackTraceToString())
                }
                File(dir, FILE).writeText(text.take(MAX_CHARS))
            }
            before?.uncaughtException(thread, error)
        }
    }

    private const val MAX_CHARS = 20_000
}
