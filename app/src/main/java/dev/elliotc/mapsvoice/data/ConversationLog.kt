package dev.elliotc.mapsvoice.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * A durable record of what was asked and answered, one JSON object per line in
 * app-private storage. Appending is cheap and crash-safe, which matters when
 * the process can be killed at any moment by the system.
 *
 * Separate from [dev.elliotc.mapsvoice.claude.ConversationState], which is the
 * short in-memory context sent to Claude. This is the archive; that is working
 * memory.
 */
object ConversationLog {

    data class Entry(val at: Long, val question: String, val answer: String)

    @Synchronized
    fun append(context: Context, question: String, answer: String) {
        val line = JSONObject()
            .put(FIELD_AT, System.currentTimeMillis())
            .put(FIELD_QUESTION, question)
            .put(FIELD_ANSWER, answer)
            .toString()

        try {
            file(context).appendText(line + "\n")
        } catch (e: Exception) {
            // A lost log line must never take down a reply the driver is
            // waiting to hear.
        }
        trimIfLarge(context)
    }

    /** Newest first, capped at [limit]. */
    @Synchronized
    fun recent(context: Context, limit: Int = 200): List<Entry> {
        val file = file(context)
        if (!file.exists()) return emptyList()

        return try {
            file.readLines()
                .asReversed()
                .asSequence()
                .mapNotNull(::parse)
                .take(limit)
                .toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
    }

    /** The whole log as plain text, for sharing out of the app. */
    fun asPlainText(context: Context): String {
        val stamp = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
        return recent(context, Int.MAX_VALUE).asReversed().joinToString("\n\n") { entry ->
            "${stamp.format(Date(entry.at))}\nYou: ${entry.question}\nClaude: ${entry.answer}"
        }
    }

    private fun parse(line: String): Entry? = try {
        val json = JSONObject(line)
        Entry(
            at = json.optLong(FIELD_AT),
            question = json.optString(FIELD_QUESTION),
            answer = json.optString(FIELD_ANSWER)
        )
    } catch (e: Exception) {
        null
    }

    /** Keep the file bounded; a year of daily driving shouldn't grow forever. */
    private fun trimIfLarge(context: Context) {
        val file = file(context)
        if (file.length() < MAX_BYTES) return
        try {
            val kept = file.readLines().takeLast(KEEP_LINES)
            file.writeText(kept.joinToString("\n", postfix = "\n"))
        } catch (e: Exception) {
            // Leave the file as it is rather than risk losing it.
        }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    private const val FILE_NAME = "conversations.jsonl"
    private const val MAX_BYTES = 1_000_000L
    private const val KEEP_LINES = 1000
    private const val FIELD_AT = "at"
    private const val FIELD_QUESTION = "q"
    private const val FIELD_ANSWER = "a"
}
