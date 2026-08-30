package dev.elliotc.mapsvoice

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.elliotc.mapsvoice.claude.ApiKeyStore
import dev.elliotc.mapsvoice.overlay.OverlayService

/**
 * First-run setup only: grant three permissions, start the bubble, then leave.
 * Everything after this happens in the overlay.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var overlayButton: Button
    private lateinit var micButton: Button
    private lateinit var notificationButton: Button
    private lateinit var serviceButton: Button
    private lateinit var saveKeyButton: Button
    private lateinit var apiKeyField: EditText
    private lateinit var apiKeyStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        overlayButton = findViewById(R.id.overlayButton)
        micButton = findViewById(R.id.micButton)
        notificationButton = findViewById(R.id.notificationButton)
        serviceButton = findViewById(R.id.serviceButton)
        saveKeyButton = findViewById(R.id.saveKeyButton)
        apiKeyField = findViewById(R.id.apiKeyField)
        apiKeyStatus = findViewById(R.id.apiKeyStatus)

        overlayButton.setOnClickListener { requestOverlayPermission() }
        micButton.setOnClickListener { requestMicPermission() }
        notificationButton.setOnClickListener { requestNotificationPermission() }
        serviceButton.setOnClickListener { toggleService() }
        saveKeyButton.setOnClickListener { saveApiKey() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasMic = isGranted(Manifest.permission.RECORD_AUDIO)
        val hasNotifications = hasNotificationPermission()
        val hasApiKey = ApiKeyStore.isPresent(this)

        markGranted(overlayButton, hasOverlay)
        markGranted(micButton, hasMic)
        markGranted(notificationButton, hasNotifications)

        apiKeyStatus.text = if (hasApiKey) {
            getString(R.string.key_saved, ApiKeyStore.masked(this))
        } else {
            getString(R.string.missing_api_key)
        }

        val running = isServiceRunning()
        serviceButton.setText(if (running) R.string.stop_bubble else R.string.start_bubble)
        serviceButton.isEnabled = running || (hasOverlay && hasMic && hasApiKey)
    }

    private fun markGranted(button: Button, granted: Boolean) {
        button.setText(if (granted) R.string.granted else R.string.grant)
        button.isEnabled = !granted
    }

    private fun saveApiKey() {
        val typed = apiKeyField.text.toString().trim()
        if (typed.isEmpty()) return
        ApiKeyStore.set(this, typed)
        apiKeyField.setText("")
        apiKeyField.clearFocus()
        Toast.makeText(this, R.string.save_key, Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun toggleService() {
        if (isServiceRunning()) {
            OverlayService.stop(this)
        } else {
            OverlayService.start(this)
        }
        // The service takes a moment to appear in the running list.
        serviceButton.postDelayed(::refresh, 400)
    }

    private fun requestOverlayPermission() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun requestMicPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_PERMISSIONS
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_PERMISSIONS
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            isGranted(Manifest.permission.POST_NOTIFICATIONS)

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        // Deprecated for general use, but still reports this app's own services.
        val manager = getSystemService(ActivityManager::class.java)
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == OverlayService::class.java.name }
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1
    }
}
