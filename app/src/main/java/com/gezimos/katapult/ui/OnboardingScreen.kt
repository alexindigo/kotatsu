package com.gezimos.katapult.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.gezimos.katapult.MainViewModel
import com.gezimos.katapult.R
import com.gezimos.katapult.Screen
import com.gezimos.katapult.util.DeviceHelper

@Composable
fun OnboardingScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val ink = LocalInk.current
    val surface = LocalSurface.current
    val isMudita = remember { DeviceHelper.isMuditaKompakt() }

    var hasNotificationPermission by remember { mutableStateOf(false) }
    var isDefaultLauncher by remember { mutableStateOf(false) }
    var hasSmsPermission by remember { mutableStateOf(false) }
    var hasCallPermission by remember { mutableStateOf(false) }
    val refreshStatus = {
        hasNotificationPermission = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
        isDefaultLauncher = DeviceHelper.isDefaultLauncher(context)
        hasSmsPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        hasCallPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
    }
    val owner = LocalView.current.findViewTreeLifecycleOwner()
    DisposableEffect(owner, context) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) { refreshStatus() }
        }
        owner?.lifecycle?.addObserver(observer)
        refreshStatus()
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    val smsRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshStatus() }
    val callRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshStatus() }
    val openAppSettings = {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(surface)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        // Logo
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.katapult_logo),
                contentDescription = stringResource(R.string.inherited_logo_description),
                tint = ink,
                modifier = Modifier.width(160.dp).height(43.dp),
            )
        }

        Text(
            text = stringResource(R.string.welcome_title),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = LatoFamily,
            color = ink,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(stringResource(R.string.kotatsu_tagline), color = ink, fontFamily = LatoFamily)
        Text(
            stringResource(R.string.onboarding_optional_desc),
            color = ink,
            fontFamily = LatoFamily,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )

        // 1. Notification Indicators
        SettingsToggleRow(
            title = stringResource(R.string.perm_notification_access),
            description = stringResource(R.string.onboarding_notification_desc),
            checked = hasNotificationPermission,
            onCheckedChange = {
                try {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (_: Exception) {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    })
                }
            },
        )

        // 2. Call & SMS Badges (Mudita Kompakt only)
        if (isMudita) {
            SettingsToggleRow(
                title = stringResource(R.string.perm_sms),
                description = stringResource(R.string.perm_sms_desc),
                checked = hasSmsPermission,
                onCheckedChange = {
                    if (hasSmsPermission) openAppSettings() else smsRequest.launch(Manifest.permission.READ_SMS)
                },
            )
            SettingsToggleRow(
                title = stringResource(R.string.perm_call_log),
                description = stringResource(R.string.perm_call_log_desc),
                checked = hasCallPermission,
                onCheckedChange = {
                    if (hasCallPermission) openAppSettings() else callRequest.launch(Manifest.permission.READ_CALL_LOG)
                },
            )
        }

        // 3. Default Launcher
        SettingsToggleRow(
            title = stringResource(R.string.set_default_launcher),
            description = stringResource(R.string.onboarding_launcher_desc),
            checked = isDefaultLauncher,
            onCheckedChange = {
                context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            },
        )

        }
        Spacer(Modifier.height(12.dp))

        // Finish button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(2.5.dp, ink, RoundedCornerShape(10.dp))
                .clickable {
                    viewModel.prefs.onboardingComplete = true
                    viewModel.navigateTo(Screen.HOME)
                }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.finish),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = LatoFamily,
                color = ink,
            )
        }

    }
}
