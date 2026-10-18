package com.gezimos.katapult.lockscreen

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Settings as SettingsIcon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gezimos.katapult.MainViewModel
import com.gezimos.katapult.R
import com.gezimos.katapult.ui.BottomSheet
import com.gezimos.katapult.ui.BottomSheetOption
import com.gezimos.katapult.ui.LatoFamily
import com.gezimos.katapult.ui.LocalInk

/**
 * The one way Kotatsu sends someone to Android's Accessibility settings. Android opens a single service's page
 * only for system apps, so the sheet names the steps on Android's list: open Kotatsu Action Service, turn on its
 * main switch, and leave its shortcut off (the shortcut adds Android's floating accessibility button).
 */
@Composable
fun ActionServiceSetupSheet(
    reason: String,
    featureTitles: List<String>,
    onOpenSettings: () -> Unit,
    onTurnOffFeatures: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    BottomSheet(onDismiss = onDismiss) {
        Text(
            text = stringResource(R.string.a11y_setup_title),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = LatoFamily,
            color = LocalInk.current,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(reason, fontSize = 14.sp, fontFamily = LatoFamily, color = LocalInk.current,
            modifier = Modifier.padding(bottom = 12.dp))
        // The features waiting for the service: bold, so they read as a list between the reason and the steps.
        for (title in featureTitles) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = LatoFamily, color = LocalInk.current,
                modifier = Modifier.padding(bottom = 8.dp))
        }
        for (step in listOf(R.string.a11y_setup_step_open, R.string.a11y_setup_step_use, R.string.a11y_setup_step_shortcut)) {
            Text(stringResource(step), fontSize = 14.sp, fontFamily = LatoFamily, color = LocalInk.current,
                modifier = Modifier.padding(bottom = 6.dp))
        }
        BottomSheetOption(text = stringResource(R.string.a11y_setup_open), icon = Icons.Rounded.SettingsIcon, onClick = onOpenSettings)
        if (onTurnOffFeatures != null) {
            BottomSheetOption(text = stringResource(R.string.lockscreen_reenable_disable), icon = Icons.Rounded.NotificationsOff,
                onClick = onTurnOffFeatures)
        }
    }
}

/**
 * Shows the setup sheet the view model asks for: recovery when features are selected but Android dropped the
 * service (it does on app updates), or a feature the user just turned on while the service was off.
 */
@Composable
fun ActionServiceSheet(viewModel: MainViewModel) {
    val context = LocalContext.current
    val request = viewModel.actionServiceSetup
    when {
        viewModel.showLockscreenReEnable -> ActionServiceSetupSheet(
            reason = stringResource(R.string.lockscreen_reenable_message),
            featureTitles = viewModel.lockscreenServiceFeatureTitles().map { stringResource(it) },
            onOpenSettings = { viewModel.openActionServiceSettings(context) },
            onTurnOffFeatures = viewModel::disableLockscreenServiceFeatures,
            onDismiss = viewModel::resolveLockscreenReEnable,
        )
        request != null -> ActionServiceSetupSheet(
            reason = stringResource(request.reasonRes),
            featureTitles = emptyList(),
            onOpenSettings = { viewModel.openActionServiceSettings(context) },
            onTurnOffFeatures = null,
            onDismiss = { viewModel.actionServiceSetup = null },
        )
    }
}
