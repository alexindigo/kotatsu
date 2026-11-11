package com.gezimos.katapult

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import com.gezimos.katapult.ui.Appearance

/** Isolated component fixtures supply the same owner as the production app root. */
fun ComponentActivity.setPaperContent(content: @Composable () -> Unit) {
    setContent { Appearance.Provide(Appearance.Surface.APP, content) }
}
