package com.gezimos.katapult.screenshot

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import android.system.OsConstants
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

fun screenshotFileName(at: Date, zone: TimeZone = TimeZone.getDefault()): String =
    "Screenshot_${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = zone }.format(at)}.png"

enum class ScreenshotFailure { STORAGE_FULL, UNAVAILABLE }

fun screenshotFailure(error: Throwable): ScreenshotFailure {
    var cause: Throwable? = error
    val seen = mutableSetOf<Throwable>()
    while (cause != null && seen.add(cause)) {
        if (cause is SQLiteFullException || cause is ErrnoException && cause.errno == OsConstants.ENOSPC ||
            cause.message?.contains("ENOSPC") == true || cause.message?.contains("No space left on device", ignoreCase = true) == true)
            return ScreenshotFailure.STORAGE_FULL
        cause = cause.cause
    }
    return ScreenshotFailure.UNAVAILABLE
}

/** A pending MediaStore row is published only after the complete PNG has been written. */
class ScreenshotStore(private val resolver: ContentResolver, private val now: () -> Date = ::Date) {
    fun save(bitmap: Bitmap): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, screenshotFileName(now()))
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create screenshot")
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("Could not open screenshot")
            stream.use { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("Could not encode screenshot") }
            if (resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) != 1)
                throw IOException("Could not publish screenshot")
            return uri
        } catch (error: Exception) {
            try { resolver.delete(uri, null, null) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }
}
