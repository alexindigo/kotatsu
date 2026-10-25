package com.gezimos.katapult

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** Owns component mutations AND the AppWidgetService publication barrier between tests. */
internal class WidgetProviderTestFixture(private val context: Context) : AutoCloseable {
    private val manager = AppWidgetManager.getInstance(context)
    private data class State(val enabled: Int, val published: Boolean)
    private val originals = linkedMapOf<ComponentName, State>()

    fun singleProvider(packageName: String): AppWidgetProviderInfo = publishedProviders(packageName).single()
    fun firstProvider(packageName: String): AppWidgetProviderInfo = publishedProviders(packageName).first()

    private fun publishedProviders(packageName: String): List<AppWidgetProviderInfo> = awaitObserved(
        "Provider publication for $packageName", { manager.installedProviders.filter { it.provider.packageName == packageName } },
        { it.isNotEmpty() })

    fun disable(info: AppWidgetProviderInfo) {
        val component = info.provider
        if (component !in originals) {
            val original = state(component)
            check(original.published && original.enabled in setOf(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED)) { "Provider must start available: $component $original" }
            originals[component] = original
        }
        shell("disable", component)
        awaitObserved("Provider removal for $component", { state(component) }, {
            it.enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED && !it.published
        })
    }

    override fun close() {
        var failure: Throwable? = null
        for ((component, original) in originals.entries.reversed()) {
            try {
                val action = if (original.enabled == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) "default-state" else "enable"
                shell(action, component)
                val restored = awaitObserved("Provider restoration for $component to $original", { state(component) }, { it == original })
                Log.i("WidgetProviderFixture", "restored $component observed=$restored")
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private fun state(component: ComponentName) = State(context.packageManager.getComponentEnabledSetting(component),
        manager.installedProviders.any { it.provider == component })

    private fun shell(action: String, component: ComponentName) {
        // API31 shell cannot change another package's component state. Only the dedicated test
        // emulator's root is used, as with its protected provider-update acceptance broadcast.
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "su 0 pm $action --user 0 ${component.flattenToString()}")).bufferedReader().use { it.readText() }
        // A command's return/output does not prove AppWidgetService has processed the package event.
    }

    private fun <T> awaitObserved(description: String, observe: () -> T, ready: (T) -> Boolean): T {
        val initial = observe()
        if (ready(initial)) return initial
        Log.i("WidgetProviderFixture", "$description awaiting; observed=$initial")
        val last = AtomicReference(initial)
        val result = CompletableFuture<T>()
        val executor = Executors.newSingleThreadScheduledExecutor()
        val task = executor.scheduleAtFixedRate({
            try {
                val observed = observe()
                last.set(observed)
                if (ready(observed)) result.complete(observed)
            } catch (error: Throwable) { result.completeExceptionally(error) }
        }, 0, 25, TimeUnit.MILLISECONDS)
        try {
            return result.get(8, TimeUnit.SECONDS)
        } catch (error: TimeoutException) {
            throw AssertionError("$description not observed within 8 seconds; last=${last.get()}", error)
        } finally {
            task.cancel(true)
            executor.shutdownNow()
        }
    }
}
