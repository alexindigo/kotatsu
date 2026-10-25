package com.gezimos.katapult

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import com.gezimos.katapult.lockscreen.LockscreenWidgetService
import com.gezimos.katapult.ui.App
import com.gezimos.katapult.util.AudioWidgetHelper
import com.gezimos.katapult.util.DeviceHelper
import com.gezimos.katapult.util.EinkHelper
import com.gezimos.katapult.util.EinkRefreshHelper

class MainActivity : ComponentActivity() {

    val viewModel: MainViewModel by viewModels()

    fun addLockscreenWidget(info: android.appwidget.AppWidgetProviderInfo) {
        viewModel.lockscreenWidgets.add(info, widgetBind::launch, ::configureWidget)
    }

    fun editLockscreenWidget(id: Int) { viewModel.lockscreenWidgets.edit(id, ::configureWidget) }

    private fun configureWidget(id: Int) {
        try {
            com.gezimos.katapult.lockscreen.LockscreenWidgetHost.get(this)
                .startAppWidgetConfigureActivityForResult(this, id, 0, REQUEST_CONFIGURE_WIDGET, null)
        } catch (_: Exception) { viewModel.lockscreenWidgets.launchFailed() }
    }

    @Deprecated("AppWidgetHost configuration uses the platform result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CONFIGURE_WIDGET) viewModel.lockscreenWidgets.configured(resultCode == RESULT_OK)
    }

    companion object { private const val REQUEST_CONFIGURE_WIDGET = 0x4B54 }

    private var einkHelper: EinkHelper? = null
    private var acknowledgmentRequestId: Long? = null
    private val callAcknowledgmentPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val id = acknowledgmentRequestId
        acknowledgmentRequestId = null
        viewModel.callAcknowledgmentResult(id, granted)
    }

    private fun clearBadge(packageName: String) {
        // Keep one actual platform request bound to its original ID. The launcher
        // lives with the Activity, so dismissing a context menu cannot unregister it.
        if (acknowledgmentRequestId != null) return
        viewModel.requestBadgeClear(packageName)?.let { id ->
            acknowledgmentRequestId = id
            try { callAcknowledgmentPermission.launch(android.Manifest.permission.WRITE_CALL_LOG) }
            catch (_: IllegalStateException) {
                acknowledgmentRequestId = null
                viewModel.callAcknowledgmentResult(id, false)
            }
        }
    }

    val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.setWallpaper(this, it) }
    }

    val iconPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.saveImportedIcon(this, uri)
        else viewModel.cancelIconImport()
    }

    val configExporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let {
            val ok = try {
                val out = contentResolver.openOutputStream(it)
                if (out == null) false else out.use { stream ->
                    stream.write(viewModel.prefs.exportToJson(this).toByteArray())
                    true
                }
            } catch (_: Exception) { false }
            android.widget.Toast.makeText(this,
                if (ok) R.string.config_exported else R.string.config_export_failed,
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    val configImporter = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val text = try {
                contentResolver.openInputStream(it)?.use { stream -> com.gezimos.katapult.util.PrefsManager.readImportText(stream) }
            } catch (_: Exception) { null }
            val preview = text?.let { data -> viewModel.prefs.previewPolicyImport(data) }
            if (text != null && preview != null) {
                viewModel.pendingImportText = text
                viewModel.pendingImportPreview = preview
            } else {
                android.widget.Toast.makeText(this, R.string.config_import_failed, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    val screensaverWallpaperPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.setScreensaverWallpaper(this, it) }
    }

    // Append new launchers to preserve the existing activity-result registration keys.
    private val widgetBind = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.lockscreenWidgets.bound(result.resultCode == RESULT_OK, ::configureWidget)
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (viewModel.goBack() || DeviceHelper.isDefaultLauncher(this@MainActivity)) return
            isEnabled = false
            try {
                onBackPressedDispatcher.onBackPressed()
            } finally {
                isEnabled = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.lockscreenWidgets.restoreState(savedInstanceState)
        acknowledgmentRequestId = savedInstanceState?.takeIf { it.containsKey("call_acknowledgment_request") }
            ?.getLong("call_acknowledgment_request")
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setWindowAnimations(0)
        overridePendingTransition(0, 0)

        onBackPressedDispatcher.addCallback(this, backCallback)

        if (DeviceHelper.isMuditaKompakt()) {
            einkHelper = EinkHelper(packageName).also { helper ->
                lifecycle.addObserver(helper)
                val saved = viewModel.prefs.einkHelperMode
                if (saved != EinkHelper.MEINK_MODE_DISABLED) helper.setMeinkMode(saved)
            }
        }

        setContent {
            App(viewModel = viewModel, imagePicker = imagePicker, iconPicker = iconPicker,
                configExporter = configExporter, configImporter = configImporter,
                screensaverWallpaperPicker = screensaverWallpaperPicker, onClearBadge = ::clearBadge)
        }
    }

    fun setMeinkMode(mode: Int) {
        einkHelper?.setMeinkMode(mode)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        viewModel.lockscreenWidgets.saveState(outState)
        acknowledgmentRequestId?.let { outState.putLong("call_acknowledgment_request", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        LockscreenWidgetService.onLauncherForeground(true)
    }

    override fun onResume() {
        super.onResume()
        overridePendingTransition(0, 0)
        viewModel.startNotificationListener() // Reconcile current choices/grants before cache refresh.
        viewModel.applyStatusBar(this)
        viewModel.checkLockscreenService(this)
        viewModel.lockscreenWidgets.refresh()
        viewModel.refreshWeather()
        viewModel.startClock()
        viewModel.loadApps()
        viewModel.refreshNotifications()
        AudioWidgetHelper.getInstance(this).resetDismissalState()
        LockscreenWidgetService.onLauncherForeground(true)

        val savedMode = viewModel.prefs.einkHelperMode
        if (savedMode != EinkHelper.MEINK_MODE_DISABLED) {
            einkHelper?.setMeinkMode(savedMode)
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopClock()
        viewModel.stopNotificationListener()
    }

    override fun onStop() {
        super.onStop()
        LockscreenWidgetService.onLauncherForeground(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        overridePendingTransition(0, 0)
        viewModel.onHomeIntent()

        val savedMode = viewModel.prefs.einkHelperMode
        if (savedMode != EinkHelper.MEINK_MODE_DISABLED) {
            einkHelper?.setMeinkMode(savedMode)
        }

        if (viewModel.prefs.einkRefreshOnHome) {
            EinkRefreshHelper.refresh(this, darkMode = viewModel.prefs.darkMode)
        }
    }
}
