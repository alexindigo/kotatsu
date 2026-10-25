package com.alexindigo.kotatsu.weather

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WeatherOperationsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs get() = context.getSharedPreferences("weather", 0)
    private val store get() = WeatherStore(context)
    private val first = Location("First", "Fixture first", 10.0, 20.0)
    private val second = Location("Second", "Fixture second", 30.0, 40.0)

    private fun bound(test: (Int, AppWidgetHost) -> Unit) {
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        val host = AppWidgetHost(context, 0x4B54)
        val id = host.allocateAppWidgetId()
        try {
            assertTrue(AppWidgetManager.getInstance(context).bindAppWidgetIdIfAllowed(id, ComponentName(context, WeatherWidgetProvider::class.java)))
            assertTrue(prefs.edit().putString("widget_$id", ConfigJson.encode(WidgetConfig(listOf(first, second)))).commit())
            test(id, host)
        } finally {
            host.deleteAppWidgetId(id)
            store.delete(id)
            WeatherRuntime.cancel(context)
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test fun addReadsTheCurrentListAndEnforcesItsLimit() = bound { id, _ ->
        assertEquals(WeatherStore.Result.APPLIED, store.add(id, first.copy(label = "Third")))
        assertEquals(WeatherStore.Result.APPLIED, WeatherStore(context).add(id, first.copy(label = "Fourth")))
        assertEquals(WeatherStore.Result.FULL, store.add(id, first))
        assertEquals(listOf("First", "Second", "Third", "Fourth"), store.config(id)!!.locations.map { it.label })
    }

    @Test fun editTargetsIdentityAfterAnInterleavedMoveAndKeepsNewRows() = bound { id, _ ->
        assertEquals(WeatherStore.Result.APPLIED, store.move(id, second.id, -1))
        assertEquals(WeatherStore.Result.APPLIED, store.add(id, first.copy(label = "Third")))
        assertEquals(WeatherStore.Result.APPLIED, store.edit(id, first.id, "Edited", 9, 21))
        val rows = store.config(id)!!.locations
        assertEquals(listOf("Second", "Edited", "Third"), rows.map { it.label })
        assertEquals(9, rows[1].start); assertEquals(21, rows[1].end)
        assertEquals(first.latitude, rows[1].latitude, 0.0)
    }

    @Test fun removeTargetsIdentityNotTheOldIndex() = bound { id, _ ->
        store.move(id, second.id, -1)
        assertEquals(WeatherStore.Result.APPLIED, store.remove(id, first.id))
        assertEquals(listOf(second), store.config(id)!!.locations)
        assertEquals(WeatherStore.Result.MISSING, store.edit(id, first.id, "Gone", 7, 23))
        assertEquals(listOf(second), store.config(id)!!.locations)
    }

    @Test fun moveAppliesToTheCurrentOrder() = bound { id, _ ->
        store.add(id, first.copy(label = "Third"))
        assertEquals(WeatherStore.Result.APPLIED, store.move(id, first.id, 2))
        assertEquals(listOf("Third", "Second", "First"), store.config(id)!!.locations.map { it.label })
        assertEquals(WeatherStore.Result.MISSING, store.move(id, first.id, 1))
    }

    @Test fun unitsReadTheCurrentValueWithoutReplacingPlaces() = bound { id, _ ->
        store.toggleUnits(id)
        store.add(id, first.copy(label = "Third"))
        assertFalse(store.config(id)!!.fahrenheit)
        WeatherStore(context).toggleUnits(id)
        assertTrue(store.config(id)!!.fahrenheit)
        assertEquals(3, store.config(id)!!.locations.size)
    }

    @Test fun concurrentStoreInstancesSerializeOperations() = bound { id, _ ->
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = listOf("Third", "Fourth").map { label -> pool.submit<WeatherStore.Result> {
                check(start.await(5, TimeUnit.SECONDS))
                WeatherStore(context).add(id, first.copy(label = label))
            } }
            start.countDown()
            results.forEach { assertEquals(WeatherStore.Result.APPLIED, it.get(10, TimeUnit.SECONDS)) }
            assertEquals(setOf("First", "Second", "Third", "Fourth"), store.config(id)!!.locations.map { it.label }.toSet())
        } finally { pool.shutdownNow() }
    }

    @Test fun widgetGoneDuringAsyncAddCannotRecreateItsConfiguration() = bound { id, host ->
        host.deleteAppWidgetId(id)
        assertEquals(WeatherStore.Result.GONE, store.add(id, first))
        // A later callback cannot write into any replacement ID or resurrect a deleted binding.
        assertFalse(id in WeatherRuntime.ids(context))
    }

    @Test fun unreadablePayloadsRemainByteForByteAndRejectEveryOperation() = bound { id, _ ->
        val valid = ConfigJson.encode(WidgetConfig(listOf(first)))
        for (raw in listOf("not json", valid.replace("\"version\":1", "\"version\":99"), valid.replace("\"start\":7", "\"start\":6"))) {
            assertTrue(prefs.edit().putString("widget_$id", raw).commit())
            assertNull(store.config(id))
            assertEquals(WeatherStore.Result.UNREADABLE, store.add(id, second))
            assertEquals(WeatherStore.Result.UNREADABLE, store.remove(id, first.id))
            assertEquals(WeatherStore.Result.UNREADABLE, store.move(id, first.id, 1))
            assertEquals(WeatherStore.Result.UNREADABLE, store.edit(id, first.id, "Edited", 7, 23))
            assertEquals(WeatherStore.Result.UNREADABLE, store.toggleUnits(id))
            assertEquals(raw, prefs.getString("widget_$id", null))
        }
    }

    @Test fun restoreCopiesExactRawDataAndNeverOverwritesADestination() = bound { id, host ->
        val destination = host.allocateAppWidgetId()
        try {
            assertTrue(AppWidgetManager.getInstance(context).bindAppWidgetIdIfAllowed(destination, ComponentName(context, WeatherWidgetProvider::class.java)))
            val raw = "{\"version\":99,\"privateFutureData\":\"retained\"}"
            prefs.edit().putString("widget_$id", raw).commit()
            WeatherWidgetProvider().onRestored(context, intArrayOf(id), intArrayOf(destination))
            assertEquals(raw, prefs.getString("widget_$destination", null))
            prefs.edit().putString("widget_$id", "different").commit()
            store.restore(id, destination)
            assertEquals(raw, prefs.getString("widget_$destination", null))
        } finally { host.deleteAppWidgetId(destination); store.delete(destination) }
    }
}
