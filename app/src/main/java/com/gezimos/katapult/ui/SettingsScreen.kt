package com.gezimos.katapult.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.gezimos.katapult.lockscreen.LockscreenWidgetService
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gezimos.katapult.MainViewModel
import com.gezimos.katapult.R
import com.gezimos.katapult.util.AppLoader
import com.gezimos.katapult.util.AppVisibility
import com.gezimos.katapult.util.AccessibilityFeature
import com.gezimos.katapult.MainActivity
import com.gezimos.katapult.util.DeviceHelper
import com.gezimos.katapult.util.PrefsManager
import com.gezimos.katapult.util.IconUtility
import com.gezimos.katapult.util.ScreensaverShortcut
import com.gezimos.katapult.util.ShortcutHelper
import com.gezimos.katapult.util.UsageHelper
import com.gezimos.katapult.util.SensitiveChoice
import com.gezimos.katapult.util.SensitiveAccess
import com.gezimos.katapult.weather.WeatherSources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import com.gezimos.katapult.ui.settings.SettingsEntry
import com.gezimos.katapult.ui.settings.AppStorePackages
import com.gezimos.katapult.ui.settings.appStoreEntry
import com.gezimos.katapult.ui.settings.rememberAppStorePackages
import com.gezimos.katapult.ui.settings.openAppStoreIntent
import com.gezimos.katapult.ui.settings.NoticeContent
import com.gezimos.katapult.ui.settings.PaperIndexRenderer
import com.gezimos.katapult.ui.settings.TableOfContents
import com.gezimos.katapult.ui.settings.appearanceCategory
import com.gezimos.katapult.ui.settings.homeCategory
import com.gezimos.katapult.ui.settings.appsCategory
import com.gezimos.katapult.ui.settings.gesturesCategory
import com.gezimos.katapult.ui.settings.notificationsCategory
import com.gezimos.katapult.ui.settings.lockCategory
import com.gezimos.katapult.ui.settings.screensaverCategory
import com.gezimos.katapult.ui.settings.systemCategory
import com.gezimos.katapult.ui.settings.aboutCategory
import com.gezimos.katapult.ui.settings.SheetPage
import com.gezimos.katapult.ui.settings.SheetText
import com.gezimos.katapult.ui.settings.SheetAction
import com.gezimos.katapult.ui.settings.Entry
import com.gezimos.katapult.ui.settings.GroupHeading
import com.gezimos.katapult.ui.settings.ChoiceOption
import com.gezimos.katapult.ui.settings.CheckboxOption
import com.gezimos.katapult.ui.settings.AppChecklistPage
import com.gezimos.katapult.ui.settings.NotifyCell

private val sensitiveRows = listOf(
    SensitiveChoice.SMS_BADGES to R.string.policy_sms_badges,
    SensitiveChoice.CALL_BADGES to R.string.policy_call_badges,
    SensitiveChoice.NOTIFICATION_BADGES to R.string.policy_notification_badges,
    SensitiveChoice.MEDIA_INFO to R.string.policy_media_info,
    SensitiveChoice.USAGE_STATS to R.string.policy_usage_stats,
    SensitiveChoice.NOTIFICATION_DISMISSAL to R.string.policy_notification_dismissal,
    SensitiveChoice.CALL_ACKNOWLEDGMENT to R.string.policy_call_acknowledgment,
    SensitiveChoice.MEDIA_CONTROLS to R.string.policy_media_controls,
    SensitiveChoice.ACCESSIBILITY_FEATURES to R.string.policy_accessibility_features,
)

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    configExporter: ActivityResultLauncher<String>,
    configImporter: ActivityResultLauncher<Array<String>>,
    screensaverWallpaperPicker: ActivityResultLauncher<String>,
    appStorePackages: AppStorePackages = rememberAppStorePackages(),
    openStoreIntent: ((Intent) -> Boolean)? = null,
) {
    val context = LocalContext.current
    val store = appStoreEntry(appStorePackages, openStoreIntent ?: { openAppStoreIntent(context, it) })
    val prefs = viewModel.prefs
    val policyTick = viewModel.policyRefresh
    val policyChoices = remember(policyTick) { prefs.sensitiveChoices() }
    var showSensitiveAccess by remember { mutableStateOf(false) }
    val applyImport: (String, Map<String, Boolean>?) -> Boolean = { text, confirmed ->
        viewModel.applyConfigImport(context, text, confirmed)
    }
    val isMudita = remember { DeviceHelper.isMuditaKompakt() }
    val weatherSource = remember { WeatherSources.active(context) }
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }
    }

    var notificationIndicators by remember { mutableStateOf(prefs.notificationIndicators) }
    var clockFormat by remember { mutableStateOf(prefs.clockFormat) }
    var dateFormat by remember { mutableStateOf(prefs.dateFormat) }
    var showBattery by remember { mutableStateOf(prefs.showBattery) }
    var showAlarm by remember { mutableStateOf(prefs.showAlarm) }
    var showWeather by remember { mutableStateOf(prefs.showWeather) }
    var weatherCelsius by remember { mutableStateOf(prefs.weatherCelsius) }
    var roundedIcons by remember { mutableStateOf(prefs.roundedIcons) }
    var iconSize by remember { mutableIntStateOf(prefs.iconSize) }
    var themeMode by remember { mutableIntStateOf(prefs.themeMode) }
    var hideStatusBar by remember { mutableStateOf(prefs.hideStatusBar) }
    var hideStatusBarClock by remember { mutableStateOf(prefs.hideStatusBarClock) }
    var einkRefreshOnHome by remember { mutableStateOf(prefs.einkRefreshOnHome) }
    var einkHelperMode by remember { mutableIntStateOf(prefs.einkHelperMode) }
    var doubleTapAction by remember { mutableIntStateOf(prefs.doubleTapAction) }
    var homeExtraRow by remember { mutableStateOf(prefs.homeExtraRow) }
    var disableMusicWidget by remember { mutableStateOf(prefs.disableMusicWidget) }
    var infiniteScroll by remember { mutableStateOf(prefs.infiniteScroll) }
    var showKatapultIcon by remember { mutableStateOf(prefs.showKatapultIcon) }
    var hideAppNames by remember { mutableStateOf(prefs.hideAppNames) }
    var hideArrowButtons by remember { mutableStateOf(prefs.hideArrowButtons) }
    var homeLongPress by remember { mutableIntStateOf(prefs.homeLongPressAction) }
    var appSortMode by remember { mutableIntStateOf(prefs.appSortMode) }
    var showScreensaverModeSheet by remember { mutableStateOf(false) }
    var showScreensaverCustomSheet by remember { mutableStateOf(false) }
    var hideAllAppsButton by remember { mutableStateOf(prefs.hideAllAppsButton) }
    var swipeUpAllApps by remember { mutableStateOf(prefs.swipeUpAllApps) }
    var homeIslands by remember { mutableStateOf(prefs.homeIslands) }
    var verticalAppGestures by remember { mutableStateOf(prefs.verticalAppGestures) }
    var lockscreenWidget by remember { mutableStateOf(prefs.lockscreenWidget) }
    var lockscreenMusicWidget by remember { mutableStateOf(prefs.lockscreenMusicWidget) }
    var lockscreenWeather by remember { mutableStateOf(prefs.lockscreenWeather) }
    val widgetSettings = viewModel.lockscreenWidgets
    var showLockscreenWidgetsSheet by remember { mutableStateOf(false) }
    var widgetChoice by remember { mutableStateOf<com.gezimos.katapult.lockscreen.WidgetSettingsRow?>(null) }
    var widgetProviders by remember { mutableStateOf<List<com.gezimos.katapult.lockscreen.WidgetProviderChoice>?>(null) }
    var actionServiceEnabled by remember { mutableStateOf(LockscreenWidgetService.isEnabled(context)) }
    var showLockscreenApps by remember { mutableStateOf(false) }
    var showHiddenApps by remember { mutableStateOf(false) }
    var showSystemApps by remember { mutableStateOf(false) }
    var showPermissionsSheet by remember { mutableStateOf(false) }
    var showConfigSheet by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var lockscreenLatestFirst by remember { mutableStateOf(prefs.lockscreenWidgetLatestFirst) }
    var screensaverIslands by remember { mutableStateOf(prefs.screensaverIslands) }
    var screensaverWallpaper by remember { mutableStateOf(prefs.screensaverWallpaper) }
    val screensaverWallpaperPath = viewModel.screensaverWallpaperPath
    var screensaverEnabled by remember { mutableStateOf(prefs.screensaverEnabled) }
    var screensaverShowClock by remember { mutableStateOf(prefs.screensaverShowClock) }
    var screensaverShowNotifications by remember { mutableStateOf(prefs.screensaverShowNotifications) }
    var screensaverDoubleTapBrightness by remember { mutableStateOf(prefs.screensaverDoubleTapBrightness) }
    var screensaverUpdateMode by remember { mutableIntStateOf(prefs.screensaverUpdateMode) }
    var screensaverUpdateMinutes by remember { mutableIntStateOf(prefs.screensaverUpdateMinutes) }
    var screensaverEinkRefresh by remember { mutableStateOf(prefs.screensaverEinkRefresh) }
    var screensaverOnPower by remember { mutableStateOf(prefs.screensaverOnPower) }
    var screensaverAppIcon by remember { mutableStateOf(ScreensaverShortcut.isIconEnabled(context)) }

    // A switch asked for before the Action Service was set up is applied on return (MainViewModel); re-read them.
    LaunchedEffect(viewModel.actionServiceApplied) {
        lockscreenWidget = prefs.lockscreenWidget
        lockscreenMusicWidget = prefs.lockscreenMusicWidget
        lockscreenWeather = prefs.lockscreenWeather
        hideStatusBarClock = prefs.hideStatusBarClock
        screensaverOnPower = prefs.screensaverOnPower
        doubleTapAction = prefs.doubleTapAction
    }

    // Settings returns refresh status only. No remembered activation/sort replay.
    LaunchedEffect(context) {
        while (true) {
            val serviceOn = LockscreenWidgetService.isEnabled(context)
            viewModel.observeActionService(serviceOn)
            if (actionServiceEnabled != serviceOn) actionServiceEnabled = serviceOn
            kotlinx.coroutines.delay(1000)
        }
    }

    var selectedCategory by rememberSaveable { mutableStateOf<Int?>(null) }

    BackHandler(enabled = selectedCategory != null) { selectedCategory = null }

    val notice = if (viewModel.actionServiceNotice.visible) NoticeContent(
        message = stringResource(R.string.paper_service_off),
        regionLabel = stringResource(R.string.paper_notice_region),
        dismissLabel = stringResource(R.string.paper_notice_dismiss),
        onDismiss = viewModel::dismissActionServiceNotice,
        actionLabel = stringResource(R.string.paper_turn_on),
        onAction = { viewModel.requestActionService(null) },
        announcementPending = viewModel.actionServiceNotice.announcementPending,
        onAnnounced = viewModel::actionServiceNoticeAnnounced,
    ) else null
    val appearance = appearanceCategory(
        isMudita = isMudita, rounded = roundedIcons, iconSize = iconSize, themeMode = themeMode,
        hideAppNames = hideAppNames, hideStatusBar = hideStatusBar, hideStatusClock = hideStatusBarClock,
        einkMode = einkHelperMode, refreshHome = einkRefreshOnHome,
        onShape = { roundedIcons = it; prefs.roundedIcons = it; viewModel.roundedIcons = it },
        onSize = { iconSize = it; prefs.iconSize = it; viewModel.iconSize = it },
        onTheme = {
            themeMode = it; prefs.themeMode = it; viewModel.themeMode = it; viewModel.applyStatusBar(context)
        },
        onHideNames = { hideAppNames = it; prefs.hideAppNames = it },
        onHideStatus = {
            hideStatusBar = it; prefs.hideStatusBar = it; viewModel.applyStatusBar(context)
            LockscreenWidgetService.onLauncherForeground(true)
        },
        onHideClock = { requested ->
            val serviceOn = LockscreenWidgetService.isEnabled(context)
            actionServiceEnabled = serviceOn
            viewModel.observeActionService(serviceOn)
            if (!requested || serviceOn) { hideStatusBarClock = requested; prefs.hideStatusBarClock = requested }
            if (requested && !serviceOn) viewModel.requestActionService(AccessibilityFeature.CLOCK_COVER)
            LockscreenWidgetService.onLauncherForeground(true)
        },
        onEink = { einkHelperMode = it; prefs.einkHelperMode = it; (context as? MainActivity)?.setMeinkMode(it) },
        onRefreshHome = { einkRefreshOnHome = it; prefs.einkRefreshOnHome = it },
    )

    val home = homeCategory(clockFormat, dateFormat, showAlarm, showBattery, weatherSource != null, showWeather,
        weatherCelsius, homeExtraRow, homeIslands, disableMusicWidget,
        onClock = { clockFormat = it; prefs.clockFormat = it; viewModel.updateClock() },
        onDate = { dateFormat = it; prefs.dateFormat = it; viewModel.updateClock() },
        onAlarm = { showAlarm = it; prefs.showAlarm = it }, onBattery = { showBattery = it; prefs.showBattery = it },
        onWeather = { showWeather = it; prefs.showWeather = it; viewModel.refreshWeather(force = true) },
        onCelsius = { weatherCelsius = it; prefs.weatherCelsius = it },
        onDock = { homeExtraRow = it; prefs.homeExtraRow = it }, onBackgrounds = { homeIslands = it; prefs.homeIslands = it },
        onMusic = { disableMusicWidget = !it; prefs.disableMusicWidget = !it })
    val apps = appsCategory(isMudita, appSortMode, showKatapultIcon, hideAllAppsButton, infiniteScroll, hideArrowButtons,
        onSort = {
            appSortMode = it; prefs.appSortMode = it; viewModel.loadApps()
            if (it != PrefsManager.APP_SORT_MANUAL && !UsageHelper.hasPermission(context)) UsageHelper.openSettings(context)
        },
        onIcon = { showKatapultIcon = it; prefs.showKatapultIcon = it; viewModel.loadApps() },
        onHidden = { showHiddenApps = true }, onSystem = { showSystemApps = true },
        onButton = { hideAllAppsButton = it; prefs.hideAllAppsButton = it },
        onInfinite = { infiniteScroll = it; prefs.infiniteScroll = it }, onArrows = { hideArrowButtons = it; prefs.hideArrowButtons = it })
    val gestures = gesturesCategory(swipeUpAllApps, verticalAppGestures, doubleTapAction, homeLongPress,
        onSwipe = { swipeUpAllApps = it; prefs.swipeUpAllApps = it },
        onVertical = { verticalAppGestures = it; prefs.verticalAppGestures = it },
        onDoubleTap = {
            doubleTapAction = it; prefs.doubleTapAction = it
            if (it == PrefsManager.DOUBLE_TAP_LOCK && !LockscreenWidgetService.isEnabled(context))
                viewModel.requestActionService(AccessibilityFeature.DOUBLE_TAP_LOCK)
        }, onLongPress = { homeLongPress = it; prefs.homeLongPressAction = it })
    val notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    val notifications = notificationsCategory(notificationIndicators, notificationAccess,
        onIndicators = { requested ->
            notificationIndicators = requested; prefs.notificationIndicators = requested
            if (requested && !NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)) {
                try { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                catch (_: Exception) { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }) }
            }
        }, onApps = {
            try { context.startActivity(Intent().apply { component = ComponentName("com.android.settings", "com.android.settings.Settings\$NotificationAppListActivity") }) }
            catch (_: Exception) {}
        }, onLog = {
            try { context.startActivity(Intent().apply { component = ComponentName("com.android.settings", "com.android.settings.Settings\$NotificationStationActivity") }) }
            catch (_: Exception) {}
        }, onHistory = { try { context.startActivity(Intent("android.settings.NOTIFICATION_HISTORY")) } catch (_: Exception) {} })
    val lock = lockCategory(isMudita, weatherSource != null, lockscreenWidget, lockscreenLatestFirst,
        lockscreenMusicWidget, lockscreenWeather, widgetSettings.enabled, widgetSettings.rows.size,
        onNotifications = { requested ->
            val serviceOn = LockscreenWidgetService.isEnabled(context)
            actionServiceEnabled = serviceOn; viewModel.observeActionService(serviceOn)
            if (!requested || serviceOn) { lockscreenWidget = requested; prefs.lockscreenWidget = requested }
            if (requested && !serviceOn) viewModel.requestActionService(AccessibilityFeature.WIDGET)
        }, onApps = { showLockscreenApps = true },
        onLatest = { lockscreenLatestFirst = it; prefs.lockscreenWidgetLatestFirst = it },
        onMusic = { requested ->
            val serviceOn = LockscreenWidgetService.isEnabled(context)
            actionServiceEnabled = serviceOn; viewModel.observeActionService(serviceOn)
            if (!requested || serviceOn) { lockscreenMusicWidget = requested; prefs.lockscreenMusicWidget = requested }
            if (requested && !serviceOn) viewModel.requestActionService(AccessibilityFeature.MEDIA)
        }, onWeather = { requested ->
            val serviceOn = LockscreenWidgetService.isEnabled(context)
            actionServiceEnabled = serviceOn; viewModel.observeActionService(serviceOn)
            if (!requested || serviceOn) { lockscreenWeather = requested; prefs.lockscreenWeather = requested }
            if (requested && !serviceOn) viewModel.requestActionService(AccessibilityFeature.WEATHER)
        }, onWidgets = { widgetSettings.refresh(); showLockscreenWidgetsSheet = true }, onMessage = viewModel::beginLockscreenMessage)
    val screensaver = screensaverCategory(screensaverEnabled, screensaverShowClock, screensaverShowNotifications,
        screensaverIslands, screensaverEinkRefresh, screensaverWallpaper, screensaverWallpaperPath != null,
        screensaverDoubleTapBrightness, screensaverOnPower, screensaverAppIcon, screensaverUpdateMode,
        onEnabled = { screensaverEnabled = it; prefs.screensaverEnabled = it },
        onSystem = { try { context.startActivity(Intent(Settings.ACTION_DREAM_SETTINGS)) } catch (_: Exception) {} },
        onCustomize = { showScreensaverCustomSheet = true }, onWallpaper = { screensaverWallpaper = it; prefs.screensaverWallpaper = it },
        onImage = { if (screensaverWallpaperPath != null) viewModel.clearScreensaverWallpaper() else screensaverWallpaperPicker.launch("image/*") },
        onBrightness = { screensaverDoubleTapBrightness = it; prefs.screensaverDoubleTapBrightness = it },
        onPower = { requested ->
            val serviceOn = LockscreenWidgetService.isEnabled(context)
            actionServiceEnabled = serviceOn; viewModel.observeActionService(serviceOn)
            if (!requested || serviceOn) { screensaverOnPower = requested; prefs.screensaverOnPower = requested }
            if (requested && !serviceOn) viewModel.requestActionService(AccessibilityFeature.POWER_SCREENSAVER)
        }, onIcon = { screensaverAppIcon = it; ScreensaverShortcut.setIconEnabled(context, it) },
        onMode = {
            screensaverUpdateMode = it; prefs.screensaverUpdateMode = it
            if (it == PrefsManager.SCREENSAVER_MODE_INTERVAL) showScreensaverModeSheet = true
        })
    val system = systemCategory(onSensitive = { showSensitiveAccess = true }, onPermissions = { showPermissionsSheet = true },
        onDefault = { context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) },
        onSearch = {
            try { context.startActivity(Intent(Settings.ACTION_APP_SEARCH_SETTINGS)) }
            catch (_: Exception) { try { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {} }
        }, onConfig = { showConfigSheet = true },
        onAppLicense = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/gezimos/Katapult/blob/main/LICENSE"))) },
        onFontLicense = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/gezimos/Katapult/blob/main/LICENSE-Lato.txt"))) })
    val about = aboutCategory(versionName,
        onSource = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/alexindigo/kotatsu"))) },
        onUpstream = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/gezimos/Katapult"))) })
    val chapters = linkedMapOf(R.string.section_appearance to appearance, R.string.section_home to home,
        R.string.section_all_apps to apps, R.string.section_gestures to gestures, R.string.section_notifications to notifications,
        R.string.section_experimental to lock, R.string.section_screensaver to screensaver, R.string.section_system to system,
        R.string.section_about to about)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalSurface.current)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        if (selectedCategory == null) {
            val entries = chapters.map { (id, chapter) ->
                SettingsEntry.Open(chapter.id, chapter.title, "", onOpen = { selectedCategory = id }, summary = chapter.summary)
            } + store
            TableOfContents(stringResource(R.string.settings), entries, notice, Modifier.weight(1f))
        } else {
            val chapter = chapters.getValue(selectedCategory!!)
            val chapterNotice = notice.takeIf { chapter.id in setOf("appearance", "lock", "screensaver") && (chapter.id != "screensaver" || screensaverEnabled) }
            PaperIndexRenderer(chapter, { selectedCategory = null }, Modifier.weight(1f), chapterNotice,
                when (chapter.id) { "appearance" -> "screen"; "screensaver" -> "interaction"; else -> null })
        }
    }

    if (showLockscreenApps) {
        LockscreenAppsDialog(
            viewModel = viewModel,
            onDismiss = { showLockscreenApps = false },
        )
    }

    if (showHiddenApps) {
        HiddenAppsDialog(
            viewModel = viewModel,
            onDismiss = { showHiddenApps = false },
        )
    }

    if (showSystemApps) {
        SystemAppsDialog(
            viewModel = viewModel,
            onDismiss = { showSystemApps = false },
        )
    }

    if (showLockscreenWidgetsSheet && widgetChoice == null && widgetProviders == null) {
        SheetPage(stringResource(R.string.lockscreen_widgets), { showLockscreenWidgetsSheet = false }) {
            SheetText(androidx.compose.ui.res.pluralStringResource(R.plurals.paper_widgets_configured,
                widgetSettings.rows.size, widgetSettings.rows.size))
            Entry(SettingsEntry.Switch("show", stringResource(R.string.lockscreen_widgets_show),
                stringResource(R.string.lockscreen_widgets_show_desc), widgetSettings.enabled,
                { on ->
                    widgetSettings.requestEnabled(on) { viewModel.requestActionService(AccessibilityFeature.LOCKSCREEN_WIDGETS) }
                }))
            widgetSettings.rows.forEach { row ->
                Entry(SettingsEntry.Open(row.widget.id.toString(), row.label,
                    stringResource(R.string.paper_widget_manage_help), { widgetChoice = row },
                    summary = stringResource(widgetSizeTitle(row.widget.size))))
            }
            Entry(SettingsEntry.Open("add", stringResource(R.string.lockscreen_widgets_add),
                stringResource(R.string.lockscreen_widgets_add_desc), { widgetProviders = widgetSettings.providers() }))
        }
    }
    widgetChoice?.let { row ->
        val index = widgetSettings.rows.indexOfFirst { it.widget.id == row.widget.id }
        LockscreenWidgetMenu(
            row = row,
            canMoveUp = index > 0,
            canMoveDown = index in 0 until widgetSettings.rows.lastIndex,
            onResize = { size -> widgetSettings.resize(row.widget.id, size) },
            onMove = { delta -> widgetSettings.move(row.widget.id, delta) },
            onEdit = { (context as? MainActivity)?.editLockscreenWidget(row.widget.id) },
            onRemove = { widgetSettings.remove(row.widget.id) },
            onDismiss = { widgetChoice = null },
        )
    }
    widgetProviders?.let { providers ->
        WidgetProviderSheet(
            providers = providers,
            onPick = { provider -> (context as? MainActivity)?.addLockscreenWidget(provider.info) },
            onDismiss = { widgetProviders = null },
        )
    }

    val messageDraft = viewModel.lockscreenMessageDraft
    if (viewModel.showLockscreenMessageSheet && messageDraft != null) androidx.compose.runtime.key(messageDraft) {
        LockscreenMessageSheet(
            textFieldValue = messageDraft.value,
            onValueChange = { messageDraft.value = it },
            onDismiss = {
                if (viewModel.lockscreenMessageDraft === messageDraft) {
                    if (viewModel.showLockscreenMessageSheet) viewModel.lockscreenMessageDraft = null
                    viewModel.showLockscreenMessageSheet = false
                }
            },
            onConfirm = { text ->
                if (viewModel.lockscreenMessageDraft === messageDraft) {
                    viewModel.showLockscreenMessageSheet = false
                    viewModel.lockscreenMessageDraft = null
                    val serviceOn = LockscreenWidgetService.isEnabled(context)
                    actionServiceEnabled = serviceOn
                    prefs.lockscreenMessage = text
                    if (text.isNotBlank() && !serviceOn) viewModel.requestActionService(AccessibilityFeature.MESSAGE)
                }
            },
        )
    }

    if (showSensitiveAccess) {
        SheetPage(stringResource(R.string.sensitive_access), { showSensitiveAccess = false }) {
            SheetText(stringResource(R.string.policy_boundary))
            for ((choice, title) in sensitiveRows) {
                val grant = when (choice) {
                    SensitiveChoice.SMS_BADGES -> context.checkSelfPermission(android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    SensitiveChoice.CALL_BADGES -> context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    SensitiveChoice.CALL_ACKNOWLEDGMENT -> context.checkSelfPermission(android.Manifest.permission.WRITE_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    SensitiveChoice.USAGE_STATS -> UsageHelper.hasPermission(context)
                    SensitiveChoice.ACCESSIBILITY_FEATURES -> LockscreenWidgetService.isEnabled(context)
                    else -> NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
                }
                val supported = choice !in setOf(SensitiveChoice.SMS_BADGES, SensitiveChoice.CALL_BADGES,
                    SensitiveChoice.CALL_ACKNOWLEDGMENT) || isMudita
                val connected = when (choice) {
                    SensitiveChoice.NOTIFICATION_BADGES, SensitiveChoice.NOTIFICATION_DISMISSAL,
                    SensitiveChoice.MEDIA_INFO, SensitiveChoice.MEDIA_CONTROLS -> SensitiveAccess.notificationConnected
                    SensitiveChoice.ACCESSIBILITY_FEATURES -> SensitiveAccess.accessibilityConnected
                    else -> true
                }
                val status = stringResource(if (!supported) R.string.policy_unsupported else if (!grant)
                    R.string.policy_not_granted else if (!connected) R.string.policy_granted_disconnected else R.string.policy_granted)
                Entry(SettingsEntry.Switch(choice.key, stringResource(title), status,
                    policyChoices.getValue(choice.key), { prefs.setSensitiveChoice(choice, it) }))
            }
            SheetText(stringResource(R.string.policy_action_boundary))
            Entry(SettingsEntry.Open("permissions", stringResource(R.string.permissions),
                stringResource(R.string.policy_grants_separate),
                { showSensitiveAccess = false; showPermissionsSheet = true }))
        }
    }

    viewModel.pendingImportPreview?.let { preview ->
        val dismissImport = {
            // HOME removes Settings; only an explicit dismissal in Settings cancels the preview.
            if (viewModel.screen == com.gezimos.katapult.Screen.SETTINGS) {
                viewModel.pendingImportPreview = null
                viewModel.pendingImportText = null
            }
        }
        SheetPage(stringResource(R.string.policy_import_confirmation), dismissImport) {
            SheetText(stringResource(R.string.config_import_replaces_settings))
            for ((choice, title) in sensitiveRows) {
                preview.changes[choice.key]?.let { incoming ->
                    Entry(SettingsEntry.Metadata(choice.key, stringResource(title),
                        stringResource(if (incoming) R.string.policy_enabled else R.string.policy_disabled)))
                }
            }
            SheetAction(stringResource(R.string.config_import)) {
                val text = viewModel.pendingImportText
                val current = text?.let { prefs.previewPolicyImport(it) }
                if (current != null && current.localChoices != preview.localChoices) {
                    viewModel.pendingImportPreview = current
                } else if (text != null && current != null && applyImport(text, preview.localChoices)) {
                    viewModel.pendingImportPreview = null; viewModel.pendingImportText = null
                } else {
                    Toast.makeText(context, R.string.config_import_failed, Toast.LENGTH_SHORT).show()
                }
            }
            SheetAction(stringResource(R.string.cancel), dismissImport)
        }
    }

    if (showPermissionsSheet) {
        var permTick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(2000)
                permTick++
            }
        }
        val notifAccess = remember(permTick) {
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        }
        val serviceOn = remember(permTick) { LockscreenWidgetService.isEnabled(context) }
        val callLog = remember(permTick) {
            context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        val sms = remember(permTick) {
            context.checkSelfPermission(android.Manifest.permission.READ_SMS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        val writeSettings = remember(permTick) { Settings.System.canWrite(context) }
        val usageAccess = remember(permTick) { UsageHelper.hasPermission(context) }
        val openAppDetails = {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                )
            } catch (_: Exception) {}
        }
        SheetPage(stringResource(R.string.permissions), { showPermissionsSheet = false }) {
            SheetText(stringResource(R.string.paper_permissions_hint))
            GroupHeading(stringResource(R.string.paper_group_android_access))
            PermissionRow(
                title = stringResource(R.string.perm_notification_access),
                description = stringResource(R.string.perm_notification_access_desc),
                checked = notifAccess,
            ) {
                try {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (_: Exception) {}
            }
            PermissionRow(
                title = stringResource(R.string.perm_action_service),
                description = stringResource(R.string.perm_action_service_desc),
                checked = serviceOn,
                service = true,
            ) {
                // Off: the guided setup. On: straight to Android's list, to manage or turn it off.
                if (!serviceOn) viewModel.requestActionService(null)
                else try {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: Exception) {}
            }
            PermissionRow(
                title = stringResource(R.string.perm_call_log),
                description = stringResource(R.string.perm_call_log_desc),
                checked = callLog,
                onClick = openAppDetails,
            )
            PermissionRow(
                title = stringResource(R.string.perm_sms),
                description = stringResource(R.string.perm_sms_desc),
                checked = sms,
                onClick = openAppDetails,
            )
            PermissionRow(
                title = stringResource(R.string.perm_write_settings),
                description = stringResource(R.string.perm_write_settings_desc),
                checked = writeSettings,
            ) {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName))
                    )
                } catch (_: Exception) {}
            }
            PermissionRow(
                title = stringResource(R.string.perm_usage_access),
                description = stringResource(R.string.perm_usage_access_desc),
                checked = usageAccess,
            ) {
                UsageHelper.openSettings(context)
            }
        }
    }

    if (showScreensaverModeSheet) {
        var minutesText by remember {
            mutableStateOf(
                androidx.compose.ui.text.input.TextFieldValue(
                    text = screensaverUpdateMinutes.toString(),
                    selection = androidx.compose.ui.text.TextRange(
                        screensaverUpdateMinutes.toString().length,
                    ),
                ),
            )
        }
        SheetPage(stringResource(R.string.screensaver_update_mode), { showScreensaverModeSheet = false }, imePadding = true) {
            listOf(
                PrefsManager.SCREENSAVER_MODE_AUTO to stringResource(R.string.screensaver_mode_auto),
                PrefsManager.SCREENSAVER_MODE_INTERVAL to stringResource(R.string.screensaver_mode_interval),
                PrefsManager.SCREENSAVER_MODE_STATIC to stringResource(R.string.screensaver_mode_static),
            ).forEach { (mode, label) ->
                ChoiceOption(label, screensaverUpdateMode == mode) {
                    screensaverUpdateMode = mode
                    prefs.screensaverUpdateMode = mode
                    if (mode != PrefsManager.SCREENSAVER_MODE_INTERVAL) {
                        showScreensaverModeSheet = false
                    }
                }
            }
            if (screensaverUpdateMode == PrefsManager.SCREENSAVER_MODE_INTERVAL) {
                Text(
                    text = stringResource(R.string.screensaver_update_interval),
                    fontSize = 14.sp,
                    fontFamily = LatoFamily,
                    color = LocalInk.current,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                )
                BasicTextField(
                    value = minutesText,
                    onValueChange = { v ->
                        val digits = v.text.filter { it.isDigit() }.take(2)
                        minutesText = v.copy(text = digits)
                        val parsed = digits.toIntOrNull()
                        if (parsed != null && parsed > 0) {
                            screensaverUpdateMinutes = parsed
                            prefs.screensaverUpdateMinutes = parsed
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = TextStyle(
                        fontSize = 18.sp,
                        fontFamily = LatoFamily,
                        color = LocalInk.current,
                    ),
                    cursorBrush = SolidColor(LocalInk.current),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(2.5.dp, LocalInk.current)
                        .padding(12.dp),
                )
            }
        }
    }

    if (showScreensaverCustomSheet) {
        MultiChoiceSheet(
            title = stringResource(R.string.screensaver_customization),
            options = listOf(
                Triple(
                    stringResource(R.string.screensaver_show_clock),
                    screensaverShowClock,
                    { v: Boolean -> screensaverShowClock = v; prefs.screensaverShowClock = v },
                ),
                Triple(
                    stringResource(R.string.screensaver_show_notifications),
                    screensaverShowNotifications,
                    { v: Boolean ->
                        screensaverShowNotifications = v
                        prefs.screensaverShowNotifications = v
                    },
                ),
                Triple(
                    stringResource(R.string.home_islands),
                    screensaverIslands,
                    { v: Boolean -> screensaverIslands = v; prefs.screensaverIslands = v },
                ),
                Triple(
                    stringResource(R.string.screensaver_eink_refresh),
                    screensaverEinkRefresh,
                    { v: Boolean ->
                        screensaverEinkRefresh = v
                        prefs.screensaverEinkRefresh = v
                    },
                ),
            ),
            onDismiss = { showScreensaverCustomSheet = false },
        )
    }

    if (showConfigSheet) {
        SheetPage(stringResource(R.string.config_backup), { showConfigSheet = false }) {
            GroupHeading(stringResource(R.string.paper_group_transfer))
            Entry(SettingsEntry.Open("export", stringResource(R.string.config_export), stringResource(R.string.paper_export_help), {
                showConfigSheet = false
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US)
                    .format(java.util.Date())
                configExporter.launch("kotatsu-config-$stamp.json")
            }, actionLabel = stringResource(R.string.paper_save_file)))
            Entry(SettingsEntry.Open("import", stringResource(R.string.config_import), stringResource(R.string.paper_import_help), {
                showConfigSheet = false
                configImporter.launch(arrayOf("application/json", "text/plain", "*/*"))
            }, actionLabel = stringResource(R.string.paper_choose_file)))
            Spacer(Modifier.height(9.dp))
            GroupHeading(stringResource(R.string.paper_group_reset))
            Entry(SettingsEntry.Open("clear", stringResource(R.string.config_clear), stringResource(R.string.paper_clear_help), {
                showConfigSheet = false
                showClearConfirm = true
            }))
        }
    }

    if (showClearConfirm) {
        SheetPage(stringResource(R.string.config_clear), { showClearConfirm = false }) {
            SheetText(stringResource(R.string.config_clear_confirm))
            SheetAction(stringResource(R.string.config_clear)) {
                showClearConfirm = false
                prefs.clearAll(context)
                IconUtility.invalidateOverrides()
                restartApp(context)
            }
        }
    }
}

private fun restartApp(context: Context) {
    val intent = Intent.makeRestartActivityTask(
        ComponentName(context, MainActivity::class.java)
    )
    context.startActivity(intent)
}

/**
 * Allowlist for the experimental lockscreen widget: checked apps appear on it.
 * Same layout as HiddenAppsDialog; stores the UNCHECKED apps so new installs
 * show up by default.
 */
@Composable
private fun LockscreenAppsDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // Launcher apps plus any package currently posting notifications — system packages
    // like Settings post notifications without having a launcher entry, and they must
    // be listed here to be deselectable.
    val allApps = remember {
        val launcherApps = AppLoader.loadApps(context)
        val known = launcherApps.map { it.packageName }.toSet()
        val extras = (com.gezimos.katapult.service.NotificationListener.getAllCounts().keys +
                viewModel.prefs.getLockscreenExcludedApps())
            .filter { it !in known }
            .distinct()
            .mapNotNull { pkg ->
                try {
                    val pm = context.packageManager
                    com.gezimos.katapult.model.AppModel(
                        packageName = pkg,
                        label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(),
                        activityName = "",
                    )
                } catch (_: Exception) {
                    null
                }
            }
        (launcherApps + extras).sortedBy { it.label.lowercase() }
    }
    var excludedSet by remember { mutableStateOf(viewModel.prefs.getLockscreenExcludedApps()) }
    AppChecklistPage(stringResource(R.string.lockscreen_widget_apps), allApps,
        allApps.filter { it.packageName !in excludedSet }.map { it.key }.toSet(), onDismiss,
        onToggle = { app ->
            val updated = if (app.packageName !in excludedSet) excludedSet + app.packageName
                else excludedSet - app.packageName
            viewModel.prefs.setLockscreenExcludedApps(updated)
            excludedSet = updated
        },
        selectAllLabel = stringResource(if (excludedSet.isEmpty()) R.string.paper_deselect_all else R.string.paper_select_all),
        onSelectAll = {
            val updated = if (excludedSet.isEmpty()) allApps.map { it.packageName }.toSet() else emptySet()
            viewModel.prefs.setLockscreenExcludedApps(updated)
            excludedSet = updated
        })
}

/** Hidden Apps: apps the user installed (and pinned shortcuts); a checked box hides the app. */
@Composable
private fun HiddenAppsDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val apps = remember {
        (AppLoader.loadApps(context).filter { !it.stock } + ShortcutHelper.installedShortcuts(context, viewModel.prefs))
            .sortedBy { it.label.lowercase() }
    }
    AppVisibilitySheet(viewModel, apps, R.string.hidden_apps, R.string.hidden_apps_count, onDismiss)
}

/** System Apps: the stock apps (see StockApps), hidden until unchecked here. */
@Composable
private fun SystemAppsDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val apps = remember { AppLoader.loadApps(context).filter { it.stock }.sortedBy { it.label.lowercase() } }
    AppVisibilitySheet(viewModel, apps, R.string.system_apps, R.string.system_apps_count, onDismiss)
}

/** Checked = hidden from the grid, by the user or by default (AppVisibility). */
@Composable
private fun AppVisibilitySheet(
    viewModel: MainViewModel,
    apps: List<com.gezimos.katapult.model.AppModel>,
    titleRes: Int,
    countRes: Int,
    onDismiss: () -> Unit,
) {
    var hidden by remember { mutableStateOf(viewModel.prefs.getHiddenApps()) }
    var shown by remember { mutableStateOf(viewModel.prefs.getShownDefaultHiddenApps()) }
    var notifyWhileHidden by remember { mutableStateOf(viewModel.prefs.getNotifyWhileHiddenApps()) }
    var excluded by remember { mutableStateOf(viewModel.prefs.getLockscreenExcludedApps()) }
    DisposableEffect(viewModel.prefs) {
        val subscription = viewModel.prefs.subscribeChanges {
            hidden = viewModel.prefs.getHiddenApps()
            shown = viewModel.prefs.getShownDefaultHiddenApps()
            notifyWhileHidden = viewModel.prefs.getNotifyWhileHiddenApps()
            excluded = viewModel.prefs.getLockscreenExcludedApps()
        }
        onDispose { subscription.close() }
    }
    val checked = apps.filter { AppVisibility.isHidden(it, hidden, shown) }.map { it.key }.toSet()
    AppChecklistPage(
        title = if (checked.isNotEmpty()) stringResource(countRes, checked.size) else stringResource(titleRes),
        apps = apps,
        checked = checked,
        onDismiss = onDismiss,
        notifyCell = { app ->
            when {
                app.shortcutId.isNotEmpty() || app.key !in checked -> NotifyCell.NONE
                app.packageName in excluded -> NotifyCell.DISABLED
                app.packageName in notifyWhileHidden -> NotifyCell.ON
                else -> NotifyCell.OFF
            }
        },
        onToggleNotify = { app ->
            viewModel.prefs.setNotifyWhileHidden(app.packageName, app.packageName !in notifyWhileHidden)
            notifyWhileHidden = viewModel.prefs.getNotifyWhileHiddenApps()
        },
        onToggle = { app ->
            viewModel.prefs.setAppHidden(app.key, hidden = app.key !in checked, AppVisibility.hiddenByDefault(app))
            hidden = viewModel.prefs.getHiddenApps()
            shown = viewModel.prefs.getShownDefaultHiddenApps()
            viewModel.loadApps()
        },
    )
}

/** Kept unchanged for Onboarding, its only consumer. Settings uses the Paper-index kit. */
@Composable
fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Switch
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            }
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = LatoFamily,
                color = LocalInk.current,
            )
            Text(
                text = description,
                fontSize = 14.sp,
                fontFamily = LatoFamily,
                color = LocalInk.current,
            )
        }
        Spacer(Modifier.width(16.dp))
        // Toggle: pill track with sliding dot
        val trackShape = RoundedCornerShape(12.dp)
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 22.dp)
                .then(
                    if (checked) Modifier.background(LocalInk.current, trackShape)
                    else Modifier.border(2.dp, LocalInk.current, trackShape)
                )
                .clickable { onCheckedChange(!checked) },
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .size(14.dp)
                    .background(
                        if (checked) LocalSurface.current else LocalInk.current,
                        CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun LockscreenMessageSheet(
    textFieldValue: androidx.compose.ui.text.input.TextFieldValue,
    onValueChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    SheetPage(stringResource(R.string.lockscreen_message), onDismiss, imePadding = true) {
        BasicTextField(
            value = textFieldValue,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 18.sp, fontFamily = LatoFamily, color = LocalInk.current),
            cursorBrush = SolidColor(LocalInk.current),
            modifier = Modifier
                .fillMaxWidth()
                .border(2.5.dp, LocalInk.current)
                .padding(12.dp)
                .focusRequester(focusRequester),
        )
        SheetAction(stringResource(R.string.save)) { onConfirm(textFieldValue.text.trim()) }
        SheetAction(stringResource(R.string.cancel), onDismiss)
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    checked: Boolean,
    service: Boolean = false,
    onClick: () -> Unit,
) {
    val status = stringResource(if (service) {
        if (checked) R.string.paper_on else R.string.paper_off
    } else if (checked) R.string.paper_granted else R.string.paper_not_granted)
    Entry(SettingsEntry.Open(title, title, description, onClick, actionLabel = status), minimumHeight = 63.dp)
}

@Composable
internal fun MultiChoiceSheet(
    title: String,
    options: List<Triple<String, Boolean, (Boolean) -> Unit>>,
    onDismiss: () -> Unit,
) {
    SheetPage(title, onDismiss) {
        options.forEach { (label, checked, onToggle) ->
            CheckboxOption(label, checked, onToggle)
        }
    }
}

/** A mixed menu: size is a choice; moving, editing and removing remain actions. */
@Composable
internal fun LockscreenWidgetMenu(
    row: com.gezimos.katapult.lockscreen.WidgetSettingsRow,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onResize: (com.gezimos.katapult.lockscreen.LockscreenWidgetSize) -> Unit,
    onMove: (Int) -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    SheetPage(row.label, onDismiss) {
        com.gezimos.katapult.lockscreen.LockscreenWidgetSize.entries.forEach { size ->
            ChoiceOption(stringResource(widgetSizeTitle(size)), size == row.widget.size) {
                onResize(size); onDismiss()
            }
        }
        if (canMoveUp) SheetAction(stringResource(R.string.lockscreen_widgets_up)) {
            onMove(-1); onDismiss()
        }
        if (canMoveDown) SheetAction(stringResource(R.string.lockscreen_widgets_down)) {
            onMove(1); onDismiss()
        }
        if (row.canEdit) SheetAction(stringResource(R.string.lockscreen_widgets_edit)) {
            onEdit(); onDismiss()
        }
        SheetAction(stringResource(R.string.lockscreen_widgets_remove)) {
            onRemove(); onDismiss()
        }
    }
}

/** A plain pick list: nothing is selected yet, so no radio marks. */
@Composable
internal fun WidgetProviderSheet(
    providers: List<com.gezimos.katapult.lockscreen.WidgetProviderChoice>,
    onPick: (com.gezimos.katapult.lockscreen.WidgetProviderChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    SheetPage(stringResource(R.string.lockscreen_widgets_add), onDismiss) {
        providers.forEach { provider -> SheetAction(provider.label) { onPick(provider); onDismiss() } }
    }
}

private fun widgetSizeTitle(size: com.gezimos.katapult.lockscreen.LockscreenWidgetSize): Int = when (size) {
    com.gezimos.katapult.lockscreen.LockscreenWidgetSize.S -> R.string.lockscreen_widgets_small
    com.gezimos.katapult.lockscreen.LockscreenWidgetSize.M -> R.string.lockscreen_widgets_medium
    com.gezimos.katapult.lockscreen.LockscreenWidgetSize.L -> R.string.lockscreen_widgets_large
}
