package dev.elliotc.mapsvoice.data

import android.content.Context

/**
 * Everything the driver can tune: where the bubble sits, how big it is, and
 * the personal context sent to Claude with every question.
 */
object Settings {

    // --- Bubble size -----------------------------------------------------

    const val MIN_SIZE_DP = 44
    const val MAX_SIZE_DP = 120
    const val DEFAULT_SIZE_DP = 64

    fun sizeDp(context: Context): Int =
        prefs(context).getInt(PREF_SIZE, DEFAULT_SIZE_DP).coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)

    fun setSizeDp(context: Context, dp: Int) {
        prefs(context).edit().putInt(PREF_SIZE, dp.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)).apply()
    }

    // --- Bubble position -------------------------------------------------

    /** [UNSET] means "no saved position" — the service picks the default corner. */
    const val UNSET = Int.MIN_VALUE

    fun positionX(context: Context): Int = prefs(context).getInt(PREF_X, UNSET)

    fun positionY(context: Context): Int = prefs(context).getInt(PREF_Y, UNSET)

    fun setPosition(context: Context, x: Int, y: Int) {
        prefs(context).edit().putInt(PREF_X, x).putInt(PREF_Y, y).apply()
    }

    fun clearPosition(context: Context) {
        prefs(context).edit().remove(PREF_X).remove(PREF_Y).apply()
    }

    // --- Personal context ------------------------------------------------

    /**
     * Free text the driver pastes in — typically an export of their Claude
     * memory. Sent as part of the system prompt on every request. Capped
     * because it rides along with every question and is paid for each time.
     */
    const val MAX_CONTEXT_CHARS = 4000

    fun personalContext(context: Context): String =
        prefs(context).getString(PREF_CONTEXT, null)?.trim().orEmpty()

    fun setPersonalContext(context: Context, text: String) {
        prefs(context).edit().putString(PREF_CONTEXT, text.trim().take(MAX_CONTEXT_CHARS)).apply()
    }

    // --- Wake word -------------------------------------------------------

    /**
     * Built-ins that won't collide with a real assistant on the phone —
     * "alexa", "hey google" and "hey siri" are deliberately left out.
     */
    val BUILT_IN_KEYWORDS = listOf(
        "COMPUTER", "JARVIS", "PICOVOICE", "BUMBLEBEE",
        "GRASSHOPPER", "BLUEBERRY", "GRAPEFRUIT", "AMERICANO", "TERMINATOR"
    )

    const val CUSTOM_KEYWORD_FILE = "wake_word.ppn"
    const val DEFAULT_SENSITIVITY = 0.6f

    fun wakeWordEnabled(context: Context): Boolean =
        prefs(context).getBoolean(PREF_WAKE_ENABLED, false)

    fun setWakeWordEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_WAKE_ENABLED, enabled).apply()
    }

    fun builtInKeyword(context: Context): String =
        prefs(context).getString(PREF_KEYWORD, null) ?: BUILT_IN_KEYWORDS.first()

    fun setBuiltInKeyword(context: Context, keyword: String) {
        prefs(context).edit().putString(PREF_KEYWORD, keyword).apply()
    }

    /** True when a custom .ppn has been imported; it takes precedence. */
    fun useCustomKeyword(context: Context): Boolean =
        prefs(context).getBoolean(PREF_USE_CUSTOM, false)

    fun setUseCustomKeyword(context: Context, use: Boolean) {
        prefs(context).edit().putBoolean(PREF_USE_CUSTOM, use).apply()
    }

    fun customKeywordFile(context: Context): java.io.File =
        java.io.File(context.applicationContext.filesDir, CUSTOM_KEYWORD_FILE)

    fun hasCustomKeyword(context: Context): Boolean = customKeywordFile(context).exists()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "maps_voice"
    private const val PREF_SIZE = "bubble_size_dp"
    private const val PREF_X = "bubble_x"
    private const val PREF_Y = "bubble_y"
    private const val PREF_CONTEXT = "personal_context"
    private const val PREF_WAKE_ENABLED = "wake_word_enabled"
    private const val PREF_KEYWORD = "wake_word_keyword"
    private const val PREF_USE_CUSTOM = "wake_word_custom"
}
