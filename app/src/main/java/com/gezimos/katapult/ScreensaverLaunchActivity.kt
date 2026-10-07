package com.gezimos.katapult

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.gezimos.katapult.lockscreen.ScreensaverActivity

class ScreensaverLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.gezimos.katapult.util.PrefsManager(this).screensaverEnabled) { finish(); return }
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setClass(this, ScreensaverActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK,
                ),
            )
        } catch (_: Exception) {}
        finish()
        overridePendingTransition(0, 0)
    }
}
