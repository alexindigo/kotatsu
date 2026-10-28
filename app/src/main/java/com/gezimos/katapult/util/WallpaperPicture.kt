package com.gezimos.katapult.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.gezimos.katapult.R

/** A picture identity shared by Home and the screensaver, independent of their rendering. */
data class WallpaperPicture(val source: WallpaperSource, val photoPath: String? = null) {
    fun decode(context: Context): Bitmap? = when (source) {
        WallpaperSource.NONE -> null
        WallpaperSource.KOTATSU -> BitmapHelper.decodeUri(context,
            Uri.parse("android.resource://${context.packageName}/${R.drawable.kotatsu_wallpaper}"),
            BitmapHelper.screenMaxSide(context))
        WallpaperSource.PHOTO -> photoPath?.let { BitmapHelper.decodeFile(it, BitmapHelper.screenMaxSide(context)) }
    }
}
