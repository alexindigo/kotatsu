package com.alexindigo.kotatsu.notes

import android.content.Context
import com.gezimos.katapult.ui.PaperPalette

/** Notes has no theme setting; screens and widgets share the light palette. */
internal object NotesAppearance {
    fun palette(context: Context) = PaperPalette.Light(context)
}
