package dev.elliotc.mapsvoice.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Speaks Claude's replies. Initialisation is asynchronous, so a request that
 * arrives before the engine is ready is held and spoken once it is.
 */
class TextToSpeechManager(context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var ready = false
    private var pending: Pair<String, (() -> Unit)?>? = null
    private var onCurrentUtteranceDone: (() -> Unit)? = null

    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            configure()
            pending?.let { (text, onDone) ->
                pending = null
                speak(text, onDone)
            }
        } else {
            pending?.second?.let { mainHandler.post(it) }
            pending = null
        }
    }

    private fun configure() {
        tts.language = Locale.getDefault()
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) = finish()

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) = finish()

            override fun onError(utteranceId: String?, errorCode: Int) = finish()

            private fun finish() {
                val callback = onCurrentUtteranceDone
                onCurrentUtteranceDone = null
                callback?.let { mainHandler.post(it) }
            }
        })
    }

    /** [onDone] always runs on the main thread, success or failure. */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!ready) {
            pending = text to onDone
            return
        }
        onCurrentUtteranceDone = onDone
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        if (result != TextToSpeech.SUCCESS) {
            onCurrentUtteranceDone = null
            onDone?.let { mainHandler.post(it) }
        }
    }

    fun stop() {
        pending = null
        onCurrentUtteranceDone = null
        if (ready) tts.stop()
    }

    fun release() {
        stop()
        tts.shutdown()
        ready = false
    }

    private companion object {
        const val UTTERANCE_ID = "maps-voice-reply"
    }
}
