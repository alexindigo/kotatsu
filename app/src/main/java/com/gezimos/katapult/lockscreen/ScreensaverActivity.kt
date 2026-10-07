package com.gezimos.katapult.lockscreen

import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.gezimos.katapult.util.EinkRefreshHelper
import com.gezimos.katapult.util.PrefsManager
import com.gezimos.katapult.util.SensitiveAccess

class ScreensaverActivity : ComponentActivity() {
    private var policySubscription: AutoCloseable? = null

    companion object {
        const val EXTRA_FROM_POWER = "from_power"

        @Volatile
        var isShowing = false
            private set

        @Volatile
        private var lastActive = 0L

        fun recentlyActive(): Boolean = SystemClock.elapsedRealtime() - lastActive < 2000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = PrefsManager(this)
        if (!prefs.screensaverEnabled) { finish(); return }
        val fromPower = intent.getBooleanExtra(EXTRA_FROM_POWER, false)
        SensitiveAccess.initialize(this)
        if (fromPower && (!prefs.screensaverOnPower || !SensitiveAccess.accessibility())) { finish(); return }
        policySubscription = prefs.subscribeChanges {
            if (!prefs.screensaverEnabled) runOnUiThread { finish() }
        }
        isShowing = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0f }
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            val light = !prefs.darkMode
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
            if (prefs.hideStatusBar) hide(WindowInsetsCompat.Type.statusBars())
            else show(WindowInsetsCompat.Type.statusBars())
        }

        setContent { ScreensaverView() }

        if (prefs.screensaverEinkRefresh && !fromPower) {
            EinkRefreshHelper.refresh(this, darkMode = prefs.darkMode)
        }

        if (!fromPower) {
            LockscreenWidgetService.lockScreen()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!PrefsManager(this).screensaverEnabled) { finish(); return }
        if (intent.getBooleanExtra(EXTRA_FROM_POWER, false) &&
            (!PrefsManager(this).screensaverOnPower || !SensitiveAccess.accessibility())) { finish(); return }
        isShowing = true
        lastActive = SystemClock.elapsedRealtime()
        LockscreenWidgetService.onScreensaverVisible(true)
    }

    override fun onStop() {
        super.onStop()
        lastActive = SystemClock.elapsedRealtime()
        isShowing = false
        LockscreenWidgetService.onScreensaverVisible(false)
        val power = getSystemService(PowerManager::class.java)
        if (power != null && !power.isInteractive) {
            finish()
        }
    }

    override fun onDestroy() {
        policySubscription?.close(); policySubscription = null
        super.onDestroy()
    }
}
