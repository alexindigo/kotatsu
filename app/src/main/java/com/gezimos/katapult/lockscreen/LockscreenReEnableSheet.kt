package com.gezimos.katapult.lockscreen

import android.content.Intent
import android.provider.Settings
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
 * Shown when selected features need the accessibility service but it is off —
 * Android drops the grant when the app is updated. Offers to reopen accessibility
 * settings or turn off those features (retaining the lock-screen message).
 */
@Composable
fun LockscreenReEnableSheet(viewModel: MainViewModel) {
    if (!viewModel.showLockscreenReEnable) return
    val context = LocalContext.current
    BottomSheet(onDismiss = { viewModel.showLockscreenReEnable = false }) {
        Text(
            text = stringResource(R.string.a11y_service_label),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = LatoFamily,
            color = LocalInk.current,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            text = stringResource(R.string.lockscreen_reenable_message),
            fontSize = 14.sp,
            fontFamily = LatoFamily,
            color = LocalInk.current,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        for (title in viewModel.lockscreenServiceFeatureTitles()) {
            Text(
                text = stringResource(title),
                fontSize = 14.sp,
                fontFamily = LatoFamily,
                color = LocalInk.current,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        BottomSheetOption(
            text = stringResource(R.string.lockscreen_reenable_enable),
            icon = Icons.Rounded.SettingsIcon,
        ) {
            viewModel.resolveLockscreenReEnable()
            try {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {}
        }
        BottomSheetOption(
            text = stringResource(R.string.lockscreen_reenable_disable),
            icon = Icons.Rounded.NotificationsOff,
        ) {
            viewModel.disableLockscreenServiceFeatures()
        }
    }
}
