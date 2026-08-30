package dev.elliotc.mapsvoice.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.elliotc.mapsvoice.MainActivity
import dev.elliotc.mapsvoice.R
import dev.elliotc.mapsvoice.claude.ApiKeyStore
import dev.elliotc.mapsvoice.claude.ClaudeClient
import dev.elliotc.mapsvoice.claude.ConversationState
import dev.elliotc.mapsvoice.voice.SpeechListener
import dev.elliotc.mapsvoice.voice.TextToSpeechManager
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the bubble and drives the whole loop:
 * long-press → listen → ask Claude → speak the reply.
 *
 * Phase 1 deliberately stops there. The bubble does not move, there is no wake
 * word, and it knows nothing about what Google Maps is showing.
 */
class OverlayService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var windowManager: WindowManager
    private lateinit var bubble: BubbleView
    private lateinit var speech: SpeechListener
    private lateinit var tts: TextToSpeechManager

    private val conversation = ConversationState()
    // Read on each request, so a key pasted in after the bubble started works.
    private val claude = ClaudeClient(apiKey = { ApiKeyStore.get(this) })

    private var busy = false
    private var longPressPending: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        speech = SpeechListener(this)
        tts = TextToSpeechManager(this)

        startForegroundWithNotification()
        addBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        cancelLongPress()
        scope.cancel()
        speech.release()
        tts.release()
        if (::bubble.isInitialized && bubble.isAttachedToWindow) {
            windowManager.removeView(bubble)
        }
        super.onDestroy()
    }

    // --- Overlay ---------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        bubble = BubbleView(this)

        val size = (BUBBLE_SIZE_DP * resources.displayMetrics.density).toInt()
        val margin = (BUBBLE_MARGIN_DP * resources.displayMetrics.density).toInt()

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Bottom-left: clear of the next-turn banner at the top and of the
            // ETA sheet's controls on the right.
            gravity = Gravity.BOTTOM or Gravity.START
            x = margin
            y = margin * 4
        }

        bubble.setOnTouchListener(longPressTouchListener)
        windowManager.addView(bubble, params)
    }

    private val longPressTouchListener = object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        // Lazy: a Service has no base context while its fields are initialised.
        private val slop by lazy { ViewConfiguration.get(this@OverlayService).scaledTouchSlop }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    scheduleLongPress()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop) {
                        cancelLongPress()
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress()
                    return true
                }
            }
            return false
        }
    }

    private fun scheduleLongPress() {
        cancelLongPress()
        val runnable = Runnable {
            longPressPending = null
            bubble.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            beginSession()
        }
        longPressPending = runnable
        mainHandler.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun cancelLongPress() {
        longPressPending?.let { mainHandler.removeCallbacks(it) }
        longPressPending = null
    }

    // --- The loop --------------------------------------------------------

    private fun beginSession() {
        if (busy) return
        busy = true
        tts.stop()
        bubble.state = BubbleView.State.LISTENING
        speech.start(speechCallbacks)
    }

    private val speechCallbacks = object : SpeechListener.Callbacks {
        override fun onListening() {
            bubble.state = BubbleView.State.LISTENING
        }

        override fun onTranscript(text: String) {
            bubble.state = BubbleView.State.THINKING
            scope.launch {
                when (val result = claude.send(text, conversation)) {
                    is ClaudeClient.Result.Reply -> {
                        conversation.commit(text, result.text)
                        speakThenIdle(result.text, BubbleView.State.SPEAKING)
                    }

                    is ClaudeClient.Result.Failure ->
                        speakThenIdle(result.message, BubbleView.State.ERROR)
                }
            }
        }

        override fun onSpeechError(message: String) {
            speakThenIdle(message, BubbleView.State.ERROR)
        }
    }

    private fun speakThenIdle(text: String, state: BubbleView.State) {
        bubble.state = state
        tts.speak(text) {
            bubble.state = BubbleView.State.IDLE
            busy = false
        }
    }

    // --- Foreground notification -----------------------------------------

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "maps_voice_overlay"
        private const val NOTIFICATION_ID = 1
        private const val BUBBLE_SIZE_DP = 64f
        private const val BUBBLE_MARGIN_DP = 12f

        fun canDrawOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
