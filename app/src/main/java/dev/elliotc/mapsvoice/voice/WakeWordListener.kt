package dev.elliotc.mapsvoice.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import org.json.JSONObject
import org.vosk.LogLevel
import org.vosk.LibVosk
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * On-device wake word, so a session can start without touching the phone.
 *
 * Uses Vosk: a small offline recogniser bundled with the app. Nothing is sent
 * anywhere to detect the phrase, and it needs no account or API key.
 *
 * Recognition is free-form rather than grammar-constrained, and the transcript
 * is matched loosely against the configured phrases. That matters because
 * "claude" is not in the small model's vocabulary — it comes back as "cloud",
 * "clawed" or similar — so the phrase list carries those spellings too.
 *
 * The microphone is exclusive: this holds it while listening, so the caller
 * must [stop] before starting [SpeechListener] and start again afterwards.
 */
class WakeWordListener(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var starting = false

    private var phrases: List<String> = emptyList()
    private var onDetected: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    val isRunning: Boolean
        get() = speechService != null

    fun start(
        wakePhrases: List<String>,
        onDetected: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (isRunning || starting) return

        val normalised = wakePhrases.map(::normalise).filter { it.isNotEmpty() }
        if (normalised.isEmpty()) {
            onError("No wake phrase set.")
            return
        }

        this.phrases = normalised
        this.onDetected = onDetected
        this.onError = onError
        starting = true

        // Unpacking and loading the model takes seconds and touches disk, so
        // it must not run on the main thread.
        Thread {
            try {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val loaded = model ?: Model(unpackedModelDir().absolutePath).also { model = it }
                mainHandler.post { beginListening(loaded) }
            } catch (e: Exception) {
                mainHandler.post {
                    starting = false
                    onError(e.message ?: "Couldn't load the wake word model.")
                }
            }
        }.start()
    }

    fun stop() {
        starting = false
        speechService?.let { service ->
            service.stop()
            service.shutdown()
        }
        speechService = null
    }

    fun release() {
        stop()
        model?.close()
        model = null
    }

    private fun beginListening(model: Model) {
        starting = false
        try {
            val recognizer = Recognizer(model, SAMPLE_RATE)
            speechService = SpeechService(recognizer, SAMPLE_RATE).also {
                it.startListening(recognitionListener)
            }
        } catch (e: Exception) {
            speechService = null
            onError?.invoke(e.message ?: "The wake word couldn't start listening.")
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            // Partials are what make this feel instant — waiting for a final
            // result adds a second of silence after the phrase.
            check(hypothesis, FIELD_PARTIAL)
        }

        override fun onResult(hypothesis: String?) = check(hypothesis, FIELD_TEXT)

        override fun onFinalResult(hypothesis: String?) = check(hypothesis, FIELD_TEXT)

        override fun onError(exception: Exception?) {
            this@WakeWordListener.onError?.invoke(
                exception?.message ?: "The wake word stopped listening."
            )
            stop()
        }

        override fun onTimeout() = Unit

        private fun check(hypothesis: String?, field: String) {
            val heard = extract(hypothesis, field)
            if (heard.isEmpty() || phrases.none { heard.contains(it) }) return
            // Free the mic before the caller opens it for the question.
            stop()
            onDetected?.invoke()
        }
    }

    private fun extract(hypothesis: String?, field: String): String = try {
        normalise(JSONObject(hypothesis.orEmpty()).optString(field))
    } catch (e: Exception) {
        ""
    }

    /** Lowercase, letters and single spaces only, so punctuation can't matter. */
    private fun normalise(text: String): String =
        text.lowercase()
            .map { if (it.isLetter() || it.isDigit()) it else ' ' }
            .joinToString("")
            .trim()
            .replace(WHITESPACE, " ")

    /**
     * Copies the bundled model out of the APK on first run. Vosk needs real
     * files on disk; assets are not that.
     */
    private fun unpackedModelDir(): File {
        val target = File(context.applicationContext.filesDir, MODEL_DIR)
        val marker = File(target, UNPACKED_MARKER)
        if (marker.exists()) return target

        target.deleteRecursively()
        target.mkdirs()
        copyAsset(MODEL_ASSET, target)
        marker.writeText(MODEL_ASSET)
        return target
    }

    private fun copyAsset(assetPath: String, target: File) {
        val assets = context.applicationContext.assets
        val children = assets.list(assetPath).orEmpty()

        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }

        target.mkdirs()
        children.forEach { child ->
            copyAsset("$assetPath/$child", File(target, child))
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16000.0f
        const val MODEL_ASSET = "model-en-us"
        const val MODEL_DIR = "vosk-model"
        const val UNPACKED_MARKER = ".unpacked"
        const val FIELD_PARTIAL = "partial"
        const val FIELD_TEXT = "text"
        val WHITESPACE = Regex("\\s+")
    }
}
