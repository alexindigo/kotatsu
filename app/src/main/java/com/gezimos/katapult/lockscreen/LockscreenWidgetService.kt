package com.gezimos.katapult.lockscreen

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetHostView
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.ContextThemeWrapper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.gezimos.katapult.ui.LocalInk
import com.gezimos.katapult.ui.LocalSurface
import com.gezimos.katapult.ui.MusicWidget
import com.gezimos.katapult.ui.NoIndication
import com.gezimos.katapult.ui.LatoFamily
import com.gezimos.katapult.ui.weatherIcon
import com.gezimos.katapult.weather.WeatherSources
import com.gezimos.katapult.weather.formatWeatherTemperature
import com.gezimos.katapult.weather.api.Weather
import com.gezimos.katapult.weather.api.WeatherSource
import com.gezimos.katapult.util.AudioWidgetHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.gezimos.katapult.R
import com.gezimos.katapult.service.DirectBadgeHelper
import com.gezimos.katapult.service.NotificationListener
import com.gezimos.katapult.service.BadgeSources
import com.gezimos.katapult.util.SensitiveAccess
import com.gezimos.katapult.util.AccessibilityPolicy
import com.gezimos.katapult.util.AccessibilitySelection
import com.gezimos.katapult.util.AccessibilityConsumers
import com.gezimos.katapult.ui.EinkTintMatrix
import com.gezimos.katapult.ui.EinkTintMatrixDark
import com.gezimos.katapult.ui.ICON_CORNER_FRACTION
import com.gezimos.katapult.util.DeviceHelper
import com.gezimos.katapult.util.IconUtility
import com.gezimos.katapult.util.PrefsManager
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Experimental: shows per-app notification counts on the lock screen.
 *
 * An accessibility service may add TYPE_ACCESSIBILITY_OVERLAY windows, which the system
 * composites above the keyguard — the same mechanism zacharee/LockscreenWidgets uses.
 * Shown only while the pref is on, the keyguard is locked, the screen is interactive,
 * there is something to show, and the bouncer isn't up.
 *
 * Adjusting (like Lockscreen Widgets): long-press the widget to enter edit mode —
 * the border turns dashed and a drag handle appears at the bottom. In edit mode a
 * one-finger drag moves the widget, dragging the bottom handle resizes it (rows), and
 * a single tap (or 8s of no touches) exits. Position and row count are remembered.
 *
 * The whole feature is contained here so it can be scrapped easily. To remove it, delete:
 * this package, the <service> entry in AndroidManifest.xml, res/xml/lockscreen_widget_service.xml,
 * the Experimental section in SettingsScreen (toggle + Lockscreen Notification Apps dialog),
 * the lockscreenWidget prefs in PrefsManager, the onCountsChangedExtra hook in
 * NotificationListener, and the lockscreen_widget and section_experimental strings.
 */
class LockscreenWidgetService :
    AccessibilityService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val viewModelStoreInstance = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = viewModelStoreInstance
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private var musicOverlay: ComposeView? = null
    private var mediaScope: CoroutineScope? = null
    private val musicState = mutableStateOf<AudioWidgetHelper.MediaInfo?>(null)

    companion object {
        private const val MIN_ROWS = 1
        private const val MAX_ROWS = 8
        private const val LONG_PRESS_MS = 400L
        private const val EDIT_TIMEOUT_MS = 8000L

        @Volatile
        private var instance: LockscreenWidgetService? = null

        /**
         * Locks the device via the accessibility service, if it's running. Used by the
         * screensaver to lock the screen so Home/Back can't return to the launcher.
         * Returns false when the service isn't enabled (nothing an app can do then).
         */
        fun lockScreen(): Boolean = try {
            val self = instance
            if (self == null || !self.currentDemand(false).lockAction) false
            else self.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } catch (_: Exception) {
            false
        }

        /**
         * Called from MainActivity's onResume/onPause. While the launcher is foreground
         * (and the setting is on) a small surface-colored overlay covers the status bar
         * clock; leaving the launcher removes it.
         */
        fun onLauncherForeground(foreground: Boolean) {
            launcherForeground = foreground
            refreshClockCover()
        }

        fun onScreensaverVisible(visible: Boolean) {
            screensaverForeground = visible
            refreshClockCover()
        }

        private fun refreshClockCover() {
            instance?.let { s -> s.mainHandler.post { s.evaluate() } }
        }

        private var launcherForeground = false
        private var screensaverForeground = false

        private const val MUDITA_AUDIO_PLAYER = "com.mudita.audio.player"
        // The stock MuditaOS widget measures 332dp wide with its top edge at 220dp. dp()
        // truncates, so 332dp lands on 441px and leaves the stock widget's last column
        // showing when it sits underneath ours. 334dp covers it with a pixel to spare.
        private const val MUSIC_WIDGET_WIDTH_DP = 334f
        private const val MUSIC_WIDGET_TOP_DP = 220f
        // Same corner as the notification island (widgetBackground uses dp(14)).
        private const val MUSIC_WIDGET_CORNER_DP = 14
        private const val KEYGUARD_RECHECK_MS = 120L
        private const val KEYGUARD_BURST_MS = 2000L
        private const val KEYGUARD_IDLE_RECHECK_MS = 1000L

        /** Whether the user has enabled this service in system accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, LockscreenWidgetService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any {
                it.equals(component.flattenToString(), ignoreCase = true) ||
                    it.equals(component.flattenToShortString(), ignoreCase = true)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var clockCover: View? = null
    private var messageOverlay: View? = null
    private var weatherOverlay: ComposeView? = null
    private var widgetsOverlay: LinearLayout? = null
    private var widgetsContent: LinearLayout? = null
    private var cachedWidgets: List<LockscreenWidget> = emptyList()
    private val widgetViews = mutableMapOf<LockscreenWidget, AppWidgetHostView>()
    private val widgetHost get() = LockscreenWidgetHost.get(this)
    private var weatherSource: WeatherSource? = null
    private var weatherSubscription: AutoCloseable? = null
    private var weatherValue: Weather? = null
    private data class WeatherDisplay(val conditionId: Int, val iconInfo: String?, val temperature: String)
    private var shownWeather: WeatherDisplay? = null
    private var shownRows: List<Pair<String, Int>> = emptyList()
    private var directBadges: DirectBadgeHelper? = null

    private val badgeListener: () -> Unit = { mainHandler.post { evaluate() } }
    private lateinit var prefs: PrefsManager
    private val badgeOwner = Any()
    private val mediaOwner = Any()
    private val consumers = AccessibilityConsumers(
        acquireBadges = { SensitiveAccess.acquireBadges(badgeOwner) },
        acquireMedia = { SensitiveAccess.acquireMedia(mediaOwner) },
        subscriptions = ::syncSourceSubscriptions,
        clearLockscreen = { removeOverlay(); removeMusicOverlay(); removeMessageOverlay(); removeWeatherOverlay(); stopWidgets(); musicState.value = null },
        clearInspection = {
            mainHandler.removeCallbacks(keyguardRecheck)
            lastCovered = false
            burstUntil = 0L
        },
    )
    private var policySubscription: AutoCloseable? = null
    private var badgeListening = false

    private fun currentDemand(lockscreenActive: Boolean) = AccessibilityPolicy.demand(
        SensitiveAccess.accessibility(),
        AccessibilitySelection(prefs.lockscreenWidget, prefs.lockscreenMusicWidget, !prefs.lockscreenMessage.isNullOrBlank(),
            prefs.hideStatusBarClock && !prefs.hideStatusBar && DeviceHelper.isMuditaKompakt(),
            prefs.doubleTapAction == PrefsManager.DOUBLE_TAP_LOCK, prefs.screensaverEnabled, prefs.screensaverOnPower,
            weather = prefs.lockscreenWeather, widgets = prefs.lockscreenWidgetsEnabled),
        lockscreenActive, launcherForeground, screensaverForeground || ScreensaverActivity.isShowing,
    )

    private fun syncSourceSubscriptions(badges: Boolean, media: Boolean) {
        if (badges && !badgeListening) {
            directBadges?.addListener(badgeListener)
            NotificationListener.addListener(badgeListener)
            badgeListening = true
        } else if (!badges && badgeListening) {
            directBadges?.removeListener(badgeListener)
            NotificationListener.removeListener(badgeListener)
            badgeListening = false
        }
        if (media && mediaScope == null) {
            mediaScope = CoroutineScope(Dispatchers.Main + SupervisorJob()).also { scope ->
                scope.launch {
                    AudioWidgetHelper.getInstance(this@LockscreenWidgetService).state.collect { info ->
                        musicState.value = if (SensitiveAccess.mediaInfo()) info else null
                        evaluate()
                    }
                }
            }
        } else if (!media) {
            mediaScope?.cancel(); mediaScope = null; musicState.value = null
        }
    }

    // Edit-mode state for the current overlay
    private var editing = false
    private var islandView: View? = null
    private var rowViews: List<View> = emptyList()
    private var overflowView: TextView? = null
    private var handleView: View? = null
    private var visibleRows = 0
    private var totalCount = 0
    private val editTimeout = Runnable { exitEdit() }

    private var keyguardPossible = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    keyguardPossible = true
                    maybeStartScreensaver()
                }
                Intent.ACTION_SCREEN_ON -> {
                    keyguardPossible = true
                    readWeather()
                }
                Intent.ACTION_USER_PRESENT -> keyguardPossible = false
            }
            evaluate()
        }
    }

    private fun maybeStartScreensaver() {
        if (!::prefs.isInitialized) return
        if (!currentDemand(false).powerEntry) return
        if (ScreensaverActivity.isShowing || ScreensaverActivity.recentlyActive()) return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setClass(this, ScreensaverActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(ScreensaverActivity.EXTRA_FROM_POWER, true),
            )
        } catch (_: Exception) {}
    }

    override fun onServiceConnected() {
        instance = this
        prefs = PrefsManager(this)
        SensitiveAccess.initialize(this)
        SensitiveAccess.accessibilityConnection(true)
        policySubscription = SensitiveAccess.subscribe { mainHandler.post { evaluate() } }
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        if (DeviceHelper.isMuditaKompakt()) {
            directBadges = DirectBadgeHelper.getInstance(this)
        }
        keyguardPossible = try {
            getSystemService(KeyguardManager::class.java).isKeyguardLocked
        } catch (_: Exception) {
            true
        }
        applyClockCover(launcherForeground || screensaverForeground)
        evaluate()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!SensitiveAccess.accessibility() ||
            (!prefs.lockscreenWidget && !prefs.lockscreenMusicWidget && !prefs.lockscreenWeather &&
                !prefs.lockscreenWidgetsEnabled && prefs.lockscreenMessage.isNullOrBlank())) return
        if (!keyguardPossible) return
        burstUntil = SystemClock.elapsedRealtime() + KEYGUARD_BURST_MS
        evaluate()
    }

    override fun onInterrupt() {
        syncSourceSubscriptions(false, false)
        releaseSensitiveConsumers()
        removeOverlay(); removeMusicOverlay(); removeMessageOverlay(); removeClockCover()
        stopWidgets()
        mainHandler.removeCallbacks(keyguardRecheck)
    }

    private fun releaseSensitiveConsumers() {
        consumers.close()
        closeWeatherSource()
        musicState.value = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        SensitiveAccess.accessibilityConnection(false)
        policySubscription?.close(); policySubscription = null
        releaseSensitiveConsumers()
        syncSourceSubscriptions(false, false)
        mainHandler.removeCallbacks(keyguardRecheck)
        mediaScope?.cancel()
        mediaScope = null
        removeMusicOverlay()
        removeMessageOverlay()
        stopWidgets()
        widgetViews.clear()
        widgetsContent = null
        widgetHost.releaseViews()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStoreInstance.clear()
        NotificationListener.removeListener(badgeListener)
        directBadges?.let {
            it.removeListener(badgeListener)
        }
        directBadges = null
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        removeOverlay()
        removeClockCover()
        super.onDestroy()
    }

    /**
     * Hides the status bar clock while the launcher is foreground (Mudita Kompakt
     * only, always on): SystemUI owns the status bar, so the clock can't be removed
     * per-app — but a TYPE_ACCESSIBILITY_OVERLAY window composites above it, so a
     * white rectangle over the centered clock visually erases it. Skipped when the
     * whole status bar is hidden anyway. The launcher shows its own clock, so the
     * duplicate in the bar is just noise.
     */
    fun applyClockCover(show: Boolean) {
        if (!::prefs.isInitialized) return
        val want = show && currentDemand(false).clockCover
        if (want && clockCover != null) return
        removeClockCover()
        if (!want) return
        // The Kompakt's status bar is always white, regardless of app dark mode.
        val view = View(this).apply {
            setBackgroundColor(Color.WHITE)
        }
        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.OPAQUE
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            width = (resources.displayMetrics.widthPixels * 0.28f).toInt()
            height = statusBarHeightPx()
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 0
        }
        try {
            getSystemService(WindowManager::class.java).addView(view, params)
            clockCover = view
        } catch (_: Exception) {
            clockCover = null
        }
    }

    /**
     * The stock MuditaOS lockscreen media widget is wired to its own player, so anything else
     * playing leaves the lock screen with no transport controls. Draw Kotatsu's widget in the
     * same place for those sessions only, so we never double up with the stock one.
     */
    private fun applyMusicWidget(onLockscreen: Boolean) {
        if (!::prefs.isInitialized) return
        val info = musicState.value
        val want = SensitiveAccess.mediaInfo() && onLockscreen &&
            prefs.lockscreenMusicWidget &&
            info != null &&
            info.packageName != MUDITA_AUDIO_PLAYER
        if (!want) {
            removeMusicOverlay()
            return
        }
        if (musicOverlay != null) return

        val helper = AudioWidgetHelper.getInstance(this)
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@LockscreenWidgetService)
            setViewTreeViewModelStoreOwner(this@LockscreenWidgetService)
            setViewTreeSavedStateRegistryOwner(this@LockscreenWidgetService)
            setContent {
                val media = musicState.value ?: return@setContent
                val dark = prefs.darkMode
                CompositionLocalProvider(
                    androidx.compose.foundation.LocalIndication provides NoIndication,
                    LocalInk provides if (dark) androidx.compose.ui.graphics.Color.White
                        else androidx.compose.ui.graphics.Color.Black,
                    LocalSurface provides if (dark) androidx.compose.ui.graphics.Color.Black
                        else androidx.compose.ui.graphics.Color.White,
                ) {
                    MusicWidget(
                        info = media,
                        onOpenApp = { helper.openApp() },
                        onPrevious = { helper.skipPrevious() },
                        onPlayPause = { helper.playPause() },
                        onNext = { helper.skipNext() },
                        onStop = { helper.stop() },
                        cornerRadius = MUSIC_WIDGET_CORNER_DP.dp,
                        borderWidth = islandStrokeDp(),
                    )
                }
            }
        }
        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            width = dp(MUSIC_WIDGET_WIDTH_DP)
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(MUSIC_WIDGET_TOP_DP)
        }
        try {
            getSystemService(WindowManager::class.java).addView(view, params)
            musicOverlay = view
        } catch (_: Exception) {
            musicOverlay = null
        }
    }

    /**
     * The island strokes with dp(2.5f), and dp() truncates to whole pixels, so it lands on 3px
     * here. Compose's border() does not truncate, so a literal 2.5.dp draws wider and reads
     * heavier beside it. Convert the island's real pixel stroke back to Dp so both match.
     */
    private fun islandStrokeDp(): Dp = (dp(2.5f) / resources.displayMetrics.density).dp

    private fun removeMusicOverlay() {
        musicOverlay?.let {
            try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {}
            try { it.disposeComposition() } catch (_: Exception) {}
        }
        musicOverlay = null
    }

    private fun closeWeatherSource() {
        weatherSubscription?.close()
        weatherSubscription = null
        weatherSource = null
        weatherValue = null
        removeWeatherOverlay()
    }

    private fun syncWeatherSource() {
        val source = if (SensitiveAccess.accessibility() && prefs.lockscreenWeather)
            WeatherSources.active(this) else null
        if (source === weatherSource) return
        closeWeatherSource()
        weatherSource = source ?: return
        weatherSubscription = source.observe(this) {
            mainHandler.post {
                if (weatherSource === source && SensitiveAccess.accessibility() && prefs.lockscreenWeather) {
                    readWeather()
                    evaluate()
                }
            }
        }
        readWeather()
    }

    // Read only on activation, wake or provider changes, never in the keyguard poll.
    private fun readWeather() {
        weatherValue = if (::prefs.isInitialized && SensitiveAccess.accessibility() && prefs.lockscreenWeather)
            weatherSource?.read(this) else null
    }

    private fun applyWeather(onLockscreen: Boolean) {
        val weather = weatherValue
        if (!onLockscreen || !prefs.lockscreenWeather || weatherSource == null || weather == null) {
            removeWeatherOverlay()
            return
        }
        val rendered = WeatherDisplay(weather.conditionId, weather.iconInfo,
            formatWeatherTemperature(weather, prefs.weatherCelsius))
        if (weatherOverlay != null && shownWeather == rendered) return
        removeWeatherOverlay()
        // Black on a transparent window, whatever the theme: the lock screen stays white in dark theme.
        val ink = androidx.compose.ui.graphics.Color.Black
        val view = ComposeView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setViewTreeLifecycleOwner(this@LockscreenWidgetService)
            setViewTreeViewModelStoreOwner(this@LockscreenWidgetService)
            setViewTreeSavedStateRegistryOwner(this@LockscreenWidgetService)
            setContent {
                Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(weatherIcon(weather.conditionId, weather.iconInfo),
                        stringResource(R.string.cd_weather), Modifier.size(18.dp), tint = ink)
                    Spacer(Modifier.width(2.dp))
                    Text(rendered.temperature,
                        fontSize = 18.sp, fontFamily = LatoFamily, color = ink)
                }
            }
        }
        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = statusBarHeightPx()
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 0
            setTitle("Kotatsu lock-screen weather")
        }
        try {
            getSystemService(WindowManager::class.java).addView(view, params)
            weatherOverlay = view
            shownWeather = rendered
        } catch (_: Exception) {
            view.disposeComposition()
        }
    }

    private fun removeWeatherOverlay() {
        weatherOverlay?.let {
            try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {}
            it.disposeComposition()
        }
        weatherOverlay = null
        shownWeather = null
    }

    private fun applyWidgets(onLockscreen: Boolean) {
        val selected = LockscreenWidgetList.decode(prefs.lockscreenWidgets)
        if (!DeviceHelper.isMuditaKompakt() || !prefs.lockscreenWidgetsEnabled || selected.isEmpty()) {
            stopWidgets()
            return
        }
        val manager = AppWidgetManager.getInstance(this)
        val available = selected.mapNotNull { widget -> manager.getAppWidgetInfo(widget.id)?.let { widget to it } }
        val list = available.map { it.first }
        try {
            // Hidden views retain their RemoteViews callback and receive provider updates.
            widgetHost.listen(true)
            if (cachedWidgets != list || widgetsContent == null) {
                removeWidgetsOverlay()
                widgetsContent?.removeAllViews()
                widgetViews.keys.retainAll(list.toSet())
                val themed = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light)
                widgetsContent = LinearLayout(themed).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.BOTTOM
                    setBackgroundColor(Color.WHITE)
                    setPadding(dp(8f), 0, dp(8f), dp(16f))
                    available.forEachIndexed { index, (widget, info) ->
                        val child = widgetViews.getOrPut(widget) {
                            LockscreenWidgetHost.updateSize(this@LockscreenWidgetService, widget)
                            widgetHost.createView(themed, widget.id, info)
                        }
                        addView(child, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(widget.size.heightDp.toFloat())).apply { if (index > 0) topMargin = dp(8f) })
                    }
                }
                cachedWidgets = list
            }
            if (!onLockscreen || list.isEmpty() || lockscreenBoxLayout(list.map { it.size.heightDp },
                    LockscreenWidgetHost.availableDp(this)) == null) {
                removeWidgetsOverlay()
                return
            }
            if (widgetsOverlay != null) return
            val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            val contentHeight = list.sumOf { dp(it.size.heightDp.toFloat()) } + dp(((list.size - 1) * 8 + 16).toFloat())
            val coverHeight = bounds.height() - (WIDGETS_MEASURED_PADLOCK_TOP_PX - dp(8f))
            val params = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                format = PixelFormat.OPAQUE
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = maxOf(contentHeight, coverHeight)
                gravity = Gravity.BOTTOM
                setTitle("Kotatsu lock-screen widgets")
            }
            getSystemService(WindowManager::class.java).addView(widgetsContent, params)
            widgetsOverlay = widgetsContent
        } catch (_: Exception) { stopWidgets() }
    }

    private fun removeWidgetsOverlay() {
        widgetsOverlay?.let {
            try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {}
        }
        widgetsOverlay = null
    }

    private fun stopWidgets() {
        removeWidgetsOverlay()
        try { widgetHost.listen(false) } catch (_: Exception) {}
    }

    /**
     * Event delivery is limited to com.android.systemui, but findFocus can still return a
     * node from another package; PIN inspection checks that node's package separately.
     * Keyguard dismissal can arrive while isKeyguardLocked is still true mid-animation,
     * with no later System UI event to remove overlays. Re-check while overlays or selected
     * PIN inspection are active, so unlock and PIN occlusion are observed without new events.
     */
    private val keyguardRecheck = Runnable { evaluate() }
    private var burstUntil = 0L

    private fun scheduleKeyguardRecheck() {
        mainHandler.removeCallbacks(keyguardRecheck)
        if (!SensitiveAccess.accessibility()) return
        if (overlay == null && musicOverlay == null && messageOverlay == null && weatherOverlay == null && widgetsOverlay == null && !consumers.badgesActive &&
            !consumers.mediaActive && !consumers.inspectionActive) return
        val bursting = SystemClock.elapsedRealtime() < burstUntil
        val delay = if (bursting) KEYGUARD_RECHECK_MS else KEYGUARD_IDLE_RECHECK_MS
        mainHandler.postDelayed(keyguardRecheck, delay)
    }

    /**
     * MuditaOS prints "Touch the power button to unlock" at the bottom of its lock screen and
     * offers no way to change it, so the same trick the clock cover uses applies here: an opaque
     * overlay paints over it, this one carrying the user's own message.
     */
    private fun applyLockscreenMessage(onLockscreen: Boolean) {
        val message = prefs.lockscreenMessage
        val want = onLockscreen && widgetsOverlay == null && !message.isNullOrBlank()
        if (!want) {
            removeMessageOverlay()
            return
        }
        if (messageOverlay != null) return
        val lato = try { ResourcesCompat.getFont(this, R.font.lato) } catch (_: Exception) { null }
        val view = TextView(this).apply {
            text = message
            setBackgroundColor(if (prefs.darkMode) Color.BLACK else Color.WHITE)
            setTextColor(if (prefs.darkMode) Color.WHITE else Color.BLACK)
            typeface = Typeface.create(lato, Typeface.BOLD)
            textSize = 20f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12f), 0, dp(12f), dp(10f))
        }
        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.OPAQUE
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = dp(56f)
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 0
        }
        try {
            getSystemService(WindowManager::class.java).addView(view, params)
            messageOverlay = view
        } catch (_: Exception) {
            messageOverlay = null
        }
    }

    private fun removeMessageOverlay() {
        messageOverlay?.let {
            try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {}
        }
        messageOverlay = null
    }

    private fun removeClockCover() {
        clockCover?.let {
            try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {}
        }
        clockCover = null
    }

    private fun statusBarHeightPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id)
        else (24 * resources.displayMetrics.density).toInt()
    }

    private fun evaluate() {
        if (!::prefs.isInitialized) return
        applyClockCover(launcherForeground || screensaverForeground)
        syncWeatherSource()
        if (!SensitiveAccess.accessibility() ||
            (!prefs.lockscreenWidget && !prefs.lockscreenMusicWidget && !prefs.lockscreenWeather &&
                !prefs.lockscreenWidgetsEnabled && prefs.lockscreenMessage.isNullOrBlank())) {
            releaseSensitiveConsumers()
            syncSourceSubscriptions(false, false)
            mainHandler.removeCallbacks(keyguardRecheck)
            removeOverlay(); removeMusicOverlay(); removeMessageOverlay()
            stopWidgets()
            lastCovered = false
            burstUntil = 0L
            return
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        val power = getSystemService(PowerManager::class.java)
        val selectedActive = keyguard.isKeyguardLocked &&
            power.isInteractive &&
            !KatapultDreamService.isDreaming &&
            !ScreensaverActivity.isShowing &&
            !inCall()
        val inspection = currentDemand(selectedActive).inspectPin
        val onLockscreen = selectedActive && (!inspection || !lockscreenCovered(lastCovered || SystemClock.elapsedRealtime() < burstUntil))
        val demand = currentDemand(onLockscreen)
        // A covered lock screen suppresses overlays/collectors, not the selected
        // recheck itself; otherwise teardown would forget that it is covered.
        consumers.reconcile(demand.copy(inspectPin = inspection))
        applyMusicWidget(onLockscreen)
        applyWidgets(onLockscreen)
        applyLockscreenMessage(onLockscreen)
        applyWeather(onLockscreen)
        if (!onLockscreen || !prefs.lockscreenWidget) {
            removeOverlay()
            scheduleKeyguardRecheck()
            return
        }
        val counts = currentCounts()
        if (counts.isEmpty()) {
            removeOverlay()
            scheduleKeyguardRecheck()
            return
        }
        // Don't rebuild under the user's fingers while they're adjusting the widget.
        if (editing) {
            scheduleKeyguardRecheck()
            return
        }
        // Skip redraws when nothing changed — every rebuild flashes the e-ink panel.
        val rows = orderedRows(counts)
        if (overlay != null && rows == shownRows) {
            scheduleKeyguardRecheck()
            return
        }
        removeOverlay()
        addOverlay(rows, counts.size)
        scheduleKeyguardRecheck()
    }

    /**
     * Whether something covers the bare lock screen: the PIN pad (a focused System UI EditText) or any window
     * in front of the lock-screen window (see [AccessibilityPolicy.lockscreenCovered]); inspection failure also
     * hides overlays. Polled instead of event-driven: re-showing the PIN doesn't re-fire focus events when its
     * field kept focus.
     */
    private var lastCovered = false

    private fun lockscreenCovered(check: Boolean): Boolean {
        if (!SensitiveAccess.accessibility()) { lastCovered = false; return false }
        if (!check) return lastCovered
        lastCovered = AccessibilityPolicy.coveredOrUnknown {
            val focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val pinFocused = try {
                focus != null && AccessibilityPolicy.isPinField(focus.packageName, focus.isVisibleToUser, focus.className)
            } finally {
                @Suppress("DEPRECATION")
                focus?.recycle()
            }
            val root = windows.firstOrNull { it.isActive }?.root
            try {
                val hasPanel = root?.findAccessibilityNodeInfosByViewId(AccessibilityPolicy.LOCKSCREEN_PANEL_ID)
                    ?.any { it.isVisibleToUser } == true
                AccessibilityPolicy.lockscreenCovered(root?.packageName, hasPanel, pinFocused)
            } finally {
                @Suppress("DEPRECATION")
                root?.recycle()
            }
        }
        return lastCovered
    }

    /** An incoming or active call shows its own UI over the lock screen — hide the widget then. */
    private fun inCall(): Boolean = try {
        when (getSystemService(AudioManager::class.java).mode) {
            AudioManager.MODE_RINGTONE,
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION -> true
            else -> false
        }
    } catch (_: Exception) {
        false
    }

    private fun currentCounts(): Map<String, Int> {
        return com.gezimos.katapult.util.LockscreenNotifications.forDisplay(this, prefs, BadgeSources.counts(this))
    }

    /**
     * Rows ordered by each app's latest notification time (direction per the sort
     * setting). Apps without a known time — the Mudita call/SMS badges — sort last.
     */
    private fun orderedRows(counts: Map<String, Int>): List<Pair<String, Int>> {
        val times = NotificationListener.getLastPostTimes()
        val comparator = if (prefs.lockscreenWidgetLatestFirst) {
            compareByDescending<Map.Entry<String, Int>> { times[it.key] ?: 0L }
        } else {
            compareBy<Map.Entry<String, Int>> { times[it.key] ?: Long.MAX_VALUE }
        }
        return counts.entries
            .sortedWith(comparator.thenBy { it.key })
            .take(MAX_ROWS)
            .map { it.key to it.value }
    }

    private fun addOverlay(rows: List<Pair<String, Int>>, total: Int) {
        val view = buildWidget(rows, total) ?: return
        val savedY = prefs.lockscreenWidgetY
        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            width = resources.displayMetrics.widthPixels - dp(28f)
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = if (savedY >= 0) savedY else (resources.displayMetrics.heightPixels * 0.62f).toInt()
        }
        attachGestures(view, params)
        try {
            getSystemService(WindowManager::class.java).addView(view, params)
            overlay = view
            overlayParams = params
            shownRows = rows
        } catch (_: Exception) {
            overlay = null
            overlayParams = null
            shownRows = emptyList()
        }
    }

    private fun removeOverlay() {
        mainHandler.removeCallbacks(editTimeout)
        editing = false
        overlay?.let {
            try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {}
        }
        overlay = null
        overlayParams = null
        shownRows = emptyList()
        islandView = null
        rowViews = emptyList()
        overflowView = null
        handleView = null
    }

    // --- Edit mode (two-finger long-press) ---

    private fun widgetBackground(dashed: Boolean): GradientDrawable {
        val ink = if (prefs.darkMode) Color.WHITE else Color.BLACK
        val surface = if (prefs.darkMode) Color.BLACK else Color.WHITE
        return GradientDrawable().apply {
            setColor(surface)
            cornerRadius = dp(14f).toFloat()
            if (dashed) {
                setStroke(dp(2.5f), ink, dp(10f).toFloat(), dp(7f).toFloat())
            } else {
                setStroke(dp(2.5f), ink)
            }
        }
    }

    private fun enterEdit() {
        val view = overlay ?: return
        editing = true
        islandView?.background = widgetBackground(dashed = true)
        handleView?.visibility = View.VISIBLE
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        mainHandler.removeCallbacks(editTimeout)
        mainHandler.postDelayed(editTimeout, EDIT_TIMEOUT_MS)
    }

    private fun exitEdit() {
        if (!editing) return
        editing = false
        mainHandler.removeCallbacks(editTimeout)
        islandView?.background = widgetBackground(dashed = false)
        handleView?.visibility = View.GONE
        prefs.lockscreenWidgetRows = visibleRows.coerceIn(MIN_ROWS, MAX_ROWS)
        overlayParams?.let { prefs.lockscreenWidgetY = it.y }
    }

    /** Show the first [target] rows, hide the rest, and refresh the +N overflow line. */
    private fun applyRowVisibility(target: Int) {
        val clamped = target.coerceIn(1, rowViews.size)
        if (clamped == visibleRows) return
        visibleRows = clamped
        rowViews.forEachIndexed { i, row ->
            row.visibility = if (i < clamped) View.VISIBLE else View.GONE
        }
        val remaining = totalCount - clamped
        overflowView?.apply {
            text = "+$remaining"
            visibility = if (remaining > 0) View.VISIBLE else View.GONE
        }
    }

    private object GestureMode {
        const val NONE = 0
        const val MOVE = 1
        const val RESIZE = 2
        const val TAP = 3
    }

    private fun attachGestures(view: View, params: WindowManager.LayoutParams) {
        var startRawX = 0f
        var startRawY = 0f
        var startParamY = 0
        var startRows = 0
        var mode = GestureMode.NONE
        val slop = dp(12f).toFloat()
        val pendingEdit = Runnable {
            if (!editing) enterEdit()
            mode = GestureMode.NONE
        }

        view.setOnTouchListener { v, event ->
            if (editing) {
                mainHandler.removeCallbacks(editTimeout)
                mainHandler.postDelayed(editTimeout, EDIT_TIMEOUT_MS)
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startParamY = params.y
                    startRows = visibleRows
                    mode = if (editing) {
                        if (event.y >= v.height - dp(44f)) GestureMode.RESIZE else GestureMode.TAP
                    } else {
                        // Long-press (one finger, held still) enters edit mode.
                        mainHandler.postDelayed(pendingEdit, LONG_PRESS_MS)
                        GestureMode.NONE
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // A second finger means this isn't a long-press.
                    mainHandler.removeCallbacks(pendingEdit)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!editing) {
                        // Fingers wandered — this isn't a long-press, cancel activation.
                        if (hypot(dx, dy) > slop) mainHandler.removeCallbacks(pendingEdit)
                        return@setOnTouchListener true
                    }
                    when (mode) {
                        GestureMode.TAP -> if (hypot(dx, dy) > slop) mode = GestureMode.MOVE
                        GestureMode.RESIZE -> {
                            val target = startRows + (dy / dp(40f).toFloat()).roundToInt()
                            applyRowVisibility(target)
                        }
                    }
                    if (mode == GestureMode.MOVE) {
                        val maxY = (resources.displayMetrics.heightPixels - v.height).coerceAtLeast(0)
                        val newY = (startParamY + dy).toInt().coerceIn(0, maxY)
                        // Move in 4dp steps so the e-ink panel isn't redrawn for every pixel.
                        if (abs(newY - params.y) >= dp(4f)) {
                            params.y = newY
                            try {
                                getSystemService(WindowManager::class.java).updateViewLayout(v, params)
                            } catch (_: Exception) {}
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                    mainHandler.removeCallbacks(pendingEdit)
                    if (editing && event.actionMasked == MotionEvent.ACTION_UP) {
                        when (mode) {
                            GestureMode.TAP -> exitEdit()
                            GestureMode.MOVE -> prefs.lockscreenWidgetY = params.y
                            GestureMode.RESIZE -> prefs.lockscreenWidgetRows = visibleRows.coerceIn(MIN_ROWS, MAX_ROWS)
                        }
                    }
                    if (event.actionMasked != MotionEvent.ACTION_POINTER_UP) mode = GestureMode.NONE
                    true
                }
                else -> false
            }
        }
    }

    // --- Widget view ---

    private fun buildWidget(rows: List<Pair<String, Int>>, total: Int): View? {
        val ink = if (prefs.darkMode) Color.WHITE else Color.BLACK
        val lato = try { ResourcesCompat.getFont(this, R.font.lato) } catch (_: Exception) { null }

        // Transparent column: the island box on top, the resize cue below it (outside the box).
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val island = FrameLayout(this).apply {
            background = widgetBackground(dashed = false)
            // Rows carry 4dp of their own vertical padding, so 16dp horizontal here
            // matches the effective 12+4dp vertical inset.
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Build up to MAX_ROWS rows so edit mode can grow the widget without rebuilding;
        // rows beyond the configured count start out hidden.
        val iconSize = dp(32f)
        val builtRows = mutableListOf<View>()
        for ((pkg, count) in rows) {
            val label = appLabel(pkg) ?: continue
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4f), 0, dp(4f))
            }
            iconBitmap(pkg, iconSize, ink)?.let { bmp ->
                row.addView(ImageView(this).apply {
                    setImageBitmap(bmp)
                }, LinearLayout.LayoutParams(iconSize, iconSize))
            }
            row.addView(TextView(this).apply {
                text = count.toString()
                typeface = Typeface.create(lato, Typeface.BOLD)
                textSize = 18f
                setTextColor(ink)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(10f)
            })
            row.addView(TextView(this).apply {
                text = label
                typeface = lato
                textSize = 18f
                setTextColor(ink)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8f)
            })
            container.addView(row)
            builtRows.add(row)
        }
        if (builtRows.isEmpty()) return null

        island.addView(container, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))

        // "+N" overflow badge pinned to the widget's top-right corner.
        val overflow = TextView(this).apply {
            typeface = Typeface.create(lato, Typeface.BOLD)
            textSize = 14f
            setTextColor(ink)
            background = GradientDrawable().apply {
                setColor(if (prefs.darkMode) Color.BLACK else Color.WHITE)
                cornerRadius = dp(8f).toFloat()
                setStroke(dp(2.5f), ink)
            }
            setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
        }
        island.addView(overflow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        ))

        root.addView(island, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        // Resize cue below the box (outside it), visible only in edit mode.
        val handle = View(this).apply {
            background = GradientDrawable().apply {
                setColor(ink)
                cornerRadius = dp(3f).toFloat()
            }
            visibility = View.GONE
        }
        root.addView(handle, LinearLayout.LayoutParams(dp(48f), dp(5f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(6f)
        })

        islandView = island
        rowViews = builtRows
        overflowView = overflow
        handleView = handle
        totalCount = total
        visibleRows = 0
        applyRowVisibility(prefs.lockscreenWidgetRows.coerceIn(MIN_ROWS, MAX_ROWS))
        return root
    }

    /**
     * The launcher icon rendered the way AppIconCircle does it: surface fill in the icon
     * shape, the monochrome silhouette via the e-ink tint matrix, and an ink border with
     * an inset corner radius so the stroke stays concentric with the fill.
     */
    private fun iconBitmap(pkg: String, sizePx: Int, ink: Int): Bitmap? {
        val src = IconUtility.loadIcon(this, pkg, "", sizePx) ?: return null
        val surface = if (prefs.darkMode) Color.BLACK else Color.WHITE
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val size = sizePx.toFloat()
        val radius = if (prefs.roundedIcons) size * ICON_CORNER_FRACTION else size / 2f
        val shape = Path().apply { addRoundRect(RectF(0f, 0f, size, size), radius, radius, Path.Direction.CW) }
        canvas.drawPath(shape, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surface })
        canvas.save()
        canvas.clipPath(shape)
        val matrix = if (prefs.darkMode) EinkTintMatrixDark.values else EinkTintMatrix.values
        canvas.drawBitmap(src, null, RectF(0f, 0f, size, size), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        })
        canvas.restore()
        val strokeWidth = dp(2.5f).toFloat()
        val inset = strokeWidth / 2f
        canvas.drawRoundRect(
            RectF(inset, inset, size - inset, size - inset),
            radius - inset,
            radius - inset,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                this.strokeWidth = strokeWidth
                color = ink
            },
        )
        return out
    }

    private val labelCache = mutableMapOf<String, String>()

    private fun appLabel(pkg: String): String? {
        prefs.getAppRename(pkg)?.let { return it }
        labelCache[pkg]?.let { return it }
        return try {
            val label = packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
            labelCache[pkg] = label
            label
        } catch (_: Exception) {
            null
        }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
