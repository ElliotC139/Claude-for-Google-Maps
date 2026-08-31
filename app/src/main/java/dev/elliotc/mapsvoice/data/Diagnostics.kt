package dev.elliotc.mapsvoice.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short, human-readable trace of what the service last did.
 *
 * The bubble says almost nothing by design — colour only — which is right
 * while driving and useless when something is broken. This is the way to see
 * where a session actually stopped without a computer attached.
 */
object Diagnostics {

    private const val MAX_LINES = 60

    @Synchronized
    fun record(context: Context, event: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "$stamp  $event"
        val kept = (lines(context) + line).takeLast(MAX_LINES)
        prefs(context).edit().putString(PREF_LOG, kept.joinToString("\n")).apply()
    }

    /** Newest last, so it reads like a story. */
    fun lines(context: Context): List<String> =
        prefs(context).getString(PREF_LOG, null)
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun asText(context: Context): String = lines(context).joinToString("\n")

    fun clear(context: Context) {
        prefs(context).edit().remove(PREF_LOG).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("maps_voice", Context.MODE_PRIVATE)

    private const val PREF_LOG = "diagnostics"
}
