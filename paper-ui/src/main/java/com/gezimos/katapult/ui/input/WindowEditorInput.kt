package com.gezimos.katapult.ui.input

import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.EditText
import com.gezimos.katapult.paper.R

/** The decor stores the binding, so distinct Compose roots in one window share one owner. */
class WindowEditorInput private constructor(private val window: Window) : AutoCloseable {
    private val decor = window.decorView
    private var closed = false
    val owner = EditorInputOwner({ action -> decor.post { action() } }, {
        if (!closed && decor.isAttachedToWindow && decor.getTag(R.id.paper_editor_input_owner) === this)
            window.insetsController?.hide(WindowInsets.Type.ime())
    })
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) { close() }
    }

    init { decor.addOnAttachStateChangeListener(attachment) }

    fun stop() = owner.stop()
    fun resume() = owner.resume()

    /** Thin native adapter: the shared owner decides when focus/hiding must be retired. */
    fun bind(field: EditText): AutoCloseable {
        val previous = field.onFocusChangeListener
        val registration = owner.register(
            requestFocus = { if (field.isAttachedToWindow && field.isShown) field.requestFocus() },
            clearFocus = {
                if (field.isFocused) {
                    // A native root otherwise searches for a replacement and can select this
                    // same EditText. Park focus on its window, not a departed editor.
                    val root = decor as ViewGroup
                    val descendants = root.descendantFocusability
                    root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    decor.isFocusableInTouchMode = true
                    decor.requestFocus()
                    root.descendantFocusability = descendants
                    field.clearFocus()
                }
            },
            eligible = { field.isAttachedToWindow && field.isShown },
        )
        val listener = View.OnFocusChangeListener { view, focused ->
            if (focused) owner.focused(registration) else owner.blurred(registration)
            previous?.onFocusChange(view, focused)
        }
        var disposed = false
        lateinit var attachment: View.OnAttachStateChangeListener
        fun dispose() {
            if (disposed) return
            disposed = true
            owner.unregister(registration)
            if (field.onFocusChangeListener === listener) field.onFocusChangeListener = previous
            field.removeOnAttachStateChangeListener(attachment)
        }
        attachment = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) = dispose()
        }
        field.onFocusChangeListener = listener
        field.addOnAttachStateChangeListener(attachment)
        if (field.isFocused) owner.focused(registration)
        return AutoCloseable { dispose() }
    }

    override fun close() {
        if (closed) return
        owner.close()
        closed = true
        decor.removeOnAttachStateChangeListener(attachment)
        if (decor.getTag(R.id.paper_editor_input_owner) === this) decor.setTag(R.id.paper_editor_input_owner, null)
    }

    companion object {
        fun get(window: Window): WindowEditorInput {
            val decor = window.decorView
            return (decor.getTag(R.id.paper_editor_input_owner) as? WindowEditorInput)
                ?: WindowEditorInput(window).also { decor.setTag(R.id.paper_editor_input_owner, it) }
        }

        fun from(view: View): WindowEditorInput? = view.rootView.getTag(R.id.paper_editor_input_owner) as? WindowEditorInput
    }
}
