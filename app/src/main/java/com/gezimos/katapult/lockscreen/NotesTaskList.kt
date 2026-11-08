package com.gezimos.katapult.lockscreen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gezimos.katapult.R
import com.gezimos.katapult.notes.TaskItem
import com.gezimos.katapult.notes.TaskListSnapshot
import com.gezimos.katapult.notes.TaskToggle
import com.gezimos.katapult.ui.LocalInk
import com.gezimos.katapult.ui.LocalSurface
import com.gezimos.katapult.ui.NoIndication
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

private val NotesTaskFont = FontFamily(Font(R.font.lato_medium, FontWeight.Medium))

@Composable
private fun Modifier.notesFocus(interactions: MutableInteractionSource): Modifier {
    val focused by interactions.collectIsFocusedAsState()
    val ink = LocalInk.current
    return drawBehind { if (focused) drawRect(ink, style = Stroke(2.dp.toPx())) }
}

/** Presentation and scroll ownership only. The consumer supplies authoritative state and commands. */
@Composable
internal fun NotesTaskList(snapshot: TaskListSnapshot, onToggle: (TaskToggle) -> Unit) {
    val items = remember(snapshot.content) { snapshot.items } ?: return
    val scroll = rememberScrollState()
    // Measure the wide content independently of the rail. A rail must never create its own
    // overflow or remain stuck after a shorter document arrives. The visible body owns scrolling.
    SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
        val probe = subcompose("measure") {
            Column(Modifier.clearAndSetSemantics {}.padding(bottom = 16.dp)) {
                items.forEach { NotesTaskRow(it, false) {} }
            }
        }.single().measure(Constraints(maxWidth = constraints.maxWidth))
        val overflow = probe.height > constraints.maxHeight
        val railWidth = if (overflow) 56.dp.roundToPx() else 0
        val body = subcompose("body") {
            Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(bottom = 16.dp)) {
                // Replacing a revision cancels its in-flight row presses rather than retargeting
                // their UP to another item. The scrolling container keeps its position.
                key(snapshot) {
                    items.forEachIndexed { index, item ->
                        NotesTaskRow(item, overflow) { onToggle(TaskToggle(snapshot, index)) }
                    }
                }
            }
        }.single().measure(Constraints.fixed((constraints.maxWidth - railWidth).coerceAtLeast(1), constraints.maxHeight))
        val rail = if (overflow) subcompose("rail") {
            NotesScrollRail(scroll)
        }.single().measure(Constraints.fixed(railWidth, constraints.maxHeight)) else null
        layout(constraints.maxWidth, constraints.maxHeight) {
            body.placeRelative(0, 0)
            rail?.placeRelative(body.width, 0)
        }
    }
}

@Composable
private fun NotesTaskRow(item: TaskItem, hasRail: Boolean, onToggle: () -> Unit) {
    val ink = LocalInk.current
    val interactions = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().notesFocus(interactions)
        .toggleable(item.checked, interactionSource = interactions, indication = NoIndication,
            role = Role.Checkbox, onValueChange = { onToggle() })
        .padding(start = 6.dp, end = if (hasRail) 0.dp else 16.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(10.dp)) { NotesTaskCheckbox(item.checked) }
        Text(item.text, fontFamily = NotesTaskFont, fontWeight = FontWeight.Medium,
            fontSize = 23.sp, lineHeight = 28.sp, color = ink,
            modifier = Modifier.weight(1f).padding(top = 10.dp, end = 8.dp))
    }
}

/** Independently drawn primitives, measured against Notes; no copied vector resource. */
@Composable
private fun NotesTaskCheckbox(checked: Boolean) {
    val ink = LocalInk.current
    val paper = LocalSurface.current
    Canvas(Modifier.size(28.dp)) {
        val unit = size.width / 24f
        if (checked) {
            drawRoundRect(ink, Offset(2 * unit, 2 * unit), Size(20 * unit, 20 * unit), CornerRadius(3 * unit))
            val check = Path().apply {
                moveTo(6.5f * unit, 13.5f * unit)
                lineTo(10.5f * unit, 16.5f * unit)
                lineTo(17.5f * unit, 7 * unit)
            }
            drawPath(check, paper, style = Stroke(2.2f * unit, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        } else {
            drawRoundRect(ink, Offset(3 * unit, 3 * unit), Size(18 * unit, 18 * unit), CornerRadius(2 * unit),
                style = Stroke(2 * unit))
        }
    }
}

@Composable
private fun NotesScrollRail(scroll: ScrollState) {
    val scope = rememberCoroutineScope()
    NotesScrollRail(scroll.viewportSize, scroll.maxValue, scroll.value, moveTo = { position ->
        scope.launch(start = CoroutineStart.UNDISPATCHED) { scroll.scrollTo(position.coerceIn(0, scroll.maxValue)) }
    })
}
