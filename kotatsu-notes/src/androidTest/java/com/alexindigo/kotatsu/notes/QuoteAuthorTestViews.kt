package com.alexindigo.kotatsu.notes

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

internal fun authorLineViews(root: View): List<TextView> {
    val author = root.findViewById<View>(R.id.quote_author) ?: return emptyList()
    fun visit(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { visit(view.getChildAt(it)) }
        else -> emptyList()
    }
    return visit(author)
}

internal fun View.boundsIn(parent: ViewGroup): Rect = Rect().also {
    getDrawingRect(it)
    parent.offsetDescendantRectToMyCoords(this, it)
}
