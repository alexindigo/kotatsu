package com.gezimos.katapult.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.gezimos.katapult.R
import com.gezimos.katapult.ui.settings.Entry
import com.gezimos.katapult.ui.settings.PaperSheetHeading
import com.gezimos.katapult.ui.settings.SettingsEntry
import com.gezimos.katapult.util.WallpaperSource

/** Both hosts supply destinations; this content knows neither the Activity nor the service. */
@Composable
fun HomeMenu(wallpaper: WallpaperSource, external: Boolean = false,
    onSettings: () -> Unit, onWallpaper: () -> Unit, onAllApps: (() -> Unit)? = null) {
    val muted = LocalPalette.current.muted
    PaperSheetHeading(stringResource(R.string.kotatsu_title))
    Entry(SettingsEntry.Open("settings", stringResource(R.string.settings), "", onSettings,
        external = external), leaderColor = muted)
    if (onAllApps != null) Entry(SettingsEntry.Open("apps", stringResource(R.string.all_apps), "", onAllApps,
        external = external), leaderColor = muted)
    val wallpaperLabel = stringResource(when (wallpaper) {
        WallpaperSource.NONE -> R.string.wallpaper_none
        WallpaperSource.KOTATSU -> R.string.wallpaper_kotatsu
        WallpaperSource.PHOTO -> R.string.wallpaper_photos
    })
    Entry(SettingsEntry.Open("wallpaper", stringResource(R.string.wallpaper), "", onWallpaper,
        actionLabel = wallpaperLabel, external = external), leaderColor = muted)
}
