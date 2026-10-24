package com.gezimos.katapult.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.gezimos.katapult.R
import com.gezimos.katapult.ui.LatoFamily
import com.gezimos.katapult.ui.LocalInk

/** Android handoffs report failure to their owner, which keeps the explanation visible. */
internal fun openAppStoreIntent(context: Context, intent: Intent): Boolean = try {
    if (intent.resolveActivity(context.packageManager) == null) false else {
        context.startActivity(intent)
        true
    }
} catch (_: ActivityNotFoundException) { false }
catch (_: SecurityException) { false }

/** Owns only the store interaction; its consumer renders the returned Link in the contents. */
@Composable
fun appStoreEntry(packages: AppStorePackages, openIntent: (Intent) -> Boolean): SettingsEntry.Link {
    var installed by remember(packages) { mutableStateOf(preferredFdroidPackage(packages)) }
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var launchFailed by rememberSaveable { mutableStateOf(false) }
    var browserMissing by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalView.current.findViewTreeLifecycleOwner()!!.lifecycle
    DisposableEffect(lifecycle, packages) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                installed = preferredFdroidPackage(packages)
                browserMissing = false
                if (installed != null && !launchFailed) showSheet = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (showSheet) SheetPage(stringResource(if (launchFailed) R.string.app_store_launch_failed else R.string.app_store_missing_title),
        onDismiss = { showSheet = false }) {
        Text(stringResource(R.string.app_store_missing_body), fontFamily = LatoFamily, fontSize = 14.sp,
            lineHeight = 19.5.sp, color = LocalInk.current, modifier = Modifier.padding(bottom = 16.5.dp))
        if (browserMissing) Text(stringResource(R.string.app_store_no_browser), fontFamily = LatoFamily,
            fontSize = 14.sp, lineHeight = 19.5.sp, color = LocalInk.current,
            modifier = Modifier.padding(bottom = 6.dp).semantics { liveRegion = LiveRegionMode.Polite })
        Entry(SettingsEntry.Link("fdroid-website", stringResource(R.string.app_store_open_website), "", onOpen = {
            browserMissing = !openIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/")))
            if (!browserMissing) showSheet = false
        }), minimumHeight = 48.dp)
        SheetAction(stringResource(R.string.cancel)) { showSheet = false }
    }
    return SettingsEntry.Link("app-store", stringResource(R.string.app_store_title), "", onOpen = {
        // Recheck at the action boundary too: a package can disappear while Settings is foreground.
        installed = preferredFdroidPackage(packages)
        val intent = installed?.let(packages::launchIntent)
        launchFailed = installed != null && (intent == null || !openIntent(intent))
        browserMissing = false
        showSheet = installed == null || launchFailed
    }, summary = stringResource(if (installed == null) R.string.app_store_absent else R.string.app_store_open))
}

@Composable
fun rememberAppStorePackages(): AppStorePackages {
    val context = LocalContext.current
    return remember(context) { AndroidAppStorePackages(context.packageManager) }
}
