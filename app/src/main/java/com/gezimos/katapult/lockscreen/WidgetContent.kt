package com.gezimos.katapult.lockscreen

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Space

/** Inspect provider content, never the host's paper/background or accessibility labels alone. */
internal fun widgetHasContent(view: View): Boolean {
    if (view.visibility != View.VISIBLE || view.alpha == 0f) return false
    return when (view) {
        is TextView -> view.text.isNotBlank() || view.compoundDrawables.any { it != null } ||
            view is android.widget.CompoundButton && view.buttonDrawable != null
        is ImageView -> view.drawable != null
        // Detached collections need their first layout to create row views. A populated
        // adapter must remain eligible so the host can attach it and render those rows.
        is android.widget.AdapterView<*> -> view.count > 0
        is ViewGroup -> (0 until view.childCount).any { widgetHasContent(view.getChildAt(it)) }
        is Space -> false
        else -> view.background != null || !view.willNotDraw()
    }
}
