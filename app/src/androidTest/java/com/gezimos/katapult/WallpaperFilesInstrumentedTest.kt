package com.gezimos.katapult

import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.activity.compose.setContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.util.BitmapHelper
import com.gezimos.katapult.util.IconUtility
import com.gezimos.katapult.ui.SettingsScreen
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WallpaperFilesInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val key = "__f4_owned_icon__"

    private class TestApplication(base: Context, private val store: SharedPreferences, private val root: File) : Application() {
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == "katapult_prefs") store else super.getSharedPreferences(name, mode)
        override fun getFilesDir(): File = root
    }

    private fun withModel(test: (MainViewModel, File) -> Unit) {
        val name = "f4-${UUID.randomUUID()}"
        val store = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val root = File(context.filesDir, name).apply { assertTrue(mkdir()) }
        val owner = ViewModelStore()
        lateinit var model: MainViewModel
        try {
            instrumentation.runOnMainSync {
                model = MainViewModel(TestApplication(context.applicationContext, store, root))
                owner.put("f4", model)
            }
            test(model, root)
        } finally {
            instrumentation.runOnMainSync { owner.clear() }
            IconUtility.clearCacheFor(key)
            assertTrue(root.deleteRecursively())
            assertTrue(context.deleteSharedPreferences(name))
        }
    }

    private fun png(file: File, color: Int): File = file.apply {
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(25)
        }
        assertTrue(message, condition())
    }

    private fun save(model: MainViewModel, source: Context, uri: Uri, screensaver: Boolean): List<kotlinx.coroutines.Job> {
        var jobs: List<kotlinx.coroutines.Job> = emptyList()
        instrumentation.runOnMainSync {
            val parent = model.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!
            val before = parent.children.toSet()
            if (screensaver) model.setScreensaverWallpaper(source, uri) else model.setWallpaper(source, uri)
            jobs = parent.children.filter { it !in before }.toList()
        }
        return jobs
    }

    private fun clear(model: MainViewModel, screensaver: Boolean) {
        instrumentation.runOnMainSync {
            if (screensaver) model.clearScreensaverWallpaper() else model.clearWallpaper()
        }
    }

    private fun path(model: MainViewModel, screensaver: Boolean): String? =
        if (screensaver) model.prefs.screensaverWallpaperPath else model.prefs.wallpaperPath

    @Test fun homeSuccessReachesBitmapConsumer() = success(false)
    @Test fun screensaverSuccessPublishesObservableSettingsState() = success(true)

    @Test fun settingsRowChangesToClearAfterAsyncSaveAndBackAfterClear() = withModel { model, root ->
        model.prefs.screensaverEnabled = true
        model.prefs.screensaverWallpaper = true
        val source = png(File(root, "source.png"), Color.BLUE)
        var activity: MainActivity? = null
        try {
            activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN).apply {
                setClassName(context.packageName, MainActivity::class.java.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }) as MainActivity
            val current = activity
            instrumentation.runOnMainSync { current.setContent { SettingsScreen(model) } }
            instrumentation.waitForIdleSync()
            val ui = PlatformUiDriver(instrumentation)
            ui.await("Fixture Settings grid is not ready") { ui.has(context.getString(R.string.cat_screensaver_sub)) }
            ui.click(context.getString(R.string.screensaver))
            ui.await("Screensaver category is not ready") { ui.has(context.getString(R.string.screensaver_enable_desc)) }
            assertEquals(true, ui.checked(context.getString(R.string.screensaver_enable_desc)))
            ui.scrollTo(context.getString(R.string.screensaver_wallpaper_home))
            save(model, context, Uri.fromFile(source), true)
            ui.await("Async save did not update the visible Settings row") {
                ui.has(context.getString(R.string.clear_image)) && ui.has(context.getString(R.string.screensaver_wallpaper_custom))
            }
            ui.click(context.getString(R.string.clear_image))
            ui.await("Clear did not restore the HOME wallpaper row") {
                ui.has(context.getString(R.string.screensaver_wallpaper_home))
            }
            assertNull(model.prefs.screensaverWallpaperPath)
            assertNull(model.screensaverWallpaperPath)
        } finally { instrumentation.runOnMainSync { activity?.finish() } }
    }

    private fun success(screensaver: Boolean) = withModel { model, root ->
        val source = png(File(root, "source.png"), Color.BLUE)
        save(model, context, Uri.fromFile(source), screensaver)
        await("Save did not reach wallpaper preference") { path(model, screensaver) != null }
        if (screensaver) {
            val observed = MainViewModel::class.java.getMethod("getScreensaverWallpaperPath").invoke(model)
            assertEquals(path(model, true), observed)
            assertEquals(Color.BLUE, BitmapHelper.decodeFile(observed as String, 64)!!.getPixel(12, 12))
        } else assertEquals(Color.BLUE, model.wallpaperBitmap!!.getPixel(12, 12))
        assertFalse(root.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test fun olderHomePickCannotReplaceNewerPick() = stale(false, false)
    @Test fun olderScreensaverPickCannotReplaceNewerPick() = stale(true, false)
    @Test fun clearHomeInvalidatesInFlightPick() = stale(false, true)
    @Test fun clearScreensaverInvalidatesInFlightPick() = stale(true, true)

    private fun stale(screensaver: Boolean, clear: Boolean) = withModel { model, root ->
        val red = png(File(root, "red.png"), Color.RED)
        val blue = png(File(root, "blue.png"), Color.BLUE)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "image/png"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS))
                completed.countDown()
                return ParcelFileDescriptor.open(red, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
        val source = object : ContextWrapper(context) {
            override fun getContentResolver(): ContentResolver = ContentResolver.wrap(provider)
        }
        try {
            val jobs = save(model, source, Uri.parse("content://f4/red"), screensaver)
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            if (clear) clear(model, screensaver) else {
                save(model, context, Uri.fromFile(blue), screensaver)
                await("New pick did not publish") { path(model, screensaver) != null }
            }
            release.countDown()
            assertTrue(completed.await(15, TimeUnit.SECONDS))
            // Join the real save job, not just its first input-stream read.
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(15000) { jobs.forEach { it.join() } } }
            if (clear) {
                assertNull(path(model, screensaver))
                assertNull(if (screensaver) MainViewModel::class.java.getMethod("getScreensaverWallpaperPath").invoke(model) else model.wallpaperBitmap)
                assertFalse(File(root, if (screensaver) "screensaver_wallpaper.png" else "wallpaper.png").exists())
            } else {
                assertEquals(Color.BLUE, BitmapHelper.decodeFile(path(model, screensaver)!!, 64)!!.getPixel(12, 12))
                if (!screensaver) assertEquals(Color.BLUE, model.wallpaperBitmap!!.getPixel(12, 12))
            }
        } finally { release.countDown() }
    }

    @Test fun failedHomeWritePreservesOldFileAndPreference() = failedWrite(false)
    @Test fun failedScreensaverWritePreservesOldFileAndPreference() = failedWrite(true)

    private fun failedWrite(screensaver: Boolean) = withModel { model, root ->
        val name = if (screensaver) "screensaver_wallpaper.png" else "wallpaper.png"
        val old = png(File(root, name), Color.RED)
        val bytes = old.readBytes()
        if (screensaver) model.prefs.screensaverWallpaperPath = old.path else model.prefs.wallpaperPath = old.path
        val source = png(File(root, "source.png"), Color.BLUE)
        File(root, "$name.1.tmp").mkdir()
        val jobs = save(model, context, Uri.fromFile(source), screensaver)
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(15000) { jobs.forEach { it.join() } } }
        assertArrayEquals(bytes, old.readBytes())
        assertEquals(old.path, path(model, screensaver))
        assertFalse(File(root, "$name.1.tmp").exists())
    }

    @Test fun unownedTraversalSymlinkAndDirectoryReferencesAreMissingAndNeverDeleted() = withModel { model, root ->
        val outside = png(File(context.cacheDir, "f4-${UUID.randomUUID()}.png"), Color.RED)
        val link = File(root, "outside-link.png")
        Os.symlink(outside.path, link.path)
        try {
            for (file in listOf(outside, File(root, "../${root.name}/../../cache/${outside.name}"), link, root)) {
                model.prefs.wallpaperPath = file.path
                model.prefs.screensaverWallpaperPath = file.path
                model.prefs.setIconOverride(key, "file:${file.path}")
                IconUtility.clearCacheFor(key)
                assertNull(model.prefs.wallpaperPath)
                assertNull(model.prefs.screensaverWallpaperPath)
                instrumentation.runOnMainSync {
                    MainViewModel::class.java.getDeclaredMethod("loadWallpaper").apply { isAccessible = true }.invoke(model)
                }
                assertNull(model.wallpaperBitmap)
                assertNull(IconUtility.loadIcon(model.getApplication(), key, "", 64))
                clear(model, false)
                clear(model, true)
                instrumentation.runOnMainSync { model.clearIconOverride(key) }
                assertTrue(outside.isFile)
                assertTrue(root.isDirectory)
                val json = JSONObject().put("_type", "katapult_config")
                    .put("wallpaper_path", file.path).put("screensaver_wallpaper_path", file.path)
                    .put("icon_override_$key", "file:${file.path}").toString()
                assertTrue(model.prefs.importFromJson(json))
                assertNull(model.prefs.wallpaperPath)
                assertNull(model.prefs.screensaverWallpaperPath)
                assertNull(model.prefs.getIconOverride(key))
            }
        } finally { assertTrue(outside.delete()) }
    }

    @Test fun clearingWallpaperRetainsOtherWallpaperAndIconReferences() = withModel { model, root ->
        val shared = png(File(root, "shared.png"), Color.BLUE)
        model.prefs.wallpaperPath = shared.path
        model.prefs.screensaverWallpaperPath = File(root, "./shared.png").path
        model.prefs.setIconOverride(key, "file:${shared.path}")
        clear(model, false)
        assertTrue(shared.isFile)
        assertEquals(Color.BLUE, BitmapHelper.decodeFile(model.prefs.screensaverWallpaperPath!!, 64)!!.getPixel(12, 12))
        clear(model, true)
        assertTrue(shared.isFile)
        IconUtility.clearCacheFor(key)
        assertEquals(Color.BLUE, IconUtility.loadIcon(model.getApplication(), key, "", 64)!!.getPixel(32, 32))
        instrumentation.runOnMainSync { model.clearIconOverride(key) }
        assertFalse(shared.exists())
    }

    @Test fun ownedCanonicalAliasesImportAndRenderAndDeleteNormally() = withModel { model, root ->
        val image = png(File(root, "owned.png"), Color.BLUE)
        val alias = File(root, "./owned.png").path
        val json = JSONObject().put("_type", "katapult_config")
            .put("wallpaper_path", alias).put("screensaver_wallpaper_path", alias)
            .put("icon_override_$key", "file:$alias").toString()
        assertTrue(model.prefs.importFromJson(json))
        assertEquals(alias, model.prefs.wallpaperPath)
        assertEquals(alias, model.prefs.screensaverWallpaperPath)
        IconUtility.clearCacheFor(key)
        assertEquals(Color.BLUE, IconUtility.loadIcon(model.getApplication(), key, "", 64)!!.getPixel(32, 32))
        instrumentation.runOnMainSync { model.setBundledIcon(key, "clock") }
        assertTrue(image.isFile)
        assertNotNull(IconUtility.loadIcon(model.getApplication(), key, "", 64))
        clear(model, true)
        assertTrue(image.isFile)
        clear(model, false)
        assertFalse(image.exists())
    }

    @Test fun renameFailureRetainsOldPreferenceAndCleansTemporaryFile() = withModel { model, root ->
        val old = png(File(root, "old.png"), Color.RED)
        val bytes = old.readBytes()
        val source = png(File(root, "source.png"), Color.BLUE)
        for (screensaver in listOf(false, true)) {
            val name = if (screensaver) "screensaver_wallpaper.png" else "wallpaper.png"
            File(root, name).mkdir()
            if (screensaver) model.prefs.screensaverWallpaperPath = old.path else model.prefs.wallpaperPath = old.path
            val jobs = save(model, context, Uri.fromFile(source), screensaver)
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(15000) { jobs.forEach { it.join() } } }
            assertArrayEquals(bytes, old.readBytes())
            assertEquals(old.path, path(model, screensaver))
            assertFalse(File(root, "$name.1.tmp").exists())
        }
    }

    @Test fun clearAllNeverFollowsDirectorySymlinksOutsideOwnedStorage() = withModel { model, root ->
        val outside = File(context.cacheDir, "f4-${UUID.randomUUID()}").apply { mkdir() }
        val preserved = png(File(outside, "preserved.png"), Color.RED)
        Os.symlink(outside.path, File(root, "outside-directory").path)
        val owned = png(File(root, "owned.png"), Color.BLUE)
        try {
            model.prefs.clearAll(model.getApplication())
            assertTrue(preserved.isFile)
            assertFalse(owned.exists())
        } finally { assertTrue(outside.deleteRecursively()) }
    }
}
