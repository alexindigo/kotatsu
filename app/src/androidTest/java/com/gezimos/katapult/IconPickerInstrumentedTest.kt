package com.gezimos.katapult

import android.content.Intent
import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.util.IconCatalogue
import com.gezimos.katapult.util.IconCollection
import com.gezimos.katapult.util.IconUtility
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Two explicit invocations prove a saved choice survives an actual process stop. */
@RunWith(AndroidJUnit4::class)
class IconPickerInstrumentedTest {
    @Test fun rejectedChoicesStayVisibleAndUnavailableTilesDoNotSave() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val run = InstrumentationRegistry.getArguments().getString("iconProofRun") ?: "native-r1"
        require(Regex("native-r[0-9]+").matches(run))
        val proof = File(context.getExternalFilesDir(null), "curated-icons-20261010-01/$run").apply { mkdirs() }
        val activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN).apply {
            setClassName(context.packageName, MainActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) as MainActivity
        val previous = context.getSharedPreferences("katapult_prefs", 0).all
        val catalogue = IconCatalogue.load(context)
        val first = catalogue.search(IconCollection.SIMPLE, "").first()
        val description = "${first.label}, Simple Icons, ${first.name}"
        val ui = PlatformUiDriver(instrumentation)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        var attempts = 0
        var dismissed = false

        fun show(unavailable: Boolean) = instrumentation.runOnMainSync {
            activity.setContent {
                CompositionLocalProvider(com.gezimos.katapult.ui.LocalInk provides Color.Black,
                    com.gezimos.katapult.ui.LocalSurface provides Color.White) {
                    Column(Modifier.fillMaxSize().background(Color.White).padding(16.dp)) {
                        com.gezimos.katapult.ui.IconCollectionPicker(catalogue, CircleShape,
                            render = { icon -> if (unavailable) null else com.gezimos.katapult.util.CatalogueIconRenderer.render(context, icon, 64) },
                            onChoose = { attempts++; false }, onChosen = { dismissed = true }, onImport = {})
                    }
                }
            }
        }

        fun capture(name: String) {
            instrumentation.waitForIdleSync()
            val file = File(proof, "$name.png")
            assertFalse(file.exists())
            val screen = automation.takeScreenshot()!!
            file.outputStream().use { assertTrue(screen.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }

        show(false)
        ui.await("Failure fixture tile missing") { ui.has(description) }
        instrumentation.waitForIdleSync()
        ui.click(description)
        ui.await("Rejected choice did not show its error") { ui.has("Could not confirm that icon choice. Please try again.") }
        assertEquals(1, attempts)
        assertFalse("A rejected choice dismissed the picker", dismissed)
        assertTrue(ui.has("Search icons"))
        assertEquals(previous, context.getSharedPreferences("katapult_prefs", 0).all)
        capture("picker-save-error-light")

        attempts = 0
        show(true)
        ui.await("Unavailable renderer state missing") { ui.has("Unavailable") }
        ui.click(description)
        instrumentation.waitForIdleSync()
        assertEquals("An unavailable tile invoked save", 0, attempts)
        assertFalse(dismissed)
        assertEquals(previous, context.getSharedPreferences("katapult_prefs", 0).all)
        capture("picker-render-error-light")
        Log.i("KotatsuIconProof", "PASS shared picker rejection / stays visible / unavailable tiles / unchanged preferences")
    }

    @Test fun publicPickerAndColdSavedChoice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("com.alexindigo.kotatsu.debug", context.packageName)
        val phase = InstrumentationRegistry.getArguments().getString("iconPhase") ?: "choose"
        assertTrue(phase in listOf("choose", "verify"))
        val run = InstrumentationRegistry.getArguments().getString("iconProofRun") ?: "native-r1"
        require(Regex("native-r[0-9]+").matches(run))
        val proof = File(context.getExternalFilesDir(null), "curated-icons-20261010-01/$run").apply { mkdirs() }
        val stateFile = File(proof, "choice-state.json")
        val activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN).apply {
            setClassName(context.packageName, MainActivity::class.java.name)
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) as MainActivity
        val model = activity.viewModel
        val ui = PlatformUiDriver(instrumentation)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ui.await("Neither onboarding nor HOME rendered") { ui.has("Finish") || ui.has("Apps") }
        assertFalse("Retained onboarding requires preservation, not test dismissal", ui.has("Finish"))
        ui.click("Apps")
        ui.await("App grid unavailable") {
            model.orderedApps.isNotEmpty() && ui.has("Clock") && ui.has("Gallery") && ui.has(model.orderedApps.first().label)
        }
        val catalogue = IconCatalogue.load(context)

        fun findLabel(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) findLabel(node.getChild(index), label)?.let { return it }
            return null
        }

        fun reveal(label: String, forward: Boolean) {
            fun visibleBounds(): Rect? {
                val node = findLabel(automation.rootInActiveWindow, label) ?: return null
                val rect = Rect()
                node.getBoundsInScreen(rect)
                var parent = node.parent
                while (parent != null && parent.className?.toString() != "android.widget.ScrollView") parent = parent.parent
                val viewport = Rect()
                parent?.getBoundsInScreen(viewport)
                return rect.takeIf { !it.isEmpty && !viewport.isEmpty && it.top >= viewport.top && it.bottom <= viewport.bottom }
            }
            repeat(8) {
                val visible = visibleBounds()
                if (visible != null) {
                    // A touch while the fling is moving stops that fling instead
                    // of clicking. Wait for observed geometry, not mere presence.
                    var stable = 0
                    var previous = visible
                    ui.await("Picker control did not settle: $label") {
                        val current = visibleBounds()
                        stable = if (current != null && current == previous) stable + 1 else 0
                        if (current != null) previous = current
                        stable >= 3
                    }
                    return
                }
                fun scroller(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (node == null) return null
                    if (node.className?.toString() == "android.widget.ScrollView" && node.packageName?.toString() == context.packageName) return node
                    for (index in 0 until node.childCount) scroller(node.getChild(index))?.let { return it }
                    return null
                }
                val owner = scroller(automation.rootInActiveWindow) ?: error("Picker scroll owner absent")
                val bounds = Rect()
                owner.getBoundsInScreen(bounds)
                assertFalse("Picker scroll owner has no current bounds", bounds.isEmpty)
                val x = bounds.centerX().toFloat()
                val top = bounds.top + bounds.height() / 4f
                val bottom = bounds.top + bounds.height() * 3 / 4f
                val start = if (forward) bottom else top
                val end = if (forward) top else bottom
                val down = SystemClock.uptimeMillis()
                for (step in 0..15) {
                    val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 15 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, start + (end - start) * step / 15f, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    assertTrue(automation.injectInputEvent(event, true))
                    event.recycle()
                    SystemClock.sleep(20)
                }
                instrumentation.waitForIdleSync()
            }
            fail("Picker scroll did not reveal $label")
        }

        fun setSearch(value: String) {
            reveal("Search icons", forward = false)
            var node = findLabel(automation.rootInActiveWindow, "Search icons")!!
            // Compose exposes the label as a child; Android editing actions
            // belong to the editable ancestor, not to that descriptive node.
            while (!node.isEditable) node = node.parent ?: error("Search editing owner absent")
            assertTrue("Search public action unavailable: class=${node.className}, text=${node.text}, actions=${node.actionList}", node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        }

        fun capture(name: String) {
            instrumentation.waitForIdleSync()
            val file = File(proof, "$name.png")
            assertFalse("Preserve an earlier proof capture", file.exists())
            var screen: Bitmap? = null
            var previous: Bitmap? = null
            var stable = 0
            ui.await("Rendered pixels did not settle for $name") {
                val current = automation.takeScreenshot()!!
                val content = Bitmap.createBitmap(current, 0, 32, current.width, current.height - 96)
                stable = if (previous?.sameAs(content) == true) stable + 1 else 0
                previous?.recycle()
                previous = content
                screen?.recycle()
                screen = current
                stable >= 3
            }
            previous?.recycle()
            file.outputStream().use { assertTrue(screen!!.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            screen?.recycle()
        }

        if (phase == "choose") {
            assertFalse("Preserve an earlier active choice proof", stateFile.exists())
            val target = model.orderedApps.first()
            val previous = model.prefs.getIconOverride(target.key)
            val originalOrder = model.orderedApps.map { it.key }
            val originalTheme = model.prefs.themeMode
            val originalShape = model.prefs.roundedIcons
            val state = JSONObject().put("key", target.key).put("label", target.label)
                .put("package", target.packageName).put("activity", target.activityName)
                .put("previous", previous ?: JSONObject.NULL).put("originalTheme", originalTheme)
                .put("originalShape", originalShape)
            stateFile.writeText(state.toString(2))
            var reached = false
            try {
                instrumentation.runOnMainSync {
                    model.prefs.themeMode = 0
                    model.themeMode = 0
                    model.prefs.roundedIcons = false
                    model.roundedIcons = false
                }
                for (collection in IconCollection.entries) {
                    ui.click(target.label, longPress = true)
                    ui.click("Change Icon")
                    ui.await("Curated tabs not rendered") { ui.has("Simple Icons") && ui.has("Tabler") && ui.has("Material Icons") }
                    val title = when (collection) { IconCollection.SIMPLE -> "Simple Icons"; IconCollection.TABLER -> "Tabler"; IconCollection.MATERIAL -> "Material Icons" }
                    ui.click(title)
                    val count = catalogue.search(collection, "").size
                    val pages = (count + 19) / 20
                    reveal("1 / $pages · $count icons", forward = true)
                    ui.await("First page/count missing") { ui.has("1 / $pages · $count icons") }
                    ui.click("Next")
                    ui.await("Full-corpus paging did not advance") { ui.has("2 / $pages · $count icons") }
                    ui.click("Previous")
                    ui.await("Previous page did not return") { ui.has("1 / $pages · $count icons") }
                    capture("picker-${collection.id}-full-light")
                    reveal("Search icons", forward = false)
                    setSearch("does-not-exist-00000")
                    ui.await("Empty search state absent") { ui.has("No matching icons") }
                    val entry = when (collection) {
                        IconCollection.SIMPLE -> catalogue.icon("simple-icons:signal")!!
                        IconCollection.TABLER -> catalogue.icon("tabler-icons:outline:zodiac-aquarius")!!
                        IconCollection.MATERIAL -> catalogue.icon("material-icons:outlined:music_note")
                            ?: catalogue.search(collection, "").first()
                    }
                    setSearch(entry.id)
                    val description = "${entry.label}, $title, ${entry.name}"
                    ui.await("Full catalogue search did not reach rendered ${entry.id}") { ui.has(description) }
                    ui.await("Native tile is not ready") {
                        var node = findLabel(automation.rootInActiveWindow, description)
                        while (node != null && !node.isClickable) node = node.parent
                        node?.isEnabled == true
                    }
                    capture("picker-${collection.id}-light")
                    ui.click(description)
                    ui.await("Manual selection did not persist/dismiss") {
                        model.prefs.getIconOverride(target.key) == "catalogue:${entry.id}" && !ui.has("Search icons")
                    }
                    assertEquals(originalOrder, model.orderedApps.map { it.key })
                    assertNotNull(IconUtility.loadIcon(context, target.packageName, target.activityName, 96))
                    state.put("selectedId", entry.id)
                    stateFile.writeText(state.toString(2))
                }
                ui.await("Selection did not return to the visible app grid") {
                    ui.has("Clock") && ui.has("Gallery") && !ui.has("Search icons")
                }
                capture("grid-selected-light")
                // Theme and shape are deliberate temporary test choices; restore
                // through their existing public controller properties in finally.
                instrumentation.runOnMainSync {
                    model.prefs.themeMode = 1
                    model.themeMode = 1
                    model.prefs.roundedIcons = true
                    model.roundedIcons = true
                }
                ui.click(target.label, longPress = true)
                ui.click("Change Icon")
                ui.await("Dark picker missing") { ui.has("Search icons") }
                ui.click("Material Icons")
                setSearch(state.getString("selectedId"))
                val darkEntry = catalogue.icon(state.getString("selectedId"))!!
                val darkDescription = "${darkEntry.label}, Material Icons, ${darkEntry.name}"
                ui.await("Dark search did not reach its actual glyph") {
                    var node = findLabel(automation.rootInActiveWindow, darkDescription)
                    while (node != null && !node.isClickable) node = node.parent
                    node?.isEnabled == true
                }
                capture("picker-material-dark-rounded")
                ui.back()
                reached = true
            } finally {
                instrumentation.runOnMainSync {
                    model.prefs.themeMode = originalTheme
                    model.themeMode = originalTheme
                    model.prefs.roundedIcons = originalShape
                    model.roundedIcons = originalShape
                }
                if (!reached) {
                    if (previous == null) model.prefs.clearIconOverride(target.key)
                    else assertTrue(model.prefs.commitIconOverride(target.key, previous))
                    IconUtility.clearCacheFor(target.key)
                    state.put("failureChoiceRestored", true)
                    stateFile.writeText(state.toString(2))
                }
            }
        } else {
            assertTrue("Cold proof missing", stateFile.isFile)
            val state = JSONObject(stateFile.readText())
            val key = state.getString("key")
            val chosen = state.getString("selectedId")
            try {
                assertEquals("catalogue:$chosen", model.prefs.getIconOverride(key))
                assertEquals(state.getInt("originalTheme"), model.prefs.themeMode)
                assertEquals(state.getBoolean("originalShape"), model.prefs.roundedIcons)
                val image = IconUtility.loadIcon(context, state.getString("package"), state.getString("activity"), 96)
                val expected = com.gezimos.katapult.util.CatalogueIconRenderer.render(context, catalogue.icon(chosen)!!, 96)
                assertNotNull(image)
                assertTrue("Cold saved choice did not reach icon bitmap consumer", image!!.sameAs(expected))
                capture("grid-selected-cold")
                ui.click(state.getString("label"), longPress = true)
                ui.click("Change Icon")
                ui.await("Cold manual override reset control absent") { ui.has("Reset to Default") }
                ui.click("Reset to Default")
                ui.await("Reset did not restore automatic/original resolution") { model.prefs.getIconOverride(key) == null }
                assertNotNull(IconUtility.loadIcon(context, state.getString("package"), state.getString("activity"), 96))
                state.put("coldBitmapVerified", true).put("resetVerified", true)
            } finally {
                val previous = if (state.isNull("previous")) null else state.getString("previous")
                if (previous == null) model.prefs.clearIconOverride(key)
                else assertTrue(model.prefs.commitIconOverride(key, previous))
                IconUtility.clearCacheFor(key)
                assertEquals(previous, model.prefs.getIconOverride(key))
                state.put("previousChoiceRestored", true)
                stateFile.writeText(state.toString(2))
            }
        }
        Log.i("KotatsuIconProof", "PASS public picker phase=$phase; paging/search/selection/cold consumer/reset/preservation")
    }
}
