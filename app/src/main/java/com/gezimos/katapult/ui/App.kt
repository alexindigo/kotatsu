package com.gezimos.katapult.ui

import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.gezimos.katapult.R
import androidx.compose.ui.unit.dp
import com.gezimos.katapult.MainViewModel
import com.gezimos.katapult.Screen

@Composable
fun App(
    viewModel: MainViewModel,
    imagePicker: ActivityResultLauncher<String>,
    iconPicker: ActivityResultLauncher<Array<String>>,
    configExporter: ActivityResultLauncher<String>,
    configImporter: ActivityResultLauncher<Array<String>>,
    screensaverWallpaperPicker: ActivityResultLauncher<String>,
    onClearBadge: (String) -> Unit,
) {
    val iconShape = appIconShape(viewModel.roundedIcons)
    val badgeShape = if (viewModel.roundedIcons) RoundedBadgeShape else CircleShape
    val palette = Appearance.current(Appearance.Surface.APP, viewModel.prefs)
    val dark = palette.isDark
    CompositionLocalProvider(
        androidx.compose.foundation.LocalIndication provides NoIndication,
        LocalIconSize provides viewModel.iconSize.dp,
        LocalIconShape provides iconShape,
        LocalBadgeShape provides badgeShape,
        LocalSheetDismissSignal provides viewModel.sheetDismissSignal,
        LocalPalette provides palette,
        LocalIconFilter provides if (dark) EinkColorFilterDark else EinkColorFilter,
    ) {
        when (viewModel.screen) {
            Screen.ONBOARDING -> OnboardingScreen(viewModel)
            Screen.HOME -> HomeScreen(viewModel, imagePicker, iconPicker, onClearBadge)
            Screen.ALL_APPS -> AllAppsScreen(viewModel, iconPicker, onClearBadge)
            Screen.SETTINGS -> SettingsScreen(viewModel, configExporter, configImporter, screensaverWallpaperPicker,
                onWallpaperPhoto = { imagePicker.launch("image/*") })
        }
        if (viewModel.screen != Screen.SETTINGS) com.gezimos.katapult.lockscreen.ActionServiceSheet(viewModel)
        if (viewModel.showReorderExit) {
            ReorderExitSheet(
                onSave = viewModel::saveReorderExit,
                onDiscard = viewModel::discardReorderExit,
                onCancel = viewModel::cancelReorderExit,
            )
        }
        if (viewModel.iconImportError) {
            val context = LocalContext.current
            val message = stringResource(R.string.icon_import_failed)
            LaunchedEffect(Unit) {
                android.widget.Toast.makeText(
                    context, message, android.widget.Toast.LENGTH_LONG,
                ).show()
                viewModel.iconImportError = false
            }
        }
    }
}
