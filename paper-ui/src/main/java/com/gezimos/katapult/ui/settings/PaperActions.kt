package com.gezimos.katapult.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gezimos.katapult.ui.LatoFamily
import com.gezimos.katapult.ui.LocalInk
import kotlin.math.abs

/** Name/help and the trailing action are independent targets on one measured text baseline. */
@Composable
fun PaperActionEntry(name: String, help: String, action: String, onOpen: () -> Unit,
    onAction: () -> Unit, modifier: Modifier = Modifier, onSwipeLeft: (() -> Unit)? = null) {
    val swipe by rememberUpdatedState(onSwipeLeft)
    val gesture = if (onSwipeLeft == null) Modifier else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var claimed = false
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.size != 1) break
                val change = event.changes.single()
                val delta = change.position - down.position
                if (!claimed && (change.isConsumed ||
                        (abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) > abs(delta.x)))) break
                if (change.pressed && abs(delta.x) > viewConfiguration.touchSlop && abs(delta.x) > abs(delta.y)) claimed = true
                // Leave taps and sub-slop finger drift to the row/action click owner.
                if (claimed) change.consume()
                if (!change.pressed) {
                    // Measure from DOWN, not from the end of touch slop. No intermediate redraws.
                    if (claimed && delta.x <= -size.width / 3f) swipe?.invoke()
                    break
                }
            }
        }
    }
    IndexTracks(modifier = modifier.then(gesture), title = {
        Column(Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).clearAndSetSemantics {}) {
            IndexName(name)
            Text(help, fontFamily = LatoFamily, fontSize = 14.sp, lineHeight = 18.sp, color = LocalInk.current)
        }
    }, value = {
        Box(Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).inkFocus()
            .clickable(role = Role.Button, onClick = onAction), contentAlignment = Alignment.TopEnd) {
            Text(action, fontFamily = LatoFamily, fontSize = 18.sp, lineHeight = 21.75.sp,
                fontWeight = FontWeight.Bold, color = LocalInk.current, maxLines = 1)
        }
    }, leadingTarget = {
        Box(Modifier.inkFocus().clickable(role = Role.Button, onClick = onOpen).semantics {
            contentDescription = name
            stateDescription = help
        })
    })
}
