package com.alexindigo.kotatsu.weather

import android.content.Context
import com.gezimos.katapult.ui.PaperPalette

/** Weather has no theme setting; screens and widgets share the light palette. */
internal object WeatherAppearance {
    fun palette(context: Context) = PaperPalette.Light(context)
}
