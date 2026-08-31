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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.elliotc.mapsvoice.claude.ApiKeyStore
import dev.elliotc.mapsvoice.data.ForegroundAppWatcher
import dev.elliotc.mapsvoice.data.Settings as AppSettings
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
    private lateinit var workspaceField: EditText
    private lateinit var contextField: EditText
    private lateinit var sizeSlider: SeekBar
    private lateinit var sizeLabel: TextView
    private lateinit var apiKeyStatus: TextView
    private lateinit var wakePhrasesField: EditText
    private lateinit var wakeWordSwitch: CheckBox
    private lateinit var onlyDuringMapsSwitch: CheckBox
    private lateinit var usageAccessStatus: TextView
    private lateinit var usageAccessButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        overlayButton = findViewById(R.id.overlayButton)
        micButton = findViewById(R.id.micButton)
        notificationButton = findViewById(R.id.notificationButton)
        serviceButton = findViewById(R.id.serviceButton)
        saveKeyButton = findViewById(R.id.saveKeyButton)
        apiKeyField = findViewById(R.id.apiKeyField)
        workspaceField = findViewById(R.id.workspaceField)
        contextField = findViewById(R.id.contextField)
        sizeSlider = findViewById(R.id.sizeSlider)
        sizeLabel = findViewById(R.id.sizeLabel)
        wakePhrasesField = findViewById(R.id.wakePhrasesField)
        wakeWordSwitch = findViewById(R.id.wakeWordSwitch)
        onlyDuringMapsSwitch = findViewById(R.id.onlyDuringMapsSwitch)
        usageAccessStatus = findViewById(R.id.usageAccessStatus)
        usageAccessButton = findViewById(R.id.usageAccessButton)
        apiKeyStatus = findViewById(R.id.apiKeyStatus)

        overlayButton.setOnClickListener { requestOverlayPermission() }
        micButton.setOnClickListener { requestMicPermission() }
        notificationButton.setOnClickListener { requestNotificationPermission() }
        serviceButton.setOnClickListener { toggleService() }
        saveKeyButton.setOnClickListener { saveCredentials() }
        findViewById<Button>(R.id.saveContextButton).setOnClickListener { savePersonalContext() }
        findViewById<Button>(R.id.historyButton).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        findViewById<Button>(R.id.resetPositionButton).setOnClickListener { resetBubblePosition() }
        setUpSizeSlider()
        setUpWakeWord()
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

        if (!workspaceField.hasFocus()) {
            workspaceField.setText(ApiKeyStore.workspaceId(this))
        }
        if (!contextField.hasFocus()) {
            contextField.setText(AppSettings.personalContext(this))
        }

        wakeWordSwitch.isChecked = AppSettings.wakeWordEnabled(this)
        onlyDuringMapsSwitch.isChecked = AppSettings.onlyDuringMaps(this)

        val hasUsageAccess = ForegroundAppWatcher.hasUsageAccess(this)
        usageAccessStatus.setText(
            if (hasUsageAccess) R.string.usage_access_granted else R.string.usage_access_needed
        )
        usageAccessButton.isEnabled = !hasUsageAccess

        if (!wakePhrasesField.hasFocus()) {
            wakePhrasesField.setText(AppSettings.wakePhrasesText(this))
        }

        apiKeyStatus.text = if (hasApiKey) {
            getString(R.string.key_saved, ApiKeyStore.masked(this))
        } else {
            getString(R.string.missing_api_key)
        }

        val running = isServiceRunning()
        serviceButton.setText(if (running) R.string.stop_bubble else R.string.start_bubble)
        serviceButton.isEnabled = running || (hasOverlay && hasMic && hasApiKey)
    }

    private fun setUpSizeSlider() {
        val range = AppSettings.MAX_SIZE_DP - AppSettings.MIN_SIZE_DP
        sizeSlider.max = range
        sizeSlider.progress = AppSettings.sizeDp(this) - AppSettings.MIN_SIZE_DP
        showSize(AppSettings.sizeDp(this))

        sizeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                showSize(AppSettings.MIN_SIZE_DP + progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) {
                AppSettings.setSizeDp(this@MainActivity, AppSettings.MIN_SIZE_DP + bar.progress)
                pushSettingsToBubble()
            }
        })
    }

    private fun setUpWakeWord() {
        findViewById<Button>(R.id.saveWakeWordButton).setOnClickListener { saveWakeWord() }
        usageAccessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun saveWakeWord() {
        AppSettings.setWakePhrasesText(this, wakePhrasesField.text.toString())
        AppSettings.setWakeWordEnabled(this, wakeWordSwitch.isChecked)
        AppSettings.setOnlyDuringMaps(this, onlyDuringMapsSwitch.isChecked)
        wakePhrasesField.clearFocus()
        Toast.makeText(this, R.string.context_saved, Toast.LENGTH_SHORT).show()
        pushSettingsToBubble()
        refresh()
    }

    private fun showSize(dp: Int) {
        sizeLabel.text = getString(R.string.bubble_size, dp)
    }

    private fun resetBubblePosition() {
        AppSettings.clearPosition(this)
        // The bubble reads its position when it is created, so a running one
        // has to be restarted to land back in the default corner.
        if (isServiceRunning()) {
            OverlayService.stop(this)
            serviceButton.postDelayed({ OverlayService.start(this); refresh() }, 300)
        }
    }

    private fun savePersonalContext() {
        AppSettings.setPersonalContext(this, contextField.text.toString())
        contextField.clearFocus()
        Toast.makeText(this, R.string.context_saved, Toast.LENGTH_SHORT).show()
    }

    /** Only meaningful while the bubble is up; starting it here would surprise. */
    private fun pushSettingsToBubble() {
        if (isServiceRunning()) OverlayService.refresh(this)
    }

    private fun markGranted(button: Button, granted: Boolean) {
        button.setText(if (granted) R.string.granted else R.string.grant)
        button.isEnabled = !granted
    }

    /**
     * Saves whichever fields were filled in. The workspace ID is saved on its
     * own so it can be added later, without re-typing the key.
     */
    private fun saveCredentials() {
        val key = apiKeyField.text.toString().trim()
        if (key.isNotEmpty()) {
            ApiKeyStore.set(this, key)
            apiKeyField.setText("")
        }

        val workspace = workspaceField.text.toString().trim()
        if (workspace != ApiKeyStore.workspaceId(this)) {
            ApiKeyStore.setWorkspaceId(this, workspace)
        }

        apiKeyField.clearFocus()
        workspaceField.clearFocus()
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
