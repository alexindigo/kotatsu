package com.gezimos.katapult.lockscreen

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.widget.FrameLayout

/** The box owns both the opaque paper and its padded content rectangle. */
internal data class LockscreenWidgetBoxGeometry(val paper: Rect, val content: Rect) {
    val bottomPadding get() = paper.bottom - content.bottom
    val sidePadding get() = content.left - paper.left

    companion object {
        fun from(window: Rect, density: Float): LockscreenWidgetBoxGeometry {
            val paper = Rect(window.left, window.top + WIDGETS_MEASURED_DATE_BOTTOM_PX, window.right, window.bottom)
            val inset = (CONTENT_INSET_DP * density).toInt()
            return LockscreenWidgetBoxGeometry(paper, Rect(paper).apply {
                left += inset; right -= inset; bottom -= inset
            })
        }

        private const val CONTENT_INSET_DP = 16
    }
}

/** Content fills the padded paper; child gravity is relative to this same content bottom. */
internal class LockscreenWidgetBox(context: Context, slot: LockscreenWidgetSlot, geometry: LockscreenWidgetBoxGeometry) : FrameLayout(context) {
    init {
        setBackgroundColor(Color.WHITE)
        setPadding(geometry.sidePadding, 0, geometry.sidePadding, geometry.bottomPadding)
        addView(slot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
}
