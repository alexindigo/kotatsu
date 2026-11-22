package com.alexindigo.kotatsu.sync

import android.Manifest
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.gezimos.katapult.ui.LocalPalette
import com.gezimos.katapult.ui.PaperPalette

private object SyncNoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource) = object : Modifier.Node() {}
    override fun hashCode() = 0
    override fun equals(other: Any?) = other === this
}

class SyncSettingsActivity : ComponentActivity() {
    private lateinit var owner: SyncSettingsOwner
    private var setupIntent by mutableStateOf<Intent?>(null)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { owner.refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setWindowAnimations(0)
        val palette = PaperPalette.Light(this)
        window.setBackgroundDrawable(ColorDrawable(palette.paperArgb))
        window.statusBarColor = palette.paperArgb
        window.navigationBarColor = palette.paperArgb
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        SyncRuntime.start(this)
        owner = SyncSettingsOwner(this)
        setupIntent = resolveSetUp()
        setContent {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides this@SyncSettingsActivity,
                LocalIndication provides SyncNoIndication, LocalPalette provides palette) {
                Box(Modifier.fillMaxSize().background(palette.paper).statusBarsPadding().navigationBarsPadding()) {
                    SyncSettingsBook(owner, ::finish, { calendar ->
                        permission.launch(if (calendar) arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                            else arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))
                    }, setupIntent?.let { intent -> { startActivity(intent) } })
                }
            }
        }
        findViewById<ViewGroup>(android.R.id.content).apply {
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            requestFocus()
        }
    }

    private fun resolveSetUp(): Intent? = listOfNotNull(
        packageManager.getLaunchIntentForPackage("at.bitfire.davdroid"),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/at.bitfire.davdroid/")),
    ).firstOrNull { it.resolveActivity(packageManager) != null }

    override fun onResume() { super.onResume(); setupIntent = resolveSetUp(); owner.resume() }
    override fun onPause() { owner.pause(); super.onPause() }
}
