package com.gezimos.katapult

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.util.AudioWidgetHelper
import com.gezimos.katapult.util.MediaAccess
import com.gezimos.katapult.util.MediaSessions
import org.junit.Assert.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal object MediaAdapterCases {
    /** Local inputs only, never change the app singleton's grant/connection flags. */
    class Access : MediaAccess {
        var metadata = false
        var controls = false
        var generation = 0L
        var actionGeneration = 0L
        val listeners = CopyOnWriteArrayList<() -> Unit>()
        override fun metadataAllowed() = metadata
        override fun controlsAllowed() = metadata && controls
        override fun revision() = generation
        override fun actionRevision() = actionGeneration
        override fun subscribe(changed: () -> Unit): AutoCloseable {
            listeners.add(changed)
            return AutoCloseable { listeners.remove(changed) }
        }
        fun changed() { listeners.forEach { it() } }
    }

    class Sessions : MediaSessions {
        var controllers = emptyList<MediaController>()
        var enumerations = 0
        val listeners = mutableListOf<(List<MediaController>?) -> Unit>()
        override fun enumerate(component: ComponentName): List<MediaController> {
            enumerations++
            return controllers
        }
        override fun subscribe(component: ComponentName, changed: (List<MediaController>?) -> Unit): AutoCloseable {
            listeners.add(changed)
            return AutoCloseable { listeners.remove(changed) }
        }
    }

    fun realOwnSessionThroughInjectedEnumerationAndAccess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val access = Access()
        val sessions = Sessions()
        val commands = AtomicInteger()
        lateinit var helper: AudioWidgetHelper
        lateinit var session: MediaSession
        lateinit var controller: MediaController
        var replacement: MediaSession? = null
        fun metadata(title: String) = MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).build()
        instrumentation.runOnMainSync {
            session = MediaSession(context, "kotatsu-synthetic-policy-session")
            session.setCallback(object : MediaSession.Callback() {
                override fun onSkipToNext() { commands.incrementAndGet() }
                override fun onSkipToPrevious() { commands.incrementAndGet() }
                override fun onPlay() { commands.incrementAndGet() }
                override fun onPause() { commands.incrementAndGet() }
                override fun onStop() { commands.incrementAndGet() }
            })
            session.setMetadata(metadata("Synthetic initial title"))
            session.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0, 1f)
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP).build())
            session.isActive = true
            controller = MediaController(context, session.sessionToken)
            sessions.controllers = listOf(controller)
            helper = AudioWidgetHelper(context, access, sessions)
            helper.initialize(ComponentName(context, com.gezimos.katapult.service.NotificationListener::class.java))
        }
        try {
            assertEquals(0, sessions.enumerations)
            assertNull(helper.state.value)
            instrumentation.runOnMainSync {
                access.metadata = true; access.generation++; access.changed()
                assertEquals("Synthetic initial title", helper.state.value?.title)
                assertFalse(helper.state.value!!.controlsAllowed)
                assertFalse(helper.skipNext()); assertFalse(helper.skipPrevious()); assertFalse(helper.playPause()); assertFalse(helper.stop())
            }
            SystemClock.sleep(100)
            assertEquals(0, commands.get())
            val oldListener = sessions.listeners.single()
            instrumentation.runOnMainSync {
                access.metadata = false; access.generation++; access.changed()
                assertNull(helper.state.value)
                assertTrue(sessions.listeners.isEmpty())
                val reads = sessions.enumerations
                session.setMetadata(metadata("Synthetic late callback"))
                oldListener(listOf(controller))
                assertNull(helper.state.value)
                assertEquals(reads, sessions.enumerations)
            }
            instrumentation.waitForIdleSync()
            assertNull(helper.state.value)
            instrumentation.runOnMainSync {
                access.metadata = true; access.generation++; access.changed()
                assertEquals("Synthetic late callback", helper.state.value?.title)
                access.controls = true; access.actionGeneration++; access.changed()
                assertTrue(helper.state.value!!.controlsAllowed)
                assertTrue(helper.skipNext())
            }
            val end = SystemClock.uptimeMillis() + 8000
            while (commands.get() == 0 && SystemClock.uptimeMillis() < end) SystemClock.sleep(20)
            assertTrue("Transport never reached this owned synthetic MediaSession", commands.get() > 0)
            instrumentation.runOnMainSync {
                val next = MediaSession(context, "kotatsu-synthetic-replacement-session")
                replacement = next
                next.setMetadata(metadata("Synthetic replacement title"))
                next.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0, 1f).build())
                next.isActive = true
                sessions.controllers = listOf(MediaController(context, next.sessionToken))
                sessions.listeners.single()(sessions.controllers)
                assertEquals("Synthetic replacement title", helper.state.value?.title)
                session.setMetadata(metadata("Synthetic obsolete session title"))
            }
            instrumentation.waitForIdleSync()
            assertEquals("Synthetic replacement title", helper.state.value?.title)
            instrumentation.runOnMainSync {
                replacement!!.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 0f).build())
            }
            val stopped = SystemClock.uptimeMillis() + 8000
            while (helper.state.value != null && SystemClock.uptimeMillis() < stopped) SystemClock.sleep(20)
            assertNull(helper.state.value)
            instrumentation.runOnMainSync { replacement!!.setMetadata(metadata("Synthetic stopped title")) }
            instrumentation.waitForIdleSync()
            assertNull("Stopped session metadata repopulated the display", helper.state.value)
            instrumentation.runOnMainSync {
                access.controls = false; access.actionGeneration++; access.changed()
                assertNull(helper.state.value)
                helper.cleanup()
                assertNull(helper.state.value)
                assertTrue(sessions.listeners.isEmpty())
            }
            instrumentation.waitForIdleSync()
            assertNull(helper.state.value)
        } finally {
            instrumentation.runOnMainSync { helper.close(); helper.close(); session.release(); replacement?.release() }
            assertTrue(access.listeners.isEmpty())
        }
    }
}
