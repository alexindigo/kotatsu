package com.gezimos.katapult.weather

import com.gezimos.katapult.fixtureCleanup
import com.gezimos.katapult.withFixtureCleanup

import android.app.UiAutomation
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.WidgetHostTestFixture
import com.gezimos.katapult.WeatherHostViewProbe
import com.gezimos.katapult.awaitActionServiceSettingsPersisted
import com.gezimos.katapult.lockscreen.LockscreenWidget
import com.gezimos.katapult.lockscreen.LockscreenWidgetHost
import com.gezimos.katapult.lockscreen.LockscreenWidgetService
import com.gezimos.katapult.lockscreen.LockscreenWidgetSize
import com.gezimos.katapult.weather.api.Weather
import com.gezimos.katapult.weather.api.WeatherCondition
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WeatherFeedInstrumentedTest {
    @get:org.junit.Rule val nativeState = com.gezimos.katapult.ConnectedUiStateRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }.trim()
    private fun <T> main(read: () -> T): T {
        var outcome: Result<T>? = null
        instrumentation.runOnMainSync { outcome = runCatching(read) }
        return outcome!!.getOrThrow()
    }
    private fun await(message: String, ready: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!ready() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
        assertTrue(message, ready())
    }
    private fun service(): LockscreenWidgetService? = LockscreenWidgetService::class.java.getDeclaredField("instance")
        .apply { isAccessible = true }.get(null) as? LockscreenWidgetService

    @Test fun mainFailureReturnsToTestThreadAndRunsRestoration() {
        val testThread = Thread.currentThread()
        val expected = AssertionError("Fixture main-thread failure")
        var restored = false
        val failure = runCatching {
            withFixtureCleanup({ assertSame(testThread, Thread.currentThread()) }, { restored = main { true } }) {
                main<Unit> { throw expected }
            }
        }.exceptionOrNull()
        assertSame(expected, failure)
        assertTrue("Restoration ran and the main looper remains usable", restored)
    }

    @Test fun blockedProviderQueryDoesNotDelayServiceEvaluationOrHostDelivery() {
      SleepingWeatherFixture(context).use { provider ->
        val source = WeatherFeed.Source(provider, provider::read, { AutoCloseable { } })
        exerciseService(source, releaseSource = provider::release) { feed ->
            provider.awaitBlocked()
            assertEquals(WeatherFeed.State.Unavailable, feed.state)
            evaluatePromptly()
            assertEquals("Service evaluation waited for the sleeping provider", WeatherFeed.State.Unavailable, feed.state)
            verifyHostReceivesRemoteViews()
            assertEquals("Host delivery did not wait for the sleeping provider", WeatherFeed.State.Unavailable, feed.state)
            provider.release()
            await("weather arrives on main after blocked query") {
                main {
                    val service = service()!!
                    LockscreenWidgetService::class.java.getDeclaredField("weatherValue").apply { isAccessible = true }.get(service) != null
                }
            }
        }
      }
    }

    @Test fun observerRegistrationFailureLeavesTheServiceAndRealWidgetHostUsable() {
        val source = WeatherFeed.Source(Any(), { error("Failed registration must not read") }, { throw SecurityException("registration denied") })
        exerciseService(source) { feed ->
            await("Failed state") { feed.state == WeatherFeed.State.Failed }
            evaluatePromptly()
            assertNotNull(main { service() })
            verifyHostReceivesRemoteViews()
            assertNotNull(main { service() })
        }
    }

    @Test fun homeConsumerReceivesUpdatesAndReleasesItsInitialLeaseOnDisableAndClear() {
        val singleton = WeatherFeed::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null)
        val raw = context.getSharedPreferences("katapult_prefs", Context.MODE_PRIVATE)
        val before = raw.all["show_weather"]
        val reading = java.util.concurrent.atomic.AtomicReference(Weather(17, "C", WeatherCondition.CLEAR, true))
        val changed = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>()
        val registrations = java.util.concurrent.atomic.AtomicInteger()
        val closes = java.util.concurrent.atomic.AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        val handler = Handler(Looper.getMainLooper())
        val feed = WeatherFeed({ WeatherFeed.Source(reading, { reading.get() }, {
            registrations.incrementAndGet(); changed.set(it); AutoCloseable { closes.incrementAndGet() }
        }) }, worker, Executor { handler.post(it) }, SystemClock::elapsedRealtime)
        val owner = com.gezimos.katapult.ownedViewModelStore()
        withFixtureCleanup(body = {
            singleton.set(null, feed)
            assertTrue(raw.edit().putBoolean("show_weather", true).commit())
            val vm = main {
                androidx.lifecycle.ViewModelProvider(owner,
                    androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(context.applicationContext as android.app.Application))[
                    com.gezimos.katapult.MainViewModel::class.java]
            }
            await("Home receives initial provider value") { main { vm.weather == reading.get() } }
            reading.set(reading.get().copy(temperature = 23))
            changed.get()!!.invoke()
            await("Home receives the provider's subsequent update") { main { vm.weather == reading.get() } }
            main { vm.prefs.showWeather = false; vm.refreshWeather() }
            await("Disabling Home releases the initial lease") { closes.get() == 1 }
            assertNull(main { vm.weather })
            main { vm.prefs.showWeather = true; vm.refreshWeather() }
            await("Home reacquires and receives weather") { main { vm.weather == reading.get() } }
            assertEquals(2, registrations.get())
            main { owner.clear() }
            await("Clearing the ViewModel releases its last lease") { closes.get() == 2 }
            assertEquals(WeatherFeed.State.Unavailable, feed.state)
        }, actions = arrayOf<() -> Unit>(
            { main { owner.clear() } }, { feed.close() },
            { worker.shutdown() }, { assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS)) },
            { singleton.set(null, previous) },
            {
            val edit = raw.edit()
            if (before == null) edit.remove("show_weather") else edit.putBoolean("show_weather", before as Boolean)
            assertTrue(edit.commit()); assertEquals(before, raw.all["show_weather"])
            },
        ))
    }

    private fun evaluatePromptly() {
        val start = SystemClock.elapsedRealtime()
        main {
            val service = service()!!
            LockscreenWidgetService::class.java.getDeclaredMethod("evaluate").apply { isAccessible = true }.invoke(service)
        }
        assertTrue("evaluate blocked on optional weather", SystemClock.elapsedRealtime() - start < 1000)
    }

    private fun exerciseService(source: WeatherFeed.Source, releaseSource: () -> Unit = {}, exercise: (WeatherFeed) -> Unit) {
      com.gezimos.katapult.ActionServiceSettingsFixture.off().use {
        val singleton = WeatherFeed::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val originalFeed = singleton.get(null)
        var accessibility: com.gezimos.katapult.ActionServiceSettingsFixture? = null
        val raw = context.getSharedPreferences("katapult_prefs", Context.MODE_PRIVATE)
        val before = raw.all["lockscreen_weather"]
        val worker = Executors.newSingleThreadExecutor()
        val handler = Handler(Looper.getMainLooper())
        val feed = WeatherFeed({ source }, worker, Executor { handler.post(it) }, SystemClock::elapsedRealtime)
        withFixtureCleanup(body = {
            com.gezimos.katapult.ConnectedUiStateFixture.prepareForegroundService()
            assertNull("Fixture requires no existing service", main { service() })
            singleton.set(null, feed)
            assertTrue(raw.edit().putBoolean("lockscreen_weather", true).commit())
            accessibility = com.gezimos.katapult.ActionServiceSettingsFixture.on()
            await("Service connected with injected feed") { main { service() != null } }
            exercise(feed)
        }, actions = arrayOf<() -> Unit>(
            { releaseSource() }, { accessibility?.close() },
            { await("Service destroyed before closing injected feed") { main { service() == null } } },
            {
                val edit = raw.edit()
                if (before == null) edit.remove("lockscreen_weather") else edit.putBoolean("lockscreen_weather", before as Boolean)
                assertTrue(edit.commit()); assertEquals(before, raw.all["lockscreen_weather"])
            },
            { feed.close() }, { worker.shutdown() }, { assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS)) },
            { singleton.set(null, originalFeed) },
        ))
      }
    }

    private fun verifyHostReceivesRemoteViews() {
        WidgetHostTestFixture(context).use { fixture ->
            val weather = "com.alexindigo.kotatsu.weather.debug"
            val info = fixture.providers.singleProvider(weather)
            // The service intentionally stops its Kompakt-only host on this emulator. Use an
            // independent real host to prove provider callbacks remain live during that evaluation.
            val host = AppWidgetHost(context, 0x574646)
            var view: AppWidgetHostView? = null
            automation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            val id = host.allocateAppWidgetId()
            withFixtureCleanup(body = {
                assertTrue(fixture.manager.bindAppWidgetIdIfAllowed(id, info.provider))
                val widget = LockscreenWidget(id, LockscreenWidgetSize.M)
                val timeline = context.packageManager.getResourcesForApplication(weather).getIdentifier("timeline", "id", weather)
                main { host.startListening(); LockscreenWidgetHost.updateSize(context, widget); view = host.createView(context, id, info) }
                WeatherHostViewProbe(context, view!!, timeline).awaitSizedBitmap()
                val marker = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
                main { view!!.findViewById<ImageView>(timeline).setImageBitmap(marker); LockscreenWidgetHost.updateSize(context, widget) }
                await("actual host still consumes new RemoteViews") { main {
                    val bitmap = (view!!.findViewById<ImageView>(timeline)?.drawable as? BitmapDrawable)?.bitmap
                    bitmap != null && !bitmap.sameAs(marker)
                } }
            }, actions = arrayOf<() -> Unit>(
                { automation.dropShellPermissionIdentity() },
                { main { fixtureCleanup({ host.stopListening() }, { host.deleteAppWidgetId(id) }) } },
            ))
        }
    }
}
