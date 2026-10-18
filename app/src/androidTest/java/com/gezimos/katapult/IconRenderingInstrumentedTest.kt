package com.gezimos.katapult

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.util.CatalogueIconRenderer
import com.gezimos.katapult.util.DefaultIcons
import com.gezimos.katapult.util.IconCatalogue
import com.gezimos.katapult.util.IconCollection
import com.gezimos.katapult.util.IconSelection
import com.gezimos.katapult.util.IconSelections
import com.gezimos.katapult.util.IconUtility
import com.gezimos.katapult.util.PrefsManager
import com.gezimos.katapult.util.SvgRasterizer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class IconRenderingInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun completeCuratedCorpusRendersAndSearchesAtActualConsumer() {
        val catalogue = IconCatalogue.load(context)
        assertEquals(2174, catalogue.icons.size)
        for ((collection, count) in mapOf(IconCollection.SIMPLE to 843, IconCollection.TABLER to 1034, IconCollection.MATERIAL to 297)) {
            assertEquals(count, catalogue.search(collection, "").size)
            val ids = mutableSetOf<String>()
            for (icon in catalogue.search(collection, "")) {
                assertTrue(ids.add(icon.id))
                assertTrue("Search lost named identity ${icon.id}", catalogue.search(collection, icon.id).any { it.id == icon.id })
                for (size in listOf(96, 192)) {
                    val bitmap = CatalogueIconRenderer.render(context, icon, size)
                    assertNotNull("Unrenderable curated glyph: ${icon.id}/$size", bitmap)
                    val pixels = IntArray(size * size)
                    bitmap!!.getPixels(pixels, 0, size, 0, 0, size, size)
                    assertTrue("Blank glyph: ${icon.id}/$size", pixels.any { Color.alpha(it) > 32 })
                    assertEquals("Glyph lost transparent frame: ${icon.id}", 0, Color.alpha(bitmap.getPixel(0, 0)))
                    assertEquals(0, Color.alpha(bitmap.getPixel(size - 1, size - 1)))
                }
            }
        }
        assertNotNull(catalogue.icon("tabler-icons:outline:zodiac-aquarius"))
        assertNull(catalogue.icon("simple-icons:archlinux"))
        assertNull(catalogue.icon("tabler-icons:outline:headphones-off"))
        assertTrue(catalogue.search(IconCollection.SIMPLE, "  SIGNAL  ").any { it.id == "simple-icons:signal" })
        assertTrue(catalogue.search(IconCollection.MATERIAL, "does-not-exist-00000").isEmpty())
        Log.i("KotatsuIconProof", "PASS full corpus 2174 named icons / 4348 native renders / full identity search")
    }

    @Test fun svgStrokePrimitivesGroupsAndTransparentHolesSurviveImport() {
        val ring = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="8"/></svg>"""
        val image = SvgRasterizer.rasterize(ring.byteInputStream(), 120)!!
        assertEquals(0, Color.alpha(image.getPixel(60, 60)))
        assertTrue(Color.alpha(image.getPixel(84, 60)) > 200)
        assertEquals(0, Color.alpha(image.getPixel(10, 60)))
        val group = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="black" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><g transform="translate(2 2)"><rect x="2" y="2" width="12" height="12"/><polygon points="3,18 10,18 6,14"/></g></svg>"""
        val shapes = SvgRasterizer.rasterize(group.byteInputStream(), 120)!!
        assertTrue(Color.alpha(shapes.getPixel(36, 54)) > 200)
        assertEquals(0, Color.alpha(shapes.getPixel(48, 48)))
        assertEquals(0, Color.alpha(shapes.getPixel(0, 0)))
        assertNull(SvgRasterizer.rasterize("not svg".byteInputStream(), 120))
        assertNull(SvgRasterizer.rasterize("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><path fill=\"none\" d=\"M0 0h24v24H0z\"/></svg>".byteInputStream(), 120))
        assertNull(SvgRasterizer.rasterize(ring.byteInputStream(), 0))
        Log.i("KotatsuIconProof", "PASS primitive stroke / inherited currentColor / group transform / nonfilled geometry")
    }

    @Test fun exactMappingsAndManualLegacyFileChoicesReachBitmapConsumer() {
        val catalogue = IconCatalogue.load(context)
        assertEquals("simple-icons:signal", catalogue.automaticIcon("org.thoughtcrime.securesms", ""))
        assertEquals("simple-icons:applemusic", catalogue.automaticIcon("com.apple.android.music", "anything"))
        assertEquals("simple-icons:google", catalogue.automaticIcon("com.google.android.googlequicksearchbox", "anything"))
        for (packageName in listOf("com.tplink.tether", "ca.bnc.android")) {
            val automaticChoice = catalogue.automaticIcon(packageName, "anything")
            assertNull(automaticChoice)
            assertEquals(IconSelection.Original, IconSelections.resolve(null, automaticChoice))
        }
        assertNull(catalogue.automaticIcon("pretend.signal.brand", ""))
        assertNull(catalogue.automaticIcon("com.android.settings", ""))
        val signal = catalogue.icon("simple-icons:signal")!!
        val automatic = IconUtility.loadIcon(context, "org.thoughtcrime.securesms", "", 96)!!
        assertTrue(automatic.sameAs(CatalogueIconRenderer.render(context, signal, 96)))
        // Kotatsu's own default for an app with no catalogue brand reaches the bitmap consumer.
        val settings = IconUtility.loadIcon(context, "com.android.settings", "", 96)!!
        assertTrue(settings.sameAs(IconUtility.renderDrawableResource(context, R.drawable.settings, 96)))
        for ((pkg, reference) in DefaultIcons.references) {
            val drawn = IconUtility.renderSelection(context, IconSelections.parse(reference), 96)
            assertNotNull("Default icon for $pkg does not draw: $reference", drawn)
            val pixels = IntArray(96 * 96)
            drawn!!.getPixels(pixels, 0, 96, 0, 0, 96, 96)
            assertTrue("Blank default icon for $pkg", pixels.any { Color.alpha(it) > 32 })
        }

        val prefs = PrefsManager(context)
        val key = "__curated_icon_test_fixture__"
        assertNull("Preserve an unexpected existing fixture", prefs.getIconOverride(key))
        val file = File.createTempFile("curated-icon-", ".png", context.filesDir)
        try {
            for (collection in IconCollection.entries) {
                val icon = catalogue.search(collection, "").first()
                assertTrue(prefs.commitIconOverride(key, "catalogue:${icon.id}"))
                IconUtility.clearCacheFor(key)
                assertTrue(IconUtility.loadIcon(context, key, "", 96)!!.sameAs(CatalogueIconRenderer.render(context, icon, 96)))
            }
            assertTrue(prefs.commitIconOverride(key, "res:clock"))
            IconUtility.clearCacheFor(key)
            assertTrue(IconUtility.loadIcon(context, key, "", 96)!!.sameAs(IconUtility.renderDrawableResource(context, R.drawable.clock, 96)))
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawRect(8f, 8f, 24f, 24f, Paint().apply { color = Color.MAGENTA })
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue(prefs.commitIconOverride(key, "file:${file.absolutePath}"))
            IconUtility.clearCacheFor(key)
            assertEquals(Color.MAGENTA, IconUtility.loadIcon(context, key, "", 96)!!.getPixel(48, 48))
            val shortcutKey = "$key|owned-shortcut"
            assertNull(prefs.getIconOverride(shortcutKey))
            try {
                assertTrue(prefs.commitIconOverride(shortcutKey, "catalogue:${signal.id}"))
                IconUtility.clearCacheFor(shortcutKey)
                assertTrue(IconUtility.loadShortcutIcon(context, key, "owned-shortcut", 96)!!.sameAs(CatalogueIconRenderer.render(context, signal, 96)))
            } finally {
                assertTrue(context.getSharedPreferences("katapult_prefs", 0).edit()
                    .remove("icon_override_$shortcutKey").commit())
                IconUtility.clearCacheFor(shortcutKey)
            }
        } finally {
            assertTrue(context.getSharedPreferences("katapult_prefs", 0).edit()
                .remove("icon_override_$key").commit())
            IconUtility.clearCacheFor(key)
            assertTrue(file.delete())
        }
        Log.i("KotatsuIconProof", "PASS reviewed mapping / unmatched original fallback / manual catalogue / legacy res / PNG / shortcut isolation")
    }
}
