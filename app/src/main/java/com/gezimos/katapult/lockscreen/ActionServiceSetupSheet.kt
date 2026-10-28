package com.gezimos.katapult.lockscreen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gezimos.katapult.MainViewModel
import com.gezimos.katapult.R
import com.gezimos.katapult.ui.LatoFamily
import com.gezimos.katapult.ui.LocalInk
import com.gezimos.katapult.ui.BottomSheet
import com.gezimos.katapult.ui.settings.SettingsPage
import com.gezimos.katapult.ui.settings.SheetText
import com.gezimos.katapult.ui.settings.SheetAction
import com.gezimos.katapult.ui.settings.Entry
import com.gezimos.katapult.ui.settings.SettingsEntry

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
    asPage: Boolean = false,
) {
    val content: @Composable () -> Unit = {
        SheetText(reason)
        // The features waiting for the service: bold, so they read as a list between the reason and the steps.
        for (title in featureTitles) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = LatoFamily, color = LocalInk.current,
                modifier = Modifier.padding(bottom = 8.dp))
        }
        for (step in listOf(R.string.a11y_setup_step_open, R.string.a11y_setup_step_use, R.string.a11y_setup_step_shortcut)) {
            SheetText(stringResource(step))
        }
        Entry(SettingsEntry.Link("accessibility", stringResource(R.string.a11y_setup_open), "", onOpenSettings))
        if (onTurnOffFeatures != null) {
            SheetAction(stringResource(R.string.lockscreen_reenable_disable), onTurnOffFeatures)
        }
    }
    if (asPage) SettingsPage(stringResource(R.string.a11y_setup_title), onDismiss, content = content)
    else BottomSheet(onDismiss) {
        // Home recovery is outside Settings navigation; preserve its original modal presentation.
        Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.a11y_setup_title), fontFamily = LatoFamily, fontSize = 21.sp,
                lineHeight = 25.5.sp, fontWeight = FontWeight.Bold, color = LocalInk.current,
                modifier = Modifier.padding(bottom = 10.5.dp).semantics { heading() })
            content()
        }
    }
}

/**
 * Shows the setup sheet the view model asks for: recovery when features are selected but the service is off,
 * or a feature the user just turned on while the service was off. The current state does not tell us its cause.
 */
@Composable
fun ActionServiceSheet(viewModel: MainViewModel, asPage: Boolean = false, onPageDismiss: () -> Unit = {}) {
    val context = LocalContext.current
    val request = viewModel.actionServiceSetup
    when {
        viewModel.showLockscreenReEnable -> ActionServiceSetupSheet(
            reason = stringResource(R.string.lockscreen_reenable_message),
            featureTitles = viewModel.lockscreenServiceFeatureTitles().map { stringResource(it) },
            onOpenSettings = { viewModel.openActionServiceSettings(context) },
            onTurnOffFeatures = viewModel::disableLockscreenServiceFeatures,
            onDismiss = { viewModel.resolveLockscreenReEnable(); onPageDismiss() },
            asPage = asPage,
        )
        request != null -> ActionServiceSetupSheet(
            reason = stringResource(request.reasonRes),
            featureTitles = emptyList(),
            onOpenSettings = { viewModel.openActionServiceSettings(context) },
            onTurnOffFeatures = null,
            onDismiss = { viewModel.actionServiceSetup = null; onPageDismiss() },
            asPage = asPage,
        )
    }
}
