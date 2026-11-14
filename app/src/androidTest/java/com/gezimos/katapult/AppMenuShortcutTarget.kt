package com.gezimos.katapult

class AppMenuShortcutTarget : android.app.Activity() {
    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        setContentView(android.widget.TextView(this).apply { text = "Shortcut launched" })
    }
}
