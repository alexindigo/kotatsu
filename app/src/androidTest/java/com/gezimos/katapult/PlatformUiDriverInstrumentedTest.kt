package com.gezimos.katapult

import android.app.AlertDialog
import android.content.Intent
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class PlatformUiDriverInstrumentedTest {
    @get:org.junit.Rule val nativeState = ConnectedUiStateRule()

    @Test fun appAwaitsNeverApproveAnUnrelatedAndroidConfirmation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val ui = PlatformUiDriver(instrumentation)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) as MainActivity
        var approved = false
        var dialog: AlertDialog? = null
        withFixtureCleanup(body = {
            fixtureMain {
                activity.setContentView(TextView(activity).apply { text = "Underlying app content" })
                val confirmation = AlertDialog.Builder(activity).setTitle("Keep this confirmation")
                    .setPositiveButton("Approve") { _, _ -> approved = true }.create()
                dialog = confirmation
                confirmation.show()
                // Even a matching Android OK id is insufficient without the tutorial owner.
                val okId = context.resources.getIdentifier("ok", "id", "android")
                check(okId != 0)
                confirmation.getButton(AlertDialog.BUTTON_POSITIVE).id = okId
            }
            ui.await("The confirmation did not own input") { ui.has("Keep this confirmation") }
            assertFalse(approved)
            assertFalse(ui.has("Underlying app content"))
            assertTrue(fixtureMain { dialog?.isShowing == true })
        }, actions = arrayOf<() -> Unit>(
            { fixtureMain { dialog?.dismiss(); activity.finish() } },
        ))
    }
}
