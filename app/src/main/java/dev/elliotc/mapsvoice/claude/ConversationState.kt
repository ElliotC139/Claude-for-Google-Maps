package dev.elliotc.mapsvoice.claude

import org.json.JSONArray
import org.json.JSONObject

/**
 * A short rolling history so follow-up questions make sense ("what about the
 * one after that?"). Held in memory only — it dies with the service, and it
 * also expires on its own after a stretch of silence so a new trip doesn't
 * inherit yesterday's context.
 */
class ConversationState(
    private val maxTurns: Int = DEFAULT_MAX_TURNS,
    private val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private data class Turn(val role: String, val text: String)

    private val turns = ArrayDeque<Turn>()
    private var lastActivityAt: Long = 0L

    /**
     * The `messages` array for a request that ends with [userText], dropping
     * history that has gone stale. Nothing is committed yet — call [commit]
     * only once the reply actually arrives, so a failed call doesn't leave a
     * dangling user turn in the history.
     */
    @Synchronized
    fun messagesFor(userText: String): JSONArray {
        expireIfIdle()
        val array = JSONArray()
        turns.forEach { array.put(messageObject(it.role, it.text)) }
        array.put(messageObject(ROLE_USER, userText))
        return array
    }

    @Synchronized
    fun commit(userText: String, assistantText: String) {
        turns.addLast(Turn(ROLE_USER, userText))
        turns.addLast(Turn(ROLE_ASSISTANT, assistantText))
        trim()
        lastActivityAt = clock()
    }

    @Synchronized
    fun clear() {
        turns.clear()
        lastActivityAt = 0L
    }

    private fun expireIfIdle() {
        if (turns.isEmpty()) return
        if (clock() - lastActivityAt >= idleTimeoutMillis) turns.clear()
    }

    /** Keep the newest [maxTurns] messages, and never start on an assistant turn. */
    private fun trim() {
        while (turns.size > maxTurns) turns.removeFirst()
        while (turns.isNotEmpty() && turns.first().role != ROLE_USER) turns.removeFirst()
    }

    private fun messageObject(role: String, text: String): JSONObject =
        JSONObject().put("role", role).put("content", text)

    companion object {
        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"

        /** Six messages ≈ three exchanges — enough for follow-ups, cheap to send. */
        private const val DEFAULT_MAX_TURNS = 6
        private const val DEFAULT_IDLE_TIMEOUT_MILLIS = 10 * 60 * 1000L
    }
}
