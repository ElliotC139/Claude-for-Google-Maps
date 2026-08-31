package dev.elliotc.mapsvoice.overlay

import ai.picovoice.porcupine.Porcupine
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
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.elliotc.mapsvoice.MainActivity
import dev.elliotc.mapsvoice.R
import dev.elliotc.mapsvoice.claude.ApiKeyStore
import dev.elliotc.mapsvoice.claude.ClaudeClient
import dev.elliotc.mapsvoice.claude.ConversationState
import dev.elliotc.mapsvoice.data.ConversationLog
import dev.elliotc.mapsvoice.data.Settings
import dev.elliotc.mapsvoice.voice.SpeechListener
import dev.elliotc.mapsvoice.voice.TextToSpeechManager
import dev.elliotc.mapsvoice.voice.WakeWordListener
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the bubble and drives the loop:
 * long-press → listen → ask Claude → speak the reply.
 *
 * Touch vocabulary, chosen so none of it needs looking at:
 * - **long-press** starts a session
 * - **tap** cancels whatever is happening
 * - **drag** moves the bubble, and the position is remembered
 */
class OverlayService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var windowManager: WindowManager
    private lateinit var bubble: BubbleView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var speech: SpeechListener
    private lateinit var tts: TextToSpeechManager
    private lateinit var wakeWord: WakeWordListener

    private val conversation = ConversationState()
    private val claude = ClaudeClient(
        apiKey = { ApiKeyStore.get(this) },
        workspaceId = { ApiKeyStore.workspaceId(this) },
        personalContext = { Settings.personalContext(this) }
    )

    private var busy = false
    private var longPressPending: Runnable? = null
    private var requestJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        speech = SpeechListener(this)
        tts = TextToSpeechManager(this)
        wakeWord = WakeWordListener(this)

        startForegroundWithNotification()
        addBubble()
        applyWakeWord()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) {
            applyAppearance()
            applyWakeWord()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        cancelLongPress()
        scope.cancel()
        speech.release()
        tts.release()
        wakeWord.release()
        if (::bubble.isInitialized && bubble.isAttachedToWindow) {
            windowManager.removeView(bubble)
        }
        super.onDestroy()
    }

    // --- Overlay ---------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        bubble = BubbleView(this)

        val size = sizePx()
        params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val savedX = Settings.positionX(this)
        val savedY = Settings.positionY(this)
        if (savedX == Settings.UNSET || savedY == Settings.UNSET) {
            // Bottom-left by default: clear of the next-turn banner at the top
            // of Maps and of the ETA sheet's controls on the right.
            params.x = dp(BUBBLE_MARGIN_DP)
            params.y = screenHeight() - size - dp(BUBBLE_MARGIN_DP * 4)
        } else {
            params.x = savedX
            params.y = savedY
        }
        clampToScreen()

        bubble.setOnTouchListener(touchListener)
        windowManager.addView(bubble, params)
    }

    /** Re-reads size from settings and keeps the bubble on screen. */
    private fun applyAppearance() {
        if (!::bubble.isInitialized || !bubble.isAttachedToWindow) return
        val size = sizePx()
        params.width = size
        params.height = size
        clampToScreen()
        windowManager.updateViewLayout(bubble, params)
    }

    private val touchListener = object : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressFired = false

        private val slop by lazy { ViewConfiguration.get(this@OverlayService).scaledTouchSlop }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    longPressFired = false
                    scheduleLongPress { longPressFired = true }
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        // A drag was intended, not a press.
                        dragging = true
                        cancelLongPress()
                    }
                    if (dragging && !longPressFired) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        clampToScreen()
                        windowManager.updateViewLayout(bubble, params)
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress()
                    when {
                        dragging -> Settings.setPosition(
                            this@OverlayService,
                            params.x,
                            params.y
                        )
                        // A tap is the cancel gesture — but only when there is
                        // something to cancel, so a knock while parked is inert.
                        !longPressFired && busy -> cancelSession()
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun scheduleLongPress(onFired: () -> Unit) {
        cancelLongPress()
        val runnable = Runnable {
            longPressPending = null
            onFired()
            bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            beginSession()
        }
        longPressPending = runnable
        mainHandler.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun cancelLongPress() {
        longPressPending?.let { mainHandler.removeCallbacks(it) }
        longPressPending = null
    }

    private fun clampToScreen() {
        val size = params.width
        params.x = params.x.coerceIn(0, (screenWidth() - size).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screenHeight() - size).coerceAtLeast(0))
    }

    private fun sizePx() = dp(Settings.sizeDp(this).toFloat())

    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()

    private fun screenWidth() = resources.displayMetrics.widthPixels

    private fun screenHeight() = resources.displayMetrics.heightPixels

    // --- The loop --------------------------------------------------------

    private fun beginSession() {
        if (busy) return
        busy = true
        tts.stop()
        // Porcupine owns the mic while it listens; SpeechRecognizer cannot
        // open it until Porcupine lets go.
        wakeWord.stop()
        bubble.state = BubbleView.State.LISTENING
        speech.start(speechCallbacks)
    }

    /**
     * Starts or stops wake-word listening to match the current settings.
     * Called whenever the service returns to idle, and when settings change.
     */
    private fun applyWakeWord() {
        if (!::wakeWord.isInitialized) return

        val enabled = Settings.wakeWordEnabled(this) && !busy
        if (!enabled) {
            wakeWord.stop()
            return
        }

        val useCustom = Settings.useCustomKeyword(this) && Settings.hasCustomKeyword(this)
        wakeWord.start(
            accessKey = ApiKeyStore.picovoiceKey(this),
            keywordFile = if (useCustom) Settings.customKeywordFile(this) else null,
            builtInKeyword = builtInKeyword(),
            sensitivity = Settings.DEFAULT_SENSITIVITY,
            onDetected = { beginSession() },
            onError = { message ->
                // Visible without unlocking the phone, and it does not talk
                // over navigation the way a spoken error would.
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun builtInKeyword(): Porcupine.BuiltInKeyword = try {
        Porcupine.BuiltInKeyword.valueOf(Settings.builtInKeyword(this))
    } catch (e: IllegalArgumentException) {
        Porcupine.BuiltInKeyword.COMPUTER
    }

    /** Tap-to-cancel: drop the mic, the in-flight request, and the speech. */
    private fun cancelSession() {
        requestJob?.cancel()
        requestJob = null
        speech.cancel()
        tts.stop()
        bubble.state = BubbleView.State.IDLE
        busy = false
        bubble.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        applyWakeWord()
    }

    private val speechCallbacks = object : SpeechListener.Callbacks {
        override fun onListening() {
            bubble.state = BubbleView.State.LISTENING
        }

        override fun onTranscript(text: String) {
            bubble.state = BubbleView.State.THINKING
            requestJob = scope.launch {
                when (val result = claude.send(text, conversation)) {
                    is ClaudeClient.Result.Reply -> {
                        conversation.commit(text, result.text)
                        ConversationLog.append(this@OverlayService, text, result.text)
                        speakThenIdle(result.text, BubbleView.State.SPEAKING)
                    }

                    is ClaudeClient.Result.Failure ->
                        speakThenIdle(result.message, BubbleView.State.ERROR)
                }
                requestJob = null
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
            applyWakeWord()
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
        private const val BUBBLE_MARGIN_DP = 12f
        private const val ACTION_REFRESH = "dev.elliotc.mapsvoice.REFRESH"

        fun canDrawOverlay(context: Context): Boolean = AndroidSettings.canDrawOverlays(context)

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }

        /** Push a settings change to a bubble that is already on screen. */
        fun refresh(context: Context) {
            context.startService(
                Intent(context, OverlayService::class.java).setAction(ACTION_REFRESH)
            )
        }
    }
}
