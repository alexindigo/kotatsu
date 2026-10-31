package com.gezimos.katapult.lockscreen

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import android.util.Xml
import android.view.Gravity
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.MainActivity
import com.gezimos.katapult.WeatherHostViewProbe
import com.gezimos.katapult.WidgetHostTestFixture
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.StringWriter
import java.security.MessageDigest
import java.time.ZonedDateTime

/** Separate Weather APK -> options broadcast -> actual launcher host, without a product test endpoint. */
@RunWith(AndroidJUnit4::class)
class WeatherSettlingInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val weather = "com.alexindigo.kotatsu.weather.debug"
    private val provider = "$weather/com.alexindigo.kotatsu.weather.WeatherWidgetProvider"
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return result!!.getOrThrow()
    }
    private fun await(message: String, ready: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (!ready() && SystemClock.elapsedRealtime() < end) Thread.sleep(25)
        assertTrue(message, ready())
    }

    @Test fun oneHourWakeReplaysNormalAndFiveHourWakeKeepsSettlingUntilFreshRemoteViews() = exercise(false)

    @Test fun createWhileVisibleNeverLeavesASettlingPictureOnScreen() = exercise(true)

    private fun exercise(recreateWhileVisible: Boolean) {
        // Preserve the entire provider preference file before binding creates its test widget config.
        shell("am force-stop $weather")
        val original = readPreferences()
        try { WidgetHostTestFixture(context).use { fixture ->
            val host = fixture.host
            val info = fixture.providers.singleProvider(weather)
            val widget = LockscreenWidget(fixture.bind(info), LockscreenWidgetSize.M)
            shell("am force-stop $weather")
            writePreferences(seed(widget.id))
            shell("su 0 am broadcast -f 0x20 -a android.appwidget.action.APPWIDGET_UPDATE -n $provider --eia appWidgetIds ${widget.id}")
            val image = context.packageManager.getResourcesForApplication(weather).getIdentifier("timeline", "id", weather)
            var now = 0L
            val previousClock = host.pictureClock
            var activity: MainActivity? = null
            host.pictureClock = { now }
            try {
                activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                val current = activity
                val view = main {
                    host.listen(true)
                    LockscreenWidgetHost.updateSize(context, widget)
                    host.createView(current, widget.id, info)
                }
                fun bitmap(): Bitmap = main { (view.findViewById<ImageView>(image).drawable as BitmapDrawable).bitmap }
                fun description(): String = main { view.findViewById<ImageView>(image).contentDescription.toString() }
                WeatherHostViewProbe(context, view, image).awaitSizedBitmap()
                val state = LockscreenVisibility().apply { beginEpoch(); recheck(true) { false } }
                val beforePulse = bitmap()
                main {
                    val density = context.resources.displayMetrics.density
                    current.setContentView(LinearLayout(current).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.BOTTOM
                        setBackgroundColor(Color.WHITE)
                        setPadding((8 * density).toInt(), 0, (8 * density).toInt(), (16 * density).toInt())
                        // MainActivity is edge-to-edge; this test host must not put a row behind emulator navigation.
                        setOnApplyWindowInsetsListener { root, insets ->
                            val bars = insets.getInsets(WindowInsets.Type.systemBars())
                            root.setPadding((8 * density).toInt() + bars.left, bars.top,
                                (8 * density).toInt() + bars.right, (16 * density).toInt() + bars.bottom)
                            insets
                        }
                        addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                            (widget.size.heightDp * density).toInt()))
                    })
                    LockscreenWidgetHost.pulseVisible(context, state, listOf(widget))
                }
                try {
                    await("Normal provider picture has real fixture rows and reaches the visible host") {
                        val received = bitmap()
                        received !== beforePulse && hasBlack(received) && description().contains("Home") && !description().contains("No forecast")
                    }
                } catch (failure: AssertionError) {
                    throw AssertionError("Initial host: id=${widget.id}, hint=${fixture.manager.getAppWidgetOptions(widget.id)}, " +
                        "description=${description()}, black=${hasBlack(bitmap())}, sameReceipt=${bitmap() === beforePulse}", failure)
                }
                val normal = bitmap()
                val spoken = description()
                assertTrue("Both fixture rows reach the host", spoken.contains("School"))
                if (recreateWhileVisible) {
                    lateinit var replacement: android.appwidget.AppWidgetHostView
                    lateinit var first: Bitmap
                    var settlingDrawn = false
                    var frames = 0
                    val observer = android.view.ViewTreeObserver.OnDrawListener {
                        frames++
                        val received = (replacement.findViewById<ImageView>(image)?.drawable as? BitmapDrawable)?.bitmap
                        if (received != null && !hasBlack(received)) settlingDrawn = true
                    }
                    main {
                        val parent = view.parent as LinearLayout
                        val params = view.layoutParams
                        replacement = host.createWidgetView(current, widget, info)
                        first = (replacement.findViewById<ImageView>(image).drawable as BitmapDrawable).bitmap
                        replacement.viewTreeObserver.addOnDrawListener(observer)
                        parent.removeView(view)
                        parent.addView(replacement, params)
                    }
                    try {
                        await("Creation's asynchronous provider picture must remain normal on screen") { main {
                            val received = (replacement.findViewById<ImageView>(image).drawable as BitmapDrawable).bitmap
                            received !== first && hasBlack(received) && replacement.isShown && frames > 0
                        } }
                        instrumentation.waitForIdleSync()
                        assertFalse("No settling frame may overwrite the visible widget", main { settlingDrawn })
                        assertEquals(spoken, main { replacement.findViewById<ImageView>(image).contentDescription.toString() })
                    } catch (failure: AssertionError) {
                        throw AssertionError(main {
                            val received = (replacement.findViewById<ImageView>(image).drawable as BitmapDrawable).bitmap
                            "Replacement: hint=${fixture.manager.getAppWidgetOptions(widget.id)}, black=${hasBlack(received)}, " +
                                "sameReceipt=${received === first}, shown=${replacement.isShown}, frames=$frames, settlingDrawn=$settlingDrawn"
                        }, failure)
                    } finally { main { replacement.viewTreeObserver.removeOnDrawListener(observer) } }
                    return@use
                }
                capture("normal")
                main { host.hideWidgets(context) }
                await("Hidden options delivered a grey picture, not just an options acknowledgement") { !hasBlack(bitmap()) && !bitmap().sameAs(normal) }
                val settling = bitmap()
                assertEquals(spoken, description())
                capture("settling")
                now = 3600_000
                lateinit var replay: Bitmap
                main {
                    state.beginEpoch(); state.recheck(true) { false }
                    LockscreenWidgetHost.pulseVisible(context, state, listOf(widget))
                    replay = (view.findViewById<ImageView>(image).drawable as BitmapDrawable).bitmap
                    assertTrue("One-hour wake synchronously replays normal before fresh callback", replay.sameAs(normal))
                }
                await("Wake pulse delivers a new normal RemoteViews picture") { bitmap() !== replay && hasBlack(bitmap()) }
                main { host.hideWidgets(context) }
                await("Second hide returns to settling") { !hasBlack(bitmap()) }
                now += 5 * 3600_000
                lateinit var kept: Bitmap
                main {
                    state.beginEpoch(); state.recheck(true) { false }
                    LockscreenWidgetHost.pulseVisible(context, state, listOf(widget))
                    kept = (view.findViewById<ImageView>(image).drawable as BitmapDrawable).bitmap
                    assertFalse("Five-hour wake does not resurrect normal", hasBlack(kept))
                    assertTrue("Settling geometry/data remain identical", kept.sameAs(settling))
                }
                await("Fresh Weather picture replaces settling at the consumer") { bitmap() !== kept && hasBlack(bitmap()) }
                assertEquals(spoken, description())
                capture("fresh-after-pulse")
            } finally {
                main { host.hideWidgets(context); host.listen(false); activity?.finish(); host.releaseViews() }
                host.pictureClock = previousClock
            }
        } } finally {
            shell("am force-stop $weather")
            if (original == null) shell("run-as $weather rm -f shared_prefs/weather.xml") else writePreferences(original)
            assertEquals("Exact provider preference bytes restored before runtime restart", original, readPreferences())
            shell("su 0 am broadcast -f 0x20 -a android.appwidget.action.APPWIDGET_UPDATE -n $provider")
        }
    }

    private fun hasBlack(bitmap: Bitmap): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.any { it == Color.BLACK }
    }

    private fun readPreferences(): String? {
        // UiAutomation uses Runtime.exec, not a quoting shell. Pass one whitespace-free sh -c argument;
        // the guest shell expands IFS into argument separators only after parsing that argument.
        val value = shell("run-as $weather sh -c test\${IFS}-f\${IFS}shared_prefs/weather.xml&&base64\${IFS}shared_prefs/weather.xml||printf\${IFS}MISSING")
        return if (value.trim() == "MISSING") null else String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
    }

    private fun writePreferences(xml: String) {
        val encoded = Base64.encodeToString(xml.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        shell("run-as $weather mkdir -p shared_prefs")
        shell("run-as $weather sh -c printf\${IFS}%s\${IFS}$encoded|base64\${IFS}-d>shared_prefs/weather.xml")
        assertEquals(xml, readPreferences())
    }

    private fun seed(id: Int): String {
        val now = ZonedDateTime.now()
        val fetched = now.minusHours(2).toInstant().toEpochMilli()
        val coordinates = "76.3210,84.1230"
        val hash = MessageDigest.getInstance("SHA-256").digest("$coordinates|true".toByteArray())
            .joinToString("") { "%02x".format(it) }
        fun place(label: String) = JSONObject().put("id", label).put("label", label).put("place", "Public test fixture")
            .put("latitude", 76.321).put("longitude", 84.123).put("start", 1).put("end", 23)
        val config = JSONObject().put("version", 1).put("fahrenheit", true)
            .put("locations", JSONArray().put(place("Home")).put(place("School")))
        val samples = JSONArray()
        val midnight = now.toLocalDate().atStartOfDay(now.zone).toInstant()
        for (h in -2..74) samples.put(JSONArray().put(midnight.plusSeconds(h * 3600L).epochSecond).put(64).put(2).put(JSONObject.NULL))
        val forecast = JSONObject().put("version", 1).put("fetchedAt", fetched).put("samples", samples)
        // A future retry keeps this cross-APK fixture fully offline without touching device network settings.
        val status = JSONObject().put("success", fetched).put("attempt", now.toInstant().toEpochMilli())
            .put("outcome", "OFFLINE").put("code", JSONObject.NULL).put("failures", 1)
            .put("next", now.plusDays(1).toInstant().toEpochMilli())
        val out = StringWriter()
        val xml = Xml.newSerializer().apply { setOutput(out); startDocument("UTF-8", true); startTag(null, "map") }
        for ((key, value) in listOf("widget_$id" to config, "forecast_$hash" to forecast, "refresh_$hash" to status)) {
            xml.startTag(null, "string").attribute(null, "name", key).text(value.toString()).endTag(null, "string")
        }
        xml.endTag(null, "map").endDocument()
        return out.toString()
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureScreenshots") != "true") return
        instrumentation.waitForIdleSync()
        Thread.sleep(200)
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val copy = screenshot.copy(Bitmap.Config.ARGB_8888, false)
        try {
            val file = File(File(context.getExternalFilesDir(null), "weather-e").apply { mkdirs() }, "$name.png")
            file.outputStream().use { assertTrue(copy.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue(file.length() > 0)
        } finally { copy.recycle(); screenshot.recycle() }
    }
}
