package com.alexindigo.kotatsu.notes

import android.content.ComponentName
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class CrossPackageHostInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun fixture(method: String) = context.contentResolver.call(MuditaNotes.URI, method, null, null)!!
    private fun await(message: String, ready: () -> Boolean) = awaitWidgetHost(message, ready)
    private fun findButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        if (node.viewIdResourceName == "android:id/button1" && node.isVisibleToUser) return node
        for (index in 0 until node.childCount) findButton(node.getChild(index))?.let { return it }
        return null
    }

    @Test fun quoteUsesTheNamedSystemLatoFamilyAcrossThePackageBoundary() {
        fixture("reset")
        var id = -1
        val clock = QuoteRuntime.clock
        try {
            QuoteRuntime.clock = java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneId.of("UTC"))
            context.startActivity(Intent().setComponent(ComponentName(instrumentation.context.packageName, NotesCrossPackageHostActivity::class.java.name))
                .putExtra("providerPackage", context.packageName).putExtra("providerClass", QuoteWidgetProvider::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("Quote host must start") { fixture("host-stats").getString("hostState") != null }
            if (fixture("host-stats").getString("hostState") == "consent") {
                var button: AccessibilityNodeInfo? = null
                await("Bind consent") { button = findButton(instrumentation.uiAutomation.rootInActiveWindow); button != null }
                assertTrue(button!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
            await("Quote host must bind") { fixture("host-stats").getString("hostState") == "bound" }
            id = fixture("host-stats").getInt("hostId")
            NotesRuntime.enqueue { QuoteStore(context).save(QuoteConfig(7)); QuoteRuntime.refresh(context) }.get()
            await("Quote must reach the other UID") { fixture("host-stats").getString("quote") == "First quote" }
            val observed = fixture("host-stats")
            assertNotEquals(context.applicationInfo.uid, observed.getInt("hostUid"))
            assertTrue(observed.getBoolean("quoteRestricted"))
            assertEquals(400, observed.getInt("quoteWeight"))
            val paint = Paint().apply { typeface = Typeface.create("lato-mudita", Typeface.NORMAL); textSize = observed.getFloat("quoteSize") }
            assertEquals(paint.measureText("WWmm ii 0123456789"), observed.getFloat("quoteWidth"), .01f)
            val png = fixture("host-capture").getByteArray("png")!!
            val directory = java.io.File(context.getExternalFilesDir(null), "notes-renders").apply { mkdirs() }
            java.io.File(directory, "widget-quote-cross-package.png").writeBytes(png)
        } finally {
            fixture("host-close")
            await("Quote host must release") { fixture("host-stats").getString("hostState") == null }
            if (id >= 0) NotesRuntime.enqueue { QuoteStore(context).delete(id); QuoteRuntime.refresh(context) }.get()
            QuoteRuntime.clock = clock
        }
    }

    @Test fun actualOtherPackageHostRendersTheIntendedTypefaceAndCommittedRows() {
        fixture("reset")
        val testPackage = instrumentation.context.packageName
        var widgetId = -1
        try {
            context.startActivity(Intent().setComponent(ComponentName(testPackage, NotesCrossPackageHostActivity::class.java.name))
                .putExtra("providerPackage", context.packageName)
                .putExtra("providerClass", NotesWidgetProvider::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("Cross-package host must start") { fixture("host-stats").getString("hostState") != null }
            if (fixture("host-stats").getString("hostState") == "consent") {
                var button: AccessibilityNodeInfo? = null
                await("Android widget bind consent must be visible") {
                    button = findButton(instrumentation.uiAutomation.rootInActiveWindow)
                    button != null
                }
                assertTrue(button!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
            await("Other package must bind its widget") { fixture("host-stats").getString("hostState") == "bound" }
            val bound = fixture("host-stats")
            widgetId = bound.getInt("hostId")
            assertEquals(testPackage, bound.getString("hostPackage"))
            assertNotEquals("This must cross a UID boundary", context.applicationInfo.uid, bound.getInt("hostUid"))
            NotesRuntime.enqueue {
                check(NotesStore(context).select(4))
                NotesRuntime.refresh(context, intArrayOf(widgetId))
            }.get()
            await("Published collection must reach the other APK") { fixture("host-stats").getString("task") == "Milk" }
            val observed = fixture("host-stats")
            android.util.Log.i("NotesCrossPackageFont", "geometry row=${observed.getParcelable<Rect>("rowBounds")} check=${observed.getParcelable<Rect>("checkBounds")} drawing=${observed.getParcelable<Rect>("checkDrawing")} text=${observed.getParcelable<Rect>("textBounds")} ink=${observed.getParcelable<Rect>("textInk")} pitch=${observed.getInt("rowPitch")}")
            assertTrue("Framework must use its restricted cross-package resource context", observed.getBoolean("restricted"))
            assertEquals("Kotatsu long list", observed.getString("heading"))
            assertEquals("The provider label supplies the host's default screen name", "Task list", observed.getString("providerLabel"))
            val paint = Paint().apply {
                textSize = observed.getFloat("textSize")
                // Stock Android has no lato-mudita family and uses its normal fallback.
                // The phone's family -> exact Lato Medium file is separately checksum-verified.
                typeface = Typeface.create(Typeface.create("lato-mudita", Typeface.NORMAL), 500, false)
            }
            val samples = listOf("Milk", "WWmm ii 0123456789", "Ask the pharmacist")
            val actual = observed.getFloatArray("widths")!!
            val reference = Paint(paint).apply { typeface = Typeface.create(context.resources.getFont(R.font.lato_medium), 500, false) }
            android.util.Log.i("NotesCrossPackageFont", "restricted=${observed.getBoolean("restricted")} weight=${observed.getInt("weight")} headingWeight=${observed.getInt("headingWeight")} actual=${actual.toList()} named=${samples.map(paint::measureText)} bundledReference=${samples.map(reference::measureText)}")
            samples.forEachIndexed { index, sample ->
                assertEquals("Cross-package named system family: $sample", paint.measureText(sample), actual[index], .01f)
            }
            assertEquals(500, observed.getInt("weight"))
            assertEquals(700, observed.getInt("headingWeight"))
            assertTrue(observed.getInt("wrappedLines") > 1)
            val png = fixture("host-capture").getByteArray("png")!!
            val directory = java.io.File(context.getExternalFilesDir(null), "notes-renders").apply { mkdirs() }
            java.io.File(directory, "widget-notes-cross-package.png").writeBytes(png)
            val frame = BitmapFactory.decodeByteArray(png, 0, png.size)
            val bounds = observed.getParcelable<Rect>("textBounds")!!
            val rowBounds = observed.getParcelable<Rect>("rowBounds")!!
            val viewport = observed.getParcelable<Rect>("listBounds")!!
            val pixels = mutableListOf<Pair<Int, Int>>()
            for (y in maxOf(rowBounds.top, viewport.top) until minOf(rowBounds.bottom, viewport.bottom)) for (x in rowBounds.left until bounds.left) {
                if ((frame.getPixel(x, y) and 0xffffff) < 0x808080) pixels += x to y
            }
            assertTrue("Checkbox ink must be present", pixels.isNotEmpty())
            val box = Rect(pixels.minOf { it.first }, pixels.minOf { it.second }, pixels.maxOf { it.first } + 1, pixels.maxOf { it.second } + 1)
            val textInk = observed.getParcelable<Rect>("textInk")!!
            val density = context.resources.displayMetrics.density
            assertEquals("First drawing starts at the collection edge", viewport.top.toFloat(), box.top.toFloat(), 1f)
            android.util.Log.i("NotesCrossPackageFont", "ink box=$box text=$textInk deltaY=${box.exactCenterY() - textInk.exactCenterY()}")
            assertEquals("Notes visible checkbox starts after row inset and padded slot", (6 + 10 + 28 * 2f / 24) * density,
                (box.left - rowBounds.left).toFloat(), 1f)
            assertEquals("Notes text follows the natural checkbox cell", 54 * density, (bounds.left - rowBounds.left).toFloat(), 1f)
            val firstLine = observed.getParcelable<Rect>("firstLine")!!
            assertEquals("Checkbox is centered on the first rendered text line", firstLine.exactCenterY(), box.exactCenterY(), 1f)
            assertEquals("Notes visible checkbox width", 28 * 20f / 24 * density, box.width().toFloat(), 1f)
            val pitch = 2 * (density + .5f).toInt() + 2 * (10 * density + .5f).toInt() + (28 * density + .5f).toInt()
            assertEquals("Natural row pitch rounds each Notes slot independently", pitch.toFloat(), observed.getInt("rowPitch").toFloat(), 1f)
            val wrappedRow = observed.getParcelable<Rect>("wrappedRowBounds")!!
            val wrappedText = observed.getParcelable<Rect>("wrappedTextBounds")!!
            val wrappedPixels = mutableListOf<Pair<Int, Int>>()
            for (y in maxOf(wrappedRow.top, viewport.top) until minOf(wrappedRow.bottom, viewport.bottom)) for (x in wrappedRow.left until wrappedText.left) {
                if ((frame.getPixel(x, y) and 0xffffff) < 0x808080) wrappedPixels += x to y
            }
            val wrappedBox = Rect(wrappedPixels.minOf { it.first }, wrappedPixels.minOf { it.second }, wrappedPixels.maxOf { it.first } + 1, wrappedPixels.maxOf { it.second } + 1)
            assertEquals("Wrapped label retains the same first-line alignment", observed.getParcelable<Rect>("wrappedFirstLine")!!.exactCenterY(), wrappedBox.exactCenterY(), 1f)
            android.util.Log.i("NotesCrossPackageFont", "verified rowRelativeBoxX=${box.left - rowBounds.left} textX=${bounds.left - rowBounds.left} width=${box.width()} line=$firstLine box=$box wrappedBox=$wrappedBox pitch=$pitch provider=${observed.getString("providerLabel")}")
            assertTrue("Committed frame must contain the measured row text", (bounds.top until bounds.bottom).any { y ->
                (bounds.left until bounds.right).any { x -> (frame.getPixel(x, y) and 0xffffff) < 0x808080 }
            })
            frame.recycle()
        } finally {
            fixture("host-close")
            await("Other package must release its widget") { fixture("host-stats").getString("hostState") == null }
            if (widgetId >= 0) NotesRuntime.enqueue { NotesStore(context).delete(widgetId, true); NotesRuntime.arm(context) }.get()
        }
    }
}
