package dev.elliotc.mapsvoice.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Thin wrapper over [SpeechRecognizer]. The recognizer decides when the driver
 * has stopped talking, so there is nothing to hold down and nothing to release.
 *
 * Everything here must be called on the main thread — that is a hard
 * requirement of the platform API, not a preference.
 */
class SpeechListener(private val context: Context) {

    interface Callbacks {
        /** The mic is live; safe to show the listening state. */
        fun onListening()

        fun onTranscript(text: String)

        fun onSpeechError(message: String)
    }

    private var recognizer: SpeechRecognizer? = null
    private var callbacks: Callbacks? = null
    private var listening = false

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(callbacks: Callbacks) {
        if (listening) return
        if (!isAvailable) {
            callbacks.onSpeechError("No speech recognition on this device.")
            return
        }

        this.callbacks = callbacks
        val recognizer = this.recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(recognitionListener)
            this.recognizer = it
        }

        listening = true
        recognizer.startListening(recognizerIntent())
    }

    /** Stop capturing but still deliver whatever was heard so far. */
    fun stop() {
        if (!listening) return
        recognizer?.stopListening()
    }

    /** Drop the session entirely — no transcript, no callback. */
    fun cancel() {
        listening = false
        callbacks = null
        recognizer?.cancel()
    }

    fun release() {
        listening = false
        callbacks = null
        recognizer?.destroy()
        recognizer = null
    }

    private fun recognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_TIMEOUT_MILLIS
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_TIMEOUT_MILLIS
            )
        }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            callbacks?.onListening()
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val transcript = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()

            if (transcript.isEmpty()) {
                callbacks?.onSpeechError("I didn't catch that.")
            } else {
                callbacks?.onTranscript(transcript)
            }
        }

        override fun onError(error: Int) {
            listening = false
            callbacks?.onSpeechError(describe(error))
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that."
        SpeechRecognizer.ERROR_AUDIO -> "The mic isn't working."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is off."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "No network for speech recognition."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Still finishing the last one."
        else -> "Speech recognition failed."
    }

    private companion object {
        /** How long a pause ends the utterance. Honoured on a best-effort basis. */
        const val SILENCE_TIMEOUT_MILLIS = 1500L
    }
}
