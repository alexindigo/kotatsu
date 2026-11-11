package com.gezimos.katapult.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.gezimos.katapult.paper.R

val LatoFamily = FontFamily(
    Font(R.font.lato_regular, FontWeight.Normal),
    Font(R.font.lato_bold, FontWeight.Bold),
)

data class PaperPalette(val ink: Color, val paper: Color, val muted: Color) {
    fun inverse() = copy(ink = paper, paper = ink)
    val inkArgb get() = ink.toArgb()
    val paperArgb get() = paper.toArgb()
    val mutedArgb get() = muted.toArgb()
    val isDark get() = paper.luminance() < ink.luminance()

    companion object {
        fun Light(context: Context) = from(context, R.color.paper_light_ink, R.color.paper_light_paper)
        fun Dark(context: Context) = from(context, R.color.paper_dark_ink, R.color.paper_dark_paper)
        private fun from(context: Context, ink: Int, paper: Int) = PaperPalette(
            Color(context.getColor(ink)), Color(context.getColor(paper)), Color(context.getColor(R.color.paper_muted)))
    }
}

val LocalPalette = staticCompositionLocalOf<PaperPalette> { error("No PaperPalette provided") }
object LocalInk { val current: Color @Composable get() = LocalPalette.current.ink }
object LocalSurface { val current: Color @Composable get() = LocalPalette.current.paper }
