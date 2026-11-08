package com.alexindigo.kotatsu.notes

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout

/** Debug-only result receiver for the real Android widget-configure activity flow. */
class SettingsConfigureHostActivity : Activity() {
    var returnedRequest: Int? = null
        private set
    var returnedResult: Int? = null
        private set
    var returnedData: Intent? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this))
    }

    @Deprecated("Legacy configure contract under test")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        returnedRequest = requestCode
        returnedResult = resultCode
        returnedData = data
    }
}
