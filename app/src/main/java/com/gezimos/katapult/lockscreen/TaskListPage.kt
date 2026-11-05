package com.gezimos.katapult.lockscreen

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.gezimos.katapult.R
import com.gezimos.katapult.notes.NotesPort
import com.gezimos.katapult.notes.TaskListSession
import com.gezimos.katapult.notes.TaskListSnapshot
import com.gezimos.katapult.ui.LocalInk
import com.gezimos.katapult.ui.LocalSurface
import com.gezimos.katapult.ui.NoIndication
import com.gezimos.katapult.ui.settings.PageHeader
import com.gezimos.katapult.ui.settings.HeaderAction
import com.gezimos.katapult.ui.settings.CloseMark
import com.gezimos.katapult.ui.settings.PageFrame
import com.gezimos.katapult.ui.settings.SheetText

/** Presentation only. Children receive callbacks; no gesture or provider policy lives in this view. */
internal class TaskListView(context: Context, private val back: () -> Unit, private val toggle: (Int) -> Unit,
                             private val captureSwipe: (Float, Float) -> Boolean,
                             private val closeSwipe: (Float, Float) -> Boolean) : FrameLayout(context) {
    private val data = mutableStateOf<TaskListSnapshot?>(null)
    private val paper = ComposeView(context)
    private var x = 0f
    private var y = 0f
    private var swiping = false
    private var cancelled = false

    init {
        isFocusableInTouchMode = true
        paper.setContent {
            CompositionLocalProvider(LocalInk provides androidx.compose.ui.graphics.Color.Black,
                LocalSurface provides androidx.compose.ui.graphics.Color.White,
                androidx.compose.foundation.LocalIndication provides NoIndication) {
                // The window covers the bars; their insets remain structural spacers, as in Settings.
                Box(Modifier.fillMaxSize().background(LocalSurface.current)
                    .statusBarsPadding().navigationBarsPadding()) {
                    val snapshot = data.value
                    val items = snapshot?.items
                    val title = snapshot?.title?.ifBlank { stringResource(R.string.edge_task_untitled) }
                        ?: stringResource(R.string.edge_task_list)
                    PageFrame(title, back, heading = {
                            PageHeader(title, trailing = {
                                HeaderAction(stringResource(R.string.edge_task_close), back) { CloseMark() }
                            })
                        }) {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxWidth().height(2.dp).background(LocalInk.current))
                            if (items == null) Box(Modifier.padding(horizontal = 16.dp)) {
                                SheetText(stringResource(R.string.edge_task_missing, stringResource(R.string.edge_swipes)))
                            } else NotesTaskList(items, toggle)
                        }
                    }
                }
            }
        }
        addView(paper, LayoutParams(-1, -1))
    }

    fun render(data: TaskListSnapshot?) {
        // Mandatory even when content is unchanged: native/consumer state never implies write success.
        this.data.value = data
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) back()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** One owner observes the complete stream, even when a child still owns touch delivery. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x = event.rawX; y = event.rawY; swiping = false; cancelled = false }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> cancelled = true
        }
        val dx = event.rawX - x
        val dy = event.rawY - y
        if (!cancelled && (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) &&
            captureSwipe(dx, dy)) swiping = true
        // A child may have requested exclusive vertical scrolling earlier in this stream.
        if (swiping) super.requestDisallowInterceptTouchEvent(false)
        if (event.actionMasked == MotionEvent.ACTION_UP && swiping) {
            // If the first qualifying sample is UP, cancel the child instead of delivering a click.
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
            if (!cancelled && closeSwipe(dx, dy)) back()
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = swiping

    override fun onTouchEvent(event: MotionEvent): Boolean = true

    fun dispose() = paper.disposeComposition()
}

/** Owns only the page window and its read session. EdgeGestures owns preview timing and final-UP routing. */
internal class TaskListPage(private val context: Context, private val handler: Handler, private val notes: NotesPort,
                            private val selected: () -> Long?, private val eligible: () -> Boolean,
                            private val changed: () -> Unit) : AutoCloseable {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: TaskListView? = null
    private var params: WindowManager.LayoutParams? = null
    private var session: TaskListSession? = null
    private var removalFailed = false
    private var edge = ScreenEdge.LEFT
    private var flash: View? = null
    private val clean = Runnable { flash?.let { view?.removeView(it) }; flash = null }
    var opened = false
        private set

    private fun create(from: ScreenEdge): Boolean {
        if (view != null) return !removalFailed && edge == from
        // Inspection belongs to first attachment, never to subsequent preview frames.
        if (!eligible()) return false
        edge = from
        val root = TaskListView(context, ::hide, { session?.toggle(it) },
            { dx, dy -> EdgeSwipe.capturesPageClose(edge, dx, dy, 8 * context.resources.displayMetrics.density) },
            { dx, dy -> EdgeSwipe.closesPage(edge, dx, dy, wm.maximumWindowMetrics.bounds.width(), 8 * context.resources.displayMetrics.density) })
        root.setViewTreeLifecycleOwner(context as LifecycleOwner)
        root.setViewTreeViewModelStoreOwner(context as ViewModelStoreOwner)
        root.setViewTreeSavedStateRegistryOwner(context as SavedStateRegistryOwner)
        val p = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY; format = PixelFormat.TRANSLUCENT
            width = -1; height = -1; gravity = Gravity.TOP or Gravity.LEFT
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0); setTitle("Kotatsu task list")
        }
        root.translationX = if (from == ScreenEdge.LEFT) -wm.maximumWindowMetrics.bounds.width().toFloat() else wm.maximumWindowMetrics.bounds.width().toFloat()
        return try {
            wm.addView(root, p); view = root; params = p
            session = TaskListSession(notes, selected()) { root.render(it) }.also { it.start() }
            true
        } catch (error: RuntimeException) {
            android.util.Log.w("TaskListPage", "Could not attach page", error); hide(); false
        }
    }

    fun preview(from: ScreenEdge, step: Int) {
        if (opened) return
        if (step == 0) { hide(); return }
        if (!create(from)) return
        val remaining = wm.maximumWindowMetrics.bounds.width() * (1 - step.coerceIn(0, 6) / 6f)
        view?.translationX = if (from == ScreenEdge.LEFT) -remaining else remaining
    }

    fun settle(from: ScreenEdge) {
        if (opened) return
        if (!create(from)) return
        val root = view ?: return
        val p = params ?: return
        root.translationX = 0f
        p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        try { wm.updateViewLayout(root, p) } catch (_: RuntimeException) { hide(); return }
        opened = true; root.requestFocus()
        flashOnOpen()
        changed()
    }

    /** Clear the previous keyguard clock once on entry; scrolling never requests an inversion. */
    private fun flashOnOpen() {
        if (!opened) return
        val root = view ?: return
        handler.removeCallbacks(clean); clean.run()
        flash = View(context).apply { setBackgroundColor(Color.BLACK) }.also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        handler.postDelayed(clean, 80)
    }

    fun cancelPreview() { if (!opened) hide() }
    fun hide() {
        val hadPage = view?.visibility == View.VISIBLE
        handler.removeCallbacks(clean); clean.run()
        session?.close(); session = null
        val root = view
        root?.let {
            root.dispose()
            try { wm.removeViewImmediate(root) } catch (error: RuntimeException) { android.util.Log.w("TaskListPage", "Could not remove page", error) }
        }
        // Retain ownership after a failed detach; never attach another page generation over it.
        removalFailed = root?.isAttachedToWindow == true
        if (removalFailed) root?.visibility = View.GONE else { view = null; params = null }
        opened = false
        if (hadPage) changed()
    }

    override fun close() = hide()
}
