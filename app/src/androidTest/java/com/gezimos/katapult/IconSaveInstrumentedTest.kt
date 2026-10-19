package com.gezimos.katapult

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.util.CatalogueIconRenderer
import com.gezimos.katapult.util.IconCatalogue
import com.gezimos.katapult.util.IconUtility
import com.gezimos.katapult.util.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class IconSaveInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val key = "__f3_icon_save__"
    private val id = "simple-icons:signal"

    private class TestApplication(base: Context, private val store: SharedPreferences, private val root: File) : Application() {
        init { attachBaseContext(base) }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == "katapult_prefs") store else super.getSharedPreferences(name, mode)
        override fun getFilesDir(): File = root
    }

    private fun withModel(wrap: (SharedPreferences) -> SharedPreferences = { it }, test: (MainViewModel, File) -> Unit) {
        val name = "f3-${UUID.randomUUID()}"
        val store = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val root = File(context.filesDir, name).apply { assertTrue(mkdir()) }
        val owner = ViewModelStore()
        lateinit var model: MainViewModel
        try {
            instrumentation.runOnMainSync {
                model = MainViewModel(TestApplication(context.applicationContext, wrap(store), root))
                owner.put("f3", model)
            }
            test(model, root)
        } finally {
            instrumentation.runOnMainSync { owner.clear() }
            IconUtility.clearCacheFor(key)
            assertTrue(root.deleteRecursively())
            assertTrue(context.deleteSharedPreferences(name))
        }
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

    @Test fun catalogueRefreshSurvivesPickerScopeCancellationAfterCommit() {
        val committed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pickerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val wrap: (SharedPreferences) -> SharedPreferences = { store ->
            object : SharedPreferences by store {
                override fun edit(): SharedPreferences.Editor {
                    val editor = store.edit()
                    return object : SharedPreferences.Editor by editor {
                        private var iconWrite = false
                        override fun putString(name: String, value: String?): SharedPreferences.Editor {
                            iconWrite = name == "icon_override_$key"
                            editor.putString(name, value)
                            return this
                        }
                        override fun commit(): Boolean {
                            val result = editor.commit()
                            if (iconWrite) {
                                committed.countDown()
                                check(release.await(20, TimeUnit.SECONDS))
                            }
                            return result
                        }
                    }
                }
            }
        }
        try {
            withModel(wrap) { model, _ ->
                val before = model.shortcutRefresh
                lateinit var waiter: Job
                instrumentation.runOnMainSync {
                    waiter = pickerScope.launch { model.setCatalogueIcon(key, id).await() }
                }
                assertTrue("Save did not reach durable commit", committed.await(15, TimeUnit.SECONDS))
                instrumentation.runOnMainSync { waiter.cancel() }
                release.countDown()
                await("Picker cancellation lost the post-save refresh") { model.shortcutRefresh > before }
                assertEquals("catalogue:$id", model.prefs.getIconOverride(key))
                val application = model.getApplication<Application>()
                val icon = IconCatalogue.load(application).icon(id)!!
                assertTrue(IconUtility.loadIcon(application, key, "", 64)!!.sameAs(CatalogueIconRenderer.render(application, icon, 64)))
            }
        } finally {
            release.countDown()
            pickerScope.cancel()
        }
    }

    @Test fun invalidationDoesNotRepublishAnOlderOverrideSnapshot() = staleSnapshot(false)
    @Test fun clearingOneIconDoesNotRepublishAnOlderOverrideSnapshot() = staleSnapshot(true)

    private fun staleSnapshot(clearOne: Boolean) {
        val name = "f3-cache-${UUID.randomUUID()}"
        val store = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val captured = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockingStore = object : SharedPreferences by store {
            override fun getAll(): MutableMap<String, *> = store.all.also {
                captured.countDown()
                check(release.await(20, TimeUnit.SECONDS))
            }
        }
        fun using(prefs: SharedPreferences) = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = prefs
        }
        val normal = using(store)
        val prefs = PrefsManager(normal)
        val worker = Executors.newSingleThreadExecutor()
        try {
            prefs.setIconOverride(key, "res:clock")
            IconUtility.clearCacheFor(key)
            val read = worker.submit<Bitmap?> { IconUtility.loadIcon(using(blockingStore), key, "", 64) }
            assertTrue("Override read did not capture its old snapshot", captured.await(15, TimeUnit.SECONDS))
            assertTrue(prefs.commitIconOverride(key, "catalogue:$id"))
            if (clearOne) IconUtility.clearCacheFor(key) else IconUtility.invalidateOverrides()
            release.countDown()
            assertNotNull(read.get(15, TimeUnit.SECONDS))
            val icon = IconCatalogue.load(normal).icon(id)!!
            assertTrue("Stale map hid the newly committed icon at the consumer",
                IconUtility.loadIcon(normal, key, "", 64)!!.sameAs(CatalogueIconRenderer.render(normal, icon, 64)))
        } finally {
            release.countDown()
            worker.shutdownNow()
            IconUtility.clearCacheFor(key)
            assertTrue(context.deleteSharedPreferences(name))
        }
    }

    private fun previousIcon(model: MainViewModel, root: File): Pair<File, ByteArray> {
        val file = File(root, "icons/$key.png").apply { parentFile!!.mkdirs() }
        val old = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        file.outputStream().use { assertTrue(old.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        old.recycle()
        model.prefs.setIconOverride(key, "file:${file.absolutePath}")
        IconUtility.clearCacheFor(key)
        return file to file.readBytes()
    }

    @Test fun materialCompressionFailurePreservesOldFileAndOverrideAndReportsError() = withModel { model, root ->
        val (file, bytes) = previousIcon(model, root)
        val refresh = model.shortcutRefresh
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { recycle() }
        instrumentation.runOnMainSync { model.saveMaterialIcon(key, bitmap) }
        await("Material save swallowed its compression error") { model.iconImportError }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals("file:${file.absolutePath}", model.prefs.getIconOverride(key))
        assertEquals(refresh, model.shortcutRefresh)
        assertFalse(File(file.path + ".tmp").exists())
        assertEquals(Color.RED, IconUtility.loadIcon(model.getApplication(), key, "", 64)!!.getPixel(32, 32))
    }

    @Test fun importedWriteFailurePreservesOldFileAndOverrideAndReportsError() = withModel { model, root ->
        val (file, bytes) = previousIcon(model, root)
        val refresh = model.shortcutRefresh
        val source = File(root, "source.png")
        source.writeBytes(bytes)
        val temp = File(file.path + ".tmp").apply { assertTrue(mkdir()) }
        instrumentation.runOnMainSync {
            model.beginIconImport(key)
            model.saveImportedIcon(context, Uri.fromFile(source))
        }
        await("Import ignored a failed temporary write") { model.iconImportError }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals("file:${file.absolutePath}", model.prefs.getIconOverride(key))
        assertEquals(refresh, model.shortcutRefresh)
        assertFalse(temp.exists())
    }

    @Test fun materialSuccessReplacesFileAndRefreshesRenderedConsumer() = successfulSave(false)
    @Test fun importSuccessReplacesFileAndRefreshesRenderedConsumer() = successfulSave(true)

    @Test fun newPngImportCapsVisibleInkAtSavedConsumer() = withModel { model, root ->
        val source = File(root, "oversized-source.png")
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(0f, 0f, 80f, 60f, Paint().apply { color = Color.BLACK })
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val refresh = model.shortcutRefresh
        instrumentation.runOnMainSync {
            model.beginIconImport(key)
            model.saveImportedIcon(context, Uri.fromFile(source))
        }
        await("Import did not reach saved consumer") { model.shortcutRefresh > refresh }
        val drawn = IconUtility.loadIcon(model.getApplication(), key, "", 192)!!
        var left = 192; var top = 192; var right = -1; var bottom = -1
        for (y in 0 until 192) for (x in 0 until 192) {
            if (Color.alpha(drawn.getPixel(x, y)) >= 128) {
                left = minOf(left, x); top = minOf(top, y)
                right = maxOf(right, x); bottom = maxOf(bottom, y)
            }
        }
        assertEquals(0.54f, maxOf(right - left + 1, bottom - top + 1) / 192f, 0.02f)
        assertEquals(96f, (left + right + 1) / 2f, 0.5f)
        assertEquals(96f, (top + bottom + 1) / 2f, 0.5f)
    }

    private fun successfulSave(import: Boolean) = withModel { model, root ->
        val (file, _) = previousIcon(model, root)
        val refresh = model.shortcutRefresh
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val source = File(root, "source.png")
        if (import) source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        instrumentation.runOnMainSync {
            if (import) {
                model.beginIconImport(key)
                model.saveImportedIcon(context, Uri.fromFile(source))
            } else model.saveMaterialIcon(key, bitmap)
        }
        await("Successful save did not publish its refresh") { model.shortcutRefresh > refresh }
        assertFalse(model.iconImportError)
        assertEquals("file:${file.absolutePath}", model.prefs.getIconOverride(key))
        assertFalse(File(file.path + ".tmp").exists())
        assertEquals(Color.BLUE, IconUtility.loadIcon(model.getApplication(), key, "", 64)!!.getPixel(32, 32))
        assertTrue(model.orderedApps.isNotEmpty())
        bitmap.recycle()
    }
}
