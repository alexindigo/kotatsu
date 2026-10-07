package com.gezimos.katapult.util

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@SuppressLint("StaticFieldLeak")
class AudioWidgetHelper internal constructor(private val context: Context,
    private val access: MediaAccess, private val sessions: MediaSessions) : AutoCloseable {

    data class MediaInfo(
        val packageName: String,
        val isPlaying: Boolean,
        val title: String?,
        val artist: String?,
        val albumArt: Bitmap?,
        val controlsAllowed: Boolean,
    )

    private val _state = MutableStateFlow<MediaInfo?>(null)
    val state: StateFlow<MediaInfo?> = _state

    private var sessionSubscription: AutoCloseable? = null
    private var initializedFor: ComponentName? = null
    private var currentController: MediaController? = null
    private var currentCallback: MediaController.Callback? = null
    private var userDismissed = false
    private var lastControllers: List<MediaController> = emptyList()
    private val watchers = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var dismissedPackageName: String? = null
    private var connectedComponent: ComponentName? = null
    private var collectionEpoch = 0L
    private val display = SourceGeneration<MediaInfo>()
    private val sessionEpoch: Long get() = display.epoch
    private var seenRevision = access.revision()
    private var closed = false
    private val policySubscription: AutoCloseable

    init {
        policySubscription = access.subscribe(::reconcile)
    }

    companion object {
        @Volatile
        private var INSTANCE: AudioWidgetHelper? = null

        fun getInstance(context: Context): AudioWidgetHelper {
            SensitiveAccess.initialize(context)
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AudioWidgetHelper(context.applicationContext, AndroidMediaAccess,
                    AndroidMediaSessions(context.applicationContext)).also { INSTANCE = it }
            }
        }
    }

    fun initialize(componentName: ComponentName) {
        connectedComponent = componentName
        reconcile()
    }

    fun reconcile() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { reconcile() }
            return
        }
        val componentName = connectedComponent
        if (seenRevision != access.revision()) {
            seenRevision = access.revision()
            clearCollection()
        }
        if (closed || !access.metadataAllowed() || componentName == null) {
            clearCollection()
            return
        }
        display.reconcile(true)
        if (sessionSubscription == null || initializedFor != componentName) {
            sessionSubscription?.let { old ->
                try { old.close() } catch (_: Exception) {}
            }
            val epoch = ++collectionEpoch
            initializedFor = componentName
            try {
                sessionSubscription = sessions.subscribe(componentName) { controllers ->
                    if (epoch == collectionEpoch && access.metadataAllowed()) handleSessionsChanged(controllers)
                    else if (!access.metadataAllowed()) clearCollection()
                }
            }
            catch (_: Exception) { clearCollection(); return }
        }

        if (!access.metadataAllowed()) { clearCollection(); return }
        try { handleSessionsChanged(sessions.enumerate(componentName)) }
        catch (_: Exception) { clearCollection() }
    }

    fun cleanup() {
        connectedComponent = null
        clearCollection()
    }

    override fun close() {
        closed = true
        policySubscription.close()
        cleanup()
    }

    private fun clearCollection() {
        collectionEpoch++
        display.reconcile(false)
        _state.value = null
        sessionSubscription?.let { subscription ->
            try { subscription.close() } catch (_: Exception) {}
        }
        sessionSubscription = null
        initializedFor = null
        unwatchAll()
        unregisterCallback()
        userDismissed = false
        dismissedPackageName = null
    }

    private fun handleSessionsChanged(controllers: List<MediaController>?) {
        if (!access.metadataAllowed()) { clearCollection(); return }
        lastControllers = controllers.orEmpty()
        watchAll(lastControllers)
        val active = controllers?.firstOrNull { c ->
            val s = c.playbackState?.state
            s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_PAUSED
        }

        if (active != null) {
            if (userDismissed &&
                active.packageName == dismissedPackageName &&
                active.playbackState?.state != PlaybackState.STATE_PLAYING
            ) return

            if (active.playbackState?.state == PlaybackState.STATE_PLAYING) {
                userDismissed = false
                dismissedPackageName = null
            }

            registerCallback(active)
            updateState(active)
        } else {
            unregisterCallback()
            _state.value = null
        }
    }

    private fun registerCallback(controller: MediaController) {
        if (currentController?.sessionToken == controller.sessionToken) {
            // Same session — just update state, callback already registered
            return
        }
        unregisterCallback()
        val epoch = sessionEpoch
        val callback = object : MediaController.Callback() {
            private fun current(): Boolean {
                if (!access.metadataAllowed()) { clearCollection(); return false }
                return epoch == sessionEpoch && currentController?.sessionToken == controller.sessionToken
            }
            override fun onMetadataChanged(metadata: MediaMetadata?) { if (current()) updateState(controller) }
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (!current()) return
                if (state?.state == PlaybackState.STATE_PLAYING) {
                    userDismissed = false
                    dismissedPackageName = null
                }
                if (state?.state == PlaybackState.STATE_STOPPED) publishDisplay(epoch, null)
                else updateState(controller)
            }
            override fun onSessionDestroyed() {
                if (!current()) return
                _state.value = null
                lastControllers = lastControllers.filter { it.sessionToken != controller.sessionToken }
                unregisterCallback()
                handleSessionsChanged(lastControllers)
            }
        }

        try {
            controller.registerCallback(callback)
            currentController = controller
            currentCallback = callback
        } catch (_: Exception) {}
    }

    private fun watchAll(controllers: List<MediaController>) {
        if (!access.metadataAllowed()) return
        val tokens = controllers.map { it.sessionToken }.toSet()
        watchers.keys.filter { it !in tokens }.forEach { token ->
            watchers.remove(token)?.let { (c, cb) ->
                try { c.unregisterCallback(cb) } catch (_: Exception) {}
            }
        }
        controllers.forEach { controller ->
            val token = controller.sessionToken
            if (token in watchers) return@forEach
            val epoch = collectionEpoch
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    if (!access.metadataAllowed()) { clearCollection(); return }
                    if (epoch != collectionEpoch || watchers[token]?.second !== this) return
                    val s = state?.state
                    if ((s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_PAUSED) &&
                        currentController?.sessionToken != token
                    ) handleSessionsChanged(lastControllers)
                }
            }
            try {
                controller.registerCallback(callback)
                watchers[token] = controller to callback
            } catch (_: Exception) {}
        }
    }

    private fun unwatchAll() {
        watchers.values.forEach { (c, cb) ->
            try { c.unregisterCallback(cb) } catch (_: Exception) {}
        }
        watchers.clear()
        lastControllers = emptyList()
    }

    private fun unregisterCallback() {
        display.invalidate()
        _state.value = null
        currentCallback?.let { cb ->
            try { currentController?.unregisterCallback(cb) } catch (_: Exception) {}
        }
        currentCallback = null
        currentController = null
    }

    private fun updateState(controller: MediaController) {
        if (!access.metadataAllowed() || currentController?.sessionToken != controller.sessionToken) {
            if (!access.metadataAllowed()) clearCollection()
            return
        }
        val epoch = sessionEpoch
        if (userDismissed && controller.playbackState?.state != PlaybackState.STATE_PLAYING) return
        val playback = controller.playbackState?.state
        if (playback != PlaybackState.STATE_PLAYING && playback != PlaybackState.STATE_PAUSED) {
            publishDisplay(epoch, null)
            return
        }
        val metadata = controller.metadata
        val isPlaying = playback == PlaybackState.STATE_PLAYING
        val sourceArt = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val art = try { sourceArt?.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Exception) { null }
        if (!access.metadataAllowed() || epoch != sessionEpoch || currentController?.sessionToken != controller.sessionToken) return
        val info = MediaInfo(
            packageName = controller.packageName,
            isPlaying = isPlaying,
            title = metadata?.description?.title?.toString(),
            artist = metadata?.description?.subtitle?.toString(),
            albumArt = art,
            controlsAllowed = access.controlsAllowed(),
        )
        publishDisplay(epoch, info)
    }

    private fun publishDisplay(epoch: Long, info: MediaInfo?) {
        if (display.publish(epoch, access.metadataAllowed(), info)) _state.value = display.value
    }

    private fun sendKey(controller: MediaController, keyCode: Int): Boolean {
        val epoch = sessionEpoch
        val revision = access.actionRevision()
        fun allowed(): Boolean = access.controlsAllowed() && epoch == sessionEpoch &&
            revision == access.actionRevision() && currentController?.sessionToken == controller.sessionToken && _state.value != null
        if (!allowed()) return false
        val down = MediaPolicy.command(::allowed) { controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode)) }
        if (!allowed()) return false
        val sent = MediaPolicy.command(::allowed) { controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode)) } && down
        if (!sent) {
            if (!allowed()) return false
            val t = controller.transportControls
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PAUSE -> t.pause()
                KeyEvent.KEYCODE_MEDIA_PLAY -> t.play()
                KeyEvent.KEYCODE_MEDIA_NEXT -> t.skipToNext()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> t.skipToPrevious()
                KeyEvent.KEYCODE_MEDIA_STOP -> t.stop()
            }
        }
        return true
    }

    fun playPause(): Boolean {
        reconcile()
        if (!access.controlsAllowed()) return false
        val controller = currentController ?: return false
        return try {
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING)
                sendKey(controller, KeyEvent.KEYCODE_MEDIA_PAUSE)
            else
                sendKey(controller, KeyEvent.KEYCODE_MEDIA_PLAY)
        } catch (_: Exception) { false }
    }

    fun skipNext(): Boolean {
        reconcile()
        if (!access.controlsAllowed()) return false
        val controller = currentController ?: return false
        return try { sendKey(controller, KeyEvent.KEYCODE_MEDIA_NEXT) } catch (_: Exception) { false }
    }

    fun skipPrevious(): Boolean {
        reconcile()
        if (!access.controlsAllowed()) return false
        val controller = currentController ?: return false
        return try { sendKey(controller, KeyEvent.KEYCODE_MEDIA_PREVIOUS) } catch (_: Exception) { false }
    }

    fun stop(): Boolean {
        reconcile()
        if (!access.controlsAllowed()) return false
        val controller = currentController ?: return false
        return try {
            if (!sendKey(controller, KeyEvent.KEYCODE_MEDIA_STOP)) return false
            dismiss()
            true
        } catch (_: Exception) { false }
    }

    fun openApp(): Boolean {
        reconcile()
        if (!access.metadataAllowed()) return false
        val pkg = _state.value?.packageName ?: return false
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            intent?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
                true
            } ?: false
        } catch (_: Exception) { false }
    }

    fun dismiss() {
        userDismissed = true
        dismissedPackageName = currentController?.packageName ?: _state.value?.packageName
        publishDisplay(sessionEpoch, null)
    }

    fun resetDismissalState() {
        reconcile()
    }
}

internal interface MediaAccess {
    fun metadataAllowed(): Boolean
    fun controlsAllowed(): Boolean
    fun revision(): Long
    fun actionRevision(): Long
    fun subscribe(changed: () -> Unit): AutoCloseable
}

internal interface MediaSessions {
    fun enumerate(component: ComponentName): List<MediaController>
    fun subscribe(component: ComponentName, changed: (List<MediaController>?) -> Unit): AutoCloseable
}

private object AndroidMediaAccess : MediaAccess {
    override fun metadataAllowed() = SensitiveAccess.mediaInfo()
    override fun controlsAllowed() = SensitiveAccess.mediaControls()
    override fun revision() = SensitiveAccess.mediaRevision
    override fun actionRevision() = SensitiveAccess.mediaActionRevision
    override fun subscribe(changed: () -> Unit) = SensitiveAccess.subscribe(changed)
}

private class AndroidMediaSessions(context: Context) : MediaSessions {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    override fun enumerate(component: ComponentName): List<MediaController> = manager.getActiveSessions(component)
    override fun subscribe(component: ComponentName, changed: (List<MediaController>?) -> Unit): AutoCloseable {
        val listener = MediaSessionManager.OnActiveSessionsChangedListener(changed)
        manager.addOnActiveSessionsChangedListener(listener, component)
        return AutoCloseable { manager.removeOnActiveSessionsChangedListener(listener) }
    }
}
