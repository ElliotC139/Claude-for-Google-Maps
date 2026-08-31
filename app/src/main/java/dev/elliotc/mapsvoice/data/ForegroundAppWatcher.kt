package dev.elliotc.mapsvoice.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * Answers "is Google Maps on screen?" so the wake word only listens when it is
 * likely to be wanted — the recogniser is the app's whole battery cost, and
 * running it while the phone is in a pocket is pure waste.
 *
 * Uses usage-access events. Android offers no ordinary permission for this;
 * the alternatives are an accessibility service (far more invasive) or nothing.
 */
class ForegroundAppWatcher(private val context: Context) {

    private var lastKnownPackage: String? = null
    private var lastSeenTargetAt = 0L
    private var lastQueriedAt = 0L

    /**
     * True while Maps is in front, and for [graceMillis] afterwards — so
     * skipping to a podcast at a red light doesn't kill the wake word.
     */
    fun isTargetActive(
        targetPackages: Set<String> = MAPS_PACKAGES,
        graceMillis: Long = DEFAULT_GRACE_MILLIS
    ): Boolean {
        val now = System.currentTimeMillis()
        refresh(now)

        if (lastKnownPackage in targetPackages) {
            lastSeenTargetAt = now
            return true
        }
        return lastSeenTargetAt > 0 && now - lastSeenTargetAt < graceMillis
    }

    /**
     * Reads foreground changes since the last call. Events outside the window
     * are gone, so the last known package is remembered between polls rather
     * than re-derived.
     */
    private fun refresh(now: Long) {
        val since = if (lastQueriedAt == 0L) now - INITIAL_LOOKBACK_MILLIS else lastQueriedAt
        lastQueriedAt = now

        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return

        val events = try {
            manager.queryEvents(since, now)
        } catch (e: Exception) {
            return
        }

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastKnownPackage = event.packageName
            }
        }
    }

    companion object {
        /** The Maps app, and Android Auto's projected version of it. */
        val MAPS_PACKAGES = setOf(
            "com.google.android.apps.maps",
            "com.google.android.projection.gearhead"
        )

        private const val DEFAULT_GRACE_MILLIS = 3 * 60 * 1000L
        private const val INITIAL_LOOKBACK_MILLIS = 60 * 1000L

        /**
         * Usage access is granted in a system settings screen, not through a
         * runtime prompt, so it has to be checked rather than requested.
         */
        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
