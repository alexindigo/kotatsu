package com.alexindigo.kotatsu.weather

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** App-icon routing must run on every entry, even if a widget editor is still in Recents. */
class WeatherEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A separate task keeps Android's launcher task-reuse from resuming an arbitrary ID-bound
        // editor. Existing single-place edits remain in their own task; no draft is thrown away.
        startActivity(Intent(this, ConfigureActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        finish()
    }
}
