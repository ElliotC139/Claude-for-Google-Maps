package dev.elliotc.mapsvoice.voice

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * On-device wake word, so a session can start without touching the phone.
 *
 * Porcupine holds the microphone for as long as it runs, and Android will not
 * hand the same mic to [SpeechRecognizer] at the same time — so the caller must
 * [stop] this before listening for a question and [start] it again afterwards.
 */
class WakeWordListener(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var manager: PorcupineManager? = null

    val isRunning: Boolean
        get() = manager != null

    /**
     * @param keywordFile a custom `.ppn` trained on the Picovoice console, or
     *   null to use [builtInKeyword].
     * @param onDetected always called on the main thread.
     */
    fun start(
        accessKey: String,
        keywordFile: File?,
        builtInKeyword: Porcupine.BuiltInKeyword,
        sensitivity: Float,
        onDetected: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (isRunning) return
        if (accessKey.isBlank()) {
            onError("No Picovoice access key.")
            return
        }

        try {
            val builder = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setSensitivity(sensitivity)
                .setErrorCallback { error ->
                    mainHandler.post { onError(describe(error)) }
                }

            if (keywordFile != null && keywordFile.exists()) {
                builder.setKeywordPath(keywordFile.absolutePath)
            } else {
                builder.setKeyword(builtInKeyword)
            }

            manager = builder.build(context.applicationContext) {
                // Fires on Porcupine's own audio thread.
                mainHandler.post(onDetected)
            }
            manager?.start()
        } catch (e: PorcupineException) {
            releaseQuietly()
            onError(describe(e))
        } catch (e: Exception) {
            releaseQuietly()
            onError(e.message ?: "Wake word failed to start.")
        }
    }

    /** Frees the microphone. Safe to call when not running. */
    fun stop() {
        releaseQuietly()
    }

    fun release() {
        releaseQuietly()
    }

    private fun releaseQuietly() {
        val current = manager ?: return
        manager = null
        try {
            current.stop()
        } catch (e: Exception) {
            // Stopping is best-effort; delete still frees the native handle.
        }
        current.delete()
    }

    private fun describe(error: PorcupineException): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("access key", ignoreCase = true) ||
                message.contains("activation", ignoreCase = true) ->
                "The Picovoice access key was rejected."
            message.contains("keyword", ignoreCase = true) ->
                "That wake word file couldn't be loaded."
            message.isNotBlank() -> message
            else -> "Wake word failed."
        }
    }
}
