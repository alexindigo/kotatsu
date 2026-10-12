package com.gezimos.katapult

import android.content.Intent
import android.graphics.Bitmap
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
import com.gezimos.katapult.ui.IconCollectionPicker
import com.gezimos.katapult.ui.LocalInk
import com.gezimos.katapult.ui.LocalSurface
import com.gezimos.katapult.util.CatalogueIcon
import com.gezimos.katapult.util.CatalogueIconRenderer
import com.gezimos.katapult.util.IconCatalogue
import com.gezimos.katapult.util.IconCollection
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A tile slot reused for another catalogue entry must not show the previous entry's
 * readiness. The Tabler renderer is held until the check is done, so a stale
 * "Unavailable" from the Simple Icons tile would stay visible throughout the check.
 */
@RunWith(AndroidJUnit4::class)
class IconTileReuseInstrumentedTest {
    @Test fun reusedTileDoesNotInheritPreviousReadiness() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val catalogue = IconCatalogue.load(context)
        val simple = catalogue.search(IconCollection.SIMPLE, "").first()
        val tabler = catalogue.search(IconCollection.TABLER, "").first()
        val tablerDescription = "${tabler.label}, ${context.getString(R.string.tabler_icons)}, ${tabler.name}"
        val unavailable = context.getString(R.string.icon_unavailable)
        val release = CountDownLatch(1)
        val activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN).apply {
            setClassName(context.packageName, MainActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) as MainActivity
        val ui = PlatformUiDriver(instrumentation)
        val render: (CatalogueIcon) -> Bitmap? = { icon ->
            if (icon.collection == IconCollection.SIMPLE) null
            else {
                release.await(20, TimeUnit.SECONDS)
                CatalogueIconRenderer.render(context, icon, 64)
            }
        }
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    CompositionLocalProvider(LocalInk provides Color.Black, LocalSurface provides Color.White) {
                        Column(Modifier.fillMaxSize().background(Color.White).padding(16.dp)) {
                            IconCollectionPicker(catalogue, CircleShape, render = render,
                                onChoose = { false }, onChosen = {}, onImport = {})
                        }
                    }
                }
            }
            ui.await("Simple Icons tiles did not render as unavailable") { ui.has(unavailable) }
            ui.click(context.getString(R.string.tabler_icons))
            ui.await("Tabler tile missing after the collection switch") { ui.has(tablerDescription) }
            assertFalse("A reused tile showed the previous entry's unavailable state", ui.has(unavailable))
        } finally {
            release.countDown()
        }
    }
}
