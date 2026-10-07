package com.gezimos.katapult.service

import com.gezimos.katapult.util.SourceGeneration

internal data class BadgeNotification(val key: String, val packageName: String, val postedAt: Long,
                                      val clearable: Boolean, val summary: Boolean)

/** Count-only listener state. Enumeration/cancellation are injected source operations. */
internal class NotificationBadges(
    private val eligible: () -> Boolean,
    private val revision: () -> Long,
    private val enumerate: () -> List<BadgeNotification>?,
    private val dismissible: (String) -> Boolean,
    private val cancel: (Array<String>) -> Unit,
    private val changed: () -> Unit,
) {
    private val state = SourceGeneration<Map<String, BadgeNotification>>()
    private val lock = Any()
    private var seenRevision = revision()
    private val noise = setOf("android", "com.android.systemui", "com.android.settings", "com.google.android.gms",
        "com.mudita.service", "com.mediatek.duraspeed")
    private fun countable(notification: BadgeNotification): Boolean =
        notification.packageName !in noise && notification.clearable && !notification.summary

    fun reconcile() {
        val transitioned = synchronized(lock) {
            val revisionChanged = seenRevision != revision()
            if (revisionChanged) { seenRevision = revision(); state.reconcile(false); state.invalidate() }
            state.reconcile(eligible()) || revisionChanged
        }
        if (!transitioned) return
        changed()
        if (!eligible()) return
        val epoch = state.epoch
        val inputRevision = revision()
        val values = try { enumerate() } catch (_: Exception) { null }
        val published = synchronized(lock) {
            state.publish(epoch, eligible() && inputRevision == revision(), values?.filter(::countable)?.associateBy { it.key })
        }
        if (published) changed()
    }

    fun counts(): Map<String, Int> {
        reconcile()
        if (!eligible()) return emptyMap()
        val values = state.value.orEmpty().values.groupingBy { it.packageName }.eachCount()
        return if (eligible()) values else emptyMap()
    }

    fun times(): Map<String, Long> {
        reconcile()
        if (!eligible()) return emptyMap()
        val times = state.value.orEmpty().values.groupBy { it.packageName }.mapValues { (_, values) -> values.maxOf { it.postedAt } }
        return if (eligible()) times else emptyMap()
    }

    fun posted(read: () -> BadgeNotification) {
        reconcile()
        if (!eligible()) return // The source object is not inspected when collection is off.
        val epoch = state.epoch
        val inputRevision = revision()
        val notification = read()
        val published = synchronized(lock) {
            if (!countable(notification)) false
            else state.publish(epoch, eligible() && inputRevision == revision(), state.value.orEmpty() + (notification.key to notification))
        }
        if (published) changed()
    }

    fun removed(readKey: () -> String) {
        reconcile()
        if (!eligible()) return
        val epoch = state.epoch
        val inputRevision = revision()
        val key = readKey()
        val published = synchronized(lock) {
            state.publish(epoch, eligible() && inputRevision == revision(), state.value.orEmpty() - key)
        }
        if (published) changed()
    }

    fun dismiss(packageName: String) {
        reconcile()
        if (!eligible() || !dismissible(packageName)) return
        val epoch = state.epoch
        val inputRevision = revision()
        val keys = state.value.orEmpty().filterValues { it.packageName == packageName }.keys.toTypedArray()
        if (keys.isEmpty()) return
        // No fabricated clear: OS removal callbacks alone remove cached keys/times.
        synchronized(lock) {
            if (state.epoch != epoch || inputRevision != revision() || !eligible() || !dismissible(packageName)) return
            try { cancel(keys) } catch (_: Exception) { return }
        }
    }

    fun disconnect() {
        synchronized(lock) { state.reconcile(false); state.invalidate() }
        changed()
    }
}
