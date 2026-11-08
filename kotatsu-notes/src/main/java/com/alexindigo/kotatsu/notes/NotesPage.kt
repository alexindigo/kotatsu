package com.alexindigo.kotatsu.notes

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal data class NotesPageEntry(val label: String, val action: (() -> Unit)? = null, val selected: Boolean? = null)

/** Shared native paper page. Content and navigation belong to the caller. */
internal fun Activity.notesPage(title: String, entries: List<NotesPageEntry>, back: (() -> Unit)? = null) {
    fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    fun text(label: String, size: Float, bold: Boolean) = TextView(this).apply {
        text = label; textSize = size; setTextColor(Color.BLACK)
        typeface = resources.getFont(if (bold) R.font.lato_bold else R.font.lato_regular)
    }
    window.setBackgroundDrawable(ColorDrawable(Color.WHITE))
    window.statusBarColor = Color.WHITE
    window.navigationBarColor = Color.WHITE
    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE)
        setPadding(dp(16), dp(16), dp(16), dp(20))
    }
    val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    if (back != null) header.addView(text("<", 26f, true).apply {
        gravity = Gravity.CENTER
        contentDescription = getString(R.string.quote_back)
        background = getDrawable(R.drawable.choice_focus)
        setOnClickListener { back() }
    }, LinearLayout.LayoutParams(dp(48), dp(48)))
    header.addView(text(title, 26f, true).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    body.addView(header.apply { setPadding(0, 0, 0, dp(16)) })
    entries.forEach { entry ->
        body.addView(text(entry.label, 18f, entry.selected ?: (entry.action != null)).apply {
            // View's minimum is independent of TextView's line/pixel minimum mode.
            minimumHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
            isSelected = entry.selected == true
            if (entry.selected == true) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.choice_check, 0, 0, 0)
                compoundDrawablePadding = dp(8)
            }
            if (entry.action != null) {
                isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
                setOnClickListener { entry.action.invoke() }
                background = getDrawable(R.drawable.choice_focus)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    setContentView(ScrollView(this).apply { setBackgroundColor(Color.WHITE); addView(body) })
}
