package com.alexindigo.kotatsu.notes

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/** Emulator-only host window for end-to-end RemoteViews tests. */
class NotesHostActivity : Activity() {
    lateinit var container: FrameLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = FrameLayout(this)
        setContentView(container)
    }
}
