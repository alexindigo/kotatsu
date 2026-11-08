package com.gezimos.katapult.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.gezimos.katapult.ui.LocalInk
import com.gezimos.katapult.ui.LocalSurface
import com.gezimos.katapult.ui.LatoFamily
import com.gezimos.katapult.paper.R

private class CardGestureClaim(var held: Boolean = false)
private val LocalCardClickAllowed = staticCompositionLocalOf<() -> Boolean> { { true } }

/** One content-sized block with two structural bracket lanes, never text glyphs. */
@Composable
fun PaperCard(modifier: Modifier = Modifier, moving: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val ink = if (moving) Color.White else LocalInk.current
    val surface = if (moving) Color.Black else LocalSurface.current
    CompositionLocalProvider(LocalInk provides ink, LocalSurface provides surface) {
        // One drawing owns the full measured card bounds. Ready is a rectangle, not
        // inverted bracket strokes painted over a fill (which cut white notches into it).
        Column(modifier.fillMaxWidth().drawBehind {
            drawRect(surface)
            if (!moving) {
                val stroke = 2.dp.toPx()
                val cap = 7.5.dp.toPx()
                val path = Path().apply {
                    moveTo(cap, stroke / 2)
                    lineTo(stroke / 2, stroke / 2)
                    lineTo(stroke / 2, size.height - stroke / 2)
                    lineTo(cap, size.height - stroke / 2)
                    moveTo(size.width - cap, stroke / 2)
                    lineTo(size.width - stroke / 2, stroke / 2)
                    lineTo(size.width - stroke / 2, size.height - stroke / 2)
                    lineTo(size.width - cap, size.height - stroke / 2)
                }
                drawPath(path, ink, style = Stroke(stroke))
            }
        }.padding(horizontal = 14.dp, vertical = 6.dp), content = content)
    }
}

/** Three compact text tracks; the independent external action still owns a 48dp target.
 * Its hit box does not reserve an extra text row. All text shares measured baselines.
 */
@Composable
fun PaperCardEntry(name: String, help: String, summary: String, onOpen: (() -> Unit)?) {
    val ink = LocalInk.current
    val open = stringResource(R.string.paper_open)
    val external = stringResource(R.string.paper_external)
    val clickAllowed = LocalCardClickAllowed.current
    Layout(modifier = Modifier.fillMaxWidth().semantics { contentDescription = name }, content = {
        IndexName(name)
        IndexLeader()
        Box(Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp)
            .then(if (onOpen != null) Modifier.inkFocus().clickable(role = Role.Button, onClickLabel = external,
                onClick = { if (clickAllowed()) onOpen() }) else Modifier),
            contentAlignment = Alignment.TopEnd) {
            if (onOpen != null) Text("$open \u2197", fontFamily = LatoFamily, fontSize = 18.sp,
                lineHeight = 21.75.sp, fontWeight = FontWeight.Bold, color = ink)
        }
        Text(help, fontFamily = LatoFamily, fontSize = 14.sp, lineHeight = 18.sp, color = ink)
        Text(summary, fontFamily = LatoFamily, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, color = ink)
    }) { children, constraints ->
        val width = constraints.maxWidth
        val value = children[2].measure(Constraints(maxWidth = width / 2))
        val gap = 7.5.dp.roundToPx()
        val nameWidth = if (onOpen == null) width else (width - value.width - 2 * gap - 9.dp.roundToPx()).coerceAtLeast(1)
        val title = children[0].measure(Constraints(maxWidth = nameWidth))
        val helpLine = children[3].measure(Constraints(maxWidth = width))
        val sizeLine = children[4].measure(Constraints(maxWidth = width))
        val leader = children[1].measure(Constraints.fixed((width - title.width - value.width - 2 * gap).coerceAtLeast(0), 1.5.dp.roundToPx()))
        val height = (title.height + helpLine.height + sizeLine.height).coerceAtLeast(48.dp.roundToPx())
        layout(width, height) {
            title.placeRelative(0, 0)
            if (onOpen != null) {
                value.placeRelative(width - value.width, title[FirstBaseline] - value[FirstBaseline])
                leader.placeRelative(title.width + gap, title[FirstBaseline] - leader.height / 2)
            }
            helpLine.placeRelative(0, title.height)
            sizeLine.placeRelative(0, title.height + helpLine.height)
        }
    }
}

/** Owns the temporary order and gesture. A drag publishes its permutation only on drop.
 * The pointer owner stays still; ink changes on pickup, neighbour crossings and release.
 * Callers supply data and actions, and never inspect card internals.
 */
@Composable
fun <T> PaperCardList(items: List<T>, itemKey: (T) -> String, moveUpLabel: String, moveDownLabel: String,
    onReorder: (List<String>) -> Unit, onCardClick: ((T) -> Unit)? = null, card: @Composable (T) -> Unit) {
    val keys = items.map(itemKey)
    var preview by remember(keys) { mutableStateOf<List<String>?>(null) }
    var moving by remember(keys) { mutableStateOf<String?>(null) }
    val bounds = remember { mutableMapOf<String, Rect>() }
    val order by rememberUpdatedState(preview ?: keys)
    val commit by rememberUpdatedState(onReorder)
    val models = items.associateBy(itemKey)
    val claim = remember(keys) { CardGestureClaim() }
    CompositionLocalProvider(LocalCardClickAllowed provides { !claim.held }) {
    Column(Modifier.fillMaxWidth().pointerInput(keys) {
        // A new DOWN starts a new click opportunity. A claimed long-press remains claimed
        // through release, including when no MOVE event was needed, for both card actions.
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.pressed && !it.previousPressed }) claim.held = false
                val finished = awaitPointerEvent(PointerEventPass.Final)
                if (finished.changes.none { it.pressed }) claim.held = false
            }
        }
    }.pointerInput(keys) {
        var active: String? = null
        var pointerY = 0f
        detectDragGesturesAfterLongPress(
            onDragStart = { point ->
                active = order.firstOrNull { bounds[it]?.contains(point) == true }
                moving = active
                if (active != null) claim.held = true
                pointerY = point.y
            },
            onDragCancel = { active = null; preview = null; moving = null },
            onDragEnd = {
                if (active != null) preview?.let(commit)
                active = null
                preview = null
                moving = null
            },
            onDrag = { change, delta ->
                val id = active
                if (id != null) {
                    change.consume()
                    pointerY += delta.y
                    val current = order
                    val from = current.indexOf(id)
                    val to = when {
                        from < current.lastIndex && pointerY > (bounds[current[from + 1]]?.center?.y ?: Float.POSITIVE_INFINITY) -> from + 1
                        from > 0 && pointerY < (bounds[current[from - 1]]?.center?.y ?: Float.NEGATIVE_INFINITY) -> from - 1
                        else -> from
                    }
                    if (to != from) preview = current.toMutableList().apply { add(to, removeAt(from)) }
                }
            })
    }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        order.forEachIndexed { index, id ->
            key(id) {
                val model = models.getValue(id)
                val action = if (onCardClick == null) Modifier else Modifier.inkFocus().clickable(role = Role.Button) {
                    if (!claim.held) onCardClick(model)
                }
                PaperCard(action.onGloballyPositioned {
                    bounds[id] = Rect(it.positionInParent(), it.size.toSize())
                }.semantics {
                    customActions = buildList {
                        if (index > 0) add(CustomAccessibilityAction(moveUpLabel) {
                            commit(order.toMutableList().apply { add(index - 1, removeAt(index)) }); true
                        })
                        if (index < order.lastIndex) add(CustomAccessibilityAction(moveDownLabel) {
                            commit(order.toMutableList().apply { add(index + 1, removeAt(index)) }); true
                        })
                    }
                }, moving = moving == id) { models[id]?.let { card(it) } }
            }
        }
    }
    }
}
