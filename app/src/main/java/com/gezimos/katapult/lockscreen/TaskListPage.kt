package com.gezimos.katapult.lockscreen

import android.content.Context
import android.os.Handler
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
import com.gezimos.katapult.R
import com.gezimos.katapult.notes.NotesPort
import com.gezimos.katapult.notes.TaskListSession
import com.gezimos.katapult.notes.TaskListSnapshot
import com.gezimos.katapult.notes.TaskToggle
import com.gezimos.katapult.ui.LocalInk
import com.gezimos.katapult.ui.LocalSurface
import com.gezimos.katapult.ui.NoIndication
import com.gezimos.katapult.ui.settings.PageHeader
import com.gezimos.katapult.ui.settings.HeaderAction
import com.gezimos.katapult.ui.settings.CloseMark
import com.gezimos.katapult.ui.settings.PageFrame
import com.gezimos.katapult.ui.settings.SheetText

/** Presentation only. Children receive callbacks; no gesture or provider policy lives in this view. */
internal class TaskListView(context: Context, private val back: () -> Unit, private val toggle: (TaskToggle) -> Unit,
                             captureSwipe: (Float, Float) -> Boolean,
                             closeSwipe: (Float, Float) -> Boolean) : EdgePageView(context, back, captureSwipe, closeSwipe) {
    var release: () -> Unit = {}
    private val data = mutableStateOf<TaskListSnapshot?>(null)
    private val paper = ComposeView(context)

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
                            if (snapshot == null || items == null) Box(Modifier.padding(horizontal = 16.dp)) {
                                SheetText(stringResource(R.string.edge_task_missing, stringResource(R.string.edge_swipes)))
                            } else NotesTaskList(snapshot, toggle)
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

    override fun dispose() { release(); paper.disposeComposition() }
}

/** The built-in task list is just one consumer of the shared edge-page owners. */
internal class TaskListPage(context: Context, handler: Handler, notes: NotesPort, selected: () -> Long?,
                            eligible: () -> Boolean, changed: () -> Unit) : AutoCloseable {
    private val window = EdgePageWindow(context, handler, eligible, changed, "Kotatsu task list") { back, capture, close ->
        var session: TaskListSession? = null
        TaskListView(context, back, { session?.toggle(it) }, capture, close).also { root ->
            root.release = { session?.close() }
            session = TaskListSession(notes, selected(), root::render).also { it.start() }
        }
    }
    val opened get() = window.opened
    fun preview(from: ScreenEdge, step: Int) = window.preview(from, step)
    fun settle(from: ScreenEdge) = window.settle(from)
    fun cancelPreview() = window.cancelPreview()
    fun hide() = window.hide()
    override fun close() = window.close()
}
