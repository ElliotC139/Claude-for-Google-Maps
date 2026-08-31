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
     * Comma-separated phrases, any of which starts a session. The extras are
     * not redundancy for its own sake: the offline model has no "claude" in
     * its vocabulary, so the word reliably comes back as "cloud" or "clawed",
     * and matching only the correct spelling would almost never fire.
     */
    const val DEFAULT_WAKE_PHRASES = "hey claude, hey cloud, hey clawed"

    fun wakeWordEnabled(context: Context): Boolean =
        prefs(context).getBoolean(PREF_WAKE_ENABLED, false)

    fun setWakeWordEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_WAKE_ENABLED, enabled).apply()
    }

    fun wakePhrasesText(context: Context): String =
        prefs(context).getString(PREF_WAKE_PHRASES, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_WAKE_PHRASES

    fun setWakePhrasesText(context: Context, text: String) {
        prefs(context).edit().putString(PREF_WAKE_PHRASES, text.trim()).apply()
    }

    /** Gate the wake word on Google Maps being on screen, to save battery. */
    fun onlyDuringMaps(context: Context): Boolean =
        prefs(context).getBoolean(PREF_ONLY_MAPS, true)

    fun setOnlyDuringMaps(context: Context, only: Boolean) {
        prefs(context).edit().putBoolean(PREF_ONLY_MAPS, only).apply()
    }

    fun wakePhrases(context: Context): List<String> =
        wakePhrasesText(context).split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "maps_voice"
    private const val PREF_SIZE = "bubble_size_dp"
    private const val PREF_X = "bubble_x"
    private const val PREF_Y = "bubble_y"
    private const val PREF_CONTEXT = "personal_context"
    private const val PREF_WAKE_ENABLED = "wake_word_enabled"
    private const val PREF_WAKE_PHRASES = "wake_word_phrases"
    private const val PREF_ONLY_MAPS = "wake_word_only_during_maps"
}
