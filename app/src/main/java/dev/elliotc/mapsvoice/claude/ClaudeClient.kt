package dev.elliotc.mapsvoice.claude

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A single call to the Messages API. Raw HTTP over OkHttp rather than the
 * Anthropic Java SDK: this is an Android app, the SDK is a JVM library that
 * isn't shipped for Android, and one POST doesn't justify the dependency.
 */
class ClaudeClient(
    private val apiKey: () -> String,
    private val model: String = DEFAULT_MODEL,
    private val systemPrompt: String = DRIVING_SYSTEM_PROMPT,
    private val http: OkHttpClient = defaultHttpClient()
) {

    sealed interface Result {
        data class Reply(val text: String) : Result
        data class Failure(val message: String) : Result
    }

    suspend fun send(userText: String, conversation: ConversationState): Result =
        withContext(Dispatchers.IO) {
            val key = apiKey()
            if (key.isBlank()) {
                return@withContext Result.Failure("No API key is set. Open Maps Voice and paste one in.")
            }

            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", MAX_TOKENS)
                .put("system", systemPrompt)
                // Thinking costs seconds of silence in a conversation the driver
                // is waiting on out loud, and these are short factual answers.
                .put("thinking", JSONObject().put("type", "disabled"))
                .put("messages", conversation.messagesFor(userText))

            val request = Request.Builder()
                .url(MESSAGES_URL)
                .addHeader("x-api-key", key)
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .addHeader("content-type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            try {
                http.newCall(request).execute().use { response ->
                    val payload = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext Result.Failure(errorMessage(response.code, payload))
                    }
                    parseReply(payload)
                }
            } catch (e: Exception) {
                Result.Failure("Couldn't reach Claude: ${e.message ?: "network error"}")
            }
        }

    private fun parseReply(payload: String): Result {
        val json = try {
            JSONObject(payload)
        } catch (e: Exception) {
            return Result.Failure("Claude sent a response I couldn't read.")
        }

        // A safety classifier can decline the request: HTTP 200, no text blocks.
        if (json.optString("stop_reason") == "refusal") {
            return Result.Failure("Claude declined to answer that one.")
        }

        val content = json.optJSONArray("content")
            ?: return Result.Failure("Claude sent an empty response.")

        val text = buildString {
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") {
                    if (isNotEmpty()) append(' ')
                    append(block.optString("text"))
                }
            }
        }.trim()

        return if (text.isEmpty()) Result.Failure("Claude sent an empty response.") else Result.Reply(text)
    }

    private fun errorMessage(code: Int, payload: String): String {
        val apiMessage = try {
            JSONObject(payload).optJSONObject("error")?.optString("message")
        } catch (e: Exception) {
            null
        }
        return when {
            !apiMessage.isNullOrBlank() -> apiMessage
            code == 401 -> "The API key was rejected."
            code == 429 -> "Rate limited — try again in a moment."
            else -> "Claude returned an error ($code)."
        }
    }

    companion object {
        private const val MESSAGES_URL = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"

        /**
         * Sonnet: fast enough to feel like conversation, smart enough for real
         * back-and-forth. Swap for `claude-opus-5` if latency stops mattering.
         */
        const val DEFAULT_MODEL = "claude-sonnet-5"

        /** Short replies only — a spoken paragraph is unsafe while driving. */
        private const val MAX_TOKENS = 300

        val DRIVING_SYSTEM_PROMPT = """
            You are a voice assistant for someone who is driving. Your replies are
            spoken aloud, never read.

            Answer in one or two sentences unless the driver explicitly asks for
            more detail. Lead with the answer; skip preamble, caveats, and
            pleasantries. Never ask a follow-up question that would need a typed
            reply — if something is ambiguous, pick the most likely reading and
            answer it. Write plain spoken prose: no lists, no markdown, no code,
            no internal or system XML tags. If you don't know, say so in a few
            words. Never give turn-by-turn directions — Google Maps is already
            handling navigation.
        """.trimIndent()

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
