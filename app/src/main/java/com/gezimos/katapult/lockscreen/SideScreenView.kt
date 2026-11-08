package com.gezimos.katapult.lockscreen

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gezimos.katapult.R
import com.gezimos.katapult.ui.LocalInk
import com.gezimos.katapult.ui.LocalSurface
import com.gezimos.katapult.ui.NoIndication
import com.gezimos.katapult.ui.settings.*

/** Activity-owned presentation of the same side surface, with ordinary PendingIntent delivery. */
@Composable
internal fun HomeSideScreen(screen: SideScreen, edge: ScreenEdge, back: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val currentBack = rememberUpdatedState(back)
    BackHandler { currentBack.value() }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) currentBack.value()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
        val slop = EdgeSwipe.SLOP_DP * context.resources.displayMetrics.density
        SideScreenView(context, screen, { currentBack.value() },
            { dx, dy -> EdgeSwipe.capturesPageClose(edge, dx, dy, slop) },
            { dx, dy -> EdgeSwipe.closesPage(edge, dx, dy,
                context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds.width(), slop) },
            {}, {}).apply { post { if (isAttachedToWindow) { requestFocus(); settled() } } }
    }, onRelease = { it.dispose() })
}

internal fun sideScreenName(context: Context, screen: SideScreen): String = screen.name.ifBlank {
    screen.slots.firstOrNull()?.let { AppWidgetManager.getInstance(context).getAppWidgetInfo(it)?.loadLabel(context.packageManager) }
        ?: context.getString(R.string.side_screen)
}

/** Kotatsu owns the frame and rail; RemoteViews owns every pixel and action inside each slot. */
internal class SideScreenView(context: Context, private val screen: SideScreen, back: () -> Unit,
                               capture: (Float, Float) -> Boolean, close: (Float, Float) -> Boolean,
                               dispatch: () -> Unit, private val visible: (Boolean) -> Unit) : EdgePageView(context, back, capture, close) {
    private val host = LockscreenWidgetHost.get(context)
    private val slots = mutableListOf<HostedWidgetSlot>()
    private val paper = ComposeView(context)
    private var disposed = false
    init {
      try {
        host.listen(LockscreenWidgetHost.Surface.SIDE, true)
        val bounds = context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        val density = resources.displayMetrics.density
        screen.placements.forEach { id -> host.sideSize(context, id, (bounds.width() / density).toInt(),
            ((bounds.height() / density - 112) / screen.layout.cells).toInt()) }
        val cells = screen.slots.take(screen.layout.cells).map { id ->
            id?.let { HostedWidgetSlot(context, host, it, dispatch).also(slots::add) }
        }
        paper.setContent {
            CompositionLocalProvider(LocalInk provides androidx.compose.ui.graphics.Color.Black,
                LocalSurface provides androidx.compose.ui.graphics.Color.White,
                androidx.compose.foundation.LocalIndication provides NoIndication) {
                Column(Modifier.fillMaxSize().background(LocalSurface.current).statusBarsPadding().navigationBarsPadding()) {
                    PageHeader(sideScreenName(context, screen), trailing = {
                        // Structural spare action cell for a future screen pager.
                        Spacer(Modifier.size(48.dp))
                        HeaderAction(stringResource(R.string.side_close), back) { CloseMark() }
                    })
                    Box(Modifier.fillMaxWidth().height(2.dp).background(LocalInk.current))
                    cells.forEachIndexed { index, slot ->
                        if (index > 0) Box(Modifier.fillMaxWidth().height(2.dp).background(LocalInk.current))
                        Row(Modifier.fillMaxWidth().weight(1f)) {
                            if (slot == null) Box(Modifier.weight(1f).padding(16.dp)) { SheetText(stringResource(R.string.side_empty)) }
                            else {
                                AndroidView(factory = { slot }, modifier = Modifier.weight(1f).fillMaxHeight())
                                slot.rail?.let { rail -> NotesScrollRail(rail.viewport, rail.maximum, rail.position, slot::moveTo, slot::step) }
                            }
                        }
                    }
                }
            }
        }
        addView(paper, LayoutParams(-1, -1))
      } catch (error: RuntimeException) {
        dispose()
        throw error
      }
    }
    override fun settled() {
        host.showSurface(context, LockscreenWidgetHost.Surface.SIDE, screen.placements.map { LockscreenWidget(it, LockscreenWidgetSize.L) })
        visible(true)
    }
    override fun dispose() {
        if (disposed) return
        disposed = true
        visible(false)
        host.showSurface(context, LockscreenWidgetHost.Surface.SIDE, emptyList())
        slots.forEach { it.close() }
        paper.disposeComposition()
        host.listen(LockscreenWidgetHost.Surface.SIDE, false)
    }
}
