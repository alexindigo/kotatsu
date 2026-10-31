package com.alexindigo.kotatsu.weather

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/** Debug-only full-frame canvas for instrumentation to present the renderer's unscaled bitmap. */
class WeatherRenderActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        setContentView(FrameLayout(this).apply { setBackgroundColor(Color.WHITE) })
    }

    fun show(bitmap: Bitmap) {
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(ImageView(context).apply { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.CENTER },
                FrameLayout.LayoutParams(bitmap.width, bitmap.height, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        })
    }
}
