package com.gezimos.katapult

import android.app.AlarmManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Telephony
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale
import com.gezimos.katapult.util.AudioWidgetHelper
import com.gezimos.katapult.weather.WeatherSources
import com.gezimos.katapult.weather.api.Weather
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.gezimos.katapult.model.AppModel
import com.gezimos.katapult.service.DirectBadgeHelper
import com.gezimos.katapult.service.NotificationListener
import com.gezimos.katapult.service.BadgeSources
import com.gezimos.katapult.util.ActionRequests
import com.gezimos.katapult.util.AccessibilityFeature
import com.gezimos.katapult.util.AccessibilityPolicy
import com.gezimos.katapult.util.AccessibilitySelection
import com.gezimos.katapult.util.BadgeActions
import com.gezimos.katapult.util.BadgeSource
import com.gezimos.katapult.util.SensitiveAccess
import com.gezimos.katapult.util.SensitiveChoice
import com.gezimos.katapult.ui.IconSize
import com.gezimos.katapult.util.AppLoader
import com.gezimos.katapult.util.BitmapHelper
import com.gezimos.katapult.util.DeviceHelper
import com.gezimos.katapult.util.IconUtility
import com.gezimos.katapult.util.PrefsManager
import com.gezimos.katapult.util.SystemChrome
import com.gezimos.katapult.util.ReorderBaseline
import com.gezimos.katapult.util.ReorderChoice
import com.gezimos.katapult.util.ShortcutHelper
import com.gezimos.katapult.util.UsageHelper
import java.io.File
import java.util.Date

enum class Screen { ONBOARDING, HOME, ALL_APPS, SETTINGS }

private val AM_PM_REGEX = Regex("\\s*[AaPp][Mm]\\s*")

private val dateFormatCache = mutableMapOf<String, SimpleDateFormat>()

private fun dateFormatter(pattern: String): SimpleDateFormat {
    val locale = Locale.getDefault()
    return dateFormatCache.getOrPut("$pattern|$locale") { SimpleDateFormat(pattern, locale) }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val prefs = PrefsManager(application)

    var screen by mutableStateOf(if (prefs.onboardingComplete) Screen.HOME else Screen.ONBOARDING)
    var shortcutRefresh by mutableIntStateOf(0)
    var contextMenuApp by mutableStateOf<AppModel?>(null)
    var showRenameDialog by mutableStateOf(false)
    var renameDraft by mutableStateOf<androidx.compose.ui.text.input.TextFieldValue?>(null)
    private var renameDraftKey: String? = null
    var lockscreenMessageDraft by mutableStateOf<androidx.compose.ui.text.input.TextFieldValue?>(null)
    var showLockscreenMessageSheet by mutableStateOf(false)
    var pendingImportText by mutableStateOf<String?>(null)
    var pendingImportPreview by mutableStateOf<PrefsManager.PolicyImportPreview?>(null)
    var reorderMode by mutableStateOf(false)
    var reorderHighlightIndex by mutableIntStateOf(-1)
    var showReorderExit by mutableStateOf(false)
        private set
    private var reorderBaseline: ReorderBaseline? = null
    private var reorderBaselineApps: List<AppModel> = emptyList()
    private var pendingReorderExit: (() -> Unit)? = null
    private var appRefreshDeferred = false
    var iconOverrideTarget by mutableStateOf<String?>(null)
    var iconImportError by mutableStateOf(false)
    var sheetDismissSignal by mutableIntStateOf(0)
        private set
    var orderedApps by mutableStateOf(listOf<AppModel>())
        private set
    var currentPage by mutableIntStateOf(0)
        private set
    var clockTime by mutableStateOf("")
        private set
    var clockAmPm by mutableStateOf<String?>(null)
        private set
    var clockDate by mutableStateOf("")
        private set
    var alarmTime by mutableStateOf<String?>(null)
        private set
    var notificationCounts by mutableStateOf(mapOf<String, Int>())
        private set
    var wallpaperBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var screensaverWallpaperPath by mutableStateOf(prefs.screensaverWallpaperPath)
        private set
    private var wallpaperRequest = 0L
    private var screensaverWallpaperRequest = 0L
    var batteryPercent by mutableIntStateOf(-1)
        private set
    var isCharging by mutableStateOf(false)
        private set
    var mediaInfo by mutableStateOf<AudioWidgetHelper.MediaInfo?>(null)
        private set
    var weather by mutableStateOf<Weather?>(null)
        private set
    private val weatherRefreshMs = 10 * 60 * 1000L
    private var weatherCheckedAt = -weatherRefreshMs
    var roundedIcons by mutableStateOf(prefs.roundedIcons)
    var iconSize by mutableStateOf(prefs.iconSize)
    var themeMode by mutableIntStateOf(prefs.themeMode)

    var showLockscreenReEnable by mutableStateOf(false)
    private var lockscreenReEnableChecked = false

    fun checkLockscreenService(context: Context) {
        checkLockscreenService(com.gezimos.katapult.lockscreen.LockscreenWidgetService.isEnabled(context))
    }

    internal fun checkLockscreenService(serviceEnabled: Boolean) {
        if (serviceEnabled) {
            lockscreenReEnableChecked = false
            showLockscreenReEnable = false
            return
        }
        if (!prefs.sensitiveChoice(SensitiveChoice.ACCESSIBILITY_FEATURES)) return
        if (lockscreenReEnableChecked || screen != Screen.HOME) return
        if (lockscreenServiceFeatures().isNotEmpty()) {
            lockscreenReEnableChecked = true
            showLockscreenReEnable = true
        }
    }

    fun resolveLockscreenReEnable() {
        showLockscreenReEnable = false
    }

    private fun lockscreenServiceFeatures() = AccessibilityPolicy.serviceFeatures(AccessibilitySelection(
        widget = prefs.lockscreenWidget, media = prefs.lockscreenMusicWidget,
        message = !prefs.lockscreenMessage.isNullOrBlank(),
        clockCover = prefs.hideStatusBarClock && DeviceHelper.isMuditaKompakt(),
        doubleTapLock = prefs.doubleTapAction == PrefsManager.DOUBLE_TAP_LOCK,
        screensaver = prefs.screensaverEnabled, powerScreensaver = prefs.screensaverOnPower,
        weather = prefs.lockscreenWeather,
        panel = prefs.lockscreenPanelTest in setOf(4, 8),
    ))

    // Include stored-but-inactive switches that the off action also clears.
    private fun lockscreenFeaturesToDisable() = (lockscreenServiceFeatures() + listOfNotNull(
        AccessibilityFeature.CLOCK_COVER.takeIf { prefs.hideStatusBarClock },
        AccessibilityFeature.POWER_SCREENSAVER.takeIf { prefs.screensaverOnPower },
    )).distinct().sortedBy { it.ordinal }

    fun lockscreenServiceFeatureTitles() = lockscreenFeaturesToDisable().map { feature ->
        when (feature) {
            AccessibilityFeature.WIDGET -> R.string.lockscreen_widget
            AccessibilityFeature.MEDIA -> R.string.lockscreen_music_widget
            AccessibilityFeature.MESSAGE -> R.string.lockscreen_message
            AccessibilityFeature.CLOCK_COVER -> R.string.hide_status_bar_clock
            AccessibilityFeature.DOUBLE_TAP_LOCK -> R.string.double_tap_action
            AccessibilityFeature.POWER_SCREENSAVER -> R.string.screensaver_on_power
            AccessibilityFeature.WEATHER -> R.string.lockscreen_weather
            AccessibilityFeature.PANEL -> R.string.lockscreen_panel_test
        }
    }

    fun disableLockscreenServiceFeatures() {
        for (feature in lockscreenFeaturesToDisable()) when (feature) {
            AccessibilityFeature.WIDGET -> prefs.lockscreenWidget = false
            AccessibilityFeature.MEDIA -> prefs.lockscreenMusicWidget = false
            AccessibilityFeature.MESSAGE -> Unit // Message clearing is a separate user choice.
            AccessibilityFeature.CLOCK_COVER -> prefs.hideStatusBarClock = false
            AccessibilityFeature.DOUBLE_TAP_LOCK -> prefs.doubleTapAction = PrefsManager.DOUBLE_TAP_OFF
            AccessibilityFeature.POWER_SCREENSAVER -> prefs.screensaverOnPower = false
            AccessibilityFeature.WEATHER -> prefs.lockscreenWeather = false
            AccessibilityFeature.PANEL -> prefs.lockscreenPanelTest = 0
        }
        resolveLockscreenReEnable()
    }

    fun startScreensaver(context: Context) {
        if (!prefs.screensaverEnabled) return
        requestReorderExit {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).setClass(context, com.gezimos.katapult.lockscreen.ScreensaverActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {}
        }
    }

    val gridColumns = 3
    var appsPerPage by mutableIntStateOf(12)
        private set
    val totalPages: Int by derivedStateOf {
        if (orderedApps.isEmpty()) 1 else ((orderedApps.size + appsPerPage - 1) / appsPerPage)
    }

    fun updateAppsPerPage(perPage: Int) {
        val clamped = perPage.coerceAtLeast(gridColumns)
        if (clamped == appsPerPage) return
        appsPerPage = clamped
        val maxPage = (totalPages - 1).coerceAtLeast(0)
        if (currentPage > maxPage) currentPage = maxPage
    }

    private val ctx get() = getApplication<Application>()
    private val isMudita = DeviceHelper.isMuditaKompakt()
    private val weatherSource = WeatherSources.active(ctx)
    private val directBadgeHelper = if (isMudita) DirectBadgeHelper.getInstance(application) else null
    private val badgeOwner = Any()
    private val mediaOwner = Any()
    private var badgeLease: AutoCloseable? = null
    private var mediaLease: AutoCloseable? = null
    private val badgeActions = BadgeActions(
        route = { BadgeSources.route(ctx, it) },
        dismissAllowed = SensitiveAccess::notificationDismissal,
        callChoiceAllowed = { prefs.sensitiveChoice(SensitiveChoice.CALL_ACKNOWLEDGMENT) && SensitiveAccess.callBadges() },
        callWriteGranted = SensitiveAccess::canWriteCallLog,
        revision = { SensitiveAccess.callActionRevision },
        acquire = { SensitiveAccess.acquireBadges(Any()) },
        dismiss = NotificationListener::cancelFor,
        acknowledge = { current, finished ->
            directBadgeHelper?.clearMissedCalls(current) { clockHandler.post { finished() } } ?: finished()
        },
    )
    private var foreground = false
    private var cleared = false
    private val policySubscription: AutoCloseable
    var policyRefresh by mutableIntStateOf(0)
        private set

    private val clockHandler = Handler(Looper.getMainLooper())

    private val badgeListener: () -> Unit = {
        clockHandler.post { refreshNotifications() }
    }
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 15_000)
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateClock()
        }
    }

    init {
        SensitiveAccess.initialize(application)
        policySubscription = SensitiveAccess.subscribe {
            clockHandler.post {
                if (!cleared) {
                    badgeActions.reconcile()
                    policyRefresh++
                    reconcileSensitiveSources()
                    loadApps()
                    refreshNotifications()
                }
            }
        }
        loadApps()
        updateClock()
        loadWallpaper()
        ctx.registerReceiver(powerReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        viewModelScope.launch {
            AudioWidgetHelper.getInstance(ctx).state.collect { projectMedia() }
        }
        val sizePx = Math.round(iconSize * ctx.resources.displayMetrics.density)
        for (slot in PrefsManager.HOME_SLOTS) {
            val pkg = getShortcutPackage(slot) ?: continue
            val shortcutId = prefs.loadSlotShortcutId(slot)
            if (shortcutId != null) {
                IconUtility.loadShortcutIcon(ctx, pkg, shortcutId, sizePx)
            } else {
                IconUtility.loadIcon(ctx, pkg, getShortcutActivity(slot), sizePx)
            }
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            IconUtility.preloadIcons(ctx, orderedApps, sizePx)
        }
    }

    fun updateClock() {
        val now = Date()
        val clockPattern = prefs.clockFormat
        val fullTime = if (clockPattern == "system") DateFormat.getTimeFormat(ctx).format(now)
            else dateFormatter(clockPattern).format(now)
        clockTime = fullTime.replace(AM_PM_REGEX, "").trim()
        val upper = fullTime.uppercase()
        clockAmPm = when {
            upper.contains("AM") -> "AM"
            upper.contains("PM") -> "PM"
            else -> null
        }
        val datePattern = prefs.dateFormat
        clockDate = if (datePattern == "system") DateFormat.getLongDateFormat(ctx).format(now)
            else dateFormatter(datePattern).format(now)

        val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val info = alarmManager.nextAlarmClock
        alarmTime = if (info != null) {
            DateFormat.getTimeFormat(ctx).format(Date(info.triggerTime))
        } else null

        val batteryIntent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) {
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            batteryPercent = (level * 100) / scale
            val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        }

        if (!prefs.showWeather) {
            weather = null
        } else if (SystemClock.elapsedRealtime() - weatherCheckedAt >= weatherRefreshMs) {
            refreshWeather()
        }
    }

    private var weatherObserverHandle: AutoCloseable? = null

    private fun onWeatherChanged() {
        val source = weatherSource ?: return
        weatherCheckedAt = SystemClock.elapsedRealtime()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = source.read(ctx)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (result != null) weather = result
            }
        }
    }

    private fun syncWeatherObserver() {
        val source = weatherSource ?: return
        val want = prefs.showWeather
        if (want == (weatherObserverHandle != null)) return
        try {
            if (want) {
                weatherObserverHandle = source.observe(ctx) { onWeatherChanged() }
            } else {
                weatherObserverHandle?.close()
                weatherObserverHandle = null
            }
        } catch (_: Exception) {
        }
    }

    fun refreshWeather(force: Boolean = false) {
        syncWeatherObserver()
        val source = weatherSource
        if (!prefs.showWeather || source == null) {
            weatherCheckedAt = SystemClock.elapsedRealtime()
            weather = null
            return
        }
        if (!force && weather != null &&
            SystemClock.elapsedRealtime() - weatherCheckedAt < weatherRefreshMs
        ) return
        weatherCheckedAt = SystemClock.elapsedRealtime()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = source.read(ctx)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (result != null) weather = result
            }
        }
    }

    fun startClock() {
        updateClock()
        clockHandler.postDelayed(clockRunnable, 15_000)
    }

    fun stopClock() {
        clockHandler.removeCallbacks(clockRunnable)
    }

    fun loadApps() {
        if (reorderMode) {
            appRefreshDeferred = true
            return
        }
        val previousKeys = orderedApps.map { it.key }.toSet()
        val currentApps = AppLoader.loadApps(ctx, showSelf = prefs.showKatapultIcon) +
            ShortcutHelper.installedShortcuts(ctx, prefs)
        val hidden = prefs.getHiddenApps()
        val visible = currentApps.filter { it.key !in hidden }
        val renamed = visible.map { app ->
            val customName = prefs.getAppRename(app.key)
            if (customName != null) app.copy(label = customName) else app
        }
        val sortMode = prefs.appSortMode
        val stats = if (sortMode != PrefsManager.APP_SORT_MANUAL) UsageHelper.stats(ctx)
            else { UsageHelper.invalidate(); null }
        if (stats != null && UsageHelper.available(ctx)) {
            val ignored = UsageHelper.ignoredPackages(ctx)
            orderedApps = renamed.sortedWith(
                compareByDescending<AppModel> { app ->
                    val s = if (app.packageName in ignored) null else stats[app.packageName]
                    if (s == null) 0L
                    else if (sortMode == PrefsManager.APP_SORT_RECENT) s.lastTimeUsed
                    else s.totalTimeInForeground
                }.thenBy { it.label.lowercase() }
            )
            refreshHomeIfAppsChanged(previousKeys)
            return
        }
        val savedOrder = prefs.loadAppOrder()
        orderedApps = if (savedOrder != null) {
            prefs.reconcileOrder(savedOrder, renamed)
        } else {
            renamed.sortedBy { it.label.lowercase() }
        }
        refreshHomeIfAppsChanged(previousKeys)
    }

    private fun refreshHomeIfAppsChanged(previousKeys: Set<String>) {
        if (previousKeys.isEmpty()) return
        if (orderedApps.map { it.key }.toSet() != previousKeys) shortcutRefresh++
    }

    fun getAllApps(): List<AppModel> {
        val all = AppLoader.loadApps(ctx) + ShortcutHelper.installedShortcuts(ctx, prefs)
        return all.map { app ->
            val customName = prefs.getAppRename(app.key)
            if (customName != null) app.copy(label = customName) else app
        }.sortedBy { it.label.lowercase() }
    }

    fun showPage(page: Int) {
        if (page < 0 || page >= totalPages) return
        currentPage = page
    }

    fun navigateTo(target: Screen) {
        requestReorderExit {
            if (target == Screen.ALL_APPS) currentPage = 0
            screen = target
            reconcileSensitiveSources()
        }
    }

    fun onHomeIntent() {
        requestReorderExit {
            // Close draft editors without treating HOME as an explicit cancellation.
            showRenameDialog = false
            contextMenuApp = null
            showLockscreenMessageSheet = false
            dismissAllSheets()
            if (screen != Screen.ONBOARDING) screen = Screen.HOME
            reconcileSensitiveSources()
        }
    }

    fun dismissAllSheets() {
        sheetDismissSignal++
    }

    fun goBack(): Boolean {
        if (showReorderExit) {
            cancelReorderExit()
            return true
        }
        if (reorderMode) {
            requestReorderExit {}
            return true
        }
        if (contextMenuApp != null) { contextMenuApp = null; showRenameDialog = false; return true }
        return when (screen) {
            Screen.ONBOARDING -> false
            Screen.SETTINGS -> { screen = Screen.HOME; reconcileSensitiveSources(); true }
            Screen.ALL_APPS -> { screen = Screen.HOME; reconcileSensitiveSources(); true }
            Screen.HOME -> false
        }
    }

    fun enterReorderMode(app: AppModel) {
        val index = orderedApps.indexOfFirst { it.key == app.key }
        if (index >= 0) {
            if (!reorderMode) {
                reorderBaselineApps = orderedApps.toList()
                reorderBaseline = ReorderBaseline(orderedApps.map { it.key }, prefs.loadAppOrder()?.toList())
            }
            reorderHighlightIndex = index
            reorderMode = true
        }
        contextMenuApp = null
    }

    fun reorderTap(targetIndex: Int) {
        if (!reorderMode || targetIndex !in orderedApps.indices) return
        if (reorderHighlightIndex < 0) {
            reorderHighlightIndex = targetIndex
        } else if (targetIndex == reorderHighlightIndex) {
            reorderHighlightIndex = -1
        } else {
            val list = orderedApps.toMutableList()
            val temp = list[reorderHighlightIndex]
            list[reorderHighlightIndex] = list[targetIndex]
            list[targetIndex] = temp
            orderedApps = list
            reorderHighlightIndex = -1
        }
    }

    fun finishReorder() {
        resolveReorderExit(ReorderChoice.SAVE)
    }

    private fun requestReorderExit(exit: () -> Unit) {
        if (!reorderMode) {
            exit()
            return
        }
        // An already-present decision retains its original destination.
        if (showReorderExit) return
        val baseline = reorderBaseline ?: return
        pendingReorderExit = exit
        if (baseline.isDirty(orderedApps.map { it.key })) {
            showReorderExit = true
        } else {
            resolveReorderExit(ReorderChoice.DISCARD)
        }
    }

    fun saveReorderExit() = resolveReorderExit(ReorderChoice.SAVE)

    fun discardReorderExit() = resolveReorderExit(ReorderChoice.DISCARD)

    fun cancelReorderExit() = resolveReorderExit(ReorderChoice.CANCEL)

    private fun resolveReorderExit(choice: ReorderChoice) {
        val baseline = reorderBaseline ?: return
        val resolution = baseline.resolve(choice, orderedApps.map { it.key })
        if (resolution == null) {
            showReorderExit = false
            pendingReorderExit = null
            return
        }
        if (resolution.persist) prefs.saveAppOrder(resolution.keys)
        if (choice == ReorderChoice.DISCARD) orderedApps = reorderBaselineApps
        val exit = pendingReorderExit
        pendingReorderExit = null
        showReorderExit = false
        reorderMode = false
        reorderHighlightIndex = -1
        reorderBaseline = null
        reorderBaselineApps = emptyList()
        if (appRefreshDeferred) {
            appRefreshDeferred = false
            loadApps()
        }
        exit?.invoke()
    }

    fun resetOrder() {
        orderedApps = orderedApps.sortedBy { it.label.lowercase() }
        reorderHighlightIndex = -1
        if (!reorderMode) prefs.saveAppOrder(orderedApps.map { it.key })
    }

    fun hideApp(key: String) {
        prefs.hideApp(key)
        contextMenuApp = null
        loadApps()
    }

    fun renameApp(key: String, newName: String, currentLabel: String) {
        val name = newName.ifBlank { null }
        contextMenuApp = null
        showRenameDialog = false
        renameDraft = null
        renameDraftKey = null
        if (name == currentLabel) return
        if (name != prefs.getAppRename(key)) prefs.removeFromAppOrder(key)
        prefs.setAppRename(key, name)
        shortcutRefresh++
        loadApps()
    }

    fun beginRename(app: AppModel) {
        if (renameDraftKey != app.key || renameDraft == null) {
            renameDraftKey = app.key
            renameDraft = androidx.compose.ui.text.input.TextFieldValue(
                app.label, androidx.compose.ui.text.TextRange(app.label.length),
            )
        }
        showRenameDialog = true
    }

    fun beginLockscreenMessage() {
        if (lockscreenMessageDraft == null) {
            val text = prefs.lockscreenMessage.orEmpty()
            lockscreenMessageDraft = androidx.compose.ui.text.input.TextFieldValue(
                text, androidx.compose.ui.text.TextRange(text.length),
            )
        }
        showLockscreenMessageSheet = true
    }

    fun applyConfigImport(context: Context, text: String, confirmed: Map<String, Boolean>?): Boolean {
        val ok = prefs.importFromJson(text, confirmed)
        if (ok) {
            ShortcutHelper.syncAllPins(context, prefs)
            IconUtility.invalidateOverrides()
            context.startActivity(Intent.makeRestartActivityTask(ComponentName(context, MainActivity::class.java)))
        }
        return ok
    }

    fun removeShortcut(app: AppModel) {
        forgetShortcut(app.packageName, app.shortcutId)
        contextMenuApp = null
        shortcutRefresh++
        loadApps()
    }

    fun setShortcutEnabled(packageName: String, shortcutId: String, label: String, enabled: Boolean) {
        if (enabled) {
            prefs.addSavedShortcut(packageName, shortcutId, label)
            prefs.unhideApp("$packageName|$shortcutId")
        } else {
            prefs.removeSavedShortcut(packageName, shortcutId)
            prefs.clearSlotsForShortcut(packageName, shortcutId)
        }
        ShortcutHelper.syncPins(ctx, prefs, packageName)
        shortcutRefresh++
        loadApps()
    }

    private fun forgetShortcut(packageName: String, shortcutId: String) {
        val key = "$packageName|$shortcutId"
        prefs.removeSavedShortcut(packageName, shortcutId)
        prefs.clearSlotsForShortcut(packageName, shortcutId)
        prefs.setAppRename(key, null)
        prefs.removeFromAppOrder(key)
        prefs.unhideApp(key)
        val existing = prefs.getIconOverride(key)
        prefs.clearIconOverride(key)
        deleteOverrideFile(existing)
        IconUtility.clearCacheFor(key)
        ShortcutHelper.syncPins(ctx, prefs, packageName)
    }

    fun applyStatusBar(context: Context) {
        val activity = context as? Activity ?: return
        SystemChrome.apply(activity.window, SystemChrome.isDark(activity, prefs.themeMode), prefs.hideStatusBar)
    }

    fun launchIntent(context: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun launchApp(context: Context, app: AppModel) {
        if (app.shortcutId.isEmpty() &&
            app.packageName == ctx.packageName &&
            DeviceHelper.isDefaultLauncher(ctx)
        ) {
            navigateTo(Screen.SETTINGS)
            return
        }
        if (app.shortcutId.isNotEmpty()) {
            if (ShortcutHelper.launch(ctx, app.packageName, app.shortcutId)) {
                (context as? Activity)?.overridePendingTransition(0, 0)
            } else {
                launchPackage(context, app.packageName, "")
            }
            return
        }
        launchPackage(context, app.packageName, app.activityName)
    }

    fun launchPackage(context: Context, packageName: String, activityName: String) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(packageName, activityName)
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            val fallback = ctx.packageManager.getLaunchIntentForPackage(packageName)
            if (fallback != null) context.startActivity(fallback)
        }
        (context as? Activity)?.overridePendingTransition(0, 0)
    }

    fun launchShortcut(context: Context, slot: String) {
        val saved = prefs.loadShortcut(slot)
        if (saved != null) {
            val shortcutId = prefs.loadSlotShortcutId(slot)
            if (shortcutId != null) {
                if (ShortcutHelper.launch(ctx, saved.first, shortcutId)) {
                    (context as? Activity)?.overridePendingTransition(0, 0)
                } else {
                    launchPackage(context, saved.first, "")
                }
            } else {
                launchPackage(context, saved.first, saved.second)
            }
            return
        }
        val defaultPkg = getDefaultPackageForSlot(slot)
        if (defaultPkg != null) {
            val launchIntent = ctx.packageManager.getLaunchIntentForPackage(defaultPkg)
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                (context as? Activity)?.overridePendingTransition(0, 0)
            }
        }
    }

    fun getShortcutPackage(slot: String): String? {
        return prefs.loadShortcut(slot)?.first ?: getDefaultPackageForSlot(slot)
    }

    fun getShortcutActivity(slot: String): String {
        return prefs.loadShortcut(slot)?.second ?: ""
    }

    fun getShortcutLabel(slot: String, defaultLabel: String): String {
        val pkg = getShortcutPackage(slot) ?: return defaultLabel
        val shortcutId = prefs.loadSlotShortcutId(slot)
        if (shortcutId != null) {
            prefs.getAppRename("$pkg|$shortcutId")?.let { return it }
            return prefs.getSavedShortcuts()
                .find { it.packageName == pkg && it.shortcutId == shortcutId }
                ?.label ?: defaultLabel
        }
        val customName = prefs.getAppRename(pkg)
        if (customName != null) return customName
        return try {
            val appInfo = ctx.packageManager.getApplicationInfo(pkg, 0)
            ctx.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            defaultLabel
        }
    }

    fun saveShortcut(slot: String, packageName: String, activityName: String, shortcutId: String = "") {
        prefs.saveShortcut(slot, packageName, activityName, shortcutId)
    }

    private fun getDefaultPackageForSlot(slot: String): String? {
        return when (slot) {
            "phone" -> resolveIntent(Intent(Intent.ACTION_DIAL))
            "sms" -> Telephony.Sms.getDefaultSmsPackage(ctx)
            "extra_left" -> "com.mudita.audio.player".takeIfInstalled()
                ?: resolveCategory(Intent.CATEGORY_APP_MUSIC)
            "extra_center" -> "com.mudita.calendar".takeIfInstalled()
                ?: resolveCategory(Intent.CATEGORY_APP_CALENDAR)
            "extra_right" -> "com.mudita.camera".takeIfInstalled()
                ?: resolveIntent(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            "center" -> resolveIntent(
                Intent(Intent.ACTION_VIEW, android.provider.ContactsContract.Contacts.CONTENT_URI)
            )
            else -> null
        }
    }

    private fun resolveIntent(intent: Intent): String? {
        val pm = ctx.packageManager
        val direct = pm.resolveActivity(intent, 0)?.activityInfo?.packageName
        if (direct != null && direct != "android") return direct
        val candidates = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName != "android" }
        val preferred = candidates.firstOrNull { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }
        return (preferred ?: candidates.firstOrNull())?.packageName
    }

    private fun resolveCategory(category: String): String? =
        resolveIntent(Intent(Intent.ACTION_MAIN).addCategory(category))

    private fun String.takeIfInstalled(): String? {
        return try {
            ctx.packageManager.getApplicationInfo(this, 0)
            this
        } catch (_: Exception) { null }
    }

    fun refreshNotifications() {
        notificationCounts = if (foreground && prefs.notificationIndicators && screen in setOf(Screen.HOME, Screen.ALL_APPS))
            BadgeSources.counts(ctx) else emptyMap()
    }

    fun clearNotificationsFor(pkg: String) {
        requestBadgeClear(pkg)
    }

    fun hasMissedCalls(): Boolean = directBadgeHelper?.hasMissedCalls() == true

    fun canWriteCallLog(): Boolean = directBadgeHelper?.hasWriteCallLogPermission() == true

    fun canClearBadge(pkg: String): Boolean = BadgeSources.canClear(ctx, pkg)
    fun badgeSource(pkg: String): BadgeSource = BadgeSources.route(ctx, pkg).source

    /** Returns an ID only when this deliberate action needs WRITE_CALL_LOG. */
    fun requestBadgeClear(pkg: String): Long? {
        return badgeActions.request(pkg)
    }

    fun callAcknowledgmentResult(id: Long?, granted: Boolean) {
        badgeActions.result(id, granted)
    }

    private fun cancelPendingBadgeAction() {
        badgeActions.close()
    }

    fun reconcileSensitiveSources() {
        val badges = foreground && prefs.notificationIndicators && screen in setOf(Screen.HOME, Screen.ALL_APPS)
        if (badges && badgeLease == null) badgeLease = SensitiveAccess.acquireBadges(badgeOwner)
        else if (!badges) { badgeLease?.close(); badgeLease = null }
        val media = foreground && screen == Screen.HOME && !prefs.disableMusicWidget
        if (media && mediaLease == null) mediaLease = SensitiveAccess.acquireMedia(mediaOwner)
        else if (!media) { mediaLease?.close(); mediaLease = null }
        directBadgeHelper?.reconcile()
        audioHelper.reconcile()
        projectMedia()
    }

    /** Media is shown only while its eligibility holds; recomputed on every state or screen change. */
    private fun projectMedia() {
        val eligible = foreground && screen == Screen.HOME && !prefs.disableMusicWidget && SensitiveAccess.mediaInfo()
        mediaInfo = if (eligible) AudioWidgetHelper.getInstance(ctx).state.value else null
    }

    private val audioHelper get() = AudioWidgetHelper.getInstance(ctx)

    fun mediaPlayPause() { audioHelper.playPause() }
    fun mediaNext() { audioHelper.skipNext() }
    fun mediaPrevious() { audioHelper.skipPrevious() }
    fun mediaStop() { audioHelper.stop() }
    fun mediaOpenApp(context: Context) { audioHelper.openApp() }

    fun startNotificationListener() {
        foreground = true
        NotificationListener.addListener(badgeListener)
        directBadgeHelper?.addListener(badgeListener)
        reconcileSensitiveSources()
        SensitiveAccess.refresh()
    }

    fun stopNotificationListener() {
        foreground = false
        NotificationListener.removeListener(badgeListener)
        directBadgeHelper?.removeListener(badgeListener)
        reconcileSensitiveSources()
        notificationCounts = emptyMap()
    }

    fun setWallpaper(context: Context, uri: Uri) {
        val request = ++wallpaperRequest
        val maxSide = BitmapHelper.screenMaxSide(ctx)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val bitmap = BitmapHelper.decodeUri(context, uri, maxSide) ?: return@launch
            val file = File(ctx.filesDir, "wallpaper.png")
            saveWallpaperBitmap(file, request, bitmap) { temp ->
                if (request == wallpaperRequest && temp.renameTo(file)) {
                    prefs.wallpaperPath = file.absolutePath
                    wallpaperBitmap = bitmap
                }
            }
        }
    }

    fun clearWallpaper() {
        ++wallpaperRequest
        val previous = prefs.wallpaperPath
        prefs.wallpaperPath = null
        wallpaperBitmap = null
        previous?.let(prefs::deleteOwnedFileIfUnreferenced)
    }

    fun setScreensaverWallpaper(context: Context, uri: Uri) {
        val request = ++screensaverWallpaperRequest
        val maxSide = BitmapHelper.screenMaxSide(ctx)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val bitmap = BitmapHelper.decodeUri(context, uri, maxSide) ?: return@launch
            val file = File(ctx.filesDir, "screensaver_wallpaper.png")
            saveWallpaperBitmap(file, request, bitmap) { temp ->
                if (request == screensaverWallpaperRequest && temp.renameTo(file)) {
                    prefs.screensaverWallpaperPath = file.absolutePath
                    screensaverWallpaperPath = file.absolutePath
                }
            }
        }
    }

    fun clearScreensaverWallpaper() {
        ++screensaverWallpaperRequest
        val previous = prefs.screensaverWallpaperPath
        prefs.screensaverWallpaperPath = null
        screensaverWallpaperPath = null
        previous?.let(prefs::deleteOwnedFileIfUnreferenced)
    }

    private suspend fun saveWallpaperBitmap(file: File, request: Long, bitmap: Bitmap, publish: (File) -> Unit) {
        val temp = File(file.path + ".$request.tmp")
        try {
            temp.outputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "Wallpaper compression failed" }
            }
            // Check freshness, replace bytes and publish together on Main; Clear/new picks cannot interleave.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { publish(temp) }
        } catch (_: Exception) {
            // Keep the prior file, preference and displayed image on failure.
        } finally {
            deleteTemporaryFile(temp)
        }
    }

    private fun deleteTemporaryFile(temp: File) {
        // Failed open() probes can leave an empty, locally generated directory.
        val ownedDirectory = java.nio.file.Files.isDirectory(temp.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
            temp.canonicalFile.toPath().startsWith(ctx.filesDir.canonicalFile.toPath())
        if (prefs.isOwnedFile(temp.path) || ownedDirectory) temp.delete()
    }

    private fun iconFile(key: String): File {
        val dir = File(ctx.filesDir, "icons").apply { mkdirs() }
        val safe = key.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val name = if (safe == key) safe
        else "${safe.take(60)}_${key.hashCode().toUInt().toString(16)}"
        return File(dir, "$name.png")
    }

    private fun saveIconBitmap(key: String, bitmap: Bitmap): File {
        val file = iconFile(key)
        val temp = File(file.path + ".tmp")
        try {
            temp.outputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "Icon compression failed" }
            }
            check(temp.renameTo(file)) { "Icon replacement failed" }
            return file
        } finally {
            deleteTemporaryFile(temp)
        }
    }

    fun saveMaterialIcon(key: String, bitmap: Bitmap) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val file = saveIconBitmap(key, bitmap)
                prefs.setIconOverride(key, "file:${file.absolutePath}")
                IconUtility.clearCacheFor(key)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    shortcutRefresh++
                    loadApps()
                }
            } catch (_: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    iconImportError = true
                }
            }
        }
    }

    fun setCatalogueIcon(key: String, id: String): Deferred<Boolean> =
        viewModelScope.async(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val icon = com.gezimos.katapult.util.IconCatalogue.load(ctx).icon(id)
                    ?: return@async false
                com.gezimos.katapult.util.CatalogueIconRenderer.render(ctx, icon, 192)
                    ?: return@async false
                if (!prefs.commitIconOverride(key, com.gezimos.katapult.util.IconSelections.CATALOGUE_PREFIX + id)) {
                    return@async false
                }
                // Old imported files remain intact; legacy references are never migrated.
                IconUtility.clearCacheFor(key)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    shortcutRefresh++
                    loadApps()
                }
                true
            } catch (error: Exception) {
                android.util.Log.w("KotatsuIcons", "Could not select catalogue icon $id", error)
                false
            }
        }

    fun beginIconImport(key: String) {
        iconOverrideTarget = key
        prefs.pendingIconTarget = key
    }

    fun saveImportedIcon(context: Context, uri: Uri) {
        val key = iconOverrideTarget ?: prefs.pendingIconTarget
        iconOverrideTarget = null
        prefs.pendingIconTarget = null
        if (key == null) {
            iconImportError = true
            return
        }

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val mime = context.contentResolver.getType(uri).orEmpty()
                val isSvg = mime == "image/svg+xml" ||
                    uri.toString().endsWith(".svg", ignoreCase = true)

                val targetPx = 256
                val bitmap: Bitmap? = if (isSvg) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        com.gezimos.katapult.util.SvgRasterizer.rasterize(input, targetPx)
                    }
                } else {
                    BitmapHelper.decodeUri(context, uri, targetPx * 2)?.let { fitToIcon(it, targetPx) }
                }
                if (bitmap == null) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        iconImportError = true
                    }
                    return@launch
                }

                val file = saveIconBitmap(key, bitmap)
                prefs.setIconOverride(key, "file:${file.absolutePath}")
                IconUtility.clearCacheFor(key)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    shortcutRefresh++
                    loadApps()
                }
            } catch (_: Throwable) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    iconImportError = true
                }
            }
        }
    }

    fun cancelIconImport() {
        iconOverrideTarget = null
        prefs.pendingIconTarget = null
    }

    fun setBundledIcon(key: String, resName: String) {
        val existing = prefs.getIconOverride(key)
        prefs.setIconOverride(key, "res:$resName")
        deleteOverrideFile(existing)
        IconUtility.clearCacheFor(key)
        shortcutRefresh++
    }

    fun clearIconOverride(key: String) {
        val existing = prefs.getIconOverride(key)
        prefs.clearIconOverride(key)
        deleteOverrideFile(existing)
        IconUtility.clearCacheFor(key)
        shortcutRefresh++
    }

    private fun deleteOverrideFile(existing: String?) {
        if (existing != null && existing.startsWith("file:")) {
            prefs.deleteOwnedFileIfUnreferenced(existing.removePrefix("file:"))
        }
    }

    private fun fitToIcon(source: Bitmap, targetPx: Int): Bitmap {
        val target = createBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        val maxSide = kotlin.math.max(source.width, source.height).toFloat().coerceAtLeast(1f)
        val scale = (targetPx * 0.6f) / maxSide
        val drawnW = source.width * scale
        val drawnH = source.height * scale
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate((targetPx - drawnW) / 2f, (targetPx - drawnH) / 2f)
        }
        canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return target
    }

    private fun loadWallpaper() {
        val path = prefs.wallpaperPath ?: return
        if (!prefs.isOwnedFile(path)) return
        wallpaperBitmap = BitmapHelper.decodeFile(path, BitmapHelper.screenMaxSide(ctx))
    }

    override fun onCleared() {
        cleared = true
        cancelPendingBadgeAction()
        policySubscription.close()
        super.onCleared()
        stopClock()
        stopNotificationListener()
        try { ctx.unregisterReceiver(powerReceiver) } catch (_: Exception) {}
        try { weatherObserverHandle?.close() } catch (_: Exception) {}
    }
}
