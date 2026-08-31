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
    private var acquiredAt = 0L

    val isHeld: Boolean
        get() = request != null

    /**
     * @param onLost called only when the audio is taken away *permanently* —
     *   an incoming call, say — so the session can bow out rather than talk
     *   into it.
     *
     *   Transient loss is deliberately ignored. Android's speech recogniser
     *   requests focus itself the moment it opens the microphone, which reads
     *   as a transient loss here; treating that as "something took over" made
     *   every session cancel itself before it recorded anything.
     */
    fun acquire(onLost: () -> Unit): Boolean {
        if (isHeld) return true
        this.onLost = onLost
        acquiredAt = System.currentTimeMillis()

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
        // Only a permanent loss means someone else has really taken over.
        // Transient loss and ducking are both normal mid-session — not least
        // from our own recogniser opening the mic.
        if (change != AudioManager.AUDIOFOCUS_LOSS) return@OnAudioFocusChangeListener

        // A loss in the first moments is the session's own machinery starting
        // up, not a phone call. Ignoring it costs nothing; acting on it kills
        // the session that just began.
        if (System.currentTimeMillis() - acquiredAt < SETTLING_MILLIS) {
            return@OnAudioFocusChangeListener
        }

        val callback = onLost
        release()
        callback?.invoke()
    }

    private companion object {
        const val SETTLING_MILLIS = 2000L
    }
}
