package com.gezimos.katapult.lockscreen

import android.content.Context
import android.os.Handler
import android.view.KeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import com.gezimos.katapult.recent.AndroidRecentApps
import com.gezimos.katapult.recent.RecentApps
import com.gezimos.katapult.ui.NoIndication
import com.gezimos.katapult.ui.RecentAppsScreen
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** A non-focusable service page: the underlying Activity never leaves its task. */
internal class RecentAppsOverlay(context: Context, private val handler: Handler, private val eligible: () -> Boolean) : AutoCloseable {
    private lateinit var closeForeground: (() -> Unit) -> Unit
    fun onForegroundClose(action: (() -> Unit) -> Unit) { closeForeground = action }
    private val heldKeys = mutableSetOf<Int>()
    private var pendingToggle = false
    private var pendingShow = false
    private var worker: ExecutorService? = null
    private val model: RecentApps = RecentApps(AndroidRecentApps(context), context.packageName, ::hideWindow,
        load = { action ->
            val executor = worker ?: Executors.newSingleThreadExecutor().also { worker = it }
            executor.execute(action)
        }, deliver = { action -> handler.post(action) }, loaded = ::showLoaded,
        closeForeground = { action -> handler.post { closeForeground(action) } })
    private val page: EdgePageWindow = EdgePageWindow(context, handler, eligible, ::windowChanged, "Kotatsu Recent apps",
        focusable = false, invertOnOpen = false, swipeClose = false, fitSystemBars = true) { _, _, _ ->
        object : EdgePageView(context, model::back, { _, _ -> false }, { _, _ -> false }) {
            private val paper = ComposeView(context)
            init {
                paper.setContent {
                    CompositionLocalProvider(androidx.compose.foundation.LocalIndication provides NoIndication) {
                        RecentAppsScreen(model)
                    }
                }
                addView(paper, LayoutParams(-1, -1))
            }
            override fun dispose() = paper.disposeComposition()
        }
    }
    val visible: Boolean get() = model.visible
    private fun hideWindow() {
        worker?.shutdown(); worker = null
        // Never dispose composition or remove a window inside a filtered-key callback.
        handler.post { if (!model.visible) page.hide() }
    }
    private fun windowChanged() { if (!page.opened) model.dismiss() }
    fun toggle() {
        if (heldKeys.isNotEmpty()) { pendingToggle = !pendingToggle; return }
        if (visible) close()
        else if (eligible()) {
            model.show()
        }
    }
    /** The framework reports the key result after onKeyEvent returns, before these posted effects. */
    fun observeKey(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> heldKeys.add(event.keyCode)
            KeyEvent.ACTION_UP -> heldKeys.remove(event.keyCode)
        }
        if (heldKeys.isEmpty()) {
            if (pendingToggle) { pendingToggle = false; handler.post { toggle() } }
            if (pendingShow) { pendingShow = false; handler.post { showLoaded() } }
        }
    }
    private fun showLoaded() {
        if (!model.visible) return
        if (heldKeys.isNotEmpty()) { pendingShow = true; return }
        if (!eligible()) { close(); return }
        page.show()
        if (!page.opened) model.dismiss()
    }
    fun back() = close()
    override fun close() {
        pendingToggle = false; pendingShow = false; heldKeys.clear()
        model.dismiss()
    }
}
