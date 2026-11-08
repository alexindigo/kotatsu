package com.alexindigo.kotatsu.notes

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** A fresh route each time the icon is tapped, like Weather's entry activity. */
class NotesEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, ConfigureActivity::class.java))
        finish()
    }
}
