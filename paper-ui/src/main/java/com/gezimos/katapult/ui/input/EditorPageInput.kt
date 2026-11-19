package com.gezimos.katapult.ui.input

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints

internal val LocalEditorPageInput = staticCompositionLocalOf<EditorPageInput?> { null }

/** The page reports placement; the window owner still makes every retirement decision. */
internal class EditorPageInput(private val owner: EditorInputOwner) {
    class Marker(val editor: EditorInputOwner.Registration, val invalidate: () -> Unit) {
        var placedPass = -1L
    }
    private val markers = mutableSetOf<Marker>()
    private var pass = 0L

    fun register(marker: Marker) { markers += marker }
    fun unregister(marker: Marker) { markers -= marker }
    fun placed(marker: Marker) { marker.placedPass = pass }

    fun beginPlacement() {
        val current = ++pass
        markers.toList().forEach { marker ->
            // Force a placement report even when a visible child's coordinates did not change.
            marker.invalidate()
            owner.retireIfUnplaced(marker.editor) { marker !in markers || marker.placedPass >= current }
        }
    }
}

internal class EditorPlacementElement(val page: EditorPageInput, val editor: EditorInputOwner.Registration) :
    ModifierNodeElement<EditorPlacementNode>() {
    override fun create() = EditorPlacementNode(page, editor)
    override fun update(node: EditorPlacementNode) = node.update(page, editor)
    override fun InspectorInfo.inspectableProperties() { name = "editorPagePlacement" }
    override fun equals(other: Any?) = other is EditorPlacementElement && other.page === page && other.editor === editor
    override fun hashCode() = 31 * page.hashCode() + editor.hashCode()
}

internal class EditorPlacementNode(private var page: EditorPageInput, editor: EditorInputOwner.Registration) :
    Modifier.Node(), LayoutModifierNode {
    private var marker = EditorPageInput.Marker(editor) { if (isAttached) invalidatePlacement() }
    override fun onAttach() { page.register(marker) }
    override fun onDetach() { page.unregister(marker) }
    fun update(next: EditorPageInput, editor: EditorInputOwner.Registration) {
        if (page === next && marker.editor === editor) return
        if (isAttached) page.unregister(marker)
        page = next
        marker = EditorPageInput.Marker(editor) { if (isAttached) invalidatePlacement() }
        if (isAttached) { page.register(marker); invalidatePlacement() }
    }
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val child = measurable.measure(constraints)
        return layout(child.width, child.height) {
            page.placed(marker)
            child.placeRelative(0, 0)
        }
    }
}
