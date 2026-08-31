package dev.elliotc.mapsvoice.claude

import android.content.Context
import dev.elliotc.mapsvoice.BuildConfig

/**
 * Where the Claude API key lives.
 *
 * Typed into the app on first run and kept in app-private SharedPreferences,
 * which no other app on a non-rooted phone can read. That is deliberately
 * better than the build-time key: a key baked into the APK is a plain string
 * in the compiled DEX and extractable by anyone holding the file, whereas this
 * one never leaves the device it was typed on — and it can be changed without
 * a rebuild.
 *
 * A build-time key (local.properties or a CI secret) still works as a
 * fallback, so an existing setup doesn't break.
 */
object ApiKeyStore {

    fun get(context: Context): String {
        val stored = prefs(context).getString(PREF_KEY, null)?.trim()
        return if (!stored.isNullOrEmpty()) stored else BuildConfig.CLAUDE_API_KEY.trim()
    }

    fun set(context: Context, key: String) {
        prefs(context).edit().putString(PREF_KEY, key.trim()).apply()
    }

    fun isPresent(context: Context): Boolean = get(context).isNotBlank()

    /**
     * Optional. A personal or service-account key that isn't scoped to one
     * workspace must name the workspace on every request, or the API rejects
     * it with "anthropic-workspace-id is required when authenticating with an
     * identity-linked API key". A workspace-scoped key needs nothing here.
     */
    fun workspaceId(context: Context): String =
        prefs(context).getString(PREF_WORKSPACE, null)?.trim().orEmpty()

    fun setWorkspaceId(context: Context, id: String) {
        prefs(context).edit().putString(PREF_WORKSPACE, id.trim()).apply()
    }

    /** Enough to recognise which key is saved, without showing the whole thing. */
    fun masked(context: Context): String {
        val key = get(context)
        return if (key.length <= VISIBLE_CHARS) key else "…" + key.takeLast(VISIBLE_CHARS)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "maps_voice"
    private const val PREF_KEY = "claude_api_key"
    private const val PREF_WORKSPACE = "claude_workspace_id"
    private const val VISIBLE_CHARS = 6
}
