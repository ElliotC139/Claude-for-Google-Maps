package dev.elliotc.mapsvoice.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Holds audio focus for the length of a session, so music and podcasts pause
 * while the driver is talking and while Claude is answering, then resume.
 *
 * Requested as a transient *gain* rather than "may duck": ducked audio still
 * plays underneath, and a quiet reply competing with a podcast is exactly the
 * thing this is meant to fix.
 *
 * The wake word deliberately does not hold focus — it listens for minutes at a
 * time, and pausing music for all of it would be intolerable.
 */
class AudioFocusHolder(context: Context) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private var request: AudioFocusRequest? = null
    private var onLost: (() -> Unit)? = null

    val isHeld: Boolean
        get() = request != null

    /**
     * @param onLost called when something more important takes the audio —
     *   an incoming call, say — so the session can bow out rather than talk
     *   into it.
     */
    fun acquire(onLost: () -> Unit): Boolean {
        if (isHeld) return true
        this.onLost = onLost

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusChangeListener, mainHandler)
            .build()

        val granted =
            audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        // Track it either way: a focus request can be granted later by the
        // system, and abandoning one we never held is harmless.
        request = focusRequest
        if (!granted) release()
        return granted
    }

    /** Gives the audio back; whatever was paused resumes on its own. */
    fun release() {
        val current = request ?: return
        request = null
        onLost = null
        audioManager.abandonAudioFocusRequest(current)
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val callback = onLost
                release()
                callback?.invoke()
            }
            // Ducking means something is mixing over us, not taking over.
            else -> Unit
        }
    }
}
