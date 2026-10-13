package com.gezimos.katapult.util

import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** App choices are independent of Android grants and optional feature selection. */
enum class SensitiveChoice(val key: String) {
    SMS_BADGES("collect_sms_badges"),
    CALL_BADGES("collect_call_badges"),
    NOTIFICATION_BADGES("collect_notification_badges"),
    MEDIA_INFO("collect_media_info"),
    USAGE_STATS("collect_usage_stats"),
    NOTIFICATION_DISMISSAL("allow_notification_dismissal"),
    CALL_ACKNOWLEDGMENT("allow_call_acknowledgment"),
    MEDIA_CONTROLS("allow_media_controls"),
    ACCESSIBILITY_FEATURES("allow_accessibility_features"),
}

object SensitivePolicy {
    fun choice(values: Map<String, *>, choice: SensitiveChoice): Boolean =
        values[choice.key] as? Boolean ?: true

    fun eligible(choice: Boolean, supported: Boolean, granted: Boolean, demanded: Boolean): Boolean =
        choice && supported && granted && demanded

    fun effectiveChoices(values: Map<String, *>): Map<String, Boolean> =
        SensitiveChoice.entries.associate { it.key to choice(values, it) }

    /** Validate all present keys even if no matching preference has ever been stored. */
    fun importedChoices(values: Map<String, *>): Map<String, Boolean> {
        val result = mutableMapOf<String, Boolean>()
        for (choice in SensitiveChoice.entries) {
            if (!values.containsKey(choice.key)) continue
            require(values[choice.key] is Boolean) { "Policy choice must be a Boolean: ${choice.key}" }
            result[choice.key] = values[choice.key] as Boolean
        }
        return result
    }

    fun importChoices(local: Map<String, Boolean>, incoming: Map<String, Boolean>,
                      confirmedLocal: Map<String, Boolean>?): Map<String, Boolean> {
        if (confirmedLocal == null) return local.toMap()
        require(confirmedLocal == local) { "Local sensitive choices changed since confirmation" }
        return local + incoming
    }
}

enum class BadgeSource { DIRECT_CALLS, DIRECT_SMS, NOTIFICATIONS, UNAVAILABLE }
data class BadgeRoute(val source: BadgeSource, val count: Int)

object BadgeRouting {
    /** An authoritative direct zero suppresses fallback; an unavailable result does not. */
    fun select(directSource: BadgeSource?, direct: Int?, notifications: Int?, notificationEligible: Boolean): BadgeRoute = when {
        directSource != null && direct != null -> BadgeRoute(directSource, direct)
        notificationEligible -> BadgeRoute(BadgeSource.NOTIFICATIONS, notifications ?: 0)
        else -> BadgeRoute(BadgeSource.UNAVAILABLE, 0)
    }

    fun canClear(route: BadgeRoute, notificationDismissal: Boolean, callAcknowledgment: Boolean): Boolean =
        route.count > 0 && when (route.source) {
            BadgeSource.DIRECT_CALLS -> callAcknowledgment
            BadgeSource.NOTIFICATIONS -> notificationDismissal
            else -> false // No SMS writes and no cosmetic data fabrication.
        }
}

/** Identity-owned, idempotent leases. Notifications always execute outside the lock. */
class OwnerLeases(private val changed: () -> Unit) {
    private val lock = Any()
    private val owners = IdentityHashMap<Any, AutoCloseable>()
    val demanded: Boolean get() = synchronized(lock) { owners.isNotEmpty() }
    val ownerCount: Int get() = synchronized(lock) { owners.size }

    fun acquire(owner: Any): AutoCloseable {
        var added = false
        val lease = synchronized(lock) {
            owners[owner] ?: run {
                val closed = AtomicBoolean(false)
                val created = AutoCloseable {
                    if (closed.compareAndSet(false, true)) {
                        synchronized(lock) { owners.remove(owner) }
                        changed()
                    }
                }
                owners[owner] = created
                added = true
                created
            }
        }
        if (added) changed()
        return lease
    }
}

/** Null means unavailable, never an authoritative empty provider result. */
class SourceGeneration<T> {
    private val lock = Any()
    private var generation = 0L
    private var active = false
    private var result: T? = null
    val value: T? get() = synchronized(lock) { if (active) result else null }
    val epoch: Long get() = synchronized(lock) { generation }
    val enabled: Boolean get() = synchronized(lock) { active }

    fun reconcile(eligible: Boolean): Boolean = synchronized(lock) {
        if (eligible == active) return@synchronized false
        generation++ // Invalidate first, before the adapter detaches or cancels.
        active = eligible
        result = null
        true
    }

    fun invalidate(): Long = synchronized(lock) {
        generation++
        result = null
        generation
    }

    fun publish(epoch: Long, eligibleNow: Boolean, value: T?): Boolean = synchronized(lock) {
        if (!active || !eligibleNow || epoch != generation) return@synchronized false
        result = value
        true
    }
}

class SourceRead<T>(private val state: SourceGeneration<T>) {
    fun run(epoch: Long, eligible: () -> Boolean, reader: () -> T?): Boolean {
        if (!state.enabled || state.epoch != epoch || !eligible()) return false
        val result = reader()
        return state.publish(epoch, eligible(), result)
    }
}

object MediaPolicy {
    fun metadata(choice: Boolean, access: Boolean, demand: Boolean): Boolean = choice && access && demand
    fun controls(metadataEligible: Boolean, choice: Boolean): Boolean = metadataEligible && choice
    fun command(eligible: () -> Boolean, action: () -> Boolean): Boolean = if (eligible()) action() else false
}

/** Request IDs belong to deliberate actions, not collection/lifecycle generations. */
class ActionRequests {
    data class Request(val id: Long, val packageName: String, val source: BadgeSource, val accessRevision: Long = 0)
    private var epoch = 0L
    private var pending: Request? = null

    @Synchronized fun begin(packageName: String, source: BadgeSource, accessRevision: Long = 0): Request =
        Request(++epoch, packageName, source, accessRevision).also { pending = it }

    @Synchronized fun cancel() { epoch++; pending = null }
    @Synchronized fun current(id: Long): Boolean = id == epoch
    @Synchronized fun awaitingResult(id: Long): Boolean = pending?.id == id && id == epoch
    @Synchronized fun complete(id: Long) { if (id == epoch) { epoch++; pending = null } }

    @Synchronized fun consume(id: Long): Request? {
        val request = pending?.takeIf { it.id == id && it.id == epoch } ?: return null
        pending = null
        return request
    }
}

/** Deliberate badge actions retain their own demand, separate from UI collection pauses. */
class BadgeActions(
    private val route: (String) -> BadgeRoute,
    private val dismissAllowed: () -> Boolean,
    private val callChoiceAllowed: () -> Boolean,
    private val callWriteGranted: () -> Boolean,
    private val revision: () -> Long,
    private val acquire: () -> AutoCloseable,
    private val dismiss: (String) -> Unit,
    private val acknowledge: (stillCurrent: () -> Boolean, finished: () -> Unit) -> Unit,
) : AutoCloseable {
    private val requests = ActionRequests()
    private var lease: AutoCloseable? = null
    private var pending: ActionRequests.Request? = null

    fun request(packageName: String): Long? {
        close()
        val selected = route(packageName)
        if (!BadgeRouting.canClear(selected, dismissAllowed(), callChoiceAllowed())) return null
        if (selected.source == BadgeSource.NOTIFICATIONS) {
            if (dismissAllowed() && route(packageName).source == BadgeSource.NOTIFICATIONS) dismiss(packageName)
            return null
        }
        val request = requests.begin(packageName, BadgeSource.DIRECT_CALLS, revision())
        pending = request
        // A different lease per deliberate action prevents an old completion
        // from releasing the demand owned by a newer request.
        lease = acquire()
        if (callWriteGranted()) { requests.consume(request.id); execute(request); return null }
        return request.id
    }

    fun result(id: Long?, granted: Boolean) {
        val request = id?.let { requests.consume(it) } ?: return
        if (!granted) { close(); return }
        // The first grant itself changes AppOps. Bind queued work to access at
        // the permission result, not the revision before that dialog opened.
        val grantedRequest = request.copy(accessRevision = revision())
        pending = grantedRequest
        execute(grantedRequest)
    }

    fun reconcile() {
        val request = pending ?: return
        if (!callChoiceAllowed() || (!requests.awaitingResult(request.id) && request.accessRevision != revision()) ||
            route(request.packageName).source != BadgeSource.DIRECT_CALLS) close()
    }

    private fun execute(request: ActionRequests.Request) {
        val ownedLease = lease ?: return
        fun current(): Boolean = requests.current(request.id) && request.accessRevision == revision() &&
            callChoiceAllowed() && callWriteGranted() && route(request.packageName).source == BadgeSource.DIRECT_CALLS
        if (!current()) { close(); return }
        acknowledge(::current) {
            requests.complete(request.id)
            if (lease === ownedLease) { lease = null; pending = null }
            ownedLease.close()
        }
    }

    override fun close() {
        requests.cancel()
        pending = null
        val owned = lease; lease = null; owned?.close()
    }
}

data class AccessibilitySelection(val widget: Boolean = false, val media: Boolean = false,
    val message: Boolean = false, val clockCover: Boolean = false, val doubleTapLock: Boolean = false,
    val screensaver: Boolean = false, val powerScreensaver: Boolean = false)

data class AccessibilityDemand(val badges: Boolean, val media: Boolean, val inspectPin: Boolean,
    val clockCover: Boolean, val lockAction: Boolean, val powerEntry: Boolean)

enum class AccessibilityFeature { WIDGET, MEDIA, MESSAGE, CLOCK_COVER, DOUBLE_TAP_LOCK, POWER_SCREENSAVER }

object AccessibilityPolicy {
    fun serviceFeatures(selection: AccessibilitySelection): List<AccessibilityFeature> = buildList {
        if (selection.widget) add(AccessibilityFeature.WIDGET)
        if (selection.media) add(AccessibilityFeature.MEDIA)
        if (selection.message) add(AccessibilityFeature.MESSAGE)
        if (selection.clockCover) add(AccessibilityFeature.CLOCK_COVER)
        if (selection.doubleTapLock) add(AccessibilityFeature.DOUBLE_TAP_LOCK)
        if (selection.screensaver && selection.powerScreensaver) add(AccessibilityFeature.POWER_SCREENSAVER)
    }

    fun isPinField(packageName: CharSequence?, visible: Boolean, className: CharSequence?): Boolean =
        packageName?.toString() == "com.android.systemui" && visible && className?.contains("EditText") == true

    /** Failed inspection cannot establish that it is safe to show overlays. */
    fun pinShowing(inspect: () -> Boolean): Boolean = try { inspect() } catch (_: Exception) { true }

    fun demand(eligible: Boolean, selection: AccessibilitySelection, lockscreenActive: Boolean,
               launcherActive: Boolean, screensaverActive: Boolean): AccessibilityDemand = AccessibilityDemand(
        badges = eligible && lockscreenActive && selection.widget,
        media = eligible && lockscreenActive && selection.media,
        inspectPin = eligible && lockscreenActive && (selection.widget || selection.media || selection.message),
        clockCover = eligible && selection.clockCover && (launcherActive || screensaverActive),
        lockAction = eligible && ((selection.doubleTapLock && launcherActive) || (selection.screensaver && screensaverActive)),
        powerEntry = eligible && selection.screensaver && selection.powerScreensaver,
    )
}

/** Source ownership and teardown for the service's selected active consumers. */
class AccessibilityConsumers(
    private val acquireBadges: () -> AutoCloseable,
    private val acquireMedia: () -> AutoCloseable,
    private val subscriptions: (badges: Boolean, media: Boolean) -> Unit,
    private val clearLockscreen: () -> Unit,
    private val clearInspection: () -> Unit,
) : AutoCloseable {
    private var badgeLease: AutoCloseable? = null
    private var mediaLease: AutoCloseable? = null
    val badgesActive: Boolean get() = badgeLease != null
    val mediaActive: Boolean get() = mediaLease != null
    var inspectionActive: Boolean = false
        private set

    fun reconcile(demand: AccessibilityDemand) {
        inspectionActive = demand.inspectPin
        if (demand.badges && badgeLease == null) badgeLease = acquireBadges()
        else if (!demand.badges) { val old = badgeLease; badgeLease = null; old?.close() }
        if (demand.media && mediaLease == null) mediaLease = acquireMedia()
        else if (!demand.media) { val old = mediaLease; mediaLease = null; old?.close() }
        subscriptions(demand.badges, demand.media)
        if (!demand.inspectPin) clearInspection()
        if (!demand.badges && !demand.media && !demand.inspectPin) clearLockscreen()
    }

    override fun close() = reconcile(AccessibilityDemand(false, false, false, false, false, false))
}

/** Eligibility is checked before cache/query and after the query, not just by its caller. */
class PolicyCache<T>(private val ttlMillis: Long) {
    private var epoch = 0L
    private var value: T? = null
    private var at = 0L
    @Synchronized fun invalidate() { epoch++; value = null; at = 0L }

    fun read(now: Long, eligible: () -> Boolean, query: () -> T?): T? {
        if (!eligible()) { invalidate(); return null }
        val generation: Long
        val cached: T?
        synchronized(this) { generation = epoch; cached = if (now - at < ttlMillis) value else null }
        if (cached != null && eligible() && synchronized(this) { epoch == generation }) return cached
        if (!eligible()) { invalidate(); return null }
        val result = query()
        synchronized(this) {
            if (generation != epoch || !eligible()) return null
            value = result; at = now
        }
        return if (eligible() && synchronized(this) { generation == epoch }) result else { invalidate(); null }
    }
}
