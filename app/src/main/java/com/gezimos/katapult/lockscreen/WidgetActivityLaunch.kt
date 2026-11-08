package com.gezimos.katapult.lockscreen

import android.accessibilityservice.AccessibilityService
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.gezimos.katapult.R

/** Bounded causal observation, not a replacement PendingIntent sender or an activity interceptor. */
internal class WidgetActivityLaunch(private val service: AccessibilityService,
                                    private val eligible: () -> Boolean, private val closePage: () -> Unit) : AutoCloseable {
    private val delivery = ReplayDelivery(AndroidEventDelivery(service)) {
        android.util.Log.e("WidgetActivityLaunch", "Could not restore narrow event delivery", it)
    }
    private var deliveryToken: Long? = null
    private var open = false
    private var tapped: Long? = null
    private var routed = false

    fun pageVisible(value: Boolean) {
        if (open == value) return
        tapped = null; routed = false
        if (value) { deliveryToken = delivery.begin(); open = true }
        else { open = false; deliveryToken?.let(delivery::end); deliveryToken = null }
    }
    fun dispatched() { if (open) { tapped = SystemClock.uptimeMillis(); routed = false } }
    fun event(event: AccessibilityEvent?) {
        val at = tapped ?: return
        if (!open || routed || event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            SystemClock.uptimeMillis() - at !in 0..3000) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        if (pkg == "com.android.systemui" || pkg == service.packageName) return
        val component = ComponentName(pkg, if (cls.startsWith('.')) pkg + cls else cls)
        val info = try { service.packageManager.getActivityInfo(component, 0) } catch (_: Exception) { return }
        if (!info.enabled || !info.applicationInfo.enabled || !eligible()) return
        routed = true
        closePage()
        // Consume the freshly validated event in this same Main transaction. Removing our
        // focused page invalidates its native root ownership; a later bare-root check would
        // be observing our own transition, not validating the original activity launch.
        try { service.startActivity(Intent(service, WidgetUnlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) }
        catch (error: RuntimeException) { android.util.Log.w("WidgetActivityLaunch", "Could not request widget unlock", error) }
    }
    override fun close() {
        pageVisible(false)
    }
}

/** Only asks Android to dismiss keyguard. The widget's original activity is already behind it. */
class WidgetUnlockActivity : ComponentActivity() {
    private var requested = false
    private var finished = false
    private var resumed = false
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { if (intent.action == Intent.ACTION_SCREEN_OFF) done(true) }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setShowWhenLocked(true)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = done(true)
        })
        window.setWindowAnimations(0)
        setContentView(TextView(this).apply {
            text = getString(R.string.side_unlock); textSize = 22f
            typeface = ResourcesCompat.getFont(this@WidgetUnlockActivity, R.font.lato_regular)
            setTextColor(Color.BLACK); setBackgroundColor(Color.WHITE)
            setPadding(16, 16, 16, 16)
        })
        registerReceiver(screen, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }
    override fun onPostResume() {
        super.onPostResume()
        resumed = true
        requestUnlock()
    }
    override fun onPause() {
        resumed = false
        super.onPause()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) requestUnlock()
    }
    private fun requestUnlock() {
        // Resuming precedes the native show-when-locked window/occlusion transition.
        // Requesting before focus lets that transition reset and cancel the new bouncer.
        if (requested || finished || !resumed || !window.decorView.hasWindowFocus()) return
        if (!getSystemService(PowerManager::class.java).isInteractive) { done(true); return }
        requested = true
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = done(false)
                override fun onDismissCancelled() = done(true)
                override fun onDismissError() = done(true)
            })
    }
    private fun done(home: Boolean) {
        if (finished) return
        finished = true
        if (home) startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finishAndRemoveTask()
    }
    override fun onDestroy() { unregisterReceiver(screen); super.onDestroy() }
}
